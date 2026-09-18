package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.files.Fi; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.Item; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 组合仓库给核心扩容的三种情况（用户报的"重进补满 / 造新仓库丢物品"）：
 *   A 造新容器：**不能丢物品**，容量要涨；
 *   B 拆容器：不涨（超容部分按需求截断，但不能凭空变多）；
 *   C 存读档：物品数量一分不差（读档时仓库手里是核心库存的副本，必须去重而不是相加）。
 */
public class CoreCapacityTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new CoreCapacityTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[CC] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Building core(){
        for(Building b : Vars.state.teams.get(Team.sharded).cores) return b;
        return null; }
    static int glass(){ Building c = core(); return c == null ? -1 : c.items.get(Items.metaglass); }
    static int total(){ Building c = core(); return c == null ? -1 : c.items.total(); }
    static int cap(){ Building c = core(); return c instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild cb ? cb.storageCapacity : -1; }
    static void clearArea(){
        for(int y=40;y<140;y++) for(int x=20;x<240;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
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
        Block cont = null, vault = null;
        for(Block b : Vars.content.blocks()){
            if(b.name.equals("container")) cont = b;
            if(b.name.equals("vault")) vault = b;
        }
        if(cont == null || vault == null){ System.out.println("[CC] 缺容器方块"); System.exit(3); }

        // ---------- A 造新容器：不能丢物品 ----------
        clearArea();
        Building c0 = place(Blocks.coreShard, 60, 60, Team.sharded);
        run(10);
        c0.items.set(Items.metaglass, c0.block.itemCapacity);      // 正好装满核心
        run(10);
        int beforeA = glass(), capA = cap();
        System.out.println("[CC] A 造容器前: 玻璃=" + beforeA + " 容量=" + capA);
        Building ca = place(cont, 63, 60, Team.sharded);
        run(40);
        System.out.println("[CC] A 造容器后: 玻璃=" + glass() + " 容量=" + cap() + " (并进核心=" + (ca.items == c0.items) + ")");
        check("A 造新容器不丢物品（" + beforeA + " → " + glass() + "）", glass() >= beforeA);
        check("A 造新容器容量变大（" + capA + " → " + cap() + "）", cap() > capA);

        // ---------- B 拆容器：不能凭空变多 ----------
        int beforeB = glass();
        ca.tile.setBlock(Blocks.air);
        run(40);
        System.out.println("[CC] B 拆容器后: 玻璃=" + glass() + " 容量=" + cap());
        check("B 拆容器后物品不变多（" + beforeB + " → " + glass() + "）", glass() <= beforeB && glass() > 0);

        // ---------- C 存读档：一分不差 ----------
        clearArea();
        Building c1 = place(Blocks.coreShard, 60, 60, Team.sharded);
        run(10);
        c1.items.set(Items.metaglass, 3000);
        c1.items.set(Items.copper, 500);
        run(10);
        Building s1 = place(cont, 63, 60, Team.sharded);
        Building s2 = place(cont, 66, 60, Team.sharded);
        Building s3 = place(vault, 60, 63, Team.sharded);
        run(40);
        int beforeC = total(), capC = cap();
        System.out.println("[CC] C 存档前: 总量=" + beforeC + " 容量=" + capC
            + " 并进核心: s1=" + (s1.items == c1.items) + " s2=" + (s2.items == c1.items) + " s3=" + (s3.items == c1.items));
        Fi file = Core.files.absolute("/tmp/cl/cap-roundtrip.msav");
        SaveIO.save(file);
        SaveIO.load(file);
        run(60);
        System.out.println("[CC] C 读档后: 总量=" + total() + " 容量=" + cap() + " 玻璃=" + glass());
        check("C 读档后物品总量一分不差（" + beforeC + " → " + total() + "）", total() == beforeC);

        // ---------- D 一组仓库从核心上脱离：物品不能被"按组容量"截断删掉 ----------
        clearArea();
        Building c2 = place(Blocks.coreShard, 60, 60, Team.sharded);
        run(10);
        c2.items.set(Items.metaglass, 3000);
        run(10);
        Building d1 = place(cont, 63, 60, Team.sharded);   // 贴着核心
        Building d2 = place(cont, 65, 60, Team.sharded);   // 连着 d1
        Building d3 = place(cont, 67, 60, Team.sharded);   // 连着 d2
        run(40);
        int beforeD = total(), capD = cap();
        System.out.println("[CC] D 三容器并存前: 总量=" + beforeD + " 容量=" + capD
            + " 都在核心池里=" + (d1.items == c2.items) + "/" + (d2.items == c2.items) + "/" + (d3.items == c2.items));
        // 拆掉贴着核心的那台 → 另外两台从核心上脱离，变成独立组（容量只有 2×300）
        d1.tile.setBlock(Blocks.air);
        run(60);
        System.out.println("[CC] D 拆掉贴核心那台后: 总量=" + total() + " 核心容量=" + cap()
            + " d2池=" + d2.items.total() + " d3池=" + d3.items.total());
        check("D 仓库组脱离核心后物品不丢（" + beforeD + " → " + (total() + d2.items.total() + d3.items.total()) + "）",
            total() + d2.items.total() + d3.items.total() == beforeD);

        // ---------- E 仓库链（3 个串在一起 + 贴着核心）存读档：不能补满/翻倍 ----------
        clearArea();
        Building c3 = place(Blocks.coreShard, 60, 60, Team.sharded);
        run(10);
        c3.items.set(Items.metaglass, 4500 > c3.block.itemCapacity ? 3000 : 4500);
        c3.items.set(Items.copper, 400);
        run(10);
        Building e1 = place(cont, 63, 60, Team.sharded);
        Building e2 = place(cont, 65, 60, Team.sharded);
        Building e3 = place(cont, 67, 60, Team.sharded);
        run(40);
        int beforeE = total(), capE = cap();
        System.out.println("[CC] E 链存档前: 总量=" + beforeE + " 容量=" + capE
            + " 三台同一池=" + (e1.items == e2.items && e2.items == e3.items) + " 并进核心=" + (e1.items == c3.items));
        Fi fileE = Core.files.absolute("/tmp/cl/cap-chain.msav");
        SaveIO.save(fileE);
        SaveIO.load(fileE);
        run(60);
        System.out.println("[CC] E 链读档后: 总量=" + total() + " 容量=" + cap() + " 玻璃=" + glass());
        check("E 仓库链读档后总量不涨（" + beforeE + " → " + total() + "）", total() == beforeE);

        // ---------- G 容量只跟"核心 + 连通仓库"走，不跟库存走 ----------
        // 用户报的：次代核心 9000 + 一堆 300 的容器，面板上限却固定在 23074（= 某种物品的存量），
        // 拆容器也不掉。以前"容量至少要装得下现在这些存货"的地板写成 items.total()/单项最大值，
        // 这次的断言：每种物品**都超过**容量，上限依旧只能是"核心 + 容器"。
        clearArea();
        Building g0 = place(Blocks.coreShard, 60, 60, Team.sharded);
        run(10);
        int capG = cap(), types = 0;
        for(Item it : Vars.content.items()){ g0.items.set(it, capG + 700); types++; }
        int totalG = g0.items.total();
        System.out.println("[CC] G 每种都超容: 容量=" + capG + " 物品种类=" + types + " 单项=" + (capG + 700)
            + " 物品总和=" + totalG);
        // 放一台容器（会触发组合仓库重算）——容量只该多出容器的 300，不该跳到 items.total()
        Building g1 = place(cont, 63, 60, Team.sharded);
        run(60);
        int capG2 = cap();
        System.out.println("[CC] G 放容器重算后: 容量=" + capG2 + "（应为 " + (capG + cont.itemCapacity) + "）"
            + " (并进核心=" + (g1.items == g0.items) + ") 玻璃=" + g0.items.get(Items.metaglass)
            + " 铜=" + g0.items.get(Items.copper) + " 总和=" + g0.items.total());
        check("G 容量只按容量算、不看库存（" + capG + " → " + capG2 + "，物品总和 " + totalG + "）",
            capG2 == capG + cont.itemCapacity);
        check("G 超容的存量一份都没被删", g0.items.total() == totalG);
        try{ g1.tile.setBlock(Blocks.air); }catch(Throwable ignored){}
        run(60);
        System.out.println("[CC] G 拆掉容器后: 容量=" + cap() + "（应为 " + capG + "） 总和=" + g0.items.total());
        check("G 拆掉仓库容量掉回核心自身（" + cap() + "）", cap() == capG);
        check("G 拆掉仓库后物品一份没少（" + g0.items.total() + "/" + totalG + "）", g0.items.total() == totalG);

        // ---------- F 直接读用户的存档，看看读档前后数量 ----------
        try{
            Fi usr = Core.files.absolute(System.getProperty("user.home") + "/sd/a/sector-serpulo-175.msav");
            if(!usr.exists()) usr = Core.files.absolute(System.getProperty("user.home") + "/sd/save/sector-serpulo-175.msav");
            if(usr.exists()){
                SaveIO.load(usr);
                run(60);
                StringBuilder sb = new StringBuilder();
                Building cc = core();
                int containers = 0, containerCap = 0;
                java.util.IdentityHashMap<Building, Boolean> seen = new java.util.IdentityHashMap<>();
                for(Tile t : Vars.world.tiles){
                    if(t == null || !(t.build instanceof mindustry.world.blocks.storage.StorageBlock.StorageBuild sb2)
                        || t.build instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild) continue;
                    if(seen.put(t.build, Boolean.TRUE) != null) continue;
                    containers++;
                    containerCap += t.build.block.itemCapacity;
                }
                if(cc != null){
                    for(Item it : Vars.content.items()){
                        int a = cc.items.get(it);
                        if(a > 0) sb.append(it.name).append('=').append(a).append(' ');
                    }
                }
                System.out.println("[CC] F 用户存档 加载后: 核心=" + (cc == null ? "无" : cc.block.name)
                    + " 容量=" + cap() + "（核心静态 " + (cc == null ? -1 : cc.block.itemCapacity)
                    + " + " + containers + " 个仓库 " + containerCap + "）物品: " + sb);
                check("F 读档后容量 = 核心 + 连通仓库（" + cap() + "），不跟库存走",
                    cc != null && cap() == cc.block.itemCapacity + containerCap);
                check("F 读档后地图里的库存没被原版 sector info 回灌清掉（总量 " + (cc == null ? -1 : cc.items.total()) + "）",
                    cc != null && cc.items.total() > 0);
                int t1 = total();
                SaveIO.save(Core.files.absolute("/tmp/cl/user-roundtrip.msav"));
                SaveIO.load(Core.files.absolute("/tmp/cl/user-roundtrip.msav"));
                run(60);
                System.out.println("[CC] F 再存读一次: 总量=" + t1 + " → " + total() + " 容量=" + cap());
                check("F 用户存档再存读一次总量不涨（" + t1 + " → " + total() + "）", total() == t1);
            }else{
                System.out.println("[CC] F 找不到用户存档，跳过");
            }
        }catch(Throwable t){ System.out.println("[CC] F 用户存档处理失败: " + t); }

        System.out.println("[CC] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
