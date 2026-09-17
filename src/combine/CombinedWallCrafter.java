package combine;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Geometry;
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
import mindustry.type.Item;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.WallCrafter;
import mindustry.world.modules.ItemModule;

import static mindustry.Vars.*;

/**
 * 组合墙切割机 —— 相邻同型自动组合，共享物品池（总容量 = Σ 单台容量）。
 * 每台切割自己面前的墙，产出进入共享池，由全体成员向外输出。
 */
public class CombinedWallCrafter extends WallCrafter {
    public boolean allowCrossTypeCombo = true;
    public float itemCapacityMultiplier = 1f;

    public Seq<Item> cachedItems = new Seq<>();

    public CombinedWallCrafter(String name) {
        super(name);
        hasItems = true;
        update = true;
        solid = true;
        sync = true;
    }

    @Override
    public void init() {
        super.init();
        hasItems = true;
        cachedItems.clear();
        conductivePower = true;
        if (output != null)
            cachedItems.add(output);
        if (itemConsumer instanceof mindustry.world.consumers.ConsumeItems ci) {
            for (var stack : ci.items)
                if (!cachedItems.contains(stack.item))
                    cachedItems.add(stack.item);
        }
    }

    @Override
    public boolean canPlaceOn(Tile tile, Team team, int rotation) {
        return efficiencyAt(tile.x, tile.y, rotation, null, null) > 0;
    }

    // 复制自 WallCrafter.getEfficiency（原版为包级私有，跨包不可见）
    float efficiencyAt(int tx, int ty, int rotation, arc.func.Cons<Tile> ctile, arc.func.Intc2 cpos) {
        float eff = 0f;
        int cornerX = tx - (size - 1) / 2, cornerY = ty - (size - 1) / 2, s = size;
        for (int i = 0; i < size; i++) {
            int rx = 0, ry = 0;
            switch (rotation) {
                case 0 -> {
                    rx = cornerX + s;
                    ry = cornerY + i;
                }
                case 1 -> {
                    rx = cornerX + i;
                    ry = cornerY + s;
                }
                case 2 -> {
                    rx = cornerX - 1;
                    ry = cornerY + i;
                }
                case 3 -> {
                    rx = cornerX + i;
                    ry = cornerY - 1;
                }
            }
            if (cpos != null)
                cpos.get(rx, ry);
            Tile other = world.tile(rx, ry);
            if (other != null && other.solid()) {
                float at = other.block().attributes.get(attribute);
                eff += at;
                if (at > 0 && ctile != null)
                    ctile.get(other);
            }
        }
        return eff;
    }

    public class CombinedWallCrafterBuild extends WallCrafterBuild {
        public CombinedWallCrafterBuild comboLeader;
        public Seq<CombinedWallCrafterBuild> comboGroup = new Seq<>();
        public boolean comboDirty = true;
        public float comboTotalLiquidCap = 0f;
        public int comboTotalItemCap = 0;
        public int pendingLeaderPos = -1;

        public CombinedWallCrafterBuild leader() {
            if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null))
                comboLeader = null;
                    comboDirty = true; // FIX: 失联后允许重建组合
            return comboLeader == null ? this : comboLeader;
        }

        public boolean isLeader() {
            return leader() == this;
        }

        public Seq<CombinedWallCrafterBuild> group() {
            CombinedWallCrafterBuild l = leader();
            if (l.comboGroup == null)
                l.comboGroup = new Seq<>();
            return l.comboGroup;
        }

        // -------------------- 组合重建 --------------------
        public void rebuildCombo() {
            Seq<CombinedWallCrafterBuild> oldGroup = comboGroup != null ? new Seq<>(comboGroup) : new Seq<>();
            comboGroup = new Seq<>();
            comboGroup.add(this);
            IntSet visited = new IntSet();
            Queue<CombinedWallCrafterBuild> queue = new Queue<>();
            queue.add(this);
            visited.add(pos());
            while (!queue.isEmpty()) {
                CombinedWallCrafterBuild cur = queue.removeFirst();
                for (Building b : cur.proximity) {
                    if (b instanceof CombinedWallCrafterBuild o && o.team == team && o.isValid()
                            && !visited.contains(o.pos())) {
                        CombinedWallCrafter cb = (CombinedWallCrafter) cur.block,
                                ob = (CombinedWallCrafter) o.block;
                        if (cur.block == o.block || cb.allowCrossTypeCombo || ob.allowCrossTypeCombo) {
                            visited.add(o.pos());
                            queue.addLast(o);
                            comboGroup.add(o);
                        }
                    }
                }
            }
            CombinedWallCrafterBuild newLeader = this;
            for (CombinedWallCrafterBuild b : comboGroup)
                if (b.isValid() && b.pos() < newLeader.pos())
                    newLeader = b;
            Seq<CombinedWallCrafterBuild> newGroup = new Seq<>(comboGroup);
            newLeader.comboGroup = newGroup;
            for (CombinedWallCrafterBuild b : newGroup) {
                if (b.isValid()) {
                    b.comboLeader = newLeader;
                    b.comboGroup = newGroup;
                    b.comboDirty = false;
                }
            }
            newLeader.comboLeader = null;

            int totalItemCap = 0;
            for (CombinedWallCrafterBuild b : newGroup)
                if (b.isValid())
                    totalItemCap += b.block.itemCapacity;
            for (CombinedWallCrafterBuild b : newGroup)
                if (b.isValid())
                    b.comboTotalItemCap = totalItemCap;

            if (oldGroup.size > newGroup.size)
                splitAssets(oldGroup, newGroup);
            shareModules(newLeader);

            for (CombinedWallCrafterBuild old : oldGroup) {
                if (old != this && old.isValid() && !newGroup.contains(old)) {
                    old.comboLeader = null;
                    old.comboGroup = new Seq<>();
                    old.comboDirty = true;
                    old.comboTotalItemCap = 0;
                }
            }
        }

        public void splitAssets(Seq<CombinedWallCrafterBuild> oldGroup,
                Seq<CombinedWallCrafterBuild> newGroup) {
            CombinedWallCrafterBuild oldLeader = null;
            for (CombinedWallCrafterBuild b : oldGroup) {
                if (!b.isValid())
                    continue;
                for (CombinedWallCrafterBuild o : oldGroup) {
                    if (o != b && o.isValid() && o.items == b.items) {
                        oldLeader = b;
                        break;
                    }
                }
                if (oldLeader != null)
                    break;
            }
            if (oldLeader == null) {
                for (CombinedWallCrafterBuild b : oldGroup)
                    if (b.isValid()) {
                        oldLeader = b;
                        break;
                    }
            }
            if (oldLeader == null)
                oldLeader = this;
            ItemModule oldItems = oldLeader.items;
            Seq<CombinedWallCrafterBuild> kicked = new Seq<>();
            for (CombinedWallCrafterBuild b : oldGroup)
                if (b.isValid() && !newGroup.contains(b))
                    kicked.add(b);
            if (kicked.isEmpty())
                return;

            int oldTotalItemCap = 0;
            for (CombinedWallCrafterBuild b : oldGroup)
                if (b.isValid())
                    oldTotalItemCap += b.block.itemCapacity;
            int[] itemCaps = new int[kicked.size];
            int kickedTotalItemCap = 0;
            for (int i = 0; i < kicked.size; i++) {
                CombinedWallCrafterBuild b = kicked.get(i);
                itemCaps[i] = b.block.itemCapacity;
                kickedTotalItemCap += itemCaps[i];
            }
            ItemModule[] newItemMods = new ItemModule[kicked.size];
            for (int i = 0; i < kicked.size; i++)
                newItemMods[i] = new ItemModule();

            if (oldItems != null && oldTotalItemCap > 0 && kickedTotalItemCap > 0) {
                for (Item item : content.items()) {
                    int total = oldItems.get(item);
                    if (total <= 0)
                        continue;
                    int kickedTotalShare = Math.round(total * (float) kickedTotalItemCap / oldTotalItemCap);
                    kickedTotalShare = Math.min(kickedTotalShare, total);
                    int remaining = kickedTotalShare;
                    for (int i = 0; i < kicked.size; i++) {
                        int ideal = (i == kicked.size - 1) ? remaining
                                : Math.round(kickedTotalShare * (float) itemCaps[i] / kickedTotalItemCap);
                        ideal = Math.min(ideal, remaining);
                        int share = ideal; // 不硬截断，宁可超容也不丢物品
                        if (share > 0) {
                            newItemMods[i].add(item, share);
                            remaining -= share;
                        }
                    }
                    oldItems.remove(item, kickedTotalShare - remaining);
                }
            }
            if (oldItems != null)
                for (CombinedWallCrafterBuild b : newGroup)
                    if (b.isValid())
                        b.items = oldItems;
            for (int i = 0; i < kicked.size; i++)
                kicked.get(i).items = newItemMods[i];
        }

        public void shareModules(CombinedWallCrafterBuild leader) {
            int totalItemCap = leader.comboTotalItemCap;
            if (leader.items == null) {
                for (CombinedWallCrafterBuild m : group())
                    if (m.items != null) {
                        leader.items = m.items;
                        break;
                    }
            }
            ObjectSet<ItemModule> processedItems = new ObjectSet<>();
            if (leader.items != null) {
                processedItems.add(leader.items);
                for (CombinedWallCrafterBuild m : group()) {
                    if (m != leader && m.isValid() && m.items != null && !processedItems.contains(m.items)) {
                        processedItems.add(m.items);
                        for (Item item : content.items()) {
                            int amt = m.items.get(item);
                            if (amt > 0)
                                leader.items.add(item, amt); // 全额并入
                        }
                    }
                }
                for (CombinedWallCrafterBuild m : group())
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
            super.onProximityUpdate();
            comboDirty = true;
            for (CombinedWallCrafterBuild m : group())
                if (m.isValid())
                    m.comboDirty = true;
        }

        @Override
        public void onRemoved() {
            Seq<CombinedWallCrafterBuild> members = new Seq<>(group());
            boolean wasLeader = isLeader();
            ItemModule oldItems = this.items;
            if (!wasLeader) {
                if (items != null) {
                    boolean shared = false;
                    for (CombinedWallCrafterBuild m : members)
                        if (m != this && m.isValid() && m.items == this.items) {
                            shared = true;
                            break;
                        }
                    if (shared)
                        items = new ItemModule();
                }
            }
            if (wasLeader) {
                Seq<CombinedWallCrafterBuild> survivors = new Seq<>();
                for (CombinedWallCrafterBuild b : members)
                    if (b != this && b.isValid())
                        survivors.add(b);
                int[] itemCaps = new int[survivors.size];
                int totalItemCap = 0;
                for (int i = 0; i < survivors.size; i++) {
                    CombinedWallCrafterBuild b = survivors.get(i);
                    itemCaps[i] = b.block.itemCapacity;
                    totalItemCap += itemCaps[i];
                }
                ItemModule[] itemMods = new ItemModule[survivors.size];
                for (int i = 0; i < survivors.size; i++)
                    itemMods[i] = new ItemModule();
                if (oldItems != null && totalItemCap > 0) {
                    for (Item item : content.items()) {
                        int total = oldItems.get(item);
                        if (total <= 0)
                            continue;
                        int remaining = total;
                        for (int i = 0; i < survivors.size; i++) {
                            int ideal = (i == survivors.size - 1) ? remaining
                                    : Math.round(total * (float) itemCaps[i] / totalItemCap);
                            ideal = Math.min(ideal, remaining);
                            int share = ideal; // 不硬截断
                            if (share > 0) {
                                itemMods[i].add(item, share);
                                remaining -= share;
                            }
                        }
                    }
                }
                for (int i = 0; i < survivors.size; i++) {
                    CombinedWallCrafterBuild b = survivors.get(i);
                    b.items = itemMods[i];
                    b.comboLeader = null;
                    b.comboGroup = new Seq<>();
                    b.comboDirty = true;
                    b.comboTotalItemCap = 0;
                }
            } else {
                CombinedWallCrafterBuild leader = leader();
                if (leader != null && leader.isValid() && leader != this)
                    leader.comboDirty = true;
            }
            comboLeader = null;
            comboGroup = new Seq<>();
            comboDirty = false;
            comboTotalItemCap = 0;
            super.onRemoved();
        }

        // -------------------- 核心逻辑 --------------------
        @Override
        public boolean acceptItem(Building source, Item item) {
            return false; // 产出型方块，不接受外部物品
        }

        @Override
        public int getMaximumAccepted(Item item) {
            return Math.max(comboTotalItemCap, 1);
        }

        @Override
        public boolean shouldAmbientSound() {
            // FIX[声音线程崩溃]: MindustryX 的环境音在独立 AudioThread（20fps）上调用
            // shouldAmbientSound()，原版默认实现转调 shouldConsume()；
            // 组合建筑这一路会读库存/遍历共享序列，跨线程相撞就是
            // NoSuchElementException。组合建筑统一禁用环境音循环。
            return false;
        }

        public boolean shouldConsume() {
            return items.get(output) < Math.max(comboTotalItemCap, 1);
        }

        @Override
        public void updateTile() {
            if (pendingLeaderPos != -1) {
                Building b = world.build(pendingLeaderPos);
                if (b instanceof CombinedWallCrafterBuild leaderBuild && leaderBuild.isValid()
                        && leaderBuild.team == team) {
                    comboLeader = leaderBuild;
                    if (leaderBuild.items != null)
                        items = leaderBuild.items;
                } else {
                    comboLeader = null;
                }
                pendingLeaderPos = -1;
                comboDirty = true;
            }
            if (isLeader() && comboDirty)
                rebuildCombo();

            boolean cons = shouldConsume();
            boolean itemValid = itemConsumer != null && itemConsumer.efficiency(this) > 0;

            warmup = Mathf.approachDelta(warmup, Mathf.num(efficiency > 0), 1f / 40f);
            float dx = Geometry.d4x(rotation) * 0.5f, dy = Geometry.d4y(rotation) * 0.5f;

            float eff = efficiencyAt(tile.x, tile.y, rotation, dest -> {
                if (wasVisible && cons && Mathf.chanceDelta(updateEffectChance * warmup)) {
                    updateEffect.at(
                            dest.worldx() + Mathf.range(3f) - dx * tilesize,
                            dest.worldy() + Mathf.range(3f) - dy * tilesize,
                            dest.block().mapColor);
                }
            }, null) * Mathf.lerp(1f, liquidBoostIntensity,
                    hasLiquidBooster ? optionalEfficiency : 0f) * (itemValid ? itemBoostIntensity : 1f);

            if (itemValid && eff * efficiency > 0 && timer(timerUse, boostItemUseTime / timeScale)) {
                consume();
            }

            lastEfficiency = eff * timeScale * efficiency;

            if (cons && (time += edelta() * eff) >= drillTime) {
                items.add(output, 1); // 直接入共享池
                time %= drillTime;
            }

            totalTime += edelta() * warmup * (eff <= 0f ? 0f : 1f);

            // FIX[搬运粒度]: 同上，按每 tick 搬运
            if (timer(timerDump, CombinedCrafter.comboDumpInterval / timeScale)) {
                dump(output);
            }
        }

        // -------------------- 显示 --------------------
        @Override
        public void display(Table table) {
          // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
          ComboUi.safe("combinedwallcrafter:display", () -> displayInner(table));
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
                            ? "[accent]组合墙切割机[] x" + count + "\n" + block.getDisplayName(tile)
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
                    final float le = lastEfficiency;
                    barsTable.add(new Bar(
                            () -> Core.bundle.format("bar.drillspeed", Strings.fixed(le * 60 / drillTime, 2)),
                            () -> Pal.ammo, () -> warmup));
                    barsTable.row();
                    if (items != null) {
                        for (Item item : content.items()) {
                            int total = items.get(item);
                            if (total > 0) {
                                final int t = total, c = Math.max(comboTotalItemCap, 1);
                                barsTable.add(new Bar(
                                        () -> item.localizedName + ": " + t + "/" + c,
                                        () -> item.color,
                                        () -> (float) t / c));
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
            CombinedWallCrafterBuild trueLeader = this;
            if (comboGroup != null && comboGroup.size > 0)
                for (CombinedWallCrafterBuild b : comboGroup)
                    if (b != null && b.isValid() && b.pos() < trueLeader.pos())
                        trueLeader = b;
            ItemModule savedItems = items;
            if (this != trueLeader && items != null)
                items = new ItemModule();
            super.write(write);
            items = savedItems;
            write.bool(comboLeader != null);
            if (comboLeader != null)
                write.i(comboLeader.pos());
            write.f(time);
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
                if (items != null)
                    items = new ItemModule();
            } else {
                pendingLeaderPos = -1;
                comboLeader = null;
            }
            if (revision >= 10)
                time = read.f();
        }
    }
}
