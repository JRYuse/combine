package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报（q1.txt 第 2 条）：矿机组合后挖速没变化。
 *
 * 量一下实际产量：同一块矿（铺满铜矿）上放 1 台 vs 相邻 3 台组合矿机，
 * 跑同样的 tick 数，比较池子里挖出来的物品总量 —— 组合后应当按台数成倍（每台各自挖、
 * 共用一口池子），或者至少明显快于单台。
 */
public class DrillComboSpeedTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[DS] "+t); };
        new HeadlessApplication(new DrillComboSpeedTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[DS] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Block findExact(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static void clearArea(){
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
    }
    /** 把一片地铺成铜矿（原版 OreBlock 是 Floor，直接 setFloor）。 */
    static void ore(int x0,int y0,int x1,int y1){
        for(int y=y0;y<=y1;y++) for(int x=x0;x<=x1;x++){
            Tile t = Vars.world.tile(x,y);
            if(t != null) t.setFloor((mindustry.world.blocks.environment.Floor)Blocks.oreCopper);
        }
    }
    static int pool(Building... builds){
        java.util.IdentityHashMap<mindustry.world.modules.ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
        int total = 0;
        for(Building b : builds){
            if(b == null || b.items == null) continue;
            if(seen.put(b.items, Boolean.TRUE) != null) continue;
            total += b.items.total();
        }
        return total;
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
        clearArea();
        Block core = findExact("core-shard");
        Block drill = findExact("mechanical-drill");
        if(core == null || drill == null){ System.out.println("[DS] 缺方块"); System.exit(3); }
        System.out.println("[DS] core=" + core.getClass().getSimpleName() + " drill=" + drill.getClass().getSimpleName()
            + " size=" + drill.size);

        // 核心（队伍得有核心，否则单位/建筑会被清）
        place(core, 60, 100, Team.sharded);
        run(10);

        // ---- A：1 台，矿铺满它下面 ----
        ore(40, 40, 100, 80);
        Building d1 = place(drill, 50, 50, Team.sharded);
        run(30);
        int p0 = pool(d1);
        run(600);
        int a = pool(d1) - p0;
        System.out.println("[DS] 单台 600 tick 产量=" + a + " 池=" + pool(d1));

        // ---- B：相邻 3 台，矿照样铺满 ----
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
        ore(40, 40, 100, 80);
        int sz = Math.max(drill.size, 1);
        Building b1 = place(drill, 60, 50, Team.sharded);
        Building b2 = place(drill, 60 + sz, 50, Team.sharded);
        Building b3 = place(drill, 60 + sz * 2, 50, Team.sharded);
        run(30);
        System.out.println("[DS] 三台: 同池=" + (b1.items == b2.items && b2.items == b3.items)
            + " 池=" + pool(b1));
        int q0 = pool(b1);
        run(600);
        int b = pool(b1) - q0;
        System.out.println("[DS] 三台 600 tick 产量=" + b + "（单台 " + a + "，倍数 " + (a == 0 ? -1 : String.format("%.2f", b / (float)a)) + "）");

        check("组合矿机（3 台）比单台挖得多（" + b + " > " + a + "）", b > a);
        check("组合矿机产量按台数成倍（≥ 2 倍单台）", a > 0 && b >= a * 2);
        System.out.println("[DS] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
