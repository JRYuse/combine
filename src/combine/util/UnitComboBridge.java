package combine.util;

import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Unit;

import java.lang.reflect.Method;

/**
 * 到 combineunit（组合单位）模组的**反射桥**：建筑侧只在这里碰单位侧。
 *
 * <p>【为什么是反射】单位侧机制（组合巨兽、共享承伤/修复、共享火力、编组 UI）已经拆到
 * combineunit 仓库单独维护。Mindustry 给每个模组独立类加载器，本仓库**编译期看不见**
 * combineunit 的类，也没法把它当依赖（玩家可能只装其中一个）——所以只能按类名反射调。
 *
 * <p>唯一的接触点：组合单位工厂/重组厂造出单位后要打组合标记
 * （{@code comboId = 工厂所在组合体的组长坐标 + 1}，见 combineunit 的
 * {@code UnitComboDamage.tagProduced(Unit, Building)}）。
 * 没装 combineunit 时这里是**静默空操作**：工厂照常造单位，只是造出来的单位不进任何组合
 *（这正是"分开装也能用"的语义）。
 *
 * <p>取类优先走 {@code Vars.mods.getMod("combineunit").main.getClass().getClassLoader()}，
 * 拿不到再退回默认类加载器（测试 harness 里模组类可能就在同一个 loader 上）。
 * 结果与"找不到/调用失败"都只算一次：这条路在"每次完工造单位"上跑，别每次都重新反射。
 */
public class UnitComboBridge {

    private static final String CLASS = "combineunit.units.UnitComboDamage";
    private static final String METHOD = "tagProduced";

    /** 三态：0 = 还没试过，1 = 找到了（method 非 null），-1 = 找不到（别反复试）。 */
    private static int state = 0;
    private static Method tag;
    /** 只把"第一次找不到"打一条日志，别刷屏。 */
    private static boolean warned = false;

    /** 给刚造出来的单位打组合标记；没有 combineunit 时什么都不做。 */
    public static void tagProduced(Unit unit, Building source) {
        if (unit == null || source == null)
            return;
        Method m = find();
        if (m == null)
            return;
        try {
            m.invoke(null, unit, source);
        } catch (Throwable t) {
            // 单位侧自己抛错不影响建筑侧生产：记一次就够了
            if (!warned) {
                warned = true;
                Log.err("[combine] 调用 combineunit 的 tagProduced 失败（单位侧机制可能没装好）", t);
            }
        }
    }

    /** combineunit 装了没（单位侧机制在不在）。 */
    public static boolean available() {
        return find() != null;
    }

    private static Method find() {
        if (state != 0)
            return state > 0 ? tag : null;
        state = -1;
        try {
            ClassLoader loader = null;
            var mod = Vars.mods == null ? null : Vars.mods.getMod("combineunit");
            if (mod != null && mod.main != null)
                loader = mod.main.getClass().getClassLoader();
            Class<?> c = loader == null ? Class.forName(CLASS) : Class.forName(CLASS, true, loader);
            tag = c.getMethod(METHOD, Unit.class, Building.class);
            tag.setAccessible(true);
            state = 1;
        } catch (Throwable t) {
            tag = null;
            state = -1;
            if (!warned) {
                warned = true;
                Log.info("[combine] 没找到 combineunit 的组合单位机制（@）—— 建筑侧照常工作，"
                    + "造出来的单位不进组合。装上 combineunit 即可。", CLASS);
            }
        }
        return tag;
    }
}
