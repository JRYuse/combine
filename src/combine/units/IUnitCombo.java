package combine.units;
import combine.util.ComboReflect;
import combine.util.ComboPower;
import arc.struct.IntSet;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Liquid;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItemDynamic;
import mindustry.world.consumers.ConsumeItems;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.*;

/**
 * 单位系组合建筑(CombinedUnitFactory / CombinedReconstructor)的共享机制。
 * 两个 Build 类都实现本接口, 从而工厂与重构厂可以跨类型成组,
 * 共享物品/液体/电力池。状态由各实现类字段持有, 这里只定义
 * 存取契约与组合算法(默认方法)。
 */
public interface IUnitCombo {

    // -------------------- 状态存取契约 --------------------
    IUnitCombo gLeader();

    void gLeader(IUnitCombo v);

    Seq<IUnitCombo> gGroup();

    void gGroup(Seq<IUnitCombo> v);

    boolean gDirty();

    void gDirty(boolean v);

    int gItemCap();

    void gItemCap(int v);

    float gLiquidCap();

    void gLiquidCap(float v);

    int gPending();

    void gPending(int v);

    static Building B(IUnitCombo m) {
        return (Building) m;
    }

    /** 跨类型开关: 由方块侧 allowCrossTypeCombo 字段决定 */
    static boolean crossAllowed(IUnitCombo m) {
        return B(m).block instanceof IUnitComboBlock x && x.allowCrossTypeCombo();
    }

    // -------------------- 组查询 --------------------
    default IUnitCombo leader() {
        if (gLeader() != null && (!B(gLeader()).isValid() || B(gLeader()).tile == null))
            gLeader(null);
        return gLeader() == null ? this : gLeader();
    }

    default boolean isLeader() {
        return leader() == this;
    }

    default Seq<IUnitCombo> group() {
        IUnitCombo l = leader();
        if (l.gGroup() == null)
            l.gGroup(new Seq<>());
        return l.gGroup();
    }

    // -------------------- 每 tick 维护(updateTile 开头调用) --------------------
    default void comboPreUpdate() {
        if (gPending() != -1) {
            Building b = world.build(gPending());
            if (b instanceof IUnitCombo leaderBuild && B(leaderBuild).isValid()
                    && B(leaderBuild).team == B(this).team) {
                gLeader(leaderBuild);
                if (B(leaderBuild).items != null)
                    B(this).items = B(leaderBuild).items;
                if (B(leaderBuild).liquids != null)
                    B(this).liquids = B(leaderBuild).liquids;
            } else {
                gLeader(null);
            }
            gPending(-1);
            gDirty(true);
        }
        if (isLeader() && gDirty())
            rebuildCombo();
    }

    // -------------------- 组合重建 --------------------
    default void rebuildCombo() {
        Seq<IUnitCombo> oldGroup = gGroup() != null ? new Seq<>(gGroup()) : new Seq<>();
        gGroup(new Seq<>());
        group().add(this);
        // 分组 BFS 穿过组合节点/连接器：被节点连上 = 效果相当于直接组合（同 LinkWall 语义）
        for (Building b : ComboReflect.linkedReachable(B(this),
                o -> o instanceof IUnitCombo u && B(u).team == B(this).team && B(u).isValid(),
                (cur, o) -> B((IUnitCombo) cur).block == B((IUnitCombo) o).block
                        || crossAllowed((IUnitCombo) cur) || crossAllowed((IUnitCombo) o))) {
            if (b != B(this))
                group().add((IUnitCombo) b);
        }
        IUnitCombo newLeader = this;
        for (IUnitCombo b : group())
            if (B(b).isValid() && B(b).pos() < B(newLeader).pos())
                newLeader = b;
        Seq<IUnitCombo> newGroup = new Seq<>(group());
        newLeader.gGroup(newGroup);
        for (IUnitCombo b : newGroup) {
            if (B(b).isValid()) {
                b.gLeader(newLeader);
                b.gGroup(newGroup);
                b.gDirty(false);
            }
        }
        newLeader.gLeader(null);

        int totalItemCap = 0;
        float totalLiquidCap = 0f;
        for (IUnitCombo b : newGroup)
            if (B(b).isValid()) {
                totalItemCap += B(b).block.itemCapacity;
                totalLiquidCap += ComboReflect.baseLiquidCap(B(b));
            }
        for (IUnitCombo b : newGroup)
            if (B(b).isValid()) {
                b.gItemCap(totalItemCap);
                b.gLiquidCap(totalLiquidCap);
            }

        if (oldGroup.size > newGroup.size)
            splitAssets(oldGroup, newGroup);
        shareModules(newLeader);
        // 组结构变了：全组过一遍电网对账（原版并网/拆网在组合体上可能漏掉几台）
        for (IUnitCombo b : newGroup)
            if (B(b).isValid())
                ComboPower.mark(B(b));

        for (IUnitCombo old : oldGroup) {
            if (old != this && B(old).isValid() && !newGroup.contains(old)) {
                old.gLeader(null);
                old.gGroup(new Seq<>());
                old.gDirty(true);
                old.gItemCap(0);
                old.gLiquidCap(0f);
            }
        }
    }

    default void splitAssets(Seq<IUnitCombo> oldGroup, Seq<IUnitCombo> newGroup) {
        // FIX[双重拆分]: 网络层(ComboNet)可能已把池子按组件拆过一轮、给部分成员发过新模块。
        // 那些份额先并回主池（新组长持有的模块），再由本地逻辑按容量统一重分，
        // 否则两轮拆分各分各的，总额对不上账（物资凭空丢/复制）。
        IUnitCombo newLeader = null;
        for (IUnitCombo b : newGroup)
            if (B(b).isValid() && (newLeader == null || B(b).pos() < B(newLeader).pos()))
                newLeader = b;
        if (newLeader == null)
            newLeader = this;
        ItemModule poolItems = B(newLeader).items;
        LiquidModule poolLiquids = B(newLeader).liquids;
        for (IUnitCombo b : oldGroup) {
            if (!B(b).isValid())
                continue;
            if (poolItems != null && B(b).items != null && B(b).items != poolItems) {
                for (Item item : content.items()) {
                    int amt = B(b).items.get(item);
                    if (amt > 0) {
                        poolItems.add(item, amt);
                        B(b).items.remove(item, amt);
                    }
                }
            }
            if (poolLiquids != null && B(b).liquids != null && B(b).liquids != poolLiquids) {
                for (Liquid liquid : content.liquids()) {
                    float amt = B(b).liquids.get(liquid);
                    if (amt > 0.001f) {
                        poolLiquids.add(liquid, amt);
                        B(b).liquids.remove(liquid, amt);
                    }
                }
            }
        }
        IUnitCombo oldLeader = newLeader;
        ItemModule oldItems = poolItems;
        LiquidModule oldLiquids = poolLiquids;
        Seq<IUnitCombo> kicked = new Seq<>();
        for (IUnitCombo b : oldGroup)
            if (B(b).isValid() && !newGroup.contains(b))
                kicked.add(b);
        if (kicked.isEmpty())
            return;

        int oldTotalItemCap = 0;
        float oldTotalLiquidCap = 0f;
        for (IUnitCombo b : oldGroup)
            if (B(b).isValid()) {
                oldTotalItemCap += B(b).block.itemCapacity;
                oldTotalLiquidCap += B(b).block.liquidCapacity;
            }
        int[] itemCaps = new int[kicked.size];
        float[] liquidCaps = new float[kicked.size];
        int kickedTotalItemCap = 0;
        float kickedTotalLiquidCap = 0f;
        for (int i = 0; i < kicked.size; i++) {
            IUnitCombo b = kicked.get(i);
            itemCaps[i] = B(b).block.itemCapacity;
            liquidCaps[i] = B(b).block.liquidCapacity;
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
            for (Item item : content.items()) {
                int total = oldItems.get(item);
                if (total <= 0)
                    continue;
                int remaining = total;
                for (int i = 0; i < kicked.size; i++) {
                    int ideal = (i == kicked.size - 1) ? remaining
                            : Math.round((float) total * itemCaps[i] / oldTotalItemCap);
                    ideal = Math.min(ideal, remaining);
                    if (ideal > 0) {
                        newItemMods[i].add(item, ideal);
                        remaining -= ideal;
                    }
                }
                oldItems.remove(item, total - remaining);
            }
        }
        if (oldLiquids != null && oldTotalLiquidCap > 0.001f && kickedTotalLiquidCap > 0.001f) {
            for (Liquid liquid : content.liquids()) {
                float total = oldLiquids.get(liquid);
                if (total <= 0.001f)
                    continue;
                float remaining = total;
                for (int i = 0; i < kicked.size; i++) {
                    float ideal = (i == kicked.size - 1) ? remaining
                            : total * liquidCaps[i] / oldTotalLiquidCap;
                    ideal = Math.min(ideal, remaining);
                    if (ideal > 0.001f) {
                        newLiquidMods[i].add(liquid, ideal);
                        remaining -= ideal;
                    }
                }
                oldLiquids.remove(liquid, total - remaining);
            }
        }
        if (oldItems != null)
            for (IUnitCombo b : newGroup)
                if (B(b).isValid())
                    B(b).items = oldItems;
        if (oldLiquids != null)
            for (IUnitCombo b : newGroup)
                if (B(b).isValid())
                    B(b).liquids = oldLiquids;
        for (int i = 0; i < kicked.size; i++) {
            B(kicked.get(i)).items = newItemMods[i];
            B(kicked.get(i)).liquids = newLiquidMods[i];
        }
    }

    default void shareModules(IUnitCombo leader) {
        if (B(leader).items == null) {
            for (IUnitCombo member : group()) {
                if (B(member).items != null) {
                    B(leader).items = B(member).items;
                    break;
                }
            }
        }
        if (B(leader).liquids == null) {
            for (IUnitCombo member : group()) {
                if (B(member).liquids != null) {
                    B(leader).liquids = B(member).liquids;
                    break;
                }
            }
        }

        ObjectSet<ItemModule> processedItems = new ObjectSet<>();
        ObjectSet<LiquidModule> processedLiquids = new ObjectSet<>();

        if (B(leader).items != null) {
            processedItems.add(B(leader).items);
            for (IUnitCombo member : group()) {
                if (member != leader && B(member).isValid() && B(member).items != null
                        && !processedItems.contains(B(member).items)) {
                    processedItems.add(B(member).items);
                    for (Item item : content.items()) {
                        int amt = B(member).items.get(item);
                        if (amt > 0)
                            B(leader).items.add(item, amt); // 全额并入
                    }
                }
            }
            for (IUnitCombo member : group())
                if (B(member).isValid())
                    B(member).items = B(leader).items;
        }

        if (B(leader).liquids != null) {
            processedLiquids.add(B(leader).liquids);
            for (IUnitCombo member : group()) {
                if (member != leader && B(member).isValid() && B(member).liquids != null
                        && !processedLiquids.contains(B(member).liquids)) {
                    processedLiquids.add(B(member).liquids);
                    for (Liquid liquid : content.liquids()) {
                        float amt = B(member).liquids.get(liquid);
                        if (amt > 0.001f)
                            B(leader).liquids.add(liquid, amt); // 全额并入
                    }
                }
            }
            for (IUnitCombo member : group())
                if (B(member).isValid())
                    B(member).liquids = B(leader).liquids;
        }

        // 【不再共用 PowerModule】见 CombinedCrafter.shareModules 的说明：共用一份模块会让
        // "电网里算进哪几台/算多少电"变成原版并网顺序的副作用 —— 在线摆的基地和读档/入服的基地
        // 会算出不同的电力数（联机时服务端与客户端对不上），拆网时还会把成员漏在旧电网上。
        // 组合方块本来就是导电体，相邻/连线自动并网，每台各留自己的模块即可。
    }

    /** 完工料费实时复核：不依赖 potentialEfficiency 缓存, 直接按消耗器将要扣除的量盘点共享池 */
    default boolean canAffordNow() {
        // 【无限火力 / 作弊模式（team.rules().cheat）下不能按"池里有没有料"判定】
        // 原版这一档直接在 BuildingComp.updateConsumption 里短路（efficiency = 1，
        // 消耗器的 efficiency 根本不参与计算），完工时的 consume() 也只是走个 trigger，
        // 池子里没料照样产出（X 端的"无限火力"就是给每个队伍打开 rules.cheat）。
        // 这里若还去盘点库存，组合工厂/重构厂/建造厂会永远卡在完工线不产出（用户报的）。
        if (B(this).cheating())
            return true;
        if (B(this).items == null)
            return false;
        for (Consume cons : B(this).block.nonOptionalConsumers) {
            if (cons instanceof ConsumeItemDynamic cid) {
                ItemStack[] reqs = cid.items.get(B(this));
                if (reqs != null) {
                    for (ItemStack stack : reqs) {
                        if (stack.amount > 0 && B(this).items.get(stack.item) < stack.amount)
                            return false;
                    }
                }
            } else if (cons instanceof ConsumeItems cis) {
                for (ItemStack stack : cis.items) {
                    if (stack.amount > 0 && B(this).items.get(stack.item) < stack.amount)
                        return false;
                }
            }
        }
        return true;
    }

    /** onRemoved 的组合资产拆分(在 super.onRemoved() 之前调用) */
    default void comboOnRemoved() {
        Seq<IUnitCombo> members = new Seq<>(group());
        boolean wasLeader = isLeader();
        ItemModule oldItems = B(this).items;
        LiquidModule oldLiquids = B(this).liquids;

        if (!wasLeader) {
            if (B(this).items != null) {
                boolean shared = false;
                for (IUnitCombo member : members) {
                    if (member != this && B(member).isValid() && B(member).items == B(this).items) {
                        shared = true;
                        break;
                    }
                }
                if (shared)
                    B(this).items = new ItemModule();
            }
            if (B(this).liquids != null) {
                boolean shared = false;
                for (IUnitCombo member : members) {
                    if (member != this && B(member).isValid()
                            && B(member).liquids == B(this).liquids) {
                        shared = true;
                        break;
                    }
                }
                if (shared)
                    B(this).liquids = new LiquidModule();
            }
            // 电力模块各归各的（见 shareModules），拆自己的时候不用再分离共享模块
            IUnitCombo l = leader();
            if (l != null && B(l).isValid() && l != this)
                l.gDirty(true);
        }

        if (wasLeader) {
            Seq<IUnitCombo> survivors = new Seq<>();
            for (IUnitCombo b : members) {
                if (b != this && B(b).isValid())
                    survivors.add(b);
            }

            int[] itemCaps = new int[survivors.size];
            float[] liquidCaps = new float[survivors.size];
            int totalItemCap = 0;
            float totalLiquidCap = 0f;
            for (int i = 0; i < survivors.size; i++) {
                Building bb = B(survivors.get(i));
                itemCaps[i] = bb.block.itemCapacity;
                liquidCaps[i] = bb.block.liquidCapacity;
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
                for (Item item : content.items()) {
                    int total = oldItems.get(item);
                    if (total <= 0)
                        continue;
                    int remaining = total;
                    for (int i = 0; i < survivors.size; i++) {
                        int ideal = (i == survivors.size - 1) ? remaining
                                : Math.round((float) total * itemCaps[i] / totalItemCap);
                        ideal = Math.min(ideal, remaining);
                        if (ideal > 0) {
                            itemMods[i].add(item, ideal);
                            remaining -= ideal;
                        }
                    }
                }
            }
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
                        if (ideal > 0.001f) {
                            liquidMods[i].add(liquid, ideal);
                            remaining -= ideal;
                        }
                    }
                }
            }
            for (int i = 0; i < survivors.size; i++) {
                Building bb = B(survivors.get(i));
                bb.items = itemMods[i];
                bb.liquids = liquidMods[i];
                survivors.get(i).gLeader(null);
                survivors.get(i).gGroup(new Seq<>());
                survivors.get(i).gDirty(true);
                survivors.get(i).gItemCap(0);
                survivors.get(i).gLiquidCap(0f);
                ComboPower.markAround(bb);
            }
            ComboPower.markAround(B(this));
        }
        gLeader(null);
        gGroup(new Seq<>());
        gDirty(false);
        gItemCap(0);
        gLiquidCap(0f);
    }

    /** 组合方块的跨类型开关契约(CombinedUnitFactory / CombinedReconstructor 的方块侧实现) */
    interface IUnitComboBlock {
        boolean allowCrossTypeCombo();
    }

    /** 组内是否有成员需要某物品。原版 consumesItem 认不出函数式 ConsumeItemDynamic（单位工厂），
     *  单位工厂成员需直接查 plans 的配方需求。 */
    static boolean groupNeedsItem(Seq<? extends IUnitCombo> group, Item item) {
        for (IUnitCombo m : group) {
            Building b = B(m);
            if (!b.isValid())
                continue;
            if (b.block.consumesItem(item))
                return true;
            if (b instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild) {
                for (mindustry.world.blocks.units.UnitFactory.UnitPlan plan
                        : ((mindustry.world.blocks.units.UnitFactory) b.block).plans) {
                    for (ItemStack stack : plan.requirements) {
                        if (stack.item == item)
                            return true;
                    }
                }
            }
        }
        return false;
    }
}
