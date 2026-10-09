package combine.production;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.struct.FloatSeq;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.io.Reads;
import arc.util.io.Writes;
import combine.BlockCloner;
import combine.net.ComboNet;
import combine.util.ComboHeatProbe;
import combine.util.ComboReflect;
import combine.util.IComboGrouped;
import mindustry.entities.part.DrawPart;
import mindustry.entities.part.RegionPart;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Liquid;
import mindustry.type.LiquidStack;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.heat.HeatBlock;
import mindustry.world.blocks.heat.HeatProducer;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.Separator;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItems;
import mindustry.world.consumers.ConsumePower;
import mindustry.world.draw.DrawBlock;
import mindustry.world.draw.DrawBlockParts;
import mindustry.world.draw.DrawMulti;
import mindustry.world.meta.BuildVisibility;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 超级组合工厂（合体工厂）：把框里的多台工厂合成 **一台 side×side 的方块**（每格一台工厂、缩到 1 格），
 * 物品 / 液体 / 电力 / 热量整台共享，可以继续往里加工厂，也能解体拆回去。
 *
 * <p>【命名/结构】用户 2026-10-04 要求：这套合体机制要**独立成一个新方块类**
 * （和"超级组合炮台 = {@code SuperTurret}"对仗），不去改动那些把原版工厂替换成组合工厂的类
 * （{@code CombinedCrafter} 等）。所以这里自成一个 {@code SuperCombineFactory} +
 * 同名 build，方块名 {@code super-combine-factory}，是**只在合体流程里出现的隐藏方块**：
 * 不进建造菜单、不参与任何原版方块的替换。
 *
 * <p>【为什么用"虚拟格子"而不是自己重写一套合成逻辑】旧版是自己算配方：只读
 * {@code outputItems} + {@code ConsumeItems/ConsumeLiquids}，于是耗电、耗热、地形加成、
 * 分离机权重、液体加成这些**全被无视**（实测：不接电也照产）。现在合体后的每一台工厂都是
 * 一个挂在假 Tile 上的**真 build**（和超级组合炮台同一套做法，见 {@code SuperTurret.makeCell}），
 * 各自跑原版 {@code updateTile()}，只是：
 * <ul>
 *   <li>物品 / 液体模块指向本体那一份（= 整台共用一份库存）；</li>
 *   <li>电力：本体用 {@link FactoryConsumePower} 把各格用电量加总上报电网，电网算出的
 *       满足率再回写给每一格；</li>
 *   <li>热量：每格挂一个热量探针（{@link ComboHeatProbe}），整台一个热池、按各格需求分摊；</li>
 *   <li>里面自己的制热机产出的热也进这个池子，供同台其它格子（和整张组合网络）用。</li>
 * </ul>
 *
 * <p>本方块带 {@code comboGroup/comboLeader/comboDirty} 这套约定字段 + 实现
 * {@link IComboGrouped}，所以组合连接器 / 组合节点 / 别的组合体都能把它接成一张网络。
 */
public class SuperCombineFactory extends Block {
  /**
   * 【用户要求 2026-10-04】"每个工厂变成 1x1，不是全部加起来变成 1x1 大小" ——
   * 所以每种边长一个方块实例（下标 = 边长），台数 → ceil(sqrt(台数)) × ceil(sqrt(台数))，
   * 每一格摆一台（缩放到 1 格）工厂，和超级组合炮台（{@code SuperTurret.bySide}）同一套做法。
   */
  public static final int MIN_SIDE = 2, MAX_SIDE = 8;
  public static final SuperCombineFactory[] bySide = new SuperCombineFactory[MAX_SIDE + 1];

  /** 紧凑配置串前缀：'~' + base64(二进制)，理由见 {@link #pack64}。 */
  public static final String CONFIG_TAG = "~";
  /** 原版 {@code TypeIO.readObjectSafe} 的上限是 1200，留点余量。 */
  public static final int CONFIG_MAX = 1150;
  /**
   * 「解体」配置哨兵：点方块打开的面板上的按钮发这个串（走 config 通道 = 服务端权威执行 +
   * 同步给所有客户端），触摸端也有入口。布局里的方块名只可能是 {@code [a-z0-9-]}，不会撞。
   */
  public static final String DISSOLVE_TAG = "!dissolve";
  /** 一台组合工厂最多装多少台工厂。 */
  public static final int MAX_FACTORIES = MAX_SIDE * MAX_SIDE;
  /** 本台的边长（占 side × side 格）。 */
  public final int side;

  public SuperCombineFactory(String name, int side) {
    super(name);
    this.side = side;
    size = side;
    update = true;
    solid = true;
    sync = true;
    saveConfig = true;
    configurable = true; // 点方块 → 面板（里面有"解体"，触摸端入口）
    rotate = false;
    drawArrow = false;
    hasItems = true;
    hasLiquids = true;
    hasPower = true;
    // 【放置即成型】原版会先摆一个 ConstructBlock（施工中：目标方块贴图压暗 + 进度条），
    // 完工才变成真方块。本方块是隐藏的虚拟方块、没有专属贴图，施工那一两秒就是用户报的
    // "放下去先暗一下"。合体本来就该是一次成型，所以标 instantBuild 跳过施工阶段。
    instantBuild = true;
    // 能被超速投影/超频加速（和超级组合炮台同一口径，本体加速后逐格同步 timeScale）
    canOverdrive = true;
    category = mindustry.type.Category.production;
    // 建造菜单里**不出现**（入口只有 HUD 上那个"工厂合体"子按钮，见 FactoryCombiner）。
    // 图标/贴图由 Main.ensureIcons 兜底，绝不留 null。
    buildVisibility = BuildVisibility.hidden;
    // 容量按"各格之和"现算（见 SuperCombineFactoryBuild.buildCells），这里只是网络记账用的估值
    itemCapacity = 1280;
    liquidCapacity = 9999f;
    buildType = SuperCombineFactoryBuild::new;
    consume(new FactoryConsumePower());
    // 【必须有】原版 `Block.offset`（偶数边长 = tilesize/2，奇数边长 = 0）是在**内容加载收尾**
    // 的 afterPatch 里按 size 算的；本方块是 Mod.init() 里现造的，那一轮早过了 → offset 一直是 0。
    // 后果：原版这边凡是"以方块中心为准"的东西（状态图标 / 选中框 / 悬停 / 剔除矩形 / 蓝图矩形）
    // 都按"锚点格中心"摆，而我们的贴图按 footprint 中心画 → 差半格，看着就是"贴图与建筑本体错位"。
    offset = (side + 1) % 2 * tilesize / 2f;
  }

  @Override
  public void init() {
    super.init();
  }

  /**
   * 信息面板里的条。原版这些条是在内容加载收尾的 afterPatch 里建的，我们的方块是
   * Mod.init() 里现造的 → 必须自己调一次（Main 里创建完就调）。
   *
   * <p>用户 2026-10-05 要求 display 里要有"产物的 bar"：物品 / 液体 / 电力 / 热量 四条都要。
   * 原版默认注册的那几条分子分母全不对 —— 物品条的分母是方块上那个静态估算
   * {@code itemCapacity=1280}、液体条是 {@code liquidCapacity=9999} 的假容量（合体后真正的上限
   * 是每台之和，写在 {@code realItemCap/realLiquidCap} 里）。所以这里把默认的物品/液体/电力条
   * 摘掉，四条池子条统一由 {@code SuperCombineFactoryBuild#buildPoolBars} 按整台共享池的口径画。
   */
  @Override
  public void setBars() {
    super.setBars();
    try {
      removeBar("items");
      removeBar("liquid");
      removeBar("power");
    } catch (Throwable t) {
      Log.err("[combine] 组合工厂信息面板的默认条精简失败（沿用原版默认条）", t);
    }
  }

  /** 台数 → 需要的边长：ceil(sqrt(n))，夹在 MIN_SIDE..MAX_SIDE。 */
  public static int sideFor(int count) {
    int s = MIN_SIDE;
    while (s < MAX_SIDE && s * s < count)
      s++;
    return s;
  }

  /** 边长对应的方块实例（内容还没装配时返回 null）。 */
  public static @Nullable SuperCombineFactory blockForSide(int side) {
    if (side < MIN_SIDE || side > MAX_SIDE)
      return null;
    return bySide[side];
  }

  /** 没有专属贴图时退到"多向压机"图标 —— uiIcon 绝不能是 null（建造菜单/数据库会 NPE）。 */
  @Override
  public void loadIcon() {
    super.loadIcon();
    try {
      TextureRegion custom = Core.atlas == null ? null : Core.atlas.find("combine-" + name);
      if (custom == null || !custom.found())
        custom = Core.atlas == null ? null : Core.atlas.find("multi-press", Core.atlas.find("error"));
      if (custom != null && custom.found()) {
        fullIcon = custom;
        uiIcon = custom;
      }
    } catch (Throwable ignored) {
    }
  }

  // ==================== 哪些方块算"工厂" ====================

  /**
   * 能合体的"工厂"：{@link GenericCrafter}（含 HeatCrafter / AttributeCrafter 这些子类）、
   * {@link Separator}（分离机）和 {@link HeatProducer}（制热机）。钻头 / 泵 / 挖墙钻不算工厂，
   * 不在这条合体流程里。
   *
   * <p>【用户报的"Separator 为什么无法合体"】旧口径只认 {@code GenericCrafter}，注释还写着
   * "Separator 是它的子类" —— 那是老版本的事；本机跑的 160.x 里 {@code Separator} 直接继承
   * {@link Block}（不是 GenericCrafter），于是分离机永远被判成"不是工厂"、框选时被跳过、
   * {@link #cellBlock} 也返回 null。这里显式补上分离机。
   */
  public static boolean isFactoryBlock(Block b) {
    return b instanceof GenericCrafter || b instanceof Separator || b instanceof HeatProducer;
  }

  /**
   * 世界里的方块 → 拿来当"格子工厂"的方块（组合方块换回它替换前的原版实例）。
   *
   * <p>格子跑的是**原版**逻辑：组合工厂自己负责并池，格子再套一层组合逻辑会互相打架。
   */
  public static @Nullable Block cellBlock(Block worldBlock) {
    if (worldBlock == null || !isFactoryBlock(worldBlock))
      return null;
    Block orig = BlockCloner.comboToOriginal.get(worldBlock);
    Block b = orig != null ? orig : worldBlock;
    return isFactoryBlock(b) ? b : null;
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

  /** 布局串里的一格 → 格子方块（null = 空格）。 */
  public static @Nullable Block blockOfToken(String token) {
    if (token == null || token.isEmpty())
      return null;
    int at = token.lastIndexOf('@');
    String name = at < 0 ? token : token.substring(0, at);
    return cellBlock(content.block(name));
  }

  public static float rotOfToken(String token) {
    if (token == null || token.isEmpty())
      return 0f;
    int at = token.lastIndexOf('@');
    if (at < 0)
      return 0f;
    try {
      return Float.parseFloat(token.substring(at + 1));
    } catch (Throwable t) {
      return 0f;
    }
  }

  // ==================== 配置串（联机安全） ====================
  //
  // 原版把 config 用 TypeIO.writeObject 塞进 ConstructFinish / TileConfig 包，客户端读的是
  // TypeIO.readObjectSafe —— 硬上限 1200 字符，超了直接 "String too long: 1200" 踢客户端。
  // 逐格文本 "方块名@朝向;…" + sources 在模组工厂（名字长）+ 多台时轻松过千，所以走
  // 二进制 + base64：每格 4 字节、每个坐标 4 字节，64 台满载 ≈ 700 字符，稳在 1200 以内。

  public static String encodeConfig(String layout, String sources) {
    return encodeConfig(layout, sources, null);
  }

  /**
   * 带上"原料工厂当时装着的货"（{@code carry[i]} = 第 i 个 source 的库存，逗号分隔的
   * {@code i物品id=数量} / {@code l液体id=数量}）。
   *
   * <p>【为什么必须带】合体预览盖在原料工厂上时，原版会**先把它拆掉**（物品模块跟着丢），
   * 之后才把 config 应用给新方块 —— 不把库存写进 config，玩家那点原料就凭空没了
   * （实测：并体时铜 7/铅 5 直接消失）。
   */
  public static String encodeConfig(String layout, String sources, String[] carry) {
    try {
      String enc = CONFIG_TAG + pack64(layout, sources, carry);
      if (enc.length() <= CONFIG_MAX)
        return enc;
      enc = CONFIG_TAG + pack64(layout, "", carry);
      if (enc.length() <= CONFIG_MAX)
        return enc;
      enc = CONFIG_TAG + pack64(layout, "", null);
      if (enc.length() <= CONFIG_MAX)
        return enc;
      String[] toks = cellTokens(layout);
      int keep = toks.length;
      while (keep > 0 && (CONFIG_TAG + pack64(joinCells(toks, keep), "", null)).length() > CONFIG_MAX)
        keep--;
      return CONFIG_TAG + pack64(joinCells(toks, keep), "", null);
    } catch (Throwable t) {
      Log.err("[combine] 组合工厂：配置串编码失败（改用逐格文本）", t);
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
  static String pack64(String layout, String sources, String[] carry) {
    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream d = new java.io.DataOutputStream(buf);
    try {
      String[] toks = cellTokens(layout);
      String[] src = (sources == null || sources.isEmpty()) ? new String[0] : sources.split(";");
      // 格式 2 = sources 每项带一个"整台"标志（加工厂：把一整台组合工厂并进来）
      boolean flagged = false;
      for (String s : src)
        if (s != null && s.startsWith("!"))
          flagged = true;
      d.writeByte(flagged ? 2 : 1);
      d.writeShort(toks.length);
      for (String tok : toks) {
        Block cb = blockOfToken(tok);
        d.writeShort(cb == null ? 0xFFFF : (cb.id & 0xFFFF));
        d.writeShort(Math.round(rotOfToken(tok)) & 0xFFFF);
      }
      d.writeShort(src.length);
      for (String s : src) {
        boolean wholeBuild = s != null && s.startsWith("!");
        String body = wholeBuild ? s.substring(1) : s;
        int comma = body == null ? -1 : body.indexOf(',');
        if (flagged)
          d.writeByte(wholeBuild ? 1 : 0);
        if (comma < 0) {
          d.writeShort(0);
          d.writeShort(0);
          continue;
        }
        try {
          d.writeShort(Integer.parseInt(body.substring(0, comma).trim()) & 0xFFFF);
          d.writeShort(Integer.parseInt(body.substring(comma + 1).trim()) & 0xFFFF);
        } catch (Throwable t) {
          d.writeShort(0);
          d.writeShort(0);
        }
      }
      // 原料工厂"当时的库存"（只有源被预览顶掉时才会用到；见 encodeConfig 的说明）
      int carrySlots = carry == null ? 0 : carry.length;
      int nonEmpty = 0;
      if (carry != null)
        for (String c : carry)
          if (c != null && !c.isEmpty())
            nonEmpty++;
      d.writeShort(nonEmpty);
      for (int i = 0; i < carrySlots && nonEmpty > 0; i++) {
        String c = carry[i];
        if (c == null || c.isEmpty())
          continue;
        int[] es = parseCarry(c);
        d.writeByte(i & 0xFF);
        d.writeShort(es.length / 3);
        for (int k = 0; k + 2 < es.length; k += 3) {
          d.writeByte(es[k]);
          d.writeShort(es[k + 1] & 0xFFFF);
          d.writeShort(es[k + 2] & 0xFFFF);
        }
      }
      d.flush();
    } catch (Throwable ignored) {
    }
    return new String(arc.util.serialization.Base64Coder.encode(buf.toByteArray()))
        .replace("\n", "").replace("\r", "");
  }

  /** "i3=7,l1=50" → [kind, id, amount, ...]（kind: 0=物品 1=液体）。 */
  static int[] parseCarry(String s) {
    if (s == null || s.isEmpty())
      return new int[0];
    java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
    for (String e : s.split(",")) {
      if (e == null || e.isEmpty())
        continue;
      int kind = e.charAt(0) == 'l' ? 1 : 0;
      int eq = e.indexOf('=');
      if (eq < 0)
        continue;
      try {
        out.add(kind);
        out.add(Integer.parseInt(e.substring(1, eq).trim()));
        out.add(Integer.parseInt(e.substring(eq + 1).trim()));
      } catch (Throwable ignored) {
      }
    }
    int[] arr = new int[out.size()];
    for (int i = 0; i < arr.length; i++)
      arr[i] = out.get(i);
    return arr;
  }

  /** 一台建筑当前装着的货 → 紧凑串（没有就返回 null）。 */
  public static @Nullable String carryOf(Building b) {
    if (b == null)
      return null;
    StringBuilder sb = new StringBuilder();
    try {
      if (b.items != null)
        for (Item it : content.items()) {
          int a = b.items.get(it);
          if (a <= 0)
            continue;
          if (sb.length() > 0)
            sb.append(',');
          sb.append('i').append(it.id).append('=').append(a);
        }
      if (b.liquids != null)
        for (Liquid l : content.liquids()) {
          float a = b.liquids.get(l);
          if (a < 0.5f)
            continue;
          if (sb.length() > 0)
            sb.append(',');
          sb.append('l').append(l.id).append('=').append(Math.round(a));
        }
    } catch (Throwable ignored) {
    }
    return sb.length() == 0 ? null : sb.toString();
  }

  /** 把 carry 串里的货加进这台建筑的池子。 */
  public static void applyCarry(Building target, String carry) {
    if (target == null || carry == null || carry.isEmpty())
      return;
    int[] es = parseCarry(carry);
    for (int i = 0; i + 2 < es.length; i += 3) {
      if (es[i] == 0) {
        Item it = content.item(es[i + 1]);
        if (it != null && target.items != null)
          target.items.add(it, es[i + 2]);
      } else {
        Liquid l = content.liquid(es[i + 1]);
        if (l != null && target.liquids != null)
          target.liquids.add(l, es[i + 2]);
      }
    }
  }

  /** 解码结果：布局 + 来源坐标 + 每个来源的库存。 */
  public static class Decoded {
    public String layout = "";
    public String sources = "";
    public String[] carry = new String[0];
  }

  /** 解出 {layout(逐格文本), sources(逐格文本)}；老的 "layout|sources" 文本串也认。 */
  public static String[] decodeConfig(String cfg) {
    Decoded d = decode(cfg);
    return new String[] { d.layout, d.sources };
  }

  public static Decoded decode(String cfg) {
    Decoded out = new Decoded();
    if (cfg == null || cfg.isEmpty())
      return out;
    if (!cfg.startsWith(CONFIG_TAG)) {
      int bar = cfg.indexOf('|');
      out.layout = bar < 0 ? cfg : cfg.substring(0, bar);
      out.sources = bar < 0 ? "" : cfg.substring(bar + 1);
      return out;
    }
    try {
      byte[] raw = arc.util.serialization.Base64Coder.decode(cfg.substring(CONFIG_TAG.length()).trim());
      java.io.DataInputStream d = new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw));
      int fmt = d.readByte();
      int n = d.readUnsignedShort();
      StringBuilder lay = new StringBuilder();
      int slots = 0;
      for (int i = 0; i < n; i++) {
        int id = d.readUnsignedShort();
        int rot = d.readShort();
        if (i > 0)
          lay.append(';');
        slots++;
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
        boolean wholeBuild = fmt >= 2 && d.readByte() != 0;
        int x = d.readShort(), y = d.readShort();
        if (i > 0)
          src.append(';');
        if (wholeBuild)
          src.append('!');
        src.append(x).append(',').append(y);
      }
      out.layout = lay.toString();
      out.sources = src.toString();
      // 库存段（老串可能没有这一段 → 读到这里就 EOF，按空处理）
      try {
        int slotsWithCarry = d.readUnsignedShort();
        String[] carry = new String[Math.max(slots, 1)];
        for (int i = 0; i < slotsWithCarry; i++) {
          int slot = d.readUnsignedByte();
          int entries = d.readUnsignedShort();
          StringBuilder cb = new StringBuilder();
          for (int k = 0; k < entries; k++) {
            int kind = d.readUnsignedByte();
            int id = d.readUnsignedShort();
            int amt = d.readUnsignedShort();
            if (cb.length() > 0)
              cb.append(',');
            cb.append(kind == 0 ? 'i' : 'l').append(id).append('=').append(amt);
          }
          if (slot >= 0 && slot < carry.length)
            carry[slot] = cb.toString();
        }
        out.carry = carry;
      } catch (Throwable ignored) {
      }
      return out;
    } catch (Throwable t) {
      Log.err("[combine] 组合工厂：配置串解码失败（按空布局处理）", t);
      return out;
    }
  }

  // ==================== 放置判据 ====================

  /**
   * 放置虚影（跟着鼠标的那块）：按布局把**每一格工厂自己的真贴图**画出来（缩到 1 格），
   * 而不是本方块那张占位图标。
   *
   * <p>用户报的"预览是一个多重压缩机的贴图"就是这么来的：以前没覆写这个方法，走了原版默认的
   * "画 {@code fullIcon}"，而本方块是隐藏的虚拟方块、没有专属贴图，兜底图标正好是多向压机。
   */
  @Override
  public void drawPlanRegion(mindustry.entities.units.BuildPlan plan, arc.util.Eachable<mindustry.entities.units.BuildPlan> list) {
    drawGhost(plan, side);
  }

  /** 这份虚影是不是"还举在手上"的预览（就是 {@code input.selectPlans} 里那一个对象）。 */
  static boolean hoverPlan(mindustry.entities.units.BuildPlan plan) {
    try {
      if (control == null || control.input == null)
        return true; // 判不出来就照旧画（宁可预览多一层暗底，也别把预览弄没了）
      for (mindustry.entities.units.BuildPlan p : control.input.selectPlans)
        if (p == plan)
          return true;
      return false;
    } catch (Throwable ignored) {
      return true;
    }
  }

  /** 虚影：整块底板 + 每格工厂自己的贴图（缩到 1 格）。 */
  public static void drawGhost(mindustry.entities.units.BuildPlan plan, int side) {
    if (plan == null || plan.block == null)
      return;
    try {
      float s = side * tilesize;
      // 【别用 plan.drawx()】那是锚点那格的中心；偶数边长时锚点是第一格，直接拿来当底板中心会偏半格。
      float cx = footprintCenter(plan.x, side, tilesize);
      float cy = footprintCenter(plan.y, side, tilesize);
      // 【暗底只给"还举在手上"的预览】原版除了预览，还会拿这同一份 plan 重画**已经下令建造**的计划
      // （排队中的建造计划 / 收工前残留的那份），那几帧的暗底会糊在新放的工厂上 —— 就是用户报的
      // "放置后两秒内盖一层暗滤镜"。判据：这份 plan 还在 {@code input.selectPlans} 里。
      if (hoverPlan(plan)) {
        Draw.color(0f, 0f, 0f, 0.45f);
        Fill.crect(cx - s / 2f, cy - s / 2f, s, s);
      }
      Draw.color(Pal.accent, 0.85f);
      Lines.stroke(0.4f);
      Lines.rect(cx - s / 2f + 0.6f, cy - s / 2f + 0.6f, s - 1.2f, s - 1.2f);
      Draw.reset();
      String[] tokens = cellTokens(decodeConfig(plan.config instanceof String str ? str : null)[0]);
      for (int i = 0; i < tokens.length && i < side * side; i++) {
        Block b = blockOfToken(tokens[i]);
        if (b == null)
          continue;
        int tx = cellTile(plan.x, side, i % side), ty = cellTile(plan.y, side, i / side);
        // 每一格画在**它自己那格的中心**（Mindustry：Tile.worldx() = x*8 就是格心）
        drawCellGhost(b, tx * tilesize, ty * tilesize, rotOfToken(tokens[i]), cellScale(b));
      }
    } catch (Throwable ignored) {
    }
  }

  /**
   * 虚影里的一格工厂：和落地后走**同一条路** —— 优先用这一格自己的 drawer 画出来
   * （这样预览里就能看到部件/发光/液体，和真摆下去的样子对得上），画不出来才退回整套图标。
   *
   * <p>虚影每帧都要重画，所以假 build 按方块名缓存，不能每帧 new（见 {@link #ghostCell}）。
   */
  public static void drawCellGhost(Block b, float cx, float cy, float rot, float k) {
    float zPrev = Draw.z();
    arc.graphics.Color oc = Draw.getColor();
    float cr = oc.r, cg = oc.g, cb2 = oc.b, ca = oc.a;
    try {
      Building cell = ghostCell(b);
      boolean drew = false;
      if (cell != null) {
        cell.set(cx, cy);
        cell.rotation = Mathf.round(rot);
        drew = drawCellDrawer(cell, b, 0, 0f); // 虚影没有池子：液体比例按格子自己的容量算
      }
      if (!drew) {
        TextureRegion reg = firstFound(b);
        if (reg != null)
          drawCellIcon(b, cx, cy, rot, k);
      }
    } catch (Throwable ignored) {
    } finally {
      Draw.z(zPrev);
      Draw.color(cr, cg, cb2, ca);
    }
  }

  /** 虚影用的假格子 build（按方块名缓存；画之前自己改坐标/朝向）。 */
  static final ObjectMap<String, Building> ghostCells = new ObjectMap<>();
  /** 造不出来的方块，别再每帧重试。 */
  static final ObjectSet<String> ghostCellBroken = new ObjectSet<>();

  static @Nullable Building ghostCell(Block cb) {
    if (cb == null || cb.name == null || ghostCellBroken.contains(cb.name))
      return null;
    Building cell = ghostCells.get(cb.name);
    if (cell != null && cell.block == cb)
      return cell;
    try {
      Building c = cb.newBuilding();
      if (c == null) {
        ghostCellBroken.add(cb.name);
        return null;
      }
      Tile fake = new Tile(0, 0);
      setTileBlock(fake, cb);
      c.create(cb, ghostTeam());
      c.tile = fake;
      c.set(0f, 0f);
      c.proximity = new Seq<>();
      fake.build = c; // isValid() 靠这一条（它不在世界网格里）
      c.rotation = 0;
      c.health = c.maxHealth;
      c.enabled = true;
      c.checkAllowUpdate();
      c.created();
      ghostCells.put(cb.name, c);
      return c;
    } catch (Throwable t) {
      ghostCellBroken.add(cb.name);
      return null;
    }
  }

  /** 虚影假 build 用的队伍（拿玩家的，拿不到就随便一个）。 */
  static mindustry.game.Team ghostTeam() {
    try {
      if (player != null && player.team() != null)
        return player.team();
    } catch (Throwable ignored) {
    }
    return mindustry.game.Team.sharded;
  }

  /** 不显示在建造菜单 / 数据库里：入口是 HUD 上的"工厂合体"子按钮。 */
  @Override
  public boolean isVisible() {
    return false;
  }

  /** 但放置流程仍要合法（原版 {@code isPlaceable()} 默认实现里含 {@code isVisible()}）。 */
  @Override
  public boolean isPlaceable() {
    return supportsEnv(state.rules.env) && (!isBanned() || state.rules.editor);
  }

  /**
   * 【必须有】原版 {@code Block.canReplace(other)} 要求"新方块 ≥ 被顶掉的方块大小"，
   * 而被顶掉的工厂可能是 2x2/3x3 —— 不放开的话连 2x2 的硅冶炼厂都顶不掉（实测 validPlace=false，
   * 表现就是"框选完点下去什么也没发生"）。这里只按**方块类型**放行工厂；
   * "这一格到底是不是这次框选的原料"由逐格判据 {@link #canPlaceOn} 兜住。
   *
   * <p>【用户报的"旁边有建筑就放不下去"】原版这条判据还要求"同组 / 同大小"：本台是隐藏的虚拟方块
   * （{@code group == none}），压在**传送带 / 管道 / 容器 / 墙 / 路由器**这类杂项建筑上时
   * {@code canReplace} 一律 false；而预览 footprint 里只要有一格是这种建筑，
   * 整个 {@code Build.validPlaceIgnoreUnits} 就判非法（表现：预览发红、点下去什么也没发生）。
   * 这些格子上的东西本来就要被本台顶掉（它们既不是原料、也不是要保护的对象），
   * 所以照原版"可替换"口径放行；**核心等 replaceable=false 的方块**仍按原版拦住，
   * "不能顶掉没框住的工厂"也仍然只由 {@link #canPlaceOn} 负责。
   */
  @Override
  public boolean canReplace(Block other) {
    if (other == null)
      return false;
    return super.canReplace(other) || isFactoryBlock(other) || (other.replaceable && !other.privileged);
  }

  /** 本次「框选合体」里允许被顶掉的格子（pos）；只在预览生成到落地之间有效。 */
  private static final IntSet replaceAllowedPos = new IntSet();

  public static void allowReplaceOver(IntSet positions) {
    replaceAllowedPos.clear();
    if (positions != null)
      replaceAllowedPos.addAll(positions);
  }

  public static void clearReplaceAllowed() {
    replaceAllowedPos.clear();
  }

  /** 只能顶被框住的工厂（免得顺手把旁边没框住的工厂顶掉）。 */
  @Override
  public boolean canPlaceOn(Tile tile, mindustry.game.Team team, int rotation) {
    if (tile != null && tile.build != null && isFactoryBlock(tile.block())
        && !replaceAllowedPos.contains(tile.pos()))
      return false;
    return super.canPlaceOn(tile, team, rotation);
  }

  /**
   * 预览只要**压住被框住工厂的一部分**就该能放 —— 那台工厂反正立刻会被本台吃掉。
   * 原版还有一条"新方块必须完全包住被顶掉的旧方块"的判据，与"1x1 棋盘格"天然冲突，
   * 所以预览期间把包围盒放大几格，预览结束就恢复（和超级组合炮台同一套做法）。
   */
  @Override
  public arc.math.geom.Rect bounds(int x, int y, arc.math.geom.Rect rect) {
    rect = super.bounds(x, y, rect);
    if (!replaceAllowedPos.isEmpty())
      rect.grow(6f * 8f * 2f);
    return rect;
  }

  /**
   * 玩家把预览"点下去"了（原版蓝图放置会把计划塞进建造队列，见 {@code InputHandler.flushPlans}）：
   * 让框选流程立刻收掉跟着手的虚影。不收的话虚影落地后还会继续重画，那层暗底就糊在新工厂上
   * （用户报的"放置后两秒内盖一层暗滤镜"）。
   */
  @Override
  public void onNewPlan(mindustry.entities.units.BuildPlan plan) {
    FactoryCombiner.markOrdered();
  }

  // ==================== 电网：把各格用电量加总上报 ====================

  public static class FactoryConsumePower extends ConsumePower {
    public FactoryConsumePower() {
      super(0f, 0f, false);
    }

    @Override
    public float requestedPower(Building entity) {
      return entity instanceof SuperCombineFactoryBuild b ? b.cellPowerUse() : 0f;
    }

    @Override
    public float efficiency(Building build) {
      return build == null || build.power == null ? 1f : build.power.status;
    }
  }

  // ==================== 建筑本体 ====================

  public class SuperCombineFactoryBuild extends Building implements IComboGrouped, HeatBlock {
    // ---- 布局与格子 ----
    /** 布局串（';' 分隔 "方块名@朝向"，空格子 = 空串）。 */
    public String layout = "";
    /** 被框选进来的工厂坐标（"x,y"，前面带 '!' = 一整台组合工厂）——落地后要把它们吃掉。 */
    public String sources = "";
    /** 每个来源"当时装着的货"（只在来源被预览顶掉时才用到，见 encodeConfig 的说明）。 */
    public String[] carry = new String[0];
    public boolean sourcesConsumed = false;
    /** 每一格的"虚拟工厂"（挂假 Tile 的真 build，跑原版逻辑）。 */
    public Building[] cells = new Building[0];
    public Block[] cellBlocks = new Block[0];
    public float[] cellRot = new float[0];
    /** 每格一个热量探针（把整台热池喂给这一格）。 */
    public ComboHeatProbe.ComboHeatProbeBuild[] probes = new ComboHeatProbe.ComboHeatProbeBuild[0];
    /** 每个 cells.update() 之后读到的"这一格自己产的热"（制热机才有）。 */
    public float[] cellProduced = new float[0];
    public float[] heatSide = new float[4];
    public float comboTotalHeat = 0f;
    /** 整台对外报的热量 = 里面制热机的产出（合体进来的制热机不该白干）。 */
    public float producedHeat = 0f;
    public boolean cellsReady = false;
    /** 本体的世界邻居变了 → 下一帧重建各格的 proximity（给 offload 用）。 */
    public boolean provDirty = true;
    public boolean placedFullHealth = false;
    public boolean healthFixPending = true;
    /** 这台实际能装多少（按格算，比 block.* 的静态估值准）。 */
    public int realItemCap = 1;
    public float realLiquidCap = 1f;
    /** 建格失败（第三方方块不认这套）的格子，只报一次日志。 */
    public ObjectSet<Integer> cellBroken = new ObjectSet<>();
    // ---- 组合网络约定字段（ComboReflect 按名字反射找） ----
    public SuperCombineFactoryBuild comboLeader;
    public Seq<Building> comboGroup = new Seq<>();
    public boolean comboDirty = true;
    public int comboTotalItemCap;
    public float comboTotalLiquidCap;

    // ==================== 分组（组合网络） ====================

    @Override
    public SuperCombineFactoryBuild leader() {
      if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null))
        comboLeader = null;
      return comboLeader == null ? this : comboLeader;
    }

    public boolean isLeader() {
      return leader() == this;
    }

    @Override
    public Seq<Building> group() {
      SuperCombineFactoryBuild l = leader();
      if (l.comboGroup == null)
        l.comboGroup = new Seq<>();
      return l.comboGroup;
    }

    @Override
    public void markGroupDirty() {
      comboDirty = true;
    }

    /** 邻接 + 穿过组合连接器/节点可达的同队组合工厂算一组。 */
    public void rebuildCombo() {
      Seq<Building> members = new Seq<>();
      members.add(this);
      try {
        for (Building b : ComboReflect.linkedReachable(this,
            o -> o instanceof SuperCombineFactoryBuild st && st.team == team && st.isValid(),
            (cur, o) -> true)) {
          if (b != this && b.isValid())
            members.addUnique(b);
        }
      } catch (Throwable t) {
        Log.err("[combine] 组合工厂分组失败（只算自己）", t);
      }

      SuperCombineFactoryBuild ldr = this;
      for (Building b : members)
        if (b.isValid() && b.pos() < ldr.pos())
          ldr = (SuperCombineFactoryBuild) b;

      int itemCap = 0;
      float liquidCap = 0f;
      for (Building b : members) {
        if (!b.isValid())
          continue;
        itemCap += ComboReflect.baseItemCap(b);
        liquidCap += ComboReflect.baseLiquidCap(b);
      }

      for (Building b : members) {
        SuperCombineFactoryBuild s = (SuperCombineFactoryBuild) b;
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

    /** 本地组合体并池：相邻 / 连线接起来的组合工厂共用一份物品、液体模块。 */
    public void sharePools(SuperCombineFactoryBuild ldr, Seq<Building> members) {
      boolean dedupe = ComboNet.pendingLoadDedupe();

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
      for (Building m : members)
        if (m instanceof SuperCombineFactoryBuild cf)
          cf.syncCellModules();
    }

    public static void moveItems(ItemModule from, ItemModule to) {
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

    public static void moveLiquids(LiquidModule from, LiquidModule to) {
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

    /** 并池之后把每一格的模块引用对齐（不然格子还在往旧池子里放东西）。 */
    public void syncCellModules() {
      for (Building c : cells) {
        if (c == null)
          continue;
        if (c.items != items)
          c.items = items;
        if (c.liquids != liquids)
          c.liquids = liquids;
      }
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
      comboTotalLiquidCap = block.liquidCapacity;
      ensureCells();
    }

    @Override
    public void onProximityUpdate() {
      super.onProximityUpdate();
      comboDirty = true;
      provDirty = true;
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
      for (Building c : cells)
        if (c != null)
          try {
            c.onDestroyed();
          } catch (Throwable ignored) {
          }
      super.onDestroyed();
    }

    // ==================== 面板 / 悬浮信息 ====================

    @Override
    public void display(Table table) {
      super.display(table);
      try {
        table.row();
        // 台数按"真的装了工厂的格子"数（cells 数组按边长² 开，空槽不算）
        table.add("[lightgray]组合工厂: [white]" + filledCount() + " 台[]").left();
        table.row();
        // 【用户 2026-10-06】display 里不再列"所有建筑的名字"和"产出的物品、液体"（太长、太占地方）：
        // 只留台数 + 物品 / 液体 / 电力 / 热量四条池子。
        // 【产物条】用户 2026-10-05 要求：物品 / 液体 / 电力 / 热量四条 bar 都要
        buildPoolBars(table);
      } catch (Throwable ignored) {
      }
    }

    /**
     * 信息面板/点方块面板里的"产物条"：物品池 / 液体池 / 电力 / 热量 —— 四类都要
     * （用户 2026-10-05 要求）。
     *
     * <p>分子分母都用**整台共享池**的口径（{@code realItemCap}/{@code realLiquidCap}）：
     * 每种物品各一条（数量/整台该物品上限）、每种液体各一条，空池也保留"物品/液体"标题写"（空）"，
     * 电力、热量两条恒定画出（没用电/没需热时是 0，但位置在、一眼能看到）。
     */
    void buildPoolBars(Table table) {
      final float w = combine.util.ComboUi.COMPOSITION_WIDTH;
      // 1) 物品池
      table.add("[lightgray]物品[]").left().row();
      boolean anyItem = false;
      if (items != null) {
        for (Item item : content.items()) {
          int amount = items.get(item);
          if (amount <= 0)
            continue;
          anyItem = true;
          final int t = amount, c = Math.max(realItemCap, 1);
          table.add(new mindustry.ui.Bar(
              () -> item.localizedName + ": " + t + "/" + c,
              () -> item.color,
              () -> Mathf.clamp((float) t / c))).width(w).height(18f).pad(4f).left().row();
        }
      }
      if (!anyItem)
        table.add("[gray]（空）[]").left().row();

      // 2) 液体池
      table.add("[lightgray]液体[]").left().row();
      boolean anyLiquid = false;
      if (liquids != null) {
        for (Liquid liquid : content.liquids()) {
          float amount = liquids.get(liquid);
          if (amount <= 0.001f)
            continue;
          anyLiquid = true;
          final float t = amount, c = Math.max(realLiquidCap, 0.001f);
          table.add(new mindustry.ui.Bar(
              () -> liquid.localizedName + ": " + arc.util.Strings.fixed(t, 1) + "/"
                  + arc.util.Strings.fixed(c, 1),
              () -> liquid.barColor != null ? liquid.barColor : liquid.color,
              () -> Mathf.clamp(t / c))).width(w).height(18f).pad(4f).left().row();
        }
      }
      if (!anyLiquid)
        table.add("[gray]（空）[]").left().row();

      // 3) 电力条：整台各格用电之和 × 电网满足率
      final float use = cellPowerUse();
      final float status = power == null ? 0f : power.status;
      table.add(new mindustry.ui.Bar(
          () -> "电力 " + arc.util.Strings.fixed(use * status * 60f, 1) + " / "
              + arc.util.Strings.fixed(use * 60f, 1) + " ⚡/s",
          () -> Pal.power,
          () -> Mathf.clamp(status))).width(w).height(18f).pad(4f).left().row();

      // 4) 热量条：整台热池 / 里面每一台需热合计（只产热不耗热时显示产热）
      final float demand = heatDemand();
      table.add(new mindustry.ui.Bar(
          () -> demand > 0.001f
              ? "热量 " + arc.util.Strings.fixed(comboTotalHeat, 1) + " / "
                  + arc.util.Strings.fixed(demand, 1)
              : "热量 " + arc.util.Strings.fixed(Math.max(comboTotalHeat, producedHeat), 1),
          () -> Pal.lightOrange,
          () -> demand > 0.001f
              ? Mathf.clamp(comboTotalHeat / demand)
              : (Math.max(comboTotalHeat, producedHeat) > 0.001f ? 1f : 0f)))
          .width(w).height(18f).pad(4f).left().row();
    }

    /** 这台组合工厂能产什么（把里面每一格的产物并集去重，物品 + 液体）。 */
    public String outputSummary() {
      StringBuilder sb = new StringBuilder();
      ObjectSet<String> seen = new ObjectSet<>();
      for (Block b : cellBlocks) {
        if (b == null)
          continue;
        try {
          if (b instanceof GenericCrafter gc) {
            if (gc.outputItems != null)
              for (ItemStack st : gc.outputItems)
                if (st != null && st.item != null && seen.add("i" + st.item.id))
                  appendName(sb, st.item.localizedName);
            if (gc.outputLiquids != null)
              for (LiquidStack st : gc.outputLiquids)
                if (st != null && st.liquid != null && seen.add("l" + st.liquid.id))
                  appendName(sb, st.liquid.localizedName);
          }
        } catch (Throwable ignored) {
        }
      }
      return sb.length() == 0 ? "（无）" : sb.toString();
    }

    static void appendName(StringBuilder sb, String name) {
      if (name == null || name.isEmpty())
        return;
      if (sb.length() > 0)
        sb.append("、");
      sb.append(name);
    }

    public String innerSummary() {
        StringBuilder sb = new StringBuilder();
        for (Block b : cellBlocks) {
        if (b == null)
          continue;
        if (sb.length() > 0)
          sb.append("、");
        sb.append(b.localizedName);
      }
      return sb.length() == 0 ? "（空）" : sb.toString();
    }

    /** 真的装了工厂的格子数（空槽不算）。 */
    public int filledCount() {
      int n = 0;
      for (Building c : cells)
        if (c != null)
          n++;
      return n;
    }

    /** 池子里的物品/液体一览（"产物/库存"那一行用）。 */
    public String poolSummary() {
      StringBuilder sb = new StringBuilder();
      try {
        if (items != null)
          for (Item it : content.items()) {
            int amt = items.get(it);
            if (amt <= 0)
              continue;
            if (sb.length() > 0)
              sb.append("、");
            sb.append(it.localizedName).append(" x").append(amt);
          }
        if (liquids != null)
          for (Liquid lq : content.liquids()) {
            float amt = liquids.get(lq);
            if (amt < 0.5f)
              continue;
            if (sb.length() > 0)
              sb.append("、");
            sb.append(lq.localizedName).append(" ").append((int) amt);
          }
      } catch (Throwable ignored) {
      }
      return sb.length() == 0 ? "（空）" : sb.toString();
    }

    @Override
    public void buildConfiguration(Table table) {
      try {
        ensureCells();
        // 【解体】触摸端入口：点方块弹出的就是这张面板。走 config 通道（服务端执行 + 同步）。
        table.table(top -> {
          top.left();
          top.button("[scarlet]解体[]", mindustry.ui.Styles.cleart, () -> {
            try {
              configure(DISSOLVE_TAG);
            } catch (Throwable t) {
              Log.err("[combine] 组合工厂解体请求发送失败", t);
            }
          }).height(40f).width(110f).padRight(6f);
          top.add("[lightgray]把每台工厂放回原处（想重摆时用）").left();
        }).left().padBottom(6f).row();
        table.add("[accent]组合工厂 " + side + "x" + side + "（" + filledCount() + " 台）[]："
            + "物品/液体/电力/热量整台共用").left().row();
        // 【用户 2026-10-06】这块"点方块弹出的悬浮面板"里也不再列"所有建筑的名字"和"产出的物品、液体"
        // （和 display 同一口径：只留台数 + 四条池子）。
        // 【产物条】物品 / 液体 / 电力 / 热量（用户 2026-10-05 要求四类都要）
        buildPoolBars(table);
        table.add("[lightgray]想再往里加工厂：点右上角浮标 → 工厂合体 → 把这台和要加的工厂一起框上").left()
            .width(combine.util.ComboUi.COMPOSITION_WIDTH).wrap();
      } catch (Throwable t) {
        Log.err("[combine] 组合工厂面板构建失败", t);
      }
    }

    // ==================== 每帧 ====================

    public void updateTile() {
      try {
        ensureCells();
      } catch (Throwable t) {
        if (cellBroken.add(-1))
          Log.err("[combine] 组合工厂 ensureCells 每 tick 抛异常（前半段被跳过）", t);
      }
      // 满血初始化：原版 constructFinish 会在 created() 之后用方块静态血量再盖一次
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
          Log.err("[combine] 组合工厂 rebuildCombo 每 tick 抛异常", t);
      }
      syncMaxHealth();
      syncCellModules();
      if (provDirty) {
        provDirty = false;
        rebuildCellProximity();
      }

      // ---- 热量：整台一个池子，按各格需求分摊 ----
      float totalHeat = computeHeatPool();
      float demand = heatDemand();
      for (int i = 0; i < probes.length; i++) {
        ComboHeatProbe.ComboHeatProbeBuild p = probes[i];
        if (p == null)
          continue;
        p.amount = heatShare(i, totalHeat, demand);
        p.team = team;
      }
      comboTotalHeat = totalHeat;

      // ---- 液体超容量裁剪（并池会把几份叠进同一个模块） ----
      try {
        if (liquids != null) {
          for (Liquid liq : content.liquids()) {
            float amt = liquids.get(liq);
            if (amt > realLiquidCap)
              liquids.remove(liq, amt - realLiquidCap);
          }
        }
      } catch (Throwable ignored) {
      }

      // ---- 逐格跑原版逻辑 ----
      float produced = 0f;
      float myTs = timeScale();
      float myDur = timeScaleDuration;
      for (int i = 0; i < cells.length; i++) {
        Building c = cells[i];
        if (c == null || c.dead())
          continue;
        if (c.items != items)
          c.items = items;
        if (c.liquids != liquids)
          c.liquids = liquids;
        if (c.power != null && power != null)
          c.power.status = power.status;
        // 【工作特效】原版 GenericCrafter.updateTile() 里那条 "wasVisible && chance(updateEffectChance)"
        // 是给渲染器每帧标过的；格子不在渲染循环里，wasVisible 永远是 false →
        // updateEffect/烟雾/火花全都不生成（用户报的"没有特效"）。这里手动标成可见。
        c.wasVisible = true;
        // 超频传递：格子不在 indexer 里，OverdriveProjector 只加速本体
        ComboReflect.setFloat(c, "timeScale", myTs);
        ComboReflect.setFloat(c, "timeScaleDuration", myDur);
        // 【容量口径】原版 shouldConsume()/产物满仓判定读的是**这一格方块自己的** itemCapacity
        // / liquidCapacity（每台工厂就那么点）。整台共用一份池子，按单台算的话产物一到
        // "一台工厂的容量"就停摆（用户报的"生产上限只有一个工厂的容量"）。
        // 所以更新这一格之前把方块的容量临时抬到"整台之和"，更新完立刻还原 ——
        // 方块是内容表里的共享实例，不还原会污染其它合并体（和超级炮台临时缩 shootX 同一套做法）。
        Block cb = cellBlocks[i];
        int oldItemCap = cb == null ? 0 : cb.itemCapacity;
        float oldLiquidCap = cb == null ? 0f : cb.liquidCapacity;
        if (cb != null) {
          if (cb.hasItems)
            cb.itemCapacity = Math.max(realItemCap, oldItemCap);
          if (cb.hasLiquids)
            cb.liquidCapacity = Math.max(realLiquidCap, oldLiquidCap);
        }
        try {
          c.update();
        } catch (Throwable t) {
          if (cellBroken.add(i))
            Log.err("[combine] 组合工厂第 @ 格（@）更新出错，已停用这一格", i,
                cellBlocks[i] == null ? "?" : cellBlocks[i].name, t);
          cells[i] = null;
          continue;
        } finally {
          if (cb != null) {
            cb.itemCapacity = oldItemCap;
            cb.liquidCapacity = oldLiquidCap;
          }
        }
        // 需热格子：把分到的这一份直接写进它的热量字段（探针读不到时兜底）
        float req = cellHeatReq(i);
        if (req > 0f && demand > 0f)
          ComboReflect.setFloat(c, "heat", totalHeat * req / demand);
        float ph = cellProducedHeat(c);
        if (i < cellProduced.length)
          cellProduced[i] = ph;
        produced += ph;
      }
      producedHeat = produced;
    }

    /** 各格的用电量合计（上报给电网）。 */
    public float cellPowerUse() {
      float sum = 0f;
      for (Building c : cells) {
        if (c == null || c.block == null || !c.block.hasPower || c.block.consPower == null)
          continue;
        try {
          sum += c.block.consPower.requestedPower(c);
        } catch (Throwable ignored) {
        }
      }
      return sum;
    }

    /** 这一格自己产的热（制热机的 heat）。 */
    float cellProducedHeat(Building c) {
      if (c == null || c.block == null || !(c.block instanceof HeatProducer))
        return 0f;
      Float v = ComboReflect.getFloat(c, "heat");
      return v == null ? 0f : Math.max(0f, v);
    }

    /**
     * 整台的热池：本体贴着的真实热源（{@code calculateHeat}）+ 组合网络分给本台的热
     * （{@code ComboNet.heatFor}）+ 里面制热机自己产的热（格子是虚拟建筑，外面谁都看不见）。
     */
    float computeHeatPool() {
      float total = 0f;
      try {
        total = calculateHeat(heatSide);
      } catch (Throwable ignored) {
      }
      float pool = 0f;
      try {
        pool = ComboNet.heatFor(this);
      } catch (Throwable ignored) {
      }
      try {
        ObjectSet<Building> seen = new ObjectSet<>();
        seen.add(this);
        if (proximity != null)
          for (Building m : proximity)
            if (m != null && m != this && m.isValid())
              seen.add(m);
        Seq<Building> net = ComboNet.componentMembers(this);
        if (net != null)
          for (Building m : net)
            if (m != null && m != this && m.isValid())
              seen.add(m);
        try {
          for (Building m : combine.coop.CoopCombo.coopGroup(this))
            if (m != null && m != this && m.isValid())
              seen.add(m);
        } catch (Throwable ignored) {
        }
        float sum = 0f;
        for (Building m : seen) {
          if (m instanceof HeatBlock hb && !(hb instanceof ComboHeatProbe.ComboHeatProbeBuild))
            sum += Math.max(0f, hb.heat());
          try {
            if (m instanceof combine.production.CombinedGenerator.CombinedGeneratorBuild gb)
              sum = Math.max(sum, gb.getComboHeat());
            else if (m instanceof combine.production.CombinedCrafter.CombinedCrafterBuild cb)
              sum = Math.max(sum, cb.getComboHeat());
          } catch (Throwable ignored) {
          }
        }
        pool = Math.max(pool, sum);
      } catch (Throwable ignored) {
      }
      return Math.max(total, pool) + producedHeat;
    }

    /** 第 i 格工厂需不需要热（heatRequirement > 0）。 */
    public float cellHeatReq(int i) {
      Block b = i >= 0 && i < cellBlocks.length ? cellBlocks[i] : null;
      if (b == null)
        return 0f;
      Float v = ComboReflect.getFloat(b, "heatRequirement");
      return v == null ? 0f : Math.max(0f, v);
    }

    /** 整台需热合计 = 里面每一台需热工厂的 heatRequirement 之和（N 台合体就要 N 份热）。 */
    public float heatDemand() {
      float sum = 0f;
      for (int i = 0; i < cells.length; i++)
        sum += cellHeatReq(i);
      return sum;
    }

    /** 第 i 格从整台热池里分到的热：按需求比例分，每格效率都等于 池/整台需求。 */
    public float heatShare(int i, float pool, float demand) {
      float req = cellHeatReq(i);
      if (req <= 0f || demand <= 0f)
        return pool;
      return pool * req / demand;
    }

    /** 对外报的热量 = 里面制热机产出的那份（合体进来的制热机不该白干）。 */
    @Override
    public float heat() {
      return producedHeat;
    }

    /**
     * 状态图标（原版在方块上方画的那个"缺料/满仓"提示）也要按**里面每一格**算。
     *
     * <p>【用户报】"生产出的物品满了也是 active 而不是 noOutput"：本体自己有 ConsumePower，
     * 原版 {@code Building.status()} 只看本体自己的 shouldConsume()/efficiency，格子满仓它不知道。
     * 这里逐格问一次（问之前把那一格方块的容量临时抬到"整台之和"，口径才和实际生产一致），
     * 汇总规则：有格子在干活 = active；全都卡在满仓 = noOutput；全都缺料 = noInput。
     */
    @Override
    public mindustry.world.meta.BlockStatus status() {
      if (!enabled)
        return mindustry.world.meta.BlockStatus.logicDisable;
      boolean anyActive = false, anyNoOutput = false, anyNoInput = false, anyDisabled = false;
      for (int i = 0; i < cells.length; i++) {
        Building c = cells[i];
        if (c == null)
          continue;
        Block cb = i < cellBlocks.length ? cellBlocks[i] : null;
        int oldItemCap = cb == null ? 0 : cb.itemCapacity;
        float oldLiquidCap = cb == null ? 0f : cb.liquidCapacity;
        if (cb != null) {
          if (cb.hasItems)
            cb.itemCapacity = Math.max(realItemCap, oldItemCap);
          if (cb.hasLiquids)
            cb.liquidCapacity = Math.max(realLiquidCap, oldLiquidCap);
        }
        mindustry.world.meta.BlockStatus st = null;
        try {
          st = c.status();
        } catch (Throwable ignored) {
        } finally {
          if (cb != null) {
            cb.itemCapacity = oldItemCap;
            cb.liquidCapacity = oldLiquidCap;
          }
        }
        if (st == mindustry.world.meta.BlockStatus.active)
          anyActive = true;
        else if (st == mindustry.world.meta.BlockStatus.noOutput)
          anyNoOutput = true;
        else if (st == mindustry.world.meta.BlockStatus.noInput)
          anyNoInput = true;
        else if (st == mindustry.world.meta.BlockStatus.logicDisable)
          anyDisabled = true;
      }
      if (anyActive)
        return mindustry.world.meta.BlockStatus.active;
      if (anyNoOutput)
        return mindustry.world.meta.BlockStatus.noOutput;
      if (anyNoInput)
        return mindustry.world.meta.BlockStatus.noInput;
      if (anyDisabled)
        return mindustry.world.meta.BlockStatus.logicDisable;
      return mindustry.world.meta.BlockStatus.active;
    }

    @Override
    public float heatFrac() {
      return 0f;
    }

    // ==================== 格子构建 / 序列化 ====================

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
      for (Building c : cells)
        if (c != null)
          try {
            c.remove();
          } catch (Throwable ignored) {
          }
      cells = new Building[0];
      cellBlocks = new Block[0];
      cellRot = new float[0];
      probes = new ComboHeatProbe.ComboHeatProbeBuild[0];
      cellProduced = new float[0];
      cellsReady = false;
      cellBroken.clear();
    }

    /** 按 layout 造格子：每一格一个挂假 Tile 的虚拟工厂。 */
    void buildCells() {
      String[] tokens = cellTokens(layout);
      int n = Math.min(Math.max(tokens.length, 0), MAX_FACTORIES);
      Building[] cs = new Building[n];
      Block[] bs = new Block[n];
      float[] rs = new float[n];
      ComboHeatProbe.ComboHeatProbeBuild[] ps = new ComboHeatProbe.ComboHeatProbeBuild[n];
      int itemCap = 0;
      float liquidCap = 0f;
      float healthSum = 0f;

      for (int i = 0; i < n; i++) {
        Block cb = blockOfToken(tokens[i]);
        if (cb == null)
          continue;
        // 【每台占自己那一格】第 i 格落在本方块的 side×side 棋盘上（行优先，先上后左）
        int cx = cellTile(tile.x, side, i % side), cy = cellTile(tile.y, side, i / side);
        Building c = makeCell(cb, rotOfToken(tokens[i]), cx, cy);
        if (c == null)
          continue;
        cs[i] = c;
        bs[i] = cb;
        rs[i] = rotOfToken(tokens[i]);
        ComboHeatProbe.ComboHeatProbeBuild p = makeProbe(cx, cy);
        if (p != null)
          ps[i] = p;
        if (cb.hasItems)
          itemCap += Math.max(cb.itemCapacity, 1);
        if (cb.hasLiquids)
          liquidCap += Math.max(cb.liquidCapacity, 0f);
        healthSum += Math.max(cb.health, 1f);
      }

      cells = cs;
      cellBlocks = bs;
      cellRot = rs;
      probes = ps;
      cellProduced = new float[n];
      realItemCap = Math.max(itemCap, 1);
      realLiquidCap = Math.max(liquidCap, 1f);
      float oldMax = maxHealth;
      maxHealth = Math.max(healthSum, 1f);
      if (placedFullHealth || oldMax <= 0.001f || health <= 0.5f || health >= oldMax - 0.5f
          || health <= block.health + 0.5f)
        health = maxHealth;
      else
        health = Math.min(health, maxHealth);
      placedFullHealth = false;
      cellsReady = true;
      syncCellModules();
      rebuildCellProximity();
    }

    /**
     * 重建每一格的 proximity：**热量探针 + 本体在世界里的邻居**。
     *
     * <p>邻居是给原版 {@code Building.offload()} 用的：工厂产出的东西得能顺着传送带 / 容器 /
     * 卸载器走出去（用户报的"生产的物品不输出"就是缺这一条 —— 格子的 proximity 里只有探针，
     * offload 找不到接收方，产物就一直堆在共享池里）。本体邻居变化不频繁，只在
     * {@link #onProximityUpdate()} 标脏时重建，不每帧 new。
     */
    void rebuildCellProximity() {
      for (int i = 0; i < cells.length; i++) {
        Building c = cells[i];
        if (c == null)
          continue;
        Seq<Building> s = new Seq<>();
        if (i < probes.length && probes[i] != null)
          s.add(probes[i]);
        try {
          if (proximity != null)
            for (Building b : proximity)
              if (b != null && b != this && b.isValid())
                s.addUnique(b);
        } catch (Throwable ignored) {
        }
        c.proximity = s;
      }
    }

    /** 造一个热量探针（假 build：只有 block/坐标/队伍/heat，被 {@code calculateHeat} 当热源读）。 */
    @Nullable
    ComboHeatProbe.ComboHeatProbeBuild makeProbe(int cx, int cy) {
      try {
        ComboHeatProbe hp = ComboHeatProbe.probe;
        if (hp == null)
          return null;
        Building raw = hp.newBuilding();
        if (!(raw instanceof ComboHeatProbe.ComboHeatProbeBuild p))
          return null;
        p.create(hp, team);
        p.tile = new Tile(cx, cy);
        p.set(cx * tilesize, cy * tilesize);
        p.proximity = new Seq<>();
        p.team = team;
        return p;
      } catch (Throwable t) {
        if (cellBroken.add(-5))
          Log.err("[combine] 组合工厂：热量探针创建失败（需热工厂会退回只能贴着的原版行为）", t);
        return null;
      }
    }

    /** 造一格的虚拟工厂（挂假 Tile、共用本体的物品/液体模块）。 */
    @Nullable
    Building makeCell(Block cb, float rot, int cx, int cy) {
      try {
        Building c = cb.newBuilding();
        if (c == null)
          return null;
        Tile fake = new Tile(cx, cy);
        setTileBlock(fake, cb);
        // 【不要用 Tile.changeBuild / c.init()】那会把格子真加进 Groups.build 并触发地块事件；
        // 这里手工装配：create() 建模块 → 挂假 Tile → 摆到这一格的中心 → created()
        c.create(cb, team);
        c.tile = fake;
        // 【格子的中心就是 tile*tilesize】Mindustry 里 Tile.worldx()=x*8 就是这一格的中心
        // （不是左下角），build.x 再靠 Block.offset 落到 footprint 中心。以前按"+4"摆反而偏半格。
        c.set(cx * tilesize, cy * tilesize);
        c.proximity = new Seq<>();
        c.items = items;
        c.liquids = liquids;
        fake.build = c; // isValid() 靠这一条（它不在世界网格里）
        c.rotation = Mathf.round(rot);
        c.health = c.maxHealth;
        c.enabled = true;
        c.checkAllowUpdate();
        c.created();
        return c;
      } catch (Throwable t) {
        if (cellBroken.add(-6))
          Log.err("[combine] 组合工厂：@ 不能作为格子工厂（已留空）", cb.name, t);
        return null;
      }
    }

    /** 本台真正的血量上限 = 里面每台工厂的血量之和。 */
    public float cellHealthSum() {
      float sum = 0f;
      for (Block cb : cellBlocks)
        if (cb != null)
          sum += Math.max(cb.health, 1f);
      return sum;
    }

    void syncMaxHealth() {
      float want = Math.max(cellHealthSum(), 1f);
      if (Math.abs(maxHealth - want) > 0.5f)
        maxHealth = want;
      if (health > maxHealth)
        health = maxHealth;
    }

    @Override
    public void configured(@Nullable Unit builder, @Nullable Object value) {
      if (value instanceof String s) {
        if (DISSOLVE_TAG.equals(s)) {
          dissolveCells();
          super.configured(builder, value);
          return;
        }
        Decoded d = decode(s);
        applyLayout(d.layout);
        sources = d.sources == null ? "" : d.sources;
        carry = d.carry == null ? new String[0] : d.carry;
        if (!sources.isEmpty())
          consumeSources();
      }
      super.configured(builder, value);
    }

    /**
     * 把被框选进来、现在又被装进本台的工厂拆掉（只在服务端/单机做，客户端等服务端同步）。
     *
     * <p>逐格核对：原料必须**真的还在**（同队 + 是同一类工厂）才吃掉；否则这一格清空 ——
     * 于是重复合体 / 蓝图复制只能得到一台空壳。
     */
    public void consumeSources() {
      if (sourcesConsumed || sources == null || sources.isEmpty())
        return;
      sourcesConsumed = true;
      if (net.client())
        return;
      String[] cellsTok = cellTokens(layout);
      String[] srcs = sources.split(";");
      ObjectSet<String> absorbed = new ObjectSet<>(); // 同一台现成组合工厂在 sources 里会出现多次
      boolean changed = false;
      for (int i = 0; i < cellsTok.length; i++) {
        Block want = blockOfToken(cellsTok[i]);
        if (want == null)
          continue;
        String src = i < srcs.length ? srcs[i] : null;
        if (src != null && src.startsWith("!")) {
          if (!absorbed.add(src))
            continue; // 这台已经被并进来了，重复项直接跳过（库存也只搬一次）
          // 【加工厂】原料是"一整台组合工厂"：把它的库存搬进来，然后整台拆掉
          Tile ot = sourceTile(src.substring(1));
          if (ot != null && ot.build instanceof SuperCombineFactoryBuild old && old.team == team
              && !old.dead()) {
            try {
              if (old.items != null && items != null)
                moveItems(old.items, items);
              if (old.liquids != null && liquids != null)
                moveLiquids(old.liquids, liquids);
            } catch (Throwable ignored) {
            }
            try {
              ot.removeNet();
            } catch (Throwable e) {
              Log.err("[combine] 组合工厂并体：拆掉被吸收的那台失败（@,@）", ot.x, ot.y, e);
            }
          } else {
            // 那台现成组合工厂被预览顶掉了：库存只能从 config 里带过来的那份补
            applyCarry(this, i < carry.length ? carry[i] : null);
          }
          continue;
        }
        Tile t = src == null ? null : sourceTile(src);
        if (t != null && (t.build == this || (t.build == null && replacedByMe(t, want)))) {
          // 【原地合体】原版先把原料工厂拆了再应用 config —— 它的库存只能靠 carry 补回来
          applyCarry(this, i < carry.length ? carry[i] : null);
          continue;
        }
        if (t != null && t.build != null && t.build.team == team && !t.build.dead()
            && isFactoryBlock(t.block()) && cellBlock(t.block()) == want) {
          try {
            // 原料工厂里已经攒着的货并进本台（不然连它的库存一起吃掉了）
            if (t.build.items != null && items != null)
              moveItems(t.build.items, items);
            if (t.build.liquids != null && liquids != null)
              moveLiquids(t.build.liquids, liquids);
            t.removeNet();
            continue;
          } catch (Throwable e) {
            Log.err("[combine] 拆掉被框选的工厂失败（@,@）", t.x, t.y, e);
          }
        }
        cellsTok[i] = "";
        changed = true;
      }
      if (changed) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cellsTok.length; i++) {
          if (i > 0)
            sb.append(';');
          sb.append(cellsTok[i]);
        }
        Log.warn("[combine] 组合工厂：有一格的原料工厂已经不在（多半是重复合体/蓝图复制），该格已清空");
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

    /** 这台原料工厂是不是已经被本次放置顶掉了（它的格子里有一格现在归本台）。 */
    boolean replacedByMe(Tile anchor, Block want) {
      int s = want == null ? 1 : Math.max(want.size, 1);
      for (int i = 0; i < s; i++)
        for (int j = 0; j < s; j++) {
          Tile c = world.tile(cellTile(anchor.x, s, i), cellTile(anchor.y, s, j));
          if (c != null && c.build == this)
            return true;
        }
      return false;
    }

    /**
     * 解体：把里面每一台工厂放回世界，然后把本体拆掉。
     *
     * <p>整台的库存交给第一台放回去的工厂 —— 这些工厂本来就在附近，组合规则会把它们重新并成一组，
     * 池子自然回到组里。走原版 {@code Call.constructFinish / Tile.removeNet}，联机时所有人一致。
     */
    public void dissolveCells() {
      if (net.client())
        return;
      String[] tokens = cellTokens(layout);
      if (tokens.length == 0)
        return;
      try {
        ItemModule stashItems = new ItemModule();
        LiquidModule stashLiquids = new LiquidModule();
        moveItems(items, stashItems);
        moveLiquids(liquids, stashLiquids);
        int tx = tile.x, ty = tile.y;
        tile.removeNet(); // 先把整台拆掉（格子空出来），再逐个放回去
        Building first = null;
        int placed = 0;
        for (String token : tokens) {
          Block cb = blockOfToken(token);
          if (cb == null)
            continue;
          // 放回世界时要用注册表里的那一个（组合方块接管了原版名字），否则新的那台没有组合功能
          Block placedBlock = content.block(cb.name);
          if (placedBlock == null)
            placedBlock = cb;
          byte rot = (byte) Mathf.clamp(Mathf.round(rotOfToken(token) / 90f), 0, 3);
          int[] spot = findFreeSpot(placedBlock, tx, ty, rot);
          if (spot == null)
            continue;
          Tile t = world.tile(spot[0], spot[1]);
          if (t == null)
            continue;
          try {
            mindustry.gen.Call.constructFinish(t, placedBlock, null, rot, team, null);
          } catch (Throwable e) {
            Log.err("[combine] 组合工厂解体：放回 @ 失败（@,@）", placedBlock.name, spot[0], spot[1], e);
            continue;
          }
          if (first == null && t.build != null)
            first = t.build;
          placed++;
        }
        if (first != null) {
          moveItems(stashItems, first.items);
          moveLiquids(stashLiquids, first.liquids);
        }
        Log.info("[combine] 组合工厂已解体：放回 @ 台工厂", placed);
      } catch (Throwable t) {
        Log.err("[combine] 组合工厂解体失败", t);
      }
    }

    /** 从 (x,y) 起由近到远找一块放得下的空地，返回放置锚点（放不下返回 null）。 */
    int[] findFreeSpot(Block b, int x, int y, byte rot) {
      int size = Math.max(b.size, 1);
      int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
      for (int r = 0; r <= 14; r++) {
        for (int dy = -r; dy <= r; dy++) {
          for (int dx = -r; dx <= r; dx++) {
            if (r > 0 && Math.max(Math.abs(dx), Math.abs(dy)) != r)
              continue; // 只看这一圈的边框
            if (canPlaceAt(b, ax + dx, ay + dy, rot))
              return new int[] { ax + dx, ay + dy };
          }
        }
      }
      return null;
    }

    boolean canPlaceAt(Block b, int x, int y, byte rot) {
      try {
        return mindustry.world.Build.validPlace(b, team, x, y, rot, false);
      } catch (Throwable t) {
        return false;
      }
    }

    @Override
    public byte version() {
      return 1;
    }

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
      write.str(layout == null ? "" : layout);
      write.str(sources == null ? "" : sources);
    }

    @Override
    public void read(Reads read, byte revision) {
      super.read(read, revision);
      applyLayout(read.str());
      sources = read.str();
      sourcesConsumed = true; // 存档里这些工厂早就拆掉了，读档别再拆一遍
    }

    // ==================== 物品 / 液体 ====================

    @Override
    public int getMaximumAccepted(Item item) {
      return realItemCap;
    }

    @Override
    public boolean acceptItem(Building source, Item item) {
      if (items == null || item == null || items.get(item) >= realItemCap)
        return false;
      // 只有"某一格会吃 / 会产这种物品"才收（免得传送带把没用的东西堆进来堵死）
      for (Block b : cellBlocks) {
        if (b == null)
          continue;
        try {
          if (blockConsumesItem(b, item))
            return true;
          if (b instanceof GenericCrafter gc && gc.outputItems != null)
            for (mindustry.type.ItemStack st : gc.outputItems)
              if (st != null && st.item == item)
                return true;
        } catch (Throwable ignored) {
        }
      }
      return false;
    }

    static boolean blockConsumesItem(Block b, Item item) {
      if (b == null || b.consumers == null)
        return false;
      for (Consume c : b.consumers) {
        if (c instanceof ConsumeItems ci && ci.items != null) {
          for (mindustry.type.ItemStack st : ci.items)
            if (st != null && st.item == item)
              return true;
        }
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

    @Override
    public boolean acceptLiquid(Building source, Liquid liquid) {
      if (liquids == null || liquid == null || liquids.get(liquid) >= realLiquidCap - 0.001f)
        return false;
      for (Building c : cells) {
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
      try {
        ensureCells();
        // 就算还没有格子（放置后、config 应用前的 1~2 帧）也要把底板画出来：
        // 不画的话那几帧整块看不见（用户报的"draw 又没画了"最怕的就是这种）。
        // 底板：整块 side×side 一块深色板（没有专属贴图，自己画）。
        // 中心要用**棋盘**中心（偶数边长时 build.x/y 只是第一格的中心，直接用会偏半格）。
        float s = side * tilesize;
        float pcx = footprintCenter(tile.x, side, tilesize);
        float pcy = footprintCenter(tile.y, side, tilesize);
        Draw.color(0f, 0f, 0f, 0.72f);
        Fill.crect(pcx - s / 2f, pcy - s / 2f, s, s);
        Draw.reset();
        // 每一格画**它自己**的原版 drawer（带进度动画 = 工作特效），并按 size 缩到 1 格以内
        // （2x2 的冶炼厂 → 0.5 倍，3x3 的压机 → 1/3 倍，用户要的"每个工厂变成 1x1"）
        for (int i = 0; i < cells.length; i++) {
          Building c = cells[i];
          if (c == null)
            continue;
          drawCell(c, i < cellBlocks.length ? cellBlocks[i] : null);
        }
        Draw.reset();
      } catch (Throwable ignored) {
      }
    }

    /**
     * 画一格工厂：**走这一格自己的原版 drawer**（和超级组合炮台 {@code drawCellDrawer} 一个思路）。
     *
     * <p>用户报的"合体工厂 drawer 没了"：以前这里只取一张贴图（{@code uiIcon/fullIcon/region}）
     * 按格子中心画 —— 静态图标看着像那台机器，但**抽屉里真正的东西全没了**：旋转件、发光件、
     * 火焰、液体（{@code DrawLiquidTile}/{@code DrawLiquidRegion}）都不画，格子永远是一张静止图。
     *
     * <p>现在的口径：直接调这一格 build 的 {@code draw()}（= 方块自己的 drawer，DrawMulti 里有什么
     * 就画什么），只在外面套一层 {@link Draw#scl} 把整格缩到 1x1；同时把该格方块的
     * {@code size} 临时按 1 算，让那些**按占地格数铺图**的部件（{@code DrawLiquidTile}、
     * {@code DrawWeave}、{@code DrawPower}…）也只画一格、不铺到隔壁格去。
     *
     * <p>{@code Draw.scl} 只缩贴图尺寸、不缩坐标，所以抽屉里那些写死的"格偏移 / 半径"要另外缩：
     * 见 {@link #scaleDrawerOffsets}(否则 2x2 的工厂缩到 1 格后零件全飘在格外，用户报的
     * "贴图没有缩放并且错位")。抽屉整体画不出来（组合方块副本的图没加载上 / 无头）时，
     * 退回画一张整套图标，绝不画半台。
     */
    void drawCell(Building c, Block cb) {
      if (cb == null)
        return;
      float k = cellScale(cb);
      float zPrev = Draw.z();
      arc.graphics.Color oc = Draw.getColor();
      float cr = oc.r, cg = oc.g, cbl = oc.b, ca = oc.a;
      // 【不用 Draw.scl】缩放由 drawCellDrawer 里的"贴图自身 scale + 偏移"做：
      // DrawPistons 这类抽屉会硬写 Draw.xscl/yscl，走 Draw.scl 会被它们改坏（见 scaleDrawer）。
      try {
        if (!drawCellDrawer(c, cb, realItemCap, realLiquidCap))
          drawCellIcon(cb, c.x, c.y, c.rotdeg(), k);
      } catch (Throwable t) {
        // 这一格画崩了绝不能把整块（甚至整帧）带丢：退回一张静态图标
        try {
          drawCellIcon(cb, c.x, c.y, c.rotdeg(), k);
        } catch (Throwable ignored) {
        }
      } finally {
        Draw.z(zPrev);
        // 【不要 Draw.reset()】那会把 z 一起清成 0 —— 下一格的 DrawDefault 就画到 z=0 去了。
        // 只把这一格抽屉改过的颜色还原。
        Draw.color(cr, cg, cbl, ca);
      }
    }

  }

  /** drawer 绘制失败的方块名（免得每帧刷日志）。 */
  static final ObjectSet<String> drawerDrawErr = new ObjectSet<>();

  /** 画一格的整套图标（drawer 画不出来时的兜底），摆在格子中心。缩的口径和抽屉那条路一致。 */
  static void drawCellIcon(Block cb, float x, float y, float rot, float k) {
    TextureRegion icon = firstFound(cb);
    if (icon == null)
      return;
    float old = Draw.scl;
    try {
      Draw.scl = old * k;
      Draw.rect(icon, x, y, rot);
    } finally {
      Draw.scl = old;
    }
  }

  /**
   * 走这一格自己的 drawer 画一格（成功返回 true），和超级组合炮台的 {@code drawCellDrawer} 对应。
   *
   * <p>画之前把这一格方块的 {@code size}/{@code liquidCapacity}/{@code itemCapacity} 临时改成
   * "一格 + 整台池子"的口径，画完（含异常）立刻还原 —— 这些是**共享的方块实例**字段，
   * 不改回世界里的原方块/别的格子就全跟着变了。
   *
   * @param sharedItemCap   整台池子的物品上限（虚影/无池子传 0 = 用格子自己的容量）
   * @param sharedLiquidCap 整台池子的液体上限（同上）
   */
  static boolean drawCellDrawer(Building c, Block cb, int sharedItemCap, float sharedLiquidCap) {
    if (c == null || cb == null || !drawerUsable(cb))
      return false;
    int oldSize = cb.size;
    int oldItemCap = cb.itemCapacity;
    float oldLiquidCap = cb.liquidCapacity;
    float k = cellScale(cb);
    float oldScl = Draw.scl;
    LiquidModule pool = c.liquids;
    LiquidModule view = null;
    try {
      // 本格在棋盘上只占 1 格：按 block.size 铺图的抽屉（DrawLiquidTile 等）也必须只铺 1 格
      if (cb.size != 1)
        cb.size = 1;
      // 液体/物品比例按**整台共享池**算：池子里存的量可能超过单台容量，按单台算会画出格/失真
      if (cb.hasLiquids && sharedLiquidCap > 0.001f)
        cb.liquidCapacity = sharedLiquidCap;
      if (cb.hasItems && sharedItemCap > 0)
        cb.itemCapacity = sharedItemCap;
      // 【把这一格缩到 1 格】见 scaleDrawerOffsets 的说明：贴图尺寸走 Draw.scl（全局精灵缩放，
      // 原版自己就用它做 DrawPlan.animScale），绝对偏移/半径另外乘 k。
      if (k != 1f) {
        Draw.scl = oldScl * k;
        scaleDrawerOffsets(cb, k);
      }
      // 【用户报的"多台不同液体的工厂合体后全画同一种液体"】合并后格子共用整台那一份
      // LiquidModule，`liquids.current()` 是**池子里最多的那种**，于是每个 DrawLiquidRegion /
      // DrawLiquidTile（没写死 drawLiquid 的那些）都去画它 —— 一格水、一格冷却液看着全成了同一种。
      // 画之前把这一格的 liquids 换成"只装它自己那种液体"的视图：量还是从整台池子里取，
      // 但 current() / get() 只反映它自己那一种。画完换回共享池。
      if (pool != null && cb.hasLiquids) {
        view = liquidViewFor(cb, pool);
        c.liquids = view;
      }
      c.draw(); // = 这一格方块的 drawer（DrawMulti 里的部件/液体/发光都在里面）
      return true;
    } catch (Throwable t) {
      if (drawerDrawErr.add(cb.name == null ? "" : cb.name))
        Log.err("[combine] 组合工厂：@ 的 drawer 绘制失败（这一格退回整套图标）", cb.name, t);
      return false;
    } finally {
      if (view != null)
        c.liquids = pool;
      if (k != 1f)
        restoreDrawerOffsets();
      Draw.scl = oldScl;
      cb.size = oldSize;
      cb.itemCapacity = oldItemCap;
      cb.liquidCapacity = oldLiquidCap;
    }
  }

  // ==================== "每格缩到 1 格"：抽屉内部偏移也要跟着缩 ====================

  // ==================== 每格画"它自己那种液体" ====================

  /** 画一格时临时用的"单液体视图"（只装这一格该显示的那种液体，量取整台池子）。 */
  static LiquidModule liquidViewModule;

  static LiquidModule liquidView() {
    if (liquidViewModule == null)
      liquidViewModule = new LiquidModule();
    return liquidViewModule;
  }

  /** 每种格子方块的"候选液体"（它产的 + 它吃的），按方块名缓存。 */
  static final ObjectMap<String, Seq<Liquid>> liquidCandidatesCache = new ObjectMap<>();

  static Seq<Liquid> liquidCandidates(Block cb) {
    Seq<Liquid> cached = cb.name == null ? null : liquidCandidatesCache.get(cb.name);
    if (cached != null)
      return cached;
    Seq<Liquid> out = new Seq<>();
    try {
      if (cb instanceof GenericCrafter gc && gc.outputLiquids != null)
        for (LiquidStack st : gc.outputLiquids)
          if (st != null && st.liquid != null && !out.contains(st.liquid))
            out.add(st.liquid);
      for (Liquid l : content.liquids())
        if (!out.contains(l) && cb.consumesLiquid(l))
          out.add(l);
    } catch (Throwable ignored) {
    }
    if (cb.name != null)
      liquidCandidatesCache.put(cb.name, out);
    return out;
  }

  /** 每种格子方块抽屉里"写死"的液体（可以有好几种，例如冷冻液混合机同时画水+冷却液）。 */
  static final ObjectMap<String, Seq<Liquid>> drawerLiquidsCache = new ObjectMap<>();

  static Seq<Liquid> drawerLiquids(Block cb) {
    Seq<Liquid> cached = cb.name == null ? null : drawerLiquidsCache.get(cb.name);
    if (cached != null)
      return cached;
    Seq<Liquid> out = new Seq<>();
    collectDrawerLiquids(drawerOf(cb), out);
    if (cb.name != null)
      drawerLiquidsCache.put(cb.name, out);
    return out;
  }

  static void collectDrawerLiquids(Object d, Seq<Liquid> out) {
    if (d == null)
      return;
    if (d instanceof DrawMulti dm) {
      if (dm.drawers != null)
        for (DrawBlock c : dm.drawers)
          collectDrawerLiquids(c, out);
      return;
    }
    try {
      java.lang.reflect.Field f = d.getClass().getField("drawLiquid");
      Object v = f.get(d);
      if (v instanceof Liquid l && !out.contains(l))
        out.add(l);
    } catch (Throwable ignored) {
    }
  }

  /** 抽屉里写死的第一种液体（没有就 null）。 */
  static @Nullable Liquid drawerLiquid(Block cb) {
    Seq<Liquid> l = drawerLiquids(cb);
    return l.isEmpty() ? null : l.first();
  }

  /**
   * 准备好"这一格的液体视图"：装这一格抽屉真正会读到的液体（写死的那几种 + 这一格自己该显示的那种），
   * 量都从整台池子里取；{@code current()} 落在这格自己那种上（没写死 drawLiquid 的抽屉就画它）。
   */
  static LiquidModule liquidViewFor(Block cb, LiquidModule pool) {
    Liquid chosen = cellLiquid(cb, pool);
    Liquid first = chosen != null ? chosen : content.liquid(0);
    LiquidModule view = liquidView();
    view.reset(first, pool == null ? 0f : pool.get(first));
    for (Liquid l : drawerLiquids(cb))
      if (l != first)
        view.set(l, pool == null ? 0f : pool.get(l));
    return view;
  }

  /**
   * 这一格该画哪种液体：抽屉里写死的 {@code drawLiquid} 优先；否则取"这台方块产/吃的液体里，
   * 整台池子里存得最多的那种"；**这台方块根本不吃/不产液体时返回 null（那一格就不画液体）** ——
   * 不能退回池子的 {@code current()}，那正好是用户报的"全都画成同一种液体"。
   */
  static @Nullable Liquid cellLiquid(Block cb, LiquidModule pool) {
    Liquid fixed = drawerLiquid(cb);
    if (fixed != null)
      return fixed;
    Seq<Liquid> cand = liquidCandidates(cb);
    Liquid best = cand.isEmpty() ? null : cand.first();
    float bestAmt = -1f;
    for (int i = 0; i < cand.size; i++) {
      Liquid l = cand.get(i);
      float a = pool == null ? 0f : pool.get(l);
      if (a > bestAmt) {
        bestAmt = a;
        best = l;
      }
    }
    return best;
  }

  /**
   * 【用户要求：每个工厂都缩成 1×1】一张 side×side 的工厂缩进 1 格里，要分两条路一起缩：
   *
   * <ol>
   *   <li><b>贴图/部件尺寸 → 全局精灵缩放 {@code Draw.scl}</b>：{@code Draw.rect(region,…)} 画出来的是
   *       {@code region.width * region.scale * Draw.scl * Draw.xscl}，{@link TextureRegion#scl()} 里就乘着
   *       这个静态字段。它**不被任何抽屉影响**（原版 {@code Block.drawPlan} 就是靠
   *       {@code Draw.scl *= plan.animScale} 缩蓝图），所以贴图、部件（RegionPart）、
   *       {@code DrawMulti} 里的每一件都会一起缩。
   *       <p>【为什么不用 {@link Draw#scl(float)}】它改的是 {@code Draw.xscl/yscl}，而
   *       {@code DrawPistons} 会**硬写** {@code Draw.yscl}（先 -1、画完无条件设回 1）—— 于是
   *       "活塞之后"的部件全按 1 倍画（用户报的"sporePress 缩放还是有问题"）。用全局
   *       {@code Draw.scl} 就绕开了它。
   *   <li><b>绝对偏移/半径自己乘 k</b>：{@code Draw.scl} 只缩尺寸、**不缩坐标**，抽屉里那些
   *       "直接加到 {@code build.x/build.y} 上"的偏移（{@code DrawRegion.x/y}、
   *       {@code DrawPistons.*Offset}、{@code DrawFlame.flameX/Y}、{@code DrawSpikes.radius/length}…）
   *       是绝对世界单位，不缩就会把零件甩到格外。{@code Lines}/{@code Fill}（尖刺、线条、火苗圆、
   *       光晕半径）连 {@code Draw.scl} 都不吃，也只能靠这些字段缩。
   * </ol>
   *
   * <p>字段名走白名单（只挑"长度/偏移/半径"语义的），反射读改 + 按类缓存，覆盖原版 + 模组各种
   * DrawBlock 组合，也不用给每个抽屉类写一遍。画完立刻还原（方块/抽屉都是共享实例）。
   */
  static final ObjectSet<String> offsetFields = ObjectSet.with(
      // DrawRegion / DrawWeave / DrawPistons / DrawArcSmelt / DrawCultivator 的居中偏移
      "x", "y",
      // DrawFlame 的火焰/光晕
      "flameX", "flameY", "lightRadius", "lightSinMag",
      "flameRadius", "flameRadiusIn", "flameRadiusMag", "flameRadiusInMag",
      // DrawPistons 的活塞长度/侧移
      // 【只缩"长度"】sinOffset 是正弦的**时间相位**、sideOffset 是**角度**（弧度），
      // 缩它们会让活塞动画错位，不是"贴图没缩放"，别放进来。
      "sinMag", "lenOffset", "horiOffset",
      // DrawCultivator / DrawBubbles
      "radius", "spread", "strokeMin",
      // DrawSpikes：半径 / 尖刺长度 / 线宽（都是世界单位；少了 length/stroke 时相位合成机的
      // 尖刺会按原尺寸戳到格外 —— 用户报的"phase-synthesizer 缩放还是有问题"）
      "length", "stroke",
      // DrawCrucibleFlame / DrawArcSmelt 的粒子
      "flameRad", "circleSpace", "circleStroke",
      "particleRad", "particleSize", "particleLen", "particleStroke",
      // DrawLiquidTile 的内边距（世界单位）
      "padding", "padLeft", "padRight", "padTop", "padBottom",
      // DrawBlockParts / RegionPart 的零件偏移
      "originX", "originY", "moveX", "moveY");

  /** 每个抽屉类里要缩的字段（按类缓存，避免每帧反射扫字段）。 */
  static final ObjectMap<Class<?>, Field[]> offsetFieldsCache = new ObjectMap<>();
  /** 本次绘制被改过的（对象, 字段, 原值）：画完按逆序还原。 */
  static final Seq<Object> sObj = new Seq<>();
  static final Seq<Field> sFld = new Seq<>();
  static final FloatSeq sVal = new FloatSeq();

  /** 这一格工厂的抽屉（分离机不是 GenericCrafter，但有同名字段 {@code drawer}）。 */
  static @Nullable DrawBlock drawerOf(Block cb) {
    try {
      if (cb instanceof GenericCrafter gc)
        return gc.drawer;
      if (cb instanceof Separator sp)
        return sp.drawer;
    } catch (Throwable ignored) {
    }
    return null;
  }

  /** 某个抽屉类里"要缩的 float 字段"（含父类；没有就返回空数组）。 */
  static Field[] offsetFieldsOf(Class<?> c) {
    Field[] cached = offsetFieldsCache.get(c);
    if (cached != null)
      return cached;
    Seq<Field> found = new Seq<>();
    try {
      for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
        for (Field f : k.getDeclaredFields()) {
          if (f.getType() != float.class || Modifier.isStatic(f.getModifiers())
              || !offsetFields.contains(f.getName()))
            continue;
          try {
            f.setAccessible(true);
            found.add(f);
          } catch (Throwable ignored) {
          }
        }
      }
    } catch (Throwable ignored) {
    }
    Field[] out = found.toArray(Field.class);
    offsetFieldsCache.put(c, out);
    return out;
  }

  /** 画这一格之前：抽屉里的绝对偏移/半径临时乘 k（贴图尺寸那半由 {@code Draw.scl} 负责）。 */
  static void scaleDrawerOffsets(Block cb, float k) {
    sObj.clear();
    sFld.clear();
    sVal.clear();
    try {
      collectOffsets(drawerOf(cb), k);
    } catch (Throwable ignored) {
    }
  }

  static void collectOffsets(Object o, float k) {
    if (o == null)
      return;
    if (o instanceof DrawMulti dm) {
      if (dm.drawers != null)
        for (DrawBlock c : dm.drawers)
          collectOffsets(c, k);
      return;
    }
    if (o instanceof DrawBlockParts dp) {
      if (dp.parts != null)
        for (DrawPart p : dp.parts)
          collectOffsets(p, k);
      return;
    }
    // 绝对偏移/半径（Lines / Fill / 直接加在坐标上的那些）
    for (Field f : offsetFieldsOf(o.getClass())) {
      try {
        float v = f.getFloat(o);
        // DrawLiquidTile 的 pad* 用 -1 表示"没设过，走 padding"：只跳这个哨兵，
        // **别**把负数一律跳过 —— DrawPistons.lenOffset 默认就是 -1，它是个长度，要缩。
        if (v < 0f && f.getName().startsWith("pad"))
          continue;
        sObj.add(o);
        sFld.add(f);
        sVal.add(v);
        f.setFloat(o, v * k);
      } catch (Throwable ignored) {
      }
    }
    if (o instanceof RegionPart rp && rp.children != null)
      for (DrawPart p : rp.children)
        collectOffsets(p, k);
  }

  /** 还原 {@link #scaleDrawerOffsets(Block, float)} 改过的字段（逆序，异常也不漏）。 */
  static void restoreDrawerOffsets() {
    for (int i = sObj.size - 1; i >= 0; i--) {
      try {
        sFld.get(i).setFloat(sObj.get(i), sVal.get(i));
      } catch (Throwable ignored) {
      }
    }
    sObj.clear();
    sFld.clear();
    sVal.clear();
  }

  /** 一格的绘制缩放：把这一格工厂的贴图缩到一格以内（2x2 → 0.5，3x3 → 1/3）。 */
  public static float cellScale(Block cb) {
    if (cb == null)
      return 1f;
    // 缩放口径：**按贴图宽度**缩到一格以内（1x1 贴图 = 32px = 1 格 → 1 倍；2x2 = 64px → 0.5；
    // 3x3 = 96px → 1/3）。贴图宽度读不到（图集没加载 / 专用服务端）时退回按占地格数 1/size，
    // 免得 2x2 的机器按原尺寸画出去盖住邻居。
    // 一格 = tilesize 世界单位 = tilesize*4 像素
    float oneTilePx = tilesize * 4f;
    float max = 0f;
    try {
      // 【只看主贴图】不看 uiIcon/fullIcon：有些方块的图标比实际贴图大得多（还有描边/圆角），
      // 拿图标宽度算会把整台缩得只剩一点点（看着像"没画"）。
      if (cb.region != null && cb.region.found())
        max = cb.region.width;
    } catch (Throwable ignored) {
    }
    if (max > 0f)
      return Math.min(1f, oneTilePx / max);
    // 贴图读不到 → 退回按占地格数（2x2 → 0.5），免得按原尺寸画出去盖住邻居
    return 1f / Math.max(cb.size, 1);
  }

  /** 让假 Tile 的 {@code block()} 也是这一格的工厂方块（Tile.block 是 protected，反射设一次）。 */
  static void setTileBlock(Tile fake, Block b) {
    try {
      java.lang.reflect.Field f = Tile.class.getDeclaredField("block");
      f.setAccessible(true);
      f.set(fake, b);
    } catch (Throwable ignored) {
    }
  }

  /** 格子的 drawer 能不能画（按方块名缓存；画不出来的格子一律退回整套图标，绝不画半台）。 */
  static final ObjectMap<String, Boolean> drawerOk = new ObjectMap<>();

  public static boolean drawerUsable(Block cb) {
    if (cb == null || cb.name == null)
      return false;
    Boolean cached = drawerOk.get(cb.name);
    if (cached != null)
      return cached;
    boolean ok = computeDrawerUsable(cb);
    drawerOk.put(cb.name, ok);
    return ok;
  }

  /**
   * drawer 里至少有一张图真的加载上了才算"能画"。
   *
   * <p>组合方块替换出来的副本在某些环境下 drawer 深处是"非 null 但没 load"的贴图，
   * 直接调 {@code draw()} 会整格空白（和超级组合炮台 {@code cellDrawerIncomplete} 同一个坑）。
   */
  static boolean computeDrawerUsable(Block cb) {
    try {
      DrawBlock d = drawerOf(cb);
      if (d == null)
        return false;
      TextureRegion[] icons = d.icons(cb);
      if (icons != null)
        for (TextureRegion r : icons)
          if (r != null && r.found())
            return true;
      // 有些抽屉（例如纯 DrawLiquidTile）不上报图标，但方块自己的 region 在就算能画
      return cb.region != null && cb.region.found();
    } catch (Throwable t) {
      return false;
    }
  }

  /** 贴图兜底：格子工厂没有图集（服务端/无头）时返回 null，调用方判空。 */
  static @Nullable TextureRegion firstFound(Block b) {
    if (b == null)
      return null;
    try {
      // 【整套图标优先】fullIcon 是原版按 drawer 合成好的"整台机器"（含底板/部件/描边），
      // uiIcon 可能只是个小图标（部分模组会给 -ui 变体），region 又可能只是半张图。
      for (TextureRegion r : new TextureRegion[] { b.fullIcon, b.uiIcon, b.region }) {
        if (r != null && r.found())
          return r;
      }
      return Core.atlas == null ? null : Core.atlas.find("error");
    } catch (Throwable t) {
      return null;
    }
  }

  /** 多格方块（原版工厂 1x1/2x2/3x3）的格子在"原点 tile"基础上的偏移。 */
  public static int cellTile(int origin, int size, int index) {
    return origin - (size - 1) / 2 + index;
  }

  /**
   * 棋盘（side×side 格）的世界中心：格子的中心是 {@code tile * tilesize}（Mindustry 的
   * {@code Tile.worldx() = x*8} 就是格心），所以中心 = 首尾两格格心的平均值。
   * 对偶数边长，锚点是"第一格"，这个值 = {@code build.x}（原版靠 {@code Block.offset} 对齐）。
   */
  public static float footprintCenter(int originTile, int side, float tilesize) {
    float lo = cellTile(originTile, side, 0) * tilesize;
    float hi = cellTile(originTile, side, side - 1) * tilesize;
    return (lo + hi) / 2f;
  }

  /** 供测试/调试：这台建筑现在有几格真的装了工厂。 */
  public static int loadedCells(Building b) {
    if (!(b instanceof SuperCombineFactoryBuild sb))
      return 0;
    int n = 0;
    for (Building c : sb.cells)
      if (c != null)
        n++;
    return n;
  }

  /** 供测试：这台合体工厂的实际物品容量上限。 */
  public static int itemCapOf(Building b) {
    return b instanceof SuperCombineFactoryBuild sb ? sb.realItemCap : 0;
  }

  public static float liquidCapOf(Building b) {
    return b instanceof SuperCombineFactoryBuild sb ? sb.realLiquidCap : 0f;
  }
}
