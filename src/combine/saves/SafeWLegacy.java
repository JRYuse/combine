package combine.saves;

import arc.util.io.*;
import mindustry.io.*;
import mindustry.io.versions.LegacyRegionSaveVersion;
import java.io.*;

public class SafeWLegacy extends LegacyRegionSaveVersion {
  public SafeWLegacy(int version) {
    super(version);
  }

  // ===== 缓冲式 chunk: 实体读写不对称只丢单个建筑状态, 主流不失步 =====
  // 【只包 readChunkReads，不包 readChunk】见 SafeWVer 里的说明。
  @Override
  public int readChunkReads(DataInput input, IORunnerLength<Reads> runner) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > 268435456)
      throw new IOException("[combine] corrupt chunk length: " + length);
    byte[] buf = new byte[length];
    input.readFully(buf);
    try {
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
