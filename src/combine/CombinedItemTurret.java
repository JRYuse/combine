package combine;

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
import mindustry.world.Block;
import mindustry.world.blocks.ItemSelection;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.consumers.Consume;

import static mindustry.Vars.*;















public class CombinedItemTurret extends ItemTurret {

  public boolean allowCrossTypeCombo = true;

  public CombinedItemTurret(String name) {
    super(name);
    buildType = CombinedItemTurretBuild::new;
    hasItems = true;
    sync = true;
    
  }

  @Override
  public void init() {
    super.init();
    conductivePower = true; 
    configurable = true;
    config(Item.class, (CombinedItemTurretBuild tile, Item i) -> tile.selected = i);
    configClear((CombinedItemTurretBuild tile) -> tile.selected = null);
  }

  @Override
  public void setBars() {
    super.setBars();
    
    
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
            float cap = Math.max(e.block.liquidCapacity, 1f);
            return cur == null || e.liquids.get(cur) <= 0.001f
                ? "[lightgray]液体[]"
                : cur.localizedName + " " + Strings.fixed(e.liquids.get(cur), 1) + "/" + Strings.fixed(cap, 1);
          },
          () -> {
            Liquid cur = e.liquids == null ? null : e.liquids.current();
            return cur == null ? Pal.gray : (cur.barColor != null ? cur.barColor : cur.color);
          },
          () -> e.liquids == null ? 0f : e.liquids.currentAmount() / Math.max(e.block.liquidCapacity, 1f)));
    }
    
    boolean first = true;
    for (Item i : ammoTypes.keys()) {
      final Item it = i;
      String key = first ? "ammo" : "ammo-" + i.name;
      first = false;
      addBar(key, (CombinedItemTurretBuild e) -> new Bar(
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
    public CombinedItemTurretBuild comboLeader;
    public Seq<CombinedItemTurretBuild> comboGroup = new Seq<>();
    public boolean comboDirty = true;
    
    public Item selected;
    
    public Item lastInput;

    

    public CombinedItemTurretBuild leader() {
      if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null))
        comboLeader = null;
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
      float total = 0f;
      for (CombinedItemTurretBuild b : group())
        if (b.isValid())
          total += ((Turret) b.block).maxAmmo;
      return Math.max(total, 1f);
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
      if (selected != null) {
        Turret.AmmoEntry e = acceptsAmmo(selected) ? findEntry(selected) : null;
        return e != null && e.amount >= ammoPerShot ? e : null;
      }
      if (lastInput != null && acceptsAmmo(lastInput)) {
        Turret.AmmoEntry e = findEntry(lastInput);
        if (e != null && e.amount >= ammoPerShot)
          return e;
      }
      for (Turret.AmmoEntry e : ammo) {
        Item it = ((ItemTurret.ItemEntry) e).item;
        if (acceptsAmmo(it) && e.amount >= ammoPerShot)
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
      newLeader.comboGroup = newGroup;
      for (CombinedItemTurretBuild b : newGroup) {
        if (b.isValid()) {
          b.comboLeader = newLeader;
          b.comboGroup = newGroup;
          b.comboDirty = false;
        }
      }
      newLeader.comboLeader = null;

      
      
      for (CombinedItemTurretBuild b : newGroup) {
        if (b.isValid() && b != newLeader)
          b.ammo = newLeader.ammo;
      }
      newLeader.syncAmmo();

      for (CombinedItemTurretBuild old : oldGroup) {
        if (old != this && old.isValid() && !newGroup.contains(old)) {
          old.comboLeader = null;
          old.comboGroup = new Seq<>();
          old.comboDirty = true;
        }
      }
    }

    

    @Override
    public void created() {
      super.created();
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
        
        ammo = new Seq<>();
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
          b.ammo = newSeqs[i];
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

    @Override
    public void updateTile() {
      if (isLeader() && comboDirty)
        rebuildCombo();
      super.updateTile();
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
      if (leader().totalAmmo == 0)
        arc.Events.fire(Trigger.resupplyTurret);

      boolean found = false;
      for (int i = 0; i < ammo.size; i++) {
        ItemTurret.ItemEntry entry =
            (ItemTurret.ItemEntry) ammo.get(i);
        if (entry.item == item) {
          entry.amount += (int) type.ammoMultiplier;
          ammo.swap(i, ammo.size - 1);
          found = true;
          break;
        }
      }
      if (!found)
        ammo.add(AmmoEntries.create(owner, item, (int) type.ammoMultiplier));
      lastInput = item;
      syncAmmo();
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
    public boolean hasAmmo() {
      return effectiveEntry() != null;
    }

    @Override
    public BulletType useAmmo() {
      if (cheating())
        return peekAmmo();
      Turret.AmmoEntry entry = effectiveEntry();
      if (entry == null)
        return null;
      entry.amount -= ammoPerShot;
      if (entry.amount <= 0)
        ammo.remove(entry);
      totalAmmo -= ammoPerShot;
      totalAmmo = Math.max(totalAmmo, 0);
      syncAmmo(); 
      return typeForEntry(entry);
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
      return 4;
    }

    @Override
    public void write(Writes write) {
      super.write(write);
      write.s(selected == null ? -1 : selected.id);
      write.i(ammo.size);
      for (Turret.AmmoEntry e : ammo) {
        Item item = ((ItemTurret.ItemEntry) e).item;
        write.i(item.id);
        write.i(e.amount);
      }
    }

    @Override
    public void read(Reads read, byte revision) {
      super.read(read, revision);
      if (revision >= 3) {
        short id = read.s();
        selected = id == -1 ? null : content.item(id);
      }
      if (revision >= 4) {
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
      }
      comboDirty = true;
    }

    

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
      for (CombinedItemTurretBuild member : group()) {
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
      if (ammo != null) {
        for (Turret.AmmoEntry e : ammo) {
          Item it = ((ItemTurret.ItemEntry) e).item;
          table.add("[lightgray]" + it.localizedName + ":[] " + e.amount + "/" + (int) perItemCap())
              .left();
          table.row();
        }
      }
    }
  }
}
