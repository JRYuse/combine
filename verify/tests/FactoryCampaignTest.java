package combine.dbg;

import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Planets;
import mindustry.core.Logic;
import mindustry.core.NetClient;
import mindustry.core.NetServer;
import mindustry.core.Platform;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Gamemode;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.game.Team;
import mindustry.maps.Map;
import mindustry.mod.Mod;
import mindustry.net.Net;
import mindustry.type.Sector;
import mindustry.ui.Fonts;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;

/**
 * 「战役模式下（工厂）无法合体」的回归。
 *
 * <p>
 * 根因：工厂合体的虚影走 {@code control.input.useSchematic} → {@code Schematics.toPlans}，
 * 而原版 {@code toPlans} 会 {@code removeAll} 掉 {@code !block.unlockedNow()} 的计划；
 * {@code UnlockableContent.unlockedNow()} 在战役里（{@code state.isCampaign()} =
 * {@code rules.sector != null}）只认 {@code unlocked}/{@code alwaysUnlocked}。
 * 组合工厂是 {@code Mod.init()} 里现造的隐藏方块：没进科技树、永远不会被研究
 * → 战役图里框选完虚影直接是空的，**点下去什么也不发生**。
 * 沙盒 / 自定义图 {@code isCampaign()=false}，{@code unlockedNow()} 恒真，所以只有战役会坏
 * （超级组合炮台当年就是靠 {@code alwaysUnlocked=true} 才在战役里能用，见 Main.createSuperTurrets）。
 *
 * <p>
 * 断言：① 对照组（非战役）虚影计划能生成；② 战役下 {@code unlockedNow()} 为真、计划仍在；
 * ③ 战役下空地上 {@code Build.validPlace} 放行。
 *
 * <p>
 * 跑法：{@code verify/run-headless.sh <game> <数据目录> combine.dbg.FactoryCampaignTest}
 * （Windows 上见 verify/run-headless.ps1）。
 */
public class FactoryCampaignTest implements arc.ApplicationListener {
  static String dataDir = "/tmp/mp_coop/data";
  static int pass = 0, fail = 0;

  public static void main(String[] a) {
    if (a.length > 0)
      dataDir = a[0];
    Vars.platform = new Platform() {
    };
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> {
      if (l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal())
        System.out.println("[L " + l + "] " + t);
    };
    new HeadlessApplication(new FactoryCampaignTest(), t -> t.printStackTrace());
  }

  static void check(String n, boolean ok) {
    System.out.println("[FC-CAMPAIGN] " + (ok ? "PASS " : "FAIL ") + n);
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

  static void clearArea(int x1, int y1, int x2, int y2) {
    for (int y = y1; y <= y2; y++)
      for (int x = x1; x <= x2; x++) {
        Tile t = Vars.world.tile(x, y);
        if (t != null && t.block() != Blocks.air)
          t.setBlock(Blocks.air);
      }
  }

  static Schematic schemOf(Block block, int side, String config) {
    Seq<Schematic.Stile> tiles = new Seq<>();
    tiles.add(new Schematic.Stile(block, 0, 0, config, (byte) 0));
    return new Schematic(tiles, new StringMap(), side, side);
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

      Block cf = Vars.content.block("super-combine-factory-2");
      check("找得到 super-combine-factory-2（class=" + (cf == null ? "null" : cf.getClass().getName()) + "）",
          cf != null && cf.size == 2);

      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      check("找得到内置图 Archipelago", map != null);
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.state.rules.canGameOver = false;
      Vars.state.rules.waves = false;
      Vars.logic.play();
      run(20);
      clearArea(30, 30, 60, 60);
      run(3);

      if (cf == null)
        return;

      // 虚影用的小蓝图（和 FactoryCombiner.finish 里那份同构：一台 2x2 组合工厂 + 配置串）
      String config = "silicon-smelter@0;silicon-smelter@0;silicon-smelter@0";
      Schematic schem = schemOf(cf, cf.size, config);
      Schematics sch = Vars.schematics != null ? Vars.schematics : new Schematics();

      // ---------- 对照组：非战役（沙盒/自定义/编辑器）本来就是好的 ----------
      check("对照组：rules.sector=null（isCampaign=false）", !Vars.state.isCampaign());
      Seq<BuildPlan> outside = sch.toPlans(schem, 40, 40, false);
      check("对照组：虚影计划 = 1（实际 " + outside.size + "）", outside.size == 1);

      // ---------- 切到战役：rules.sector != null ----------
      Sector sector = Planets.serpulo.sectors.first();
      check("塞普罗有区块（sector 非空）", sector != null);
      Vars.state.rules.sector = sector;
      check("已切到战役模式（isCampaign=true）", Vars.state.isCampaign());

      check("战役下 super-combine-factory-2 是解锁的（unlockedNow=" + cf.unlockedNow() + "）",
          cf.unlockedNow());
      Seq<BuildPlan> inside = sch.toPlans(schem, 40, 40, false);
      check("战役下虚影计划仍在 = 1（实际 " + inside.size + "）", inside.size == 1);

      Team team = Vars.state.rules.defaultTeam;
      check("战役下空地上 Build.validPlace 放行",
          Build.validPlaceIgnoreUnits(cf, team, 40, 40, 0, true, true));

      System.out.println("[FC-CAMPAIGN] 结果: PASS=" + pass + " FAIL=" + fail);
      Core.app.exit();
    } catch (Throwable t) {
      System.out.println("[FC-CAMPAIGN] 崩了: " + t);
      t.printStackTrace(System.out);
      System.exit(2);
    }
  }
}
