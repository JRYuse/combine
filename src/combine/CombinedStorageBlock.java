package combine;

import arc.Events;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.game.Teams.TeamData;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.modules.ItemModule;

import static mindustry.Vars.content;
import static mindustry.Vars.state;
import static mindustry.Vars.world;

/**
 * 组合仓库（container / vault，及它们的强化版）。
 *
 * 并仓机制全部收在本类里，两条规则：
 *
 * 1) 没连核心 —— 跟本模组其它组合建筑一样：相邻成组的仓库**共用一个物品模块**，
 *    组容量 = 各成员容量之和（每个成员 getMaximumAccepted 都返回组容量）。
 *    拆开时按容量比例把池子分开，不丢物品。
 *
 * 2) 连通到核心 —— 原版规则（CoreBlock.CoreBuild.onProximityUpdate）的传递闭包：
 *    核心容量 = 自身 + **所有连通**的仓库容量，这些仓库也并进核心的库存模块。
 *    原版只认"直接相邻"一层；这里按 proximity 洪水填充，任意深度都算
 *    （a 连 b、b 连核心 ⇒ a 也算）。
 *    断开（拆掉中间那个）时：分离出去的仓库库存早已在核心池里，所以只解链、给它一份
 *    空模块（=「a 中所有物品进入核心」），再按缩小后的容量把核心库存截断
 *    （=「超出核心容量的部分截断」）。
 *
 * 全程只改核心自己的 storageCapacity 字段与仓库的 linkedCore/items，不替换原版核心。
 */
public class CombinedStorageBlock extends StorageBlock {

  // ==================== 机制驱动 ====================

  /** 排查用：置 true 会把每次重算的细节打进日志。 */
  public static boolean debug = false;

  /**
   * 已登记的组合仓库。
   * 仓库方块 update=false，不会进 Groups.build，所以只能由仓库自己在 created()/onRemoved() 里登记。
   */
  private static final ObjectSet<CombinedStorageBuild> tracked = new ObjectSet<>();

  /** 有建筑变化时置位；每帧最多重算一次（保证在原版 proximity 更新之后跑）。 */
  private static boolean dirty = false;
  /** 读档那一轮：仓库手里是"核心库存的副本"，内容相同要丢弃而不是相加（否则读档翻倍）。 */
  private static boolean dedupeOnce = false;

  /** 在 Main.init() 里注册一次。 */
  public static void register() {
    // Trigger 是 enum：必须用 Events.run(具体常量) 注册，Events.on(Trigger.class) 收不到
    Events.run(EventType.Trigger.update, CombinedStorageBlock::update);
    Events.on(EventType.WorldLoadBeginEvent.class, e -> {
      dedupeOnce = true;
      dirty = true;
    });
    Events.on(EventType.WorldLoadEvent.class, e -> rescan());
    // 放置/拆除/替换方块都会触发；这里只置位，真正重算留到之后
    Events.on(EventType.TileChangeEvent.class, e -> dirty = true);
    Events.on(EventType.BlockBuildEndEvent.class, e -> dirty = true);
  }

  /** 组合连接器/节点网络要用：仓库不在 Groups.build 里，得把这个登记表交出去。 */
  public static ObjectSet<CombinedStorageBuild> trackedSet() {
    return tracked;
  }

  /** 外部也可以主动叫一次。 */
  public static void markDirty() {
    dirty = true;
  }

  private static void update() {
    if (!dirty || state == null || world == null || world.isGenerating()) return;
    dirty = false;
    // 读档完成后的第一次重算带着"去重"语义；只有真正算完才清掉这个标记
    boolean dedupe = dedupeOnce;
    dedupeOnce = false;
    rebuildAll(dedupe);
  }

  /** 刷新所有队伍的"仓库↔核心"连通关系、容量与并仓。 */
  public static void rebuildAll() {
    rebuildAll(false);
  }

  public static void rebuildAll(boolean dedupe) {
    if (state == null || world == null || world.isGenerating()) {
      dirty = true;
      return;
    }
    try {
      // 注意：不能用 state.teams.present —— 读档/服务器环境下它可能是空的，
      // 而队伍数据（cores / 仓库）是好的。这里遍历所有队伍：
      // 有核心的（要扩容）、或者有登记仓库的（要走"独立组合仓库组"规则）都要处理。
      for (Team team : Team.all) {
        TeamData data = team.data();
        if (data == null) continue;
        if (data.cores.isEmpty() && !hasTracked(team)) continue;
        rebuildTeam(data, dedupe);
      }
    } catch (Throwable t) {
      Log.err("[combine] 组合仓库↔核心 重算失败", t);
    }
  }

  private static boolean hasTracked(Team team) {
    for (CombinedStorageBuild s : tracked) {
      if (s != null && s.isValid() && s.team == team) return true;
    }
    return false;
  }

  /** 仓库登记 / 注销（由 {@link CombinedStorageBuild} 自己调用）。 */
  public static void track(CombinedStorageBuild storage) {
    if (storage != null && tracked.add(storage)) dirty = true;
  }

  public static void untrack(CombinedStorageBuild storage) {
    if (storage != null && tracked.remove(storage)) dirty = true;
  }

  /** 读档后全图扫一遍重建登记表（兜底，正常情况下 created() 已经登记过）。 */
  public static void rescan() {
    tracked.clear();
    if (world != null) {
      for (Tile tile : world.tiles) {
        if (tile != null && tile.build instanceof CombinedStorageBuild sb && sb.coreMergeStorage()) {
          tracked.add(sb);
        }
      }
    }
    dirty = true;
  }

  private static void rebuildTeam(TeamData data, boolean dedupe) {
    Seq<CoreBuild> cores = new Seq<>(data.cores);

    // ---- 1) 本队所有参与并仓的仓库，按"仓库↔仓库相邻"求连通分量 ----
    Seq<CombinedStorageBuild> storages = new Seq<>();
    for (CombinedStorageBuild sb : tracked) {
      if (sb != null && sb.isValid() && sb.team == data.team && sb.coreMergeStorage()) storages.add(sb);
    }

    ObjectSet<CombinedStorageBuild> visited = new ObjectSet<>();
    Seq<Seq<CombinedStorageBuild>> comps = new Seq<>();

    // 同一张组合连接器/节点网络里的仓库，语义上和"直接贴着"完全一样，必须算同一个分量：
    // 容量相加、共用一个池子、面板显示整张网络。否则就会出现"池子共享了、容量只算自己"。
    arc.struct.IntMap<Seq<CombinedStorageBuild>> byNetwork = new arc.struct.IntMap<>();
    for (CombinedStorageBuild s : storages) {
      int key = ComboNet.networkKey(s);
      if (key != 0) byNetwork.get(key, Seq::new).add(s);
    }

    for (CombinedStorageBuild start : storages) {
      if (visited.contains(start)) continue;
      Seq<CombinedStorageBuild> comp = new Seq<>();
      Queue<CombinedStorageBuild> queue = new Queue<>();
      queue.addLast(start);
      visited.add(start);
      while (!queue.isEmpty()) {
        CombinedStorageBuild cur = queue.removeFirst();
        comp.add(cur);
        for (Building nb : cur.proximity) {
          if (nb instanceof CombinedStorageBuild other && other.team == data.team
              && other.coreMergeStorage() && visited.add(other)) {
            queue.addLast(other);
          }
        }
        int key = ComboNet.networkKey(cur);
        if (key != 0) {
          Seq<CombinedStorageBuild> sameNet = byNetwork.get(key);
          if (sameNet != null) {
            for (CombinedStorageBuild other : sameNet) {
              if (other != cur && other.team == data.team && visited.add(other)) queue.addLast(other);
            }
          }
        }
      }
      comps.add(comp);
    }

    if (debug) {
      StringBuilder sb = new StringBuilder();
      for (Seq<CombinedStorageBuild> comp : comps) {
        sb.append('[');
        for (CombinedStorageBuild x : comp) {
          sb.append(x.block.name).append('@').append(x.tileX()).append(',').append(x.tileY()).append(' ');
        }
        sb.append("] ");
      }
      Log.info("[combine] 重算 team=@ 已登记=@ 分量=@ -> @", data.team.name, storages.size, comps.size, sb);
    }

    // ---- 2) 每个分量：挨着核心 → 并进核心；否则独立组合仓库组 ----
    int capacity = 0;
    for (CoreBuild core : cores) {
      if (core != null && core.isValid()) capacity += core.block.itemCapacity;
    }

    ObjectSet<CombinedStorageBuild> linkedNow = new ObjectSet<>();
    for (Seq<CombinedStorageBuild> comp : comps) {
      int compCap = 0;
      for (CombinedStorageBuild s : comp) compCap += s.block.itemCapacity;

      CoreBuild core = adjoiningCore(comp, data);
      if (core != null) {
        capacity += compCap;
        for (CombinedStorageBuild s : comp) {
          s.linkToCore(core, dedupe);
          linkedNow.add(s);
        }
      } else {
        standalone(comp, compCap, dedupe);
      }
    }

    // ---- 3) 曾经连着核心、这次不再连通的仓库：解链（物品已经留在核心池里） ----
    for (CombinedStorageBuild s : storages) {
      if (s.linkedCore != null && !linkedNow.contains(s)) s.unlinkFromCore();
    }

    // ---- 4) 刚被拆开的独立组之间可能还共用同一个模块：按容量比例分开，不丢物品 ----
    splitSharedModules(comps);

    // ---- 5) 核心容量 = 自身 + 所有连通的仓库；超容截断 ----
    ItemModule coreItems = null;
    for (CoreBuild core : cores) {
      if (core != null && core.isValid()) {
        core.storageCapacity = capacity;
        if (coreItems == null) coreItems = core.items;
      }
    }
    if (coreItems != null) {
      for (Item item : content.items()) {
        coreItems.set(item, Math.min(coreItems.get(item), capacity));
      }
    }
  }

  /** 这个分量里有没有成员紧挨着本队的核心（有就整块并进核心）。 */
  private static CoreBuild adjoiningCore(Seq<CombinedStorageBuild> comp, TeamData data) {
    for (CombinedStorageBuild s : comp) {
      for (Building nb : s.proximity) {
        if (nb instanceof CoreBuild core && core.team == data.team && core.isValid()) return core;
      }
    }
    return null;
  }

  /**
   * 独立组合仓库组（没连核心）：跟其它组合建筑一样，整组共用一个物品模块、容量相加。
   * 注意刚从核心上分离出来的仓库，手里那份模块其实是核心的池子（物品归核心），
   * 必须换成空模块，别把核心池抢过来。
   */
  private static void standalone(Seq<CombinedStorageBuild> comp, int groupCap, boolean dedupe) {
    for (CombinedStorageBuild s : comp) {
      if (s.linkedCore != null) {
        s.items = new ItemModule(); // 它的库存已经并进核心，这里从空开始
        s.linkedCore = null;
      }
    }

    CombinedStorageBuild leader = comp.first();
    for (CombinedStorageBuild s : comp) {
      if (s.pos() < leader.pos()) leader = s;
    }
    ItemModule pool = leader.items != null ? leader.items : new ItemModule();

    // 连了组合连接器/节点时，容量和池子都按"整张网络"算：整组报告网络合计容量，
    // 相当于这些机器直接贴在一起。池子目标也沿用 ComboNet 选中的那一份，避免两边来回抢。
    int netCap = ComboNet.networkItemCap(leader);
    if (netCap > groupCap) groupCap = netCap;
    int netSize = ComboNet.networkSize(leader);
    ItemModule netPool = ComboNet.poolModuleFor(leader);
    if (netPool != null) pool = netPool;

    for (CombinedStorageBuild s : comp) {
      if (s.items != null && s.items != pool) {
        boolean duplicate = dedupe && sameItems(s.items, pool);
        if (!duplicate) moveItems(s.items, pool);
      }
    }
    for (CombinedStorageBuild s : comp) {
      s.items = pool;
      s.linkedCore = null;
      s.comboStorageCap = groupCap;
      s.comboGroupSize = Math.max(comp.size, netSize);
    }
    clamp(pool, groupCap);
  }

  /** 同一个模块被多个"独立组合仓库组"共用（刚被拆开）时，按各组容量比例拆分。 */
  private static void splitSharedModules(Seq<Seq<CombinedStorageBuild>> comps) {
    ObjectMap<ItemModule, Seq<Seq<CombinedStorageBuild>>> byModule = new ObjectMap<>();
    for (Seq<CombinedStorageBuild> comp : comps) {
      if (comp.isEmpty()) continue;
      CombinedStorageBuild first = comp.first();
      if (first.linkedCore != null || first.items == null) continue;
      byModule.get(first.items, Seq::new).add(comp);
    }

    for (ObjectMap.Entry<ItemModule, Seq<Seq<CombinedStorageBuild>>> entry : byModule) {
      Seq<Seq<CombinedStorageBuild>> users = entry.value;
      if (users.size <= 1) continue;

      ItemModule old = entry.key;
      int n = users.size;
      int[] caps = new int[n];
      int totalCap = 0;
      for (int i = 0; i < n; i++) {
        int cap = 0;
        for (CombinedStorageBuild s : users.get(i)) cap += s.block.itemCapacity;
        caps[i] = Math.max(cap, 1);
        totalCap += caps[i];
      }

      ItemModule[] shares = new ItemModule[n];
      for (int i = 0; i < n; i++) shares[i] = new ItemModule();
      for (Item item : content.items()) {
        int total = old.get(item);
        if (total <= 0) continue;
        int remaining = total;
        for (int i = 0; i < n; i++) {
          int ideal = i == n - 1 ? remaining : Math.round(total * (float) caps[i] / totalCap);
          ideal = Math.max(0, Math.min(ideal, remaining));
          if (ideal > 0) {
            shares[i].add(item, ideal);
            remaining -= ideal;
          }
        }
      }
      for (int i = 0; i < n; i++) {
        for (CombinedStorageBuild s : users.get(i)) s.items = shares[i];
        clamp(shares[i], caps[i]);
      }
    }
  }

  private static void clamp(ItemModule module, int capacity) {
    if (module == null) return;
    for (Item item : content.items()) {
      module.set(item, Math.min(module.get(item), capacity));
    }
  }

  private static void moveItems(ItemModule from, ItemModule to) {
    if (from == null || to == null || from == to) return;
    for (Item item : content.items()) {
      int amount = from.get(item);
      if (amount > 0) {
        to.add(item, amount);
        from.remove(item, amount);
      }
    }
  }

  private static boolean sameItems(ItemModule a, ItemModule b) {
    if (a == b) return true;
    if (a == null || b == null) return false;
    for (Item item : content.items()) {
      if (a.get(item) != b.get(item)) return false;
    }
    return true;
  }

  // ==================== 方块 ====================

  public CombinedStorageBlock(String name) {
    super(name);
  }

  public class CombinedStorageBuild extends StorageBuild {
    /**
     * 独立组合仓库组的组容量（>0 表示"没连核心，组内共用一个池子"）；
     * 并进核心时置 -1，容量一律走核心的 storageCapacity。
     */
    public int comboStorageCap = -1;
    /** 独立组的成员数（仅用于面板显示）。 */
    public int comboGroupSize = 1;

    /** 是否参与核心并仓（原版 container/vault 是 true，强化版是 false）。 */
    public boolean coreMergeStorage() {
      return coreMerge;
    }

    /** 当前实际可存容量：并进核心 → 核心容量；独立组 → 组容量；都没在组里 → 自身容量。 */
    public int comboCapacity() {
      if (linkedCore instanceof CoreBuild core && core.isValid()) return core.storageCapacity;
      return comboStorageCap > 0 ? comboStorageCap : itemCapacity;
    }

    /** 面板上那行说明（UI 与测试共用同一处，免得两边算得不一样）。 */
    public String poolLabel() {
      int cap = Math.max(comboCapacity(), 1);
      return linkedCore instanceof CoreBuild
          ? "[accent]已并入核心[] 容量 " + cap
          : "[accent]组合仓库组 x" + comboGroupSize + "[] 容量 " + cap;
    }

    /** 并进核心：共用核心的物品模块（容量已计入核心）。 */
    void linkToCore(CoreBuild core, boolean dedupe) {
      if (items != core.items) {
        // 仓库里本来有自己的一份库存 → 真正并入；
        // 但读档那一轮，每个链接仓库都写了一份核心库存的副本，内容相同就是副本，丢弃而不是相加。
        boolean duplicate = dedupe && sameItems(items, core.items);
        if (!duplicate) moveItems(items, core.items);
      }
      items = core.items;
      linkedCore = core;
      comboStorageCap = -1;
      comboGroupSize = 1;
    }

    /** 解链：物品早已在核心池里（链接期间共用模块），这里给它一份空模块即可。 */
    void unlinkFromCore() {
      linkedCore = null;
      items = new ItemModule();
      comboStorageCap = -1;
      comboGroupSize = 1;
    }

    @Override
    public void created() {
      super.created();
      track(this);
    }

    @Override
    public void onRemoved() {
      untrack(this);
      super.onRemoved();
    }

    @Override
    public int getMaximumAccepted(Item item) {
      if (linkedCore != null) return linkedCore.getMaximumAccepted(item);
      return comboStorageCap > 0 ? comboStorageCap : itemCapacity;
    }

    @Override
    public double sense(mindustry.logic.LAccess sensor) {
      if (sensor == mindustry.logic.LAccess.itemCapacity && linkedCore == null && comboStorageCap > 0) {
        return comboStorageCap;
      }
      return super.sense(sensor);
    }

    @Override
    public void display(Table table) {
      // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
      ComboUi.safe("combinedstorageblock:display", () -> displayInner(table));
    }

    void displayInner(Table table) {
      super.display(table);
      ComboNet.addNetworkDisplay(table, this, 1);
      showPool(table);
    }

    /** 显示组合仓库当前共用的池子（独立组 = 组池；并进核心 = 核心池）。 */
    void showPool(Table table) {
      int cap = Math.max(comboCapacity(), 1);
      table.row();
      table.add(poolLabel()).left();
      if (items != null) {
        for (Item item : content.items()) {
          int amount = items.get(item);
          if (amount <= 0) continue;
          table.row();
          table.add(new mindustry.ui.Bar(
              () -> item.localizedName + ": " + amount + "/" + cap,
              () -> item.color,
              () -> (float) amount / cap)).growX().height(18f).pad(4).left();
        }
      }
    }
  }
}
