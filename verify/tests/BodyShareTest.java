package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 组合体自己的「共享哪些部分」配置（用户要求：给组合体加一个新悬浮面板，能像组合节点/连接器
 * 那样勾选是否共享 物品/液体/电力/热量）。
 *
 * 面板本体是 UI（真客户端截图另验），这里验它读写的那份配置：
 *  1) 默认全共享 = 老行为（组合体之间共用池子 / 电网）
 *  2) 在**组合体**上取消勾选"物品" = 整张网络（含连接器/别的组合体）一起改，两边物品池分开
 *  3) 勾回来又合并，总量不丢（没有翻倍/丢一半）
 *  4) 组合体自己的配置**写进存档**，读档后还在（ComboShareState 自定义块）
 *  5) 空网络（没有连接件）的组合体也能存自己的配置，且不影响本地组合体内部照旧同池
 */
public class BodyShareTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_cj/data";
    static int pass = 0, fail = 0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[BS] "+t); };
        new HeadlessApplication(new BodyShareTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[BS] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
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
    static Object field(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return null; }
    static Class<?> modClass(String name){
        try{ return Class.forName(name, true, Vars.mods.getMod("combine").main.getClass().getClassLoader()); }
        catch(Throwable t){ return null; } }
    /** ComboShare.maskOf(building) */
    static int maskOf(Building b){
        try{
            Class<?> c = modClass("combine.net.ComboShare");
            Object r = c.getMethod("maskOf", Building.class).invoke(null, b);
            return r instanceof Number n ? n.intValue() : -1;
        }catch(Throwable t){ Log.err("[BS] maskOf 失败", t); return -1; } }
    /** ComboShare.toggle(building, bit) —— 悬浮面板勾选框走的就是它 */
    static int toggle(Building b, int bit){
        int r = toggleRaw(b, bit);
        run(6);
        return r; }
    static int toggleRaw(Building b, int bit){
        try{
            Class<?> c = modClass("combine.net.ComboShare");
            Object r = c.getMethod("toggle", Building.class, int.class).invoke(null, b, bit);
            return r instanceof Number n ? n.intValue() : -1;
        }catch(Throwable t){ Log.err("[BS] toggle 失败", t); return -1; } }
    /** 组合体的本地组成员数（反射调 group()）。 */
    static int groupSize(Building b){
        try{
            java.lang.reflect.Method mm = b.getClass().getMethod("group");
            Object r = mm.invoke(b);
            if(r instanceof arc.struct.Seq<?> s) return s.size;
        }catch(Throwable ignored){}
        return -1; }
    static int distinctItemTotal(Building... bs){
        arc.struct.ObjectSet<mindustry.world.modules.ItemModule> seen = new arc.struct.ObjectSet<>();
        int total = 0;
        for(Building b : bs){
            if(b == null || b.items == null) continue;
            if(seen.add(b.items)) total += b.items.total();
        }
        return total; }
    static void clearArea(){
        for(int y=40;y<140;y++) for(int x=20;x<240;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5); }
    static final int ITEMS = 1, LIQUIDS = 2, POWER = 4, HEAT = 8, ALL = 15;

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
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.waves = false;
        Vars.logic.play();
        Vars.state.set(mindustry.core.GameState.State.playing);
        run(20);
        clearArea();

        Block prod = find("coop-producer");
        if(prod == null || !prod.hasItems){
            for(String n : new String[]{"silicon-smelter", "kiln", "pulverizer", "melter"}){
                Block c = findExact(n);
                if(c != null && c.hasItems){ prod = c; break; }
            }
        }
        Block connBlock = byClass("combine.net.ComboConnector");
        Block nodeBlock = byClass("combine.net.ComboNode");
        System.out.println("[BS] prod=" + prod + " conn=" + connBlock + " node=" + nodeBlock);
        if(prod == null || connBlock == null){ System.out.println("[BS] 缺方块"); System.exit(3); }
        int sz = Math.max(prod.size, 1);

        // ================= 1) 组合体在整张网络里下配置（连接器分开的两个组合体） =================
        Building a1 = place(prod, 60, 60, Team.sharded);
        Building a2 = place(prod, 60 + sz, 60, Team.sharded);
        Building c1 = place(connBlock, 60 + sz * 2, 60, Team.sharded);
        Building c2 = place(connBlock, 60 + sz * 2 + 1, 60, Team.sharded);
        Building b1 = place(prod, 60 + sz * 2 + 2, 60, Team.sharded);
        Building b2 = place(prod, 60 + sz * 2 + 2 + sz, 60, Team.sharded);
        run(30);
        if(a1 == null || b1 == null || c1 == null){ System.out.println("[BS] 摆放失败"); System.exit(3); }
        a1.items.add(Items.copper, 40);
        run(10);
        System.out.println("[BS] 默认: maskOf(组合体)=" + maskOf(a1) + " 同池=" + (a1.items == b1.items)
            + " A组=" + groupSize(a1) + " 铜=" + a1.items.get(Items.copper) + "/" + b1.items.get(Items.copper));
        check("默认全共享(15)", maskOf(a1) == ALL && maskOf(c1) == ALL);
        check("默认：两边同一个物品池", a1.items == b1.items);

        // 在**组合体**上取消勾选"物品"（等价于点悬浮面板上的"物品"勾选框）
        int afterToggle = toggle(a1, ITEMS);
        run(20);
        int total = distinctItemTotal(a1, a2, b1, b2);
        System.out.println("[BS] 组合体取消物品: 返回mask=" + afterToggle + " maskOf(组合体)=" + maskOf(a1)
            + " maskOf(连接器)=" + maskOf(c1) + " 同池=" + (a1.items == b1.items) + " 总=" + total);
        check("点组合体也能改配置（不是必须点连接件）", maskOf(a1) == (ALL & ~ITEMS));
        check("整张网络一起改（连接器也变 14）", maskOf(c1) == (ALL & ~ITEMS) && maskOf(c2) == (ALL & ~ITEMS));
        check("物品池分开（不再是同一个模块）", a1.items != b1.items);
        check("物品总量没变(" + total + ")", total == 40);
        // 【口径变了】"不共享物品"现在连**本地组合体内部**也不共用（每台各留各的模块）：
        // 用户报的"关闭物品共享后物品模块还是共享的"就是这条。
        check("本地组合体内部也分开了（每台一份模块）", a1.items != a2.items && b1.items != b2.items);

        // 勾回来
        toggle(a1, ITEMS);
        run(20);
        System.out.println("[BS] 勾回物品: maskOf=" + maskOf(a1) + " 同池=" + (a1.items == b1.items)
            + " 铜=" + a1.items.get(Items.copper));
        check("组合体勾回后又是同一个池", a1.items == b1.items);
        check("勾回后总量没变(40)", a1.items.total() == 40);

        // ================= 2) 组合体自己的配置写进存档 =================
        toggle(a1, ITEMS);          // → 14（不共享物品）
        run(10);
        toggle(a1, LIQUIDS);        // → 12（物品+液体都不共享）
        run(10);
        int want = ALL & ~ITEMS & ~LIQUIDS;
        System.out.println("[BS] 存前: maskOf(组合体)=" + maskOf(a1) + "（期望 " + want + "）");
        check("组合体配置=12", maskOf(a1) == want);
        SaveIO.save(Core.files.absolute("/tmp/cl/bodyshare.msav"));
        SaveIO.load(Core.files.absolute("/tmp/cl/bodyshare.msav"));
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.waves = false;
        Vars.state.set(mindustry.core.GameState.State.playing);
        run(40);
        Building ra = Vars.world.build(a1.tileX(), a1.tileY());
        Building rc = Vars.world.build(c1.tileX(), c1.tileY());
        System.out.println("[BS] 读档后: 组合体=" + ra + " mask=" + (ra == null ? -1 : maskOf(ra))
            + " 连接器 mask=" + (rc == null ? -1 : maskOf(rc)));
        check("读档后组合体还在", ra != null);
        check("读档后组合体自己的共享配置还在(12)", ra != null && maskOf(ra) == want);

        // ================= 3) 孤立组合体（没有连接件）也能存配置 =================
        clearArea();
        Building s1 = place(prod, 60, 60, Team.sharded);
        Building s2 = place(prod, 60 + sz, 60, Team.sharded);
        run(30);
        System.out.println("[BS] 孤立组合体: 同池=" + (s1.items == s2.items) + " mask=" + maskOf(s1));
        check("孤立组合体默认全共享", maskOf(s1) == ALL && s1.items == s2.items);
        toggle(s1, POWER);
        run(20);
        System.out.println("[BS] 孤立组合体取消电力: mask=" + maskOf(s1) + " 同池=" + (s1.items == s2.items));
        check("孤立组合体也能记下自己的配置(11)", maskOf(s1) == (ALL & ~POWER));
        check("取消的是电力：物品池没受影响", s1.items == s2.items
            && s1.liquids == s2.liquids);

        // ================= 4) 单个本地组合体：取消"物品"后组内还要不要同池 =================
        clearArea();
        Building t1 = place(prod, 60, 60, Team.sharded);
        Building t2 = place(prod, 60 + sz, 60, Team.sharded);
        run(30);
        t1.items.add(Items.copper, 40);
        run(10);
        System.out.println("[BS] 本地组合体: 同池=" + (t1.items == t2.items) + " mask=" + maskOf(t1));
        check("本地组合体默认同池", t1.items == t2.items);
        toggle(t1, ITEMS);
        run(30);
        System.out.println("[BS] 本地组合体取消物品: mask=" + maskOf(t1) + " 同池=" + (t1.items == t2.items)
            + " A铜=" + t1.items.get(Items.copper) + " B铜=" + t2.items.get(Items.copper));
        check("本地组合体取消物品后两台各留各的物品模块", t1.items != t2.items);
        check("本地组合体取消物品后物品总量没变(40)",
            distinctItemTotal(t1, t2) == 40);

        // ================= 5) 原版替换出来的组合方块（CombinedDrill）：本地取消物品共享 =================
        // 协作组合（上面用的 coop-producer）的并池由 ComboNet 一家做；
        // 原版替换出来的组合方块**自己**还有一套 shareModules，必须也认这个开关。
        clearArea();
        Block drill = findExact("mechanical-drill");
        if (drill == null || !drill.getClass().getName().contains("CombinedDrill")) {
          System.out.println("[BS] SKIP 这套数据集没有组合机械钻（" + drill + "）");
        } else {
          int dsz = Math.max(drill.size, 1);
          Building d1 = place(drill, 60, 60, Team.sharded);
          Building d2 = place(drill, 60 + dsz, 60, Team.sharded);
          run(30);
          boolean shared0 = d1.items == d2.items;
          d1.items.add(Items.copper, 40);
          run(10);
          toggle(d1, ITEMS);
          run(30);
          int dTotal = distinctItemTotal(d1, d2);
          System.out.println("[BS] 组合机械钻: 默认同池=" + shared0 + " 取消物品后同池="
              + (d1.items == d2.items) + " 总=" + dTotal + " A=" + d1.items.get(Items.copper)
              + " B=" + d2.items.get(Items.copper));
          check("组合机械钻默认同池", shared0);
          check("组合机械钻取消物品后两台各留各的模块", d1.items != d2.items);
          check("组合机械钻取消物品后总量没变(40)", dTotal == 40);

          // 再并一台进来（触发本地组的拆/并）：总量既不能丢、也不能翻倍
          place(drill, 60 + dsz * 2, 60, Team.sharded);
          run(40);
          int dTotal3 = distinctItemTotal(d1, d2, Vars.world.build(60 + dsz * 2, 60));
          System.out.println("[BS] 组合机械钻加第三台后: 总=" + dTotal3);
          check("加/拆成员后物品总量还是 40（没丢没翻倍）", dTotal3 == 40);

          // 拆掉第三台（本地组变小 → 走 splitAssets）：剩下的两台还是各留各的，不能崩、不能丢机器
          Tile third = Vars.world.tile(60 + dsz * 2, 60);
          if (third != null)
            third.setBlock(Blocks.air);
          run(30);
          System.out.println("[BS] 组合机械钻拆掉第三台后: 两台都还在=" + (d1.isValid() && d2.isValid())
              + " 同池=" + (d1.items == d2.items));
          check("拆成员后不崩、两台都还在", d1.isValid() && d2.isValid());
          check("拆成员后剩下的两台还是各留各的模块", d1.items != d2.items);
        }

        // ================= 6) 液体：本地取消共享（口径同物品） =================
        clearArea();
        Block melter = findExact("melter");
        if (melter == null || !melter.getClass().getName().contains("CombinedCrafter")) {
          System.out.println("[BS] SKIP 这套数据集没有组合熔炼炉（" + melter + "）");
        } else {
          int msz = Math.max(melter.size, 1);
          Building q1 = place(melter, 60, 60, Team.sharded);
          Building q2 = place(melter, 60 + msz, 60, Team.sharded);
          run(30);
          boolean sharedL0 = q1.liquids == q2.liquids;
          // 注意：组合方块的液池每帧按容量截断，两台熔炼炉的合计容量只有 2×baseLiquidCapacity，
          // 加超了会被截掉（那是"超容防护"，不是丢物品），所以这里加 15（≤ 合计容量）。
          q1.liquids.add(Liquids.water, 15f);
          run(10);
          toggle(q1, LIQUIDS);
          run(30);
          float w1 = q1.liquids.get(Liquids.water), w2 = q2.liquids.get(Liquids.water);
          System.out.println("[BS] 组合熔炼炉: 默认共用液池=" + sharedL0 + " 取消液体后同池="
              + (q1.liquids == q2.liquids) + " A水=" + w1 + " B水=" + w2);
          check("组合熔炼炉默认共用液池", sharedL0);
          check("组合熔炼炉取消液体后两台各留各的液池", q1.liquids != q2.liquids);
          check("取消液体后水量没丢没翻倍（合计 15）", Math.abs(w1 + w2 - 15f) < 0.5f);
        }

        System.out.println("[BS] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
