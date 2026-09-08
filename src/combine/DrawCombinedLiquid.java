package combine;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import combine.CombinedCrafter.CombinedCrafterBuild;
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
    if (build instanceof CombinedCrafterBuild ccb) {
      Liquid drawn = drawLiquid != null ? drawLiquid : build.liquids.current();
      float a = ccb.liquids.get(drawn) / Math.max(ccb.comboTotalItemCap, 1);
      Drawf.liquid(liquid, build.x, build.y,
          a * alpha,
          drawn.color);
    }
  }

  @Override
  public void load(Block block) {
    if (!(block instanceof CombinedCrafter))
      throw new RuntimeException(block + "must be CombinedCrafter");
    if (!block.hasLiquids) {
      throw new RuntimeException(
          "Block '" + block + "' has a DrawLiquidRegion, but hasLiquids is false! Make sure it is true.");
    }

    liquid = Core.atlas.find(block.name + suffix);
  }
}
