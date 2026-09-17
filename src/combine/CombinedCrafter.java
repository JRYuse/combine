package combine;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.IntSet;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Strings;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import combine.CombinedGenerator.CombinedGeneratorBuild;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Liquid;
import mindustry.type.LiquidStack;
import mindustry.ui.Bar;
import mindustry.ui.ReqImage;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.heat.HeatBlock;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItems;
import mindustry.world.consumers.ConsumeLiquid;
import mindustry.world.consumers.ConsumeLiquids;
import mindustry.world.consumers.ConsumePower;
import mindustry.world.meta.Attribute;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.world.meta.StatValues;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;
import mindustry.world.modules.PowerModule;

import static mindustry.Vars.*;
import static mindustry.Vars.iconMed;

/**
 * 组合工厂 v13 —— 三模式统一版
 * 模式：
 * 1. generic : 普通工厂（原版 GenericCrafter 行为）
 * 2. attribute : 地形加成工厂（AttributeCrafter 行为）
 * 3. separator : 分离机（Separator 行为）
 * 4. heatcrafter : 需热工厂（只从【直接相邻】的热源取热，不 BFS、不隔空传热）
 * 5. heatproducer : 产热工厂（对外暴露平滑热量输出 producerHeat）
 * 相邻放置自动形成组合体、共享 items/liquids/power 的核心逻辑不变。
 * 热量显示（统计/血条/面板）仅 heatcrafter / heatproducer 两种模式可见。
 * CombinedCrafterBuild 现 implements HeatBlock：仅 heatproducer 模式通过
 * heat() 对外暴露 producerHeat（原版 afflict 等的热量扫描只认 HeatBlock）；
 * 其余模式 heat() 恒为 0，不进原版导体网络；内部池语义走 availableHeat()，
 * 两者分离，杜绝 crafter 之间互调 heat() 的递归。
 */
public class CombinedCrafter extends GenericCrafter {

    public enum Mode {
        generic,
        attribute,
        separator,
        heatcrafter,
        heatproducer
    }

    /** 当前工厂模式 */
    public Mode mode = Mode.generic;

    // ==================== Attribute 模式字段 ====================
    public Attribute attribute = Attribute.heat;
    public float baseEfficiency = 1f;
    public float boostScale = 1f;
    public float maxBoost = 1f;
    public float minEfficiency = -1f;
    public float displayEfficiencyScale = 1f;
    public boolean displayEfficiency = true;
    public boolean scaleLiquidConsumption = false;

    // ==================== Separator 模式字段 ====================
    public @Nullable ItemStack[] results;
    public float separatorWarmupSpeed = 0.02f;
    /** 缓存 ConsumeItems，供 separator 的 shouldConsume 使用 */
    protected @Nullable ConsumeItems consItems;

    // ==================== HeatCrafter / HeatProducer 模式字段 ====================
    public float heatRequirement = 10f;
    public float maxEfficiency = 4f;
    public float overheatScale = 0.5f;
    public boolean overheat = false;
    public boolean drawTop = true;

    // ==================== HeatProducer 模式字段 ====================
    public float heatOutput = 0f;

    // ==================== 原有字段 ====================
    public float displayLiquid;
    public boolean allowCrossTypeCombo = true;
    public float itemCapacityMultiplier = 1f;
    public float liquidCapacityMultiplier = 1f;
    public float safetyBuffer = 0.15f;

    // ==================== 组合热量系统 ====================
    public float heatCapacity = 100f;
    public float flowSmoothing = 0.92f;
    public float safetyBufferSeconds = 3f;
    public float baseLiquidCapacity = 10f;

    public Seq<Item> cachedItems = new Seq<>();
    public Seq<Liquid> cachedLiquids = new Seq<>();

    /**
     * 组合建筑的搬运节奏（单位：tick）。
     *
     * 原版 {@code Block.dumpTime = 5}（每 5 tick 搬 1 个），单点最多 12 个/秒。
     * 但接收方自己的插入窗口比这更细：管道(duct) 15/s、钛传送带 12/s、塑料传送带更快，
     * 它们的窗口只有 4~5 tick，而 5 tick 的搬运粒度和窗口一旦相位错开就会整轮错过——
     * 实测组合体(16 台压机)外送：普通传送带 4.2/s(满)、钛传送带只有 9.3/s、管道只有 13.0/s，
     * 表现出来就是「输出速度慢、塞不满传送带」。
     *
     * 组合体内部本来就共用一个池子，所以把搬运改成每 tick 尝试一次：
     * 实际能搬多快由接收方（传送带/管道/容器）自己的接收上限决定，不再被 5 tick 的粒度拖住。
     */
    public static float comboDumpInterval = 1f;

    public CombinedCrafter(String name) {
        super(name);
        conductivePower = true;
        update = true;
        solid = true;
        hasItems = true;
        hasLiquids = true;
        sync = true;
    }

    @Override
    public void init() {
            try {
                super.init();
            } catch (Throwable t) {
                Log.err("[组合工厂] super.init() 异常: @", t.toString());
                t.printStackTrace();
            }
            try {
        super.init();
        baseLiquidCapacity = liquidCapacity;
        displayLiquid = liquidCapacity;
        liquidCapacity = 9999f;
        if (!hasLiquids)
            displayLiquid = 0;
        hasLiquids = true;
        conductivePower = true;
        hasItems = true;
        // 环境音已禁用（shouldAmbientSound 返回 false）

        // Separator 模式初始化
        if (mode == Mode.separator) {
            consItems = findConsumer(c -> c instanceof ConsumeItems);
            if (results == null)
                results = new ItemStack[0];
        }

        // HeatCrafter / HeatProducer 模式初始化
        if (mode == Mode.heatcrafter || mode == Mode.heatproducer) {
            hasItems = true;
            hasLiquids = true;
            if (mode == Mode.heatproducer) {
                heatRequirement = 0f; // 防止默认值泄漏到 stats
            }
        }

        // 非产热/需热模式：清空热量字段，既不显示也不参与热量逻辑
        if (mode != Mode.heatcrafter && mode != Mode.heatproducer) {
            heatRequirement = 0f;
            heatOutput = 0f;
        }

        // 缓存涉及物品（输入 + 输出 / results）
        cachedItems.clear();
        if (consumers != null) {
            for (Consume cons : consumers) {
                if (cons instanceof ConsumeItems ci) {
                    for (ItemStack stack : ci.items) {
                        if (!cachedItems.contains(stack.item))
                            cachedItems.add(stack.item);
                    }
                }
            }
        }
        if (mode == Mode.separator) {
            if (results != null) {
                for (ItemStack stack : results) {
                    if (!cachedItems.contains(stack.item))
                        cachedItems.add(stack.item);
                }
            }
        } else {
            if (outputItems != null) {
                for (ItemStack stack : outputItems) {
                    if (!cachedItems.contains(stack.item))
                        cachedItems.add(stack.item);
                }
            }
        }

        // 缓存涉及液体
        cachedLiquids.clear();
        if (consumers != null) {
            for (Consume cons : consumers) {
                if (cons instanceof ConsumeLiquid cl) {
                    if (!cachedLiquids.contains(cl.liquid))
                        cachedLiquids.add(cl.liquid);
                } else if (cons instanceof ConsumeLiquids cls) {
                    for (LiquidStack stack : cls.liquids) {
                        if (!cachedLiquids.contains(stack.liquid))
                            cachedLiquids.add(stack.liquid);
                    }
                }
            }
        }
        if (outputLiquids != null) {
            for (LiquidStack stack : outputLiquids) {
                if (!cachedLiquids.contains(stack.liquid))
                    cachedLiquids.add(stack.liquid);
            }
        }
        // FIX[液条]: 原版部分机器只设单数 outputLiquid(桥接在 GenericCrafter.init 里,
        // 若填充时机早于桥接或实例未走完整管线会漏), 这里兜底并入缓存
        if (outputLiquid != null && !cachedLiquids.contains(outputLiquid.liquid))
            cachedLiquids.add(outputLiquid.liquid);

        // 禁用环境音循环：避免触发 SoundControl 环境音线程在 Android 上的
        // IllegalThreadStateException（Thread.start 崩溃）
        ambientSound = mindustry.gen.Sounds.none;
        ambientSoundVolume = 0f;
    
            } catch (Throwable t) {
                Log.err("[组合工厂] init 主体异常(已尽力完成初始化): @", t.toString());
                t.printStackTrace();
            }
        }

    @Override
    public TextureRegion[] icons() {
        return drawer.finalIcons(this);
    }

    @Override
    public void setStats() {
        // 只有产热/需热模式才显示热量统计
        if (mode == Mode.heatproducer && heatOutput > 0)
            stats.add(Stat.output, heatOutput, StatUnit.heatUnits);
        if (mode == Mode.heatcrafter && heatRequirement > 0)
            stats.add(Stat.input, heatRequirement, StatUnit.heatUnits);

        if (mode == Mode.separator) {
            stats.timePeriod = craftTime;
            stats.add(Stat.productionTime, craftTime / 60f, StatUnit.seconds);
            if (consumers != null) {
                for (var cons : consumers) {
                    cons.display(stats);
                }
            }
            if (results != null && results.length > 0) {
                int[] sum = { 0 };
                for (var r : results)
                    sum[0] += r.amount;
                stats.add(Stat.output, table -> {
                    for (ItemStack stack : results) {
                        table.add(StatValues.displayItemPercent(stack.item,
                                (int) ((float) stack.amount / sum[0] * 100), true)).padRight(5);
                    }
                });
            }
        } else {
            super.setStats();
            if (mode == Mode.attribute) {
                stats.add(baseEfficiency <= 0.0001f ? Stat.tiles : Stat.affinities,
                        attribute, floating, boostScale * size * size, !displayEfficiency);
            }
        }
        stats.remove(Stat.liquidCapacity);
        stats.add(Stat.liquidCapacity, displayLiquid, StatUnit.liquidUnits);
    }

    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid) {
        super.drawPlace(x, y, rotation, valid);
        if (mode == Mode.attribute && displayEfficiency) {
            drawPlaceText(Core.bundle.format("bar.efficiency",
                    (int) ((baseEfficiency + Math.min(maxBoost, boostScale * sumAttribute(attribute, x, y))) * 100f)),
                    x, y, valid);
        }
        if (mode == Mode.heatcrafter) {
            // 放置时没有建筑可读，显示满热时的理论效率
            drawPlaceText(Core.bundle.format("bar.efficiency", (int) (maxEfficiency * 100f)), x, y, valid);
        }
    }

    @Override
    public boolean canPlaceOn(Tile tile, Team team, int rotation) {
        if (mode == Mode.attribute) {
            return baseEfficiency + tile.getLinkedTilesAs(this, tempTiles)
                    .sumf(other -> other.floor().attributes.get(attribute)) >= minEfficiency;
        }
        return super.canPlaceOn(tile, team, rotation);
    }

    @Override
    public void setBars() {
        super.setBars();
        // 只有这两种模式显示热量条
        if (mode == Mode.heatcrafter || mode == Mode.heatproducer) {
            addBar("heat", (CombinedCrafterBuild e) -> new Bar("bar.heat", Pal.lightOrange, e::heatFrac));
        }
        if (mode == Mode.heatcrafter) {
            addBar("efficiency", (CombinedCrafterBuild e) -> new Bar(
                    () -> Core.bundle.formatFloat("bar.efficiency", e.heatEfficiency() * 100 * maxEfficiency, 1),
                    () -> Pal.lightOrange,
                    () -> e.heatEfficiency() / maxEfficiency));
        }
        if (mode == Mode.attribute && displayEfficiency) {
            addBar("efficiency", (CombinedCrafterBuild entity) -> new Bar(
                    () -> Core.bundle.format("bar.efficiency",
                            (int) (entity.efficiencyMultiplier() * 100 * displayEfficiencyScale)),
                    () -> Pal.lightOrange,
                    entity::efficiencyMultiplier));
        }
    }

    // ==================== Building ====================

    public class CombinedCrafterBuild extends GenericCrafterBuild implements HeatBlock {
        public CombinedCrafterBuild comboLeader;
        public Seq<CombinedCrafterBuild> comboGroup = new Seq<>();
        public boolean comboDirty = true;
        public float comboTotalLiquidCap = 0f;
        public int comboTotalItemCap = 0;

        // ---- 组合热量共享 ----
        public float comboHeat = 0f;
        public float comboHeatCap = 0f;
        // heatcrafter 组共享的"可得热量"池：按需计算，结果缓存在 leader 上（每 tick 一次）
        // 热量在需热成员之间【均分】：单成员可读热量 = 池值 / 需热成员数
        public float comboPooledHeat = 0f;
        public int comboPooledConsumers = 1;
        public float lastPooledHeatTime = -1f;
        // HeatProducer 平滑输出值（对外暴露，非池子存量）
        public float producerHeat = 0f;

        // Attribute 模式数据
        public float attrsum;
        // Separator 模式数据
        public int seed;

        // FIX: 读取时暂存 leader 位置，等 updateTile 时恢复共享模块
        public int pendingLeaderPos = -1;

        /**
         * 组级聚合缓存（只有 leader 持有，每 tick 只算一次，组重建时失效）。
         *
         * 原来"这个物品/液体组里有没有人要""组内产率/耗率是多少"这类问题，
         * 每一处调用都现遍历一遍整组；而这些调用点在 acceptItem/acceptLiquid/
         * dumpOutputs 里，都是每 tick（每个成员、每个搬运请求、每种产出）都会跑的，
         * 于是整组 N 台就是 O(N²)——上百台的组合体一放下去就疯狂掉帧。
         * 现在按 tick 缓存一次聚合结果，查询 O(1)。
         */
        public boolean[] comboConsumedItems, comboConsumedLiquids;
        public float[] comboItemProduceRate, comboItemConsumeRate, comboLiquidProduceRate, comboLiquidConsumeRate;
        public int[] comboItemNeedPerCraft;
        public float[] comboLiquidNeedPerCraft;
        public long comboAggregateTick = Long.MIN_VALUE;
        /** heat() 的组内产热总和缓存（每 tick 一次，否则邻居读取是 O(N²)） */
        public long comboProducerHeatTick = Long.MIN_VALUE;
        public float comboProducerHeatSum = 0f;

        public float getComboTotalLiquidAmount() {
            return liquids != null ? liquids.currentAmount() : 0f;
        }

        public CombinedCrafterBuild leader() {
            if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null)) {
                comboLeader = null;
                    comboDirty = true; // FIX: 失联后允许重建组合
            }
            return comboLeader == null ? this : comboLeader;
        }

        // 热量访问
        public float getComboHeat() {
            return isLeader() ? comboHeat : leader().comboHeat;
        }

        public void setComboHeat(float v) {
            if (isLeader())
                comboHeat = v;
            else
                leader().comboHeat = v;
        }

        public float getComboHeatCap() {
            return isLeader() ? comboHeatCap : leader().comboHeatCap;
        }

        public void setComboHeatCap(float v) {
            if (isLeader())
                comboHeatCap = v;
            else
                leader().comboHeatCap = v;
        }

        /**
         * 热量读取（不再是 HeatBlock 接口方法，仅供本类内部使用）。
         * heatcrafter：只统计【直接相邻】的热源，不 BFS、不跳板、不穿墙、
         * 不借道其它工厂 —— 热量绝不隔空传播。
         * 绝不调用其它组合工厂的 heat()（防互相递归 → StackOverflowError）。
         */
        public float availableHeat() {
            CombinedCrafter cb = (CombinedCrafter) block;
            // HeatProducer 模式：返回平滑后的瞬时热量输出值
            if (cb.mode == Mode.heatproducer) {
                return producerHeat;
            }
            // HeatCrafter 模式：整组共享"可得热量"池。
            // 池 = 本组 heatproducer 成员产量之和 + 每个成员【直接相邻】的外部热源
            // （发电机簇共享池 / 外部 heatproducer 组的整组产量 / 旧式产热 / 原版热源），
            // 按 leader 去重。热量只从相邻热源进入组合体，绝不隔空传播；
            // 全程只取字段，绝不调用其它工厂的 heat()（无递归）。
            if (cb.mode == Mode.heatcrafter) {
                CombinedCrafterBuild l = leader();
                if (l.lastPooledHeatTime != Time.time) {
                    l.lastPooledHeatTime = Time.time;
                    float sum = 0f;
                    int consumers = 0;
                    ObjectSet<Object> counted = new ObjectSet<>();
                    for (CombinedCrafterBuild member : l.group()) {
                        if (!member.isValid())
                            continue;
                        CombinedCrafter mb = (CombinedCrafter) member.block;
                        if (mb.mode == Mode.heatcrafter)
                            consumers++;
                        // 本组内的产热成员：产量直接并入（组合 = 产量合并）
                        if (mb.mode == Mode.heatproducer) {
                            sum += member.producerHeat;
                        } else if (mb.heatOutput > 0) {
                            sum += member.getComboHeat(); // 兼容旧式 heatOutput 配置
                        }
                        // 每个成员各自的外部热源
                        for (Building b : member.proximity) {
                            if (b == null || !b.isValid() || b.team != team)
                                continue;
                            if (b instanceof CombinedGeneratorBuild gb) {
                                // 发电机簇：贴到簇内任意一台即读取整簇共享池
                                if (counted.add(gb.leader()))
                                    sum += gb.getComboHeat();
                            } else if (b instanceof CombinedCrafterBuild other && other.leader() != l) {
                                CombinedCrafter ob = (CombinedCrafter) other.block;
                                if (ob.mode == Mode.heatproducer) {
                                    // 外部 heatproducer 组：整组产量合并
                                    if (counted.add(other.leader()))
                                        for (CombinedCrafterBuild p : other.group())
                                            if (p.isValid())
                                                sum += p.producerHeat;
                                } else if (ob.heatOutput > 0) {
                                    if (counted.add(other.leader()))
                                        sum += other.getComboHeat();
                                }
                            } else if (b instanceof mindustry.world.blocks.heat.HeatBlock hb
                                    && !(b instanceof CombinedCrafterBuild)) {
                                // 原版热源（电热器、热路由器等）；
                                // 组合工厂由上方分支专门处理，避免同组产热成员经 HeatBlock 接口重复计入
                                sum += hb.heat();
                            }
                        }
                    }
                    sum += ComboNet.heatFor(l);
                    l.comboPooledHeat = sum;
                    l.comboPooledConsumers = Math.max(consumers, 1);
                }
                return l.comboPooledHeat / l.comboPooledConsumers;
            }

            // 其他模式：保留原有逻辑，但绝不调用其它组合工厂的 heat()（防互相递归）
            float self = getComboHeat();
            if (cb.heatOutput > 0)
                return self;
            float max = self;
            for (Building b : proximity) {
                if (b instanceof mindustry.world.blocks.heat.HeatBlock hb && b != this
                        && !(b instanceof CombinedCrafterBuild)) {
                    max = Math.max(max, hb.heat());
                }
            }
            return max;
        }

        /**
         * HeatBlock 接口实现：让原版/组合的热量消费方（ afflict 等电力炮塔的
         * calculateHeat、原版热熔炉等）能把组合产热器识别为热源。
         * 只有 heatproducer 模式对外供热，其余模式恒返回 0 —— 不进入原版
         * 导体网络、不被误读、也不会与 availableHeat() 的内部池语义混淆。
         * 无递归：本方法只读 producerHeat 字段，绝不调用其它建筑的 heat()。
         */
        /**
         * 对外暴露的产量 = 整组 heatproducer 成员的 producerHeat 之和
         * （与 CombinedGenerator 的"簇共享池"语义一致：贴到组内任意一台即读整组总产量）。
         * 其余模式恒为 0。只读字段、无递归。
         */
        @Override
        public float heat() {
            CombinedCrafter cb = (CombinedCrafter) block;
            if (cb.mode != Mode.heatproducer)
                return 0f;
            // 邻居每 tick 都会来读 heat()，原先每次都遍历整组 → O(N²)；按 tick 缓存一次
            CombinedCrafterBuild l = leader();
            if (l.comboProducerHeatTick != state.updateId) {
                l.comboProducerHeatTick = state.updateId;
                float sum = 0f;
                for (CombinedCrafterBuild m : l.group()) {
                    if (m.isValid() && ((CombinedCrafter) m.block).mode == Mode.heatproducer)
                        sum += m.producerHeat;
                }
                l.comboProducerHeatSum = sum;
            }
            return l.comboProducerHeatSum;
        }

        public float heatFrac() {
            CombinedCrafter cb = (CombinedCrafter) block;
            if (cb.mode == Mode.heatcrafter)
                return availableHeat() / Math.max(cb.heatRequirement * cb.maxEfficiency, 1f);
            if (cb.mode == Mode.heatproducer)
                return heat() / Math.max(cb.heatOutput, 0.001f);
            return availableHeat() / Math.max(getComboHeatCap(), 1f);
        }

        /** HeatCrafter 热量效率 */
        public float heatEfficiency() {
            CombinedCrafter cb = (CombinedCrafter) block;
            if (cb.mode != Mode.heatcrafter)
                return 1f;
            // FIX: heatRequirement <= 0 时 0/0=NaN，导致 "active 但不生产"
            if (cb.heatRequirement <= 0.0001f)
                return cb.maxEfficiency;
            return Math.min(availableHeat() / cb.heatRequirement, cb.maxEfficiency);
        }

        public boolean isLeader() {
            return leader() == this;
        }

        public Seq<CombinedCrafterBuild> group() {
            CombinedCrafterBuild l = leader();
            if (l.comboGroup == null)
                l.comboGroup = new Seq<>();
            return l.comboGroup;
        }

        // -------------------- 组合重建 --------------------
        public void rebuildCombo() {
            Seq<CombinedCrafterBuild> oldGroup = comboGroup != null ? new Seq<>(comboGroup) : new Seq<>();

            comboGroup = new Seq<>();
            comboGroup.add(this);

            IntSet visited = new IntSet();
            Queue<CombinedCrafterBuild> queue = new Queue<>();
            queue.add(this);
            visited.add(pos());

            while (!queue.isEmpty()) {
                CombinedCrafterBuild current = queue.removeFirst();
                for (Building b : current.proximity) {
                    if (b instanceof CombinedCrafterBuild other && other.team == team && other.isValid()) {
                        if (!visited.contains(other.pos())) {
                            CombinedCrafter cb = (CombinedCrafter) current.block;
                            CombinedCrafter ob = (CombinedCrafter) other.block;
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
            }

            CombinedCrafterBuild newLeader = this;
            for (CombinedCrafterBuild b : comboGroup) {
                if (b.isValid() && b.pos() < newLeader.pos())
                    newLeader = b;
            }

            Seq<CombinedCrafterBuild> newGroup = new Seq<>(comboGroup);
            newLeader.comboGroup = newGroup;

            // 热量继承：重建【前】收拢所有旧 leader（本组的 + 被合并进来的
            // 其它组的）的热量。新块 pos 更小时会抢走 leader，只看 oldGroup
            // 会漏掉被并进来的旧 leader 的池子；且此扫描必须在隶属重赋值之前。
            float carriedHeat = 0f;
            for (CombinedCrafterBuild b : comboGroup) {
                if (b.isValid() && b.comboLeader == null) {
                    carriedHeat += b.comboHeat;
                    b.comboHeat = 0f;
                }
            }

            for (CombinedCrafterBuild b : newGroup) {
                if (b.isValid()) {
                    b.comboLeader = newLeader;
                    b.comboGroup = newGroup;
                    b.comboDirty = false;
                }
            }
            newLeader.comboLeader = null;
            newLeader.comboHeat = carriedHeat;

            float totalLiqCap = 0f;
            int totalItemCap = 0;
            for (CombinedCrafterBuild b : newGroup) {
                if (b.isValid()) {
                    totalLiqCap += ((CombinedCrafter) b.block).baseLiquidCapacity;
                    totalItemCap += b.block.itemCapacity;
                }
            }
            for (CombinedCrafterBuild b : newGroup) {
                if (b.isValid()) {
                    b.comboTotalLiquidCap = totalLiqCap;
                    b.comboTotalItemCap = totalItemCap;
                    // Attribute 模式：重建时重新计算 attrsum
                    CombinedCrafter bBlock = (CombinedCrafter) b.block;
                    if (bBlock.mode == Mode.attribute) {
                        b.attrsum = sumAttribute(bBlock.attribute, b.tile.x, b.tile.y);
                    }
                }
            }

            // 组合热量容量汇总
            float totalHeatCap = 0f;
            for (CombinedCrafterBuild b : newGroup) {
                if (b.isValid()) {
                    totalHeatCap += ((CombinedCrafter) b.block).heatCapacity;
                }
            }
            for (CombinedCrafterBuild b : newGroup) {
                if (b.isValid()) {
                    b.comboHeatCap = totalHeatCap;
                }
            }

            if (oldGroup.size > newGroup.size) {
                splitAssets(oldGroup, newGroup);
            }

            // 组结构变了：聚合缓存必须失效（否则本 tick 内还会用旧组成员的产/耗数据）
            newLeader.invalidateComboAggregate();
            for (CombinedCrafterBuild b : oldGroup) {
                if (b.comboAggregateTick != Long.MIN_VALUE)
                    b.invalidateComboAggregate();
            }

            shareModules(newLeader);

            for (CombinedCrafterBuild oldMember : oldGroup) {
                if (oldMember != this && oldMember.isValid() && !newGroup.contains(oldMember)) {
                    oldMember.comboLeader = null;
                    oldMember.comboGroup = new Seq<>();
                    oldMember.comboDirty = true;
                    oldMember.comboTotalLiquidCap = 0f;
                    oldMember.comboTotalItemCap = 0;
                }
            }

        }

        public void collectInvolvedTypes(Seq<CombinedCrafterBuild> group, Seq<Item> outItems, Seq<Liquid> outLiquids) {
            outItems.clear();
            outLiquids.clear();
            for (CombinedCrafterBuild b : group) {
                if (!b.isValid())
                    continue;
                CombinedCrafter cb = (CombinedCrafter) b.block;
                for (Item item : cb.cachedItems) {
                    if (!outItems.contains(item))
                        outItems.add(item);
                }
                for (Liquid liquid : cb.cachedLiquids) {
                    if (!outLiquids.contains(liquid))
                        outLiquids.add(liquid);
                }
            }
        }

        public void splitAssets(Seq<CombinedCrafterBuild> oldGroup, Seq<CombinedCrafterBuild> newGroup) {
            CombinedCrafterBuild oldLeader = null;
            for (CombinedCrafterBuild b : oldGroup) {
                if (!b.isValid())
                    continue;
                for (CombinedCrafterBuild other : oldGroup) {
                    if (other != b && other.isValid() && (other.items == b.items || other.liquids == b.liquids)) {
                        oldLeader = b;
                        break;
                    }
                }
                if (oldLeader != null)
                    break;
            }
            if (oldLeader == null) {
                for (CombinedCrafterBuild b : oldGroup) {
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

            Seq<CombinedCrafterBuild> kicked = new Seq<>();
            for (CombinedCrafterBuild b : oldGroup) {
                if (b.isValid() && !newGroup.contains(b))
                    kicked.add(b);
            }

            // ---- 组合热量分配 ----
            float oldHeat = oldLeader.comboHeat;
            float oldTotalHeatCap = 0f;
            for (CombinedCrafterBuild b : oldGroup) {
                if (b.isValid()) {
                    oldTotalHeatCap += ((CombinedCrafter) b.block).heatCapacity;
                }
            }
            float newGroupHeatCap = 0f;
            for (CombinedCrafterBuild b : newGroup) {
                if (b.isValid()) {
                    newGroupHeatCap += ((CombinedCrafter) b.block).heatCapacity;
                }
            }
            float kickedTotalHeatCap = 0f;
            float[] kickedHeatCaps = new float[kicked.size];
            for (int i = 0; i < kicked.size; i++) {
                CombinedCrafterBuild b = kicked.get(i);
                if (b.isValid()) {
                    kickedHeatCaps[i] = ((CombinedCrafter) b.block).heatCapacity;
                    kickedTotalHeatCap += kickedHeatCaps[i];
                }
            }

            if (oldTotalHeatCap > 0.001f && oldHeat > 0.001f) {
                // 新组保留热量
                if (oldLeader.isValid() && newGroup.contains(oldLeader)) {
                    oldLeader.comboHeat = oldHeat * newGroupHeatCap / oldTotalHeatCap;
                } else if (newGroup.size > 0) {
                    CombinedCrafterBuild newGroupLeader = newGroup.first();
                    for (CombinedCrafterBuild b : newGroup) {
                        if (b.isValid() && b.pos() < newGroupLeader.pos())
                            newGroupLeader = b;
                    }
                    newGroupLeader.comboHeat = oldHeat * newGroupHeatCap / oldTotalHeatCap;
                    newGroupLeader.comboHeatCap = newGroupHeatCap;
                }

                // 被踢出的成员按比例分配热量
                float remainingHeat = oldHeat;
                for (int i = 0; i < kicked.size; i++) {
                    CombinedCrafterBuild b = kicked.get(i);
                    if (!b.isValid())
                        continue;
                    float ideal = (i == kicked.size - 1) ? remainingHeat
                            : oldHeat * kickedHeatCaps[i] / oldTotalHeatCap;
                    ideal = Math.min(ideal, remainingHeat);
                    b.comboHeat = ideal;
                    b.comboHeatCap = kickedHeatCaps[i];
                    remainingHeat -= ideal;
                }
            }

            int oldTotalItemCap = 0;
            float oldTotalLiquidCap = 0f;
            for (CombinedCrafterBuild b : oldGroup) {
                if (b.isValid()) {
                    oldTotalItemCap += b.block.itemCapacity;
                    oldTotalLiquidCap += ((CombinedCrafter) b.block).baseLiquidCapacity;
                }
            }

            int newGroupItemCap = 0;
            float newGroupLiquidCap = 0f;
            for (CombinedCrafterBuild b : newGroup) {
                if (b.isValid()) {
                    newGroupItemCap += b.block.itemCapacity;
                    newGroupLiquidCap += ((CombinedCrafter) b.block).baseLiquidCapacity;
                }
            }

            int[] itemCaps = new int[kicked.size];
            float[] liquidCaps = new float[kicked.size];
            int kickedTotalItemCap = 0;
            float kickedTotalLiquidCap = 0f;
            for (int i = 0; i < kicked.size; i++) {
                CombinedCrafterBuild b = kicked.get(i);
                itemCaps[i] = b.block.itemCapacity;
                liquidCaps[i] = ((CombinedCrafter) b.block).baseLiquidCapacity;
                kickedTotalItemCap += itemCaps[i];
                kickedTotalLiquidCap += liquidCaps[i];
            }

            ItemModule[] newItemMods = new ItemModule[kicked.size];
            LiquidModule[] newLiquidMods = new LiquidModule[kicked.size];
            for (int i = 0; i < kicked.size; i++) {
                newItemMods[i] = new ItemModule();
                newLiquidMods[i] = new LiquidModule();
            }

            Seq<Item> involvedItems = new Seq<>();
            Seq<Liquid> involvedLiquids = new Seq<>();
            collectInvolvedTypes(oldGroup, involvedItems, involvedLiquids);

            if (oldItems != null && oldTotalItemCap > 0 && kickedTotalItemCap > 0) {
                int[] kickedAllocated = new int[kicked.size];
                for (Item item : involvedItems) {
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
                for (Liquid liquid : involvedLiquids) {
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

            if (oldItems != null) {
                for (CombinedCrafterBuild b : newGroup) {
                    if (b.isValid())
                        b.items = oldItems;
                }
            }
            if (oldLiquids != null) {
                for (CombinedCrafterBuild b : newGroup) {
                    if (b.isValid())
                        b.liquids = oldLiquids;
                }
            }

            for (int i = 0; i < kicked.size; i++) {
                CombinedCrafterBuild b = kicked.get(i);
                b.items = newItemMods[i];
                b.liquids = newLiquidMods[i];
                // FIX[电力共享]: 离组成员重建独立电力模块并保留原连接
                if (b.power != null) {
                    PowerModule oldPower = b.power;
                    b.power = new PowerModule();
                    if (oldPower != null) {
                        b.power.links.addAll(oldPower.links);
                        b.power.status = oldPower.status;
                    }
                    b.updatePowerGraph();
                }
            }
        }

        public void shareModules(CombinedCrafterBuild leader) {
            int totalItemCap = leader.comboTotalItemCap;
            float totalLiquidCap = leader.comboTotalLiquidCap;

            if (leader.items == null) {
                for (CombinedCrafterBuild member : group()) {
                    if (member.items != null) {
                        leader.items = member.items;
                        break;
                    }
                }
            }
            if (leader.liquids == null) {
                for (CombinedCrafterBuild member : group()) {
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
                for (CombinedCrafterBuild member : group()) {
                    if (member != leader && member.isValid() && member.items != null
                            && !processedItems.contains(member.items)) {
                        processedItems.add(member.items);
                        for (Item item : content.items()) {
                            int amt = member.items.get(item);
                            if (amt > 0) {
                                int currentTotal = leader.items.total();
                                int canAccept = Math.max(0, totalItemCap - currentTotal);
                                int transfer = amt; // 全额并入：总量必然 ≤ 合并后容量，截断只会丢物品
                                if (transfer > 0)
                                    leader.items.add(item, transfer);
                            }
                        }
                    }
                }
                for (CombinedCrafterBuild member : group()) {
                    if (member.isValid())
                        member.items = leader.items;
                }
            }

            if (leader.liquids != null) {
                processedLiquids.add(leader.liquids);
                for (CombinedCrafterBuild member : group()) {
                    if (member != leader && member.isValid() && member.liquids != null
                            && !processedLiquids.contains(member.liquids)) {
                        processedLiquids.add(member.liquids);
                        for (Liquid liquid : content.liquids()) {
                            float amt = member.liquids.get(liquid);
                            if (amt > 0.001f) {
                                float canAccept = Math.max(0f, totalLiquidCap - leader.liquids.get(liquid));
                                float transfer = amt; // 全额并入：总量必然 ≤ 合并后容量，截断只会丢物品
                                if (transfer > 0.001f)
                                    leader.liquids.add(liquid, transfer);
                            }
                        }
                    }
                }
                for (CombinedCrafterBuild member : group()) {
                    if (member.isValid())
                        member.liquids = leader.liquids;
                }
            }

            // FIX[电力共享]: 全组共用领导者的电力模块——任一成员接电即整组通电。
            // links 存在 PowerModule 里: 先摘旧电网, 把成员自己的节点链接并入领导者模块, 再换模块
            if (leader.power != null) {
                for (CombinedCrafterBuild member : group()) {
                    if (member != leader && member.isValid() && member.power != null
                            && member.power != leader.power) {
                        PowerModule oldPower = member.power;
                        if (oldPower.graph != null)
                            oldPower.graph.remove(member);
                        for (int li = 0; li < oldPower.links.size; li++) {
                            int link = oldPower.links.get(li);
                            if (!leader.power.links.contains(link))
                                leader.power.links.add(link);
                        }
                        member.power = leader.power;
                        member.updatePowerGraph();
                    }
                }
            }

        }

        // -------------------- 生命周期 --------------------
        @Override
        public void created() {
            super.created();
            comboDirty = true;
            CombinedCrafter cb = (CombinedCrafter) block;
            if (cb.mode == Mode.separator) {
                seed = Mathf.randomSeed(tile.pos(), 0, Integer.MAX_VALUE - 1);
            }
        }

        @Override
        public void onProximityUpdate() {
            super.onProximityUpdate();
            comboDirty = true;
            for (CombinedCrafterBuild member : group()) {
                if (member.isValid())
                    member.comboDirty = true;
            }
            CombinedCrafter cb = (CombinedCrafter) block;
            if (cb.mode == Mode.attribute) {
                attrsum = sumAttribute(cb.attribute, tile.x, tile.y);
            }
        }

        @Override
        public void onRemoved() {
            Seq<CombinedCrafterBuild> members = new Seq<>(group());
            boolean wasLeader = isLeader();
            ItemModule oldItems = this.items;
            LiquidModule oldLiquids = this.liquids;

            if (!wasLeader) {
                if (items != null) {
                    boolean shared = false;
                    for (CombinedCrafterBuild member : members) {
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
                    for (CombinedCrafterBuild member : members) {
                        if (member != this && member.isValid() && member.liquids == this.liquids) {
                            shared = true;
                            break;
                        }
                    }
                    if (shared)
                        liquids = new LiquidModule();
                }
                if (power != null) {
                    boolean sharedPower = false;
                    for (CombinedCrafterBuild member : members) {
                        if (member != this && member.isValid() && member.power == this.power) {
                            sharedPower = true;
                            break;
                        }
                    }
                    if (sharedPower) {
                        // FIX[电力共享]: 换独立模块但保留电力连接(links 存在模块里, 直接 new 会丢光)
                        PowerModule oldPower = power;
                        power = new PowerModule();
                        if (oldPower != null) {
                            power.links.addAll(oldPower.links);
                            power.status = oldPower.status;
                        }
                        updatePowerGraph();
                    }
                }
            }

            if (wasLeader) {
                Seq<CombinedCrafterBuild> survivors = new Seq<>();
                for (CombinedCrafterBuild b : members) {
                    if (b != this && b.isValid())
                        survivors.add(b);
                }

                int[] itemCaps = new int[survivors.size];
                float[] liquidCaps = new float[survivors.size];
                int totalItemCap = 0;
                float totalLiquidCap = 0f;
                for (int i = 0; i < survivors.size; i++) {
                    CombinedCrafterBuild b = survivors.get(i);
                    itemCaps[i] = b.block.itemCapacity;
                    liquidCaps[i] = ((CombinedCrafter) b.block).baseLiquidCapacity;
                    totalItemCap += itemCaps[i];
                    totalLiquidCap += liquidCaps[i];
                }

                ItemModule[] itemMods = new ItemModule[survivors.size];
                LiquidModule[] liquidMods = new LiquidModule[survivors.size];
                for (int i = 0; i < survivors.size; i++) {
                    itemMods[i] = new ItemModule();
                    liquidMods[i] = new LiquidModule();
                }

                Seq<Item> involvedItems = new Seq<>();
                Seq<Liquid> involvedLiquids = new Seq<>();
                collectInvolvedTypes(members, involvedItems, involvedLiquids);

                if (oldItems != null && totalItemCap > 0) {
                    int[] allocated = new int[survivors.size];
                    for (Item item : involvedItems) {
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
                    for (Liquid liquid : involvedLiquids) {
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

                // 组合热量按 heatCapacity 比例分配给幸存者
                // （原实现完全没分配：leader 被拆/拆毁时整个热量池直接清零）
                float survHeatCap = 0f;
                for (int i = 0; i < survivors.size; i++)
                    survHeatCap += ((CombinedCrafter) survivors.get(i).block).heatCapacity;
                float remainingHeat = comboHeat;
                for (int i = 0; i < survivors.size; i++) {
                    CombinedCrafterBuild b = survivors.get(i);
                    float cap = ((CombinedCrafter) b.block).heatCapacity;
                    float ideal = (i == survivors.size - 1) ? remainingHeat
                            : comboHeat * cap / Math.max(survHeatCap, 0.001f);
                    ideal = Math.min(ideal, remainingHeat);
                    b.comboHeat = Math.max(ideal, 0f);
                    b.comboHeatCap = cap;
                    remainingHeat -= ideal;
                }

                for (int i = 0; i < survivors.size; i++) {
                    CombinedCrafterBuild b = survivors.get(i);
                    b.items = itemMods[i];
                    b.liquids = liquidMods[i];
                    b.comboLeader = null;
                    b.comboGroup = new Seq<>();
                    b.comboDirty = true;
                    b.comboTotalLiquidCap = 0f;
                    b.comboTotalItemCap = 0;
                }
            } else {
                CombinedCrafterBuild leader = leader();
                if (leader != null && leader.isValid() && leader != this) {
                    leader.comboDirty = true;
                }
            }

            comboLeader = null;
            comboGroup = new Seq<>();
            comboDirty = false;
            comboTotalLiquidCap = 0f;
            comboTotalItemCap = 0;
            comboHeat = 0f;
            comboHeatCap = 0f;
            super.onRemoved();
        }

        // -------------------- 生产效率 --------------------
        public float efficiencyMultiplier() {
            CombinedCrafter cb = (CombinedCrafter) block;
            if (cb.mode != Mode.attribute)
                return 1f;
            return cb.baseEfficiency + Math.min(cb.maxBoost, cb.boostScale * attrsum) + cb.attribute.env();
        }

        @Override
        public float efficiencyScale() {
            CombinedCrafter cb = (CombinedCrafter) block;
            if (cb.mode == Mode.attribute && cb.scaleLiquidConsumption) {
                return efficiencyMultiplier();
            }
            return super.efficiencyScale();
        }

        @Override
        public float getProgressIncrease(float baseTime) {
            CombinedCrafter cb = (CombinedCrafter) block;
            if (cb.mode == Mode.attribute) {
                return super.getProgressIncrease(baseTime) * efficiencyMultiplier();
            } else if (cb.mode == Mode.separator) {
                return super.getProgressIncrease(baseTime);
            }
            // Generic 模式：保留原有液体容量限制逻辑
            if (ignoreLiquidFullness)
                return super.getProgressIncrease(baseTime);
            float scaling = 1f, max = 1f;
            if (outputLiquids != null) {
                max = 0f;
                for (var s : outputLiquids) {
                    float value = (comboTotalLiquidCap - liquids.get(s.liquid)) / (s.amount * edelta());
                    scaling = Math.min(scaling, value);
                    max = Math.max(max, value);
                }
            }
            return super.getProgressIncrease(baseTime) * (dumpExtraLiquid ? Math.min(max, 1f) : scaling);
        }

        @Override
        public boolean shouldAmbientSound() {
            return false; // 禁用环境音，避免 Android 声音线程崩溃
        }

        @Override
        public mindustry.world.meta.BlockStatus status() {
            CombinedCrafter cb = (CombinedCrafter) block;
            if (cb.mode == Mode.heatcrafter) {
                if (availableHeat() <= 0.001f)
                    return mindustry.world.meta.BlockStatus.noInput;
                if (efficiency > 0)
                    return mindustry.world.meta.BlockStatus.active;
            }
            // FIX: 非 heatcrafter 模式带热量需求的方块，缺热且效率为 0 时如实显示 noInput。
            // 必须排除产物满的情况: 产物满时 shouldConsume=false 导致 efficiency=0,
            // 否则会把 noOutput 误判成 noInput。
            if (cb.mode != Mode.separator && cb.heatRequirement > 0 && shouldConsume()
                    && availableHeat() <= 0.001f && efficiency <= 0)
                return mindustry.world.meta.BlockStatus.noInput;
            return super.status();
        }

        @Override
        public boolean shouldConsume() {
            CombinedCrafter cb = (CombinedCrafter) block;
            if (cb.mode == Mode.separator) {
                int total = items.total();
                if (cb.consItems != null) {
                    for (ItemStack stack : cb.consItems.items) {
                        total -= items.get(stack.item);
                    }
                }
                // FIX[独立储存]: 每种产物独立判断——只要有一种产物未满就继续生产。
                // 不再统计"所有物品总量": 某一种产物(甚至混进来的非产物物品)满了
                // 不会让整个工厂停摆/误报 nooutput; 全部产物都满才停止并显示 nooutput。
                boolean sepDecision = false;
                if (cb.results != null) {
                    for (ItemStack stack : cb.results) {
                        if (items.get(stack.item) < getMaximumAccepted(stack.item)) {
                            sepDecision = enabled;
                            break;
                        }
                    }
                }
                return sepDecision;
            }

            // Generic / Attribute
            if (outputItems != null) {
                for (var output : outputItems) {
                    if (items.get(output.item) + output.amount > getMaximumAccepted(output.item)) {
                        return false;
                    }
                }
            }
            if (outputLiquids != null && !ignoreLiquidFullness) {
                boolean allFull = true;
                for (var output : outputLiquids) {
                    // FIX[进水即停摆]: 这里必须只看"这一种产出液体"在池里的量，而不是全池总量。
                    // 组合体的池子是共享的，输入液体（例如低温液混合机的水）很容易把池子灌满；
                    // 原先用全池总量判断"产出满了" → efficiency 被强制为 0 → 连输入也不再消耗
                    // → 池子永远是满的 → 永久停摆（表现就是：接上导管后机器不工作，
                    //  水进不去/被原样顶回管道里，看起来像"把该输入的液体输出出去了"）。
                    // 按原版语义逐种产出液体判断：只有产出液体自己占满池子容量才停。
                    if (liquids.get(output.liquid) >= comboTotalLiquidCap - 0.001f) {
                        if (!dumpExtraLiquid)
                            return false;
                    } else {
                        allFull = false;
                    }
                }
                if (allFull)
                    return false;
            }
            return enabled;
        }

        // -------------------- 核心更新 --------------------
        @Override
        public void updateTile() {
            // FIX: 先恢复从存档读取时的 leader 共享模块引用
            if (pendingLeaderPos != -1) {
                Building b = world.build(pendingLeaderPos);
                if (b instanceof CombinedCrafterBuild leaderBuild
                        && leaderBuild.isValid() && leaderBuild.team == team) {
                    comboLeader = leaderBuild;
                    if (leaderBuild.items != null)
                        items = leaderBuild.items;
                    if (leaderBuild.liquids != null)
                        liquids = leaderBuild.liquids;
                } else {
                    // leader 已不存在，自己成为 leader
                    comboLeader = null;
                }
                pendingLeaderPos = -1;
                comboDirty = true;
            }

            if (isLeader() && comboDirty) {
                rebuildCombo();
            }

            // FIX[液体超容]: 上限是"每种液体各自"的（和物品一致）。
            // 组合体缩容(拆成员)后上限变小, 之前合法灌入的液体可能每种都超标,
            // 组长把每种液体各自截回组容量（互不挤占）。
            if (isLeader() && liquids != null) {
                for (Liquid l : content.liquids()) {
                    float have = liquids.get(l);
                    if (have > comboTotalLiquidCap + 0.001f) {
                        liquids.remove(l, have - comboTotalLiquidCap);
                    }
                }
            }

            // 热量自然衰减（只有 leader 执行）
            if (isLeader() && comboHeat > 0) {
                CombinedCrafter cb = (CombinedCrafter) block;
                comboHeat = Math.max(0f, comboHeat - cb.heatCapacity * 0.0001f * delta());
            }

            CombinedCrafter cb = (CombinedCrafter) block;

            // HeatProducer 模式的热量注入已在 updateGenericOrAttribute 中处理
            // 非 heatproducer 模式的旧热量输出保留（兼容旧配置）
            if (cb.mode != Mode.heatproducer && cb.heatOutput > 0 && efficiency > 0) {
                float current = getComboHeat();
                float cap = getComboHeatCap();
                setComboHeat(Math.min(current + cb.heatOutput * efficiency * delta(), cap));
            }

            if (cb.mode == Mode.separator) {
                updateSeparator();
            } else {
                updateGenericOrAttribute();
            }
        }

        private void updateGenericOrAttribute() {
            CombinedCrafter cb = (CombinedCrafter) block;
            // 热量需求检查（availableHeat() 只读直接相邻的热源）
            float heatEff = 1f;
            // FIX: 非 heatcrafter 模式不应用热量门 (原版 GenericCrafter 从不因 heatRequirement 停产;
            // BlockCloner 深拷贝失败的方块可能带着非零 heatRequirement)
            if (cb.mode == Mode.heatcrafter && cb.heatRequirement > 0) {
                heatEff = Math.min(availableHeat() / cb.heatRequirement, 1f);
            }
            // HeatCrafter 模式：效率由热量决定
            if (cb.mode == Mode.heatcrafter) {
                heatEff = heatEfficiency();
                if (heatEff > 0 && efficiency > 0) {
                    progress += getProgressIncrease(craftTime) * heatEff;
                    warmup = Mathf.approachDelta(warmup, warmupTarget(), warmupSpeed);

                    if (outputLiquids != null) {
                        float inc = getProgressIncrease(1f);
                        for (var output : outputLiquids) {
                            handleLiquid(this, output.liquid,
                                    Math.min(output.amount * inc,
                                            Math.max(0f, comboTotalLiquidCap - liquids.get(output.liquid))));
                        }
                    }

                    if (wasVisible && Mathf.chanceDelta(updateEffectChance)) {
                        updateEffect.at(x + Mathf.range(size * updateEffectSpread),
                                y + Mathf.range(size * updateEffectSpread));
                    }
                } else {
                    warmup = Mathf.approachDelta(warmup, 0f, warmupSpeed);
                }

                totalProgress += warmup * Time.delta;
                if (progress >= 1f)
                    craft();
                dumpOutputs();
                return;
            }

            // HeatProducer 模式：运行时输出热量
            if (cb.mode == Mode.heatproducer) {
                if (efficiency > 0) {
                    progress += getProgressIncrease(craftTime);
                    warmup = Mathf.approachDelta(warmup, warmupTarget(), warmupSpeed);
                    // 平滑热量输出值（对外暴露）
                    producerHeat = Mathf.approachDelta(producerHeat, cb.heatOutput * efficiency, warmupSpeed * delta());

                    // 同时往共享池注入（供组合体内部不相邻成员使用）
                    if (cb.heatOutput > 0) {
                        float current = getComboHeat();
                        float cap = getComboHeatCap();
                        setComboHeat(Math.min(current + cb.heatOutput * efficiency * delta(), cap));
                    }

                    if (outputLiquids != null) {
                        float inc = getProgressIncrease(1f);
                        for (var output : outputLiquids) {
                            handleLiquid(this, output.liquid,
                                    Math.min(output.amount * inc,
                                            Math.max(0f, comboTotalLiquidCap - liquids.get(output.liquid))));
                        }
                    }

                    if (wasVisible && Mathf.chanceDelta(updateEffectChance)) {
                        updateEffect.at(x + Mathf.range(size * updateEffectSpread),
                                y + Mathf.range(size * updateEffectSpread));
                    }
                } else {
                    warmup = Mathf.approachDelta(warmup, 0f, warmupSpeed);
                    producerHeat = Mathf.approachDelta(producerHeat, 0f, warmupSpeed * delta());
                }

                totalProgress += warmup * Time.delta;
                if (progress >= 1f)
                    craft();
                dumpOutputs();
                return;
            }
            // Generic / Attribute 模式
            if (efficiency > 0 && heatEff > 0) {
                progress += getProgressIncrease(craftTime) * heatEff;
                warmup = Mathf.approachDelta(warmup, warmupTarget(), warmupSpeed);

                if (outputLiquids != null) {
                    float inc = getProgressIncrease(1f);
                    for (var output : outputLiquids) {
                        handleLiquid(this, output.liquid,
                                Math.min(output.amount * inc,
                                        Math.max(0f, comboTotalLiquidCap - liquids.get(output.liquid))));
                    }
                }

                if (wasVisible && Mathf.chanceDelta(updateEffectChance)) {
                    updateEffect.at(x + Mathf.range(size * updateEffectSpread),
                            y + Mathf.range(size * updateEffectSpread));
                }
            } else {
                warmup = Mathf.approachDelta(warmup, 0f, warmupSpeed);
            }

            totalProgress += warmup * Time.delta;
            if (progress >= 1f)
                craft();
            dumpOutputs();
        }

        private void updateSeparator() {
            totalProgress += warmup * Time.delta;
            CombinedCrafter cb = (CombinedCrafter) block;

            // 热量需求检查
            // FIX[separator冻结]: 分离机永远不需要热量(原版 Separator 本体不继承热量体系)。
            // 克隆体 heatRequirement 默认 10f, 若实例未走 init 清理(如直接 new 顶替原版),
            // 热门控会把进度冻死在 0——separator 直接不启用热门控
            float heatEfficiency = 1f;

            if (efficiency > 0 && heatEfficiency > 0) {
                progress += getProgressIncrease(craftTime) * heatEfficiency;
                warmup = Mathf.lerpDelta(warmup, 1f, cb.separatorWarmupSpeed);
            } else {
                warmup = Mathf.lerpDelta(warmup, 0f, cb.separatorWarmupSpeed);
            }

            if (progress >= 1f) {
                progress %= 1f;
                craftSeparator();
            }
            dumpOutputs();
        }

        private void craftSeparator() {
            CombinedCrafter cb = (CombinedCrafter) block;
            if (cb.results == null || cb.results.length == 0)
                return;

            // FIX[独立储存+概率重分配]: 已满的产物不参与抽取, 其权重按比例分摊给
            // 其余未满产物; 全部产物已满时不消耗输入(shouldConsume 正常会拦住,
            // 这里兜底防止抽取间隙满容导致白吞原料)
            int sum = 0;
            for (ItemStack stack : cb.results)
                if (items.get(stack.item) < getMaximumAccepted(stack.item))
                    sum += stack.amount;

            if (sum <= 0)
                return;

            int i = Mathf.randomSeed(seed++, 0, sum - 1);
            int count = 0;
            Item item = null;
            for (ItemStack stack : cb.results) {
                if (items.get(stack.item) >= getMaximumAccepted(stack.item))
                    continue; // FIX: 跳过已满产物
                if (i >= count && i < count + stack.amount) {
                    item = stack.item;
                    break;
                }
                count += stack.amount;
            }

            consume();
            if (item != null && items.get(item) < getMaximumAccepted(item)) {
                items.add(item, 1);
                // FIX[区块产出统计]: 分离机产物同样补调 produced()
                produced(item, 1);
            }
        }

        @Override
        public void craft() {
            CombinedCrafter cb = (CombinedCrafter) block;
            if (cb.mode == Mode.separator)
                return;

            consume();
            if (outputItems != null) {
                for (var output : outputItems) {
                    for (int i = 0; i < output.amount; i++)
                        items.add(output.item, 1);
                    // FIX[区块产出统计]: 原版走 offload() 会自动调 produced() 计入
                    // sector.info.handleProduction -> rawProduction；此处自写产出逻辑
                    // 必须补调，否则区块产量被 min(production, rawProduction)=0 封顶
                    produced(output.item, output.amount);
                }
            }
            if (wasVisible)
                craftEffect.at(x, y);
            progress %= 1f;
        }

        @Override
        public void dumpOutputs() {
            CombinedCrafter cb = (CombinedCrafter) block;

            if (cb.mode == Mode.separator) {
                // FIX[搬运粒度]: 用 comboDumpInterval(1 tick) 代替原版 dumpTime(5 tick)，
                // 否则高速传送带/管道的单个插入点永远等不到下一次搬运（见字段注释）。
                if (timer(timerDump, comboDumpInterval / timeScale)) {
                    if (cb.results != null) {
                        for (ItemStack result : cb.results) {
                            boolean isIntermediate = isConsumedInCombo(result.item);
                            // 同上：有人吃的料只在它自己这一格 >=90% 满时才外送
                            float resultAmt = items.get(result.item);
                            boolean shouldDump = !isIntermediate
                                    || (shouldDumpIntermediate(result.item)
                                            && resultAmt >= getMaximumAccepted(result.item) * 0.9f);
                            boolean forceDump = isIntermediate
                                    && resultAmt >= getMaximumAccepted(result.item) * 0.99f;
                            if (shouldDump || forceDump) {
                                dump(result.item);
                            }
                        }
                    }
                }
                return;
            }

            // Generic / Attribute 的原有逻辑
            // 【混着输出】组里共用一份池子，所以任何一台的输出口都可以外送**整组**的产物
            // （2.2 就是这样：逐台遍历整组的 outputItems 再 dump）。
            // 后来为了性能改成"只搬自己这一格的产出"——因为逐台遍历整组是 O(N²)，
            // 大组合体每 tick 要搬 N² 次、直接掉帧。这里改成第三种写法：
            // 读组级聚合数据（ensureComboAggregate 每 tick 只在 leader 上算一次，
            // 里面有"整组产出哪些物品/液体"），每台只按**物品数**循环一遍 ——
            // 既恢复了混着输出，也没有 N² 的代价。
            CombinedCrafterBuild lead = leader();
            if (lead == null)
                return;

            if (timer(timerDump, comboDumpInterval / timeScale)) {
                lead.ensureComboAggregate();
                float[] produce = lead.comboItemProduceRate;
                if (produce != null) {
                    for (int id = 0; id < produce.length; id++) {
                        if (produce[id] <= 0.0001f)
                            continue;
                        Item item = content.item(id);
                        if (item == null)
                            continue;
                        boolean isIntermediate = isConsumedInCombo(item);
                        // FIX[原料被当产出倒出去]: 组内有人吃的料（含"上游产的 + 下游当原料用的"），
                        // 只在它在这一格的池子里 >=90% 满时才外送 —— 和液体那套完全一致。
                        // 原先只看"产率>耗率"(needPerCraft*2 就能触发)，结果像 大型硅厂 这种
                        // "隔壁有产煤机 + 自己烧煤"的组合会把作为原料的 coal 当成品倒到输出带上。
                        float itemAmt = items.get(item);
                        boolean shouldDump = !isIntermediate
                                || (shouldDumpIntermediate(item)
                                        && itemAmt >= getMaximumAccepted(item) * 0.9f);
                        boolean forceDump = isIntermediate
                                && itemAmt >= getMaximumAccepted(item) * 0.99f;
                        if (shouldDump || forceDump)
                            dump(item);
                    }
                }
            }
            // 液体同理：整组的产出液体都能从本格的输出口外送（方向优先取本格自己的配置）
            if (liquids != null) {
                lead.ensureComboAggregate();
                float[] produceLiquid = lead.comboLiquidProduceRate;
                if (produceLiquid != null) {
                    for (int id = 0; id < produceLiquid.length; id++) {
                        if (produceLiquid[id] <= 0.0001f)
                            continue;
                        Liquid liquid = content.liquid(id);
                        if (liquid == null)
                            continue;
                        int dir = -1;
                        if (cb.outputLiquids != null) {
                            for (int k = 0; k < cb.outputLiquids.length; k++) {
                                if (cb.outputLiquids[k].liquid == liquid) {
                                    dir = liquidOutputDirections.length > k ? liquidOutputDirections[k] : -1;
                                    break;
                                }
                            }
                        }
                        boolean isIntermediate = isLiquidConsumedInCombo(liquid);
                        // FIX[矿渣外流]: 旧逻辑"产率>耗率就外送"——熔炉名义产率远大于分离机
                        // 消耗, 矿渣被当过剩产物不断导出: 共享池恒空, 只泼溅邻近方块(越远越少)。
                        // 改为: 中间产物只在池子>=90%满容时才外送, 平时留在组内共享池
                        // 阈值看"这种产出液体自己"占了多少容量（每种液体独立储存）
                        float liqAmt = liquids.get(liquid);
                        boolean shouldDump = !isIntermediate
                                || (shouldDumpIntermediateLiquid(liquid)
                                        && liqAmt >= comboTotalLiquidCap * 0.9f);
                        boolean forceDump = isIntermediate
                                && liqAmt >= comboTotalLiquidCap * 0.99f;
                        if (shouldDump || forceDump)
                            dumpLiquid(liquid, 2f, dir);
                    }
                }
            }
        }

        // -------------------- 中间产物判断 --------------------
        /** 组结构变化（重建/成员增删）后让聚合缓存失效。 */
        public void invalidateComboAggregate() {
            comboAggregateTick = Long.MIN_VALUE;
            comboProducerHeatTick = Long.MIN_VALUE;
        }

        /** 刷新 leader 上的组级聚合；每 tick 至多算一次，之后所有查询都是 O(1)。 */
        public void ensureComboAggregate() {
            if (comboAggregateTick == state.updateId)
                return;
            comboAggregateTick = state.updateId;

            int itemN = content.items().size, liquidN = content.liquids().size;
            if (comboConsumedItems == null || comboConsumedItems.length < itemN) {
                comboConsumedItems = new boolean[itemN];
                comboItemProduceRate = new float[itemN];
                comboItemConsumeRate = new float[itemN];
                comboItemNeedPerCraft = new int[itemN];
            }
            if (comboConsumedLiquids == null || comboConsumedLiquids.length < liquidN) {
                comboConsumedLiquids = new boolean[liquidN];
                comboLiquidProduceRate = new float[liquidN];
                comboLiquidConsumeRate = new float[liquidN];
                comboLiquidNeedPerCraft = new float[liquidN];
            }
            java.util.Arrays.fill(comboConsumedItems, false);
            java.util.Arrays.fill(comboItemProduceRate, 0f);
            java.util.Arrays.fill(comboItemConsumeRate, 0f);
            java.util.Arrays.fill(comboItemNeedPerCraft, 0);
            java.util.Arrays.fill(comboConsumedLiquids, false);
            java.util.Arrays.fill(comboLiquidProduceRate, 0f);
            java.util.Arrays.fill(comboLiquidConsumeRate, 0f);
            java.util.Arrays.fill(comboLiquidNeedPerCraft, 0f);

            for (CombinedCrafterBuild member : group()) {
                if (!member.isValid())
                    continue;
                CombinedCrafter mb = (CombinedCrafter) member.block;

                // 谁在消耗什么（等价于原先逐成员 block.consumesItem/consumesLiquid）
                if (mb.itemFilter != null)
                    for (int i = 0; i < Math.min(mb.itemFilter.length, comboConsumedItems.length); i++)
                        if (mb.itemFilter[i])
                            comboConsumedItems[i] = true;
                if (mb.liquidFilter != null)
                    for (int i = 0; i < Math.min(mb.liquidFilter.length, comboConsumedLiquids.length); i++)
                        if (mb.liquidFilter[i])
                            comboConsumedLiquids[i] = true;

                // Generic / Attribute 产出
                if (mb.mode != Mode.separator && mb.outputItems != null) {
                    for (ItemStack out : mb.outputItems) {
                        if (out.item != null && out.item.id < comboItemProduceRate.length)
                            comboItemProduceRate[out.item.id] += out.amount / mb.craftTime * 60f;
                    }
                }
                // Separator 产出（按概率估算）
                if (mb.mode == Mode.separator && mb.results != null) {
                    int totalAmount = 0;
                    for (ItemStack r : mb.results)
                        totalAmount += r.amount;
                    if (totalAmount > 0) {
                        for (ItemStack out : mb.results) {
                            if (out.item != null && out.item.id < comboItemProduceRate.length)
                                comboItemProduceRate[out.item.id] += (out.amount / (float) totalAmount) / mb.craftTime * 60f;
                        }
                    }
                }

                // 液体产出
                if (mb.outputLiquids != null) {
                    for (LiquidStack out : mb.outputLiquids) {
                        if (out.liquid != null && out.liquid.id < comboLiquidProduceRate.length)
                            comboLiquidProduceRate[out.liquid.id] += out.amount * 60f;
                    }
                }

                // 输入（所有模式通用）
                if (mb.consumers != null) {
                    for (Consume cons : mb.consumers) {
                        if (cons instanceof ConsumeItems ci) {
                            for (ItemStack in : ci.items) {
                                if (in.item != null && in.item.id < comboItemConsumeRate.length) {
                                    comboItemConsumeRate[in.item.id] += in.amount / mb.craftTime * 60f;
                                    comboItemNeedPerCraft[in.item.id] += in.amount;
                                }
                            }
                        } else if (cons instanceof ConsumeLiquids cl) {
                            for (LiquidStack in : cl.liquids) {
                                if (in.liquid != null && in.liquid.id < comboLiquidConsumeRate.length) {
                                    comboLiquidConsumeRate[in.liquid.id] += in.amount * 60f;
                                    comboLiquidNeedPerCraft[in.liquid.id] += in.amount;
                                }
                            }
                        } else if (cons instanceof ConsumeLiquid cl) {
                            if (cl.liquid != null && cl.liquid.id < comboLiquidConsumeRate.length) {
                                comboLiquidConsumeRate[cl.liquid.id] += cl.amount * 60f;
                                comboLiquidNeedPerCraft[cl.liquid.id] += cl.amount;
                            }
                        }
                    }
                }
            }
        }

        public boolean isConsumedInCombo(Item item) {
            if (item == null)
                return false;
            CombinedCrafterBuild l = leader();
            l.ensureComboAggregate();
            return item.id < l.comboConsumedItems.length && l.comboConsumedItems[item.id];
        }

        public boolean isLiquidConsumedInCombo(Liquid liquid) {
            if (liquid == null)
                return false;
            CombinedCrafterBuild l = leader();
            l.ensureComboAggregate();
            return liquid.id < l.comboConsumedLiquids.length && l.comboConsumedLiquids[liquid.id];
        }

        public boolean shouldDumpIntermediate(Item item) {
            if (item == null)
                return false;
            CombinedCrafterBuild l = leader();
            l.ensureComboAggregate();
            int needPerCraftTotal = item.id < l.comboItemNeedPerCraft.length ? l.comboItemNeedPerCraft[item.id] : 0;
            if (needPerCraftTotal > 0 && items.get(item) < needPerCraftTotal * 2)
                return false;
            return l.comboItemProduceRate[item.id] > l.comboItemConsumeRate[item.id]
                    * (1f + ((CombinedCrafter) block).safetyBuffer);
        }

        public boolean shouldDumpIntermediateLiquid(Liquid liquid) {
            if (liquid == null)
                return false;
            CombinedCrafterBuild l = leader();
            l.ensureComboAggregate();
            float needPerCraftTotal = liquid.id < l.comboLiquidNeedPerCraft.length ? l.comboLiquidNeedPerCraft[liquid.id] : 0f;
            if (needPerCraftTotal > 0.001f && liquids.get(liquid) < needPerCraftTotal * 2f)
                return false;
            return l.comboLiquidProduceRate[liquid.id] > l.comboLiquidConsumeRate[liquid.id]
                    * (1f + ((CombinedCrafter) block).safetyBuffer);
        }

        // -------------------- 物品/液体交互 --------------------
        @Override
        public boolean acceptItem(Building source, Item item) {
            if (!block.hasItems || item == null)
                return false;
            boolean needed = isConsumedInCombo(item);
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
            if (!block.hasLiquids || liquid == null)
                return false;
            boolean needed = isLiquidConsumedInCombo(liquid);
            return needed && liquids.get(liquid) < comboTotalLiquidCap - 0.001f;
        }

        @Override
        public void handleLiquid(Building source, Liquid liquid, float amount) {
            if (amount <= 0.001f)
                return;
            float canAccept = Math.max(0f, comboTotalLiquidCap - liquids.get(liquid));
            float actual = Math.min(amount, canAccept);
            if (actual > 0.001f)
                liquids.add(liquid, actual);
            float refund = amount - actual;
            if (refund > 0.001f && source != null && source.liquids != null)
                source.liquids.add(liquid, refund);
        }

        @Override
        public boolean canDump(Building to, Item item) {
            CombinedCrafter cb = (CombinedCrafter) block;
            if (cb.mode == Mode.separator) {
                return !consumesItem(item);
            }
            return super.canDump(to, item);
        }

        public float getTotalLiquidCapacity() {
            return Math.max(comboTotalLiquidCap, 1f);
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
        public void display(Table table) {
          // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
          ComboUi.safe("combinedcrafter:display", () -> displayInner(table));
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
                    t.labelWrap(comboPanelTitle()).left().width(160f).padLeft(4);
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

        /**
         * 面板标题。用 {@link ComboNet#displayMembers} 而不是本地 group()：
         * 组合连接器/节点接起来的整张网络都算"一个组合体"，标题也按网络报数。
         */
        public String comboPanelTitle() {
            int count = comboMemberCount();
            return count > 1
                    ? "[accent]组合工厂[] x" + count + "\n" + block.getDisplayName(tile)
                    : block.getDisplayName(tile);
        }

        /** 面板上的"x N"：整张网络的成员数（连接器/节点接起来的都算）。 */
        public int comboMemberCount() {
            return ComboNet.displayMembers(this, group().size).size;
        }

        public void buildComboBars(Table table) {
            CombinedCrafter cb = (CombinedCrafter) block;
            // 只有 heatcrafter / heatproducer 显示热量条
            if (!Mathf.zero(block.health, 0.001f)) {
                final float h = health, mh = maxHealth;
                table.add(new Bar(
                        () -> Core.bundle.get("stat.health", "Health") + " " + (int) Math.max(h, 0),
                        () -> Pal.health,
                        () -> Mathf.clamp(h / mh)));
                table.row();
            }

            if (cb.mode == Mode.heatcrafter) {
                final float h = availableHeat(), req = cb.heatRequirement;
                table.add(new Bar(
                        () -> "热量 " + Strings.fixed(h, 1) + "/" + Strings.fixed(req, 1),
                        () -> Pal.lightOrange,
                        () -> Math.min(h / req, 1f)));
                table.row();
                final float eff = heatEfficiency();
                table.add(new Bar(
                        () -> "效率 " + Strings.fixed(eff * 100f, 0) + "%",
                        () -> Pal.accent,
                        () -> eff / cb.maxEfficiency));
                table.row();
            } else if (cb.mode == Mode.heatproducer) {
                final float h = producerHeat, out = cb.heatOutput;
                table.add(new Bar(
                        () -> "热量 " + Strings.fixed(h, 1) + "/" + Strings.fixed(out, 1),
                        () -> Pal.lightOrange,
                        () -> h / out));
                table.row();
            }

            float totalPower = 0f;
            for (CombinedCrafterBuild member : group()) {
                if (member.isValid() && member.block.consPower != null)
                    totalPower += member.block.consPower.usage;
            }
            if (totalPower > 0 && power != null) {
                final float tp = totalPower;
                table.add(new Bar(
                        () -> "电力 " + Strings.fixed(tp * power.status * 60f, 1) + " ⚡/s",
                        () -> Pal.power,
                        () -> power.status));
                table.row();
            }

            Seq<Item> involvedItems = new Seq<>();
            // 用 displayMembers 而不是本地 group()：连接器/节点把别的组合建筑接进来时，
            // 池子是共用的，对方用到的物品/液体也得有对应条目，否则面板看着"少了一半"。
            for (Building member : ComboNet.displayMembers(this, group().size)) {
                if (member.isValid() && member instanceof CombinedCrafterBuild mb) {
                    for (Item item : ((CombinedCrafter) mb.block).cachedItems) {
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
                        table.add(new Bar(
                                () -> item.localizedName + ": " + t + "/" + c,
                                () -> item.color,
                                () -> (float) t / c));
                        table.row();
                    }
                }
            }

            Seq<Liquid> involvedLiquids = new Seq<>();
            for (Building member : ComboNet.displayMembers(this, group().size)) {
                if (member.isValid() && member instanceof CombinedCrafterBuild mb) {
                    for (Liquid liquid : ((CombinedCrafter) mb.block).cachedLiquids) {
                        if (!involvedLiquids.contains(liquid))
                            involvedLiquids.add(liquid);
                    }
                }
            }

            LiquidModule sharedLiq = this.liquids;
            if (sharedLiq == null) {
                CombinedCrafterBuild l = leader();
                if (l != null)
                    sharedLiq = l.liquids;
            }
            if (sharedLiq == null) {
                for (CombinedCrafterBuild member : group()) {
                    if (member.liquids != null) {
                        sharedLiq = member.liquids;
                        break;
                    }
                }
            }
            // FIX[液条]: 缓存全空(如克隆体未走完整初始化管线)时, 回退到共享池里
            // 实际存在的液体——只要有液体在池里就有条可显示
            if (involvedLiquids.isEmpty() && sharedLiq != null) {
                for (Liquid lq : content.liquids()) {
                    if (sharedLiq.get(lq) > 0.001f && !involvedLiquids.contains(lq))
                        involvedLiquids.add(lq);
                }
            }

            if (sharedLiq != null) {
                for (Liquid liquid : involvedLiquids) {
                    float total = sharedLiq.get(liquid);
                    // FIX[液条]: 有参与的液体就显示——矿渣即产即耗常年接近0, 旧门槛把它隐藏了
                    if (total > -1f) {
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
            for (Building member : ComboNet.displayMembers(this, group().size)) {
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
            CombinedCrafter mb = (CombinedCrafter) this.block;
            table.add("[lightgray]本机: " + block.localizedName + "[]").left();
            table.row();

            boolean hasLocalInput = false;
            if (block.consumers != null && block.consumers.length > 0) {
                for (Consume cons : block.consumers) {
                    if (cons instanceof ConsumeItems ci) {
                        for (ItemStack stack : ci.items) {
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
                        for (LiquidStack stack : cls.liquids) {
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

            // 热量需求显示：只有 heatcrafter 模式
            if (mb.mode == Mode.heatcrafter && mb.heatRequirement > 0) {
                if (!hasLocalInput) {
                    table.add("[gray]输入:").left();
                    table.row();
                    hasLocalInput = true;
                }
                boolean has = availableHeat() >= mb.heatRequirement;
                table.table(row -> {
                    row.left();
                    row.add("[orange]≈ " + Strings.fixed(mb.heatRequirement, 0) + " 单位热量")
                            .color(has ? Color.white : Color.scarlet).left();
                }).left();
                table.row();
            }

            boolean hasLocalOutput = false;
            if (mb.mode == Mode.separator && mb.results != null && mb.results.length > 0) {
                int[] sum = { 0 };
                for (ItemStack stack : mb.results)
                    sum[0] += stack.amount;
                for (ItemStack stack : mb.results) {
                    if (!hasLocalOutput) {
                        table.add("[gray]产出:").left();
                        table.row();
                        hasLocalOutput = true;
                    }
                    int percent = sum[0] > 0 ? (int) ((float) stack.amount / sum[0] * 100) : 0;
                    table.table(row -> {
                        row.left();
                        if (stack.item.uiIcon != null)
                            row.image(stack.item.uiIcon).size(24f).padRight(4f);
                        row.add(stack.item.localizedName + " " + percent + "% [gray](随机)[]")
                                .color(Pal.accent).left();
                    }).left();
                    table.row();
                }
            } else if (mb.outputItems != null) {
                for (ItemStack stack : mb.outputItems) {
                    if (!hasLocalOutput) {
                        table.add("[gray]产出:").left();
                        table.row();
                        hasLocalOutput = true;
                    }
                    table.table(row -> {
                        row.left();
                        if (stack.item.uiIcon != null)
                            row.image(stack.item.uiIcon).size(24f).padRight(4f);
                        row.add(stack.item.localizedName + " x" + stack.amount + " / "
                                + Strings.fixed(mb.craftTime / 60f, 1) + "s [gray](单台)[]").color(Pal.accent).left();
                    }).left();
                    table.row();
                }
            }
            if (mb.outputLiquids != null && mb.mode != Mode.separator) {
                for (LiquidStack stack : mb.outputLiquids) {
                    if (!hasLocalOutput) {
                        table.add("[gray]产出:").left();
                        table.row();
                        hasLocalOutput = true;
                    }
                    table.table(row -> {
                        row.left();
                        if (stack.liquid.uiIcon != null)
                            row.image(stack.liquid.uiIcon).size(24f).padRight(4f);
                        row.add(stack.liquid.localizedName + " " + Strings.fixed(stack.amount * 60f, 1)
                                + "/s [gray](单台)[]").color(Pal.accent).left();
                    }).left();
                    table.row();
                }
            }
            if (mb.mode == Mode.heatproducer && mb.heatOutput > 0) {
                if (!hasLocalOutput) {
                    table.add("[gray]产出:").left();
                    table.row();
                    hasLocalOutput = true;
                }
                table.table(row -> {
                    row.left();
                    row.add("[orange]热量 " + Strings.fixed(mb.heatOutput, 1) + " 热/s").color(Pal.lightOrange).left();
                }).left();
                table.row();
            }
            if (!hasLocalOutput && (block.consumers == null || block.consumers.length == 0)) {
                table.add("[darkGray]无").left();
                table.row();
            }
        }

        // -------------------- 序列化 --------------------
        @Override
        public byte version() {
            return 10;
        }

        @Override
        public void write(Writes write) {
            // 确定真实 leader（按 pos 最小）
            CombinedCrafterBuild trueLeader = this;
            if (comboGroup != null && comboGroup.size > 0) {
                for (CombinedCrafterBuild b : comboGroup) {
                    if (b != null && b.isValid() && b.pos() < trueLeader.pos())
                        trueLeader = b;
                }
            }

            // 只有真实 leader 保存真实模块；其他成员写入空模块
            ItemModule savedItems = items;
            LiquidModule savedLiquids = liquids;
            if (this != trueLeader) {
                if (items != null)
                    items = new ItemModule();
                if (liquids != null)
                    liquids = new LiquidModule();
            }

            super.write(write);

            // 恢复引用（避免影响后续逻辑）
            items = savedItems;
            liquids = savedLiquids;

            // 写入 leader 引用
            write.bool(comboLeader != null);
            if (comboLeader != null)
                write.i(comboLeader.pos());

            // 写入共享热量
            write.f(getComboHeat());
            write.f(getComboHeatCap());

            // 模式特定数据
            CombinedCrafter cb = (CombinedCrafter) block;
            if (cb.mode == Mode.separator) {
                write.i(seed);
            } else if (cb.mode == Mode.attribute) {
                write.f(attrsum);
            } else if (cb.mode == Mode.heatproducer) {
                write.f(producerHeat);
            }
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);

            boolean hasLeader = false;
            int leaderPos = -1;
            if (revision >= 10) {
                hasLeader = read.bool();
                if (hasLeader)
                    leaderPos = read.i();
            }

            // 读取共享热量
            if (revision >= 10) {
                comboHeat = read.f();
                comboHeatCap = read.f();
            }

            comboDirty = true;

            if (hasLeader && leaderPos != pos()) {
                // 非 leader：先清空自己的模块，记录 leader 位置，等 updateTile 时恢复引用
                pendingLeaderPos = leaderPos;
                if (items != null)
                    items = new ItemModule();
                if (liquids != null)
                    liquids = new LiquidModule();
            } else {
                // 自己是 leader（或没有 leader）
                pendingLeaderPos = -1;
                comboLeader = null;
            }

            // 读取模式特定数据（version >= 2）
            if (revision >= 10) {
                CombinedCrafter cb = (CombinedCrafter) block;
                if (cb.mode == Mode.separator) {
                    seed = read.i();
                } else if (cb.mode == Mode.attribute) {
                    attrsum = read.f();
                } else if (cb.mode == Mode.heatproducer) {
                    producerHeat = read.f();
                }
            }
        }
    }
}
