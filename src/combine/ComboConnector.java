package combine;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
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
        hasPower = true;
        hasItems = false;
        hasLiquids = false;
        consumesPower = false;
        outputsPower = false;
        conductivePower = true;
        connectedPower = true;
        group = mindustry.world.meta.BlockGroup.power;
        buildType = ComboConnectorBuild::new;
        fullOverride = "power-node";
    }

    @Override
    public void load(){
        super.load();
        // 贴图用模组自带 assets/sprites/blocks/connection.png
        // （模组贴图打包时统一加 "<模组名>-" 前缀，所以 region 名是 combine-connection）
        TextureRegion custom = Core.atlas.has("combine-connection") ? Core.atlas.find("combine-connection") : null;
        linkRegion = custom != null ? custom : Core.atlas.find("power-node");
        // region 必须设：建造过程中的 ConstructBuild 画的是目标方块的 region
        // （Block.icons() 用 region，没设就会退回用方块名找 "combo-connector" → 找不到 → error 贴图）
        region = linkRegion;
        resetGeneratedIcons();
        if(linkRegion != null && linkRegion.found()) fullOverride = "combine-connection"; // 图标也用新贴图
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

    public class ComboConnectorBuild extends Building {
        public int lastLinkHash = Integer.MIN_VALUE;

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
            super.updateTile();
            int hash = linkHash();
            if(hash != lastLinkHash){
                lastLinkHash = hash;
                ComboNet.markDirty();
            }
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
            // 面板只在切换选中目标时重建一次 → 内容放进 update 里每帧重画（物品数量才是活的）
            ComboUi.live(table, "comboconnector:info", this::buildPanel);
        }

        /** 面板文字都是"活"函数：每次调用取当前值（面板只搭一次，数字必须自己刷新）。 */
        public String membersText(){
            return "连接组合 x" + ComboNet.componentMembers(this).size;
        }

        public String itemText(Building pool, Item item){
            return item.localizedName + ": " + (pool == null || pool.items == null ? 0 : pool.items.get(item));
        }

        public String liquidText(Building pool, Liquid liquid){
            return liquid.localizedName + ": "
                + Strings.fixed(pool == null || pool.liquids == null ? 0f : pool.liquids.get(liquid), 1);
        }

        /** 连接器面板内容（活数据）。 */
        public void buildPanel(Table table) {
            table.add("[accent]组合连接器[]").left();
            table.row();
            table.add("相邻组合体通过连续连接器共享物品/液体/电力").color(Pal.accent).left();

            Seq<Building> members = ComboNet.componentMembers(this);
            if(!members.isEmpty()){
                table.row();
                table.add("[accent]" + membersText() + "[]").left();

                Building itemPool = null, liquidPool = null;
                for(Building m : members){
                    if(m.items != null && (itemPool == null || m.pos() < itemPool.pos())) itemPool = m;
                    if(m.liquids != null && (liquidPool == null || m.pos() < liquidPool.pos())) liquidPool = m;
                }

                if(itemPool != null){
                    final Building pool = itemPool;
                    for(Item item : content.items()){
                        if(pool.items.get(item) > 0){
                            table.row();
                            table.add(itemText(pool, item)).color(item.color).left();
                        }
                    }
                }
                if(liquidPool != null){
                    final Building pool = liquidPool;
                    for(Liquid liquid : content.liquids()){
                        if(pool.liquids.get(liquid) > 0.001f){
                            table.row();
                            table.add(liquidText(pool, liquid)).color(liquid.color).left();
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
            return 1;
        }

        @Override
        public void write(Writes write){
            super.write(write);
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);
        }
    }
}
