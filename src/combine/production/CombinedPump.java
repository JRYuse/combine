package combine.production;
import combine.net.ComboNet;
import combine.util.ComboUi;
import combine.util.ComboReflect;
import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.struct.IntSet;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.type.Liquid;
import mindustry.type.Item;
import mindustry.type.LiquidStack;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeLiquid;
import mindustry.world.consumers.ConsumeLiquids;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Pump;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.*;

/**
 * 组合泵 —— 相邻同型自动组合，共享液体池（总容量 = Σ 单台容量）。
 * 每台泵往共享池注入自己地格的液体，池子由全体成员向外输出。
 */
public class CombinedPump extends Pump {
    public boolean allowCrossTypeCombo = true;
    public float baseLiquidCapacity = 10f;
    public float displayLiquid;

    public Seq<Liquid> cachedLiquids = new Seq<>();

    public CombinedPump(String name) {
        super(name);
        hasLiquids = true;
        update = true;
        solid = true;
        sync = true;
    }

    @Override
    public void init() {
        super.init();
        if (liquidCapacity != 9999f) {
            baseLiquidCapacity = liquidCapacity;
            displayLiquid = baseLiquidCapacity;
        }
        liquidCapacity = 9999f;
        hasLiquids = true;

        conductivePower = true;
        cachedLiquids.clear();
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.remove(Stat.liquidCapacity);
        stats.add(Stat.liquidCapacity, displayLiquid, StatUnit.liquidUnits);
    }

    public class CombinedPumpBuild extends PumpBuild implements combine.saves.ComboSaved {
        public CombinedPumpBuild comboLeader;
        public Seq<CombinedPumpBuild> comboGroup = new Seq<>();
        public boolean comboDirty = true;
        public float comboTotalLiquidCap = 0f;
        public int comboTotalItemCap = 0;
        public int pendingLeaderPos = -1;

        public CombinedPumpBuild leader() {
            if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null))
                comboLeader = null;
            return comboLeader == null ? this : comboLeader;
        }

        public boolean isLeader() {
            return leader() == this;
        }

        public Seq<CombinedPumpBuild> group() {
            CombinedPumpBuild l = leader();
            if (l.comboGroup == null)
                l.comboGroup = new Seq<>();
            return l.comboGroup;
        }

        // -------------------- 组合重建 --------------------
        public void rebuildCombo() {
            Seq<CombinedPumpBuild> oldGroup = comboGroup != null ? new Seq<>(comboGroup) : new Seq<>();
            comboGroup = new Seq<>();
            comboGroup.add(this);
            // 分组 BFS 穿过组合节点/连接器：被节点连上 = 效果相当于直接组合（同 LinkWall 语义）
            for (Building b : ComboReflect.linkedReachable(this,
                    o -> o instanceof CombinedPumpBuild other && other.team == team && other.isValid(),
                    (cur, o) -> cur.block == o.block
                    || ((CombinedPump) cur.block).allowCrossTypeCombo
                    || ((CombinedPump) o.block).allowCrossTypeCombo)) {
                if (b != this)
                    comboGroup.add((CombinedPumpBuild) b);
            }
            CombinedPumpBuild newLeader = this;
            for (CombinedPumpBuild b : comboGroup)
                if (b.isValid() && b.pos() < newLeader.pos())
                    newLeader = b;
            Seq<CombinedPumpBuild> newGroup = new Seq<>(comboGroup);
            newLeader.comboGroup = newGroup;
            for (CombinedPumpBuild b : newGroup) {
                if (b.isValid()) {
                    b.comboLeader = newLeader;
                    b.comboGroup = newGroup;
                    b.comboDirty = false;
                }
            }
            newLeader.comboLeader = null;

            float totalLiqCap = 0f;
            for (CombinedPumpBuild b : newGroup)
                if (b.isValid())
                    totalLiqCap += ((CombinedPump) b.block).baseLiquidCapacity;
            for (CombinedPumpBuild b : newGroup)
                if (b.isValid())
                    b.comboTotalLiquidCap = totalLiqCap;

            if (oldGroup.size > newGroup.size)
                splitAssets(oldGroup, newGroup);
            shareModules(newLeader);

            for (CombinedPumpBuild old : oldGroup) {
                if (old != this && old.isValid() && !newGroup.contains(old)) {
                    old.comboLeader = null;
                    old.comboGroup = new Seq<>();
                    old.comboDirty = true;
                    old.comboTotalLiquidCap = 0f;
                }
            }
        }

        public void splitAssets(Seq<CombinedPumpBuild> oldGroup, Seq<CombinedPumpBuild> newGroup) {
            // FIX[双重拆分]: 网络层(ComboNet)可能已把池子按组件拆过一轮、给部分成员发过新模块。
            // 那些份额先并回主池（新组长持有的模块），再由本地逻辑按容量统一重分，
            // 否则两轮拆分各分各的，总额对不上账（物资凭空丢/复制）。
            CombinedPumpBuild newLeader = null;
            for (CombinedPumpBuild b : newGroup)
                if (b.isValid() && (newLeader == null || b.pos() < newLeader.pos()))
                    newLeader = b;
            if (newLeader == null)
                newLeader = this;
            LiquidModule poolLiquids = newLeader.liquids;
            // 【池子可能不只本组在用】核心库存 / 组合节点接过来的别的组合体也在引用它时，
            // 本地拆分一律不动这口池子（退出成员拿空模块，由 ComboNet 按网络重新分配）。
            // 否则就是从核心库存里搬东西给退出的工厂 —— 用户报的"拆工厂时核心里的东西全没了"。
            boolean liquidPoolOurs = !ComboReflect.liquidPoolSharedOutside(poolLiquids, oldGroup);
            for (CombinedPumpBuild b : oldGroup) {
                if (!b.isValid()) continue;
                if (liquidPoolOurs && poolLiquids != null && b.liquids != null && b.liquids != poolLiquids) {
                    for (Liquid liquid : content.liquids()) {
                        float amt = b.liquids.get(liquid);
                        if (amt > 0.001f) { poolLiquids.add(liquid, amt); b.liquids.remove(liquid, amt); }
                    }
                }
            }
            CombinedPumpBuild oldLeader = newLeader;
            LiquidModule oldLiquids = poolLiquids;
            Seq<CombinedPumpBuild> kicked = new Seq<>();
            for (CombinedPumpBuild b : oldGroup)
                if (b.isValid() && !newGroup.contains(b))
                    kicked.add(b);
            if (kicked.isEmpty())
                return;

            float oldTotalLiquidCap = 0f;
            for (CombinedPumpBuild b : oldGroup)
                if (b.isValid())
                    oldTotalLiquidCap += ((CombinedPump) b.block).baseLiquidCapacity;
            float[] liquidCaps = new float[kicked.size];
            float kickedTotalLiquidCap = 0f;
            for (int i = 0; i < kicked.size; i++) {
                CombinedPumpBuild b = kicked.get(i);
                liquidCaps[i] = ((CombinedPump) b.block).baseLiquidCapacity;
                kickedTotalLiquidCap += liquidCaps[i];
            }
            LiquidModule[] newLiquidMods = new LiquidModule[kicked.size];
            for (int i = 0; i < kicked.size; i++)
                newLiquidMods[i] = new LiquidModule();

            if (liquidPoolOurs && oldLiquids != null && oldTotalLiquidCap > 0.001f && kickedTotalLiquidCap > 0.001f) {
                for (Liquid liquid : content.liquids()) {
                    float total = oldLiquids.get(liquid);
                    if (total <= 0.001f)
                        continue;
                    float kickedTotalShare = total * kickedTotalLiquidCap / oldTotalLiquidCap;
                    kickedTotalShare = Math.min(kickedTotalShare, total);
                    float remaining = kickedTotalShare;
                    for (int i = 0; i < kicked.size; i++) {
                        float ideal = (i == kicked.size - 1) ? remaining
                                : kickedTotalShare * liquidCaps[i] / kickedTotalLiquidCap;
                        ideal = Math.min(ideal, remaining);
                        float share = ideal; // 不硬截断，宁可超容也不丢液体
                        if (share > 0.001f) {
                            newLiquidMods[i].add(liquid, share);
                            remaining -= share;
                        }
                    }
                    oldLiquids.remove(liquid, kickedTotalShare - remaining);
                }
            }
            if (liquidPoolOurs && oldLiquids != null)
                for (CombinedPumpBuild b : newGroup)
                    if (b.isValid())
                        b.liquids = oldLiquids;
            for (int i = 0; i < kicked.size; i++)
                kicked.get(i).liquids = newLiquidMods[i];
        }

        public void shareModules(CombinedPumpBuild leader) {
            float totalLiquidCap = leader.comboTotalLiquidCap;
            if (leader.liquids == null) {
                for (CombinedPumpBuild m : group())
                    if (m.liquids != null) {
                        leader.liquids = m.liquids;
                        break;
                    }
            }
            // 【池子归属】组里有人拿着"组外也在用"的那份模块（核心库存/网络池）时，
            // 组长必须换成那一份再并池：否则会把网络池复制进组长自己的模块里
            //（池子没动、这边也多一份同样的液体），网络层随后把这多出来的一份并回去 —— 凭空翻倍。
            ObjectSet<LiquidModule> sharedLiquidPools = ComboReflect.liquidPoolsSharedOutside(group());
            LiquidModule sharedLiquids = sharedLiquidPools.isEmpty() ? null : sharedLiquidPools.first();
            if (sharedLiquids != null) leader.liquids = sharedLiquids;
            ObjectSet<LiquidModule> processedLiquids = new ObjectSet<>();
            if (leader.liquids != null) {
                processedLiquids.add(leader.liquids);
                for (CombinedPumpBuild m : group()) {
                    if (m != leader && m.isValid() && m.liquids != null
                            && !processedLiquids.contains(m.liquids)
                            // 组外也在用的模块不能"全额并入"（并入不清空源模块 = 凭空多一份）
                            && !sharedLiquidPools.contains(m.liquids)) {
                        processedLiquids.add(m.liquids);
                        for (Liquid liquid : content.liquids()) {
                            float amt = m.liquids.get(liquid);
                            if (amt > 0.001f)
                                leader.liquids.add(liquid, amt); // 全额并入
                        }
                    }
                }
                for (CombinedPumpBuild m : group())
                    if (m.isValid())
                        m.liquids = leader.liquids;
                if (leader.liquids != null) leader.liquids.stopFlow(); // 组内搬池子不算流量（见 ComboNet.moveItems）
            }
        }

        // -------------------- 生命周期 --------------------
        @Override
        public void created() {
            super.created();
            comboDirty = true;
        }

        @Override
        public void onProximityUpdate() {
            super.onProximityUpdate();
            comboDirty = true;
            for (CombinedPumpBuild m : group())
                if (m.isValid())
                    m.comboDirty = true;
        }

        @Override
        public void onRemoved() {
            Seq<CombinedPumpBuild> members = new Seq<>(group());
            boolean wasLeader = isLeader();
            LiquidModule oldLiquids = this.liquids;
            // 【别动不属于本组的池子】核心库存/别的组合体也在用的那份模块：既不能按容量
            // "分一份给幸存者"（那是从核心库存里搬东西），也不能复制一份（核心库存会凭空翻倍）。
            boolean liquidPoolOurs = !ComboReflect.liquidPoolSharedOutside(oldLiquids, members);
            if (!wasLeader) {
                if (liquids != null) {
                    boolean shared = false;
                    for (CombinedPumpBuild m : members)
                        if (m != this && m.isValid() && m.liquids == this.liquids) {
                            shared = true;
                            break;
                        }
                    if (shared)
                        liquids = new LiquidModule();
                }
            }
            if (wasLeader) {
                Seq<CombinedPumpBuild> survivors = new Seq<>();
                for (CombinedPumpBuild b : members)
                    if (b != this && b.isValid())
                        survivors.add(b);
                float[] liquidCaps = new float[survivors.size];
                float totalLiquidCap = 0f;
                for (int i = 0; i < survivors.size; i++) {
                    CombinedPumpBuild b = survivors.get(i);
                    liquidCaps[i] = ((CombinedPump) b.block).baseLiquidCapacity;
                    totalLiquidCap += liquidCaps[i];
                }
                LiquidModule[] liquidMods = new LiquidModule[survivors.size];
                for (int i = 0; i < survivors.size; i++)
                    liquidMods[i] = new LiquidModule();
                if (liquidPoolOurs && oldLiquids != null && totalLiquidCap > 0.001f) {
                    for (Liquid liquid : content.liquids()) {
                        float total = oldLiquids.get(liquid);
                        if (total <= 0.001f)
                            continue;
                        float remaining = total;
                        for (int i = 0; i < survivors.size; i++) {
                            float ideal = (i == survivors.size - 1) ? remaining
                                    : total * liquidCaps[i] / totalLiquidCap;
                            ideal = Math.min(ideal, remaining);
                            float share = ideal; // 不硬截断
                            if (share > 0.001f) {
                                liquidMods[i].add(liquid, share);
                                remaining -= share;
                            }
                        }
                    }
                }
                for (int i = 0; i < survivors.size; i++) {
                    CombinedPumpBuild b = survivors.get(i);
                    b.liquids = liquidMods[i];
                    b.comboLeader = null;
                    b.comboGroup = new Seq<>();
                    b.comboDirty = true;
                    b.comboTotalLiquidCap = 0f;
                }
            } else {
                CombinedPumpBuild leader = leader();
                if (leader != null && leader.isValid() && leader != this)
                    leader.comboDirty = true;
            }
            comboLeader = null;
            comboGroup = new Seq<>();
            comboDirty = false;
            comboTotalLiquidCap = 0f;
            super.onRemoved();
        }

        // -------------------- 核心逻辑 --------------------
        @Override
        public boolean shouldAmbientSound() {
            // FIX[声音线程崩溃]: MindustryX 的环境音在独立 AudioThread（20fps）上调用
            // shouldAmbientSound()，原版默认实现转调 shouldConsume()；
            // 组合建筑这一路会读库存/遍历共享序列，跨线程相撞就是
            // NoSuchElementException。组合建筑统一禁用环境音循环。
            return false;
        }

        public boolean shouldConsume() {
            return liquidDrop != null && liquids.get(liquidDrop) < comboTotalLiquidCap - 0.01f && enabled;
        }

        @Override
        public void updateTile() {
            if (pendingLeaderPos != -1) {
                Building b = world.build(pendingLeaderPos);
                if (b instanceof CombinedPumpBuild leaderBuild && leaderBuild.isValid()
                        && leaderBuild.team == team) {
                    comboLeader = leaderBuild;
                    if (leaderBuild.liquids != null)
                        liquids = leaderBuild.liquids;
                } else {
                    comboLeader = null;
                }
                pendingLeaderPos = -1;
                comboDirty = true;
            }
            if (isLeader() && comboDirty)
                rebuildCombo();
            clampPoolToCaps();

            if (efficiency > 0 && liquidDrop != null) {
                float room = Math.max(0f, comboTotalLiquidCap - liquids.get(liquidDrop));
                float maxPump = Math.min(room, amount * pumpAmount * edelta());
                liquids.add(liquidDrop, maxPump);

                if ((consTimer += delta()) >= consumeTime) {
                    consume();
                    consTimer %= 1f;
                }
                warmup = Mathf.approachDelta(warmup, maxPump > 0.001f ? 1f : 0f, warmupSpeed);
            } else {
                warmup = Mathf.approachDelta(warmup, 0f, warmupSpeed);
            }

            totalProgress += warmup * Time.delta;

            if (liquidDrop != null)
                dumpLiquid(liquidDrop);
        }

        /** 这种液体是不是本机要"吃"的料（原版 consumeLiquid / consumeLiquids）。 */
        public boolean consumesLiquid(Liquid liquid) {
            if (liquid == null || block.consumers == null)
                return false;
            for (Consume cons : block.consumers) {
                if (cons instanceof ConsumeLiquid cl && cl.liquid == liquid)
                    return true;
                if (cons instanceof ConsumeLiquids cls) {
                    for (LiquidStack st : cls.liquids)
                        if (st.liquid == liquid)
                            return true;
                }
            }
            return false;
        }

        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            // 以前一律拒收（"产出型泵"）。但 reinforced-pump 是"吃氢气抽水"的：
            // 拒收氢气 → 缺料 → 完全不工作，面板上也看不到需要氢气。
            // 现在只收本机真正消费的液体，收到整组容量为止；纯产出泵（机械/旋转泵）仍然拒收。
            if (liquid == null || !consumesLiquid(liquid))
                return false;
            return liquids != null && liquids.get(liquid) < Math.max(comboTotalLiquidCap, 1f) - 0.01f;
        }

        /** 进料口按整组上限夹住（原版 handleLiquid 是无脑 add；liquidCapacity 被抬成 9999 假容量）。 */
        @Override
        public void handleLiquid(Building source, Liquid liquid, float amount) {
            float room = Math.max(comboTotalLiquidCap, 0f) - liquids.get(liquid);
            if (room <= 0f)
                return;
            super.handleLiquid(source, liquid, Math.min(amount, room));
        }

        /** 读档/合并/拆分后留下的超容存量：每帧夹回上限。 */
        /**
         * 【已停用】原来这里每帧把液体/物品按"组容量"硬删（set(liquid, cap)）。
         * 组容量随成员增减变化，一拆成员、或某轮容量算小了，超出的存量就被真删掉 ——
         * 用户报的"物资莫名其妙清空"就是这个；而且和本模组其它地方"宁可超容也不丢"的
         * 约定不一致（容量只该拦住新液体进入，见 acceptLiquid/handleLiquid）。
         * 实测：两台组合泵的池子灌 1000 水，下一帧就被削成组容量 40。
         */
        @Deprecated
        public void clampPoolToCaps() {
        }

        @Override
        public void dumpLiquid(Liquid liquid, float scaling, int outputDir) {
            float oldCap = block.liquidCapacity;
            block.liquidCapacity = Math.max(comboTotalLiquidCap, 1f);
            super.dumpLiquid(liquid, scaling, outputDir);
            block.liquidCapacity = oldCap;
        }

        // -------------------- 显示 --------------------
        @Override
        public void display(Table table) {
          // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
          ComboUi.safe("combinedpump:display", () -> displayInner(table));
        }

        void displayInner(Table table) {
            table.table(cont -> {
                cont.top().left();
                cont.defaults().growX().left();
                cont.table(t -> {
                    t.left();
                    TextureRegion icon = block.getDisplayIcon(tile);
                    if (icon == null)
                        icon = Core.atlas.find("clear");
                    t.add(new Image(icon)).size(8 * 4);
                    int count = ComboNet.displayMembers(this, group().size).size;
                    String title = count > 1 ? "[accent]组合泵[] x" + count + "\n" + block.getDisplayName(tile)
                            : block.getDisplayName(tile);
                    t.labelWrap(title).left().width(160f).padLeft(4);
                }).growX().left();
                cont.row();
                if (team != mindustry.Vars.player.team())
                    return;
                Table barsTable = new Table();
                barsTable.left();
                barsTable.update(() -> {
                    barsTable.clearChildren();
                    barsTable.defaults().growX().height(18f).pad(4);
                    // 血条：手绘（不要用 displayBars —— 它会把原版那条按假容量 9999 算的液条也带出来，
                    // 和组合自己的液条重复）
                    if (!Mathf.zero(block.health, 0.001f)) {
                        final float h = health, mh = maxHealth;
                        barsTable.add(new Bar(
                                () -> Core.bundle.get("stat.health", "Health") + " " + (int) Math.max(h, 0),
                                () -> Pal.health, () -> Mathf.clamp(h / mh)));
                        barsTable.row();
                    }
                    // 电力条：按整组耗电显示（组里没人耗电就不画）
                    float totalPowerUsage = 0f;
                    for (CombinedPumpBuild member : group())
                        if (member.isValid() && member.block.consPower != null)
                            totalPowerUsage += member.block.consPower.usage;
                    ComboUi.addPowerBar(barsTable, this, totalPowerUsage);
                    // 需要输入的液体（如 reinforced-pump 的氢气）也要显示，否则玩家看不出缺什么
                    LiquidModule needSource = liquids;
                    if (needSource == null) {
                        CombinedPumpBuild l3 = leader();
                        if (l3 != null)
                            needSource = l3.liquids;
                    }
                    if (needSource != null && block.consumers != null) {
                        final LiquidModule fLiq = needSource;
                        final float lcap3 = Math.max(comboTotalLiquidCap, 1f);
                        for (Consume cons : block.consumers) {
                            Liquid need = null;
                            if (cons instanceof ConsumeLiquid cl)
                                need = cl.liquid;
                            else if (cons instanceof ConsumeLiquids cls && cls.liquids.length > 0)
                                need = cls.liquids[0].liquid;
                            if (need == null || need == liquidDrop)
                                continue;
                            final Liquid needF = need;
                            barsTable.add(new Bar(
                                    () -> needF.localizedName + ": " + Strings.fixed(fLiq.get(needF), 1) + "/" + Strings.fixed(lcap3, 1),
                                    () -> needF.barColor != null ? needF.barColor : needF.color,
                                    () -> fLiq.get(needF) / lcap3));
                            barsTable.row();
                        }
                    }
                    LiquidModule liq = liquids;
                    if (liq == null) {
                        CombinedPumpBuild l = leader();
                        if (l != null)
                            liq = l.liquids;
                    }
                    if (liq != null) {
                        for (Liquid liquid : content.liquids()) {
                            float total = liq.get(liquid);
                            if (total > 0.001f) {
                                final float t = total, c = Math.max(comboTotalLiquidCap, 1f);
                                barsTable.add(new Bar(
                                        () -> liquid.localizedName + ": " + Strings.fixed(t, 1) + "/"
                                                + Strings.fixed(c, 1),
                                        () -> liquid.barColor != null ? liquid.barColor : liquid.color,
                                        () -> t / c));
                                barsTable.row();
                            }
                        }
                    }
                });
                cont.add(barsTable).growX().left();
                cont.row();
                Table comboIO = new Table();
                comboIO.left();
                comboIO.update(() -> {
                    comboIO.clearChildren();
                    buildComboIO(comboIO);
                });
                cont.add(comboIO).growX().left();
            }).width(260f).left();
                }

        public void buildComboIO(Table table) {
            table.left();
            table.add("[lightgray]组合体构成:").left();
            table.row();
            ObjectIntMap<Block> blockCounts = new ObjectIntMap<>();
            for (Building member : ComboNet.displayMembers(this, group().size)) {
                if (member.isValid()) {
                    int old = blockCounts.get(member.block, 0);
                    blockCounts.put(member.block, old + 1);
                }
            }
            Seq<Block> sorted = new Seq<>();
            for (Block b : blockCounts.keys())
                sorted.add(b);
            sorted.sort(b -> b.id);
            for (Block b : sorted) {
                int count = blockCounts.get(b, 0);
                if (count > 0) {
                    table.add(b.localizedName + "*" + count).color(Color.white).left();
                    table.row();
                }
            }
        }

        // -------------------- 序列化 --------------------
        // 地图区里只写"原版机械泵那一份字节"（原版 PumpBuild 就是 Building，什么都不加），
        // 模组自己的字段（组长）挪到自定义存档块 ComboSaveState —— 详见 ComboSaved。
        @Override
        public byte version() {
            return combine.saves.ComboSaveState.vanillaVersion(block);
        }

        @Override
        public void write(Writes write) {
            super.write(write);
        }

        // 存档先调 writeBase 写模块数据、后调 write —— "只有组长写真实模块"必须挂在 writeBase 上
        // （写在 write() 里来不及），否则每个成员各写一份整池，读档合并后数量 ×N。
        @Override
        public void writeBase(Writes write) {
            ItemModule savedItems = items;
            LiquidModule savedLiquids = liquids;
            if (combine.saves.ComboSaveState.isFollower(this, comboGroup)) {
                if (items != null)
                    items = new ItemModule();
                if (liquids != null)
                    liquids = new LiquidModule();
            }
            super.writeBase(write);
            items = savedItems;
            liquids = savedLiquids;
        }

        @Override
        public void writeCombo(Writes write) {
            Building leader = combine.saves.ComboSaveState.trueLeader(this, comboGroup);
            write.bool(leader != this);
            if (leader != this)
                write.i(leader.pos());
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            if (revision >= 10) {
                // 旧档（≤2.6）：模组字段直接续写在地图区里
                readLeader(read, true);
                return;
            }
            comboDirty = true;
        }

        @Override
        public void readCombo(Reads read, byte revision) {
            // 新格式里"非组长"在存档里写的就是空模块，读档时整组已经被并成一份，
            // 这里不能再清空（清了就把并好的池子丢掉）。
            readLeader(read, false);
        }

        void readLeader(Reads read, boolean clearModules) {
            boolean hasLeader = read.bool();
            int leaderPos = hasLeader ? read.i() : -1;
            comboDirty = true;
            if (hasLeader && leaderPos != pos()) {
                pendingLeaderPos = leaderPos;
                if (clearModules && liquids != null)
                    liquids = new LiquidModule();
            } else {
                pendingLeaderPos = -1;
                comboLeader = null;
            }
        }
    }
}
