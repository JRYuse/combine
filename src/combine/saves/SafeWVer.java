package combine.saves;
import arc.util.io.*;
import mindustry.io.*;
import mindustry.io.SaveVersion;
import java.io.*;

/**
 * v10/v12/v13 及所有直接继承 SaveVersion 的版本包装器.
 * 不覆写 read(): 继承的原版 read() 内部调用全部走运行时(可能被 R8 重命名)的
 * 正确方法名, 只在 readChunk/readLegacyShortChunk 两个点注入缓冲,
 * 从而彻底免疫方法名反射失效问题。
 */
public class SafeWVer extends SaveVersion {
  public SafeWVer(int version) {
    super(version);
  }

  // ===== 缓冲式 chunk: 实体读写不对称只丢单个建筑状态, 主流不失步 =====
  //
  // 【只包 readChunkReads（实体字节块 / 地图区里的单台建筑状态），不包 readChunk】
  // readRegion()（meta/map/entities/custom 整个区域）走的也是 readChunk —— 连它一起吞异常，
  // 等于"地图区里某一台建筑读崩 → 把整个 map 区域剩下的字节静默丢掉"：读档后一大片
  // 地面/环境墙没被读到、别处残留着旧内容（用户报的"地图读写时大范围移除环境墙和放置
  // 一些其他块"）。区域级错误应该照旧抛出去让读档明确报错，只有"单台建筑的实体块"这种
  // 读写不对称才需要被隔离。
  @Override
  public int readChunkReads(DataInput input, IORunnerLength<Reads> runner) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > 268435456)
      throw new IOException("[combine] corrupt chunk length: " + length);
    byte[] buf = new byte[length];
    input.readFully(buf);
    try {
      // 每次新起一个 Reads（别复用静态 chunkReads）：实体块可能嵌套读取，
      // 共享同一个 Reads 实例会被内层覆盖 input，外层读到错位的字节。
      runner.accept(new Reads(new DataInputStream(new ByteArrayInputStream(buf))), length);
    } catch (Throwable t) {
      arc.util.Log.warn("[combine] entity chunk over-read, state defaulted: @", t.getMessage());
    }
    return length;
  }

  @Override
  public int readLegacyShortChunk(DataInput input, IORunnerLength<Reads> runner)
      throws IOException {
    int length = input.readUnsignedShort();
    if (length > 33554432)
      throw new IOException("[combine] corrupt legacy chunk length: " + length);
    byte[] buf = new byte[length];
    input.readFully(buf);
    chunkReads.input = new DataInputStream(new ByteArrayInputStream(buf));
    try {
      runner.accept(chunkReads, length);
    } catch (Throwable t) {
      arc.util.Log.warn("[combine] legacy chunk over-read: @", t.getMessage());
    }
    return length;
  }
}
