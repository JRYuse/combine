package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.ItemModule; import mindustry.type.Item;

/**
 * 读档后的"去重窗口"：读档头几帧里再发生的合并必须按**去重**算（内容相同的副本只留一份），
 * 否则每台建筑手里那份完整副本会被当成真库存相加 —— 物品正好翻倍
 * （用户报的"重新读写后物品增加"：实测核心石墨 48800 → 97600 → 195200）。
 *
 * 这里不用真存档，直接演：
 *  1) 核心 + 容器一组，池子里放 3000 铜；
 *  2) 触发一次读档（SaveIO.load）打开去重窗口；
 *  3) **窗口内**把某台容器的模块换成"同一份内容的副本"（= 读档时每台手里的样子），
 *     再让模组重算 —— 期望：去重，总量还是 3000；
 *  4) 过了窗口之后同样再来一次 —— 期望：这时算真库存，相加成 6000。
 */
public class LoadDedupeWindowTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new LoadDedupeWindowTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[LD] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Block findExact(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static int worldTotal(){
        java.util.IdentityHashMap<ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
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
    /** 让模组重算一次（等价于世界发生了点变化）。 */
    static void pokeModRebuild(){
        try{
            ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
            Class.forName("combine.coop.CoopCombo", true, ml).getMethod("markDirty").invoke(null);
            Class.forName("combine.storage.CombinedStorageBlock", true, ml).getMethod("markDirty").invoke(null);
            Class.forName("combine.net.ComboNet", true, ml).getMethod("markDirty").invoke(null);
        }catch(Throwable t){ System.out.println("[LD] pokeModRebuild failed: " + t); }
    }

    /** 把某台建筑手里的池子换成"内容相同的一份副本"（= 存档里每台各写一份的样子）。 */
    static void makeCopy(Building b){
        ItemModule src = b.items;
        ItemModule copy = new ItemModule();
        for(Item it : Vars.content.items()){
            int n = src.get(it);
            if(n > 0) copy.set(it, n);
        }
        b.items = copy;
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

        Block cont = findExact("container");
        if(cont == null){ System.out.println("[LD] 没容器"); System.exit(3); }
        int sz = Math.max(cont.size, 1);
        Building c = place(Blocks.coreShard, 60, 60, Team.sharded);
        run(10);
        Building s1 = place(cont, 63, 60, Team.sharded);
        Building s2 = place(cont, 63 + sz, 60, Team.sharded);
        run(30);
        c.items.set(Items.copper, 3000);
        run(10);
        System.out.println("[LD] 初始: 同池=" + (s1.items == c.items) + "/" + (s2.items == c.items)
            + " 总量=" + worldTotal() + " 铜=" + c.items.get(Items.copper));
        check("容器和核心共用一个池", s1.items == c.items && s2.items == c.items);

        SaveIO.save(Core.files.absolute("/tmp/cl/dedupw.msav"));
        int before = worldTotal();

        // ---- 窗口内：换成副本 → 合并必须去重 ----
        SaveIO.load(Core.files.absolute("/tmp/cl/dedupw.msav"));
        c = core();
        Building t2 = Vars.world.build(63 + sz, 60);
        if(c == null || t2 == null){ System.out.println("[LD] 读档后找不到核心/容器"); System.exit(3); }
        System.out.println("[LD] 读档后: 总量=" + worldTotal() + " 铜=" + c.items.get(Items.copper));
        makeCopy(t2);                       // 这台手里现在是一份"同一内容的副本"
        pokeModRebuild();
        run(3);                             // 窗口内重算
        System.out.println("[LD] 窗口内换副本后: 总量=" + worldTotal() + " 铜=" + c.items.get(Items.copper));
        check("读档窗口内合并按去重算（不翻倍）", c.items.get(Items.copper) == 3000);

        // ---- 窗口外：同样一次 → 现在是真库存，应该相加 ----
        run(40);                            // 跑过去重窗口（20 帧）
        makeCopy(t2);
        pokeModRebuild();
        run(3);
        System.out.println("[LD] 窗口外换副本后: 总量=" + worldTotal() + " 铜=" + c.items.get(Items.copper));
        check("窗口结束后合并按真库存相加（3000+3000）", worldTotal() == 6000);

        System.out.println("[LD] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
