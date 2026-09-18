package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts;
import mindustry.world.*;

/**
 * 克隆方块不能污染原版方块的配置表。
 *
 * 原版方块（战役基地蓝图 / 地图里的原版炮塔…）如果拿到本模组注册的配置回调，
 * 回调里强转成组合建筑的 build 类型 → ClassCastException（发射/生成基地时崩）。
 * 这里直接检查：被替换掉的原版方块手里，没有任何 combine.* 的回调。
 */
public class ConfigPollutionTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new ConfigPollutionTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[CP] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }

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

        Class<?> repCls = Class.forName("combine.Replacer", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
        Object repMap = repCls.getField("replaced").get(null);
        Object ents = repMap.getClass().getMethod("entries").invoke(repMap);
        var it = ents.getClass().getMethod("iterator").invoke(ents);
        var hasNext = ents.getClass().getMethod("hasNext");
        var next = ents.getClass().getMethod("next");
        Seq<Object> entries = new Seq<>();
        while((Boolean) hasNext.invoke(it)) entries.add(next.invoke(it));
        int checked = 0, polluted = 0;
        StringBuilder bad = new StringBuilder();
        for(Object entryObj : entries){
            var entry = (arc.struct.ObjectMap.Entry<?, ?>) entryObj;
            Block orig = (Block) entry.key, combo = (Block) entry.value;
            if(orig == null || combo == null || orig.configurations == null) continue;
            // 两个方块的 configurations 不该是同一张表
            if(orig.configurations == combo.configurations){
                polluted++; bad.append(orig.name).append("(同表) ");
                continue;
            }
            checked++;
            for(var c : orig.configurations){
                Object handler = c.value;
                String hn = handler == null ? "null" : handler.getClass().getName();
                if(hn.startsWith("combine.")){
                    polluted++;
                    bad.append(orig.name).append('[').append(hn).append("] ");
                    break;
                }
            }
        }
        System.out.println("[CP] 检查了 " + checked + " 个被替换方块的配置表，污染 " + polluted + " 个" + (bad.length() > 0 ? "：" + bad : ""));
        check("被替换掉的原版方块没有拿到本模组的配置回调", polluted == 0);

        System.out.println("[CP] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
