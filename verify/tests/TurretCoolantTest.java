package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.scene.ui.layout.Table; import arc.util.Log; import arc.util.Strings;
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
    static String name(Liquid l){ return l == null ? "无" : l.name; }
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

        Block turretItem = null, turretPower = null, cont = null, nodeBlock = null;
        for(Block b : Vars.content.blocks()){
            if(turretItem == null && b.getClass().getName().startsWith("combine.turret.CombinedItemTurret")
                && b instanceof ItemTurret it && it.ammoTypes != null && it.ammoTypes.containsKey(Items.copper)) turretItem = b;
            // 组合炮台（PowerTurret/LaserTurret 走的那个类）：lancer 是 PowerTurret + consumeCoolant
            if(turretPower == null && b.name.equals("lancer") && b.getClass().getName().startsWith("combine.turret.CombinedTurret")) turretPower = b;
            if(b.name.endsWith("liquid-maker")) cont = b;
            if(b.getClass().getName().equals("combine.net.ComboNode")) nodeBlock = b;
        }
        System.out.println("[TC] 物品炮塔=" + (turretItem == null ? "无" : turretItem.name)
            + " 组合炮台=" + (turretPower == null ? "无" : turretPower.name)
            + " 液体伙伴=" + (cont == null ? "无" : cont.name) + " 节点=" + (nodeBlock == null ? "无" : nodeBlock.name));
        if(turretItem == null || turretPower == null || cont == null || nodeBlock == null){ System.out.println("[TC] 缺方块"); System.exit(3); }

        for(Block turretB : new Block[]{turretItem, turretPower}){
            String label = turretB.name;
            for(int y=40;y<140;y++) for(int x=20;x<240;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            run(5);
            Building store = place(cont, 60, 60, Team.sharded);
            // **一组多台**（用户是 15 台）：选择必须整组生效，否则各台互相抢池子的 current
            int stride = Math.max(turretB.size, 1);   // 2x2 的炮塔要按 2 格间距摆才挨着
            Building t1 = place(turretB, 74, 60, Team.sharded);
            Building t2 = place(turretB, 74 + stride, 60, Team.sharded);
            Building t3 = place(turretB, 74 + stride * 2, 60, Team.sharded);
            run(5);
            store.items.add(Items.copper, 300);
            store.liquids.add(Liquids.water, 400f);
            store.liquids.add(Liquids.cryofluid, 400f);
            Building node = place(nodeBlock, 67, 60, Team.sharded);
            run(30);
            StringBuilder keys = new StringBuilder();
            for(Class<?> k : t1.block.configurations.keys()) keys.append(k.getSimpleName()).append(' ');
            System.out.println("[TC][" + label + "] 3 台一组  configurable=" + t1.block.configurable + " 配置=" + keys);

            check(label + "：方块 configurable=true（不然点选择会被 Call.tileConfig 直接忽略）", t1.block.configurable);
            check(label + "：方块注册了 Liquid 配置（克隆后还在）", t1.block.configurations.containsKey(Liquid.class));
            check(label + "：空选时自动用效果最好的冷却液（冷冻液）",
                current(t1) == Liquids.cryofluid && current(t2) == Liquids.cryofluid && current(t3) == Liquids.cryofluid);

            // 只在**第一台**上选水
            configure(t1, Liquids.water);
            run(20);
            System.out.println("[TC][" + label + "] 只在第 1 台选水后: 三台分别=" + name(current(t1)) + "/" + name(current(t2)) + "/" + name(current(t3)));
            check(label + "：在一台上选水，整组都用水（不再被其余台抢回去）",
                current(t1) == Liquids.water && current(t2) == Liquids.water && current(t3) == Liquids.water);

            store.liquids.set(Liquids.water, 0f);
            run(20);
            System.out.println("[TC][" + label + "] 水抽干后: 三台分别=" + name(current(t1)) + "/" + name(current(t2)) + "/" + name(current(t3)));
            check(label + "：选中的冷却液没货时整组自动回退", current(t1) == Liquids.cryofluid && current(t3) == Liquids.cryofluid);

            // 只在第一台上点"取消"
            configure(t1, null);
            store.liquids.set(Liquids.water, 400f);
            run(20);
            System.out.println("[TC][" + label + "] 第 1 台点回自动后: 三台分别=" + name(current(t1)) + "/" + name(current(t2)) + "/" + name(current(t3)));
            check(label + "：点回自动 → 整组又用效果最好的", current(t1) == Liquids.cryofluid && current(t2) == Liquids.cryofluid);
        }

        System.out.println("[TC] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
