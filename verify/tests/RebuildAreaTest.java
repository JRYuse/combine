package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.entities.units.BuildPlan; import mindustry.entities.units.WeaponMount;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.type.*;

/**
 * 用户报："核心单位建造建筑有问题 —— 修废墟和重建不会立刻修复/重建范围里所有建筑，只修/建几个；
 * 批量改传送带方向也一样。"
 *
 * 【为什么老测试看不出问题】以前的测试用 arc.util.Time.delta = 1f 跑帧 = 一帧顶一秒。
 * 真机 60fps 下 Time.delta = 1/60：原版 BuilderComp 的 buildCounter 要一秒才凑够 1，
 * 也就是**单位自己的建造逻辑每秒才处理 1 格**。核心机那几把多线程建造武器
 * (MultiBuildWeapon) 才是"一次修一片 / 转一片 / 几格同时开工"的关键 ——
 * 它们没干活，用户看到的就是"一秒钟才动一两格，剩下的不动了"。
 *
 * 本测试一律用真实 delta（1/60）跑，两种场景各测两遍：
 *   模组 = 挂上本模组建造武器的核心单位；原版 = 把那些挂座摘掉的核心单位（对照组）。
 *
 *   场景 1 批量改方向：一排 8 台传送带，方向 0 → 拖一排方向 1 的计划（原版原地转向计划）。
 *   场景 2 重建区（原版 B 键框选 rebuildArea）：4x4 台 derelict 废墟 + 4 台被摧毁建筑。
 *
 * 复现过的两个根因（都在 MultiBuildWeapon 里）：
 *   a) "原地改方向"的计划被当成"这一格已经造好了"直接删掉 → 批量改方向只动了队首一格；
 *   b) 修废墟/改方向这些**当场就能做完**的计划排在队列后面（rebuildArea 就是先排被摧毁建筑、
 *      后面才排废墟），而挂座又是"认领了就抱着不放" → 前面慢施工没完工，废墟一个都不修。
 */
public class RebuildAreaTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new RebuildAreaTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[RA] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    /** 真实节奏：一帧 = 1/60 秒。 */
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta = 1f/60f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Block find(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static boolean isBuildWeapon(WeaponMount wm){
        return wm != null && wm.weapon != null && wm.weapon.getClass().getName().equals("combine.MultiBuildWeapon"); }
    static int countBuildMounts(Unit u){ int n=0; for(WeaponMount wm : u.mounts) if(isBuildWeapon(wm)) n++; return n; }
    /** 造一台单位；strip=true 时摘掉本模组的建造武器（对照组 = 原版节奏）。 */
    static Unit spawnUnit(UnitType type, float x, float y, boolean strip){
        Unit u = type.create(Team.sharded);
        u.set(x, y);
        if(strip){
            arc.struct.Seq<WeaponMount> keep = new arc.struct.Seq<>();
            for(WeaponMount wm : u.mounts) if(!isBuildWeapon(wm)) keep.add(wm);
            u.mounts = keep.toArray(WeaponMount.class);
        }
        u.add();
        return u;
    }
    static String frames(int f){ return f < 0 ? "超时" : (f + " 帧（" + String.format("%.2f", f/60f) + " 秒）"); }

    // ---------------- 场景 1：批量改传送带方向 ----------------
    static int rotateScenario(int cx, int cy, int count, Block conv, Unit unit, int maxFrames, String tag){
        for(int i=0;i<count;i++){
            Building b = place(conv, cx+i, cy, Team.sharded);
            if(b != null){ b.rotation = 0; try{ b.updateProximity(); }catch(Throwable ignored){} }
        }
        unit.set((cx + count/2f) * 8f, (cy + 3) * 8f);
        run(5);
        // 原版拖动改方向生成的计划就是这样：同格、同名方块、方向不同
        for(int i=0;i<count;i++) unit.addBuild(new BuildPlan(cx+i, cy, 1, conv, null));
        int planCount = unit.plans().size;
        int done = -1;
        for(int f=0; f<maxFrames; f++){
            arc.util.Time.delta = 1f/60f; Vars.logic.update();
            int rot = 0;
            for(int i=0;i<count;i++){ Tile t = Vars.world.tile(cx+i, cy); if(t.build != null && t.build.rotation == 1) rot++; }
            if(rot == count){ done = f + 1; break; }
        }
        int rot = 0;
        for(int i=0;i<count;i++){ Tile t = Vars.world.tile(cx+i, cy); if(t.build != null && t.build.rotation == 1) rot++; }
        System.out.println("[RA] " + tag + " 批量改方向：" + rot + "/" + count + " 转过去，计划=" + planCount
            + "，用时=" + (done < 0 ? ("超时(>" + maxFrames + "帧)") : frames(done)) + "，队列剩=" + unit.plans().size);
        unit.plans().clear();
        for(int i=0;i<count;i++){ Tile t = Vars.world.tile(cx+i, cy); if(t.build != null){ t.build.rotation = 0; try{ t.build.updateProximity(); }catch(Throwable ignored){} } }
        run(2);
        return done;
    }

    // ---------------- 场景 2：重建区（废墟 + 被摧毁建筑） ----------------
    static int rebuildScenario(int bx, int by, int nx, int ny, Block wall, Block rebuildBlock, Unit unit, int maxFrames, String tag){
        int broken = 4;
        unit.plans().clear();
        unit.set((bx + nx/2f) * 8f, (by + ny/2f) * 8f + 24f);
        run(2);
        // 废墟：nx*ny 面 derelict 墙
        for(int y=0;y<ny;y++) for(int x=0;x<nx;x++){
            Building r = place(wall, bx + x, by + y, Team.sharded);
            if(r != null) r.changeTeam(Team.derelict);
        }
        // 被摧毁的建筑（队伍计划表 = 原版 rebuildArea 的第一段来源），格子是空的
        Teams.TeamData data = Team.sharded.data();
        for(int x=0;x<broken;x++) data.plans.addLast(new Teams.BlockPlan(bx + nx + 2 + x, by, (short)0, rebuildBlock, null));
        run(2);
        int ruins = 0;
        for(int y=0;y<ny;y++) for(int x=0;x<nx;x++){
            Tile t = Vars.world.tile(bx+x, by+y);
            if(t != null && t.team() == Team.derelict && t.block() == wall) ruins++;
        }

        // ---- 完全照抄原版 InputHandler.rebuildArea 的两段 ----
        int x1 = bx, y1 = by, x2 = bx + nx + 1 + broken, y2 = by + ny - 1;
        for(Teams.BlockPlan p : data.plans){
            if(p.block == null || p.removed) continue;
            if(p.x >= x1 && p.x <= x2 && p.y >= y1 && p.y <= y2){
                unit.addBuild(new BuildPlan(p.x, p.y, p.rotation, p.block, p.config));
            }
        }
        for(int x=x1;x<=x2;x++) for(int y=y1;y<=y2;y++){
            Tile t = Vars.world.tile(x,y);
            if(t != null && t.build != null && t.build.team == Team.derelict){
                unit.addBuild(new BuildPlan(t.build.tileX(), t.build.tileY(), t.build.rotation, t.block(), t.build.config()));
            }
        }
        int planCount = unit.plans().size;

        int done = -1;
        for(int f=0; f<maxFrames; f++){
            arc.util.Time.delta = 1f/60f; Vars.logic.update();
            int repaired = 0;
            for(int y=0;y<ny;y++) for(int x=0;x<nx;x++){
                Tile t = Vars.world.tile(bx+x, by+y);
                if(t.team() == Team.sharded && t.block() == wall && t.build != null) repaired++;
            }
            int rebuilt = 0;
            for(int x=0;x<broken;x++){
                Tile t = Vars.world.tile(bx + nx + 2 + x, by);
                if(t.block() == rebuildBlock && t.build != null) rebuilt++;
            }
            if(repaired == ruins && rebuilt == broken){ done = f + 1; break; }
        }
        int repaired = 0;
        for(int y=0;y<ny;y++) for(int x=0;x<nx;x++){
            Tile t = Vars.world.tile(bx+x, by+y);
            if(t.team() == Team.sharded && t.block() == wall && t.build != null) repaired++;
        }
        int rebuilt = 0;
        for(int x=0;x<broken;x++){
            Tile t = Vars.world.tile(bx + nx + 2 + x, by);
            if(t.block() == rebuildBlock && t.build != null) rebuilt++;
        }
        System.out.println("[RA] " + tag + " 重建区：废墟=" + repaired + "/" + ruins + " 重建=" + rebuilt + "/" + broken
            + "，计划=" + planCount + "，用时=" + (done < 0 ? ("超时(>" + maxFrames + "帧)") : frames(done))
            + "，队列剩=" + unit.plans().size);
        // 清场
        for(int y=0;y<ny;y++) for(int x=0;x<nx;x++){ Tile t = Vars.world.tile(bx+x, by+y); if(t != null && t.build != null) t.setBlock(Blocks.air); }
        for(int x=0;x<broken;x++){ Tile t = Vars.world.tile(bx + nx + 2 + x, by); if(t != null && t.build != null) t.setBlock(Blocks.air); }
        data.plans.clear();
        unit.plans().clear();
        run(2);
        return done;
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
        for(int y=30;y<190;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        // 本模组的建造武器（正常在 ClientLoad/ServerLoad 里挂，headless 里手动来一次）
        ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        Class.forName("combine.Main", true, ml).getMethod("addBuildWeapons").invoke(null);

        Block wall = find("copper-wall");
        Block conv = find("conveyor");
        System.out.println("[RA] 规则 derelictRepair=" + Vars.state.rules.derelictRepair
            + " 墙=" + (wall == null ? "无" : wall.name + "/" + wall.getClass().getSimpleName())
            + " 传送带=" + (conv == null ? "无" : conv.name));
        if(wall == null || conv == null){ System.out.println("[RA] 缺方块"); System.exit(3); }

        // 找一块干净的陆地（避免 deep-water：墙/传送带在深水上 validPlace=false）
        int ax0 = -1, ay0 = -1;
        outer:
        for(int y = 40; y < 150; y++){
            for(int x = 20; x < 220; x++){
                boolean ok = true;
                for(int dy = 0; dy < 26 && ok; dy++){
                    for(int dx = 0; dx < 26; dx++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor().isDeep() || t.block() != Blocks.air){ ok = false; break; }
                    }
                }
                if(ok){ ax0 = x; ay0 = y; break outer; }
            }
        }
        if(ax0 < 0){ System.out.println("[RA] 没找到 26x26 陆地"); System.exit(3); }
        System.out.println("[RA] 陆地原点=" + ax0 + "," + ay0 + " floor=" + Vars.world.tile(ax0, ay0).floor().name);

        Building core = place(Blocks.coreShard, ax0 + 21, ay0 + 21, Team.sharded);
        run(10);
        if(core == null){ System.out.println("[RA] 没核心"); System.exit(3); }
        // 给核心材料，保证"重建"这一半也真的能造出来（不然卡在没料）
        core.items.set(Items.copper, 4000);
        core.items.set(Items.lead, 4000);

        Unit coreUnit = spawnUnit(UnitTypes.alpha, (ax0 + 13) * 8f, (ay0 + 13) * 8f, false);
        Unit plainUnit = spawnUnit(UnitTypes.alpha, (ax0 + 17) * 8f, (ay0 + 17) * 8f, true);
        run(5);
        System.out.println("[RA] 核心单位=" + coreUnit.type.name + " 本模组建造挂座=" + countBuildMounts(coreUnit)
            + "；对照组挂座=" + countBuildMounts(plainUnit));

        int cx = ax0 + 2, cy = ay0 + 2;
        int modRot = rotateScenario(cx, cy, 8, conv, coreUnit, 900, "模组");
        int vanRot = rotateScenario(cx, cy, 8, conv, plainUnit, 900, "原版");
        check("批量改方向：核心机 1 秒内全部转过去（实测 " + frames(modRot) + "）", modRot >= 0 && modRot <= 60);
        check("批量改方向：不能比原版还慢（模组 " + frames(modRot) + " vs 原版 " + frames(vanRot) + "）",
            modRot >= 0 && (vanRot < 0 || modRot <= vanRot));

        int bx = ax0 + 2, by = ay0 + 12;
        int modFix = rebuildScenario(bx, by, 4, 4, wall, conv, coreUnit, 1500, "模组");
        int vanFix = rebuildScenario(bx, by, 4, 4, wall, conv, plainUnit, 1500, "原版");
        check("重建区：核心机 5 秒内废墟全修好 + 重建全部完工（实测 " + frames(modFix) + "）", modFix >= 0 && modFix <= 300);
        check("重建区：不能比原版还慢（模组 " + frames(modFix) + " vs 原版 " + frames(vanFix) + "）",
            modFix >= 0 && (vanFix < 0 || modFix <= vanFix));

        System.out.println("[RA] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
