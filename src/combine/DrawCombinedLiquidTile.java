package combine;

import mindustry.gen.Building;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.blocks.liquid.LiquidBlock;
import mindustry.world.draw.DrawBlock;

public class DrawCombinedLiquidTile extends DrawBlock {
  public Liquid drawLiquid;
  public float padding;
  public float padLeft = -1, padRight = -1, padTop = -1, padBottom = -1;
  public float alpha = 1f;

  public DrawCombinedLiquidTile(Liquid drawLiquid, float padding) {
    this.drawLiquid = drawLiquid;
    this.padding = padding;
  }

  public DrawCombinedLiquidTile(Liquid drawLiquid) {
    this.drawLiquid = drawLiquid;
  }

  public DrawCombinedLiquidTile() {
  }

  @Override
  public void draw(Building build) {
    Liquid drawn = drawLiquid != null ? drawLiquid : build.liquids.current();
    float cap = getComboLiquidCap(build);
    float a = build.liquids.get(drawn) / Math.max(cap, 1f);
    LiquidBlock.drawTiledFrames(build.block.size, build.x, build.y, padLeft, padRight, padTop, padBottom, drawn, a * alpha);
  }

  @Override
  public void load(Block block) {
    if (padLeft < 0) padLeft = padding;
    if (padRight < 0) padRight = padding;
    if (padTop < 0) padTop = padding;
    if (padBottom < 0) padBottom = padding;
  }

  /** 获取组合块的总液体容量，适配所有 Combined 类型 */
  private float getComboLiquidCap(Building build) {
    if (build instanceof CombinedCrafter.CombinedCrafterBuild b) return b.comboTotalLiquidCap;
    if (build instanceof CombinedDrill.CombinedDrillBuild b) return b.comboTotalLiquidCap;
    if (build instanceof CombinedGenerator.CombinedGeneratorBuild b) return b.comboTotalLiquidCap;
    if (build instanceof CombinedRegenProjector.CombinedRegenProjectorBuild b) return b.comboTotalLiquidCap;
    if (build instanceof CombinedOverdriveProjector.CombinedOverdriveProjectorBuild b) return b.comboTotalLiquidCap;
    if (build instanceof CombinedMendProjector.CombinedMendProjectorBuild b) return b.comboTotalLiquidCap;
    if (build instanceof CombinedForceProjector.CombinedForceProjectorBuild b) return b.comboTotalLiquidCap;
    if (build instanceof CombinedLaunchPad.CombinedLaunchPadBuild b) return b.comboTotalLiquidCap;
    return build.block.liquidCapacity;
  }
}
