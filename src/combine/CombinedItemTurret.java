package combine;

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
    // 原版 ItemTurret 的 ConsumeItemFilter 高亮 ammo.peek()。组合炮塔共享 ammo 队列后，
    // 队列顶部是全组第一发弹药，不一定是当前炮塔可用/选中的弹药，必须改成高亮 getAmmoContent()。
    replaceAmmoConsumer();
    conductivePower = true; 
    configurable = true;
    config(Item.class, (CombinedItemTurretBuild tile, Item i) -> tile.selected = i);
    configClear((CombinedItemTurretBuild tile) -> tile.selected = null);

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

  public class CombinedItemTurretBuild extends ItemTurretBuild {

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
      IntSet visited = new IntSet();
      Queue<CombinedItemTurretBuild> queue = new Queue<>();
      queue.add(this);
      visited.add(pos());
      while (!queue.isEmpty()) {
        CombinedItemTurretBuild cur = queue.removeFirst();
        CombinedItemTurret curBlock = (CombinedItemTurret) cur.block;
        for (Building b : cur.proximity) {
          if (!(b instanceof CombinedItemTurretBuild o) || o.team != team || !o.isValid()
              || visited.contains(o.pos()))
            continue;
          CombinedItemTurret otherBlock = (CombinedItemTurret) o.block;
          if (otherBlock != curBlock && !curBlock.allowCrossTypeCombo && !otherBlock.allowCrossTypeCombo)
            continue;
          visited.add(o.pos());
          queue.addLast(o);
          comboGroup.add(o);
        }
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
      java.util.IdentityHashMap<CombinedItemTurretBuild, Boolean> oldLeaders =
          new java.util.IdentityHashMap<>();
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
          mergedTotals.merge(ie.item, (long)Math.max(0, e.amount), Long::sum);
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
      int mergedCap = (int)Math.max(0, newLeader.perItemCap());
      for (java.util.Map.Entry<Item, Long> entry : mergedTotals.entrySet()) {
        int amount = (int)Math.min(mergedCap, entry.getValue());
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
      super.updateTile();
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
        totals.merge(ie.item, (long)Math.max(0, e.amount), Long::sum);
      }

      ammo.clear();
      int cap = (int)Math.max(0, perItemCap());
      for (java.util.Map.Entry<Item, Long> entry : totals.entrySet()) {
        int amount = (int)Math.min(cap, entry.getValue());
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
      int add = (int)type.ammoMultiplier;
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
      if (present.size < 2)
        return;
      ItemSelection.buildTable(CombinedItemTurret.this, table, present,
          () -> selected, i -> configure(i));
    }

    @Override
    public Object config() {
      return selected;
    }

    

    @Override
    public byte version() {
      return 10;
    }

    @Override
    public void write(Writes write) {
      super.write(write);
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

      if (revision >= 10) {
      if (revision >= 10) {
        short id = read.s();
        selected = id == -1 ? null : content.item(id);
      }
      if (revision >= 10) {
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
