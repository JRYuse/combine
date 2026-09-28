package combine.input;

import arc.scene.ui.layout.Table;
import mindustry.input.MobileInput;

/**
 * 手机版输入处理器：只重写 {@code buildPlacementUI}，在原版那一行
 * （拆除 / 斜向 / **复制（不能旋转时那个键会变成 copy）** / 确认 …）里，
 * 插到"复制"键的**右边**——也就是下标 3 的位置。
 *
 * <p>其余行为一律走 {@code super}，触摸移动、建造、框选复制都不变。
 */
public class ComboMobileInput extends MobileInput {
  @Override
  public void buildPlacementUI(Table table) {
    super.buildPlacementUI(table);
    ComboInputButton.add(table);
  }
}
