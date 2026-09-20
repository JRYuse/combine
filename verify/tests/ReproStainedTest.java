package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.files.Fi; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.Item;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.ItemModule;

/**
 * 用户复现存档：stainedMountains（sector-serpulo-223.msav）+ 那套模组。
 * 读档 → 存档 → 再读档，看物品是不是会涨（核心 & 组合建筑里的都算）。
 */
public class ReproStainedTest implements ApplicationListener{
    static String dataDir="/tmp/mp_rep/data";
    static String savePath=System.getProperty("save", "/tmp/mp_rep/data/saves/sector-serpulo-223.msav");
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new ReproStainedTest(), t->t.printStackTrace()); }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building core(){
        for(Building b : Vars.state.teams.get(Team.sharded).cores) return b;
        return null; }

    /** 全世界物品总量（同一个模块只算一次）。 */
    static int worldTotal(){
        java.util.IdentityHashMap<ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
        int total = 0;
        for(Tile t : Vars.world.tiles){
            if(t == null || t.build == null || t.build.items == null) continue;
            if(seen.put(t.build.items, Boolean.TRUE) != null) continue;
            total += t.build.items.total();
        }
        return total; }

    static String coreItems(){
        Building c = core();
        if(c == null || c.items == null) return "无核心";
        StringBuilder sb = new StringBuilder();
        for(Item it : Vars.content.items()){
            int n = c.items.get(it);
            if(n > 0) sb.append(it.name).append('=').append(n).append(' ');
        }
        return sb.toString(); }

    static String snap(String tag){
        Building c = core();
        int modules = 0;
        java.util.IdentityHashMap<ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
        for(Tile t : Vars.world.tiles){
            if(t == null || t.build == null || t.build.items == null) continue;
            if(seen.put(t.build.items, Boolean.TRUE) == null) modules++;
        }
        int builds = 0;
        for(Tile t : Vars.world.tiles) if(t != null && t.build != null) builds++;
        String s = "[RP] " + tag + " 核心=" + (c == null ? "无" : c.block.name)
            + " 世界总量=" + worldTotal() + " 独立物品模块=" + modules + " 建筑数=" + builds
            + "\n[RP]   核心物品: " + coreItems();
        System.out.println(s);
        return s;
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

        Fi file = Core.files.absolute(savePath);
        System.out.println("[RP] 存档=" + file.absolutePath() + " 存在=" + file.exists());
        if(!file.exists()) System.exit(3);
        SaveIO.load(file);
        run(180);
        System.out.println("[RP] 地图=" + Vars.state.map.name() + " 规则sector=" + (Vars.state.rules.sector == null ? "null" : Vars.state.rules.sector.name()));
        snap("读档后");

        // 暂停，隔离"生产/消耗"带来的变化，只看存读档本身
        Vars.state.set(mindustry.core.GameState.State.paused);
        int a = worldTotal();
        run(60);
        int b = worldTotal();
        System.out.println("[RP] 对照组（暂停跑60tick）: " + a + " → " + b);
        int before = b;

        SaveIO.save(Core.files.absolute("/tmp/cl/rep1.msav"));
        SaveIO.load(Core.files.absolute("/tmp/cl/rep1.msav"));
        run(120);
        int after1 = worldTotal();
        System.out.println("[RP] 存读一次: " + before + " → " + after1 + "（差 " + (after1 - before) + "）");
        snap("存读一次后");

        SaveIO.save(Core.files.absolute("/tmp/cl/rep2.msav"));
        SaveIO.load(Core.files.absolute("/tmp/cl/rep2.msav"));
        run(120);
        int after2 = worldTotal();
        System.out.println("[RP] 再存读一次: " + after1 + " → " + after2 + "（差 " + (after2 - after1) + "）");
        snap("再存读一次后");

        System.out.println("[RP] 结论: " + (after2 > before ? "物品变多了（复现）" : "物品没变多")
            + "  " + before + " → " + after1 + " → " + after2);
        System.exit(0);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
