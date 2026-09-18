package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.*;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.blocks.defense.turrets.*;

/**
 * 升华站（sublimate，ContinuousLiquidTurret，弹药是臭氧/氰气两种液体）：
 * 两种液体都满时必须能开火（用户报的"都输满了但不能发射"）。
 */
public class SublimateTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new SublimateTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[SL] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Object field(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return null; }
    static Object call(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var m = c.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(o); }
                catch(NoSuchMethodException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return null;
    }
    static void fieldSet(Object o, String name, Object v){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField(name); f.setAccessible(true); f.set(o, v); return; }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
    }
    static int bullets(Building b){
        Object seq = field(b, "bullets");
        return seq instanceof arc.struct.Seq<?> s ? s.size : -1; }

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

        Block sub = null;
        for(Block b : Vars.content.blocks()) if(b.name.equals("sublimate")) sub = b;
        if(sub == null){ System.out.println("[SL] 没有 sublimate"); System.exit(3); }
        System.out.println("[SL] sublimate 类=" + sub.getClass().getName());

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        for(int y=40;y<160;y++) for(int x=20;x<260;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        Building tower = place(sub, 60, 60, Team.sharded);
        run(10);
        // 两种弹药都灌满
        tower.liquids.add(Liquids.ozone, tower.block.liquidCapacity > 1000 ? 1000f : tower.block.liquidCapacity);
        tower.liquids.add(Liquids.cyanogen, tower.block.liquidCapacity > 1000 ? 1000f : tower.block.liquidCapacity);
        run(10);
        System.out.println("[SL] 灌满后: 臭氧=" + tower.liquids.get(Liquids.ozone) + " 氰气=" + tower.liquids.get(Liquids.cyanogen)
            + " current=" + (tower.liquids.current() == null ? "无" : tower.liquids.current().name)
            + " activated=" + field(tower, "activated")
            + " canConsume=" + tower.canConsume()
            + " shouldConsume=" + tower.shouldConsume());

        // 放一个敌人在射程内
        Unit enemy = UnitTypes.dagger.create(Team.crux);
        enemy.maxHealth = 1e9f; enemy.health = 1e9f;   // 别被光束秒掉，靶子要撑住
        enemy.set(60 * 8f + 60f, 60 * 8f);
        enemy.add();
        run(10);
        // 直接指定目标：headless 里 findEnemy 不认我们手动 add 的单位，绕过去测"开火链路"
        fieldSet(tower, "target", enemy);
        run(60);
        System.out.println("[SL] 详情: 敌人数=" + Groups.unit.size() + " target=" + field(tower, "target")
            + " isShooting=" + field(tower, "isShooting") + " reloadCounter=" + field(tower, "reloadCounter")
            + " reload=" + field(tower, "reload") + " efficiency=" + field(tower, "efficiency")
            + " enabled=" + field(tower, "enabled") + " hasAmmo=" + field(tower, "activated")
            + " heat=" + field(tower, "heat") + " team=" + tower.team + " 目标同队?=" + (field(tower, "target") instanceof Building));
        System.out.println("[SL] 有敌人后: activated=" + field(tower, "activated")
            + " shouldConsume=" + tower.shouldConsume()
            + " canConsume=" + tower.canConsume()
            + " 光束数=" + bullets(tower)
            + " 臭氧=" + tower.liquids.get(Liquids.ozone) + " 氰气=" + tower.liquids.get(Liquids.cyanogen));
        check("单台：两种弹药都有时能开火", Boolean.TRUE.equals(field(tower, "isShooting")));
        check("单台：开火消耗的是弹药液体（臭氧或氰气）",
            tower.liquids.get(Liquids.ozone) < 45f || tower.liquids.get(Liquids.cyanogen) < 45f);
        check("单台：不会去消耗非弹药液体（水）", Math.abs(tower.liquids.get(Liquids.water)) < 0.01f);

        // ---------- 组合体（多台升华站一组）也要能开火 ----------
        for(int y=40;y<160;y++) for(int x=20;x<260;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
        int stride = Math.max(sub.size, 1);
        Building t1 = place(sub, 60, 60, Team.sharded);
        Building t2 = place(sub, 60 + stride, 60, Team.sharded);
        run(20);
        for(Building t : new Building[]{t1, t2}){
            t.liquids.add(Liquids.ozone, 2000f);
            t.liquids.add(Liquids.cyanogen, 2000f);
            // 混入非弹药液体（组合体里很常见：旁边有水/废液的机器），不能因此判定成"没弹药"
            t.liquids.add(Liquids.water, 2000f);
        }
        run(20);
        System.out.println("[SL] 组合前: 同池=" + (t1.liquids == t2.liquids)
            + " 组容量=" + field(t1, "comboTotalLiquidCap")
            + " 臭氧=" + t1.liquids.get(Liquids.ozone) + " 氰气=" + t1.liquids.get(Liquids.cyanogen));
        Unit e2 = UnitTypes.dagger.create(Team.crux);
        e2.maxHealth = 1e9f; e2.health = 1e9f;
        e2.set(60 * 8f + 40f, 60 * 8f);
        e2.add();
        run(10);
        fieldSet(t1, "target", e2);
        fieldSet(t2, "target", e2);
        run(3);
        System.out.println("[SL] 敌人状态: valid=" + e2.isValid() + " dead=" + e2.dead + " 血=" + e2.health
            + " 位置=" + (int) e2.x + "," + (int) e2.y + " 距离=" + (int) Math.sqrt(e2.dst2(t1))
            + " 射程=" + field(t1.block, "range"));
        run(7);
        System.out.println("[SL] 组合后: activated=" + field(t1, "activated") + " isShooting=" + field(t1, "isShooting")
            + " shouldConsume=" + t1.shouldConsume() + " efficiency=" + field(t1, "efficiency")
            + " 臭氧=" + t1.liquids.get(Liquids.ozone) + " 氰气=" + t1.liquids.get(Liquids.cyanogen));
        for(var pair : new Object[][]{{t1, "t1"}, {t2, "t2"}}){
            Building t = (Building) pair[0];
            System.out.println("[SL]   " + pair[1] + ": target=" + field(t, "target") + " isShooting=" + field(t, "isShooting")
                + " efficiency=" + field(t, "efficiency") + " canConsume=" + t.canConsume() + " shouldConsume=" + t.shouldConsume()
                + " activated=" + field(t, "activated") + " 是队长=" + field(t, "comboLeader"));
        }
        // 直接验根因：池里混了非弹药液体时，弹药指向必须还是臭氧/氰气
        Object eff = call(t1, "effectiveLiquid");
        Object peek = call(t1, "peekAmmo");
        System.out.println("[SL] 组合体(混水) 弹药指向=" + eff + " peekAmmo=" + peek);
        check("池里混水时弹药液体仍指向臭氧/氰气（不是水）",
            eff == Liquids.ozone || eff == Liquids.cyanogen);
        check("peekAmmo() 不为 null（原版 range() 直接用它，null 会抛 key cannot be null）", peek != null);
        check("开火时不会把非弹药液体（水）当弹药消耗",
            Math.abs(t1.liquids.get(Liquids.water) - 100f) < 1f);

        System.out.println("[SL] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
