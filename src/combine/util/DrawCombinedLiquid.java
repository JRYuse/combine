package combine.util;
import combine.net.ComboNet;
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
    // FIX[液体遮挡]: 这里原来是除以 comboTotalItemCap（物品容量），液体比例永远算不对，
    // 液体层几乎画不出来。改成"这种液体自己的组上限"（每种液体独立储存，容量=各成员之和），
    // 并且不再要求必须是组合工厂 —— 钻头/泵等带液体的组合体也要画。
    if (build == null || build.liquids == null)
      return;
    Liquid drawn = drawLiquid != null ? drawLiquid : build.liquids.current();
    if (drawn == null)
      return;
    float cap = Math.max(ComboNet.effectiveLiquidCap(build), 1f);
    float a = build.liquids.get(drawn) / cap;
    Drawf.liquid(liquid, build.x, build.y, a * alpha, drawn.color);
  }

  @Override
  public void load(Block block) {
    // FIX[content.load 崩溃]：draw() 早已放宽为"任何带液体的组合体都能画"
    // （钻头/泵/发电机等，用 effectiveLiquidCap 取组容量），但 load() 里还留着
    // "必须是 CombinedCrafter" 的旧检查 —— CombinedGenerator（蒸汽发电机）等
    // 一走到这里就抛 RuntimeException。任何让组合方块先于 content.load() 存在的环境
    // （服务端时序、第三方客户端、测试 harness）都会因此中断整个内容加载。
    // 这里与 draw() 对齐：只保留 hasLiquids 检查。
    if (!block.hasLiquids) {
      throw new RuntimeException(
          "Block '" + block + "' has a DrawLiquidRegion, but hasLiquids is false! Make sure it is true.");
    }

    liquid = Core.atlas.find(block.name + suffix);
  }
}
