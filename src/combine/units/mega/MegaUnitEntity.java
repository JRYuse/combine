package combine.units.mega;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import arc.math.Angles;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.io.Reads;
import arc.util.io.Writes;
import combine.units.UnitComboMerge;
import mindustry.entities.EntityCollisions;
import mindustry.entities.abilities.Ability;
import mindustry.entities.units.WeaponMount;
import mindustry.gen.Unit;
import mindustry.gen.UnitEntity;
import mindustry.io.TypeIO;
import mindustry.type.UnitType;
import mindustry.type.Weapon;
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
 *     <li>贴图代表类型 = 数量最多的成员类型（并列取 type.health 最少），见 {@link UnitComboMerge}。</li>
 * </ul>
 *
 * <p>推导只在成员构成变化时发生（用构成签名缓存），网络同步快照和存档读取后各推导一次。
 * 移动能力（飞/游/跑）同样从成员构成推导，按当前地形动态切换（{@link #moveMode()}）：
 * 有飞行成员就能飞、有海军成员就能游、有陆地成员就能跑，见 {@link #update()} 的高度驱动
 * 与 solidity/canDrown/onSolid 的碰撞语义。
 */
public class MegaUnitEntity extends UnitEntity{
    /** 网络/存档用的类 ID，由 UnitComboMerge.register() 登记 EntityMapping 后回填。 */
    public static int CLASS_ID = 255;

    /** 贴图代表类型（数量最多；并列取血量最少），由成员构成推导。 */
    public transient UnitType dominant;
    /** 贴图缩放 = 综合 hitSize / 代表类型 hitSize，由成员构成推导。 */
    public transient float drawScale = 1f;
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

    /** @param force 跳过签名检查强制重建（融合瞬间用）。 */
    public void refreshDerived(boolean force){
        int sig = members.size;
        for(int i = 0; i < members.size; i++){
            UnitPayload up = members.get(i);
            if(up != null && up.unit != null && up.unit.type != null){
                sig = sig * 31 + up.unit.type.id + 1;
            }else{
                sig = sig * 31 + 1;
            }
        }
        // 跳过条件不只看出构成签名：原版 afterSync/afterRead 会调 setType(this.type)，
        // 把 hitSize/maxHealth 重置成类型兜底值、并按 type.weapons(恒为空) 重建 mounts——
        // 构成签名没变但实例数据已被清空，必须用快照比对识别出来并重建。
        WeaponMount[] curMounts = mounts();
        Ability[] curAbilities = abilities();
        if(!force && sig == lastSig
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

            // 贴图代表类型：数量最多；并列取 type.health 最少
            int c = tally.get(t, 0) + 1;
            tally.put(t, c);
            if(dom == null || c > domCount || (c == domCount && t.health < dom.health)){
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

        // 按构成签名换绑派生类型：采矿/建造/速度/物品上限等"原版只读 type 静态字段"
        // 的能力从此按成员构成生效；同步/存档只写占位 id，对端按成员列表重建同一类型
        type = compTypeFor(sig, dom, tally, sumMax, sumArmor, combined);

        rebuildMounts();

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
            mounts(new WeaponMount[0]);
            return;
        }
        rebuildMounts();
    }

    /**
     * 按成员构成派生（并缓存）巨兽自己的单位类型，原版直接读 {@code unit.type} 静态字段
     * 的功能由此按成员构成生效：
     * <ul>
     *     <li>速度 = 成员速度平均值（总和 ÷ 成员数）；拖拽/加速度/转向跟随代表类型；</li>
     *     <li>采矿：等级取成员最高（门槛语义）、速度取最快、范围取最远，
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
        // 代表成员的图标（客户端才有 atlas；服务端没有贴图，跳过）
        TextureRegion icon = null;
        if(!mindustry.Vars.headless){
            icon = dom.uiIcon != null ? dom.uiIcon : dom.fullIcon;
            if(icon != null && Core.atlas != null && Core.atlas.isFound(icon)){
                ct.fullIcon = ct.uiIcon = icon;
            }else{
                icon = null;
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
            if(t.mineSpeed > 0f && t.mineTier >= 0){
                mineSpd = Math.max(mineSpd, t.mineSpeed);
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
            if(icon != null){
                placeholder.fullIcon = icon;
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
        write.i(members.size);
        for(int i = 0; i < members.size; i++){
            TypeIO.writePayload(write, members.get(i));
        }
    }

    @Override
    public void readSync(Reads read){
        super.readSync(read);
        readMembers(read);
        refreshDerived();
    }

    /** 存档：同样在原版字段之后追加成员列表。 */
    @Override
    public void write(Writes write){
        super.write(write);
        write.i(members.size);
        for(int i = 0; i < members.size; i++){
            TypeIO.writePayload(write, members.get(i));
        }
    }

    @Override
    public void read(Reads read){
        super.read(read);
        readMembers(read);
        refreshDerived();
    }

    private void readMembers(Reads read){
        int n = read.i();
        members.clear();
        for(int i = 0; i < n; i++){
            Payload p = TypeIO.readPayload(read);
            if(p instanceof UnitPayload up){
                members.add(up);
            }
        }
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
        if(dead) return; // 死亡坠落由原版处理（fallSpeed）
        int mode = moveMode();
        float target = mode == MODE_FLY ? 1f : 0f;
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
