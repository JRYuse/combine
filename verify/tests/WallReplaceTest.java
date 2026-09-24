package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.entities.units.BuildPlan; import mindustry.entities.units.WeaponMount;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.type.*;

/**
 * 用户报：**在墙上覆盖新的墙建造的时候多线程建造武器不工作**（"替换墙只能单线程"）。
 *
 * 场景就是游戏里最常见的：拖一片**新墙**盖在**旧墙**上（原版允许同类同尺寸的方块互相覆盖，
 * `Block.canReplace`）。原版核心机的建造逻辑一秒处理一格，所以"一次盖一片"本来要靠
 * {@link combine.MultiBuildWeapon} 那几把挂座并行认领。
 *
 * 但 {@code claimable()} 里原来对"格子上已经有别的方块"的建造计划**一律拒收**
 * （当成"这一格已经造好了"）—— 于是覆盖建造的计划一个挂座都不认领，只剩单位自己
 * 一秒一格地慢慢做，表现就是用户看到的"多线程建造武器不工作 / 只能单线程"。
 *
 * 本测试：8 面铜墙 → 排 8 个"盖成铅墙"的计划 → 真实 delta（1/60）跑，
 * 要求核心机 1 秒内全部盖完，而且不能比摘掉建造武器的对照组慢。
 */
public class WallReplaceTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[WR] "+t); };
        new HeadlessApplication(new WallReplaceTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[WR] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    /** 真实节奏：一帧 = 1/60 秒（delta=1 会把原版"一秒一格"的节流掩盖掉）。 */
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta = 1f/60f; Vars.logic.update(); } }
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

    /**
     * 一排旧墙 → 排"盖成新墙"的计划 → 跑帧。
     * @return 全部盖完用的帧数（超时返回 -1）
     */
    static int replaceScenario(int cx, int cy, int count, Block oldWall, Block newWall, Unit unit, int maxFrames){
        for(int i = 0; i < count; i++)
            place(oldWall, cx + i, cy, Team.sharded);
        unit.set((cx + count / 2f) * 8f, (cy + 4) * 8f);
        run(5);
        for(int i = 0; i < count; i++)
            unit.addBuild(new BuildPlan(cx + i, cy, 0, newWall, null));
        for(int f = 0; f < maxFrames; f++){
            arc.util.Time.delta = 1f/60f; Vars.logic.update();
            int done = 0;
            for(int i = 0; i < count; i++){
                Tile t = Vars.world.tile(cx + i, cy);
                if(t != null && t.block() == newWall) done++;
            }
            if(done >= count) return f + 1;
        }
        return -1;
    }

    static String frames(int f){ return f < 0 ? "超时" : (f + " 帧（" + String.format("%.2f", f/60f) + " 秒）"); }

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

        // 本模组的建造武器（正常在 ClientLoad/ServerLoad 里挂，headless 里手动来一次）
        Vars.mods.getMod("combine").main.getClass().getMethod("addBuildWeapons").invoke(null);

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.state.rules.infiniteResources = true; // 本测试只量"认不认领"，不量料
        Vars.logic.play();
        run(20);

        Block oldWall = find("copper-wall");
        Block newWall = find("titanium-wall") != null ? find("titanium-wall") : find("plastanium-wall");
        if(oldWall == null || newWall == null){ System.out.println("[WR] 缺墙方块"); System.exit(3); }
        boolean canReplace = newWall.canReplace(oldWall);
        System.out.println("[WR] 旧墙=" + oldWall.name + "(" + oldWall.getClass().getSimpleName() + ")"
            + " 新墙=" + newWall.name + "(" + newWall.getClass().getSimpleName() + ")"
            + " 原版允许覆盖=" + canReplace);
        check("原版允许同类墙覆盖（前置条件）", canReplace);

        for(int y=20;y<200;y++) for(int x=10;x<300;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        // 找一块干净的陆地
        int ax0 = -1, ay0 = -1;
        outer:
        for(int y = 24; y < 180; y++){
            for(int x = 16; x < 260; x++){
                boolean ok = true;
                for(int dy = 0; dy < 12 && ok; dy++){
                    for(int dx = 0; dx < 20; dx++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor().isDeep() || t.block() != Blocks.air){ ok = false; break; }
                    }
                }
                if(ok){ ax0 = x; ay0 = y; break outer; }
            }
        }
        if(ax0 < 0){ System.out.println("[WR] 找不到陆地"); System.exit(3); }
        Building core = place(Blocks.coreShard, ax0 + 17, ay0 + 9, Team.sharded);
        run(10);
        core.items.set(Items.copper, 4000);
        core.items.set(Items.titanium, 4000);
        core.items.set(Items.plastanium, 4000);

        Unit modUnit = spawnUnit(UnitTypes.alpha, (ax0 + 9) * 8f, (ay0 + 4) * 8f, false);
        Unit vanUnit = spawnUnit(UnitTypes.alpha, (ax0 + 9) * 8f, (ay0 + 4) * 8f, true);
        int modMounts = 0, vanMounts = 0;
        for(WeaponMount wm : modUnit.mounts) if(isBuildWeapon(wm)) modMounts++;
        for(WeaponMount wm : vanUnit.mounts) if(isBuildWeapon(wm)) vanMounts++;
        System.out.println("[WR] 模组核心机建造挂座=" + modMounts + "，对照组=" + vanMounts);

        int modFrames = replaceScenario(ax0 + 2, ay0 + 2, 8, oldWall, newWall, modUnit, 900);
        // 对照组：清场重来（原版一秒一格）
        for(int y=20;y<200;y++) for(int x=10;x<300;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
        int vanFrames = replaceScenario(ax0 + 2, ay0 + 2, 8, oldWall, newWall, vanUnit, 900);
        System.out.println("[WR] 覆盖建造 8 格：模组=" + frames(modFrames) + "，对照组=" + frames(vanFrames));

        check("覆盖建造：核心机 1 秒内把 8 面墙全部盖完（实测 " + frames(modFrames) + "）",
            modFrames >= 0 && modFrames <= 60);
        check("覆盖建造：不能比摘掉建造武器的对照组慢（模组 " + frames(modFrames) + " vs 对照组 " + frames(vanFrames) + "）",
            modFrames >= 0 && (vanFrames < 0 || modFrames <= vanFrames));

        System.out.println("[WR] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
