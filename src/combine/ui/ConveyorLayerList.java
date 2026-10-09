package combine.ui;

import arc.scene.ui.ScrollPane;
import arc.scene.ui.Slider;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import combine.distribution.ConveyorOverlay;
import mindustry.Vars;
import mindustry.world.Block;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;

/**
 * 「组合传送带堆叠配置」界面（挂在设置里的按钮弹窗）。
 *
 * <p>每行一种组合传送带 / 管道：单独配置它的层数上限（1..10）。
 * 滑块拖到 <b>0 = 未配置</b>，走设置里的全局滑块默认（{@link ConveyorOverlay#SETTING_MAX_LAYERS}）。
 * 数据存 {@link ConveyorOverlay#customLayers}（Core.settings 持久化），改动即时生效。
 */
public class ConveyorLayerList {

    public static void build(SettingsTable table) {
        Seq<Block> list = new Seq<>();
        for (Block b : Vars.content.blocks()) {
            if (ConveyorOverlay.isConveyorBlock(b))
                list.add(b);
        }
        list.sort((a, b) -> {
            String na = a.localizedName != null ? a.localizedName : a.name;
            String nb = b.localizedName != null ? b.localizedName : b.name;
            return na.compareTo(nb);
        });

        table.add("[accent]组合工厂[] [lightgray]· 传送带堆叠配置").left().padBottom(4f).row();
        table.add("[lightgray]每行单独上限；0 = 跟随全局滑块默认值。[]").left().wrap().padBottom(8f).row();
        if (list.isEmpty()) {
            table.add("[gray]没有可配置的传送带/管道[]").left().row();
            return;
        }

        final Table rows = new Table();
        rows.top().left();
        rows.defaults().left();
        for (Block b : list) {
            buildRow(rows, b);
        }

        ScrollPane pane = new ScrollPane(rows);
        pane.setFadeScrollBars(false);
        pane.setScrollingDisabled(true, false);
        pane.setOverscroll(false, false);
        table.add(pane).growX().height(380f).padTop(6f).left().row();
    }

    private static void buildRow(Table parent, Block b) {
        parent.table(t -> {
            t.left().margin(3f);
            t.add(b.localizedName != null ? b.localizedName : b.name).left().padRight(12f).width(200f);

            Slider slider = new Slider(0f, 10f, 1f, false);
            slider.setValue(ConveyorOverlay.customLayersOf(b));
            t.add(slider).growX().minWidth(160f);

            t.label(() -> {
                int v = (int) slider.getValue();
                return v == 0 ? "[lightgray]跟随全局[]" : v + " 层";
            }).width(90f).padLeft(8f);

            slider.moved(v -> {
                int nv = (int) v;
                if (nv > 0)
                    ConveyorOverlay.customLayers.put(b.name, nv);
                else
                    ConveyorOverlay.customLayers.remove(b.name);
            });
            slider.released(ConveyorOverlay::saveLayers);
        }).growX().row();
    }
}
