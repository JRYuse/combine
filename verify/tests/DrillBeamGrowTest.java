package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："组合钻头的 beam 模式会引起后台物品增长"。
 *
 * <p>beam 模式（激光钻头）在 {@code CombinedDrill.updateBeam()} 里长出物品：
 * `time += edelta() * multiplier`，够了就往共享池里 `items.add(drop, 1)`。
 * 本测试量三件事：
 * <ol>
 *   <li><b>没电</b>时不许产出（这是"后台增长"最典型的来源：块上没挂上耗电器 → efficiency 恒 1）；</li>
 *   <li>有电时的产出速率（对照单台基准，别是"双倍/按台数平方"）；</li>
 *   <li>相邻 N 台共享池：总产出应当 ≈ N × 单台（而不是 N²）。</li>
 * </ol>
 */
public class DrillBeamGrowTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new DrillBeamGrowTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[DBG] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        return Vars.world.build(ax,ay);
    }
    static Building placeRot(Block b,int x,int y,Team team,int rot){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,rot,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)rot,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null) bu.rotation = (byte)rot;
        return bu;
    }
    static void clearArea(){
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
    }
    static ClassLoader ml;
    /**
     * 找一个"beam 钻头真的能挖"的墙：`Tile.wallDrop()` = `block.solid && itemDrop != null`
     * （或 floor.wallOre）。注意**模组替换出来的 LinkWall 的 itemDrop 是 null**
     * （实测 wallDrop=null）—— 所以这里连 Replacer.replaced 的 key（原版方块）一起扫。
     */
    static Block findWallWithDrop(){
        Block inContent = null, inReplaced = null;
        for(Block b : Vars.content.blocks()){
            if(b.solid && b.itemDrop != null && b.itemDrop.hardness <= 3){ inContent = b; break; }
        }
        try{
            Class<?> rep = Class.forName("combine.Replacer", true, ml);
            Object map = rep.getField("replaced").get(null);
            if(map instanceof arc.struct.ObjectMap<?, ?> m){
                for(Object k : m.keys()){
                    if(k instanceof Block b && b.solid && b.itemDrop != null && b.itemDrop.hardness <= 3){ inReplaced = b; break; }
                }
            }
        }catch(Throwable t){ System.out.println("[DBG] 扫 Replacer 失败: " + t); }
        System.out.println("[DBG] 候选墙: 内容表=" + (inContent == null ? "无" : inContent.name + "(drop=" + inContent.itemDrop.name + ")")
            + " 原版实例=" + (inReplaced == null ? "无" : inReplaced.name + "(drop=" + inReplaced.itemDrop.name + ")"));
        return inContent != null ? inContent : inReplaced;
    }
    static Block staticWall;
    /** 在 (x0,y) 往东摆一排天然石墙（激光钻头挖的就是这种墙）。 */
    static void wallRow(int x0,int y,int count){
        Block wall = staticWall;
        for(int i = 1; i <= count; i++){
            Tile t = Vars.world.tile(x0 + i, y);
            if(t != null) t.setBlock(wall);
        }
    }
    static Block beamDrill(){
        for(Block b : Vars.content.blocks()){
            if(!b.getClass().getName().startsWith("combine.production.CombinedDrill")) continue;
            try{
                Object mode = CombineReflect.mode(b);
                if(mode != null && mode.toString().equals("beam")) return b;
            }catch(Throwable ignored){}
        }
        return null;
    }
    /** 反射读 CombinedDrill.mode。 */
    static class CombineReflect{
        static Object mode(Block b){
            try{
                java.lang.reflect.Field f = b.getClass().getField("mode");
                return f.get(b);
            }catch(Throwable t){ return null; }
        }
    }
    /** 反射读 build 上的字段（诊断场景是否真的在挖）。 */
    static Object fld(Object o, String name){
        for(Class<?> c = o.getClass(); c != null; c = c.getSuperclass()){
            try{ java.lang.reflect.Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
            catch(NoSuchFieldException ignored){} catch(Throwable ignored){ return null; }
        }
        return null;
    }
    static String digFacts(Building b){
        return "效率=" + fld(b, "efficiency") + " 朝向格=" + fld(b, "facingAmount") + " 目标=" + fld(b, "lastItem")
            + " time=" + fld(b, "time") + " 电=" + (b.power == null ? "-" : b.power.status) + " 有料=" + poolTotal(b);
    }

    /** 读 sector.info.rawProduction 里某物品的累计量（没有就返回 0）。 */
    static float sectorRaw(Item item){
        var info = Vars.state.rules.sector == null ? null : Vars.state.rules.sector.info;
        if(info == null) return 0f;
        var stat = info.rawProduction.get(item);
        return stat == null ? 0f : stat.mean;
    }

    static int poolTotal(Building b){
        return b == null || b.items == null ? 0 : b.items.total();
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
        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        clearArea();

        Block core = null, ps = null;
        for(Block b : Vars.content.blocks()){
            if(b.name.equals("core-shard")) core = b;
            if(b.name.equals("power-source")) ps = b;
        }
        ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        staticWall = findWallWithDrop();
        Block drill = beamDrill();
        System.out.println("[DBG] 目标墙=" + (staticWall == null ? "无" : staticWall.name + " drop=" + staticWall.itemDrop.name));
        System.out.println("[DBG] beam 模式钻头=" + (drill == null ? "无" : drill.name + "(" + drill.getClass().getSimpleName() + ")")
            + " 耗电=" + (drill != null && drill.hasPower) + " size=" + (drill == null ? -1 : drill.size)
            + " power-source=" + (ps == null ? "无" : ps.name));
        if(drill == null){ System.out.println("[DBG] 数据目录里没有 beam 模式组合钻头"); System.exit(3); }
        place(core, 60, 120, Team.sharded);
        run(10);

        int sz = Math.max(drill.size, 1);
        // ---------- A：单台、**不接电** ----------
        wallRow(50, 60, 12);
        Building a1 = placeRot(drill, 50 - sz, 60, Team.sharded, 0);
        run(30);
        int a0 = poolTotal(a1);
        run(900);
        int aGrow = poolTotal(a1) - a0;
        System.out.println("[DBG] A 场景: " + digFacts(a1));
        System.out.println("[DBG] A 单台不接电 900 tick: 池 " + a0 + " → " + poolTotal(a1) + "（增长 " + aGrow + "）");
        check("beam 钻头**不接电**时不许产出（后台增长的来源）", aGrow == 0);

        // ---------- B：单台 + 电源 ----------
        clearArea();
        wallRow(50, 60, 12);
        Building b1 = placeRot(drill, 50 - sz, 60, Team.sharded, 0);
        if(ps != null) place(ps, 48, 62, Team.sharded);   // 紧贴钻头（供电靠邻近）
        run(60);
        System.out.println("[DBG] B 场景: 钻头=" + digFacts(b1) + " 电源相邻=" + (ps != null));
        System.out.println("[DBG] B 场景细节: 钻头 tile=" + b1.tileX() + "," + b1.tileY() + " rot=" + b1.rotation
            + " tier=" + fld(b1.block, "tier") + " range=" + fld(b1.block, "range") + " size=" + b1.block.size);
        for(int i = 0; i <= 4; i++){
            Tile t = Vars.world.tile(b1.tileX() + b1.block.size / 2 + i, b1.tileY());
            if(t == null) continue;
            System.out.println("[DBG]   前方 " + (b1.tileX() + b1.block.size / 2 + i) + "," + b1.tileY()
                + " block=" + t.block().name + " solid=" + t.solid() + " wallDrop=" + t.wallDrop()
                + " hardness=" + fld(t.block(), "hardness") + " blockType=" + t.block().getClass().getSimpleName());
        }
        int b0 = poolTotal(b1);
        run(900);
        int bGrow = poolTotal(b1) - b0;
        System.out.println("[DBG] B 单台+电源 900 tick: 池 " + b0 + " → " + poolTotal(b1) + "（增长 " + bGrow + "）");
        check("beam 钻头接电后应当产出", bGrow > 0);

        // ---------- C：相邻 2 台 + 电源（共享池） ----------
        clearArea();
        wallRow(50, 60, 12);
        wallRow(50, 60 + sz, 12);
        Building c1 = placeRot(drill, 50 - sz, 60, Team.sharded, 0);
        Building c2 = placeRot(drill, 50 - sz, 60 + sz, Team.sharded, 0);
        if(ps != null) place(ps, 48, 62 + sz, Team.sharded);
        run(60);
        System.out.println("[DBG] C 场景: 1号=" + digFacts(c1) + " 2号=" + digFacts(c2));
        int c0 = poolTotal(c1);
        run(900);
        int cGrow = poolTotal(c1) - c0;
        System.out.println("[DBG] C 相邻 2 台+电源 900 tick: 池 " + c0 + " → " + poolTotal(c1) + "（增长 " + cGrow
            + "；单台基准 " + bGrow + "）");
        check("两台共享池的产出 ≈ 2 × 单台（不是平方增长）",
            cGrow > bGrow && cGrow <= bGrow * 5 / 2 + 4);

        // ---------- D：区块物品产出（用户说的"后台物品增长"） ----------
        // produced(item, n) 只在 state.rules.sector != null 且 team == rules.defaultTeam 时
        // 计入 sector.info.handleProduction（rawProduction）；beam 分支原来直接 items.add，
        // 所以物品进了池子但区块产量恒为 0。这里挂一个真 sector 直接验。
        clearArea();
        wallRow(50, 60, 12);
        Vars.state.rules.sector = mindustry.content.Planets.serpulo.sectors.first();
        Vars.state.rules.defaultTeam = Team.sharded;
        Building d1 = placeRot(drill, 50 - sz, 60, Team.sharded, 0);
        if(ps != null) place(ps, 48, 62, Team.sharded);
        run(60);
        float raw0 = sectorRaw(mindustry.content.Items.graphite);
        int d0 = poolTotal(d1);
        run(900);
        float raw1 = sectorRaw(mindustry.content.Items.graphite);
        int dGrow = poolTotal(d1) - d0;
        System.out.println("[DBG] D 区块产出: 池增长=" + dGrow + " rawProduction " + raw0 + " → " + raw1
            + "（sector=" + Vars.state.rules.sector.name() + "）");
        check("beam 钻头产出的物品要计入区块产量 rawProduction（后台物品产出）", raw1 > raw0);
        // rawProduction.mean 是"每秒产量"（实测 5 个/900 tick → 5/60 = 0.083），不是累计个数
        float expectRate = dGrow / 60f;
        check("区块产量速率与池子产出对得上（≈ " + String.format("%.3f", expectRate) + "/秒，实测 "
            + String.format("%.4f", raw1 - raw0) + "）",
            Math.abs((raw1 - raw0) - expectRate) <= Math.max(0.02f, expectRate * 0.5f));

        System.out.println("[DBG] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
