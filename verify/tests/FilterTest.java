package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;
/** 设置里的过滤按钮：显示可组合 / 显示不可组合 只能列"能手动开关"的建筑。 */
public class FilterTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new FilterTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[FT] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        Vars.mods.eachClass(Mod::init);
        ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        Class<?> list = Class.forName("combine.ui.ComboBlockList", true, ml);
        Class<?> coop = Class.forName("combine.coop.CoopCombo", true, ml);
        java.lang.reflect.Method kind = list.getMethod("kind", Block.class);
        java.lang.reflect.Method list3 = list.getMethod("list", String.class, int.class, int.class);
        java.lang.reflect.Method setBlocked = coop.getMethod("setBlocked", String.class, boolean.class);

        Block smelt = null;
        for(Block b : Vars.content.blocks()) if(b.name.endsWith("冶炼厂")) smelt = b;
        setBlocked.invoke(null, smelt.name, false);
        Seq<Block> ext = (Seq<Block>) list3.invoke(null, "", 0, 0);
        Seq<Block> on  = (Seq<Block>) list3.invoke(null, "", 0, 3);
        Seq<Block> off = (Seq<Block>) list3.invoke(null, "", 0, 4);
        System.out.println("[FT] 无名单: 扩展=" + ext.size + " 可组合=" + on.size + " 不可组合=" + off.size);
        check("显示可组合 = 全部可用建筑（" + on.size + "）", on.size == ext.size && on.size > 0);
        check("未关任何建筑时 显示不可组合 为空", off.size == 0);

        setBlocked.invoke(null, smelt.name, true);
        Seq<Block> on2  = (Seq<Block>) list3.invoke(null, "", 0, 3);
        Seq<Block> off2 = (Seq<Block>) list3.invoke(null, "", 0, 4);
        System.out.println("[FT] 关掉 " + smelt.name + " 后: 可组合=" + on2.size + " 不可组合=" + off2.size
            + " 里面是=" + (off2.size > 0 ? off2.first().name : "-"));
        check("关掉的建筑出现在 显示不可组合", off2.size == 1 && off2.first() == smelt);
        check("关掉的建筑不再出现在 显示可组合", on2.size == ext.size - 1 && !on2.contains(smelt, true));
        boolean allSwitchable = true;
        for(Block b : on2) if((Integer) kind.invoke(null, b) != 0) allSwitchable = false;
        for(Block b : off2) if((Integer) kind.invoke(null, b) != 0) allSwitchable = false;
        check("两个过滤都只列能手动开关的建筑", allSwitchable);
        // 被替换接管的方块（kind=1）绝不能出现在这两个过滤里
        Block rep = null;
        for(Block b : Vars.content.blocks()) if((Integer) kind.invoke(null, b) == 1){ rep = b; break; }
        check("替换接管的方块不出现在两个过滤里（" + (rep == null ? "无" : rep.name) + "）",
            rep == null || (!on2.contains(rep, true) && !off2.contains(rep, true)));

        setBlocked.invoke(null, smelt.name, false);
        System.out.println("[FT] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
