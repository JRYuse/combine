package combine.distribution;

import arc.struct.Seq;
import arc.util.Log;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.io.SaveFileReader;
import mindustry.io.SaveVersion;
import mindustry.world.Tile;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * 组合传送带"覆盖层数"的自定义存档块。
 *
 * <p>走自定义块而不是把层数续写进地图区的建筑字节流：地图区里必须只剩原版那一份字节，
 * 关掉模组后原版读档才不会错位（同 {@code combine.saves.ComboSaveState} 的理由）。
 *
 * <p>{@link #writeNet()} 必须是 true：联机的**入服世界流**（{@code NetworkIO.writeWorld}）只带
 * "writeNet 的自定义块" —— 传送带不是原版 {@code BlockFlag.synced} 方块（没有任何 block snapshot），
 * 新加入的客户端只能靠这个块拿到层数，否则它会按 1 层自己模拟，物品位置和主机对不上。
 *
 * <p>只写"真的叠过层"（layers &gt; 1）的传送带：绝大多数传送带都是 1 层，不用占存档/入服流量。
 */
public class ConveyorLayerState implements SaveFileReader.CustomChunk {
  public static final String chunkName = "combine-conveyor-layers";
  public static final byte format = 1;
  /** 单项/总数上限：防坏档把内存吃满。 */
  static final int maxEntries = 1 << 20;

  /** 客户端 + 服务端都要注册（服务端写、客户端读）。幂等。 */
  public static void register() {
    try {
      SaveVersion.addCustomChunk(chunkName, new ConveyorLayerState());
    } catch (Throwable t) {
      Log.err("[combine] 注册传送带层数存档块失败（层数将不进存档）", t);
    }
  }

  /**
   * 世界里"叠过层"（层数 &gt; 1）的组合传送带。
   *
   * <p>【必须扫整张地图，不能用 {@code Groups.build}】空转的传送带会 {@code sleep()} 并从
   * {@code Groups.build} 里摘掉（{@code Building.sleep() → remove()}），照 {@code Groups.build} 扫会漏掉
   * 大量"没在动但叠过层"的传送带，存读档/入服就丢层数。
   */
  static Seq<Building> collect() {
    Seq<Building> out = new Seq<>();
    if (Vars.world == null)
      return out;
    try {
      arc.struct.IntSet seen = new arc.struct.IntSet();
      int total = Vars.world.width() * Vars.world.height();
      for (int i = 0; i < total; i++) {
        Tile tile = Vars.world.tiles.geti(i);
        Building b = tile == null ? null : tile.build;
        if (b instanceof Layered l && l.layers() > 1 && b.isValid() && b.block != null && seen.add(b.pos()))
          out.add(b);
      }
    } catch (Throwable t) {
      Log.err("[combine] 收集传送带层数失败", t);
    }
    return out;
  }

  @Override
  public boolean shouldWrite() {
    return !collect().isEmpty();
  }

  @Override
  public boolean writeNet() {
    return true;
  }

  @Override
  public void write(DataOutput stream) throws IOException {
    Seq<Building> list = collect();
    Writes write = new Writes(stream);
    write.b(format);
    write.i(list.size);
    for (Building b : list) {
      write.i(b.pos());
      write.b(((Layered) b).layers());
    }
  }

  @Override
  public void read(DataInput stream) throws IOException {
    Reads read = new Reads(stream);
    read.b(); // revision：目前只有 1 一种格式
    int count = read.i();
    if (count < 0 || count > maxEntries)
      throw new IOException("[combine] corrupt conveyor layer count: " + count);

    for (int i = 0; i < count; i++) {
      int pos = read.i();
      int layers = read.ub();
      Building b = Vars.world == null ? null : Vars.world.build(pos);
      if (!(b instanceof Layered layered))
        continue;
      try {
        layered.layers(layers);
      } catch (Throwable t) {
        Log.err("[combine] 读传送带层数失败 @" + pos, t);
      }
    }
  }
}
