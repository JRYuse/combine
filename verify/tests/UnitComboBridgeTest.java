package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.blocks.units.UnitFactory;

/**
 * 建筑侧 → 单位侧的**反射桥**（{@code combine.util.UnitComboBridge}）必须真的把
 * "工厂造出来的单位"交给 combineunit 打组合标记。
 *
 * <p>背景：单位侧机制（组合巨兽/共享承伤/共享火力/编组 UI）拆到了 combineunit 仓库；
 * 本仓库的组合单位工厂造出单位后，唯一要做的就是让它继承本建筑组合体的组标记
 * （{@code comboId = 组长坐标 + 1}，combineunit 的 {@code UnitComboDamage.tagProduced}）。
 * Mindustry 每个模组一份类加载器 → 编译期看不见 → 只能按名字反射调。
 *
 * <p>本测试要一套**同时装了 combine + combineunit** 的数据目录（见 verify/README.md
 * "跨仓库：反射桥" 一节）；只装 combine 时打印 SKIP 直接退出（不算失败）。
 *
 * <p>判定：无限火力下让组合单位工厂产出单位，然后用反射问 combineunit
 * {@code UnitComboDamage.comboId(Unit)} —— 必须等于 leader.pos() + 1（不是 0 = 没打标记）。
 */
public class UnitComboBridgeTest implements ApplicationListener{
    static String dataDir="/tmp/mp_both/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new UnitComboBridgeTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[UCB] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        return Vars.world.build(ax,ay);
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

        var unitMod = Vars.mods.getMod("combineunit");
        if(unitMod == null || unitMod.main == null){
            System.out.println("[UCB] SKIP 这套数据目录里没有 combineunit（只装了 combine）—— 反射桥会静默跳过，工厂照常工作");
            Class<?> bridge0 = Class.forName("combine.util.UnitComboBridge",
                true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
            System.out.println("[UCB] 反射桥 available()=" + bridge0.getMethod("available").invoke(null)
                + "（没装 combineunit → 必须是 false）");
            System.exit(0);
        }
        ml = unitMod.main.getClass().getClassLoader();
        Class<?> damage = Class.forName("combineunit.units.UnitComboDamage", true, ml);
        java.lang.reflect.Method comboId = damage.getMethod("comboId", Unit.class);

        ClassLoader cml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        Class<?> reflect = Class.forName("combine.util.ComboReflect", true, cml);
        java.lang.reflect.Method leader = reflect.getMethod("leader", Building.class);
        Class<?> bridge = Class.forName("combine.util.UnitComboBridge", true, cml);
        System.out.println("[UCB] 反射桥 available()=" + bridge.getMethod("available").invoke(null));

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        Vars.state.rules.unitFactoryActivationDelay = 0f;
        Team.sharded.rules().unitFactoryActivationDelay = 0f;
        for(int y=40;y<160;y++) for(int x=20;x<260;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        Block uf = null;
        for(Block b : Vars.content.blocks())
            if(b.getClass().getName().startsWith("combine.units.CombinedUnitFactory")) { uf = b; break; }
        if(uf == null){ System.out.println("[UCB] SKIP 数据集里没有组合单位工厂方块"); System.exit(0); }
        System.out.println("[UCB] 组合单位工厂=" + uf.name + " 类=" + uf.getClass().getName());

        Building b = place(uf, 60, 60, Team.sharded);
        run(10);
        if(!(b instanceof UnitFactory.UnitFactoryBuild fb)){
            System.out.println("[UCB] SKIP 不是 UnitFactoryBuild"); System.exit(0); return;
        }
        if(fb.currentPlan < 0){ System.out.println("[UCB] SKIP currentPlan=-1（没有可选单位）"); System.exit(0); }
        float planTime = ((UnitFactory) uf).plans.get(fb.currentPlan).time;

        Team.sharded.rules().cheat = true;
        run(2);
        fb.progress = planTime - 1f;
        run(10);
        if(fb.payload == null){ check("无限火力下工厂产出单位（前置条件）", false); }
        else{
            Unit u = fb.payload.unit;
            double gid = ((Number)comboId.invoke(null, u)).doubleValue();
            Building l = (Building)leader.invoke(null, b);
            double expect = l == null ? 0.0 : (double)l.pos() + 1.0;
            System.out.println("[UCB] 产出单位=" + u.type.name + " 类=" + u.getClass().getName()
                + " comboId=" + gid + " 期望（组长坐标+1）=" + expect + " 组长=" + (l == null ? "-" : l.block.name + "@" + l.pos()));
            check("无限火力下工厂产出单位（前置条件）", true);
            check("产出单位带组合标记（comboId != 0）", gid != 0.0);
            check("组合标记 = 本建筑组合体组长坐标 + 1", gid == expect);
            check("单位的实体类来自 combineunit 的镜像类（共享承伤/火力生效的前提）",
                u.getClass().getName().startsWith("combineunit.units.entities."));
        }

        System.out.println("[UCB] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
