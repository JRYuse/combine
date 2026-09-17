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
import arc.util.Nullable;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.game.Team;
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
                ComboNet.markDirty();
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

    /** 能不能作为连接目标：原版替换出来的组合方块，或协作组合（js/java 子类）方块。 */
    public static boolean linkTarget(Building b){
        return b != null && b.isValid()
            && (ComboReflect.isComboBuild(b) || CoopCombo.eligible(b.block));
    }

    /**
     * 一台机器所属的「组合体」代表 —— 用来做连线去重：**一个组合体只需要一根线**。
     *
     * 组合节点以前是"最近的 maxNodes 台机器各连一根"，于是旁边站着一个三台的组合体时，
     * 三根线全连到同一个组合体上，别的组合体反而连不上。
     *
     * - 协作组合（js/java 子类）和各种 Combined* 方块：组长就是代表（ComboReflect.leader）；
     * - 组合仓库/容器：它们走自己那套"相邻成组"的并仓，这里按相邻同类做个小 BFS 找代表。
     */
    public static Building groupRep(Building b){
        if(b == null) return null;
        if(b.block instanceof CombinedStorageBlock){
            Building rep = null;
            for(Building m : storageGroupMembers(b)){
                if(rep == null || m.pos() < rep.pos()) rep = m;
            }
            return rep != null ? rep : b;
        }
        Building l = ComboReflect.leader(b);
        return l == null ? b : l;
    }

    /** 一个组合体的全部成员（画蓝框、算包围盒用）。 */
    public static Seq<Building> groupMembers(Building b){
        if(b == null) return new Seq<>();
        if(b.block instanceof CombinedStorageBlock) return storageGroupMembers(b);
        return ComboReflect.group(b);
    }

    /** 组合仓库/容器的"相邻成组"（和 CombinedStorageBlock 自己的并仓规则一致）。 */
    private static Seq<Building> storageGroupMembers(Building b){
        Seq<Building> out = new Seq<>();
        try{
            arc.struct.ObjectSet<Building> seen = new arc.struct.ObjectSet<>();
            arc.struct.Queue<Building> queue = new arc.struct.Queue<>();
            queue.addLast(b);
            seen.add(b);
            boolean merge = ((CombinedStorageBlock.CombinedStorageBuild) b).coreMergeStorage();
            while(!queue.isEmpty()){
                Building cur = queue.removeFirst();
                out.add(cur);
                if(!merge || cur.proximity == null) continue;
                for(Building nb : cur.proximity){
                    if(nb instanceof CombinedStorageBlock.CombinedStorageBuild other
                        && other.team == b.team && other.coreMergeStorage() && ComboReflect.inWorld(other)
                        && seen.add(other)){
                        queue.addLast(other);
                    }
                }
            }
        }catch(Throwable ignored){
        }
        if(out.isEmpty()) out.add(b);
        return out;
    }

    public boolean linkValid(Building tile, Building link, boolean checkMaxNodes){
        if(tile == null || link == null || tile == link || !link.isValid() || tile.team != link.team) return false;
        if(!linkTarget(link)) return false;
        if(checkMaxNodes && tile instanceof ComboNodeBuild node){
            if(node.links.size >= maxNodes) return false;
            // 同一个组合体连一根就够：再连它别的成员只是白占连线数
            Building rep = groupRep(link);
            for(int i = 0; i < node.links.size; i++){
                Building ex = world.build(node.links.get(i));
                if(ex != null && ex.isValid() && groupRep(ex) == rep) return false;
            }
        }
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
        drawLinkHints(x, y);
        Draw.reset();
    }

    /**
     * 放置**建造计划（预设建筑 / 蓝图里的每一个格子）**时也要给"能连上的组合体"加蓝框。
     * 原版电力节点只在鼠标那一个格子上画预览(drawPlace)，预设是一串计划、走的是 drawPlan，
     * 所以两处都得挂。
     */
    @Override
    public void drawPlan(mindustry.entities.units.BuildPlan plan, arc.util.Eachable<mindustry.entities.units.BuildPlan> list, boolean valid){
        super.drawPlan(plan, list, true);
        drawLinkHints(plan.x, plan.y);
    }

    /** 把"这个位置能连上的组合体"框成蓝框（同原版电力节点放置预览的 Pal.place 方框）。 */
    void drawLinkHints(int tileX, int tileY){
        try{
            if(player == null || player.team() == null) return;
            // 一个组合体只画一个框：画在每个成员上会变成一串框，看不出"这是一组"
            arc.struct.ObjectSet<Building> done = new arc.struct.ObjectSet<>();
            for(Building other : potentialLinks(tileX, tileY, player.team(), null)){
                Building rep = groupRep(other);
                if(rep == null || !done.add(rep)) continue;
                float minx = other.x, maxx = other.x, miny = other.y, maxy = other.y;
                float half = 0f;
                for(Building m : groupMembers(rep)){
                    if(m == null || !m.isValid()) continue;
                    minx = Math.min(minx, m.x);
                    maxx = Math.max(maxx, m.x);
                    miny = Math.min(miny, m.y);
                    maxy = Math.max(maxy, m.y);
                    half = Math.max(half, m.block.size * tilesize / 2f);
                }
                if(half <= 0f) half = Math.max(other.block.size, 1) * tilesize / 2f;
                Drawf.square((minx + maxx) / 2f, (miny + maxy) / 2f,
                    Math.max((maxx - minx) / 2f, (maxy - miny) / 2f) + half + 2f, Pal.place);
            }
        }catch(Throwable ignored){
        }
    }

    /**
     * 这个位置能连到哪些组合体（放置预览、预设预览和测试共用）。
     * 判据和 linkValid 一致：同队 + 是组合建筑 + 在激光范围内 + 没被绝缘方块挡住。
     */
    public Seq<Building> potentialLinks(int tileX, int tileY, Team team, @Nullable Building self){
        Seq<Building> out = new Seq<>();
        if(team == null || world == null) return out;
        mindustry.world.Tile tile = world.tile(tileX, tileY);
        if(tile == null) return out;

        float wx = tileX * tilesize + offset, wy = tileY * tilesize + offset;
        // 用 ComboNet 的完整名单：组合仓库/容器是 update=false，不在 Groups.build 里
        for(Building other : ComboNet.allComboBuildings()){
            if(other == null || !other.isValid() || other.team != team || other == self) continue;
            if(!linkTarget(other)) continue;
            if(!arc.math.geom.Intersector.overlaps(new arc.math.geom.Circle(wx, wy, laserRange * tilesize),
                other.tile.getHitbox(arc.util.Tmp.r1))) continue;
            if(PowerNode.insulated(tileX, tileY, other.tileX(), other.tileY())) continue;
            out.add(other);
        }
        return out;
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
                for(Building c : autoLinkCandidates()) configureAny(c.pos());
            }
            ComboNet.markDirty();
            CoopCombo.markDirty();
        }

        /**
         * 自动连线的候选：**每个组合体只取一台**（最近的那台），最多 maxNodes 个组合体。
         * 以前是"最近的三台机器"，导致旁边一个三台组合体会把三根线全吃掉。
         */
        public Seq<Building> autoLinkCandidates(){
            Seq<Building> candidates = new Seq<>();
            for(Building other : ComboNet.allComboBuildings()){
                if(other != this && other.team == team && linkTarget(other)
                    && linkValid(this, other, true) && !links.contains(other.pos())){
                    candidates.add(other);
                }
            }
            if(candidates.isEmpty()) return candidates;
            candidates.sort((a, b) -> Float.compare(a.dst2(this), b.dst2(this)));
            arc.struct.ObjectSet<Building> groups = new arc.struct.ObjectSet<>();
            Seq<Building> out = new Seq<>();
            for(Building c : candidates){
                if(out.size >= maxNodes) break;
                Building rep = groupRep(c);
                if(rep != null && groups.add(rep)) out.add(c);
            }
            return out;
        }

        @Override
        public boolean onConfigureBuildTapped(Building other){
            if(other == this){
                if(links.size == 0){
                    Seq<Point2> points = new Seq<>();
                    for(Building b : autoLinkCandidates()){
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
                if(link != null && link.isValid() && linkTarget(link)){
                    h = h * 31 + link.pos();
                }
            }
            return h;
        }

        public void addLink(Building other){
            CoopCombo.markDirty();
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
            ComboNet.markDirty();
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
            ComboNet.markDirty();
        }

        @Override
        public void created(){
            super.created();
            // 登记给协作组合：节点的 links 能把 js/java 子类方块跨距离接进同一个组合体
            CoopCombo.trackNode(this);
        }

        @Override
        public void onRemoved(){
            CoopCombo.untrackNode(this);
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
            ComboNet.markDirty();
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
        public void display(Table table) {
          // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
          ComboUi.safe("combonode:display", () -> displayInner(table));
        }

        void displayInner(Table table) {
            super.display(table);
            // 信息面板只在切换选中目标时重建一次，所以这些行必须是"活"标签，
            // 不然连接数/覆盖数/热量都会停在打开面板那一刻。
            ComboUi.live(table, "combonode:info", this::buildPanel);
        }

        /** 面板文字都是"活"函数：每次调用取当前值（面板只搭一次，数字必须自己刷新）。 */
        public String linkText(){
            return "连接数: " + links.size + "/" + maxNodes;
        }

        public String coverText(){
            return "覆盖组合建筑: " + ComboNet.componentMembers(this).size;
        }

        public String heatText(){
            return "网络热量: " + Strings.fixed(heat, 1) + "/" + Strings.fixed(heatCap, 1);
        }

        /** 节点面板内容（活数据）。 */
        public void buildPanel(Table table) {
            table.add("[accent]组合节点[]").left();
            table.row();
            table.add(linkText()).color(Pal.accent).left();
            if(ComboNet.componentMembers(this).size > 0){
                table.row();
                table.add(coverText()).color(Pal.accent).left();
            }
            table.row();
            table.add(heatText()).color(Pal.lightOrange).left();
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
