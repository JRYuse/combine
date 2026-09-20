package combine.util;
import arc.struct.Seq;
import mindustry.gen.Building;

/**
 * 被替换出来的组合建筑里，「自己维护一套按空间分组」的那些（目前是组合墙 LinkWall）。
 *
 * 这类建筑不走 comboGroup/comboLeader 那套约定，但已经提供了 {@code group()} / {@code leader()}，
 * 所以：
 *   · {@link ComboReflect#isComboBuild} 认它 → 组合节点/连接器可以把它当连接目标；
 *   · 组合节点/连接器的连线会喂给它的分组逻辑（于是"接上节点/连接器 = 完整的组合功能"，
 *     比如组合墙真的共享血量，而不是只画一根线）。
 */
public interface IComboGrouped {
    /** 本组合体的全部成员（不含组合连接器/节点本身）。 */
    Seq<? extends Building> group();

    /** 本组合体的组长（同组的其它成员都指向它）。 */
    Building leader();

    /** 分组需要重算（例如节点连线变了、连接器接上了）。 */
    void markGroupDirty();
}
