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
import mindustry.io.SaveIO;
import mindustry.mod.Mod;
import mindustry.net.Net;
import mindustry.ui.Fonts;

/**
 * 回归：加载指定目录里的所有 *.msav（老地图 / 老存档）。
 *
 * 背景（用户报的"部分地图加载报 Unknown object type: 63 / Error reading region entities"）：
 *   · 原因一：combine 把"所有 LegacyRegion 子类"都换成通用包装器，把 Save1..Save4 各自
 *     覆写的 readEntities（老格式没有 entity ID 映射段）弄丢了 —— 读 v4 老地图一进 entities
 *     区域就错位。现在 v1..v5 保持原版读取器。
 *   · 原因二：组合建筑的 read() 没有照着原版"按 revision 门控"，读老地图时多读字节，
 *     地图区错位（典型：CombinedDrill 读 revision=0 的钻头、CombinedCrafter 的 Separator
 *     读 revision=0 的 seed）。现在两处都按原版口径门控。
 *
 * 用法: verify/run-headless.sh mx <数据目录> combine.dbg.MapLoadTest [地图目录]
 *   默认地图目录 = /root/sd/q/maps（用户放坏档的地方）。
 */
public class MapLoadTest implements arc.ApplicationListener {
  static String dataDir = "/tmp/mp_cj/data";
  static String mapDir = "/root/sd/q/maps";
  static int pass = 0, fail = 0;

  public static void main(String[] a) {
    if (a.length > 0)
      dataDir = a[0];
    if (a.length > 1)
      mapDir = a[1];
    Vars.platform = new Platform() {
    };
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> {
      if (l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal())
        System.out.println("[L " + l + "] " + t);
    };
    new HeadlessApplication(new MapLoadTest(), t -> t.printStackTrace());
  }

  static void check(String name, boolean ok) {
    System.out.println("[ML] " + (ok ? "PASS " : "FAIL ") + name);
    if (ok)
      pass++;
    else
      fail++;
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

      Fi dir = Core.files.absolute(mapDir);
      if (!dir.exists()) {
        System.out.println("[ML] 地图目录不存在: " + mapDir);
        Core.app.exit();
        return;
      }
      for (Fi f : dir.list()) {
        if (!f.extEquals("msav"))
          continue;
        try {
          SaveIO.load(f);
          check(f.name() + " 能加载", true);
        } catch (Throwable t) {
          Throwable c = t;
          while (c.getCause() != null)
            c = c.getCause();
          check(f.name() + " 能加载（" + t + " / 根因 " + c + "）", false);
        }
      }
      System.out.println("[ML] 结果: PASS=" + pass + " FAIL=" + fail);
      Core.app.exit();
    } catch (Throwable t) {
      System.out.println("[ML] 测试异常");
      t.printStackTrace();
      Core.app.exit();
    }
  }
}
