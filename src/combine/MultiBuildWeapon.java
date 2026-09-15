package combine;

import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.Queue;
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

public class MultiBuildWeapon extends Weapon {
  public float range = 236f;
  /** 该挂座施工速度倍率。 */
  public float speedMulti = 1f;

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
      if (targetUnit.activelyBuilding()) {
        findTarget(targetUnit, unit, m);
      } else {
        m.target = null;
        if (m.plan != null)
          m.plan.initialized = false;
        m.plan = null;
      }

      check(unit, m);
      if (m.plan != null
          && (!(world.build(m.plan.x, m.plan.y) instanceof ConstructBlock.ConstructBuild) || m.plan.progress >= 1f)) {
        m.target = null;
        if (m.plan != null)
          m.plan.initialized = false;
        m.plan = null;
        findTarget(targetUnit, unit, m);
      }

      if (m.target != null && m.plan != null && targetUnit.activelyBuilding()) {
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
    }

    if (m.plan != null) {
      mount.aimX = m.plan.drawx();
      mount.aimY = m.plan.drawy();
    } else {
      Tmp.v2.trns(unit.rotation(), 800f);
      mount.aimX = Tmp.v2.x + unit.x;
      mount.aimY = Tmp.v2.y + unit.y;
    }

    mount.shoot = false;
    mount.rotate = true;
    super.update(unit, mount);
  }

  /** 认领一个计划：非队首、在射程内、未被其他建造挂座认领、未初始化。 */
  BuildPlan findPlan(Unit unit, Unit weaponUnit, BuildWeaponMount tm) {
    Queue<BuildPlan> plans = unit.plans();
    return plans.find(p -> {
      if (unit.plans.first() != p && weaponUnit.within(p.x * 8f, p.y * 8f, range)) {
        for (WeaponMount mount : unit.mounts()) {
          if (mount instanceof BuildWeaponMount bm && bm != tm && bm.plan == p) {
            return false;
          }
        }
        return !p.initialized;
      }
      return false;
    });
  }

  void check(Unit unit, BuildWeaponMount m) {
    if (m.plan != null && Mathf.dst(unit.x, unit.y, m.plan.x * 8f,
        m.plan.y * 8f) > (state.rules.infiniteResources ? Float.MAX_VALUE : range)) {
      m.target = null;
      if (m.plan != null)
        m.plan.initialized = false;
      m.plan = null;
    }
  }

  void findTarget(Unit unit, Unit weaponUnit, BuildWeaponMount m) {
    if (m.plan == null || m.target == null || isRob(unit, m)) {
      m.plan = null;
      m.target = null;
      BuildPlan plan = findPlan(unit, weaponUnit, m);
      if (plan == null)
        return;

      if (!plan.breaking) {
        if (Build.validPlace(plan.block, unit.team, plan.x, plan.y, plan.rotation)) {
          Building build = world.build(plan.x, plan.y);
          if (build instanceof ConstructBlock.ConstructBuild cb) {
            m.target = cb;
            m.plan = plan;
            plan.initialized = true;
            return;
          }
          Build.beginPlace(unit, plan.block, unit.team, plan.x, plan.y, plan.rotation, plan.config);
          build = world.build(plan.x, plan.y);
          if (build instanceof ConstructBlock.ConstructBuild cb) {
            m.target = cb;
            m.plan = plan;
            plan.initialized = true;
          }
        }
      } else if (Build.validBreak(unit.team, plan.x, plan.y)) {
        Building build = world.build(plan.x, plan.y);
        if (build instanceof ConstructBlock.ConstructBuild cb) {
          m.target = cb;
          m.plan = plan;
          plan.initialized = true;
          return;
        }
        Build.beginBreak(unit, unit.team, plan.x, plan.y);
        build = world.build(plan.x, plan.y);
        if (build instanceof ConstructBlock.ConstructBuild cb) {
          m.target = cb;
          m.plan = plan;
          plan.initialized = true;
        }
      }
    }
  }

  /** 计划被队首占用 / 被其他挂座抢了就放弃重找。 */
  boolean isRob(Unit unit, BuildWeaponMount m) {
    if (unit.plans.first() == m.plan)
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

    Queue<BuildPlan> plans = unit.plans();
    for (int i = 1; i < plans.size; i++) {
      if ((i - 1) % total != idx)
        continue;
      BuildPlan plan = plans.get(i);
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
    try {
      Field f = CommandAI.class.getDeclaredField("commandController");
      f.setAccessible(true);
      return f.get(ai);
    } catch (Exception e) {
      return null;
    }
  }

  public static class BuildWeaponMount extends WeaponMount {
    public ConstructBlock.ConstructBuild target;
    public BuildPlan plan;

    public BuildWeaponMount(Weapon w) {
      super(w);
    }
  }
}
