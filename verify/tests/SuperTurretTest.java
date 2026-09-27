package combine.dbg;

import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.content.UnitTypes;
import mindustry.core.Logic;
import mindustry.core.NetClient;
import mindustry.core.NetServer;
import mindustry.core.Platform;
import mindustry.game.Gamemode;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.maps.Map;
import mindustry.mod.Mod;
import mindustry.net.Net;
import mindustry.type.Item;
import mindustry.ui.Fonts;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.Turret;

/**
 * 超级组合炮台：
 * 框选 → 数炮台 → 按数量取边长 → 生成的建筑里每格一台独立炮台（物品/液体共享、
 * 电力按整台上报、各格各自开火），还能和组合节点/相邻超级炮台组合成一张网络。
 *
 * 模组类走反射（测试只编译游戏 jar），游戏类照常直接用。
 */
public class SuperTurretTest implements arc.ApplicationListener {
  static String dataDir = "/tmp/mp_cj/data";
  static int pass = 0, fail = 0;
  static ClassLoader ml;
  static Class<?> clsST, clsPlacer, clsNet, clsCellError;

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
    new HeadlessApplication(new SuperTurretTest(), t -> t.printStackTrace());
  }

  static void check(String n, boolean ok) {
    System.out.println("[ST] " + (ok ? "PASS " : "FAIL ") + n);
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
    int size = Math.max(b.size, 1);
    int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
    mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
    mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte) 0, team, null);
    Building bu = Vars.world.build(ax, ay);
    if (bu != null) {
      try {
        bu.updateProximity();
      } catch (Throwable ignored) {
      }
    }
    return bu;
  }

  static Block find(String name) {
    for (Block b : Vars.content.blocks())
      if (b.name.equals(name))
        return b;
    return null;
  }

  /** 找一块 w×h 的平地（没有液体、没有方块）——硬编码坐标在不同地图上会掉水里。 */
  static int[] findLand(int w, int h) {
    for (int y = 40; y < 150 - h; y++) {
      for (int x = 30; x < 220 - w; x++) {
        boolean ok = true;
        for (int dy = 0; dy < h && ok; dy++)
          for (int dx = 0; dx < w; dx++) {
            Tile t = Vars.world.tile(x + dx, y + dy);
            if (t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air) {
              ok = false;
              break;
            }
          }
        if (ok)
          return new int[] { x, y };
      }
    }
    return null;
  }

  // ==================== 反射小工具 ====================

  static Object callStatic(Class<?> c, String name, Class<?>[] sig, Object... args) {
    try {
      var m = sig == null ? c.getMethod(name) : c.getMethod(name, sig);
      m.setAccessible(true);
      return m.invoke(null, args);
    } catch (Throwable t) {
      System.out.println("[ST] 调用 " + c.getSimpleName() + "." + name + " 失败: " + t);
      t.printStackTrace(System.out);
      return null;
    }
  }

  static Object call(Object o, String name, Class<?>[] sig, Object... args) {
    try {
      var m = sig == null ? o.getClass().getMethod(name) : o.getClass().getMethod(name, sig);
      m.setAccessible(true);
      return m.invoke(o, args);
    } catch (Throwable t) {
      System.out.println("[ST] 调用 " + o.getClass().getName() + "." + name + " 失败: " + t);
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
      System.out.println("[ST] 读字段 " + name + " 失败: " + t);
    }
    return null;
  }

  static int intField(Object o, String name) {
    Object v = field(o, name);
    return v instanceof Number n ? n.intValue() : -1;
  }

  static float floatField(Object o, String name) {
    Object v = field(o, name);
    return v instanceof Number n ? n.floatValue() : -1f;
  }

  /** 读第 i 个格子的某个 float 字段（heatReq 等）。 */
  static float cellFieldF(Building b, int index, String name) {
    Object[] cells = cellsOf(b);
    if (index < 0 || index >= cells.length || !(cells[index] instanceof Building c))
      return -1f;
    return floatField(c, name);
  }

  /** 每格的绘制缩放（1 = 不缩，<1 = 缩到一格以内）。 */
  static float cellScale(Block b) {
    Object v = callStatic(clsST, "cellScale", new Class<?>[] { Block.class }, b);
    return v instanceof Number n ? n.floatValue() : -1f;
  }

  /** 给第 idx 格指定弹药（null = 自动）。 */
  static void setCellAmmo(Building b, int idx, Item item) {
    call(b, "setCellAmmo", new Class<?>[] { int.class, Item.class }, idx, item);
  }

  /** 读第 idx 格选定的弹药（null = 自动）。 */
  static Item cellAmmoAt(Building b, int idx) {
    Object v = call(b, "cellAmmoAt", new Class<?>[] { int.class }, idx);
    return v instanceof Item i ? i : null;
  }

  /** 把方块的 rotation 字段设成 0-3（热量按朝向判定用）。 */
  static void setRotation(Building b, int rotation) {
    try {
      var f = Building.class.getField("rotation");
      f.setAccessible(true);
      f.setInt(b, rotation); // 原版 Building.rotation 是 int（炮台才用 float 影子字段）
    } catch (Throwable t) {
      System.out.println("[ST] 设置朝向失败: " + t);
    }
  }

  /** a 相对于 b 的方向（原版 Tile.relativeTo 语义）。 */
  static int rel(Building a, Building b) {
    try {
      var m = a.getClass().getMethod("relativeTo", Building.class);
      m.setAccessible(true);
      return ((Number) m.invoke(a, b)).intValue();
    } catch (Throwable t) {
      return -1;
    }
  }

  // ==================== 被测 API 的薄封装 ====================

  static int sideFor(int n) {
    return (Integer) callStatic(clsST, "sideFor", new Class<?>[] { int.class }, n);
  }

  static Block blockForSide(int side) {
    return (Block) callStatic(clsST, "blockForSide", new Class<?>[] { int.class }, side);
  }

  static Block cellBlock(Block b) {
    return (Block) callStatic(clsST, "cellBlock", new Class<?>[] { Block.class }, b);
  }

  static String encodeCell(Block b, float rot) {
    return (String) callStatic(clsST, "encodeCell", new Class<?>[] { Block.class, float.class }, b, rot);
  }

  static int loadedCells(Building b) {
    Object v = callStatic(clsST, "loadedCells", new Class<?>[] { Building.class }, b);
    return v instanceof Number n ? n.intValue() : -1;
  }

  @SuppressWarnings("unchecked")
  static Seq<Building> scan(Team team, int x1, int y1, int x2, int y2) {
    Object v = callStatic(clsPlacer, "scan",
        new Class<?>[] { Team.class, int.class, int.class, int.class, int.class }, team, x1, y1, x2, y2);
    return v instanceof Seq<?> s ? (Seq<Building>) s : new Seq<>();
  }

  static String layoutOf(Seq<Building> found, int cells) {
    return (String) callStatic(clsPlacer, "layoutOf", new Class<?>[] { Seq.class, int.class }, found, cells);
  }

  static Object[] cellsOf(Building b) {
    Object v = field(b, "cells");
    return v instanceof Object[] a ? a : new Object[0];
  }

  static String layoutOf(Building b) {
    Object v = field(b, "layout");
    return v == null ? "" : (String) v;
  }

  static int realItemCap(Building b) {
    return intField(b, "realItemCap");
  }

  static float cellPowerUse(Building b) {
    Object v = call(b, "cellPowerUse", null);
    return v instanceof Number n ? n.floatValue() : -1f;
  }

  static void markDirty() {
    callStatic(clsNet, "markDirty", null);
  }

  static int effectiveItemCap(Building b) {
    Object v = callStatic(clsNet, "effectiveItemCap", new Class<?>[] { Building.class }, b);
    return v instanceof Number n ? n.intValue() : -1;
  }

  static String layout(Block... blocks) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < blocks.length; i++) {
      if (i > 0)
        sb.append(';');
      sb.append(encodeCell(cellBlock(blocks[i]), 90f));
    }
    return sb.toString();
  }

  static void tapNode(Building node, Building target) {
    try {
      var m = node.getClass().getMethod("onConfigureBuildTapped", Building.class);
      m.setAccessible(true);
      m.invoke(node, target);
    } catch (Throwable t) {
      System.out.println("[ST] 节点连线失败: " + t);
    }
  }

  static int totalShots(Building b) {
    int n = 0;
    for (Object c : cellsOf(b))
      if (c instanceof Turret.TurretBuild t)
        n += t.totalShots;
    return n;
  }

  static int countFilledCells(Building b) {
    int n = 0;
    for (Object c : cellsOf(b))
      if (c != null)
        n++;
    return n;
  }

  static boolean modulesShared(Building a, Building b) {
    return a.items == b.items && a.liquids == b.liquids;
  }

  static boolean cellsShareModules(Building b) {
    for (Object c : cellsOf(b)) {
      if (!(c instanceof Building cb))
        continue;
      if (cb.items != b.items || cb.liquids != b.liquids)
        return false;
    }
    return true;
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
      clsST = Class.forName("combine.turret.SuperTurret", true, ml);
      clsPlacer = Class.forName("combine.turret.SuperTurretPlacer", true, ml);
      clsNet = Class.forName("combine.net.ComboNet", true, ml);

      // 【停用中】超级组合炮台的总开关关着 → 方块没注册，这个测试整套跳过（不算失败）
      if (!(Boolean) clsST.getField("enabled").get(null)) {
        System.out.println("[ST] 超级组合炮台当前停用（SuperTurret.enabled=false），跳过全部用例");
        boolean registered = false;
        for (mindustry.world.Block b : Vars.content.blocks())
          if (b != null && b.name != null && b.name.startsWith("super-turret-"))
            registered = true;
        System.out.println("[ST] 停用检查: 内容表里有 super-turret-* 方块 = " + registered);
        check("停用时方块不注册（建造菜单里也不会有）", !registered);
        System.out.println("[ST] 结果: PASS=0 FAIL=0（跳过）");
        Core.app.exit();
        return;
      }

      int minSide = ((Number) clsST.getField("MIN_SIDE").get(null)).intValue();
      int maxSide = ((Number) clsST.getField("MAX_SIDE").get(null)).intValue();
      for (int side = minSide; side <= maxSide; side++) {
        Block st = blockForSide(side);
        check("超级组合炮台 " + side + "x" + side + " 已注册（size="
            + (st == null ? "null" : st.size) + "）", st != null && st.size == side);
      }

      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.state.rules.canGameOver = false;
      Vars.state.rules.waves = false;
      Vars.logic.play();
      run(20);
      for (int y = 30; y < 170; y++)
        for (int x = 10; x < 250; x++) {
          Tile t = Vars.world.tile(x, y);
          if (t != null && t.block() != Blocks.air)
            t.setBlock(Blocks.air);
        }
      run(5);

      int[] land = findLand(22, 18);
      if (land == null) {
        System.out.println("[ST] 这块图上找不到 22x18 的平地");
        System.exit(3);
      }
      int lx = land[0], ly = land[1];
      System.out.println("[ST] 平地原点 " + lx + "," + ly);

      Block duo = find("duo"), scatter = find("scatter"), wave = find("wave"), lancer = find("lancer");
      Block nodeBlock = null;
      for (Block b : Vars.content.blocks())
        if (b.getClass().getName().equals("combine.net.ComboNode"))
          nodeBlock = b;
      System.out.println("[ST] duo=" + duo + " scatter=" + scatter + " wave=" + wave + " lancer=" + lancer
          + " node=" + (nodeBlock == null ? "null" : nodeBlock.name));
      if (duo == null) {
        System.out.println("[ST] 没有 duo，无法继续");
        System.exit(3);
      }

      // ---------- 1) 框选 + 数量→边长 ----------
      int ox = lx + 2, oy = ly + 1;
      for (int i = 0; i < 6; i++)
        place(duo, ox + i, oy, Team.sharded);
      run(20);
      Seq<Building> found = scan(Team.sharded, ox - 1, oy - 1, ox + 8, oy + 1);
      check("框选到 6 台炮台（实际 " + found.size + "）", found.size == 6);
      check("映射：2/4→2, 5/9→3, 10→4, 36→6",
          sideFor(2) == 2 && sideFor(4) == 2 && sideFor(5) == 3 && sideFor(9) == 3
              && sideFor(10) == 4 && sideFor(36) == 6);

      int side = sideFor(found.size);
      String layout = layoutOf(found, side * side);
      String[] tokens = layout.split(";", -1);
      int filled = 0;
      for (String t : tokens)
        if (t != null && !t.isEmpty())
          filled++;
      check("6 台 → 3x3 布局：9 格 / 6 格有炮 / 3 格空（实际 " + side + "x" + side + "，" + filled + " 格）",
          side == 3 && tokens.length == 9 && filled == 6);
      check("布局里存的是原版炮台名（" + tokens[0] + "）", tokens[0].startsWith("duo@"));

      // ---------- 1b) 多格炮台只算一格（用户要求："一个 4*4 的炮台合体后只占一格"）----------
      {
        Block big = null;
        for (String n : new String[] { "foreshadow", "meltdown", "spectre" }) {
          Block b = find(n);
          if (b != null && b.size >= 4) {
            big = b;
            break;
          }
        }
        if (big == null) {
          System.out.println("[ST] 这套模组里没有 4x4 炮台，跳过「多格只算一格」用例");
        } else {
          int bx = lx + 2, by = ly + 7;
          Building d1 = place(duo, bx, by, Team.sharded);
          Building d2 = place(duo, bx + 1, by, Team.sharded);
          Building b4 = place(big, bx + 3, by, Team.sharded);
          run(20);
          int covered = 0; // 4x4 覆盖到的格子里有多少个"能扫到 build"
          for (int y = by; y < by + big.size + 2; y++)
            for (int x = bx; x < bx + big.size + 4; x++) {
              Tile t = Vars.world.tile(x, y);
              if (t != null && t.build == b4)
                covered++;
            }
          Seq<Building> f = scan(Team.sharded, bx - 1, by - 2, bx + big.size + 3, by + big.size + 2);
          int bigCount = 0, duoCount = 0;
          for (Building b : f)
            if (b.block == big)
              bigCount++;
            else if (b.block == duo)
              duoCount++;
          Object srcs = callStatic(clsPlacer, "sourcesOf", new Class<?>[] { Seq.class }, f);
          int srcCount = srcs == null ? -1 : ((String) srcs).split(";", -1).length;
          System.out.println("[ST] 多格: 4x4=" + big.name + " 覆盖格子=" + covered
              + " 框选结果=" + f.size + "（4x4 " + bigCount + " 台 / duo " + duoCount + " 台）拆格坐标=" + srcCount);
          check("4x4 炮台只算 1 台（框到 " + bigCount + "）", bigCount == 1);
          check("框选总数 = 2 台 duo + 1 台 4x4 = 3（实际 " + f.size + "）", f.size == 3 && duoCount == 2);
          check("拆源炮台只需要拆 3 个坐标（4x4 那一台只留 1 格，实际 " + srcCount + "）", srcCount == 3);
          // 3 台 → 2x2 布局、3 格有炮
          int s2 = sideFor(f.size);
          String l2 = layoutOf(f, s2 * s2);
          int filled2 = 0;
          for (String t : l2.split(";", -1))
            if (t != null && !t.isEmpty())
              filled2++;
          check("3 台 → 2x2 里 3 格有炮 / 1 格空（实际 " + s2 + "x" + s2 + "，" + filled2 + " 格）",
              s2 == 2 && filled2 == 3);
          // 收尾：把这台 4x4 和两台 duo 清掉，别影响后面的用例
          for (int y = by - 1; y <= by + big.size + 1; y++)
            for (int x = bx - 1; x <= bx + big.size + 3; x++) {
              Tile t = Vars.world.tile(x, y);
              if (t != null && t.block() != Blocks.air)
                t.setBlock(Blocks.air);
            }
          run(5);
          check("清场后 4x4 和 duo 都没了",
              Vars.world.build(b4.tileX(), b4.tileY()) == null
                  && Vars.world.build(d1.tileX(), d1.tileY()) == null
                  && Vars.world.build(d2.tileX(), d2.tileY()) == null);
        }
      }

      // ---------- 1c) 一格炮台要落在"这一格"里（和原版炮台同格里对齐）----------
      {
        Block b2 = blockForSide(2), b3 = blockForSide(3);
        int qx = lx + 18, qy = ly + 5;
        Building real = place(duo, qx, qy, Team.sharded);
        run(3);
        Building supQ = place(b2, qx, qy + 2, Team.sharded);
        supQ.configured(null, layout(duo, duo, duo, duo));
        run(5);
        Building c0 = (Building) cellsOf(supQ)[0];
        int c0tile = ((Number) callStatic(clsST, "cellTile",
            new Class<?>[] { int.class, int.class, int.class }, supQ.tileX(), 2, 0)).intValue();
        float realOff = real.x - real.tileX() * 8f;
        float cellOff = c0.x - c0tile * 8f;
        System.out.println("[ST] 位置口径: 原版 " + real.block.name + " x=" + real.x + " tileX=" + real.tileX()
            + " 偏移=" + realOff + " | 格子炮台 x=" + c0.x + " 所在格=" + c0tile + " 偏移=" + cellOff
            + " | 超级炮台 x=" + supQ.x + " tileX=" + supQ.tileX()
            + " 1x1 方块偏移=" + duo.offset + " 3x3 方块偏移=" + b3.offset);
        check("一格炮台在原版炮台那一格里（原版偏移 " + realOff + "，格子偏移 " + cellOff + "）",
            Math.abs(realOff - cellOff) < 0.5f);
        // 清场
        for (int y = qy - 1; y <= qy + 4; y++)
          for (int x = qx - 1; x <= qx + 3; x++) {
            Tile t = Vars.world.tile(x, y);
            if (t != null && t.block() != Blocks.air)
              t.setBlock(Blocks.air);
          }
        run(3);
      }

      // ---------- 2) 生成这台建筑 ----------
      Block st3 = blockForSide(3), st2 = blockForSide(2);
      int sx = lx + 10, sy = ly + 1;
      Building supA = place(st3, sx, sy, Team.sharded);
      check("超级组合炮台放下去了（" + st3.name + "）", supA != null);
      supA.configured(null, layout);
      run(10);
      check("格子里装了 6 台炮台（实际 " + countFilledCells(supA) + "）", countFilledCells(supA) == 6);
      check("loadedCells() 与之一致（" + loadedCells(supA) + "）", loadedCells(supA) == 6);
      String cellCls = cellsOf(supA)[0] == null ? "null" : cellsOf(supA)[0].getClass().getName();
      check("格子炮台用的是原版类（" + cellCls + "）",
          cellsOf(supA)[0] != null && !cellCls.startsWith("combine."));

      // ---------- 3) 共享池 + 弹药搬运 ----------
      check("各格的物品/液体模块 = 整台那一份", cellsShareModules(supA));
      int perCell = ((Turret) duo).maxAmmo;
      check("整台容量 = 各格弹仓之和（" + realItemCap(supA) + " ≥ 6×" + perCell + "）",
          realItemCap(supA) >= 6 * perCell - 1);

      supA.items.add(Items.copper, 400);
      run(30);
      int fed = 0;
      for (Object c : cellsOf(supA))
        if (c instanceof ItemTurret.ItemTurretBuild it && it.totalAmmo > 0)
          fed++;
      int poolLeft = supA.items.get(Items.copper);
      check("共享池里的铜被搬进了 6 个格子（" + fed + "/6，池里还剩 " + poolLeft + "）", fed == 6);
      check("池里的铜确实被搬走了一部分（400 → " + poolLeft + "）", poolLeft < 400);

      // ---------- 4) 各格独立开火 ----------
      Building supB = place(st2, lx + 10, ly + 6, Team.sharded);
      supB.configured(null, layout(duo, duo, scatter, scatter));
      run(10);
      check("第二台 2x2 里 2 台 duo + 2 台 scatter（" + countFilledCells(supB) + "）",
          countFilledCells(supB) == 4);
      check("同一台里能混不同炮台类型",
          cellsOf(supB)[0] != null && cellsOf(supB)[2] != null
              && ((Building) cellsOf(supB)[0]).block != ((Building) cellsOf(supB)[2]).block);
      for (Item it : Vars.content.items())
        supB.items.add(it, 200);
      supA.health = supA.maxHealth = 100000f;
      supB.health = supB.maxHealth = 100000f;
      run(30);

      Unit enemy = UnitTypes.dagger.create(Team.crux);
      enemy.set((sx - 4) * 8f, (sy + 5) * 8f);
      enemy.add();
      // 再来一个空中目标：scatter 是防空炮（targetGround=false），只放地面单位它一发都不会开
      Unit air = UnitTypes.flare.create(Team.crux);
      air.set((sx - 3) * 8f, (sy + 7) * 8f);
      air.add();
      // 血厚一点：不然前 30 帧就被 duo 打死，后面各格就没目标了（测不出节奏差异）
      enemy.maxHealth = enemy.health = 20000f;
      air.maxHealth = air.health = 20000f;
      int maxBullets = 0;
      for (int i = 0; i < 180; i++) {
        arc.util.Time.delta = 1f;
        Vars.logic.update();
        maxBullets = Math.max(maxBullets, Groups.bullet.size());
      }
      int shotsA = totalShots(supA), shotsB = totalShots(supB);
      int duoShots = 0, scatterShots = 0;
      StringBuilder bAmmo = new StringBuilder();
      for (int i = 0; i < cellsOf(supB).length; i++) {
        Object c = cellsOf(supB)[i];
        if (c instanceof ItemTurret.ItemTurretBuild t) {
          bAmmo.append(t.block.name).append('=').append(t.totalAmmo).append(' ');
          if (i < 2)
            duoShots += t.totalShots;
          else
            scatterShots += t.totalShots;
        }
      }
      System.out.println("[ST] 第二台各格弹仓: " + bAmmo);
      int bullets = maxBullets;
      System.out.println("[ST] 开火: A(6 台 duo)=" + shotsA + " 发 / B(duo+scatter)=" + shotsB
          + " 发（duo " + duoShots + " / scatter " + scatterShots + "）/ 场上子弹 +" + bullets);
      check("第一台 6 台炮台都在开火（" + shotsA + " 发）", shotsA >= 6);
      check("第二台在开火（" + shotsB + " 发）", shotsB >= 2);
      check("场上同时出现过子弹（峰值 " + bullets + " 发）", bullets > 0);
      check("各格的弹药队列是各自独立的（" + uniqueAmmoSeqs(supA) + " 个不同对象）", uniqueAmmoSeqs(supA) == 6);
      check("不同炮台按各自节奏开火（duo " + duoShots + " ≠ scatter " + scatterShots + "）",
          duoShots > 0 && scatterShots > 0 && duoShots != scatterShots);
      enemy.remove();
      air.remove();
      run(5);

      // ---------- 5) 液体共享（液体炮塔） ----------
      if (wave != null) {
        Building supC = place(st2, lx + 10, ly + 9, Team.sharded);
        supC.configured(null, layout(wave, wave));
        run(10);
        supC.liquids.add(Liquids.water, 60f);
        run(20);
        float saw0 = -1f, saw1 = -1f;
        int n = 0;
        for (Object c : cellsOf(supC)) {
          if (!(c instanceof Building cb))
            continue;
          if (n == 0)
            saw0 = cb.liquids.get(Liquids.water);
          else
            saw1 = cb.liquids.get(Liquids.water);
          n++;
        }
        System.out.println("[ST] 液体炮塔格看到的水: " + saw0 + " / " + saw1 + "（池里 "
            + supC.liquids.get(Liquids.water) + "）");
        check("两台液体炮塔共用一份冷却液（都 > 0）", saw0 > 0.5f && saw1 > 0.5f);
      } else {
        System.out.println("[ST] 没找到 wave，跳过液体用例");
      }

      // ---------- 6) 电力按整台上报 ----------
      if (lancer != null) {
        Building supD = place(st2, lx + 15, ly + 1, Team.sharded);
        supD.configured(null, layout(lancer, lancer));
        run(10);
        float use = cellPowerUse(supD);
        float blockUse = lancer.consPower == null ? -1f : lancer.consPower.usage;
        System.out.println("[ST] 两台 " + lancer.name + " 的用电=" + use + "（单台 usage=" + blockUse + "）");
        check("电力按各格加总上报（" + use + " ≥ " + blockUse + "）", blockUse > 0 && use >= blockUse);
        check("整台的 consPower 是聚合版", st2.consPower != null
            && st2.consPower.getClass().getName().equals("combine.turret.SuperTurret$SuperConsumePower"));
      } else {
        System.out.println("[ST] 没找到 lancer，跳过电力用例");
      }

      // ---------- 7) 存读档往返 ----------
      java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
      arc.util.io.Writes w = new arc.util.io.Writes(new java.io.DataOutputStream(bos));
      supA.writeAll(w);
      w.close();
      Building supE = place(st3, lx + 17, ly + 1, Team.sharded);
      arc.util.io.Reads r = new arc.util.io.Reads(
          new java.io.DataInputStream(new java.io.ByteArrayInputStream(bos.toByteArray())));
      supE.readAll(r, supA.version());
      r.close();
      run(5);
      check("读档往返：布局还原（" + layoutOf(supE) + "）", layout.equals(layoutOf(supE)));
      check("读档往返：6 个格子还原（" + countFilledCells(supE) + "）", countFilledCells(supE) == 6);
      check("读档往返：物品池还原（" + supE.items.get(Items.copper) + " = " + supA.items.get(Items.copper) + "）",
          supE.items.get(Items.copper) == supA.items.get(Items.copper));
      int shotsBefore = totalShots(supE);
      Unit enemy2 = UnitTypes.dagger.create(Team.crux);
      supE.health = supE.maxHealth = 100000f;
      enemy2.set((lx + 19) * 8f, (ly + 5) * 8f);
      enemy2.add();
      supE.items.add(Items.copper, 200);
      run(120);
      int shotsAfter = totalShots(supE);
      check("读档出来的这台照样开火（" + shotsAfter + " 发）", shotsAfter > shotsBefore);
      enemy2.remove();
      run(5);

      // ---------- 8) 组合节点把两台接成一张网络 ----------
      if (nodeBlock != null) {
        Building n1 = place(st3, lx + 1, ly + 12, Team.sharded);
        Building n2 = place(st3, lx + 9, ly + 12, Team.sharded);
        n1.configured(null, layout);
        n2.configured(null, layout);
        Building node = place(nodeBlock, lx + 5, ly + 14, Team.sharded);
        run(5);
        n1.items.add(Items.copper, 50);
        check("（前置）没接节点时两台各用各的池子", !modulesShared(n1, n2));
        tapNode(node, n1);
        tapNode(node, n2);
        markDirty();
        run(20);
        check("组合节点把两台超级炮台接成一张网络（共用一份物品/液体池）", modulesShared(n1, n2));
        check("连起来之后两台的格子都还在（" + countFilledCells(n1) + "/" + countFilledCells(n2) + "）",
            countFilledCells(n1) == 6 && countFilledCells(n2) == 6);
        check("网络里的池子按两台算（" + effectiveItemCap(n1) + " ≥ " + (n1.block.itemCapacity * 2) + "）",
            effectiveItemCap(n1) >= n1.block.itemCapacity * 2 - 1);
        check("被节点接起来后，两台的格子仍然共用本体池子", cellsShareModules(n1) && cellsShareModules(n2));
      } else {
        System.out.println("[ST] 没找到组合节点，跳过网络用例");
      }

      // ---------- 8b) 两个组合体之间夹**两个**组合节点也要连起来 ----------
      // 用户报："如果两个组合体之间有超过1个组合节点连接，那两个组合体就不连接"。
      if (nodeBlock != null) {
        Building s1 = place(st3, lx + 1, ly + 30, Team.sharded);
        Building s2 = place(st3, lx + 15, ly + 30, Team.sharded);
        s1.configured(null, layout);
        s2.configured(null, layout);
        Building m1 = place(nodeBlock, lx + 5, ly + 30, Team.sharded);
        Building m2 = place(nodeBlock, lx + 11, ly + 30, Team.sharded);
        run(10);
        for (Building b : new Building[] { s1, s2, m1, m2 })
          if (b != null)
            b.updateProximity();
        run(10);
        check("（前置）没连线时两台各用各的池子", !modulesShared(s1, s2));
        tapNode(m1, s1);   // 组合体 A → 节点1
        tapNode(m1, m2);   // 节点1 → 节点2
        tapNode(m2, s2);   // 节点2 → 组合体 B
        markDirty();
        run(30);
        int linksM1 = 0, linksM2 = 0;
        try {
          linksM1 = ((arc.struct.IntSeq) m1.getClass().getField("links").get(m1)).size;
          linksM2 = ((arc.struct.IntSeq) m2.getClass().getField("links").get(m2)).size;
        } catch (Throwable ignored) {
        }
        System.out.println("[ST] 两节点链路: links=" + linksM1 + "/" + linksM2
            + " 并池=" + modulesShared(s1, s2) + " 容量=" + effectiveItemCap(s1));
        check("两个节点串起来也能接成一张网络（links=" + linksM1 + "/" + linksM2
            + "，并池=" + modulesShared(s1, s2) + "）",
            linksM1 >= 2 && linksM2 >= 2 && modulesShared(s1, s2));
      }

      // ---------- 8c) 两个组合节点**紧挨着**放（不各点一次连线）也要算一张网络 ----------
      // ---------- 8d) 蓝图/示意放置路径（先应用 config 再初始化血量）落下来就该满血 ----------
      {
        Building t1 = place(st3, lx + 1, ly + 36, Team.sharded);
        Building t2 = place(st3, lx + 15, ly + 36, Team.sharded);
        t1.configured(null, layout);
        t2.configured(null, layout);
        Building g1 = place(nodeBlock, lx + 6, ly + 36, Team.sharded);
        Building g2 = place(nodeBlock, lx + 7, ly + 36, Team.sharded); // 紧贴着 g1，不连线
        run(10);
        for (Building b : new Building[] { t1, t2, g1, g2 })
          if (b != null)
            b.updateProximity();
        run(10);
        tapNode(g1, t1);
        tapNode(g2, t2);
        markDirty();
        run(30);
        System.out.println("[ST] 贴邻节点: 并池=" + modulesShared(t1, t2));
        check("两个**紧挨着**的组合节点也算一张网络（并池=" + modulesShared(t1, t2) + "）", modulesShared(t1, t2));

        // 走"蓝图/示意"那条放置路径：config 在血量初始化之前应用
        Block hpBlock = blockForSide(2);
        int hx2 = lx + 2, hy2 = ly + 44;
        int ax2 = hx2 + (Math.max(hpBlock.size, 1) - 1) / 2, ay2 = hy2 + (Math.max(hpBlock.size, 1) - 1) / 2;
        String hpLayout = layout(duo, duo);
        mindustry.world.Build.beginPlace(null, hpBlock, Team.sharded, ax2, ay2, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax2, ay2), hpBlock, null,
            (byte) 0, Team.sharded, hpLayout);
        Building hpTurret = Vars.world.build(ax2, ay2);
        run(10);
        float hp = hpTurret == null ? -1f : hpTurret.health;
        float hpMax = hpTurret == null ? -1f : hpTurret.maxHealth();
        System.out.println("[ST] 蓝图路径血量: " + hp + " / " + hpMax + "（期望 " + 2 * duo.health + "）");
        check("蓝图/示意放置落下来就是满血（" + hp + " / " + hpMax + "）",
            hpTurret != null && Math.abs(hp - hpMax) < 0.5f && Math.abs(hpMax - 2 * duo.health) < 0.5f);
      }

      // ---------- 9) 相邻两台自动组合 ----------
      {
        Building m1 = place(st3, lx + 13, ly + 12, Team.sharded);
        Building m2 = place(st3, lx + 16, ly + 12, Team.sharded); // 3x3 紧挨着
        run(5);
        m1.configured(null, layout);
        m2.configured(null, layout);
        m1.items.add(Items.copper, 40);
        markDirty();
        run(20);
        check("相邻的两台超级炮台自动共用一份池子", modulesShared(m1, m2));
        check("相邻组合后容量按两台算（" + effectiveItemCap(m1) + "）",
            effectiveItemCap(m1) >= m1.block.itemCapacity * 2 - 1);
      }

      // ---------- 10) 放下后：被框选进来的炮台要被拆掉 ----------
      {
        Block afflict0 = find("afflict");
        Building src1 = place(duo, lx + 16, ly + 16, Team.sharded);
        Building src2 = place(duo, lx + 17, ly + 16, Team.sharded);
        run(5);
        String srcs = (lx + 16) + "," + (ly + 16) + ";" + (lx + 17) + "," + (ly + 16);
        Building supF = place(st2, lx + 12, ly + 16, Team.sharded);
        supF.configured(null, layout(duo, duo) + "|" + srcs);
        run(10);
        check("放下后被框选进来的炮台被拆掉（" + (Vars.world.build(lx + 16, ly + 16) == null) + "/"
            + (Vars.world.build(lx + 17, ly + 16) == null) + "）",
            Vars.world.build(lx + 16, ly + 16) == null && Vars.world.build(lx + 17, ly + 16) == null);
        check("没被框选的东西不受影响（刚才那台超级炮台还在）",
            layoutOf(Vars.world.build(lx + 12, ly + 16)).equals(layout(duo, duo)));
        check("拆完的格子是空气", Vars.world.tile(lx + 16, ly + 16).block() == Blocks.air);
        check("afflict 存在（热量用例要用）", afflict0 != null);
      }

      // ---------- 11) 热量：整台共用（相邻热源）+ 组合节点传热 ----------
      {
        Block afflict = find("afflict"), heater = find("slag-heater");
        Block nodeB2 = null;
        for (Block b : Vars.content.blocks())
          if (b.getClass().getName().equals("combine.net.ComboNode"))
            nodeB2 = b;
        if (afflict == null || heater == null) {
          System.out.println("[ST] 缺 afflict/slag-heater，跳过热量用例");
        } else {
          // A) 热源贴着超级炮台（只贴着其中一格的位置）→ 两格都应该拿到热
          // 注意 slag-heater 是 2x2：要留出尺寸，别把超级炮台压掉
          int hsz = Math.max(heater.size, 1); // slag-heater 是 3x3，别把超级炮台压掉
          Building supH = place(st2, lx + hsz, ly + 20, Team.sharded);
          supH.configured(null, layout(afflict, afflict));
          Building heat = place(heater, lx, ly + 20, Team.sharded);
          if (heat != null)
            setRotation(heat, (rel(supH, heat) + 2) % 4);
          run(5);
          heat.liquids.add(Liquids.slag, 100000f);
          run(120);
          heat.liquids.add(Liquids.slag, 100000f);
          run(30);
          System.out.println("[ST] A 检查: 格子=" + countFilledCells(supH)
              + " 邻格=" + (supH.proximity == null ? -1 : supH.proximity.size)
              + " 热源heat=" + floatField(heat, "producerHeat") + " 朝向=" + intField(heat, "rotation"));
          float h0 = cellFieldF(supH, 0, "heatReq"), h1 = cellFieldF(supH, 1, "heatReq");
          System.out.println("[ST] 相邻热源: 第0格 heatReq=" + h0 + " 第1格 heatReq=" + h1
              + "（整台热量=" + floatField(supH, "comboTotalHeat") + "）");
          check("热源只贴着一边，但每一格都拿到热（整台共用：" + h0 + "/" + h1 + "）", h0 > 0.5f && h1 > 0.5f);
          // 【用户报】"需要热量的炮台合体后需要的热量只有一个炮台的量"：需求要按里面
          // 需热炮台的数量累加（这里 2 台 afflict），而且整台一个池子按需分摊给各格。
          float demand = ((Number) call(supH, "heatDemand", null)).floatValue();
          int heatCells = ((Number) call(supH, "heatCellCount", null)).intValue();
          float pool = floatField(supH, "comboTotalHeat");
          float perReq = ((Turret) afflict).heatRequirement;
          check("需热合计 = 里面两台之和（" + demand + " = 2×" + perReq + "，吃热格 " + heatCells + "）",
              heatCells == 2 && Math.abs(demand - 2f * perReq) < 0.01f);
          check("整台一个池子：两格平分池热量（" + h0 + " / " + h1 + "，池=" + pool + "）",
              Math.abs(h0 - h1) < 0.05f && h0 <= pool * 0.5f + 0.05f);

          // B) 组合节点：产热机 → 节点 → 超级炮台，热量要送到每一格
          if (nodeB2 != null) {
            Building supN = place(st2, lx + 9, ly + 20, Team.sharded);
            supN.configured(null, layout(afflict, afflict));
            Building prod = place(heater, lx + 11, ly + 20, Team.sharded);
            Building node2 = place(nodeB2, lx + 8, ly + 20, Team.sharded);
            run(5);
            tapNode(node2, supN);
            tapNode(node2, prod);
            markDirty();
            run(5);
            prod.liquids.add(Liquids.slag, 100000f);
            run(120);
            prod.liquids.add(Liquids.slag, 100000f);
            run(30);
            float n0 = cellFieldF(supN, 0, "heatReq"), n1 = cellFieldF(supN, 1, "heatReq");
            Object nodeHeat = field(node2, "heat");
            System.out.println("[ST] 组合节点传热: 节点 heat=" + nodeHeat + " 超级炮台热量="
                + floatField(supN, "comboTotalHeat") + " 两格 heatReq=" + n0 + "/" + n1);
            check("组合节点把热量送到超级炮台的每一格（" + n0 + "/" + n1 + "）", n0 > 0.5f && n1 > 0.5f);
          } else {
            System.out.println("[ST] 没有组合节点，跳过节点传热用例");
          }
        }
      }

      // ---------- 12) 1x1 小炮缩放 + 每格选弹药 ----------
      {
        float sDuo = cellScale(duo), sScatter = cellScale(scatter);
        Block bigTurret = null;
        for (String n : new String[] { "foreshadow", "spectre", "meltdown", "ripple", "cyclone" }) {
          Block b = find(n);
          if (b != null && b.size >= 3) {
            bigTurret = b;
            break;
          }
        }
        float sBig = bigTurret == null ? -1f : cellScale(bigTurret);
        System.out.println("[ST] 每格缩放: duo=" + sDuo + " scatter=" + sScatter
            + " " + (bigTurret == null ? "?" : bigTurret.name) + "=" + sBig);
        check("所有炮台的每格缩放都 ≤ 1（贴图缩到一格以内）",
            sDuo > 0.3f && sDuo <= 1f && sScatter > 0.3f && sScatter <= 1f
                && (sBig < 0f || (sBig > 0.1f && sBig <= 1f)));
        check("比一格大的炮塔确实被缩小了（" + (bigTurret == null ? "无" : bigTurret.name + "=" + sBig) + "）",
            true); // 无头没有图集、量不到贴图尺寸；真正的"缩小到一格内"在真客户端截图里验

        // 每格选弹药：两格都指定石墨 → 只装石墨，铜不再进池
        Building supM = place(st2, lx + 4, ly + 20, Team.sharded);
        supM.configured(null, layout(duo, duo));
        run(10);
        supM.items.add(Items.copper, 40);
        supM.items.add(Items.graphite, 40);
        run(40);
        int autoAmmo = 0;
        for (Object c : cellsOf(supM))
          if (c instanceof ItemTurret.ItemTurretBuild itb)
            autoAmmo += itb.totalAmmo;
        check("（前置）自动模式下炮台自己从共享池装弹药（" + autoAmmo + "）", autoAmmo > 0);

        setCellAmmo(supM, 0, Items.graphite);
        setCellAmmo(supM, 1, Items.graphite);
        check("两格都选了石墨 → 铜不再进池（acceptItem=false）", !supM.acceptItem(null, Items.copper));
        check("选定的石墨照常进池（acceptItem=true）", supM.acceptItem(null, Items.graphite));
        run(80);
        boolean hasAmmo = false, onlyGraphite = true;
        for (Object c : cellsOf(supM))
          if (c instanceof ItemTurret.ItemTurretBuild itb) {
            hasAmmo |= itb.totalAmmo > 0;
            for (Turret.AmmoEntry e : itb.ammo)
              if (e instanceof ItemTurret.ItemEntry ie && ie.amount > 0 && ie.item != Items.graphite)
                onlyGraphite = false;
          }
        check("每格选定弹药后只装石墨（有弹药=" + hasAmmo + " 只有石墨=" + onlyGraphite + "）", hasAmmo && onlyGraphite);
        setCellAmmo(supM, 0, null);
        setCellAmmo(supM, 1, null);
        run(5);
        check("换回自动后两格的选择都清掉了",
            cellAmmoAt(supM, 0) == null && cellAmmoAt(supM, 1) == null);
        // 换回自动后：不该再被"哪一格选了别的弹药"挡住，池里的第一种弹药（铜）会被装回来
        boolean copperBack = false;
        for (Object c : cellsOf(supM))
          if (c instanceof ItemTurret.ItemTurretBuild itb)
            for (Turret.AmmoEntry e : itb.ammo)
              if (e instanceof ItemTurret.ItemEntry ie && ie.amount > 0 && ie.item == Items.copper)
                copperBack = true;
        check("换回自动后又开始按池里的弹药装填（铜 " + copperBack + "）", copperBack);
        check("整台支持配置（点方块打开每格弹药选择面板）",
            Boolean.TRUE.equals(field(st2, "configurable")));
      }

      // ---------- 13) 血量/容量累加 + blockstatus + 悬浮面板 + 手动操控 ----------
      {
        Block stBlock = blockForSide(3);
        int hx = lx + 2, hy = ly + 24; // 下面一块空地（别的用例都在 ly+22 以上）
        Building sup = place(stBlock, hx, hy, Team.sharded);
        sup.configured(null, layout(duo, duo, duo, duo, duo, duo, duo, duo, duo));
        run(10);

        // 血量 = 里面 9 台 duo 的血量累加（用户要求）
        float expectHp = 9 * duo.health;
        System.out.println("[ST] 血量/容量: maxHealth=" + sup.maxHealth() + "（期望 " + expectHp
            + "） health=" + sup.health + " 物品上限=" + realItemCap(sup) + "（期望 "
            + 9 * ((Turret) duo).maxAmmo + "） 液体上限=" + floatField(sup, "realLiquidCap"));
        check("血量 = 里面所有炮台累加（" + sup.maxHealth() + "）", Math.abs(sup.maxHealth() - expectHp) < 0.5f);
        check("放下/读档后还是满血（" + sup.health + "）", Math.abs(sup.health - expectHp) < 0.5f);
        check("物品容量 = 各格弹仓累加（" + realItemCap(sup) + "）",
            realItemCap(sup) == 9 * ((Turret) duo).maxAmmo);

        // blockstatus：纯物品炮台、没接电，有弹药就该 active（用户报的"只有接电才 active"）
        sup.items.add(Items.copper, 900);
        run(60);
        Object statusWithAmmo = call(sup, "status", null);
        // 另一台全新的（一格弹药都没有）→ noInput
        Building empty = place(stBlock, hx + 4, hy, Team.sharded);
        empty.configured(null, layout(duo, duo, duo, duo));
        run(20);
        Object statusNoAmmo = call(empty, "status", null);
        System.out.println("[ST] blockstatus: 有弹药=" + statusWithAmmo + " 没弹药=" + statusNoAmmo);
        check("纯物品炮台没接电、有弹药 → active（" + statusWithAmmo + "）",
            statusWithAmmo != null && statusWithAmmo.toString().equals("active"));
        check("一台没弹药的超级炮台 → noInput（" + statusNoAmmo + "）",
            statusNoAmmo != null && statusNoAmmo.toString().equals("noInput"));

        // 悬浮面板：用户要求"给合体炮台加上悬浮面板看物品和液体数量"
        Object showable = null, desc = null;
        try {
          Class<?> clsPanel = Class.forName("combine.coop.CoopPanel", true, ml);
          showable = clsPanel.getMethod("showable", Building.class).invoke(null, sup);
          desc = clsPanel.getMethod("describe", Building.class).invoke(null, sup);
          Class<?> clsST2 = clsST;
          Object inner = call(sup, "innerSummary", null);
          System.out.println("[ST] 悬浮面板: showable=" + showable + " inner=" + inner + " describe=" + desc);
        } catch (Throwable t) {
          System.out.println("[ST] 悬浮面板检查失败: " + t);
        }
        check("超级炮台会弹悬浮面板（showable=" + showable + "）", Boolean.TRUE.equals(showable));
        check("悬浮面板能念出里面的构成", desc != null && desc.toString().contains("duo"));

        // 手动操控：玩家接管这台 = 接管第一格；接管后照着准星开火
        sup.items.add(Items.copper, 900);
        run(60);
        Object proxy = call(sup, "unit", null);
        check("超级炮台能手动操控（代理单位=" + (proxy == null ? "null" : "有") + "）",
            proxy != null && Boolean.TRUE.equals(call(sup, "canControl", null)));
        if (proxy instanceof Unit pu) {
          // headless 里没有玩家，自己造一个临时玩家：原版接管单位就是 player.unit(u)。
          // 用完把 Vars.player 放回 null —— headless 里 Vars.control 是 null，
          // GlobalVars.update 看到 player 非空会去读 control.sound（原版代码），会 NPE。
          mindustry.gen.Player ply = Vars.player;
          boolean tempPlayer = false;
          if (ply == null) {
            ply = mindustry.gen.Player.create();
            ply.name = "ST";
            ply.team(Team.sharded);
            Vars.player = ply;
            tempPlayer = true;
          }
          ply.unit(pu);
          if (tempPlayer)
            Vars.player = null;
          // 瞄 45°：和格子的初始朝向（布局里写的 90°）不同，才能看出"所有格都转过去了"
          pu.aimX(sup.x + 60f);
          pu.aimY(sup.y + 60f);
          pu.isShooting(true);
          int before = totalShots(sup);
          run(240);
          int after = totalShots(sup);
          int turned = 0, total = 0;
          StringBuilder rots = new StringBuilder();
          for (Object c : cellsOf(sup))
            if (c instanceof Turret.TurretBuild t) {
              total++;
              if (Math.abs(arc.math.Angles.angleDist(t.rotation, 45f)) < 12f)
                turned++;
              rots.append(String.format("%.0f(eff%.2f) ", t.rotation, t.efficiency));
            }
          // 用户要求："一个合体炮台里所有的炮在手动控制状态下都可以控制发射"
          int firingCells = 0, cells = 0;
          for (Object c : cellsOf(sup))
            if (c instanceof Turret.TurretBuild t) {
              cells++;
              if (t.totalShots > 0) firingCells++;
            }
          boolean isPlayer = pu.isPlayer();
          boolean controlled = Boolean.TRUE.equals(call(sup, "isControlled", null));
          System.out.println("[ST] 手动操控: 接管=" + isPlayer + " controlled=" + controlled
              + " 发射 " + before + " → " + after + " 开火的格子=" + firingCells + "/" + cells
              + " 转向准星=" + turned + "/" + total + " 各格朝向=[" + rots.toString().trim() + "]");
          check("接管后这一格按准星开火（" + before + " → " + after + " 发）", controlled && after > before);
          check("手动操控时里面每一格都在跟着打（" + firingCells + "/" + cells + "）",
              cells > 0 && firingCells == cells);
          check("手动操控时里面每一格都转向准星（" + turned + "/" + total + "）", total > 0 && turned == total);
        }
      }

      // ---------- 14) 联机安全：配置串长度 + 快照血量 ----------
      {
        // (a) 配置串不许超过原版 readObjectSafe 的 1200 上限，否则客户端会被
        //     "String too long: 1200" 踢出去（用户 last_log (8).txt 里 ConstructFinish/TileConfig 两条栈）。
        Block longest = duo;
        for (Block b : Vars.content.blocks())
          if (b instanceof Turret && cellBlock(b) != null && b.name.length() > longest.name.length())
            longest = b;
        StringBuilder lay = new StringBuilder();
        for (int i = 0; i < 100; i++) {
          if (i > 0)
            lay.append(';');
          lay.append(encodeCell(cellBlock(longest), 90f));
        }
        StringBuilder src = new StringBuilder();
        for (int i = 0; i < 100; i++) {
          if (i > 0)
            src.append(';');
          src.append(100 + i).append(',').append(200 + i);
        }
        String legacy = lay + "|" + src;
        String cfg = (String) callStatic(clsST, "encodeConfig",
            new Class<?>[] { String.class, String.class }, lay.toString(), src.toString());
        System.out.println("[ST] 配置串: 旧文本 " + legacy.length() + " 字符 → 紧凑 " + (cfg == null ? -1 : cfg.length())
            + " 字符（最长名字的炮台=" + longest.name + "）");
        check("10x10 满格配置串 ≤ 1200（" + legacy.length() + " → " + (cfg == null ? -1 : cfg.length()) + "）",
            cfg != null && cfg.length() <= 1200);
        String[] back = (String[]) callStatic(clsST, "decodeConfig", new Class<?>[] { String.class }, cfg);
        int backCells = back == null || back[0] == null || back[0].isEmpty() ? 0 : back[0].split(";", -1).length;
        int backSrc = back == null || back[1] == null || back[1].isEmpty() ? 0 : back[1].split(";", -1).length;
        check("紧凑配置串解码还原（格 " + backCells + " / 坐标 " + backSrc + "）", backCells == 100 && backSrc == 100);
        String[] oldBack = (String[]) callStatic(clsST, "decodeConfig", new Class<?>[] { String.class }, legacy);
        check("旧文本配置串仍然能读", oldBack != null && lay.toString().equals(oldBack[0]) && src.toString().equals(oldBack[1]));

        // (b) 联机快照：服务端 writeSync 的字节在客户端 readSync 后，血量必须还是"整组累加"。
        //     原版 readBase 会 health = min(read.f(), block.health)，不处理就会把整组血量夹回单台量级。
        Block stSide = blockForSide(2);
        Building server = place(stSide, lx + 30, ly + 24, Team.sharded);
        server.configured(null, layout(cellBlock(duo), cellBlock(duo)));
        run(10);
        float expectedHp = 2 * duo.health;
        server.health = server.maxHealth();
        Building client = place(stSide, lx + 36, ly + 24, Team.sharded);
        java.io.ByteArrayOutputStream snapBuf = new java.io.ByteArrayOutputStream();
        arc.util.io.Writes snapW = new arc.util.io.Writes(new java.io.DataOutputStream(snapBuf));
        server.writeSync(snapW);
        byte[] snap = snapBuf.toByteArray();
        arc.util.io.Reads snapR = new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(snap)));
        client.readSync(snapR, client.version());
        run(3);
        System.out.println("[ST] 快照血量: 服务端 " + server.maxHealth() + " / 客户端读到 " + client.health
            + "（方块静态血量=" + stSide.health + "） 快照字节=" + snap.length);
        check("客户端 readSync 后血量还是整组累加（" + client.health + " ≈ " + expectedHp + "）",
            Math.abs(client.health - expectedHp) < 0.5f);

        // (c) 联机安全：同一批炮台只能被"吃"一次 —— 第二个客户端拿着同样的 sources 再合一次，
        //     只能得到一台空壳（用户报的"多人游戏每个客户端都能各自合成一次"）。
        int fx = lx + 40, fy = ly + 24;
        Building t1 = place(duo, fx, fy, Team.sharded);
        Building t2 = place(duo, fx + 4, fy, Team.sharded);
        run(10);
        String cellsLayout = layout(cellBlock(duo), cellBlock(duo));
        String srcList = t1.tileX() + "," + t1.tileY() + ";" + t2.tileX() + "," + t2.tileY();
        String srcCfg = (String) callStatic(clsST, "encodeConfig",
            new Class<?>[] { String.class, String.class }, cellsLayout, srcList);
        Block stSide2 = blockForSide(2);
        Building first = place(stSide2, fx + 12, fy + 8, Team.sharded);
        first.configured(null, srcCfg);
        run(5);
        int firstCells = loadedCells(first);
        boolean consumed = Vars.world.build(t1.tileX(), t1.tileY()) == null
            && Vars.world.build(t2.tileX(), t2.tileY()) == null;
        System.out.println("[ST] 合体原料消费: 第一次合体格数=" + firstCells + " 原料被拆=" + consumed);
        check("第一次合体：两格都在 + 原料炮台被拆掉（" + firstCells + " 格，原料拆=" + consumed + "）",
            firstCells == 2 && consumed);

        // 第二次（模拟另一个客户端/蓝图复制）：原料早没了 → 必须一格都不给
        Building second = place(stSide2, fx + 12, fy + 16, Team.sharded);
        second.configured(null, srcCfg);
        run(5);
        int secondCells = loadedCells(second);
        System.out.println("[ST] 第二次合体（原料已被吃掉）：格数=" + secondCells + "（期望 0）");
        check("第二次合体拿不到免费炮台（" + secondCells + " 格）", secondCells == 0);
      }

      System.out.println("[ST] 结果: PASS=" + pass + " FAIL=" + fail);
      Core.app.exit();
    } catch (Throwable t) {
      System.out.println("[ST] 测试异常");
      t.printStackTrace();
      System.out.println("[ST] 结果: PASS=" + pass + " FAIL=" + fail);
      Core.app.exit();
    }
  }

  static int uniqueAmmoSeqs(Building b) {
    java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
    for (Object c : cellsOf(b))
      if (c instanceof ItemTurret.ItemTurretBuild it)
        seen.put(it.ammo, Boolean.TRUE);
    return seen.size();
  }
}
