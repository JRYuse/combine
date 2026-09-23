package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.UnitType; import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.meta.Env;

/**
 * 用户报：**任意埃里克尔地图、任意埃里克尔单位，合体后直接爆炸**（用户自己怀疑"组合单位的环境适应
 * 与埃里克尔不匹配"——查证属实）。
 *
 * 根因：埃里克尔地图的 `state.rules.env` 是 `Env.scorching | Env.terrestrial`
 * （`Planets.erekir.defaultEnv`），而原版 `UnitType` 的默认口径是
 * `envEnabled = Env.terrestrial`、`envDisabled = Env.scorching`（塞普罗口径）。
 * 埃里克尔单位自己都是 `ErekirUnitType`（把 `envDisabled` 改成 `Env.space`，不禁灼热），
 * 可组合巨兽的 `MegaUnitType` 是模组 late 注册的，**从没按成员推导过这两个字段** ——
 * 于是合体之后 `UnitComp.update()` 里 `!type.supportsEnv(state.rules.env)` 立刻成立：
 * <pre>
 *   if(!type.supportsEnv(state.rules.env) && !dead){
 *       Call.unitEnvDeath(self());   // ← 单位当场死亡（玩家视角就是"合体后直接爆炸"）
 *   }
 * </pre>
 *
 * 本测试把 env 改成埃里克尔那样，再用**埃里克尔单位**合体，验：
 *   ① 占位巨兽类型自己就得支持这种环境（成员还没推导出来时的兜底）；
 *   ② 按成员推导出来的巨兽类型要支持（envEnabled 并集 / envDisabled 交集）；
 *   ③ 合体后 2 秒巨兽仍然活着。
 * 反过来：纯塞普罗单位合体后仍然保持塞普罗口径（不能在灼热环境待着）——原版塞普罗单位在
 * 埃里克尔地图上本来就会死，这里只打印，不做断言（避免把"塞普罗单位也能在埃里克尔活"钉死）。
 */
public class MegaEnvTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[ME] "+t); };
        new HeadlessApplication(new MegaEnvTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[ME] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static Class<?> combineClass(String name){
        try{ return Class.forName(name, true, ml); }catch(Throwable t){ return null; }
    }
    static boolean supportsEnv(UnitType t, int env){ return t != null && t.supportsEnv(env); }
    static boolean UnitComboMerge_isCore(UnitType t){
        try{
            return (Boolean) Class.forName("combine.units.UnitComboMerge", true, ml)
                .getMethod("isCoreUnit", UnitType.class).invoke(null, t);
        }catch(Throwable e){ return false; }
    }
    static String envName(int env){
        StringBuilder sb = new StringBuilder();
        if((env & Env.terrestrial) != 0) sb.append("terrestrial ");
        if((env & Env.scorching) != 0) sb.append("scorching ");
        if((env & Env.space) != 0) sb.append("space ");
        if((env & Env.underwater) != 0) sb.append("underwater ");
        return sb.toString().trim();
    }

    static Unit mergeAt(Class<?> mergeCls, float x, float y, UnitType... types){
        try{
            Seq<Unit> units = new Seq<>();
            for(int i = 0; i < types.length; i++){
                Unit u = types[i].create(Team.sharded);
                u.set(x + i * 24f, y);
                u.add();
                units.add(u);
                System.out.println("[ME]   造出 " + types[i].name + " 位置=" + (int)u.x + "," + (int)u.y
                    + " 血=" + (int)u.health + "/" + (int)u.maxHealth + " 已加=" + u.isAdded()
                    + " floor=" + (Vars.world.tileWorld(u.x, u.y) == null ? "无" : Vars.world.tileWorld(u.x, u.y).floor().name));
            }
            for(int k = 0; k < 2; k++){
                run(1);
                for(Unit u : units){
                    System.out.println("[ME]   tick" + k + " " + u.type.name + " 血=" + (int)u.health
                        + " 死亡=" + u.dead + " 已加=" + u.isAdded()
                        + " supportsEnv=" + supportsEnv(u.type, Vars.state.rules.env));
                }
            }
            for(Unit u : units){
                Object groupable = null;
                try{
                    Class<?> dmg = Class.forName("combine.units.UnitComboDamage", true, ml);
                    groupable = dmg.getMethod("groupable", Unit.class).invoke(null, u);
                }catch(Throwable ignored){}
                System.out.println("[ME]   成员 " + u.type.name + " 已加=" + u.isAdded()
                    + " 有效=" + u.isValid() + " 死亡=" + u.dead + " hittable=" + u.hittable()
                    + " 核心单位=" + UnitComboMerge_isCore(u.type) + " 可编组=" + groupable
                    + " 地面=" + u.floorOn().name);
            }
            return (Unit)mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, units);
        }catch(Throwable t){ System.out.println("[ME] 融合失败: " + t); return null; }
    }

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        Vars.mods.eachClass(Mod::init);
        if(Vars.logic==null) Vars.logic=new Logic();
        if(Vars.netServer==null) Vars.netServer=new NetServer();
        if(Vars.netClient==null) Vars.netClient=new NetClient();
        ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        Class<?> mergeCls = combineClass("combine.units.UnitComboMerge");
        if(mergeCls == null){ System.out.println("[ME] 找不到 UnitComboMerge"); System.exit(3); }

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);

        // 【模拟埃里克尔地图】env = scorching | terrestrial（见 Planets.erekir.defaultEnv）
        int erekirEnv = Env.scorching | Env.terrestrial;
        Vars.state.rules.env = erekirEnv;
        System.out.println("[ME] env 已设为: " + envName(erekirEnv));

        // 占位类型（成员还没推导出来时的兜底）必须也支持
        UnitType placeholder = null;
        try{ placeholder = (UnitType) combineClass("combine.units.UnitComboMerge").getField("megaGround").get(null); }
        catch(Throwable t){ System.out.println("[ME] 取 megaGround 失败: " + t); }
        if(placeholder != null){
            System.out.println("[ME] 占位类型 envEnabled=" + placeholder.envEnabled
                + " envDisabled=" + placeholder.envDisabled + " envRequired=" + placeholder.envRequired);
            check("占位巨兽类型支持埃里克尔环境（成员未推导时不会当场环境死亡）", supportsEnv(placeholder, erekirEnv));
        }
        if(placeholder != null && (placeholder.envDisabled & Env.scorching) != 0){
            check("占位类型不再带塞普罗口径的禁灼热", false);
        }

        // 埃里克尔单位（都是 ErekirUnitType：envDisabled = Env.space，不禁灼热）
        Seq<UnitType> erekirCandidates = new Seq<>();
        for(String n : new String[]{"stell", "merui", "locus", "cleroi", "tecta", "anthicus", "vanquish"}){
            for(UnitType t : Vars.content.units()){
                if(t.name.equals(n) && (t.envDisabled & Env.scorching) == 0) erekirCandidates.add(t);
            }
        }
        if(erekirCandidates.size < 2){
            System.out.println("[ME] 找不到两个埃里克尔单位（数据目录没有官方单位？）");
            System.exit(3);
        }
        UnitType e1 = erekirCandidates.get(0), e2 = erekirCandidates.get(1);
        System.out.println("[ME] 埃里克尔单位: " + e1.name + " / " + e2.name
            + "（各自 envEnabled=" + e1.envEnabled + " envDisabled=" + e1.envDisabled + "）");
        for(UnitType t : new UnitType[]{e1, e2}){
            check("成员 " + t.name + " 自己能待在埃里克尔环境里（前置条件）", supportsEnv(t, erekirEnv));
        }

        // 【注意单位】world.width()/height() 才是瓦片数，unitWidth()/unitHeight() 是像素
        int tx0 = -1, ty0 = -1;
        outer:
        for(int y = Vars.world.height() / 3; y < Vars.world.height() - 6; y++){
            for(int x = Vars.world.width() / 3; x < Vars.world.width() - 6; x++){
                boolean ok = true;
                for(int dy = 0; dy < 4 && ok; dy++){
                    for(int dx = 0; dx < 8; dx++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor().isDeep() || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                }
                if(ok){ tx0 = x; ty0 = y; break outer; }
            }
        }
        if(tx0 < 0){ System.out.println("[ME] 找不到陆地"); System.exit(3); }
        float px = (tx0 + 2f) * 8f, py = (ty0 + 2f) * 8f;
        System.out.println("[ME] 融合点（瓦片）= " + (tx0 + 2) + "," + (ty0 + 2) + " floor=" + Vars.world.tile(tx0 + 2, ty0 + 2).floor().name);

        Unit mega = mergeAt(mergeCls, px, py, e1, e2);
        if(mega == null){ check("埃里克尔单位能融出巨兽", false); }
        else{
            UnitType mt = mega.type;
            System.out.println("[ME] 巨兽类型 " + mt.name + " envEnabled=" + mt.envEnabled
                + " envDisabled=" + mt.envDisabled + " envRequired=" + mt.envRequired
                + " supportsEnv=" + supportsEnv(mt, erekirEnv));
            check("巨兽类型按成员推导出能待在灼热环境（envDisabled 不含 scorching）",
                (mt.envDisabled & Env.scorching) == 0);
            check("巨兽类型支持埃里克尔环境", supportsEnv(mt, erekirEnv));
            int id = mega.id;
            run(1);
            boolean rightAfter = Groups.unit.contains(u -> u.id == id);
            run(119);
            boolean after2s = Groups.unit.contains(u -> u.id == id);
            System.out.println("[ME] 巨兽 id=" + id + " 融合后立刻在=" + rightAfter + " 2 秒后还在=" + after2s
                + " 死亡=" + mega.dead + " 血量=" + (int) mega.health + "/" + (int) mega.maxHealth);
            check("埃里克尔地图上合体后立即存活", rightAfter);
            check("埃里克尔地图上合体后 2 秒仍存活（不环境死亡）", after2s && !mega.dead);
        }

        // 纯塞普罗单位：保持塞普罗口径（这里只打印，因为原版塞普罗单位在埃里克尔本来就会死）
        Vars.state.rules.env = erekirEnv;
        Unit serpulo = mergeAt(mergeCls, px, py + 48f, UnitTypes.dagger, UnitTypes.fortress);
        if(serpulo != null){
            System.out.println("[ME] 塞普罗编组（对照组）: 巨兽 envDisabled=" + serpulo.type.envDisabled
                + " supportsEnv=" + supportsEnv(serpulo.type, erekirEnv)
                + "（原版 dagger 自己 envDisabled=" + UnitTypes.dagger.envDisabled + "）");
        }

        System.out.println("[ME] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
