package combine.util;
import arc.struct.ObjectSet;
import arc.util.Log;

/**
 * 组合建筑信息面板的异常保护。
 *
 * 信息面板（Building.display）是**每帧**由游戏调用的；只要里面抛一次异常，
 * Arc/Mindustry 会当场崩（"Mindustry has crashed"），而且这张面板只画了一半 ——
 * 半成品面板里可能留下没有挂到场景上的元素，之后鼠标点上去就会在输入分发里
 * 报 `Element.getScene() is null`（Arc 的 addTouchFocus），表现成另一个看着毫不相干的崩溃。
 *
 * 所以组合建筑自己往面板里加的东西，一律包一层：出错就记日志（同一处只记一次），
 * 绝不把异常抛回游戏。
 */
public class ComboUi {
  private static final ObjectSet<String> reported = new ObjectSet<>();

  /** 安全执行一段界面绘制代码；同 tag 只报告一次，避免每帧刷屏。 */
  public static void safe(String tag, Runnable runnable) {
    try {
      runnable.run();
    } catch (Throwable t) {
      if (reported.add(tag)) {
        Log.err("[combine] 组合建筑界面绘制出错(" + tag + ")，已跳过（不影响游戏运行）", t);
      }
    }
  }

  /**
   * 给组合面板画一条"电力"条：按**整组**耗电 × 电网状态显示；组里没人耗电就不画。
   *
   * 注意不要用原版 displayBars() —— 它会把原版注册的那些条一起带出来，其中"液体条"读的是
   * block.liquidCapacity（组合建筑为了不让管道算出负流量把它抬成了 9999 假容量），
   * 于是面板上会多出一条 水 160/9.99K 的假条，还和组合自己的液条重复。
   */
  public static void addPowerBar(arc.scene.ui.layout.Table table, mindustry.gen.Building self, float totalUsage) {
    if (table == null || self == null || self.power == null || totalUsage <= 0.001f) return;
    final float usage = totalUsage;
    final mindustry.world.modules.PowerModule pw = self.power;
    table.add(new mindustry.ui.Bar(
        () -> "电力 " + arc.util.Strings.fixed(usage * pw.status * 60f, 1) + " ⚡/s",
        () -> mindustry.graphics.Pal.power,
        () -> pw.status)).growX().height(18f).pad(4).left();
    table.row();
  }
}
