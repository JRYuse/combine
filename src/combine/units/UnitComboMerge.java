package combine.units;

import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Tmp;
import combine.units.mega.MegaUnitEntity;
import combine.units.mega.MegaUnitType;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.gen.Sounds;
import mindustry.content.UnitTypes;
import mindustry.entities.EntityGroup;
import mindustry.gen.EntityMapping;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.gen.WaterMovec;
import mindustry.type.UnitType;
import mindustry.world.Tile;
import mindustry.world.blocks.payloads.UnitPayload;

/**
 * 组合巨兽：把一个单位组合融合成单一大型单位，保留所有成员的能力/武器，
 * 血量/护甲/护盾叠加，贴图取成员中数量最多的类型（并列取血量最少），
 * 绘制大小按综合 hitSize 缩放；随时可以重新解体成成员单位。
 *
 * <p>融合是<b>服务器权威</b>的：联机时客户端通过 {@link MegaOrderPacket} 发送请求，
 * 服务器校验（发起人在线、单位存在、与发起人同队）后自己按编组规则结算，与承伤共享一致；
 * 成员单位由巨兽实体自管存放（见 {@link MegaUnitEntity}；同步/存档字节与原版
 * 货舱同一格式），双端都装本模组即天然一致。
 *
 * <p>形态判定（Σ 按成员 hitSize 计；数量按成员个数计）：
 * <ul>
 *     <li>飞行 hitSize 总和 &gt; 地面 hitSize 总和 且 &gt; 海军 hitSize 总和 → 飞行形态；</li>
 *     <li>否则船类型单位数量过半 → 海军形态（只能在水面活动）；</li>
 *     <li>其余 → 地面形态。</li>
 * </ul>
 *
 * <p>已知限制：被玩家操控的单位融合后玩家会脱控（巨兽交回 AI/指挥控制）；
 * 巨兽被击杀时成员随之一同损失（融合不是无敌寄存柜）；三种形态类型的
 * 面板数值是兜底默认值（真实数值写在实体上，血条/伤害都是准的）。
 */
public class UnitComboMerge{
    /** 地面/飞行/海军三种巨兽类型，register() 里创建。 */
    public static MegaUnitType megaGround, megaAir, megaNaval;

    /**
     * 命令面板里的"组合"指令：注册进所有单位类型的 commands 列表后，
     * 选中单位时右下角命令按钮区就会出现它（图标 link）。
     * 点击只是借原版指令通道"报信"——{@link UnitComboBind} 检测到指令后弹组合菜单，
     * 并把单位原本的指令还原；controller 返回 null，单位行为不受影响。
     */
    public static mindustry.ai.UnitCommand comboCommand;

    /** 船类型判定缓存（原版类型层面没有"海军"标志，看实体是否 WaterMove）。 */
    private static final ObjectMap<UnitType, Boolean> navalCache = new ObjectMap<>();

    /** 在 mod init 时调用：创建三种巨兽类型并登记巨兽实体类的网络/存档 ID。 */
    public static void register(){
        if(megaGround != null) return;

        megaGround = new MegaUnitType("combine-mega-ground");

        megaAir = new MegaUnitType("combine-mega-air"){{
            flying = true;
            engineSize = 10f;
            engineOffset = 20f;
            speed = 1.6f;
            // 这个变体自带引擎参数：比例照它自己的来（hitSize 20 → 位置 1.0×、大小 0.5×）
            engineOffsetRatio = 20f / 20f;
            engineSizeRatio = 10f / 20f;
        }};

        megaNaval = new MegaUnitType("combine-mega-naval"){{
            speed = 1.1f;
        }};

        // 类型没有自己的贴图：图标兜底，防止内容浏览器/小地图等 UI 拿到空图标。
        // 注意这几个只是"还没有巨兽存在时"的占位：命令面板是按 unit.type.id 去 content.unit(id)
        // 取类型的（派生类型共用 megaGround 的 id），所以每次推导出新的成员构成时，
        // MegaUnitEntity.compTypeFor 会把 megaGround 的图标/名字同步成代表成员那套，
        // 否则面板会一直显示 dagger（用户报的"框选巨兽显示 dagger 图标"）。
        megaGround.localizedName = megaAir.localizedName = megaNaval.localizedName = "组合巨兽";
        if(!Vars.headless){
            try{
                megaGround.fullIcon = megaGround.uiIcon = UnitTypes.dagger.fullIcon;
                megaAir.fullIcon = megaAir.uiIcon = UnitTypes.flare.fullIcon;
                megaNaval.fullIcon = megaNaval.uiIcon = UnitTypes.risso.fullIcon;
            }catch(Throwable ignored){
            }
        }

        // 登记巨兽实体类：网络同步重建与存档读取都按 classId 走 EntityMapping
        MegaUnitEntity.CLASS_ID = EntityMapping.register("MegaUnitEntity", MegaUnitEntity::new);

        // 客户端请求包（融合/解体指令）；与 ContentCheckPacket 一样两端都要注册，
        // 注册顺序由同一份 mod 代码保证两端一致
        mindustry.net.Net.registerPacket(MegaOrderPacket::new);

        // 命令面板的"组合"指令：塞进所有单位类型的 commands 列表，
        // 选中单位时就会出现在原版命令按钮区（commands 数从 1 变 2 的类型也会顺带显示面板）
        comboCommand = new mindustry.ai.UnitCommand("combo", "link", w -> null){
            @Override
            public String localized(){
                return "组合";
            }
        };
        for(UnitType t : Vars.content.units()){
            if(t.commands != null && !t.commands.contains(comboCommand)){
                t.commands.add(comboCommand);
            }
        }

        // 巨兽类型也要能被指挥模式控制。late 注册导致 init() 从没给它们配过指令，
        // 这里手动补齐（全部用原版公共字段，不动原版代码）：
        //  - commands + defaultCommand：CommandAI.command() 要求 type.commands 包含指令，
        //    为空则指挥模式点什么都被静默拒绝；defaultCommand 显式给 moveCommand，
        //    原版 create() 出生即挂默认指令（不给的话 CommandAI.init 会拿 commands
        //    第一条当默认指令，这正是初代"巨兽永远挂着组合指令死循环"的坑）；
        //  - stances：不给的话命令面板没有 停止/停火/追击/巡逻 选项；
        //  - "组合"指令同样注入巨兽：选中巨兽点"组合"即可弹菜单解体
        //    （有默认指令兜底，不存在初代版本的死循环问题）；
        //  - range/maxRange：CommandAI 算接敌距离用 unit.type.range，停在默认 -1 会让
        //    巨兽接到攻击指令后一路贴到目标脸上才停。
        for(MegaUnitType mt : new MegaUnitType[]{megaGround, megaAir, megaNaval}){
            mt.commands.add(mindustry.ai.UnitCommand.moveCommand, comboCommand);
            mt.defaultCommand = mindustry.ai.UnitCommand.moveCommand;
            mt.stances.addAll(mindustry.ai.UnitStance.stop, mindustry.ai.UnitStance.holdFire,
                mindustry.ai.UnitStance.pursueTarget, mindustry.ai.UnitStance.patrol);
            if(!mt.flying) mt.stances.add(mindustry.ai.UnitStance.ram);
            mt.range = 260f;
            mt.maxRange = 260f;
            // 原版 UnitType.init 才会指派 pathCost，late 注册导致它停在 null——
            // CommandAI/UnitGroup/isPathImpassable 每帧直接读 type.pathCost，null 会
            // 每帧抛异常打断整个单位更新链（指挥巨兽一动就全局卡死）。这里手动补齐
            // （init 的同款指派：飞行 costNone、海军 costNaval、其余 costGround）
            // pathCost 读旧 Pathfinder.costTypes（按下标取实例），pathCostId 读
            // ControlPathfinder.costTypes（ground=0/hover=1/legs=2/naval=3）
            if(mt.flying){
                mt.pathCost = mindustry.ai.Pathfinder.costTypes.get(mindustry.ai.Pathfinder.costNone);
                mt.pathCostId = mindustry.ai.ControlPathfinder.costIdGround;
            }else if(mt == megaNaval){
                mt.pathCost = mindustry.ai.Pathfinder.costTypes.get(mindustry.ai.Pathfinder.costNaval);
                mt.pathCostId = mindustry.ai.ControlPathfinder.costIdNaval;
            }else{
                mt.pathCost = mindustry.ai.Pathfinder.costTypes.get(mindustry.ai.Pathfinder.costGround);
                mt.pathCostId = mindustry.ai.ControlPathfinder.costIdGround;
            }
        }
    }

    /**
     * 请求融合：客户端发给服务器执行，单机/主机直接执行。
     * 实体增删必须服务器权威，客户端本地执行只会造出服务器不承认的本地幽灵。
     */
    public static void requestMerge(Unit u){
        if(Vars.net.client()){
            Vars.net.send(MegaOrderPacket.of(false, u), true);
        }else{
            merge(u);
        }
    }

    /** 请求解体：同 {@link #requestMerge(Unit)}。 */
    public static void requestSplit(Unit u){
        if(Vars.net.client()){
            Vars.net.send(MegaOrderPacket.of(true, u), true);
        }else{
            split(u);
        }
    }

    /** 是否核心机（不可组合/合体）：原版六台核心单位（alpha/beta/gamma/evoke/incite/emanate）
     *  以及任何 coreUnitDock 类型（核心机出生、与核心对接，编进组合会弄丢玩家出生单位）。 */
    public static boolean isCoreUnit(UnitType t){
        return t != null && (t.coreUnitDock
            || t == UnitTypes.alpha || t == UnitTypes.beta || t == UnitTypes.gamma
            || t == UnitTypes.evoke || t == UnitTypes.incite || t == UnitTypes.emanate);
    }

    /** 单位类型是否为船（以生成的实体是否 WaterMove 判定，结果缓存）。 */
    public static boolean isNaval(UnitType t){
        if(t == null) return false;
        return navalCache.get(t, () -> {
            try{
                return t.constructor != null && t.constructor.get() instanceof WaterMovec;
            }catch(Throwable e){
                return false;
            }
        });
    }

    /**
     * u 融合时实际参与的成员：已组合 → 全组（范围内同 comboId 成员）；
     * 未组合 → 自己 + 半径内所有未组合同队单位。
     */
    public static Seq<Unit> membersFor(Unit u){
        Seq<Unit> out = new Seq<>();
        if(u == null) return out;
        double gid = UnitComboDamage.comboId(u);
        if(gid != 0.0){
            for(Unit o : Groups.unit){
                if(o.team() != u.team() || UnitComboDamage.comboId(o) != gid || !UnitComboDamage.groupable(o)) continue;
                out.add(o);
            }
        }else{
            out.add(u);
            for(Unit o : UnitComboDamage.ungroupedNear(u, UnitComboDamage.joinRadius)){
                out.add(o);
            }
        }
        return out;
    }

    /**
     * 把 u（及其组合/周围单位）融合成组合巨兽。
     * @return 融合出的巨兽；条件不满足（成员不足 2、客户端等）返回 null
     */
    public static Unit merge(Unit u){
        if(u == null || u instanceof MegaUnitEntity || Vars.net.client()) return null;
        Seq<Unit> members = membersFor(u);
        if(members.size < 2) return null;
        return mergeMembers(members);
    }

    /**
     * 一键合体（指挥模式框选的单位）：只融合传入的单位，半径内没被选中的不参与。
     * 成员逐个校验（可编组、同队、存活、去重），不足 2 个返回 null。
     */
    public static Unit mergeSelected(Seq<Unit> units){
        if(units == null || Vars.net.client()) return null;
        Seq<Unit> members = new Seq<>();
        for(Unit u : units){
            if(u == null || !u.isValid() || !UnitComboDamage.groupable(u)) continue;
            if(!members.contains(u)) members.add(u);
        }
        if(members.size < 2) return null;
        return mergeMembers(members);
    }

    /** 请求一键合体：同 {@link #requestMerge(Unit)}，联机走 {@link MegaOrderPacket} 带成员 id。 */
    public static void requestMergeSelected(Seq<Unit> units){
        if(units == null || units.isEmpty()) return;
        if(Vars.net.client()){
            Unit self = Vars.player.unit();
            Unit first = self != null && units.contains(self) ? self : units.first();
            MegaOrderPacket p = MegaOrderPacket.of(false, first);
            arc.struct.IntSeq ids = new arc.struct.IntSeq();
            for(Unit u : units){
                if(u != null) ids.add(u.id());
            }
            p.memberIds = ids.toArray();
            Vars.net.send(p, true);
        }else{
            mergeSelected(units);
        }
    }

    /** 服务端结算一键合体请求：按 id 解析成员（校验存在、与请求者同队），再走 {@link #mergeSelected(Seq)}。 */
    public static Unit mergeSelected(int[] ids, mindustry.game.Team team){
        if(ids == null || Vars.net.client()) return null;
        Seq<Unit> units = new Seq<>();
        for(int id : ids){
            Unit u = Groups.unit.getByID(id);
            if(u != null && u.isAdded() && u.team() == team) units.add(u);
        }
        return mergeSelected(units);
    }

    /** 结算融合：成员已确定（集合/框选），做形态判定、收纳、血量叠加并落盘。 */
    private static Unit mergeMembers(Seq<Unit> members){
        // 形态不再"多数决"定死：统一用地面变体类型，移动能力（飞/游/跑）由成员构成
        // 在实体上动态推导（MegaUnitEntity.moveMode）——有飞机就能飞、有海军就能游、
        // 有陆地就能跑，混合编组不再丢失任何一种移动能力。
        MegaUnitEntity mega = (MegaUnitEntity)megaGround.create(members.first().team());

        float cx = 0f, cy = 0f;
        for(Unit m : members){
            cx += m.x;
            cy += m.y;
        }
        mega.set(cx / members.size, cy / members.size);
        mega.rotation(members.first().rotation());

        // 收纳成员（语义与 PayloadComp.pickup 一致：remove 会 -1 计数，先 +1 抵消；
        // 成员不清血，原样封存——解体时按巨兽血量比例折算返还）
        float sumHp = 0f, sumShield = 0f;
        for(Unit m : members){
            UnitComboDamage.comboId(m, 0.0);
            sumHp += Math.max(m.health(), 0f);
            sumShield += Math.max(m.shield(), 0f);
            if(m.isAdded()){
                m.team().data().updateCount(m.type, 1);
                m.remove();
            }
            mega.addMember(m);
        }

        // 先推导（会按成员叠出 maxHealth），再写血量——反过来血量会被兜底 1000 截断
        mega.refreshDerived(true);
        mega.health(Math.min(sumHp, mega.maxHealth()));
        mega.shield(sumShield);
        mega.add();

        Fx.unitDrop.at(mega.x, mega.y);
        Sounds.unitCreateBig.at(mega.x, mega.y, 0.9f, 0.8f);
        return mega;
    }

    /**
     * 解体落点是否可放置该单位：地图界内、环境标签满足（水/岩浆/地形）、且格子对该单位可通行。
     * 与原版 PayloadComp.dropUnit 的判定一致（canPass + 环境检查）。
     */
    static boolean canPlace(Unit m, float x, float y){
        Tile tile = Vars.world.tileWorld(x, y);
        if(tile == null) return false;
        // 实心方块/地形阻挡（canPass 内部已区分飞行/地面）
        if(!m.canPass(tile.x, tile.y)) return false;
        // 会溺水的地面单位不能落在液体上（原版 UnitComp.updateDrowning 的规则：
        // floor.isLiquid && floor.drownTime > 0 && canDrown → 持续掉血淹死）
        if(m.canDrown()){
            mindustry.world.blocks.environment.Floor f = tile.floor();
            if(f != null && f.isLiquid && f.drownTime > 0f) return false;
        }
        return true;
    }

    /**
     * 为成员寻找可放置落点：先尝试解体环上的首选位置，不行就以它为中心向外螺旋搜索，
     * 再不行围绕巨兽中心搜索。找到则写入 out 并返回 true；找不到返回 false
     * （调用方应让该成员留在巨兽体内，而不是硬放出去送死）。
     */
    static boolean findDropPos(MegaUnitEntity mega, Unit m, float prefX, float prefY, Vec2 out){
        if(canPlace(m, prefX, prefY)){ out.set(prefX, prefY); return true; }
        float step = Math.max(3f, m.hitSize / 2f);
        float maxR = Math.max(mega.hitSize() + m.hitSize + step * 8f, step * 10f);
        for(float r = step; r <= maxR; r += step){
            int count = (int)(r / 2f) + 6;
            for(int i = 0; i < count; i++){
                float ang = i * 360f / count;
                Tmp.v1.trns(ang, r).add(prefX, prefY);
                if(canPlace(m, Tmp.v1.x, Tmp.v1.y)){ out.set(Tmp.v1.x, Tmp.v1.y); return true; }
                Tmp.v1.trns(ang + 180f / count, r).add(mega.x, mega.y);
                if(canPlace(m, Tmp.v1.x, Tmp.v1.y)){ out.set(Tmp.v1.x, Tmp.v1.y); return true; }
            }
        }
        return false;
    }

    /**
     * 巨兽解体：把成员按巨兽当前血量比例放回原单位（满血巨兽 → 成员满血归队）。
     * 每个成员的落点都经 {@link #canPlace} 校验：首选解体环位置，非法则向外搜索合法格；
     * 实在无处可放的成员继续留在巨兽体内（部分解体，巨兽保留残躯），避免位置不合法
     * 导致单位落水/卡墙死亡。全部放出时巨兽才消失。
     * @return 是否成功解体（至少放出一个成员）
     */
    public static boolean split(Unit unit){
        if(unit == null || Vars.net.client()) return false;
        if(!(unit instanceof MegaUnitEntity mega) || !unit.isAdded()) return false;

        Seq<UnitPayload> pays = new Seq<>(mega.members());
        if(pays.isEmpty()) return false;

        float ratio = mega.maxHealth() > 0f ? Mathf.clamp(mega.health() / mega.maxHealth(), 0f, 1f) : 1f;
        float shieldLeft = Math.max(mega.shield(), 0f);
        int n = pays.size;
        int i = 0;
        boolean any = false;

        for(UnitPayload up : pays){
            if(up == null || up.unit == null) continue;
            Unit m = up.unit;
            float ang = i * 360f / n;
            Tmp.v1.trns(ang, mega.hitSize() + m.hitSize);
            if(!findDropPos(mega, m, mega.x + Tmp.v1.x, mega.y + Tmp.v1.y, Tmp.v2)) continue; // 无处可放：留在巨兽体内
            m.set(Tmp.v2.x, Tmp.v2.y);
            m.rotation(mega.rotation());
            m.health(Math.min(m.maxHealth(), Math.max(1f, m.health() * ratio)));
            m.shield(m.shield() + shieldLeft / n);
            // 与 PayloadComp.dropUnit 一致：换新 id 保证客户端重新生成实体、干净同步
            m.id(EntityGroup.nextId());
            m.add();
            m.unloaded();
            mega.removeMember(up); // 只摘掉已放出的成员，避免 remove() 连带处理
            any = true;
            i++;
        }

        if(!any) return false; // 一个都放不出去：保持巨兽原状

        if(mega.memberCount() == 0){
            mega.clearMembers();
            mega.remove();
        }else{
            // 部分解体：按剩余成员刷新巨兽，血量按剩余成员比例重新折算
            float sumHp = 0f;
            for(UnitPayload up : mega.members()){
                if(up != null && up.unit != null) sumHp += Math.min(up.unit.maxHealth(), Math.max(1f, up.unit.health() * ratio));
            }
            mega.health(Math.min(sumHp, mega.maxHealth()));
            mega.refreshDerived();
        }

        Fx.unitDrop.at(mega.x, mega.y);
        Sounds.unitCreateBig.at(mega.x, mega.y, 1.1f, 0.9f);
        return true;
    }

    /** 巨兽的成员构成描述（如 "战锤×2, 领主×1"）；非巨兽或没有成员返回空串。 */
    public static String composition(Unit u){
        if(!(u instanceof MegaUnitEntity mega) || mega.members() == null) return "";
        ObjectMap<UnitType, Integer> tally = new ObjectMap<>();
        for(UnitPayload up : mega.members()){
            if(up != null && up.unit != null && up.unit.type != null){
                tally.put(up.unit.type, tally.get(up.unit.type, 0) + 1);
            }
        }
        if(tally.size == 0) return "";
        Seq<UnitType> types = tally.keys().toSeq();
        types.sort((a, b) -> Integer.compare(tally.get(b, 0), tally.get(a, 0)));
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < types.size; i++){
            if(i > 0) sb.append(", ");
            UnitType t = types.get(i);
            sb.append(t.localizedName).append("×").append(tally.get(t, 0));
        }
        return sb.toString();
    }
}
