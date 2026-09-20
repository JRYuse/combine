package combine.turret;

import combine.net.ComboNet;
import combine.util.ComboUi;
import combine.util.ComboReflect;
import java.util.LinkedHashMap;
import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.struct.IntSet;
import arc.struct.ObjectIntMap;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.content.Items;
import mindustry.ctype.UnlockableContent;
import mindustry.entities.bullet.BulletType;
import mindustry.game.EventType.Trigger;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Teamc;
import mindustry.graphics.Pal;
import mindustry.logic.LAccess;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.ui.MultiReqImage;
import mindustry.ui.ReqImage;
import mindustry.world.Block;
import mindustry.world.blocks.ItemSelection;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItemFilter;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.*;

public class CombinedItemTurret extends ItemTurret {

  public boolean allowCrossTypeCombo = true;

  public CombinedItemTurret(String name) {
    super(name);
    buildType = CombinedItemTurretBuild::new;
    hasItems = true;
    sync = true;

  }

  /** 原版单体液体容量（组容量计算基准；liquidCapacity 本体抬高为 9999 假容量） */
  public float baseLiquidCapacity = 10f;

  @Override
  public void init() {
    super.init();
    // 【再登记一次】克隆体先 new 再 copyFields，配置表万一被原版的覆盖也不会丢；
    // configurable 保持 true（原版 ItemTurret 本来就是 true，弹药选择就靠它）
    configurable = true;
    config(Item.class, (CombinedItemTurretBuild tile, Item it) -> tile.selected = it);
    // 冷却液选择：**整组一起改**（液池是全组共用的一份，只改一台会被其余"自动"抢回去）
    config(Liquid.class, (CombinedItemTurretBuild tile, Liquid l) -> {
      for (CombinedItemTurretBuild m : tile.group())
        if (m != null)
          m.selectedCoolant = l;
    });
    configClear((CombinedItemTurretBuild tile) -> {
      tile.selected = null;
      tile.selectedCoolant = null;
    });
    // 原版 ItemTurret 的 ConsumeItemFilter 高亮 ammo.peek()。组合炮塔共享 ammo 队列后，
    // 队列顶部是全组第一发弹药，不一定是当前炮塔可用/选中的弹药，必须改成高亮 getAmmoContent()。
    replaceAmmoConsumer();
    conductivePower = true;
    configurable = true;
    config(Item.class, (CombinedItemTurretBuild tile, Item i) -> tile.selected = i);
    // 冷却液选择：和弹药选择一样走 config（联机同步）；null = 自动用池子里效果最好的
    // 冷却液选择：**整组一起改**（液池是全组共用的一份，只改一台会被其余"自动"抢回去）
    config(Liquid.class, (CombinedItemTurretBuild tile, Liquid l) -> {
      for (CombinedItemTurretBuild m : tile.group())
        if (m != null)
          m.selectedCoolant = l;
    });
    configClear((CombinedItemTurretBuild tile) -> {
      tile.selected = null;
      tile.selectedCoolant = null;
    });

    // FIX[liquid]: 与 CombinedLiquidTurret 同理——原版 transferLiquid 按"目标方块的
    // liquidCapacity"限流，共享冷却池总量超过单台容量后管道会算出负流量而彻底断流
    // （调用方是原版代码，炮塔侧无法拦截）。抬高为假容量，
    // 真实上限 = 组总容量（acceptLiquid 组容量 + handleLiquid 超量退回 + 每帧截断）。
    baseLiquidCapacity = liquidCapacity;
    liquidCapacity = 9999f;
    hasLiquids = true;
  }

  /** 用显示当前有效弹药的 Consumer 替换原版读取 ammo.peek() 的 Consumer。 */
  private void replaceAmmoConsumer() {
    if (consumers == null)
      return;

    Consume old = findConsumer((Consume c) -> c instanceof ConsumeItemFilter);
    if (!(old instanceof ConsumeItemFilter oldFilter))
      return;

    ConsumeItemFilter stable = new ConsumeItemFilter(i -> ammoTypes != null && ammoTypes.containsKey(i)) {
      @Override
      public void build(Building build, Table table) {
        MultiReqImage image = new MultiReqImage();
        content.items().each(i -> this.filter.get(i) && i.unlockedNow(), item -> image.add(
            new ReqImage(new Image(item.uiIcon), () -> {
              if (build instanceof CombinedItemTurretBuild it)
                return it.getAmmoContent() == item;
              return false;
            })));
        table.add(image).size(32f);
      }

      @Override
      public float efficiency(Building build) {
        if (build instanceof CombinedItemTurretBuild it) {
          Turret.AmmoEntry e = it.effectiveEntry();
          return e != null && (e.amount >= CombinedItemTurret.this.ammoPerShot || it.cheating()) ? 1f : 0f;
        }
        return super.efficiency(build);
      }

      @Override
      public void display(mindustry.world.meta.Stats stats) {
      }
    };

    stable.optional = oldFilter.optional;
    stable.booster = oldFilter.booster;
    stable.update = oldFilter.update;
    stable.multiplier = oldFilter.multiplier;
    replaceConsumerInstance(old, stable);
  }

  /** init() 已生成 consumers 数组，不能直接 removeConsumer；同步替换所有数组和 builder 中的实例。 */
  private void replaceConsumerInstance(Consume oldConsumer, Consume newConsumer) {
    for (int i = 0; i < consumeBuilder.size; i++) {
      if (consumeBuilder.get(i) == oldConsumer) {
        consumeBuilder.set(i, newConsumer);
        break;
      }
    }

    Consume[][] arrays = { consumers, optionalConsumers, nonOptionalConsumers, updateConsumers };
    for (Consume[] array : arrays) {
      if (array == null)
        continue;
      for (int i = 0; i < array.length; i++) {
        if (array[i] == oldConsumer)
          array[i] = newConsumer;
      }
    }
  }

  @Override
  public void setStats() {
    super.setStats();
    // liquidCapacity 已是 9999 假容量，统计面板显示真实单台容量
    stats.remove(mindustry.world.meta.Stat.liquidCapacity);
    if (hasLiquids)
      stats.add(mindustry.world.meta.Stat.liquidCapacity, baseLiquidCapacity,
          mindustry.world.meta.StatUnit.liquidUnits);
  }

  @Override
  public void setBars() {
    super.setBars();

    // 移除原版及历史版本注册的所有 ammo 前缀条目，避免任何同名残留。
    // 注意：这里会 removeBar（改 map），必须先拷一份 key 再遍历，
    // 否则边遍历边删会踩 arc 迭代器的 index 越界（原版 titan 炮台就必崩）。
    for (String barKey : barMap.keys().toSeq()) {
      if (barKey.startsWith("ammo"))
        removeBar(barKey);
    }

    // 原版那条「弹药」（stat.ammo）bar 的分母是**单台** maxAmmo，组合体十几台共享弹仓时
    // 会显示成几百 %（截图里那个"装填 104%"就是这么来的）。换成按组总量做分母。
    addBar("ammo-total", (CombinedItemTurretBuild e) -> new Bar(
        "stat.ammo",
        Pal.ammo,
        () -> Math.min(e.totalAmmo / Math.max(e.perItemCap(), 1f), 1f)));

    // 原版可能注册 "liquid" 或 "liquid-water"，先删除，避免和自定义液体条重复。
    for (String barKey : barMap.keys().toSeq()) {
      if (barKey.startsWith("liquid"))
        removeBar(barKey);
    }

    boolean usesLiquid = coolant != null;
    if (!usesLiquid && consumers != null) {
      for (Consume cons : consumers) {
        if (cons instanceof mindustry.world.consumers.ConsumeLiquidBase) {
          usesLiquid = true;
          break;
        }
      }
    }
    if (!usesLiquid) {
      removeBar("liquid");
    } else {
      addBar("liquid", (CombinedItemTurretBuild e) -> new Bar(
          () -> {
            Liquid cur = e.liquids == null ? null : e.liquids.current();
            float cap = Math.max(e.perLiquidCap(), 1f);
            return cur == null || e.liquids.get(cur) <= 0.001f
                ? "[lightgray]液体[]"
                : cur.localizedName + " " + Strings.fixed(e.liquids.get(cur), 1) + "/" + Strings.fixed(cap, 1);
          },
          () -> {
            Liquid cur = e.liquids == null ? null : e.liquids.current();
            return cur == null ? Pal.gray : (cur.barColor != null ? cur.barColor : cur.color);
          },
          () -> e.liquids == null ? 0f : e.liquids.currentAmount() / Math.max(e.perLiquidCap(), 1f)));
    }

    // 每种可用弹药只显示一条明细，避免“当前弹药总条 + 明细条”重复显示同一种弹药。
    for (Item i : ammoTypes.keys()) {
      final Item it = i;
      addBar("ammo-detail-" + i.name, (CombinedItemTurretBuild e) -> new Bar(
          () -> {
            Turret.AmmoEntry en = e.findEntry(it);
            return it.localizedName + " " + (en == null ? 0 : en.amount)
                + "/" + (int) e.perItemCap();
          },
          () -> it.color,
          () -> {
            Turret.AmmoEntry en = e.findEntry(it);
            return en == null ? 0f : en.amount / Math.max(e.perItemCap(), 1f);
          }));
    }
  }

  public class CombinedItemTurretBuild extends ItemTurretBuild implements combine.saves.ComboSaved {

    /** perItemCap() 的每-tick 缓存（组总弹容），避免收弹路径每次都遍历整组。 */
    public long comboAmmoCapTick = Long.MIN_VALUE;
    public float comboAmmoCapSum = 1f;

    /** 内容按组合体同步，但 peek()/any() 只返回本建筑当前可使用的弹药。 */
    private class DisplayAmmoSeq extends Seq<Turret.AmmoEntry> {
      @Override
      public Turret.AmmoEntry peek() {
        if (size == 0)
          throw new IllegalStateException("Array is empty.");
        Turret.AmmoEntry e = effectiveEntry();
        return e != null ? e : super.peek();
      }

      @Override
      public boolean any() {
        return effectiveEntry() != null;
      }
    }

    public CombinedItemTurretBuild() {
      ammo = new DisplayAmmoSeq();
    }

    public CombinedItemTurretBuild comboLeader;
    public Seq<CombinedItemTurretBuild> comboGroup = new Seq<>();
    public boolean comboDirty = true;

    public Item selected;

    public Item lastInput;

    /** 组总液体容量（rebuildCombo 时重算；<=0 = 尚未建组，perLiquidCap 回退单台基准） */
    public float comboTotalLiquidCap = 0f;

    public CombinedItemTurretBuild leader() {
      if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null)) {
        comboLeader = null;
        comboDirty = true; // FIX: 静默失联后必须允许重建组合
      }
      return comboLeader == null ? this : comboLeader;
    }

    public boolean isLeader() {
      return leader() == this;
    }

    public Seq<CombinedItemTurretBuild> group() {
      CombinedItemTurretBuild l = leader();
      if (l.comboGroup == null)
        l.comboGroup = new Seq<>();
      return l.comboGroup;
    }

    public float perItemCap() {
      // 装弹/收弹路径每次都要问组总弹容，原先每次都遍历整组 → 按 tick 缓存
      CombinedItemTurretBuild l = leader();
      if (l.comboAmmoCapTick != state.updateId) {
        l.comboAmmoCapTick = state.updateId;
        float total = 0f;
        for (CombinedItemTurretBuild b : l.group())
          if (b.isValid())
            total += ((Turret) b.block).maxAmmo;
        l.comboAmmoCapSum = Math.max(total, 1f);
      }
      return l.comboAmmoCapSum;
    }

    // FIX[liquid]: 组总容量 = 各成员 baseLiquidCapacity 之和
    // （block.liquidCapacity 已被 init() 抬成 9999 假容量，绝不能再累加它）。
    // 建组前回退单台基准，避免刚放置的一帧拒收。
    public float perLiquidCap() {
      if (comboTotalLiquidCap <= 0.001f)
        return Math.max(((CombinedItemTurret) block).baseLiquidCapacity, 0.001f);
      return Math.max(comboTotalLiquidCap, 0.001f);
    }

    @Override
    public boolean acceptLiquid(mindustry.gen.Building source, Liquid liquid) {
      if (!block.hasLiquids)
        return false;
      // FIX: 共享液体池的接收上限用全组总容量, 而非单台 block.liquidCapacity
      return liquids.get(liquid) < perLiquidCap() - 0.0001f;
    }

    @Override
    public void handleLiquid(mindustry.gen.Building source, Liquid liquid, float amount) {
      if (amount <= 0.001f)
        return;
      // 组总容量上限，超量退回源端（源端被 9999 假容量骗过时会多送）
      float actual = Math.min(amount, Math.max(0f, perLiquidCap() - liquids.get(liquid)));
      if (actual > 0.001f)
        liquids.add(liquid, actual);
      float refund = amount - actual;
      if (refund > 0.001f && source != null && source.liquids != null)
        source.liquids.add(liquid, refund);
    }

    /** 组总容量截断（外部按 9999 假容量注入时会超容） */
    public void trimExcessLiquids(CombinedItemTurretBuild leader) {
      LiquidModule liq = leader.liquids;
      if (liq == null || leader.comboTotalLiquidCap <= 0.001f)
        return;
      float perCap = leader.perLiquidCap();
      for (Liquid l : content.liquids()) {
        float over = liq.get(l) - perCap;
        if (over > 0.001f)
          liq.remove(l, over);
      }
    }

    public Turret.AmmoEntry findEntry(Item item) {
      if (item == null)
        return null;
      for (Turret.AmmoEntry e : ammo) {
        if (((ItemTurret.ItemEntry) e).item == item)
          return e;
      }
      return null;
    }

    public int amountOf(Item item) {
      if (item == null || ammo == null)
        return 0;
      int total = 0;
      for (Turret.AmmoEntry e : ammo) {
        if (((ItemTurret.ItemEntry) e).item == item)
          total += e.amount;
      }
      return total;
    }

    public BulletType typeFor(Item item) {
      return item == null || ammoTypes == null ? null : ammoTypes.get(item);
    }

    public BulletType typeForEntry(Turret.AmmoEntry e) {
      return e instanceof ItemTurret.ItemEntry ie ? typeFor(ie.item) : null;
    }

    public boolean acceptsAmmo(Item item) {
      return item != null && ammoTypes != null && ammoTypes.containsKey(item);
    }

    public Turret.AmmoEntry effectiveEntry() {
      if (ammo == null || ammo.isEmpty())
        return null;
      // FIX: 只要 amount > 0 即允许使用；selected 耗尽后自动回退，不再返回 null 卡死
      if (selected != null && acceptsAmmo(selected)) {
        Turret.AmmoEntry e = findEntry(selected);
        if (e != null && e.amount > 0)
          return e;
      }
      // 不再按 lastInput 自动改选，避免后输入的弹药抢占第一发选择的弹药。
      for (Turret.AmmoEntry e : ammo) {
        if (e == null || ((ItemTurret.ItemEntry) e).item == null || e.type() == null)
          continue;
        Item it = ((ItemTurret.ItemEntry) e).item;
        if (acceptsAmmo(it) && e.amount > 0)
          return e;
      }
      return null;
    }

    public void syncAmmo() {
      int total = 0;
      for (Turret.AmmoEntry e : ammo)
        total += e.amount;
      for (CombinedItemTurretBuild m : group())
        if (m.isValid())
          m.totalAmmo = total;
    }

    public void rebuildCombo() {
      Seq<CombinedItemTurretBuild> oldGroup = comboGroup != null ? new Seq<>(comboGroup) : new Seq<>();
      comboGroup = new Seq<>();
      comboGroup.add(this);
            // 分组 BFS 穿过组合节点/连接器：被节点连上 = 效果相当于直接组合（同 LinkWall 语义）
            for (Building b : ComboReflect.linkedReachable(this,
                    o -> o instanceof CombinedItemTurretBuild other && other.team == team && other.isValid(),
                    (cur, o) -> cur.block == o.block
                    || ((CombinedItemTurret) cur.block).allowCrossTypeCombo
                    || ((CombinedItemTurret) o.block).allowCrossTypeCombo)) {
                if (b != this)
                    comboGroup.add((CombinedItemTurretBuild) b);
            }
      CombinedItemTurretBuild newLeader = this;
      for (CombinedItemTurretBuild b : comboGroup)
        if (b.isValid() && b.pos() < newLeader.pos())
          newLeader = b;
      Seq<CombinedItemTurretBuild> newGroup = new Seq<>(comboGroup);

      // 组合前可能存在多个独立小组；必须先按“旧小组 leader”合并各自的弹药，
      // 否则新 leader 只会保留自己原小组的弹药，另一组供入的铜/石墨会消失。
      // 用 IdentityHashMap 保存旧小组 Leader，避免大组合体反复执行 Seq.contains 造成
      // O(n²) 扫描和 ArrayList/Seq 扩容分配，防止移动端 512MB 堆直接 OOM。
      java.util.IdentityHashMap<CombinedItemTurretBuild, Boolean> oldLeaders = new java.util.IdentityHashMap<>();
      LinkedHashMap<Item, Long> mergedTotals = new LinkedHashMap<>();
      for (CombinedItemTurretBuild b : newGroup) {
        if (!b.isValid())
          continue;
        CombinedItemTurretBuild oldLeader = b.leader();
        if (oldLeader == null || oldLeaders.containsKey(oldLeader))
          continue;
        oldLeaders.put(oldLeader, Boolean.TRUE);
        oldLeader.normalizeAmmoEntries();
        for (Turret.AmmoEntry e : oldLeader.ammo) {
          if (e == null || !(e instanceof ItemTurret.ItemEntry ie) || ie.item == null)
            continue;
          mergedTotals.merge(ie.item, (long) Math.max(0, e.amount), Long::sum);
        }
      }

      newLeader.comboGroup = newGroup;
      for (CombinedItemTurretBuild b : newGroup) {
        if (b.isValid()) {
          b.comboLeader = newLeader;
          b.comboGroup = newGroup;
          b.comboDirty = false;
        }
      }
      newLeader.comboLeader = null;
      newLeader.comboAmmoCapTick = Long.MIN_VALUE;

      newLeader.ammo.clear();
      int mergedCap = (int) Math.max(0, newLeader.perItemCap());
      for (java.util.Map.Entry<Item, Long> entry : mergedTotals.entrySet()) {
        int amount = (int) Math.min(mergedCap, entry.getValue());
        if (amount <= 0)
          continue;
        ItemTurret owner = newLeader.findOwnerBlock(entry.getKey());
        if (owner != null)
          newLeader.ammo.add(AmmoEntries.create(owner, entry.getKey(), amount));
      }

      for (CombinedItemTurretBuild b : newGroup) {
        if (b.isValid() && b != newLeader) {
          b.ammo.clear();
          b.ammo.addAll(newLeader.ammo);
          b.normalizeAmmoEntries();
        }
      }
      newLeader.syncAmmo();

      // FIX[liquid]: 组总液体容量 = 各成员 baseLiquidCapacity 之和
      float totalLiqCap = 0f;
      for (CombinedItemTurretBuild b : newGroup)
        if (b.isValid())
          totalLiqCap += ((CombinedItemTurret) b.block).baseLiquidCapacity;
      for (CombinedItemTurretBuild b : newGroup)
        if (b.isValid())
          b.comboTotalLiquidCap = totalLiqCap;

      // FIX[bug2]: 共享液体模块 (冷却液) — 合并入领导者模块后全组指向同一对象
      LiquidModule leaderLiquids = newLeader.liquids;
      for (CombinedItemTurretBuild b : newGroup) {
        if (!b.isValid() || b == newLeader || b.liquids == leaderLiquids)
          continue;
        for (Liquid liquid : content.liquids()) {
          float amt = b.liquids.get(liquid);
          if (amt > 0f)
            leaderLiquids.add(liquid, amt);
        }
        b.liquids = leaderLiquids;
      }

      trimExcessLiquids(newLeader);

      // 离组建筑归还独立液体模块, 避免继续读写共享池
      for (CombinedItemTurretBuild oldb : oldGroup) {
        if (oldb != this && oldb.isValid() && !newGroup.contains(oldb) && oldb.liquids == leaderLiquids)
          oldb.liquids = new LiquidModule();
      }

      for (CombinedItemTurretBuild old : oldGroup) {
        if (old != this && old.isValid() && !newGroup.contains(old)) {
          old.comboLeader = null;
          old.comboGroup = new Seq<>();
          old.comboDirty = true;
          old.comboTotalLiquidCap = 0f;
        }
      }
    }

    @Override
    public void created() {
      super.created();
      comboTotalLiquidCap = ((CombinedItemTurret) block).baseLiquidCapacity;
      comboDirty = true;
    }

    @Override
    public void onProximityUpdate() {
      super.onProximityUpdate();
      comboDirty = true;
      for (CombinedItemTurretBuild m : group())
        if (m.isValid())
          m.comboDirty = true;
    }

    @Override
    public void onRemoved() {
      Seq<CombinedItemTurretBuild> members = new Seq<>(group());
      boolean wasLeader = isLeader();
      if (!wasLeader) {

        ammo.clear();
        totalAmmo = 0;
      }
      if (wasLeader) {
        Seq<CombinedItemTurretBuild> survivors = new Seq<>();
        for (CombinedItemTurretBuild b : members)
          if (b != this && b.isValid())
            survivors.add(b);

        float[] caps = new float[survivors.size];
        float totalCap = 0f;
        for (int i = 0; i < survivors.size; i++) {
          caps[i] = ((Turret) survivors.get(i).block).maxAmmo;
          totalCap += caps[i];
        }
        Seq<Turret.AmmoEntry>[] newSeqs = new Seq[survivors.size];
        for (int i = 0; i < survivors.size; i++)
          newSeqs[i] = new Seq<>();
        if (totalCap > 0.001f) {
          for (Turret.AmmoEntry e : ammo) {
            Item item = ((ItemTurret.ItemEntry) e).item;
            int remaining = e.amount;
            int lastAccepting = -1;
            for (int i = 0; i < survivors.size; i++) {
              if (survivors.get(i).block instanceof ItemTurret it && it.ammoTypes != null
                  && it.ammoTypes.get(item) != null)
                lastAccepting = i;
            }
            for (int i = 0; i < survivors.size; i++) {
              if (!(survivors.get(i).block instanceof ItemTurret it) || it.ammoTypes == null
                  || it.ammoTypes.get(item) == null)
                continue;
              int share = (i == lastAccepting) ? remaining
                  : Math.round(e.amount * (caps[i] / totalCap));
              share = Math.min(share, remaining);
              if (share > 0) {
                newSeqs[i].add(AmmoEntries.create(it, item, share));
                remaining -= share;
              }
            }
          }
        }
        for (int i = 0; i < survivors.size; i++) {
          CombinedItemTurretBuild b = survivors.get(i);
          b.ammo.clear();
          b.ammo.addAll(newSeqs[i]);
          b.comboLeader = null;
          b.comboGroup = new Seq<>();
          b.comboDirty = true;
        }
        if (survivors.size > 0)
          survivors.first().syncAmmo();
      } else {
        CombinedItemTurretBuild l = leader();
        if (l != null && l.isValid() && l != this)
          l.comboDirty = true;
      }
      comboLeader = null;
      comboGroup = new Seq<>();
      comboDirty = false;
      super.onRemoved();
    }

    // ===== 弹药空值防护 (单副本, 全限定类型) =====
    @Override
    public boolean hasAmmo() {
      // FIX[NPE]: 原实现"任何条目有弹药即 true"，不校验是否本塔弹药——跨类型组合共享弹药时，
      // 邻塔弹药（如双管池顶的散裂 metaglass）会让 hasAmmo()==true 而 peekAmmo()
      // =effectiveEntry()==null，TurretBuild.findEnemy 第一行读 ammo.unitSort 直接 NPE。
      // 修复：换顶只考虑本塔可接受弹药，判定与 effectiveEntry()/peekAmmo() 严格一致。
      if (ammo == null)
        return false;
      int perShot = ((Turret) block).ammoPerShot;
      // 不调整共享弹药队列：两台炮塔若各自 swap，会让 ammo.peek() 在两塔之间来回变化，
      // 原版弹药显示读取 ammo.peek()，表现为当前弹药图标闪烁。
      if (!canConsume())
        return false;
      if (cheating())
        return !ammo.isEmpty();
      Turret.AmmoEntry e = effectiveEntry();
      return e != null && e.amount >= perShot;
    }

    @Override
    public float ammoReloadMultiplier() {
      // 不读取 ammo.peek()：共享队列顶部由第一发输入决定，实际使用弹药由 effectiveEntry() 决定
      Turret.AmmoEntry e = effectiveEntry();
      return e == null || e.type() == null ? 1f : e.type().reloadMultiplier;
    }

    @Override
    public void updateTile() {
      if (isLeader() && comboDirty)
        rebuildCombo();
      // FIX[liquid]: 领导者每帧按组总容量截断共享池
      if (isLeader())
        trimExcessLiquids(leader());
      applyCoolantChoice();
      pullAmmoFromPool();
      super.updateTile();
    }

    /** 选定的冷却液（null = 自动用池子里效果最好的那种）。 */
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

    /** 池子里现有的、本炮能用的冷却液（按液体 id 顺序）。 */
    public Seq<Liquid> presentCoolants() {
      Seq<Liquid> out = new Seq<>();
      if (liquids == null)
        return out;
      for (Liquid l : content.liquids())
        if (liquids.get(l) > 0.001f && acceptsCoolant(l))
          out.add(l);
      return out;
    }

    /**
     * 该用哪种冷却液：
     * · 选了就用选的（选的那种没货了自动回退到自动模式，不会卡住）；
     * · 没选（空选）→ 自动取池子里**效果最好**的（heatCapacity 最高，原版冷却速度就是乘它）。
     */
    public Liquid effectiveCoolant() {
      if (liquids == null)
        return null;
      // 以队长为准（整组共用一个池子，选择也该整组一致）
      CombinedItemTurretBuild lead0 = leader();
      Liquid sel = lead0 != null ? lead0.selectedCoolant : selectedCoolant;
      if (sel != null && acceptsCoolant(sel) && liquids.get(sel) > 0.001f)
        return sel;
      Liquid best = null;
      for (Liquid l : presentCoolants())
        if (best == null || l.heatCapacity > best.heatCapacity)
          best = l;
      return best;
    }

    /**
     * 把"当前冷却液"对齐到选定/自动挑出来的那种。
     *
     * 原版冷却（ReloadTurret.updateCooling / ConsumeLiquidFilter.getConsumed）先看
     * {@code liquids.current()}，所以池子里混了几种液体时，炮台会用到"最后被加进来的那种"
     * ——表现就是"混液体、冷却效果随机"（用户报的）。LiquidModule.add(l, 0) 只改 current，
     * 不动存量，正好用来把当前液体钉成我们挑的那种。
     */
    public void applyCoolantChoice() {
      if (coolant == null || liquids == null)
        return;
      try {
        Liquid want = effectiveCoolant();
        if (want != null && liquids.current() != want)
          liquids.add(want, 0f);
      } catch (Throwable ignored) {
      }
    }

    /** 每 tick 最多从池子里搬这么多弹药进炮塔（别一口气抽干，也别拖帧）。 */
    public static final int pullAmmoPerTick = 8;

    /**
     * 【仓库供弹】组合节点/连接器把**仓库和炮塔**接在一起时，池子里的弹药要真的进炮塔。
     *
     * 炮塔的弹药存在自己的 ammo 队列里，items 模块只当"共享池"用；而原版只有**贴着**的
     * 方块才会把货送进来。跨着节点/连接器连过来的仓库不会送货 ——
     * 于是面板上显示着池子里的数量、炮塔却打不出来（用户报的"物品不会真正进入炮台"）。
     * 这里每 tick 从池子里搬一点，逻辑和邻居送货完全一样（remove + handleItem），不凭空造货。
     */
    public void pullAmmoFromPool() {
      if (items == null || ammoTypes == null || ammoTypes.size == 0)
        return;
      // 注意用 perItemCap()（= 组内各台 maxAmmo 之和）而不是 maxAmmo：
      // maxAmmo 是原版**单台**的总弹仓上限，组合体十几台共享一个弹仓时
      // 用它当上限就会出现"仓库里 100+ 铜、炮塔只进 39，而每种弹药上限写着 400+"（用户报的）。
      if (totalAmmo >= perItemCap())
        return;
      try {
        // 【每种弹药都要分到额度】以前是按 ammoTypes 的顺序"谁先能装就一路装到本轮上限"，
        // 池子里铜一直有货，于是每 tick 的额度全被铜吃光，石墨/硅永远是 0 ——
        // 表现就是"只能进一种弹药"（用户截图里铜 32/450、石墨 0/450、硅 0/450）。
        // 现在把每 tick 的额度按弹药种类均分，几种弹药一起往里装。
        int perType = Math.max(2, pullAmmoPerTick / Math.max(ammoTypes.size, 1));
        for (Item item : ammoTypes.keys()) {
          if (items.get(item) <= 0)
            continue;
          int guard = 0;
          while (guard++ < perType && items.get(item) > 0 && totalAmmo < perItemCap() && acceptItem(this, item)) {
            items.remove(item, 1);
            handleItem(this, item);
          }
        }
      } catch (Throwable ignored) {
        // 供弹出问题不该把炮塔/游戏带崩，下一帧再试
      }
    }

    public ItemTurret findOwnerBlock(Item item) {
      for (CombinedItemTurretBuild m : group()) {
        if (m.isValid() && m.block instanceof ItemTurret mt && mt.ammoTypes.containsKey(item))
          return mt;
      }
      return ownerFor(item);
    }

    /** 修复旧存档/异常同步产生的重复弹药条目，并把数量限制在组合体容量内。 */
    public void normalizeAmmoEntries() {
      if (ammo == null)
        return;

      LinkedHashMap<Item, Long> totals = new LinkedHashMap<>();
      for (Turret.AmmoEntry e : ammo) {
        if (e == null || !(e instanceof ItemTurret.ItemEntry ie) || ie.item == null)
          continue;
        totals.merge(ie.item, (long) Math.max(0, e.amount), Long::sum);
      }

      ammo.clear();
      int cap = (int) Math.max(0, perItemCap());
      for (java.util.Map.Entry<Item, Long> entry : totals.entrySet()) {
        int amount = (int) Math.min(cap, entry.getValue());
        if (amount <= 0)
          continue;
        ItemTurret owner = findOwnerBlock(entry.getKey());
        if (owner != null)
          ammo.add(AmmoEntries.create(owner, entry.getKey(), amount));
      }
      syncAmmo();
    }

    public ItemTurret ownerFor(Item item) {
      if (ammoTypes != null && ammoTypes.containsKey(item))
        return (ItemTurret) block;
      CombinedItemTurretBuild lead = leader();
      if (lead != this && lead.block instanceof ItemTurret lt && lt.ammoTypes != null
          && lt.ammoTypes.containsKey(item))
        return lt;
      for (CombinedItemTurretBuild m : group())
        if (m != null && m.isValid() && m != this && m.block instanceof ItemTurret mt
            && mt.ammoTypes != null && mt.ammoTypes.containsKey(item))
          return mt;
      return null;
    }

    @Override
    public boolean acceptItem(Building source, Item item) {

      ItemTurret owner = ownerFor(item);
      if (owner == null)
        return false;
      float mult = owner.ammoTypes.get(item).ammoMultiplier;

      float cur = amountOf(item);
      return cur + mult <= perItemCap() + 0.001f;
    }

    @Override
    public void handleItem(Building source, Item item) {
      if (item == Items.pyratite)
        arc.Events.fire(Trigger.flameAmmo);

      ItemTurret owner = ownerFor(item);
      if (owner == null)
        return;
      BulletType type = owner.ammoTypes.get(item);
      if (type == null)
        return;

      CombinedItemTurretBuild lead = leader();
      lead.normalizeAmmoEntries();
      float room = lead.perItemCap() - lead.amountOf(item);
      int add = (int) type.ammoMultiplier;
      if (room < add)
        return;
      if (lead.totalAmmo == 0)
        arc.Events.fire(Trigger.resupplyTurret);

      CombinedItemTurretBuild ownerBuild = null;
      for (CombinedItemTurretBuild m : group()) {
        if (m.isValid() && m.block == owner) {
          ownerBuild = m;
          break;
        }
      }
      if (ownerBuild == null && block == owner)
        ownerBuild = this;

      Turret.AmmoEntry canonical = lead.findEntry(item);
      if (canonical != null) {
        canonical.amount += add;
      } else {
        canonical = AmmoEntries.create(owner, item, add);
        lead.ammo.add(canonical);
      }

      // 每台建筑持有自己的 Entry，但数量只以 leader 的 canonical Entry 为准，不能重复累加。
      for (CombinedItemTurretBuild m : group()) {
        if (!m.isValid())
          continue;
        Turret.AmmoEntry local = m.findEntry(item);
        if (local == null) {
          m.ammo.add(AmmoEntries.create(owner, item, canonical.amount));
        } else {
          local.amount = canonical.amount;
        }
      }

      if (ownerBuild != null && ownerBuild.selected == null && ownerBuild.acceptsAmmo(item))
        ownerBuild.selected = item;
      if (selected == null && acceptsAmmo(item))
        selected = item;

      lastInput = item;
      lead.syncAmmo();
      for (CombinedItemTurretBuild m : group())
        if (m.isValid())
          m.totalAmmo = lead.totalAmmo;
    }

    @Override
    public int acceptStack(Item item, int amount, Teamc source) {
      ItemTurret owner = ownerFor(item);
      if (owner == null)
        return 0;
      float mult = owner.ammoTypes.get(item).ammoMultiplier;

      float cur = amountOf(item);
      return Math.min(amount, (int) ((perItemCap() - cur) / mult));
    }

    @Override
    public void handleStack(Item item, int amount, Teamc source) {
      int accepted = Math.min(amount, acceptStack(item, amount, source));
      for (int i = 0; i < accepted; i++)
        handleItem(null, item);
    }

    @Override
    public BulletType useAmmo() {
      if (cheating())
        return peekAmmo();
      Turret.AmmoEntry selectedEntry = effectiveEntry();
      if (selectedEntry == null)
        return null;

      CombinedItemTurretBuild lead = leader();
      Item usedItem = ((ItemTurret.ItemEntry) selectedEntry).item;
      Turret.AmmoEntry canonical = lead.findEntry(usedItem);
      if (canonical == null)
        return null;

      BulletType result = typeForEntry(canonical);
      canonical.amount -= 1;
      boolean removed = canonical.amount <= 0;
      if (removed)
        lead.ammo.remove(canonical);
      lead.syncAmmo();

      for (CombinedItemTurretBuild m : group()) {
        if (!m.isValid())
          continue;
        Turret.AmmoEntry local = m.findEntry(usedItem);
        if (local != null) {
          if (removed)
            m.ammo.remove(local);
          else
            local.amount = canonical.amount;
        }
        m.totalAmmo = lead.totalAmmo;
      }
      return result;
    }

    @Override
    public @arc.util.Nullable BulletType peekAmmo() {
      Turret.AmmoEntry e = effectiveEntry();
      return e == null ? null : typeForEntry(e);
    }

    @Override
    public UnlockableContent getAmmoContent() {
      Turret.AmmoEntry e = effectiveEntry();
      return e == null ? null : ((ItemTurret.ItemEntry) e).item;
    }

    @Override
    public Object senseObject(LAccess sensor) {
      if (sensor == LAccess.currentAmmoType)
        return getAmmoContent();
      return super.senseObject(sensor);
    }

    @Override
    public void buildConfiguration(Table table) {
      Seq<Item> present = new Seq<>();
      for (Turret.AmmoEntry e : ammo) {
        Item it = ((ItemTurret.ItemEntry) e).item;
        if (acceptsAmmo(it) && !present.contains(it))
          present.add(it);
      }
      if (present.size >= 2) {
        ItemSelection.buildTable(CombinedItemTurret.this, table, present,
            () -> selected, i -> configure(i));
      }

      // 冷却液选择：**和物品弹药选择用同一套 UI**（ItemSelection.buildTable）——
      // 图标按钮 + 选中的那个自带黄框（Styles.clearNoneTogglei 的 checked 背景），
      // 点已选中的那个 = 取消（回到"自动取效果最好的"）。
      // 这里列的是"这个炮台能用的所有冷却液"（不只是池子里现有的），
      // 这样就算想要的冷却液还没送过来也能先选好。
      Seq<Liquid> coolants = new Seq<>();
      for (Liquid l : content.liquids())
        if (acceptsCoolant(l) && !l.isHidden())
          coolants.add(l);
      if (coolants.size >= 2) {
        // 配置面板是游戏 UI 的一部分，出问题也不能把面板带崩（和 display 一样兜住）
        ComboUi.safe("combineditemturret:coolantSelect", () -> {
          if (present.size >= 2)
            table.row();
          ItemSelection.buildTable(CombinedItemTurret.this, table, coolants,
              () -> selectedCoolant, l -> configure(l));
        });
      }
    }

    @Override
    public Object config() {
      return selected;
    }

    // 地图区里只写"原版物品炮台那一份字节"（super.write = ItemTurretBuild 的弹仓格式），
    // 模组自己的字段（选中的弹药 + 精确弹量）挪到自定义存档块 ComboSaveState —— 详见 ComboSaved。
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
      write.i(ammo.size);
      for (Turret.AmmoEntry e : ammo) {
        if (e == null || ((ItemTurret.ItemEntry) e).item == null || e.type() == null)
          continue;
        Item item = ((ItemTurret.ItemEntry) e).item;
        write.i(item.id);
        write.i(e.amount);
      }
    }

    @Override
    public void read(Reads read, byte revision) {
      super.read(read, revision);
      // 旧档（≤2.6）：模组字段直接续写在地图区里
      if (revision >= 10) {
        readCombo(read, revision);
        return;
      }
      comboDirty = true;
    }

    @Override
    public void readCombo(Reads read, byte revision) {
      short id = read.s();
      selected = id == -1 ? null : content.item(id);
      int entries = read.i();
      ammo.clear();
      totalAmmo = 0;
      for (int i = 0; i < entries; i++) {
        Item item = content.item(read.i());
        int amount = read.i();
        if (item == null || amount <= 0)
          continue;
        ammo.add(AmmoEntries.create((ItemTurret) block, item, amount));
        totalAmmo += amount;
      }
      if (selected != null && !acceptsAmmo(selected))
        selected = null;
      if (selected == null) {
        for (Turret.AmmoEntry e : ammo) {
          if (e == null || ((ItemTurret.ItemEntry) e).item == null || e.amount <= 0)
            continue;
          Item it = ((ItemTurret.ItemEntry) e).item;
          if (acceptsAmmo(it)) {
            selected = it;
            break;
          }
        }
      }
      comboDirty = true;
    }

    @Override
    public void display(Table table) {
      // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
      ComboUi.safe("combineditemturret:display", () -> displayInner(table));
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
          String title = count > 1 ? "[accent]组合物品炮塔[] x" + count + "\n" + block.getDisplayName(tile)
              : block.getDisplayName(tile);
          t.labelWrap(title).left().width(160f).padLeft(4);
        }).growX().left();
        cont.row();
        if (team != mindustry.Vars.player.team())
          return;

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
    }
  }
}
