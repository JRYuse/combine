package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："核心的悬浮面板物品最大容量只显示该核心的物品容量，没有把其他核心和容器扩容的部分算进去"。
 *
 * 核心真正的每种物品上限写在 {@code CoreBuild.storageCapacity} 里（原版按"核心 + 相邻仓库"算，
 * 本模组的组合仓库/节点并仓继续往上加）。面板/信息面板的分母必须用这个数字，
 * 而不是只把 core.block.itemCapacity 加一遍。
 */
public class CorePanelCapTest implements ApplicationListener {
  static String dataDir = "/tmp/mp_cj/data";
  static int pass = 0, fail = 0;
  static ClassLoader ml;
  public static void main(String[] a) {
    if (a.length > 0) dataDir = a[0];
    Vars.platform = new Platform() {};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if (l.ordinal() >= arc.util.Log.LogLevel.err.ordinal()) System.out.println("[CP2] " + t); };
    new HeadlessApplication(new CorePanelCapTest(), t -> t.printStackTrace());
  }
  static void check(String n, boolean ok) { System.out.println("[CP2] " + (ok ? "PASS " : "FAIL ") + n); if (ok) pass++; else fail++; }
  static void run(int f) { for (int i = 0; i < f; i++) { Time.delta = 1f; Vars.logic.update(); } }
  static Block findExact(String name) { for (Block b : Vars.content.blocks()) if (b.name.equals(name)) return b; return null; }
  static Building place(Block b, int x, int y, Team team) {
    int size = Math.max(b.size, 1);
    int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
    mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
    mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte) 0, team, null);
    Building bu = Vars.world.build(ax, ay);
    if (bu != null) { try { bu.created(); } catch (Throwable ignored) {} try { bu.updateProximity(); } catch (Throwable ignored) {} }
    return bu;
  }
  static int panelCap(Building b) {
    try {
      Class<?> net = Class.forName("combine.net.ComboNet", true, ml);
      return (Integer) net.getMethod("panelItemCap", Building.class).invoke(null, b);
    } catch (Throwable t) { return -1; }
  }

  @Override public void init() {
    try {
      Core.settings.setDataDirectory(Core.files.local(dataDir));
      Vars.loadLocales = false; Vars.loadSettings(); Vars.headless = true; Vars.init();
      UI.loadColors(); Fonts.loadContentIconsHeadless();
      Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
      Vars.mods.eachClass(Mod::init);
      ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
      if (Vars.logic == null) Vars.logic = new Logic();
      if (Vars.netServer == null) Vars.netServer = new NetServer();
      if (Vars.netClient == null) Vars.netClient = new NetClient();
      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.logic.play();
      run(20);
      for (int y = 25; y < 175; y++) for (int x = 10; x < 250; x++) {
        Tile t = Vars.world.tile(x, y);
        if (t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
      }
      run(5);

      Block core = findExact("core-shard"), cont = findExact("container");
      if (core == null || cont == null) { System.out.println("[CP2] 缺方块"); System.exit(3); }
      Building c = place(core, 60, 60, Team.sharded);
      run(20);
      int alone = panelCap(c);
      int coreOwn = core.itemCapacity;
      System.out.println("[CP2] 只有核心：面板容量=" + alone + " 核心自身 itemCapacity=" + coreOwn
          + " storageCapacity=" + ((mindustry.world.blocks.storage.CoreBlock.CoreBuild) c).storageCapacity);
      check("只有核心时面板容量 ≥ 核心自身容量（" + alone + " ≥ " + coreOwn + "）", alone >= coreOwn);

      Building k1 = place(cont, 63, 60, Team.sharded);
      Building k2 = place(cont, 65, 60, Team.sharded);
      run(60);
      int withCont = panelCap(c);
      int storage = ((mindustry.world.blocks.storage.CoreBlock.CoreBuild) c).storageCapacity;
      System.out.println("[CP2] 贴两台容器后：面板容量=" + withCont + " storageCapacity=" + storage
          + " 容器同池=" + (c.items == k1.items) + "/" + (c.items == k2.items));
      check("容器真的并进核心（同池）", c.items == k1.items && c.items == k2.items);
      check("面板容量跟着容器扩容涨了（" + alone + " → " + withCont + "）", withCont > alone);
      check("面板容量把 storageCapacity（核心+容器）算进去（" + withCont + " ≥ " + storage + "）", withCont >= storage);

      // 再来一个**不相连**的核心：本模组的核心容量是"本队所有核心 + 连通仓库"，
      // 而面板原来只把"这一台核心 + 它这一组的成员"加起来 —— 另一个核心扩出来的部分就漏了
      // （用户报的"没有把其他核心……算进去"）。
      Building c2 = place(core, 80, 80, Team.sharded);
      run(60);
      int storage2 = ((mindustry.world.blocks.storage.CoreBlock.CoreBuild) c).storageCapacity;
      int cap2 = panelCap(c);
      System.out.println("[CP2] 再放一个不相连的核心后：面板容量=" + cap2 + " storageCapacity=" + storage2
          + "（两台核心自身 " + coreOwn + " + " + core.itemCapacity + " + 容器 600）");
      check("面板容量把另一个核心也算进去（" + cap2 + " ≥ " + storage2 + "）", cap2 >= storage2);

      System.out.println("[CP2] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
  }
}
