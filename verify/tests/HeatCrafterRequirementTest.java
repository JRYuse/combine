package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："需要热量的组合工厂组合后，只需要供给一个工厂的热量就能满效率，
 * 但悬浮面板里的效率是正确的。"
 *
 * 根因：{@code heatEfficiency()} 原来除以**单台**的 {@code heatRequirement}，
 * 而 {@code availableHeat()} 是**整组**共享的热量池 —— 2 台各需 24 的组合体只要 24 热量
 * 就双双满效率（悬浮面板按整组 48 算，显示 63%，和实际对不上）。
 *
 * 判定：一台产热机 + 一台大气收集器 → 效率照常；再并一台同型（组合成 2 台）→
 * 需求翻倍，同样的热量下效率必须≈原来的一半，而不是仍然是 100%。
 */
public class HeatCrafterRequirementTest implements ApplicationListener {
  static String dataDir = "/tmp/mp_cj/data";
  static int pass = 0, fail = 0;
  public static void main(String[] a) {
    if (a.length > 0) dataDir = a[0];
    Vars.platform = new Platform() {};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if (l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[HCR] " + t); };
    new HeadlessApplication(new HeatCrafterRequirementTest(), t -> t.printStackTrace());
  }
  static void check(String n, boolean ok) { System.out.println("[HCR] " + (ok ? "PASS " : "FAIL ") + n); if (ok) pass++; else fail++; }
  static void run(int f) { for (int i = 0; i < f; i++) { Time.delta = 1f; Vars.logic.update(); } }
  static Block find(String name) { for (Block b : Vars.content.blocks()) if (b.name.equals(name)) return b; return null; }
  static Building place(Block b, int x, int y, Team team) {
    int size = Math.max(b.size, 1);
    int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
    mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
    mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte) 0, team, null);
    Building bu = Vars.world.build(ax, ay);
    if (bu != null) { try { bu.created(); } catch (Throwable ignored) {} try { bu.updateProximity(); } catch (Throwable ignored) {} }
    return bu;
  }
  static float f(Object o, String name) {
    try {
      Class<?> k = o.getClass();
      while (k != null) {
        try { java.lang.reflect.Field fd = k.getDeclaredField(name); fd.setAccessible(true); return ((Number) fd.get(o)).floatValue(); }
        catch (NoSuchFieldException e) { k = k.getSuperclass(); }
      }
    } catch (Throwable ignored) { }
    return -1f;
  }
  static float heatEff(Object o) {
    try { return (Float) o.getClass().getMethod("heatEfficiency").invoke(o); } catch (Throwable t) { return -1f; }
  }
  static float availHeat(Object o) {
    try { return (Float) o.getClass().getMethod("availableHeat").invoke(o); } catch (Throwable t) { return -1f; }
  }
  static float comboReq(Object o) {
    try { return (Float) o.getClass().getMethod("comboHeatRequirement").invoke(o); } catch (Throwable t) { return -1f; }
  }
  /** 产热机朝向 target（原版 HeatProducer 要"对着"才供热）。 */
  static void face(Building heater, Building target) {
    try {
      int dir = heater.tileX() > target.tileX() ? 2
          : heater.tileX() < target.tileX() ? 0
          : (heater.tileY() > target.tileY() ? 3 : 1);
      var fd = Building.class.getField("rotation");
      fd.setAccessible(true);
      fd.setInt(heater, dir);
    } catch (Throwable t) { System.out.println("[HCR] 设置朝向失败: " + t); }
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
      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.logic.play();
      Vars.state.rules.canGameOver = false;
      Vars.state.rules.waves = false;
      run(20);
      for (int y = 40; y < 140; y++) for (int x = 20; x < 240; x++) {
        Tile t = Vars.world.tile(x, y);
        if (t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
      }
      run(5);
      place(Blocks.coreShard, 60, 60, Team.sharded);
      run(10);

      Block crafter = find("atmospheric-concentrator");
      Block heater = find("slag-heater");
      if (crafter == null || heater == null) { System.out.println("[HCR] 缺方块（atmospheric-concentrator / slag-heater）"); System.exit(3); }
      float req = f(crafter, "heatRequirement");
      float out = f(heater, "heatOutput");
      System.out.println("[HCR] 方块: 需热=" + crafter.name + "(req=" + req + ") 产热=" + heater.name + "(out=" + out + ")");

      // 一台需热工厂 + 一台产热机（贴着并朝向它）
      int csize = Math.max(crafter.size, 1);
      Building c1 = place(crafter, 80, 80, Team.sharded);
      // 产热机贴着它的右边（矿渣制热机要"对着"耗热方，且要喂矿渣才有热量）
      Building h = place(heater, 80 + csize, 80, Team.sharded);
      run(5);
      if (h != null && h.liquids != null)
        h.liquids.add(mindustry.content.Liquids.slag, 5000f);
      face(h, c1);
      c1.updateProximity();
      h.updateProximity();
      // 供点电（有的需热工厂也要电）
      Block src = find("power-source");
      if (src != null) { Building p = place(src, 78, 80, Team.sharded); run(5); p.updateProximity(); c1.updateProximity(); }
      run(180);
      float a1 = availHeat(c1), e1 = heatEff(c1), r1 = comboReq(c1);
      System.out.println("[HCR] 单台: 可得热=" + a1 + " 需求=" + r1 + " 效率=" + e1);
      check("单台：效率 ≈ min(可得热/需求, 1)（" + e1 + "）", Math.abs(e1 - Math.min(a1 / Math.max(r1, 1e-4f), 1f)) < 0.02f);
      check("单台：整组需求 = 单台需求（" + r1 + " ≈ " + req + "）", Math.abs(r1 - req) < 0.01f);

      // 再并一台同型（组合成 2 台）→ 需求翻倍
      Building c2 = place(crafter, 80, 80 + csize, Team.sharded);
      run(180);
      float a2 = availHeat(c2), e2 = heatEff(c2), r2 = comboReq(c2);
      float expect = Math.min(a2 / Math.max(2f * req, 1e-4f), 1f);
      System.out.println("[HCR] 组合两台: 可得热=" + a2 + " 整组需求=" + r2 + " 效率=" + e2
          + "（按整组需求应该是 " + expect + "；按单台需求会算成 " + Math.min(a2 / Math.max(req, 1e-4f), 1f) + "）");
      check("组合后：整组需求 = 2×单台（" + r2 + " ≈ " + (2f * req) + "）", Math.abs(r2 - 2f * req) < 0.01f);
      check("组合后：效率按整组需求算（" + e2 + " ≈ " + expect + "）", Math.abs(e2 - expect) < 0.02f);
      check("组合后：只供给一台的热量不再满效率（" + e2 + " < 1）", e2 < 0.99f);

      System.out.println("[HCR] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
  }
}
