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
import mindustry.content.UnitTypes;
import mindustry.content.TechTree.TechNode;
import mindustry.ctype.Content;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.BlockBuildBeginEvent;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.SaveLoadEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Groups;
import mindustry.game.EventType.TileChangeEvent;
import mindustry.game.Schematic;
import mindustry.mod.Mod;
import mindustry.mod.Mods.LoadedMod;
import mindustry.type.ItemStack;
import mindustry.type.UnitType;
import mindustry.type.Weapon;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock;
import mindustry.world.blocks.campaign.LaunchPad;
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
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.units.Reconstructor;
import mindustry.world.meta.BuildVisibility;

import static combine.BlockCloner.*;

public class Main extends Mod {
  public static String fileName = "whitelist.json";
  static Seq<String> list = new Seq<>();

  static JsonReader reader = new JsonReader();
  static JsonValue json;

  @Override
  public void init() {
    Settings.load();
    // 施工替换：残留的旧实例引用（其他模组静态字段等）→ 组合实例。
    Events.on(BlockBuildBeginEvent.class, e -> {
      if (e.breaking)
        return;
      if (e.tile.build instanceof ConstructBlock.ConstructBuild cons) {
        Block combo = Replacer.replaced.get(cons.current);
        if (combo != null)
          cons.current = combo;
      }
    });

    Events.on(TileChangeEvent.class, e -> {
      if (Vars.state.isEditor() || Vars.world.isGenerating())
        return;
      Block combo = Replacer.replaced.get(e.tile.block());
      if (combo != null && e.tile.build != null && !e.tile.build.dead) {
        e.tile.setBlock(combo, e.tile.team(), e.tile.build.rotation);
      }
    });

    Events.on(mindustry.game.EventType.UnlockEvent.class, e -> {
      if (e.content == null)
        return;
      for (arc.struct.ObjectMap.Entry<Block, Block> en : Replacer.replaced.entries()) {
        if (en.value == e.content) {
          en.key.unlocked();
          return;
        }
      }

    });

    Events.on(ClientLoadEvent.class, e -> {
      // 包装所有存档版本读取器：继承原版 read()（免疫 R8 方法重命名），
      // 只在 readChunk/readLegacyShortChunk 注入缓冲，实体 IO 不对称不再崩图。
      // 注意判断顺序：Save11 -> LegacyRegion -> ShortChunk -> SaveVersion（子类优先）。
      try {
        for (mindustry.io.SaveVersion v : new arc.struct.Seq<>(mindustry.io.SaveIO.versionArray)) {
          mindustry.io.SaveVersion w;
          if (v instanceof mindustry.io.versions.Save11) {
            w = new SafeW11();
          } else if (v instanceof mindustry.io.versions.LegacyRegionSaveVersion) {
            w = new SafeWLegacy(v.version);
          } else if (v instanceof mindustry.io.versions.ShortChunkSaveVersion) {
            w = new SafeWShort(v.version);
          } else if (v instanceof mindustry.io.SaveVersion) {
            w = new SafeWVer(v.version);
          } else {
            continue;
          }
          mindustry.io.SaveIO.versions.put(v.version, w);
        }
        Log.info("[combine] save versions patched (@)", mindustry.io.SaveIO.versions.size);
      } catch (Throwable t) {
        Log.err("[combine] failed to patch save versions", t);
      }

      getWhiteList();
      Loads.load();
      processModBlocks();
      processWalls();
      for (var entry : Replacer.replaced) {
        postInit(entry.value);
      }
      for (var b : Vars.content.blocks()) {
        if (b instanceof CoreBlock c) {
          UnitType u = c.unitType;
            Weapon w = new TestMultiBuildWeapon() {
              {
                mirror = false;
                x = 0f;
                y = 2f;
                speedMulti = 1f;
                maxBuild = c.size;
              }
            };
            w.load();
            u.weapons.add(w);
          Groups.unit.each(un -> un.type == u, un -> un.setupWeapons(u));

        }
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

  /** 只匹配目标类本身，不匹配任何子类 */

  static boolean isExact(Block b, Class<? extends Block> target) {
    Class<?> cls = b.getClass();
    return cls == target || (cls.isAnonymousClass() && cls.getSuperclass() == target);
  }

  /** 反射读字段, 兼容 159.7/160.1 字段差异 (X36 缺 heatConsumeRate 等), 缺字段回落默认值 */
  static float fieldFloat(Block b, String name, float def) {
    try {
      java.lang.reflect.Field f = b.getClass().getField(name);
      return f.getFloat(b);
    } catch (Throwable th) {
      return def;
    }
  }

  static <T> T fieldObj(Block b, String name, T def) {
    try {
      java.lang.reflect.Field f = b.getClass().getField(name);
      return (T) f.get(b);
    } catch (Throwable th) {
      return def;
    }
  }

  void processModBlocks() {
    // 快照遍历：createCombo 会原地修改注册表（set+pop），不可直接遍历
    Seq<Content> snapshot = new Seq<>(Vars.content.getBy(ContentType.block));
    for (var c : snapshot) {
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
      // CoreBlock 是 StorageBlock 的具名子类，精确匹配下自动被排除，无需额外判断
      boolean isStorage = isExact(b, StorageBlock.class);
      // 可选：接上之前一直闲置的 CombinedPump / CombinedWallCrafter
      boolean isLogic = isExact(b, LogicBlock.class);
      boolean isContLiquidTurret = isExact(b, ContinuousLiquidTurret.class);
      boolean isLiquidTurret = isExact(b, LiquidTurret.class);
      boolean isItemTurret = isExact(b, ItemTurret.class);
      boolean isPowerTurret = isExact(b, PowerTurret.class);
      boolean isLaserTurret = isExact(b, LaserTurret.class);
      boolean isPump = isExact(b, Pump.class) && !isExact(b, SolidPump.class);
      boolean isSolidPump = isExact(b, SolidPump.class);
      boolean isWallCrafter = isExact(b, WallCrafter.class);
      boolean isUnitFactory = isExact(b, UnitFactory.class);
      boolean isReconstructor = isExact(b, Reconstructor.class);

      if (!isFactory && !isHeatCrafter && !isHeatProducer && !isSeparator && !isAttribute
          && !isDrill && !isGenerator && !isLaunchPad
          && !isRegen && !isOverdrive && !isMend && !isForce && !isStorage
          && !isLogic && !isContLiquidTurret && !isLiquidTurret && !isItemTurret
          && !isPowerTurret && !isLaserTurret
          && !isPump && !isSolidPump && !isWallCrafter
          && !isUnitFactory && !isReconstructor)
        continue;

      // 防止重复处理已转换类型
      if (b instanceof CombinedCrafter || b instanceof CombinedDrill
          || b instanceof CombinedGenerator || b instanceof CombinedLaunchPad
          || b instanceof CombinedRegenProjector || b instanceof CombinedOverdriveProjector
          || b instanceof CombinedMendProjector || b instanceof CombinedForceProjector
          || b instanceof CombinedStorageBlock || b instanceof CombinedLogicProcessor
          || b instanceof CombinedContinuousLiquidTurret || b instanceof CombinedLiquidTurret
          || b instanceof CombinedItemTurret || b instanceof CombinedTurret
          || b instanceof CombinedPump
          || b instanceof CombinedWallCrafter
          || b instanceof CombinedUnitFactory
          || b instanceof CombinedReconstructor)
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
        // FIX[消耗变快]: copyFields 只拷同名字段，而组合类的模式字段带
        // nuclearXxx/impactXxx/heaterXxx 前缀——原版自定义参数全部丢失、
        // 退回默认调校，导致单体消耗速度变快。这里按类型显式映射。
        if (isExact(b, ImpactReactor.class)) {
          cg.mode = CombinedGenerator.Mode.impact;
          ImpactReactor ir = (ImpactReactor) b;
          cg.impactWarmupSpeed = ir.warmupSpeed;
          cg.impactItemDuration = ir.itemDuration;
        } else if (isExact(b, NuclearReactor.class)) {
          cg.mode = CombinedGenerator.Mode.nuclear;
          NuclearReactor nr = (NuclearReactor) b;
          // FIX[X36]: 反射读取——159.7 与 160.1 字段集不同 (heatConsumeRate 为 160.1 新增),
          // 缺字段时回落到 CombinedGenerator 的默认值, 直接访问会 NoSuchFieldError 崩初始化
          cg.nuclearHeating = fieldFloat(nr, "heating", cg.nuclearHeating);
          cg.nuclearHeatOutput = fieldFloat(nr, "heatOutput", cg.nuclearHeatOutput);
          cg.nuclearHeatWarmupRate = fieldFloat(nr, "heatWarmupRate", cg.nuclearHeatWarmupRate);
          cg.nuclearHeatConsumeRate = fieldFloat(nr, "heatConsumeRate", cg.nuclearHeatConsumeRate);
          cg.nuclearAmbientCooldown = fieldFloat(nr, "ambientCooldownTime", cg.nuclearAmbientCooldown);
          cg.nuclearSmokeThreshold = fieldFloat(nr, "smokeThreshold", cg.nuclearSmokeThreshold);
          cg.nuclearFlashThreshold = fieldFloat(nr, "flashThreshold", cg.nuclearFlashThreshold);
          cg.nuclearCoolantPower = fieldFloat(nr, "coolantPower", cg.nuclearCoolantPower);
          cg.nuclearFuelItem = fieldObj(nr, "fuelItem", cg.nuclearFuelItem);
          cg.nuclearLightColor = fieldObj(nr, "lightColor", cg.nuclearLightColor);
          cg.nuclearCoolColor = fieldObj(nr, "coolColor", cg.nuclearCoolColor);
          cg.nuclearHotColor = fieldObj(nr, "hotColor", cg.nuclearHotColor);
        } else if (isExact(b, HeaterGenerator.class)) {
          cg.mode = CombinedGenerator.Mode.heater;
          HeaterGenerator hg = (HeaterGenerator) b;
          cg.heaterHeatOutput = hg.heatOutput;
          cg.heaterWarmupRate = hg.warmupRate;
        } else {
          cg.mode = CombinedGenerator.Mode.consume;
        }
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
        // PowerTurret / LaserTurret 合并为一个组合类的两个模式（LaserTurret extends PowerTurret）
        CombinedTurret ct = createCombo(b, CombinedTurret.class);
        ct.mode = isLaserTurret ? CombinedTurret.Mode.laser : CombinedTurret.Mode.power;
        combo = ct;
      } else if (isUnitFactory) {
        combo = createCombo(b, CombinedUnitFactory.class);
        Log.info("[combine] unit factory combined: @", b.name);
      } else if (isReconstructor) {
        combo = createCombo(b, CombinedReconstructor.class);
        Log.info("[combine] reconstructor combined: @", b.name);
      } else if (isPump) {
        combo = createCombo(b, CombinedPump.class);
      } else if (isSolidPump) {
        combo = createCombo(b, CombinedSolidPump.class);
      } else if (isWallCrafter) {
        // 可选分支：不需要就删除 isWallCrafter 相关三处（标志、守卫、此处）
        combo = createCombo(b, CombinedWallCrafter.class);
      } else {
        combo = createCombo(b, CombinedForceProjector.class);
      }

      try {
        copyFields(b, combo);
        combo.init();
        combo.postInit();
        combo.loadIcon();
        postInit(combo);
      } catch (Throwable th) {
        // FIX: 单个方块初始化失败不再拖垮整个模组
        Log.err("[combine] processModBlocks failed for " + b.name, th);
        continue;
      }
    }
  }

  /**
   * 为每个原版 Wall 生成 LinkWall 克隆体（机制与方块组合一致：走 createCombo，
   * 自动获得蓝图转换、放置接管、解锁同步、科技树镜像和原版隐藏）。
   * copyFields 会把原版的 update=false 拷过来，必须重置为 true。
   */
  /**
   * 墙与门都替换为 LinkWall（墙模式/门模式）。
   * Door extends Wall，必须用 isExact 区分；mode 必须在 init() 之前设定
   * （init 按 mode 配置 solid/solidifes/consumesTap，不要再手动指定 solid）。
   */
  void processWalls() {
    // 快照遍历：createCombo 会原地修改注册表（set+pop），不可直接遍历
    Seq<Content> snapshot = new Seq<>(Vars.content.getBy(ContentType.block));
    for (var c : snapshot) {
      Block b = (Block) c;
      if (b instanceof LinkWall)
        continue;
      boolean isWall = isExact(b, Wall.class);
      boolean isDoor = isExact(b, mindustry.world.blocks.defense.Door.class);
      boolean isShield = isExact(b, mindustry.world.blocks.defense.ShieldWall.class); // 新增
      if (!isWall && !isDoor && !isShield) // 条件加一个
        continue;
      try {
        LinkWall lw = createCombo(b, LinkWall.class);
        copyFields(b, lw);
        lw.mode = isShield ? LinkWall.Mode.shield
            : (isDoor ? LinkWall.Mode.door : LinkWall.Mode.wall); // 三模式
        lw.update = true;
        lw.init();
        lw.postInit();
        lw.loadIcon();
        if (isShield) {
          lw.glowRegion = arc.Core.atlas.find(b.name + "-glow");
          lw.stats = new mindustry.world.meta.Stats();
          lw.setStats();
          lw.setBars();
        }
        postInit(lw);
      } catch (Exception ex) {
        Log.err("processWalls: failed for " + b.name, ex);
      }
    }
  }
}
