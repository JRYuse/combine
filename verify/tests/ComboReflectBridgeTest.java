package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;

import java.lang.reflect.Field;

/**
 * {@code combine.util.ComboReflect} 这把"通用反射桥"必须对所有**三种形态**的组合建筑都成立：
 *
 * <ol>
 *   <li><b>接口 default 方法</b>：组合单位工厂/重组厂/构造机实现 {@code combine.units.IUnitCombo}，
 *       {@code leader()/group()/rebuildCombo()/comboPreUpdate()} 全是接口 default 方法，
 *       实现类自己**不声明**它们 —— {@code getDeclaredMethod} 顺着类层次永远找不到，
 *       必须额外去接口里找（{@link #checkDefaultMethods}）。这一条是"单位侧拆分后
 *       ComboReflect 不再 instanceof IUnitCombo"的直接防线：一旦找不到，
 *       {@code preUpdate()}（读档/网络并池后恢复共享模块的挂点）与 {@code rebuildLocal()}
 *       （连线变了重算分组）会**静默失效**（不报错、看着正常，实际功能没了）。</li>
 *   <li><b>类自己写的方法</b>：发射台/着陆台的 {@code leader()/group()/rebuildCombo()} 直接写在
 *       build 类里（不实现任何接口）。</li>
 *   <li><b>既没方法也没接口</b>：组合仓库（CombinedStorageBlock）只有字段 ——
 *       {@code group()} 必须兜底返回自己而不是崩（信息面板每帧都要调它）。</li>
 * </ol>
 *
 * 判定方式都是"行为"而不是"调用了没"：例如待命组长（pendingLeaderPos）塞进去后调
 * {@code preUpdate()}，必须真的把 comboLeader 换成那台建筑 —— 说明 default 方法确实被调到了。
 */
public class ComboReflectBridgeTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new ComboReflectBridgeTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[CR] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }

    static ClassLoader ml;
    /** 组合建筑上那些约定字段（comboLeader / comboTotalItemCap / pendingLeaderPos …）。 */
    static Object fld(Object o, String name){
        for(Class<?> c = o.getClass(); c != null; c = c.getSuperclass()){
            try{ Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
            catch(NoSuchFieldException ignored){}
            catch(Throwable ignored){ return null; }
        }
        return null;
    }
    static Block byClass(String exactOrPrefix, boolean prefix){
        for(Block b : Vars.content.blocks()){
            String cn = b.getClass().getName();
            if(prefix ? cn.startsWith(exactOrPrefix) : cn.equals(exactOrPrefix)) return b;
        }
        return null;
    }
    static Building place(Block b,int x,int y){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,Team.sharded,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,Team.sharded,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu;
    }

    /** ① 接口 default 方法形态：组合单位工厂（IUnitCombo）。 */
    static void checkDefaultMethods() throws Exception{
        Class<?> reflect = Class.forName("combine.util.ComboReflect", true, ml);
        java.lang.reflect.Method isComboBuild = reflect.getMethod("isComboBuild", Building.class);
        java.lang.reflect.Method leader = reflect.getMethod("leader", Building.class);
        java.lang.reflect.Method group = reflect.getMethod("group", Building.class);
        java.lang.reflect.Method preUpdate = reflect.getMethod("preUpdate", Building.class);
        java.lang.reflect.Method rebuildLocal = reflect.getMethod("rebuildLocal", Building.class);
        java.lang.reflect.Method setItemCap = reflect.getMethod("setItemCap", Building.class, int.class);
        java.lang.reflect.Method setDirty = reflect.getMethod("setDirty", Building.class, boolean.class);
        java.lang.reflect.Method isDirty = reflect.getMethod("isDirty", Building.class);
        java.lang.reflect.Method markClean = reflect.getMethod("markClean", Building.class);
        java.lang.reflect.Method hasPending = reflect.getMethod("hasPendingLeader", Building.class);

        Block uf = byClass("combine.units.CombinedUnitFactory", false);
        if(uf == null){
            check("数据目录里有组合单位工厂（vanilla unit-factory 被替换）", false);
            return;
        }
        check("数据目录里有组合单位工厂（vanilla unit-factory 被替换）", true);
        System.out.println("[CR] 被测建筑: " + uf.name + " cls=" + uf.getClass().getName());

        for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null&&t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(3);
        int sz = Math.max(uf.size, 1);
        // 组合单位工厂尺寸可能是奇数/偶数，横着摆两台相邻的（用方块占地宽度算下一台的位置）
        int w = uf.size;
        Building f1 = place(uf, 60, 60);
        Building f2 = place(uf, 60 + w, 60);
        run(40);
        if(f1 == null || f2 == null){ check("两台组合单位工厂放下来", false); return; }
        check("两台组合单位工厂放下来", true);

        check("isComboBuild() 认这台是组合建筑（靠 comboGroup/comboLeader 字段，不靠 instanceof）",
            (Boolean)isComboBuild.invoke(null, f1));
        check("两台共用一份物品池（相邻同型自动成组）", f1.items == f2.items);
        Building l1 = (Building)leader.invoke(null, f1), l2 = (Building)leader.invoke(null, f2);
        check("leader() 走接口 default 方法（两台组长是同一台）", l1 != null && l1 == l2);
        Seq<?> g = (Seq<?>)group.invoke(null, l1);
        check("group() 走接口 default 方法（组里 2 台）", g != null && g.size == 2);

        // —— 关键：preUpdate() = comboPreUpdate()（接口 default 方法）真的被调到了 ——
        // 塞一个"待认组长"，调 preUpdate 后 comboLeader 必须真的换成它，并且 pendingLeaderPos 被清掉
        Field pend = fieldOf(f2, "pendingLeaderPos");
        if(pend != null){
            pend.setInt(f2, f1.pos());
            fieldOf(f2, "comboLeader").set(f2, null);
            preUpdate.invoke(null, f2);
            Object newLeader = fieldOf(f2, "comboLeader").get(f2);
            System.out.println("[CR] preUpdate 后：comboLeader=" + (newLeader == null ? "null" : ((Building)newLeader).block.name)
                + " pendingLeaderPos=" + pend.getInt(f2));
            check("preUpdate() 调到接口 default 的 comboPreUpdate()（待认组长已生效）", newLeader == f1);
            check("preUpdate() 把 pendingLeaderPos 清成 -1", pend.getInt(f2) == -1);
        }else{
            check("找得到 pendingLeaderPos 字段（约定字段名）", false);
        }

        // —— 关键：rebuildLocal() = rebuildCombo()（接口 default 方法）真的被调到了 ——
        Building f3 = place(uf, 60 + w * 2, 60);
        if(f3 != null){
            try{ f3.updateProximity(); }catch(Throwable ignored){}
            run(2);
            rebuildLocal.invoke(null, l1);
            Seq<?> g3 = (Seq<?>)group.invoke(null, l1);
            System.out.println("[CR] rebuildLocal 后组大小=" + g3.size);
            check("rebuildLocal() 调到接口 default 的 rebuildCombo()（新邻居并进组，组变 3）", g3.size == 3);
            check("第三台也共用同一个物品池", f3.items == f1.items);
        }

        // —— 存取契约（容量/脏标记/待命）都走字段，读写必须真的落到字段上 ——
        setItemCap.invoke(null, f1, 4242);
        check("setItemCap() 写进 comboTotalItemCap", Integer.valueOf(4242).equals(fld(f1, "comboTotalItemCap")));
        setDirty.invoke(null, f1, true);
        check("setDirty(true) 后 isDirty()=true", (Boolean)isDirty.invoke(null, f1));
        markClean.invoke(null, f1);
        check("markClean() 后 isDirty()=false", !(Boolean)isDirty.invoke(null, f1));
        check("hasPendingLeader() 认 pendingLeaderPos 字段", !(Boolean)hasPending.invoke(null, f1));
    }

    static Field fieldOf(Object o, String name){
        for(Class<?> c = o.getClass(); c != null; c = c.getSuperclass()){
            try{ Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch(NoSuchFieldException ignored){}
        }
        return null;
    }

    /** ② 类自己写方法形态：发射台（CombinedLaunchPad）。 */
    static void checkOwnMethods() throws Exception{
        Class<?> reflect = Class.forName("combine.util.ComboReflect", true, ml);
        java.lang.reflect.Method isComboBuild = reflect.getMethod("isComboBuild", Building.class);
        java.lang.reflect.Method leader = reflect.getMethod("leader", Building.class);
        java.lang.reflect.Method group = reflect.getMethod("group", Building.class);
        java.lang.reflect.Method rebuildLocal = reflect.getMethod("rebuildLocal", Building.class);

        Block pad = byClass("combine.units.CombinedLaunchPad", false);
        if(pad == null){ System.out.println("[CR] （没有发射台，跳过 ②）"); return; }
        for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null&&t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(3);
        int w = Math.max(pad.size, 1);
        Building p1 = place(pad, 60, 60);
        Building p2 = place(pad, 60 + w, 60);
        run(40);
        if(p1 == null || p2 == null){ check("两台发射台放下来", false); return; }
        check("两台发射台放下来", true);
        check("isComboBuild() 认发射台是组合建筑", (Boolean)isComboBuild.invoke(null, p1));
        Building lp1 = (Building)leader.invoke(null, p1), lp2 = (Building)leader.invoke(null, p2);
        check("发射台的 leader()（类里自己写的）两台是同一台", lp1 != null && lp1 == lp2);
        Seq<?> g = (Seq<?>)group.invoke(null, lp1);
        check("发射台的 group()（类里自己写的）组里 2 台", g != null && g.size == 2);
        check("发射台的 rebuildCombo() 反射调到不抛异常", tryCall(rebuildLocal, lp1));
    }

    static boolean tryCall(java.lang.reflect.Method m, Object arg){
        try{ m.invoke(null, arg); return true; }catch(Throwable t){ System.out.println("[CR] 调用失败: " + t.getCause()); return false; }
    }

    /** ③ 只有字段、没有 leader()/group() 方法的组合建筑：组合仓库（group() 必须兜底）。 */
    static void checkFallback() throws Exception{
        Class<?> reflect = Class.forName("combine.util.ComboReflect", true, ml);
        java.lang.reflect.Method group = reflect.getMethod("group", Building.class);
        java.lang.reflect.Method leader = reflect.getMethod("leader", Building.class);
        Block core = byClass("combine.storage.CombinedCoreBlock", false);
        Block stor = core == null ? byClass("combine.storage.CombinedStorageBlock", false) : core;
        if(stor == null){ System.out.println("[CR] （没有组合核心/仓库，跳过 ③）"); return; }
        for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null&&t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(3);
        Building s1 = place(stor, 60, 60);
        run(20);
        if(s1 == null){ check("组合核心/仓库放下来", false); return; }
        check("组合核心/仓库放下来", true);
        Seq<?> g = (Seq<?>)group.invoke(null, s1);
        check("group() 对没有 group() 方法的建筑兜底返回自己（不崩）", g != null && g.size >= 1 && g.first() == s1);
        Building l = (Building)leader.invoke(null, s1);
        check("leader() 对没有 leader() 方法的建筑兜底返回自己（不崩）", l == s1);
    }

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        Vars.mods.eachClass(Mod::init);
        if(Vars.logic==null) Vars.logic=new Logic();
        if(Vars.netServer==null) Vars.netServer=new NetServer();
        if(Vars.netClient==null) Vars.netClient=new NetClient();
        ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);

        checkDefaultMethods();
        checkOwnMethods();
        checkFallback();

        System.out.println("[CR] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
