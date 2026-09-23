package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import arc.util.io.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.Item;
import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import java.io.ByteArrayInputStream; import java.io.ByteArrayOutputStream; import java.io.DataInputStream; import java.io.DataOutputStream;

/**
 * 用户报：**客户端视角内炮台弹药时常归零然后恢复**（q1.txt 第 3 条的联机版）。
 *
 * 服务端的弹仓是"整组共享"的：`perItemCap()` = Σ 各成员的 maxAmmo（3 台就是单台的 3 倍），
 * 所以服务端某一发弹药的数量可以**超过单台 maxAmmo**。
 *
 * 但联机同步走的是原版 block snapshot：`writeSync()` = writeBase + `ItemTurretBuild.write()`，
 * 而原版 `ItemTurretBuild.read()` 里有一句
 * <pre>int itemAmount = Math.min(read.s(), maxAmmo);</pre>
 * `maxAmmo` 是**单台**上限（组合方块没抬过它），`read.s()` 还是个 **short**：
 *   · 组里囤到 90（3×30）→ 客户端读出来只有 30（被单台上限夹掉 2/3）；
 *   · 组里囤到 32768 以上 → short 溢出成负数 → 客户端 totalAmmo 变负 → 弹药条直接显示 0，
 *     等下一次快照/打掉几发又跳回去（用户看到的"时常归零然后恢复"）。
 *
 * 本测试照客户端那条链路取证：服务端炮塔组囤弹 → 取成员的 `writeSync` 字节（= 服务端发的快照）
 * → 在**另一台**炮塔上 `readSync`（= 客户端收到快照做的事）→ 客户端看到的数量必须和服务端一致。
 */
public class TurretClientAmmoTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.info.ordinal()) System.out.println("[TCA] "+t); };
        new HeadlessApplication(new TurretClientAmmoTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[TCA] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }

    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }

    /** 读炮塔里某一种弹药的数量（CombinedItemTurretBuild.amountOf(Item)）。 */
    static int ammoOf(Object o, Item item){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{
                    java.lang.reflect.Method m = c.getDeclaredMethod("amountOf", Item.class);
                    m.setAccessible(true);
                    return (Integer) m.invoke(o, item);
                }catch(NoSuchMethodException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return -1;
    }

    static int reflectInt(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ java.lang.reflect.Field f = c.getDeclaredField(name); f.setAccessible(true); return f.getInt(o); }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return -1;
    }

    /** 服务端发一包快照：`build.writeSync(...)`（NetServer.writeBlockSnapshots 用的就是这个）。 */
    static byte[] snapshot(Building b) throws Exception{
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.writeSync(new Writes(new DataOutputStream(bos)));
        return bos.toByteArray();
    }

    /** 客户端收一包快照：`tile.build.readSync(reads, tile.build.version())`。 */
    static void applySnapshot(Building b, byte[] bytes) throws Exception{
        b.readSync(new Reads(new DataInputStream(new ByteArrayInputStream(bytes))), b.version());
    }

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        try{ Vars.mods.eachClass(Mod::init); }catch(Throwable t){ System.out.println("[TCA] eachClass 抛了: " + t); }
        if(Vars.logic==null) Vars.logic=new Logic();
        if(Vars.netServer==null) Vars.netServer=new NetServer();
        if(Vars.netClient==null) Vars.netClient=new NetClient();

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);

        // 找一台"吃铜"的组合炮塔 + 一个组合容器 + 一个组合节点（和 TurretAmmoTest 同一套找法）
        Block turret = null, cont = null, nodeBlock = null;
        for(Block b : Vars.content.blocks()){
            if(b.getClass().getName().startsWith("combine.turret.CombinedItemTurret")
                && b instanceof ItemTurret it && it.ammoTypes != null && it.ammoTypes.containsKey(Items.copper) && turret == null) turret = b;
            if(b.name.equals("container")) cont = b;
            if(b.getClass().getName().equals("combine.net.ComboNode")) nodeBlock = b;
        }
        if(turret == null || cont == null || nodeBlock == null){
            System.out.println("[TCA] 缺方块（炮塔=" + turret + " 容器=" + cont + " 节点=" + nodeBlock + "）");
            System.exit(3);
        }
        System.out.println("[TCA] 炮塔=" + turret.name + " size=" + turret.size
            + " maxAmmo=" + ((ItemTurret) turret).maxAmmo + " 容器=" + cont.name);

        for(int y=40;y<140;y++) for(int x=20;x<240;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        int step = Math.max(turret.size, 1);
        // 用户场景：一个仓库 + 3 台炮塔（组总弹仓 = 3 × 单台上限），用组合节点接在一起
        Building store = place(cont, 60, 60, Team.sharded);
        Building t1 = place(turret, 74, 60, Team.sharded);
        Building t2 = place(turret, 74 + step, 60, Team.sharded);
        Building t3 = place(turret, 74 + step * 2, 60, Team.sharded);
        // 客户端的"另一台"（不挨着，自己一组）：把服务端成员的快照读进它，看客户端会看到什么
        Building twin = place(turret, 150, 120, Team.sharded);
        run(5);
        store.items.add(Items.copper, 2000);
        Building node = place(nodeBlock, 67, 60, Team.sharded);
        node.onConfigureBuildTapped(store);
        run(6);
        node.onConfigureBuildTapped(t1);
        run(900);

        int serverAmmo = reflectInt(t1, "totalAmmo");
        int serverCopper = ammoOf(t1, Items.copper);
        int singleMax = ((ItemTurret) turret).maxAmmo;
        float groupCap = (Float) t1.getClass().getMethod("perItemCap").invoke(t1);
        System.out.println("[TCA] 服务端: 铜=" + serverCopper + " totalAmmo=" + serverAmmo
            + "（单台上限=" + singleMax + " 组总上限=" + (int) groupCap + "）");
        check("服务端弹仓真的按台数囤过单台上限（前置条件）", serverAmmo > singleMax);

        applySnapshot(twin, snapshot(t1));
        int clientAmmo = reflectInt(twin, "totalAmmo");
        int clientCopper = ammoOf(twin, Items.copper);
        System.out.println("[TCA] 客户端读完快照: 铜=" + clientCopper + " totalAmmo=" + clientAmmo);
        check("客户端弹药总量必须和服务端一致（现在是 " + clientAmmo + " vs " + serverAmmo + "）",
            clientAmmo == serverAmmo);
        check("客户端每种弹药数量必须和服务端一致（铜 " + clientCopper + " vs " + serverCopper + "）",
            clientCopper == serverCopper);

        // 大基地的超额囤货：组总上限能到几万（用户那种几百台的大组合），而此时
        // 原版 write.s() 的 short 会溢出成负数 —— 客户端 totalAmmo 变负 = 弹药条归零。
        // 这里直接把弹仓数值拉到 40000（组内每台都是同一份共享数值）来验这条边界。
        fillAmmo(t1, Items.copper, 40000);
        fillAmmo(t2, Items.copper, 40000);
        fillAmmo(t3, Items.copper, 40000);
        run(2);
        int serverAmmo2 = reflectInt(t1, "totalAmmo");
        applySnapshot(twin, snapshot(t1));
        int clientAmmo2 = reflectInt(twin, "totalAmmo");
        System.out.println("[TCA] 大额（服务端 totalAmmo=" + serverAmmo2 + "，>32767 时 short 会溢出）→ 客户端=" + clientAmmo2);
        check("大额弹药同步后客户端不能是负数、也不能归零", clientAmmo2 > 0);
        check("大额弹药同步后两端总量一致（" + clientAmmo2 + " vs " + serverAmmo2 + "）", clientAmmo2 == serverAmmo2);

        System.out.println("[TCA] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }

    /** 把一台炮塔里某种弹药的数值直接写成 amount（大基地场景没有测试时间去一发一发喂）。 */
    static void fillAmmo(Building b, Item item, int amount){
        try{
            java.lang.reflect.Field af = null;
            Class<?> c = b.getClass();
            while(c != null){
                try{ af = c.getDeclaredField("ammo"); break; }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
            if(af == null) return;
            af.setAccessible(true);
            Object seq = af.get(b);
            if(!(seq instanceof arc.struct.Seq<?> s)) return;
            for(Object e : s){
                if(e == null) continue;
                java.lang.reflect.Field itemF = e.getClass().getDeclaredField("item");
                itemF.setAccessible(true);
                if(itemF.get(e) != item) continue;
                java.lang.reflect.Field amtF = e.getClass().getField("amount");
                amtF.setAccessible(true);
                amtF.setInt(e, amount);
                java.lang.reflect.Field totF = null;
                Class<?> tc = b.getClass();
                while(tc != null){
                    try{ totF = tc.getDeclaredField("totalAmmo"); break; }
                    catch(NoSuchFieldException ignored){ tc = tc.getSuperclass(); }
                }
                if(totF != null){ totF.setAccessible(true); totF.setInt(b, amount); }
            }
        }catch(Throwable t){ System.out.println("[TCA] fillAmmo 失败: " + t); }
    }
}
