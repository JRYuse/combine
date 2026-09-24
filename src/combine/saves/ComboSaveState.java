package combine.saves;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.io.Reads;
import arc.util.io.Writes;
import combine.BlockCloner;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.io.SaveFileReader;
import mindustry.io.SaveVersion;
import mindustry.world.Block;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;

// 组合建筑"模组专属状态"的自定义存档块 —— 动机见 ComboSaved。
//
// 格式（块名 combine-combo-state，格式号见 format）：
//   byte  format
//   int   count
//   每项: int pos; int length; byte[length] payload（payload 由 ComboSaved.writeCombo 写）
//
// 每项带长度，读的时候遇到对不上的项能整项跳过，不会把后面的流带偏。
public class ComboSaveState implements SaveFileReader.CustomChunk{
  // 自定义块名：原版读到不认识的名字会 skipChunk，整块丢掉。
  public static final String chunkName = "combine-combo-state";
  // 本块自己的格式号（ComboSaved.readCombo 拿到的 revision 就是它）。
  public static final byte format = 1;
  // 单项上限：防坏档把内存吃满。
  static final int maxEntry = 1 << 20;

  // 客户端 + 服务端都要注册（服务端也会存档）。幂等。
  public static void register(){
    try{
      SaveVersion.addCustomChunk(chunkName, new ComboSaveState());
    }catch(Throwable t){
      Log.err("[combine] 注册自定义存档块失败（存档将退回旧格式）", t);
    }
  }

  // 世界里需要额外状态的组合建筑。
  static Seq<Building> collect(){
    Seq<Building> out = new Seq<>();
    if(Vars.world == null)
      return out;
    try{
      for(Building b : Groups.build){
        if(b instanceof ComboSaved && b.isValid() && b.block != null)
          out.add(b);
      }
    }catch(Throwable t){
      Log.err("[combine] 收集组合建筑状态失败", t);
    }
    return out;
  }

  @Override
  public boolean shouldWrite(){
    return !collect().isEmpty();
  }

  // 自定义块只在存档里用；联机同步（NetworkIO）不带它，客户端照旧靠重建分组。
  @Override
  public boolean writeNet(){
    return false;
  }

  @Override
  public void write(DataOutput stream) throws IOException{
    Seq<Building> list = collect();
    Writes write = new Writes(stream);
    write.b(format);
    write.i(list.size);
    for(Building b : list){
      byte[] payload = encode((ComboSaved)b);
      write.i(b.pos());
      write.i(payload.length);
      stream.write(payload);
    }
  }

  @Override
  public void read(DataInput stream) throws IOException{
    Reads read = new Reads(stream);
    byte revision = read.b();
    int count = read.i();
    if(count < 0 || count > 1 << 20)
      throw new IOException("[combine] corrupt combo state count: " + count);

    for(int i = 0; i < count; i++){
      int pos = read.i();
      int length = read.i();
      if(length < 0 || length > maxEntry)
        throw new IOException("[combine] corrupt combo state entry length: " + length);
      byte[] payload = new byte[length];
      stream.readFully(payload);

      Building b = Vars.world == null ? null : Vars.world.build(pos);
      if(!(b instanceof ComboSaved saved))
        continue;
      try{
        saved.readCombo(new Reads(new DataInputStream(new ByteArrayInputStream(payload))), revision);
      }catch(Throwable t){
        Log.err("[combine] 读组合建筑状态失败 @" + pos, t);
      }
    }
  }

  static byte[] encode(ComboSaved saved){
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    saved.writeCombo(new Writes(new DataOutputStream(buffer)));
    return buffer.toByteArray();
  }

  // ==================== 原版那一份字节 ====================

  // 组合体里真正的组长（按 pos 最小，和 rebuildCombo 的选法一致）。
  // 存档时只有组长写"真实模块"，其余成员写空模块 —— 否则每个成员各写一份整池，
  // 读档时把 N 份并起来，数量就 ×N（旧版"读档物品翻倍"的根因）。
  public static Building trueLeader(Building self, Iterable<? extends Building> group){
    Building leader = self;
    if(group != null){
      for(Building b : group){
        if(b != null && b.isValid() && b.pos() < leader.pos())
          leader = b;
      }
    }
    return leader;
  }

  /**
   * 自己是不是组员（不是组长）——**只在写存档时**算数。
   *
   * 为什么必须区分存档 / 联机同步：非组长写空模块是为了防"读档物品翻倍"，
   * 但联机的 block snapshot（客户端把鼠标移到建筑上看物品时会请求一次）走的
   * **是同一套 writeBase**。同步快照里也写空模块的话，客户端读到的就是空模块 ——
   * 而组合体成员共用同一份 ItemModule，`ItemModule.read()` 一上来就
   * `Arrays.fill(items, 0)`，整组池子在客户端当场被清空：
   * 用户报的"联机时鼠标移到建筑上查看物品，物资被清空"就是这个。
   *
   * 所以同步（不是存档）时一律写真数据：客户端读到的就是真池子。
   */
  public static boolean isFollower(Building self, Iterable<? extends Building> group){
    if(!writingSave())
      return false;
    return trueLeader(self, group) != self;
  }

  /** 现在是不是在写存档（而不是在写联机同步快照）。 */
  public static boolean writingSave(){
    try{
      if(Vars.control != null && Vars.control.saves != null && Vars.control.saves.isSaving())
        return true;
    }catch(Throwable ignored){
    }
    // 兜底：按调用栈判断（服务端/地图导出这类不走 Saves 的存档路径）。
    // 认不出来时按"不是存档"处理 —— 万一真在存档，也只是每个成员各写一份整池，
    // 读档时由去重窗口兜住（不会丢东西），比清空客户端池子安全得多。
    try{
      for(StackTraceElement e : new Throwable().getStackTrace()){
        String cn = e.getClassName();
        if(cn.startsWith("mindustry.io.Save") || cn.startsWith("mindustry.io.MapIO"))
          return true;
        if(cn.startsWith("mindustry.core.NetServer"))
          return false;
      }
    }catch(Throwable ignored){
    }
    return false;
  }

  static final ObjectMap<Block, Byte> versionCache = new ObjectMap<>();

  // 组合方块对应的**原版方块**的 build 版本号。
  //
  // 组合类是一个类顶多个原版模式（发电机 4 种、墙/门/力墙 3 种…），版本号不能固定写死：
  // 地图区里写出去的东西必须和原版那一台的版本号一致，原版读档才会按同一套字段读。
  // 直接问原方块自己 new 出来的 build（结果缓存，一个方块只算一次）。
  public static byte vanillaVersion(Block comboBlock){
    Block original = comboBlock == null ? null : BlockCloner.comboToOriginal.get(comboBlock);
    if(original == null)
      return 0;
    Byte cached = versionCache.get(original);
    if(cached != null)
      return cached;

    byte version = 0;
    try{
      version = original.newBuilding().version();
    }catch(Throwable t){
      Log.warn("[combine] 取原版 @ 的 version() 失败，按 0 处理", original.name);
    }
    versionCache.put(original, version);
    return version;
  }
}
