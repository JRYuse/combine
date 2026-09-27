package mindustry.core;

import mindustry.gen.Building;

/**
 * 【测试专用 · 故意放在 {@code mindustry.core} 包里】
 *
 * {@code combine.saves.ComboSaveState.writingSave()} 是靠**调用栈里的类名**判断
 * "这次 writeBase 是联机快照还是存档"：栈里有 {@code mindustry.core.NetServer*} → 按快照处理。
 * 想在测试里模拟"快照那条栈"，最省事的办法就是造一个同前缀名字的探针类
 * （类名只要以 {@code mindustry.core.NetServer} 开头，栈里那一条就能被认出来）。
 * 本文件只在 {@code verify/tests/} 里，不进交付包。
 */
public class NetServerProbe {
  private NetServerProbe() {
  }

  /** 以"联机快照"的身份问：这台建筑该不该把物品池写成真数据。 */
  public static boolean probeFirstItemPool(ClassLoader ml, Building b) throws Exception {
    Class<?> cls = Class.forName("combine.saves.ComboSaveState", true, ml);
    return (Boolean) cls.getMethod("firstItemPool", Building.class).invoke(null, b);
  }

  /** 以"联机快照"的身份问：当前算不算在存档。 */
  public static boolean probeWritingSave(ClassLoader ml) throws Exception {
    Class<?> cls = Class.forName("combine.saves.ComboSaveState", true, ml);
    return (Boolean) cls.getMethod("writingSave").invoke(null);
  }

  /** 以"联机快照"的身份取一份 writeSync 字节（= 服务端发出去的方块快照）。 */
  public static byte[] probeWriteSync(Building b) throws Exception {
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    arc.util.io.Writes w = new arc.util.io.Writes(new java.io.DataOutputStream(bos));
    b.writeSync(w);
    return bos.toByteArray();
  }
}
