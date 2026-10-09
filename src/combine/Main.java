package combine;

import combine.coop.CoopCombo;
import combine.defense.CombinedForceProjector;
import combine.defense.CombinedMendProjector;
import combine.defense.CombinedOverdriveProjector;
import combine.defense.CombinedRegenProjector;
import combine.defense.LinkWall;
import combine.logic.CombinedLogicProcessor;
import combine.net.ComboConnector;
import combine.net.ComboNet;
import combine.net.ComboNode;
import combine.production.CombinedCrafter;
import combine.production.CombinedDrill;
import combine.production.CombinedFracker;
import combine.production.CombinedGenerator;
import combine.production.CombinedPump;
import combine.production.CombinedSolidPump;
import combine.production.CombinedWallCrafter;
import combine.production.CombinedVariableReactor;
import combine.production.SuperCombineFactory;
import combine.production.FactoryCombiner;
import combine.saves.SafeW11;
import combine.saves.SafeWLegacy;
import combine.saves.SafeWShort;
import combine.saves.SafeWVer;
import combine.storage.CombinedStorageBlock;
import combine.storage.LiquidUnloader;
import combine.turret.CombinedContinuousLiquidTurret;
import combine.turret.CombinedItemTurret;
import combine.turret.CombinedLiquidTurret;
import combine.turret.CombinedTurret;
import combine.units.CombinedLandingPad;
import combine.units.CombinedLaunchPad;
import combine.units.CombinedReconstructor;
import combine.units.CombinedUnitFactory;
import combine.util.ComboReflect;
import arc.Events;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Log;
import arc.util.Time;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import java.util.IdentityHashMap;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.content.Planets;
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
import mindustry.type.Item;
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
import mindustry.world.blocks.power.VariableReactor;
import mindustry.world.blocks.production.Incinerator;
import mindustry.world.blocks.production.Fracker;
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
import static mindustry.Vars.tilesize;

public class Main extends Mod {
  public static String fileName = "whitelist.json";
  static Seq<String> list = new Seq<>();
  public static ComboConnector comboConnector;
  public static ComboNode comboNode;
  public static LiquidUnloader liquidUnloader;
  public static SuperCombineFactory superCombineFactory;

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
      // 世界真被改动了（造/拆方块）：读档的去重窗口到此为止
      ComboNet.markWorldModified();
      Block combo = Replacer.replaced.get(e.tile.block());
      if (combo != null && e.tile.build != null && !e.tile.build.dead) {
        e.tile.setBlock(combo, e.tile.team(), e.tile.build.rotation);
      }
      // 置脏即可：一次放置会触发多个事件，ComboNet 每帧最多重建一次（见 markDirty 注释）。
      // 这里**无条件**置脏：拆掉一台机器、放个传送带……都可能让"本地组合体/共享池"的分组变化，
      // 而 ComboNet 的全局拆池（同一份模块被多个组件共用时按容量拆开）必须有机会跑。
      // 【别无条件置脏】多线程建造刷一大片时，每一格都置脏 = 每帧重建整张网络（实测建造中
      // 4~5ms/tick、峰值 45ms，用户报的"多线程建造后帧率下降明显"）。现在交给
      // markTileChanged 判"这一格变化到底跟组合网络有没有关系"，无关的直接跳过。
      ComboNet.markTileChanged(e.tile);
    });

    // 读档窗口：WorldLoadBegin → 读档语义合并（去重）；结束前不做运行期相加
    Events.on(mindustry.game.EventType.WorldLoadBeginEvent.class, e -> {
      // 换图：上一张图的"组合体共享配置"不能带到新图（新档里读到本块会再装回来）
      combine.saves.ComboShareState.onWorldLoadBegin();
      combine.production.OreShadow.onWorldLoadBegin();
      ComboNet.beginWorldLoad();
    });
    // 【读**存档**只会发 SaveLoadEvent，不会发 WorldLoadBegin/WorldLoad】那两条是"读地图"才发的。
    // 读存档（战役区块 / 存档槽）走 SaveIO.load → SaveLoadEvent，于是"读档去重窗口"以前从来没开过：
    // 每台建筑手里那份"同一口池子的副本"被按运行期语义相加，一轮存读物品就翻好几倍
    // （用户报的"物品数量异常增长"，实测 14.7M → 32.6M → 69M）。
    // 这里在读档末尾把窗口打开 + 立刻按去重语义合并一次（之后头几帧也按去重算）。
    Events.on(SaveLoadEvent.class, e -> {
      // 【必须在 beginWorldLoad 之前】读档末尾补一次"没读到本块就清空"（见 ComboShareState）
      combine.saves.ComboShareState.onLoadFinished();
      combine.production.OreShadow.onLoadFinished();
      ComboNet.beginWorldLoad();
      ComboNet.rebuildLoading();
    });
    Events.on(WorldLoadEvent.class, e -> {
      combine.saves.ComboShareState.onLoadFinished();
      combine.production.OreShadow.onLoadFinished();
      // 联机入服时 rules 是刚按名字反查出来的（researched/bannedBlocks 可能装着被替换掉的原版实例），
      // 必须在任何 UI/建造校验读到它之前纠正，否则客户端建造菜单里组合建筑会全部消失。
      Replacer.remapStaleContent();
      Replacer.remapSectorInfos();
      Replacer.remapPlanetDefaults();
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

    // 跨组合体网络（连接器/节点）：每帧最多重建一次，避免放一个连接器就重建 5~10 遍
    ComboNet.register();

    // 组合传送带：手里选着同种传送带点在同种传送带上 → 叠一层提速（走原版 Call.tileTap/TapEvent，
    // 桌面点击与手机点按都覆盖；服务端权威 + 转发，见 combine.distribution.ConveyorOverlay）
    combine.distribution.ConveyorOverlay.register();

    // 【arc 输入兜底】只吞掉"UI 元素在触摸派发过程中被摘掉 → Element.notify 读 getScene() 为 null"
    // 那条 arc 已知 NPE（别的异常照旧抛），别让一次点击把整个存档崩回主菜单。详见 ComboInputGuard。
    combine.util.ComboInputGuard.register();

    // 组合墙分组：同样每帧最多重算一遍（放一格墙会触发 placed + 四周 proximity，
    // 原先每一遍都整组 BFS 一次 —— 多线程建造刷墙时就是"帧率下降十分明显"的大头）
    LinkWall.register();

    // "不组合"名单（设置界面里切换的）从 Core.settings 读回来
    CoopCombo.loadBlacklist();

    // 悬浮面板：点击非己方建筑时要不要弹（设置界面里的复选框，见 CoopPanel/ComboBlockList）
    combine.coop.CoopPanel.loadForeignSetting();

    // "只和玩家队友组合"总开关（设置界面里的复选框）
    combine.util.ComboTeams.load();

    // 协作组合（机制本体在 CoopCombo 里）：给"继承原版类但写了新功能的方块"（isExact 过滤掉
    // 的那些子类 / JS 模组方块）用另一条路组合 —— 不替换方块、不动它们的 build 类，
    // 只把相邻同类机器的库存模块接在一起，因此它们自己的配方/配置/更新逻辑全部保留。
    // 注册顺序有意放在 ComboNet 之后：ComboNet 每帧先收口网络（并在网络变化时叫醒本地组合），
    // 同一帧里 CoopCombo/组合仓库再按整张网络算容量与面板，"放下去"和"生效"还是同一帧。
    // （协作分组自己变了的时候，CoopCombo.rebuild 会同步调 ComboNet.rebuild，不靠这个顺序。）
    CoopCombo.register();

    // 【单位侧机制已经拆到 combineunit 模组】（组合巨兽、共享承伤/修复、共享火力、手动编组 UI）
    // 这里不再注册：那些机制连同镜像实体类都在 combineunit 仓库里维护
    // （MegaUnitEntity 固定占用自定义实体槽 250；combineunit 的 UnitComboDamage.register() 等
    // 由它自己的 Main 调）。本仓库只留"建筑侧"：组合单位工厂/升级厂/构造机/发射台/着陆台，
    // 它们造出单位后靠 combine.util.UnitComboBridge 反射叫一句 combineunit 的 tagProduced
    // （没装 combineunit 时静默跳过，工厂照常工作，只是造出来的单位不进组合）。

    // 组合体↔电网的对账（共用一份 PowerModule 的组合建筑在原版并网/拆网里会掉队，
    // 表现就是"读档/拆东西之后要动一下电网才来电"，见 combine.util.ComboPower）
    combine.util.ComboPower.register();

    // 设置里的"建筑组合开关"界面（客户端才有 UI，服务端自动跳过）
    combine.ui.ComboBlockList.register();

    // 超级组合炮台：快捷键/按钮 → 框选炮台 → 按数量生成对应边长的组合体（纯客户端交互）
    combine.turret.SuperTurretPlacer.register();
    combine.production.FactoryCombiner.register();
    // 矿物阴影：框选矿物地板 → 画一层阴影 → 阴影上放钻头会缩到 1x1
    combine.production.OreShadow.register();
    // 【必须】框选流程本体（输入 + 拖框那层高亮/边框 + 自动取消）的注册：
    // 漏了它的话 start() 仍会把输入处理器装上、也能框出阴影，但 Trigger.draw 那条没挂 →
    // **拖框时看不到任何框（没有填充也没有线条边框）**（用户 2026-10-09 报的）。
    combine.production.OreShadowPlacer.register();
    // 【用户要求 2026-09-28】不再改 input：入口是 SuperTurretPlacer 自己挂的"框选合体"悬浮按钮
    // （手机/桌面同一个，可拖动，见 SuperTurretPlacer#ensureButton），输入处理器保持原版。

    Events.on(ClientLoadEvent.class, e -> combine.ui.ComboBlockList.register());
    // 客户端在这之后才把蓝图库从磁盘读进来（assets.load(schematics)），再兜一次键；
    // 顺带把内置发射蓝图里的核心实例再对齐一遍（幂等）。
    Events.on(ClientLoadEvent.class, e -> {
      // 【必须在最前面】客户端的 assets.load(schematics) 跑在 Mod.init() 之前，
      // 那时组合连接器/节点还没建出来 —— 含它们的老蓝图会被当成未知方块丢掉。
      // 现在内容齐了，先重读一遍蓝图库，再做键/引用的对齐。
      Replacer.reloadSchematics();
      Replacer.remapBuiltinLoadouts();
      Replacer.remapLoadoutKeys();
      Replacer.remapSectorInfos();
      Replacer.remapPlanetDefaults();
      // 别的模组可能到这一步才动过方块配方（组装机的 PayloadStack 等），再兜一次
      Replacer.remapCapturedBlocks();
    });

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
  /** 矿物阴影用的 1x1 钻头版本装配过没有。 */
  static boolean shadowDrillsReady = false;

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
        } else if (v instanceof mindustry.io.versions.LegacySaveVersion
            || v instanceof mindustry.io.versions.LegacySaveVersion2) {
          // 【不能换】v1-v5（Save1..Save5，都继承 LegacySaveVersion / LegacySaveVersion2）
          // **各自覆写了 readMap / readEntities**，语义和"通用 LegacyRegion 包装器"不一样：
          //   · Save1/Save2/Save3 的 readEntities 是老式"跳过所有实体"（Save3 还自己读一遍
          //     队伍计划），Save1/Save2 连计划都没有；
          //   · Save4 的 readEntities 是 `readTeamBlocks + readWorldEntities(idMap)`，
          //     **不读 entity ID 映射段**（这个版本根本没写映射）；
          //   · Save1/Save2/Save3 的 readMap 还在 legacy chunk 里**混用 stream 和 chunkReads**。
          // 以前把它们统一换成 SafeWLegacy（= 通用 LegacyRegion）：readEntities 退回
          // "先读 entity 映射再读队伍计划"，于是 v4 的老地图一读就错位 ——
          // 用户报的"部分地图加载报 Unknown object type / Error reading region entities"。
          // 这些老版本本来也不需要"实体块容错"（它们的地图区是短块、模组建筑读法不同），
          // 直接保留原版读取器最安全。
          continue;
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

    // 组合建筑"模组专属字段"的自定义存档块：地图区里只写原版那份字节，
    // 关掉模组后原版读档时这些字段整块跳过，组合建筑干净地变回原版建筑
    // （详见 combine.saves.ComboSaved / ComboSaveState）
    combine.saves.ComboSaveState.register();
    // 组合体自己的"共享哪些部分"配置（按 pos 存在 ComboShare 里，见 ComboShareState）
    combine.saves.ComboShareState.register();
    // 组合传送带的"覆盖层数"（存档 + 联机入服世界流都带它，见 ConveyorLayerState）
    combine.distribution.ConveyorLayerState.register();

    try {
      getWhiteList();
      processModBlocks();
      // 组合传送带（原版/模组的 Conveyor/ArmoredConveyor/Duct/StackConveyor → 能"覆盖叠层提速"的组合版）
      processConveyors();
      processWalls();
      // 内容初始化期被"按引用抓走"的老方块实例（典型：组装机 UnitAssembler 配方里的
      // PayloadStack.item = 老钨墙/碳化墙）换回组合实例 —— 不换的话原版
      // UnitAssemblerBuild.acceptPayload 里 b.item == payload.content() 恒 false，
      // 组装机就不收建筑 payload（用户报的）
      Replacer.remapCapturedBlocks();
      // 造价表必须在 content.load() 之后现造（Items.* 是 load() 阶段才赋值的静态字段）
      initRequirementTables();
      createLinkBlocks();
      createSuperCombineFactory();
      createLiquidBlocks();
      // 跨距离传热用的探针（组合节点/连接器把热喂给"连线接进来的"需热建筑；和超级炮台开关无关）
      combine.util.ComboHeatProbe.create();
      createSuperTurrets();
      for (var entry : Replacer.replaced) {
        postInit(entry.value);
      }
      // 【放在 postInit 之后】组合传送带的描述是 postInit 从原版方块抄来的，这里再补一行玩法说明
      combine.distribution.ConveyorOverlay.applyHints();
      // 【矿物阴影】每种钻头一个"压成 1x1"的隐藏版本（阴影上摆钻头时由 CombinedDrill.onNewPlan 顶替）。
      // 【放在 postInit 之后】组合钻头的 localizedName / 图标是 postInit 里从原版方块补上的，
      // 早建的话 1x1 版本会抄到 null（点它会显示 "shadow-xxx" 这种内部名）。
      createShadowDrills();
      // 启动日志汇总（逐类刷屏已在 BlockCloner 里合并成一行）
      BlockCloner.logFallbackSummary();
      // 协作组合：抓取"放大前"的基础容量（必须在任何世界加载之前）
      CoopCombo.captureBaseCaps();
      // 蓝图库缓存兜底：有东西在装配前就 schematics.load() 过的话，键还停在旧核心实例上
      // （发射界面查不到内置蓝图 → getLoadouts().get(核心).first() 崩）
      Replacer.remapLoadoutKeys();
      // 发射界面拿的是 from.info.bestCoreType（Planet.load() 阶段按名字反查出来的旧核心实例）
      Replacer.remapSectorInfos();
      // 以及 from.planet.defaultCore（内容装配阶段写死的旧核心实例）——
      // Erekir 的 allowLaunchSchematics=false，发射时用的就是这个字段
      Replacer.remapPlanetDefaults();
      // 数据兜底：方块造价表 / 科技树节点里绝不能留 null 的 ItemStack（原版 PlacementFragment
      // 和 ResearchDialog.canSpend 都是无检查取值，一碰就崩）
      sanitizeContent();
      // 客户端：进主界面后再扫一遍（别的模组可能到这一步才动过树），并给科技树挂个守护
      Events.on(ClientLoadEvent.class, e -> {
        sanitizeContent();
        guardResearchDialog();
        refreshResearchTree();
      });
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
    if (mod == null) {
      // 模组未经过 mods.load() 的环境（测试 harness / 异常加载）下没有 whitelist.json 可读，
      // 直接跳过；否则下面 mod.root 会 NPE，并被 setupContent 的 catch 吞掉导致整个装配中止。
      return;
    }
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

  // ===== 组合连接器 / 组合节点 / 液体卸载器：双星球显示 + 分星球造价 =====
  // Serpulo 用铜/铅/硅；Erekir 没有这些资源，必须用铍/钨/氧化物，否则埃里克尔战役里造不出来。
  // Block.requirements 本身不分星球，只有一份 —— 用 WorldLoadEvent 按当前星球整体换表
  // （PlacementFragment / ConstructBlock 都是每帧现读 block.requirements，换表即生效）。
  //
  // 【不能写成 static final】v8 把 Items.copper 这类静态字段改成了在 content.load()
  // （Items.load()）里赋值，而模组主类的静态初始化发生在更早的 mods.load()：
  // 那时候 Items.* 全是 null，ItemStack.with(...) 造出来的每一项 item 都是 null。
  // 真机实测的后果（安卓崩溃堆栈就是这条链）：
  // 1) Block.requirements() 内部 Arrays.sort(i -> i.item.id) 直接 NPE ——
  // 方块剩下的 init()/postInit()/贴图全被跳过，建造菜单里是个残废方块；
  // 2) TechNode.setupRequirements() 读 requirements[i].item.name 又 NPE ——
  // 科技树节点根本建不出来，"装在两个科技树上"静默失败；
  // 3) 残留的 null 造价数据留在 block.requirements / TechNode 里，原版
  // PlacementFragment（建造菜单）和 ResearchDialog.canSpend（科技树）都是无检查取值：
  // ItemModule.has(stack.item) / finishedRequirements[i].amount → 一打开就崩。
  // 所以造价一律在 content.load() 之后（Mod.init）现造；顺带把 null 项丢掉，
  // 宁可少一项造价，也绝不让 null 进游戏数据。
  static ItemStack[] connReqSerpulo, connReqErekir;
  static ItemStack[] nodeReqSerpulo, nodeReqErekir;
  static ItemStack[] unloaderReqSerpulo, unloaderReqErekir;

  /** 造价表：参数是 (Item, 数量) 对。item 为 null（Items 还没 load）的项直接丢掉。 */
  static ItemStack[] req(Object... pairs) {
    Seq<ItemStack> out = new Seq<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      Object itemObj = pairs[i];
      int amount = (Integer) pairs[i + 1];
      if (!(itemObj instanceof Item item) || item == null) {
        Log.warn("[combine] 造价表里有 null 项（Items 还没 load？），已丢弃 amount=@", amount);
        continue;
      }
      out.add(new ItemStack(item, amount));
    }
    return out.toArray(ItemStack.class);
  }

  /** 造价表必须在 content.load() 之后（setupContent 里）建，幂等。 */
  static void initRequirementTables() {
    if (connReqSerpulo != null)
      return;
    connReqSerpulo = req(Items.copper, 40, Items.lead, 30);
    connReqErekir = req(Items.beryllium, 40, Items.tungsten, 20);
    nodeReqSerpulo = req(Items.copper, 80, Items.lead, 80, Items.silicon, 30);
    nodeReqErekir = req(Items.beryllium, 80, Items.tungsten, 40, Items.oxide, 20);
    // 液体卸载器：塞普罗用铜/铅/钢化玻璃（原版液体建筑的材料体系），埃里克尔用铍/钨
    unloaderReqSerpulo = req(Items.copper, 30, Items.lead, 25, Items.metaglass, 10);
    unloaderReqErekir = req(Items.beryllium, 30, Items.tungsten, 15);
    Log.info("[combine] 造价表: 塞普罗 连接器@项/节点@项/卸载器@项，埃里克尔 连接器@项/节点@项/卸载器@项",
        connReqSerpulo.length, nodeReqSerpulo.length, unloaderReqSerpulo.length,
        connReqErekir.length, nodeReqErekir.length, unloaderReqErekir.length);
  }

  /** 当前星球是埃里克尔就用埃里克尔造价，否则（塞普罗/自定义图）用塞普罗造价 */
  static void applyPlanetRequirements() {
    if (comboConnector == null || comboNode == null || connReqSerpulo == null)
      return;
    boolean erekir = Vars.state.getPlanet() == Planets.erekir;
    comboConnector.requirements = erekir ? connReqErekir : connReqSerpulo;
    comboNode.requirements = erekir ? nodeReqErekir : nodeReqSerpulo;
    if (liquidUnloader != null)
      liquidUnloader.requirements = erekir ? unloaderReqErekir : unloaderReqSerpulo;
  }

  void createLinkBlocks() {
    if (comboConnector != null)
      return;

    // 【防 NPE 崩溃】v8 里 Mod.init() 在 content.load() 全部跑完之后才调用（ClientLauncher：
    // createModContent -> content.init()/load() -> assets 加载完 ->
    // eachClass(Mod::init)），
    // 所以这里注册的方块永远吃不到游戏的自动 loadIcon —— 图标全靠下面的手动调用。
    // 进建造菜单列表的方块只要 uiIcon 为 null，PlacementFragment 重建时
    // new TextureRegionDrawable(null) 就直接崩游戏。
    // 因此：每个方块单独 try/catch，任何一步抛异常都必须在 finally 里把图标补上，
    // 绝不能留下"已注册 + 无图标"的方块。
    comboConnector = new ComboConnector("connection");
    try {
      comboConnector.requirements(Category.distribution, BuildVisibility.shown, connReqSerpulo);
      // 科技树挂在塞普罗（coreShard）上会把 shownPlanets 收成 {serpulo}（Planet.load 时
      // addPlanet 递归写入），埃里克尔就看不到了 —— 这里显式补回两个星球
      comboConnector.shownPlanets.add(Planets.serpulo);
      comboConnector.shownPlanets.add(Planets.erekir);
      comboConnector.localizedName = "组合连接器";
      comboConnector.description = "连接两个组合体。连接器必须连续相邻地铺在两个组合体之间，才能共享物品、液体和电力。";
      comboConnector.health = 90;
      comboConnector.size = 1;
      comboConnector.init();
      comboConnector.postInit();
    } catch (Throwable t) {
      Log.err("[combine] connection 装配失败（已兜底，不会带崩建造菜单）", t);
    } finally {
      ensureIcons(comboConnector);
    }

    comboNode = new ComboNode("node");
    try {
      comboNode.requirements(Category.distribution, BuildVisibility.shown, nodeReqSerpulo);
      comboNode.shownPlanets.add(Planets.serpulo);
      comboNode.shownPlanets.add(Planets.erekir);
      comboNode.localizedName = "组合节点";
      comboNode.description = "像电力节点一样在范围内连接组合体，共享物品、液体、电力和热量。";
      comboNode.health = 120;
      comboNode.size = 1;
      comboNode.maxNodes = 6;
      comboNode.laserRange = 18f;
      comboNode.init();
      comboNode.postInit();
    } catch (Throwable t) {
      Log.err("[combine] node 装配失败（已兜底，不会带崩建造菜单）", t);
    } finally {
      ensureIcons(comboNode);
    }

    boolean onTree = false;
    try {
      // 两棵科技树分别挂节点，研究材料分星球：塞普罗树用铜/铅/硅，埃里克尔树用铍/钨/氧化物。
      // （之前 addTech(child.requirements) 两棵树都给塞普罗材料 —— 埃里克尔根本凑不齐，
      // 节点等于废的；这是"放到两个科技树上"的正确挂法。）
      // 显式接住返回的节点引用：TechNode 构造会把 content.techNode 改写成最新一个，
      // 第二次 addTech(comboConnector, ...) 挂在哪棵树上完全取决于引用顺序，容易挂错。
      TechNode serpuloConn = new TechNode(mindustry.content.Blocks.coreShard.techNode, comboConnector,
          connReqSerpulo);
      TechNode erekirConn = new TechNode(mindustry.content.Blocks.coreBastion.techNode, comboConnector,
          connReqErekir);
      TechNode serpuloNode = new TechNode(serpuloConn, comboNode, nodeReqSerpulo);
      TechNode erekirNode = new TechNode(erekirConn, comboNode, nodeReqErekir);
      onTree = serpuloNode != null && erekirNode != null
          && TechTree.all.contains(serpuloNode) && TechTree.all.contains(erekirNode);

      // 分星球造价：每次世界加载（含战役切图/读档/联机进房）按当前星球换 requirements
      applyPlanetRequirements();
      Events.on(WorldLoadEvent.class, e -> {
        applyPlanetRequirements();
        sanitizeContent();
      });
    } catch (Throwable t) {
      // 科技树/事件注册失败不影响方块本体可用性（兜底为 alwaysUnlocked 的直接可用）
      Log.err("[combine] 科技树/事件注册失败", t);
    }
    // 上了科技树就以研究解锁（两棵树都能研究到）；节点没挂上才兜底成"直接可用"，
    // 免得玩家在战役里彻底造不出来。
    comboConnector.alwaysUnlocked = !onTree;
    comboNode.alwaysUnlocked = !onTree;
  }

  /**
   * 组合工厂（合体工厂）：框选多台工厂 → 合成一台 side×side（每格一台工厂）、物品/液体/电力/热量共享的方块
   * （入口是 HUD 浮标里的"工厂合体"子按钮，见 combine.production.FactoryCombiner）。
   *
   * <p>和其它"方块在 Mod.init() 里现造"的情况一样：v8 不会替这一步注册的方块调
   * init()/postInit()/loadIcon()，必须自己补，否则 buildType / 图标是空的。
   * 方块本身不显示在建造菜单里（buildVisibility=hidden + isVisible()=false），
   * 但放置流程要合法（isPlaceable() 单独放行）。
   */
  void createSuperCombineFactory() {
    if (SuperCombineFactory.bySide[SuperCombineFactory.MIN_SIDE] != null)
      return;
    // 每种边长一个方块（和超级组合炮台一样）：N 台工厂 → ceil(sqrt(N)) × ceil(sqrt(N))，
    // 每格一台工厂（缩到 1x1）。台数不够铺满的格子留空。
    for (int side = SuperCombineFactory.MIN_SIDE; side <= SuperCombineFactory.MAX_SIDE; side++) {
      SuperCombineFactory f = new SuperCombineFactory("super-combine-factory-" + side, side);
      try {
        f.localizedName = "组合工厂 " + side + "x" + side;
        f.description = "多台工厂合体成 " + side + "x" + side + " 的组合工厂：每格一台工厂（缩到 1x1），"
            + "物品/液体/电力/热量整台共享，可以继续往里加工厂，也能解体拆回每一台。"
            + "入口：屏幕上的浮标 →「工厂合体」→ 框选要合体的工厂。";
        f.health = 320 * side;
        // 【必须用 hidden】以前写成 requirements(Category, ItemStack.with())：那个重载会把
        // buildVisibility 覆盖成 shown，于是这个 0 造价的方块直接进了建造菜单（用户可见的 bug）。
        f.requirements(Category.production, BuildVisibility.hidden, new ItemStack[0]);
        // 【战役模式必须】本方块是 Mod.init() 里现造的隐藏方块，没进科技树、永远不会被研究 ——
        // 而原版虚影链路 control.input.useSchematic → Schematics.toPlans 会 removeAll 掉
        // `!block.unlockedNow()` 的计划，UnlockableContent.unlockedNow() 在战役里
        // （state.isCampaign() = rules.sector != null）只认 unlocked/alwaysUnlocked：
        // 少了这一行，战役图里框选完**虚影根本不会生成**、点下去什么也不发生
        // （用户报的"战役模式下工厂无法合体"）。沙盒/自定义图 isCampaign()=false，
        // unlockedNow() 恒真，所以只有战役会坏。超级组合炮台当年也是靠这一行才在战役里能用
        // （见 createSuperTurrets）。hidden 方块不进建造菜单/数据库，这一行没有别的副作用。
        f.alwaysUnlocked = true;
        f.init();
        f.postInit();
        // 血量/液体产物的信息条：原版是在 afterPatch 里建的，我们现造的方块要自己调一次
        f.setBars();
      } catch (Throwable t) {
        Log.err("[combine] 组合工厂 @x@ 装配失败（已兜底，不会带崩建造菜单）", side, side, t);
      } finally {
        ensureIcons(f);
      }
      SuperCombineFactory.bySide[side] = f;
    }
    superCombineFactory = SuperCombineFactory.bySide[SuperCombineFactory.MIN_SIDE];
    Log.info("[combine] 组合工厂：@ 种边长已装配（2x2 .. @x@）",
        SuperCombineFactory.MAX_SIDE - SuperCombineFactory.MIN_SIDE + 1, SuperCombineFactory.MAX_SIDE);
  }

  /**
   * 【矿物阴影】给每一种钻头现造一个"压成 1x1"的隐藏版本（{@code CombinedDrill} 克隆体）。
   *
   * <p>它本体只占 1 格（{@code size=1}），但 {@code mineSize} 记着原来的边长 —— 挖矿、判定矿石、
   * 激光束数量全按"原来那 size×size 每格都盖住了矿物"算；阴影区域里的这些 1x1 钻头共用一口
   * 物品 / 液体池（组合逻辑原样继承），所以通水给任意一台就能加速整站，物品也能从区域任意一边出去。
   *
   * <p>隐藏方块：不进建造菜单、不参与研究，玩家看到的还是原来的钻头（和合体工厂 / 合体炮台同一个思路）。
   * 方块名固定 {@code shadow-<原名>}，两端遍历顺序一致 → id 也一致，联机不会错位。
   */
  void createShadowDrills() {
    if (shadowDrillsReady)
      return;
    shadowDrillsReady = true;
    // 快照遍历：新方块会追加进注册表，不能直接遍历
    Seq<Content> snapshot = new Seq<>(Vars.content.getBy(ContentType.block));
    int made = 0;
    for (var c : snapshot) {
      if (!(c instanceof CombinedDrill cd))
        continue;
      if (cd.shadowShrunk || cd.shadowVariant != null || cd.size <= 1)
        continue;
      try {
        CombinedDrill sh = new CombinedDrill("shadow-" + cd.name);
        copyFields(cd, sh);
        sh.shadowShrunk = true;
        sh.shadowVariant = sh;
        sh.mineSize = cd.size;
        sh.size = 1;
        sh.localizedName = cd.localizedName == null ? cd.name : cd.localizedName;
        sh.description = "压成 1 格的钻头（只出现在矿物阴影上）：挖矿速度仍按原来 "
            + cd.size + "x" + cd.size + " 每格都盖住了矿物计算。";
        sh.buildVisibility = BuildVisibility.hidden;
        sh.requirements(Category.production, BuildVisibility.hidden, new ItemStack[0]);
        sh.init();
        // 【offset 必须是 1x1 自己的值（0）】以前为了让贴图落在"原来那片地基的中心"把它设成
        // 原钻头的半格偏移 —— 结果 Block.bounds()/hitbox 整体偏半格，原版放置判据里
        // "新方块要能罩住这一格原有的东西"那条直接失败，validPlace=false，
        // 表现就是**用户报的"阴影上摆钻头、点建造后钻头直接消失"**。
        // 1x1 的方块就该以自己那一格为中心（init() 已按 size=1 算好 offset/sizeOffset）。
        sh.postInit();
        try {
          sh.setBars();
        } catch (Throwable ignored) {
        }
        // 【贴图：抄原钻头的，别走 ensureIcons】ensureIcons 调的是 sh.load()，它按**自己的名字**
        // "shadow-机械钻头" 去图集里找贴图 —— 找不到就把 region/topRegion 全换成 error，
        // 真实客户端截图上就是"阴影上一块 error 方块"（实测踩过）。整套沿用原钻头加载好的贴图。
        CombinedDrill.copyVisuals(cd, sh);
        shadowIconFallback(cd, sh);
        cd.shadowVariant = sh;
        made++;
      } catch (Throwable t) {
        Log.err("[combine] 矿物阴影：@ 的 1x1 版本装配失败（这种钻头不参与阴影压格）", cd.name, t);
      }
    }
    Log.info("[combine] 矿物阴影：@ 种钻头有了压成 1x1 的版本", made);
  }

  /** 1x1 版本的图标兜底：抄不到原钻头那份就给 error，uiIcon/fullIcon/region 都不留 null。 */
  static void shadowIconFallback(Block src, Block dst) {
    try {
      Object error = (arc.Core.atlas == null) ? null : arc.Core.atlas.find("error");
      if (dst.fullIcon == null)
        dst.fullIcon = src.fullIcon != null ? src.fullIcon : (arc.graphics.g2d.TextureRegion) error;
      if (dst.uiIcon == null)
        dst.uiIcon = src.uiIcon != null ? src.uiIcon : dst.fullIcon;
      if (dst.region == null)
        dst.region = src.region != null ? src.region : dst.fullIcon;
    } catch (Throwable ignored) {
    }
  }

  void createLiquidBlocks() {
    if (liquidUnloader != null)
      return;

    liquidUnloader = new LiquidUnloader("liquid-unloader");
    try {
      liquidUnloader.requirements(Category.liquid, BuildVisibility.shown, unloaderReqSerpulo);
      // 与组合节点同款双星球显示（不挂科技树，shownPlanets 显式声明两个星球）
      liquidUnloader.shownPlanets.add(Planets.serpulo);
      liquidUnloader.shownPlanets.add(Planets.erekir);
      liquidUnloader.localizedName = "液体卸载器";
      liquidUnloader.description = "从相邻建筑中抽取选定的液体，再倾倒给下游。点选配置选择液体。";
      liquidUnloader.health = 80;
      liquidUnloader.size = 1;
      liquidUnloader.init();
      liquidUnloader.postInit();
    } catch (Throwable t) {
      Log.err("[combine] liquid-unloader 装配失败（已兜底，不会带崩建造菜单）", t);
    } finally {
      ensureIcons(liquidUnloader);
    }
    boolean onTree = false;
    try {
      // 同样两树分挂、研究材料分星球（塞普罗 铜/铅/钢化玻璃；埃里克尔 铍/钨）
      TechNode serpulo = new TechNode(mindustry.content.Blocks.coreShard.techNode, liquidUnloader,
          unloaderReqSerpulo);
      TechNode erekir = new TechNode(mindustry.content.Blocks.coreBastion.techNode, liquidUnloader,
          unloaderReqErekir);
      onTree = serpulo != null && erekir != null
          && TechTree.all.contains(serpulo) && TechTree.all.contains(erekir);
    } catch (Throwable t) {
      Log.err("[combine] liquid-unloader 科技树注册失败", t);
    }
    liquidUnloader.alwaysUnlocked = !onTree;
  }

  /**
   * 超级组合炮台：每种边长一个方块实例（2x2 ~ 6x6）。
   *
   * <p>
   * 这些方块不进科技树（也不给造价）：它们不是"造"出来的，而是用快捷键/建造菜单里的按钮
   * 框选一片炮台之后，按炮台数量自动生成对应边长的那一台（见 combine.turret.SuperTurretPlacer）。
   * 为了走原版那套放置流程（虚影、合法染色、建造队列、联机同步），buildVisibility 必须是
   * shown（hidden 的方块 Build.validPlace 直接拒绝），所以它们在建造菜单里也会出现一行
   * —— 点它等于"按下框选按钮"。
   */
  void createSuperTurrets() {
    // 【停用中】用户 2026-09-25：「超级组合炮台先停用，我后面补充描述」。
    // 开关在 combine.turret.SuperTurret.enabled；关着的时候一个方块都不注册（入口也不挂）。
    if (!combine.turret.SuperTurret.enabled) {
      Log.info("[combine] 超级组合炮台当前停用（SuperTurret.enabled=false）");
      return;
    }
    if (combine.turret.SuperTurret.bySide[combine.turret.SuperTurret.MIN_SIDE] != null)
      return;
    // 内部用的热量探针（不落地、不进建造菜单）：把整台的热量喂给每一格
    try {
      combine.turret.SuperTurret.heatProbe = new combine.turret.SuperTurret.HeatProbe("super-turret-heat");
      combine.turret.SuperTurret.heatProbe.init();
      combine.turret.SuperTurret.heatProbe.postInit();
      // 隐藏方块也要有非空图标，免得别的界面顺手画到它
      combine.turret.SuperTurret.HeatProbe hp = combine.turret.SuperTurret.heatProbe;
      if (visuals()) {
        arc.graphics.g2d.TextureRegion reg = arc.Core.atlas.find("heat-source", arc.Core.atlas.find("error"));
        hp.region = reg;
        hp.fullIcon = reg;
        hp.uiIcon = reg;
      }
    } catch (Throwable t) {
      Log.err("[combine] 超级组合炮台热量探针装配失败（热量会退回逐格相邻）", t);
    }
    for (int side = combine.turret.SuperTurret.MIN_SIDE; side <= combine.turret.SuperTurret.MAX_SIDE; side++) {
      combine.turret.SuperTurret st = new combine.turret.SuperTurret("super-turret-" + side, side);
      try {
        st.requirements(Category.turret, BuildVisibility.shown, new ItemStack[0]);
        st.localizedName = "超级组合炮台 " + side + "x" + side;
        st.description = "在建造菜单里点它（或按快捷键，默认 G）= 框选炮台："
            + "按框里的炮台数量生成对应边长的一台（2~4 台→2x2，5~9 台→3x3 …），"
            + "每格一台炮台、不够的格子留空；物品/液体/电力整台共用，各格各自开火、各自冷却。"
            + "虚影跟着鼠标走，左键确定位置，右键/Q/Esc 取消。";
        st.alwaysUnlocked = true;
        // 【不要往 shownPlanets 里加星球】原版 UnlockableContent.isOnPlanet() 是
        // "planet == null || shownPlanets.isEmpty() || shownPlanets.contains(planet)" ——
        // 一旦写进了 {塞普罗, 埃里克尔}，**别的星球（含模组星球）就会被过滤掉**。
        // 卡住的是放置那一步：Build.validPlaceIgnoreUnits 里的 type.environmentBuildable()
        // （原版 Block.environmentBuildable() 就是 isOnPlanet(state.getPlanet())）——
        // 框选、虚影都正常，点下去却什么也不发生，表现就是用户报的
        // "在其他星球的地图上合体炮台无法建造"。
        // 空集合 = 所有星球都能用，这里什么都不用加。
        st.init();
        st.postInit();
      } catch (Throwable t) {
        Log.err("[combine] 超级组合炮台 @x@ 装配失败（已兜底）", side, side, t);
      } finally {
        ensureIcons(st);
      }
      combine.turret.SuperTurret.bySide[side] = st;
    }
    Log.info("[combine] 超级组合炮台：@ 种边长已装配",
        combine.turret.SuperTurret.MAX_SIDE - combine.turret.SuperTurret.MIN_SIDE + 1);
  }

  /**
   * 内容数据兜底：把"带 null 的 ItemStack 数组"从游戏数据里清掉。
   *
   * 为什么必须有：原版这两个地方都是无检查取值，模组改不了它们（都是原版类）——
   * - PlacementFragment（建造菜单）：ItemModule.has(stack.item) → 造价表里 item=null 就 NPE；
   * - ResearchDialog$View.canSpend（科技树）：finishedRequirements[i].amount /
   * requirements[i].amount
   * → 数组里出现 null 元素就 NPE（用户报的"打开科技树崩溃"就是这条）。
   * 坏数据的典型来源：别的模组在**节点已经进 TechTree.all 之后**再调 TechNode.setupRequirements()
   * 重设造价（按 JSON 解析科技树的模组都这么干），只要有一项找不到对应物品，
   * setupRequirements 就会在填 finishedRequirements 的中途抛异常：节点留在树里、
   * finishedRequirements 全是 null，之后谁开科技树谁崩。本模组自己踩过的坑则是
   * 造价表在 content.load() 之前就构造（Items.* 那时还是 null），同样留下 null 造价。
   * 幂等、只扫数组，成本低；装完之后方块/节点都只剩干净数据。
   */
  static void sanitizeContent() {
    try {
      for (mindustry.world.Block b : Vars.content.blocks()) {
        if (b == null || b.requirements == null)
          continue;
        ItemStack[] req = b.requirements;
        boolean bad = false;
        for (ItemStack s : req)
          if (s == null || s.item == null) {
            bad = true;
            break;
          }
        if (!bad)
          continue;
        ItemStack[] fixed = filterStacks(req);
        b.requirements = fixed;
        Log.warn("[combine] 方块 @ 的造价表里有 null 项，已清掉（@ 项 -> @ 项）",
            b.name, req.length, fixed.length);
      }
    } catch (Throwable t) {
      Log.err("[combine] 方块造价表体检失败", t);
    }
    sanitizeTechNodes();
  }

  /** 去掉数组里的 null 元素与 item=null 的项（保留顺序）。 */
  static ItemStack[] filterStacks(ItemStack[] in) {
    Seq<ItemStack> clean = new Seq<>();
    if (in != null)
      for (ItemStack s : in)
        if (s != null && s.item != null)
          clean.add(s);
    return clean.toArray(ItemStack.class);
  }

  /**
   * 科技树节点数据兜底：requirements / finishedRequirements 为 null、长度不一致、
   * 含 null 元素或 item=null 的节点，用非空项重建（setupRequirements 会同步重建
   * finishedRequirements）。幂等，每次世界加载/进客户端后跑一次，成本低。
   */
  static void sanitizeTechNodes() {
    try {
      for (TechNode n : TechTree.all) {
        if (n == null || n.content == null)
          continue;
        ItemStack[] req = n.requirements;
        ItemStack[] fin = n.finishedRequirements;
        boolean bad = req == null || fin == null || fin.length != req.length;
        if (!bad) {
          for (ItemStack s : req)
            if (s == null || s.item == null) {
              bad = true;
              break;
            }
        }
        if (!bad) {
          for (ItemStack s : fin)
            if (s == null || s.item == null) {
              bad = true;
              break;
            }
        }
        if (!bad)
          continue;
        ItemStack[] fixed = filterStacks(req);
        n.setupRequirements(fixed.length == 0 ? new ItemStack[0] : ItemStack.copy(fixed));
        Log.warn("[combine] 科技树节点 @ 的造价数据损坏，已重建 @ 项", n.content.name, fixed.length);
      }
    } catch (Throwable t) {
      Log.err("[combine] sanitizeTechNodes 失败", t);
    }
  }

  /**
   * 科技树开着的时候再兜一层：原版 canSpend 是每帧在 update 里跑的，
   * 只要有人（别的模组）在进游戏之后又动了树，这里能在下一帧之前把数据补回去。
   * 节流成 1 秒一次，扫一遍数组而已。
   */
  static float lastTreeCheck = -10f;

  static void guardResearchDialog() {
    if (Vars.headless || Vars.ui == null || Vars.ui.research == null)
      return;
    try {
      Vars.ui.research.update(() -> {
        if (!Vars.ui.research.isShown())
          return;
        if (Time.time - lastTreeCheck < 60f)
          return;
        lastTreeCheck = Time.time;
        sanitizeTechNodes();
      });
    } catch (Throwable t) {
      Log.err("[combine] 科技树守护挂钩失败", t);
    }
  }

  /**
   * 刷新科技树界面的节点缓存。
   *
   * ResearchDialog 是在 UI 构造时（ClientLauncher: add(ui = new UI())，早于
   * mods.eachClass(Mod::init)）就把"当前那棵树"的节点缓存进 view 的（TechTreeNode 递归建一遍），
   * 之后打开界面只是照缓存画。java 模组的 Mod.init() 在 UI 构造之后才跑，
   * 所以这里挂上去的节点，界面第一次打开（正好是塞普罗树）是看不到的 ——
   * 玩家手动切一次树（点标题）才会重新抓。实测 X37 客户端：初始缓存 223 个节点，
   * 三个新节点一个都没有；切走再切回来就都有了。
   * 这里在客户端加载完成后检查一次，缺了就切到别的树再切回来（switchTree 在
   * lastNode == node 时会直接 return，所以必须绕一圈）。
   */
  static void refreshResearchTree() {
    if (Vars.headless || Vars.ui == null || Vars.ui.research == null)
      return;
    try {
      TechNode cur = Vars.ui.research.lastNode;
      if (cur == null)
        return;
      // 注意：要查**界面缓存的那份节点表**（ResearchDialog.nodes），不是 TechTree 的数据 ——
      // 数据里早就有我们的节点了，是界面那份快照没有。
      boolean missing = !dialogTreeHas(comboConnector) || !dialogTreeHas(comboNode)
          || !dialogTreeHas(liquidUnloader);
      if (!missing)
        return;
      TechNode other = null;
      for (TechNode r : TechTree.roots)
        if (r != cur) {
          other = r;
          break;
        }
      if (other == null)
        return;
      Vars.ui.research.switchTree(other);
      Vars.ui.research.switchTree(cur);
      Log.info("[combine] 科技树界面缓存已刷新（新节点挂载晚于 UI 构造）");
    } catch (Throwable t) {
      Log.err("[combine] 刷新科技树界面缓存失败（不影响其它功能）", t);
    }
  }

  /** 科技树界面当前缓存的那份节点表里有没有这个方块。 */
  static boolean dialogTreeHas(Block b) {
    if (b == null)
      return true;
    for (mindustry.ui.dialogs.ResearchDialog.TechTreeNode n : Vars.ui.research.nodes)
      if (n != null && n.node != null && n.node.content == b)
        return true;
    return false;
  }

  /**
   * 已注册方块的图标兜底：v8 客户端不会替 init() 里注册的方块调 load/loadIcon，
   * 漏掉任何一个 + alwaysUnlocked 就会在 PlacementFragment 重建时 NPE。
   * 这里逐步容错：哪步失败只记日志，目标是"绝不让 uiIcon/fullIcon 为 null 的方块进注册表"。
   */
  static void ensureIcons(mindustry.world.Block b) {
    if (b == null || !visuals())
      return;
    try {
      b.load();
    } catch (Throwable t) {
      Log.err("[combine] " + b.name + " load() 失败（图标兜底继续）", t);
    }
    try {
      b.loadIcon();
    } catch (Throwable t) {
      Log.err("[combine] " + b.name + " loadIcon() 失败", t);
    }
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

  /**
   * 方块（或其父类）的类名是不是 name。
   * 用于"某些版本 / 某些客户端 jar 里压根没有这个类"的判定（如 AutoDoor）——
   * 直接写 {@code instanceof AutoDoor} 会在类加载阶段就 NoClassDefFoundError，
   * 用类名字符串比较则完全不受影响。
   */
  static boolean isClassNamed(Object o, String name) {
    if (o == null || name == null)
      return false;
    for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
      if (name.equals(c.getName()))
        return true;
    return false;
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

  /**
   * 组合传送带：把原版/模组的 {@code Conveyor / ArmoredConveyor / Duct / StackConveyor}（含子类）
   * 换成能"覆盖叠层提速"的组合版（同 id、同名字、同贴图、同速度，行为不变）。
   *
   * <p>这些类在 {@link NoCombo} 里（传输类不该"组合"共用库存），所以不走 {@link #processModBlocks()}；
   * 放置/叠层逻辑见 {@code combine.distribution.ConveyorOverlay}。
   *
   * <p>和模组其它"现造方块"一样：v8 不会替这一步注册的方块调 init()/postInit()/loadIcon()，
   * 必须自己补；单个方块失败不能拖垮整张内容表。
   */
  void processConveyors() {
    // 快照遍历：createCombo 会原地修改注册表（set+pop），不可直接遍历
    Seq<Content> snapshot = new Seq<>(Vars.content.getBy(ContentType.block));
    int count = 0;
    for (var c : snapshot) {
      Block b = (Block) c;
      if (originalToCombo.containsKey(b) || comboToOriginal.containsKey(b))
        continue;
      if (list.contains(b.name))
        continue;
      if (b instanceof combine.distribution.CombinedConveyor
          || b instanceof combine.distribution.CombinedDuct
          || b instanceof combine.distribution.CombinedStackConveyor)
        continue;

      boolean isStack = b instanceof mindustry.world.blocks.distribution.StackConveyor;
      boolean isDuct = b instanceof mindustry.world.blocks.distribution.Duct;
      boolean isConveyor = b instanceof mindustry.world.blocks.distribution.Conveyor;
      if (!isStack && !isDuct && !isConveyor)
        continue;

      try {
        Block combo;
        if (isStack) {
          combo = createCombo(b, combine.distribution.CombinedStackConveyor.class);
        } else if (isDuct) {
          combo = createCombo(b, combine.distribution.CombinedDuct.class);
        } else {
          combine.distribution.CombinedConveyor cc =
              createCombo(b, combine.distribution.CombinedConveyor.class);
          // ArmoredConveyor 只是 Conveyor 上多了"防侧装"，用同一个组合类 + 开关保留它的行为
          cc.armored = b instanceof mindustry.world.blocks.distribution.ArmoredConveyor;
          combo = cc;
        }
        copyFields(b, combo);
        combo.init();
        combo.postInit();
        if (visuals())
          // 贴图/图标兜底：方块名和原版一样，load() 会按原名重新取一遍图集贴图
          // （绝不留 null —— 建造菜单里 uiIcon 为 null 会直接崩）
          ensureIcons(combo);
        postInit(combo);
        // 层数"配置"：copyFields 之后注册一次（原版这些方块没有配置，不会被覆盖，但顺序上更稳）
        combine.distribution.ConveyorOverlay.install(combo);
        count++;
      } catch (Throwable th) {
        Log.err("[combine] processConveyors failed for " + b.name, th);
      }
    }
    if (count > 0)
      Log.info("[combine] 组合传送带：@ 种传送带/管道已装配（可覆盖叠层，最多 @ 层）",
          count, combine.distribution.ConveyorOverlay.MAX_LAYERS);
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
      // 不组合名单（**只按类**，含 js/java 子类）：传送带/管道/桥/分流器这类运输方块。
      // 这里绝不能看设置界面里的手动黑名单（Core.settings，本机偏好）：
      // 替换会改内容表 id，两端不一致联机就错位；而且改一下就整模组组合建筑变原版。
      if (NoCombo.blockedByClass(b))
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
      // 通量反应堆（flux-reactor, Erekir VariableReactor）：既不是 ConsumeGenerator 也不是
      // 上面几种，以前完全没被接管 → "通量反应堆无法组合"（用户报的）。单独走
      // CombinedVariableReactor（继承原版，存/读档和原版一致）。
      boolean isFlux = isExact(b, VariableReactor.class);
      boolean isLaunchPad = isExact(b, LaunchPad.class);
      // 接收台（campaign LandingPad）：组合类 CombinedLandingPad 一直写好了但**从没接进来**，
      // 于是"接收台"从来没被组合过（用户报的"CombinedLandingPad 的组合怎么没了"）。
      boolean isLandingPad = isExact(b, mindustry.world.blocks.campaign.LandingPad.class);
      boolean isRegen = isExact(b, RegenProjector.class);
      boolean isOverdrive = isExact(b, OverdriveProjector.class);
      boolean isMend = isExact(b, MendProjector.class);
      // FIX[JS 力墙]: js（或 java）写的 ForceProjector **子类**，只要用到相位护盾这套参数
      // （phaseUseTime / itemConsumer / phaseShieldBoost —— 都是 ForceProjector 上的普通字段，
      // BlockCloner 会原样拷到组合力墙上），也纳入组合力墙的替换范围。
      // 用反射读，避免依赖编译期版本有没有这些字段；不想要可以写进 assets/whitelist.json 黑名单。
      boolean isForce = isExact(b, ForceProjector.class);
      if (!isForce && b instanceof ForceProjector) {
        boolean usesPhase = fieldFloat(b, "phaseUseTime", 0f) > 0f
            || fieldFloat(b, "phaseShieldBoost", 0f) > 0f
            || ComboReflect.fieldValue(b, "itemConsumer") != null;
        isForce = usesPhase;
      }

      boolean isStorage = isExact(b, StorageBlock.class);
      // 核心：原版 CoreBuild.onProximityUpdate 会按"自己算的容量"把超出部分**真删掉**，
      // 而组合仓库链/节点扩出来的容量它不认 —— 一重算就吞玩家的货（用户报的）。
      // 按用户要求"源码做不到就替换原版核心"，这里换成组合核心（只在截断处兜住）。
      boolean isCore = isExact(b, CoreBlock.class);
      // 可选：接上之前一直闲置的 CombinedPump / CombinedWallCrafter
      boolean isLogic = isExact(b, LogicBlock.class);
      boolean isContLiquidTurret = isExact(b, ContinuousLiquidTurret.class);
      boolean isLiquidTurret = isExact(b, LiquidTurret.class);
      boolean isItemTurret = isExact(b, ItemTurret.class);
      boolean isPowerTurret = isExact(b, PowerTurret.class);
      boolean isLaserTurret = isExact(b, LaserTurret.class);
      boolean isPump = isExact(b, Pump.class) && !isExact(b, SolidPump.class);
      boolean isSolidPump = isExact(b, SolidPump.class);
      // 抽油机（Fracker / oil-extractor）：SolidPump 的子类，会"吃物品"换油，单独走 CombinedFracker
      boolean isFracker = isExact(b, Fracker.class);
      boolean isWallCrafter = isExact(b, WallCrafter.class);
      boolean isUnitFactory = isExact(b, UnitFactory.class);
      boolean isReconstructor = isExact(b, Reconstructor.class);

      if (!isFactory && !isHeatCrafter && !isHeatProducer && !isSeparator && !isAttribute
          && !isDrill && !isGenerator && !isLaunchPad && !isLandingPad
          && !isRegen && !isOverdrive && !isMend && !isForce && !isStorage && !isCore
          && !isLogic && !isContLiquidTurret && !isLiquidTurret && !isItemTurret
          && !isPowerTurret && !isLaserTurret
          && !isPump && !isSolidPump && !isFracker && !isWallCrafter
          && !isUnitFactory && !isReconstructor && !isFlux)
        continue;

      // 防止重复处理已转换类型
      if (b instanceof CombinedCrafter || b instanceof CombinedDrill
          || b instanceof CombinedGenerator || b instanceof CombinedLaunchPad || b instanceof CombinedLandingPad
          || b instanceof combine.storage.CombinedCoreBlock
          || b instanceof CombinedRegenProjector || b instanceof CombinedOverdriveProjector
          || b instanceof CombinedMendProjector || b instanceof CombinedForceProjector
          || b instanceof CombinedStorageBlock || b instanceof CombinedLogicProcessor
          || b instanceof CombinedContinuousLiquidTurret || b instanceof CombinedLiquidTurret
          || b instanceof CombinedItemTurret || b instanceof CombinedTurret
          || b instanceof CombinedPump
          || b instanceof CombinedFracker
          || b instanceof CombinedWallCrafter
          || b instanceof CombinedUnitFactory
          || b instanceof CombinedReconstructor
          || b instanceof CombinedVariableReactor)
        continue;

      Block combo;
      if (isFlux) {
        combo = createCombo(b, CombinedVariableReactor.class);
      } else if (isFactory || isHeatCrafter || isHeatProducer || isSeparator || isAttribute) {
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
      } else if (isLandingPad) {
        combo = createCombo(b, CombinedLandingPad.class);
      } else if (isRegen) {
        combo = createCombo(b, CombinedRegenProjector.class);
      } else if (isOverdrive) {
        combo = createCombo(b, CombinedOverdriveProjector.class);
      } else if (isMend) {
        combo = createCombo(b, CombinedMendProjector.class);
      } else if (isCore) {
        combo = createCombo(b, combine.storage.CombinedCoreBlock.class);
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
      } else if (isFracker) {
        // Fracker 是 SolidPump 子类，但会"吃物品"换油（原版 FrackerBuild 的 accumulator/consume），
        // 所以单独用 CombinedFracker：液体池 + 物品池都按整组共享
        combo = createCombo(b, CombinedFracker.class);
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
      // 同上：只按类排除，不看设置界面里的手动黑名单
      if (NoCombo.blockedByClass(b))
        continue;
      boolean isWall = b instanceof Wall;
      boolean isDoor = isExact(b, mindustry.world.blocks.defense.Door.class);
      boolean isShield = isExact(b, mindustry.world.blocks.defense.ShieldWall.class); // 新增
      // 自动门（原版 AutoDoor / blast-door）也 extends Wall，原来会被当成普通墙替换 ——
      // 自动开关的行为就没了。这里单独认出来，作为 LinkWall 的 autodoor 模式融入。
      // 按**类名**判断：低版本/别的客户端 jar 没有 AutoDoor 这个类，直接引用会在加载时
      // NoClassDefFoundError（所以不能用 instanceof）。
      boolean isAutoDoor = isClassNamed(b, "mindustry.world.blocks.defense.AutoDoor");
      if (!isWall && !isDoor && !isShield && !isAutoDoor) // 条件加一个
        continue;
      try {
        LinkWall lw = createCombo(b, LinkWall.class);
        copyFields(b, lw);
        lw.mode = isShield ? LinkWall.Mode.shield
            : (isAutoDoor ? LinkWall.Mode.autodoor
                : (isDoor ? LinkWall.Mode.door : LinkWall.Mode.wall)); // 四模式
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
