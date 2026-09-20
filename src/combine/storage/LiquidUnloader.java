package combine.storage;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.scene.ui.layout.Table;
import arc.util.Eachable;
import arc.util.io.*;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.blocks.ItemSelection;
import mindustry.world.blocks.liquid.LiquidBlock.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

public class LiquidUnloader extends Block {
    public String center;
    public float speed = 3f;

    public LiquidUnloader(String name) {
        super(name);

        update = true;
        solid = true;
        hasLiquids = true;
        liquidCapacity = 10f;
        configurable = true;
        outputsLiquid = true;
        saveConfig = true;
        noUpdateDisabled = true;
        displayFlow = false;
        group = BlockGroup.liquids;
        envEnabled = Env.any;
        clearOnDoubleTap = true;

        config(Liquid.class, (LiquidUnloaderBuild tile, Liquid l) -> tile.sortLiquid = l);
        configClear((LiquidUnloaderBuild tile) -> tile.sortLiquid = null);
    }

    @Override
    public void load() {
        super.load();
        // 模组贴图打包时统一加 "<模组名>-" 前缀（见 Mods.packSprites），所以 region / 图标 /
        // center 覆盖层都得用带前缀的 region 名；否则 Block.load() 里 Core.atlas.find(name)
        // 找不到，建造菜单与方块本体会画 error 贴图（实测 v160.4/X37 客户端）。
        if (Core.atlas != null) {
            TextureRegion custom = Core.atlas.has("combine-" + name) ? Core.atlas.find("combine-" + name) : null;
            if (custom != null && custom.found()) {
                region = custom;
                fullOverride = "combine-" + name;
                if (fullIcon == null || !fullIcon.found())
                    fullIcon = custom;
                if (uiIcon == null || !uiIcon.found())
                    uiIcon = custom;
            }
            // 贴图文件叫 liquid-unloader-centre.png（英式拼写）——两种拼写/前缀都试一遍，
            // 免得 draw() 里 Core.atlas.find(center) 又退回 error 贴图
            String c = "combine-" + name + "-centre";
            if (!Core.atlas.has(c)) c = name + "-centre";
            if (!Core.atlas.has(c)) c = "combine-" + name + "-center";
            if (!Core.atlas.has(c)) c = name + "-center";
            center = c;
        } else {
            center = name + "-centre";
        }
    }

    @Override
    public void setBars() {
        super.setBars();

        removeBar("liquid");
    }

    @Override
    public void drawPlanConfig(BuildPlan plan, Eachable<BuildPlan> list) {
        drawPlanConfigCenter(plan, plan.config, center, true);
    }

    public class LiquidUnloaderBuild extends Building {
        public Liquid sortLiquid = null;
        public Liquid lastSort = null;
        public Building dumpingTo = null;
        public float offset = 0f;

        @Override
        public void updateTile() {
            if (lastSort != sortLiquid) {
                liquids.clear();
                lastSort = sortLiquid;
            }
            for (int i = 0; i < proximity.size; i++) {
                int pos = (int) (offset + i) % proximity.size;
                Building other = proximity.get(pos);

                if (other.interactable(team) && other.block.hasLiquids
                        && !(other instanceof LiquidBuild && other.block.size == 1) && sortLiquid != null
                        && other.liquids.get(sortLiquid) > 0) {
                    dumpingTo = other;
                    if (liquids.get(sortLiquid) < block.liquidCapacity) {
                        float amount = Math.min(speed, other.liquids.get(sortLiquid));
                        liquids.add(sortLiquid, amount);
                        other.liquids.remove(sortLiquid, amount);
                    }
                }
            }
            if (proximity.size > 0) {
                offset++;
                offset %= proximity.size;
            }
            this.dumpLiquid(liquids.current());
        }

        @Override
        public boolean canDumpLiquid(Building to, Liquid liquid) {
            return to != dumpingTo;
        }

        @Override
        public void draw() {
            super.draw();
            Draw.color(sortLiquid == null ? Color.clear : sortLiquid.color);
            Draw.rect(Core.atlas.find(center), x, y);
            Draw.color();
        }

        @Override
        public void buildConfiguration(Table table) {
            ItemSelection.buildTable(LiquidUnloader.this, table, content.liquids(), () -> sortLiquid, this::configure);
        }

        @Override
        public Liquid config() {
            return sortLiquid;
        }

        @Override
        public byte version() {
            return 1;
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.s(sortLiquid == null ? -1 : sortLiquid.id);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            int id = revision == 1 ? read.s() : read.b();
            sortLiquid = id == -1 ? null : content.liquid(id);
        }
    }
}
