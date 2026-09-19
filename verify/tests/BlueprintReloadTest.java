package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import arc.files.Fi;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 蓝图里保存了组合连接器，退出游戏重进后连接器消失（用户报的）。
 *
 * 根因：客户端的加载顺序是
 *   createModContent() → **assets.load(schematics)**（读磁盘上的 .msch） → Mod.init()
 * 而组合连接器/节点是在 {@code Main.init()} 里才 new 出来的 —— 读蓝图那一刻这俩方块还不存在，
 * 蓝图里 "connection"/"node" 按名字查不到 → 原版当成未知方块丢成 air（Schematics.read）。
 *
 * 这个测试：write 阶段先写一张含 3 个连接器的蓝图；read 阶段照客户端的顺序读一遍
 * （应当丢掉连接器 = 复现），再触发一次 ClientLoadEvent（模组此时会重读蓝图库）看是否恢复。
 */
public class BlueprintReloadTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static final String NAME = "comboreload-test";
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new BlueprintReloadTest(), t->t.printStackTrace()); }
    static Block connBlock(){ for(Block b : Vars.content.blocks()) if(b.getClass().getName().equals("combine.net.ComboConnector")) return b; return null; }
    static Fi file(){ return Core.files.local(dataDir).child("schematics").child(NAME + ".msch"); }

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        String phase = System.getProperty("br.phase", "read");

        if(phase.equals("write")){
            UI.loadColors(); Fonts.loadContentIconsHeadless();
            Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
            Vars.mods.eachClass(Mod::init);
            Block conn = connBlock();
            if(conn == null){ System.out.println("[BR] 没连接器"); System.exit(3); }
            var s = new mindustry.game.Schematic(new arc.struct.Seq<>(), new arc.struct.StringMap(), 3, 1);
            for(int i = 0; i < 3; i++) s.tiles.add(new mindustry.game.Schematic.Stile(conn, i, 0, null, (byte)0));
            s.tags.put("name", NAME);
            Fi f = file();
            f.parent().mkdirs();
            mindustry.game.Schematics.write(s, f);
            System.out.println("[BR] 写好蓝图 " + f.absolutePath() + " 格数=" + s.tiles.size
                + "（连接器名=" + conn.name + "）");
            System.exit(0);
        }

        // ---- read：照客户端的顺序 ----
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent();
        Vars.mods.loadScripts();
        Vars.content.createModContent();          // 此时连接器还没被 new 出来
        if(Vars.schematics == null) Vars.schematics = new mindustry.game.Schematics();
        System.out.println("[BR] 读蓝图前 连接器方块=" + connBlock());
        Vars.schematics.load();                   // ← 客户端就是这里读磁盘上的蓝图
        int before = countConn();
        System.out.println("[BR] 客户端顺序读到的连接器数=" + before + "（写了 3 个）—— 复现 bug 应当是 0");

        Vars.content.init();
        Vars.mods.eachClass(Mod::init);           // 现在连接器/节点才建出来
        System.out.println("[BR] Mod.init() 后 连接器方块=" + connBlock());
        // 走模组的修复代码：重读蓝图库（Main 的 ClientLoadEvent 处理里也是调它）
        ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        Class.forName("combine.Replacer", true, ml).getMethod("reloadSchematics").invoke(null);
        int after = countConn();
        System.out.println("[BR] ClientLoadEvent 之后连接器数=" + after);

        boolean pass = after == 3;
        System.out.println("[BR] " + (pass ? "PASS" : "FAIL") + " 重进游戏后蓝图里的组合连接器不丢");
        System.out.println("[BR] RESULT " + (pass ? "ALL PASS (pass=1)" : "1 FAILED (pass=0)"));
        System.exit(pass ? 0 : 1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
    static int countConn(){
        if(Vars.schematics == null) return -1;
        for(mindustry.game.Schematic s : Vars.schematics.all()){
            if(!NAME.equals(s.tags.get("name"))) continue;
            int n = 0;
            for(mindustry.game.Schematic.Stile st : s.tiles)
                if(st.block != null && st.block.getClass().getName().equals("combine.net.ComboConnector")) n++;
            return n;
        }
        return -1;
    }
}
