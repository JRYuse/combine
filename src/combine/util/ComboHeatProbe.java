package combine.util;

import arc.Core;
import arc.util.Log;
import mindustry.gen.Building;
import mindustry.world.Block;

/**
 * 「组合网络热量探针」：把整张组合网络的热量喂给**跨距离连进来的**需热建筑。
 *
 * <p>为什么需要它：组合节点/连接器是**跨距离**连接的，可原版 {@code calculateHeat} 只看
 * {@code proximity}（挨着的邻居）。于是"制热机 ——节点连线—— 需热炮台"里，炮台读不到节点
 * 上的热（用户报的"无法把制热机的热量传递给炮台"）。贴着节点的建筑本来就看得见，
 * 只有**连线接进来、又没贴着节点**的需热端会漏。
 *
 * <p>做法和 {@link combine.turret.SuperTurret.HeatProbe} 一样：给那些需热建筑的
 * {@code proximity} 里插一个摆在它自己坐标上的假建筑（{@code rotate = false}，
 * 于是原版把它算成"整份热量都在这格的热源"），其 {@code heat()} 就是整张网络的热量。
 * 每帧由 {@link combine.net.ComboNet} 刷新，网络断了就把探针摘掉。
 */
public class ComboHeatProbe extends Block implements mindustry.world.blocks.heat.HeatBlock {
  /** 全局单例（内容装配时建好；没建好时网络热量退回"只能贴着"）。 */
  public static ComboHeatProbe probe;

  public ComboHeatProbe(String name) {
    super(name);
    size = 1;
    rotate = false;
    update = false;
    solid = false;
    destructible = false;
    buildVisibility = mindustry.world.meta.BuildVisibility.hidden;
    category = mindustry.type.Category.effect;
    buildType = ComboHeatProbeBuild::new;
  }

  /** 内容装配阶段调用一次（和超级炮台的热量探针同一套写法）。 */
  public static void create() {
    if (probe != null)
      return;
    try {
      probe = new ComboHeatProbe("combine-heat-probe");
      probe.init();
      probe.postInit();
      arc.graphics.g2d.TextureRegion reg = Core.atlas == null ? null
          : Core.atlas.find("heat-source", Core.atlas.find("error"));
      if (reg != null) {
        probe.region = reg;
        probe.fullIcon = reg;
        probe.uiIcon = reg;
      }
    } catch (Throwable t) {
      probe = null;
      Log.err("[combine] 组合网络热量探针装配失败（跨距离传热会退回只能贴着的原版行为）", t);
    }
  }

  @Override
  public float heat() {
    return 0f;
  }

  @Override
  public float heatFrac() {
    return 0f;
  }

  /** 一台待喂热的需热建筑对应一个探针（同一 tick 内复用，网络变了就摘掉）。 */
  public class ComboHeatProbeBuild extends Building implements mindustry.world.blocks.heat.HeatBlock {
    public float amount;
    /**
     * 最后一次被 ComboNet 喂热时的 state.updateId。喂一次续一次期；
     * 任何网络都不再喂它（取消共享/拆节点/断线）超过 30 tick 后，探针会被
     * ComboNet 统一摘掉 —— 否则探针残留一份旧热量，"取消热量传递后还在传热"。
     */
    public long lastFed = -1L;

    @Override
    public float heat() {
      return amount;
    }

    @Override
    public float heatFrac() {
      return 0f;
    }
  }
}
