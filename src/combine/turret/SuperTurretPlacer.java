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
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Scl;
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

import static mindustry.Vars.*;

/**
 * 超级组合炮台的选择/放置流程（纯客户端）。
 *
 * <p>交互：点屏幕上的"框选合体"悬浮按钮 → 进入框选模式
 * （这时左键只用来画框，不会开枪/开面板/摆方块）→ 拖一个框松开 → 数出框里的炮台、
 * 按数量生成对应边长的超级组合炮台虚影跟着鼠标走 → 左键确定、右键/Q/Esc 取消。
 * 再点一下按钮（或右键/Esc）可以在框选阶段取消。
 * （用户 2026-09-28 要求删掉默认 G 键位、入口改成悬浮按钮、并且不要再改 input。）
 *
 * <p>放置走原版蓝图那套（{@code input.useSchematic}），所以虚影、合法性染色、
 * 建造队列/多线程建造、联机同步全都沿用原版。
 */
public class SuperTurretPlacer {
  /** 【已停用】不再注册任何键位（用户要求删掉 G）；恒为 null，入口只用按钮。 */
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
  /** HUD 上的悬浮入口按钮（用户要求：手机/桌面都用悬浮式按钮触发框选，别再改 input）。 */
  static @Nullable Element button;
  /** 点开后出现的两个子按钮：炮台合体 + 工厂合体。 */
  static @Nullable Element turretBtn, factoryBtn;
  static boolean subButtonsShown;
  /** 悬浮按钮边长（像素，乘 Scl）。 */
  static final float BUTTON_SIDE = 48f;
  /** 默认落点距屏幕右边缘的留白（像素，乘 Scl）。 */
  static final float BUTTON_EDGE_PAD = 8f;
  /** 默认落点距屏幕顶部的留白（像素，乘 Scl）：贴右上角会盖住小地图，往下让一点落在它正下方。 */
  static final float BUTTON_TOP_PAD = 225f;
  /** 拖动判定阈值（像素，乘 Scl）：位移超过这么多才算"拖动"，否则算"点一下"。 */
  static final float DRAG_THRESHOLD = 6f;
  /** 悬浮按钮位置记在本机设置里的键（按屏幕比例存，换设备/换分辨率不会跑到屏幕外）。 */
  static final String ICON_POS_X = "combine-turret-button-nx";
  static final String ICON_POS_Y = "combine-turret-button-ny";
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
    // 【不再注册键位】用户 2026-09-28：G 和其它功能冲突，要求删掉。
    // 入口只保留屏幕上的"框选合体"悬浮按钮（手机/桌面同一个，见 ensureButton）；
    // 取消框选还能用右键 / Esc（触摸端点按钮第二下 = 取消）。selectKey 恒为 null，
    // 下面所有 `selectKey != null && ...` 的判定自然失效。
    selectKey = null;
    Events.run(Trigger.update, SuperTurretPlacer::update);
    Events.run(Trigger.draw, SuperTurretPlacer::draw);
    Events.on(WorldLoadEvent.class, e -> {
      selecting = false;
      dragging = false;
      sx = sy = ex = ey = -1;
      pending.clear();
      button = null; // 场景重建了，下一帧补挂
      hideSubButtons();
      buttonRetry = 0;
      SuperTurret.clearReplaceAllowed();
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
    onMainTap();
  }

  /**
   * 主按钮点按（非拖动）。
   *
   * <p>【用户要求 2026-10-04】点浮标 = 在它旁边展开两个子按钮（炮台合体 / 工厂合体），
   * 浮标在屏幕左半边就把子按钮摆到右边、在右半边就摆到左边（见 {@link #positionSubButtons}）；
   * 再点一次收起。框选进行中时点它 = 取消框选（手机没有右键/Esc，这是触摸端的退出出口）。
   */
  public static void onMainTap() {
    if (selecting) {
      cancel();
      hideSubButtons();
      return;
    }
    if (combine.production.FactoryCombiner.selecting()) {
      combine.production.FactoryCombiner.cancel();
      hideSubButtons();
      return;
    }
    toggleSubButtons();
  }

  /** 主按钮点按（非拖动）：显示/隐藏两个子按钮。 */
  public static void toggleSubButtons() {
    if (subButtonsShown) {
      hideSubButtons();
    } else {
      showSubButtons();
    }
  }

  /** 炮台合体子按钮被点：启动炮台框选。 */
  static void onTurretBtn() {
    hideSubButtons();
    start();
  }

  /** 工厂合体子按钮被点：启动工厂框选。 */
  static void onFactoryBtn() {
    hideSubButtons();
    combine.production.FactoryCombiner.start();
  }

  public static void start() {
    if (headless || state == null || !state.isGame() || player == null || !player.isBuilder())
      return;
    // 两个框选流程互斥：已经开始框工厂了就别同时框炮台（两个处理器会互相抢事件）
    try {
      combine.production.FactoryCombiner.cancel();
    } catch (Throwable ignored) {
    }
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
    // 【加炮台】框里可以有现成的合体炮台：它的每一格的炮台都算数（等于把那台拆开重拼，
    // 也就是"往合体炮台里加新炮台"），所以数的是**格子数**不是建筑数。
    int cellCount = cellCount(found);
    if (cellCount < 2) {
      ui.showInfoFade("[scarlet]这个框里只有 @ 台炮台（至少要 2 台）", cellCount);
      return;
    }
    int absorbed = 0;
    for (Building b : found)
      if (b instanceof SuperTurret.SuperTurretBuild)
        absorbed++;

    int side = SuperTurret.sideFor(cellCount);
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
    // 【只许顶被框住的炮台】把这次框选里那些炮台占的格子登记给放置判据
    //（SuperTurret.canPlaceOn 逐格检查），于是预览盖在它们上面是绿的、盖在**没框住**的炮台上是红的。
    SuperTurret.allowReplaceOver(framedTiles(found));
    selecting = false;
    dragging = false;
    ui.showInfoFade(absorbed > 0
        ? arc.util.Strings.format("[accent]@ 格炮台（含 @ 台现成合体）→ @x@ 超级组合炮台[]："
            + "左键确定位置，右键/Q/Esc 取消", cellCount, absorbed, side, side)
        : arc.util.Strings.format(
            "@ 台炮台 → @x@ 超级组合炮台[]：左键确定位置，右键/Q/Esc 取消", cellCount, side, side));
  }

  /** 选中的这些建筑一共能出多少格炮台（合体炮台按它的格子数算）。 */
  public static int cellCount(Seq<Building> found) {
    int n = 0;
    for (Building b : found) {
      if (b instanceof SuperTurret.SuperTurretBuild st)
        n += st.cellBlocks == null ? 0 : st.cellBlocks.length;
      else if (b != null && b.block != null && SuperTurret.cellBlock(b.block) != null)
        n++;
    }
    return n;
  }

  /** 这次框选里那些炮台占的**所有格子**（pos）：只有这些格子允许被超级炮台顶掉。 */
  public static arc.struct.IntSet framedTiles(Seq<Building> found) {
    arc.struct.IntSet out = new arc.struct.IntSet();
    for (Building b : found) {
      if (b == null || b.tile == null || b.block == null)
        continue;
      int size = Math.max(b.block.size, 1);
      for (int i = 0; i < size; i++)
        for (int j = 0; j < size; j++) {
          Tile t = world.tile(SuperTurret.cellTile(b.tile.x, size, i),
              SuperTurret.cellTile(b.tile.y, size, j));
          if (t != null)
            out.add(t.pos());
        }
    }
    return out;
  }

  /** 把选中的炮台按行优先铺进格子里（多余格子留空）；合体炮台直接把它每一格的炮台铺开。 */
  public static String layoutOf(Seq<Building> found, int cellCount) {
    StringBuilder sb = new StringBuilder();
    int written = 0;
    for (Building b : found) {
      if (b == null || b.block == null)
        continue;
      if (b instanceof SuperTurret.SuperTurretBuild st) {
        // 现成的合体炮台：把它每一格的炮台按原朝向铺开
        for (int i = 0; i < st.cellBlocks.length; i++) {
          Block cb = st.cellBlocks[i];
          if (cb == null)
            continue;
          if (written > 0)
            sb.append(';');
          written++;
          sb.append(SuperTurret.encodeCell(cb, i < st.cellRot.length ? st.cellRot[i] : 90f));
        }
        continue;
      }
      Block cb = SuperTurret.cellBlock(b.block);
      if (cb == null)
        continue;
      // 点防炮的 build 是 BaseTurret.BaseTurretBuild（不是 TurretBuild），rotation 字段同样有
      float rot = b instanceof mindustry.world.blocks.defense.turrets.BaseTurret.BaseTurretBuild tb
          ? tb.rotation : 90f;
      if (written > 0)
        sb.append(';');
      written++;
      sb.append(SuperTurret.encodeCell(cb, rot));
    }
    // 补齐到 cellCount 个槽位（多余格子留空）
    for (int i = written; i < cellCount; i++) {
      sb.append(';');
    }
    return sb.toString();
  }

  /**
   * 被选中的炮台坐标（"x,y;x,y"）：超级炮台放下后要把它们拆掉。
   *
   * <p>现成的合体炮台用 {@code !x,y} 标记 —— 落地时由 {@code consumeSources} 把整台并进来
   * （连它的库存一起），而不是当成"一格炮台"处理。
   */
  public static String sourcesOf(Seq<Building> found) {
    StringBuilder sb = new StringBuilder();
    for (Building b : found) {
      if (b == null || b.tile == null)
        continue;
      if (b instanceof SuperTurret.SuperTurretBuild st) {
        // 现成的合体炮台：它贡献几格就写几项（每一项都是同一台，落地时第一次就把整台并进来，
        // 后面的重复项看到那台已经没了会自动跳过）。这样 sources 和 layout 的格子一一对齐。
        for (int i = 0; i < st.cellBlocks.length; i++) {
          if (st.cellBlocks[i] == null)
            continue;
          if (sb.length() > 0)
            sb.append(';');
          sb.append('!').append(b.tile.x).append(',').append(b.tile.y);
        }
        continue;
      }
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
        // 【加炮台】现成的合体炮台也认：把它框进去 = 连它里面那些炮台一起重新拼
        //（想往一台合体里加炮台，就把那台 + 要加的炮台一起框上，再点一下放下去）。
        if (b instanceof SuperTurret.SuperTurretBuild) {
          out.add(b);
          if (out.size >= max)
            return out;
          continue;
        }
        // 框选范围 = Turret（含组合炮塔）+ 点防炮（PointDefenseTurret）+ 牵引光束炮（TractorBeamTurret），
        // 见 SuperTurret.cellCapable
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
    if (pending.isEmpty()) {
      SuperTurret.clearReplaceAllowed(); // 预览已落地/超时 → "可顶格子"名单作废
      return;
    }
    for (int i = pending.size - 1; i >= 0; i--) {
      Pending p = pending.get(i);
      p.frames += Time.delta;
      if (p.frames > 60f * 30f)
        pending.remove(i); // 30 秒还没落地就当没放成
    }
    if (pending.isEmpty())
      SuperTurret.clearReplaceAllowed();
  }

  // ==================== 每帧 ====================

  static void update() {
    if (headless || Core.input == null)
      return;
    ensureProcessor();
    if (state == null || ui == null)
      return;
    // 悬浮按钮只在游戏里出现（回主菜单就收掉，别糊在菜单上挡点击）
    ensureButton(state.isGame());
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
   * 悬浮在屏幕上的入口按钮（手机/桌面同一个）：一个图标浮标，**点它 = 在旁边展开两个子按钮**
   * （炮台合体 / 工厂合体，见 {@link #showSubButtons}），再点一次收起；框选进行中时点它 = 取消。
   * 按住还能把它拖到任意位置（位置记进本机设置），子按钮会跟着换边。
   *
   * <p>【用户要求 2026-09-28】不要再改 input / {@code buildPlacementUI}，入口就用这个浮标：
   * 直接把自己挂到 {@code Core.scene.root}（在 hudGroup 之上），不依赖原版建造菜单的内部结构，
   * 触摸端 / 桌面都有明确的可点入口（手机没有快捷键）。
   *
   * <p>【为什么挂 scene root 而不是 hudGroup】客户端（MindustryX 实测）会在 hudGroup 里再叠一层
   * 铺满屏幕的可点容器 / 方块信息面板，排在 hudGroup 的按钮之上 —— 挂在 hudGroup 里的按钮会
   * "看得见、点不动"（点在按钮中心上命中的是那层 ScrollPane）。挂到 root 上就压过它们；
   * 对话框是后加到 root 的，仍然盖在这个浮标上面（弹窗时照旧被挡住，语义不变）。
   */
  static void ensureButton(boolean show) {
    if (Core.scene == null)
      return;
    if (!show) {
      hideSubButtons(); // 子按钮跟着主按钮一起收掉
      // 只在游戏里出现：回到主菜单就把浮标收掉，别糊在菜单上挡点击
      if (button != null) {
        try {
          button.remove();
        } catch (Throwable ignored) {
        }
        button = null;
      }
      return;
    }
    // 防重复：WorldLoadEvent 会把引用清掉，但老浮标可能还挂在场景里 —— 按名字先收掉残留的那份
    if (button == null && Core.scene.root != null) {
      Element stale = Core.scene.root.find("combineSuperTurretButton");
      if (stale != null) {
        try {
          stale.remove();
        } catch (Throwable ignored) {
        }
      }
    }
    if (button != null && button.parent != null) {
      // 每帧置顶（原因见上面）+ 收边，别被后加的 HUD 层盖住、也别被拖/缩到屏幕外
      try {
        button.toFront();
      } catch (Throwable ignored) {
      }
      clampButton(button);
      return;
    }
    if (buttonRetry-- > 0)
      return;
    buttonRetry = 30;
    try {
      if (ui == null || Core.scene.root == null)
        return;
      float size = Scl.scl(BUTTON_SIDE);
      ImageButton b = new ImageButton(mindustry.gen.Icon.add, mindustry.ui.Styles.clearTogglei);
      b.name = "combineSuperTurretButton";
      b.setSize(size, size);
      b.resizeImage(size * 0.6f);
      b.setOrigin(arc.util.Align.center);
      // 和"复制"键一样是**切换**键：正在框选就高亮，点第二下 = 取消
      b.update(() -> b.setChecked(selecting()));
      // 点按 = 切换框选；拖动 = 挪浮标（超过阈值才算拖动，见 DragTap）
      b.addListener(new DragTap());
      b.addListener(Tooltip.Tooltips.getInstance().create(
          "组合工厂：点一下展开炮台/工厂合体，按住可拖动", false));
      Core.scene.root.addChild(b);
      button = b;
      applyButtonPos(b);
      Log.info("[combine] 超级组合炮台悬浮按钮已挂到 HUD（可拖动）");
    } catch (Throwable t) {
      Log.err("[combine] 超级组合炮台悬浮按钮挂载失败（还能用框选相关的其它操作）", t);
    }
  }

  /** 浮标落点：上次拖到的位置（按屏幕比例存），没有就默认右上角、小地图正下方。 */
  static void applyButtonPos(Element e) {
    Element p = e.parent;
    float w = p == null ? Core.graphics.getWidth() : p.getWidth();
    float h = p == null ? Core.graphics.getHeight() : p.getHeight();
    float nx = Core.settings == null ? Float.NaN : Core.settings.getFloat(ICON_POS_X, Float.NaN);
    float ny = Core.settings == null ? Float.NaN : Core.settings.getFloat(ICON_POS_Y, Float.NaN);
    if (!Float.isNaN(nx) && !Float.isNaN(ny)) {
      // 存的是屏幕比例（0-1）：换设备 / 换分辨率也不会跑到屏幕外
      e.setPosition(nx * w, ny * h);
    } else {
      // 默认：右上角、小地图正下方（用户要求"从左下角移到右边靠上"）
      e.setPosition(w - Scl.scl(BUTTON_EDGE_PAD) - e.getWidth(), h - Scl.scl(BUTTON_TOP_PAD) - e.getHeight());
    }
    clampButton(e);
  }

  /** 把浮标收进屏幕内（拖动 / 分辨率变化时用）。 */
  static void clampButton(Element e) {
    Element p = e.parent;
    if (p == null)
      return;
    e.setPosition(
        Mathf.clamp(e.x, 0f, Math.max(0f, p.getWidth() - e.getWidth())),
        Mathf.clamp(e.y, 0f, Math.max(0f, p.getHeight() - e.getHeight())));
  }

  /** 把浮标位置按屏幕比例记进本机设置（拖动结束时调）。 */
  static void saveButtonPos(Element e) {
    Element p = e.parent;
    if (p == null || Core.settings == null)
      return;
    try {
      Core.settings.putFloat(ICON_POS_X, p.getWidth() > 0f ? e.x / p.getWidth() : 0f);
      Core.settings.putFloat(ICON_POS_Y, p.getHeight() > 0f ? e.y / p.getHeight() : 0f);
    } catch (Throwable ignored) {
    }
  }


  // ==================== 子按钮 ====================

  static void showSubButtons() {
    if (button == null || Core.scene == null || Core.scene.root == null) return;
    hideSubButtons();
    float subSize = Scl.scl(40f);

    ImageButton tb = new ImageButton(mindustry.gen.Icon.turret, mindustry.ui.Styles.clearTogglei);
    tb.name = "combineSubTurretBtn";
    tb.setSize(subSize, subSize);
    tb.resizeImage(subSize * 0.55f);
    tb.addListener(Tooltip.Tooltips.getInstance().create("框选炮台合体", false));
    tb.clicked(SuperTurretPlacer::onTurretBtn);
    Core.scene.root.addChild(tb);
    turretBtn = tb;

    ImageButton fb = new ImageButton(mindustry.gen.Icon.production, mindustry.ui.Styles.clearTogglei);
    fb.name = "combineSubFactoryBtn";
    fb.setSize(subSize, subSize);
    fb.resizeImage(subSize * 0.55f);
    fb.addListener(Tooltip.Tooltips.getInstance().create("框选工厂合体", false));
    fb.clicked(SuperTurretPlacer::onFactoryBtn);
    Core.scene.root.addChild(fb);
    factoryBtn = fb;

    subButtonsShown = true;
    positionSubButtons();
  }

  static void hideSubButtons() {
    if (turretBtn != null) { try { turretBtn.remove(); } catch (Throwable ignored) {} turretBtn = null; }
    if (factoryBtn != null) { try { factoryBtn.remove(); } catch (Throwable ignored) {} factoryBtn = null; }
    subButtonsShown = false;
  }

  /**
   * 按主按钮位置把两个子按钮摆到它旁边：**主按钮在屏幕左半边 → 子按钮在它右边；
   * 在右半边 → 在它左边**（用户 2026-10-04 要求）。
   *
   * <p>两个子按钮在那一侧**竖排成一列、整体与主按钮垂直居中**，彼此留 {@code gap} 的缝 ——
   * 以前是 "mainY+mainH-subH / mainY" 两条，40px 高的按钮只错开 8px，看着就是"一个按钮"。
   */
  static void positionSubButtons() {
    if (button == null || turretBtn == null || factoryBtn == null) return;
    Element p = button.parent;
    if (p == null) return;
    float mainX = button.x, mainY = button.y;
    float mainW = button.getWidth(), mainH = button.getHeight();
    float subW = turretBtn.getWidth(), subH = turretBtn.getHeight();
    float gap = Scl.scl(4f);
    boolean onLeft = mainX + mainW / 2f < p.getWidth() / 2f;
    float x = onLeft ? mainX + mainW + gap : mainX - subW - gap;
    // 竖排一列、整体垂直居中于主按钮（上 = 炮台合体，下 = 工厂合体）
    float bottom = mainY + mainH / 2f - (subH * 2f + gap) / 2f;
    factoryBtn.setPosition(x, bottom);
    turretBtn.setPosition(x, bottom + subH + gap);
  }
  /**
   * 浮标上的点按 / 拖动判定：位移超过 {@link #DRAG_THRESHOLD} 才算拖动（挪浮标 + 存位置），
   * 否则算点按（切换框选）。用 {@code event.stageX/Y} 的位移而不是元素的局部坐标 ——
   * 元素会跟着手指走，局部坐标每帧都在变，拿来算位移是不对的。
   */
  static class DragTap extends InputListener {
    float downX, downY, startX, startY;
    boolean moved;

    @Override
    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
      if (button != KeyCode.mouseLeft && button != KeyCode.mouseRight && button != KeyCode.mouseMiddle)
        return false;
      downX = event.stageX;
      downY = event.stageY;
      startX = event.listenerActor.x;
      startY = event.listenerActor.y;
      moved = false;
      return true;
    }

    @Override
    public void touchDragged(InputEvent event, float x, float y, int pointer) {
      float dx = event.stageX - downX, dy = event.stageY - downY;
      if (!moved && Math.abs(dx) + Math.abs(dy) < Scl.scl(DRAG_THRESHOLD))
        return;
      moved = true;
      event.listenerActor.setPosition(startX + dx, startY + dy);
      clampButton(event.listenerActor);
    }

    @Override
    public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
      if (!moved) {
        onMainTap();
      } else {
        saveButtonPos(event.listenerActor);
            if (subButtonsShown) positionSubButtons();
      }
      moved = false;
    }
  }

  /** 我们的输入处理器必须是"最后一个"（= 最先收到事件），否则框选时左键会去开枪/开面板。 */
  static void ensureProcessor() {
    // 子按钮开着时每帧跟着主按钮位置更新布局
    if (subButtonsShown && state != null && state.isGame())
      positionSubButtons();
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
