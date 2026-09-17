package combine;
import combine.coop.CoopCombo;
import arc.struct.Seq;
import mindustry.world.Block;

/**
 * 【不被组合的方块类】
 *
 * 这里列出的类，以及用 js/java 写出来的**子类**，一律不参与组合：
 *   · 不会被替换成组合方块（见 {@link Main#processModBlocks} / {@link Main#processWalls}）
 *   · 也不会被"协作组合"接管（见 {@link CoopCombo#computeEligible}）
 *
 * 判据用的是 instanceof，所以子类自动跟着被排除 —— 不用管模组里那些继承原版类写的方块。
 * 想额外排除某个类，往 {@link #classes} 里加一行即可；运行时加的话记得叫一次
 * {@link CoopCombo#clearEligibilityCache()}（资格判定有缓存）。
 *
 * 传输类（传送带/管道/分流器/装卸器/桥/批量装卸器…）本来就不该"组合"：
 * 它们是把东西**搬走**的，共用一个库存模块等于把它们当仓库使。
 */
public class NoCombo {

  /** 不参与组合的类；它们的子类同样不参与。 */
  public static final Seq<Class<?>> classes = Seq.with(
      // ——— mindustry.world.blocks.distribution（物品/管道运输：传送带、桥、分流器、装卸器…）———
      mindustry.world.blocks.distribution.ChainedBuilding.class,   // 接口：带"链式"语义的运输方块都实现它
      mindustry.world.blocks.distribution.Conveyor.class,
      mindustry.world.blocks.distribution.ArmoredConveyor.class,
      mindustry.world.blocks.distribution.Duct.class,
      mindustry.world.blocks.distribution.OverflowDuct.class,
      mindustry.world.blocks.distribution.StackConveyor.class,
      mindustry.world.blocks.distribution.Junction.class,
      mindustry.world.blocks.distribution.DuctJunction.class,
      mindustry.world.blocks.distribution.DirectionBridge.class,
      mindustry.world.blocks.distribution.DirectionLiquidBridge.class,
      mindustry.world.blocks.distribution.ItemBridge.class,
      mindustry.world.blocks.distribution.BufferedItemBridge.class,
      mindustry.world.blocks.distribution.DuctBridge.class,
      mindustry.world.blocks.distribution.Router.class,
      mindustry.world.blocks.distribution.DuctRouter.class,
      mindustry.world.blocks.distribution.StackRouter.class,
      mindustry.world.blocks.distribution.Sorter.class,
      mindustry.world.blocks.distribution.OverflowGate.class,
      mindustry.world.blocks.distribution.DirectionalUnloader.class,
      mindustry.world.blocks.distribution.MassDriver.class
  );

  /** 这个方块（或其父类）是不是在"不组合"名单里。 */
  public static boolean blocked(Block b) {
    if (b == null)
      return false;
    for (int i = 0; i < classes.size; i++) {
      Class<?> c = classes.get(i);
      if (c != null && c.isInstance(b))
        return true;
    }
    return false;
  }
}
