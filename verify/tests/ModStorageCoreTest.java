package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.Item;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.blocks.storage.*;

/**
 * 别的模组用 js/java 写的仓库（例如"更多实用设备"的 cargo：js 的 StorageBlock 子类，容量 5000）
 * 挨着核心时，必须**照样给核心扩容** —— 它的扩容是原版 CoreBuild 按"相邻 StorageBlock"算的，
 * 组合工厂重算核心容量时不能把这个值压低。
 */
public class ModStorageCoreTest implements ApplicationListener{
    static String dataDir="/tmp/mp_sf/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new ModStorageCoreTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MS] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Building core(){ for(Building b : Vars.state.teams.get(Team.sharded).cores) return b; return null; }
    static int cap(){ Building c = core(); return c instanceof CoreBlock.CoreBuild cb ? cb.storageCapacity : -1; }

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

        // 找"别的模组用 js/java 写的仓库"（不是原版、也不是 combine 替换出来的）
        StringBuilder all = new StringBuilder();
        for(Block b : Vars.content.blocks())
            if(b instanceof StorageBlock && !(b instanceof CoreBlock))
                all.append(b.name).append('[').append(b.getClass().getSimpleName()).append(']').append(' ');
        System.out.println("[MS] 所有仓库类方块: " + (all.length() > 400 ? all.substring(0, 400) + "..." : all));
        System.out.println("[MS] 模组: " + Vars.mods.list().size);
        Block modStore = null;
        StringBuilder cands = new StringBuilder();
        for(Block b : Vars.content.blocks()){
            if(!(b instanceof StorageBlock) || b instanceof CoreBlock) continue;
            String cn = b.getClass().getName();
            if(cn.startsWith("combine.") || cn.startsWith("mindustry.")) continue;
            if(!b.hasItems || b.itemCapacity < 100) continue;
            if(cands.length() < 200) cands.append(b.name).append('(').append(b.itemCapacity).append(") ");
            if(modStore == null) modStore = b;
        }
        System.out.println("[MS] 模组仓库候选: " + cands);
        if(modStore == null){ System.out.println("[MS] 这套数据里没有这种方块，跳过"); System.exit(0); }
        System.out.println("[MS] 用 " + modStore.name + "（" + modStore.getClass().getName() + " 容量 " + modStore.itemCapacity + "）");

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        for(int y=40;y<140;y++) for(int x=20;x<240;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        Building c = place(Blocks.coreShard, 60, 60, Team.sharded);
        run(20);
        c.items.set(Items.metaglass, c.block.itemCapacity);
        run(10);
        int cap0 = cap(), items0 = c.items.total();
        System.out.println("[MS] 只有核心: 容量=" + cap0 + " 物品总量=" + items0);

        int size = Math.max(c.block.size, 1);
        Building ms = place(modStore, 60 + size, 60, Team.sharded);   // 紧贴核心
        System.out.println("[MS] 刚放下：容量=" + cap() + "（原版此刻应已算上它）");
        run(2);
        System.out.println("[MS] 2 帧后：容量=" + cap());
        run(38);
        System.out.println("[MS] 核心旁边放" + modStore.name + "后: 容量=" + cap() + "（期望 ≥ " + (cap0 + modStore.itemCapacity) + "）物品总量=" + c.items.total());
        check("模组仓库挨着核心时照样给核心扩容", cap() >= cap0 + modStore.itemCapacity);
        check("扩容时不会把核心里的物品删掉", c.items.total() >= items0);

        System.out.println("[MS] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
