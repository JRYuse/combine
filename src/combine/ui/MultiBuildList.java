package combine.ui;

import arc.Core;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.Slider;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import combine.TestMultiBuildWeapon;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.type.UnitType;
import mindustry.type.Weapon;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;

/**
 * 「多倍建造配置」界面（挂在设置里的按钮弹窗）。
 *
 * 列出所有可建造的单位（buildSpeed > 0），可以：
 *   · 按名字搜索（英文 / 本地化名）
 *   · 过滤：全部 / 有多倍建造 / 无多倍建造
 *   · 点击单位展开配置，再次点击收起
 *
 * 单个单位的配置写入 Core.settings：
 *   combine-unit-max-&lt;unitName&gt;   并行建造数（存在时覆盖全局 Settings.maxBuild()）
 *   combine-unit-boost-&lt;unitName&gt; 加速建造（存在时覆盖全局 Settings.buildBoost()）
 */
public class MultiBuildList {

    public static final int FILTER_ALL = 0, FILTER_HAS = 1, FILTER_NONE = 2;
    private static int filterMode = FILTER_ALL;
    private static String searchText = "";
    private static final ObjectSet<String> expanded = new ObjectSet<>();

    public static String keyMax(String name){ return "combine-unit-max-" + name; }
    public static String keyBoost(String name){ return "combine-unit-boost-" + name; }
    public static String keyEnabled(String name){ return "combine-unit-enabled-" + name; }

    /** 用户对该单位是否启用多倍建造的偏好；null = 没设置过（用 Main 的硬编码默认）。 */
    public static Boolean userEnabled(String name){
        if(Core.settings == null || name == null) return null;
        String key = keyEnabled(name);
        if(!Core.settings.has(key)) return null;
        return Core.settings.getBool(key);
    }

    public static void setUserEnabled(String name, boolean enabled){
        if(Core.settings == null || name == null) return;
        Core.settings.put(keyEnabled(name), enabled);
        Core.settings.saveValues();
    }

    public static void init() {}

    /** 单位是否可建造（buildSpeed > 0）。 */
    public static boolean canBuild(UnitType u){
        return u != null && u.buildSpeed > 0;
    }

    public static TestMultiBuildWeapon getWeapon(UnitType u){
        if(u == null) return null;
        for(Weapon w : u.weapons){
            if(w instanceof TestMultiBuildWeapon) return (TestMultiBuildWeapon)w;
        }
        return null;
    }

    public static Seq<UnitType> list(String query, int filter){
        String q = query == null ? "" : query.trim().toLowerCase();
        Seq<UnitType> out = new Seq<>();
        for(UnitType u : Vars.content.units()){
            if(!canBuild(u)) continue;
            if(!q.isEmpty()){
                String name = u.name == null ? "" : u.name.toLowerCase();
                String loc  = u.localizedName == null ? "" : u.localizedName.toLowerCase();
                if(!name.contains(q) && !loc.contains(q)) continue;
            }
            boolean has = getWeapon(u) != null;
            if(filter == FILTER_HAS && !has) continue;
            if(filter == FILTER_NONE && has) continue;
            out.add(u);
        }
        out.sort((a, b) -> {
            int ha = getWeapon(a) != null ? 0 : 1;
            int hb = getWeapon(b) != null ? 0 : 1;
            if(ha != hb) return Integer.compare(ha, hb);
            return a.name.compareTo(b.name);
        });
        return out;
    }

    private static void defer(Runnable[] r){
        if(Core.app == null){ r[0].run(); return; }
        Core.app.post(() -> { if(r[0] != null) r[0].run(); });
    }

    private static void addFilterRow(SettingsTable table, Runnable[] whole, int[] modes, String[] names){
        table.table(filters -> {
            filters.left();
            for(int i = 0; i < modes.length; i++){
                final int mode = modes[i];
                boolean active = filterMode == mode;
                String label = active ? "[accent]▶ " + names[i] + "[]" : names[i];
                filters.button(label, () -> {
                    filterMode = mode;
                    defer(whole);
                }).growX().minWidth(0f).height(32f).padRight(6f);
            }
        }).growX().left().padTop(4f).row();
    }

    public static void build(SettingsTable table){
        build(table, new Runnable[1]);
    }

    private static void build(SettingsTable table, Runnable[] whole){
        whole[0] = () -> {
            table.clearChildren();
            build(table, whole);
        };

        final String[] query = {searchText};
        final Table list = new Table();
        list.top().left();
        list.defaults().left();

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            try{
                list.clearChildren();
                Seq<UnitType> units = list(query[0], filterMode);
                if(units.isEmpty()){
                    list.add("[gray]没有匹配的单位[]").left().row();
                    return;
                }
                for(UnitType u : units){
                    buildRow(list, u, rebuild);
                }
            }catch(Throwable t){
                Log.err("[combine] 多倍建造列表绘制失败", t);
            }
        };

        table.add("[accent]组合工厂[] [lightgray]· 多倍建造配置").left().padBottom(4f).row();
        table.add("[lightgray]点击单位展开配置，再次点击收起。[]").left().wrap().padBottom(8f).row();

        TextField field = new TextField();
        field.setMessageText("搜索单位（英文名 / 中文名）");
        field.setText(searchText);
        field.changed(() -> {
            searchText = field.getText();
            query[0] = field.getText();
            rebuild[0].run();
        });
        table.add(field).growX().left().row();

        addFilterRow(table, whole, new int[]{FILTER_ALL, FILTER_HAS, FILTER_NONE},
                new String[]{"全部", "有多倍建造", "无多倍建造"});

        ScrollPane pane = new ScrollPane(list);
        pane.setFadeScrollBars(false);
        pane.setScrollingDisabled(true, false);
        pane.setOverscroll(false, false);
        table.add(pane).growX().height(420f).padTop(6f).left().row();

        rebuild[0].run();
    }

    private static void buildRow(Table parent, UnitType u, Runnable[] rebuild){
        boolean has = getWeapon(u) != null;
        boolean isExpanded = expanded.contains(u.name);

        Table row = new Table();
        row.left();

        row.button(t -> {
            t.left().margin(4f);
            if(u.uiIcon != null) t.image(u.uiIcon).size(28f).padRight(6f);
            String state = has ? "[accent]已启用[]" : "[gray]未启用[]";
            t.add(state + " [white]" + u.localizedName + " [gray]" + u.name + "[]")
                    .left().growX().wrap().minWidth(0f);
            t.add(isExpanded ? "[lightgray]▲[]" : "[lightgray]▼[]").right().padLeft(4f).padRight(4f);
        }, Styles.grayt, () -> {
            if(expanded.contains(u.name)) expanded.remove(u.name);
            else expanded.add(u.name);
            defer(rebuild);
        }).growX().left().height(44f);

        if(isExpanded){
            row.row();
            row.table(c -> {
                c.left().margin(6f);
                buildConfig(c, u, rebuild);
            }).growX().left();
        }

        parent.add(row).growX().left().padBottom(2f).row();
    }

    private static void buildConfig(Table c, UnitType u, Runnable[] rebuild){
        boolean has = getWeapon(u) != null;
        TestMultiBuildWeapon w = getWeapon(u);

        int globalDefault = Settings.maxBuild() > 0 ? Settings.maxBuild() : 5;
        int cur = Core.settings.getInt(keyMax(u.name), globalDefault);
        if(w != null && w.maxBuild > 0) cur = w.maxBuild;

        int finalCur = cur;
        c.table(t -> {
            t.left();
            t.add("最大建造数").left().padRight(8f);
            Slider slider = new Slider(1, 50, 1, false);
            slider.setValue(finalCur);
            t.add(slider).growX().minWidth(200f);
            t.label(() -> (int)slider.getValue() + "").width(40f).padLeft(8f);
            slider.moved(v -> {
                int iv = (int)v;
                Core.settings.put(keyMax(u.name), iv);
                TestMultiBuildWeapon ww = getWeapon(u);
                if(ww != null) ww.maxBuild = iv;
            });
            slider.released(() -> Core.settings.saveValues());
        }).growX().left().row();

        boolean boostVal = Core.settings.getBool(keyBoost(u.name), Settings.buildBoost());
        c.check("空建造位额外加速", boostVal, val -> {
            Core.settings.put(keyBoost(u.name), val);
            TestMultiBuildWeapon ww = getWeapon(u);
            if(ww != null) ww.buildBoost = val;
            Core.settings.saveValues();
        }).left().padTop(4f).row();

        c.button(has ? "删除多倍建造" : "添加多倍建造", has ? Icon.cancel : Icon.add, () -> {
            if(has) removeWeapon(u);
            else addWeapon(u);
            defer(rebuild);
        }).growX().height(40f).padTop(6f).left().row();
    }

    public static void addWeapon(UnitType u){
        if(u == null) return;

        // 已经有武器：只同步偏好，不重复加
        if(getWeapon(u) != null){
            setUserEnabled(u.name, true);
            return;
        }

        TestMultiBuildWeapon w = new TestMultiBuildWeapon();
        w.mirror = false;
        w.x = 0f;
        w.y = 2f;
        w.speedMulti = 1f;
        w.maxBuild = Settings.maxBuild() > 0 ? Settings.maxBuild() : 5;

        int savedMax = Core.settings.getInt(keyMax(u.name), 0);
        if(savedMax > 0) w.maxBuild = savedMax;

        if(Core.settings.has(keyBoost(u.name)))
            w.buildBoost = Core.settings.getBool(keyBoost(u.name), true);

        if(!Vars.headless && Core.atlas != null) w.load();
        u.weapons.add(w);

        // 关键：把"启用"写盘，重启后 Main 会读回
        setUserEnabled(u.name, true);

        Groups.unit.each(un -> un.type == u, un -> un.setupWeapons(u));
    }

    public static void removeWeapon(UnitType u){
        if(u == null) return;
        u.weapons.removeAll(weapon -> weapon instanceof TestMultiBuildWeapon);

        // 关键：把"删除"写盘，重启后 Main 不会重挂
        setUserEnabled(u.name, false);

        Groups.unit.each(un -> un.type == u, un -> un.setupWeapons(u));
    }
}
