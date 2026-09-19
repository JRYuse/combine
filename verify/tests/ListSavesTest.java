package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.files.Fi; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.io.SaveMeta; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts;

/**
 * 列出某个 saves 目录里每个存档的地图名（找复现用的存档）。
 *   -Dsaves.dir=/root/sd/a/save/saves
 */
public class ListSavesTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new ListSavesTest(), t->t.printStackTrace()); }

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        Vars.mods.eachClass(Mod::init);
        String dir = System.getProperty("saves.dir", System.getProperty("user.home") + "/sd/a/save/saves");
        Fi d = Core.files.absolute(dir);
        System.out.println("[LS] 目录=" + d.absolutePath() + " 存在=" + d.exists());
        int n = 0;
        for(Fi f : d.list()){
            if(!f.extension().equals("msav")) continue;
            try{
                SaveMeta meta = SaveIO.getMeta(f);
                String mapname = meta.tags.get("mapname", "");
                String preset = meta.tags.get("sectorPreset", "");
                String mods = meta.tags.get("mods", "");
                System.out.println("[LS] " + f.name() + " | map=" + meta.map + " | mapname=" + mapname
                    + " | 预设=" + preset + " | 波次=" + meta.wave + " | 时长=" + meta.timePlayed + " | mods=" + mods);
                n++;
            }catch(Throwable t){
                System.out.println("[LS] " + f.name() + " 读取失败: " + t);
            }
        }
        System.out.println("[LS] 共 " + n + " 个存档");
        System.exit(0);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
