package combine.production;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.input.InputProcessor;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.struct.IntSet;
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
 * 「工厂合体」的选择 / 放置流程（与 {@code SuperTurretPlacer} 平行，纯客户端交互）。
 *
 * <p>入口：HUD 上那个浮标 → 展开的"工厂合体"子按钮（见 {@code SuperTurretPlacer.showSubButtons}）。
 * 点它 → 拖动左键框选工厂 → 松开 → 生成一台 {@link SuperCombineFactory}（N 台 → ceil(sqrt(N)) 见方）的虚影跟着鼠标走 →
 * 左键确定、右键 / Esc 取消。把**已经有的一台组合工厂**一起框进去就是"往里加工厂"。
 *
 * <p>放置走原版蓝图那套（{@code input.useSchematic}），所以虚影、合法性染色、建造队列 /
 * 多线程建造、联机同步全都沿用原版。
 */
public class FactoryCombiner {
  static boolean selecting = false;
  static boolean dragging = false;
  static int sx = -1, sy = -1, ex = -1, ey = -1;
  /** 正在拖这个框的手指：多点触控时只认起手的那一根。 */
  static int boxPointer = -1;
  /** 框选期间缓存的"框里的工厂"（拖动时每几帧重算一次，画高亮和数字用）。 */
  static final Seq<Building> scanCache = new Seq<>();
  static long scanFrame = -1000L;
  /** 联机时客户端自己那份建筑拿不到蓝图里的 config，这里按"最近这次框选"补一次。 */
  static final Seq<Pending> pending = new Seq<>();
  /** 预览虚影刚被"点下去"（{@code SuperCombineFactory.onNewPlan} 置位）：下一帧收掉虚影。 */
  static boolean ordered = false;
  static final InputProcessor processor = new Processor();

  /** 玩家把预览点下去了（{@code SuperCombineFactory.onNewPlan} 里调）：下一帧收虚影，别再重画暗底。 */
  static void markOrdered() {
    ordered = true;
  }

  static class Pending {
    final String config;
    float frames = 0f;

    Pending(String config) {
      this.config = config;
    }
  }

  public static boolean selecting() {
    return selecting;
  }

  public static void register() {
    if (headless)
      return;
    Events.run(Trigger.update, FactoryCombiner::update);
    Events.run(Trigger.draw, FactoryCombiner::drawOverlay);
    Events.on(WorldLoadEvent.class, e -> {
      selecting = false;
      dragging = false;
      sx = sy = ex = ey = -1;
      pending.clear();
      ordered = false;
      SuperCombineFactory.clearReplaceAllowed();
    });
    Events.on(mindustry.game.EventType.TileChangeEvent.class, e -> {
      try {
        onTileChanged(e.tile);
      } catch (Throwable ignored) {
      }
    });
  }

  public static void start() {
    if (headless || state == null || !state.isGame() || player == null || !player.isBuilder())
      return;
    // 两个框选流程互斥：已经开始框炮台了就别同时框工厂（两个处理器会互相抢事件）
    try {
      if (combine.turret.SuperTurretPlacer.selecting())
        combine.turret.SuperTurretPlacer.cancel();
    } catch (Throwable ignored) {
    }
    selecting = true;
    dragging = false;
    sx = sy = ex = ey = -1;
    boxPointer = -1;
    scanCache.clear();
    try {
      control.input.block = null;
      if (control.input instanceof DesktopInput di)
        di.mode = PlaceMode.none;
    } catch (Throwable ignored) {
    }
    ensureProcessor();
    ui.showInfoFade("[accent]工厂合体[]：拖动左键框选工厂（至少要 2 台；右键/Esc 取消）");
  }

  public static void cancel() {
    if (selecting)
      ui.showInfoFade("[lightgray]已取消工厂框选");
    selecting = false;
    dragging = false;
    boxPointer = -1;
    sx = sy = ex = ey = -1;
    scanCache.clear();
    // 【别在这里清"可顶名单"】那是**虚影**的东西：cancel() 只是退出框选，
    // 而 update() 在玩家点建造菜单/聊天等情况下每帧都会调它 —— 一清，后面点下去的
    // 虚影就因为"这格不是被框住的工厂"被判非法（实测：框选完点下去什么也没发生）。
    // 虚影结束时由 flushPending() 负责清。
  }

  static void toTiles(int screenX, int screenY) {
    var w = Core.input.mouseWorld(screenX, screenY);
    ex = World.toTile(w.x);
    ey = World.toTile(w.y);
  }

  /**
   * 框选完成（用户要求 2026-10-04：**和框选蓝图一样的机制** —— 松手就立刻判断）：
   * 框到 ≥2 台工厂 → 生成合体虚影；没框到 / 不够 2 台 → 直接退出框选模式（不再"卡在框选里"）。
   */
  static void finish() {
    int x1 = Math.min(sx, ex), y1 = Math.min(sy, ey), x2 = Math.max(sx, ex), y2 = Math.max(sy, ey);
    Seq<Building> found = scan(player.team(), x1, y1, x2, y2);
    int count = count(found);
    if (count < 2) {
      ui.showInfoFade("[lightgray]框里只有 @ 台工厂（至少 2 台），已退出框选", count);
      cancel();
      return;
    }
    int absorbed = 0;
    for (Building b : found)
      if (b instanceof SuperCombineFactory.SuperCombineFactoryBuild)
        absorbed++;

    // 台数 → 边长（N 台 → ceil(sqrt(N)) × ceil(sqrt(N))，每格一台工厂）
    int side = SuperCombineFactory.sideFor(count);
    SuperCombineFactory block = SuperCombineFactory.blockForSide(side);
    if (block == null) {
      ui.showInfoFade("[scarlet]组合工厂（" + side + "x" + side + "）还没装配好");
      return;
    }

    String layout = layoutOf(found, side * side);
    String sources = sourcesOf(found);
    String[] carry = carryOf(found);
    // 【联机安全】config 走原版的 ConstructFinish / TileConfig 包，客户端 readObjectSafe 上限
    // 1200 字符 —— 逐格文本 + sources 全写会超，必须走紧凑编码（见 SuperCombineFactory.encodeConfig）。
    String config = SuperCombineFactory.encodeConfig(layout, sources, carry);
    Seq<Schematic.Stile> tiles = new Seq<>();
    tiles.add(new Schematic.Stile(block, 0, 0, config, (byte) 0));
    Schematic schem = new Schematic(tiles, new StringMap(), side, side);
    try {
      control.input.useSchematic(schem, false);
    } catch (Throwable t) {
      Log.err("[combine] 生成组合工厂虚影失败", t);
      return;
    }
    pending.add(new Pending(config));
    if (pending.size > 8)
      pending.remove(0);
    // 【只许顶被框住的工厂】把这次框选里那些工厂占的格子登记给放置判据
    // （SuperCombineFactory.canPlaceOn 逐格检查），于是预览盖在它们上面是绿的、
    // 盖在**没框住**的工厂上是红的。
    SuperCombineFactory.allowReplaceOver(framedTiles(found));
    selecting = false;
    dragging = false;
    ui.showInfoFade(absorbed > 0
        ? "[accent]" + count + " 台工厂（含 " + absorbed + " 台现成组合工厂）→ " + side + "x" + side
            + " 组合工厂[]：左键确定位置，右键/Esc 取消"
        : "[accent]" + count + " 台工厂 → " + side + "x" + side
            + " 组合工厂[]：左键确定位置，右键/Esc 取消");
  }

  /** 选中的这些建筑一共能出多少台工厂（现成的组合工厂按它里面的台数算）。 */
  public static int count(Seq<Building> found) {
    int n = 0;
    for (Building b : found) {
      if (b instanceof SuperCombineFactory.SuperCombineFactoryBuild cf)
        n += cf.filledCount(); // 空槽不算（cells 数组是按边长² 开的）
      else if (b != null && b.block != null && SuperCombineFactory.cellBlock(b.block) != null)
        n++;
    }
    return n;
  }

  /** 这次框选里那些工厂占的**所有格子**（pos）：只有这些格子允许被组合工厂顶掉。 */
  public static IntSet framedTiles(Seq<Building> found) {
    IntSet out = new IntSet();
    for (Building b : found) {
      if (b == null || b.tile == null || b.block == null)
        continue;
      int size = Math.max(b.block.size, 1);
      for (int i = 0; i < size; i++)
        for (int j = 0; j < size; j++) {
          Tile t = world.tile(SuperCombineFactory.cellTile(b.tile.x, size, i),
              SuperCombineFactory.cellTile(b.tile.y, size, j));
          if (t != null)
            out.add(t.pos());
        }
    }
    return out;
  }

  /** 把选中的工厂按行优先铺进布局（超出上限的丢掉）。 */
  public static String layoutOf(Seq<Building> found) {
    return layoutOf(found, 0);
  }

  /**
   * 把选中的工厂按行优先铺进布局。
   *
   * @param slots 目标格子数（= 边长²）：不够的槽位补空串，保证"第 i 台落在棋盘的第 i 格"
   *              （和 {@link SuperCombineFactory.SuperCombineFactoryBuild#buildCells} 的
   *              行优先摆放一一对应）。传 0 = 不补齐。
   */
  public static String layoutOf(Seq<Building> found, int slots) {
    StringBuilder sb = new StringBuilder();
    int written = 0;
    for (Building b : found) {
      if (b == null || b.block == null)
        continue;
      if (b instanceof SuperCombineFactory.SuperCombineFactoryBuild cf) {
        // 现成的组合工厂：把它每一格的工厂原样铺开（= 往里加工厂）
        for (int i = 0; i < cf.cellBlocks.length; i++) {
          Block cb = cf.cellBlocks[i];
          if (cb == null)
            continue;
          if (written >= SuperCombineFactory.MAX_FACTORIES)
            return sb.toString();
          if (written > 0)
            sb.append(';');
          written++;
          sb.append(SuperCombineFactory.encodeCell(cb, i < cf.cellRot.length ? cf.cellRot[i] : 0f));
        }
        continue;
      }
      Block cb = SuperCombineFactory.cellBlock(b.block);
      if (cb == null)
        continue;
      if (written >= SuperCombineFactory.MAX_FACTORIES)
        return sb.toString();
      if (written > 0)
        sb.append(';');
      written++;
      sb.append(SuperCombineFactory.encodeCell(cb, 0f));
    }
    // 补齐到 slots 个槽位（多余格子留空）
    if (slots > 0)
      for (int i = written; i < slots && i < SuperCombineFactory.MAX_FACTORIES; i++)
        sb.append(';');
    return sb.toString();
  }

  /**
   * 被选中的工厂坐标（"x,y;x,y"）：组合工厂放下后要把它们拆掉。
   *
   * <p>现成的组合工厂用 {@code !x,y} 标记 —— 落地时由 {@code consumeSources} 把整台并进来
   * （连它的库存一起），而不是当成"一台工厂"处理。
   */
  public static String sourcesOf(Seq<Building> found) {
    StringBuilder sb = new StringBuilder();
    for (Building b : found) {
      if (b == null || b.tile == null)
        continue;
      if (b instanceof SuperCombineFactory.SuperCombineFactoryBuild cf) {
        for (int i = 0; i < cf.cellBlocks.length; i++) {
          if (cf.cellBlocks[i] == null)
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
   * 每个来源"当时装着的货"（和 {@link #sourcesOf} 一一对齐）。
   *
   * <p>合体预览盖在原料工厂上时，原版会先把原料拆掉、之后才应用 config —— 那点库存只能
   * 顺着 config 带过去（见 {@code SuperCombineFactory.encodeConfig(layout, sources, carry)}）。
   */
  public static String[] carryOf(Seq<Building> found) {
    Seq<String> out = new Seq<>();
    for (Building b : found) {
      if (b == null || b.block == null)
        continue;
      if (b instanceof SuperCombineFactory.SuperCombineFactoryBuild cf) {
        String c = SuperCombineFactory.carryOf(b);
        for (int i = 0; i < cf.cellBlocks.length; i++)
          out.add(c);
        continue;
      }
      if (SuperCombineFactory.cellBlock(b.block) == null)
        continue;
      out.add(SuperCombineFactory.carryOf(b));
    }
    return out.toArray(String.class);
  }

  /** 框选区域里的工厂（行优先：先上后下、先左后右）。 */
  public static Seq<Building> scan(@Nullable Team team, int x1, int y1, int x2, int y2) {
    Seq<Building> out = new Seq<>();
    if (world == null)
      return out;
    Seq<Block> seenCell = new Seq<>();
    for (int y = y2; y >= y1; y--) {
      for (int x = x1; x <= x2; x++) {
        Tile t = world.tile(x, y);
        if (t == null || t.build == null || t.build.dead())
          continue;
        Building b = t.build;
        if (team != null && b.team != team)
          continue;
        // 【一台多格工厂只算一台】3x3 的压机被它盖住的 9 个格子都会返回同一个 build，
        // 只认它自己的那一格（= b.tile），否则一台 3x3 就吃掉 9 台的名额。
        if (b.tile != null && b.tile != t)
          continue;
        // 【加工厂】现成的组合工厂也认：把它框进去 = 连它里面那些工厂一起重新拼。
        if (b instanceof SuperCombineFactory.SuperCombineFactoryBuild) {
          out.add(b);
          continue;
        }
        if (SuperCombineFactory.cellBlock(b.block) == null)
          continue;
        out.add(b);
      }
    }
    return out;
  }

  // ==================== 联机：本机补配置 ====================

  /** 这次预览的虚影还在不在（{@code selectPlans} 里还有没有本方块的蓝图计划）。 */
  static boolean previewArmed() {
    try {
      if (control == null || control.input == null)
        return false;
      for (var plan : control.input.selectPlans)
        if (plan != null && plan.block instanceof SuperCombineFactory)
          return true;
    } catch (Throwable ignored) {
    }
    return false;
  }

  /**
   * 【落地即收工】预览结束：虚影计划 + 本机补配置队列 + "可顶名单"一起作废。
   *
   * <p>用户报的"放下去先盖一层暗的滤镜、过一会儿才恢复正常亮度"，暗的是**虚影**：本方块自己的
   * {@code drawGhost} 会画一层 0.45 的黑底（预览时它本来就该在），而预览结束后原版还会拿这同一份
   * plan 重画 —— 一是蓝图虚影（{@code control.input.selectPlans}，见 {@code DesktopInput.drawBottom}）
   * 落地后不会自己消失、继续跟着鼠标；二是排队的建造计划（{@code InputHandler.drawBuildPlans}）
   * 在开建前也按虚影画一遍。刚放下的工厂被这块黑底压住，直到虚影收掉才"亮回来"。
   * 合体本来就是一次性的（原料已经在这次落地里被吃掉），落地就该立刻收工。
   */
  static void endPreview() {
    pending.clear();
    SuperCombineFactory.clearReplaceAllowed();
    dropHoverGhost();
  }

  /** 收掉"还举在手上"的虚影计划：只动 {@code selectPlans}，不动补配置队列 / 可顶名单。 */
  static void dropHoverGhost() {
    try {
      var in = control == null ? null : control.input;
      if (in != null && !in.selectPlans.isEmpty()) {
        boolean ours = false;
        for (var plan : in.selectPlans)
          if (plan != null && plan.block instanceof SuperCombineFactory) {
            ours = true;
            break;
          }
        // 只在虚影确实是本方块时清：selectPlans 是和原版蓝图共用的，别人的蓝图计划不动
        if (ours) {
          in.selectPlans.clear();
          in.lastSchematic = null;
        }
      }
    } catch (Throwable ignored) {
    }
  }

  static void onTileChanged(Tile tile) {
    if (tile == null || tile.build == null || player == null)
      return;
    if (!(tile.build instanceof SuperCombineFactory.SuperCombineFactoryBuild b))
      return;
    if (pending.isEmpty() && !previewArmed())
      return; // 不是我们这次预览落的（别人放的 / 蓝图复制的）就不插手
    // 联机时客户端自己那份建筑拿不到蓝图里的 config，这里按"最近这次框选"补一次
    if ((b.layout == null || b.layout.isEmpty()) && !pending.isEmpty()) {
      Pending best = pending.first();
      try {
        b.configure(best.config);
      } catch (Throwable t) {
        Log.err("[combine] 补组合工厂配置失败", t);
      }
    }
    if (!pending.isEmpty())
      pending.remove(0);
    endPreview();
  }

  static void flushPending() {
    if (pending.isEmpty()) {
      SuperCombineFactory.clearReplaceAllowed();
      return;
    }
    for (int i = pending.size - 1; i >= 0; i--) {
      Pending p = pending.get(i);
      p.frames += Time.delta;
      if (p.frames > 60f * 30f)
        pending.remove(i); // 30 秒还没落地就当没放成
    }
    if (pending.isEmpty())
      // 超时没收工：这次预览已经废了（"可顶名单"一清，虚影压住原料时必然发红），
      // 连虚影一起收掉，别留一个红色虚影跟着鼠标、落地了还压着新建筑
      endPreview();
  }

  // ==================== 每帧 ====================

  static void update() {
    if (headless || Core.input == null)
      return;
    ensureProcessor();
    if (state == null || ui == null)
      return;
    if (!state.isGame() || player == null) {
      if (selecting)
        cancel();
      return;
    }
    flushPending();
    // 【落地即收工·第一步】玩家一点下去，计划就进了建造队列（见 SuperCombineFactory.onNewPlan）：
    // 立刻收掉跟着手的虚影，别让它在放置后继续把暗底画在新工厂上。
    // 补配置队列(pending)和"可顶名单"要留到真正落地，见 endPreview()/onTileChanged()。
    if (ordered) {
      ordered = false;
      dropHoverGhost();
    }
    if (!selecting)
      return;
    // 框选期间玩家在建造菜单里点了别的方块 → 退出框选，把操作权交回菜单
    if (control.input != null && control.input.block != null) {
      cancel();
      return;
    }
    if (!player.isBuilder() || ui.chatfrag.shown() || Core.scene.hasKeyboard()) {
      cancel();
      return;
    }
    if (Core.input.keyTap(Binding.deselect) || Core.input.keyTap(Binding.clearBuilding)) {
      cancel();
      return;
    }
    // 拖动时的高亮/计数：刷新快一点（2 帧），别让数字看着"卡"（用户说框不太灵敏）
    if (dragging && (ex != sx || ey != sy) && state.updateId - scanFrame > 2) {
      scanFrame = state.updateId;
      int x1 = Math.min(sx, ex), y1 = Math.min(sy, ey), x2 = Math.max(sx, ex), y2 = Math.max(sy, ey);
      scanCache.clear();
      scanCache.addAll(scan(player.team(), x1, y1, x2, y2));
    }
  }

  /** 我们的输入处理器必须是"第一个"（= 最先收到事件），否则框选时左键会去开枪/开面板。 */
  static void ensureProcessor() {
    try {
      var list = Core.input.getInputProcessors();
      if (list == null)
        return;
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
      if (overUi(screenX, screenY))
        return false;
      if (dragging && pointer != boxPointer)
        return true; // 别的手指：吃掉，别漏给移动摇杆
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
        return true;
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
        return true;
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
      if (keycode == KeyCode.escape || keycode == KeyCode.q) {
        cancel();
        return true;
      }
      return false;
    }
  }

  // ==================== 绘制框 ====================

  static void drawOverlay() {
    if (headless)
      return;
    // 【虚影自己画】原版那套蓝图虚影对本方块（isVisible=false 的虚拟方块）不一定画得出来：
    // 用户报的"预览是一个多重压缩机的贴图"就是以前走了默认的"画 fullIcon"。
    // 所以预览直接由我们把"整块底板 + 每格工厂的真贴图"画在计划位置上（稳定、和落地后的样子一致）。
    try {
      if (!pending.isEmpty() && control != null && control.input != null
          && !control.input.selectPlans.isEmpty()) {
        var plan = control.input.selectPlans.first();
        if (plan != null && plan.block instanceof SuperCombineFactory scf) {
          Draw.draw(mindustry.graphics.Layer.overlayUI, () -> SuperCombineFactory.drawGhost(plan, scf.side));
        }
      }
    } catch (Throwable ignored) {
    }
    if (!selecting || sx < 0 || ex < 0)
      return;
    try {
      int x1 = Math.min(sx, ex), y1 = Math.min(sy, ey), x2 = Math.max(sx, ex), y2 = Math.max(sy, ey);
      float fx = x1 * tilesize - tilesize / 2f, fy = y1 * tilesize - tilesize / 2f;
      float fw = (x2 - x1 + 1) * tilesize, fh = (y2 - y1 + 1) * tilesize;
      int count = count(scanCache);
      String text;
      if (count <= 0) {
        text = "框选工厂";
      } else if (count < 2) {
        text = count + " 台工厂（至少要 2 台）";
      } else {
        text = count + " 台工厂 → " + SuperCombineFactory.sideFor(count) + "x"
            + SuperCombineFactory.sideFor(count) + " 组合工厂";
      }

      Draw.draw(Layer.overlayUI, () -> {
        Draw.color(Pal.accent, 0.22f);
        Fill.crect(fx, fy, fw, fh);
        Lines.stroke(2f);
        Draw.color(Pal.accentBack);
        Lines.rect(fx, fy - 1f, fw, fh);
        Draw.color(Pal.accent);
        Lines.rect(fx, fy, fw, fh);
        // 已经被算进去的工厂
        Draw.color(Pal.accent, 0.5f);
        for (Building b : scanCache) {
          if (b == null || !b.isValid() || b.block == null)
            continue;
          float s = b.block.size * tilesize;
          Fill.crect(b.x - s / 2f, b.y - s / 2f, s, s);
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
