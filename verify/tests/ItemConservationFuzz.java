package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.ObjectMap; import arc.struct.ObjectSet; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.Item; import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.ItemModule;
import java.util.Random;

/**
 * 物品守恒模糊测试：核心 + 贴着的容器 + 组合节点 + 组合工厂，随机 连/断/造/拆/存读档，
 * 每一步都检查"世界里的物品总量"只允许因为**被拆掉的方块自己那份模块**减少，
 * 其它任何减少都算丢失（用户报的"拆工厂时核心里的东西全没了"就是这一类）。
 *
 * 操作历史会打印出来，方便最小化复现。
 */
public class ItemConservationFuzz implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static long seed = Long.getLong("ifz.seed", 20260922L);
    static int seedCount = Integer.getInteger("ifz.seeds", 6);
    static int steps = Integer.getInteger("ifz.steps", 200);
    static int pass=0, fail=0;
    static Random rnd;
    static Seq<String> history = new Seq<>();
    static Seq<Block> conts = new Seq<>(), facts = new Seq<>();
    static Block core, node;
    static java.lang.reflect.Method coopSetBlocked, coopIsBlocked;
    static Seq<String> originalBlocked;

    static Class<?> coaxClass(){
        try{
            return Class.forName("combine.coop.CoopCombo", true, Vars.mods.mainLoader());
        }catch(Throwable t){
            throw new RuntimeException(t);
        }
    }
    static boolean isBlocked(String name){
        try{ return Boolean.TRUE.equals(coopIsBlocked.invoke(null, name)); }catch(Throwable t){ return false; }
    }
    static void setBlocked(String name, boolean blocked){
        try{ coopSetBlocked.invoke(null, name, blocked); }catch(Throwable ignored){}
    }
    static void resetBlocklist(){
        for(Block b : Vars.content.blocks()) if(isBlocked(b.name)) setBlocked(b.name, false);
    }
    /** 给全世界当前的物品模块套上探针（每次都会重新套：新建的模块也能被监听到）。 */
    static void installSpies(){
        ObjectMap<ItemModule, SpyItemModule> spies = new ObjectMap<>();
        Seq<Building> all = new Seq<>();
        for(Tile t : Vars.world.tiles){
            Building b = t == null ? null : t.build;
            if(b != null && b.items != null && !all.contains(b, true)) all.add(b);
        }
        for(Building b : all){
            if(b.items instanceof SpyItemModule) continue;
            SpyItemModule spy = spies.get(b.items);
            if(spy == null){ spy = new SpyItemModule(); spy.set(b.items); spies.put(b.items, spy); }
            b.items = spy;
        }
    }
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(System.getProperty("dbg") != null){
                if(l.ordinal()>=arc.util.Log.LogLevel.info.ordinal()) System.out.println("[L] "+t);
            }else if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new ItemConservationFuzz(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[IFZ] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Block findExact(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static Block byClass(String cls){ for(Block b : Vars.content.blocks()) if(b.getClass().getName().equals(cls)) return b; return null; }
    /** 先找完全同名的，再找"以该名字结尾"的（模组方块带"模组名-"前缀）。exactOnly=true 时不模糊。 */
    static Block bySuffix(String name, boolean exactOnly){
        Block exact = findExact(name);
        if(exact != null) return exact;
        if(exactOnly) return null;
        for(Block b : Vars.content.blocks()){
            if(b.name.endsWith("-" + name)){
                String cn = b.getClass().getName();
                if(cn.startsWith("mindustry.")) continue;
                return b;
            }
        }
        return null;
    }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static boolean free(Block b, int x, int y){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        // 实际占地：x ∈ [ax-(size-1)/2, ax+size/2]，y 同理（偶数尺寸往右/下多占一格）
        int x0 = ax - (size - 1) / 2, x1 = ax + size / 2;
        int y0 = ay - (size - 1) / 2, y1 = ay + size / 2;
        for(int tx = x0; tx <= x1; tx++) for(int ty = y0; ty <= y1; ty++){
            Tile t = Vars.world.tile(tx, ty);
            if(t == null || t.block() != Blocks.air) return false;
        }
        return true;
    }
    static void clearArea(){
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
    }
    static Seq<Building> itemBuildings(){
        Seq<Building> out = new Seq<>();
        ObjectSet<Building> seen = new ObjectSet<>();
        for(Tile t : Vars.world.tiles){
            Building b = t == null ? null : t.build;
            if(b == null || b.items == null || !seen.add(b)) continue;
            out.add(b);
        }
        return out;
    }
    /** 模块 -> 每种物品数量（只统计世界里还被建筑引用着的模块）。 */
    /** 世界上所有建筑（含 items==null 的节点/连接器/线缆，它们也是有连线的网络顶点）。 */
    static Seq<Building> allBuildings(){
        Seq<Building> out = new Seq<>();
        ObjectSet<Building> seen = new ObjectSet<>();
        for(Tile t : Vars.world.tiles){
            Building b = t == null ? null : t.build;
            if(b != null && seen.add(b)) out.add(b);
        }
        return out;
    }
    /** 组合节点 / 组合连接器（用户报的"瞎连"就发生在这两类方块上）。 */
    static boolean isNode(Building b){ return b != null && b.getClass().getName().contains("ComboNode"); }
    static boolean isConnector(Building b){ return b != null && b.getClass().getName().contains("ComboConnector"); }
    static Seq<Building> nodes(){
        Seq<Building> out = new Seq<>();
        for(Building b : allBuildings()) if(isNode(b)) out.add(b);
        return out;
    }
    static Block connectorBlock(){ return byClass("combine.net.ComboConnector"); }

    static ObjectMap<ItemModule, int[]> snapshot(){
        ObjectMap<ItemModule, int[]> map = new ObjectMap<>();
        for(Building b : itemBuildings()){
            if(map.containsKey(b.items)) continue;
            int[] arr = new int[Vars.content.items().size];
            for(Item it : Vars.content.items()) arr[it.id] = b.items.get(it);
            map.put(b.items, arr);
        }
        return map;
    }
    static int sum(ObjectMap<ItemModule, int[]> map){
        int t = 0;
        for(ItemModule m : map.keys()) for(int v : map.get(m)) t += v;
        return t;
    }

    /** 核心池的逐物品快照（用来抓"拆工厂时核心里的东西没了"）。 */
    static int[] coreSnap(Building coreBuild){
        if(coreBuild == null || coreBuild.items == null) return null;
        int[] arr = new int[Vars.content.items().size];
        for(Item it : Vars.content.items()) arr[it.id] = coreBuild.items.get(it);
        return arr;
    }
    static int arrSum(int[] a){ int t = 0; for(int v : a) t += v; return t; }
    /** 打印"还活着、手里有物品"的建筑（排查用）。 */
    static void printBuildings(){
        System.out.print(dumpBuildings());
    }
    static String dumpBuildings(){
        StringBuilder out = new StringBuilder();
        java.util.IdentityHashMap<ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
        for(Building b : itemBuildings()){
            if(b.items == null || seen.put(b.items, Boolean.TRUE) != null) continue;
            StringBuilder sb = new StringBuilder();
            for(Item it : Vars.content.items()) if(b.items.get(it) > 0) sb.append(it.name).append('=').append(b.items.get(it)).append(' ');
            out.append("      ").append(b.block.name).append('@').append(b.tileX()).append(',').append(b.tileY())
                .append(" 类=").append(b.getClass().getSimpleName())
                .append(" 模块=").append(System.identityHashCode(b.items)).append(" 总=").append(b.items.total())
                .append(' ').append(sb).append('\n');
        }
        return out.toString();
    }

    /** 装到核心上的"探针模块"：任何让库存变少的调用都打印调用栈（找出是谁删的）。 */
    public static class SpyItemModule extends ItemModule{
        static boolean enabled = false;
        static int addThreshold = Integer.getInteger("spy.add", 0);
        private void note(int before, int after, String what){
            if(!enabled || after >= before) return;
            if(Vars.state != null && Vars.state.isMenu()) return;
            System.out.println("[SPY] " + what + " 核心池 " + before + " → " + after);
            StackTraceElement[] st = new Throwable().getStackTrace();
            for(int i = 2; i < Math.min(st.length, 14); i++) System.out.println("        " + st[i]);
        }
        private void noteAdd(int before, int after, Item item, int amount){
            if(!enabled || after <= before) return;
            if(addThreshold > 0 && amount < addThreshold) return;
            System.out.println("[SPY+] " + item.name + " +" + amount + " 核心池 " + before + " → " + after);
            StackTraceElement[] st = new Throwable().getStackTrace();
            for(int i = 2; i < Math.min(st.length, 12); i++) System.out.println("        " + st[i]);
        }
        @Override public void add(Item item, int amount){
            int before = total; super.add(item, amount); noteAdd(before, total, item, amount);
        }
        @Override public void add(ItemModule other){
            int before = total; super.add(other); note(before, total, "add(模块)");
        }
        @Override public void remove(Item item, int amount){
            int before = total; super.remove(item, amount); note(before, total, "remove(" + item.name + "," + amount + ")");
        }
        @Override public void set(Item item, int amount){
            int before = total; super.set(item, amount); note(before, total, "set(" + item.name + "," + amount + ")");
        }
        @Override public void set(ItemModule other){
            int before = total; super.set(other);
            note(before, total, "set(模块)");
            if(enabled && total != before){
                System.out.println("[SPY±] set(模块) 核心池 " + before + " → " + total);
                StackTraceElement[] st = new Throwable().getStackTrace();
                for(int i = 2; i < Math.min(st.length, 12); i++) System.out.println("        " + st[i]);
            }
        }
        @Override public void read(arc.util.io.Reads read){
            int before = total; super.read(read);
            if(enabled && total != before){
                System.out.println("[SPY±] read() 核心池 " + before + " → " + total);
                StackTraceElement[] st = new Throwable().getStackTrace();
                for(int i = 2; i < Math.min(st.length, 12); i++) System.out.println("        " + st[i]);
            }
        }
        @Override public void clear(){
            int before = total; super.clear(); note(before, total, "clear()");
        }
        @Override public Item take(){
            int before = total; Item it = super.take(); note(before, total, "take()"); return it;
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
        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        if(System.getProperty("dbg") != null){
            try{
                Class.forName("combine.storage.CombinedStorageBlock", true, Vars.mods.mainLoader())
                    .getField("debug").setBoolean(null, true);
                Class.forName("combine.coop.CoopCombo", true, Vars.mods.mainLoader())
                    .getField("debug").setBoolean(null, true);
            }catch(Throwable t){ System.out.println("[IFZ] 开 debug 失败: " + t); }
        }
        clearArea();
        rnd = new Random(seed);

        core = findExact("core-shard");
        node = byClass("combine.net.ComboNode");
        // 【数据集卫生】"设置组合"那一档会把名单写进 settings；先记下原样，跑完恢复，
        // 不然会把数据目录搞脏（后面的测试会看到"某个方块被手动关掉了"）。
        coopSetBlocked = coaxClass().getMethod("setBlocked", String.class, boolean.class);
        coopIsBlocked = coaxClass().getMethod("isBlocked", String.class);
        if(System.getProperty("ifz.resetblocked") != null){
            resetBlocklist();
            System.out.println("[IFZ] 已把「手动不组合」名单清空");
            System.exit(0);
        }
        originalBlocked = new Seq<>();
        for(Block b : Vars.content.blocks()) if(isBlocked(b.name)) originalBlocked.add(b.name);
        System.out.println("[IFZ] 起始「手动不组合」名单=" + originalBlocked);
        for(String n : new String[]{"container","vault","安山合金容器","重型容器","虫蚀集装箱"}){ Block b=bySuffix(n, true); if(b!=null) conts.add(b); }
        for(String n : new String[]{"graphite-press","silicon-smelter","kiln","pulverizer","coop-producer","冶炼厂","冷却机","辊压机","陆军工厂"}){ Block b=bySuffix(n, false); if(b!=null) facts.add(b); }
        System.out.println("[IFZ] core=" + core + " node=" + node + " 容器种类=" + conts.size + " 工厂种类=" + facts.size);
        try{
            Class<?> coop = Class.forName("combine.coop.CoopCombo", true, Vars.mods.mainLoader());
            java.lang.reflect.Method el = coop.getMethod("eligibleByClass", Block.class);
            System.out.println("[IFZ] 可协作组合的方块：");
            for(Block b : Vars.content.blocks()){
                if(Boolean.TRUE.equals(el.invoke(null, b)))
                    System.out.println("     " + b.name + " 类=" + b.getClass().getSimpleName()
                        + " 是仓库=" + (b instanceof mindustry.world.blocks.storage.StorageBlock)
                        + " items=" + b.itemCapacity + " liq=" + b.liquidCapacity);
            }
        }catch(Throwable t){ System.out.println("[IFZ] 列协作组合方块失败: " + t); }
        for(Block b : facts) System.out.println("   工厂候选: " + b.name + " 类=" + b.getClass().getName() + " items=" + b.itemCapacity);
        if(core == null || node == null || conts.isEmpty() || facts.isEmpty()){ System.out.println("[IFZ] 缺方块"); System.exit(3); }
        int bad = 0;
        for(int si = 0; si < seedCount; si++){
            long sd = seed + si;
            if(!runSeed(sd)) bad++;
        }
        // 把这一轮开关动过的名单恢复原样（settings 是数据目录里的持久文件）
        resetBlocklist();
        for(String n : originalBlocked) setBlocked(n, true);
        check("物品守恒模糊测试（seed=" + seed + ".." + (seed + seedCount - 1) + " steps=" + steps + "）", bad == 0);
        System.out.println("[IFZ] RESULT " + (bad==0?"ALL PASS":(bad+" FAILED")));
        System.exit(bad==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }

    static boolean runSeed(long sd){
        rnd = new Random(sd);
        history.clear();
        clearArea();
        System.out.println("[IFZ] ---- seed=" + sd + " ----");
        Building coreBuild = place(core, 60, 60, Team.sharded);
        run(5);
        Building first = place(conts.first(), 63, 60, Team.sharded);
        run(10);
        coreBuild.items.add(Items.copper, 800);
        coreBuild.items.add(Items.lead, 400);
        coreBuild.items.add(Items.graphite, 300);
        run(10);
        if(System.getProperty("spy") != null){
            // 全世界的物品模块都换成探针（同一份模块共用一个探针，保持共享关系不变）
            ObjectMap<ItemModule, SpyItemModule> spies = new ObjectMap<>();
            Seq<Building> all = new Seq<>();
            for(Tile t : Vars.world.tiles){
                Building b = t == null ? null : t.build;
                if(b != null && b.items != null && !all.contains(b, true)) all.add(b);
            }
            for(Building b : all){
                SpyItemModule spy = spies.get(b.items);
                if(spy == null){ spy = new SpyItemModule(); spy.set(b.items); spies.put(b.items, spy); }
                b.items = spy;
            }
            SpyItemModule.enabled = true;
            run(5);
        }
        run(10);
        Building nodeBuild = place(node, 66, 60, Team.sharded);
        run(10);

        for(int step = 0; step < steps; step++){
            int op = rnd.nextInt(100);
            String act;
            // 判定精度：0=必须分毫不差（连/断/设置组合这类"只改结构"的操作）
            //           1=只许不增长 + 丢失不超过被拆模块（拆方块：池子可能搬给剩下的成员）
            //           2=不判（造方块/灌物品/跑 tick/存读档，本来就改了总量）
            int expectMode = 0;
            boolean skipCoreWatch = false;
            if(System.getProperty("spy") != null) installSpies();
            ObjectMap<ItemModule, int[]> before = snapshot();
            int beforeTotal = sum(before);
            int[] coreBefore = coreSnap(coreBuild);
            String beforeDump = System.getProperty("ifz.dump") != null ? dumpBuildings() : null;
            if(op < 14){
                // 造一台工厂
                Block b = facts.get(rnd.nextInt(facts.size));
                int x = 55 + rnd.nextInt(40), y = 52 + rnd.nextInt(20);
                act = "造工厂 " + b.name + "@" + x + "," + y;
                if(free(b, x, y)) place(b, x, y, Team.sharded);
                else act += " (位置被占，跳过)";
                expectMode = 1;
            }else if(op < 24){
                // 造一个容器（贴着核心或别处）
                Block b = conts.get(rnd.nextInt(conts.size));
                boolean near = rnd.nextBoolean();
                int x = near ? (63 + rnd.nextInt(3)) : (55 + rnd.nextInt(40));
                int y = near ? (57 + rnd.nextInt(7)) : (52 + rnd.nextInt(20));
                act = "造容器 " + b.name + "@" + x + "," + y;
                if(free(b, x, y)) place(b, x, y, Team.sharded);
                else act += " (位置被占，跳过)";
                expectMode = 1;
            }else if(op < 30){
                // 【新增】再放一个组合节点：用户报的"瞎连"就是多节点互相连线（接力）时出的
                int x = 52 + rnd.nextInt(30), y = 48 + rnd.nextInt(26);
                act = "造节点@" + x + "," + y;
                if(free(node, x, y)) place(node, x, y, Team.sharded);
                else act += " (位置被占，跳过)";
                expectMode = 1;
            }else if(op < 34){
                // 【新增】组合连接器：它按相邻关系自动并池，是"瞎连"的另一条路径
                Block cb = connectorBlock();
                int x = 52 + rnd.nextInt(30), y = 48 + rnd.nextInt(26);
                act = "造连接器@" + x + "," + y;
                if(cb == null) act += " (没有连接器方块)";
                else if(free(cb, x, y)) place(cb, x, y, Team.sharded);
                else act += " (位置被占，跳过)";
                expectMode = 1;
            }else if(op < 40){
                // 【新增】给**非核心**的组合建筑灌物品：用户报的"瞎连导致物品异常增长/减少"
                // 发生在"独立组合体身上有物品"时（核心池有专门的不拆分支，原来只喂核心永远测不到）
                Seq<Building> cand = new Seq<>();
                for(Building b : allBuildings()){
                    if(b == coreBuild || b.items == null) continue;
                    if(b.block instanceof mindustry.world.blocks.storage.CoreBlock) continue;
                    if(isNode(b) || isConnector(b)) continue;
                    cand.add(b);
                }
                if(cand.isEmpty()){ act = "灌物品 (没目标)"; }
                else {
                    Building b = cand.get(rnd.nextInt(cand.size));
                    Item it = Vars.content.items().get(rnd.nextInt(Vars.content.items().size));
                    int amt = 1 + rnd.nextInt(5000);
                    b.items.add(it, amt);
                    act = "灌物品 " + b.block.name + "@" + b.tileX() + "," + b.tileY() + " +" + amt + " " + it.name;
                    // 这一步本来就改了总量：不参与守恒判定，下一步重新取基准
                    expectMode = 2;
                }
            }else if(op < 52){
                Seq<Building> cand = new Seq<>();
                // 【改动】节点/连接器现在也在拆除候选里（items==null，原来遍历 itemBuildings() 永远看不到它们）：
                // "拆掉连着一堆线的节点"是最容易出现"物品凭空多/少"的路径
                for(Building b : allBuildings()) if(b != coreBuild && !(b.block instanceof mindustry.world.blocks.storage.CoreBlock)) cand.add(b);
                if(cand.isEmpty()){ act = "拆方块 (没得拆)"; }
                else {
                    Building b = cand.get(rnd.nextInt(cand.size));
                    act = "拆 " + b.block.name + "@" + b.tileX() + "," + b.tileY();
                    before = snapshot(); beforeTotal = sum(before);
                    Vars.world.tile(b.tileX(), b.tileY()).setBlock(Blocks.air);
                    // 拆方块：池子可能整份搬给剩下的成员（总量不变）或被带走（总量减少），只判"不许增长"
                    expectMode = 1;
                }
            }else if(op < 66){
                Seq<Building> cand = new Seq<>();
                act = "连线";
                // 【改动】连线候选遍历**所有**建筑：节点/连接器的 items 是 null，
                // 原来用 itemBuildings() 导致"节点连节点/节点连连接器"从来没被测到
                Seq<Building> live = nodes();
                Building nd = live.isEmpty() ? null : live.get(rnd.nextInt(live.size));
                if(nd == null){ act = "连线 (没有节点)"; }
                else
                for(Building b : allBuildings()){
                    if(b == coreBuild) continue;
                    if(b == nd) continue;
                    if(buildingInNodeRange(nd, b)) cand.add(b);
                }
                if(nd == null){ /* 上面已处理 */ }
                else if(cand.isEmpty()){ act = "连线 (没目标)"; }
                else {
                    Building b = cand.get(rnd.nextInt(cand.size));
                    act = "节点连线 " + b.block.name + "@" + b.tileX() + "," + b.tileY();
                    nd.onConfigureBuildTapped(b);
                }
            }else if(op < 76){
                Seq<Building> linked = new Seq<>();
                Seq<Building> live = nodes();
                Building nd = live.isEmpty() ? null : live.get(rnd.nextInt(live.size));
                if(nd != null) for(Building b : linkedTargets(nd)) linked.add(b);
                if(linked.isEmpty()){ act = "断线 (没连线)"; }
                else {
                    Building b = linked.get(rnd.nextInt(linked.size));
                    act = "节点断线 " + b.block.name + "@" + b.tileX() + "," + b.tileY();
                    nd.onConfigureBuildTapped(b);
                }
            }else if(op < 95){
                act = "跑 " + (1 + rnd.nextInt(4)) + " tick";
                run(1 + rnd.nextInt(4));
                expectMode = 2;
                skipCoreWatch = true;
            }else if(op < 98){
                // 设置里把某个方块"组合"关掉/打开（CoopCombo 的脱离路径）
                Seq<Block> all = new Seq<>();
                all.addAll(facts); all.addAll(conts);
                Block b = all.get(rnd.nextInt(all.size));
                boolean off = rnd.nextBoolean();
                act = "设置组合 " + b.name + "=" + !off;
                try{
                    Class<?> coop = Class.forName("combine.coop.CoopCombo", true, Vars.mods.mainLoader());
                    coop.getMethod("setBlocked", String.class, boolean.class).invoke(null, b.name, off);
                }catch(Throwable t){ act += " 失败:" + t; }
            }else{
                act = "存读档";
                try{
                    String path = "/tmp/cl/ifz.msav";
                    SaveIO.save(Core.files.absolute(path));
                    SaveIO.load(Core.files.absolute(path));
                    Vars.logic.play();
                    run(5);
                }catch(Throwable t){ act += " 失败:" + t; }
                expectMode = 2;
                skipCoreWatch = true;
            }
            history.add(step + ": " + act);
            if(history.size > 60) history.remove(0);

            if(expectMode != 2){
                ObjectMap<ItemModule, int[]> after = snapshot();
                int afterTotal = sum(after);
                int lostModules = 0;
                for(ItemModule m : before.keys()) if(!after.containsKey(m)) for(int v : before.get(m)) lostModules += v;
                int expected = beforeTotal - lostModules;
                boolean bad;
                if(expectMode == 0){
                    // 连/断/设置组合只改结构：总量必须分毫不差（用户报的"瞎连导致物品异常增长/减少"）
                    bad = afterTotal != beforeTotal;
                    expected = beforeTotal;
                }else{
                    // 拆方块：池子可能整份搬给剩下的成员（总量不变）或被带走（总量减少），
                    // 但绝不允许凭空变多
                    bad = afterTotal > beforeTotal || afterTotal < expected;
                }
                if(bad){
                    System.out.println("[IFZ] *** 物品不守恒! seed=" + sd + " step=" + step + " 操作=" + act
                        + " 之前=" + beforeTotal + " 之后=" + afterTotal + " 拆掉模块带走=" + lostModules
                        + " (差 " + (afterTotal - expected) + ")");
                    System.out.println("[IFZ] 操作前有物品的建筑：");
                    System.out.print(beforeDump == null ? "" : beforeDump);
                    if(beforeDump == null) printBuildings();
                    System.out.println("[IFZ] 操作后有物品的建筑：");
                    printBuildings();
                    System.out.println("[IFZ] 历史:");
                    for(String h : history) System.out.println("   " + h);
                    return false;
                }
            }

            // 【为什么不再"多跑两 tick 看核心库存有没有变少"】工厂在跑的时候会合法地吃料/倒货
            //（组合体共用核心那份池子，倒货就是把物品从池子里搬到旁边的容器里），
            // 只看"核心池少了"会把正常行为当成丢物品。上面"操作当下总量守恒"那一条才是判据。
        }
        return true;
    }
    static boolean buildingInNodeRange(Building node, Building b){
        try{
            Class<?> c = Class.forName("combine.net.ComboNode", true, Vars.mods.mainLoader());
            Object r = c.getMethod("linkValid", Building.class, Building.class, boolean.class).invoke(node.block, node, b, false);
            return r instanceof Boolean bl && bl;
        }catch(Throwable t){ return false; }
    }
    static Seq<Building> linkedTargets(Building node){
        Seq<Building> out = new Seq<>();
        try{
            java.lang.reflect.Field lf = node.getClass().getDeclaredField("links");
            lf.setAccessible(true);
            arc.struct.IntSeq links = (arc.struct.IntSeq)lf.get(node);
            for(int i = 0; i < links.size; i++){
                Building b = Vars.world.build(links.get(i));
                if(b != null) out.add(b);
            }
        }catch(Throwable ignored){}
        return out;
    }
}
