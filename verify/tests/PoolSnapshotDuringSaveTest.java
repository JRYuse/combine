package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报的"工厂资源间歇性清零"。
 *
 * 根因：原版 {@code Saves.update()} 里触发自动存档时先 {@code saving = true}，
 * 存完还要 {@code Time.runTask(3f, () -> saving = false)} —— 也就是**存档结束后的那几个时间单位里
 * {@code saves.isSaving()} 仍然是 true**。而方块快照每 snapshotInterval 发一次，正好会掉进这段窗口。
 * 组合建筑的 {@code writeBase} 用 {@code ComboSaveState.writingSave()} 判断"要不要给非组长写空模块"
 * （存档里必须写空，否则读档会翻倍）——以前它**先看这个标志**，于是那几帧里的联机快照被当成存档：
 * 非组长写空模块 → 客户端读到空模块（{@code ItemModule.read} 先清零）→ 整组池子在客户端清零，
 * 下一帧快照再恢复 = 用户看到的"工厂资源间歇性清零"。
 *
 * 修法：{@code writingSave()} 先看**调用栈**（栈里有 {@code mindustry.core.NetServer*} → 联机快照，
 * 一律写真数据；有 {@code mindustry.io.Save*}/{@code MapIO} → 真存档），都没有才回落到 isSaving() 标志。
 *
 * 这个测试：造两台共池的组合发电机 → 把假的"正在存档"标志置真（用 {@link mindustry.core.NetServerProbe}
 * 模拟联机快照那条调用栈）→ 取后一台的快照读回前一合 → 整组池子必须一份不少。
 */
public class PoolSnapshotDuringSaveTest implements ApplicationListener {
  static String dataDir = "/tmp/mp_cj/data";
  static int pass = 0, fail = 0;
  static ClassLoader ml;
  public static void main(String[] a) {
    if (a.length > 0) dataDir = a[0];
    Vars.platform = new Platform() {};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if (l.ordinal() >= arc.util.Log.LogLevel.err.ordinal()) System.out.println("[PSD] " + t); };
    new HeadlessApplication(new PoolSnapshotDuringSaveTest(), t -> t.printStackTrace());
  }
  static void check(String n, boolean ok) { System.out.println("[PSD] " + (ok ? "PASS " : "FAIL ") + n); if (ok) pass++; else fail++; }
  static void run(int f) { for (int i = 0; i < f; i++) { Time.delta = 1f; Vars.logic.update(); } }
  static Block byClass(String cls, boolean needItems) {
    for (Block b : Vars.content.blocks())
      if (b.getClass().getName().equals(cls) && b.size == 1 && (!needItems || b.hasItems)) return b;
    return null;
  }
  static Building place(Block b, int x, int y, Team team) {
    mindustry.world.Build.beginPlace(null, b, team, x, y, 0, null);
    mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(x, y), b, null, (byte) 0, team, null);
    Building bu = Vars.world.build(x, y);
    if (bu != null) { try { bu.created(); } catch (Throwable ignored) {} try { bu.updateProximity(); } catch (Throwable ignored) {} }
    return bu;
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

      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.logic.play();
      run(20);
      for (int y = 25; y < 175; y++) for (int x = 10; x < 250; x++) {
        Tile t = Vars.world.tile(x, y);
        if (t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
      }
      run(5);
      place(Blocks.coreShard, 60, 60, Team.sharded);
      run(10);

      Block gen = byClass("combine.production.CombinedGenerator", true);
      if (gen == null) gen = byClass("combine.production.CombinedCrafter", true);
      if (gen == null) { System.out.println("[PSD] 没找到组合发电机/工厂"); System.exit(3); }
      Building a = place(gen, 80, 80, Team.sharded);
      Building b = place(gen, 81, 80, Team.sharded);
      run(60);
      System.out.println("[PSD] 方块=" + gen.name + " 两台同池=" + (a.items == b.items)
          + " 合并后池子=" + (a.items == null ? -1 : a.items.total()));
      check("两台相邻的组合发电机共用一份物品池", a.items == b.items && a.items != null);
      if (a.items != null) {
        a.items.add(Items.coal, 30);
        a.items.add(Items.pyratite, 10);
      }
      run(10);
      int before = a.items == null ? -1 : a.items.total();

      // 造一个"正在存档"的假象：Vars.control.saves.isSaving() == true（原版存档后还会残留 3 个时间单位）
      Object oldControl = Vars.control;
      boolean flagSet = false;
      try {
        Object ctl = allocateUnsafe(Control.class);
        Object saves = allocateUnsafe(mindustry.game.Saves.class);
        setField(mindustry.game.Saves.class, saves, "saving", true);
        setField(Control.class, ctl, "saves", saves);
        Vars.control = (Control) ctl;
        flagSet = Vars.control.saves != null && Vars.control.saves.isSaving();
      } catch (Throwable t) {
        System.out.println("[PSD] 没能造假存档标志: " + t);
      }
      check("（前置）造出「正在存档」标志（saves.isSaving()==true，模拟存档残留窗口）", flagSet);

      // ① 直接调用（栈里没有 NetServer）→ 应当按"存档"算（这是存档路径的语义）
      Class<?> saveState = Class.forName("combine.saves.ComboSaveState", true, ml);
      boolean direct = (Boolean) saveState.getMethod("writingSave").invoke(null);
      // ② 从 NetServerProbe（类名以 mindustry.core.NetServer 开头）里调用 → 必须按"联机快照"算
      boolean viaNet = mindustry.core.NetServerProbe.probeWritingSave(ml);
      System.out.println("[PSD] writingSave: 直接调用=" + direct + " 从 NetServer 栈里调用=" + viaNet);
      check("直接调用（=存档路径）认成存档", direct);
      check("从 NetServer 调用栈里调用（=联机快照）认成快照（写真数据）", !viaNet);

      // ③ 端到端：在"正在存档"的假象下，从 NetServer 那条栈里取后一台的快照，读回前一台
      byte[] snap = mindustry.core.NetServerProbe.probeWriteSync(b);
      b.readSync(new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(snap))), b.version());
      int after = a.items == null ? -1 : a.items.total();
      System.out.println("[PSD] 存档窗口里的快照: 字节=" + snap.length + " 池子 " + before + " → " + after);
      check("存档窗口里发的联机快照仍然带真实池子（整组没被清零）", before > 0 && after == before);

      Vars.control = (Control) oldControl;

      System.out.println("[PSD] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
  }

  /** 不走构造器分配一个实例（造"假的正在存档"状态用；只在本测试里）。 */
  static Object allocateUnsafe(Class<?> cls) throws Exception {
    java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    f.setAccessible(true);
    sun.misc.Unsafe u = (sun.misc.Unsafe) f.get(null);
    return u.allocateInstance(cls);
  }

  static void setField(Class<?> cls, Object obj, String name, Object value) throws Exception {
    java.lang.reflect.Field f = null;
    for (Class<?> k = cls; k != null && f == null; k = k.getSuperclass()) {
      try { f = k.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
    }
    f.setAccessible(true);
    f.set(obj, value);
  }
}
