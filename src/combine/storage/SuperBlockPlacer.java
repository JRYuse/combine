package combine.storage;

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
 * 超级组合方块的选择/放置流程（纯客户端）。
 *
 * <p>交互：按快捷键（设置 → 按键里可改，默认 H），或者点 HUD 上的按钮
 * → 进入框选模式 → 拖一个框松开 → 数出框里**非炮台**的建筑、按数量生成对应边长的
 * 超级组合方块虚影跟着鼠标走 → 左键确定、右键/Q/Esc 取消。
 *
 * <p>炮台会被自动过滤掉（它们走 {@link combine.turret.SuperTurretPlacer}）。
 * 若框里全是炮台，则提示玩家改用超级组合炮台。
 */
public class SuperBlockPlacer {
    /** 快捷键（设置 → 按键里可改；默认 H）。 */
    public static @Nullable KeyBind selectKey;

    static boolean selecting = false;
    static boolean dragging = false;
    static int sx = -1, sy = -1, ex = -1, ey = -1;
    static int boxPointer = -1;

    static final Seq<Building> scanCache = new Seq<>();
    static long scanFrame = -1000L;

    static final Seq<Pending> pending = new Seq<>();
    static final InputProcessor processor = new Processor();
    static @Nullable arc.scene.Element button;
    static final float BUTTON_TOP_PAD = 273f; // 比 SuperTurret 按钮再往下 48px
    static int buttonRetry = 0;

    static class Pending {
        final String layout;
        float frames = 0f;
        Pending(String layout) { this.layout = layout; }
    }

    public static boolean selecting() { return selecting; }

    public static void register() {
        if (headless || !SuperBlock.enabled) return;
        try {
            selectKey = KeyBind.add("combine_super_block", KeyCode.h, "combine");
        } catch (Throwable t) {
            Log.err("[combine] 超级组合方块快捷键注册失败（还能用 HUD 按钮）", t);
        }
        Events.run(Trigger.update, SuperBlockPlacer::update);
        Events.run(Trigger.draw, SuperBlockPlacer::draw);
        Events.on(WorldLoadEvent.class, e -> {
            selecting = false;
            dragging = false;
            sx = sy = ex = ey = -1;
            pending.clear();
            button = null;
            buttonRetry = 0;
        });
        Events.on(mindustry.game.EventType.TileChangeEvent.class, e -> {
            try { onTileChanged(e.tile); }
            catch (Throwable ignored) {}
        });
    }

    // ==================== 选择流程 ====================

    public static void toggle() {
        if (selecting) cancel();
        else start();
    }

    public static void start() {
        if (headless || state == null || !state.isGame() || player == null || !player.isBuilder())
            return;
        selecting = true;
        dragging = false;
        sx = sy = ex = ey = -1;
        scanCache.clear();
        try {
            control.input.block = null;
            if (control.input instanceof DesktopInput di) di.mode = PlaceMode.none;
        } catch (Throwable ignored) {}
        ui.showInfoFade("[accent]超级组合方块[]：拖动左键框选**非炮台**建筑（炮台请用超级组合炮台；右键/Esc 取消）");
    }

    public static void cancel() {
        if (selecting) ui.showInfoFade("[lightgray]已取消框选");
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

    static void finish() {
        int x1 = Math.min(sx, ex), y1 = Math.min(sy, ey), x2 = Math.max(sx, ex), y2 = Math.max(sy, ey);
        // 同时统计两种（用户可能混选，需要区分提示）
        Seq<Building> foundBlocks = scan(player.team(), x1, y1, x2, y2);
        Seq<Building> foundTurrets = scanTurrets(player.team(), x1, y1, x2, y2);

        if (foundBlocks.size < 2) {
            if (foundTurrets.size >= 2 && foundBlocks.isEmpty()) {
                ui.showInfoFade("[scarlet]这个框里只有 @ 台炮台，请用超级组合炮台（默认快捷键 G）", foundTurrets.size);
            } else {
                ui.showInfoFade("[scarlet]这个框里只有 @ 台非炮台建筑（至少要 2 台）", foundBlocks.size);
            }
            return;
        }

        int side = SuperBlock.sideFor(foundBlocks.size);
        SuperBlock block = SuperBlock.blockForSide(side);
        if (block == null) {
            ui.showInfoFade("[scarlet]" + arc.util.Strings.format("超级组合方块（@x@）还没装配好", side, side));
            return;
        }

        String layout = layoutOf(foundBlocks, side * side);
        String sources = sourcesOf(foundBlocks);
        Seq<Schematic.Stile> tiles = new Seq<>();
        tiles.add(new Schematic.Stile(block, 0, 0, layout + "|" + sources, (byte) 0));
        Schematic schem = new Schematic(tiles, new StringMap(), side, side);
        try {
            control.input.useSchematic(schem, false);
        } catch (Throwable t) {
            Log.err("[combine] 生成超级组合方块虚影失败", t);
            return;
        }
        pending.add(new Pending(layout));
        if (pending.size > 8) pending.remove(0);
        selecting = false;
        dragging = false;

        String hint = foundTurrets.size > 0
                ? "（[lightgray]框里的 " + foundTurrets.size + " 台炮台已被忽略，请另用超级组合炮台[]）"
                : "";
        ui.showInfoFade("[accent]" + arc.util.Strings.format(
                "@ 台建筑 → @x@ 超级组合方块[]：左键确定位置，右键/Q/Esc 取消 @",
                foundBlocks.size, side, side, hint));
    }

    public static String layoutOf(Seq<Building> found, int cellCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cellCount; i++) {
            if (i > 0) sb.append(';');
            if (i >= found.size) continue;
            Building b = found.get(i);
            if (b == null || b.block == null) continue;
            Block cb = SuperBlock.cellBlock(b.block);
            if (cb == null) continue;
            float rot = b.rotation;
            sb.append(SuperBlock.encodeCell(cb, rot));
        }
        return sb.toString();
    }

    public static String sourcesOf(Seq<Building> found) {
        StringBuilder sb = new StringBuilder();
        for (Building b : found) {
            if (b == null || b.tile == null) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(b.tile.x).append(',').append(b.tile.y);
        }
        return sb.toString();
    }

    /** 框选区域里的**非炮台**建筑。 */
    public static Seq<Building> scan(@Nullable Team team, int x1, int y1, int x2, int y2) {
        Seq<Building> out = new Seq<>();
        if (world == null) return out;
        int max = SuperBlock.MAX_SIDE * SuperBlock.MAX_SIDE;
        for (int y = y2; y >= y1; y--) {
            for (int x = x1; x <= x2; x++) {
                Tile t = world.tile(x, y);
                if (t == null || t.build == null || t.build.dead()) continue;
                Building b = t.build;
                if (team != null && b.team != team) continue;
                if (b.tile != null && b.tile != t) continue;
                if (b.block instanceof Turret) continue; // 炮台走 SuperTurret
                if (SuperBlock.cellBlock(b.block) == null) continue;
                out.add(b);
                if (out.size >= max) return out;
            }
        }
        return out;
    }

    /** 只数炮台（用于提示）。 */
    public static Seq<Building> scanTurrets(@Nullable Team team, int x1, int y1, int x2, int y2) {
        Seq<Building> out = new Seq<>();
        if (world == null) return out;
        for (int y = y2; y >= y1; y--) {
            for (int x = x1; x <= x2; x++) {
                Tile t = world.tile(x, y);
                if (t == null || t.build == null || t.build.dead()) continue;
                Building b = t.build;
                if (team != null && b.team != team) continue;
                if (b.tile != null && b.tile != t) continue;
                if (b.block instanceof Turret) out.add(b);
            }
        }
        return out;
    }

    // ==================== 联机：本机补布局 ====================

    static void onTileChanged(Tile tile) {
        if (!selecting && pending.isEmpty()) return;
        if (tile == null || tile.build == null || player == null) return;
        if (!(tile.build instanceof SuperBlock.SuperBlockBuild b)) return;
        if (b.layout != null && !b.layout.isEmpty()) return;
        Pending best = null;
        for (Pending p : pending) { best = p; break; }
        if (best == null) return;
        try {
            b.configure(best.layout);
            pending.remove(best);
        } catch (Throwable t) {
            Log.err("[combine] 补超级组合方块布局失败", t);
        }
    }

    static void flushPending() {
        if (pending.isEmpty()) return;
        for (int i = pending.size - 1; i >= 0; i--) {
            Pending p = pending.get(i);
            p.frames += Time.delta;
            if (p.frames > 60f * 30f) pending.remove(i);
        }
    }

    // ==================== 每帧 ====================

    static void update() {
        if (headless || Core.input == null) return;
        ensureProcessor();
        if (state == null || ui == null) return;
        ensureButton();
        flushPending();
        if (!state.isGame() || player == null) {
            if (selecting) cancel();
            return;
        }
        if (!selecting) return;
        if (control.input != null && control.input.block != null) {
            boolean sameEntry = control.input.block instanceof SuperBlock;
            cancel();
            if (sameEntry) control.input.block = null;
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

        if (dragging && (ex != sx || ey != sy) && state.updateId - scanFrame > 8) {
            scanFrame = state.updateId;
            int x1 = Math.min(sx, ex), y1 = Math.min(sy, ey), x2 = Math.max(sx, ex), y2 = Math.max(sy, ey);
            scanCache.clear();
            scanCache.addAll(scan(player.team(), x1, y1, x2, y2));
        }
    }

    static void ensureButton() {
        if (button != null && button.parent != null) {
            try { button.toFront(); } catch (Throwable ignored) {}
            return;
        }
        if (buttonRetry-- > 0) return;
        buttonRetry = 30;
        try {
            if (ui == null || ui.hudGroup == null) return;
            arc.scene.ui.layout.Table root = new arc.scene.ui.layout.Table();
            root.name = "combineSuperBlockButton";
            root.setFillParent(true);
            root.touchable = arc.scene.event.Touchable.childrenOnly;
            root.align(arc.util.Align.topRight);
            root.add().size(8f, BUTTON_TOP_PAD);
            root.row();
            // 图标用一个"仓库"图标（Icon.box 或 storage）
            root.button(mindustry.gen.Icon.box, mindustry.ui.Styles.clearNonei, SuperBlockPlacer::toggle)
                    .size(48f).padRight(8f)
                    .tooltip("超级组合方块：点一下框选非炮台建筑（默认快捷键 H，右键/Esc 取消）");
            if (Core.scene != null)
                Core.scene.root.addChild(root);
            else
                ui.hudGroup.addChild(root);
            button = root;
            Log.info("[combine] 超级组合方块按钮已挂到 HUD 右上");
        } catch (Throwable t) {
            Log.err("[combine] 超级组合方块按钮挂载失败（还能用快捷键）", t);
        }
    }

    static void ensureProcessor() {
        try {
            var list = Core.input.getInputProcessors();
            if (list == null) return;
            if (list.isEmpty() || list.peek() != processor) {
                Core.input.removeProcessor(processor);
                Core.input.addProcessor(processor);
            }
        } catch (Throwable ignored) {}
    }

    static class Processor implements InputProcessor {
        @Override
        public boolean touchDown(int screenX, int screenY, int pointer, KeyCode button) {
            if (!selecting) return false;
            if (overUi(screenX, screenY)) return false;
            if (dragging && pointer != boxPointer) return true;
            if (button == KeyCode.mouseRight || button == KeyCode.mouseMiddle) {
                cancel();
                return true;
            }
            if (button != KeyCode.mouseLeft) return true;
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
            if (!selecting) return false;
            if (overUi(screenX, screenY)) return false;
            if (dragging && pointer != boxPointer) return true;
            if (button != KeyCode.mouseLeft) return selecting;
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
            if (!selecting) return false;
            if (pointer != boxPointer) return true;
            if (overUi(screenX, screenY) && !dragging) return false;
            toTiles(screenX, screenY);
            return true;
        }

        static boolean overUi(int screenX, int screenY) {
            try { return Core.scene != null && Core.scene.hasMouse(screenX, screenY); }
            catch (Throwable t) { return false; }
        }

        @Override
        public boolean keyDown(KeyCode keycode) {
            if (!selecting) return false;
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
        if (headless || !selecting || sx < 0 || ex < 0) return;
        try {
            int x1 = Math.min(sx, ex), y1 = Math.min(sy, ey), x2 = Math.max(sx, ex), y2 = Math.max(sy, ey);
            float fx = x1 * tilesize - tilesize / 2f, fy = y1 * tilesize - tilesize / 2f;
            float fw = (x2 - x1 + 1) * tilesize, fh = (y2 - y1 + 1) * tilesize;
            String text;
            int count = scanCache.size;
            if (count <= 0) {
                text = "框选建筑（不含炮台）";
            } else if (count < 2) {
                text = count + " 台建筑（至少要 2 台）";
            } else {
                int side = SuperBlock.sideFor(count);
                text = count + " 台建筑 → " + side + "x" + side;
            }

            Draw.draw(Layer.overlayUI, () -> {
                Draw.color(Pal.accent, 0.22f);
                Fill.crect(fx, fy, fw, fh);
                Lines.stroke(2f);
                Draw.color(Pal.accentBack);
                Lines.rect(fx, fy - 1f, fw, fh);
                Draw.color(Pal.accent);
                Lines.rect(fx, fy, fw, fh);
                Draw.color(Pal.accent, 0.5f);
                for (Building b : scanCache) {
                    if (b == null || !b.isValid() || b.block == null) continue;
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
        } catch (Throwable ignored) {}
    }
}