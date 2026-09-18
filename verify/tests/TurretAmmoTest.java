package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;

/**
 * 组合节点/连接器把**仓库和炮台**接在一起时：池子里的弹药要真的进炮塔。
 * 用户报的 bug：炮塔面板里显示着仓库的物品数量，但炮塔打不出来（自己的 ammo 队列是空的）。
 */
public class TurretAmmoTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.info.ordinal()) System.out.println("[L "+l+"] "+t); };
        new HeadlessApplication(new TurretAmmoTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[TA] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
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
        try{
            Vars.mods.eachClass(Mod::init);
        }catch(Throwable t){ System.out.println("[TA] eachClass 抛了: " + t); t.printStackTrace(); }
        System.out.println("[TA] 模组数=" + Vars.mods.list().size + " 方块数=" + Vars.content.blocks().size);
        for(mindustry.mod.Mods.LoadedMod m : Vars.mods.list()){
            System.out.println("[TA]   模组 " + m.name + " main=" + m.main);
        }
        try{
            var cm = Vars.mods.getMod("combine");
            System.out.println("[TA] 手动调用 combine.main.init()...");
            cm.main.init();
            System.out.println("[TA] 手动 init 后 方块数=" + Vars.content.blocks().size);
        }catch(Throwable t){ System.out.println("[TA] 手动 init 抛了: " + t); }
        if(Vars.logic==null) Vars.logic=new Logic();
        if(Vars.netServer==null) Vars.netServer=new NetServer();
        if(Vars.netClient==null) Vars.netClient=new NetClient();

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);

        // 找一台"吃铜"的组合炮塔、一个组合容器、一个组合节点
        Block turret = null, cont = null, nodeBlock = null;
        for(Block b : Vars.content.blocks()){
            if(b.getClass().getName().startsWith("combine.turret.CombinedItemTurret")
                && b instanceof ItemTurret it && it.ammoTypes != null && it.ammoTypes.containsKey(Items.copper) && turret == null) turret = b;
            if(b.name.equals("container")) cont = b;
            if(b.getClass().getName().equals("combine.net.ComboNode")) nodeBlock = b;
        }
        int comboCount = 0, shown2 = 0;
        for(Block b : Vars.content.blocks()){
            if(b.getClass().getName().startsWith("combine.")){
                comboCount++;
                if(shown2++ < 8) System.out.println("[TA]   combine 方块: " + b.name + " / " + b.getClass().getName());
            }
        }
        System.out.println("[TA] class 以 combine. 开头的方块数=" + comboCount);
        int shown = 0;
        for(Block b : Vars.content.blocks()){
            String cn = b.getClass().getName();
            if((cn.contains("ComboNode") || cn.contains("CombinedItemTurret") || cn.contains("ComboConnector")) && shown++ < 8)
                System.out.println("[TA]   候选: " + b.name + " / " + cn);
        }
        System.out.println("[TA] 炮塔=" + (turret == null ? "无" : turret.name) + " 容器=" + (cont == null ? "无" : cont.name)
            + " 节点=" + (nodeBlock == null ? "无" : nodeBlock.name));
        if(turret == null || cont == null || nodeBlock == null){ System.out.println("[TA] 缺方块"); System.exit(3); }

        for(int y=40;y<140;y++) for(int x=20;x<240;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
        Building store = place(cont, 60, 60, Team.sharded);
        Building tower = place(turret, 74, 60, Team.sharded);
        run(5);
        store.items.add(Items.copper, 200);
        Building node = place(nodeBlock, 67, 60, Team.sharded);
        run(60);

        boolean samePool = store.items == tower.items;
        int poolCopper = store.items.get(Items.copper);
        int ammo = reflectInt(tower, "totalAmmo");
        System.out.println("[TA] 接上后: 同池=" + samePool + " 池里铜=" + poolCopper + " 炮塔 totalAmmo=" + ammo);
        check("仓库和炮塔接上后共用一个池（面板显示的就是这个池）", samePool && poolCopper > 0);
        check("池子里的弹药会真的进炮塔（totalAmmo > 0）", ammo > 0);
        check("进炮塔的弹药是从池子里扣掉的（不是凭空来的）", poolCopper < 200);

        System.out.println("[TA] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }

    static int reflectInt(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ java.lang.reflect.Field f = c.getDeclaredField(name); f.setAccessible(true); return f.getInt(o); }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return -1;
    }
}
