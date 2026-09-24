package combine.units;
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
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.blocks.campaign.LandingPad;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.*;

/**
 * 组合接收台 —— 相邻同型自动组合，共享物品/液体池。
 * 每台保留自己的落地动画与冷却；落地物品进入共享物品池，
 * 冷却水从共享液体池扣除，池子由全体成员向外输出。
 */
public class CombinedLandingPad extends LandingPad {
    public boolean allowCrossTypeCombo = true;
    public float baseLiquidCapacity = 10f;
    public float displayLiquid;

    public Seq<Item> cachedItems = new Seq<>();
    public Seq<Liquid> cachedLiquids = new Seq<>();

    public CombinedLandingPad(String name) {
        super(name);
        hasItems = true;
        hasLiquids = true;
        solid = true;
        update = true;
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
        hasItems = true;
        conductivePower = true;

        cachedItems.clear();
        for (Item item : content.items())
            cachedItems.add(item);
        cachedLiquids.clear();
        if (consumeLiquid != null && !cachedLiquids.contains(consumeLiquid))
            cachedLiquids.add(consumeLiquid);
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.remove(Stat.liquidCapacity);
        stats.add(Stat.liquidCapacity, displayLiquid, StatUnit.liquidUnits);
    }

    public class CombinedLandingPadBuild extends LandingPadBuild implements combine.saves.ComboSaved {
        public CombinedLandingPadBuild comboLeader;
        public Seq<CombinedLandingPadBuild> comboGroup = new Seq<>();
        public boolean comboDirty = true;
        public float comboTotalLiquidCap = 0f;
        public int comboTotalItemCap = 0;
        public int pendingLeaderPos = -1;

        public CombinedLandingPadBuild leader() {
            if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null))
                comboLeader = null;
            return comboLeader == null ? this : comboLeader;
        }

        public boolean isLeader() {
            return leader() == this;
        }

        public Seq<CombinedLandingPadBuild> group() {
            CombinedLandingPadBuild l = leader();
            if (l.comboGroup == null)
                l.comboGroup = new Seq<>();
            return l.comboGroup;
        }

        // -------------------- 组合重建 --------------------
        public void rebuildCombo() {
            Seq<CombinedLandingPadBuild> oldGroup = comboGroup != null ? new Seq<>(comboGroup) : new Seq<>();
            comboGroup = new Seq<>();
            comboGroup.add(this);
            // 分组 BFS 穿过组合节点/连接器：被节点连上 = 效果相当于直接组合（同 LinkWall 语义）
            for (Building b : ComboReflect.linkedReachable(this,
                    o -> o instanceof CombinedLandingPadBuild other && other.team == team && other.isValid(),
                    (cur, o) -> cur.block == o.block
                    || ((CombinedLandingPad) cur.block).allowCrossTypeCombo
                    || ((CombinedLandingPad) o.block).allowCrossTypeCombo)) {
                if (b != this)
                    comboGroup.add((CombinedLandingPadBuild) b);
            }
            CombinedLandingPadBuild newLeader = this;
            for (CombinedLandingPadBuild b : comboGroup)
                if (b.isValid() && b.pos() < newLeader.pos())
                    newLeader = b;
            Seq<CombinedLandingPadBuild> newGroup = new Seq<>(comboGroup);
            newLeader.comboGroup = newGroup;
            for (CombinedLandingPadBuild b : newGroup) {
                if (b.isValid()) {
                    b.comboLeader = newLeader;
                    b.comboGroup = newGroup;
                    b.comboDirty = false;
                }
            }
            newLeader.comboLeader = null;

            float totalLiqCap = 0f;
            int totalItemCap = 0;
            for (CombinedLandingPadBuild b : newGroup) {
                if (b.isValid()) {
                    totalLiqCap += ((CombinedLandingPad) b.block).baseLiquidCapacity;
                    totalItemCap += b.block.itemCapacity;
                }
            }
            for (CombinedLandingPadBuild b : newGroup) {
                if (b.isValid()) {
                    b.comboTotalLiquidCap = totalLiqCap;
                    b.comboTotalItemCap = totalItemCap;
                }
            }

            if (oldGroup.size > newGroup.size)
                splitAssets(oldGroup, newGroup);
            shareModules(newLeader);

            for (CombinedLandingPadBuild old : oldGroup) {
                if (old != this && old.isValid() && !newGroup.contains(old)) {
                    old.comboLeader = null;
                    old.comboGroup = new Seq<>();
                    old.comboDirty = true;
                    old.comboTotalLiquidCap = 0f;
                    old.comboTotalItemCap = 0;
                }
            }
        }

        public void splitAssets(Seq<CombinedLandingPadBuild> oldGroup,
                Seq<CombinedLandingPadBuild> newGroup) {
            // FIX[双重拆分]: 网络层(ComboNet)可能已把池子按组件拆过一轮、给部分成员发过新模块。
            // 那些份额先并回主池（新组长持有的模块），再由本地逻辑按容量统一重分，
            // 否则两轮拆分各分各的，总额对不上账（物资凭空丢/复制）。
            CombinedLandingPadBuild newLeader = null;
            for (CombinedLandingPadBuild b : newGroup)
                if (b.isValid() && (newLeader == null || b.pos() < newLeader.pos()))
                    newLeader = b;
            if (newLeader == null)
                newLeader = this;
            ItemModule poolItems = newLeader.items;
            LiquidModule poolLiquids = newLeader.liquids;
            // 【池子可能不只本组在用】核心库存 / 组合节点接过来的别的组合体也在引用它时，
            // 本地拆分一律不动这口池子（退出成员拿空模块，由 ComboNet 按网络重新分配）。
            // 否则就是从核心库存里搬东西给退出的工厂 —— 用户报的"拆工厂时核心里的东西全没了"。
            boolean poolOurs = !ComboReflect.itemPoolSharedOutside(poolItems, oldGroup);
            boolean liquidPoolOurs = !ComboReflect.liquidPoolSharedOutside(poolLiquids, oldGroup);
            for (CombinedLandingPadBuild b : oldGroup) {
                if (!b.isValid()) continue;
                if (poolOurs && poolItems != null && b.items != null && b.items != poolItems) {
                    for (Item item : content.items()) {
                        int amt = b.items.get(item);
                        if (amt > 0) { poolItems.add(item, amt); b.items.remove(item, amt); }
                    }
                }
                if (liquidPoolOurs && poolLiquids != null && b.liquids != null && b.liquids != poolLiquids) {
                    for (Liquid liquid : content.liquids()) {
                        float amt = b.liquids.get(liquid);
                        if (amt > 0.001f) { poolLiquids.add(liquid, amt); b.liquids.remove(liquid, amt); }
                    }
                }
            }
            CombinedLandingPadBuild oldLeader = newLeader;
            ItemModule oldItems = poolItems;
            LiquidModule oldLiquids = poolLiquids;
            Seq<CombinedLandingPadBuild> kicked = new Seq<>();
            for (CombinedLandingPadBuild b : oldGroup)
                if (b.isValid() && !newGroup.contains(b))
                    kicked.add(b);
            if (kicked.isEmpty())
                return;

            int oldTotalItemCap = 0;
            float oldTotalLiquidCap = 0f;
            for (CombinedLandingPadBuild b : oldGroup) {
                if (b.isValid()) {
                    oldTotalItemCap += b.block.itemCapacity;
                    oldTotalLiquidCap += ((CombinedLandingPad) b.block).baseLiquidCapacity;
                }
            }
            int[] itemCaps = new int[kicked.size];
            float[] liquidCaps = new float[kicked.size];
            int kickedTotalItemCap = 0;
            float kickedTotalLiquidCap = 0f;
            for (int i = 0; i < kicked.size; i++) {
                CombinedLandingPadBuild b = kicked.get(i);
                itemCaps[i] = b.block.itemCapacity;
                liquidCaps[i] = ((CombinedLandingPad) b.block).baseLiquidCapacity;
                kickedTotalItemCap += itemCaps[i];
                kickedTotalLiquidCap += liquidCaps[i];
            }
            ItemModule[] newItemMods = new ItemModule[kicked.size];
            LiquidModule[] newLiquidMods = new LiquidModule[kicked.size];
            for (int i = 0; i < kicked.size; i++) {
                newItemMods[i] = new ItemModule();
                newLiquidMods[i] = new LiquidModule();
            }
            if (poolOurs && oldItems != null && oldTotalItemCap > 0 && kickedTotalItemCap > 0) {
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
                        int share = ideal; // 不硬截断
                        if (share > 0) {
                            newItemMods[i].add(item, share);
                            remaining -= share;
                        }
                    }
                    oldItems.remove(item, kickedTotalShare - remaining);
                }
            }
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
                        float share = ideal; // 不硬截断
                        if (share > 0.001f) {
                            newLiquidMods[i].add(liquid, share);
                            remaining -= share;
                        }
                    }
                    oldLiquids.remove(liquid, kickedTotalShare - remaining);
                }
            }
            if (poolOurs && oldItems != null)
                for (CombinedLandingPadBuild b : newGroup)
                    if (b.isValid())
                        b.items = oldItems;
            if (liquidPoolOurs && oldLiquids != null)
                for (CombinedLandingPadBuild b : newGroup)
                    if (b.isValid())
                        b.liquids = oldLiquids;
            for (int i = 0; i < kicked.size; i++) {
                CombinedLandingPadBuild b = kicked.get(i);
                b.items = newItemMods[i];
                b.liquids = newLiquidMods[i];
            }
        }

        public void shareModules(CombinedLandingPadBuild leader) {
            int totalItemCap = leader.comboTotalItemCap;
            float totalLiquidCap = leader.comboTotalLiquidCap;
            if (leader.items == null) {
                for (CombinedLandingPadBuild m : group())
                    if (m.items != null) {
                        leader.items = m.items;
                        break;
                    }
            }
            if (leader.liquids == null) {
                for (CombinedLandingPadBuild m : group())
                    if (m.liquids != null) {
                        leader.liquids = m.liquids;
                        break;
                    }
            }
            // 【池子归属】组里有人拿着"组外也在用"的那份模块（核心库存/网络池）时，
            // 组长必须换成那一份再并池：否则会把核心库存复制进组长自己的模块里
            //（核心没动、工厂也多一份同样的物品），网络层随后把这多出来的一份并回核心 —— 库存凭空翻倍。
            ObjectSet<ItemModule> sharedItemPools = ComboReflect.itemPoolsSharedOutside(group());
            ObjectSet<LiquidModule> sharedLiquidPools = ComboReflect.liquidPoolsSharedOutside(group());
            ItemModule sharedItems = sharedItemPools.isEmpty() ? null : sharedItemPools.first();
            if (sharedItems != null) leader.items = sharedItems;
            LiquidModule sharedLiquids = sharedLiquidPools.isEmpty() ? null : sharedLiquidPools.first();
            if (sharedLiquids != null) leader.liquids = sharedLiquids;
            ObjectSet<ItemModule> processedItems = new ObjectSet<>();
            ObjectSet<LiquidModule> processedLiquids = new ObjectSet<>();
            if (leader.items != null) {
                processedItems.add(leader.items);
                for (CombinedLandingPadBuild m : group()) {
                    if (m != leader && m.isValid() && m.items != null && !processedItems.contains(m.items)
                            // 组外也在用的模块不能"全额并入"（并入不清空源模块 = 凭空多一份）
                            && !sharedItemPools.contains(m.items)) {
                        processedItems.add(m.items);
                        for (Item item : content.items()) {
                            int amt = m.items.get(item);
                            if (amt > 0)
                                leader.items.add(item, amt); // 全额并入
                        }
                    }
                }
                for (CombinedLandingPadBuild m : group())
                    if (m.isValid())
                        m.items = leader.items;
                if (leader.items != null) leader.items.stopFlow(); // 组内搬池子不算流量（见 ComboNet.moveItems）
            }
            if (leader.liquids != null) {
                processedLiquids.add(leader.liquids);
                for (CombinedLandingPadBuild m : group()) {
                    if (m != leader && m.isValid() && m.liquids != null
                            && !processedLiquids.contains(m.liquids)
                            && !sharedLiquidPools.contains(m.liquids)) {
                        processedLiquids.add(m.liquids);
                        for (Liquid liquid : content.liquids()) {
                            float amt = m.liquids.get(liquid);
                            if (amt > 0.001f)
                                leader.liquids.add(liquid, amt); // 全额并入
                        }
                    }
                }
                for (CombinedLandingPadBuild m : group())
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
            for (CombinedLandingPadBuild m : group())
                if (m.isValid())
                    m.comboDirty = true;
        }

        @Override
        public void onRemoved() {
            Seq<CombinedLandingPadBuild> members = new Seq<>(group());
            boolean wasLeader = isLeader();
            ItemModule oldItems = this.items;
            // 【别动不属于本组的池子】核心库存/别的组合体也在用的那份模块：既不能按容量
            // "分一份给幸存者"（那是从核心库存里搬东西），也不能复制一份（核心库存会凭空翻倍）。
            boolean poolOurs = !ComboReflect.itemPoolSharedOutside(oldItems, members);
            LiquidModule oldLiquids = this.liquids;
            boolean liquidPoolOurs = !ComboReflect.liquidPoolSharedOutside(oldLiquids, members);
            if (!wasLeader) {
                if (items != null) {
                    boolean shared = false;
                    for (CombinedLandingPadBuild m : members)
                        if (m != this && m.isValid() && m.items == this.items) {
                            shared = true;
                            break;
                        }
                    if (shared)
                        items = new ItemModule();
                }
                if (liquids != null) {
                    boolean shared = false;
                    for (CombinedLandingPadBuild m : members)
                        if (m != this && m.isValid() && m.liquids == this.liquids) {
                            shared = true;
                            break;
                        }
                    if (shared)
                        liquids = new LiquidModule();
                }
            }
            if (wasLeader) {
                Seq<CombinedLandingPadBuild> survivors = new Seq<>();
                for (CombinedLandingPadBuild b : members)
                    if (b != this && b.isValid())
                        survivors.add(b);
                int[] itemCaps = new int[survivors.size];
                float[] liquidCaps = new float[survivors.size];
                int totalItemCap = 0;
                float totalLiquidCap = 0f;
                for (int i = 0; i < survivors.size; i++) {
                    CombinedLandingPadBuild b = survivors.get(i);
                    itemCaps[i] = b.block.itemCapacity;
                    liquidCaps[i] = ((CombinedLandingPad) b.block).baseLiquidCapacity;
                    totalItemCap += itemCaps[i];
                    totalLiquidCap += liquidCaps[i];
                }
                ItemModule[] itemMods = new ItemModule[survivors.size];
                LiquidModule[] liquidMods = new LiquidModule[survivors.size];
                for (int i = 0; i < survivors.size; i++) {
                    itemMods[i] = new ItemModule();
                    liquidMods[i] = new LiquidModule();
                }
                if (poolOurs && oldItems != null && totalItemCap > 0) {
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
                    CombinedLandingPadBuild b = survivors.get(i);
                    b.items = itemMods[i];
                    b.liquids = liquidMods[i];
                    b.comboLeader = null;
                    b.comboGroup = new Seq<>();
                    b.comboDirty = true;
                    b.comboTotalLiquidCap = 0f;
                    b.comboTotalItemCap = 0;
                }
            } else {
                CombinedLandingPadBuild leader = leader();
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

        // -------------------- 核心逻辑 --------------------
        @Override
        public void updateTile() {
            if (pendingLeaderPos != -1) {
                Building b = world.build(pendingLeaderPos);
                if (b instanceof CombinedLandingPadBuild leaderBuild && leaderBuild.isValid()
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

            // 原版 LandingPadBuild.updateTile：等待队列、落地动画、
            // items.set(arriving, itemCapacity) 入池、耗水、向外输出 ——
            // items/liquids 已指向共享池，天然池化，无需改动。
            super.updateTile();

            // 液体超限反注入：池子超过组合总容量（旧档残留/合并超额）时，
            // 把多余液体推回相邻液体网络，而不是截断销毁。
            if (liquids != null && comboTotalLiquidCap > 0.001f) {
                for (Liquid l : content.liquids()) {
                    if (liquids.get(l) > comboTotalLiquidCap + 0.001f)
                        dumpLiquid(l, 1f, -1);
                }
            }
        }

        @Override
        public int getMaximumAccepted(Item item) {
            return Math.max(comboTotalItemCap, 1);
        }

        // -------------------- 液体进出限制 --------------------
        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            if (!block.hasLiquids)
                return false;
            // 只接受冷却液（原版 consumeLiquid 字段本身就是 Liquid），
            // 且按组合池总量限制 —— 原版默认按 block.liquidCapacity(9999) 放行会超限
            boolean needed = consumeLiquid != null && liquid == consumeLiquid;
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
            // 未接收部分退回源端，防止源端因 9999 假容量被过度扣除
            float refund = amount - actual;
            if (refund > 0.001f && source != null && source.liquids != null)
                source.liquids.add(liquid, refund);
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
          ComboUi.safe("combinedlandingpad:display", () -> displayInner(table));
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
                            ? "[accent]组合接收台[] x" + count + "\n" + block.getDisplayName(tile)
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
                    for (CombinedLandingPadBuild member : group())
                        if (member.isValid() && member.block.consPower != null)
                            totalPowerUsage += member.block.consPower.usage;
                    ComboUi.addPowerBar(barsTable, this, totalPowerUsage);
                    final float cd = cooldown;
                    barsTable.add(new Bar(
                            () -> "接收冷却 " + Strings.fixed(cd * 100f, 0) + "%",
                            () -> Pal.accent, () -> 1f - cd));
                    barsTable.row();
                    if (items != null) {
                        final int t = items.total(), c = Math.max(comboTotalItemCap, 1);
                        barsTable.add(new Bar(() -> "物品 " + t + "/" + c, () -> Pal.items,
                                () -> (float) t / c));
                        barsTable.row();
                        for (Item item : content.items()) {
                            int total = items.get(item);
                            if (total > 0) {
                                final int ti = total;
                                barsTable.add(new Bar(
                                        () -> item.localizedName + ": " + ti + "/" + c,
                                        () -> item.color, () -> (float) ti / c));
                                barsTable.row();
                            }
                        }
                    }
                    LiquidModule liq = liquids;
                    if (liq == null) {
                        CombinedLandingPadBuild l = leader();
                        if (l != null)
                            liq = l.liquids;
                    }
                    if (liq != null) {
                        for (Liquid liquid : cachedLiquids) {
                            float total = liq.get(liquid);
                            final float t = total, c = Math.max(comboTotalLiquidCap, 1f);
                            barsTable.add(new Bar(
                                    () -> liquid.localizedName + ": " + Strings.fixed(t, 1) + "/"
                                            + Strings.fixed(c, 1),
                                    () -> liquid.barColor != null ? liquid.barColor : liquid.color,
                                    () -> t / c));
                            barsTable.row();
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
        // 地图区里只写"原版接收台那一份字节"（super.write 就是 LandingPadBuild 自己的布局），
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
