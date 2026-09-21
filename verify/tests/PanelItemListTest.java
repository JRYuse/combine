package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.*; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.Item;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："观测组合建筑物品面板时（看着像）清空所有物资"。
 *
 * 根因：面板只列 {@code cachedItems}（块声明的 {@code ConsumeItems}）。可很多组合工厂/发电机的
 * 输入是**运行期动态**的（燃料按 {@code ConsumeItemFilter} 过滤、配方由 {@code ConsumeItemDynamic}
 * 决定），这些方块 cachedItems 里根本没有那条物品 —— 池子里堆满货，点开面板却一行物品都没有。
 *
 * 这里用一个"燃料是过滤式消耗器"的组合发电机验证：
 *   · cachedItems 里**没有**煤（所以修前面板不会列它）；
 *   · ComboReflect.displayItems(池子, cachedItems) 里**有**煤（修后面板会列出池子里实际有的物品）。
 */
public class PanelItemListTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[PL] "+t); };
        new HeadlessApplication(new PanelItemListTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[PL] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
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
        if(gen == null){ System.out.println("[PL] 缺组合发电机"); System.exit(3); }
        Building b = place(gen, 60, 60, Team.sharded);
        run(20);
        // 灌燃料（游戏里燃料是过滤器消耗器：acceptItem -> ConsumeItemFilter）
        if(b.items != null) b.items.set(Items.coal, 5);
        run(5);

        Seq<Item> declared = new Seq<>();
        try{
            Object v = b.block.getClass().getField("cachedItems").get(b.block);
            if(v instanceof Seq<?> s) for(Object o : s) declared.add((Item)o);
        }catch(Throwable t){ System.out.println("[PL] 读 cachedItems 失败: " + t); }
        Seq<Item> listed = new Seq<>();
        try{
            Class<?> cr = Class.forName("combine.util.ComboReflect", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
            @SuppressWarnings("unchecked")
            Seq<Item> out = (Seq<Item>)cr.getMethod("displayItems", mindustry.world.modules.ItemModule.class, Iterable.class)
                .invoke(null, b.items, declared);
            listed = out;
        }catch(Throwable t){ System.out.println("[PL] 调 displayItems 失败: " + t); }
        StringBuilder ds = new StringBuilder();
        for(Item i : declared) ds.append(i.name).append(' ');
        StringBuilder ls = new StringBuilder();
        for(Item i : listed) ls.append(i.name).append(' ');
        System.out.println("[PL] " + gen.name + ": 池子里煤=" + b.items.get(Items.coal)
            + " cachedItems=[" + ds + "] 面板将列出=[" + ls + "]");

        check("这块发电机的煤是运行期过滤出来的（cachedItems 里没有煤 —— 修前面板就是这么显示成空的）",
            !declared.contains(Items.coal, true));
        check("面板现在会列出池子里实际有的煤（displayItems 兜住了）", listed.contains(Items.coal, true));

        System.out.println("[PL] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
