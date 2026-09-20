package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.entities.units.BuildPlan; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import arc.util.Strings; import mindustry.world.*; import mindustry.world.blocks.ConstructBlock; import mindustry.type.*; import mindustry.type.Item;

/**
 * "造了一半、还在建造的建筑要能立刻拆掉"（用户报的）。
 *
 * 玩家对着一格 ConstructBuild 下拆除指令时，原版会把它变成"正在拆的 ConstructBuild"
 * （current 换成被拆的那个方块），然后由单位把它拆完；队列里那条过期的建造计划
 * 会被原版 BuilderComp 顺手丢掉（cb.current != plan.block）。
 *
 * 本模组的多线程建造武器原先不看这个，只要格子上是 ConstructBuild 就接手 construct()，
 * 把拆除又"造"回去了 —— 表现就是拆不掉 / 反而被造完。
 */
public class HalfBuiltBreakTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new HalfBuiltBreakTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[HB] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Block findExact(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static float fieldF(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField(name); f.setAccessible(true); return f.getFloat(o); }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return -1f; }
    static Object field(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return null; }
    static void tickWeapons(Unit unit){
        for(mindustry.entities.units.WeaponMount wm : unit.mounts){
            if(wm.weapon != null && wm.weapon.getClass().getName().equals("combine.MultiBuildWeapon")){
                wm.weapon.update(unit, wm);
            }
        }
    }
    /** 有没有挂座认领着这一格。 */
    static boolean anyMountOn(Unit unit, int x, int y){
        for(mindustry.entities.units.WeaponMount wm : unit.mounts){
            if(wm instanceof mindustry.entities.units.WeaponMount && wm.weapon != null
                && wm.weapon.getClass().getName().equals("combine.MultiBuildWeapon")){
                Object plan = field(wm, "plan");
                if(plan != null && ((mindustry.entities.units.BuildPlan) plan).x == x && ((mindustry.entities.units.BuildPlan) plan).y == y) return true;
            }
        }
        return false;
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
        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        for(int y=30;y<170;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
        try{
            ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
            Class.forName("combine.Main", true, ml).getMethod("addBuildWeapons", UnitType.class, int.class).invoke(null, UnitTypes.poly, 2);
        }catch(Throwable t){ System.out.println("[HB] 挂武器失败 " + t); }
        Building core = place(Blocks.coreShard, 70, 80, Team.sharded);
        Vars.state.rules.infiniteResources = false;
        run(10);
        if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 100000);
        run(20);

        Block block = null;
        for(Block b : Vars.content.blocks()) if(b.getClass().getName().equals("combine.production.CombinedCrafter") && b.size == 2){ block = b; break; }
        if(block == null) for(Block b : Vars.content.blocks()) if(b.getClass().getName().equals("combine.production.CombinedCrafter")){ block = b; break; }
        if(block == null){ System.out.println("[HB] 没找到组合方块"); System.exit(3); }
        int size = Math.max(block.size, 1);

        // ---------- 场景 1：挂座已经认领了这一格，玩家下拆除指令 ----------
        int ax = 78, ay = 80;
        Tile t = Vars.world.tile(ax, ay);
        t.setBlock(ConstructBlock.get(size), Team.sharded, 0);
        ConstructBlock.ConstructBuild cb = (ConstructBlock.ConstructBuild) Vars.world.build(ax, ay);
        cb.setConstruct(Blocks.air, block);
        setProgress(cb, 0.5f);

        Unit unit = UnitTypes.poly.create(Team.sharded);
        unit.set((ax + 2) * 8f, ay * 8f);
        unit.add();
        // 直接把"挂座已经认领了这一格"摆出来（认领路径由别的测试覆盖，这里要验的是"正在拆时会不会松手"）
        mindustry.entities.units.WeaponMount wm0 = null;
        for(mindustry.entities.units.WeaponMount wm : unit.mounts){
            if(wm.weapon != null && wm.weapon.getClass().getName().equals("combine.MultiBuildWeapon")){ wm0 = wm; break; }
        }
        if(wm0 == null){ System.out.println("[HB] 没有 MultiBuildWeapon 挂座"); System.exit(3); }
        BuildPlan claim = new BuildPlan(ax, ay, 0, block, null);
        setField(wm0, "plan", claim);
        setField(wm0, "target", cb);
        for(int i = 0; i < 5; i++){ arc.util.Time.delta = 1f; tickWeapons(unit); Vars.logic.update(); }
        Building beforeBreak = Vars.world.build(ax, ay);
        System.out.println("[HB] 拆除前: 挂座占着=" + anyMountOn(unit, ax, ay)
            + " 格子上=" + (beforeBreak == null ? "空气" : beforeBreak.block.name)
            + " progress=" + fieldF(beforeBreak, "progress"));

        // 玩家下拆除指令：原版 beginBreak 把这一格切成"正在拆"（current = 被拆的方块）
        mindustry.world.Build.beginBreak(null, Team.sharded, ax, ay);
        Building mid = Vars.world.build(ax, ay);
        System.out.println("[HB] 刚下完拆除指令: 格子上=" + (mid == null ? "空气"
            : (mid instanceof ConstructBlock.ConstructBuild cm ? "ConstructBuild(current=" + cm.current.name + ")" : mid.block.name))
            + " 挂座还占着=" + anyMountOn(unit, ax, ay));
        for(int i = 0; i < 60; i++){ arc.util.Time.delta = 1f; tickWeapons(unit); Vars.logic.update(); }
        Building after = Vars.world.build(ax, ay);
        boolean stillClaimed = anyMountOn(unit, ax, ay);
        boolean finished = after != null && after.block == block;
        System.out.println("[HB] 拆除后: 挂座还占着=" + stillClaimed + " 格子上="
            + (after == null ? "空气" : (after instanceof ConstructBlock.ConstructBuild c3 ? "ConstructBuild(current=" + c3.current.name + ")" : after.block.name)));
        check("玩家下拆除指令后挂座放手（不再对正在拆的格子 construct）", !stillClaimed);
        check("目标格没有被造完（不是 " + block.name + "）", !finished);
        try{ unit.remove(); }catch(Throwable ignored){}
        run(5);

        // ---------- 场景 2：拆除工具把计划换成 demolition 后，格子应该被拆掉 ----------
        int bx = 78, by = 100;
        Tile t3 = Vars.world.tile(bx, by);
        t3.setBlock(ConstructBlock.get(size), Team.sharded, 0);
        ConstructBlock.ConstructBuild cb3 = (ConstructBlock.ConstructBuild) Vars.world.build(bx, by);
        cb3.setConstruct(Blocks.air, block);
        setProgress(cb3, 0.9f);
        Unit unit2 = UnitTypes.poly.create(Team.sharded);
        unit2.set((bx + 2) * 8f, by * 8f);
        unit2.add();
        unit2.addBuild(new BuildPlan(bx, by, 0, block, null));
        for(int i = 0; i < 20; i++){ arc.util.Time.delta = 1f; tickWeapons(unit2); Vars.logic.update(); }
        unit2.addBuild(new BuildPlan(bx, by));   // 玩家用拆除工具
        for(int i = 0; i < 300; i++){ arc.util.Time.delta = 1f; tickWeapons(unit2); Vars.logic.update(); }
        Building after2 = Vars.world.build(bx, by);
        System.out.println("[HB] 拆除工具场景: 300 帧后=" + (after2 == null ? "空气" : after2.block.name));
        check("造到一半的建筑用拆除工具能拆掉（空气）", after2 == null);
        try{ unit2.remove(); }catch(Throwable ignored){}

        System.out.println("[HB] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
    static int ay2(int ay){ return ay; }
    static void setField(Object o, String name, Object v){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField(name); f.setAccessible(true); f.set(o, v); return; }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
    }
    static void setProgress(Object cb, float v){
        try{
            Class<?> c = cb.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField("progress"); f.setAccessible(true); f.setFloat(cb, v); return; }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
    }
}
