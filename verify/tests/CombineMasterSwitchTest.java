package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.Item; import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.ItemModule;

/**
 * 设置里的「启用组合」总开关（含玩家队伍）：
 *   开着 → 相邻的同种组合建筑并成一个池子；
 *   关掉 → 立刻拆回"每台一份"（物品按容量比例分，总量不变、容量回基础值）；
 *   再打开 → 又并回一个池子，总量仍然不变。
 *
 * <p>用法：verify/run-headless.sh mx /tmp/mp_coop/data combine.dbg.CombineMasterSwitchTest
 */
public class CombineMasterSwitchTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_coop/data";
    static int pass = 0, fail = 0;
    static ClassLoader ml;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){}; Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] " + t); };
        new HeadlessApplication(new CombineMasterSwitchTest(), t -> t.printStackTrace());
    }
    static void check(String n, boolean ok){ System.out.println("[MS] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i = 0; i < f; i++){ arc.util.Time.delta = 1f; Vars.logic.update(); } }
    static Block find(String n){ for(Block b : Vars.content.blocks()) if(b.name.equals(n)) return b; return null; }
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
    static Object call(Class<?> c, String m, Class<?>[] sig, Object... args){
        try{ java.lang.reflect.Method mm = c.getMethod(m, sig); mm.setAccessible(true); return mm.invoke(null, args); }
        catch(Throwable t){ System.out.println("[MS] 调用 " + m + " 失败: " + t); return null; }
    }
    static void setEnabled(Class<?> teams, boolean v){
        call(teams, "setEnabled", new Class<?>[]{boolean.class}, v);
    }
    /** 这台组合建筑当前的"整组物品上限"（并池时会变成整组之和）。 */
    static int comboCap(Building b){
        try{ return ((Number)b.getClass().getField("comboTotalItemCap").get(b)).intValue(); }
        catch(Throwable t){ return -1; }
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
            ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
            Class<?> teams = Class.forName("combine.util.ComboTeams", true, ml);
            Class<?> reflect = Class.forName("combine.util.ComboReflect", true, ml);
            Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false; Vars.state.rules.waves = false;
            Vars.logic.play();
            run(20);
            for(int y = 30; y < 170; y++) for(int x = 10; x < 250; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
            }
            run(5);

            Block press = find("graphite-press");
            int s = Math.max(press.size, 1);
            Building p1 = place(press, 60, 60, Team.sharded);
            Building p2 = place(press, 60 + s, 60, Team.sharded);
            run(20);
            check("（前置）开着时相邻两台并一个池子", p1.items == p2.items);
            p1.items.add(Items.titanium, 20);
            run(5);
            int before = worldItems();
            int capBefore = comboCap(p1);
            System.out.println("[MS] 并池时: 同池=" + (p1.items == p2.items) + " 总量=" + before
                + " 容量=" + capBefore);

            setEnabled(teams, false);
            run(10);
            int capOff = comboCap(p1);
            System.out.println("[MS] 关掉后: p1池=" + (p1.items == null ? -1 : p1.items.total())
                + " p2池=" + (p2.items == null ? -1 : p2.items.total())
                + " 同池=" + (p1.items == p2.items) + " 总量=" + worldItems() + " 容量=" + capOff);
            check("关掉后不再共用池子", p1.items != p2.items);
            check("关掉后物品没丢（" + before + " → " + worldItems() + "）", worldItems() == before);
            check("关掉后容量回到基础值（" + capBefore + " → " + capOff + "）", capOff < capBefore && capOff > 0);

            setEnabled(teams, true);
            run(20);
            System.out.println("[MS] 重新打开: 同池=" + (p1.items == p2.items) + " 总量=" + worldItems());
            check("重新打开后又并回一个池子", p1.items == p2.items);
            check("重新打开后物品总量不变（" + before + " → " + worldItems() + "）", worldItems() == before);

            System.out.println("[MS] RESULT " + (fail == 0 ? "ALL PASS " : "FAILED ") + "(pass=" + pass + " fail=" + fail + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
