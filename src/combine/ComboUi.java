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

  /**
   * 在 parent 里挂一张「每帧重画」的子表，内容由 builder 决定。
   *
   * 背景：Mindustry 的选中方块信息面板（PlacementFragment）**只在切换选中目标时重建一次**，
   * 之后一直复用同一张表。所以面板里的数字必须自己刷新，否则就会停在打开面板那一刻
   * （"仓库里的物品数量不动"就是这么来的）。包一层这个，builder 每帧重跑，
   * 再配 Bar / label(Prov) 这类活 supplier 就万无一失。
   */
  public static arc.scene.ui.layout.Table live(arc.scene.ui.layout.Table parent, String tag, arc.func.Cons<arc.scene.ui.layout.Table> builder) {
    arc.scene.ui.layout.Table t = new arc.scene.ui.layout.Table();
    t.left();
    t.update(() -> {
      t.clearChildren();
      t.defaults().left();
      safe(tag, () -> builder.get(t));
    });
    parent.row();
    parent.add(t).growX().left();
    return t;
  }
}
