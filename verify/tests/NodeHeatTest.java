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
 * 用户报"组合节点还是不传输热量"：验证**用节点连起来**的需热组合工厂能不能拿到网络里的热。
 *
 * 与 HeatProducerTest 里那条"导热管贴着节点"的用例不同：这里需热方和产热方**互不相邻**，
 * 中间只隔着组合节点连线 —— 走的必须是 ComboNet 的热量分配
 * （{@code ComboNet.distributeHeat → heatAlloc → ComboNet.heatFor → CombinedCrafter.availableHeat}）。
 */
public class NodeHeatTest implements arc.ApplicationListener {
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
        System.out.println("[NH] " + t);
    };
    new HeadlessApplication(new NodeHeatTest(), t -> t.printStackTrace());
  }

  static void check(String n, boolean ok) {
    System.out.println("[NH] " + (ok ? "PASS " : "FAIL ") + n);
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

  static Building place(Block b, int x, int y, Team team) {
    int size = Math.max(b.size, 1);
    int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
    mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
    mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte) 0, team, null);
    Building bu = Vars.world.build(ax, ay);
    if (bu != null)
      try {
        bu.updateProximity();
      } catch (Throwable ignored) {
      }
    return bu;
  }

  static float methodF(Object o, String name) {
    try {
      Class<?> c = o.getClass();
      while (c != null) {
        try {
          var m = c.getDeclaredMethod(name);
          m.setAccessible(true);
          return ((Number) m.invoke(o)).floatValue();
        } catch (NoSuchMethodException ignored) {
          c = c.getSuperclass();
        }
      }
    } catch (Throwable ignored) {
    }
    return -1f;
  }

  /**
   * 产热机这一帧产了多少热：字段/方法名在不同模组里不一样（字段 producerHeat / 方法 heat()），
   * 全都试一遍，读不到才返回 -1（测试的前置检查用它）。
   */
  static float producerHeat(Object o) {
    float v = methodF(o, "producerHeat");
    if (v >= 0f) return v;
    try {
      return o.getClass().getField("producerHeat").getFloat(o);
    } catch (Throwable ignored) {
    }
    return methodF(o, "heat");
  }

  static void tapNode(Building node, Building target) {
    try {
      var m = node.getClass().getMethod("onConfigureBuildTapped", Building.class);
      m.setAccessible(true);
      m.invoke(node, target);
    } catch (Throwable t) {
      System.out.println("[NH] 节点连线失败: " + t);
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

      Block heater = null, heatCrafter = null, nodeB = null;
      for (Block b : Vars.content.blocks()) {
        if (heater == null && b.name.equals("slag-heater"))
          heater = b;
        if (nodeB == null && b.getClass().getName().equals("combine.net.ComboNode"))
          nodeB = b;
        if (heatCrafter == null && b.getClass().getName().equals("combine.production.CombinedCrafter")) {
          try {
            var f = b.getClass().getDeclaredField("mode");
            f.setAccessible(true);
            if (String.valueOf(f.get(b)).equals("heatcrafter"))
              heatCrafter = b;
          } catch (Throwable ignored) {
          }
        }
      }
      System.out.println("[NH] heater=" + (heater == null ? "null" : heater.name)
          + " heatcrafter=" + (heatCrafter == null ? "null" : heatCrafter.name)
          + " node=" + (nodeB == null ? "null" : nodeB.name));
      if (heater == null || heatCrafter == null || nodeB == null) {
        System.out.println("[NH] 数据集缺方块，跳过（exit 0）");
        System.exit(0);
      }

      // 一块平地
      int ox = -1, oy = -1;
      outer:
      for (int y = 45; y < 140; y++)
        for (int x = 40; x < 190; x++) {
          boolean ok = true;
          for (int dy = -5; dy <= 5 && ok; dy++)
            for (int dx = -4; dx <= 24; dx++) {
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
        System.out.println("[NH] 没找到平地");
        System.exit(3);
      }

      int hsz = Math.max(heater.size, 1);
      Building h1 = place(heater, ox, oy, Team.sharded);
      Building h2 = place(heater, ox + hsz, oy, Team.sharded); // 相邻成组（两台一起产热）
      Building consumer = place(heatCrafter, ox + hsz * 2 + 8, oy, Team.sharded);
      Building node = place(nodeB, ox + hsz * 2 + 4, oy, Team.sharded);
      run(10);
      System.out.println("[NH] 布置: 产热=" + h1 + "/" + h2 + " 需热=" + consumer + " 节点=" + node
          + " 距离=" + (consumer == null || h1 == null ? -1 : (int) consumer.dst(h1)));

      // 先看没有连线时的对照
      h1.liquids.add(Liquids.slag, 100000f);
      h2.liquids.add(Liquids.slag, 100000f);
      run(120);
      float before = methodF(consumer, "availableHeat");
      System.out.println("[NH] 连线前: 产热1 producerHeat=" + producerHeat(h1)
          + " 需热 availableHeat=" + before + " 节点heat=" + methodF(node, "heat"));
      check("（前置）产热机在产热", producerHeat(h1) > 0.1f);
      check("（对照）没连线时需热方拿不到热（" + before + " ≈ 0）", before < 0.1f);

      // 用节点把产热组和需热方连起来
      tapNode(node, h1);
      tapNode(node, consumer);
      run(5);
      h1.liquids.add(Liquids.slag, 100000f);
      h2.liquids.add(Liquids.slag, 100000f);
      run(120);
      float after = methodF(consumer, "availableHeat");
      float nodeHeat = methodF(node, "heat");
      System.out.println("[NH] 连线后: 节点heat=" + nodeHeat + " 需热 availableHeat=" + after
          + "（产热组产量=" + (producerHeat(h1) + producerHeat(h2)) + "）");
      check("组合节点把网络里的热量分给了不相邻的需热方（" + after + " > 0）", after > 0.1f);

      System.out.println("[NH] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(2);
    }
  }
}
