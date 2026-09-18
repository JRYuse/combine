package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.files.Fi; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 存/读档不能凭空变出物品。
 *
 * 用户场景：发射到地图时带了 4500 钢化玻璃，核心旁边造容器（= 组合仓库，给核心扩容）后
 * 玻璃变成 7000+。这里复现"核心 + 紧邻容器 + 存读档"这条链，断言读档前后数量一致。
 */
public class SaveRoundTripTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.info.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new SaveRoundTripTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[SR] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    /** 真实放置：(x,y) 是**左下角**；Mindustry 的 tile.setBlock 用的是"锚点=左下角+(size-1)/2"。 */
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){
            try{ bu.created(); }catch(Throwable e){ System.out.println("[SR] created() 抛了: " + e); }
            try{ bu.updateProximity(); }catch(Throwable e){ System.out.println("[SR] updateProximity 抛了: " + e); }
        }
        return bu; }
    /** 全世界物品总量（按模块去重，共用池只算一次）。 */
    static int worldItems(){
        java.util.IdentityHashMap<mindustry.world.modules.ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
        int total = 0;
        for(Tile t : Vars.world.tiles){
            if(t == null || t.build == null || t.build.items == null) continue;
            if(seen.put(t.build.items, Boolean.TRUE) != null) continue;
            total += t.build.items.total();
        }
        return total;
    }
    static int coreGlass(){
        for(Building b : Vars.state.teams.get(Team.sharded).cores) return b.items.get(Items.metaglass);
        return -1; }
    static String gb(Building b){ return b == null ? "null" : b.block.name; }

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

        // 找容器（容器会被替换成组合仓库）
        try{
            Class<?> sb = Class.forName("combine.storage.CombinedStorageBlock", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
            sb.getField("debug").setBoolean(sb, true);
        }catch(Throwable t){ System.out.println("[SR] 打不开 debug: " + t); }
        Block cont = null;
        for(Block b : Vars.content.blocks()) if(b.name.equals("container")){ cont = b; break; }
        if(cont == null) for(Block b : Vars.content.blocks()) if(b.name.equals("vault")){ cont = b; break; }
        System.out.println("[SR] 容器方块=" + gb2(cont) + " 类=" + (cont == null ? "-" : cont.getClass().getName()));
        if(cont == null){ System.out.println("[SR] 没找到容器"); System.exit(3); }

        for(int y=40;y<130;y++) for(int x=20;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
        Building core = place(Blocks.coreShard, 60, 60, Team.sharded);
        run(10);
        System.out.println("[SR] 核心 tile=" + core.tileX() + "," + core.tileY() + " size=" + core.block.size);
        core.items.add(Items.metaglass, 4500);
        System.out.println("[SR] 核心放好，玻璃=" + coreGlass() + " 全世界物品=" + worldItems());
        // 两簇容器：都贴着核心，但彼此不相邻（用户"造了容器给核心扩容"的常见摆法）
        int base0 = coreGlass();
        System.out.println("[SR] --- 放 A 前 玻璃=" + base0 + " 全世界=" + worldItems());
        Building a = place(cont, 63, 60, Team.sharded);    // 核心右边
        run(20);
        System.out.println("[SR] --- 放 A 后 玻璃=" + coreGlass() + " A池=" + a.items.total()
            + " 同模块=" + (a.items == core.items) + " core.module=" + System.identityHashCode(core.items) + " a.module=" + System.identityHashCode(a.items)
            + " 容量=" + ((mindustry.world.blocks.storage.CoreBlock.CoreBuild) core).storageCapacity + " 全世界=" + worldItems());
        Building b = place(cont, 60, 63, Team.sharded);    // 核心下边
        run(20);
        System.out.println("[SR] --- 放 B 后 玻璃=" + coreGlass() + " A池=" + a.items.total() + " B池=" + b.items.total()
            + " 同模块A=" + (a.items == core.items) + " 同模块B=" + (b.items == core.items)
            + " core.module=" + System.identityHashCode(core.items) + " 容量=" + ((mindustry.world.blocks.storage.CoreBlock.CoreBuild) core).storageCapacity
            + " 全世界=" + worldItems());
        System.out.println("[SR] 造容器后: 玻璃=" + coreGlass()
            + "  A并进核心=" + (a.items == core.items) + " B并进核心=" + (b.items == core.items)
            + "  A池物品=" + a.items.total() + " B池物品=" + b.items.total() + " 核心容量=" + ((mindustry.world.blocks.storage.CoreBlock.CoreBuild) core).storageCapacity);
        check("造容器（扩容）不会让物品变多（基线 " + base0 + " → " + coreGlass() + "）", coreGlass() <= base0);
        check("两簇容器都并进核心", a.items == core.items && b.items == core.items);

        Fi file = Core.files.absolute("/tmp/cl/roundtrip.msav");
        SaveIO.save(file);
        System.out.println("[SR] 存档好了: " + file.absolutePath() + " (" + file.length() + " 字节)");
        int before = coreGlass();
        SaveIO.load(file);
        run(40);
        int after = coreGlass();
        System.out.println("[SR] 读档后 玻璃=" + after + "（存档前 " + before + "）");
        check("读档后核心玻璃数量不翻倍（" + before + " → " + after + "）", after == before);

        System.out.println("[SR] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
    static String gb2(Block b){ return b == null ? "null" : b.name; }
}
