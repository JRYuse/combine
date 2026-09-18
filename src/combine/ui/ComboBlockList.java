package combine.ui;

import arc.Core;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import combine.Replacer;
import combine.coop.CoopCombo;
import mindustry.Vars;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.world.Block;

/**
 * 组合工厂的「建筑组合开关」界面（挂在游戏设置里）。
 *
 * 列出所有 **js/java 功能扩展建筑** —— 也就是协作组合本来会接管的那些方块
 * （继承原版类、自己写了新功能的模组方块），可以：
 *   · 按名字搜索（英文名 / 本地化名都行）
 *   · 逐个切换"能不能组合"
 *
 * 被标成"不能组合"的方块名会存进 {@code Core.settings}（键见
 * {@link CoopCombo#BLACKLIST_KEY}），下次启动照旧生效；想恢复就再点一下。
 */
public class ComboBlockList {

  /** 设置里这个分类的名字。 */
  public static final String CATEGORY = "组合工厂";
  private static boolean added = false;

  /** 客户端加载后挂到设置面板里（服务端 / 没有 UI 的环境直接跳过）。 */
  public static void register() {
    if (added || Vars.headless || Vars.ui == null || Vars.ui.settings == null)
      return;
    try {
      Vars.ui.settings.addCategory(CATEGORY, ComboBlockList::build);
      added = true;
    } catch (Throwable t) {
      Log.err("[combine] 设置界面挂载失败（不影响游戏运行）", t);
    }
  }

  /**
   * 界面用：按搜索词列出方块（**所有方块**，不只是扩展建筑 —— 这样也能屏蔽某个模组里的
   * 特定功能建筑，甚至原版方块）。排序：会参与组合的排前面，其余在后，各自按名字排。
   *
   * @param cap 最多返回多少个（<=0 表示不限）；query 为空时建议给个上限，免得一次挂上千行。
   */
  public static Seq<Block> list(String query, int cap) {
    return list(query, cap, FILTER_EXT);
  }

  /**
   * 过滤：
   * · {@link #FILTER_EXT} 只看"扩展建筑"（协作组合接管的那些，能手动开关）
   * · {@link #FILTER_HANDLED} 只看会参与组合的（扩展 + 已被替换接管的）
   * · {@link #FILTER_ALL} 全部方块
   * · {@link #FILTER_ON}/{@link #FILTER_OFF} **只列能手动开关组合的建筑**，
   *   再按"当前是可组合 / 不可组合"分（也就是扩展建筑里没被关掉的 / 已关掉的）
   */
  public static final int FILTER_EXT = 0, FILTER_HANDLED = 1, FILTER_ALL = 2, FILTER_ON = 3, FILTER_OFF = 4;

  /** 这个方块能不能手动开关组合（就是"扩展建筑"那一类）。 */
  public static boolean manuallySwitchable(Block b) {
    return kind(b) == 0;
  }

  public static Seq<Block> list(String query, int cap, int filter) {
    String q = query == null ? "" : query.trim().toLowerCase();
    Seq<Block> hit = new Seq<>(), rest = new Seq<>();
    for (Block b : Vars.content.blocks()) {
      if (b == null || b.name == null)
        continue;
      if (!q.isEmpty()) {
        String name = b.name.toLowerCase();
        String loc = b.localizedName == null ? "" : b.localizedName.toLowerCase();
        if (!name.contains(q) && !loc.contains(q))
          continue;
      }
      int k = kind(b);
      if (filter == FILTER_EXT && k != 0)
        continue;
      if (filter == FILTER_HANDLED && k == 2)
        continue;
      // 这两个过滤只列"能手动选择开关组合"的建筑，再按当前状态分
      if (filter == FILTER_ON && (k != 0 || CoopCombo.isBlocked(b.name)))
        continue;
      if (filter == FILTER_OFF && (k != 0 || !CoopCombo.isBlocked(b.name)))
        continue;
      if (k == 0) hit.add(b); else rest.add(b);
    }
    hit.sort((a, b2) -> a.name.compareTo(b2.name));
    rest.sort((a, b2) -> {
      int ka = kind(a), kb = kind(b2);
      return ka != kb ? Integer.compare(ka, kb) : a.name.compareTo(b2.name);
    });
    hit.addAll(rest);
    if (cap > 0 && hit.size > cap)
      hit.truncate(cap);
    return hit;
  }

  /**
   * 分类：0 = js/java 功能扩展建筑（协作组合接管的那些）；1 = 会被替换成组合方块的原版/模组方块；
   * 2 = 其它（本来就不参与组合）。列表按这个顺序排，扩展建筑在最前面。
   */
  public static int kind(Block b) {
    if (b == null)
      return 2;
    try {
      if (CoopCombo.eligibleByClass(b))
        return 0;
    } catch (Throwable ignored) {
    }
    try {
      if (Replacer.replaced.containsValue(b, true))
        return 1;
    } catch (Throwable ignored) {
    }
    return 2;
  }

  /** 这个方块本来会不会参与组合（会被替换成组合方块，或会被协作组合接管）。 */
  public static boolean combinableByNature(Block b) {
    if (b == null)
      return false;
    try {
      if (CoopCombo.eligibleByClass(b))
        return true;
    } catch (Throwable ignored) {
    }
    try {
      return Replacer.replaced.containsValue(b, true);
    } catch (Throwable t) {
      return false;
    }
  }

  /** 所有"本来能被组合"的方块（不含手动屏蔽），用来给界面排序/统计。 */
  public static boolean handled(Block b) {
    return combinableByNature(b);
  }

  /**
   * 所有「功能扩展建筑」：本来能被协作组合接管的方块（**忽略**手动"不组合"名单，
   * 这样界面上还能把它们勾回来），按搜索词过滤（null/空 = 全部）。
   */
  public static Seq<Block> extensions(String query) {
    Seq<Block> out = new Seq<>();
    String q = query == null ? "" : query.trim().toLowerCase();
    for (Block b : Vars.content.blocks()) {
      if (b == null || b.name == null)
        continue;
      if (!CoopCombo.eligibleByClass(b))
        continue;
      if (!q.isEmpty()) {
        String name = b.name.toLowerCase();
        String loc = b.localizedName == null ? "" : b.localizedName.toLowerCase();
        if (!name.contains(q) && !loc.contains(q))
          continue;
      }
      out.add(b);
    }
    out.sort((a, b2) -> a.name.compareTo(b2.name));
    return out;
  }

  /** 界面上"可组合"的开关状态。 */
  public static boolean isCombinable(Block b) {
    return b != null && !CoopCombo.isBlocked(b.name);
  }

  // ==================== 界面 ====================

  /**
   * 开关点完后再重画列表。
   *
   * 【为什么不能直接 run】按钮就在这张表里，点击事件还在派发时就把父表 clearChildren()，
   * 旧按钮会被当场摘掉（arc 里表现为"点一下闪一下/偶发 getScene() 为空的崩溃"）。
   * 放到下一帧做，列表已经稳定。
   */
  private static void defer(java.lang.Runnable[] rebuild) {
    if (arc.Core.app == null) {
      rebuild[0].run();
      return;
    }
    arc.Core.app.post(() -> {
      if (rebuild[0] != null)
        rebuild[0].run();
    });
  }

  /** 一排过滤按钮；当前生效的那个会高亮（点它会整块重画，好让高亮跟着走）。 */
  private static void addFilterRow(SettingsTable table, Runnable[] whole, int[] modes, String[] names) {
    table.table(filters -> {
      filters.left();
      for (int i = 0; i < modes.length; i++) {
        final int mode = modes[i];
        boolean active = filterMode == mode;
        String label = active ? "[accent]▶ " + names[i] + "[]" : names[i];
        filters.button(label, () -> {
          filterMode = mode;
          defer(whole);       // 整块重画（按钮也要跟着高亮），下一帧做，别在点击派发里清表
        }).growX().minWidth(0f).height(32f).padRight(6f);
      }
    }).growX().left().padTop(4f).row();
  }

  /** 界面状态（放在静态里：整块重画/重新打开设置都不会丢当前选择和搜索词）。 */
  private static int filterMode = FILTER_EXT;
  private static String searchText = "";

  public static void build(SettingsTable table) {
    build(table, new Runnable[1]);
  }

  /**
   * @param whole 整块重画（点了过滤按钮后要用它，因为过滤按钮自己也要跟着高亮当前选择）
   */
  private static void build(SettingsTable table, Runnable[] whole) {
    whole[0] = () -> {
      table.clearChildren();
      build(table, whole);
    };

    final String[] query = {searchText};
    final int[] filter = {filterMode};
    final Table list = new Table();
    list.top().left();
    list.defaults().left();

    final Runnable[] rebuild = new Runnable[1];
    rebuild[0] = () -> {
      try {
        list.clearChildren();
        boolean searching = query[0] != null && !query[0].trim().isEmpty();
        Seq<Block> blocks = list(query[0], searching ? 0 : 120, filter[0]);
        if (blocks.isEmpty()) {
          list.add("[gray]没有匹配的建筑[]").left().row();
          return;
        }
        for (Block b : blocks) {
          final boolean blockedNow = CoopCombo.isBlocked(b.name);
          int kind = kind(b);
          list.table(row -> {
            row.left();
            if (b.uiIcon != null)
              row.image(b.uiIcon).size(24f).padRight(6f);
            String tag = kind == 0 ? "[accent]扩展[]" : (kind == 1 ? "[gray]替换[]" : "[darkGray]—[]");
            String state = kind != 0 ? "" : (blockedNow ? "[scarlet]关[] " : "[accent]开[] ");
            // 名字一栏**让它自己伸缩 + 换行**：手机屏窄，写死宽度会把右边的按钮顶出可视区，
            // 于是"按钮看得见一半、点不到"（见用户截图）。
            // 这里必须用 wrap 而不是 ellipsis：arc 的 Label 只有 wrap 才会把"最小宽度"降成 0
            // （ellipsis 只影响绘制），否则长中文名仍会把整行撑宽、把按钮挤出去。
            row.add(state + tag + " " + b.localizedName + " [gray]" + b.name + "[]")
                .left().growX().wrap().minWidth(0f);
            if (kind != 0) {
              // 替换接管 / 本来就不组合：这两类不吃设置里的开关
              row.add(kind == 1 ? "[gray]替换接管[]" : "[darkGray]不参与[]")
                  .right().padLeft(4f).padRight(4f);
            } else {
              row.button(blockedNow ? "恢复组合" : "关闭组合", () -> {
                CoopCombo.setBlocked(b.name, !blockedNow);
                defer(rebuild);
              }).size(96f, 34f).padLeft(4f).padRight(8f);
            }
          }).growX().left().row();
        }
        if (!searching && blocks.size >= 120)
          list.add("[gray]…只显示前 120 个，输入名字搜索全部[]").left().padTop(4f).row();
      } catch (Throwable t) {
        Log.err("[combine] 建筑组合开关列表绘制失败", t);
      }
    };

    table.add("[accent]组合工厂[] [lightgray]· 建筑组合开关").left().padBottom(4f).row();
    table.add("[lightgray]开/关 = 这个建筑现在能不能组合（点右边按钮切换，立刻生效）。[]")
        .left().wrap().row();
    table.add("[lightgray]「替换」= 已被组合方块顶掉，由组合方块接管，不吃这个开关。[]")
        .left().wrap().padBottom(8f).row();

    TextField field = new TextField();
    field.setMessageText("搜索建筑（英文名 / 中文名）");
    field.setText(searchText);
    field.changed(() -> {
      searchText = field.getText();
      query[0] = field.getText();
      rebuild[0].run();
    });
    table.add(field).growX().left().row();

    // 过滤按钮分两排（手机屏窄，一排塞不下五个）。当前选中的那个前面加个标记。
    addFilterRow(table, whole, new int[]{FILTER_EXT, FILTER_ON, FILTER_OFF},
        new String[]{"只看扩展建筑", "显示可组合", "显示不可组合"});
    addFilterRow(table, whole, new int[]{FILTER_HANDLED, FILTER_ALL},
        new String[]{"只看会组合的", "全部方块"});

    table.table(btns -> {
      btns.left();
      btns.button("全部可组合", () -> {
        for (Block b : list(query[0], 0, filter[0]))
          CoopCombo.setBlocked(b.name, false);
        defer(rebuild);
      }).growX().minWidth(0f).height(32f).padRight(6f);
      btns.button("全部不组合", () -> {
        for (Block b : list(query[0], 0, filter[0]))
          CoopCombo.setBlocked(b.name, true);
        defer(rebuild);
      }).growX().minWidth(0f).height(32f).padRight(6f);
      btns.button("清空手动名单", () -> {
        for (String name : CoopCombo.blockedNames())
          CoopCombo.setBlocked(name, false);
        defer(rebuild);
      }).growX().minWidth(0f).height(32f);
    }).growX().left().padTop(6f).row();

    table.label(() -> {
      int total = Vars.content.blocks().size;
      int ext = extensions("").size;
      int off = CoopCombo.blockedNames().size;
      return "[lightgray]共 " + total + " 个方块（其中 " + ext + " 个扩展建筑会组合），"
          + Strings.fixed(off, 0) + " 个被标为不组合[]";
    }).left().wrap().padTop(4f).row();

    ScrollPane pane = new ScrollPane(list);
    pane.setFadeScrollBars(false);
    // 只允许竖着滚：横着能滚的话，比面板宽的行会被推到右边，右边的按钮就"看得见一半、点不到"
    // （用户截图里就是这个现象）。
    pane.setScrollingDisabled(true, false);
    pane.setOverscroll(false, false);
    // 宽度跟着设置面板走（只 growX，不写死）：写死宽度在窄屏上会超出面板，
    // 行里的按钮就被挤到面板外面——看得到、点不到。
    table.add(pane).growX().height(380f).padTop(6f).left().row();

    rebuild[0].run();
  }
}
