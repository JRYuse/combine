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
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;

public class BlockCloner {
  public static final ObjectMap<Block, Block> originalToCombo = new ObjectMap<>();
  public static final ObjectMap<Block, Block> comboToOriginal = new ObjectMap<>();

  @SuppressWarnings("unchecked")
  public static <T extends Block> T createCombo(Block original, Class<T> comboClass) {
    try {
      Constructor<T> ctor = comboClass.getConstructor(String.class);
      T combo = ctor.newInstance("c-" + original.name);
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
            || n.equals("barMap") 
            || n.equals("drawer")
            || n.equals("config") || n.equals("configurations"))
          continue;

        f.setAccessible(true);
        try {
          Object val = f.get(from);
          Field toField = findField(to.getClass(), n);
          if (toField != null && toField.getType() == f.getType()) {
            toField.setAccessible(true);
            if (n.equals("region") || n.equals("fullIcon") || n.equals("uiIcon")) {
              Log.info("[BC-COPY] @ -> @ | field=@ | val=@", from.name, to.name, n, val);
            }

            toField.set(to, val);
          }
        } catch (Exception ignored) {
        }
      }
      clazz = clazz.getSuperclass();
    }
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
        : Core.atlas.find(original.name);
    combo.fullIcon = (original.fullIcon != null && original.fullIcon.found())
        ? original.fullIcon
        : Core.atlas.find(original.name + "-full", original.name);
    combo.uiIcon = (original.uiIcon != null && original.uiIcon.found())
        ? original.uiIcon
        : Core.atlas.find(original.name + "-icon", original.name);
    if (combo.region == null) {
      combo.region = (original.region != null && original.region.found())
          ? original.region
          : Core.atlas.find(original.name);
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

    
    try {
      Field comboDrawerF = findField(combo.getClass(), "drawer");
      Field origDrawerF = findField(original.getClass(), "drawer");
      if (comboDrawerF != null && origDrawerF != null) {
        comboDrawerF.setAccessible(true);
        origDrawerF.setAccessible(true);
        Object origDrawer = origDrawerF.get(original);
        if (origDrawer != null) {
          Object comboDrawer = deepCopy(origDrawer);
          replaceLiquidDrawers(comboDrawer, new IdentityHashMap<>());
          comboDrawerF.set(combo, comboDrawer);
        }
      }
    } catch (Exception e) {
      Log.err(e);
    }
  }

  
  private static void replaceLiquidDrawers(Object obj, IdentityHashMap<Object, Object> visited) {
    if (obj == null)
      return;
    if (visited.containsKey(obj))
      return;
    visited.put(obj, obj);

    if (obj instanceof DrawLiquidRegion old) {
      
      
      return;
    }

    Class<?> clazz = obj.getClass();

    
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

  
  private static Object replaceSingle(Object obj) {
    if (obj instanceof DrawLiquidRegion old) {
      DrawCombinedLiquid neo = new DrawCombinedLiquid();
      neo.drawLiquid = old.drawLiquid;
      neo.suffix = old.suffix;
      neo.alpha = old.alpha;
      neo.liquid = old.liquid; 
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
    if (src instanceof Block || src instanceof Content)
      return src;

    try {
      Object copy;
      try {
        copy = cls.getDeclaredConstructor().newInstance();
      } catch (NoSuchMethodException ex) {
        Constructor<?> ctor = cls.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Class<?>[] ptypes = ctor.getParameterTypes();
        Object[] args = new Object[ptypes.length];
        for (int i = 0; i < ptypes.length; i++)
          args[i] = defaultValue(ptypes[i]);
        copy = ctor.newInstance(args);
      }

      visited.put(src, copy);

      Class<?> c = cls;
      while (c != null && c != Object.class) {
        for (Field f : c.getDeclaredFields()) {
          if (Modifier.isStatic(f.getModifiers()))
            continue;
          f.setAccessible(true);
          Object val = f.get(src);
          if (val instanceof Block || val instanceof Content)
            continue;
          f.set(copy, deepCopy(val, visited));
        }
        c = c.getSuperclass();
      }
      return copy;
    } catch (Exception e) {
      Log.warn("Deep copy fallback for " + cls.getName() + ": " + e.getMessage());
      return src;
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
