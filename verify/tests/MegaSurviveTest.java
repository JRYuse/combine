package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.UnitType; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 真联机复现出来的 bug：**服务端自己**融合出来的巨兽，过了 1 秒就不在 {@code Groups.unit} 里了
 * （客户端当然也就看不到 —— 不是同步问题，是巨兽在服务端就没了）。
 *
 * 真联机里两个剧本唯一的差别是成员构成（2 个机甲 vs 机甲+海军），但两者的出生点都一样：
 * {@code Vars.world.unitWidth() * 8f * 0.25f} —— 那个坐标是**地图外**（单位是像素，
 * {@code unitWidth()} 已经是像素了，再乘 8 → 2 倍地图宽）。
 *
 * 这个测试就验两件事：
 *   A. 在地图内的陆地上融合 → 巨兽必须活下去（跑 120 帧还在 Groups.unit 里）；
 *   B. 在地图外融合（老剧本的坐标）→ 看它是不是被原版的"环境死亡"清掉（那就是剧本坐标写错了）。
 */
public class MegaSurviveTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    static Seq<String> events = new Seq<>();

    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MSV] "+t); };
        new HeadlessApplication(new MegaSurviveTest(), t->t.printStackTrace()); }

    static void check(String n, boolean ok){ System.out.println("[MSV] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static Class<?> combineClass(String name){
        try{ return Class.forName(name, true, ml); }catch(Throwable t){ return null; }
    }
    static int memberCount(Unit u){
        try{ return (Integer)u.getClass().getMethod("memberCount").invoke(u); }catch(Throwable t){ return -1; }
    }
    static boolean isMega(Unit u){ return u != null && u.getClass().getName().equals("combine.units.mega.MegaUnitEntity"); }

    /** 造 n 只单位 → 融合，返回巨兽。 */
    static Unit mergeAt(Class<?> mergeCls, float x, float y, UnitType... types){ return mergeAt(mergeCls, x, y, 2, types); }

    static Unit mergeAt(Class<?> mergeCls, float x, float y, int tickWait, UnitType... types){
        try{
            Seq<Unit> units = new Seq<>();
            for(int i = 0; i < types.length; i++){
                Unit u = types[i].create(Team.sharded);
                u.set(x + i * 24f, y);
                u.add();
                units.add(u);
            }
            run(tickWait);
            for(Unit u : units) System.out.println("[MSV]   成员 " + u.type.name + " id=" + u.id
                + " 在组里=" + Groups.unit.contains(x2 -> x2 == u) + " 已加=" + u.isAdded() + " 有效=" + u.isValid()
                + " 死亡=" + u.dead + " 血量=" + (int)u.health);
            return (Unit)mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, units);
        }catch(Throwable t){ System.out.println("[MSV] 融合失败: " + t); return null; }
    }

    static void scene(String tag, Class<?> mergeCls, float x, float y, UnitType... types){
        scene(tag, mergeCls, x, y, 2, true, types);
    }

    /**
     * @param expectAlive true = 这一组应当活下来（地图内）；false = 这一组是"生到地图外"的**复现**，
     *                    原版会把它当环境死亡清掉，断言反过来写（复现也算通过，不留假红）。
     */
    static void scene(String tag, Class<?> mergeCls, float x, float y, boolean expectAlive, UnitType... types){
        scene(tag, mergeCls, x, y, 2, expectAlive, types);
    }

    static void scene(String tag, Class<?> mergeCls, float x, float y, int tickWait, boolean expectAlive, UnitType... types){
        events.clear();
        Unit mega = mergeAt(mergeCls, x, y, tickWait, types);
        if(mega == null){
            check(tag + " 融合结果符合预期（" + (expectAlive ? "应当融出巨兽" : "地图外融不出来 / 活不下来，正是原版行为") + "）", !expectAlive);
            return;
        }
        int id = mega.id;
        run(1);
        boolean rightAfter = Groups.unit.contains(u -> u.id == id);
        run(119);
        boolean after2s = Groups.unit.contains(u -> u.id == id);
        System.out.println("[MSV] " + tag + " 巨兽 id=" + id + " 位置=" + (int)x + "," + (int)y
            + " 融合后立刻在组里=" + rightAfter + " 2 秒后还在=" + after2s
            + " 血量=" + (int)mega.health + "/" + (int)mega.maxHealth
            + " 融合后位置=" + (int)mega.x + "," + (int)mega.y
            + " 死亡=" + mega.dead + " 有效=" + mega.isValid() + " 事件=" + events);
        if(expectAlive){
            check(tag + "：融合出来的巨兽 2 秒后仍然存在（服务端自己看得见）", after2s);
        }else{
            check(tag + "：地图外的巨兽被原版清掉（复现老剧本的坑，改法=出生点必须落在地图内）", !after2s);
        }
    }

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
        ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        Class<?> mergeCls = combineClass("combine.units.UnitComboMerge");
        if(mergeCls == null){ System.out.println("[MSV] 找不到 UnitComboMerge"); System.exit(3); }

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.state.rules.waves = false;
        Vars.state.rules.canGameOver = false;
        Vars.logic.play();
        run(20);
        // 和真联机剧本一样：清一块地、放核心（核心 = 队伍有家，接近真实对局）
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
        int cx = 60, cy = 60;
        mindustry.world.Build.beginPlace(null, Blocks.coreShard, Team.sharded, cx, cy, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(cx, cy), Blocks.coreShard, null, (byte)0, Team.sharded, null);
        run(10);

        Events.on(EventType.UnitDestroyEvent.class, e -> {
            if(isMega(e.unit)) events.add("销毁(血量=" + (int)e.unit.health + ")");
        });
        Events.on(EventType.UnitDrownEvent.class, e -> {
            if(isMega(e.unit)) events.add("淹死");
        });

        System.out.println("[MSV] 地图 单位宽高=" + Vars.world.unitWidth() + "x" + Vars.world.unitHeight()
            + " 格数=" + Vars.world.width() + "x" + Vars.world.height()
            + "  剧本旧坐标=" + (Vars.world.unitWidth() * 8f * 0.25f) + "," + (Vars.world.unitHeight() * 8f * 0.25f)
            + "  地图内坐标=" + (cx * 8f) + "," + (cy * 8f));

        // A. 地图内（核心旁边）融合：应该活着
        scene("A 地图内(机甲2)", mergeCls, cx * 8f + 200f, cy * 8f + 200f, UnitTypes.dagger, UnitTypes.fortress);
        // B. 地图外（真联机剧本当时用的坐标）融合：预期**活不下来** —— 这就是当时那个坑的复现
        scene("B 地图外(机甲2)", mergeCls, Vars.world.unitWidth() * 8f * 0.25f, Vars.world.unitHeight() * 8f * 0.25f,
            false, UnitTypes.dagger, UnitTypes.fortress);
        // B2. 地图外 + 和真联机剧本一样"同一帧造完就融合"（中途不等帧）
        scene("B2 地图外同帧融合(机甲2)", mergeCls, Vars.world.unitWidth() * 8f * 0.25f, Vars.world.unitHeight() * 8f * 0.25f,
            0, false, UnitTypes.dagger, UnitTypes.fortress);
        // C. 地图内 + 机甲/海军混合（真联机里"活下来"的那一组）
        scene("C 地图内(机甲+海军3)", mergeCls, cx * 8f + 200f, cy * 8f + 200f,
            UnitTypes.dagger, UnitTypes.fortress, UnitTypes.oct);
        // D. 地图内换成爬虫/蜘蛛（履带类）
        scene("D 地图内(爬虫2)", mergeCls, cx * 8f + 200f, cy * 8f + 200f, UnitTypes.crawler, UnitTypes.atrax);

        System.out.println("[MSV] 结果: 通过 " + pass + " / 失败 " + fail);
        System.exit(fail == 0 ? 0 : 1);
      }catch(Throwable t){
        t.printStackTrace();
        System.exit(2);
      }
    }
}
