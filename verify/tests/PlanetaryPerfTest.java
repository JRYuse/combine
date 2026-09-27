package combine.dbg;

import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.files.Fi;
import arc.util.Log;
import mindustry.Vars;
import mindustry.core.Logic;
import mindustry.core.NetClient;
import mindustry.core.NetServer;
import mindustry.core.Platform;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.io.SaveIO;
import mindustry.mod.Mod;
import mindustry.net.Net;

/**
 * 用户报："进 planetaryTerminal.msav 这种大型基地图非常卡，怀疑敌人的建筑也组合；
 * 设了'只有玩家建筑组合'还是很卡"。
 *
 * 这个图是 512x512 进攻图（attackMode，大量 crux 敌方工厂，不需要任何模组），
 * 拿它量：读档耗时、每帧 update 耗时、以及"模组到底碰了多少台建筑"（分队伍）。
 *
 * 数据目录（这个存档不需要任何模组，直接把存档拷进去即可）：
 *   mkdir -p /tmp/mp_pt/data/{mods,saves}
 *   cp ~/sd/q/planetaryTerminal.msav /tmp/mp_pt/data/saves/
 *   cp build/libs/combine.jar /tmp/mp_pt/data/mods/
 * 对照组（不装模组）：同样目录但不放 mods/combine.jar（用 java 直接跑，别用 run-headless.sh —— 它会要求模组加载）
 *
 *   verify/run-headless.sh mx /tmp/mp_pt/data combine.dbg.PlanetaryPerfTest
 *   verify/run-headless.sh mx /tmp/mp_pt/data combine.dbg.PlanetaryPerfTest -Donly=true
 *   verify/run-headless.sh mx /tmp/mp_pt/data combine.dbg.PlanetaryPerfTest -DbuildOnly=true   # 按方块类分开计时
 *   verify/run-headless.sh mx /tmp/mp_pt/data combine.dbg.PlanetaryPerfTest -DpurgeEnemy=true   # 清掉敌方建筑做对照
 */
public class PlanetaryPerfTest implements arc.ApplicationListener {
  static String dataDir = "/tmp/mp_pt/data";
  static String savePath = "/tmp/mp_pt/data/saves/planetaryTerminal.msav";
  static ClassLoader ml;

  public static void main(String[] a) {
    if (a.length > 0)
      dataDir = a[0];
    Vars.platform = new Platform() {
    };
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> {
      if (l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal())
        System.out.println("[PP] " + t);
    };
    new HeadlessApplication(new PlanetaryPerfTest(), t -> t.printStackTrace());
  }

  static Object callStatic(String cls, String name, Class<?>[] sig, Object... args) {
    try {
      Class<?> c = Class.forName(cls, true, ml);
      var m = sig == null ? c.getMethod(name) : c.getMethod(name, sig);
      m.setAccessible(true);
      return m.invoke(null, args);
    } catch (Throwable t) {
      System.out.println("[PP] 调 " + cls + "." + name + " 失败: " + t);
      return null;
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
      try {
        ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
      } catch (Throwable ignored) {
        ml = null;   // 对照组：数据目录里没有 combine 模组
      }

      boolean only = Boolean.parseBoolean(System.getProperty("only", "false"));
      boolean purgeEnemy = Boolean.parseBoolean(System.getProperty("purgeEnemy", "false"));
      if (ml != null)
        callStatic("combine.util.ComboTeams", "set", new Class<?>[] { boolean.class }, only);
      System.out.println("[PP] 只玩家组合(playersOnly)=" + only + " 清掉敌方建筑=" + purgeEnemy
          + " 模组已加载=" + (ml != null));

      long t0 = System.nanoTime();
      SaveIO.load(Core.files.absolute(savePath));
      long t1 = System.nanoTime();
      System.out.println("[PP] 读档耗时 = " + (t1 - t0) / 1_000_000.0 + " ms");
      Vars.logic.play();
      Vars.state.set(mindustry.core.GameState.State.playing);

      if (purgeEnemy) {
        long p0 = System.nanoTime();
        int removed = 0;
        for (mindustry.world.Tile t : Vars.world.tiles) {
          Building b = t == null ? null : t.build;
          if (b == null || b.team == Vars.state.rules.defaultTeam)
            continue;
          t.setBlock(mindustry.content.Blocks.air);
          removed++;
        }
        System.out.println("[PP] 清掉非玩家建筑 " + removed + " 台，耗时 "
            + (System.nanoTime() - p0) / 1_000_000.0 + " ms");
        for (int i = 0; i < 20; i++) {
          arc.util.Time.delta = 1f;
          Vars.logic.update();
        }
      }

      // ---- 每帧耗时 ----
      int ticks = 180;
      boolean buildOnly = Boolean.parseBoolean(System.getProperty("buildOnly", "false"));
      long total = 0, worst = 0;
      if (buildOnly) {
        // 只跑建筑 update，并按"队伍 / 方块类"累计 —— 找出到底是哪些建筑在吃时间
        arc.struct.ObjectFloatMap<String> byClass = new arc.struct.ObjectFloatMap<>();
        long playerNs = 0, otherNs = 0;
        for (int i = 0; i < ticks; i++) {
          arc.util.Time.delta = 1f;
          long a = System.nanoTime();
          for (Building b : mindustry.gen.Groups.build) {
            long t2 = System.nanoTime();
            try {
              b.update();
            } catch (Throwable ignored) {
            }
            long d2 = System.nanoTime() - t2;
            if (b.team == Vars.state.rules.defaultTeam)
              playerNs += d2;
            else
              otherNs += d2;
            String key = b.block.getClass().getSimpleName();
            byClass.put(key, byClass.get(key, 0f) + d2 / 1_000_000f);
          }
          total += System.nanoTime() - a;
        }
        System.out.println("[PP] 只跑建筑 update: 平均 " + (total / 1_000_000.0 / ticks) + " ms/帧");
        System.out.println("[PP]   玩家队建筑合计 " + playerNs / 1_000_000.0 / ticks
            + " ms/帧，其它队建筑合计 " + otherNs / 1_000_000.0 / ticks + " ms/帧");
        // 注意：arc 的 map 迭代器复用同一个 Entry，必须先抄出来再排序
        arc.struct.Seq<String[]> es = new arc.struct.Seq<>();
        for (String k : byClass.keys())
          es.add(new String[] { k, String.valueOf(byClass.get(k, 0f)) });
        es.sort(a2 -> -Float.parseFloat(a2[1]));
        for (int i = 0; i < Math.min(12, es.size); i++)
          System.out.println("[PP]   类耗时 " + es.get(i)[0] + " = "
              + (Float.parseFloat(es.get(i)[1]) / ticks) + " ms/帧");
      } else {
        for (int i = 0; i < ticks; i++) {
          arc.util.Time.delta = 1f;
          long a = System.nanoTime();
          Vars.logic.update();
          long d = System.nanoTime() - a;
          total += d;
          worst = Math.max(worst, d);
          if (i < 5)
            System.out.println("[PP]   第 " + i + " 帧 = " + d / 1_000_000.0 + " ms");
        }
        System.out.println("[PP] 每帧 update: 平均 " + (total / 1_000_000.0 / ticks) + " ms，最慢 "
            + worst / 1_000_000.0 + " ms（" + ticks + " 帧）");
      }

      // ---- 模组到底碰了多少台建筑 ----
      int[] perTeam = new int[16];
      int[] combinable = new int[16];
      int totalBuilds = 0, totalCombinable = 0, playerBuilds = 0, playerCombinable = 0;
      for (mindustry.world.Tile t : Vars.world.tiles) {
        Building b = t == null ? null : t.build;
        if (b == null)
          continue;
        totalBuilds++;
        int id = b.team == null ? 0 : b.team.id;
        perTeam[id]++;
        boolean combo = ml != null && Boolean.TRUE.equals(callStatic("combine.util.ComboReflect", "isComboBuild",
            new Class<?>[] { Building.class }, b));
        if (combo) {
          combinable[id]++;
          totalCombinable++;
        }
        if (b.team == Vars.state.rules.defaultTeam) {
          playerBuilds++;
          if (combo)
            playerCombinable++;
        }
      }
      System.out.println("[PP] 建筑总数=" + totalBuilds + "（玩家队 " + playerBuilds + "）");
      for (int i = 0; i < perTeam.length; i++)
        if (perTeam[i] > 0)
          System.out.println("[PP]   team#" + i + " = " + perTeam[i] + " 台，其中被模组当组合方块的有 "
              + combinable[i]);
      System.out.println("[PP] 被模组当组合方块的总数=" + totalCombinable + "（玩家队 " + playerCombinable + "）");

      // ---- 强制重建一次组合网络，量它的耗时 ----
      try {
        if (ml == null)
          throw new IllegalStateException("模组没加载，跳过");
        Class<?> net = Class.forName("combine.net.ComboNet", true, ml);
        net.getMethod("markDirty").invoke(null);
        long a = System.nanoTime();
        net.getMethod("rebuild").invoke(null);
        long b2 = System.nanoTime();
        System.out.println("[PP] ComboNet.rebuild() 一次 = " + (b2 - a) / 1_000_000.0 + " ms");
        Object all = net.getMethod("allComboBuildings").invoke(null);
        System.out.println("[PP] allComboBuildings() = "
            + (all instanceof arc.struct.Seq<?> s ? s.size : -1) + " 台");
      } catch (Throwable t) {
        System.out.println("[PP] 重建测量失败: " + t);
      }
    } catch (Throwable t) {
      System.out.println("[PP] 异常: " + t);
      t.printStackTrace();
    }
    Core.app.exit();
  }
}
