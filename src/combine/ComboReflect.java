package combine;

import arc.struct.Seq;
import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
        return hasField(b, "comboGroup") || hasField(b, "comboLeader");
    }

    public static Building leader(Building b){
        if(b == null) return null;
        if(b instanceof IUnitCombo u){
            IUnitCombo l = u.leader();
            return l instanceof Building bl ? bl : b;
        }
        Object r = call(b, "leader");
        return r instanceof Building bl ? bl : b;
    }

    public static Seq<Building> group(Building b){
        Seq<Building> out = new Seq<>();
        if(b == null) return out;

        if(b instanceof IUnitCombo u){
            for(IUnitCombo m : u.group()){
                if(m instanceof Building bl && bl.isValid()) out.addUnique(bl);
            }
            if(out.isEmpty()) out.add(b);
            return out;
        }

        Object r = call(b, "group");
        if(r instanceof Seq<?> seq){
            for(Object o : seq){
                if(o instanceof Building bl && bl.isValid()) out.addUnique(bl);
            }
        }
        if(out.isEmpty()) out.add(b);
        return out;
    }

    /** 用于容量计算的本机容量，绝不要读 comboTotal*（跨组合并后会变成全局值）。 */
    public static int baseItemCap(Building b){
        if(b == null || b.items == null || b.block == null) return 0;
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
        return b != null && b.getClass().getName().equals("combine.CombinedGenerator$CombinedGeneratorBuild");
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
            f.setAccessible(true);
            f.setInt(obj, value);
        }catch(Throwable ignored){
        }
    }

    public static void setFloat(Object obj, String name, float value){
        Field f = findField(obj.getClass(), name);
        if(f == null) return;
        try{
            f.setAccessible(true);
            f.setFloat(obj, value);
        }catch(Throwable ignored){
        }
    }

    public static void setBoolean(Object obj, String name, boolean value){
        Field f = findField(obj.getClass(), name);
        if(f == null) return;
        try{
            f.setAccessible(true);
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
            f.setAccessible(true);
            return f.get(obj);
        }catch(Throwable ignored){
            return null;
        }
    }

    public static Object call(Object obj, String name){
        Method m = findMethod(obj.getClass(), name);
        if(m == null) return null;
        try{
            m.setAccessible(true);
            return m.invoke(obj);
        }catch(Throwable ignored){
            return null;
        }
    }

    public static Object call(Object obj, String name, Class<?>[] types, Object value){
        Method m = findMethod(obj.getClass(), name, types);
        if(m == null) return null;
        try{
            m.setAccessible(true);
            return m.invoke(obj, value);
        }catch(Throwable ignored){
            return null;
        }
    }

    public static Field findField(Class<?> clazz, String name){
        Class<?> c = clazz;
        while(c != null && c != Object.class){
            try{
                return c.getDeclaredField(name);
            }catch(NoSuchFieldException ignored){
                c = c.getSuperclass();
            }
        }
        return null;
    }

    public static Method findMethod(Class<?> clazz, String name, Class<?>... types){
        Class<?> c = clazz;
        while(c != null && c != Object.class){
            try{
                Method m = types == null || types.length == 0
                    ? c.getDeclaredMethod(name)
                    : c.getDeclaredMethod(name, types);
                return m;
            }catch(NoSuchMethodException ignored){
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
