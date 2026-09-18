package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 接收台（campaign LandingPad）必须被替换成 CombinedLandingPad 并且能组合。
 * （用户报"CombinedLandingPad 的组合怎么没了" —— 那个类写好了但没接进替换流程。）
 */
public class LandingPadTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new LandingPadTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[LP] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }

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

        Block pad = null, launch = null;
        for(Block b : Vars.content.blocks()){
            if(b.name.equals("landing-pad")) pad = b;
            if(b.name.equals("launch-pad")) launch = b;
        }
        System.out.println("[LP] landing-pad 类=" + (pad == null ? "无" : pad.getClass().getName())
            + " | launch-pad 类=" + (launch == null ? "无" : launch.getClass().getName()));
        check("接收台被替换成组合接收台", pad != null && pad.getClass().getName().equals("combine.units.CombinedLandingPad"));
        check("发射台仍然被替换（别修坏另一个）", launch != null && launch.getClass().getName().startsWith("combine.units.CombinedLaunchPad"));

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        for(int y=40;y<140;y++) for(int x=20;x<240;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        int stride = Math.max(pad.size, 1);
        Building p1 = place(pad, 60, 60, Team.sharded);
        Building p2 = place(pad, 60 + stride, 60, Team.sharded);
        run(40);
        System.out.println("[LP] 放两台接收台: p1=" + (p1 == null ? "无" : p1.block.name) + " p2=" + (p2 == null ? "无" : p2.block.name)
            + " 同物品模块=" + (p1 != null && p2 != null && p1.items == p2.items)
            + " 同液体模块=" + (p1 != null && p2 != null && p1.liquids == p2.liquids));
        check("两台相邻接收台能组合（共用物品池）", p1 != null && p2 != null && p1.items == p2.items);
        check("两台相邻接收台共用液体池", p1 != null && p2 != null && p1.liquids == p2.liquids);

        System.out.println("[LP] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
