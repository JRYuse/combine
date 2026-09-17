package combine;

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

    // ==================== 组合体通用 ====================
    public boolean allowCrossTypeCombo = true;
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
                    StatValues.drillables(drillTime, 0f, size, drillMultipliers,
                            b -> (b instanceof Floor f && f.wallOre && f.itemDrop != null && f.itemDrop.hardness <= tier
                                    && (blockedItems == null || !blockedItems.contains(f.itemDrop))) ||
                                    (b instanceof StaticWall w && w.itemDrop != null && w.itemDrop.hardness <= tier
                                            && (blockedItems == null || !blockedItems.contains(w.itemDrop)))));
            stats.add(Stat.drillSpeed, 60f / drillTime * size, StatUnit.itemsSecond);
            if (optionalBoostIntensity != 1 && findConsumer(
                    f -> f instanceof ConsumeLiquidBase && f.booster) instanceof ConsumeLiquidBase consBase) {
                stats.replace(Stat.booster, StatValues.speedBoosters("{0}" + StatUnit.timesSpeed.localized(),
                        consBase.amount, optionalBoostIntensity, false, consBase::consumes));
            }
        } else {
            stats.add(Stat.drillTier, StatValues.drillables(drillTime, hardnessDrillMultiplier, size * size,
                    drillMultipliers, b -> b instanceof Floor f && !f.wallOre && f.itemDrop != null &&
                            f.itemDrop.hardness <= tier && (blockedItems == null || !blockedItems.contains(f.itemDrop))
                            && (indexer.isBlockPresent(f) || state.isMenu())));
            stats.add(Stat.drillSpeed, 60f / drillTime * size * size, StatUnit.itemsSecond);
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
        if (mode == Mode.beam) {
            for (int i = 0; i < size; i++) {
                nearbySide(tile.x, tile.y, rotation, i, Tmp.p1);
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
            if (isMultiblock()) {
                for (Tile other : tile.getLinkedTilesAs(this, tempTiles))
                    if (canMine(other))
                        return true;
                return false;
            }
            return canMine(tile);
        }
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

        Draw.tint(returnItem.color);
        Draw.rect(itemRegion, plan.drawx(), plan.drawy());
        Draw.color();
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
            float dx = x * tilesize + offset - width / 2f - 4f, dy = y * tilesize + offset + size * tilesize / 2f + 5,
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
            Tile to = tile.getLinkedTilesAs(this, tempTiles).find(t -> t.drop() != null
                    && (t.drop().hardness > tier || (blockedItems != null && blockedItems.contains(t.drop()))));
            Item item = to == null ? null : to.drop();
            if (item != null)
                drawPlaceText(Core.bundle.get("bar.drilltierreq"), x, y, valid);
        }
    }

    void drawPlaceBeam(int x, int y, int rotation, boolean valid) {
        Item item = null, invalidItem = null;
        boolean multiple = false;
        int count = 0;
        for (int i = 0; i < size; i++) {
            nearbySide(x, y, rotation, i, Tmp.p1);
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
                        dy = y * tilesize + offset + size * tilesize / 2f + 5, s = iconSmall / 4f;
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
        for (Tile other : tile.getLinkedTilesAs(this, tempTiles)) {
            if (canMine(other))
                oreCount.increment(getDrop(other), 0, 1);
        }
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

    public boolean canMine(Tile tile) {
        if (tile == null || tile.block().isStatic())
            return false;
        Item drops = tile.drop();
        return drops != null && drops.hardness <= tier && (blockedItems == null || !blockedItems.contains(drops));
    }

    // ==================== Building ====================

    public class CombinedDrillBuild extends Building {
        public CombinedDrillBuild comboLeader;
        public Seq<CombinedDrillBuild> comboGroup = new Seq<>();
        public boolean comboDirty = true;
        public float comboTotalLiquidCap = 0f;
        public int comboTotalItemCap = 0;
        public int pendingLeaderPos = -1;

        // Drill / Burst
        public float progress, warmup, timeDrilled, lastDrillSpeed;
        public int dominantItems;
        public Item dominantItem;

        // Burst
        public float smoothProgress, invertTime;

        // Beam
        public Tile[] facing = new Tile[size];
        public Point2[] lasers = new Point2[size];
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
            IntSet visited = new IntSet();
            Queue<CombinedDrillBuild> queue = new Queue<>();
            queue.add(this);
            visited.add(pos());
            while (!queue.isEmpty()) {
                CombinedDrillBuild current = queue.removeFirst();
                for (Building b : current.proximity) {
                    if (b instanceof CombinedDrillBuild other && other.team == team && other.isValid()
                            && !visited.contains(other.pos())) {
                        CombinedDrill cb = (CombinedDrill) current.block, ob = (CombinedDrill) other.block;
                        if (current.block == other.block || cb.allowCrossTypeCombo || ob.allowCrossTypeCombo) {
                            visited.add(other.pos());
                            queue.addLast(other);
                            comboGroup.add(other);
                        }
                    }
                }
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
                    b.comboTotalLiquidCap = totalLiqCap;
                    b.comboTotalItemCap = totalItemCap;
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
            CombinedDrillBuild oldLeader = null;
            for (CombinedDrillBuild b : oldGroup) {
                if (!b.isValid())
                    continue;
                for (CombinedDrillBuild other : oldGroup) {
                    if (other != b && other.isValid() && (other.items == b.items || other.liquids == b.liquids)) {
                        oldLeader = b;
                        break;
                    }
                }
                if (oldLeader != null)
                    break;
            }
            if (oldLeader == null) {
                for (CombinedDrillBuild b : oldGroup)
                    if (b.isValid()) {
                        oldLeader = b;
                        break;
                    }
            }
            if (oldLeader == null)
                oldLeader = this;
            ItemModule oldItems = oldLeader.items;
            LiquidModule oldLiquids = oldLeader.liquids;
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
            if (oldItems != null && oldTotalItemCap > 0 && kickedTotalItemCap > 0) {
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
            if (oldLiquids != null && oldTotalLiquidCap > 0.001f && kickedTotalLiquidCap > 0.001f) {
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
            if (oldItems != null)
                for (CombinedDrillBuild b : newGroup)
                    if (b.isValid())
                        b.items = oldItems;
            if (oldLiquids != null)
                for (CombinedDrillBuild b : newGroup)
                    if (b.isValid())
                        b.liquids = oldLiquids;
            for (int i = 0; i < kicked.size; i++) {
                CombinedDrillBuild b = kicked.get(i);
                b.items = newItemMods[i];
                b.liquids = newLiquidMods[i];
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
            ObjectSet<ItemModule> processedItems = new ObjectSet<>();
            ObjectSet<LiquidModule> processedLiquids = new ObjectSet<>();
            if (leader.items != null) {
                processedItems.add(leader.items);
                for (CombinedDrillBuild member : group()) {
                    if (member != leader && member.isValid() && member.items != null
                            && !processedItems.contains(member.items)) {
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
            }
            if (leader.liquids != null) {
                processedLiquids.add(leader.liquids);
                for (CombinedDrillBuild member : group()) {
                    if (member != leader && member.isValid() && member.liquids != null
                            && !processedLiquids.contains(member.liquids)) {
                        processedLiquids.add(member.liquids);
                        for (Liquid liquid : content.liquids()) {
                            float amt = member.liquids.get(liquid);
                            if (amt > 0.001f) {
                                float canAccept = Math.max(0f, totalLiquidCap - ComboReflect.liquidTotal(leader.liquids));
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
            } else if (cb.mode == Mode.beam) {
                updateLasers();
                updateFacing();
            }
        }

        @Override
        public void onRemoved() {
            Seq<CombinedDrillBuild> members = new Seq<>(group());
            boolean wasLeader = isLeader();
            ItemModule oldItems = this.items;
            LiquidModule oldLiquids = this.liquids;
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
                if (oldItems != null && totalItemCap > 0) {
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
                if (oldLiquids != null && totalLiquidCap > 0.001f) {
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

            // FIX: 每帧强制截断超出的液体
            if (liquids != null && comboTotalLiquidCap > 0.001f) {
                float excess = ComboReflect.liquidTotal(liquids) - comboTotalLiquidCap;
                if (excess > 0.001f) {
                    for (Liquid l : content.liquids()) {
                        float amt = liquids.get(l);
                        if (amt > 0.001f) {
                            float remove = Math.min(amt, excess);
                            liquids.remove(l, remove);
                            excess -= remove;
                            if (excess <= 0.001f)
                                break;
                        }
                    }
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
            if (timer(timerDump, dumpTime / timeScale)) {
                for (Item item : content.items())
                    if (items.get(item) > 0)
                        dump(item);
            }
            if (dominantItem == null)
                return;
            timeDrilled += warmup * delta();
            float delay = getDrillTime(dominantItem);
            // FIX(per-type)：只看 dominantItem 自身余量 —— 另一种矿满不影响本矿挖掘
            if (items.get(dominantItem) < comboTotalItemCap && dominantItems > 0 && efficiency > 0) {
                float speed = Mathf.lerp(1f, cb.liquidBoostIntensity, optionalEfficiency) * efficiency;
                lastDrillSpeed = (speed * dominantItems * warmup) / delay;
                warmup = Mathf.approachDelta(warmup, speed, cb.warmupSpeed);
                progress += delta() * dominantItems * speed * warmup;
                if (Mathf.chanceDelta(cb.updateEffectChance * warmup))
                    cb.updateEffect.at(x + Mathf.range(size * 2f), y + Mathf.range(size * 2f));
            } else {
                lastDrillSpeed = 0f;
                warmup = Mathf.approachDelta(warmup, 0f, cb.warmupSpeed);
                return;
            }
            if (dominantItems > 0 && progress >= delay && items.get(dominantItem) < comboTotalItemCap) {
                int amount = (int) (progress / delay);
                for (int i = 0; i < amount; i++)
                    offload(dominantItem);
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
            if (timer(timerDump, dumpTime / timeScale)) {
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
            if (dominantItems > 0 && progress >= drillTime && items.get(dominantItem) < comboTotalItemCap) {
                for (int i = 0; i < dominantItems; i++)
                    offload(dominantItem);
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
            float multiplier = Mathf.lerp(1f, cb.optionalBoostIntensity, optionalEfficiency);
            float drillTime = getDrillTime(lastItem);
            boostWarmup = Mathf.lerpDelta(boostWarmup, optionalEfficiency, 0.1f);
            lastDrillSpeed = (facingAmount * multiplier * timeScale) / drillTime * efficiency;
            time += edelta() * multiplier;
            if (time >= drillTime) {
                for (Tile tile : facing) {
                    Item drop = tile == null ? null : tile.wallDrop();
                    // FIX(per-type)：每种矿独立余量，某种满了继续挖别的
                    if (drop != null && items.get(drop) < comboTotalItemCap)
                        items.add(drop, 1);
                }
                time %= drillTime;
            }
            // FIX(混合矿堵塞)：逐类型倒出池内所有矿物
            if (timer(timerDump, dumpTime / timeScale)) {
                for (Item item : content.items())
                    if (items.get(item) > 0)
                        dump(item);
            }
        }

        // Beam 辅助
        protected void updateLasers() {
            for (int i = 0; i < size; i++) {
                if (lasers[i] == null)
                    lasers[i] = new Point2();
                nearbySide(tileX(), tileY(), rotation, i, lasers[i]);
            }
        }

        protected void updateFacing() {
            lastItem = null;
            boolean multiple = false;
            int dx = Geometry.d4x(rotation), dy = Geometry.d4y(rotation);
            facingAmount = 0;
            CombinedDrill cb = (CombinedDrill) block;
            for (int p = 0; p < size; p++) {
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
            boolean needed = false;
            for (CombinedDrillBuild member : group()) {
                if (member.isValid() && member.block.consumesLiquid(liquid)) {
                    needed = true;
                    break;
                }
            }
            return needed && liquids != null && ComboReflect.liquidTotal(liquids) < comboTotalLiquidCap - 0.001f;
        }

        @Override
        public void handleLiquid(Building source, Liquid liquid, float amount) {
            if (amount <= 0.001f)
                return;
            float currentTotal = ComboReflect.liquidTotal(liquids);
            float canAccept = Math.max(0f, comboTotalLiquidCap - currentTotal);
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
            drawItemSelection(cb.mode == Mode.beam ? lastItem : dominantItem);
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

        public void drawDefaultCracks() {
            super.drawCracks();
        }

        @Override
        public void draw() {
            CombinedDrill cb = (CombinedDrill) block;
            if (cb.mode == Mode.beam)
                drawBeam();
            else if (cb.mode == Mode.burst)
                drawBurst();
            else
                drawDrill();
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
            if (dominantItem != null && cb.drawMineItem) {
                Draw.color(dominantItem.color);
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
            if (dominantItem != null && cb.drawMineItem) {
                Draw.color(dominantItem.color);
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
            for (int i = 0; i < size; i++) {
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
                    barsTable.defaults().growX().height(18f).pad(4);
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
            float totalPower = 0f;
            for (CombinedDrillBuild member : group()) {
                if (member.isValid() && member.block.consPower != null)
                    totalPower += member.block.consPower.usage;
            }
            if (totalPower > 0 && power != null) {
                final float tp = totalPower;
                table.add(new Bar(
                        () -> "电力 " + Strings.fixed(tp * power.status * 60f, 1) + " ⚡/s",
                        () -> Pal.power,
                        () -> power.status));
                table.row();
            }
            final float ls = lastDrillSpeed;
            table.add(new Bar(
                    () -> Core.bundle.format("bar.drillspeed", Strings.fixed(ls * 60f * timeScale(), 2)),
                    () -> Pal.ammo,
                    () -> warmup));
            table.row();
            if (items != null) {
                for (Item item : content.items()) {
                    int total = items.get(item);
                    if (total > 0) {
                        final int t = total, c = ComboNet.effectiveItemCap(this);
                        table.add(new Bar(
                                () -> item.localizedName + ": " + t + "/" + c,
                                () -> item.color,
                                () -> (float) t / c));
                        table.row();
                    }
                }
            }
            LiquidModule sharedLiq = this.liquids;
            if (sharedLiq == null) {
                CombinedDrillBuild l = leader();
                if (l != null)
                    sharedLiq = l.liquids;
            }
            if (sharedLiq == null) {
                for (CombinedDrillBuild member : group()) {
                    if (member.liquids != null) {
                        sharedLiq = member.liquids;
                        break;
                    }
                }
            }
            if (sharedLiq != null) {
                for (Liquid liquid : content.liquids()) {
                    float total = sharedLiq.get(liquid);
                    if (total > 0.001f) {
                        final float t = total, c = ComboNet.effectiveLiquidCap(this);
                        table.add(new Bar(
                                () -> liquid.localizedName + ": " + Strings.fixed(t, 1) + "/" + Strings.fixed(c, 1),
                                () -> liquid.barColor != null ? liquid.barColor : liquid.color,
                                () -> t / c));
                        table.row();
                    }
                }
            }
        }

        public void buildComboIO(Table table) {
            table.left();
            table.add("[lightgray]组合体构成:").left();
            table.row();
            ObjectIntMap<Block> blockCounts = new ObjectIntMap<>();
            for (Building member : ComboNet.displayMembers(this, group().size)) {
                if (member.isValid()) {
                    int old = blockCounts.get(member.block, 0);
                    blockCounts.put(member.block, old + 1);
                }
            }
            Seq<Block> sortedBlocks = new Seq<>();
            for (Block b : blockCounts.keys())
                sortedBlocks.add(b);
            sortedBlocks.sort(b -> b.id);
            boolean hasContent = false;
            for (Block b : sortedBlocks) {
                int count = blockCounts.get(b, 0);
                if (count > 0) {
                    hasContent = true;
                    table.add(b.localizedName + "*" + count).color(Color.white).left();
                    table.row();
                }
            }
            if (!hasContent) {
                table.add("[darkGray]无").left();
                table.row();
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
        @Override
        public byte version() {
            return 10;
        }

        @Override
        public void write(Writes write) {
            CombinedDrillBuild trueLeader = this;
            if (comboGroup != null && comboGroup.size > 0) {
                for (CombinedDrillBuild b : comboGroup)
                    if (b != null && b.isValid() && b.pos() < trueLeader.pos())
                        trueLeader = b;
            }
            ItemModule savedItems = items;
            LiquidModule savedLiquids = liquids;
            if (this != trueLeader) {
                if (items != null)
                    items = new ItemModule();
                if (liquids != null)
                    liquids = new LiquidModule();
            }
            super.write(write);
            items = savedItems;
            liquids = savedLiquids;
            write.bool(comboLeader != null);
            if (comboLeader != null)
                write.i(comboLeader.pos());
            CombinedDrill cb = (CombinedDrill) block;
            write.f(progress);
            write.f(warmup);
            write.f(timeDrilled);
            write.f(lastDrillSpeed);
            write.i(dominantItems);
            write.s(dominantItem == null ? -1 : dominantItem.id);
            if (cb.mode == Mode.burst) {
                write.f(smoothProgress);
                write.f(invertTime);
            } else if (cb.mode == Mode.beam) {
                write.f(time);
                write.f(boostWarmup);
                write.i(facingAmount);
                write.s(lastItem == null ? -1 : lastItem.id);
            }
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            boolean hasLeader = false;
            int leaderPos = -1;
            if (revision >= 10) {
                hasLeader = read.bool();
                if (hasLeader)
                    leaderPos = read.i();
            }
            comboDirty = true;
            if (hasLeader && leaderPos != pos()) {
                pendingLeaderPos = leaderPos;
                if (items != null)
                    items = new ItemModule();
                if (liquids != null)
                    liquids = new LiquidModule();
            } else {
                pendingLeaderPos = -1;
                comboLeader = null;
            }
            if (revision >= 10) {
                progress = read.f();
                warmup = read.f();
                timeDrilled = read.f();
                lastDrillSpeed = read.f();
                dominantItems = read.i();
                int domId = read.s();
                dominantItem = domId == -1 ? null : content.item(domId);
            }
            if (revision >= 10) {
                CombinedDrill cb = (CombinedDrill) block;
                if (cb.mode == Mode.burst) {
                    smoothProgress = read.f();
                    invertTime = read.f();
                } else if (cb.mode == Mode.beam) {
                    time = read.f();
                    boostWarmup = read.f();
                    facingAmount = read.i();
                    int lastId = read.s();
                    lastItem = lastId == -1 ? null : content.item(lastId);
                }
            }
        }
    }
}
