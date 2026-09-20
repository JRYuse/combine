package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.blocks.payloads.BuildPayload; import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.units.UnitAssembler; import mindustry.world.blocks.units.UnitAssembler.AssemblerUnitPlan;
import mindustry.type.PayloadStack;

/**
 * 组装机（UnitAssembler）要交的建筑（payload）收不收。
 *
 * 组装机配方是内容初始化阶段用 `PayloadStack.list(UnitTypes.stell, 4, Blocks.tungstenWallLarge, 10)`
 * 建的 —— 里面抓的是**替换前的老方块实例**；而世界里/蓝图里的建筑已经被换成了组合实例，
 * 于是 acceptPayload 里 `b.item == payload.content()` 恒 false：用户报的"组装机不收建筑输入"。
 * 单位（stell 之类）没被替换，所以只坏建筑那一条 —— 这条测试就是卡这个不对称。
 */
public class AssemblerPayloadTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new AssemblerPayloadTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[AP] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Block find(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){
            try{ bu.created(); }catch(Throwable ignored){}
            try{ bu.updateProximity(); }catch(Throwable ignored){}
        }
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

        // 模组的替换表：值非 null 说明这个实例已经被换掉了（= 老实例）
        @SuppressWarnings("rawtypes")
        arc.struct.ObjectMap replaced = null;
        int firstPass = 0;
        try{
            ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
            replaced = (arc.struct.ObjectMap)Class.forName("combine.Replacer", true, ml)
                .getField("replaced").get(null);
            java.lang.reflect.Field cf = Class.forName("combine.Replacer", true, ml).getDeclaredField("capturedCount");
            cf.setAccessible(true);
            firstPass = (Integer)cf.get(null);
        }catch(Throwable t){ System.out.println("[AP] 拿不到替换表: " + t); }
        System.out.println("[AP] 模组装配时回填了 " + firstPass + " 处老方块实例");
        check("模组装配时确实回填过（" + firstPass + " 处 > 0）", firstPass > 0);

        // 所有组装机的配方（原版三台组装机里都同时要单位和建筑）
        int stale = 0, total = 0, assemblers = 0;
        for(Block b : Vars.content.blocks()){
            if(!(b instanceof UnitAssembler ua)) continue;
            assemblers++;
            for(AssemblerUnitPlan plan : ua.plans){
                for(PayloadStack stack : plan.requirements){
                    total++;
                    boolean isStale = replaced != null && replaced.containsKey(stack.item);
                    if(isStale) stale++;
                    System.out.println("[AP] " + ua.name + " 配方 " + plan.unit.name + " 需要 " + stack.amount + "x "
                        + stack.item.name + "（实例=" + stack.item.getClass().getSimpleName() + " 老实例=" + isStale + "）");
                }
            }
        }
        check("组装机配方里的建筑引用不该是老实例（" + stale + "/" + total + " 个是老实例，共 " + assemblers + " 台组装机）",
            stale == 0 && total > 0);
        UnitAssembler ua = (UnitAssembler)find("tank-assembler");
        if(ua == null){ System.out.println("[AP] 没有 tank-assembler"); System.exit(3); }

        // 性能护栏：读写世界时都会兜一次，不能变成"深度扫描整张图"
        try{
            ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
            java.lang.reflect.Method m = Class.forName("combine.Replacer", true, ml)
                .getMethod("remapCapturedBlocks");
            long t0 = System.nanoTime();
            for(int i = 0; i < 10; i++) m.invoke(null);
            long ms = (System.nanoTime() - t0) / 10 / 1_000_000;
            java.lang.reflect.Field cf = Class.forName("combine.Replacer", true, ml).getDeclaredField("capturedCount");
            cf.setAccessible(true);
            int again = (Integer)cf.get(null);
            System.out.println("[AP] 浅层扫描单次 " + ms + " ms，再跑一轮回填 " + again + " 处（幂等）");
            check("浅层扫描单次 < 300ms（" + ms + "ms）", ms < 300);
            check("再扫一遍不再改动（幂等，" + again + " 处）", again == 0);
        }catch(Throwable t){ System.out.println("[AP] 计时失败: " + t); }

        mindustry.maps.Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        for(int y=30;y<170;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        Building asm = place(ua, 60, 60, Team.sharded);
        run(10);
        check("组装机放下来了", asm instanceof UnitAssembler.UnitAssemblerBuild);
        if(asm instanceof UnitAssembler.UnitAssemblerBuild ab){
            Block wall = Blocks.tungstenWallLarge;
            System.out.println("[AP] 世界里/静态字段里的钨墙实例=" + wall.getClass().getSimpleName()
                + "（老实例=" + (replaced != null && replaced.containsKey(wall)) + "）");
            BuildPayload wallPayload = new BuildPayload(wall, Team.sharded);
            UnitPayload unitPayload = new UnitPayload(UnitTypes.stell.create(Team.sharded));
            check("组装机收建筑 payload（钨墙）", ab.acceptPayload(null, wallPayload));
            check("组装机收单位 payload（stell）", ab.acceptPayload(null, unitPayload));

            // 真把建筑登记进组装机的 payload 计数里：配方进度必须认得它（同一套身份比较）
            PayloadStack wallStack = ua.plans.first().requirements.find(s -> s.item instanceof Block);
            int need = wallStack == null ? 0 : wallStack.amount;
            ab.getPayloads().add(wallPayload.content(), need);
            boolean counted = wallStack != null && ab.getPayloads().contains(wallStack);
            System.out.println("[AP] 交满 " + need + " 台 " + wall.name + " 后，配方 " + ua.plans.first().unit.name
                + " 的该项进度认得它=" + counted);
            check("交满需求后配方按组合实例记账、能对上（" + need + " 台）", counted);
        }

        System.out.println("[AP] 结果: pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 ? 0 : 1);
      }catch(Throwable t){ t.printStackTrace(); System.out.println("[AP] 崩了: " + t); System.exit(2); }
    }
}
