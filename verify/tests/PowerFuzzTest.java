package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.ObjectSet; import arc.struct.Queue; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;
import java.util.Random;

/**
 * 电网不变量模糊测试：随机造/拆方块 + 随机存读档，每步都检查
 *   1) 按原版连接规则重算的分量，和实际 power.graph 指针一致；
 *   2) 有 ≥2 台建筑的分量必须有 updater（不然这一张电网永远不更新 —— 用户报的"要刺激一下"）；
 *   3) 有电源的分量必须真的在发电（prod > 0）。
 * 任何一步违反，就打印随机操作历史（可复现）。
 */
public class PowerFuzzTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static long seed = Long.getLong("fz.seed", 12345L);
    static int steps = Integer.getInteger("fz.steps", 400);
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new PowerFuzzTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[FZ] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Block findExact(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static Block byClass(String cls){ for(Block b : Vars.content.blocks()) if(b.getClass().getName().equals(cls)) return b; return null; }
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
    static Seq<Building> powered(){
        Seq<Building> all = new Seq<>();
        ObjectSet<Building> uniq = new ObjectSet<>();
        for(Tile t : Vars.world.tiles){
            Building b = t == null ? null : t.build;
            if(b == null || !b.isValid() || b.power == null || b.power.graph == null || !uniq.add(b)) continue;
            all.add(b);
        }
        return all;
    }
    /** 返回第一个违反不变量的描述，null = 一切正常。 */
    static String audit(){
        Seq<Building> all = powered();
        ObjectSet<Building> seen = new ObjectSet<>();
        Seq<Building> tmp = new Seq<>();
        for(Building start : all){
            if(seen.contains(start)) continue;
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
            for(Building b : comp){
                if(b.power.graph != g){
                    return "同一连通分量里的机器不在同一张电网: " + b.block.name + "@" + b.tileX() + "," + b.tileY()
                        + " (graph=" + b.power.graph.getID() + " 应为=" + g.getID() + "), 分量=" + names(comp);
                }
            }
            if(comp.size >= 2 && !hasUpdater(g)){
                return "分量(" + comp.size + " 台)没有 updater（这张电网永远不更新）: " + names(comp);
            }
            // 只拿 power-source（不用燃料、恒定发电）当判据：接上了别的东西却产出 0 = 这张电网没在更新。
            boolean hasSrc = false;
            for(Building b : comp) if(b.block != null && b.block.name.equals("power-source")) hasSrc = true;
            if(comp.size >= 2 && hasSrc && g.getLastPowerProduced() <= 0f){
                return "分量里有 power-source 但产出=0: " + names(comp) + " producers=" + g.producers.size;
            }
        }
        return null;
    }
    static String names(Seq<Building> comp){
        StringBuilder sb = new StringBuilder();
        for(Building b : comp) sb.append(b.block.name).append('@').append(b.tileX()).append(',').append(b.tileY()).append(' ');
        return sb.toString();
    }

    /** 违规时把每台有电建筑的电网/连线/邻接都打出来，方便定位"单向连线"之类的破绽。 */
    /** -Dfz.replay=<文件>：按文件里的操作序列回放（每行一条：place/break/save-load），无 RNG。 */
    static Seq<String> replayOps(){
        String path = System.getProperty("fz.replay");
        if(path == null) return null;
        Seq<String> out = new Seq<>();
        for(String line : Core.files.absolute(path).readString().split("\n")){
            String s = line.trim();
            if(s.startsWith("[FZ]  ")) s = s.substring(6).trim();
            if(s.isEmpty()) continue;
            if(s.startsWith("place ") || s.startsWith("break ") || s.equals("save-load")) out.add(s);
        }
        System.out.println("[FZ] 回放 " + out.size + " 条操作（" + path + "）");
        return out;
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
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        // 候选方块：组合建筑 + 协作组合方块 + 电源/电池/节点/连接器
        Seq<Block> cand = new Seq<>();
        for(String n : new String[]{"silicon-smelter", "phase-weaver", "cryofluid-mixer", "pulverizer",
                                    "combustion-generator", "battery", "power-node", "power-source"})
            if(findExact(n) != null) cand.add(findExact(n));
        ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        Class<?> coopc = Class.forName("combine.coop.CoopCombo", true, ml);
        java.lang.reflect.Method eligible = coopc.getMethod("eligible", Block.class);
        Block js = null;
        for(Block b : Vars.content.blocks()){
            if(!(Boolean) eligible.invoke(null, b)) continue;
            if(!b.hasPower || b.getClass().getName().startsWith("mindustry.")) continue;
            if(js == null || b.size < js.size) js = b;
        }
        if(js != null) cand.add(js);
        Block conn = byClass("combine.net.ComboConnector");
        if(conn != null) cand.add(conn);
        Block node = byClass("combine.net.ComboNode");
        if(node != null) cand.add(node);
        StringBuilder cn = new StringBuilder();
        for(Block b : cand) cn.append(b.name).append(' ');
        System.out.println("[FZ] 候选方块: " + cn);

        Random rnd = new Random(seed);
        Seq<String> hist = new Seq<>();
        int violations = 0;
        Seq<String> replay = replayOps();
        for(int step = 0; step < steps; step++){
            int roll = rnd.nextInt(100);
            if(replay != null){
                if(step >= replay.size) break;
                String op = replay.get(step);
                System.out.println("[FZ] 回放第 " + step + " 步: " + op);
                if(op.startsWith("save-load")){
                    SaveIO.save(Core.files.absolute("/tmp/cl/fuzz.msav"));
                    SaveIO.load(Core.files.absolute("/tmp/cl/fuzz.msav"));
                    Vars.state.set(mindustry.core.GameState.State.playing);
                }else if(op.startsWith("place ") || op.startsWith("break ")){
                    String[] parts = op.split(" ");
                    Block b = findExact(parts[1]);
                    if(parts[1].equals("connection")) b = byClass("combine.net.ComboConnector");
                    if(parts[1].equals("node")) b = byClass("combine.net.ComboNode");
                    if(b == null){ System.out.println("[FZ] 回放: 找不到方块 " + parts[1]); break; }
                    int x = Integer.parseInt(parts[2]), y = Integer.parseInt(parts[3]);
                    if(op.startsWith("place ")){
                        place(b, x, y, Team.sharded);
                    }else{
                        Building t = Vars.world.build(x, y);
                        if(t != null) t.tile.setBlock(Blocks.air);
                    }
                }
                // 多跑几帧再查：模组的"电网对账"是在下一帧开头收口的，末帧的中间态不算问题
                run(op.startsWith("break ") ? 20 : 2);
                run(5);
                String bad0 = audit();
                if(bad0 != null){
                    System.out.println("[FZ] 回放第 " + step + " 步后不变量被破坏: " + bad0);
                    violations++;
                }
                continue;
            }
            if(roll < 55){
                // 贴着已有建筑旁边放（容易成组）
                Seq<Building> live = powered();
                int x, y;
                if(live.size > 0 && rnd.nextInt(100) < 70){
                    Building near = live.get(rnd.nextInt(live.size));
                    x = near.tileX() + rnd.nextInt(7) - 3;
                    y = near.tileY() + rnd.nextInt(7) - 3;
                }else{
                    x = 30 + rnd.nextInt(180);
                    y = 40 + rnd.nextInt(100);
                }
                Block b = cand.get(rnd.nextInt(cand.size));
                String op = "place " + b.name + " " + x + " " + y;
                hist.add(op);
                place(b, x, y, Team.sharded);
                run(rnd.nextInt(6) + 1);
            }else if(roll < 75){
                Seq<Building> live = powered();
                if(live.size > 0){
                    Building b = live.get(rnd.nextInt(live.size));
                    String op = "break " + b.block.name + " " + b.tileX() + " " + b.tileY();
                    hist.add(op);
                    b.tile.setBlock(Blocks.air);
                    run(rnd.nextInt(6) + 1);
                }
            }else if(roll < 90){
                String op = "save-load";
                hist.add(op);
                SaveIO.save(Core.files.absolute("/tmp/cl/fuzz.msav"));
                SaveIO.load(Core.files.absolute("/tmp/cl/fuzz.msav"));
                Vars.state.set(mindustry.core.GameState.State.playing);
                run(rnd.nextInt(20) + 20);
            }else{
                run(rnd.nextInt(40) + 10);
            }
            // 模组的"电网对账"在下一帧开头收口（兜底巡检 5 帧一轮）：多跑几帧再判定
            run(25);
            String bad = audit();
            if(bad != null){
                violations++;
                System.out.println("[FZ] 第 " + step + " 步之后不变量被破坏: " + bad);
                System.out.println("[FZ] 最近操作:");
                for(String h : hist) System.out.println("[FZ]   " + h);
                break;
            }
        }
        check("随机 " + steps + " 步（seed=" + seed + "）电网不变量一直成立", violations == 0);
        System.out.println("[FZ] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")));
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
