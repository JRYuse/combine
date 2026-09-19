package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.entities.units.BuildPlan; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.type.*;

/**
 * 修废墟：team=derelict 的同名方块，队员排一串计划（蓝图框）过去，应该**立刻**全被修好。
 * 用户报：只能修几个、或者一个都不修。
 */
public class DerelictRepairTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new DerelictRepairTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[DR] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Block find(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }

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

        Block wall = find("copper-wall");
        if(wall == null){ System.out.println("[DR] 没找到 copper-wall"); System.exit(3); }
        System.out.println("[DR] 墙=" + wall.name + "/" + wall.getClass().getName()
            + " 废墟修复规则=" + Vars.state.rules.derelictRepair + " alwaysUnlocked=" + wall.alwaysUnlocked);

        // 核心（单位建造要有 core 才能动）+ 一面 3x3 的废墟
        Building core = place(Blocks.coreShard, 80, 80, Team.sharded);
        run(10);
        int bx = 60, by = 60;
        for(int y=0;y<3;y++) for(int x=0;x<3;x++){
            Building r = place(wall, bx + x, by + y, Team.sharded);
            r.changeTeam(Team.derelict);
        }
        run(5);
        int ruins = 0;
        for(int y=0;y<3;y++) for(int x=0;x<3;x++){
            Tile t = Vars.world.tile(bx+x, by+y);
            if(t.team() == Team.derelict && t.block() == wall) ruins++;
        }
        System.out.println("[DR] 废墟数=" + ruins);

        // 建造单位：给 poly 挂上本模组的多线程建造武器
        Unit unit = null;
        try{
            ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
            Class<?> mainCls = Class.forName("combine.Main", true, ml);
            mainCls.getMethod("addBuildWeapons", UnitType.class, int.class).invoke(null, UnitTypes.poly, 4);
            unit = UnitTypes.poly.create(Team.sharded);
            unit.set((bx + 1) * 8f, (by + 1) * 8f);
            unit.add();
            Log.info("[DR] 单位挂座数=@", unit.mounts.length);
        }catch(Throwable t){ System.out.println("[DR] 造单位/挂武器失败 " + t); t.printStackTrace(); }
        if(unit == null){ System.exit(3); }

        // 一排计划（蓝图框）：每个废墟格一个
        for(int y=0;y<3;y++) for(int x=0;x<3;x++){
            unit.addBuild(new BuildPlan(bx + x, by + y, 0, wall, null));
        }
        System.out.println("[DR] 排好计划数=" + unit.plans().size);

        int remainingAt60 = -1;
        for(int tick = 0; tick < 400; tick++){
            arc.util.Time.delta = 1f;
            // 建造武器（挂座）自己不会在 headless 里跑：手动按原版节奏调一次 update
            for(mindustry.entities.units.WeaponMount wm : unit.mounts){
                if(wm.weapon != null && wm.weapon.getClass().getName().equals("combine.MultiBuildWeapon")){
                    wm.weapon.update(unit, wm);
                }
            }
            Vars.logic.update();
            if(tick == 60) remainingAt60 = unit.plans().size;
        }
        int repaired = 0;
        for(int y=0;y<3;y++) for(int x=0;x<3;x++){
            Tile t = Vars.world.tile(bx+x, by+y);
            if(t.team() == Team.sharded && t.block() == wall && t.build != null) repaired++;
        }
        System.out.println("[DR] 60 tick 时剩余计划=" + remainingAt60 + "，最终修复=" + repaired + "/" + ruins
            + " 剩余计划=" + unit.plans().size);
        check("废墟全被修好（" + repaired + "/" + ruins + "）", repaired == ruins);

        System.out.println("[DR] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
