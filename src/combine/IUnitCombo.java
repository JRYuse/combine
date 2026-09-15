package combine;

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
import mindustry.world.modules.PowerModule;

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
                if (B(leaderBuild).power != null)
                    B(this).power = B(leaderBuild).power;
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
        IntSet visited = new IntSet();
        Queue<IUnitCombo> queue = new Queue<>();
        queue.add(this);
        visited.add(B(this).pos());
        while (!queue.isEmpty()) {
            IUnitCombo cur = queue.removeFirst();
            for (Building b : B(cur).proximity) {
                if (b instanceof IUnitCombo o && B(o).team == B(this).team && B(o).isValid()
                        && !visited.contains(B(o).pos())) {
                    // 同型总是可以; 跨型(工厂↔重构厂)看双方方块开关
                    if (B(cur).block == B(o).block || crossAllowed(cur) || crossAllowed(o)) {
                        visited.add(B(o).pos());
                        queue.addLast(o);
                        group().add(o);
                    }
                }
            }
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
                totalLiquidCap += B(b).block.liquidCapacity;
            }
        for (IUnitCombo b : newGroup)
            if (B(b).isValid()) {
                b.gItemCap(totalItemCap);
                b.gLiquidCap(totalLiquidCap);
            }

        if (oldGroup.size > newGroup.size)
            splitAssets(oldGroup, newGroup);
        shareModules(newLeader);

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
        IUnitCombo oldLeader = null;
        for (IUnitCombo b : oldGroup) {
            if (!B(b).isValid())
                continue;
            for (IUnitCombo o : oldGroup) {
                if (o != b && B(o).isValid()
                        && (B(o).items == B(b).items || B(o).liquids == B(b).liquids)) {
                    oldLeader = b;
                    break;
                }
            }
            if (oldLeader != null)
                break;
        }
        if (oldLeader == null) {
            for (IUnitCombo b : oldGroup)
                if (B(b).isValid()) {
                    oldLeader = b;
                    break;
                }
        }
        if (oldLeader == null)
            oldLeader = this;
        ItemModule oldItems = B(oldLeader).items;
        LiquidModule oldLiquids = B(oldLeader).liquids;
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

        // FIX[电力共享]: 全组共用领导者的电力模块——任一成员接电即整组通电。
        // links 存在 PowerModule 里: 先摘旧电网, 把成员自己的节点链接并入领导者模块, 再换模块
        if (B(leader).power != null) {
            for (IUnitCombo member : group()) {
                if (member != leader && B(member).isValid() && B(member).power != null
                        && B(member).power != B(leader).power) {
                    PowerModule oldPower = B(member).power;
                    if (oldPower.graph != null)
                        oldPower.graph.remove(B(member));
                    for (int li = 0; li < oldPower.links.size; li++) {
                        int link = oldPower.links.get(li);
                        if (!B(leader).power.links.contains(link))
                            B(leader).power.links.add(link);
                    }
                    B(member).power = B(leader).power;
                    B(member).updatePowerGraph();
                }
            }
        }
    }

    /** 完工料费实时复核：不依赖 potentialEfficiency 缓存, 直接按消耗器将要扣除的量盘点共享池 */
    default boolean canAffordNow() {
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
            if (B(this).power != null) {
                boolean sharedPower = false;
                for (IUnitCombo member : members) {
                    if (member != this && B(member).isValid() && B(member).power == B(this).power) {
                        sharedPower = true;
                        break;
                    }
                }
                if (sharedPower) {
                    // 换独立模块但保留电力连接(links 存在模块里, 直接 new 会丢光)
                    PowerModule oldPower = B(this).power;
                    B(this).power = new PowerModule();
                    if (oldPower != null) {
                        B(this).power.links.addAll(oldPower.links);
                        B(this).power.status = oldPower.status;
                    }
                    B(this).updatePowerGraph();
                }
            }
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
            }
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
}
