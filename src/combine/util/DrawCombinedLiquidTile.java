package combine.util;
import combine.net.ComboNet;
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
    // 同上：按"这种液体自己的组上限"算比例，且对所有组合建筑生效
    if (build == null || build.liquids == null)
      return;
    Liquid drawn = drawLiquid != null ? drawLiquid : build.liquids.current();
    if (drawn == null)
      return;
    float cap = Math.max(ComboNet.effectiveLiquidCap(build), 1f);
    float a = build.liquids.get(drawn) / cap;
    LiquidBlock.drawTiledFrames(build.block.size, build.x, build.y, padLeft, padRight, padTop, padBottom, drawn,
        a * alpha);
  }

  @Override
  public void load(Block block) {
    if (padLeft < 0)
      padLeft = padding;
    if (padRight < 0)
      padRight = padding;
    if (padTop < 0)
      padTop = padding;
    if (padBottom < 0)
      padBottom = padding;
  }
}
