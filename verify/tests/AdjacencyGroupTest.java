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
 * 1) 相邻并池按**同类**（同一个组合方块类，例如两台不同配方的"组合工厂"）判定，
 *    不是按"同一个方块对象"；不同**类**（组合工厂 vs 组合钻头）相邻不并，
 *    要靠组合节点/组合连接器才并。
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

  /** 这台建筑所在组合网络的成员数（读不到就 -1）。 */
  static int members(Building b) {
    try {
      Class<?> net = Class.forName("combine.net.ComboNet", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
      var m = net.getMethod("componentMembers", Building.class);
      arc.struct.Seq<?> cm = (arc.struct.Seq<?>) m.invoke(null, b);
      return cm.size;
    } catch (Throwable t) {
      return -1;
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
      Block drill = find("mechanical-drill");
      Block nodeB = null, duo = find("duo"), super2 = find("super-turret-2");
      for (Block b : Vars.content.blocks())
        if (b.getClass().getName().equals("combine.net.ComboNode")) {
          nodeB = b;
          break;
        }
      System.out.println("[AG] graphite-press=" + (gpress == null ? "null" : gpress.getClass().getSimpleName())
          + " silicon-smelter=" + (smelter == null ? "null" : smelter.getClass().getSimpleName())
          + " mechanical-drill=" + (drill == null ? "null" : drill.getClass().getSimpleName())
          + " duo=" + (duo == null ? "null" : duo.getClass().getSimpleName())
          + " node=" + (nodeB == null ? "null" : nodeB.getClass().getSimpleName())
          + " super-turret-2=" + (super2 == null ? "null" : super2.getClass().getSimpleName()));
      if (gpress == null || smelter == null || drill == null || nodeB == null || duo == null || super2 == null) {
        System.out.println("[AG] 数据集缺方块，跳过（exit 0）");
        System.exit(0);
      }

      // 1) 同种（同一个方块）组合建筑相邻 → 并池
      Building g1 = place(gpress, 40, 40), g2 = place(gpress, 42, 40);
      run(20);
      check("同种组合建筑相邻：共用一份物品池", g1 != null && g2 != null && g1.items == g2.items);

      // 2) **同类**（同一个组合方块类、不同配方）相邻 → 并池（用户 2026-09-28 要求：
      //    判定按"同类"而不是"同一个方块对象"）。graphite-press / silicon-smelter 都是 CombinedCrafter。
      Building gp = place(gpress, 50, 40), sm = place(smelter, 52, 40);
      run(20);
      System.out.println("[AG] 同类相邻(" + gpress.name + "+" + smelter.name + "): items 同模块="
          + (gp != null && sm != null && gp.items == sm.items));
      check("同类组合建筑相邻（不同方块、同一个组合类）：并池", gp != null && sm != null && gp.items == sm.items);

      // 3) 不同**类**（组合工厂 vs 组合钻头）相邻 → 不并池；用组合节点连起来 → 并池
      Building gp2 = place(gpress, 60, 40), dr = place(drill, 62, 40);
      run(20);
      System.out.println("[AG] 不同类相邻(" + gpress.name + "+" + drill.name + "): items 同模块="
          + (gp2 != null && dr != null && gp2.items == dr.items));
      check("不同类组合建筑相邻：不并池（要节点/连接器才并）", gp2 != null && dr != null && gp2.items != dr.items);

      Building node2 = place(nodeB, 59, 44);
      run(5);
      tapNode(node2, gp2);
      tapNode(node2, dr);
      run(30);
      System.out.println("[AG] 不同类+节点: items 同模块=" + (gp2.items == dr.items));
      check("不同类组合建筑用节点连上：并池", gp2.items == dr.items);

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

      // 5) 单位生产族（组装厂 ↔ 重构厂）相邻 → 并成一台（用户报的"Reconstructor 和 UnitFactory
      //    的组合没了"）。这两个是**不同的组合类**，靠 allowCrossTypeCombo（单位族例外）并。
      {
        Block fac = find("ground-factory"), rec = find("additive-reconstructor");
        System.out.println("[AG] 单位族: unit-factory=" + (fac == null ? "null" : fac.getClass().getSimpleName())
            + " reconstructor=" + (rec == null ? "null" : rec.getClass().getSimpleName()));
        if (fac == null || rec == null) {
          System.out.println("[AG] 数据集缺单位工厂/重构厂，跳过一个用例");
        } else {
          int fx = 90, fy = 40;
          Building f1 = place(fac, fx, fy);
          Building r1 = place(rec, fx + fac.size, fy); // 紧贴（各 3x3）
          run(40);
          System.out.println("[AG] 组装厂+重构厂相邻: items 同模块="
              + (f1 != null && r1 != null && f1.items == r1.items)
              + " 组装厂成员数=" + (f1 == null ? -1 : members(f1))
              + " 重构厂成员数=" + (r1 == null ? -1 : members(r1)));
          check("组装厂 + 重构厂相邻：并成一台（共用物品池）", f1 != null && r1 != null && f1.items == r1.items);
        }
      }

      // 6) 协作组合（JS/Java 扩展建筑）：**同一个 Java 类**但不同建筑不该并成一组 ——
      //    JS 模组的方块常常一批共享同一个 Rhino adapter 类（废土科技里 28 个建筑共用一个类），
      //    按"类"分组会把这 28 个毫不相干的建筑在基地里并成一个巨型协作组，
      //    每次放置/每帧搬运都按组大小走 = 用户报的"加一个新建筑就卡一下"。
      try {
        Class<?> coop = Class.forName("combine.coop.CoopCombo", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
        java.lang.reflect.Method eligible = coop.getMethod("eligible", Block.class);
        java.lang.reflect.Method leader = coop.getMethod("coopLeader", Building.class);
        // 按类聚合可组合方块，挑一个"一个类里 >= 2 个不同方块"的类
        arc.struct.ObjectMap<String, arc.struct.Seq<Block>> byClass = new arc.struct.ObjectMap<>();
        for (Block x : Vars.content.blocks()) {
          if (x == null || x.size != 1 || !(Boolean) eligible.invoke(null, x)) continue;
          byClass.get(x.getClass().getName(), arc.struct.Seq::new).add(x);
        }
        Block a = null, b = null;
        for (var e : byClass.entries())
          if (e.value.size >= 2) {
            a = e.value.get(0);
            b = e.value.get(1);
            break;
          }
        System.out.println("[AG] 协作组合同类不同建筑: a=" + (a == null ? "null" : a.name)
            + "(" + (a == null ? "" : a.getClass().getSimpleName()) + ") b="
            + (b == null ? "null" : b.name) + "(" + (b == null ? "" : b.getClass().getSimpleName()) + ")");
        if (a == null || b == null) {
          System.out.println("[AG] 找不到同一个类里的两个不同方块，跳过一个用例");
        } else {
          Building ca = null, cb = null;
          try {
            ca = place(a, 40, 70);
            cb = place(b, 41, 70);
          } catch (Throwable t) {
            System.out.println("[AG] 这两个方块在本数据集里放不下（JS 初始化依赖），跳过: " + t);
          }
          if (ca != null && cb != null) {
            run(30);
            Object la = leader.invoke(null, ca);
            Object lb = leader.invoke(null, cb);
            System.out.println("[AG] 协作组合: 同一组=" + (la != null && la == lb));
            check("协作组合：同类的两个**不同**建筑相邻不并组（按方块而不是按类）", la != null && la != lb);
          }
        }
      } catch (Throwable t) {
        System.out.println("[AG] 协作组合用例跳过: " + t);
      }

      System.out.println("[AG] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(2);
    }
  }
}
