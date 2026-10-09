package combine.distribution;

import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Edges;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.ArmoredConveyor;
import mindustry.world.blocks.distribution.Conveyor;

/**
 * 组合传送带：替换原版/模组的 {@link Conveyor} 与 {@link ArmoredConveyor}（含子类，同 id 同名字）。
 *
 * <p>行为与原版完全一致（速度/贴图/交叉点/桥），只多一件事：同一格上再放一条**同种**传送带时叠一层，
 * 速度按层数成倍 —— 入口与同步见 {@link ConveyorOverlay}。
 * {@link ArmoredConveyor} 的"防侧装"（{@link #armored}）也一起保留，因为原版那样直接继承再用
 * {@code copyFields} 会把它自己的 {@code blends/acceptItem} 丢掉。
 */
public class CombinedConveyor extends Conveyor {
  /** 最多叠几层（见 {@link Layered}）。 */
  public int maxLayers = ConveyorOverlay.MAX_LAYERS;
  /** 是不是"装甲"传送带（原版 {@link ArmoredConveyor} 的防侧装行为）。 */
  public boolean armored = false;

  public CombinedConveyor(String name) {
    super(name);
  }

  /**
   * 拖拽预览：原版先给这条线补桥/换路口，我们再记下这条线（松手时给线上的同种传送带各叠一层，
   * 见 {@link ConveyorOverlay#finishDrag}）。
   *
   * <p>没有本机玩家（无头测试/专用服务端）时跳过原版那一步：{@code Placement.smartCalculateBridges}
   * 内部要用 {@code Vars.player}，没有就直接 NPE —— 而它只是客户端的预览表现（补桥/换路口），
   * 玩法不受影响。
   */
  @Override
  public void handlePlacementLine(Seq<BuildPlan> plans) {
    if (Vars.player != null)
      super.handlePlacementLine(plans);
    ConveyorOverlay.captureLine(plans);
  }

  /**
   * 先放预设、按建造（✓）/入队时才叠层：原版把这个计划交过来时我们才真正改层数，
   * 见 {@link ConveyorOverlay#onNewPlan(Block, BuildPlan)}。
   */
  @Override
  public void onNewPlan(BuildPlan plan) {
    ConveyorOverlay.onNewPlan(this, plan);
  }

  /** 预览：同方向"覆盖叠层"的预设要画成可以放（原版把它判成非法，会画成红色/禁止）。 */
  @Override
  public void drawPlan(BuildPlan plan, arc.util.Eachable<BuildPlan> list, boolean valid) {
    super.drawPlan(plan, list, valid || ConveyorOverlay.isOverlayPlan(this, plan));
  }

  @Override
  public void drawPlan(BuildPlan plan, arc.util.Eachable<BuildPlan> list, boolean valid, float alpha) {
    super.drawPlan(plan, list, valid || ConveyorOverlay.isOverlayPlan(this, plan), alpha);
  }

  @Override
  public boolean blends(Tile tile, int rotation, int otherx, int othery, int otherrot, Block otherblock) {
    if (!armored)
      return super.blends(tile, rotation, otherx, othery, otherrot, otherblock);
    // 原版 ArmoredConveyor.blends
    return (otherblock.outputsItems() && blendsArmored(tile, rotation, otherx, othery, otherrot, otherblock))
        || (lookingAt(tile, rotation, otherx, othery, otherblock) && otherblock.hasItems);
  }

  @Override
  public boolean blendsArmored(Tile tile, int rotation, int otherx, int othery, int otherrot, Block otherblock) {
    if (!armored)
      return super.blendsArmored(tile, rotation, otherx, othery, otherrot, otherblock);
    // 原版 ArmoredConveyor.blendsArmored（比 Autotiler 的默认实现多认"也 instanceof Conveyor"这一支）
    return Point2.equals(tile.x + Geometry.d4(rotation).x, tile.y + Geometry.d4(rotation).y, otherx, othery)
        || ((!otherblock.rotatedOutput(otherx, othery, tile)
            && Edges.getFacingEdge(otherblock, otherx, othery, tile) != null
            && Edges.getFacingEdge(otherblock, otherx, othery, tile).relativeTo(tile) == rotation)
            || (otherblock instanceof Conveyor && otherblock.rotatedOutput(otherx, othery, tile)
                && Point2.equals(otherx + Geometry.d4(otherrot).x, othery + Geometry.d4(otherrot).y, tile.x, tile.y)));
  }

  public class CombinedConveyorBuild extends ConveyorBuild implements Layered {
    /** 覆盖层数：1 = 原速，没被覆盖过（存档/快照里单独存，见 {@link Layered}）。 */
    public int layers = 1;

    @Override
    public int layers() {
      // 读取按设置上限夹紧：设置下调时，已叠层数立即按新上限算速度/绘制
      return Math.min(layers, ConveyorOverlay.maxLayers(block));
    }

    @Override
    public void layers(int value) {
      // 只增不减 + 夹上限（上限走设置）：客户端乐观叠层与服务端转发的同一个值会各生效一次，这样是幂等的
      int next = Mathf.clamp(Math.max(layers, value), 1, ConveyorOverlay.maxLayers(block));
      if (next == layers)
        return;
      layers = next;
      noSleep();
      ConveyorOverlay.changedFx(this);
    }

    // 原版 ArmoredConveyorBuild.acceptItem
    @Override
    public boolean acceptItem(Building source, Item item) {
      if (!armored)
        return super.acceptItem(source, item);
      return super.acceptItem(source, item)
          && (source.block instanceof Conveyor || Edges.getFacingEdge(source.tile, tile).relativeTo(tile) == rotation);
    }

    /*
     * 【速度实现】原版 ConveyorBuild 的 update/draw/unitOn 读的都是**方块级**的 speed 字段
     * （内嵌类里 `speed` 解析到外层 Conveyor.this.speed）。层数是每个建筑各自的，
     * 所以调用原版实现时先把速度按层数放大，跑完立刻还原 —— 这样连"传送带动画快慢"
     * 都跟着变，不用把原版那三段代码抄一遍（抄一遍以后原版一改就会悄悄失配）。
     * 世界更新与绘制都是单线程顺序执行，调用期间没有别的传送带会读这个字段。
     */
    @Override
    public void updateTile() {
      float keep = speed;
      speed = keep * speedMul();
      try {
        super.updateTile();
      } finally {
        speed = keep;
      }
    }

    @Override
    public void draw() {
      float keep = speed;
      speed = keep * speedMul();
      try {
        super.draw();
      } finally {
        speed = keep;
      }
    }

    @Override
    public void unitOn(Unit unit) {
      float keep = speed;
      speed = keep * speedMul();
      try {
        super.unitOn(unit);
      } finally {
        speed = keep;
      }
    }
  }
}
