package combine.defense;
import combine.util.ComboUi;
import arc.Core;
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
import arc.graphics.Color;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.util.Time;
import mindustry.graphics.Drawf;
import mindustry.ui.Bar;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;

public class LinkWall extends Wall {

  public enum Mode {
    wall,
    door,
    shield
  }

  public Mode mode = Mode.wall;
  public TextureRegion openRegion;

  public LinkWall(String name) {
    super(name);
    this.update = true;
    // 注意：copyFields 会用原版方块的 configurations 覆盖本表（其闭包指向原版
    // Build 类型，会 ClassCastException），故在 init() 里清空重注册
    buildType = LinkWallBuild::new;
  }

  @Override
  public void load() {
    super.load();
    // FIX：openRegion 之前从未加载，门开着时永远走不到贴图分支
    openRegion = Core.atlas.find(name + "-open");
    // FIX[shield]: 相位盾自发光贴图（克隆体名字可能取不到，processWalls 里还会按原名再取一次兜底）
    glowRegion = Core.atlas.find(name + "-glow");
  }

  // ===== 相位盾（shield 模式）配置字段：与原版 ShieldWall 字段同名，copyFields 自动拷贝 =====
  public float shieldHealth = 900f;
  public float breakCooldown = 600f;
  public float regenSpeed = 2f;
  public Color glowColor = Color.valueOf("ff7531").a(0.5f);
  public float glowMag = 0.6f;
  public float glowScl = 8f;
  public TextureRegion glowRegion;

  @Override
  public void setStats() {
    super.setStats();
    if (mode == Mode.shield) {
      stats.add(Stat.shieldHealth, shieldHealth);
      stats.add(Stat.cooldownTime, breakCooldown / 60f, StatUnit.seconds);
    }
  }

  @Override
  public void setBars() {
    barMap.clear();
    super.setBars();
    if (mode == Mode.shield) {
      // 注意：LinkWallBuild 继承 Building，addBar 的实体形参是 WallBuild 类型，需显式转型。
      // 用 (String, Color, Floatp) 构造器，兼容性最好。
      addBar("charge", entity -> new Bar(
          "stat.shieldhealth",
          mindustry.graphics.Pal.shield,
          () -> {
            LinkWallBuild self = (LinkWallBuild) entity;
            return shieldHealth <= 0f ? 0f : self.shield / shieldHealth;
          }));
    }
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
    if (mode == Mode.shield) {
      // 相位盾需要动态绘制（盾半径/受击闪白/自发光）
      drawDynamic = true;
    }
    // FIX：copyFields 已把原版 Door/Wall 的 configurations 拷进来（闭包类型不对），
    // 这里清空并按 LinkWallBuild 重新注册
    configurations.clear();
    config(Boolean.class, (LinkWallBuild b, Boolean open) -> {
      b.setOpen(open);
      Seq<LinkWallBuild> members = new Seq<>(b.group());
      for (LinkWallBuild other : members)
        if (other != b && other.isDoor())
          other.setOpen(open);
    });
  }

  public class LinkWallBuild extends Building {

    public Seq<LinkWallBuild> links = new Seq<>();
    public LinkWallBuild linkLeader;
    public boolean linksDirty = true;
    public int seqSize = 1;
    /** 上一次"是否允许组合"的判定结果：队伍里玩家有变动时自动重算一次。 */
    public boolean groupAllowedLast = true;
    public boolean open = false;

    // ===== 相位盾（shield 模式）运行时状态 =====
    public float shield;
    public float shieldRadius;
    public float breakTimer;
    // LinkWallBuild 直接继承 Building（不是 WallBuild），没有继承的受击闪白字段，自管
    public float hit;

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

    /**
     * 这面墙要不要参与"组合"（连成一组、共享一个血池）。
     *
     * 判据是"这个队伍里**有没有玩家**"，而不是队伍名、也不是 Team.isOnlyAI()：
     *   · Team.isOnlyAI() 只在"有波次/进攻模式/战役"里成立 —— 沙盒、自定义这些模式里
     *     敌方队伍会被算成"不是 AI"，照样组合（实测就是这样漏的）；
     *   · 按队伍名硬编码（只让 sharded 组合）又会破坏 PvP。
     * 所以：队伍里有玩家（PvP 两边、联机各方、玩家自己切到的队伍）→ 组合；
     * 纯 AI 势力的墙保持原版单格行为（血池不叠、不被"打一处整片掉"）。
     */
    public boolean groupAllowed() {
      if (team == null)
        return false;
      if (!team.data().players.isEmpty())
        return true;
      // 兜底：本地玩家所在的队伍（专用服务器上没有本地玩家，返回 null）
      return Vars.player != null && Vars.player.team() == team;
    }

    public void rebuildLinks() {
      Seq<LinkWallBuild> found = new Seq<>();
      // 不组合的队伍：自己就是一组（组员只有自己 → 伤害/治疗/门开关都退回原版单格行为）
      groupAllowedLast = groupAllowed();
      if (!groupAllowedLast) {
        links = found;
        found.add(this);
        linkLeader = null;
        linksDirty = false;
        seqSize = 1;
        return;
      }
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
          if (b.block instanceof LinkWall && visited.add(b))
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
      // 成员增删后也摊平（新加进来的满血墙不会和受伤的同伴差一截）
      if (found.size > 1)
        redistributeHealth();
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
      // 队伍里玩家来了/走了（PvP 换边、联机加入等）→ 判定变了就重算一次：
      // 该组合的重新连起来，不该组合的拆回单格
      if (!linksDirty && !dead() && groupAllowedLast != groupAllowed())
        rebuildLinks();
      // FIX[shield]: 相位盾回复/破盾计时/盾半径动画（原版 ShieldWallBuild.updateTile 移植）
      if (isShield()) {
        if (hit > 0f)
          hit = Math.max(hit - Time.delta / 10f, 0f);
        if (breakTimer > 0f)
          breakTimer -= Time.delta;
        else
          shield = Mathf.clamp(shield + ((LinkWall) block).regenSpeed * edelta(), 0f, ((LinkWall) block).shieldHealth);
        shieldRadius = Mathf.lerpDelta(shieldRadius, shieldBroken() ? 0f : 1f, 0.12f);
      }
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

    public boolean isShield() {
      return ((LinkWall) block).mode == Mode.shield;
    }

    /** 盾是否处于破碎不可用状态（原版 ShieldWall.broken 语义） */
    public boolean shieldBroken() {
      return breakTimer > 0f || !canConsume();
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
        drawShieldFx();
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

    /** FIX[shield]: 相位盾覆盖层（原版 ShieldWallBuild.draw 移植） */
    public void drawShieldFx() {
      if (!isShield() || shieldRadius <= 0.001f)
        return;
      float radius = shieldRadius * 8f * block.size / 2f;
      Draw.z(125f);
      Draw.color(team.color, Color.white, Mathf.clamp(hit));
      if (Vars.renderer.animateShields) {
        Fill.square(x, y, radius);
      } else {
        Lines.stroke(1.5f);
        Draw.alpha(0.09f + Mathf.clamp(0.08f * hit));
        Fill.square(x, y, radius);
        Draw.alpha(1f);
        Lines.poly(x, y, 4, radius, 45f);
        Draw.reset();
      }
      Draw.reset();
      TextureRegion glow = ((LinkWall) block).glowRegion;
      if (glow != null && glow.found())
        Drawf.additive(glow, ((LinkWall) block).glowColor,
            (1f - ((LinkWall) block).glowMag + Mathf.absin(((LinkWall) block).glowScl, ((LinkWall) block).glowMag))
                * shieldRadius,
            x, y, 0f, 31f);
    }

    @Override
    public byte version() {
      return 2;
    }

    @Override
    public void write(arc.util.io.Writes write) {
      super.write(write);
      write.bool(open);
      // FIX[shield]: 相位盾状态（version 2 起）
      write.f(shield);
      write.f(breakTimer);
    }

    @Override
    public void read(arc.util.io.Reads read, byte revision) {
      super.read(read, revision);
      if (revision >= 1)
        open = read.bool();
      // FIX[shield]: 相位盾状态（version 2 起；旧档缺省 = 满盾）
      if (revision >= 2) {
        shield = read.f();
        breakTimer = read.f();
      }
    }

    /**
     * 修复：先按原版补"被治疗的那一格"（修复投影/修复塔只会照到射程内的墙，
     * 所以只有那几格会进这里），然后**立刻**把整组的血量按最大血量比重重新摊一遍。
     *
     * 这样组仍然是"一整面墙"的血池，但任何时候每格都是同一个血量百分比：
     *   · 治疗量只按射程内实际照到的墙计入池子，不会出现"一个修复源等于在修整面墙"的夸张效果；
     *   · 摊平之后不会再出现"某一格先见底 → 连带整组一起炸"。
     */
    @Override
    public void heal(float amount) {
      super.heal(amount);
      redistributeHealth();
    }

    /** 把整组的血量按各成员最大血量比重重新分配（总量不变，各格回到同一百分比）。 */
    public void redistributeHealth() {
      Seq<LinkWallBuild> members = new Seq<>(group());
      int n = members.size;
      if (n <= 1)
        return;
      float totalMax = 0f, total = 0f;
      for (int i = 0; i < n; i++) {
        LinkWallBuild b = members.get(i);
        totalMax += b.maxHealth;
        total += Math.max(b.health, 0f);
      }
      if (totalMax <= 0.001f)
        return;
      for (int i = 0; i < n; i++) {
        LinkWallBuild b = members.get(i);
        b.health = total * (b.maxHealth / totalMax);
        b.clampHealth();
        b.healthChanged();
      }
    }

    @Override
    public void damage(float damage) {
      if (dead() || Vars.net.client())
        return;
      // FIX[shield]: 各自相位盾先吸收（原版 ShieldWallBuild.damage 移植），
      // 剩余伤害才进组血池；被打的成员盾先扛，保持"组=一整面墙"的语义
      if (isShield() && !shieldBroken() && shield > 0f) {
        float taken = Math.min(shield, damage);
        shield -= taken;
        hit = 1f;
        damage -= taken;
        if (shield <= 1e-5f && taken > 0f)
          breakTimer = ((LinkWall) block).breakCooldown;
        if (damage <= 0f)
          return;
      }
      // FIX[生命倍率]: 与原版 BuildingComp.damage 一致——组血池分摊前必须按
      // state.rules.blockHealth(team) 折算伤害，否则规则里的建筑生命倍率对组合墙失效
      float dm = Vars.state.rules.blockHealth(team);
      if (Mathf.zero(dm)) {
        damage = health + 1f;
      } else {
        damage /= dm;
      }
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
      for (LinkWallBuild b : members) {
        b.health -= damage * (b.maxHealth / totalMax);
      }
      // 分摊完立刻摊平：各格始终保持同一个百分比，这样不会出现"某一格血量先见底就被单独打掉"，
      // 更不会因为那一格见底而把满血的同伴一起带走（以前这里是 health <= 0 就整组 kill）。
      redistributeHealth();
      float pool = 0f;
      for (LinkWallBuild b : members)
        pool += Math.max(b.health, 0f);
      // 只有整组的血池被打空，这面组合墙才算倒
      if (pool <= 0.001f) {
        for (LinkWallBuild b : members)
          if (!b.dead())
            b.kill();
      }
    }

    @Override
    public void pickedUp() {
      super.pickedUp();
      shieldRadius = 0f;
    }

    @Override
    public void display(Table table) {
      // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
      ComboUi.safe("linkwall:display", () -> displayInner(table));
    }

    void displayInner(Table table) {
      super.display(table);
      if (Vars.player.team() == this.team) {
        table.row();
        table.label(() -> "链接数量" + this.seqSize).pad(4).wrap().width(200f).left();
        table.row();
        table.label(() -> isDoor() ? (open ? "模式: 门(开)" : "模式: 门(关)") : (isShield() ? "模式: 相位盾" : "模式: 墙")).pad(4)
            .wrap().width(200f).left();
      }
        }
  }
}
