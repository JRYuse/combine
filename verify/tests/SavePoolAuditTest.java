package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.ObjectSet; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.Item; import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.ItemModule;

/**
 * 用户存档的"组合池子审计"：读档后把每台组合建筑所在**分量**的池子、容量、共享关系全打出来，
 * 用来回答"视频里面板显示的百万级数字到底是池子真被算岔了，还是池子本来就并到了整张基地"。
 *
 * <p>判据：
 * <ul>
 * <li>世界物品总量（按模块身份去重）必须等于"所有池子之和" —— 不能出现同一份库存被算两遍；</li>
 * <li>面板口径（{@link #panelItemPool} + {@link #panelItemCap}）下：分子必须 ≤ 分母的若干倍
 *     （允许"满池不清理"造成的超容，但不允许差几个数量级）；</li>
 * <li>不许出现负数。</li>
 * </ul>
 *
 * 用法：{@code verify/run-headless.sh mx /tmp/mp_save/data combine.dbg.SavePoolAuditTest}
 */
public class SavePoolAuditTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_save/data";
    static int pass = 0, fail = 0;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){}; Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] " + t); };
        new HeadlessApplication(new SavePoolAuditTest(), t -> t.printStackTrace());
    }
    static void check(String n, boolean ok){ System.out.println("[SA] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i = 0; i < f; i++){ Time.delta = 1f; Vars.logic.update(); } }
    static ClassLoader ml;
    static Class<?> comboNet(){ try{ return Class.forName("combine.net.ComboNet", true, ml); }catch(Throwable t){ return null; } }
    static ItemModule panelPool(Class<?> net, Building b){
        try{ return (ItemModule)net.getMethod("panelItemPool", Building.class).invoke(null, b); }catch(Throwable t){ return b.items; }
    }
    static int panelCap(Class<?> net, Building b){
        try{ return (Integer)net.getMethod("panelItemCap", Building.class).invoke(null, b); }catch(Throwable t){ return -1; }
    }
    static Seq<Building> allBuildings(){
        Seq<Building> out = new Seq<>();
        ObjectSet<Building> seen = new ObjectSet<>();
        for(Tile t : Vars.world.tiles){
            Building b = t == null ? null : t.build;
            if(b != null && seen.add(b)) out.add(b);
        }
        return out;
    }
    static int worldTotal(){
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        int t = 0;
        for(Building b : allBuildings()){
            if(b.items == null || seen.put(b.items, Boolean.TRUE) != null) continue;
            for(Item it : Vars.content.items()) t += b.items.get(it);
        }
        return t;
    }

    @Override public void init(){
        try{
            Core.settings.setDataDirectory(Core.files.local(dataDir));
            Vars.loadLocales = false; Vars.loadSettings(); Vars.headless = true; Vars.init();
            UI.loadColors(); Fonts.loadContentIconsHeadless();
            Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
            Vars.mods.eachClass(Mod::init);
            ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
            Map fallback = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(fallback, fallback.applyRules(Gamemode.survival));
            if(Vars.logic == null) Vars.logic = new Logic();
            if(Vars.netServer == null) Vars.netServer = new NetServer();
            if(Vars.netClient == null) Vars.netClient = new NetClient();
            Vars.logic.play();
            run(10);

            arc.files.Fi file = null;
            for(arc.files.Fi f : Vars.saveDirectory.list()){
                if(!f.name().endsWith(".msav") || f.name().contains("backup")) continue;
                file = f;
                if(f.name().contains("sector-serpulo-20")) break;
            }
            if(file == null){ System.out.println("[SA] 数据目录里没有存档: " + Vars.saveDirectory.absolutePath()); System.exit(3); }
            System.out.println("[SA] 读档 " + file.name());
            SaveIO.load(file);
            if(Vars.state.isPaused()) Vars.state.set(GameState.State.playing);
            run(60);   // 让读档窗口过去（去重语义、网络重建都跑完）
            System.out.println("[SA] 地图=" + Vars.state.map.name()
                + " 建筑=" + Vars.state.teams.get(Team.sharded).buildings.size
                + " 世界物品总量（按模块身份去重）=" + worldTotal());

            Class<?> net = comboNet();
            if(net == null){ System.out.println("[SA] 找不到 ComboNet"); System.exit(3); }

            // 逐池子打印 + 面板口径核对
            java.util.IdentityHashMap<ItemModule, Building> reps = new java.util.IdentityHashMap<>();
            int overshoot = 0, worstRatio = 0, negative = 0;
            Building worst = null; Item worstItem = null; int worstAmt = 0, worstCap = 0;
            for(Building b : allBuildings()){
                if(b == null || b.items == null || !b.isValid()) continue;
                ItemModule pool = panelPool(net, b);
                if(pool == null) continue;
                if(reps.putIfAbsent(pool, b) == null){
                    int cap = panelCap(net, b);
                    StringBuilder sb = new StringBuilder();
                    int poolTotal = 0;
                    for(Item it : Vars.content.items()){
                        int amt = pool.get(it);
                        if(amt == 0) continue;
                        poolTotal += amt;
                        if(amt < 0){ negative++; }
                        if(cap > 0){
                            int ratio = amt / Math.max(cap, 1);
                            if(ratio > worstRatio){ worstRatio = ratio; worst = b; worstItem = it; worstAmt = amt; worstCap = cap; }
                            if(ratio >= 10) overshoot++;
                        }
                        sb.append(it.name).append('=').append(amt).append(' ');
                    }
                    System.out.println("[SA] 池子@" + b.block.name + "@" + b.tileX() + "," + b.tileY()
                        + " 类=" + b.getClass().getSimpleName() + " 模块=" + System.identityHashCode(pool)
                        + " 该分量容量=" + cap + " 池子合计=" + poolTotal + "  " + sb);
                }
            }
            System.out.println("[SA] 池子数=" + reps.size() + " 超容10倍以上的物品项=" + overshoot
                + " 负数项=" + negative);
            // 打印所有"明显超过容量"的池子（超过 10 倍），方便判断阈值定得合不合理
            Seq<String> bad = new Seq<>();
            ObjectSet<ItemModule> seen2 = new ObjectSet<>();
            for(Building b : allBuildings()){
                if(b == null || b.items == null || !b.isValid() || !seen2.add(b.items)) continue;
                int cap = panelCap(net, b);
                for(Item it : Vars.content.items()){
                    int amt = b.items.get(it);
                    if(cap > 0 && amt > cap * 10) bad.add(b.block.name + "@" + b.tileX() + "," + b.tileY()
                        + " " + it.name + "=" + amt + "/" + cap + "（" + (amt / Math.max(cap, 1)) + " 倍）");
                }
            }
            System.out.println("[SA] 超过容量 10 倍的池子项数=" + bad.size);
            for(int i = 0; i < Math.min(bad.size, 10); i++) System.out.println("[SA]   " + bad.get(i));
            if(worst != null){
                System.out.println("[SA] 最离谱的一笔：" + worst.block.name + "@" + worst.tileX() + "," + worst.tileY()
                    + " 的 " + worstItem.name + "=" + worstAmt + " / 该分量容量 " + worstCap
                    + "（" + worstRatio + " 倍）");
            }
            check("没有负数物品", negative == 0);
            // 面板口径的"分子/分母"必须同源：超容可以有（满池不清理），但不该差几个数量级
            check("面板口径下没有量级离谱的池子（最大 " + worstRatio + " 倍 < 1000）", worstRatio < 1000);

            // 【关键】存→读 往返会不会把池子乘一遍（用户报的"玩着玩着涨到 11m"最可能是这个）
            int initial = worldTotal();
            int t0 = initial;
            for(int cycle = 1; cycle <= 3; cycle++){
                arc.files.Fi tmp = Core.files.absolute("/tmp/cl/savepool-cycle" + cycle + ".msav");
                tmp.parent().mkdirs();
                SaveIO.save(tmp);
                SaveIO.load(tmp);
                if(Vars.state.isPaused()) Vars.state.set(GameState.State.playing);
                run(60);
                int t1 = worldTotal();
                System.out.println("[SA] 第 " + cycle + " 轮 存→读：总量 " + t0 + " → " + t1
                    + (t1 > t0 ? "（涨了 " + (t1 - t0) + "，×" + String.format("%.2f", t1 / (double)Math.max(t0, 1)) + "）" : ""));
                t0 = t1;
            }
            check("存→读往返不会改变物品总量（起 " + initial + "，3 轮后 " + t0 + "）", t0 == initial);

            // 【只跑不打】让这个世界自己跑一段：总量要是自己往上爬，说明有代码在"反复把池子加起来"
            int runStart = worldTotal();
            for(int k = 0; k < 10; k++){
                run(60);
                int now = worldTotal();
                System.out.println("[SA] 空跑 " + ((k + 1) * 60) + " tick 后总量=" + now
                    + (now != runStart ? "（相对起点 " + (now - runStart) + "）" : ""));
            }
            int runEnd = worldTotal();
            check("空跑 600 tick 物品总量不涨（起 " + runStart + "，末 " + runEnd + "）", runEnd == runStart);

            // 【乱连实验】就在这个存档上连/断组合节点，看总量会不会被"乘"
            Seq<Building> nodes = new Seq<>();
            for(Building b : allBuildings())
                if(b != null && b.isValid() && b.getClass().getName().contains("ComboNode")) nodes.add(b);
            System.out.println("[SA] 存档里的组合节点数=" + nodes.size);
            int before = worldTotal();
            int maxDelta = 0;
            for(int i = 0; i < Math.min(nodes.size, 6); i++){
                Building nd = nodes.get(i);
                // 点自己 = 全断
                nd.onConfigureBuildTapped(nd);
                run(10);
                int afterBreak = worldTotal();
                // 再连一台附近的组合建筑
                Building target = null;
                for(Building b : allBuildings()){
                    if(b == null || b == nd || !b.isValid() || b.items == null) continue;
                    if(b.getClass().getName().contains("ComboNode")) continue;
                    if(b.dst(nd) < 200f){ target = b; break; }
                }
                if(target != null) nd.onConfigureBuildTapped(target);
                run(10);
                int now = worldTotal();
                maxDelta = Math.max(maxDelta, Math.abs(now - before));
                System.out.println("[SA] 节点@" + nd.tileX() + "," + nd.tileY()
                    + " 全断后=" + afterBreak + " 再连 " + (target == null ? "无" : target.block.name)
                    + " 后=" + now + "（相对起点 " + (now - before) + "）");
                before = now;
            }
            check("连/断节点不会把物品总量乘出来（最大变化 " + maxDelta + "）", maxDelta <= Math.max(1000, runStart / 100));

            // 【拆/造】"瞎连"通常还伴随拆掉连着线的成员、或旁边新造方块 —— 各来几次看总量
            int base2 = worldTotal();
            int worstGrow = 0;
            Seq<Building> victims = new Seq<>();
            for(Building b : allBuildings()){
                if(b == null || !b.isValid() || b.items == null) continue;
                if(b.getClass().getName().contains("ComboNode")) continue;
                if(b.block.size != 1) continue;
                victims.add(b);
            }
            for(int i = 0; i < Math.min(8, victims.size); i++){
                Building v = victims.get(i);
                int at = worldTotal();
                Vars.world.tile(v.tileX(), v.tileY()).setBlock(Blocks.air);
                run(10);
                int afterBreak = worldTotal();
                System.out.println("[SA] 拆 " + v.block.name + "@" + v.tileX() + "," + v.tileY()
                    + "：总量 " + at + " → " + afterBreak + "（差 " + (afterBreak - at) + "）");
                worstGrow = Math.max(worstGrow, afterBreak - at);
                if(afterBreak - at > 1000) break;
            }
            check("拆掉连线的组合成员不会让总量变多（最大增长 " + worstGrow + "）", worstGrow <= 1000);

            System.out.println("[SA] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){
            t.printStackTrace();
            System.exit(2);
        }
    }
}
