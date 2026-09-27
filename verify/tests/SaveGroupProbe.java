package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 读用户存档，把"钻头 / 窑炉"这些方块**到底有没有组合在一起**查清楚：
 * 每台打印 方块类 / 位置 / 组合leader / 组员数 / 相邻同类方块 / 网络分量 / 是否被"不组合"名单挡下。
 *
 * 用法：verify/run-headless.sh mx /tmp/mp_q/data combine.dbg.SaveGroupProbe [-Dblocks=kiln,laser-drill]
 */
public class SaveGroupProbe implements ApplicationListener {
  static String dataDir = "/tmp/mp_q/data";
  static String want = System.getProperty("blocks", "kiln,laser-drill,pneumatic-drill,mechanical-drill,blast-drill");
  static ClassLoader ml;
  public static void main(String[] a) {
    if (a.length > 0) dataDir = a[0];
    Vars.platform = new Platform() {};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if (l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[SGP] " + t); };
    new HeadlessApplication(new SaveGroupProbe(), t -> t.printStackTrace());
  }
  static void run(int f) { for (int i = 0; i < f; i++) { Time.delta = 1f; Vars.logic.update(); } }
  static Object callStatic(Class<?> c, String name, Class<?>[] sig, Object... args) {
    try { var m = sig == null ? c.getMethod(name) : c.getMethod(name, sig); m.setAccessible(true); return m.invoke(null, args); }
    catch (Throwable t) { return null; }
  }
  static Object field(Object o, String name) {
    if (o == null) return null;
    for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass())
      try { var f = k.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (Throwable ignored) { }
    return null;
  }

  @Override public void init() {
    try {
      Core.settings.setDataDirectory(Core.files.local(dataDir));
      Vars.loadLocales = false; Vars.loadSettings(); Vars.headless = true; Vars.init();
      UI.loadColors(); Fonts.loadContentIconsHeadless();
      Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
      Vars.mods.eachClass(Mod::init);
      if (Vars.logic == null) Vars.logic = new Logic();
      if (Vars.netServer == null) Vars.netServer = new NetServer();
      if (Vars.netClient == null) Vars.netClient = new NetClient();
      ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
      Map fallback = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(fallback, fallback.applyRules(Gamemode.survival));
      Vars.logic.play();
      run(10);

      arc.files.Fi file = null;
      for (arc.files.Fi f : Vars.saveDirectory.list())
        if (f.name().endsWith(".msav") && !f.name().contains("backup")) { file = f; break; }
      if (file == null) { System.out.println("[SGP] 没有存档"); System.exit(3); }
      System.out.println("[SGP] 读档 " + file.name());
      SaveIO.load(file);
      if (Vars.state.isPaused()) Vars.state.set(GameState.State.playing);
      run(120);

      Class<?> net = Class.forName("combine.net.ComboNet", true, ml);
      Class<?> reflect = Class.forName("combine.util.ComboReflect", true, ml);
      String[] names = want.split(",");
      for (String name : names) {
        for (Tile t : Vars.world.tiles) {
          Building b = t == null ? null : t.build;
          if (b == null || b.block == null || !b.block.name.equals(name)) continue;
          if (b.tile != t) continue; // 只认多格方块的中心格
          Object leader = callStatic(reflect, "leader", new Class<?>[] { Building.class }, b);
          Object group = callStatic(reflect, "group", new Class<?>[] { Building.class }, b);
          Seq<?> members = group instanceof Seq<?> s ? s : new Seq<>();
          Object netMembers = callStatic(net, "componentMembers", new Class<?>[] { Building.class }, b);
          int netSize = netMembers instanceof Seq<?> s2 ? s2.size : -1;
          StringBuilder adj = new StringBuilder();
          if (b.proximity != null)
            for (Building nb : b.proximity) {
              if (nb == null || nb.block == null) continue;
              adj.append(nb.block.name).append('@').append(nb.tileX()).append(',').append(nb.tileY()).append(' ');
            }
          boolean blocked = false;
          try {
            Class<?> coop = Class.forName("combine.coop.CoopCombo", true, ml);
            blocked = (Boolean) coop.getMethod("isBlocked", String.class).invoke(null, b.block.name);
          } catch (Throwable ignored) { }
          System.out.println("[SGP] " + b.block.name + "@" + b.tileX() + "," + b.tileY()
              + " 实体类=" + b.getClass().getSimpleName()
              + " 组长=" + (leader instanceof Building lb ? lb.tileX() + "," + lb.tileY() : String.valueOf(leader))
              + " 本地组员=" + members.size + " 网络成员=" + netSize
              + " 不组合名单命中=" + blocked);
          System.out.println("[SGP]     相邻: " + adj);
          if (b.items != null)
            System.out.println("[SGP]     物品模块=" + System.identityHashCode(b.items) + " 池内合计=" + b.items.total()
                + " 容量=" + field(b, "comboTotalItemCap"));
        }
      }
      Core.app.exit();
    } catch (Throwable t) { t.printStackTrace(); Core.app.exit(); }
  }
}
