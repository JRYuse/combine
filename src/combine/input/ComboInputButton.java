package combine.input;

import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import combine.turret.SuperTurret;
import combine.turret.SuperTurretPlacer;

/**
 * 把"超级组合炮台（框选合体）"按钮插进**原版放置 UI**（{@code buildPlacementUI(Table)} 拿到的那张表）
 * ——也就是紧贴"复制/蓝图"那几个键的那一行，手机和桌面都插。
 *
 * <p>用户要求："重写手机和桌面的 input 的 buildPlacementUI，把启动炮台合体的按钮放 copy 按钮的右边，
 * 而且点击后跟点了 copy 一样让框选优先级高于玩家单位移动"。
 *
 * <p>点击行为 = {@link SuperTurretPlacer#toggle()}：进入框选模式后由
 * {@link SuperTurretPlacer} 自己的 InputProcessor 接管拖动（见那边把处理器排到下标 0 的说明），
 * 所以框选期间玩家单位不会跟着走。
 */
public final class ComboInputButton {
  /** 按钮名（重复调用 buildPlacementUI 时用来防重复插入）。 */
  public static final String BUTTON_NAME = "combineTurretMergeButton";

  private ComboInputButton() {
  }

  /**
   * 往放置 UI 里插按钮。
   *
   * @param index 插到第几个格子（0 = 最左）。手机版用 3（"复制/旋转"切换键的右边），
   *              桌面版用 1（蓝图/粘贴键的右边）。
   */
  static void add(Table table, int index) {
    try {
      if (table == null || !SuperTurret.enabled)
        return;
      // 同一张表可能被调两次（PlacementFragment 建菜单 + InputHandler.add），别插两遍
      if (table.find(BUTTON_NAME) != null)
        return;

      Cell<ImageButton> cell = table.button(mindustry.gen.Icon.turret,
          mindustry.ui.Styles.clearNoneTogglei, SuperTurretPlacer::toggle);
      ImageButton button = cell.get();
      button.name = BUTTON_NAME;
      // 和"复制"键一样是**切换**键：正在框选就高亮，点第二下 = 取消
      cell.update(i -> i.setChecked(SuperTurretPlacer.selecting()));
      cell.size(48f).tooltip("超级组合炮台：点一下框选炮台合体（再点一下取消；默认快捷键 G）");

      // 默认加在最后（= 最右）；挪到指定位置。注意别插到下标 0：Table 的 rows 缓存以第一个格子为准。
      Seq<Cell> cells = table.getCells();
      cells.remove(cell, true);
      int at = Math.max(1, Math.min(index, cells.size));
      cells.insert(at, cell);
      table.invalidate();
    } catch (Throwable t) {
      Log.err("[combine] 放置 UI 里挂「框选合体」按钮失败（还能用快捷键/HUD 按钮）", t);
    }
  }
}
