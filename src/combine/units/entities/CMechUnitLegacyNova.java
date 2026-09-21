package combine.units.entities;

import arc.util.io.Reads;
import arc.util.io.Writes;
import combine.units.UnitComboDamage;
import mindustry.gen.MechUnitLegacyNova;

/**
 * 替换原版 MechUnitLegacyNova 的组合单位实体。
 * 拦截 rawDamage：伤害在组合内按当前生命占比分摊，自己的份额走原版逻辑（保留护盾/击杀判定）。
 * 拦截 heal：治疗量同样按当前生命占比分摊给每个成员。
 * 组合标记存在自己的 comboId 字段上（0 = 未组合），不占用原版 flag 字段，
 * 并在 writeSync/readSync 末尾做网络同步（原版同步字段由 super 处理）。
 */
public class CMechUnitLegacyNova extends MechUnitLegacyNova implements ComboUnit{
    /** 组合标记：同队同 ID 且在范围内的单位共享承伤/修复。0 = 未组合。 */
    public double comboId;

    public CMechUnitLegacyNova(){}

    @Override
    public double comboId(){
        return comboId;
    }

    @Override
    public void comboId(double id){
        this.comboId = id;
    }

    @Override
    public void writeSync(Writes write){
        super.writeSync(write);
        write.d(comboId);
    }

    @Override
    public void readSync(Reads read){
        super.readSync(read);
        comboId = read.d();
    }

    @Override
    public void rawDamage(float amount){
        // 返回本单位自己应承担的份额；其余成员的份额已在 apply 内直接结算
        float own = UnitComboDamage.apply(this, amount);
        if(own > 0f) super.rawDamage(own);
    }

    @Override
    public void heal(float amount){
        // 返回本单位自己应得的修复份额；其余成员的份额已在 applyHeal 内直接结算
        float own = UnitComboDamage.applyHeal(this, amount);
        if(own > 0f) super.heal(own);
    }
}
