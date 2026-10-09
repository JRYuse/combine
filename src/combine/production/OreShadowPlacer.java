package combine.production;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.input.InputProcessor;
import arc.input.KeyCode;
import arc.scene.ui.layout.Scl;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.core.World;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.input.Binding;
import mindustry.input.DesktopInput;
import mindustry.input.PlaceMode;
import mindustry.ui.Fonts;
import mindustry.world.Tile;

import static mindustry.Vars.*;

/**
 * 「矿物阴影」的框选流程（和 {@code FactoryCombiner} / {@code SuperTurretPlacer} 平行，纯客户端）。
 *
 * <p>入口：HUD 浮标 → 展开的子按钮里的 {@code Icon.production} 那一个。
 * 拖框 → 松开就把框里面的**矿物地板**（{@code tile.drop() != null}）逐格取反，
 * 记进 {@link OreShadow}；阴影由 {@link OreShadow} 自己每帧画在地上。
 */
public class OreShadowPlacer {
  static boolean selecting = false;
  static boolean dragging = false;
  static int sx = -1, sy = -1, ex = -1, ey = -1;
  /** 正在拖这个框的手指（多点触控时只认起手的那一根）。 */
  static int boxPointer = -1;
  static final InputProcessor processor = new Processor();
  /** 每次松开框选后的一行摘要（画在框旁边，让玩家立刻看到 +/- 了多少格）。 */
  static @Nullable String lastResult;

  public static boolean selecting() {
    return selecting;
  }

  public static void register() {
    if (headless)
      return;
    Events.run(Trigger.update, OreShadowPlacer::update);
    Events.run(Trigger.draw, OreShadowPlacer::drawOverlay);
    Events.on(WorldLoadEvent.class, e -> {
        selecting = false;
      dragging = false;
      sx = sy = ex = ey = -1;
      lastResult = null;
    });
  }

  public static void start() {
    if (headless || state == null || !state.isGame() || player == null || !player.isBuilder())
      return;
    // 三个框选流程互斥（两个处理器会互相抢事件）
    try {
      if (combine.turret.SuperTurretPlacer.selecting())
        combine.turret.SuperTurretPlacer.cancel();
      if (FactoryCombiner.selecting())
        FactoryCombiner.cancel();
    } catch (Throwable ignored) {
    }
    selecting = true;
    dragging = false;
    sx = sy = ex = ey = -1;
    boxPointer = -1;
    try {
      control.input.block = null;
      if (control.input instanceof DesktopInput di)
        di.mode = PlaceMode.none;
    } catch (Throwable ignored) {
    }
    ensureProcessor();
    ui.showInfoFade("[accent]矿物阴影[]：拖动左键框选矿物地板（只记有矿的格子；同一片再框一次 = 去掉阴影。右键/Esc 取消）");
  }

  public static void cancel() {
    if (selecting)
      ui.showInfoFade("[lightgray]已取消矿物阴影框选");
    selecting = false;
    dragging = false;
    boxPointer = -1;
    sx = sy = ex = ey = -1;
  }

  static void toTiles(int screenX, int screenY) {
    var w = Core.input.mouseWorld(screenX, screenY);
    ex = World.toTile(w.x);
    ey = World.toTile(w.y);
  }

  /** 框选完成：把框里的矿物地板逐格取反。 */
  static void finish() {
    int x1 = Math.min(sx, ex), y1 = Math.min(sy, ey), x2 = Math.max(sx, ex), y2 = Math.max(sy, ey);
    int[] r = OreShadow.toggle(x1, y1, x2, y2);
    if (r[0] == 0 && r[1] == 0) {
      lastResult = "这个框里没有矿物地板";
      ui.showInfoFade("[lightgray]框里没有矿物地板（阴影只画在矿上），现在共 @ 格", OreShadow.size());
    } else {
      lastResult = "+" + r[0] + " / -" + r[1] + " 格";
      ui.showInfoFade("[accent]矿物阴影[] +" + r[0] + " 格、-" + r[1] + " 格，现在共 "
          + OreShadow.size() + " 格（阴影上放钻头会缩到 1x1）");
    }
    selecting = false;
    dragging = false;
    boxPointer = -1;
    sx = sy = ex = ey = -1;
  }

  // ==================== 每帧 ====================

  static void update() {
    if (headless || Core.input == null)
      return;
    ensureProcessor();
    if (state == null || ui == null)
      return;
    if (!state.isGame() || player == null) {
      if (selecting)
        cancel();
      return;
    }
    if (!selecting)
      return;
    if (control.input != null && control.input.block != null) {
      cancel();
      return;
    }
    if (!player.isBuilder() || ui.chatfrag.shown() || Core.scene.hasKeyboard()) {
      cancel();
      return;
    }
    if (Core.input.keyTap(Binding.deselect) || Core.input.keyTap(Binding.clearBuilding)) {
      cancel();
      return;
    }
  }

  /** 我们的输入处理器必须是"第一个"（= 最先收到事件），否则框选时左键会去开枪/开面板。 */
  static void ensureProcessor() {
    try {
      var list = Core.input.getInputProcessors();
      if (list == null)
        return;
      if (!list.isEmpty() && list.first() == processor)
        return;
      list.remove(processor, true);
      list.insert(0, processor);
    } catch (Throwable ignored) {
    }
  }

  static class Processor implements InputProcessor {
    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, KeyCode button) {
      if (!selecting)
        return false;
      if (overUi(screenX, screenY))
        return false;
      if (dragging && pointer != boxPointer)
        return true;
      if (button == KeyCode.mouseRight || button == KeyCode.mouseMiddle) {
        cancel();
        return true;
      }
      if (button != KeyCode.mouseLeft)
        return true;
      dragging = true;
      boxPointer = pointer;
      var w = Core.input.mouseWorld(screenX, screenY);
      sx = ex = World.toTile(w.x);
      sy = ey = World.toTile(w.y);
      return true;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, KeyCode button) {
      if (!selecting)
        return false;
      if (overUi(screenX, screenY))
        return false;
      if (dragging && pointer != boxPointer)
        return true;
      if (button != KeyCode.mouseLeft)
        return selecting;
      if (dragging) {
        toTiles(screenX, screenY);
        dragging = false;
        boxPointer = -1;
        finish();
        return true;
      }
      return !overUi(screenX, screenY);
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
      if (!selecting)
        return false;
      if (pointer != boxPointer)
        return true;
      if (overUi(screenX, screenY) && !dragging)
        return false;
      toTiles(screenX, screenY);
      return true;
    }

    /** 指针下面有没有 UI 元素（有就让给界面处理）。 */
    static boolean overUi(int screenX, int screenY) {
      try {
        return Core.scene != null && Core.scene.hasMouse(screenX, screenY);
      } catch (Throwable t) {
        return false;
      }
    }

    @Override
    public boolean keyDown(KeyCode keycode) {
      if (!selecting)
        return false;
      if (keycode == KeyCode.escape || keycode == KeyCode.q) {
        cancel();
        return true;
      }
      return false;
    }
  }

  // ==================== 绘制框 ====================

  static void drawOverlay() {
    if (headless || !selecting || sx < 0 || ex < 0)
      return;
    try {
      int x1 = Math.min(sx, ex), y1 = Math.min(sy, ey), x2 = Math.max(sx, ex), y2 = Math.max(sy, ey);
      float fx = (x1 - 0.5f) * tilesize, fy = (y1 - 0.5f) * tilesize;
      float fw = (x2 - x1 + 1) * tilesize, fh = (y2 - y1 + 1) * tilesize;
      int ore = 0;
      for (int y = y1; y <= y2; y++)
        for (int x = x1; x <= x2; x++) {
          Tile t = world.tile(x, y);
          if (t != null && t.drop() != null)
            ore++;
        }
      String text = "矿物阴影：" + ore + " 格矿物地板";
      // 【为什么这套画法是对的】用户 2026-10-09 报"框矿物的时候没有线条边框"，根因不是画法，
      // 而是 Main 里漏了 OreShadowPlacer.register() —— 缺了它，start() 照样能框（输入处理器是
      // start() 自己装的）、阴影也能落地，但 Trigger.draw 这条没挂，拖框时**什么框都不画**。
      // 补上注册后这套（和 FactoryCombiner / SuperTurretPlacer 完全同款）在真客户端上正常显示。
      Draw.draw(Layer.overlayUI, () -> {
        Draw.color(Pal.accent, 0.22f);
        Fill.crect(fx, fy, fw, fh);
        Lines.stroke(2f);
        Draw.color(Pal.accentBack);
        Lines.rect(fx, fy - 1f, fw, fh);
        Draw.color(Pal.accent);
        Lines.rect(fx, fy, fw, fh);
        Draw.reset();

        var font = Fonts.outline;
        boolean ints = font.usesIntegerPositions();
        float z = Draw.z();
        font.setUseIntegerPositions(false);
        Draw.z(Layer.endPixeled);
        font.getData().setScale(1f / renderer.camerascale);
        font.setColor(Color.white);
        font.draw(text, Core.input.mouseWorldX() + 10f, Core.input.mouseWorldY() - 10f);
        font.getData().setScale(1f);
        font.setUseIntegerPositions(ints);
        Draw.z(z);
        Draw.reset();
      });
    } catch (Throwable t) {
      Log.err("[combine] 矿物阴影框绘制失败", t);
    }
  }
}
