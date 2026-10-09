package combine.production;

import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.game.EventType.Trigger;
import mindustry.graphics.Layer;
import mindustry.io.SaveFileReader;
import mindustry.io.SaveVersion;
import mindustry.gen.Building;
import mindustry.world.Tile;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import static mindustry.Vars.*;

/**
 * 「矿物阴影」：用户框选出来的矿物地板。
 *
 * <p>【用户 2026-10-08 要求】合体按钮旁边再加一个新按钮（{@code Icon.production}）：
 * 点它 → 拖框选一片矿物地板（只记有矿的格子）→ 给这些格子画一层阴影。阴影可以做**不规则**形状。
 *
 * <p><b>一片阴影 = 一个"资源输出站"</b>（用户第三版口径，本质和合体工厂 / 合体炮台同一套）：
 * <ul>
 *   <li>阴影上摆下的钻头**实际占地也压成 1x1**（每种钻头有一个隐藏的 1x1 版本，
 *       {@code CombinedDrill.onNewPlan} 在落地时自动顶替；挖矿仍按它原来 size×size 那片矿算）；</li>
 *   <li>这片阴影里的 1x1 钻头（本体那格落在阴影上）并成同一组、共用一口物品池**和一口液体池**
 *       —— 所以通水（冷却液）给任意一台就能加速整站，钻头越多产量越大；</li>
 *   <li>物品能从这片阴影的**任意一边**送出去（不限于挨着钻头那几格，见 {@code CombinedDrillBuild.dump}）；</li>
 *   <li>阴影形状可以是不规则的（按 4 邻连通分量算一片站）。</li>
 * </ul>
 *
 * <p>联机：产出是服务端权威的，所以阴影本身会同步（客户端改完发给服务端、服务端转给其它客户端、
 * 新玩家入服补一份，见 {@link OreShadowPacket}）；存档里另有自定义块（{@link Chunk}）带着走。
 *
 * <p>层：阴影画在 {@code Trigger.draw} 里、z 取 {@code Layer.floor + 0.01} —— 原版在开渲染前
 * 就 fire 这个事件（见 {@code Renderer.render}），配合 {@code Draw.sort(true)} 的 z 排序，
 * 它正好落在"地板/矿物之上、建筑之下"，缩到 1 格的钻头贴在上面不会被压暗。
 */
public class OreShadow {
  public static final String chunkName = "combine-ore-shadow";
  public static final byte format = 1;
  /** 单次读档的格子上限（坏档不让内存炸掉）。 */
  static final int maxTiles = 1 << 20;

  /** 被标成阴影的格子（{@code tile.pos()}）。 */
  static final IntSet marked = new IntSet();

  /** 绘制缓存：y → 这一行里所有连续段（x1,x2 成对）。{@code marked} 变了才重算。 */
  static IntMap<IntSeq> spans;
  /** 递增版本号：变了就重算 {@link #spans}。 */
  static int version = 1;
  static int spanVersion = -1;

  /** 本档这个块有没有真的被读到（决定换图时该不该清）。 */
  static boolean readApplied = false;

  public static void register() {
    Events.run(Trigger.draw, OreShadow::draw);
    // 联机同步：客户端改完发给服务端，服务端落地 + 转给其它客户端；新玩家入服补一份。
    try {
      mindustry.net.Net.registerPacket(OreShadowPacket::new);
      Events.on(mindustry.game.EventType.PlayerJoin.class, e -> {
        try {
          if (net != null && net.server() && e.player != null && e.player.con != null && !marked.isEmpty())
            sendZone(e.player.con);
        } catch (Throwable ignored) {
        }
      });
    } catch (Throwable t) {
      Log.err("[combine] 矿物阴影同步包注册失败（联机时阴影只在操作的那台机器上生效）", t);
    }
    try {
      SaveVersion.addCustomChunk(chunkName, new Chunk());
    } catch (Throwable t) {
      Log.err("[combine] 注册矿物阴影存档块失败（阴影不跨存档保留）", t);
    }
  }

  /** 换图（读地图，不是读存档）：先把上一张图的阴影清掉；读到本块再装回来。 */
  public static void onWorldLoadBegin() {
    readApplied = false;
    clear();
  }

  /** 读档/读图结束：这次没读到本块 = 这张图本来就没有阴影，补一次清空。 */
  public static void onLoadFinished() {
    if (!readApplied)
      clear();
    readApplied = false;
  }

  // ==================== 增删查 ====================

  public static boolean isEmpty() {
    return marked.isEmpty();
  }

  public static int size() {
    return marked.size;
  }

  /** 这一格在不在阴影里。 */
  public static boolean has(int pos) {
    return marked.contains(pos);
  }

  public static void clear() {
    if (marked.isEmpty())
      return;
    marked.clear();
    version++;
  }

  /**
   * 框选一片区域：**只认矿物地板**（{@code tile.drop() != null}，即下面有矿/矿墙的格子），
   * 逐格取反 —— 再框一次同一片就能把阴影去掉。
   *
   * @return {@code [新增数, 去掉数]}
   */
  public static int[] toggle(int x1, int y1, int x2, int y2) {
    int add = 0, remove = 0;
    for (int y = y1; y <= y2; y++) {
      for (int x = x1; x <= x2; x++) {
        Tile t = world.tile(x, y);
        if (t == null || t.drop() == null)
          continue;
        if (marked.contains(t.pos())) {
          marked.remove(t.pos());
          remove++;
        } else {
          marked.add(t.pos());
          add++;
        }
      }
    }
    if (add > 0 || remove > 0)
      version++;
    if (add > 0 || remove > 0)
      sendZone(null);
    return new int[] { add, remove };
  }

  // ==================== 联机同步 ====================

  /** 单包携带多少格（UDP 单包有限，分块发）。 */
  static final int syncChunk = 200;

  /** 把整片阴影发出去：{@code only == null} 时走 {@code net.send}（客户端→服务端 / 服务端→所有客户端）。 */
  static void sendZone(@Nullable mindustry.net.NetConnection only) {
    try {
      if (only == null && (net == null || !net.active()))
        return;
      IntSeq all = new IntSeq();
      marked.each(all::add);
      int chunks = Math.max(1, (all.size + syncChunk - 1) / syncChunk);
      for (int i = 0; i < chunks; i++) {
        int from = Math.min(i * syncChunk, all.size), to = Math.min(all.size, from + syncChunk);
        OreShadowPacket p = new OreShadowPacket();
        p.total = all.size;
        p.index = i;
        p.zone = new int[to - from];
        for (int k = from; k < to; k++)
          p.zone[k - from] = all.items[k];
        if (only != null)
          only.send(p, true);
        else
          net.send(p, true);
      }
    } catch (Throwable t) {
      Log.err("[combine] 矿物阴影同步发送失败", t);
    }
  }

  /** 收到一块：攒齐了整套再落地（整片替换，不是逐块相加）。 */
  static void receiveZone(OreShadowPacket p) {
    if (p == null)
      return;
    if (p.index == 0) {
      incoming.clear();
      incomingTotal = p.total;
    }
    if (p.total != incomingTotal) {
      incoming.clear();
      incomingTotal = p.total;
    }
    incoming.addAll(p.zone);
    if (incoming.size >= incomingTotal) {
      marked.clear();
      for (int i = 0; i < incoming.size; i++) {
        int pos = incoming.items[i];
        Tile t = world == null ? null : world.tile(arc.math.geom.Point2.x(pos), arc.math.geom.Point2.y(pos));
        if (t != null)
          marked.add(t.pos());
      }
      version++;
      incoming.clear();
      incomingTotal = -1;
    }
  }

  static final IntSeq incoming = new IntSeq();
  static int incomingTotal = -1;

  // ==================== 站点：一片连通的阴影 = 一个"资源生产地" ====================

  /** 站点表：站点 id → 该站点的格子（4 邻连通分量）。{@code marked} 变了才重算。 */
  static final IntMap<IntSet> sites = new IntMap<>();
  /** 格子 pos → 站点 id。 */
  static final IntMap<Integer> siteOf = new IntMap<>();
  static int siteVersion = Integer.MIN_VALUE;

  /** 重算连通分量（不规则阴影就是多个站点，或者一个 L 形站点）。 */
  static void ensureSites() {
    if (siteVersion == version)
      return;
    siteVersion = version;
    sites.clear();
    siteOf.clear();
    if (marked.isEmpty() || world == null)
      return;
    IntSeq lone = new IntSeq();
    marked.each(pos -> lone.add(pos));
    IntSeq queue = new IntSeq();
    int id = 0;
    for (int i = 0; i < lone.size; i++) {
      int start = lone.items[i];
      if (siteOf.containsKey(start))
        continue;
      IntSet comp = new IntSet();
      queue.clear();
      queue.add(start);
      siteOf.put(start, id);
      comp.add(start);
      while (queue.size > 0) {
        int pos = queue.pop();
        int x = arc.math.geom.Point2.x(pos), y = arc.math.geom.Point2.y(pos);
        for (int k = 0; k < 4; k++) {
          int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0);
          int ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
          int np = arc.math.geom.Point2.pack(nx, ny);
          if (marked.contains(np) && !siteOf.containsKey(np)) {
            siteOf.put(np, id);
            comp.add(np);
            queue.add(np);
          }
        }
      }
      sites.put(id, comp);
      id++;
    }
  }

  /** 这一格在不在某片阴影里。 */
  public static boolean inSite(@Nullable Tile tile) {
    return tile != null && siteIdOf(tile.pos()) >= 0;
  }

  /**
   * 一块 size×size 的地基（锚点 {@code (ax,ay)}，和 {@code Tile.getLinkedTilesAs} 同口径）
   * 有没有盖到阴影 —— 落地压格用它判断"这台钻头是不是摆在阴影上"。
   */
  public static boolean covers(int ax, int ay, int size) {
    if (marked.isEmpty())
      return false;
    int o = -((size - 1) / 2);
    for (int dx = 0; dx < size; dx++) {
      for (int dy = 0; dy < size; dy++) {
        Tile t = world.tile(ax + dx + o, ay + dy + o);
        if (t != null && marked.contains(t.pos()))
          return true;
      }
    }
    return false;
  }

  public static int siteIdOf(int pos) {
    ensureSites();
    Integer id = siteOf.get(pos);
    return id == null ? -1 : id;
  }

  /** 这一格所在站点覆盖的所有格子（不在阴影里就返回它自己，调用方走原版口径）。 */
  public static Seq<Tile> siteTilesOf(Tile center, Seq<Tile> out) {
    out.clear();
    if (center == null)
      return out;
    int id = siteIdOf(center.pos());
    if (id < 0) {
      out.add(center);
      return out;
    }
    IntSet comp = sites.get(id);
    if (comp == null) {
      out.add(center);
      return out;
    }
    comp.each(pos -> {
      Tile t = world.tile(arc.math.geom.Point2.x(pos), arc.math.geom.Point2.y(pos));
      if (t != null)
        out.add(t);
    });
    return out;
  }

  /**
   * 同一片阴影里的钻头（本体那格落在这片阴影上）。
   *
   * <p>这就是"同一个阴影区域自成一个资源生产地"：这些钻头并成同一组（共用一口物品池），
   * 物品也能从这片阴影的任意一边送出去。
   */
  public static Seq<CombinedDrill.CombinedDrillBuild> drillsInSiteOf(CombinedDrill.CombinedDrillBuild self) {
    Seq<CombinedDrill.CombinedDrillBuild> out = new Seq<>();
    if (self == null || self.tile == null)
      return out;
    int id = siteIdOf(self.tile.pos());
    if (id < 0)
      return out;
    IntSet comp = sites.get(id);
    if (comp == null)
      return out;
    comp.each(pos -> {
      Building b = world.build(arc.math.geom.Point2.x(pos), arc.math.geom.Point2.y(pos));
      if (b instanceof CombinedDrill.CombinedDrillBuild d && d.isValid())
        out.add(d);
    });
    return out;
  }

  /** 站点边界外面、能收物品的那些建筑（缓存：区域版本变了 / 每 0.25s 重算一次）。 */
  static final IntMap<Seq<Building>> outputsCache = new IntMap<>();
  static int outputsVersion = Integer.MIN_VALUE;
  static float outputsCacheTime = -9999f;

  public static Seq<Building> outputsOf(Tile center) {
    if (center == null)
      return EMPTY;
    int id = siteIdOf(center.pos());
    if (id < 0)
      return EMPTY;
    if (outputsVersion != version || arc.util.Time.time - outputsCacheTime > 0.25f) {
      outputsVersion = version;
      outputsCacheTime = arc.util.Time.time;
      outputsCache.clear();
    }
    Seq<Building> cached = outputsCache.get(id);
    if (cached != null)
      return cached;
    IntSet comp = sites.get(id);
    Seq<Building> out = new Seq<>();
    if (comp != null) {
      ObjectSet<Building> seen = new ObjectSet<>();
      comp.each(pos -> {
        int x = arc.math.geom.Point2.x(pos), y = arc.math.geom.Point2.y(pos);
        // k = -1：这一格**自己**上的建筑也算出口 —— 压成 1x1 之后玩家很可能把传送带
        // 直接铺在阴影区域里面（用户报的"阴影的物品输出不正常"就是这种摆法原来出不去）。
        for (int k = -1; k < 4; k++) {
          int nx = x + (k < 0 ? 0 : k == 0 ? 1 : k == 1 ? -1 : 0);
          int ny = y + (k < 0 ? 0 : k == 2 ? 1 : k == 3 ? -1 : 0);
          Tile t = world.tile(nx, ny);
          if (t == null || t.build == null || t.build.dead())
            continue;
          Building o = t.build;
          // 本站的**钻头**不算出口（同一口池子，倒给自己没意义）；别的建筑（传送带/容器/管道…）都算。
          if (o instanceof CombinedDrill.CombinedDrillBuild && siteIdOf(o.tile.pos()) == id)
            continue;
          if (seen.add(o))
            out.add(o);
        }
      });
    }
    outputsCache.put(id, out);
    return out;
  }

  static final Seq<Building> EMPTY = new Seq<>();

  /**
   * 【用户 2026-10-09】目标建筑紧挨着的那一格如果是本站的阴影格子，就返回它。
   *
   * <p>传送带这类"只收挨着自己那一格递过来的东西"（{@code ConveyorBuild.acceptItem} 里用
   * {@code Edges.getFacingEdge(source.tile, tile)}）—— 站在区域里的钻头离它可能隔了好几格，
   * 直接把物品塞给它会被拒（用户报的"阴影的物品输出不正常"）。递货时临时把"来源那一格"
   * 挪到这片阴影紧挨着它的那一格，物品就正常从区域那一边出去了。
   */
  public static @Nullable Tile adjacentSiteTile(Building target) {
    if (target == null || target.tile == null)
      return null;
    int tx = target.tile.x, ty = target.tile.y;
    int ts = Math.max(target.block == null ? 1 : target.block.size, 1);
    int o = -((ts - 1) / 2);
    for (int dx = 0; dx < ts; dx++)
      for (int dy = 0; dy < ts; dy++) {
        int bx = tx + dx + o, by = ty + dy + o;
        for (int k = 0; k < 4; k++) {
          int nx = bx + (k == 0 ? 1 : k == 1 ? -1 : 0);
          int ny = by + (k == 2 ? 1 : k == 3 ? -1 : 0);
          Tile t = world.tile(nx, ny);
          if (t != null && siteIdOf(t.pos()) >= 0 && (t.build == null || t.build.dead()))
            return t;
        }
      }
    return null;
  }

  /** 锚点所在的那片阴影（{@code size×size} 地基里第一块落在阴影上的格子所在的站）；-1 = 不在阴影上。 */
  public static int siteFor(int ax, int ay, int size) {
    ensureSites();
    int o = -((size - 1) / 2);
    for (int dx = 0; dx < size; dx++)
      for (int dy = 0; dy < size; dy++) {
        Tile t = world.tile(ax + dx + o, ay + dy + o);
        if (t != null) {
          int id = siteIdOf(t.pos());
          if (id >= 0)
            return id;
        }
      }
    return -1;
  }

  /**
   * 【用户 2026-10-08】"只要阴影区域没满，可以在阴影任意位置建造钻头，造完的钻头塞阴影没有被覆盖的地方"。
   *
   * @return 这片阴影里离 {@code (ax,ay)} 最近的**空位**（{@code {x,y}}）；这片已经满了返回 null。
   */
  public static int[] freeSpotFor(int ax, int ay, int size) {
    ensureSites();
    int id = siteFor(ax, ay, size);
    if (id < 0)
      return null;
    IntSet comp = sites.get(id);
    if (comp == null || comp.isEmpty())
      return null;
    IntSeq tmp = new IntSeq();
    comp.each(tmp::add);
    int bx = -1, by = -1, best = Integer.MAX_VALUE;
    for (int i = 0; i < tmp.size; i++) {
      int pos = tmp.items[i];
      int x = arc.math.geom.Point2.x(pos), y = arc.math.geom.Point2.y(pos);
      Tile t = world.tile(x, y);
      if (t == null || (t.build != null && !t.build.dead()))
        continue;
      if (x == ax && y == ay)
        return new int[] { x, y };
      int d = Math.abs(x - ax) + Math.abs(y - ay);
      if (d < best) {
        best = d;
        bx = x;
        by = y;
      }
    }
    return bx < 0 ? null : new int[] { bx, by };
  }

  // ==================== 绘制 ====================

  /** 阴影颜色（半透明黑：矿物地板被压暗一层，缩到 1 格的钻头画在它上面）。 */
  static final Color shadowColor = Color.valueOf("000000a8");

  static void draw() {
    if (headless || marked.isEmpty() || world == null)
      return;
    try {
      ensureSpans();
      Draw.z(Layer.floor + 0.01f);
      Draw.color(shadowColor);
      for (IntMap.Entry<IntSeq> e : spans) {
        int y = e.key;
        IntSeq row = e.value;
        if (row == null)
          continue;
        for (int k = 0; k + 1 < row.size; k += 2) {
          int x1 = row.get(k), x2 = row.get(k + 1);
          // 格子中心在 x*tilesize（原版 Floor.drawBase 同口径）
          Fill.crect((x1 - 0.5f) * tilesize, (y - 0.5f) * tilesize,
              (x2 - x1 + 1) * tilesize, tilesize);
        }
      }
      Draw.color();
      // 【不要在这里 Draw.flush()】原版的地板是在 Trigger.draw 之后才画的（FloorRenderer
      // 的 beginDraw 里有一次 flush）：这里自己 flush 会把阴影提前画到地板上、随即被地板盖住
      // （实测：三张截图里阴影一格都看不见）。留在批里按 z 排序，正好落在"地板之上、建筑之下"。
    } catch (Throwable t) {
      Log.err("[combine] 矿物阴影绘制失败（这一帧跳过）", t);
    }
  }

  /** 把散点压成"按行的连续段"：绘制时一行几段，几千格也只是几十次 fill。 */
  static void ensureSpans() {
    if (spanVersion == version && spans != null)
      return;
    spanVersion = version;
    IntMap<IntSeq> rows = new IntMap<>();
    marked.each(pos -> {
      if (pos < 0)
        return;
      // 注意：tile.pos() 是 Point2.pack(x,y)（x 在高 16 位），不是 x + y*width
      int x = arc.math.geom.Point2.x(pos), y = arc.math.geom.Point2.y(pos);
      IntSeq row = rows.get(y);
      if (row == null)
        rows.put(y, row = new IntSeq());
      row.add(x);
    });
    IntMap<IntSeq> out = new IntMap<>();
    for (IntMap.Entry<IntSeq> e : rows) {
      IntSeq row = e.value;
      row.sort();
      IntSeq merged = new IntSeq();
      int start = row.first(), prev = start;
      for (int k = 1; k < row.size; k++) {
        int x = row.get(k);
        if (x == prev + 1) {
          prev = x;
          continue;
        }
        merged.add(start, prev);
        start = prev = x;
      }
      merged.add(start, prev);
      out.put(e.key, merged);
    }
    spans = out;
  }

  // ==================== 存档 ====================

  /**
   * 自定义存档块。
   *
   * <pre>
   *   byte format
   *   int  count
   *   每项: short x; short y
   * </pre>
   *
   * 没有阴影时整块不写（{@link #shouldWrite()}），原版/别的模组读档时看不到这个块名。
   */
  public static class Chunk implements SaveFileReader.CustomChunk {
    @Override
    public boolean shouldWrite() {
      return !marked.isEmpty();
    }

    @Override
    public boolean writeNet() {
      return false;
    }

    @Override
    public void write(DataOutput stream) throws IOException {
      stream.writeByte(format);
      stream.writeInt(marked.size);
      IntSeq tmp = new IntSeq();
      marked.each(tmp::add);
      for (int i = 0; i < tmp.size; i++) {
        int pos = tmp.items[i];
        stream.writeShort(arc.math.geom.Point2.x(pos));
        stream.writeShort(arc.math.geom.Point2.y(pos));
      }
    }

    @Override
    public void read(DataInput stream) throws IOException {
      readApplied = true;
      marked.clear();
      version++;
      byte fmt = stream.readByte();
      int n = stream.readInt();
      if (fmt != format) {
        Log.warn("[combine] 矿物阴影块格式号 " + fmt + "（当前 " + format + "），已跳过");
        return;
      }
      if (n < 0 || n > maxTiles)
        throw new IOException("[combine] corrupt ore shadow count: " + n);
      int w = world == null ? 1 : Math.max(world.width(), 1);
      int h = world == null ? 1 : Math.max(world.height(), 1);
      for (int i = 0; i < n; i++) {
        int x = stream.readShort(), y = stream.readShort();
        if (x >= 0 && y >= 0 && x < w && y < h)
          marked.add(arc.math.geom.Point2.pack(x, y));
      }
    }
  }
}
