package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.ObjectSet; import arc.struct.Queue; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 电网体检（用户报：装了 combine 之后每次读写地图都要"刺激"一下电网，
 * 组合建筑才会重新和电网交互）。
 *
 * 判据不看代码怎么写的，只看结果：从每台有电的建筑出发，
 * 按原版 BuildingComp.getPowerConnections 自己重算一遍"应该"属于哪个电网分量，
 * 再看实际 power.graph 指针对不对得上；顺带查"电网分量是不是真的在每帧更新"。
 */
public class PowerGridAuditTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new PowerGridAuditTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[GA] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Block findExact(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static Block find(String name){ for(Block b : Vars.content.blocks()) if(b.name.endsWith(name)) return b; return null; }
    static Block byClass(String cls){ for(Block b : Vars.content.blocks()) if(b.getClass().getName().equals(cls)) return b; return null; }
    static void clearArea(){
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
    }
    static boolean hasUpdater(mindustry.world.blocks.power.PowerGraph g){
        for(var e : Groups.powerGraph){
            if(e instanceof mindustry.gen.PowerGraphUpdater u && u.graph == g) return true;
        }
        return false;
    }
    /** 自己按原版连接规则重算分量，比实际 graph 指针。 */
    static int audit(String tag){
        Seq<Building> all = new Seq<>();
        ObjectSet<Building> uniq = new ObjectSet<>();
        for(Tile t : Vars.world.tiles){
            Building b = t == null ? null : t.build;
            if(b == null || !b.isValid() || b.power == null || !uniq.add(b)) continue;
            all.add(b);
        }
        ObjectSet<Building> seen = new ObjectSet<>();
        int comps = 0, mismatch = 0, deadGraph = 0, prodDead = 0;
        StringBuilder detail = new StringBuilder();
        Seq<Building> tmp = new Seq<>();
        for(Building start : all){
            if(seen.contains(start)) continue;
            comps++;
            Seq<Building> comp = new Seq<>();
            Queue<Building> q = new Queue<>();
            q.addLast(start); seen.add(start);
            while(!q.isEmpty()){
                Building cur = q.removeFirst();
                comp.add(cur);
                for(Building nb : cur.getPowerConnections(tmp)){
                    if(nb != null && nb.power != null && seen.add(nb)) q.addLast(nb);
                }
            }
            mindustry.world.blocks.power.PowerGraph g = comp.first().power.graph;
            boolean updater = hasUpdater(g);
            if(!updater) deadGraph++;
            for(Building b : comp){
                if(b.power.graph != g){
                    mismatch++;
                    if(detail.length() < 700) detail.append(" 期望同网但不同:").append(b.block.name).append('@').append(b.tileX()).append(',').append(b.tileY())
                        .append("(实际graph=").append(b.power.graph == null ? "null" : b.power.graph.getID())
                        .append(",应为=").append(g == null ? "null" : g.getID()).append(')');
                }
            }
            if(g != null && g.producers.size > 0 && g.getLastPowerProduced() <= 0f){
                prodDead++;
                if(detail.length() < 700) detail.append(" 有待发电但产出=0:").append(comp.first().block.name).append('@').append(comp.first().tileX()).append(',').append(comp.first().tileY())
                    .append("(producers=").append(g.producers.size).append(",all=").append(g.all.size).append(')');
            }
        }
        System.out.println("[GA] audit " + tag + ": 有电建筑=" + all.size + " 分量=" + comps
            + " 不同网=" + mismatch + " 分量无updater=" + deadGraph + " 有产不出=" + prodDead + detail);
        return mismatch + deadGraph + prodDead;
    }
    static String snap(Building b){
        if(b == null || b.power == null || b.power.graph == null) return "null";
        return b.block.name + "@" + b.tileX() + "," + b.tileY() + "{g=" + b.power.graph.getID()
            + ",all=" + b.power.graph.all.size + ",prod=" + b.power.graph.getLastPowerProduced()
            + ",status=" + b.power.status + "}";
    }

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

        Block smelter = findExact("silicon-smelter");     // 组合工厂（耗电，2x2）
        Block weaver  = findExact("phase-weaver");       // 另一种组合工厂（耗电，2x2）
        Block gen     = findExact("combustion-generator"); // 组合发电机（烧煤，1x1）
        Block src     = findExact("power-source");       // 测试电源（不耗料）
        Block battery = findExact("battery");
        Block nodeBlk = findExact("power-node");
        Block connBlk = byClass("combine.net.ComboConnector");
        Block coopP   = find("coop-producer");
        Block coopC   = find("coop-consumer");
        System.out.println("[GA] smelter=" + nm(smelter) + " weaver=" + nm(weaver) + " gen=" + nm(gen)
            + " src=" + nm(src) + " battery=" + nm(battery) + " node=" + nm(nodeBlk)
            + " conn=" + nm(connBlk) + " coopP=" + nm(coopP) + " coopC=" + nm(coopC));

        // S1: 两台同类组合工厂 + 电源
        clearArea();
        Building a1 = place(smelter, 60, 60, Team.sharded);
        Building a2 = place(smelter, 62, 60, Team.sharded);
        Building a3 = place(src, 64, 60, Team.sharded);
        run(40);
        int bad = audit("S1 放好");
        check("S1 同类组合工厂 + 电源：电网自洽", bad == 0);
        SaveIO.save(Core.files.absolute("/tmp/cl/ga1.msav"));
        SaveIO.load(Core.files.absolute("/tmp/cl/ga1.msav"));
        Vars.state.set(mindustry.core.GameState.State.playing);
        run(60);
        bad = audit("S1 读档后");
        check("S1 读档后电网自洽（不需要刺激）", bad == 0);
        System.out.println("[GA] S1 读档后 " + snap(Vars.world.build(60,60)) + " | " + snap(Vars.world.build(62,60)) + " | " + snap(Vars.world.build(64,60)));

        // S2: 两台不同组合工厂（跨类型）+ 电源
        clearArea();
        Building b1 = place(smelter, 60, 60, Team.sharded);
        Building b2 = place(weaver, 62, 60, Team.sharded);
        Building b3 = place(src, 64, 60, Team.sharded);
        run(40);
        bad = audit("S2 放好");
        check("S2 跨类型组合工厂 + 电源：电网自洽", bad == 0);
        SaveIO.save(Core.files.absolute("/tmp/cl/ga2.msav"));
        SaveIO.load(Core.files.absolute("/tmp/cl/ga2.msav"));
        Vars.state.set(mindustry.core.GameState.State.playing);
        run(60);
        bad = audit("S2 读档后");
        check("S2 读档后电网自洽", bad == 0);

        // S3: 组合发电机 + 组合工厂（发电机组和耗电组相邻）
        clearArea();
        Building c1 = place(gen, 60, 60, Team.sharded);
        c1.items.add(Items.coal, 10);
        Building c2 = place(gen, 61, 60, Team.sharded);
        c2.items.add(Items.coal, 10);
        Building c3 = place(smelter, 62, 60, Team.sharded);
        run(40);
        bad = audit("S3 放好");
        check("S3 组合发电机 + 组合工厂：电网自洽", bad == 0);
        SaveIO.save(Core.files.absolute("/tmp/cl/ga3.msav"));
        SaveIO.load(Core.files.absolute("/tmp/cl/ga3.msav"));
        Vars.state.set(mindustry.core.GameState.State.playing);
        run(60);
        bad = audit("S3 读档后");
        check("S3 读档后电网自洽", bad == 0);
        System.out.println("[GA] S3 读档后 " + snap(Vars.world.build(60,60)) + " | " + snap(Vars.world.build(62,60)));

        // S4: 组合工厂组靠**原版电力节点**跨距离接电
        clearArea();
        Building d1 = place(smelter, 60, 60, Team.sharded);
        Building d2 = place(smelter, 62, 60, Team.sharded);
        Building d3 = place(nodeBlk, 64, 60, Team.sharded);
        Building d4 = place(nodeBlk, 68, 60, Team.sharded);
        Building d5 = place(src, 70, 60, Team.sharded);
        run(20);
        // 原版电力节点放下时自己是会自动连线的，这里不手动点，走真实路径
        run(40);
        System.out.println("[GA] S4 连线: d3.links=" + d3.power.links.size + " d4.links=" + d4.power.links.size
            + " src.links=" + d5.power.links.size);
        System.out.println("[GA] S4 放好: " + snap(d1) + " | " + snap(d3) + " | " + snap(d5));
        bad = audit("S4 放好");
        check("S4 节点跨距离接电：电网自洽", bad == 0);
        check("S4 电源真的通过节点接上了组合体（同一张电网）",
            d1.power.graph == d5.power.graph && d1.power.graph != null);
        SaveIO.save(Core.files.absolute("/tmp/cl/ga4.msav"));
        SaveIO.load(Core.files.absolute("/tmp/cl/ga4.msav"));
        Vars.state.set(mindustry.core.GameState.State.playing);
        run(60);
        bad = audit("S4 读档后");
        check("S4 读档后电网自洽", bad == 0);
        System.out.println("[GA] S4 读档后 " + snap(Vars.world.build(60,60)) + " | " + snap(Vars.world.build(64,60)) + " | " + snap(Vars.world.build(70,60)));

        // S5: 协作组合（mod 方块，靠 conductivePower 并网）跨类型 + 电源
        if(coopP != null && coopC != null){
            clearArea();
            Building e1 = place(coopP, 60, 90, Team.sharded);
            Building e2 = place(coopC, 62, 90, Team.sharded);
            Building e3 = place(src, 64, 90, Team.sharded);
            run(40);
            bad = audit("S5 放好");
            check("S5 协作组合跨类型 + 电源：电网自洽", bad == 0);
            SaveIO.save(Core.files.absolute("/tmp/cl/ga5.msav"));
            SaveIO.load(Core.files.absolute("/tmp/cl/ga5.msav"));
            Vars.state.set(mindustry.core.GameState.State.playing);
            run(60);
            bad = audit("S5 读档后");
            check("S5 读档后电网自洽", bad == 0);
            System.out.println("[GA] S5 读档后 " + snap(Vars.world.build(60,90)) + " | " + snap(Vars.world.build(62,90)));
        }

        // S6: 协作组合方块 + 组合连接器链 + 远处的组合工厂
        if(coopP != null && connBlk != null){
            clearArea();
            Building f1 = place(coopP, 60, 90, Team.sharded);
            Building f2 = place(coopP, 62, 90, Team.sharded);
            for(int x = 64; x < 70; x++) place(connBlk, x, 90, Team.sharded);
            Building f3 = place(smelter, 70, 90, Team.sharded);
            Building f4 = place(src, 72, 90, Team.sharded);
            run(60);
            System.out.println("[GA] S6 放好: " + snap(f1) + " | " + snap(f3));
            bad = audit("S6 放好");
            check("S6 连接器链把协作组合和组合工厂连成一张电网", bad == 0);
            SaveIO.save(Core.files.absolute("/tmp/cl/ga6.msav"));
            SaveIO.load(Core.files.absolute("/tmp/cl/ga6.msav"));
            Vars.state.set(mindustry.core.GameState.State.playing);
            run(60);
            bad = audit("S6 读档后");
            check("S6 读档后电网自洽", bad == 0);
            System.out.println("[GA] S6 读档后 " + snap(Vars.world.build(60,90)) + " | " + snap(Vars.world.build(70,90)));
        }

        // S7: 组合工厂 + 电池 + 电源
        if(battery != null){
            clearArea();
            Building g1 = place(smelter, 60, 60, Team.sharded);
            Building g2 = place(battery, 62, 60, Team.sharded);
            Building g3 = place(src, 64, 60, Team.sharded);
            run(40);
            bad = audit("S7 放好");
            check("S7 组合工厂 + 电池 + 电源：电网自洽", bad == 0);
            SaveIO.save(Core.files.absolute("/tmp/cl/ga7.msav"));
            SaveIO.load(Core.files.absolute("/tmp/cl/ga7.msav"));
            Vars.state.set(mindustry.core.GameState.State.playing);
            run(60);
            bad = audit("S7 读档后");
            check("S7 读档后电网自洽", bad == 0);
        }

        // S8: 拿**当前数据集里真实模组方块**（有电、需要靠 conductivePower 才并网的那些）放两台 + 电源。
        //     用户报的场景就是这些 js/java 扩展建筑（废土科技/饱和火力那类）。
        try{
            ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
            Class<?> coop = Class.forName("combine.coop.CoopCombo", true, ml);
            java.lang.reflect.Method eligible = coop.getMethod("eligible", Block.class);
            Block pick = null;
            for(Block b : Vars.content.blocks()){
                if(!(Boolean) eligible.invoke(null, b)) continue;
                if(!b.hasPower || b.conductivePower || b.getClass().getName().startsWith("mindustry.")) continue;
                if(pick == null || b.size < pick.size) pick = b;
            }
            if(pick == null){
                System.out.println("[GA] S8 跳过：这套数据里没有「有电但要靠成组才并网」的模组方块");
            }else{
                int sz = Math.max(pick.size, 1);
                System.out.println("[GA] S8 用模组方块: " + pick.name + " class=" + pick.getClass().getName() + " size=" + sz);
                clearArea();
                Building h1 = place(pick, 60, 60, Team.sharded);
                Building h2 = place(pick, 60 + sz, 60, Team.sharded);
                Building h3 = place(src, 60 + sz * 2, 60, Team.sharded);
                run(60);
                System.out.println("[GA] S8 放好: " + snap(h1) + " | " + snap(h2) + " | " + snap(h3));
                bad = audit("S8 放好");
                check("S8 两台同类模组方块 + 电源：电网自洽", bad == 0);
                check("S8 放好：两台模组方块同一张电网", h1 != null && h2 != null && h1.power.graph == h2.power.graph);
                check("S8 放好：电源和它们同一张电网", h1 != null && h3 != null && h1.power.graph == h3.power.graph);
                SaveIO.save(Core.files.absolute("/tmp/cl/ga8.msav"));
                SaveIO.load(Core.files.absolute("/tmp/cl/ga8.msav"));
                Vars.state.set(mindustry.core.GameState.State.playing);
                run(60);
                Building h1b = Vars.world.build(60, 60), h2b = Vars.world.build(60 + sz, 60), h3b = Vars.world.build(60 + sz * 2, 60);
                System.out.println("[GA] S8 读档后: " + snap(h1b) + " | " + snap(h2b) + " | " + snap(h3b));
                bad = audit("S8 读档后");
                check("S8 读档后电网自洽（不需要刺激）", bad == 0);
                check("S8 读档后两台模组方块还在同一张电网",
                    h1b != null && h2b != null && h1b.power.graph == h2b.power.graph);
                check("S8 读档后电源还在同一张电网",
                    h1b != null && h3b != null && h1b.power.graph == h3b.power.graph);
            }
        }catch(Throwable t){ System.out.println("[GA] S8 异常: " + t); t.printStackTrace(); fail++; }

        System.out.println("[GA] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
    static String nm(Block b){ return b == null ? "null" : b.name + "(" + b.getClass().getSimpleName() + ")"; }
}
