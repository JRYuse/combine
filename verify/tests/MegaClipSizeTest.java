package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.*; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.UnitType;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.blocks.*;

/**
 * 用户报：安卓端"一造东西就闪退"，崩溃栈
 *   UnitEntity.clipSize → EntityGroup.draw → Renderer.draw
 * 里抛 `NullPointerException: Attempt to read from field 'int TextureRegion.width' on a null object reference`。
 *
 * 根因：原版 UnitComp.clipSize() 在"有建造计划"（isBuilding() = 建造队列非空）时会读
 * {@code type.region.width}；而组合巨兽的派生类型没有自己的贴图（region == null），
 * 于是只要把巨兽当建造单位用（它带建造武器、能给建造计划），渲染循环每帧裁剪时就会 NPE 闪退。
 *
 * 这个测试：造一只带工程成员（poly，buildSpeed>0）的巨兽 → 给它排一条建造计划
 * → 直接调 clipSize()（= 渲染循环里调的那个），必须不抛异常且是个正数。
 */
public class MegaClipSizeTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[MC] "+t); };
        new HeadlessApplication(new MegaClipSizeTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MC] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static ClassLoader ml(){ return Vars.mods.getMod("combine").main.getClass().getClassLoader(); }

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
        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        int ox = -1, oy = -1;
        outer:
        for(int y=45;y<140;y++){
            for(int x=40;x<200;x++){
                boolean ok = true;
                for(int dy=-2;dy<=2 && ok;dy++) for(int dx=-3;dx<=3;dx++){
                    Tile t = Vars.world.tile(x+dx, y+dy);
                    if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                }
                if(ok){ ox = x; oy = y; break outer; }
            }
        }
        if(ox < 0){ System.out.println("[MC] 没找到陆地"); System.exit(3); }
        place(Blocks.coreShard, ox + 16, oy + 10, Team.sharded);
        run(10);

        // poly（工程单位，buildSpeed>0）+ mace → 巨兽会带建造能力
        Unit poly = UnitTypes.poly.create(Team.sharded);
        poly.set(ox * 8f - 15f, oy * 8f);
        poly.add();
        Unit mace = UnitTypes.mace.create(Team.sharded);
        mace.set(ox * 8f + 15f, oy * 8f);
        mace.add();
        run(20);

        Unit mega = null;
        try{
            Object m = Class.forName("combine.units.UnitComboMerge", true, ml())
                .getMethod("merge", Unit.class).invoke(null, poly);
            if(m instanceof Unit u) mega = u;
        }catch(Throwable t){ Log.err("[MC] 融合失败", t); }
        if(mega == null){ System.out.println("[MC] 融合失败"); System.exit(3); }
        run(10);
        System.out.println("[MC] 巨兽: type=" + mega.type.name + " buildSpeed=" + mega.type.buildSpeed
            + " type.region=" + (mega.type.region == null ? "null" : "有") + " hitSize=" + mega.hitSize());

        // 平时（没建造计划）不能崩
        float plain = clipSize(mega, "无计划");
        check("没有建造计划时 clipSize 正常（" + plain + "）", plain > 0f);

        // 排一条建造计划 → isBuilding() = true → 原版会去读 type.region.width
        boolean added = false;
        try{
            mega.addBuild(new mindustry.entities.units.BuildPlan(ox + 4, oy + 4, 0, Blocks.conveyor, null));
            added = true;
        }catch(Throwable t){ Log.err("[MC] 加建造计划失败", t); }
        check("给巨兽排了一条建造计划", added && mega.isBuilding());

        float building = clipSize(mega, "有计划");
        check("有建造计划时 clipSize 也不崩（" + building + "）", building > 0f);
        System.out.println("[MC] 对照：原版公式会读 type.region.width，巨兽 region 为空就 NPE 崩溃栈 "
            + "UnitEntity.clipSize → EntityGroup.draw → Renderer.draw");

        System.out.println("[MC] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }

    /** 调 clipSize()（渲染循环里 EntityGroup.draw 调的就是它），抛异常就记 FAIL 并返回 -1。 */
    static float clipSize(Unit u, String tag){
        try{
            return u.clipSize();
        }catch(Throwable t){
            System.out.println("[MC] " + tag + " clipSize 抛异常：" + t);
            return -1f;
        }
    }
}
