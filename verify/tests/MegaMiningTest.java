package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.Item; import mindustry.type.UnitType; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："挖矿单位合体后挖矿的时候没有挖矿光束，而且物品容量没了"。
 *
 * <p>两条根因：
 * <ul>
 * <li><b>没有挖矿光束</b>：巨兽是完全自定义绘制（{@code MegaUnitType.draw}），漏了原版
 *     {@code UnitType.draw} 开头的 {@code if(unit.mining()) drawMining(unit);}；而且巨兽类型是
 *     late 注册的，{@code init()} 里赋的 {@code mineBeamOffset} 停在
 *     {@code Float.NEGATIVE_INFINITY}、{@code load()} 里赋的激光贴图是 null —— 光束起点算成 -Inf，
 *     就算调了 drawMining 也画不出来。</li>
 * <li><b>物品容量没了</b>：{@code UnitType.itemCapacity} 默认是 -1。成员表一旦读不出来，
 *     派生类型就建不起来，{@code unit.type} 停在**占位类型**上 → 面板没容量、{@code acceptsItem}
 *     恒 false（挖不动也装不下）。现在改成用代表类型按"单成员构成"兜底推一个类型。</li>
 * </ul>
 *
 * <p>这个测试盯数值：合体后的物品容量/挖矿速率必须是成员累加、光束所需的字段必须可用；
 * 构成丢失时也必须保住这些（不能退回 -1 的占位类型）。
 */
public class MegaMiningTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_cj/data";
    static int pass = 0, fail = 0;
    static ClassLoader ml;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){}; Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MM] " + t); };
        new HeadlessApplication(new MegaMiningTest(), t -> t.printStackTrace());
    }

    static void check(String n, boolean ok){ System.out.println("[MM] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
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
        }catch(Throwable t){ System.out.println("[MM] 融合失败: " + t); return null; }
    }
    static int memberCount(Unit u){
        try{ return (Integer)u.getClass().getMethod("memberCount").invoke(u); }catch(Throwable t){ return -1; }
    }
    static void clearMembers(Unit mega){
        try{
            Object seq = mega.getClass().getMethod("members").invoke(mega);
            seq.getClass().getMethod("clear").invoke(seq);
        }catch(Throwable t){ System.out.println("[MM] 清成员失败: " + t); }
    }
    static void refresh(Unit u){
        try{ u.getClass().getMethod("refreshDerived").invoke(u); }catch(Throwable ignored){}
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
            for(int y = 25; y < 175; y++) for(int x = 10; x < 250; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
            }
            run(10);

            int ox = -1, oy = -1;
            outer:
            for(int y = 45; y < 140; y++){
                for(int x = 40; x < 200; x++){
                    boolean ok = true;
                    for(int dy = -2; dy <= 2 && ok; dy++) for(int dx = -3; dx <= 3; dx++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ System.out.println("[MM] 没找到陆地"); System.exit(3); }
            place(Blocks.coreShard, ox + 16, oy + 12, Team.sharded);
            run(10);
            Class<?> mergeCls = Class.forName("combine.units.UnitComboMerge", true, ml);

            Tile ore = null;
            for(int y = oy - 15; y < oy + 15 && ore == null; y++){
                for(int x = ox - 15; x < ox + 15; x++){
                    Tile t = Vars.world.tile(x, y);
                    if(t != null && t.block() == Blocks.air && t.drop() != null && t.floor() != null
                        && !t.floor().isLiquid && t.drop().hardness <= UnitTypes.poly.mineTier){ ore = t; break; }
                }
            }
            if(ore == null){ System.out.println("[MM] 附近没有矿"); System.exit(3); }

            // ============ A. 3 台 poly 合体：物品容量 / 挖矿速率 / 光束字段 ============
            Unit mega = merge(mergeCls, ox * 8f, oy * 8f, UnitTypes.poly, UnitTypes.poly, UnitTypes.poly);
            if(mega == null){ System.out.println("[MM] 融合失败"); System.exit(3); }
            run(10);
            int sumCap = UnitTypes.poly.itemCapacity * 3;
            System.out.println("[MM] A 巨兽: 成员=" + memberCount(mega)
                + " itemCapacity=" + mega.type.itemCapacity + "（期望 ≥ " + sumCap + "，单台 poly=" + UnitTypes.poly.itemCapacity + "）"
                + " mineSpeed=" + mega.type.mineSpeed + "（3 台 = " + (UnitTypes.poly.mineSpeed * 3) + "）"
                + " mineBeamOffset=" + mega.type.mineBeamOffset + " drawMineBeam=" + mega.type.drawMineBeam
                + " 激光贴图=" + (mega.type.mineLaserRegion == null ? "null（headless 无 atlas）" : "有"));
            check("A 物品容量按成员累加（" + mega.type.itemCapacity + " ≥ " + sumCap + "）",
                mega.type.itemCapacity >= sumCap);
            check("A 挖矿速率按成员累加", Math.abs(mega.type.mineSpeed - UnitTypes.poly.mineSpeed * 3f) < 0.001f);
            check("A 挖矿光束开关是开的", mega.type.drawMineBeam);
            check("A 光束起点已初始化（不再是 NEGATIVE_INFINITY）", mega.type.mineBeamOffset > 0f);
            // 【身上物品】原版 UnitType.draw 尾部按 drawItems 画"物品图标 + 底圈"，
            // 操控自己那只（unit.isLocal()）时还会画数量数字。巨兽是自定义绘制，
            // 这两段必须自己接上（用户报的"身上有物品却不显示有多少物品"）。
            check("A 身上物品的绘制开关是开的（drawItems）", mega.type.drawItems);
            check("A 物品图标偏移有效（itemOffsetY > 0）", mega.type.itemOffsetY > 0f);
            if(Vars.headless){
                System.out.println("[MM] A headless 没有 atlas，itemCircleRegion 为空属正常（真客户端再验）");
            }else{
                check("A 物品底圈贴图已借到（itemCircleRegion）", mega.type.itemCircleRegion != null);
            }

            // 真的挖起来：mineTile + 跑 300 tick，物品必须进背包（物品容量存在的实证）
            mega.set(ore.worldx(), ore.worldy() + 20f);
            mega.mineTile(ore);
            run(300);
            int mined = mega.stack.amount;
            System.out.println("[MM] A 挖矿 300 tick 挖到 " + mined + " 个（背包 " + mega.stack.amount + "/" + mega.itemCapacity() + "）");
            check("A 合并后的巨兽真的能挖到东西（容量没丢）", mined > 0);
            mega.mineTile(null);
            // itemTime 是双端都按 hasItem() 每帧收敛的本地视觉量；原来 = 0.01 以下时
            // drawItems 直接不画（图标/数字都不出现）
            run(60);
            System.out.println("[MM] A 有物品后 itemTime=" + mega.itemTime() + "（>0.9 才会画物品图标/数量）");
            check("A 有物品时 itemTime 收敛到 1（物品图标/数量会被画出来）", mega.itemTime() > 0.9f);

            // ============ B. 构成丢失（读快照打嗝的降级态）也要保住容量/挖速 ============
            Unit lost = merge(mergeCls, ox * 8f - 300f, oy * 8f, UnitTypes.poly, UnitTypes.poly);
            if(lost != null){
                run(5);
                clearMembers(lost);
                refresh(lost);
                run(3);
                System.out.println("[MM] B 成员丢了的巨兽: 成员=" + memberCount(lost)
                    + " itemCapacity=" + lost.type.itemCapacity + " mineSpeed=" + lost.type.mineSpeed
                    + " mineBeamOffset=" + lost.type.mineBeamOffset
                    + " 占位类型?=" + lost.type.name.equals("combine-mega-ground"));
                check("B 构成丢了也不能退回占位类型（那是 itemCapacity=-1 的壳）", lost.type.itemCapacity > 0);
                check("B 构成丢了也要保住挖矿速率", lost.type.mineSpeed > 0f);
                lost.remove();
                run(3);
            }else{
                check("B 融合成功", false);
            }

            System.out.println("[MM] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){
            t.printStackTrace();
            System.exit(2);
        }
    }
}
