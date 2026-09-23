package drv;
import arc.*; import arc.struct.Seq; import arc.util.*;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.gen.Building;
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
    /** 客户端连上那一刻的墙上时间：剧本节奏按**真实秒**走，别按帧数 —— 服务端帧率会被
     *  客户端的软渲染抢 CPU 拖慢，而客户端的 Timer 是按帧算的，两边会越漂越远
     *  （实测服务端只跑到 t=33 时客户端已经跑满 110 秒退出了，收尾的挖矿阶段根本没轮到）。 */
    static long startMs = 0;
    /** 给"客户端自己发起合体"用的那两只单位是否已经放好（独立于 phase 那条剧本链）。 */
    static boolean pairSpawned = false;
    /** 大编组（20 只）是否已经合过（见 update 里的"用户报的 bug"注释）。 */
    static boolean bigMerged = false;
    static final Seq<Unit> pairUnits = new Seq<>();
    static float stateTimer = 0f;
    static final Seq<String> seen = new Seq<>();
    static ClassLoader ml = MpScenario.class.getClassLoader();

    /** 单位侧机制（组合巨兽/共享承伤…）已经拆到 combineunit 模组里：优先用它的类加载器；
     *  没装就退回本驱动自己的（联机的单位侧剧本需要两端都装 combineunit，见 verify/README.md）。 */
    static ClassLoader unitMl(){
        try{
            var m = Vars.mods.getMod("combineunit");
            if(m != null && m.main != null) return m.main.getClass().getClassLoader();
        }catch(Throwable ignored){}
        return ml;
    }


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
                // 时间戳用 **epoch 毫秒**：两端在同一台机器上，脚本按毫秒对齐"同一时刻"的实体清单
                //（服务端按墙上时间排剧本、客户端按帧计时，两边各自的"游戏秒"不是同一个钟）。
                System.out.println("[MP-IDS] ms=" + System.currentTimeMillis() + " player="
                    + (Groups.player.size() == 0 || Groups.player.first().unit() == null ? -1 : Groups.player.first().unit().id)
                    + " " + ids);
                // 【物品总量】按**模块身份**去重（共享池只算一次）：两端各打一行，脚本按毫秒对齐比对。
                // 用户报的"组合节点瞎连导致物品涨到 11m / 变负数"就是这一步该抓的。
                System.out.println("[MP-ITEMS] ms=" + System.currentTimeMillis() + " total=" + worldItemTotal());
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
                startMs = System.currentTimeMillis();
                System.out.println("[MP-HOST] 客户端已连接，3 秒后开始剧本");
                return;
            }
            if(frames % 60 != 0) return;
            // 剧本节奏用墙上时间（客户端连上开始算）
            int t = (int)((System.currentTimeMillis() - startMs) / 1000L);
            if(phase < 0 && t >= 5){
                // 剧本要一次造 20 只：默认 unitCap 会把第 9 只起直接 unitCapDeath（测试前提）
                Vars.state.rules.disableUnitCap = true;
                phase = 0; spawnAndMerge(2, true);
            }
            // 【客户端自己发起的合体】t=8 秒时在别处放两只 dagger：等客户端通过命令面板那条
            // 入口（UnitComboMerge.requestMergeSelected）自己请求合体，复现"客户端合体变幽灵"。
            // 用一个独立开关，别去动 phase（phase 是下面那条剧本链的状态机）。
            if(!pairSpawned && t >= 8){ pairSpawned = true; spawnClientMergePair(); }
            if(phase == 0 && t >= 20){ phase = 1; splitMega(); }
            else if(phase == 1 && t >= 28){ phase = 2; spawnAndMerge(3, false); }
            else if(phase == 2 && t >= 43){ phase = 3; splitMega(); }
            else if(phase == 3 && t >= 52){ phase = 4; System.out.println("[MP-HOST] 剧本结束"); }
            // 【挖矿光束取证】最后再合一只**矿工巨兽**并让它当场挖矿：客户端截图里应当看得见
            // 原版那条挖矿激光（用户报的"合体后挖矿没有挖矿光束"）。
            else if(phase == 4 && t >= 58){ phase = 5; spawnMiningMega(); }
            // 【节点乱连取证】用户报"组合节点瞎连 → 物品异常增长/减少（11m、负数）"。
            // 摆一个"只会被连来连去、不消耗物品"的小基地（只给铜，在场工厂的配方都不吃铜），
            // 之后每秒随机连/断一根线；两端各自报世界物品总量，脚本比对（涨/跌/两端不一致都 FAIL）。
            else if(phase == 5 && t >= 72){ phase = 6; buildNodeMess(); }
            else if(phase == 6 && t < 74){ /* 静默期：等基地摆好、物品注入生效 */ }
            else if(phase == 6 && t == 74){
                // 让世界静下来再测：把矿工巨兽的 mineTile 停掉（它 10.5/s 的产量会把"守恒"淹掉）
                if(miningMega != null && miningMega.isAdded()) miningMega.mineTile(null);
                miningTile = null;
                System.out.println("[MP-HOST] 已停止挖矿，物品总量进入静默期（基线 " + worldItemTotal() + "）");
            }
            else if(phase == 6 && t >= 76){ /* 连/断由**客户端**在做（见 Driver.mpNodeMess），服务端只摆基地 */ }
            // 【用户报的 bug】一次合 20 只（快照 ~3.4KB）：客户端应当照样看得到这只大单位
            // （看不到 = 快照没同步过去，harness 的"服务端每种状态客户端都看到"会 FAIL）。
            // 【用户报的 bug】一次合 20 只：客户端应当照样看得到这只大单位（看不到 = 快照没同步
            // 过去/包太大，harness 的"服务端每种状态客户端都看到"会 FAIL）。用独立开关，
            // 别去动 phase 链（免得打乱后面挖矿/节点取证的时间点）。
            if(!bigMerged && t >= 40){ bigMerged = true; spawnAndMerge(20, true); }
            // 矿工巨兽的 mineTile 会被它自己的 AI 清掉（原版 CommandAI 发现目标不在射程里就清），
            // 这里每 tick 重新钉住，保证客户端那边**一直是挖矿状态**（截图才有光束可看）。
            if(miningMega != null && miningMega.isAdded() && miningTile != null){
                miningMega.mineTile(miningTile);
            }
            // 【操控取证】"身上有多少物品"只有 `unit.isLocal()`（= 玩家正在操控它）时才画数字，
            // 所以挖矿几秒后把这只巨兽交给玩家操控（原版接管机制 Call.unitControl），
            // 客户端截图里才看得到那串数量（用户报的就是"操控组合巨兽时不显示身上有多少物品"）。
            if(miningMega != null && miningMega.isAdded() && !mineControlGiven && startMs > 0
                && System.currentTimeMillis() - startMs > 66_000L){
                mineControlGiven = true;
                try{
                    Vars.state.rules.possessionAllowed = true;
                    mindustry.gen.Call.unitControl(Groups.player.first(), miningMega);
                    System.out.println("[MP-HOST] 已把矿工巨兽 @" + miningMega.id() + " 交给玩家操控（物品="
                        + miningMega.stack().amount + "）");
                }catch(Throwable ex){
                    System.out.println("[MP-HOST] 接管巨兽失败: " + ex);
                }
            }
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
            Class<?> mergeCls = Class.forName("combineunit.units.UnitComboMerge", true, unitMl());
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

    /** 正在挖矿的巨兽（客户端截图取证用）。 */
    static Unit miningMega;
    static mindustry.world.Tile miningTile;
    static boolean mineControlGiven = false;
    /** "节点乱连"取证用的小基地 + 起始物品总量。 */
    static final Seq<Building> messBuildings = new Seq<>();
    static final Seq<Building> messNodes = new Seq<>();
    static int messStartTotal = -1;

    /**
     * 世界物品总量：按**模块身份**去重（共享池只算一次）。
     * 用户报的"组合节点瞎连导致物品涨到 11m / 变负数"，在这一项上表现为总数暴涨/变负。
     */
    static int worldItemTotal(){
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        int total = 0;
        // 必须按**世界格**遍历：Groups.build 在有些时机并不全（实测漏过刚摆下的容器，
        // 于是"总量"读数凭空虚低，看着像物品凭空多出来）
        for(mindustry.world.Tile t : Vars.world.tiles){
            Building b = t == null ? null : t.build;
            if(b == null || b.items == null || seen.put(b.items, Boolean.TRUE) != null) continue;
            for(mindustry.type.Item it : Vars.content.items()) total += b.items.get(it);
        }
        return total;
    }

    static Block byName(String name){
        for(Block b : Vars.content.blocks()){
            if(b.name.equals(name)) return b;
        }
        for(Block b : Vars.content.blocks()){
            if(b.name.endsWith("-" + name) && !b.getClass().getName().startsWith("mindustry.")) return b;
        }
        return null;
    }
    static Block nodeBlock(){
        for(Block b : Vars.content.blocks())
            if(b.getClass().getName().contains("ComboNode")) return b;
        return null;
    }
    static void placeBlock(Block b, int x, int y){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null, b, Team.sharded, ax, ay, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte)0, Team.sharded, null);
        Building bu = Vars.world.build(ax, ay);
        if(bu != null){
            try{ bu.created(); }catch(Throwable ignored){}
            try{ bu.updateProximity(); }catch(Throwable ignored){}
        }
    }
    static boolean areaFree(Block b, int x, int y){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        int x0 = ax - (size - 1) / 2, x1 = ax + size / 2, y0 = ay - (size - 1) / 2, y1 = ay + size / 2;
        for(int tx = x0; tx <= x1; tx++) for(int ty = y0; ty <= y1; ty++){
            mindustry.world.Tile t = Vars.world.tile(tx, ty);
            if(t == null || t.block() != mindustry.content.Blocks.air) return false;
        }
        return true;
    }

    /** 摆"节点乱连"基地：一个组合仓库 + 三台组合工厂 + 两个组合节点，只灌铜（工厂配方不吃铜，不会动）。 */
    static void buildNodeMess(){
        try{
            float[] at = landSpot();
            int bx = (int)(at[0] / 8f) + 12, by = (int)(at[1] / 8f);
            Block cont = byName("container"), node = nodeBlock();
            Seq<Block> facts = new Seq<>();
            for(String n : new String[]{"graphite-press", "silicon-smelter", "kiln"}){
                Block f = byName(n);
                if(f != null) facts.add(f);
            }
            if(cont == null || node == null || facts.isEmpty()){
                System.out.println("[MP-HOST] 找不到组合仓库/组合节点/组合工厂，跳过节点乱连取证（cont=" + cont
                    + " node=" + node + " facts=" + facts.size + "）");
                return;
            }
            // 先把这块清空，保证能摆下
            for(int y = by - 8; y <= by + 8; y++) for(int x = bx - 3; x <= bx + 14; x++){
                mindustry.world.Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != mindustry.content.Blocks.air) t.setBlock(mindustry.content.Blocks.air);
            }
            run2();
            int cx = bx, cy = by;
            if(areaFree(cont, cx, cy)){ placeBlock(cont, cx, cy); messBuildings.add(Vars.world.build(cx + (cont.size - 1) / 2, cy + (cont.size - 1) / 2)); }
            int fx = bx + 5;
            for(Block f : facts){
                if(areaFree(f, fx, cy)){ placeBlock(f, fx, cy); messBuildings.add(Vars.world.build(fx + (f.size - 1) / 2, cy + (f.size - 1) / 2)); }
                fx += 4;
            }
            for(int i = 0; i < 2; i++){
                int nx = bx - 2 + i * 2, ny = cy + 4;
                if(areaFree(node, nx, ny)){
                    placeBlock(node, nx, ny);
                    Building nb = Vars.world.build(nx, ny);
                    if(nb != null){ messNodes.add(nb); messBuildings.add(nb); }
                }
            }
            run2();
            run2();
            // 只灌铜：在场工厂（石墨压机/硅冶炼厂/窑）都不吃铜，所以总量必须是死的
            Building store = messBuildings.isEmpty() ? null : messBuildings.first();
            if(store != null && store.items != null) store.items.add(mindustry.content.Items.copper, 5000);
            messStartTotal = worldItemTotal();
            System.out.println("[MP-HOST] 节点乱连基地就位：建筑=" + messBuildings.size + " 节点=" + messNodes.size
                + " 起始物品总量=" + messStartTotal);
        }catch(Throwable t){
            System.out.println("[MP-HOST] buildNodeMess 失败: " + t);
        }
    }

    /** 每秒随机连/断一根线（模拟玩家"瞎连"）。 */
    static void nodeMessStep(){
        try{
            if(messNodes.isEmpty()) return;
            Building nd = messNodes.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(messNodes.size));
            if(nd == null || !nd.isValid()) return;
            Seq<Building> cand = new Seq<>();
            for(Building b : messBuildings){
                if(b == null || b == nd || !b.isValid()) continue;
                if(b.dst(nd) < 120f) cand.add(b);
            }
            if(cand.isEmpty()) return;
            Building target = cand.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(cand.size));
            nd.onConfigureBuildTapped(target);
            run2();
            int total = worldItemTotal();
            System.out.println("[MP-ITEMS-STEP] ms=" + System.currentTimeMillis() + " 连线切换 "
                + nd.tileX() + "," + nd.tileY() + " ↔ " + target.block.name + "@" + target.tileX() + "," + target.tileY()
                + " 总量=" + total + "（起始 " + messStartTotal + "）");
        }catch(Throwable t){
            System.out.println("[MP-HOST] nodeMessStep 失败: " + t);
        }
    }

    /**
     * 合一只矿工巨兽并让它当场挖矿：找一块"矿格旁边有干净落脚点"的地方，
     * 用 poly（工程/采矿单位）×3 融合，然后把 {@code mineTile} 指到那格矿上。
     *
     * <p>客户端截图要能看见**原版那条挖矿激光**（用户报的"合体后挖矿没有挖矿光束"）。
     */
    static void spawnMiningMega(){
        try{
            int ox = -1, oy = -1, mx = -1, my = -1;
            outer:
            for(int y = 45; y < 200; y++){
                for(int x = 40; x < 250; x++){
                    mindustry.world.Tile t = Vars.world.tile(x, y);
                    if(t == null || t.block() != mindustry.content.Blocks.air || t.drop() == null
                        || t.drop().hardness > UnitTypes.poly.mineTier) continue;
                    // 站到离矿 **5~8 格**的干净陆地上：poly 的 mineRange=70px（≈8.75 格）够得着，
                    // 光束又足够长、截图里一眼能看见（太近时激光会被单位自己挡住）
                    for(int r = 5; r <= 8 && ox < 0; r++){
                        for(int dy = -r; dy <= r; dy++) for(int dx = -r; dx <= r; dx++){
                            if(Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                            if(cleanAt(x + dx, y + dy)){ ox = x + dx; oy = y + dy; mx = x; my = y; break; }
                        }
                    }
                    if(ox >= 0) break outer;
                }
            }
            if(ox < 0){ System.out.println("[MP-HOST] 没找到带矿的落脚点，跳过挖矿取证"); return; }

            Seq<Unit> units = new Seq<>();
            for(int i = 0; i < 3; i++){
                Unit u = UnitTypes.poly.create(Team.sharded);
                u.set(ox * 8f + i * 16f, oy * 8f);
                u.add();
                units.add(u);
            }
            run2();
            Class<?> mergeCls = Class.forName("combineunit.units.UnitComboMerge", true, unitMl());
            miningMega = (Unit)mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, units);
            if(miningMega == null){ System.out.println("[MP-HOST] 矿工巨兽融合失败"); return; }
            run2();
            miningMega.set(ox * 8f, oy * 8f);
            miningTile = Vars.world.tile(mx, my);
            miningMega.mineTile(miningTile);
            System.out.println("[MP-HOST] 矿工巨兽 id=" + miningMega.id() + " 成员=" + memberCount(miningMega)
                + " itemCapacity=" + miningMega.type.itemCapacity + " mineSpeed=" + miningMega.type.mineSpeed
                + " mineBeamOffset=" + miningMega.type.mineBeamOffset
                + " 挖矿格=" + mx + "," + my + " 位置=" + (int)miningMega.x + "," + (int)miningMega.y);
            System.out.println("[MP-PHASE] mining " + state());
        }catch(Throwable t){
            System.out.println("[MP-HOST] spawnMiningMega 失败: " + t);
        }
    }

    /** 无参版本的 run(1)（上面几个剧本方法用不到固定帧数时用）。 */
    static void run2(){
        arc.util.Time.delta = 1f;
        Vars.logic.update();
    }

    static void splitMega(){
        try{
            Unit mega = mega();
            if(mega == null){ System.out.println("[MP-HOST] 剧本：没有巨兽可解体"); return; }
            Class<?> mergeCls = Class.forName("combineunit.units.UnitComboMerge", true, unitMl());
            Object ok = mergeCls.getMethod("split", Unit.class).invoke(null, mega);
            System.out.println("[MP-HOST] 剧本：解体 = " + ok);
            System.out.println("[MP-PHASE] split " + state());
        }catch(Throwable t){
            System.out.println("[MP-HOST] splitMega 失败: " + t);
        }
    }

    static Unit mega(){
        for(Unit u : Groups.unit)
            if(u.team() == Team.sharded && u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity")) return u;
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
            if(u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity")){ mega++; members += memberCount(u); }
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
