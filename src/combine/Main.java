package combine;

import arc.Events;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Json;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import mindustry.Vars;
import mindustry.content.TechTree;
import mindustry.content.TechTree.TechNode;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.Mod;
import mindustry.mod.Mods.LoadedMod;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.blocks.power.ConsumeGenerator;
import mindustry.world.blocks.power.HeaterGenerator;
import mindustry.world.blocks.power.ImpactReactor;
import mindustry.world.blocks.power.NuclearReactor;
import mindustry.world.blocks.production.AttributeCrafter;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.Separator;
import mindustry.world.meta.BuildVisibility;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static combine.BlockCloner.*;

public class Main extends Mod {
  public static String fileName = "whitelist.json";
  static Seq<String> list = new Seq<>();

  static JsonReader reader = new JsonReader();
  static JsonValue json;

  @Override
  public void loadContent() {
    Loads.load();

    Events.on(ClientLoadEvent.class, e -> {
      for (var entry : originalToCombo) {
        postInit(entry.value);
      }
      getWhiteList();
      processModBlocks();
      rebuildTechTree();

      for (var entry : originalToCombo) {
        Block orig = entry.key;
        orig.hideDatabase = true;
        orig.buildVisibility = BuildVisibility.hidden;
        hideNode(orig);
      }
    });
  }

  void getWhiteList() {
    LoadedMod mod = Vars.mods.getMod("combine");
    Fi metaFile = null;
    if (mod.root.child(fileName).exists()) {
      metaFile = mod.root.child(fileName);
    }
    if (metaFile == null) {
      Log.info("has no whitelist.json");
      return;
    }
    json = reader.parse(metaFile);
    if (json == null) {
      Log.info("whitelist.json is empty or invalid, using default empty list");
      return;
    }
    try {
      String[] array = json.asStringArray();
      list.set(array);
    } finally {
    }
  }

  /** 检测是否是 JS extend() 生成的适配器类 */
  boolean isJSAdapter(Block b) {
    Class<?> cls = b.getClass();
    String name = cls.getName().toLowerCase();

    // 类名特征
    if (name.contains("adapter") || name.contains("rhino") || name.contains("javascript"))
      return true;

    // 关键：JavaAdapter 实现了 org.mozilla.javascript.Wrapper
    for (Class<?> iface : cls.getInterfaces()) {
      if (iface.getName().equals("org.mozilla.javascript.Wrapper"))
        return true;
    }

    return false;
  }

  void processModBlocks() {
    for (var c : Vars.content.getBy(ContentType.block)) {
      Block b = (Block) c;
      if (originalToCombo.containsKey(b) || comboToOriginal.containsKey(b))
        continue;
      if (list.contains(b.name))
        continue;

      boolean isFactory = b instanceof GenericCrafter;
      boolean isDrill = b instanceof Drill || b instanceof BeamDrill;
      boolean isGenerator = b instanceof ConsumeGenerator
          || b instanceof mindustry.world.blocks.power.ImpactReactor
          || b instanceof mindustry.world.blocks.power.NuclearReactor;

      if (!isFactory && !isDrill && !isGenerator)
        continue;
      if (b instanceof CombinedCrafter || b instanceof CombinedDrill || b instanceof CombinedGenerator)
        continue;

      Block combo;
      if (isFactory) {
        CombinedCrafter cc = createCombo(b, CombinedCrafter.class);
        if (b instanceof AttributeCrafter)
          cc.mode = CombinedCrafter.Mode.attribute;
        else if (b instanceof Separator)
          cc.mode = CombinedCrafter.Mode.separator;
        else
          cc.mode = CombinedCrafter.Mode.generic;
        combo = cc;
      } else if (isDrill) {
        CombinedDrill cd = createCombo(b, CombinedDrill.class);
        if (b instanceof BeamDrill)
          cd.mode = CombinedDrill.Mode.beam;
        else if (b.name.contains("blast") || b.name.contains("burst"))
          cd.mode = CombinedDrill.Mode.burst;
        else
          cd.mode = CombinedDrill.Mode.drill;
        combo = cd;
      } else { // generator
        CombinedGenerator cg = createCombo(b, CombinedGenerator.class);
        if (b instanceof mindustry.world.blocks.power.HeaterGenerator)
          cg.mode = CombinedGenerator.Mode.heater;
        else if (b instanceof mindustry.world.blocks.power.ImpactReactor)
          cg.mode = CombinedGenerator.Mode.impact;
        else if (b instanceof mindustry.world.blocks.power.NuclearReactor)
          cg.mode = CombinedGenerator.Mode.nuclear;
        else
          cg.mode = CombinedGenerator.Mode.consume;
        combo = cg;
      }

      copyFields(b, combo);
      combo.init();
      combo.postInit();
      combo.loadIcon();
      postInit(combo);
    }
  }

  /**
   * 检查 clazz 在 baseClass 之上是否声明了额外的实例字段。
   * 原版匿名类（如 Blocks$1）通常没有额外字段，返回 false。
   * JS MultiCrafter 等魔改类通常有 tmpRecs 等额外字段，返回 true。
   */
  boolean hasExtraFields(Class<?> clazz, Class<?> baseClass) {
    // 收集基准类及其所有父类的字段名
    Set<String> baseFields = new HashSet<>();
    Class<?> c = baseClass;
    while (c != null && c != Object.class) {
      for (Field f : c.getDeclaredFields()) {
        baseFields.add(f.getName());
      }
      c = c.getSuperclass();
    }

    // 检查 clazz 及其父类（直到 baseClass 为止）的所有声明字段
    c = clazz;
    while (c != null && c != Object.class && c != baseClass) {
      for (Field f : c.getDeclaredFields()) {
        int mod = f.getModifiers();
        if (Modifier.isStatic(mod) || Modifier.isFinal(mod) || f.isSynthetic())
          continue;

        String n = f.getName();
        // 系统字段不算
        if (n.equals("name") || n.equals("id")
            || n.equals("techNode") || n.equals("techNodes")
            || n.equals("buildType") || n.equals("bars") || n.equals("stats"))
          continue;

        if (!baseFields.contains(n)) {
          Log.info("Extra field '" + n + "' found in " + clazz.getName());
          return true;
        }
      }
      c = c.getSuperclass();
    }
    return false;
  }

  void rebuildTechTree() {
    ObjectMap<TechNode, TechNode> nodeMap = new ObjectMap<>();

    for (var entry : originalToCombo) {
      Block orig = entry.key;
      Block combo = entry.value;
      if (orig.techNode == null)
        continue;

      TechNode o = orig.techNode;
      TechNode n = new TechNode(null, combo, combo.researchRequirements());
      n.objectives.addAll(o.objectives);
      n.planet = o.planet;
      n.depth = o.depth;
      n.rootNode = o.rootNode;
      n.researchCostMultipliers = o.researchCostMultipliers;

      if (o.finishedRequirements != null) {
        n.finishedRequirements = new ItemStack[o.finishedRequirements.length];
        for (int i = 0; i < o.finishedRequirements.length; i++) {
          n.finishedRequirements[i] = new ItemStack(
              o.finishedRequirements[i].item, o.finishedRequirements[i].amount);
        }
      }

      combo.techNode = n;
      combo.techNodes.add(n);
      nodeMap.put(o, n);
    }

    for (var entry : nodeMap) {
      TechNode o = entry.key;
      TechNode n = entry.value;

      if (o.parent != null) {
        TechNode p = nodeMap.get(o.parent);
        if (p != null) {
          n.parent = p;
          if (!p.children.contains(n))
            p.children.add(n);
        } else {
          n.parent = o.parent;
          if (!o.parent.children.contains(n))
            o.parent.children.add(n);
        }
      }

      Seq<TechNode> oldChildren = new Seq<>(o.children);
      for (TechNode child : oldChildren) {
        if (!nodeMap.containsKey(child)) {
          child.parent = n;
          n.children.add(child);
          o.children.remove(child);
        }
      }
    }

    for (var entry : nodeMap) {
      TechNode o = entry.key;
      TechNode n = entry.value;
      TechTree.all.remove(o);
      if (!TechTree.all.contains(n))
        TechTree.all.add(n);
    }
  }

  void hideNode(UnlockableContent content) {
    if (content.techNode != null) {
      content.techNode.remove();
      content.techNode = null;
    }
    content.techNodes.clear();
  }
}
