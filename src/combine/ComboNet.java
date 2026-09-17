package combine;

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

    /** 在 Main.init() 里注册一次：每帧最多重建一次。 */
    public static void register(){
        arc.Events.run(mindustry.game.EventType.Trigger.update, ComboNet::flush);
    }

    /** 网络结构变了：置脏，交给本帧的 flush 统一重建。 */
    public static void markDirty(){
        dirty = true;
    }

    /** 是否正在 rebuild 内部（防止 CoopCombo → ComboNet → CoopCombo 的重入）。 */
    public static boolean rebuilding(){
        return inRebuild;
    }

    private static boolean inRebuild = false;

    /** 每帧一次的收口：只有真的脏了才重建。 */
    private static void flush(){
        if(!dirty) return;
        dirty = false;
        rebuild();
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
        Seq<Building> members = componentMembers(self);
        if(members.size <= localCount) return false;

        table.row();
        table.add("[accent]网络组合 x" + members.size + "[] " + self.block.localizedName).left();

        Building itemPool = null, liquidPool = null;
        int totalItemCap = 0;
        float totalLiquidCap = 0f;
        for(Building m : members){
            totalItemCap += ComboReflect.baseItemCap(m);
            totalLiquidCap += ComboReflect.baseLiquidCap(m);
            if(m.items != null && (itemPool == null || m.pos() < itemPool.pos())) itemPool = m;
            if(m.liquids != null && (liquidPool == null || m.pos() < liquidPool.pos())) liquidPool = m;
        }

        if(itemPool != null && itemPool.items != null){
            for(Item item : content.items()){
                int amount = itemPool.items.get(item);
                if(amount <= 0) continue;
                final int a = amount;
                final int cap = Math.max(totalItemCap, 1);
                table.row();
                table.add(new Bar(
                    () -> item.localizedName + ": " + a + "/" + cap,
                    () -> item.color,
                    () -> (float)a / cap)).growX().height(18f).pad(4).left();
            }
        }

        if(liquidPool != null && liquidPool.liquids != null){
            for(Liquid liquid : content.liquids()){
                float amount = liquidPool.liquids.get(liquid);
                if(amount <= 0.001f) continue;
                final float a = amount;
                final float cap = Math.max(totalLiquidCap, 1f);
                table.row();
                table.add(new Bar(
                    () -> liquid.localizedName + ": " + Strings.fixed(a, 1) + "/" + Strings.fixed(cap, 1),
                    () -> liquid.barColor != null ? liquid.barColor : liquid.color,
                    () -> a / cap)).growX().height(18f).pad(4).left();
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
        return true;
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
        boolean loadPhase = loading || loadingWorld || world.isGenerating();

        try{
            Seq<Building> all = new Seq<>();
            ObjectSet<Building> allSet = new ObjectSet<>();
            for(Building b : Groups.build.copy()){
                if(ComboReflect.inWorld(b) && b != excluded && allSet.add(b)) all.add(b);
            }
            // 组合仓库/容器是 update=false，不会进 Groups.build —— 但它们在连接器/节点网络里
            // 同样要算成员（否则"js 工厂 ↔ 组合仓库"这种网络永远合不到一个池子里）。
            for(CombinedStorageBlock.CombinedStorageBuild sb : CombinedStorageBlock.trackedSet()){
                if(ComboReflect.inWorld(sb) && sb != excluded && allSet.add(sb)) all.add(sb);
            }
            // 协作组合登记表：里面既有 update=true 的机器，也有各种 update=false 的 mod 仓库/容器，
            // 后者同样不在 Groups.build 里，必须一起补进来才能同池。
            for(Building cb : CoopCombo.trackedBuildings()){
                if(ComboReflect.inWorld(cb) && cb != excluded && allSet.add(cb)) all.add(cb);
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
                Building l = ComboReflect.leader(b);
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
                            Building l = ComboReflect.leader(nb);
                            if(l != null && vertexSet.contains(l)) addEdge(edges, c, l);
                        }
                    }
                }else if(v instanceof ComboNode.ComboNodeBuild n){
                    for(int i = 0; i < n.links.size; i++){
                        Building link = world.build(n.links.get(i));
                        if(link == null || !link.isValid() || !ComboReflect.isComboBuild(link)) continue;
                        Building l = ComboReflect.leader(link);
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
                Building l = ComboReflect.leader(b);
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
            long netSig = 1125899906842597L;
            for(Seq<Building> members : compMembers){
                if(members.size <= 1) continue;
                for(Building m : members){
                    if(m != null && m.isValid()){
                        netByPos.put(m.pos(), members);
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

    private static void splitItemsAcrossComponents(Seq<Seq<Building>> comps){
        IdentityHashMap<ItemModule, IntSet> owners = new IdentityHashMap<>();
        for(int i = 0; i < comps.size; i++){
            for(Building m : comps.get(i)){
                if(m.items == null) continue;
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

            ItemModule old = entry.getKey();
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
        IdentityHashMap<LiquidModule, IntSet> owners = new IdentityHashMap<>();
        for(int i = 0; i < comps.size; i++){
            for(Building m : comps.get(i)){
                if(m.liquids == null) continue;
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

            LiquidModule old = entry.getKey();
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
     * 取 pos 最小的成员手里的那份模块，保证每次重建选到同一个池对象。
     *
     * 例外：并进核心的组合仓库，它的模块**就是核心库存**。如果网络里有这么一台，
     * 目标模块必须是它 —— 否则整个网络换成别的模块时，核心会凭空少掉这些物品。
     */
    private static ItemModule moduleOfFirst(Seq<Building> members, Seq<ItemModule> candidates){
        ItemModule core = null;
        int corePos = Integer.MAX_VALUE;
        for(Building m : members){
            if(m.items != null && coreLinked(m) && candidates.contains(m.items, true) && m.pos() < corePos){
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
            if(m.liquids != null && coreLinked(m) && candidates.contains(m.liquids, true) && m.pos() < corePos){
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
        for(int i = 1; i < unique.size; i++) moveItems(unique.get(i), dst);
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
        for(int i = 1; i < unique.size; i++) moveLiquids(unique.get(i), dst);
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

    private static float componentLiquidCap(Seq<Building> members){
        float total = 0f;
        for(Building m : members) total += ComboReflect.baseLiquidCap(m);
        return total;
    }

    private static void moveItems(ItemModule from, ItemModule to){
        if(from == null || to == null || from == to) return;
        for(Item item : content.items()){
            int amt = from.get(item);
            if(amt > 0){
                to.add(item, amt);
                from.remove(item, amt);
            }
        }
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

    private static Seq<Building> linkedHeatGroups(ComboNode.ComboNodeBuild node){
        Seq<Building> out = new Seq<>();
        ObjectSet<Building> seen = new ObjectSet<>();
        for(int i = 0; i < node.links.size; i++){
            Building link = world.build(node.links.get(i));
            if(link == null || !link.isValid() || !ComboReflect.isComboBuild(link)) continue;
            Building leader = ComboReflect.leader(link);
            if(leader != null && seen.add(leader)) out.add(leader);
        }
        return out;
    }

    private static float groupHeatSource(Building leader){
        for(Building m : ComboReflect.group(leader)){
            if(m.isValid() && m instanceof HeatBlock hb && !(m instanceof ComboNode.ComboNodeBuild)){
                return Math.max(0f, hb.heat());
            }
        }
        return 0f;
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
