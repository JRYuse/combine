package combine.units.mega;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Angles;
import arc.math.Mathf;
import arc.util.Tmp;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.gen.Legsc;
import mindustry.gen.Tankc;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

/**
 * 组合巨兽的单位类型。无自己的贴图——绘制时取成员中"代表类型"
 * （数量最多；并列取血量最少）的贴图，并按综合 hitSize 缩放
 * （缩放系数由 {@link MegaUnitEntity#drawScale} 提供，双端各自从成员构成推导，无需同步）。
 *
 * <p>
 * 三个变体（地面/飞行/海军）由 {@link combine.units.UnitComboMerge} 创建：
 * 飞行变体 {@code flying = true}；海军变体的语义在 {@link MegaUnitEntity} 里
 * （碰撞判定按水面处理）。生命/护甲/碰撞半径/武器/能力都是融合那一刻
 * 写在实体实例上的（见 MegaUnitEntity.refreshDerived），类型的同名字段只是兜底默认值。
 */
public class MegaUnitType extends UnitType {

    /**
     * 这只巨兽会不会升空（成员里有飞行单位）。
     *
     * 巨兽"能不能飞"是按成员构成算的（见 {@link MegaUnitEntity#moveMode()}），类型上的
     * {@code flying} 必须保持 false（否则原版会把它当固定飞行单位，碰撞/高度全乱）。
     * 但引擎要不要画，就靠这个标记：会升空才生成引擎。
     */
    public boolean hoverEngines = false;

    /** 引擎尺寸/位置的兜底参考比例，取自 flare（hitSize 9 / engineOffset 5.75 / engineSize 2.5）。 */
    public static final float refHitSize = 9f, refEngineOffset = 5.75f, refEngineSize = 2.5f;

    /**
     * 引擎比例（相对 hitSize）。默认用 flare 那套：
     * flare hitSize 9、engineOffset 5.75、engineSize 2.5 → 位置 0.639×hitSize、大小
     * 0.278×hitSize。
     * 成员里有飞行单位时，{@link MegaUnitEntity} 会按那台单位自己的引擎参数改写这两个比例
     * （身体贴图就是按同一套缩放画的，引擎跟着同源才不会一个巨大一个迷你）。
     */
    public float engineOffsetRatio = refEngineOffset / refHitSize, engineSizeRatio = refEngineSize / refHitSize;

    /**
     * 补齐 {@link UnitType#init()} / {@link UnitType#load()} 才会写的那些字段。
     *
     * 巨兽类型是模组 init 期 late 注册的，这两个方法**从没跑过**，而其中两个字段是"无效默认值"：
     * <ul>
     * <li>{@code flyingLayer = -1}：混合编组里有飞行成员（比如 mace + oct）时，
     * {@code MegaUnitEntity.moveMode()} 会让 elevation 逼近 1，绘制走 "elevation &gt; 0.5 →
     * flyingLayer"，
     * 于是 {@code Draw.z(-1)} —— 比地板层还低，整个单位被地形盖住。
     * 表现就是用户报的"mace 和 oct 组合后不绘制单位身体"（贴图其实一直在画，只是画在了地板下面）。</li>
     * <li>{@code clipSize = -1}：视口裁剪（{@code EntityGroup.draw}）按它算包围盒，
     * 负尺寸等于"只剩中心点"，单位贴着屏幕边就会被整块剔掉。</li>
     * </ul>
     * 另外 {@code lightRadius = -1} 会让巨兽没有灯光。hitSize 会在融合时按成员重算，
     * 所以这个方法在构造期和每次推导之后都要调一次。
     */
    public void applyLateDefaults() {
        if (lowAltitude) {
            flyingLayer = Layer.flyingUnitLow;
        } else if (flyingLayer < 0f) {
            flyingLayer = Layer.flyingUnit;
        }
        if (lightRadius < 0f) {
            lightRadius = Math.max(60f, hitSize * 2.3f);
        }
        // 【探雾范围】原版是 UnitType.init() 里写的（`fogRadius = max(58*3, hitSize*2)/8`），
        // 巨兽类型 late 注册、init() 从没跑过 —— fogRadius 停在 -1 时 FogControl 直接
        // `if(unit.type.fogRadius <= 0f) continue;`，巨兽一点雾都不探（用户报的"几乎没有"）。
        // 每按体型重算一次（派生类型是按成员构成新建的，值跟着体型走）。
        fogRadius = Math.max(58f * 3f, hitSize * 2f) / 8f;
        clipSize = Math.max(Math.max(clipSize, lightRadius * 1.1f), hitSize * 2.4f);
        rebuildEngines();
    }

    /**
     * 按体型生成引擎。
     *
     * 原版是在 {@link UnitType#init()} 里按 {@code engineSize/engineOffset} 建
     * {@code engines} 的，
     * 而巨兽类型是 late 注册、init() 从没跑过 —— {@code engines} 永远是空表，
     * 于是"会飞的巨兽"一点尾焰都没有。这里按 hitSize × 比例算出尺寸与位置
     * （比例默认取自 flare；有飞行成员时由 MegaUnitEntity 换成那台单位的参数）。
     */
    public void rebuildEngines() {
        engines.clear();
        // 不会升空的巨兽不需要引擎（地面时 elevation=0，引擎本来就画不出来）
        if (!flying && !hoverEngines)
            return;

        engineOffset = hitSize * engineOffsetRatio;
        engineSize = hitSize * engineSizeRatio;
        if (engineOffset <= 0.01f || engineSize <= 0.01f)
            return;

        // 和 flare / oct 一样居中一个引擎（引擎是圆，跟着 elevation 缩放，不会越界）
        engines.add(new UnitEngine(0f, -engineOffset, engineSize, -90f));
    }

    public MegaUnitType(String name) {
        super(name);
        constructor = MegaUnitEntity::new;
        // 兜底数值（融合时会被按成员重算的实例值覆盖）
        hitSize = 20f;
        health = 1000f;
        speed = 0.8f;
        rotateSpeed = 3f;
        // 巨兽"会不会飞"是运行时按成员构成决定的（见 MegaUnitEntity.moveMode），
        // 类型上的 flying 保持 false；但悬浮时走的是"低空飞行层"，同 oct 这类 lowAltitude 单位。
        lowAltitude = true;
        applyLateDefaults();
        // 没有自己的贴图，这些绘制开关关掉（绘制全在 draw() 里自定义）
        drawCell = false;
        drawItems = false;
        // 【小地图】原版 MinimapRenderer 每个单位都判 `!unit.type.drawMinimap` 就跳过；
        // 巨兽类型这里关掉过，于是**任何一方的小地图都没有它**（用户报的"合体的单位不会在
        // 小地图出现"）。图标走 MegaUnitEntity.icon()（= 代表成员的 uiIcon），不会画空贴图。
        drawMinimap = true;
        hidden = true;
        // 巨兽类型是模组 init 期 late 注册的，UnitType.load() 从不执行：
        // wreckRegions 停在 null，而 createWreck/createScorch 默认 true——
        // PayloadUnit.destroy() 会遍历 type.wreckRegions.length，巨兽死亡瞬间直接 NPE。
        // 融合体死亡不留原版残骸/焦痕（成员本来也没有真死），从根源上关掉。
        createWreck = false;
        createScorch = false;
        wreckRegions = new TextureRegion[0];
        segmentRegions = new TextureRegion[0];
        // 融合不占用人口上限：合并 N 个单位为 1 个时计数先减回 N 再加 1，
        // 顶着上限融合会让巨兽出生即触发 unitCapDeath。
        useUnitCap = false;

        // 幽灵武器：只为了让原版"类型级"判定对巨兽成立——
        // DesktopInput 的 aimCursor（玩家操控时朝准星转身）和 AIController 的
        // faceTarget 都查 unit.type.hasWeapons()（= type.weapons 非空），巨兽真实
        // 武器在实例 mounts 上、type.weapons 为空，不重写这两条玩家操控就是
        // "不转身、不自动索敌"。它从不进入实例 mounts（武器从成员构成推导，
        // 见 MegaUnitEntity.rebuildMounts），永远不会开火；子弹射程给得极大，
        // 万一 UnitType.init() 被调用，也不会把 type.range 用 min() 压成 0
        // （移动端"目标超出 type.range 即失效"会把自动索敌全部 invalidate）。
        weapons.add(newWeapon());
    }

    /** 幽灵武器实例，见构造器注释。包级私有以便模拟/测试断言用。 */
    static mindustry.type.Weapon newWeapon() {
        mindustry.type.Weapon w = new mindustry.type.Weapon("combine-ghost-weapon");
        w.bullet = new mindustry.entities.bullet.BulletType(1f, 0f);
        w.bullet.lifetime = 10000f;
        w.predictTarget = false;
        return w;
    }

    /**
     * 真·整只单位图（原版 {@code unit-<名字>-full}）：躯干 + 腿/机甲腿/履带 + 武器都画在里面。
     *
     * <p>注意要区分"真的整图"和"兜底"：{@code UnlockableContent.loadIcon()} 在找不到
     * {@code -full} 贴图时会把 {@code fullIcon} **兜底成躯干 region 本身**（模组单位很常见），
     * 那种情况下 figure 里并没有额外部件，不能当成整图用。拿不到真整图时返回 null。
     */
    static TextureRegion fullArt(UnitType dom) {
        if (dom == null) return null;
        TextureRegion f = dom.fullIcon;
        if (f == null || !Core.atlas.isFound(f)) return null;
        // 兜底成了躯干：不是整图
        if (f == dom.region) return null;
        // 命名上也不是整图（原版约定 unit-<名字>-full；模组也常用 -full/_full 后缀）
        if (f instanceof arc.graphics.g2d.TextureAtlas.AtlasRegion ar && ar.name != null
            && !(ar.name.endsWith("-full") || ar.name.endsWith("_full"))) return null;
        return f;
    }

    /** 躯干图（region → UI 图标）。 */
    static TextureRegion torsoArt(UnitType dom) {
        if (dom == null) return null;
        if (dom.region != null && Core.atlas.isFound(dom.region)) return dom.region;
        if (dom.uiIcon != null && Core.atlas.isFound(dom.uiIcon)) return dom.uiIcon;
        return null;
    }

    /**
     * 本体贴图：**优先画整只单位图**（原版 {@code unit-<名字>-full}，腿/履带/武器都画在里面），
     * 只有拿不到真整图时才退到躯干 region / UI 图标。
     *
     * <p>整图里部件已经画好了，所以走这条路时 {@link #drawAttachments} 不用再补一遍
     *（见 {@link #fullArt}：模组单位常见的"fullIcon 被兜底成躯干 region"不算整图，
     * 那种情况仍然要用自己画的那套腿/履带 —— 用户报的"mech 的脚怎么又不画了"就是它）。
     *
     * <p>巨兽类型自己没有任何贴图，所以每一层都要兜底，否则 {@code Draw.rect(null)}
     * 什么都不画（"不绘制单位身体"的另一半）。
     */
    static TextureRegion bodyRegion(UnitType dom) {
        if (dom == null) return null;
        TextureRegion full = fullArt(dom);
        if (full != null) return full;
        return torsoArt(dom);
    }

    /**
     * 把**身体部件**（腿 / 机甲腿 / 履带 / 爬虫身）画上去。
     *
     * 原版的 drawBody 只画 region 那张躯干图；腿之类由 drawLegs/drawMech/drawTank/drawCrawl
     * 单独画，且直接读"单位自己的部件状态"——巨兽实体不是原版那些腿/履带/爬虫实体，
     * 这些部件一直没被画出来（用户报的"组合 Legs 单位没画腿，腿也要比例放大"）。
     *
     * 现在状态由巨兽自己维护（见 MegaUnitEntity.updateAttachments），这里只负责按代表类型的
     * 部件种类分派到原版那几套画法；缩放靠临时把 {@code Draw.scl} 乘上巨兽的缩放系数——
     * 原版这些绘制里所有尺寸都走 {@code region.scl()}（= region.scale × Draw.scl）和
     * {@code Lines.stroke(... × region.scl())}，所以乘一次 Draw.scl，腿长/腿粗/脚掌/关节/
     * 履带/节段就一起等比放大了。
     *
     * 机甲腿用本类的 {@link #drawMechOf}（原版 drawMech 的拷贝）：Mechc 接口**故意不实现**，
     * 见 MegaUnitEntity 里关于 "机甲飞行时禁止开火" 特判的注释。
     */
    static void drawAttachments(Unit unit, MegaUnitEntity mu, UnitType dom, float z, float sc) {
        if (dom == null || mu == null || mu.attKind == MegaUnitEntity.ATT_NONE)
            return;
        float prevScl = Draw.scl;
        Draw.scl = prevScl * sc;
        try {
            switch (mu.attKind) {
                case MegaUnitEntity.ATT_LEGS -> {
                    Draw.z(z - 0.02f);
                    drawLegsOf(dom, unit);
                }
                case MegaUnitEntity.ATT_MECH -> {
                    Draw.z(z - 0.02f);
                    drawMechOf(dom, unit, mu);
                }
                case MegaUnitEntity.ATT_TANK -> {
                    Draw.z(z - 0.02f);
                    drawTankOf(dom, unit);
                }
                case MegaUnitEntity.ATT_CRAWL -> {
                    Draw.z(z);
                    dom.drawCrawl(mu);
                }
                default -> {
                }
            }
        } catch (Throwable ignored) {
        } finally {
            Draw.scl = prevScl;
            Draw.reset();
        }
    }

    /**
     * 原版 {@code UnitType.drawMech} 的等价实现（尺寸走 region.scl()，所以跟着 Draw.scl 等比放大）。
     * walkExtend 的两套语义照抄 MechComp：scaled=true 返回 0..4 的相位、false 返回 -stride..2×stride 的位移。
     */
    static void drawMechOf(UnitType dom, Unit unit, MegaUnitEntity mu) {
        Draw.reset();

        float e = unit.elevation;
        float stride = Math.max(dom.mechStride, 0.01f);
        float raw = mu.mechWalkTime % (stride * 4f);
        float scaled = raw / stride;
        float extension = raw;
        if (extension > stride * 3f) extension -= stride * 4f;
        else if (extension > stride * 2f) extension = stride * 2f - extension;
        else if (extension > stride) extension = stride * 2f - extension;

        float sin = Mathf.lerp(Mathf.sin(scaled, 2f / Mathf.PI, 1f), 0f, e);
        extension = Mathf.lerp(extension, 0f, e);
        float boostTrns = e * 2f;
        float baseRotation = mu.baseRotation();

        mindustry.world.blocks.environment.Floor floor = unit.isFlying()
                ? mindustry.content.Blocks.air.asFloor() : unit.floorOn();
        if (floor.isLiquid)
            Draw.color(arc.graphics.Color.white, floor.mapColor, 0.5f);

        for (int i : Mathf.signs) {
            Draw.mixcol(Tmp.c1.set(dom.mechLegColor).lerp(arc.graphics.Color.white, Mathf.clamp(unit.hitTime)),
                    Math.max(Math.max(0f, i * extension / stride), unit.hitTime));

            Draw.rect(dom.legRegion,
                    unit.x + Angles.trnsx(baseRotation, extension * i - boostTrns, -boostTrns * i),
                    unit.y + Angles.trnsy(baseRotation, extension * i - boostTrns, -boostTrns * i),
                    dom.legRegion.width * dom.legRegion.scl() * i,
                    dom.legRegion.height * dom.legRegion.scl() * (1f - Math.max(-sin * i, 0f) * 0.5f),
                    baseRotation - 90f + 35f * i * e);
        }

        Draw.mixcol(arc.graphics.Color.white, unit.hitTime);

        if (unit.lastDrownFloor != null) {
            Draw.color(arc.graphics.Color.white, Tmp.c1.set(unit.lastDrownFloor.mapColor).mul(0.83f), unit.drownTime * 0.9f);
        } else {
            Draw.color(arc.graphics.Color.white);
        }

        Draw.rect(dom.baseRegion, unit.x, unit.y, baseRotation - 90f);
        Draw.mixcol();
    }

    /** 原版 drawLegs/drawTank 的形参带 `T extends Unit & X` 交叉上界，用泛型辅助方法转一次。 */
    @SuppressWarnings("unchecked")
    private static <T extends Unit & Legsc> void drawLegsOf(UnitType type, Unit u) {
        type.drawLegs((T) u);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Unit & Tankc> void drawTankOf(UnitType type, Unit u) {
        type.drawTank((T) u);
    }

    @Override
    public void draw(Unit unit) {
        if (unit.inFogTo(mindustry.Vars.player.team()))
            return;

        MegaUnitEntity mu = unit instanceof MegaUnitEntity m ? m : null;
        UnitType dom = mu != null && mu.dominant != null ? mu.dominant : null;
        TextureRegion region = bodyRegion(dom);
        // 【兜底：成员还没解析出来时也要看得见】代表类型来自成员构成（快照/存档刚建立、
        // 或对端模组不一致导致成员解析失败时是空的）—— 以前这里什么都画不出来，
        // 单位直接"隐身"（用户报的"客户端组合不同单位会导致单位不可见"）。
        // 退回到巨兽类型自己的占位贴图（注册时给的 dagger/flare/risso 图标）。
        if (region == null && this.region != null && Core.atlas.isFound(this.region))
            region = this.region;
        // 【什么时候不用自己补部件】用的是真·unit-<名字>-full，而且这个种类在整图里
        // **确实把部件画全了**（腿类/履带/飞行都画全了；模组单位常常没有 -full，
        // fullIcon 会被兜底成躯干 region，这时不算整图）。
        //
        // 【机甲例外：整图画了腿也要再画一遍】原版机甲整图里的腿是"缩在身体底下的图标姿态"
        // （dagger/fortress 的 -full 里那两条腿几乎贴在躯干下面），只画整图看着就是没腿
        //（用户报的"组合巨兽 mech 的脚怎么又不画了"），所以机甲一律再叠一层按体型画的机甲腿。
        boolean mech = mu != null && mu.attKind == MegaUnitEntity.ATT_MECH;
        boolean bakedParts = !mech && dom != null && region != null && region == fullArt(dom);
        float s = mu == null ? 1f : mu.bodyScale();
        boolean isPayload = !unit.isAdded();
        float z = isPayload ? Draw.z()
                : (unit.elevation > 0.5f || (flying && unit.dead)
                        ? (flyingLayer < 0f ? Layer.flyingUnitLow : flyingLayer)
                        : groundLayer + Mathf.clamp(unit.hitSize / 4000f, 0f, 0.01f));

        // 空中阴影（按代表类型的阴影贴图、按缩放系数放大）
        if (!isPayload && (unit.isFlying() || shadowElevation > 0)) {
            TextureRegion sh = dom != null && dom.shadowRegion != null && Core.atlas.isFound(dom.shadowRegion)
                    ? dom.shadowRegion
                    : shadowRegion;
            if (sh != null && Core.atlas.isFound(sh)) {
                float e = Mathf.clamp(unit.elevation, shadowElevation, 1f) * shadowElevationScl * (1f - unit.drownTime);
                Draw.z(Math.min(Layer.darkness, z - 1f));
                Draw.color(Pal.shadow, Pal.shadow.a * unit.shadowAlpha);
                Draw.rect(sh, unit.x + shadowTX * e, unit.y + shadowTY * e,
                        sh.width * s * Draw.scl, sh.height * s * Draw.scl, unit.rotation - 90);
                Draw.color();
            }
        }

        // 【身体部件：腿/机甲腿/履带/爬虫身】原版顺序在机身之前（腿在机身下面）
        if (!isPayload && !bakedParts)
            drawAttachments(unit, mu, dom, z, s);

        // 【先引擎、后机身】——和原版 UnitType.draw 同一个顺序（引擎 → Draw.z(z) → drawBody）：
        // 引擎是画在机身**下面**的，机身盖住它朝内那半圈，看上去才是"喷口从机身尾部喷出来"。
        // 反过来（机身先、引擎后）就是用户看到的"引擎糊在单位身上"。
        Draw.z(z);
        if (engines.size > 0)
            drawEngines(unit);

        // 本体：代表类型贴图，按缩放系数
        Draw.z(z);
        applyColor(unit);
        if (region != null && Core.atlas.isFound(region)) {
            Draw.rect(region, unit.x, unit.y,
                    region.width * s * Draw.scl, region.height * s * Draw.scl, unit.rotation - 90);
        }
        Draw.reset();

        // 武器挂在本实体自己的 mounts 上（成员武器复制 + 环形排布），原版绘制循环直接可用
        drawWeaponOutlines(unit);
        drawWeapons(unit);

        // 成员能力特效（力场护盾/状态光环/维修波等）——原版画在 UnitType.draw 尾部
        // （abilities → drawBody），巨兽是完全自定义绘制，必须自己接这一段，
        // 否则能力只有逻辑生效、没有任何视觉（"没有力墙显示"）
        if (!isPayload) {
            for (mindustry.entities.abilities.Ability a : unit.abilities()) {
                Draw.reset();
                a.draw(unit);
            }
        }

        // 护盾（力场能力等）
        if (unit.shieldAlpha() > 0f && drawShields)
            drawShield(unit);
    }
}
