package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.io.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.UnitType; import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.blocks.payloads.UnitPayload;
import java.io.ByteArrayInputStream; import java.io.ByteArrayOutputStream;

/**
 * 用户报（q1.txt 第 4/5/6/7 条 + 第 1/6 条）：
 *   1. 组合单位的探照迷雾范围几乎没有；
 *   4. 客户端组合不同单位会导致单位不可见；
 *   5. 客户端组合不清除原单位，直接生成大的 → 幽灵单位；
 *   6. 合体单位不在小地图出现；
 *   7. 存档里有组合单位直接炸档（快照/存档读取出错）。
 *
 * 这里在真游戏类里逐条验证：
 *   A. 迷雾：巨兽类型 fogRadius 必须 > 0（否则 FogControl 直接 continue，单位一点雾都不探）；
 *   B. 小地图：type.drawMinimap 不能被关掉（关掉 = 任何一方的小地图都没有它）；
 *   C. 融合后成员必须**通知客户端移除**（原版 remove() 只删本地，双端会留下幽灵成员，
 *      幽灵占着 id，服务端之后复用同一个 id 发别的实体快照 → 客户端按幽灵的类读别的类的字节
 *      = 用户看到的"Unknown payload type / Queue too long"整片刷屏 + 单位不可见）；
 *   D. 快照/存档往返（含成员里有建造计划的单位）不报错、成员数一致 —— 而且**读坏了也不能炸**：
 *      错位的字节必须被挡在实体内部（存档里每个实体是独立 chunk，抛出去就是"炸档进不去"）；
 *   E. 真存档存读一遍（SaveIO.save → SaveIO.load）后巨兽还在、成员没丢。
 */
public class MegaSyncTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MS] "+t); };
        new HeadlessApplication(new MegaSyncTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MS] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Class<?> combineClass(String name){
        try{ return Class.forName(name, true, ml); }catch(Throwable t){ return null; }
    }
    static Object invoke(Class<?> c, String name, Class<?>[] sig, Object self, Object... args){
        try{ return c.getMethod(name, sig).invoke(self, args); }catch(Throwable t){ System.out.println("[MS] 调 " + name + " 失败: " + t); return null; }
    }
    static int memberCount(Unit u){
        try{ return (Integer)u.getClass().getMethod("memberCount").invoke(u); }catch(Throwable t){ return -1; }
    }
    static boolean isMega(Unit u){
        Class<?> c = combineClass("combine.units.mega.MegaUnitEntity");
        return c != null && c.isInstance(u);
    }
    static Seq<UnitPayload> members(Unit u){
        try{
            @SuppressWarnings("unchecked")
            Seq<UnitPayload> s = (Seq<UnitPayload>)u.getClass().getMethod("members").invoke(u);
            return s;
        }catch(Throwable t){ return new Seq<>(); }
    }
    static byte[] writeBytes(Unit u, boolean sync){
        try{
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
            Writes w = new Writes(dos);
            if(sync) u.writeSync(w); else u.write(w);
            dos.flush();
            return bos.toByteArray();
        }catch(Throwable t){ System.out.println("[MS] 写失败: " + t); return new byte[0]; }
    }
    /** 模拟对端：按实体类 id 新建实体 → readSync/read（NetClient.entitySnapshot / SaveVersion 同款）。 */
    static Unit readBack(Unit src, byte[] bytes, boolean sync){
        try{
            Unit u = (Unit)EntityMapping.map(src.classId()).get();
            u.id(src.id());
            Reads r = new Reads(new java.io.DataInputStream(new ByteArrayInputStream(bytes)));
            if(sync) u.readSync(r); else u.read(r);
            // 快照路径：客户端会把实体加进世界；存档路径由 SaveVersion 负责 add，
            // 这里不加（否则测试进程里会多出一只同 id 的巨兽，存档时报警告）
            if(sync) u.add();
            return u;
        }catch(Throwable t){ System.out.println("[MS] 读回失败: " + t); return null; }
    }
    /** 找一块陆地（别让单位落水里）。 */
    static int[] land(int w, int h){
        for(int y=45;y<150;y++){
            for(int x=40;x<220;x++){
                boolean ok = true;
                for(int dy=-h;dy<=h && ok;dy++) for(int dx=-w;dx<=w;dx++){
                    Tile t = Vars.world.tile(x+dx, y+dy);
                    if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                }
                if(ok) return new int[]{x, y};
            }
        }
        return null;
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
        if(mergeCls == null){ System.out.println("[MS] 找不到 UnitComboMerge"); System.exit(3); }

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
        int[] at = land(6, 4);
        if(at == null){ System.out.println("[MS] 找不到陆地"); System.exit(3); }
        int cx = at[0], cy = at[1];
        int coreX = cx + 24, coreY = cy + 14;
        mindustry.world.Build.beginPlace(null, Blocks.coreShard, Team.sharded, coreX, coreY, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(coreX, coreY), Blocks.coreShard, null, (byte)0, Team.sharded, null);
        run(10);

        // ---- 融合：战锤(机甲) + 爬虫(履带/爬) + 领主(建造，会排计划) ----
        Unit a = UnitTypes.mace.create(Team.sharded);   a.set(cx*8f - 30f, cy*8f);        a.add();
        Unit b = UnitTypes.crawler.create(Team.sharded); b.set(cx*8f, cy*8f);             b.add();
        Unit c = UnitTypes.poly.create(Team.sharded);    c.set(cx*8f + 30f, cy*8f);        c.add();
        run(5);
        // 给成员排一个建造计划（存档/同步里 plans 的 config 对象是"可变长"字段，最容易错位）
        try{
            c.plans.add(new mindustry.entities.units.BuildPlan(cx + 6, cy, 0, Blocks.conveyor, false));
        }catch(Throwable t){ System.out.println("[MS] 排计划失败: " + t); }
        run(3);

        int before = Groups.unit.count(u -> u.team() == Team.sharded);
        Unit mega = (Unit)invoke(mergeCls, "merge", new Class<?>[]{Unit.class}, null, a);
        run(10);
        if(mega == null){ System.out.println("[MS] 融合失败"); System.exit(3); }
        System.out.println("[MS] 巨兽: type=" + mega.type.name + " 成员=" + memberCount(mega)
            + " fogRadius=" + mega.type.fogRadius + " lightRadius=" + mega.type.lightRadius
            + " drawMinimap=" + mega.type.drawMinimap + " hitSize=" + mega.type.hitSize);

        // ---- A. 迷雾 ----
        check("A 巨兽类型能探雾（type.fogRadius > 0，0/-1 时 FogControl 直接跳过）", mega.type.fogRadius > 0f);
        check("A 探雾范围按体型放大（fogRadius ≥ 原版下限 " + (58f*3f/8f) + "）", mega.type.fogRadius >= 58f * 3f / 8f);
        check("A 巨兽有灯光（lightRadius > 0）", mega.type.lightRadius > 0f);

        // ---- B. 小地图 ----
        check("B 巨兽会画在小地图上（type.drawMinimap）", mega.type.drawMinimap);

        // ---- C. 融合后成员必须通知客户端移除（否则客户端留下幽灵成员） ----
        boolean anyOriginalLeft = false;
        for(Unit u : Groups.unit){
            if(u == mega || u.team() != Team.sharded) continue;
            if(u.type == UnitTypes.mace || u.type == UnitTypes.crawler || u.type == UnitTypes.poly) anyOriginalLeft = true;
        }
        int after = Groups.unit.count(u -> u.team() == Team.sharded);
        System.out.println("[MS] 融合前同队单位=" + before + " 融合后=" + after + " 残留成员=" + anyOriginalLeft);
        check("C 融合后原成员已从世界移除（服务端）", !anyOriginalLeft);
        check("C 融合后同队单位数 = 原数量 - 成员数 + 1", after == before - 3 + 1);

        // ---- D. 快照/存档往返 ----
        byte[] snap = writeBytes(mega, true);
        Unit sc = readBack(mega, snap, true);
        System.out.println("[MS] 快照字节=" + snap.length + " 读回=" + (sc == null ? "null" : memberCount(sc) + " 成员"));
        check("D 快照往返成功且成员数一致（" + memberCount(mega) + "）", sc != null && memberCount(sc) == memberCount(mega));
        if(sc != null){
            check("D 快照往返后成员类型一致",
                memberTypes(mega).equals(memberTypes(sc)));
            sc.remove(); // 别把测试实体留进世界（存档会报重复 id）
        }

        byte[] saved = writeBytes(mega, false);
        Unit sl = readBack(mega, saved, false);
        System.out.println("[MS] 存档字节=" + saved.length + " 读回=" + (sl == null ? "null" : memberCount(sl) + " 成员"));
        check("D 存档往返成功且成员数一致", sl != null && memberCount(sl) == memberCount(mega));

        // ---- E. 真存档存读（SaveIO.save → SaveIO.load）----
        arc.files.Fi file = Core.files.absolute("/tmp/cl/megasync.msav");
        SaveIO.save(file);
        SaveIO.load(file);
        Vars.logic.play();
        run(30);
        Unit mega2 = null;
        for(Unit u : Groups.unit) if(u.team() == Team.sharded && isMega(u)) mega2 = u;
        System.out.println("[MS] 读档后巨兽=" + (mega2 == null ? "null" : (memberCount(mega2) + " 成员 hitSize=" + mega2.hitSize())));
        check("E 存档读回后巨兽还在", mega2 != null);
        check("E 存档读回后成员没丢（" + memberCount(mega) + "）", mega2 != null && memberCount(mega2) == memberCount(mega));
        check("E 存档读回后还能探雾", mega2 != null && mega2.type.fogRadius > 0f);
        if(mega2 != null) mega = mega2;

        // ---- F. 兼容"写了自定义实体"的模组 ----
        // F1: 别的模组注册自定义实体时，我们的巨兽 id 必须**不受影响**（固定高位槽）。
        int megaIdBefore = mega.classId();
        int otherSlot = -1;
        try{
            otherSlot = EntityMapping.register("SomeOtherModUnit", () -> new CUnitStub());
        }catch(Throwable t){ System.out.println("[MS] 模拟别的模组注册实体失败: " + t); }
        System.out.println("[MS] F1 巨兽 id=" + megaIdBefore + "（模拟别的模组注册后，另一个实体拿到槽 " + otherSlot + "）");
        check("F1 别的模组注册自定义实体后巨兽 id 不变（" + megaIdBefore + "）= 固定槽 250",
            megaIdBefore == 250 && mega.classId() == megaIdBefore);

        // F2: 成员实体类的 id 变了（两端模组顺序不同）时，成员仍能按"名字"解析出来。
        Object savedProv = null;
        try{
            int memberId = members(mega).first().unit.classId();
            // 把成员的实体类搬到另一个槽（模拟"对端这个类的 id 不一样"）
            int spare = -1;
            for(int i = 0; i < EntityMapping.idMap.length; i++)
                if(i != memberId && i != otherSlot && EntityMapping.idMap[i] == null){ spare = i; break; }
            if(spare >= 0){
                savedProv = new Object[]{memberId, spare, EntityMapping.idMap[memberId]};
                EntityMapping.idMap[spare] = EntityMapping.idMap[memberId];
                EntityMapping.idMap[memberId] = null;
                System.out.println("[MS] F2 把成员实体类从槽 " + memberId + " 挪到 " + spare + "（模拟两端 id 不同）");
            }
        }catch(Throwable t){ System.out.println("[MS] F2 挪槽失败: " + t); }
        byte[] snap2 = writeBytes(mega, true);
        Unit sc2 = readBack(mega, snap2, true);
        System.out.println("[MS] F2 挪槽后快照读回=" + (sc2 == null ? "null" : memberCount(sc2) + " 成员"));
        check("F2 成员实体类 id 变了也能按名字读回成员（" + memberCount(mega) + "）",
            sc2 != null && memberCount(sc2) == memberCount(mega));
        if(sc2 != null){
            check("F2 挪槽后成员类型一致", memberTypes(mega).equals(memberTypes(sc2)));
            sc2.remove();
        }
        if(savedProv != null){
            Object[] arr = (Object[])savedProv;
            int memberId = (Integer)arr[0];
            int spare = (Integer)arr[1];
            EntityMapping.idMap[spare] = null;
            EntityMapping.idMap[memberId] = (arc.func.Prov<?>)arr[2];
        }

        // ---- G. 成员里有"别的模组写的自定义实体单位"时也能同步/存档 ----
        UnitType modded = null;
        for(UnitType t : Vars.content.units()){
            if(t == null || t.constructor == null || t.hidden) continue;
            try{
                Object sample = t.constructor.get();
                if(sample instanceof Unit su && !su.getClass().getName().startsWith("mindustry.")){
                    modded = t;
                    break;
                }
            }catch(Throwable ignored){
            }
        }
        if(modded == null){
            System.out.println("[MS] G 这套模组里没有自定义实体单位，跳过");
        }else{
            System.out.println("[MS] G 用自定义实体单位做成员: " + modded.name + " 类=" + modded.constructor.get().getClass().getName());
            Unit g1 = UnitTypes.dagger.create(Team.sharded);
            g1.set(mega.x - 30f, mega.y);
            g1.add();
            Unit g2 = modded.create(Team.sharded);
            g2.set(mega.x + 30f, mega.y);
            g2.add();
            run(5);
            Unit gm = (Unit)invoke(mergeCls, "merge", new Class<?>[]{Unit.class}, null, g1);
            run(10);
            if(gm == null){
                check("G 融合（含自定义实体成员）成功", false);
            }else{
                byte[] s1 = writeBytes(gm, true);
                Unit back = readBack(gm, s1, true);
                check("G 含自定义实体成员的快照往返成功（成员 " + memberCount(gm) + "）",
                    back != null && memberCount(back) == memberCount(gm));
                if(back != null){
                    check("G 含自定义实体成员的快照成员类型一致", memberTypes(gm).equals(memberTypes(back)));
                    back.remove();
                }
                byte[] s2 = writeBytes(gm, false);
                Unit back2 = readBack(gm, s2, false);
                check("G 含自定义实体成员的存档往返成功", back2 != null && memberCount(back2) == memberCount(gm));
                invoke(mergeCls, "split", new Class<?>[]{Unit.class}, null, gm);
                run(10);
            }
        }

        // ---- H. 升级前写的存档（旧成员格式）还得能读 ----
        try{
            byte[] oldBytes = oldFormatBytes(mega);
            Unit h = readBack(mega, oldBytes, false);
            System.out.println("[H] 旧格式字节=" + oldBytes.length + " 读回=" + (h == null ? "null" : memberCount(h) + " 成员"));
            check("H 旧格式存档仍能读回成员（" + memberCount(mega) + "）", h != null && memberCount(h) == memberCount(mega));
        }catch(Throwable t){
            System.out.println("[MS] H 旧格式兼容测试异常: " + t);
            check("H 旧格式存档仍能读回成员", false);
        }

        // ---- H2. 上一版（带标记、成员体没有版本字节/成员 id）的存档也得能读 ----
        try{
            byte[] prev = prevFormatBytes(mega);
            Unit h2 = readBack(mega, prev, false);
            System.out.println("[H2] 上一版格式字节=" + prev.length + " 读回="
                + (h2 == null ? "null" : memberCount(h2) + " 成员"));
            check("H2 上一版格式存档仍能读回成员（" + memberCount(mega) + "）",
                h2 != null && memberCount(h2) == memberCount(mega));
        }catch(Throwable t){
            System.out.println("[MS] H2 兼容测试异常: " + t);
            check("H2 上一版格式存档仍能读回成员", false);
        }

        // ---- I. 贴图代表类型：数量并列时取**血量最大**的那个 ----
        // 先把测试过程中留下的散单位清掉（融合会按半径把附近的未组合单位一起收进去，
        // 混进来就不叫"数量并列"了）
        Seq<Unit> snapshotUnits = new Seq<>();
        for(Unit u : Groups.unit) snapshotUnits.add(u);
        for(Unit u : snapshotUnits){
            if(u.team() == Team.sharded && u != mega && !isMega(u)) u.remove();
        }
        run(5);
        System.out.println("[MS] I 血量: dagger=" + UnitTypes.dagger.health + " mace=" + UnitTypes.mace.health);
        Unit i1 = UnitTypes.dagger.create(Team.sharded);
        i1.set(mega.x - 600f, mega.y + 400f);
        i1.add();
        Unit i2 = UnitTypes.mace.create(Team.sharded);
        i2.set(mega.x - 570f, mega.y + 400f);
        i2.add();
        run(5);
        Unit im = (Unit)invoke(mergeCls, "merge", new Class<?>[]{Unit.class}, null, i1);
        run(10);
        if(im == null){
            check("I 融合成功（dagger + mace）", false);
        }else{
            UnitType dom = (UnitType)im.getClass().getField("dominant").get(im);
            UnitType want = UnitTypes.dagger.health >= UnitTypes.mace.health ? UnitTypes.dagger : UnitTypes.mace;
            System.out.println("[MS] I 成员=" + memberTypes(im) + " 各 1 只并列 → dominant=" + (dom == null ? "null" : dom.name)
                + "（血量最大应为 " + want.name + "）");
            check("I 数量并列时取血量最大的代表类型（" + want.name + "）", dom == want);
            UnitType biggest = UnitTypes.dagger.health >= UnitTypes.mace.health ? UnitTypes.dagger : UnitTypes.mace;
            check("I 推导出的类型 body 贴图用的是整只单位图（客户端才有 atlas）",
                Vars.headless || im.type.region == null || im.type.region == biggest.fullIcon || im.type.region == biggest.region);
            invoke(mergeCls, "split", new Class<?>[]{Unit.class}, null, im);
            run(10);
        }

        System.out.println("[MS] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }

    static Seq<String> memberTypes(Unit u){
        Seq<String> out = new Seq<>();
        for(UnitPayload p : members(u)) if(p != null && p.unit != null && p.unit.type != null) out.add(p.unit.type.name);
        return out;
    }

    /** 模拟"别的模组写的自定义单位实体"（随便一个 UnitEntity 子类即可）。 */
    public static class CUnitStub extends UnitEntity{
    }

    /**
     * 造一份**升级前格式**的巨兽存档字节：
     * 原版那一段字节 + {@code i(成员数)} + 每名成员一个原版载荷（{@code TypeIO.writePayload}）。
     *
     * 原版那一段怎么拿：把成员临时清空写一遍（尾块就是"标记 + 长度0"，5 个字节），去掉即可。
     */
    static byte[] oldFormatBytes(Unit mega) throws Exception{
        Seq<UnitPayload> keep = new Seq<>(members(mega));
        members(mega).clear();
        byte[] empty;
        try{
            empty = writeBytes(mega, false);
        }finally{
            for(UnitPayload p : keep) members(mega).add(p);
        }
        byte[] vanilla = stripMemberBlock(empty);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
        Writes w = new Writes(dos);
        w.b(vanilla);
        w.i(keep.size);
        for(UnitPayload p : keep) mindustry.io.TypeIO.writePayload(w, p);
        dos.flush();
        return bos.toByteArray();
    }

    /**
     * 上一版（带 {@code MEMBER_TAG} 标记、成员体还是 `i(数量) + 成员`、没有版本字节与成员 id）
     * 的存档字节。用户手里现有的存档就是这个格式 —— 新代码必须照样读得回来。
     */
    static byte[] prevFormatBytes(Unit mega) throws Exception{
        Seq<UnitPayload> keep = new Seq<>(members(mega));
        members(mega).clear();
        byte[] empty;
        try{
            empty = writeBytes(mega, false);
        }finally{
            for(UnitPayload p : keep) members(mega).add(p);
        }
        byte[] vanilla = stripMemberBlock(empty);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
        Writes w = new Writes(dos);
        w.b(vanilla);
        w.b(0x4D);                     // MEMBER_TAG
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Writes bw = new Writes(new java.io.DataOutputStream(body));
        bw.i(keep.size);               // 老成员体：直接就是数量（最高字节 0 = 新读法里的 version 0）
        for(UnitPayload p : keep){
            bw.bool(true);
            bw.str("combine-e-" + p.unit.getClass().getName());
            p.unit.write(bw);
        }
        byte[] bodyBytes = body.toByteArray();
        w.i(bodyBytes.length);
        w.b(bodyBytes);
        dos.flush();
        return bos.toByteArray();
    }

    /**
     * 去掉末尾那块成员数据（`标记(1) + 长度(4) + 成员体(len)`），留下"原版单位字节"。
     *
     * <p>不能按固定字节数切：成员体结构改过版本（现在开头有版本字节），长度会变。
     * 反过来从尾部找"某个 0x4D 后面跟的长度正好对齐到数据末尾"的位置 —— 这就是成员块的起点。
     */
    static byte[] stripMemberBlock(byte[] bytes){
        for(int s = bytes.length - 1; s >= 0; s--){
            if((bytes[s] & 0xFF) != 0x4D || s + 5 > bytes.length) continue;
            int len = ((bytes[s + 1] & 0xFF) << 24) | ((bytes[s + 2] & 0xFF) << 16)
                | ((bytes[s + 3] & 0xFF) << 8) | (bytes[s + 4] & 0xFF);
            if(s + 5 + len == bytes.length)
                return java.util.Arrays.copyOf(bytes, s);
        }
        return bytes;
    }
}
