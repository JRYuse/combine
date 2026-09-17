package combine;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.ctype.Content;
import mindustry.world.Block;
import mindustry.world.draw.DrawLiquidRegion;
import mindustry.world.draw.DrawLiquidTile;
import mindustry.world.draw.DrawMulti;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;

public class BlockCloner {
  public static final ObjectMap<Block, Block> originalToCombo = new ObjectMap<>();
  public static final ObjectMap<Block, Block> comboToOriginal = new ObjectMap<>();

  @SuppressWarnings("unchecked")
  public static <T extends Block> T createCombo(Block original, Class<T> comboClass) {
    try {
      Constructor<T> ctor = comboClass.getConstructor(String.class);
      // 本版 ContentLoader 对重名硬抛异常且 name 为 final：先用临时唯一名构造，
      // 由 Replacer 反射还回正式名并同 id 接管注册表（旧存档/蓝图无缝兼容）
      String temp = "__replace__" + original.name;
      T combo = ctor.newInstance(temp);
      Replacer.replace(original, combo, temp);
      originalToCombo.put(original, combo);
      comboToOriginal.put(combo, original);
      return combo;
    } catch (Exception ex) {
      throw new RuntimeException("Failed to create combo for " + original.name, ex);
    }
  }

  public static void copyFields(Block from, Block to) {
    Class<?> clazz = from.getClass();
    while (clazz != null && clazz != Object.class) {
      for (Field f : clazz.getDeclaredFields()) {
        int mod = f.getModifiers();
        if (Modifier.isStatic(mod) || Modifier.isFinal(mod))
          continue;

        String n = f.getName();
        if (n.equals("name") || n.equals("id")
            || n.equals("techNode") || n.equals("techNodes")
            || n.equals("buildType")
            || n.equals("bars") || n.equals("stats")
            || n.equals("barMap") // 关键：防止共享 bars 容器
            || n.equals("drawer"))
          continue;

        f.setAccessible(true);
        try {
          Object val = f.get(from);
          Field toField = findField(to.getClass(), n);
          if (toField != null && toField.getType() == f.getType()) {
            toField.setAccessible(true);
            if (n.equals("region") || n.equals("fullIcon") || n.equals("uiIcon")) {
            }

            // FIX[配置串味 / 原版建筑崩溃]: configurations 是"配置值类型 -> 配置回调"的表。
            // 直接共享引用的话，组合方块在 init() 里 config()/configClear()/clear() 改的是
            // **同一张表**，原版方块（战役基地蓝图、地图里的原版炮塔等）就会拿到本模组的回调；
            // 回调里强转成组合建筑的 build 类型 → ClassCastException 直接崩游戏
            // （ItemTurret$ItemTurretBuild cannot be cast to CombinedItemTurret$CombinedItemTurretBuild）。
            // 所以这里复制成独立表，并且保留我们自己已经注册的项（同类型以我们的为准）。
            if (n.equals("configurations") && val instanceof ObjectMap<?, ?> srcMap) {
              ObjectMap<Object, Object> dst = (ObjectMap<Object, Object>) toField.get(to);
              if (dst == null) {
                dst = new ObjectMap<>();
                toField.set(to, dst);
              }
              for (ObjectMap.Entry<?, ?> e : srcMap) {
                if (!dst.containsKey(e.key))
                  dst.put(e.key, e.value);
              }
              continue;
            }

            // FIX[消费者串味]: 数组字段必须新建数组拷贝——直接共享引用的话,
            // 任一克隆体/原版的 consume() 追加都会污染所有共享者(分离机被掺入
            // 电力/冷却液消费者即此因)。元素共享无妨(消费者对象无状态), 数组必须独立
            if (val != null && val.getClass().isArray()) {
              int len = java.lang.reflect.Array.getLength(val);
              Object arrCopy = java.lang.reflect.Array.newInstance(val.getClass().getComponentType(), len);
              System.arraycopy(val, 0, arrCopy, 0, len);
              toField.set(to, arrCopy);
            } else {
              toField.set(to, val);
            }
          }
        } catch (Exception ignored) {
        }
      }
      clazz = clazz.getSuperclass();
    }
  }

  /**
   * 取贴图，但允许没有图集的环境（专用服务器 Core.atlas 为 null）。
   * 组合内容现在服务端也要装配，缺图集时返回 null 即可，服务端不会绘制。
   */
  static TextureRegion atlasFind(String name){
    return Core.atlas == null ? null : Core.atlas.find(name);
  }

  static TextureRegion atlasFind(String name, String fallback){
    return Core.atlas == null ? null : Core.atlas.find(name, fallback);
  }

  public static void postInit(Block combo) {
    Block original = comboToOriginal.get(combo);
    if (original == null)
      return;

    combo.localizedName = original.localizedName;
    combo.description = original.description;
    combo.details = original.details;
    combo.fullIcon = original.fullIcon;
    combo.uiIcon = original.uiIcon;
    combo.region = (original.region != null && original.region.found())
        ? original.region
        : atlasFind(original.name);
    combo.fullIcon = (original.fullIcon != null && original.fullIcon.found())
        ? original.fullIcon
        : atlasFind(original.name + "-full", original.name);
    combo.uiIcon = (original.uiIcon != null && original.uiIcon.found())
        ? original.uiIcon
        : atlasFind(original.name + "-icon", original.name);
    if (combo.region == null) {
      combo.region = (original.region != null && original.region.found())
          ? original.region
          : atlasFind(original.name);
    }
    ObjectMap<String, String> fieldMap = new ObjectMap<>();
    if (combo instanceof CombinedDrill) {
      fieldMap.put("topRegionBeam", "topRegion");
      fieldMap.put("glowRegionBeam", "glowRegion");
      fieldMap.put("topRegionBurst", "topRegion");
      fieldMap.put("glowRegionBurst", "glowRegion");
    }

    ObjectMap<String, TextureRegion> origRegions = new ObjectMap<>();
    collectRegions(original, origRegions);

    Class<?> comboClazz = combo.getClass();
    while (comboClazz != null) {
      for (Field f : comboClazz.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers()))
          continue;
        if (f.getType() != TextureRegion.class)
          continue;

        String n = f.getName();
        if (n.equals("fullIcon") || n.equals("uiIcon"))
          continue;

        f.setAccessible(true);
        try {
          TextureRegion r = origRegions.get(n);
          if (r == null && fieldMap.containsKey(n)) {
            r = origRegions.get(fieldMap.get(n));
          }
          if (r == null) {
            for (String suffix : new String[] { "Beam", "Burst", "Drill" }) {
              if (n.endsWith(suffix)) {
                r = origRegions.get(n.substring(0, n.length() - suffix.length()));
                if (r != null)
                  break;
              }
            }
          }
          if (r != null)
            f.set(combo, r);
        } catch (Exception e) {
          Log.err(e);
        }
      }
      comboClazz = comboClazz.getSuperclass();
    }

    copyRegionArray(original, combo, "teamRegions");
    copyRegionArray(original, combo, "variantRegions");

    // 从原版深拷贝 drawer，然后替换 Liquid 绘制器。
    // 纯绘制用数据：专用服务器没有图集也不会绘制，跳过可省下大量深拷贝与告警。
    if (Core.atlas == null)
      return;

    try {
      Field comboDrawerF = findField(combo.getClass(), "drawer");
      Field origDrawerF = findField(original.getClass(), "drawer");
      if (comboDrawerF != null && origDrawerF != null) {
        comboDrawerF.setAccessible(true);
        origDrawerF.setAccessible(true);
        Object origDrawer = origDrawerF.get(original);
        if (origDrawer != null) {
          Object comboDrawer = deepCopy(origDrawer);
          // FIX[污染原版]: 深拷贝失败回退为原对象时, 绝不能在原对象上做替换,
          // 否则会原地改写 EU 原版方块的 drawer, 污染原对象
          if (comboDrawer != origDrawer) {
            replaceLiquidDrawers(comboDrawer, new IdentityHashMap<>());
          }
          comboDrawerF.set(combo, comboDrawer);
        }
      }
    } catch (Exception e) {
      Log.err(e);
    }
  }

  /** 递归把 drawer 结构中的 DrawLiquidRegion/DrawLiquidTile 替换为 Combined 版本 */
  private static void replaceLiquidDrawers(Object obj, IdentityHashMap<Object, Object> visited) {
    if (obj == null)
      return;
    if (visited.containsKey(obj))
      return;
    visited.put(obj, obj);

    if (obj instanceof DrawLiquidRegion old) {
      // 这个分支理论上不会走到，因为 deepCopy 已经复制了新对象
      // 但如果直接引用了原版对象，需要处理
      return;
    }

    Class<?> clazz = obj.getClass();

    // 数组
    if (clazz.isArray()) {
      int len = Array.getLength(obj);
      for (int i = 0; i < len; i++) {
        Object elem = Array.get(obj, i);
        Object rep = replaceSingle(elem);
        if (rep != elem) {
          Array.set(obj, i, rep);
        } else {
          replaceLiquidDrawers(elem, visited);
        }
      }
      return;
    }

    // Seq（特别是 DrawMulti.drawers）
    if (obj instanceof Seq) {
      Seq<?> seq = (Seq<?>) obj;
      for (int i = 0; i < seq.size; i++) {
        Object elem = seq.get(i);
        Object rep = replaceSingle(elem);
        if (rep != elem) {
          ((Seq) seq).set(i, rep);
        } else {
          replaceLiquidDrawers(elem, visited);
        }
      }
      return;
    }

    // 反射遍历字段
    while (clazz != null && clazz != Object.class) {
      for (Field f : clazz.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers()))
          continue;
        f.setAccessible(true);
        try {
          Class<?> type = f.getType();
          Object val = f.get(obj);
          if (val == null)
            continue;

          if (type == DrawLiquidRegion.class || val instanceof DrawLiquidRegion) {
            Object rep = replaceSingle(val);
            if (rep != val)
              f.set(obj, rep);
          } else if (type == DrawLiquidTile.class || val instanceof DrawLiquidTile) {
            Object rep = replaceSingle(val);
            if (rep != val)
              f.set(obj, rep);
          } else if (!type.isPrimitive() && !type.getName().startsWith("java.lang.")
              && !type.getName().startsWith("arc.struct.")
              && !(val instanceof TextureRegion)
              && !(val instanceof Block) && !(val instanceof Content)) {
            replaceLiquidDrawers(val, visited);
          }
        } catch (Exception ignored) {
        }
      }
      clazz = clazz.getSuperclass();
    }
  }

  /** 把单个 DrawLiquidRegion/DrawLiquidTile 替换为 Combined 版本 */
  private static Object replaceSingle(Object obj) {
    if (obj instanceof DrawLiquidRegion old) {
      DrawCombinedLiquid neo = new DrawCombinedLiquid();
      neo.drawLiquid = old.drawLiquid;
      neo.suffix = old.suffix;
      neo.alpha = old.alpha;
      neo.liquid = old.liquid; // 贴图引用
      return neo;
    }
    if (obj instanceof DrawLiquidTile old) {
      DrawCombinedLiquidTile neo = new DrawCombinedLiquidTile();
      neo.drawLiquid = old.drawLiquid;
      neo.padding = old.padding;
      neo.padLeft = old.padLeft;
      neo.padRight = old.padRight;
      neo.padTop = old.padTop;
      neo.padBottom = old.padBottom;
      neo.alpha = old.alpha;
      return neo;
    }
    return obj;
  }

  private static void collectRegions(Object obj, ObjectMap<String, TextureRegion> out) {
    if (obj == null)
      return;
    Class<?> clazz = obj.getClass();
    while (clazz != null && clazz != Object.class) {
      for (Field f : clazz.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers()))
          continue;
        if (f.getType() != TextureRegion.class)
          continue;
        f.setAccessible(true);
        try {
          TextureRegion r = (TextureRegion) f.get(obj);
          if (r != null)
            out.put(f.getName(), r);
        } catch (Exception ignored) {
        }
      }
      clazz = clazz.getSuperclass();
    }
  }

  private static void copyRegionArray(Block from, Block to, String fieldName) {
    try {
      Field fromF = findField(from.getClass(), fieldName);
      Field toF = findField(to.getClass(), fieldName);
      if (fromF == null || toF == null)
        return;
      fromF.setAccessible(true);
      toF.setAccessible(true);
      TextureRegion[] fromArr = (TextureRegion[]) fromF.get(from);
      if (fromArr == null)
        return;
      TextureRegion[] toArr = new TextureRegion[fromArr.length];
      System.arraycopy(fromArr, 0, toArr, 0, fromArr.length);
      toF.set(to, toArr);
    } catch (Exception ignored) {
    }
  }

  /* ==================== 深拷贝 ==================== */

  public static Object deepCopy(Object src) {
    return deepCopy(src, new IdentityHashMap<>());
  }

  @SuppressWarnings("unchecked")
  private static Object deepCopy(Object src, IdentityHashMap<Object, Object> visited) {
    if (src == null)
      return null;
    if (visited.containsKey(src))
      return visited.get(src);

    Class<?> cls = src.getClass();

    if (cls.isPrimitive() || cls == String.class
        || Number.class.isAssignableFrom(cls)
        || cls == Boolean.class || cls == Character.class
        || src instanceof TextureRegion) {
      return src;
    }

    if (src instanceof Color)
      return ((Color) src).cpy();

    if (cls.isArray()) {
      int len = Array.getLength(src);
      Object copy = Array.newInstance(cls.getComponentType(), len);
      visited.put(src, copy);
      for (int i = 0; i < len; i++) {
        Array.set(copy, i, deepCopy(Array.get(src, i), visited));
      }
      return copy;
    }

    if (src instanceof Seq) {
      Seq<Object> seq = (Seq<Object>) src;
      Seq<Object> copy = new Seq<>(seq.size);
      visited.put(src, copy);
      for (Object o : seq)
        copy.add(deepCopy(o, visited));
      return copy;
    }

    if (cls.getName().startsWith("arc.struct."))
      return src;

    // lambda / 合成类：JDK 里是隐藏类，反射读写字段会被模块系统拒绝（"some fields could not
    // be written"），而它们只保存捕获的不可变环境（DrawPart 的 PartProgress/数值等），
    // 与原实现一样按共享处理即可 —— 不再让它走一遍注定失败的复制。
    if (cls.isSynthetic() || cls.getName().contains("$$Lambda"))
      return src;

    // FIX[EU 兼容]: Content(Liquid/Item/StatusEffect/UnitType/BulletType...) 与 Block
    // 是内容注册表里的全局单例, 必须按引用共享。原实现是在字段循环里 continue 跳过,
    // 克隆对象中这些字段全部保持构造默认值 null —— EU 匿名绘制/更新类
    // (EUBlocks$xx$yy、DrawLA$1) 捕获的 Liquid/Color 引用即因此断裂,
    // 分别导致 LiquidModule.get 的 liquid NPE 和 Effect.add 的 color NPE。
    if (src instanceof Block || src instanceof Content)
      return src;

    try {
      // FIX[匿名类抽屉]: drawer 结构里大量匿名内部类（例如 sublimate / malign 的 drawer，
      // 编译名 Blocks$314$1 / Blocks$321$2）的构造器第一个参数是外部实例 $this$0，
      // 后面跟着捕获变量：无参构造不存在，按"默认参数"造实例会在构造器里直接 NPE
      // （Cannot assign field "heatColor" because "this.this$0" is null），
      // 于是整个 drawer 只能退回共享原版对象。改成先 Unsafe 直接分配实例（绕过构造器，
      // $this$0 与捕获变量随后由字段拷贝填满），最后才退回"默认参数构造器"的老办法。
      Object copy = allocateInstance(cls);
      if (copy == null) {
        deepCopyFallbacks++;
        lastFallbackReason = cls.getName() + ": no usable constructor";
        return src;
      }

      visited.put(src, copy);

      boolean complete = true;
      Class<?> c = cls;
      while (c != null && c != Object.class) {
        for (Field f : c.getDeclaredFields()) {
          if (Modifier.isStatic(f.getModifiers()))
            continue;
          f.setAccessible(true);
          // FIX[全有或全无]: 原实现把整个字段循环包在一个 try 里, 任何一个字段
          // set 抛异常都会跳到外层 catch 返回 src —— 已拷字段白拷、后续字段全部
          // 静默丢失。改为逐字段容错: Content/Block 共享引用, 克隆失败时
          // 也回落为共享原引用, 绝不留构造默认 null。
          try {
            Object val = f.get(src);
            if (val == null)
              continue;
            if (val instanceof Block || val instanceof Content) {
              if (!setField(copy, f, val))
                complete = false;
              continue;
            }
            if (!setField(copy, f, deepCopy(val, visited)))
              complete = false;
          } catch (Throwable t) {
            try {
              if (!setField(copy, f, f.get(src)))
                complete = false;
            } catch (Throwable ignored) {
              complete = false;
            }
          }
        }
        c = c.getSuperclass();
      }
      // 字段没写全（例如某些平台不允许反射写 final 字段）时宁可继续共享原对象，
      // 也不能留一个半成品抽屉（会画出错图，甚至直接 NPE）。
      if (!complete) {
        visited.remove(src);
        deepCopyFallbacks++;
        lastFallbackReason = cls.getName() + ": some fields could not be written";
        return src;
      }
      return copy;
    } catch (Throwable e) {
      // 不再逐类刷屏：只记数量，由 logFallbackSummary() 在内容装配结束后汇总一行。
      deepCopyFallbacks++;
      lastFallbackReason = cls.getName() + ": " + e;
      return src;
    }
  }

  /**
   * 造一个空实例（字段全默认，随后由拷贝循环填满）。
   *
   * 顺序：无参构造 → Unsafe.allocateInstance → 唯一构造器 + 默认参数（老逻辑）。
   * 匿名内部类只有 Unsafe 这条路走得通 —— 这就是原本几十行 "Deep copy fallback" 的来源。
   */
  private static Object allocateInstance(Class<?> cls) {
    try {
      Constructor<?> ctor = cls.getDeclaredConstructor();
      ctor.setAccessible(true);
      return ctor.newInstance();
    } catch (Throwable ignored) {
    }

    Object unsafe = unsafe();
    if (unsafe != null) {
      try {
        return unsafeCall(unsafe, "allocateInstance", new Class<?>[] { Class.class }, new Object[] { cls });
      } catch (Throwable ignored) {
      }
    }

    try {
      Constructor<?>[] ctors = cls.getDeclaredConstructors();
      if (ctors.length == 1) {
        Constructor<?> ctor = ctors[0];
        ctor.setAccessible(true);
        Class<?>[] ptypes = ctor.getParameterTypes();
        Object[] args = new Object[ptypes.length];
        for (int i = 0; i < ptypes.length; i++)
          args[i] = defaultValue(ptypes[i]);
        return ctor.newInstance(args);
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  /** 写字段：反射失败（final 字段等平台限制）时退到 Unsafe 直接写内存。 */
  private static boolean setField(Object target, Field f, Object value) {
    try {
      f.set(target, value);
      return true;
    } catch (Throwable ignored) {
    }

    Object unsafe = unsafe();
    if (unsafe == null)
      return false;
    try {
      long offset = (Long) unsafeCall(unsafe, "objectFieldOffset",
          new Class<?>[] { Field.class }, new Object[] { f });
      Class<?> t = f.getType();
      if (!t.isPrimitive()) {
        unsafeCall(unsafe, "putObject", new Class<?>[] { Object.class, long.class, Object.class },
            new Object[] { target, offset, value });
      } else if (t == int.class) {
        unsafeCall(unsafe, "putInt", new Class<?>[] { Object.class, long.class, int.class },
            new Object[] { target, offset, ((Number) value).intValue() });
      } else if (t == long.class) {
        unsafeCall(unsafe, "putLong", new Class<?>[] { Object.class, long.class, long.class },
            new Object[] { target, offset, ((Number) value).longValue() });
      } else if (t == float.class) {
        unsafeCall(unsafe, "putFloat", new Class<?>[] { Object.class, long.class, float.class },
            new Object[] { target, offset, ((Number) value).floatValue() });
      } else if (t == double.class) {
        unsafeCall(unsafe, "putDouble", new Class<?>[] { Object.class, long.class, double.class },
            new Object[] { target, offset, ((Number) value).doubleValue() });
      } else if (t == short.class) {
        unsafeCall(unsafe, "putShort", new Class<?>[] { Object.class, long.class, short.class },
            new Object[] { target, offset, ((Number) value).shortValue() });
      } else if (t == byte.class) {
        unsafeCall(unsafe, "putByte", new Class<?>[] { Object.class, long.class, byte.class },
            new Object[] { target, offset, ((Number) value).byteValue() });
      } else if (t == boolean.class) {
        unsafeCall(unsafe, "putBoolean", new Class<?>[] { Object.class, long.class, boolean.class },
            new Object[] { target, offset, value });
      } else {
        return false;
      }
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  /* ============ Unsafe：全部走反射，避免编译期依赖与安卓 dex 引用问题 ============ */

  private static Object unsafeObject;
  private static boolean unsafeResolved;

  private static Object unsafe() {
    if (!unsafeResolved) {
      unsafeResolved = true;
      try {
        Class<?> uc = Class.forName("sun.misc.Unsafe");
        Field f = uc.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafeObject = f.get(null);
      } catch (Throwable t) {
        unsafeObject = null;
      }
    }
    return unsafeObject;
  }

  private static Object unsafeCall(Object unsafe, String name, Class<?>[] types, Object[] args)
      throws Exception {
    Method m = unsafe.getClass().getMethod(name, types);
    return m.invoke(unsafe, args);
  }

  /** 深拷贝回退计数（仅用于启动日志汇总，避免每个匿名类各刷一行）。 */
  private static int deepCopyFallbacks = 0;
  private static String lastFallbackReason = "";

  /** 内容装配结束后调用；没有回退就不打日志。 */
  public static void logFallbackSummary() {
    if (deepCopyFallbacks > 0) {
      Log.warn("[combine] deep copy fell back to sharing @ object(s); last: @",
          deepCopyFallbacks, lastFallbackReason);
      deepCopyFallbacks = 0;
      lastFallbackReason = "";
    }
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive())
      return null;
    if (type == boolean.class)
      return false;
    if (type == int.class)
      return 0;
    if (type == long.class)
      return 0L;
    if (type == float.class)
      return 0f;
    if (type == double.class)
      return 0d;
    if (type == short.class)
      return (short) 0;
    if (type == byte.class)
      return (byte) 0;
    if (type == char.class)
      return '\0';
    return null;
  }

  private static Field findField(Class<?> clazz, String name) {
    while (clazz != null && clazz != Object.class) {
      try {
        return clazz.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        clazz = clazz.getSuperclass();
      }
    }
    return null;
  }
}
