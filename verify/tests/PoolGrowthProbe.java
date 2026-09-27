package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.ObjectSet; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.Item; import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.ItemModule;

/**
 * 用户报的"物品数量异常增长"探针：读用户存档 → 存 → 读，每一步都把
 * "按模块身份去重的世界物品总量"和几个关键池子的内容打出来，
 * 用来定位增长发生在哪一步（存档写坏了 / 读档合并翻倍 / 运行期自己涨）。
 *
 * 用法：verify/run-headless.sh mx /tmp/mp_213/data combine.dbg.PoolGrowthProbe
 */
public class PoolGrowthProbe implements ApplicationListener {
  static String dataDir = "/tmp/mp_213/data";
  static int pass = 0, fail = 0;

  public static void main(String[] a) {
    if (a.length > 0) dataDir = a[0];
    Vars.platform = new Platform() {};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if (l.ordinal() >= arc.util.Log.LogLevel.info.ordinal()) System.out.println("[L] " + t); };
    new HeadlessApplication(new PoolGrowthProbe(), t -> t.printStackTrace());
  }
  static void check(String n, boolean ok) { System.out.println("[PG] " + (ok ? "PASS " : "FAIL ") + n); if (ok) pass++; else fail++; }
  static void run(int f) { for (int i = 0; i < f; i++) { Time.delta = 1f; Vars.logic.update(); } }

  static Seq<Building> allBuildings() {
    Seq<Building> out = new Seq<>();
    ObjectSet<Building> seen = new ObjectSet<>();
    for (Tile t : Vars.world.tiles) {
      Building b = t == null ? null : t.build;
      if (b != null && seen.add(b)) out.add(b);
    }
    return out;
  }
  static int worldTotal() {
    java.util.IdentityHashMap<ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
    int total = 0;
    for (Building b : allBuildings()) {
      if (b.items == null || seen.put(b.items, Boolean.TRUE) != null) continue;
      total += b.items.total();
    }
    return total;
  }
  /** 按"模块身份"列出所有 >1 万库存的池子（带坐标）。 */
  static void dump(String tag) {
    System.out.println("[PG] " + tag + " 世界总量(去重)=" + worldTotal());
    java.util.IdentityHashMap<ItemModule, Building> reps = new java.util.IdentityHashMap<>();
    int n = 0;
    for (Building b : allBuildings()) {
      if (b == null || b.items == null || !b.isValid()) continue;
      if (reps.putIfAbsent(b.items, b) != null) continue;
      int t = b.items.total();
      if (t < 10000) continue;
      StringBuilder sb = new StringBuilder();
      for (Item it : Vars.content.items())
        if (b.items.get(it) > 0) sb.append(it.name).append('=').append(b.items.get(it)).append(' ');
      System.out.println("[PG]   @" + b.block.name + "@" + b.tileX() + "," + b.tileY()
          + " 模块=" + System.identityHashCode(b.items) + " 合计=" + t
          + " 分量=" + componentOf(b) + "  " + sb);
      n++;
      if (n > 8) break;
    }
  }
  /** "分量成员数/不同模块数/该模块被几台共用"。 */
  static String componentOf(Building b) {
    try {
      ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
      Class<?> net = Class.forName("combine.net.ComboNet", true, ml);
      @SuppressWarnings("unchecked")
      Seq<Building> members = (Seq<Building>) net.getMethod("componentMembers", Building.class).invoke(null, b);
      java.util.IdentityHashMap<ItemModule, Integer> counts = new java.util.IdentityHashMap<>();
      for (Building m : members)
        if (m.items != null) counts.merge(m.items, 1, Integer::sum);
      return members.size + "/" + counts.size() + "/" + counts.getOrDefault(b.items, 0);
    } catch (Throwable t) {
      return "?";
    }
  }
  /** 某台建筑所在分量里，各成员手里模块的"身份计数"（>1 说明还有多份副本）。 */
  static void moduleSpread(String blockName) {
    try {
      ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
      Class<?> net = Class.forName("combine.net.ComboNet", true, ml);
      for (Building b : allBuildings()) {
        if (b == null || b.block == null || !b.block.name.equals(blockName) || b.items == null) continue;
        @SuppressWarnings("unchecked")
        Seq<Building> members = (Seq<Building>) net.getMethod("componentMembers", Building.class)
            .invoke(null, b);
        java.util.IdentityHashMap<ItemModule, Integer> counts = new java.util.IdentityHashMap<>();
        for (Building m : members)
          if (m.items != null) counts.merge(m.items, 1, Integer::sum);
        System.out.println("[PG]   @" + b.block.name + "@" + b.tileX() + "," + b.tileY()
            + " 分量成员=" + members.size + " 不同模块数=" + counts.size()
            + " 各模块成员数=" + counts.values());
        return;
      }
    } catch (Throwable t) {
      System.out.println("[PG] moduleSpread 失败: " + t);
    }
  }

  @Override public void init() {
    try {
      Core.settings.setDataDirectory(Core.files.local(dataDir));
      Vars.loadLocales = false; Vars.loadSettings(); Vars.headless = true; Vars.init();
      UI.loadColors(); Fonts.loadContentIconsHeadless();
      Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
      Vars.mods.eachClass(Mod::init);
      if (Vars.logic == null) Vars.logic = new Logic();
      if (Vars.netServer == null) Vars.netServer = new NetServer();
      if (Vars.netClient == null) Vars.netClient = new NetClient();
      Map fallback = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(fallback, fallback.applyRules(Gamemode.survival));
      Vars.logic.play();
      run(10);

      arc.files.Fi file = null;
      for (arc.files.Fi f : Vars.saveDirectory.list())
        if (f.name().endsWith(".msav") && !f.name().contains("backup")) { file = f; break; }
      if (file == null) { System.out.println("[PG] 没有存档"); System.exit(3); }
      System.out.println("[PG] 读档 " + file.name());
      SaveIO.load(file);
      if (Vars.state.isPaused()) Vars.state.set(GameState.State.playing);
      System.out.println("[PG] 读档刚回来（还没跑 tick）:");
      dump("  刚读档");
      moduleSpread("blast-drill");
      moduleSpread("container");
      run(1); dump("  run 1 tick");
      run(4); dump("  run 5 tick");
      run(10); dump("  run 15 tick");
      run(45); dump("  run 60 tick");
      moduleSpread("blast-drill");
      moduleSpread("container");

      for (int cycle = 1; cycle <= 3; cycle++) {
        arc.files.Fi tmp = Core.files.absolute("/tmp/pg/save-cycle" + cycle + ".msav");
        tmp.parent().mkdirs();
        SaveIO.save(tmp);
        System.out.println("[PG] 第 " + cycle + " 轮：存完 ");
        dump("  存完");
        SaveIO.load(tmp);
        if (Vars.state.isPaused()) Vars.state.set(GameState.State.playing);
        dump("  读回来（未跑 tick）");
        run(1);
        dump("  读回来 run 1 tick");
        run(59);
        dump("  读回来 run 60 tick");
        moduleSpread("blast-drill");
        moduleSpread("container");
      }

      System.out.println("[PG] RESULT " + (fail == 0 ? "done" : fail + " FAILED") + " (pass=" + pass + ")");
      Core.app.exit();
    } catch (Throwable t) { t.printStackTrace(); Core.app.exit(); }
  }
}
