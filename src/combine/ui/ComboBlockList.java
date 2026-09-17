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
      if (combinableByNature(b)) hit.add(b); else rest.add(b);
    }
    hit.sort((a, b2) -> a.name.compareTo(b2.name));
    rest.sort((a, b2) -> a.name.compareTo(b2.name));
    hit.addAll(rest);
    if (cap > 0 && hit.size > cap)
      hit.truncate(cap);
    return hit;
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

  private static void build(SettingsTable table) {
    final String[] query = {""};
    final Table list = new Table();
    list.top().left();
    list.defaults().left();

    final Runnable[] rebuild = new Runnable[1];
    rebuild[0] = () -> {
      try {
        list.clearChildren();
        boolean searching = query[0] != null && !query[0].trim().isEmpty();
        Seq<Block> blocks = list(query[0], searching ? 0 : 120);
        if (blocks.isEmpty()) {
          list.add("[gray]没有匹配的建筑[]").left().row();
          return;
        }
        for (Block b : blocks) {
          final boolean blockedNow = CoopCombo.isBlocked(b.name);
          final boolean handled = combinableByNature(b);
          list.table(row -> {
            row.left();
            if (b.uiIcon != null)
              row.image(b.uiIcon).size(24f).padRight(6f);
            row.add(b.localizedName + "   [gray]" + b.name + "[]").left().width(280f);
            if (blockedNow) {
              row.button("[scarlet]不组合[]", () -> {
                CoopCombo.setBlocked(b.name, false);
                rebuild[0].run();
              }).width(120f).height(30f).padLeft(6f);
            } else if (handled) {
              row.button("[accent]可组合[]", () -> {
                CoopCombo.setBlocked(b.name, true);
                rebuild[0].run();
              }).width(120f).height(30f).padLeft(6f);
            } else {
              // 本来就不参与组合的方块（原版多数方块）：也能屏蔽，但先标注清楚
              row.button("[gray]不参与[]", () -> {
                CoopCombo.setBlocked(b.name, true);
                rebuild[0].run();
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
    table.add("[lightgray]取消后该建筑不再参与组合，名单会写进设置。[]").left().padBottom(8f).row();

    TextField field = new TextField();
    field.setMessageText("搜索建筑（英文名 / 中文名）");
    field.changed(() -> {
      query[0] = field.getText();
      rebuild[0].run();
    });
    table.add(field).width(430f).left().row();

    table.table(btns -> {
      btns.left();
      btns.button("全部可组合", () -> {
        for (Block b : list(query[0], 0))
          CoopCombo.setBlocked(b.name, false);
        rebuild[0].run();
      }).width(140f).height(30f).padRight(6f);
      btns.button("全部不组合", () -> {
        for (Block b : list(query[0], 0))
          CoopCombo.setBlocked(b.name, true);
        rebuild[0].run();
      }).width(140f).height(30f).padRight(6f);
      btns.button("清空手动名单", () -> {
        for (String name : CoopCombo.blockedNames())
          CoopCombo.setBlocked(name, false);
        rebuild[0].run();
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
