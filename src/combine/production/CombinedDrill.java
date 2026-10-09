package combine.production;
import combine.net.ComboNet;
import combine.util.ComboReflect;
import combine.util.ComboUi;
import arc.Core;
import arc.audio.Sound;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.math.Angles;
import arc.math.Interp;
import arc.math.Mathf;
import arc.math.Rand;
import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.struct.EnumSet;
import arc.struct.IntSet;
import arc.struct.ObjectFloatMap;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Eachable;
import arc.util.Nullable;
import arc.util.Strings;
import arc.util.Time;
import arc.util.Tmp;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.content.Fx;
import mindustry.content.Liquids;
import mindustry.entities.Effect;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.gen.Sounds;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Lod;
import mindustry.graphics.Pal;
import mindustry.logic.LAccess;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.ui.ReqImage;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.StaticWall;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeLiquid;
import mindustry.world.consumers.ConsumeLiquidBase;
import mindustry.world.consumers.ConsumeLiquids;
import mindustry.world.consumers.ConsumePower;
import mindustry.world.meta.BlockFlag;
import mindustry.world.meta.BlockGroup;
import mindustry.world.meta.Env;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.world.meta.StatValues;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.*;
import static mindustry.Vars.iconMed;

/**
 * 组合钻机 —— 三模式统一版（无 @Load 注解）
 * 模式：drill / burst / beam
 */
public class CombinedDrill extends Block {

    public enum Mode {
        drill, burst, beam
    }

    public Mode mode = Mode.drill;

    // ==================== 矿物阴影：压成 1x1 的钻头 ====================
    /**
     * 【用户 2026-10-08 第三版】阴影上的钻头**实际占地也压成 1x1**（不是只画小）：每种钻头在
     * 内容装配时造一个 {@code size=1} 的隐藏版本（{@code Main.createShadowDrills}），玩家把钻头
     * 摆到阴影上时由 {@link #onNewPlan} 自动换成它。
     *
     * <p>它只占 1 格，但挖矿 / 判定矿石仍按**它原来那 size×size 的面积**算（{@link #mineSize()}）——
     * 也就是"每格都盖住了矿物"照算，激光束条数同理。阴影区域整体是一个"资源输出站"：
     * 区域里的这些 1x1 钻头共用一口物品 / 液体池（见 {@link OreShadow}），所以通水给任意一台
     * 都能加速全站，物品也能从区域任意一边送出去（本质和合体工厂 / 合体炮台是同一套东西）。
     */
    public int mineSize = -1;
    /** 本钻头"压成 1x1"的那个隐藏版本（内容装配时建好）。 */
    public @Nullable CombinedDrill shadowVariant;
    /** 本实例是不是那个 1x1 版本。 */
    public boolean shadowShrunk = false;

    /** 挖矿/判定用地基边长：1x1 版本 = 原来的 size，其余 = 自己的 size。 */
    public int mineSize() {
        return mineSize > 0 ? mineSize : size;
    }

    /** 画这一格时的缩放系数：1x1 版本把原来 size×size 的贴图缩进一格。 */
    public float cellDrawScale() {
        int m = mineSize();
        return m <= 0 ? 1f : size / (float) m;
    }

    /** 和原版 {@code Block.nearbySide} 同口径，但按 {@link #mineSize()} 算（1x1 版本要用原来的宽度）。 */
    public void nearbySideMine(int x, int y, int rotation, int index, Point2 out) {
        int s = mineSize();
        int cornerX = x - (s - 1) / 2, cornerY = y - (s - 1) / 2;
        switch (rotation) {
            case 0 -> out.set(cornerX + s, cornerY + index);
            case 1 -> out.set(cornerX + index, cornerY + s);
            case 2 -> out.set(cornerX - 1, cornerY + index);
            case 3 -> out.set(cornerX + index, cornerY - 1);
            default -> out.set(x, y);
        }
    }

    /** {@link #mineSize()} 见方那片地基覆盖的格子。 */
    public void eachMineTile(Tile tile, arc.func.Cons<Tile> cons) {
        if (tile == null)
            return;
        int s = mineSize();
        if (s <= 1) {
            cons.get(tile);
            return;
        }
        int o = -((s - 1) / 2);
        for (int dx = 0; dx < s; dx++)
            for (int dy = 0; dy < s; dy++) {
                Tile other = world.tile(tile.x + dx + o, tile.y + dy + o);
                if (other != null)
                    cons.get(other);
            }
    }

    /** {@link #eachMineTile} 的 Seq 版（给 {@code find} 之类的旧代码用）。 */
    public Seq<Tile> mineTiles(Tile tile, Seq<Tile> out) {
        out.clear();
        eachMineTile(tile, out::add);
        return out;
    }

    /**
     * 压格版本和合体工厂 / 合体炮台同一套：建造菜单里看不见（{@code hidden}），
     * 但**放置流程必须仍然合法**。
     *
     * <p>【用户报的"阴影上摆钻头、点建造后钻头直接消失"】原版 {@code Build.validPlaceIgnoreUnits}
     * 第 185 行读的是 {@code type.isPlaceable()}，而默认实现里含 {@code isVisible()}，
     * {@code hidden} 方块一律 false —— 于是换过去的那份 plan 被判非法、直接从建造队列里丢掉，
     * 表现就是"点下去什么都没放"。这里单独放行（和 SuperCombineFactory / SuperTurret 一致）。
     */
    @Override
    public boolean isVisible() {
        return shadowShrunk ? false : super.isVisible();
    }

    @Override
    public boolean isPlaceable() {
        return shadowShrunk
                ? (supportsEnv(state.rules.env) && (!isBanned() || state.rules.editor))
                : super.isPlaceable();
    }

    /**
     * 【落地压格】玩家把钻头摆到阴影上：这一份 plan 换成 1x1 的隐藏版本。
     *
     * <p>原版 {@code InputHandler.flushPlans} 在把计划塞进建造队列前会调一次
     * {@code plan.block.onNewPlan(copy)}，我们在这时改 {@code copy.block} —— 队列里、发到服务端的
     * 就已经是 1x1 方块（和超级炮台 / 组合工厂用蓝图虚影放下去是同一类做法，联机可同步）。
     * 锚点 {@code plan.x/plan.y} 不动，{@link #mineSize()} 的虚拟地基按同一锚点算。
     */
    @Override
    public void onNewPlan(mindustry.entities.units.BuildPlan plan) {
        if (plan == null || plan.block == null || shadowShrunk)
            return;
        try {
            CombinedDrill target = shadowVariant;
            if (target == null)
                return;
            if (!OreShadow.covers(plan.x, plan.y, mineSize()))
                return;
            // 【用户 2026-10-09】"造完的钻头塞阴影没有被覆盖的地方"：点在哪无所谓，
            // 落点换成这片阴影里最近的空位（锚点/方块一起改，plan 是拷贝，直接改就会发到服务端）。
            int[] spot = OreShadow.freeSpotFor(plan.x, plan.y, mineSize());
            if (spot == null)
                return; // 这片阴影满了：保持原 plan（canPlaceOn 那一关已经判非法了）
            plan.block = target;
            plan.x = spot[0];
            plan.y = spot[1];
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把 {@code from} 的贴图字段整套拷到 {@code to}。
     *
     * <p>1x1 版本是内容装配后才现造的，没法再走原版 {@code load()}（那按自己的名字找图集，
     * "shadow-xxx" 找不到就把贴图换成 error）；直接沿用原钻头已经加载好的贴图最稳。
     */
    public static void copyVisuals(Block from, Block to) {
        try {
            for (Class<?> c = from.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            || f.getType() != TextureRegion.class)
                        continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(from);
                        java.lang.reflect.Field t = fieldOf(to.getClass(), f.getName());
                        if (t != null && t.getType() == TextureRegion.class) {
                            t.setAccessible(true);
                            t.set(to, v);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static java.lang.reflect.Field fieldOf(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    // ==================== 组合体通用 ====================
    public boolean allowCrossTypeCombo = false;
    public float itemCapacityMultiplier = 1f;
    public float liquidCapacityMultiplier = 1f;
    public float baseLiquidCapacity = 10f;
    public float displayLiquid;

    // ==================== Drill / Burst 通用 ====================
    public float hardnessDrillMultiplier = 50f;
    public int tier = 1;
    public float drillTime = 300f;
    public float liquidBoostIntensity = 1.6f;
    public float warmupSpeed = 0.015f;
    public @Nullable Item blockedItem;
    public @Nullable Seq<Item> blockedItems;
    public boolean drawMineItem = true;
    public Effect drillEffect = Fx.mine;
    public float drillEffectRnd = -1f;
    public float drillEffectChance = 0.02f;
    public float rotateSpeed = 2f;
    public Effect updateEffect = Fx.pulverizeSmall;
    public float updateEffectChance = 0.02f;
    public ObjectFloatMap<Item> drillMultipliers = new ObjectFloatMap<>();
    public boolean drawRim = false;
    public boolean drawSpinSprite = true;
    public Color heatColor = Color.valueOf("ff5512");

    // 纹理（手动加载）
    public TextureRegion rimRegion, rotatorRegion, topRegion, itemRegion;

    // ==================== Burst 专用 ====================
    public float shake = 2f;
    public Interp speedCurve = Interp.pow2In;
    public float invertedTime = 200f;
    public float arrowSpacing = 4f, arrowOffset = 0f;
    public int arrows = 3;
    public Color arrowColor = Color.valueOf("feb380"), baseArrowColor = Color.valueOf("6e7080");
    public Color glowColorBurst = arrowColor.cpy();
    public Sound drillSound = Sounds.drillImpact;
    public float drillSoundVolume = 0.6f, drillSoundPitchRand = 0.1f;
    public TextureRegion topInvertRegion, glowRegionBurst, arrowRegion, arrowBlurRegion;

    // ==================== Beam 专用 ====================
    public int range = 5;
    public float laserWidth = 0.65f;
    public float optionalBoostIntensity = 2.5f;
    public Color sparkColor = Color.valueOf("fd9e81"), glowColorBeam = Color.white;
    public float glowIntensity = 0.2f, pulseIntensity = 0.07f;
    public float glowScl = 3f;
    public int sparks = 7;
    public float sparkRange = 10f, sparkLife = 27f, sparkRecurrence = 4f, sparkSpread = 45f, sparkSize = 3.5f;
    public Color boostHeatColor = Color.sky.cpy().mul(0.87f);
    public Color heatColorBeam = new Color(1f, 0.35f, 0.35f, 0.9f);
    public float heatPulse = 0.3f, heatPulseScl = 7f;
    public TextureRegion laser, laserEnd, laserCenter;
    public TextureRegion laserBoost, laserEndBoost, laserCenterBoost;
    public TextureRegion topRegionBeam, glowRegionBeam;

    // drill/burst 计数 ore 的临时变量
    protected final ObjectIntMap<Item> oreCount = new ObjectIntMap<>();
    protected final Seq<Item> itemArray = new Seq<>();
    protected @Nullable Item returnItem;
    protected int returnCount;

    public CombinedDrill(String name) {
        super(name);
        buildType = () -> new CombinedDrillBuild();
        conductivePower = true;
        update = true;
        solid = true;
        group = BlockGroup.drills;
        hasItems = true;
        hasLiquids = true;
        ambientSound = Sounds.loopDrill;
        ambientSoundVolume = 0.019f;
        envEnabled |= Env.space;
        flags = EnumSet.of(BlockFlag.drill);
    }

    @Override
    public void load() {
        super.load();
        // Drill 通用纹理
        rimRegion = Core.atlas.find(name + "-rim");
        rotatorRegion = Core.atlas.find(name + "-rotator");
        topRegion = Core.atlas.find(name + "-top");
        itemRegion = Core.atlas.find(name + "-item", "drill-item-" + size);

        // Burst 纹理
        topInvertRegion = Core.atlas.find(name + "-top-invert");
        glowRegionBurst = Core.atlas.find(name + "-glow");
        arrowRegion = Core.atlas.find(name + "-arrow");
        arrowBlurRegion = Core.atlas.find(name + "-arrow-blur");

        // Beam 纹理
        laser = Core.atlas.find(name + "-beam", "drill-laser");
        laserEnd = Core.atlas.find(name + "-beam-end", "drill-laser-end");
        laserCenter = Core.atlas.find(name + "-beam-center", "drill-laser-center");
        laserBoost = Core.atlas.find(name + "-beam-boost", "drill-laser-boost");
        laserEndBoost = Core.atlas.find(name + "-beam-boost-end", "drill-laser-boost-end");
        laserCenterBoost = Core.atlas.find(name + "-beam-boost-center", "drill-laser-boost-center");
        topRegionBeam = Core.atlas.find(name + "-top");
        glowRegionBeam = Core.atlas.find(name + "-glow");
    }

    @Override
    public void init() {
        super.init();
        conductivePower = true;
        if (liquidCapacity != 9999f) {
            displayLiquid = baseLiquidCapacity;
        }
        liquidCapacity = 9999f;
        if (!hasLiquids)
            displayLiquid = 0;
        hasLiquids = true;
        hasItems = true;

        if (blockedItems == null && blockedItem != null)
            blockedItems = Seq.with(blockedItem);
        if (drillEffectRnd < 0)
            drillEffectRnd = size;

        if (mode == Mode.beam) {
            rotate = true;
            drawArrow = false;
            regionRotated1 = 1;
            ignoreLineRotation = true;
            ambientSound = Sounds.loopMineBeam;
            ambientSoundVolume = 0.05f;
            updateClipRadius((range + 2) * tilesize);
        } else if (mode == Mode.burst) {
            hardnessDrillMultiplier = 0f;
            drillEffectRnd = 0f;
            drillEffect = Fx.shockwave;
            ambientSoundVolume = 0.18f;
            ambientSound = Sounds.drillCharge;
        }

        // 禁用环境音循环：避免触发 SoundControl 环境音线程在 Android 上的
        // IllegalThreadStateException（Thread.start 崩溃）
        ambientSound = mindustry.gen.Sounds.none;
        ambientSoundVolume = 0f;
    }

    @Override
    public void setStats() {
        super.setStats();
        if (mode == Mode.beam) {
            stats.add(Stat.drillTier,
                    StatValues.drillables(drillTime, 0f, mineSize(), drillMultipliers,
                            b -> (b instanceof Floor f && f.wallOre && f.itemDrop != null && f.itemDrop.hardness <= tier
                                    && (blockedItems == null || !blockedItems.contains(f.itemDrop))) ||
                                    (b instanceof StaticWall w && w.itemDrop != null && w.itemDrop.hardness <= tier
                                            && (blockedItems == null || !blockedItems.contains(w.itemDrop)))));
            stats.add(Stat.drillSpeed, 60f / drillTime * mineSize(), StatUnit.itemsSecond);
            if (optionalBoostIntensity != 1 && findConsumer(
                    f -> f instanceof ConsumeLiquidBase && f.booster) instanceof ConsumeLiquidBase consBase) {
                stats.replace(Stat.booster, StatValues.speedBoosters("{0}" + StatUnit.timesSpeed.localized(),
                        consBase.amount, optionalBoostIntensity, false, consBase::consumes));
            }
        } else {
            stats.add(Stat.drillTier, StatValues.drillables(drillTime, hardnessDrillMultiplier, mineSize() * mineSize(),
                    drillMultipliers, b -> b instanceof Floor f && !f.wallOre && f.itemDrop != null &&
                            f.itemDrop.hardness <= tier && (blockedItems == null || !blockedItems.contains(f.itemDrop))
                            && (indexer.isBlockPresent(f) || state.isMenu())));
            stats.add(Stat.drillSpeed, 60f / drillTime * mineSize() * mineSize(), StatUnit.itemsSecond);
            if (liquidBoostIntensity != 1 && findConsumer(
                    f -> f instanceof ConsumeLiquidBase && f.booster) instanceof ConsumeLiquidBase consBase) {
                if (mode == Mode.burst) {
                    stats.remove(Stat.booster);
                    stats.add(Stat.booster, StatValues.speedBoosters("{0}" + StatUnit.timesSpeed.localized(),
                            consBase.amount, liquidBoostIntensity, false, consBase::consumes));
                } else {
                    stats.add(Stat.booster, StatValues.speedBoosters("{0}" + StatUnit.timesSpeed.localized(),
                            consBase.amount, liquidBoostIntensity * liquidBoostIntensity, false, consBase::consumes));
                }
            }
        }
        stats.remove(Stat.liquidCapacity);
        stats.add(Stat.liquidCapacity, displayLiquid, StatUnit.liquidUnits);
    }

    @Override
    public void setBars() {
        super.setBars();
        addBar("drillspeed", (CombinedDrillBuild e) -> new Bar(
                () -> Core.bundle.format("bar.drillspeed", Strings.fixed(e.lastDrillSpeed * 60 * e.timeScale(), 2)),
                () -> Pal.ammo, () -> e.warmup));
    }

    @Override
    public boolean outputsItems() {
        return true;
    }

    @Override
    public boolean rotatedOutput(int x, int y) {
        return false;
    }

    @Override
    public TextureRegion[] icons() {
        if (mode == Mode.beam)
            return new TextureRegion[] { region, topRegionBeam };
        if (mode == Mode.burst)
            return new TextureRegion[] { region, topRegion };
        return new TextureRegion[] { region, rotatorRegion, topRegion };
    }

    @Override
    public boolean canPlaceOn(Tile tile, Team team, int rotation) {
        // 【用户 2026-10-09】"只要阴影区域没满，可以在阴影任意位置建造钻头，造完的钻头塞阴影没有被覆盖的地方"：
        // 点在这片阴影上（地基压到阴影就算）时不再要求"脚下有矿"，只要求这片区域还有空位 ——
        // 真正落点由 onNewPlan 挪到最近的空位（见 OreShadow.freeSpotFor）。
        // 区域满了就直接判非法（点下去什么也不放，不会去顶掉区域里已有的东西）。
        if (tile != null && OreShadow.covers(tile.x, tile.y, size)) {
            int[] spot = OreShadow.freeSpotFor(tile.x, tile.y, size);
            if (spot != null) {
                lastPlaceX = tile.x;
                lastPlaceY = tile.y;
                return true;
            }
            lastPlaceX = Integer.MIN_VALUE;
            return false;
        }
        lastPlaceX = Integer.MIN_VALUE;
        if (mode == Mode.beam) {
            for (int i = 0; i < mineSize(); i++) {
                nearbySideMine(tile.x, tile.y, rotation, i, Tmp.p1);
                for (int j = 0; j < range; j++) {
                    Tile other = world.tile(Tmp.p1.x + Geometry.d4x(rotation) * j,
                            Tmp.p1.y + Geometry.d4y(rotation) * j);
                    if (other != null && other.solid()) {
                        Item drop = other.wallDrop();
                        if (drop != null && drop.hardness <= tier
                                && (blockedItems == null || !blockedItems.contains(drop)))
                            return true;
                        break;
                    }
                }
            }
            return false;
        } else {
            if (isMultiblock() || mineSize() > 1) {
                Seq<Tile> linked = mineTiles(tile, tempTiles);
                for (int i = 0; i < linked.size; i++)
                    if (canMine(linked.get(i)))
                        return true;
                return false;
            }
            return canMine(tile);
        }
    }

    /** 上一次 {@link #canPlaceOn} 问的那一格（原版 validPlace 先问 canPlaceOn 再问 canReplace，这里当上下文用）。 */
    int lastPlaceX = Integer.MIN_VALUE, lastPlaceY = Integer.MIN_VALUE;

    /**
     * 阴影上允许"顶掉"点到的东西（可能是已经塞进去的钻头、或者别人铺的传送带）——
     * 因为 plan 随后会被 {@link #onNewPlan} 挪到空位上，实际不会真的顶掉谁。
     **/
    @Override
    public boolean canReplace(Block other) {
        if (lastPlaceX != Integer.MIN_VALUE && OreShadow.covers(lastPlaceX, lastPlaceY, size))
            return true;
        return super.canReplace(other);
    }

    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid) {
        super.drawPlace(x, y, rotation, valid);
        if (mode == Mode.beam)
            drawPlaceBeam(x, y, rotation, valid);
        else
            drawPlaceDrill(x, y, valid);
    }

    @Override
    public void drawPlanConfig(BuildPlan plan, Eachable<BuildPlan> list) {
        if (mode == Mode.beam || !plan.worldContext)
            return;

        Tile tile = plan.tile();
        if (tile == null)
            return;

        countOre(tile);
        if (returnItem == null || !drawMineItem)
            return;

        // 压格版本的中心色块也要缩进这一格（和 drawPlanRegion 同一个系数）
        float k = planScaleFor(plan.x, plan.y);
        float px = landingX(plan), py = landingY(plan);
        float ox = Draw.xscl, oy = Draw.yscl;
        try {
            if (k != 1f)
                Draw.scl(k);
            Draw.tint(returnItem.color);
            Draw.rect(itemRegion, px, py);
            Draw.color();
        } catch (Throwable ignored) {
        } finally {
            if (k != 1f)
                Draw.scl(ox, oy);
        }
    }

    /**
     * 这颗钻头的**预设虚影**该怎么画：
     * <ul>
     * <li>自己就是压格版本（{@code shadowShrunk}）→ 按 1/原size 缩进一格；</li>
     * <li>自己是原版钻头、但这一格（含它 size×size 的地基）压在矿物阴影上 → 落地后会被
     * {@code onNewPlan} 换成压格版本，虚影也照压格版本画（所见即所得）；
     * 这时锚点用这一格自己（落地后 1x1 版本就落在这一格）。</li>
     * </ul>
     *
     * @return 画虚影用的缩放系数（1 = 照原版画）
     */
    public float planScaleFor(int x, int y) {
        if (shadowShrunk || (shadowVariant != null && OreShadow.covers(x, y, mineSize())))
            return 1f / Math.max(mineSize(), 1);
        return 1f;
    }

    /**
     * 压格钻头**虚影的落点**（世界坐标）：不是原钻头那 size×size 地基的中心，而是
     * "落地后这一格"的中心 —— 自己就是压格版本时 = 锚点那一格；原版钻头悬停在阴影上时
     * = {@link OreShadow#freeSpotFor} 算出来的空位（和 {@code onNewPlan} 落地用的是同一个口径），
     * 所以虚影画在哪就真落在哪，"像放一个真正的 1x1 建筑一样"。
     */
    public float landingX(BuildPlan plan) {
        if (shadowShrunk)
            return plan.drawx();
        int[] spot = OreShadow.freeSpotFor(plan.x, plan.y, mineSize());
        return (spot == null ? plan.x : spot[0]) * tilesize;
    }

    public float landingY(BuildPlan plan) {
        if (shadowShrunk)
            return plan.drawy();
        int[] spot = OreShadow.freeSpotFor(plan.x, plan.y, mineSize());
        return (spot == null ? plan.y : spot[1]) * tilesize;
    }

    /**
     * 【用户报的"阴影上建造合体钻头的时候预设直接画 1x1"】预设虚影要和落地后的样子一致：
     * 原版 {@code drawPlanRegion} 画的是 {@code fullIcon} —— 那还是**原钻头**那张 size×size 的整图，
     * 而压格版本是 1x1 的方块，于是虚影要么伸出一格、要么只剩一个 1x1 的空框。
     * 这里压格时按 {@code 1/原size} 缩小画"落地后静止时画的那几张图"（本体 + 各模式的顶盖）。
     */
    @Override
    public void drawPlanRegion(BuildPlan plan, Eachable<BuildPlan> list) {
        float k = planScaleFor(plan.x, plan.y);
        if (k == 1f) {
            super.drawPlanRegion(plan, list);
            return;
        }
        float px = landingX(plan), py = landingY(plan);
        float ox = Draw.xscl, oy = Draw.yscl;
        try {
            Draw.scl(k);
            if (region != null && region.found())
                Draw.rect(region, px, py);
            TextureRegion top = mode == Mode.beam ? topRegionBeam : topRegion;
            if (top != null && top.found())
                Draw.rect(top, px, py, mode == Mode.beam ? plan.rotation * 90f : 0f);
        } catch (Throwable ignored) {
        } finally {
            Draw.scl(ox, oy);
        }
    }

    void drawPlaceDrill(int x, int y, boolean valid) {
        Tile tile = world.tile(x, y);
        if (tile == null)
            return;
        countOre(tile);
        if (returnItem != null) {
            float width = drawPlaceText(
                    Core.bundle.formatFloat("bar.drillspeed", 60f / getDrillTime(returnItem) * returnCount, 2), x, y,
                    valid);
            float dx = x * tilesize + offset - width / 2f - 4f,
                    dy = y * tilesize + offset + mineSize() * tilesize / 2f + 5,
                    s = iconSmall / 4f;
            Draw.mixcol(Color.darkGray, 1f);
            Draw.rect(returnItem.fullIcon, dx, dy - 1, s, s);
            Draw.reset();
            Draw.rect(returnItem.fullIcon, dx, dy, s, s);
            if (drawMineItem) {
                Draw.color(returnItem.color);
                Draw.rect(itemRegion, tile.worldx() + offset, tile.worldy() + offset);
                Draw.color();
            }
        } else {
            Seq<Tile> linked = mineTiles(tile, tempTiles);
            Tile to = null;
            for (int i = 0; i < linked.size; i++) {
                Tile t = linked.get(i);
                if (t.drop() != null
                        && (t.drop().hardness > tier || (blockedItems != null && blockedItems.contains(t.drop())))) {
                    to = t;
                    break;
                }
            }
            Item item = to == null ? null : to.drop();
            if (item != null)
                drawPlaceText(Core.bundle.get("bar.drilltierreq"), x, y, valid);
        }
    }

    void drawPlaceBeam(int x, int y, int rotation, boolean valid) {
        Item item = null, invalidItem = null;
        boolean multiple = false;
        int count = 0;
        for (int i = 0; i < mineSize(); i++) {
            nearbySideMine(x, y, rotation, i, Tmp.p1);
            int j = 0;
            Item found = null;
            for (; j < range; j++) {
                int rx = Tmp.p1.x + Geometry.d4x(rotation) * j, ry = Tmp.p1.y + Geometry.d4y(rotation) * j;
                Tile other = world.tile(rx, ry);
                if (other != null && other.solid()) {
                    Item drop = other.wallDrop();
                    if (drop != null) {
                        if (drop.hardness <= tier && (blockedItems == null || !blockedItems.contains(drop))) {
                            found = drop;
                            count++;
                        } else
                            invalidItem = drop;
                    }
                    break;
                }
            }
            if (found != null) {
                if (item != found && item != null)
                    multiple = true;
                item = found;
            }
            int len = Math.min(j, range - 1);
            Drawf.dashLine(found == null ? Pal.remove : Pal.placing,
                    Tmp.p1.x * tilesize, Tmp.p1.y * tilesize,
                    (Tmp.p1.x + Geometry.d4x(rotation) * len) * tilesize,
                    (Tmp.p1.y + Geometry.d4y(rotation) * len) * tilesize);
        }
        if (item != null) {
            float width = drawPlaceText(Core.bundle.formatFloat("bar.drillspeed", 60f / getDrillTime(item) * count, 2),
                    x, y, valid);
            if (!multiple) {
                float dx = x * tilesize + offset - width / 2f - 4f,
                        dy = y * tilesize + offset + mineSize() * tilesize / 2f + 5, s = iconSmall / 4f;
                Draw.mixcol(Color.darkGray, 1f);
                Draw.rect(item.fullIcon, dx, dy - 1, s, s);
                Draw.reset();
                Draw.rect(item.fullIcon, dx, dy, s, s);
            }
        } else if (invalidItem != null) {
            drawPlaceText(Core.bundle.get("bar.drilltierreq"), x, y, false);
        }
    }

    public float getDrillTime(Item item) {
        if (mode == Mode.burst || mode == Mode.beam)
            return drillTime / drillMultipliers.get(item, 1f);
        return (drillTime + hardnessDrillMultiplier * item.hardness) / drillMultipliers.get(item, 1f);
    }

    protected void countOre(Tile tile) {
        returnItem = null;
        returnCount = 0;
        oreCount.clear();
        itemArray.clear();
        // 【矿物阴影 = 一个资源输出站】
        //   · 矿种（用户 2026-10-09）：**只看这台钻头自己踩着的那一格** ——
        //     "钻头挖的矿只应该有它停留的那一格"。踩在铜上挖铜、踩在铅上挖铅；想两种都挖就在
        //     两种矿上各摆一台（站里共用一口池子）。踩着那格挖不动（tier / 黑名单）就没有。
        //   · 效率：每台仍按"它原来那 size×size 铺满矿物"给整站加一份（mineSize()²）。
        // 不在阴影上的钻头照原版口径（自己的 size×size 地基实际有几格矿算几格）。
        if (OreShadow.inSite(tile)) {
            if (canMine(tile))
                oreCount.increment(getDrop(tile), 0, 1);
            pickDominant();
            if (returnItem != null)
                returnCount = mineSize() * mineSize();
            return;
        }
        Seq<Tile> linked = mineTiles(tile, tempTiles);
        for (int i = 0; i < linked.size; i++) {
            Tile other = linked.get(i);
            if (canMine(other))
                oreCount.increment(getDrop(other), 0, 1);
        }
        pickDominant();
    }

    /** 从 {@link #oreCount} 里挑主导矿（原版排序口径），写进 {@link #returnItem}/{@link #returnCount}。 */
    protected void pickDominant() {
        itemArray.clear();
        for (Item item : oreCount.keys())
            itemArray.add(item);
        itemArray.sort((item1, item2) -> {
            int type = Boolean.compare(!item1.lowPriority, !item2.lowPriority);
            if (type != 0)
                return type;
            int amounts = Integer.compare(oreCount.get(item1, 0), oreCount.get(item2, 0));
            if (amounts != 0)
                return amounts;
            return Integer.compare(item1.id, item2.id);
        });
        if (itemArray.size == 0)
            return;
        returnItem = itemArray.peek();
        returnCount = oreCount.get(itemArray.peek(), 0);
    }

    public Item getDrop(Tile tile) {
        return tile.drop();
    }

    /** 只用来算"这台钻头**自己脚下**那片地里的主导矿"的临时计数（不碰 countOre 的 oreCount）。 */
    protected final ObjectIntMap<Item> displayCounts = new ObjectIntMap<>();

    /**
     * 【用户 2026-10-09】"阴影区域钻头的中心矿物颜色有问题，而且哪怕钻头预设下面没有矿物 a，
     * 也会显示挖掘矿物 a" —— 中心那个矿物色块/选中框应该反映**钻头脚下实际有什么矿**，
     * 和原版口径一致（不是阴影区域的主导矿，也不是站里在产的东西）。
     *
     * @return 自己 {@link #mineSize()} 见方那片地里数量最多的、这台钻头挖得动的矿；没有就 null。
     */
    public @Nullable Item footprintOre(Tile tile, Seq<Tile> tmp) {
        if (tile == null)
            return null;
        displayCounts.clear();
        Seq<Tile> own = mineTiles(tile, tmp);
        for (int i = 0; i < own.size; i++) {
            Tile t = own.get(i);
            if (canMine(t))
                displayCounts.increment(getDrop(t), 0, 1);
        }
        Item best = null;
        int bestN = 0;
        for (Item it : displayCounts.keys()) {
            int n = displayCounts.get(it, 0);
            if (n > bestN) {
                best = it;
                bestN = n;
            }
        }
        return best;
    }

    public boolean canMine(Tile tile) {
        if (tile == null || tile.block().isStatic())
            return false;
        Item drops = tile.drop();
        return drops != null && drops.hardness <= tier && (blockedItems == null || !blockedItems.contains(drops));
    }

    // ==================== Building ====================

    public class CombinedDrillBuild extends Building implements combine.saves.ComboSaved {
        public CombinedDrillBuild comboLeader;
        public Seq<CombinedDrillBuild> comboGroup = new Seq<>();
        public boolean comboDirty = true;
        public float comboTotalLiquidCap = 0f;
        public int comboTotalItemCap = 0;
        public int pendingLeaderPos = -1;
        /** 【矿物阴影】定期对账用的时间戳 / 上次看到的"这片阴影里的钻头数"。 */
        public float shadowCheckAt = 0f;
        public int lastSiteDrills = -1;
        /** 【矿物阴影】本站点产出哪种矿（整片阴影区域的主导矿；不在阴影上时为 null）。 */
        public @Nullable Item siteOre;
        /**
         * 【矿物阴影】这片区域里、**这台钻头挖得动**的矿种（用户 2026-10-09："挖矿看钻头预设能挖的"）——
         * 每一种都按这台钻头"原来大小铺满矿物"的满效率产出，不是按格数比例分摊。
         */
        public final Seq<Item> siteItems = new Seq<>();
        /** 【显示口径】自己脚下那片地里的主导矿（下面没矿就是 null）—— 中心色块/选中框用它。 */
        public @Nullable Item displayOre;

        // Drill / Burst
        public float progress, warmup, timeDrilled, lastDrillSpeed;
        public int dominantItems;
        public Item dominantItem;

        // Burst
        public float smoothProgress, invertTime;

        // Beam
        public Tile[] facing = new Tile[mineSize()];
        public Point2[] lasers = new Point2[mineSize()];
        public @Nullable Item lastItem;
        public float time, boostWarmup;
        public int facingAmount;

        public CombinedDrillBuild leader() {
            if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null)) {
                comboLeader = null;
                comboDirty = true; // FIX: 失联后允许重建组合
            }
            return comboLeader == null ? this : comboLeader;
        }

        public boolean isLeader() {
            return leader() == this;
        }

        public Seq<CombinedDrillBuild> group() {
            CombinedDrillBuild l = leader();
            if (l.comboGroup == null)
                l.comboGroup = new Seq<>();
            return l.comboGroup;
        }

        // ---- 组合重建 ----
        public void rebuildCombo() {
            Seq<CombinedDrillBuild> oldGroup = comboGroup != null ? new Seq<>(comboGroup) : new Seq<>();
            comboGroup = new Seq<>();
            comboGroup.add(this);
            // 分组 BFS 穿过组合节点/连接器：被节点连上 = 效果相当于直接组合（同 LinkWall 语义）
            for (Building b : ComboReflect.linkedReachable(this,
                    o -> o instanceof CombinedDrillBuild other && other.team == team && other.isValid(),
                    (cur, o) -> cur.block.getClass() == o.block.getClass()
                            || ((CombinedDrill) cur.block).allowCrossTypeCombo
                    || ((CombinedDrill) o.block).allowCrossTypeCombo)) {
                if (b != this)
                    comboGroup.add((CombinedDrillBuild) b);
            }
            // 【矿物阴影：一片阴影 = 一个资源生产地】同一片阴影区域里的钻头（哪怕彼此不相邻、
            // 类型不同）并进同一组 —— 共用一口物品池，物品也能从这片区域的任意一边送出去。
            try {
                for (CombinedDrillBuild o : OreShadow.drillsInSiteOf(this)) {
                    if (o != this && o.isValid() && o.team == team && !comboGroup.contains(o, true))
                        comboGroup.add(o);
                }
            } catch (Throwable ignored) {
            }
            CombinedDrillBuild newLeader = this;
            for (CombinedDrillBuild b : comboGroup)
                if (b.isValid() && b.pos() < newLeader.pos())
                    newLeader = b;
            Seq<CombinedDrillBuild> newGroup = new Seq<>(comboGroup);
            newLeader.comboGroup = newGroup;
            for (CombinedDrillBuild b : newGroup) {
                if (b.isValid()) {
                    b.comboLeader = newLeader;
                    b.comboGroup = newGroup;
                    b.comboDirty = false;
                }
            }
            newLeader.comboLeader = null;
            float totalLiqCap = 0f;
            int totalItemCap = 0;
            for (CombinedDrillBuild b : newGroup) {
                if (b.isValid()) {
                    totalLiqCap += ((CombinedDrill) b.block).baseLiquidCapacity;
                    totalItemCap += b.block.itemCapacity;
                }
            }
            for (CombinedDrillBuild b : newGroup) {
                if (b.isValid()) {
                    // 【不共享液体】每台只算自己的液容量（和物品同一口径）
                    b.comboTotalLiquidCap = combine.net.ComboShare.shares(newLeader, combine.net.ComboShare.LIQUIDS)
                            ? totalLiqCap : Math.max(ComboReflect.baseLiquidCap(b), 1f);
                    // 【不共享物品】面板上"物品"没勾 = 每台各留各的模块，容量也只算自己那份
                    b.comboTotalItemCap = combine.net.ComboShare.shares(newLeader, combine.net.ComboShare.ITEMS)
                            ? totalItemCap : Math.max(ComboReflect.baseItemCap(b), 1);
                }
            }
            if (oldGroup.size > newGroup.size)
                splitAssets(oldGroup, newGroup);
            shareModules(newLeader);
            for (CombinedDrillBuild oldMember : oldGroup) {
                if (oldMember != this && oldMember.isValid() && !newGroup.contains(oldMember)) {
                    oldMember.comboLeader = null;
                    oldMember.comboGroup = new Seq<>();
                    oldMember.comboDirty = true;
                    oldMember.comboTotalLiquidCap = 0f;
                    oldMember.comboTotalItemCap = 0;
                }
            }
        }

        public void splitAssets(Seq<CombinedDrillBuild> oldGroup, Seq<CombinedDrillBuild> newGroup) {
            // FIX[双重拆分]: 网络层(ComboNet)可能已把池子按组件拆过一轮、给部分成员发过新模块。
            // 那些份额先并回主池（新组长持有的模块），再由本地逻辑按容量统一重分，
            // 否则两轮拆分各分各的，总额对不上账（物资凭空丢/复制）。
            CombinedDrillBuild newLeader = null;
            for (CombinedDrillBuild b : newGroup)
                if (b.isValid() && (newLeader == null || b.pos() < newLeader.pos()))
                    newLeader = b;
            if (newLeader == null)
                newLeader = this;
            ItemModule poolItems = newLeader.items;
            LiquidModule poolLiquids = newLeader.liquids;
            // 【池子可能不只本组在用】核心库存 / 组合节点接过来的别的组合体也在引用它时，
            // 本地拆分一律不动这口池子（退出成员拿空模块，由 ComboNet 按网络重新分配）。
            // 否则就是从核心库存里搬东西给退出的工厂 —— 用户报的"拆工厂时核心里的东西全没了"。
            // 【不共享物品】没有"公共池"：谁也别并进来、退出成员也别被换成空模块（否则等于丢物品）
            boolean localShareItems = combine.net.ComboShare.shares(newLeader, combine.net.ComboShare.ITEMS);
            boolean poolOurs = localShareItems && !ComboReflect.itemPoolSharedOutside(poolItems, oldGroup);
            // 【不共享液体】没有"公共液池"：退出成员各留各的（否则会被换成空模块 = 丢液体）
            boolean localShareLiquids = combine.net.ComboShare.shares(newLeader, combine.net.ComboShare.LIQUIDS);
            boolean liquidPoolOurs = localShareLiquids && !ComboReflect.liquidPoolSharedOutside(poolLiquids, oldGroup);
            for (CombinedDrillBuild b : oldGroup) {
                if (!b.isValid()) continue;
                if (poolOurs && poolItems != null && b.items != null && b.items != poolItems) {
                    for (Item item : content.items()) {
                        int amt = b.items.get(item);
                        if (amt > 0) { poolItems.add(item, amt); b.items.remove(item, amt); }
                    }
                }
                if (liquidPoolOurs && poolLiquids != null && b.liquids != null && b.liquids != poolLiquids) {
                    for (Liquid liquid : content.liquids()) {
                        float amt = b.liquids.get(liquid);
                        if (amt > 0.001f) { poolLiquids.add(liquid, amt); b.liquids.remove(liquid, amt); }
                    }
                }
            }
            CombinedDrillBuild oldLeader = newLeader;
            ItemModule oldItems = poolItems;
            LiquidModule oldLiquids = poolLiquids;
            Seq<CombinedDrillBuild> kicked = new Seq<>();
            for (CombinedDrillBuild b : oldGroup)
                if (b.isValid() && !newGroup.contains(b))
                    kicked.add(b);
            int oldTotalItemCap = 0;
            float oldTotalLiquidCap = 0f;
            for (CombinedDrillBuild b : oldGroup)
                if (b.isValid()) {
                    oldTotalItemCap += b.block.itemCapacity;
                    oldTotalLiquidCap += ((CombinedDrill) b.block).baseLiquidCapacity;
                }
            int[] itemCaps = new int[kicked.size];
            float[] liquidCaps = new float[kicked.size];
            int kickedTotalItemCap = 0;
            float kickedTotalLiquidCap = 0f;
            for (int i = 0; i < kicked.size; i++) {
                CombinedDrillBuild b = kicked.get(i);
                itemCaps[i] = b.block.itemCapacity;
                liquidCaps[i] = ((CombinedDrill) b.block).baseLiquidCapacity;
                kickedTotalItemCap += itemCaps[i];
                kickedTotalLiquidCap += liquidCaps[i];
            }
            ItemModule[] newItemMods = new ItemModule[kicked.size];
            LiquidModule[] newLiquidMods = new LiquidModule[kicked.size];
            for (int i = 0; i < kicked.size; i++) {
                newItemMods[i] = new ItemModule();
                newLiquidMods[i] = new LiquidModule();
            }
            if (poolOurs && oldItems != null && oldTotalItemCap > 0 && kickedTotalItemCap > 0) {
                int[] kickedAllocated = new int[kicked.size];
                for (Item item : content.items()) {
                    int total = oldItems.get(item);
                    if (total <= 0)
                        continue;
                    int kickedTotalShare = Math.round(total * (float) kickedTotalItemCap / oldTotalItemCap);
                    kickedTotalShare = Math.min(kickedTotalShare, total);
                    int remaining = kickedTotalShare;
                    for (int i = 0; i < kicked.size; i++) {
                        int ideal = (i == kicked.size - 1) ? remaining
                                : Math.round(kickedTotalShare * (float) itemCaps[i] / kickedTotalItemCap);
                        ideal = Math.min(ideal, remaining);
                        int canTake = Math.max(0, itemCaps[i] - kickedAllocated[i]);
                        int share = ideal; // 不做容量硬截断：超出份额暂时超容保留，宁可超容也不丢物品
                        if (share > 0) {
                            newItemMods[i].add(item, share);
                            kickedAllocated[i] += share;
                            remaining -= share;
                        }
                    }
                    oldItems.remove(item, kickedTotalShare - remaining);
                }
            }
            if (liquidPoolOurs && oldLiquids != null && oldTotalLiquidCap > 0.001f && kickedTotalLiquidCap > 0.001f) {
                float[] kickedAllocated = new float[kicked.size];
                for (Liquid liquid : content.liquids()) {
                    float total = oldLiquids.get(liquid);
                    if (total <= 0.001f)
                        continue;
                    float kickedTotalShare = total * kickedTotalLiquidCap / oldTotalLiquidCap;
                    kickedTotalShare = Math.min(kickedTotalShare, total);
                    float remaining = kickedTotalShare;
                    for (int i = 0; i < kicked.size; i++) {
                        float ideal = (i == kicked.size - 1) ? remaining
                                : kickedTotalShare * liquidCaps[i] / kickedTotalLiquidCap;
                        ideal = Math.min(ideal, remaining);
                        float canTake = Math.max(0f, liquidCaps[i] - kickedAllocated[i]);
                        float share = ideal; // 不做容量硬截断：超出份额暂时超容保留，宁可超容也不丢物品
                        if (share > 0.001f) {
                            newLiquidMods[i].add(liquid, share);
                            kickedAllocated[i] += share;
                            remaining -= share;
                        }
                    }
                    oldLiquids.remove(liquid, kickedTotalShare - remaining);
                }
            }
            if (poolOurs && oldItems != null)
                for (CombinedDrillBuild b : newGroup)
                    if (b.isValid())
                        b.items = oldItems;
            if (liquidPoolOurs && oldLiquids != null)
                for (CombinedDrillBuild b : newGroup)
                    if (b.isValid())
                        b.liquids = oldLiquids;
            for (int i = 0; i < kicked.size; i++) {
                CombinedDrillBuild b = kicked.get(i);
                if (poolOurs) b.items = newItemMods[i];
                if (liquidPoolOurs) b.liquids = newLiquidMods[i];
            }
        }

        public void shareModules(CombinedDrillBuild leader) {
            int totalItemCap = leader.comboTotalItemCap;
            float totalLiquidCap = leader.comboTotalLiquidCap;
            if (leader.items == null)
                for (CombinedDrillBuild member : group())
                    if (member.items != null) {
                        leader.items = member.items;
                        break;
                    }
            if (leader.liquids == null)
                for (CombinedDrillBuild member : group())
                    if (member.liquids != null) {
                        leader.liquids = member.liquids;
                        break;
                    }
            // 【池子归属】组里有人拿着"组外也在用"的那份模块（核心库存/网络池）时，
            // 组长必须换成那一份再并池：否则会把核心库存复制进组长自己的模块里
            //（核心没动、工厂也多一份同样的物品），网络层随后把这多出来的一份并回核心 —— 库存凭空翻倍。
            ObjectSet<ItemModule> sharedItemPools = ComboReflect.itemPoolsSharedOutside(group());
            ObjectSet<LiquidModule> sharedLiquidPools = ComboReflect.liquidPoolsSharedOutside(group());
            ItemModule sharedItems = sharedItemPools.isEmpty() ? null : sharedItemPools.first();
            // 【不共享物品】没勾"物品"时谁也别并谁（拆开由 ComboNet 按单台粒度做，见 perMemberGroups）
            boolean localShareItems = combine.net.ComboShare.shares(leader, combine.net.ComboShare.ITEMS);
            if (localShareItems && sharedItems != null) leader.items = sharedItems;
            LiquidModule sharedLiquids = sharedLiquidPools.isEmpty() ? null : sharedLiquidPools.first();
            boolean localShareLiquids = combine.net.ComboShare.shares(leader, combine.net.ComboShare.LIQUIDS);
            if (localShareLiquids && sharedLiquids != null) leader.liquids = sharedLiquids;
            ObjectSet<ItemModule> processedItems = new ObjectSet<>();
            ObjectSet<LiquidModule> processedLiquids = new ObjectSet<>();
            if (localShareItems && leader.items != null) {
                processedItems.add(leader.items);
                for (CombinedDrillBuild member : group()) {
                    if (member != leader && member.isValid() && member.items != null
                            && !processedItems.contains(member.items)
                            // 组外也在用的模块不能"全额并入"（并入不清空源模块 = 凭空多一份）
                            && !sharedItemPools.contains(member.items)) {
                        processedItems.add(member.items);
                        for (Item item : content.items()) {
                            int amt = member.items.get(item);
                            if (amt > 0) {
                                int canAccept = Math.max(0, totalItemCap - leader.items.total());
                                int transfer = amt; // 全额并入：总量必然 ≤ 合并后容量，截断只会丢物品
                                if (transfer > 0)
                                    leader.items.add(item, transfer);
                            }
                        }
                    }
                }
                for (CombinedDrillBuild member : group())
                    if (member.isValid())
                        member.items = leader.items;
                if (leader.items != null) leader.items.stopFlow(); // 组内搬池子不算流量（见 ComboNet.moveItems）
                if (leader.items != null) leader.items.stopFlow(); // 组内搬池子不算流量（见 ComboNet.moveItems）
            }
            if (localShareLiquids && leader.liquids != null) {
                processedLiquids.add(leader.liquids);
                for (CombinedDrillBuild member : group()) {
                    if (member != leader && member.isValid() && member.liquids != null
                            && !processedLiquids.contains(member.liquids)
                            && !sharedLiquidPools.contains(member.liquids)) {
                        processedLiquids.add(member.liquids);
                        for (Liquid liquid : content.liquids()) {
                            float amt = member.liquids.get(liquid);
                            if (amt > 0.001f) {
                                float canAccept = Math.max(0f, totalLiquidCap - leader.liquids.get(liquid));
                                float transfer = amt; // 全额并入：总量必然 ≤ 合并后容量，截断只会丢物品
                                if (transfer > 0.001f)
                                    leader.liquids.add(liquid, transfer);
                            }
                        }
                    }
                }
                for (CombinedDrillBuild member : group())
                    if (member.isValid())
                        member.liquids = leader.liquids;
                if (leader.liquids != null) leader.liquids.stopFlow(); // 组内搬池子不算流量（见 ComboNet.moveItems）
                if (leader.liquids != null) leader.liquids.stopFlow(); // 组内搬池子不算流量（见 ComboNet.moveItems）
            }
        }

        // ---- 生命周期 ----
        @Override
        public void created() {
            super.created();
            comboDirty = true;
        }

        @Override
        public void onProximityUpdate() {
            super.onProximityUpdate();
            comboDirty = true;
            for (CombinedDrillBuild member : group())
                if (member.isValid())
                    member.comboDirty = true;
            CombinedDrill cb = (CombinedDrill) block;
            if (cb.mode == Mode.drill || cb.mode == Mode.burst) {
                countOre(tile);
                dominantItem = returnItem;
                dominantItems = returnCount;
                refreshSiteMix();
                refreshDisplayOre();
            } else if (cb.mode == Mode.beam) {
                updateLasers();
                updateFacing();
            }
        }

        /**
         * 【用户 2026-10-09】"挖矿看钻头预设能挖的"：把这片阴影里**这台钻头挖得动**的矿种抄下来，
         * 产出时每种都按满效率给（不是按格数比例分摊）。
         */
        void refreshSiteMix() {
            siteItems.clear();
            if (!OreShadow.inSite(tile))
                return;
            for (Item it : oreCount.keys())
                if (oreCount.get(it, 0) > 0 && !siteItems.contains(it, true))
                    siteItems.add(it);
        }

        /** 【显示口径】自己脚下那片地里的主导矿（按原版口径；下面没矿就是 null）。 */
        void refreshDisplayOre() {
            CombinedDrill cb = (CombinedDrill) block;
            if (cb.mode == Mode.beam) {
                // 激光钻没有"脚下"，它挖的是前方那几束：显示前方**实际打到**的矿，打不到就不显示
                Item found = null;
                for (int i = 0; i < cb.mineSize() && i < facing.length; i++) {
                    Tile t = facing[i];
                    if (t == null)
                        continue;
                    Item d = t.wallDrop();
                    if (d != null) {
                        found = d;
                        break;
                    }
                }
                displayOre = found;
                return;
            }
            // 阴影上：中心就画它踩着的那一格（和"挖的矿"同一个口径）
            if (OreShadow.inSite(tile)) {
                displayOre = dominantItem;
                return;
            }
            displayOre = cb.footprintOre(tile, cb.tempTiles);
        }

        @Override
        public void onRemoved() {
            Seq<CombinedDrillBuild> members = new Seq<>(group());
            boolean wasLeader = isLeader();
            ItemModule oldItems = this.items;
            LiquidModule oldLiquids = this.liquids;
            // 【别动不属于本组的池子】核心库存/别的组合体也在用的那份模块：既不能按容量
            // "分一份给幸存者"（那是从核心库存里搬东西），也不能复制一份（核心库存会凭空翻倍）。
            boolean poolOurs = !ComboReflect.itemPoolSharedOutside(oldItems, members);
            boolean liquidPoolOurs = !ComboReflect.liquidPoolSharedOutside(oldLiquids, members);
            if (!wasLeader) {
                if (items != null) {
                    boolean shared = false;
                    for (CombinedDrillBuild member : members)
                        if (member != this && member.isValid() && member.items == this.items) {
                            shared = true;
                            break;
                        }
                    if (shared)
                        items = new ItemModule();
                }
                if (liquids != null) {
                    boolean shared = false;
                    for (CombinedDrillBuild member : members)
                        if (member != this && member.isValid() && member.liquids == this.liquids) {
                            shared = true;
                            break;
                        }
                    if (shared)
                        liquids = new LiquidModule();
                }
            }
            if (wasLeader) {
                Seq<CombinedDrillBuild> survivors = new Seq<>();
                for (CombinedDrillBuild b : members)
                    if (b != this && b.isValid())
                        survivors.add(b);
                int[] itemCaps = new int[survivors.size];
                float[] liquidCaps = new float[survivors.size];
                int totalItemCap = 0;
                float totalLiquidCap = 0f;
                for (int i = 0; i < survivors.size; i++) {
                    CombinedDrillBuild b = survivors.get(i);
                    itemCaps[i] = b.block.itemCapacity;
                    liquidCaps[i] = ((CombinedDrill) b.block).baseLiquidCapacity;
                    totalItemCap += itemCaps[i];
                    totalLiquidCap += liquidCaps[i];
                }
                ItemModule[] itemMods = new ItemModule[survivors.size];
                LiquidModule[] liquidMods = new LiquidModule[survivors.size];
                for (int i = 0; i < survivors.size; i++) {
                    itemMods[i] = new ItemModule();
                    liquidMods[i] = new LiquidModule();
                }
                if (poolOurs && oldItems != null && totalItemCap > 0) {
                    int[] allocated = new int[survivors.size];
                    for (Item item : content.items()) {
                        int total = oldItems.get(item);
                        if (total <= 0)
                            continue;
                        int remaining = total;
                        for (int i = 0; i < survivors.size; i++) {
                            int ideal = (i == survivors.size - 1) ? remaining
                                    : Math.round(total * (float) itemCaps[i] / totalItemCap);
                            ideal = Math.min(ideal, remaining);
                            int canTake = Math.max(0, itemCaps[i] - allocated[i]);
                            int share = ideal; // 不做容量硬截断：超出份额暂时超容保留，宁可超容也不丢物品
                            if (share > 0) {
                                itemMods[i].add(item, share);
                                allocated[i] += share;
                                remaining -= share;
                            }
                        }
                    }
                }
                if (liquidPoolOurs && oldLiquids != null && totalLiquidCap > 0.001f) {
                    float[] allocated = new float[survivors.size];
                    for (Liquid liquid : content.liquids()) {
                        float total = oldLiquids.get(liquid);
                        if (total <= 0.001f)
                            continue;
                        float remaining = total;
                        for (int i = 0; i < survivors.size; i++) {
                            float ideal = (i == survivors.size - 1) ? remaining
                                    : total * liquidCaps[i] / totalLiquidCap;
                            ideal = Math.min(ideal, remaining);
                            float canTake = Math.max(0f, liquidCaps[i] - allocated[i]);
                            float share = ideal; // 不做容量硬截断：超出份额暂时超容保留，宁可超容也不丢物品
                            if (share > 0.001f) {
                                liquidMods[i].add(liquid, share);
                                allocated[i] += share;
                                remaining -= share;
                            }
                        }
                    }
                }
                for (int i = 0; i < survivors.size; i++) {
                    CombinedDrillBuild b = survivors.get(i);
                    b.items = itemMods[i];
                    b.liquids = liquidMods[i];
                    b.comboLeader = null;
                    b.comboGroup = new Seq<>();
                    b.comboDirty = true;
                    b.comboTotalLiquidCap = 0f;
                    b.comboTotalItemCap = 0;
                }
            } else {
                CombinedDrillBuild leader = leader();
                if (leader != null && leader.isValid() && leader != this)
                    leader.comboDirty = true;
            }
            comboLeader = null;
            comboGroup = new Seq<>();
            comboDirty = false;
            comboTotalLiquidCap = 0f;
            comboTotalItemCap = 0;
            super.onRemoved();
        }

        // ---- 核心更新 ----
        @Override
        public void updateTile() {
          if (!combine.util.ComboTeams.playerTeam(team)) { super.updateTile(); return; }   // 只玩家组合开关：AI 敌人的建筑按原版跑，不参与组合那套
            if (pendingLeaderPos != -1) {
                Building b = world.build(pendingLeaderPos);
                if (b instanceof CombinedDrillBuild leaderBuild && leaderBuild.isValid() && leaderBuild.team == team) {
                    comboLeader = leaderBuild;
                    if (leaderBuild.items != null)
                        items = leaderBuild.items;
                    if (leaderBuild.liquids != null)
                        liquids = leaderBuild.liquids;
                } else {
                    comboLeader = null;
                }
                pendingLeaderPos = -1;
                comboDirty = true;
            }
            if (isLeader() && comboDirty)
                rebuildCombo();

            // 【矿物阴影】区域是玩家后来框出来的、边界上的建筑也会变：定期对一次账
            //（重新算"整片区域的矿"、并在这一片的钻头数变了时让组长重建分组）。
            // 0.25s 一次、只有组长扫站点，代价很小。
            if (shadowCheckAt <= arc.util.Time.time) {
                shadowCheckAt = arc.util.Time.time + 0.25f;
                if (OreShadow.inSite(tile)) {
                    countOre(tile);
                    siteOre = returnItem;
                    refreshSiteMix();
                    refreshDisplayOre();
                    if (isLeader()) {
                        int n = OreShadow.drillsInSiteOf(this).size;
                        if (n != lastSiteDrills) {
                            lastSiteDrills = n;
                            comboDirty = true;
                        }
                    }
                } else if (lastSiteDrills != -1) {
                    lastSiteDrills = -1;
                    siteOre = null;
                    displayOre = null;
                } else {
                    siteOre = null;
                    displayOre = null;
                }
            }

            // FIX: 每帧强制截断超出的液体
            if (liquids != null && comboTotalLiquidCap > 0.001f) {
                for (Liquid l : content.liquids()) {
                    float amt = liquids.get(l);
                    if (amt > comboTotalLiquidCap + 0.001f)
                        liquids.remove(l, amt - comboTotalLiquidCap);
                }
            }

            CombinedDrill cb = (CombinedDrill) block;
            if (cb.mode == Mode.beam)
                updateBeam();
            else if (cb.mode == Mode.burst)
                updateBurst();
            else
                updateDrill();
        }

        void updateDrill() {
            CombinedDrill cb = (CombinedDrill) block;
            // FIX(混合矿堵塞)：逐类型倒出池内所有矿物，不再只倒 dominant
            // FIX[搬运粒度]: 组合体共用池子，按每 tick 搬运，别被原版 5 tick 的粒度卡住
            if (timer(timerDump, CombinedCrafter.comboDumpInterval / timeScale)) {
                for (Item item : content.items())
                    if (items.get(item) > 0)
                        dump(item);
            }
            if (dominantItem == null)
                return;
            timeDrilled += warmup * delta();
            float delay = getDrillTime(dominantItem);
            // FIX(per-type)：只看 dominantItem 自身余量 —— 另一种矿满不影响本矿挖掘
            // 【阴影区域：两种矿都要挖】池子按"整站没满"判定，产出轮流按区域矿种比例出
            boolean site = !siteItems.isEmpty();
            boolean room = site ? items.total() < comboTotalItemCap : items.get(dominantItem) < comboTotalItemCap;
            if (room && dominantItems > 0 && efficiency > 0) {
                float speed = Mathf.lerp(1f, cb.liquidBoostIntensity, optionalEfficiency) * efficiency;
                lastDrillSpeed = (speed * dominantItems * warmup * (site ? Math.max(siteItems.size, 1) : 1)) / delay;
                warmup = Mathf.approachDelta(warmup, speed, cb.warmupSpeed);
                progress += delta() * dominantItems * speed * warmup;
                if (Mathf.chanceDelta(cb.updateEffectChance * warmup))
                    cb.updateEffect.at(x + Mathf.range(cb.mineSize() * 2f), y + Mathf.range(cb.mineSize() * 2f));
            } else {
                lastDrillSpeed = 0f;
                warmup = Mathf.approachDelta(warmup, 0f, cb.warmupSpeed);
                return;
            }
            if (dominantItems > 0 && progress >= delay
                    && (site ? items.total() < comboTotalItemCap : items.get(dominantItem) < comboTotalItemCap)) {
                int amount = (int) (progress / delay);
                for (int i = 0; i < amount; i++) {
                    if (!site) {
                        offload(dominantItem);
                        continue;
                    }
                    // 站里的每一种矿都按这台钻头的满效率产（不是按比例分摊）
                    for (int k = 0; k < siteItems.size; k++)
                        offload(siteItems.get(k));
                }
                progress %= delay;
                if (wasVisible && Mathf.chanceDelta(cb.drillEffectChance * warmup))
                    cb.drillEffect.at(x + Mathf.range(cb.drillEffectRnd), y + Mathf.range(cb.drillEffectRnd),
                            dominantItem.color);
            }
        }

        void updateBurst() {
            CombinedDrill cb = (CombinedDrill) block;
            if (dominantItem == null)
                return;
            if (invertTime > 0f)
                invertTime -= delta() / cb.invertedTime;
            // FIX(混合矿堵塞)：逐类型倒出池内所有矿物
            // FIX[搬运粒度]: 组合体共用池子，按每 tick 搬运，别被原版 5 tick 的粒度卡住
            if (timer(timerDump, CombinedCrafter.comboDumpInterval / timeScale)) {
                for (Item item : content.items())
                    if (items.get(item) > 0)
                        dump(item);
            }
            if (dominantItem == null)
                return;
            float drillTime = getDrillTime(dominantItem);
            smoothProgress = Mathf.lerpDelta(smoothProgress, progress / (drillTime - 20f), 0.1f);
            // FIX(per-type)
            if (items.get(dominantItem) <= comboTotalItemCap - dominantItems && dominantItems > 0 && efficiency > 0) {
                warmup = Mathf.approachDelta(warmup, progress / drillTime, 0.01f);
                float speed = Mathf.lerp(1f, cb.liquidBoostIntensity, optionalEfficiency) * efficiency;
                timeDrilled += cb.speedCurve.apply(progress / drillTime) * speed;
                lastDrillSpeed = 1f / drillTime * speed * dominantItems;
                progress += delta() * speed;
            } else {
                warmup = Mathf.approachDelta(warmup, 0f, 0.01f);
                lastDrillSpeed = 0f;
                return;
            }
            if (dominantItems > 0 && progress >= drillTime
                    && (siteItems.isEmpty() ? items.get(dominantItem) < comboTotalItemCap
                            : items.total() < comboTotalItemCap)) {
                for (int i = 0; i < dominantItems; i++) {
                    if (siteItems.isEmpty()) {
                        offload(dominantItem);
                        continue;
                    }
                    for (int k = 0; k < siteItems.size; k++)
                        offload(siteItems.get(k));
                }
                invertTime = 1f;
                progress %= drillTime;
                if (wasVisible) {
                    Effect.shake(cb.shake, cb.shake, this);
                    cb.drillSound.at(x, y, 1f + Mathf.range(cb.drillSoundPitchRand), cb.drillSoundVolume);
                    cb.drillEffect.at(x + Mathf.range(cb.drillEffectRnd), y + Mathf.range(cb.drillEffectRnd),
                            dominantItem.color);
                }
            }
        }

        void updateBeam() {
            CombinedDrill cb = (CombinedDrill) block;
            if (lasers[0] == null)
                updateLasers();
            warmup = Mathf.approachDelta(warmup, Mathf.num(efficiency > 0), 1f / 60f);
            updateFacing();
            // 【资源输出站】同站口径：激光钻也按"原来 size 条光束铺满矿"算 ——
            // 束数取 mineSize()、矿种取整片阴影区域的主导矿（不看前方实际有没有矿墙）。
            boolean site = siteOre != null;
            if (site) {
                facingAmount = cb.mineSize();
                lastItem = siteOre;
            }
            float multiplier = Mathf.lerp(1f, cb.optionalBoostIntensity, optionalEfficiency);
            float drillTime = getDrillTime(lastItem);
            boostWarmup = Mathf.lerpDelta(boostWarmup, optionalEfficiency, 0.1f);
            lastDrillSpeed = (facingAmount * multiplier * timeScale) / drillTime * efficiency;
            time += edelta() * multiplier;
            if (time >= drillTime) {
                // 【区块产出统计】必须走原版 offload()：它会先 produced(item, 1) 计入
                // sector.info.handleProduction（区块产量 rawProduction）。直接 items.add() 的话物品进池了、
                // 区块产量却恒为 0（用户报的"后台物品增长没算上"）。
                if (site) {
                    for (int i = 0; i < cb.mineSize(); i++)
                        for (int k = 0; k < siteItems.size; k++)
                            if (efficiency > 0f && items.total() < comboTotalItemCap)
                                offload(siteItems.get(k));
                } else
                    for (Tile tile : facing) {
                        Item drop = tile == null ? null : tile.wallDrop();
                        if (drop != null && efficiency > 0f && items.get(drop) < comboTotalItemCap)
                            offload(drop);
                    }
                time %= drillTime;
            }
            // FIX(混合矿堵塞)：逐类型倒出池内所有矿物
            // FIX[搬运粒度]: 组合体共用池子，按每 tick 搬运，别被原版 5 tick 的粒度卡住
            if (timer(timerDump, CombinedCrafter.comboDumpInterval / timeScale)) {
                for (Item item : content.items())
                    if (items.get(item) > 0)
                        dump(item);
            }
        }

        // Beam 辅助
        protected void updateLasers() {
            CombinedDrill cb = (CombinedDrill) block;
            for (int i = 0; i < cb.mineSize(); i++) {
                if (lasers[i] == null)
                    lasers[i] = new Point2();
                cb.nearbySideMine(tileX(), tileY(), rotation, i, lasers[i]);
            }
        }

        protected void updateFacing() {
            lastItem = null;
            boolean multiple = false;
            int dx = Geometry.d4x(rotation), dy = Geometry.d4y(rotation);
            facingAmount = 0;
            CombinedDrill cb = (CombinedDrill) block;
            for (int p = 0; p < cb.mineSize(); p++) {
                Point2 l = lasers[p];
                Tile dest = null;
                for (int i = 0; i < cb.range; i++) {
                    int rx = l.x + dx * i, ry = l.y + dy * i;
                    Tile other = world.tile(rx, ry);
                    if (other != null) {
                        if (other.solid()) {
                            Item drop = other.wallDrop();
                            if (drop != null && drop.hardness <= cb.tier
                                    && (cb.blockedItems == null || !cb.blockedItems.contains(drop))) {
                                facingAmount++;
                                if (lastItem != drop && lastItem != null)
                                    multiple = true;
                                lastItem = drop;
                                dest = other;
                            }
                            break;
                        }
                    }
                }
                facing[p] = dest;
            }
            if (multiple)
                lastItem = null;
        }

        // ---- 物品/液体交互 ----
        @Override
        public boolean acceptItem(Building source, Item item) {
            return false;
        }

        @Override
        public int getMaximumAccepted(Item item) {
            return Math.max(comboTotalItemCap, 1);
        }

        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            if (!block.hasLiquids)
                return false;
            boolean needed = ComboReflect.groupConsumesLiquid(this, liquid);
            return needed && liquids != null && liquids.get(liquid) < comboTotalLiquidCap - 0.001f;
        }

        @Override
        public void handleLiquid(Building source, Liquid liquid, float amount) {
            if (amount <= 0.001f)
                return;
            float canAccept = Math.max(0f, comboTotalLiquidCap - liquids.get(liquid));
            float actual = Math.min(amount, canAccept);
            if (actual > 0.001f)
                liquids.add(liquid, actual);
            // FIX: 将未接收的液体退回源端，防止因 block.liquidCapacity=9999f 导致源端过度扣除
            float refund = amount - actual;
            if (refund > 0.001f && source != null && source.liquids != null)
                source.liquids.add(liquid, refund);
        }

        @Override
        public boolean shouldConsume() {
            CombinedDrill cb = (CombinedDrill) block;
            // FIX(per-type)：只看对应矿种自身的余量，某种矿满不停其他矿
            if (cb.mode == Mode.beam) {
                if (!enabled || facingAmount <= 0)
                    return false;
                for (Tile t : facing) {
                    Item drop = t == null ? null : t.wallDrop();
                    if (drop != null && items.get(drop) < comboTotalItemCap)
                        return true;
                }
                return false;
            }
            if (cb.mode == Mode.burst)
                return enabled && dominantItem != null
                        && items.get(dominantItem) <= comboTotalItemCap - dominantItems;
            return enabled && dominantItem != null && items.get(dominantItem) < comboTotalItemCap;
        }

        @Override
        public boolean shouldAmbientSound() {
            // 禁用环境音循环，避免触发 SoundControl 环境音线程在 Android 上的
            // IllegalThreadStateException（Thread.start 崩溃）
            return false;
        }

        @Override
        public float ambientVolume() {
            return 0f; // 环境音已禁用
        }

        @Override
        public void drawSelect() {
            CombinedDrill cb = (CombinedDrill) block;
            drawItemSelection(displayOre);
        }

        @Override
        public void pickedUp() {
            dominantItem = null;
            lastItem = null;
        }

        @Override
        public Object senseObject(LAccess sensor) {
            if (sensor == LAccess.firstItem) {
                CombinedDrill cb = (CombinedDrill) block;
                return cb.mode == Mode.beam ? lastItem : dominantItem;
            }
            return super.senseObject(sensor);
        }

        @Override
        public float progress() {
            CombinedDrill cb = (CombinedDrill) block;
            if (cb.mode == Mode.beam)
                return 0f;
            return dominantItem == null ? 0f : Mathf.clamp(progress / getDrillTime(dominantItem));
        }

        @Override
        public double sense(LAccess sensor) {
            if (sensor == LAccess.progress) {
                CombinedDrill cb = (CombinedDrill) block;
                if (cb.mode == Mode.beam)
                    return 0;
                if (dominantItem != null)
                    return progress;
            }
            return super.sense(sensor);
        }

        // ---- 绘制 ----
        @Override
        public void drawCracks() {
        }

        /**
         * 【矿物阴影：物品能从这片阴影的任意一边送出去】
         *
         * <p>原版 {@code dump} 只看自己那几格旁边的邻居；阴影上的钻头改成从"整片阴影区域边界上
         * 所有能收物品的建筑"里挑一个送（区域是不规则形状也一样 —— 逐格扫边界）。
         * 不在阴影上的钻头照原版。列表有缓存（区域版本变了 / 每 0.25s 重算），拆掉的建筑不会留在里面。
         */
        @Override
        public boolean dump(Item todump) {
            if (!OreShadow.inSite(tile))
                return super.dump(todump);
            // 先走原版那条：紧挨着自己的传送带 / 容器（现在钻头压成 1x1，玩家很可能把它们
            // 直接铺在阴影区域**里面**）——
            // 用户报的"阴影的物品输出不正常"就是这条原来被整条跳过了。
            if (super.dump(todump))
                return true;
            if (!block.hasItems || items == null || items.total() == 0
                    || (todump != null && !items.has(todump)))
                return false;
            Seq<Building> outs = OreShadow.outputsOf(tile);
            if (outs.isEmpty())
                return false;
            int dump = cdump;
            for (int i = 0; i < outs.size; i++) {
                Building other = outs.get((i + dump) % outs.size);
                if (other == null || !other.isValid())
                    continue;
                if (handOff(other, todump)) {
                    incrementDump(outs.size);
                    return true;
                }
                // 传送带这类"只收挨着自己那一格递过来的东西"：钻头在区域里可能离它好几格，
                // 直接递会被拒。临时把"来源"挪到这片阴影紧挨着它的那一格再递一次，
                // 物品就正常从区域那一边出去了（物品数不凭空多/少）。
                Tile proxy = OreShadow.adjacentSiteTile(other);
                if (proxy != null && proxy != tile) {
                    Tile back = tile;
                    tile = proxy;
                    boolean ok, forced;
                    try {
                        ok = handOff(other, todump);
                        // 方向不对（传送带只认"背后"那一格）时只要它还有位置就强行喂一个：
                        // 站里的东西出了区域的边，具体往哪边流由那条传送带自己的朝向决定。
                        forced = !ok && todump != null && forceHandOff(other, todump);
                    } finally {
                        tile = back;
                    }
                    if (ok || forced) {
                        incrementDump(outs.size);
                        return true;
                    }
                }
                incrementDump(outs.size);
            }
            return false;
        }

        /** 试着把池子里的物品交给 {@code other}（原版 acceptItem/canDump/handleItem 三步）。 */
        boolean handOff(Building other, Item todump) {
            if (todump != null) {
                if (other.acceptItem(this, todump) && canDump(other, todump)) {
                    other.handleItem(this, todump);
                    items.remove(todump, 1);
                    return true;
                }
                return false;
            }
            for (Item item : content.items()) {
                if (!items.has(item))
                    continue;
                if (other.acceptItem(this, item) && canDump(other, item)) {
                    other.handleItem(this, item);
                    items.remove(item, 1);
                    return true;
                }
            }
            return false;
        }

        /** 目标还有位置、而且"来源那一格"和它相邻时，不管它的朝向直接递一个过去。 */
        boolean forceHandOff(Building other, Item item) {
            if (other == null || other.items == null || !other.block.hasItems)
                return false;
            if (other.items.total() >= other.getMaximumAccepted(item))
                return false;
            if (mindustry.world.Edges.getFacingEdge(tile, other.tile) == null)
                return false;
            if (!canDump(other, item))
                return false;
            other.handleItem(this, item);
            items.remove(item, 1);
            return true;
        }

        public void drawDefaultCracks() {
            super.drawCracks();
        }

        @Override
        public void draw() {
            CombinedDrill cb = (CombinedDrill) block;
            // 【压成 1x1 的钻头】把原来 size×size 的贴图缩进 1 格（普通钻头 k=1 不动）。
            // 绝对长度（爆钻的箭羽偏移）另外乘同一个系数：Draw.scl 只缩尺寸、不缩那些直接加在坐标上的偏移。
            float k = cb.cellDrawScale();
            boolean scaled = k != 1f;
            // 【必须用 Draw.scl(k) 那个方法】arc 的 `Draw.scl` **字段**没有任何人读取（rect 用的是
            // xscl/yscl），以前只写字段 = 贴图根本没缩，1x1 的钻头把原钻头 size×size 的大图直接铺出来
            //（用户报的"预设/钻头画出来还是原来那么大"）。这里改成和其它压格方块（SuperTurret）
            // 同一个口径：临时乘 xscl/yscl，画完还原。
            float oldX = Draw.xscl, oldY = Draw.yscl, oldSpacing = cb.arrowSpacing, oldOffset = cb.arrowOffset;
            if (scaled) {
                Draw.scl(k);
                cb.arrowSpacing = oldSpacing * k;
                cb.arrowOffset = oldOffset * k;
            }
            try {
                if (cb.mode == Mode.beam)
                    drawBeam();
                else if (cb.mode == Mode.burst)
                    drawBurst();
                else
                    drawDrill();
            } finally {
                if (scaled) {
                    cb.arrowSpacing = oldSpacing;
                    cb.arrowOffset = oldOffset;
                    Draw.scl(oldX, oldY);
                }
            }
        }

        void drawDrill() {
            CombinedDrill cb = (CombinedDrill) block;
            float s = 0.3f, ts = 0.6f;
            Draw.rect(cb.region, x, y);
            Draw.z(Layer.blockCracks);
            drawDefaultCracks();
            Draw.z(Layer.blockAfterCracks);
            if (cb.drawRim) {
                Draw.color(cb.heatColor);
                Draw.alpha(warmup * ts * (1f - s + Mathf.absin(Time.time, 3f, s)));
                Draw.blend(Blending.additive);
                Draw.rect(cb.rimRegion, x, y);
                Draw.blend();
                Draw.color();
            }
            if (cb.drawSpinSprite)
                Drawf.spinSprite(cb.rotatorRegion, x, y, timeDrilled * cb.rotateSpeed);
            else
                Draw.rect(cb.rotatorRegion, x, y, timeDrilled * cb.rotateSpeed);
            Draw.rect(cb.topRegion, x, y);
            // 【显示口径】中心那个矿物色块画的是"自己脚下实际有的矿"（阴影里也一样），
            // 不是站在阴影上就照着区域主导矿画（用户报的"下面没有矿物 a 也显示挖矿物 a"）。
            if (displayOre != null && cb.drawMineItem) {
                Draw.color(displayOre.color);
                Draw.rect(cb.itemRegion, x, y);
                Draw.color();
            }
        }

        void drawBurst() {
            CombinedDrill cb = (CombinedDrill) block;
            Draw.rect(cb.region, x, y);
            drawDefaultCracks();
            Draw.rect(cb.topRegion, x, y);
            if (invertTime > 0 && cb.topInvertRegion.found()) {
                Draw.alpha(Interp.pow3Out.apply(invertTime));
                Draw.rect(cb.topInvertRegion, x, y);
                Draw.color();
            }
            if (displayOre != null && cb.drawMineItem) {
                Draw.color(displayOre.color);
                Draw.rect(cb.itemRegion, x, y);
                Draw.color();
            }
            float fract = smoothProgress;
            Draw.color(cb.arrowColor);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < cb.arrows; j++) {
                    float arrowFract = (cb.arrows - 1 - j);
                    float a = Mathf.clamp(fract * cb.arrows - arrowFract);
                    Tmp.v1.trns(i * 90 + 45, j * cb.arrowSpacing + cb.arrowOffset);
                    Draw.z(Layer.block);
                    Draw.color(cb.baseArrowColor, cb.arrowColor, a);
                    Draw.rect(cb.arrowRegion, x + Tmp.v1.x, y + Tmp.v1.y, i * 90);
                    Draw.color(cb.arrowColor);
                    if (cb.arrowBlurRegion.found()) {
                        Draw.z(Layer.blockAdditive);
                        Draw.blend(Blending.additive);
                        Draw.alpha(Mathf.pow(a, 10f));
                        Draw.rect(cb.arrowBlurRegion, x + Tmp.v1.x, y + Tmp.v1.y, i * 90);
                        Draw.blend();
                    }
                }
            }
            Draw.color();
            if (cb.glowRegionBurst.found())
                Drawf.additive(cb.glowRegionBurst,
                        Tmp.c2.set(cb.glowColorBurst).a(Mathf.pow(fract, 3f) * cb.glowColorBurst.a), x, y);
        }

        void drawBeam() {
            CombinedDrill cb = (CombinedDrill) block;
            Draw.rect(cb.region, x, y);
            Draw.rect(cb.topRegionBeam, x, y, rotdeg());
            if (isPayload())
                return;
            var dir = Geometry.d4(rotation);
            int ddx = Geometry.d4x(rotation + 1), ddy = Geometry.d4y(rotation + 1);
            Rand rand = new Rand();
            for (int i = 0; i < cb.mineSize(); i++) {
                Tile face = facing[i];
                if (face != null) {
                    Item drop = face.wallDrop();
                    if (drop == null)
                        continue;
                    Point2 p = lasers[i];
                    float lx = face.worldx() - (dir.x / 2f) * tilesize, ly = face.worldy() - (dir.y / 2f) * tilesize;
                    float width = (cb.laserWidth
                            + Mathf.absin(Time.time + i * 5 + (id % 9) * 9, cb.glowScl, cb.pulseIntensity)) * warmup;
                    Draw.z(Layer.power - 1);
                    Draw.mixcol(cb.glowColorBeam,
                            Mathf.absin(Time.time + i * 5 + id * 9, cb.glowScl, cb.glowIntensity));
                    if (Math.abs(p.x - face.x) + Math.abs(p.y - face.y) == 0) {
                        Draw.scl(width);
                        if (boostWarmup < 0.99f) {
                            Draw.alpha(1f - boostWarmup);
                            Draw.rect(cb.laserCenter, lx, ly);
                        }
                        if (boostWarmup > 0.01f) {
                            Draw.alpha(boostWarmup);
                            Draw.rect(cb.laserCenterBoost, lx, ly);
                        }
                        Draw.scl();
                    } else {
                        float lsx = (p.x - dir.x / 2f) * tilesize, lsy = (p.y - dir.y / 2f) * tilesize;
                        if (boostWarmup < 0.99f) {
                            Draw.alpha(1f - boostWarmup);
                            Drawf.laser(cb.laser, cb.laserEnd, lsx, lsy, lx, ly, width);
                        }
                        if (boostWarmup > 0.001f) {
                            Draw.alpha(boostWarmup);
                            Drawf.laser(cb.laserBoost, cb.laserEndBoost, lsx, lsy, lx, ly, width);
                        }
                    }
                    Draw.color();
                    Draw.mixcol();
                    if (Lod.l2) {
                        Draw.z(Layer.effect);
                        Lines.stroke(warmup);
                        rand.setState(i, id);
                        Color col = drop.color;
                        Color spark = Tmp.c3.set(cb.sparkColor).lerp(cb.boostHeatColor, boostWarmup);
                        for (int j = 0; j < cb.sparks; j++) {
                            float fin = (Time.time / cb.sparkLife + rand.random(cb.sparkRecurrence + 1f))
                                    % cb.sparkRecurrence;
                            float or = rand.range(2f);
                            Tmp.v1.set(cb.sparkRange * fin, 0).rotate(rotdeg() + rand.range(cb.sparkSpread));
                            Color result = Tmp.c1.set(spark).lerp(col, fin);
                            Draw.color(result.r, result.g, result.b, result.a * Lod.alpha2);
                            float px = Tmp.v1.x, py = Tmp.v1.y;
                            if (fin <= 1f)
                                Lines.lineAngle(lx + px + or * ddx, ly + py + or * ddy, Angles.angle(px, py),
                                        Mathf.slope(fin) * cb.sparkSize);
                        }
                        Draw.reset();
                    }
                }
            }
            if (cb.glowRegionBeam.found()) {
                Draw.z(Layer.blockAdditive);
                Draw.blend(Blending.additive);
                Draw.color(Tmp.c1.set(cb.heatColorBeam).lerp(cb.boostHeatColor, boostWarmup), warmup
                        * (cb.heatColorBeam.a * (1f - cb.heatPulse + Mathf.absin(cb.heatPulseScl, cb.heatPulse))));
                Draw.rect(cb.glowRegionBeam, x, y, rotdeg());
                Draw.blend();
                Draw.color();
            }
            Draw.blend();
            Draw.reset();
        }

        // ---- 显示面板 ----
        @Override
        public void display(Table table) {
          if (!ComboUi.detail()) { super.display(table); return; }
          // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
          ComboUi.safe("combineddrill:display", () -> displayInner(table));
        }

        void displayInner(Table table) {
            table.table(cont -> {
                cont.top().left();
                cont.defaults().growX().left();

                cont.table(t -> {
                    t.left();
                    TextureRegion icon = block.getDisplayIcon(tile);
                    if (icon == null)
                        icon = Core.atlas.find("clear");
                    t.add(new Image(icon)).size(8 * 4);
                    int count = ComboNet.displayMembers(this, group().size).size;
                    String title = count > 1
                            ? "[accent]组合钻机[] x" + count + "\n" + block.getDisplayName(tile)
                            : block.getDisplayName(tile);
                    t.labelWrap(title).left().width(160f).padLeft(4);
                }).growX().left();
                cont.row();

                if (team != mindustry.Vars.player.team())
                    return;

                Table barsTable = new Table();
                barsTable.left();
                barsTable.update(() -> {
                    barsTable.clearChildren();
                    barsTable.defaults().width(ComboUi.COMPOSITION_WIDTH).height(18f).pad(4);
                    buildComboBars(barsTable);
                });
                cont.add(barsTable).growX().left();
                cont.row();

                Table comboIO = new Table();
                comboIO.left();
                comboIO.update(() -> {
                    comboIO.clearChildren();
                    buildComboIO(comboIO);
                });
                cont.add(comboIO).growX().left();
                cont.row();

                Table localIO = new Table();
                localIO.left();
                localIO.update(() -> {
                    localIO.clearChildren();
                    buildLocalIO(localIO);
                });
                cont.add(localIO).growX().left();
            }).width(260f).left();
                }

        public void buildComboBars(Table table) {
            if (!Mathf.zero(block.health, 0.001f)) {
                final float h = health, mh = maxHealth;
                table.add(new Bar(
                        () -> Core.bundle.get("stat.health", "Health") + " " + (int) Math.max(h, 0),
                        () -> Pal.health,
                        () -> Mathf.clamp(h / mh)));
                table.row();
            }
            final float ls = lastDrillSpeed;
            table.add(new Bar(
                    () -> Core.bundle.format("bar.drillspeed", Strings.fixed(ls * 60f * timeScale(), 2)),
                    () -> Pal.ammo,
                    () -> warmup));
            table.row();
            // 物品池 / 液体池 / 电力条都挪到悬浮面板了（用户要求 display() 只留组合体构成）；
            // 这里只留"过程量"条（血量 / 本机钻速）。
        }

        public void buildComboIO(Table table) {
            table.left();
            ComboUi.addComposition(table, this, group().size);

            // 【每种物品的整组挖速】用户要求："给组合钻头的 display() 加上每种物品的挖掘
            // 总速率，要统计组合体里所有组合钻头"。每台各有自己的矿种/覆盖格数，整组产量
            // 按矿种分别累加（件/秒），一行一样、超宽换行。
            ObjectFloatMap<Item> rates = new ObjectFloatMap<>();
            groupDrillRates(rates);
            if (rates.size > 0) {
                StringBuilder sb = new StringBuilder();
                for (Item item : content.items()) {
                    float r = rates.get(item, 0f);
                    if (r <= 0.001f)
                        continue;
                    if (sb.length() > 0)
                        sb.append("  ");
                    sb.append(item.localizedName == null ? item.name : item.localizedName)
                            .append(' ').append(Strings.fixed(r, 2)).append("/s");
                }
                if (sb.length() > 0) {
                    table.add("[lightgray]挖速: " + sb + "[]").left().width(ComboUi.COMPOSITION_WIDTH).wrap();
                    table.row();
                }
            }
        }

        /** 整组产量（件/秒）：Σ 各成员自己的那一份（自己的矿种 × 自己的钻速 × 覆盖格数）。 */
        public float groupDrillSpeed() {
            ObjectFloatMap<Item> rates = new ObjectFloatMap<>();
            groupDrillRates(rates);
            float sum = 0f;
            for (ObjectFloatMap.Entry<Item> e : rates)
                sum += e.value;
            return sum;
        }

        /**
         * 每种物品的整组挖掘总速率（件/秒）累加进 {@code out}。
         *
         * <p>统计范围是**整个组合体**（连接器/节点接进来的也算，和"构成"同一份成员表）。
         *
         * <p>每台取的就是它自己那份**运行期速率** {@code lastDrillSpeed}（件/tick，三种模式都由
         * updateTile 维护），乘 60 × timeScale() 换成件/秒 —— 和每台面板上那条"钻速"条
         * （{@code bar.drillspeed}）口径完全一致，于是整组 = 那一堆条之和（用户要求：
         * "应该是全部钻头挖某个物品的速率，不是某个单独钻头的速率"）。
         * 某台当前没在挖（lastDrillSpeed=0 或没有当前矿种）就不计入。
         */
        public void groupDrillRates(ObjectFloatMap<Item> out) {
            if (out == null)
                return;
            for (Building m : ComboNet.displayMembers(this, group().size)) {
                if (!(m instanceof CombinedDrillBuild d) || !d.isValid())
                    continue;
                CombinedDrill db = (CombinedDrill) d.block;
                Item mining = db.mode == Mode.beam ? d.lastItem : d.dominantItem;
                float perSecond = d.lastDrillSpeed * 60f * d.timeScale();
                // 注意：arc 的 ObjectFloatMap.increment(key, defaultAmount, amount) 参数顺序很反直觉
                // （第三个才是要加的量），这里直接写 put(get+…) 免得再踩。
                if (perSecond <= 0.0001f)
                    continue;
                // 【矿物阴影·资源输出站】站里的钻头是"挖得动的每种矿各按满效率产"，
                // 所以 display 里每种矿都要有一行速率（用户报的"只有主导矿的速率，没有其他矿的"）。
                if (!d.siteItems.isEmpty()) {
                    float each = perSecond / d.siteItems.size;
                    for (int i = 0; i < d.siteItems.size; i++)
                        out.put(d.siteItems.get(i), out.get(d.siteItems.get(i), 0f) + each);
                    continue;
                }
                if (mining == null)
                    continue;
                if (perSecond > 0.0001f)
                    out.put(mining, out.get(mining, 0f) + perSecond);
            }
        }

        public void buildLocalIO(Table table) {
            table.left();
            CombinedDrill mb = (CombinedDrill) this.block;
            table.add("[lightgray]本机: " + block.localizedName + "[]").left();
            table.row();
            boolean hasLocalInput = false;
            if (block.consumers != null && block.consumers.length > 0) {
                for (Consume cons : block.consumers) {
                    if (cons instanceof ConsumeLiquid cl) {
                        if (!hasLocalInput) {
                            table.add("[gray]输入:").left();
                            table.row();
                            hasLocalInput = true;
                        }
                        boolean has = liquids != null && liquids.get(cl.liquid) >= cl.amount * 10f;
                        table.table(row -> {
                            row.left();
                            if (cl.liquid.uiIcon != null) {
                                row.add(new ReqImage(cl.liquid.uiIcon, () -> has)).size(iconMed).padRight(4f);
                            }
                            row.add(cl.liquid.localizedName + " " + Strings.fixed(cl.amount * 60f, 1) + "/s")
                                    .color(has ? Color.white : Color.scarlet).left();
                        }).left();
                        table.row();
                    } else if (cons instanceof ConsumeLiquids cls) {
                        for (var stack : cls.liquids) {
                            if (!hasLocalInput) {
                                table.add("[gray]输入:").left();
                                table.row();
                                hasLocalInput = true;
                            }
                            boolean has = liquids != null && liquids.get(stack.liquid) >= stack.amount * 10f;
                            table.table(row -> {
                                row.left();
                                if (stack.liquid.uiIcon != null) {
                                    row.add(new ReqImage(stack.liquid.uiIcon, () -> has)).size(iconMed).padRight(4f);
                                }
                                row.add(stack.liquid.localizedName + " " + Strings.fixed(stack.amount * 60f, 1) + "/s")
                                        .color(has ? Color.white : Color.scarlet).left();
                            }).left();
                            table.row();
                        }
                    } else if (cons instanceof ConsumePower cp) {
                        if (!hasLocalInput) {
                            table.add("[gray]输入:").left();
                            table.row();
                            hasLocalInput = true;
                        }
                        table.table(row -> {
                            row.left();
                            row.image(Icon.powerSmall).size(iconMed).padRight(4f);
                            row.add("电力 " + Strings.fixed(cp.usage * 60f, 1) + " ⚡/s").color(Color.white).left();
                        }).left();
                        table.row();
                    }
                }
            }
            boolean hasLocalOutput = false;
            if (mb.mode == Mode.beam) {
                if (lastItem != null && facingAmount > 0) {
                    if (!hasLocalOutput) {
                        table.add("[gray]产出:").left();
                        table.row();
                        hasLocalOutput = true;
                    }
                    table.table(row -> {
                        row.left();
                        if (lastItem.uiIcon != null)
                            row.image(lastItem.uiIcon).size(24f).padRight(4f);
                        row.add(lastItem.localizedName + " "
                                + Strings.fixed(60f / mb.getDrillTime(lastItem) * facingAmount, 1)
                                + "/s [gray](" + facingAmount + "束激光)[]").color(Pal.accent).left();
                    }).left();
                    table.row();
                }
            } else if (mb.mode == Mode.burst) {
                if (dominantItem != null && dominantItems > 0) {
                    if (!hasLocalOutput) {
                        table.add("[gray]产出:").left();
                        table.row();
                        hasLocalOutput = true;
                    }
                    table.table(row -> {
                        row.left();
                        if (dominantItem.uiIcon != null)
                            row.image(dominantItem.uiIcon).size(24f).padRight(4f);
                        row.add(dominantItem.localizedName + " "
                                + Strings.fixed(60f / mb.getDrillTime(dominantItem) * dominantItems, 1)
                                + "/s [gray](脉冲,覆盖" + dominantItems + "格)[]").color(Pal.accent).left();
                    }).left();
                    table.row();
                }
            } else {
                if (dominantItem != null && dominantItems > 0) {
                    if (!hasLocalOutput) {
                        table.add("[gray]产出:").left();
                        table.row();
                        hasLocalOutput = true;
                    }
                    table.table(row -> {
                        row.left();
                        if (dominantItem.uiIcon != null)
                            row.image(dominantItem.uiIcon).size(24f).padRight(4f);
                        row.add(dominantItem.localizedName + " "
                                + Strings.fixed(60f / mb.getDrillTime(dominantItem) * dominantItems, 1)
                                + "/s [gray](覆盖" + dominantItems + "格)[]").color(Pal.accent).left();
                    }).left();
                    table.row();
                }
            }
            if (!hasLocalOutput && !hasLocalInput) {
                table.add("[darkGray]无").left();
                table.row();
            }
        }

        // ---- 序列化 ----
        // 地图区里只写"原版那一台钻头"的字节：Drill/BurstDrill 是 base + progress + warmup，
        // BeamDrill 是 base + time + warmup。模组自己的字段（组长、timeDrilled、dominantItem…）
        // 全部挪到自定义存档块 ComboSaveState —— 详见 ComboSaved。
        @Override
        public byte version() {
            return combine.saves.ComboSaveState.vanillaVersion(block);
        }

        @Override
        public void write(Writes write) {
            CombinedDrill cb = (CombinedDrill) block;
            if (cb.mode == Mode.beam) {
                write.f(time);
                write.f(warmup);
            } else {
                write.f(progress);
                write.f(warmup);
            }
        }

        // 存档先调 writeBase 写模块数据、后调 write —— "每口池子只写一份"必须挂在 writeBase 上
        // （写在 write() 里来不及），否则每个成员各写一份整池，读档合并后数量 ×N。
        @Override
        public void writeBase(Writes write) {
            ItemModule savedItems = items;
            LiquidModule savedLiquids = liquids;
            // 【每口池子只写一份】按模块身份去重（见 ComboSaveState.firstItemPool）：
            // 以前按"本地组组长"算，一条网络里 M 个本地组会把同一口池子写 M 遍，读档直接 ×M。
            if (!combine.saves.ComboSaveState.firstItemPool(this) && items != null)
                items = new ItemModule();
            if (!combine.saves.ComboSaveState.firstLiquidPool(this) && liquids != null)
                liquids = new LiquidModule();
            super.writeBase(write);
            items = savedItems;
            liquids = savedLiquids;
        }

        @Override
        public void writeCombo(Writes write) {
            Building leader = combine.saves.ComboSaveState.trueLeader(this, comboGroup);
            write.bool(leader != this);
            if (leader != this)
                write.i(leader.pos());
            writeExtras(write);
        }

        void writeExtras(Writes write) {
            CombinedDrill cb = (CombinedDrill) block;
            write.f(timeDrilled);
            write.f(lastDrillSpeed);
            write.i(dominantItems);
            write.s(dominantItem == null ? -1 : dominantItem.id);
            if (cb.mode == Mode.burst) {
                write.f(smoothProgress);
                write.f(invertTime);
            } else if (cb.mode == Mode.beam) {
                write.f(boostWarmup);
                write.i(facingAmount);
                write.s(lastItem == null ? -1 : lastItem.id);
            }
        }

        @Override
        public void read(Reads read, byte revision) {
            if (revision >= 10) {
                // 旧档（≤2.6）：模组字段直接续写在地图区里
                super.read(read, revision);
                boolean hasLeader = read.bool();
                int leaderPos = hasLeader ? read.i() : -1;
                progress = read.f();
                warmup = read.f();
                readExtras(read);
                applyLeader(hasLeader, leaderPos, true);
                return;
            }

            CombinedDrill cb = (CombinedDrill) block;
            // 【必须和原版同口径：只有 revision >= 1 才读这两个 float】
            // 原版 Drill/BeamDrill 的 read 都是 `if(revision >= 1){ ... }` —— 老地图里这一格
            // 写的是 revision=0（那一版 Drill.version() 还是 0），原版根本不写这两个字段。
            // 这里以前无条件读 → 读老地图多读 8 字节，地图区从此错位（用户报的
            // "部分老地图加载报 Unknown object type / EOF、环境墙大范围丢失"）。
            if (revision >= 1) {
                if (cb.mode == Mode.beam) {
                    time = read.f();
                    warmup = read.f();
                } else {
                    progress = read.f();
                    warmup = read.f();
                }
            }
            comboDirty = true;
        }

        @Override
        public void readCombo(Reads read, byte revision) {
            boolean hasLeader = read.bool();
            int leaderPos = hasLeader ? read.i() : -1;
            readExtras(read);
            // 新格式里"非组长"在存档里写的就是空模块，读档时整组已经被并成一份，
            // 这里不能再清空（清了就把并好的池子丢掉）。
            applyLeader(hasLeader, leaderPos, false);
        }

        void readExtras(Reads read) {
            CombinedDrill cb = (CombinedDrill) block;
            timeDrilled = read.f();
            lastDrillSpeed = read.f();
            dominantItems = read.i();
            int domId = read.s();
            dominantItem = domId == -1 ? null : content.item(domId);
            if (cb.mode == Mode.burst) {
                smoothProgress = read.f();
                invertTime = read.f();
            } else if (cb.mode == Mode.beam) {
                boostWarmup = read.f();
                facingAmount = read.i();
                int lastId = read.s();
                lastItem = lastId == -1 ? null : content.item(lastId);
            }
        }

        void applyLeader(boolean hasLeader, int leaderPos, boolean clearModules) {
            comboDirty = true;
            if (hasLeader && leaderPos != pos()) {
                pendingLeaderPos = leaderPos;
                if (clearModules && items != null)
                    items = new ItemModule();
                if (clearModules && liquids != null)
                    liquids = new LiquidModule();
            } else {
                pendingLeaderPos = -1;
                comboLeader = null;
            }
        }
    }
}
