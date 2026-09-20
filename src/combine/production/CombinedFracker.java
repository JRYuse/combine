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
import mindustry.logic.LAccess;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Fracker;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.type.LiquidStack;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItems;
import mindustry.world.consumers.ConsumeLiquid;
import mindustry.world.consumers.ConsumeLiquids;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.*;

/**
 * 组合抽油机（Fracker / oil-extractor 等）—— 相邻同型自动组合：
 *   · 共享**液体**池（总容量 = Σ 单台容量），每台按自己地格的 validTiles/boost 注入，池子由全体输出；
 *   · 共享**物品**池（总容量 = Σ 单台容量），Fracker 每攒够 itemUseTime 就"吃"一次料，
 *     从组共享池里扣 —— 所以同伴送进来的料也能喂给组里任意一台。
 * 复刻原版 SolidPumpBuild 的 warmup/pumpTime/lastPump 行为（字段全部继承自
 * FrackerBuild/SolidPumpBuild，不重复声明）。
 */
public class CombinedFracker extends Fracker {
    public boolean allowCrossTypeCombo = true;
    public float baseLiquidCapacity = 10f;
    public float displayLiquid;

    public CombinedFracker(String name) {
        super(name);
        hasLiquids = true;
        hasItems = true;
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
        // 9999 必须保留：原版 transferLiquid 按"目标方块的 liquidCapacity"限流，
        // 共享池总量超过单台容量后管道会算出负流量而彻底断流（调用方是原版代码）。
        // 超容防护 = acceptLiquid 拒绝输入（泵只产不吸）+ 产量按组容量限流。
        conductivePower = true;
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.remove(Stat.liquidCapacity);
        stats.add(Stat.liquidCapacity, displayLiquid, StatUnit.liquidUnits);
    }

    public class CombinedFrackerBuild extends FrackerBuild {
        public CombinedFrackerBuild comboLeader;
        public Seq<CombinedFrackerBuild> comboGroup = new Seq<>();
        public boolean comboDirty = true;
        public float comboTotalLiquidCap = 0f;
        public int comboTotalItemCap = 0;
        public int pendingLeaderPos = -1;

        public CombinedFrackerBuild leader() {
            if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null))
                comboLeader = null;
            return comboLeader == null ? this : comboLeader;
        }

        public boolean isLeader() {
            return leader() == this;
        }

        public Seq<CombinedFrackerBuild> group() {
            CombinedFrackerBuild l = leader();
            if (l.comboGroup == null)
                l.comboGroup = new Seq<>();
            return l.comboGroup;
        }

        // -------------------- 组合重建 --------------------
        public void rebuildCombo() {
            Seq<CombinedFrackerBuild> oldGroup = comboGroup != null ? new Seq<>(comboGroup) : new Seq<>();
            comboGroup = new Seq<>();
            comboGroup.add(this);
            // 分组 BFS 穿过组合节点/连接器：被节点连上 = 效果相当于直接组合（同 LinkWall 语义）
            for (Building b : ComboReflect.linkedReachable(this,
                    o -> o instanceof CombinedFrackerBuild other && other.team == team && other.isValid(),
                    (cur, o) -> cur.block == o.block
                    || ((CombinedFracker) cur.block).allowCrossTypeCombo
                    || ((CombinedFracker) o.block).allowCrossTypeCombo)) {
                if (b != this)
                    comboGroup.add((CombinedFrackerBuild) b);
            }
            CombinedFrackerBuild newLeader = this;
            for (CombinedFrackerBuild b : comboGroup)
                if (b.isValid() && b.pos() < newLeader.pos())
                    newLeader = b;
            Seq<CombinedFrackerBuild> newGroup = new Seq<>(comboGroup);
            newLeader.comboGroup = newGroup;
            for (CombinedFrackerBuild b : newGroup) {
                if (b.isValid()) {
                    b.comboLeader = newLeader;
                    b.comboGroup = newGroup;
                    b.comboDirty = false;
                }
            }
            newLeader.comboLeader = null;

            float totalLiqCap = 0f;
            int totalItemCap = 0;
            for (CombinedFrackerBuild b : newGroup)
                if (b.isValid()) {
                    totalLiqCap += ((CombinedFracker) b.block).baseLiquidCapacity;
                    totalItemCap += b.block.itemCapacity;
                }
            for (CombinedFrackerBuild b : newGroup)
                if (b.isValid()) {
                    b.comboTotalLiquidCap = totalLiqCap;
                    b.comboTotalItemCap = totalItemCap;
                }

            // 【拆池】这里不自己拆：刚被拆开的组之间可能还共用同一份模块，谁来分都容易分错
            // （不知道"新"的一共有几组）。ComboNet 每帧会做一次全局拆池：同一份模块被多个
            // 组件引用时按各组件的基础容量比例拆开，物品/液体都算，分配正确且总值不变。
            shareModules(newLeader);

            for (CombinedFrackerBuild old : oldGroup) {
                if (old != this && old.isValid() && !newGroup.contains(old)) {
                    old.comboLeader = null;
                    old.comboGroup = new Seq<>();
                    old.comboDirty = true;
                    old.comboTotalLiquidCap = 0f;
                    old.comboTotalItemCap = 0;
                }
            }
        }

        public void shareModules(CombinedFrackerBuild leader) {
            float totalLiquidCap = leader.comboTotalLiquidCap;
            if (leader.liquids == null) {
                for (CombinedFrackerBuild m : group())
                    if (m.liquids != null) {
                        leader.liquids = m.liquids;
                        break;
                    }
            }
            ObjectSet<LiquidModule> processed = new ObjectSet<>();
            if (leader.liquids != null) {
                processed.add(leader.liquids);
                for (CombinedFrackerBuild m : group()) {
                    if (m != leader && m.isValid() && m.liquids != null
                            && !processed.contains(m.liquids)) {
                        processed.add(m.liquids);
                        for (Liquid liquid : content.liquids()) {
                            float amt = m.liquids.get(liquid);
                            if (amt > 0.001f)
                                leader.liquids.add(liquid, amt); // 全额并入
                        }
                    }
                }
                for (CombinedFrackerBuild m : group())
                    if (m.isValid())
                        m.liquids = leader.liquids;
            }

            // 物品同样：组内共用一份模块，容量 = Σ 单台容量（Fracker 吃料从这里扣）
            if (leader.items == null) {
                for (CombinedFrackerBuild m : group())
                    if (m.items != null) {
                        leader.items = m.items;
                        break;
                    }
            }
            ObjectSet<ItemModule> processedItems = new ObjectSet<>();
            if (leader.items != null) {
                processedItems.add(leader.items);
                for (CombinedFrackerBuild m : group()) {
                    if (m != leader && m.isValid() && m.items != null && !processedItems.contains(m.items)) {
                        processedItems.add(m.items);
                        for (Item item : content.items()) {
                            int amt = m.items.get(item);
                            if (amt > 0)
                                leader.items.add(item, amt);
                        }
                    }
                }
                for (CombinedFrackerBuild m : group())
                    if (m.isValid())
                        m.items = leader.items;
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
            super.onProximityUpdate(); // 原版：重算 boost / validTiles
            comboDirty = true;
            for (CombinedFrackerBuild m : group())
                if (m.isValid())
                    m.comboDirty = true;
        }

        @Override
        public void onRemoved() {
            Seq<CombinedFrackerBuild> members = new Seq<>(group());
            for (CombinedFrackerBuild m : members) {
                if (m != this && m.isValid()) {
                    m.comboLeader = null;
                    m.comboGroup = new Seq<>();
                    m.comboDirty = true;
                }
            }
            comboLeader = null;
            comboGroup = new Seq<>();
            comboDirty = false;
            comboTotalLiquidCap = 0f;
            comboTotalItemCap = 0;
            super.onRemoved();
        }

        // -------------------- 核心逻辑（复刻 SolidPumpBuild，容量换组容量） --------------------
        @Override
        public boolean shouldAmbientSound() {
            // FIX[声音线程崩溃]: MindustryX 的环境音在独立 AudioThread（20fps）上调用
            // shouldAmbientSound()，原版默认实现转调 shouldConsume()；
            // 组合建筑这一路会读库存/遍历共享序列，跨线程相撞就是
            // NoSuchElementException。组合建筑统一禁用环境音循环。
            return false;
        }

        public boolean shouldConsume() {
            return liquids.get(result) < Math.max(comboTotalLiquidCap, 1f) - 0.01f;
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


        /**
         * 收液体时按"整组容量"夹住。
         *
         * 我们把 liquidCapacity 抬到 9999 是为了让原版管道的流量计算不出现负值（否则池子超过
         * 单台容量后管道会判定为"满了"而彻底断流），代价是管道一次可以灌进来一大坨 ——
         * 原版 handleLiquid() 是无脑 add，所以池子能被灌到远超上限（面板上就是 水 4862/160）。
         * 这里在入口把超出上限的部分直接丢掉。
         */
        @Override
        public void handleLiquid(Building source, Liquid liquid, float amount) {
            float room = Math.max(comboTotalLiquidCap, 0f) - liquids.get(liquid);
            if (room <= 0f)
                return;
            super.handleLiquid(source, liquid, Math.min(amount, room));
        }

        /** 物品同理：满了就不再收（原版 handleItem 也是无脑 add）。 */
        @Override
        public void handleItem(Building source, Item item) {
            if (items == null)
                return;
            if (items.get(item) >= Math.max(comboTotalItemCap, 1))
                return;
            super.handleItem(source, item);
        }

        /** 读档/合并/拆分后可能留下超容的存量：每帧夹回各自上限（和组合仓库/核心一个口径）。 */
        public void clampPoolToCaps() {
            if (items != null) {
                int cap = Math.max(comboTotalItemCap, 0);
                for (Item item : content.items())
                    if (items.get(item) > cap)
                        items.set(item, cap);
            }
            if (liquids != null) {
                float cap = Math.max(comboTotalLiquidCap, 0f);
                for (Liquid liquid : content.liquids())
                    if (liquids.get(liquid) > cap)
                        liquids.set(liquid, cap);
            }
        }
        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            // 以前一律拒收（"产出型泵"）。但 oil-extractor 这类 Fracker 是"吃水/吃料换油"的，
            // 拒收就会缺料 → efficiency=0 → 完全不工作；面板上也看不到需要的那种液体。
            // 现在：只有本机真正消费的液体才收，收到"整组容量"为止；纯产出泵仍然拒收。
            if (!consumesLiquid(liquid))
                return false;
            return liquids != null && liquids.get(liquid) < Math.max(comboTotalLiquidCap, 1f) - 0.01f;
        }

        /** 收料按"整组容量"算：传送带/装卸器可以直接把料送进组共享池（谁在边上都行）。 */
        @Override
        public boolean acceptItem(Building source, Item item) {
            return items != null && items.get(item) < getMaximumAccepted(item);
        }

        @Override
        public int getMaximumAccepted(Item item) {
            return Math.max(comboTotalItemCap, 1);
        }

        @Override
        public void updateTile() {
            if (pendingLeaderPos != -1) {
                Building b = world.build(pendingLeaderPos);
                if (b instanceof CombinedFrackerBuild leaderBuild && leaderBuild.isValid()
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

            liquidDrop = result;
            float fraction = Math.max(validTiles + boost + (attribute == null ? 0 : attribute.env()), 0);
            float room = Math.max(0f, comboTotalLiquidCap - liquids.get(result));

            // Fracker 特有：每攒够 itemUseTime 就吃一次料（从组共享的物品池里扣）。
            // 原版 FrackerBuild.updateTile 就是这个节奏，这里换了"容量/池子"的来源。
            if (efficiency > 0) {
                float useTime = ((Fracker) block).itemUseTime;
                if (useTime > 0f && accumulator >= useTime) {
                    consume();
                    accumulator -= useTime;
                }
                accumulator += delta() * efficiency;
            } else {
                accumulator = 0f;
            }

            if (efficiency > 0 && room > 0.001f) {
                float maxPump = Math.min(room, pumpAmount * delta() * fraction * efficiency);
                liquids.add(result, maxPump);
                lastPump = maxPump / Time.delta;
                warmup = Mathf.lerpDelta(warmup, 1f, 0.02f);
                if (Mathf.chance(delta() * updateEffectChance)) {
                    updateEffect.at(x + Mathf.range(size * 2f), y + Mathf.range(size * 2f));
                }
            } else {
                warmup = Mathf.lerpDelta(warmup, 0f, 0.02f);
                lastPump = 0f;
            }

            pumpTime += warmup * edelta();
            dumpLiquid(result);
        }

        @Override
        public void dumpLiquid(Liquid liquid, float scaling, int outputDir) {
            float oldCap = block.liquidCapacity;
            block.liquidCapacity = Math.max(comboTotalLiquidCap, 1f);
            super.dumpLiquid(liquid, scaling, outputDir);
            block.liquidCapacity = oldCap;
        }

        @Override
        public void draw() {
            // 原版 SolidPumpBuild.draw 按 block.liquidCapacity 算填充度，绘制期间临时换容量
            float oldCap = block.liquidCapacity;
            block.liquidCapacity = Math.max(comboTotalLiquidCap, 1f);
            try {
                super.draw();
            } finally {
                block.liquidCapacity = oldCap;
            }
        }

        @Override
        public double sense(LAccess sensor) {
            if (sensor == LAccess.efficiency)
                return (validTiles + boost) * efficiency;
            return super.sense(sensor);
        }

        // -------------------- 显示 --------------------
        @Override
        public void display(Table table) {
          // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
          ComboUi.safe("combinedfracker:display", () -> displayInner(table));
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
                    String title = count > 1
                            ? "[accent]组合抽油机[] x" + count + "\n" + block.getDisplayName(tile)
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
                    for (CombinedFrackerBuild member : group())
                        if (member.isValid() && member.block.consPower != null)
                            totalPowerUsage += member.block.consPower.usage;
                    ComboUi.addPowerBar(barsTable, this, totalPowerUsage);
                    LiquidModule liq = liquids;
                    if (liq == null) {
                        CombinedFrackerBuild l = leader();
                        if (l != null)
                            liq = l.liquids;
                    }
                    ItemModule it = items;
                    if (it == null) {
                        CombinedFrackerBuild l2 = leader();
                        if (l2 != null)
                            it = l2.items;
                    }
                    if (it != null) {
                        final ItemModule fItems = it;
                        final int itemCap = Math.max(comboTotalItemCap, 1);
                        // 先列"这台机器要吃的料"（原版 consumeItem）：哪怕池里是 0 也要显示，
                        // 否则玩家看不到还需要喂什么
                        ObjectSet<Item> shownItems = new ObjectSet<>();
                        if (block.consumers != null) {
                            for (Consume cons : block.consumers) {
                                if (cons instanceof ConsumeItems ci) {
                                    for (ItemStack st : ci.items) {
                                        if (st.item == null || !shownItems.add(st.item))
                                            continue;
                                        Item item = st.item;
                                        barsTable.add(new Bar(
                                                () -> item.localizedName + ": " + fItems.get(item) + "/" + itemCap,
                                                () -> item.color,
                                                () -> fItems.get(item) / (float) itemCap));
                                        barsTable.row();
                                    }
                                }
                            }
                        }
                        for (Item item : content.items()) {
                            if (fItems.get(item) <= 0 || shownItems.contains(item))
                                continue;
                            barsTable.add(new Bar(
                                    () -> item.localizedName + ": " + fItems.get(item) + "/" + itemCap,
                                    () -> item.color,
                                    () -> fItems.get(item) / (float) itemCap));
                            barsTable.row();
                        }
                    }
                    if (liq != null) {
                        // 需要输入的液体（原版 consumeLiquid/consumeLiquids）也要显示：
                        // oil-extractor 就是"吃水换油"，以前面板上根本看不到水
                        ObjectSet<Liquid> shownLiquids = new ObjectSet<>();
                        if (block.consumers != null) {
                            for (Consume cons : block.consumers) {
                                if (cons instanceof ConsumeLiquid cl) {
                                    shownLiquids.add(cl.liquid);
                                } else if (cons instanceof ConsumeLiquids cls) {
                                    for (LiquidStack st : cls.liquids)
                                        if (st.liquid != null)
                                            shownLiquids.add(st.liquid);
                                }
                            }
                        }
                        final LiquidModule fLiq = liq;
                        final float lcap = Math.max(comboTotalLiquidCap, 1f);
                        for (Liquid need : shownLiquids) {
                            if (need == null || need == result)
                                continue;
                            barsTable.add(new Bar(
                                    () -> need.localizedName + ": " + Strings.fixed(fLiq.get(need), 1) + "/" + Strings.fixed(lcap, 1),
                                    () -> need.barColor != null ? need.barColor : need.color,
                                    () -> fLiq.get(need) / lcap));
                            barsTable.row();
                        }
                        Liquid out = result;
                        float total = liq.get(out);
                        final float t2 = total, c = Math.max(comboTotalLiquidCap, 1f);
                        barsTable.add(new Bar(
                                () -> out.localizedName + ": " + Strings.fixed(t2, 1) + "/" + Strings.fixed(c, 1),
                                () -> out.barColor != null ? out.barColor : out.color,
                                () -> t2 / c));
                        barsTable.row();
                        // 泵送速度显示**整组**的合计：面板上其它数字（池子/上限）都是整组口径，
                        // 只显示本格的话，站在没水/没油地格上的那一台会一直显示 0.0/秒，
                        // 看起来像"整组不干活"，其实水是同伴在抽。
                        float groupPump = 0f;
                        for (CombinedFrackerBuild m : group())
                            if (m.isValid())
                                groupPump += m.lastPump;
                        final float lp = groupPump;
                        barsTable.add(new Bar(
                                () -> Core.bundle.formatFloat("bar.pumpspeed", lp * 60, 1),
                                () -> Pal.ammo,
                                () -> lp > 0.001f ? 1f : 0f));
                        barsTable.row();
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
        @Override
        public byte version() {
            return 10;
        }

        @Override
        public void write(Writes write) {
            CombinedFrackerBuild trueLeader = this;
            if (comboGroup != null && comboGroup.size > 0)
                for (CombinedFrackerBuild b : comboGroup)
                    if (b != null && b.isValid() && b.pos() < trueLeader.pos())
                        trueLeader = b;
            LiquidModule savedLiquids = liquids;
            if (this != trueLeader && liquids != null)
                liquids = new LiquidModule();
            super.write(write);
            liquids = savedLiquids;
            write.bool(comboLeader != null);
            if (comboLeader != null)
                write.i(comboLeader.pos());
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            boolean hasLeader = false;
            int leaderPos = -1;

            if (revision >= 10) {
            hasLeader = read.bool();
                        if (hasLeader)
                leaderPos = read.i();

            }

            comboDirty = true;
            if (hasLeader && leaderPos != pos()) {
                pendingLeaderPos = leaderPos;
                if (liquids != null)
                    liquids = new LiquidModule();
            } else {
                pendingLeaderPos = -1;
                comboLeader = null;
            }
            comboTotalLiquidCap = ((CombinedFracker) block).baseLiquidCapacity;
            comboTotalItemCap = block.itemCapacity;
}
    }
}
