package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.ObjectSet; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.Item; import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.ItemModule;

/**
 * 用户报："组合节点瞎连（随机连接任意组合建筑再断开）会导致异常的物品增长和减少，
 * 有增长到 11m 的，还有减少到负数的"（含 js/java 扩展建筑）。
 *
 * <p>真联机里已复现到一次：服务端"节点连到装着 5000 铜的容器"那一步，
 * 世界物品总量从 2004 跳到 7042 —— 正好多出一份容器里的铜。
 *
 * <p>这个测试把那个场景缩到最小：**不和核心相连的独立组合体**（容器/工厂）身上带物品，
 * 用组合节点反复连/断，每一步都核对"按模块身份去重的世界物品总量"必须一分不差。
 * 物品守恒模糊测试（{@code ItemConservationFuzz}）只给**核心**灌物品（核心池有专门的
 * 不拆分支），所以从来没走到这条路上。
 */
public class NodePoolConserveTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_cj/data";
    static int pass = 0, fail = 0;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){}; Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] " + t); };
        new HeadlessApplication(new NodePoolConserveTest(), t -> t.printStackTrace());
    }
    static void check(String n, boolean ok){ System.out.println("[NPC] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i = 0; i < f; i++){ Time.delta = 1f; Vars.logic.update(); } }

    static Block byName(String name){
        for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b;
        for(Block b : Vars.content.blocks())
            if(b.name.endsWith("-" + name) && !b.getClass().getName().startsWith("mindustry.")) return b;
        return null;
    }
    static Block nodeBlock(){
        for(Block b : Vars.content.blocks()) if(b.getClass().getName().contains("ComboNode")) return b;
        return null;
    }
    static Building place(Block b, int x, int y){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null, b, Team.sharded, ax, ay, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte)0, Team.sharded, null);
        Building bu = Vars.world.build(ax, ay);
        if(bu != null){
            try{ bu.created(); }catch(Throwable ignored){}
            try{ bu.updateProximity(); }catch(Throwable ignored){}
        }
        return bu;
    }
    /** 世界上所有建筑（按世界格遍历：headless 下 Groups.build 不一定全）。 */
    static Seq<Building> allBuildings(){
        Seq<Building> out = new Seq<>();
        ObjectSet<Building> seen = new ObjectSet<>();
        for(Tile t : Vars.world.tiles){
            Building b = t == null ? null : t.build;
            if(b != null && seen.add(b)) out.add(b);
        }
        return out;
    }
    /** 按模块身份去重的世界物品总量（共享池只算一次）。 */
    static int total(){
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        int t = 0;
        for(Building b : allBuildings()){
            if(b == null || b.items == null || seen.put(b.items, Boolean.TRUE) != null) continue;
            for(Item it : Vars.content.items()) t += b.items.get(it);
        }
        return t;
    }
    /** 逐建筑列出模块身份与数量（排查"哪一份被复制了"）。 */
    static String dump(){
        StringBuilder sb = new StringBuilder();
        java.util.IdentityHashMap<ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
        for(Building b : allBuildings()){
            if(b == null || b.items == null) continue;
            String mark = seen.put(b.items, Boolean.TRUE) == null ? "模块#" + System.identityHashCode(b.items) : "（同上）";
            sb.append("      ").append(b.block.name).append('@').append(b.tileX()).append(',').append(b.tileY())
              .append(' ').append(mark).append(" 铜=").append(b.items.get(Items.copper))
              .append(" 总=").append(b.items.total()).append('\n');
        }
        return sb.toString();
    }

    /** 每一步都要求总量不变；不变才算过。 */
    static int expect;
    static void step(String what, Runnable action){
        action.run();
        run(3);
        int now = total();
        boolean ok = now == expect;
        System.out.println("[NPC] " + (ok ? "PASS " : "FAIL ") + what + "：总量 " + expect + " → " + now
            + (ok ? "" : "（差 " + (now - expect) + "）"));
        if(ok) pass++; else { fail++; System.out.print(dump()); }
    }

    @Override public void init(){
        try{
            Core.settings.setDataDirectory(Core.files.local(dataDir));
            Vars.loadLocales = false; Vars.loadSettings(); Vars.headless = true; Vars.init();
            UI.loadColors(); Fonts.loadContentIconsHeadless();
            Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
            Vars.mods.eachClass(Mod::init);
            if(Vars.logic == null) Vars.logic = new Logic();
            if(Vars.netServer == null) Vars.netServer = new NetServer();
            if(Vars.netClient == null) Vars.netClient = new NetClient();

            Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.waves = false;
            Vars.state.rules.canGameOver = false;
            Vars.logic.play();
            run(20);
            for(int y = 25; y < 200; y++) for(int x = 10; x < 250; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
            }
            run(10);

            int ox = -1, oy = -1;
            outer:
            for(int y = 60; y < 160; y++){
                for(int x = 40; x < 220; x++){
                    boolean ok = true;
                    for(int dy = -4; dy <= 8 && ok; dy++) for(int dx = -2; dx <= 12; dx++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ System.out.println("[NPC] 没找到空地"); System.exit(3); }

            // 核心放得**很远**：容器/工厂都不会并仓，属于"独立组合体"
            Building core = place(Blocks.coreShard, ox - 30, oy - 30);
            run(5);
            Block cont = byName("container"), node = nodeBlock();
            Block fact = byName("graphite-press");
            if(cont == null || node == null || fact == null){
                System.out.println("[NPC] 缺方块（container=" + cont + " node=" + node + " graphite-press=" + fact + "）");
                System.exit(3);
            }
            Building a = place(cont, ox, oy);
            Building b = place(fact, ox + 6, oy);
            Building n = place(node, ox + 3, oy + 4);
            run(10);
            if(a == null || b == null || n == null){ System.out.println("[NPC] 摆放失败"); System.exit(3); }

            a.items.add(Items.copper, 5000);
            run(5);
            expect = total();
            System.out.println("[NPC] 初始：总量=" + expect + "（容器 5000 铜 + 核心自带）");
            check("初始总量 ≥ 5000（容器那 5000 铜确实在账上）", expect >= 5000);

            // 用户报的路径：随机连任意组合建筑，再断开
            step("节点连容器", () -> n.onConfigureBuildTapped(a));
            step("节点连工厂", () -> n.onConfigureBuildTapped(b));
            step("节点断容器", () -> n.onConfigureBuildTapped(a));
            step("节点断工厂", () -> n.onConfigureBuildTapped(b));
            step("再连容器", () -> n.onConfigureBuildTapped(a));
            step("再连工厂", () -> n.onConfigureBuildTapped(b));
            step("点节点自己（全断）", () -> n.onConfigureBuildTapped(n));
            step("连容器（第三次）", () -> n.onConfigureBuildTapped(a));
            step("断开容器（第三次）", () -> n.onConfigureBuildTapped(a));

            System.out.println("[NPC] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){
            t.printStackTrace();
            System.exit(2);
        }
    }
}
