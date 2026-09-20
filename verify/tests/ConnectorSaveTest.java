package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 组合连接器：连着两个组合体，存读档之后还连不连、池子/电有没有断。
 */
public class ConnectorSaveTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new ConnectorSaveTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[CS] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Block find(String name){ for(Block b : Vars.content.blocks()) if(b.name.endsWith(name)) return b; return null; }
    static Block findExact(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static Block connBlock(){ for(Block b : Vars.content.blocks()) if(b.getClass().getName().equals("combine.net.ComboConnector")) return b; return null; }
    static boolean sameGrid(Building a, Building b){
        return a != null && b != null && a.power != null && b.power != null
            && a.power.graph != null && a.power.graph == b.power.graph; }

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
        for(int y=30;y<170;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        Block conn = connBlock();
        Block prod = find("coop-producer");
        Block press = find("graphite-press");
        System.out.println("[CS] conn=" + (conn==null?null:conn.name) + " prod=" + (prod==null?null:prod.name) + " press=" + (press==null?null:press.name));
        if(conn == null || prod == null || press == null) System.exit(3);
        int sz = Math.max(prod.size, 1);

        // A 在 60，连接器 60+sz .. 60+sz+3，B 在 60+sz+4
        Building a = place(prod, 60, 60, Team.sharded);
        for(int i = 0; i < 4; i++) place(conn, 60 + sz + i, 60, Team.sharded);
        Building b = place(prod, 60 + sz + 4, 60, Team.sharded);
        run(30);
        a.items.add(Items.copper, 120);
        run(10);
        System.out.println("[CS] 读档前: 同池=" + (a.items == b.items) + " 铜=" + a.items.get(Items.copper) + "/" + b.items.get(Items.copper)
            + " 同电网=" + sameGrid(a, b));
        check("连接器接上后同一个池", a.items == b.items);

        SaveIO.save(Core.files.absolute("/tmp/cl/connsave.msav"));
        SaveIO.load(Core.files.absolute("/tmp/cl/connsave.msav"));
        run(60);
        Building a2 = Vars.world.build(60, 60);
        Building b2 = Vars.world.build(60 + sz + 4, 60);
        System.out.println("[CS] 读档后: a=" + a2 + " b=" + b2
            + " 同池=" + (a2 != null && b2 != null && a2.items == b2.items)
            + " 铜=" + (a2==null?-1:a2.items.get(Items.copper)) + "/" + (b2==null?-1:b2.items.get(Items.copper))
            + " 同电网=" + sameGrid(a2, b2));
        check("读档后两个组合体还在", a2 != null && b2 != null);
        check("读档后连接器仍然让两边同池", a2 != null && b2 != null && a2.items == b2.items);
        check("读档后物品数量没变（120）", a2 != null && a2.items.get(Items.copper) == 120);

        // ---------- 混合：扩展建筑 <-> 组合仓库（用户常见：连接器把仓库和工厂接起来） ----------
        for(int y=40;y<140;y++) for(int x=20;x<240;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
        Block cont = findExact("container");
        if(cont == null){ System.out.println("[CS] 没容器"); System.exit(3); }
        int csz = Math.max(cont.size, 1);
        System.out.println("[CS] 混合用容器=" + cont.name + " size=" + cont.size + " hasItems=" + cont.hasItems);
        Building s1 = place(cont, 60, 60, Team.sharded);      // 组合仓库（2x2）
        for(int i = 0; i < 3; i++) place(conn, 60 + csz + i, 60, Team.sharded);
        Building p1 = place(prod, 60 + csz + 3, 60, Team.sharded);
        run(40);
        s1.items.add(Items.copper, 60);
        run(20);
        System.out.println("[CS] 混合（仓库<->扩展建筑）: 同池=" + (s1.items == p1.items)
            + " 仓库铜=" + s1.items.get(Items.copper) + " 工厂铜=" + p1.items.get(Items.copper));
        check("连接器把组合仓库和扩展建筑接成同一个池", s1.items == p1.items);
        check("池里的物品两边都看得到", p1.items.get(Items.copper) >= 60);

        System.out.println("[CS] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
