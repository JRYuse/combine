package combine.dbg;

import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.files.Fi;
import arc.util.Log;
import mindustry.Vars;
import mindustry.core.Platform;
import mindustry.io.SaveIO;
import mindustry.io.SaveMeta;
import mindustry.mod.Mod;
import mindustry.net.Net;

/**
 * 只读存档元信息（地图名 / 需要哪些模组 / 规则），用来搭"用户复现"的数据集。
 * 用法：verify/run-headless.sh mx <任意数据目录> combine.dbg.SaveMetaProbe -Dsave=/path/x.msav
 */
public class SaveMetaProbe implements arc.ApplicationListener {
  static String dataDir = "/tmp/mp_cj/data";
  static String save = System.getProperty("save", "/root/sd/q/planetaryTerminal.msav");

  public static void main(String[] a) {
    if (a.length > 0) dataDir = a[0];
    Vars.platform = new Platform() {
    };
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> System.out.println("[SM] " + t);
    new HeadlessApplication(new SaveMetaProbe(), t -> t.printStackTrace());
  }

  @Override
  public void init() {
    try {
      Core.settings.setDataDirectory(Core.files.local(dataDir));
      Vars.loadLocales = false;
      Vars.loadSettings();
      Vars.headless = true;
      Vars.init();
      Vars.content.createBaseContent();
      Vars.mods.loadScripts();
      Vars.content.createModContent();
      Vars.content.init();
      Vars.mods.eachClass(Mod::init);

      Fi fi = Core.files.absolute(save);
      System.out.println("[SM] 文件=" + fi.absolutePath() + " 存在=" + fi.exists() + " 字节=" + fi.length());
      SaveMeta meta = SaveIO.getMeta(fi);
      System.out.println("[SM] 版本=" + SaveIO.getVersion().version);
      System.out.println("[SM] isMap=" + meta.isMap() + " 地图=" + meta.map
          + (meta.map == null ? "" : (" 名=" + meta.map.name() + " 宽高=" + meta.map.width + "x" + meta.map.height)));
      System.out.println("[SM] tags=" + meta.tags);
      System.out.println("[SM] 需要模组=" + java.util.Arrays.toString(meta.mods));
      System.out.println("[SM] 规则: defaultTeam=" + (meta.rules == null ? "?" : meta.rules.defaultTeam)
          + " waves=" + (meta.rules == null ? "?" : meta.rules.waves)
          + " attackMode=" + (meta.rules == null ? "?" : meta.rules.attackMode));
      System.out.println("[SM] 存档时间=" + meta.timestamp + " 波次=" + meta.wave);
    } catch (Throwable t) {
      System.out.println("[SM] 异常: " + t);
      t.printStackTrace();
    }
    Core.app.exit();
  }
}
