package combine.dbg;

import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.struct.Seq;
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
 * 用户报：用组合节点连接 afflict（需热炮台）和 slag-heater（制热机）之后，
 * **热量确实传到了**，但是
 *   (a) 这两个没有"组合在一起"（悬浮面板不把它们算成一组）；
 *   (b) afflict 的面板上热量显示 0。
 *
 * 这个测试把节点连线那一套原样搭出来，然后打印：
 *   - ComboNet.componentMembers（悬浮面板的成员来源）
 *   - CoopPanel.members / describe（面板实际看到的东西）
 *   - 面板里那段热量是怎么算的（Σ HeatBlock.heat()）+ afflict 自己的 heatReq（原版热条读的就是它）
 * 并断言：两组必须成一组、面板热量必须 > 0、afflict 的 heatReq 必须 > 0。
 */
public class NodeHeatPanelTest implements arc.ApplicationListener {
  static String dataDir = "/tmp/mp_cj/data";
  static int pass = 0, fail = 0;
  static ClassLoader ml;

  public static void main(String[] a) {
    if (a.length > 0)
      dataDir = a[0];
    Vars.platform = new Platform() {
    };
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> {
      if (l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal())
        System.out.println("[NHP] " + t);
    };
    new HeadlessApplication(new NodeHeatPanelTest(), t -> t.printStackTrace());
  }

  static void check(String n, boolean ok) {
    System.out.println("[NHP] " + (ok ? "PASS " : "FAIL ") + n);
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
      System.out.println("[NHP] 节点连线失败: " + t);
    }
  }

  static Object call(Object o, String name, Class<?>[] sig, Object... args) {
    if (o == null)
      return null;
    try {
      var m = sig == null ? o.getClass().getMethod(name) : o.getClass().getMethod(name, sig);
      m.setAccessible(true);
      return m.invoke(o, args);
    } catch (Throwable t) {
      try {
        Class<?> c = Class.forName("combine.coop.CoopPanel", true, ml);
        var m = sig == null ? c.getMethod(name) : c.getMethod(name, sig);
        m.setAccessible(true);
        return m.invoke(null, args);
      } catch (Throwable t2) {
        System.out.println("[NHP] 调用 " + name + " 失败: " + t2);
        return null;
      }
    }
  }

  static float heatReq(Building turret) {
    Object v = fieldOf(turret, "heatReq");
    return v instanceof Number n ? n.floatValue() : -1f;
  }

  static float heatOf(Building b) {
    if (!(b instanceof mindustry.world.blocks.heat.HeatBlock))
      return -1f;
    try {
      var m = b.getClass().getMethod("heat");
      m.setAccessible(true);
      return ((Number) m.invoke(b)).floatValue();
    } catch (Throwable t) {
      return -1f;
    }
  }

  static Object fieldOf(Object o, String name) {
    for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
      try {
        var f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
      } catch (Throwable ignored) {
      }
    }
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
      ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();

      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.logic.play();
      run(20);
      for (int y = 25; y < 175; y++)
        for (int x = 10; x < 250; x++) {
          Tile t = Vars.world.tile(x, y);
          if (t != null && t.block() != Blocks.air)
            t.setBlock(Blocks.air);
        }
      run(5);

      Block core = null, nodeB = null, heater = null, afflict = null;
      for (Block b : Vars.content.blocks()) {
        if (core == null && b.name.equals("core-shard"))
          core = b;
        if (nodeB == null && b.getClass().getName().equals("combine.net.ComboNode"))
          nodeB = b;
        if (heater == null && b.name.equals("slag-heater"))
          heater = b;
        if (afflict == null && b.name.equals("afflict"))
          afflict = b;
      }
      if (core == null || nodeB == null || heater == null || afflict == null) {
        System.out.println("[NHP] 缺方块（core/node/slag-heater/afflict）");
        System.exit(3);
      }
      System.out.println("[NHP] 方块: 制热机=" + heater.getClass().getName()
          + " 需热炮台=" + afflict.getClass().getName()
          + " 节点=" + nodeB.getClass().getName());
      place(core, 60, 100);
      run(5);

      // 布置：制热机在右下、炮台在左上，中间用组合节点连线（互相不贴）。
      int nx = 40, ny = 40;
      Building heaterB = place(heater, nx + 8, ny + 8);
      Building afflictB = place(afflict, nx - 6, ny - 6);
      Building node = place(nodeB, nx, ny);
      node.updateProximity();
      heaterB.updateProximity();
      afflictB.updateProximity();
      run(5);
      tapNode(node, heaterB);
      tapNode(node, afflictB);
      run(5);

      heaterB.liquids.add(Liquids.slag, 100000f);
      run(120);
      heaterB.liquids.add(Liquids.slag, 100000f);
      run(60);

      System.out.println("[NHP] 制热机 " + heaterB.block.name + " heat=" + heatOf(heaterB)
          + " 节点heat=" + heatOf(node)
          + " afflict.heatReq=" + heatReq(afflictB)
          + "（heat=@ 是原版热条读的字段）");

      // 悬浮面板的成员来源
      Class<?> net = Class.forName("combine.net.ComboNet", true, ml);
      Class<?> panel = Class.forName("combine.coop.CoopPanel", true, ml);
      Object comp = net.getMethod("componentMembers", Building.class).invoke(null, afflictB);
      Object pms = panel.getMethod("members", Building.class).invoke(null, afflictB);
      String names = "";
      if (comp instanceof Seq<?> s)
        for (Object o : s)
          names += (o instanceof Building b ? b.block.name + "@" + b.tileX() + "," + b.tileY() : String.valueOf(o)) + " ";
      System.out.println("[NHP] componentMembers(afflict)=" + (comp instanceof Seq<?> s2 ? s2.size : -1)
          + " [" + names.trim() + "]");
      System.out.println("[NHP] CoopPanel.members(afflict)=" + (pms instanceof Seq<?> s3 ? s3.size : -1));
      System.out.println("[NHP] describe(afflict)=" + panel.getMethod("describe", Building.class).invoke(null, afflictB));

      // 面板里那段热量是这么算的：Σ 成员的 HeatBlock.heat()，需求 = Σ heatRequirement
      float heatSum = 0f, reqSum = 0f;
      if (pms instanceof Seq<?> s4) {
        for (Object o : s4) {
          if (!(o instanceof Building m) || !m.isValid())
            continue;
          if (m instanceof mindustry.world.blocks.heat.HeatBlock) {
            float h = heatOf(m);
            if (h > 0f)
              heatSum += h;
          }
          Object req = Class.forName("combine.util.ComboReflect", true, ml)
              .getMethod("getFloat", Object.class, String.class).invoke(null, m.block, "heatRequirement");
          if (req instanceof Number n2 && n2.floatValue() > 0f)
            reqSum += n2.floatValue();
        }
      }
      System.out.println("[NHP] 面板热量口径: heatSum=" + heatSum + " reqSum=" + reqSum);

      check("[S1 显式连线] afflict 自己拿到了热（heatReq=" + heatReq(afflictB) + " > 0）", heatReq(afflictB) > 0.5f);
      check("[S1 显式连线] 悬浮面板把两者算成一组（成员数=" + (pms instanceof Seq<?> s5 ? s5.size : -1) + " ≥ 2）",
          pms instanceof Seq<?> s6 && s6.size >= 2);
      check("[S1 显式连线] 面板热量 > 0（" + heatSum + "）", heatSum > 0.5f);

      // ---------- 场景 2：节点只是"贴着"两台（不点连线）—— 用户很可能就是这么摆的 ----
      {
        int bx = nx + 20, by = ny;
        Building heater2 = place(heater, bx + 1, by);
        Building afflict2 = place(afflict, bx - 4, by);
        Building node2 = place(nodeB, bx, by);
        node2.updateProximity();
        heater2.updateProximity();
        afflict2.updateProximity();
        run(5);
        heater2.liquids.add(Liquids.slag, 100000f);
        run(150);
        heater2.liquids.add(Liquids.slag, 100000f);
        run(30);
        boolean adjacent = node2.proximity != null
            && (node2.proximity.contains(heater2) || node2.proximity.contains(afflict2));
        Object comp2 = net.getMethod("componentMembers", Building.class).invoke(null, afflict2);
        Object pms2 = panel.getMethod("members", Building.class).invoke(null, afflict2);
        System.out.println("[NHP] [S2 只贴不连线] 节点邻格=" + (node2.proximity == null ? -1 : node2.proximity.size)
            + " 相邻成立=" + adjacent
            + " heatReq=" + heatReq(afflict2)
            + " componentMembers=" + (comp2 instanceof Seq<?> s7 ? s7.size : -1)
            + " 面板成员=" + (pms2 instanceof Seq<?> s8 ? s8.size : -1));
        System.out.println("[NHP] [S2] describe=" + panel.getMethod("describe", Building.class).invoke(null, afflict2));
        check("[S2 只贴不连线] afflict 拿得到热（" + heatReq(afflict2) + " > 0）", heatReq(afflict2) > 0.5f);
        check("[S2 只贴不连线] 面板把两者算成一组（成员数=" + (pms2 instanceof Seq<?> s9 ? s9.size : -1) + " ≥ 2）",
            pms2 instanceof Seq<?> s10 && s10.size >= 2);
      }

      // ---------- 场景 3：产热方是**没被本模组替换过**的纯热方块（mod 制热机的典型情形）----
      {
        int cx = nx + 40, cy = ny;
        Building src = place(Blocks.heatSource, cx + 1, cy);
        Building afflict3 = place(afflict, cx - 4, cy);
        Building node3 = place(nodeB, cx, cy);
        node3.updateProximity();
        src.updateProximity();
        afflict3.updateProximity();
        run(5);
        tapNode(node3, src);
        tapNode(node3, afflict3);
        run(60);
        Object comp3 = net.getMethod("componentMembers", Building.class).invoke(null, afflict3);
        Object pms3 = panel.getMethod("members", Building.class).invoke(null, afflict3);
        System.out.println("[NHP] [S3 纯热方块] 产热方块=" + src.getClass().getName()
            + " heat=" + heatOf(src) + " heatReq=" + heatReq(afflict3)
            + " componentMembers=" + (comp3 instanceof Seq<?> s11 ? s11.size : -1)
            + " 面板成员=" + (pms3 instanceof Seq<?> s12 ? s12.size : -1));
        System.out.println("[NHP] [S3] describe=" + panel.getMethod("describe", Building.class).invoke(null, afflict3));
        float h3 = 0f;
        if (pms3 instanceof Seq<?> s13)
          for (Object o : s13)
            if (o instanceof Building m3 && m3.isValid() && m3 instanceof mindustry.world.blocks.heat.HeatBlock)
              h3 += Math.max(0f, heatOf(m3));
        check("[S3 纯热方块] afflict 拿得到热（" + heatReq(afflict3) + " > 0）", heatReq(afflict3) > 0.5f);
        check("[S3 纯热方块] 面板把两者算成一组（成员数=" + (pms3 instanceof Seq<?> s14 ? s14.size : -1) + " ≥ 2）",
            pms3 instanceof Seq<?> s15 && s15.size >= 2);
        check("[S3 纯热方块] 面板热量 > 0（" + h3 + "）", h3 > 0.5f);
      }
    } catch (Throwable t) {
      System.out.println("[NHP] 运行异常: " + t);
      t.printStackTrace();
    }
    System.out.println("[NHP] 结果: PASS=" + pass + " FAIL=" + fail);
    Core.app.exit();
  }
}
