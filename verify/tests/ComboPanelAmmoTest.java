package combine.dbg;

import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
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
import mindustry.world.blocks.defense.turrets.ItemTurret;

/**
 * 用户报："纯物品炮台的组合体(不是合体)的悬浮窗口不显示物品"。
 *
 * <p>根因：炮塔的弹药存在**自己的弹仓**里，共享物品池只是仓库侧的中转 —— 悬浮面板只画池子，
 * 池子空的时候面板上就什么都没有。修法：面板/摘要里单列一段"弹仓（弹药 / 已装 / 上限）"。
 *
 * <p>本测试：摆一台内容表里的 duo（= 本模组替换出来的组合炮台），喂铜 → 等它把弹药搬进弹仓，
 * 然后检查 {@code CoopPanel.showable / ammoTypes / ammoCounts / describe} 都能念出弹药。
 */
public class ComboPanelAmmoTest implements arc.ApplicationListener {
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
        System.out.println("[CPA] " + t);
    };
    new HeadlessApplication(new ComboPanelAmmoTest(), t -> t.printStackTrace());
  }

  static void check(String n, boolean ok) {
    System.out.println("[CPA] " + (ok ? "PASS " : "FAIL ") + n);
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

      Block duo = null;
      for (Block b : Vars.content.blocks())
        if (duo == null && b.name.equals("duo"))
          duo = b;
      if (duo == null || !(duo instanceof ItemTurret)) {
        System.out.println("[CPA] 内容表里的 duo 不是物品炮塔（" + duo + "），跳过");
        System.exit(0);
      }
      System.out.println("[CPA] duo 类=" + duo.getClass().getName() + " size=" + duo.size
          + " 弹药种类=" + ((ItemTurret) duo).ammoTypes.size);

      Class<?> panel = Class.forName("combine.coop.CoopPanel", true, ml);
      Building turret = place(duo, 60, 60);
      run(10);
      boolean showable = Boolean.TRUE.equals(panel.getMethod("showable", Building.class).invoke(null, turret));
      check("组合炮台会弹悬浮面板", showable);

      // 喂铜 → 等它把池子里的弹药搬进弹仓
      turret.items.add(Items.copper, 60);
      run(120);
      Object types = panel.getMethod("ammoTypes", Building.class).invoke(null, turret);
      Object counts = panel.getMethod("ammoCounts", Building.class).invoke(null, turret);
      int cap = (Integer) panel.getMethod("ammoCap", Building.class).invoke(null, turret);
      int loaded = 0;
      if (counts instanceof arc.struct.ObjectIntMap<?> m)
        for (var e : m.entries()) loaded += e.value;
      String desc = String.valueOf(panel.getMethod("describe", Building.class).invoke(null, turret));
      System.out.println("[CPA] 弹仓诊断: 种类=" + (types instanceof arc.struct.Seq<?> s ? s.size : -1)
          + " 已装合计=" + loaded + " 上限=" + cap + " describe=" + desc);
      check("面板能列出弹药种类", types instanceof arc.struct.Seq<?> s2 && s2.size > 0);
      check("池子里的铜被搬进了弹仓（已装 " + loaded + "）", loaded > 0);
      check("摘要里有弹仓一段（describe）", desc.contains("弹仓") && desc.contains("/"));
      check("弹仓上限 > 0（" + cap + "）", cap > 0);

      System.out.println("[CPA] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(2);
    }
  }
}
