package combine;

import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.Tmp;
import arc.util.Log;
import mindustry.ai.types.BuilderAI;
import mindustry.ai.types.CommandAI;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.units.BuildPlan;
import mindustry.entities.units.UnitController;
import mindustry.entities.units.WeaponMount;
import mindustry.entities.Units;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.Weapon;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock;
import mindustry.game.Teams.BlockPlan;

import java.lang.reflect.Field;

import static mindustry.Vars.*;

public class TestMultiBuildWeapon extends Weapon {
  public float range = 236f;
  /** 该挂座施工速度倍率。 */
  public float speedMulti = 1f;
  /** 该挂座同时建造/拆除的建筑数量上限（可被 Settings.maxBuild 覆盖）。 */
  public int maxBuild = 5;

  public TestMultiBuildWeapon() {
    rotate = true;
    noAttack = true;
    predictTarget = false;
    display = false;
    bullet = new BulletType();
    useAttackRange = false;
    mountType = BuildWeaponMount::new;
  }

  /** 实际生效的并行上限：优先取全局 Settings，其次取本武器字段。 */
  int getMaxBuild() {
    return Settings.maxBuild() <= 0 ? maxBuild : Settings.maxBuild();
  }

  @Override
  public void update(Unit unit, WeaponMount mount) {
    BuildWeaponMount m = (BuildWeaponMount) mount;
    try {
      updateWeapon(unit, m);
    } catch (Throwable t) {
      // 任何异常都不要静默：否则表现为"建造武器完全不干活"
      if (Time.time - m.lastError > 300f) {
        m.lastError = Time.time;
        Log.err("[combine] 建造武器出错(@): @", unit.type.name, t);
      }
    }
  }

  void updateWeapon(Unit unit, BuildWeaponMount m) {
    // 施工队列的主人：跟随谁就优先辅助谁；没人可跟时用自己
    Unit targetUnit = resolveTargetUnit(unit);

    check(unit, m);

    // 造完/失效/被抢的格子放开，下面重新认领
    for (int i = m.plans.size - 1; i >= 0; i--) {
      BuildPlan plan = m.plans.get(i);
      if (plan == null) {
        m.plans.remove(i);
        m.targets.remove(i);
        continue;
      }
      Building b = world.build(plan.x, plan.y);
      boolean done = !(b instanceof ConstructBlock.ConstructBuild) || plan.progress >= 1f;
      if (done || isRob(unit, m, plan)) {
        releaseIndex(m, i);
      }
    }

    // 不要求"主人正在施工"：空闲的建造单位也该主动去造队列/预设里的建筑
    findTargets(targetUnit, unit, m);

    if (m.plans.size > 0) {
      // 开启 Settings.buildBoost 时：总速度平摊到当前认领数 → 认满 = maxBuild 倍速，未满时单格更快
      // 关闭时（默认）：完全等同于原版单格施工速度
      float boost = Settings.buildBoost() ? (float) getMaxBuild() / m.plans.size : 1f;

      for (int i = 0; i < m.plans.size; i++) {
        BuildPlan plan = m.plans.get(i);
        ConstructBlock.ConstructBuild target = m.targets.get(i);
        if (target == null) continue;

        float bs = 1f / target.buildCost * unit.type.buildSpeed * boost
                * unit.buildSpeedMultiplier * state.rules.buildSpeed(unit.team) * speedMulti * Time.delta;
        if (plan.breaking) {
          target.deconstruct(unit, unit.team.core(), bs);
          plan.progress = target.progress;
        } else {
          target.construct(unit, unit.team.core(), bs, plan.config);
          plan.progress = target.progress;
        }
      }
    }

    if (m.plans.size > 0) {
      BuildPlan plan = m.plans.first();
      m.aimX = plan.drawx();
      m.aimY = plan.drawy();
    } else {
      Tmp.v2.trns(unit.rotation(), 800f);
      m.aimX = Tmp.v2.x + unit.x;
      m.aimY = Tmp.v2.y + unit.y;
    }

    m.shoot = false;
    m.rotate = true;
    super.update(unit, m);
  }

  /**
   * 施工队列的主人。
   *
   * 建造单位(poly 等)的控制器是 CommandAI，内层才是 BuilderAI：
   * 跟随某个人时就用那个人的队列（玩家排队的那串预设建筑），
   * 没人可跟时回落到自己 —— 此时 weapon 会从队伍计划表(team.data().plans)里各认领一格，
   * 这样多把建造武器才能真正并行施工，而不是只跟着别人一次造一格。
   */
  Unit resolveTargetUnit(Unit unit) {
    UnitController controller = unit.controller();
    BuilderAI builder = null;
    if (controller instanceof CommandAI commandAI) {
      Object inner = getCommandController(commandAI);
      if (inner instanceof BuilderAI bai)
        builder = bai;
    } else if (controller instanceof BuilderAI bai) {
      builder = bai;
    }

    if (builder != null) {
      if (builder.following != null)
        return builder.following;
      if (builder.assistFollowing != null)
        return builder.assistFollowing;
    }
    return unit;
  }

  /** 按索引放弃当前认领（计划本身还留给别的施工者） */
  void releaseIndex(BuildWeaponMount m, int i) {
    if (i < 0 || i >= m.plans.size) return;
    BuildPlan plan = m.plans.remove(i);
    m.targets.remove(i);
    if (plan != null)
      plan.initialized = false;
  }

  /** 循环认领直到达到 maxBuild 或没有可认领的格子 */
  void findTargets(Unit unit, Unit weaponUnit, BuildWeaponMount m) {
    int guard = 0;
    while (m.plans.size < getMaxBuild() && guard++ < getMaxBuild()) {
      BuildPlan plan = findPlan(unit, weaponUnit, m);
      if (plan == null) break;
      if (!claimPlan(unit, m, plan)) break;
    }
  }

  /** 尝试认领一格（建造或拆除）。返回是否成功认领。 */
  boolean claimPlan(Unit unit, BuildWeaponMount m, BuildPlan plan) {
    Building existing = world.build(plan.x, plan.y);
    // 已经在施工(ConstructBuild 已存在)：直接接手继续造/继续拆，
    // 不能走 Build.validPlace —— 它遇到已有 ConstructBuild 会返回 false，
    // 那样格子开工之后就永远没人接着施工，进度会卡死。
    if (existing instanceof ConstructBlock.ConstructBuild cb) {
      m.targets.add(cb);
      m.plans.add(plan);
      plan.initialized = true;
      return true;
    }

    if (!plan.breaking) {
      if (Build.validPlace(plan.block, unit.team, plan.x, plan.y, plan.rotation)) {
        Build.beginPlace(unit, plan.block, unit.team, plan.x, plan.y, plan.rotation, plan.config);
        Building build = world.build(plan.x, plan.y);
        if (build instanceof ConstructBlock.ConstructBuild cb) {
          m.targets.add(cb);
          m.plans.add(plan);
          plan.initialized = true;
          return true;
        }
      }
    } else if (Build.validBreak(unit.team, plan.x, plan.y)) {
      Build.beginBreak(unit, unit.team, plan.x, plan.y);
      Building build = world.build(plan.x, plan.y);
      if (build instanceof ConstructBlock.ConstructBuild cb) {
        m.targets.add(cb);
        m.plans.add(plan);
        plan.initialized = true;
        return true;
      }
    }
    return false;
  }

  /**
   * 认领一格要造的东西，每把建造武器各认领一格：
   * 1. 先看施工队列主人的队列（跟随谁就帮谁排队的那串预设建筑，非队首）；
   * 2. 再看同队玩家的建造队列 —— 空闲的建造单位也能主动去造别人排好的预设；
   * 3. 最后看队伍计划表 team.data().plans（工程车/建造炮台用的同一份幽灵计划）。
   * 三种来源都会跳过：自己正在造的那格、已经在施工的格子、射程外的格子、
   * 以及被本机其它建造挂座认领的格子。
   */
  BuildPlan findPlan(Unit unit, Unit weaponUnit, BuildWeaponMount tm) {
    Queue<BuildPlan> plans = unit.plans();

    if (plans.size > 0) {
      BuildPlan first = plans.first();
      // 先找还没开工的格子，其次接手已经开工但没人管的 ConstructBuild
      for (int pass = 0; pass < 2; pass++) {
        for (int i = 0; i < plans.size; i++) {
          BuildPlan p = plans.get(i);
          // 队首归它自己的建造逻辑
          if (p == null || p == first)
            continue;
          if (!claimable(weaponUnit, tm, p, pass == 0))
            continue;
          return p;
        }
      }

      // 顺手清掉"已经造好"的残留计划，别让它一直堵在队列里（原版只清队首）
      for (int i = plans.size - 1; i >= 0; i--) {
        BuildPlan p = plans.get(i);
        if (p == null || p.breaking || p.block == null)
          continue;
        Building b = world.build(p.x, p.y);
        if (b != null && !(b instanceof ConstructBlock.ConstructBuild) && b.block == p.block) {
          plans.removeIndex(i);
        }
      }
    }

    // 附近同队单位的队列（玩家排好的预设、其它工程车正在排的活都在这）
    final BuildPlan[] found = {null};
    float radius = range;
    Units.nearby(unit.team, weaponUnit.x, weaponUnit.y, radius, other -> {
      if(found[0] != null || other == null || other == weaponUnit || !other.canBuild())
        return;
      Queue<BuildPlan> q = other.plans();
      for(int pass = 0; pass < 2 && found[0] == null; pass++){
        for(int i = 1; i < q.size; i++){ // 队首归它自己，跳过
          BuildPlan p = q.get(i);
          if(p != null && claimable(weaponUnit, tm, p, pass == 0)){
            found[0] = p;
            return;
          }
        }
      }
    });
    if(found[0] != null)
      return found[0];

    if (unit.team != null) {
      Queue<BlockPlan> teamPlans = unit.team.data().plans;
      BuildPlan own = weaponUnit.buildPlan();
      for (int pass = 0; pass < 2; pass++) {
        for (int i = 0; i < teamPlans.size; i++) {
          BlockPlan p = teamPlans.get(i);
          if (p == null || p.block == null)
            continue;
          if (own != null && own.x == p.x && own.y == p.y)
            continue; // 自己正在造的那格
          if (!claimable(weaponUnit, tm, p.x, p.y, pass == 0))
            continue;
          return new BuildPlan(p.x, p.y, p.rotation, p.block, p.config);
        }
      }
    }
    return null;
  }

  /** 查询用：不看是否已开工，只问"这格能不能被认领" */
  boolean claimable(Unit weaponUnit, BuildWeaponMount tm, BuildPlan plan){
    return claimable(weaponUnit, tm, plan, false);
  }

  boolean claimable(Unit weaponUnit, BuildWeaponMount tm, BuildPlan plan, boolean freshOnly){
    if(plan == null || plan.block == null)
      return false;
    if(!weaponUnit.within(plan.x * 8f, plan.y * 8f, range))
      return false;

    Building existing = world.build(plan.x, plan.y);
    boolean constructing = existing instanceof ConstructBlock.ConstructBuild;
    if(constructing && freshOnly)
      return false;
    if(plan.breaking){
      if(existing == null)
        return false;
    }else{
      if(existing != null && !constructing)
        return false;
    }

    BuildPlan own = weaponUnit.buildPlan();
    if(own != null && own.x == plan.x && own.y == plan.y)
      return false;
    if(tm.plans.contains(plan))
      return false;
    return notClaimedByOtherMount(weaponUnit, tm, plan.x, plan.y);
  }

  boolean claimable(Unit weaponUnit, BuildWeaponMount tm, int x, int y) {
    return claimable(weaponUnit, tm, x, y, true);
  }

  boolean claimable(Unit weaponUnit, BuildWeaponMount tm, int x, int y, boolean freshOnly) {
    if (!weaponUnit.within(x * 8f, y * 8f, range))
      return false;

    Building existing = world.build(x, y);
    boolean constructing = existing instanceof ConstructBlock.ConstructBuild;
    if (constructing && freshOnly)
      return false;
    if (existing != null && !constructing)
      return false;

    BuildPlan own = weaponUnit.buildPlan();
    if (own != null && own.x == x && own.y == y)
      return false;

    for (int i = 0; i < tm.plans.size; i++) {
      BuildPlan p = tm.plans.get(i);
      if (p != null && p.x == x && p.y == y)
        return false;
    }

    return notClaimedByOtherMount(weaponUnit, tm, x, y);
  }

  /** 同一格没有被本机别的建造挂座认领 */
  boolean notClaimedByOtherMount(Unit weaponUnit, BuildWeaponMount tm, int x, int y){
    for (WeaponMount mount : weaponUnit.mounts()) {
      if (mount instanceof BuildWeaponMount bm && bm != tm) {
        for (int i = 0; i < bm.plans.size; i++) {
          BuildPlan p = bm.plans.get(i);
          if (p != null && p.x == x && p.y == y)
            return false;
        }
      }
    }
    return true;
  }

  void check(Unit unit, BuildWeaponMount m) {
    float max = state.rules.infiniteResources ? Float.MAX_VALUE : range;
    for (int i = m.plans.size - 1; i >= 0; i--) {
      BuildPlan plan = m.plans.get(i);
      if (plan == null) {
        m.plans.remove(i);
        m.targets.remove(i);
        continue;
      }
      if (Mathf.dst(unit.x, unit.y, plan.x * 8f, plan.y * 8f) > max) {
        releaseIndex(m, i);
      }
    }
  }

  /** 该格是否被队首 / 其它挂座抢走（该放弃重找） */
  boolean isRob(Unit unit, BuildWeaponMount m, BuildPlan plan) {
    if (plan == null)
      return true;
    Queue<BuildPlan> plans = unit.plans();
    if (plans.size > 0 && plans.first() == plan)
      return true;
    for (WeaponMount mount : unit.mounts) {
      if (mount instanceof BuildWeaponMount bm && bm != m) {
        for (int i = 0; i < bm.plans.size; i++) {
          BuildPlan p = bm.plans.get(i);
          if (p == plan)
            return true;
        }
      }
    }
    return false;
  }

  @Override
  public void draw(Unit unit, WeaponMount mount) {
    super.draw(unit, mount);
    BuildWeaponMount m = (BuildWeaponMount) mount;

    int idx = 0, total = 0;
    for (WeaponMount wm : unit.mounts) {
      if (wm instanceof BuildWeaponMount) {
        if (wm == m)
          idx = total;
        total++;
      }
    }
    if (total == 0)
      return;
    float rotation = unit.rotation - 90f;
    float px = unit.x + Angles.trnsx(rotation, this.x, this.y);
    float py = unit.y + Angles.trnsy(rotation, this.x, this.y);

    var core = unit.core();

    // 本挂座自己认领的每一格：必须画
    drawBuildingBeam(px, py, m, unit);

    // 单位自己队列里、由本挂座负责的其它格子
    Queue<BuildPlan> plans = unit.plans();
    for (int i = 1; i < plans.size; i++) {
      if ((i - 1) % total != idx)
        continue;
      BuildPlan plan = plans.get(i);
      if (m.plans.contains(plan))
        continue;
      Tile tile = world.tile(plan.x, plan.y);
      if (!(tile.build instanceof ConstructBlock.ConstructBuild))
        continue;

      boolean worked = false;
      for (WeaponMount wm : unit.mounts) {
        if (wm instanceof BuildWeaponMount bm && bm.plans.contains(plan) && bm.targets.get(bm.plans.indexOf(plan)) != null) {
          worked = true;
          break;
        }
      }
      if (!worked)
        continue;

      if (!state.rules.infiniteResources && unit.shouldSkip(plan, core))
        continue;

      float maxDst = state.rules.infiniteResources ? Float.MAX_VALUE : unit.type.buildRange;
      if (!unit.within(plan, maxDst))
        continue;

      int size = plan.breaking ? tile.block().size : plan.block.size;
      Lines.stroke(1f, plan.breaking ? Pal.remove : Pal.accent);
      Draw.z(Layer.buildBeam);
      Draw.alpha(unit.buildAlpha());
      Drawf.buildBeam(px, py, plan.drawx(), plan.drawy(), tilesize * size / 2f);
    }
    Draw.reset();
  }

  public void drawBuildingBeam(float px, float py, BuildWeaponMount mount, Unit unit) {
    float maxDst = state.rules.infiniteResources ? Float.MAX_VALUE : range;
    for (int i = 0; i < mount.plans.size; i++) {
      BuildPlan plan = mount.plans.get(i);
      if (plan == null) continue;
      Tile tile = world.tile(plan.x, plan.y);
      if (tile == null) continue;
      if (Mathf.dst(plan.drawx(), plan.drawy(), px, py) > maxDst) continue;

      int size = plan.breaking ? tile.block().size : plan.block.size;
      Lines.stroke(1f, plan.breaking ? Pal.remove : Pal.accent);
      Draw.z(Layer.buildBeam);
      Draw.alpha(unit.buildAlpha());
      Drawf.buildBeam(px, py, plan.drawx(), plan.drawy(), tilesize * size / 2f);
      Fill.square(px, py, 1.5f + Mathf.absin(Time.time, 2.2f, 0.7f), mount.rotation + 45f);
      Draw.reset();
    }
  }

  private static Object getCommandController(CommandAI ai) {
    try {
      Field f = CommandAI.class.getDeclaredField("commandController");
      f.setAccessible(true);
      return f.get(ai);
    } catch (Exception e) {
      return null;
    }
  }

  public static class BuildWeaponMount extends WeaponMount {
    public Seq<ConstructBlock.ConstructBuild> targets = new Seq<>();
    public Seq<BuildPlan> plans = new Seq<>();
    public float lastError = -999f;

    public BuildWeaponMount(Weapon w) {
      super(w);
    }
  }
}