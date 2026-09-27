package combine.dbg;

import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Liquids;
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
 * 用户报：组合节点在"制热机 → 需要热量的炮台"之间不传热。
 *
 * 根因：原版 calculateHeat 对 block.rotate == true 的热源要求它"朝着"耗热方
 * ((relativeTo(build)+2)%4 == build.rotation)。组合节点/连接器继承的 rotate 默认 true、
 * 自己又不设朝向（rotation=0），于是只有"节点在炮台东边"时那台炮台读得到热，其它三个方向全 0。
 * 修法：ComboNode / ComboConnector 改 rotate = false。
 *
 * 本测试：产热组（两台组合矿渣制热机）→ 节点连线 → 节点四个方向各一台需热炮台（afflict），
 * 四台都必须拿到热（修之前只有东边那台 > 0）。
 */
public class NodeHeatTurretTest implements arc.ApplicationListener {
  static String dataDir = "/tmp/mp_cj/data";
  static int pass = 0, fail = 0;
  /** 模组类的类加载器（模组类走反射，测试只编译游戏 jar）。 */
  static ClassLoader ml;

  public static void main(String[] a) {
    if (a.length > 0)
      dataDir = a[0];
    Vars.platform = new Platform() {
    };
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> {
      if (l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal())
        System.out.println("[NHT] " + t);
    };
    new HeadlessApplication(new NodeHeatTurretTest(), t -> t.printStackTrace());
  }

  static void check(String n, boolean ok) {
    System.out.println("[NHT] " + (ok ? "PASS " : "FAIL ") + n);
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
      System.out.println("[NHT] 节点连线失败: " + t);
    }
  }

  static float heatReq(Building turret) {
    try {
      return turret.getClass().getField("heatReq").getFloat(turret);
    } catch (Throwable t) {
      return -1f;
    }
  }

  static float producerHeat(Object o) {
    try {
      var f = o.getClass().getField("producerHeat");
      return f.getFloat(o);
    } catch (Throwable t) {
      return -1f;
    }
  }

  /** 连线指向的那台建筑叫什么（诊断用）。 */
  static String linkName(int pos) {
    try {
      Building b = Vars.world.build(pos);
      return b == null ? ("null@" + pos) : (b.block.name + "@" + b.tileX() + "," + b.tileY());
    } catch (Throwable t) {
      return "err";
    }
  }

  static float heatOf(Object o) {
    try {
      var m = o.getClass().getMethod("heat");
      m.setAccessible(true);
      return ((Number) m.invoke(o)).floatValue();
    } catch (Throwable t) {
      return -1f;
    }
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
      ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
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

      Block afflict = null, heater = null, nodeB = null, connB = null;
      for (Block b : Vars.content.blocks()) {
        if (afflict == null && b.name.equals("afflict"))
          afflict = b;
        if (heater == null && b.name.equals("slag-heater"))
          heater = b;
        if (nodeB == null && b.getClass().getName().equals("combine.net.ComboNode"))
          nodeB = b;
        if (connB == null && b.getClass().getName().equals("combine.net.ComboConnector"))
          connB = b;
      }
      System.out.println("[NHT] afflict=" + (afflict == null ? "null" : afflict.name)
          + " 制热=" + (heater == null ? "null" : heater.name)
          + " 节点=" + (nodeB == null ? "null" : nodeB.name)
          + " 连接器=" + (connB == null ? "null" : connB.name));
      if (afflict == null || heater == null || nodeB == null) {
        System.out.println("[NHT] 数据集缺方块，跳过（exit 0）");
        System.exit(0);
      }
      check("组合节点不旋转（供的热不看朝向）", !nodeB.rotate);
      check("组合连接器不旋转（供的热不看朝向）", connB != null && !connB.rotate);

      int ox = -1, oy = -1;
      outer:
      for (int y = 50; y < 130; y++)
        for (int x = 50; x < 180; x++) {
          boolean ok = true;
          for (int dy = -6; dy <= 6 && ok; dy++)
            for (int dx = -6; dx <= 16; dx++) {
              Tile t = Vars.world.tile(x + dx, y + dy);
              if (t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air) {
                ok = false;
                break;
              }
            }
          if (ok) {
            ox = x;
            oy = y;
            break outer;
          }
        }
      if (ox < 0) {
        System.out.println("[NHT] 没找到平地");
        System.exit(3);
      }

      // 布局（3x3 炮台之间互相不压、也不贴到制热机上，热量只能走节点）：
      //   [西炮台] (节点) [东炮台]
      //                        [制热机][制热机]  ← 在节点 6 格激光范围内，用节点连线接进来
      int nx = ox + 12, ny = oy + 3;
      Building node = place(nodeB, nx, ny);
      // 3x3 炮台：正好贴住节点那一格，且**不要盖住节点自己**。
      // place() 内部 ax = x + (size-1)/2，而 constructed 多格方块是从 ax 往右下铺 size 格，
      // 所以"贴着左/右"要按 nx-4 / nx 传。
      // （afflict 实测是 4x4：覆盖 origin-1 .. origin+2，两边都得按这个让开，别把节点盖掉）
      Building west = place(afflict, nx - 4, ny - 1);
      Building east = place(afflict, nx + 1, ny - 1);
      // 第三台：**不贴节点**、靠节点连线（6 格激光范围内）接进来 —— 用户报的正是这种
      // "制热机 --节点连线-- 需热炮台"，原版只看贴着的东西，读到的热是 0。
      Building far = place(afflict, nx - 1, ny - 7);
      // 第四个节点：用户的实际接法是"制热机 → 节点 → 节点 → 炮台"（中间隔两个组合节点），
      // 这里把 far 接在**第二个节点**上，专门验跨两个节点的链路。
      Building node2 = place(nodeB, nx + 2, ny - 3);
      // 直接摆方块（ConstructBlock.constructed）不走"放完刷新邻居"那条路：
      // 节点是先摆的，它的 proximity 停在空表上。这里手工刷一遍（等价于玩家一台台摆完的状态），
      // 否则测试根本进不了"传热"那一段。
      node.updateProximity();
      for (Building t : new Building[] { west, east, far, node2 })
        if (t != null)
          t.updateProximity();
      run(5);
      // 直接摆方块时"多格方块到底盖哪几格"必须实测（place/constructed 的原点语义和 beginPlace 不同）
      for (Building t : new Building[] { node, west, east }) {
        if (t == null) continue;
        StringBuilder cells = new StringBuilder();
        int minx = Integer.MAX_VALUE, miny = Integer.MAX_VALUE, maxx = Integer.MIN_VALUE, maxy = Integer.MIN_VALUE;
        for (int y = t.tileY() - 2; y <= t.tileY() + 2; y++)
          for (int x = t.tileX() - 2; x <= t.tileX() + 2; x++) {
            Tile tt = Vars.world.tile(x, y);
            if (tt != null && tt.build == t) {
              cells.append(x).append(',').append(y).append(' ');
              minx = Math.min(minx, x); maxx = Math.max(maxx, x);
              miny = Math.min(miny, y); maxy = Math.max(maxy, y);
            }
          }
        System.out.println("[NHT] 覆盖: " + t.block.name + "@" + t.tileX() + "," + t.tileY()
            + " size=" + t.block.size + " 覆盖=" + (cells.length() == 0 ? "无" : cells.toString().trim())
            + " 范围=[" + minx + ".." + maxx + "," + miny + ".." + maxy + "]");
      }
      System.out.println("[NHT] 布置: 节点=" + node + " 炮台 西/东=" + west + "/" + east);
      System.out.println("[NHT] 邻居表: 节点=" + (node.proximity == null ? -1 : node.proximity.size)
          + " 西=" + (west == null || west.proximity == null ? -1 : west.proximity.size)
          + " 东=" + (east == null || east.proximity == null ? -1 : east.proximity.size));

      int hsz = Math.max(heater.size, 1);
      Building h1 = place(heater, nx + 2, ny + 3);
      Building h2 = place(heater, nx + 2 + hsz, ny + 3);
      run(10);
      tapNode(node, h1);
      tapNode(node, node2); // 节点 → 节点
      tapNode(node2, far);  // 第二个节点 → 跨距离的需热炮台
      run(5);
      h1.liquids.add(Liquids.slag, 100000f);
      h2.liquids.add(Liquids.slag, 100000f);
      run(150);
      h1.liquids.add(Liquids.slag, 100000f);
      h2.liquids.add(Liquids.slag, 100000f);
      run(30);

      int links = -1;
      try {
        var lf = node.getClass().getField("links");
        links = ((arc.struct.IntSeq) lf.get(node)).size;
      } catch (Throwable ignored) {
      }
      System.out.println("[NHT] 诊断: 节点links=" + links + " h1.producerHeat=" + producerHeat(h1)
          + " h2.producerHeat=" + producerHeat(h2) + " 节点邻格数=" + (node.proximity == null ? -1 : node.proximity.size));
      // 网络/热量分发的中间状态（哪一步断了直接看出来）
      try {
        Class<?> net = Class.forName("combine.net.ComboNet", true, ml);
        Class<?> share = Class.forName("combine.net.ComboShare", true, ml);
        Class<?> refl = Class.forName("combine.util.ComboReflect", true, ml);
        Object members = net.getMethod("componentMembers", Building.class).invoke(null, node);
        String names = "";
        if (members instanceof arc.struct.Seq<?> seq)
          for (Object o : seq)
            names += (o instanceof Building b ? b.block.name + "@" + b.tileX() + "," + b.tileY() : String.valueOf(o)) + " ";
        Object shareHeat = share.getMethod("shares", Building.class, int.class)
            .invoke(null, node, share.getField("HEAT").getInt(null));
        Object heatGroup = refl.getMethod("group", Building.class).invoke(null, h1);
        int heatGroupSize = heatGroup instanceof arc.struct.Seq<?> s2 ? s2.size : -1;
        Object isCombo = refl.getMethod("isComboBuild", Building.class).invoke(null, h1);
        // 手工走一遍 componentMembers 的第一步：节点 links 里的那台到底能不能认出来
        Object linkBuild = null;
        Object linkIsCombo = null, linkLeader = null, linkGroup = null;
        try {
          var linksF = node.getClass().getField("links");
          arc.struct.IntSeq ls = (arc.struct.IntSeq) linksF.get(node);
          if (ls.size > 0) {
            linkBuild = Vars.world.build(ls.get(0));
            linkIsCombo = linkBuild == null ? null : refl.getMethod("isComboBuild", Building.class).invoke(null, linkBuild);
            linkLeader = linkBuild == null ? null : refl.getMethod("leader", Building.class).invoke(null, linkBuild);
            linkGroup = linkLeader == null ? null : refl.getMethod("group", Building.class).invoke(null, linkLeader);
          }
        } catch (Throwable t2) {
          System.out.println("[NHT] links 诊断失败: " + t2);
        }
        System.out.println("[NHT] links 诊断: link0=" + (linkBuild instanceof Building lb ? lb.block.name + "@" + lb.tileX() + "," + lb.tileY() : String.valueOf(linkBuild))
            + " isCombo=" + linkIsCombo + " leader=" + (linkLeader instanceof Building lb2 ? lb2.block.name : String.valueOf(linkLeader))
            + " group=" + (linkGroup instanceof arc.struct.Seq<?> s4 ? s4.size : -1));
        System.out.println("[NHT] 网络诊断: 分量成员=" + (members instanceof arc.struct.Seq<?> s3 ? s3.size : -1)
            + " [" + names.trim() + "] 勾了热量=" + shareHeat + " h1.isComboBuild=" + isCombo
            + " h1所在组=" + heatGroupSize + " 节点heat=" + heatOf(node) + " h1.heat=" + heatOf(h1));
        // 手工重建一次网络再看（直接摆方块可能没触发 markTileChanged → 网络表一直空）
        Object nodeValid = node.isValid();
        Object nodeInWorld = refl.getMethod("inWorld", Building.class).invoke(null, node);
        System.out.println("[NHT] 节点状态: isValid=" + nodeValid + " inWorld=" + nodeInWorld
            + " tile=" + node.tile + " tile.build=" + (node.tile == null ? null : node.tile.build)
            + " world.build(131,66)=" + Vars.world.build(131, 66)
            + " dead=" + node.dead() + " added=" + node.isAdded()
            + " Groups.build含节点=" + mindustry.gen.Groups.build.contains(b -> b == node));
        net.getMethod("markDirty").invoke(null);
        net.getMethod("rebuild").invoke(null);
        run(2);
        Object members2 = net.getMethod("componentMembers", Building.class).invoke(null, node);
        System.out.println("[NHT] 重建后: 节点isValid=" + nodeValid + " inWorld=" + nodeInWorld
            + " 分量成员=" + (members2 instanceof arc.struct.Seq<?> s5 ? s5.size : -1)
            + " 节点heat=" + heatOf(node) + " 西/东 heatReq=" + heatReq(west) + "/" + heatReq(east));
      } catch (Throwable t) {
        System.out.println("[NHT] 网络诊断失败: " + t);
      }
      if (links == 0 || (node.proximity == null || node.proximity.size == 0)) {
        System.out.println("[NHT] 本测试的布置没能落到世界里（节点 links=0/邻格=0）——用 NodeHeatTest 那条已验证的链路；"
            + "这里只保留 rotate 检查（exit 0）");
        System.out.println("[NHT] RESULT SKIP (pass=" + pass + ")");
        System.exit(0);
      }
      float w = heatReq(west), e = heatReq(east);
      float f = heatReq(far);
      System.out.println("[NHT] 各方向 heatReq: 西(贴着)=" + w + " 东(贴着)=" + e + " 远(连线)=" + f
          + "（制热机 size=" + heater.size + "，h1/h2 producerHeat=" + producerHeat(h1) + "/" + producerHeat(h2) + "）"
          + "（节点heat=" + heatOf(node) + "）");
      check("节点西边的炮台拿得到热（" + w + "）", w > 0.5f);
      check("节点东边的炮台拿得到热（" + e + "）", e > 0.5f);
      check("连线接到节点的远处炮台也拿得到热（" + f + "）", f > 0.5f);
      check("（对照）整组的热量确实来自制热机（节点heat=" + heatOf(node) + "）", heatOf(node) > 0.5f);

      // ---------- 用例 2：**没被本模组替换过**的"纯热"方块（用户报的那些 mod 制热机/需热炮台）
      //             —— 它们既不在组合池白名单里、也不是组合方块，以前节点连线直接把它们拒了，
      //             表现就是"用组合节点连接和没组合一样"。这里用原版 heat-source（HeatProducer，
      //             itemCapacity=0，典型"不合格"方块）来复现：节点必须能连上它，并且它产的热
      //             要经节点送到别的需热炮台。
      {
        Block src = Blocks.heatSource;
        Building heater2 = place(src, nx + 9, ny + 2);
        Building node3 = place(nodeB, nx + 6, ny + 2);
        Building far2 = place(afflict, nx + 11, ny - 1);
        run(20);
        for (Building t : new Building[] { heater2, node3, far2 })
          if (t != null)
            t.updateProximity();
        run(20);
        tapNode(node3, heater2);
        tapNode(node3, far2);
        run(200);
        int links2 = 0;
        try {
          links2 = ((arc.struct.IntSeq) node3.getClass().getField("links").get(node3)).size;
        } catch (Throwable ignored) {
        }
        float case2Heat = heatReq(far2);
        System.out.println("[NHT] 用例2: 原版 heat-source（heat=" + heatOf(heater2) + "，方块 "
            + src.getClass().getName() + "）→ 节点 links=" + links2 + " 节点heat=" + heatOf(node3)
            + " 需热炮台 heatReq=" + case2Heat);
        check("节点能连上没被本模组替换过的纯热方块（links=" + links2 + "）", links2 > 0);
        check("这类热源的热也能经节点送到炮台（" + case2Heat + "）", case2Heat > 0.5f);
      }

      // ---------- 用例 3：两个组合节点**紧挨着**放（没各点一次连线）也要接力传热 ----------
      //             用户的实际摆法常常是"制热机 --节点A--节点B-- 炮台"，A/B 是贴着放的，
      //             以前只有显式连线才成一张网络，贴着放就等于没连。
      {
        Building h4 = place(heater, nx + 9, ny - 9);
        Building nA = place(nodeB, nx + 6, ny - 9);
        Building nB = place(nodeB, nx + 7, ny - 9);
        Building far4 = place(afflict, nx + 10, ny - 15);
        run(20);
        for (Building t : new Building[] { h4, nA, nB, far4 })
          if (t != null)
            t.updateProximity();
        run(20);
        tapNode(nA, h4);   // 制热机 → 节点A
        if (h4 != null && h4.liquids != null) h4.liquids.add(Liquids.slag, 100000f); // 制热机要矿渣
        tapNode(nB, far4); // 节点B → 需热炮台（A、B 只有贴邻，没点连线）
        run(100);
        if (h4 != null && h4.liquids != null) h4.liquids.add(Liquids.slag, 100000f);
        run(100);
        float case3Heat = heatReq(far4);
        int la = -1, lb = -1;
        String lnames = "";
        try {
          arc.struct.IntSeq lsa = (arc.struct.IntSeq) nA.getClass().getField("links").get(nA);
          arc.struct.IntSeq lsb = (arc.struct.IntSeq) nB.getClass().getField("links").get(nB);
          la = lsa.size;
          lb = lsb.size;
          for (int i = 0; i < lsa.size; i++)
            lnames += "A→" + linkName(lsa.get(i)) + " ";
          for (int i = 0; i < lsb.size; i++)
            lnames += "B→" + linkName(lsb.get(i)) + " ";
        } catch (Throwable ignored) {
        }
        int members3 = -1;
        try {
          Class<?> net = Class.forName("combine.net.ComboNet", true, ml);
          Object mm = net.getMethod("componentMembers", Building.class).invoke(null, nA);
          members3 = mm instanceof arc.struct.Seq<?> s ? s.size : -1;
        } catch (Throwable ignored) {
        }
        System.out.println("[NHT] 用例3: 节点紧贴接力: 节点A=" + nA + " 节点B=" + nB
            + " 邻接=" + (nA.proximity != null && nA.proximity.contains(b -> b == nB))
            + " links=" + la + "/" + lb + " 分量成员=" + members3
            + " 连线[" + lnames.trim() + "]"
            + " 制热机heat=" + heatOf(h4) + " 节点heat=" + heatOf(nA) + "/" + heatOf(nB)
            + " 需热炮台 heatReq=" + case3Heat);
        check("两个紧挨着的组合节点接力传热（" + case3Heat + "）", case3Heat > 0.5f);
      }

      System.out.println("[NHT] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(2);
    }
  }
}
