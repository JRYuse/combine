package combine;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.struct.IntSet;
import arc.struct.ObjectIntMap;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Strings;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.blocks.logic.LogicBlock;

import static mindustry.Vars.state;

/**
 * 组合逻辑处理器 —— 原版 LogicBlock 的组合版，算力合并。
 *
 * 继承要点（保证旧蓝图/存档里的代码、逻辑连接不丢）：
 * - extends LogicBlock / 内部 Build extends LogicBuild；
 * - 不覆盖 config() / configured(...) / version() / write() / read()，
 *   代码、连接、变量池的保存/恢复完全由原版逻辑继承。
 *
 * 算力合并语义：
 * - 相邻同型自动成组（leader = 最小 pos），组总算力 = Σ 各成员 ipt（指令/tick）；
 * - 组内共享一个"指令池"：每 tick 总注入量 = Σ ipt（每个成员按自己的 ipt 注入），
 *   任一成员执行时从池中取——哪台负载高哪台多吃，总吞吐 = 台数 × 单机算力；
 * - 池上限 = maxInstructionScale × 组总ipt（与原版的 5 拍缓冲一致）；
 * - 池是易失状态，不进存档；编组信息也不进存档，读档后首个 updateTile 惰性重建。
 *
 * 实现手法：updateTile 里先把 accumulator=0 / ipt=0 调 super（原版链接解析、
 * 读码等全部保留，仅自带执行循环被零化），再自己跑共享池循环。
 */
public class CombinedLogicProcessor extends LogicBlock {

  public CombinedLogicProcessor(String name) {
    super(name);
    // copyFields 显式跳过 buildType，构造器里必须指定
    buildType = CombinedLogicProcessorBuild::new;
  }

  @Override
  public void setBars() {
    super.setBars();
    addBar("compute", (CombinedLogicProcessorBuild e) -> new Bar(
        () -> "算力 ×" + e.group().size + " (" + Strings.fixed(e.totalIpt() * 60f, 0) + "/秒)",
        () -> Pal.accent,
        () -> e.poolFrac()));
  }

  public class CombinedLogicProcessorBuild extends LogicBuild {
    public CombinedLogicProcessorBuild comboLeader;
    public Seq<CombinedLogicProcessorBuild> comboGroup = new Seq<>();
    public boolean comboDirty = true;
    /** totalIpt 的每-tick 缓存（组内算力总和） */
    public long comboIptTick = Long.MIN_VALUE;
    public float comboIptSum = 0.001f;
    /** 共享指令池（仅 leader 的值有意义）：本组当前剩余的可执行指令余量 */
    public float comboPool = 0f;

    public CombinedLogicProcessorBuild leader() {
      if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null))
        comboLeader = null;
      return comboLeader == null ? this : comboLeader;
    }

    public boolean isLeader() {
      return leader() == this;
    }

    public Seq<CombinedLogicProcessorBuild> group() {
      CombinedLogicProcessorBuild l = leader();
      if (l.comboGroup == null)
        l.comboGroup = new Seq<>();
      return l.comboGroup;
    }

    /** 组总算力 = Σ 各成员 ipt（指令/tick，显示时 ×60 换算成 /秒）。
     *  每 tick 都会被执行循环和 UI 读，按 tick 缓存一次，避免整组 O(N) 反复扫描。 */
    public float totalIpt() {
      CombinedLogicProcessorBuild l = leader();
      if (l.comboIptTick != state.updateId) {
        l.comboIptTick = state.updateId;
        float total = 0f;
        for (CombinedLogicProcessorBuild b : l.group())
          if (b.isValid())
            total += Math.max(b.ipt, 0);
        l.comboIptSum = Math.max(total, 0.001f);
      }
      return l.comboIptSum;
    }

    /** 池占用率（bar 用）：池余量 / 上限 */
    public float poolFrac() {
      CombinedLogicProcessorBuild l = leader();
      LogicBlock lb = (LogicBlock) block;
      float cap = lb.maxInstructionScale * totalIpt();
      return cap <= 0f ? 0f : Math.min(l.comboPool / cap, 1f);
    }

    public void rebuildCombo() {
      comboIptTick = Long.MIN_VALUE;
      comboGroup = new Seq<>();
      comboGroup.add(this);
      IntSet visited = new IntSet();
      Queue<CombinedLogicProcessorBuild> queue = new Queue<>();
      queue.add(this);
      visited.add(pos());
      while (!queue.isEmpty()) {
        CombinedLogicProcessorBuild cur = queue.removeFirst();
        for (Building b : cur.proximity) {
          if (b instanceof CombinedLogicProcessorBuild o && o.team == team && o.isValid()
              && !visited.contains(o.pos())) {
            visited.add(o.pos());
            queue.addLast(o);
            comboGroup.add(o);
          }
        }
      }
      CombinedLogicProcessorBuild newLeader = this;
      for (CombinedLogicProcessorBuild b : comboGroup)
        if (b.isValid() && b.pos() < newLeader.pos())
          newLeader = b;
      Seq<CombinedLogicProcessorBuild> newGroup = new Seq<>(comboGroup);
      newLeader.comboGroup = newGroup;
      for (CombinedLogicProcessorBuild b : newGroup) {
        if (b.isValid()) {
          b.comboLeader = newLeader;
          b.comboGroup = newGroup;
          b.comboDirty = false;
        }
      }
      newLeader.comboLeader = null;
      newLeader.comboIptTick = Long.MIN_VALUE;
    }

    @Override
    public void created() {
      super.created();
      comboDirty = true;
    }

    @Override
    public void onProximityUpdate() {
      super.onProximityUpdate();
      comboDirty = true;
      for (CombinedLogicProcessorBuild m : group())
        if (m.isValid())
          m.comboDirty = true;
    }

    @Override
    public void onRemoved() {
      float pool = comboPool;
      Seq<CombinedLogicProcessorBuild> members = new Seq<>(group());
      boolean wasLeader = isLeader();
      if (wasLeader) {
        boolean transferred = false;
        for (CombinedLogicProcessorBuild b : members) {
          if (b != this && b.isValid()) {
            b.comboLeader = null;
            b.comboGroup = new Seq<>();
            b.comboDirty = true;
            if (!transferred) {
              b.comboPool = pool; // 池余量交给第一个幸存者
              transferred = true;
            }
          }
        }
      } else {
        CombinedLogicProcessorBuild l = leader();
        if (l != null && l.isValid() && l != this)
          l.comboDirty = true;
      }
      comboLeader = null;
      comboGroup = new Seq<>();
      comboDirty = false;
      comboPool = 0f;
      super.onRemoved();
    }

    @Override
    public void updateTile() {
      if (isLeader() && comboDirty)
        rebuildCombo();
      CombinedLogicProcessorBuild lead = leader();

      // 1) 原版流程全部保留（读码 / 链接解析 / updateLinks），
      //    但把自带执行循环零化：ipt=0 → 不注入、不执行、上限为 0
      float savedAcc = accumulator;
      int savedIpt = ipt;
      accumulator = 0f;
      ipt = 0;
      try {
        super.updateTile();
      } finally {
        accumulator = savedAcc;
        ipt = savedIpt;
      }

      // 2) 共享算力池：每 tick 全组总注入 = Σ ipt；任一成员可消耗整池
      LogicBlock lb = (LogicBlock) block;
      if (!(state.rules.disableWorldProcessors && lb.privileged)
          && enabled && executor.initialized()) {
        float pool = lead.comboPool;
        float cap = lb.maxInstructionScale * lead.totalIpt();
        if (pool > cap)
          pool = cap;
        while (pool >= 1f) {
          executor.runOnce(); // 执行一条指令
          if (executor.yield) {
            executor.yield = false;
            break;
          }
          pool--;
        }
        pool += edelta() * savedIpt; // 本机按自己的 ipt 注入
        lead.comboPool = pool;
      }
    }

    // ==================== 显示面板（参考组合工厂） ====================

    @Override
    public void display(Table table) {
      // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
      ComboUi.safe("combinedlogicprocessor:display", () -> displayInner(table));
    }

    void displayInner(Table table) {
      table.table(cont -> {
        cont.top().left();
        cont.defaults().growX().left();

        cont.table(t -> {
          t.left();
          TextureRegion icon = block.getDisplayIcon(tile);
          if (icon == null)
            icon = Core.atlas.find("clear");
          t.add(new Image(icon)).size(8 * 4);
          int count = ComboNet.displayMembers(this, group().size).size;
          String title = count > 1
              ? "[accent]组合逻辑处理器[] x" + count + "\n" + block.getDisplayName(tile)
              : block.getDisplayName(tile);
          t.labelWrap(title).left().width(160f).padLeft(4);
        }).growX().left();
        cont.row();

        if (team != mindustry.Vars.player.team())
          return;

        Table barsTable = new Table();
        barsTable.left();
        barsTable.update(() -> {
          barsTable.clearChildren();
          barsTable.defaults().growX().height(18f).pad(4);
          buildComboBars(barsTable);
        });
        cont.add(barsTable).growX().left();
        cont.row();

        Table comboIO = new Table();
        comboIO.left();
        comboIO.update(() -> {
          comboIO.clearChildren();
          buildComboIO(comboIO);
        });
        cont.add(comboIO).growX().left();
      }).width(260f).left();
        }

    public void buildComboBars(Table table) {
      // 总算力（数字）+ 指令池占用
      final float total = totalIpt();
      final float pool = leader().comboPool;
      final float cap = ((LogicBlock) block).maxInstructionScale * total;
      table.add(new Bar(
          () -> "总算力 " + Strings.fixed(total * 60f, (total * 60f) % 1f == 0f ? 0 : 1) + " 指令/秒",
          () -> Pal.accent,
          () -> 1f));
      table.row();
      table.add(new Bar(
          () -> "指令池 " + Strings.fixed(pool, 1) + "/" + Strings.fixed(cap, 1),
          () -> Pal.lightOrange,
          () -> cap <= 0f ? 0f : Math.min(pool / cap, 1f)));
      table.row();
    }

    public void buildComboIO(Table table) {
      table.left();
      table.add("[lightgray]组合体构成:").left();
      table.row();
      ObjectIntMap<Block> blockCounts = new ObjectIntMap<>();
      for (Building member : ComboNet.displayMembers(this, group().size)) {
        if (member.isValid()) {
          int old = blockCounts.get(member.block, 0);
          blockCounts.put(member.block, old + 1);
        }
      }
      Seq<Block> sortedBlocks = new Seq<>();
      for (Block b : blockCounts.keys())
        sortedBlocks.add(b);
      sortedBlocks.sort(b -> b.id);
      boolean hasContent = false;
      for (Block b : sortedBlocks) {
        int count = blockCounts.get(b, 0);
        if (count > 0) {
          hasContent = true;
          table.add(b.localizedName + "*" + count).color(Color.white).left();
          table.row();
        }
      }
      if (!hasContent) {
        table.add("[darkGray]无").left();
        table.row();
      }
    }
  }
}
