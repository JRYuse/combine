package combine;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import mindustry.gen.Building;
import mindustry.graphics.Drawf;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.draw.DrawBlock;

public class DrawCombinedLiquid extends DrawBlock {
  public Liquid drawLiquid;
  public TextureRegion liquid;
  public String suffix = "-liquid";
  public float alpha = 1f;

  public DrawCombinedLiquid(Liquid drawLiquid) {
    this.drawLiquid = drawLiquid;
  }

  public DrawCombinedLiquid() {
  }

  @Override
  public void draw(Building build) {
    Liquid drawn = drawLiquid != null ? drawLiquid : build.liquids.current();
    float cap = getComboLiquidCap(build);
    float a = build.liquids.get(drawn) / Math.max(cap, 1f);
    Drawf.liquid(liquid, build.x, build.y, a * alpha, drawn.color);
  }

  @Override
  public void load(Block block) {
    if (!block.hasLiquids) {
      throw new RuntimeException(
          "Block '" + block + "' has a DrawCombinedLiquid, but hasLiquids is false! Make sure it is true.");
    }
    liquid = Core.atlas.find(block.name + suffix);
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
