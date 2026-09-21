package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.files.Fi; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 【关闭模组后存档还能不能读】两个阶段：
 *
 *   -Dmode=write  带 combine：造一堆组合建筑 → SaveIO.save(-Dsave=...)
 *   -Dmode=read   不带 combine（数据目录里没有 combine.jar）：SaveIO.load(同一个存档)
 *
 * 判定：read 阶段必须能读进来、方块按名字回落到原版建筑（组合建筑保留成原版建筑，
 * 模组自己新加的方块回落成空气），并且**不能抛异常**。
 */
public class NoModCompatTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.info.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new NoModCompatTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[NO] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){
            try{ bu.created(); }catch(Throwable e){ System.out.println("[NO] created() 抛了: " + e); }
            try{ bu.updateProximity(); }catch(Throwable e){ System.out.println("[NO] updateProximity 抛了: " + e); }
        }
        return bu; }
    static Block find(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    /** 全世界物品总量（按模块去重，共用池只算一次）。 */
    static int worldItems(){
        java.util.IdentityHashMap<mindustry.world.modules.ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
        int total = 0;
        for(Tile t : Vars.world.tiles){
            if(t == null || t.build == null || t.build.items == null) continue;
            if(seen.put(t.build.items, Boolean.TRUE) != null) continue;
            total += t.build.items.total();
        }
        return total;
    }

    @Override public void init(){
      try{
        String mode = System.getProperty("mode", "write");
        String savePath = System.getProperty("save", "/tmp/cl/nomod.msav");
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        Vars.mods.eachClass(Mod::init);
        if(Vars.logic==null) Vars.logic=new Logic();
        if(Vars.netServer==null) Vars.netServer=new NetServer();
        if(Vars.netClient==null) Vars.netClient=new NetClient();

        boolean combine = Vars.mods.getMod("combine") != null;
        System.out.println("[NO] 数据目录=" + dataDir + " combine=" + combine + " 模式=" + mode
            + " 方块总数=" + Vars.content.blocks().size);

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        for(int y=30;y<170;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        if(mode.equals("write")){
          Building core = place(Blocks.coreShard, 60, 60, Team.sharded);
          run(10);
          if(core != null){ core.items.add(Items.copper, 500); core.items.add(Items.metaglass, 300); }

          // 一台一台放：覆盖"写了额外字段"的那些组合类（发电机/炮台/仓库/墙/泵/钻头/投影/处理器）
          String[] names = {
            "container", "vault", "thorium-reactor", "impact-reactor", "combustion-generator",
            "duo", "wave", "fuse", "copper-wall", "mechanical-pump",
            "graphite-press", "laser-drill", "mend-projector", "force-projector",
            "logic-processor", "ground-factory", "additive-reconstructor",
            "connection", "node", "liquid-unloader"
          };
          // -Donly=<方块名>：只放这一台，用来逐个定位"谁写的字节原版读不回去"
          String only = System.getProperty("only", "");
          if(only.length() > 0) names = new String[]{only};
          int x = 70, y = 60;
          int placed = 0;
          for(String n : names){
            Block b = find(n);
            if(b == null){ System.out.println("[NO] 找不到方块 " + n); continue; }
            if(x > 230){ x = 70; y += 8; }
            Building bu = place(b, x, y, Team.sharded);
            System.out.println("[NO] 放置 " + n + " → " + (bu == null ? "null" : bu.block.name + " / " + bu.getClass().getName()));
            if(bu != null){
              placed++;
              if(bu.items != null){ bu.items.add(Items.copper, 50); bu.items.add(Items.titanium, 20); }
              if(bu.liquids != null){ bu.liquids.add(Liquids.water, 30f); }
            }
            x += 6;
          }
          run(30);
          System.out.println("[NO] 放了 " + placed + " 台");
          System.out.println("[NO] 物品总量=" + worldItems());

          SaveIO.save(Core.files.absolute(savePath));
          System.out.println("[NO] 存档写好: " + savePath + " 大小=" + Core.files.absolute(savePath).length());
          check("write 阶段：存档文件存在且非空", Core.files.absolute(savePath).length() > 0);
          System.out.println("[NO] 结果: pass=" + pass + " fail=" + fail);
          System.exit(fail == 0 ? 0 : 1);
        }else{
          Fi file = Core.files.absolute(savePath);
          boolean ok;
          String err = "";
          try{
            SaveIO.load(file);
            ok = true;
          }catch(Throwable t){
            ok = false;
            err = t.toString();
          }
          System.out.println("[NO] 不带模组读档: " + (ok ? "成功" : "失败 " + err));
          if(!ok){
            Throwable c = null;
            try{ SaveIO.load(file); }catch(Throwable t){ c = t; }
            if(c != null) c.printStackTrace(System.out);
          }
          check("read 阶段：不带模组能读档", ok);
          int buildings = 0;
          StringBuilder sb = new StringBuilder();
          if(ok){
            run(20);
            for(Tile t : Vars.world.tiles){
              if(t != null && t.build != null && t.isCenter()){
                buildings++;
                if(sb.length() < 600) sb.append(t.block().name).append(' ');
              }
            }
            System.out.println("[NO] 读进来的建筑 " + buildings + " 台: " + sb);
            System.out.println("[NO] 物品总量=" + worldItems());
          }
          if(System.getProperty("only", "").isEmpty()){
            check("read 阶段：建筑没丢光（>=10 台）", buildings >= 10);
          }else{
            check("read 阶段：这一台还在（>=1 台）", buildings >= 1);
          }
          System.out.println("[NO] 结果: pass=" + pass + " fail=" + fail);
          System.exit(fail == 0 ? 0 : 1);
        }
      }catch(Throwable t){ t.printStackTrace(); System.out.println("[NO] 崩了: " + t); System.exit(2); }
    }
}
