package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log; import arc.util.io.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.ai.types.CommandAI;
import java.io.ByteArrayInputStream; import java.io.ByteArrayOutputStream;

/**
 * 用户报：合体后的单位有时候会变成**无法控制的幽灵单位**——指挥模式下点不动任何指令、
 * 只能附身操控，有的还"重新读写后消失"。
 *
 * 原版指挥链路的三个必要条件（缺一个，表现就完全对得上"幽灵单位"）：
 *   1. `DesktopInput` 每帧 `selectedUnits.removeAll(u -> !u.allowCommand() ...)`——控制器不是
 *      CommandAI 的单位会被踢出框选集，于是命令面板上什么都点不了；
 *   2. `InputHandler.setUnitCommand` 只对 `controller() instanceof CommandAI` 且
 *      `unit.type.allowCommand(unit, command)`（= type.commands 含该指令）的单位生效，
 *      不满足时指令被静默丢弃（点了没反应）；
 *   3. 单位必须真的在 `Groups.unit` 里（客户端本地幽灵不在服务端，指令/存档都不认）。
 *
 * 这个测试把三条链路都在真游戏类里跑一遍（模组类只能反射引用，测试编译时只有游戏 jar）：
 *   A. 单机/主机侧刚融合出来的巨兽：控制器 / 指令表 / 是否可指挥；
 *   B. "客户端收到快照"（writeSync 字节 → 新建实体 readSync，NetClient.entitySnapshot 同款）；
 *   C. "存档读写"（write 字节 → EntityMapping 新建实体 read，看成员会不会丢）；
 *   D. 解体出来的成员：是不是真的回到世界、还能被指挥。
 */
public class MegaGhostTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[MG] "+t); };
        new HeadlessApplication(new MegaGhostTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MG] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }

    static Class<?> combineClass(String name){
        try{ return Class.forName(name, true, ml); }catch(Throwable t){ return null; }
    }
    static int memberCount(Unit u){
        try{ return (Integer)u.getClass().getMethod("memberCount").invoke(u); }catch(Throwable t){ return -1; }
    }
    static boolean isMega(Unit u){
        Class<?> c = combineClass("combine.units.mega.MegaUnitEntity");
        return c != null && c.isInstance(u);
    }
    static String ctrlName(Unit u){
        return u.controller() == null ? "null" : u.controller().getClass().getSimpleName();
    }
    /** 读镜像实体上的 comboId（0 = 未组合）。 */
    static double comboId(Unit u){
        try{ return (Double)u.getClass().getMethod("comboId").invoke(u); }catch(Throwable t){ return -1.0; }
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
        Class<?> damageCls = combineClass("combine.units.UnitComboDamage");
        if(mergeCls == null){ System.out.println("[MG] 找不到 combine.units.UnitComboMerge"); System.exit(3); }

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        // 找一块陆地（别让 dagger 落水里淹死，也别让解体找不到落脚点）
        int cx = -1, cy = -1;
        outer:
        for(int y=45;y<150;y++){
            for(int x=40;x<220;x++){
                boolean ok = true;
                for(int dy=-2;dy<=2 && ok;dy++) for(int dx=-3;dx<=3;dx++){
                    Tile t = Vars.world.tile(x+dx, y+dy);
                    if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                }
                if(ok){ cx = x; cy = y; break outer; }
            }
        }
        if(cx < 0){ System.out.println("[MG] 找不到陆地"); System.exit(3); }
        System.out.println("[MG] 陆地格=" + cx + "," + cy);
        // 队伍得有核心，不然会被当成出局队伍清单位；但核心是实心方块，绝不能压在单位脚下
        // （原版 UnitComp：站在实心块上又不 canBoost 的单位会被直接 kill）
        int coreX = cx + 20, coreY = cy + 12;
        mindustry.world.Build.beginPlace(null, Blocks.coreShard, Team.sharded, coreX, coreY, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(coreX, coreY), Blocks.coreShard, null, (byte)0, Team.sharded, null);
        run(10);
        System.out.println("[MG] 巨兽是不是可编组单位（用于确认 dagger 能合体）= "
            + (damageCls == null ? "?" : invoke(combineClass("combine.units.UnitComboDamage"), "groupable", new Class<?>[]{Unit.class}, null)));

        Unit a = UnitTypes.dagger.create(Team.sharded);
        a.set(cx*8f - 20f, cy*8f);
        a.add();
        Unit b = UnitTypes.dagger.create(Team.sharded);
        b.set(cx*8f + 20f, cy*8f);
        b.add();
        run(5);
        System.out.println("[MG] 融合前: a 控制器=" + ctrlName(a) + " 可指挥=" + a.isCommandable());

        Unit mega = (Unit)invoke(mergeCls, "merge", new Class<?>[]{Unit.class}, null, a);
        run(10);
        if(mega == null){ System.out.println("[MG] 融合失败（dagger 不可编组？）"); System.exit(3); }
        int sharded = 0;
        for(Unit u : Groups.unit) if(u.team() == Team.sharded) sharded++;
        System.out.println("[MG] 巨兽: type=" + mega.type.name + " 成员=" + memberCount(mega)
            + " 控制器=" + ctrlName(mega) + " at=" + (int)mega.x + "," + (int)mega.y
            + " isAdded=" + mega.isAdded() + " isValid=" + mega.isValid() + " team=" + mega.team().name
            + " | Groups.unit.size=" + Groups.unit.size() + " 其中同一队=" + sharded);

        Object comboCommand = mergeCls.getField("comboCommand").get(null);

        // ---- A. 主机侧刚融合出来的巨兽 ----
        check("融合后巨兽是巨兽实体", isMega(mega));
        check("融合后巨兽在 Groups.unit 里（不是本地幽灵）", Groups.unit.contains(u -> u == mega) && mega.isAdded());
        check("融合后巨兽的控制器是 CommandAI（" + ctrlName(mega) + "）", mega.controller() instanceof CommandAI);
        check("融合后巨兽 allowCommand=true（否则指挥模式每帧把它踢出选择集）", mega.allowCommand());
        check("融合后巨兽 isCommandable=true", mega.isCommandable());
        check("巨兽类型指令表里有 移动", mega.type.commands.contains(mindustry.ai.UnitCommand.moveCommand));
        check("巨兽类型指令表里有 组合", comboCommand != null && mega.type.commands.contains((mindustry.ai.UnitCommand)comboCommand));
        check("巨兽类型 allowCommand(移动)=true", mega.type.allowCommand(mega, mindustry.ai.UnitCommand.moveCommand));
        check("巨兽类型有姿态（命令面板的停止/停火等）", mega.type.stances.size > 0);
        boolean cmdOk = false;
        try{
            if(mega.controller() instanceof CommandAI cai && mega.type.allowCommand(mega, mindustry.ai.UnitCommand.moveCommand)){
                cai.command(mindustry.ai.UnitCommand.moveCommand);
                cmdOk = cai.hasCommand() || cai.currentCommand() != null;
            }
        }catch(Throwable t){ System.out.println("[MG] 指令失败: " + t); }
        check("给巨兽下 移动 指令生效（原版 setUnitCommand 的判定链）", cmdOk);

        // ---- B. 客户端快照 ----
        byte[] snap = writeBytes(mega, true);
        Unit client = readBack(mega, snap, true);
        System.out.println("[MG] 快照字节=" + snap.length + " 客户端实体=" + (client == null ? "null"
            : client.getClass().getSimpleName() + " type=" + client.type.name + " 成员=" + memberCount(client)
              + " 控制器=" + ctrlName(client)));
        if(client != null){
            check("客户端读快照后成员数一致", memberCount(client) == memberCount(mega));
            check("客户端读快照后是巨兽实体", client instanceof Syncc && isMega(client));
            check("客户端读快照后控制器是 CommandAI（" + ctrlName(client) + "）", client.controller() instanceof CommandAI);
            check("客户端读快照后 allowCommand=true", client.allowCommand());
            check("客户端读快照后指令表里有 移动", client.type.commands.contains(mindustry.ai.UnitCommand.moveCommand));
        }

        // ---- C. 存档读写 ----
        byte[] saved = writeBytes(mega, false);
        Unit loaded = readBack(mega, saved, false);
        System.out.println("[MG] 存档字节=" + saved.length + " 读回=" + (loaded == null ? "null"
            : loaded.getClass().getSimpleName() + " 成员=" + memberCount(loaded) + " 控制器=" + ctrlName(loaded)));
        if(loaded != null){
            check("存档读回后成员没丢（2 名）", memberCount(loaded) == 2);
            check("存档读回后是巨兽实体", isMega(loaded));
            check("存档读回后控制器是 CommandAI（" + ctrlName(loaded) + "）", loaded.controller() instanceof CommandAI);
        }

        // ---- D. 解体出来的成员 ----
        boolean splitOk = (Boolean)invoke(mergeCls, "split", new Class<?>[]{Unit.class}, null, mega);
        run(10);
        int alive = 0, commandable = 0, ghost = 0;
        for(Unit u : Groups.unit){
            if(u.team() == Team.sharded && u.type == UnitTypes.dagger){
                alive++;
                if(u.isCommandable()) commandable++;
                if(!u.isAdded()) ghost++;
            }
        }
        System.out.println("[MG] 解体后 dagger 存活=" + alive + " 可指挥=" + commandable + " 未入世界=" + ghost);
        check("解体成功", splitOk);
        check("解体后成员都回到世界（2 个 dagger）", alive == 2 && ghost == 0);
        check("解体后的成员都能被指挥", commandable == 2);

        // ---- E. 手动编组必须走服务器（联机时客户端本地改 comboId 会被下一份快照盖回去） ----
        Unit d1 = null, d2 = null;
        for(Unit u : Groups.unit){
            if(u.team() == Team.sharded && u.type == UnitTypes.dagger){
                if(d1 == null) d1 = u; else if(d2 == null) d2 = u;
            }
        }
        if(d1 != null && d2 != null){
            invoke(damageCls, "requestCombineWith", new Class<?>[]{Unit.class, Unit.class}, null, d1, d2);
            run(3);
            double g1 = comboId(d1), g2 = comboId(d2);
            System.out.println("[MG] 编组后 comboId: " + g1 + " / " + g2);
            check("调用编组入口后两单位同组（comboId 相同且非 0）", g1 != 0.0 && g1 == g2);
            invoke(damageCls, "requestUngroup", new Class<?>[]{Unit.class}, null, d1);
            run(3);
            System.out.println("[MG] 退出组合后 comboId: " + comboId(d1) + " / " + comboId(d2));
            check("退出的那个单位 comboId 清零、同组其他成员留在组里", comboId(d1) == 0.0 && comboId(d2) == g2);
            invoke(damageCls, "requestUngroup", new Class<?>[]{Unit.class}, null, d2);
            run(3);
            check("另一个也退出后组合空了", comboId(d2) == 0.0);
        }else{
            check("找不到两个 dagger 做编组测试", false);
        }

        System.out.println("[MG] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }

    static Object invoke(Class<?> c, String name, Class<?>[] sig, Object self, Object... args){
        try{
            var m = c.getMethod(name, sig);
            return m.invoke(self, args);
        }catch(Throwable t){ System.out.println("[MG] 调 " + name + " 失败: " + t); return null; }
    }

    static byte[] writeBytes(Unit u, boolean sync){
        try{
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
            Writes w = new Writes(dos);
            if(sync) u.writeSync(w); else u.write(w);
            dos.flush();
            return bos.toByteArray();
        }catch(Throwable t){ System.out.println("[MG] 写失败: " + t); return new byte[0]; }
    }

    /** 模拟对端：按实体类 id 新建实体 → readSync/read → add()（NetClient.entitySnapshot 同款）。 */
    static Unit readBack(Unit src, byte[] bytes, boolean sync){
        try{
            Unit u = (Unit)EntityMapping.map(src.classId()).get();
            u.id(src.id());
            Reads r = new Reads(new java.io.DataInputStream(new ByteArrayInputStream(bytes)));
            if(sync) u.readSync(r); else u.read(r);
            u.add();
            return u;
        }catch(Throwable t){ System.out.println("[MG] 读回失败: " + t); return null; }
    }
}
