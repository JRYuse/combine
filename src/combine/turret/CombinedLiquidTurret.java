package combine.turret;
import combine.net.ComboNet;
import combine.util.ComboReflect;
import combine.util.ComboUi;
import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.struct.IntSet;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.entities.bullet.BulletType;
import mindustry.logic.LAccess;
import mindustry.type.Liquid;
import mindustry.world.blocks.ItemSelection;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.LiquidTurret;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.*;

/**
 * 组合液体炮塔
 * 相邻同型自动成组，共享液体池（液体即弹药，组内共享弹药池），总容量 = Σ 单台容量。
 * 不写盘序列化：读档后首个 updateTile 惰性重建编组并重新并池，
 * 各成员存档里的液体在重建时合并进共享池（总量守恒）。
 */
public class CombinedLiquidTurret extends LiquidTurret {
  public boolean allowCrossTypeCombo = true;
  public float baseLiquidCapacity = 10f;
  public float displayLiquid;
  public boolean baseCapCaptured = false;

  public CombinedLiquidTurret(String name) {
    super(name);
    buildType = CombinedLiquidTurretBuild::new;
    conductivePower = true;
    hasLiquids = true;
    sync = true;
    // 注意：configurable/config 注册不要写这里——copyFields 会用原版的字段/表覆盖，
    // 统一在 init()（copyFields 之后执行）里设置
  }

  @Override
  public void init() {
    super.init();
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
    // copyFields 会用原版的 false 覆盖构造器赋值，选择 UI 开关必须在这里重设
    configurable = true;
    // configurations 表同样被 copyFields 换成原版的（没有 Liquid 消费者），在此重新注册
    config(Liquid.class, (CombinedLiquidTurretBuild tile, Liquid l) -> tile.selected = l);
    configClear((CombinedLiquidTurretBuild tile) -> tile.selected = null);
  }

  @Override
  public void setStats() {
    super.setStats();
    stats.remove(Stat.liquidCapacity);
    stats.add(Stat.liquidCapacity, displayLiquid, StatUnit.liquidUnits);
  }

  @Override
  public void setBars() {
    super.setBars();
    // 原版 liquid 条按 block.liquidCapacity 计算（真实容量下行为已恢复）；
    // 每种弹药液体各一条，占比 = 该液体余量 / 组容量；第一条占用 "liquid" 名以替换原版条
    boolean first = true;
    for (Liquid l : ammoTypes.keys()) {
      final Liquid liq = l;
      String key = first ? "liquid" : "liquid-" + l.name;
      first = false;
      addBar(key, (CombinedLiquidTurretBuild e) -> new Bar(
          () -> liq.localizedName + " " + Strings.fixed(e.liquids.get(liq), 1)
              + "/" + Strings.fixed(e.perLiquidCap(), 1),
          () -> liq.barColor != null ? liq.barColor : liq.color,
          () -> e.liquids == null ? 0f : e.liquids.get(liq) / e.perLiquidCap()));
    }
  }

  public class CombinedLiquidTurretBuild extends LiquidTurretBuild implements combine.saves.ComboSaved {

  /** LiquidModule.current 是 private 且无 setter，反射缓存（仅在当前液体与发射液体不一致时写回） */
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

    public CombinedLiquidTurretBuild comboLeader;
    public Seq<CombinedLiquidTurretBuild> comboGroup = new Seq<>();
    public boolean comboDirty = true;
    public float comboTotalLiquidCap = 0f;
    /** 玩家指定的优先弹药液体（null = 自动：最近注入的液体） */
    public Liquid selected;
    /** 最近一次外部注入的液体（自动模式的优先对象；消耗产生的 add(-x) 不更新它） */
    public Liquid lastInput;

    /**
     * 实际使用的弹药液体：selected 有量则优先 → 最近注入的液体 → 池内当前液体。
     * 不直接信 current()：原版 remove() 内部走 add(-x) 会把 current 拉回被消耗的旧液体，
     * 混合输入时颜色/弹药指向会被顶掉。
     */
    public Liquid effectiveLiquid() {
      if (selected != null && liquids.get(selected) > 0.001f)
        return selected;
      if (lastInput != null && liquids.get(lastInput) > 0.001f)
        return lastInput;
      // 不能回退 current()：扣减会把 current 设在刚打光的液体上（0 量），
      // 必须回退到池内第一种有量的液体，保证贴图颜色和弹药始终指向真实存在的液体
      Liquid first = firstLiquid();
      return first != null ? first : liquids.current();
    }


    /** 每种液体独立容量 = 组总量：组合后容量叠加，各液体各自独立存满 */
    public float perLiquidCap() {
      return Math.max(comboTotalLiquidCap, 0.001f);
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

    public CombinedLiquidTurretBuild leader() {
      if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null))
        comboLeader = null;
      return comboLeader == null ? this : comboLeader;
    }

    public boolean isLeader() {
      return leader() == this;
    }

    public Seq<CombinedLiquidTurretBuild> group() {
      CombinedLiquidTurretBuild l = leader();
      if (l.comboGroup == null)
        l.comboGroup = new Seq<>();
      return l.comboGroup;
    }

    // -------------------- 组合重建 --------------------

    public void rebuildCombo() {
      Seq<CombinedLiquidTurretBuild> oldGroup = comboGroup != null ? new Seq<>(comboGroup) : new Seq<>();
      comboGroup = new Seq<>();
      comboGroup.add(this);
            // 分组 BFS 穿过组合节点/连接器：被节点连上 = 效果相当于直接组合（同 LinkWall 语义）
            for (Building b : ComboReflect.linkedReachable(this,
                    o -> o instanceof CombinedLiquidTurretBuild other && other.team == team && other.isValid(),
                    (cur, o) -> cur.block == o.block
                    || ((CombinedLiquidTurret) cur.block).allowCrossTypeCombo
                    || ((CombinedLiquidTurret) o.block).allowCrossTypeCombo)) {
                if (b != this)
                    comboGroup.add((CombinedLiquidTurretBuild) b);
            }
      CombinedLiquidTurretBuild newLeader = this;
      for (CombinedLiquidTurretBuild b : comboGroup)
        if (b.isValid() && b.pos() < newLeader.pos())
          newLeader = b;
      Seq<CombinedLiquidTurretBuild> newGroup = new Seq<>(comboGroup);
      newLeader.comboGroup = newGroup;
      for (CombinedLiquidTurretBuild b : newGroup) {
        if (b.isValid()) {
          b.comboLeader = newLeader;
          b.comboGroup = newGroup;
          b.comboDirty = false;
        }
      }
      newLeader.comboLeader = null;

      float totalLiqCap = 0f;
      for (CombinedLiquidTurretBuild b : newGroup)
        if (b.isValid())
          totalLiqCap += ((CombinedLiquidTurret) b.block).baseLiquidCapacity;
      for (CombinedLiquidTurretBuild b : newGroup)
        if (b.isValid())
          b.comboTotalLiquidCap = totalLiqCap;

      if (oldGroup.size > newGroup.size)
        splitAssets(oldGroup, newGroup);
      shareModules(newLeader);
      trimExcessLiquids(newLeader);

      for (CombinedLiquidTurretBuild old : oldGroup) {
        if (old != this && old.isValid() && !newGroup.contains(old)) {
          old.comboLeader = null;
          old.comboGroup = new Seq<>();
          old.comboDirty = true;
          old.comboTotalLiquidCap = 0f;
        }
      }
    }

    /** 被踢出的成员按容量比例分走液体 */
    public void splitAssets(Seq<CombinedLiquidTurretBuild> oldGroup, Seq<CombinedLiquidTurretBuild> newGroup) {
      // FIX[双重拆分]: 网络层(ComboNet)可能已把池子按组件拆过一轮、给部分成员发过新模块。
      // 那些份额先并回主池（新组长持有的模块），再由本地逻辑按容量统一重分，
      // 否则两轮拆分各分各的，总额对不上账（物资凭空丢/复制）。
      CombinedLiquidTurretBuild newLeader = null;
      for (CombinedLiquidTurretBuild b : newGroup)
          if (b.isValid() && (newLeader == null || b.pos() < newLeader.pos()))
              newLeader = b;
      if (newLeader == null)
          newLeader = this;
      LiquidModule poolLiquids = newLeader.liquids;
      // 【池子可能不只本组在用】核心库存 / 组合节点接过来的别的组合体也在引用它时，
      // 本地拆分一律不动这口池子（退出成员拿空模块，由 ComboNet 按网络重新分配）。
      // 否则就是从核心库存里搬东西给退出的工厂 —— 用户报的"拆工厂时核心里的东西全没了"。
      boolean liquidPoolOurs = !ComboReflect.liquidPoolSharedOutside(poolLiquids, oldGroup);
      for (CombinedLiquidTurretBuild b : oldGroup) {
          if (!b.isValid()) continue;
          if (liquidPoolOurs && poolLiquids != null && b.liquids != null && b.liquids != poolLiquids) {
              for (Liquid liquid : content.liquids()) {
                  float amt = b.liquids.get(liquid);
                  if (amt > 0.001f) { poolLiquids.add(liquid, amt); b.liquids.remove(liquid, amt); }
              }
          }
      }
      CombinedLiquidTurretBuild oldLeader = newLeader;
      LiquidModule oldLiquids = poolLiquids;
      Seq<CombinedLiquidTurretBuild> kicked = new Seq<>();
      for (CombinedLiquidTurretBuild b : oldGroup)
        if (b.isValid() && !newGroup.contains(b))
          kicked.add(b);
      if (kicked.isEmpty())
        return;

      float oldTotalLiqCap = 0f;
      for (CombinedLiquidTurretBuild b : oldGroup)
        if (b.isValid())
          oldTotalLiqCap += ((CombinedLiquidTurret) b.block).baseLiquidCapacity;
      float[] liquidCaps = new float[kicked.size];
      float kickedTotalLiqCap = 0f;
      for (int i = 0; i < kicked.size; i++) {
        CombinedLiquidTurretBuild b = kicked.get(i);
        liquidCaps[i] = ((CombinedLiquidTurret) b.block).baseLiquidCapacity;
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
        for (CombinedLiquidTurretBuild b : newGroup)
          if (b.isValid())
            b.liquids = oldLiquids;
      for (int i = 0; i < kicked.size; i++)
        kicked.get(i).liquids = newLiquidMods[i];
    }

    public void shareModules(CombinedLiquidTurretBuild leader) {
      float totalLiqCap = leader.comboTotalLiquidCap;
      if (leader.liquids == null) {
        for (CombinedLiquidTurretBuild m : group())
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
        for (CombinedLiquidTurretBuild m : group()) {
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
        for (CombinedLiquidTurretBuild m : group())
          if (m.isValid())
            m.liquids = leader.liquids;
    if (leader.liquids != null) leader.liquids.stopFlow(); // 组内搬池子不算流量（见 ComboNet.moveItems）
      }
    }

    /** 组总容量截断（外部按 9999 假容量注入时会超容） */
    public void trimExcessLiquids(CombinedLiquidTurretBuild leader) {
      LiquidModule liq = leader.liquids;
      if (liq == null || leader.comboTotalLiquidCap <= 0.001f)
        return;
      // 每种液体容量上限 = 组总量（与 perLiquidCap 一致，叠加）
      float perCap = leader.perLiquidCap();
      for (Liquid l : content.liquids()) {
        float over = liq.get(l) - perCap;
        if (over > 0.001f)
          liq.remove(l, over);
      }
    }

    // -------------------- 生命周期 --------------------

    @Override
    public void created() {
      super.created();
      comboTotalLiquidCap = ((CombinedLiquidTurret) block).baseLiquidCapacity;
      comboDirty = true;
    }

    @Override
    public void onProximityUpdate() {
      super.onProximityUpdate();
      comboDirty = true;
      for (CombinedLiquidTurretBuild m : group())
        if (m.isValid())
          m.comboDirty = true;
    }

    @Override
    public void onRemoved() {
      Seq<CombinedLiquidTurretBuild> members = new Seq<>(group());
      boolean wasLeader = isLeader();
      LiquidModule oldLiquids = this.liquids;
      // 【别动不属于本组的池子】核心库存/别的组合体也在用的那份模块：既不能按容量
      // "分一份给幸存者"（那是从核心库存里搬东西），也不能复制一份（核心库存会凭空翻倍）。
      boolean liquidPoolOurs = !ComboReflect.liquidPoolSharedOutside(oldLiquids, members);
      if (!wasLeader && oldLiquids != null) {
        boolean shared = false;
        for (CombinedLiquidTurretBuild m : members)
          if (m != this && m.isValid() && m.liquids == oldLiquids) {
            shared = true;
            break;
          }
        if (shared)
          liquids = new LiquidModule(); // 先脱钩，避免共享池被空模块覆盖
      }
      if (wasLeader) {
        Seq<CombinedLiquidTurretBuild> survivors = new Seq<>();
        for (CombinedLiquidTurretBuild b : members)
          if (b != this && b.isValid())
            survivors.add(b);
        float[] liquidCaps = new float[survivors.size];
        float totalLiqCap = 0f;
        for (int i = 0; i < survivors.size; i++) {
          CombinedLiquidTurretBuild b = survivors.get(i);
          liquidCaps[i] = ((CombinedLiquidTurret) b.block).baseLiquidCapacity;
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
          CombinedLiquidTurretBuild b = survivors.get(i);
          b.liquids = liquidMods[i];
          b.comboLeader = null;
          b.comboGroup = new Seq<>();
          b.comboDirty = true;
          b.comboTotalLiquidCap = 0f;
        }
      } else {
        CombinedLiquidTurretBuild l = leader();
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
      // 绘制期间按"每台"替换 drawer.liquidDraw 与 block.liquidCapacity：
      // DrawTurret 的液体段会画 liquidDraw 指定的液体、按 block.liquidCapacity 算填充度，
      // 位置/层级/绘制顺序（本体与 top 之间）完全沿用原版，颜色/余量以 effectiveLiquid 为准。
      // 渲染线程内顺序执行，两个字段画完即恢复（与 dumpLiquid 换容量同款手法）。
      mindustry.world.draw.DrawTurret dturret =
          ((mindustry.world.blocks.defense.turrets.Turret) block).drawer instanceof mindustry.world.draw.DrawTurret d
              ? d
              : null;
      float oldCap = block.liquidCapacity;
      Liquid oldDraw = dturret != null ? dturret.liquidDraw : null;
      Liquid eff = effectiveLiquid();
      if (dturret != null)
        dturret.liquidDraw = eff != null ? eff : oldDraw;
      // 填充度 = 选中液体余量 / 组合后的液体容量（组总量）
      block.liquidCapacity = Math.max(perLiquidCap(), 1f);
      try {
        super.draw();
      } finally {
        block.liquidCapacity = oldCap;
        if (dturret != null)
          dturret.liquidDraw = oldDraw;
      }
    }


    

    // ===== 弹药判定: 液体炮台不使用物品弹药序列, 必须按液体池判定 =====
    @Override
    public boolean hasAmmo() {
      // FIX[罢工]: 原版 LiquidTurretBuild.hasAmmo 基于 liquids; 误查恒空的 ammo 序列
      // 会永远返回 false, 炮台永远不开火
      if (liquids == null)
        return false;
      Liquid eff = effectiveLiquid();
      if (eff == null)
        return false;
      BulletType type = ammoTypes.get(eff);
      return type != null && liquids.get(eff) >= 1f / type.ammoMultiplier;
    }

    @Override
    public float ammoReloadMultiplier() {
      BulletType type = ammoTypes.get(effectiveLiquid());
      return type == null ? 1f : type.reloadMultiplier;
    }

    @Override
    public void updateTile() {
      if (isLeader() && comboDirty)
        rebuildCombo();
      if (isLeader())
        trimExcessLiquids(leader());
      super.updateTile();
      // remove() 内部走 add(-x) 会把 current 拉回被消耗的旧液体，
      // 每帧把池子 current 对齐为实际发射液体，保证贴图颜色与弹药指向一致
      if (liquids != null && fLiquidCurrent != null) {
        Liquid eff = effectiveLiquid();
        if (liquids.current() != eff) {
          try {
            fLiquidCurrent.set(liquids, eff);
          } catch (Exception ignored) {
          }
        }
      }
    }

    // ---- 弹药指向 effectiveLiquid ----

    @Override
    public BulletType peekAmmo() {
      return ammoTypes.get(effectiveLiquid());
    }

    

    @Override
    public BulletType useAmmo() {
      if (cheating())
        return ammoTypes.get(effectiveLiquid());
      BulletType type = ammoTypes.get(effectiveLiquid());
      // 原版从 liquids.current() 扣，这里改为扣 effectiveLiquid，与发射液体一致
      liquids.remove(effectiveLiquid(), 1f / type.ammoMultiplier);
      return type;
    }

    @Override
    public float getAmmoFraction() {
      // 原版除以 block.liquidCapacity（组合版为 9999 假容量），改为组容量
      return liquids == null ? 0f : liquids.get(effectiveLiquid()) / Math.max(comboTotalLiquidCap, 1f);
    }

    @Override
    public mindustry.ctype.UnlockableContent getAmmoContent() {
      return liquids != null && liquids.get(effectiveLiquid()) > 0.001f ? effectiveLiquid() : null;
    }

    @Override
    public Object senseObject(LAccess sensor) {
      if (sensor == LAccess.currentAmmoType)
        return effectiveLiquid();
      return super.senseObject(sensor);
    }

    // ---- 液体选择 UI：池内存在两种及以上液体时显示 ----

    @Override
    public void buildConfiguration(Table table) {
      Seq<Liquid> present = new Seq<>();
      for (Liquid l : content.liquids())
        if (liquids.get(l) > 0.001f)
          present.add(l);
      if (present.size < 2)
        return;
      ItemSelection.buildTable(CombinedLiquidTurret.this, table, present,
          () -> selected, l -> configure(l));
    }

    @Override
    public Object config() {
      return selected;
    }

    // ---- selected 序列化（基线 TurretBuild.version()=1，故用 2）----

    // 地图区里只写"原版液体炮台那一份字节"（super.write = TurretBuild/LiquidTurretBuild 的布局），
    // 模组自己的字段（选中的弹药液体）挪到自定义存档块 ComboSaveState —— 详见 ComboSaved。
    @Override
    public byte version() {
      return combine.saves.ComboSaveState.vanillaVersion(block);
    }

    @Override
    public void write(Writes write) {
      super.write(write);
    }

    @Override
    public void writeCombo(Writes write) {
      write.s(selected == null ? -1 : selected.id);
    }

    @Override
    public void read(Reads read, byte revision) {
      super.read(read, revision);
      // 旧档（≤2.6）：模组字段直接续写在地图区里
      if (revision >= 10) {
        readCombo(read, revision);
        return;
      }
    }

    @Override
    public void readCombo(Reads read, byte revision) {
      short id = read.s();
      selected = id == -1 ? null : content.liquid(id);
    }

    // -------------------- 液体交互 --------------------

    @Override
    public boolean acceptLiquid(Building source, Liquid liquid) {
      if (!block.hasLiquids)
        return false;
      // 输入限制：只接受本炮塔的弹药液体（非弹药液体一律拒之门外）
      if (!((CombinedLiquidTurret) block).ammoTypes.containsKey(liquid))
        return false;
      boolean needed = ComboReflect.groupConsumesLiquid(this, liquid);
      // 每种液体独立容量（各 = 组总量）：该液体自身未满即收
      return needed && liquids != null
          && liquids.get(liquid) < perLiquidCap() - 0.001f;
    }

    @Override
    public void handleLiquid(Building source, Liquid liquid, float amount) {
      if (amount <= 0.001f)
        return;
      // 每种液体独立容量（各 = 组总量），与该液体自身比较；超量退回源端
      float actual = Math.min(amount, Math.max(0f, perLiquidCap() - liquids.get(liquid)));
      if (actual > 0.001f) {
        liquids.add(liquid, actual);
        lastInput = liquid;
      }
      // 未接收部分退回源端，防止源端因 9999 假容量被过度扣除
      float refund = amount - actual;
      if (refund > 0.001f && source != null && source.liquids != null)
        source.liquids.add(liquid, refund);
    }

    // -------------------- 显示 --------------------

    @Override
    public void display(Table table) {
      // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
      ComboUi.safe("combinedliquidturret:display", () -> displayInner(table));
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
          String title = count > 1 ? "[accent]组合液体炮塔[] x" + count + "\n" + block.getDisplayName(tile)
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
      // 共享液体池状态
      LiquidModule liq = liquids;
      if (liq == null) {
        CombinedLiquidTurretBuild l = leader();
        if (l != null)
          liq = l.liquids;
      }
      if (liq != null) {
        for (Liquid l : content.liquids()) {
          float amt = liq.get(l);
          if (amt > 0.001f) {
            table.add("[lightgray]" + l.localizedName + ":[] " + Strings.fixed(amt, 1)
                + "/" + Strings.fixed(perLiquidCap(), 1)).left();
            table.row();
          }
        }
      }
    }
  }
}
