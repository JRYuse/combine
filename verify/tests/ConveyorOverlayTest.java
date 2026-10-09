package combine.dbg;

import arc.*;
import arc.backend.headless.HeadlessApplication;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.io.Writes;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.units.BuildPlan;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.SaveFileReader;
import mindustry.maps.Map;
import mindustry.mod.*;
import mindustry.net.Net;
import mindustry.type.*;
import mindustry.ui.Fonts;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 组合传送带（覆盖叠层提速）回归。语义：**先放预设，按建造（✓）/松手提交后才生效**。
 * · 原版/模组的 Conveyor / ArmoredConveyor / Duct / StackConveyor 都被换成组合版（能叠层）；
 * · 桌面：拖拽/点击松手（LineConfirmEvent）提交 → 线上每格各叠一层；
 * · 手机：点/拖先出现"预设"（覆盖用的计划，旋转 = 方向+4，原版 validPlace 必须放行 —— 这就是 ✓ 按钮那一关），
 *   按 ✓ 时原版把这个计划交给 {@code Block.onNewPlan} → 我们在那里才叠层并把计划作废（不会真造一条新带子）；
 * · 叠层 = 层数 +1 = 速度成倍（1..上限，夹上限，只增不减）；只能覆盖相同实例（异种/异向不叠）；
 * · 层数只进自定义块（存档 + 联机入服世界流），地图区字节不随层数变化。
 *
 * <p>模组的类不在测试的编译 classpath 上（run-headless.sh 只编游戏 jar），
 * 所以和 DetachTest 一样用模组 classloader + 反射拿 {@code combine.distribution.*}。
 */
public class ConveyorOverlayTest implements ApplicationListener {
  static String dataDir = "/tmp/mp_coop/data";
  static int pass = 0, fail = 0;

  static Class<?> layeredCls, combinedConveyorCls, overlayCls, chunkCls;
  static Method layersGet, layersSet, speedMul, addPreset, applyLine, isOverlayPlan;
  static Field armoredField;
  static int MAX_LAYERS = 5, PRESET_OFFSET = 4;

  public static void main(String[] a) {
    if (a.length > 0)
      dataDir = a[0];
    Vars.platform = new Platform() {};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> {
      if (l.ordinal() >= Log.LogLevel.err.ordinal())
        System.out.println("[CV] ERR " + t);
    };
    new HeadlessApplication(new ConveyorOverlayTest(), t -> t.printStackTrace());
  }

  static void check(String n, boolean ok) {
    System.out.println("[CV] " + (ok ? "PASS " : "FAIL ") + n);
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

  static Block find(String name) {
    for (Block b : Vars.content.blocks())
      if (b.name.equals(name))
        return b;
    return null;
  }

  static Building place(Block b, int x, int y, Team team, int rot) {
    int size = Math.max(b.size, 1);
    int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
    mindustry.world.Build.beginPlace(null, b, team, ax, ay, rot, null);
    mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte) rot, team, null);
    Building bu = Vars.world.build(ax, ay);
    if (bu != null) {
      try {
        bu.created();
      } catch (Throwable ignored) {
      }
      try {
        bu.updateProximity();
      } catch (Throwable ignored) {
      }
    }
    return bu;
  }

  static boolean isCombo(Block b) {
    return b != null && b.getClass().getName().startsWith("combine.distribution.");
  }

  static boolean isLayered(Building b) {
    return b != null && layeredCls.isInstance(b);
  }

  static int layers(Building b) {
    try {
      return (Integer) layersGet.invoke(b);
    } catch (Throwable t) {
      return -1;
    }
  }

  static void setLayers(Building b, int v) {
    try {
      layersSet.invoke(b, v);
    } catch (Throwable t) {
      t.printStackTrace();
    }
  }

  static int maxLayers(Block b) {
    try {
      return (Integer) overlayCls.getMethod("maxLayers", Block.class).invoke(null, b);
    } catch (Throwable t) {
      return -1;
    }
  }

  /** 桌面：拖拽预览那条线 + 松手提交。 */
  static int dragAndCommit(Seq<BuildPlan> line, Block selected) {
    try {
      selected.handlePlacementLine(line); // 真正的方块方法：super（补桥/换路口）+ 记录这条线
      return (Integer) applyLine.invoke(null, null, selected);
    } catch (Throwable t) {
      t.printStackTrace();
      return -1;
    }
  }

  /** 手机：放预设（返回那个预设计划，没放下就是 null）。 */
  static BuildPlan preset(Seq<BuildPlan> plans, Block selected, Tile tile, int rotation) {
    try {
      boolean ok = (Boolean) addPreset.invoke(null, null, plans, selected, tile, rotation);
      return ok && plans.size > 0 ? plans.peek() : null;
    } catch (Throwable t) {
      t.printStackTrace();
      return null;
    }
  }

  /** 手机：放预设（不关心返回值以外的东西）。 */
  static boolean addPresetCall(Block selected, Tile tile, int rotation) {
    try {
      return (Boolean) addPreset.invoke(null, null, new Seq<BuildPlan>(), selected, tile, rotation);
    } catch (Throwable t) {
      t.printStackTrace();
      return false;
    }
  }

  /** 手机：按建造（✓）——原版 ✓ 按钮对每个计划做的就是这几步。 */
  static boolean pressBuild(BuildPlan plan) {
    if (plan == null)
      return false;
    if (!Build.validPlaceIgnoreUnits(plan.block, Team.sharded, plan.x, plan.y, plan.rotation, true, true))
      return false;
    BuildPlan copy = plan.copy();
    plan.block.onNewPlan(copy); // ← 我们的钩子在这里叠层
    // 单位那边入队后会被 validatePlans 丢掉（同方块同方向），不会真造一条新的
    return !Build.validPlace(plan.block, Team.sharded, copy.x, copy.y, copy.rotation);
  }

  /** 一条传送带上放一个物品，跑 frames 帧后它走了多远（ys[0]，1 = 走到头）。 */
  static float advance(Building build, int frames, Item item) {
    Conveyor.ConveyorBuild c = (Conveyor.ConveyorBuild) build;
    c.items.clear();
    c.len = 0;
    c.handleStack(item, 1, null);
    run(frames);
    float pos = c.ys[0];
    c.items.clear();
    c.len = 0;
    return pos;
  }

  /** 一个建筑在"地图区"里写出的字节数（= writeAll，原版读档读的就是这一段）。 */
  static int mapBytes(Building build) {
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      DataOutputStream dos = new DataOutputStream(bos);
      build.writeAll(new Writes(dos));
      dos.flush();
      return bos.toByteArray().length;
    } catch (Throwable t) {
      return -1;
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
      UI.loadColors();
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

      // 模组类都是模组 classloader 加载的，测试里只能反射
      ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
      layeredCls = Class.forName("combine.distribution.Layered", true, ml);
      combinedConveyorCls = Class.forName("combine.distribution.CombinedConveyor", true, ml);
      overlayCls = Class.forName("combine.distribution.ConveyorOverlay", true, ml);
      chunkCls = Class.forName("combine.distribution.ConveyorLayerState", true, ml);
      layersGet = layeredCls.getMethod("layers");
      layersSet = layeredCls.getMethod("layers", int.class);
      speedMul = layeredCls.getMethod("speedMul");
      armoredField = combinedConveyorCls.getField("armored");
      addPreset = overlayCls.getMethod("addPreset", Player.class, Seq.class, Block.class, Tile.class, int.class);
      applyLine = overlayCls.getMethod("applyLine", Player.class, Block.class);
      isOverlayPlan = overlayCls.getMethod("isOverlayPlan", Block.class, BuildPlan.class);
      MAX_LAYERS = overlayCls.getField("MAX_LAYERS").getInt(null);
      PRESET_OFFSET = overlayCls.getField("PRESET_ROTATION_OFFSET").getInt(null);

      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.state.rules.infiniteResources = true;
      Vars.logic.play();
      run(10);

      // ================= 1) 替换：原版那几种传送带/管道都换成组合版 =================
      Block conveyor = find("conveyor");
      Block titanium = find("titanium-conveyor");
      Block armoredConveyor = find("armored-conveyor");
      Block duct = find("duct");
      Block armoredDuct = find("armored-duct");
      Block plastanium = find("plastanium-conveyor");
      Block surge = find("surge-conveyor");
      String[] names = { "conveyor", "titanium-conveyor", "armored-conveyor", "duct", "armored-duct",
          "plastanium-conveyor", "surge-conveyor" };
      for (String n : names) {
        Block b = find(n);
        check("替换 " + n + " → " + (b == null ? "null" : b.getClass().getSimpleName()),
            isCombo(b) && isLayered(b.newBuilding()));
      }
      check("ArmoredConveyor 保留'防侧装'（armored=true），普通传送带不受影响",
          armoredField.getBoolean(armoredConveyor) && !armoredField.getBoolean(conveyor));
      check("组合管道保留 armored-duct 的 armored 开关",
          ((Duct) armoredDuct).armored && !((Duct) duct).armored);
      check("层数上限（方块字段）=" + maxLayers(conveyor) + "，和常量一致",
          maxLayers(conveyor) == MAX_LAYERS && MAX_LAYERS >= 2);

      // 找一块干净的陆地
      int bx = -1, by = -1;
      outer:
      for (int y = 24; y < 150; y++) {
        for (int x = 16; x < 220; x++) {
          boolean ok = true;
          for (int dy = 0; dy < 8 && ok; dy++)
            for (int dx = 0; dx < 16; dx++) {
              Tile t = Vars.world.tile(x + dx, y + dy);
              if (t == null || t.floor().isDeep() || t.block() != Blocks.air) {
                ok = false;
                break;
              }
            }
          if (ok) {
            bx = x;
            by = y;
            break outer;
          }
        }
      }
      if (bx < 0) {
        System.out.println("[CV] 找不到陆地");
        System.exit(3);
      }
      System.out.println("[CV] 试验田 = " + bx + "," + by);
      run(3);

      // ================= 2) 桌面：松手提交（不是按下就生效） =================
      Building[] row = new Building[5];
      for (int i = 0; i < row.length; i++)
        row[i] = place(conveyor, bx + i, by + 2, Team.sharded, 0);
      Building wrongDir = place(conveyor, bx + 6, by + 2, Team.sharded, 0);
      Building junctionPlan = place(conveyor, bx + 7, by + 2, Team.sharded, 0);
      run(2);

      Seq<BuildPlan> line = new Seq<>();
      for (int i = 0; i < row.length; i++)
        line.add(new BuildPlan(bx + i, by + 2, 0, conveyor, null));
      line.add(new BuildPlan(bx + 6, by + 2, 1, conveyor, null)); // 方向不一致的那一格
      line.add(new BuildPlan(bx + 7, by + 2, 0, Blocks.junction, null)); // 被原版换成路口的那种计划

      // 只是"预览"（handlePlacementLine 只记录，不改层数）
      conveyor.handlePlacementLine(line);
      check("拖拽预览阶段不叠层（先放预设，还没提交）",
          layers(row[0]) == 1 && layers(row[4]) == 1 && layers(wrongDir) == 1);

      int n1 = dragAndCommit(line, conveyor);
      int stacked = 0;
      for (Building b : row)
        if (layers(b) == 2)
          stacked++;
      System.out.println("[CV] 松手提交：叠上 " + n1 + " 格（整排 " + stacked + "/" + row.length
          + "），异向格层数=" + layers(wrongDir) + "，路口计划格层数=" + layers(junctionPlan));
      check("桌面松手提交：线上每格各叠一层（" + stacked + "/" + row.length + "）", stacked == row.length);
      check("提交：方向不一致的格不叠（交给原版原地改方向）", layers(wrongDir) == 1);
      check("提交：被原版换成路口(junction)的计划不算，不叠", layers(junctionPlan) == 1);
      int n2 = dragAndCommit(line, conveyor);
      check("再松手一次（新的一轮）→ 每格再叠一层（" + n2 + " 格）", layers(row[0]) == 3 && layers(row[4]) == 3);

      // ================= 3) 手机：预设 → 按建造（✓）才叠层 =================
      Building belt = place(conveyor, bx + 2, by + 5, Team.sharded, 0);
      run(2);
      Seq<BuildPlan> presets = new Seq<>();
      BuildPlan preset = preset(presets, conveyor, Vars.world.tile(bx + 2, by + 5), 0);
      check("点一下只放预设（队列里多了一个计划，层数还没变）",
          preset != null && presets.size == 1 && layers(belt) == 1);
      check("预设的旋转 = 方向 + " + PRESET_OFFSET + "（这样才过得了原版 validPlace）",
          preset != null && preset.rotation == PRESET_OFFSET + 0);
      check("原版 validPlace 放行这个预设（= 手机 ✓ 按钮那一关）",
          preset != null && Build.validPlaceIgnoreUnits(conveyor, Team.sharded, preset.x, preset.y,
              preset.rotation, true, true));
      check("预览里这个预设不算\"不能放\"（画虚影用）",
          (Boolean) isOverlayPlan.invoke(null, conveyor, preset));

      int before = layers(belt);
      boolean built = pressBuild(preset);
      System.out.println("[CV] 按建造（✓）：层数 " + before + " → " + layers(belt) + "，计划被作废=" + built);
      check("按建造（✓）才叠层（" + before + " → " + layers(belt) + "）", layers(belt) == before + 1);
      check("叠层后那个计划被作废（同方块同方向 → 单位那边会丢掉，不会真造一条新的）", built);

      // 预设只认"方向+偏移"的计划：原版真正的"原地改方向"计划（异向）不该被我们当成覆盖
      final int dirBefore = layers(belt);
      dragAndCommit(Seq.with(new BuildPlan(bx + 2, by + 5, 1, conveyor, null)), conveyor);
      check("异向计划不被当作覆盖（层数不变）", layers(belt) == dirBefore);

      // 异种 / 到上限 都不放预设
      check("异种传送带点上去不放预设（只能覆盖相同的实例）",
          !addPresetCall(titanium, Vars.world.tile(bx + 2, by + 5), 0) && titanium.canReplace(conveyor));
      setLayers(belt, MAX_LAYERS);
      check("到上限后不再放预设", !addPresetCall(conveyor, Vars.world.tile(bx + 2, by + 5), 0));
      setLayers(belt, 1);

      // ================= 4) 速度真的按层数变快 =================
      Building spd = place(conveyor, bx + 8, by + 4, Team.sharded, 0);
      run(2);
      int frames = 4;
      float one = advance(spd, frames, Items.copper);
      setLayers(spd, MAX_LAYERS);
      float four = advance(spd, frames, Items.copper);
      System.out.println("[CV] " + frames + " 帧位移：1 层=" + one + "，" + MAX_LAYERS + " 层=" + four
          + "（方块速度字段=" + ((Conveyor) conveyor).speed + "，层数=" + layers(spd) + "）");
      check("满层比 1 层快 ≈" + MAX_LAYERS + " 倍（实测 "
          + String.format("%.2f", one > 0 ? four / one : 0) + " 倍）",
          one > 0.01f && four >= one * (MAX_LAYERS - 1f) && four <= one * (MAX_LAYERS + 0.5f));
      setLayers(spd, 1);
      check("层数只增不减（服务端/客户端重复或更小的值不会打回去）", layers(spd) == MAX_LAYERS);

      // ================= 5) 层数只进自定义块（地图区字节不变）+ 存档/入服 =================
      Building thin = place(conveyor, bx + 6, by + 5, Team.sharded, 0);
      Building thick = place(conveyor, bx + 7, by + 5, Team.sharded, 0);
      setLayers(thick, MAX_LAYERS);
      int bytes1 = mapBytes(thin);
      int bytes4 = mapBytes(thick);
      System.out.println("[CV] 地图区字节：1 层=" + bytes1 + "，满层=" + bytes4
          + "（层数=" + layers(thin) + "/" + layers(thick) + "）");
      check("地图区字节不随层数变化（关掉模组原版读档不错位）",
          bytes1 > 0 && bytes1 == bytes4 && layers(thick) == MAX_LAYERS);

      SaveFileReader.CustomChunk chunk = (SaveFileReader.CustomChunk) chunkCls.getConstructor().newInstance();
      check("层数自定义块要进联机入服世界流（writeNet=true）", chunk.writeNet());
      check("有叠层的传送带时才写自定义块", chunk.shouldWrite());
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      DataOutputStream dos = new DataOutputStream(bos);
      chunk.write(dos);
      dos.flush();
      byte[] saved = bos.toByteArray();
      System.out.println("[CV] 层数块字节数=" + saved.length);
      Vars.world.tile(bx + 8, by + 4).setBlock(Blocks.air);
      run(2);
      Building reborn = place(conveyor, bx + 8, by + 4, Team.sharded, 0);
      int beforeRead = layers(reborn);
      chunk.read(new DataInputStream(new ByteArrayInputStream(saved)));
      int afterRead = layers(reborn);
      System.out.println("[CV] 读回层数：" + beforeRead + " → " + afterRead);
      check("存档/入服块能把层数读回来（新放的 1 层 → 满层）",
          beforeRead == 1 && afterRead == MAX_LAYERS);
      run(200);
      check("空转（sleep）的传送带层数也收得到", chunk.shouldWrite());

      // ================= 6) 存档字节仍与原版对称（version 不变） =================
      ObjectMapRef originalMap = new ObjectMapRef(ml);
      boolean versionOk = true;
      for (String n : names) {
        Block b = find(n);
        Block original = originalMap.get(b);
        if (original == null)
          continue;
        try {
          byte orig = original.newBuilding().version();
          byte now = b.newBuilding().version();
          if (orig != now) {
            versionOk = false;
            System.out.println("[CV] version 不一致：" + n + " 原版=" + orig + " 组合=" + now);
          }
        } catch (Throwable ignored) {
        }
      }
      check("组合传送带的存档 version() 与原版一致（原版读档按同一格式读）", versionOk);

      System.out.println("[CV] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(2);
    }
  }

  /** {@code combine.BlockCloner.comboToOriginal} 的反射包装（模组类不在编译 classpath 上）。 */
  static class ObjectMapRef {
    final Object map;
    final Method get;

    ObjectMapRef(ClassLoader ml) throws Exception {
      Class<?> cloner = Class.forName("combine.BlockCloner", true, ml);
      map = cloner.getField("comboToOriginal").get(null);
      get = map.getClass().getMethod("get", Object.class, Object.class);
    }

    Block get(Block combo) {
      try {
        return (Block) get.invoke(map, combo, null);
      } catch (Throwable t) {
        return null;
      }
    }
  }
}
