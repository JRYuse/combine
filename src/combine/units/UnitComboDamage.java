package combine.units;

import arc.func.Prov;
import arc.struct.IntSeq;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import combine.units.entities.ComboUnit;
import mindustry.Vars;
import mindustry.gen.EntityMapping;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.gen.Building;

/**
 * 组合单位共享伤害机制（实体替换版）。
 *
 * <p>分组方式：组合标记存在镜像实体自己的 {@code comboId} 字段上
 * （见 {@link ComboUnit}，0 = 未组合），<b>不占用原版 flag 字段</b>——
 * flag 可以照常留给逻辑处理器等其它用途。同队伍、comboId 相同且非 0、
 * 相互距离在 {@link #range} 内的单位视为一个组合，共享伤害。
 *
 * <p>实现方式：用 {@code combine.units.entities} 包下的镜像类
 * （{@code CUnitEntity} 等，继承原版生成的单位实体类）替换所有原版单位实体，
 * 重写 {@code rawDamage} 拦截每一次扣血：伤害按组合内各单位
 * <b>当前生命值占总生命值的比例</b> 分摊，其余成员直接扣血
 * （护甲/护盾/规则加成已在被击单位身上结算过一次，分摊时只移动生命值，不重复结算），
 * 被击单位自己的份额交还原版逻辑处理（保留护盾吸收与击杀判定）。
 * 重写 {@code heal} 拦截治疗：修复量按同样的生命占比分给每个成员
 * （修复塔/再生投射器/维修单位等一切 heal 入口都生效）。
 *
 * <p>comboId 的网络同步：镜像类重写了 writeSync/readSync，在原版字段之后
 * 追加写入该字段（原版同步流走 writeSync/readSync，双端镜像类对称，格式一致）。
 * 同步是服务器权威的：逻辑处理器在服务端结算，客户端照常接收。
 */
public class UnitComboDamage{
    /** 总开关。 */
    public static boolean enabled = true;
    /** 组合成员间允许的最大距离（按中心距减去双方碰撞半径判定），<=0 表示不限距离。 */
    public static float range = 240f;
    /** 手动编组时搜索周围单位/单位组的半径。 */
    public static float joinRadius = 160f;
    /** 是否允许不同兵种混编成一个组合。 */
    public static boolean allowMixedTypes = true;

    /** 重入保护：分摊过程中触发的爆炸等二次伤害不再分摊，直接走原版逻辑。 */
    private static boolean handling = false;

    /** 原版实体类 -> 组合实体构造器。 */
    private static final ObjectMap<Class<?>, Prov<Unit>> mirrors = new ObjectMap<>();

    /** 在 mod init 时调用：替换全部原版单位实体。 */
    public static void register(){
        initMirrors();
        replaceUnitConstructors();
        replaceEntityMapping();
    }

    /** 替换所有单位类型的 constructor（影响本地生产/生成）。 */
    @SuppressWarnings("unchecked")
    private static void replaceUnitConstructors(){
        for(UnitType type : Vars.content.units()){
            Prov<? extends Unit> ctor = type.constructor;
            if(ctor == null) continue;
            Prov<Unit> rep = mirrors.get(ctor.get().getClass());
            if(rep != null) type.constructor = rep;
        }
    }

    /** 替换 EntityMapping 中所有指向原版单位实体的构造器（影响网络同步重建）。 */
    @SuppressWarnings("unchecked")
    private static void replaceEntityMapping(){
        // 名称映射：每个名字对应一个构造器，逐个取样判断类别
        EntityMapping.nameMap.each((name, prov) -> {
            Prov<Unit> rep = mirrors.get((Class<?>)prov.get().getClass());
            if(rep != null) EntityMapping.nameMap.put(name, rep);
        });

        // 数字 ID 映射
        for(int i = 0; i < EntityMapping.idMap.length; i++){
            Prov<?> prov = EntityMapping.idMap[i];
            if(prov == null) continue;
            Prov<Unit> rep = mirrors.get(prov.get().getClass());
            if(rep != null) EntityMapping.idMap[i] = rep;
        }
    }

    /** 读取单位的组合标记；非镜像单位视为未组合。 */
    public static double comboId(Unit u){
        return u instanceof ComboUnit cu ? cu.comboId() : 0.0;
    }

    /** 设置单位的组合标记。 */
    public static void comboId(Unit u, double id){
        if(u instanceof ComboUnit cu) cu.comboId(id);
    }

    /**
     * 给单位打组合标记（由组合工厂/重组器在生产时调用）。
     * @param unit 刚生产的单位
     * @param source 生产它的建筑；若是组合建筑且有领头单位，则用领头单位坐标生成组合 ID
     */
    public static void tagProduced(Unit unit, Building source){
        if(unit == null || source == null) return;
        if(source instanceof IUnitCombo combo && combo.leader() != null && !combo.group().isEmpty()){
            comboId(unit, IUnitCombo.B(combo.leader()).pos() + 1.0);
        }
    }

    /** 把单位编入指定组合（组合 ID 需非 0；0 表示退出组合）。 */
    public static void setGroup(Unit unit, double groupId){
        comboId(unit, groupId);
    }

    /** 让 b 加入 a 所在的组合。 */
    public static void join(Unit a, Unit b){
        if(a != null && b != null) comboId(b, comboId(a));
    }

    /** 单位是否可编入组合（活着、可命中、同队、非核心机；组合巨兽是融合体，不能再编组）。 */
    public static boolean groupable(Unit u){
        return u != null && !u.dead() && u.isValid() && u.hittable()
            && !UnitComboMerge.isCoreUnit(u.type)
            && !(u instanceof combine.units.mega.MegaUnitEntity);
    }

    /** 半径内未组合（comboId 为 0）的同队可编单位数（不含 u 自己）。 */
    public static int countUngroupedNear(Unit u, float radius){
        int count = 0;
        for(Unit o : Groups.unit){
            if(o == u || o.team() != u.team() || comboId(o) != 0.0 || !groupable(o)) continue;
            if(radius > 0f && !o.within(u, radius + u.hitSize / 2f + o.hitSize / 2f)) continue;
            count++;
        }
        return count;
    }

    /** 半径内所有未组合的同队可编单位（不含 u 自己），按距离升序。 */
    public static Seq<Unit> ungroupedNear(Unit u, float radius){
        Seq<Unit> out = new Seq<>();
        for(Unit o : Groups.unit){
            if(o == u || o.team() != u.team() || comboId(o) != 0.0 || !groupable(o)) continue;
            if(radius > 0f && !o.within(u, radius + u.hitSize / 2f + o.hitSize / 2f)) continue;
            out.add(o);
        }
        out.sort(o -> o.dst2(u));
        return out;
    }

    /**
     * 半径内各组合的代表单位（每个不同 comboId 取最近的一名成员），按距离升序。
     * 用于"并入哪个单位组"的列表：代表身上的 comboId 即该组的标记。
     */
    public static Seq<Unit> groupRepsNear(Unit u, float radius){
        Seq<Unit> out = new Seq<>();
        Seq<Double> ids = new Seq<>();
        for(Unit o : Groups.unit){
            if(o == u || o.team() != u.team() || !groupable(o)) continue;
            double gid = comboId(o);
            if(gid == 0.0) continue;
            if(radius > 0f && !o.within(u, radius + u.hitSize / 2f + o.hitSize / 2f)) continue;
            int idx = ids.indexOf(gid);
            if(idx < 0){
                ids.add(gid);
                out.add(o);
            }else if(o.dst2(u) < out.get(idx).dst2(u)){
                out.set(idx, o);
            }
        }
        out.sort(o -> o.dst2(u));
        return out;
    }

    /** 单位的组合构成描述（如 "战锤×2, 领主×1"）；未组合或无成员返回空串。 */
    public static String groupComposition(Unit u){
        double gid = comboId(u);
        if(u == null || gid == 0.0) return "";
        ObjectMap<UnitType, Integer> tally = new ObjectMap<>();
        float r = range, hr = u.hitSize / 2f;
        for(Unit o : Groups.unit){
            if(o.team() != u.team() || comboId(o) != gid || !groupable(o)) continue;
            if(r > 0f && !o.within(u, r + hr + o.hitSize / 2f)) continue;
            tally.put(o.type, tally.get(o.type, 0) + 1);
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

    /**
     * 让 u 与目标单位/单位组组合：目标是未组合单位则两者结成新组（u 已组则目标拉入 u 的组），
     * 目标已组合则 u 并入该组。
     * @return 是否成功
     */
    public static boolean combineWith(Unit u, Unit target){
        if(!groupable(u) || !groupable(target) || u == target || u.team() != target.team()) return false;
        double tg = comboId(target);
        if(tg == 0.0){
            double ug = comboId(u);
            if(ug == 0.0){
                double gid = -(Math.min(u.id(), target.id()) + 1.0);
                comboId(u, gid);
                comboId(target, gid);
            }else{
                comboId(target, ug);
            }
        }else{
            comboId(u, tg);
        }
        return true;
    }

    /**
     * 一键编组：把给定单位编成同一个组合（不融合），只打 comboId。
     * 逐个校验（可编组、去重），全部沿用同一个手动负 ID（与工厂自动打标的正数区分）。
     * @return 实际编入的单位数；不足 2 个返回 0 且不改任何单位
     */
    public static int groupSelected(Seq<Unit> units){
        Seq<Unit> members = new Seq<>();
        for(Unit u : units){
            if(u == null || !groupable(u)) continue;
            if(!members.contains(u)) members.add(u);
        }
        if(members.size < 2) return 0;
        int min = Integer.MAX_VALUE;
        for(Unit u : members) min = Math.min(min, u.id());
        double gid = -(min + 1.0);
        for(Unit u : members) comboId(u, gid);
        return members.size;
    }

    /** 服务端结算一键编组请求：按 id 解析成员（校验存在、与请求者同队），再走 {@link #groupSelected(Seq)}。 */
    public static int groupSelected(int[] ids, mindustry.game.Team team){
        if(ids == null || Vars.net.client()) return 0;
        Seq<Unit> units = new Seq<>();
        for(int id : ids){
            Unit u = Groups.unit.getByID(id);
            if(u != null && u.isAdded() && u.team() == team) units.add(u);
        }
        return groupSelected(units);
    }

    /** 请求一键编组：同 {@link UnitComboMerge#requestMergeSelected(Seq)}，联机走 {@link MegaOrderPacket} 带成员 id。 */
    public static void requestGroupSelected(Seq<Unit> units){
        if(units == null || units.isEmpty()) return;
        if(Vars.net.client()){
            MegaOrderPacket p = MegaOrderPacket.of(false, units.first());
            p.group = true;
            arc.struct.IntSeq ids = new arc.struct.IntSeq();
            for(Unit u : units){
                if(u != null) ids.add(u.id());
            }
            p.memberIds = ids.toArray();
            Vars.net.send(p, true);
        }else{
            groupSelected(units);
        }
    }

    /**
     * 请求"把 u 编入 target 所在组合"（手动组合菜单里的那一排按钮）。
     *
     * <p><b>必须走服务器</b>：comboId 是镜像实体上的同步字段，只有服务端写的值才会被同步给所有客户端。
     * 客户端直接调 {@link #combineWith} 只是本地改了一份，服务端下一份快照就把它盖回去 ——
     * 就是模组注释里说的"服务器不承认的本地幽灵"，玩家看到的组合（承伤共享、连线）其实是假的。
     *
     * @return 单机/主机是否成功（联机时结果由服务器结算，这里恒 true 表示已发出请求）
     */
    public static boolean requestCombineWith(Unit u, Unit target){
        if(u == null || target == null) return false;
        if(!Vars.net.client()) return combineWith(u, target);
        // 成员 = 两边的组合并集（各自成组就都带上），服务器再按 id 重新校验
        IntSeq ids = new IntSeq();
        collectIds(u, ids);
        collectIds(target, ids);
        if(ids.size < 2) return false;
        MegaOrderPacket p = MegaOrderPacket.of(false, u);
        p.group = true;
        p.memberIds = ids.toArray();
        Vars.net.send(p, true);
        return true;
    }

    /** 请求"退出当前组合"：联机走服务器（同 {@link #requestCombineWith} 的理由）。 */
    public static void requestUngroup(Unit u){
        if(u == null) return;
        if(!Vars.net.client()){
            ungroup(u);
            return;
        }
        MegaOrderPacket p = MegaOrderPacket.of(false, u);
        p.ungroup = true;
        // 只让 u 退出：同组其他成员留在组合里（和单机的 ungroup 语义一致）
        p.memberIds = new int[]{u.id()};
        Vars.net.send(p, true);
    }

    /** 把 u 所在组合的全部成员 id 收进 out（u 未组合时只收它自己）。 */
    private static void collectIds(Unit u, IntSeq out){
        double gid = comboId(u);
        if(gid == 0.0){
            if(!out.contains(u.id())) out.add(u.id());
            return;
        }
        for(Unit o : Groups.unit){
            if(o.team() == u.team() && comboId(o) == gid && o.isValid() && !out.contains(o.id())) out.add(o.id());
        }
    }

    /** 服务端结算"退出组合"请求：按 id 解析成员（校验存在、与请求者同队），逐个清零 comboId。 */
    public static int ungroupSelected(int[] ids, mindustry.game.Team team){
        if(ids == null || Vars.net.client()) return 0;
        int n = 0;
        for(int id : ids){
            Unit u = Groups.unit.getByID(id);
            if(u != null && u.isAdded() && u.team() == team){
                comboId(u, 0.0);
                n++;
            }
        }
        return n;
    }

    /**
     * 与周围未组合的同队单位组成新组合（手动组合的 ID 用负数，与工厂自动打标的正数区分开）。
     * @return 编入组合的单位数量（含 u 自己）；周围没有可编入的单位时返回 0 且不改动 u
     */
    public static int groupNearby(Unit u, float radius){
        if(!groupable(u)) return 0;
        double gid = -(u.id() + 1.0);
        int count = 0;
        for(Unit o : Groups.unit){
            if(o.team() != u.team() || comboId(o) != 0.0 || !groupable(o)) continue;
            if(radius > 0f && !o.within(u, radius + u.hitSize / 2f + o.hitSize / 2f)) continue;
            comboId(o, gid);
            count++;
        }
        return count;
    }

    /** 半径内最近的、已属于其它组合的同队单位；没有则返回 null。 */
    public static Unit nearestGrouped(Unit u, float radius){
        if(u == null) return null;
        Unit nearest = null;
        float best = Float.MAX_VALUE;
        for(Unit o : Groups.unit){
            if(o == u || o.team() != u.team() || comboId(o) == 0.0 || !groupable(o)) continue;
            if(radius > 0f && !o.within(u, radius + u.hitSize / 2f + o.hitSize / 2f)) continue;
            float d = o.dst2(u);
            if(d < best){
                best = d;
                nearest = o;
            }
        }
        return nearest;
    }

    /**
     * 并入半径内最近的其他组合。
     * @return 并入后该组合的规模；半径内没有其它组合时返回 0 且不改动 u
     */
    public static int joinNearestGroup(Unit u, float radius){
        if(!groupable(u) || comboId(u) != 0.0) return 0;
        Unit nearest = nearestGrouped(u, radius);
        if(nearest == null) return 0;
        comboId(u, comboId(nearest));
        return groupSize(u);
    }

    /** 当前组合规模（与承伤判定同规则：同队、同 comboId、范围内）。 */
    public static int groupSize(Unit u){
        double gid = comboId(u);
        if(u == null || gid == 0.0) return 0;
        int count = 0;
        float r = range, hr = u.hitSize / 2f;
        for(Unit o : Groups.unit){
            if(o.team() != u.team() || comboId(o) != gid || !groupable(o)) continue;
            if(r > 0f && !o.within(u, r + hr + o.hitSize / 2f)) continue;
            count++;
        }
        return count;
    }

    /** 退出组合。 */
    public static void ungroup(Unit u){
        comboId(u, 0.0);
    }

    /**
     * 伤害分摊入口：由镜像实体的 {@code rawDamage} 调用。
     * @param hit 被击中的单位
     * @param amount 已通过护盾前结算（护甲/规则缩放后、护盾吸收前）的伤害值
     * @return 被击单位自己应承担的份额（调用方将其交给原版逻辑）；
     *         无法分摊时原样返回 amount
     */
    public static float apply(Unit hit, float amount){
        double gid = comboId(hit);
        if(!enabled || amount <= 0f || hit == null || hit.dead() || gid == 0.0 || handling) return amount;
        // 客户端不做分摊：由服务端/主机结算后同步生命值，避免重复扣血
        if(Vars.net.client()) return amount;

        Seq<Unit> members = collect(hit);
        if(members.size <= 1) return amount;

        float total = 0f;
        for(int i = 0; i < members.size; i++) total += Math.max(members.get(i).health(), 0f);
        if(total <= 0f) return amount;

        handling = true;
        try{
            float remaining = amount;
            float own = amount;
            for(int i = 0; i < members.size; i++){
                Unit member = members.get(i);
                // 最后一名拿剩余量，避免浮点误差
                float share = (i == members.size - 1) ? remaining : amount * Math.max(member.health(), 0f) / total;
                remaining -= share;
                if(member == hit){
                    own = share;
                    continue;
                }
                damageMember(member, share);
            }
            return Math.max(own, 0f);
        }finally{
            handling = false;
        }
    }

    /**
     * 修复分摊入口：由镜像实体的 {@code heal} 调用，规则与承伤对称——
     * 治疗量按组合内各单位当前生命占比分配给每个成员。
     * @param healed 接受治疗的单位
     * @param amount 治疗量
     * @return 该单位自己应得的份额（调用方交给原版 heal）；无法分摊时原样返回
     */
    public static float applyHeal(Unit healed, float amount){
        double gid = comboId(healed);
        if(!enabled || amount <= 0f || healed == null || healed.dead() || gid == 0.0) return amount;
        // 客户端不做分摊：由服务端/主机结算后同步生命值
        if(Vars.net.client()) return amount;

        Seq<Unit> members = collect(healed);
        if(members.size <= 1) return amount;

        float total = 0f;
        for(int i = 0; i < members.size; i++) total += Math.max(members.get(i).health(), 0f);
        if(total <= 0f) return amount;

        float remaining = amount;
        float own = amount;
        for(int i = 0; i < members.size; i++){
            Unit member = members.get(i);
            float share = (i == members.size - 1) ? remaining : amount * Math.max(member.health(), 0f) / total;
            remaining -= share;
            if(member == healed){
                own = share;
                continue;
            }
            healMember(member, share);
        }
        return Math.max(own, 0f);
    }

    /** 对组合内其他成员直接回血（不超过生命上限，与原版 heal 一致）。 */
    private static void healMember(Unit member, float share){
        if(share <= 0f || member.dead()) return;
        member.health(Math.min(member.maxHealth(), member.health() + share));
    }

    /** 对组合内其他成员直接扣血（不重算护甲/护盾；不免疫的单位不可扣）。 */
    private static void damageMember(Unit member, float share){
        if(share <= 0f || member.dead()) return;
        // 与原版 rawDamage 一致：type.killable 为 false 的单位不掉血（如无敌假人）
        if(!member.type.killable) return;

        member.hitTime(1f);
        float next = member.health() - share;
        if(next <= 0f){
            member.health(0f);
            if(!member.dead()) member.kill();
        }else{
            member.health(next);
        }
    }

    /** 收集与 hit 同组合的成员（含 hit 自己）。 */
    private static Seq<Unit> collect(Unit hit){
        Seq<Unit> out = new Seq<>();
        double gid = comboId(hit);
        boolean typed = !allowMixedTypes;
        float r = range;
        float hr = hit.hitSize / 2f;

        for(Unit u : Groups.unit){
            if(u == hit){
                out.add(u);
                continue;
            }
            if(u.team() != hit.team() || u.dead() || !u.isValid() || !u.hittable()) continue;
            if(comboId(u) != gid || gid == 0.0) continue;
            if(typed && u.type != hit.type) continue;
            if(r > 0f && !u.within(hit, r + hr + u.hitSize / 2f)) continue;
            out.add(u);
        }
        return out;
    }

    /** 填充原版实体类 -> 镜像构造器的映射（放在方法里避免 static 块过长）。 */
    @SuppressWarnings("unchecked")
    private static void initMirrors(){
        mirrors.clear();
        mirrors.put(mindustry.gen.UnitEntity.class, combine.units.entities.CUnitEntity::new);
        mirrors.put(mindustry.gen.MechUnit.class, combine.units.entities.CMechUnit::new);
        mirrors.put(mindustry.gen.LegsUnit.class, combine.units.entities.CLegsUnit::new);
        mirrors.put(mindustry.gen.TankUnit.class, combine.units.entities.CTankUnit::new);
        mirrors.put(mindustry.gen.PayloadUnit.class, combine.units.entities.CPayloadUnit::new);
        mirrors.put(mindustry.gen.UnitWaterMove.class, combine.units.entities.CUnitWaterMove::new);
        mirrors.put(mindustry.gen.CrawlUnit.class, combine.units.entities.CCrawlUnit::new);
        mirrors.put(mindustry.gen.ElevationMoveUnit.class, combine.units.entities.CElevationMoveUnit::new);
        mirrors.put(mindustry.gen.TimedKillUnit.class, combine.units.entities.CTimedKillUnit::new);
        mirrors.put(mindustry.gen.TargetDummyUnit.class, combine.units.entities.CTargetDummyUnit::new);
        mirrors.put(mindustry.gen.BlockUnitUnit.class, combine.units.entities.CBlockUnitUnit::new);
        mirrors.put(mindustry.gen.BuildingTetherPayloadUnit.class, combine.units.entities.CBuildingTetherPayloadUnit::new);
        mirrors.put(mindustry.gen.UnitEntityLegacyAlpha.class, combine.units.entities.CUnitEntityLegacyAlpha::new);
        mirrors.put(mindustry.gen.UnitEntityLegacyBeta.class, combine.units.entities.CUnitEntityLegacyBeta::new);
        mirrors.put(mindustry.gen.UnitEntityLegacyGamma.class, combine.units.entities.CUnitEntityLegacyGamma::new);
        mirrors.put(mindustry.gen.UnitEntityLegacyMono.class, combine.units.entities.CUnitEntityLegacyMono::new);
        mirrors.put(mindustry.gen.UnitEntityLegacyPoly.class, combine.units.entities.CUnitEntityLegacyPoly::new);
        mirrors.put(mindustry.gen.MechUnitLegacyNova.class, combine.units.entities.CMechUnitLegacyNova::new);
        mirrors.put(mindustry.gen.MechUnitLegacyPulsar.class, combine.units.entities.CMechUnitLegacyPulsar::new);
        mirrors.put(mindustry.gen.MechUnitLegacyQuasar.class, combine.units.entities.CMechUnitLegacyQuasar::new);
        mirrors.put(mindustry.gen.LegsUnitLegacyArkyid.class, combine.units.entities.CLegsUnitLegacyArkyid::new);
        mirrors.put(mindustry.gen.LegsUnitLegacySpiroct.class, combine.units.entities.CLegsUnitLegacySpiroct::new);
        mirrors.put(mindustry.gen.LegsUnitLegacyToxopid.class, combine.units.entities.CLegsUnitLegacyToxopid::new);
        mirrors.put(mindustry.gen.PayloadUnitLegacyOct.class, combine.units.entities.CPayloadUnitLegacyOct::new);
        mirrors.put(mindustry.gen.PayloadUnitLegacyQuad.class, combine.units.entities.CPayloadUnitLegacyQuad::new);
    }
}
