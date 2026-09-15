package combine;

import arc.Core;
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
import mindustry.content.Fx;
import mindustry.entities.Effect;
import mindustry.game.EventType;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.blocks.units.Reconstructor;
import mindustry.type.ItemStack;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItemDynamic;
import mindustry.world.consumers.ConsumeItems;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;
import mindustry.world.modules.PowerModule;

import static mindustry.Vars.*;

/**
 * 组合重构工厂 —— 相邻同型自动组合，共享物品/液体/电力池
 * （总物品容量 = Σ 单台容量, 总液体容量 = Σ 单台容量）。
 * 机制与 CombinedCrafter/CombinedPump 完全一致：
 * proximity 泛洪成组, shareModules 全额并入组长模块,
 * splitAssets 按容量比例拆分, pendingLeaderPos 存档恢复。
 */
public class CombinedReconstructor extends Reconstructor implements IUnitCombo.IUnitComboBlock {
    /** 是否允许跨方块类型成组（true = 重构厂可与工厂互通成组） */
    public boolean allowCrossTypeCombo = true;

    @Override
    public boolean allowCrossTypeCombo() {
        return allowCrossTypeCombo;
    }

    public CombinedReconstructor(String name) {
        super(name);
        update = true;
        sync = true;
        buildType = CombinedReconstructorBuild::new;
    }

    public class CombinedReconstructorBuild extends ReconstructorBuild implements IUnitCombo {
        // FIX[进度不走]: ReconstructorBuild.constructing 是包级私有字段,
        // 原版 updateTile 每帧用 constructing() 方法回写它, shouldConsume() 依赖它;
        // 跨包无法直接赋值, 用反射回写(保住原版施工动画), 并覆写 shouldConsume() 兜底
        static final java.lang.reflect.Field constructingField;
        static {
            java.lang.reflect.Field f = null;
            try {
                f = ReconstructorBuild.class.getDeclaredField("constructing");
                f.setAccessible(true);
            } catch (Throwable ignored) {
            }
            constructingField = f;
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




        @Override
        public boolean shouldConsume() {
            // FIX[进度不走]: 原版依赖包级私有的 constructing 字段, 跨包可能没被回写;
            // 这里用实时计算兜底, 语义与原版一致
            return payload != null && hasUpgrade(payload.unit.type)
                    && enabled && team.activateUnitFactories();
        }

        /**
         * 完工料费实时复核：不依赖 potentialEfficiency 缓存(同 tick 内可能滞后),
         * 直接按 ConsumeItemDynamic 将要扣除的量盘点共享池。
         */


        // 组合池入料：原版 Reconstructor 未覆写 acceptItem, 基类按单座 itemCapacity
        // 验收; 池化后放大为全组总容量, 并照 crafter 加"组内有人需要才收"过滤
        @Override
        public boolean acceptItem(Building source, Item item) {
            if (!block.hasItems)
                return false;
            boolean needed = false;
            for (IUnitCombo member : group()) {
                if (((Building) member).isValid() && ((Building) member).block.consumesItem(item)) {
                    needed = true;
                    break;
                }
            }
            return needed && items.get(item) < getMaximumAccepted(item);
        }

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

            // 回写 constructing 字段(原版 updateTile 里的 this.constructing = this.constructing())
            if (constructingField != null) {
                try {
                    constructingField.setBoolean(this,
                            payload != null && hasUpgrade(payload.unit.type));
                } catch (Throwable ignored) {
                }
            }


            // ---- 以下照抄原版 ReconstructorBuild.updateTile, 仅完工分支加料费复核 ----
            // NOTE: constructing 字段在 ReconstructorBuild 中包级私有, 跨包无法赋值,
            // 损失仅施工动画的 constructing 遮罩, 不影响升级逻辑
            boolean valid = false;
            if (this.payload != null) {
                if (!this.hasUpgrade((this.payload).unit.type)) {
                    this.moveOutPayload();
                } else if (this.moveInPayload()) {
                    if (this.efficiency > 0.0F) {
                        valid = true;
                        this.progress += this.edelta() * Vars.state.rules.unitBuildSpeed(this.team);
                    }
                    if (this.progress >= ((Reconstructor) this.block).constructTime) {
                        if (!canAffordNow()) {
                            // FIX[白嫖]: 料不够就停在完工线等料, 不产出。
                            // 必须实时盘点(canAffordNow), canConsume() 的 potentialEfficiency
                            // 是本帧开头缓存的, 同 tick 内会误判"付得起"
                            this.progress = ((Reconstructor) this.block).constructTime;
                        } else {
                            (this.payload).unit = this.upgrade((this.payload).unit.type)
                                    .create((this.payload).unit.team());
                            if ((this.payload).unit.isCommandable()) {
                                if (this.commandPos != null) {
                                    (this.payload).unit.command().commandPosition(this.commandPos);
                                }
                                (this.payload).unit.command().command(
                                        this.command == null
                                                && (this.payload).unit.type.defaultCommand != null
                                                        ? (this.payload).unit.type.defaultCommand
                                                        : this.command);
                            }
                            ((Reconstructor) this.block).createSound.at(this, 1.0F + Mathf.range(0.06F),
                                    ((Reconstructor) this.block).createSoundVolume);
                            this.progress %= 1.0F;
                            Effect.shake(2.0F, 3.0F, this);
                            Fx.producesmoke.at(this);
                            this.consume();
                            Events.fire(new EventType.UnitCreateEvent((this.payload).unit, this));
                        }
                    }
                }
            }
            this.speedScl = Mathf.lerpDelta(this.speedScl, (float) Mathf.num(valid), 0.05F);
            this.time += this.edelta() * this.speedScl * Vars.state.rules.unitBuildSpeed(this.team);
        }

        @Override
        public void onRemoved() {
            comboOnRemoved();
            super.onRemoved();
        }

        // -------------------- 显示 --------------------
        @Override
        public void display(Table table) {
            super.display(table);
            int count = group().size;
            if (count <= 1)
                return;
            table.row();
            table.add("[accent]组合 x" + count + "[] " + block.localizedName).left();
            table.row();
            Table bars = new Table();
            bars.left();
            bars.update(() -> {
                bars.clearChildren();
                bars.defaults().growX().height(18f).pad(4);
                IUnitCombo l = leader();
                if (((Building) l).items != null) {
                    for (Item item : content.items()) {
                        int total = ((Building) l).items.get(item);
                        if (total > 0) {
                            final int t = total;
                            final int cap = Math.max(l.gItemCap(), 1);
                            bars.add(new Bar(
                                    () -> item.localizedName + ": " + t + "/" + cap,
                                    () -> item.color,
                                    () -> (float) t / cap));
                            bars.row();
                        }
                    }
                }
                if (((Building) l).liquids != null) {
                    for (Liquid liquid : content.liquids()) {
                        float total = ((Building) l).liquids.get(liquid);
                        if (total > 0.001f) {
                            final float t = total;
                            final float cap = Math.max(l.gLiquidCap(), 1f);
                            bars.add(new Bar(
                                    () -> liquid.localizedName + ": "
                                            + Strings.fixed(t, 1) + "/" + Strings.fixed(cap, 1),
                                    () -> liquid.barColor != null ? liquid.barColor : liquid.color,
                                    () -> t / cap));
                            bars.row();
                        }
                    }
                }
            });
            table.add(bars).growX().left();
        }

        // -------------------- 序列化 --------------------
        /**
         * FIX[序列化翻倍]: 存档先调 writeBase 写模块数据、后调 write——
         * 在 write() 里换空模块(照抄 pump/crafter 的 trick)根本来不及,
         * 每个成员都会把整池写一遍, 读档全额并入后数量 ×N。
         * 正确挂点是 writeBase: 写模块前就把非组长的 items/liquids 换成空模块。
         */
        @Override
        public void writeBase(Writes write) {
            if (!isLeader()) {
                ItemModule savedI = items;
                LiquidModule savedL = liquids;
                if (savedI != null)
                    items = new ItemModule();
                if (savedL != null)
                    liquids = new LiquidModule();
                super.writeBase(write);
                items = savedI;
                liquids = savedL;
                return;
            }
            super.writeBase(write);
        }

        @Override
        public byte version() {
            return 4;
        }

        @Override
        public void write(Writes write) {
            IUnitCombo trueLeader = this;
            if (comboGroup != null && comboGroup.size > 0)
                for (IUnitCombo b : comboGroup)
                    if (b != null && ((Building) b).isValid()
                            && ((Building) b).pos() < ((Building) trueLeader).pos())
                        trueLeader = b;
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
                write.i(((Building) comboLeader).pos());
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            if (revision >= 4) {
                boolean hasLeader = read.bool();
                if (hasLeader) {
                    pendingLeaderPos = read.i();
                    // FIX[序列化翻倍·旧档修复]: 旧档里每个成员都写了整池,
                    // 组长的模块才是池子, 自己这份是重复副本, 必须清掉,
                    // 否则 shareModules 全额并入后数量 ×N
                    items = new ItemModule();
                    liquids = new LiquidModule();
                }
            }
            comboDirty = true;
        }
    }
}
