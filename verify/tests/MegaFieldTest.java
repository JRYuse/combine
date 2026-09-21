package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.type.UnitType; import mindustry.type.Item;

/**
 * 用户报：组合单位时，mace + oct 组合后"不绘制单位身体"、"力墙 bar 超上限了"。
 *
 * 根因两条：
 *  1) {@code flyingLayer} —— 巨兽类型是 late 注册、{@code UnitType.init()/load()} 从没跑过，
 *     {@code flyingLayer} 停在 -1。混合编组里有飞行成员时 elevation 会升到 1，绘制走
 *     "elevation &gt; 0.5 → flyingLayer" 分支，等于 {@code Draw.z(-1)}（比地板还低），整个单位被地形盖住。
 *     {@code clipSize = -1} 同理（视口裁剪的包围盒成了负尺寸）。
 *  2) 力场按成员各留一份 —— 巨兽的护盾池是全员之和（融合时 shield(Σ)），
 *     而"力墙条"的分母只是**单个**能力的上限，组里带两台力场单位就超 100%。
 *     现在合并成一份：上限/回复相加，半径取最大。
 */
public class MegaFieldTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new MegaFieldTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MF] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }

    static Building place(Block b, int x, int y, Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte)0, team, null);
        Building bu = Vars.world.build(ax, ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu;
    }

    static float fieldMax(Unit u){
        float sum = 0f;
        for(var a : u.abilities())
            if(a instanceof mindustry.entities.abilities.ForceFieldAbility ff) sum += ff.max;
        return sum;
    }
    static int fieldCount(Unit u){
        int n = 0;
        for(var a : u.abilities())
            if(a instanceof mindustry.entities.abilities.ForceFieldAbility) n++;
        return n;
    }
    static float memberFieldMax(UnitType t){
        float sum = 0f;
        for(var a : t.abilities)
            if(a instanceof mindustry.entities.abilities.ForceFieldAbility ff) sum += ff.max;
        return sum;
    }

    /** 反射调 combine 的 groupable（测试类编译时只有游戏 jar，不能直接引用模组类）。 */
    static boolean combineGroupable(Unit u){
        try{
            Class<?> c = Class.forName("combine.units.UnitComboDamage", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
            return (Boolean)c.getMethod("groupable", Unit.class).invoke(null, u);
        }catch(Throwable t){ return false; }
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
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(10);

        // 找一块陆地
        int ox = -1, oy = -1;
        outer:
        for(int y=45;y<150;y++){
            for(int x=40;x<220;x++){
                boolean ok = true;
                for(int dy=-1;dy<=1 && ok;dy++) for(int dx=-2;dx<=2;dx++){
                    Tile t = Vars.world.tile(x+dx, y+dy);
                    if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                }
                if(ok){ ox = x; oy = y; break outer; }
            }
        }
        if(ox < 0){ System.out.println("[MF] 没找到陆地"); System.exit(3); }

        // 队伍必须有核心，不然游戏会把队伍当出局、清掉单位
        Building core = place(Blocks.coreShard, ox + 20, oy + 12, Team.sharded);
        if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
        run(10);

        Unit mace = UnitTypes.mace.create(Team.sharded);
        mace.set(ox * 8f - 20f, oy * 8f);
        mace.add();
        Unit oct = UnitTypes.oct.create(Team.sharded);
        oct.set(ox * 8f + 30f, oy * 8f);
        oct.add();
        Unit oct2 = UnitTypes.oct.create(Team.sharded);
        oct2.set(ox * 8f + 70f, oy * 8f);
        oct2.add();
        run(30);
        System.out.println("[MF] 融合前: mace 血=" + mace.health() + " oct 血=" + oct.health() + " oct盾=" + oct.shield()
            + " oct力场上限=" + memberFieldMax(UnitTypes.oct) + " Groups.unit=" + Groups.unit.size());
        for(Unit u : Groups.unit)
            System.out.println("[MF]   单位 " + u.type.name + "@" + (int)u.x + "," + (int)u.y
                + " 有效=" + u.isValid() + " 已添加=" + u.isAdded() + " 可编组=" + combineGroupable(u)
                + " 血=" + u.health() + " Groups.unit=@" + Groups.unit.size());
        System.out.println("[MF]   mace 有效=" + mace.isValid() + "@" + (int)mace.x + "," + (int)mace.y
            + " oct 有效=" + oct.isValid() + "@" + (int)oct.x + "," + (int)oct.y
            + " oct2 有效=" + oct2.isValid() + "@" + (int)oct2.x + "," + (int)oct2.y);

        float maceHp = mace.health(), octHp = oct.health(), oct2Hp = oct2.health();
        float shieldBefore = oct.shield() + oct2.shield();
        Object merged = null;
        try{
            Class<?> c = Class.forName("combine.units.UnitComboMerge", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
            merged = c.getMethod("merge", Unit.class).invoke(null, mace);
        }catch(Throwable t){ t.printStackTrace(); }
        Unit mega = merged instanceof Unit u ? u : null;
        if(mega == null){ System.out.println("[MF] 融合失败"); System.exit(3); }
        run(5);

        float expectMax = memberFieldMax(UnitTypes.oct) * 2f; // 两台 oct
        System.out.println("[MF] 巨兽: type=" + mega.type.name + " 类=" + mega.type.getClass().getName()
            + " hitSize=" + mega.hitSize() + " 血=" + mega.health() + "/" + mega.maxHealth() + " 盾=" + mega.shield()
            + " 力场数=" + fieldCount(mega) + " 力场上限合计=" + fieldMax(mega)
            + " flyingLayer=" + mega.type.flyingLayer + " clipSize=" + mega.type.clipSize
            + " 挂座=" + (mega.mounts() == null ? -1 : mega.mounts().length));

        check("血上限 = 成员血量之和（" + mega.maxHealth() + " ≈ " + (maceHp + octHp + oct2Hp) + "）",
            Math.abs(mega.maxHealth() - (UnitTypes.mace.health + UnitTypes.oct.health * 2f)) < 1f);
        check("力场合并成 1 份（原先是每台成员一份）", fieldCount(mega) == 1);
        check("力场上限 = 成员上限之和（" + fieldMax(mega) + " = " + expectMax + "）", Math.abs(fieldMax(mega) - expectMax) < 0.01f);
        check("力墙 bar 不超上限（盾 " + mega.shield() + " ≤ " + fieldMax(mega) + "，盾是融合前成员之和 " + shieldBefore + "）",
            mega.shield() <= fieldMax(mega) + 0.01f);
        check("flyingLayer 有效（不是 late-init 留下的 -1）", mega.type.flyingLayer > 0f);
        check("clipSize 有效（不是 -1，视口裁剪才有正常包围盒）", mega.type.clipSize > 0f);
        check("有武器挂座（成员武器没被 setType 清掉）", mega.mounts() != null && mega.mounts().length > 0);

        // 引擎：late 注册导致 UnitType.init() 从没跑过，engines 原来恒为空 → 会飞的巨兽没有尾焰。
        // 比例取自"飞行成员里体型最大的那台"（这里是 oct：hitSize 66 / engineOffset 46 / engineSize 7.8），
        // 也就是 engineOffset = 综合hitSize × 46/66、engineSize = 综合hitSize × 7.8/66
        // —— 和身体贴图同一个缩放来源，画出来才配套（没参考时兜底用 flare 的 5.75/9、2.5/9）。
        int engines = mega.type.engines == null ? -1 : mega.type.engines.size;
        float eo = mega.type.engineOffset, es = mega.type.engineSize;
        float refOffset = UnitTypes.oct.engineOffset / UnitTypes.oct.hitSize;
        float refSize = UnitTypes.oct.engineSize / UnitTypes.oct.hitSize;
        System.out.println("[MF] 引擎: 个数=" + engines + " engineOffset=" + eo + " engineSize=" + es
            + " hitSize=" + mega.hitSize() + "（oct 比例 @" + refOffset + " / @" + refSize + "）");
        check("有飞行成员的巨兽有引擎（原来恒为 0）", engines > 0);
        check("居中一个引擎（同 flare / oct 的画法）", engines == 1);
        check("引擎位置随体型放大（engineOffset ≈ hitSize × 46/66 = " + (mega.hitSize() * refOffset) + "）",
            Math.abs(eo - mega.hitSize() * refOffset) < 0.01f);
        check("引擎大小随体型放大（engineSize ≈ hitSize × 7.8/66 = " + (mega.hitSize() * refSize) + "）",
            Math.abs(es - mega.hitSize() * refSize) < 0.01f);
        for(var e : mega.type.engines)
            System.out.println("[MF]   引擎 @" + e.x + "," + e.y + " 半径=" + e.radius + " 朝向=" + e.rotation);

        // 纯地面编组（两台没有飞行能力的单位）：不该有引擎、也不该升空
        Unit dagger = UnitTypes.dagger.create(Team.sharded);
        dagger.set(ox * 8f + 140f, oy * 8f);
        dagger.add();
        Unit mace2 = UnitTypes.mace.create(Team.sharded);
        mace2.set(ox * 8f + 160f, oy * 8f);
        mace2.add();
        run(20);
        Unit groundMega = null;
        try{
            Class<?> c = Class.forName("combine.units.UnitComboMerge", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
            Object m2 = c.getMethod("merge", Unit.class).invoke(null, dagger);
            if(m2 instanceof Unit u2) groundMega = u2;
        }catch(Throwable ignored){}
        if(groundMega != null){
            int ge = groundMega.type.engines == null ? -1 : groundMega.type.engines.size;
            System.out.println("[MF] 纯地面编组巨兽（dagger + 前面的地面单位）: 引擎=" + ge
                + " elevation=" + groundMega.elevation);
            check("不含飞行成员的巨兽不生成引擎", ge == 0);
        }

        // 成员指令必须继承：poly 是工程/采矿单位，原版 init() 会给它加
        // 自动重建 / 辅助建造 / 挖矿，合体后这些指令不能丢（用户报的）
        Unit poly = UnitTypes.poly.create(Team.sharded);
        poly.set(ox * 8f + 260f, oy * 8f);
        poly.add();
        Unit mace3 = UnitTypes.mace.create(Team.sharded);
        mace3.set(ox * 8f + 280f, oy * 8f);
        mace3.add();
        run(20);
        Unit polyMega = null;
        try{
            Class<?> c = Class.forName("combine.units.UnitComboMerge", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
            Object m3 = c.getMethod("merge", Unit.class).invoke(null, poly);
            if(m3 instanceof Unit u3) polyMega = u3;
        }catch(Throwable ignored){}
        if(polyMega != null){
            var cmds = polyMega.type.commands;
            boolean rebuild = cmds.contains(mindustry.ai.UnitCommand.rebuildCommand, true);
            boolean assist = cmds.contains(mindustry.ai.UnitCommand.assistCommand, true);
            boolean mine = cmds.contains(mindustry.ai.UnitCommand.mineCommand, true);
            // 命令面板读的是占位类型（content.unit(type.id)）的指令表，两边都要有
            var placeholderCmds = Vars.content.unit(polyMega.type.id).commands;
            boolean placeholderHas = placeholderCmds.contains(mindustry.ai.UnitCommand.rebuildCommand, true)
                && placeholderCmds.contains(mindustry.ai.UnitCommand.mineCommand, true);
            System.out.println("[MF] poly 合体后指令: " + cmds + "（占位类型指令: " + placeholderCmds + "）");
            check("合体后保留「自动重建」指令", rebuild);
            check("合体后保留「辅助建造」指令", assist);
            check("合体后保留「挖矿」指令", mine);
            check("命令面板用的占位类型也有这些指令（面板才会列出来）", placeholderHas);
        }

        // 速度/物品上限/建造速度都必须按【成员个数】聚合：
        // 两艘同型船合体原来会变成"一半速度"（tally 按类型遍历、求和却没乘成员数）
        Unit risso1 = UnitTypes.risso.create(Team.sharded);
        risso1.set(ox * 8f - 400f, oy * 8f);
        risso1.add();
        Unit risso2 = UnitTypes.risso.create(Team.sharded);
        risso2.set(ox * 8f - 380f, oy * 8f);
        risso2.add();
        run(20);
        Unit shipMega = null;
        try{
            Class<?> c = Class.forName("combine.units.UnitComboMerge", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
            Object m4 = c.getMethod("merge", Unit.class).invoke(null, risso1);
            if(m4 instanceof Unit u4) shipMega = u4;
        }catch(Throwable ignored){}
        if(shipMega != null){
            System.out.println("[MF] 两艘 risso 合体: speed=" + shipMega.type.speed + "（单台 " + UnitTypes.risso.speed
                + "，原来是它的一半） 物品上限=" + shipMega.type.itemCapacity + "（单台 " + UnitTypes.risso.itemCapacity + "）"
                + " hitSize=" + shipMega.hitSize());
            check("两艘同型船合体后速度 = 单台速度（原来砍半：" + (UnitTypes.risso.speed / 2f) + "）",
                Math.abs(shipMega.type.speed - UnitTypes.risso.speed) < 0.001f);
            check("物品上限按成员求和（" + shipMega.type.itemCapacity + " = 2 × " + UnitTypes.risso.itemCapacity + "）",
                shipMega.type.itemCapacity == UnitTypes.risso.itemCapacity * 2);

            // 【水阻】原版船（WaterMoveComp）把 floorSpeedMultiplier 整个替换成
            // (floor.shallow ? 1f : 1.3f)：水里没有水阻、深水还快 30%；
            // 巨兽继承的是普通 UnitEntity，不处理就按 UnitComp 算 —— 深水 0.2、浅水 0.5。
            Tile deep = null, shallow = null;
            for(Tile t : Vars.world.tiles){
                if(t == null || t.floor() == null) continue;
                if(deep == null && t.floor().isDeep() && t.floor().drownTime > 0f && t.block() == Blocks.air) deep = t;
                // 浅水：地形层带 shallow 标记（原版 ShallowLiquid 覆盖的浅水块）
                if(shallow == null && t.floor().shallow && t.block() == Blocks.air) shallow = t;
                if(deep != null && shallow != null) break;
            }
            Unit ref = UnitTypes.risso.create(Team.sharded);
            ref.add();
            if(deep != null){
                shipMega.set(deep.worldx(), deep.worldy());
                ref.set(deep.worldx(), deep.worldy());
                run(2);
                float megaMul = shipMega.floorSpeedMultiplier();
                float shipMul = ref.floorSpeedMultiplier();
                System.out.println("[MF] 深水地形系数: 船合体=" + megaMul + " 原版船=" + shipMul
                    + "（修前会按普通单位算成 " + (0.2f * shipMega.speedMultiplier()) + "）");
                check("船合体在深水里的地形系数和原版船一致（水阻已处理，修前是 0.2）",
                    Math.abs(megaMul - shipMul) < 0.001f);
                check("深水系数 = 1.3（原版 WaterMoveComp 的深水加成）", Math.abs(megaMul - 1.3f) < 0.02f);
            }
            if(shallow != null){
                shipMega.set(shallow.worldx(), shallow.worldy());
                ref.set(shallow.worldx(), shallow.worldy());
                run(2);
                float megaMul = shipMega.floorSpeedMultiplier(), shipMul = ref.floorSpeedMultiplier();
                System.out.println("[MF] 浅水地形系数: 船合体=" + megaMul + " 原版船=" + shipMul);
                check("船合体在浅水里的地形系数和原版船一致", Math.abs(megaMul - shipMul) < 0.001f);
                check("浅水系数 = 1.0（水里没有水阻，修前按普通单位是 0.5）", Math.abs(megaMul - 1.0f) < 0.02f);
            }
        }
        Unit risso3 = UnitTypes.risso.create(Team.sharded);
        risso3.set(ox * 8f - 500f, oy * 8f);
        risso3.add();
        Unit minke = UnitTypes.minke.create(Team.sharded);
        minke.set(ox * 8f - 480f, oy * 8f);
        minke.add();
        run(20);
        Unit mixedShip = null;
        try{
            Class<?> c = Class.forName("combine.units.UnitComboMerge", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
            Object m5 = c.getMethod("merge", Unit.class).invoke(null, risso3);
            if(m5 instanceof Unit u5) mixedShip = u5;
        }catch(Throwable ignored){}
        if(mixedShip != null){
            float expect = (UnitTypes.risso.speed + UnitTypes.minke.speed) / 2f;
            System.out.println("[MF] risso + minke 合体: speed=" + mixedShip.type.speed
                + " 期望（两台平均）=" + expect);
            check("不同型船合体速度 = 两台速度的平均值", Math.abs(mixedShip.type.speed - expect) < 0.001f);
        }
        // 两台 poly：建造速度要按成员翻倍（"多个工程单位造得更快"）
        Unit poly1 = UnitTypes.poly.create(Team.sharded);
        poly1.set(ox * 8f + 400f, oy * 8f);
        poly1.add();
        Unit poly2 = UnitTypes.poly.create(Team.sharded);
        poly2.set(ox * 8f + 420f, oy * 8f);
        poly2.add();
        run(20);
        Unit polyPair = null;
        try{
            Class<?> c = Class.forName("combine.units.UnitComboMerge", true, Vars.mods.getMod("combine").main.getClass().getClassLoader());
            Object m6 = c.getMethod("merge", Unit.class).invoke(null, poly1);
            if(m6 instanceof Unit u6) polyPair = u6;
        }catch(Throwable ignored){}
        if(polyPair != null)
            check("两台 poly 合体建造速度翻倍（" + polyPair.type.buildSpeed + " = 2 × " + UnitTypes.poly.buildSpeed + "）",
                Math.abs(polyPair.type.buildSpeed - UnitTypes.poly.buildSpeed * 2f) < 0.001f);

        // 命令面板按 unit.type.id → content.unit(id) 取图标；派生类型共用基础巨兽的占位 id，
        // 所以占位类型必须带"组合巨兽"这个名字（图标由客户端同步，见 compTypeFor）
        var byId = Vars.content.unit(mega.type.id);
        System.out.println("[MF] content.unit(type.id) = " + byId.name + " / " + byId.localizedName
            + "，是巨兽基础类型=" + byId.getClass().getName().startsWith("combine.units.mega"));
        check("按 type.id 查回来的类型就是命令面板会用的那个（基础巨兽类型）", byId.name.startsWith("combine-mega"));
        check("它的名字是「组合巨兽」（原先是 combine-mega-ground）", "组合巨兽".equals(byId.localizedName));

        // 混合编组里有飞行成员：升到飞行高度（这就是"身体被画到地板下面"的触发条件）
        run(60);
        System.out.println("[MF] 60 tick 后: elevation=" + mega.elevation + " 盾=" + mega.shield() + "/" + fieldMax(mega));
        check("有飞行成员时会升到飞行高度（elevation>0.5 → 走 flyingLayer）", mega.elevation > 0.5f);
        check("升空后力墙 bar 仍不超上限（" + mega.shield() + "/" + fieldMax(mega) + "）", mega.shield() <= fieldMax(mega) + 0.01f);

        System.out.println("[MF] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
