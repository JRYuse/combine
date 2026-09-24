package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.*; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.Liquid;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报：联机/游戏里"有时候莫名其妙显示进了水，实则没有"，截图里储液罐面板上挂着
 * "7225.7/秒"这种压根不可能的大流量（储罐和抽水机并没有真的连管）。
 *
 * 根因：组合体合并两池子是把液体**内部搬**过去（ComboNet.moveLiquids 等用
 * {@code LiquidModule.add}），而 Mindustry 的 add() 会把搬运量记进"流量窗口"
 * （LiquidModule.add 里 cacheSums += amount）。于是合并/接力一次几千液体，
 * 面板（含 MindustryX 的流量行）就显示"每秒几千的水进账"，其实池子一点没变。
 *
 * 这个测试：两组组合泵各带一批水 → 用中间几台把它们接成一整组（触发并池）
 * → 跑几十 tick 看流量：内部搬池子不该产生流量（修好前会出现几千/秒的正流量）。
 */
public class LiquidFlowPollutionTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[LFP] "+t); };
        new HeadlessApplication(new LiquidFlowPollutionTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[LFP] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }

    /** 单台泵的真实液体容量（方块上的 liquidCapacity 被模组抬成 9999 假容量了）。 */
    static float 数学(Block pump){
        try{
            Class<?> cr = Class.forName("combine.util.ComboReflect", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
            java.lang.reflect.Method m = cr.getMethod("baseLiquidCap", mindustry.world.Block.class);
            Object v = m.invoke(null, pump);
            if(v instanceof Float f) return f;
        }catch(Throwable ignored){}
        return 20f;
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
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        // 用"机械泵"的组合版：有液体容量、1x1、好摆
        Block pump = null;
        for(Block b : Vars.content.blocks())
            if(b.getClass().getName().equals("combine.production.CombinedPump") && b.size == 1 && b.hasLiquids){ pump = b; break; }
        if(pump == null){
            for(Block b : Vars.content.blocks())
                if(b.name.equals("mechanical-pump") && b.hasLiquids){ pump = b; break; }
        }
        if(pump == null){ System.out.println("[LFP] 找不到带液体的组合泵方块"); System.exit(3); }
        Block core = null;
        for(Block b : Vars.content.blocks()) if(b.name.equals("core-shard")) core = b;
        if(core != null) place(core, 80, 70, Team.sharded);
        run(10);
        System.out.println("[LFP] 方块=" + pump.name + " 液体容量=" + pump.liquidCapacity);

        float unitCap = 数学(pump);
        System.out.println("[LFP] 单台液体容量=" + unitCap);

        // A 组：两台相邻（自动成组）→ 灌到"组容量"（= 2 × 单台）
        Building a1 = place(pump, 60, 60, Team.sharded);
        Building a2 = place(pump, 61, 60, Team.sharded);
        run(30);
        a1.liquids.set(Liquids.water, unitCap * 2f);
        run(5);
        System.out.println("[LFP] A 组: 同池=" + (a1.liquids == a2.liquids) + " 水=" + a1.liquids.get(Liquids.water));

        // 【按容量硬删】回归：池子里合法的超容存量不能被删（容量只该拦新液体进入）
        a1.liquids.set(Liquids.water, 100f); // 组容量只有 40
        run(10);
        float kept = a1.liquids.get(Liquids.water);
        check("超容存量不被按容量硬删（现在 " + kept + "，修前会被削成组容量 40）", kept > 60f);
        a1.liquids.set(Liquids.water, unitCap * 2f); // 恢复到"刚好满"，方便后面算并池
        run(5);

        // B 组：10 台相邻（离 A 组远一点），也灌到自己的组容量
        Building bFirst = null;
        for(int i = 0; i < 10; i++){
            Building b = place(pump, 66 + i, 60, Team.sharded);
            if(i == 0) bFirst = b;
        }
        run(30);
        float bCap = unitCap * 10f;
        bFirst.liquids.set(Liquids.water, bCap);
        run(5);
        System.out.println("[LFP] B 组(10 台): 水=" + bFirst.liquids.get(Liquids.water)
            + " 两组同池=" + (a1.liquids == bFirst.liquids));
        check("搭场景：A/B 两组各自独立（还没并池）", a1.liquids != bFirst.liquids);

        // 中间补 4 台把两组接起来（62~65，65 紧挨着 B 组第一台）——每放一台都会触发并组/并池
        for(int i = 0; i < 4; i++){
            place(pump, 62 + i, 60, Team.sharded);
            run(5);
        }
        run(40);

        float water = a1.liquids.get(Liquids.water);
        float rate = a1.liquids.getFlowRate(Liquids.water);
        float moved = unitCap * 12f; // A 组 2 台 + B 组 10 台 = 240，并池后这份水必须都在
        System.out.println("[LFP] 并池后: 同池=" + (a1.liquids == bFirst.liquids) + " 水=" + water
            + " 流量=" + (rate < 0 ? "无数据(-1)" : rate + "/秒"));
        check("场景成立：两组并成一份池子、搬过去的液体一份不少（水 " + water + " ≥ " + moved + "）",
            a1.liquids == bFirst.liquids && water >= moved);
        // 并池是"内部搬"，不该在流量里出现（真流量的来源只有泵自己抽上来的那点）
        check("并池属于内部搬运，不该显示成流量（流量 " + (rate < 0 ? "无数据" : rate + "/秒") + "）", rate <= unitCap * 2f);

        System.out.println("[LFP] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
