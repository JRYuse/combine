package combine.compat;

import arc.struct.ObjectMap;
import mindustry.world.Block;

/**
 * 附属模组兼容注册表。
 *
 * 用法（附属侧）：
 *   CompatRegistry.register(WHItemTurret.class, CombinedWHItemTurret.class);
 *
 * combine 在内容装配时（以及 ClientLoadEvent 兜底）遍历方块，
 * 命中注册的具名子类就用对应的组合类走 createCombo 替换。
 *
 * combine 编译期不需要任何附属的类，泛型用 Block 通配即可。
 */
public class CompatRegistry {

    private static final ObjectMap<Class<?>, Class<? extends Block>> MAP = new ObjectMap<>();

    /**
     * 注册一个替换规则。
     * @param source 原方块类（具名子类，例如 WHItemTurret.class）
     * @param combo  替换目标类（通常 extends source，例如 CombinedWHItemTurret.class）
     */
    public static void register(Class<? extends Block> source, Class<? extends Block> combo) {
        if (source == null || combo == null) return;
        MAP.put(source, combo);
    }

    /** 这个类有没有注册过替换。 */
    public static boolean has(Class<?> source) {
        return source != null && MAP.containsKey(source);
    }

    /** 取注册的组合类，没注册返回 null。 */
    public static Class<? extends Block> comboFor(Class<?> source) {
        return source == null ? null : MAP.get(source);
    }

    /** 已注册的源类数量（调试用）。 */
    public static int size() {
        return MAP.size;
    }
}