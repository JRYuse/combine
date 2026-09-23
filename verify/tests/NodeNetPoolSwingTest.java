package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.ObjectSet; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.Item; import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.ItemModule;

/**
 * 用户给的现场视频（只有 combine 模组、生存图）里看到的：
 * 一台 **组合钻机 x6** 的面板显示池子里 铜 1482273/60、硅 5555520/60、钛 1831551/60，
 * 而且每帧在"百万级"和"几十"之间反复跳（铜 1482273 → 996335 → 487 → 8 → 44），
 * 而左上角 HUD 显示基地本身只有千级物品 —— 池子被反复合并/拆分导致量级异常。
 *
 * <p>这个测试照着那个现场搭：一坨组合建筑（钻机 + 工厂 + 容器）+ 组合节点连起来，
 * 灌上物品，然后**逐 tick** 打印"按模块身份去重"的世界物品总量与每个池子的总量。
 * 判据：总量必须守恒（只有造/拆才允许变），而且**相邻帧之间不许出现量级跳变**。
 */
public class NodeNetPoolSwingTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_cj/data";
    static int pass = 0, fail = 0;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){}; Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] " + t); };
        new HeadlessApplication(new NodeNetPoolSwingTest(), t -> t.printStackTrace());
    }
    static void check(String n, boolean ok){ System.out.println("[NSW] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i = 0; i < f; i++){ Time.delta = 1f; Vars.logic.update(); } }
    static Seq<Building> allBuildings(){
        Seq<Building> out = new Seq<>();
        ObjectSet<Building> seen = new ObjectSet<>();
        for(Tile t : Vars.world.tiles){
            Building b = t == null ? null : t.build;
            if(b != null && seen.add(b)) out.add(b);
        }
        return out;
    }
    static int total(){
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        int t = 0;
        for(Building b : allBuildings()){
            if(b.items == null || seen.put(b.items, Boolean.TRUE) != null) continue;
            for(Item it : Vars.content.items()) t += b.items.get(it);
        }
        return t;
    }
    static Block byName(String n){
        for(Block b : Vars.content.blocks()) if(b.name.equals(n)) return b;
        for(Block b : Vars.content.blocks())
            if(b.name.endsWith("-" + n) && !b.getClass().getName().startsWith("mindustry.")) return b;
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
    static boolean free(Block b, int x, int y){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        int x0 = ax - (size - 1) / 2, x1 = ax + size / 2, y0 = ay - (size - 1) / 2, y1 = ay + size / 2;
        for(int tx = x0; tx <= x1; tx++) for(int ty = y0; ty <= y1; ty++){
            Tile t = Vars.world.tile(tx, ty);
            if(t == null || t.block() != Blocks.air) return false;
        }
        return true;
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
            for(int y = 60; y < 150; y++){
                for(int x = 40; x < 200; x++){
                    boolean ok = true;
                    for(int dy = -3; dy <= 8 && ok; dy++) for(int dx = -3; dx <= 24; dx++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ System.out.println("[NSW] 没找到空地"); System.exit(3); }

            Building core = place(Blocks.coreShard, ox + 2, oy);
            run(3);
            Block node = nodeBlock();
            Block drill = byName("laser-drill");
            Block cont = byName("container");
            Block fact = byName("graphite-press");
            Block smelter = byName("silicon-smelter");
            if(node == null || drill == null || cont == null){ System.out.println("[NSW] 缺方块"); System.exit(3); }

            // 一坨组合建筑：6 台钻机（模拟"组合钻机 x6"）+ 容器/工厂若干
            Seq<Building> group = new Seq<>();
            for(int i = 0; i < 6; i++){
                Building b = place(drill, ox + 6 + i * 3, oy);
                if(b != null) group.add(b);
            }
            for(int i = 0; i < 3; i++){
                Building b = place(cont, ox + 6 + i * 3, oy + 3);
                if(b != null) group.add(b);
            }
            if(fact != null){ Building b = place(fact, ox + 15, oy + 3); if(b != null) group.add(b); }
            if(smelter != null){ Building b = place(smelter, ox + 17, oy + 3); if(b != null) group.add(b); }
            run(10);
            // 两台节点：一台连回核心（把整张网络并到核心池），一台在组里
            Building n1 = place(node, ox + 4, oy + 3);
            Building n2 = place(node, ox + 12, oy + 6);
            run(10);
            if(n1 == null || n2 == null){ System.out.println("[NSW] 节点摆放失败"); System.exit(3); }

            // 灌物品（模拟基地里已有的库存）
            Item[] items = {Items.copper, Items.lead, Items.graphite, Items.silicon, Items.titanium, Items.sand, Items.coal};
            int injected = 0;
            for(int i = 0; i < group.size; i++){
                Building b = group.get(i);
                if(b.items == null) continue;
                Item it = items[i % items.length];
                int amt = 100 + (i * 37) % 900;
                b.items.add(it, amt);
                injected += amt;
            }
            run(10);
            int before = total();
            System.out.println("[NSW] 摆好 " + group.size + " 台组合建筑 + 2 节点；注入 " + injected
                + "，当前总量=" + before);

            // 让节点连起来（先连核心那台，再连组里那台），逐 tick 观察总量
            int[] seq = new int[0];
            n1.onConfigureBuildTapped(core);
            run(5);
            int afterCore = total();
            System.out.println("[NSW] 节点连核心后：总量=" + afterCore + "（应当仍是 " + before + "）");
            check("连核心不改变物品总量", afterCore == before);

            int prev = afterCore;
            int maxJump = 0;
            StringBuilder trace = new StringBuilder();
            for(int step = 0; step < 40; step++){
                Building target = group.get(step % group.size);
                n2.onConfigureBuildTapped(n2);                 // 先全断
                run(2);
                n2.onConfigureBuildTapped(target);             // 再连一台
                run(2);
                int now = total();
                int jump = Math.abs(now - prev);
                maxJump = Math.max(maxJump, jump);
                trace.append(step).append(":总量=").append(now).append(" ").append(step % 3 == 2 ? "\n" : "");
                prev = now;
                if(jump > Math.max(50, before / 10)){
                    System.out.println("[NSW] *** 第 " + step + " 步总量跳变：" + (now - jump) + " → " + now
                        + "（连的是 " + target.block.name + "@" + target.tileX() + "," + target.tileY() + "）");
                    break;
                }
            }
            System.out.println("[NSW] 40 步里最大跳变=" + maxJump + "（阈值 " + Math.max(50, before / 10) + "）");
            System.out.println("[NSW] 轨迹: " + trace);
            check("反复连/断节点时物品总量不许跳变（视频里是百万级乱跳）",
                maxJump <= Math.max(50, before / 10));
            check("最终总量仍是 " + before + "（实测 " + total() + "）", total() == before);

            // 【视频现场】面板的分子/分母必须同源：组合钻机 x6 显示的是"整张网络共用的池子"，
            // 修前面板的分母却是**这台方块自己**的容量（6 台钻机 = 60）→ 出现"1482273/60"。
            Building drillInGroup = null;
            for(Building b : group) if(b.block == drill){ drillInGroup = b; break; }
            if(drillInGroup != null && drillInGroup.items != null){
                int localCap = Math.max(drillInGroup.block.itemCapacity, 1);
                Class<?> net = Class.forName("combine.net.ComboNet", true, Vars.mods.mainLoader());
                Object poolObj = net.getMethod("panelItemPool", Building.class).invoke(null, drillInGroup);
                ItemModule panelPool = poolObj instanceof ItemModule im ? im : null;
                int poolMax = 0;
                if(panelPool != null) for(Item it : Vars.content.items()) poolMax = Math.max(poolMax, panelPool.get(it));
                Object capObj = net.getMethod("panelItemCap", Building.class).invoke(null, drillInGroup);
                int panelCap = capObj instanceof Integer v ? v : -1;
                System.out.println("[NSW] 面板容量同源检查：本地容量=" + localCap + " 池子里最大一项=" + poolMax
                    + " panelItemCap=" + panelCap);
                System.out.println("[NSW]   （视频现场是 poolMax(" + poolMax + ") 远大于本地容量 " + localCap
                    + "；本用例的合成基地可能还没走到那个状态，所以这里只做信息输出）");
                check("修后分母跟着池子走（panelItemCap ≥ 池子里的量）", panelCap >= poolMax);
            }

            System.out.println("[NSW] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){
            t.printStackTrace();
            System.exit(2);
        }
    }
}
