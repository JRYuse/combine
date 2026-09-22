package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time; import arc.util.io.*;
import arc.math.Mathf;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.entities.abilities.Ability;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.Item; import mindustry.type.UnitType; import mindustry.ui.Fonts; import mindustry.world.*;
import java.io.ByteArrayInputStream; import java.io.ByteArrayOutputStream;

/**
 * 用户报（联机/单机都有）：
 *   1. 合体后的巨兽没有把**建造速率**和**挖矿速率**按成员累加；
 *   2. 客户端**不显示**巨兽单位；
 *   3. 联机时巨兽的**力墙绘制一直放大缩小**。
 *
 * 这里不读代码，直接量行为：
 *   A. 建造速率：给单位排一条建造计划，跑同样 30 个 tick，量 {@code ConstructBuild.progress}，
 *      1 台 poly 作为基准 → 2 台 / 3 台合体的进度必须是 2× / 3×；
 *   B. 挖矿速率：把单位放到铜矿上挖同样 300 个 tick，量挖到手的物品数，同样要求 2× / 3×；
 *   C. 客户端侧：按 NetClient 那条路（EntityMapping 新建 → readSync）造一份"客户端实体"，
 *      它的 type 也必须是累加后的（客户端读的 type 是 content 里那个占位类型，
 *      全靠成员构成重新推导，推导断了客户端就看不到累加值）；
 *   D. 力场绘制：连续喂两个同步快照，力场能力必须还是**同一个实例**、展开动画
 *      （{@code ForceFieldAbility.radiusScale}）不能被清零 —— 清零就是"力墙一直放大缩小"
 *      （每帧涨一点、快照又压回 0）。武器挂载的装填进度同理不能被快照清零。
 */
public class MegaStatSumTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_cj/data";
    static int pass = 0, fail = 0;
    static ClassLoader ml;
    static int landX, landY;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){}; Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MSS] " + t); };
        new HeadlessApplication(new MegaStatSumTest(), t -> t.printStackTrace());
    }

    static void check(String n, boolean ok){ System.out.println("[MSS] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i = 0; i < f; i++){ Time.delta = 1f; Vars.logic.update(); } }
    static Class<?> combineClass(String name){
        try{ return Class.forName(name, true, ml); }catch(Throwable t){ return null; }
    }
    static Building place(Block b, int x, int y, Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte)0, team, null);
        Building bu = Vars.world.build(ax, ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu;
    }

    /** 造一批单位 → mergeSelected → 返回巨兽。 */
    static Unit merge(Class<?> mergeCls, float x, float y, UnitType... types){
        try{
            Seq<Unit> units = new Seq<>();
            for(int i = 0; i < types.length; i++){
                Unit u = types[i].create(Team.sharded);
                u.set(x + i * 20f, y);
                u.add();
                units.add(u);
            }
            run(10);
            return (Unit)mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, units);
        }catch(Throwable t){
            System.out.println("[MSS] 融合失败: " + t);
            return null;
        }
    }

    static float progressOf(int x, int y){
        Building b = Vars.world.build(x, y);
        if(b instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild cb) return cb.progress;
        return b == null ? 0f : 1f;
    }

    /**
     * 让一个单位去建一格传送带，返回跑 ticks 个 tick 之后的施工进度。
     * 用无限资源规则（否则还要算取料距离，"造得快"会被走路时间淹没）。
     */
    static float buildAmount(Unit u, int x, int y, int ticks){
        Vars.world.tile(x, y).setBlock(Blocks.air);
        u.plans.clear();
        mindustry.entities.units.BuildPlan plan = new mindustry.entities.units.BuildPlan(x, y, 0, Blocks.conveyor, null);
        u.addBuild(plan);
        run(2);      // 让建造计划开出 ConstructBuild（第一帧只是排进去）
        Building made = Vars.world.build(x, y);
        float before = progressOf(x, y);
        run(ticks);
        float after = progressOf(x, y);
        u.plans.clear();
        System.out.println("[MSS]   建造诊断 " + u.type.name + ": buildSpeed=" + u.type.buildSpeed
            + " 目标=" + (made == null ? "null（计划没落地）" : made.getClass().getSimpleName())
            + " 造价=" + (made instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild
                ? ((mindustry.world.blocks.ConstructBlock.ConstructBuild)made).buildCost : -1f)
            + " 距目标=" + (int)Mathf.dst(u.x, u.y, x * 8f, y * 8f) + " buildRange=" + u.type.buildRange
            + " → " + ticks + " tick 进度增量=" + (after - before));
        // 【取增量】ConstructBuild 刚建出来时会有一笔一次性进度（原版 beginPlace 的初始值），
        // 绝对进度会把不同成员的这笔差算进去；速率看的是同一段时间里的增量。
        return after - before;
    }

    /** 让单位挖矿 ticks 个 tick，返回挖到手的物品数（自己背包里的）。 */
    static int mineAmount(Unit u, Tile ore, int ticks){
        try{
            @SuppressWarnings("unchecked")
            Seq<Object> ms = (Seq<Object>)u.getClass().getMethod("members").invoke(u);
            StringBuilder sb = new StringBuilder();
            for(Object up : ms){
                java.lang.reflect.Field f = up.getClass().getField("unit");
                Unit mu = (Unit)f.get(up);
                sb.append(mu == null ? "null" : (mu.type.name + "(mine=" + mu.type.mineSpeed + ",build=" + mu.type.buildSpeed + ")")).append(" ");
            }
            System.out.println("[MSS]   成员构成 " + ms.size + ": " + sb);
        }catch(Throwable t){ System.out.println("[MSS]   成员打印失败 " + t); }
        u.mineTile(ore);
        int before = u.stack.amount;
        run(10);
        System.out.println("[MSS]   挖矿诊断 " + u.type.name + ": mineTile=" + (u.mineTile() == null ? "null" : "有")
            + " mineSpeed=" + u.type.mineSpeed + " tier=" + u.type.mineTier + " 可挖=" + u.canMine()
            + " 有效矿=" + u.validMine(ore) + " 背包=" + u.stack.amount + "/" + u.itemCapacity()
            + " mineRange=" + u.type.mineRange + " 距矿=" + (int)Mathf.dst(u.x, u.y, ore.worldx(), ore.worldy())
            + " 有效=" + u.isValid() + " 已加=" + u.isAdded() + " 血=" + (int)u.health());
        run(ticks);
        int got = u.stack.amount - before;
        u.mineTile(null);
        u.stack.amount = 0;
        return got;
    }

    static Ability firstField(Unit u){
        for(Ability a : u.abilities())
            if(a instanceof mindustry.entities.abilities.ForceFieldAbility) return a;
        return null;
    }
    static float radiusScale(Ability a){
        // 力场可能是匿名子类（模组里带 breakSound 的那种），字段声明在父类上，要往上找
        for(Class<?> c = a.getClass(); c != null; c = c.getSuperclass()){
            try{
                java.lang.reflect.Field f = c.getDeclaredField("radiusScale");
                f.setAccessible(true);
                return f.getFloat(a);
            }catch(NoSuchFieldException ignored){
            }catch(Throwable t){ return -2f; }
        }
        return -1f;
    }
    /** 按 NetClient 的路子造一份"客户端实体"：EntityMapping 新建 → readSync（含成员块）。 */
    static Unit clientCopy(Unit src){
        try{
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
            src.writeSync(new Writes(dos));
            dos.flush();
            Unit u = (Unit)EntityMapping.map(src.classId()).get();
            u.id(src.id() + 1000);
            u.readSync(new Reads(new java.io.DataInputStream(new ByteArrayInputStream(bos.toByteArray()))));
            return u;
        }catch(Throwable t){
            System.out.println("[MSS] 客户端副本造失败: " + t);
            return null;
        }
    }
    /** 再喂一个快照（客户端每秒收到几十个）。 */
    static void feedSnapshot(Unit src, Unit dst){
        try{
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
            src.writeSync(new Writes(dos));
            dos.flush();
            dst.readSync(new Reads(new java.io.DataInputStream(new ByteArrayInputStream(bos.toByteArray()))));
        }catch(Throwable t){
            System.out.println("[MSS] 快照喂失败: " + t);
        }
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

            Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            // 无限资源 = 原版 ConstructBuild 直接完工（progress>=1 判据里有 infiniteResources），
            // 那样根本量不出建造速率差；改成正常资源 + 拉高造价倍数，让进度落在 0~1 之间。
            Vars.state.rules.buildCostMultiplier = 20f;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            run(20);
            for(int y = 25; y < 175; y++) for(int x = 10; x < 250; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
            }
            run(10);

            // 找一块干净陆地（单位站这儿）+ 一片铜矿（挖矿用）
            for(int y = 45; y < 150 && landX == 0; y++){
                for(int x = 40; x < 220; x++){
                    boolean ok = true;
                    for(int dy = -2; dy <= 2 && ok; dy++) for(int dx = -4; dx <= 4; dx++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ landX = x; landY = y; break; }
                }
            }
            if(landX == 0){ System.out.println("[MSS] 没找到陆地"); System.exit(3); }
            Building core = place(Blocks.coreShard, landX + 16, landY + 12, Team.sharded);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
            run(10);

            Tile ore = null;
            outer:
            for(int y = landY - 20; y < landY + 20; y++){
                for(int x = landX - 20; x < landX + 20; x++){
                    Tile t = Vars.world.tile(x, y);
                    // 必须挑"挖得动"的矿：getMineResult 还要过 type.mineTier >= item.hardness，
                    // 挖不动的矿会在 update 里当场把 mineTile 清掉（之前挑到高硬度矿，量出来全是 0）。
                    if(t != null && t.block() == Blocks.air && t.drop() != null && t.floor() != null
                        && !t.floor().isLiquid && t.drop().hardness <= UnitTypes.poly.mineTier){
                        ore = t; break outer;
                    }
                }
            }
            if(ore == null){ System.out.println("[MSS] 附近没有矿"); System.exit(3); }

            Class<?> mergeCls = combineClass("combine.units.UnitComboMerge");
            if(mergeCls == null){ System.out.println("[MSS] 找不到 UnitComboMerge"); System.exit(3); }

            // ================= A. 建造速率：1 台 poly vs 2/3 台合体 =================
            // 先找一格**真的能建**的位置：validPlace 会算地形/覆盖层/单位重叠，
            // 随手挑一格很可能是 false，计划会被当场丢掉（量出来永远是 0 进度）。
            int px = -1, py = -1;
            for(int y = landY - 12; y < landY + 12 && px < 0; y++){
                for(int x = landX + 6; x < landX + 26; x++){
                    if(Build.validPlace(Blocks.conveyor, Team.sharded, x, y, 0)){ px = x; py = y; break; }
                }
            }
            if(px < 0){ System.out.println("[MSS] 附近找不到能建的位置"); System.exit(3); }
            System.out.println("[MSS] 建造目标格 = " + px + "," + py);
            float bx = px * 8f - 120f, by = py * 8f;
            Unit poly1 = UnitTypes.poly.create(Team.sharded);
            poly1.set(bx, by);
            poly1.add();
            run(5);
            // 传送带 buildTime 只有 0.5（造价 5），量 5 个 tick 的进度：1 台 poly ≈ 0.2、
            // 2 台 ≈ 0.4、3 台 ≈ 0.6（progress/tick = type.buildSpeed / buildCost）。
            float build1 = buildAmount(poly1, px, py, 5);
            poly1.remove();
            run(2);

            float single = UnitTypes.poly.buildSpeed;
            Unit mega2b = merge(mergeCls, bx, by, UnitTypes.poly, UnitTypes.poly);
            float build2 = mega2b == null ? -1f : buildAmount(mega2b, px, py, 5);
            System.out.println("[MSS] 建造：1 台 poly buildSpeed=" + single + " 5 tick 进度=" + build1
                + "；2 台巨兽 buildSpeed=" + (mega2b == null ? -1 : mega2b.type.buildSpeed) + " 进度=" + build2);
            check("2 台 poly 合体：建造速率 = 2×（" + build2 + " vs 基准 " + build1 + "）",
                build1 > 0f && Math.abs(build2 / build1 - 2f) < 0.15f);
            if(mega2b != null) mega2b.remove();
            run(2);

            Unit mega3 = merge(mergeCls, bx, by, UnitTypes.poly, UnitTypes.poly, UnitTypes.poly);
            float build3 = mega3 == null ? -1f : buildAmount(mega3, px, py, 5);
            check("3 台 poly 合体：建造速率 = 3×（" + build3 + " vs 基准 " + build1 + "）",
                build1 > 0f && Math.abs(build3 / build1 - 3f) < 0.2f);
            if(mega3 != null) mega3.remove();
            run(2);

            // ================= B. 挖矿速率：1 台 poly vs 2/3 台合体 =================
            Unit m1 = UnitTypes.poly.create(Team.sharded);
            m1.set(ore.worldx(), ore.worldy() - 24f);
            m1.add();
            run(5);
            int mine1 = mineAmount(m1, ore, 300);
            m1.remove();
            run(2);

            Unit mm2 = merge(mergeCls, ore.worldx(), ore.worldy() - 24f, UnitTypes.poly, UnitTypes.poly);
            int mine2 = mm2 == null ? -1 : mineAmount(mm2, ore, 300);
            check("2 台 poly 合体：挖矿速率 = 2×（挖到 " + mine2 + " vs 基准 " + mine1 + "）",
                mine1 > 0 && Math.abs((float)mine2 / mine1 - 2f) < 0.25f);
            if(mm2 != null) mm2.remove();
            run(2);

            Unit mm3 = merge(mergeCls, ore.worldx(), ore.worldy() - 24f, UnitTypes.poly, UnitTypes.poly, UnitTypes.poly);
            int mine3 = mm3 == null ? -1 : mineAmount(mm3, ore, 300);
            check("3 台 poly 合体：挖矿速率 = 3×（挖到 " + mine3 + " vs 基准 " + mine1 + "）",
                mine1 > 0 && Math.abs((float)mine3 / mine1 - 3f) < 0.4f);

            // ============ C. 客户端那份实体（按 NetClient 的路子读快照）也要是累加值 ============
            Unit mm3c = mm3 == null ? null : clientCopy(mm3);
            if(mm3c != null){
                System.out.println("[MSS] 客户端副本: type=" + mm3c.type.name
                    + " buildSpeed=" + mm3c.type.buildSpeed + " mineSpeed=" + mm3c.type.mineSpeed
                    + " hitSize=" + mm3c.hitSize());
                check("客户端读回来的巨兽建造速率也是 3× poly（" + mm3c.type.buildSpeed + "）",
                    Math.abs(mm3c.type.buildSpeed - UnitTypes.poly.buildSpeed * 3f) < 0.001f);
                check("客户端读回来的巨兽挖矿速率也是 3× poly（" + mm3c.type.mineSpeed + "）",
                    Math.abs(mm3c.type.mineSpeed - UnitTypes.poly.mineSpeed * 3f) < 0.001f);
            }else{
                check("客户端副本建出来了", false);
            }
            if(mm3 != null) mm3.remove();
            run(2);

            // ================= D. 力场/武器状态要能扛住连续快照 =================
            Unit octMega = merge(mergeCls, bx + 120f, by, UnitTypes.oct, UnitTypes.oct);
            if(octMega == null){
                check("oct 巨兽融合成功", false);
            }else{
                run(5);
                Unit cli = clientCopy(octMega);
                Ability f1 = cli == null ? null : firstField(cli);
                if(cli == null || f1 == null){
                    check("客户端副本里有力场能力", false);
                }else{
                    check("客户端副本里有力场能力", true);
                    // 让力场"展开"（原版 draw 用的 radiusScale 在 update 里朝 1 收敛）
                    for(int i = 0; i < 60; i++) f1.update(cli);
                    float open1 = radiusScale(f1);
                    check("力场展开动画涨到 1（" + open1 + "）", open1 > 0.9f);

                    // 武器装填进度（客户端自己预测的，快照里不带）
                    if(cli.mounts().length > 0) cli.mounts()[0].reload = 30f;

                    feedSnapshot(octMega, cli);
                    feedSnapshot(octMega, cli);

                    Ability f2 = firstField(cli);
                    check("两个快照之后力场还是同一个实例（没被重建）", f2 == f1);
                    check("两个快照之后力场展开状态还在（" + radiusScale(f2) + "，重建会归 0）", radiusScale(f2) > 0.9f);
                    if(cli.mounts().length > 0)
                        check("两个快照之后武器装填进度还在（" + cli.mounts()[0].reload + "）", cli.mounts()[0].reload > 29f);
                }
            }

            System.out.println("[MSS] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){
            t.printStackTrace();
            System.exit(2);
        }
    }
}
