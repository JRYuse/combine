package combine;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.struct.IntSet;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import arc.util.Tmp;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.entities.Damage;
import mindustry.game.EventType.GeneratorPressureExplodeEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.graphics.Drawf;
import mindustry.graphics.Pal;
import mindustry.logic.LAccess;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.ui.ReqImage;
import mindustry.world.Block;
import mindustry.world.blocks.heat.HeatBlock;
import mindustry.world.blocks.power.ConsumeGenerator;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItemFilter;
import mindustry.world.consumers.ConsumeItems;
import mindustry.world.consumers.ConsumeLiquid;
import mindustry.world.consumers.ConsumeLiquids;
import mindustry.world.consumers.ConsumePower;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.*;
import static mindustry.Vars.iconMed;

/**
 * 组合发电机 —— 四模式统一版
 * 模式：
 * 1. consume : 普通消耗型发电机（原版 ConsumeGenerator 行为）
 * 2. impact : 冲击反应堆（原版 ImpactReactor 行为）
 * 3. nuclear : 核反应堆（原版 NuclearReactor 行为）
 * 4. heater : 热力发电机（原版 HeaterGenerator 行为）
 * 相邻放置自动形成组合体、共享 items/liquids 输入池。
 */
public class CombinedGenerator extends ConsumeGenerator {

  public enum Mode {
    consume,
    impact,
    nuclear,
    heater
  }

  /** 当前发电机模式 */
  public Mode mode = Mode.consume;

  // ==================== Impact 模式字段 ====================
  public float impactWarmupSpeed = 0.001f;
  public float impactItemDuration = 60f;

  // ==================== Nuclear 模式字段 ====================
  public Color nuclearLightColor = Color.valueOf("7f19ea");
  public Color nuclearCoolColor = new Color(1, 1, 1, 0f);
  public Color nuclearHotColor = Color.valueOf("ff9575a3");
  public float nuclearHeating = 0.01f;
  public float nuclearHeatOutput = 12f;
  public float nuclearHeatWarmupRate = 1f;
  public float nuclearHeatConsumeRate = 10f;
  public float nuclearAmbientCooldown = 60f * 20f;
  public float nuclearSmokeThreshold = 0.3f;
  public float nuclearFlashThreshold = 0.46f;
  public float nuclearCoolantPower = 0.5f;
  public Item nuclearFuelItem = Items.thorium;

  // ==================== Heater 模式字段 ====================
  public float heaterHeatOutput = 10f;
  public float heaterWarmupRate = 0.15f;

  // ==================== 组合体通用字段 ====================
  public boolean allowCrossTypeCombo = true;
  public float itemCapacityMultiplier = 1f;
  public float liquidCapacityMultiplier = 1f;
  public float baseLiquidCapacity = 10f;
  public float displayLiquid;

  public Seq<Item> cachedItems = new Seq<>();
  public Seq<Liquid> cachedLiquids = new Seq<>();

  // 计时器（ConsumeGenerator 没有 timerUse）
  public final int timerUse = timers++;

  // 纹理（手动加载）
  public TextureRegion topRegion, lightsRegion;

  public CombinedGenerator(String name) {
    super(name);
    conductivePower = true;
    update = true;
    solid = true;
    hasItems = true;
    hasLiquids = true;
    sync = true;
  }

  @Override
  public void load() {
    super.load();
    topRegion = Core.atlas.find(name + "-top");
    lightsRegion = Core.atlas.find(name + "-lights");
  }

  @Override
  public void init() {
    super.init();

    itemCapacity = Math.max(1, (int) (itemCapacity * itemCapacityMultiplier));
    baseLiquidCapacity = Math.max(1f, liquidCapacity * liquidCapacityMultiplier);
    displayLiquid = baseLiquidCapacity;
    liquidCapacity = 9999f;
    if (!hasLiquids)
      displayLiquid = 0;
    hasLiquids = true;
    conductivePower = true;
    hasItems = true;

    // 缓存输入物品 / 液体（用于显示与分裂资产）
    cachedItems.clear();
    cachedLiquids.clear();
    if (consumers != null) {
      for (Consume cons : consumers) {
        if (cons instanceof ConsumeItems ci) {
          for (var stack : ci.items) {
            if (!cachedItems.contains(stack.item))
              cachedItems.add(stack.item);
          }
        } else if (cons instanceof ConsumeLiquid cl) {
          if (!cachedLiquids.contains(cl.liquid))
            cachedLiquids.add(cl.liquid);
        } else if (cons instanceof ConsumeLiquids cls) {
          for (var stack : cls.liquids) {
            if (!cachedLiquids.contains(stack.liquid))
              cachedLiquids.add(stack.liquid);
          }
        }
      }
    }
    if (outputLiquid != null && !cachedLiquids.contains(outputLiquid.liquid)) {
      cachedLiquids.add(outputLiquid.liquid);
    }
  }

  @Override
  public void setStats() {
    stats.timePeriod = itemDuration;
    if (mode == Mode.impact) {
      stats.timePeriod = impactItemDuration;
    }
    super.setStats();

    if (hasItems && mode != Mode.impact) {
      stats.add(Stat.productionTime, itemDuration / 60f, StatUnit.seconds);
    } else if (hasItems && mode == Mode.impact) {
      stats.add(Stat.productionTime, impactItemDuration / 60f, StatUnit.seconds);
    }

    if (mode == Mode.heater) {
      stats.add(Stat.output, heaterHeatOutput, StatUnit.heatUnits);
    }

    stats.remove(Stat.liquidCapacity);
    stats.add(Stat.liquidCapacity, displayLiquid, StatUnit.liquidUnits);
  }

  @Override
  public void setBars() {
    super.setBars();
    if (mode == Mode.nuclear) {
      addBar("heat", (CombinedGeneratorBuild e) -> new Bar("bar.heat", Pal.lightOrange, () -> e.nuclearHeat));
    } else if (mode == Mode.heater) {
      addBar("heat",
          (CombinedGeneratorBuild e) -> new Bar("bar.heat", Pal.lightOrange,
              () -> e.heaterHeat / heaterHeatOutput));
    } else if (mode == Mode.impact) {
      addBar("warmup", (CombinedGeneratorBuild e) -> new Bar(() -> Core.bundle.get("bar.warmup"), () -> Pal.accent,
          () -> e.impactWarmup));
    }
  }

  @Override
  public TextureRegion[] icons() {
    // nuclear 模式需要 topRegion 绘制冷却液覆盖层；其他模式不需要
    if (mode == Mode.nuclear && topRegion != null && topRegion.found()) {
      return new TextureRegion[] { region, topRegion };
    }
    return new TextureRegion[] { region };
  }

  // ==================== Building ====================

  public class CombinedGeneratorBuild extends ConsumeGeneratorBuild implements HeatBlock {

    // ---- 组合体状态 ----
    public CombinedGeneratorBuild comboLeader;
    public Seq<CombinedGeneratorBuild> comboGroup = new Seq<>();
    public boolean comboDirty = true;
    public float comboTotalLiquidCap = 0f;
    public int comboTotalItemCap = 0;
    public int pendingLeaderPos = -1;

    // ---- Impact 模式状态 ----
    public float impactWarmup;
    public float impactTotalProgress;

    // ---- Nuclear 模式状态 ----
    public float nuclearHeat;
    public float nuclearHeatProgress;
    public float nuclearFlash;
    public float nuclearSmoothLight;

    // ---- Heater 模式状态 ----
    public float heaterHeat;

    float comboLiquidCapacity() {
      return Math.max(comboTotalLiquidCap, 1f);
    }

    public CombinedGeneratorBuild leader() {
      if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null)) {
        comboLeader = null;
      }
      return comboLeader == null ? this : comboLeader;
    }

    public boolean isLeader() {
      return leader() == this;
    }

    public Seq<CombinedGeneratorBuild> group() {
      CombinedGeneratorBuild l = leader();
      if (l.comboGroup == null)
        l.comboGroup = new Seq<>();
      return l.comboGroup;
    }

    // -------------------- 组合重建 --------------------
    public void rebuildCombo() {
      Seq<CombinedGeneratorBuild> oldGroup = comboGroup != null ? new Seq<>(comboGroup) : new Seq<>();

      comboGroup = new Seq<>();
      comboGroup.add(this);

      IntSet visited = new IntSet();
      Queue<CombinedGeneratorBuild> queue = new Queue<>();
      queue.add(this);
      visited.add(pos());

      while (!queue.isEmpty()) {
        CombinedGeneratorBuild current = queue.removeFirst();
        for (Building b : current.proximity) {
          if (b instanceof CombinedGeneratorBuild other && other.team == team && other.isValid()
              && !visited.contains(other.pos())) {
            CombinedGenerator cb = (CombinedGenerator) current.block;
            CombinedGenerator ob = (CombinedGenerator) other.block;
            boolean canCombo = (current.block == other.block)
                || cb.allowCrossTypeCombo || ob.allowCrossTypeCombo;
            if (canCombo) {
              visited.add(other.pos());
              queue.addLast(other);
              comboGroup.add(other);
            }
          }
        }
      }

      CombinedGeneratorBuild newLeader = this;
      for (CombinedGeneratorBuild b : comboGroup) {
        if (b.isValid() && b.pos() < newLeader.pos())
          newLeader = b;
      }

      Seq<CombinedGeneratorBuild> newGroup = new Seq<>(comboGroup);
      newLeader.comboGroup = newGroup;

      for (CombinedGeneratorBuild b : newGroup) {
        if (b.isValid()) {
          b.comboLeader = newLeader;
          b.comboGroup = newGroup;
          b.comboDirty = false;
        }
      }
      newLeader.comboLeader = null;

      float totalLiqCap = 0f;
      int totalItemCap = 0;
      for (CombinedGeneratorBuild b : newGroup) {
        if (b.isValid()) {
          totalLiqCap += ((CombinedGenerator) b.block).baseLiquidCapacity;
          totalItemCap += b.block.itemCapacity;
        }
      }
      for (CombinedGeneratorBuild b : newGroup) {
        if (b.isValid()) {
          b.comboTotalLiquidCap = totalLiqCap;
          b.comboTotalItemCap = totalItemCap;
        }
      }

      if (oldGroup.size > newGroup.size) {
        splitAssets(oldGroup, newGroup);
      }

      shareModules(newLeader);

      if (newLeader.items != null && totalItemCap > 0) {
        int excess = newLeader.items.total() - totalItemCap;
        if (excess > 0) {
          for (Item item : cachedItems) {
            int amt = newLeader.items.get(item);
            if (amt > 0) {
              int remove = Math.min(amt, excess);
              newLeader.items.remove(item, remove);
              excess -= remove;
              if (excess <= 0)
                break;
            }
          }
        }
      }
      if (newLeader.liquids != null && totalLiqCap > 0.001f) {
        float excess = newLeader.liquids.currentAmount() - totalLiqCap;
        if (excess > 0.001f) {
          for (Liquid liquid : cachedLiquids) {
            float amt = newLeader.liquids.get(liquid);
            if (amt > 0.001f) {
              float remove = Math.min(amt, excess);
              newLeader.liquids.remove(liquid, remove);
              excess -= remove;
              if (excess <= 0.001f)
                break;
            }
          }
        }
      }

      for (CombinedGeneratorBuild oldMember : oldGroup) {
        if (oldMember != this && oldMember.isValid() && !newGroup.contains(oldMember)) {
          oldMember.comboLeader = null;
          oldMember.comboGroup = new Seq<>();
          oldMember.comboDirty = true;
          oldMember.comboTotalLiquidCap = 0f;
          oldMember.comboTotalItemCap = 0;
        }
      }
    }

    public void splitAssets(Seq<CombinedGeneratorBuild> oldGroup, Seq<CombinedGeneratorBuild> newGroup) {
      CombinedGeneratorBuild oldLeader = null;
      for (CombinedGeneratorBuild b : oldGroup) {
        if (!b.isValid())
          continue;
        for (CombinedGeneratorBuild other : oldGroup) {
          if (other != b && other.isValid() && (other.items == b.items || other.liquids == b.liquids)) {
            oldLeader = b;
            break;
          }
        }
        if (oldLeader != null)
          break;
      }
      if (oldLeader == null) {
        for (CombinedGeneratorBuild b : oldGroup) {
          if (b.isValid()) {
            oldLeader = b;
            break;
          }
        }
      }
      if (oldLeader == null)
        oldLeader = this;

      ItemModule oldItems = oldLeader.items;
      LiquidModule oldLiquids = oldLeader.liquids;

      Seq<CombinedGeneratorBuild> kicked = new Seq<>();
      for (CombinedGeneratorBuild b : oldGroup) {
        if (b.isValid() && !newGroup.contains(b))
          kicked.add(b);
      }

      int oldTotalItemCap = 0;
      float oldTotalLiquidCap = 0f;
      for (CombinedGeneratorBuild b : oldGroup) {
        if (b.isValid()) {
          oldTotalItemCap += b.block.itemCapacity;
          oldTotalLiquidCap += ((CombinedGenerator) b.block).baseLiquidCapacity;
        }
      }

      int[] itemCaps = new int[kicked.size];
      float[] liquidCaps = new float[kicked.size];
      int kickedTotalItemCap = 0;
      float kickedTotalLiquidCap = 0f;
      for (int i = 0; i < kicked.size; i++) {
        CombinedGeneratorBuild b = kicked.get(i);
        itemCaps[i] = b.block.itemCapacity;
        liquidCaps[i] = ((CombinedGenerator) b.block).baseLiquidCapacity;
        kickedTotalItemCap += itemCaps[i];
        kickedTotalLiquidCap += liquidCaps[i];
      }

      ItemModule[] newItemMods = new ItemModule[kicked.size];
      LiquidModule[] newLiquidMods = new LiquidModule[kicked.size];
      for (int i = 0; i < kicked.size; i++) {
        newItemMods[i] = new ItemModule();
        newLiquidMods[i] = new LiquidModule();
      }

      if (oldItems != null && oldTotalItemCap > 0 && kickedTotalItemCap > 0) {
        int[] kickedAllocated = new int[kicked.size];
        for (Item item : cachedItems) {
          int total = oldItems.get(item);
          if (total <= 0)
            continue;
          int kickedTotalShare = Math.round(total * (float) kickedTotalItemCap / oldTotalItemCap);
          kickedTotalShare = Math.min(kickedTotalShare, total);
          int remaining = kickedTotalShare;
          for (int i = 0; i < kicked.size; i++) {
            int ideal = (i == kicked.size - 1) ? remaining
                : Math.round(kickedTotalShare * (float) itemCaps[i] / kickedTotalItemCap);
            ideal = Math.min(ideal, remaining);
            int canTake = Math.max(0, itemCaps[i] - kickedAllocated[i]);
            int share = Math.min(ideal, canTake);
            if (share > 0) {
              newItemMods[i].add(item, share);
              kickedAllocated[i] += share;
              remaining -= share;
            }
          }
          oldItems.remove(item, kickedTotalShare - remaining);
        }
      }

      if (oldLiquids != null && oldTotalLiquidCap > 0.001f && kickedTotalLiquidCap > 0.001f) {
        float[] kickedAllocated = new float[kicked.size];
        for (Liquid liquid : cachedLiquids) {
          float total = oldLiquids.get(liquid);
          if (total <= 0.001f)
            continue;
          float kickedTotalShare = total * kickedTotalLiquidCap / oldTotalLiquidCap;
          kickedTotalShare = Math.min(kickedTotalShare, total);
          float remaining = kickedTotalShare;
          for (int i = 0; i < kicked.size; i++) {
            float ideal = (i == kicked.size - 1) ? remaining
                : kickedTotalShare * liquidCaps[i] / kickedTotalLiquidCap;
            ideal = Math.min(ideal, remaining);
            float canTake = Math.max(0f, liquidCaps[i] - kickedAllocated[i]);
            float share = Math.min(ideal, canTake);
            if (share > 0.001f) {
              newLiquidMods[i].add(liquid, share);
              kickedAllocated[i] += share;
              remaining -= share;
            }
          }
          oldLiquids.remove(liquid, kickedTotalShare - remaining);
        }
      }

      if (oldItems != null)
        for (CombinedGeneratorBuild b : newGroup)
          if (b.isValid())
            b.items = oldItems;
      if (oldLiquids != null)
        for (CombinedGeneratorBuild b : newGroup)
          if (b.isValid())
            b.liquids = oldLiquids;
      for (int i = 0; i < kicked.size; i++) {
        CombinedGeneratorBuild b = kicked.get(i);
        b.items = newItemMods[i];
        b.liquids = newLiquidMods[i];
      }
    }

    public void shareModules(CombinedGeneratorBuild leader) {
      int totalItemCap = leader.comboTotalItemCap;
      float totalLiquidCap = leader.comboTotalLiquidCap;

      if (leader.items == null) {
        for (CombinedGeneratorBuild member : group()) {
          if (member.items != null) {
            leader.items = member.items;
            break;
          }
        }
      }
      if (leader.liquids == null) {
        for (CombinedGeneratorBuild member : group()) {
          if (member.liquids != null) {
            leader.liquids = member.liquids;
            break;
          }
        }
      }

      ObjectSet<ItemModule> processedItems = new ObjectSet<>();
      ObjectSet<LiquidModule> processedLiquids = new ObjectSet<>();

      if (leader.items != null) {
        processedItems.add(leader.items);
        for (CombinedGeneratorBuild member : group()) {
          if (member != leader && member.isValid() && member.items != null
              && !processedItems.contains(member.items)) {
            processedItems.add(member.items);
            for (Item item : cachedItems) {
              int amt = member.items.get(item);
              if (amt > 0) {
                int canAccept = Math.max(0, totalItemCap - leader.items.total());
                int transfer = Math.min(amt, canAccept);
                if (transfer > 0)
                  leader.items.add(item, transfer);
              }
            }
          }
        }
        for (CombinedGeneratorBuild member : group()) {
          if (member.isValid())
            member.items = leader.items;
        }
      }

      if (leader.liquids != null) {
        processedLiquids.add(leader.liquids);
        for (CombinedGeneratorBuild member : group()) {
          if (member != leader && member.isValid() && member.liquids != null
              && !processedLiquids.contains(member.liquids)) {
            processedLiquids.add(member.liquids);
            for (Liquid liquid : cachedLiquids) {
              float amt = member.liquids.get(liquid);
              if (amt > 0.001f) {
                float canAccept = Math.max(0f, totalLiquidCap - leader.liquids.currentAmount());
                float transfer = Math.min(amt, canAccept);
                if (transfer > 0.001f)
                  leader.liquids.add(liquid, transfer);
              }
            }
          }
        }
        for (CombinedGeneratorBuild member : group()) {
          if (member.isValid())
            member.liquids = leader.liquids;
        }
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
      for (CombinedGeneratorBuild member : group()) {
        if (member.isValid())
          member.comboDirty = true;
      }
    }

    @Override
    public void onRemoved() {
      Seq<CombinedGeneratorBuild> members = new Seq<>(group());
      boolean wasLeader = isLeader();
      ItemModule oldItems = this.items;
      LiquidModule oldLiquids = this.liquids;

      if (!wasLeader) {
        if (items != null) {
          boolean shared = false;
          for (CombinedGeneratorBuild member : members) {
            if (member != this && member.isValid() && member.items == this.items) {
              shared = true;
              break;
            }
          }
          if (shared)
            items = new ItemModule();
        }
        if (liquids != null) {
          boolean shared = false;
          for (CombinedGeneratorBuild member : members) {
            if (member != this && member.isValid() && member.liquids == this.liquids) {
              shared = true;
              break;
            }
          }
          if (shared)
            liquids = new LiquidModule();
        }
      }

      if (wasLeader) {
        Seq<CombinedGeneratorBuild> survivors = new Seq<>();
        for (CombinedGeneratorBuild b : members)
          if (b != this && b.isValid())
            survivors.add(b);

        int[] itemCaps = new int[survivors.size];
        float[] liquidCaps = new float[survivors.size];
        int totalItemCap = 0;
        float totalLiquidCap = 0f;
        for (int i = 0; i < survivors.size; i++) {
          CombinedGeneratorBuild b = survivors.get(i);
          itemCaps[i] = b.block.itemCapacity;
          liquidCaps[i] = ((CombinedGenerator) b.block).baseLiquidCapacity;
          totalItemCap += itemCaps[i];
          totalLiquidCap += liquidCaps[i];
        }

        ItemModule[] itemMods = new ItemModule[survivors.size];
        LiquidModule[] liquidMods = new LiquidModule[survivors.size];
        for (int i = 0; i < survivors.size; i++) {
          itemMods[i] = new ItemModule();
          liquidMods[i] = new LiquidModule();
        }

        if (oldItems != null && totalItemCap > 0) {
          int[] allocated = new int[survivors.size];
          for (Item item : cachedItems) {
            int total = oldItems.get(item);
            if (total <= 0)
              continue;
            int remaining = total;
            for (int i = 0; i < survivors.size; i++) {
              int ideal = (i == survivors.size - 1) ? remaining
                  : Math.round(total * (float) itemCaps[i] / totalItemCap);
              ideal = Math.min(ideal, remaining);
              int canTake = Math.max(0, itemCaps[i] - allocated[i]);
              int share = Math.min(ideal, canTake);
              if (share > 0) {
                itemMods[i].add(item, share);
                allocated[i] += share;
                remaining -= share;
              }
            }
          }
        }

        if (oldLiquids != null && totalLiquidCap > 0.001f) {
          float[] allocated = new float[survivors.size];
          for (Liquid liquid : cachedLiquids) {
            float total = oldLiquids.get(liquid);
            if (total <= 0.001f)
              continue;
            float remaining = total;
            for (int i = 0; i < survivors.size; i++) {
              float ideal = (i == survivors.size - 1) ? remaining
                  : total * liquidCaps[i] / totalLiquidCap;
              ideal = Math.min(ideal, remaining);
              float canTake = Math.max(0f, liquidCaps[i] - allocated[i]);
              float share = Math.min(ideal, canTake);
              if (share > 0.001f) {
                liquidMods[i].add(liquid, share);
                allocated[i] += share;
                remaining -= share;
              }
            }
          }
        }

        for (int i = 0; i < survivors.size; i++) {
          CombinedGeneratorBuild b = survivors.get(i);
          b.items = itemMods[i];
          b.liquids = liquidMods[i];
          b.comboLeader = null;
          b.comboGroup = new Seq<>();
          b.comboDirty = true;
          b.comboTotalLiquidCap = 0f;
          b.comboTotalItemCap = 0;
        }
      } else {
        CombinedGeneratorBuild leader = leader();
        if (leader != null && leader.isValid() && leader != this)
          leader.comboDirty = true;
      }

      comboLeader = null;
      comboGroup = new Seq<>();
      comboDirty = false;
      comboTotalLiquidCap = 0f;
      comboTotalItemCap = 0;
      super.onRemoved();
    }

    // -------------------- 核心更新 --------------------
    @Override
    public void updateTile() {
      if (pendingLeaderPos != -1) {
        Building b = world.build(pendingLeaderPos);
        if (b instanceof CombinedGeneratorBuild leaderBuild
            && leaderBuild.isValid() && leaderBuild.team == team) {
          comboLeader = leaderBuild;
          if (leaderBuild.items != null)
            items = leaderBuild.items;
          if (leaderBuild.liquids != null)
            liquids = leaderBuild.liquids;
        } else {
          comboLeader = null;
        }
        pendingLeaderPos = -1;
        comboDirty = true;
      }

      if (isLeader() && comboDirty) {
        rebuildCombo();
      }

      if (liquids != null && comboTotalLiquidCap > 0.001f) {
        float excess = liquids.currentAmount() - comboTotalLiquidCap;
        if (excess > 0.001f) {
          for (Liquid l : content.liquids()) {
            float amt = liquids.get(l);
            if (amt > 0.001f) {
              float remove = Math.min(amt, excess);
              liquids.remove(l, remove);
              excess -= remove;
              if (excess <= 0.001f)
                break;
            }
          }
        }
      }

      CombinedGenerator cb = (CombinedGenerator) block;
      switch (cb.mode) {
        case consume:
          updateConsume(cb);
          break;
        case impact:
          updateImpact(cb);
          break;
        case nuclear:
          updateNuclear(cb);
          break;
        case heater:
          updateHeater(cb);
          break;
      }
    }

    // ---- Consume 模式 ----
    private void updateConsume(CombinedGenerator cb) {
      boolean valid = efficiency > 0;

      warmup = Mathf.lerpDelta(warmup, valid ? 1f : 0f, cb.warmupSpeed);
      productionEfficiency = efficiency * efficiencyMultiplier;
      totalTime += warmup * Time.delta;

      if (valid && Mathf.chanceDelta(cb.effectChance)) {
        cb.generateEffect.at(x + Mathf.range(cb.generateEffectRange), y + Mathf.range(cb.generateEffectRange));
      }

      if (filterItem != null && valid && itemDurationMultipliers.size > 0
          && filterItem.getConsumed(this) != null) {
        itemDurationMultiplier = itemDurationMultipliers.get(filterItem.getConsumed(this), 1);
      }

      if (hasItems && valid && generateTime <= 0f) {
        consume();
        cb.consumeEffect.at(x + Mathf.range(cb.generateEffectRange), y + Mathf.range(cb.generateEffectRange));
        generateTime = 1f;
      }

      if (outputLiquid != null) {
        float cap = comboLiquidCapacity();
        float added = Math.min(productionEfficiency * delta() * outputLiquid.amount,
            cap - liquids.get(outputLiquid.liquid));
        liquids.add(outputLiquid.liquid, added);
        dumpLiquid(outputLiquid.liquid);

        if (cb.explodeOnFull && liquids.get(outputLiquid.liquid) >= cap - 0.01f) {
          kill();
          Events.fire(new GeneratorPressureExplodeEvent(this));
        }
      }

      generateTime -= delta() / (cb.itemDuration * itemDurationMultiplier);
    }

    // ---- Impact 模式 ----
    private void updateImpact(CombinedGenerator cb) {
      if (efficiency >= 0.9999f && power.status >= 0.99f) {
        boolean prevOut = consPower != null && getPowerProduction() <= consPower.requestedPower(this);

        impactWarmup = Mathf.lerpDelta(impactWarmup, 1f, cb.impactWarmupSpeed * timeScale);
        if (Mathf.equal(impactWarmup, 1f, 0.001f)) {
          impactWarmup = 1f;
        }

        if (!prevOut && consPower != null && (getPowerProduction() > consPower.requestedPower(this))) {
          Events.fire(Trigger.impactPower);
        }

        if (timer(timerUse, cb.impactItemDuration / timeScale)) {
          consume();
        }
      } else {
        impactWarmup = Mathf.lerpDelta(impactWarmup, 0f, 0.01f);
      }

      impactTotalProgress += impactWarmup * Time.delta;
      productionEfficiency = Mathf.pow(impactWarmup, 5f);
    }

    // ---- Nuclear 模式 ----
    private void updateNuclear(CombinedGenerator cb) {
      int fuel = items.get(cb.nuclearFuelItem);
      float fullness = (float) fuel / Math.max(comboTotalItemCap, 1);
      productionEfficiency = fullness;

      if (fuel > 0 && enabled) {
        nuclearHeat += fullness * cb.nuclearHeating * Math.min(delta(), 4f);

        if (timer(timerUse,
            cb.itemDuration / (timeScale + (nuclearHeat > 0 ? 1f * nuclearHeat * cb.nuclearHeatConsumeRate : 0f)))) {
          consume();
        }
      } else {
        productionEfficiency = 0f;
        nuclearHeat = Math.max(0f, nuclearHeat - Time.delta / cb.nuclearAmbientCooldown);
      }

      if (nuclearHeat > 0) {
        float maxUsed = Math.min(liquids.currentAmount(), nuclearHeat / cb.nuclearCoolantPower);
        nuclearHeat -= maxUsed * cb.nuclearCoolantPower;
        liquids.remove(liquids.current(), maxUsed);
      }

      if (nuclearHeat > cb.nuclearSmokeThreshold) {
        float smoke = 1.0f + (nuclearHeat - cb.nuclearSmokeThreshold)
            / (1f - cb.nuclearSmokeThreshold);
        if (Mathf.chance(smoke / 20.0 * delta())) {
          Fx.reactorsmoke.at(x + Mathf.range(size * tilesize / 2f),
              y + Mathf.range(size * tilesize / 2f));
        }
      }

      nuclearHeat = Mathf.clamp(nuclearHeat);
      nuclearHeatProgress = cb.nuclearHeatOutput > 0f
          ? Mathf.approachDelta(nuclearHeatProgress,
              nuclearHeat * cb.nuclearHeatOutput * ((enabled && productionEfficiency > 0) ? 1f : 0f),
              cb.nuclearHeatWarmupRate * delta())
          : 0f;

      if (nuclearHeat >= 0.999f) {
        Events.fire(Trigger.thoriumReactorOverheat);
        kill();
      }
    }

    // ---- Heater 模式 ----
    private void updateHeater(CombinedGenerator cb) {
      updateConsume(cb);
      heaterHeat = Mathf.approachDelta(heaterHeat, cb.heaterHeatOutput * efficiency, cb.heaterWarmupRate * delta());
    }

    // -------------------- HeatBlock 接口 --------------------
    @Override
    public float heat() {
      CombinedGenerator cb = (CombinedGenerator) block;
      if (cb.mode == Mode.nuclear)
        return nuclearHeatProgress;
      if (cb.mode == Mode.heater)
        return heaterHeat;
      return 0f;
    }

    @Override
    public float heatFrac() {
      CombinedGenerator cb = (CombinedGenerator) block;
      if (cb.mode == Mode.nuclear)
        return nuclearHeatProgress / Math.max(cb.nuclearHeatOutput, 0.001f);
      if (cb.mode == Mode.heater)
        return heaterHeat / Math.max(cb.heaterHeatOutput, 0.001f);
      return 0f;
    }

    // -------------------- 覆盖 Building 方法 --------------------
    @Override
    public float warmup() {
      CombinedGenerator cb = (CombinedGenerator) block;
      if (cb.mode == Mode.impact)
        return impactWarmup;
      return super.warmup();
    }

    @Override
    public float totalProgress() {
      CombinedGenerator cb = (CombinedGenerator) block;
      if (cb.mode == Mode.impact)
        return impactTotalProgress;
      return super.totalProgress();
    }

    @Override
    public boolean shouldExplode() {
      CombinedGenerator cb = (CombinedGenerator) block;
      if (cb.mode == Mode.nuclear) {
        return super.shouldExplode()
            && (items.get(cb.nuclearFuelItem) >= 5 || nuclearHeat >= 0.5f);
      }
      return super.shouldExplode();
    }

    @Override
    public double sense(LAccess sensor) {
      CombinedGenerator cb = (CombinedGenerator) block;
      if (sensor == LAccess.heat) {
        if (cb.mode == Mode.nuclear)
          return nuclearHeat;
        if (cb.mode == Mode.heater)
          return heaterHeat;
      }
      return super.sense(sensor);
    }

    // -------------------- 物品/液体交互 --------------------
    @Override
    public boolean acceptItem(Building source, Item item) {
      if (!block.hasItems)
        return false;
      return block.consumesItem(item) && items.get(item) < getMaximumAccepted(item);
    }

    @Override
    public int getMaximumAccepted(Item item) {
      return Math.max(comboTotalItemCap, 1);
    }

    @Override
    public void handleItem(Building source, Item item) {
      items.add(item, 1);
    }

    @Override
    public boolean acceptLiquid(Building source, Liquid liquid) {
      if (!block.hasLiquids)
        return false;
      return block.consumesLiquid(liquid) && liquids.get(liquid) < comboTotalLiquidCap - 0.001f;
    }

    // -------------------- 液体输出覆盖 --------------------
    @Override
    public void dumpLiquid(Liquid liquid, float scaling, int outputDir) {
      int dump = this.cdump;
      if (liquids.get(liquid) <= 0.0001f)
        return;
      for (int i = 0; i < proximity.size; i++) {
        incrementDump(proximity.size);
        Building other = proximity.get((i + dump) % proximity.size);
        if (outputDir != -1 && (outputDir + rotation) % 4 != relativeTo(other))
          continue;
        other = other.getLiquidDestination(self(), liquid);
        if (other != null && other.block.hasLiquids && canDumpLiquid(other, liquid) && other.liquids != null) {
          float ofract = other.liquids.get(liquid) / Math.max(other.block.liquidCapacity, 1f);
          float fract = liquids.get(liquid) / Math.max(comboTotalLiquidCap, 1f);
          if (ofract < fract) {
            transferLiquid(other, (fract - ofract) * Math.max(comboTotalLiquidCap, 1f) / scaling, liquid);
          }
        }
      }
    }

    @Override
    public void transferLiquid(Building next, float amount, Liquid liquid) {
      float flow = Math.min(Math.max(next.block.liquidCapacity - next.liquids.get(liquid), 0f), amount);
      if (next.acceptLiquid(self(), liquid)) {
        next.handleLiquid(self(), liquid, flow);
        liquids.remove(liquid, flow);
      }
    }

    @Override
    public void handleLiquid(Building source, Liquid liquid, float amount) {
      if (amount <= 0.001f)
        return;
      float current = liquids.get(liquid);
      float canAccept = Math.max(0f, comboTotalLiquidCap - current);
      float actual = Math.min(amount, canAccept);
      if (actual > 0.001f)
        liquids.add(liquid, actual);
    }

    // -------------------- 绘制 --------------------
    @Override
    public void draw() {
      super.draw();
      CombinedGenerator cb = (CombinedGenerator) block;
      if (cb.mode == Mode.nuclear) {
        drawNuclear(cb);
      }
    }

    void drawNuclear(CombinedGenerator cb) {
      Draw.color(cb.nuclearCoolColor, cb.nuclearHotColor, nuclearHeat);
      Fill.rect(x, y, size * tilesize, size * tilesize);

      Draw.color(liquids.current().color);
      Draw.alpha(liquids.currentAmount() / Math.max(comboTotalLiquidCap, 1f));
      Draw.rect(cb.topRegion, x, y);

      if (nuclearHeat > cb.nuclearFlashThreshold) {
        nuclearFlash += (1f + ((nuclearHeat - cb.nuclearFlashThreshold)
            / (1f - cb.nuclearFlashThreshold)) * 5.4f) * Time.delta;
        Draw.color(Color.red, Color.yellow, Mathf.absin(nuclearFlash, 9f, 1f));
        Draw.alpha(0.3f);
        Draw.rect(cb.lightsRegion, x, y);
      }
      Draw.reset();
    }

    @Override
    public void drawLight() {
      CombinedGenerator cb = (CombinedGenerator) block;
      if (cb.mode == Mode.nuclear) {
        float fract = productionEfficiency;
        nuclearSmoothLight = Mathf.lerpDelta(nuclearSmoothLight, fract, 0.08f);
        Drawf.light(x, y, (90f + Mathf.absin(5, 5f)) * nuclearSmoothLight,
            Tmp.c1.set(cb.nuclearLightColor).lerp(Color.scarlet, nuclearHeat), 0.6f * nuclearSmoothLight);
      } else {
        super.drawLight();
      }
    }

    // -------------------- 显示面板 --------------------
    @Override
    public void display(Table table) {
      table.table(cont -> {
        cont.top().left();
        cont.defaults().growX().left();

        cont.table(t -> {
          t.left();
          TextureRegion icon = block.getDisplayIcon(tile);
          if (icon == null)
            icon = Core.atlas.find("clear");
          t.add(new Image(icon)).size(8 * 4);
          int count = group().size;
          String title = count > 1
              ? "[accent]组合发电机[] x" + count + "\n" + block.getDisplayName(tile)
              : block.getDisplayName(tile);
          t.labelWrap(title).left().width(160f).padLeft(4);
        }).growX().left();
        cont.row();

        if (team != mindustry.Vars.player.team())
          return;

        Table barsTable = new Table();
        barsTable.left();
        barsTable.update(() -> {
          barsTable.clearChildren();
          barsTable.defaults().growX().height(18f).pad(4);
          buildComboBars(barsTable);
        });
        cont.add(barsTable).growX().left();
        cont.row();

        Table comboIO = new Table();
        comboIO.left();
        comboIO.update(() -> {
          comboIO.clearChildren();
          buildComboIO(comboIO);
        });
        cont.add(comboIO).growX().left();
        cont.row();

        Table localIO = new Table();
        localIO.left();
        localIO.update(() -> {
          localIO.clearChildren();
          buildLocalIO(localIO);
        });
        cont.add(localIO).growX().left();
      }).width(260f).left();
    }

    public void buildComboBars(Table table) {
      float totalPower = 0f;
      for (CombinedGeneratorBuild member : group()) {
        if (member.isValid()) {
          totalPower += member.getPowerProduction();
        }
      }
      if (totalPower > 0.001f) {
        final float tp = totalPower;
        table.add(new Bar(
            () -> "发电 " + Strings.fixed(tp * 60f, 1) + " ⚡/s",
            () -> Pal.power,
            () -> 1f));
        table.row();
      }

      CombinedGenerator cb = (CombinedGenerator) block;
      if (cb.mode == Mode.nuclear) {
        final float h = nuclearHeat;
        table.add(new Bar(
            () -> "热量 " + Strings.fixed(h * 100, 0) + "%",
            () -> Pal.lightOrange,
            () -> h));
        table.row();
      } else if (cb.mode == Mode.heater) {
        final float h = heaterHeat / Math.max(cb.heaterHeatOutput, 0.001f);
        table.add(new Bar(
            () -> "热量 " + Strings.fixed(heaterHeat, 1) + "/" + Strings.fixed(cb.heaterHeatOutput, 1),
            () -> Pal.lightOrange,
            () -> h));
        table.row();
      } else if (cb.mode == Mode.impact) {
        final float w = impactWarmup;
        table.add(new Bar(
            () -> "预热 " + Strings.fixed(w * 100, 0) + "%",
            () -> Pal.accent,
            () -> w));
        table.row();
      }

      if (items != null) {
        for (Item item : cachedItems) {
          int total = items.get(item);
          if (total > 0) {
            final int t = total, c = Math.max(comboTotalItemCap, 1);
            table.add(new Bar(
                () -> item.localizedName + ": " + t + "/" + c,
                () -> item.color,
                () -> (float) t / c));
            table.row();
          }
        }
      }

      LiquidModule sharedLiq = this.liquids;
      if (sharedLiq == null) {
        CombinedGeneratorBuild l = leader();
        if (l != null)
          sharedLiq = l.liquids;
      }
      if (sharedLiq != null) {
        for (Liquid liquid : cachedLiquids) {
          float total = sharedLiq.get(liquid);
          if (total > 0.001f) {
            final float t = total, c = Math.max(comboTotalLiquidCap, 1f);
            table.add(new Bar(
                () -> liquid.localizedName + ": " + Strings.fixed(t, 1) + "/" + Strings.fixed(c, 1),
                () -> liquid.barColor != null ? liquid.barColor : liquid.color,
                () -> t / c));
            table.row();
          }
        }
      }
    }

    public void buildComboIO(Table table) {
      table.left();
      table.add("[lightgray]组合体构成:").left();
      table.row();
      ObjectIntMap<Block> blockCounts = new ObjectIntMap<>();
      for (CombinedGeneratorBuild member : group()) {
        if (member.isValid()) {
          int old = blockCounts.get(member.block, 0);
          blockCounts.put(member.block, old + 1);
        }
      }
      Seq<Block> sortedBlocks = new Seq<>();
      for (Block b : blockCounts.keys())
        sortedBlocks.add(b);
      sortedBlocks.sort(b -> b.id);
      boolean hasContent = false;
      for (Block b : sortedBlocks) {
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
    }

    public void buildLocalIO(Table table) {
      table.left();
      CombinedGenerator cb = (CombinedGenerator) this.block;
      table.add("[lightgray]本机: " + block.localizedName + "[]").left();
      table.row();

      boolean hasLocalInput = false;
      if (block.consumers != null && block.consumers.length > 0) {
        for (Consume cons : block.consumers) {
          if (cons instanceof ConsumeItems ci) {
            for (var stack : ci.items) {
              if (!hasLocalInput) {
                table.add("[gray]输入:").left();
                table.row();
                hasLocalInput = true;
              }
              boolean has = items != null && items.get(stack.item) >= stack.amount;
              table.table(row -> {
                row.left();
                if (stack.item.uiIcon != null) {
                  row.add(new ReqImage(stack.item.uiIcon, () -> has)).size(iconMed).padRight(4f);
                }
                row.add(stack.item.localizedName + " x" + stack.amount)
                    .color(has ? Color.white : Color.scarlet).left();
              }).left();
              table.row();
            }
          } else if (cons instanceof ConsumeLiquid cl) {
            if (!hasLocalInput) {
              table.add("[gray]输入:").left();
              table.row();
              hasLocalInput = true;
            }
            boolean has = liquids != null && liquids.get(cl.liquid) >= cl.amount * 10f;
            table.table(row -> {
              row.left();
              if (cl.liquid.uiIcon != null) {
                row.add(new ReqImage(cl.liquid.uiIcon, () -> has)).size(iconMed).padRight(4f);
              }
              row.add(cl.liquid.localizedName + " " + Strings.fixed(cl.amount * 60f, 1) + "/s")
                  .color(has ? Color.white : Color.scarlet).left();
            }).left();
            table.row();
          } else if (cons instanceof ConsumeLiquids cls) {
            for (var stack : cls.liquids) {
              if (!hasLocalInput) {
                table.add("[gray]输入:").left();
                table.row();
                hasLocalInput = true;
              }
              boolean has = liquids != null && liquids.get(stack.liquid) >= stack.amount * 10f;
              table.table(row -> {
                row.left();
                if (stack.liquid.uiIcon != null) {
                  row.add(new ReqImage(stack.liquid.uiIcon, () -> has)).size(iconMed).padRight(4f);
                }
                row.add(stack.liquid.localizedName + " " + Strings.fixed(stack.amount * 60f, 1) + "/s")
                    .color(has ? Color.white : Color.scarlet).left();
              }).left();
              table.row();
            }
          } else if (cons instanceof ConsumePower cp) {
            if (!hasLocalInput) {
              table.add("[gray]输入:").left();
              table.row();
              hasLocalInput = true;
            }
            table.table(row -> {
              row.left();
              row.image(Icon.powerSmall).size(iconMed).padRight(4f);
              row.add("电力 " + Strings.fixed(cp.usage * 60f, 1) + " ⚡/s").color(Color.white).left();
            }).left();
            table.row();
          }
        }
      }

      boolean hasLocalOutput = false;
      if (cb.powerProduction > 0) {
        if (!hasLocalOutput) {
          table.add("[gray]产出:").left();
          table.row();
          hasLocalOutput = true;
        }
        table.table(row -> {
          row.left();
          row.image(Icon.powerSmall).size(24f).padRight(4f);
          row.add("电力 " + Strings.fixed(cb.powerProduction * 60f, 1) + " ⚡/s").color(Pal.power).left();
        }).left();
        table.row();
      }
      if (cb.mode == Mode.heater && cb.heaterHeatOutput > 0) {
        if (!hasLocalOutput) {
          table.add("[gray]产出:").left();
          table.row();
          hasLocalOutput = true;
        }
        table.table(row -> {
          row.left();
          row.add("[orange]热量 " + Strings.fixed(cb.heaterHeatOutput, 1) + " 热/s").color(Pal.lightOrange)
              .left();
        }).left();
        table.row();
      }
      if (!hasLocalOutput && !hasLocalInput) {
        table.add("[darkGray]无").left();
        table.row();
      }
    }

    // -------------------- 序列化 --------------------
    @Override
    public byte version() {
      return 2;
    }

    @Override
    public void write(Writes write) {
      CombinedGeneratorBuild trueLeader = this;
      if (comboGroup != null && comboGroup.size > 0) {
        for (CombinedGeneratorBuild b : comboGroup) {
          if (b != null && b.isValid() && b.pos() < trueLeader.pos())
            trueLeader = b;
        }
      }

      ItemModule savedItems = items;
      LiquidModule savedLiquids = liquids;
      if (this != trueLeader) {
        if (items != null)
          items = new ItemModule();
        if (liquids != null)
          liquids = new LiquidModule();
      }

      super.write(write);

      items = savedItems;
      liquids = savedLiquids;

      write.bool(comboLeader != null);
      if (comboLeader != null)
        write.i(comboLeader.pos());

      CombinedGenerator cb = (CombinedGenerator) block;
      if (cb.mode == Mode.impact) {
        write.f(impactWarmup);
        write.f(impactTotalProgress);
      } else if (cb.mode == Mode.nuclear) {
        write.f(nuclearHeat);
        write.f(nuclearHeatProgress);
        write.f(nuclearFlash);
        write.f(nuclearSmoothLight);
      } else if (cb.mode == Mode.heater) {
        write.f(heaterHeat);
      }
    }

    @Override
    public void read(Reads read, byte revision) {
      super.read(read, revision);

      boolean hasLeader = read.bool();
      int leaderPos = -1;
      if (hasLeader)
        leaderPos = read.i();

      comboDirty = true;
      if (hasLeader && leaderPos != pos()) {
        pendingLeaderPos = leaderPos;
        if (items != null)
          items = new ItemModule();
        if (liquids != null)
          liquids = new LiquidModule();
      } else {
        pendingLeaderPos = -1;
        comboLeader = null;
      }

      if (revision >= 2) {
        CombinedGenerator cb = (CombinedGenerator) block;
        if (cb.mode == Mode.impact) {
          impactWarmup = read.f();
          impactTotalProgress = read.f();
        } else if (cb.mode == Mode.nuclear) {
          nuclearHeat = read.f();
          nuclearHeatProgress = read.f();
          nuclearFlash = read.f();
          nuclearSmoothLight = read.f();
        } else if (cb.mode == Mode.heater) {
          heaterHeat = read.f();
        }
      }
    }
  }
}
