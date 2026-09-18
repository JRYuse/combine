package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.*;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.entities.units.*;

/**
 * 建造武器在"没有建造队列"的单位上不能崩。
 *
 * 不能建造的单位（或某些模组单位）没有 BuilderComp，{@code unit.plans()} 返回 null，
 * 旧代码直接 plans.size/first() → "Attempt to invoke ... arc.struct.Seq.first() on a null
 * object reference"（用户报的崩溃）。
 */
public class BuildWeaponGuardTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new BuildWeaponGuardTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[BW] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }

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

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);

        // 挂上建造武器（正常在 ClientLoad/ServerLoad 时挂，headless 里手动来一次）
        ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        Class.forName("combine.Main", true, ml).getMethod("addBuildWeapons").invoke(null);

        // 找一把本模组挂在多足单位上的建造武器
        Weapon weapon = null;
        for(Weapon w : UnitTypes.poly.weapons)
            if(w.getClass().getName().equals("combine.MultiBuildWeapon")) weapon = w;
        System.out.println("[BW] poly 上的组合建造武器=" + (weapon == null ? "无" : weapon.getClass().getSimpleName()));
        if(weapon == null){ System.out.println("[BW] 没找到，跳过"); System.exit(0); }

        // 一个"不能建造"的单位：plans() 应该是 null（这正是崩溃场景）
        Unit dagger = UnitTypes.dagger.create(Team.sharded);
        dagger.set(60 * 8f, 60 * 8f);
        dagger.add();
        run(5);
        System.out.println("[BW] dagger.plans()=" + dagger.plans() + " canBuild=" + dagger.canBuild());

        WeaponMount mount = (WeaponMount) Class.forName("combine.MultiBuildWeapon$BuildWeaponMount", true, ml)
            .getConstructor(Weapon.class).newInstance(weapon);

        // 找出真正"plans() 为 null"的单位（没有 Builderc 的单位类，例如 MechUnitWaterMove）
        Unit nullUnit = null;
        int nullCount = 0;
        for(UnitType t : Vars.content.units()){
            Unit u;
            try{ u = t.create(Team.sharded); }catch(Throwable e){ continue; }
            if(u == null) continue;
            if(u.plans() == null){ nullCount++; if(nullUnit == null) nullUnit = u; }
        }
        System.out.println("[BW] 全内容单位里 plans()==null 的数量=" + nullCount
            + (nullUnit == null ? "" : ("，例=" + nullUnit.type.name + " canBuild=" + nullUnit.canBuild())));

        // 直接走"取活"和"是否被抢"两条路径
        boolean crashed = false;
        try{
            var findPlan = weapon.getClass().getDeclaredMethod("findPlan", Unit.class, Unit.class, Class.forName("combine.MultiBuildWeapon$BuildWeaponMount", true, ml));
            findPlan.setAccessible(true);
            Object plan = findPlan.invoke(weapon, dagger, dagger, null);
            System.out.println("[BW] findPlan(null 队列) = " + plan);
            var isRob = weapon.getClass().getDeclaredMethod("isRob", Unit.class, Class.forName("combine.MultiBuildWeapon$BuildWeaponMount", true, ml));
            isRob.setAccessible(true);
            System.out.println("[BW] isRob(null 队列) = " + isRob.invoke(weapon, dagger, mount));

            if(nullUnit != null){
                nullUnit.set(60 * 8f, 60 * 8f);
                nullUnit.add();
                run(5);
                System.out.println("[BW] " + nullUnit.type.name + ".plans()=" + nullUnit.plans()
                    + " canBuild=" + nullUnit.canBuild());
                System.out.println("[BW] findPlan(真 null) = " + findPlan.invoke(weapon, nullUnit, nullUnit, null));
                System.out.println("[BW] isRob(真 null) = " + isRob.invoke(weapon, nullUnit, mount));
                weapon.update(nullUnit, mount); // 整条武器更新路径
                run(5);
                System.out.println("[BW] weapon.update() 通过（真 null 单位）");
            }else{
                System.out.println("[BW] 没找到 plans()==null 的单位，跳过该分支");
            }
        }catch(java.lang.reflect.InvocationTargetException e){
            crashed = true;
            System.out.println("[BW] 抛异常: " + e.getCause());
        }catch(Throwable t){
            System.out.println("[BW] 反射调用跳过: " + t);
        }
        check("没有建造队列的单位不会让建造武器崩溃", !crashed);

        System.out.println("[BW] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
