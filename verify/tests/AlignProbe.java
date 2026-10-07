package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*; import static mindustry.Vars.*;

/** 临时探针：2x2 方块到底覆盖哪几格 / build.x,y 落在哪。 */
public class AlignProbe implements arc.ApplicationListener {
  static String dataDir="/tmp/mp_coop/data"; static ClassLoader ml;
  public static void main(String[] a){ if(a.length>0) dataDir=a[0];
    Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
    Log.logger=(l,t)->{ if(l.ordinal()>=Log.LogLevel.warn.ordinal()) System.out.println("[L "+l+"] "+t); };
    new HeadlessApplication(new AlignProbe(), t->t.printStackTrace()); }
  static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
  @Override public void init(){
    try{
      Core.settings.setDataDirectory(Core.files.local(dataDir));
      Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
      UI.loadColors(); Fonts.loadContentIconsHeadless();
      Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
      Vars.mods.eachClass(Mod::init);
      if (Vars.logic==null) Vars.logic=new Logic();
      if (Vars.netServer==null) Vars.netServer=new NetServer();
      if (Vars.netClient==null) Vars.netClient=new NetClient();
      ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.state.rules.waves=false; Vars.logic.play(); run(10);
      for(int y=40;y<160;y++) for(int x=20;x<220;x++){ Tile t=Vars.world.tile(x,y); if(t!=null&&t.block()!=Blocks.air) t.setBlock(Blocks.air); }
      run(5);
      Block mixer = Vars.content.block("cryofluid-mixer");
      Tile t0 = Vars.world.tile(60,60);
      t0.setBlock(mixer, Team.sharded, 0);
      run(5);
      Building b = t0.build;
      System.out.println("[AL] mixer size=" + mixer.size + " offset=" + mixer.offset
          + " 锚点=(" + b.tileX() + "," + b.tileY() + ") build.x=" + b.x + " build.y=" + b.y);
      for(int dy=3; dy>=-3; dy--){
        StringBuilder sb=new StringBuilder();
        for(int dx=-3; dx<=3; dx++){
          Tile t=Vars.world.tile(b.tileX()+dx, b.tileY()+dy);
          sb.append(t!=null && t.build==b ? "#" : ".");
        }
        System.out.println("[AL]  y=" + (b.tileY()+dy) + " " + sb + "   (x 从 " + (b.tileX()-3) + " 到 " + (b.tileX()+3) + ")");
      }
      Core.app.exit();
    }catch(Throwable t){ System.out.println("[AL] 崩了: "+t); t.printStackTrace(System.out); System.exit(2);} }
}
