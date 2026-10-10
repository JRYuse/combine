package combine.distribution;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.GlyphLayout;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.scene.ui.layout.Scl;
import arc.struct.IntIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.pooling.Pools;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.LineConfirmEvent;
import mindustry.game.EventType.TapEvent;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.graphics.Layer;
import mindustry.input.MobileInput;
import mindustry.world.Block;
import mindustry.world.Tile;

/**
 * 组合传送带的「覆盖叠加」入口 —— **先放预设，按建造才生效**。
 *
 * <p>玩法：手里选着某一种传送带，点/拖到**同一格上已经造好的同种传送带**上 →
 * 那一格出现一个正常的**预设**（放置虚影，可以再点一下取消）；
 * 手机上按**建造（✓）按钮**、桌面上**松开鼠标**（桌面没有 ✓，松手就是提交）→ 才真的给这条带子
 * **叠一层**，速度按层数成倍（最多 {@link #MAX_LAYERS} 层）。长按拖一整排就是"整排各放一个预设"。
 * 只有**相同的实例**（同一个方块、方向也一致）能叠 —— 选另一种传送带点上去，还是原版的"替换"逻辑。
 *
 * <p>为什么要绕这一圈：原版 {@code Build.validPlace} 把"同一个方块 + 同方向"直接判成非法
 * （{@code type == check.block() && rotation == check.build.rotation && type.rotate}），
 * 于是"在传送带上再盖一条同种传送带"这个动作**根本进不了放置流程** —— {@code InputHandler.flushPlans}
 * 先过 {@code validPlace}，连 {@code Block.onNewPlan} 都不会调，虚影也画成"不能放"的红色。
 * 所以手机上我们把这种覆盖做成一个**预设计划**，旋转填 {@code 方向 + 4}（{@code Mathf.mod} 之后还是同一个
 * 方向，但对原版校验来说"和这格上的方块旋转不同"，于是走原版 {@code isRotation} 这条路合法通过），
 * 玩家按 ✓ 时原版会把这个计划交给 {@code Block.onNewPlan} —— 我们在那里才真的叠层
 * （见 {@link #onNewPlan(Block, BuildPlan)}），并把这个计划作废（不真造一条新带子）。
 *
 * <p>真正改层数走 {@code Call.tileConfig}（和原版排序器/门开关同一条通道，服务端权威执行 + 转发给所有客户端），
 * 单机、联机（含别人看到、含中途入服的客户端）都一致；层数本身走 {@link ConveyorLayerState}
 * （存档 + 联机入服世界流）。
 */
public class ConveyorOverlay {
  /** 最多叠几层（1 层 = 原速）。 */
  public static final int MAX_LAYERS = 5;
  /** 设置项 key：组合传送带最大堆叠层数（滑块 1..10，默认 {@link #MAX_LAYERS}，即时生效）。 */
  public static final String SETTING_MAX_LAYERS = "combine-conveyor-max-layers";
  /** 单独配置（按传送带名字）的存储键。 */
  static final String CUSTOM_KEY = "combine-conveyor-layer-data";
  /** 单独配置：方块名 → 自定义上限（0 / 缺失 = 未配置，走全局滑块默认）。 */
  public static final ObjectMap<String, Integer> customLayers = new ObjectMap<>();
  private static boolean customLoaded = false;

  /** 设置值（无头 / 未配置时回默认）。 */
  public static int settingMaxLayers() {
    return Mathf.clamp(Core.settings.getInt(SETTING_MAX_LAYERS, MAX_LAYERS), 1, 10);
  }

  /** 读入单独配置（幂等）。 */
  public static void loadLayers() {
    if (customLoaded)
      return;
    customLoaded = true;
    customLayers.clear();
    if (Core.settings == null)
      return;
    String raw = Core.settings.getString(CUSTOM_KEY, "");
    if (raw.isEmpty())
      return;
    for (String line : raw.split("\n")) {
      if (line.trim().isEmpty())
        continue;
      int eq = line.indexOf('=');
      if (eq <= 0)
        continue;
      try {
        customLayers.put(line.substring(0, eq).trim(), Integer.parseInt(line.substring(eq + 1).trim()));
      } catch (Throwable t) {
        Log.err("[combine] 读取传送带堆叠配置失败: " + line, t);
      }
    }
  }

  public static void saveLayers() {
    if (Core.settings == null)
      return;
    StringBuilder sb = new StringBuilder();
    for (var e : customLayers.entries()) {
      if (sb.length() > 0)
        sb.append('\n');
      sb.append(e.key).append('=').append(e.value);
    }
    Core.settings.put(CUSTOM_KEY, sb.toString());
    Core.settings.saveValues();
  }

  /** 某传送带的单独上限（0 = 未配置，用全局滑块默认）。 */
  public static int customLayersOf(Block block) {
    if (block == null || block.name == null)
      return 0;
    return customLayers.get(block.name, 0);
  }
  /**
   * 预设计划的旋转偏移：{@code 方向 + 4}。
   * {@code Mathf.mod(rotation, 4)} 之后还是同一个方向，但能让 {@code Build.validPlace} 把
   * "同方块同方向"那条硬判据（{@code rotation == check.build.rotation}）绕过去，走原版
   * {@code BuildPlan.isRotation} 的"原地改这条带子"语义（免费、不会被 skip）。
   */
  public static final int PRESET_ROTATION_OFFSET = 4;
  /** 事件只能注册一次。 */
  static boolean registered = false;
  /** 这一轮拖拽扫过的格（pos → 该格的放置方向）；松手时用。 */
  static final IntIntMap dragLine = new IntIntMap();

  /** 内容装配时给每个组合传送带注册"层数"配置（幂等）。 */
  public static void install(Block block) {
    if (block == null)
      return;
    try {
      block.config(Integer.class, (Building build, Integer value) -> {
        if (build instanceof Layered layered && value != null)
          layered.layers(value);
      });
    } catch (Throwable t) {
      Log.err("[combine] 组合传送带层数配置注册失败: " + block.name, t);
    }
  }

  /** 客户端 + 服务端都注册（事件本身两端都会发，靠玩家身份区分）。 */
  public static void register() {
    if (registered)
      return;
    registered = true;

    // ① 点/按一下。桌面：什么都不做（松手那条路提交，见 LineConfirmEvent）。
    //    手机：把这一格做成"预设"，等玩家按建造按钮（✓）才生效。
    Events.on(TapEvent.class, e -> {
      try {
        if (!isMobileInput() || e.player == null || e.player != Vars.player || e.tile == null)
          return;
        final Player player = e.player;
        final Tile tile = e.tile;
        final int rotation = Vars.control.input.rotation;
        // 【必须下一帧】MobileInput.tap 里发完这个事件紧接着就 hasPlan()→removePlan()
        // （那一步是"再点一次同一个预设=取消"），同一帧加进去会被它当成取消立刻删掉。
        Core.app.post(() -> {
          try {
            if (Vars.control == null || Vars.control.input == null)
              return;
            addPreset(player, Vars.control.input.selectPlans, Vars.control.input.block, tile, rotation);
          } catch (Throwable t) {
            Log.err("[combine] 传送带覆盖预设失败", t);
          }
        });
      } catch (Throwable t) {
        Log.err("[combine] 传送带覆盖点击失败", t);
      }
    });

    // ② 拖拽松手：桌面松左键 / 手机拖完抬手（原版都会 fire LineConfirmEvent）
    Events.on(LineConfirmEvent.class, e -> {
      try {
        if (Vars.headless || Vars.control == null || Vars.control.input == null) {
          dragLine.clear();
          return;
        }
        Block selected = Vars.control.input.block;
        if (isMobileInput()) {
          // 手机：拖过的每一格做成预设，等玩家按建造按钮（✓）才生效
          addPresets(Vars.player, Vars.control.input.selectPlans, selected);
        } else {
          // 桌面：松手就是"建造"，直接提交
          applyLine(Vars.player, selected);
        }
      } catch (Throwable t) {
        Log.err("[combine] 传送带拖拽叠加失败", t);
      }
    });
  }

  /** 当前输入是不是手机那套（有"建造 ✓ 按钮"、放置先变成预设的那种）。 */
  public static boolean isMobileInput() {
    return Vars.control != null && Vars.control.input instanceof MobileInput;
  }

  /** 是不是"组合传送带"这三类方块之一。 */
  public static boolean isConveyorBlock(Block block) {
    return block instanceof CombinedConveyor || block instanceof CombinedDuct
        || block instanceof CombinedStackConveyor;
  }

  /** 方块层数上限：单独配置（{@link #customLayers}）优先，否则走全局滑块默认（{@link #SETTING_MAX_LAYERS}）。 */
  public static int maxLayers(Block block) {
    int custom = customLayersOf(block);
    return custom > 0 ? Mathf.clamp(custom, 1, 10) : settingMaxLayers();
  }

  /**
   * 这一格能不能"覆盖叠层"：手里选的必须是**同一格上那种**传送带（相同实例）、方向也要一致。
   *
   * @return 能叠就返回那台的 {@code Build}，否则 null（到上限、异种、异向都算不能）
   */
  public static Building overlayTarget(Block selected, int rotation, Tile tile) {
    if (selected == null || tile == null || tile.build == null || tile.build.dead)
      return null;
    if (!isConveyorBlock(selected))
      return null;
    Building build = tile.build;
    // 【只能覆盖相同的实例】
    if (build.block != selected)
      return null;
    // 方向不同的话原版本来就会"原地改方向"（quickRotate），那条路保持原样不动
    if (build.rotation != rotation)
      return null;
    if (!(build instanceof Layered layered))
      return null;
    if (layered.layers() >= maxLayers(build.block))
      return null;
    return build;
  }

  /** 叠一层（走配置通道：单机本地生效；联机服务端执行 + 转发）。 */
  static boolean addLayer(Player player, Building build) {
    if (!(build instanceof Layered layered))
      return false;
    int target = Math.min(layered.layers() + 1, maxLayers(build.block));
    if (target <= layered.layers())
      return false;
    Call.tileConfig(player, build, target);
    return true;
  }

  // ==================== 手机：放置预设，按 ✓ 才生效 ====================

  /**
   * 把"覆盖这一格"做成一个**预设计划**放进放置队列（手机放置 UI 的那一串虚影）。
   * 玩家按建造按钮时，原版会把队列里的计划逐个交给 {@link #onNewPlan(Block, BuildPlan)}。
   *
   * @return 有没有成功放下预设
   */
  public static boolean addPreset(Player player, Seq<BuildPlan> plans, Tile tile, int rotation) {
    return addPreset(player, plans,
        Vars.control == null || Vars.control.input == null ? null : Vars.control.input.block, tile, rotation);
  }

  /** 同上，方块显式传（方便无头回归）。 */
  public static boolean addPreset(Player player, Seq<BuildPlan> plans, Block selected, Tile tile, int rotation) {
    if (plans == null || tile == null)
      return false;
    if (overlayTarget(selected, rotation, tile) == null)
      return false;
    int pos = tile.pos();
    // 这一格已经有预设/计划了就不重复放（再点一次由原版当"取消"处理）
    for (int i = 0; i < plans.size; i++) {
      BuildPlan p = plans.get(i);
      if (p != null && !p.breaking && Point2.pack(p.x, p.y) == pos)
        return false;
    }
    plans.add(new BuildPlan(tile.x, tile.y, rotation + PRESET_ROTATION_OFFSET, selected, null));
    return true;
  }

  /** 把这一轮拖拽扫过的格都做成预设（手机拖拽松手时）。 */
  public static int addPresets(Player player, Seq<BuildPlan> plans, Block selected) {
    if (dragLine.size == 0)
      return 0;
    int count = 0;
    try {
      for (IntIntMap.Entry entry : dragLine) {
        Tile tile = Vars.world == null ? null : Vars.world.tile(entry.key);
        if (tile == null || overlayTarget(selected, entry.value, tile) == null)
          continue;
        if (addPreset(player, plans, selected, tile, entry.value))
          count++;
      }
    } finally {
      dragLine.clear();
    }
    return count;
  }

  // ==================== 桌面：松手即提交 ====================

  /**
   * 拖拽预览期间记录"这一轮拖过的格"（由组合传送带自己的 {@code handlePlacementLine} 调；
   * 原版每拖到新的一格都会调一次。只有玩家关掉原版"自动替换/补桥"那个设置时才不会调，
   * 那种情况下点击仍然能用，只是拖拽不叠）。
   */
  public static void captureLine(Seq<BuildPlan> plans) {
    try {
      dragLine.clear();
      if (plans == null)
        return;
      for (BuildPlan plan : plans) {
        // 只算"手里选的就是组合传送带"的计划：被原版换成路口(junction)/桥的那些计划不算
        if (plan == null || plan.breaking || !isConveyorBlock(plan.block))
          continue;
        dragLine.put(Point2.pack(plan.x, plan.y), plan.rotation);
      }
    } catch (Throwable t) {
      Log.err("[combine] 记录拖拽传送带失败", t);
    }
  }

  /** 松手提交（桌面）：这条线扫过的同种传送带各叠一层。 */
  public static int applyLine(Player player, Block selected) {
    if (dragLine.size == 0)
      return 0;
    int count = 0;
    try {
      for (IntIntMap.Entry entry : dragLine) {
        Tile tile = Vars.world == null ? null : Vars.world.tile(entry.key);
        Building build = overlayTarget(selected, entry.value, tile);
        if (build != null && addLayer(player, build))
          count++;
      }
    } finally {
      dragLine.clear();
    }
    return count;
  }

  // ==================== 按建造（✓）时：原版把这个计划交过来 ====================

  /**
   * 方块 {@code onNewPlan} 被调用（手机上按 ✓ / 桌面上松手后入队）时：如果这是一个"同方向覆盖"的
   * 预设计划（旋转是 {@code 方向 + PRESET_ROTATION_OFFSET}），就**这时候**才叠层，并把计划作废
   * （旋转改回原方向 → 单位那边立刻按"同一格同一个方块"丢掉，不会再造一条新带子）。
   *
   * <p>方向不同（数值也不同）的是原版"原地改方向"的计划，这里不碰。
   */
  public static void onNewPlan(Block self, BuildPlan plan) {
    try {
      if (plan == null || plan.block != self || plan.breaking)
        return;
      Tile tile = plan.tile();
      if (tile == null || tile.build == null || tile.build.dead)
        return;
      Building build = tile.build;
      // 只要"我们的预设"：方向（mod 4 后）和这格上的带子一致，但旋转数值不同
      if (Mathf.mod(plan.rotation, 4) != build.rotation || plan.rotation == build.rotation)
        return;
      if (build.block != self)
        return;
      addLayer(Vars.player, build);
      // 作废这个计划：不是要真造一条新带子，只是借原版"放置"这一步来提交
      plan.rotation = build.rotation;
    } catch (Throwable t) {
      Log.err("[combine] 处理传送带覆盖预设失败", t);
    }
  }

  /** 这个计划是不是"同方向覆盖"的预设（画虚影时用：别画成"不能放"的红色）。 */
  public static boolean isOverlayPlan(Block self, BuildPlan plan) {
    try {
      if (plan == null || plan.block != self || plan.breaking)
        return false;
      Tile tile = plan.tile();
      return overlayTarget(self, Mathf.mod(plan.rotation, 4), tile) != null;
    } catch (Throwable t) {
      return false;
    }
  }

  /** 叠上一层时的表现（放置音/特效）：客户端才有图集与音频，无头里跳过。 */
  public static void changedFx(Building build) {
    if (Vars.headless || build == null || build.block == null)
      return;
    try {
      if (build.block.placeSound != null)
        build.block.placeSound.at(build.x, build.y);
    } catch (Throwable ignored) {
    }
    try {
      if (build.block.placeEffect != null)
        build.block.placeEffect.at(build.x, build.y, build.block.size);
    } catch (Throwable ignored) {
    }
  }

  /** 建造菜单里的说明：把玩法写进原版方块的描述（名字保持不变，和模组其它组合方块一致）。 */
  public static void applyHints() {
    for (Block block : Vars.content.blocks()) {
      if (!isConveyorBlock(block))
        continue;
      try {
        String base = block.description == null ? "" : block.description;
        // 幂等：重复调用不会叠加多行
        if (base.contains("【组合】"))
          continue;
        block.description = base + (base.isEmpty() ? "" : "\n")
            + "【组合】手持同一种传送带（放置方向也一致），点在已经造好的同种传送带上、或者按住拖过一整排，"
            + "会先放下预设；按建造（✓）之后才覆盖叠加：每叠一层速度翻一倍，最多 "
            + maxLayers(block) + " 层（一次拖拽每格各叠一层）。";
      } catch (Throwable ignored) {
      }
    }
  }

  /** 层数数字的画法（世界坐标小字）：口径抄原版 {@code Block.drawPlaceText}。 */
  static final float badgeScale = 0.2f;

  /**
   * 【用户 2026-10-10】"组合传送带的数字显示放在**传送带身上**，而不是旁边"：
   * 叠过层（layers &gt; 1）的带子，把**层数**数字画在它自己那一格的中心（= 传送带身上）。
   *
   * <p>只有 1 层（默认状态）不画 —— 不然满地图都是数字。数字跟着方块走，缩放/平移和贴图一致；
   * 颜色走 {@code Fonts.outline}（自带描边），压在深色贴图上也能看清。
   */
  public static void drawLayerBadge(Building build, int layers) {
    if (Vars.headless || build == null || build.block == null || layers <= 1)
      return;
    try {
      var font = mindustry.ui.Fonts.outline;
      if (font == null)
        return;
      String text = Integer.toString(layers);
      boolean ints = font.usesIntegerPositions();
      float z = Draw.z();
      GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);
      try {
        font.setUseIntegerPositions(false);
        font.getData().setScale(badgeScale / Scl.scl(1f));
        layout.setText(font, text);
        // 带子本身画在 Layer.block - 0.5（原版 ConveyorBuild.draw 的 29.5），数字压在它上面一点点
        Draw.z(Layer.block + 0.2f);
        // 【必须把批颜色重置成不透明白】原版画建筑时可能留下 alpha=0/带色调的批颜色，
        // 那样 font.draw 画出来是全透明的（实测：日志"画完了"但截图上一个字都看不到）。
        Draw.color(Color.white);
        font.setColor(Color.white);
        font.draw(text, build.x - layout.width / 2f, build.y + layout.height / 2f);
      } finally {
        Pools.free(layout);
        font.getData().setScale(1f);
        font.setUseIntegerPositions(ints);
        Draw.z(z);
        Draw.color();
      }
    } catch (Throwable ignored) {
    }
  }
}
