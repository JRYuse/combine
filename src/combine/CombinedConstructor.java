package combine;

import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.payloads.Constructor;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.*;

/**
 * 组合Constructor：相邻自动组合(允许跨类型), 共享物品/液体/电力池。
 * 机制经 IUnitCombo 接口与工厂/重构厂完全统一, 完工扣料有实时复核。
 */
public class CombinedConstructor extends Constructor implements IUnitCombo.IUnitComboBlock {
    /** 是否允许跨方块类型成组 */
    public boolean allowCrossTypeCombo = true;

    public CombinedConstructor(String name) {
        super(name);
        update = true;
        sync = true;
        buildType = CombinedConstructorBuild::new;
    }

    @Override
    public boolean allowCrossTypeCombo() {
        return allowCrossTypeCombo;
    }

    public class CombinedConstructorBuild extends Constructor.ConstructorBuild implements IUnitCombo {
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
        public void updateTile() {
            comboPreUpdate();

            // FIX[白嫖]: 本 tick 将跨完工线而池子付不起时, 冻结效率停在完工线下等料。
            // (Constructor 未覆写 updateTile, super 即 BlockProducerBuild 全文, 无法复刻插入,
            //  用效率冻结拦截; efficiency 每帧开头由 updateConsumption 重算, 现场改现场还原安全)
            mindustry.world.Block recipe = this.recipe();
            float savedEff = this.efficiency;
            if (recipe != null && this.payload == null
                    && this.progress + ((Constructor) this.block).buildSpeed * this.edelta() >= recipe.buildTime
                    && !canAffordNow()) {
                this.efficiency = 0f;
            }
            super.updateTile();
            this.efficiency = savedEff;
        }


        @Override
        public void onRemoved() {
            comboOnRemoved();
            super.onRemoved();
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
            for (IUnitCombo member : group()) {
                if (((Building) member).isValid())
                    member.gDirty(true);
            }
        }

        // 组合池入料：放大为全组总容量, 按组内成员配方过滤
        @Override
        public boolean acceptItem(Building source, Item item) {
            if (!block.hasItems)
                return false;
            boolean needed = false;
            for (IUnitCombo member : group()) {
                if (((Building) member).isValid()
                        && ((Constructor.ConstructorBuild) member).recipe() != null) {
                    for (ItemStack stack : ((Constructor.ConstructorBuild) member).recipe().requirements) {
                        if (stack.item == item) {
                            needed = true;
                            break;
                        }
                    }
                }
                if (needed)
                    break;
            }
            return needed && items.get(item) < getMaximumAccepted(item);
        }

        @Override
        public int getMaximumAccepted(Item item) {
            return Math.max(comboTotalItemCap, 1);
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
            return (byte) (super.version() + 1);
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
            if (revision >= super.version() + 1) {
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
