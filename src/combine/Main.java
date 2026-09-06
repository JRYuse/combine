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
import mindustry.game.EventType.UnlockEvent;
import mindustry.mod.Mod;
import mindustry.mod.Mods.LoadedMod;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.blocks.campaign.LaunchPad;
import mindustry.world.blocks.defense.ForceProjector;
import mindustry.world.blocks.defense.MendProjector;
import mindustry.world.blocks.defense.OverdriveProjector;
import mindustry.world.blocks.defense.RegenProjector;
import mindustry.world.blocks.power.ConsumeGenerator;
import mindustry.world.blocks.power.HeaterGenerator;
import mindustry.world.blocks.power.ImpactReactor;
import mindustry.world.blocks.power.NuclearReactor;
import mindustry.world.blocks.production.AttributeCrafter;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.BurstDrill;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.Separator;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.StorageBlock;
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

    Events.on(UnlockEvent.class, event -> {
      if (event.content instanceof Block b) {
        Block orig = comboToOriginal.get(b);
        if (orig != null && !orig.unlocked()) {
          orig.unlock();
        }
      }
    });

    Events.on(ClientLoadEvent.class, e -> {
      for (var entry : originalToCombo) {
        postInit(entry.value);
      }
      getWhiteList();
      processModBlocks();
      rebuildTechTree();

      // 隐藏所有原版（包括 processModBlocks 动态生成的）
      for (var entry : originalToCombo) {
        Block orig = entry.key;
        orig.hideDatabase = true;
        orig.buildVisibility = BuildVisibility.hidden;
        hideNode(orig);
      }

      // 全量同步：组合已解锁 → 原版也解锁（覆盖预置 + Mod 动态生成）
      syncUnlocks();
    });
  }

  /** 同步所有组合方块与其原版的解锁状态 */
  void syncUnlocks() {
    for (var entry : originalToCombo) {
      Block orig = entry.key;
      Block combo = entry.value;
      if (combo.unlocked() && !orig.unlocked()) {
        orig.unlock();
      }
    }
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

    if (name.contains("adapter") || name.contains("rhino") || name.contains("javascript"))
      return true;

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
      boolean isDrill = b instanceof Drill || b instanceof BeamDrill || b instanceof BurstDrill;
      boolean isGenerator = b instanceof ConsumeGenerator
          || b instanceof ImpactReactor
          || b instanceof NuclearReactor;
      boolean isLaunchPad = b instanceof LaunchPad;
      boolean isRegen = b instanceof RegenProjector;
      boolean isOverdrive = b instanceof OverdriveProjector;
      boolean isMend = b instanceof MendProjector;
      boolean isForce = b instanceof ForceProjector;
      boolean isStorage = b instanceof StorageBlock && !(b instanceof CoreBlock);

      if (!isFactory && !isDrill && !isGenerator && !isLaunchPad
          && !isRegen && !isOverdrive && !isMend && !isForce && !isStorage)
        continue;

      // 防止重复处理已转换类型
      if (b instanceof CombinedCrafter || b instanceof CombinedDrill
          || b instanceof CombinedGenerator || b instanceof CombinedLaunchPad
          || b instanceof CombinedRegenProjector || b instanceof CombinedOverdriveProjector
          || b instanceof CombinedMendProjector || b instanceof CombinedForceProjector
          || b instanceof CombinedStorageBlock)
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
        else if (b instanceof BurstDrill)
          cd.mode = CombinedDrill.Mode.burst;
        else
          cd.mode = CombinedDrill.Mode.drill;
        combo = cd;
      } else if (isGenerator) {
        CombinedGenerator cg = createCombo(b, CombinedGenerator.class);
        if (b instanceof ImpactReactor)
          cg.mode = CombinedGenerator.Mode.impact;
        else if (b instanceof NuclearReactor)
          cg.mode = CombinedGenerator.Mode.nuclear;
        else if (b instanceof HeaterGenerator)
          cg.mode = CombinedGenerator.Mode.heater;
        else
          cg.mode = CombinedGenerator.Mode.consume;
        combo = cg;
      } else if (isLaunchPad) {
        combo = createCombo(b, CombinedLaunchPad.class);
      } else if (isRegen) {
        combo = createCombo(b, CombinedRegenProjector.class);
      } else if (isOverdrive) {
        combo = createCombo(b, CombinedOverdriveProjector.class);
      } else if (isMend) {
        combo = createCombo(b, CombinedMendProjector.class);
      } else if (isForce) {
        combo = createCombo(b, CombinedForceProjector.class);
      } else { // StorageBlock
        combo = createCombo(b, CombinedStorageBlock.class);
      }

      copyFields(b, combo);
      combo.init();
      combo.postInit();
      combo.loadIcon();
      postInit(combo);
    }
  }

  boolean hasExtraFields(Class<?> clazz, Class<?> baseClass) {
    Set<String> baseFields = new HashSet<>();
    Class<?> c = baseClass;
    while (c != null && c != Object.class) {
      for (Field f : c.getDeclaredFields()) {
        baseFields.add(f.getName());
      }
      c = c.getSuperclass();
    }

    c = clazz;
    while (c != null && c != Object.class && c != baseClass) {
      for (Field f : c.getDeclaredFields()) {
        int mod = f.getModifiers();
        if (Modifier.isStatic(mod) || Modifier.isFinal(mod) || f.isSynthetic())
          continue;

        String n = f.getName();
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
