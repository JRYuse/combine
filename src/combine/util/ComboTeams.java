package combine.util;

import arc.Core;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Player;

/**
 * "只和玩家队友组合"总开关（设置界面里的复选框）。
 *
 * <p>打开之后：**只有玩家队伍（本地玩家/联机队友）的建筑才互相组合**，
 * AI 敌人（crux/malis 这类没有玩家的队伍）的建筑之间不再合并库存/火力/血量。
 * 关掉就是老行为（谁跟谁贴一起都能组合）。
 *
 * <p>判据用"这个队伍里有没有玩家"（{@link Groups#player}），单机/联机/专用服务器都成立：
 * 单机只有本地玩家那一个队伍有 Player，AI 队伍一个 Player 都没有。
 */
public class ComboTeams {
  public static final String KEY = "combine-only-player-teams";
  public static final String KEY_ENABLED = "combine-enabled";
  public static boolean playersOnly = false;
  /**
   * 组合总开关（设置里那个按钮）：关掉之后**所有**组合建筑 —— 包括玩家自己队伍的 ——
   * 都按原版跑，物品/液体/电力/容量都不再并池。
   *
   * <p>和"只和玩家队友组合"是两回事：那个只是不管 AI 敌人的建筑，玩家队伍照常组合。
   */
  public static boolean enabled = true;

  public static void load() {
    try {
      playersOnly = Core.settings.getBool(KEY, false);
      enabled = Core.settings.getBool(KEY_ENABLED, true);
    } catch (Throwable ignored) {
    }
  }

  /** 设置界面用：组合总开关。关掉时把已经并在一起的组合体立刻拆成"每台各一份"。 */
  public static void setEnabled(boolean value) {
    enabled = value;
    try {
      combine.net.ComboNet.invalidateTracked();
    } catch (Throwable ignored) {
    }
    if (!value) {
      // 【不能只关闸】关掉的那一刻基地里可能已经并了一大片：模块还共用着，
      // 不主动拆的话玩家看到的是"开关关了但东西还是共享的"。按容量比例拆回每台一份。
      try {
        combine.net.ComboNet.detachAllCombos();
      } catch (Throwable ignored) {
      }
    } else {
      try {
        combine.net.ComboNet.markDirty(); // 重新打开：下一轮重算把该并的并回去
        combine.coop.CoopCombo.markDirty();
      } catch (Throwable ignored) {
      }
    }
    try {
      Core.settings.put(KEY_ENABLED, value);
    } catch (Throwable ignored) {
    }
  }

  public static void set(boolean value) {
    playersOnly = value;
    // 【性能】"组合相关建筑"登记表是按这个开关过滤过后存的，开关一变就得重扫
    //（否则打开"只和玩家队友组合"之后，名单里还留着敌人的建筑）。
    try {
      combine.net.ComboNet.invalidateTracked();
    } catch (Throwable ignored) {
    }
    try {
      Core.settings.put(KEY, value);
    } catch (Throwable ignored) {
    }
  }

  /** 这个队伍算"玩家队伍"吗（总开关关着时恒为 true = 不管）。 */
  public static boolean playerTeam(Team team) {
    // 组合总开关关掉：谁的建筑都不参与组合（含玩家队伍）
    if (!enabled)
      return false;
    if (!playersOnly)
      return true;
    if (team == null)
      return false;
    try {
      if (Vars.player != null && Vars.player.team() == team)
        return true;
      for (Player p : Groups.player)
        if (p != null && p.team() == team)
          return true;
    } catch (Throwable ignored) {
    }
    // 一个玩家都没有的场合（无头测试 / 服务器刚开还没人）：把"默认队伍"当玩家队伍，
    // 其余队伍（crux/malis 这类 AI）一律不算 —— 有玩家时上面的判断先命中，不受影响。
    try {
      return Vars.headless && Vars.state != null && Vars.state.rules != null
          && team == Vars.state.rules.defaultTeam;
    } catch (Throwable ignored) {
    }
    return false;
  }

  /** 这两台建筑能不能组合成一组（总开关关着时只看同队）。 */
  public static boolean allowCombine(Building a, Building b) {
    if (a == null || b == null || a.team != b.team)
      return false;
    return playerTeam(a.team);
  }
}
