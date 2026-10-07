package combine.net;

import arc.Core;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.actions.Actions;
import arc.scene.event.Touchable;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;

/**
 * 组合体的「共享哪些部分」悬浮面板（物品 / 液体 / 电力 / 热量）。
 *
 * <p>用户要求："给组合体加一个新悬浮面板，可以选择该组合体是否共享物品，液体，电力，热量，
 * 像组合连接器和组合节点那种，可以自己选，然后不要和显示详细信息的悬浮面板重叠。"
 *
 * <p>所以：点一下**组合体**（本模组替换出来的组合方块）就弹出这张小面板，四个勾选框和
 * 连接件/节点那套完全一致（同一份 {@link ComboShare} 配置，点任意一个成员改的都是同一张网络）。
 * 摆放时优先贴在"详细信息悬浮面板"（{@link combine.coop.CoopPanel}）旁边，
 * 放不下才换边/换到它下面，绝不和它叠在一起。
 */
public final class ComboSharePanel {
  private ComboSharePanel() {
  }

  /** 面板相对屏幕的等比缩放（只在塞不下时缩小，从不放大）。 */
  private static float fitScale = 1f;

  private static final Table table = new Table();
  private static Building build;
  private static boolean built = false;
  /** 面板内容是不是已经按当前目标建好了（换目标时重建一次）。 */
  private static Building contentFor;

  /** 客户端加载完成后调用：把面板挂到 HUD 上。 */
  public static void init() {
    if (built || Vars.headless || Vars.ui == null)
      return;
    try {
      table.name = "combosharepanel";
      table.setTransform(true);
      table.visible = false;
      table.touchable = Touchable.disabled;
      table.background(Tex.inventory);

      var marker = Core.scene == null ? null : Core.scene.find("overlaymarker");
      if (marker != null) {
        Vars.ui.hudGroup.addChildBefore(marker, table);
      } else {
        Vars.ui.hudGroup.addChild(table);
      }
      built = true;
      // 每帧：目标失效就收起，否则重新摆位（详情面板的位置每帧都在变）
      table.update(() -> {
        if (build == null || !build.isValid() || Vars.state.isMenu()) {
          hide();
          return;
        }
        // 设置里关掉 / 队伍没权限（读档换队伍之类）：面板也该收掉
        if (!showable(build)) {
          hide();
          return;
        }
        updatePosition();
      });
    } catch (Throwable t) {
      Log.err("[combine] 组合体共享面板初始化失败（不影响游戏运行）", t);
    }
  }

  /** 这个组合体要不要给共享面板（本模组替换出来的组合方块）。 */
  public static boolean showable(Building b) {
    if (b == null || !b.isValid() || b.block == null)
      return false;
    // 【只对自己队伍】用户报的"怎么点别的队伍的建筑也有"：共享配置是**改我这边的组合体**，
    // 看敌方的机器时不该弹这张面板（设置里也能整个关掉，见 ComboUi.sharePanel()）。
    if (!combine.util.ComboUi.sharePanel())
      return false;
    try {
      if (Vars.player == null || b.team != Vars.player.team())
        return false;
    } catch (Throwable ignored) {
      return false;
    }
    try {
      // 连接件/节点自己有配置面板（点一下弹出来的那个），这里只认"组合体"
      if (b instanceof ComboConnector.ComboConnectorBuild
          || b instanceof ComboNode.ComboNodeBuild)
        return false;
      return combine.coop.CoopPanel.isCombined(b.block);
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** 点击某个格子：是组合体就弹共享面板，否则收起。 */
  public static void tapped(Building b) {
    if (Vars.headless)
      return;
    // 兜底：客户端事件顺序不一定保证 init() 已经跑过（测试驱动直接调 tapped 也走这里）
    if (!built)
      init();
    if (!built)
      return;
    try {
      if (showable(b))
        showFor(b);
      else
        hide();
    } catch (Throwable t) {
      Log.err("[combine] 组合体共享面板点击处理失败（不影响游戏运行）", t);
    }
  }

  public static Building target() {
    return build;
  }

  /** 上一次摆好的矩形 {@code [left, bottom, right, top]}（屏幕坐标）；没显示时 null。 */
  public static float[] rect() {
    if (!built || build == null || !table.visible)
      return null;
    return new float[] { lastCx - lastHalfW, lastCy - lastHalfH, lastCx + lastHalfW, lastCy + lastHalfH };
  }

  /** 面板当前占的矩形（中心 + 半宽/半高）。 */
  private static float lastCx = 0f, lastCy = 0f, lastHalfW = 0f, lastHalfH = 0f;

  public static void showFor(Building b) {
    if (build == b) {
      // 已经在显示同一台：保持不动（只有点别的地方才收起）
      return;
    }
    build = b;
    contentFor = null; // 换目标：下一帧重建内容（勾选状态本来就每帧跟配置同步）
    rebuild(true);
  }

  public static void hide() {
    build = null;
    contentFor = null;
    if (table == null)
      return;
    try {
      // 【已经藏着了就别再排动作】以前每帧都排一次"缩到 0 + 清空"的动画序列，
      // 而这些动作只在元素被 act()（=可见）时才推进 —— 于是面板一显示，
      // 之前攒下的动作立刻把刚画好的内容清掉（表现成"点了没反应/闪一下就没"）。
      if (!table.visible) {
        table.clearChildren();
        return;
      }
      table.actions(Actions.scaleTo(0f, 1f, 0.06f), Actions.run(() -> {
        table.clearChildren();
        table.visible = false;
        table.touchable = Touchable.disabled;
      }));
    } catch (Throwable ignored) {
    }
  }

  private static void rebuild(boolean animate) {
    if (build == null || !build.isValid()) {
      hide();
      return;
    }
    try {
      if (contentFor == build && table.visible && table.getChildren().size > 0) {
        // 内容不变（勾选状态自己每帧同步），只保证显示
        return;
      }
      contentFor = build;
      // 清掉可能还在排队的"隐藏"动作（同 CoopPanel.rebuild 的说明），否则显示动画会被它打断
      table.clearActions();
      table.clearChildren();
      table.margin(4f);
      table.left();

      // 【不要给标签写固定 width + wrap】之前那样 pack 出来的高度和实际绘制对不上，
      // 最后一行会压到勾选框上、还会溢出面板（真客户端截图里能直接看到）。
      // 这里让宽度由整张表自己撑，太宽时整块等比缩小（见 fitScaleFor）。
      table.add("[accent]组合体共享[]").left().row();
      // 口径要说清：物品/液体关掉后**连本地组合体内部也不共用**（每台各留各的）；
      // 电力/热量目前只在网络层生效（两边不并网/不传热）
      table.add("[lightgray]勾上 = 共用；关掉 = 物品/液体每台各留各的[]").left().row();

      // 四个勾选框（和连接件/节点同一个 ComboShare 配置）
      Table toggles = new Table();
      toggles.left();
      for (int i = 0; i < ComboShare.bits.length; i++) {
        final int bit = ComboShare.bits[i];
        var box = toggles.check(ComboShare.label(bit),
            (ComboShare.maskOf(build) & bit) != 0,
            c -> ComboShare.toggle(build, bit)).padRight(8f).get();
        // 配置可能被别的组合体/连接件改动：勾选框每帧跟当前配置对齐
        box.update(() -> box.setChecked((ComboShare.maskOf(build) & bit) != 0));
        if (i % 2 == 1) toggles.row();
      }
      table.add(toggles).left().padTop(2f).row();

      table.label(() -> "[lightgray]当前: []" + ComboShare.describe(ComboShare.maskOf(build)))
          .color(Pal.accent).left().padTop(2f).row();
      table.add("[gray]点任意组合体/连接件 = 改整张网络[]").left().row();

      table.pack();
      fitScale = fitScaleFor(table);
      table.setOrigin(Align.center);
      updatePosition();
      table.visible = true;
      table.touchable = Touchable.enabled;
      if (animate) {
        table.setScale(0f, fitScale);
        table.actions(Actions.scaleTo(fitScale, fitScale, 0.06f));
      } else {
        table.setScale(fitScale, fitScale);
      }
    } catch (Throwable t) {
      Log.err("[combine] 组合体共享面板绘制失败（已跳过）", t);
    }
  }

  /** 相对屏幕的等比缩放：只在超出可用范围时缩小。 */
  private static float fitScaleFor(Table t) {
    try {
      if (t == null || Core.scene == null)
        return 1f;
      float availW = Math.max(64f, Core.scene.getWidth() * 0.9f);
      float availH = Math.max(64f, Core.scene.getHeight() * 0.9f);
      float w = Math.max(t.getWidth(), 1f), h = Math.max(t.getHeight(), 1f);
      return Math.min(1f, Math.min(availW / w, availH / h));
    } catch (Throwable ignored) {
      return 1f;
    }
  }

  /**
   * 摆位：优先贴在"详细信息面板"旁边（右、左），都不行就放到它下面；
   * 详情面板没显示时，自己按"方块正上方（放不下就下方）"摆，和详情面板同一套锚点规则。
   */
  private static void updatePosition() {
    try {
      if (build == null || Core.input == null || Core.scene == null)
        return;
      table.pack();
      float sceneW = Core.scene.getWidth(), sceneH = Core.scene.getHeight();
      float w = table.getWidth() * table.scaleX, h = table.getHeight() * table.scaleY;
      float gap = 6f;
      float cx, cy;

      float[] r = combine.coop.CoopPanel.rect();
      if (r != null && r[2] > r[0]) {
        float cyMid = (r[1] + r[3]) / 2f;
        float right = r[2] + gap + w / 2f;
        float left = r[0] - gap - w / 2f;
        if (right + w / 2f <= sceneW - 4f) {
          cx = right;
          cy = cyMid;
        } else if (left - w / 2f >= 4f) {
          cx = left;
          cy = cyMid;
        } else {
          // 两侧都塞不下：放到详情面板正下方（两面板仍不重叠）
          cx = (r[0] + r[2]) / 2f;
          cy = r[1] - gap - h / 2f;
        }
      } else {
        // 详情面板没开：自己贴方块（上边放不下翻下边），和 CoopPanel.updatePosition 同规则
        float half = build.block.size * Vars.tilesize / 2f;
        Vec2 top = Core.input.mouseScreen(build.x, build.y + half);
        float sx = top.x - Core.scene.marginLeft;
        float syTop = top.y - Core.scene.marginBottom;
        cx = sx;
        cy = syTop + 4f + h / 2f;
        if (cy + h / 2f > sceneH - 4f) {
          Vec2 bottom = Core.input.mouseScreen(build.x, build.y - half);
          cy = (bottom.y - Core.scene.marginBottom) - 4f - h / 2f;
        }
      }

      float minX = w / 2f + 4f, maxX = Math.max(minX, sceneW - w / 2f - 4f);
      float minY = h / 2f + 4f, maxY = Math.max(minY, sceneH - h / 2f - 4f);
      cx = Mathf.clamp(cx, minX, maxX);
      cy = Mathf.clamp(cy, minY, maxY);
      table.setPosition(cx, cy, Align.center);
      lastCx = cx;
      lastCy = cy;
      lastHalfW = w / 2f;
      lastHalfH = h / 2f;
    } catch (Throwable ignored) {
    }
  }
}
