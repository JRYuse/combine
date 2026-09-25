package combine.util;
import arc.graphics.Color;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import combine.net.ComboNet;
import mindustry.gen.Building;
import mindustry.world.Block;

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

  /** 组合体构成列表的**内容高度上限**（约 5 行）——再多就出滚动条，面板不再往下长。 */
  public static final float memberListHeight = 118f;

  /** 整个信息面板的高度上限 = 屏幕高度的 62%（下限 240）——组合体再大也顶不穿屏幕。 */
  public static float panelMaxHeight() {
    float h = 720f;
    try {
      if (arc.Core.graphics != null)
        h = arc.Core.graphics.getHeight() / arc.scene.ui.layout.Scl.scl();
    } catch (Throwable ignored) {
    }
    return Math.max(240f, h * 0.62f);
  }

  /**
   * 把信息面板的整段内容放进一个**高度封顶的滚动窗**。
   *
   * <p>用户报的"组合的东西过多，显示面板会非常大"：组合建筑的 display() 会往上加
   * 标题 / 物品条 / 液体条 / 组合体构成 / 本机信息……组合体一大，面板就一路长到屏幕外。
   * 这里在 display() 里把内容先画进一张临时表，超过 {@link #panelMaxHeight()} 就套滚动窗
   * （内容不高就直接放，免得白白多一层、还抢滚轮）。
   *
   * <p>滚动窗实例挂在 outer.userObject 上复用：面板本身是每帧重画/刷值的，
   * 每帧新建一个滚动窗的话 scrollY 恒为 0，玩家根本滚不动。
   */
  public static void scrollPanel(Table outer, arc.func.Cons<Table> content) {
    if (outer == null || content == null)
      return;

    Table inner = new Table();
    inner.left();
    content.get(inner);

    float max = panelMaxHeight();
    boolean hadPane = outer.userObject instanceof ScrollPane;
    if (!hadPane && inner.getPrefHeight() <= max) {
      // 内容不高：原样放（和以前一样，不带滚动窗）
      outer.add(inner).left();
      return;
    }

    ScrollPane pane = hadPane ? (ScrollPane) outer.userObject : new ScrollPane(inner);
    pane.setWidget(inner);
    pane.setFadeScrollBars(false);
    pane.setScrollbarsOnTop(true);
    pane.setOverscroll(false, false);
    pane.setScrollingDisabled(true, false); // 只竖向滚
    outer.userObject = pane;
    outer.add(pane).maxHeight(max).left();
  }

  /**
   * 画"组合体构成"列表（组合建筑信息面板里那一段）。
   *
   * <p>【为什么要滚动】组合体可以有几十台、几十种不同类型，而这张列表是"每种类型一行"：
   * 行数没有上限时面板会一路长到屏幕外（用户报的"组合的东西过多，显示面板会非常大"），
   * 右下角的信息面板顶穿屏幕、把别的东西全挡住。这里把列表放进 {@link ScrollPane}：
   * 高度封顶 {@link #memberListHeight}，行数超了就滚动；标题里带上"共 N 台"，
   * 这样滚不滚动都知道整组有多少台。
   *
   * @param self       面板所属建筑（成员按 ComboNet.displayMembers 算：连接器/节点接进来的也算）
   * @param localCount 本地组大小（客户端没有本地组时按显示成员回退）
   */
  public static void memberList(Table table, Building self, int localCount) {
    if (table == null || self == null)
      return;

    ObjectIntMap<Block> counts = new ObjectIntMap<>();
    int total = 0;
    for (Building m : ComboNet.displayMembers(self, localCount)) {
      if (m == null || !m.isValid() || m.block == null)
        continue;
      counts.increment(m.block, 1);
      total++;
    }

    Seq<Block> blocks = new Seq<>();
    for (Block b : counts.keys())
      blocks.add(b);
    // 按 id 排序：内容表顺序稳定，面板每帧重画时行序不会跳
    blocks.sort(b -> b.id);

    Table list = new Table();
    list.left();
    boolean has = false;
    for (Block b : blocks) {
      int count = counts.get(b, 0);
      if (count <= 0)
        continue;
      has = true;
      list.add(b.localizedName + "*" + count).color(Color.white).left().growX();
      list.row();
    }
    if (!has)
      list.add("[darkGray]无").left();

    table.add("[lightgray]组合体构成（共 " + total + " 台）:[]").left().growX();
    table.row();

    if (blocks.size <= 1) {
      // 一行都用不着滚：直接放，别为一行套个滚动窗（免得出现多余的空滚动条/内边距）
      table.userObject = null;
      table.add(list).growX().left();
    } else {
      // 【滚动窗必须复用】调用方（各组合建筑的 display()）是**每帧** clearChildren() + 重画这一段，
      // 而新建的 ScrollPane 的 scrollY 恒为 0 —— 每帧换一个滚动窗，玩家就永远滚不动
      // （手一松就弹回顶部）。这里把滚动窗实例挂在 table.userObject 上复用，每帧只换它里面的内容表，
      // 滚动位置就能留住。
      ScrollPane pane = table.userObject instanceof ScrollPane old ? old : new ScrollPane(list);
      pane.setWidget(list);
      // 滚动条不淡出：一眼看得出"这里还能往下滚"
      pane.setFadeScrollBars(false);
      pane.setScrollbarsOnTop(true);
      pane.setOverscroll(false, false);
      pane.setScrollingDisabled(true, false); // 只竖向滚
      table.userObject = pane;
      table.add(pane).growX().maxHeight(memberListHeight).left();
    }
    table.row();
  }
}
