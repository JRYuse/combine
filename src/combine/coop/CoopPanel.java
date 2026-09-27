package combine.coop;
import combine.net.ComboNet;
import combine.util.ComboUi;
import arc.Core;
import arc.scene.actions.Actions;
import arc.scene.event.Touchable;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Stack;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectIntMap;
import arc.struct.Seq;
import arc.util.Align;
import arc.math.Mathf;
import arc.util.Log;
import arc.util.Time;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.world.Block;

import static mindustry.Vars.content;
import static mindustry.Vars.state;
import mindustry.Vars;
import arc.Events;

/**
 * 协作组合的「点击查看」面板 —— 仿原版 {@code BlockInventoryFragment}。
 *
 * 为什么需要它：协作组合接管的那些方块是"我们改不了 display() 方法的 JS/子类方块"，
 * 它们自己的信息面板里插不进"整组共享池"的内容，点开只能看到自己那一份。
 * 原版那个点击方块弹出的小库存面板又只在"没被配置面板吃掉点击"时才出现
 * （多配方工厂这类 configurable 方块点了先弹配方面板），所以这里自己做一个：
 *
 *   点击组合体 → 在方块旁边浮出一个小面板，显示
 *     · 组合构成（每种方块各几台、共几台）
 *     · 整组共享的物品池（物品 / 每种上限）
 *     · 整组共享的液体池（液体 / 每种上限）
 *
 * 只管显示，不做取货（取货走原版容器/装卸器那套，避免和组合体的池子语义打架）。
 * 只在客户端构建（专用服务器 ui 为 null，直接跳过），并且所有绘制都包在 try/catch 里，
 * 面板异常不会把游戏带崩（同 {@link ComboUi} 的思路）。
 */
public class CoopPanel {

  /** 总开关（想关掉点击面板就置 false）。 */
  public static boolean enabled = true;

  /**
   * "点击非自己队伍的建筑时也弹悬浮面板"（设置里可关）。
   *
   * <p>关掉之后只对自己队伍的方块弹面板 —— 玩家看敌人家门口的建筑时，
   * 那块面板老是糊在屏幕上、还会挡住点击。
   */
  public static final String FOREIGN_KEY = "combine-panel-foreign";
  public static boolean showForeign = true;

  public static void loadForeignSetting() {
    try {
      showForeign = Core.settings.getBool(FOREIGN_KEY, true);
    } catch (Throwable ignored) {
    }
  }

  public static void setShowForeign(boolean value) {
    showForeign = value;
    try {
      Core.settings.put(FOREIGN_KEY, value);
    } catch (Throwable ignored) {
    }
  }

  /** 这台建筑该不该弹面板：自己队伍的照旧；别人的看设置。 */
  public static boolean teamAllowed(Building b) {
    if (b == null)
      return false;
    if (showForeign)
      return true;
    try {
      return Vars.player != null && b.team == Vars.player.team();
    } catch (Throwable ignored) {
      return true;
    }
  }

  private static final Table table = new Table();
  /** 面板**内容**那一张表——rebuild 里所有行都加在它上面，再由 ScrollPane 包起来（见 rebuild 收尾）。
   *  注意别叫 content：本文件静态导入了 {@code Vars.content}（内容表），重名会把它遮住。 */
  private static final Table contentRows = new Table();
  /** 内容的滚动容器（内容太长时只滚它，面板本身不再被撑到屏幕外）。 */
  private static arc.scene.ui.ScrollPane scroll;
  private static Building build;
  private static boolean built = false;

  /** 客户端加载完成后调用：把面板挂到 HUD 上。 */
  public static void init() {
    if (!enabled || built || Vars.headless || Vars.ui == null) return;
    try {
      table.name = "coopinventory";
      table.setTransform(true);
      table.visible = false;
      table.touchable = Touchable.disabled;

      // 和原版 BlockInventoryFragment 一样挂到 hudGroup 上（overlaymarker 之前）
      var marker = Core.scene == null ? null : Core.scene.find("overlaymarker");
      if (marker != null) {
        Vars.ui.hudGroup.addChildBefore(marker, table);
      } else {
        Vars.ui.hudGroup.addChild(table);
      }
      built = true;
    } catch (Throwable t) {
      Log.err("[combine] 协作组合面板初始化失败（不影响游戏运行）", t);
    }
  }

  /** 玩家点击了某个格子：是协作组合方块就弹面板，否则收起。 */
  public static void tapped(mindustry.world.Tile tile) {
    if (!enabled || Vars.headless || !built) return;
    try {
      Building b = tile == null ? null : tile.build;
      // 同一次点击可能触发两回 TapEvent（手机端点按、别的模组转发、原版再派发一次…），
      // 而 showFor 对"同一台建筑"是切换语义 —— 于是面板会"闪一下就没了"。
      // 这里做一次去抖：短时间内对同一台的重复点击直接忽略。
      if (!acceptTap(b)) return;
      if (showable(b) && teamAllowed(b)) {
        showFor(b);
      } else {
        hide();
      }
    } catch (Throwable t) {
      Log.err("[combine] 协作组合面板点击处理失败（不影响游戏运行）", t);
    }
  }

  /** 同一次点击里的重复事件窗口（秒）。 */
  public static final float TAP_DEBOUNCE = 0.35f;
  private static Building lastTapBuild;
  private static float lastTapTime = -999f;

  /**
   * 这次点击要不要真的处理：同一台建筑在 {@link #TAP_DEBOUNCE} 秒内的重复点击会被忽略
   * （一次点击触发多回 TapEvent 时，面板就不会"闪一下"）。超过窗口的再次点击 = 真的要收起。
   */
  public static boolean acceptTap(Building b) {
    float now = Time.time;
    boolean same = b != null && b == lastTapBuild;
    if (same && now - lastTapTime < TAP_DEBOUNCE) {
      lastTapTime = now;
      return false;
    }
    lastTapBuild = b;
    lastTapTime = now;
    return true;
  }

  /**
   * 这个方块要不要给面板？
   *
   * <p>两类：
   * <ul>
   *   <li>协作组合接管的 JS/子类方块（改不了它们的 display()，只能靠这个悬浮面板看整组池子）；</li>
   *   <li><b>本模组自己替换出来的组合方块</b>（{@link combine.BlockCloner#comboToOriginal} 里的那些）。
   *       用户的诉求原话："还是搞一个大的悬浮面板，跟 js/java 扩展建筑的一样" ——
   *       组合体一大，原版那个贴在屏幕右侧的信息面板要么顶穿屏幕、要么一改显示方式就出问题，
   *       所以组合方块也走这个**独立悬浮面板**：点一下方块就浮出来、内容实时刷新、
   *       点别的地方才收起（不依赖原版信息面板的重画时机）。</li>
   * </ul>
   */
  public static boolean showable(Building b) {
    if (b == null || !b.isValid() || b.block == null) return false;
    // 超级组合炮台：用户要求"给合体炮台加上悬浮面板看物品和液体数量"。
    // 它不是组合方块（不替换原版），也不走协作组合，所以这里单独放行。
    if (b instanceof combine.turret.SuperTurret.SuperTurretBuild) return b.items != null || b.liquids != null;
    if (!CoopCombo.eligible(b.block) && !isCombined(b.block)) return false;
    return b.items != null || b.liquids != null;
  }

  /** 是不是"本模组替换出来的组合方块"（同 id 同名字接管的那些）。 */
  public static boolean isCombined(Block block) {
    if (block == null) return false;
    try {
      if (combine.BlockCloner.comboToOriginal.containsKey(block)) return true;
      return combine.Replacer.replaced.containsValue(block, true);
    } catch (Throwable ignored) {
      return false;
    }
  }

  public static void showFor(Building t) {
    if (build == t) {
      // 已经在显示同一台：保持不动（"只有点击其他位置才会关闭"），别把面板切掉
      return;
    }
    build = t;
    lastSignature = null;   // 换目标：下一帧立刻重画一次
    side = 0;               // 换目标：上下位置重新判一次（之后保持不动，见 updatePosition）
    itemSeenAt.clear();     // 换目标：迟滞集合也重来，别把上一台的物品带过来
    liquidSeenAt.clear();
    rebuild(true);
  }

  /** 上一次画出来的内容签名（null = 需要立刻重画一次）。 */
  private static String lastSignature;

  /** 面板摆在被点建筑的上面(1)/下面(2)；0 = 还没定（换目标时重判）。 */
  private static int side = 0;

  /**
   * 面板相对屏幕的等比缩放（只在内容超宽/超高时缩小，从不放大）。
   *
   * <p>用户要求："点击组合工厂时他们上面会显示那个材料之类的，加个自适应缩放，不然有一些会很长" ——
   * 超长的方块名 / 一大堆构成 / 多种物品·液体·弹仓会把面板撑得比屏幕还宽（两侧被裁掉、
   * 面板也跟着上下翻面乱跳）。这里量一次面板尺寸，超出屏幕就整体缩小到塞得下。
   */
  private static float fitScale = 1f;

  /**
   * 面板上"刚显示过"的物品/液体 + 最后一次有货的时刻（游戏秒）。
   *
   * <p>用户报："有的物品和液体在 0 和 1 的边缘跳，会导致面板也一直跳"。数量在 0/1 之间抖时，
   * 这一行一会儿有一会儿没有 → 面板高度变 → 位置跟着上下跳。这里给行集合加 3 秒迟滞：
   * 一旦显示过，数量归零后还保留 3 秒才撤掉（面板就不会因 0/1 抖动而"抖"了）。
   */
  private static final arc.struct.ObjectMap<Item, Float> itemSeenAt = new arc.struct.ObjectMap<>();
  private static final arc.struct.ObjectMap<Liquid, Float> liquidSeenAt = new arc.struct.ObjectMap<>();
  private static final float KEEP_EMPTY_ROW_SECONDS = 3f;
  /** 面板这一帧要显示哪些物品行：池里有货的 + 最近 3 秒内有过货的（防 0/1 抖动导致行进出）。 */
  public static Seq<Item> itemRows(mindustry.world.modules.ItemModule pool) {
    Seq<Item> out = new Seq<>();
    float now = Time.time;
    if (pool != null)
      for (Item item : content.items())
        if (pool.get(item) > 0) {
          itemSeenAt.put(item, now);
          out.add(item);
        }
    for (Item item : itemSeenAt.keys()) {
      if (out.contains(item, true)) continue;
      if (now - itemSeenAt.get(item, 0f) <= KEEP_EMPTY_ROW_SECONDS) out.add(item);
    }
    for (var it = itemSeenAt.iterator(); it.hasNext();) {
      var e = it.next();
      if (now - e.value > KEEP_EMPTY_ROW_SECONDS) it.remove();
    }
    return out;
  }

  /** 液体版（阈值 0.001，和别处一致）。 */
  public static Seq<Liquid> liquidRows(mindustry.world.modules.LiquidModule pool) {
    Seq<Liquid> out = new Seq<>();
    float now = Time.time;
    if (pool != null)
      for (Liquid liquid : content.liquids())
        if (pool.get(liquid) > 0.001f) {
          liquidSeenAt.put(liquid, now);
          out.add(liquid);
        }
    for (Liquid liquid : liquidSeenAt.keys()) {
      if (out.contains(liquid, true)) continue;
      if (now - liquidSeenAt.get(liquid, 0f) <= KEEP_EMPTY_ROW_SECONDS) out.add(liquid);
    }
    for (var it = liquidSeenAt.iterator(); it.hasNext();) {
      var e = it.next();
      if (now - e.value > KEEP_EMPTY_ROW_SECONDS) it.remove();
    }
    return out;
  }

  public static void hide() {
    build = null;
    if (table == null) return;
    try {
      table.actions(Actions.scaleTo(0f, 1f, 0.06f), Actions.run(() -> {
        table.clearChildren();
        table.visible = false;
        table.touchable = Touchable.disabled;
      }));
    } catch (Throwable ignored) {
    }
  }

  private static void rebuild(boolean actions) {
    if (build == null || !build.isValid()) {
      hide();
      return;
    }
    Table table = CoopPanel.table;
    // 【内容改成可滚动的那一张表】所有行都加到 content 上，最后由 ScrollPane 包起来
    // —— 用户要求"悬浮面板的大小限制一下，改成可滑动面板"。
    // 实时重画时保留当前滚动位置，免得池子数字一变（几乎每帧都在变）就跳回顶部。
    float keepScroll = scroll == null ? 0f : scroll.getScrollY();
    Table rows = CoopPanel.contentRows;
    rows.clearChildren();
    // 只有"显示动画"那次才清动作：实时重画内容时清掉会把正在跑的缩放动画打断（面板卡在半截大小）
    if (actions) table.clearActions();
    table.background(mindustry.gen.Tex.inventory);
    try {
      rows.margin(4f);
      table = rows; // 下面所有 table.add(...) 都落到内容表上

      Seq<Building> members = members(build);
      // 标题 + 构成
      // 组合方块（本模组替换出来的）叫"组合体"，协作组合接管的那些仍叫"协作组合"
      combine.turret.SuperTurret.SuperTurretBuild st = build instanceof combine.turret.SuperTurret.SuperTurretBuild s ? s : null;
      String kind = st != null ? "超级组合炮台" : (isCombined(build.block) ? "组合体" : "协作组合");
      // 【换行宽度封顶】以前取"逻辑屏幕宽的一半"当换行宽：设备逻辑宽很大时（平板/桌面放大 UI），
      // 这个宽度能到上千，面板照样顶穿屏幕（用户报的"容器的组合体成员显示还是没换行"）。
      // 现在夹在 160..300 之间，任何设备上都只占屏幕的一小条。
      float compWidth = Math.max(160f, Math.min(300f,
          arc.Core.graphics.getWidth() / arc.scene.ui.layout.Scl.scl() * 0.5f));
      // 标题（方块名）也可能很长，一起换行
      table.add("[accent]" + kind + "[] x" + Math.max(members.size, 1) + "  " + build.block.localizedName)
          .left().width(compWidth).wrap().row();
      // 超级炮台再单列一行"里面装了哪些炮台"（每格一台，看池子的时候知道里面是什么）
      if (st != null) {
        String inner = st.innerSummary();
        if (inner.isEmpty()) inner = "（空）";
        table.add("[lightgray]里面: " + inner + "[]").left().width(compWidth).wrap().row();
      }
      ObjectIntMap<Block> counts = new ObjectIntMap<>();
      for (Building m : members) counts.increment(m.block, 1);
      StringBuilder comp = new StringBuilder();
      for (Block b : counts.keys()) {
        if (comp.length() > 0) comp.append("  ");
        comp.append(b.localizedName).append(" x").append(counts.get(b, 0));
      }
      if (comp.length() == 0) comp.append(build.block.localizedName).append(" x1");
      // 构成可能有十几种方块：不换行的话面板会宽到屏幕外（标题被裁掉、还没法看全）。
      table.add("[lightgray]构成: " + comp + "[]").left().width(compWidth).wrap().row();

      // 共享物品池（每行 3 个）
      // 【分母必须和池子同源】池子可能是**整张网络**共用的那一份（组合节点/连接器接起来的），
      // 这时用"这台方块自己的容量"当分母就会出现"1482273/60"这种数字
      //（用户视频现场：组合钻机 x6 的面板显示 铜 1482273/60、硅 5555520/60，还在每帧乱跳）。
      int itemCap = Math.max(combine.net.ComboNet.panelItemCap(build), Math.max(build.block.itemCapacity, 1));
      // 超级炮台：容量就是"里面每一格累加"（用户要求），别用 block 上那个静态估算
      if (st != null) itemCap = Math.max(st.realItemCap, 1);
      // 【池子也要取"网络实际共用的那一份"】并池之后池子可能躺在网络里别的成员手上，
      // 直接读 build.items 会显示"（空）"或在不同模块之间乱跳（视频里 1482273 → 487 → 8）。
      mindustry.world.modules.ItemModule pool = combine.net.ComboNet.panelItemPool(build);
      // 【行集合要稳】池里有货的 + 最近 3 秒有过货的（0/1 抖动不让行进出，见 itemRows）
      Seq<Item> items = itemRows(pool);
      table.add("[lightgray]物品池[]").left().row();
      if (items.isEmpty()) {
        table.add("[gray]（空）[]").left().row();
      } else {
        for (int i = 0; i < items.size; i += 3) {
          Table rowT = new Table();
          for (int k = i; k < Math.min(i + 3, items.size); k++) {
            Item item = items.get(k);
            int amount = pool == null ? 0 : pool.get(item);
            Table cell = new Table();
            cell.add(new Image(item.uiIcon)).size(26f).pad(2f);
            // 【分母就是组上限，别再拿 getMaximumAccepted 兜】核心的 getMaximumAccepted 是
            // "无限"（MindustryX 上实测 1073741823），于是核心面板显示成
            // "2000/1073741823"（用户截图 1.jpg）。真正的每种上限就是 itemCap（页脚那行同源）。
            cell.add(amount + "/" + Math.max(itemCap, 1)).pad(2f);
            rowT.add(cell).left();
          }
          table.add(rowT).left().row();
        }
      }

      // 共享液体池（每行 3 个）
      // 【分母不能用假容量】组合方块的 block.liquidCapacity 被抬成了 9999（防管道算出负流量），
      // 所以这里必须取"真实的单台/整组容量"：协作组合的方块取 CoopCombo 记的那份，
      // 组合方块取 baseLiquidCapacity —— 全在 ComboReflect.baseLiquidCap() 里。
      float liquidCap = Math.max(combine.net.ComboNet.panelLiquidCap(build),
          Math.max(combine.util.ComboReflect.baseLiquidCap(build), 1f));
      if (st != null) liquidCap = Math.max(st.realLiquidCap, 1f);
      mindustry.world.modules.LiquidModule lpool = combine.net.ComboNet.panelLiquidPool(build);
      Seq<Liquid> liquids = liquidRows(lpool);
      table.add("[lightgray]液体池[]").left().row();
      if (liquids.isEmpty()) {
        table.add("[gray]（空）[]").left().row();
      } else {
        for (int i = 0; i < liquids.size; i += 3) {
          Table rowT = new Table();
          for (int k = i; k < Math.min(i + 3, liquids.size); k++) {
            Liquid liquid = liquids.get(k);
            float amount = lpool == null ? 0f : lpool.get(liquid);
            Table cell = new Table();
            cell.add(new Image(liquid.uiIcon)).size(26f).pad(2f);
            cell.add(Math.round(amount) + "/" + (int) liquidCap).pad(2f);
            rowT.add(cell).left();
          }
          table.add(rowT).left().row();
        }
      }

      // 【组合/合体炮台的弹仓】炮塔的弹药装在自己的弹仓里（共享物品池只是中转/仓库那一侧），
      // 于是"纯物品炮台的组合体"的悬浮面板上物品池经常是空的 —— 用户报"悬浮窗不显示物品"。
      // 这里把整组的弹仓单列一段（弹药种类 / 已装 / 上限）。
      try {
        Seq<Item> ammoItems = ammoTypes(build);
        if (ammoItems.size > 0) {
          ObjectIntMap<Item> ammo = ammoCounts(build);
          int per = ammoCap(build);
          table.add("[lightgray]弹仓[]").left().row();
          for (int i = 0; i < ammoItems.size; i += 3) {
            Table rowT = new Table();
            for (int k = i; k < Math.min(i + 3, ammoItems.size); k++) {
              Item item = ammoItems.get(k);
              Table cell = new Table();
              cell.add(new Image(item.uiIcon)).size(26f).pad(2f);
              cell.add(ammo.get(item, 0) + "/" + Math.max(per, 1)).pad(2f);
              rowT.add(cell).left();
            }
            table.add(rowT).left().row();
          }
        }
      } catch (Throwable t) {
        Log.err("[combine] 悬浮面板：弹仓显示失败（跳过）", t);
      }

      // 【热量】整组产热的对外合计 + 需热合计。
      // 超级组合炮台：真正吃热的是格子里那些 afflict 单元，整台共用一个热量池
      // （comboTotalHeat 是 updateTile 每帧从探针/原版 calculateHeat 读回来的），
      // 直接显示这一份；普通组合体把组里每个 HeatBlock 成员的 heat() 加起来
      // （产热机各报各的那份，合计 = 整池），需热量按 block 上的 heatRequirement 反射求和。
      try {
        float heatSum = 0f, reqSum = 0f;
        boolean anyHeat = false;
        if (st != null) {
          heatSum = Math.max(0f, st.comboTotalHeat);
          // 分子 = 整台拿到的热量；分母 = 里面每一台需热炮台需求之和（合体后 N 台就要 N 份，
          // 不再用方块上那个"单台"标记值 —— 用户报的"合体后只需要一个炮台的热量"）。
          float need = st.heatDemand();
          reqSum = need > 0f ? need
              : Math.max(0f, ((combine.turret.SuperTurret) st.block).heatRequirement);
          anyHeat = true;
        } else {
          for (Building m : members) {
            if (m == null || !m.isValid()) continue;
            if (m instanceof mindustry.world.blocks.heat.HeatBlock hb) {
              heatSum += Math.max(0f, hb.heat());
              anyHeat = true;
            }
            Float req = combine.util.ComboReflect.getFloat(m.block, "heatRequirement");
            if (req != null && req > 0f) {
              reqSum += req;
              anyHeat = true;
            }
          }
        }
        // 【自己实际吃到的热】那些"没被本模组替换"的需热方块（mod 炮台等）不是 HeatBlock，
        // 按 HeatBlock.heat() 求和永远是 0 —— 但它们自己的 heatReq（原版热条读的就是它）
        // 就是"这一台实际拿到的热"。取两者较大的那个，面板就不会显示成 0
        //（用户报："afflict 的悬浮面板显示热量是 0"）。
        heatSum = Math.max(heatSum, ownReceivedHeat(build));
        if (anyHeat) {
          String line = (int) heatSum
              + (reqSum > 0.001f ? " / " + (int) reqSum + " ("
                  + Math.min(100, Math.round(heatSum / reqSum * 100f)) + "%)" : "");
          table.add("[lightgray]热量[]").left().row();
          table.add(line).left().row();
        }
      } catch (Throwable t) {
        Log.err("[combine] 悬浮面板：热量显示失败（跳过）", t);
      }

      table.add("[gray]组上限: 每种物品 " + itemCap + " / 每种液体 " + (int) liquidCap + "[]").left();
    } catch (Throwable t) {
      Log.err("[combine] 协作组合面板绘制失败（已跳过）", t);
      return;
    }

    // 【面板尺寸封顶 + 内容可滚动】内容再长也不把面板撑出屏幕（以前靠整块等比缩小，
    // 内容一多就缩到看不清；现在宽度/高度封顶，超出部分在面板里滚）。
    rows.pack();
    if (scroll == null) {
      scroll = new arc.scene.ui.ScrollPane(rows, mindustry.ui.Styles.smallPane);
      scroll.setScrollingDisabled(true, false);   // 只纵向滚，横向不滚
      scroll.setFadeScrollBars(false);
      scroll.setOverscroll(false, false);
    }
    float sceneW = Core.scene == null ? 400f : Core.scene.getWidth();
    float sceneH = Core.scene == null ? 400f : Core.scene.getHeight();
    float capW = Math.max(200f, sceneW * 0.92f);
    float capH = Math.max(160f, sceneH * 0.70f);
    CoopPanel.table.clearChildren();
    CoopPanel.table.background(mindustry.gen.Tex.inventory);
    CoopPanel.table.margin(4f);
    CoopPanel.table.add(scroll)
        .width(Math.min(rows.getWidth() + 14f, capW))
        .height(Math.min(rows.getHeight() + 10f, capH));
    if (keepScroll > 0f) {
      try {
        scroll.setScrollY(Math.min(keepScroll, Math.max(0f, scroll.getMaxY())));
        scroll.updateVisualScroll();
      } catch (Throwable ignored) {
      }
    }
    CoopPanel.table.pack();
    // 【自适应缩放】兜底：万一还是超过屏幕（超小窗口）就整体缩一点
    // 注意这里必须用**外层面板** CoopPanel.table —— 上面 table 这个局部变量已经被
    // 换成了内容表（rows），拿它设 visible/缩放就会出现"面板尺寸算对了、但一直是隐藏的"。
    fitScale = fitScaleFor(CoopPanel.table);
    CoopPanel.table.setOrigin(Align.center);
    updatePosition();
    CoopPanel.table.visible = true;
    CoopPanel.table.touchable = Touchable.enabled;
    if (actions) {
      CoopPanel.table.setScale(0f, fitScale);
      CoopPanel.table.actions(Actions.scaleTo(fitScale, fitScale, 0.06f));
    } else {
      CoopPanel.table.setScale(fitScale, fitScale);
    }

    // 【不自动收起】面板打开后就一直留着，只在"点了其他位置 / 读档 / 目标失效"时关闭。
    // （以前有个"池子空置 30 秒自动收起"，但计时器不清零，重开后会瞬间到期，
    //   表现成"闪一下就没了"；按需求直接去掉这类定时收起。）
    // 同理：实时刷新挂在**外层面板**上（局部 table 现在是内容表）
    CoopPanel.table.update(() -> {
      if (state.isMenu() || build == null || !build.isValid()) {
        lastSignature = null;
        hide();
        return;
      }
      // 【实时刷新】原来面板只在打开时画一次，物品/液体数量就冻在那一刻了。
      // 现在每帧比一次"内容签名"，变了就重画：数量、图标、"（空）"都跟着池子实时变。
      // 这里特意**不**用时间做节流 —— 客户端在某些状态下 Time 会冻住（不在跑逻辑时），
      // 按 Time 节流的话面板就再也不刷新了（实测踩到过）。
      String sig = signature();
      if (lastSignature == null || !lastSignature.equals(sig)) {
        lastSignature = sig;
        rebuild(false);
        return;
      }
      updatePosition();
    });
  }

  /** 面板要显示的内容的"指纹"：成员数 + 有货的物品数量 + 有货的液体数量。 */
  private static String signature() {
    StringBuilder sb = new StringBuilder(64);
    Seq<Building> members = members(build);
    sb.append(members.size).append('|');
    // 【指纹必须和"画出来的那份池子"同源】以前读的是 build.items/liquids（这台方块**自己**
    // 的模块），而面板画的是 ComboNet.panelItemPool/PanelLiquidPool（并池之后可能是整张网络里
    // 别人的那一份）。两者不同源时：池子在别人手里 → 面板数字冻住不刷新；自己那份在动 →
    // 每帧都重画一次（面板看着在抖）。现在直接按画的那两个池子算指纹。
    mindustry.world.modules.ItemModule pool = combine.net.ComboNet.panelItemPool(build);
    // 指纹也用"稳定的行集合"：否则 0/1 抖动时指纹每帧变 → 每帧重画 → 面板跟着抖
    for (Item item : itemRows(pool))
      sb.append(item.id).append(':').append(pool == null ? 0 : pool.get(item)).append(',');
    sb.append('|');
    mindustry.world.modules.LiquidModule lpool = combine.net.ComboNet.panelLiquidPool(build);
    for (Liquid liquid : liquidRows(lpool))
      sb.append(liquid.id).append(':').append(Math.round(lpool == null ? 0f : lpool.get(liquid))).append(',');
    // 热量：变了也要重画面板（整数粒度够看，不会每帧抖动）
    sb.append('|');
    try {
      if (build instanceof combine.turret.SuperTurret.SuperTurretBuild stb) {
        sb.append(Math.round(stb.comboTotalHeat)).append('/').append(Math.round(stb.heatDemand()));
      } else {
        float h = 0f;
        for (Building m : members) {
          if (m instanceof mindustry.world.blocks.heat.HeatBlock hb)
            h += Math.max(0f, hb.heat());
        }
        sb.append(Math.round(h));
      }
    } catch (Throwable ignored) {
    }
    return sb.toString();
  }

  /**
   * 面板位置：**显示在被点击建筑的正上方** —— 水平居中、底边贴着建筑顶边（留 4px 缝）；
   * 上方放不下时翻到建筑正下方。水平方向会夹在屏幕里，免得贴着屏幕边被裁掉。
   *
   * 【不许上下抽搐】上下那一侧**一旦选定就保持不变**（只在这一侧真的放不下时翻面）：
   * 以前每帧都按"当前面板高度"重判一次，而面板高度会随池子里的物品行数变（多一行/少一行），
   * 于是"物品多一行 → 上方塞不下 → 翻到下面；下一帧少一行 → 又翻回上面"，
   * 面板就会在建筑上下之间来回跳（用户报的"组合工厂 ui 面板抽搐"）。
   */
  private static void updatePosition() {
    try {
      if (build == null || Core.input == null || Core.scene == null) return;
      table.pack();

      float[] a = anchor(build);
      arc.math.geom.Vec2 v = Core.input.mouseScreen(a[0], a[1]);
      float sx = v.x - Core.scene.marginLeft;
      float sy = v.y - Core.scene.marginBottom;
      float half = build.block.size * mindustry.Vars.tilesize / 2f;
      arc.math.geom.Vec2 v2 = Core.input.mouseScreen(build.x, build.y - half);
      float belowY = v2.y - Core.scene.marginBottom + 4f;
      float sceneH = Core.scene.getHeight();
      // 用**缩放后**的尺寸判上下（否则缩小了还按原尺寸翻面）
      float h = table.getHeight() * table.scaleY;

      if (side == 0) {
        // 第一次：上方塞得下就放上面（用户要的"浮在方块正上方"），塞不下才翻到下面
        side = (sy - h - 4f < 0f) ? 2 : 1;
      } else if (side == 1 && sy - h - 4f < 0f && belowY + h <= sceneH) {
        // 已经放在上面但上方真的塞不下了（相机移动/长高了）→ 翻到下面
        side = 2;
      } else if (side == 2 && belowY + h > sceneH && sy - h - 4f >= 0f) {
        // 已经放在下面但下方也塞不下 → 翻回上面
        side = 1;
      }

      if (side == 2) {
        placeClamped(sx, belowY, Align.top);
      } else {
        placeClamped(sx, sy - 4f, Align.bottom);
      }
    } catch (Throwable ignored) {
    }
  }

  /** 按对齐方式摆好并夹在屏幕内（尺寸一律按缩放后的算）。 */
  private static void placeClamped(float sx, float sy, int align) {
    float sceneW = Core.scene.getWidth(), sceneH = Core.scene.getHeight();
    float halfW = table.getWidth() * table.scaleX / 2f, h = table.getHeight() * table.scaleY;
    float cx = Mathf.clamp(sx, halfW + 4f, Math.max(halfW + 4f, sceneW - halfW - 4f));
    // align=Align.top：面板在锚点下方；align=Align.bottom：面板在锚点上方。
    float cy = (align & Align.top) != 0 ? sy - h / 2f : sy + h / 2f;
    float minY = h / 2f + 4f, maxY = Math.max(minY, sceneH - h / 2f - 4f);
    cy = Mathf.clamp(cy, minY, maxY);
    table.setPosition(cx, cy, Align.center);
  }

  /** 面板相对屏幕的等比缩放：只在超出可用范围时缩小，从不放大。 */
  static float fitScaleFor(Table t) {
    try {
      if (t == null || Core.scene == null)
        return 1f;
      float availW = Math.max(64f, Core.scene.getWidth() * 0.92f);
      float availH = Math.max(64f, Core.scene.getHeight() * 0.92f);
      float w = Math.max(t.getWidth(), 1f), h = Math.max(t.getHeight(), 1f);
      return Math.min(1f, Math.min(availW / w, availH / h));
    } catch (Throwable ignored) {
      return 1f;
    }
  }

  /**
   * 面板锚点（世界坐标）：建筑**顶边中点**；对齐用 Align.bottom 就是"面板贴在这点的上方"。
   * 独立成方法是为了能被 headless 测试直接验算。
   */
  public static float[] anchor(Building b) {
    if (b == null || b.block == null) return new float[]{0f, 0f, Align.bottom};
    float half = b.block.size * mindustry.Vars.tilesize / 2f;
    return new float[]{b.x, b.y + half, Align.bottom};
  }

  // ==================== 供面板 / 测试共用的数据 ====================

  /**
   * 参与"弹仓"统计的炮台：超级炮台 = 里面每一格；其它（组合炮台）= 整组成员里的炮台。
   */
  public static Seq<Building> turretMembers(Building b) {
    Seq<Building> out = new Seq<>();
    if (b == null) return out;
    if (b instanceof combine.turret.SuperTurret.SuperTurretBuild st) {
      for (Object c : st.cells)
        if (c instanceof Building cb) out.add(cb);
      return out;
    }
    for (Building m : members(b))
      if (m instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild) out.add(m);
    return out;
  }

  /** 整组的弹仓：弹药 → 已装数量（炮塔的弹药存在自己弹仓里，不在共享池）。 */
  public static ObjectIntMap<Item> ammoCounts(Building b) {
    ObjectIntMap<Item> out = new ObjectIntMap<>();
    for (Building m : turretMembers(b)) {
      if (!(m instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild itb) || itb.ammo == null)
        continue;
      for (mindustry.world.blocks.defense.turrets.Turret.AmmoEntry e : itb.ammo) {
        if (!(e instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemEntry ie)) continue;
        if (ie.item == null || ie.amount <= 0) continue;
        out.increment(ie.item, ie.amount);
      }
    }
    return out;
  }

  /** 整组能用的弹药种类（并集，顺序跟着 ammoTypes）。 */
  public static Seq<Item> ammoTypes(Building b) {
    Seq<Item> out = new Seq<>();
    for (Building m : turretMembers(b)) {
      if (!(m.block instanceof mindustry.world.blocks.defense.turrets.ItemTurret it) || it.ammoTypes == null)
        continue;
      for (Item item : it.ammoTypes.keys()) out.addUnique(item);
    }
    return out;
  }

  /** 整组弹仓上限（各台 maxAmmo 之和）。 */
  public static int ammoCap(Building b) {
    int cap = 0;
    for (Building m : turretMembers(b))
      if (m.block instanceof mindustry.world.blocks.defense.turrets.Turret t) cap += Math.max(t.maxAmmo, 1);
    return Math.max(cap, 1);
  }

  /** 这一组的所有成员（同 CoopCombo 的分组规则：同队 + 相邻 + 可组合）。 */
  public static Seq<Building> members(Building start) {
    // 整张网络：协作组合组 + 通过连接器/节点接上的方块（由 ComboNet 统计）。
    // 用 panelMembers（= 网络分量 + 连线接进来的"纯热"建筑）：那些没被本模组替换的 mod
    // 制热机/需热炮台不并池，但用户点开面板时要能看到"连在一起了"以及整组热量。
    Seq<Building> net = ComboNet.panelMembers(start);
    if (net != null && net.size > 0) return net;
    return CoopCombo.coopGroup(start);
  }

  /**
   * 这台建筑**自己实际吃到的热**（面板"热量"那一行的分子兜底）。
   *
   * <p>没被本模组替换的需热方块（mod 炮台、原版 afflict 这类）不是 {@code HeatBlock}，
   * 按"Σ 成员的 heat()"永远算成 0；可它们自己的 {@code heatReq}（原版热条读的就是它）
   * 就是这一台实际拿到的热。热熔炉那类用字段名 {@code heat}。
   */
  private static float ownReceivedHeat(Building b) {
    for (String field : new String[] { "heatReq", "heat" }) {
      try {
        Float v = combine.util.ComboReflect.getFloat(b, field);
        if (v != null && v > 0f) return v;
      } catch (Throwable ignored) {
      }
    }
    return 0f;
  }

  /** 面板文字版摘要（测试用；也是排查时的现成工具）。 */
  public static String describe(Building b) {
    if (b == null || !b.isValid()) return "无效";
    Seq<Building> members = members(b);
    ObjectIntMap<Block> counts = new ObjectIntMap<>();
    for (Building m : members) counts.increment(m.block, 1);
    StringBuilder sb = new StringBuilder();
    combine.turret.SuperTurret.SuperTurretBuild st = b instanceof combine.turret.SuperTurret.SuperTurretBuild s ? s : null;
    sb.append(st != null ? "超级组合炮台" : "协作组合")
        .append(" x").append(Math.max(members.size, 1)).append(' ').append(b.block.localizedName);
    if (st != null) sb.append(" | 里面: ").append(st.innerSummary());
    sb.append(" | 构成: ");
    for (Block bl : counts.keys()) sb.append(bl.localizedName).append('x').append(counts.get(bl, 0)).append(' ');
    sb.append("| 物品: ");
    if (b.items != null) {
      for (Item item : content.items()) {
        int amount = b.items.get(item);
        // 分母同样用"组上限"（核心的 getMaximumAccepted 是无限值，别用它）
        if (amount > 0) sb.append(item.localizedName).append('=').append(amount).append('/')
            .append(Math.max(combine.net.ComboNet.panelItemCap(b), 1)).append(' ');
      }
    }
    // 弹仓（组合/合体炮台看的是这个 —— 弹药存在各自弹仓里）
    try {
      Seq<Item> ammoItems = ammoTypes(b);
      if (ammoItems.size > 0) {
        ObjectIntMap<Item> ammo = ammoCounts(b);
        int per = ammoCap(b);
        sb.append("| 弹仓: ");
        for (Item item : ammoItems)
          sb.append(item.localizedName).append('=').append(ammo.get(item, 0)).append('/').append(per).append(' ');
      }
    } catch (Throwable ignored) {
    }
    sb.append("| 液体: ");
    if (b.liquids != null) {
      for (Liquid liquid : content.liquids()) {
        float amount = b.liquids.get(liquid);
        if (amount > 0.001f) sb.append(liquid.localizedName).append('=').append(Math.round(amount)).append('/').append((int) b.block.liquidCapacity).append(' ');
      }
    }
    return sb.toString();
  }

  /** 注册点击钩子 + 客户端加载时建面板（由 CoopCombo.register 调用）。 */
  public static void register() {
    if (Vars.headless) return;
    Events.on(EventType.ClientLoadEvent.class, e -> init());
    Events.on(EventType.TapEvent.class, e -> tapped(e.tile));
    Events.on(EventType.WorldLoadEvent.class, e -> hide());
  }
}
