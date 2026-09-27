package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户要求："把 AutoDoor 作为 LinkWall 的一个新模式融入 LinkWall，并在 Main 里加替换 AutoDoor 的部分"。
 *
 * 原版 AutoDoor（blast-door）extends Wall —— 原来 processWalls 按"普通墙"把它也换成了 LinkWall，
 * 自动开关的能力就没了。现在它按 LinkWall.Mode.autodoor 融入：
 *   · 靠近地面单位（非腿式）就开、走开就关（原版 AutoDoor 那套判据）；
 *   · 服务端权威决定开/关（玩家点它不做任何事）；
 *   · 地图区里原版 AutoDoor 的那一份字节（一个 bool open）照写，关掉模组还能被原版读回。
 */
public class AutoDoorTest implements ApplicationListener {
  static String dataDir = "/tmp/mp_cj/data";
  static int pass = 0, fail = 0;
  public static void main(String[] a) {
    if (a.length > 0) dataDir = a[0];
    Vars.platform = new Platform() {};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if (l.ordinal() >= arc.util.Log.LogLevel.err.ordinal()) System.out.println("[AD] " + t); };
    new HeadlessApplication(new AutoDoorTest(), t -> t.printStackTrace());
  }
  static void check(String n, boolean ok) { System.out.println("[AD] " + (ok ? "PASS " : "FAIL ") + n); if (ok) pass++; else fail++; }
  static void run(int f) { for (int i = 0; i < f; i++) { arc.util.Time.delta = 1f; Vars.logic.update(); } }
  static Block byName(String name) { for (Block b : Vars.content.blocks()) if (b.name.equals(name)) return b; return null; }
  static Building place(Block b, int x, int y, Team team) {
    int size = Math.max(b.size, 1);
    int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
    mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
    mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte) 0, team, null);
    Building bu = Vars.world.build(ax, ay);
    if (bu != null) { try { bu.updateProximity(); } catch (Throwable ignored) {} }
    return bu;
  }
  static Object fld(Object o, String name) {
    try {
      Class<?> c = o.getClass();
      while (c != null) {
        try { var f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
        catch (NoSuchFieldException e) { c = c.getSuperclass(); }
      }
    } catch (Throwable ignored) {}
    return null;
  }
  static boolean boolField(Object o, String name) { Object v = fld(o, name); return v instanceof Boolean b && b; }
  static String modeOf(Building b) { Object v = fld(b.block, "mode"); return v == null ? "null" : v.toString(); }

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
      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.logic.play();
      Vars.state.rules.unitCap = 100; // 无核心时原版 unitCap=0 会把刚 add 的单位当超编杀掉
      run(20);
      for (int y = 25; y < 175; y++) for (int x = 10; x < 250; x++) {
        Tile t = Vars.world.tile(x, y);
        if (t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
      }
      run(5);

      Block auto = byName("blast-door");
      Block door = byName("door");
      if (auto == null) {
        System.out.println("[AD] 这套数据集里没有 blast-door（低版本），跳过自动门用例");
        System.out.println("[AD] RESULT ALL PASS (pass=" + pass + ")");
        System.exit(0);
      }
      Building autoDemo = place(auto, 60, 60, Team.sharded);
      run(5);
      check("blast-door 被替换成组合类（" + auto.getClass().getName() + "）",
          auto.getClass().getName().equals("combine.defense.LinkWall"));
      check("blast-door 走的是 LinkWall 的 autodoor 模式（" + modeOf(autoDemo) + "）",
          "autodoor".equals(modeOf(autoDemo)));
      if (door != null) {
        Building doorDemo = place(door, 70, 60, Team.sharded);
        run(5);
        check("普通门还是 door 模式（" + modeOf(doorDemo) + "）",
            "door".equals(modeOf(doorDemo)));
      }

      // 自动开关：靠近地面单位 → 开；走开 → 关
      Building ad = place(auto, 90, 60, Team.sharded);
      run(30);
      boolean closedAtStart = !boolField(ad, "open");
      check("没单位时自动门是关的（open=" + boolField(ad, "open") + "）", closedAtStart);
      Unit u = UnitTypes.dagger.create(Team.sharded);
      u.set(ad.x + 8f, ad.y);
      u.add();
      run(60);
      boolean openedNearUnit = boolField(ad, "open");
      System.out.println("[AD] 单位靠近后 open=" + openedNearUnit + "（单位=" + u.type.name + " grounded=" + u.isGrounded()
          + " allowLegStep=" + u.type.allowLegStep + "）");
      check("地面单位靠近 → 自动门打开", openedNearUnit);
      u.remove();
      run(60);
      check("单位走开 → 自动门关上（open=" + boolField(ad, "open") + "）", !boolField(ad, "open"));

      // 空气单位不该触发（原版判据：只认地面单位）
      Building ad2 = place(auto, 95, 60, Team.sharded);
      run(30);
      Unit air = UnitTypes.flare.create(Team.sharded);
      air.set(ad2.x + 8f, ad2.y);
      air.add();
      run(60);
      check("空中单位不触发自动门（open=" + boolField(ad2, "open") + "）", !boolField(ad2, "open"));
      air.remove();
      run(10);

      // 地图区存读档：原版 AutoDoor 的字节（一个 bool）+ 读回来还是开的
      Building ad3 = place(auto, 100, 60, Team.sharded);
      run(20);
      Unit u3 = UnitTypes.dagger.create(Team.sharded);
      u3.set(ad3.x + 8f, ad3.y);
      u3.add();
      run(60);
      check("（前置）存档前自动门是开的", boolField(ad3, "open"));
      java.io.ByteArrayOutputStream sb = new java.io.ByteArrayOutputStream();
      arc.util.io.Writes wr = new arc.util.io.Writes(new java.io.DataOutputStream(sb));
      ad3.write(wr);
      byte[] doorBytes = sb.toByteArray();
      Building ad4 = place(auto, 104, 60, Team.sharded);
      arc.util.io.Reads rd = new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(doorBytes)));
      ad4.read(rd, ad4.version());
      System.out.println("[AD] 存档字节=" + doorBytes.length + " 读回后 open=" + boolField(ad4, "open"));
      check("自动门的开状态在存档字节里（" + doorBytes.length + " 字节，open 复原）", doorBytes.length == 1 && boolField(ad4, "open"));
      u3.remove();

      System.out.println("[AD] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
  }
}
