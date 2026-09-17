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

  /** 过滤：只看扩展建筑 / 只看会参与组合的 / 全部。 */
  public static final int FILTER_EXT = 0, FILTER_HANDLED = 1, FILTER_ALL = 2;

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

  private static void build(SettingsTable table) {
    final String[] query = {""};
    final int[] filter = {FILTER_EXT};
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
          list.table(row -> {
            row.left();
            if (b.uiIcon != null)
              row.image(b.uiIcon).size(24f).padRight(6f);
            int kind = kind(b);
            String tag = kind == 0 ? "[accent]扩展[]" : (kind == 1 ? "[gray]替换[]" : "[darkGray]—[]");
            row.add(tag + " " + b.localizedName + "   [gray]" + b.name + "[]").left().width(280f);
            // 状态用文字写明、按钮只写"动作" —— 免得把"可组合"当成"点一下就能组合"，
            // 结果点下去反而把它关掉了。
            if (kind != 0) {
              // 替换接管 / 本来就不组合：这两类不吃设置里的开关
              row.add(kind == 1 ? "[gray]由组合方块接管[]" : "[darkGray]不参与组合[]")
                  .left().width(150f).padLeft(6f);
            } else if (blockedNow) {
              row.add("[scarlet]已关闭组合[]").left().width(110f).padLeft(6f);
              row.button("恢复组合", () -> {
                CoopCombo.setBlocked(b.name, false);
                defer(rebuild);
              }).width(120f).height(30f).padLeft(6f);
            } else {
              row.add("[accent]组合已开启[]").left().width(110f).padLeft(6f);
              row.button("关闭组合", () -> {
                CoopCombo.setBlocked(b.name, true);
                defer(rebuild);
              }).width(120f).height(30f).padLeft(6f);
            }
          }).left().row();
        }
        if (!searching && blocks.size >= 120)
          list.add("[gray]…只显示前 120 个，输入名字搜索全部[]").left().padTop(4f).row();
      } catch (Throwable t) {
        Log.err("[combine] 建筑组合开关列表绘制失败", t);
      }
    };

    table.add("[accent]组合工厂[] [lightgray]· 建筑组合开关").left().padBottom(4f).row();
    table.add("[lightgray]只影响 js/java 功能扩展建筑（协作组合接管的那些）。[]").left().row();
    table.add("[lightgray]「关闭组合」立刻生效：这台建筑不再共享物品/液体/电力，容量还原。[]").left().row();
    table.add("[lightgray]名单存在设置里（本机偏好），不会改动内容表 —— 联机两端不会因为本地开关错位。[]")
        .left().padBottom(8f).row();

    TextField field = new TextField();
    field.setMessageText("搜索建筑（英文名 / 中文名）");
    field.changed(() -> {
      query[0] = field.getText();
      rebuild[0].run();
    });
    table.add(field).width(430f).left().row();

    table.table(filters -> {
      filters.left();
      String[] names = {"只看扩展建筑", "只看会组合的", "全部方块"};
      for(int i = 0; i < 3; i++){
        final int mode = i;
        filters.button(names[i], () -> {
          filter[0] = mode;
          rebuild[0].run();
        }).width(150f).height(30f).padRight(6f);
      }
    }).left().padTop(4f).row();

    table.table(btns -> {
      btns.left();
      btns.button("全部可组合", () -> {
        for (Block b : list(query[0], 0, filter[0]))
          CoopCombo.setBlocked(b.name, false);
        defer(rebuild);
      }).width(140f).height(30f).padRight(6f);
      btns.button("全部不组合", () -> {
        for (Block b : list(query[0], 0, filter[0]))
          CoopCombo.setBlocked(b.name, true);
        defer(rebuild);
      }).width(140f).height(30f).padRight(6f);
      btns.button("清空手动名单", () -> {
        for (String name : CoopCombo.blockedNames())
          CoopCombo.setBlocked(name, false);
        defer(rebuild);
      }).width(160f).height(30f);
    }).left().padTop(6f).row();

    table.label(() -> {
      int total = Vars.content.blocks().size;
      int ext = extensions("").size;
      int off = CoopCombo.blockedNames().size;
      return "[lightgray]共 " + total + " 个方块（其中 " + ext + " 个扩展建筑会组合），"
          + Strings.fixed(off, 0) + " 个被标为不组合[]";
    }).left().padTop(4f).row();

    ScrollPane pane = new ScrollPane(list);
    pane.setFadeScrollBars(false);
    pane.setScrollingDisabledX(true);
    table.add(pane).width(460f).height(360f).padTop(6f).left().row();

    rebuild[0].run();
  }
}
