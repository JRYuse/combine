package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："两个组合体之间的组合节点太多的话无法组合"。
 *
 * 场景：两台组合工厂（相隔很远，靠不到一起），中间摆一串组合节点（点连线接力）——
 * 组网 BFS 是普通广度优先、没有深度上限，理论上多少台都该连成一张网络。
 * 这个测试就逐个长度试：1 / 2 / 3 / 5 / 8 台节点接力，两台工厂必须共用同一份物品池。
 */
public class NodeChainTest implements ApplicationListener {
  static String dataDir = "/tmp/mp_cj/data";
  static int pass = 0, fail = 0;
  public static void main(String[] a) {
    if (a.length > 0) dataDir = a[0];
    Vars.platform = new Platform() {};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if (l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[NC] " + t); };
    new HeadlessApplication(new NodeChainTest(), t -> t.printStackTrace());
  }
  static void check(String n, boolean ok) { System.out.println("[NC] " + (ok ? "PASS " : "FAIL ") + n); if (ok) pass++; else fail++; }
  static void run(int f) { for (int i = 0; i < f; i++) { Time.delta = 1f; Vars.logic.update(); } }
  static Block find(String name) { for (Block b : Vars.content.blocks()) if (b.name.equals(name)) return b; return null; }
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
    } catch (Throwable t) { System.out.println("[NC] 连线失败: " + t); }
  }
  static void clear() {
    for (int y = 40; y < 140; y++) for (int x = 20; x < 240; x++) {
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
      Vars.state.rules.canGameOver = false;
      Vars.state.rules.waves = false;
      run(20);
      clear();
      place(Blocks.coreShard, 60, 60, Team.sharded);
      run(10);

      // 优先用组合工厂（替换出来的）；没有就退到协作组合的 js/java 扩展建筑（例如废土科技的冶炼厂）
      Block kiln = find("kiln");
      if (kiln == null) kiln = find("Smelter");
      if (kiln == null) kiln = find("smelter");
      Block nodeB = byClass("combine.net.ComboNode");
      if (kiln == null || nodeB == null) { System.out.println("[NC] 缺方块"); System.exit(3); }
      Object maxNodes = null;
      try { maxNodes = nodeB.getClass().getField("maxNodes").get(nodeB); } catch (Throwable ignored) { }
      System.out.println("[NC] 组合节点 maxNodes=" + maxNodes);

      int[] lengths = {2, 8, 24};
      for (int n : lengths) {
        clear();
        // 两台工厂隔开（靠不到一起），中间夹 n 台节点：首尾节点必须落在激光连接范围内
        int step = 4;                       // 节点间距（< laserRange）
        int b1x = 40;
        int b2x = b1x + 4 + n * step;       // 末尾节点右边 4 格 = 第二台工厂
        place(find("power-source"), b1x, 84, Team.sharded);
        Building a = place(kiln, b1x, 80, Team.sharded);
        Building b = place(kiln, b2x, 80, Team.sharded);
        run(10);
        boolean alone = a.items != b.items;
        Building prev = null;
        Building first = null, lastN = null;
        for (int i = 0; i < n; i++) {
          Building nd = place(nodeB, b1x + 4 + i * step, 80, Team.sharded);
          if (nd == null) { System.out.println("[NC] 第 " + i + " 台节点摆不下"); break; }
          if (i == 0) first = nd;
          if (prev != null) link(prev, nd);
          prev = nd;
          lastN = nd;
        }
        run(20);
        if (first != null) link(first, a);
        if (lastN != null) link(lastN, b);
        run(60);
        boolean merged = a.items == b.items;
        Object linksObj = first == null ? null : fieldOf(first, "links");
        int aLinks = linksObj instanceof arc.struct.IntSeq is ? is.size : -1;
        System.out.println("[NC] 节点数=" + n + "：两台工厂相距 " + (b2x - b1x) + " 格，原本各自一池=" + alone + " 接完后同池=" + merged
            + "（首节点 links=" + aLinks + "）");
        check("节点接力 " + n + " 台：两台组合工厂仍然组合在一起", merged);
      }

      System.out.println("[NC] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
  }
  static Object fieldOf(Object o, String name) {
    try {
      Class<?> k = o.getClass();
      while (k != null) {
        try { var f = k.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
        catch (NoSuchFieldException e) { k = k.getSuperclass(); }
      }
    } catch (Throwable ignored) { }
    return null;
  }
}
