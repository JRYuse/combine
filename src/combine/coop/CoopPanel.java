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

  private static final Table table = new Table();
  private static Building build;
  private static boolean built = false;
  private static float emptyTime = 0f;

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
      if (showable(b)) {
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

  /** 这个方块要不要给面板？—— 只给"协作组合接管的、改不了 display 的"方块。 */
  public static boolean showable(Building b) {
    if (b == null || !b.isValid() || b.block == null) return false;
    // 原版方块、combine 自己替换出来的组合方块都不管（后者面板里本来就有整组池子）
    if (!CoopCombo.eligible(b.block)) return false;
    return b.items != null || b.liquids != null;
  }

  public static void showFor(Building t) {
    if (build == t) {
      hide();
      return;
    }
    build = t;
    rebuild(true);
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
    table.clearChildren();
    table.clearActions();
    table.background(mindustry.gen.Tex.inventory);
    try {
      table.margin(4f);

      Seq<Building> members = members(build);
      // 标题 + 构成
      table.add("[accent]协作组合[] x" + Math.max(members.size, 1) + "  " + build.block.localizedName).left().row();
      ObjectIntMap<Block> counts = new ObjectIntMap<>();
      for (Building m : members) counts.increment(m.block, 1);
      StringBuilder comp = new StringBuilder();
      for (Block b : counts.keys()) {
        if (comp.length() > 0) comp.append("  ");
        comp.append(b.localizedName).append(" x").append(counts.get(b, 0));
      }
      if (comp.length() == 0) comp.append(build.block.localizedName).append(" x1");
      table.add("[lightgray]构成: " + comp + "[]").left().row();

      // 共享物品池（每行 3 个）
      int itemCap = Math.max(build.block.itemCapacity, 1);
      Seq<Item> items = new Seq<>();
      if (build.items != null) {
        for (Item item : content.items()) if (build.items.get(item) > 0) items.add(item);
      }
      table.add("[lightgray]物品池[]").left().row();
      if (items.isEmpty()) {
        table.add("[gray]（空）[]").left().row();
      } else {
        for (int i = 0; i < items.size; i += 3) {
          Table rowT = new Table();
          for (int k = i; k < Math.min(i + 3, items.size); k++) {
            Item item = items.get(k);
            int amount = build.items.get(item);
            Table cell = new Table();
            cell.add(new Image(item.uiIcon)).size(26f).pad(2f);
            cell.add(amount + "/" + build.getMaximumAccepted(item)).pad(2f);
            rowT.add(cell).left();
          }
          table.add(rowT).left().row();
        }
      }

      // 共享液体池（每行 3 个）
      float liquidCap = Math.max(build.block.liquidCapacity, 1f);
      Seq<Liquid> liquids = new Seq<>();
      if (build.liquids != null) {
        for (Liquid liquid : content.liquids()) if (build.liquids.get(liquid) > 0.001f) liquids.add(liquid);
      }
      table.add("[lightgray]液体池[]").left().row();
      if (liquids.isEmpty()) {
        table.add("[gray]（空）[]").left().row();
      } else {
        for (int i = 0; i < liquids.size; i += 3) {
          Table rowT = new Table();
          for (int k = i; k < Math.min(i + 3, liquids.size); k++) {
            Liquid liquid = liquids.get(k);
            float amount = build.liquids.get(liquid);
            Table cell = new Table();
            cell.add(new Image(liquid.uiIcon)).size(26f).pad(2f);
            cell.add(Math.round(amount) + "/" + (int) liquidCap).pad(2f);
            rowT.add(cell).left();
          }
          table.add(rowT).left().row();
        }
      }

      table.add("[gray]组上限: 每种物品 " + itemCap + " / 每种液体 " + (int) liquidCap + "[]").left();
    } catch (Throwable t) {
      Log.err("[combine] 协作组合面板绘制失败（已跳过）", t);
      return;
    }

    table.pack();
    updatePosition();
    table.visible = true;
    table.touchable = Touchable.enabled;
    if (actions) {
      table.setScale(0f, 1f);
      table.actions(Actions.scaleTo(1f, 1f, 0.06f));
    }

    table.update(() -> {
      if (state.isMenu() || build == null || !build.isValid()) {
        hide();
        return;
      }
      updatePosition();
      // 池子空了/变了就重画（内容变化时刷新数字）
      if (build.items != null && build.items.total() == 0 && build.liquids != null && build.liquids.currentAmount() <= 0.001f) {
        emptyTime += Time.delta;
        if (emptyTime > 30f) hide();
      } else {
        emptyTime = 0f;
      }
    });
  }

  /**
   * 面板位置：**显示在被点击建筑的正上方** —— 水平居中、底边贴着建筑顶边（留 4px 缝）；
   * 上方放不下时翻到建筑正下方。水平方向会夹在屏幕里，免得贴着屏幕边被裁掉。
   */
  private static void updatePosition() {
    try {
      if (build == null || Core.input == null || Core.scene == null) return;
      table.pack();

      float[] a = anchor(build);
      arc.math.geom.Vec2 v = Core.input.mouseScreen(a[0], a[1]);
      float sx = v.x - Core.scene.marginLeft;
      float sy = v.y - Core.scene.marginBottom;

      if (sy - table.getHeight() - 4f < 0f) {
        // 建筑上方塞不下：翻到正下方（顶边贴着建筑底边）
        float half = build.block.size * mindustry.Vars.tilesize / 2f;
        arc.math.geom.Vec2 v2 = Core.input.mouseScreen(build.x, build.y - half);
        placeClamped(sx, v2.y - Core.scene.marginBottom + 4f, Align.top);
      } else {
        placeClamped(sx, sy - 4f, Align.bottom);
      }
    } catch (Throwable ignored) {
    }
  }

  /** 按对齐方式摆好并夹在屏幕内。 */
  private static void placeClamped(float sx, float sy, int align) {
    float sceneW = Core.scene.getWidth(), sceneH = Core.scene.getHeight();
    float halfW = table.getWidth() / 2f, h = table.getHeight();
    float cx = Mathf.clamp(sx, halfW + 4f, Math.max(halfW + 4f, sceneW - halfW - 4f));
    float cy = (align & Align.top) != 0
        ? Mathf.clamp(sy, 4f, Math.max(4f, sceneH - h - 4f))       // 面板在下方
        : Mathf.clamp(sy, Math.min(h + 4f, sceneH), sceneH - 4f); // 面板在上方
    table.setPosition(cx, cy, align);
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

  /** 这一组的所有成员（同 CoopCombo 的分组规则：同队 + 相邻 + 可组合）。 */
  public static Seq<Building> members(Building start) {
    // 整张网络：协作组合组 + 通过连接器/节点接上的原版组合方块（由 ComboNet 统计）
    Seq<Building> net = ComboNet.componentMembers(start);
    if (net != null && net.size > 0) return net;
    return CoopCombo.coopGroup(start);
  }

  /** 面板文字版摘要（测试用；也是排查时的现成工具）。 */
  public static String describe(Building b) {
    if (b == null || !b.isValid()) return "无效";
    Seq<Building> members = members(b);
    ObjectIntMap<Block> counts = new ObjectIntMap<>();
    for (Building m : members) counts.increment(m.block, 1);
    StringBuilder sb = new StringBuilder();
    sb.append("协作组合 x").append(Math.max(members.size, 1)).append(' ').append(b.block.localizedName);
    sb.append(" | 构成: ");
    for (Block bl : counts.keys()) sb.append(bl.localizedName).append('x').append(counts.get(bl, 0)).append(' ');
    sb.append("| 物品: ");
    if (b.items != null) {
      for (Item item : content.items()) {
        int amount = b.items.get(item);
        if (amount > 0) sb.append(item.localizedName).append('=').append(amount).append('/').append(b.getMaximumAccepted(item)).append(' ');
      }
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
