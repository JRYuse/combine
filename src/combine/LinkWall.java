package combine;

import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.geom.Point2;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.entities.Units;
import mindustry.gen.Bullet;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Edges;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.Wall;















public class LinkWall extends Wall {

  public enum Mode {
    wall,
    door
  }

  public Mode mode = Mode.wall;
  public TextureRegion openRegion;


  public LinkWall(String name) {
    super(name);
    this.update = true;

    config(Boolean.class, (LinkWallBuild b, Boolean open) -> {
      b.setOpen(open);
      Seq<LinkWallBuild> members = new Seq<>(b.group());
      for (LinkWallBuild other : members)
        if (other != b && other.isDoor())
          other.setOpen(open);
    });

    buildType = LinkWallBuild::new;
  }

  @Override
  public void init() {
    super.init();
    update = true;
    if (mode == Mode.door) {
      solid = false;
      solidifes = true;
      consumesTap = true;
    } else {
      solid = true;
      solidifes = false;
      consumesTap = false;
    }
  }

  public class LinkWallBuild extends Building {
    
    public Seq<LinkWallBuild> links = new Seq<>();
    public LinkWallBuild linkLeader;
    public boolean linksDirty = true;
    public int seqSize = 1;
    public boolean open = false;

    public LinkWallBuild leader() {
      if (linkLeader != null && !linkLeader.isValid())
        linkLeader = null;
      return linkLeader == null ? this : linkLeader;
    }

    public boolean isLeader() {
      return leader() == this;
    }

    
    public Seq<LinkWallBuild> group() {
      LinkWallBuild l = leader();
      if (l.links == null)
        l.links = new Seq<>();
      return l.links;
    }

    
    public void markGroupDirty() {
      for (LinkWallBuild b : new Seq<>(group())) {
        if (!b.dead())
          b.linksDirty = true;
      }
      linksDirty = true;
    }

    
    public void rebuildLinks() {
      Seq<LinkWallBuild> found = new Seq<>();
      ObjectSet<LinkWallBuild> visited = new ObjectSet<>();
      Queue<LinkWallBuild> queue = new Queue<>();
      queue.addLast(this);
      visited.add(this);
      while (!queue.isEmpty()) {
        LinkWallBuild cur = queue.removeFirst();
        found.add(cur);
        for (Point2 edge : Edges.getEdges(cur.block.size)) {
          Tile t = Vars.world.tile(cur.tile.x + edge.x, cur.tile.y + edge.y);
          if (t == null || !(t.build instanceof LinkWallBuild b) || b.dead())
            continue;
          if (visited.add(b))
            queue.addLast(b);
        }
      }
      LinkWallBuild newLeader = this;
      for (LinkWallBuild b : found)
        if (b.pos() < newLeader.pos())
          newLeader = b;
      
      
      for (LinkWallBuild b : found) {
        b.links = found;
        b.linkLeader = (b == newLeader) ? null : newLeader;
        b.linksDirty = false;
        b.seqSize = found.size;
      }
    }

    @Override
    public void onProximityUpdate() {
      super.onProximityUpdate();
      if (!dead())
        rebuildLinks();
    }

    @Override
    public void placed() {
      super.placed();
      rebuildLinks();
    }

    @Override
    public void updateTile() {
      super.updateTile();
      if (linksDirty && !dead())
        rebuildLinks();
    }

    @Override
    public void onRemoved() {
      
      Seq<LinkWallBuild> members = new Seq<>(group());
      for (LinkWallBuild b : members) {
        if (b != this && !b.dead()) {
          b.linkLeader = null;
          b.linksDirty = true;
        }
      }
      links = new Seq<>();
      links.add(this);
      linkLeader = null;
      linksDirty = false;
      seqSize = 1;
      super.onRemoved();
    }

    



    public boolean isDoor() {
      return ((LinkWall) block).mode == Mode.door;
    }

    public void setOpen(boolean open) {
      if (this.open == open)
        return;
      this.open = open;
      recache();
      if (!Vars.world.isGenerating())
        Vars.pathfinder.updateTile(tile);
    }

    @Override
    public Boolean config() {
      return open;
    }

    @Override
    public void tapped() {
      if (!isDoor())
        return;
      LinkWallBuild lead = leader();
      if (lead == null)
        lead = this;
      Seq<LinkWallBuild> members = new Seq<>(lead.group());
      if (members.isEmpty())
        members.add(this);
      if (open && members.contains(b -> b.isDoor() && Units.anyEntities(b.tile)))
        return;
      if (!lead.timer(0, 60f))
        return;
      configure(!open);
    }

    @Override
    public boolean checkSolid() {
      return !(isDoor() && open);
    }

    @Override
    public boolean collision(Bullet bullet) {
      if (isDoor() && open)
        return false;
      return super.collision(bullet);
    }

    @Override
    public void draw() {
      if (!isDoor()) {
        super.draw();
        return;
      }
      TextureRegion openRegion = ((LinkWall) block).openRegion;
      if (open) {
        if (openRegion != null && openRegion.found())
          Draw.rect(openRegion, x, y);
        else
          Draw.rect(region, x, y + Vars.tilesize * size);
      } else {
        Draw.rect(region, x, y);
      }
    }

    @Override
    public byte version() {
      return 1;
    }

    @Override
    public void write(arc.util.io.Writes write) {
      super.write(write);
      write.bool(open);
    }

    @Override
    public void read(arc.util.io.Reads read, byte revision) {
      super.read(read, revision);
      if (revision >= 1)
        open = read.bool();
    }

    @Override
    public void heal(float amount) {
      Seq<LinkWallBuild> members = new Seq<>(group());
      float totalMax = 0f;
      for (LinkWallBuild b : members)
        totalMax += b.maxHealth;
      if (totalMax <= 0.001f) {
        super.heal(amount);
        return;
      }
      for (LinkWallBuild b : members) {
        b.health += amount * (b.maxHealth / totalMax);
        b.clampHealth();
        b.healthChanged();
      }
    }

    



    @Override
    public void damage(float damage) {
      if (dead() || Vars.net.client())
        return;
      Seq<LinkWallBuild> members = new Seq<>(group());
      if (members.isEmpty())
        return;
      float totalMax = 0f;
      for (LinkWallBuild b : members)
        totalMax += b.maxHealth;
      if (totalMax <= 0.001f) {
        super.damage(damage);
        return;
      }
      float pool = 0f;
      for (LinkWallBuild b : members) {
        b.health -= damage * (b.maxHealth / totalMax);
        b.clampHealth();
        b.healthChanged();
        pool += b.health;
      }
      if (pool <= 0f || health <= 0f) {
        for (LinkWallBuild b : members)
          if (!b.dead())
            b.kill();
      }
    }

    @Override
    public void display(Table table) {
      super.display(table);
      if (Vars.player.team() == this.team) {
        table.row();
        table.label(() -> "链接数量" + this.seqSize).pad(4).wrap().width(200f).left();
        table.row();
        table.label(() -> isDoor() ? (open ? "模式: 门(开)" : "模式: 门(关)") : "模式: 墙").pad(4).wrap().width(200f).left();
      }
    }
  }
}
