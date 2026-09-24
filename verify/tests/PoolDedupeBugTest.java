package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.Item; import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.ItemModule;

/**
 * 读档去重合并里的一个漏项 bug（用户报的"物品异常减少"）。
 *
 * <p>{@code ComboNet.mergeDistinctItems} 先在"内容不同的模块"里去重，再用
 * {@code moduleOfFirst(members, unique)} 挑目标（优先核心池，否则 pos 最小的那台），
 * 然后**从下标 1 开始**把剩下的搬进目标：
 * <pre>
 *   ItemModule dst = moduleOfFirst(members, unique);
 *   for(int i = 1; i &lt; unique.size; i++) moveItems(unique.get(i), dst);
 * </pre>
 * 一旦 {@code dst} 不是 {@code unique.get(0)}（成员在列表里的顺序和 pos 顺序不一致就会这样），
 * 第 0 份库存**永远不会被搬走**；紧接着 {@code mergeComponent} 把每个成员的模块都指向 {@code dst}，
 * 那份库存就被静默丢掉了 —— 读档后"物品凭空变少"。
 *
 * <p>这个测试直接用反射调那个私有方法，构造成"目标不是第 0 份"的布局：
 * 两台方块，A 在列表里排第 2、但 pos 更小（会被选成目标），B 排第 1、内容不同（必须被搬进去）。
 * 正确的行为：A 的 100 铜 + B 的 50 铅 都在目标里。
 */
public class PoolDedupeBugTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_cj/data";
    static int pass = 0, fail = 0;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){}; Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] " + t); };
        new HeadlessApplication(new PoolDedupeBugTest(), t -> t.printStackTrace());
    }
    static void check(String n, boolean ok){ System.out.println("[PDB] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i = 0; i < f; i++){ Time.delta = 1f; Vars.logic.update(); } }
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
    static Block byName(String n){
        for(Block b : Vars.content.blocks()) if(b.name.equals(n)) return b;
        for(Block b : Vars.content.blocks())
            if(b.name.endsWith("-" + n) && !b.getClass().getName().startsWith("mindustry.")) return b;
        return null;
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
            Vars.logic.play();
            run(20);
            for(int y = 25; y < 200; y++) for(int x = 10; x < 250; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
            }
            run(10);

            Block cont = byName("container");
            if(cont == null){ System.out.println("[PDB] 找不到容器"); System.exit(3); }
            // A：pos 更小（会被 moduleOfFirst 选成目标），但在 members 列表里排第 2
            Building a = place(cont, 60, 60);
            // B：pos 更大（不会被选成目标），在 members 列表里排第 1
            Building b = place(cont, 70, 60);
            run(10);
            if(a == null || b == null){ System.out.println("[PDB] 摆放失败"); System.exit(3); }
            check("A 的 pos 比 B 小（moduleOfFirst 会挑 A 的模块当目标）", a.pos() < b.pos());

            // 两份**内容不同**的模块：去重后两份都要保留（内容相同的副本才会被去掉）
            a.items = new ItemModule();
            b.items = new ItemModule();
            a.items.add(Items.copper, 100);
            b.items.add(Items.lead, 50);

            // members 顺序 = [B, A]：unique[0] 是 B 的模块，而目标会是 A 的模块
            Seq<Building> members = new Seq<>();
            members.add(b);
            members.add(a);
            Seq<ItemModule> mods = new Seq<>();
            mods.add(b.items);
            mods.add(a.items);

            Class<?> net = Class.forName("combine.net.ComboNet", true, Vars.mods.mainLoader());
            java.lang.reflect.Method m = net.getDeclaredMethod("mergeDistinctItems", Seq.class, Seq.class);
            m.setAccessible(true);
            ItemModule dst = (ItemModule)m.invoke(null, members, mods);
            System.out.println("[PDB] 目标模块 = " + (dst == a.items ? "A 的" : dst == b.items ? "B 的" : "别的")
                + " 铜=" + dst.get(Items.copper) + " 铅=" + dst.get(Items.lead));
            check("合并后目标模块里铜=100（A 那份没被丢）", dst.get(Items.copper) == 100);
            check("合并后目标模块里铅=50（B 那份也搬进来了）", dst.get(Items.lead) == 50);

            System.out.println("[PDB] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){
            t.printStackTrace();
            System.exit(2);
        }
    }
}
