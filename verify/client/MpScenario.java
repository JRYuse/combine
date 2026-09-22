package drv;
import arc.*; import arc.struct.Seq; import arc.util.*;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

/**
 * 联机验证的**服务端剧本**（跑在官方 server-release.jar 里，`-Ddrv.scenario=1` 打开）：
 * 等客户端进来 → 造单位 → 融合成组合巨兽 → 解体 → 再融合（成员更多）。
 *
 * 每秒打印一行世界快照，格式和客户端 {@code mode=mp} 完全一致：
 * <pre>
 *   [MP-STATE] mega=1 members=2 daggers=0 fortresses=1 octs=0 units=2
 * </pre>
 * {@code verify/run-mp.sh} 把服务端出现过的每种状态和客户端观测到的状态做包含比对：
 * 客户端少看到任何一种（= 没同步过去/幽灵/看不见）就算失败。
 */
public class MpScenario{
    static boolean installed = false, started = false;
    static int frames = 0, phase = -1;
    static float stateTimer = 0f;
    static final Seq<String> seen = new Seq<>();
    static ClassLoader ml = MpScenario.class.getClassLoader();

    /** -Ddrv.scenario=1 时挂上（服务端专用）。 */
    public static void install(){
        if(System.getProperty("drv.scenario") == null || installed) return;
        installed = true;
        Log.info("[MP-HOST] 服务端剧本已挂上（等客户端进来）");
        Events.run(EventType.Trigger.update, MpScenario::update);
    }

    static void update(){
        try{
            if(Vars.state == null || !Vars.state.isGame()) return;
            frames++;

            stateTimer += 1f;
            if(stateTimer >= 60f){
                stateTimer = 0f;
                String s = state();
                if(!seen.contains(s)) seen.add(s);
                System.out.println("[MP-STATE] " + s + " 链路=" + link());
                // 逐个单位的明细默认关着（一秒 4 行×单位数，正常跑一次就刷几千行）；
                // 排查"客户端少看到哪个单位"时用服务端加 -Ddrv.mpVerbose=1 打开。
                if("1".equals(System.getProperty("drv.mpVerbose"))){
                    for(Unit u : Groups.unit){
                        System.out.println("[MP-DBG] 单位 id=" + u.id + " 类=" + u.getClass().getName()
                            + " 类型=" + (u.type == null ? "null" : u.type.name)
                            + " 队伍=" + (u.team() == null ? "null" : u.team().name)
                            + " 位置=" + (int)u.x + "," + (int)u.y
                            + " 血量=" + (int)u.health + "/" + (int)u.maxHealth
                            + " hit=" + (int)u.hitSize + " members=" + memberCount(u));
                    }
                }
            }

            if(Groups.player.size() == 0) return;
            if(!started){
                started = true;
                frames = 0;
                System.out.println("[MP-HOST] 客户端已连接，3 秒后开始剧本");
                return;
            }
            if(frames % 60 != 0) return;
            int t = frames / 60;
            if(phase < 0 && t >= 5){ phase = 0; spawnAndMerge(2, true); }
            else if(phase == 0 && t >= 20){ phase = 1; splitMega(); }
            else if(phase == 1 && t >= 28){ phase = 2; spawnAndMerge(3, false); }
            else if(phase == 2 && t >= 43){ phase = 3; splitMega(); }
            else if(phase == 3 && t >= 52){ phase = 4; System.out.println("[MP-HOST] 剧本结束"); }
        }catch(Throwable t){
            Log.err("[MP-HOST] 剧本异常", t);
        }
    }

    static void spawnAndMerge(int n, boolean mechOnly){
        try{
            Seq<Unit> units = new Seq<>();
            float[] at = landSpot();
            float x = at[0], y = at[1];
            for(int i = 0; i < n; i++){
                UnitType type = mechOnly ? (i % 2 == 0 ? UnitTypes.dagger : UnitTypes.fortress)
                    : (i % 3 == 0 ? UnitTypes.dagger : (i % 3 == 1 ? UnitTypes.fortress : UnitTypes.oct));
                Unit u = type.create(Team.sharded);
                u.set(x + i * 24f, y);
                u.add();
                units.add(u);
            }
            Class<?> mergeCls = Class.forName("combine.units.UnitComboMerge", true, ml);
            Unit mega = (Unit)mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, units);
            System.out.println("[MP-HOST] 剧本：造 " + n + " 只 → 融合 " + (mega == null ? "失败" : ("成功 members=" + memberCount(mega) + " id=" + mega.id())));
            if(mega != null) System.out.println("[MP-PHASE] merge" + n + " " + state());
        }catch(Throwable t){
            System.out.println("[MP-HOST] spawnAndMerge 失败: " + t);
        }
    }

    /**
     * 找一块**地图内**的干净陆地当出生点（像素坐标）。
     *
     * 这里踩过一个坑：早先写的是 {@code Vars.world.unitWidth() * 8f * 0.25f}，而
     * {@code unitWidth()} 返回的**已经是像素**，再乘 8 就是两倍地图宽 —— 单位直接生在
     * 地图外，原版当场把它按"环境死亡"清掉。表现是"服务端融合成功、1 秒后巨兽就没了、
     * 客户端当然也看不到"，看着像同步 bug，其实是剧本把单位生到了地图外。
     */
    static float[] landSpot(){
        int w = Vars.world.width(), h = Vars.world.height();
        for(int y = h / 4; y < h * 3 / 4; y++){
            for(int x = w / 4; x < w * 3 / 4; x++){
                boolean ok = true;
                for(int dy = -3; dy <= 3 && ok; dy++){
                    for(int dx = -3; dx <= 3; dx++){
                        mindustry.world.Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != mindustry.content.Blocks.air){
                            ok = false;
                            break;
                        }
                    }
                }
                if(ok) return new float[]{x * 8f, y * 8f};
            }
        }
        return new float[]{w * 8f / 2f, h * 8f / 2f};
    }

    static void splitMega(){
        try{
            Unit mega = mega();
            if(mega == null){ System.out.println("[MP-HOST] 剧本：没有巨兽可解体"); return; }
            Class<?> mergeCls = Class.forName("combine.units.UnitComboMerge", true, ml);
            Object ok = mergeCls.getMethod("split", Unit.class).invoke(null, mega);
            System.out.println("[MP-HOST] 剧本：解体 = " + ok);
            System.out.println("[MP-PHASE] split " + state());
        }catch(Throwable t){
            System.out.println("[MP-HOST] splitMega 失败: " + t);
        }
    }

    static Unit mega(){
        for(Unit u : Groups.unit)
            if(u.team() == Team.sharded && u.getClass().getName().equals("combine.units.mega.MegaUnitEntity")) return u;
        return null;
    }

    static int memberCount(Unit u){
        try{ return (Integer)u.getClass().getMethod("memberCount").invoke(u); }catch(Throwable t){ return -1; }
    }

    static String state(){
        int mega = 0, members = 0, daggers = 0, fortresses = 0, octs = 0, units = 0;
        for(Unit u : Groups.unit){
            if(u.team() != Team.sharded) continue;
            units++;
            if(u.getClass().getName().equals("combine.units.mega.MegaUnitEntity")){ mega++; members += memberCount(u); }
            if(u.type == UnitTypes.dagger) daggers++;
            if(u.type == UnitTypes.fortress) fortresses++;
            if(u.type == UnitTypes.oct) octs++;
        }
        return "mega=" + mega + " members=" + members + " daggers=" + daggers
            + " fortresses=" + fortresses + " octs=" + octs + " units=" + units;
    }

    /** 本次链路模拟的参数（真链路模拟在 verify/lagnet.py 那个代理里，这里只是把参数记进日志）。 */
    static String link(){
        String l = System.getProperty("drv.latency", "0"), o = System.getProperty("drv.loss", "0");
        return "延迟" + l + "ms/丢包" + o + "%";
    }

}
