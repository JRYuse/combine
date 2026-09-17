package combine;

import arc.Core;
import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Icon;

public class Settings {
    //神秘命名
    static final String maxBuild = "combine-max-multi-build";
    static final String buildBoost = "combine-build-boost";
    static void load() {
        Events.on(EventType.ClientLoadEvent.class, e -> Core.app.post(Settings::register));
    }

    static void register() {
        if (Vars.headless || Vars.ui == null || Vars.ui.settings == null) return;
        Vars.ui.settings.addCategory("组合工厂设置", Icon.settings, table -> {
            table.sliderPref(maxBuild, 5, 0, 50, 1,i -> i > 0 ? "" + i : "自动");
            table.checkPref(buildBoost, false);
        });
    }

    static int maxBuild() {
        return Core.settings.getInt(maxBuild, 5);
    }

    static boolean buildBoost() {
        return Core.settings.getBool(buildBoost, true);
    }
}
