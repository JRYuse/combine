package combine.turret;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.input.InputProcessor;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Time;
import mindustry.core.World;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Schematic;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.input.Binding;
import mindustry.input.DesktopInput;
import mindustry.input.PlaceMode;
import mindustry.ui.Fonts;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.Turret;

import static mindustry.Vars.*;

/**
 * 超级组合炮台的选择/放置流程（纯客户端）。
 *
 * <p>交互：按快捷键（设置 → 按键里可改，默认 G），或者在建造菜单里点一下"超级组合炮台"
 * （那几个方块就是入口按钮，点了不会被摆下去）→ 进入框选模式
 * （这时左键只用来画框，不会开枪/开面板/摆方块）→ 拖一个框松开 → 数出框里的炮台、
 * 按数量生成对应边长的超级组合炮台虚影跟着鼠标走 → 左键确定、右键/Q/Esc 取消。
 * 再按一次快捷键（或右键）可以在框选阶段取消。
 *
 * <p>放置走原版蓝图那套（{@code input.useSchematic}），所以虚影、合法性染色、
 * 建造队列/多线程建造、联机同步全都沿用原版。
 */
public class SuperTurretPlacer {
  /** 快捷键（设置 → 按键里可改；默认 G）。 */
  public static @Nullable KeyBind selectKey;

  static boolean selecting = false;
  static boolean dragging = false;
  static int sx = -1, sy = -1, ex = -1, ey = -1;
  /** 正在拖这个框的手指：多点触控时只认起手的那一根，别的手指既不抢框、也不漏给移动摇杆。 */
  static int boxPointer = -1;

  /** 框选期间缓存的"框里的炮台"（拖动时每几帧重算一次，画高亮和数字用）。 */
  static final Seq<Building> scanCache = new Seq<>();
  static long scanFrame = -1000L;

  /** 联机时客户端自己那份建筑拿不到 plan 里的布局，这里按"最近这次框选"补一次 config。 */
  static final Seq<Pending> pending = new Seq<>();
  static final InputProcessor processor = new Processor();
  /** HUD 上的入口按钮（用户要求"点击 ui 按钮触发框选，而不是建造界面的建筑"）。 */
  static @Nullable arc.scene.Element button;
  /** HUD 按钮距屏幕顶部的留白（场景单位）：贴右上角会盖住小地图，往下让一点落在它正下方。 */
  static final float BUTTON_TOP_PAD = 225f;
  static int buttonRetry = 0;

  static class Pending {
    final String layout;
    float frames = 0f;

    Pending(String layout) {
      this.layout = layout;
    }
  }

  public static boolean selecting() {
    return selecting;
  }

  public static void register() {
    // 【停用中】见 SuperTurret.enabled：总开关关着就不挂任何入口
    if (headless || !SuperTurret.enabled)
      return;
    try {
      selectKey = KeyBind.add("combine_super_turret", KeyCode.g, "combine");
    } catch (Throwable t) {
      Log.err("[combine] 超级组合炮台快捷键注册失败（还能用建造菜单里的按钮）", t);
    }
    Events.run(Trigger.update, SuperTurretPlacer::update);
    Events.run(Trigger.draw, SuperTurretPlacer::draw);
    Events.on(WorldLoadEvent.class, e -> {
      selecting = false;
      dragging = false;
      sx = sy = ex = ey = -1;
      pending.clear();
      button = null; // HUD 重建了，下一帧补挂
      buttonRetry = 0;
    });
    Events.on(mindustry.game.EventType.TileChangeEvent.class, e -> {
      try {
        onTileChanged(e.tile);
      } catch (Throwable ignored) {
      }
    });
  }

  // ==================== 选择流程 ====================

  /** 快捷键/按钮入口：再按一次就取消。 */
  public static void toggle() {
    if (selecting) {
      cancel();
    } else {
      start();
    }
  }

  public static void start() {
    if (headless || state == null || !state.isGame() || player == null || !player.isBuilder())
      return;
    selecting = true;
    dragging = false;
    sx = sy = ex = ey = -1;
    scanCache.clear();
    // 别和建造菜单里当前选中的方块打架
    try {
      control.input.block = null;
      if (control.input instanceof DesktopInput di)
        di.mode = PlaceMode.none;
    } catch (Throwable ignored) {
    }
    ui.showInfoFade("[accent]超级组合炮台[]：拖动左键框选炮台（框选期间不能移动，框完自动恢复；右键/Esc 取消）");
  }

  public static void cancel() {
    if (selecting)
      ui.showInfoFade("[lightgray]已取消框选");
    selecting = false;
    dragging = false;
    boxPointer = -1;
    sx = sy = ex = ey = -1;
    scanCache.clear();
  }

  static void toTiles(int screenX, int screenY) {
    var w = Core.input.mouseWorld(screenX, screenY);
    ex = World.toTile(w.x);
    ey = World.toTile(w.y);
  }

  /** 框选完成：数炮台 → 生成虚影。炮台数量不够就留在框选模式。 */
  static void finish() {
    int x1 = Math.min(sx, ex), y1 = Math.min(sy, ey), x2 = Math.max(sx, ex), y2 = Math.max(sy, ey);
    Seq<Building> found = scan(player.team(), x1, y1, x2, y2);
    if (found.size < 2) {
      ui.showInfoFade("[scarlet]这个框里只有 @ 台炮台（至少要 2 台）", found.size);
      return;
    }

    int side = SuperTurret.sideFor(found.size);
    SuperTurret block = SuperTurret.blockForSide(side);
    if (block == null) {
      ui.showInfoFade("[scarlet]" + arc.util.Strings.format("超级组合炮台（@x@）还没装配好", side, side));
      return;
    }

    String layout = layoutOf(found, side * side);
    String sources = sourcesOf(found);
    Seq<Schematic.Stile> tiles = new Seq<>();
    // 【联机安全】config 会走原版的 ConstructFinish / TileConfig 包，客户端 readObjectSafe
    // 上限 1200 字符 —— 逐格文本 "名字@朝向" + sources 全写会超，必须走紧凑编码（见 encodeConfig）。
    tiles.add(new Schematic.Stile(block, 0, 0, SuperTurret.encodeConfig(layout, sources), (byte) 0));
    Schematic schem = new Schematic(tiles, new StringMap(), side, side);
    try {
      control.input.useSchematic(schem, false);
    } catch (Throwable t) {
      Log.err("[combine] 生成超级组合炮台虚影失败", t);
      return;
    }
    pending.add(new Pending(layout));
    if (pending.size > 8)
      pending.remove(0);
    selecting = false;
    dragging = false;
    ui.showInfoFade("[accent]" + arc.util.Strings.format(
        "@ 台炮台 → @x@ 超级组合炮台[]：左键确定位置，右键/Q/Esc 取消", found.size, side, side));
  }

  /** 把选中的炮台按行优先铺进格子里（多余格子留空）。 */
  public static String layoutOf(Seq<Building> found, int cellCount) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < cellCount; i++) {
      if (i > 0)
        sb.append(';');
      if (i >= found.size)
        continue;
      Building b = found.get(i);
      if (b == null || b.block == null)
        continue;
      Block cb = SuperTurret.cellBlock(b.block);
      if (cb == null)
        continue;
      float rot = b instanceof Turret.TurretBuild tb ? tb.rotation : 90f;
      sb.append(SuperTurret.encodeCell(cb, rot));
    }
    return sb.toString();
  }

  /** 被选中的炮台坐标（"x,y;x,y"）：超级炮台放下后要把它们拆掉。 */
  public static String sourcesOf(Seq<Building> found) {
    StringBuilder sb = new StringBuilder();
    for (Building b : found) {
      if (b == null || b.tile == null)
        continue;
      if (sb.length() > 0)
        sb.append(';');
      sb.append(b.tile.x).append(',').append(b.tile.y);
    }
    return sb.toString();
  }

  /**
   * 框选区域里的炮台（行优先：先上后下、先左后右，最多 {@link SuperTurret#MAX_SIDE}² 台）。
   *
   * @param team 只看这个队伍的炮台（null = 不看队伍）
   */
  public static Seq<Building> scan(@Nullable Team team, int x1, int y1, int x2, int y2) {
    Seq<Building> out = new Seq<>();
    if (world == null)
      return out;
    int max = SuperTurret.MAX_SIDE * SuperTurret.MAX_SIDE;
    for (int y = y2; y >= y1; y--) {
      for (int x = x1; x <= x2; x++) {
        Tile t = world.tile(x, y);
        if (t == null || t.build == null || t.build.dead())
          continue;
        Building b = t.build;
        if (team != null && b.team != team)
          continue;
        // 【一台多格炮台只算一格】4x4 这种炮台被它盖住的 16 个格子都会返回同一个 build，
        // 只认它自己的那一格（= b.tile），否则一台 4x4 就吃掉 16 格
        // （用户要求："一个 4*4 的炮台合体后只占一格"）。
        if (b.tile != null && b.tile != t)
          continue;
        if (!(b.block instanceof Turret))
          continue;
        if (SuperTurret.cellBlock(b.block) == null)
          continue;
        out.add(b);
        if (out.size >= max)
          return out;
      }
    }
    return out;
  }

  // ==================== 联机：本机补布局 ====================

  static void onTileChanged(Tile tile) {
    if (!selecting && pending.isEmpty())
      return;
    if (tile == null || tile.build == null || player == null)
      return;
    if (!(tile.build instanceof SuperTurret.SuperTurretBuild b))
      return;
    if (b.layout != null && !b.layout.isEmpty())
      return;
    Pending best = null;
    for (Pending p : pending) {
      best = p;
      break;
    }
    if (best == null)
      return;
    try {
      // 走紧凑编码：这一句会以 Call.tileConfig 发给服务端、再由服务端转发给所有客户端，
      // 客户端 readObjectSafe 上限 1200 —— 长布局直接踢客户端（用户 last_log 里的
      // TileConfigCallPacket "String too long: 1200"）。
      b.configure(SuperTurret.encodeConfig(best.layout, ""));
      pending.remove(best);
    } catch (Throwable t) {
      Log.err("[combine] 补超级组合炮台布局失败", t);
    }
  }

  static void flushPending() {
    if (pending.isEmpty())
      return;
    for (int i = pending.size - 1; i >= 0; i--) {
      Pending p = pending.get(i);
      p.frames += Time.delta;
      if (p.frames > 60f * 30f)
        pending.remove(i); // 30 秒还没落地就当没放成
    }
  }

  // ==================== 每帧 ====================

  static void update() {
    if (headless || Core.input == null)
      return;
    ensureProcessor();
    if (state == null || ui == null)
      return;
    ensureButton();
    flushPending();
    if (!state.isGame() || player == null) {
      if (selecting)
        cancel();
      return;
    }
    if (!selecting)
      return;
    // 框选期间玩家在菜单里点了别的方块（或又点了一次入口）→ 退出框选，把操作权交回菜单。
    // 【必须有】不然框选模式下点建造菜单会被输入处理器吃掉，看起来像"建筑列表点不动了"。
    if (control.input != null && control.input.block != null) {
      boolean sameEntry = control.input.block instanceof SuperTurret;
      cancel();
      if (sameEntry) {
        control.input.block = null; // 又点了一次入口 = 纯取消
      }
      // 点了别的方块：保留玩家的选择（框选已取消，他可以正常摆那个方块）
      return;
    }

    if (!player.isBuilder() || ui.chatfrag.shown() || Core.scene.hasKeyboard()) {
      cancel();
      return;
    }
    if (Core.input.keyTap(Binding.deselect) || Core.input.keyTap(Binding.clearBuilding)
        || (selectKey != null && Core.input.keyTap(selectKey))) {
      cancel();
      return;
    }

    // 鼠标已经被我们的 InputProcessor 接管（见 Processor），这里只补一次拖动中的缓存
    if (dragging && (ex != sx || ey != sy) && state.updateId - scanFrame > 8) {
      scanFrame = state.updateId;
      int x1 = Math.min(sx, ex), y1 = Math.min(sy, ey), x2 = Math.max(sx, ex), y2 = Math.max(sy, ey);
      scanCache.clear();
      scanCache.addAll(scan(player.team(), x1, y1, x2, y2));
    }
  }

  /**
   * HUD 上的入口按钮：**右侧靠上**一个炮台图标按钮，点它 = 进入框选模式（再点 = 取消）。
   *
   * <p>用独立的一张铺满屏幕的 Table 挂在 hudGroup 上（childrenOnly，不挡其它 UI），
   * 不依赖原版建造菜单的内部结构 —— 触摸端也有明确的可点入口（手机没有快捷键）。
   *
   * <p>位置：右侧、小地图正下方（用户要求"从左下角移到右边靠上"）。原来放在左下角，
   * 手机上正好压着"指挥"按钮那一带。
   */
  static void ensureButton() {
    // 按钮已经做进原版放置 UI（buildPlacementUI）时不再挂 HUD 悬浮按钮
    if (!useHudButton) {
      if (button != null && button.parent != null) {
        try {
          button.remove();
        } catch (Throwable ignored) {
        }
        button = null;
      }
      return;
    }
    if (button != null && button.parent != null) {
      // 【别被后加的 HUD 层盖住】MindustryX 之类的客户端会在 hudGroup 里再叠一层（实测是个
      // 铺满屏幕的 ScrollPane）—— 它排在按钮后面（z 更高），于是按钮**看得见却点不动**
      // （驱动实测：点在按钮中心上命中的是 ScrollPane）。这里每帧把按钮挪到 hudGroup 最上层。
      // 对话框挂在 scene root、不在 hudGroup 里，所以弹窗照样盖住它，不会跑到弹窗上面去。
      try {
        button.toFront();
      } catch (Throwable ignored) {
      }
      return;
    }
    if (buttonRetry-- > 0)
      return;
    buttonRetry = 30;
    try {
      if (ui == null || ui.hudGroup == null)
        return;
      arc.scene.ui.layout.Table root = new arc.scene.ui.layout.Table();
      root.name = "combineSuperTurretButton";
      root.setFillParent(true);
      root.touchable = arc.scene.event.Touchable.childrenOnly;
      root.align(arc.util.Align.topRight);
      // 顶部留白用一张空白格铺出来（Table 上没有 padXxx 这一类方法）：
      // 让按钮落在小地图下面一点，不盖小地图、也不进右边那条建造菜单。
      root.add().size(8f, BUTTON_TOP_PAD);
      root.row();
      root.button(mindustry.gen.Icon.turret, mindustry.ui.Styles.clearNonei, SuperTurretPlacer::toggle)
          .size(48f).padRight(8f)
          .tooltip("超级组合炮台：点一下框选炮台（默认快捷键 G，右键/Esc 取消）");
      // 【挂在 scene root（hudGroup 之上）而不是 hudGroup 里】：
      // 客户端（MindustryX 实测）会在 hudGroup 里再叠一层铺满屏幕的可点容器 / 方块信息面板，
      // 排在 hudGroup 的按钮之上 —— 挂在 hudGroup 里的按钮就会"看得见、点不动"
      // （驱动实测：点按钮中心命中的是那个 ScrollPane）。挂到 root 上就压过它们；
      // 对话框是后加到 root 的，仍然盖在这张表上面（弹窗时按钮照旧被挡住，语义不变）。
      if (Core.scene != null)
        Core.scene.root.addChild(root);
      else
        ui.hudGroup.addChild(root);
      button = root;
      Log.info("[combine] 超级组合炮台按钮已挂到 HUD 右上（小地图下方）");
    } catch (Throwable t) {
      Log.err("[combine] 超级组合炮台按钮挂载失败（还能用快捷键）", t);
    }
  }

  /** 我们的输入处理器必须是"最后一个"（= 最先收到事件），否则框选时左键会去开枪/开面板。 */
  static void ensureProcessor() {
    try {
      var list = Core.input.getInputProcessors();
      if (list == null)
        return;
      // 【必须排到最前（下标 0）】arc 的 InputMultiplexer 是**从下标 0 开始**派发的
      // （见 arc/input/InputMultiplexer.java：`for(int i = 0; i < n; i++)`）。
      // 原版的 InputHandler / GestureDetector 是在我们之后才 add 进去的，以前把我们的处理器
      // add 到最后 = **最后**才收到事件：手机上玩家一拖，MobileInput.touchDragged 先把事件
      // 拿去移动单位了，我们的框选根本轮不到（用户报的"点击后要跟点了 copy 一样，
      // 让框选优先级高于玩家单位移动"）。
      // 放到下标 0 才是最先收到；框选期间我们 return true 把拖动吃掉，单位就不会走。
      if (!list.isEmpty() && list.first() == processor)
        return;
      list.remove(processor, true);
      list.insert(0, processor);
      } catch (Throwable ignored) {
      }
  }

  /**
   * 是否还要在 HUD 上挂那个悬浮按钮。把按钮做进原版放置 UI 之后（见
   * {@link combine.Main#installInputHandler()}）就关掉，免得同一件事有两个入口。
   * 输入处理器换不掉时（别的模组也换了）保持 true，HUD 按钮就当兜底。
   */
  public static boolean useHudButton = true;

  static class Processor implements InputProcessor {
    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, KeyCode button) {
      if (!selecting)
        return false;
      // 点在界面（建造菜单/按钮/面板）上时放行：框选模式下也得能点建造列表
      if (overUi(screenX, screenY))
        return false;
      // 【用户的要求】框选期间玩家不能动（和原版"重建/复制框内建筑"那种模式一样，
      // 框完才恢复移动）—— 所以这里连**第二根手指**也吃掉，别让它漏给移动摇杆；
      // 但也别让它抢走这个框（只有起手那根手指能驱动）。
      if (dragging && pointer != boxPointer)
        return true;
      if (button == KeyCode.mouseRight || button == KeyCode.mouseMiddle) {
        cancel();
        return true;
      }
      if (button != KeyCode.mouseLeft)
        return true;
      dragging = true;
      boxPointer = pointer;
      var w = Core.input.mouseWorld(screenX, screenY);
      sx = ex = World.toTile(w.x);
      sy = ey = World.toTile(w.y);
      scanCache.clear();
      return true;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, KeyCode button) {
      if (!selecting)
        return false;
      if (overUi(screenX, screenY))
        return false;
      if (dragging && pointer != boxPointer)
        return true; // 别的手指抬起：吃掉，但不动这个框
      if (button != KeyCode.mouseLeft)
        return selecting;
      if (dragging) {
        toTiles(screenX, screenY);
        dragging = false;
        boxPointer = -1;
        finish();
        return true;
      }
      return !overUi(screenX, screenY);
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
      if (!selecting)
        return false;
      if (pointer != boxPointer)
        return true; // 别的手指：吃掉（别漏给移动摇杆），但不驱动这个框
      if (overUi(screenX, screenY) && !dragging)
        return false;
      toTiles(screenX, screenY);
      return true;
    }

    /** 指针下面有没有 UI 元素（有就让给界面处理）。 */
    static boolean overUi(int screenX, int screenY) {
      try {
        return Core.scene != null && Core.scene.hasMouse(screenX, screenY);
      } catch (Throwable t) {
        return false;
      }
    }

    @Override
    public boolean keyDown(KeyCode keycode) {
      if (!selecting)
        return false;
      if (keycode == KeyCode.escape || keycode == KeyCode.q
          || (selectKey != null && selectKey.value != null
              && keycode == selectKey.value.key)) {
        cancel();
        return true;
      }
      return false;
    }
  }

  // ==================== 绘制框 ====================

  static void draw() {
    if (headless || !selecting || sx < 0 || ex < 0)
      return;
    try {
      int x1 = Math.min(sx, ex), y1 = Math.min(sy, ey), x2 = Math.max(sx, ex), y2 = Math.max(sy, ey);
      float fx = x1 * tilesize - tilesize / 2f, fy = y1 * tilesize - tilesize / 2f;
      float fw = (x2 - x1 + 1) * tilesize, fh = (y2 - y1 + 1) * tilesize;
      String text;
      int count = scanCache.size;
      if (count <= 0) {
        text = "框选炮台";
      } else if (count < 2) {
        text = count + " 台炮台（至少要 2 台）";
      } else {
        int side = SuperTurret.sideFor(count);
        text = count + " 台炮台 → " + side + "x" + side;
      }

      Draw.draw(Layer.overlayUI, () -> {
        Draw.color(Pal.accent, 0.22f);
        // 【坐标系】arc 里 Fill.rect(x,y,w,h) 是"中心在 (x,y)"，而 Lines.rect / Fill.crect
        // 是"左下角在 (x,y)"——两套混用就差半个框（用户两次报的错位都是这个）。
        // 以前一直用 Fill.rect 画填充、Lines.rect 画边框，所以必然错位。这里填充改用 crect，
        // 和边框同一套"左下角"口径；边框照原版重建框/蓝图框的写法（drawSelection）：两层描边。
        Fill.crect(fx, fy, fw, fh);
        Lines.stroke(2f);
        Draw.color(Pal.accentBack);
        Lines.rect(fx, fy - 1f, fw, fh);
        Draw.color(Pal.accent);
        Lines.rect(fx, fy, fw, fh);
        // 已经被算进去的炮台
        Draw.color(Pal.accent, 0.5f);
        for (Building b : scanCache) {
          if (b == null || !b.isValid() || b.block == null)
            continue;
          float s = b.block.size * tilesize;
          Fill.crect(b.x - s / 2f, b.y - s / 2f, s, s); // 同样是"左下角"口径 = 正好套住这一台
        }
        Draw.reset();

        var font = Fonts.outline;
        boolean ints = font.usesIntegerPositions();
        float z = Draw.z();
        font.setUseIntegerPositions(false);
        Draw.z(Layer.endPixeled);
        font.getData().setScale(1f / renderer.camerascale);
        font.setColor(Color.white);
        font.draw(text, Core.input.mouseWorldX() + 10f, Core.input.mouseWorldY() - 10f);
        font.getData().setScale(1f);
        font.setUseIntegerPositions(ints);
        Draw.z(z);
        Draw.reset();
      });
    } catch (Throwable ignored) {
    }
  }
}
