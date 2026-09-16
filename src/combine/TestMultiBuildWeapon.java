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
import mindustry.ai.UnitCommand;
import mindustry.ai.types.BuilderAI;
import mindustry.ai.types.CommandAI;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.units.BuildPlan;
import mindustry.entities.units.UnitController;
import mindustry.entities.units.WeaponMount;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.Weapon;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock;

import java.lang.reflect.Field;

import static mindustry.Vars.*;

public class TestMultiBuildWeapon extends Weapon {
  public float range = 236f;
  /** 施工速度倍率。 */
  public float speedMulti = 1f;
//  /** 同时建造/拆除的建筑数量上限。 */
//  public int maxPlans = 45;

  public TestMultiBuildWeapon() {
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
    Unit targetUnit = null;
    UnitController controller = unit.controller();
    if (controller instanceof CommandAI commandAI) {
      // 命令模式下的辅助/重建命令：找到其内部的 BuilderAI 跟随目标
      if (commandAI.command == UnitCommand.assistCommand || commandAI.command == UnitCommand.rebuildCommand) {
        Object inner = getCommandController(commandAI);
        if (inner instanceof BuilderAI bai) {
          targetUnit = bai.following;
        }
      }
    } else if (controller instanceof BuilderAI bai) {
      targetUnit = bai.following;
    } else {
      // 玩家直接控制等情况：施工对象就是自己的队列
      targetUnit = unit;
    }

    if (targetUnit != null) {
      if (!targetUnit.activelyBuilding()) {
        clearMount(m);
      } else {
        // 1) 清理已完成 / 已失效 / 超出射程的计划
        for (int i = m.plans.size - 1; i >= 0; i--) {
          BuildPlan plan = m.plans.get(i);
          if (!(world.build(plan.x, plan.y) instanceof ConstructBlock.ConstructBuild) || plan.progress >= 1f) {
            plan.initialized = false;
            m.plans.remove(i);
            m.targets.remove(i);
          }
        }
        check(unit, m);

        // 2) 补充至 maxPlans
        if (m.plans.size < Settings.maxBuild()) {
          findTargets(targetUnit, unit, m);
        }
      }

      // 3) 并行施工
      if (targetUnit.activelyBuilding()) {
        for (int i = 0; i < m.plans.size; i++) {
          BuildPlan plan = m.plans.get(i);
          ConstructBlock.ConstructBuild target = m.targets.get(i);
          if (target == null) continue;

          float boost = Settings.buildBoost() ? (float) Settings.maxBuild() / m.plans.size : 1;
          float bs = 1f / target.buildCost * unit.type.buildSpeed * boost
                  * unit.buildSpeedMultiplier * state.rules.buildSpeed(unit.team) * speedMulti * Time.delta;
          if (plan.breaking) {
            target.deconstruct(unit, unit.team.core(), bs);
          } else {
            target.construct(unit, unit.team.core(), bs, plan.config);
          }
          plan.progress = target.progress;
        }
      }
    }

    // 瞄准：指向第一个（主）计划
    if (m.plans.size > 0) {
      BuildPlan plan = m.plans.first();
      mount.aimX = plan.drawx();
      mount.aimY = plan.drawy();
    } else {
      Tmp.v2.trns(unit.rotation(), 800f);
      mount.aimX = Tmp.v2.x + unit.x;
      mount.aimY = Tmp.v2.y + unit.y;
    }

    mount.shoot = false;
    mount.rotate = true;
    super.update(unit, mount);
  }

  /** 认领多个计划：非队首、在射程内、未初始化。 */
  void findTargets(Unit unit, Unit weaponUnit, BuildWeaponMount m) {
    Queue<BuildPlan> plans = unit.plans();
    //
    for (int i = 0; i < plans.size && m.plans.size < Settings.maxBuild(); i++) {
      BuildPlan p = plans.get(i);
      if (m.plans.contains(p)) continue;
      if (!weaponUnit.within(p.x * 8f, p.y * 8f, range)) continue;
      // if (p.initialized) continue;

      tryClaimPlan(unit, m, p);
    }
  }

  /** 尝试认领一个计划（建造或拆除）。返回是否成功认领。 */
  boolean tryClaimPlan(Unit unit, BuildWeaponMount m, BuildPlan plan) {
    if (!plan.breaking) {
      if (Build.validPlace(plan.block, unit.team, plan.x, plan.y, plan.rotation)) {
        Building build = world.build(plan.x, plan.y);
        if (build instanceof ConstructBlock.ConstructBuild cb) {
          m.targets.add(cb);
          m.plans.add(plan);
          plan.initialized = true;
          return true;
        }
        Build.beginPlace(unit, plan.block, unit.team, plan.x, plan.y, plan.rotation, plan.config);
        build = world.build(plan.x, plan.y);
        if (build instanceof ConstructBlock.ConstructBuild cb) {
          m.targets.add(cb);
          m.plans.add(plan);
          plan.initialized = true;
          return true;
        }
      }
    } else if (Build.validBreak(unit.team, plan.x, plan.y)) {
      Building build = world.build(plan.x, plan.y);
      if (build instanceof ConstructBlock.ConstructBuild cb) {
        m.targets.add(cb);
        m.plans.add(plan);
        plan.initialized = true;
        return true;
      }
      Build.beginBreak(unit, unit.team, plan.x, plan.y);
      build = world.build(plan.x, plan.y);
      if (build instanceof ConstructBlock.ConstructBuild cb) {
        m.targets.add(cb);
        m.plans.add(plan);
        plan.initialized = true;
        return true;
      }
    }
    return false;
  }

  /** 清空所有计划/目标。 */
  void clearMount(BuildWeaponMount m) {
    for (int i = 0; i < m.plans.size; i++) {
      m.plans.get(i).initialized = false;
    }
    m.plans.clear();
    m.targets.clear();
  }

  /** 移除超出射程的计划。 */
  void check(Unit unit, BuildWeaponMount m) {
    float max = state.rules.infiniteResources ? Float.MAX_VALUE : range;
    for (int i = m.plans.size - 1; i >= 0; i--) {
      BuildPlan plan = m.plans.get(i);
      if (Mathf.dst(unit.x, unit.y, plan.x * 8f, plan.y * 8f) > max) {
        plan.initialized = false;
        m.plans.remove(i);
        m.targets.remove(i);
      }
    }
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

    Queue<BuildPlan> plans = unit.plans();
    for (int i = 1; i < plans.size; i++) {
      if ((i - 1) % total != idx) continue;
      BuildPlan plan = plans.get(i);
      Tile tile = world.tile(plan.x, plan.y);
      if (!(tile.build instanceof ConstructBlock.ConstructBuild)) continue;

      boolean worked = false;
      for (WeaponMount wm : unit.mounts) {
        if (wm instanceof BuildWeaponMount bm) {
          int pidx = bm.plans.indexOf(plan);
          if (pidx >= 0 && bm.targets.get(pidx) != null) {
            worked = true;
            break;
          }
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
    /** 当前正在并行处理的多个建造/拆除计划。 */
    public Seq<BuildPlan> plans = new Seq<>();

    public BuildWeaponMount(Weapon w) {
      super(w);
    }
  }
}