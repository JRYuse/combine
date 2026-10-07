package combine.saves;

import arc.struct.Seq;
import arc.util.Log;
import arc.util.io.Reads;
import arc.util.io.Writes;
import combine.net.ComboShare;
import mindustry.io.SaveFileReader;
import mindustry.io.SaveVersion;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * 组合体自己的「共享哪些部分」配置（物品/液体/电力/热量）的存档块。
 *
 * <p>组合连接器/组合节点的这份配置存在方块自己的字段里（跟着它们那一份存读档走）；
 * 组合方块是"替换出来"的、没有统一基类可以加字段，所以它们那份按 **pos** 存在
 * {@link ComboShare} 的静态表里，这里负责把它写进存档、读档时装回去。
 *
 * <p>格式（块名 {@link #chunkName}，格式号 {@link #format}）：
 * <pre>
 *   byte format
 *   int  count
 *   每项: int pos; int mask
 * </pre>
 * 只写**非默认**（不是全共享）的那些，大部分存档这个块是空的（{@code shouldWrite()} 返回 false，
 * 原版/别的模组读档时压根看不到这个块名，整块跳过）。
 */
public class ComboShareState implements SaveFileReader.CustomChunk {
  public static final String chunkName = "combine-share-state";
  public static final byte format = 1;
  /** 单项上限：坏档不让内存炸掉。 */
  static final int maxEntries = 1 << 20;

  /** 本次读档这个块有没有真的被读到（用来判断"该清的旧配置清没清"）。 */
  private static boolean readApplied = false;

  public static void register() {
    try {
      SaveVersion.addCustomChunk(chunkName, new ComboShareState());
    } catch (Throwable t) {
      Log.err("[combine] 注册组合体共享配置存档块失败（共享配置将退回默认全共享）", t);
    }
  }

  /**
   * 开始读一张新图/新档时调用：先把上一张图那份组合体配置清掉，并清掉"读到了"的标记。
   *
   * <p>【为什么不放在 SaveLoadEvent】读档时自定义块是在地图区之后、{@code SaveLoadEvent}
   * **之前**读的；在 SaveLoadEvent 里直接清会把刚读回来的配置擦掉。所以：
   * {@link #onWorldLoadBegin()}（换图，块还没读）先清；读档末尾（{@link #onLoadFinished()}）
   * 只有在"这次没读到本块"（老存档/没有非默认配置）时才补清。
   */
  public static void onWorldLoadBegin() {
    readApplied = false;
    ComboShare.clearBodyMasks();
  }

  /** 读档/读图结束：没读到本块就说明上一张图的配置不该留，补一次清空。 */
  public static void onLoadFinished() {
    if (!readApplied)
      ComboShare.clearBodyMasks();
    readApplied = false;
  }

  @Override
  public boolean shouldWrite() {
    try {
      return ComboShare.bodyEntries().size > 0;
    } catch (Throwable t) {
      return false;
    }
  }

  // 自定义块只在存档里用；联机同步（NetworkIO）不带它，客户端照旧靠重建分组。
  @Override
  public boolean writeNet() {
    return false;
  }

  @Override
  public void write(DataOutput stream) throws IOException {
    Seq<int[]> entries = ComboShare.bodyEntries();
    Writes write = new Writes(stream);
    write.b(format);
    write.i(entries.size);
    for (int[] e : entries) {
      write.i(e[0]);
      write.i(e[1]);
    }
  }

  @Override
  public void read(DataInput stream) throws IOException {
    Reads read = new Reads(stream);
    byte revision = read.b();
    int count = read.i();
    if (count < 0 || count > maxEntries)
      throw new IOException("[combine] corrupt share state count: " + count);

    // 读档前先把上一次那份清掉（新图里同一格可能换成了别的东西）
    ComboShare.clearBodyMasks();
    readApplied = true;
    for (int i = 0; i < count; i++) {
      int pos = read.i();
      int mask = read.i();
      ComboShare.loadBodyMask(pos, mask);
    }
    // 装回来之后立刻按新配置重算网络（池子/电网/本地分组）
    try {
      combine.net.ComboNet.markDirty();
    } catch (Throwable ignored) {
    }
    if (revision != format)
      Log.warn("[combine] 组合体共享配置块格式号 " + revision + "（当前 " + format + "），已按兼容方式读取");
  }
}
