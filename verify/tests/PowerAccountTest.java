package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import arc.util.Strings; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 组合体的**耗电记账**在「游戏里摆出来」和「读档读出来」两条路上要一致 ——
 * 用户联机截图：服务端 +2.4k、客户端 -9.2k（客户端是读图/读档建的，服务端是在线摆的）。
 *
 * 判据：同一组 3 台组合冶炼厂 + 一个电源，
 *   在游戏里摆好 → 电网「需要」多少；
 *   存读一次（= 客户端入服/读档的路径）→ 电网「需要」多少。
 * 两个数字必须一样，且都应该等于"整组耗电之和"。
 */
public class PowerAccountTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new PowerAccountTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[PA] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Block findExact(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static void clearArea(){
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
    }
    static String report(String tag, Building member){
        var g = member.power.graph;
        return tag + ": 电网#" + g.getID() + " all=" + g.all.size + " 产=" + g.producers.size + " 耗=" + g.consumers.size
            + " 发电=" + Strings.fixed(g.getLastPowerProduced(), 2) + " 需要=" + Strings.fixed(g.getLastPowerNeeded(), 2)
            + " 平衡=" + Strings.fixed(g.getPowerBalance(), 2);
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

        Block smelter = findExact("silicon-smelter");
        Block src = findExact("power-source");
        if(smelter == null || src == null){ System.out.println("[PA] 缺方块"); System.exit(3); }
        int psz = Math.max(smelter.size, 1);
        float each = smelter.consPower == null ? -1f : smelter.consPower.usage;
        System.out.println("[PA] 组合冶炼厂 size=" + psz + " 每台耗电=" + each + "/tick（3 台应为 " + (each*3) + "）");

        clearArea();
        Building a = place(smelter, 60, 60, Team.sharded);
        Building b = place(smelter, 60 + psz, 60, Team.sharded);
        Building c = place(smelter, 60 + psz * 2, 60, Team.sharded);
        Building s = place(src, 60 + psz * 3, 60, Team.sharded);
        run(30);
        a.items.add(Items.coal, 200); a.items.add(Items.sand, 400);
        run(60);
        System.out.println("[PA] 摆出来 " + report("在线摆", a));
        float placedNeed = a.power.graph.getLastPowerNeeded();
        float placedProduced = a.power.graph.getLastPowerProduced();
        check("在线摆：三台都在同一张电网", a.power.graph == b.power.graph && a.power.graph == c.power.graph);
        check("在线摆：电源只接在最后一台上，三台都通电（status>0）",
            a.power.status > 0f && b.power.status > 0f && c.power.status > 0f);
        check("在线摆：电网需要 = 整组耗电之和（" + placedNeed + " ≈ " + (each*3) + "）",
            Math.abs(placedNeed - each * 3) < 0.05f);

        SaveIO.save(Core.files.absolute("/tmp/cl/acct.msav"));
        SaveIO.load(Core.files.absolute("/tmp/cl/acct.msav"));
        Vars.state.set(mindustry.core.GameState.State.playing);
        run(60);
        Building a2 = Vars.world.build(60, 60);
        if(a2 != null){ a2.items.add(Items.coal, 200); a2.items.add(Items.sand, 400); }
        run(60);
        System.out.println("[PA] 读档后 " + report("读档", a2));
        float loadNeed = a2 == null ? -1f : a2.power.graph.getLastPowerNeeded();
        check("读档后：电源只接在一侧，三台都通电（status>0）",
            a2 != null && Vars.world.build(60 + psz, 60) != null && Vars.world.build(60 + psz * 2, 60) != null
            && a2.power.status > 0f && Vars.world.build(60 + psz, 60).power.status > 0f && Vars.world.build(60 + psz * 2, 60).power.status > 0f);
        check("读档后：电网需要 = 整组耗电之和（" + loadNeed + " ≈ " + (each*3) + "）",
            Math.abs(loadNeed - each * 3) < 0.05f);
        check("两条路记账一致（在线 " + placedNeed + " vs 读档 " + loadNeed + "，差 " + Math.abs(placedNeed - loadNeed) + "）",
            Math.abs(placedNeed - loadNeed) < 0.05f);
        check("两条路产出也一致（" + placedProduced + "）",
            a2 != null && Math.abs(placedProduced - a2.power.graph.getLastPowerProduced()) < 0.05f);
        System.out.println("[PA] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
