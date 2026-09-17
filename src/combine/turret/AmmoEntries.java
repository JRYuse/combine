package combine.turret;
import java.lang.reflect.Constructor;
import mindustry.type.Item;
import mindustry.world.blocks.defense.turrets.ItemTurret;

/**
 * ItemTurret.ItemEntry 构造器是包级私有（且是非静态内部类），
 * mod 代码位于别的包无法直接 new。用反射 setAccessible 创建，
 * 避免 split-package / 类加载器差异导致的 IllegalAccessError。
 */
public class AmmoEntries {
  static final Constructor<ItemTurret.ItemEntry> ctor = find();

  static Constructor<ItemTurret.ItemEntry> find() {
    try {
      // 非静态内部类的构造器第一个参数是外部类实例
      Constructor<ItemTurret.ItemEntry> c =
          ItemTurret.ItemEntry.class.getDeclaredConstructor(ItemTurret.class, Item.class, int.class);
      c.setAccessible(true);
      return c;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** enclosing = 条目所属炮塔方块实例（type() 会读它的 ammoTypes） */
  public static ItemTurret.ItemEntry create(ItemTurret enclosing, Item item, int amount) {
    try {
      return ctor.newInstance(enclosing, item, amount);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
