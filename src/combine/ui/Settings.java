package combine.ui;

import arc.Core;
import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Icon;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SettingsMenuDialog;

public class Settings {
    //神秘命名
    static final String maxBuild = "combine-max-multi-build";
    static final String buildBoost = "combine-build-boost";
    static final String openList = "combine-open-list";
    public static void load() {
        Events.on(EventType.ClientLoadEvent.class, e -> Core.app.post(Settings::register));
    }

    public static void register() {
        if (Vars.headless || Vars.ui == null || Vars.ui.settings == null) return;
        Vars.ui.settings.addCategory("组合工厂设置", Icon.settings, table -> {
            table.sliderPref(maxBuild, 5, 0, 50, 1,i -> i > 0 ? "" + i : "自动");
            table.checkPref(buildBoost, false);
            table.pref(new SettingsMenuDialog.SettingsTable.Setting(openList) {
                @Override
                public void add(SettingsMenuDialog.SettingsTable table) {
                    table.button("组合建筑管理", Icon.list, () -> {
                        BaseDialog dialog = new BaseDialog("组合建筑管理");
                        dialog.addCloseButton();
                        SettingsMenuDialog.SettingsTable content = new SettingsMenuDialog.SettingsTable();
                        ComboBlockList.build(content);
                        dialog.cont.pane(content).grow().scrollX(false);
                        dialog.show();
                    }).growX().height(50f).padTop(10f).row();
                }
            });
        });
    }

    public static int maxBuild() {
        return Core.settings.getInt(maxBuild, 5);
    }

    public static boolean buildBoost() {
        return Core.settings.getBool(buildBoost, true);
    }
}
