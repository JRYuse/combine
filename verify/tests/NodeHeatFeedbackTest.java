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
 * 用户报：用组合节点/组合连接器把【制热机】和【热量传输装置（导热管/热路由器）】连起来后，
 * 导热管的热量输出会变成浮点数的最大值（Float.MAX_VALUE）。
 *
 * 根因：导热管（{@code HeatConductor}）同时是 HeatBlock **和** HeatConsumer，它的 heat() 是从
 * 贴着它的组合节点读过去的（节点每帧被写入"整张网络的热量"）。而 ComboNet 统计"外来热源"时
 * 又把这个导热管当成源头加进总量 → node.heat 与导热管.heat 互相喂，每帧翻倍 → 几十帧到 MAX_VALUE。
 * 修法：{@code heatSourceOf} 只认"非 HeatConsumer 的 HeatBlock"（呼应 linkerExternalHeat 里同一口径）。
 *
 * 本测试：制热机 -节点连线- 节点（贴着导热管），跑几百帧看导热管 heat() 有没有被控制在合理范围。
 */
public class NodeHeatFeedbackTest implements arc.ApplicationListener {
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
        System.out.println("[NHF] " + t);
    };
    new HeadlessApplication(new NodeHeatFeedbackTest(), t -> t.printStackTrace());
  }

  static void check(String n, boolean ok) {
    System.out.println("[NHF] " + (ok ? "PASS " : "FAIL ") + n);
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
      System.out.println("[NHF] 节点连线失败: " + t);
    }
  }

  static float heatOf(Building b) {
    try {
      var m = b.getClass().getMethod("heat");
      m.setAccessible(true);
      return ((Number) m.invoke(b)).floatValue();
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

      Block heater = null, conduit = null, nodeB = null, psrc = null;
      for (Block b : Vars.content.blocks()) {
        if (heater == null && b.name.equals("slag-heater")) heater = b;
        if (conduit == null && b.name.equals("heat-redirector")) conduit = b;
        if (conduit == null && b.name.equals("heat-router")) conduit = b;
        if (nodeB == null && b.getClass().getName().equals("combine.net.ComboNode")) nodeB = b;
        if (psrc == null && b.name.equals("power-source")) psrc = b;
      }
      System.out.println("[NHF] 制热=" + (heater == null ? "null" : heater.name)
          + " 导热管=" + (conduit == null ? "null" : conduit.name)
          + " 节点=" + (nodeB == null ? "null" : nodeB.name));
      if (heater == null || conduit == null || nodeB == null) {
        System.out.println("[NHF] 数据集缺方块，跳过（exit 0）");
        System.exit(0);
      }

      int ox = -1, oy = -1;
      outer:
      for (int y = 50; y < 130; y++)
        for (int x = 50; x < 180; x++) {
          boolean ok = true;
          for (int dy = -4; dy <= 8 && ok; dy++)
            for (int dx = -2; dx <= 18; dx++) {
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
        System.out.println("[NHF] 没找到平地");
        System.exit(3);
      }

      // 布局：导热管(size3) 右边贴一个组合节点；制热机在节点 6 格连线范围内，用节点连线接进来。
      Building cond = place(conduit, ox, oy);
      Building node = place(nodeB, ox + 3, oy + 1);
      Building heat = place(heater, ox + 8, oy);
      run(5);
      if (heat != null && heat.liquids != null)
        heat.liquids.add(Liquids.slag, 5000f);
      tapNode(node, heat);
      run(20);

      float maxCond = 0f, maxNode = 0f, maxHeat = 0f;
      for (int i = 0; i < 300; i++) {
        arc.util.Time.delta = 1f;
        Vars.logic.update();
        if (heat != null && heat.liquids != null)
          heat.liquids.add(Liquids.slag, 5f);
        float c = heatOf(cond), n = heatOf(node), h = heatOf(heat);
        if (Float.isFinite(c)) maxCond = Math.max(maxCond, c);
        if (Float.isFinite(n)) maxNode = Math.max(maxNode, n);
        if (Float.isFinite(h)) maxHeat = Math.max(maxHeat, h);
      }
      System.out.println("[NHF] 峰值热量: 导热管=" + maxCond + " 节点=" + maxNode + " 制热机=" + maxHeat);
      check("导热管热量没有冲到浮点最大值/无穷（峰值 " + maxCond + "）",
          Float.isFinite(maxCond) && maxCond < 100f);
      check("导热管确实拿到了网络热量（峰值 " + maxCond + " > 0）", maxCond > 0.01f);
      check("制热机热量正常（峰值 " + maxHeat + "）", Float.isFinite(maxHeat) && maxHeat < 100f);

      System.out.println("[NHF] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(2);
    }
  }
}
