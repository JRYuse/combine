package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.scene.ui.layout.Table; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.world.*; import mindustry.ui.Fonts;

/**
 * 组合单位工厂 / 组合升级厂要跟原版一样有"单位数量/上限"的 bar（bar.unitcap）。
 * 这里直接调它们的 display()，数一数里面有几个 Bar，并确认 bar.unitcap / bar.progress 这两个
 * 文案键在真 bundle 里存在（不然面板上会显示成一串 key）。
 */
public class UnitBarTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new UnitBarTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[UB] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static int countBars(arc.scene.Element e){
        int n = e.getClass().getName().equals("mindustry.ui.Bar") ? 1 : 0;
        if(e instanceof arc.scene.Group g) for(arc.scene.Element c : g.getChildren()) n += countBars(c);
        return n; }
    static String firstLine(arc.scene.Element e){
        if(e.getClass().getName().equals("mindustry.ui.Bar") && e instanceof Table t){
            for(arc.scene.Element c : t.getChildren())
                if(c instanceof arc.scene.ui.Label l) return String.valueOf(l.getText());
        }
        if(e instanceof arc.scene.Group g) for(arc.scene.Element c : g.getChildren()){ String r = firstLine(c); if(r != null) return r; }
        return null; }

    /** 打印这个方块注册了哪些 bar（原版单位工厂是 progress + units 两条）。 */
    static void javapBars(Block blk, String label){
        try{
            java.lang.reflect.Field f = mindustry.world.Block.class.getDeclaredField("bars");
            f.setAccessible(true);
            Object map = f.get(blk);
            java.lang.reflect.Method keys = map.getClass().getMethod("keys");
            Object it = keys.invoke(map);
            java.lang.reflect.Method hasNext = it.getClass().getMethod("hasNext");
            java.lang.reflect.Method next = it.getClass().getMethod("next");
            StringBuilder sb = new StringBuilder();
            while((Boolean) hasNext.invoke(it)){ sb.append(next.invoke(it)).append(' '); }
            System.out.println("[UB] " + label + " 注册的 bar: " + sb);
        }catch(Throwable t){ System.out.println("[UB] " + label + " 读 bar 列表失败: " + t); }
    }

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        // 这次要真 bundle（bar.unitcap 之类的文案键在里面）
        Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        Vars.mods.eachClass(Mod::init);
        if(Vars.logic==null) Vars.logic=new Logic();
        if(Vars.netServer==null) Vars.netServer=new NetServer();
        if(Vars.netClient==null) Vars.netClient=new NetClient();

        System.out.println("[UB] bundle: bar.unitcap=" + (Core.bundle == null ? "null" : Core.bundle.format("bar.unitcap", "x", 1, 2)));
        check("bundle 里有 bar.unitcap 文案（不是显示成 key）",
            Core.bundle != null && !Core.bundle.format("bar.unitcap", "x", 1, 2).contains("bar.unitcap"));

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        for(int y=40;y<130;y++) for(int x=20;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        Block uf = null, rc = null;
        for(Block b : Vars.content.blocks()){
            if(b.getClass().getName().startsWith("combine.units.CombinedUnitFactory")) uf = b;
            if(b.getClass().getName().startsWith("combine.units.CombinedReconstructor")) rc = b;
        }
        System.out.println("[UB] 单位工厂方块=" + (uf == null ? "无" : uf.name) + "  升级厂方块=" + (rc == null ? "无" : rc.name));

        for(var pair : new Object[][]{{uf, "组合单位工厂"}, {rc, "组合升级厂"}}){
            Block blk = (Block) pair[0];
            String label = (String) pair[1];
            if(blk == null){ check(label + " 存在", false); continue; }
            Building b = place(blk, 60, 60, Team.sharded);
            run(5);
            javapBars(blk, label);
            Table table = new Table();
            try{
                b.displayBars(table);      // 原版注册的 bar 由这里画（display 里我们也是这么调的）
            }catch(Throwable t){
                System.out.println("[UB] " + label + " displayBars 抛了: " + t);
                check(label + " 的 displayBars 能画出来", false);
                continue;
            }
            int bars = countBars(table);
            System.out.println("[UB] " + label + " displayBars: Bar 数量=" + bars);
            check(label + " 会画原版的 bar（进度 + 单位数量/上限）", bars >= 2);
        }

        System.out.println("[UB] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
