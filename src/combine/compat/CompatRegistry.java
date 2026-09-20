package combine.compat;

import arc.struct.ObjectMap;
import mindustry.world.Block;

public class CompatRegistry {

    private static final ObjectMap<Class<?>, Class<? extends Block>> MAP = new ObjectMap<>();

    public static void register(Class<? extends Block> source, Class<? extends Block> combo) {
        if (source == null || combo == null) return;
        MAP.put(source, combo);
    }

    public static boolean has(Class<?> source) {
        return source != null && MAP.containsKey(source);
    }

    public static Class<? extends Block> comboFor(Class<?> source) {
        if (source == null) return null;

        Class<? extends Block> direct = MAP.get(source);
        if (direct != null) return direct;

        Class<?> sup = source.getSuperclass();
        if (source.isAnonymousClass() && sup != null) {
            direct = MAP.get(sup);
            if (direct != null) return direct;
        }

        for (Class<?> c = sup; c != null && c != Block.class; c = c.getSuperclass()) {
            direct = MAP.get(c);
            if (direct != null) return direct;
        }
        return null;
    }

    public static int size() {
        return MAP.size;
    }
}