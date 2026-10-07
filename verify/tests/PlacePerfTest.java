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
 * 量"往基地里再放一台组合建筑要多久"——用户报的"加一个新建筑就卡一下"。
 *
 * 摆 N 台混合类型的组合工厂（相邻成片），逐台"放置 + 跑 2 帧（让 ComboNet 重建）"计时，
 * 打印每次的平均/最大耗时。用同一个数据目录、同一套方块，对比不同版本的组合判定开销。
 *
 * 用法: verify/run-headless.sh mx <数据目录> combine.dbg.PlacePerfTest [-Dpp.n=80]
 */
public class PlacePerfTest implements arc.ApplicationListener {
  static String dataDir = "/tmp/mp_coop/data";
  static int n = Integer.getInteger("pp.n", 80);

  public static void main(String[] a) {
    if (a.length > 0)
      dataDir = a[0];
    Vars.platform = new Platform() {
    };
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> {
    };
    new HeadlessApplication(new PlacePerfTest(), t -> t.printStackTrace());
  }

  static void run(int f) {
    for (int i = 0; i < f; i++) {
      arc.util.Time.delta = 1f;
      Vars.logic.update();
    }
  }

  static Building place(Block b, int x, int y) {
    mindustry.world.Build.beginPlace(null, b, Team.sharded, x, y, 0, null);
    mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(x, y), b, null, (byte) 0, Team.sharded, null);
    Building bu = Vars.world.build(x, y);
    if (bu != null)
      try {
        bu.updateProximity();
      } catch (Throwable ignored) {
      }
    return bu;
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

      // 收集一批"组合工厂"方块（CombinedCrafter 族）——混合类型，模拟真实基地
      // 收集一批 1x1 的"组合工厂"（CombinedCrafter 族）——**混合类型**，模拟真实基地：
      // 老口径（同一个方块才并）下它们各成小组；新口径（同类才并）下一大片并成一个大组。
      arc.struct.Seq<Block> pool = new arc.struct.Seq<>();
      for (Block b : Vars.content.blocks()) {
        if (b == null || b.size != 1) continue;
        if (!b.getClass().getName().equals("combine.production.CombinedCrafter")) continue;
        pool.add(b);
        if (pool.size >= 8) break;
      }
      if (pool.size == 0) {
        System.out.println("[PP] 数据集里没有组合工厂，跳过");
        Core.app.exit();
        return;
      }
      System.out.println("[PP] 组合工厂种类=" + pool.size + " " + pool.map(b -> b.name)
          + " n=" + n + " 数据=" + dataDir);

      // 诊断：协作组合(扩展建筑)到底有多少个方块共享同一个类 —— 分组按"方块类"时，
      // 共享类越多，一片基地就越容易被并成一个巨型协作组（每帧搬运/重算都按组大小走）。
      try {
        Class<?> coop = Class.forName("combine.coop.CoopCombo", true,
            Vars.mods.getMod("combine").main.getClass().getClassLoader());
        java.lang.reflect.Method eligible = coop.getMethod("eligible", Block.class);
        arc.struct.ObjectIntMap<String> byClass = new arc.struct.ObjectIntMap<>();
        int total = 0;
        for (Block b : Vars.content.blocks()) {
          if (b == null || !(Boolean) eligible.invoke(null, b)) continue;
          byClass.increment(b.getClass().getName(), 1);
          total++;
        }
        int maxSame = 0;
        String maxName = "?";
        for (var e : byClass.entries())
          if (e.value > maxSame) {
            maxSame = e.value;
            maxName = e.key;
          }
        System.out.println("[PP] 协作组合可组合方块=" + total + " 种类(类)=" + byClass.size
            + " 同一个类最多 " + maxSame + " 个（" + maxName + "）");
      } catch (Throwable t) {
        System.out.println("[PP] 协作组合类统计跳过: " + t);
      }

      // 一横排（互相贴邻）+ 第二排（让它们连成一大片）
      // 先量一次基线：什么都不放，只跑 2 帧（这样能扣掉"每帧固有开销"）
      long baseTotal = 0;
      for (int i = 0; i < 10; i++) {
        long t0 = System.nanoTime();
        run(2);
        baseTotal += System.nanoTime() - t0;
      }
      long base = baseTotal / 10;

      // 直接量"放置以后那次重算"本身：本地分组(rebuildLocal) + 权威图重算(ComboNet.rebuild)，
      // 不走"跑帧"，避免把每帧固有开销混进来。
      Class<?> cr = Class.forName("combine.util.ComboReflect", true,
          Vars.mods.getMod("combine").main.getClass().getClassLoader());
      Class<?> cn = Class.forName("combine.net.ComboNet", true,
          Vars.mods.getMod("combine").main.getClass().getClassLoader());
      java.lang.reflect.Method rebuildLocal = cr.getMethod("rebuildLocal", Building.class);
      java.lang.reflect.Method netRebuild = cn.getMethod("rebuild");

      // 阶段 A：摆一排**同类**组合工厂（互相贴邻、会并成一大片）—— 这些放置本来就要重算
      long totalA = 0, maxA = 0, localA = 0;
      for (int i = 0; i < n; i++) {
        Block b = pool.get(i % pool.size);
        int x = 40 + i, y = 40;
        Building bu = place(b, x, y);
        long t0 = System.nanoTime();
        if (bu != null)
          rebuildLocal.invoke(null, bu);
        long t1 = System.nanoTime();
        netRebuild.invoke(null);
        long dt = System.nanoTime() - t0;
        totalA += dt;
        localA += t1 - t0;
        maxA = Math.max(maxA, dt);
        run(1);
      }

      // 阶段 B：在这一片旁边放**别的类**的组合建筑（组合钻头）——它不会和工厂并组，
      // 所以"这次放置要不要整图重算"完全取决于那条判据（用户报的"加一个新建筑就卡一下"）。
      Block drill = null;
      for (Block b : Vars.content.blocks())
        if (b.name.equals("mechanical-drill") && b.getClass().getName().contains("Combined"))
          drill = b;
      long totalB = 0, maxB = 0;
      int nb = 0;
      if (drill != null) {
        for (int i = 0; i < 20; i++) {
          // 紧贴上面那一排工厂（y=42 与 y=40 相邻）→ 邻居是"异类组合建筑"，
          // 但钻头彼此隔一格、不互相贴，所以每次放置都是"贴着一个异类工厂 + 不贴同类"。
          int x = 40 + i * 2, y = 41;
          Building bu = place(drill, x, y);
          long t0 = System.nanoTime();
          if (bu != null)
            rebuildLocal.invoke(null, bu);
          netRebuild.invoke(null);
          long dt = System.nanoTime() - t0;
          totalB += dt;
          maxB = Math.max(maxB, dt);
          nb++;
          run(1);
        }
      }
      int members = 0;
      try {
        Class<?> net = Class.forName("combine.net.ComboNet", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
        var m = net.getMethod("componentMembers", Building.class);
        Building first = Vars.world.build(40, 40);
        if (first != null) {
          arc.struct.Seq<?> cm = (arc.struct.Seq<?>) m.invoke(null, first);
          members = cm.size;
        }
      } catch (Throwable ignored) {
      }
      System.out.println("[PP] A 同类放置 " + n + " 次重算: 平均 " + (totalA / n / 1000)
          + " µs（本地分组 " + (localA / n / 1000) + " µs）, 最大 " + (maxA / 1000)
          + " µs, 整片网络成员=" + members);
      if (nb > 0)
        System.out.println("[PP] B 异类放置 " + nb + " 次重算: 平均 " + (totalB / nb / 1000)
            + " µs, 最大 " + (maxB / 1000) + " µs");
      Core.app.exit();
    } catch (Throwable t) {
      t.printStackTrace();
      Core.app.exit();
    }
  }
}
