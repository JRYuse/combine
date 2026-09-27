package combine.saves;

import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
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

  // ==================== "每口池子只写一份" ====================
  //
  // 【用户报的"物品数量异常增长"的根因】以前按"本地组合体的组长"决定谁写真实模块，
  // 而组合节点/连接器接起来的**整张网络**只有一口池子：网络里 M 个本地组的组长各自都把
  // 同一份池子写了一遍 → 存档里有 M 份同样的库存。读档时每次 read 都新建一个模块对象，
  // 于是这 M 份变成 M 个**不同的**模块，世界物品总量直接 ×M；再存一次又 ×M……
  // （用户存档实测：一轮存读 14.7M → 32.6M → 69M，池子里出现 100 万 / 500 万这种数字。）
  //
  // 现在按"池子的身份"去重：同一份模块对象在一次存档里只有第一台写真实数据，
  // 其余一律写空模块（读档时它们本来就会被并回那一份）。联机快照不走这条路（见 writingSave）。
  private static final ObjectSet<mindustry.world.modules.ItemModule> seenItems = new ObjectSet<>();
  private static final ObjectSet<mindustry.world.modules.LiquidModule> seenLiquids = new ObjectSet<>();

  /** 这台建筑该不该把**物品池**写成真实数据（一次存档里同一份池子只有第一台写）。 */
  public static boolean firstItemPool(Building self){
    if(!writingSave()){
      seenItems.clear();
      seenLiquids.clear();
      return true;
    }
    return self.items == null || seenItems.add(self.items);
  }

  /** 液体池版。 */
  public static boolean firstLiquidPool(Building self){
    if(!writingSave()){
      seenItems.clear();
      seenLiquids.clear();
      return true;
    }
    return self.liquids == null || seenLiquids.add(self.liquids);
  }

  /** 现在是不是在写存档（而不是在写联机同步快照）。 */
  public static boolean writingSave(){
    // 【先看调用栈，再看不看"正在存档"标志】顺序很关键：
    // Saves.update() 里 `saving = true` 之后要 3 个时间单位才复位（Time.runTask(3f, ...)），
    // 而联机的方块快照是每 snapshotInterval 发一次 —— 掉在这一小段里时
    // `saves.isSaving()` 还是 true。以前先看标志，于是**联机快照被当成存档**：
    // 非组长写空模块 → 客户端读到空模块把整组池子清零，下一帧快照又恢复
    // = 用户报的"工厂资源间歇性清零"。
    // 调用栈里出现 NetServer（联机快照/实体包）一律按"不是存档"处理；
    // 出现 mindustry.io.Save*/MapIO（真存档/地图导出）一律按"是存档"处理；
    // 都没有才回落到 isSaving() 标志。
    try{
      for(StackTraceElement e : new Throwable().getStackTrace()){
        String cn = e.getClassName();
        if(cn.startsWith("mindustry.core.NetServer"))
          return false;
        if(cn.startsWith("mindustry.io.Save") || cn.startsWith("mindustry.io.MapIO"))
          return true;
      }
    }catch(Throwable ignored){
    }
    // 兜底：认不出来时才信标志。万一真在存档，也只是每个成员各写一份整池，
    // 读档时由去重窗口兜住（不会丢东西），比清空客户端池子安全得多。
    try{
      if(Vars.control != null && Vars.control.saves != null && Vars.control.saves.isSaving())
        return true;
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
