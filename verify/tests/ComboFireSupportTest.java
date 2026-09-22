package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.*; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："dagger 和 vela 普通组合后会发射 vela 治疗武器的子弹，而且碰撞箱好像变了"。
 *
 * 根因：组合火力共享（{@link combine.units.UnitComboFire}）会把组内空闲成员的武器借给正在开火的成员代打。
 * 原来的过滤只排除"纯治疗弹"（{@code bullet.heals() && damage <= 0}），于是 vela 那种
 * "伤害 + 治疗双用"的主力激光（healPercent + collidesTeam）也会被借出去 ——
 * dagger 开火时就从自己身上发射 vela 的治疗激光，看着就是"普通单位在发射治疗武器的子弹"，
 * 而且那束激光的命中范围/形状跟 dagger 自己的枪完全不是一回事（"碰撞箱好像变了"）。
 *
 * 这个测试：dagger + vela 编成组合 → 逼 dagger 开火 60 tick →
 *   1) 场上不能出现 vela 那种"带治疗"的弹体；
 *   2) 同时 dagger 自己的普通子弹必须还在（证明场景真的在开火，不是假过）。
 */
public class ComboFireSupportTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[CF] "+t); };
        new HeadlessApplication(new ComboFireSupportTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[CF] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static ClassLoader ml(){ return Vars.mods.getMod("combine").main.getClass().getClassLoader(); }

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
        for(int i = 0; i < 20; i++){ arc.util.Time.delta = 1f; Vars.logic.update(); }
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        for(int i = 0; i < 5; i++){ arc.util.Time.delta = 1f; Vars.logic.update(); }

        int ox = -1, oy = -1;
        outer:
        for(int y=45;y<140;y++){
            for(int x=40;x<200;x++){
                boolean ok = true;
                for(int dy=-2;dy<=2 && ok;dy++) for(int dx=-3;dx<=3;dx++){
                    Tile t = Vars.world.tile(x+dx, y+dy);
                    if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                }
                if(ok){ ox = x; oy = y; break outer; }
            }
        }
        if(ox < 0){ System.out.println("[CF] 没找到陆地"); System.exit(3); }
        float cx = ox * 8f, cy = oy * 8f;
        // 队伍得有核心，不然单位被清掉
        mindustry.world.Build.beginPlace(null, Blocks.coreShard, Team.sharded, ox + 16, oy + 10, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ox + 16, oy + 10), Blocks.coreShard, null, (byte)0, Team.sharded, null);
        run(10);

        Unit dagger = UnitTypes.dagger.create(Team.sharded);
        dagger.set(cx - 15f, cy);
        dagger.add();
        Unit vela = UnitTypes.vela.create(Team.sharded);
        vela.set(cx + 15f, cy);
        vela.add();
        // 靶子放在 dagger 射程内（120 单位）：这样 dagger 的 AI 会自己开火。
        // vela 靠"停火姿态"变空闲（见下），否则它射程更远、一定自己先开火，轮不到代打。
        // 靶子放 70 单位（近到 dagger 的 AI 一定开火；vela 靠停火姿态保持空闲）。
        // 血量拉满天的血量：别让它在测量窗口里被 dagger 打死（打死了就没目标、也就不开火了）。
        Unit enemy = UnitTypes.dagger.create(Team.crux);
        enemy.set(cx + 70f, cy);
        enemy.add();
        enemy.maxHealth(1e9f);
        enemy.health(1e9f);
        run(20);

        // 编成组合（= 普通组合，不融合）
        try{
            Class<?> uc = Class.forName("combine.units.UnitComboDamage", true, ml());
            uc.getMethod("combineWith", Unit.class, Unit.class).invoke(null, dagger, vela);
        }catch(Throwable t){ Log.err("[CF] combineWith 失败", t); }
        run(10);
        System.out.println("[CF] 组合: dagger 组=" + comboId(dagger) + " vela 组=" + comboId(vela)
            + "（同组=" + (comboId(dagger) == comboId(vela)) + "）");
        check("dagger 和 vela 成功编成一个组合", comboId(dagger) != 0.0 && comboId(dagger) == comboId(vela));

        // 代打只在"借出方空闲"时发生：vela 本身射程比 dagger 远，只要给它目标它就会自己开火，
        // 永远轮不到代打。所以这里把两台单位的控制器都摘掉（vela 不会自己开火 = 空闲），
        // 再由测试每帧替 dagger 按住扳机（没有 AI 覆盖，mount.shoot 能保持住）。
        // vela 挂"停火"姿态：它自己就不开火 = 空闲 = 会被代打借武器
        try{
            if(vela.controller() instanceof mindustry.ai.types.CommandAI ai){
                ai.setStance(mindustry.ai.UnitStance.holdFire);
            }
        }catch(Throwable t){ Log.err("[CF] 给 vela 设停火失败", t); }

        // vela 刚才自己打了一发，被动武器的冷却还没走完（激光 reload=155）——测试里直接给它清零，
        // 否则这 60 tick 内它"没装填好"，代打也借不走（测出来会假通过）。
        if(vela.mounts() != null) for(var m : vela.mounts()) m.reload = 0f;

        // 逼 dagger 开火（朝着远处靶子方向，手动瞄具武器不看目标也能打），跑 60 tick
        // 逐帧统计 dagger 自己"发出过"的弹体（子弹寿命很短，只查最后那一帧会漏）
        int healing = 0, normal = 0;
        int shotsBefore = totalShots(dagger);
        System.out.println("[CF] 测量前: dagger 存活=" + dagger.isValid() + " 会开火=" + dagger.isShooting()
            + " 敌方存活=" + enemy.isValid() + " 血=" + (int)enemy.health()
            + " 组内距离=" + (int)dagger.dst(enemy));
        for(int tick = 0; tick < 60; tick++){
            if(dagger.mounts() != null) for(var m : dagger.mounts()){
                m.aimX = enemy.x; m.aimY = enemy.y; m.shoot = true; m.rotate = true;
            }
            arc.util.Time.delta = 1f;
            Vars.logic.update();
            for(Bullet b : Groups.bullet){
                if(b == null || b.type == null || b.owner != dagger) continue;
                if(b.type.heals()) healing++;
                else if(b.type.damage > 0f) normal++;
            }
        }
        int shotsAfter = totalShots(dagger);
        System.out.println("[CF] 60 tick 内 dagger 发出的弹体: 带治疗帧数=" + healing + " 普通伤害帧数=" + normal
            + "（射击次数 " + shotsBefore + " → " + shotsAfter + "，场上弹体总数=" + Groups.bullet.size() + "）");
        check("没有借出 vela 的治疗类武器（带治疗的弹体必须为 0）", healing == 0);
        check("dagger 自己的武器照常开火（射击次数增加，证明场景有效）", shotsAfter > shotsBefore);

        System.out.println("[CF] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }

    /** 这台单位所有挂座的累计射击次数。 */
    static int totalShots(Unit u){
        int n = 0;
        if(u.mounts() != null) for(var m : u.mounts()) n += m.totalShots;
        return n;
    }

    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static double comboId(Unit u){
        try{ return (Double)Class.forName("combine.units.UnitComboDamage", true, ml()).getMethod("comboId", Unit.class).invoke(null, u); }
        catch(Throwable t){ return -1; }
    }
}
