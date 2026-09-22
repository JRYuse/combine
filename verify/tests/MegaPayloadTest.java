package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.UnitType; import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.blocks.payloads.*;

/**
 * 用户报："有时候组合巨兽单位的图标变了而且无法解体，而且不能进入载荷黑洞销毁"。
 *
 * <p>三条症状分别用真游戏类量一遍，看它们各自卡在哪：
 * <ul>
 * <li><b>图标</b>：{@code unit.icon()}（HUD/指挥面板用）拿的是代表成员的 uiIcon；
 *     成员表没了就会退回 {@code type.uiIcon}（占位类型的图标 = 注册时写死的 dagger）——
 *     这正是用户看到的"图标变了"。</li>
 * <li><b>解体</b>：{@code split} 要求成员表非空，而且每个成员都得找到合法落脚点；
 *     悬在大水面上时"一个都放不出去"就直接返回 false = 用户报的"无法解体"（这是之前故意做的
 *     "不放出去送死"兜底，但玩家会觉得卡死了）。</li>
 * <li><b>进载荷黑洞</b>：原版 {@code PayloadVoid} 只收**载荷**（被运输单位搬过来/被丢进来），
 *     单位自己"走进去"要看 {@code PayloadBlockBuild.canControlSelect} 和
 *     {@code PayloadComp.canPickup} 的条件（spawnedByCore / allowedInPayloads / isAI / 体型）。</li>
 * </ul>
 */
public class MegaPayloadTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_cj/data";
    static int pass = 0, fail = 0;
    static ClassLoader ml;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){}; Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MPL] " + t); };
        new HeadlessApplication(new MegaPayloadTest(), t -> t.printStackTrace());
    }

    static void check(String n, boolean ok){ System.out.println("[MPL] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i = 0; i < f; i++){ Time.delta = 1f; Vars.logic.update(); } }
    static Building place(Block b, int x, int y, Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte)0, team, null);
        Building bu = Vars.world.build(ax, ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu;
    }
    static Class<?> combineClass(String name){
        try{ return Class.forName(name, true, ml); }catch(Throwable t){ return null; }
    }
    static Unit merge(Class<?> mergeCls, float x, float y, UnitType... types){
        try{
            Seq<Unit> units = new Seq<>();
            for(int i = 0; i < types.length; i++){
                Unit u = types[i].create(Team.sharded);
                u.set(x + i * 20f, y);
                u.add();
                units.add(u);
            }
            run(10);
            return (Unit)mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, units);
        }catch(Throwable t){ System.out.println("[MPL] 融合失败: " + t); return null; }
    }
    static int memberCount(Unit u){
        try{ return (Integer)u.getClass().getMethod("memberCount").invoke(u); }catch(Throwable t){ return -1; }
    }
    static boolean split(Unit u, Class<?> mergeCls){
        try{ return (Boolean)mergeCls.getMethod("split", Unit.class).invoke(null, u); }catch(Throwable t){ return false; }
    }
    static void clearMembers(Unit mega){
        try{
            Object seq = mega.getClass().getMethod("members").invoke(mega);
            seq.getClass().getMethod("clear").invoke(seq);
        }catch(Throwable t){ System.out.println("[MPL] 清成员失败: " + t); }
    }
    static Object dominant(Unit mega){
        try{ return mega.getClass().getField("dominant").get(mega); }catch(Throwable t){ return null; }
    }
    static byte[] writeSync(Unit u){
        try{
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
            u.writeSync(new arc.util.io.Writes(dos));
            dos.flush();
            return bos.toByteArray();
        }catch(Throwable t){ System.out.println("[MPL] writeSync 失败: " + t); return new byte[0]; }
    }
    /** 按 NetClient 的路子：EntityMapping 新建实体 → readSync（含成员块）。 */
    static Unit readSyncBack(Unit src, byte[] bytes){
        try{
            Unit u = (Unit)EntityMapping.map(src.classId()).get();
            u.id(src.id() + 5000);
            u.readSync(new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))));
            return u;
        }catch(Throwable t){ System.out.println("[MPL] readSync 失败: " + t); return null; }
    }

    /** 一片纯水面（放"成员悬空卡住"的巨兽用）。 */
    static int[] openWater(int radius){
        for(int y = 60; y < 200; y++){
            for(int x = 60; x < 240; x++){
                boolean ok = true;
                for(int dy = -radius; dy <= radius && ok; dy++) for(int dx = -radius; dx <= radius; dx++){
                    Tile t = Vars.world.tile(x + dx, y + dy);
                    if(t == null || t.floor() == null || !t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                }
                if(ok) return new int[]{x, y};
            }
        }
        return null;
    }

    @Override public void init(){
        try{
            Core.settings.setDataDirectory(Core.files.local(dataDir));
            Vars.loadLocales = false; Vars.loadSettings(); Vars.headless = true; Vars.init();
            UI.loadColors(); Fonts.loadContentIconsHeadless();
            Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
            Vars.mods.eachClass(Mod::init);
            if(Vars.logic == null) Vars.logic = new Logic();
            if(Vars.netServer == null) Vars.netServer = new NetServer();
            if(Vars.netClient == null) Vars.netClient = new NetClient();
            ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();

            Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.waves = false;
            Vars.state.rules.canGameOver = false;
            Vars.logic.play();
            run(20);
            for(int y = 25; y < 200; y++) for(int x = 10; x < 250; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
            }
            run(10);

            int ox = -1, oy = -1;
            outer:
            for(int y = 45; y < 140; y++){
                for(int x = 40; x < 200; x++){
                    boolean ok = true;
                    for(int dy = -3; dy <= 3 && ok; dy++) for(int dx = -4; dx <= 4; dx++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ System.out.println("[MPL] 没找到陆地"); System.exit(3); }
            place(Blocks.coreShard, ox + 18, oy + 14, Team.sharded);
            run(10);
            Class<?> mergeCls = combineClass("combine.units.UnitComboMerge");
            if(mergeCls == null){ System.out.println("[MPL] 找不到 UnitComboMerge"); System.exit(3); }

            // ============ A. 正常巨兽：图标 / 解体 / 载荷 ============
            Unit mega = merge(mergeCls, ox * 8f, oy * 8f, UnitTypes.dagger, UnitTypes.fortress);
            if(mega == null){ System.out.println("[MPL] 融合失败"); System.exit(3); }
            run(10);
            System.out.println("[MPL] A 正常巨兽: 成员=" + memberCount(mega) + " dominant=" + dominant(mega)
                + "（dagger+fortress 并列，应取血量最大的 fortress）"
                + " type.allowedInPayloads=" + mega.type.allowedInPayloads
                + " spawnedByCore=" + mega.spawnedByCore + " isAI=" + mega.isAI()
                + " hitSize=" + (int)mega.hitSize());
            check("A 正常巨兽的代表类型 = 血量更大的 fortress（headless 没有 atlas，图标另在真客户端验）",
                dominant(mega) == UnitTypes.fortress);
            check("A 正常巨兽能解体", split(mega, mergeCls));

            // ============ D. 载荷路径：被大载具搬走 → 丢进载荷黑洞销毁 ============
            Unit boss = merge(mergeCls, ox * 8f + 600f, oy * 8f, UnitTypes.dagger, UnitTypes.fortress, UnitTypes.mace);
            Unit carrier = null;
            if(boss != null){
                run(5);
                // 挑运力最大的几台"能装单位"的原版载具试一遍
                Seq<UnitType> cand = new Seq<>();
                for(UnitType t : Vars.content.units()){
                    if(!t.pickupUnits || t.payloadCapacity < 100f) continue;
                    cand.add(t);
                }
                cand.sort(t -> -t.payloadCapacity);
                for(int i = 0; i < Math.min(6, cand.size); i++){
                    UnitType t = cand.get(i);
                    Unit c = t.create(Team.sharded);
                    c.set(ox * 8f + 600f, oy * 8f + 200f);
                    c.add();
                    run(5);
                    boolean can = ((Payloadc)c).canPickup(boss);
                    System.out.println("[MPL] D 载具 " + t.name + " 运力=" + (int)t.payloadCapacity
                        + " 巨兽hitSize²=" + (int)(boss.hitSize() * boss.hitSize()) + " 能装=" + can);
                    if(can){ carrier = c; break; }
                    c.remove();
                }
                check("D 有一只原版载具能装下这只巨兽（能装 = 能被搬进载荷黑洞）", carrier != null);
                if(carrier != null){
                    ((Payloadc)carrier).pickup(boss);
                    run(5);
                    boolean carried = !boss.isAdded() && ((Payloadc)carrier).hasPayload();
                    System.out.println("[MPL] D 载具装上了巨兽: 巨兽还在世界=" + boss.isAdded()
                        + " 载具载荷数=" + ((Payloadc)carrier).payloads().size);
                    check("D 载具真的把巨兽收进载荷", carried);

                    Building dev = place(Blocks.payloadVoid, ox + 12, oy + 6, Team.sharded);
                    run(5);
                    if(dev != null && dev instanceof mindustry.world.blocks.payloads.PayloadBlock.PayloadBlockBuild pb){
                        Payload up = ((Payloadc)carrier).payloads().first();
                        boolean accepted = pb.acceptPayload(null, up);
                        if(accepted) pb.handlePayload(null, up);
                        run(60);
                        System.out.println("[MPL] D 载荷黑洞 accept=" + accepted
                            + " 黑洞里还有载荷=" + (pb.getPayload() != null)
                            + " 巨兽还在世界=" + boss.isAdded());
                        check("D 载荷黑洞把巨兽销毁了", accepted && !boss.isAdded());
                    }else{
                        check("D 载荷黑洞摆出来了", false);
                    }
                }
            }else{
                check("D 三只巨兽融合成功", false);
            }

            // ============ B. 成员表丢了（客户端读快照失败那种降级状态）============
            Unit lost = merge(mergeCls, ox * 8f - 400f, oy * 8f, UnitTypes.dagger, UnitTypes.mace);
            if(lost != null){
                run(5);
                clearMembers(lost);
                try{ lost.getClass().getMethod("refreshDerived").invoke(lost); }catch(Throwable ignored){}
                run(5);
                System.out.println("[MPL] B 成员丢了的巨兽: dominant=" + dominant(lost)
                    + " 成员=" + memberCount(lost) + " hitSize=" + (int)lost.hitSize());
                check("B 成员丢了的巨兽不能再解体（这就是用户报的「无法解体」）", !split(lost, mergeCls));
                check("B 成员丢了也不能丢掉代表类型（图标/体型不能退回占位类型）", dominant(lost) != null);
                // 关键回归：走一遍"快照写出→对端读回"，成员表带上代表类型；
                // 即使对端把成员读丢了（缺模组/字节错位），图标也不能变成别的单位。
                byte[] snap = writeSync(lost);
                Unit copy = readSyncBack(lost, snap);
                clearMembers(copy);
                run(3);
                System.out.println("[MPL] B2 读回来的副本: dominant=" + dominant(copy) + " 成员=" + memberCount(copy));
                check("B2 快照里带着代表类型，成员读丢了图标也不变（" + dominant(copy) + "）", dominant(copy) != null);
                lost.remove();
                run(3);
            }

            // ============ C. 成员悬在大水面上（全部放不出去）============
            int[] water = openWater(4);
            if(water != null){
                Unit stuck = merge(mergeCls, water[0] * 8f, water[1] * 8f, UnitTypes.dagger, UnitTypes.mace);
                if(stuck != null){
                    run(5);
                    int before = memberCount(stuck);
                    boolean ok = split(stuck, mergeCls);
                    run(5);
                    System.out.println("[MPL] C 悬在水面上的巨兽: 成员=" + before + " → 解体返回=" + ok
                        + " 剩余成员=" + memberCount(stuck));
                    // 用户要的是"能把它弄掉"：要么解体成功，要么至少别卡成永久不可处理
                    check("C 悬在水面上的巨兽：不能无声无息地卡死（要么解体，要么给可达的兜底）",
                        ok || memberCount(stuck) < before || !stuck.isAdded());
                    if(stuck.isAdded()) stuck.remove();
                    run(3);
                }else{
                    System.out.println("[MPL] C 水面上融不出来（跳过）");
                }
            }

            System.out.println("[MPL] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){
            t.printStackTrace();
            System.exit(2);
        }
    }
}
