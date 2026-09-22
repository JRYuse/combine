package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.Item; import mindustry.type.UnitType; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 真联机验证的**服务端**：起一个 headless 专用服务器（`Vars.net.host(port)`），
 * 等客户端进来，然后按剧本造单位 → 融合成组合巨兽 → 解体 → 再融合（成员更多）。
 *
 * <p>每帧（以及每次剧本阶段）打印一行世界快照，格式和客户端 {@code drv} 的 mp 模式一致：
 * <pre>
 *   [MP-STATE] mega=1 members=2 daggers=0 fortresses=1 octs=0 units=2
 * </pre>
 * {@code verify/run-mp.sh} 会把服务端出现过的**全部状态**和客户端观测到的状态做包含比对：
 * 客户端少看到任何一种状态（= 有单位没同步过去/留下幽灵/看不见）就算失败。
 *
 * <p>用法：{@code java -cp <游戏jar>:<服务端jar>:<测试class> combine.dbg.MpHost <数据目录> <端口> <跑多少秒>}
 */
public class MpHost implements ApplicationListener{
    static String dataDir = "/tmp/mp_mp/server";
    static int port = 6567;
    static int runSeconds = 60;
    static int frames = 0, phase = -1;
    static boolean started = false;
    static Seq<String> seenStates = new Seq<>();

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        if(a.length > 1) port = Integer.parseInt(a[1]);
        if(a.length > 2) runSeconds = Integer.parseInt(a[2]);
        Vars.platform = new Platform(){};
        Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= Log.LogLevel.info.ordinal()) System.out.println("[L " + l + "] " + t); };
        new HeadlessApplication(new MpHost(), t -> t.printStackTrace());
    }

    static void dumpMods(String tag){
        StringBuilder sb = new StringBuilder();
        for(var m : Vars.mods.list()){
            sb.append(m.name).append(":").append(m.meta.version)
              .append("[hidden=").append(m.meta.hidden)
              .append(",enabled=").append(m.enabled())
              .append(",state=").append(m.state).append("] ");
        }
        System.out.println("[" + tag + "] 模组明细: " + sb + " | getModStrings=" + Vars.mods.getModStrings());
    }

    static void run(int f){ for(int i=0;i<f;i++){ Time.delta = 1f; Vars.logic.update(); } }

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
            Vars.state.rules.waves = false;
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.fog = false;
            Vars.logic.play();
            run(20);

            // 清一块地、放核心（客户端进来后要能出生/有单位）
            for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            run(5);
            int cx = 60, cy = 60;
            mindustry.world.Build.beginPlace(null, Blocks.coreShard, Team.sharded, cx, cy, 0, null);
            mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(cx, cy), Blocks.coreShard, null, (byte)0, Team.sharded, null);
            if(Vars.state.teams.get(Team.sharded).core() != null && Vars.state.teams.get(Team.sharded).core().items != null)
                for(Item it : Vars.content.items()) Vars.state.teams.get(Team.sharded).core().items.set(it, 5000);
            run(10);

            dumpMods("MP-HOST");
            Vars.net.host(port);
            started = true;
            System.out.println("[MP-HOST] 服务器已就绪 port=" + port + "（等待客户端连接…）");
        }catch(Throwable t){
            t.printStackTrace();
            System.exit(2);
        }
    }


    static float stateTimer = 0f;

    static void scenario(){
        stateTimer += 1f / 60f;
        if(stateTimer >= 1f){
            stateTimer = 0f;
            String s = state();
            if(!seenStates.contains(s)) seenStates.add(s);
            System.out.println("[MP-STATE] " + s);
        }

        if(Groups.player.size() == 0) return;

        if(phase < 0){
            phase = 0;
            System.out.println("[MP-HOST] 客户端已连接，3 秒后开始剧本");
        }

        // 剧本按"玩家进来之后的秒数"推进
        if(frames % 60 != 0) return;
        int t = frames / 60;
        if(phase == 0 && t >= 3){
            phase = 1;
            spawnAndMerge(2, true);
        }else if(phase == 1 && t >= 12){
            phase = 2;
            splitMega();
        }else if(phase == 2 && t >= 20){
            phase = 3;
            spawnAndMerge(3, false);
        }else if(phase == 3 && t >= 29){
            phase = 4;
            splitMega();
        }else if(phase == 4 && t >= 34){
            phase = 5;
            System.out.println("[MP-HOST] 剧本结束");
        }
    }

    /** 造 n 只单位（mechOnly=true 时只用机甲）并融合成巨兽。 */
    static void spawnAndMerge(int n, boolean mechOnly){
        try{
            Seq<Unit> units = new Seq<>();
            float x = 60 * 8f + 200f, y = 60 * 8f + 200f;
            for(int i = 0; i < n; i++){
                UnitType type = mechOnly ? (i % 2 == 0 ? UnitTypes.dagger : UnitTypes.fortress)
                    : (i % 3 == 0 ? UnitTypes.dagger : (i % 3 == 1 ? UnitTypes.fortress : UnitTypes.oct));
                Unit u = type.create(Team.sharded);
                u.set(x + i * 24f, y);
                u.add();
                units.add(u);
            }
            run(2);
            Class<?> mergeCls = Class.forName("combine.units.UnitComboMerge", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
            java.lang.reflect.Method mergeSelected = mergeCls.getMethod("mergeSelected", Seq.class);
            Unit mega = (Unit)mergeSelected.invoke(null, units);
            run(5);
            System.out.println("[MP-HOST] 剧本：造 " + n + " 只 → 融合 = " + (mega == null ? "失败" : "成功")
                + " " + (mega == null ? "" : ("members=" + memberCount(mega) + " id=" + mega.id())));
        }catch(Throwable t){
            System.out.println("[MP-HOST] spawnAndMerge 失败: " + t);
        }
    }

    static void splitMega(){
        try{
            Unit mega = mega();
            if(mega == null){ System.out.println("[MP-HOST] 剧本：没有巨兽可解体"); return; }
            Class<?> mergeCls = Class.forName("combine.units.UnitComboMerge", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
            java.lang.reflect.Method split = mergeCls.getMethod("split", Unit.class);
            boolean ok = (Boolean)split.invoke(null, mega);
            run(5);
            System.out.println("[MP-HOST] 剧本：解体 = " + ok);
        }catch(Throwable t){
            System.out.println("[MP-HOST] splitMega 失败: " + t);
        }
    }

    static Unit mega(){
        for(Unit u : Groups.unit)
            if(u.team() == Team.sharded && u.getClass().getName().equals("combine.units.mega.MegaUnitEntity")) return u;
        return null;
    }

    static int memberCount(Unit u){
        try{ return (Integer)u.getClass().getMethod("memberCount").invoke(u); }catch(Throwable t){ return -1; }
    }

    /** 世界快照（和客户端 drv mp 模式同一格式，见类注释）。 */
    static String state(){
        int mega = 0, members = 0, daggers = 0, fortresses = 0, octs = 0, units = 0;
        for(Unit u : Groups.unit){
            if(u.team() != Team.sharded) continue;
            units++;
            if(u.getClass().getName().equals("combine.units.mega.MegaUnitEntity")){ mega++; members += memberCount(u); }
            if(u.type == UnitTypes.dagger) daggers++;
            if(u.type == UnitTypes.fortress) fortresses++;
            if(u.type == UnitTypes.oct) octs++;
        }
        return "mega=" + mega + " members=" + members + " daggers=" + daggers
            + " fortresses=" + fortresses + " octs=" + octs + " units=" + units;
    }
}
