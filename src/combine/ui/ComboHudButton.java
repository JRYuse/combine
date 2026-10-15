package combine.ui;

import arc.Core;
import arc.Events;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Scl;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;

import static mindustry.Vars.*;

/**
 * HUD 上的悬浮入口浮标（手机/桌面同一个，可拖动）。
 *
 * <p>【本项目现在只有一个入口】合体建筑（炮台合体 / 工厂合体）已经独立到 `merge` 项目，
 * 这里留下的浮标只负责**「矿物阴影」**（框选矿物地板 → 阴影上放钻头缩到 1x1，见
 * {@link combine.production.OreShadowPlacer}）—— 它是"钻头组合"那一半，留在 combine 里。
 *
 * <p>以前这个浮标展开三个子按钮（炮台合体 / 工厂合体 / 矿物阴影）；现在展开一个。
 * 触屏端没有快捷键，所以"取消框选"的出口是：再点一次浮标（框选进行中时点它 = 取消）。
 *
 * <p>挂载方式沿用原来的做法：直接挂到 {@code Core.scene.root}（在 hudGroup 之上），
 * 不依赖原版建造菜单的内部结构。原因见下面 {@link #ensureButton(boolean)} 的注释。
 */
public class ComboHudButton {
  /** HUD 上的悬浮入口按钮。 */
  static @Nullable Element button;
  /** 点开后出现的子按钮：矿物阴影。 */
  static @Nullable Element productionBtn;
  static boolean subButtonsShown;
  /** 悬浮按钮边长（像素，乘 Scl）。 */
  static final float BUTTON_SIDE = 48f;
  /** 默认落点距屏幕右边缘的留白（像素，乘 Scl）。 */
  static final float BUTTON_EDGE_PAD = 8f;
  /** 默认落点距屏幕顶部的留白（像素，乘 Scl）：贴右上角会盖住小地图，往下让一点落在它正下方。 */
  static final float BUTTON_TOP_PAD = 225f;
  /** 拖动判定阈值（像素，乘 Scl）：位移超过这么多才算"拖动"，否则算"点一下"。 */
  static final float DRAG_THRESHOLD = 6f;
  /** 悬浮按钮位置记在本机设置里的键（按屏幕比例存，换设备/换分辨率不会跑到屏幕外）。 */
  static final String ICON_POS_X = "combine-turret-button-nx";
  static final String ICON_POS_Y = "combine-turret-button-ny";
  static int buttonRetry = 0;

  public static void register() {
    if (headless)
      return;
    Events.run(Trigger.update, ComboHudButton::update);
    Events.on(WorldLoadEvent.class, e -> {
      button = null; // 场景重建了，下一帧补挂
      hideSubButtons();
      buttonRetry = 0;
    });
  }

  /** 主按钮点按：框选进行中 = 取消；否则展开/收起子按钮。 */
  public static void onMainTap() {
    if (combine.production.OreShadowPlacer.selecting()) {
      combine.production.OreShadowPlacer.cancel();
      hideSubButtons();
      return;
    }
    toggleSubButtons();
  }

  public static void toggleSubButtons() {
    if (subButtonsShown) {
      hideSubButtons();
    } else {
      showSubButtons();
    }
  }

  /** 矿物阴影子按钮被点：启动"框矿物地板"流程。 */
  static void onProductionBtn() {
    hideSubButtons();
    combine.production.OreShadowPlacer.start();
  }

  // ==================== 每帧 ====================

  static void update() {
    if (headless || Core.input == null)
      return;
    if (state == null || ui == null)
      return;
    // 悬浮按钮只在游戏里出现（回主菜单就收掉，别糊在菜单上挡点击）
    ensureButton(state.isGame());
    // 子按钮开着时每帧跟着主按钮位置更新布局
    if (subButtonsShown && state.isGame())
      positionSubButtons();
  }

  /**
   * 悬浮在屏幕上的入口按钮（手机/桌面同一个）：一个图标浮标，**点它 = 在旁边展开子按钮**
   * （矿物阴影，见 {@link #showSubButtons}），再点一次收起；按住还能把它拖到任意位置
   * （位置记进本机设置），子按钮会跟着换边。
   *
   * <p>【为什么挂 scene root 而不是 hudGroup】客户端（MindustryX 实测）会在 hudGroup 里再叠一层
   * 铺满屏幕的可点容器 / 方块信息面板，排在 hudGroup 的按钮之上 —— 挂在 hudGroup 里的按钮会
   * "看得见、点不动"（点在按钮中心上命中的是那层 ScrollPane）。挂到 root 上就压过它们；
   * 对话框是后加到 root 的，仍然盖在这个浮标上面（弹窗时照旧被挡住，语义不变）。
   */
  static void ensureButton(boolean show) {
    if (Core.scene == null)
      return;
    if (!show) {
      hideSubButtons(); // 子按钮跟着主按钮一起收掉
      if (button != null) {
        try {
          button.remove();
        } catch (Throwable ignored) {
        }
        button = null;
      }
      return;
    }
    // 防重复：WorldLoadEvent 会把引用清掉，但老浮标可能还挂在场景里 —— 按名字先收掉残留的那份
    if (button == null && Core.scene.root != null) {
      Element stale = Core.scene.root.find("combineHudButton");
      if (stale != null) {
        try {
          stale.remove();
        } catch (Throwable ignored) {
        }
      }
    }
    if (button != null && button.parent != null) {
      // 每帧置顶（原因见上面）+ 收边，别被后加的 HUD 层盖住、也别被拖/缩到屏幕外
      try {
        button.toFront();
      } catch (Throwable ignored) {
      }
      clampButton(button);
      return;
    }
    if (buttonRetry-- > 0)
      return;
    buttonRetry = 30;
    try {
      if (ui == null || Core.scene.root == null)
        return;
      float size = Scl.scl(BUTTON_SIDE);
      ImageButton b = new ImageButton(mindustry.gen.Icon.add, mindustry.ui.Styles.clearTogglei);
      b.name = "combineHudButton";
      b.setSize(size, size);
      b.resizeImage(size * 0.6f);
      b.setOrigin(arc.util.Align.center);
      // 框选进行中就高亮（点第二下 = 取消）
      b.update(() -> b.setChecked(combine.production.OreShadowPlacer.selecting()));
      // 点按 = 展开/收起子按钮；拖动 = 挪浮标（超过阈值才算拖动，见 DragTap）
      b.addListener(new DragTap());
      b.addListener(Tooltip.Tooltips.getInstance().create(
          "组合工厂：点一下展开「矿物阴影」（框选矿物地板），按住可拖动", false));
      Core.scene.root.addChild(b);
      button = b;
      applyButtonPos(b);
      Log.info("[combine] 悬浮浮标已挂到 HUD（可拖动；子按钮：矿物阴影）");
    } catch (Throwable t) {
      Log.err("[combine] 悬浮浮标挂载失败", t);
    }
  }

  /** 浮标落点：上次拖到的位置（按屏幕比例存），没有就默认右上角、小地图正下方。 */
  static void applyButtonPos(Element e) {
    Element p = e.parent;
    float w = p == null ? Core.graphics.getWidth() : p.getWidth();
    float h = p == null ? Core.graphics.getHeight() : p.getHeight();
    float nx = Core.settings == null ? Float.NaN : Core.settings.getFloat(ICON_POS_X, Float.NaN);
    float ny = Core.settings == null ? Float.NaN : Core.settings.getFloat(ICON_POS_Y, Float.NaN);
    if (!Float.isNaN(nx) && !Float.isNaN(ny)) {
      // 存的是屏幕比例（0-1）：换设备 / 换分辨率也不会跑到屏幕外
      e.setPosition(nx * w, ny * h);
    } else {
      // 默认：右上角、小地图正下方
      e.setPosition(w - Scl.scl(BUTTON_EDGE_PAD) - e.getWidth(), h - Scl.scl(BUTTON_TOP_PAD) - e.getHeight());
    }
    clampButton(e);
  }

  /** 把浮标收进屏幕内（拖动 / 分辨率变化时用）。 */
  static void clampButton(Element e) {
    Element p = e.parent;
    if (p == null)
      return;
    e.setPosition(
        Mathf.clamp(e.x, 0f, Math.max(0f, p.getWidth() - e.getWidth())),
        Mathf.clamp(e.y, 0f, Math.max(0f, p.getHeight() - e.getHeight())));
  }

  /** 把浮标位置按屏幕比例记进本机设置（拖动结束时调）。 */
  static void saveButtonPos(Element e) {
    Element p = e.parent;
    if (p == null || Core.settings == null)
      return;
    try {
      Core.settings.putFloat(ICON_POS_X, p.getWidth() > 0f ? e.x / p.getWidth() : 0f);
      Core.settings.putFloat(ICON_POS_Y, p.getHeight() > 0f ? e.y / p.getHeight() : 0f);
    } catch (Throwable ignored) {
    }
  }

  // ==================== 子按钮 ====================

  static void showSubButtons() {
    if (button == null || Core.scene == null || Core.scene.root == null)
      return;
    hideSubButtons();
    float subSize = Scl.scl(40f);

    ImageButton pb = new ImageButton(mindustry.gen.Icon.production, mindustry.ui.Styles.clearTogglei);
    pb.name = "combineSubProductionBtn";
    pb.setSize(subSize, subSize);
    pb.resizeImage(subSize * 0.55f);
    pb.addListener(Tooltip.Tooltips.getInstance().create("框选矿物地板：画一层阴影，阴影上放钻头缩到 1x1", false));
    pb.clicked(ComboHudButton::onProductionBtn);
    Core.scene.root.addChild(pb);
    productionBtn = pb;

    subButtonsShown = true;
    positionSubButtons();
  }

  static void hideSubButtons() {
    if (productionBtn != null) {
      try {
        productionBtn.remove();
      } catch (Throwable ignored) {
      }
      productionBtn = null;
    }
    subButtonsShown = false;
  }

  /**
   * 按主按钮位置把子按钮摆到它旁边：**主按钮在屏幕左半边 → 子按钮在它右边；
   * 在右半边 → 在它左边**，并与主按钮垂直居中。
   */
  static void positionSubButtons() {
    if (button == null || productionBtn == null)
      return;
    Element p = button.parent;
    if (p == null)
      return;
    float mainX = button.x, mainY = button.y;
    float mainW = button.getWidth(), mainH = button.getHeight();
    float subW = productionBtn.getWidth(), subH = productionBtn.getHeight();
    float gap = Scl.scl(4f);
    boolean onLeft = mainX + mainW / 2f < p.getWidth() / 2f;
    float x = onLeft ? mainX + mainW + gap : mainX - subW - gap;
    int n = 1;
    float bottom = mainY + mainH / 2f - (subH * n + gap * (n - 1)) / 2f;
    productionBtn.setPosition(x, bottom);
  }

  /**
   * 浮标上的点按 / 拖动判定：位移超过 {@link #DRAG_THRESHOLD} 才算拖动（挪浮标 + 存位置），
   * 否则算点按（展开/收起子按钮）。用 {@code event.stageX/Y} 的位移而不是元素的局部坐标 ——
   * 元素会跟着手指走，局部坐标每帧都在变，拿来算位移是不对的。
   */
  static class DragTap extends InputListener {
    float downX, downY, startX, startY;
    boolean moved;

    @Override
    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
      if (button != KeyCode.mouseLeft && button != KeyCode.mouseRight && button != KeyCode.mouseMiddle)
        return false;
      downX = event.stageX;
      downY = event.stageY;
      startX = event.listenerActor.x;
      startY = event.listenerActor.y;
      moved = false;
      return true;
    }

    @Override
    public void touchDragged(InputEvent event, float x, float y, int pointer) {
      float dx = event.stageX - downX, dy = event.stageY - downY;
      if (!moved && Math.abs(dx) + Math.abs(dy) < Scl.scl(DRAG_THRESHOLD))
        return;
      moved = true;
      event.listenerActor.setPosition(startX + dx, startY + dy);
      clampButton(event.listenerActor);
    }

    @Override
    public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
      if (!moved) {
        onMainTap();
      } else {
        saveButtonPos(event.listenerActor);
        if (subButtonsShown)
          positionSubButtons();
      }
      moved = false;
    }
  }
}
