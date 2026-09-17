package combine;

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
}
