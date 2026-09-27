package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 组合节点/组合连接器的「共享哪些部分」配置（物品 / 液体 / 电力 / 热量）。
 *
 *  1) 默认全共享 = 老行为：节点/连接器接起来的组合体共用一份池子、同一张电网
 *  2) 取消勾选"物品"：两边物品池分开（总量不变），其它部分照旧共享
 *  3) 取消勾选"液体"：两边液体池分开，物品照旧共享
 *  4) 取消勾选"电力"：两边各自一张电网；勾回来又并成一张
 *  5) 连接在一起的节点配置一致（点任意一个 = 改整张网络）
 *  6) 存读档后配置还在
 *  7) 液体输出只能从"生产它的"工厂出口（两种液体不在同一个出口混着出来）
 */
public class ShareConfigTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_cj/data";
    static int pass = 0, fail = 0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[SC] "+t); };
        new HeadlessApplication(new ShareConfigTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[SC] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Block find(String suffix){ for(Block b : Vars.content.blocks()) if(b.name.endsWith(suffix)) return b; return null; }
    static Block findExact(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static Block byClass(String cls){ for(Block b : Vars.content.blocks()) if(b.getClass().getName().equals(cls)) return b; return null; }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static boolean sameGrid(Building a, Building b){
        return a != null && b != null && a.power != null && b.power != null
            && a.power.graph != null && a.power.graph == b.power.graph; }
    static Object field(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return null; }
    static int mask(Building b){
        Object v = field(b, "shareMask");
        return v instanceof Number n ? n.intValue() : -1; }
    static void setMask(Building linker, int mask){
        linker.configure("comboshare:" + mask);
        run(4); }
    /** 调组合工厂的 dumpOutputs()（测试类编译时看不到模组类，用反射）。 */
    static void dumpOutputs(Building b){
        try{
            java.lang.reflect.Method mm = b.getClass().getMethod("dumpOutputs");
            mm.setAccessible(true);
            mm.invoke(b);
        }catch(Throwable t){ Log.err("[SC] dumpOutputs 调用失败", t); } }
    /** 组合体的本地组成员数（反射调 group()）。 */
    static int groupSize(Building b){
        try{
            java.lang.reflect.Method mm = b.getClass().getMethod("group");
            Object r = mm.invoke(b);
            if(r instanceof arc.struct.Seq<?> s) return s.size;
        }catch(Throwable ignored){}
        return -1; }
    /** 这几台建筑手上的物品总量（同一份模块只算一次）。 */
    static int distinctItemTotal(Building... bs){
        arc.struct.ObjectSet<mindustry.world.modules.ItemModule> seen = new arc.struct.ObjectSet<>();
        int total = 0;
        for(Building b : bs){
            if(b == null || b.items == null) continue;
            if(seen.add(b.items)) total += b.items.total();
        }
        return total; }
    static Building leaderOf(Building b){
        try{
            java.lang.reflect.Method mm = b.getClass().getMethod("leader");
            Object r = mm.invoke(b);
            if(r instanceof Building bl) return bl;
        }catch(Throwable ignored){}
        return null; }
    static String idOf(Object o){
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o)); }
    /** 读 build 上的 heat 字段（产热/耗热都写在这里）。 */
    static float heatOf(Building b){
        Object v = field(b, "heat");
        return v instanceof Number n ? n.floatValue() : 0f; }
    static Class<?> modClass(String name){
        try{ return Class.forName(name, true, Vars.mods.getMod("combine").main.getClass().getClassLoader()); }
        catch(Throwable t){ return null; } }
    static boolean staticBool(String cls, String method){
        try{
            Class<?> c = modClass(cls);
            return c != null && Boolean.TRUE.equals(c.getMethod(method).invoke(null));
        }catch(Throwable t){ return false; } }
    static void clearArea(){
        for(int y=40;y<140;y++) for(int x=20;x<240;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5); }

    static final int ITEMS = 1, LIQUIDS = 2, POWER = 4, HEAT = 8, ALL = 15;
    /** 这套数据集的组合工厂有没有电力模块（没有就跳过电力共享的断言）。 */
    static boolean powerTestable = true;
    static void checkPower(String n, boolean ok){
        if(!powerTestable){ System.out.println("[SC] SKIP " + n + "（这台组合工厂没有电力模块）"); return; }
        check(n, ok);
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
        // 【必须】清场会把核心也清掉 → 没有核心会触发 gameOver → 状态掉回 menu，
        // 之后 Logic 就不再更新建筑了（updateTile 不跑 = 本地组合体永远不成组）。
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.waves = false;
        Vars.logic.play();
        Vars.state.set(mindustry.core.GameState.State.playing);
        run(20);
        clearArea();

        Block prod = find("coop-producer");
        Block liquidMaker = find("coop-liquid-maker");
        // 换一套数据集（例如废土科技）时没有 java 测试模组：退回"被替换过的原版组合工厂"
        if(prod == null || !prod.hasItems || !prod.hasPower){
            for(String n : new String[]{"silicon-smelter", "kiln", "pulverizer", "melter", "graphite-press"}){
                Block c = findExact(n);
                if(c != null && c.hasItems && c.hasPower && c.hasLiquids){ prod = c; break; }
            }
        }
        if(liquidMaker == null || !liquidMaker.hasLiquids) liquidMaker = findExact("melter");
        Block nodeBlock = byClass("combine.net.ComboNode");
        Block connBlock = byClass("combine.net.ComboConnector");
        System.out.println("[SC] prod=" + prod + " liquidMaker=" + liquidMaker + " node=" + nodeBlock + " conn=" + connBlock);
        if(prod == null || nodeBlock == null || connBlock == null){ System.out.println("[SC] 缺方块"); System.exit(3); }
        int sz = Math.max(prod.size, 1);
        powerTestable = prod.hasPower;

        // ================= 1) 组合节点：默认全共享 =================
        Building a1 = place(prod, 60, 60, Team.sharded);
        Building a2 = place(prod, 60 + sz, 60, Team.sharded);
        Building b1 = place(prod, 90, 60, Team.sharded);
        Building b2 = place(prod, 90 + sz, 60, Team.sharded);
        Building node = place(nodeBlock, 75, 60, Team.sharded);
        run(20);
        if(node == null){ System.out.println("[SC] 节点没放上"); System.exit(3); }
        node.onConfigureBuildTapped(a1);
        run(4);
        node.onConfigureBuildTapped(b1);
        run(20);
        a1.items.add(Items.copper, 40);
        run(10);
        System.out.println("[SC] 默认: 节点mask=" + mask(node) + " 同池=" + (a1.items == b1.items)
            + " 同电网=" + sameGrid(a1, b1) + " 铜=" + a1.items.get(Items.copper) + "/" + b1.items.get(Items.copper)
            + " 节点links=" + field(node, "links") + " prod.size=" + prod.size
            + " A组=" + groupSize(a1) + " B组=" + groupSize(b1));
        check("默认全共享(15)", mask(node) == ALL);
        check("默认：两边同一个物品池", a1.items == b1.items);
        checkPower("默认：两边同一张电网", sameGrid(a1, b1));

        // ================= 2) 取消勾选"物品" =================
        // 两边共用一份模块时不能把同一份库存数两遍 —— 按"模块去重"统计
        int totalBefore = distinctItemTotal(a1, a2, b1, b2);
        setMask(node, ALL & ~ITEMS);
        run(20);
        int totalAfter = distinctItemTotal(a1, a2, b1, b2);
        System.out.println("[SC] 不共享物品: mask=" + mask(node) + " 同池=" + (a1.items == b1.items)
            + " 总物品 " + totalBefore + " → " + totalAfter
            + "  A铜=" + a1.items.get(Items.copper) + " B铜=" + b1.items.get(Items.copper)
            + " 同电网=" + sameGrid(a1, b1) + " 同液体模块=" + (a1.liquids == b1.liquids)
            + " 模块 A=" + idOf(a1.items) + " B=" + idOf(b1.items));
        check("取消勾选后 mask=14", mask(node) == (ALL & ~ITEMS));
        check("物品池分开（不再是同一个模块）", a1.items != b1.items);
        check("物品总量没变(" + totalBefore + ")", totalAfter == totalBefore);
        checkPower("只取消物品时：电力仍然共享", sameGrid(a1, b1));
        check("只取消物品时：液体模块仍然共享", a1.liquids == b1.liquids);

        // 组内（相邻成组）仍然共用一份：A 的两台之间必须同池
        check("本地组合体内部照旧同池(A1=A2)", a1.items == a2.items);
        check("本地组合体内部照旧同池(B1=B2)", b1.items == b2.items);

        // ================= 3) 勾回"物品" =================
        setMask(node, ALL);
        run(20);
        int backTotal = distinctItemTotal(a1, a2, b1, b2);
        System.out.println("[SC] 勾回物品: 同池=" + (a1.items == b1.items) + " 铜=" + a1.items.get(Items.copper)
            + " 去重总量=" + backTotal + "（拆开的真库存重新合并必须相加，不能当重复副本丢掉）");
        check("勾回后两边又是同一个池", a1.items == b1.items);
        check("勾回后总量没变（40，没翻倍/没丢）", backTotal == 40 && a1.items.get(Items.copper) == 40);

        // ================= 4) 取消勾选"电力" =================
        setMask(node, ALL & ~POWER);
        run(20);
        System.out.println("[SC] 不共享电力: mask=" + mask(node) + " 同电网=" + sameGrid(a1, b1)
            + " 同池=" + (a1.items == b1.items));
        check("取消勾选后 mask=11", mask(node) == (ALL & ~POWER));
        checkPower("两边各自一张电网", !sameGrid(a1, b1));
        check("只取消电力时：物品仍然共享", a1.items == b1.items);
        setMask(node, ALL);
        run(20);
        System.out.println("[SC] 勾回电力: 同电网=" + sameGrid(a1, b1));
        checkPower("勾回电力后并成一张电网", sameGrid(a1, b1));

        // ================= 5) 连接在一起的节点配置一致 =================
        Building node2 = place(nodeBlock, 78, 60, Team.sharded);
        run(10);
        node.onConfigureBuildTapped(node2);
        run(10);
        boolean linked = false;
        Object linksObj = field(node, "links");
        if(linksObj instanceof arc.struct.IntSeq is) linked = is.contains(node2.pos());
        System.out.println("[SC] 节点互连: linked=" + linked + " node.mask=" + mask(node) + " node2.mask=" + mask(node2));
        check("两个节点连上了", linked);
        setMask(node2, ALL & ~LIQUIDS);
        run(10);
        System.out.println("[SC] 在 node2 上改配置: node.mask=" + mask(node) + " node2.mask=" + mask(node2));
        check("点任意一个节点 = 整张网络一起改", mask(node) == (ALL & ~LIQUIDS) && mask(node2) == (ALL & ~LIQUIDS));

        // ================= 6) 存读档 =================
        setMask(node2, ALL & ~ITEMS);
        run(10);
        SaveIO.save(Core.files.absolute("/tmp/cl/sharecfg.msav"));
        SaveIO.load(Core.files.absolute("/tmp/cl/sharecfg.msav"));
        // 读档会把状态带回非 playing（没有核心 / 刚进地图）：建筑得继续更新，否则后面测不了
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.waves = false;
        Vars.state.set(mindustry.core.GameState.State.playing);
        run(40);
        Building node3 = Vars.world.build(node.tileX(), node.tileY());
        System.out.println("[SC] 读档后: node=" + node3 + " mask=" + (node3 == null ? -1 : mask(node3)));
        check("读档后节点还在", node3 != null);
        check("读档后共享配置还在（14）", node3 != null && mask(node3) == (ALL & ~ITEMS));
        Building na = Vars.world.build(a1.tileX(), a1.tileY());
        Building nb = Vars.world.build(b1.tileX(), b1.tileY());
        check("读档后物品池还是分开的", na != null && nb != null && na.items != nb.items);

        // ================= 7) 组合连接器同一套配置 =================
        clearArea();
        Building la1 = place(prod, 60, 60, Team.sharded);
        Building la2 = place(prod, 60 + sz, 60, Team.sharded);
        Building c1 = place(connBlock, 60 + sz * 2, 60, Team.sharded);
        Building c2 = place(connBlock, 60 + sz * 2 + 1, 60, Team.sharded);
        Building lb1 = place(prod, 60 + sz * 2 + 2, 60, Team.sharded);
        run(30);
        la1.items.add(Items.copper, 30);
        run(10);
        System.out.println("[SC] 连接器: c1=" + c1 + " 同池=" + (la1.items == lb1.items) + " mask=" + mask(c1));
        check("连接器接上后同一个池", la1.items == lb1.items);
        check("连接器默认全共享", mask(c1) == ALL);
        setMask(c1, ALL & ~ITEMS);
        run(20);
        int connTotal = la1.items.total() + lb1.items.total();
        System.out.println("[SC] 连接器取消物品: 同池=" + (la1.items == lb1.items) + " 总=" + connTotal + " mask(c1)=" + mask(c1) + " mask(c2)=" + mask(c2));
        check("连接器：物品池分开", la1.items != lb1.items);
        check("连接器：一条链上的连接器配置一致", mask(c1) == (ALL & ~ITEMS) && mask(c2) == (ALL & ~ITEMS));
        check("连接器：物品总量没变(30)", connTotal == 30);
        setMask(c2, ALL);
        run(20);
        check("连接器：改回全共享后又是同一个池", la1.items == lb1.items && la1.items.total() == 30);

        // ================= 8) 液体：共享/不共享 =================
        clearArea();
        Block lm = liquidMaker != null ? liquidMaker : prod;
        if(lm != null && !lm.hasLiquids) lm = findExact("melter");
        // 间距必须按**这台方块自己的**尺寸算（1x1 的方块留 2 格就断开相邻了）
        int lsz = Math.max(lm.size, 1);
        Building lq1 = place(lm, 60, 60, Team.sharded);
        Building lq2 = place(lm, 60 + lsz, 60, Team.sharded);
        Building d1 = place(connBlock, 60 + lsz * 2, 60, Team.sharded);
        Building d2 = place(connBlock, 60 + lsz * 2 + 1, 60, Team.sharded);
        Building lq3 = place(lm, 60 + lsz * 2 + 2, 60, Team.sharded);
        run(30);
        System.out.println("[SC] 液体: 同液体模块=" + (lq1.liquids == lq3.liquids)
            + " 液体容量=" + field(lq1, "comboTotalLiquidCap"));
        check("液体默认共享（同一个模块）", lq1.liquids == lq3.liquids);
        setMask(d1, ALL & ~LIQUIDS);
        run(20);
        lq1.liquids.add(Liquids.water, 10f);
        run(6);
        System.out.println("[SC] 不共享液体: 同模块=" + (lq1.liquids == lq3.liquids)
            + " A水=" + lq1.liquids.get(Liquids.water) + " B水=" + lq3.liquids.get(Liquids.water)
            + " 同物品池=" + (lq1.items == lq3.items));
        check("液体池分开", lq1.liquids != lq3.liquids);
        check("A 加的水不会跑到 B（不混）", lq1.liquids.get(Liquids.water) > 9f && lq3.liquids.get(Liquids.water) < 0.001f);
        check("只取消液体时：物品仍然共享", lq1.items == lq3.items);
        setMask(d2, ALL);
        run(20);
        System.out.println("[SC] 勾回液体: 同模块=" + (lq1.liquids == lq3.liquids) + " 水=" + lq1.liquids.get(Liquids.water));
        check("勾回后又共用一个液体池", lq1.liquids == lq3.liquids);
        check("勾回后水量没翻倍", lq1.liquids.get(Liquids.water) <= 10.01f);

        // ================= 9) 液体输出只从生产它的工厂出口 =================
        clearArea();
        Block melter = findExact("melter");            // 组合熔炼炉：产出 熔渣
        Block mixer = findExact("cryofluid-mixer");    // 组合冷冻液混合机：产出 冷冻液
        Block tank = findExact("liquid-container");
        System.out.println("[SC] 液体输出用方块: melter=" + (melter==null?null:melter.getClass().getName())
            + " mixer=" + (mixer==null?null:mixer.getClass().getName()) + " tank=" + tank);
        if(melter == null || mixer == null || tank == null || !(melter instanceof mindustry.world.blocks.production.GenericCrafter)){
            System.out.println("[SC] 缺组合熔炼炉/混合机/储液罐，跳过液体输出用例");
        }else if(!melter.getClass().getName().contains("CombinedCrafter") || !mixer.getClass().getName().contains("CombinedCrafter")){
            System.out.println("[SC] melter/mixer 不是组合工厂（" + melter.getClass().getName() + " / " + mixer.getClass().getName() + "），跳过");
        }else{
            Building m = place(melter, 60, 60, Team.sharded);
            Building x = place(mixer, 61, 60, Team.sharded);
            Building sinkW = place(tank, 58, 60, Team.sharded);   // 贴着熔炼炉
            Building sinkE = place(tank, 63, 60, Team.sharded);   // 贴着混合机
            run(30);
            System.out.println("[SC] 组合工厂: m=" + m + " @" + (m == null ? "?" : m.tileX() + "," + m.tileY())
                + " x=" + x + " @" + (x == null ? "?" : x.tileX() + "," + x.tileY())
                + " m.group=" + groupSize(m) + " x.group=" + groupSize(x)
                + " 同池=" + (m.items == x.items) + " 同液体池=" + (m.liquids == x.liquids));
            check("两种组合工厂接在一起共用一口液体池", m.liquids == x.liquids);
            m.liquids.add(Liquids.slag, 20f);
            m.liquids.add(Liquids.cryofluid, 20f);
            // 两台机器各自会定期外送：混着输出的老逻辑会把"整组产物"从**每个**出口倒出去，
            // 于是西侧（贴着熔炼炉）会出现冷冻液、东侧（贴着混合机）会出现熔渣。
            run(20);
            System.out.println("[SC] 运行 20 tick 后: 西侧(熔炼炉口) 熔渣=" + sinkW.liquids.get(Liquids.slag)
                + " 冷冻液=" + sinkW.liquids.get(Liquids.cryofluid)
                + "  东侧(混合机口) 熔渣=" + sinkE.liquids.get(Liquids.slag) + " 冷冻液=" + sinkE.liquids.get(Liquids.cryofluid));
            check("熔炼炉的出口只出熔渣（不带出冷冻液）",
                sinkW.liquids.get(Liquids.slag) > 0.001f && sinkW.liquids.get(Liquids.cryofluid) < 0.001f
                && sinkE.liquids.get(Liquids.slag) < 0.001f);
            check("混合机的出口只出冷冻液（不带出熔渣）",
                sinkE.liquids.get(Liquids.cryofluid) > 0.001f && sinkE.liquids.get(Liquids.slag) < 0.001f);
            // 再单独叫各自外送一次：确认是"按生产它的工厂"分流，而不是碰巧
            sinkW.liquids.clear();
            sinkE.liquids.clear();
            dumpOutputs(m);
            System.out.println("[SC] 单独让熔炼炉外送: 西侧 熔渣=" + sinkW.liquids.get(Liquids.slag)
                + " 冷冻液=" + sinkW.liquids.get(Liquids.cryofluid)
                + "  东侧 熔渣=" + sinkE.liquids.get(Liquids.slag) + " 冷冻液=" + sinkE.liquids.get(Liquids.cryofluid));
            check("单独叫熔炼炉外送：只从它自己的出口出熔渣",
                sinkW.liquids.get(Liquids.slag) > 0.001f && sinkE.liquids.get(Liquids.cryofluid) < 0.001f
                && sinkW.liquids.get(Liquids.cryofluid) < 0.001f);
            sinkW.liquids.clear();
            sinkE.liquids.clear();
            dumpOutputs(x);
            System.out.println("[SC] 单独让混合机外送: 西侧 熔渣=" + sinkW.liquids.get(Liquids.slag)
                + " 冷冻液=" + sinkW.liquids.get(Liquids.cryofluid)
                + "  东侧 熔渣=" + sinkE.liquids.get(Liquids.slag) + " 冷冻液=" + sinkE.liquids.get(Liquids.cryofluid));
            check("单独叫混合机外送：只从它自己的出口出冷冻液",
                sinkE.liquids.get(Liquids.cryofluid) > 0.001f && sinkW.liquids.get(Liquids.slag) < 0.001f
                && sinkE.liquids.get(Liquids.slag) < 0.001f);
        }

        // ================= 10) 热量共享开关 =================
        clearArea();
        Block heater = find("coop-heater");
        Block heatUser = find("coop-heat-user");
        if(heater == null || heatUser == null){
            System.out.println("[SC] 这套数据目录没有产热/耗热测试方块，跳过热量用例");
        }else{
            int hsz = Math.max(heater.size, 1), usz = Math.max(heatUser.size, 1);
            Building h1 = place(heater, 60, 60, Team.sharded);
            Building h2 = place(heater, 60 + hsz, 60, Team.sharded);
            Building u1 = place(heatUser, 90, 60, Team.sharded);
            Building u2 = place(heatUser, 90 + usz, 60, Team.sharded);
            Building hnode = place(nodeBlock, 75, 60, Team.sharded);
            run(20);
            hnode.onConfigureBuildTapped(h1);
            run(4);
            hnode.onConfigureBuildTapped(u1);
            run(20);
            h1.items.add(Items.coal, 10);
            run(120);
            float heatOn = heatOf(u1);
            setMask(hnode, ALL & ~HEAT);
            run(60);
            float heatOff = heatOf(u1);
            System.out.println("[SC] 热量: 勾上时 耗热方heat=" + heatOn + "（产热方=" + heatOf(h1)
                + "），取消勾选后 耗热方heat=" + heatOff + " mask=" + mask(hnode));
            check("勾上热量：隔着组合节点也吃得到热", heatOn > 1f);
            check("取消热量：吃不到热了", heatOff <= 0.001f);
        }

        System.out.println("[SC] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
