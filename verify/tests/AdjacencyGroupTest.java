package combine.dbg;

import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.Logic;
import mindustry.core.NetClient;
import mindustry.core.NetServer;
import mindustry.core.Platform;
import mindustry.game.Gamemode;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.maps.Map;
import mindustry.mod.Mod;
import mindustry.net.Net;
import mindustry.ui.Fonts;
import mindustry.world.Block;
import mindustry.world.Tile;

/**
 * 用户 2026-09-28 要求：
 * 1) 不同的组合建筑**相邻**不再自动并成一台；只有**同种**组合建筑相邻才并，
 *    不同种的要靠组合节点/组合连接器才并。
 * 2) 合体炮台（SuperTurret）应该能和组合炮台（CombinedTurret 族）组合。
 */
public class AdjacencyGroupTest implements arc.ApplicationListener {
  static String dataDir = "/tmp/mp_cj/data";
  static int pass = 0, fail = 0;

  public static void main(String[] a) {
    if (a.length > 0)
      dataDir = a[0];
    Vars.platform = new Platform() {
    };
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> {
      if (l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal())
        System.out.println("[AG] " + t);
    };
    new HeadlessApplication(new AdjacencyGroupTest(), t -> t.printStackTrace());
  }

  static void check(String n, boolean ok) {
    System.out.println("[AG] " + (ok ? "PASS " : "FAIL ") + n);
    if (ok)
      pass++;
    else
      fail++;
  }

  static void run(int frames) {
    for (int i = 0; i < frames; i++) {
      arc.util.Time.delta = 1f;
      Vars.logic.update();
    }
  }

  static Building place(Block b, int x, int y) {
    int size = Math.max(b.size, 1);
    int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
    mindustry.world.Build.beginPlace(null, b, Team.sharded, ax, ay, 0, null);
    mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte) 0, Team.sharded, null);
    Building bu = Vars.world.build(ax, ay);
    if (bu != null)
      try {
        bu.updateProximity();
      } catch (Throwable ignored) {
      }
    return bu;
  }

  static void tapNode(Building node, Building target) {
    try {
      var m = node.getClass().getMethod("onConfigureBuildTapped", Building.class);
      m.setAccessible(true);
      m.invoke(node, target);
    } catch (Throwable t) {
      System.out.println("[AG] 节点连线失败: " + t);
    }
  }

  static Block find(String name) {
    for (Block b : Vars.content.blocks())
      if (b.name.equals(name))
        return b;
    return null;
  }

  @Override
  public void init() {
    try {
      Core.settings.setDataDirectory(Core.files.local(dataDir));
      Vars.loadLocales = false;
      Vars.loadSettings();
      Vars.headless = true;
      Vars.init();
      mindustry.core.UI.loadColors();
      Fonts.loadContentIconsHeadless();
      Vars.content.createBaseContent();
      Vars.mods.loadScripts();
      Vars.content.createModContent();
      Vars.content.init();
      Vars.mods.eachClass(Mod::init);
      if (Vars.logic == null)
        Vars.logic = new Logic();
      if (Vars.netServer == null)
        Vars.netServer = new NetServer();
      if (Vars.netClient == null)
        Vars.netClient = new NetClient();
      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.logic.play();
      run(20);
      for (int y = 30; y < 170; y++)
        for (int x = 10; x < 250; x++) {
          Tile t = Vars.world.tile(x, y);
          if (t != null && t.block() != Blocks.air)
            t.setBlock(Blocks.air);
        }
      run(5);

      Block gpress = find("graphite-press"), smelter = find("silicon-smelter");
      Block nodeB = null, duo = find("duo"), super2 = find("super-turret-2");
      for (Block b : Vars.content.blocks())
        if (b.getClass().getName().equals("combine.net.ComboNode")) {
          nodeB = b;
          break;
        }
      System.out.println("[AG] graphite-press=" + (gpress == null ? "null" : gpress.getClass().getSimpleName())
          + " silicon-smelter=" + (smelter == null ? "null" : smelter.getClass().getSimpleName())
          + " duo=" + (duo == null ? "null" : duo.getClass().getSimpleName())
          + " node=" + (nodeB == null ? "null" : nodeB.getClass().getSimpleName())
          + " super-turret-2=" + (super2 == null ? "null" : super2.getClass().getSimpleName()));
      if (gpress == null || smelter == null || nodeB == null || duo == null || super2 == null) {
        System.out.println("[AG] 数据集缺方块，跳过（exit 0）");
        System.exit(0);
      }

      // 1) 同种组合建筑相邻 → 并池
      Building g1 = place(gpress, 40, 40), g2 = place(gpress, 42, 40);
      run(20);
      check("同种组合建筑相邻：共用一份物品池", g1 != null && g2 != null && g1.items == g2.items);

      // 2) 不同种组合建筑相邻 → 不并池
      Building gp = place(gpress, 50, 40), sm = place(smelter, 52, 40);
      run(20);
      System.out.println("[AG] 不同种相邻: items 同模块=" + (gp != null && sm != null && gp.items == sm.items));
      check("不同种组合建筑相邻：不并池（要节点/连接器才并）", gp != null && sm != null && gp.items != sm.items);

      // 3) 不同种组合建筑用组合节点连起来 → 并池
      Building node = place(nodeB, 49, 44);
      run(5);
      tapNode(node, gp);
      tapNode(node, sm);
      run(30);
      System.out.println("[AG] 不同种+节点: items 同模块=" + (gp.items == sm.items));
      check("不同种组合建筑用节点连上：并池", gp.items == sm.items);

      // 4) 合体炮台 + 组合炮台相邻 → 并池（用户要的例外）
      Building sup = place(super2, 70, 40);      // size2 → 占 70..71
      Building dt = place(duo, 72, 40);          // size1，紧贴 71
      run(30);
      System.out.println("[AG] 合体炮台+组合炮台相邻: 液池同模块="
          + (sup != null && dt != null && sup.liquids == dt.liquids));
      try {
        Class<?> net = Class.forName("combine.net.ComboNet", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
        var m = net.getMethod("componentMembers", Building.class);
        @SuppressWarnings("unchecked")
        arc.struct.Seq<Building> cm = (arc.struct.Seq<Building>) m.invoke(null, sup);
        boolean hasDt = false;
        for (Building b : cm) if (b == dt) hasDt = true;
        System.out.println("[AG] 合体炮台所在网络成员数=" + cm.size + " 含组合炮台=" + hasDt
            + " 成员=" + cm.map(b -> b.block.name));
        try {
          var f = net.getDeclaredField("netByPos");
          f.setAccessible(true);
          Object netMap = f.get(null);
          @SuppressWarnings("unchecked")
          Object list = ((arc.struct.IntMap<Object>) netMap).get(sup.pos());
          System.out.println("[AG] 权威图 netByPos[合体炮台]=" + (list == null ? "null" : list));
        } catch (Throwable t2) {
          System.out.println("[AG] 读 netByPos 失败: " + t2);
        }
      } catch (Throwable t) {
        System.out.println("[AG] 读网络成员失败: " + t);
      }
      check("合体炮台和组合炮台相邻：并成一台（共用液池）", sup != null && dt != null && sup.liquids == dt.liquids);

      System.out.println("[AG] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(2);
    }
  }
}
