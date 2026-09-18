package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts;
import mindustry.world.*; import mindustry.world.blocks.defense.turrets.*;

/**
 * 炮台 + 装着不同液体的仓库组合时：
 *   · 空选（默认）→ 自动用池子里**效果最好**的冷却液（heatCapacity 最高）；
 *   · 选中某种 → 就用那种；点回"自动" → 回到自动。
 */
public class TurretCoolantTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new TurretCoolantTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[TC] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Liquid current(Building b){ return b.liquids == null ? null : b.liquids.current(); }
    /** headless 下没有网络，直接走服务端收到配置包后调用的那个方法。 */
    static void configure(Building b, Object v){ b.configured(null, v); }

    static Object call(Building b, String name){
        try{
            Class<?> c = b.getClass();
            while(c != null){
                try{ var m = c.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(b); }
                catch(NoSuchMethodException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return null;
    }
    static Object sel(Building b){ return field(b, "selectedCoolant"); }
    static Object field(Building b, String name){
        try{
            Class<?> c = b.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField(name); f.setAccessible(true); return f.get(b); }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return null;
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

        Block turret = null, cont = null, nodeBlock = null;
        for(Block b : Vars.content.blocks()){
            if(b.getClass().getName().startsWith("combine.turret.CombinedItemTurret")
                && b instanceof ItemTurret it && it.ammoTypes != null && it.ammoTypes.containsKey(Items.copper) && turret == null) turret = b;
            // 液体伙伴：用测试模组里带液体的协作方块（原版容器只存物品，没有液体模块）
            if(b.name.endsWith("liquid-maker")) cont = b;
            if(b.getClass().getName().equals("combine.net.ComboNode")) nodeBlock = b;
        }
        if(turret == null || cont == null || nodeBlock == null){ System.out.println("[TC] 缺方块"); System.exit(3); }

        for(int y=40;y<140;y++) for(int x=20;x<240;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
        Building store = place(cont, 60, 60, Team.sharded);
        Building tower = place(turret, 74, 60, Team.sharded);
        run(5);
        // 仓库里放两种冷却液：水（heatCapacity 低）和冷冻液（高）
        store.items.add(Items.copper, 300);
        store.liquids.add(Liquids.water, 400f);
        store.liquids.add(Liquids.cryofluid, 400f);
        Building node = place(nodeBlock, 67, 60, Team.sharded);
        run(30);

        System.out.println("[TC] 两种冷却液在池里: 水=" + store.liquids.get(Liquids.water) + " 冷冻液=" + store.liquids.get(Liquids.cryofluid)
            + " | 炮台当前冷却液=" + (current(tower) == null ? "无" : current(tower).name)
            + "（水 heatCapacity=" + Liquids.water.heatCapacity + " 冷冻液=" + Liquids.cryofluid.heatCapacity + "）");
        check("空选时自动用效果最好的冷却液（冷冻液）", current(tower) == Liquids.cryofluid);

        // 手动选水
        configure(tower, Liquids.water);
        run(20);
        System.out.println("[TC] 手动选水后: 选择=" + sel(tower) + " 生效=" + call(tower, "effectiveCoolant") + " 当前=" + (current(tower) == null ? "无" : current(tower).name));
        check("手动选中水后就用水", current(tower) == Liquids.water);

        // 选的那种没了 → 自动回退
        store.liquids.set(Liquids.water, 0f);
        run(20);
        System.out.println("[TC] 水抽干后: 炮台当前冷却液=" + (current(tower) == null ? "无" : current(tower).name));
        check("选中的冷却液没货时自动回退到还有的（冷冻液）", current(tower) == Liquids.cryofluid);

        // 回到自动
        configure(tower, null);
        store.liquids.set(Liquids.water, 400f);
        run(20);
        System.out.println("[TC] 点回自动后: 选择=" + sel(tower) + " 生效=" + call(tower, "effectiveCoolant") + " 当前=" + (current(tower) == null ? "无" : current(tower).name)
            + " 池里 水=" + store.liquids.get(Liquids.water) + " 冷冻液=" + store.liquids.get(Liquids.cryofluid));
        check("点回自动 → 又用效果最好的（冷冻液）", current(tower) == Liquids.cryofluid);

        System.out.println("[TC] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
