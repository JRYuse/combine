package combine;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.ctype.Content;
import mindustry.ctype.ContentType;
import mindustry.ctype.MappableContent;
import mindustry.world.Block;

import java.lang.reflect.Field;

/**
 * 内容替换器：把原版/模组方块实例直接换成组合类实例（同 id、同名字）。
 * 旧存档/地图/蓝图/按名引用全部无缝指向组合版；
 * replaced 表用于事件兜底替换任何残留的旧实例引用。
 * 注意：本版 ContentLoader 对重名硬抛异常且 MappableContent.name 是 final ——
 * 组合实例必须先用临时唯一名构造，再由 replace() 反射改 name、修名字映射。
 */
public class Replacer {
  public static final ObjectMap<Block, Block> replaced = new ObjectMap<>();

  public static void replace(Block orig, Block combo) {
    replace(orig, combo, null);
  }

  @SuppressWarnings("unchecked")
  public static void replace(Block orig, Block combo, String tempName) {
    replaced.put(orig, combo);

    // 0) 还回正式名：name 是 final，编译期不能赋值，但实例 final 引用字段
    //    运行期反射可改（HotSpot/ART 均允许）
    try {
      Field nf = MappableContent.class.getDeclaredField("name");
      nf.setAccessible(true);
      nf.set(combo, orig.name);
    } catch (Throwable t) {
      Log.err("[Replacer] rename failed: @", t);
    }

    // 1) id 接管：combo 构造时被追加到注册表末尾，现在放回原方块的位置
    Seq<Content> list = Vars.content.getBy(ContentType.block);
    combo.id = orig.id;
    list.set(orig.id, combo);
    if (list.peek() == combo) {
      list.pop(); // 弹出构造时追加在末尾的重复项
    } else {
      for (int i = list.size - 1; i >= 0; i--)
        if (list.get(i) == combo) { list.remove(i); break; }
    }

    // 2) 名字映射：移除临时名，登记正式名（contentNameMap / nameMap 都是 private，反射更新）
    try {
      Field f = mindustry.core.ContentLoader.class.getDeclaredField("contentNameMap");
      f.setAccessible(true);
      ObjectMap<String, MappableContent>[] maps =
          (ObjectMap<String, MappableContent>[]) f.get(Vars.content);
      if (tempName != null)
        maps[ContentType.block.ordinal()].remove(tempName);
      maps[ContentType.block.ordinal()].put(orig.name, (MappableContent) combo);

      // FIX[联机客户端丢建筑]: ContentLoader.nameMap 是“全局按名反查”表，
      // content.byName() 走的正是它 —— JsonIO 的 UnlockableContent 序列化器
      // （rules.researched 整个集合）、MapObjectives、编辑器内容框都用它反查。
      // 之前只改了 contentNameMap（getByName(type,name)），nameMap 仍指向被替换掉的
      // 原版实例：联机时客户端读服务端发来的 rules，researched 里装的全是原版方块，
      // UnlockableContent.unlockedHost() 查 researched.contains(组合方块) 一个都命不中，
      // 建造菜单里所有组合建筑就全部消失了（房主本机解锁标记有效所以看不出来）。
      Field globalF = mindustry.core.ContentLoader.class.getDeclaredField("nameMap");
      globalF.setAccessible(true);
      ObjectMap<String, MappableContent> global =
          (ObjectMap<String, MappableContent>) globalF.get(Vars.content);
      global.put(orig.name, (MappableContent) combo);
    } catch (Throwable t) {
      Log.warn("[Replacer] name map update failed: @", t.getMessage());
    }

    // 3) Blocks 静态字段换引用
    try {
      Field f = Blocks.class.getDeclaredField(camel(orig.name));
      f.setAccessible(true);
      f.set(null, combo);
    } catch (Throwable ignored) {
    }

    // 4) 科技树节点指向 combo（整棵树原样保留）
    if (orig.techNode != null) {
      orig.techNode.content = combo;
      combo.techNode = orig.techNode;
      combo.techNodes.clear();
      combo.techNodes.add(orig.techNode);
    }

    // 4.5) 科技树目标重指向：科技树在内容初始化阶段就已构建完毕，
    //    SectorPreset 等节点的目标（如 new Research(Blocks.combustionGenerator)）
    //    捕获的是旧方块实例；Objectives.Research.complete() 读的是该实例的内存解锁位。
    //    不重定向的话，研究了组合建筑旧实例依然是 locked → 目标永不完成
    //    → 对应 sector 当会话无法解锁，只能重启让旧实例从 settings 恢复解锁位。
    try {
      for (mindustry.content.TechTree.TechNode n : mindustry.content.TechTree.all) {
        for (mindustry.game.Objectives.Objective o : n.objectives) {
          if (o instanceof mindustry.game.Objectives.Research r && r.content == orig) {
            r.content = combo;
          } else if (o instanceof mindustry.game.Objectives.Produce p && p.content == orig) {
            p.content = combo;
          }
        }
      }
    } catch (Throwable t) {
      Log.warn("[Replacer] objective rewire failed: @", t.getMessage());
    }

    // 5) 解锁状态随名字保留
    if (orig.unlocked() && !combo.unlocked())
      combo.quietUnlock();

    // 6) 蓝图库引用原地替换（只改内存，不碰文件）
    // 专用服务端没有 Vars.schematics，必须跳过（内容装配现在服务端也要跑）
    if (Vars.schematics != null) {
      for (mindustry.game.Schematic s : Vars.schematics.all()) {
        for (var st : s.tiles) {
          if (st.block == orig)
            st.block = combo;
        }
      }
    }

    // 6.5) 原版内置发射蓝图（Loadouts.basicShard 等）里的核心实例也要换。
    //   它们的 tiles 是 ContentLoader.createBaseContent() 阶段按"当时的核心"读出来的（替换之前），
    //   Schematics.load() 把内置蓝图登记进 schematics.loadouts 时用的键就是这个旧实例。
    //   于是 universe.getLoadout(组合核心) 查不到（返回 null），发射界面里紧接着那行
    //   schematics.getLoadouts().get((CoreBlock)Blocks.coreShard).first() 直接崩
    //   ——用户报的"发射核心时崩溃 / Seq.first() on a null object reference"。
    remapBuiltinLoadouts();
 

    // 7) 逻辑全局常量 @<name>：GlobalVars.init() 在 content 加载阶段固化的是旧实例，
    //    不更新的话逻辑 fetch/lookup 引用 @duo 等永远解析到旧方块 ——
    //    零号地区教程处理器的 fetch build ... @duo 因此永远取不到组合炮台，
    //    buildCount=0 还会让 op mod 除零杀掉处理器，装弹 flag 永不置位。
    try {
      mindustry.logic.LVar v = Vars.logicVars.get("@" + orig.name, true);
      if (v != null && v.isobj)
        v.objval = combo;
    } catch (Throwable ignored) {
    }

    // 8) lookup 指令的 logicIdToContent 映射同理（非教程必需，尽力修）
    try {
      java.lang.reflect.Field f =
          mindustry.logic.GlobalVars.class.getDeclaredField("logicIdToContent");
      f.setAccessible(true);
      mindustry.ctype.UnlockableContent[][] arr =
          (mindustry.ctype.UnlockableContent[][]) f.get(Vars.logicVars);
      mindustry.ctype.ContentType type = orig.getContentType();
      if (arr != null && arr[type.ordinal()] != null)
        for (int i = 0; i < arr[type.ordinal()].length; i++)
          if (arr[type.ordinal()][i] == orig)
            arr[type.ordinal()][i] = combo;
    } catch (Throwable ignored) {
    }
  }

  static String camel(String name) {
    StringBuilder sb = new StringBuilder();
    boolean up = false;
    for (char c : name.toCharArray()) {
      if (c == '-') { up = true; continue; }
      sb.append(up ? Character.toUpperCase(c) : c);
      up = false;
    }
    return sb.toString();
  }

  /**
   * 入服/读档后把"按名字反查错"的内容引用就地换回组合实例。
   *
   * 地图/规则都是从对端整包发过来的，里面的内容引用按名字写、在本地按名字反查。
   * 联机时客户端的 {@code rules.researched} 如果装的是被替换掉的原版实例，
   * {@code unlockedHost()}/{@code unlockedNowHost()}（建造菜单、单位建造、蓝图校验都要用）
   * 会认为组合建筑统统没解锁 → 客户端组不出任何组合建筑。名字表已在
   * {@link #replace(Block, Block, String)} 里修正，这里再兜一层：旧档、
   * 其他模组按引用塞进来的残留实例也一并纠正。
   */
  public static void remapStaleContent() {
    if (replaced.isEmpty() || Vars.state == null || Vars.state.rules == null)
      return;
    remap(Vars.state.rules.researched);
    remap(Vars.state.rules.bannedBlocks);
    remap(Vars.state.rules.revealedBlocks);
  }

  /**
   * 各星球 sector 的 {@code SectorInfo.bestCoreType} 换成组合实例。
   *
   * {@code Planet.load()} 会在内容装配阶段（组合替换之前）对每个 sector 调
   * {@code loadInfo()} 按名字反查方块，于是 {@code bestCoreType} 全部停在被替换掉的原版核心上。
   * 发射时 {@code PlanetDialog.playSelected()} 正是拿它当作核心传给发射界面
   * （{@code loadouts.show(from.info.bestCoreType, ...)}），而蓝图库的键是组合核心 →
   * {@code universe.getLoadout(旧实例)} 查不到任何蓝图 → 返回 null → 发射界面里紧接着的
   * {@code schematics.getLoadouts().get(Blocks.coreShard).first()} 崩
   * （用户报的"发射核心时崩溃"，栈：PlanetDialog.playSelected → LaunchLoadoutDialog.show）。
   * 即使不崩，查不到蓝图也会把玩家自己存的发射蓝图丢掉、退回内置 basicShard。
   */
  public static void remapSectorInfos() {
    if (replaced.isEmpty() || Vars.content == null)
      return;
    try {
      for (mindustry.type.Planet planet : Vars.content.planets()) {
        if (planet.sectors == null)
          continue;
        for (mindustry.type.Sector sector : planet.sectors) {
          mindustry.game.SectorInfo info = sector.info;
          if (info == null || info.bestCoreType == null)
            continue;
          Block combo = replaced.get(info.bestCoreType);
          if (combo != null)
            info.bestCoreType = combo;
        }
      }
    } catch (Throwable t) {
      Log.warn("[Replacer] sector info remap failed: @", t.getMessage());
    }
  }

  /** 集合里凡是"被替换掉的原实例"都换成组合实例；其余（含组合实例本身）原样保留。 */
  private static <T extends mindustry.ctype.UnlockableContent> void remap(
      arc.struct.ObjectSet<T> set) {
    if (set == null || set.isEmpty())
      return;
    Seq<Block> stale = null;
    for (T c : set) {
      if (c instanceof Block b && replaced.containsKey(b)) {
        if (stale == null)
          stale = new Seq<>();
        stale.add(b);
      }
    }
    if (stale == null)
      return;
    for (Block b : stale) {
      Block combo = replaced.get(b);
      if (combo == null)
        continue;
      set.remove((T) b);
      set.add((T) combo);
    }
  }

  /**
   * 原版内置发射蓝图（{@code Loadouts.basicShard/basicFoundation/basicNucleus/basicBastion}）
   * 里的方块换成组合实例。
   *
   * 这些内置蓝图是 {@code ContentLoader.createBaseContent()} 里 {@code Loadouts.load()}
   * 按当时的 {@code Blocks.coreShard} 等读出来的，tiles 里存的是**替换前**的核心实例；
   * 之后 {@code Schematics.load()} 会调 {@code loadLoadouts()} 把它们登记进
   * {@code schematics.loadouts}/{@code defaultLoadouts}，键就是那个旧实例。
   * 组合工厂换掉了 {@code Blocks.coreShard}，于是 {@code universe.getLoadout(组合核心)}
   * 查不到任何蓝图 → 返回 null，发射界面（{@code LaunchLoadoutDialog.show}）里紧接着执行的
   * {@code schematics.getLoadouts().get((CoreBlock)Blocks.coreShard).first()} 就崩了
   * ——用户报的"发射核心时崩溃 / Seq.first() on a null object reference"。
   */
  public static void remapBuiltinLoadouts() {
    try {
      Seq<mindustry.game.Schematic> builtins = new Seq<>();
      builtins.add(mindustry.content.Loadouts.basicShard);
      builtins.add(mindustry.content.Loadouts.basicFoundation);
      builtins.add(mindustry.content.Loadouts.basicNucleus);
      builtins.add(mindustry.content.Loadouts.basicBastion);
      for (mindustry.game.Schematic s : builtins) {
        if (s == null || s.tiles == null)
          continue;
        for (mindustry.game.Schematic.Stile st : s.tiles) {
          Block combo = replaced.get(st.block);
          if (combo != null)
            st.block = combo;
        }
      }
    } catch (Throwable t) {
      Log.warn("[Replacer] builtin loadout remap failed: @", t.getMessage());
    }
  }

  /**
   * 蓝图库（{@code Schematics}）里**已经登记过**的键换成组合实例。
   *
   * 正常顺序（{@code ClientLauncher} 先装配内容、后 {@code assets.load(schematics)}）下，
   * {@link #remapBuiltinLoadouts()} 已经让键从一开始就是组合实例，这里只是兜底：
   * 万一有模组/工具在装配前就 {@code schematics.load()} 过，键会停在旧实例上，
   * 之后查组合核心依然会 miss（发射界面崩溃、蓝图面板列不出内置蓝图）。
   */
  public static void remapLoadoutKeys() {
    if (Vars.schematics == null || replaced.isEmpty())
      return;
    try {
      java.lang.reflect.Field lf = mindustry.game.Schematics.class.getDeclaredField("loadouts");
      lf.setAccessible(true);
      java.lang.reflect.Field df =
          mindustry.game.Schematics.class.getDeclaredField("defaultLoadouts");
      df.setAccessible(true);
      ObjectMap<mindustry.world.blocks.storage.CoreBlock, Seq<mindustry.game.Schematic>> loadouts =
          (ObjectMap<mindustry.world.blocks.storage.CoreBlock, Seq<mindustry.game.Schematic>>) lf
              .get(Vars.schematics);
      ObjectMap<mindustry.world.blocks.storage.CoreBlock, mindustry.game.Schematic> defaults =
          (ObjectMap<mindustry.world.blocks.storage.CoreBlock, mindustry.game.Schematic>) df
              .get(Vars.schematics);
      if (loadouts == null || defaults == null)
        return;

      Seq<mindustry.world.blocks.storage.CoreBlock> stale = new Seq<>();
      for (var e : loadouts) {
        Block combo = replaced.get(e.key);
        if (combo instanceof mindustry.world.blocks.storage.CoreBlock ck && ck != e.key)
          stale.add(e.key);
      }
      for (mindustry.world.blocks.storage.CoreBlock old : stale) {
        mindustry.world.blocks.storage.CoreBlock swapped =
            (mindustry.world.blocks.storage.CoreBlock) replaced.get(old);
        Seq<mindustry.game.Schematic> list = loadouts.remove(old);
        if (list == null)
          continue;
        Seq<mindustry.game.Schematic> exist = loadouts.get(swapped);
        if (exist != null) {
          exist.addAll(list);
        } else {
          loadouts.put(swapped, list);
        }
        mindustry.game.Schematic def = defaults.remove(old);
        if (def != null && !defaults.containsKey(swapped))
          defaults.put(swapped, def);
      }
    } catch (Throwable t) {
      Log.warn("[Replacer] loadout key remap failed: @", t.getMessage());
    }
  }
}
