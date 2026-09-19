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
 * 全部数据集中存在 {@link #dataSeq} 里，以 {@link MultiBuildData} 为单位：
 *   name       单位内部名
 *   maxBuild   并行建造数（0 = 走全局默认）
 *   buildBoost 空建造位额外加速
 *   using      用户是否启用多倍建造（点添加/删除时写入；没设置过 = 走 Main 硬编码默认）
 *
 * 序列化后整体保存在 {@code Core.settings} 的 {@link #DATA_KEY} 键里（每行一条，
 * 逗号分隔）。{@link #load()} / {@link #save()} 负责读写。
 */
public class MultiBuildList {

    public static final int FILTER_ALL = 0, FILTER_HAS = 1, FILTER_NONE = 2;
    private static int filterMode = FILTER_ALL;
    private static String searchText = "";
    private static final ObjectSet<String> expanded = new ObjectSet<>();

    /** 单一数据源。整个界面所有配置都读写这里。 */
    public static final Seq<MultiBuildData> dataSeq = new Seq<>();
    private static final String DATA_KEY = "combine-multibuild-data";
    private static boolean loaded = false;
    private static boolean migrated = false;

    // ==================== 数据持久化 ====================

    /** 读入 dataSeq（幂等）。 */
    public static void load(){
        if(loaded) return;
        loaded = true;
        dataSeq.clear();
        if(Core.settings == null) return;
        String raw = Core.settings.getString(DATA_KEY, "");
        if(raw.isEmpty()) return;
        for(String line : raw.split("\n")){
            if(line.trim().isEmpty()) continue;
            try{
                dataSeq.add(MultiBuildData.of(line));
            }catch(Throwable t){
                Log.err("[combine] 读取多倍建造数据失败: " + line, t);
            }
        }
    }

    public static void save(){
        if(Core.settings == null) return;
        StringBuilder sb = new StringBuilder();
        for(MultiBuildData d : dataSeq){
            if(sb.length() > 0) sb.append('\n');
            sb.append(d.encode());
        }
        Core.settings.put(DATA_KEY, sb.toString());
        Core.settings.saveValues();
    }

    public static void init(){
        load();
    }

    public static MultiBuildData get(String name){
        if(name == null) return null;
        return dataSeq.find(d -> name.equals(d.name));
    }

    public static MultiBuildData getOrCreate(String name){
        MultiBuildData d = get(name);
        if(d == null){
            d = new MultiBuildData(name);
            dataSeq.add(d);
        }
        return d;
    }

    // ==================== 读值（界面 / Main 用） ====================

    /** 用户对该单位是否启用多倍建造；null = 没设置过（走 Main 的硬编码默认）。 */
    public static Boolean userEnabled(String name){
        MultiBuildData d = get(name);
        return d == null ? null : d.using;
    }

    public static void setUserEnabled(String name, boolean enabled){
        if(Core.settings == null || name == null) return;
        getOrCreate(name).using = enabled;
        save();
    }

    /** 界面里显示的最大建造数（优先用户值，其次全局默认）。 */
    public static int maxBuild(UnitType u){
        MultiBuildData d = get(u.name);
        if(d != null && d.maxBuild > 0) return d.maxBuild;
        return Settings.maxBuild() > 0 ? Settings.maxBuild() : 5;
    }

    /** 界面里显示的加速开关（优先用户值，其次全局默认）。 */
    public static boolean buildBoost(UnitType u){
        MultiBuildData d = get(u.name);
        return d != null ? d.buildBoost : Settings.buildBoost();
    }

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

    // ==================== 界面 ====================

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
        load();
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

        int cur = maxBuild(u);
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
                getOrCreate(u.name).maxBuild = iv;
                TestMultiBuildWeapon ww = getWeapon(u);
                if(ww != null) ww.maxBuild = iv;
            });
            // 松手才写盘，拖动过程只改内存
            slider.released(MultiBuildList::save);
        }).growX().left().row();

        boolean boostVal = buildBoost(u);
        c.check("空建造位额外加速", boostVal, val -> {
            getOrCreate(u.name).buildBoost = val;
            TestMultiBuildWeapon ww = getWeapon(u);
            if(ww != null) ww.buildBoost = val;
            save();
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

        MultiBuildData d = getOrCreate(u.name);
        d.using = true;
        if(d.maxBuild <= 0){
            d.maxBuild = Settings.maxBuild() > 0 ? Settings.maxBuild() : 5;
        }

        TestMultiBuildWeapon w = new TestMultiBuildWeapon();
        w.mirror = false;
        w.x = 0f;
        w.y = 2f;
        w.speedMulti = 1f;
        w.maxBuild = d.maxBuild;
        w.buildBoost = d.buildBoost;

        if(!Vars.headless && Core.atlas != null) w.load();
        u.weapons.add(w);

        save();
        Groups.unit.each(un -> un.type == u, un -> un.setupWeapons(u));
    }

    public static void removeWeapon(UnitType u){
        if(u == null) return;
        u.weapons.removeAll(weapon -> weapon instanceof TestMultiBuildWeapon);
        getOrCreate(u.name).using = false;
        save();
        Groups.unit.each(un -> un.type == u, un -> un.setupWeapons(u));
    }

    // ==================== 数据类 ====================

    public static class MultiBuildData {
        public String name;
        public boolean buildBoost, using;
        public int maxBuild;

        public MultiBuildData(String name){
            this.name = name;
        }

        public MultiBuildData(String name, boolean buildBoost, int maxBuild){
            this(name);
            this.buildBoost = buildBoost;
            this.maxBuild = maxBuild;
        }

        public static MultiBuildData of(String str){
            MultiBuildData data = new MultiBuildData("");
            data.decode(str);
            return data;
        }

        public void decode(String str){
            String[] a = str.split(",");
            name = a[0];
            if(a.length >= 2) buildBoost = Boolean.parseBoolean(a[1]);
            if(a.length >= 3) maxBuild = parseIntSafe(a[2], 0);
            if(a.length >= 4) using = Boolean.parseBoolean(a[3]);
        }

        public UnitType type(){
            return Vars.content.unit(name);
        }

        public String encode(){
            return name + "," + buildBoost + "," + maxBuild + "," + using;
        }

        private static int parseIntSafe(String s, int def){
            try{
                return Integer.parseInt(s.trim());
            }catch(Throwable t){
                return def;
            }
        }
    }
}