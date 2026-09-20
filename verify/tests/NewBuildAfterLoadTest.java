package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 【诊断】读档之后**新建组合建筑**时，核心 / 组合建筑里的物品会不会莫名变多变少。
 * 用户场景：核心 + 若干组合仓库（并进核心扩容）+ 若干组合工厂，存读档后再造东西。
 */
public class NewBuildAfterLoadTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new NewBuildAfterLoadTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[NB] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Block find(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    /** 全世界物品总量（同一个模块只算一次）。 */
    static int worldItems(){
        java.util.IdentityHashMap<mindustry.world.modules.ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
        int total = 0;
        for(Tile t : Vars.world.tiles){
            if(t == null || t.build == null || t.build.items == null) continue;
            if(seen.put(t.build.items, Boolean.TRUE) != null) continue;
            total += t.build.items.total();
        }
        return total; }
    static Building core(){
        for(Building b : Vars.state.teams.get(Team.sharded).cores) return b;
        return null; }
    static String snap(String tag){
        Building c = core();
        StringBuilder sb = new StringBuilder("[NB] " + tag + " 核心玻璃=" + (c==null?-1:c.items.get(Items.metaglass))
            + " 核心铜=" + (c==null?-1:c.items.get(Items.copper))
            + " 世界物品=" + worldItems() + " 核心模块=" + (c==null?0:System.identityHashCode(c.items)));
        for(int[] p : new int[][]{{63,60},{63,63},{66,60},{66,63},{60,63}}){
            Building b = Vars.world.build(p[0], p[1]);
            if(b == null) continue;
            sb.append(" | ").append(p[0]).append(',').append(p[1]).append(':').append(b.block.name)
              .append(" 并核心=").append(c != null && b.items == c.items)
              .append(" 玻璃=").append(b.items.get(Items.metaglass));
        }
        return sb.toString(); }

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
        for(int y=30;y<170;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
        Block cont = find("container");
        System.out.println("[NB] 容器=" + (cont==null?null:cont.name + "/" + cont.getClass().getName()) + " size=" + (cont==null?-1:cont.size));
        if(cont == null) System.exit(3);

        Building c = place(Blocks.coreShard, 60, 60, Team.sharded);
        run(10);
        c.items.add(Items.metaglass, 4500);
        c.items.add(Items.copper, 800);
        place(cont, 63, 60, Team.sharded);   // 贴着核心 → 并进核心
        place(cont, 63, 63, Team.sharded);   // 也贴着核心
        run(30);
        place(cont, 66, 60, Team.sharded);   // 不与核心相邻 → 独立组合仓库组
        run(30);
        System.out.println(snap("造好之后"));
        int before = worldItems();

        SaveIO.save(Core.files.absolute("/tmp/cl/newbuild.msav"));
        SaveIO.load(Core.files.absolute("/tmp/cl/newbuild.msav"));
        run(60);
        System.out.println(snap("读档后"));

        // 用户场景：读档后新建组合建筑
        place(cont, 60, 63, Team.sharded);
        run(60);
        System.out.println(snap("新造一个容器后"));
        check("新造容器后世界物品不变（" + before + "）", worldItems() == before);

        Building c2 = core();
        check("核心玻璃没变（4500）", c2 != null && c2.items.get(Items.metaglass) == 4500);

        // 再放一台组合工厂（combine 自己的方块）
        Block press = find("graphite-press");
        if(press != null){
            place(press, 70, 70, Team.sharded);
            run(60);
            System.out.println(snap("再放一台组合工厂后"));
            check("放组合工厂后世界物品不变", worldItems() == before);
        }

        // 再看看拆掉一个容器
        Building s = Vars.world.build(66, 60);
        if(s != null){ s.tile.remove(); run(60); }
        System.out.println(snap("拆掉一个容器后"));
        check("拆容器后世界物品不变（" + before + "）", worldItems() == before);

        System.out.println("[NB] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
