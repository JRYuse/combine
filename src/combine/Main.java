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
import mindustry.content.Items;
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
import mindustry.type.Category;

import static combine.BlockCloner.*;

public class Main extends Mod {
  public static String fileName = "whitelist.json";
  static Seq<String> list = new Seq<>();
  public static ComboConnector comboConnector;
  public static ComboNode comboNode;

  static JsonReader reader = new JsonReader();
  static JsonValue json;

  @Override
  public void init() {
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
        // 置脏即可：一次放置会触发多个事件，ComboNet 每帧最多重建一次（见 markDirty 注释）
        ComboNet.markDirty();
      }
    });

    // 读档窗口：WorldLoadBegin → 读档语义合并（去重）；结束前不做运行期相加
    Events.on(mindustry.game.EventType.WorldLoadBeginEvent.class, e -> ComboNet.beginWorldLoad());
    Events.on(WorldLoadEvent.class, e -> {
      // 联机入服时 rules 是刚按名字反查出来的（researched/bannedBlocks 可能装着被替换掉的原版实例），
      // 必须在任何 UI/建造校验读到它之前纠正，否则客户端建造菜单里组合建筑会全部消失。
      Replacer.remapStaleContent();
      ComboNet.rebuildLoading();
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

    // 多线程建造武器的加载时机（客户端 + 服务端）
    registerBuildWeaponLoad();

    // 联机内容自检：入服时两端互换方块名列表，逐 id 比对（顺序/id 不一致会直接报出来）
    ComboContentCheck.register();

    // 协作组合（机制本体在 CoopCombo 里）：给"继承原版类但写了新功能的方块"（isExact 过滤掉
    // 的那些子类 / JS 模组方块）用另一条路组合 —— 不替换方块、不动它们的 build 类，
    // 只把相邻同类机器的库存模块接在一起，因此它们自己的配方/配置/更新逻辑全部保留。
    // 注册顺序有意放在 ComboNet 之前：同一帧里先重算协作分组，再由 ComboNet 收口并池，
    // 这样"放下去"和"同池"发生在同一帧。
    CoopCombo.register();

    // 跨组合体网络（连接器/节点）：每帧最多重建一次，避免放一个连接器就重建 5~10 遍
    ComboNet.register();

    // 组合仓库并仓（机制本体在 CombinedStorageBlock 里）：没连核心时像其它组合建筑一样
    // 共用物品模块（容量相加），连到核心时整块并进核心给核心扩容（任意深度链式）
    CombinedStorageBlock.register();

    // 内容装配必须客户端与（专用）服务端都执行，而且发生在同一个引导阶段：
    // 专用服务端不会触发 ClientLoadEvent，之前只在客户端装配 —— 服务端不认识组合方块/
    // 连接器/节点（方块 id 与客户端不一致），联机的地图里这些建筑会被服务端当成未知 id
    // 直接变成空气，客户端进图自然什么都看不到。
    setupContent();
  }

  /**
   * 【建造武器 · 方法一】决定"给哪个单位挂几把建造武器"，并按原参数真正挂上。
   *
   * 核心机 = 核心机尺寸；建造单位按下面列出的数量（也就是它能并行造几格）。
   * 武器参数与原来内联在 Main 里的匿名类完全相同：mirror=false, x=0, y=2, speedMulti=1。
   * 幂等：重复调用、客户端/服务端事件都触发，都不会重复挂。
   */
  public static void addBuildWeapons() {
    for (var b : Vars.content.blocks()) {
      if (b instanceof CoreBlock c && c.unitType != null) {
        addBuildWeapons(c.unitType, c.size);
      }
    }
    addBuildWeapons(UnitTypes.poly, 2);
    addBuildWeapons(UnitTypes.mega, 3);
    addBuildWeapons(UnitTypes.quad, 4);
    addBuildWeapons(UnitTypes.oct, 5);
    addBuildWeapons(UnitTypes.nova, 1);
    addBuildWeapons(UnitTypes.pulsar, 2);
    addBuildWeapons(UnitTypes.quasar, 3);
  }

  /** 兼容旧调用名 */
  public static void addBuild(UnitType unit, int amount) {
    addBuildWeapons(unit, amount);
  }

  /**
   * 给指定单位挂 count 把建造武器（幂等：已挂够就跳过，不会叠加）。
   * 想改某个单位的并行数，改 {@link #addBuildWeapons()} 里的数字即可。
   */
  public static void addBuildWeapons(UnitType unit, int count) {
    if (unit == null || count <= 0)
      return;

    int existing = 0;
    for (Weapon weapon : unit.weapons) {
      if (weapon instanceof MultiBuildWeapon)
        existing++;
    }
    if (existing >= count)
      return;

    for (int i = existing; i < count; i++) {
      MultiBuildWeapon w = new MultiBuildWeapon();
      w.mirror = false;
      w.x = 0f;
      w.y = 2f;
      w.speedMulti = 1f;
      if (visuals()) // 专用服务端没有图集，贴图字段跳过即可，不影响建造逻辑
        w.load();
      unit.weapons.add(w);
    }
    // 已经存在的单位补挂座（新造单位的挂座由 Unit 自己按 weapons.size 补齐）
    Groups.unit.each(un -> un.type == unit, un -> un.setupWeapons(unit));
  }

  /**
   * 【建造武器 · 方法二】决定加载时机：客户端在 ClientLoadEvent、
   * 服务端在 ServerLoadEvent 各挂一次 —— 联机时服务端也在模拟单位，
   * 服务端必须同样持有建造武器，多线程建造才会真正生效。
   */
  void registerBuildWeaponLoad() {
    Events.on(ClientLoadEvent.class, e -> addBuildWeapons());
    Events.on(mindustry.game.EventType.ServerLoadEvent.class, e -> addBuildWeapons());
  }

  /** 客户端与（专用）服务端共用的内容装配。 */
  static boolean contentSetupDone = false;

  void setupContent() {
    if (contentSetupDone)
      return;
    contentSetupDone = true;
    try {
      // 包装所有存档版本读取器：继承原版 read()（免疫 R8 方法重命名），
      // 只在 readChunk/readLegacyShortChunk 注入缓冲，实体 IO 不对称不再崩图。
      // 注意判断顺序：Save11 -> LegacyRegion -> ShortChunk -> SaveVersion（子类优先）。
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
    } catch (Throwable t) {
      Log.err("[combine] failed to patch save versions", t);
    }

    try {
      getWhiteList();
      processModBlocks();
      processWalls();
      createLinkBlocks();
      for (var entry : Replacer.replaced) {
        postInit(entry.value);
      }
      // 启动日志汇总（逐类刷屏已在 BlockCloner 里合并成一行）
      BlockCloner.logFallbackSummary();
      // 协作组合：抓取"放大前"的基础容量（必须在任何世界加载之前）
      CoopCombo.captureBaseCaps();
    } catch (Throwable t) {
      Log.err("[combine] content setup failed", t);
    }
  }

  /** 客户端才有的贴图/图标环境（专用服务器 Core.atlas 为 null）。 */
  static boolean visuals() {
    return !Vars.headless && arc.Core.atlas != null;
  }

  void getWhiteList() {
    LoadedMod mod = Vars.mods.getMod("combine");
    Fi metaFile = null;
    if (mod.root.child(fileName).exists()) {
      metaFile = mod.root.child(fileName);
    }
    if (metaFile == null) {
      return;
    }
    json = reader.parse(metaFile);
    if (json == null) {
      return;
    }
    try {
      String[] array = json.asStringArray();
      list.set(array);
    } finally {
    }
  }

  void createLinkBlocks() {
    if (comboConnector != null)
      return;

    comboConnector = new ComboConnector("connection");
    comboConnector.requirements(Category.distribution, BuildVisibility.shown,
        mindustry.type.ItemStack.with(Items.copper, 40, Items.lead, 30));
    comboConnector.localizedName = "组合连接器";
    comboConnector.description = "连接两个组合体。连接器必须连续相邻地铺在两个组合体之间，才能共享物品、液体和电力。";
    comboConnector.health = 90;
    comboConnector.size = 1;
    comboConnector.alwaysUnlocked = true;
    comboConnector.init();
    comboConnector.postInit();
    if (visuals()) {
      comboConnector.load();
      comboConnector.loadIcon();
    }

    comboNode = new ComboNode("node");
    comboNode.requirements(Category.distribution, BuildVisibility.shown,
        mindustry.type.ItemStack.with(Items.copper, 80, Items.lead, 80, Items.silicon, 30));
    comboNode.localizedName = "组合节点";
    comboNode.description = "像电力节点一样在范围内连接组合体，共享物品、液体、电力和热量。";
    comboNode.health = 120;
    comboNode.size = 1;
    comboNode.alwaysUnlocked = true;
    comboNode.maxNodes = 3;
    comboNode.laserRange = 6f;
    comboNode.init();
    comboNode.postInit();
    if (visuals()) {
      comboNode.load();
      comboNode.loadIcon();
    }

    addTech(mindustry.content.Blocks.coreShard, comboConnector);
    addTech(comboConnector, comboNode);
  }

  void addTech(mindustry.world.Block parent, mindustry.world.Block child) {
    if (parent != null && parent.techNode != null) {
      new TechNode(parent.techNode, child, child.requirements);
    } else {
      child.alwaysUnlocked = true;
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
      // FIX[JS 力墙]: js（或 java）写的 ForceProjector **子类**，只要用到相位护盾这套参数
      // （phaseUseTime / itemConsumer / phaseShieldBoost —— 都是 ForceProjector 上的普通字段，
      //  BlockCloner 会原样拷到组合力墙上），也纳入组合力墙的替换范围。
      // 用反射读，避免依赖编译期版本有没有这些字段；不想要可以写进 assets/whitelist.json 黑名单。
      boolean isForce = isExact(b, ForceProjector.class);
      if (!isForce && b instanceof ForceProjector) {
        boolean usesPhase = fieldFloat(b, "phaseUseTime", 0f) > 0f
            || fieldFloat(b, "phaseShieldBoost", 0f) > 0f
            || ComboReflect.fieldValue(b, "itemConsumer") != null;
        isForce = usesPhase;
      }

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
      } else if (isReconstructor) {
        combo = createCombo(b, CombinedReconstructor.class);
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
        if (visuals())
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
        if (visuals())
          lw.loadIcon();
        if (isShield) {
          if (visuals())
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
