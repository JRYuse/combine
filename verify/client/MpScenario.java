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
    /** 给"客户端自己发起合体"用的那两只单位是否已经放好（独立于 phase 那条剧本链）。 */
    static boolean pairSpawned = false;
    static final Seq<Unit> pairUnits = new Seq<>();
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
                // 【逐 id 清单】run-mp.sh 拿两端的**最后一行**做集合比对：
                // 客户端多出来的 id = 幽灵（服务端没有这个实体），少的 = 没同步过去。
                StringBuilder ids = new StringBuilder();
                for(Unit u : Groups.unit){
                    if(u.team() != Team.sharded) continue;
                    int mc = memberCount(u);
                    ids.append(u.id()).append(":").append(mc);
                    if(mc > 0) ids.append(":").append(memberIds(u));
                    ids.append(" ");
                }
                // 末尾带 t= 秒 与玩家单位 id：脚本按 t= 对齐两端、并排除"玩家自己那只"
                // （客户端一退出，服务端立刻把它删掉，最后一行天然对不上）
                System.out.println("[MP-IDS] t=" + (frames / 60) + " player="
                    + (Groups.player.size() == 0 || Groups.player.first().unit() == null ? -1 : Groups.player.first().unit().id)
                    + " " + ids);
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
            // 【客户端自己发起的合体】t=8 秒时在别处放两只 dagger：等客户端通过命令面板那条
            // 入口（UnitComboMerge.requestMergeSelected）自己请求合体，复现"客户端合体变幽灵"。
            // 用一个独立开关，别去动 phase（phase 是下面那条剧本链的状态机）。
            if(!pairSpawned && t >= 8){ pairSpawned = true; spawnClientMergePair(); }
            if(phase == 0 && t >= 20){ phase = 1; splitMega(); }
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

    /** 某一格周围 7×7 是不是干净陆地（放"客户端合体用"的单位前先确认，别丢水里）。 */
    static boolean cleanAt(int x, int y){
        for(int dy = -3; dy <= 3; dy++){
            for(int dx = -3; dx <= 3; dx++){
                mindustry.world.Tile t = Vars.world.tile(x + dx, y + dy);
                if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != mindustry.content.Blocks.air) return false;
            }
        }
        return true;
    }

    /**
     * 放两只 dagger（**不融合**）：等**客户端**通过命令面板那条入口
     * （{@code UnitComboMerge.requestMergeSelected}）自己请求合体。
     * 用来复现/回归"客户端自己发起合体后会留下幽灵单位"。
     */
    static void spawnClientMergePair(){
        try{
            float[] at = landSpot();
            int mx = (int)(at[0] / 8f), my = (int)(at[1] / 8f);
            int px = -1, py = -1;
            outer:
            for(int y = 45; y < 200; y++){
                for(int x = 40; x < 250; x++){
                    // 离剧本那堆单位远一点，免得被剧本的框选/合体顺手卷进去
                    if(Math.abs(x - mx) < 30 && Math.abs(y - my) < 30) continue;
                    if(cleanAt(x, y)){ px = x; py = y; break outer; }
                }
            }
            if(px < 0){ System.out.println("[MP-HOST] 没找到给客户端合体用的空地"); return; }

            pairUnits.clear();
            for(int i = 0; i < 2; i++){
                Unit u = UnitTypes.dagger.create(Team.sharded);
                u.set(px * 8f + i * 20f, py * 8f);
                u.add();
                pairUnits.add(u);
            }
            StringBuilder sb = new StringBuilder();
            for(Unit u : pairUnits) sb.append(u.id()).append(" ");
            System.out.println("[MP-HOST] 已放好客户端合体用成员 id=" + sb + "（位置 " + px + "," + py + "）");
        }catch(Throwable t){
            System.out.println("[MP-HOST] spawnClientMergePair 失败: " + t);
        }
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

    /** 巨兽的成员 id 列表（逗号分隔）；不是巨兽返回空串（脚本用它查幽灵成员）。 */
    static String memberIds(Unit u){
        try{
            Object seq = u.getClass().getMethod("members").invoke(u);
            StringBuilder sb = new StringBuilder();
            for(Object up : (Iterable<?>)seq){
                if(up == null) continue;
                Unit m = (Unit)up.getClass().getField("unit").get(up);
                if(m == null) continue;
                if(sb.length() > 0) sb.append(',');
                sb.append(m.id());
            }
            return sb.toString();
        }catch(Throwable t){
            return "";
        }
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
