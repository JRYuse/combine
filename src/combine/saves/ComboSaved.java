package combine.saves;

import arc.util.io.Reads;
import arc.util.io.Writes;

// "模组自己的存档状态"搬运接口：组合建筑把**原版读不到、也不该读到**的字段
// （组长位置、共享热量、选中的燃料/冷却液…）写进自定义存档块，
// 而地图区里只写原版那一份字节。
//
// 【为什么必须这样】存档里每个建筑的数据是一段"按原版类自己的 write/read 严格对称"的字节流。
// 组合建筑以前把模组字段直接续写在地图区里，而原版读同一台机器时只读它认识的那几个字段，
// 多出来的字节就留在了流里 —— 地图区从这一格开始整片错位，读档时直接抛
// "Error reading region map / Could not skip bytes"。用户把模组关掉后存档读不了（"坏档"）就是这条链。
//
// 现在：地图区里只留原版工厂/炮台/墙那一份字节 → 关掉模组后原版读档，
// 组合建筑干干净净地变回原版建筑；模组自己的字段放在自定义块里，
// 原版遇到不认识的自定义块名会整块跳过（SaveVersion.readCustomChunks），不受影响。
public interface ComboSaved{
  // 写模组自己的状态（只有本模组会读）。
  void writeCombo(Writes write);

  // 读回模组自己的状态；revision 是自定义块的格式号（见 ComboSaveState.format）。
  void readCombo(Reads read, byte revision);
}
