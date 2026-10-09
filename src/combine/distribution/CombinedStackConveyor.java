package combine.distribution;

import arc.math.Mathf;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.world.blocks.distribution.StackConveyor;

/**
 * 组合堆叠传送带（塑钢/浪涌传送带那类）：替换原版/模组的 {@link StackConveyor}（含子类）。
 * 玩法同 {@link CombinedConveyor}：同种带子覆盖一层 → 速度按层数成倍（收料速度跟着变；
 * 层数靠 {@link ConveyorLayerState} 同步给所有人，两端算出的速度一致）。
 */
public class CombinedStackConveyor extends StackConveyor {
  /** 最多叠几层（见 {@link Layered}）。 */
  public int maxLayers = ConveyorOverlay.MAX_LAYERS;

  public CombinedStackConveyor(String name) {
    super(name);
  }

  /** 拖拽预览：记下这条线（松手时给线上的同种带子各叠一层，见 {@link ConveyorOverlay#finishDrag}）。 */
  @Override
  public void handlePlacementLine(Seq<BuildPlan> plans) {
    if (Vars.player != null)
      super.handlePlacementLine(plans);
    ConveyorOverlay.captureLine(plans);
  }

  /** 先放预设、按建造（✓）/入队时才叠层，见 {@link ConveyorOverlay#onNewPlan(Block, BuildPlan)}。 */
  @Override
  public void onNewPlan(BuildPlan plan) {
    ConveyorOverlay.onNewPlan(this, plan);
  }

  /** 预览：同方向"覆盖叠层"的预设要画成可以放（原版判成非法会画红）。 */
  @Override
  public void drawPlan(BuildPlan plan, arc.util.Eachable<BuildPlan> list, boolean valid) {
    super.drawPlan(plan, list, valid || ConveyorOverlay.isOverlayPlan(this, plan));
  }

  @Override
  public void drawPlan(BuildPlan plan, arc.util.Eachable<BuildPlan> list, boolean valid, float alpha) {
    super.drawPlan(plan, list, valid || ConveyorOverlay.isOverlayPlan(this, plan), alpha);
  }

  public class CombinedStackConveyorBuild extends StackConveyorBuild implements Layered {
    public int layers = 1;

    @Override
    public int layers() {
      return layers;
    }

    @Override
    public void layers(int value) {
      int next = Mathf.clamp(Math.max(layers, value), 1, maxLayers);
      if (next == layers)
        return;
      layers = next;
      noSleep();
      ConveyorOverlay.changedFx(this);
    }

    // 原版 StackConveyorBuild.updateTile 里 cooldown -= speed * eff * delta()，speed 是方块级字段
    @Override
    public void updateTile() {
      float keep = speed;
      speed = keep * speedMul();
      try {
        super.updateTile();
      } finally {
        speed = keep;
      }
    }
  }
}
