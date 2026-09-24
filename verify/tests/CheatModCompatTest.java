package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.func.Prov; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："组合单位和 `~/sd/questions/` 里的无敌作弊模组冲突，直接崩"（附了崩溃日志）。
 *
 * <p>日志（安卓 160.5，模组 combine:2.6 / combineunit:1 / invincible-cheat-mod-v8:4.1.0）：
 * <pre>
 * combineunit.units.UnitComboDamage.replaceUnitConstructors(UnitComboDamage.java:65)
 *   → Rhino JavaAdapter 定义 adapter27 失败 → ClassNotFoundException: adapter27
 *   → suppressed NoClassDefFoundError: combineunit/units/entities/CUnitEntityLegacyAlpha
 * </pre>
 *
 * <p>根因：作弊模组 {@code scripts/super-cheat/invincible-ship.js:132} 写的是
 * <pre>m.constructor = prov(() => extend(UnitTypes.alpha.constructor.get().class, { damage(amount){} }));</pre>
 * 也就是**继承 alpha（核心机）构造器产出的类**。我们把**所有**单位类型的构造器都换成了自己的镜像类，
 * 核心机 alpha 也不例外 → 它的构造器产出 `combineunit.units.entities.CUnitEntityLegacyAlpha`，
 * 于是作弊模组的 JS 去继承一个**模组类**；安卓上 Rhino 的 JavaAdapter 在内存 dex 里解析不了模组类
 * → 适配器定义失败 → 异常从我们的 `register()` 抛出去 → combineunit 加载失败、游戏崩。
 *
 * <p>修法：①核心机（`UnitTypes.alpha/beta/gamma/evoke/incite/emanate`，以及任何 coreUnitDock 类型）
 * **一律不换构造器**（它们本来就不参与组合，换了只有害）；②两处"取样"都包上 try/catch ——
 * 别的模组写的动态构造器（JS/JavaAdapter）取样失败时跳过那个类型，绝不把异常冒到 Mod.init() 外面。
 *
 * <p>本测试要有**combine + combineunit + 作弊模组**三件的数据目录（例如 `/tmp/mp_cheat/data`）。
 */
public class CheatModCompatTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cheat/data";
    static int pass=0, fail=0;
    static ClassLoader cml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new CheatModCompatTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[CC] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    /** 模组内容的单位名带前缀（实测 "invincible-cheat-mod-v8-invincible-ship"），所以按后缀找。 */
    static UnitType byName(String name){
        for(UnitType t : Vars.content.units()) if(t.name.equals(name)) return t;
        for(UnitType t : Vars.content.units()) if(t.name.endsWith("-" + name)) return t;
        return null;
    }
    static String cls(Object o){ return o == null ? "null" : o.getClass().getName(); }

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        Vars.mods.eachClass(Mod::init);
        try{ arc.Events.fire(mindustry.game.EventType.ContentInitEvent.class); }catch(Throwable t){
            System.out.println("[CC] 触发 ContentInitEvent 失败: " + t);
        }
        if(Vars.logic==null) Vars.logic=new Logic();
        if(Vars.netServer==null) Vars.netServer=new NetServer();
        if(Vars.netClient==null) Vars.netClient=new NetClient();

        {   // 诊断：作弊模组到底加了哪些单位/方块（名字里带 invincible/dps/tester 的）
            StringBuilder found = new StringBuilder();
            for(UnitType t : Vars.content.units())
                if(t.name.contains("invincible") || t.name.contains("dps") || t.name.contains("tester"))
                    found.append(t.name).append(' ');
            System.out.println("[CC] 作弊模组相关单位: " + (found.length() == 0 ? "（无）" : found.toString())
                + "；单位总数=" + Vars.content.units().size);
        }
        StringBuilder mods = new StringBuilder();
        for(var m : Vars.mods.list()) mods.append(m.name).append(':').append(m.meta.version).append(' ');
        System.out.println("[CC] 已加载模组: " + mods);
        var cheat = Vars.mods.getMod("invincible-cheat-mod-v8");
        var unit = Vars.mods.getMod("combineunit");
        if(cheat == null){ System.out.println("[CC] 数据目录里没有作弊模组（跳过）"); System.exit(0); }
        if(unit == null){ System.out.println("[CC] 数据目录里没有 combineunit（跳过）"); System.exit(0); }
        cml = Vars.mods.getMod("combine").main.getClass().getClassLoader();

        // ---------- ① 核心机的构造器必须是**原版**的（不能是我们的镜像类） ----------
        UnitType alpha = byName("alpha");
        String alphaCls = alpha == null ? "null" : cls(alpha.constructor.get());
        System.out.println("[CC] alpha 构造器产出类=" + alphaCls);
        check("核心机 alpha 的构造器仍是原版类（不是 combineunit 镜像）",
            alphaCls.startsWith("mindustry.") && !alphaCls.startsWith("combineunit."));
        for(String n : new String[]{"beta", "gamma"}){
            UnitType t = byName(n);
            if(t == null) continue;
            String c = cls(t.constructor.get());
            check("核心机 " + n + " 的构造器仍是原版类（" + c + "）",
                c.startsWith("mindustry.") && !c.startsWith("combineunit."));
        }

        // ---------- ② 作弊模组的 JS 动态单位能正常创建（继承的是原版类） ----------
        for(String n : new String[]{"invincible-ship", "dps-tester-land", "dps-tester-air"}){
            UnitType t = byName(n);
            if(t == null){ System.out.println("[CC] （没有单位 " + n + "，跳过）"); continue; }
            Object made = null; String err = null;
            try{ made = t.constructor.get(); }catch(Throwable e){ err = e.toString(); }
            String madeCls = cls(made);
            String superCls = made == null ? "-" : made.getClass().getSuperclass().getName();
            System.out.println("[CC] 作弊模组单位 " + n + ": 造出=" + madeCls + " 父类=" + superCls
                + (err == null ? "" : " 抛错=" + err));
            check("作弊模组单位 " + n + " 能正常造出来（继承原版类，不含 combineunit 类）",
                made != null && !madeCls.startsWith("combineunit.") && !superCls.startsWith("combineunit."));
        }

        // ---------- ③ 一个"取样就抛"的构造器不许拖垮整个注册 ----------
        // 模拟别的模组写了会抛的动态构造器（安卓上 JavaAdapter 解析失败就是这种）
        UnitType fake = new UnitType("cc-throwing-unit");
        fake.constructor = () -> { throw new RuntimeException("模拟别的模组的坏构造器"); };
        try{
            Class<?> dmg = Class.forName("combineunit.units.UnitComboDamage", true,
                unit.main.getClass().getClassLoader());
            dmg.getMethod("register").invoke(null);   // 再跑一遍注册（幂等）
            check("构造器取样失败不会把 register() 弄崩（坏类型被跳过）", true);
        }catch(Throwable t){
            System.out.println("[CC] register() 抛了: " + t);
            check("构造器取样失败不会把 register() 弄崩（坏类型被跳过）", false);
        }
        String fakeMsg = catchMsg(fake.constructor);
        check("坏构造器保持原样（没有被替换，实测=\"" + fakeMsg + "\"）",
            fakeMsg.contains("模拟别的模组的坏构造器"));
        UnitType dagger = byName("dagger");
        check("普通单位照旧被换成 combineunit 镜像（dagger → " + cls(dagger.constructor.get()) + "）",
            cls(dagger.constructor.get()).startsWith("combineunit.units.entities."));

        // ---------- ④ 合体/共享承伤在这套模组组合下仍然可用 ----------
        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.state.rules.waves = false;
        Vars.state.rules.canGameOver = false;
        Vars.logic.play();
        run(20);
        int ox=-1, oy=-1;
        outer:
        for(int y=45;y<150;y++) for(int x=40;x<220;x++){
            boolean ok = true;
            for(int dy=-2;dy<=2 && ok;dy++) for(int dx=-3;dx<=3;dx++){
                Tile t = Vars.world.tile(x+dx, y+dy);
                if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
            }
            if(ok){ ox=x; oy=y; break outer; }
        }
        if(ox < 0){ System.out.println("[CC] 没找到陆地"); System.exit(3); }
        Building coreB;
        {
            int ax = ox + 20, ay = oy + 10;
            mindustry.world.Build.beginPlace(null, Blocks.coreShard, Team.sharded, ax, ay, 0, null);
            mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), Blocks.coreShard, null, (byte)0, Team.sharded, null);
            coreB = Vars.world.build(ax, ay);
        }
        run(10);
        arc.struct.Seq<Unit> us = new arc.struct.Seq<>();
        for(int i = 0; i < 3; i++){
            Unit u = UnitTypes.dagger.create(Team.sharded);
            u.set(ox * 8f + i * 12f, oy * 8f);
            u.add();
            us.add(u);
        }
        run(3);
        Object merged = null;
        try{
            Class<?> merge = Class.forName("combineunit.units.UnitComboMerge", true, unit.main.getClass().getClassLoader());
            merged = merge.getMethod("mergeSelected", arc.struct.Seq.class).invoke(null, us);
        }catch(Throwable t){ System.out.println("[CC] 融合失败: " + t); }
        check("这三件模组一起装时仍能合体（" + cls(merged) + "）",
            merged instanceof Unit mu && mu.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity"));

        System.out.println("[CC] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
    static String catchMsg(Prov<?> p){
        try{ p.get(); return "（没抛）"; }catch(Throwable t){ return t.toString(); }
    }
}
