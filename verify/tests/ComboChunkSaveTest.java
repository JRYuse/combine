package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.files.Fi; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 自定义存档块（combine-combo-state）真的在搬运"模组专属字段"：
 * 存读档后发电机选中的燃料、炮塔选中的弹药、组合墙的 breakTimer 都得还在，
 * 组合体（两台相邻组合工厂）也得还是同一个池子（物品总量一分不差）。
 *
 * 这些字段以前是直接续写在地图区里的（所以关掉模组会坏档），现在挪进自定义块，
 * 这个测试就是防"挪过去之后忘了读回来"。
 */
public class ComboChunkSaveTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static final String FILE="/tmp/cl/chunk.msav";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new ComboChunkSaveTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[CS] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){
            try{ bu.created(); }catch(Throwable ignored){}
            try{ bu.updateProximity(); }catch(Throwable ignored){}
        }
        return bu; }
    static Block find(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static Object field(Building b, String name){
        try{ return b.getClass().getField(name).get(b); }catch(Throwable t){ return null; } }
    static boolean setField(Building b, String name, Object v){
        try{ b.getClass().getField(name).set(b, v); return true; }catch(Throwable t){ return false; } }
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

        Block core = Blocks.coreShard, gen = find("combustion-generator"), turret = find("duo"),
            wall = find("copper-wall"), press = find("graphite-press");
        System.out.println("[CS] 方块: gen=" + gen + " turret=" + turret + " wall=" + wall + " press=" + press);

        Building c = place(core, 60, 60, Team.sharded);
        run(5);
        Building g = place(gen, 70, 60, Team.sharded);
        Building tu = place(turret, 76, 60, Team.sharded);
        Building w = place(wall, 82, 60, Team.sharded);
        Building p1 = place(press, 86, 60, Team.sharded);
        Building p2 = place(press, 88, 60, Team.sharded);
        run(30);
        if(g == null || tu == null || w == null || p1 == null || p2 == null){ System.out.println("[CS] 没放全"); System.exit(3); }

        // 模组专属状态
        check("设置发电机选中燃料", setField(g, "selectedFuel", Items.coal));
        check("设置炮塔选中弹药", setField(tu, "selected", Items.copper));
        check("设置组合墙 breakTimer", setField(w, "breakTimer", 100f));
        if(c != null){ c.items.add(Items.copper, 500); }
        p1.items.add(Items.titanium, 200);
        run(10);

        int itemsBefore = worldItems();
        boolean samePool = p1.items == p2.items;
        System.out.println("[CS] 存前: 物品总量=" + itemsBefore + " 两台工厂同池=" + samePool);
        System.out.println("[CS] 存前 P1 leader=" + field(p1, "comboLeader") + " pending=" + field(p1, "pendingLeaderPos")
            + " | P2 leader=" + field(p2, "comboLeader") + " pending=" + field(p2, "pendingLeaderPos"));
        check("两台相邻组合工厂共用一个池子", samePool);

        Fi file = Core.files.absolute(FILE);
        SaveIO.save(file);
        // 存档整包是 deflate 压过的，要解开再找块名
        byte[] plain;
        try(var in = new java.util.zip.InflaterInputStream(file.read(8192))){
            plain = in.readAllBytes();
        }
        String raw = new String(plain, java.nio.charset.StandardCharsets.ISO_8859_1);
        boolean hasChunk = raw.contains("combine-combo-state");
        System.out.println("[CS] 存档大小=" + file.length() + " 含自定义块=" + hasChunk);
        check("存档里有 combine-combo-state 自定义块", hasChunk);

        SaveIO.load(file);
        run(60);
        Building g2 = Vars.world.build(70 + (gen.size - 1) / 2, 60 + (gen.size - 1) / 2);
        Building t2 = Vars.world.build(76 + (turret.size - 1) / 2, 60 + (turret.size - 1) / 2);
        Building w2 = Vars.world.build(82, 60);
        Building q1 = Vars.world.build(86 + (press.size - 1) / 2, 60 + (press.size - 1) / 2);
        Building q2 = Vars.world.build(88 + (press.size - 1) / 2, 60 + (press.size - 1) / 2);

        check("读档后发电机还在", g2 != null);
        check("读档后炮塔还在", t2 != null);
        check("读档后组合墙还在", w2 != null);
        check("读档后两台工厂还在", q1 != null && q2 != null);
        if(g2 != null) check("发电机选中的燃料还在（" + field(g2, "selectedFuel") + "）", field(g2, "selectedFuel") == Items.coal);
        if(t2 != null) check("炮塔选中的弹药还在（" + field(t2, "selected") + "）", field(t2, "selected") == Items.copper);
        if(w2 != null){
            Object bt = field(w2, "breakTimer");
            check("组合墙 breakTimer 还在（" + bt + "）", bt instanceof Float f && f > 30f);
        }
        if(q1 != null && q2 != null){
            System.out.println("[CS] P1 items=" + q1.items.total() + " P2 items=" + q2.items.total()
                + " 同实例=" + (q1.items == q2.items) + " P1.liquids同=" + (q1.liquids == q2.liquids));
            check("读档后两台工厂还是同一个池子", q1.items == q2.items);
        }
        int itemsAfter = worldItems();
        System.out.println("[CS] 读后: 物品总量=" + itemsAfter);
        check("存读档物品总量不变（" + itemsBefore + " → " + itemsAfter + "）", itemsAfter == itemsBefore);

        System.out.println("[CS] 结果: pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 ? 0 : 1);
      }catch(Throwable t){ t.printStackTrace(); System.out.println("[CS] 崩了: " + t); System.exit(2); }
    }
}
