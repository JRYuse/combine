package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.Item; import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.ItemModule;

/**
 * 用户报：「作者~.zip 里的存档，读写一遍之后组合建筑的物品就没了」。
 *
 * <p>根因：写存档时"同一口池子只有一台写真实数据"，选中哪一台以前是**按写盘顺序**
 * （MapIO 先 y 后 x）；读档并池时留下的却是 **pos 最小**（pos = x&lt;&lt;16|y）那一台的模块。
 * 两者不是同一台时，组长读回来的是空模块，真正那份库存被本地并池当"多余副本"丢掉。
 * 所以这里故意摆一个"最小 y 的格子 ≠ 最小 x 的格子"的 L 形组合体，复现那条顺序差。
 *
 * <p>用法：verify/run-headless.sh mx /tmp/mp_coop/data combine.dbg.SavePoolOwnerTest
 */
public class SavePoolOwnerTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_coop/data";
    static final String FILE = "/tmp/cl/poolowner.msav";
    static int pass = 0, fail = 0;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){}; Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> {
            if(l.ordinal() >= arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] " + t);
            else if(System.getProperty("combine.dbg") != null && t.contains("[dbg]")) System.out.println("[D] " + t);
        };
        new HeadlessApplication(new SavePoolOwnerTest(), t -> t.printStackTrace());
    }
    static void check(String n, boolean ok){ System.out.println("[PO] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i = 0; i < f; i++){ Time.delta = 1f; Vars.logic.update(); } }
    static Block find(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static Building place(Block b, int x, int y, Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte)0, team, null);
        Building bu = Vars.world.build(ax, ay);
        if(bu != null){
            try{ bu.created(); }catch(Throwable ignored){}
            try{ bu.updateProximity(); }catch(Throwable ignored){}
        }
        return bu;
    }
    static int worldItems(){
        java.util.IdentityHashMap<ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
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
            Vars.loadLocales = false; Vars.loadSettings(); Vars.headless = true; Vars.init();
            UI.loadColors(); Fonts.loadContentIconsHeadless();
            Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
            Vars.mods.eachClass(Mod::init);
            if(Vars.logic == null) Vars.logic = new Logic();
            if(Vars.netServer == null) Vars.netServer = new NetServer();
            if(Vars.netClient == null) Vars.netClient = new NetClient();
            Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.logic.play();
            run(20);
            for(int y = 30; y < 170; y++) for(int x = 10; x < 250; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
            }
            run(5);

            // 用组合发电机：它的并池循环以前只搬 cachedItems（燃料）还按容量夹，
            // 池子里不在 cachedItems 里的物品（用户存档里就是这种）会被丢掉。
            Block gen = find("combustion-generator");
            if(gen == null){ System.out.println("[PO] 找不到 combustion-generator"); System.exit(3); }
            int s = Math.max(gen.size, 1);
            // L 形：最小 y 的格子 (120,60) 与最小 x 的格子 (120-s,60+s) 不是同一台
            Building a = place(gen, 120, 60, Team.sharded);
            Building b = place(gen, 120, 60 + s, Team.sharded);
            Building c = place(gen, 120 - s, 60 + s, Team.sharded);
            run(20);
            if(a == null || b == null || c == null){ System.out.println("[PO] 没放全"); System.exit(3); }

            boolean same = a.items == b.items && b.items == c.items;
            check("三台相邻组合发电机共用一个池子", same);
            a.items.add(Items.titanium, 20);
            run(10);
            System.out.println("[PO] pos: 120,60=" + a.pos() + " 120,62=" + b.pos() + " 118,62=" + c.pos()
                + " | 池同=" + (a.items == c.items));

            int before = worldItems();
            int beforeTi = a.items.get(Items.titanium);
            System.out.println("[PO] 存前: 总量=" + before + " 钛=" + beforeTi + " 池=" + a.items.total());

            arc.files.Fi file = Core.files.absolute(FILE);
            file.parent().mkdirs();
            SaveIO.save(file);
            SaveIO.load(file);
            if(Vars.state.isPaused()) Vars.state.set(GameState.State.playing);
            run(1);
            int afterTi = 0;
            for(Tile t : Vars.world.tiles){
                if(t == null || t.build == null || t.build.items == null) continue;
                if(Math.abs(t.build.tileX() - 120) <= 4 && Math.abs(t.build.tileY() - 62) <= 4)
                    afterTi = Math.max(afterTi, t.build.items.get(Items.titanium));
            }
            run(59);
            int after = worldItems();
            System.out.println("[PO] 读后: 总量=" + after + "（存前 " + before + "）钛=" + afterTi);
            check("读档后池子里的钛没丢（" + beforeTi + " → " + afterTi + "）", afterTi == beforeTi);
            check("读档后物品总量没少（" + before + " → " + after + "）", after >= before);
            System.out.println("[PO] RESULT " + (fail == 0 ? "ALL PASS " : "FAILED ") + "(pass=" + pass + " fail=" + fail + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
