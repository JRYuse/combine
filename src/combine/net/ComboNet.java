package combine.net;
import combine.net.ComboConnector.ComboConnectorBuild;
import combine.net.ComboNode.ComboNodeBuild;
import combine.Main;
import combine.coop.CoopCombo;
import combine.storage.CombinedStorageBlock.CombinedStorageBuild;
import combine.storage.CombinedStorageBlock;
import combine.util.ComboReflect;
import combine.util.ComboUi;
import arc.struct.IntFloatMap;
import arc.struct.IntSet;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import arc.util.Strings;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.blocks.heat.HeatBlock;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import java.util.IdentityHashMap;

import static mindustry.Vars.content;
import static mindustry.Vars.state;
import static mindustry.Vars.world;

/**
 * 组合连接器 / 组合节点的跨组合体网络。
 *
 * 现有 Combined* 建筑各自维护“直接相邻”的本地组合体；这里把多个本地组合体
 * 通过连接器或节点连成一个网络，统一共享物品/液体模块，并保持各建筑自己的
 * leader 选举和序列化机制不变。
 *
 * 电力走原版电网：连接器是 conductivePower 导体，节点的 links 会由
 * ComboNodeBuild 同步到原版 PowerModule.links。
 */
public class ComboNet {
    private static final IntFloatMap heatAlloc = new IntFloatMap();
    private static long heatFrame = -1L;
    /**
     * 读档窗口标记。
     *
     * 存档时网络里所有成员写的是同一个池模块，读档后每个成员各自拿到一份副本；
     * 这期间（endMapLoad 的 proximity 刷新 + WorldLoadEvent）出现的任何合并都必须
     * 按“去重”处理，一旦当成两份真库存相加，池子就会翻倍。
     */
    private static boolean loadingWorld = false;

    /**
     * 延迟重建标记。
     *
     * 【性能】放一个连接器/节点，会连着触发 placed()、自己和四周邻居的 onProximityUpdate()、
     * updateTile() 里的 linkHash 变化、还有 Main 的 TileChangeEvent —— 一次放置能叫 5~10 遍
     * rebuild()。每遍都要把全图建筑过一遍、重建网络图，几百栋建筑时单遍就是几毫秒，
     * 叠起来就是"在组合体之间放连接器那一瞬间疯狂掉帧"。
     * 现在这些事件只置脏标记，真正的重建每帧最多跑一次（Trigger.update 在建筑 update 之前，
     * 所以下一帧开始前网络就已经是新的，逻辑上只差一帧）。
     */
    private static boolean dirty = false;

    /**
     * 读档后的"去重语义"还要保持多少帧。
     *
     * 读档时每台共享同一份池子的建筑都在存档里写了一份完整副本，合并这些副本必须**去重**
     * （内容相同只留一份）而不是相加。以前这个语义只覆盖"读档那一轮"的合并，
     * 但读档后的头几帧里本地组合体还在重建（updateTile / 组合节点网络重算），会再发生几次合并 ——
     * 那几次落到了窗口外，按"运行期相加"处理，每台手里的整份副本就被当成真库存加进去，
     * 物品正好翻倍（用户报的"重新读写后物品增加"，实测 993210 → 1983230）。
     *
     * 现在读档后头 N 帧一律按去重处理；世界一旦真的被改动（造/拆方块）就提前结束。
     */
    private static int loadDedupeFrames = 0;
    private static final int loadDedupeGraceFrames = 20;

    /** 读档去重语义还生效吗（CombinedStorageBlock / CoopCombo 共用）。 */
    public static boolean pendingLoadDedupe(){
        return loadDedupeFrames > 0;
    }

    /** 世界被真正改动过（造/拆方块）→ 读档去重语义到此为止。 */
    public static void markWorldModified(){
        loadDedupeFrames = 0;
    }

    /** 在 Main.init() 里注册一次：每帧最多重建一次。 */
    public static void register(){
        arc.Events.run(mindustry.game.EventType.Trigger.update, ComboNet::flush);
    }

    /** 网络结构变了：置脏，交给本帧的 flush 统一重建。 */
    public static void markDirty(){
        dirty = true;
    }

    /** 上一次重建里"属于某张网络"的格子（pos）；用来判断一次格子变化要不要重建网络。 */
    private static final IntSet netMemberPos = new IntSet();

    /**
     * 一格方块变了（造/拆/替换）之后：**只有可能影响组合网络的**才置脏。
     *
     * 【性能/用户报"服务端和客户端使用多线程建造后帧率下降十分明显"】以前这里无条件置脏，
     * 于是"多线程建造刷一大片"时每一帧都在重建整张组合网络（全图建筑过一遍 + 建图 + 拆池 + 合池，
     * 大基地上单次就是几毫秒到几十毫秒，实测建造中 4~5ms/tick、峰值 45ms）。
     * 但绝大多数格子变化跟网络无关：传送带、生产线、矿机、墙……既不是连接器/节点，
     * 也不在任何网络的成员表里，更不挨着网络成员 —— 重建结果一定和上一帧一模一样。
     *
     * 判据（保守优先：拿不准就置脏）：
     *   · 这一格**原来**是网络成员（pos 在成员表里）→ 变了就得重算；
     *   · 新方块是组合连接器/节点 → 要重算；
     *   · 新方块是组合建筑，且自己或邻居是网络成员/连接件 → 可能刚并进网络 → 要重算。
     */
    public static void markTileChanged(mindustry.world.Tile tile){
        try{
            if(tile == null){
                markDirty();
                return;
            }
            if(netMemberPos.contains(tile.pos())){
                markDirty();
                return;
            }
            Building b = tile.build;
            if(b == null)
                return;
            if(isLinker(b)){
                markDirty();
                return;
            }
            if(!ComboReflect.isComboBuild(b))
                return;
            if(b.proximity != null){
                for(Building nb : b.proximity){
                    if(nb == null) continue;
                    if(isLinker(nb) || netMemberPos.contains(nb.pos())){
                        markDirty();
                        return;
                    }
                }
            }
        }catch(Throwable t){
            // 判据出错宁可多算一遍，也不要漏掉网络变化
            markDirty();
        }
    }

    /** 是否正在 rebuild 内部（防止 CoopCombo → ComboNet → CoopCombo 的重入）。 */
    public static boolean rebuilding(){
        return inRebuild;
    }

    private static boolean inRebuild = false;

    /** 每帧一次的收口：只有真的脏了才重建。 */
    private static void flush(){
        if(loadDedupeFrames > 0) loadDedupeFrames --;
        if(!dirty) return;
        dirty = false;
        rebuild();
    }

    /**
     * 当前世界里所有「可能与组合逻辑有关」的建筑。
     *
     * 注意不能只看 {@code Groups.build}：组合仓库/容器、以及各种 mod 的仓库方块都是
     * {@code update = false}，Mindustry 根本不把它们放进 Groups.build。组合节点找连接目标、
     * 蓝框预览、网络重建都得用这份完整名单（否则"节点连不上旁边的组合仓库"）。
     */
    public static Seq<Building> allComboBuildings(){
        Seq<Building> all = new Seq<>();
        ObjectSet<Building> set = new ObjectSet<>();
        for(Building b : Groups.build){
            if(ComboReflect.inWorld(b) && set.add(b)) all.add(b);
        }
        for(CombinedStorageBlock.CombinedStorageBuild sb : CombinedStorageBlock.trackedSet()){
            if(ComboReflect.inWorld(sb) && set.add(sb)) all.add(sb);
        }
        for(Building cb : CoopCombo.trackedBuildings()){
            if(ComboReflect.inWorld(cb) && set.add(cb)) all.add(cb);
        }
        return all;
    }

    public static void rebuild(){
        rebuild(null, false);
    }

    public static void rebuildExcluding(Building excluded){
        rebuild(excluded, false);
    }

    /** WorldLoadBeginEvent：进入读档语义窗口。 */
    public static void beginWorldLoad(){
        loadingWorld = true;
        // 读档后头几帧仍按去重语义合并（本地组合体/网络还在重建，见 loadDedupeFrames 注释）
        loadDedupeFrames = loadDedupeGraceFrames;
        // 这一轮读档的"离谱池子体检"还没做（见 repairInsanePools）
        poolRepairDone = false;
    }

    /** 本轮读档的池子体检是否已做过。 */
    private static boolean poolRepairDone = false;

    /**
     * 读档体检：把**明显被算岔的池子**夹回容量上限。
     *
     * <p>为什么需要：用户存档里出现过一台激光钻机的池子写着 {@code 煤=522167664}、
     * {@code 硅=568718682}，而该分量容量只有 1840（差了 30 万倍）—— 这是以前版本在某条
     * "瞎连"路径上把池子反复相加留下的烂账，数字已经烧进存档，光修代码清不掉。
     *
     * <p>阈值故意定得极保守（{@code max(分量容量×1000, 1000 万)}），正常玩法堆不到这个量级：
     * 只有确凿的坏账才会被夹。夹的时候逐条打日志，方便玩家对照。
     */
    public static void repairInsanePools(){
        if(poolRepairDone) return;
        poolRepairDone = true;
        try{
            ObjectSet<ItemModule> seen = new ObjectSet<>();
            int fixed = 0;
            for(Building b : allComboBuildings()){
                if(b == null || !b.isValid() || b.items == null || !seen.add(b.items)) continue;
                Seq<Building> members = componentMembers(b);
                int cap = Math.max(componentItemCap(members), 1);
                long limit = Math.max((long)cap * 1000L, 10_000_000L);
                for(Item item : content.items()){
                    int amt = b.items.get(item);
                    if(amt <= limit) continue;
                    b.items.set(item, cap);
                    fixed++;
                    Log.warn("[combine] 存档池子坏账已夹回容量：@ 的 @ @ → @（该分量容量 @，阈值 @）",
                        b.block.name, item.name, amt, cap, cap, limit);
                }
            }
            if(fixed > 0){
                Log.warn("[combine] 共修正 @ 项离谱库存（历史版本「瞎连」留下的坏账，已夹回该分量容量）", fixed);
                markDirty();
            }
        }catch(Throwable t){
            Log.err("[combine] 读档池子体检失败（跳过，不影响游戏）", t);
        }
    }

    public static void rebuildLoading(){
        loadingWorld = true;
        try{
            rebuild(null, true);
        }finally{
            loadingWorld = false;
        }
    }

    /**
     * 只读查询：返回某个连接器/节点/组合建筑所在连通分量里的所有组合建筑成员。
     *
     * 【性能】这里是信息面板每帧都要调用的东西（displayMembers / effectiveItemCap /
     * effectiveLiquidCap 都会转进来，一次面板刷新就调 3~5 次）。原实现每次都
     * {@code Groups.build.copy()} 把全世界的建筑拷一遍再建全局图 —— 建筑一多，
     * 选中一台组合建筑就每帧卡好几毫秒（也就是"点一下组合建筑就掉帧"）。
     * 现在改成从给定的那个点做局部 BFS：只走这个连通分量里的连接器/节点/组合体，
     * 复杂度只和网络大小有关，与全图建筑数无关；同 tick 内再按"组 leader 的 pos"记忆一份，
     * 一帧只算一次。
     */
    public static Seq<Building> componentMembers(Building linker){
        Seq<Building> empty = new Seq<>();
        if(world == null || linker == null || !linker.isValid()) return empty;

        // 组合节点是**跨距离**连接的：节点不在成员的 proximity 里，
        // 从成员出发的局部 BFS 根本走不到节点，于是"点开一台只能看到自己那一格"。
        // rebuild 时算好的 netByPos 才是权威，有记录就直接用（并确认这份记录确实是它的）。
        Seq<Building> indexed = netByPos.get(linker.pos());
        if(indexed != null && indexed.size > 0){
            boolean found = false;
            for(int i = 0; i < indexed.size; i++){
                if(indexed.get(i) == linker){ found = true; break; }
            }
            if(found){
                boolean allValid = true;
                for(int i = 0; i < indexed.size; i++){
                    // 必须用 inWorld：读档后旧世界的建筑 isValid() 仍是 true
                    if(!ComboReflect.inWorld(indexed.get(i))){ allValid = false; break; }
                }
                if(allValid) return indexed;
                Seq<Building> filtered = new Seq<>();
                for(int i = 0; i < indexed.size; i++){
                    if(ComboReflect.inWorld(indexed.get(i))) filtered.add(indexed.get(i));
                }
                if(filtered.size > 0 && filtered.contains(linker, true)) return filtered;
            }
        }

        // 缓存：同一帧内、同一个网络只算一次（组合建筑按组 leader 归并）
        Building key = ComboReflect.isComboBuild(linker) ? ComboReflect.leader(linker) : linker;
        if(key == null || !key.isValid()) key = linker;
        boolean cacheable = state != null;
        if(cacheable){
            if(componentCacheTick != state.updateId){
                componentCacheTick = state.updateId;
                componentCache.clear();
            }
            Seq<Building> cached = componentCache.get(key.pos());
            if(cached != null) return cached;
        }

        Seq<Building> out = new Seq<>();
        ObjectSet<Building> visited = new ObjectSet<>();     // 已入队的连接器/节点
        ObjectSet<Building> visitedGroups = new ObjectSet<>();// 已收集的组合体(以 leader 计)
        Queue<Building> queue = new Queue<>();

        if(isLinker(key)){
            enqueueVertex(key, visited, queue);
        }else if(ComboReflect.isComboBuild(key)){
            collectGroup(key, out, visitedGroups, visited, queue);
        }

        while(!queue.isEmpty()){
            Building v = queue.removeFirst();
            if(v instanceof ComboConnector.ComboConnectorBuild c){
                for(Building nb : c.proximity){
                    if(nb == null || !nb.isValid()) continue;
                    if(isLinker(nb)){
                        enqueueVertex(nb, visited, queue);
                    }else if(ComboReflect.isComboBuild(nb)){
                        collectGroup(nb, out, visitedGroups, visited, queue);
                    }
                }
            }else if(v instanceof ComboNode.ComboNodeBuild n){
                for(int i = 0; i < n.links.size; i++){
                    Building link = world.build(n.links.get(i));
                    if(link == null || !link.isValid()) continue;
                    if(isLinker(link)){
                        enqueueVertex(link, visited, queue);
                    }else if(ComboReflect.isComboBuild(link)){
                        collectGroup(link, out, visitedGroups, visited, queue);
                    }
                }
            }
        }

        if(cacheable) componentCache.put(key.pos(), out);
        return out;
    }

    /** 同 tick 内的连通分量缓存（key = 组 leader / 连接器 / 节点的 pos）。 */
    private static long componentCacheTick = Long.MIN_VALUE;
    private static final arc.struct.IntMap<Seq<Building>> componentCache = new arc.struct.IntMap<>();

    /** 清掉分量缓存（网络结构变化时调用，避免同一帧内显示旧网络）。 */
    static void invalidateComponentCache(){
        componentCacheTick = Long.MIN_VALUE;
        componentCache.clear();
    }

    private static boolean isLinker(Building b){
        return b instanceof ComboConnector.ComboConnectorBuild
            || b instanceof ComboNode.ComboNodeBuild;
    }

    private static void enqueueVertex(Building v, ObjectSet<Building> visited, Queue<Building> queue){
        if(v == null || !v.isValid()) return;
        if(visited.add(v)) queue.addLast(v);
    }

    /**
     * 把一个组合体整组收进来，并把这个组相邻的连接器/节点也排进 BFS
     * （原图里组合体 leader 也是顶点，和相邻连接器有双向边，这里等价展开）。
     */
    private static void collectGroup(Building member, Seq<Building> out, ObjectSet<Building> visitedGroups,
                                     ObjectSet<Building> visited, Queue<Building> queue){
        Building leader = ComboReflect.leader(member);
        if(leader == null) leader = member;
        if(!visitedGroups.add(leader)) return;
        for(Building m : ComboReflect.group(leader)){
            if(m == null || !m.isValid()) continue;
            out.addUnique(m);
            for(Building nb : m.proximity){
                if(nb == null || !nb.isValid()) continue;
                if(isLinker(nb)) enqueueVertex(nb, visited, queue);
            }
        }
    }

    /** 显示/生产用的有效物品容量：网络组合存在时按网络成员合计，否则回退到本地缓存值。 */
    public static int effectiveItemCap(Building self){
        if(self == null) return 1;
        int cap = 0;
        for(Building m : displayMembers(self, ComboReflect.group(self).size)){
            if(m.isValid()) cap += ComboReflect.baseItemCap(m);
        }
        if(cap <= 0){
            Integer v = ComboReflect.getInt(self, "comboTotalItemCap");
            cap = v == null ? 0 : v;
        }
        return Math.max(cap, 1);
    }

    /** 显示/生产用的有效液体容量：网络组合存在时按网络成员合计，否则回退到本地缓存值。 */
    public static float effectiveLiquidCap(Building self){
        if(self == null) return 1f;
        float cap = 0f;
        for(Building m : displayMembers(self, ComboReflect.group(self).size)){
            if(m.isValid()) cap += ComboReflect.baseLiquidCap(m);
        }
        if(cap <= 0.001f){
            Float v = ComboReflect.getFloat(self, "comboTotalLiquidCap");
            cap = v == null ? 0f : v;
        }
        return Math.max(cap, 1f);
    }

    /** display 用的“有效组合体”：网络组合大于本地组时返回网络成员，否则返回本地组。 */
    public static Seq<Building> displayMembers(Building self, int localCount){
        if(self == null) return new Seq<>();
        Seq<Building> network = componentMembers(self);
        if(network.size > localCount) return network;
        return ComboReflect.group(self);
    }

    // ==================== 给"本地组合"用的网络查询 ====================
    //
    // 组合仓库/容器有自己的并仓逻辑（CombinedStorageBlock），它按"仓库↔仓库相邻"求分量。
    // 连接器/节点接起来的那部分网络，ComboNet 才是权威：容量、模块都得以整张网络算，
    // 不然就会出现"池子是同一份、容量却只算自己那格"（用户看到的"容量没相加"）。

    /** 这个建筑所在网络的成员；不在任何网络里时只有它自己。 */
    public static Seq<Building> networkMembers(Building self){
        if(self == null) return new Seq<>();
        // 优先用 rebuild 时算好的全局索引：组合节点是跨距离连接的，
        // 从成员出发做局部 BFS 根本走不到那个节点（节点不在它的 proximity 里）。
        Seq<Building> indexed = netByPos.get(self.pos());
        if(indexed != null) return indexed;
        return componentMembers(self);
    }

    /** 成员 pos -> 该网络的全部成员（只有成员数 > 1 的网络才登记），每次 rebuild 重建。 */
    private static final arc.struct.IntMap<Seq<Building>> netByPos = new arc.struct.IntMap<>();
    /** 上一次的网络结构签名（用来判断"要不要叫组合仓库重算"）。 */
    private static long lastNetSignature = 0L;

    /** 网络成员数（不在网络里返回 1）。 */
    public static int networkSize(Building self){
        return Math.max(networkMembers(self).size, 1);
    }

    /** 网络合计物品容量；不在网络里（只有自己一台）返回 0。 */
    public static int networkItemCap(Building self){
        Seq<Building> members = networkMembers(self);
        if(members.size <= 1) return 0;
        int total = 0;
        for(Building m : members){
            if(m.isValid()) total += ComboReflect.baseItemCap(m);
        }
        return total;
    }

    /** 网络合计液体容量；不在网络里返回 0。 */
    public static float networkLiquidCap(Building self){
        Seq<Building> members = networkMembers(self);
        if(members.size <= 1) return 0f;
        float total = 0f;
        for(Building m : members){
            if(m.isValid()) total += ComboReflect.baseLiquidCap(m);
        }
        return total;
    }

    /** 同一张网络里的建筑返回同一个编号；不在网络里返回 0。 */
    public static int networkKey(Building self){
        Seq<Building> members = networkMembers(self);
        if(members.size <= 1) return 0;
        int min = Integer.MAX_VALUE;
        for(Building m : members){
            if(m.isValid() && m.pos() < min) min = m.pos();
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /**
     * 网络里"应该共用"的那份物品模块（ComboNet 并池时会选中的那份）。
     * 网络成员已经共用一份时返回 null —— 调用方保持原样即可。
     */
    public static ItemModule poolModuleFor(Building self){
        Seq<Building> members = networkMembers(self);
        if(members.size <= 1) return null;
        Seq<ItemModule> mods = new Seq<>();
        for(Building m : members){
            if(m.items != null && !mods.contains(m.items, true)) mods.add(m.items);
        }
        return mods.size <= 1 ? null : moduleOfFirst(members, mods);
    }

    /** 同上，液体版。 */
    public static LiquidModule poolLiquidFor(Building self){
        Seq<Building> members = networkMembers(self);
        if(members.size <= 1) return null;
        Seq<LiquidModule> mods = new Seq<>();
        for(Building m : members){
            if(m.liquids != null && !mods.contains(m.liquids, true)) mods.add(m.liquids);
        }
        return mods.size <= 1 ? null : moduleOfFirstLiquid(members, mods);
    }

    /** 给任意 Combined* 建筑的 display 追加网络组合面板。 */
    public static boolean addNetworkDisplay(Table table, Building self, int localCount){
        if(self == null || table == null) return false;
        if(componentMembers(self).size <= localCount) return false;

        // 同 showPool：面板只在切换目标时重建一次，数字必须每帧重取
        Table net = new Table();
        net.left();
        net.update(() -> {
            net.clearChildren();
            net.defaults().left();
            ComboUi.safe("combonet:network", () -> buildNetworkContent(net, self, localCount));
        });
        table.row();
        table.add(net).growX().left();
        return true;
    }

    /** 网络面板上一条物品条的文字：每次调用取当前值。 */
    public static String poolItemText(Building pool, int cap, Item item){
        return item.localizedName + ": " + (pool == null || pool.items == null ? 0 : pool.items.get(item))
            + "/" + Math.max(cap, 1);
    }

    /** 同上，液体版。 */
    public static String poolLiquidText(Building pool, float cap, Liquid liquid){
        return liquid.localizedName + ": "
            + Strings.fixed(pool == null || pool.liquids == null ? 0f : pool.liquids.get(liquid), 1)
            + "/" + Strings.fixed(Math.max(cap, 1f), 1);
    }

    /** 网络面板内容：活数据（供面板每帧重画）。 */
    public static void buildNetworkContent(Table table, Building self, int localCount){
        Seq<Building> members = componentMembers(self);
        if(members.size <= localCount) return;

        table.add("[accent]网络组合 x" + members.size + "[] " + self.block.localizedName).left();

        Building itemPool = null, liquidPool = null;
        int totalItemCap = 0;
        float totalLiquidCap = 0f;
        for(Building m : members){
            if(!m.isValid()) continue;
            totalItemCap += ComboReflect.baseItemCap(m);
            totalLiquidCap += ComboReflect.baseLiquidCap(m);
            if(m.items != null && (itemPool == null || m.pos() < itemPool.pos())) itemPool = m;
            if(m.liquids != null && (liquidPool == null || m.pos() < liquidPool.pos())) liquidPool = m;
        }
        final Building fi = itemPool, fl = liquidPool;
        final int icap = Math.max(totalItemCap, 1);
        final float lcap = Math.max(totalLiquidCap, 1f);

        if(fi != null && fi.items != null){
            for(Item item : content.items()){
                if(fi.items.get(item) <= 0) continue;
                table.row();
                table.add(new Bar(
                    () -> poolItemText(fi, icap, item),
                    () -> item.color,
                    () -> fi.items == null ? 0f : (float)fi.items.get(item) / icap)).growX().height(18f).pad(4).left();
            }
        }

        if(fl != null && fl.liquids != null){
            for(Liquid liquid : content.liquids()){
                if(fl.liquids.get(liquid) <= 0.001f) continue;
                table.row();
                table.add(new Bar(
                    () -> poolLiquidText(fl, lcap, liquid),
                    () -> liquid.barColor != null ? liquid.barColor : liquid.color,
                    () -> fl.liquids == null ? 0f : fl.liquids.get(liquid) / lcap)).growX().height(18f).pad(4).left();
            }
        }

        ObjectIntMap<Block> counts = new ObjectIntMap<>();
        for(Building m : members) counts.increment(m.block, 1);
        StringBuilder comp = new StringBuilder();
        for(Block b : counts.keys()){
            if(comp.length() > 0) comp.append("  ");
            comp.append(b.localizedName).append("*").append(counts.get(b, 0));
        }
        table.row();
        table.add("[lightgray]构成: " + comp + "[]").left();
    }

    /** 临时排查用：打印各阶段耗时（默认关）。 */
    public static boolean timeDebug = false;

    private static void rebuild(Building excluded, boolean loading){
        if(world == null || Groups.build == null) return;
        if(inRebuild) return;
        inRebuild = true;
        try{
            rebuildInner(excluded, loading);
        }finally{
            inRebuild = false;
        }
    }

    private static void rebuildInner(Building excluded, boolean loading){
        long _t0 = System.nanoTime(), _tA = _t0, _tB = _t0, _tC = _t0, _tD = _t0, _tE = _t0, _tF = _t0;

        // 网络结构变了：显示用的连通分量缓存立刻作废（同帧内别拿旧网络画面板）
        invalidateComponentCache();

        // endMapLoad 阶段连接器/节点会随 proximity 刷新提前触发 rebuild，
        // 那时各本地组合体手里还是存档写下的重复副本，必须按读档语义处理。
        // 读档窗口还要看"本地组合"那边的去重标志有没有用掉：读档后每台机器手里都写着
        // 同一份池子的副本，谁先合并谁就必须按"内容相同的副本只留一份"来算。
        // ComboNet 每帧跑在它们之前，所以要以它们的标志为准，否则第一帧就把副本当三份真库存相加了。
        boolean loadPhase = loading || loadingWorld || world.isGenerating()
            || CombinedStorageBlock.pendingDedupe() || CoopCombo.pendingDedupe() || pendingLoadDedupe();

        try{
            Seq<Building> all = new Seq<>();
            ObjectSet<Building> allSet = new ObjectSet<>();
            for(Building b : allComboBuildings()){
                if(b != excluded && allSet.add(b)) all.add(b);
            }
            _tA = System.nanoTime();

            // 1) 先让所有本地组合体完成自己的 leader 选举/邻接重建，再在其上架网络。
            settleLocalGroups(all);
            _tB = System.nanoTime();

            // 2) 找出所有本地组合体的 leader（同一组合体只出现一次）。
            Seq<Building> groupLeaders = new Seq<>();
            ObjectSet<Building> seenLeaders = new ObjectSet<>();
            for(Building b : all){
                if(!ComboReflect.isComboBuild(b)) continue;
                // 用 localGroupRep：组合仓库/容器按"相邻成簇"算一个组合体。
                // 否则同一条仓库链里的每台都会成为独立顶点，它们共用的那份物品模块会被
                // splitAcrossComponents 当成"断开残留"再拆成每人一份（读档后"名义连在一起、
                // 模块却不共享"就是这个）。
                Building l = ComboNode.groupRep(b);
                if(l != null && seenLeaders.add(l)) groupLeaders.add(l);
            }

            // 3) 建图：顶点 = 连接器/节点 + 本地组合体 leader。
            Seq<Building> vertices = new Seq<>();
            ObjectSet<Building> vertexSet = new ObjectSet<>();
            for(Building b : all){
                if(b instanceof ComboConnector.ComboConnectorBuild
                    || b instanceof ComboNode.ComboNodeBuild){
                    if(vertexSet.add(b)) vertices.add(b);
                }
            }
            for(Building l : groupLeaders){
                if(vertexSet.add(l)) vertices.add(l);
            }

            ObjectMap<Building, Seq<Building>> edges = new ObjectMap<>();
            for(Building v : vertices) edges.put(v, new Seq<>());

            for(Building v : vertices){
                if(v instanceof ComboConnector.ComboConnectorBuild c){
                    for(Building nb : c.proximity){
                        if(nb == null || !nb.isValid()) continue;
                        if(nb instanceof ComboConnector.ComboConnectorBuild
                            && vertexSet.contains(nb)){
                            addEdge(edges, c, nb);
                        }else if(ComboReflect.isComboBuild(nb)){
                            Building l = ComboNode.groupRep(nb);
                            if(l != null && vertexSet.contains(l)) addEdge(edges, c, l);
                        }
                    }
                }else if(v instanceof ComboNode.ComboNodeBuild n){
                    for(int i = 0; i < n.links.size; i++){
                        Building link = world.build(n.links.get(i));
                        if(link == null || !link.isValid() || !ComboReflect.isComboBuild(link)) continue;
                        Building l = ComboNode.groupRep(link);
                        if(l != null && vertexSet.contains(l)) addEdge(edges, n, l);
                    }
                }
            }

            // 4) 连通分量。
            ObjectSet<Building> visited = new ObjectSet<>();
            Seq<Seq<Building>> components = new Seq<>();
            for(Building v : vertices){
                if(visited.contains(v)) continue;
                Seq<Building> comp = new Seq<>();
                Queue<Building> queue = new Queue<>();
                queue.addLast(v);
                visited.add(v);
                while(!queue.isEmpty()){
                    Building cur = queue.removeFirst();
                    comp.add(cur);
                    Seq<Building> es = edges.get(cur);
                    if(es == null) continue;
                    for(Building nb : es){
                        if(visited.add(nb)) queue.addLast(nb);
                    }
                }
                components.add(comp);
            }

            // 5) 每个分量的组合体成员：按“当前 leader 归属”收集，不依赖可能过期的 group 缓存。
            IdentityHashMap<Building, Integer> leaderComponent = new IdentityHashMap<>();
            for(int i = 0; i < components.size; i++){
                for(Building b : components.get(i)){
                    if(ComboReflect.isComboBuild(b)) leaderComponent.put(b, i);
                }
            }
            Seq<Seq<Building>> compMembers = new Seq<>();
            for(int i = 0; i < components.size; i++) compMembers.add(new Seq<Building>());
            for(Building b : all){
                if(!ComboReflect.isComboBuild(b)) continue;
                Building l = ComboNode.groupRep(b);
                if(l == null) l = b;
                Integer idx = leaderComponent.get(l);
                if(idx != null) compMembers.get(idx).add(b);
            }

            // 6) 断开连接器/节点时：被多个分量共用的模块按分量容量比例拆开，总值不变。
            _tC = System.nanoTime();
            splitAcrossComponents(compMembers);
            _tD = System.nanoTime();

            // 7) 分量内合并共享池：运行期“相加”，读档窗口“去重”。
            for(Seq<Building> members : compMembers){
                mergeComponent(members, loadPhase);
            }
            // 8) 登记"网络成员 -> 整张网络"的索引，给组合仓库那套本地并仓逻辑查（见 networkMembers）。
            netByPos.clear();
            netMemberPos.clear();
            long netSig = 1125899906842597L;
            for(Seq<Building> members : compMembers){
                if(members.size <= 1) continue;
                for(Building m : members){
                    if(m != null && m.isValid()){
                        netByPos.put(m.pos(), members);
                        netMemberPos.add(m.pos());
                        netSig = netSig * 31 + m.pos();
                    }
                }
                netSig = netSig * 31 + 7;
            }
            // 网络结构变了 → 两边"本地组合"逻辑都要跟着重算（容量/面板/池子都按整张网络）。
            // 它们自己只认方块变化事件，连接器/节点的"连上/断开"不会触发它们，
            // 不叫一声就会出现"池子共享了、容量和面板还是自己那格"。
            if(netSig != lastNetSignature){
                lastNetSignature = netSig;
                CombinedStorageBlock.markDirty();
                CoopCombo.markDirty();
            }
            _tE = System.nanoTime();
            if(timeDebug){
                Log.info("[combine][time] 快照=@ms settle=@ms 建图=@ms 拆池=@ms 合池=@ms 合计=@ms all=@ verts=@ comps=@",
                    (_tA-_t0)/1e6, (_tB-_tA)/1e6, (_tC-_tB)/1e6, (_tD-_tC)/1e6, (_tE-_tD)/1e6, (_tE-_t0)/1e6,
                    all.size, vertices.size, components.size);
            }

            // 读档语义只在读档过程中生效；任何一次运行期重建都意味着读档已经结束
            if(!loading && !world.isGenerating()) loadingWorld = false;
            // 读档窗口结束 → 做一次"离谱池子"体检（一次读档只做一次；见 repairInsanePools）
            if(loadPhase && !poolRepairDone && !loading && !world.isGenerating()) repairInsanePools();

            heatAlloc.clear();
        }catch(Throwable t){
            Log.err("[combine] ComboNet.rebuild failed", t);
        }
    }

    /** 局部组合体在成员被拆后可能仍是脏的单点；先调用它们自己的 rebuildCombo 恢复本地组。 */
    private static void settleLocalGroups(Seq<Building> all){
        // 读档留下的“待恢复 leader”先落地(与 updateTile 里的 comboPreUpdate 完全一致)。
        // 不落地的话这些成员会被当成独立的单点组，池归属与容量都会错位。
        for(Building b : all){
            if(ComboReflect.isComboBuild(b) && ComboReflect.hasPendingLeader(b)){
                ComboReflect.preUpdate(b);
                // 该成员所属的本地组合体也要重选一次，否则它的 group 缓存可能还是空的
                Building l = ComboReflect.leader(b);
                if(l != null && l != b) ComboReflect.setDirty(l, true);
            }
        }
        for(int iteration = 0; iteration < 6; iteration++){
            ObjectSet<Building> rebuilt = new ObjectSet<>();
            boolean changed = false;
            for(Building b : all){
                if(!ComboReflect.isComboBuild(b) || !ComboReflect.isDirty(b)) continue;
                if(ComboReflect.hasPendingLeader(b)) continue;
                Building leader = ComboReflect.leader(b);
                if(leader == null || !leader.isValid()) continue;
                if(rebuilt.add(leader)){
                    ComboReflect.rebuildLocal(leader);
                    changed = true;
                }
            }
            if(!changed) return;
        }
    }

    private static void addEdge(ObjectMap<Building, Seq<Building>> edges, Building a, Building b){
        if(a == null || b == null || a == b) return;
        Seq<Building> ea = edges.get(a);
        if(ea != null) ea.addUnique(b);
        Seq<Building> eb = edges.get(b);
        if(eb != null) eb.addUnique(a);
    }

    /**
     * 断开连接器/节点时，被多个连通分量共用的物品/液体模块必须拆开，
     * 否则断开后两个分量仍然共用同一个池。按各分量容量比例分配，拆分前后总值不变。
     */
    private static void splitAcrossComponents(Seq<Seq<Building>> comps){
        splitItemsAcrossComponents(comps);
        splitLiquidsAcrossComponents(comps);
    }

    /**
     * 这个建筑手里的库存模块是不是"核心的池子"：核心本身，或者已经并进核心的组合仓库。
     * 这类模块**故意**被多台建筑共用，绝不能当成"断开残留的共享池"去拆。
     */
    private static boolean isCorePool(Building b){
        if(b instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild) return true;
        if(b instanceof CombinedStorageBlock.CombinedStorageBuild s) return s.linkedCoreOf() != null;
        // 【注意】这里**不能**用"模块正好是核心那份"来判断：组合工厂临时挂在核心池上时
        // 也满足这个条件，那样断开组合后它会被当成核心成员、永远留在核心库存上。
        // 判定"谁有资格待在核心池里"只看并仓状态；usesCorePool() 只在挑池子时当偏好用。
        return false;
    }

    private static void splitItemsAcrossComponents(Seq<Seq<Building>> comps){
        ObjectSet<Building> allMembers = new ObjectSet<>();
        for(Seq<Building> comp : comps)
            for(Building m : comp)
                if(m != null) allMembers.add(m);
        IdentityHashMap<ItemModule, IntSet> owners = new IdentityHashMap<>();
        // 核心的池子（核心自己、或"并进核心"的组合仓库）**不能拆**：
        // 它们本来就是故意共用同一份模块的。按分量拆会把它复制成好几份
        // （核心那份还原样留着），下次再并回核心就把副本加进去 —— 物品凭空翻倍
        // （用户报的"钢化玻璃 4500 变 7000+"，最后被容量截断压成"正好等于容量"）。
        IdentityHashMap<ItemModule, Boolean> coreOwned = new IdentityHashMap<>();
        for(int i = 0; i < comps.size; i++){
            for(Building m : comps.get(i)){
                if(m.items == null) continue;
                if(isCorePool(m)) coreOwned.put(m.items, Boolean.TRUE);
                IntSet set = owners.get(m.items);
                if(set == null){
                    set = new IntSet();
                    owners.put(m.items, set);
                }
                set.add(i);
            }
        }

        for(var entry : owners.entrySet()){
            IntSet ownerComps = entry.getValue();
            if(ownerComps.size <= 1) continue;
            if(coreOwned.containsKey(entry.getKey())){
                // 核心那份池子不拆（核心 + 并仓仓库本来就说好共用）。
                // 但**不是**核心成员的（例如被组合节点接进来的组合工厂）必须脱离：
                // 断开之后还挂在核心库存上，就会出现"组合断了、物品还跟着那台机器走"。
                ItemModule core = entry.getKey();
                for(int i = 0; i < comps.size; i++){
                    for(Building m : comps.get(i)){
                        if(m.items == core && !isCorePool(m)){
                            m.items = new ItemModule();
                        }
                    }
                }
                continue;
            }

            ItemModule old = entry.getKey();
            // 【池子被组外也在用】核心库存 / 组合节点接进来的别的组合体也引用着这份模块时，
            // 按容量"复制一份"给各分量就是凭空多一份（原模块还在别人手里）——
            // 用户报的"拆工厂时核心里的东西全没了/翻倍"就是这个。
            // 这份池子不属于这些分量：分量里的成员换成空模块，池子留给真正的所有者。
            if(ComboReflect.itemPoolSharedOutside(old, allMembers)){
                for(int i = 0; i < comps.size; i++){
                    for(Building m : comps.get(i)){
                        if(m.items == old) m.items = new ItemModule();
                    }
                }
                continue;
            }
            int n = ownerComps.size;
            int[] compIdx = new int[n];
            int[] caps = new int[n];
            int totalCap = 0;
            IntSet.IntSetIterator it = ownerComps.iterator();
            for(int k = 0; k < n && it.hasNext; k++){
                compIdx[k] = it.next();
                caps[k] = Math.max(componentItemCap(comps.get(compIdx[k])), 1);
                totalCap += caps[k];
            }

            ItemModule[] shares = new ItemModule[n];
            for(int k = 0; k < n; k++) shares[k] = new ItemModule();
            for(Item item : content.items()){
                int total = old.get(item);
                if(total <= 0) continue;
                int remaining = total;
                for(int k = 0; k < n; k++){
                    int ideal = (k == n - 1) ? remaining : Math.round(total * (float)caps[k] / totalCap);
                    ideal = Math.min(ideal, remaining);
                    if(ideal > 0){
                        shares[k].add(item, ideal);
                        remaining -= ideal;
                    }
                }
            }
            for(int k = 0; k < n; k++){
                for(Building m : comps.get(compIdx[k])){
                    if(m.items == old) m.items = shares[k];
                }
            }
        }
    }

    private static void splitLiquidsAcrossComponents(Seq<Seq<Building>> comps){
        ObjectSet<Building> allMembers = new ObjectSet<>();
        for(Seq<Building> comp : comps)
            for(Building m : comp)
                if(m != null) allMembers.add(m);
        IdentityHashMap<LiquidModule, IntSet> owners = new IdentityHashMap<>();
        IdentityHashMap<LiquidModule, Boolean> coreOwned = new IdentityHashMap<>();
        for(int i = 0; i < comps.size; i++){
            for(Building m : comps.get(i)){
                if(m.liquids == null) continue;
                if(isCorePool(m)) coreOwned.put(m.liquids, Boolean.TRUE);
                IntSet set = owners.get(m.liquids);
                if(set == null){
                    set = new IntSet();
                    owners.put(m.liquids, set);
                }
                set.add(i);
            }
        }

        for(var entry : owners.entrySet()){
            IntSet ownerComps = entry.getValue();
            if(ownerComps.size <= 1) continue;
            if(coreOwned.containsKey(entry.getKey())){
                // 同物品：核心的液体池不拆，但非核心成员要脱离
                LiquidModule core = entry.getKey();
                for(int i = 0; i < comps.size; i++){
                    for(Building m : comps.get(i)){
                        if(m.liquids == core && !isCorePool(m)){
                            m.liquids = new LiquidModule();
                        }
                    }
                }
                continue;
            }

            LiquidModule old = entry.getKey();
            // 【池子被组外也在用】同物品：不是这些分量独有的池子不能"复制一份"分给各分量。
            if(ComboReflect.liquidPoolSharedOutside(old, allMembers)){
                for(int i = 0; i < comps.size; i++){
                    for(Building m : comps.get(i)){
                        if(m.liquids == old) m.liquids = new LiquidModule();
                    }
                }
                continue;
            }
            int n = ownerComps.size;
            int[] compIdx = new int[n];
            float[] caps = new float[n];
            float totalCap = 0f;
            IntSet.IntSetIterator it = ownerComps.iterator();
            for(int k = 0; k < n && it.hasNext; k++){
                compIdx[k] = it.next();
                caps[k] = Math.max(componentLiquidCap(comps.get(compIdx[k])), 1f);
                totalCap += caps[k];
            }

            LiquidModule[] shares = new LiquidModule[n];
            for(int k = 0; k < n; k++) shares[k] = new LiquidModule();
            for(Liquid liquid : content.liquids()){
                float total = old.get(liquid);
                if(total <= 0.001f) continue;
                float remaining = total;
                for(int k = 0; k < n; k++){
                    float ideal = (k == n - 1) ? remaining : total * caps[k] / totalCap;
                    ideal = Math.min(ideal, remaining);
                    if(ideal > 0.001f){
                        shares[k].add(liquid, ideal);
                        remaining -= ideal;
                    }
                }
            }
            for(int k = 0; k < n; k++){
                for(Building m : comps.get(compIdx[k])){
                    if(m.liquids == old) m.liquids = shares[k];
                }
            }
        }
    }

    /**
     * 分量内统一共享池。
     * 运行期(loadPhase=false)：各成员手里是真实库存，合并 = 相加到 pos 最小的成员模块；
     * 读档窗口(loadPhase=true)：各成员手里是存档写下的同一份池的重复副本，
     * 合并 = 相同副本只留一份、不同内容相加，绝不能把重复副本当两份真库存相加。
     */
    private static void mergeComponent(Seq<Building> members, boolean loadPhase){
        if(members.size <= 0) return;

        ItemModule itemTarget = pickItemModule(members, loadPhase);
        if(itemTarget != null){
            for(Building m : members){
                if(m.items == null || m.items == itemTarget) continue;
                if(!loadPhase) moveItems(m.items, itemTarget);
                m.items = itemTarget;
            }
        }

        LiquidModule liquidTarget = pickLiquidModule(members, loadPhase);
        if(liquidTarget != null){
            for(Building m : members){
                if(m.liquids == null || m.liquids == liquidTarget) continue;
                if(!loadPhase) moveLiquids(m.liquids, liquidTarget);
                m.liquids = liquidTarget;
            }
        }

        int itemCap = componentItemCap(members);
        float liquidCap = componentLiquidCap(members);
        for(Building m : members){
            ComboReflect.setItemCap(m, itemCap);
            ComboReflect.setLiquidCap(m, liquidCap);
            ComboReflect.markClean(m);
        }
    }

    private static ItemModule pickItemModule(Seq<Building> members, boolean loadPhase){
        Seq<ItemModule> mods = new Seq<>();
        for(Building m : members){
            if(m.items != null && !mods.contains(m.items, true)) mods.add(m.items);
        }
        if(mods.size <= 1) return mods.isEmpty() ? null : mods.first();
        return loadPhase ? mergeDistinctItems(members, mods) : moduleOfFirst(members, mods);
    }

    private static LiquidModule pickLiquidModule(Seq<Building> members, boolean loadPhase){
        Seq<LiquidModule> mods = new Seq<>();
        for(Building m : members){
            if(m.liquids != null && !mods.contains(m.liquids, true)) mods.add(m.liquids);
        }
        if(mods.size <= 1) return mods.isEmpty() ? null : mods.first();
        return loadPhase ? mergeDistinctLiquids(members, mods) : moduleOfFirstLiquid(members, mods);
    }

    /** 这台机器是不是"已经并进核心"的组合仓库（它的模块就是核心库存）。 */
    private static boolean coreLinked(Building m){
        return m instanceof CombinedStorageBlock.CombinedStorageBuild sb
            && sb.linkedCore != null && sb.linkedCore.isValid();
    }

    /**
     * 手里这份模块**就是某座核心的库存**吗（不管它是核心自己、并仓的仓库，还是别人的模块在
     * 网络重整时被换成了核心那份）。
     *
     * 【为什么还要查一遍】并仓状态（linkedCore）是 CombinedStorageBlock 那边重算出来的，
     * 和 ComboNet 的重建不是同一时刻：中间那一瞬 coreLinked() 可能还是 false，
     * 于是网络池会挑成组合工厂那一份 —— 玩家看到的就是"核心的东西全跑到工厂里去了"
     * （用户报的：组合节点接组合仓库 + 石墨压缩机）。
     */
    private static boolean usesCorePool(Building m){
        if(m == null || m.items == null || state == null || m.team == null) return false;
        var data = state.teams.get(m.team);
        if(data == null || data.cores == null) return false;
        for(var core : data.cores){
            if(core != null && core.items == m.items) return true;
        }
        return false;
    }

    /**
     * 取 pos 最小的成员手里的那份模块，保证每次重建选到同一个池对象。
     *
     * 例外：并进核心的组合仓库，它的模块**就是核心库存**。如果网络里有这么一台，
     * 目标模块必须是它 —— 否则整个网络换成别的模块时，核心会凭空少掉这些物品。
     */
    private static ItemModule moduleOfFirst(Seq<Building> members, Seq<ItemModule> candidates){
        ItemModule core = null;
        int corePos = Integer.MAX_VALUE;
        for(Building m : members){
            if(m.items != null && (coreLinked(m) || usesCorePool(m)) && candidates.contains(m.items, true) && m.pos() < corePos){
                core = m.items;
                corePos = m.pos();
            }
        }
        if(core != null) return core;

        ItemModule best = null;
        int bestPos = Integer.MAX_VALUE;
        for(Building m : members){
            if(m.items != null && candidates.contains(m.items, true) && m.pos() < bestPos){
                best = m.items;
                bestPos = m.pos();
            }
        }
        return best;
    }

    private static LiquidModule moduleOfFirstLiquid(Seq<Building> members, Seq<LiquidModule> candidates){
        LiquidModule core = null;
        int corePos = Integer.MAX_VALUE;
        for(Building m : members){
            if(m.liquids != null && (coreLinked(m) || usesCorePool(m)) && candidates.contains(m.liquids, true) && m.pos() < corePos){
                core = m.liquids;
                corePos = m.pos();
            }
        }
        if(core != null) return core;

        LiquidModule best = null;
        int bestPos = Integer.MAX_VALUE;
        for(Building m : members){
            if(m.liquids != null && candidates.contains(m.liquids, true) && m.pos() < bestPos){
                best = m.liquids;
                bestPos = m.pos();
            }
        }
        return best;
    }

    /**
     * 运行期的目标模块：pos 最小的成员手里的那一份（其余相加进去）。
     * 读档窗口则要小心：同一份池被每个成员各写了一份完全相同的副本，
     * 内容相同的副本只能保留一份，内容不同的（旧档/断开残留的分配结果）仍要相加，
     * 否则要么翻倍、要么丢库存。
     */
    private static ItemModule mergeDistinctItems(Seq<Building> members, Seq<ItemModule> mods){
        Seq<ItemModule> unique = new Seq<>();
        for(ItemModule mod : mods){
            boolean dup = false;
            for(ItemModule seen : unique){
                if(sameItems(seen, mod)){
                    dup = true;
                    break;
                }
            }
            if(!dup) unique.add(mod);
        }
        ItemModule dst = moduleOfFirst(members, unique);
        // 【必须遍历全部、只跳过目标自己】原来看起来是"把第 0 份留下、其余搬进 dst"，
        // 但 dst 是 moduleOfFirst() 挑的（优先核心池，否则 pos 最小的那台），
        // **不一定是 unique.get(0)**：一旦不是，第 0 份库存就永远不会被搬走，
        // 紧接着 mergeComponent() 把每个成员的模块都指向 dst —— 那份库存被静默丢掉
        //（用户报的"物品异常减少"）。
        for(ItemModule mod : unique){
            if(mod != dst) moveItems(mod, dst);
        }
        return dst;
    }

    private static LiquidModule mergeDistinctLiquids(Seq<Building> members, Seq<LiquidModule> mods){
        Seq<LiquidModule> unique = new Seq<>();
        for(LiquidModule mod : mods){
            boolean dup = false;
            for(LiquidModule seen : unique){
                if(sameLiquids(seen, mod)){
                    dup = true;
                    break;
                }
            }
            if(!dup) unique.add(mod);
        }
        LiquidModule dst = moduleOfFirstLiquid(members, unique);
        // 同上：目标不一定是 unique.get(0)，必须遍历全部、只跳过目标自己
        for(LiquidModule mod : unique){
            if(mod != dst) moveLiquids(mod, dst);
        }
        return dst;
    }

    private static boolean sameItems(ItemModule a, ItemModule b){
        if(a == b) return true;
        if(a == null || b == null) return false;
        for(Item item : content.items()){
            if(a.get(item) != b.get(item)) return false;
        }
        return true;
    }

    private static boolean sameLiquids(LiquidModule a, LiquidModule b){
        if(a == b) return true;
        if(a == null || b == null) return false;
        for(Liquid liquid : content.liquids()){
            if(Math.abs(a.get(liquid) - b.get(liquid)) > 0.001f) return false;
        }
        return true;
    }

    private static int componentItemCap(Seq<Building> members){
        int total = 0;
        for(Building m : members) total += ComboReflect.baseItemCap(m);
        return total;
    }

    /**
     * 面板用：这台建筑所在**分量**（= 共用同一份池子的那整张网络）的物品容量。
     *
     * <p>为什么要单独给一个：协作组合面板原来打印的分母是**这台方块自己**的容量
     * （例如"组合钻机 x6"= 6 台钻机的 60），而它显示的池子却可能是**整张网络**共用的那一份
     * —— 于是面板上出现"1482273/60"这种数字（用户视频里的现场：铜 1482273/60、
     * 硅 5555520/60，每帧还在百万/几十之间跳）。分子分母必须同源：池子是网络的，分母也得是网络的。
     */
    public static int panelItemCap(Building self){
        int cap = componentItemCap(componentMembers(self));
        // 不在任何组合网络里（分量只有自己）时用这台方块自己的基础容量：
        // 否则核心这类方块会算出 1，面板/审计就会把"核心里 12000 物品"误判成坏账
        if(cap <= 0) cap = ComboReflect.baseItemCap(self);
        return Math.max(cap, 1);
    }

    /** 同上，液体版。 */
    public static float panelLiquidCap(Building self){
        float cap = componentLiquidCap(componentMembers(self));
        if(cap <= 0f) cap = ComboReflect.baseLiquidCap(self);
        return Math.max(cap, 1f);
    }

    /**
     * 面板用：这台建筑所在网络**实际共用的那一份**物品模块（网络已经共用时就是它自己的那份）。
     *
     * <p>面板原来直接读 {@code build.items}（这台方块自己的模块），可并池之后池子可能躺在网络里
     * 别的成员手里 —— 于是面板要么显示"（空）"，要么在不同模块之间来回跳（用户视频里
     * 组合钻机的面板数字每帧在百万/几十之间跳，就是这个）。
     */
    public static ItemModule panelItemPool(Building self){
        if(self == null) return null;
        // 1) 节点/连接器接起来的**网络**共用池
        ItemModule netPool = poolModuleFor(self);
        if(netPool != null) return netPool;
        // 2) 本地组合体（相邻成组）里成员真的共用一份时，取那一份
        ItemModule shared = null;
        for(Building m : ComboReflect.group(self)){
            if(m == null || m.items == null) continue;
            if(shared == null) shared = m.items;
            else if(shared != m.items) return self.items;   // 没共用：各自看各自的
        }
        return shared != null ? shared : self.items;
    }

    /** 同上，液体版。 */
    public static LiquidModule panelLiquidPool(Building self){
        if(self == null) return null;
        LiquidModule netPool = poolLiquidFor(self);
        if(netPool != null) return netPool;
        LiquidModule shared = null;
        for(Building m : ComboReflect.group(self)){
            if(m == null || m.liquids == null) continue;
            if(shared == null) shared = m.liquids;
            else if(shared != m.liquids) return self.liquids;
        }
        return shared != null ? shared : self.liquids;
    }

    private static float componentLiquidCap(Seq<Building> members){
        float total = 0f;
        for(Building m : members) total += ComboReflect.baseLiquidCap(m);
        return total;
    }

    // 【别把"内部搬池子"记成流量】LiquidModule.add/ItemModule.add 会把搬运量记进
    // 流量窗口（cacheSums += amount），面板（含 MindustryX 的流量行）就会显示
    // "每秒几千的水进账"，其实只是把两份池子并成一份、总量一点没变 ——
    // 用户报的"莫名其妙显示进了水实则没有，储罐也没和抽水机连管"。
    // stopFlow() 让这份模块的流量窗口从头重新统计（下一次真实进液再算）。

    private static void moveItems(ItemModule from, ItemModule to){
        if(from == null || to == null || from == to) return;
        for(Item item : content.items()){
            int amt = from.get(item);
            if(amt > 0){
                to.add(item, amt);
                from.remove(item, amt);
            }
        }
        to.stopFlow();
    }

    private static void moveLiquids(LiquidModule from, LiquidModule to){
        if(from == null || to == null || from == to) return;
        for(Liquid liquid : content.liquids()){
            float amt = from.get(liquid);
            if(amt > 0.001f){
                to.add(liquid, amt);
                from.remove(liquid, amt);
            }
        }
        to.stopFlow();
    }

    // -------------------- 节点热量网络 --------------------

    public static float heatFor(Building consumer){
        if(consumer == null) return 0f;
        beginHeatFrame();
        Building leader = ComboReflect.leader(consumer);
        return leader == null ? 0f : heatAlloc.get(leader.pos(), 0f);
    }

    public static void updateHeatNode(ComboNode.ComboNodeBuild node){
        if(node == null || state == null || node.power == null) return;
        beginHeatFrame();

        // 节点互连后，同一条链上的每个节点看到的组完全一样 —— 热量分配必须由
        // 链上唯一"负责人"（互连/贴邻节点簇里 pos 最小者）做一次，否则同一批
        // 产热组的存量热被每个节点各扣一遍、需热组各领一份（越领越多/速耗翻倍）。
        if(!isHeatAuthority(node)){
            node.heat = 0f;
            return;
        }

        Seq<Building> groups = linkedHeatGroups(node);
        int n = groups.size;
        if(n == 0){
            node.heat = 0f;
            return;
        }

        float[] source = new float[n];
        float[] demand = new float[n];
        float totalSource = 0f, totalDemand = 0f;
        for(int i = 0; i < n; i++){
            Building leader = groups.get(i);
            source[i] = groupHeatSource(leader);
            demand[i] = groupHeatDemand(leader);
            totalSource += source[i];
            totalDemand += demand[i];
        }

        if(totalDemand > 0.001f){
            for(int i = 0; i < n; i++){
                float amount = totalSource * demand[i] / totalDemand;
                heatAlloc.increment(groups.get(i).pos(), amount);
                if(amount > 0.001f && source[i] > 0.001f){
                    deductStoredHeat(groups.get(i), amount * source[i] / Math.max(totalSource, 0.001f));
                }
            }
            node.heat = 0f;
        }else{
            // 没有网络内需热端时，节点作为 HeatBlock 向相邻的原版/组合需热建筑供热。
            node.heat = totalSource;
        }
    }

    /**
     * 这条节点是不是自己所在"节点簇"的热量负责人。
     * 节点簇 = 经节点互连连线、贴邻、或同贴一个连接器而连在一起的全部节点 —
     * 簇内 pos 最小的节点负责给整个网络做热量分配。
     */
    private static boolean isHeatAuthority(ComboNode.ComboNodeBuild node){
        ObjectSet<Building> seen = new ObjectSet<>();
        Queue<Building> q = new Queue<>();
        q.addLast(node);
        seen.add(node);
        while(!q.isEmpty()){
            Building v = q.removeFirst();
            Seq<Building> nbs = new Seq<>();
            if(v instanceof ComboConnector.ComboConnectorBuild c){
                if(c.proximity != null) for(Building nb : c.proximity) nbs.add(nb);
            }else if(v instanceof ComboNode.ComboNodeBuild n){
                for(int i = 0; i < n.links.size; i++){
                    Building l = world.build(n.links.get(i));
                    if(l != null && l.isValid()) nbs.add(l);
                }
                if(n.proximity != null) for(Building nb : n.proximity) nbs.add(nb);
            }
            for(int i = 0; i < nbs.size; i++){
                Building nb = nbs.get(i);
                if(nb instanceof ComboNode.ComboNodeBuild nn && seen.add(nn)) q.addLast(nn);
            }
        }
        for(Building b : seen){
            if(b instanceof ComboNode.ComboNodeBuild nb && nb != node && nb.pos() < node.pos()) return false;
        }
        return true;
    }

    private static Seq<Building> linkedHeatGroups(ComboNode.ComboNodeBuild node){
        // 直接走网络分量：节点互连后链上所有组都是同一个"组合网络"，
        // 产热/需热要和物品/液体池按同一套连通性通算。
        Seq<Building> out = new Seq<>();
        ObjectSet<Building> seen = new ObjectSet<>();
        for(Building m : componentMembers(node)){
            if(m == null || !m.isValid() || m instanceof ComboNode.ComboNodeBuild) continue;
            if(!ComboReflect.isComboBuild(m)) continue;
            Building leader = ComboReflect.leader(m);
            if(leader != null && seen.add(leader)) out.add(leader);
        }
        return out;
    }

    /**
     * 一个组合体对外能提供的热量 = **整组成员**各自 heat() 之和。
     *
     * 以前这里是"取第一个成员（HeatBlock）的 heat()"——那时组合产热机的 heat() 报的是整组总量，
     * 所以取一台就够。现在产热机的 heat() 改回原版语义（只报自己那一份，避免相邻多台被重复计入），
     * 汇总就得在这里做：整组求和。
     */
    private static float groupHeatSource(Building leader){
        float sum = 0f;
        for(Building m : ComboReflect.group(leader)){
            if(m == null || !m.isValid() || m instanceof ComboNode.ComboNodeBuild) continue;
            if(m instanceof HeatBlock hb) sum += Math.max(0f, hb.heat());
        }
        return sum;
    }

    private static float groupHeatDemand(Building leader){
        float demand = 0f;
        for(Building m : ComboReflect.group(leader)){
            if(!m.isValid()) continue;
            Float req = ComboReflect.getFloat(m.block, "heatRequirement");
            if(req != null && req > 0f) demand += req;
        }
        return demand;
    }

    private static void deductStoredHeat(Building leader, float amount){
        Building stored = null;
        for(Building m : ComboReflect.group(leader)){
            if(ComboReflect.isStoredHeatBuild(m)){
                stored = m;
                break;
            }
        }
        if(stored == null && ComboReflect.isStoredHeatBuild(leader)) stored = leader;
        if(stored == null) return;
        float cur = ComboReflect.getStoredHeat(stored);
        ComboReflect.setStoredHeat(stored, Math.max(0f, cur - amount));
    }

    private static void beginHeatFrame(){
        if(state != null && heatFrame != state.updateId){
            heatAlloc.clear();
            heatFrame = state.updateId;
        }
    }
}
