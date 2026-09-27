package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts;
import mindustry.world.*; import mindustry.type.Liquid;

/**
 * 用户报："如果组合体或者合体炮台的液体容量之和超过 9999 的话还是会被限制在 9999"。
 *
 * 根因：组合方块为了不让原版管道算出负流量，把 {@code block.liquidCapacity} 抬成 **9999 假容量**
 * （真正的上限存在 build 的 {@code comboTotalLiquidCap}，由 acceptLiquid/handleLiquid 拦）。
 * 可原版 {@code Building.transferLiquid} / {@code moveLiquid} 是拿**目标方块的
 * block.liquidCapacity** 限流的（{@code flow = cap - 池内已有量}）——池子一到 9999，
 * 管道就再也送不进去，ass 里的组容量上限根本轮不到。
 *
 * 修法：{@code ComboReflect.setLiquidCap} 顺手把 {@code block.liquidCapacity} 抬到
 * "见过的最大组容量"（只松上限，永不回缩；该方块类型的其它组仍由 acceptLiquid 按各自的
 * comboTotalLiquidCap 拦）。
 *
 * 这个测试直接验三件事：
 *   1) setLiquidCap 之后 block.liquidCapacity ≥ 组容量（修前恒 9999）；
 *   2) 池子到 9999 时，原版的流量公式还能算出正的流量（修前是 0）；
 *   3) 同一方块再次 init() 不会把"被抬高的容量"误记成基础容量（否则容量算成 N 倍）。
 */
public class LiquidCapOverflowTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[LCO] "+t); };
        new HeadlessApplication(new LiquidCapOverflowTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[LCO] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Object callStatic(String cls, String name, Class<?>[] sig, Object... args){
        try{
            Class<?> c = Class.forName(cls, true, ml);
            java.lang.reflect.Method m = sig == null ? c.getMethod(name) : c.getMethod(name, sig);
            m.setAccessible(true);
            return m.invoke(null, args);
        }catch(Throwable t){ System.out.println("[LCO] 调用 " + cls + "." + name + " 失败: " + t); return null; }
    }
    /** ComboReflect.baseLiquidCap(Building)：这一台"放大前"的基础液体容量。 */
    static float baseLiquidCap(Building b){
        Object v = callStatic("combine.util.ComboReflect", "baseLiquidCap",
            new Class<?>[]{ Building.class }, b);
        return v instanceof Float f ? f : -1f;
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
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        Block core = null;
        for(Block b : Vars.content.blocks()) if(b.name.equals("core-shard")){ core = b; break; }
        if(core != null) place(core, 60, 100, Team.sharded);
        run(5);

        // 找一个"有液体、1x1"的组合方块（组合泵/组合液体炮塔等）
        Block pump = null;
        for(Block b : Vars.content.blocks())
            if(b.getClass().getName().equals("combine.production.CombinedPump") && b.size == 1 && b.hasLiquids){ pump = b; break; }
        if(pump == null){
            for(Block b : Vars.content.blocks())
                if(b.getClass().getName().startsWith("combine.") && b.hasLiquids && b.size == 1){ pump = b; break; }
        }
        if(pump == null){ System.out.println("[LCO] 这套数据集没有 1x1 的组合液体方块，跳过"); System.exit(0); }
        System.out.println("[LCO] 组合液体方块=" + pump.name
            + " block.liquidCapacity=" + pump.liquidCapacity);

        Building a = place(pump, 40, 40, Team.sharded);
        Building b = place(pump, 41, 40, Team.sharded);
        run(30);
        if(a == null || b == null){ System.out.println("[LCO] 组合方块没放下，跳过"); System.exit(3); }

        float groupCap = readFloat(a, "comboTotalLiquidCap");
        System.out.println("[LCO] 组容量=" + groupCap + " 方块假容量=" + pump.liquidCapacity
            + " 基础=" + baseLiquidCap(a));

        // ---- 1) 用户报的场景：组容量 > 9999，方块容量得跟着抬上去 ----
        callStatic("combine.util.ComboReflect", "setLiquidCap",
            new Class<?>[]{ Building.class, float.class }, a, 12000f);
        float capAfter = pump.liquidCapacity;
        System.out.println("[LCO] setLiquidCap(12000) 之后 block.liquidCapacity=" + capAfter);
        check("方块容量被抬到组容量（≥12000，不再卡 9999）", capAfter >= 12000f - 0.01f);

        // ---- 2) 池子到 9999 时，原版流量公式仍应算得出正流量 ----
        Liquid water = findWater();
        if(water != null && a.liquids != null){
            a.liquids.add(water, 9999f);
            float flow = Math.min(pump.liquidCapacity - a.liquids.get(water), 100f);
            System.out.println("[LCO] 池=9999 时原版可再收流量=" + flow + "（修前 = "
                + Math.min(9999f - 9999f, 100f) + "）");
            check("池子到 9999 之后管道还能继续送（flow > 0）", flow > 0.001f);
        }

        // ---- 3) 同一方块再 init() 不许把"抬高后的容量"当成基础容量 ----
        float baseBefore = baseLiquidCap(a);
        try{ pump.init(); }catch(Throwable ignored){}
        float baseAfter = baseLiquidCap(a);
        System.out.println("[LCO] 再次 init() 前后基础容量: " + baseBefore + " → " + baseAfter);
        check("重复 init() 不会污染基础容量（容量不翻 N 倍）",
            Math.abs(baseBefore - baseAfter) < 0.01f);
      }catch(Throwable t){ System.out.println("[LCO] 运行异常: " + t); t.printStackTrace(); }
      System.out.println("[LCO] 结果: PASS=" + pass + " FAIL=" + fail);
      arc.Core.app.exit();
    }

    static Liquid findWater(){
        for(Liquid l : Vars.content.liquids()) if(l.name.equals("water")) return l;
        return null;
    }
    static float readFloat(Object o, String name){
        for(Class<?> c = o.getClass(); c != null; c = c.getSuperclass()){
            try{ var f = c.getDeclaredField(name); f.setAccessible(true); return f.getFloat(o); }catch(Throwable ignored){}
        }
        return -1f;
    }
}
