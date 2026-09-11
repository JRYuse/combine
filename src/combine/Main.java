package combine;

import arc.Events;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Log;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import java.util.IdentityHashMap;
import mindustry.Vars;
import mindustry.content.TechTree;
import mindustry.content.TechTree.TechNode;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.BlockBuildBeginEvent;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.SaveLoadEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.EventType.SchematicCreateEvent;
import mindustry.game.EventType.TileChangeEvent;
import mindustry.game.Schematic;
import mindustry.mod.Mod;
import mindustry.mod.Mods.LoadedMod;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock;
import mindustry.world.blocks.campaign.LaunchPad;
import mindustry.world.blocks.defense.Door;
import mindustry.world.blocks.defense.ForceProjector;
import mindustry.world.blocks.defense.MendProjector;
import mindustry.world.blocks.defense.OverdriveProjector;
import mindustry.world.blocks.defense.RegenProjector;
import mindustry.world.blocks.defense.turrets.ContinuousLiquidTurret;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.LaserTurret;
import mindustry.world.blocks.defense.turrets.LiquidTurret;
import mindustry.world.blocks.defense.turrets.PowerTurret;
import mindustry.world.blocks.heat.HeatProducer;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.power.ConsumeGenerator;
import mindustry.world.blocks.power.HeaterGenerator;
import mindustry.world.blocks.power.ImpactReactor;
import mindustry.world.blocks.power.NuclearReactor;
import mindustry.world.blocks.production.AttributeCrafter;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.BurstDrill;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.HeatCrafter;
import mindustry.world.blocks.production.Incinerator;
import mindustry.world.blocks.production.Pump;
import mindustry.world.blocks.production.SolidPump;
import mindustry.world.blocks.defense.Wall;
import mindustry.world.blocks.production.Separator;
import mindustry.world.blocks.production.WallCrafter;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.meta.BuildVisibility;

import static combine.BlockCloner.*;

public class Main extends Mod {
  public static String fileName = "whitelist.json";
  static Seq<String> list = new Seq<>();

  static JsonReader reader = new JsonReader();
  static JsonValue json;

  
  static final IdentityHashMap<Schematic, Schematic> virtualCopies = new IdentityHashMap<>();

  @Override
  public void init() {
    
    
    
    Events.on(BlockBuildBeginEvent.class, e -> {
      if (e.breaking)
        return; 
      if (e.tile.build instanceof ConstructBlock.ConstructBuild cons) {
        Block combo = originalToCombo.get(cons.current);
        if (combo != null) {
          
          cons.current = combo;
        }
      }
    });

    
    
    
    Events.on(TileChangeEvent.class, e -> {
      if (Vars.state.isEditor() || Vars.world.isGenerating())
        return;
      Block combo = originalToCombo.get(e.tile.block());
      if (combo != null && e.tile.build != null && !e.tile.build.dead) {
        e.tile.setBlock(combo, e.tile.team(), e.tile.build.rotation);
      }
    });

    
    Events.on(WorldLoadEvent.class, e -> syncComboUnlocks());
    Events.on(SaveLoadEvent.class, e -> syncComboUnlocks());

    
    
    Events.on(mindustry.game.EventType.Trigger.class, t -> {
      if (t == mindustry.game.EventType.Trigger.update && !Vars.state.isMenu())
        syncVirtualCopyEdits();
    });

    Events.on(ClientLoadEvent.class, e -> {
      for (var entry : originalToCombo) {
        postInit(entry.value);
      }
      getWhiteList();
      processModBlocks();
      rebuildTechTree();

      
      convertSchematicLibrary();
      
      Events.on(SchematicCreateEvent.class, ev -> convertSchematicInPlace(ev.schematic));

      syncComboUnlocks();
      for (var entry : originalToCombo) {
        Block orig = entry.key;
        orig.hideDatabase = true;
        orig.buildVisibility = BuildVisibility.hidden;
        hideNode(orig);
      }
    });
  }

  




  void syncComboUnlocks() {
    for (var entry : originalToCombo) {
      Block orig = entry.key;
      Block combo = entry.value;
      if (orig.unlocked() && !combo.unlocked())
        combo.quietUnlock();
    }
  }

  
  void syncVirtualCopyEdits() {
    if (virtualCopies.isEmpty())
      return;
    for (var e : virtualCopies.entrySet()) {
      Schematic copy = e.getKey();
      Schematic orig = e.getValue();
      boolean changed = false;
      if (!copy.labels.equals(orig.labels)) {
        orig.labels.set(copy.labels);
        changed = true;
      }
      String cn = copy.tags.get("name");
      if (cn != null && !cn.equals(orig.tags.get("name"))) {
        orig.tags.put("name", cn);
        changed = true;
      }
      if (changed && orig.file != null) {
        try {
          mindustry.game.Schematics.write(orig, orig.file);
        } catch (Throwable t) {
          Log.err(t);
        }
      }
    }
  }

  
  void convertSchematicInPlace(Schematic s) {
    s.tiles.each(st -> {
      Block combo = originalToCombo.get(st.block);
      if (combo != null)
        st.block = combo;
    });
  }

  






  void convertSchematicLibrary() {
    virtualCopies.clear();
    Seq<Schematic> toHide = new Seq<>();
    for (Schematic s : Vars.schematics.all()) {
      if ("true".equals(s.tags.get("comboCopy"))) {
        
        if (s.file != null)
          toHide.add(s);
        continue;
      }
      boolean hasOrig = false;
      for (var st : s.tiles) {
        if (originalToCombo.containsKey(st.block)) {
          hasOrig = true;
          break;
        }
      }
      if (!hasOrig)
        continue;

      Schematic copy = makeComboCopy(s);
      copy.file = null; 
      Vars.schematics.all().add(copy);
      virtualCopies.put(copy, s); 
      toHide.add(s);
    }
    for (Schematic s : toHide) {
      Vars.schematics.all().remove(s); 
    }
    Vars.schematics.all().sort();
  }

  Schematic makeComboCopy(Schematic s) {
    Seq<Schematic.Stile> tiles = new Seq<>(s.tiles.size);
    for (var st : s.tiles) {
      Block combo = originalToCombo.get(st.block);
      tiles.add(new Schematic.Stile(combo != null ? combo : st.block, st.x, st.y, st.config, st.rotation));
    }
    StringMap tags = new StringMap();
    tags.putAll(s.tags); 
    tags.put("comboCopy", "true");
    Schematic copy = new Schematic(tiles, tags, s.width, s.height);
    copy.labels.addAll(s.labels); 
    return copy;
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

  

  static boolean isExact(Block b, Class<? extends Block> target) {
    Class<?> cls = b.getClass();
    return cls == target || (cls.isAnonymousClass() && cls.getSuperclass() == target);
  }

  
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
      if (isExact(b, Incinerator.class))
        continue;

      boolean isFactory = isExact(b, GenericCrafter.class);
      boolean isHeatCrafter = isExact(b, HeatCrafter.class);
      boolean isHeatProducer = isExact(b, HeatProducer.class);
      boolean isSeparator = isExact(b, Separator.class);
      boolean isAttribute = isExact(b, AttributeCrafter.class);
      boolean isDrill = isExact(b, Drill.class) || isExact(b, BeamDrill.class)
          || isExact(b, BurstDrill.class);
      boolean isGenerator = isExact(b, ConsumeGenerator.class)
          || isExact(b, ImpactReactor.class)
          || isExact(b, NuclearReactor.class) || isExact(b, HeaterGenerator.class);
      boolean isLaunchPad = isExact(b, LaunchPad.class);
      boolean isRegen = isExact(b, RegenProjector.class);
      boolean isOverdrive = isExact(b, OverdriveProjector.class);
      boolean isMend = isExact(b, MendProjector.class);
      boolean isForce = isExact(b, ForceProjector.class);
      
      boolean isStorage = isExact(b, StorageBlock.class);
      
      boolean isLogic = isExact(b, LogicBlock.class);
      boolean isContLiquidTurret = isExact(b, ContinuousLiquidTurret.class);
      boolean isLiquidTurret = isExact(b, LiquidTurret.class);
      boolean isItemTurret = isExact(b, ItemTurret.class);
      boolean isPowerTurret = isExact(b, PowerTurret.class);
      boolean isLaserTurret = isExact(b, LaserTurret.class);
      boolean isPump = isExact(b, Pump.class) && !isExact(b, SolidPump.class);
      boolean isSolidPump = isExact(b, SolidPump.class);
      boolean isWallCrafter = isExact(b, WallCrafter.class);
      boolean isWall = isExact(b, Wall.class) || isExact(b, Door.class);

      if (!isFactory && !isHeatCrafter && !isHeatProducer && !isSeparator && !isAttribute
          && !isDrill && !isGenerator && !isLaunchPad
          && !isRegen && !isOverdrive && !isMend && !isForce && !isStorage
          && !isLogic && !isContLiquidTurret && !isLiquidTurret && !isItemTurret
          && !isPowerTurret && !isLaserTurret
          && !isPump && !isSolidPump && !isWallCrafter && !isWall)
        continue;

      
      if (isExact(b, CombinedCrafter.class) || isExact(b, CombinedDrill.class)
          || isExact(b, CombinedGenerator.class) || isExact(b, CombinedLaunchPad.class)
          || isExact(b, CombinedRegenProjector.class) || isExact(b, CombinedOverdriveProjector.class)
          || isExact(b, CombinedMendProjector.class) || isExact(b, CombinedForceProjector.class)
          || isExact(b, CombinedStorageBlock.class) || isExact(b, CombinedLogicProcessor.class)
          || isExact(b, CombinedContinuousLiquidTurret.class) || isExact(b, CombinedLiquidTurret.class)
          || isExact(b, CombinedItemTurret.class) || isExact(b, CombinedTurret.class)
          || isExact(b, CombinedPump.class)
          || isExact(b, CombinedWallCrafter.class)
          || isExact(b, LinkWall.class))
        continue;

      Block combo;
      if (isFactory || isHeatCrafter || isHeatProducer || isSeparator || isAttribute) {
        CombinedCrafter cc = createCombo(b, CombinedCrafter.class);
        if (isExact(b, HeatProducer.class))
          cc.mode = CombinedCrafter.Mode.heatproducer;
        else if (isExact(b, HeatCrafter.class))
          cc.mode = CombinedCrafter.Mode.heatcrafter;
        else if (isAttribute)
          cc.mode = CombinedCrafter.Mode.attribute;
        else if (isSeparator)
          cc.mode = CombinedCrafter.Mode.separator;
        else
          cc.mode = CombinedCrafter.Mode.generic;
        combo = cc;
      } else if (isDrill) {
        CombinedDrill cd = createCombo(b, CombinedDrill.class);
        if (isExact(b, BeamDrill.class))
          cd.mode = CombinedDrill.Mode.beam;
        else if (isExact(b, BurstDrill.class))
          cd.mode = CombinedDrill.Mode.burst;
        else
          cd.mode = CombinedDrill.Mode.drill;
        combo = cd;
      } else if (isGenerator) {
        CombinedGenerator cg = createCombo(b, CombinedGenerator.class);
        if (isExact(b, ImpactReactor.class))
          cg.mode = CombinedGenerator.Mode.impact;
        else if (isExact(b, NuclearReactor.class))
          cg.mode = CombinedGenerator.Mode.nuclear;
        else if (isExact(b, HeaterGenerator.class))
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
      } else if (isStorage) {
        combo = createCombo(b, CombinedStorageBlock.class);
      } else if (isLogic) {
        combo = createCombo(b, CombinedLogicProcessor.class);
      } else if (isContLiquidTurret) {
        combo = createCombo(b, CombinedContinuousLiquidTurret.class);
      } else if (isLiquidTurret) {
        combo = createCombo(b, CombinedLiquidTurret.class);
      } else if (isItemTurret) {
        combo = createCombo(b, CombinedItemTurret.class);
      } else if (isPowerTurret || isLaserTurret) {
        
        CombinedTurret ct = createCombo(b, CombinedTurret.class);
        ct.mode = isLaserTurret ? CombinedTurret.Mode.laser : CombinedTurret.Mode.power;
        combo = ct;
      } else if (isPump) {
        combo = createCombo(b, CombinedPump.class);
      } else if (isSolidPump) {
        combo = createCombo(b, CombinedSolidPump.class);
      } else if (isWallCrafter) {

        combo = createCombo(b, CombinedWallCrafter.class);
      } else if (isWall) {
        LinkWall lw = createCombo(b, LinkWall.class);
        lw.mode = isExact(b, Door.class) ? LinkWall.Mode.door : LinkWall.Mode.wall;
        combo = lw;
      } else {
        combo = createCombo(b, CombinedForceProjector.class);
      }

      copyFields(b, combo);
      combo.init();
      combo.postInit();
      combo.loadIcon();
      postInit(combo);
    }
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
