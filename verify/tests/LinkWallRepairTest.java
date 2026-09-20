package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.blocks.defense.MendProjector;

/**
 * 组合墙（LinkWall）的血池能**被修满**。
 *
 * 用户报："连接墙不管怎么修都是破损状态"。
 * 根因：治疗量先吃在这**一格**的血量上、并被这格的 maxHealth 截断，然后才摊回整组 ——
 * 血池越接近满，被截掉的部分越多（几何收敛），最后卡在比满血低一点的浮点定点上，
 * `damaged()` 永远为真 → 画面上永远有裂纹。
 *
 * 这里用**真的修复投影**（不是直接调 heal）来验：放 3 面铜墙成一组，打成残血，
 * 修复投影应该把整组修到满血（不再 "破损"）。
 */
public class LinkWallRepairTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new LinkWallRepairTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[LR] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Block find(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static int seqSize(Object b){
        try{
            Class<?> c = b.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField("seqSize"); f.setAccessible(true); return f.getInt(b); }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return -1; }
    static Object refl(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return null; }
    static String hp(Building b){ return b == null ? "null" : String.format(java.util.Locale.ROOT, "%.4f", b.health); }

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
        for(int y=30;y<170;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        // headless 里没有玩家 → LinkWall.groupAllowed() 恒 false（不组合）。
        // 造一个假玩家放进 Groups.player（原版每帧按 Groups.player 重建 team.data().players）。
        Player p = Player.create();
        p.team(Team.sharded);
        Groups.player.add(p);
        // 修复投影要电：给这个队伍开 cheat（等价于无限电力），免得再去搭电网
        Vars.state.rules.teams.get(Team.sharded).cheat = true;

        Block wall = find("copper-wall");
        Block mend = find("mend-projector");
        System.out.println("[LR] 墙=" + (wall==null?null:wall.name + "/" + wall.getClass().getName())
            + " 修复投影=" + (mend==null?null:mend.name + "/" + mend.getClass().getName()));
        if(wall == null || mend == null) System.exit(3);

        Building w1 = place(wall, 60, 60, Team.sharded);
        Building w2 = place(wall, 61, 60, Team.sharded);
        Building w3 = place(wall, 62, 60, Team.sharded);
        run(20);
        System.out.println("[LR] 初始: " + hp(w1) + " / " + hp(w2) + " / " + hp(w3) + " 组=" + seqSize(w1));
        check("三面墙合成一组", seqSize(w1) == 3);
        check("初始满血、不显示破损", !w1.damaged() && !w2.damaged() && !w3.damaged());

        w2.damage(600f);
        run(5);
        System.out.println("[LR] 打中间 600: " + hp(w1) + " / " + hp(w2) + " / " + hp(w3));
        check("伤害按整组血池分摊（三面都残血）", w1.damaged() && w2.damaged() && w3.damaged());
        check("三面残血比例一致", java.lang.Math.abs(w1.health - w2.health) < 0.01f
            && java.lang.Math.abs(w2.health - w3.health) < 0.01f);

        // 真的摆一台修复投影（贴着墙，效率靠 cheat 拉满）
        place(mend, 61, 56, Team.sharded);
        run(20);
        int repairedTick = -1;
        for(int t = 0; t < 900; t++){
            arc.util.Time.delta = 1f;
            Vars.logic.update();
            if(!w1.damaged() && !w2.damaged() && !w3.damaged()){
                repairedTick = t;
                break;
            }
        }
        System.out.println("[LR] 修复投影修 " + repairedTick + " tick 后: " + hp(w1) + " / " + hp(w2) + " / " + hp(w3)
            + " 破损=" + w1.damaged() + "/" + w2.damaged() + "/" + w3.damaged());
        check("修复投影能把整组修到**不再破损**（" + repairedTick + " tick）", repairedTick >= 0);
        check("修满后每面都是满血（health == maxHealth）",
            w1.health == w1.maxHealth && w2.health == w2.maxHealth && w3.health == w3.maxHealth);

        // 只修其中一面（模拟修复源只照到一格）：整组也应该跟着涨、最终能满
        w2.damage(300f);
        run(5);
        float before = w1.health;
        w1.heal(30f);
        run(2);
        System.out.println("[LR] 只治一面 30: 治疗前 " + before + " → 现在 " + hp(w1) + " / " + hp(w2) + " / " + hp(w3));
        check("治一面的治疗量会摊到整组（不是只涨那一格）", w2.health > 0.0f && Math.abs(w1.health - w2.health) < 0.01f);

        // ---------- 组合节点 / 连接器接起来的墙 = 同一组（跨距离也共享血量） ----------
        Block node = null, conn = null;
        for(Block b : Vars.content.blocks()){
            if(node == null && b.getClass().getName().equals("combine.net.ComboNode")) node = b;
            if(conn == null && b.getClass().getName().equals("combine.net.ComboConnector")) conn = b;
        }
        if(node != null && conn != null){
            for(int y=40;y<140;y++) for(int x=20;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            run(5);
            Building n1 = place(wall, 60, 80, Team.sharded);
            Building n2 = place(wall, 61, 80, Team.sharded);
            Building n3 = place(wall, 70, 80, Team.sharded);
            Building n4 = place(wall, 71, 80, Team.sharded);
            run(20);
            System.out.println("[LR] 节点前: 组1=" + seqSize(n1) + " 组2=" + seqSize(n3));
            check("节点前是两组", seqSize(n1) == 2 && seqSize(n3) == 2);
            Building nd = place(node, 65, 80, Team.sharded);
            run(10);
            check("节点放下后不自动连线（还是两组）", seqSize(n1) == 2 && seqSize(n3) == 2);
            nd.onConfigureBuildTapped(n1);
            run(6);
            check("只连一边时还是两组", seqSize(n1) == 2 && seqSize(n3) == 2);
            nd.onConfigureBuildTapped(n3);
            run(20);
            System.out.println("[LR] 节点连两边后: 组1=" + seqSize(n1) + " 组2=" + seqSize(n3));
            check("节点把两段墙接成一组（4 台）", seqSize(n1) == 4 && seqSize(n3) == 4);
            System.out.println("[LR] 节点 links=" + refl(nd, "links"));
            n3.damage(400f);
            run(5);
            System.out.println("[LR] 节点组掉血: " + hp(n1) + "/" + hp(n2) + "/" + hp(n3) + "/" + hp(n4));
            check("跨节点也共享血池（没被打的那段也掉血）", n1.damaged() && n4.damaged());
            nd.onConfigureBuildTapped(n3);   // 断开
            run(2);
            System.out.println("[LR] 断开后立刻: 节点 links=" + refl(nd, "links"));
            run(18);
            System.out.println("[LR] 节点断开后: 组1=" + seqSize(n1) + " 组2=" + seqSize(n3));
            check("节点断开后又分成两组", seqSize(n1) == 2 && seqSize(n3) == 2);

            // 连接器：贴着两段墙
            for(int y=40;y<140;y++) for(int x=20;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            run(5);
            Building c1 = place(wall, 60, 100, Team.sharded);
            Building c2 = place(wall, 61, 100, Team.sharded);
            Building c3 = place(wall, 63, 100, Team.sharded);
            Building c4 = place(wall, 64, 100, Team.sharded);
            run(10);
            check("连接器前是两组", seqSize(c1) == 2 && seqSize(c3) == 2);
            place(conn, 62, 100, Team.sharded);   // 夹在两段墙中间
            run(20);
            System.out.println("[LR] 连接器接上后: 组1=" + seqSize(c1) + " 组2=" + seqSize(c3));
            check("连接器把两段墙接成一组", seqSize(c1) == 4 && seqSize(c3) == 4);
            c3.damage(400f);
            run(5);
            System.out.println("[LR] 连接器组掉血: " + hp(c1) + "/" + hp(c2) + "/" + hp(c3) + "/" + hp(c4));
            check("跨连接器也共享血池", c1.damaged() && c4.damaged());
        }else{
            System.out.println("[LR] 缺组合节点/连接器，跳过跨距离分组");
        }

        System.out.println("[LR] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
