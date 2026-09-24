package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报：核心 + 贴着核心的容器 用组合节点接上组合工厂之后，**拆工厂**的时候核心里的东西全没了。
 *
 * 场景：核心 + 并仓容器（池子就是核心那份）+ 组合节点把容器和两台组合工厂接成一张网络；
 * 然后（1）断开其中一台工厂的连接、（2）拆掉一台工厂，核心库存都必须一份不少。
 */
public class CoreFactoryNodeTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    static Block core, cont, kiln, pulv, node;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new CoreFactoryNodeTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[CF] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Block findExact(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static Block byClass(String cls){ for(Block b : Vars.content.blocks()) if(b.getClass().getName().equals(cls)) return b; return null; }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static void clearArea(){
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
    }
    static String inv(Building b){
        if(b == null) return "null";
        return b.block.name + "@" + b.tileX() + "," + b.tileY() + "{铜=" + b.items.get(Items.copper) + " 铅=" + b.items.get(Items.lead)
            + " 石墨=" + b.items.get(Items.graphite) + " 总=" + b.items.total() + "}";
    }
    static int worldTotal(){
        java.util.IdentityHashMap<mindustry.world.modules.ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
        int total = 0;
        for(Tile t : Vars.world.tiles){
            if(t == null || t.build == null || t.build.items == null) continue;
            if(seen.put(t.build.items, Boolean.TRUE) != null) continue;
            total += t.build.items.total();
        }
        return total;
    }
    static boolean coreOk(Building c){
        return c.items.get(Items.copper) == 800 && c.items.get(Items.lead) == 400 && c.items.get(Items.graphite) == 300;
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
        clearArea();

        core = findExact("core-shard");
        cont = findExact("container");
        kiln = findExact("kiln");
        pulv = findExact("pulverizer");
        node = byClass("combine.net.ComboNode");
        if(core == null || cont == null || kiln == null || pulv == null || node == null){ System.out.println("[CF] 缺方块"); System.exit(3); }

        Building c = place(core, 60, 60, Team.sharded);
        run(5);
        Building k = place(cont, 63, 60, Team.sharded);
        run(20);
        c.items.add(Items.copper, 800); c.items.add(Items.lead, 400); c.items.add(Items.graphite, 300);
        run(20);
        System.out.println("[CF] 并仓后: 核心=" + inv(c) + " 容器=" + inv(k) + " 同池=" + (c.items == k.items));
        int total0 = worldTotal();

        Building n = place(node, 66, 60, Team.sharded);
        Building a = place(kiln, 69, 60, Team.sharded);
        Building b = place(pulv, 74, 60, Team.sharded);
        run(20);
        n.onConfigureBuildTapped(k);
        run(20);
        n.onConfigureBuildTapped(a);
        run(20);
        n.onConfigureBuildTapped(b);
        run(40);
        System.out.println("[CF] 接上后: 核心=" + inv(c) + " 容器=" + inv(k) + " 窑=" + inv(a) + " 粉碎机=" + inv(b)
            + " 世界总量=" + worldTotal() + " (初始 " + total0 + ")");
        check("接上后核心库存没被搬走", coreOk(c));
        check("接上后世界总量没变（" + total0 + " → " + worldTotal() + "）", worldTotal() == total0);

        check("接上组合节点后核心库存一份不少（" + c.items.total() + "/1500）", coreOk(c));

        // ---- 场景 A：断开一台工厂（它带着核心那份池子离开网络）----
        n.onConfigureBuildTapped(b);
        run(60);
        System.out.println("[CF] A 断开粉碎机后: 核心=" + inv(c) + " 容器=" + inv(k) + " 窑=" + inv(a) + " 粉碎机=" + inv(b)
            + " 世界总量=" + worldTotal());
        check("A 断开一台工厂后核心库存一份不少（" + c.items.total() + "，应为 1500）", coreOk(c));
        check("A 断开一台工厂后没过路丢东西（" + total0 + " → " + worldTotal() + "）", worldTotal() == total0);

        // ---- 场景 B：断开之后把那台工厂拆掉（不该把核心的东西带走）----
        Vars.world.tile(b.tileX(), b.tileY()).setBlock(Blocks.air);
        run(60);
        System.out.println("[CF] B 拆掉已断开的粉碎机后: 核心=" + inv(c) + " 容器=" + inv(k) + " 窑=" + inv(a)
            + " 世界总量=" + worldTotal());
        check("B 拆掉工厂后核心库存一份不少（" + c.items.total() + "，应为 1500）", coreOk(c));
        check("B 拆掉工厂后没过路丢东西（" + total0 + " → " + worldTotal() + "）", worldTotal() == total0);

        // ---- 场景 C：拆掉网络里还在连着的那台工厂 ----
        Vars.world.tile(a.tileX(), a.tileY()).setBlock(Blocks.air);
        run(60);
        System.out.println("[CF] C 拆掉窑后: 核心=" + inv(c) + " 容器=" + inv(k) + " 世界总量=" + worldTotal());
        check("C 拆掉工厂后核心库存一份不少", coreOk(c));
        check("C 拆掉工厂后没过路丢东西（" + total0 + " → " + worldTotal() + "）", worldTotal() == total0);

        // ---- 场景 D：重来一次，拆掉网络里的**组长**工厂（pos 最小的那台）----
        clearArea();
        run(5);
        checkBaseAgain("D 拆掉组长工厂");

        // ---- 场景 E：直接拆掉贴着核心的容器 ----
        clearArea();
        run(5);
        checkBaseAgain("E 拆掉贴着核心的容器");

        System.out.println("[CF] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }

    /**
     * 重来一遍基础布局（核心 + 贴着核心的容器 + 组合节点接两台工厂），
     * 然后把左侧那台工厂（网络里的组长）拆掉，核心库存必须一份不少。
     */
    static void checkBaseAgain(String tag){
        Building c = place(core, 60, 60, Team.sharded);
        run(5);
        Building k = place(cont, 63, 60, Team.sharded);
        run(20);
        c.items.add(Items.copper, 800); c.items.add(Items.lead, 400); c.items.add(Items.graphite, 300);
        run(20);
        Building n = place(node, 66, 60, Team.sharded);
        Building a = place(kiln, 69, 60, Team.sharded);
        Building b = place(pulv, 74, 60, Team.sharded);
        run(20);
        n.onConfigureBuildTapped(k);
        run(20);
        n.onConfigureBuildTapped(a);
        run(20);
        n.onConfigureBuildTapped(b);
        run(40);
        int total0 = worldTotal();

        if(tag.startsWith("D")){
            Vars.world.tile(a.tileX(), a.tileY()).setBlock(Blocks.air);
        }else{
            Vars.world.tile(k.tileX(), k.tileY()).setBlock(Blocks.air);
        }
        run(60);
        System.out.println("[CF] " + tag + " 后: 核心=" + inv(c) + " 容器=" + inv(k) + " 世界总量=" + worldTotal());
        check(tag + "后核心库存一份不少（" + c.items.total() + "，应为 1500）", coreOk(c));
        check(tag + "后没过路丢东西（" + total0 + " → " + worldTotal() + "）", worldTotal() == total0);
    }
}
