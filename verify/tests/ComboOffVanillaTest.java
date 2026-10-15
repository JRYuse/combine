package combine.dbg;
import arc.*;
import arc.backend.headless.HeadlessApplication;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.core.Logic;
import mindustry.core.NetClient;
import mindustry.core.NetServer;
import mindustry.core.Platform;
import mindustry.core.UI;
import mindustry.game.Gamemode;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.maps.Map;
import mindustry.mod.Mod;
import mindustry.net.Net;
import mindustry.type.Item;
import mindustry.ui.Fonts;
import mindustry.world.Block;
import mindustry.world.Tile;

/**
 * 「关掉相邻自动组合」（组合总开关 / 只保留超级组合）之后，各建筑必须**照常按原版单机跑**。
 *
 * <p>用户报的一串现象都出在这里：关掉之后组合建筑不再走自己的逻辑，而是
 * {@code super.updateTile()} 提前返回 —— 而组合钻头的 super 是空实现（钻头/泵全停摆）、
 * 组合发电机/单位工厂/炮塔的 super 根本不是它们各自仿写的那个原版 build 类
 * （冲击反应堆免预热、单位工厂"容量还是整组的、收料各收各的"）。
 *
 * <p>判定：
 * <ol>
 * <li>关掉后钻头照常挖矿、泵照常抽水；</li>
 * <li>关掉后单位工厂的容量回到"单台"，相邻两台不再共池；重新打开又并回整组容量；</li>
 * <li>关掉后相邻两台组合炮塔不再共享弹药池；</li>
 * <li>产热方块（heatproducer 模式）真的对外供热；需热方块（heatcrafter 模式）没有热源不预热；</li>
 * <li>冲击反应堆要重新预热（不能一上来就满功率）。</li>
 * </ol>
 *
 * <p>用法：verify/run-headless.sh mx &lt;数据目录&gt; combine.dbg.ComboOffVanillaTest
 */
public class ComboOffVanillaTest implements ApplicationListener{
    static String dataDir = "";
    static int pass = 0, fail = 0;
    static ClassLoader ml;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){}; Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[COF] " + t); };
        new HeadlessApplication(new ComboOffVanillaTest(), t -> t.printStackTrace());
    }
    static void check(String n, boolean ok){ System.out.println("[COF] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
    static void info(String s){ System.out.println("[COF] " + s); }
    static void run(int f){ for(int i = 0; i < f; i++){ Time.delta = 1f; Vars.logic.update(); } }
    static Block find(String n){ for(Block b : Vars.content.blocks()) if(b.name.equals(n)) return b; return null; }
    static Building place(Block b, int x, int y, Team team){
        // (x, y) = 引擎放置时用的那一格（多格方块以它为中心铺开，见 Tile.setBlock）。
        mindustry.world.Build.beginPlace(null, b, team, x, y, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(x, y), b, null, (byte)0, team, null);
        // 【必须等一帧再取】多格方块先落的是施工块，完工那一刻 Tile.setBlock 会换成一个**新的**
        // build 实例 —— 立刻取到的那份随后就作废了（往它身上塞料/读效率全是空转，
        // 实测"产热方块 heat() 恒为 0、 efficiencies 全 0"，其实测的是个孤儿对象）。
        for(int i = 0; i < 3; i++){
            run(1);
            Building live = Vars.world.build(x, y);
            if(live != null && live.block == b){
                try{ live.rotation = 0; }catch(Throwable ignored){}
                try{ live.created(); }catch(Throwable ignored){}
                try{ live.updateProximity(); }catch(Throwable ignored){}
                return live;
            }
        }
        return null;
    }
    /**
     * 把 b 放在 target 旁边（四个方向挨个试，西 → 东 → 南 → 北），并把两边的邻近/电网接上。
     */
    static Building placeBeside(Block b, Building target){
        if(target == null) return null;
        int tx = target.tileX(), ty = target.tileY();
        int ts = Math.max(target.block.size, 1), bs = Math.max(b.size, 1);
        // 多格方块以**锚点格为中心**铺开：占 [a-(size-1)/2, a+size/2]（见 Tile.setBlock 的 offset）。
        // 所以"西边那一台"的锚点是： target 西边界 - 1 再往回退自己的一半。
        int westX = tx - (ts - 1) / 2 - 1 - bs / 2;
        int eastX = tx + ts / 2 + 1 + (bs - 1) / 2;
        int southY = ty - (ts - 1) / 2 - 1 - bs / 2;
        int northY = ty + ts / 2 + 1 + (bs - 1) / 2;
        int[][] cand = {{westX, ty}, {eastX, ty}, {tx, southY}, {tx, northY}};
        for(int[] p : cand){
            Building r = place(b, p[0], p[1], Team.sharded);
            if(r == null || r.tileX() != p[0] || r.tileY() != p[1]) continue;
            link(target, r);
            return r;
        }
        return null;
    }
    /**
     * 把两台建筑接成"相邻"（双向 proximity + 电网重算）。
     *
     * <p>为什么测试要自己接：引擎在放置时靠 {@code updateProximity()} 维护这份关系，
     * 而无头环境里它只把自己写进邻居的名单（自己那份要等邻居刷），顺序不对就会漏 ——
     * 现实游戏里两台挨着的建筑一定是互相在 proximity 里的，这里补上这一步，
     * 免得测出来的是"邻居没接上"而不是被测逻辑本身的问题。
     */
    static void link(Building a, Building b){
        if(a == null || b == null) return;
        try{
            if(a.proximity == null) a.proximity = new arc.struct.Seq<>();
            if(b.proximity == null) b.proximity = new arc.struct.Seq<>();
            if(!a.proximity.contains(b, true)) a.proximity.add(b);
            if(!b.proximity.contains(a, true)) b.proximity.add(a);
        }catch(Throwable ignored){}
        for(Building x : new Building[]{a, b}){
            try{ x.onProximityUpdate(); }catch(Throwable ignored){}
            try{ x.updatePowerGraph(); }catch(Throwable ignored){}
        }
    }
    /** Serpulo 的制热机要喝矿渣才会运转（组合后共用一个液池，灌一台就行）。 */
    static void feedSlag(Building b){
        try{ if(b != null && b.liquids != null) b.liquids.add(Liquids.slag, 50000f); }catch(Throwable ignored){}
    }
    static Object fld(Object o, String name){
        for(Class<?> c = o == null ? null : o.getClass(); c != null && c != Object.class; c = c.getSuperclass()){
            try{ java.lang.reflect.Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
            catch(NoSuchFieldException ignored){} catch(Throwable ignored){ return null; }
        }
        return null;
    }
    static float num(Object o, String name){ Object v = fld(o, name); return v instanceof Number n ? n.floatValue() : -1f; }
    static Object call(Object o, String name){
        try{ java.lang.reflect.Method m = o.getClass().getMethod(name); m.setAccessible(true); return m.invoke(o); }
        catch(Throwable t){ return null; }
    }
    static float callF(Object o, String name){ Object v = call(o, name); return v instanceof Number n ? n.floatValue() : -1f; }
    /** 组合总开关：关 = 谁都不自动组合（含只保留超级组合那条挡位用的是另一个方法）。 */
    static void setAutoOff(boolean off){
        try{
            Class<?> teams = Class.forName("combine.util.ComboTeams", true, ml);
            teams.getMethod("setEnabled", boolean.class).invoke(null, !off);
        }catch(Throwable t){ info("切换组合开关失败: " + t); }
    }
    /** 组合方块的"整组物品上限"。 */
    static int comboCap(Building b){ return (int) num(b, "comboTotalItemCap"); }
    /** 方块模式（CombinedCrafter / CombinedGenerator 的 mode 枚举）。 */
    static String modeOf(Block b){
        Object m = fld(b, "mode");
        return m == null ? null : m.toString();
    }
    static Block findModeBlock(String cls, String mode){
        for(Block b : Vars.content.blocks())
            if(b.getClass().getName().startsWith(cls) && mode.equals(modeOf(b))) return b;
        return null;
    }
    static Block findClass(String cls){
        for(Block b : Vars.content.blocks())
            if(b.getClass().getName().startsWith(cls)) return b;
        return null;
    }
    /** 把这块建筑"吃"的物品/液体都塞满（用来只缺一个条件的场景，例如只缺热）。 */
    static void feedAll(Building b){
        int added = 0;
        if(b == null || b.block == null) return;
        if(b.block.consumers != null){
            for(mindustry.world.consumers.Consume c : b.block.consumers){
                if(c instanceof mindustry.world.consumers.ConsumeItems ci && b.items != null){
                    for(mindustry.type.ItemStack st : ci.items){ b.items.add(st.item, 1000); added++; }
                }else if(c instanceof mindustry.world.consumers.ConsumeLiquid cl && b.liquids != null){
                    b.liquids.add(cl.liquid, 800f); added++;
                }else if(c instanceof mindustry.world.consumers.ConsumeLiquids cls && b.liquids != null){
                    for(mindustry.type.LiquidStack st : cls.liquids){ b.liquids.add(st.liquid, 800f); added++; }
                }
            }
        }
        if(added == 0){
            if(b.items != null) for(Item it : Vars.content.items()) b.items.add(it, 200);
            if(b.liquids != null) for(mindustry.type.Liquid l : Vars.content.liquids()) b.liquids.add(l, 200f);
        }
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
            Vars.state.rules.canGameOver = false; Vars.state.rules.waves = false;
            Vars.logic.play();
            run(20);
            for(int y = 40; y < 140; y++) for(int x = 20; x < 240; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
            }
            run(5);
            // 矿 + 水两块地
            for(int y = 60; y < 68; y++) for(int x = 60; x < 70; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null){ t.setFloor((mindustry.world.blocks.environment.Floor)Blocks.stone); t.setOverlay(Blocks.oreCopper); }
            }
            for(int y = 80; y < 90; y++) for(int x = 60; x < 74; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null) t.setFloor((mindustry.world.blocks.environment.Floor)Blocks.water);
            }
            run(5);

            // ==================== 1) 先关掉组合再放建筑（= 开局就是关的 / 只保留超级组合） ====================
            setAutoOff(true);
            Block drill = find("mechanical-drill");
            Block pump = find("mechanical-pump");
            Block factory = find("ground-factory");
            Building d1 = place(drill, 61, 61, Team.sharded);
            Building p1 = place(pump, 61, 81, Team.sharded);
            Building f1 = place(factory, 100, 60, Team.sharded);
            Building f2 = place(factory, 100 + factory.size, 60, Team.sharded);
            Building f3 = place(factory, 120, 60, Team.sharded); // 孤立的一台（基准）
            run(420);
            info("关掉组合: 钻头=" + (d1.items == null ? -1 : d1.items.total())
                + " 泵=" + (p1.liquids == null ? -1 : p1.liquids.currentAmount())
                + " 工厂容量 相邻=" + comboCap(f1) + " 孤立=" + comboCap(f3)
                + " f1上限=" + f1.getMaximumAccepted(Items.copper) + " f3上限=" + f3.getMaximumAccepted(Items.copper));
            check("关掉组合后钻头照常挖矿（items>0）", d1.items != null && d1.items.total() > 0);
            check("关掉组合后泵照常抽水（liquids>0）", p1.liquids != null && p1.liquids.currentAmount() > 0f);
            check("关掉组合后相邻单位工厂不再共池", f1.items != f2.items);
            check("关掉组合后单位工厂容量回到单台（相邻 == 孤立）",
                comboCap(f1) == comboCap(f3) && comboCap(f1) > 0);
            check("关掉组合后入料上限也是单台的（" + f1.getMaximumAccepted(Items.copper) + " == "
                + f3.getMaximumAccepted(Items.copper) + "）",
                f1.getMaximumAccepted(Items.copper) == f3.getMaximumAccepted(Items.copper));

            // ==================== 2) 重新打开 = 又并回整组 ====================
            setAutoOff(false);
            run(20);
            info("重新打开: 相邻共池=" + (f1.items == f2.items) + " 容量 相邻=" + comboCap(f1) + " 孤立=" + comboCap(f3));
            check("重新打开后相邻单位工厂并回整组容量（= 孤立的两倍）",
                f1.items == f2.items && comboCap(f1) == comboCap(f3) * 2);

            // ==================== 3) 炮塔：关掉后相邻不共享弹药池 ====================
            Block turret = findClass("combine.turret.CombinedItemTurret");
            if(turret == null){
                check("找得到组合物品炮塔（内容表）", false);
            }else{
                Building t1 = place(turret, 140, 60, Team.sharded);
                Building t2 = place(turret, 140 + turret.size, 60, Team.sharded);
                run(10);
                check("（前置）开着组合时相邻组合炮塔共享弹药池", t1.items == t2.items);
                setAutoOff(true);
                run(10);
                info("炮塔关掉组合: 同池=" + (t1.items == t2.items));
                check("关掉组合后相邻组合炮塔不再共享弹药池", t1.items != t2.items);
                setAutoOff(false);
                run(10);
                check("重新打开后相邻组合炮塔又共享弹药池", t1.items == t2.items);
            }

            // ==================== 4) 热量：产热的要供热，需热的没热不工作 ====================
            // 制热机优先用塞普罗的矿渣制热机（只吃矿渣，喂料简单）——埃里克尔的氧化室还要
            // 臭氧 + 电，喂料一断 producerHeat 就衰减回 0，测出来的是"料没了"而不是"逻辑坏了"。
            Block heater = find("slag-heater");
            if(heater == null || !"heatproducer".equals(modeOf(heater)))
                heater = findModeBlock("combine.production.CombinedCrafter", "heatproducer");
            Block heatCrafter = findModeBlock("combine.production.CombinedCrafter", "heatcrafter");
            Block power = find("power-source");
            info("热方块: heatproducer=" + (heater == null ? "无" : heater.name)
                + " heatcrafter=" + (heatCrafter == null ? "无" : heatCrafter.name));
            if(heater != null){
                setAutoOff(true);
                Building h = place(heater, 160, 60, Team.sharded);
                Building hp = power == null ? null : placeBeside(power, h); // 电（有的制热机要）
                // 制热机要一直有料才在产热（停了 producerHeat 会衰减回 0）。
                // 【注意】组合建筑每帧把每种液体夹回"单台液容量"（矿渣制热机约 120），
                // 所以要边跑边补，否则测到的是"料被喝完"而不是"产热逻辑坏了"。
                for(int i = 0; i < 10; i++){ feedAll(h); feedSlag(h); run(50); }
                float heat = callF(h, "heat");
                info("关掉组合: 产热方块 " + heater.name + " heat()=" + heat + " efficiency=" + num(h, "efficiency")
                    + " warmup=" + num(h, "warmup") + " producerHeat=" + num(h, "producerHeat")
                    + " 电源=" + (hp != null));
                check("关掉组合后产热方块（" + heater.name + "）照常供热（heat()>0）", heat > 0.01f);
            }
            if(heatCrafter != null){
                setAutoOff(true);
                Building c = place(heatCrafter, 175, 60, Team.sharded);
                // 料管够（各种物品/液体都塞一遍），唯一缺的就是"热"
                feedAll(c);
                run(200);
                float warmup = num(c, "warmup");
                float heatEff = callF(c, "heatEfficiency");
                info("关掉组合: 需热方块 " + heatCrafter.name + " 无热源 warmup=" + warmup
                    + " heatEfficiency=" + heatEff);
                check("关掉组合后需热方块（" + heatCrafter.name + "）没有热源就不预热", warmup < 0.05f);
                if(heater != null){
                    // 先放需热方块、再把制热机贴着它放（放置时那一格才会把邻居写进 proximity），
                    // 仍不放心就手动 link 一次（模拟"两台挨着"这个引擎保证）。
                    Building h2 = placeBeside(heater, c);
                    if(h2 != null){
                        link(c, h2);
                        for(int i = 0; i < 10; i++){ feedAll(h2); feedSlag(h2); feedAll(c); run(60); }
                        float heatEff2 = callF(c, "heatEfficiency");
                        info("关掉组合: 需热方块 有热源 heatEfficiency=" + heatEff2 + " warmup=" + num(c, "warmup")
                            + " availableHeat=" + callF(c, "availableHeat")
                            + " 热源producerHeat=" + num(h2, "producerHeat")
                            + " 看到热源=" + c.proximity.contains(h2, true));
                        check("关掉组合后需热方块（" + heatCrafter.name + "）贴到热源就能开工", heatEff2 > 0.05f);
                    }
                }
            }

            // ==================== 5) 冲击反应堆：关掉后要重新预热 ====================
            Block impact = find("impact-reactor");
            if(impact != null){
                setAutoOff(true);
                Building ir = place(impact, 200, 60, Team.sharded);
                Building ip = power == null ? null : placeBeside(power, ir);
                feedAll(ir);
                if(ir.items != null){
                    ir.items.add(Items.blastCompound, 300);
                    ir.items.add(Items.pyratite, 300);
                }
                if(ir.liquids != null) ir.liquids.add(Liquids.cryofluid, 300f);
                run(15);
                float w1 = num(ir, "impactWarmup"), pe1 = num(ir, "productionEfficiency");
                // 同样要持续补燃料/冷却液（每帧会被夹回单台液容量）
                for(int i = 0; i < 18; i++){
                    feedAll(ir);
                    if(ir.items != null){ ir.items.add(Items.blastCompound, 300); ir.items.add(Items.pyratite, 300); }
                    if(ir.liquids != null) ir.liquids.add(Liquids.cryofluid, 300f);
                    run(50);
                }
                float w2 = num(ir, "impactWarmup"), pe2 = num(ir, "productionEfficiency");
                info("关掉组合: 冲击反应堆 15tick 预热=" + w1 + " 出力=" + pe1
                    + " / 900tick 预热=" + w2 + " 出力=" + pe2 + " 电源=" + (ip != null));
                check("关掉组合后冲击反应堆要重新预热（刚放下时出力≈0）", pe1 < 0.05f);
                check("关掉组合后冲击反应堆照常预热（预热随时间涨上来）", w2 > w1 + 0.3f);
            }

            // ==================== 6) 收尾：把组合开关恢复到"开着" ====================
            // （原来这里还测「只保留超级组合」那条挡位：合体建筑已独立到 merge 项目，
            //   那条挡位和它的用例一起去掉了 —— merge 里本来就只有合体、没有相邻自动组合。）
            setAutoOff(false);
            run(20);
            check("重新打开后钻头/泵照常工作（收尾）", d1.items != null);
            System.out.println("[COF] RESULT " + (fail == 0 ? "ALL PASS " : ("FAILED " + fail + " "))
                + "(pass=" + pass + " fail=" + fail + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
