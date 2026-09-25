package combine.units;
import combine.net.ComboNet;
import combine.production.CombinedCrafter;
import combine.production.CombinedDrill;
import combine.production.CombinedPump;
import combine.util.ComboReflect;
import combine.util.UnitComboBridge;
import combine.util.ComboUi;
import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.scene.ui.Image;
import arc.struct.ObjectIntMap;
import mindustry.ui.ReqImage;
import arc.Events;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.struct.IntSet;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.game.EventType;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.type.UnitType;
import mindustry.gen.Iconc;
import mindustry.ui.Fonts;
import mindustry.entities.Units;
import mindustry.world.Block;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.type.ItemStack;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItemDynamic;
import mindustry.world.consumers.ConsumeItems;
import mindustry.world.modules.ItemModule;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.world.modules.LiquidModule;
import mindustry.world.modules.PowerModule;

import static mindustry.Vars.*;

/**
 * 组合单位工厂 —— 相邻同型自动组合，共享物品/液体/电力池
 * （总物品容量 = Σ 单台容量, 总液体容量 = Σ 单台容量）。
 * 机制与 CombinedCrafter/CombinedPump 完全一致：
 * proximity 泛洪成组, shareModules 全额并入组长模块,
 * splitAssets 按容量比例拆分, pendingLeaderPos 存档恢复。
 */
public class CombinedUnitFactory extends UnitFactory implements IUnitCombo.IUnitComboBlock {
    /** 是否允许跨方块类型成组（true = 工厂可与重构厂互通成组） */
    public boolean allowCrossTypeCombo = true;

    @Override
    public boolean allowCrossTypeCombo() {
        return allowCrossTypeCombo;
    }

    public float baseLiquidCapacity = -1f;

    @Override
    public void init() {
        super.init();
        // FIX[液体输满]: 记录真实单块容量后抬高假容量，防止原版 moveLiquid 按单块容量截断流入
        // FIX[液体容量显示]: 用 -1 作哨兵；只在尚未抬高假容量时记录真实值（兼容重复 init），
        // 无液体的克隆块不抬高，避免 baseLiquidCap 回退读出 9999 污染池上限
        if (liquidCapacity > 0.001f && liquidCapacity != 9999f) {
            baseLiquidCapacity = liquidCapacity;
            liquidCapacity = 9999f;
        }
    }

    @Override
    public void setStats() {
        super.setStats();
        if (hasLiquids && baseLiquidCapacity > 0.001f) { // 信息显示真实容量而非假容量 9999
            stats.remove(Stat.liquidCapacity);
            stats.add(Stat.liquidCapacity, baseLiquidCapacity, StatUnit.liquidUnits);
        }
    }

    public CombinedUnitFactory(String name) {
        super(name);
        update = true;
        sync = true;
        buildType = CombinedUnitFactoryBuild::new;
    }

    public class CombinedUnitFactoryBuild extends UnitFactoryBuild implements IUnitCombo, combine.saves.ComboSaved {

        @Override
        public boolean shouldAmbientSound() {
            // FIX[声音线程崩溃]: MindustryX 的环境音在独立 AudioThread（20fps）上调用
            // shouldAmbientSound()，原版默认实现转调 shouldConsume()；
            // 组合建筑这一路会读库存/遍历共享序列，跨线程相撞就是 NoSuchElementException。
            // 组合建筑统一禁用环境音循环（与 CombinedCrafter/CombinedDrill 等一致）。
            return false;
        }

        @Override
        public boolean acceptItem(Building source, Item item) {
            if (!block.hasItems)
                return false;
            // FIX[跨类输入]: consumesItem 认不出 ConsumeItemDynamic（单位工厂），用 groupNeedsItem
            return ComboReflect.groupNeedsItem(this, item)
                    && items.get(item) < getMaximumAccepted(item);
        }

        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            if (!block.hasLiquids)
                return false;
            boolean needed = ComboReflect.groupConsumesLiquid(this, liquid);
            return needed && liquids != null && liquids.get(liquid) < comboTotalLiquidCap - 0.001f;
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

        @Override
        public void dumpLiquid(Liquid liquid, float scaling, int outputDir) {
            float oldCap = block.liquidCapacity;
            block.liquidCapacity = Math.max(comboTotalLiquidCap, 1f);
            super.dumpLiquid(liquid, scaling, outputDir);
            block.liquidCapacity = oldCap;
        }

        public IUnitCombo comboLeader;
        public Seq<IUnitCombo> comboGroup = new Seq<>();
        public boolean comboDirty = true;
        public int comboTotalItemCap = 0;
        public float comboTotalLiquidCap = 0f;
        public int pendingLeaderPos = -1;

        @Override
        public IUnitCombo gLeader() {
            return comboLeader;
        }

        @Override
        public void gLeader(IUnitCombo v) {
            comboLeader = v;
        }

        @Override
        public Seq<IUnitCombo> gGroup() {
            return comboGroup;
        }

        @Override
        public void gGroup(Seq<IUnitCombo> v) {
            comboGroup = v;
        }

        @Override
        public boolean gDirty() {
            return comboDirty;
        }

        @Override
        public void gDirty(boolean v) {
            comboDirty = v;
        }

        @Override
        public int gItemCap() {
            return comboTotalItemCap;
        }

        @Override
        public void gItemCap(int v) {
            comboTotalItemCap = v;
        }

        @Override
        public float gLiquidCap() {
            return comboTotalLiquidCap;
        }

        @Override
        public void gLiquidCap(float v) {
            comboTotalLiquidCap = v;
        }

        @Override
        public int gPending() {
            return pendingLeaderPos;
        }

        @Override
        public void gPending(int v) {
            pendingLeaderPos = v;
        }




        /**
         * 完工料费实时复核：不依赖 potentialEfficiency 缓存(同 tick 内可能滞后),
         * 直接按 ConsumeItemDynamic 将要扣除的量盘点共享池。
         */


        // 组合池入料上限：原版返回单个配方需求量(capacities[item]×unitCost),
        // 全组共享池却只按单座上限验收, 导致整组只能缓冲一批料。
        // 放大为全组总容量(Σ 单座 itemCapacity), acceptItem 原版逻辑自动生效。
        @Override
        public int getMaximumAccepted(Item item) {
            return Math.max(comboTotalItemCap, 1);
        }

        // -------------------- 组合重建 --------------------



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
            for (IUnitCombo member : group()) {
                if (((Building) member).isValid())
                    member.gDirty(true);
            }
        }

        @Override
        public void updateTile() {
            comboPreUpdate();


            // ---- 以下照抄原版 UnitFactoryBuild.updateTile, 仅完工分支加料费复核 ----
            if (!((UnitFactory) this.block).configurable) {
                this.currentPlan = 0;
            }
            if (this.currentPlan < 0 || this.currentPlan >= ((UnitFactory) this.block).plans.size) {
                this.currentPlan = -1;
            }
            if (this.efficiency > 0.0F && this.currentPlan != -1) {
                this.time += this.edelta() * this.speedScl * Vars.state.rules.unitBuildSpeed(this.team);
                this.progress += this.edelta() * Vars.state.rules.unitBuildSpeed(this.team);
                this.speedScl = Mathf.lerpDelta(this.speedScl, 1.0F, 0.05F);
            } else {
                this.speedScl = Mathf.lerpDelta(this.speedScl, 0.0F, 0.05F);
            }
            this.moveOutPayload();
            if (this.currentPlan != -1 && this.payload == null) {
                UnitPlan plan = ((UnitFactory) this.block).plans.get(this.currentPlan);
                if (plan.unit.isBanned()) {
                    this.currentPlan = -1;
                    return;
                }
                if (this.progress >= plan.time) {
                    if (!canAffordNow()) {
                        // FIX[白嫖]: 完工瞬间池子已被其他成员扣光时, 停在完工线等料, 不产出。
                        // 必须实时盘点(canAffordNow), canConsume() 的 potentialEfficiency
                        // 是本帧开头缓存的, 同 tick 内会误判"付得起"
                        this.progress = plan.time;
                    } else {
                        this.progress %= 1.0F;
                        Unit unit = plan.unit.create(this.team);
                        // 组合单位工厂产出的单位继承本建筑组合体的组标记（共享承伤/火力）。
                        // 单位侧机制在 combineunit 里，这里走反射桥（没装就静默跳过）。
                        UnitComboBridge.tagProduced(unit, this);
                        if (unit.isCommandable()) {
                            if (this.commandPos != null) {
                                unit.command().commandPosition(this.commandPos);
                            }
                            unit.command().command(this.command == null && unit.type.defaultCommand != null
                                    ? unit.type.defaultCommand : this.command);
                        }
                        ((UnitFactory) this.block).createSound.at(this, 1.0F + Mathf.range(0.06F),
                                ((UnitFactory) this.block).createSoundVolume);
                        this.payload = new UnitPayload(unit);
                        this.payVector.setZero();
                        this.consume();
                        Events.fire(new EventType.UnitCreateEvent((this.payload).unit, this));
                    }
                }
                this.progress = Mathf.clamp(this.progress, 0.0F, plan.time);
            } else {
                this.progress = 0.0F;
            }
        }

        @Override
        public void onRemoved() {
            comboOnRemoved();
            super.onRemoved();
        }

        // -------------------- 显示 --------------------
        @Override
        public void display(Table table) {
          // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
          ComboUi.safe("combinedunitfactory:display", () -> displayInner(table));
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
                    String title = count > 1
                            ? "[accent]组合单位工厂[] x" + count + "\n" + block.getDisplayName(tile)
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
            if (!Mathf.zero(block.health, 0.001f)) {
                final float h = health, mh = maxHealth;
                table.add(new Bar(
                        () -> Core.bundle.get("stat.health", "Health") + " " + (int) Math.max(h, 0),
                        () -> Pal.health,
                        () -> Mathf.clamp(h / mh)));
                table.row();
            }
            // 原版注册的 bar 都在这儿画：进度条 + 「单位数量/上限」（bar.unitcap）。
            // 以前只画了我们自己那条进度，把原版这两条全丢了，所以看不到单位数量那条。
            displayBars(table);
            float totalPower = 0f;
            for (IUnitCombo member : group()) {
                Building mb = (Building) member;
                if (mb.isValid() && mb.block.consPower != null)
                    totalPower += mb.block.consPower.usage;
            }
            if (totalPower > 0 && power != null) {
                final float tp = totalPower;
                table.add(new Bar(
                        () -> "电力 " + Strings.fixed(tp * power.status * 60f, 1) + " ⚡/s",
                        () -> Pal.power,
                        () -> power.status));
                table.row();
            }
            Building l = (Building) leader();
            ItemModule sharedItems = l != null && l.items != null ? l.items : items;
            if (sharedItems != null) {
                final int cap = ComboNet.effectiveItemCap(this);
                for (Item item : content.items()) {
                    int total = sharedItems.get(item);
                    if (total > 0) {
                        final int t = total;
                        table.add(new Bar(
                                () -> item.localizedName + ": " + t + "/" + cap,
                                () -> item.color,
                                () -> (float) t / cap));
                        table.row();
                    }
                }
            }
            LiquidModule sharedLiq = l != null && l.liquids != null ? l.liquids : liquids;
            if (sharedLiq != null) {
                final float cap = ComboNet.effectiveLiquidCap(this);
                for (Liquid liquid : content.liquids()) {
                    float total = sharedLiq.get(liquid);
                    if (total > 0.001f) {
                        final float t = total;
                        table.add(new Bar(
                                () -> liquid.localizedName + ": " + Strings.fixed(t, 1) + "/" + Strings.fixed(cap, 1),
                                () -> liquid.barColor != null ? liquid.barColor : liquid.color,
                                () -> t / cap));
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
            table.add("[lightgray]本机: " + block.localizedName + "[]").left();
            table.row();
            boolean hasLocalInput = false;
            UnitFactory fac = (UnitFactory) this.block;
            if (currentPlan >= 0 && currentPlan < fac.plans.size) {
                UnitPlan plan = fac.plans.get(currentPlan);
                for (ItemStack stack : plan.requirements) {
                    if (!hasLocalInput) {
                        table.add("[gray]输入:").left();
                        table.row();
                        hasLocalInput = true;
                    }
                    boolean has = items != null && items.get(stack.item) >= stack.amount;
                    table.table(row -> {
                        row.left();
                        if (stack.item.uiIcon != null)
                            row.add(new ReqImage(stack.item.uiIcon, () -> has)).size(iconMed).padRight(4f);
                        row.add(stack.item.localizedName + " x" + stack.amount)
                                .color(has ? Color.white : Color.scarlet).left();
                    }).left();
                    table.row();
                }
            }
            if (!hasLocalInput) {
                table.add("[darkGray]无输入").left();
                table.row();
            }
        }

        // -------------------- 序列化 --------------------
        // 地图区里只写"原版单位工厂那一份字节"（super.write 就是 UnitFactoryBuild 自己的布局），
        // 模组自己的字段（组长）挪到自定义存档块 ComboSaveState —— 详见 ComboSaved。
        @Override
        public byte version() {
            return combine.saves.ComboSaveState.vanillaVersion(block);
        }

        @Override
        public void write(Writes write) {
            super.write(write);
        }

        // 存档先调 writeBase 写模块数据、后调 write —— "只有组长写真实模块"必须挂在 writeBase 上
        // （写在 write() 里来不及），否则每个成员各写一份整池，读档合并后数量 ×N。
        @Override
        public void writeBase(Writes write) {
            ItemModule savedItems = items;
            LiquidModule savedLiquids = liquids;
            if (trueLeader() != this) {
                if (items != null)
                    items = new ItemModule();
                if (liquids != null)
                    liquids = new LiquidModule();
            }
            super.writeBase(write);
            items = savedItems;
            liquids = savedLiquids;
        }

        // 按 pos 最小的那个当组长，和 rebuildCombo / 旧 write() 的规则一致。
        Building trueLeader() {
            Building leader = this;
            if (comboGroup != null) {
                for (IUnitCombo b : comboGroup) {
                    if (b != null && ((Building) b).isValid() && ((Building) b).pos() < leader.pos())
                        leader = (Building) b;
                }
            }
            return leader;
        }

        @Override
        public void writeCombo(Writes write) {
            Building leader = trueLeader();
            write.bool(leader != this);
            if (leader != this)
                write.i(leader.pos());
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            if (revision >= 4) {
                applyLeader(read, true); // 旧档（≤2.6）：模组字段直接写在地图区里
                return;
            }
            comboDirty = true;
        }

        @Override
        public void readCombo(Reads read, byte revision) {
            // 新格式里"非组长"在存档里写的就是空模块，读档时整组已经被并成一份，
            // 这里不能再清空（清了就把并好的池子丢掉）。
            applyLeader(read, false);
        }

        void applyLeader(Reads read, boolean clearModules) {
            boolean hasLeader = read.bool();
            int leaderPos = hasLeader ? read.i() : -1;
            if (hasLeader && leaderPos != pos()) {
                pendingLeaderPos = leaderPos;
                if (clearModules && items != null)
                    items = new ItemModule();
                if (clearModules && liquids != null)
                    liquids = new LiquidModule();
            } else {
                pendingLeaderPos = -1;
                comboLeader = null;
            }
            comboDirty = true;
        }
    }
}
