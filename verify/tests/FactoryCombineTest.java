package combine.dbg;

import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Liquids;
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
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.ui.Fonts;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.meta.BuildVisibility;

/**
 * 组合工厂（合体工厂）回归：
 * 框选多台工厂 → 合成 1x1 → 库存并池 / 拆掉原料 / 耗电生效 / 耗热生效 / 解体放回。
 *
 * <p>模组类走反射（测试只编译游戏 jar），游戏类照常直接用。
 */
public class FactoryCombineTest implements arc.ApplicationListener {
  static String dataDir = "/tmp/mp_coop/data";
  static int pass = 0, fail = 0;
  static ClassLoader ml;
  static Class<?> clsCF, clsCombiner;

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
    new HeadlessApplication(new FactoryCombineTest(), t -> t.printStackTrace());
  }

  static void check(String n, boolean ok) {
    System.out.println("[FC] " + (ok ? "PASS " : "FAIL ") + n);
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

  static Object callStatic(Class<?> c, String name, Class<?>[] sig, Object... args) {
    try {
      var m = sig == null ? c.getMethod(name) : c.getMethod(name, sig);
      m.setAccessible(true);
      return m.invoke(null, args);
    } catch (NoSuchMethodException nsme) {
      // 包级可见（非 public）的静态工具方法：getMethod 找不到，退回 getDeclaredMethod
      try {
        var m = sig == null ? c.getDeclaredMethod(name) : c.getDeclaredMethod(name, sig);
        m.setAccessible(true);
        return m.invoke(null, args);
      } catch (Throwable t) {
        System.out.println("[FC] 调用 " + c.getSimpleName() + "." + name + " 失败: " + t);
        return null;
      }
    } catch (Throwable t) {
      System.out.println("[FC] 调用 " + c.getSimpleName() + "." + name + " 失败: " + t);
      return null;
    }
  }

  static Object call(Object o, String name, Class<?>[] sig, Object... args) {
    try {
      var m = sig == null ? o.getClass().getMethod(name) : o.getClass().getMethod(name, sig);
      m.setAccessible(true);
      return m.invoke(o, args);
    } catch (Throwable t) {
      System.out.println("[FC] 调用 " + name + " 失败: " + t);
      return null;
    }
  }

  static Object field(Object o, String name) {
    try {
      Class<?> c = o.getClass();
      while (c != null) {
        try {
          var f = c.getDeclaredField(name);
          f.setAccessible(true);
          return f.get(o);
        } catch (NoSuchFieldException ignored) {
          c = c.getSuperclass();
        }
      }
    } catch (Throwable t) {
      System.out.println("[FC] 读字段 " + name + " 失败: " + t);
    }
    return null;
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

  static void clearArea(int x1, int y1, int x2, int y2) {
    for (int y = y1; y <= y2; y++)
      for (int x = x1; x <= x2; x++) {
        Tile t = Vars.world.tile(x, y);
        if (t != null && t.block() != Blocks.air)
          t.setBlock(Blocks.air);
      }
  }

  static int totalOf(Building b, Seq<Item> items) {
    int n = 0;
    for (Item it : items)
      n += b.items.get(it);
    return n;
  }

  static int loadedCells(Building b) {
    Object r = callStatic(clsCF, "loadedCells", new Class<?>[] { Building.class }, b);
    return r instanceof Number n ? n.intValue() : -1;
  }

  static int itemCap(Building b) {
    Object r = callStatic(clsCF, "itemCapOf", new Class<?>[] { Building.class }, b);
    return r instanceof Number n ? n.intValue() : -1;
  }

  static float fieldFloat(Object o, String name) {
    for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
      try {
        var f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.getFloat(o);
      } catch (NoSuchFieldException ignored) {
      } catch (Throwable t) {
        return Float.NaN;
      }
    return Float.NaN;
  }

  static Object fieldObj(Object o, String name) {
    for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
      try {
        var f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
      } catch (NoSuchFieldException ignored) {
      } catch (Throwable t) {
        return null;
      }
    return null;
  }

  static boolean approx(float a, float b) {
    return Math.abs(a - b) < 0.001f;
  }

  /** 在抽屉树里找"有某个字段"的那个抽屉节点（例如 DrawSpikes 有 length、DrawPistons 有 sinMag）。 */
  static Object findDrawer(Object drawer, String fieldName) {
    if (drawer == null)
      return null;
    if (hasField(drawer, fieldName))
      return drawer;
    Object subs = fieldObj(drawer, "drawers");
    if (subs instanceof Object[] arr)
      for (Object c : arr) {
        Object r = findDrawer(c, fieldName);
        if (r != null)
          return r;
      }
    return null;
  }

  static boolean hasField(Object o, String name) {
    for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
      try {
        c.getDeclaredField(name);
        return true;
      } catch (NoSuchFieldException ignored) {
      }
    return false;
  }

  /** 反射调 SuperCombineFactory.sideFor（测试只编译游戏 jar，模组类只能反射）。 */
  static class SuperSide {
    static int sideFor(int count) {
      Object r = callStatic(clsCF, "sideFor", new Class<?>[] { int.class }, count);
      return r instanceof Number n ? n.intValue() : 2;
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

      ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
      clsCF = Class.forName("combine.production.SuperCombineFactory", true, ml);
      clsCombiner = Class.forName("combine.production.FactoryCombiner", true, ml);

      // 每种边长一个方块：3 台工厂 → 2x2
      Block cf = Vars.content.block("super-combine-factory-2");
      int registered = 0;
      for (int side = 2; side <= 8; side++) {
        Block b = Vars.content.block("super-combine-factory-" + side);
        if (b != null && b.size == side)
          registered++;
      }
      check("2x2..8x8 的 super-combine-factory 都注册了（" + registered + "/7）", registered == 7);
      check("super-combine-factory-2（class=combine.*，size=2）",
          cf != null && cf.getClass().getName().startsWith("combine.") && cf.size == 2);
      check("不进建造菜单（isVisible=false）", cf != null && !cf.isVisible());
      check("隐藏但可放置（isPlaceable=true / buildVisibility=hidden）",
          cf != null && cf.isPlaceable() && cf.buildVisibility == BuildVisibility.hidden);
      // 无头环境没有图集，uiIcon 允许为 null；真客户端由 Main.ensureIcons 兜底（见客户端截图）
      check("无头下图标允许 null（有图集时必须有）", Core.atlas == null || (cf != null && cf.uiIcon != null));

      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.state.rules.canGameOver = false;
      Vars.state.rules.waves = false;
      Vars.logic.play();
      run(20);
      clearArea(20, 20, 200, 150);
      run(5);

      Team team = Vars.state.rules.defaultTeam;

      // ---------- 1) 框选 + 布局 / 来源 / 库存 编码 ----------
      Building smelter = place(Vars.content.block("silicon-smelter"), 60, 60, team);
      Building press = place(Vars.content.block("graphite-press"), 70, 60, team);
      Building kiln = place(Vars.content.block("kiln"), 80, 60, team);
      run(5);
      check("三台工厂已摆好", smelter != null && press != null && kiln != null);
      if (smelter != null)
        smelter.items.add(Items.copper, 7);
      if (press != null)
        press.items.add(Items.lead, 5);

      Seq<Building> found = (Seq<Building>) callStatic(clsCombiner, "scan",
          new Class<?>[] { Team.class, int.class, int.class, int.class, int.class },
          team, 55, 50, 90, 70);
      check("框选扫到 3 台工厂（实际 " + (found == null ? -1 : found.size) + "）", found != null && found.size == 3);
      int count = found == null ? 0
          : ((Number) callStatic(clsCombiner, "count", new Class<?>[] { Seq.class }, found)).intValue();
      check("台数统计 = 3", count == 3);
      // 多格工厂只算一台：3x3 的压机不该吃 9 个名额
      Building big = place(Vars.content.block("multi-press"), 100, 100, team);
      run(3);
      Seq<Building> one = (Seq<Building>) callStatic(clsCombiner, "scan",
          new Class<?>[] { Team.class, int.class, int.class, int.class, int.class },
          team, 95, 95, 108, 108);
      check("3x3 压机只算 1 台（扫到 " + (one == null ? -1 : one.size) + "）", one != null && one.size == 1);
      clearArea(95, 95, 108, 108);
      run(3);

      // ---------- 1b) 分离机（Separator）也能合体 ----------
      // 【用户报的"Separator 为什么无法合体"】160.x 里 Separator 直接继承 Block（不是 GenericCrafter），
      // 旧口径把它判成"不是工厂"，框选时被跳过。这里锁死回归。
      Block sepWorld = Vars.content.block("separator");
      Building sep = place(sepWorld, 120, 60, team);
      run(3);
      check("分离机已摆好（world block=" + (sepWorld == null ? "null" : sepWorld.getClass().getName()) + "）",
          sep != null);
      Object sepCell = callStatic(clsCF, "cellBlock", new Class<?>[] { Block.class }, sepWorld);
      check("cellBlock(分离机) 非 null（= 分离机能当格子工厂）", sepCell != null);
      Seq<Building> sepFound = (Seq<Building>) callStatic(clsCombiner, "scan",
          new Class<?>[] { Team.class, int.class, int.class, int.class, int.class },
          team, 115, 55, 128, 70);
      int sepCount = sepFound == null ? 0
          : ((Number) callStatic(clsCombiner, "count", new Class<?>[] { Seq.class }, sepFound)).intValue();
      check("框选扫到分离机（x" + sepCount + "）", sepCount == 1);
      String sepLayout = (String) callStatic(clsCombiner, "layoutOf", new Class<?>[] { Seq.class }, sepFound);
      check("布局串含分离机（" + sepLayout + "）", sepLayout != null && sepLayout.contains("separator"));
      clearArea(115, 55, 128, 70);
      run(3);

      // ---------- 1c) "每格工厂缩成 1x1"：抽屉里的绝对长度要跟着缩 ----------
      // 用户报的"phase-weaver / phase-synthesizer / sporePress 缩放还是有问题"。
      Object psCell = callStatic(clsCF, "cellBlock", new Class<?>[] { Block.class },
          Vars.content.block("phase-synthesizer"));
      Object spCell = callStatic(clsCF, "cellBlock", new Class<?>[] { Block.class },
          Vars.content.block("spore-press"));
      Object pwCell = callStatic(clsCF, "cellBlock", new Class<?>[] { Block.class },
          Vars.content.block("phase-weaver"));
      check("拿到相位合成机/孢子压缩机/相织机的格子方块（" + psCell + "/" + spCell + "/" + pwCell + "）",
          psCell instanceof Block && spCell instanceof Block && pwCell instanceof Block);
      if (psCell instanceof Block ps && spCell instanceof Block sp && pwCell instanceof Block pw) {
        Object spikes = findDrawer(fieldObj(ps, "drawer"), "length");
        Object pistons = findDrawer(fieldObj(sp, "drawer"), "sinMag");
        Object weaveOwner = findDrawer(fieldObj(pw, "drawer"), "weave");
        check("相位合成机抽屉里有 DrawSpikes（radius/length/stroke）", spikes != null);
        check("孢子压缩机抽屉里有 DrawPistons（sinMag/lenOffset）", pistons != null);
        check("相织机抽屉里有 DrawWeave（weave 贴图）", weaveOwner != null);
        if (spikes != null) {
          float r0 = fieldFloat(spikes, "radius"), l0 = fieldFloat(spikes, "length"),
              s0 = fieldFloat(spikes, "stroke");
          callStatic(clsCF, "scaleDrawerOffsets", new Class<?>[] { Block.class, float.class }, ps, 0.5f);
          boolean scaled = approx(fieldFloat(spikes, "radius"), r0 * 0.5f)
              && approx(fieldFloat(spikes, "length"), l0 * 0.5f)
              && approx(fieldFloat(spikes, "stroke"), s0 * 0.5f);
          callStatic(clsCF, "restoreDrawerOffsets", null);
          check("DrawSpikes 的 radius/length/stroke 跟着缩（" + r0 + " / " + l0 + " / " + s0 + "）", scaled);
          check("画完还原（radius 回到 " + r0 + "）",
              approx(fieldFloat(spikes, "radius"), r0) && approx(fieldFloat(spikes, "length"), l0));
        }
        if (pistons != null) {
          float mag0 = fieldFloat(pistons, "sinMag"), len0 = fieldFloat(pistons, "lenOffset");
          float sof0 = fieldFloat(pistons, "sinOffset"), sid0 = fieldFloat(pistons, "sideOffset");
          callStatic(clsCF, "scaleDrawerOffsets", new Class<?>[] { Block.class, float.class }, sp, 0.5f);
          boolean ok = approx(fieldFloat(pistons, "sinMag"), mag0 * 0.5f)
              && approx(fieldFloat(pistons, "lenOffset"), len0 * 0.5f)
              && approx(fieldFloat(pistons, "sinOffset"), sof0)
              && approx(fieldFloat(pistons, "sideOffset"), sid0);
          callStatic(clsCF, "restoreDrawerOffsets", null);
          check("DrawPistons：sinMag/lenOffset(=" + len0 + ") 缩一半，sinOffset/sideOffset 不动", ok);
        }
        // 贴图尺寸那一半走全局精灵缩放 Draw.scl（无头没有图集，只能靠真客户端截图核对）
        check("无头下没有图集（贴图尺寸那半由客户端截图核对）", Core.atlas == null);
      }

      // ---------- 1d) 多台"不同液体"的工厂合体后，每格要画自己的液体 ----------
      // 【用户报的"多个有 DrawLiquidRegion/DrawLiquidTile 的工厂合体、液体不同时全画同一种"】
      // 合并后格子共用整台那一份 LiquidModule，liquids.current() 是池子里最多的那种。
      Object pcCell = callStatic(clsCF, "cellBlock", new Class<?>[] { Block.class },
          Vars.content.block("plastanium-compressor"));
      Object cmCell = callStatic(clsCF, "cellBlock", new Class<?>[] { Block.class },
          Vars.content.block("cryofluid-mixer"));
      check("拿到塑料压缩机/冷冻液混合机的格子方块（" + pcCell + "/" + cmCell + "）",
          pcCell instanceof Block && cmCell instanceof Block);
      if (pcCell instanceof Block pc && cmCell instanceof Block cm) {
        check("两台要的液体确实不同（塑料压缩机吃油=" + pc.consumesLiquid(Liquids.oil)
            + "，混合机吃水=" + cm.consumesLiquid(Liquids.water) + "）",
            pc.consumesLiquid(Liquids.oil) && cm.consumesLiquid(Liquids.water));
        // 造一个"油 + 水"都在的池子（水更多 → 旧口径下 current() 就是水，两格都会画水）
        mindustry.world.modules.LiquidModule pool = new mindustry.world.modules.LiquidModule();
        pool.set(Liquids.oil, 40f);
        pool.set(Liquids.water, 90f);
        check("池子的 current 是水（= 旧口径下每格都会画水，校验用例本身有效）",
            pool.current() == Liquids.water);
        Object l1 = callStatic(clsCF, "cellLiquid",
            new Class<?>[] { Block.class, mindustry.world.modules.LiquidModule.class }, pc, pool);
        Object l2 = callStatic(clsCF, "cellLiquid",
            new Class<?>[] { Block.class, mindustry.world.modules.LiquidModule.class }, cm, pool);
        String n1 = l1 instanceof Liquid lq ? lq.name : String.valueOf(l1);
        String n2 = l2 instanceof Liquid lq ? lq.name : String.valueOf(l2);
        check("两格挑到的液体不同（塑料压缩机=" + n1 + " / 混合机=" + n2 + "）",
            l1 instanceof Liquid a && l2 instanceof Liquid b && a != b);
        check("塑料压缩机那格挑到的是油", l1 == Liquids.oil);
        // 视图模块：只装这一格要的液体，量取整台池子
        Object view = callStatic(clsCF, "liquidViewFor",
            new Class<?>[] { Block.class, mindustry.world.modules.LiquidModule.class }, pc, pool);
        check("液体视图只装这一格的油（40，池子口径）",
            view instanceof mindustry.world.modules.LiquidModule vm
                && Math.abs(vm.get(Liquids.oil) - 40f) < 0.01f
                && vm.get(Liquids.water) < 0.01f && vm.current() == Liquids.oil);
        // 【旧口径的病根】分离机的抽屉是 `DrawLiquidTile()`（drawLiquid=null，画 liquids.current()），
        // 它自己吃的是**渣**；合并后格子共用整台那一份模块，current() 是池子里最多的水 →
        // 分离机那格也跟着画水。新口径按"这台方块吃的液体"取，应该画渣。
        Object sepCell2 = callStatic(clsCF, "cellBlock", new Class<?>[] { Block.class },
            Vars.content.block("separator"));
        Object sepLiquid = sepCell2 instanceof Block scb ? callStatic(clsCF, "cellLiquid",
            new Class<?>[] { Block.class, mindustry.world.modules.LiquidModule.class }, scb, pool) : "no-cell";
        check("分离机那格画的是它自己吃的渣（不是池子里最多的水）",
            sepLiquid == Liquids.slag && sepLiquid != pool.current());
        if (sepCell2 instanceof Block scb) {
          Object sepView = callStatic(clsCF, "liquidViewFor",
              new Class<?>[] { Block.class, mindustry.world.modules.LiquidModule.class }, scb, pool);
          check("分离机的液体视图里 water=0（不再退化成池子的 current）",
              sepView instanceof mindustry.world.modules.LiquidModule svm
                  && svm.get(Liquids.water) < 0.01f && svm.current() == Liquids.slag);
        }
      }

      String layout = (String) callStatic(clsCombiner, "layoutOf", new Class<?>[] { Seq.class }, found);
      String sources = (String) callStatic(clsCombiner, "sourcesOf", new Class<?>[] { Seq.class }, found);
      String[] carry = (String[]) callStatic(clsCombiner, "carryOf", new Class<?>[] { Seq.class }, found);
      check("布局串含三台工厂（" + layout + "）",
          layout != null && layout.contains("silicon-smelter") && layout.contains("graphite-press")
              && layout.contains("kiln") && layout.split(";", -1).length == 3);
      check("原料库存被带进配置（铜/铅都在）",
          carry != null && carry.length == 3 && carry[0] != null && carry[0].contains("i")
              && carry[1] != null);

      String config = (String) callStatic(clsCF, "encodeConfig",
          new Class<?>[] { String.class, String.class, String[].class }, layout, sources, carry);
      check("配置串走紧凑编码且不超长（长度 " + (config == null ? -1 : config.length()) + " ≤ 1150）",
          config != null && config.length() <= 1150);
      String[] back = (String[]) callStatic(clsCF, "decodeConfig", new Class<?>[] { String.class }, config);
      check("布局/来源能原样解回来", back != null && back[0].equals(layout) && back[1].equals(sources));

      // ---------- 2) 允许顶掉被框住的工厂 ----------
      Object framed = callStatic(clsCombiner, "framedTiles", new Class<?>[] { Seq.class }, found);
      callStatic(clsCF, "allowReplaceOver", new Class<?>[] { arc.struct.IntSet.class }, framed);
      check("允许在 2x2 工厂原位置上放 2x2 组合工厂",
          mindustry.world.Build.validPlace(cf, team, 60, 60, 0));
      // 【用户报的"旁边有建筑就放不下去"】预览 footprint 里夹着传送带/墙这类杂项建筑时也要能放：
      // 它们既不是原料、也不是要保护的对象，落地时随本台一起被顶掉（见 canReplace 的放行口径）。
      Building belt = place(Vars.content.block("conveyor"), 62, 60, team);
      Building wall = place(Vars.content.block("copper-wall"), 62, 61, team);
      run(3);
      check("传送带/墙已摆好（校验用例本身有效）", belt != null && wall != null);
      check("预览同时压住被框住的工厂 + 传送带/墙也能放（杂项建筑放行）",
          mindustry.world.Build.validPlace(cf, team, 61, 60, 0));
      // "不能顶掉没框住的工厂"这条保护不能因为放行杂项建筑而失效（multi-press 不在全局计数里）
      Building stray = place(Vars.content.block("multi-press"), 68, 68, team);
      run(3);
      check("没框住的工厂仍然挡住预览（不能顺手吃掉）",
          stray != null && !mindustry.world.Build.validPlace(cf, team, 68, 68, 0));

      // ---------- 3) 落地：吃掉原料 + 建格子 + 库存跟着过来 ----------
      run(3);
      Tile anchor = Vars.world.tile(60, 60);
      mindustry.gen.Call.constructFinish(anchor, cf, null, (byte) 0, team, config);
      run(10);
      Building merged = anchor.build;
      check("组合工厂已落地", merged != null && merged.block == cf);
      if (merged == null) {
        System.out.println("[FC] 落地失败，后续用例跳过");
        System.out.println("[FC] 结果: PASS=" + pass + " FAIL=" + fail);
        Core.app.exit();
        return;
      }
      check("里面装了 3 台工厂（实际 " + loadedCells(merged) + "）", loadedCells(merged) == 3);
      // 【用户要求】"每个工厂变成 1x1，不是全部加起来变成 1x1"：3 台 → 2x2 方块，
      // 每台落在自己那一格（行优先），互不重叠
      {
        Object[] cs = (Object[]) field(merged, "cells");
        arc.struct.IntSet spots = new arc.struct.IntSet();
        boolean inside = true;
        for (int i = 0; i < cs.length; i++) {
          if (!(cs[i] instanceof Building c)) {
            inside = false;
            break;
          }
          spots.add(c.tileX() * 1000 + c.tileY());
          // 行优先落在锚点（偶数边长 = 左下那一格）起的 size×size 棋盘里
          int ex = merged.tileX() - (merged.block.size - 1) / 2 + i % merged.block.size;
          int ey = merged.tileY() - (merged.block.size - 1) / 2 + i / merged.block.size;
          if (c.tileX() != ex || c.tileY() != ey)
            inside = false;
          // 【用户报的"有偏移"】每格必须画在**它自己那格的中心**（Mindustry：Tile.worldx()=x*8）
          if (Math.abs(c.x - c.tileX() * 8) > 0.01f || Math.abs(c.y - c.tileY() * 8) > 0.01f)
            inside = false;
        }
        check("方块占 " + merged.block.size + "x" + merged.block.size + " 格、每台工厂各占一格（"
            + spots.size + " 格、都在范围内=" + inside + "）",
            merged.block.size == 2 && spots.size == 3 && inside);
      }
      {
        Object[] cbs = (Object[]) field(merged, "cellBlocks");
        StringBuilder sb = new StringBuilder();
        if (cbs != null)
          for (Object o : cbs)
            sb.append(o == null ? "null" : ((Block) o).name).append(",");
        System.out.println("[FC] 格子方块: len=" + (cbs == null ? -1 : cbs.length) + " [" + sb + "]");
      }
      check("原版工厂库存并进来了（铜 " + merged.items.get(Items.copper)
          + " / 铅 " + merged.items.get(Items.lead) + "）",
          merged.items.get(Items.copper) == 7 && merged.items.get(Items.lead) == 5);
      check("容量 = 各格之和（物品 " + itemCap(merged) + " = 30）", itemCap(merged) == 30);

      int left = 0;
      for (Building b : mindustry.gen.Groups.build)
        if (b.block != null && b.block.name != null
            && ("silicon-smelter".equals(b.block.name) || "graphite-press".equals(b.block.name)
                || "kiln".equals(b.block.name)))
          left++;
      check("原料工厂已被吃掉（剩 " + left + " 台）", left == 0);

      // ---------- 4) 电力：没电不产，接上电源就产 ----------
      clearArea(40, 40, 50, 50);
      Building cf2 = place(cf, 40, 40, team);
      run(5);
      check("第二台组合工厂已摆好", cf2 != null);
      if (cf2 != null) {
        call(cf2, "configure", new Class<?>[] { Object.class }, "silicon-smelter@0");
        run(5);
        // 硅冶炼厂：2 沙 + 1 煤 → 1 硅（要电）
        cf2.items.add(Items.sand, 60);
        cf2.items.add(Items.coal, 60);
        int si0 = cf2.items.get(Items.silicon);
        run(240);
        int siNoPower = cf2.items.get(Items.silicon) - si0;
        check("没接电时硅冶炼厂不产（4 秒产了 " + siNoPower + "）", siNoPower == 0);

        Block src = Vars.content.block("power-source");
        if (src == null)
          check("找得到 power-source 方块", false);
        else {
          place(src, 42, 40, team); // 贴邻才连得上电网（本体是 2x2：40..41，右边那格是 42）
          run(30);
          int si1 = cf2.items.get(Items.silicon);
          run(240);
          int siPower = cf2.items.get(Items.silicon) - si1;
          check("接上电源后开始产硅（4 秒产了 " + siPower + "）", siPower > 0);
        }
      }

      // ---------- 5) 热量：需热工厂没热不产，给热就产 ----------
      // ---------- 4c) 原地合体（玩家最常见）：就压在原料工厂上放，三台都要被吃掉 ----------
      clearArea(60, 100, 92, 112);
      Building s1 = place(Vars.content.block("silicon-smelter"), 60, 100, team);
      Building s2 = place(Vars.content.block("graphite-press"), 66, 100, team);
      Building s3 = place(Vars.content.block("kiln"), 72, 100, team);
      run(5);
      Seq<Building> inPlace = (Seq<Building>) callStatic(clsCombiner, "scan",
          new Class<?>[] { Team.class, int.class, int.class, int.class, int.class },
          team, 55, 95, 80, 108);
      check("原地合体：框选扫到 3 台（实际 " + (inPlace == null ? -1 : inPlace.size) + "）",
          inPlace != null && inPlace.size == 3);
      if (inPlace != null && inPlace.size == 3) {
        int ipCount = ((Number) callStatic(clsCombiner, "count", new Class<?>[] { Seq.class }, inPlace))
            .intValue();
        int ipSide = SuperSide.sideFor(ipCount);
        String ipLayout = (String) callStatic(clsCombiner, "layoutOf",
            new Class<?>[] { Seq.class, int.class }, inPlace, ipSide * ipSide);
        String ipSources = (String) callStatic(clsCombiner, "sourcesOf", new Class<?>[] { Seq.class }, inPlace);
        String[] ipCarry = (String[]) callStatic(clsCombiner, "carryOf", new Class<?>[] { Seq.class }, inPlace);
        String ipConfig = (String) callStatic(clsCF, "encodeConfig",
            new Class<?>[] { String.class, String.class, String[].class }, ipLayout, ipSources, ipCarry);
        Object ipFramed = callStatic(clsCombiner, "framedTiles", new Class<?>[] { Seq.class }, inPlace);
        callStatic(clsCF, "allowReplaceOver", new Class<?>[] { arc.struct.IntSet.class }, ipFramed);
        Block ipBlock = Vars.content.block("super-combine-factory-" + ipSide);
        // 就放在第一台（60,100）的原位置上
        mindustry.gen.Call.constructFinish(Vars.world.tile(60, 100), ipBlock, null, (byte) 0, team, ipConfig);
        run(10);
        int remain = 0;
        for (Building b : mindustry.gen.Groups.build)
          if (b.block != null && b.block.name != null
              && ("silicon-smelter".equals(b.block.name) || "graphite-press".equals(b.block.name)
                  || "kiln".equals(b.block.name)))
            remain++;
        Building ipMerged = Vars.world.tile(60, 100).build;
        check("原地合体后三台原料工厂都消失了（剩 " + remain + " 台）", remain == 0);
        check("原地合体后组合工厂在位（格数 " + loadedCells(ipMerged) + "）",
            ipMerged != null && loadedCells(ipMerged) == 3);
      }

      // ---------- 5) 热量：需热工厂没热不产，给热就产 ----------
      // ---------- 4b) 产物要能输出 + 产出上限是"各格之和" ----------
      Block cf2x2 = Vars.content.block("super-combine-factory-2");
      String cfg3 = (String) callStatic(clsCF, "encodeConfig",
          new Class<?>[] { String.class, String.class, String[].class },
          "silicon-smelter@0;silicon-smelter@0;silicon-smelter@0", "", null);
      // (a) 旁边放容器：产物必须顺着原版 offload 走出去（用户报的"生产的物品不输出"）
      clearArea(100, 40, 118, 52);
      mindustry.gen.Call.constructFinish(Vars.world.tile(100, 40), cf2x2, null, (byte) 0, team, cfg3);
      run(10);
      Building out = Vars.world.tile(100, 40).build;
      check("2x2 组合工厂（3 台冶炼厂）已落地", out != null && out.block == cf2x2 && loadedCells(out) == 3);
      if (out != null) {
        place(Vars.content.block("power-source"), 99, 40, team);
        Building cont = place(Vars.content.block("container"), 102, 40, team);
        out.items.add(Items.sand, 400);
        out.items.add(Items.coal, 400);
        run(60);
        int got0 = cont == null ? 0 : cont.items.get(Items.silicon);
        run(600);
        int got = (cont == null ? 0 : cont.items.get(Items.silicon)) - got0;
        check("产物会往外输出（旁边容器收到 " + got + " 硅）", got > 0);
      }

      // (b) 没有邻居接着：池子要能攒到"各格之和"（3 台 = 30），而不是一台的 10
      clearArea(110, 40, 126, 52);
      mindustry.gen.Call.constructFinish(Vars.world.tile(110, 40), cf2x2, null, (byte) 0, team, cfg3);
      run(10);
      Building solo = Vars.world.tile(110, 40).build;
      if (solo != null) {
        place(Vars.content.block("power-source"), 109, 40, team);
        solo.items.add(Items.sand, 400);
        solo.items.add(Items.coal, 400);
        run(900);
        int si = solo.items.get(Items.silicon);
        check("产出上限 = 各格之和（池里攒到 " + si + " 硅 > 单台的 10）", si > 10);
        // 【用户报】满仓时状态必须是 noOutput，不能是 active
        check("满仓时状态 = noOutput（实际 " + solo.status() + "）",
            solo.status() == mindustry.world.meta.BlockStatus.noOutput);
      }

      // 【用户报】"合体小工厂的 draw 画错"（冷冻液混合机）：每格缩放要按**占地格数**算，
      // 不能只按贴图宽度（图集读不到时会退回 1 倍 → 2x2 的机器画成 2 格大、盖住邻居）
      {
        float kMixer = ((Number) callStatic(clsCF, "cellScale",
            new Class<?>[] { Block.class }, Vars.content.block("cryofluid-mixer"))).floatValue();
        float kPress3 = ((Number) callStatic(clsCF, "cellScale",
            new Class<?>[] { Block.class }, Vars.content.block("multi-press"))).floatValue();
        float kPress2 = ((Number) callStatic(clsCF, "cellScale",
            new Class<?>[] { Block.class }, Vars.content.block("graphite-press"))).floatValue();
        check("每格缩放按占地算（冷冻液混合机(2x2)=" + kMixer + " 三联压机(3x3)=" + kPress3
            + " 石墨压机(2x2)=" + kPress2 + "）",
            Math.abs(kMixer - 0.5f) < 1e-3 && Math.abs(kPress3 - 1f / 3f) < 1e-3
                && Math.abs(kPress2 - 0.5f) < 1e-3);
      }

      // ---------- 5) 热量：需热工厂没热不产，给热就产 ----------
      clearArea(70, 90, 84, 104);
      Building heatFac = place(cf, 70, 90, team);
      run(5);
      Block heatOriginal = (Block) callStatic(clsCF, "cellBlock", new Class<?>[] { Block.class },
          Vars.content.block("cyanogen-synthesizer"));
      check("氰气合成机在合体范围里（原始类是 HeatCrafter）",
          heatOriginal instanceof mindustry.world.blocks.production.HeatCrafter);
      if (heatFac != null && heatOriginal != null) {
        call(heatFac, "configure", new Class<?>[] { Object.class }, "cyanogen-synthesizer@0");
        run(5);
        place(Vars.content.block("power-source"), 69, 90, team); // 贴邻（本体 70..71 × 90..91）
        Seq<Item> inputs = new Seq<>();
        for (mindustry.world.consumers.Consume c : heatOriginal.consumers)
          if (c instanceof mindustry.world.consumers.ConsumeItems ci)
            for (mindustry.type.ItemStack st : ci.items)
              inputs.add(st.item);
        check("找得到氰气合成机的原料（" + inputs.size + " 种）", inputs.size > 0);
        for (Item it : inputs)
          heatFac.items.add(it, 200);
        // 它还要液体（水）——一起灌满
        Seq<mindustry.type.Liquid> liqs = new Seq<>();
        for (mindustry.world.consumers.Consume c : heatOriginal.consumers)
          if (c instanceof mindustry.world.consumers.ConsumeLiquids cl)
            for (mindustry.type.LiquidStack st : cl.liquids) {
              heatFac.liquids.add(st.liquid, 300f);
              liqs.add(st.liquid);
            }
          else if (c instanceof mindustry.world.consumers.ConsumeLiquid cl) {
            Object l = field(cl, "liquid");
            if (l instanceof mindustry.type.Liquid liq) {
              heatFac.liquids.add(liq, 300f);
              liqs.add(liq);
            }
          }
        float demand = ((Number) call(heatFac, "heatDemand", null)).floatValue();
        check("整台需热合计 > 0（" + demand + "）", demand > 0f);
        run(60);
        int noHeat0 = totalOf(heatFac, inputs);
        run(240);
        int noHeatUsed = noHeat0 - totalOf(heatFac, inputs);
        check("没热时需热工厂不消耗原料（消耗 " + noHeatUsed + "）", noHeatUsed <= 0);

        Block heatSrc = Vars.content.block("heat-source");
        if (heatSrc == null)
          check("找得到 heat-source 方块", false);
        else {
          place(heatSrc, 72, 90, team);
          run(60);
          int h0 = totalOf(heatFac, inputs);
          float cyan0 = heatFac.liquids.get(Vars.content.liquid("cyanogen"));
          // 液体池会被本台的容量上限夹住（每台就那点液体容量），跑长一点要边跑边补
          for (int i = 0; i < 10; i++) {
            run(30);
            for (mindustry.type.Liquid lq : liqs)
              heatFac.liquids.add(lq, 200f);
          }
          int used = h0 - totalOf(heatFac, inputs);
          float cyanMade = heatFac.liquids.get(Vars.content.liquid("cyanogen")) - cyan0;
          float pool = ((Number) field(heatFac, "comboTotalHeat")).floatValue();
          check("接上热源后整台热池 > 0（" + pool + "）", pool > 0f);
          check("接上热源后需热工厂开始消耗原料（消耗 " + used + "）", used > 0);
          check("产物（氰气）进的是同一个池子（+" + cyanMade + "）", cyanMade > 0f);
        }
      }

      // ---------- 6) 存读档：格子/库存/并池都要还在 ----------
      try {
        Fi dir = Core.files.absolute("/tmp/fc");
        dir.mkdirs();
        Fi file = dir.child("factory-roundtrip.msav");
        mindustry.io.SaveIO.save(file);
        int itemsSaved = merged.items.get(Items.copper) + merged.items.get(Items.lead);
        mindustry.io.SaveIO.load(file);
        run(40);
        Building after = Vars.world.tile(60, 60) == null ? null : Vars.world.tile(60, 60).build;
        check("读档后组合工厂还在", after != null && after.block == cf);
        check("读档后里面 3 台工厂还在（实际 " + loadedCells(after) + "）", loadedCells(after) == 3);
        check("读档后库存没翻倍也没丢（" + itemsSaved + " -> "
            + (after == null ? "-" : after.items.get(Items.copper) + after.items.get(Items.lead)) + "）",
            after != null && after.items.get(Items.copper) + after.items.get(Items.lead) == itemsSaved);
        merged = after;
        anchor = Vars.world.tile(60, 60);
      } catch (Throwable t) {
        System.out.println("[FC] 存读档用例出错: " + t);
        t.printStackTrace(System.out);
        check("存读档用例不抛异常", false);
      }

      // ---------- 7) 解体：工厂放回来 + 库存保留 ----------
      int itemsBefore = merged.items.get(Items.copper) + merged.items.get(Items.lead);
      call(merged, "configure", new Class<?>[] { Object.class }, "!dissolve");
      run(20);
      check("解体后本体没了", anchor.build == null || anchor.build.block != cf);
      int backCount = 0;
      int backItems = 0;
      for (int y = 50; y <= 70; y++)
        for (int x = 50; x <= 70; x++) {
          Tile t = Vars.world.tile(x, y);
          if (t == null || t.build == null || t.block() == null)
            continue;
          String n = t.block().name;
          if (("silicon-smelter".equals(n) || "graphite-press".equals(n) || "kiln".equals(n))
              && t.build.tile == t) {
            backCount++;
            backItems += t.build.items.get(Items.copper) + t.build.items.get(Items.lead);
          }
        }
      check("解体放回 3 台工厂（实际 " + backCount + "）", backCount == 3);
      check("库存没丢（解体外 " + backItems + " ≥ 解体前 " + itemsBefore + "）", backItems >= itemsBefore);

      System.out.println("[FC] 结果: PASS=" + pass + " FAIL=" + fail);
      Core.app.exit();
    } catch (Throwable t) {
      System.out.println("[FC] 崩了: " + t);
      t.printStackTrace(System.out);
      System.exit(2);
    }
  }
}
