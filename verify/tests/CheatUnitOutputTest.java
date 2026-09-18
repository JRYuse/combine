package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.blocks.units.UnitFactory;

/**
 * X 端的"无限火力"（= 给每个队伍打开 team.rules().cheat）下，组合单位工厂必须照样产出单位。
 *
 * 根因：组合工厂为了防"跨成员白嫖"加了完工瞬间的料费实时复核 canAffordNow()；
 * 而无限火力模式下原版**根本不检查也不扣料**（BuildingComp.updateConsumption 直接短路成
 * efficiency=1，consume() 只是走个 trigger），池子里当然是空的 —— 于是工厂永远卡在完工线不产出。
 */
public class CheatUnitOutputTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new CheatUnitOutputTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[CU] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
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

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        // 工厂激活延迟清零，免得测试要跑几千帧
        Vars.state.rules.unitFactoryActivationDelay = 0f;
        Team.sharded.rules().unitFactoryActivationDelay = 0f;
        for(int y=40;y<160;y++) for(int x=20;x<260;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        Block uf = null;
        for(Block b : Vars.content.blocks())
            if(b.getClass().getName().startsWith("combine.units.CombinedUnitFactory")) { uf = b; break; }
        if(uf == null){ System.out.println("[CU] 数据集里没有组合单位工厂方块"); System.exit(3); }
        System.out.println("[CU] 组合单位工厂=" + uf.name + " 类=" + uf.getClass().getName());

        Building b = place(uf, 60, 60, Team.sharded);
        run(10);
        if(!(b instanceof UnitFactory.UnitFactoryBuild)){ System.out.println("[CU] 不是 UnitFactoryBuild"); System.exit(3); }
        UnitFactory.UnitFactoryBuild fb = (UnitFactory.UnitFactoryBuild) b;
        if(fb.currentPlan < 0){ System.out.println("[CU] currentPlan=-1（没有可选单位）"); System.exit(3); }
        float planTime = ((UnitFactory) uf).plans.get(fb.currentPlan).time;
        System.out.println("[CU] 计划=" + ((UnitFactory) uf).plans.get(fb.currentPlan).unit.name + " 时间=" + planTime
            + " 池内物品=" + fb.items.total() + " 无限火力=" + fb.cheating());

        // ---------- 1) 无限火力打开：池子里一点料都没有，也必须产出 ----------
        Team.sharded.rules().cheat = true;
        run(2);
        fb.progress = planTime - 1f;
        run(10);
        boolean produced = fb.payload != null;
        System.out.println("[CU] 无限火力+空池: payload=" + fb.payload + " 单位="
            + (fb.payload == null ? "-" : fb.payload.unit.type.name) + " progress=" + fb.progress);
        check("无限火力（cheat）下空池也能产出单位", produced);

        // ---------- 2) 关掉无限火力：同样的空池，不能白嫖 ----------
        fb.payload = null;
        fb.progress = 0f;
        Team.sharded.rules().cheat = false;
        run(2);
        fb.progress = planTime - 1f;
        run(10);
        System.out.println("[CU] 非无限火力+空池: payload=" + fb.payload + " progress=" + fb.progress);
        check("关掉无限火力后空池不产出（防白嫖逻辑还在）", fb.payload == null);

        System.out.println("[CU] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
