package combine.units.mega;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

/**
 * 组合巨兽的单位类型。无自己的贴图——绘制时取成员中"代表类型"
 * （数量最多；并列取血量最少）的贴图，并按综合 hitSize 缩放
 * （缩放系数由 {@link MegaUnitEntity#drawScale} 提供，双端各自从成员构成推导，无需同步）。
 *
 * <p>三个变体（地面/飞行/海军）由 {@link combine.units.UnitComboMerge} 创建：
 * 飞行变体 {@code flying = true}；海军变体的语义在 {@link MegaUnitEntity} 里
 * （碰撞判定按水面处理）。生命/护甲/碰撞半径/武器/能力都是融合那一刻
 * 写在实体实例上的（见 MegaUnitEntity.refreshDerived），类型的同名字段只是兜底默认值。
 */
public class MegaUnitType extends UnitType{

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
     * flare hitSize 9、engineOffset 5.75、engineSize 2.5 → 位置 0.639×hitSize、大小 0.278×hitSize。
     * 成员里有飞行单位时，{@link MegaUnitEntity} 会按那台单位自己的引擎参数改写这两个比例
     * （身体贴图就是按同一套缩放画的，引擎跟着同源才不会一个巨大一个迷你）。
     */
    public float engineOffsetRatio = refEngineOffset / refHitSize, engineSizeRatio = refEngineSize / refHitSize;

    /**
     * 补齐 {@link UnitType#init()} / {@link UnitType#load()} 才会写的那些字段。
     *
     * 巨兽类型是模组 init 期 late 注册的，这两个方法**从没跑过**，而其中两个字段是"无效默认值"：
     * <ul>
     *     <li>{@code flyingLayer = -1}：混合编组里有飞行成员（比如 mace + oct）时，
     *         {@code MegaUnitEntity.moveMode()} 会让 elevation 逼近 1，绘制走 "elevation &gt; 0.5 → flyingLayer"，
     *         于是 {@code Draw.z(-1)} —— 比地板层还低，整个单位被地形盖住。
     *         表现就是用户报的"mace 和 oct 组合后不绘制单位身体"（贴图其实一直在画，只是画在了地板下面）。</li>
     *     <li>{@code clipSize = -1}：视口裁剪（{@code EntityGroup.draw}）按它算包围盒，
     *         负尺寸等于"只剩中心点"，单位贴着屏幕边就会被整块剔掉。</li>
     * </ul>
     * 另外 {@code lightRadius = -1} 会让巨兽没有灯光。hitSize 会在融合时按成员重算，
     * 所以这个方法在构造期和每次推导之后都要调一次。
     */
    public void applyLateDefaults(){
        if(lowAltitude){
            flyingLayer = Layer.flyingUnitLow;
        }else if(flyingLayer < 0f){
            flyingLayer = Layer.flyingUnit;
        }
        if(lightRadius < 0f){
            lightRadius = Math.max(60f, hitSize * 2.3f);
        }
        clipSize = Math.max(Math.max(clipSize, lightRadius * 1.1f), hitSize * 2.4f);
        rebuildEngines();
    }

    /**
     * 按体型生成引擎。
     *
     * 原版是在 {@link UnitType#init()} 里按 {@code engineSize/engineOffset} 建 {@code engines} 的，
     * 而巨兽类型是 late 注册、init() 从没跑过 —— {@code engines} 永远是空表，
     * 于是"会飞的巨兽"一点尾焰都没有。这里按 hitSize × 比例算出尺寸与位置
     * （比例默认取自 flare；有飞行成员时由 MegaUnitEntity 换成那台单位的参数）。
     */
    public void rebuildEngines(){
        engines.clear();
        // 不会升空的巨兽不需要引擎（地面时 elevation=0，引擎本来就画不出来）
        if(!flying && !hoverEngines) return;

        engineOffset = hitSize * engineOffsetRatio;
        engineSize = hitSize * engineSizeRatio;
        if(engineOffset <= 0.01f || engineSize <= 0.01f) return;

        // 和 flare / oct 一样居中一个引擎（引擎是圆，跟着 elevation 缩放，不会越界）
        engines.add(new UnitEngine(0f, -engineOffset, engineSize, -90f));
    }

    public MegaUnitType(String name){
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
        drawMinimap = false;
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
    static mindustry.type.Weapon newWeapon(){
        mindustry.type.Weapon w = new mindustry.type.Weapon("combine-ghost-weapon");
        w.bullet = new mindustry.entities.bullet.BulletType(1f, 0f);
        w.bullet.lifetime = 10000f;
        w.predictTarget = false;
        return w;
    }

    /**
     * 本体贴图：优先用成员代表类型的整图（{@code fullIcon}，塞普罗原版单位这里是
     * {@code unit-<名字>-full} 整图），找不到再退到身体贴图 / UI 图标。
     * 巨兽类型自己没有任何贴图（{@code region} 恒为 null），所以这里必须逐个兜底，
     * 否则 {@code Draw.rect(null)} 直接什么都不画（"不绘制单位身体"的另一半）。
     */
    static TextureRegion bodyRegion(UnitType dom){
        if(dom != null){
            if(dom.fullIcon != null && Core.atlas.isFound(dom.fullIcon)) return dom.fullIcon;
            if(dom.region != null && Core.atlas.isFound(dom.region)) return dom.region;
            if(dom.uiIcon != null && Core.atlas.isFound(dom.uiIcon)) return dom.uiIcon;
        }
        return null;
    }

    @Override
    public void draw(Unit unit){
        if(unit.inFogTo(mindustry.Vars.player.team())) return;

        MegaUnitEntity mu = unit instanceof MegaUnitEntity m ? m : null;
        UnitType dom = mu != null && mu.dominant != null ? mu.dominant : null;
        TextureRegion region = bodyRegion(dom);
        float s = mu == null ? 1f : Mathf.clamp(mu.drawScale, 0.5f, 8f);
        boolean isPayload = !unit.isAdded();
        float z = isPayload ? Draw.z()
            : (unit.elevation > 0.5f || (flying && unit.dead) ? (flyingLayer < 0f ? Layer.flyingUnitLow : flyingLayer)
            : groundLayer + Mathf.clamp(unit.hitSize / 4000f, 0f, 0.01f));

        // 空中阴影（按代表类型的阴影贴图、按缩放系数放大）
        if(!isPayload && (unit.isFlying() || shadowElevation > 0)){
            TextureRegion sh = dom != null && dom.shadowRegion != null && Core.atlas.isFound(dom.shadowRegion)
                ? dom.shadowRegion : shadowRegion;
            if(sh != null && Core.atlas.isFound(sh)){
                float e = Mathf.clamp(unit.elevation, shadowElevation, 1f) * shadowElevationScl * (1f - unit.drownTime);
                Draw.z(Math.min(Layer.darkness, z - 1f));
                Draw.color(Pal.shadow, Pal.shadow.a * unit.shadowAlpha);
                Draw.rect(sh, unit.x + shadowTX * e, unit.y + shadowTY * e,
                    sh.width * s * Draw.scl, sh.height * s * Draw.scl, unit.rotation - 90);
                Draw.color();
            }
        }

        // 【先引擎、后机身】——和原版 UnitType.draw 同一个顺序（引擎 → Draw.z(z) → drawBody）：
        // 引擎是画在机身**下面**的，机身盖住它朝内那半圈，看上去才是"喷口从机身尾部喷出来"。
        // 反过来（机身先、引擎后）就是用户看到的"引擎糊在单位身上"。
        Draw.z(z);
        if(engines.size > 0) drawEngines(unit);

        // 本体：代表类型贴图，按缩放系数
        Draw.z(z);
        applyColor(unit);
        if(region != null && Core.atlas.isFound(region)){
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
        if(!isPayload){
            for(mindustry.entities.abilities.Ability a : unit.abilities()){
                Draw.reset();
                a.draw(unit);
            }
        }

        // 护盾（力场能力等）
        if(unit.shieldAlpha() > 0f && drawShields) drawShield(unit);
    }
}
