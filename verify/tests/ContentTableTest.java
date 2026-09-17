package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.core.*; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts;
/**
 * 设置里的手动「不组合」名单**不能**改动内容表（替换表 / 方块数 / id 顺序），
 * 否则联机两端 id 对不上，而且"点一下设置，重启后模组组合建筑全变回原版"。
 * 用法：-Dseed=<方块名> 模拟"上次关掉了这个方块"。
 */
public class ContentTableTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new ContentTableTest(), t->t.printStackTrace()); }
    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        String seed = System.getProperty("seed");
        if(seed != null && !seed.isEmpty()) Core.settings.put("combine.blockBlacklist", seed);
        Vars.mods.eachClass(Mod::init);
        ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        Object map = Class.forName("combine.Replacer", true, ml).getField("replaced").get(null);
        var keys = map.getClass().getMethod("keys").invoke(map);
        var it = keys.getClass().getMethod("iterator").invoke(keys);
        var hasNext = keys.getClass().getMethod("hasNext"); var next = keys.getClass().getMethod("next");
        int n=0; StringBuilder sb=new StringBuilder();
        while((Boolean)hasNext.invoke(it)){ Object k = next.invoke(it); n++; if(n<=4) sb.append(((mindustry.world.Block)k).name).append(' '); }
        StringBuilder ids = new StringBuilder();
        for(int i=0;i<Vars.content.blocks().size;i+=Math.max(Vars.content.blocks().size/8,1))
            ids.append(Vars.content.blocks().get(i).name).append(',');
        System.out.println("[CT] seed=" + seed + " 被替换=" + n + " 方块数=" + Vars.content.blocks().size);
        System.out.println("[CT] 头几个替换: " + sb);
        System.out.println("[CT] id 抽样: " + ids);
        System.exit(0);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
