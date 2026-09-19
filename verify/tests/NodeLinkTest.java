package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 组合节点（ComboNode）连线开关：
 *   · 点已连接的目标 = 断开那根线（用户报的 bug："连上之后断不开"）
 *   · 再点一次 = 重新连上
 *   · 点同一个组合体的另一台 = 把这一组已有的那根线断开（一个组合体只连一根）
 *   · 点节点自己 = 有连线就全清
 */
public class NodeLinkTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new NodeLinkTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[NL] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Block find(String suffix){ for(Block b : Vars.content.blocks()) if(b.name.endsWith(suffix)) return b; return null; }
    static Building place(Block b,int x,int y,Team team){
        mindustry.world.Build.beginPlace(null,b,team,x,y,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(x,y),b,null,(byte)0,team,null);
        return Vars.world.build(x,y); }

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
        Block nodeBlock = null;
        for(Block b : Vars.content.blocks()){
            if(b.getClass().getName().equals("combine.net.ComboNode")){ nodeBlock = b; break; }
        }
        if(prod == null || nodeBlock == null){ System.out.println("[NL] 缺方块: prod=" + prod + " node=" + nodeBlock); System.exit(3); }
        int sz = Math.max(prod.size, 1);
        // 清场
        for(int y=40;y<140;y++) for(int x=20;x<220;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(3);

        // 两个"分开的组合体"：A 在 60/61，B 在 70/71，节点放中间
        Building a1 = place(prod, 60, 60, Team.sharded);
        Building a2 = place(prod, 60 + sz, 60, Team.sharded);
        Building b1 = place(prod, 70, 60, Team.sharded);
        Building b2 = place(prod, 70 + sz, 60, Team.sharded);
        run(20);
        Building node = place(nodeBlock, 65, 60, Team.sharded);
        run(20);
        if(node == null){ System.out.println("[NL] 节点没放上"); System.exit(3); }
        System.out.println("[NL] 节点类=" + node.getClass().getName());
        java.lang.reflect.Field lf = node.getClass().getDeclaredField("links");
        lf.setAccessible(true);
        arc.struct.IntSeq links = (arc.struct.IntSeq) lf.get(node);
        System.out.println("[NL] 放好后 节点 links=" + links.size + " (应为 0：不再自动连线)   A池=" + (a1.items == a2.items) + " B池=" + (b1.items == b2.items));
        System.out.println("[NL] 两个组合体现在是不是同一个池（节点把它们接起来了）: " + (a1.items == b1.items));
        check("节点放下后**不**自动连线（links=0）", links.size == 0);
        check("没连线时两边各自独立（不同池）", a1.items != b1.items);

        // —— 用户要求：不自动连接，改成自己点目标 ——
        node.onConfigureBuildTapped(a1);
        run(6);
        node.onConfigureBuildTapped(b1);
        run(10);
        System.out.println("[NL] 手动点 A、B 之后: links=" + links.size + " 同池=" + (a1.items == b1.items));
        check("手动点两个组合体后连上（2 根线）", links.size == 2);
        check("连上后两边同一个池", a1.items == b1.items);

        // —— 用户报的 bug：点已连接的目标，应该断开 ——
        boolean handled = node.onConfigureBuildTapped(a1);
        run(10);
        System.out.println("[NL] 点 A(a1) 之后: handled=" + handled + " links=" + links.size + " 同池=" + (a1.items == b1.items));
        check("点已连接的目标会断开那一根线", links.size == 1);
        check("断开被当作已处理（不会去选中对方方块）", !handled);
        // 用户报的第二个 bug：点 A 却把 B 的线断了 —— 断言剩下的那根必须是 B
        boolean keptB = links.contains(b1.pos());
        boolean droppedA = !links.contains(a1.pos());
        System.out.println("[NL]   剩下的是 B 吗=" + keptB + "  A 的线没了吗=" + droppedA);
        check("断掉的是被点的那一边（点 A 就断 A，不能动 B）", keptB && droppedA);

        // —— 再把最后一根也断开：两边应该真的分成两个组合体（池子拆开）——
        node.onConfigureBuildTapped(b1);
        run(10);
        System.out.println("[NL] 再点 B(b1) 之后: links=" + links.size + " 同池=" + (a1.items == b1.items));
        check("断掉之后连线清空", links.size == 0);
        check("断掉之后两边不再是同一个池（真断开）", a1.items != b1.items);

        // —— 重新连：两个组合体各连一根才算接上 ——
        node.onConfigureBuildTapped(a1);
        run(10);
        System.out.println("[NL] 连回 A: links=" + links.size + " 同池=" + (a1.items == b1.items));
        check("连回一半时只有 1 根线、还没并池", links.size == 1 && a1.items != b1.items);
        node.onConfigureBuildTapped(b1);
        run(10);
        System.out.println("[NL] 连回 B: links=" + links.size + " 同池=" + (a1.items == b1.items));
        check("两边各连一根后又并成一个组合体", links.size == 2 && a1.items == b1.items);

        // —— 点同一组合体的另一台：认的是"这一组"，应该是断开而不是又加一根 ——
        node.onConfigureBuildTapped(a2);
        run(10);
        System.out.println("[NL] 点同组的 A(a2) 之后: links=" + links.size + " 同池=" + (a1.items == b1.items));
        check("点同一组合体的另一台＝断开该组那根线（不会加第二根）", links.size == 1 && a1.items != b1.items);

        // —— 点节点自己：有连线就全清 ——
        node.onConfigureBuildTapped(a1);   // 先接回来
        run(10);
        node.onConfigureBuildTapped(node);
        run(10);
        System.out.println("[NL] 点节点自己之后: links=" + links.size + " 同池=" + (a1.items == b1.items));
        check("点节点自己会清空全部连线并拆开组合体", links.size == 0 && a1.items != b1.items);

        // —— 三个组合体都连上（3 根线），点其中一个只能断它自己那根 ——
        Building c1 = place(prod, 80, 60, Team.sharded);
        Building c2 = place(prod, 80 + sz, 60, Team.sharded);
        run(20);
        node.onConfigureBuildTapped(a1);
        run(6);
        node.onConfigureBuildTapped(b1);
        run(6);
        node.onConfigureBuildTapped(c1);
        run(10);
        System.out.println("[NL] 三个组合体: links=" + links.size + " A/B/C 全连="
            + (links.contains(a1.pos()) && links.contains(b1.pos()) && links.contains(c1.pos())));
        check("三个组合体各连一根（3 根线）", links.size == 3 && links.contains(c1.pos()));
        node.onConfigureBuildTapped(a1);
        run(10);
        System.out.println("[NL] 三个里点 A: links=" + links.size
            + "  B还在=" + links.contains(b1.pos()) + " C还在=" + links.contains(c1.pos()) + " A没了=" + !links.contains(a1.pos()));
        check("三个里点 A 只断 A，B/C 的线都还在",
            links.size == 2 && links.contains(b1.pos()) && links.contains(c1.pos()) && !links.contains(a1.pos()));

        System.out.println("[NL] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
