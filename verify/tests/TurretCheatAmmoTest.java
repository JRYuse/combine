package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.Item;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.blocks.defense.turrets.*;

/**
 * 用户报的崩溃（Android / MindustryX 2026.09.X37）：
 * <pre>
 * java.lang.NullPointerException: Attempt to read from field
 *   'mindustry.entities.Units$Sortf mindustry.entities.bullet.BulletType.unitSort' on a null object reference
 *     at mindustry.world.blocks.defense.turrets.Turret$TurretBuild.findEnemy
 *     at mindustry.world.blocks.defense.turrets.Turret$TurretBuild.findTarget
 *     at mindustry.world.blocks.defense.turrets.Turret$TurretBuild.updateTile
 *     at combine.turret.CombinedItemTurret$CombinedItemTurretBuild.updateTile
 * </pre>
 *
 * 根因：hasAmmo() 与 peekAmmo() 判定不一致 —— 池子里只有"本塔用不了的弹药"时（跨类型组合共享
 * 弹药池、对端快照、旧存档），原版 updateTile 里"找目标/开火"整段套在 if(hasAmmo()) 里，
 * 而 findEnemy 第一行就无检查解引用 peekAmmo().unitSort。**作弊模式**（team.rules().cheat）
 * 那条早退以前是"ammo 非空就算有弹药"，是最容易踩到的一路。
 *
 * 断言：不变量 hasAmmo()==true ⇒ peekAmmo()!=null 必须成立；跑 updateTile 不许抛异常；
 * 作弊模式下"有能用的弹药"仍要照常开火（不能为了防崩把作弊炮塔变哑巴）。
 */
public class TurretCheatAmmoTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()){ System.out.println("[L] "+t);} };
        new HeadlessApplication(new TurretCheatAmmoTest(), t->t.printStackTrace()); }

    static void check(String n, boolean ok){ System.out.println("[TCA] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }

    /**
     * 推 tick。这里必须**同时**推进 {@code Time.time}：炮塔找目标是
     * {@code timer(timerTarget, targetInterval)} 卡的（20 tick 一次），而 timer 比的是
     * Time.time —— 只推 delta 不推 time 的话，findTarget 一次都不会被调用，
     * 用户那个崩溃在 headless 里就"复现不出来"（假过）。
     */
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; arc.util.Time.time += 1f; Vars.logic.update(); } }

    /** 跑 updateTile，把异常记下来（用户那个崩溃就是从 updateTile 抛出来的）。 */
    static Throwable runCatching(int f){
        try{ run(f); return null; }catch(Throwable t){ return t; }
    }

    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }

    static Object call(Object o, String name, Class<?>[] sig, Object... args){
        for(Class<?> c = o.getClass(); c != null; c = c.getSuperclass()){
            try{
                java.lang.reflect.Method m = sig == null ? c.getDeclaredMethod(name) : c.getDeclaredMethod(name, sig);
                m.setAccessible(true);
                return m.invoke(o, args);
            }catch(NoSuchMethodException ignored){
            }catch(Throwable t){ return null; }
        }
        return null;
    }

    static boolean hasAmmo(Object b){ return Boolean.TRUE.equals(call(b, "hasAmmo", null)); }
    static Object peekAmmo(Object b){ return call(b, "peekAmmo", null); }

    /** 调一个 protected 方法并把抛出来的异常原样带回来。 */
    static Throwable callThrowing(Object o, String name){
        for(Class<?> c = o.getClass(); c != null; c = c.getSuperclass()){
            try{
                java.lang.reflect.Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                m.invoke(o);
                return null;
            }catch(NoSuchMethodException ignored){
            }catch(java.lang.reflect.InvocationTargetException t){
                return t.getCause();
            }catch(Throwable t){ return t; }
        }
        return null;
    }

    /** 炮塔的弹药队列（TurretBuild.ammo，public 字段）。 */
    @SuppressWarnings("unchecked")
    static Seq<Object> ammoSeq(Building b){
        for(Class<?> c = b.getClass(); c != null; c = c.getSuperclass()){
            try{
                java.lang.reflect.Field f = c.getDeclaredField("ammo");
                f.setAccessible(true);
                Object v = f.get(b);
                if(v instanceof Seq<?> s) return (Seq<Object>)s;
            }catch(NoSuchFieldException ignored){
            }catch(Throwable ignored){ return null; }
        }
        return null;
    }

    /** 往炮塔的弹药队列里塞一条"本塔用不了的弹药"（模拟跨类型组合共享池 / 对端快照的脏数据）。 */
    static boolean injectForeignAmmo(Building turret, ItemTurret block, Item item, int amount){
        try{
            ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
            Class<?> ae = Class.forName("combine.turret.AmmoEntries", true, ml);
            Object entry = ae.getMethod("create", ItemTurret.class, Item.class, int.class)
                .invoke(null, block, item, amount);
            java.lang.reflect.Field f = null;
            for(Class<?> c = turret.getClass(); c != null && f == null; c = c.getSuperclass()){
                try{ f = c.getDeclaredField("ammo"); }catch(NoSuchFieldException ignored){}
            }
            if(f == null) return false;
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Seq<Object> ammo = (Seq<Object>) f.get(turret);
            ammo.add(entry);
            return true;
        }catch(Throwable t){
            System.out.println("[TCA] 注入失败: " + t);
            return false;
        }
    }

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        try{ Vars.mods.eachClass(Mod::init); }catch(Throwable t){ System.out.println("[TCA] eachClass 抛了: " + t); }
        if(Vars.logic==null) Vars.logic=new Logic();
        if(Vars.netServer==null) Vars.netServer=new NetServer();
        if(Vars.netClient==null) Vars.netClient=new NetClient();

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);

        // 找一台"吃铜"的组合物品炮塔
        ItemTurret turret = null;
        for(Block b : Vars.content.blocks()){
            if(b instanceof ItemTurret it && b.getClass().getName().equals("combine.turret.CombinedItemTurret")
                && it.ammoTypes != null && it.ammoTypes.containsKey(Items.copper) && turret == null) turret = it;
        }
        if(turret == null){ System.out.println("[TCA] 没找到组合物品炮塔"); System.exit(3); }
        // 找一种它不收的弹药
        Item foreign = null;
        for(Item it : Vars.content.items())
            if(!turret.ammoTypes.containsKey(it)){ foreign = it; break; }
        if(foreign == null){ System.out.println("[TCA] 没有它不收的物品"); System.exit(3); }
        System.out.println("[TCA] 炮塔=" + turret.name + " 用它不收的弹药=" + foreign.name
            + " ammoPerShot=" + turret.ammoPerShot);

        for(int y=40;y<140;y++) for(int x=20;x<240;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        // ---- 场景 1：作弊模式 + 池子里只有"本塔用不了的弹药" ----
        // 作弊模式（team.rules().cheat）就是用户那边最容易踩到的路：这条早退以前
        // 只要 ammo 非空就 hasAmmo()==true，而 peekAmmo() 是 null → findEnemy 直接 NPE。
        Vars.state.rules.teams.get(Team.sharded).cheat = true;
        Building t1 = place(turret, 60, 60, Team.sharded);
        run(5);
        // 作弊模式下列一落地就会自动补一发"第一种弹药"（原版 ItemTurretBuild.onProximityAdded 的
        // cheat 分支），先把弹仓清空，只留"本塔用不了的弹药"
        Seq<Object> ammo = ammoSeq(t1);
        if(ammo != null) ammo.clear();
        boolean injected = injectForeignAmmo(t1, turret, foreign, 10);
        check("能把\"本塔用不了的弹药\"塞进弹仓（复现前置）", injected && ammoSeq(t1) != null && ammoSeq(t1).size == 1);
        boolean cheat = Boolean.TRUE.equals(call(t1, "cheating", null));
        System.out.println("[TCA] 作弊=" + cheat + " hasAmmo=" + hasAmmo(t1) + " peekAmmo=" + peekAmmo(t1)
            + " 弹仓条目数=" + (ammoSeq(t1) == null ? -1 : ammoSeq(t1).size));
        check("作弊模式下\"只有用不了的弹药\"时 hasAmmo() 必须是 false（旧行为是 true → 崩溃）",
            !hasAmmo(t1));
        check("不变量：hasAmmo()==true ⇒ peekAmmo()!=null（当前 hasAmmo=" + hasAmmo(t1) + " peekAmmo=" + peekAmmo(t1) + "）",
            !(hasAmmo(t1) && peekAmmo(t1) == null));
        // 直接叫一次找目标：不依赖 timer，旧实现这里就是 findEnemy 里的 unitSort NPE
        Throwable direct = callThrowing(t1, "findTarget");
        check("直接调 findTarget() 不崩（旧行为：findEnemy 读 null.unitSort 抛 NPE）", direct == null);
        if(direct != null) System.out.println("[TCA] findTarget 抛出：" + direct);
        Throwable crash = runCatching(120);
        check("跑了 120 tick 的 updateTile 没有抛异常（旧行为：findEnemy NPE）", crash == null);
        if(crash != null) crash.printStackTrace();

        // ---- 场景 2：作弊模式 + 有能用的弹药 → 仍然要"有弹药"（不能把作弊炮塔变哑巴） ----
        if(t1 != null) t1.handleItem(t1, Items.copper);
        run(3);
        System.out.println("[TCA] 补一发铜后: hasAmmo=" + hasAmmo(t1) + " peekAmmo=" + peekAmmo(t1)
            + " getAmmoContent=" + call(t1, "getAmmoContent", null));
        check("作弊模式下有能用的弹药时 hasAmmo() 仍为 true", hasAmmo(t1));
        check("peekAmmo() 指向那发能用的弹药", peekAmmo(t1) != null);
        check("再跑 120 tick 也正常", runCatching(120) == null);

        // ---- 场景 3：关掉作弊，同一份"用不了的弹药"也不能崩 ----
        Vars.state.rules.teams.get(Team.sharded).cheat = false;
        Seq<Object> ammo2 = ammoSeq(t1);
        if(ammo2 != null) ammo2.clear();
        injectForeignAmmo(t1, turret, foreign, 10);
        run(3);
        System.out.println("[TCA] 关作弊+只留用不了的弹药: hasAmmo=" + hasAmmo(t1) + " peekAmmo=" + peekAmmo(t1)
            + " 弹仓条目数=" + (ammoSeq(t1) == null ? -1 : ammoSeq(t1).size));
        Throwable crash2 = runCatching(120);
        check("非作弊模式跑 120 tick 不崩", crash2 == null);
        check("非作弊模式下 hasAmmo()==false（弹药池里只有它不收的那种）", !hasAmmo(t1));
        check("非作弊模式同样满足不变量（hasAmmo ⇒ peekAmmo!=null）", !(hasAmmo(t1) && peekAmmo(t1) == null));

        System.out.println("[TCA] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
