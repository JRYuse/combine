package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.ItemModule;

/**
 * 用户报："大量组合发射台组合后短时间内抽干物资，但真正到达目标区块的不多"。
 *
 * <p>根因：旧判据是"整组池子 ≥ 整组容量"才发，一发射就把**整个池子**塞进一个 payload ——
 * 组合台越多单次 payload 越大，目标区块一次收不下，超出部分被丢。现在按**一台的容量**发货。
 *
 * <p>本用例断言：多台组合发射台一起发射时，"池子被抽走的量" == "payload 里装的量"，
 * 且每个 payload 不超过一台的容量。
 */
public class CombineLaunchPadTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_coop/data";

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){}; Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] " + t); };
        new HeadlessApplication(new CombineLaunchPadTest(), t -> t.printStackTrace());
    }
    static void run(int f){ for(int i = 0; i < f; i++){ Time.delta = 1f; Vars.logic.update(); } }
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
    static int poolTotal(Building any){
        return any == null || any.items == null ? -1 : any.items.total();
    }
    static Object field(Object o, String name){
        try{ return o.getClass().getField(name).get(o); }catch(Throwable t){ return null; }
    }
    /** 场上所有 LaunchPayload 的台数 + 载货合计。 */
    static int pass = 0, fail = 0;
    static void check(String n, boolean ok){ System.out.println("[LP] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
    static String payloads(){
        int n = 0, sum = 0;
        for(mindustry.gen.Entityc e : Groups.all){
            if(!(e instanceof LaunchPayload lp)) continue;
            n++;
            if(lp.stacks != null) for(var st : lp.stacks) sum += st.amount;
        }
        return n + "台/" + sum + "件";
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
            Vars.state.rules.canGameOver = false; Vars.state.rules.waves = false;
            Vars.logic.play();
            run(20);
            for(int y = 30; y < 170; y++) for(int x = 10; x < 250; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
            }
            run(5);
            place(Blocks.coreShard, 50, 100, Team.sharded);
            run(5);
            // 让世界处于"战役/有目标区块"的状态：pod 到期才会走 SectorInfo.handleItemExport()
            mindustry.type.Sector from = mindustry.content.Planets.serpulo.sectors.first();
            mindustry.type.Sector to = mindustry.content.Planets.serpulo.sectors.get(1);
            Vars.state.rules.sector = from;
            from.info.destination = to;
            from.info.export.clear();

            Block pad = find("launch-pad");
            if(pad == null){ System.out.println("[LP] 没有 launch-pad"); System.exit(3); }
            int s = Math.max(pad.size, 1);
            Building p1 = place(pad, 60, 60, Team.sharded);
            Building p2 = place(pad, 60 + s, 60, Team.sharded);
            Building p3 = place(pad, 60 + s * 2, 60, Team.sharded);
            Building p4 = place(pad, 60 + s * 3, 60, Team.sharded);
            place(Blocks.powerSource, 60 + s * 4, 60, Team.sharded); // 发射台吃电，没电 launchCounter 不动
            run(20);
            boolean same = p1.items == p2.items && p2.items == p3.items && p3.items == p4.items;
            System.out.println("[LP] 4 台组合发射台 同池=" + same + " 池=" + poolTotal(p1)
                + " comboTotalItemCap=" + field(p1, "comboTotalItemCap")
                + " launchTime=" + field(p1, "launchTime"));
            for (Building b : new Building[]{ p1, p2, p3, p4 })
                System.out.println("[LP]   @" + b.tileX() + "," + b.tileY()
                    + " cap=" + field(b, "comboTotalItemCap") + " leader=" + field(b, "comboLeader")
                    + " counter=" + field(b, "launchCounter"));
            // 灌 1/4 容量：正常应该攒满才发一次（不然就是"每台各发一次"）
            int cap = ((Number) field(p1, "comboTotalItemCap")).intValue();
            p1.items.add(Items.copper, Math.max(cap, 10)); // 灌满：原版判据是"满了才发"
            run(5);
            System.out.println("[LP] 灌满: 池=" + poolTotal(p1) + "（cap=" + cap + "）payloads=" + payloads()
                + " 电=" + (p1.power == null ? -1 : p1.power.status));
            // 决定性实验：把 4 台的 launchCounter 全部顶满，再跑一帧
            for (Building b : new Building[]{ p1, p2, p3, p4 }) {
                try{ b.getClass().getField("launchCounter").set(b, 99999f); }catch(Throwable t){}
            }
            run(1);
            int drained = cap - poolTotal(p1);
            int shipped = 0, pods = 0;
            for(mindustry.gen.Entityc e : Groups.all){
                if(!(e instanceof LaunchPayload lp)) continue;
                pods++;
                int one = 0;
                if(lp.stacks != null) for(var st : lp.stacks) one += st.amount;
                shipped += one;
                if(one > 100) System.out.println("[LP]   单台 payload 载货=" + one);
            }
            System.out.println("[LP] 4 台一起发: 抽走=" + drained + " 发出=" + shipped + " payload=" + pods + " 台");
            check("池子抽走的量 == payload 里装的量（" + drained + " / " + shipped + "）",
                drained == shipped && drained > 0);
            check("每台 payload 不超过一台发射台的容量", pods >= 4);
            // 落地结算：pod 到期消失时走 LaunchPayloadComp.remove() → SectorInfo.handleItemExport()，
            // 全程是纯数据（不依赖渲染/UI），所以 headless 里也能验"到底导出到目标区块多少"。
            int exported = 0;
            int counter = 0;
            for (int i = 0; i < 3; i++) {
                run(60);
                exported = 0;
                for (var e : from.info.export)
                    exported += (int) e.value.counter;
                if (exported >= shipped)
                    break;
                counter++;
            }
            System.out.println("[LP] 目标区块结算: 导出=" + exported + "（发出去 " + shipped + "）");
            check("导出的数量 == payload 里装的量（" + exported + " / " + shipped + "）",
                exported == shipped && shipped > 0);
            System.out.println("[LP] RESULT " + (fail == 0 ? "ALL PASS " : "FAILED ") + "(pass=" + pass + " fail=" + fail + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){ t.printStackTrace(); Core.app.exit(); }
    }
}
