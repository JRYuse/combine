package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.entities.units.BuildPlan; import mindustry.entities.units.WeaponMount;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.type.*;
import mindustry.world.meta.BlockFlag;

/**
 * 用户报：**服务端和客户端使用多线程建造后帧率下降十分明显**。
 *
 * 这个测试不做判定，只**量**：同一套基地 + 同一批建造计划，
 *   · 空闲（什么都不造）时的每 tick 耗时；
 *   · 挂本模组建造武器（多线程建造）造这批计划时的每 tick 耗时；
 *   · 把建造武器摘掉（对照组）造同一批计划时的每 tick 耗时。
 * 再加上 ComboNet 自己的分段计时（`-Dperf.net=1` 打开 timeDebug 时会打印
 * "快照/建图/拆池/合池"），用来判断耗时到底是"建造武器本身"还是"每格方块变化都牵动
 * 全图网络重建/组合墙分组重建"。
 *
 * 参数（都是 -D 系统属性）：
 *   -Dperf.walls=900   基地里先摆多少面铜墙（= 组合墙，连通成一大组）
 *   -Dperf.plans=400   排多少格建造计划（多线程建造要造的活）
 *   -Dperf.ticks=600   最多跑多少 tick（真实 delta 1/60）
 *   -Dperf.strip=1     对照组：摘掉本模组的建造武器
 *   -Dperf.net=1       打开 ComboNet 的分段计时
 */
public class MultiBuildPerfTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int walls = Integer.getInteger("perf.walls", 900);
    static int planCount = Integer.getInteger("perf.plans", 400);
    static int maxTicks = Integer.getInteger("perf.ticks", 900);
    static boolean strip = Integer.getInteger("perf.strip", 0) == 1;
    static boolean netDebug = Integer.getInteger("perf.net", 0) == 1;
    static String tag = System.getProperty("perf.tag", strip ? "对照组" : "多线程");

    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{
            if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[MP] "+t);
            else if(t != null && t.contains("[combine][time]")) System.out.println("[T] "+t);
        };
        new HeadlessApplication(new MultiBuildPerfTest(), t->t.printStackTrace()); }

    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f/60f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Block find(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static boolean isBuildWeapon(WeaponMount wm){
        return wm != null && wm.weapon != null && wm.weapon.getClass().getName().equals("combine.MultiBuildWeapon"); }
    static Unit spawnUnit(UnitType type, float x, float y, boolean stripWeapons){
        Unit u = type.create(Team.sharded);
        u.set(x, y);
        if(stripWeapons){
            arc.struct.Seq<WeaponMount> keep = new arc.struct.Seq<>();
            for(WeaponMount wm : u.mounts) if(!isBuildWeapon(wm)) keep.add(wm);
            u.mounts = keep.toArray(WeaponMount.class);
        }
        u.add();
        return u;
    }

    /** 跑 n tick，返回每 tick 毫秒（取前 30 tick 之后，避开一次性的冷启动）。 */
    static double[] timeTicks(int ticks, int warmup){
        double[] out = new double[ticks - warmup];
        for(int i=0;i<ticks;i++){
            long t0 = System.nanoTime();
            arc.util.Time.delta = 1f/60f; Vars.logic.update();
            long dt = System.nanoTime() - t0;
            if(i >= warmup) out[i - warmup] = dt / 1e6;
        }
        return out;
    }
    static String stat(double[] v){
        double sum = 0, max = 0;
        for(double d : v){ sum += d; max = Math.max(max, d); }
        return String.format("平均 %.2fms/tick 峰值 %.2fms → 约 %.0f fps（均值计）", sum / v.length, max, 1000.0 / Math.max(sum / v.length, 0.001));
    }

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        try{ Vars.mods.eachClass(Mod::init); }catch(Throwable t){ System.out.println("[MP] eachClass 抛了: " + t); }
        if(Vars.logic==null) Vars.logic=new Logic();
        if(Vars.netServer==null) Vars.netServer=new NetServer();
        if(Vars.netClient==null) Vars.netClient=new NetClient();
        if(netDebug){
            try{
                Class<?> cn = Class.forName("combine.net.ComboNet", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
                java.lang.reflect.Field f = cn.getField("timeDebug");
                f.set(null, Boolean.TRUE);
                System.out.println("[MP] ComboNet.timeDebug 已打开");
            }catch(Throwable t){ System.out.println("[MP] 打开 timeDebug 失败: " + t); }
        }

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.state.rules.derelictRepair = true;
        // 无限资源 = 每格当场造完（用户"框选一大片蓝图，几把建造线程一起刷"的场景；
        // 有料慢造的话一格要 100+ tick，量不出"每格方块变化的开销"）
        if(Integer.getInteger("perf.instant", 1) == 1) Vars.state.rules.infiniteResources = true;
        Vars.logic.play();
        run(20);

        // 本模组的建造武器（正常在 ClientLoad/ServerLoad 里挂，headless 里手动来一次）
        try{
            Vars.mods.getMod("combine").main.getClass().getMethod("addBuildWeapons").invoke(null);
        }catch(Throwable t){ System.out.println("[MP] addBuildWeapons 失败: " + t); }

        Block wall = find("copper-wall");
        Block core = find("core-shard");
        Block conv = find("conveyor");
        if(wall == null || core == null || conv == null){ System.out.println("[MP] 缺方块"); System.exit(3); }
        System.out.println("[MP] 用例=" + tag + " 墙=" + wall.name + "(" + wall.getClass().getSimpleName() + ")"
            + " 计划=" + planCount + " 基地墙数=" + walls);

        for(int y=20;y<200;y++) for(int x=10;x<300;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        // 找一块干净的陆地（避免 deep-water：墙/传送带在深水上 validPlace=false）
        int ax0 = -1, ay0 = -1;
        int side0 = Math.max((int) Math.sqrt(Math.max(walls, 1)), 1);
        int needW = side0 + (int) Math.ceil(planCount / 32.0) + 4, needH = side0 + 8;
        outer0:
        for(int y = 24; y < 200 - needH; y++){
            for(int x = 16; x < 300 - needW; x++){
                boolean ok = true;
                for(int dy = 0; dy < needH && ok; dy++){
                    for(int dx = 0; dx < needW; dx++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor().isDeep() || t.block() != Blocks.air){ ok = false; break; }
                    }
                }
                if(ok){ ax0 = x; ay0 = y; break outer0; }
            }
        }
        if(ax0 < 0){ System.out.println("[MP] 没找到干净陆地"); System.exit(3); }
        System.out.println("[MP] 陆地原点=" + ax0 + "," + ay0 + " floor=" + Vars.world.tile(ax0, ay0).floor().name);

        // 1) 一大片连在一起的组合墙（LinkWall）：墙越多，"每格变化重算整组"的代价越明显
        int side = Math.max((int) Math.sqrt(walls), 1), placed = 0, bx = ax0 + 2, by = ay0 + 2;
        outer:
        for(int yy=0; yy<side; yy++){
            for(int xx=0; xx<side; xx++){
                if(placed >= walls) break outer;
                if(place(wall, bx + xx, by + yy, Team.sharded) != null) placed++;
            }
        }
        run(5);
        System.out.println("[MP] 实际摆下墙=" + placed);

        // 2) 核心（造核心机）+ 几个组合工厂/节点，让 ComboNet 有网络要维护
        Building coreB = place(core, ax0 + needW - 6, ay0 + needH - 6, Team.sharded);
        run(5);
        Unit unit = spawnUnit(mindustry.content.UnitTypes.alpha, (bx + side / 2f + 3) * 8f, (by + side / 2f) * 8f, strip);
        int mounts = 0, buildMounts = 0;
        for(WeaponMount wm : unit.mounts){ mounts++; if(isBuildWeapon(wm)) buildMounts++; }
        System.out.println("[MP] 单位=" + unit.type.name + " 挂座=" + mounts + " 建造挂座=" + buildMounts);

        // 3) 空闲基线
        double[] idle = timeTicks(60, 20);
        System.out.println("[MP] 空闲: " + stat(idle));

        // 4) 排一批建造计划（多线程建造要造的活）：紧贴墙区再补一片墙
        //    （新墙会并进那片大组合墙 —— 正是用户"多线程建造一大片墙"的场景）
        int planY = by;
        int px = bx + side;
        Block planBlock = "conv".equals(System.getProperty("perf.block", "wall")) ? conv : wall;
        for(int i=0;i<planCount;i++){
            int x = px + (i / 32), y = planY + (i % 32);
            unit.addBuild(new BuildPlan(x, y, 0, planBlock, null));
        }
        {
            int inRange = 0, placeable = 0;
            for(BuildPlan p : unit.plans()){
                if(unit.within(p.x * 8f, p.y * 8f, 236f)) inRange++;
                if(mindustry.world.Build.validPlace(p.block, unit.team, p.x, p.y, p.rotation)) placeable++;
            }
            System.out.println("[MP] 计划诊断: 射程内=" + inRange + "/" + unit.plans().size
                + " 可放置=" + placeable
                + " floor=" + Vars.world.tile(px, planY).floor().name + " deep=" + Vars.world.tile(px, planY).floor().isDeep()
                + " liquid=" + Vars.world.tile(px, planY).floor().isLiquid
                + " 核心料=" + (coreB.items == null ? "无" : coreB.items.get(Items.copper)));
        }
        System.out.println("[MP] 已排计划=" + unit.plans().size);
        // 给核心灌够料（建造要材料；没料的话挂座会空转，量出来的就不是建造开销了）
        float[] need = new float[Vars.content.items().size];
        for(BuildPlan p : unit.plans()){
            if(p.block == null) continue;
            for(ItemStack st : p.block.requirements) need[st.item.id] += st.amount * 4;
        }
        for(int i=0;i<need.length;i++){
            if(need[i] > 0 && coreB.items != null) coreB.items.add(Vars.content.item(i), (int) need[i] + 100);
        }

        int t = 0;
        java.util.ArrayList<Double> per = new java.util.ArrayList<>();
        int trace = Integer.getInteger("perf.trace", 0);
        for(; t<maxTicks; t++){
            long t0 = System.nanoTime();
            arc.util.Time.delta = 1f/60f; Vars.logic.update();
            per.add((System.nanoTime() - t0) / 1e6);
            if(trace > 0 && t < trace && t % 5 == 0){
                StringBuilder sb = new StringBuilder();
                for(WeaponMount wm : unit.mounts){
                    if(!isBuildWeapon(wm)) continue;
                    Object plan = null, target = null, cd = null;
                    for(java.lang.reflect.Field f : wm.getClass().getFields()){
                        try{
                            if(f.getName().equals("plan")) plan = f.get(wm);
                            if(f.getName().equals("target")) target = f.get(wm);
                            if(f.getName().equals("searchCooldown")) cd = f.get(wm);
                        }catch(Throwable t2){ System.out.println("[MP] 读字段 " + f.getName() + " 失败: " + t2); }
                    }
                    String extra = "";
                    if(plan != null){
                        try{
                            java.lang.reflect.Field fx = plan.getClass().getField("x");
                            java.lang.reflect.Field fy = plan.getClass().getField("y");
                            int px2 = fx.getInt(plan), py2 = fy.getInt(plan);
                            Tile pt = Vars.world.tile(px2, py2);
                            extra = "(" + px2 + "," + py2 + " 场上=" + (pt == null || pt.build == null ? "空" : pt.build.getClass().getSimpleName())
                                + " valid=" + mindustry.world.Build.validPlace(wall, unit.team, px2, py2, 0) + ")";
                        }catch(Throwable ignored){}
                    }
                    sb.append(" [plan=").append(plan == null ? "-" : "有" + extra).append(" target=").append(target == null ? "-" : "有").append(" cd=").append(cd).append("]");
                }
                System.out.println("[MP] t=" + t + " 队列=" + unit.plans().size + sb
                    + " closestCore=" + (unit.closestCore() == null ? "null" : "有")
                    + " 核心铜=" + (coreB.items == null ? "无" : coreB.items.get(Items.copper)));
            }
            if(unit.plans().size == 0) { t++; break; }
        }
        double[] arr = new double[Math.max(per.size() - 20, 1)];
        int ai = 0;
        for(int i=20; i<per.size(); i++) arr[ai++] = per.get(i);
        System.out.println("[MP] 建造中: " + stat(arr) + " | 用了 " + t + " tick，剩余计划=" + unit.plans().size);

        // 5) 建造完之后再量一次（方块的后续每帧开销：组合墙 linkerHash、组合体 updateTile 等）
        double[] after = timeTicks(60, 20);
        System.out.println("[MP] 建造后空闲: " + stat(after));
        System.out.println("[MP] RESULT " + tag + " 空闲=" + String.format("%.2f", avg(idle))
            + "ms 建造中=" + String.format("%.2f", avg(arr))
            + "ms 建造后=" + String.format("%.2f", avg(after)) + "ms");
        System.exit(0);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
    static double avg(double[] v){ double s=0; for(double d : v) s+=d; return v.length==0?0:s/v.length; }
}
