package combine.storage;

import arc.struct.Seq;
import mindustry.content.Items;
import mindustry.type.Item;
import mindustry.world.blocks.storage.CoreBlock;

import static mindustry.Vars.content;

/**
 * 组合核心：只做一件事 —— **不让原版把玩家的存货删掉**。
 *
 * 原版 {@link CoreBlock.CoreBuild#onProximityUpdate()} 每次重算都会：
 * <pre>
 *   storageCapacity = itemCapacity + 相邻的 StorageBlock 容量;
 *   for(Item item : content.items()) items.set(item, Math.min(items.get(item), storageCapacity));
 * </pre>
 * 也就是说它**按自己算出来的容量把超出的存货直接删掉**。而"组合仓库链 / 组合节点接过来的仓库"
 * 扩出来的那部分容量原版根本不认 —— 只要核心附近放/拆一个方块触发一次重算，
 * 玩家的存货就会按"核心自身容量"被削掉一大截（用户报的"造新组合仓库时物品消失"）。
 *
 * 这里在调用 super 前后把数量记下来、被削掉的补回去，并把容量抬到至少装得下现有存量；
 * 容量本身仍然由 {@link CombinedStorageBlock} 按"核心 + 连通仓库"重算。
 */
public class CombinedCoreBlock extends CoreBlock {

    public CombinedCoreBlock(String name) {
        super(name);
        buildType = CombinedCoreBuild::new;
    }

    public class CombinedCoreBuild extends CoreBuild {
        @Override
        public void onProximityUpdate() {
            // 调用前把每种物品的数量记下来（content.items() 的顺序稳定）
            int[] before = null;
            if (items != null) {
                before = new int[content.items().size];
                for (Item item : content.items())
                    before[item.id] = items.get(item);
            }

            super.onProximityUpdate();

            if (before == null || items == null)
                return;
            // 把被原版"超容截断"删掉的部分补回来：那些容量是组合仓库给的，原版不认
            boolean restored = false;
            for (Item item : content.items()) {
                int old = before[item.id];
                if (old > items.get(item)) {
                    items.set(item, old);
                    restored = true;
                }
            }
            if (restored || items.total() > storageCapacity) {
                // 容量抬到至少装得下现有存量，免得下一帧又被削
                storageCapacity = Math.max(storageCapacity, items.total());
            }
        }
    }
}
