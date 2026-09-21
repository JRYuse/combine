package combine.units;

import arc.Events;
import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.util.Time;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.ai.types.MissileAI;
import mindustry.content.StatusEffects;
import mindustry.entities.Effect;
import mindustry.entities.Predict;
import mindustry.entities.Mover;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Bullet;
import mindustry.gen.Entityc;
import mindustry.gen.Groups;
import mindustry.entities.Sized;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.entities.units.WeaponMount;
import mindustry.type.Weapon;

/**
 * 组合火力共享：正在开火的单位可以调用组里空闲单位的武器代打。
 *
 * <p>每帧检查：组合中正在开火（{@code isShooting}）的单位 U，可以把组内
 * <b>空闲</b>成员（自己不在开火）的<b>已装填完毕</b>的武器借来发射——
 * 发射位置、射程判定全部以 U 为基准（武器挂在 U 身上的坐标计算），
 * 子弹归属 U（击杀/承伤都与 U 一致，反正同组合）。
 *
 * <p>复刻原版 {@code Weapon.shoot/bullet} 的完整流程（散布、寿命缩放、后座、
 * 弹壳/枪口特效、音效、射击状态）。持续型武器（激光等）逐帧维护借出光束
 * （位置/朝向跟随借入方、射程截断、keepAlive 续命，见 {@link #lendContinuous}）；
 * 充能型（firstShotDelay）武器仍不支持。只在逻辑端（服务端/主机/单人）结算。
 */
public class UnitComboFire{
    /** 总开关。 */
    public static boolean enabled = true;

    /**
     * 代打光束登记表：借出武器挂载点 → 光束弹体。
     *  deliberately 不放 {@code mount.bullet}——借出方自己的 {@code Weapon.update}
     * 持续武器段一旦发现 mount.bullet 非空，会每帧把弹体位置/朝向拉回借出方身上，
     * 表现就是"激光从原单位发射"而不是从借用方发射。
     */
    private static final arc.struct.ObjectMap<WeaponMount, Bullet> borrowedBeams = new arc.struct.ObjectMap<>();

    /** 在 mod init 时调用。 */
    public static void register(){
        Events.run(Trigger.update, UnitComboFire::update);
    }

    static void update(){
        if(!enabled || !UnitComboDamage.enabled || Vars.state.isMenu()) return;
        // 客户端不结算子弹（由服务端创建后同步），与承伤/修复同一规则
        if(Vars.net.client()) return;

        // 清理死光束登记（借出方死亡/停火后光束自然消亡）
        if(!borrowedBeams.isEmpty()){
            arc.struct.Seq<WeaponMount> stale = new arc.struct.Seq<>();
            for(var e : borrowedBeams.entries()){
                if(e.value == null || !e.value.isAdded() || e.value.time >= e.value.lifetime) stale.add(e.key);
            }
            for(WeaponMount m : stale) borrowedBeams.remove(m);
        }

        for(Unit u : Groups.unit){
            if(!UnitComboDamage.groupable(u) || !u.isShooting()) continue;
            double gid = UnitComboDamage.comboId(u);
            if(gid == 0.0) continue;

            // 借出方目标：优先取 U 自己武器正在跟踪的目标；没有（玩家瞄准型）就用 U 的瞄准点
            Teamc target = null;
            for(WeaponMount um : u.mounts()){
                if(um.shoot && um.target != null){
                    target = um.target;
                    break;
                }
            }

            for(Unit m : Groups.unit){
                if(m == u || m.team() != u.team() || m.isShooting()) continue;
                if(UnitComboDamage.comboId(m) != gid || !UnitComboDamage.groupable(m)) continue;
                if(UnitComboDamage.range > 0f && !m.within(u, UnitComboDamage.range + u.hitSize / 2f + m.hitSize / 2f)) continue;
                lend(u, m, target);
            }
        }
    }

    /** 把 m 的空闲且就绪的武器借给 u 发射一轮（持续型武器走 {@link #lendContinuous}）。 */
    private static void lend(Unit u, Unit m, Teamc target){
        arc.struct.Seq<Weapon> weapons = m.type.weapons;
        WeaponMount[] mounts = m.mounts();
        for(int i = 0; i < weapons.size && i < mounts.length; i++){
            Weapon w = weapons.get(i);
            WeaponMount mount = mounts[i];

            if(w.bullet == null) continue;
            if(w instanceof mindustry.type.weapons.RepairBeamWeapon) continue; // 维修光束只修己方建筑/单位，不代打
            if(w.bullet.heals() && w.bullet.damage <= 0f) continue;      // 纯治疗弹同理（vela 激光等伤害/治疗双用武器仍可代打）
            if(w.shoot.firstShotDelay > 0f && !w.continuous) continue; // 充能型不支持（持续型单独走光束逻辑）
            if(w.bullet.killShooter && mount.totalShots > 0 && !w.continuous) continue;

            // 发射位置以 u 为基准：武器挂载点按 u 的坐标/朝向计算
            float mountX = u.x + Angles.trnsx(u.rotation - 90, w.x, w.y);
            float mountY = u.y + Angles.trnsy(u.rotation - 90, w.x, w.y);

            float range = w.range() + Math.abs(w.shootY) + (target instanceof Sized s ? s.hitSize() / 2f : 0f);

            if(w.continuous){
                lendContinuous(u, w, mount, mountX, mountY, range, target);
                continue;
            }

            if(mount.reload > 0.0001f) continue;                      // 尚未装填完毕

            float aimX, aimY;
            if(target != null){
                if(!target.within(mountX, mountY, range)) continue;
                // 弹道预测（每把武器弹速不同，分别算）
                Vec2 to = Predict.intercept(u, target, w.bullet);
                aimX = to.x;
                aimY = to.y;
            }else{
                aimX = u.aimX;
                aimY = u.aimY;
                // 手动瞄准且目标点在射程外：沿瞄准方向缩到射程边缘再开火——
                // 长按任意位置都能调动全组空闲武器齐射，而不是必须长按该武器射程内
                float d = Mathf.dst(mountX, mountY, aimX, aimY);
                if(d > range && d > 0.001f){
                    float scl = range / d;
                    aimX = mountX + (aimX - mountX) * scl;
                    aimY = mountY + (aimY - mountY) * scl;
                }
            }

            // 与原版本一致的速度约束
            float velLen = u.isRemote() ? u.vel.len() : u.deltaLen() / Time.delta;
            if(velLen < w.minShootVelocity || (w.maxShootVelocity != -1 && velLen > w.maxShootVelocity)) continue;

            // 同步 mount 瞄准与朝向（旋转武器视觉上对准目标）
            mount.aimX = aimX;
            mount.aimY = aimY;
            mount.target = target;
            if(w.rotate){
                mount.rotation = Angles.angle(mountX, mountY, aimX, aimY) - u.rotation;
                mount.targetRotation = mount.rotation;
            }
            if(mount.warmup < w.minWarmup) mount.warmup = w.minWarmup;

            fire(u, w, mount);

            mount.reload = w.reload;
            mount.shoot = true;
            mount.totalShots ++;
            if(w.shootStatus != StatusEffects.none) u.apply(w.shootStatus, w.shootStatusDuration);
        }
    }

    /**
     * 持续型武器（激光等）代打：以 u 为基准创建光束弹体并逐帧续命，
     * 复刻原版 {@code Weapon.update} 的持续武器维护段（位置/朝向跟随 u、
     * 射程截断瞄准点、{@code time} 回调到最优寿命比例、{@code keepAlive} 续命）。
     * u 停火后光束自然烧完弹体寿命结束（与原版松开开火键一致）。
     */
    private static void lendContinuous(Unit u, Weapon w, WeaponMount mount,
                                       float mountX, float mountY, float range, Teamc target){
        // 期望瞄准点（射程截断）与是否维持开火
        float aimX, aimY;
        boolean wantFire;
        if(target != null){
            wantFire = target.within(mountX, mountY, range);
            aimX = target.x();
            aimY = target.y();
        }else{
            aimX = u.aimX;
            aimY = u.aimY;
            wantFire = Mathf.dst(mountX, mountY, aimX, aimY) > 1f;   // 瞄着自己脚下就不打
        }

        // 维护既有光束（跟随 u 的位置与朝向，每帧续命）
        Bullet tracked = borrowedBeams.get(mount);
        if(tracked != null){
            Bullet b = tracked;
            if(!b.isAdded() || b.time >= b.lifetime || b.type != w.bullet){
                borrowedBeams.remove(mount);
            }else{
                float weaponRotation = u.rotation - 90 + (w.rotate ? mount.rotation : w.baseRotation);
                b.rotation(weaponRotation + 90);
                b.set(mountX, mountY);
                mount.reload = w.reload;
                mount.recoil = 1f;
                // 原版 Weapon.update 的 warmupTarget 含 (continuous && mount.bullet != null)，
                // 光束存续期间预热保持满值；借出方的光束登记在 borrowedBeams 而非 mount.bullet，
                // 不主动维持的话预热会被借出方自己的更新衰减成 0。
                // 用 approach（每帧固定步进）顶住借出方每帧向 0 的 lerp 衰减，钉在满预热
                mount.warmup = Mathf.approachDelta(mount.warmup, 1f, w.shootWarmupSpeed);
                if(wantFire){
                    // 射程截断：光束长度向"到瞄准点的距离（不超过射程）"平滑跟进
                    float shootLength = Math.min(Mathf.dst(mountX, mountY, aimX, aimY), range);
                    float curLength = Mathf.dst(b.aimX, b.aimY, mountX, mountY);
                    float resultLength = Mathf.approachDelta(curLength, shootLength, w.aimChangeSpeed);
                    Tmp.v1.trns(weaponRotation, resultLength).add(mountX, mountY);
                    b.aimX = Tmp.v1.x;
                    b.aimY = Tmp.v1.y;
                    // 原版只对 alwaysContinuous 武器回拨 time/keepAlive；普通持续武器
                    // （如 vela 激光 lifetime=160、reload=155）就是要烧完弹体寿命后
                    // 走完整冷却+充能再出光——无条件续命会把它们锁成永动机：
                    // 无限激光、毫无间隔
                    if(w.alwaysContinuous){
                        b.time = b.lifetime * b.type.optimalLifeFract * mount.warmup;
                        b.keepAlive = true;
                        if(w.shootStatus != StatusEffects.none) u.apply(w.shootStatus, w.shootStatusDuration);
                    }
                }
                return;
            }
        }

        // 光束不存在：装填完毕且目标可打时创建新光束
        if(!wantFire || mount.reload > 0.0001f) return;

        mount.aimX = aimX;
        mount.aimY = aimY;
        mount.target = target;
        if(w.rotate){
            mount.rotation = Angles.angle(mountX, mountY, aimX, aimY) - u.rotation;
            mount.targetRotation = mount.rotation;
        }
        if(mount.warmup < w.minWarmup) mount.warmup = w.minWarmup;

        if(w.shoot.firstShotDelay > 0f){
            // 充能型持续武器（如 vela）：先充能再出光。立即扣装填防止每帧重复排程；
            // 任务执行时条件可能已变（停火/出射程），此时放弃出光走自然冷却。
            float delay = w.shoot.firstShotDelay;
            mount.reload = w.reload;
            mount.shoot = true;
            mount.totalShots ++;
            Time.run(delay, () -> {
                if(!u.isAdded() || !u.isShooting()) return;
                float wr = u.rotation - 90 + (w.rotate ? mount.rotation : w.baseRotation);
                float mx = u.x + Angles.trnsx(u.rotation - 90, w.x, w.y);
                float my = u.y + Angles.trnsy(u.rotation - 90, w.x, w.y);
                spawnBullet(u, w, mount, wr, mx, my, 0f, 0f, 0f, null);
                trackBeam(mount);
            });
            return;
        }

        fire(u, w, mount);
        trackBeam(mount);

        mount.reload = w.reload;
        mount.shoot = true;
        mount.totalShots ++;
    }

    /**
     * 把刚创建的光束弹体从 {@code mount.bullet} 转入 {@link #borrowedBeams} 登记——
     * 否则借出方自己的 Weapon.update 会持续武器段每帧把弹体拉回借出方位置。
     */
    private static void trackBeam(WeaponMount mount){
        if(mount.bullet != null){
            borrowedBeams.put(mount, mount.bullet);
            mount.bullet = null;
        }
    }

    /** 复刻 Weapon.shoot：按射击模式逐管开火。 */
    private static void fire(Unit u, Weapon w, WeaponMount mount){
        float weaponRotation = u.rotation - 90 + (w.rotate ? mount.rotation : w.baseRotation);
        float mountX = u.x + Angles.trnsx(u.rotation - 90, w.x, w.y);
        float mountY = u.y + Angles.trnsy(u.rotation - 90, w.x, w.y);

        w.shoot.shoot(mount.barrelCounter, (xOffset, yOffset, angle, delay, mover) -> {
            int barrel = mount.barrelCounter;
            if(delay > 0f){
                Time.run(delay, () -> {
                    // 与原版一致的枪管计数保护
                    int prev = mount.barrelCounter;
                    mount.barrelCounter = barrel;
                    spawnBullet(u, w, mount, weaponRotation, mountX, mountY, xOffset, yOffset, angle, mover);
                    mount.barrelCounter = prev;
                });
            }else{
                spawnBullet(u, w, mount, weaponRotation, mountX, mountY, xOffset, yOffset, angle, mover);
            }
        }, () -> mount.barrelCounter++);
    }

    /** 复刻 Weapon.bullet：以 u 为基准计算弹道并创建子弹。 */
    private static void spawnBullet(Unit u, Weapon w, WeaponMount mount, float weaponRotation,
                                    float mountX, float mountY, float xOffset, float yOffset, float angleOffset, Mover mover){
        if(!u.isAdded()) return;

        mount.charging = false;
        float
        xSpread = Mathf.range(w.xRand),
        ySpread = Mathf.range(w.yRand),
        bulletX = mountX + Angles.trnsx(weaponRotation, w.shootX + xOffset + xSpread, w.shootY + yOffset + ySpread),
        bulletY = mountY + Angles.trnsy(weaponRotation, w.shootX + xOffset + xSpread, w.shootY + yOffset + ySpread),
        shootAngle = (w.rotate ? u.rotation + mount.rotation
            : Angles.angle(bulletX, bulletY, mount.aimX, mount.aimY) + (u.rotation - u.angleTo(mount.aimX, mount.aimY)) + w.baseRotation) + angleOffset,
        baseLife = (1f - w.lifeRnd) + Mathf.random(w.lifeRnd) + w.extraLife,
        lifeScl = w.bullet.scaleLife ? baseLife * Mathf.clamp(Mathf.dst(bulletX, bulletY, mount.aimX, mount.aimY) / w.bullet.range) : baseLife,
        angle = shootAngle + Mathf.range(w.inaccuracy + w.bullet.inaccuracy);

        Entityc shooter = u.controller() instanceof MissileAI ai ? ai.shooter : u;
        Bullet b = w.bullet.create(u, shooter, u.team, bulletX, bulletY, angle, -1f,
            (1f - w.velocityRnd) + Mathf.random(w.velocityRnd) + w.extraVelocity, lifeScl, null, mover, mount.aimX, mount.aimY, mount.target);
        mount.bullet = b;
        // 非持续武器的 handleBullet 是空操作，持续武器已排除，无需调用

        if(w.continuous){
            w.initialShootSound.at(bulletX, bulletY, Mathf.random(w.soundPitchMin, w.soundPitchMax), w.shootSoundVolume);
        }else{
            w.shootSound.at(bulletX, bulletY, Mathf.random(w.soundPitchMin, w.soundPitchMax), w.shootSoundVolume);
        }

        if(mount.allowShootEffects){
            w.ejectEffect.at(mountX, mountY, angle * Mathf.sign(w.x));
            w.bullet.shootEffect.at(bulletX, bulletY, angle, w.bullet.hitColor, u);
            w.bullet.smokeEffect.at(bulletX, bulletY, angle, w.bullet.hitColor, u);
        }

        u.vel.add(Tmp.v1.trns(shootAngle + 180f, w.bullet.recoil));
        Effect.shake(w.shake, w.shake, bulletX, bulletY);
        mount.recoil = 1f;
        if(w.recoils > 0){
            mount.recoils[mount.barrelCounter % w.recoils] = 1f;
        }
    }
}
