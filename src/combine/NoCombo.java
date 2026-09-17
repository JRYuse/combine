package combine;

import combine.coop.CoopCombo;
import arc.struct.Seq;
import mindustry.world.Block;
import mindustry.world.blocks.production.Incinerator;

/**
 * 【不被组合的方块类】
 *
 * 这里列出的类，以及用 js/java 写出来的**子类**，一律不参与组合：
 * · 不会被替换成组合方块（见 {@link Main#processModBlocks} / {@link Main#processWalls}）
 * · 也不会被"协作组合"接管（见 {@link CoopCombo#computeEligible}）
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
      mindustry.world.blocks.distribution.ChainedBuilding.class, // 接口：带"链式"语义的运输方块都实现它
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
      mindustry.world.blocks.distribution.MassDriver.class,

      // ——— mindustry.world.blocks.liquid（液体运输：导管、液体路由器、液体桥…）———
      // 加 LiquidBlock 一个就覆盖了
      // Conduit/ArmoredConduit/LiquidJunction/LiquidRouter/LiquidBridge，
      // 也覆盖模组里继承它们写的"液体分流器""气体泵"这类方块（VE 里就有好几个）
      mindustry.world.blocks.liquid.LiquidBlock.class,
      mindustry.world.blocks.liquid.Conduit.class,
      mindustry.world.blocks.liquid.ArmoredConduit.class,
      mindustry.world.blocks.liquid.LiquidJunction.class,
      mindustry.world.blocks.liquid.LiquidRouter.class,
      mindustry.world.blocks.liquid.LiquidBridge.class,

      // ——— 载荷运输（只列真正"搬运"的，别把工厂/重构厂也算进去）———
      mindustry.world.blocks.payloads.PayloadConveyor.class,
      mindustry.world.blocks.payloads.PayloadRouter.class,
      mindustry.world.blocks.payloads.PayloadMassDriver.class,
      mindustry.world.blocks.payloads.PayloadLoader.class,
      mindustry.world.blocks.payloads.PayloadUnloader.class,
      mindustry.world.blocks.payloads.PayloadSource.class,
      mindustry.world.blocks.payloads.PayloadVoid.class,

      // ——— 装卸器（类在 storage 包里，但语义是运输）———
      mindustry.world.blocks.storage.Unloader.class, Incinerator.class);

  /**
   * 这个方块是不是"不组合"：
   * · 类在 {@link #classes} 里（含 js/java 子类）；
   * · 或者被玩家在设置界面里手动标了"不组合"（见 {@link CoopCombo#isBlocked}）。
   * 两条路径都走这里：方块替换（Main.processModBlocks / processWalls）和协作组合。
   */
  public static boolean blocked(Block b) {
    return blockedByClass(b) || CoopCombo.isBlocked(b.name);
  }

  /** 只看"类名单"（不含玩家手动屏蔽）。 */
  public static boolean blockedByClass(Block b) {
    if (b == null)
      return false;
    for (int i = 0; i < classes.size; i++) {
      Class<?> c = classes.get(i);
      if (c == null || !c.isInstance(b))
        continue;
      // 例外：LiquidBlock 既是导管/液体路由器的基类，也是**生产型水泵**（Pump/SolidPump/Fracker）的基类。
      // 水泵是"产液体"的生产建筑，必须照旧参与组合，不能跟着液体运输一起被排除。
      if (c == mindustry.world.blocks.liquid.LiquidBlock.class
          && b instanceof mindustry.world.blocks.production.Pump)
        continue;
      return true;
    }
    return false;
  }
}
