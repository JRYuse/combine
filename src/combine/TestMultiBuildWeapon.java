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
import mindustry.Vars;
import mindustry.ai.types.BuilderAI;
import mindustry.ai.types.CommandAI;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.units.BuildPlan;
import mindustry.entities.units.UnitController;
import mindustry.entities.units.WeaponMount;
import mindustry.entities.Units;
import mindustry.game.Gamemode;
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
  /** 该挂座同时建造/拆除的建筑数量上限（Settings.maxBuild() <= 0 时使用本字段）。 */
  public int maxBuild = 5;
  /** 找活失败后的重试间隔（秒）。空闲挂座不必每帧都去附近单位里翻施工队列。 */
  public float searchRetryInterval = 0.15f;

  public TestMultiBuildWeapon() {
    rotate = true;
    noAttack = true;
    predictTarget = false;
    display = false;
    bullet = new BulletType();
    useAttackRange = false;
    mountType = BuildWeaponMount::new;
  }

  /** 实际生效的并行上限：Settings 里 > 0 用手动值，0（=自动）回落武器自带 maxBuild。 */
  int getMaxBuild() {
    int s = Settings.maxBuild();
    return s > 0 ? s : maxBuild;
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
    // FIX[建造启停]: E 键 / 手机端"暂停建造"翻的是 unit.updateBuilding()。
    // 多挂座必须统一看它，否则按 E 只停第一个建造线程，其它挂座还在造。
    if (!unit.updateBuilding()) {
      releaseAll(m);
      aimForward(unit, m);
      m.shoot = false;
      m.rotate = true;
      super.update(unit, m);
      return;
    }

    // FIX[取消建造]: 玩家单位队列被清空（Q = clearBuilding）就该彻底停手，
    // 否则挂座会继续从附近单位/队伍计划表里找活 —— 表现就是"取消了还在造"。
    if (unit.isPlayer() && unit.plans().size == 0) {
      releaseAll(m);
      aimForward(unit, m);
      m.shoot = false;
      m.rotate = true;
      super.update(unit, m);
      return;
    }

    // 施工队列的主人：跟随谁就优先辅助谁；没人可跟时用自己
    Unit targetUnit = resolveTargetUnit(unit);

    // 1) 清理：造完/失效/被抢/超射程的格子放开
    float max = state.rules.infiniteResources ? Float.MAX_VALUE : range;
    for (int i = m.plans.size - 1; i >= 0; i--) {
      BuildPlan plan = m.plans.get(i);
      if (plan == null) {
        releaseIndex(m, i);
        continue;
      }
      Building b = world.build(plan.x, plan.y);
      boolean done = !(b instanceof ConstructBlock.ConstructBuild) || plan.progress >= 1f;
      boolean outOfRange = Mathf.dst(unit.x, unit.y, plan.x * 8f, plan.y * 8f) > max;
      if (done || outOfRange || isRob(unit, m, plan)) {
        releaseIndex(m, i);
      }
    }

    // 2) 补位到 maxBuild（带冷却：空闲挂座原先每帧都做一次附近搜索，
    //    工程车一多就把帧数吃光 —— 加 searchRetryInterval 冷却）
    if (m.plans.size < getMaxBuild()) {
      if (m.searchCooldown > 0f) {
        m.searchCooldown -= Time.delta;
      } else {
        int before = m.plans.size;
        findTargets(targetUnit, unit, m);
        if (m.plans.size == before) m.searchCooldown = searchRetryInterval;
      }
    } else {
      m.searchCooldown = 0f;
    }

    // 3) 并行施工
    if (m.plans.size > 0) {
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

    // 4) 瞄准：指向第一个计划
    if (m.plans.size > 0) {
      BuildPlan plan = m.plans.first();
      m.aimX = plan.drawx();
      m.aimY = plan.drawy();
    } else {
      aimForward(unit, m);
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

  /** 没活干时的瞄准方向：朝正前方（和原版空手时一致） */
  void aimForward(Unit unit, BuildWeaponMount m) {
    Tmp.v2.trns(unit.rotation(), 800f);
    m.aimX = Tmp.v2.x + unit.x;
    m.aimY = Tmp.v2.y + unit.y;
  }

  /** 放弃本挂座全部认领（计划本身还留给别的施工者）。 */
  void releaseAll(BuildWeaponMount m) {
    for (int i = 0; i < m.plans.size; i++) {
      BuildPlan p = m.plans.get(i);
      if (p != null) p.initialized = false;
    }
    m.plans.clear();
    m.targets.clear();
  }

  /** 按索引放弃一格。 */
  void releaseIndex(BuildWeaponMount m, int i) {
    if (i < 0 || i >= m.plans.size) return;
    BuildPlan plan = m.plans.remove(i);
    m.targets.remove(i);
    if (plan != null) plan.initialized = false;
  }

  /** 循环认领直到达到上限或没有可认领的格子。 */
  void findTargets(Unit unit, Unit weaponUnit, BuildWeaponMount m) {
    int limit = getMaxBuild();
    int guard = 0;
    while (m.plans.size < limit && guard++ < limit) {
      BuildPlan plan = findPlan(unit, weaponUnit, m);
      if (plan == null) break;
      if (!claimPlan(unit, m, plan)) break;
    }
  }

  /** 尝试认领一格（建造或拆除）。返回是否成功认领。 */
  boolean claimPlan(Unit unit, BuildWeaponMount m, BuildPlan plan) {
    Building existing = world.build(plan.x, plan.y);
    // 已经在施工：直接接手，不能走 Build.validPlace —— 它遇到已有 ConstructBuild 会返回 false，
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
      // 认领顺序（队首始终留给单位自己的建造逻辑）：
      //   1. 队首以外已经在施工的格子（一把武器帮一格，几把就分摊到几格）
      //   2. 队首以外还没开工的格子（开新格）
      //   3. 都没有时，才允许帮队首那格一起造（避免多把武器全空转）
      for (int pass = 0; pass < 2; pass++) {
        boolean assist = pass == 0;
        for (int i = 1; i < plans.size; i++) {
          BuildPlan p = plans.get(i);
          if (p == null) continue;
          if (assist && !(world.build(p.x, p.y) instanceof ConstructBlock.ConstructBuild)) continue;
          if (!assist && world.build(p.x, p.y) instanceof ConstructBlock.ConstructBuild) continue;
          if (!claimable(weaponUnit, tm, p, !assist, false)) continue;
          return p;
        }
      }
      // 兜底：队列里只剩队首那一格（或其余都够不着）时，允许帮它一起造
      if (first != null && claimable(weaponUnit, tm, first,
              !(world.build(first.x, first.y) instanceof ConstructBlock.ConstructBuild), true)) {
        return first;
      }

      // 顺手清掉"已经造好"的残留计划，别让它一直堵在队列里（原版只清队首）
      for (int i = plans.size - 1; i >= 0; i--) {
        BuildPlan p = plans.get(i);
        if (p == null || p.breaking || p.block == null) continue;
        Building b = world.build(p.x, p.y);
        if (b != null && !(b instanceof ConstructBlock.ConstructBuild) && b.block == p.block) {
          plans.removeIndex(i);
        }
      }
    }

    // FIX[取消建造]: 玩家单位只负责"玩家自己排的队"，空闲时替别人施工是给 AI 单位的功能。
    if (weaponUnit.isPlayer()) return null;

    // FIX[敌方自动重建]: 队伍里没有玩家的势力（战役/进攻图里的敌方、沙盒里随便放的敌方队）
    // 只能帮着造"所跟随单位的队列"，不能去认领队伍计划表 —— 那是 BaseBuilderAI 排的基地蓝图
    // （含被摧毁建筑的原地重建），每把挂座认领一格 = 敌方多线程重建基地。
    // 判据用"队伍里有没有玩家"，不用 Team.isOnlyAI()：后者只在有波次/进攻/战役时成立，
    // 沙盒、自定义模式里敌方队伍会被算成"不是 AI"，照样会去认领。
    if (weaponUnit.team != null && weaponUnit.team.data().players.isEmpty()
            && (mindustry.Vars.player == null || mindustry.Vars.player.team() != weaponUnit.team))
      return null;

    // 附近同队单位的队列（玩家排好的预设、其它工程车正在排的活都在这）
    final BuildPlan[] found = {null};
    float radius = range;
    Units.nearby(unit.team, weaponUnit.x, weaponUnit.y, radius, other -> {
      if (found[0] != null || other == null || other == weaponUnit || !other.canBuild()) return;
      Queue<BuildPlan> q = other.plans();
      for (int pass = 0; pass < 2 && found[0] == null; pass++) {
        boolean assist = pass == 0;
        for (int i = 1; i < q.size; i++) { // 队首归它自己，跳过
          BuildPlan p = q.get(i);
          if (p == null) continue;
          if (assist && !(world.build(p.x, p.y) instanceof ConstructBlock.ConstructBuild)) continue;
          if (claimable(weaponUnit, tm, p, !assist, assist)) {
            found[0] = p;
            return;
          }
        }
      }
    });
    if (found[0] != null) return found[0];

    if (unit.team != null) {
      Queue<BlockPlan> teamPlans = unit.team.data().plans;
      BuildPlan own = weaponUnit.buildPlan();
      for (int pass = 0; pass < 2; pass++) {
        for (int i = 0; i < teamPlans.size; i++) {
          BlockPlan p = teamPlans.get(i);
          if (p == null || p.block == null) continue;
          if (own != null && own.x == p.x && own.y == p.y) continue; // 自己正在造的那格
          if (!claimable(weaponUnit, tm, p.x, p.y, pass == 0)) continue;
          return new BuildPlan(p.x, p.y, p.rotation, p.block, p.config);
        }
      }
    }
    return null;
  }

  /** 查询用：不看是否已开工，只问"这格能不能被认领" */
  boolean claimable(Unit weaponUnit, BuildWeaponMount tm, BuildPlan plan) {
    return claimable(weaponUnit, tm, plan, false, false);
  }

  boolean claimable(Unit weaponUnit, BuildWeaponMount tm, BuildPlan plan, boolean freshOnly) {
    return claimable(weaponUnit, tm, plan, freshOnly, false);
  }

  /** @param allowOwn 是否允许认领"单位自己队首那格"（帮它一起造） */
  boolean claimable(Unit weaponUnit, BuildWeaponMount tm, BuildPlan plan, boolean freshOnly, boolean allowOwn) {
    if (plan == null || plan.block == null) return false;
    if (!weaponUnit.within(plan.x * 8f, plan.y * 8f, range) && !(state.rules.mode() == Gamemode.sandbox)) return false;

    Building existing = world.build(plan.x, plan.y);
    boolean constructing = existing instanceof ConstructBlock.ConstructBuild;
    if (constructing && freshOnly) return false;
    if (plan.breaking) {
      if (existing == null) return false;
    } else {
      if (existing != null && !constructing) return false;
    }

    if (!allowOwn) {
      BuildPlan own = weaponUnit.buildPlan();
      if (own != null && own.x == plan.x && own.y == plan.y) return false;
    }
    // FIX[多格重复认领]: 去重也要在 BuildPlan 版本上做一次，
    // 否则循环 findTargets 时同一格会被反复塞进 plans（第一格被 findPlan 反复返回）。
    if (containsPlan(tm, plan.x, plan.y)) return false;
    return notClaimedByOtherMount(weaponUnit, tm, plan.x, plan.y);
  }

  boolean claimable(Unit weaponUnit, BuildWeaponMount tm, int x, int y) {
    return claimable(weaponUnit, tm, x, y, true);
  }

  boolean claimable(Unit weaponUnit, BuildWeaponMount tm, int x, int y, boolean freshOnly) {
    if (!weaponUnit.within(x * 8f, y * 8f, range)) return false;

    Building existing = world.build(x, y);
    boolean constructing = existing instanceof ConstructBlock.ConstructBuild;
    if (constructing && freshOnly) return false;
    if (existing != null && !constructing) return false;

    BuildPlan own = weaponUnit.buildPlan();
    if (own != null && own.x == x && own.y == y) return false;

    if (containsPlan(tm, x, y)) return false;
    return notClaimedByOtherMount(weaponUnit, tm, x, y);
  }

  /** 本挂座是否已认领这格（按坐标判，避免依赖 BuildPlan 对象相等）。 */
  boolean containsPlan(BuildWeaponMount tm, int x, int y) {
    for (int i = 0; i < tm.plans.size; i++) {
      BuildPlan p = tm.plans.get(i);
      if (p != null && p.x == x && p.y == y) return true;
    }
    return false;
  }

  /** 同一格没有被本机别的建造挂座认领 */
  boolean notClaimedByOtherMount(Unit weaponUnit, BuildWeaponMount tm, int x, int y) {
    for (WeaponMount mount : weaponUnit.mounts()) {
      if (mount instanceof BuildWeaponMount bm && bm != tm) {
        for (int i = 0; i < bm.plans.size; i++) {
          BuildPlan p = bm.plans.get(i);
          if (p != null && p.x == x && p.y == y) return false;
        }
      }
    }
    return true;
  }

  /** 该格是否被队首 / 其它挂座抢走（该放弃重找）。 */
  boolean isRob(Unit unit, BuildWeaponMount m, BuildPlan plan) {
    if (plan == null) return true;
    for (WeaponMount mount : unit.mounts) {
      if (mount instanceof BuildWeaponMount bm && bm != m) {
        for (int i = 0; i < bm.plans.size; i++) {
          if (bm.plans.get(i) == plan) return true;
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
        if (wm == m) idx = total;
        total++;
      }
    }
    if (total == 0) return;
    float rotation = unit.rotation - 90f;
    float px = unit.x + Angles.trnsx(rotation, this.x, this.y);
    float py = unit.y + Angles.trnsy(rotation, this.x, this.y);

    var core = unit.core();

    // 本挂座自己认领的每一格：必须画
    drawBuildingBeam(px, py, m, unit);

    // 单位自己队列里、由本挂座负责的其它格子
    Queue<BuildPlan> plans = unit.plans();
    for (int i = 1; i < plans.size; i++) {
      if ((i - 1) % total != idx) continue;
      BuildPlan plan = plans.get(i);
      if (m.plans.contains(plan)) continue;
      Tile tile = world.tile(plan.x, plan.y);
      if (!(tile.build instanceof ConstructBlock.ConstructBuild)) continue;

      boolean worked = false;
      for (WeaponMount wm : unit.mounts) {
        if (wm instanceof BuildWeaponMount bm) {
          int pidx = bm.plans.indexOf(plan);
          if (pidx >= 0 && bm.targets.get(pidx) != null) { worked = true; break; }
        }
      }
      if (!worked) continue;

      if (!state.rules.infiniteResources && unit.shouldSkip(plan, core)) continue;

      float maxDst = state.rules.infiniteResources ? Float.MAX_VALUE : unit.type.buildRange;
      if (!unit.within(plan, maxDst)) continue;

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
    if (commandControllerField == null) return null;
    try {
      return commandControllerField.get(ai);
    } catch (Exception e) {
      return null;
    }
  }

  /** 反射字段只查一次：每帧每挂座都要走的路径。 */
  private static final Field commandControllerField = findCommandControllerField();

  private static Field findCommandControllerField() {
    try {
      Field f = CommandAI.class.getDeclaredField("commandController");
      f.setAccessible(true);
      return f;
    } catch (Throwable t) {
      return null;
    }
  }

  public static class BuildWeaponMount extends WeaponMount {
    public Seq<ConstructBlock.ConstructBuild> targets = new Seq<>();
    public Seq<BuildPlan> plans = new Seq<>();
    public float lastError = -999f;
    /** 找活失败后的冷却（秒），避免空闲挂座每帧都做空间查询。 */
    public float searchCooldown = 0f;

    public BuildWeaponMount(Weapon w) {
      super(w);
    }
  }
}