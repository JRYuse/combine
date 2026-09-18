package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts;

/**
 * 验证器的"防呆"检查：**combine 模组真的加载了吗**。
 *
 * 【为什么需要】客户端跑挂了（被沙箱杀在半路、或真崩了）时，Mindustry 会在数据目录的
 * settings 里写 `mod-combine-failed`，之后所有 headless 测试里 combine 都会被跳过 ——
 * 测试看着还在 PASS，其实一条组合逻辑都没跑（我们真踩过）。
 * run-headless.sh 每次跑测试前先跑这个，不通过就自动清掉那份 settings 重来。
 */
public class SanityCheck implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new SanityCheck(), t->t.printStackTrace()); }
    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        Vars.mods.eachClass(Mod::init);
        var cm = Vars.mods.getMod("combine");
        int combo = 0;
        for(mindustry.world.Block b : Vars.content.blocks()) if(b.getClass().getName().startsWith("combine.")) combo++;
        boolean ok = cm != null && cm.main != null && combo > 0;
        System.out.println("[SANITY] combine 加载=" + (cm != null && cm.main != null)
            + " combine 方块数=" + combo + " 方块总数=" + Vars.content.blocks().size);
        System.exit(ok ? 0 : 1);
      }catch(Throwable t){ System.out.println("[SANITY] 崩了: " + t); System.exit(2); }
    }
}
