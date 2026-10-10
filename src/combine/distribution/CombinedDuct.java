package combine.distribution;

import arc.math.Mathf;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.world.blocks.distribution.Duct;

/**
 * 组合管道：替换原版/模组的 {@link Duct}（含 armored-duct 这类"装甲管道"，它只是 Duct 上的
 * {@code armored} 开关，靠 {@code copyFields} 原样带过来）。玩法同 {@link CombinedConveyor}：
 * 同种管道覆盖一层 → 速度按层数成倍。
 */
public class CombinedDuct extends Duct {
  /** 最多叠几层（见 {@link Layered}）。 */
  public int maxLayers = ConveyorOverlay.MAX_LAYERS;

  public CombinedDuct(String name) {
    super(name);
  }

  /** 拖拽预览：记下这条线（松手时给线上的同种管道各叠一层，见 {@link ConveyorOverlay#finishDrag}）。
   *  没有本机玩家时跳过原版补桥那步（它要用 {@code Vars.player}，无头里会 NPE，见 CombinedConveyor）。 */
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

  public class CombinedDuctBuild extends DuctBuild implements Layered {
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

    // 原版 DuctBuild.updateTile 里的进度增量与绘制里的插值都读方块级 speed，见 CombinedConveyor 的注释
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

    @Override
    public void draw(boolean under) {
      float keep = speed;
      speed = keep * speedMul();
      try {
        super.draw(under);
      } finally {
        speed = keep;
      }
      // 层数数字画在管道自己身上（只在正常那一趟画；under 那趟是垫底贴图）
      if (!under)
        ConveyorOverlay.drawLayerBadge(this, layers);
    }
  }
}
