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
  @Override
  public int readChunk(DataInput input, IORunnerLength<DataInput> runner) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > 268435456)
      throw new IOException("[combine] corrupt chunk length: " + length);
    byte[] buf = new byte[length];
    input.readFully(buf);
    try {
      runner.accept(new DataInputStream(new ByteArrayInputStream(buf)), length);
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
