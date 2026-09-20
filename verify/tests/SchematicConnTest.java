package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import arc.files.Fi;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.game.Schematic;

/**
 * 蓝图里的组合连接器：写盘 → **另开一次进程**读回来，看连接器还在不在。
 *   -Dsc.phase=write / read
 */
public class SchematicConnTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static final String FILE = "/tmp/cl/conn-schem.msch";
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new SchematicConnTest(), t->t.printStackTrace()); }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Block findExact(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static Block connBlock(){ for(Block b : Vars.content.blocks()) if(b.getClass().getName().equals("combine.net.ComboConnector")) return b; return null; }

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
        String phase = System.getProperty("sc.phase", "write");
        Block conn = connBlock();
        System.out.println("[SC] 阶段=" + phase + " 连接器=" + (conn==null?"null":conn.name + "/" + conn.getClass().getName())
            + " id=" + (conn==null?-1:conn.id));

        if(phase.equals("write")){
            if(conn == null){ System.out.println("[SC] 没连接器"); System.exit(3); }
            Schematic s = new Schematic(new arc.struct.Seq<>(), new arc.struct.StringMap(), 4, 2);
            s.tiles.add(new Schematic.Stile(conn, 0, 0, null, (byte)0));
            s.tiles.add(new Schematic.Stile(conn, 1, 0, null, (byte)0));
            s.tiles.add(new Schematic.Stile(conn, 2, 0, null, (byte)0));
            Fi f = Core.files.absolute(FILE);
            mindustry.game.Schematics.write(s, f);
            System.out.println("[SC] 写好蓝图 " + f.absolutePath() + " 格数=" + s.tiles.size);
        }else{
            Fi f = Core.files.absolute(FILE);
            if(!f.exists()){ System.out.println("[SC] 蓝图文件不存在: " + f.absolutePath()); System.exit(3); }
            Schematic s = mindustry.game.Schematics.read(f);
            StringBuilder sb = new StringBuilder();
            for(Schematic.Stile st : s.tiles) sb.append(st.block == null ? "null" : st.block.name).append(' ');
            System.out.println("[SC] 读回蓝图: 格数=" + s.tiles.size + " 方块: " + sb);
            int conns = 0;
            for(Schematic.Stile st : s.tiles) if(st.block != null && st.block.getClass().getName().equals("combine.net.ComboConnector")) conns++;
            System.out.println("[SC] 读回的组合连接器数量=" + conns + "（写了 3 个）");
            System.exit(conns == 3 ? 0 : 1);
        }
        System.exit(0);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
