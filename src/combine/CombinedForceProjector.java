package combine;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.Blending;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.func.Cons;
import arc.math.Mathf;
import arc.math.geom.Intersector;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.struct.IntSet;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.content.Fx;
import mindustry.game.EventType.Trigger;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.logic.LAccess;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.ui.ReqImage;
import mindustry.world.Block;
import mindustry.world.consumers.Consume;
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

import mindustry.world.blocks.defense.ForceProjector;

public class CombinedForceProjector extends ForceProjector {
    public boolean allowCrossTypeCombo = true;
    public float baseLiquidCapacity = 10f;
    public float displayLiquid;

    public Seq<Item> cachedItems = new Seq<>();
    public Seq<Liquid> cachedLiquids = new Seq<>();

    // 自己的 shieldConsumer，访问共享状态
    protected static CombinedForceProjector paramBlock;
    protected static CombinedForceProjectorBuild paramEntity;
    protected static final Cons<Bullet> comboShieldConsumer = bullet -> {
        if (bullet.team != paramEntity.team && bullet.type.absorbable && !bullet.absorbed &&
                Intersector.isInRegularPolygon(paramBlock.sides, paramEntity.x, paramEntity.y, paramEntity.realRadius(),
                        paramBlock.shieldRotation, bullet.x, bullet.y)) {
            bullet.absorb();
            paramBlock.hitSound.at(bullet.x, bullet.y, 1f + Mathf.range(0.1f), paramBlock.hitSoundVolume);
            paramBlock.absorbEffect.at(bullet);
            paramEntity.hit = 1f;
            paramEntity.setBuildup(paramEntity.getBuildup() + bullet.type.shieldDamage(bullet));
        }
    };

    public CombinedForceProjector(String name) {
        super(name);
        buildType = () -> new CombinedForceProjectorBuild();
        conductivePower = true;
        hasItems = true;
        hasLiquids = true;
        sync = true;
    }

    @Override
    public void init() {
        super.init();
        if (liquidCapacity != 9999f) {
            baseLiquidCapacity = liquidCapacity;
            displayLiquid = liquidCapacity;
        }
        liquidCapacity = 9999f;
        if (!hasLiquids)
            displayLiquid = 0;
        hasLiquids = true;
        hasItems = true;

        cachedItems.clear();
        conductivePower = true;
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

        // 禁用环境音循环：避免触发 SoundControl 环境音线程在 Android 上的
        // IllegalThreadStateException（Thread.start 崩溃）
        ambientSound = mindustry.gen.Sounds.none;
        ambientSoundVolume = 0f;
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.remove(Stat.liquidCapacity);
        stats.add(Stat.liquidCapacity, displayLiquid, StatUnit.liquidUnits);
    }

    public class CombinedForceProjectorBuild extends ForceBuild {
        public CombinedForceProjectorBuild comboLeader;
        public Seq<CombinedForceProjectorBuild> comboGroup = new Seq<>();
        public boolean comboDirty = true;
        public float comboTotalLiquidCap = 0f;
        public int comboTotalItemCap = 0;
        public int pendingLeaderPos = -1;

        // 组合体共享护盾核心状态（只有 leader 持有）
        public float comboBuildup = 0f;
        public boolean comboBroken = true;

        public CombinedForceProjectorBuild leader() {
            if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null))
                comboLeader = null;
            return comboLeader == null ? this : comboLeader;
        }

        public boolean isLeader() {
            return leader() == this;
        }

        public Seq<CombinedForceProjectorBuild> group() {
            CombinedForceProjectorBuild l = leader();
            if (l.comboGroup == null)
                l.comboGroup = new Seq<>();
            return l.comboGroup;
        }

        // 获取/设置共享 buildup / broken
        public float getBuildup() {
            return isLeader() ? comboBuildup : leader().comboBuildup;
        }

        public void setBuildup(float v) {
            if (isLeader())
                comboBuildup = v;
            else
                leader().comboBuildup = v;
        }

        public boolean getBroken() {
            return isLeader() ? comboBroken : leader().comboBroken;
        }

        public void setBroken(boolean v) {
            if (isLeader())
                comboBroken = v;
            else
                leader().comboBroken = v;
        }

        // 同步共享状态到本地字段（供原版方法使用）
        public void syncToLocal() {
            this.buildup = getBuildup();
            this.broken = getBroken();
        }

        // ===== 组合体总盾容 =====
        public float getComboMaxShield() {
            float total = 0f;
            for (CombinedForceProjectorBuild member : group()) {
                if (!member.isValid())
                    continue;
                CombinedForceProjector b = (CombinedForceProjector) member.block;
                total += b.shieldHealth + b.phaseShieldBoost * member.phaseHeat;
            }
            return total;
        }

        // ===== 组合体总修复速度（所有成员贡献）=====
        public float getComboCooldown() {
            float total = 0f;
            for (CombinedForceProjectorBuild member : group()) {
                if (!member.isValid())
                    continue;
                CombinedForceProjector b = (CombinedForceProjector) member.block;
                float scale = !getBroken() ? b.cooldownNormal : b.cooldownBrokenBase;
                if (b.coolantConsumer != null && b.coolantConsumer.efficiency(member) > 0) {
                    b.coolantConsumer.update(member);
                    scale *= (b.cooldownLiquid * (1f + (member.liquids.current().heatCapacity - 0.4f) * 0.9f));
                }
                total += scale;
            }
            return total;
        }

        // ===== 标准组合体逻辑 =====
        public void rebuildCombo() {
            Seq<CombinedForceProjectorBuild> oldGroup = comboGroup != null ? new Seq<>(comboGroup) : new Seq<>();
            comboGroup = new Seq<>();
            comboGroup.add(this);
            IntSet visited = new IntSet();
            Queue<CombinedForceProjectorBuild> queue = new Queue<>();
            queue.add(this);
            visited.add(pos());
            while (!queue.isEmpty()) {
                CombinedForceProjectorBuild cur = queue.removeFirst();
                for (Building b : cur.proximity) {
                    if (b instanceof CombinedForceProjectorBuild o && o.team == team && o.isValid()
                            && !visited.contains(o.pos())) {
                        CombinedForceProjector cb = (CombinedForceProjector) cur.block,
                                ob = (CombinedForceProjector) o.block;
                        if (cur.block == o.block || cb.allowCrossTypeCombo || ob.allowCrossTypeCombo) {
                            visited.add(o.pos());
                            queue.addLast(o);
                            comboGroup.add(o);
                        }
                    }
                }
            }
            CombinedForceProjectorBuild newLeader = this;
            for (CombinedForceProjectorBuild b : comboGroup)
                if (b.isValid() && b.pos() < newLeader.pos())
                    newLeader = b;
            Seq<CombinedForceProjectorBuild> newGroup = new Seq<>(comboGroup);
            newLeader.comboGroup = newGroup;

            // 护盾核心状态继承：重建【前】收拢所有旧 leader（本组 + 被合并组）
            float carriedBuildup = 0f;
            boolean carriedBroken = false;
            for (CombinedForceProjectorBuild b : comboGroup) {
                if (b.isValid() && b.comboLeader == null) {
                    carriedBuildup += b.comboBuildup;
                    b.comboBuildup = 0f;
                    if (b.comboBroken)
                        carriedBroken = true;
                }
            }

            for (CombinedForceProjectorBuild b : newGroup) {
                if (b.isValid()) {
                    b.comboLeader = newLeader;
                    b.comboGroup = newGroup;
                    b.comboDirty = false;
                }
            }
            newLeader.comboLeader = null;
            float totalLiqCap = 0f;
            int totalItemCap = 0;
            for (CombinedForceProjectorBuild b : newGroup) {
                if (b.isValid()) {
                    totalLiqCap += ((CombinedForceProjector) b.block).baseLiquidCapacity;
                    totalItemCap += b.block.itemCapacity;
                }
            }
            for (CombinedForceProjectorBuild b : newGroup) {
                if (b.isValid()) {
                    b.comboTotalLiquidCap = totalLiqCap;
                    b.comboTotalItemCap = totalItemCap;
                }
            }
            if (oldGroup.size > newGroup.size)
                splitAssets(oldGroup, newGroup);
            shareModules(newLeader);
            newLeader.comboBuildup = carriedBuildup;
            newLeader.comboBroken = carriedBroken;
            // 新成员加入组合体时，所有力墙立刻展开
            if (oldGroup.size > 0 && newGroup.size > oldGroup.size) {
                newLeader.comboBroken = false;
            }
            for (CombinedForceProjectorBuild old : oldGroup) {
                if (old != this && old.isValid() && !newGroup.contains(old)) {
                    old.comboLeader = null;
                    old.comboGroup = new Seq<>();
                    old.comboDirty = true;
                    old.comboTotalLiquidCap = 0f;
                    old.comboTotalItemCap = 0;
                }
            }
        }

        public void splitAssets(Seq<CombinedForceProjectorBuild> oldGroup, Seq<CombinedForceProjectorBuild> newGroup) {
            CombinedForceProjectorBuild oldLeader = null;
            for (CombinedForceProjectorBuild b : oldGroup) {
                if (!b.isValid())
                    continue;
                for (CombinedForceProjectorBuild o : oldGroup) {
                    if (o != b && o.isValid() && (o.items == b.items || o.liquids == b.liquids)) {
                        oldLeader = b;
                        break;
                    }
                }
                if (oldLeader != null)
                    break;
            }
            if (oldLeader == null) {
                for (CombinedForceProjectorBuild b : oldGroup)
                    if (b.isValid()) {
                        oldLeader = b;
                        break;
                    }
            }
            if (oldLeader == null)
                oldLeader = this;
            ItemModule oldItems = oldLeader.items;
            LiquidModule oldLiquids = oldLeader.liquids;
            Seq<CombinedForceProjectorBuild> kicked = new Seq<>();
            for (CombinedForceProjectorBuild b : oldGroup)
                if (b.isValid() && !newGroup.contains(b))
                    kicked.add(b);
            int oldTotalItemCap = 0;
            float oldTotalLiquidCap = 0f;
            for (CombinedForceProjectorBuild b : oldGroup) {
                if (b.isValid()) {
                    oldTotalItemCap += b.block.itemCapacity;
                    oldTotalLiquidCap += ((CombinedForceProjector) b.block).baseLiquidCapacity;
                }
            }
            int[] itemCaps = new int[kicked.size];
            float[] liquidCaps = new float[kicked.size];
            int kickedTotalItemCap = 0;
            float kickedTotalLiquidCap = 0f;
            for (int i = 0; i < kicked.size; i++) {
                CombinedForceProjectorBuild b = kicked.get(i);
                itemCaps[i] = b.block.itemCapacity;
                liquidCaps[i] = ((CombinedForceProjector) b.block).baseLiquidCapacity;
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
                        int share = ideal; // 不做容量硬截断：超出份额暂时超容保留，宁可超容也不丢物品
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
                        float share = ideal; // 不做容量硬截断：超出份额暂时超容保留，宁可超容也不丢物品
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
                for (CombinedForceProjectorBuild b : newGroup)
                    if (b.isValid())
                        b.items = oldItems;
            if (oldLiquids != null)
                for (CombinedForceProjectorBuild b : newGroup)
                    if (b.isValid())
                        b.liquids = oldLiquids;
            for (int i = 0; i < kicked.size; i++) {
                CombinedForceProjectorBuild b = kicked.get(i);
                b.items = newItemMods[i];
                b.liquids = newLiquidMods[i];
            }
        }

        public void shareModules(CombinedForceProjectorBuild leader) {
            int totalItemCap = leader.comboTotalItemCap;
            float totalLiquidCap = leader.comboTotalLiquidCap;
            if (leader.items == null) {
                for (CombinedForceProjectorBuild m : group())
                    if (m.items != null) {
                        leader.items = m.items;
                        break;
                    }
            }
            if (leader.liquids == null) {
                for (CombinedForceProjectorBuild m : group())
                    if (m.liquids != null) {
                        leader.liquids = m.liquids;
                        break;
                    }
            }
            ObjectSet<ItemModule> processedItems = new ObjectSet<>();
            ObjectSet<LiquidModule> processedLiquids = new ObjectSet<>();
            if (leader.items != null) {
                processedItems.add(leader.items);
                for (CombinedForceProjectorBuild m : group()) {
                    if (m != leader && m.isValid() && m.items != null && !processedItems.contains(m.items)) {
                        processedItems.add(m.items);
                        for (Item item : content.items()) {
                            int amt = m.items.get(item);
                            if (amt > 0) {
                                int canAccept = Math.max(0, totalItemCap - leader.items.total());
                                int transfer = amt; // 全额并入：总量必然 ≤ 合并后容量，截断只会丢物品
                                if (transfer > 0)
                                    leader.items.add(item, transfer);
                            }
                        }
                    }
                }
                for (CombinedForceProjectorBuild m : group())
                    if (m.isValid())
                        m.items = leader.items;
            }
            if (leader.liquids != null) {
                processedLiquids.add(leader.liquids);
                for (CombinedForceProjectorBuild m : group()) {
                    if (m != leader && m.isValid() && m.liquids != null && !processedLiquids.contains(m.liquids)) {
                        processedLiquids.add(m.liquids);
                        for (Liquid liquid : content.liquids()) {
                            float amt = m.liquids.get(liquid);
                            if (amt > 0.001f) {
                                float canAccept = Math.max(0f, totalLiquidCap - leader.liquids.currentAmount());
                                float transfer = amt; // 全额并入：总量必然 ≤ 合并后容量，截断只会丢物品
                                if (transfer > 0.001f)
                                    leader.liquids.add(liquid, transfer);
                            }
                        }
                    }
                }
                for (CombinedForceProjectorBuild m : group())
                    if (m.isValid())
                        m.liquids = leader.liquids;
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
            for (CombinedForceProjectorBuild m : group())
                if (m.isValid())
                    m.comboDirty = true;
        }

        @Override
        public void onRemoved() {
            Seq<CombinedForceProjectorBuild> members = new Seq<>(group());
            boolean wasLeader = isLeader();
            ItemModule oldItems = this.items;
            LiquidModule oldLiquids = this.liquids;
            if (!wasLeader) {
                if (items != null) {
                    boolean shared = false;
                    for (CombinedForceProjectorBuild m : members)
                        if (m != this && m.isValid() && m.items == this.items) {
                            shared = true;
                            break;
                        }
                    if (shared)
                        items = new ItemModule();
                }
                if (liquids != null) {
                    boolean shared = false;
                    for (CombinedForceProjectorBuild m : members)
                        if (m != this && m.isValid() && m.liquids == this.liquids) {
                            shared = true;
                            break;
                        }
                    if (shared)
                        liquids = new LiquidModule();
                }
            }
            if (wasLeader) {
                Seq<CombinedForceProjectorBuild> survivors = new Seq<>();
                for (CombinedForceProjectorBuild b : members)
                    if (b != this && b.isValid())
                        survivors.add(b);
                int[] itemCaps = new int[survivors.size];
                float[] liquidCaps = new float[survivors.size];
                int totalItemCap = 0;
                float totalLiquidCap = 0f;
                for (int i = 0; i < survivors.size; i++) {
                    CombinedForceProjectorBuild b = survivors.get(i);
                    itemCaps[i] = b.block.itemCapacity;
                    liquidCaps[i] = ((CombinedForceProjector) b.block).baseLiquidCapacity;
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
                            int share = ideal; // 不做容量硬截断：超出份额暂时超容保留，宁可超容也不丢物品
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
                            float share = ideal; // 不做容量硬截断：超出份额暂时超容保留，宁可超容也不丢物品
                            if (share > 0.001f) {
                                liquidMods[i].add(liquid, share);
                                allocated[i] += share;
                                remaining -= share;
                            }
                        }
                    }
                }
                // 共享护盾 buildup/broken 分配给幸存者（原实现随 leader 一起销毁）
                for (int i = 0; i < survivors.size; i++) {
                    CombinedForceProjectorBuild b = survivors.get(i);
                    b.comboBuildup = survivors.size == 1 ? comboBuildup : comboBuildup / survivors.size;
                    b.comboBroken = comboBroken;
                }

                for (int i = 0; i < survivors.size; i++) {
                    CombinedForceProjectorBuild b = survivors.get(i);
                    b.items = itemMods[i];
                    b.liquids = liquidMods[i];
                    b.comboLeader = null;
                    b.comboGroup = new Seq<>();
                    b.comboDirty = true;
                    b.comboTotalLiquidCap = 0f;
                    b.comboTotalItemCap = 0;
                }
            } else {
                CombinedForceProjectorBuild leader = leader();
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

        // ===== 覆盖核心 updateTile：共享盾容 + 共享修复，各自独立绘制/范围 =====
        @Override
        public void updateTile() {
            if (pendingLeaderPos != -1) {
                Building b = world.build(pendingLeaderPos);
                if (b instanceof CombinedForceProjectorBuild leaderBuild && leaderBuild.isValid()
                        && leaderBuild.team == team) {
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
            if (isLeader() && comboDirty)
                rebuildCombo();
            if (liquids != null && comboTotalLiquidCap > 0.001f) {
                for (Liquid l : content.liquids()) {
                    float amt = liquids.get(l);
                    if (amt > comboTotalLiquidCap + 0.001f) {
                        liquids.remove(l, amt - comboTotalLiquidCap);
                    }
                }
            }

            // 同步共享 buildup/broken 到本地字段
            syncToLocal();

            CombinedForceProjector block = (CombinedForceProjector) this.block;

            // phaseHeat 独立更新
            boolean phaseValid = itemConsumer != null && itemConsumer.efficiency(this) > 0;
            phaseHeat = Mathf.lerpDelta(phaseHeat, Mathf.num(phaseValid), 0.1f);
            if (phaseValid && !broken && timer(timerUse, block.phaseUseTime / timeScale) && efficiency > 0) {
                consume();
            }

            // warmup / radscl 独立更新
            warmup = Mathf.lerpDelta(warmup, efficiency, 0.1f);
            radscl = Mathf.lerpDelta(radscl, broken ? 0f : warmup, 0.05f);

            if (Mathf.chanceDelta(buildup / block.shieldHealth * 0.1f)) {
                Fx.reactorsmoke.at(x + Mathf.range(tilesize / 2f), y + Mathf.range(tilesize / 2f));
            }

            // 只有 leader 负责修复共享 buildup
            if (isLeader() && comboBuildup > 0) {
                float comboCooldown = getComboCooldown();
                comboBuildup = Math.max(0f, comboBuildup - delta() * comboCooldown);
            }
            if (isLeader()) {
                if (comboBroken && comboBuildup <= 0) {
                    comboBroken = false;
                }
                float maxShield = getComboMaxShield();
                if (comboBuildup >= maxShield && !comboBroken) {
                    comboBroken = true;
                    comboBuildup = maxShield;
                    block.shieldBreakEffect.at(x, y, realRadius(), team.color, block);
                    block.breakSound.at(x, y);
                    if (team != state.rules.defaultTeam) {
                        Events.fire(Trigger.forceProjectorBreak);
                    }
                }
            }

            if (hit > 0f) {
                hit -= 1f / 5f * Time.delta;
            }

            // 每个成员独立执行子弹偏转
            deflectBullets();
        }

        // ===== 自己的 deflectBullets，使用共享状态 =====
        @Override
        public void deflectBullets() {
            float realRadius = realRadius();
            if (realRadius > 0 && !broken) {
                paramBlock = (CombinedForceProjector) this.block;
                paramEntity = this;
                Groups.bullet.intersect(x - realRadius, y - realRadius, realRadius * 2f, realRadius * 2f,
                        comboShieldConsumer);
            }
        }

        // ===== 保持原版 realRadius：每个成员用自己的 radius =====
        @Override
        public float realRadius() {
            CombinedForceProjector b = (CombinedForceProjector) block;
            return (b.radius + phaseHeat * b.phaseRadiusBoost) * radscl;
        }

        // ===== 保持原版 absorbExplosion =====
        @Override
        public boolean absorbExplosion(float ex, float ey, float damage) {
            boolean absorb = !broken && Intersector.isInRegularPolygon(
                    ((CombinedForceProjector) block).sides, x, y, realRadius(),
                    ((CombinedForceProjector) block).shieldRotation, ex, ey);
            if (absorb) {
                absorbEffect.at(ex, ey);
                hit = 1f;
                setBuildup(Math.min(getBuildup() + damage * ((CombinedForceProjector) block).crashDamageMultiplier,
                        getComboMaxShield()));
                syncToLocal();
            }
            return absorb;
        }

        // ===== 保持原版 draw：每个成员独立绘制自己的护盾 =====
        @Override
        public void draw() {
            super.draw();
            syncToLocal();
            if (buildup > 0f) {
                Draw.alpha(buildup / ((CombinedForceProjector) block).shieldHealth * 0.75f);
                Draw.z(Layer.blockAdditive);
                Draw.blend(Blending.additive);
                Draw.rect(topRegion, x, y);
                Draw.blend();
                Draw.z(Layer.block);
                Draw.reset();
            }
            drawShield();
        }

        // ===== 保持原版 drawShield：每个成员独立绘制 =====
        @Override
        public void drawShield() {
            syncToLocal();
            if (!broken) {
                float radius = realRadius();
                if (radius > 0.001f) {
                    Draw.color(team.color, Color.white, Mathf.clamp(hit));
                    if (renderer.animateShields) {
                        Draw.z(Layer.shields + 0.001f * hit);
                        Fill.poly(x, y, ((CombinedForceProjector) block).sides, radius,
                                ((CombinedForceProjector) block).shieldRotation);
                    } else {
                        Draw.z(Layer.shields);
                        Lines.stroke(1.5f);
                        Draw.alpha(0.09f + Mathf.clamp(0.08f * hit));
                        Fill.poly(x, y, ((CombinedForceProjector) block).sides, radius,
                                ((CombinedForceProjector) block).shieldRotation);
                        Draw.alpha(1f);
                        Lines.poly(x, y, ((CombinedForceProjector) block).sides, radius,
                                ((CombinedForceProjector) block).shieldRotation);
                        Draw.reset();
                    }
                }
            }
            Draw.reset();
        }

        @Override
        public double sense(LAccess sensor) {
            if (sensor == LAccess.heat)
                return getBuildup();
            if (sensor == LAccess.shield)
                return getBroken() ? 0f : Math.max(getComboMaxShield() - getBuildup(), 0);
            return super.sense(sensor);
        }

        // ===== 物品/液体交互 =====
        @Override
        public boolean shouldAmbientSound() {
            // 禁用环境音循环，避免触发 SoundControl 环境音线程在 Android 上的
            // IllegalThreadStateException（Thread.start 崩溃）
            return false;
        }

        @Override
        public boolean acceptItem(Building source, Item item) {
            if (!block.hasItems)
                return false;
            boolean needed = false;
            for (CombinedForceProjectorBuild member : group()) {
                if (member.isValid() && member.block.consumesItem(item)) {
                    needed = true;
                    break;
                }
            }
            return needed && items.get(item) < getMaximumAccepted(item); // 按种类检查：每种原料各有份额，先到的不堵死其它的：不再按种类各装满一份
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
            boolean needed = false;
            for (CombinedForceProjectorBuild member : group()) {
                if (member.isValid() && member.block.consumesLiquid(liquid)) {
                    needed = true;
                    break;
                }
            }
            return needed && liquids.get(liquid) < comboTotalLiquidCap - 0.001f;
        }

        @Override
        public void handleLiquid(Building source, Liquid liquid, float amount) {
            if (amount <= 0.001f)
                return;
            float currentTotal = liquids.currentAmount();
            float canAccept = Math.max(0f, comboTotalLiquidCap - currentTotal);
            float actual = Math.min(amount, canAccept);
            if (actual > 0.001f)
                liquids.add(liquid, actual);
            // FIX: 将未接收的液体退回源端，防止因 block.liquidCapacity=9999f 导致源端过度扣除
            float refund = amount - actual;
            if (refund > 0.001f && source != null && source.liquids != null)
                source.liquids.add(liquid, refund);
        }

        // ===== 显示面板 =====
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
                    String title = count > 1 ? "[accent]组合力场投影[] x" + count + "\n" + block.getDisplayName(tile)
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

                Table missingTable = new Table();
                missingTable.left();
                missingTable.update(() -> {
                    missingTable.clearChildren();
                    missingTable.defaults().pad(2);
                    LiquidModule sharedLiq = liquids;
                    if (sharedLiq == null) {
                        CombinedForceProjectorBuild l = leader();
                        if (l != null)
                            sharedLiq = l.liquids;
                    }
                    if (sharedLiq != null) {
                        for (Liquid liquid : cachedLiquids) {
                            if (sharedLiq.get(liquid) < 0.001f) {
                                missingTable.add(new ReqImage(liquid.uiIcon, () -> false)).size(iconMed);
                            }
                        }
                    }
                    if (items != null) {
                        for (Item item : cachedItems) {
                            if (items.get(item) < 1) {
                                missingTable.add(new ReqImage(item.uiIcon, () -> false)).size(iconMed);
                            }
                        }
                    }
                });
                if (missingTable.getChildren().size > 0) {
                    cont.add(missingTable).growX().left().padTop(4);
                    cont.row();
                }

                Table comboIO = new Table();
                comboIO.left();
                comboIO.update(() -> {
                    comboIO.clearChildren();
                    buildComboIO(comboIO);
                });
                cont.add(comboIO).growX().left();
            }).width(260f).left();
        }

        public void buildComboBars(Table table) {
            if (!Mathf.zero(block.health, 0.001f)) {
                final float h = health, mh = maxHealth;
                table.add(new Bar(() -> Core.bundle.get("stat.health", "Health") + " " + (int) Math.max(h, 0),
                        () -> Pal.health, () -> Mathf.clamp(h / mh)));
                table.row();
            }
            float totalPower = 0f;
            for (CombinedForceProjectorBuild member : group()) {
                if (member.isValid() && member.block.consPower != null)
                    totalPower += member.block.consPower.usage;
            }
            if (totalPower > 0 && power != null) {
                final float tp = totalPower;
                table.add(new Bar(() -> "电力 " + Strings.fixed(tp * power.status * 60f, 1) + " ⚡/s", () -> Pal.power,
                        () -> power.status));
                table.row();
            }
            final float bs = getBuildup(), ms = getComboMaxShield();
            if (ms > 0.001f) {
                table.add(new Bar(
                        () -> "护盾 " + Strings.fixed(Math.max(ms - bs, 0), 0) + "/" + Strings.fixed(ms, 0),
                        () -> Pal.accent,
                        () -> getBroken() ? 0f : Math.max(0f, 1f - bs / ms)));
                table.row();
            }
            Seq<Item> involvedItems = new Seq<>();
            for (CombinedForceProjectorBuild member : group()) {
                if (member.isValid()) {
                    for (Item item : ((CombinedForceProjector) member.block).cachedItems) {
                        if (!involvedItems.contains(item))
                            involvedItems.add(item);
                    }
                }
            }
            if (items != null) {
                for (Item item : involvedItems) {
                    int total = items.get(item);
                    if (total > 0) {
                        final int t = total, c = Math.max(comboTotalItemCap, 1);
                        table.add(new Bar(() -> item.localizedName + ": " + t + "/" + c, () -> item.color,
                                () -> (float) t / c));
                        table.row();
                    }
                }
            }
            LiquidModule sharedLiq = this.liquids;
            if (sharedLiq == null) {
                CombinedForceProjectorBuild l = leader();
                if (l != null)
                    sharedLiq = l.liquids;
            }
            if (sharedLiq == null) {
                for (CombinedForceProjectorBuild member : group()) {
                    if (member.liquids != null) {
                        sharedLiq = member.liquids;
                        break;
                    }
                }
            }
            if (sharedLiq != null) {
                for (Liquid liquid : cachedLiquids) {
                    float total = sharedLiq.get(liquid);
                    final float t = total, c = Math.max(comboTotalLiquidCap, 1f);
                    table.add(
                            new Bar(() -> liquid.localizedName + ": " + Strings.fixed(t, 1) + "/" + Strings.fixed(c, 1),
                                    () -> liquid.barColor != null ? liquid.barColor : liquid.color, () -> t / c));
                    table.row();
                }
                for (Liquid liquid : content.liquids()) {
                    if (cachedLiquids.contains(liquid))
                        continue;
                    float total = sharedLiq.get(liquid);
                    if (total > 0.001f) {
                        final float t = total, c = Math.max(comboTotalLiquidCap, 1f);
                        table.add(new Bar(
                                () -> liquid.localizedName + ": " + Strings.fixed(t, 1) + "/" + Strings.fixed(c, 1),
                                () -> liquid.barColor != null ? liquid.barColor : liquid.color, () -> t / c));
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
            for (CombinedForceProjectorBuild member : group()) {
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

        // ===== 序列化 =====
        @Override
        public byte version() {
            return 3;
        }

        @Override
        public void write(Writes write) {
            CombinedForceProjectorBuild trueLeader = this;
            if (comboGroup != null && comboGroup.size > 0) {
                for (CombinedForceProjectorBuild b : comboGroup)
                    if (b != null && b.isValid() && b.pos() < trueLeader.pos())
                        trueLeader = b;
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
            write.f(getBuildup());
            write.bool(getBroken());
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
            if (revision >= 3) {
                setBuildup(read.f());
                setBroken(read.bool());
            }
            comboTotalLiquidCap = ((CombinedForceProjector) block).baseLiquidCapacity;
            comboTotalItemCap = block.itemCapacity;
        }
    }
}
