package combine.defense;
import combine.net.ComboNet;
import combine.util.ComboReflect;
import combine.util.ComboUi;
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
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.ui.ReqImage;
import mindustry.world.Block;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItems;
import mindustry.world.consumers.ConsumeLiquid;
import mindustry.world.consumers.ConsumeLiquids;
import mindustry.world.consumers.ConsumePower;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.*;
import static mindustry.Vars.iconMed;

import mindustry.world.blocks.defense.OverdriveProjector;

public class CombinedOverdriveProjector extends OverdriveProjector {
    public boolean allowCrossTypeCombo = true;
    public float itemCapacityMultiplier = 1f;
    public float liquidCapacityMultiplier = 1f;
    public float baseLiquidCapacity = 10f;
    public float displayLiquid;

    public Seq<Item> cachedItems = new Seq<>();
    public Seq<Liquid> cachedLiquids = new Seq<>();

    public CombinedOverdriveProjector(String name) {
        super(name);
        buildType = () -> new CombinedOverdriveProjectorBuild();
        conductivePower = true;
        hasItems = true;
        hasLiquids = true;
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
        if (!hasLiquids)
            displayLiquid = 0;
        hasLiquids = true;
        hasItems = true;
        conductivePower = true;

        cachedItems.clear();
        cachedLiquids.clear();
        if (consumers != null) {
            for (Consume cons : consumers) {
                if (cons instanceof ConsumeItems ci) {
                    for (var stack : ci.items) {
                        if (!cachedItems.contains(stack.item))
                            cachedItems.add(stack.item);
                    }
                } else if (cons instanceof ConsumeLiquid cl) {
                    if (!cachedLiquids.contains(cl.liquid))
                        cachedLiquids.add(cl.liquid);
                } else if (cons instanceof ConsumeLiquids cls) {
                    for (var stack : cls.liquids) {
                        if (!cachedLiquids.contains(stack.liquid))
                            cachedLiquids.add(stack.liquid);
                    }
                }
            }
        }

        // 禁用环境音循环：避免触发 SoundControl 环境音线程在 Android 上的
        // IllegalThreadStateException（Thread.start 崩溃）
        ambientSound = mindustry.gen.Sounds.none;
        ambientSoundVolume = 0f;
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.remove(Stat.liquidCapacity);
        stats.add(Stat.liquidCapacity, displayLiquid, StatUnit.liquidUnits);
    }

    public class CombinedOverdriveProjectorBuild extends OverdriveBuild implements combine.saves.ComboSaved {

        public CombinedOverdriveProjectorBuild comboLeader;
        public Seq<CombinedOverdriveProjectorBuild> comboGroup = new Seq<>();
        public boolean comboDirty = true;
        public float comboTotalLiquidCap = 0f;
        public int comboTotalItemCap = 0;
        public int pendingLeaderPos = -1;

        public CombinedOverdriveProjectorBuild leader() {
            if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null))
                comboLeader = null;
            return comboLeader == null ? this : comboLeader;
        }

        public boolean isLeader() {
            return leader() == this;
        }

        public Seq<CombinedOverdriveProjectorBuild> group() {
            CombinedOverdriveProjectorBuild l = leader();
            if (l.comboGroup == null)
                l.comboGroup = new Seq<>();
            return l.comboGroup;
        }

        public void rebuildCombo() {
            Seq<CombinedOverdriveProjectorBuild> oldGroup = comboGroup != null ? new Seq<>(comboGroup) : new Seq<>();
            comboGroup = new Seq<>();
            comboGroup.add(this);
            // 分组 BFS 穿过组合节点/连接器：被节点连上 = 效果相当于直接组合（同 LinkWall 语义）
            for (Building b : ComboReflect.linkedReachable(this,
                    o -> o instanceof CombinedOverdriveProjectorBuild other && other.team == team && other.isValid(),
                    (cur, o) -> cur.block == o.block
                    || ((CombinedOverdriveProjector) cur.block).allowCrossTypeCombo
                    || ((CombinedOverdriveProjector) o.block).allowCrossTypeCombo)) {
                if (b != this)
                    comboGroup.add((CombinedOverdriveProjectorBuild) b);
            }
            CombinedOverdriveProjectorBuild newLeader = this;
            for (CombinedOverdriveProjectorBuild b : comboGroup)
                if (b.isValid() && b.pos() < newLeader.pos())
                    newLeader = b;
            Seq<CombinedOverdriveProjectorBuild> newGroup = new Seq<>(comboGroup);
            newLeader.comboGroup = newGroup;
            for (CombinedOverdriveProjectorBuild b : newGroup) {
                if (b.isValid()) {
                    b.comboLeader = newLeader;
                    b.comboGroup = newGroup;
                    b.comboDirty = false;
                }
            }
            newLeader.comboLeader = null;
            float totalLiqCap = 0f;
            int totalItemCap = 0;
            for (CombinedOverdriveProjectorBuild b : newGroup) {
                if (b.isValid()) {
                    totalLiqCap += ((CombinedOverdriveProjector) b.block).baseLiquidCapacity;
                    totalItemCap += b.block.itemCapacity;
                }
            }
            for (CombinedOverdriveProjectorBuild b : newGroup) {
                if (b.isValid()) {
                    b.comboTotalLiquidCap = totalLiqCap;
                    b.comboTotalItemCap = totalItemCap;
                }
            }
            if (oldGroup.size > newGroup.size)
                splitAssets(oldGroup, newGroup);
            shareModules(newLeader);
            for (CombinedOverdriveProjectorBuild old : oldGroup) {
                if (old != this && old.isValid() && !newGroup.contains(old)) {
                    old.comboLeader = null;
                    old.comboGroup = new Seq<>();
                    old.comboDirty = true;
                    old.comboTotalLiquidCap = 0f;
                    old.comboTotalItemCap = 0;
                }
            }
        }

        public void splitAssets(Seq<CombinedOverdriveProjectorBuild> oldGroup,
                Seq<CombinedOverdriveProjectorBuild> newGroup) {
            // FIX[双重拆分]: 网络层(ComboNet)可能已把池子按组件拆过一轮、给部分成员发过新模块。
            // 那些份额先并回主池（新组长持有的模块），再由本地逻辑按容量统一重分，
            // 否则两轮拆分各分各的，总额对不上账（物资凭空丢/复制）。
            CombinedOverdriveProjectorBuild newLeader = null;
            for (CombinedOverdriveProjectorBuild b : newGroup)
                if (b.isValid() && (newLeader == null || b.pos() < newLeader.pos()))
                    newLeader = b;
            if (newLeader == null)
                newLeader = this;
            ItemModule poolItems = newLeader.items;
            LiquidModule poolLiquids = newLeader.liquids;
            for (CombinedOverdriveProjectorBuild b : oldGroup) {
                if (!b.isValid()) continue;
                if (poolItems != null && b.items != null && b.items != poolItems) {
                    for (Item item : content.items()) {
                        int amt = b.items.get(item);
                        if (amt > 0) { poolItems.add(item, amt); b.items.remove(item, amt); }
                    }
                }
                if (poolLiquids != null && b.liquids != null && b.liquids != poolLiquids) {
                    for (Liquid liquid : content.liquids()) {
                        float amt = b.liquids.get(liquid);
                        if (amt > 0.001f) { poolLiquids.add(liquid, amt); b.liquids.remove(liquid, amt); }
                    }
                }
            }
            CombinedOverdriveProjectorBuild oldLeader = newLeader;
            ItemModule oldItems = poolItems;
            LiquidModule oldLiquids = poolLiquids;
            Seq<CombinedOverdriveProjectorBuild> kicked = new Seq<>();
            for (CombinedOverdriveProjectorBuild b : oldGroup)
                if (b.isValid() && !newGroup.contains(b))
                    kicked.add(b);
            int oldTotalItemCap = 0;
            float oldTotalLiquidCap = 0f;
            for (CombinedOverdriveProjectorBuild b : oldGroup) {
                if (b.isValid()) {
                    oldTotalItemCap += b.block.itemCapacity;
                    oldTotalLiquidCap += ((CombinedOverdriveProjector) b.block).baseLiquidCapacity;
                }
            }
            int[] itemCaps = new int[kicked.size];
            float[] liquidCaps = new float[kicked.size];
            int kickedTotalItemCap = 0;
            float kickedTotalLiquidCap = 0f;
            for (int i = 0; i < kicked.size; i++) {
                CombinedOverdriveProjectorBuild b = kicked.get(i);
                itemCaps[i] = b.block.itemCapacity;
                liquidCaps[i] = ((CombinedOverdriveProjector) b.block).baseLiquidCapacity;
                kickedTotalItemCap += itemCaps[i];
                kickedTotalLiquidCap += liquidCaps[i];
            }
            ItemModule[] newItemMods = new ItemModule[kicked.size];
            LiquidModule[] newLiquidMods = new LiquidModule[kicked.size];
            for (int i = 0; i < kicked.size; i++) {
                newItemMods[i] = new ItemModule();
                newLiquidMods[i] = new LiquidModule();
            }
            if (oldItems != null && oldTotalItemCap > 0 && kickedTotalItemCap > 0) {
                int[] kickedAllocated = new int[kicked.size];
                for (Item item : cachedItems) {
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
                        int canTake = Math.max(0, itemCaps[i] - kickedAllocated[i]);
                        int share = ideal; // 不做容量硬截断：超出份额暂时超容保留，宁可超容也不丢物品
                        if (share > 0) {
                            newItemMods[i].add(item, share);
                            kickedAllocated[i] += share;
                            remaining -= share;
                        }
                    }
                    oldItems.remove(item, kickedTotalShare - remaining);
                }
            }
            if (oldLiquids != null && oldTotalLiquidCap > 0.001f && kickedTotalLiquidCap > 0.001f) {
                float[] kickedAllocated = new float[kicked.size];
                for (Liquid liquid : cachedLiquids) {
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
                        float canTake = Math.max(0f, liquidCaps[i] - kickedAllocated[i]);
                        float share = ideal; // 不做容量硬截断：超出份额暂时超容保留，宁可超容也不丢物品
                        if (share > 0.001f) {
                            newLiquidMods[i].add(liquid, share);
                            kickedAllocated[i] += share;
                            remaining -= share;
                        }
                    }
                    oldLiquids.remove(liquid, kickedTotalShare - remaining);
                }
            }
            if (oldItems != null)
                for (CombinedOverdriveProjectorBuild b : newGroup)
                    if (b.isValid())
                        b.items = oldItems;
            if (oldLiquids != null)
                for (CombinedOverdriveProjectorBuild b : newGroup)
                    if (b.isValid())
                        b.liquids = oldLiquids;
            for (int i = 0; i < kicked.size; i++) {
                CombinedOverdriveProjectorBuild b = kicked.get(i);
                b.items = newItemMods[i];
                b.liquids = newLiquidMods[i];
            }
        }

        public void shareModules(CombinedOverdriveProjectorBuild leader) {
            int totalItemCap = leader.comboTotalItemCap;
            float totalLiquidCap = leader.comboTotalLiquidCap;
            if (leader.items == null) {
                for (CombinedOverdriveProjectorBuild m : group())
                    if (m.items != null) {
                        leader.items = m.items;
                        break;
                    }
            }
            if (leader.liquids == null) {
                for (CombinedOverdriveProjectorBuild m : group())
                    if (m.liquids != null) {
                        leader.liquids = m.liquids;
                        break;
                    }
            }
            ObjectSet<ItemModule> processedItems = new ObjectSet<>();
            ObjectSet<LiquidModule> processedLiquids = new ObjectSet<>();
            if (leader.items != null) {
                processedItems.add(leader.items);
                for (CombinedOverdriveProjectorBuild m : group()) {
                    if (m != leader && m.isValid() && m.items != null && !processedItems.contains(m.items)) {
                        processedItems.add(m.items);
                        for (Item item : content.items()) {
                            int amt = m.items.get(item);
                            if (amt > 0) {
                                int canAccept = Math.max(0, totalItemCap - leader.items.total());
                                int transfer = amt; // 全额并入：总量必然 ≤ 合并后容量，截断只会丢物品
                                if (transfer > 0)
                                    leader.items.add(item, transfer);
                            }
                        }
                    }
                }
                for (CombinedOverdriveProjectorBuild m : group())
                    if (m.isValid())
                        m.items = leader.items;
            }
            if (leader.liquids != null) {
                processedLiquids.add(leader.liquids);
                for (CombinedOverdriveProjectorBuild m : group()) {
                    if (m != leader && m.isValid() && m.liquids != null && !processedLiquids.contains(m.liquids)) {
                        processedLiquids.add(m.liquids);
                        for (Liquid liquid : content.liquids()) {
                            float amt = m.liquids.get(liquid);
                            if (amt > 0.001f) {
                                float canAccept = Math.max(0f, totalLiquidCap - leader.liquids.get(liquid));
                                float transfer = amt; // 全额并入：总量必然 ≤ 合并后容量，截断只会丢物品
                                if (transfer > 0.001f)
                                    leader.liquids.add(liquid, transfer);
                            }
                        }
                    }
                }
                for (CombinedOverdriveProjectorBuild m : group())
                    if (m.isValid())
                        m.liquids = leader.liquids;
            }
        }

        @Override
        public void created() {
            super.created();
            comboDirty = true;
        }

        @Override
        public void onProximityUpdate() {
            super.onProximityUpdate();
            comboDirty = true;
            for (CombinedOverdriveProjectorBuild m : group())
                if (m.isValid())
                    m.comboDirty = true;
        }

        @Override
        public void onRemoved() {
            Seq<CombinedOverdriveProjectorBuild> members = new Seq<>(group());
            boolean wasLeader = isLeader();
            ItemModule oldItems = this.items;
            LiquidModule oldLiquids = this.liquids;
            if (!wasLeader) {
                if (items != null) {
                    boolean shared = false;
                    for (CombinedOverdriveProjectorBuild m : members)
                        if (m != this && m.isValid() && m.items == this.items) {
                            shared = true;
                            break;
                        }
                    if (shared)
                        items = new ItemModule();
                }
                if (liquids != null) {
                    boolean shared = false;
                    for (CombinedOverdriveProjectorBuild m : members)
                        if (m != this && m.isValid() && m.liquids == this.liquids) {
                            shared = true;
                            break;
                        }
                    if (shared)
                        liquids = new LiquidModule();
                }
            }
            if (wasLeader) {
                Seq<CombinedOverdriveProjectorBuild> survivors = new Seq<>();
                for (CombinedOverdriveProjectorBuild b : members)
                    if (b != this && b.isValid())
                        survivors.add(b);
                int[] itemCaps = new int[survivors.size];
                float[] liquidCaps = new float[survivors.size];
                int totalItemCap = 0;
                float totalLiquidCap = 0f;
                for (int i = 0; i < survivors.size; i++) {
                    CombinedOverdriveProjectorBuild b = survivors.get(i);
                    itemCaps[i] = b.block.itemCapacity;
                    liquidCaps[i] = ((CombinedOverdriveProjector) b.block).baseLiquidCapacity;
                    totalItemCap += itemCaps[i];
                    totalLiquidCap += liquidCaps[i];
                }
                ItemModule[] itemMods = new ItemModule[survivors.size];
                LiquidModule[] liquidMods = new LiquidModule[survivors.size];
                for (int i = 0; i < survivors.size; i++) {
                    itemMods[i] = new ItemModule();
                    liquidMods[i] = new LiquidModule();
                }
                if (oldItems != null && totalItemCap > 0) {
                    int[] allocated = new int[survivors.size];
                    for (Item item : cachedItems) {
                        int total = oldItems.get(item);
                        if (total <= 0)
                            continue;
                        int remaining = total;
                        for (int i = 0; i < survivors.size; i++) {
                            int ideal = (i == survivors.size - 1) ? remaining
                                    : Math.round(total * (float) itemCaps[i] / totalItemCap);
                            ideal = Math.min(ideal, remaining);
                            int canTake = Math.max(0, itemCaps[i] - allocated[i]);
                            int share = ideal; // 不做容量硬截断：超出份额暂时超容保留，宁可超容也不丢物品
                            if (share > 0) {
                                itemMods[i].add(item, share);
                                allocated[i] += share;
                                remaining -= share;
                            }
                        }
                    }
                }
                if (oldLiquids != null && totalLiquidCap > 0.001f) {
                    float[] allocated = new float[survivors.size];
                    for (Liquid liquid : cachedLiquids) {
                        float total = oldLiquids.get(liquid);
                        if (total <= 0.001f)
                            continue;
                        float remaining = total;
                        for (int i = 0; i < survivors.size; i++) {
                            float ideal = (i == survivors.size - 1) ? remaining
                                    : total * liquidCaps[i] / totalLiquidCap;
                            ideal = Math.min(ideal, remaining);
                            float canTake = Math.max(0f, liquidCaps[i] - allocated[i]);
                            float share = ideal; // 不做容量硬截断：超出份额暂时超容保留，宁可超容也不丢物品
                            if (share > 0.001f) {
                                liquidMods[i].add(liquid, share);
                                allocated[i] += share;
                                remaining -= share;
                            }
                        }
                    }
                }
                for (int i = 0; i < survivors.size; i++) {
                    CombinedOverdriveProjectorBuild b = survivors.get(i);
                    b.items = itemMods[i];
                    b.liquids = liquidMods[i];
                    b.comboLeader = null;
                    b.comboGroup = new Seq<>();
                    b.comboDirty = true;
                    b.comboTotalLiquidCap = 0f;
                    b.comboTotalItemCap = 0;
                }
            } else {
                CombinedOverdriveProjectorBuild leader = leader();
                if (leader != null && leader.isValid() && leader != this)
                    leader.comboDirty = true;
            }
            comboLeader = null;
            comboGroup = new Seq<>();
            comboDirty = false;
            comboTotalLiquidCap = 0f;
            comboTotalItemCap = 0;
            super.onRemoved();
        }

        @Override
        public void updateTile() {
            if (pendingLeaderPos != -1) {
                Building b = world.build(pendingLeaderPos);
                if (b instanceof CombinedOverdriveProjectorBuild leaderBuild && leaderBuild.isValid()
                        && leaderBuild.team == team) {
                    comboLeader = leaderBuild;
                    if (leaderBuild.items != null)
                        items = leaderBuild.items;
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
            if (liquids != null && comboTotalLiquidCap > 0.001f) {
                for (Liquid l : content.liquids()) {
                    float amt = liquids.get(l);
                    if (amt > comboTotalLiquidCap + 0.001f)
                        liquids.remove(l, amt - comboTotalLiquidCap);
                }
            }
            super.updateTile();
        }

        @Override
        public boolean shouldAmbientSound() {
            // 禁用环境音循环，避免触发 SoundControl 环境音线程在 Android 上的
            // IllegalThreadStateException（Thread.start 崩溃）
            return false;
        }

        @Override
        public boolean acceptItem(Building source, Item item) {
            if (!block.hasItems)
                return false;
            boolean needed = ComboReflect.groupConsumesItem(this, item);
            return needed && items.get(item) < getMaximumAccepted(item); // 按种类检查：每种原料各有份额，先到的不堵死其它的：不再按种类各装满一份
        }

        @Override
        public int getMaximumAccepted(Item item) {
            return Math.max(comboTotalItemCap, 1);
        }

        @Override
        public void handleItem(Building source, Item item) {
            items.add(item, 1);
        }

        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            if (!block.hasLiquids)
                return false;
            boolean needed = ComboReflect.groupConsumesLiquid(this, liquid);
            return needed && liquids.get(liquid) < comboTotalLiquidCap - 0.001f;
        }

        @Override
        public void handleLiquid(Building source, Liquid liquid, float amount) {
            if (amount <= 0.001f)
                return;
            float canAccept = Math.max(0f, comboTotalLiquidCap - liquids.get(liquid));
            float actual = Math.min(amount, canAccept);
            if (actual > 0.001f)
                liquids.add(liquid, actual);
            // FIX: 将未接收的液体退回源端，防止因 block.liquidCapacity=9999f 导致源端过度扣除
            float refund = amount - actual;
            if (refund > 0.001f && source != null && source.liquids != null)
                source.liquids.add(liquid, refund);
        }

        @Override
        public void display(Table table) {
          // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
          ComboUi.safe("combinedoverdriveprojector:display", () -> displayInner(table));
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
                    String title = count > 1 ? "[accent]组合超速投影[] x" + count + "\n" + block.getDisplayName(tile)
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
                    buildComboBars(barsTable);
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

        public void buildComboBars(Table table) {
            if (!Mathf.zero(block.health, 0.001f)) {
                final float h = health, mh = maxHealth;
                table.add(new Bar(() -> Core.bundle.get("stat.health", "Health") + " " + (int) Math.max(h, 0),
                        () -> Pal.health, () -> Mathf.clamp(h / mh)));
                table.row();
            }
            float totalPower = 0f;
            for (CombinedOverdriveProjectorBuild member : group()) {
                if (member.isValid() && member.block.consPower != null)
                    totalPower += member.block.consPower.usage;
            }
            if (totalPower > 0 && power != null) {
                final float tp = totalPower;
                table.add(new Bar(() -> "电力 " + Strings.fixed(tp * power.status * 60f, 1) + " ⚡/s", () -> Pal.power,
                        () -> power.status));
                table.row();
            }
            // 收集组合体中所有涉及物品（跨类型）
            Seq<Item> involvedItems = new Seq<>();
            for (CombinedOverdriveProjectorBuild member : group()) {
                if (member.isValid()) {
                    for (Item item : ((CombinedOverdriveProjector) member.block).cachedItems) {
                        if (!involvedItems.contains(item))
                            involvedItems.add(item);
                    }
                }
            }
            if (items != null) {
                for (Item item : combine.util.ComboReflect.displayItems(items, involvedItems)) {
                    int total = items.get(item);
                    if (total > 0) {
                        final int t = total, c = Math.max(comboTotalItemCap, 1);
                        table.add(new Bar(() -> item.localizedName + ": " + t + "/" + c, () -> item.color,
                                () -> (float) t / c));
                        table.row();
                    }
                }
            }
            // 收集组合体中所有涉及液体（跨类型）
            Seq<Liquid> involvedLiquids = new Seq<>();
            for (CombinedOverdriveProjectorBuild member : group()) {
                if (member.isValid()) {
                    for (Liquid liquid : ((CombinedOverdriveProjector) member.block).cachedLiquids) {
                        if (!involvedLiquids.contains(liquid))
                            involvedLiquids.add(liquid);
                    }
                }
            }
            LiquidModule sharedLiq = this.liquids;
            if (sharedLiq == null) {
                CombinedOverdriveProjectorBuild l = leader();
                if (l != null)
                    sharedLiq = l.liquids;
            }
            if (sharedLiq == null) {
                for (CombinedOverdriveProjectorBuild member : group()) {
                    if (member.liquids != null) {
                        sharedLiq = member.liquids;
                        break;
                    }
                }
            }
            if (sharedLiq != null) {
                for (Liquid liquid : involvedLiquids) {
                    float total = sharedLiq.get(liquid);
                    if (total > 0.001f) {
                        final float t = total, c = Math.max(comboTotalLiquidCap, 1f);
                        table.add(new Bar(
                                () -> liquid.localizedName + ": " + Strings.fixed(t, 1) + "/" + Strings.fixed(c, 1),
                                () -> liquid.barColor != null ? liquid.barColor : liquid.color, () -> t / c));
                        table.row();
                    }
                }
            }
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
            Seq<Block> sortedBlocks = new Seq<>();
            for (Block b : blockCounts.keys())
                sortedBlocks.add(b);
            sortedBlocks.sort(b -> b.id);
            boolean hasContent = false;
            for (Block b : sortedBlocks) {
                int count = blockCounts.get(b, 0);
                if (count > 0) {
                    hasContent = true;
                    table.add(b.localizedName + "*" + count).color(Color.white).left();
                    table.row();
                }
            }
            if (!hasContent) {
                table.add("[darkGray]无").left();
                table.row();
            }
        }

        // 地图区里只写"原版那一份字节"（OverdriveBuild 写 heat/phaseHeat，super.write 就是它），
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
                applyLeader(read, true); // 旧档（≤2.6）：模组字段直接写在地图区里
                return;
            }
            comboDirty = true;
        }

        @Override
        public void readCombo(Reads read, byte revision) {
            // 新格式里"非组长"在存档里写的就是空模块，读档时整组已经被并成一份，
            // 这里不能再清空（清了就把并好的池子丢掉）。
            applyLeader(read, false);
        }

        void applyLeader(Reads read, boolean clearModules) {
            boolean hasLeader = read.bool();
            int leaderPos = hasLeader ? read.i() : -1;
            comboDirty = true;
            if (hasLeader && leaderPos != pos()) {
                pendingLeaderPos = leaderPos;
                if (clearModules && items != null)
                    items = new ItemModule();
                if (clearModules && liquids != null)
                    liquids = new LiquidModule();
            } else {
                pendingLeaderPos = -1;
                comboLeader = null;
            }
        }

    }
}
