package combine.turret;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Eachable;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import combine.BlockCloner;
import combine.net.ComboNet;
import combine.util.ComboReflect;
import combine.util.IComboGrouped;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.ControlBlock;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.consumers.ConsumePower;
import mindustry.world.draw.DrawTurret;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;
import mindustry.world.meta.BlockStatus;

import static mindustry.Vars.*;

/**
 * 超级组合炮台：一台方块内部塞进 N 台（边长² 以内）独立炮台。
 *
 * <p>玩法：按快捷键框选一片区域 → 数出里面所有炮台 → 按数量取边长
 * （2~4 台 → 2x2，5~9 台 → 3x3，…，最多 6x6）→ 生成一台该边长的建筑，
 * 每格画一台被选中的炮台（炮台不够的格子留空）。
 *
 * <h3>实现（为什么不是"多台真建筑"）</h3>
 * 每格是一个<b>虚拟炮台</b>：用原版炮台方块（组合方块换回它替换前的原版实例）
 * {@code newBuilding()} 造一个真实 build 对象，挂在一张"离网"的假 Tile 上
 * （坐标就是这一格的世界坐标），由本方块每帧代它跑 {@code update()}：
 *
 * <ul>
 *   <li><b>物品/液体</b>：各格把 {@code items}/{@code liquids} 指向本方块那一份模块
 *       —— 原版消费者/冷却液读取的都是模块，于是"全组共用一份池子"不需要改原版代码；
 *       ItemTurret 那类把弹药存在自己 {@code ammo} 队列里的，由本方块每帧从共享池
 *       "搬一点"进格（和邻居送货一模一样：remove + handleItem，不凭空造货）。</li>
 *   <li><b>电力</b>：本方块用自定义 {@link SuperConsumePower} 把各格的用电量加总上报电网，
 *       电网算出的满足率再回写给每格（格自己不进电网，免得和本方块的格子坐标打架）。</li>
 *   <li><b>热量</b>：每格的 proximity 指到"与这一格相邻的真实建筑"，于是隔壁的产热机
 *       按原版那套接触点算法给这一格供热。</li>
 *   <li><b>独立逻辑</b>：每格各自 update / 冷却 / 装填 / 找目标 / 开火 / 后坐，互不影响。</li>
 * </ul>
 *
 * <p>本方块带 {@code comboGroup/comboLeader/comboDirty} 这套约定字段，所以组合连接器、
 * 组合节点、以及别的组合建筑都能把它当普通组合体接起来（超级炮台之间也能互相组合）。
 */
public class SuperTurret extends Block {
  /**
   * 总开关。2026-09-25 用户改了口径之后重新启用（描述见下）：
   * 关掉的话方块一个都不注册、框选入口（HUD 按钮/快捷键）也不挂，游戏里完全看不到它。
   */
  public static boolean enabled = true;

  /**
   * 最小/最大边长。原来上限是 6（36 台），用户问"为什么限制在 6x6" —— 放开到 10（100 台）。
   * 7x7 以上没有现成贴图（assets 里只有 super-turret-2..6），底板改成程序化绘制，见
   * {@link #drawPlate}。
   */
  public static final int MIN_SIDE = 2, MAX_SIDE = 10;
  /** 弹药选择面板里"一格一个按钮"的大小（用户要求面板改小、按边长换行）。 */
  static final float CELL_BTN_W = 42f, CELL_BTN_H = 30f;
  /** 每种边长一个方块实例（下标 = 边长），由 Main 在内容装配时填。 */
  public static final SuperTurret[] bySide = new SuperTurret[MAX_SIDE + 1];
  /** 内部用的"热量探针"方块（不落地、不进建造菜单，只用来把整台的热量喂给每一格）。 */
  public static @Nullable HeatProbe heatProbe;

  public final int side;
  /** 供 {@link ComboReflect#baseLiquidCap} 读取的"本机基础容量"（按边长估的上限）。 */
  public float baseLiquidCapacity;
  /**
   * 热量需求"标记值"：只用来让 ComboNet 认出"这台是吃热的"（必须 &gt; 0）。本方块本身不实现 HeatConsumer ——
   * 这个字段是给 ComboNet 看的：组合网络做热量分配时要认出"这台组合体是吃热的"，
   * 才会把网络总热量通过热探针喂进它的 proximity（否则"制热机 --组合节点--> 劫难"
   * 这条链路的热量永远到不了，劫难只能读到贴在自己身上的零星热源）。
   *
   * <p>【别拿它当"整台需求"】真正要多少热 = 里面每一台需热格子 heatRequirement 之和，
   * 见 {@code SuperTurretBuild.heatDemand()}（用户报的"合体后只要一个炮台的热量"就是这里）。
   */
  public float heatRequirement = 10f;
  /** 没有对应边长的贴图时，底板改成程序化绘制（7x7 以上）。 */
  public boolean proceduralPlate = false;
  /** 部件绘制失败告警：每种 (方块+部件类) 只报一次，避免每帧刷屏。 */
  static final ObjectSet<String> partDrawErrWarned = new ObjectSet<>();

  public SuperTurret(String name, int side) {
    super(name);
    this.side = side;
    size = side;
    update = true;
    solid = true;
    sync = true;
    saveConfig = true;
    configurable = true; // 点方块可以给每一格选弹药（选完的弹药整组共享池里只喂这一种）
    hasItems = true;
    hasLiquids = true;
    canOverdrive = false;
    rotate = false;
    drawArrow = false;
    itemCapacity = side * side * 30;
    // 见 CombinedTurret：原版管道按"目标方块的 liquidCapacity"算流量，
    // 共享池比单台大得多时必须给足假容量，真正的上限由 acceptLiquid 拦。
    liquidCapacity = 9999f;
    baseLiquidCapacity = side * side * 20f;
    buildType = SuperTurretBuild::new;
    consume(new SuperConsumePower());
  }

  // ==================== 选炮台 / 布局编解码 ====================

  /** 需要几台炮台就要多大的边长：ceil(sqrt(n))，夹在 MIN_SIDE..MAX_SIDE。 */
  public static int sideFor(int count) {
    int side = MIN_SIDE;
    while (side < MAX_SIDE && side * side < count)
      side++;
    return side;
  }

  /** 边长对应的方块实例（内容还没装配时返回 null）。 */
  public static SuperTurret blockForSide(int side) {
    if (side < MIN_SIDE || side > MAX_SIDE)
      return null;
    return bySide[side];
  }

  /** 一台世界里的炮台 → 该拿来当"格子炮台"的方块（组合方块换回它替换前的原版实例）。 */
  public static @Nullable Block cellBlock(Block worldBlock) {
    if (!(worldBlock instanceof Turret))
      return null;
    Block orig = BlockCloner.comboToOriginal.get(worldBlock);
    Block b = orig != null ? orig : worldBlock;
    return b instanceof Turret ? b : null;
  }

  /** 布局串里的一格：';' 分隔，空格子 = 空串，否则 "方块名@朝向度数"。 */
  public static String[] cellTokens(String layout) {
    if (layout == null || layout.isEmpty())
      return new String[0];
    return layout.split(";", -1);
  }

  public static String encodeCell(Block b, float rot) {
    return b == null ? "" : b.name + "@" + Math.round(rot);
  }

  /** 布局串里的方块（null = 空格）。 */
  public static @Nullable Block blockOfToken(String token) {
    if (token == null || token.isEmpty())
      return null;
    int at = token.lastIndexOf('@');
    String name = at < 0 ? token : token.substring(0, at);
    // 名字反查拿到的是"注册表里的那一个"（组合方块接管了原版名字），
    // 再走一遍 cellBlock() 换回它替换前的原版实例 —— 格子用的是原版逻辑。
    return cellBlock(content.block(name));
  }

  public static float rotOfToken(String token) {
    if (token == null || token.isEmpty())
      return 90f;
    int at = token.lastIndexOf('@');
    if (at < 0)
      return 90f;
    try {
      return Float.parseFloat(token.substring(at + 1));
    } catch (Throwable t) {
      return 90f;
    }
  }

  // ==================== 配置串（联机安全） ====================
  //
  // 【用户报】"文本数据包太长了超出上限会踢出客户端"：原版把方块的 config 用
  // TypeIO.writeObject 塞进 ConstructFinish / TileConfig 包，客户端读的是
  // TypeIO.readObjectSafe —— 里面有硬上限 1200 字符，超了直接抛
  // "String too long: 1200" 把客户端踢掉（见 ~/sd/q/last_log (8).txt）。
  // 老的 config() 返回 "方块名@朝向;…" + "x,y;…"，10x10 超级炮台（100 格）配上
  // 长名字的模组炮台轻松过千、加上 sources 必超。这里改成紧凑二进制 + base64：
  // 每格 4 字节（id + 朝向）、每个坐标 4 字节，100 格满载 ≈ 1070 字符，稳在 1200 以内。
  // 老的 "layout|sources" 文本串仍然能读（旧存档/旧蓝图/旧客户端），只是不再往外写。

  /** 紧凑配置串前缀：'~' + base64(二进制)。 */
  public static final String CONFIG_TAG = "~";
  /** 原版 readObjectSafe 的上限是 1200，留点余量。 */
  public static final int CONFIG_MAX = 1150;

  /** 把 layout + sources 编成"短到能过联机"的配置串。 */
  public static String encodeConfig(String layout, String sources) {
    try {
      String enc = CONFIG_TAG + pack64(layout, sources);
      if (enc.length() <= CONFIG_MAX)
        return enc;
      // 极端情况（格子/名字多到超限）：先丢掉 sources（只影响"落地后拆掉被框选的炮台"这一步），
      // 还不够就只保留前 N 个格子 —— 绝不返回一个超限的串把客户端踢出去。
      enc = CONFIG_TAG + pack64(layout, "");
      if (enc.length() <= CONFIG_MAX)
        return enc;
      String[] toks = cellTokens(layout);
      int keep = toks.length;
      while (keep > 0 && (CONFIG_TAG + pack64(joinCells(toks, keep), "")).length() > CONFIG_MAX)
        keep--;
      return CONFIG_TAG + pack64(joinCells(toks, keep), "");
    } catch (Throwable t) {
      Log.err("[combine] 超级组合炮台：配置串编码失败（改用逐格文本）", t);
      return layout == null ? "" : layout;
    }
  }

  static String joinCells(String[] toks, int keep) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < keep && i < toks.length; i++) {
      if (i > 0)
        sb.append(';');
      sb.append(toks[i]);
    }
    return sb.toString();
  }

  /** 逐格文本 layout/sources → base64(紧凑二进制)。 */
  static String pack64(String layout, String sources) {
    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream d = new java.io.DataOutputStream(buf);
    try {
      String[] toks = cellTokens(layout);
      d.writeByte(1);
      d.writeShort(toks.length);
      for (String tok : toks) {
        Block cb = blockOfToken(tok);
        d.writeShort(cb == null ? 0xFFFF : (cb.id & 0xFFFF));
        d.writeShort(Math.round(rotOfToken(tok)) & 0xFFFF);
      }
      String[] src = (sources == null || sources.isEmpty()) ? new String[0] : sources.split(";");
      d.writeShort(src.length);
      for (String s : src) {
        int comma = s == null ? -1 : s.indexOf(',');
        if (comma < 0) {
          d.writeShort(0);
          d.writeShort(0);
          continue;
        }
        try {
          d.writeShort(Integer.parseInt(s.substring(0, comma).trim()) & 0xFFFF);
          d.writeShort(Integer.parseInt(s.substring(comma + 1).trim()) & 0xFFFF);
        } catch (Throwable t) {
          d.writeShort(0);
          d.writeShort(0);
        }
      }
      d.flush();
    } catch (Throwable ignored) {
    }
    return new String(arc.util.serialization.Base64Coder.encode(buf.toByteArray()))
        .replace("\n", "").replace("\r", "");
  }

  /** 解出 {layout(逐格文本), sources(逐格文本)}；老的 "layout|sources" 文本串也认。 */
  public static String[] decodeConfig(String cfg) {
    if (cfg == null || cfg.isEmpty())
      return new String[] { "", "" };
    if (!cfg.startsWith(CONFIG_TAG)) {
      int bar = cfg.indexOf('|');
      return bar < 0 ? new String[] { cfg, "" } : new String[] { cfg.substring(0, bar), cfg.substring(bar + 1) };
    }
    try {
      byte[] raw = arc.util.serialization.Base64Coder.decode(cfg.substring(CONFIG_TAG.length()).trim());
      java.io.DataInputStream d = new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw));
      d.readByte(); // 格式号
      int n = d.readUnsignedShort();
      StringBuilder lay = new StringBuilder();
      for (int i = 0; i < n; i++) {
        int id = d.readUnsignedShort();
        int rot = d.readShort();
        if (i > 0)
          lay.append(';');
        if (id == 0xFFFF)
          continue;
        Block b = content.block(id);
        Block cb = cellBlock(b);
        String nm = cb != null ? cb.name : (b == null ? "" : b.name);
        if (nm == null || nm.isEmpty())
          continue;
        lay.append(nm).append('@').append(rot);
      }
      StringBuilder src = new StringBuilder();
      int sc = d.readUnsignedShort();
      for (int i = 0; i < sc; i++) {
        int x = d.readShort(), y = d.readShort();
        if (i > 0)
          src.append(';');
        src.append(x).append(',').append(y);
      }
      return new String[] { lay.toString(), src.toString() };
    } catch (Throwable t) {
      Log.err("[combine] 超级组合炮台：配置串解码失败（按空布局处理）", t);
      return new String[] { "", "" };
    }
  }

  /** 多格方块的格子在"原点 tile"基础上的偏移（和原版 setBlock 的多格展开一致）。 */
  public static int cellTile(int origin, int size, int index) {
    return origin - (size - 1) / 2 + index;
  }

  // ==================== 热量探针 ====================

  /**
   * 一格炮台自己的 {@code calculateHeat()} 只看得到"和这一格相邻"的东西 —— 组合节点/连接器
   * 的热量只会到贴着的那个格子。为了做到"整台共用一个热量池"（也让节点传热对所有格子生效），
   * 每格挂一个探针：探针就摆在格子自己的坐标上、{@code rotate=false}，所以原版
   * {@code calculateHeat()} 会把它算成"贴着这一格的热源"，其 heat() 就是整台的热量。
   */
  public static class HeatProbe extends Block implements mindustry.world.blocks.heat.HeatBlock {
    public HeatProbe(String name) {
      super(name);
      size = 1;
      rotate = false;
      update = false;
      solid = false;
      destructible = false;
      buildVisibility = mindustry.world.meta.BuildVisibility.hidden;
      category = mindustry.type.Category.effect;
      buildType = HeatProbeBuild::new;
    }

    @Override
    public float heat() {
      return 0f;
    }

    @Override
    public float heatFrac() {
      return 0f;
    }

    public class HeatProbeBuild extends Building implements mindustry.world.blocks.heat.HeatBlock {
      public float amount;

      @Override
      public float heat() {
        return amount;
      }

      @Override
      public float heatFrac() {
        return 0f;
      }
    }
  }

  // ==================== 虚影 ====================

  /**
   * 不进建造菜单 / 数据库：入口是 HUD 上的按钮（用户要求"通过点击 ui 按钮触发框选，
   * 而不是建造界面的建筑"）。
   */
  @Override
  public boolean isVisible() {
    return false;
  }

  /**
   * 但**放置流程必须仍然合法**：{@code Build.validPlace} / {@code BuilderComp} 读的是
   * {@code isPlaceable()}（原版默认实现里含 {@code isVisible()}），这里单独放行，
   * 于是"菜单里看不到、但框选之后能正常放下去"。
   */
  @Override
  public boolean isPlaceable() {
    return supportsEnv(state.rules.env) && (!isBanned() || state.rules.editor);
  }

  /** 一格的绘制缩放：把这一格炮台贴图缩到一格以内（= 用户要的 1x1 小炮）。 */
  public static float cellScale(Block cb) {
    if (cb == null)
      return 1f;
    float max = 0f;
    if (cb instanceof Turret t && t.drawer instanceof DrawTurret dt) {
      // 无头环境下贴图 region 可能是 null（没加载图集），一律判空
      max = Math.max(max, dt.base != null && dt.base.found() ? dt.base.width : 0f);
      max = Math.max(max, dt.preview != null && dt.preview.found() ? dt.preview.width : 0f);
      max = Math.max(max, dt.top != null && dt.top.found() ? dt.top.width : 0f);
    }
    if (cb.region != null && cb.region.found())
      max = Math.max(max, cb.region.width);
    if (cb.fullIcon != null && cb.fullIcon.found())
      max = Math.max(max, cb.fullIcon.width);
    // 贴图按"4 像素 = 1 世界单位"画（一格 = tilesize 世界单位 = tilesize*4 像素）
    float oneTilePx = tilesize * 4f;
    if (max <= oneTilePx)
      return 1f;
    return oneTilePx / max;
  }

  @Override
  public void drawPlanRegion(BuildPlan plan, Eachable<BuildPlan> list) {
    if (proceduralPlate)
      drawPlate(plan.drawx(), plan.drawy(), side); // 没贴图的边长：底板自己画
    else
      drawDefaultPlanRegion(plan, list); // 底板 + 队伍色 + config 预览
    if (plan.block != this)
      return;
    // 计划里的 config 可能是紧凑串（~base64，见 encodeConfig），先解成逐格文本再画虚影。
    String[] tokens = cellTokens(decodeConfig(plan.config instanceof String s ? s : null)[0]);
    for (int i = 0; i < tokens.length && i < side * side; i++) {
      Block b = blockOfToken(tokens[i]);
      if (b == null)
        continue;
      int cx = cellTile(plan.x, side, i % side);
      int cy = cellTile(plan.y, side, i / side);
      drawCellGhost(b, cx * tilesize, cy * tilesize, rotOfToken(tokens[i]), cellScale(b));
    }
  }

  /**
   * 虚影（放置预览）里用的"假格子炮台"缓存：key = 方块名。
   *
   * <p>用户要求"合体炮台显示的时候要把 drawer 的部件画上"：以前虚影里是照着
   * {@link DrawTurret} 硬编码画 base/preview/top 三张图，于是炮台的 {@code parts}
   * （炮管、护板、发光件…）和"drawer 不是 DrawTurret"的炮台（模组/组合方块）全都画不出来，
   * 虚影跟真摆下去的样子对不上。
   *
   * <p>现在虚影走**和落地格子完全同一条路**：造一个挂假 Tile 的真 build，调它自己的
   * {@code draw()}（= 方块的 drawer 画什么就画什么）。虚影每帧都要重画，所以假 build
   * 按方块名缓存，不能每帧 new。
   */
  static final arc.struct.ObjectMap<String, Turret.TurretBuild> ghostCells = new arc.struct.ObjectMap<>();
  /** 造不出来的（会抛异常的）方块，别再每帧重试。 */
  static final ObjectSet<String> ghostCellBroken = new ObjectSet<>();

  static mindustry.game.Team ghostTeam() {
    try {
      if (player != null && player.team() != null)
        return player.team();
    } catch (Throwable ignored) {
    }
    return mindustry.game.Team.sharded;
  }

  /** 让假 Tile 的 block() 也是这一格的炮台方块（Tile.block 是 protected，反射设一次）。 */
  static void setTileBlock(Tile fake, Block b) {
    try {
      java.lang.reflect.Field f = Tile.class.getDeclaredField("block");
      f.setAccessible(true);
      f.set(fake, b);
    } catch (Throwable ignored) {
    }
  }

  /** 虚影用的一格炮台 build（缓存；画之前自己改坐标/朝向）。 */
  public static @Nullable Turret.TurretBuild ghostCell(Block b) {
    if (!(b instanceof Turret) || b.name == null || ghostCellBroken.contains(b.name))
      return null;
    Turret.TurretBuild c = ghostCells.get(b.name);
    if (c != null && c.block == b)
      return c;
    try {
      Building raw = b.newBuilding();
      if (!(raw instanceof Turret.TurretBuild nc)) {
        ghostCellBroken.add(b.name);
        return null;
      }
      Tile fake = new Tile(0, 0);
      setTileBlock(fake, b);
      nc.create(b, ghostTeam());
      nc.tile = fake;
      nc.set(0f, 0f);
      nc.proximity = new Seq<>();
      fake.build = nc; // isValid() 靠这一条（它不在世界网格里）
      nc.rotation = 90f;
      nc.health = nc.maxHealth;
      nc.enabled = true;
      nc.checkAllowUpdate();
      nc.created();
      ghostCells.put(b.name, nc);
      return nc;
    } catch (Throwable t) {
      ghostCellBroken.add(b.name);
      Log.err("[combine] 超级组合炮台：虚影格子（@）创建失败，退回整块贴图", b.name, t);
      return null;
    }
  }

  /**
   * 程序化底板：边长没有现成贴图（7x7 以上）时用，深色底 + 每格网格线 + 外框。
   *
   * <p>用户问"为什么限制在 6x6" —— 因为 assets 里只有 super-turret-2..6 五张底板贴图。
   * 放开上限后这些边长改成自己画，任意边长都能用。
   */
  public static void drawPlate(float cx, float cy, int side) {
    float s = side * tilesize;
    float x = cx - s / 2f, y = cy - s / 2f;
    Draw.color(0f, 0f, 0f, 0.5f);
    Fill.crect(x, y, s, s);
    Draw.color(Pal.gray);
    for (int i = 1; i < side; i++) {
      float off = i * tilesize;
      Fill.crect(x, y + off, s, 1f);
      Fill.crect(x + off, y, 1f, s);
    }
    Draw.color(Pal.accent, 0.35f);
    Fill.crect(x, y, s, 1.5f);
    Fill.crect(x, y + s - 1.5f, s, 1.5f);
    Fill.crect(x, y, 1.5f, s);
    Fill.crect(x + s - 1.5f, y, 1.5f, s);
    Draw.reset();
  }

  /**
   * 画一格的炮台虚影。
   *
   * <p>【用户要求】"预设（建造计划虚影）应该画 fullicon"：虚影是静态预览，直接画方块
   * {@code fullIcon} —— 原版给每个方块生成好的"整套"图标（底板 + 炮管 + 部件 + 描边），
   * 不用赌 drawer 在这套内容/这个平台上能不能把部件画全。
   * fullIcon 缺失时才退回真 drawer（真 build → 方块自己的绘制），再不行退回整块 region。
   */
  public static void drawCellGhost(Block b, float cx, float cy, float rot, float scale) {
    if (b == null)
      return;
    float ox = Draw.xscl, oy = Draw.yscl;
    arc.graphics.Color oc = Draw.getColor();
    float cr = oc.r, cg = oc.g, cb = oc.b, ca = oc.a;
    if (scale != 1f)
      Draw.scl(scale);
    try {
      TextureRegion icon = firstFound(b.fullIcon, b.uiIcon, b.region,
          Core.atlas == null ? null : Core.atlas.find("error"));
      if (icon != null) {
        // fullIcon 是按"朝上"（rotation = 90）画的，和炮塔武器同一个口径
        Draw.rect(icon, cx, cy, rot - 90f);
      } else {
        Turret.TurretBuild c = ghostCell(b);
        if (c != null) {
          c.set(cx, cy);
          c.rotation = rot;
          c.recoilOffset.setZero();
          c.draw(); // = 方块的 drawer（DrawTurret.parts、自定义 drawer 都在里面）
        } else {
          TextureRegion fallback = b.region != null && b.region.found() ? b.region
              : Core.atlas.find("error");
          Draw.rect(fallback, cx, cy, rot - 90f);
        }
      }
    } catch (Throwable ignored) {
      // 虚影画不出来绝不能把渲染带崩（下一个方块照常画）
    } finally {
      Draw.scl(ox, oy);
      Draw.color(cr, cg, cb, ca);
    }
  }

  /**
   * 这个方块的 drawer 是不是"少了图"（底板/预览/顶部/描边一张都没加载上）。
   *
   * <p>用户报"合体后的 duo 明显没画全、drawer 没了"：格子里的炮台只剩 {@code block.region}
   * 那半张图，底板、部件、描边全不见 —— 这是 drawer 里的图没加载上（组合方块替换出来的副本
   * 在有些环境下就是这样）。这种格子直接用 {@code fullIcon} 顶上，绝不画半台。
   */
  public static boolean cellDrawerIncomplete(Block b) {
    if (!(b instanceof Turret t) || !(t.drawer instanceof DrawTurret dt))
      return true;
    try {
      // 一张图都没加载上：整个抽屉是空的（没 load 过的抽屉连 region 都是 null）
      if (!found(dt.base) && !found(dt.preview) && !found(dt.top) && !found(dt.outline))
        return true;
      // 【用户报"drawer 还是没画起来"】底板/炮管画得出、但"部件"的图没加载上时，
      // 格子里会少掉炮管/护板/描边（正好是 duo 那种"零件全在 parts 里"的炮台）。
      // 这种也算不完整 —— 整台退回 fullIcon，宁可静态也别画半台。
      if (partRegionsMissing(dt.parts))
        return true;
      for (var arr : dt.ammoParts.values())
        if (partRegionsMissing(arr))
          return true;
      return false;
    } catch (Throwable e) {
      return true; // 判据本身出错 → 当不完整处理，保证不会画半台
    }
  }

  static boolean found(TextureRegion r) {
    return r != null && r.found();
  }

  /** 第一个真正加载出来的贴图（顺序：fullIcon → uiIcon → region → error）。 */
  public static @Nullable TextureRegion firstFound(TextureRegion... regions) {
    if (regions != null)
      for (TextureRegion r : regions)
        if (found(r))
          return r;
    return null;
  }

  /**
   * 画一格炮台的"整套图标"（摆在格子中心，口径和武器一致：朝上 = rot-90）。
   *
   * <p>挑选贴图时一律验证 {@code found()}，绝不在没加载出来的图上画 —— 之前只判 null，
   * 遇到"非 null 但没加载"的副本贴图时结果是整格空的（用户报的"其他还是没画"）。
   */
  public static void drawCellIcon(Block b, float cx, float cy, float rot) {
    TextureRegion icon = b == null ? null
        : firstFound(b.fullIcon, b.uiIcon, b.region,
            Core.atlas == null ? null : Core.atlas.find("error"));
    if (icon == null)
      return;
    Draw.rect(icon, cx, cy, rot - 90f);
  }

  /** 这组部件里有没有"声明了部件、但一张图都没加载上"的。 */
  static boolean partRegionsMissing(mindustry.entities.part.DrawPart[] parts) {
    if (parts == null)
      return false;
    return partRegionsMissing(new arc.struct.Seq<>(parts));
  }

  /** 这组部件里有没有"声明了部件、但一张图都没加载上"的。 */
  static boolean partRegionsMissing(arc.struct.Seq<mindustry.entities.part.DrawPart> parts) {
    if (parts == null)
      return false;
    for (mindustry.entities.part.DrawPart p : parts) {
      if (!(p instanceof mindustry.entities.part.RegionPart rp))
        continue;
      boolean any = false;
      if (rp.regions != null)
        for (TextureRegion r : rp.regions)
          if (r != null && r.found())
            any = true;
      if (!any)
        return true; // 有部件却没有图 = 这个抽屉画不出它
    }
    return false;
  }

  /** 这个方块是不是"模组替换出来的组合方块副本"（抽屉是深拷贝的，部分环境下画不全）。 */
  public static boolean isComboCopy(Block b) {
    try {
      return b != null && combine.BlockCloner.comboToOriginal.containsKey(b);
    } catch (Throwable t) {
      return false;
    }
  }

  /**
   * 照原版 {@link DrawTurret#draw} 复刻一格的绘制，但跳过通用底板（{@code base}）和阴影。
   *
   * <p>为什么不直接调 {@code build.draw()}：
   * <ul>
   *   <li>{@code DrawTurret.draw} 第一行就画 {@code base}（{@code block-N} 通用小板），
   *       超级炮台自己已有整台大底板，每格再垫一张就是用户报的"多出来的底座"；</li>
   *   <li>"描边 + 全部 parts" 在原版是同一段连续代码，任何一个 part 抛异常会整段全丢
   *       （"duo 炮管和所有炮台的轮廓线一起消失"与该症状吻合）——这里逐件 try 隔离，
   *       一件坏了只丢一件；</li>
   *   <li>所有绘制都在调用方的当前 z 层完成，不再跳去 {@code turretLayer(50)}，
   *       避开部分客户端批处理按层提交时对中途改层的丢弃问题。</li>
   * </ul>
   *
   * <p>调用前需已通过 {@link #cellDrawerIncomplete} 检查（图都加载齐了才走这条路），
   * 且调用方已按 {@link #cellScale} 设好 {@code Draw.scl}。
   */
  /**
   * 部件位置随格子缩放：调用方用 {@link Draw#scl} 只缩了贴图尺寸，而 RegionPart 的
   * 偏移（x/y/moveX/moveY/originX/originY，世界单位）是在 part.draw() 里直接加到
   * 坐标上的，不吃 Draw.scl —— 2×2 升华塞进 1×1 格子（k=0.5）时 parts 全飘在格外，
   * 就是截图里散落的粉色碎片（用户判断"位置没缩放完全"属实）。
   * 做法：画部件前把偏移乘 k、画完除回去（共享的部件定义不能永久改，普通升华还要用）。
   * 角度（moveRot/rot）不缩，旋转跟尺寸无关。
   */
  static void mulPartOffsets(mindustry.entities.part.DrawPart part, float k) {
    if (part instanceof mindustry.entities.part.RegionPart rp) {
      rp.x *= k;
      rp.y *= k;
      rp.moveX *= k;
      rp.moveY *= k;
      rp.originX *= k;
      rp.originY *= k;
      if (rp.children != null)
        for (mindustry.entities.part.DrawPart ch : rp.children)
          mulPartOffsets(ch, k);
    }
  }

  static void drawCellDrawer(Block cb, Turret.TurretBuild c) {
    Turret t = (Turret) cb;
    if (!(t.drawer instanceof DrawTurret dt))
      return;
    float x = c.x + c.recoilOffset.x, y = c.y + c.recoilOffset.y;
    float rot = c.drawrot();
    // 【z 层照原版 DrawTurret】原版在 turretLayer(50) 画本体/部件、49.99 画描边、
    // heatLayer(50.1) 画热量 —— 之前在调用方的 z(30,方块层)画，炮管被底板/别的方块
    // 盖住，看起来就像贴了张静态图标（用户报的"drawer 画错了"）。
    float zPrev = Draw.z();

    // 0) 底座和投影**故意不画**：底板由超级炮台自己的大板负责，每格再垫一张
    // block-N 小底板就是早年用户报的"多出来的底座"（第 10 轮曾照原版补回，被用户
    // 抓包"升华合体 drawer 绘制有问题"，这里恢复跳过）。
    // 1) 本体（照原版 drawTurret：region / 液体 / top，全部带后坐偏移和朝向）
    Draw.z(dt.turretLayer);
    if (cb.region != null && cb.region.found())
      Draw.rect(cb.region, x, y, rot);
    if (dt.liquid != null && dt.liquid.found()) {
      Liquid toDraw = dt.liquidDraw == null ? c.liquids.current() : dt.liquidDraw;
      if (toDraw != null)
        Drawf.liquid(dt.liquid, x, y, c.liquids.get(toDraw) / Math.max(t.liquidCapacity, 1f),
            toDraw.color.write(arc.util.Tmp.c1).a(1f), rot);
    }
    if (dt.top != null && dt.top.found())
      Draw.rect(dt.top, x, y, rot);

    // 2) 热量发光（照原版 drawHeat：heatLayer）
    if (c.heat > 1e-5f && dt.heat != null && dt.heat.found())
      Drawf.additive(dt.heat, t.heatColor.write(arc.util.Tmp.c1).a(c.heat), x, y, rot, dt.heatLayer);

    // 3) 描边 + parts（原版同一段；逐件隔离，一件坏了不拖垮整格）
    // 部件偏移随 k 缩放（见 mulPartOffsets；缩 0.5 的大炮台必须缩偏移否则零件飞出格外）
    float pk = cellScale(cb);
    if (pk != 1f)
      for (mindustry.entities.part.DrawPart part : dt.parts)
        mulPartOffsets(part, pk);
    try {
    if (dt.parts.size > 0) {
      if (dt.outline != null && dt.outline.found()) {
        Draw.z(dt.turretLayer - 0.01f);
        Draw.rect(dt.outline, x, y, rot);
        Draw.z(dt.turretLayer);
      }
      float progress = c.progress();
      mindustry.entities.part.DrawPart.PartParams params = mindustry.entities.part.DrawPart.params
          .set(c.warmup(), 1f - progress, 1f - progress, c.heat, c.curRecoil, c.charge, x, y, c.rotation);
      for (mindustry.entities.part.DrawPart part : dt.parts) {
        try {
          params.setRecoil(part.recoilIndex >= 0 && c.curRecoils != null ? c.curRecoils[part.recoilIndex] : c.curRecoil);
          part.draw(params);
        } catch (Throwable e) {
          // 这一个部件画不出来就算了，别像原版那样把整段（描边+所有部件）全带丢
          if (partDrawErrWarned.add(cb.name + "|" + part.getClass().getSimpleName()))
            Log.err("[combine] 超级组合炮台：@ 的部件 @ 绘制失败（已跳过该部件）", cb.name,
                part.getClass().getSimpleName(), e);
        }
      }
    }
    if (dt.ammoParts.size > 0 && c.getAmmoContent() != null) {
      mindustry.entities.part.DrawPart[] parts = dt.ammoParts.get(c.getAmmoContent());
      if (parts != null) {
        if (pk != 1f)
          for (mindustry.entities.part.DrawPart part : parts)
            mulPartOffsets(part, pk);
        float progress = c.progress();
        // 注意：ammoParts 的"除回去"统一放 finally 里，别在这里再除一遍
        mindustry.entities.part.DrawPart.PartParams params = mindustry.entities.part.DrawPart.params
            .set(c.warmup(), 1f - progress, 1f - progress, c.heat, c.curRecoil, c.charge, x, y, c.rotation);
        for (mindustry.entities.part.DrawPart part : parts) {
          try {
            params.setRecoil(part.recoilIndex >= 0 && c.curRecoils != null ? c.curRecoils[part.recoilIndex] : c.curRecoil);
            part.draw(params);
          } catch (Throwable ignored) {
          }
        }
      }
    }
    } finally {
      // 把部件偏移除回去，别让共享的 drawer 定义带着缩放过的小偏移
      if (pk != 1f) {
        for (mindustry.entities.part.DrawPart part : dt.parts)
          mulPartOffsets(part, 1f / pk);
        mindustry.entities.part.DrawPart[] ap = c.getAmmoContent() == null ? null
            : dt.ammoParts.get(c.getAmmoContent());
        if (ap != null)
          for (mindustry.entities.part.DrawPart part : ap)
            mulPartOffsets(part, 1f / pk);
      }
    }
    Draw.z(zPrev);
  }

  @Override
  public void drawPlace(int x, int y, int rotation, boolean valid) {
    super.drawPlace(x, y, rotation, valid);
    // 直接放置（没带布局）时至少把范围画出来
    Draw.color(valid ? Pal.accent : Pal.remove);
    arc.graphics.g2d.Lines.stroke(2f);
    arc.graphics.g2d.Lines.rect(
        x * tilesize + offset - side * tilesize / 2f + 1f,
        y * tilesize + offset - side * tilesize / 2f + 1f,
        side * tilesize - 2f, side * tilesize - 2f);
    Draw.reset();
  }

  @Override
  public void load() {
    super.load();
    // 模组自带贴图在 atlas 里的名字带模组前缀（assets/sprites/blocks/xxx.png → combine-xxx）
    TextureRegion custom = Core.atlas.find("combine-" + name);
    if (custom.found()) {
      region = custom;
      fullIcon = custom;
      uiIcon = custom;
      proceduralPlate = false;
    } else {
      // 没有这个边长的贴图（7x7 以上）→ 底板程序化绘制，别留 error 图
      proceduralPlate = true;
      region = Core.atlas.find("error");
      fullIcon = region;
      uiIcon = region;
    }
  }

  /**
   * 信息面板上的"物品 / 液体"条。
   *
   * <p>【用户要求】"物品容量、液体容量也是累加"：原版这两条的分母取自 block 上的静态值
   * （物品 = 边长²×30 的估算、液体 = 9999 那个"怕管道算出负流量"的假容量），对超级炮台都不对。
   * 这里把两条换掉，分母改用这台建筑按实际格子算出来的真实容量，条上也直接写 "当前 / 上限"。
   */
  @Override
  public void setBars() {
    super.setBars();
    try {
      // 【用户要求】"既然有悬浮面板了，就把 display() 里的物品、液体、电力删掉，只显示组合体构成"：
      // 物品/液体/电力条（原版注册的 power 也一并去掉）不在这里画了 —— 悬浮面板里有池子和弹仓。
      removeBar("items");
      removeBar("liquid");
      removeBar("power");
    } catch (Throwable t) {
      Log.err("[combine] 超级组合炮台精简信息面板失败（沿用原版默认条）", t);
    }
    // 【热量条】原版 Turret 的 heat 条读 entity.heatReq —— 那个字段只由原版
    // TurretBuild.updateTile 每帧回写；SuperTurretBuild 不走那条更新链，heatReq
    // 恒 0，条永远显示 0%（用户抓包"没生效"：热其实早通了，是条在撒谎）。
    // 换成读我们自己的 comboTotalHeat（= vanilla 相邻 + 组合池 + 探针，三方合并值）。
    try {
      removeBar("heat");
      addBar("heat", e -> {
        SuperTurretBuild b = (SuperTurretBuild) e;
        // 分母 = 里面每一台需热炮台需求之和（合体后 N 台就要 N 份），单台时退回方块上的标记值。
        return new mindustry.ui.Bar(
            () -> {
              float req = Math.max(1f, b.heatDemand() > 0f ? b.heatDemand()
                  : ((SuperTurret) b.block).heatRequirement);
              return Core.bundle.format("bar.heatpercent",
                  (int) b.comboTotalHeat,
                  (int) (Math.min(b.comboTotalHeat / req, 1f) * 100f));
            },
            () -> Pal.lightOrange,
            () -> {
              float req = Math.max(1f, b.heatDemand() > 0f ? b.heatDemand()
                  : ((SuperTurret) b.block).heatRequirement);
              return Mathf.clamp(b.comboTotalHeat / req);
            });
      });
    } catch (Throwable t) {
      Log.err("[combine] 超级组合炮台热量条装配失败（沿用原版默认条）", t);
    }
  }

  @Override
  public void loadIcon() {
    super.loadIcon();
    TextureRegion custom = Core.atlas.find("combine-" + name);
    if (custom.found()) {
      fullIcon = custom;
      uiIcon = custom;
    }
  }

  // ==================== 电网：把各格用电量加总上报 ====================

  public static class SuperConsumePower extends ConsumePower {
    public SuperConsumePower() {
      super(0f, 0f, false);
    }

    @Override
    public float requestedPower(Building entity) {
      return entity instanceof SuperTurretBuild b ? b.cellPowerUse() : 0f;
    }

    @Override
    public float efficiency(Building build) {
      return build == null || build.power == null ? 1f : build.power.status;
    }
  }

  // ==================== 建筑本体 ====================

  public class SuperTurretBuild extends Building implements IComboGrouped, ControlBlock {
    // ---- 布局与格子 ----
    /** 布局串（';' 分隔的 "方块名@朝向"，空格子是空串）。 */
    public String layout = "";
    /** 被框选进来的炮台坐标（"x,y;x,y"）—— 超级炮台放下后要把它们拆掉。 */
    public String sources = "";
    public boolean sourcesConsumed = false;
    public Turret.TurretBuild[] cells = new Turret.TurretBuild[0];
    public Block[] cellBlocks = new Block[0];
    public float[] cellRot = new float[0];
    /** 每格的热量探针（把整台的热量喂给这一格，见 {@link HeatProbe}）。 */
    public HeatProbe.HeatProbeBuild[] probes = new HeatProbe.HeatProbeBuild[0];
    /** 整台当前拿到的热量（各格共用这一份）。 */
    public float[] heatSide = new float[4];
    public float comboTotalHeat = 0f;
    /** 每格**选定**的弹药（null = 自动：池里有什么就打什么，和组合炮台一样可点选）。 */
    public Item[] cellAmmo = new Item[0];
    public boolean cellsReady = false;
    /** 放置后的第一次 buildCells 强制满血（原版 constructFinish 会用方块静态血量覆盖血量）。 */
    public boolean placedFullHealth = false;
    /** updateTile 里的一次性满血初始化（等 constructFinish 覆盖完 health 之后再补刀）。 */
    public boolean healthFixPending = true;
    /** 这台建筑真正的物品/液体容量（按实际格算，比 block.* 的静态估值准）。 */
    public int realItemCap = 1;
    public float realLiquidCap = 1f;
    /** 建格失败（第三方炮台方块不认这套）的格子，只报一次日志。 */
    public ObjectSet<Integer> cellBroken = new ObjectSet<>();
    // ---- 组合网络约定字段（ComboReflect 按名字反射找） ----
    public SuperTurretBuild comboLeader;
    public Seq<Building> comboGroup = new Seq<>();
    public boolean comboDirty = true;
    public int comboTotalItemCap;
    public float comboTotalLiquidCap;

    // ==================== 分组（组合网络） ====================

    @Override
    public SuperTurretBuild leader() {
      if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null))
        comboLeader = null;
      return comboLeader == null ? this : comboLeader;
    }

    public boolean isLeader() {
      return leader() == this;
    }

    @Override
    public Seq<Building> group() {
      SuperTurretBuild l = leader();
      if (l.comboGroup == null)
        l.comboGroup = new Seq<>();
      return l.comboGroup;
    }

    @Override
    public void markGroupDirty() {
      comboDirty = true;
    }

    /** 邻接 + 穿过组合连接器/节点可达的同队超级炮台算一组。 */
    public void rebuildCombo() {
      Seq<Building> members = new Seq<>();
      members.add(this);
      try {
        for (Building b : ComboReflect.linkedReachable(this,
            o -> o instanceof SuperTurretBuild st && st.team == team && st.isValid(),
            (cur, o) -> true)) {
          if (b != this && b.isValid())
            members.addUnique(b);
        }
      } catch (Throwable t) {
        Log.err("[combine] 超级炮台分组失败（只算自己）", t);
      }

      SuperTurretBuild ldr = this;
      for (Building b : members)
        if (b.isValid() && b.pos() < ldr.pos())
          ldr = (SuperTurretBuild) b;

      int itemCap = 0;
      float liquidCap = 0f;
      for (Building b : members) {
        if (!b.isValid())
          continue;
        itemCap += ComboReflect.baseItemCap(b);
        liquidCap += ComboReflect.baseLiquidCap(b);
      }

      for (Building b : members) {
        SuperTurretBuild s = (SuperTurretBuild) b;
        s.comboGroup = members;
        s.comboLeader = s == ldr ? null : ldr;
        s.comboTotalItemCap = Math.max(itemCap, 1);
        s.comboTotalLiquidCap = Math.max(liquidCap, 1f);
        s.comboDirty = false;
      }
      ldr.comboLeader = null;

      if (members.size > 1)
        sharePools(ldr, members);
    }

    /**
     * 本地组合体并池（相邻的超级炮台共用一份物品/液体模块）。
     *
     * <p>跨连接器/节点的网络并池由 ComboNet 负责（见 ComboNet.mergeComponent），
     * 这里只管"挨着"的那几台 —— ComboNet 的重建由方块变化事件触发，不必依赖它。
     * 读档窗口下各成员手里是同一份池子的副本，此时只认领一份、不相加（否则库存翻倍）。
     */
    public void sharePools(SuperTurretBuild ldr, Seq<Building> members) {
      boolean dedupe = ComboNet.pendingLoadDedupe();

      // ---- 物品 ----
      ItemModule itemPool = ldr.items;
      if (itemPool == null)
        for (Building m : members)
          if (m.items != null) {
            itemPool = m.items;
            break;
          }
      if (itemPool != null) {
        ObjectSet<ItemModule> outside = ComboReflect.itemPoolsSharedOutside(members);
        if (!outside.isEmpty())
          itemPool = outside.first();
        ObjectSet<ItemModule> seen = new ObjectSet<>();
        seen.add(itemPool);
        for (Building m : members) {
          if (m.items == null || !seen.add(m.items))
            continue;
          if (!dedupe && !outside.contains(m.items))
            moveItems(m.items, itemPool);
        }
        for (Building m : members)
          m.items = itemPool;
      }

      // ---- 液体 ----
      LiquidModule liquidPool = ldr.liquids;
      if (liquidPool == null)
        for (Building m : members)
          if (m.liquids != null) {
            liquidPool = m.liquids;
            break;
          }
      if (liquidPool != null) {
        ObjectSet<LiquidModule> outside = ComboReflect.liquidPoolsSharedOutside(members);
        if (!outside.isEmpty())
          liquidPool = outside.first();
        ObjectSet<LiquidModule> seen = new ObjectSet<>();
        seen.add(liquidPool);
        for (Building m : members) {
          if (m.liquids == null || !seen.add(m.liquids))
            continue;
          if (!dedupe && !outside.contains(m.liquids))
            moveLiquids(m.liquids, liquidPool);
        }
        for (Building m : members)
          m.liquids = liquidPool;
      }
    }

    static void moveItems(ItemModule from, ItemModule to) {
      if (from == null || to == null || from == to)
        return;
      for (Item item : content.items()) {
        int amt = from.get(item);
        if (amt > 0) {
          to.add(item, amt);
          from.remove(item, amt);
        }
      }
      to.stopFlow();
    }

    static void moveLiquids(LiquidModule from, LiquidModule to) {
      if (from == null || to == null || from == to)
        return;
      for (Liquid liquid : content.liquids()) {
        float amt = from.get(liquid);
        if (amt > 0.001f) {
          to.add(liquid, amt);
          from.remove(liquid, amt);
        }
      }
      to.stopFlow();
    }

    // ==================== 生命周期 ====================

    @Override
    public void created() {
      super.created();
      cellsReady = false;
      placedFullHealth = true;
      healthFixPending = true;
      comboDirty = true;
      comboTotalItemCap = block.itemCapacity;
      comboTotalLiquidCap = baseLiquidCapacity;
      ensureCells();
    }

    @Override
    public void onProximityUpdate() {
      super.onProximityUpdate();
      comboDirty = true;
    }

    @Override
    public void onRemoved() {
      disposeCells();
      comboLeader = null;
      comboGroup = new Seq<>();
      super.onRemoved();
    }

    @Override
    public void onDestroyed() {
      for (Turret.TurretBuild c : cells)
        if (c != null)
          try {
            c.onDestroyed();
          } catch (Throwable ignored) {
          }
      super.onDestroyed();
    }

    @Override
    public boolean shouldConsume() {
      return true; // 电网/物品都按整台算
    }

    // ==================== 手动操控（玩家接管后自己瞄准开火） ====================

    /**
     * 玩家"操控"这台超级炮台时借用的那一格：第一格装好的炮台。
     *
     * <p>原版炮台是靠 {@code ControlBlock} 的代理单位被玩家接管的（桌面：按住"控制"键点它；
     * 手机：点它）—— 那套逻辑在 {@code TurretBuild.updateTile()} 里（接管后照着玩家的准星打）。
     * 超级炮台的格子是"离网"的假建筑，别人点不到，所以本体现在也实现 {@code ControlBlock}，
     * 直接把代理单位借给某一格：玩家接管这一台 = 接管那一格，那一格就按玩家准星开火。
     */
    public @Nullable Turret.TurretBuild controlCell() {
      ensureCells();
      Turret.TurretBuild fallback = null;
      for (Turret.TurretBuild c : cells) {
        if (c == null || c.block == null)
          continue;
        // 已经接管的那一格就一直用它：否则弹药打空/换弹时"控制对象"会跳到别格，
        // 玩家体验就是"操控着突然被踢出去"。
        if (c.controlled())
          return c;
        if (fallback == null)
          fallback = c;
        // 优先挑现在**真能开火**的那一格（里面的炮台不一定都吃同一种弹药，
        // 挑到没弹的那台会出现"接管了却打不出子弹"）
        boolean ammo = false;
        try {
          ammo = c.hasAmmo();
        } catch (Throwable ignored) {
        }
        if (ammo)
          return c;
      }
      return fallback;
    }

    /** 整池液体总量（信息面板/液体条上显示"当前 / 上限"用）。 */
    public float totalLiquid() {
      if (liquids == null)
        return 0f;
      float sum = 0f;
      for (Liquid l : content.liquids())
        sum += liquids.get(l);
      return sum;
    }

  /** 里面装了些什么（悬浮面板上显示"duo x4 scatter x3 …"）。 */
  public String innerSummary() {
      ensureCells();
      arc.struct.ObjectIntMap<Block> counts = new arc.struct.ObjectIntMap<>();
      for (Block b : cellBlocks)
        if (b != null)
          counts.increment(b, 1);
      StringBuilder sb = new StringBuilder();
      for (Block b : counts.keys()) {
        if (sb.length() > 0)
          sb.append("  ");
        sb.append(b.localizedName == null ? b.name : b.localizedName).append(" x").append(counts.get(b, 0));
      }
      return sb.toString();
    }

    /**
     * 信息面板：{@code super.display} 之后补一行"构成"（= 里面每一格是什么炮台、各几台）。
     *
     * <p>【用户要求】"既然有悬浮面板了就把 display() 里的物品、液体、电力删了，只显示组合体构成，
     * 而且会换行" —— 物品/液体/电力条已在 {@code setBars()} 里摘掉，这里只补构成那一行，
     * 按面板宽度换行（构成可能很长）。
     */
    @Override
    public void display(arc.scene.ui.layout.Table table) {
      super.display(table);
      try {
        String inner = innerSummary();
        if (inner.isEmpty())
          inner = "（空）";
        table.row();
        table.add("[lightgray]构成: " + inner + "[]").left()
            .width(combine.util.ComboUi.COMPOSITION_WIDTH).wrap();
      } catch (Throwable ignored) {
      }
    }

    /** 第 i 格炮台的热量需求（heatRequirement>0 才吃热；空格/不需要热的格子 = 0）。 */
    public float cellHeatReq(int i) {
      Turret.TurretBuild c = i >= 0 && i < cells.length ? cells[i] : null;
      return c != null && c.block instanceof Turret t ? Math.max(0f, t.heatRequirement) : 0f;
    }

    /**
     * 整台的"需热合计" = 里面每一台需热炮台的 heatRequirement 之和。
     *
     * <p>【用户报】"需要热量的炮台合体后需要的热量只有一个炮台的量"。以前这里拿方块上写死的
     * 单个炮台的值（{@link SuperTurret#heatRequirement}，只用来标记"这台吃热"）当整台需求，
     * 于是 4 台 afflict 合体后，只要有 1 台的热量就能让 4 台一起满速 —— 显然不对。
     * 现在按里面**实际**的需热格子累加，N 台合体就要 N 份热。
     */
    public float heatDemand() {
      float sum = 0f;
      for (int i = 0; i < cells.length; i++)
        sum += cellHeatReq(i);
      return sum;
    }

    /** 里面吃热的格子数（heatRequirement>0），悬浮面板/诊断用。 */
    public int heatCellCount() {
      int n = 0;
      for (int i = 0; i < cells.length; i++)
        if (cellHeatReq(i) > 0f)
          n++;
      return n;
    }

    /**
     * 第 i 格从整台热量池里分到的热量。
     *
     * <p>整台一个池子、按各格需求比例分摊：需热格子拿 {@code pool * req_i / demand}，
     * 于是**每格效率都等于 {@code pool / demand}**（池子够整台需求时全部满速）。
     * 不需要热的格子（req=0）照旧拿整池（它们根本不看热量，只是保持原样）。
     */
    public float heatShare(int i, float pool, float demand) {
      float req = cellHeatReq(i);
      if (req <= 0f || demand <= 0f)
        return pool;
      return pool * req / demand;
    }

    @Override
    public @Nullable Unit unit() {
      Turret.TurretBuild c = controlCell();
      return c == null ? null : c.unit();
    }

    @Override
    public boolean isControlled() {
      Turret.TurretBuild c = controlCell();
      return c != null && c.controlled();
    }

    @Override
    public boolean canControl() {
      return controlCell() != null;
    }

    @Override
    public boolean shouldAutoTarget() {
      return true;
    }

    /**
     * 手动操控：把"玩家的准星 + 开火键"同步给每一格，整台的炮一起打。
     *
     * <p>用户要求："一个合体炮台里所有的炮在手动控制状态下都可以控制发射"。
     *
     * <p>做法：原版只有"玩家接管的那个单位"才会走 {@code controlled()} 分支，所以这里把
     * 其余格子的代理单位也挂到同一个玩家控制器上，并每帧把接管那格的 aim/开火状态抄过去 ——
     * 那些代理单位不在 {@code Groups.unit} 里（BlockUnit 不允许 add），所以不会干扰相机/面板。
     * 玩家松手（不再被操控）时把这些格子放回 AI。
     */
    public void syncCellControls() {
      if (cells.length == 0)
        return;
      Turret.TurretBuild owner = possessedCell();
      Unit src = owner == null ? null : owner.unit();
      // 操控源 = 被接管那一格的代理单位身上的玩家（无头测试里 Vars.player 可能是空的，
      // 所以认单位上的控制器，而不是 Vars.player）
      boolean live = src != null && src.getPlayer() != null;
      for (Turret.TurretBuild c : cells) {
        if (c == null || c == owner)
          continue;
        try {
          if (live) {
            // 走**原版逻辑控制**通道：照同一套准星/开火键打，既不用去抢玩家控制器
            // （单位控制器不能被两个地方同时用），也不会把这一格从 AI 手里弄乱。
            // 【不能走 c.control()】原版 control() 里对坐标做 World.unconv（逻辑坐标→
            // 世界坐标），而 aimX/aimY 本来就是世界坐标 —— 再 unconv 一次准星被缩到
            // 错误位置，其它格全部转向乱点（用户报的"合体里只能控制一台的转向"）。
            // 这里照 control() 里 LAccess.shoot 的语义直接写字段（它就做这三件事）。
            c.targetPos.set(src.aimX(), src.aimY());
            c.logicControlTime = 120f;
            c.logicShooting = src.isShooting();
            // 【转向也一起管】只靠逻辑控制通道时，转向速度用的是格子自己的 potentialEfficiency ——
            // 有的 mod 炮台自带用电/效率被卡住，就变成"只开火不转"（用户报的"只能控制一个的转向"）。
            // 手动操控时按**整台**的电力满足率直接转，整台没电才不转。
            if (c.block instanceof Turret ct) {
              float powerFrac = power == null ? 1f : Mathf.clamp(power.status);
              if (powerFrac > 0.01f && ct.rotateSpeed > 0f) {
                float ang = arc.math.Angles.angle(src.aimX() - c.x, src.aimY() - c.y);
                c.rotation = arc.math.Angles.moveToward(c.rotation, ang,
                    ct.rotateSpeed * powerFrac * arc.util.Time.delta);
              }
            }
          } else if (c.logicControlTime > 0f) {
            // 玩家松手了：结束逻辑控制，交回它自己的 AI
            c.logicControlTime = -1f;
            c.logicShooting = false;
          }
        } catch (Throwable ignored) {
          // 某一格同步失败不该影响其它格/整台
        }
      }
    }

    /** 玩家**真正**接管的那一格（它的代理单位就是 player.unit()）；没接管返回 null。 */
    public @Nullable Turret.TurretBuild possessedCell() {
      ensureCells();
      Unit cur = player == null ? null : player.unit();
      for (Turret.TurretBuild c : cells) {
        if (c == null || c.unit == null)
          continue;
        // 玩家正式接管的那一格
        if (cur != null && c.unit() == cur)
          return c;
        // 兜底（无头测试等 player 为空的场合）：谁被玩家控制器占着就当操控源
        if (cur == null && c.unit().getPlayer() != null)
          return c;
      }
      return null;
    }

    /**
     * 方块的 blockstatus（那个绿/红菱形）。
     *
     * <p>用户报："只有在连接电力后才变成 active，哪怕里面的炮台全是只需要物品的炮台" ——
     * 原版这条是拿整台自己的 electricity/efficiency 算的，纯物品炮台也被"没接电"卡住。
     * 改成按**里面每一格**的实际需求算：格子里有弹药（物品/冷却液/自身电力）就算有输入，
     * 只有真有格子吃电、而电网又没喂饱时才报 noInput。
     */
    @Override
    public BlockStatus status() {
      if (!enabled)
        return BlockStatus.logicDisable;
      ensureCells();
      boolean any = false, anyWorkable = false, needsPower = false;
      for (int i = 0; i < cells.length; i++) {
        Turret.TurretBuild c = cells[i];
        if (c == null)
          continue;
        any = true;
        Block cb = i < cellBlocks.length ? cellBlocks[i] : null;
        if (cb != null && cb.hasPower)
          needsPower = true;
        boolean ok;
        try {
          ok = c.hasAmmo();
        } catch (Throwable t) {
          ok = true;
        }
        if (ok)
          anyWorkable = true;
      }
      if (!any)
        return BlockStatus.active; // 空格子不算"没输入"：空壳也让它亮着
      if (!anyWorkable)
        return BlockStatus.noInput; // 里面一台都没有弹药/冷却液
      if (needsPower && (power == null || power.status < 0.999f))
        return BlockStatus.noInput; // 真有格子吃电，但电网没喂饱
      return BlockStatus.active;
    }

    // ==================== 主循环 ====================

    @Override
    public void updateTile() {
      if (!combine.util.ComboTeams.playerTeam(team)) { super.updateTile(); return; }   // 只玩家组合开关：AI 敌人的建筑按原版跑，不参与组合那套
      try {
        ensureCells();
      } catch (Throwable t) {
        if (cellBroken.add(-1))
          Log.err("[combine] ensureCells 每 tick 抛异常（updateTile 前半段被跳过，热/弹药全停）", t);
      }
      // 满血初始化必须在 constructFinish 覆盖 health 之后再做一次：
      // created()->ensureCells() 里立的 placedFullHealth 会被随后 vanilla
      // "health = block.health * healthf" 盖掉（上一版标志白立，用户抓包"血量没修好"）。
      // 读档时 health 由 read() 恢复、此补丁在 updateTile 之前完成，不会误伤。
      if (healthFixPending && cellsReady) {
        healthFixPending = false;
        if (placedFullHealth || health <= block.health + 0.5f || health >= maxHealth - 0.5f)
          health = maxHealth;
        else
          health = Math.min(health, maxHealth);
        placedFullHealth = false;
      }
      try {
        if (isLeader() && comboDirty)
          rebuildCombo();
      } catch (Throwable t) {
        if (cellBroken.add(-2))
          Log.err("[combine] rebuildCombo 每 tick 抛异常", t);
      }

      // 整台的热量（含贴着本体的组合节点/连接器送来的那一份），喂给每一格的探针 ->
      // 各格再按原版规则读到"整台的热量"
      float totalHeat = 0f;
      try {
        totalHeat = calculateHeat(heatSide);
      } catch (Throwable ignored) {
      }
      // 组合体内产热成员（矿渣制热机等）的池化热量：vanilla calculateHeat 只看得见
      // **贴着本体的**邻居，框选合成的组合体里离得远的制热机的热从来并不进来。
      // 注意必须走 ComboNet.componentMembers（整张网络/组合体的成员，和悬浮面板同源）——
      // 本类自己的 group() 只收 SuperTurretBuild，制热机（CombinedCrafterBuild）根本不在里面，
      // 上一版用 group() 求和恒等于 0，等于没修（用户抓包：面板 48、炮台仍 6）。
      // 取较大值而非相加：贴着的成员在两个路径里都会被数到，相加会双计。
      float poolHeat = 0f;
      float poolUnion = 0f; // 并集 heat() 求和（连接器口径）
      float poolGeo = 0f;   // 几何兜底扫到的 heat()
      try {
        // 成员源 = 三路并集（去重）：
        // 1) ComboNet.componentMembers —— 走组合节点/连接器的网络组合；
        // 2) CoopCombo.coopGroup —— 框选/协作组合（componentMembers 只沿连接件走，
        //    普通框选组合里它只返回炮台自己，面板点的若是制热机就走这条兜底）；
        // 3) proximity 相邻建筑 —— 什么都不组合时贴着本体的产热机也得算。
        // 只取其中一路都会漏（用户连抓三包：面板 48、炮台恒 6 的根源）。
        ObjectSet<Building> union = new ObjectSet<>();
        // 【几何播种】proximity 可能是空/旧的（模组直放方块不走原版邻近重建），
        // 节点不在 proximity 里 → 反向拿组无从起步（用户日志：源无 +src/+geo，
        // 并集恒 2 个成员、连节点都没有）。所以先按几何把本体 footprint 外扩 1 格
        //  ring 里的所有建筑播进并集，再走 net/coop/fixpoint。
        try {
          // 环半径按格子布局算：block.size 远小于 side×side 的视觉本体，
          // 按 block.size 罩不住贴着视觉本体的组合节点（用户日志：节点进不了并集）
          int rr = Math.max((block.size + 1) / 2 + 1, side / 2 + 2);
          for (int tx = tileX() - rr; tx <= tileX() + rr; tx++)
            for (int ty = tileY() - rr; ty <= tileY() + rr; ty++) {
              Tile t = world.tile(tx, ty);
              Building m = t == null ? null : t.build;
              if (m != null && m != this && m.isValid())
                union.add(m);
            }
        } catch (Throwable ignored) {
        }
        if (proximity != null)
          for (Building m : proximity)
            if (m != null && m != this && m.isValid())
              union.add(m);
        Seq<Building> net = ComboNet.componentMembers(this);
        if (net != null)
          for (Building m : net)
            if (m != null && m != this && m.isValid())
              union.add(m);
        try {
          for (Building m : combine.coop.CoopCombo.coopGroup(this))
            if (m != null && m != this && m.isValid())
              union.add(m);
        } catch (Throwable ignored) {
        }
        if (proximity != null)
          for (Building m : proximity)
            if (m != null && m != this && m.isValid())
              union.add(m);
        // 【不动点反向拿组】分组 BFS 是双向不对称的：制热机组的 BFS 会把贴着它的
        // 炮台收进去（面板从制热机那边能看到 x5），炮台的组却只收 SuperTurretBuild
        // （用户抓包：x5 明明 32 热，合体炮台 totalHeat=0、不发射）。
        // 所以对并集内每个成员反取 componentMembers/coopGroup，迭代到不再增长 ——
        // 无论从哪一侧出发，最终收敛到同一个闭包。
        try {
          Seq<Building> work = new Seq<>();
          for (int pass = 0; pass < 6; pass++) {
            work.clear();
            for (Building m : union)
              if (m != null && m.isValid())
                work.add(m);
            boolean grew = false;
            for (Building m : work) {
              try {
                Seq<Building> mg = ComboNet.componentMembers(m);
                if (mg != null)
                  for (Building g : mg)
                    if (g != null && g != this && g.isValid() && !union.contains(g)) {
                      union.add(g);
                      grew = true;
                    }
              } catch (Throwable ignored) {
              }
              try {
                for (Building g : combine.coop.CoopCombo.coopGroup(m))
                  if (g != null && g != this && g.isValid() && !union.contains(g)) {
                    union.add(g);
                    grew = true;
                  }
              } catch (Throwable ignored) {
              }
              // 【节点显式连线】组合节点只认 links 里显式连过的目标（用户实测：
              // 贴着节点能收热、隔距连线收不到）。节点进并集后把它的 links 目标
              // 也拉进来 —— 产热机/另一台节点/远处建筑都覆盖。
              try {
                if (m instanceof combine.net.ComboNode.ComboNodeBuild node && node.links != null)
                  for (int li = 0; li < node.links.size; li++) {
                    Building g = world.build(node.links.get(li));
                    if (g != null && g != this && g.isValid() && !union.contains(g)) {
                      union.add(g);
                      grew = true;
                    }
                  }
              } catch (Throwable ignored) {
              }
            }
            if (!grew)
              break;
          }
        } catch (Throwable ignored) {
        }
        float srcHeat = 0f; // 组产热池（产能口径 = 源头，不被节点扣热影响）
        for (Building m : union) {
          // 探针不算：它的 heat() 就是上一帧回写给炮台的总热，算进去会自我叠加放大
          if (m instanceof mindustry.world.blocks.heat.HeatBlock hb
              && !(m instanceof SuperTurret.HeatProbe.HeatProbeBuild))
            poolUnion += Math.max(0f, hb.heat());
          // 产热机读"产能"而不是 heat()：组合节点是扣热再分配制，heat() 可能已被
          // 扣成 0（用户抓包：节点不行、连接器可以——连接器不扣热所以读得到）。
          // getComboHeat() 是组的产热总量，扣热动的是存量、不影响产能读数。
          // 同组成员各报整组总值，取 max 不相加，避免 N 倍。
          try {
            if (m instanceof combine.production.CombinedGenerator.CombinedGeneratorBuild gb) {
              float ph = gb.getComboHeat();
              if (ph > srcHeat)
                srcHeat = ph;
            } else if (m instanceof combine.production.CombinedCrafter.CombinedCrafterBuild cb) {
              float ph = cb.getComboHeat();
              if (ph > srcHeat)
                srcHeat = ph;
            }
          } catch (Throwable ignored) {
          }
        }
        if (poolUnion > poolHeat)
          poolHeat = poolUnion;
        if (srcHeat > poolHeat) {
          poolHeat = srcHeat;
        }
        // 【几何兜底】日志实锤：有些布局里炮台和制热机之间在 net/coop/相邻图里
        // **一条边都没有**（用户"明明已经组合了"但所有图路径都断）。这时直接按
        // 几何位置扫：本体 footprint 外扩 1 格内的产热机全进池。框选成一体的组合
        // 不需要任何组关系也能拿到热。
        if (poolHeat < 0.001f) {
          try {
            int r = Math.max((block.size + 1) / 2 + 1, side / 2 + 2);
            for (int tx = tileX() - r; tx <= tileX() + r; tx++)
              for (int ty = tileY() - r; ty <= tileY() + r; ty++) {
                Tile t = world.tile(tx, ty);
                Building m = t == null ? null : t.build;
                if (m == null || m == this || !m.isValid() || union.contains(m))
                  continue;
                if (m instanceof mindustry.world.blocks.heat.HeatBlock hb
                    && !(m instanceof SuperTurret.HeatProbe.HeatProbeBuild))
                  poolGeo += Math.max(0f, hb.heat());
              }
            if (poolGeo > poolHeat) {
              poolHeat = poolGeo;
            }
          } catch (Throwable ignored) {
          }
        }
        // 【节点网络领热】组合节点的热量是"分配制"：distributeHeat(node, true) 把
        // 产热组存量热扣走、按各组需求比例挪进 heatAlloc，需热方必须调 heatFor(自己)
        // 领配额。连接器是全共享 allocate=false、热留在产热方 heat() 里（并集能读到），
        // 节点则把热搬走了 —— 不领就是 0（用户抓包：节点不行、连接器可以）。
        try {
          float allocated = ComboNet.heatFor(this);
          if (allocated > poolHeat) {
            poolHeat = allocated;
          }
        } catch (Throwable ignored) {
        }
      } catch (Throwable t) {
        // 之前静默吞掉 → 池日志整条消失、热传递全停还无任何报错（用户第 21 轮抓包）。
        // 改为一台炮台只报一次，日志里能看到具体行号。
        if (cellBroken.add(-3))
          Log.err("[combine] 热池计算每 tick 抛异常", t);
      }
      totalHeat = Math.max(totalHeat, poolHeat);
      comboTotalHeat = totalHeat;
      // 整台的需热合计（= 里面每一台需热炮台 heatRequirement 之和）：池子按它分摊给各格。
      float demandTotal = heatDemand();
      for (int i = 0; i < probes.length; i++) {
        HeatProbe.HeatProbeBuild p = probes[i];
        if (p == null)
          continue;
        // 【整台一个热量池 → 按需分摊】需热格子拿到的是"整台池子按这一格需求占的比例"，
        // 于是每格效率 = 池热量 / 整台需求：N 台需热炮台合体后就要 N 份热，不再是一份。
        p.amount = heatShare(i, totalHeat, demandTotal);
        p.team = team;
      }

      // 液体超容量裁剪：acceptLiquid 按"当前这一格"放行，组合并池/网络换模块会把
      // 几份液体叠进同一个模块（用户抓包 4495/900），每帧把每种液体夹回真实容量。
      try {
        if (liquids != null) {
          for (mindustry.type.Liquid liq : content.liquids()) {
            float amt = liquids.get(liq);
            if (amt > realLiquidCap)
              liquids.remove(liq, amt - realLiquidCap);
          }
        }
      } catch (Throwable ignored) {
      }

      // 【用户要求】手动操控这台时，里面**所有**炮台都跟着玩家的准星/开火键一起打
      // （不是只有被接管的某一格）
      syncCellControls();

      // 轮询装填：每格每轮 8 发、共 16 轮 —— 所有格子一起装，而不是排队的"挨个填充"。
      // 轮数 × 每轮发数 = 128/格/帧，和原来的单格上限一致，只是从" sequential 灌满"
      // 变成"全体齐头并进"。
      for (int round = 0; round < 16; round++)
        feedAmmoRound(8);

      for (int i = 0; i < cells.length; i++) {
        Turret.TurretBuild c = cells[i];
        if (c == null || c.dead())
          continue;
        // 池子/电网可能被 ComboNet 换成别的模块对象：每帧对齐引用
        if (c.items != items)
          c.items = items;
        if (c.liquids != liquids)
          c.liquids = liquids;
        if (c.power != null && power != null)
          c.power.status = power.status;
        try {
          c.update();
          // 【热量直灌】需热格子（劫难等 heatRequirement>0）打完原版更新后，把分到这一格的
          // 热量直写进 heatReq —— 格子住假格子，探针能不能被 calculateHeat 看见取决于太多
          // 环节（用户抓包：探针挂在炮台 proximity 里，格子 heatReq 仍 0，装填 1%、根本不
          // 发射）。这不是显示，canConsume 直接 false。分到的那一份见 heatShare()。
          float cellReq = c.block instanceof Turret t ? t.heatRequirement : 0f;
          if (cellReq > 0f && demandTotal > 0f)
            c.heatReq = totalHeat * cellReq / demandTotal;
        } catch (Throwable t) {
          if (cellBroken.add(i))
            Log.err("[combine] 超级组合炮台第 @ 格（@）更新出错，已停用这一格", i,
                cellBlocks[i] == null ? "?" : cellBlocks[i].name, t);
          cells[i] = null;
        }
      }
    }

    /** 各格的用电量合计（上报给电网）。 */
    public float cellPowerUse() {
      float sum = 0f;
      for (Turret.TurretBuild c : cells) {
        if (c == null || c.block == null || !c.block.hasPower || c.block.consPower == null)
          continue;
        try {
          sum += c.block.consPower.requestedPower(c);
        } catch (Throwable ignored) {
        }
      }
      return sum;
    }

    /**
     * 从共享物品池往这一格的弹仓里搬弹药（和邻居送货一样：remove + handleItem）。
     *
     * <p>这一格选了弹药（{@link #cellAmmo}）时**只喂那一种** —— 效果就是"像组合炮台一样选弹药"。
     */
    /**
     * 从共享物品池往这一格的弹仓里搬弹药（和邻居送货一样：remove + handleItem）。
     *
     * <p>这一格选了弹药（{@link #cellAmmo}）时**只喂那一种** —— 效果就是"像组合炮台一样选弹药"。
     *
     * <p>{@code budget} 是本次调用最多搬几发：updateTile 用轮询方式喂（每格每轮一点），
     * 让所有格子**同时**装填，而不是先把第 0 格塞满才轮到下一格（用户报的"挨个填充"）。
     */
    public void feedAmmo(Turret.TurretBuild c, int idx, int budget) {
      if (items == null || !(c instanceof ItemTurret.ItemTurretBuild itb))
        return;
      ItemTurret it = (ItemTurret) c.block;
      if (it == null || it.ammoTypes == null || budget <= 0)
        return;
      Item forced = cellAmmoAt(idx);
      if (forced != null) {
        if (it.ammoTypes.get(forced) == null || items.get(forced) <= 0)
          return;
        int guard = 0;
        while (items.get(forced) > 0 && itb.acceptItem(this, forced) && guard++ < budget) {
          items.remove(forced, 1);
          itb.handleItem(this, forced);
        }
        return;
      }
      for (Item item : it.ammoTypes.keys()) {
        if (items.get(item) <= 0)
          continue;
        int guard = 0;
        while (items.get(item) > 0 && itb.acceptItem(this, item) && guard++ < budget) {
          items.remove(item, 1);
          itb.handleItem(this, item);
        }
      }
    }

    /** 轮询装填一整轮：每格最多 {@code perCell} 发，轮流喂一遍。 */
    public void feedAmmoRound(int perCell) {
      for (int i = 0; i < cells.length; i++) {
        Turret.TurretBuild c = cells[i];
        if (c == null || c.dead())
          continue;
        try {
          feedAmmo(c, i, perCell);
        } catch (Throwable t) {
          if (cellBroken.add(i))
            Log.err("[combine] 超级组合炮台第 @ 格（@）装填出错，已停用这一格", i,
                cellBlocks[i] == null ? "?" : cellBlocks[i].name, t);
          cells[i] = null;
        }
      }
    }

    /** 这一格选定的弹药（null = 自动）。 */
    public @Nullable Item cellAmmoAt(int idx) {
      return cellAmmo != null && idx >= 0 && idx < cellAmmo.length ? cellAmmo[idx] : null;
    }

    /**
     * 给第 idx 格换弹药：{@code item == null} 表示"自动"。
     *
     * <p>换的时候把这一格弹仓里**已经装着的**弹药按 ammoMultiplier 折算回物品退回共享池
     * （不凭空造货、也不丢库存），下一帧起就会按新的选择重新装填。
     */
    public void setCellAmmo(int idx, @Nullable Item item) {
      ensureCells();
      int n = side * side;
      if (idx < 0 || idx >= n || cells.length != n)
        return;
      if (cellAmmo.length != n)
        cellAmmo = new Item[n];
      cellAmmo[idx] = item;

      Turret.TurretBuild c = cells[idx];
      if (c instanceof ItemTurret.ItemTurretBuild itb && items != null && itb.ammo != null) {
        int total = 0;
        for (int k = 0; k < itb.ammo.size; k++) {
          Turret.AmmoEntry e = itb.ammo.get(k);
          int per = 1;
          if (e instanceof ItemTurret.ItemEntry ie && ie.item != null) {
            var type = ((ItemTurret) c.block).ammoTypes.get(ie.item);
            if (type != null)
              per = Math.max(1, (int) type.ammoMultiplier);
            int give = ie.amount / per;
            if (give > 0)
              items.add(ie.item, give);
            // 【换弹要干净】不足 1 个物品的零头（<per 个单位）直接丢掉：
            // 留着的话这一格还会用旧弹药打一两发，看着就像"选了没生效"。
            // 反过来也不可能把零头当成 1 个物品退回池子（那是凭空造货）。
            ie.amount = 0;
          }
          total += Math.max(0, e.amount);
        }
        itb.totalAmmo = total;
      }
    }

    /** 点方块 → 给每一格选弹药（和组合炮台的弹药/冷却液选择同一套 UI）。 */
    @Override
    public void buildConfiguration(arc.scene.ui.layout.Table table) {
      try {
        ensureCells();
        Seq<Integer> itemCells = new Seq<>();
        for (int i = 0; i < cells.length; i++)
          if (cells[i] instanceof ItemTurret.ItemTurretBuild)
            itemCells.add(i);
        if (itemCells.isEmpty()) {
          table.add("[lightgray]这台里面没有装物品炮台").pad(6f);
          return;
        }
        int[] sel = { itemCells.first() };
        arc.scene.ui.layout.Table ammo = new arc.scene.ui.layout.Table();
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
          ammo.clearChildren();
          int idx = sel[0];
          if (!(cellBlocks[idx] instanceof ItemTurret it)) {
            ammo.add("-");
            return;
          }
          Seq<Item> items2 = new Seq<>();
          items2.addAll(it.ammoTypes.keys());
          ammo.button("自动", mindustry.ui.Styles.cleart,
              () -> configure((Integer) (idx | (0 << 16)))).size(96f, 40f).pad(4f)
              .update(b -> b.setChecked(cellAmmoAt(idx) == null));
          ammo.row();
          mindustry.world.blocks.ItemSelection.buildTable(SuperTurret.this, ammo, items2, () -> cellAmmoAt(idx),
              item -> configure((Integer) (idx | (((item == null ? 0 : item.id + 1)) << 16))));
        };
        // 【用户要求】面板要小、要能换行：每行正好是合体炮台的一行（= 边长格数），
        // 按钮也缩小；行序按世界里的上下顺序排（第一行 = 炮台最上面一行）。
        arc.scene.ui.layout.Table cellsRow = new arc.scene.ui.layout.Table();
        cellsRow.defaults().size(CELL_BTN_W, CELL_BTN_H).pad(1f);
        for (int row = side - 1; row >= 0; row--) {
          for (int col = 0; col < side; col++) {
            int idx = row * side + col;
            boolean pickable = itemCells.contains(idx);
            arc.scene.ui.layout.Cell<arc.scene.ui.TextButton> cell = cellsRow
                .button(pickable ? String.valueOf(idx + 1) : "-", mindustry.ui.Styles.cleart, () -> {
                  if (!pickable)
                    return;
                  sel[0] = idx;
                  rebuild[0].run();
                });
            if (pickable)
              cell.update(b -> b.setChecked(sel[0] == idx));
            else
              cell.get().setDisabled(true); // 这一格不是物品炮台：留占位，网格才和炮台对得上
          }
          cellsRow.row();
        }
        table.add("[accent]超级组合炮台 " + side + "x" + side + "[]：每格可单独选弹药").left().row();
        table.add(cellsRow).left().padTop(4f).row();
        table.add(ammo).left().row();
        rebuild[0].run();
      } catch (Throwable t) {
        Log.err("[combine] 超级组合炮台弹药选择面板构建失败", t);
      }
    }

    // ==================== 格子构建 / 序列化 ====================

    /** 布局没变就什么都不做（读同步包时每帧都会走一遍）。 */
    public void applyLayout(String l) {
      String next = l == null ? "" : l;
      if (cellsReady && next.equals(layout))
        return;
      layout = next;
      disposeCells();
      buildCells();
    }

    public void ensureCells() {
      if (!cellsReady)
        buildCells();
    }

    void disposeCells() {
      for (Turret.TurretBuild c : cells)
        if (c != null)
          try {
            c.remove();
          } catch (Throwable ignored) {
          }
      cells = new Turret.TurretBuild[0];
      cellBlocks = new Block[0];
      cellRot = new float[0];
      probes = new HeatProbe.HeatProbeBuild[0];
      cellAmmo = new Item[0];
      cellsReady = false;
      cellBroken.clear();
    }

    /** 按 layout 造格子（每一格一个挂假 Tile 的虚拟炮台）。 */
    void buildCells() {
      int n = side * side;
      String[] tokens = cellTokens(layout);
      Turret.TurretBuild[] cs = new Turret.TurretBuild[n];
      Block[] bs = new Block[n];
      float[] rs = new float[n];
      HeatProbe.HeatProbeBuild[] ps = new HeatProbe.HeatProbeBuild[n];
      int itemCap = 0;
      float liquidCap = 0f;
      float healthSum = 0f;

      for (int i = 0; i < n; i++) {
        String token = i < tokens.length ? tokens[i] : "";
        Block cb = blockOfToken(token);
        if (cb == null)
          continue;
        int cx = cellTile(tile.x, side, i % side), cy = cellTile(tile.y, side, i / side);
        Turret.TurretBuild c = makeCell(cb, cx, cy, rotOfToken(token));
        if (c == null)
          continue;
        cs[i] = c;
        bs[i] = cb;
        rs[i] = rotOfToken(token);
        // 每格一个热量探针（摆在格子自己的坐标上，diff=0 -> 原版算作"整份热量都在这一格"）
        HeatProbe.HeatProbeBuild p = makeProbe(cx, cy);
        if (p != null) {
          ps[i] = p;
          c.proximity = new Seq<>();
          c.proximity.add(p);
        }
        if (cb.hasItems)
          itemCap += cellItemCap(cb);
        if (cb.hasLiquids)
          liquidCap += Math.max(cb.liquidCapacity, 0f);
        healthSum += Math.max(cb.health, 1f);
      }

      cells = cs;
      cellBlocks = bs;
      cellRot = rs;
      probes = ps;
      realItemCap = Math.max(itemCap, 1);
      realLiquidCap = Math.max(liquidCap, 1f);
      // 【用户要求】本体的血量 = 里面所有炮台的血量累加。
      // 原本满血的那台（配置/读档后重算容量时）跟着新的上限一起满血，受过伤的按上限夹一下。
      // 【用户报"一造出来血量不是满的"】示意图/蓝图那条放置路径会**先应用配置、后初始化血量**，
      // 这时候 health 还是 0 —— 0 血量显然是"刚造出来"，直接按满血算，别把它当成受损状态。
      float oldMax = maxHealth;
      maxHealth = Math.max(healthSum, 1f);
      // 【刚造出来必满血】原版 constructFinish 在 created() **之后**还会执行
      // tile.build.health = block.health * healthf —— 用方块静态血量（边长那点默认值）
      // 覆盖我们刚设好的满血，等布局 configure 进来再跑 buildCells 时，这份"满血"已经
      // 小于旧上限，被当成"受损状态"夹住（用户报的"刚造出来血量不是满的"）。
      // 对策：created() 里立 placedFullHealth 标志，下一次 buildCells 强制满血后清除；
      // 另加一条兜底：血量还没超过方块静态默认值，也只可能是刚放置（正常受损不可能
      // 恰好停在默认值），同样按满血处理。
      if (placedFullHealth || oldMax <= 0.001f || health <= 0.5f || health >= oldMax - 0.5f
          || health <= block.health + 0.5f)
        health = maxHealth;
      else
        health = Math.min(health, maxHealth);
      placedFullHealth = false;
      cellsReady = true;
    }

    /** 造一个热量探针（假 build：只有 block/坐标/队伍/heat，被 {@code calculateHeat} 当热源读）。 */
    @Nullable
    HeatProbe.HeatProbeBuild makeProbe(int cx, int cy) {
      try {
        if (heatProbe == null)
          return null;
        Building raw = heatProbe.newBuilding();
        if (!(raw instanceof HeatProbe.HeatProbeBuild p))
          return null;
        p.create(heatProbe, team);
        p.tile = new Tile(cx, cy);
        p.set(cx * tilesize, cy * tilesize);
        p.proximity = new Seq<>();
        p.team = team;
        return p;
      } catch (Throwable t) {
        Log.err("[combine] 超级组合炮台：热量探针创建失败", t);
        return null;
      }
    }

    static int cellItemCap(Block b) {
      if (b instanceof Turret t)
        return Math.max(t.maxAmmo, 1);
      return Math.max(b.itemCapacity, 1);
    }

    /** 造一格虚拟炮台：原版 build 类 + 一张只属于它的假 Tile（坐标 = 这一格的中心）。 */
    @Nullable
    Turret.TurretBuild makeCell(Block cb, int cx, int cy, float rot) {
      try {
        Building raw = cb.newBuilding();
        if (!(raw instanceof Turret.TurretBuild c))
          return null;
        Tile fake = new Tile(cx, cy);
        setTileBlock(fake, cb);
        // 【不用 c.init()】Tile.changeBuild() 会把 build 真加进 Groups.build 并触发地块事件；
        // 这里手工装配：create() 建模块 → 挂假 Tile → 摆到这一格的中心 → created()
        c.create(cb, team);
        c.tile = fake;
        c.set(cx * tilesize, cy * tilesize); // 假 Tile 的中心（= 这一格的世界坐标）
        c.proximity.clear();
        c.items = items;
        c.liquids = liquids;
        fake.build = c; // isValid() 靠这一条；它不在世界网格里（world.tile(x,y).build 是本体）
        c.rotation = rot;
        c.health = c.maxHealth;
        c.enabled = true;
        c.checkAllowUpdate();
        c.created();
        return c;
      } catch (Throwable t) {
        if (cellBroken.add(-1))
          Log.err("[combine] 超级组合炮台：@ 不能作为格子炮台（已留空）", cb.name, t);
        return null;
      }
    }

    @Override
    public Object config() {
      // 走紧凑编码：原版会把 config 塞进 ConstructFinish/TileConfig 包，
      // 客户端按 readObjectSafe（上限 1200 字符）读，超了会直接踢客户端（见 encodeConfig）。
      return encodeConfig(layout, sources == null ? "" : sources);
    }

    @Override
    public void configured(@Nullable mindustry.gen.Unit builder, @Nullable Object value) {
      if (value instanceof String s) {
        // 紧凑串（本模组新格式）和老文本串 "layout|sources" 都认。
        String[] parts = decodeConfig(s);
        applyLayout(parts[0]);
        sources = parts[1] == null ? "" : parts[1];
        if (!sources.isEmpty())
          consumeSources();
      } else if (value instanceof Integer packed) {
        // 选弹药：(格号 | ((物品id+1) << 16))，0 = 自动
        int idx = packed & 0xFFFF;
        int ord = (packed >>> 16) & 0xFFFF;
        setCellAmmo(idx, ord <= 0 ? null : content.item(ord - 1));
      }
      super.configured(builder, value);
    }

    /**
     * 把被框选进来、现在又被"装进"这台超级炮台的炮台拆掉。
     *
     * <p>只在服务端/单机做（客户端等服务端同步，免得两边各自删一遍）；坐标来自配置串，
     * 而且只拆"这里还站着炮台"的格子 —— 玩家提前拆掉/换了别的东西就不动。
     * 读档不会重跑（{@link #read} 里直接把 sourcesConsumed 置 true）。
     */
    public void consumeSources() {
      if (sourcesConsumed || sources == null || sources.isEmpty())
        return;
      sourcesConsumed = true;
      if (net.client())
        return;
      // 【每次合体只能吃一份炮台】用户报"多人游戏每个客户端都能各自合成一次"：
      // 原料炮台只在**服务端**被消费，第二个客户端（本地世界还没收到拆除包，或者干脆是
      // 复制粘贴出来的配置）再合成一次时，它这份 config 里的 sources 指向的炮台早就没了；
      // 旧实现"没东西可拆"就照样放行，等于白送一台满格超级炮台。
      //
      // 现在逐格核对：第 i 格的原料炮台必须**真的还在**（同队 + 是同一种炮台），
      // 拆掉它、保留这一格；否则（原料没了 / 类型不符 / 不是自己队的）这一格清空 ——
      // 于是第二次合体只能得到一台"空壳"，同时顺带堵住"蓝图复制一台超级炮台白嫖"。
      String[] cells = cellTokens(layout);
      String[] srcs = sources.split(";");
      boolean changed = false;
      for (int i = 0; i < cells.length; i++) {
        Block want = blockOfToken(cells[i]);
        if (want == null)
          continue; // 空格子
        Tile t = i < srcs.length ? sourceTile(srcs[i]) : null;
        if (t != null && t.build != null && t.build.team == team && !t.build.dead()
            && t.block() instanceof Turret && cellBlock(t.block()) == want) {
          try {
            t.removeNet(); // 原版的"跨网络同步删格"
            continue;
          } catch (Throwable e) {
            Log.err("[combine] 拆掉被框选的炮台失败（@,@）", t.x, t.y, e);
          }
        }
        cells[i] = "";
        changed = true;
      }
      if (changed) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
          if (i > 0)
            sb.append(';');
          sb.append(cells[i]);
        }
        Log.warn("[combine] 超级组合炮台：有一格的原料炮台已经不在（多半是重复合体/蓝图复制），该格已清空");
        applyLayout(sb.toString());
      }
    }

    /** "x,y" → 那一格；解析不出来返回 null。 */
    static Tile sourceTile(String tok) {
      if (tok == null || tok.isEmpty())
        return null;
      int comma = tok.indexOf(',');
      if (comma < 0)
        return null;
      try {
        return world.tile(Integer.parseInt(tok.substring(0, comma).trim()),
            Integer.parseInt(tok.substring(comma + 1).trim()));
      } catch (Throwable t) {
        return null;
      }
    }

    @Override
    public byte version() {
      return 1;
    }

    /**
     * 【联机血量】原版 {@code Building.readBase} 会 {@code health = Math.min(read.f(), block.health)} ——
     * 这个 {@code block.health} 是"方块静态血量"（超级炮台按边长²估出来的一个小值，和多格炮台
     * 累加出来的真实整组血量差一个量级）。联机快照（NetClient.blockSnapshot → readSync）和存档
     * 读回的"整组血量"一过这里就被夹回单台量级 —— 用户报的"多人游戏时一段时间后合体炮台的
     * 血量会回到一个炮台的血量"就是这个。
     *
     * <p>读的这一刻临时把静态上限放开，读完再按本台真实的 {@code maxHealth}（read() 里
     * applyLayout → buildCells 按成员重算过）夹一次。
     */
    @Override
    public void readAll(Reads read, byte revision) {
      int old = block.health;
      try {
        block.health = Integer.MAX_VALUE;
        super.readAll(read, revision);
      } finally {
        block.health = old;
      }
      if (maxHealth > 0.5f)
        health = Mathf.clamp(health, 0f, maxHealth);
    }

    @Override
    public void write(Writes write) {
      super.write(write);
      write.str(layout);
      write.str(sources == null ? "" : sources);
      // 每格选定的弹药（0 = 自动，itemId+1 = 指定）
      for (int i = 0; i < side * side; i++) {
        Item a = cellAmmoAt(i);
        write.s(a == null ? 0 : a.id + 1);
      }
      for (Turret.TurretBuild c : cells) {
        if (c == null) {
          write.b((byte) -1);
        } else {
          write.b(c.version());
          c.write(write);
        }
      }
    }

    @Override
    public void read(Reads read, byte revision) {
      super.read(read, revision);
      applyLayout(read.str());
      sources = read.str();
      sourcesConsumed = true; // 存档里这些炮台早就拆掉了，读档别再拆一遍
      cellAmmo = new Item[side * side];
      for (int i = 0; i < side * side; i++) {
        int ord = read.s();
        cellAmmo[i] = ord <= 0 ? null : content.item(ord - 1);
      }
      int n = side * side;
      for (int i = 0; i < n; i++) {
        byte ver = read.b();
        Turret.TurretBuild c = i < cells.length ? cells[i] : null;
        if (c == null)
          continue;
        c.read(read, ver);
      }
    }

    // ==================== 物品 / 液体 ====================

    @Override
    public int getMaximumAccepted(Item item) {
      return realItemCap;
    }

    /** 只要任一格"会吃"这种弹药就收（上限按各格弹仓之和）。 */
    @Override
    public boolean acceptItem(Building source, Item item) {
      if (items == null || item == null || items.get(item) >= realItemCap)
        return false;
      for (int i = 0; i < cells.length; i++) {
        Turret.TurretBuild c = cells[i];
        if (!(c instanceof ItemTurret.ItemTurretBuild))
          continue;
        // 这一格选了别的弹药 -> 不进池（免得攒一堆没人用的）
        Item forced = cellAmmoAt(i);
        if (forced != null && forced != item)
          continue;
        // 【别问格子的 acceptItem】格子的内部弹仓一满 itb.acceptItem 就 false，
        // 送货的（传送带/单位）就全停了 —— 表现正是"共享池还没收满就不再收货"。
        // 池子本身才是收货口：这一格的炮台吃这种弹药就让它进池，
        // 池 -> 弹仓的搬运由 feedAmmo 按弹仓余量自己调节。
        ItemTurret it = (ItemTurret) c.block;
        if (it.ammoTypes != null && it.ammoTypes.containsKey(item))
          return true;
      }
      return false;
    }

    @Override
    public void handleItem(Building source, Item item) {
      if (items != null)
        items.add(item, 1);
    }

    @Override
    public int acceptStack(Item item, int amount, mindustry.gen.Teamc source) {
      if (items == null)
        return 0;
      return Math.min(amount, Math.max(0, realItemCap - items.get(item)));
    }

    @Override
    public void handleStack(Item item, int amount, mindustry.gen.Teamc source) {
      if (items != null)
        items.add(item, amount);
    }

    @Override
    public int removeStack(Item item, int amount) {
      if (items == null)
        return 0;
      int removed = Math.min(amount, items.get(item));
      items.remove(item, removed);
      return removed;
    }

    /** 任一格认这种液体（冷却液等）就收；上限 = 各格液体容量之和。 */
    @Override
    public boolean acceptLiquid(Building source, Liquid liquid) {
      if (liquids == null || liquid == null)
        return false;
      if (liquids.get(liquid) >= realLiquidCap)
        return false;
      for (Turret.TurretBuild c : cells) {
        if (c != null && c.block != null && c.block.hasLiquids && c.block.consumesLiquid(liquid))
          return true;
      }
      return false;
    }

    @Override
    public void handleLiquid(Building source, Liquid liquid, float amount) {
      if (liquids != null)
        liquids.add(liquid, amount);
    }

    // ==================== 绘制 ====================

    @Override
    public void draw() {
      if (proceduralPlate)
        drawPlate(x, y, side);
      else
        super.draw();
      ensureCells();
      for (int i = 0; i < cells.length; i++) {
        Turret.TurretBuild c = cells[i];
        if (c == null)
          continue;
        Block cb = i < cellBlocks.length ? cellBlocks[i] : null;
        float k = cellScale(cb);
        // 【缩放照原版那套 Draw.scl 来】以前是拿变换矩阵（Draw.trans）绕格子中心缩，
        // 但有的客户端（带自定义批处理的移动端）不吃那个矩阵，表现就是"整格没画出来"。
        // arc 里 Draw.rect(region,x,y,rot) 和 RegionPart 画部件时都会乘 xscl/yscl，
        // 所以像原版一样设一次缩放就够，也顺带避免和相机/批处理的矩阵打架。
        float ox = Draw.xscl, oy = Draw.yscl;
        if (k != 1f)
          Draw.scl(k);
        try {
          if (cellDrawerIncomplete(cb)) {
            // drawer 的图没加载上：只能画整块图标，至少这一格是一台完整的炮台
            drawCellIcon(cb, c.x, c.y, c.rotation);
          } else {
            // 照原版 DrawTurret 那套画（本体/液体/顶盖 + 描边 + parts，带后坐/热量动画），
            // 但**跳过通用底板（base）和阴影** —— 底板由超级炮台自己那块大板负责，
            // 每格再垫一张 block-N 小底板就是用户报的"多出来的底座"。
            // 不直接调 c.draw()：原版把"描边+全部 parts"画在同一段里，任何一个 part
            // 抛异常会整段丢失（"炮管和轮廓线一起没"就是这么来的）；这里逐件隔离，
            // 一件坏了只丢那一件，其余照常画。
            drawCellDrawer(cb, c);
          }
        } catch (Throwable ignored) {
        } finally {
          if (k != 1f)
            Draw.scl(ox, oy);
        }
      }
      Draw.reset();
    }

    /** 选中时把每格的射程圈画出来，方便看这台炮台到底覆盖到哪。 */
    @Override
    public void drawSelect() {
      super.drawSelect();
      for (Turret.TurretBuild c : cells) {
        if (c == null)
          continue;
        try {
          Drawf.dashCircle(c.x, c.y, c.range(), team.color);
        } catch (Throwable ignored) {
        }
      }
    }
  }

  /** 供测试/调试：这台建筑现在有几格真的装了炮台。 */
  public static int loadedCells(Building b) {
    if (!(b instanceof SuperTurretBuild sb))
      return 0;
    int n = 0;
    for (Turret.TurretBuild c : sb.cells)
      if (c != null)
        n++;
    return n;
  }
}
