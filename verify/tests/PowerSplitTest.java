package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用组合节点 / 组合连接器把两个组合体接起来，再断开：**电力也必须跟着断开**。
 * （用户报：断开后电力可能还连着）
 */
public class PowerSplitTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new PowerSplitTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[PW] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Block find(String suffix){ for(Block b : Vars.content.blocks()) if(b.name.endsWith(suffix)) return b; return null; }
    static Building place(Block b,int x,int y,Team team){
        mindustry.world.Build.beginPlace(null,b,team,x,y,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(x,y),b,null,(byte)0,team,null);
        return Vars.world.build(x,y); }
    static boolean sameGrid(Building a, Building b){
        return a != null && b != null && a.power != null && b.power != null
            && a.power.graph != null && a.power.graph == b.power.graph; }
    /** 甲所在的电网里还列着乙吗（真正的"电还能过去"判据，只看 graph 是不是同一个会漏）。 */
    static boolean gridHas(Building a, Building b){
        return a != null && b != null && a.power != null && a.power.graph != null
            && a.power.graph.all.contains(b, true); }

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

        Block prod = find("coop-producer");
        Block nodeBlock = null, connBlock = null;
        for(Block b : Vars.content.blocks()){
            if(b.getClass().getName().equals("combine.net.ComboNode")) nodeBlock = b;
            if(b.getClass().getName().equals("combine.net.ComboConnector")) connBlock = b;
        }
        if(prod == null || nodeBlock == null || connBlock == null){ System.out.println("[PW] 缺方块 " + prod + " " + nodeBlock + " " + connBlock); System.exit(3); }
        int sz = Math.max(prod.size, 1);
        for(int y=40;y<140;y++) for(int x=20;x<240;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(3);

        // ---------- 组合节点 ----------
        Building a1 = place(prod, 60, 60, Team.sharded), a2 = place(prod, 60 + sz, 60, Team.sharded);
        Building b1 = place(prod, 70, 60, Team.sharded), b2 = place(prod, 70 + sz, 60, Team.sharded);
        run(20);
        System.out.println("[PW] 放节点前: A内部同网=" + sameGrid(a1, a2) + " A-B同网=" + sameGrid(a1, b1));
        Building node = place(nodeBlock, 65, 60, Team.sharded);
        run(20);
        System.out.println("[PW] 节点接上后: A-B同网=" + sameGrid(a1, b1) + " A内部=" + sameGrid(a1, a2) + " B内部=" + sameGrid(b1, b2));
        check("节点接上后两边同一个电网", sameGrid(a1, b1));

        java.lang.reflect.Field lf = node.getClass().getDeclaredField("links"); lf.setAccessible(true);
        arc.struct.IntSeq links = (arc.struct.IntSeq) lf.get(node);
        int removed = 0;
        for(int i = links.size - 1; i >= 0; i--){
            Building target = Vars.world.build(links.get(i));
            node.onConfigureBuildTapped(target);
            run(6);
            removed++;
        }
        System.out.println("[PW] 断开 " + removed + " 根线后: links=" + links.size + " A-B同网=" + sameGrid(a1, b1));
        check("节点断开后两边不再是同一个电网", !sameGrid(a1, b1));
        System.out.println("[PW]   A 的电网里还有 B 吗=" + gridHas(a1, b1) + " / B 的电网里还有 A 吗=" + gridHas(b1, a1));
        check("节点断开后 A 的电网里不再列出 B 的机器", !gridHas(a1, b1));
        check("节点断开后 B 的电网里不再列出 A 的机器", !gridHas(b1, a1));
        check("节点断开后各自内部还是一个电网", sameGrid(a1, a2) && sameGrid(b1, b2));

        // ---------- 组合连接器 ----------
        for(int y=40;y<140;y++) for(int x=20;x<240;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
        a1 = place(prod, 60, 60, Team.sharded); a2 = place(prod, 60 + sz, 60, Team.sharded);
        b1 = place(prod, 80, 60, Team.sharded); b2 = place(prod, 80 + sz, 60, Team.sharded);
        run(20);
        // 两个组合体之间铺一条连接器链（62..79）
        Building midLink = null;
        for(int x = 62; x < 80; x++){
            Building c = place(connBlock, x, 60, Team.sharded);
            if(x == 70) midLink = c;
        }
        run(30);
        System.out.println("[PW] 连接器链铺好后: A-B同网=" + sameGrid(a1, b1) + " 同池=" + (a1.items == b1.items));
        check("连接器接上后两边同一个电网", sameGrid(a1, b1));
        boolean linked0 = a1.items == b1.items;
        check("连接器接上后两边同一个物品池", linked0);

        // 拆掉中间一个连接器 = 断开
        if(midLink != null){ midLink.tile.setBlock(Blocks.air); }
        run(40);
        System.out.println("[PW] 拆掉中间连接器后: A-B同网=" + sameGrid(a1, b1) + " 同池=" + (a1.items == b1.items));
        check("连接器链断开后两边不再是同一个电网", !sameGrid(a1, b1));
        check("连接器链断开后 A 的电网里不再列出 B 的机器", !gridHas(a1, b1));
        check("连接器链断开后两边不再是同一个物品池", a1.items != b1.items);

        System.out.println("[PW] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
