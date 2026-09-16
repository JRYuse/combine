package combine;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Circle;
import arc.math.geom.Point2;
import arc.scene.ui.layout.Table;
import arc.struct.IntSeq;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Pal;
import mindustry.world.Block;
import mindustry.world.blocks.heat.HeatBlock;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;

import static mindustry.Vars.*;

/**
 * 组合节点（不继承 PowerNode）。
 *
 * links 是节点自己的组合连接表，可以连接范围内任意 ComboReflect.isComboBuild 的建筑，
 * 包括没有 power 模块的仓库。对有 power 的连接目标，同时写入原版 PowerModule.links，
 * 以便电力也共享；物品/液体/热量由 ComboNet 处理。
 */
public class ComboNode extends Block {
    public int maxNodes = 3;
    public float laserRange = 6f;
    public TextureRegion laser, laserEnd, nodeRegion;

    public ComboNode(String name){
        super(name);
        update = true;
        sync = true;
        solid = true;
        destructible = true;
        configurable = true;
        hasPower = true;
        hasItems = false;
        hasLiquids = false;
        consumesPower = false;
        outputsPower = false;
        conductivePower = false;
        connectedPower = true;
        drawCached = false;
        fullOverride = "power-node-large";
        buildType = ComboNodeBuild::new;

        config(Integer.class, (ComboNodeBuild entity, Integer value) -> {
            Building other = world.build(value);
            boolean contains = entity.links.contains(value);
            boolean valid = other != null && other.isValid() && linkValid(entity, other, false);

            if(contains){
                entity.links.removeValue(value);
                if(entity.power != null) entity.power.links.removeValue(value);
                if(other != null && other.power != null){
                    other.power.links.removeValue(entity.pos());
                    other.updatePowerGraph();
                }
                if(entity.power != null){
                    new mindustry.world.blocks.power.PowerGraph().reflow(entity);
                    entity.updatePowerGraph();
                }
                ComboNet.rebuild();
            }else if(valid && entity.links.size < maxNodes){
                entity.addLink(other);
            }
        });

        config(Point2[].class, (ComboNodeBuild entity, Point2[] points) -> {
            IntSeq old = new IntSeq(entity.links);
            for(int i = 0; i < old.size; i++){
                configurations.get(Integer.class).get(entity, old.get(i));
            }
            for(Point2 p : points){
                configurations.get(Integer.class).get(entity, Point2.pack(p.x + entity.tileX(), p.y + entity.tileY()));
            }
        });
    }

    @Override
    public void load(){
        super.load();
        // 贴图用模组自带 assets/sprites/blocks/node.png
        // （模组贴图打包时统一加 "<模组名>-" 前缀，所以 region 名是 combine-node）
        TextureRegion custom = Core.atlas.has("combine-node") ? Core.atlas.find("combine-node") : null;
        nodeRegion = custom != null ? custom : Core.atlas.find("power-node-large", Core.atlas.find("power-node"));
        // region 必须设：建造过程中的 ConstructBuild 画的是目标方块的 region
        // （Block.icons() 用 region，没设就会退回用方块名找 "combo-node" → 找不到 → error 贴图）
        region = nodeRegion;
        resetGeneratedIcons();
        if(nodeRegion != null && nodeRegion.found()) fullOverride = "combine-node"; // 图标也用新贴图
        laser = Core.atlas.find("laser");
        laserEnd = Core.atlas.find("laser-end");
        if(fullIcon == null || !fullIcon.found()) fullIcon = nodeRegion;
        if(uiIcon == null || !uiIcon.found()) uiIcon = fullIcon;
    }

    @Override
    public void setStats(){
        super.setStats();
        stats.add(Stat.powerRange, laserRange, StatUnit.blocks);
        stats.add(Stat.powerConnections, maxNodes, StatUnit.none);
    }

    public boolean linkValid(Building tile, Building link, boolean checkMaxNodes){
        if(tile == null || link == null || tile == link || !link.isValid() || tile.team != link.team) return false;
        if(!ComboReflect.isComboBuild(link)) return false;
        if(checkMaxNodes && link instanceof ComboNodeBuild node && node.links.size >= maxNodes) return false;
        if(PowerNode.insulated(tile, link)) return false;
        return overlaps(tile, link, laserRange * tilesize);
    }

    public boolean overlaps(Building src, Building other, float range){
        return arc.math.geom.Intersector.overlaps(
            new Circle(src.x, src.y, range),
            other.tile.getHitbox(arc.util.Tmp.r1));
    }

    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid){
        Lines.stroke(1f);
        Draw.color(Pal.placing);
        Drawf.circles(x * tilesize + offset, y * tilesize + offset, laserRange * tilesize);
        Draw.reset();
    }

    public class ComboNodeBuild extends Building implements HeatBlock {
        public IntSeq links = new IntSeq();
        public float heat = 0f;
        public float heatCap = 100f;
        public int lastLinkHash = Integer.MIN_VALUE;

        @Override
        public void placed(){
            super.placed();
            if(!net.client() && links.size == 0){
                Seq<Building> candidates = new Seq<>();
                for(Building other : Groups.build){
                    if(other != this && other.team == team && ComboReflect.isComboBuild(other)
                        && linkValid(this, other, true) && !links.contains(other.pos())){
                        candidates.add(other);
                    }
                }
                candidates.sort((a, b) -> Float.compare(a.dst2(this), b.dst2(this)));
                for(int i = 0; i < candidates.size && i < maxNodes; i++){
                    configureAny(candidates.get(i).pos());
                }
            }
            ComboNet.rebuild();
        }

        @Override
        public boolean onConfigureBuildTapped(Building other){
            if(other == this){
                if(links.size == 0){
                    Seq<Building> candidates = new Seq<>();
                    for(Building b : Groups.build){
                        if(b != this && b.team == team && ComboReflect.isComboBuild(b)
                            && linkValid(this, b, true) && !links.contains(b.pos())){
                            candidates.add(b);
                        }
                    }
                    candidates.sort((a, b) -> Float.compare(a.dst2(this), b.dst2(this)));
                    Seq<Point2> points = new Seq<>();
                    for(int i = 0; i < candidates.size && i < maxNodes; i++){
                        Building b = candidates.get(i);
                        points.add(new Point2(b.tileX() - tile.x, b.tileY() - tile.y));
                    }
                    configure(points.toArray(Point2.class));
                }else{
                    configure(new Point2[0]);
                }
                deselect();
                return false;
            }
            if(linkValid(this, other, true)){
                configure(other.pos());
                return false;
            }
            return true;
        }

        @Override
        public void configured(Unit builder, Object value){
            super.configured(builder, value);
            ComboNet.rebuild();
        }

        @Override
        public void updateTile(){
            super.updateTile();
            int hash = linkHash();
            if(hash != lastLinkHash){
                lastLinkHash = hash;
                ComboNet.rebuild();
            }
            if(enabled){
                ComboNet.updateHeatNode(this);
            }else{
                heat = 0f;
            }
        }

        private int linkHash(){
            int h = 19;
            for(int i = 0; i < links.size; i++){
                Building link = world.build(links.get(i));
                if(link != null && link.isValid() && ComboReflect.isComboBuild(link)){
                    h = h * 31 + link.pos();
                }
            }
            return h;
        }

        public void addLink(Building other){
            if(other == null || !other.isValid()) return;
            links.addUnique(other.pos());
            if(power != null && other.power != null){
                power.links.addUnique(other.pos());
                if(other.team == team){
                    other.power.links.addUnique(pos());
                }
                if(power.graph != null && other.power.graph != null){
                    power.graph.addGraph(other.power.graph);
                }
            }
            ComboNet.rebuild();
        }

        public void removeLink(Building other){
            if(other == null) return;
            links.removeValue(other.pos());
            if(power != null && other != null && other.power != null){
                power.links.removeValue(other.pos());
                other.power.links.removeValue(pos());
                if(power.graph != null) power.graph.remove(this);
                new mindustry.world.blocks.power.PowerGraph().reflow(this);
                new mindustry.world.blocks.power.PowerGraph().reflow(other);
                updatePowerGraph();
                other.updatePowerGraph();
            }
            ComboNet.rebuild();
        }

        @Override
        public void onRemoved(){
            for(int i = links.size - 1; i >= 0; i--){
                Building other = world.build(links.get(i));
                if(other != null){
                    links.removeIndex(i);
                    if(power != null && other.power != null){
                        power.links.removeValue(other.pos());
                        other.power.links.removeValue(pos());
                        other.updatePowerGraph();
                    }
                }
            }
            if(power != null){
                new mindustry.world.blocks.power.PowerGraph().reflow(this);
                updatePowerGraph();
            }
            ComboNet.rebuildExcluding(this);
            super.onRemoved();
        }

        @Override
        public void draw(){
            if(nodeRegion != null && nodeRegion.found()){
                Draw.rect(nodeRegion, x, y);
            }

            Draw.z(125f);
            Lines.stroke(1f);
            for(int i = 0; i < links.size; i++){
                Building link = world.build(links.get(i));
                if(link == null || !link.isValid()) continue;
                Draw.color(Pal.accent, 0.8f);
                Drawf.laser(laser, laserEnd, laserEnd, x, y, link.x, link.y, 0.25f, true, true);
            }
            Draw.reset();
        }

        @Override
        public void drawSelect(){
            super.drawSelect();
            Lines.stroke(1f);
            Draw.color(Pal.accent);
            Drawf.circles(x, y, laserRange * tilesize);
            Draw.reset();
        }

        @Override
        public void drawConfigure(){
            Drawf.circles(x, y, block.size * tilesize / 2f + 1f + Mathf.absin(arc.util.Time.time, 4f, 1f));
            Drawf.circles(x, y, laserRange * tilesize);
            for(int i = 0; i < links.size; i++){
                Building link = world.build(links.get(i));
                if(link != null && link.isValid()){
                    Drawf.square(link.x, link.y, link.block.size * tilesize / 2f + 1f, Pal.place);
                }
            }
            Draw.reset();
        }

        @Override
        public Point2[] config(){
            Point2[] out = new Point2[links.size];
            for(int i = 0; i < out.length; i++){
                out[i] = Point2.unpack(links.get(i)).sub(tile.x, tile.y);
            }
            return out;
        }

        @Override
        public float heat(){
            return heat;
        }

        @Override
        public float heatFrac(){
            return Mathf.clamp(heat / Math.max(heatCap, 0.001f));
        }

        @Override
        public void display(Table table){
            super.display(table);
            int members = ComboNet.componentMembers(this).size;
            table.row();
            table.add("[accent]组合节点[]").left();
            table.row();
            table.add("连接数: " + links.size + "/" + maxNodes).color(Pal.accent).left();
            if(members > 0){
                table.row();
                table.add("覆盖组合建筑: " + members).color(Pal.accent).left();
            }
            table.row();
            table.add("网络热量: " + Strings.fixed(heat, 1) + "/" + Strings.fixed(heatCap, 1))
                .color(Pal.lightOrange).left();
        }

        @Override
        public byte version(){
            return 2;
        }

        @Override
        public void write(Writes write){
            super.write(write);
            write.s(links.size);
            for(int i = 0; i < links.size; i++){
                write.i(links.get(i));
            }
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);
            links.clear();
            if(revision >= 2){
                int amount = read.s();
                for(int i = 0; i < amount; i++){
                    links.add(read.i());
                }
                if(links.size == 0 && power != null){
                    for(int i = 0; i < power.links.size; i++){
                        links.addUnique(power.links.get(i));
                    }
                }
            }else{
                // v1 旧节点继承 PowerNode，连接只存在 power.links 里；立即迁移到 links。
                if(power != null){
                    for(int i = 0; i < power.links.size; i++){
                        links.addUnique(power.links.get(i));
                    }
                }
            }
        }
    }
}
