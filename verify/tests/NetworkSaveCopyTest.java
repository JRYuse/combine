package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.ObjectSet; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.Item; import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.ItemModule;

/**
 * 用户报的"物品数量异常增长"的定式复现：**一条网络里有多个"本地组合组"**时，
 * 存档是不是把同一口池子写了好几份（读档时每份各成一个模块 → 总量 ×份数）。
 *
 * 布局：核心 + 贴着核心的容器（本地组 A） + 两台相邻窑炉（本地组 B）
 *       + 一个组合节点把容器和窑炉接成**一张网络**。
 * 判定：存→读往返 3 轮，按模块身份去重的世界物品总量必须一分不差
 *       （修前实测：多份副本读回来变成多份真库存，一轮翻一倍）。
 */
public class NetworkSaveCopyTest implements ApplicationListener {
  static String dataDir = "/tmp/mp_cj/data";
  static int pass = 0, fail = 0;
  public static void main(String[] a) {
    if (a.length > 0) dataDir = a[0];
    Vars.platform = new Platform() {};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if (l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[NS] " + t); };
    new HeadlessApplication(new NetworkSaveCopyTest(), t -> t.printStackTrace());
  }
  static void check(String n, boolean ok) { System.out.println("[NS] " + (ok ? "PASS " : "FAIL ") + n); if (ok) pass++; else fail++; }
  static void run(int f) { for (int i = 0; i < f; i++) { Time.delta = 1f; Vars.logic.update(); } }
  static Block findExact(String name) { for (Block b : Vars.content.blocks()) if (b.name.equals(name)) return b; return null; }
  static Block byClass(String cls) { for (Block b : Vars.content.blocks()) if (b.getClass().getName().equals(cls)) return b; return null; }
  static Building place(Block b, int x, int y, Team team) {
    int size = Math.max(b.size, 1);
    int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
    mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
    mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte) 0, team, null);
    Building bu = Vars.world.build(ax, ay);
    if (bu != null) { try { bu.created(); } catch (Throwable ignored) {} try { bu.updateProximity(); } catch (Throwable ignored) {} }
    return bu;
  }
  static void link(Building node, Building target) {
    try {
      var m = node.getClass().getMethod("onConfigureBuildTapped", Building.class);
      m.setAccessible(true);
      m.invoke(node, target);
    } catch (Throwable t) { System.out.println("[NS] 连线失败: " + t); }
  }
  static int worldTotal() {
    java.util.IdentityHashMap<ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
    int total = 0;
    for (Tile t : Vars.world.tiles) {
      Building b = t == null ? null : t.build;
      if (b == null || b.items == null) continue;
      if (seen.put(b.items, Boolean.TRUE) != null) continue;
      total += b.items.total();
    }
    return total;
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
      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.logic.play();
      Vars.state.rules.unitCap = 100;
      run(20);
      for (int y = 25; y < 175; y++) for (int x = 10; x < 250; x++) {
        Tile t = Vars.world.tile(x, y);
        if (t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
      }
      run(5);

      Block core = findExact("core-shard"), cont = findExact("container"), kiln = findExact("kiln");
      Block node = byClass("combine.net.ComboNode");
      if (core == null || cont == null || kiln == null || node == null) {
        System.out.println("[NS] 缺方块"); System.exit(3);
      }
      Building c = place(core, 60, 60, Team.sharded);
      run(5);
      Building k = place(cont, 63, 60, Team.sharded);
      run(20);
      System.out.println("[NS] 核心与容器同池=" + (c.items == k.items));
      check("容器并进核心（同池）", c.items == k.items);

      // 本地组 B：两台相邻窑炉（共用一份池子）
      Building k1 = place(kiln, 69, 60, Team.sharded);
      Building k2 = place(kiln, 71, 60, Team.sharded);
      run(30);
      check("两台相邻窑炉共用一个池子", k1.items == k2.items);

      // 节点把两个本地组接成一张网络
      Building nd = place(node, 66, 60, Team.sharded);
      run(10);
      link(nd, k);
      link(nd, k1);
      run(60);
      check("节点把核心/容器那一组和窑炉那一组接成一张网络（同池）", c.items == k1.items);

      c.items.add(Items.copper, 5000);
      c.items.add(Items.lead, 3000);
      c.items.add(Items.silicon, 2000);
      run(30);
      int start = worldTotal();
      System.out.println("[NS] 初始世界物品总量=" + start);

      int t0 = start;
      for (int cycle = 1; cycle <= 3; cycle++) {
        arc.files.Fi tmp = Core.files.absolute("/tmp/ns/save-cycle" + cycle + ".msav");
        tmp.parent().mkdirs();
        SaveIO.save(tmp);
        SaveIO.load(tmp);
        if (Vars.state.isPaused()) Vars.state.set(GameState.State.playing);
        run(60);
        int t1 = worldTotal();
        System.out.println("[NS] 第 " + cycle + " 轮 存→读：总量 " + t0 + " → " + t1
            + (t1 != t0 ? "（差 " + (t1 - t0) + "，×" + String.format("%.2f", t1 / (double) Math.max(t0, 1)) + "）" : ""));
        check("第 " + cycle + " 轮 存→读总量不变（" + t0 + " → " + t1 + "）", t1 == t0);
        t0 = t1;
      }

      System.out.println("[NS] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
  }
}
