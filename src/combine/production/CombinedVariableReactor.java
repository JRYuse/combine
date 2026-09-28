package combine.production;

import combine.net.ComboNet;
import combine.util.ComboReflect;
import combine.util.ComboUi;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.Building;
import mindustry.type.Liquid;
import mindustry.world.blocks.heat.HeatBlock;
import mindustry.world.blocks.power.VariableReactor;
import mindustry.world.modules.LiquidModule;

/**
 * 组合通量反应堆（原版 {@code VariableReactor} / flux-reactor）。
 *
 * <p>原版通量反应堆一直没被组合工厂接管（它 extends PowerGenerator，不属于 ConsumeGenerator/
 * Impact/Nuclear/Heater 任何一类），所以"通量反应堆无法组合"（用户报的）。
 *
 * <p>这里直接继承原版 {@code VariableReactor}，把原版那一份 {@code write/read/version}
 * 原样继承下来 —— 关掉模组后存档仍能被原版通量反应堆正确读取。组合语义：
 * 相邻同型（或允许的跨型）共用一份氰气液池（组液容 = Σ 单台），并把整组直接相邻的
 * 外部热源 + 跨距离组合网络热量汇成一份共享需热池（不再只有贴着产热机那一台有热）。
 */
public class CombinedVariableReactor extends VariableReactor {
  public boolean allowCrossTypeCombo = false;
  /** 单台液容（init 第一次记下，之后 liquidCapacity 被抬成 9999 假容量）。 */
  public float baseLiquidCapacity = 30f;

  public CombinedVariableReactor(String name) {
    super(name);
    update = true;
    solid = true;
    sync = true;
    conductivePower = true;
  }

  @Override
  public void init() {
    super.init();
    if (ComboReflect.captureBaseLiquidCapOnce(this))
      baseLiquidCapacity = liquidCapacity;
    liquidCapacity = 9999f;
    hasLiquids = true;
    conductivePower = true;
  }

  public class CombinedVariableReactorBuild extends VariableReactorBuild
      implements combine.saves.ComboSaved {
    public CombinedVariableReactorBuild comboLeader;
    public Seq<CombinedVariableReactorBuild> comboGroup = new Seq<>();
    public boolean comboDirty = true;
    public float comboTotalLiquidCap = 0f;
    public int pendingLeaderPos = -1;
    /** 整组可得的共享热量池（leader 上每 tick 算一次）。 */
    public float comboPooledHeat = 0f;
    public float lastPooledHeatTime = -1f;

    public CombinedVariableReactorBuild leader() {
      if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null)) {
        comboLeader = null;
        comboDirty = true;
      }
      return comboLeader == null ? this : comboLeader;
    }

    public boolean isLeader() {
      return leader() == this;
    }

    public Seq<CombinedVariableReactorBuild> group() {
      CombinedVariableReactorBuild l = leader();
      if (l.comboGroup == null)
        l.comboGroup = new Seq<>();
      return l.comboGroup;
    }

    // -------------------- 组合重建 --------------------
    public void rebuildCombo() {
      Seq<CombinedVariableReactorBuild> oldGroup = comboGroup != null ? new Seq<>(comboGroup) : new Seq<>();
      comboGroup = new Seq<>();
      comboGroup.add(this);
      for (Building b : ComboReflect.linkedReachable(this,
          o -> o instanceof CombinedVariableReactorBuild other && other.team == team && other.isValid(),
          (cur, o) -> cur.block == o.block
              || ((CombinedVariableReactor) cur.block).allowCrossTypeCombo
              || ((CombinedVariableReactor) o.block).allowCrossTypeCombo)) {
        if (b != this)
          comboGroup.add((CombinedVariableReactorBuild) b);
      }
      CombinedVariableReactorBuild newLeader = this;
      for (CombinedVariableReactorBuild b : comboGroup)
        if (b.isValid() && b.pos() < newLeader.pos())
          newLeader = b;
      Seq<CombinedVariableReactorBuild> newGroup = new Seq<>(comboGroup);
      newLeader.comboGroup = newGroup;
      for (CombinedVariableReactorBuild b : newGroup) {
        if (b.isValid()) {
          b.comboLeader = newLeader;
          b.comboGroup = newGroup;
          b.comboDirty = false;
        }
      }
      newLeader.comboLeader = null;

      float totalLiquidCap = 0f;
      for (CombinedVariableReactorBuild b : newGroup)
        if (b.isValid())
          totalLiquidCap += ((CombinedVariableReactor) b.block).baseLiquidCapacity;
      for (CombinedVariableReactorBuild b : newGroup)
        if (b.isValid())
          b.comboTotalLiquidCap = totalLiquidCap;

      shareLiquids(newLeader);

      for (CombinedVariableReactorBuild old : oldGroup) {
        if (old != this && old.isValid() && !newGroup.contains(old)) {
          old.comboLeader = null;
          old.comboGroup = new Seq<>();
          old.comboDirty = true;
          old.comboTotalLiquidCap = 0f;
        }
      }
    }

    /** 整组共用一份 liquids：加入时全额并入组长那一份，之后所有成员都指向它。 */
    public void shareLiquids(CombinedVariableReactorBuild leader) {
      if (leader.liquids == null) {
        for (CombinedVariableReactorBuild m : group())
          if (m.liquids != null) {
            leader.liquids = m.liquids;
            break;
          }
      }
      ObjectSet<LiquidModule> shared = ComboReflect.liquidPoolsSharedOutside(group());
      if (!shared.isEmpty())
        leader.liquids = shared.first();
      ObjectSet<LiquidModule> processed = new ObjectSet<>();
      if (leader.liquids != null) {
        processed.add(leader.liquids);
        for (CombinedVariableReactorBuild m : group()) {
          if (m != leader && m.isValid() && m.liquids != null && !processed.contains(m.liquids)
              && !shared.contains(m.liquids)) {
            processed.add(m.liquids);
            for (Liquid liquid : mindustry.Vars.content.liquids()) {
              float amt = m.liquids.get(liquid);
              if (amt > 0f)
                leader.liquids.add(liquid, amt);
            }
          }
        }
        for (CombinedVariableReactorBuild m : group())
          if (m.isValid())
            m.liquids = leader.liquids;
        leader.liquids.stopFlow();
      }
    }

    // -------------------- 生命周期 --------------------
    @Override
    public void created() {
      super.created();
      comboDirty = true;
    }

    @Override
    public void onProximityUpdate() {
      super.onProximityUpdate();
      comboDirty = true;
      for (CombinedVariableReactorBuild m : group())
        if (m.isValid())
          m.comboDirty = true;
    }

    @Override
    public void onRemoved() {
      // 共享池对象由幸存者继续引用，删掉本台不会丢池子；只把编组标脏让邻居重算。
      for (CombinedVariableReactorBuild m : new Seq<>(group()))
        if (m != this && m.isValid())
          m.comboDirty = true;
      comboLeader = null;
      comboGroup = new Seq<>();
      comboDirty = false;
      comboTotalLiquidCap = 0f;
      super.onRemoved();
    }

    // -------------------- 核心更新 --------------------
    @Override
    public boolean shouldAmbientSound() {
      return false; // 组合建筑统一禁用环境音（MindustryX 音频线程安全问题）
    }

    @Override
    public void updateTile() {
      if (!combine.util.ComboTeams.playerTeam(team)) {
        super.updateTile();
        return;
      }
      if (pendingLeaderPos != -1) {
        Building b = mindustry.Vars.world.build(pendingLeaderPos);
        if (b instanceof CombinedVariableReactorBuild leaderBuild && leaderBuild.isValid()
            && leaderBuild.team == team) {
          comboLeader = leaderBuild;
          if (leaderBuild.liquids != null)
            liquids = leaderBuild.liquids;
        } else {
          comboLeader = null;
        }
        pendingLeaderPos = -1;
        comboDirty = true;
      }
      if (isLeader() && comboDirty)
        rebuildCombo();

      // 原版逻辑：heat = calculateHeat(贴着的邻居)、warmup、instability 等
      super.updateTile();
      // 组合共享热量：把整组的外部热源 + 网络热量并到本台 heat 上
      heat = Math.max(heat, pooledHeatAvailable());
    }

    /** 整组可得的需热池（口径同 CombinedCrafter.availableHeat / CombinedTurret.pooledHeatAvailable）。 */
    public float pooledHeatAvailable() {
      CombinedVariableReactorBuild l = leader();
      if (l.lastPooledHeatTime != Time.time) {
        l.lastPooledHeatTime = Time.time;
        float sum = 0f;
        ObjectSet<Object> counted = new ObjectSet<>();
        for (CombinedVariableReactorBuild member : l.group()) {
          if (!member.isValid())
            continue;
          for (Building b : member.proximity) {
            if (b == null || !b.isValid() || b.team != team || b == this)
              continue;
            if (b instanceof combine.production.CombinedGenerator.CombinedGeneratorBuild gb) {
              if (counted.add(gb.leader()))
                sum += gb.getComboHeat();
            } else if (b instanceof CombinedCrafter.CombinedCrafterBuild other) {
              CombinedCrafter ob = (CombinedCrafter) other.block;
              if (ob.mode == CombinedCrafter.Mode.heatproducer) {
                if (counted.add(other.leader()))
                  for (CombinedCrafter.CombinedCrafterBuild p : other.group())
                    if (p.isValid())
                      sum += p.producerHeat;
              } else if (ob.heatOutput > 0) {
                if (counted.add(other.leader()))
                  sum += other.getComboHeat();
              }
            } else if (b instanceof HeatBlock hb
                && !(b instanceof CombinedCrafter.CombinedCrafterBuild)
                && !(b instanceof combine.production.CombinedGenerator.CombinedGeneratorBuild)) {
              sum += hb.heat();
            }
          }
        }
        sum += ComboNet.heatFor(l);
        l.comboPooledHeat = sum;
      }
      return l.comboPooledHeat;
    }

    // -------------------- 液体交互 --------------------
    @Override
    public boolean acceptLiquid(Building source, Liquid liquid) {
      if (!block.hasLiquids || liquid == null)
        return false;
      return block.consumesLiquid(liquid)
          && liquids != null && liquids.get(liquid) < Math.max(comboTotalLiquidCap, 1f) - 0.01f;
    }

    @Override
    public void handleLiquid(Building source, Liquid liquid, float amount) {
      if (amount <= 0.001f)
        return;
      float room = Math.max(comboTotalLiquidCap, 0f) - liquids.get(liquid);
      if (room <= 0f)
        return;
      super.handleLiquid(source, liquid, Math.min(amount, room));
    }

    @Override
    public void dumpLiquid(Liquid liquid, float scaling, int outputDir) {
      float oldCap = block.liquidCapacity;
      block.liquidCapacity = Math.max(comboTotalLiquidCap, 1f);
      super.dumpLiquid(liquid, scaling, outputDir);
      block.liquidCapacity = oldCap;
    }

    // -------------------- 显示 --------------------
    @Override
    public void display(arc.scene.ui.layout.Table table) {
      // 原版显示 + 组合构成行（面板异常绝不外抛）
      try {
        super.display(table);
        if (group().size > 1)
          ComboUi.addComposition(table, this, group().size);
      } catch (Throwable ignored) {
      }
    }

    // -------------------- 序列化 --------------------
    // 地图区里只写原版通量反应堆那一份字节（继承 VariableReactorBuild.write）——
    // 关掉模组后原版按同一版本号读得回去。
    @Override
    public byte version() {
      return combine.saves.ComboSaveState.vanillaVersion(block);
    }

    @Override
    public void writeBase(Writes write) {
      LiquidModule saved = liquids;
      if (!combine.saves.ComboSaveState.firstLiquidPool(this) && liquids != null)
        liquids = new LiquidModule();
      super.writeBase(write);
      liquids = saved;
    }

    @Override
    public void writeCombo(Writes write) {
      Building leader = combine.saves.ComboSaveState.trueLeader(this, comboGroup);
      write.bool(leader != this);
      if (leader != this)
        write.i(leader.pos());
    }

    @Override
    public void readCombo(Reads read, byte revision) {
      boolean hasLeader = read.bool();
      int leaderPos = hasLeader ? read.i() : -1;
      comboDirty = true;
      if (hasLeader && leaderPos != pos())
        pendingLeaderPos = leaderPos;
      else
        pendingLeaderPos = -1;
    }

    @Override
    public void read(Reads read, byte revision) {
      super.read(read, revision);
      comboDirty = true;
    }
  }
}
