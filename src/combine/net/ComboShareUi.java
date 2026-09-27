package combine.net;

import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.gen.Building;
import mindustry.graphics.Pal;

import static mindustry.Vars.headless;

/**
 * 组合节点 / 组合连接器的配置面板：勾选"共享哪些部分"（物品 / 液体 / 电力 / 热量）。
 *
 * <p>挂在方块的 config 面板里（{@code buildConfiguration}），也就是玩家点一下节点/连接器
 * 弹出来的那个小面板。四个勾选框读写的是**整张网络**的配置（点任意一个连接件都一样），
 * 所以勾选框每帧跟着当前配置刷新，别的玩家/别的节点改了这里也立刻同步。
 */
public final class ComboShareUi {
    private ComboShareUi() {
    }

    public static void build(Table table, Building self) {
        if (table == null || self == null) return;
        try {
            boolean node = self instanceof ComboNode.ComboNodeBuild;
            table.left();
            table.add("[accent]" + (node ? "组合节点" : "组合连接器") + "[]").left();
            table.row();
            table.add("[lightgray]共享部分（点任意一个连接件 = 改整张网络）[]").left();
            table.row();

            if (!headless) {
                Table toggles = new Table();
                for (int i = 0; i < ComboShare.bits.length; i++) {
                    final int bit = ComboShare.bits[i];
                    var box = toggles.check(ComboShare.label(bit),
                        (ComboShare.maskOf(self) & bit) != 0,
                        c -> ComboShare.toggle(self, bit)).padRight(8f).get();
                    // 配置可能由别的连接件（同一张网络的）改动，勾选框每帧跟当前配置对齐
                    box.update(() -> box.setChecked((ComboShare.maskOf(self) & bit) != 0));
                }
                table.add(toggles).left();
                table.row();
            }

            table.label(() -> "[lightgray]当前: []" + ComboShare.describe(ComboShare.maskOf(self))).left();
            table.row();

            if (node) {
                ComboNode.ComboNodeBuild nb = (ComboNode.ComboNodeBuild) self;
                table.label(() -> "连接数: " + nb.links.size + "/" + ((ComboNode) nb.block).maxNodes)
                    .color(Pal.accent).left();
                table.row();
                table.label(() -> "[lightgray]点其它组合体 = 连接 / 断开[]").left();
                table.row();
                table.label(() -> "覆盖组合建筑: " + ComboNet.componentMembers(nb).size).color(Pal.accent).left();
                table.row();
            } else {
                table.label(() -> "[lightgray]贴着连接器的组合体，按上面勾选的部分共享[]").left();
                table.row();
                table.label(() -> "连接组合: " + ComboNet.componentMembers(self).size).color(Pal.accent).left();
                table.row();
            }
        } catch (Throwable t) {
            // 面板异常绝不能把游戏带崩（同 ComboUi 的思路）
            Log.err("[combine] 组合共享配置面板绘制失败（跳过）", t);
        }
    }
}
