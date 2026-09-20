package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.ObjectSet; import arc.struct.Queue; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 两阶段：
 *   -Dpf.mode=write → 进图、摆好"协作组合方块 + 组合工厂 + 电源"的电网，存档到 /tmp/cl/fresh.msav
 *   -Dpf.mode=read  → **另起一个进程**直接读这份存档（= 用户说的"读地图"），不做任何"刺激"，
 *                     看组合建筑有没有自己接上电网。
 * 两个阶段必须用同一份数据目录（内容 id 要对得上）。
 */
public class PowerFreshLoadTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static String mode = System.getProperty("pf.mode", "read");
    static String savePath = System.getProperty("pf.save", "/tmp/cl/fresh.msav");
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new PowerFreshLoadTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[FL] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Block findExact(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static Block find(String name){ for(Block b : Vars.content.blocks()) if(b.name.endsWith(name)) return b; return null; }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static boolean hasUpdater(mindustry.world.blocks.power.PowerGraph g){
        for(var e : Groups.powerGraph){
            if(e instanceof mindustry.gen.PowerGraphUpdater u && u.graph == g) return true;
        }
        return false;
    }
    static String snap(Building b){
        if(b == null || b.power == null || b.power.graph == null) return "null";
        return b.block.name + "@" + b.tileX() + "," + b.tileY() + "{g=" + b.power.graph.getID()
            + ",all=" + b.power.graph.all.size + ",prod=" + b.power.graph.getLastPowerProduced()
            + ",status=" + b.power.status + ",updater=" + hasUpdater(b.power.graph) + "}";
    }
    /** 用户视角的判据：这两台是不是"在同一个电网里、并且电网真的在更新"。 */
    static boolean working(Building a, Building b){
        if(a == null || b == null || a.power == null || b.power == null) return false;
        if(a.power.graph == null || b.power.graph == null) return false;
        return a.power.graph == b.power.graph && hasUpdater(a.power.graph)
            && a.power.graph.getLastPowerProduced() > 0f;
    }

    /** 全图电网自洽检查（判据同 PowerFuzzTest.audit）：返回不一致的条目数。 */
    static int auditWorld(){
        Seq<Building> all = new Seq<>();
        ObjectSet<Building> uniq = new ObjectSet<>();
        for(Tile t : Vars.world.tiles){
            Building b = t == null ? null : t.build;
            if(b == null || !b.isValid() || b.power == null || b.power.graph == null || !uniq.add(b)) continue;
            all.add(b);
        }
        ObjectSet<Building> seen = new ObjectSet<>();
        Seq<Building> tmp = new Seq<>();
        int bad = 0;
        for(Building start : all){
            if(seen.contains(start)) continue;
            Seq<Building> comp = new Seq<>();
            Queue<Building> q = new Queue<>();
            q.addLast(start); seen.add(start);
            while(!q.isEmpty()){
                Building cur = q.removeFirst();
                comp.add(cur);
                for(Building nb : cur.getPowerConnections(tmp))
                    if(nb != null && nb.power != null && seen.add(nb)) q.addLast(nb);
            }
            var g = comp.first().power.graph;
            for(Building b : comp){
                if(b.power.graph != g){
                    bad++;
                    if(bad < 6) System.out.println("[FL]   电网不自洽: " + b.block.name + "@" + b.tileX() + "," + b.tileY()
                        + " graph=" + (b.power.graph == null ? "null" : b.power.graph.getID()) + " 应为=" + g.getID());
                }
            }
            if(comp.size >= 2 && !hasUpdater(g)){
                bad++;
                if(bad < 6) System.out.println("[FL]   分量没有 updater: " + comp.first().block.name + "@" + comp.first().tileX() + "," + comp.first().tileY());
            }
        }
        return bad;
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

        Block src = findExact("power-source");
        Block smelter = findExact("silicon-smelter");
        ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        Class<?> coop = Class.forName("combine.coop.CoopCombo", true, ml);
        java.lang.reflect.Method eligible = coop.getMethod("eligible", Block.class);
        Block pick = null;
        for(Block b : Vars.content.blocks()){
            if(!(Boolean) eligible.invoke(null, b)) continue;
            if(!b.hasPower || b.conductivePower || b.getClass().getName().startsWith("mindustry.")) continue;
            if(pick == null || b.size < pick.size) pick = b;
        }
        if(src == null || smelter == null || pick == null){ System.out.println("[FL] 缺方块 src=" + src + " smelter=" + smelter + " pick=" + pick); System.exit(3); }
        int sz = Math.max(pick.size, 1);

        if(mode.equals("write")){
            for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            run(5);
            Building a = place(pick, 60, 60, Team.sharded);
            Building b = place(pick, 60 + sz, 60, Team.sharded);
            Building c = place(src, 60 + sz * 2, 60, Team.sharded);
            // 另一处：组合工厂 + 电源
            Building d = place(smelter, 60, 90, Team.sharded);
            Building e = place(src, 62, 90, Team.sharded);

            // 再堆一个"像真存档"的密集混合基地：组合工厂群 + 协作组合方块 + 组合节点/连接器 + 原版电源
            java.util.Random rnd = new java.util.Random(Long.getLong("pf.seed", 7L));
            Block conn = null, nodeBlk = null;
            for(Block blk : Vars.content.blocks()){
                if(blk.getClass().getName().equals("combine.net.ComboConnector")) conn = blk;
                if(blk.getClass().getName().equals("combine.net.ComboNode")) nodeBlk = blk;
            }
            Block[] pool = {smelter, findExact("phase-weaver"), findExact("cryofluid-mixer"), findExact("pulverizer"),
                            pick, src, findExact("battery"), findExact("power-node"), conn, nodeBlk};
            int placed = 0;
            for(int i = 0; i < 80; i++){
                Block blk = pool[rnd.nextInt(pool.length)];
                if(blk == null) continue;
                int x = 80 + rnd.nextInt(24), y = 70 + rnd.nextInt(24);
                if(place(blk, x, y, Team.sharded) != null) placed++;
                if(i % 7 == 0) run(2);
            }
            run(40);
            // 随机拆掉几台（真存档里也常是这样留下的）
            for(int i = 0; i < 12; i++){
                int x = 80 + rnd.nextInt(24), y = 70 + rnd.nextInt(24);
                Building t = Vars.world.build(x, y);
                if(t != null && t.power != null) t.tile.setBlock(Blocks.air);
                if(i % 4 == 0) run(2);
            }
            run(60);
            System.out.println("[FL] 密集基地放置了 " + placed + " 台方块");
            System.out.println("[FL] 写存档前: 模组方块组 " + snap(a) + " | " + snap(b) + " 电源 " + snap(c));
            System.out.println("[FL] 写存档前: 组合工厂 " + snap(d) + " 电源 " + snap(e));
            check("写档前：两台模组方块 + 电源在同一张活电网", working(a, c) && working(b, c));
            check("写档前：组合工厂 + 电源在同一张活电网", working(d, e));
            check("写档前：整张图的电网自洽", auditWorld() == 0);
            SaveIO.save(Core.files.absolute(savePath));
            System.out.println("[FL] 已写 " + savePath);
            System.out.println("[FL] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")));
            System.exit(fail==0?0:1);
            return;
        }

        // read 阶段：另起进程直接读档（= 用户"读地图"），不碰世界
        SaveIO.load(Core.files.absolute(savePath));
        Vars.state.set(mindustry.core.GameState.State.playing);
        run(60);
        Building a = Vars.world.build(60, 60), b = Vars.world.build(60 + sz, 60), c = Vars.world.build(60 + sz * 2, 60);
        Building d = Vars.world.build(60, 90), e = Vars.world.build(62, 90);
        System.out.println("[FL] 读档后: 模组方块组 " + snap(a) + " | " + snap(b) + " | 电源 " + snap(c));
        System.out.println("[FL] 读档后: 组合工厂 " + snap(d) + " | 电源 " + snap(e));
        check("读档后：两台模组方块还在同一张电网", a != null && b != null && a.power.graph == b.power.graph);
        check("读档后：电源和模组方块组同一张电网", a != null && c != null && a.power.graph == c.power.graph);
        check("读档后：这张电网真的在更新（有 updater + 有产出）", working(a, c));
        check("读档后：组合工厂 + 电源同一张活电网", working(d, e));
        check("读档后：模组方块真的拿到了电（status>0）", a != null && a.power.status > 0f);
        check("读档后：组合工厂真的拿到了电（status>0）", d != null && d.power.status > 0f);
        int bad = auditWorld();
        check("读档后：整张图的电网自洽（不需要刺激）", bad == 0);
        System.out.println("[FL] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
