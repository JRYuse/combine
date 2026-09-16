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
import mindustry.logic.LAccess;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.SolidPump;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.*;

/**
 * 组合固体系泵 —— 相邻同型自动组合，共享液体池（总容量 = Σ 单台容量）。
 * 每台按自己地格的 validTiles/boost 产量注入共享池，池子由全体成员向外输出。
 * 复刻原版 SolidPumpBuild 的 warmup/pumpTime/lastPump 行为（字段全部继承自
 * SolidPumpBuild，不重复声明）。
 */
public class CombinedSolidPump extends SolidPump {
    public boolean allowCrossTypeCombo = true;
    public float baseLiquidCapacity = 10f;
    public float displayLiquid;

    public CombinedSolidPump(String name) {
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

    public class CombinedSolidPumpBuild extends SolidPumpBuild {
        public CombinedSolidPumpBuild comboLeader;
        public Seq<CombinedSolidPumpBuild> comboGroup = new Seq<>();
        public boolean comboDirty = true;
        public float comboTotalLiquidCap = 0f;
        public int pendingLeaderPos = -1;

        public CombinedSolidPumpBuild leader() {
            if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null))
                comboLeader = null;
            return comboLeader == null ? this : comboLeader;
        }

        public boolean isLeader() {
            return leader() == this;
        }

        public Seq<CombinedSolidPumpBuild> group() {
            CombinedSolidPumpBuild l = leader();
            if (l.comboGroup == null)
                l.comboGroup = new Seq<>();
            return l.comboGroup;
        }

        // -------------------- 组合重建 --------------------
        public void rebuildCombo() {
            Seq<CombinedSolidPumpBuild> oldGroup = comboGroup != null ? new Seq<>(comboGroup) : new Seq<>();
            comboGroup = new Seq<>();
            comboGroup.add(this);
            IntSet visited = new IntSet();
            Queue<CombinedSolidPumpBuild> queue = new Queue<>();
            queue.add(this);
            visited.add(pos());
            while (!queue.isEmpty()) {
                CombinedSolidPumpBuild cur = queue.removeFirst();
                for (Building b : cur.proximity) {
                    if (b instanceof CombinedSolidPumpBuild o && o.team == team && o.isValid()
                            && !visited.contains(o.pos())) {
                        CombinedSolidPump cb = (CombinedSolidPump) cur.block, ob = (CombinedSolidPump) o.block;
                        if (cur.block == o.block || cb.allowCrossTypeCombo || ob.allowCrossTypeCombo) {
                            visited.add(o.pos());
                            queue.addLast(o);
                            comboGroup.add(o);
                        }
                    }
                }
            }
            CombinedSolidPumpBuild newLeader = this;
            for (CombinedSolidPumpBuild b : comboGroup)
                if (b.isValid() && b.pos() < newLeader.pos())
                    newLeader = b;
            Seq<CombinedSolidPumpBuild> newGroup = new Seq<>(comboGroup);
            newLeader.comboGroup = newGroup;
            for (CombinedSolidPumpBuild b : newGroup) {
                if (b.isValid()) {
                    b.comboLeader = newLeader;
                    b.comboGroup = newGroup;
                    b.comboDirty = false;
                }
            }
            newLeader.comboLeader = null;

            float totalLiqCap = 0f;
            for (CombinedSolidPumpBuild b : newGroup)
                if (b.isValid())
                    totalLiqCap += ((CombinedSolidPump) b.block).baseLiquidCapacity;
            for (CombinedSolidPumpBuild b : newGroup)
                if (b.isValid())
                    b.comboTotalLiquidCap = totalLiqCap;

            if (oldGroup.size > newGroup.size)
                splitAssets(oldGroup, newGroup);
            shareModules(newLeader);

            for (CombinedSolidPumpBuild old : oldGroup) {
                if (old != this && old.isValid() && !newGroup.contains(old)) {
                    old.comboLeader = null;
                    old.comboGroup = new Seq<>();
                    old.comboDirty = true;
                    old.comboTotalLiquidCap = 0f;
                }
            }
        }

        /** 被踢出的成员按容量比例分走液体 */
        public void splitAssets(Seq<CombinedSolidPumpBuild> oldGroup,
                Seq<CombinedSolidPumpBuild> newGroup) {
            CombinedSolidPumpBuild oldLeader = null;
            for (CombinedSolidPumpBuild b : oldGroup) {
                if (!b.isValid())
                    continue;
                for (CombinedSolidPumpBuild o : oldGroup) {
                    if (o != b && o.isValid() && o.liquids == b.liquids) {
                        oldLeader = b;
                        break;
                    }
                }
                if (oldLeader != null)
                    break;
            }
            if (oldLeader == null) {
                for (CombinedSolidPumpBuild b : oldGroup)
                    if (b.isValid()) {
                        oldLeader = b;
                        break;
                    }
            }
            if (oldLeader == null)
                oldLeader = this;
            LiquidModule oldLiquids = oldLeader.liquids;
            Seq<CombinedSolidPumpBuild> kicked = new Seq<>();
            for (CombinedSolidPumpBuild b : oldGroup)
                if (b.isValid() && !newGroup.contains(b))
                    kicked.add(b);
            if (kicked.isEmpty())
                return;

            float oldTotalLiqCap = 0f;
            for (CombinedSolidPumpBuild b : oldGroup)
                if (b.isValid())
                    oldTotalLiqCap += ((CombinedSolidPump) b.block).baseLiquidCapacity;
            float[] liquidCaps = new float[kicked.size];
            float kickedTotalLiqCap = 0f;
            for (int i = 0; i < kicked.size; i++) {
                CombinedSolidPumpBuild b = kicked.get(i);
                liquidCaps[i] = ((CombinedSolidPump) b.block).baseLiquidCapacity;
                kickedTotalLiqCap += liquidCaps[i];
            }
            LiquidModule[] newLiquidMods = new LiquidModule[kicked.size];
            for (int i = 0; i < kicked.size; i++)
                newLiquidMods[i] = new LiquidModule();

            if (oldLiquids != null && oldTotalLiqCap > 0.001f && kickedTotalLiqCap > 0.001f) {
                for (Liquid liquid : content.liquids()) {
                    float total = oldLiquids.get(liquid);
                    if (total <= 0.001f)
                        continue;
                    float kickedTotalShare = total * kickedTotalLiqCap / oldTotalLiqCap;
                    kickedTotalShare = Math.min(kickedTotalShare, total);
                    float remaining = kickedTotalShare;
                    for (int i = 0; i < kicked.size; i++) {
                        float ideal = (i == kicked.size - 1) ? remaining
                                : kickedTotalShare * liquidCaps[i] / kickedTotalLiqCap;
                        ideal = Math.min(ideal, remaining);
                        if (ideal > 0.001f) {
                            newLiquidMods[i].add(liquid, ideal);
                            remaining -= ideal;
                        }
                    }
                    oldLiquids.remove(liquid, kickedTotalShare - remaining);
                }
            }
            if (oldLiquids != null)
                for (CombinedSolidPumpBuild b : newGroup)
                    if (b.isValid())
                        b.liquids = oldLiquids;
            for (int i = 0; i < kicked.size; i++)
                kicked.get(i).liquids = newLiquidMods[i];
        }

        public void shareModules(CombinedSolidPumpBuild leader) {
            float totalLiquidCap = leader.comboTotalLiquidCap;
            if (leader.liquids == null) {
                for (CombinedSolidPumpBuild m : group())
                    if (m.liquids != null) {
                        leader.liquids = m.liquids;
                        break;
                    }
            }
            ObjectSet<LiquidModule> processed = new ObjectSet<>();
            if (leader.liquids != null) {
                processed.add(leader.liquids);
                for (CombinedSolidPumpBuild m : group()) {
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
                for (CombinedSolidPumpBuild m : group())
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
            super.onProximityUpdate(); // 原版：重算 boost / validTiles
            comboDirty = true;
            for (CombinedSolidPumpBuild m : group())
                if (m.isValid())
                    m.comboDirty = true;
        }

        @Override
        public void onRemoved() {
            Seq<CombinedSolidPumpBuild> members = new Seq<>(group());
            boolean wasLeader = isLeader();
            LiquidModule oldLiquids = this.liquids;
            if (!wasLeader && oldLiquids != null) {
                boolean shared = false;
                for (CombinedSolidPumpBuild m : members)
                    if (m != this && m.isValid() && m.liquids == oldLiquids) {
                        shared = true;
                        break;
                    }
                if (shared)
                    liquids = new LiquidModule(); // 先脱钩，避免共享池被空模块覆盖
            }
            if (wasLeader) {
                Seq<CombinedSolidPumpBuild> survivors = new Seq<>();
                for (CombinedSolidPumpBuild b : members)
                    if (b != this && b.isValid())
                        survivors.add(b);
                float[] liquidCaps = new float[survivors.size];
                float totalLiqCap = 0f;
                for (int i = 0; i < survivors.size; i++) {
                    CombinedSolidPumpBuild b = survivors.get(i);
                    liquidCaps[i] = ((CombinedSolidPump) b.block).baseLiquidCapacity;
                    totalLiqCap += liquidCaps[i];
                }
                LiquidModule[] liquidMods = new LiquidModule[survivors.size];
                for (int i = 0; i < survivors.size; i++)
                    liquidMods[i] = new LiquidModule();
                if (oldLiquids != null && totalLiqCap > 0.001f) {
                    for (Liquid liquid : content.liquids()) {
                        float total = oldLiquids.get(liquid);
                        if (total <= 0.001f)
                            continue;
                        float remaining = total;
                        for (int i = 0; i < survivors.size; i++) {
                            float ideal = (i == survivors.size - 1) ? remaining
                                    : total * liquidCaps[i] / totalLiqCap;
                            ideal = Math.min(ideal, remaining);
                            if (ideal > 0.001f) {
                                liquidMods[i].add(liquid, ideal);
                                remaining -= ideal;
                            }
                        }
                    }
                }
                for (int i = 0; i < survivors.size; i++) {
                    CombinedSolidPumpBuild b = survivors.get(i);
                    b.liquids = liquidMods[i];
                    b.comboLeader = null;
                    b.comboGroup = new Seq<>();
                    b.comboDirty = true;
                    b.comboTotalLiquidCap = 0f;
                }
            } else {
                CombinedSolidPumpBuild leader = leader();
                if (leader != null && leader.isValid() && leader != this)
                    leader.comboDirty = true;
            }
            comboLeader = null;
            comboGroup = new Seq<>();
            comboDirty = false;
            comboTotalLiquidCap = 0f;
            super.onRemoved();
        }

        // -------------------- 核心逻辑（复刻 SolidPumpBuild，容量换组容量） --------------------
        @Override
        public boolean shouldConsume() {
            return liquids.get(result) < Math.max(comboTotalLiquidCap, 1f) - 0.01f;
        }

        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            return false; // 产出型泵，不接受外部液体
        }

        @Override
        public void updateTile() {
            if (pendingLeaderPos != -1) {
                Building b = world.build(pendingLeaderPos);
                if (b instanceof CombinedSolidPumpBuild leaderBuild && leaderBuild.isValid()
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

            liquidDrop = result;
            float fraction = Math.max(validTiles + boost + (attribute == null ? 0 : attribute.env()), 0);
            float room = Math.max(0f, comboTotalLiquidCap - ComboReflect.liquidTotal(liquids));

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
                            ? "[accent]组合固体系泵[] x" + count + "\\n" + block.getDisplayName(tile)
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
                    if (!Mathf.zero(block.health, 0.001f)) {
                        final float h = health, mh = maxHealth;
                        barsTable.add(new Bar(
                                () -> Core.bundle.get("stat.health", "Health") + " " + (int) Math.max(h, 0),
                                () -> Pal.health, () -> Mathf.clamp(h / mh)));
                        barsTable.row();
                    }
                    LiquidModule liq = liquids;
                    if (liq == null) {
                        CombinedSolidPumpBuild l = leader();
                        if (l != null)
                            liq = l.liquids;
                    }
                    if (liq != null) {
                        Liquid out = result;
                        float total = liq.get(out);
                        final float t2 = total, c = Math.max(comboTotalLiquidCap, 1f);
                        barsTable.add(new Bar(
                                () -> out.localizedName + ": " + Strings.fixed(t2, 1) + "/" + Strings.fixed(c, 1),
                                () -> out.barColor != null ? out.barColor : out.color,
                                () -> t2 / c));
                        barsTable.row();
                        final float lp = lastPump;
                        barsTable.add(new Bar(
                                () -> Core.bundle.formatFloat("bar.pumpspeed", lp * 60, 1),
                                () -> Pal.ammo,
                                () -> warmup * efficiency));
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
            CombinedSolidPumpBuild trueLeader = this;
            if (comboGroup != null && comboGroup.size > 0)
                for (CombinedSolidPumpBuild b : comboGroup)
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
            comboTotalLiquidCap = ((CombinedSolidPump) block).baseLiquidCapacity;
}
    }
}
