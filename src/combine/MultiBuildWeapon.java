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
import mindustry.game.Team;
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
    Queue<BuildPlan> selfPlans = unit.plans();
    if (unit.isPlayer() && (selfPlans == null || selfPlans.size == 0)) {
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
    //
    // 【接管的那一格换人了也要放开】玩家用拆除工具点过之后，原版会把这一格**换成一个新的
    // ConstructBuild**（拆除态）；挂座手里攥着的是换掉之前的那个旧对象，继续对它 construct()
    // 照样会作用在同一格上 —— 把拆除又造回去（用户报的"造到一半的建筑拆不掉/反而被造完"）。
    if (m.plan != null && m.target != null) {
      Building cur = world.build(m.plan.x, m.plan.y);
      if (!(cur instanceof ConstructBlock.ConstructBuild) || cur != m.target || m.plan.progress >= 1f) {
        release(m);
      }
    }
    // 【这一格正在被拆】玩家用拆除工具点过之后，格子上的 ConstructBuild 会变成"正在拆"的
    // （current 换成被拆的那个方块 / activeDeconstruct=true）。挂座原先不看这个，
    // 继续对它 construct() —— 把拆除又造回去，表现就是"造到一半的建筑拆不掉/反而被造完"。
    if (m.plan != null && deconstructing(m.target, m.plan)) {
      release(m);
    }
    // 【不许被"造不动"的计划钉死】认领之后核心的料被别的施工者吃光了：原版队首逻辑会跳过
    // 这种计划（BuilderComp.shouldSkip），挂座也得放手，否则几把挂座一起抱着没料的格子空转，
    // 队列里能做的活（修废墟/改方向/有料的）一个都轮不到 —— 用户报的"只修/建了几个"。
    if (m.plan != null && m.target != null && coreLacks(m.plan, unit)) {
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
    // 【必须判空】不能建造的单位（例如核心机之外的战斗单位）、以及某些模组单位根本没有
    // BuilderComp，plans() 会返回 null —— 直接 plans.first()/size 就是
    // "Attempt to invoke ... arc.struct.Seq.first() on a null object reference"（用户报的崩溃）。
    if (plans == null)
      return null;
    if (plans.size > 0) {
      BuildPlan first = plans.first();
      // 【立刻能做完的事优先】修废墟(derelict) 与 原地改方向(rotation) 都不需要材料、也不需要
      // 施工时间：原版 Build.beginPlace 一按就当场完成。它们排在队列后面时（原版 rebuildArea
      // 就是先排"被摧毁建筑"、再排废墟修复），按原来的"只从队首往后认领"要等前面那些慢施工
      // 全部完工才轮得到 —— 表现就是用户报的"修废墟/改方向只动了几个"。
      // 这里单独扫一遍队列：只要是能当场做完的，不管排在第几个都先认领，几把挂座一起分。
      for (int i = 0; i < plans.size; i++) {
        BuildPlan p = plans.get(i);
        if (p == null || !instantPlan(p, weaponUnit.team))
          continue;
        if (!claimable(weaponUnit, tm, p, false, true))
          continue;
        return p;
      }
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
        // 【修废墟不能当成"已经造好"】废墟格上的方块和计划里的方块是同一种（同名同 id），
        // 直接按"b.block == p.block"判会把整框的修复计划全删掉 ——
        // 表现就是"修废墟时一个都不修"（用户报的）。
        if (derelictPlan(p))
          continue;
        Building b = world.build(p.x, p.y);
        // 【改方向也不能当成"已经造好"】原地改方向的计划，格子上本来就是同一种方块
        // （只是方向不同），只按 b.block == p.block 判会在还没转过去时就把计划删掉 ——
        // 表现就是用户报的"批量改传送带方向只改了几个，其它计划直接没了"。
        // 只有"方向也对上了"（或者方块本身不转向）才算这一格已经完成。
        if (b != null && !(b instanceof ConstructBlock.ConstructBuild) && b.block == p.block
            && (!p.block.rotate || b.rotation == p.rotation)) {
          plans.removeIndex(i);
        }
      }
    }

    // FIX[取消建造]: 玩家自己操控的单位只负责"玩家自己排的队"。
    // 空闲时替别人/替队伍计划表施工是给 AI 建造单位的功能；对玩家单位来说，
    // 清空队列就该停手，否则会出现"取消了还在造"。
    if (weaponUnit.isPlayer())
      return null;

    // FIX[敌方自动重建]: 队伍里没有玩家的势力（战役/进攻图里的敌方、沙盒里随便放的敌方队）
    // 只能"帮着造所跟随单位的队列"，不能去认领下面那两条"主动找活"的来源 ——
    // 因为它们的队伍计划表(team.data().plans)正是 BaseBuilderAI 排的基地蓝图
    // （含被摧毁建筑的原地重建）。每把挂座认领一格 = 敌方多线程重建基地，
    // 表现就是玩家报的"敌方建造机自动重建被摧毁的敌方建筑"。
    // 2.1 及之前根本没有这两条来源，所以没有这个现象；玩家队伍（含联机多方）保持原样。
    // 判据用"队伍里有没有玩家"，不用 Team.isOnlyAI()：后者只在有波次/进攻/战役时成立，
    // 沙盒、自定义模式里敌方队伍会被算成"不是 AI"，照样会去认领。
    if (weaponUnit.team != null && weaponUnit.team.data().players.isEmpty()
        && (mindustry.Vars.player == null || mindustry.Vars.player.team() != weaponUnit.team))
      return null;

    // 附近同队单位的队列（玩家排好的预设、其它工程车正在排的活都在这）
    final BuildPlan[] found = {null};
    float radius = range;
    Units.nearby(unit.team, weaponUnit.x, weaponUnit.y, radius, other -> {
      if(found[0] != null || other == null || other == weaponUnit || !other.canBuild())
        return;
      Queue<BuildPlan> q = other.plans();
      if(q == null)
        return;
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
          BuildPlan bp = new BuildPlan(p.x, p.y, p.rotation, p.block, p.config);
          // 用 BuildPlan 版判定：这样"废墟修复"的格子也能被队伍计划表的建造者认领
          if (!claimable(weaponUnit, tm, bp, pass == 0))
            continue;
          return bp;
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
    // 正在被拆的格子不认领（不管建造还是拆除计划）：构造计划认领会把拆除造回去，
    // 拆除计划则由原版/队首那条计划去处理。
    if (constructing && deconstructing((ConstructBlock.ConstructBuild) existing, plan))
      return false;
    // 已经在施工：只在"接手"那一轮允许认领
    if(constructing && freshOnly)
      return false;
    if(plan.breaking){
      // 拆除计划：空气说明已经拆完
      if(existing == null)
        return false;
    }else{
      // 建造计划：已经有别的方块，说明造好了/这里不是该造的东西
      // 例外1 废墟修复 —— 格子上就是衍生物团队的**同名**方块，原版会当场修好，属于"可认领"
      // 例外2 原地改方向 —— 同格同名我方方块、只是方向不同，原版 beginPlace 会当场 quickRotate，
      //       也属于"可认领"（否则批量改方向时挂座一个都认不了，计划还被当成"已造好"删掉）
      // 例外3 【覆盖建造】格子上是**别的**方块（同类同尺寸、可被替换）——原版
      //       Build.beginPlace 就是"拆掉旧的、盖上新的"（用户报的"在墙上覆盖新的墙建造时
      //       多线程建造武器不工作"：拖一片新墙盖旧墙，所有挂座都把这种计划当成"这格已经造好了"
      //       拒收，只剩单位自己的建造逻辑一秒一格 → 看着像"只能单线程"）。
      if(existing != null && !constructing && !instantPlan(plan, weaponUnit.team)){
        if(existing.team != weaponUnit.team || existing.block == plan.block)
          return false;
        // 到底能不能盖（方块替换规则、组/尺寸、地形都在里面）交给原版判一次
        if(!Build.validPlace(plan.block, weaponUnit.team, plan.x, plan.y, plan.rotation))
          return false;
      }
    }

    // 核心现在拿不出料的计划不认领（和原版 BuilderComp.shouldSkip 同一个判据）：
    // 不然后面能造的（有料的新格子）全被几把"抱着没料计划空转"的挂座堵住。
    if(coreLacks(plan, weaponUnit))
      return false;

    if(!allowOwn){
      BuildPlan own = weaponUnit.buildPlan();
      if(own != null && own.x == plan.x && own.y == plan.y)
        return false;
    }
    return notClaimedByOtherMount(weaponUnit, tm, plan.x, plan.y);
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
      // 正在被拆的格子不接手（不然会把拆除又造回去）
      if (deconstructing(cb, plan)) {
        m.plan = null;
        m.target = null;
        return;
      }
      m.target = cb;
      m.plan = plan;
      plan.initialized = true;
      return;
    }

    // 修废墟 / 原地改方向：原版 beginPlace 会**当场**做完（不经过施工阶段、不要材料）。
    // 这里不需要接住什么 ConstructBuild，处理完就把这格松开，
    // 下一帧再去找别的格子（多把武器因此能同时修/转好几格）。
    if (instantPlan(plan, unit.team)) {
      if (Build.validPlace(plan.block, unit.team, plan.x, plan.y, plan.rotation)) {
        Build.beginPlace(unit, plan.block, unit.team, plan.x, plan.y, plan.rotation, plan.config);
      }
      m.plan = null;
      m.target = null;
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
      }else{
        // 这一格现在放不下（比如有单位压着格子）：别每帧原地重试，退一下再看别的活
        m.searchCooldown = searchRetryInterval;
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

  /**
   * 这一格是不是"修废墟"：team=derelict 的**同名**方块。
   *
   * 原版 Build.beginPlace 遇到这种格子会**当场把它变成自己的建筑**（不经过施工阶段、
   * 也不要材料），所以：
   *   · 它不能算"已经造好"（清残留计划时不能删它，删了玩家排的框就再也不会被修）；
   *   · 它也应该能被本模组的建造武器认领，这样几把武器能同时修好几格（用户要的"立刻全修好"）。
   */
  static boolean derelictPlan(BuildPlan plan){
    if(plan == null || plan.breaking || plan.block == null)
      return false;
    Tile tile = world.tile(plan.x, plan.y);
    return tile != null && tile.team() == mindustry.game.Team.derelict && tile.block() == plan.block
        && tile.build != null && plan.block.allowDerelictRepair && state.rules.derelictRepair;
  }

  /**
   * 这一格是不是"原地改方向"：同格、同名、我方方块，只是方向对不上。
   *
   * 原版 Build.beginPlace 对"自己队伍的同名方块"走的是 quickRotate 分支（当场把方向改掉、
   * 不经过施工阶段也不要材料），玩家拖一排传送带改方向时生成的计划就是这一种。
   * 它和 {@link #derelictPlan} 一样是"当场能做掉"的活，所以：
   *   · 不能算"已经造好"（格子上本来就是同名方块，只按方块名判会在还没转过去时就删掉计划）；
   *   · 也要能被建造武器认领 —— 不然批量改方向只有队首那一格会被原版一秒一个地转过去。
   */
  static boolean rotationPlan(BuildPlan plan, Team team){
    if(plan == null || plan.breaking || plan.block == null || !plan.block.rotate)
      return false;
    Tile tile = world.tile(plan.x, plan.y);
    return tile != null && tile.team() == team && tile.block() == plan.block && tile.build != null
        && tile.build.rotation != plan.rotation;
  }

  /** 当场就能做完、不要材料的那一类计划：修废墟 + 原地改方向。 */
  static boolean instantPlan(BuildPlan plan, Team team){
    return derelictPlan(plan) || rotationPlan(plan, team);
  }

  /**
   * 核心现在拿不出这个计划的料（和原版 {@code BuilderComp.shouldSkip} 同一个判据）。
   *
   * 挂座原先是"认领了就抱着不放"，几把挂座全被没料的计划占住之后，队列里能做的
   * （修废墟、改方向、有料的格子）一个都轮不到，表现就是"重建只动了几个"。
   */
  static boolean coreLacks(BuildPlan plan, Unit unit){
    if(plan == null || plan.breaking || plan.block == null || plan.block.requirements.length == 0)
      return false;
    // 修废墟 / 改方向不要材料，别把它们也挡掉
    if(instantPlan(plan, unit.team))
      return false;
    if(state.rules.infiniteResources || (unit.team != null && unit.team.rules().infiniteResources))
      return false;
    // 用"离这台单位最近的核心"，和原版 BuilderComp 的取法一致（多核心地图里各自看各自的料）
    Building core = unit.closestCore();
    if(core == null)
      return false;
    return arc.util.Structs.contains(plan.block.requirements, i ->
        !core.items.has(i.item, Math.min(i.amount, 15))
            && Mathf.round(i.amount * state.rules.buildCostMultiplier) > 0);
  }

  /**
   * 这一格是不是"正在被拆"。
   *
   * 玩家用拆除工具点过之后，格子上的 ConstructBuild 会切到拆除模式：
   *   · {@code activeDeconstruct} 为真（正在被拆的那个 tick 起）；
   *   · 或者它的 {@code current} 已经换成"被拆掉的那个方块"，对不上建造计划里的方块。
   * 这两种情况下都不该再对它 construct()（那等于把拆除又造回去，用户报的"拆不掉/反而被造完"）。
   */
  static boolean deconstructing(ConstructBlock.ConstructBuild cb, @arc.util.Nullable BuildPlan plan){
    if(cb == null)
      return false;
    if(cb.activeDeconstruct)
      return true;
    return plan != null && !plan.breaking && plan.block != null && cb.current != plan.block;
  }

  /** 计划被队首占用 / 被其他挂座抢了就放弃重找。 */
  boolean isRob(Unit unit, BuildWeaponMount m) {
    Queue<BuildPlan> plans = unit.plans();
    if (plans == null)
      return true;
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
    if (plans == null)
      return;
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
