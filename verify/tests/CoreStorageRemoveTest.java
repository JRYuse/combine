package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："拆除与贴紧核心的仓库连接的建筑时有概率清空核心物品"。
 *
 * 场景：核心 + 贴着核心的仓库（池子就是核心那一份）+ 用组合节点/组合连接器把仓库和
 * 别的建筑接成一张网络，然后把那台建筑拆掉 —— 核心库存必须一份不少。
 * 逐个试：节点连的工厂 / 节点连的第二个容器 / 连接器连的工厂 / 直接拆掉节点本身 /
 * 拆掉第二台容器。每一步都比"核心三件套"和"按模块身份去重的世界总量"。
 */
public class CoreStorageRemoveTest implements ApplicationListener {
  static String dataDir = "/tmp/mp_cj/data";
  static int pass = 0, fail = 0;
  static Block core, cont, kiln, pulv, node, conn;

  public static void main(String[] a) {
    if (a.length > 0) dataDir = a[0];
    Vars.platform = new Platform() {};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if (l.ordinal() >= arc.util.Log.LogLevel.err.ordinal()) System.out.println("[CS] " + t); };
    new HeadlessApplication(new CoreStorageRemoveTest(), t -> t.printStackTrace());
  }
  static void check(String n, boolean ok) { System.out.println("[CS] " + (ok ? "PASS " : "FAIL ") + n); if (ok) pass++; else fail++; }
  static void run(int f) { for (int i = 0; i < f; i++) { arc.util.Time.delta = 1f; Vars.logic.update(); } }
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
  static int worldTotal() {
    java.util.IdentityHashMap<mindustry.world.modules.ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
    int total = 0;
    for (Tile t : Vars.world.tiles) {
      if (t == null || t.build == null || t.build.items == null) continue;
      if (seen.put(t.build.items, Boolean.TRUE) != null) continue;
      total += t.build.items.total();
    }
    return total;
  }
  static boolean coreOk(Building c) {
    return c.items.get(Items.copper) == 800 && c.items.get(Items.lead) == 400 && c.items.get(Items.graphite) == 300;
  }
  static String inv(Building b) {
    return b == null ? "null" : b.block.name + "{总=" + (b.items == null ? -1 : b.items.total()) + "}";
  }
  static void clearArea() {
    for (int y = 25; y < 175; y++) for (int x = 10; x < 250; x++) {
      Tile t = Vars.world.tile(x, y);
      if (t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
    }
    run(5);
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
      run(20);
      clearArea();

      core = findExact("core-shard");
      cont = findExact("container");
      kiln = findExact("kiln");
      pulv = findExact("pulverizer");
      node = byClass("combine.net.ComboNode");
      conn = byClass("combine.net.ComboConnector");
      if (core == null || cont == null || kiln == null || pulv == null || node == null || conn == null) {
        System.out.println("[CS] 缺方块"); System.exit(3);
      }

      // 每个变体都从这里重新搭（核心 + 贴着核心的容器 + 灌满），返回 {核心, 容器}
      // 变体 1：节点连的工厂，直接拆掉工厂
      runVariant("1 节点+工厂，拆工厂", (c, k, extra) -> {
        Building f = place(kiln, 72, 60, Team.sharded);
        run(20);
        link(extra, k); link(extra, f);
        run(30);
        print(c, k, f, extra, "接上后");
        Vars.world.tile(f.tileX(), f.tileY()).setBlock(Blocks.air);
        run(60);
        print(c, k, f, extra, "拆掉工厂后");
      }, true);

      // 变体 2：节点连的第二个容器，拆掉它
      runVariant("2 节点+第二个容器，拆第二个容器", (c, k, extra) -> {
        Building k2 = place(cont, 72, 60, Team.sharded);
        run(20);
        link(extra, k); link(extra, k2);
        run(30);
        print(c, k, k2, extra, "接上后");
        Vars.world.tile(k2.tileX(), k2.tileY()).setBlock(Blocks.air);
        run(60);
        print(c, k, k2, extra, "拆掉第二个容器后");
      }, true);

      // 变体 3：直接拆掉节点本身（连接件）
      runVariant("3 节点+工厂，拆掉节点", (c, k, extra) -> {
        Building f = place(kiln, 72, 60, Team.sharded);
        run(20);
        link(extra, k); link(extra, f);
        run(30);
        print(c, k, f, extra, "接上后");
        Vars.world.tile(extra.tileX(), extra.tileY()).setBlock(Blocks.air);
        run(60);
        print(c, k, f, extra, "拆掉节点后");
      }, true);

      // 变体 4：组合连接器贴脸连的工厂，拆掉工厂
      runVariant("4 连接器+工厂，拆工厂", (c, k, extra) -> {
        // 连接器贴脸即连：容器 - 连接器 - 工厂 排一条
        Building cc = place(conn, 66, 60, Team.sharded);
        Building f = place(kiln, 70, 60, Team.sharded);
        run(30);
        print(c, k, f, cc, "接上后");
        Vars.world.tile(f.tileX(), f.tileY()).setBlock(Blocks.air);
        run(60);
        print(c, k, f, cc, "拆掉工厂后");
      }, true);

      System.out.println("[CS] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
  }

  interface Variant { void run(Building core, Building cont, Building linker); }

  static void runVariant(String name, Variant v, boolean withNode) {
    clearArea();
    Building c = place(core, 60, 60, Team.sharded);
    run(5);
    Building k = place(cont, 63, 60, Team.sharded);
    run(20);
    c.items.add(Items.copper, 800); c.items.add(Items.lead, 400); c.items.add(Items.graphite, 300);
    run(20);
    boolean merged = c.items == k.items;
    System.out.println("[CS] === 变体 " + name + "：核心=" + inv(c) + " 容器=" + inv(k) + " 同池=" + merged);
    if (!merged) {
      check(name + "（前置：容器确实并进核心）", false);
      return;
    }
    int total0 = worldTotal();
    Building linker = withNode ? place(node, 66, 60, Team.sharded) : null;
    run(10);
    try {
      v.run(c, k, linker);
    } catch (Throwable t) {
      System.out.println("[CS] 变体 " + name + " 抛异常: " + t);
    }
    System.out.println("[CS] " + name + " 结束：核心=" + inv(c) + " 容器=" + inv(k) + " 世界总量=" + worldTotal() + "（初始 " + total0 + "）");
    check(name + "：核心库存一份不少", coreOk(c));
    check(name + "：世界总量没过路丢/涨（" + total0 + " → " + worldTotal() + "）", worldTotal() == total0);
  }

  static void link(Building node, Building target) {
    try {
      var m = node.getClass().getMethod("onConfigureBuildTapped", Building.class);
      m.setAccessible(true);
      m.invoke(node, target);
    } catch (Throwable t) {
      System.out.println("[CS] 连线失败: " + t);
    }
  }

  static void print(Building c, Building k, Building f, Building linker, String tag) {
    System.out.println("[CS] " + tag + ": 核心=" + inv(c) + " 容器=" + inv(k) + " 那台=" + inv(f)
        + " 核心池==" + (c.items == k.items ? "容器" : "不是容器")
        + " 世界总量=" + worldTotal());
  }
}
