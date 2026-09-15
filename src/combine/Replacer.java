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

    // 2) 名字映射：移除临时名，登记正式名（contentNameMap 是 private，反射更新）
    try {
      Field f = mindustry.core.ContentLoader.class.getDeclaredField("contentNameMap");
      f.setAccessible(true);
      ObjectMap<String, MappableContent>[] maps =
          (ObjectMap<String, MappableContent>[]) f.get(Vars.content);
      if (tempName != null)
        maps[ContentType.block.ordinal()].remove(tempName);
      maps[ContentType.block.ordinal()].put(orig.name, (MappableContent) combo);
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

    // 5) 解锁状态随名字保留
    if (orig.unlocked() && !combo.unlocked())
      combo.quietUnlock();

    // 6) 蓝图库引用原地替换（只改内存，不碰文件）
    for (mindustry.game.Schematic s : Vars.schematics.all()) {
      for (var st : s.tiles) {
        if (st.block == orig)
          st.block = combo;
      }
    }
 

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
}
