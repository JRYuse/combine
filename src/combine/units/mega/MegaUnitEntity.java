package combine.units.mega;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Tmp;
import arc.util.Log;
import arc.util.io.Reads;
import arc.util.io.Writes;
import combine.units.UnitComboMerge;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.entities.EntityCollisions;
import mindustry.entities.Leg;
import mindustry.entities.abilities.Ability;
import mindustry.entities.units.WeaponMount;
import mindustry.gen.Crawlc;
import mindustry.gen.Legsc;
import mindustry.gen.Tankc;
import mindustry.gen.Unit;
import mindustry.gen.UnitEntity;
import mindustry.graphics.InverseKinematics;
import mindustry.io.TypeIO;
import mindustry.type.UnitType;
import mindustry.type.Weapon;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.payloads.Payload;
import mindustry.world.blocks.payloads.UnitPayload;

/**
 * 组合巨兽实体：整个单位组合融合成的单一大型单位。
 *
 * <p><b>重构说明（v3）</b>：早期版本继承 PayloadUnit、把成员塞进原版货舱，
 * 靠 8 个 no-op 覆盖屏蔽卸货/拾取。这套做法带来一堆副作用（长按被当卸货、
 * 货运交互误触、客户端 elevation/视觉异常）。现在改为继承最普通的
 * {@link UnitEntity}——巨兽就是一个"普通单位"，原版对单位的一切判定
 * （指挥、碰撞、绘制、AI、玩家操控）原样生效，没有任何货运语义需要封堵。
 *
 * <p>成员由本类<b>自管</b>：成员作为 {@link UnitPayload} 存放在自己的
 * {@link #members} 列表里，进出只走 {@link UnitComboMerge#merge}/{@link UnitComboMerge#split}。
 * 网络同步/存档的字节格式与原版货舱完全一致（每条成员一次
 * {@link TypeIO#writePayload} 全量内联写入），双端都装本模组就天然一致，
 * 不需要额外自定义同步协议。
 *
 * <p>巨兽的全部战斗属性都从成员构成<b>推导</b>（{@link #refreshDerived()}）：
 * <ul>
 *     <li>生命上限 = Σ 成员类型生命（血量叠加）；当前生命值在融合那一刻 = Σ 成员当前生命，
 *         之后就是普通单位血量，走原版同步/存档；</li>
 *     <li>护甲 = Σ 成员类型护甲（实例字段，服务端结算伤害时生效）；</li>
 *     <li>碰撞半径 = sqrt(Σ hitSize²)（面积守恒的合理综合）；</li>
 *     <li>武器 = 全部成员武器复制后环绕本体排布（原位置偏移对小单位合理、对巨兽会挤成一团），
 *         直接装进本实体自己的 mounts 数组，原版武器更新/绘制循环原样接管；</li>
 *     <li>能力 = 全部成员类型能力的副本，装进本实体自己的 abilities 数组，
 *         原版能力更新循环原样接管；</li>
 *     <li>贴图代表类型 = 数量最多的成员类型（数量并列时取 type.health **最大**的），见 {@link UnitComboMerge}。</li>
 * </ul>
 *
 * <p>推导只在成员构成变化时发生（用构成签名缓存），网络同步快照和存档读取后各推导一次。
 * 移动能力（飞/游/跑）同样从成员构成推导，按当前地形动态切换（{@link #moveMode()}）：
 * 有飞行成员就能飞、有海军成员就能游、有陆地成员就能跑，见 {@link #update()} 的高度驱动
 * 与 solidity/canDrown/onSolid 的碰撞语义。
 */
public class MegaUnitEntity extends UnitEntity implements Legsc, Crawlc, Tankc{
    /** 网络/存档用的类 ID，由 UnitComboMerge.register() 登记 EntityMapping 后回填。 */
    public static int CLASS_ID = 255;

    /** 贴图代表类型（数量最多；数量并列时取血量最大的），由成员构成推导。 */
    public transient UnitType dominant;
    /** 贴图缩放 = 综合 hitSize / 代表类型 hitSize，由成员构成推导。 */
    public transient float drawScale = 1f;
    /**
     * 身体部件（腿/机甲腿/履带/爬虫身）的种类，按代表类型判定（{@link #attachmentKind}）。
     * 巨兽实体不是原版的"腿类/机甲/履带/爬虫"实体，这些部件原版从来不会画，
     * 必须自己按这个种类分派（见 {@link MegaUnitType#drawAttachments} + {@link #updateAttachments()}）。
     */
    public transient int attKind = ATT_NONE;

    /** 部件种类常量。 */
    public static final int ATT_NONE = 0, ATT_LEGS = 1, ATT_MECH = 2, ATT_CRAWL = 3, ATT_TANK = 4;

    /**
     * 腿（Legsc）的骨骼状态。腿段位置是<b>世界坐标</b>，每帧在 {@link #updateAttachments()} 里
     * 按巨兽的位置/速度跑一遍 IK（原版 LegsComp.update 那套算法，长度按 {@link #bodyScale()} 放大）。
     */
    public transient Leg[] legs = {};
    public transient float legTotalLength, legMoveSpace, legBaseRotation;
    public transient Vec2 legCurMoveOffset = new Vec2();
    /** 上次 resetLegs 用的缩放，缩放变了（成员构成变了）要重新摆一次腿的初始位置。 */
    private transient float legResetScale = -1f;
    private static final Vec2 StraightVec = new Vec2();
    private transient Floor lastDeepFloor;

    /** 机甲（Mechc）的行走相位：baseRotation 与腿共用 legBaseRotation，这里只累加走过的距离。 */
    public transient float mechWalkTime;
    /** 爬虫（Crawlc）的摆动相位（原版的 crawlTime / segmentRot）。 */
    public transient float crawlTime = Mathf.random(100f), crawlSegmentRot;
    /** 履带（Tankc）的滚动相位。 */
    public transient float treadTime;
    private transient boolean walkedState;
    /**
     * 成员移动能力（构成推导）：有飞行成员就能飞、有海军成员就能游、有陆地成员就能跑。
     * 移动模式按当前地形动态选择（{@link #moveMode()}），不再用"多数决"定死一个形态——
     * 否则混合编组（飞机+坦克）合体后飞机的能力直接丢失。
     */
    private transient boolean hasFlyer = false, hasNaval = false, hasGround = false;
    /** 成员里有没有爬爬虫（Crawlc）：它们在深水里的速度系数和普通单位不一样（见 {@link #floorSpeedMultiplier()}）。 */
    private transient boolean hasCrawler = false;

    /** 移动模式：走路 / 游泳 / 飞行 / 搁浅（纯海军组在陆地上，原版海军语义）。 */
    public static final int MODE_WALK = 0, MODE_SWIM = 1, MODE_FLY = 2, MODE_STUCK = 3;

    /**
     * 构成签名 → 派生类型缓存。原版大量功能（采矿/建造/速度/物品上限/指令面板…）直接读
     * {@code unit.type} 的静态字段，实例级覆盖不了——所以给每种成员构成派生一个真正的
     * 类型实例，让 {@code unit.type} 本身就是"按成员算好的类型"。
     *
     * <p>派生类型不进内容列表（内容 id 空间在加载期就固定了）；所有派生类型共用已注册的
     * 基础巨兽 content id 作占位——快照/存档里写的类型 id 就是它，对端解析出基础类型后
     * 由我们附加在快照/存档末尾的成员列表重建出签名一致的同一个派生类型。
     * 双端跑同一份 mod 代码，推导结果天然一致，类型本身永远不需要网络同步。
     */
    private static final ObjectMap<Integer, MegaUnitType> compTypeCache = new ObjectMap<>();
    /**
     * AI 索敌半径（原版 UnitType.init 才会按武器算 type.range，巨兽类型是 late 注册、
     * init 从不执行，type.range 停在默认值 -1——AI 索敌走 unit.range()，在这里给实例值）。
     */
    public transient float megaRange = 240f;
    /** 成员构成签名（成员数 + 各成员类型 id 混合），变化时才重建武器/能力挂载。 */
    private transient int lastSig = -1;
    /**
     * 当前 mounts/abilities 是按哪个构成签名建出来的（{@link #rebuildMounts()} 里回填）。
     *
     * <p>原版 {@code afterSync}/{@code afterRead} 每个同步快照都会走 {@code setType} →
     * {@code setupWeapons}。以前这两条路都是"无条件重建"，而重建会换掉整个 mounts/abilities
     * 数组：武器挂载的装填/冷却/瞄准状态被清零，能力的内部状态也被清零 —— 力场
     * （{@code ForceFieldAbility}）的展开动画 {@code radiusScale} 每帧 lerp 涨、又被快照压回 0，
     * 画出来就是用户报的"联机时力墙一直放大缩小"。构成没变就绝不重建。
     */
    private transient int mountSig = -1;
    /**
     * 最近一次由我们建出来的 mounts/abilities 数组本体。
     *
     * <p>原版 {@code setType} 里有两段"按 type 重建数组"的代码：{@code mounts.length !=
     * type.weapons.size} 就 {@code setupWeapons}、{@code abilities.length != type.abilities.size}
     * （或首元素是 {@code EmptyDataAbility}）就按 {@code type.abilities} 重新 copy 一遍。
     * 巨兽的**占位类型**上 weapons 只有那门幽灵武器、abilities 是空的，于是每来一个同步快照
     * （客户端每秒几十个）都会把按成员构成建好的数组换掉：武器装填/冷却状态清零、
     * 力场能力的展开动画（{@code radiusScale}）清零 —— 画出来就是"巨兽的力墙一直放大缩小"。
     * 用数组本体比对，被换掉才重建，没被换就原样保留。
     */
    private transient WeaponMount[] builtMounts;
    private transient Ability[] builtAbilities;
    /** 上次推导结果的快照，用于识别"构成没变但实例数据被 setType 重置"的情况。 */
    private transient int lastMounts = -1, lastAbilities = -1;
    private transient float lastHit = -1f, lastMax = -1f;

    /** 被封存的成员。同步/存档时逐条全量内联写出，见 writeSync/readSync/write/read。 */
    private final Seq<UnitPayload> members = new Seq<>();

    public MegaUnitEntity(){
    }

    @Override
    public int classId(){
        return CLASS_ID;
    }

    /** 成员列表（UnitComboMerge 融合/解体、composition 构成统计用）。 */
    public Seq<UnitPayload> members(){
        return members;
    }

    /** 成员数量。 */
    public int memberCount(){
        return members.size;
    }

    /** 封存一名成员（融合用；成员应已 remove 出世界）。 */
    public void addMember(Unit m){
        members.add(new UnitPayload(m));
    }

    /** 清空成员（解体放出后调用，避免 remove() 连带处理）。 */
    public void clearMembers(){
        members.clear();
    }

    /** 移除指定成员（解体时仅放出部分成员的场景）。 */
    public void removeMember(UnitPayload up){
        members.remove(up);
    }

    /** 成员构成是否变化，变化则重新推导全部衍生属性。 */
    public void refreshDerived(){
        refreshDerived(false);
    }

    /**
     * 成员构成签名：成员数 + 各成员类型 id 混合（同一份构成永远同一个签名，两端各自推导一致）。
     * 派生类型缓存、挂载/能力是否要重建，都以它为准。
     */
    public int compositionSig(){
        int sig = members.size;
        for(int i = 0; i < members.size; i++){
            UnitPayload up = members.get(i);
            if(up != null && up.unit != null && up.unit.type != null){
                sig = sig * 31 + up.unit.type.id + 1;
            }else{
                sig = sig * 31 + 1;
            }
        }
        return sig;
    }

    /** @param force 跳过签名检查强制重建（融合瞬间用）。 */
    public void refreshDerived(boolean force){
        int sig = compositionSig();
        boolean sameComp = sig == lastSig;
        // 跳过条件不只看出构成签名：原版 afterSync/afterRead 会调 setType(this.type)，
        // 把 hitSize/maxHealth 重置成类型兜底值、并按 type.weapons(恒为空) 重建 mounts——
        // 构成签名没变但实例数据已被清空，必须用快照比对识别出来并重建。
        WeaponMount[] curMounts = mounts();
        Ability[] curAbilities = abilities();
        if(!force && sameComp
            && curMounts != null && curMounts.length == lastMounts
            && curAbilities != null && curAbilities.length == lastAbilities
            && Math.abs(hitSize() - lastHit) < 0.01f
            && Math.abs(maxHealth() - lastMax) < 0.01f) return;
        lastSig = sig;

        float sumHit2 = 0f, sumMax = 0f, sumArmor = 0f;
        int count = 0;
        UnitType dom = null;
        int domCount = 0;
        ObjectMap<UnitType, Integer> tally = new ObjectMap<>();
        boolean fly = false, nav = false, gnd = false, crawl = false;

        for(int i = 0; i < members.size; i++){
            UnitPayload up = members.get(i);
            if(up == null || up.unit == null || up.unit.type == null) continue;
            UnitType t = up.unit.type;
            count++;

            // 移动能力：有飞机就能飞、有海军就能游、有陆地就能跑
            if(t.flying){
                fly = true;
            }else if(UnitComboMerge.isNaval(t)){
                nav = true;
            }else{
                gnd = true;
                // 爬爬虫（Crawlc）在深水里的速度系数是原版写死的 0.45，和普通单位不同
                try{
                    if(t.constructor != null && t.constructor.get() instanceof mindustry.gen.Crawlc) crawl = true;
                }catch(Throwable ignored){
                }
            }

            float h = Math.max(t.hitSize, 1f);
            sumHit2 += h * h;
            sumMax += Math.max(t.health, 1f);
            sumArmor += t.armor;

            // 贴图代表类型：数量最多；数量并列时取 type.health **最大**的那个（用户要求：
            // 两种单位各占一半时显示更"壮"的那只，而不是血少的那只）。
            int c = tally.get(t, 0) + 1;
            tally.put(t, c);
            if(dom == null || c > domCount || (c == domCount && t.health > dom.health)){
                dom = t;
                domCount = c;
            }
        }
        if(count == 0 || dom == null) return;

        hasFlyer = fly;
        hasNaval = nav;
        hasGround = gnd;
        hasCrawler = crawl;
        dominant = dom;
        maxHealth(Math.max(sumMax, 1f));
        armor(sumArmor);
        float combined = (float)Math.sqrt(sumHit2);
        hitSize(Math.max(combined, 1f));
        drawScale = combined / Math.max(dom.hitSize, 1f);
        // 身体部件种类（腿/机甲腿/履带/爬虫身）：变了就重摆一次腿的初始姿势 / 重置相位
        attKind = attachmentKind(dom);
        if(attKind == ATT_LEGS){
            float scl = bodyScale();
            if(legs.length != dom.legCount || Math.abs(scl - legResetScale) > 0.001f) resetLegs();
        }else if(attKind == ATT_CRAWL){
            crawlSegmentRot = rotation;
        }

        // 按构成签名换绑派生类型：采矿/建造/速度/物品上限等"原版只读 type 静态字段"
        // 的能力从此按成员构成生效；同步/存档只写占位 id，对端按成员列表重建同一类型
        type = compTypeFor(sig, dom, tally, sumMax, sumArmor, combined);

        // 控制器兜底：原版是 setType() 里 "controller == null 就用 type.controller 造一个"，
        // 而派生类型是**直接换 type 字段**（不经过 setType），派生类型又是 late 注册的。
        // 控制器为 null 的单位在指挥模式里会被原版每帧踢出框选集
        // （DesktopInput: selectedUnits.removeAll(u -> !u.allowCommand())），
        // 表现就是"巨兽变成点不动任何指令的幽灵单位、只能附身操控"。
        if(controller() == null){
            try{
                resetController();
            }catch(Throwable ignored){
            }
        }

        // 【只在构成变化、或数组被原版换掉时才重建挂载】原版每个同步快照都会 setType →
        // "按 type 重建 mounts/abilities"（巨兽占位类型的 weapons/abilities 是空的），
        // 而重建会换掉数组、把武器装填状态与能力内部状态（力场展开动画）清零；
        // 客户端每秒收到几十个快照 → 力场永远"刚展开就被压回去" = 用户报的"力墙一直放大缩小"。
        // 构成没变、数组还是我们建的那份，就只重算标量（生命/体型/类型），挂载与能力原样留着。
        if(force || !sameComp || mounts() != builtMounts || abilities() != builtAbilities){
            rebuildMounts();
        }

        // 记录推导结果快照，供下次跳过条件比对（识别 setType 重置）
        WeaponMount[] cur = mounts();
        lastMounts = cur == null ? 0 : cur.length;
        Ability[] cab = abilities();
        lastAbilities = cab == null ? 0 : cab.length;
        lastHit = hitSize();
        lastMax = maxHealth();
    }

    /**
     * 按成员构成重建武器环与能力数组（融合/推导/setType 自愈共用）。
     * 武器 = 全部成员武器复制后环绕本体排布；能力 = 成员类型能力的副本。
     */
    private void rebuildMounts(){
        Seq<Weapon> ws = new Seq<>();
        Seq<Ability> abs = new Seq<>();
        // 力场（ForceFieldAbility）要**合并成一份**，不能按成员各留一份：
        //  · 各自的 max 只覆盖自己那份，而巨兽的护盾池是全员之和（融合时 mega.shield(Σ)），
        //    于是 HUD/信息面板上的"力墙条"用盾量 ÷ 单个成员的上限 —— 一旦组里有两台带力场的
        //    单位（或一台的盾本来就满），条就超过 100%（用户报的"力墙 bar 超上限了"）；
        //  · 多份力场还会各自回复（护盾回血速度翻倍）、各自画一圈护盾、各自扣伤害。
        // 合并语义：半径取最大、回复速率相加、上限相加、冷却取最大。
        mindustry.entities.abilities.ForceFieldAbility mergedField = null;
        for(int i = 0; i < members.size; i++){
            UnitPayload up = members.get(i);
            if(up == null || up.unit == null || up.unit.type == null) continue;
            UnitType t = up.unit.type;

            // 该成员的武器副本。镜像武器对（原版 init 展开成相邻两条、互相用
            // otherSide 记挂载索引）必须按本巨兽 mounts 数组内的实际位置重映射——
            // 否则副本带着成员自己布局里的旧索引，指向别的成员的挂载，
            // alternate 轮流开火状态机会把整组武器的卡壳（表现为"只有一两个武器工作"）。
            int base = ws.size;
            arc.struct.IntSeq origIdx = new arc.struct.IntSeq();
            Seq<Weapon> mws = new Seq<>();
            for(int k = 0; k < t.weapons.size; k++){
                Weapon w = t.weapons.get(k);
                if(w.bullet == null) continue;
                origIdx.add(k);
                // 镜像武器对的 reload 已被原版 init 翻倍（两条轮流打、合计射速=定义值）。
                // 副本必须原样保留这个翻倍值：配合上面的 otherSide 重映射轮流开火，
                // 合体后的射速才与成员单体一致——减半会翻倍，不减半（旧 bug）会卡壳慢一半。
                mws.add(w.copy());
            }
            for(int k = 0; k < mws.size; k++){
                Weapon c = mws.get(k);
                if(c.otherSide >= 0){
                    int pos = origIdx.indexOf(c.otherSide);
                    c.otherSide = pos < 0 ? -1 : base + pos;
                }
                ws.add(c);
            }
            for(Ability a : t.abilities){
                if(a instanceof mindustry.entities.abilities.ForceFieldAbility ff){
                    if(mergedField == null){
                        mergedField = (mindustry.entities.abilities.ForceFieldAbility)ff.copy();
                    }else{
                        mergedField.radius = Math.max(mergedField.radius, ff.radius);
                        mergedField.regen += ff.regen;
                        mergedField.max += ff.max;
                        mergedField.cooldown = Math.max(mergedField.cooldown, ff.cooldown);
                    }
                    continue;
                }
                abs.add(a.copy());
            }
        }
        if(mergedField != null)
            abs.add(mergedField);

        int n = ws.size;
        float rad = hitSize() * 0.55f;
        for(int i = 0; i < n; i++){
            Weapon w = ws.get(i);
            float ang = i * 360f / n;
            w.x = Angles.trnsx(ang, rad);
            w.y = Angles.trnsy(ang, rad);
        }
        WeaponMount[] arr = new WeaponMount[n];
        for(int i = 0; i < n; i++){
            arr[i] = ws.get(i).mountType.get(ws.get(i));
        }
        mounts(arr);
        abilities(abs.toArray(Ability.class));
        // 记住这次建出来的数组本体：原版 setType/readAbilities 换掉它们时（占位类型的
        // weapons/abilities 是空的），下一次 refreshDerived 按本体比对就能发现并重建
        builtMounts = mounts();
        builtAbilities = abilities();
        mountSig = compositionSig();

        // 索敌半径 = 本体半径 + 武器环半径 + 最远武器射程
        float mr = hitSize() * 1.55f;
        for(int i = 0; i < n; i++){
            Weapon w = ws.get(i);
            if(w.bullet != null) mr = Math.max(mr, w.bullet.range + rad + hitSize());
        }
        megaRange = mr;
    }

    /**
     * 原版 setType 发现 mounts 长度和 type.weapons 不一致就会调 setupWeapons 按
     * type.weapons 重建——巨兽 type.weapons 恒为空，等于把成员武器清空。
     * 重写为按成员构成重建：任何路径（同步快照后的 setType、读档）触发都会自愈。
     */
    @Override
    public void setupWeapons(UnitType def){
        if(members.isEmpty()){
            if(mounts() == null || mounts().length != 0) mounts(new WeaponMount[0]);
            mountSig = -1;
            return;
        }
        // 构成没变、挂载也还在 → 原样保留。
        // 原版每个同步快照都会 readSync → afterSync → setType → 这里；无条件重建会把武器
        // 装填/冷却/瞄准状态清零（客户端预测的 reload 不在快照里），也会把力场的展开动画
        // （ForceFieldAbility.radiusScale）清零 —— 那就是"联机时力墙一直放大缩小"的来源。
        int sig = compositionSig();
        if(mountSig == sig && mounts() == builtMounts) return;
        rebuildMounts();
    }

    /**
     * 原版 setType 会按 {@code type.weapons}/{@code type.abilities} 重建武器挂载与能力数组，
     * 而巨兽的占位类型上 weapons 只有那门幽灵武器、abilities 恒为空 —— 每次同步快照
     * （客户端每秒几十次）都会把按成员构成建好的数组换掉，把力场的展开动画
     * （{@code radiusScale}）和武器的装填/冷却状态清零，表现就是用户报的
     * "联机时力墙一直放大缩小"。
     *
     * <p>有成员时：只让原版更新标量（生命/拖拽/护甲/体型/控制器），数组交给
     * {@link #refreshDerived()} 按成员构成维护；数组万一真被别处换掉，
     * 它也会按 {@link #builtMounts}/{@link #builtAbilities} 本体比对补回来。
     */
    @Override
    public void setType(UnitType type){
        if(members.isEmpty()){
            super.setType(type);
            return;
        }
        this.type = type;
        maxHealth = type.health;
        drag = type.drag;
        armor = type.armor;
        hitSize = type.hitSize;
        if(controller() == null){
            try{
                controller(type.createController(self()));
            }catch(Throwable ignored){
            }
        }
    }

    /**
     * 按成员构成派生（并缓存）巨兽自己的单位类型，原版直接读 {@code unit.type} 静态字段
     * 的功能由此按成员构成生效：
     * <ul>
     *     <li>速度 = 成员速度平均值（总和 ÷ 成员数）；拖拽/加速度/转向跟随代表类型；</li>
     *     <li>采矿：等级取成员最高（门槛语义）、**速度按成员累加**、范围取最远，
     *         沙矿/墙矿任一成员会即会；</li>
     *     <li>建造：速度按成员叠加（组合多个工程单位造得更快）、范围取最远；</li>
     *     <li>物品上限 = Σ 成员；</li>
     *     <li>生命/碰撞/护甲与实例推导一致（setType 重置后也不丢）；</li>
     *     <li>指令面板补齐（late 注册的模板同款处理，见 UnitComboMerge.register）。</li>
     * </ul>
     * 所有派生类型共用基础巨兽的 content id（占位，见 {@link #compTypeCache} 注释）。
     */
    private static MegaUnitType compTypeFor(int sig, UnitType dom, ObjectMap<UnitType, Integer> tally,
                                            float sumMax, float sumArmor, float combined){
        MegaUnitType cached = compTypeCache.get(sig);
        if(cached != null) return cached;

        MegaUnitType ct = new MegaUnitType("combine-mega-c" + sig);
        // 占位 id：快照/存档写它，对端解析出基础巨兽类型后由成员列表重建本类型
        ct.id = UnitComboMerge.megaGround.id;
        ct.localizedName = "组合巨兽";
        // 代表成员的贴图（客户端才有 atlas；服务端没有贴图，跳过）
        TextureRegion body = null, icon = null;
        if(!mindustry.Vars.headless){
            // 身体 = 代表成员的**整只单位图**（unit-<名字>-full：躯干 + 腿/履带 + 武器）；
            // 模组单位没有 -full 时（fullIcon 被兜底成躯干 region）才退到躯干，腿自己画。
            body = MegaUnitType.bodyRegion(dom);
            // 图标 = 原版 UI 图（面板/小地图/指挥面板用 unit-<名字>-ui）
            icon = dom.uiIcon != null && Core.atlas.isFound(dom.uiIcon) ? dom.uiIcon : body;
            // region 必须设：原版不少地方直接读 type.region（clipSize 就是其一），
            // 巨兽类型本来 region 恒为 null，排个建造计划就会 NPE 闪退
            ct.region = body;
            TextureRegion full = MegaUnitType.fullArt(dom);
            ct.fullIcon = full != null ? full : (body != null ? body : icon);
            ct.uiIcon = icon;
            // 【还得有贴图可画】代表成员那张图找不到时退到占位类型（dagger/flare/risso）的图，
            // 否则这一档巨兽在客户端什么都画不出来 = 单位不可见（用户报的"组合不同单位后看不见"）。
            if(ct.region == null && UnitComboMerge.megaGround != null && UnitComboMerge.megaGround.region != null
                && Core.atlas.isFound(UnitComboMerge.megaGround.region)){
                ct.region = ct.fullIcon = ct.uiIcon = UnitComboMerge.megaGround.region;
            }
        }

        ct.health = Math.max(sumMax, 1f);
        ct.hitSize = Math.max(combined, 1f);
        ct.armor = sumArmor;
        ct.drag = dom.drag;
        ct.accel = dom.accel;
        ct.rotateSpeed = dom.rotateSpeed;
        ct.drownTimeMultiplier = dom.drownTimeMultiplier;

        float spd = 0f, mineSpd = 0f, mineRange = 0f, buildSpd = 0f, buildRange = 0f;
        int tier = -1, cap = 0, spdCount = 0;
        boolean mineFloor = false, mineWalls = false;
        for(UnitType t : tally.keys()){
            // 【按成员个数加权】这里 tally 是按"类型"遍历的（每种类型只来一次），
            // 所以求和必须乘上该类型的成员数 —— 原来只加一次、计数却按成员数，
            // 两艘**同型**船合体速度直接砍半（用户报的"两艘船组合后速度超级慢"），
            // 三台同型单位更是只剩 1/3。
            // 语义不变：速度 = 成员速度平均值（总和 ÷ 成员数），慢的成员拖慢整体、快的带不动全队。
            int count = tally.get(t);
            spd += t.speed * count;
            spdCount += count;
            // 【挖矿速率按成员累加】原版 MinerComp 的产量 = type.mineSpeed（每 tick 的挖掘进度），
            // 所以"两只挖矿单位合体"要加在一起（原来取 max → 三只合体和一只一样快，用户报的
            // "挖矿速率没有累加"）。门槛语义不变：mineTier 取最高、范围取最远。
            if(t.mineSpeed > 0f && t.mineTier >= 0){
                mineSpd += t.mineSpeed * count;
                mineRange = Math.max(mineRange, t.mineRange);
            }
            tier = Math.max(tier, t.mineTier);
            mineFloor |= t.mineFloor;
            mineWalls |= t.mineWalls;
            // 原版 buildSpeed 以 -1 表示"不能建造"，只叠加正值；
            // "多个工程单位造得更快"同样要按成员个数加（两台 poly 合体 = 双倍建造速度）
            if(t.buildSpeed > 0f) buildSpd += t.buildSpeed * count;
            buildRange = Math.max(buildRange, t.buildRange);
            // 物品上限 = Σ 成员（不是 Σ 类型）
            cap += Math.max(t.itemCapacity, 0) * count;
        }
        ct.speed = spdCount > 0 ? Math.max(spd / spdCount, 0.3f) : 0.8f;
        ct.mineTier = tier;
        ct.mineSpeed = mineSpd;
        ct.mineRange = Math.max(mineRange, 70f);
        ct.mineFloor = mineFloor;
        ct.mineWalls = mineWalls;
        ct.buildSpeed = buildSpd;
        ct.buildRange = Math.max(buildRange, mindustry.Vars.buildingRange);
        ct.itemCapacity = Math.max(cap, 10);

        // 寻路代价（原版 UnitType.init 的同款指派；巨兽类型 late 注册、init 从不执行，
        // pathCost 停在 null——CommandAI.isNearObstacle / UnitGroup 寻路 / isPathImpassable
        // 每帧直接读 type.pathCost，null 会每帧抛异常打断整个单位更新链 = 游戏极慢）。
        // 按成员构成指派：有陆地成员走地面代价；纯海军走水面；纯飞行无视地形。
        {
            boolean g = false, n = false, fl = false;
            UnitType engRef = null;
            for(UnitType t : tally.keys()){
                if(t.flying){
                    fl = true;
                    // 引擎参考：飞行成员里体型最大的那台（它自己的 engineOffset/engineSize
                    // 最接近"巨兽该长什么样"；flare 那套只是没有飞行成员参考时的兜底）
                    if(engRef == null || t.hitSize > engRef.hitSize) engRef = t;
                }
                else if(UnitComboMerge.isNaval(t)) n = true;
                else g = true;
            }
            // 有飞行成员 → 会升空 → 按 hitSize 生成引擎（见 MegaUnitType.rebuildEngines）
            ct.hoverEngines = fl;
            // 【用户要求】合体时只要有飞行成员，组合巨兽就"是"飞行单位：
            // 类型上 flying=true（不只靠 elevation 动态判），这样原版所有按 type.flying 分派的
            // 逻辑（影高/图层、太空环境、防空索敌、寻路代价、绘制里的飞行层）一致按飞行处理。
            ct.flying = fl;
            if(n){
                // 纯船编组：照原版 init() 对山东（WaterMovec）的那几条来
                //   naval：影响 CommandAI 的通行判定（船默认只认水路）
                //   emitWalkSound / shadowElevation：视觉（船的影子是水面影）
                // 混合编组（船+陆/飞）不给 naval —— 它还要上岸走路，按陆地判定更合理。
                if(!g && !fl){
                    ct.naval = true;
                    ct.emitWalkSound = false;
                    if(ct.shadowElevation < 0f) ct.shadowElevation = 0.11f;
                }
            }
            if(engRef != null){
                // 换算成"相对 hitSize 的比例"：身体贴图也是按 综合hitSize/成员hitSize 缩放的，
                // 引擎跟身体同源，画出来才配套（不会出现身板巨大、尾焰迷你）
                float refHit = Math.max(engRef.hitSize, 1f);
                if(engRef.engineOffset > 0.01f) ct.engineOffsetRatio = engRef.engineOffset / refHit;
                if(engRef.engineSize > 0.01f) ct.engineSizeRatio = engRef.engineSize / refHit;
            }
            // pathCost 读旧 Pathfinder.costTypes（按下标取实例），pathCostId 读
            // ControlPathfinder.costTypes（ground=0/hover=1/legs=2/naval=3）
            if(g){
                ct.pathCost = mindustry.ai.Pathfinder.costTypes.get(mindustry.ai.Pathfinder.costGround);
                ct.pathCostId = mindustry.ai.ControlPathfinder.costIdGround;
            }else if(n){
                ct.pathCost = mindustry.ai.Pathfinder.costTypes.get(mindustry.ai.Pathfinder.costNaval);
                ct.pathCostId = mindustry.ai.ControlPathfinder.costIdNaval;
            }else{
                ct.pathCost = mindustry.ai.Pathfinder.costTypes.get(mindustry.ai.Pathfinder.costNone);
                ct.pathCostId = mindustry.ai.ControlPathfinder.costIdGround;
            }
        }

        // 指令面板（同 UnitComboMerge.register 对模板的手工补齐）
        ct.commands.add(mindustry.ai.UnitCommand.moveCommand, UnitComboMerge.comboCommand);
        ct.defaultCommand = mindustry.ai.UnitCommand.moveCommand;
        ct.stances.addAll(mindustry.ai.UnitStance.stop, mindustry.ai.UnitStance.holdFire,
            mindustry.ai.UnitStance.pursueTarget, mindustry.ai.UnitStance.patrol, mindustry.ai.UnitStance.ram);
        // 【成员指令/姿态要并进来】原版这些是 UnitType.init() 按能力填的：
        //   canBoost 且 buildSpeed>0 → 自动重建(rebuildCommand) + 辅助建造(assistCommand)；
        //   mineTier>0 → 挖矿(mineCommand)；flying 且 canHeal → 治疗建筑(repairCommand)；
        //   还有载具类的装载/卸载指令。巨兽类型是 late 注册、init() 从没跑过，
        //   这里只写死 [移动, 组合] 的话，poly 这类工程/采矿单位合体后上面那些指令就全没了
        //   （用户报的"poly 和其它单位合体后 自动重建/辅助建造/治疗建筑/挖矿 命令消失"）。
        // 直接取每个成员的 commands/stances 求并集：成员类型是正常加载的内容，init() 跑过，
        // 它们的列表就是最权威的答案（还能顺带带上别的模组给单位加的指令）。
        for(UnitType t : tally.keys()){
            for(var cmd : t.commands)
                if(!ct.commands.contains(cmd)) ct.commands.add(cmd);
            for(var st : t.stances)
                if(!ct.stances.contains(st)) ct.stances.add(st);
            // 抗性也继承（船在水里会被水地形持续套"湿" = -6% 速度，
            // 原版是在 init() 里给船加 StatusEffects.wet 免疫的，巨兽 init() 没跑过 → 白吃这个减速）
            for(var immune : t.immunities)
                ct.immunities.add(immune);
            if(t.canBoost) ct.canBoost = true;
            if(t.canHeal) ct.canHeal = true;
        }
        ct.range = 260f;
        ct.maxRange = 260f;

        // hitSize/是否低空是这里才定的，late-init 的绘制字段（flyingLayer/clipSize/lightRadius）要按新值重算
        ct.lowAltitude = true;
        ct.applyLateDefaults();

        // 【占位类型同步】原版命令面板是按 unit.type.id 聚合、再用 content.unit(id) 取类型的
        // （PlacementFragment：图标用 StatValues.stack(type, n) 读 type.uiIcon，指令按钮遍历
        // type.commands）。派生巨兽类型全都共用基础巨兽的占位 id，所以面板实际拿到的是
        // 基础类型本身 —— 它必须跟着这次推导同步，否则：
        //   · 图标一直是注册时写死的 dagger（用户报的"框选巨兽显示 dagger"）；
        //   · 指令只剩 [移动, 组合]，成员的自动重建/辅助建造/治疗建筑/挖矿在面板里全不见了。
        // 纯元数据（名字/指令/姿态/canBoost），服务端也一起同步，只有图标在客户端才有的贴图上做。
        MegaUnitType placeholder = UnitComboMerge.megaGround;
        if(placeholder != null){
            placeholder.localizedName = ct.localizedName;
            placeholder.commands.clear();
            placeholder.commands.addAll(ct.commands);
            placeholder.stances.clear();
            placeholder.stances.addAll(ct.stances);
            placeholder.defaultCommand = ct.defaultCommand;
            placeholder.canBoost = ct.canBoost;
            placeholder.canHeal = ct.canHeal;
            // 【注意不要同步 flying/naval 到占位类型】占位类型是所有派生类型共用、且是
            // 新建巨兽时的"出生类型"：把上一次推导的 flying 留在上面，会让下一次（比如两艘船）
            // 新建出来的巨兽出生就带着 elevation=1（原版按 type.flying 给出生高度），
            // 头几十 tick 被当成飞行单位算地形系数。飞行/海军语义由派生类型自己带着。
            if(icon != null){
                // 占位类型的 fullIcon 保持"整只单位图"的语义，uiIcon 是面板/小地图用的 UI 图
                placeholder.fullIcon = body != null ? body : icon;
                placeholder.uiIcon = icon;
            }
        }

        compTypeCache.put(sig, ct);
        return ct;
    }

    /**
     * 原版 afterSync/afterRead 会调 setType(this.type)：巨兽类型 late 注册、
     * type.weapons/type.abilities 恒为空、health/hitSize 是兜底值——setType 会把
     * 实例武器挂载清空、hitSize/maxHealth 重置。这里在原版逻辑之后立即按成员构成
     * 重推导（签名一致但快照比对失败时会自动重建），保证客户端每个同步快照后、
     * 读档后状态都与服务端一致。
     */
    @Override
    public void afterSync(){
        super.afterSync();
        refreshDerived();
    }

    @Override
    public void afterRead(){
        super.afterRead();
        refreshDerived();
    }


    /** AI 索敌半径：原版 AI 走 unit.range()，巨兽类型 late 注册、type.range 是默认值。 */
    @Override
    public float range(){
        return megaRange;
    }

    /**
     * 视口裁剪包围盒。
     *
     * **必须重写**：原版 {@code UnitComp.clipSize()} 在"有建造计划"（{@code isBuilding()}）
     * 时会读 {@code type.region.width}，而巨兽类型没有自己的贴图（{@code region} 为 null），
     * 于是玩家一给巨兽排建造计划，渲染循环里的 {@code EntityGroup.draw} 就抛
     * `NullPointerException: Attempt to read from field 'int TextureRegion.width' on a null object reference`
     * 直接闪退（安卓崩溃栈：UnitEntity.clipSize → EntityGroup.draw → Renderer.draw）。
     * 这里改成按巨兽自己的 hitSize/建造范围算，不碰 {@code region}。
     */
    @Override
    public float clipSize(){
        float size = type.clipSize > 0f ? type.clipSize : Math.max(hitSize() * 2.4f, 120f);
        if(isBuilding())
            return mindustry.Vars.state.rules.infiniteResources ? Float.MAX_VALUE
                : size + type.buildRange + mindustry.Vars.tilesize * 4f;
        if(mining())
            return size + type.mineRange;
        return size;
    }

    /**
     * 左上角玩家预览图标（原版 HUD 每帧调 player.icon() → unit.icon() → type.uiIcon，
     * 是类型级静态值——注册时巨兽类型被写死成 dagger/flare/risso 的图标）。
     * 实例级重写：跟代表类型走，合体构成变化即预览变化。
     */
    @Override
    public arc.graphics.g2d.TextureRegion icon(){
        return dominant != null && dominant.uiIcon != null ? dominant.uiIcon : super.icon();
    }

    /**
     * 是否有武器：原版实现只看 type.weapons，巨兽类型自己的 weapons 是空的
     * （武器挂在实例 mounts 上，由成员构成推导）。不重写的话 AIController.
     * updateTargeting 里 if(unit.hasWeapons()) 恒为 false，updateWeapons 从不执行，
     * mount.shoot 永远不会被置真——巨兽就成了绝不还手的沙包。
     */
    @Override
    public boolean hasWeapons(){
        WeaponMount[] ms = mounts();
        return ms != null && ms.length > 0;
    }

    /**
     * 网络快照：原版字段之后追加成员列表（逐条全量内联，与原版货舱同一格式）。
     * 注意 super.readSync 结尾会调 afterSync → setType → setupWeapons，
     * 那一刻 members 还是上一份快照的内容（构成一致，无碍）；返回后此处读入
     * 新成员并 refreshDerived，构成若变化会立刻重建。
     */
    @Override
    public void writeSync(Writes write){
        super.writeSync(write);
        writeMembers(write);
    }

    @Override
    public void readSync(Reads read){
        try{
            super.readSync(read);
            readMembers(read);
        }catch(Throwable t){
            // 【不能把异常抛回 NetClient.entitySnapshot】一次快照包里排在它后面的实体全读不出来
            //（玩家看到的就是"一片单位不可见"），日志还会被 "Unknown payload type / Queue too long"
            // 刷屏。读出问题（对端实体类映射不一致、字节错位）时退化成"空成员的巨兽"：
            // 单位还在、还能操控，损失只是这一次快照里的成员构成。
            Log.err("[combine] 组合巨兽快照读取失败，按空成员处理", t);
            try{ members.clear(); }catch(Throwable ignored){}
            if(type == null) type = UnitComboMerge.megaGround;
            return;
        }
        refreshDerived();
    }

    /** 存档：同样在原版字段之后追加成员列表。 */
    @Override
    public void write(Writes write){
        super.write(write);
        writeMembers(write);
    }

    @Override
    public void read(Reads read){
        try{
            super.read(read);
            readMembers(read);
        }catch(Throwable t){
            // 【存档绝不能炸】SaveVersion.readWorldEntities 是逐个实体 chunk 读的，异常抛出去
            // 就是整个存档读不进去（用户报的"存档内有组合单位直接炸档"）。这里吞掉：
            // 残躯当场判死（下一个 tick 就被清掉），其余实体照常读。
            Log.err("[combine] 组合巨兽存档数据读取失败，按空成员处理", t);
            try{ members.clear(); }catch(Throwable ignored){}
            if(type == null) type = UnitComboMerge.megaGround;
            try{ health(0f); }catch(Throwable ignored){}
            return;
        }
        refreshDerived();
    }

    /** 成员块标记（'M'）。老版本写的是"数量 + 载荷"，没有这个标记 —— 读到别的值就当没有成员。 */
    private static final byte MEMBER_TAG = 0x4D;
    /** 成员块上限（防坏数据要一大块内存）。 */
    private static final int MAX_MEMBER_BODY = 4 << 20;

    /**
     * 成员块：标记 + 长度 + 成员体。
     *
     * 【为什么要长度前缀】快照是一条读取流里**连着好几个实体**：成员体里读错一位，
     * 后面所有实体这一帧全废（用户报的"一片单位不可见 + 刷屏报错"）。先把成员序列化到内存、
     * 把长度写在前面，外面就永远只按长度前进 —— 里面读崩了最多丢这一只巨兽的成员。
     *
     * 【为什么成员按"名字"写】载荷原本只写单位实体类的 **classId**，而 id 是
     * {@code EntityMapping.register} 按"第一个空槽"分配的：装了别的自定义实体模组时，
     * 两端的 id 顺序可能不同，成员就会按错误的类去读（存档炸档、快照错位）。
     * 这里写"实体类全名"（两端各自按名字查自己的表），换模组/换顺序都能对上。
     */
    private void writeMembers(Writes write){
        byte[] body;
        try{
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            Writes w = new Writes(new java.io.DataOutputStream(bos));
            w.i(members.size);
            for(int i = 0; i < members.size; i++){
                UnitPayload up = members.get(i);
                Unit mu = up == null ? null : up.unit;
                if(mu == null){
                    w.bool(false);
                    continue;
                }
                w.bool(true);
                w.str(entityKey(mu));
                mu.write(w);
            }
            body = bos.toByteArray();
        }catch(Throwable t){
            Log.err("[combine] 组合巨兽成员序列化失败（本次按无成员写出）", t);
            body = new byte[4]; // count = 0
        }
        write.b(MEMBER_TAG);
        write.i(body.length);
        write.b(body);
    }

    private void readMembers(Reads read){
        members.clear();
        int tag = read.ub();
        if(tag != (MEMBER_TAG & 0xFF)){
            // 【旧格式兼容】本次改动前写的是 `i(数量) + 数量 × 原版载荷`（载荷 = bool + 类型 +
            // classId + 单位存档字节）。标记这一位读到的其实是那个 count 的最低字节，
            // 把剩下的三个字节补上就能按老格式读回来 —— 用户手里已经存了巨兽的存档不能一升级就废掉。
            // 大端：这一位是 count 的最高字节
            int n = (tag << 24) | (read.ub() << 16) | (read.ub() << 8) | read.ub();
            if(n < 0 || n > 4096)
                throw new IllegalArgumentException("旧格式成员数量异常: " + n);
            for(int i = 0; i < n; i++){
                if(!read.bool())
                    continue;
                int kind = read.ub();
                if(kind != 0) // 0 = 单位载荷（成员只可能是单位）
                    throw new IllegalArgumentException("旧格式成员载荷类型异常: " + kind);
                int classId = read.ub();
                arc.func.Prov<?> prov = mindustry.gen.EntityMapping.map(classId);
                if(prov == null)
                    throw new IllegalArgumentException("旧格式成员实体类找不到: " + classId);
                Object o = prov.get();
                if(!(o instanceof Unit u))
                    throw new IllegalArgumentException("旧格式成员不是单位: " + classId);
                u.read(read);
                members.add(new UnitPayload(u));
            }
            return;
        }
        int len = read.i();
        if(len < 0 || len > MAX_MEMBER_BODY)
            throw new IllegalArgumentException("成员块长度异常: " + len);
        byte[] body = read.b(len);
        Reads r = new Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(body)));
        int n = r.i();
        if(n < 0 || n > 4096)
            throw new IllegalArgumentException("成员数量异常: " + n);
        for(int i = 0; i < n; i++){
            if(!r.bool())
                continue;
            String key = r.str(256);
            Unit mu = unitByKey(key);
            if(mu == null){
                // 名字认不出来（缺模组/版本不同）：剩下的成员只能一起丢，但流是安全的
                Log.warn("[combine] 组合巨兽成员类型 @ 认不出来，剩余成员丢弃", key);
                break;
            }
            mu.read(r);
            members.add(new UnitPayload(mu));
        }
    }

    /** 成员实体的跨端名字（用类全名，见 writeMembers 说明）。 */
    public static String entityKey(Unit u){
        return "combine-e-" + u.getClass().getName();
    }

    /** 按 {@link #entityKey} 的名字在自己的实体表里找同类：找到就返回一个新实例（未入世界）。 */
    public static Unit unitByKey(String key){
        if(key == null || !key.startsWith("combine-e-"))
            return null;
        arc.func.Prov<?> cached = mindustry.gen.EntityMapping.nameMap.get(key);
        if(cached != null){
            Object o = cached.get();
            if(o instanceof Unit u) return u;
        }
        String cls = key.substring("combine-e-".length());
        for(int i = 0; i < mindustry.gen.EntityMapping.idMap.length; i++){
            arc.func.Prov<?> cand = mindustry.gen.EntityMapping.idMap[i];
            if(cand == null)
                continue;
            Object sample;
            try{
                sample = cand.get();
            }catch(Throwable t){
                continue;
            }
            if(sample instanceof Unit u && u.getClass().getName().equals(cls)){
                // 缓存名字 -> 构造器，下次直接命中
                try{
                    mindustry.gen.EntityMapping.nameMap.put(key, cand);
                }catch(Throwable ignored){
                }
                return u;
            }
        }
        return null;
    }

    /** 是否有飞行成员（能飞）。 */
    public boolean hasFlyer(){
        return hasFlyer;
    }

    /** 是否有海军成员（能游）。 */
    public boolean hasNaval(){
        return hasNaval;
    }

    /** 是否有陆地成员（能跑）。 */
    public boolean hasGround(){
        return hasGround;
    }

    // ==================== 身体部件（腿/机甲腿/履带/爬虫身） ====================
    // 原版这些部件由实体组件（LegsComp/MechComp/TankComp/CrawlComp）维护、由 UnitType 分开绘制。
    // 巨兽是最普通的 UnitEntity，没有这些组件——这里自己实现：
    //   · 状态（腿骨骼/行走相位/履带相位）放本实体，每帧 updateAttachments() 里更新；
    //   · 绘制交给 MegaUnitType.drawAttachments（原版那几套画法，等比放大）。
    // 腿/履带/爬虫三类直接实现 Legsc/Tankc/Crawlc 接口，好让原版 drawLegs/drawTank/drawCrawl
    // 原样可用；机甲（Mechc）**故意不实现**：原版有 "player.unit() instanceof Mechc && isFlying
    // → 禁止开火" 这类特判（NetServer.handleUnitPacket），巨兽挂上 Mechc 会让会飞的巨兽没法开火，
    // 所以机甲腿用 MegaUnitType.drawMechOf 自己画一份。

    /** 贴图/部件共用的缩放系数（和 MegaUnitType.draw 里那套保持一致）。 */
    public float bodyScale(){
        return Mathf.clamp(drawScale, 0.5f, 8f);
    }

    /** 代表类型属于哪一类身体部件（按原版绘制顺序判定：机甲 → 履带 → 腿 → 爬虫）。 */
    static int attachmentKind(UnitType t){
        if(t == null || t.constructor == null) return ATT_NONE;
        Integer cached = attKindCache.get(t);
        if(cached != null) return cached;
        int k = ATT_NONE;
        try{
            Object sample = t.constructor.get();
            if(sample instanceof mindustry.gen.Mechc) k = ATT_MECH;
            else if(sample instanceof Tankc) k = ATT_TANK;
            else if(sample instanceof Legsc) k = ATT_LEGS;
            else if(sample instanceof Crawlc) k = ATT_CRAWL;
        }catch(Throwable ignored){
        }
        attKindCache.put(t, k);
        return k;
    }

    private static final ObjectMap<UnitType, Integer> attKindCache = new ObjectMap<>();

    @Override
    public Leg[] legs(){
        return legs;
    }

    @Override
    public void legs(Leg[] l){
        legs = l;
    }

    @Override
    public float baseRotation(){
        return legBaseRotation;
    }

    @Override
    public void baseRotation(float v){
        legBaseRotation = v;
    }

    @Override
    public float totalLength(){
        return legTotalLength;
    }

    @Override
    public void totalLength(float v){
        legTotalLength = v;
    }

    @Override
    public float moveSpace(){
        return legMoveSpace;
    }

    @Override
    public void moveSpace(float v){
        legMoveSpace = v;
    }

    @Override
    public Vec2 curMoveOffset(){
        return legCurMoveOffset;
    }

    @Override
    public void curMoveOffset(Vec2 v){
        legCurMoveOffset = v;
    }

    @Override
    public Floor lastDeepFloor(){
        return lastDeepFloor;
    }

    @Override
    public void lastDeepFloor(Floor f){
        lastDeepFloor = f;
    }

    @Override
    public float crawlTime(){
        return crawlTime;
    }

    @Override
    public void crawlTime(float v){
        crawlTime = v;
    }

    @Override
    public float segmentRot(){
        return crawlSegmentRot;
    }

    @Override
    public void segmentRot(float v){
        crawlSegmentRot = v;
    }

    @Override
    public float lastCrawlSlowdown(){
        return 1f;
    }

    @Override
    public void lastCrawlSlowdown(float v){
    }

    @Override
    public float treadTime(){
        return treadTime;
    }

    @Override
    public void treadTime(float v){
        treadTime = v;
    }

    @Override
    public boolean walked(){
        return walkedState;
    }

    @Override
    public void walked(boolean v){
        walkedState = v;
    }

    /** 腿的外展角度（原版 LegsComp.legAngle，长度按巨兽体型放大）。 */
    @Override
    public float legAngle(int index){
        UnitType d = dominant;
        if(d != null && d.legStraightness > 0f){
            return Mathf.slerp(defaultLegAngle(index),
                (index >= legs.length / 2 ? -90f : 90f) + legBaseRotation, d.legStraightness);
        }
        return defaultLegAngle(index);
    }

    @Override
    public float defaultLegAngle(int index){
        if(legs.length == 0) return legBaseRotation;
        return legBaseRotation + 360f / legs.length * index + (360f / legs.length / 2f);
    }

    /** 腿根相对身体中心的偏移（原版 LegsComp.legOffset，长度按巨兽体型放大）。 */
    @Override
    public Vec2 legOffset(Vec2 out, int index){
        UnitType d = dominant;
        if(d == null) return out.setZero();
        float scl = bodyScale();
        out.trns(defaultLegAngle(index), d.legBaseOffset * scl);
        if(d.legStraightness > 0f){
            StraightVec.trns(defaultLegAngle(index) - legBaseRotation, d.legBaseOffset * scl);
            StraightVec.y = Mathf.sign(StraightVec.y) * d.legBaseOffset * scl * d.legStraightLength;
            StraightVec.rotate(legBaseRotation);
            out.lerp(StraightVec, d.baseLegStraightness);
        }
        return out;
    }

    @Override
    public void resetLegs(){
        UnitType d = dominant;
        resetLegs(d == null ? 0f : d.legLength * bodyScale());
    }

    @Override
    public void resetLegs(float legLength){
        UnitType d = dominant;
        if(d == null || d.legCount <= 0 || legLength <= 0f){
            legs = new Leg[0];
            return;
        }
        Leg[] arr = new Leg[d.legCount];
        legs = arr;
        if(d.lockLegBase) legBaseRotation = rotation;
        for(int i = 0; i < arr.length; i++){
            Leg l = new Leg();
            float dstRot = legAngle(i);
            Vec2 baseOffset = legOffset(Tmp.v5, i).add(x, y);
            l.joint.trns(dstRot, legLength / 2f).add(baseOffset);
            l.base.trns(dstRot, legLength).add(baseOffset);
            arr[i] = l;
        }
        legTotalLength = Mathf.random(100f);
        legResetScale = bodyScale();
    }

    /** 每帧更新身体部件的动画状态（腿的 IK / 机甲行走相位 / 履带滚动 / 爬虫摆动）。 */
    private void updateAttachments(){
        UnitType d = dominant;
        if(d == null || dead) return;
        float dx = deltaX(), dy = deltaY();
        float len = Mathf.dst(dx, dy);

        if(attKind == ATT_LEGS){
            updateLegs(d, dx, dy);
        }else if(attKind == ATT_MECH){
            if(len > 0.001f){
                legBaseRotation = Angles.moveToward(legBaseRotation, Mathf.angle(dx, dy),
                    Math.max(d.baseRotateSpeed, 0.01f) * Mathf.clamp(len / Math.max(d.speed, 0.01f) / Math.max(arc.util.Time.delta, 0.001f), 0f, 1f) * arc.util.Time.delta);
                mechWalkTime += len;
            }
        }else if(attKind == ATT_CRAWL){
            if(moving()){
                crawlSegmentRot = Angles.moveToward(crawlSegmentRot, rotation, d.segmentRotSpeed * arc.util.Time.delta);
            }
            crawlSegmentRot = Angles.clampRange(crawlSegmentRot, rotation, d.segmentMaxRot);
            crawlTime += len;
        }else if(attKind == ATT_TANK){
            treadTime += len;
            walkedState = len > 0.001f;
        }
    }

    /** 原版 LegsComp.update 的等比放大版（腿长/腿根偏移都乘上巨兽的体型缩放）。 */
    private void updateLegs(UnitType d, float dx, float dy){
        float scl = bodyScale();
        float legLength = d.legLength * scl;
        if(d.legCount <= 0 || legs.length != d.legCount || Math.abs(scl - legResetScale) > 0.001f){
            resetLegs(legLength);
        }
        if(legs.length == 0) return;

        float movingLen = Mathf.dst(dx, dy);
        if(movingLen > 0.001f){
            legBaseRotation = Angles.moveToward(legBaseRotation, Mathf.angle(dx, dy), d.rotateSpeed);
        }
        if(d.lockLegBase) legBaseRotation = rotation;

        float moveSpeed = d.legSpeed;
        int div = Math.max(legs.length / Math.max(d.legGroupSize, 1), 2);
        float moveSpace = legLength / 1.6f / (div / 2f) * d.legMoveSpace;
        if(!(moveSpace > 0.0001f)) moveSpace = Math.max(legLength, 1f);
        legMoveSpace = moveSpace;

        float trns = moveSpace * 0.85f * d.legForwardScl;
        boolean moving = moving();
        Vec2 moveOffset = !moving ? Tmp.v4.setZero() : Tmp.v4.trns(Mathf.angle(dx, dy), trns);
        moveOffset = legCurMoveOffset.lerpDelta(moveOffset, 0.1f);
        legTotalLength += d.legContinuousMove ? d.speed * speedMultiplier * arc.util.Time.delta : movingLen;

        lastDeepFloor = null;
        int deeps = 0;

        for(int i = 0; i < legs.length; i++){
            float dstRot = legAngle(i);
            Vec2 baseOffset = legOffset(Tmp.v5, i).add(x, y);
            Leg l = legs[i];

            l.joint.sub(baseOffset).clampLength(d.legMinLength * legLength / 2f, d.legMaxLength * legLength / 2f).add(baseOffset);
            l.base.sub(baseOffset).clampLength(d.legMinLength * legLength, d.legMaxLength * legLength).add(baseOffset);

            float stageF = (legTotalLength + i * d.legPairOffset) / moveSpace;
            int stage = (int)stageF;
            int group = stage % div;
            boolean move = i % div == group;
            boolean side = i < legs.length / 2;
            boolean backLeg = Math.abs((i + 0.5f) - legs.length / 2f) <= 0.501f;
            if(backLeg && d.flipBackLegs) side = !side;
            if(d.flipLegSide) side = !side;

            l.moving = move;
            l.stage = moving ? stageF % 1f : Mathf.lerpDelta(l.stage, 0f, 0.1f);

            Tile tile = Vars.world.tileWorld(l.base.x, l.base.y);
            Floor floor = tile == null ? Blocks.air.asFloor() : tile.floor();

            if(tile != null && tile.isDeep()){
                deeps++;
                lastDeepFloor = floor;
            }

            if(l.group != group){
                //落地那一瞬间的扬尘/踏地声（原版同款，只是位置跟着巨兽的腿走）
                if(!move && (moving || !d.legContinuousMove) && i % div == l.group
                    && !Vars.headless && !inFogTo(Vars.player.team())){
                    var color = tile == null ? arc.graphics.Color.clear : tile.getFloorColor();
                    if(floor.isLiquid && tile != null && tile.block() == Blocks.air){
                        floor.walkEffect.at(l.base.x, l.base.y, d.rippleScale, color);
                        floor.walkSound.at(x, y, 1f, floor.walkSoundVolume);
                    }else{
                        Fx.unitLandSmall.at(l.base.x, l.base.y, d.rippleScale, color);
                        d.stepSound.at(l.base.x, l.base.y,
                            d.stepSoundPitch + Mathf.range(d.stepSoundPitchRange), d.stepSoundVolume);
                    }
                    if(d.stepShake > 0f){
                        mindustry.entities.Effect.shake(d.stepShake, d.stepShake, l.base);
                    }
                }
                //巨型蜘蛛踩地的范围伤害（原版 legSplashDamage）
                if(d.legSplashDamage > 0f && !disarmed){
                    mindustry.entities.Damage.damage(team, l.base.x, l.base.y, d.legSplashRange,
                        d.legSplashDamage * Vars.state.rules.unitDamage(team), false, true);
                }

                l.group = group;
            }

            //落点 → IK → 插值（原版算法）
            Vec2 legDest = Tmp.v1.trns(dstRot, legLength * d.legLengthScl).add(baseOffset).add(moveOffset);
            Vec2 jointDest = Tmp.v2;
            InverseKinematics.solve(legLength / 2f, legLength / 2f, Tmp.v6.set(l.base).sub(baseOffset), side, jointDest);
            jointDest.add(baseOffset);

            if(move){
                float moveFract = stageF % 1f;
                l.base.lerpDelta(legDest, moveFract);
                l.joint.lerpDelta(jointDest, moveFract / 2f);
            }
            l.joint.lerpDelta(jointDest, moveSpeed / 4f);

            l.joint.sub(baseOffset).clampLength(d.legMinLength * legLength / 2f, d.legMaxLength * legLength / 2f).add(baseOffset);
            l.base.sub(baseOffset).clampLength(d.legMinLength * legLength, d.legMaxLength * legLength).add(baseOffset);
        }

        if(deeps != legs.length || !floorOn().isDeep()){
            lastDeepFloor = null;
        }
    }

    /**
     * 选择移动模式（有飞机就能飞、有海军就能游、有陆地就能跑）：
     * <ul>
     *     <li>有飞行成员 → 飞行（任何地形——飞机成员本就无处不在地飞，组合体同理，
     *         飞行严格优于走/游，不会因为在陆地上就把飞机成员的飞行能力埋掉）；</li>
     *     <li>无飞机、深水体上：有海军成员 → 游泳；否则（纯陆地组）→ 走路，
     *         保留原版溺水判定（代价与散装编组一致）；</li>
     *     <li>无飞机、陆地/浅水上：有陆地成员 → 走路；否则（纯海军组）→ 搁浅
     *         （原版海军不能上岸）。</li>
     * </ul>
     */
    public int moveMode(){
        if(hasFlyer) return MODE_FLY;
        mindustry.world.blocks.environment.Floor on = floorOn();
        boolean deep = on != null && on.isLiquid && on.drownTime > 0f;
        if(deep){
            return hasNaval ? MODE_SWIM : MODE_WALK;
        }
        return hasGround ? MODE_WALK : MODE_STUCK;
    }

    /**
     * 地形速度系数（"水阻"就在这里）。
     *
     * 原版是按**实体组件**分派的，不是按类型：
     * <ul>
     *     <li>船（{@code WaterMoveComp} / {@code WaterCrawlComp}）把
     *         {@code floorSpeedMultiplier()} 整个 {@code @Replace} 掉了：
     *         {@code (floor.shallow ? 1f : 1.3f)} —— 水里不但没有水阻，深水还快 30%；</li>
     *     <li>普通单位（{@code UnitComp}）才是
     *         {@code pow(floor.speedMultiplier, type.floorMultiplier)}：深水 0.2、浅水 0.5；</li>
     *     <li>爬爬虫（{@code CrawlComp}）：深水固定 0.45。</li>
     * </ul>
     * 巨兽实体继承的是最普通的 {@code UnitEntity}、派生类型又是 late 注册
     * （{@code floorMultiplier} 停在默认 1），所以两艘船合体后按"普通单位"算 ——
     * 一进深水直接吃 0.2 倍水阻（用户报的"两艘船组合后超级慢 / 没处理水的阻力"）。
     * 这里按移动模式分派：游/搁浅（有船成员的形态）用船那套，爬虫成员在深水按爬虫那套，
     * 其余交给原版 UnitComp 的算法。
     */
    @Override
    public float floorSpeedMultiplier(){
        if(!isFlying()){
            int mode = moveMode();
            if(mode == MODE_SWIM || mode == MODE_STUCK){
                // 船：水里没有水阻（深水 1.3、浅水 1.0），和原版 WaterMoveComp 一致
                mindustry.world.blocks.environment.Floor on = floorOn();
                return (on != null && on.shallow ? 1f : 1.3f) * speedMultiplier;
            }
            if(hasCrawler){
                // 爬虫：原版 CrawlComp 对深水固定 0.45（不受 floorMultiplier 影响）
                mindustry.world.blocks.environment.Floor on = floorOn();
                if(on != null && on.isDeep())
                    return (float)Math.pow(0.45f, type.floorMultiplier) * speedMultiplier;
            }
        }
        return super.floorSpeedMultiplier();
    }

    /**
     * 驱动高度：该飞则升、该落则落。原版飞行单位靠 type.flying 在创建时把 elevation
     * 置 1 且没有力把它降下来；巨兽类型 flying 恒为 false（移动能力随成员构成动态变化，
     * 不能定死在类型上），所以在这里按 {@link #moveMode()} 手动逼近目标高度——
     * 原版所有按 elevation 判定飞行与否的逻辑（碰撞、瞄准、绘制层级、阴影）原样生效。
     */
    @Override
    public void update(){
        super.update();
        // 身体部件（腿/机甲腿/履带/爬虫身）的动画：原版是实体组件在 super.update() 里跑的，
        // 巨兽没有这些组件，在这里按代表类型的部件种类自己驱动（绘制见 MegaUnitType）。
        updateAttachments();
        if(dead) return; // 死亡坠落由原版处理（fallSpeed）
        int mode = moveMode();
        float target = mode == MODE_FLY ? 1f : 0f;
        // 【会飞的巨兽必须一直悬空】原版对 canBoost 单位每帧都会按"助跑/落地"重设 elevation
        // （updateBoosting 里 shouldBoost = boost || onSolid() || (isFlying() && !canLand())，
        //  而 canLand() 在深水上会返回 true），于是刚升空的巨兽会被一点点拉回地面，
        // isFlying() 变 false —— 用户报的"合体时有飞行单位却不是飞行单位"就是这么来的。
        // 有飞行成员就直接钉在 1，不给原版那套抢高度的机会。
        if(hasFlyer){
            elevation = 1f;
            return;
        }
        if(elevation != target){
            elevation = arc.math.Mathf.approachDelta(elevation, target, 0.05f);
        }
    }

    /**
     * 碰撞判定（按当前移动模式，语义对齐原版各移动组件）：
     * 飞行中 → 无碰撞；游泳（纯海军组）→ 水面，陆地对它实心（原版海军不能上岸）；
     * 游泳（混合组有陆地成员）→ 全实体碰撞，可以直接走上岸；走路 → 全实体碰撞。
     */
    @Override
    public EntityCollisions.SolidPred solidity(){
        if(isFlying() || ignoreSolids()) return null;
        int mode = moveMode();
        if(mode == MODE_SWIM && !hasGround) return EntityCollisions::waterSolid;
        if(mode == MODE_STUCK) return EntityCollisions::waterSolid;
        return EntityCollisions::solid;
    }

    /** 游泳/搁浅（原版海军语义）：脚下必须是液体。 */
    @Override
    public boolean onSolid(){
        int mode = moveMode();
        return (mode == MODE_SWIM && !hasGround) || mode == MODE_STUCK
            ? EntityCollisions.waterSolid(tileX(), tileY())
            : super.onSolid();
    }

    /**
     * 有海军成员的巨兽在深水体上处于游泳模式，不应溺水（原版海军单位 canDrown 为 false
     * 的语义）；纯陆地组保留原版溺水判定——散装编组坦克下水也淹，合体不应当更差也不更特权。
     */
    @Override
    public boolean canDrown(){
        if(hasNaval && !isFlying()) return false;
        return super.canDrown();
    }
}
