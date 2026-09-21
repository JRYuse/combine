package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Strings;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报：用组合节点把「组合仓库」和「石墨压缩机」接起来之后，核心里的东西**全跑到石墨压缩机里**，
 * 而且**切断组合也不会复原**。
 *
 * 场景：核心 + 挨着核心的组合仓库（并仓，池子就是核心那份）+ 组合节点 + 石墨压缩机。
 */
public class CorePoolPullTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new CorePoolPullTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[CP] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
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
        return b.block.name + "@" + b.tileX() + "," + b.tileY() + "{模块=" + System.identityHashCode(b.items)
            + " 铜=" + b.items.get(Items.copper) + " 铅=" + b.items.get(Items.lead) + " 石墨=" + b.items.get(Items.graphite)
            + " 总=" + b.items.total() + "}";
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

        Block core = findExact("core-shard");
        Block cont = findExact("container");
        Block press = findExact("graphite-press");
        Block node = byClass("combine.net.ComboNode");
        Block src = findExact("power-source");
        if(core == null || cont == null || press == null || node == null){ System.out.println("[CP] 缺方块"); System.exit(3); }
        System.out.println("[CP] core=" + core.getClass().getSimpleName() + " cont=" + cont.getClass().getSimpleName()
            + " press=" + press.getClass().getSimpleName() + " node=" + node.getClass().getSimpleName());

        // 核心 + 挨着核心的容器（组合仓库并仓）
        Building c = place(core, 60, 60, Team.sharded);
        System.out.println("[CP] 刚放下核心: c=" + (c == null ? null : c.block.name + "@" + c.tileX() + "," + c.tileY())
            + " tile(61,61)=" + Vars.world.tile(61,61).block() + " build=" + Vars.world.build(61,61)
            + " 队伍核心数=" + Vars.state.teams.get(Team.sharded).cores.size);
        run(5);
        System.out.println("[CP] 5 tick 后: tile(61,61)=" + Vars.world.tile(61,61).block() + " build=" + Vars.world.build(61,61)
            + " 队伍核心数=" + Vars.state.teams.get(Team.sharded).cores.size);
        Building k = place(cont, 63, 60, Team.sharded);
        run(30);
        c.items.add(Items.copper, 800); c.items.add(Items.lead, 400); c.items.add(Items.graphite, 300);
        run(30);
        Building at = Vars.world.build(61,61);
        System.out.println("[CP] 队伍核心数=" + Vars.state.teams.get(Team.sharded).cores.size
            + " 61,61类=" + (at == null ? null : at.getClass().getName())
            + " 是CoreBuild?=" + (at instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild)
            + " 容器类=" + (k == null ? null : k.getClass().getName())
            + " 容器是StorageBuild?=" + (k instanceof mindustry.world.blocks.storage.StorageBlock.StorageBuild));
        System.out.println("[CP] 并仓后: 核心=" + inv(c) + " 容器=" + inv(k)
            + " 同池=" + (c.items == k.items));
        int total0 = worldTotal();
        System.out.println("[CP] 世界物品总量=" + total0);

        // 远处一个石墨压缩机 + 组合节点，把节点连到容器（= 核心那一份池子）
        Building n = place(node, 66, 60, Team.sharded);
        Building p = place(press, 68, 60, Team.sharded);
        Building s = place(src, 66, 62, Team.sharded);
        run(20);
        n.onConfigureBuildTapped(k);
        run(20);
        n.onConfigureBuildTapped(p);
        run(60);
        System.out.println("[CP] 接上后: 核心=" + inv(c) + " 容器=" + inv(k) + " 压缩机=" + inv(p));
        System.out.println("[CP] 接上后: 核心和压缩机同池=" + (c.items == p.items)
            + " 核心和容器同池=" + (c.items == k.items) + " 世界总量=" + worldTotal());
        check("接上组合节点后物品一份没多没少（" + total0 + " → " + worldTotal() + "）", worldTotal() == total0);
        check("接上后压缩机用的是核心那一份池子（不是把核心的东西搬走）", c.items == p.items || p.items.get(Items.copper) == 0);

        // 断开
        java.lang.reflect.Field lf = n.getClass().getDeclaredField("links");
        lf.setAccessible(true);
        arc.struct.IntSeq links = (arc.struct.IntSeq) lf.get(n);
        System.out.println("[CP] 断开前节点 links=" + links.size);
        // 把两根线都断掉（只断一根的话另一边还连着，池子本来就该继续共享）
        for(int i = links.size - 1; i >= 0; i--){
            Building target = Vars.world.build(links.get(i));
            n.onConfigureBuildTapped(target);
            run(10);
        }
        run(60);
        System.out.println("[CP] 断开后节点 links=" + links.size);
        System.out.println("[CP] 断开后: 核心=" + inv(c) + " 容器=" + inv(k) + " 压缩机=" + inv(p));
        System.out.println("[CP] 断开后: 核心和压缩机同池=" + (c.items == p.items) + " 世界总量=" + worldTotal());
        check("断开后核心的东西还在（核心 铜+铅+石墨 = " + (c.items.get(Items.copper)+c.items.get(Items.lead)+c.items.get(Items.graphite)) + "）",
            c.items.get(Items.copper) + c.items.get(Items.lead) + c.items.get(Items.graphite) == 1500);
        check("断开后没过路丢东西（" + total0 + " → " + worldTotal() + "）", worldTotal() == total0);
        System.out.println("[CP] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
