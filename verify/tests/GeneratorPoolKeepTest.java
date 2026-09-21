package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.*; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.ItemModule;
import java.util.IdentityHashMap;

/**
 * 用户报：组合工厂"物资满后有时也会莫名其妙清空物品"。
 *
 * 根因：组合发电机在自己的 rebuildCombo 里按"这次算出来的组容量"截断池子
 * （{@code if (amt > totalItemCap) remove(amt - totalItemCap)}）。而验收上限本来就是
 * 组容量（acceptItem 里 {@code items.get(item) < getMaximumAccepted}），池子"每种都能装满一份"，
 * 总量天然等于/超过组容量；只要组变小（拆掉一台成员、或周围方块变化触发一次重建），
 * 超出新容量的那份就被真删掉。
 *
 * 这个测试：4 台相邻的组合发电机共享一口池子 → 灌到组容量 → 拆掉一台成员
 *   · 拆前后世界物品总量必须守恒（容量只拦"新物品进入"，不销毁存量）；
 *   · 池子里的燃料不能少（超容保留）。
 */
public class GeneratorPoolKeepTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new GeneratorPoolKeepTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[GP] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static int worldTotal(){
        IdentityHashMap<ItemModule, Boolean> seen = new IdentityHashMap<>();
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
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        Block gen = null;
        for(Block b : Vars.content.blocks())
            if(b.getClass().getName().equals("combine.production.CombinedGenerator") && b.size == 1 && b.hasItems){ gen = b; break; }
        if(gen == null){ System.out.println("[GP] 缺组合发电机方块"); System.exit(3); }
        System.out.println("[GP] 方块=" + gen.name + " 类=" + gen.getClass().getSimpleName());

        // 4 台并排（互相相邻 → 一组）
        Seq<Building> scene = new Seq<>();
        for(int i = 0; i < 4; i++) scene.add(place(gen, 60 + i, 60, Team.sharded));
        run(30);
        Building main = scene.first();
        int cap = main.getMaximumAccepted(Items.coal);
        System.out.println("[GP] 成组: 组容量=" + cap + " 同池台数=" + samePool(scene) + " 池=" + main.items.total());
        check("4 台相邻的组合发电机共享一口池子", samePool(scene) == 4);
        check("组容量 = 4 × 单台容量", cap == gen.itemCapacity * 4);

        // 灌到"满"（游戏里 acceptItem 就是按这个上限放行的）
        if(main.items != null) main.items.set(Items.coal, cap);
        run(3);
        int coalFull = main.items.get(Items.coal);
        int totalBefore = worldTotal();
        System.out.println("[GP] 灌满后: 煤=" + coalFull + "/" + cap + " 世界总量=" + totalBefore);

        // 拆掉最后一台成员（剩下的成员仍然相邻，整组还连着 → 组容量变小）
        Building victim = scene.peek();
        victim.tile.setBlock(Blocks.air);
        run(30);
        int totalAfter = worldTotal();
        int capAfter = main.getMaximumAccepted(Items.coal);
        int coalAfter = main.items.get(Items.coal);
        System.out.println("[GP] 拆一台后: 组容量=" + capAfter + " 煤=" + coalAfter + " 同池台数=" + samePool(scene) + " 世界总量=" + totalAfter);

        // 发电机烧煤会掉一点，所以判据是"不出现按容量截断式的大掉数"：
        // 原先的写法会把超过新容量的那份直接删掉（这里至少会掉 cap - capAfter = 单台容量那么多）。
        check("拆掉一台成员后池子没被按容量截断（" + coalFull + " → " + coalAfter + "，允许的只是烧掉的燃料）",
            coalAfter > coalFull - gen.itemCapacity);
        check("世界物品总量守恒（" + totalBefore + " → " + totalAfter + "，只允许烧料带来的减少）",
            totalAfter > totalBefore - gen.itemCapacity);

        System.out.println("[GP] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }

    /** 这几台里有多少台用的是同一份物品模块。 */
    static int samePool(Seq<Building> scene){
        if(scene.isEmpty()) return 0;
        ItemModule m = scene.first().items;
        int n = 0;
        for(Building b : scene) if(b != null && b.isValid() && b.items == m) n++;
        return n;
    }
}
