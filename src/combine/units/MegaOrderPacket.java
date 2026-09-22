package combine.units;

import arc.util.io.Reads;
import arc.util.io.Writes;
import combine.units.mega.MegaUnitEntity;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.net.NetConnection;
import mindustry.net.Packet;

/**
 * 组合巨兽的客户端请求包（客户端 → 服务端）：申请把某单位融合成巨兽，或申请解体某巨兽。
 *
 * <p>融合/解体会创建/移除实体，必须服务器权威结算，客户端只能请求：
 * 服务器收到后重新校验（发起人在线、单位存在、<b>与发起人同队</b>），
 * 成员构成也是服务器自己按 {@link UnitComboDamage} 的编组规则现场算的，
 * 客户端无法干预实际结果。
 */
public class MegaOrderPacket extends Packet{
    /** true = 解体；false = 融合/编组。 */
    public boolean split;
    /** true = 把 memberIds 编成组合（不融合）；false = 融合。仅 split=false 时有意义。 */
    public boolean group;
    /** true = 退出组合（memberIds 里的单位把 comboId 清零）。仅 split=false、group=false 时有意义。 */
    public boolean ungroup;
    /** 目标单位的网络 id（融合 = 发起单位；解体 = 巨兽；编组时取第一个成员）。 */
    public int unitId;
    /** 框选操作（合体/编组）时的成员 id 列表；为空表示旧式"发起单位+周围全组合"。 */
    public int[] memberIds = new int[0];

    public MegaOrderPacket(){
    }

    public static MegaOrderPacket of(boolean split, Unit unit){
        MegaOrderPacket p = new MegaOrderPacket();
        p.split = split;
        p.unitId = unit == null ? -1 : unit.id();
        return p;
    }

    @Override
    public void write(Writes write){
        write.bool(split);
        write.bool(group);
        write.bool(ungroup);
        write.i(unitId);
        write.i(memberIds == null ? 0 : memberIds.length);
        if(memberIds != null){
            for(int id : memberIds) write.i(id);
        }
    }

    @Override
    public void read(Reads read){
        split = read.bool();
        group = read.bool();
        ungroup = read.bool();
        unitId = read.i();
        int n = Math.min(Math.max(read.i(), 0), 4096);
        memberIds = new int[n];
        for(int i = 0; i < n; i++) memberIds[i] = read.i();
    }

    @Override
    public void handleClient(){
        // 客户端不处理：这是发给服务端的请求包
    }

    @Override
    public void handleServer(NetConnection connection){
        // 发起人在线
        if(connection.player == null) return;
        Unit unit = Groups.unit.getByID(unitId);
        // 单位必须真实存在
        if(unit == null || !unit.isAdded()) return;
        // 只能对自己队伍的单位发号施令
        if(connection.player.team() != unit.team()) return;

        if(split){
            if(unit instanceof MegaUnitEntity) UnitComboMerge.split(unit);
        }else if(ungroup){
            // 退出组合：只打 comboId = 0，服务器按 id 自己解析校验
            UnitComboDamage.ungroupSelected(memberIds, connection.player.team());
        }else if(group){
            // 一键编组：只打 comboId，不融合；成员由服务器按 id 解析校验
            UnitComboDamage.groupSelected(memberIds, connection.player.team());
        }else if(memberIds != null && memberIds.length > 0){
            // 一键合体：成员由服务器按 id 自己解析校验，客户端无法干预
            UnitComboMerge.mergeSelected(memberIds, connection.player.team());
        }else{
            UnitComboMerge.merge(unit);
        }
    }
}
