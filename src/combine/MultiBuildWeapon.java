package combine;

import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.Queue;
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

public class MultiBuildWeapon extends Weapon {
  public float range = 236f;
  /** 该挂座施工速度倍率。 */
  public float speedMulti = 1f;
  /** 找活失败后的重试间隔（秒）。空闲挂座不必每帧都去附近单位里翻施工队列。 */
  public float searchRetryInterval = 0.15f;

  public MultiBuildWeapon() {
    rotate = true;
    noAttack = true;
    predictTarget = false;
    display = false;
    bullet = new BulletType();
    useAttackRange = false;
    mountType = BuildWeaponMount::new;
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
    // FIX[建造启停]: 原版 E 键(Binding.pauseBuilding) / 手机端的"暂停建造"是给单位翻
    // updateBuilding 这个开关；原版那第一把建造武器会照做，但本模组多出来的挂座原先不看它 ——
    // 表现就是"按 E 只停第一个建造线程，其它挂座还在造"。这里所有挂座统一看这个开关。
    if (!unit.updateBuilding()) {
      release(m);
      aimForward(unit, m);
      m.shoot = false;
      m.rotate = true;
      super.update(unit, m);
      return;
    }

    // FIX[取消建造]: 玩家操控的单位一旦队列被清空（原版 Q = clearBuilding），
    // 就该彻底停手；原先挂座会继续从"附近单位的队列 / 队伍计划表"里找活，
    // 表现就是"取消了建造、光束也没了，但格子还在造"。
    if (unit.isPlayer() && unit.plans().size == 0) {
      release(m);
      aimForward(unit, m);
      m.shoot = false;
      m.rotate = true;
      super.update(unit, m);
      return;
    }

    // 施工队列的主人：跟随谁就优先辅助谁；没人可跟时用自己
    Unit targetUnit = resolveTargetUnit(unit);

    check(unit, m);

    // 这一格造完/失效了就放开，下面重新认领一格
    if (m.plan != null && m.target != null
        && (!(world.build(m.plan.x, m.plan.y) instanceof ConstructBlock.ConstructBuild) || m.plan.progress >= 1f)) {
      release(m);
    }

    // 不要求"主人正在施工"：空闲的建造单位也该主动去造队列/预设里的建筑
    findTarget(targetUnit, unit, m);

    if (m.target != null && m.plan != null) {
      float bs = 1f / m.target.buildCost * unit.type.buildSpeed
          * unit.buildSpeedMultiplier * state.rules.buildSpeed(unit.team) * speedMulti * Time.delta;
      if (m.plan.breaking) {
        m.target.deconstruct(unit, unit.team.core(), bs);
        m.plan.progress = m.target.progress;
      } else {
        m.target.construct(unit, unit.team.core(), bs, m.plan.config);
        m.plan.progress = m.target.progress;
      }
    }

    if (m.plan != null) {
      m.aimX = m.plan.drawx();
      m.aimY = m.plan.drawy();
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

  /** 没活干时的瞄准方向：朝正前方（和原版空手时一致） */
  void aimForward(Unit unit, BuildWeaponMount m) {
    Tmp.v2.trns(unit.rotation(), 800f);
    m.aimX = Tmp.v2.x + unit.x;
    m.aimY = Tmp.v2.y + unit.y;
  }

  /** 放弃当前认领（计划本身还留给别的施工者） */
  void release(BuildWeaponMount m) {
    m.target = null;
    if (m.plan != null)
      m.plan.initialized = false;
    m.plan = null;
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
      //   1. 队首**以外**已经在施工的格子（一把武器帮一格，几把就分摊到几格）
      //   2. 队首以外还没开工的格子（开新格）
      //   3. 都没有时，才允许帮队首那格一起造（避免多把武器全空转）
      // 第 1 步以前是允许连队首一起认领的，于是"1 把原版武器 + N 把本模组武器"里会有一把
      // 和原版抢同一格 —— 同时施工的建筑数变成 N 而不是 N+1（beta 那种 core.size+1 的设计
      // 就只能同时造 core.size 个）。
      for (int pass = 0; pass < 2; pass++) {
        boolean assist = pass == 0;
        for (int i = 1; i < plans.size; i++) {
          BuildPlan p = plans.get(i);
          if (p == null)
            continue;
          if (assist && !(world.build(p.x, p.y) instanceof ConstructBlock.ConstructBuild))
            continue; // 这一轮只认"已经开工"的
          if (!assist && world.build(p.x, p.y) instanceof ConstructBlock.ConstructBuild)
            continue; // 这一轮只认"还没开工"的
          if (!claimable(weaponUnit, tm, p, !assist, false))
            continue;
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
        if (p == null || p.breaking || p.block == null)
          continue;
        Building b = world.build(p.x, p.y);
        if (b != null && !(b instanceof ConstructBlock.ConstructBuild) && b.block == p.block) {
          plans.removeIndex(i);
        }
      }
    }

    // FIX[取消建造]: 玩家自己操控的单位只负责"玩家自己排的队"。
    // 空闲时替别人/替队伍计划表施工是给 AI 建造单位的功能；对玩家单位来说，
    // 清空队列就该停手，否则会出现"取消了还在造"。
    if (weaponUnit.isPlayer())
      return null;

    // FIX[敌方自动重建]: 纯 AI 势力（战役/进攻图里的敌方队伍，队伍里没有玩家）
    // 只能"帮着造所跟随单位的队列"，不能去认领下面那两条"主动找活"的来源 ——
    // 因为它们的队伍计划表(team.data().plans)正是 BaseBuilderAI 排的基地蓝图
    // （含被摧毁建筑的原地重建）。每把挂座认领一格 = 敌方多线程重建基地，
    // 表现就是玩家报的"敌方建造机自动重建被摧毁的敌方建筑"。
    // 2.1 及之前根本没有这两条来源，所以没有这个现象；玩家队伍（含联机多方）保持原样。
    if (weaponUnit.team != null && weaponUnit.team.isOnlyAI())
      return null;

    // 附近同队单位的队列（玩家排好的预设、其它工程车正在排的活都在这）
    final BuildPlan[] found = {null};
    float radius = range;
    Units.nearby(unit.team, weaponUnit.x, weaponUnit.y, radius, other -> {
      if(found[0] != null || other == null || other == weaponUnit || !other.canBuild())
        return;
      Queue<BuildPlan> q = other.plans();
      for(int pass = 0; pass < 2 && found[0] == null; pass++){
        boolean assist = pass == 0;
        for(int i = 1; i < q.size; i++){ // 队首归它自己，跳过
          BuildPlan p = q.get(i);
          if(p == null) continue;
          if(assist && !(world.build(p.x, p.y) instanceof ConstructBlock.ConstructBuild)) continue;
          if(claimable(weaponUnit, tm, p, !assist, assist)){
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
    return claimable(weaponUnit, tm, plan, freshOnly, false);
  }

  /** @param allowOwn 是否允许认领"单位自己队首那格"（帮它一起造） */
  boolean claimable(Unit weaponUnit, BuildWeaponMount tm, BuildPlan plan, boolean freshOnly, boolean allowOwn){
    if(plan == null || plan.block == null)
      return false;
    if(!weaponUnit.within(plan.x * 8f, plan.y * 8f, range))
      return false;

    Building existing = world.build(plan.x, plan.y);
    boolean constructing = existing instanceof ConstructBlock.ConstructBuild;
    // 已经在施工：只在"接手"那一轮允许认领
    if(constructing && freshOnly)
      return false;
    if(plan.breaking){
      // 拆除计划：空气说明已经拆完
      if(existing == null)
        return false;
    }else{
      // 建造计划：已经有别的方块，说明造好了/这里不是该造的东西
      if(existing != null && !constructing)
        return false;
    }

    if(!allowOwn){
      BuildPlan own = weaponUnit.buildPlan();
      if(own != null && own.x == plan.x && own.y == plan.y)
        return false;
    }
    return notClaimedByOtherMount(weaponUnit, tm, plan.x, plan.y);
  }

  /**
   * 这一格能不能认领：射程内、没开工、没被自己身上其它挂座认领、
   * 也不是自己正在造的那格（队首由单位自己的建造逻辑负责）。
   */
  boolean claimable(Unit weaponUnit, BuildWeaponMount tm, int x, int y) {
    return claimable(weaponUnit, tm, x, y, true);
  }

  /**
   * 这一格能不能认领。
   *
   * @param freshOnly true = 只认领还没开工的格子；false = 也允许接手已经存在的 ConstructBuild
   *                  （原版 BuilderComp 就是靠"tile.build instanceof ConstructBuild 就直接继续"
   *                  来接手的，只靠 Build.validPlace() 会因为 ConstructBuild 已存在而被拒，
   *                  结果格子开工后没人继续施工、进度卡死）。
   */
  boolean claimable(Unit weaponUnit, BuildWeaponMount tm, int x, int y, boolean freshOnly) {
    if (!weaponUnit.within(x * 8f, y * 8f, range))
      return false;

    Building existing = world.build(x, y);
    boolean constructing = existing instanceof ConstructBlock.ConstructBuild;
    // 已经在施工：只有允许接手时才行
    if (constructing && freshOnly)
      return false;
    // 已经是别的方块了（造完了/不是计划中的东西），跳过
    if (existing != null && !constructing)
      return false;

    BuildPlan own = weaponUnit.buildPlan();
    if (own != null && own.x == x && own.y == y)
      return false;

    return notClaimedByOtherMount(weaponUnit, tm, x, y);
  }

  /** 同一格没有被本机别的建造挂座认领 */
  boolean notClaimedByOtherMount(Unit weaponUnit, BuildWeaponMount tm, int x, int y){
    for (WeaponMount mount : weaponUnit.mounts()) {
      if (mount instanceof BuildWeaponMount bm && bm != tm && bm.plan != null
          && bm.plan.x == x && bm.plan.y == y)
        return false;
    }
    return true;
  }

  void check(Unit unit, BuildWeaponMount m) {
    if (m.plan != null && Mathf.dst(unit.x, unit.y, m.plan.x * 8f,
        m.plan.y * 8f) > (state.rules.infiniteResources ? Float.MAX_VALUE : range)) {
      release(m);
    }
  }

  void findTarget(Unit unit, Unit weaponUnit, BuildWeaponMount m) {
    // 被队首占用/被别的挂座抢走：立刻放手（这一步每帧都要做，很便宜）
    if (m.plan != null && m.target != null && isRob(unit, m)) {
      release(m);
    }
    // 手上已经有活，不重新找
    if (m.plan != null && m.target != null)
      return;

    // 找活失败后的冷却：空闲的建造挂座原先每帧都会做一次附近单位的空间查询
    // （Units.nearby + 逐单位遍历施工队列），工程车一多就把帧数吃光。
    if (m.searchCooldown > 0f) {
      m.searchCooldown -= Time.delta;
      return;
    }

    m.plan = null;
    m.target = null;
    BuildPlan plan = findPlan(unit, weaponUnit, m);
    if (plan == null) {
      m.searchCooldown = searchRetryInterval;
      return;
    }
    m.searchCooldown = 0f;

    Building existing = world.build(plan.x, plan.y);
    // 已经在施工(ConstructBuild 已存在)：直接接手继续造/继续拆，
    // 不能走 Build.validPlace —— 它遇到已有 ConstructBuild 会返回 false，
    // 那样格子开工之后就永远没人接着施工，进度会卡死。
    if (existing instanceof ConstructBlock.ConstructBuild cb) {
      m.target = cb;
      m.plan = plan;
      plan.initialized = true;
      return;
    }

    if (!plan.breaking) {
      if (Build.validPlace(plan.block, unit.team, plan.x, plan.y, plan.rotation)) {
        Build.beginPlace(unit, plan.block, unit.team, plan.x, plan.y, plan.rotation, plan.config);
        Building build = world.build(plan.x, plan.y);
        if (build instanceof ConstructBlock.ConstructBuild cb) {
          m.target = cb;
          m.plan = plan;
          plan.initialized = true;
        }
      }
    } else if (Build.validBreak(unit.team, plan.x, plan.y)) {
      Build.beginBreak(unit, unit.team, plan.x, plan.y);
      Building build = world.build(plan.x, plan.y);
      if (build instanceof ConstructBlock.ConstructBuild cb) {
        m.target = cb;
        m.plan = plan;
        plan.initialized = true;
      }
    }
  }

  /** 计划被队首占用 / 被其他挂座抢了就放弃重找。 */
  boolean isRob(Unit unit, BuildWeaponMount m) {
    Queue<BuildPlan> plans = unit.plans();
    // 没有认领，或者认领的正好是队首（队首归主人自己的建造逻辑）→ 放弃重找
    if (m.plan == null || (plans.size > 0 && plans.first() == m.plan))
      return true;
    for (WeaponMount mount : unit.mounts) {
      if (mount instanceof BuildWeaponMount bm && bm != m && bm.target == m.target) {
        return true;
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

    // 本挂座自己认领的那一格：必须画（poly 这类单位自己的队列只有"正在辅助的那格"，
    // 光靠下面的队列轮询是画不出武器认领的格子的）
    drawBuildingBeam(px, py, m, unit);

    // 单位自己队列里、由本挂座负责的其它格子（核心机这种整串队列就在自己身上）
    Queue<BuildPlan> plans = unit.plans();
    for (int i = 1; i < plans.size; i++) {
      if ((i - 1) % total != idx)
        continue;
      BuildPlan plan = plans.get(i);
      if (plan == m.plan)
        continue; // 上面已经画过
      Tile tile = world.tile(plan.x, plan.y);
      if (!(tile.build instanceof ConstructBlock.ConstructBuild))
        continue;

      boolean worked = false;
      for (WeaponMount wm : unit.mounts) {
        if (wm instanceof BuildWeaponMount bm && bm.plan == plan && bm.target != null) {
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
    BuildPlan plan = mount.plan;
    if (plan != null) {
      Tile tile = world.tile(plan.x, plan.y);
      if (tile != null && Mathf.dst(plan.drawx(), plan.drawy(), px,
          py) <= (state.rules.infiniteResources ? Float.MAX_VALUE : range)) {
        int size = plan.breaking ? tile.block().size : plan.block.size;
        Lines.stroke(1f, plan.breaking ? Pal.remove : Pal.accent);
        Draw.z(Layer.buildBeam);
        Draw.alpha(unit.buildAlpha());
        Drawf.buildBeam(px, py, plan.drawx(), plan.drawy(), tilesize * size / 2f);
        Fill.square(px, py, 1.5f + Mathf.absin(Time.time, 2.2f, 0.7f), mount.rotation + 45f);
        Draw.reset();
      }
    }
  }

  private static Object getCommandController(CommandAI ai) {
    if (commandControllerField == null)
      return null;
    try {
      return commandControllerField.get(ai);
    } catch (Exception e) {
      return null;
    }
  }

  /** 反射字段只查一次：这个方法是每帧每挂座都要走的。 */
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
    public ConstructBlock.ConstructBuild target;
    public BuildPlan plan;
    public float lastError = -999f;
    /** 找活失败后的冷却（秒），避免空闲挂座每帧都做空间查询。 */
    public float searchCooldown = 0f;

    public BuildWeaponMount(Weapon w) {
      super(w);
    }
  }
}
