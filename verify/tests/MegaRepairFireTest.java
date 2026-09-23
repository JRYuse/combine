package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.entities.units.*;
import mindustry.game.*; import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报：**"组合了 mega 的巨兽单位在 mega 武器去修复建筑的时候，把其他武器的开火会损坏己方建筑"**。
 *
 * <p>要验的两件事：
 * <ol>
 *   <li>基线：同队子弹/伤害到底会不会伤到**己方建筑**（原版语义，先量出来）；</li>
 *   <li>场景：巨兽（成员含 mega，带维修/建造武器）+ 同组空闲成员 + 己方"半血墙/在建结构"，
 *       跑若干秒，己方建筑的血**只许涨（被修）不许掉**。</li>
 * </ol>
 *
 * <p>这条测试要"同时装 combine + combineunit"的数据目录（例如 `/tmp/mp_both/data`）：mega 的
 * 建造武器是 combine 挂的，而"借火"（同组空闲成员把武器借给开火者代打）在 combineunit 里。
 *
 * <p>日志会把巨兽每个挂载的（武器类 / shoot / target / aim）打出来，出问题时能直接看出
 * "到底是哪门武器在朝哪打"。
 */
public class MegaRepairFireTest implements ApplicationListener{
    static String dataDir="/tmp/mp_both/data";
    static int pass=0, fail=0;
    static ClassLoader uml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new MegaRepairFireTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MRF] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        return Vars.world.build(ax,ay);
    }
    /** 造到一半的建筑（ConstructBuild，progress 很小）：建造武器要认领的就是这种格子。 */
    static Building placeHalfBuilt(Block b,int x,int y,Team team){
        mindustry.world.Build.beginPlace(null,b,team,x,y,0,null);
        Building bu = Vars.world.build(x,y);
        if(bu instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild cb){
            try{ var f = cb.getClass().getDeclaredField("progress"); f.setAccessible(true); f.setFloat(cb, 0.05f); }catch(Throwable ignored){}
        }
        return bu;
    }
    static Object call(String cls, String method, Class<?>[] sig, Object... args){
        try{
            Class<?> c = Class.forName(cls, true, uml);
            java.lang.reflect.Method m = sig == null ? c.getMethod(method) : c.getMethod(method, sig);
            m.setAccessible(true);
            return m.invoke(null, args);
        }catch(Throwable t){ System.out.println("[MRF] 调用 " + cls + "." + method + " 失败: " + t); return null; }
    }
    static String mounts(Unit u){
        StringBuilder sb = new StringBuilder();
        if(u == null) return "（无单位）";
        WeaponMount[] ms = u.mounts();
        for(int i = 0; i < ms.length; i++){
            WeaponMount m = ms[i];
            String wn = u.type.weapons.size > i ? u.type.weapons.get(i).getClass().getSimpleName() : "?";
            sb.append("\n    [").append(i).append("] ").append(wn)
              .append(" mount=").append(m.getClass().getSimpleName())
              .append(" shoot=").append(m.shoot)
              .append(" target=").append(m.target == null ? "null"
                  : (m.target.getClass().getSimpleName() + " team=" + m.target.team() + " @"
                     + (int)m.target.x() + "," + (int)m.target.y()))
              .append(" aim=").append((int)m.aimX).append(",").append((int)m.aimY)
              .append(" reload=").append(String.format("%.1f", m.reload));
        }
        return sb.toString();
    }

    /** 没有 AI 的控制器：AI 每帧会按自己的索敌结果覆盖 mount.shoot/target，测试要自己按住扳机。 */
    static class DummyController implements UnitController{
        Unit u;
        @Override public void unit(Unit u){ this.u = u; }
        @Override public Unit unit(){ return u; }
    }

    static Unit noAi(Unit u, float x, float y){
        u.controller(new DummyController());
        u.set(x, y);
        u.add();
        return u;
    }

    static int totalShots(Unit u){
        int n = 0;
        if(u.mounts() != null) for(WeaponMount m : u.mounts()) n += m.totalShots;
        return n;
    }

    /**
     * 让 shooter 把 target 当**唯一目标**按住扳机跑 ticks 帧，返回"借出方（同组空闲成员）
     * 的挂载射击次数"增量 —— 增量 > 0 就说明同组其他武器的火力被借去打了这个目标。
     */
    static int shotsWhileTargeting(Unit shooter, Unit lender, Building target, int ticks, boolean keepTarget){
        // keepTarget=false 时同时压住瞄准点（target 模式与 aim 模式分开量）

        int before = totalShots(lender);
        for(int i = 0; i < ticks; i++){
            if(shooter != null && shooter.isValid()){
                if(shooter.mounts() != null) for(WeaponMount m : shooter.mounts()){
                    m.target = target;
                    m.aimX = target.x; m.aimY = target.y;
                    m.shoot = true; m.rotate = true;
                }
                shooter.aimX = target.x; shooter.aimY = target.y;   // 单位瞄准点（借火兜底路径看的就是它）
                // 原版 isShooting 是个**字段**，由控制器/武器每帧写（不是从 mounts 推出来的）——
                // 手动模拟"在开火"必须把它也置真，否则 UnitComboFire 第一步就跳过。
                shooter.isShooting(true);
            }
            if(lender != null && lender.isValid() && lender.mounts() != null)
                for(WeaponMount m : lender.mounts()) m.shoot = false;   // 借出方必须空闲
            run(1);
        }
        return totalShots(lender) - before;
    }

    /** 瞄准点模式：shooter 的挂载没有 target，只有瞄准点停在目标建筑上（玩家长按己方建筑）。 */
    static int shotsWhileAiming(Unit shooter, Unit lender, Building target, int ticks){
        int before = totalShots(lender);
        for(int i = 0; i < ticks; i++){
            if(shooter != null && shooter.isValid() && shooter.mounts() != null)
                for(WeaponMount m : shooter.mounts()){
                    m.target = null;
                    m.aimX = target.x; m.aimY = target.y;
                    m.shoot = true; m.rotate = true;
                }
            if(shooter != null && shooter.isValid()){
                shooter.aimX = target.x; shooter.aimY = target.y;
                shooter.isShooting(true);
            }
            if(lender != null && lender.isValid() && lender.mounts() != null)
                for(WeaponMount m : lender.mounts()) m.shoot = false;
            run(1);
        }
        return totalShots(lender) - before;
    }

    /** 反射问 comboId / groupable（测试类不能直接引用模组类）。 */
    static boolean comboGroupable(Unit u){
        try{
            Class<?> dmg = Class.forName("combineunit.units.UnitComboDamage", true, uml);
            return (Boolean)dmg.getMethod("groupable", Unit.class).invoke(null, u);
        }catch(Throwable t){ return false; }
    }

    /** 让 u 直接对着 b 开火（没有 AI 覆盖），返回射击次数。 */
    static int shootAt(Unit u, Building b, int ticks){
        int before = totalShots(u);
        for(int i = 0; i < ticks; i++){
            if(u.mounts() != null) for(WeaponMount m : u.mounts()){
                m.target = b;
                m.aimX = b.x; m.aimY = b.y;
                m.shoot = true; m.rotate = true;
            }
            u.aimX = b.x; u.aimY = b.y;
            u.isShooting(true);
            run(1);
        }
        return totalShots(u) - before;
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
        var unitMod = Vars.mods.getMod("combineunit");
        if(unitMod == null){ System.out.println("[MRF] SKIP 数据目录里没有 combineunit"); System.exit(0); }
        uml = unitMod.main.getClass().getClassLoader();
        Class<?> mergeCls = Class.forName("combineunit.units.UnitComboMerge", true, uml);
        Class<?> dmgCls = Class.forName("combineunit.units.UnitComboDamage", true, uml);
        System.out.println("[MRF] mega 的武器: " + UnitTypes.mega.weapons.map(w -> w.getClass().getSimpleName() + "(heals="
            + (w.bullet != null && w.bullet.heals()) + ")").toString());

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.waves = false;
        Vars.logic.play();
        run(20);
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(10);

        // 找一块陆地
        int ox=-1, oy=-1;
        outer:
        for(int y=45;y<150;y++) for(int x=40;x<220;x++){
            boolean ok = true;
            for(int dy=-3;dy<=3 && ok;dy++) for(int dx=-4;dx<=4;dx++){
                Tile t = Vars.world.tile(x+dx, y+dy);
                if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
            }
            if(ok){ ox=x; oy=y; break outer; }
        }
        if(ox < 0){ System.out.println("[MRF] 没找到陆地"); System.exit(3); }
        Building core = place(Blocks.coreShard, ox + 26, oy + 14, Team.sharded);
        if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
        run(10);
        float cx = ox * 8f, cy = oy * 8f;

        // ---------- 基线：同队伤害打己方建筑，原版会不会伤到它 ----------
        Building b1 = place(Blocks.copperWall, ox + 3, oy, Team.sharded);
        Building baseWall = b1;
        float hp = baseWall.maxHealth();
        baseWall.health(hp * 0.5f);
        float beforeBase = baseWall.health();
        mindustry.entities.Damage.damage(Team.sharded, baseWall.x, baseWall.y, 8f, 40f, true);
        run(2);
        float afterBase = baseWall.health();
        System.out.println("[MRF] 基线（同队 Damage.damage）：己方墙 " + (int)beforeBase + " → " + (int)afterBase);
        check("基线：同队伤害不会伤到己方建筑（原版有敌我判定）", afterBase >= beforeBase - 0.01f);

        // ---------- 基线 2：同队**单位的武器**真的对着己方建筑开火，会不会打坏它 ----------
        // 关键点：AI 每帧会覆盖 mount.shoot/target，所以这里用"没有 AI 的控制器"（DummyController）
        // 把扳机按住 —— 这样才能确定"子弹真的朝己方建筑飞出去了"再谈掉不掉血。
        Unit friendly = noAi(UnitTypes.dagger.create(Team.sharded), baseWall.x - 40f, baseWall.y);
        int friendlyShots = shootAt(friendly, baseWall, 240);
        System.out.println("[MRF] 基线 2（同队单位武器对着己方墙打 240 tick）：开火次数=" + friendlyShots
            + " 己方墙 " + (int)beforeBase + " → " + (int)baseWall.health());
        float afterFriendlyFire = baseWall.health();
        check("基线 2：同队单位的子弹不会打坏己方建筑（原版敌我判定）",
            friendlyShots > 0 && afterFriendlyFire >= beforeBase - 0.01f);
        friendly.remove();
        baseWall.health(beforeBase);
        run(2);

        // ---------- 场景：己方半血墙 = "维修/建造目标" ----------
        Building wall = place(Blocks.copperWall, ox + 6, oy, Team.sharded);
        wall.health(wall.maxHealth() * 0.5f);
        Building enemyWall = place(Blocks.copperWall, ox + 6, oy + 5, Team.crux);
        run(10);
        System.out.println("[MRF] 场景: 己方半血墙=" + (int)wall.health() + "/" + (int)wall.maxHealth()
            + " 敌方墙=" + (int)enemyWall.health() + "/" + (int)enemyWall.maxHealth());

        // ---------- A：普通组合（不开巨兽）：开火者把"己方半血墙"当目标 ----------
        Unit shooterA = noAi(UnitTypes.dagger.create(Team.sharded), cx, cy);
        Unit lenderA  = noAi(UnitTypes.dagger.create(Team.sharded), cx + 30f, cy + 10f);
        call("combineunit.units.UnitComboDamage", "comboId", new Class<?>[]{Unit.class, double.class}, shooterA, 4321.0);
        call("combineunit.units.UnitComboDamage", "comboId", new Class<?>[]{Unit.class, double.class}, lenderA, 4321.0);
        run(5);
        float wallBeforeA = wall.health();
        // ① 目标模式：开火者的挂载 target 直接指向己方建筑（= 维修/建造武器或指令锁定的就是它）
        int aFriendly = shotsWhileTargeting(shooterA, lenderA, wall, 180, false);
        // ② 瞄准点模式：没有 target，只有瞄准点压在己方建筑上（玩家指着自己的房子长按）
        int aFriendlyAim = shotsWhileAiming(shooterA, lenderA, wall, 180);
        int aEnemy = shotsWhileTargeting(shooterA, lenderA, enemyWall, 180, false);
        System.out.println("[MRF] A 普通组合: target=己方墙 → 借出方射击次数+=" + aFriendly
            + "；aim=己方墙 → +" + aFriendlyAim + "；target=敌方墙 → +" + aEnemy
            + "（墙 " + (int)wallBeforeA + " → " + (int)wall.health() + "）");
        check("A 借出方的武器不会朝己方建筑代打（用户报的「损坏己方建筑」）", aFriendly == 0);
        check("A 瞄准点压在己方建筑上时也不代打", aFriendlyAim == 0);
        check("A 打敌方目标时照常代打（场景有效、功能没被砍掉）", aEnemy > 0);
        check("A 己方墙在整段过程里没被己方武器打掉血（" + (int)wallBeforeA + " → " + (int)wall.health() + "）",
            wall.health() >= wallBeforeA - 0.01f);

        // 诊断：把 UnitComboFire.update() 里那条判定链逐条打出来（哪一条不成立，一眼就能看出）
        try{
            Class<?> fire = Class.forName("combineunit.units.UnitComboFire", true, uml);
            Class<?> dmg = Class.forName("combineunit.units.UnitComboDamage", true, uml);
            java.lang.reflect.Method gidM = dmg.getMethod("comboId", Unit.class);
            java.lang.reflect.Method grpM = dmg.getMethod("groupable", Unit.class);
            for(WeaponMount m : shooterA.mounts()){ m.target = enemyWall; m.aimX = enemyWall.x; m.aimY = enemyWall.y; m.shoot = true; m.rotate = true; } shooterA.isShooting(true);
            for(WeaponMount m : lenderA.mounts()){ m.shoot = false; m.reload = 0f; }
            System.out.println("[MRF] 诊断: UnitComboFire 来自 "
                + fire.getProtectionDomain().getCodeSource().getLocation()
                + " hostileTo 存在=" + java.util.Arrays.stream(fire.getDeclaredMethods())
                    .anyMatch(mm -> mm.getName().equals("hostileTo"))
                + " friendlyBuildingAt 存在=" + java.util.Arrays.stream(fire.getDeclaredMethods())
                    .anyMatch(mm -> mm.getName().equals("friendlyBuildingAt")));
            System.out.println("[MRF] 诊断: fire.enabled=" + fire.getField("enabled").get(null)
                + " dmg.enabled=" + dmg.getField("enabled").get(null)
                + " state.isMenu=" + Vars.state.isMenu()
                + " net.client=" + Vars.net.client()
                + " 双方 comboId=" + gidM.invoke(null, shooterA) + "/" + gidM.invoke(null, lenderA)
                + " groupable=" + grpM.invoke(null, shooterA) + "/" + grpM.invoke(null, lenderA)
                + " isShooting=" + shooterA.isShooting() + "/" + lenderA.isShooting()
                + " 距离=" + (int)lenderA.dst(shooterA)
                + " range=" + dmg.getField("range").get(null)
                + " lender 挂载数=" + lenderA.mounts().length
                + " 第一把武器=" + (lenderA.type.weapons.size > 0 ? lenderA.type.weapons.first().getClass().getName() : "-")
                + " firstShotDelay=" + (lenderA.type.weapons.size > 0 ? lenderA.type.weapons.first().shoot.firstShotDelay : -1f)
                + " bullet=" + (lenderA.type.weapons.size > 0 && lenderA.type.weapons.first().bullet != null)
                + " heals=" + (lenderA.type.weapons.size > 0 && lenderA.type.weapons.first().bullet != null && lenderA.type.weapons.first().bullet.heals()));
        }catch(Throwable t){ System.out.println("[MRF] 诊断失败: " + t); }
        shooterA.remove(); lenderA.remove();
        run(5);

        // ---------- B：巨兽（mega + fortress）= 用户场景 ----------
        Seq<Unit> members = new Seq<>();
        for(UnitType t : new UnitType[]{UnitTypes.mega, UnitTypes.fortress}){
            Unit u = t.create(Team.sharded);
            u.set(cx, cy + (t == UnitTypes.mega ? 0f : 20f));
            u.add();
            members.add(u);
        }
        run(2);
        Unit beast = (Unit)mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, members);
        if(beast == null){ check("mega + fortress 能融合成巨兽（前置）", false); }
        else{
            check("mega + fortress 能融合成巨兽（前置）", true);
            beast.controller(new DummyController());      // 摘掉 AI：别让它每帧覆盖挂载状态
            beast.set(cx, cy);
            // 给巨兽排一个建造计划（mega 的建造武器会去认领这一格）
            try{ beast.addBuild(new BuildPlan(ox + 8, oy, 0, Blocks.copperWall)); }catch(Throwable t){ System.out.println("[MRF] 排计划失败: " + t); }
            Unit lenderB = noAi(UnitTypes.dagger.create(Team.sharded), cx + 30f, cy + 10f);
            call("combineunit.units.UnitComboDamage", "comboId", new Class<?>[]{Unit.class, double.class}, beast, 1234.0);
            call("combineunit.units.UnitComboDamage", "comboId", new Class<?>[]{Unit.class, double.class}, lenderB, 1234.0);
            run(5);
            System.out.println("[MRF] 巨兽挂载:" + mounts(beast));
            float wallBefore = wall.health();
            // 巨兽的"维修武器"命中己方半血墙（HealBeamMount 的目标是它自己的字段，WeaponMount.target
            // 仍是 null）→ 借火走的是"瞄准点"那一路；这里两种都量一遍（巨兽的瞄准点也压在墙上）
            int bFriendlyAim = shotsWhileAiming(beast, lenderB, wall, 240);
            int bFriendly = shotsWhileTargeting(beast, lenderB, wall, 180, false);
            System.out.println("[MRF] B 巨兽（含 mega）: aim=己方半血墙 → 借出方射击次数+=" + bFriendlyAim
                + "；target=己方半血墙 → +" + bFriendly
                + "（墙 " + (int)wallBefore + " → " + (int)wall.health() + "）");
            System.out.println("[MRF] B 结束时巨兽挂载:" + mounts(beast));
            check("B 巨兽修己方建筑时（瞄准点压在己方建筑上），同组其他武器不会朝己方建筑开火", bFriendlyAim == 0);
            check("B 巨兽把己方建筑当目标时，同组其他武器不会朝己方建筑开火", bFriendly == 0);
            check("B 己方半血墙没被己方武器打掉血（" + (int)wallBefore + " → " + (int)wall.health() + "）",
                wall.health() >= wallBefore - 0.01f);
            // 巨兽自己既不当借出方也不当借入方（groupable 明确排除 MegaUnitEntity），
            // 所以"巨兽身上的其他武器"只能由它自己的挂载开火 —— 这条要印出来，
            // 免得下次又把"巨兽"和"普通组合"的路径混在一起查。
            System.out.println("[MRF] 巨兽 groupable=" + comboGroupable(beast)
                + "（false = 巨兽不参与借火：既不被借武器，也不会借用别人的武器）");
        }

        // ---------- 附加事实：哪类武器能伤到**同队**建筑（原版只有 collidesTeam 的弹体） ----------
        StringBuilder ct = new StringBuilder();
        boolean anyCollidesTeam = false;
        for(UnitType t : Vars.content.units()){
            for(Weapon w : t.weapons){
                if(w.bullet != null && w.bullet.collidesTeam && w.bullet.damage > 0f && !w.bullet.heals()){
                    anyCollidesTeam = true;
                    if(ct.length() < 200) ct.append(t.name).append('/').append(w.bullet.getClass().getSimpleName()).append(' ');
                }
            }
        }
        System.out.println("[MRF] 数据目录里 \"能打到同队建筑的伤害弹体\"(collidesTeam+damage): "
            + (anyCollidesTeam ? ct.toString() : "无 —— 那么同队建筑只会因为敌队武器/环境受伤"));

        System.out.println("[MRF] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
