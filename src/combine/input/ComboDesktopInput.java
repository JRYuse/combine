package combine.input;

import arc.scene.ui.layout.Table;
import mindustry.input.DesktopInput;

/**
 * 桌面版输入处理器：只重写 {@code buildPlacementUI}，在原版的"蓝图/粘贴、数据库、科技树、星球"
 * 那一行里、**蓝图（粘贴）键的右边**插一个"框选合体"按钮（用户要求放 copy 按钮右边；
 * 桌面这一行里最接近 copy 的就是那个蓝图/粘贴键）。
 *
 * <p>其余行为一律走 {@code super}，所以桌面专有的多线程建造、框选、快捷键都不变。
 * 换掉输入处理器见 {@link combine.Main#installInputHandler()}（走原版 {@code Control.setInput}）。
 */
public class ComboDesktopInput extends DesktopInput {
  @Override
  public void buildPlacementUI(Table table) {
    super.buildPlacementUI(table);
    ComboInputButton.add(table);
  }
}
