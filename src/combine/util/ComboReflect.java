package combine.util;
import combine.coop.CoopCombo;
import combine.net.ComboNet;
import combine.production.CombinedGenerator.CombinedGeneratorBuild;
import combine.production.CombinedGenerator;
import combine.storage.CombinedStorageBlock;
import combine.units.IUnitCombo;
import arc.struct.Seq;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static mindustry.Vars.content;
import static mindustry.Vars.state;
import static mindustry.Vars.world;

/**
 * 反射访问所有 Combined* / IUnitCombo 建筑共有的组合字段。
 *
 * 新建筑(连接器/节点)不属于任何 Combined* 类，但它们要和已放置的老组合建筑
 * 互通，因此这里不依赖具体内部类，只依赖各组合建筑已经存在的约定字段：
 * comboLeader / comboGroup / comboDirty / comboTotalItemCap / comboTotalLiquidCap，
 * 以及 IUnitCombo 的存取方法。
 */
public class ComboReflect {

    public static boolean isComboBuild(Building b){
        if(b == null) return false;
        if(b instanceof IUnitCombo) return true;
        if(b.block instanceof CombinedStorageBlock) return true;
        // 协作组合（继承原版类、自己写了新功能的 js/java 方块）也当成组合方块：
        // 这样 ComboNet 的池子合并/拆分、连接器/节点、信息面板都能一视同仁。
        if(CoopCombo.eligible(b.block)) return true;
        return hasField(b, "comboGroup") || hasField(b, "comboLeader");
    }

    /**
     * 这台机器还属于**当前世界**吗？
     *
     * 读档会重建整个世界：旧世界留下的 Building 对象 {@code isValid()} 仍然是 true
     * （Mindustry 不逐个调用 remove()），只有 {@code world.tile(x,y).build} 才能分辨出
     * "这块地上的建筑已经换成新对象了"。各处的登记表/网络索引都必须用它过滤，
     * 否则读档后新旧两批机器会被当成两组、容量直接翻倍（倾倒站那种）。
     */
    public static boolean inWorld(Building b){
        if(b == null || !b.isValid()) return false;
        if(b.tile == null) return false;
        Tile t = world == null ? null : world.tile(b.tileX(), b.tileY());
        return t != null && t.build == b;
    }

    public static Building leader(Building b){
        if(b == null) return null;
        if(b instanceof IUnitCombo u){
            IUnitCombo l = u.leader();
            return l instanceof Building bl ? bl : b;
        }
        if(CoopCombo.eligible(b.block)) return CoopCombo.coopLeader(b);
        Object r = call(b, "leader");
        return r instanceof Building bl ? bl : b;
    }

    public static Seq<Building> group(Building b){
        Seq<Building> out = new Seq<>();
        if(b == null) return out;

        // 注意用 add 而不是 addUnique：各组合类自己的 group() 已经是去重的集合，
        // 这里只是过滤 isValid。addUnique 在 Seq 上是线性查找，对 N 台一组的组合体
        // 就是 O(N²) —— 信息面板每帧都要调这个，几百台一组时会明显掉帧。
        if(b instanceof IUnitCombo u){
            for(IUnitCombo m : u.group()){
                if(m instanceof Building bl && bl.isValid()) out.add(bl);
            }
            if(out.isEmpty()) out.add(b);
            return out;
        }

        if(CoopCombo.eligible(b.block)) return CoopCombo.coopGroup(b);
        Object r = call(b, "group");
        if(r instanceof Seq<?> seq){
            for(Object o : seq){
                if(o instanceof Building bl && bl.isValid()) out.add(bl);
            }
        }
        if(out.isEmpty()) out.add(b);
        return out;
    }

    /** 用于容量计算的本机容量，绝不要读 comboTotal*（跨组合并后会变成全局值）。 */
    public static int baseItemCap(Building b){
        if(b == null || b.items == null || b.block == null) return 0;
        // 协作组合的方块容量是"放大后"的（组容量），统计组容量时必须用放大前的基础值
        Integer coopBase = CoopCombo.coopBaseItemCap(b);
        if(coopBase != null) return coopBase;
        return b.block.itemCapacity;
    }

    /**
     * 组合池里的液体总量。
     *
     * LiquidModule.currentAmount() 只是"最近被添加/移除的那种液体"的量，
     * 池子里混了两种以上液体时它会来回跳（谁最后被动过就是谁），
     * 用它做容量判断会出现"有时超容、有时没满就拒收"的随机现象。
     */
    public static float liquidTotal(LiquidModule module){
        return module == null ? 0f : module.sum((liquid, amount) -> amount);
    }

    /** 用于容量计算的本机液体容量。 */
    public static float baseLiquidCap(Building b){
        if(b == null || b.liquids == null || b.block == null) return 0f;
        Float coopBase = CoopCombo.coopBaseLiquidCap(b);
        if(coopBase != null) return coopBase;
        Float base = getFloat(b.block, "baseLiquidCapacity");
        if(base != null && base > 0f) return base;
        return Math.max(b.block.liquidCapacity, 0f);
    }

    public static int groupBaseItemCap(Building leader){
        int total = 0;
        for(Building m : group(leader)){
            if(m.isValid()) total += baseItemCap(m);
        }
        return total;
    }

    public static float groupBaseLiquidCap(Building leader){
        float total = 0f;
        for(Building m : group(leader)){
            if(m.isValid()) total += baseLiquidCap(m);
        }
        return total;
    }

    public static void setItemCap(Building b, int value){
        if(b == null) return;
        if(b instanceof IUnitCombo u){
            u.gItemCap(value);
            return;
        }
        setInt(b, "comboTotalItemCap", value);
    }

    public static void setLiquidCap(Building b, float value){
        if(b == null) return;
        if(b instanceof IUnitCombo u){
            u.gLiquidCap(value);
            return;
        }
        setFloat(b, "comboTotalLiquidCap", value);
    }

    public static void markClean(Building b){
        if(b == null) return;
        if(b instanceof IUnitCombo u){
            u.gDirty(false);
            return;
        }
        setBoolean(b, "comboDirty", false);
    }

    public static void setDirty(Building b, boolean value){
        if(b == null) return;
        if(b instanceof IUnitCombo u){
            u.gDirty(value);
            return;
        }
        setBoolean(b, "comboDirty", value);
    }

    public static boolean isDirty(Building b){
        if(b == null) return false;
        if(b instanceof IUnitCombo u) return u.gDirty();
        Boolean v = getBoolean(b, "comboDirty");
        return v != null && v;
    }

    public static boolean hasPendingLeader(Building b){
        if(b == null) return false;
        if(b instanceof IUnitCombo u) return u.gPending() != -1;
        Integer p = getInt(b, "pendingLeaderPos");
        return p != null && p != -1;
    }

    public static void rebuildLocal(Building b){
        if(b == null) return;
        if(b instanceof IUnitCombo u){
            u.rebuildCombo();
            return;
        }
        call(b, "rebuildCombo");
    }

    public static boolean isStoredHeatBuild(Building b){
        return b != null && b.getClass().getName().equals("combine.production.CombinedGenerator$CombinedGeneratorBuild");
    }

    /** 读档后恢复共享模块的挂点：等价于各组合建筑 updateTile 开头的 comboPreUpdate()。 */
    public static void preUpdate(Building b){
        if(b == null) return;
        if(b instanceof IUnitCombo u){
            u.comboPreUpdate();
            return;
        }
        call(b, "comboPreUpdate");
    }

    public static float getStoredHeat(Building b){
        Object r = call(b, "getComboHeat");
        return r instanceof Number n ? n.floatValue() : 0f;
    }

    public static void setStoredHeat(Building b, float value){
        call(b, "setComboHeat", new Class<?>[]{float.class}, value);
    }

    public static boolean hasField(Object obj, String name){
        return findField(obj.getClass(), name) != null;
    }

    public static Integer getInt(Object obj, String name){
        Object v = fieldValue(obj, name);
        return v instanceof Number n ? n.intValue() : null;
    }

    public static Float getFloat(Object obj, String name){
        Object v = fieldValue(obj, name);
        return v instanceof Number n ? n.floatValue() : null;
    }

    public static void setInt(Object obj, String name, int value){
        Field f = findField(obj.getClass(), name);
        if(f == null) return;
        try{
            f.setInt(obj, value);
        }catch(Throwable ignored){
        }
    }

    public static void setFloat(Object obj, String name, float value){
        Field f = findField(obj.getClass(), name);
        if(f == null) return;
        try{
            f.setFloat(obj, value);
        }catch(Throwable ignored){
        }
    }

    public static void setBoolean(Object obj, String name, boolean value){
        Field f = findField(obj.getClass(), name);
        if(f == null) return;
        try{
            f.setBoolean(obj, value);
        }catch(Throwable ignored){
        }
    }

    public static Boolean getBoolean(Object obj, String name){
        Object v = fieldValue(obj, name);
        return v instanceof Boolean b ? b : null;
    }

    public static Object fieldValue(Object obj, String name){
        Field f = findField(obj.getClass(), name);
        if(f == null) return null;
        try{
            return f.get(obj);
        }catch(Throwable ignored){
            return null;
        }
    }

    public static Object call(Object obj, String name){
        Method m = findMethod(obj.getClass(), name);
        if(m == null) return null;
        try{
            return m.invoke(obj);
        }catch(Throwable ignored){
            return null;
        }
    }

    public static Object call(Object obj, String name, Class<?>[] types, Object value){
        Method m = findMethod(obj.getClass(), name, types);
        if(m == null) return null;
        try{
            return m.invoke(obj, value);
        }catch(Throwable ignored){
            return null;
        }
    }

    public static Field findField(Class<?> clazz, String name){
        // FIX[放置卡顿]: 字段查找必须缓存（按 Class 分层，避免每次拼字符串 key）。
        // isComboBuild() 走的是 hasField("comboGroup")，而 ComboNet.rebuild 里
        // "每栋建筑 × 每趟循环" 都要问一次；原实现每次都逐层 getDeclaredField，
        // 每层 miss 还会抛一次 NoSuchFieldException（构造异常很贵）——
        // 几百栋建筑时单趟 rebuild 就能吃掉好几毫秒，放在组合体旁边放连接器/节点
        // 那一下的疯狂掉帧就是这么来的。
        java.util.concurrent.ConcurrentHashMap<String, Field> perClass = fieldCache.get(clazz);
        if(perClass == null){
            perClass = new java.util.concurrent.ConcurrentHashMap<>();
            fieldCache.put(clazz, perClass);
        }
        Field cached = perClass.get(name);
        if(cached != null) return cached;
        java.util.Set<String> missing = missingFieldCache.get(clazz);
        if(missing != null && missing.contains(name)) return null;

        Class<?> c = clazz;
        while(c != null && c != Object.class){
            try{
                Field f = c.getDeclaredField(name);
                // 缓存时就 setAccessible：反射调用点每次 setAccessible 很贵，
                // 而 rebuild 里这些调用是"每建筑 × 每字段"量级的。
                try{ f.setAccessible(true); }catch(Throwable ignored){}
                perClass.put(name, f);
                return f;
            }catch(NoSuchFieldException ignored){
                c = c.getSuperclass();
            }
        }
        if(missing == null){
            missing = java.util.concurrent.ConcurrentHashMap.newKeySet();
            missingFieldCache.put(clazz, missing);
        }
        missing.add(name);
        return null;
    }

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.concurrent.ConcurrentHashMap<String, Field>> fieldCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** 记下"这个类确实没有这个字段"（ConcurrentHashMap 不允许 null 值，所以单独放）。 */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.Set<String>> missingFieldCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    public static Method findMethod(Class<?> clazz, String name, Class<?>... types){
        // 反射查找必须缓存：leader()/group()/preUpdate() 这些桥接方法在
        //   "每 tick × 每建筑 × 每条搬运请求" 的路径上被调用，
        // 每次都走一遍 getDeclaredMethod + setAccessible 会直接把帧数吃掉。
        StringBuilder key = new StringBuilder(name).append('(');
        if(types != null)
            for(Class<?> t : types) key.append(t.getName()).append(',');
        key.append(')');
        String k = key.toString();

        java.util.concurrent.ConcurrentHashMap<String, Method> perClass = methodCache.get(clazz);
        if(perClass == null){
            perClass = new java.util.concurrent.ConcurrentHashMap<>();
            methodCache.put(clazz, perClass);
        }
        Method cached = perClass.get(k);
        if(cached != null) return cached;
        // 注意：ConcurrentHashMap 不允许 null 值，所以"找不到"必须记在单独的集合里 ——
        // 之前把 null 直接 put 进去，一查询没有该方法类的类（例如组合仓库，
        // 它没有 leader()/group() 方法，靠下面的兜底返回自身）就 NPE 崩客户端。
        java.util.Set<String> missing = missingMethodCache.get(clazz);
        if(missing != null && missing.contains(k)) return null;

        Class<?> c = clazz;
        while(c != null && c != Object.class){
            try{
                Method m = types == null || types.length == 0
                    ? c.getDeclaredMethod(name)
                    : c.getDeclaredMethod(name, types);
                try{ m.setAccessible(true); }catch(Throwable ignored){}
                perClass.put(k, m);
                return m;
            }catch(NoSuchMethodException ignored){
                c = c.getSuperclass();
            }
        }
        if(missing == null){
            missing = java.util.concurrent.ConcurrentHashMap.newKeySet();
            missingMethodCache.put(clazz, missing);
        }
        missing.add(k);
        return null;
    }

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.concurrent.ConcurrentHashMap<String, Method>> methodCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** 记下"这个类确实没有这个方法"，避免每次重走父类查找。 */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.Set<String>> missingMethodCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    // -------------------- 组级"谁消耗什么"缓存 --------------------

    /**
     * 组级消耗表缓存（key = 组 leader 的 pos）。
     *
     * 每个组合建筑的 acceptItem/acceptLiquid 都要回答"组里有没有人吃这个物品/液体"，
     * 原先每次都现遍历整组 —— N 台一组的基地里，每条搬运请求都是 O(N)，
     * 物品一流动就变成每 tick O(N²)，直接卡成幻灯片。
     * 这里按 tick 缓存一次（同 tick 内组结构变化由 rebuild 后下一 tick 生效，
     * 消耗表滞后一帧无害），查询变成 O(1)。
     */
    private static long consumeTick = Long.MIN_VALUE;
    private static final arc.struct.IntMap<boolean[]> consumedItemCache = new arc.struct.IntMap<>();
    private static final arc.struct.IntMap<boolean[]> consumedLiquidCache = new arc.struct.IntMap<>();

    /** 组内是否有成员消耗该物品（含跨类型组合）。 */
    public static boolean groupConsumesItem(Building self, Item item){
        if(item == null) return false;
        boolean[] table = consumedTable(self, true);
        return table != null && item.id < table.length && table[item.id];
    }

    /** 组内是否有成员消耗该液体（含跨类型组合）。 */
    public static boolean groupConsumesLiquid(Building self, Liquid liquid){
        if(liquid == null) return false;
        boolean[] table = consumedTable(self, false);
        return table != null && liquid.id < table.length && table[liquid.id];
    }

    private static boolean[] consumedTable(Building self, boolean items){
        if(self == null || state == null) return null;
        if(consumeTick != state.updateId){
            consumeTick = state.updateId;
            consumedItemCache.clear();
            consumedLiquidCache.clear();
            neededItemCache.clear();
        }
        Building leader = leader(self);
        if(leader == null) return null;
        arc.struct.IntMap<boolean[]> cache = items ? consumedItemCache : consumedLiquidCache;
        boolean[] table = cache.get(leader.pos());
        if(table != null) return table;

        table = new boolean[items ? content.items().size : content.liquids().size];
        for(Building m : group(leader)){
            if(m == null || !m.isValid() || m.block == null) continue;
            boolean[] filter = items ? m.block.itemFilter : m.block.liquidFilter;
            if(filter == null) continue;
            for(int i = 0; i < filter.length && i < table.length; i++)
                if(filter[i]) table[i] = true;
        }
        cache.put(leader.pos(), table);
        return table;
    }

    /**
     * 组内"需要"该物品：普通 filter 消耗 + 单位工厂/蓝图方块的动态配方需求。
     * （原版 consumesItem 认不出 ConsumeItemDynamic，单位工厂要看 plans 里的需求。）
     */
    public static boolean groupNeedsItem(Building self, Item item){
        if(item == null || self == null || state == null) return false;
        if(consumeTick != state.updateId){
            consumeTick = state.updateId;
            consumedItemCache.clear();
            consumedLiquidCache.clear();
            neededItemCache.clear();
        }
        Building leader = leader(self);
        if(leader == null) return false;
        boolean[] table = neededItemCache.get(leader.pos());
        if(table == null){
            table = new boolean[content.items().size];
            for(Building m : group(leader)){
                if(m == null || !m.isValid() || m.block == null) continue;
                boolean[] filter = m.block.itemFilter;
                if(filter != null)
                    for(int i = 0; i < filter.length && i < table.length; i++)
                        if(filter[i]) table[i] = true;
                // 单位工厂：配方需求是运行期动态的
                if(m instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild
                    && m.block instanceof mindustry.world.blocks.units.UnitFactory uf){
                    for(mindustry.world.blocks.units.UnitFactory.UnitPlan plan : uf.plans){
                        for(mindustry.type.ItemStack stack : plan.requirements){
                            if(stack.item != null && stack.item.id < table.length)
                                table[stack.item.id] = true;
                        }
                    }
                }
                // 蓝图方块：正在造什么就吃那台的建造需求
                if(m instanceof mindustry.world.blocks.payloads.Constructor.ConstructorBuild cb){
                    Block recipe = cb.recipe();
                    if(recipe != null && recipe.requirements != null){
                        for(mindustry.type.ItemStack stack : recipe.requirements){
                            if(stack.item != null && stack.item.id < table.length)
                                table[stack.item.id] = true;
                        }
                    }
                }
            }
            neededItemCache.put(leader.pos(), table);
        }
        return item.id < table.length && table[item.id];
    }

    private static final arc.struct.IntMap<boolean[]> neededItemCache = new arc.struct.IntMap<>();
}
