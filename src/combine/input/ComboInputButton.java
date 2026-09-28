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

  /** 往放置 UI 里插按钮：放在那一行**最左**（= 拆除键左边）。 */
  static void add(Table table) {
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
      cell.size(48f).tooltip("超级组合炮台：点一下框选炮台合体（再点一下 / 右键 / Esc 取消）");

      // 【放到这一行最左】先正常 append（布局一定对），再挪到"本行第一个格子"之前。
      // Cell 的 row/column 是包级私有，挪完必须一起改，否则 Table.layout() 会把它排歪（3x3 像素）。
      Seq<Cell> cells = table.getCells();
      if(!cells.isEmpty() && cellFields()){
        int myRow = cellRow(cell);
        int rowStart = cells.size - 1;
        while(rowStart > 0 && cellRow(cells.get(rowStart - 1)) == myRow) rowStart--;
        cells.remove(cell, true);
        cells.insert(rowStart, cell);
        int col = 0;
        for(int i = rowStart; i < cells.size; i++){
          Cell c = cells.get(i);
          if(cellRow(c) != myRow) break;
          setCellRowCol(c, myRow, col++);
        }
        if(fAbove != null){
          try{ fAbove.setInt(cell, -1); }catch(Throwable ignored){}
        }
      }
      table.invalidate();
    } catch (Throwable t) {
      Log.err("[combine] 放置 UI 里挂「框选合体」按钮失败（还能用快捷键/HUD 按钮）", t);
    }
  }

  // ---- Cell 的 row/column/cellAboveIndex 是包级私有：反射读写（拿不到就留在行尾，至少能显示能点）----
  static java.lang.reflect.Field fRow, fColumn, fAbove;
  static boolean fieldsTried = false;

  static boolean cellFields() {
    if (fieldsTried)
      return fRow != null && fColumn != null;
    fieldsTried = true;
    try {
      Class<?> c = Class.forName("arc.scene.ui.layout.Cell");
      fRow = c.getDeclaredField("row");
      fRow.setAccessible(true);
      fColumn = c.getDeclaredField("column");
      fColumn.setAccessible(true);
      try {
        fAbove = c.getDeclaredField("cellAboveIndex");
        fAbove.setAccessible(true);
      } catch (Throwable ignored) {
      }
    } catch (Throwable t) {
      fRow = fColumn = null;
    }
    return fRow != null && fColumn != null;
  }

  static int cellRow(Cell c) {
    if (!cellFields())
      return -1;
    try {
      return fRow.getInt(c);
    } catch (Throwable t) {
      return -1;
    }
  }

  static void setCellRowCol(Cell c, int row, int column) {
    try {
      fRow.setInt(c, row);
      fColumn.setInt(c, column);
    } catch (Throwable ignored) {
    }
  }
}
