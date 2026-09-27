package combine.net;
import combine.util.ComboReflect;
import combine.util.ComboUi;
import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.core.UI;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.world.blocks.power.PowerBlock;

import static mindustry.Vars.*;

/**
 * 组合连接器：本身是导电体，向两侧相邻的任意组合体延伸共享网络。
 * 只有一整条“连续相邻”的连接器链能把两个组合体连起来时，物品/液体池才会合并。
 * 电力由原版 conductivePower 电网直接导通。
 */
public class ComboConnector extends PowerBlock {
    public TextureRegion linkRegion;

    public ComboConnector(String name){
        super(name);
        update = true;
        solid = true;
        destructible = true;
        configurable = true;
        hasPower = true;
        hasItems = false;
        hasLiquids = false;
        consumesPower = false;
        outputsPower = false;
        conductivePower = true;
        connectedPower = true;
        // 同 ComboNode：热量传送门不该有朝向（否则只有特定方位的耗热方读得到节点热）
        rotate = false;
        group = mindustry.world.meta.BlockGroup.power;
        buildType = ComboConnectorBuild::new;
        fullOverride = "power-node";

        // 共享哪些部分（物品/液体/电力/热量）：点开连接器就能勾选
        config(String.class, (ComboConnectorBuild entity, String value) -> {
            int mask = ComboShare.decode(value);
            if(mask >= 0) ComboShare.set(entity, mask);
        });
        // 蓝图/复制粘贴带过来的共享配置（哨兵见 ComboConnectorBuild.config()）
        config(Point2[].class, (ComboConnectorBuild entity, Point2[] points) -> {
            for(Point2 p : points){
                if(p.y == Integer.MIN_VALUE){
                    ComboShare.set(entity, p.x);
                    return;
                }
            }
        });
    }

    @Override
    public void load(){
        super.load();
        // 贴图用模组自带 assets/sprites/blocks/connection.png
        // （模组贴图打包时统一加 "<模组名>-" 前缀，所以 region 名是 combine-connection）
        TextureRegion custom = Core.atlas.has("combine-connection") ? Core.atlas.find("combine-connection") : null;
        // 只有模组贴图真实存在时才用 "combine-connection" 这个 region 名；
        // 否则 fullOverride 必须保持 "power-node" —— 写成不存在的名字会让建造菜单画 error 贴图（实测 v160.4 客户端复现）
        if(custom != null && custom.found()){
            linkRegion = custom;
            fullOverride = "combine-connection";
        }else{
            linkRegion = Core.atlas.find("power-node");
            fullOverride = "power-node";
        }
        // region 必须设：建造过程中的 ConstructBuild 画的是目标方块的 region
        // （Block.icons() 用 region，没设就会退回用方块名找 "combo-connector" → 找不到 → error 贴图）
        region = linkRegion;
        resetGeneratedIcons();
        if(fullIcon == null || !fullIcon.found()) fullIcon = linkRegion;
        if(uiIcon == null || !uiIcon.found()) uiIcon = linkRegion;
    }

    @Override
    public void setBars(){
        super.setBars();
        addBar("power", power -> new Bar(
            () -> Core.bundle.format("bar.powerbalance",
                (power.power.graph.getPowerBalance() >= 0 ? "+" : "")
                    + UI.formatAmount((long)(power.power.graph.getPowerBalance() * 60f))),
            () -> Pal.powerBar,
            () -> Mathf.clamp(
                power.power.graph.getLastPowerProduced()
                    / Math.max(power.power.graph.getLastPowerNeeded(), 0.001f))));
    }

    public class ComboConnectorBuild extends Building implements ComboShare.Holder, mindustry.world.blocks.heat.HeatBlock {
        public int lastLinkHash = Integer.MIN_VALUE;
        /**
         * 对外暴露的网络热量（连接器当导热管用）。
         *
         * <p>节点早就有这个字段，连接器原来没有 —— 于是"组合体 → 导热管 → 需热方"这条链
         * 只要中间经过连接器就断了（用户报的"组合节点和组合连接器不能传递热量"）。
         * 数值由 {@code ComboNet.updateHeatConnector} 每帧写成本图网络的热量总和。
         */
        public float heat = 0f;
        public float heatCap = 100f;
        /** 这张网络共享哪些部分（物品/液体/电力/热量），见 {@link ComboShare}。 */
        public int shareMask = ComboShare.ALL;
        /** 最后一次改配置的序号：同一张网络里"后改的说了算"。 */
        public int shareStamp = 1;

        @Override
        public int shareMask(){
            return shareMask;
        }

        @Override
        public void shareMask(int mask){
            shareMask = mask & ComboShare.ALL;
        }

        @Override
        public int shareStamp(){
            return shareStamp;
        }

        @Override
        public void shareStamp(int stamp){
            shareStamp = stamp;
        }

        /**
         * 电力没勾共享时，连接器不当导线：原版并网判据（consumesPower=false 的方块算导体）
         * 会把它贴着的两个组合体的电网并起来，那"各接各的电"就不成立。
         */
        @Override
        public boolean conductsTo(Building other){
            return shareBit(ComboShare.POWER) && super.conductsTo(other);
        }

        /** 点开连接器弹出来的配置面板：勾选共享哪些部分。 */
        @Override
        public void buildConfiguration(Table table){
            ComboShareUi.build(table, this);
        }

        @Override
        public void placed(){
            super.placed();
            ComboNet.markDirty();
        }

        @Override
        public void onProximityUpdate(){
            super.onProximityUpdate();
            ComboNet.markDirty();
        }

        @Override
        public void updateTile(){
          if (!combine.util.ComboTeams.playerTeam(team)) { super.updateTile(); return; }   // 只玩家组合开关：AI 敌人的建筑按原版跑，不参与组合那套
            super.updateTile();
            int hash = linkHash();
            if(hash != lastLinkHash){
                lastLinkHash = hash;
                ComboNet.markDirty();
            }
            if(enabled) ComboNet.updateHeatConnector(this);
        }

        @Override
        public float heat(){
            return heat;
        }

        @Override
        public float heatFrac(){
            return Mathf.clamp(heat / Math.max(heatCap, 0.001f));
        }

        private int linkHash(){
            int h = 17;
            for(Building other : proximity){
                if(other == null || !other.isValid()) continue;
                if(other instanceof ComboConnectorBuild || ComboReflect.isComboBuild(other)){
                    h = h * 31 + other.pos();
                }
            }
            return h;
        }

        @Override
        public void onRemoved(){
            // 连接器是组合体之间唯一的"导线"：拆掉它等于给电网换拓扑，
            // 相邻的组合体会被原版的拆网扇形漏在旧图上 —— 交给对账器重划
            if(proximity != null){
                for(Building nb : proximity) combine.util.ComboPower.mark(nb);
            }
            ComboNet.markDirty();
            super.onRemoved();
        }

        @Override
        public void draw(){
            TextureRegion r = ((ComboConnector)block).linkRegion;
            if(r != null && r.found()){
                Draw.rect(r, x, y);
            }else{
                super.draw();
            }
        }

        @Override
        public void display(Table table) {
          // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
          ComboUi.safe("comboconnector:display", () -> displayInner(table));
        }

        void displayInner(Table table) {
            super.display(table);
            table.row();
            table.add("[accent]组合连接器[]").left();
            table.row();
            table.add("相邻组合体通过连续连接器共享: " + ComboShare.describe(shareMask)).color(Pal.accent).left();

            Seq<Building> members = ComboNet.componentMembers(this);
            if(!members.isEmpty()){
                table.row();
                table.add("[accent]连接组合 x" + members.size + "[]").left();

                Building itemPool = null, liquidPool = null;
                for(Building m : members){
                    if(m.items != null && (itemPool == null || m.pos() < itemPool.pos())) itemPool = m;
                    if(m.liquids != null && (liquidPool == null || m.pos() < liquidPool.pos())) liquidPool = m;
                }

                if(itemPool != null){
                    for(Item item : content.items()){
                        int amount = itemPool.items.get(item);
                        if(amount > 0){
                            final int a = amount;
                            table.row();
                            table.add(item.localizedName + ": " + a).color(item.color).left();
                        }
                    }
                }
                if(liquidPool != null){
                    for(Liquid liquid : content.liquids()){
                        float amount = liquidPool.liquids.get(liquid);
                        if(amount > 0.001f){
                            table.row();
                            table.add(liquid.localizedName + ": " + Strings.fixed(amount, 1))
                                .color(liquid.color).left();
                        }
                    }
                }
            }else{
                table.row();
                table.add("未连接任何组合体").color(Pal.accent).left();
            }
                }

        @Override
        public byte version(){
            return 2;
        }

        @Override
        public void write(Writes write){
            super.write(write);
            write.b(shareMask);
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);
            // v2 起存"共享哪些部分"；旧存档按全共享（老行为）
            shareMask = revision >= 2 ? (read.b() & ComboShare.ALL) : ComboShare.ALL;
            shareStamp = 1;
        }

        /** 蓝图/复制粘贴要带上共享配置（哨兵项，见 ComboNode 的同款写法）。 */
        @Override
        public Object config(){
            return new Point2[]{ new Point2(shareMask, Integer.MIN_VALUE) };
        }
    }
}
