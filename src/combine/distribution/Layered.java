package combine.distribution;

/**
 * 【组合传送带 · 覆盖层数】同一格上再"覆盖"一条同种传送带 → 层数 +1，速度按层数成倍。
 *
 * <p>层数是模组自己的状态，走 {@link ConveyorLayerState}（自定义存档块，存档与联机入服的世界流都带）：
 * 地图区里仍然只有原版那一份字节 —— 关掉模组后原版读档不会错位（原因见 {@code ComboSaveState} 的注释）。
 */
public interface Layered {
  /** 当前层数（1 = 原速，没被覆盖过）。 */
  int layers();

  /** 设层数：只增不减，并夹在 [1, 方块上限]（服务端权威 + 转发，客户端乐观生效后也会收到同一个值）。 */
  void layers(int value);

  /** 速度倍率 = 层数（1 层 = 原速，N 层 = N 倍速；N 上限见 {@code ConveyorOverlay.MAX_LAYERS}）。 */
  default float speedMul() {
    return Math.max(1, layers());
  }
}
