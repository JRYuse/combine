package combine;

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
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Pump;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
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

    public class CombinedPumpBuild extends PumpBuild {
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
            IntSet visited = new IntSet();
            Queue<CombinedPumpBuild> queue = new Queue<>();
            queue.add(this);
            visited.add(pos());
            while (!queue.isEmpty()) {
                CombinedPumpBuild cur = queue.removeFirst();
                for (Building b : cur.proximity) {
                    if (b instanceof CombinedPumpBuild o && o.team == team && o.isValid()
                            && !visited.contains(o.pos())) {
                        CombinedPump cb = (CombinedPump) cur.block, ob = (CombinedPump) o.block;
                        if (cur.block == o.block || cb.allowCrossTypeCombo || ob.allowCrossTypeCombo) {
                            visited.add(o.pos());
                            queue.addLast(o);
                            comboGroup.add(o);
                        }
                    }
                }
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
            CombinedPumpBuild oldLeader = null;
            for (CombinedPumpBuild b : oldGroup) {
                if (!b.isValid())
                    continue;
                for (CombinedPumpBuild o : oldGroup) {
                    if (o != b && o.isValid() && o.liquids == b.liquids) {
                        oldLeader = b;
                        break;
                    }
                }
                if (oldLeader != null)
                    break;
            }
            if (oldLeader == null) {
                for (CombinedPumpBuild b : oldGroup)
                    if (b.isValid()) {
                        oldLeader = b;
                        break;
                    }
            }
            if (oldLeader == null)
                oldLeader = this;
            LiquidModule oldLiquids = oldLeader.liquids;
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

            if (oldLiquids != null && oldTotalLiquidCap > 0.001f && kickedTotalLiquidCap > 0.001f) {
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
            if (oldLiquids != null)
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
            ObjectSet<LiquidModule> processedLiquids = new ObjectSet<>();
            if (leader.liquids != null) {
                processedLiquids.add(leader.liquids);
                for (CombinedPumpBuild m : group()) {
                    if (m != leader && m.isValid() && m.liquids != null
                            && !processedLiquids.contains(m.liquids)) {
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
                if (oldLiquids != null && totalLiquidCap > 0.001f) {
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

        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            return false;
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
                    // 标题也用活标签：网络组成变了（比如刚放了个连接器）不用重新点开一次
                    t.labelWrap(() -> {
                    int count = ComboNet.displayMembers(this, group().size).size;
                    return count > 1 ? "[accent]组合泵[] x" + count + "\n" + block.getDisplayName(tile)
                        : block.getDisplayName(tile);
                    }).left().width(160f).padLeft(4);
                }).growX().left();
                cont.row();
                if (team != mindustry.Vars.player.team())
                    return;
                Table barsTable = new Table();
                barsTable.left();
                barsTable.update(() -> {
                    barsTable.clearChildren();
                    barsTable.defaults().growX().height(18f).pad(4);
                    if (!Mathf.zero(block.health, 0.001f)) {
                        final float h = health, mh = maxHealth;
                        barsTable.add(new Bar(
                                () -> Core.bundle.get("stat.health", "Health") + " " + (int) Math.max(h, 0),
                                () -> Pal.health, () -> Mathf.clamp(h / mh)));
                        barsTable.row();
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
        @Override
        public byte version() {
            return 10;
        }

        @Override
        public void write(Writes write) {
            CombinedPumpBuild trueLeader = this;
            if (comboGroup != null && comboGroup.size > 0)
                for (CombinedPumpBuild b : comboGroup)
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
}
    }
}
