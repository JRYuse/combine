package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.*; import arc.util.Log;
import arc.util.io.*; import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.Item;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.ItemModule;
import java.io.ByteArrayInputStream; import java.io.ByteArrayOutputStream;

/**
 * 用户报：**联机时**鼠标移动到建筑上查看物品（= 客户端请求一次 block snapshot）会把物资清空。
 *
 * 根因：服务端写快照走的是 `build.writeSync()` → `writeAll()` → `writeBase()`，
 * 而模组为了"防读档物品翻倍"，让**非组长在 writeBase 里写空模块**。于是客户端
 * （NetClient.blockSnapshot → `tile.build.readSync(...)`）读到跟随者的快照时，
 * 读进的是空模块；偏偏组合体成员**共用同一份 ItemModule**，
 * `ItemModule.read()` 开头就是 `Arrays.fill(items, 0)` —— 整组池子在客户端当场清空。
 *
 * 这个测试模拟那条链路：
 *   两台相邻的组合工厂成一租 → 灌物品 → 取**跟随者**的 writeSync 字节（= 服务端发的快照）
 *   → 在跟随者身上 readSync（= 客户端做的事）→ 断言整组池子一点没少。
 */
public class ClientSnapshotWipeTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[CSW] "+t); };
        new HeadlessApplication(new ClientSnapshotWipeTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[CSW] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }

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
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        // 找一台有物品容量的组合工厂
        Block crafter = null;
        for(Block b : Vars.content.blocks()){
            if(b.getClass().getName().equals("combine.production.CombinedCrafter") && b.size == 1
                && b.hasItems && !b.hasLiquids){ crafter = b; break; }
        }
        if(crafter == null){
            for(Block b : Vars.content.blocks())
                if(b.getClass().getName().equals("combine.production.CombinedCrafter") && b.size == 1 && b.hasItems){ crafter = b; break; }
        }
        if(crafter == null){ System.out.println("[CSW] 找不到组合工厂方块"); System.exit(3); }
        System.out.println("[CSW] 方块=" + crafter.name + " 单台容量=" + crafter.itemCapacity);

        Building a = place(crafter, 60, 60, Team.sharded);
        Building b2 = place(crafter, 61, 60, Team.sharded);
        run(30);
        if(a == null || b2 == null){ System.out.println("[CSW] 放置失败"); System.exit(3); }
        boolean samePool = a.items == b2.items;
        System.out.println("[CSW] 两台同池=" + samePool + " A模块=" + System.identityHashCode(a.items)
            + " B模块=" + System.identityHashCode(b2.items));
        check("两台相邻的组合工厂共用同一份物品模块", samePool);
        if(!samePool) System.exit(0);

        // 灌货：给池子塞满（按协议每台能装满一份，这里直接塞，模拟满池）
        a.items.add(Items.copper, 30);
        a.items.add(Items.lead, 20);
        run(3);
        int before = a.items.total();
        System.out.println("[CSW] 灌货后池子=" + before + "（" + a.items.get(Items.copper) + " 铜 / "
            + a.items.get(Items.lead) + " 铅）");

        // 挑一台"跟随者"（不是 pos 最小的那台）—— 服务端快照就从它身上取
        Building follower = a.pos() < b2.pos() ? b2 : a;
        Building leader = follower == a ? b2 : a;
        System.out.println("[CSW] 组长=" + leader.pos() + " 跟随者=" + follower.pos());

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Writes w = new Writes(new java.io.DataOutputStream(bos));
        follower.writeSync(w);
        byte[] snap = bos.toByteArray();
        System.out.println("[CSW] 快照字节=" + snap.length);

        // 客户端做的事：把快照读回自己身上（NetClient.blockSnapshot → readSync）
        follower.readSync(new Reads(new java.io.DataInputStream(new ByteArrayInputStream(snap))), follower.version());
        run(3);

        int after = leader.items.total();
        System.out.println("[CSW] readSync 之后（组长那边看到的）池子=" + after
            + "（" + leader.items.get(Items.copper) + " 铜 / " + leader.items.get(Items.lead) + " 铅）");
        check("客户端读跟随者快照后整组池子没被清空（" + before + " → " + after + "）", after == before);
        check("池子里还是原来的物品（铜 " + leader.items.get(Items.copper) + " / 铅 " + leader.items.get(Items.lead) + "）",
            leader.items.get(Items.copper) == 30 && leader.items.get(Items.lead) == 20);

        System.out.println("[CSW] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
