package combine.units.entities;

/**
 * 组合单位实体的统一访问接口：组合标记存在镜像实体自己的 {@code comboId} 字段上，
 * 不占用原版的 {@code flag}（flag 留给逻辑处理器等其它用途）。
 * 0 表示未加入任何组合。
 */
public interface ComboUnit{
    double comboId();
    void comboId(double id);
}
