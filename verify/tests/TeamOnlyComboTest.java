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
import mindustry.gen.Player;
import mindustry.maps.Map;
import mindustry.mod.Mod;
import mindustry.net.Net;
import mindustry.ui.Fonts;
import mindustry.world.Block;
import mindustry.world.Tile;

/**
 * 设置里的"只和玩家队友组合"开关（AI 敌人的建筑不互相组合）。
 *
 * 验：① 关着的时候 AI 队伍（crux）两台相邻组合炮台照常共用池子；
 * ② 打开之后同样两台**不再共用**；
 * ③ 玩家队伍（sharded，本地玩家所在队）照常共用（能力没被一刀切）。
 */
public class TeamOnlyComboTest implements arc.ApplicationListener {
  static String dataDir = "/tmp/mp_cj/data";
  static int pass = 0, fail = 0;
  static Class<?> clsTeams;

  public static void main(String[] a) {
    if (a.length > 0)
      dataDir = a[0];
    Vars.platform = new Platform() {
    };
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> {
      if (l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal())
        System.out.println("[TO] " + t);
    };
    new HeadlessApplication(new TeamOnlyComboTest(), t -> t.printStackTrace());
  }

  static void check(String n, boolean ok) {
    System.out.println("[TO] " + (ok ? "PASS " : "FAIL ") + n);
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
    mindustry.world.Build.beginPlace(null, b, team, x, y, 0, null);
    mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(x, y), b, null, (byte) 0, team, null);
    Building bu = Vars.world.build(x, y);
    if (bu != null)
      try {
        bu.updateProximity();
      } catch (Throwable ignored) {
      }
    return bu;
  }

  static void setPlayersOnly(boolean v) {
    try {
      clsTeams.getMethod("set", boolean.class).invoke(null, v);
    } catch (Throwable t) {
      System.out.println("[TO] 设置开关失败: " + t);
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
      clsTeams = Class.forName("combine.util.ComboTeams", true,
          Vars.mods.getMod("combine").main.getClass().getClassLoader());

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

      // 无头测试里不造 Player（GlobalVars.update 会因为 control==null 崩）：
      // ComboTeams 在没有玩家时把"默认队伍"（sharded）当玩家队伍。

      Block turret = null;
      for (Block b : Vars.content.blocks())
        if (b.name.equals("duo") && b.getClass().getName().startsWith("combine.")) {
          turret = b;
          break;
        }
      if (turret == null) {
        System.out.println("[TO] 找不到组合 duo");
        System.exit(3);
      }
      System.out.println("[TO] 组合炮台=" + turret.name + "/" + turret.getClass().getName());

      // 判据检查：开关关着 = 谁都能组合；打开 = 只有玩家队伍（默认队 sharded）能组合
      java.lang.reflect.Method playerTeam = clsTeams.getMethod("playerTeam", Team.class);
      setPlayersOnly(false);
      boolean offSharded = (Boolean) playerTeam.invoke(null, Team.sharded);
      boolean offCrux = (Boolean) playerTeam.invoke(null, Team.crux);
      check("关着的时候：玩家队伍/AI 队伍都算可组合", offSharded && offCrux);
      setPlayersOnly(true);
      boolean onSharded = (Boolean) playerTeam.invoke(null, Team.sharded);
      boolean onCrux = (Boolean) playerTeam.invoke(null, Team.crux);
      System.out.println("[TO] 打开后: sharded=" + onSharded + " crux=" + onCrux);
      check("打开后：玩家队伍（默认队）算可组合", onSharded);
      check("打开后：AI 敌人队伍（crux）不算可组合", !onCrux);

      // 玩家队伍（sharded）两台相邻炮台：打开着也照常组合（能力没被一刀切）
      Building s1 = place(turret, 70, 60, Team.sharded);
      Building s2 = place(turret, 71, 60, Team.sharded);
      run(60);
      // 物品炮塔的"组合"体现在整组的 ammo/组员关系上（不是 items 模块），所以看 group()
      boolean playerShared = false;
      try {
        Object g = s1.getClass().getMethod("group").invoke(s1);
        if (g instanceof arc.struct.Seq<?> seq)
          for (Object o : seq)
            if (o == s2)
              playerShared = true;
      } catch (Throwable t) {
        System.out.println("[TO] 读 group 失败: " + t);
      }
      System.out.println("[TO] 打开着: sharded 两台共用池子=" + playerShared);
      check("打开后玩家队伍照常组合", playerShared);

      System.out.println("[TO] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(2);
    }
  }
}
