package combine.storage;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Strings;
import arc.util.io.Reads;
import arc.util.io.Writes;
import combine.net.ComboNet;
import combine.util.ComboReflect;
import combine.util.ComboUi;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.heat.HeatBlock;
import mindustry.world.blocks.heat.HeatConsumer;
import mindustry.world.consumers.ConsumeLiquid;
import mindustry.world.consumers.ConsumeLiquidFilter;
import mindustry.world.consumers.ConsumeLiquids;
import mindustry.world.consumers.ConsumePower;
import mindustry.world.meta.BlockStatus;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.*;

/**
 * 超级组合方块：把多台**任意非炮台建筑**塞进一台边长 N（2~6）的方块里。
 *
 * <p>炮台走 {@link combine.turret.SuperTurret}，本类只接收工厂/钻机/发电机/仓库/力墙/
 * 升级厂/制造厂等非炮台建筑。
 *
 * <h3>共享规则</h3>
 * <ul>
 *   <li><b>物品</b>：全台共用一份 {@link ItemModule}（整台 = 一个大池子）。需要物品的格子由
 *       {@link SuperBlockBuild#feedItemsRound(int)} 从池里搬一小点进它自己的内部模块，
 *       走的是原版 {@code items.remove + handleItem} 路径，不凭空造货。</li>
 *   <li><b>液体</b>：全台共用一份 {@link LiquidModule}，格子直接读这份池子。</li>
 *   <li><b>电力</b>：格子的私有 {@code PowerGraph} 被合并进 SuperBlock 的图，格子成为该图
 *       正式的 producer/consumer/battery 成员——产电和耗电直接进电网。格子保留自己的
 *       {@code PowerModule}，电网分配到的 {@code power.status} 是格子自己的。</li>
 *   <li><b>热量</b>：每格挂一个热量探针，整台的热量池按需热格子数均摊。</li>
 * </ul>
 */
public class SuperBlock extends Block {

    /** 总开关。 */
    public static boolean enabled = true;

    /** 最小/最大边长。 */
    public static final int MIN_SIDE = 2, MAX_SIDE = 6;
    /** 每格一个按钮的大小（配置面板里）。 */
    static final float CELL_BTN_W = 42f, CELL_BTN_H = 30f;
    /** 每种边长一个方块实例（下标 = 边长），由 Main 在内容装配时填。 */
    public static final SuperBlock[] bySide = new SuperBlock[MAX_SIDE + 1];
    /** 内部用的"热量探针"方块（不落地、不进建造菜单）。 */
    public static @Nullable HeatProbe heatProbe;

    public final int side;
    /** 供 ComboReflect.baseLiquidCap 读取的"本机基础容量"。 */
    public float baseLiquidCapacity;
    /** 热量需求"标记值"：只用来让 ComboNet 认出"这台吃热"。 */
    public float heatRequirement = 10f;
    /** 没有对应边长的贴图时，底板改成程序化绘制。 */
    public boolean proceduralPlate = false;
    /** 液体 dump 的计时器索引（物品 dump 用基类的 timerDump）。 */
    public int timerLiquidDump = timers++;
    /** 绘制告警去重。 */
    static final ObjectSet<String> partDrawErrWarned = new ObjectSet<>();

    public SuperBlock(String name, int side) {
        super(name);
        this.side = side;
        size = side;
        update = true;
        solid = true;
        sync = true;
        saveConfig = true;
        configurable = true;
        hasItems = true;
        hasLiquids = true;
        canOverdrive = false;
        rotate = false;
        drawArrow = false;
        itemCapacity = side * side * 30;
        // 假容量（防管道按"目标方块的 liquidCapacity"限流）；
        // buildCells() 会把它改写成"内部所有建筑容量之和"。
        liquidCapacity = 9999f;
        baseLiquidCapacity = side * side * 20f;
        buildType = SuperBlockBuild::new;
        consume(new SuperConsumePower());
    }

    // ==================== 选建筑 / 布局编解码 ====================

    public static int sideFor(int count) {
        int side = MIN_SIDE;
        while (side < MAX_SIDE && side * side < count) side++;
        return side;
    }

    public static SuperBlock blockForSide(int side) {
        if (side < MIN_SIDE || side > MAX_SIDE) return null;
        return bySide[side];
    }

    /**
     * 一台世界里的建筑 → 该拿来当"格子建筑"的方块（组合方块换回它替换前的原版实例）。
     * <p>排除：炮台、核心、ConstructBlock、本模组替换出来的组合方块。
     */
    public static @Nullable Block cellBlock(Block worldBlock) {
        if (worldBlock == null) return null;

        // 炮台不进 SuperBlock
        if (worldBlock instanceof mindustry.world.blocks.defense.turrets.Turret) return null;

        // 换回替换前的原版实例
        Block orig = combine.BlockCloner.comboToOriginal.get(worldBlock);
        Block b = orig != null ? orig : worldBlock;

        // 换回原版后仍可能是炮台（组合炮塔换回的也是 Turret 子类）
        if (b instanceof mindustry.world.blocks.defense.turrets.Turret) return null;

        // 核心/ConstructBlock 排除
        if (b instanceof mindustry.world.blocks.storage.CoreBlock) return null;
        if (b instanceof mindustry.world.blocks.ConstructBlock) return null;

        // 本模组自己的组合方块别再叠一层
        if (b.getClass().getName().startsWith("combine.")) return null;

        return b;
    }

    public static String[] cellTokens(String layout) {
        if (layout == null || layout.isEmpty()) return new String[0];
        return layout.split(";", -1);
    }

    public static String encodeCell(Block b, float rot) {
        return b == null ? "" : b.name + "@" + Math.round(rot);
    }

    public static @Nullable Block blockOfToken(String token) {
        if (token == null || token.isEmpty()) return null;
        int at = token.lastIndexOf('@');
        String name = at < 0 ? token : token.substring(0, at);
        return cellBlock(content.block(name));
    }

    public static float rotOfToken(String token) {
        if (token == null || token.isEmpty()) return 0f;
        int at = token.lastIndexOf('@');
        if (at < 0) return 0f;
        try { return Float.parseFloat(token.substring(at + 1)); }
        catch (Throwable t) { return 0f; }
    }

    public static int cellTile(int origin, int size, int index) {
        return origin - (size - 1) / 2 + index;
    }

    // ==================== 热量探针 ====================

    public static class HeatProbe extends Block implements HeatBlock {
        public HeatProbe(String name) {
            super(name);
            size = 1;
            rotate = false;
            update = false;
            solid = false;
            destructible = false;
            buildVisibility = mindustry.world.meta.BuildVisibility.hidden;
            category = mindustry.type.Category.effect;
            buildType = HeatProbeBuild::new;
        }

        @Override public float heat() { return 0f; }
        @Override public float heatFrac() { return 0f; }

        public class HeatProbeBuild extends Building implements HeatBlock {
            public float amount;
            @Override public float heat() { return amount; }
            @Override public float heatFrac() { return 0f; }
        }
    }

    // ==================== 虚影 ====================

    @Override
    public boolean isVisible() {
        return false; // 不进建造菜单
    }

    @Override
    public boolean isPlaceable() {
        return supportsEnv(state.rules.env) && (!isBanned() || state.rules.editor);
    }

    public static float cellScale(Block cb) {
        if (cb == null) return 1f;
        float max = 0f;
        if (cb.region != null && cb.region.found()) max = Math.max(max, cb.region.width);
        if (cb.fullIcon != null && cb.fullIcon.found()) max = Math.max(max, cb.fullIcon.width);
        float oneTilePx = tilesize * 4f;
        if (max <= oneTilePx) return 1f;
        return oneTilePx / max;
    }

    @Override
    public void drawPlanRegion(BuildPlan plan, arc.util.Eachable<BuildPlan> list) {
        if (proceduralPlate) drawPlate(plan.drawx(), plan.drawy(), side);
        else drawDefaultPlanRegion(plan, list);
        if (plan.block != this) return;
        String[] tokens = cellTokens(plan.config instanceof String s ? s : null);
        for (int i = 0; i < tokens.length && i < side * side; i++) {
            Block b = blockOfToken(tokens[i]);
            if (b == null) continue;
            int cx = cellTile(plan.x, side, i % side);
            int cy = cellTile(plan.y, side, i / side);
            drawCellGhost(b, cx * tilesize, cy * tilesize, rotOfToken(tokens[i]), cellScale(b));
        }
    }

    static final arc.struct.ObjectMap<String, Building> ghostCells = new arc.struct.ObjectMap<>();
    static final ObjectSet<String> ghostCellBroken = new ObjectSet<>();

    static Team ghostTeam() {
        try { if (player != null && player.team() != null) return player.team(); }
        catch (Throwable ignored) {}
        return Team.sharded;
    }

    static void setTileBlock(Tile fake, Block b) {
        try {
            java.lang.reflect.Field f = Tile.class.getDeclaredField("block");
            f.setAccessible(true);
            f.set(fake, b);
        } catch (Throwable ignored) {}
    }

    public static @Nullable Building ghostCell(Block b) {
        if (b == null || b.name == null || ghostCellBroken.contains(b.name)) return null;
        Building c = ghostCells.get(b.name);
        if (c != null && c.block == b) return c;
        try {
            Building raw = b.newBuilding();
            Tile fake = new Tile(0, 0);
            setTileBlock(fake, b);
            raw.create(b, ghostTeam());
            raw.tile = fake;
            raw.set(0f, 0f);
            raw.proximity = new Seq<>();
            fake.build = raw;
            raw.rotation = 90;
            raw.health = raw.maxHealth;
            raw.enabled = true;
            raw.checkAllowUpdate();
            raw.created();
            ghostCells.put(b.name, raw);
            return raw;
        } catch (Throwable t) {
            ghostCellBroken.add(b.name);
            Log.err("[combine] 超级组合方块：虚影格子（@）创建失败", b.name, t);
            return null;
        }
    }

    public static void drawPlate(float cx, float cy, int side) {
        float s = side * tilesize;
        float x = cx - s / 2f, y = cy - s / 2f;
        Draw.color(0f, 0f, 0f, 0.5f);
        Fill.crect(x, y, s, s);
        Draw.color(Pal.gray);
        for (int i = 1; i < side; i++) {
            float off = i * tilesize;
            Fill.crect(x, y + off, s, 1f);
            Fill.crect(x + off, y, 1f, s);
        }
        Draw.color(Pal.accent, 0.35f);
        Fill.crect(x, y, s, 1.5f);
        Fill.crect(x, y + s - 1.5f, s, 1.5f);
        Fill.crect(x, y, 1.5f, s);
        Fill.crect(x + s - 1.5f, y, 1.5f, s);
        Draw.reset();
    }

    public static void drawCellGhost(Block b, float cx, float cy, float rot, float scale) {
        if (b == null) return;
        float ox = Draw.xscl, oy = Draw.yscl;
        arc.graphics.Color oc = Draw.getColor();
        float cr = oc.r, cg = oc.g, cb = oc.b, ca = oc.a;
        if (scale != 1f) Draw.scl(scale);
        try {
            TextureRegion icon = firstFound(b.fullIcon, b.uiIcon, b.region,
                    Core.atlas == null ? null : Core.atlas.find("error"));
            if (icon != null) {
                Draw.rect(icon, cx, cy, rot - 90f);
            } else {
                Building c = ghostCell(b);
                if (c != null) {
                    c.set(cx, cy);
                    c.rotation = Mathf.mod(Math.round(rot), 4);
                    c.draw();
                } else {
                    TextureRegion fb = b.region != null && b.region.found() ? b.region
                            : Core.atlas.find("error");
                    Draw.rect(fb, cx, cy, rot - 90f);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            Draw.scl(ox, oy);
            Draw.color(cr, cg, cb, ca);
        }
    }

    static boolean found(TextureRegion r) { return r != null && r.found(); }

    public static @Nullable TextureRegion firstFound(TextureRegion... regions) {
        if (regions != null)
            for (TextureRegion r : regions) if (found(r)) return r;
        return null;
    }

    // ==================== 电网 ====================

    /**
     * SuperBlock 本体的电网消费者。
     *
     * <p>【为什么 requestedPower/efficiency 都返回常量】内部格子的私有图已经合并进
     * SuperBlock 的图，格子是图上正式的 producer/consumer 成员——产电/耗电由图
     * 直接统计。如果本体再累加一次，就是双重计数。
     *
     * <p>本体自身不再是一个"真正的耗电方"，只是一个"电网容器"：
     * efficiency 恒 1 避免把整台的 build 效率拉低。
     */
    public static class SuperConsumePower extends ConsumePower {
        public SuperConsumePower() { super(0f, 0f, false); }

        @Override
        public float requestedPower(Building entity) {
            // 内部格子的耗电由它们在 SuperBlock 图里的独立 consumer 身份自动上报；
            // SuperBlock 不再累加，避免双重计数。
            return 0f;
        }

        @Override
        public float efficiency(Building build) {
            // 本体效率不因电网供电不足而下降（真正吃电的是内部格子，
            // 它们的 power.status 由电网分配）。
            return 1f;
        }
    }

    // ==================== 建筑本体 ====================

    public class SuperBlockBuild extends Building implements combine.util.IComboGrouped {
        // ---- 布局与格子 ----
        public String layout = "";
        public String sources = "";
        public boolean sourcesConsumed = false;
        public Building[] cells = new Building[0];
        public Block[] cellBlocks = new Block[0];
        public float[] cellRot = new float[0];
        public HeatProbe.HeatProbeBuild[] probes = new HeatProbe.HeatProbeBuild[0];
        public float[] heatSide = new float[4];
        public float comboTotalHeat = 0f;
        /** 每格**选定**的"填充物品"（null = 自动：池里有什么就喂什么）。 */
        public Item[] cellItem = new Item[0];
        public boolean cellsReady = false;
        public boolean placedFullHealth = false;
        public boolean healthFixPending = true;
        public int realItemCap = 1;
        public float realLiquidCap = 1f;
        public ObjectSet<Integer> cellBroken = new ObjectSet<>();

        // ---- 组合网络约定字段 ----
        public SuperBlockBuild comboLeader;
        public Seq<Building> comboGroup = new Seq<>();
        public boolean comboDirty = true;
        public int comboTotalItemCap;
        public float comboTotalLiquidCap;

        // ==================== 分组 ====================

        @Override
        public SuperBlockBuild leader() {
            if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null))
                comboLeader = null;
            return comboLeader == null ? this : comboLeader;
        }

        public boolean isLeader() { return leader() == this; }

        @Override
        public Seq<Building> group() {
            SuperBlockBuild l = leader();
            if (l.comboGroup == null) l.comboGroup = new Seq<>();
            return l.comboGroup;
        }

        @Override
        public void markGroupDirty() { comboDirty = true; }

        public void rebuildCombo() {
            Seq<Building> members = new Seq<>();
            members.add(this);
            try {
                for (Building b : ComboReflect.linkedReachable(this,
                        o -> o instanceof SuperBlockBuild st && st.team == team && st.isValid(),
                        (cur, o) -> true)) {
                    if (b != this && b.isValid()) members.addUnique(b);
                }
            } catch (Throwable t) {
                Log.err("[combine] 超级方块分组失败（只算自己）", t);
            }

            SuperBlockBuild ldr = this;
            for (Building b : members)
                if (b.isValid() && b.pos() < ldr.pos()) ldr = (SuperBlockBuild) b;

            int itemCap = 0;
            float liquidCap = 0f;
            for (Building b : members) {
                if (!b.isValid()) continue;
                itemCap += ComboReflect.baseItemCap(b);
                liquidCap += ComboReflect.baseLiquidCap(b);
            }

            for (Building b : members) {
                SuperBlockBuild s = (SuperBlockBuild) b;
                s.comboGroup = members;
                s.comboLeader = s == ldr ? null : ldr;
                s.comboTotalItemCap = Math.max(itemCap, 1);
                s.comboTotalLiquidCap = Math.max(liquidCap, 1f);
                s.comboDirty = false;
            }
            ldr.comboLeader = null;

            if (members.size > 1) sharePools(ldr, members);
        }

        public void sharePools(SuperBlockBuild ldr, Seq<Building> members) {
            boolean dedupe = ComboNet.pendingLoadDedupe();

            // ---- 物品 ----
            ItemModule itemPool = ldr.items;
            if (itemPool == null)
                for (Building m : members) if (m.items != null) { itemPool = m.items; break; }
            if (itemPool != null) {
                ObjectSet<ItemModule> outside = ComboReflect.itemPoolsSharedOutside(members);
                if (!outside.isEmpty()) itemPool = outside.first();
                ObjectSet<ItemModule> seen = new ObjectSet<>();
                seen.add(itemPool);
                for (Building m : members) {
                    if (m.items == null || !seen.add(m.items)) continue;
                    if (!dedupe && !outside.contains(m.items)) moveItems(m.items, itemPool);
                }
                for (Building m : members) m.items = itemPool;
            }

            // ---- 液体 ----
            LiquidModule liquidPool = ldr.liquids;
            if (liquidPool == null)
                for (Building m : members) if (m.liquids != null) { liquidPool = m.liquids; break; }
            if (liquidPool != null) {
                ObjectSet<LiquidModule> outside = ComboReflect.liquidPoolsSharedOutside(members);
                if (!outside.isEmpty()) liquidPool = outside.first();
                ObjectSet<LiquidModule> seen = new ObjectSet<>();
                seen.add(liquidPool);
                for (Building m : members) {
                    if (m.liquids == null || !seen.add(m.liquids)) continue;
                    if (!dedupe && !outside.contains(m.liquids)) moveLiquids(m.liquids, liquidPool);
                }
                for (Building m : members) m.liquids = liquidPool;
            }
        }

        static void moveItems(ItemModule from, ItemModule to) {
            if (from == null || to == null || from == to) return;
            for (Item item : content.items()) {
                int amt = from.get(item);
                if (amt > 0) { to.add(item, amt); from.remove(item, amt); }
            }
            to.stopFlow();
        }

        static void moveLiquids(LiquidModule from, LiquidModule to) {
            if (from == null || to == null || from == to) return;
            for (Liquid liquid : content.liquids()) {
                float amt = from.get(liquid);
                if (amt > 0.001f) { to.add(liquid, amt); from.remove(liquid, amt); }
            }
            to.stopFlow();
        }

        // ==================== 生命周期 ====================

        @Override
        public void created() {
            super.created();
            cellsReady = false;
            placedFullHealth = true;
            healthFixPending = true;
            comboDirty = true;
            comboTotalItemCap = block.itemCapacity;
            comboTotalLiquidCap = baseLiquidCapacity;
            ensureCells();
        }

        @Override
        public void onProximityUpdate() {
            super.onProximityUpdate();
            comboDirty = true;
        }

        @Override
        public void onRemoved() {
            disposeCells();
            comboLeader = null;
            comboGroup = new Seq<>();
            super.onRemoved();
        }

        @Override
        public void onDestroyed() {
            for (Building c : cells)
                if (c != null) try { c.onDestroyed(); } catch (Throwable ignored) {}
            super.onDestroyed();
        }

        @Override
        public boolean shouldConsume() { return true; }

        // ==================== 状态 ====================

        @Override
        public BlockStatus status() {
            if (!SuperBlock.enabled) return BlockStatus.logicDisable;
            ensureCells();

            boolean any = false;
            boolean needsPower = false;
            boolean anyWorkable = false;

            for (int i = 0; i < cells.length; i++) {
                Building c = cells[i];
                if (c == null) continue;
                any = true;
                Block cb = i < cellBlocks.length ? cellBlocks[i] : null;
                if (cb != null && cb.hasPower) needsPower = true;

                boolean productive = cb != null && (cb.outputsPower || cb.outputsLiquid
                        || cb instanceof mindustry.world.blocks.production.Drill
                        || cb instanceof mindustry.world.blocks.production.Pump
                        || cb instanceof mindustry.world.blocks.production.SolidPump
                        || cb instanceof mindustry.world.blocks.production.WallCrafter
                        || cb instanceof mindustry.world.blocks.production.GenericCrafter
                        || cb instanceof mindustry.world.blocks.heat.HeatProducer);
                if (productive) { anyWorkable = true; continue; }

                boolean hasLiquid = cb != null && cb.hasLiquids && liquids != null;
                boolean hasItem = cb != null && cb.hasItems && items != null && items.total() > 0;

                // 反射读 heat 字段（Building 没有这个字段）
                boolean hasHeat = false;
                if (c instanceof HeatConsumer hc && hc.heatRequirement() > 0f) {
                    Float heat = ComboReflect.getFloat(c, "heat");
                    hasHeat = heat != null && heat > 0.001f;
                }
                if (hasLiquid || hasItem || hasHeat) anyWorkable = true;
            }

            if (!any) return BlockStatus.active;
            if (!anyWorkable) return BlockStatus.noInput;
            if (needsPower && (power == null || power.status < 0.999f)) return BlockStatus.noInput;
            return BlockStatus.active;
        }

        // ==================== 主动输出 ====================

        /**
         * 内部有格子"声明"能吃这种物品（含动态配方，如单位工厂）。
         *
         * <p>遍历 {@code block.consumers}——覆盖三类消费者：
         * <ul>
         *   <li>{@link mindustry.world.consumers.ConsumeItems}：固定物品列表（如硅厂）</li>
         *   <li>{@link mindustry.world.consumers.ConsumeItemFilter}：按过滤器的物品集合（如发电机燃料）</li>
         *   <li>{@link mindustry.world.consumers.ConsumeItemDynamic}：运行期动态配方（如单位工厂）——
         *       需要把当前格子实例传进去算一遍，所以这个方法必须在有 cells[i] 的上下文中调用</li>
         * </ul>
         */
        private boolean internalDeclaresItem(Item item) {
            if (item == null) return false;
            for (Building c : cells) {
                if (c == null || c.block == null || c.block.consumers == null) continue;
                for (mindustry.world.consumers.Consume cons : c.block.consumers) {
                    try {
                        if (cons instanceof mindustry.world.consumers.ConsumeItems ci) {
                            for (mindustry.type.ItemStack stack : ci.items) {
                                if (stack.item == item) return true;
                            }
                        } else if (cons instanceof mindustry.world.consumers.ConsumeItemFilter cf) {
                            if (cf.filter.get(item)) return true;
                        } else if (cons instanceof mindustry.world.consumers.ConsumeItemDynamic cd) {
                            // 动态配方：把当前建筑实例传进去算一遍
                            mindustry.type.ItemStack[] stacks = cd.items.get(c);
                            if (stacks != null) {
                                for (mindustry.type.ItemStack stack : stacks) {
                                    if (stack.item == item) return true;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            return false;
        }

        /**
         * 内部有格子"声明"能吃这种液体。覆盖固定（{@link ConsumeLiquid} / {@link ConsumeLiquids}）
         * 和过滤器（{@link ConsumeLiquidFilter}）两类。
         */
        private boolean internalDeclaresLiquid(Liquid liquid) {
            if (liquid == null) return false;
            for (Building c : cells) {
                if (c == null || c.block == null || c.block.consumers == null) continue;
                for (mindustry.world.consumers.Consume cons : c.block.consumers) {
                    try {
                        if (cons instanceof mindustry.world.consumers.ConsumeLiquid cl) {
                            if (cl.liquid == liquid) return true;
                        } else if (cons instanceof mindustry.world.consumers.ConsumeLiquids cls) {
                            for (mindustry.type.LiquidStack stack : cls.liquids) {
                                if (stack.liquid == liquid) return true;
                            }
                        } else if (cons instanceof mindustry.world.consumers.ConsumeLiquidFilter clf) {
                            if (clf.filter.get(liquid)) return true;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            return false;
        }

        /**
         * 覆写 dump(Item)：只 dump "内部不声明吃"的物品。
         *
         * <p>池子里的物品分两类：
         *   · 内部某格声明要吃的（原料）→ 留给 feedItemsRound，不往外送；
         *   · 内部没人声明吃的（产品/多余料）→ 照原版 dump 送给邻居。
         *
         * <p>不需要覆写 dump() 无参版本：基类实现会调 dump(null)，
         * 正好落到这里。dumpAccumulate / offload / put / moveForward 也都走基类默认。
         */
        @Override
        public boolean dump(Item todump) {
            if (items == null || items.total() == 0 || proximity == null || proximity.size == 0)
                return false;

            int dump = this.cdump;
            for (Item item : content.items()) {
                if (items.get(item) <= 0) continue;
                if (todump != null && todump != item) continue;
                // 内部声明要吃 → 留在池里（喂料路径负责，不 dump 出去）
                if (internalDeclaresItem(item)) continue;

                for (int i = 0; i < proximity.size; i++) {
                    Building other = proximity.get((i + dump) % proximity.size);
                    if (other != null && other.acceptItem(this, item)) {
                        other.handleItem(this, item);
                        items.remove(item, 1);
                        incrementDump(proximity.size);
                        return true;
                    }
                    incrementDump(proximity.size);
                }
            }
            return false;
        }

        // ==================== 主循环 ====================

        @Override
        public void updateTile() {
            if (!combine.util.ComboTeams.playerTeam(team)) { super.updateTile(); return; }
            try { ensureCells(); }
            catch (Throwable t) {
                if (cellBroken.add(-1)) Log.err("[combine] ensureCells 抛异常", t);
            }
            if (healthFixPending && cellsReady) {
                healthFixPending = false;
                if (placedFullHealth || health <= block.health + 0.5f || health >= maxHealth - 0.5f)
                    health = maxHealth;
                else health = Math.min(health, maxHealth);
                placedFullHealth = false;
            }
            try {
                if (isLeader() && comboDirty) rebuildCombo();
            } catch (Throwable t) {
                if (cellBroken.add(-2)) Log.err("[combine] rebuildCombo 抛异常", t);
            }

            // ---- 整台热量池 ----
            float totalHeat = 0f;
            try { totalHeat = calculateHeat(heatSide); } catch (Throwable ignored) {}
            try {
                ObjectSet<Building> union = new ObjectSet<>();
                if (proximity != null)
                    for (Building m : proximity)
                        if (m != null && m != this && m.isValid()) union.add(m);
                Seq<Building> net = ComboNet.componentMembers(this);
                if (net != null)
                    for (Building m : net)
                        if (m != null && m != this && m.isValid()) union.add(m);
                try {
                    for (Building m : combine.coop.CoopCombo.coopGroup(this))
                        if (m != null && m != this && m.isValid()) union.add(m);
                } catch (Throwable ignored) {}

                float poolUnion = 0f;
                for (Building m : union) {
                    if (m instanceof HeatBlock hb && !(m instanceof HeatProbe.HeatProbeBuild))
                        poolUnion += Math.max(0f, hb.heat());
                }
                totalHeat = Math.max(totalHeat, poolUnion);
            } catch (Throwable ignored) {}

            try {
                float allocated = ComboNet.heatFor(this);
                if (allocated > totalHeat) totalHeat = allocated;
            } catch (Throwable ignored) {}

            comboTotalHeat = totalHeat;
            float demandTotal = heatDemand();
            for (int i = 0; i < probes.length; i++) {
                HeatProbe.HeatProbeBuild p = probes[i];
                if (p == null) continue;
                p.amount = heatShare(i, totalHeat, demandTotal);
                p.team = team;
            }

            // ---- 液体超容量裁剪 ----
            try {
                if (liquids != null) {
                    for (Liquid liq : content.liquids()) {
                        float amt = liquids.get(liq);
                        if (amt > realLiquidCap) liquids.remove(liq, amt - realLiquidCap);
                    }
                }
            } catch (Throwable ignored) {}

            // ---- 轮询装填：所有格子齐头并进 ----
            for (int round = 0; round < 16; round++) feedItemsRound(8);

            // ---- 每格 update ----
            for (int i = 0; i < cells.length; i++) {
                Building c = cells[i];
                if (c == null || c.dead()) continue;
                if (c.items != items) c.items = items;
                if (c.liquids != liquids) c.liquids = liquids;
                // ★ 不再回写 power.status：格子的 power 是它自己的模块，
                //   由电网（PowerGraph.update）直接分配到格子自己的 power.status 上。

                // ★ 强制格子的 proximity 只含探针。
                //   格子的假 Tile 坐标落在真世界坐标上，任何原版路径重建 proximity
                //   都会 world.build() 查到平台**外部**的建筑，把它们卷进来 ——
                //   之后格子的 dump 就会把料送给平台外的邻居。
                if (c.proximity == null) c.proximity = new Seq<>();
                if (i < probes.length && probes[i] != null) {
                    if (c.proximity.size != 1 || c.proximity.first() != probes[i]) {
                        c.proximity.clear();
                        c.proximity.add(probes[i]);
                    }
                } else if (c.proximity.size != 0) {
                    c.proximity.clear();
                }

                try {
                    c.update();
                    float req = c instanceof HeatConsumer hc ? hc.heatRequirement() : 0f;
                    if (req > 0f && demandTotal > 0f)
                        ComboReflect.setFloat(c, "heat", totalHeat * req / demandTotal);
                } catch (Throwable t) {
                    if (cellBroken.add(i))
                        Log.err("[combine] 超级方块第 @ 格（@）更新出错，已停用", i,
                                cellBlocks[i] == null ? "?" : cellBlocks[i].name, t);
                    cells[i] = null;
                }
            }

            // ---- 主动输出：物品 ----
            // 照原版 GenericCrafter.dumpOutputs 的思路，定时把"内部不收的"池子物品推给邻居。
            // 循环 8 次是因为池子里可能积压多种物品，每种要找一圈邻居。
            if (items != null && items.total() > 0 && timer(timerDump, dumpTime / timeScale)) {
                for (int i = 0; i < 8; i++) {
                    if (!dump(null)) break;
                }
            }

            // ---- 主动输出：液体 ----
            // 用独立的计时器（timerLiquidDump = timers++），与原版物品 dump 互不干扰。
            if (liquids != null && timer(timerLiquidDump, dumpTime / timeScale)) {
                for (Liquid l : content.liquids()) {
                    if (liquids.get(l) <= 0.001f) continue;
                    if (internalDeclaresLiquid(l)) continue;
                    dumpLiquid(l);
                }
            }
        }

        public float cellPowerUse() {
            float sum = 0f;
            for (Building c : cells) {
                if (c == null || c.block == null || !c.block.hasPower || c.block.consPower == null)
                    continue;
                try { sum += c.block.consPower.requestedPower(c); }
                catch (Throwable ignored) {}
            }
            return sum;
        }

        /** 从共享物品池往这一格搬运物品（走原版 remove + handleItem）。 */
        public void feedItems(Building c, int idx, int budget) {
            if (items == null || c == null || budget <= 0) return;

            Item forced = cellItemAt(idx);

            if (forced != null) {
                if (items.get(forced) <= 0) return;
                int guard = 0;
                while (items.get(forced) > 0 && c.acceptItem(this, forced) && guard++ < budget) {
                    items.remove(forced, 1);
                    c.handleItem(this, forced);
                }
                return;
            }

            for (Item item : content.items()) {
                if (items.get(item) <= 0) continue;
                int guard = 0;
                while (items.get(item) > 0 && c.acceptItem(this, item) && guard++ < budget) {
                    items.remove(item, 1);
                    c.handleItem(this, item);
                }
            }
        }

        public void feedItemsRound(int perCell) {
            for (int i = 0; i < cells.length; i++) {
                Building c = cells[i];
                if (c == null || c.dead()) continue;
                try { feedItems(c, i, perCell); }
                catch (Throwable t) {
                    if (cellBroken.add(i))
                        Log.err("[combine] 超级方块第 @ 格（@）装填出错，已停用", i,
                                cellBlocks[i] == null ? "?" : cellBlocks[i].name, t);
                    cells[i] = null;
                }
            }
        }

        public @Nullable Item cellItemAt(int idx) {
            return cellItem != null && idx >= 0 && idx < cellItem.length ? cellItem[idx] : null;
        }

        public void setCellItem(int idx, @Nullable Item item) {
            ensureCells();
            int n = side * side;
            if (idx < 0 || idx >= n || cells.length != n) return;
            if (cellItem.length != n) cellItem = new Item[n];
            cellItem[idx] = item;
            // 换的时候把这一格内部已经装着的物品退回共享池（不凭空造货、也不丢库存）
            Building c = cells[idx];
            if (c == null || c.items == null) return;
            for (Item i : content.items()) {
                int amt = c.items.get(i);
                if (amt > 0) {
                    items.add(i, amt);
                    c.items.remove(i, amt);
                }
            }
        }

        // ==================== 序列化 ====================

        public void applyLayout(String l) {
            String next = l == null ? "" : l;
            if (cellsReady && next.equals(layout)) return;
            layout = next;
            disposeCells();
            buildCells();
        }

        public void ensureCells() { if (!cellsReady) buildCells(); }

        void disposeCells() {
            for (Building c : cells)
                if (c != null) {
                    try {
                        // ★ 从 SuperBlock 的 PowerGraph 成员表移除（removeList 只动列表、不改图结构；
                        //   remove() 会按分支重划整张图，对我们不适用）。
                        if (c.power != null && power != null && c.power.graph == power.graph) {
                            power.graph.removeList(c);
                        }
                        c.remove();
                    } catch (Throwable ignored) {}
                }
            cells = new Building[0];
            cellBlocks = new Block[0];
            cellRot = new float[0];
            probes = new HeatProbe.HeatProbeBuild[0];
            cellItem = new Item[0];
            cellsReady = false;
            cellBroken.clear();
        }

        void buildCells() {
            int n = side * side;
            String[] tokens = cellTokens(layout);
            Building[] cs = new Building[n];
            Block[] bs = new Block[n];
            float[] rs = new float[n];
            HeatProbe.HeatProbeBuild[] ps = new HeatProbe.HeatProbeBuild[n];
            int itemCap = 0;
            float liquidCap = 0f;
            float healthSum = 0f;

            for (int i = 0; i < n; i++) {
                String token = i < tokens.length ? tokens[i] : "";
                Block cb = blockOfToken(token);
                if (cb == null) continue;
                int cx = cellTile(tile.x, side, i % side), cy = cellTile(tile.y, side, i / side);
                Building c = makeCell(cb, cx, cy, rotOfToken(token));
                if (c == null) continue;
                cs[i] = c;
                bs[i] = cb;
                rs[i] = rotOfToken(token);
                HeatProbe.HeatProbeBuild p = makeProbe(cx, cy);
                if (p != null) {
                    ps[i] = p;
                    // 格子的 proximity 只含探针；updateTile 每帧还会加固一遍。
                    c.proximity = new Seq<>();
                    c.proximity.add(p);
                }
                if (cb.hasItems) itemCap += cellItemCap(cb);
                if (cb.hasLiquids) liquidCap += Math.max(cb.liquidCapacity, 0f);
                healthSum += Math.max(cb.health, 1f);
            }

            cells = cs;
            cellBlocks = bs;
            cellRot = rs;
            probes = ps;
            realItemCap = Math.max(itemCap, 1);
            realLiquidCap = Math.max(liquidCap, 1f);

            // ★ 方块级容量 = 内部所有建筑容量相加（面板/信息条读的就是这两个字段）。
            //   空壳（没有任何格子建筑）时不动，避免全局字段被写成 1。
            if (itemCap > 0 || liquidCap > 0f) {
                itemCapacity = realItemCap;
                liquidCapacity = Math.max(realLiquidCap, 1f);
            }

            float oldMax = maxHealth;
            maxHealth = Math.max(healthSum, 1f);
            if (placedFullHealth || oldMax <= 0.001f || health <= 0.5f
                    || health >= oldMax - 0.5f || health <= block.health + 0.5f)
                health = maxHealth;
            else health = Math.min(health, maxHealth);
            placedFullHealth = false;
            cellsReady = true;
        }

        static int cellItemCap(Block b) {
            return Math.max(b.itemCapacity, 1);
        }

        @Nullable
        HeatProbe.HeatProbeBuild makeProbe(int cx, int cy) {
            try {
                if (heatProbe == null) return null;
                Building raw = heatProbe.newBuilding();
                if (!(raw instanceof HeatProbe.HeatProbeBuild p)) return null;
                p.create(heatProbe, team);
                p.tile = new Tile(cx, cy);
                p.set(cx * tilesize, cy * tilesize);
                p.proximity = new Seq<>();
                p.team = team;
                return p;
            } catch (Throwable t) {
                Log.err("[combine] 超级方块：热量探针创建失败", t);
                return null;
            }
        }

        @Nullable
        Building makeCell(Block cb, int cx, int cy, float rot) {
            try {
                Building c = cb.newBuilding();
                Tile fake = new Tile(cx, cy);
                setTileBlock(fake, cb);
                c.create(cb, team);
                c.tile = fake;
                c.set(cx * tilesize, cy * tilesize);
                c.proximity.clear();
                // 判空：cb 可能没有 hasItems / hasLiquids（例如纯电力方块），
                // 此时 this.items / this.liquids 还是 null，直接赋值会把格子的模块抹成 null
                if (items != null) c.items = items;
                if (liquids != null) c.liquids = liquids;
                fake.build = c;
                c.rotation = Mathf.mod(Math.round(rot), 4);
                c.health = c.maxHealth;
                c.enabled = true;
                c.checkAllowUpdate();
                c.created();

                // ★ 电力：把格子自己的私有 PowerGraph 合并进 SuperBlock 的图。
                //   注意**不能**共享 PowerModule —— PowerGraph.add() 的判据
                //   `build.power.graph != this || !build.power.init` 在共享模块时会早退，
                //   格子根本进不了 producers/consumers，产电耗电都不算。
                //   格子保留独立模块、只把 power.graph 引用切到 SuperBlock 的图，成为正式成员。
                if (c.block.hasPower && c.block.connectedPower) {
                    power.graph.add(c);
                    power.graph.checkAdd();
                }
                return c;
            } catch (Throwable t) {
                if (cellBroken.add(-1))
                    Log.err("[combine] 超级方块：@ 不能作为格子建筑（已留空）", cb.name, t);
                return null;
            }
        }

        @Override
        public Object config() {
            return layout + "|" + (sources == null ? "" : sources);
        }

        @Override
        public void configured(@Nullable mindustry.gen.Unit builder, @Nullable Object value) {
            if (value instanceof String s) {
                int bar = s.indexOf('|');
                applyLayout(bar < 0 ? s : s.substring(0, bar));
                if (bar >= 0) {
                    sources = s.substring(bar + 1);
                    consumeSources();
                }
            } else if (value instanceof Integer packed) {
                int idx = packed & 0xFFFF;
                int ord = (packed >>> 16) & 0xFFFF;
                setCellItem(idx, ord <= 0 ? null : content.item(ord - 1));
            }
            super.configured(builder, value);
        }

        public void consumeSources() {
            if (sourcesConsumed || sources == null || sources.isEmpty()) return;
            sourcesConsumed = true;
            if (net.client()) return;
            for (String tok : sources.split(";")) {
                if (tok == null || tok.isEmpty()) continue;
                int comma = tok.indexOf(',');
                if (comma < 0) continue;
                int x, y;
                try {
                    x = Integer.parseInt(tok.substring(0, comma).trim());
                    y = Integer.parseInt(tok.substring(comma + 1).trim());
                } catch (Throwable t) { continue; }
                Tile t = world.tile(x, y);
                if (t == null || t.build == null) continue;
                try { t.removeNet(); }
                catch (Throwable e) { Log.err("[combine] 拆掉被框选的建筑失败（@,@）", x, y, e); }
            }
        }

        @Override public byte version() { return 1; }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.str(layout);
            write.str(sources == null ? "" : sources);
            for (int i = 0; i < side * side; i++) {
                Item a = cellItemAt(i);
                write.s(a == null ? 0 : a.id + 1);
            }
            for (Building c : cells) {
                if (c == null) { write.b((byte) -1); }
                else { write.b(c.version()); c.write(write); }
            }
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            applyLayout(read.str());
            sources = read.str();
            sourcesConsumed = true;
            cellItem = new Item[side * side];
            for (int i = 0; i < side * side; i++) {
                int ord = read.s();
                cellItem[i] = ord <= 0 ? null : content.item(ord - 1);
            }
            int n = side * side;
            for (int i = 0; i < n; i++) {
                byte ver = read.b();
                Building c = i < cells.length ? cells[i] : null;
                if (c == null) continue;
                c.read(read, ver);
            }
        }

        // ==================== 物品 / 液体 / 热量 ====================

        @Override public int getMaximumAccepted(Item item) { return realItemCap; }

        @Override
        public boolean acceptItem(Building source, Item item) {
            if (items == null || item == null || items.get(item) >= realItemCap) return false;
            for (int i = 0; i < cells.length; i++) {
                Building c = cells[i];
                if (c == null) continue;
                Item forced = cellItemAt(i);
                if (forced != null && forced != item) continue;
                try { if (c.acceptItem(this, item)) return true; }
                catch (Throwable ignored) {}
            }
            return false;
        }

        @Override public void handleItem(Building source, Item item) {
            if (items != null) items.add(item, 1);
        }

        @Override
        public int acceptStack(Item item, int amount, mindustry.gen.Teamc source) {
            if (items == null) return 0;
            return Math.min(amount, Math.max(0, realItemCap - items.get(item)));
        }

        @Override
        public void handleStack(Item item, int amount, mindustry.gen.Teamc source) {
            if (items != null) items.add(item, amount);
        }

        @Override
        public int removeStack(Item item, int amount) {
            if (items == null) return 0;
            int removed = Math.min(amount, items.get(item));
            items.remove(item, removed);
            return removed;
        }

        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            if (liquids == null || liquid == null) return false;
            if (liquids.get(liquid) >= realLiquidCap) return false;
            for (Building c : cells) {
                if (c == null || c.block == null) continue;
                try { if (c.block.consumesLiquid(liquid) && c.acceptLiquid(this, liquid)) return true; }
                catch (Throwable ignored) {}
            }
            return false;
        }

        @Override
        public void handleLiquid(Building source, Liquid liquid, float amount) {
            if (liquids != null) liquids.add(liquid, amount);
        }

        // ==================== 热量 ====================

        public float cellHeatReq(int i) {
            Building c = i >= 0 && i < cells.length ? cells[i] : null;
            if (c == null) return 0f;
            if (c instanceof HeatConsumer hc) return Math.max(0f, hc.heatRequirement());
            // 兼容字段型需热（有些模组方块的 heatRequirement 是 block 上的字段）
            Float req = ComboReflect.getFloat(c.block, "heatRequirement");
            return req != null ? Math.max(0f, req) : 0f;
        }

        public float heatDemand() {
            float sum = 0f;
            for (int i = 0; i < cells.length; i++) sum += cellHeatReq(i);
            return sum;
        }

        public int heatCellCount() {
            int n = 0;
            for (int i = 0; i < cells.length; i++) if (cellHeatReq(i) > 0f) n++;
            return n;
        }

        public float heatShare(int i, float pool, float demand) {
            float req = cellHeatReq(i);
            if (req <= 0f || demand <= 0f) return pool;
            return pool * req / demand;
        }

        // ==================== 绘制 ====================

        @Override
        public void draw() {
            if (proceduralPlate) drawPlate(x, y, side);
            else super.draw();
            ensureCells();

            // ★ 保存父方块绘制完时的 z（一般是 Layer.block = 30）
            //   不用 Layer.blockBuilding(40)：那是施工脚手架专用层，比普通方块高 10 层，
            //   会把格子画到裂纹/其他方块之上，还会因为 z 变化触发批次 flush，
            //   把上一格残留的颜色带进新批次 —— 就是"纯白"的根因。
            float baseZ = Draw.z();

            for (int i = 0; i < cells.length; i++) {
                Building c = cells[i];
                if (c == null) continue;
                Block cb = i < cellBlocks.length ? cellBlocks[i] : null;
                if (cb == null) continue;

                float k = cellScale(cb);
                float ox = Draw.xscl, oy = Draw.yscl;

                // ★ 每格进入前彻底重置（color→白 / mixcol→透明 / scl→1 / stroke→1）
                Draw.reset();
                Draw.z(baseZ);
                if (k != 1f) {
                    Draw.scl(k);
                    scalePartsInDrawer(cb, k);
                }

                try {
                    if (cellDrawerIncomplete(cb)) {
                        TextureRegion icon = firstFound(cb.fullIcon, cb.uiIcon, cb.region,
                                Core.atlas == null ? null : Core.atlas.find("error"));
                        if (icon != null)
                            Draw.rect(icon, c.x, c.y, c.rotation * 90f - 90f);
                    } else {
                        // 照原版 BuildingRenderer 的两个独立标志分别调用
                        if (cb.drawCached) {
                            try { c.drawCached(); } catch (Throwable ignored) {}
                        }
                        if (cb.drawDynamic) {
                            try { c.draw(); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    // ★ 每格结束后完整恢复
                    if (k != 1f) scalePartsInDrawer(cb, 1f / k);
                    Draw.reset();
                    Draw.z(baseZ);
                    Draw.scl(ox, oy);
                }
            }
            Draw.reset();
        }

        /** 把 part 的所有偏移乘以 k（画完后除回来）。同 SuperTurret 的做法。 */
        static void mulPartOffsets(mindustry.entities.part.DrawPart part, float k) {
            if (part instanceof mindustry.entities.part.RegionPart rp) {
                rp.x *= k;
                rp.y *= k;
                rp.moveX *= k;
                rp.moveY *= k;
                rp.originX *= k;
                rp.originY *= k;
                if (rp.children != null)
                    for (mindustry.entities.part.DrawPart ch : rp.children)
                        mulPartOffsets(ch, k);
            }
        }

        /** 反射从 Block 找 drawer 字段（Block 基类没这个字段，只有部分子类有）。 */
        static Object extractDrawer(Block b) {
            return b == null ? null : ComboReflect.fieldValue(b, "drawer");
        }

        /** 复用 visited 集合，避免每帧每格都 new 一个 ObjectSet。 */
        private static final arc.struct.ObjectSet<Object> scaleVisited = new arc.struct.ObjectSet<>();

        static void scalePartsInDrawer(Object drawerOrBlock, float k) {
            scaleVisited.clear();
            scalePartsInDrawer(drawerOrBlock, k, scaleVisited);
        }
        /**
         * 递归遍历 drawer 结构，把其中所有 DrawPart 的偏移乘 k。
         *
         * <p>参数可以是 Block（内部会反射取 drawer）或任意 drawer 实例。
         */
        static void scalePartsInDrawer(Object drawerOrBlock, float k, arc.struct.ObjectSet<Object> visited) {
            if (drawerOrBlock == null || k == 1f) return;

            // 传进来是 Block：先反射取 drawer 再递归
            if (drawerOrBlock instanceof Block b) {
                Object d = extractDrawer(b);
                if (d != null) scalePartsInDrawer(d, k, visited);
                return;
            }

            if (!visited.add(drawerOrBlock)) return;

            Class<?> cls = drawerOrBlock.getClass();
            while (cls != null && cls != Object.class) {
                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    try {
                        if (t == mindustry.entities.part.DrawPart[].class) {
                            f.setAccessible(true);
                            mindustry.entities.part.DrawPart[] arr =
                                    (mindustry.entities.part.DrawPart[]) f.get(drawerOrBlock);
                            if (arr != null)
                                for (mindustry.entities.part.DrawPart p : arr) mulPartOffsets(p, k);
                        } else if (arc.struct.Seq.class.isAssignableFrom(t)) {
                            f.setAccessible(true);
                            Object v = f.get(drawerOrBlock);
                            if (v instanceof arc.struct.Seq<?> s && !s.isEmpty()) {
                                Object first = s.first();
                                if (first instanceof mindustry.entities.part.DrawPart) {
                                    for (Object o : s)
                                        mulPartOffsets((mindustry.entities.part.DrawPart) o, k);
                                } else if (first instanceof mindustry.world.draw.DrawBlock) {
                                    for (Object o : s) scalePartsInDrawer(o, k, visited);
                                }
                            }
                        } else if (mindustry.world.draw.DrawBlock.class.isAssignableFrom(t)) {
                            f.setAccessible(true);
                            Object v = f.get(drawerOrBlock);
                            if (v != null) scalePartsInDrawer(v, k, visited);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                cls = cls.getSuperclass();
            }
        }

        /** drawer 的关键贴图是否加载齐（不齐就画 fullIcon，免得画出半台/空白）。 */
        public static boolean cellDrawerIncomplete(Block b) {
            if (b == null) return true;
            boolean hasRegion = b.region != null && b.region.found();
            boolean hasIcon = b.fullIcon != null && b.fullIcon.found();
            if (!hasRegion && !hasIcon) return true;
            return false;
        }

        // ==================== 面板 ====================

        @Override
        public void buildConfiguration(Table table) {
            try {
                ensureCells();
                Seq<Integer> itemCells = new Seq<>();
                for (int i = 0; i < cells.length; i++)
                    if (cells[i] != null) itemCells.add(i);
                if (itemCells.isEmpty()) {
                    table.add("[lightgray]这台里面没有装建筑").pad(6f);
                    return;
                }
                int[] sel = { itemCells.first() };
                Table ammo = new Table();
                Runnable[] rebuild = new Runnable[1];
                rebuild[0] = () -> {
                    ammo.clearChildren();
                    int idx = sel[0];
                    Seq<Item> items2 = new Seq<>();
                    if (cells[idx] != null)
                        for (Item it : content.items())
                            try { if (cells[idx].acceptItem(this, it)) items2.addUnique(it); }
                            catch (Throwable ignored) {}
                    ammo.button("自动", mindustry.ui.Styles.cleart,
                                    () -> configure((Integer) (idx | (0 << 16)))).size(96f, 40f).pad(4f)
                            .update(b -> b.setChecked(cellItemAt(idx) == null));
                    ammo.row();
                    mindustry.world.blocks.ItemSelection.buildTable(SuperBlock.this, ammo, items2,
                            () -> cellItemAt(idx),
                            item -> configure((Integer) (idx | (((item == null ? 0 : item.id + 1)) << 16))));
                };
                Table cellsRow = new Table();
                cellsRow.defaults().size(CELL_BTN_W, CELL_BTN_H).pad(1f);
                for (int row = side - 1; row >= 0; row--) {
                    for (int col = 0; col < side; col++) {
                        int idx = row * side + col;
                        boolean pickable = itemCells.contains(idx);
                        arc.scene.ui.layout.Cell<arc.scene.ui.TextButton> cell = cellsRow
                                .button(pickable ? String.valueOf(idx + 1) : "-",
                                        mindustry.ui.Styles.cleart, () -> {
                                            if (!pickable) return;
                                            sel[0] = idx;
                                            rebuild[0].run();
                                        });
                        if (pickable) cell.update(b -> b.setChecked(sel[0] == idx));
                        else cell.get().setDisabled(true);
                    }
                    cellsRow.row();
                }
                table.add("[accent]超级组合方块 " + side + "x" + side + "[]：每格可单独选填充物品").left().row();
                table.add(cellsRow).left().padTop(4f).row();
                table.add(ammo).left().row();
                rebuild[0].run();
            } catch (Throwable t) {
                Log.err("[combine] 超级方块配置面板构建失败", t);
            }
        }

        @Override
        public void display(Table table) {
            ComboUi.safe("superblock:display", () -> displayInner(table));
        }

        void displayInner(Table table) {
            table.table(cont -> {
                cont.top().left();
                cont.defaults().growX().left();
                cont.table(t -> {
                    t.left();
                    TextureRegion icon = block.getDisplayIcon(tile);
                    if (icon == null) icon = Core.atlas.find("clear");
                    t.add(new Image(icon)).size(8 * 4);
                    int count = ComboNet.displayMembers(this, group().size).size;
                    String title = count > 1 ? "[accent]超级组合方块[] x" + count + "\n" + block.getDisplayName(tile)
                            : block.getDisplayName(tile);
                    t.labelWrap(title).left().width(160f).padLeft(4);
                }).growX().left();
                cont.row();
                if (team != player.team()) return;
                cont.table(bars -> {
                    bars.defaults().growX().height(18f).pad(4);
                    if (!Mathf.zero(block.health, 0.001f)) {
                        final float h = health, mh = maxHealth;
                        bars.add(new mindustry.ui.Bar(
                                () -> Core.bundle.get("stat.health", "Health") + " " + (int) Math.max(h, 0),
                                () -> Pal.health,
                                () -> Mathf.clamp(h / mh)));
                        bars.row();
                    }
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

        public String innerSummary() {
            ensureCells();
            ObjectIntMap<Block> counts = new ObjectIntMap<>();
            for (Block b : cellBlocks) if (b != null) counts.increment(b, 1);
            StringBuilder sb = new StringBuilder();
            for (Block b : counts.keys()) {
                if (sb.length() > 0) sb.append("  ");
                sb.append(b.localizedName == null ? b.name : b.localizedName)
                        .append(" x").append(counts.get(b, 0));
            }
            return sb.toString();
        }

        public void buildComboIO(Table table) {
            table.left();
            ComboUi.addComposition(table, this, group().size);
            String inner = innerSummary();
            if (!inner.isEmpty())
                table.add("[lightgray]里面: " + inner + "[]").left()
                        .width(ComboUi.COMPOSITION_WIDTH).wrap().row();
        }
    }

    /** 供测试/调试：这台建筑现在有几格真的装了建筑。 */
    public static int loadedCells(Building b) {
        if (!(b instanceof SuperBlockBuild sb)) return 0;
        int n = 0;
        for (Building c : sb.cells) if (c != null) n++;
        return n;
    }
}