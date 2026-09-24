package combine.turret;
import arc.struct.*;
import combine.net.ComboNet;
import combine.production.CombinedCrafter;
import combine.util.ComboReflect;
import combine.util.ComboUi;
import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.math.Angles;
import arc.math.Mathf;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Strings;
import arc.util.Time;
import mindustry.gen.Bullet;
import mindustry.entities.bullet.BulletType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.logic.LAccess;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.consumers.Consume;
import mindustry.world.blocks.ItemSelection;
import mindustry.world.consumers.ConsumeLiquidBase;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.world.meta.StatValues;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.*;

/**
 * 组合炮塔（电力/激光双模式）——原版 PowerTurret / LaserTurret 的组合版，共享液体池。
 *
 * 单继承方案：extends Turret，两种模式把原版子类行为复刻在 Build 内（参照 CombinedCrafter 模式先例）：
 * - power : 复刻 PowerTurretBuild（弹药抽象为 shootType、电量弹药条/传感器）；
 * - laser : 在 power 基础上追加 LaserTurretBuild（持续光束 BulletEntry 维护、冷却液驱动装填）。
 * copyFields 会把 shootType / coolant / firingMoveFract / shootDuration /
 * hasPower 等字段
 * 从原版同名同类型拷贝过来。
 *
 * 组合语义：相邻同型自动成组，冷却液/液体共享（组总容量 = Σ 单台），
 * liquidCapacity=9999 + 每帧组容量截断 + acceptLiquid 组容量 + 超额退回源端。
 * 无自定义序列化：读档后首个 updateTile 惰性重建编组并重新并池，液体总量守恒。
 */
public class CombinedTurret extends Turret {

  public enum Mode {
    power,
    laser
  }

  public Mode mode = Mode.power;

  // ---- 复刻自 PowerTurret / LaserTurret 的字段（copyFields 会从原版同名同类型拷贝） ----
  /** PowerTurret.shootType：弹药弹道类型 */
  public BulletType shootType;
  /** LaserTurret.firingMoveFract：开火中转向速度比例 */
  public float firingMoveFract = 0.25f;
  /** LaserTurret.shootDuration：光束维持时长（帧） */
  public float shootDuration = 100f;

  public boolean allowCrossTypeCombo = true;
  public float baseLiquidCapacity = 10f;
  public float displayLiquid;
  public boolean baseCapCaptured = false;

  public CombinedTurret(String name) {
    super(name);
    buildType = CombinedTurretBuild::new;
    hasLiquids = true;
    sync = true;
    // 冷却液选择（和组合物品炮塔同一套）：config 走联机同步；null = 自动用池里效果最好的
    configurable = true;
    // 【整组一起改】液池是全组共用的一份；只改被点的那台的话，其余还是"自动"，
    // 每帧互相把池子的 current 抢回去 —— 表现就是"选了没用、始终用冷冻液"（用户报的）。
    config(Liquid.class, (CombinedTurretBuild tile, Liquid l) -> {
      for (CombinedTurretBuild m : tile.group())
        if (m != null)
          m.selectedCoolant = l;
    });
    configClear((CombinedTurretBuild tile) -> {
      for (CombinedTurretBuild m : tile.group())
        if (m != null)
          m.selectedCoolant = null;
    });
    // conductivePower 在 init() 里设置（copyFields 会覆盖构造器赋值）
  }

  @Override
  public void init() {
    super.init();
    // 【必须在 init 里设】克隆体是先 new 再 copyFields(原版字段)，原版 PowerTurret/LaserTurret
    // 的 configurable 是 false，会把构造器里设的值覆盖掉 —— 于是 Call.tileConfig 直接忽略点击，
    // "选了冷却液没反应、也没有黄框"（用户报的）。init() 在 copyFields 之后跑，这里设才留得住。
    configurable = true;
    // 【整组一起改】液池是全组共用的一份；只改被点的那台的话，其余还是"自动"，
    // 每帧互相把池子的 current 抢回去 —— 表现就是"选了没用、始终用冷冻液"（用户报的）。
    config(Liquid.class, (CombinedTurretBuild tile, Liquid l) -> {
      for (CombinedTurretBuild m : tile.group())
        if (m != null)
          m.selectedCoolant = l;
    });
    configClear((CombinedTurretBuild tile) -> {
      for (CombinedTurretBuild m : tile.group())
        if (m != null)
          m.selectedCoolant = null;
    });
    // FIX[双init防护]: 内容加载器可能再次 init(), 防止把 9999 假容量记成基础容量
    if (!baseCapCaptured) {
      baseLiquidCapacity = liquidCapacity;
      displayLiquid = baseLiquidCapacity;
      baseCapCaptured = true;
    }
    // 9999 必须保留：原版 transferLiquid 按"目标方块的 liquidCapacity"限流，共享池总量超过
    // 单台容量后管道会算出负流量而彻底断流（调用方是原版代码，炮塔侧无法拦截）。
    // 超容防护 = acceptLiquid 组容量 + handleLiquid 超量退回源端 + 每帧截断。
    // 副作用 DrawTurret 液体填充度 = 余量/9999 ≈ 0，由 Build.draw() 里临时换容量解决。
    liquidCapacity = 9999f;
    if (!hasLiquids)
      displayLiquid = 0;
    hasLiquids = true;
    conductivePower = true; // copyFields 会用原版的 false 覆盖构造器赋值
    // LaserTurret.init：coolant 从消费者里兜底查找
    if (mode == Mode.laser && coolant == null)
      coolant = findConsumer(c -> c instanceof mindustry.world.consumers.ConsumeLiquidBase);
  }

  @Override
  public void setStats() {
    super.setStats();

    // 【关键】原版 PowerTurret / LaserTurret 的子弹信息就在这里显示。
    // 不能直接写 ObjectMap.of(this, shootType)——CombinedTurret 只 extends Turret，
    // this 的静态类型是 CombinedTurret，泛型推断出的 ObjectMap<CombinedTurret, BulletType>
    // 传不进 StatValues.ammo(ObjectMap<Block, BulletType>)，编译器要么报错、要么静默选错。
    // 显式声明成 ObjectMap<Block, BulletType> 才能让编译器和运行时都走上正确的重载。
    if (shootType != null) {
      stats.add(Stat.ammo, StatValues.ammo(ObjectMap.of(this, shootType)));
    }

    stats.remove(Stat.liquidCapacity);
    stats.add(Stat.liquidCapacity, displayLiquid, StatUnit.liquidUnits);

    if (mode == Mode.laser) {
      stats.remove(Stat.booster);
      if (coolant != null)
        stats.add(Stat.input,
                StatValues.boosters(reload, coolant.amount, coolantMultiplier, false, this::consumesLiquid));
    }
  }

  @Override
  public void setBars() {
    super.setBars();
    // 没有液体消费（冷却液）的炮塔（如 afflict：只吃电+热，原版同样拒绝一切液体）
    // 不显示液体条 —— 避免永远空着的"液体"灰条造成"吃不了液体"的误解
    boolean usesLiquid = coolant != null;
    if (!usesLiquid && consumers != null) {
      for (Consume cons : consumers) {
        if (cons instanceof ConsumeLiquidBase) {
          usesLiquid = true;
          break;
        }
      }
    }
    if (!usesLiquid) {
      removeBar("liquid");
      return;
    }
    // 替换原版 liquid 条（原版按 block.liquidCapacity=9999 算占比，永远是空的）
    addBar("liquid", (CombinedTurretBuild e) -> new Bar(
        () -> {
          Liquid cur = e.displayLiquidNow();
          return cur == null ? "[lightgray]液体[]"
              : cur.localizedName + " " + Strings.fixed(e.liquids.currentAmount(), 1)
                  + "/" + Strings.fixed(Math.max(e.comboTotalLiquidCap, 1f), 1);
        },
        () -> {
          Liquid cur = e.displayLiquidNow();
          return cur == null ? Pal.gray
              : (cur.barColor != null ? cur.barColor : cur.color);
        },
        () -> e.liquids == null ? 0f
            : e.liquids.currentAmount() / Math.max(e.comboTotalLiquidCap, 1f)));
  }

  public class CombinedTurretBuild extends TurretBuild {

    /** LiquidModule.current 是 private 且无 setter，反射缓存（仅在实际液体与 current 不一致时写回） */
    static final java.lang.reflect.Field fLiquidCurrent = findLiquidCurrent();

    static java.lang.reflect.Field findLiquidCurrent() {
      try {
        java.lang.reflect.Field f = mindustry.world.modules.LiquidModule.class.getDeclaredField("current");
        f.setAccessible(true);
        return f;
      } catch (Exception e) {
        return null;
      }
    }

    public CombinedTurretBuild comboLeader;
    public Seq<CombinedTurretBuild> comboGroup = new Seq<>();
    public boolean comboDirty = true;
    public float comboTotalLiquidCap = 0f;
    /** laser 模式：维持中的光束（复刻 LaserTurretBuild.bullets） */
    public Seq<BulletEntry> bullets = new Seq<>();

    boolean laser() {
      return ((CombinedTurret) block).mode == Mode.laser;
    }

    BulletType shootType() {
      return ((CombinedTurret) block).shootType;
    }

    // ==================== 组合编组（共享液体池） ====================

    /** 面板/绘制该显示的液体：选了就显示它，否则显示池里效果最好的，最后才退回"池里第一种"。 */
    public Liquid displayLiquidNow() {
      Liquid c = effectiveCoolant();
      return c != null ? c : firstLiquid();
    }

    /** 池内实际存在的第一种液体（空池返回 null，避免 current() 默认水的误导显示） */
    public Liquid firstLiquid() {
      if (liquids == null)
        return null;
      for (Liquid l : content.liquids())
        if (liquids.get(l) > 0.001f)
          return l;
      return null;
    }

    public CombinedTurretBuild leader() {
      if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null))
        comboLeader = null;
      return comboLeader == null ? this : comboLeader;
    }

    public boolean isLeader() {
      return leader() == this;
    }

    public Seq<CombinedTurretBuild> group() {
      CombinedTurretBuild l = leader();
      if (l.comboGroup == null)
        l.comboGroup = new Seq<>();
      return l.comboGroup;
    }

    public void rebuildCombo() {
      Seq<CombinedTurretBuild> oldGroup = comboGroup != null ? new Seq<>(comboGroup) : new Seq<>();
      comboGroup = new Seq<>();
      comboGroup.add(this);
            // 分组 BFS 穿过组合节点/连接器：被节点连上 = 效果相当于直接组合（同 LinkWall 语义）
            for (Building b : ComboReflect.linkedReachable(this,
                    o -> o instanceof CombinedTurretBuild other && other.team == team && other.isValid(),
                    (cur, o) -> cur.block == o.block
                    || ((CombinedTurret) cur.block).allowCrossTypeCombo
                    || ((CombinedTurret) o.block).allowCrossTypeCombo)) {
                if (b != this)
                    comboGroup.add((CombinedTurretBuild) b);
            }
      CombinedTurretBuild newLeader = this;
      for (CombinedTurretBuild b : comboGroup)
        if (b.isValid() && b.pos() < newLeader.pos())
          newLeader = b;
      Seq<CombinedTurretBuild> newGroup = new Seq<>(comboGroup);
      newLeader.comboGroup = newGroup;
      for (CombinedTurretBuild b : newGroup) {
        if (b.isValid()) {
          b.comboLeader = newLeader;
          b.comboGroup = newGroup;
          b.comboDirty = false;
        }
      }
      newLeader.comboLeader = null;

      float totalLiqCap = 0f;
      for (CombinedTurretBuild b : newGroup)
        if (b.isValid())
          totalLiqCap += ((CombinedTurret) b.block).baseLiquidCapacity;
      for (CombinedTurretBuild b : newGroup)
        if (b.isValid())
          b.comboTotalLiquidCap = totalLiqCap;

      if (oldGroup.size > newGroup.size)
        splitAssets(oldGroup, newGroup);
      shareModules(newLeader);
      trimExcessLiquids(newLeader);

      for (CombinedTurretBuild old : oldGroup) {
        if (old != this && old.isValid() && !newGroup.contains(old)) {
          old.comboLeader = null;
          old.comboGroup = new Seq<>();
          old.comboDirty = true;
          old.comboTotalLiquidCap = 0f;
        }
      }
    }

    /** 被踢出的成员按容量比例分走液体 */
    public void splitAssets(Seq<CombinedTurretBuild> oldGroup, Seq<CombinedTurretBuild> newGroup) {
      // FIX[双重拆分]: 网络层(ComboNet)可能已把池子按组件拆过一轮、给部分成员发过新模块。
      // 那些份额先并回主池（新组长持有的模块），再由本地逻辑按容量统一重分，
      // 否则两轮拆分各分各的，总额对不上账（物资凭空丢/复制）。
      CombinedTurretBuild newLeader = null;
      for (CombinedTurretBuild b : newGroup)
          if (b.isValid() && (newLeader == null || b.pos() < newLeader.pos()))
              newLeader = b;
      if (newLeader == null)
          newLeader = this;
      LiquidModule poolLiquids = newLeader.liquids;
      // 【池子可能不只本组在用】核心库存 / 组合节点接过来的别的组合体也在引用它时，
      // 本地拆分一律不动这口池子（退出成员拿空模块，由 ComboNet 按网络重新分配）。
      // 否则就是从核心库存里搬东西给退出的工厂 —— 用户报的"拆工厂时核心里的东西全没了"。
      boolean liquidPoolOurs = !ComboReflect.liquidPoolSharedOutside(poolLiquids, oldGroup);
      for (CombinedTurretBuild b : oldGroup) {
          if (!b.isValid()) continue;
          if (liquidPoolOurs && poolLiquids != null && b.liquids != null && b.liquids != poolLiquids) {
              for (Liquid liquid : content.liquids()) {
                  float amt = b.liquids.get(liquid);
                  if (amt > 0.001f) { poolLiquids.add(liquid, amt); b.liquids.remove(liquid, amt); }
              }
          }
      }
      CombinedTurretBuild oldLeader = newLeader;
      LiquidModule oldLiquids = poolLiquids;
      Seq<CombinedTurretBuild> kicked = new Seq<>();
      for (CombinedTurretBuild b : oldGroup)
        if (b.isValid() && !newGroup.contains(b))
          kicked.add(b);
      if (kicked.isEmpty())
        return;

      float oldTotalLiqCap = 0f;
      for (CombinedTurretBuild b : oldGroup)
        if (b.isValid())
          oldTotalLiqCap += ((CombinedTurret) b.block).baseLiquidCapacity;
      float[] liquidCaps = new float[kicked.size];
      float kickedTotalLiqCap = 0f;
      for (int i = 0; i < kicked.size; i++) {
        CombinedTurretBuild b = kicked.get(i);
        liquidCaps[i] = ((CombinedTurret) b.block).baseLiquidCapacity;
        kickedTotalLiqCap += liquidCaps[i];
      }
      LiquidModule[] newLiquidMods = new LiquidModule[kicked.size];
      for (int i = 0; i < kicked.size; i++)
        newLiquidMods[i] = new LiquidModule();

      if (liquidPoolOurs && oldLiquids != null && oldTotalLiqCap > 0.001f && kickedTotalLiqCap > 0.001f) {
        for (Liquid liquid : content.liquids()) {
          float total = oldLiquids.get(liquid);
          if (total <= 0.001f)
            continue;
          float kickedTotalShare = total * kickedTotalLiqCap / oldTotalLiqCap;
          kickedTotalShare = Math.min(kickedTotalShare, total);
          float remaining = kickedTotalShare;
          for (int i = 0; i < kicked.size; i++) {
            float ideal = (i == kicked.size - 1) ? remaining
                : kickedTotalShare * liquidCaps[i] / kickedTotalLiqCap;
            ideal = Math.min(ideal, remaining);
            if (ideal > 0.001f) {
              newLiquidMods[i].add(liquid, ideal);
              remaining -= ideal;
            }
          }
          oldLiquids.remove(liquid, kickedTotalShare - remaining);
        }
      }
      if (liquidPoolOurs && oldLiquids != null)
        for (CombinedTurretBuild b : newGroup)
          if (b.isValid())
            b.liquids = oldLiquids;
      for (int i = 0; i < kicked.size; i++)
        kicked.get(i).liquids = newLiquidMods[i];
    }

    public void shareModules(CombinedTurretBuild leader) {
      float totalLiqCap = leader.comboTotalLiquidCap;
      if (leader.liquids == null) {
        for (CombinedTurretBuild m : group())
          if (m.liquids != null) {
            leader.liquids = m.liquids;
            break;
          }
      }
      // 【池子归属】组里有人拿着"组外也在用"的那份模块（核心库存/网络池）时，
      // 组长必须换成那一份再并池：否则会把网络池复制进组长自己的模块里
      //（池子没动、这边也多一份同样的液体），网络层随后把这多出来的一份并回去 —— 凭空翻倍。
      ObjectSet<LiquidModule> sharedLiquidPools = ComboReflect.liquidPoolsSharedOutside(group());
      LiquidModule sharedLiquids = sharedLiquidPools.isEmpty() ? null : sharedLiquidPools.first();
      if (sharedLiquids != null) leader.liquids = sharedLiquids;
      ObjectSet<LiquidModule> processed = new ObjectSet<>();
      if (leader.liquids != null) {
        processed.add(leader.liquids);
        for (CombinedTurretBuild m : group()) {
          if (m != leader && m.isValid() && m.liquids != null && !processed.contains(m.liquids)
              // 组外也在用的模块不能"全额并入"（并入不清空源模块 = 凭空多一份）
              && !sharedLiquidPools.contains(m.liquids)) {
            processed.add(m.liquids);
            for (Liquid liquid : content.liquids()) {
              float amt = m.liquids.get(liquid);
              if (amt > 0.001f) {
                float canAccept = Math.max(0f, totalLiqCap - leader.liquids.get(liquid));
                float transfer = Math.min(amt, canAccept);
                if (transfer > 0.001f)
                  leader.liquids.add(liquid, transfer);
              }
            }
          }
        }
        for (CombinedTurretBuild m : group())
          if (m.isValid())
            m.liquids = leader.liquids;
    if (leader.liquids != null) leader.liquids.stopFlow(); // 组内搬池子不算流量（见 ComboNet.moveItems）
      }
    }

    /** 组总容量截断（外部按 9999 假容量注入时会超容） */
    public void trimExcessLiquids(CombinedTurretBuild leader) {
      LiquidModule liq = leader.liquids;
      if (liq == null || leader.comboTotalLiquidCap <= 0.001f)
        return;
      // 每种液体各自截到组容量（不是全池总量）
      for (Liquid l : content.liquids()) {
        float amt = liq.get(l);
        if (amt > leader.comboTotalLiquidCap + 0.001f) {
          liq.remove(l, amt - leader.comboTotalLiquidCap);
        }
      }
    }

    // ==================== 生命周期 ====================

    @Override
    public void created() {
      super.created();
      comboTotalLiquidCap = ((CombinedTurret) block).baseLiquidCapacity;
      comboDirty = true;
    }

    @Override
    public void onProximityUpdate() {
      super.onProximityUpdate();
      comboDirty = true;
      for (CombinedTurretBuild m : group())
        if (m.isValid())
          m.comboDirty = true;
    }

    @Override
    public void onRemoved() {
      Seq<CombinedTurretBuild> members = new Seq<>(group());
      boolean wasLeader = isLeader();
      LiquidModule oldLiquids = this.liquids;
      // 【别动不属于本组的池子】核心库存/别的组合体也在用的那份模块：既不能按容量
      // "分一份给幸存者"（那是从核心库存里搬东西），也不能复制一份（核心库存会凭空翻倍）。
      boolean liquidPoolOurs = !ComboReflect.liquidPoolSharedOutside(oldLiquids, members);
      if (!wasLeader && oldLiquids != null) {
        boolean shared = false;
        for (CombinedTurretBuild m : members)
          if (m != this && m.isValid() && m.liquids == oldLiquids) {
            shared = true;
            break;
          }
        if (shared)
          liquids = new LiquidModule(); // 先脱钩，避免共享池被空模块覆盖
      }
      if (wasLeader) {
        Seq<CombinedTurretBuild> survivors = new Seq<>();
        for (CombinedTurretBuild b : members)
          if (b != this && b.isValid())
            survivors.add(b);
        float[] liquidCaps = new float[survivors.size];
        float totalLiqCap = 0f;
        for (int i = 0; i < survivors.size; i++) {
          CombinedTurretBuild b = survivors.get(i);
          liquidCaps[i] = ((CombinedTurret) b.block).baseLiquidCapacity;
          totalLiqCap += liquidCaps[i];
        }
        LiquidModule[] liquidMods = new LiquidModule[survivors.size];
        for (int i = 0; i < survivors.size; i++)
          liquidMods[i] = new LiquidModule();
        if (liquidPoolOurs && oldLiquids != null && totalLiqCap > 0.001f) {
          for (Liquid liquid : content.liquids()) {
            float total = oldLiquids.get(liquid);
            if (total <= 0.001f)
              continue;
            float remaining = total;
            for (int i = 0; i < survivors.size; i++) {
              float ideal = (i == survivors.size - 1) ? remaining
                  : total * liquidCaps[i] / totalLiqCap;
              ideal = Math.min(ideal, remaining);
              if (ideal > 0.001f) {
                liquidMods[i].add(liquid, ideal);
                remaining -= ideal;
              }
            }
          }
        }
        for (int i = 0; i < survivors.size; i++) {
          CombinedTurretBuild b = survivors.get(i);
          b.liquids = liquidMods[i];
          b.comboLeader = null;
          b.comboGroup = new Seq<>();
          b.comboDirty = true;
          b.comboTotalLiquidCap = 0f;
        }
      } else {
        CombinedTurretBuild l = leader();
        if (l != null && l.isValid() && l != this)
          l.comboDirty = true;
      }
      comboLeader = null;
      comboGroup = new Seq<>();
      comboDirty = false;
      comboTotalLiquidCap = 0f;
      super.onRemoved();
    }

    @Override
    public void draw() {
      // 与液体炮塔同款：绘制期间按"每台"替换 drawer.liquidDraw（=池内实际在用的液体）与
      // block.liquidCapacity（=组容量），DrawTurret 自带的液体段随之画出正确颜色/填充度
      mindustry.world.draw.DrawTurret dturret = ((mindustry.world.blocks.defense.turrets.Turret) block).drawer instanceof mindustry.world.draw.DrawTurret d
          ? d
          : null;
      float oldCap = block.liquidCapacity;
      Liquid oldDraw = dturret != null ? dturret.liquidDraw : null;
      if (dturret != null)
        dturret.liquidDraw = displayLiquidNow();
      block.liquidCapacity = Math.max(comboTotalLiquidCap, 1f);
      try {
        super.draw();
      } finally {
        block.liquidCapacity = oldCap;
        if (dturret != null)
          dturret.liquidDraw = oldDraw;
      }
    }

    /** 选定的冷却液（null = 自动用池里效果最好的那种）。和组合物品炮塔同一套。 */
    public Liquid selectedCoolant;

    /** 这个炮台能不能用这种液体当冷却液（按原版 consumeLiquid 的过滤条件）。 */
    public boolean acceptsCoolant(Liquid liquid) {
      if (liquid == null || coolant == null)
        return false;
      try {
        return coolant.consumes(liquid);
      } catch (Throwable t) {
        return false;
      }
    }

    /** 池子里现有的、本炮能用的冷却液。 */
    public Seq<Liquid> presentCoolants() {
      Seq<Liquid> out = new Seq<>();
      if (liquids == null)
        return out;
      for (Liquid l : content.liquids())
        if (liquids.get(l) > 0.001f && acceptsCoolant(l))
          out.add(l);
      return out;
    }

    /** 选中的冷却液以**队长**为准（整组共用一个池子，选择也该整组一致）。 */
    public Liquid groupCoolant() {
      CombinedTurretBuild l = leader();
      return (l != null ? l.selectedCoolant : null);
    }

    /** 选了就用选的（没货自动回退）；没选就取池里 heatCapacity 最高的（=冷却倍率最高）。 */
    public Liquid effectiveCoolant() {
      if (liquids == null)
        return null;
      Liquid selectedCoolant = groupCoolant();
      if (selectedCoolant != null && acceptsCoolant(selectedCoolant) && liquids.get(selectedCoolant) > 0.001f)
        return selectedCoolant;
      Liquid best = null;
      for (Liquid l : presentCoolants())
        if (best == null || l.heatCapacity > best.heatCapacity)
          best = l;
      return best;
    }

    /** 把池子的 current 钉成选定/自动挑出来的那种（原版冷却读的就是 current）。 */
    public void applyCoolantChoice() {
      if (liquids == null)
        return;
      try {
        Liquid want = effectiveCoolant();
        if (want != null && liquids.current() != want)
          liquids.add(want, 0f);
      } catch (Throwable ignored) {
      }
    }

    /** 配置面板：冷却液选择（和组合物品炮塔完全一样的 UI：图标按钮 + 选中黄框）。 */
    @Override
    public void buildConfiguration(Table table) {
      try {
        Seq<Liquid> coolants = new Seq<>();
        for (Liquid l : content.liquids())
          if (acceptsCoolant(l) && !l.isHidden())
            coolants.add(l);
        if (coolants.size < 2)
          return;
        ItemSelection.buildTable(CombinedTurret.this, table, coolants,
            () -> selectedCoolant, l -> configure(l));
      } catch (Throwable t) {
        Log.err("[combine] 组合炮台冷却液选择面板构建失败", t);
      }
    }

    @Override
    public void updateTile() {
      if (isLeader() && comboDirty)
        rebuildCombo();
      if (isLeader())
        trimExcessLiquids(leader());
      // 每帧把池子 current 对齐到"选定/自动挑出来的那种"冷却液
      // （以前是取池里第一种 = 谁先加进来用谁，池里混了几种就随机了）
      applyCoolantChoice();

      if (laser()) {
        // ---- 复刻 LaserTurretBuild.updateTile ----
        super.updateTile();
        bullets.removeAll(b -> !b.bullet.isAdded() || b.bullet.type == null || b.life <= 0f
            || b.bullet.owner != this);
        if (bullets.any()) {
          for (var entry : bullets) {
            float bulletX = x + Angles.trnsx(rotation - 90, shootX + entry.x, shootY + entry.y),
                bulletY = y + Angles.trnsy(rotation - 90, shootX + entry.x, shootY + entry.y),
                angle = rotation + entry.rotation;
            entry.bullet.rotation(angle);
            entry.bullet.set(bulletX, bulletY);
            entry.bullet.time = entry.bullet.type.lifetime * entry.bullet.type.optimalLifeFract;
            entry.bullet.keepAlive = true;
            entry.life -= Time.delta * timeScale / Math.max(efficiency, 0.00001f);
          }
          wasShooting = true;
          heat = 1f;
          curRecoil = 1f;
        } else if (reloadCounter > 0) {
          if (coolant != null) {
            Liquid liquid = liquids.current();
            float maxUsed = coolant.amount;
            float used = (cheating() ? maxUsed : Math.min(liquids.get(liquid), maxUsed)) * delta();
            reloadCounter -= used * liquid.heatCapacity * coolantMultiplier;
            liquids.remove(liquid, used);
            if (Mathf.chance(0.06 * used))
              coolEffect.at(x + Mathf.range(size * tilesize / 2f), y + Mathf.range(size * tilesize / 2f));
          } else {
            reloadCounter -= edelta();
          }
        }
      } else {
        super.updateTile();
      }
    }

    // ==================== PowerTurretBuild 复刻（两种模式共用） ====================

    @Override
    public float getAmmoFraction() {
      return power == null ? 0f : power.status;
    }

    @Override
    public double sense(LAccess sensor) {
      return switch (sensor) {
        case ammo -> power == null ? 0f : power.status;
        case ammoCapacity -> 1;
        case heat -> heatRequirement > 0 ? heatReq : Float.NaN;
        default -> super.sense(sensor);
      };
    }

    @Override
    public BulletType useAmmo() {
      return shootType();
    }

    @Override
    public boolean hasAmmo() {
      return true;
    }

    @Override
    public BulletType peekAmmo() {
      return shootType();
    }

    // ==================== LaserTurretBuild 复刻（仅 laser 模式） ====================

    @Override
    protected void updateCooling() {
      if (laser())
        return; // 冷却液驱动装填，走 updateTile 里的逻辑
      super.updateCooling();
    }

    @Override
    public boolean shouldAmbientSound() {
        // FIX[声音线程崩溃]: MindustryX 的环境音在独立 AudioThread（20fps）上调用
        // shouldAmbientSound()，原版默认实现转调 shouldConsume()；
        // 组合建筑这一路会读库存/遍历共享序列，跨线程相撞就是
        // NoSuchElementException。组合建筑统一禁用环境音循环。
        return false;
    }

    public boolean shouldConsume() {
      if (laser()) {
        if (bullets.any() || isActive())
          return true;
        return isShooting;
      }
      return super.shouldConsume();
    }

    @Override
    public void placed() {
      super.placed();
      if (laser())
        reloadCounter = reload;
    }

    @Override
    public float progress() {
      if (laser())
        return 1f - Mathf.clamp(reloadCounter / reload);
      return super.progress();
    }

    @Override
    protected void updateReload() {
      if (laser())
        return; // 在 updateTile() 里按冷却液结算
      super.updateReload();
    }

    @Override
    protected void updateShooting() {
      if (!laser()) {
        super.updateShooting();
        return;
      }
      if (bullets.any())
        return;
      if (reloadCounter <= 0 && efficiency > 0 && !charging() && shootWarmup >= minWarmup) {
        shoot(peekAmmo());
        reloadCounter = reload; // 原版语义：只在开火后设置；放在外面会每帧钉死装填
      }
    }

    @Override
    protected void turnToTarget(float targetRot) {
      if (!laser()) {
        super.turnToTarget(targetRot);
        return;
      }
      rotation = Angles.moveToward(rotation, targetRot,
          efficiency * rotateSpeed * delta() * (bullets.any() ? ((CombinedTurret) block).firingMoveFract : 1f));
    }

    @Override
    protected void handleBullet(@Nullable Bullet bullet, float offsetX, float offsetY, float angleOffset) {
      if (!laser()) {
        super.handleBullet(bullet, offsetX, offsetY, angleOffset);
        return;
      }
      if (bullet != null)
        bullets.add(new BulletEntry(bullet, offsetX, offsetY, angleOffset,
            ((CombinedTurret) block).shootDuration));
    }

    @Override
    public float activeSoundVolume() {
      return laser() ? 1f : super.activeSoundVolume();
    }

    @Override
    public boolean shouldActiveSound() {
      return laser() ? bullets.any() : super.shouldActiveSound();
    }

    // ==================== 液体交互 ====================

    @Override
    public boolean acceptLiquid(Building source, Liquid liquid) {
      if (!block.hasLiquids)
        return false;
      // ConsumeLiquidFilter.apply 会把所有合格冷却液写进 liquidFilter，
      // 所以组级消耗表已经覆盖了 coolant，不必再逐个成员问 cl.consumes()
      boolean needed = ComboReflect.groupConsumesLiquid(this, liquid);
      return needed && liquids != null && liquids.get(liquid) < perLiquidCap() - 0.001f;
    }

    public float perLiquidCap() {
      return Math.max(comboTotalLiquidCap, 0.001f);
    }

    @Override
    public void handleLiquid(Building source, Liquid liquid, float amount) {
      if (amount <= 0.001f)
        return;
      float canAccept = Math.max(0f, comboTotalLiquidCap - liquids.get(liquid));
      float actual = Math.min(amount, canAccept);
      if (actual > 0.001f)
        liquids.add(liquid, actual);
      // 未接收部分退回源端，防止源端因 9999 假容量被过度扣除
      float refund = amount - actual;
      if (refund > 0.001f && source != null && source.liquids != null)
        source.liquids.add(liquid, refund);
    }

    // ==================== 显示 ====================

    @Override
    public void display(Table table) {
      // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
      ComboUi.safe("combinedturret:display", () -> displayInner(table));
    }

    void displayInner(Table table) {
      table.table(cont -> {
        cont.top().left();
        cont.defaults().growX().left();
        cont.table(t -> {
          t.left();
          TextureRegion icon = block.getDisplayIcon(tile);
          if (icon == null)
            icon = Core.atlas.find("clear");
          t.add(new Image(icon)).size(8 * 4);
          int count = ComboNet.displayMembers(this, group().size).size;
          String title = count > 1 ? "[accent]组合炮塔[] x" + count + "\n" + block.getDisplayName(tile)
              : block.getDisplayName(tile);
          t.labelWrap(title).left().width(160f).padLeft(4);
        }).growX().left();
        cont.row();
        if (team != player.team())
          return;
        // ===== 仿原版：血量/液体/电力等全部 bars（displayBars 遍历 block 注册的 bar） =====
        cont.table(bars -> {
          bars.defaults().growX().height(18f).pad(4);
          displayBars(bars);
        }).growX();
        cont.row();

        Table comboIO = new Table();
        comboIO.left();
        comboIO.update(() -> {
          comboIO.clearChildren();
          buildComboIO(comboIO);
        });
        cont.add(comboIO).growX().left();
      }).width(260f).left();
        }

    public void buildComboIO(Table table) {
      table.left();
      table.add("[lightgray]组合体构成:").left();
      table.row();
      ObjectIntMap<Block> blockCounts = new ObjectIntMap<>();
      for (Building member : ComboNet.displayMembers(this, group().size)) {
        if (member.isValid()) {
          int old = blockCounts.get(member.block, 0);
          blockCounts.put(member.block, old + 1);
        }
      }
      Seq<Block> sorted = new Seq<>();
      for (Block b : blockCounts.keys())
        sorted.add(b);
      sorted.sort(b -> b.id);
      boolean hasContent = false;
      for (Block b : sorted) {
        int count = blockCounts.get(b, 0);
        if (count > 0) {
          hasContent = true;
          table.add(b.localizedName + "*" + count).color(Color.white).left();
          table.row();
        }
      }
      if (!hasContent) {
        table.add("[darkGray]无").left();
        table.row();
      }
      LiquidModule liq = liquids;
      if (liq == null) {
        CombinedTurretBuild l = leader();
        if (l != null)
          liq = l.liquids;
      }
      if (liq != null && liq.currentAmount() > 0.001f && liq.current() != null) {
        table.add("[lightgray]共享液体池:[] " + liq.current().localizedName + " "
            + Strings.fixed(liq.currentAmount(), 1) + "/" + Strings.fixed(Math.max(comboTotalLiquidCap, 1f), 1))
            .left();
        table.row();
      }
    }
  }
}
