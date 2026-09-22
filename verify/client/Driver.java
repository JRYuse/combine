package drv;
import arc.*; import arc.util.*; import arc.util.Timer;
import mindustry.*; import mindustry.content.*; import mindustry.game.*; import mindustry.gen.*; import mindustry.mod.*;
import mindustry.ui.dialogs.*; import mindustry.world.*; import mindustry.world.modules.*; import mindustry.type.*;

/**
 * 验证驱动：截图 + 开设置列表 + 打开 CoopPanel 并改池子看是否实时刷新。
 *
 * 截图输出目录：-Ddrv.out=<目录>（默认 ~/sd/shots）。文件按**跨次运行的连续序号**命名：
 *   001_main.png、002_settings_menu.png …  这样多次跑不会互相覆盖，也能看出先后顺序。
 */
public class Driver extends Mod{
    static String outDir = System.getProperty("drv.out", System.getProperty("user.home") + "/sd/shots");
    static ClassLoader ml;
    static Building b1, b2, c1;

    /** 目录里已有的最大序号 + 1（没有目录就从 1 开始）。 */
    static int nextIndex(){
        int max = 0;
        try{
            arc.files.Fi d = Core.files.absolute(outDir);
            d.mkdirs();
            for(arc.files.Fi f : d.list()){
                String n = f.name();
                int i = 0;
                while(i < n.length() && Character.isDigit(n.charAt(i))) i++;
                if(i > 0) max = Math.max(max, Integer.parseInt(n.substring(0, i)));
            }
        }catch(Throwable t){ Log.err("[drv] 读取截图目录失败", t); }
        return max + 1;
    }

    static int counter = -1;

    @Override public void init(){
        Events.on(EventType.ClientLoadEvent.class, e -> {
            Log.info("[drv] ClientLoadEvent mods=@ blocks=@ mode=@", Vars.mods.list().size, Vars.content.blocks().size, mode);
            try{ Core.files.absolute(outDir).mkdirs(); }catch(Throwable t){}
            counter = nextIndex();
            Log.info("[drv] 截图目录 @（从序号 @ 开始）", Core.files.absolute(outDir).absolutePath(), counter);
            ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
            // -Ddrv.uiscale=200 → 模拟"小逻辑宽度"（PC 上 uiscale 调大/窗口小），用来复现排版问题
            String us = System.getProperty("drv.uiscale");
            if(us != null){
                try{
                    arc.scene.ui.layout.Scl.setProduct(Float.parseFloat(us) / 100f);
                    Core.settings.put("uiscale", Integer.parseInt(us));
                    Log.info("[drv] uiscale=@（逻辑宽度 ≈ @×@）", us, Core.graphics.getWidth(), Core.graphics.getHeight());
                }catch(Throwable t){ Log.err("[drv] uiscale 设置失败", t); }
            }
            if(mode.equals("coolant")){
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupCoolantScene, 5f);
                Timer.schedule(() -> { shot("coolant_selector"); Core.app.exit(); }, 11f);
            }else if(mode.equals("launch")){
                // 发射界面（LaunchLoadoutDialog）：核心被换成组合实例后，
                // universe.getLoadout(组合核心) 必须还能查到内置发射蓝图，否则 .first() 直接崩。
                // 软渲染很慢（1~5fps）：地图加载 + 建世界是几十秒级的真实耗时，
                // 按"固定秒数截图"会截到还没弹出的画面，所以按状态等（launchShown）。
                Timer.schedule(Driver::hideDialogs, 2f);
                Timer.schedule(Driver::setupLaunchScene, 3f);
                Timer.schedule(() -> {
                    if(!launchShown) return;
                    shot("launch_dialog");
                    Log.info("[drv] launch 模式结束（发射界面已截图）");
                    Core.app.exit();
                }, 10f, 1f);
                Timer.schedule(() -> { Log.info("[drv] launch 模式超时退出"); Core.app.exit(); }, 150f);
            }else if(mode.equals("coop")){
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupWorld, 5f);
                Timer.schedule(() -> { shot("coop_panel_open"); }, 8f);
                Timer.schedule(() -> {
                    try{
                        Class<?> coopPanel = Class.forName("combine.coop.CoopPanel", true, ml);
                        Object desc = coopPanel.getMethod("describe", Building.class).invoke(null, b1);
                        Log.info("[drv] t=@ paused=@ describe=@", arc.util.Time.time, Vars.state.isPaused(), desc);
                    }catch(Throwable t){ Log.err("[drv] describe failed", t); }
                }, 8.5f);
                Timer.schedule(Driver::addStuff, 9f);
                Timer.schedule(() -> {
                    try{
                        Class<?> coopPanel = Class.forName("combine.coop.CoopPanel", true, ml);
                        Object desc = coopPanel.getMethod("describe", Building.class).invoke(null, b1);
                        Log.info("[drv] t=@ paused=@ describe=@", arc.util.Time.time, Vars.state.isPaused(), desc);
                    }catch(Throwable t){ Log.err("[drv] describe failed", t); }
                }, 11f);
                // 软渲染下一帧很慢（1~5fps）：加完料**必须真的过了 30 帧**再截，
                // 否则会截到面板还没重画的那一帧（看起来像"没实时刷新"）。
                Timer.schedule(Driver::maybeShotAfter, 10f, 0.5f);
                Timer.schedule(() -> { Log.info("[drv] 超时，直接截"); shot("coop_panel_after"); finish(); }, 75f);
            }else if(mode.equals("gen")){
                // 核反应堆组合进"容量极大的发电机"后：
                //   1) 发电效率只看核反应堆自己的容量（喂满即可 100%）；
                //   2) 面板里的燃料条也要按同一个口径显示（不然看着像没燃料）。
                // 软渲染下一帧很慢：截图靠"真的过了 N 帧"来卡，不用固定秒数。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupGenScene, 5f);
                Timer.schedule(() -> { hideDialogs(); genSetFuel(Math.max(genNukeCap / 2, 1)); openGenPanel(); }, 12f);
                Timer.schedule(Driver::genStep, 13f, 0.5f);
                Timer.schedule(() -> { Log.info("[drv] gen 模式超时结束"); Core.app.exit(); }, 120f);
            }else if(mode.equals("conn")){
                // 组合连接器：两块组合工厂之间铺一连串连接器 → 世界截图（看贴图/连线）
                // + 连接器信息面板截图（看"连接组合 xN"和池子内容）。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupConnScene, 5f);
                Timer.schedule(Driver::stepConn, 8f, 0.5f);
                Timer.schedule(() -> { Log.info("[drv] conn 模式超时结束"); Core.app.exit(); }, 90f);
            }else if(mode.equals("wall")){
                // 组合墙：3 面铜墙成一组 → 打成残血 → 修复投影修 → 截图看还破不破损（裂纹）
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupWallScene, 5f);
                Timer.schedule(() -> shot("wall_damaged"), 16f);
                Timer.schedule(Driver::wallRepairStep, 18f, 0.5f);
                Timer.schedule(() -> { Log.info("[drv] wall 模式超时结束"); Core.app.exit(); }, 150f);
            }else if(mode.equals("bp")){
                // 蓝图里的组合连接器：写一个含连接器的蓝图 → 重新从磁盘 load() → 打印/截图看还在不在
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupBlueprintScene, 5f);
                Timer.schedule(() -> shot("blueprint_dialog"), 25f);
                Timer.schedule(() -> { Log.info("[drv] bp 模式结束"); Core.app.exit(); }, 30f);
            }else if(mode.equals("rebuild")){
                // 用户报："核心单位修废墟/重建不会立刻全做完，只做几个；批量改传送带方向也一样"。
                // 真客户端走一遍原版 B 键框选（InputHandler.rebuildArea）+ 拖一排原地转向计划。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupRebuildScene, 5f);
                Timer.schedule(Driver::rebuildGo, 12f);
                Timer.schedule(Driver::rebuildStep, 14f, 0.5f);
                Timer.schedule(() -> { Log.info("[drv] rebuild 模式超时结束"); Core.app.exit(); }, 200f);
            }else if(mode.equals("rep")){
                // 用户复现存档（stainedMountains）：读档 → 存档 → 再读档，看物品会不会变多。
                // 这些模组（ve 建 GL shader、饱和火力要 Java 25）在 headless 里跑不起来，所以用真客户端。
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupReproScene, 6f);
                Timer.schedule(Driver::reproStep, 20f, 10f);
                Timer.schedule(() -> { Log.info("[drv] rep 模式超时结束"); Core.app.exit(); }, 600f);
            }else if(mode.equals("pwr")){
                // 用户报："每次读写地图后，都要给电网一些刺激（拆一个并入电网的建筑）
                // 才会触发组合建筑与电网的交互更新" —— 读用户存档 ×3 次存读，每次做一遍电网体检。
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupPowerScene, 6f);
                Timer.schedule(Driver::powerStep, 25f, 12f);
                Timer.schedule(() -> { Log.info("[drv] pwr 模式超时结束"); Core.app.exit(); }, 600f);
            }else if(mode.equals("status")){
                // 两块东西一起看：
                //  1) 升华站（sublimate）灌了氰气后的**方块状态**菱形（红=noinput / 绿=active）
                //     —— 开了 renderer.drawStatus 才会画出来，和玩家按 F6 看到的是同一条代码路径；
                //  2) 容器（组合仓库并进核心）的信息面板，看"已并入核心 容量 N"这个数字对不对。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupStatusScene, 5f);
                Timer.schedule(() -> shot("status_world"), 12f);
                Timer.schedule(Driver::openStatusPanel, 14f);
                Timer.schedule(() -> shot("status_panel"), 18f);
                Timer.schedule(() -> { Log.info("[drv] status 模式结束"); Core.app.exit(); }, 22f);
            }else if(mode.equals("mega")){
                // 用户报：mace + oct 组合成"巨兽"后 ①不绘制单位身体 ②力墙 bar 超上限。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupMegaScene, 5f);
                Timer.schedule(() -> shot("mega_before"), 10f);
                Timer.schedule(Driver::megaMerge, 14f);
                Timer.schedule(Driver::megaDumpStep, 20f);
                Timer.schedule(() -> shot("mega_after"), 22f);
                Timer.schedule(Driver::megaTakeControl, 26f);
                Timer.schedule(() -> shot("mega_hud"), 30f);
                Timer.schedule(Driver::megaOpenPanel, 34f);
                Timer.schedule(() -> shot("mega_panel"), 38f);
                // 指挥模式：框选巨兽后命令菜单里的图标（用户报"一直显示 dagger"）
                Timer.schedule(Driver::megaCommandMode, 42f);
                Timer.schedule(() -> shot("mega_command"), 47f);
                Timer.schedule(() -> { Log.info("[drv] mega 模式结束 frames=@", frames); Core.app.exit(); }, 53f);
            }else if(mode.equals("shipmega")){
                // 用户报："两艘船组合后速度变得超级慢 / 没处理水的阻力"。
                // 两艘 risso 在**深水**里合体：看它还在不在水面、速度系数是不是和原版船一致
                // （原版船 1.3；没处理水阻时按普通单位算只有 0.2）。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupShipScene, 5f);
                Timer.schedule(Driver::shipMerge, 12f);
                Timer.schedule(Driver::shipReport, 20f);
                Timer.schedule(() -> shot("ship_mega"), 24f);
                Timer.schedule(() -> { Log.info("[drv] shipmega 模式结束 frames=@", frames); Core.app.exit(); }, 30f);
            }else if(mode.equals("duo")){
                // 用户报：dagger + vela 组合后"会发射 vela 治疗武器的子弹"、"碰撞箱好像变了"。
                // 场景：dagger+vela 合体 → 打开碰撞箱显示 → 旁边放一个敌方单位逼它开火 → 截图。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupDuoScene, 5f);
                Timer.schedule(Driver::duoMerge, 12f);
                Timer.schedule(Driver::duoReport, 20f);
                Timer.schedule(() -> shot("duo_fight"), 30f);
                Timer.schedule(Driver::duoReport, 34f);
                Timer.schedule(() -> shot("duo_fight2"), 40f);
                Timer.schedule(() -> { Log.info("[drv] duo 模式结束 frames=@", frames); Core.app.exit(); }, 46f);
            }else if(mode.equals("legs")){
                // 用户报：组合巨兽（成员是腿类单位）没画腿，腿也要按比例放大。
                installFrameCounter();
                installCameraLock();
                keepDialogsHidden();
                Timer.schedule(Driver::setupLegsScene, 5f);
                Timer.schedule(Driver::legsMerge, 12f);
                Timer.schedule(Driver::legsReport, 20f);
                // 参照 spiroct 紧跟着巨兽，同一张图里对比腿的比例（融合半径 160，必须融合后再放）
                Timer.schedule(Driver::legsRefSpawn, 21f);
                Timer.schedule(() -> shot("legs_mega"), 24f);
                Timer.schedule(Driver::legsReport, 28f);
                Timer.schedule(() -> {
                    Log.info("[drv] legs 参照: ref=@ 位置=(@,@) 腿数=@", legsRef == null ? "null" : legsRef.type.name,
                        legsRef == null ? 0 : (int)legsRef.x, legsRef == null ? 0 : (int)legsRef.y,
                        legsRef instanceof mindustry.gen.Legsc l ? l.legs().length : -1);
                    logLegSpan("巨兽", legsMega);
                    logLegSpan("参照spiroct", legsRef);
                }, 30f);
                Timer.schedule(() -> { Log.info("[drv] legs 模式结束 frames=@", frames); Core.app.exit(); }, 36f);
            }else if(mode.equals("mech")){
                // 机甲类成员（dagger/fortress…）合体：机甲腿也是原版分开画的（drawMech），
                // 一样要按体型放大——和 legs 模式同一套场景，只是把成员换成机甲。
                installFrameCounter();
                installCameraLock();
                keepDialogsHidden();
                Timer.schedule(Driver::setupLegsScene, 5f);
                Timer.schedule(Driver::legsMerge, 12f);
                Timer.schedule(Driver::legsRefSpawn, 21f);
                Timer.schedule(() -> shot("mech_mega"), 24f);
                Timer.schedule(() -> {
                    Log.info("[drv] mech 巨兽: attKind=@ 部件状态: 行走相位=@ baseRotation=@ hitSize=@",
                        field(legsMega, "attKind"), field(legsMega, "mechWalkTime"),
                        field(legsMega, "legBaseRotation"), legsMega == null ? -1f : legsMega.hitSize());
                    Log.info("[drv] mech 参照: @ hitSize=@", legsRef == null ? "null" : legsRef.type.name,
                        legsRef == null ? -1f : legsRef.hitSize());
                }, 28f);
                Timer.schedule(() -> { Log.info("[drv] mech 模式结束 frames=@", frames); Core.app.exit(); }, 36f);
            }else if(mode.equals("userpanel")){
                // 用户报："组合**工厂**物品面板…清空所有物资"。直接读用户存档，挑几台装满货的
                // 组合工厂把信息面板弹出来截图，并把面板里的文字（Bar 的 label）打进日志。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::loadUserSave, 5f);
                Timer.schedule(Driver::userPanelShot0, 40f);
                Timer.schedule(Driver::userPanelShot1, 52f);
                Timer.schedule(() -> { Log.info("[drv] userpanel 模式结束 frames=@", frames); Core.app.exit(); }, 60f);
            }else if(mode.equals("pool")){
                // 用户报："组合建筑物品面板…清空所有物资""组合工厂物资满后有时也会莫名其妙清空物品"。
                // 场景：4 台并排的组合发电机（共享一口池子）灌满燃料 → 开信息面板截图 →
                // 拆掉其中一台成员（原来会把超出新容量的那份真删掉）→ 再看面板/池子。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupPoolScene, 5f);
                Timer.schedule(Driver::poolFill, 10f);
                Timer.schedule(Driver::poolOpenPanel, 14f);
                Timer.schedule(() -> shot("pool_panel_full"), 18f);
                Timer.schedule(Driver::poolBreakOne, 22f);
                Timer.schedule(Driver::poolOpenPanel, 26f);
                Timer.schedule(() -> shot("pool_panel_after"), 30f);
                Timer.schedule(() -> { Log.info("[drv] pool 模式结束 frames=@", frames); Core.app.exit(); }, 36f);
            }else if(mode.equals("tech")){
                // 科技树：真的把 ResearchDialog 打开（用户报"打开科技树崩溃"），
                // 顺带按原版 canSpend 的写法把整棵树解引用一遍，报出是哪个节点坏。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::techScan, 5f);
                Timer.schedule(() -> {
                    try{
                        Vars.ui.research.show();
                        Log.info("[drv] 科技树已打开");
                    }catch(Throwable t){ Log.err("[drv] 打开科技树失败", t); }
                }, 7f);
                Timer.schedule(() -> shot("tech_tree"), 12f);
                Timer.schedule(Driver::techScan, 13f);
                Timer.schedule(() -> { Log.info("[drv] tech 模式结束（frames=@）", frames); Core.app.exit(); }, 16f);
            }else if(mode.equals("tech2")){
                // 进图之后再开科技树：WorldLoadEvent 上本模组会按星球换造价 + 扫一遍树，
                // 这条路径和"主菜单直接开树"不一样，用户是在游戏里点开的。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupTechWorld, 5f);
                Timer.schedule(Driver::techScan, 20f);
                Timer.schedule(Driver::openTechRoots, 22f);
                Timer.schedule(Driver::techScan, 55f);
                Timer.schedule(() -> { Log.info("[drv] tech2 模式结束（frames=@）", frames); Core.app.exit(); }, 58f);
            }else if(mode.equals("technode")){
                // 只关心"三个方块在不在两棵树上、图标画得对不对"：切到塞普罗/埃里克尔树，
                // 把镜头居中到该节点再截图（树很大，不居中的话节点在屏幕外）。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(() -> {
                    // 三个方块的节点在"没解锁前置物品"时会显示锁图标（原版行为），
                    // 这里把它们的材料解锁掉，让节点画出方块本身的样子，方便对图。
                    for(Item it : new Item[]{Items.copper, Items.lead, Items.silicon, Items.metaglass,
                        Items.beryllium, Items.tungsten, Items.oxide}){
                        it.unlock();
                    }
                    Vars.ui.research.show();
                    Timer.schedule(() -> Vars.ui.research.rebuildTree(Blocks.coreShard.techNode), 1f);
                    Timer.schedule(() -> Driver.treeHasOurNodes("打开后（未切换）"), 2.5f);
                    Timer.schedule(() -> centerTree("connection"), 3f);
                    Timer.schedule(Driver::locateTechNodes, 5f);
                    Timer.schedule(() -> shot("serpulo_connection"), 6f);
                    Timer.schedule(() -> centerTree("liquid-unloader"), 7f);
                    Timer.schedule(Driver::locateTechNodes, 9f);
                    Timer.schedule(() -> shot("serpulo_unloader"), 10f);
                    Timer.schedule(() -> Vars.ui.research.rebuildTree(Blocks.coreBastion.techNode), 11f);
                    Timer.schedule(() -> centerTree("connection"), 13f);
                    Timer.schedule(Driver::locateTechNodes, 15f);
                    Timer.schedule(() -> shot("erekir_connection"), 16f);
                    Timer.schedule(() -> centerTree("liquid-unloader"), 17f);
                    Timer.schedule(Driver::locateTechNodes, 19f);
                    Timer.schedule(() -> shot("erekir_unloader"), 20f);
                    Timer.schedule(() -> { Log.info("[drv] technode 模式结束"); Core.app.exit(); }, 23f);
                }, 6f);
            }else{
                Timer.schedule(Driver::step1, 4f);
            }
        });
    }

    static final String mode = System.getProperty("drv.mode", "list");

    // ---------------- 组合巨兽（mace + oct 融合） ----------------
    static Unit megaUnit, maceUnit, octUnit, octUnit2, polyUnit;
    static int megaPhase = 0, megaFrames = -1;

    static Object combineCall(String cls, String method, Class<?>[] sig, Object... args){
        try{
            Class<?> c = Class.forName(cls, true, ml);
            java.lang.reflect.Method m = sig == null ? null : c.getMethod(method, sig);
            if(m == null) m = c.getMethod(method);
            m.setAccessible(true);
            return m.invoke(null, args);
        }catch(Throwable t){ Log.err("[drv] 调用 @.@ 失败", cls, method, t); return null; }
    }

    static void setupMegaScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            // 找一块陆地（别让 mace 落水里淹死）
            int ox = -1, oy = -1;
            outer:
            for(int y=45;y<125;y++){
                for(int x=35;x<190;x++){
                    boolean ok = true;
                    for(int dy=-1;dy<=1 && ok;dy++) for(int dx=-2;dx<=2;dx++){
                        Tile t = Vars.world.tile(x+dx, y+dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] mega 没找到陆地"); return; }

            // 队伍得有核心，不然游戏会把队伍当成已出局、清掉单位
            Building core = placeBL(Blocks.coreShard, ox + 20, oy + 12);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
            megaOx = ox; megaOy = oy;
            Core.camera.position.set(ox * 8f, oy * 8f);
            Timer.schedule(Driver::megaSpawn, 2f);
            Timer.schedule(Driver::megaMerge, 5f);
        }catch(Throwable t){ Log.err("[drv] setupMegaScene failed", t); }
    }

    static int megaOx = -1, megaOy = -1;

    static void megaSpawn(){
        try{
            float cx = megaOx * 8f, cy = megaOy * 8f;
            maceUnit = UnitTypes.mace.create(Team.sharded);
            maceUnit.set(cx - 20f, cy);
            maceUnit.add();
            octUnit = UnitTypes.oct.create(Team.sharded);
            octUnit.set(cx + 30f, cy);
            octUnit.add();
            // 第二台带力场的成员：力场必须合并成一份，否则"力墙条"用单个成员的上限去除全组盾量（超 100%）
            octUnit2 = UnitTypes.oct.create(Team.sharded);
            octUnit2.set(cx + 70f, cy + 20f);
            octUnit2.add();
            // poly：工程/采矿单位 —— 自动重建 / 辅助建造 / 挖矿 这些指令都挂在它身上，
            // 合体后指令表必须继承（用户报的"poly 合体后这些命令消失"）
            polyUnit = UnitTypes.poly.create(Team.sharded);
            polyUnit.set(cx - 60f, cy + 20f);
            polyUnit.add();
            Core.camera.position.set(cx, cy);
            Log.info("[drv] mega 场景: 陆地=@,@ mace=@(@, @) oct=@(@, @) Groups.unit=@ frames=@", megaOx, megaOy,
                maceUnit.type.name, (int)maceUnit.x, (int)maceUnit.y, octUnit.type.name, (int)octUnit.x, (int)octUnit.y,
                Groups.unit.size(), frames);
        }catch(Throwable t){ Log.err("[drv] megaSpawn failed", t); }
    }

    static void megaDumpStep(){
        megaDump("融合后");
        arc.math.geom.Vec2 sp = Core.camera.project(megaUnit == null ? 0 : megaUnit.x, megaUnit == null ? 0 : megaUnit.y);
        Log.info("[drv] 屏幕坐标=@,@ 相机=@,@ frames=@ 力墙条: @", (int)sp.x, (int)sp.y,
            (int)Core.camera.position.x, (int)Core.camera.position.y, frames, shieldBar());
    }

    static void megaTakeControl(){
        if(megaUnit != null) Vars.player.unit(megaUnit);
        if(megaUnit != null) Core.camera.position.set(megaUnit.x, megaUnit.y);
        installCameraLock();
        Log.info("[drv] 玩家单位=@ frames=@", Vars.player.unit() == null ? "null" : Vars.player.unit().type.name, frames);
    }

    /** 力墙条比例 = 盾量 / 力场上限（原版 HUD 与信息面板都用这个数）。 */
    // ---------------- 组合发电机共享池（物品面板 + 满池拆一台） ----------------
    static arc.struct.Seq<Building> poolScene = new arc.struct.Seq<>();
    static Building poolMain;

    static int worldItems(){
        java.util.IdentityHashMap<mindustry.world.modules.ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
        int total = 0;
        for(Tile t : Vars.world.tiles){
            if(t == null || t.build == null || t.build.items == null) continue;
            if(seen.put(t.build.items, Boolean.TRUE) != null) continue;
            total += t.build.items.total();
        }
        return total;
    }

    // ---------------- 用户存档：组合工厂的信息面板 ----------------
    static arc.struct.Seq<Building> userFactories = new arc.struct.Seq<>();

    static void loadUserSave(){
        try{
            arc.files.Fi dir = Vars.saveDirectory;
            arc.files.Fi file = null;
            for(arc.files.Fi f : dir.list()){
                if(!f.name().endsWith(".msav") || f.name().contains("backup")) continue;
                if(f.name().contains("泰伯利亚-4")) file = f;
            }
            if(file == null){
                for(arc.files.Fi f : dir.list()) if(f.name().endsWith(".msav")) file = f;
            }
            if(file == null){ Log.err("[drv] userpanel: 没找到存档（@）", dir.absolutePath()); return; }
            Log.info("[drv] userpanel: 读档 @", file.name());
            mindustry.io.SaveIO.load(file);
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            Log.info("[drv] userpanel: 地图=@ 建筑=@", Vars.state.map.name(), Vars.state.teams.get(Team.sharded).buildings.size);

            // 收集装满货的组合工厂（同一份模块只留一台代表）
            java.util.IdentityHashMap<mindustry.world.modules.ItemModule, Building> reps = new java.util.IdentityHashMap<>();
            for(Tile t : Vars.world.tiles){
                Building b = t == null ? null : t.build;
                if(b == null || !b.isValid() || b.items == null || b.items.total() <= 0) continue;
                String cn = b.getClass().getName();
                if(!cn.startsWith("combine.production.CombinedCrafter")
                    && !cn.startsWith("combine.units.CombinedUnitFactory")) continue;
                reps.put(b.items, b);
            }
            for(var e : reps.entrySet()) userFactories.add(e.getValue());
            userFactories.sort(b -> -b.items.total());
            Log.info("[drv] userpanel: 有货的组合工厂池子 @ 口", userFactories.size);
            for(int i = 0; i < Math.min(6, userFactories.size); i++){
                Building b = userFactories.get(i);
                Log.info("[drv]   #" + i + " " + b.block.name + "@" + b.tileX() + "," + b.tileY()
                    + " 类=" + b.getClass().getSimpleName() + " 池总=" + b.items.total()
                    + " 单台容量=" + b.block.itemCapacity + " 验收上限=" + b.getMaximumAccepted(Vars.content.item(0)));
            }
        }catch(Throwable t){ Log.err("[drv] loadUserSave failed", t); }
    }

    /** 把第 idx 台组合工厂的信息面板弹出来（原版 display 的那套），截图 + 打印面板文字。 */
    static void userPanelOpen(int idx){
        try{
            if(idx >= userFactories.size){ Log.info("[drv] userpanel: 没有第 @ 台", idx); return; }
            Building b = userFactories.get(idx);
            if(b == null || !b.isValid()){ Log.info("[drv] userpanel: 第 @ 台已失效", idx); return; }
            Core.camera.position.set(b.x, b.y);
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            b.getClass().getMethod("display", arc.scene.ui.layout.Table.class).invoke(b, panel);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("组合工厂信息面板: " + b.block.name);
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] userpanel #" + idx + " 面板已弹出: " + b.block.name + "@" + b.tileX() + "," + b.tileY()
                + " 池总=" + (b.items == null ? -1 : b.items.total()));
            userPanel = panel;
        }catch(Throwable t){ Log.err("[drv] userPanelOpen failed", t); }
    }

    static arc.scene.ui.layout.Table userPanel;

    /** 面板显示一帧后，把里面的文字（Bar / Label）抠出来打进日志。 */
    static void userPanelTexts(String tag){
        try{
            if(userPanel == null) return;
            arc.struct.Seq<String> out = new arc.struct.Seq<>();
            collectTexts(userPanel, out);
            StringBuilder sb = new StringBuilder();
            for(String s : out) sb.append('「').append(s).append('」');
            Log.info("[drv] userpanel 面板文字（@）: @", tag, sb.length() == 0 ? "（空）" : sb.toString());
        }catch(Throwable t){ Log.err("[drv] userPanelTexts failed", t); }
    }

    static void collectTexts(arc.scene.Element e, arc.struct.Seq<String> out){
        if(e == null) return;
        if(e instanceof arc.scene.ui.Label l && l.getText() != null){
            String s = l.getText().toString().trim();
            if(!s.isEmpty()) out.add(s);
        }
        if(e instanceof arc.scene.Group g) for(arc.scene.Element c : g.getChildren()) collectTexts(c, out);
    }

    static void userPanelShot0(){ userPanelOpen(0); Timer.schedule(() -> { userPanelTexts("#0"); shot("user_factory_panel0"); }, 2f); }
    static void userPanelShot1(){ userPanelOpen(2); Timer.schedule(() -> { userPanelTexts("#2"); shot("user_factory_panel2"); }, 2f); }

    static String poolInfo(String tag){
        if(poolMain == null || !poolMain.isValid()) return tag + ": 主建筑没了";
        StringBuilder sb = new StringBuilder();
        sb.append(tag).append(": ").append(poolMain.block.name).append("@" ).append(poolMain.tileX()).append(',').append(poolMain.tileY())
          .append(" 组容量=").append(poolMain.getMaximumAccepted(Items.copper))
          .append(" 池总量=").append(poolMain.items == null ? -1 : poolMain.items.total())
          .append(" 煤=").append(poolMain.items == null ? -1 : poolMain.items.get(Items.coal))
          .append(" 同池台数=").append(poolGroupCount())
          .append(" 世界物品总量=").append(worldItems());
        return sb.toString();
    }

    static int poolGroupCount(){
        java.util.IdentityHashMap<Building, Boolean> seen = new java.util.IdentityHashMap<>();
        for(Tile t : Vars.world.tiles){
            if(t == null || t.build == null || t.build.items != poolMain.items) continue;
            seen.put(t.build, Boolean.TRUE);
        }
        return seen.size();
    }

    static void setupPoolScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<120;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            // 找一块 6x4 的空地
            int ox = -1, oy = -1;
            outer:
            for(int y=45;y<110;y++){
                for(int x=35;x<180;x++){
                    boolean ok = true;
                    for(int dy=0;dy<3 && ok;dy++) for(int dx=0;dx<5;dx++){
                        Tile t = Vars.world.tile(x+dx, y+dy);
                        if(t == null || t.floor() == null || t.floor().isDeep() || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] pool 没找到空地"); return; }

            Block gen = null;
            for(Block b : Vars.content.blocks())
                if(b.getClass().getName().equals("combine.production.CombinedGenerator") && b.size == 1 && b.hasItems){ gen = b; break; }
            if(gen == null){ Log.err("[drv] pool 没找到组合发电机"); return; }
            Log.info("[drv] pool 场景: 方块=@(@) 原点=@,@", gen.name, gen.getClass().getSimpleName(), ox, oy);
            for(int i = 0; i < 4; i++){
                Building b = placeBL(gen, ox + i, oy);
                if(b != null) poolScene.add(b);
            }
            poolMain = poolScene.isEmpty() ? null : poolScene.first();
            // 旁边放电源，免得发电机因为没电不工作
            Block src = null;
            for(Block b : Vars.content.blocks()) if(b.name.equals("power-source")) src = b;
            if(src != null) placeBL(src, ox + 1, oy + 2);
            Vars.player.unit(null);
            Core.camera.position.set((ox + 1.5f) * 8f, oy * 8f);
            Log.info("[drv] pool 场景就绪: @", poolInfo("放下后"));
        }catch(Throwable t){ Log.err("[drv] setupPoolScene failed", t); }
    }

    static void poolFill(){
        try{
            if(poolMain == null) return;
            int cap = poolMain.getMaximumAccepted(Items.coal);
            // 按验收上限"灌满"（游戏里 acceptItem 就是按这个上限放行的），
            // 不越过上限 —— 越限属于测试自己造出来的非现实状态
            if(poolMain.items != null) poolMain.items.set(Items.coal, cap);
            Log.info("[drv] pool 灌满: @", poolInfo("灌满后"));
        }catch(Throwable t){ Log.err("[drv] poolFill failed", t); }
    }

    static void poolOpenPanel(){
        try{
            if(poolMain == null || !poolMain.isValid()) return;
            Log.info("[drv] pool 面板前: @", poolInfo("要看面板"));
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            poolMain.getClass().getMethod("display", arc.scene.ui.layout.Table.class).invoke(poolMain, panel);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("组合发电机（组合体共享池）信息面板");
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] pool 面板已弹出（子元素=@）", panel.getChildren().size);
        }catch(Throwable t){ Log.err("[drv] poolOpenPanel failed", t); }
    }

    static void poolBreakOne(){
        try{
            if(poolScene.size < 2) return;
            // 拆"最后一台"：剩下的成员仍然相邻（整组还连着），能看清"容量变小但库存不丢"
            Building victim = poolScene.peek();
            Log.info("[drv] pool 拆前: @", poolInfo("拆前"));
            if(victim != null && victim.isValid()) victim.tile.setBlock(Blocks.air);
            Timer.schedule(() -> Log.info("[drv] pool 拆后: @", poolInfo("拆后")), 1f);
        }catch(Throwable t){ Log.err("[drv] poolBreakOne failed", t); }
    }

    static String shieldBar(){
        if(megaUnit == null) return "无巨兽";
        int fields = 0;
        float max = 0f, scaled = 0f;
        for(var a : megaUnit.abilities()){
            if(a instanceof mindustry.entities.abilities.ForceFieldAbility ff){
                fields++;
                max += ff.max;
                scaled += ff.scaledMax(megaUnit);
            }
        }
        return "力场数=" + fields + " 盾=" + megaUnit.shield() + " max合计=" + max + " scaledMax合计=" + scaled
            + " 第一条bar比例=" + firstShieldRatio();
    }

    static String firstShieldRatio(){
        for(var a : megaUnit.abilities()){
            if(a instanceof mindustry.entities.abilities.ForceFieldAbility ff)
                return (megaUnit.shield() / ff.max) + "（上限=" + ff.max + "）";
        }
        return "无";
    }

    /** 截图时想锁定的单位（默认是 mega 模式那台巨兽）。 */
    static Unit camTarget;

    /** 每帧把相机钉在目标单位身上（llvmpipe 下相机跟随会跟丢，截图就看不到单位）。 */
    static void installCameraLock(){
        var t = new arc.scene.ui.layout.Table();
        t.touchable = arc.scene.event.Touchable.disabled;
        t.update(() -> {
            Unit u = camTarget != null ? camTarget : megaUnit;
            if(u != null && u.isAdded()) Core.camera.position.set(u.x, u.y);
        });
        Vars.ui.hudGroup.addChild(t);
    }

    static void megaOpenPanel(){
        try{
            if(megaUnit == null) return;
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            megaUnit.type.display(megaUnit, panel);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("组合巨兽信息面板");
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] 巨兽信息面板已弹出（子元素=@）frames=@", panel.getChildren().size, frames);
        }catch(Throwable t){ Log.err("[drv] megaOpenPanel failed", t); }
    }

    /**
     * 进指挥模式、把巨兽塞进选择集（= 玩家框选它），把命令菜单截图下来。
     * 面板里的单位图标走的是 content.unit(unit.type.id).uiIcon —— 派生类型共用基础巨兽的占位 id，
     * 所以这里顺手把"面板实际会用的那个类型/图标"打出来对账。
     */
    // ---------------- 腿类单位（spiroct 等）合体：腿 ----------------
    static Unit legsMega, legsA, legsB, legsRef;
    static int legsOx = -1, legsOy = -1;

    static void setupLegsScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.state.rules.fog = false;
            Vars.state.rules.staticFog = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            int ox = -1, oy = -1;
            outer:
            for(int y=45;y<120;y++){
                for(int x=40;x<190;x++){
                    boolean ok = true;
                    for(int dy=-3;dy<=3 && ok;dy++) for(int dx=-4;dx<=4;dx++){
                        Tile t = Vars.world.tile(x+dx, y+dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] legs: 没找到陆地"); return; }
            legsOx = ox; legsOy = oy;
            Building core = placeBL(Blocks.coreShard, ox + 18, oy + 10);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
            Core.camera.position.set(ox * 8f, oy * 8f);
            Timer.schedule(Driver::legsSpawn, 2f);
        }catch(Throwable t){ Log.err("[drv] setupLegsScene failed", t); }
    }

    static void legsSpawn(){
        try{
            float cx = legsOx * 8f, cy = legsOy * 8f;
            if("mech".equals(mode)){
                // mech 模式：换成一中一小两台机甲（成员构成不同、hitSize 不同，缩放才看得出来）
                legsA = UnitTypes.dagger.create(Team.sharded);
                legsA.set(cx - 20f, cy);
                legsA.add();
                legsB = UnitTypes.fortress.create(Team.sharded);
                legsB.set(cx + 20f, cy);
                legsB.add();
                Log.info("[drv] mech 场景: dagger + fortress 已就位");
                return;
            }
            legsA = UnitTypes.spiroct.create(Team.sharded);
            legsA.set(cx - 20f, cy);
            legsA.add();
            legsB = UnitTypes.arkyid.create(Team.sharded);
            legsB.set(cx + 20f, cy);
            legsB.add();
            Log.info("[drv] legs 场景: spiroct + arkyid 已就位（腿=@/@ 段）",
                UnitTypes.spiroct.legCount, UnitTypes.arkyid.legCount);
        }catch(Throwable t){ Log.err("[drv] legsSpawn failed", t); }
    }

    /** 融合**之后**再放一只参照 spiroct 到巨兽旁边（融合半径内的会被卷进去，所以必须后放）。 */
    static void legsRefSpawn(){
        try{
            if(legsMega == null || !legsMega.isValid()){ Log.err("[drv] legsRefSpawn: 巨兽没了"); return; }
            // 挪到场景开头挑好的那块空地（离核心 18 格），免得巨兽正好站在核心上、贴图互相糊住
            legsMega.set(legsOx * 8f, legsOy * 8f);
            // 相机：关掉"跟随玩家单位"，让 installCameraLock 每帧把镜头钉在巨兽身上；
            // 缩放调大（1.3）保证整只巨兽连同放大后的腿都在画面里。
            Core.settings.put("detach-camera", true);
            Vars.renderer.setScale(1.3f);
            Core.camera.position.set(legsMega.x, legsMega.y);
            legsRef = ("mech".equals(mode) ? UnitTypes.fortress : UnitTypes.spiroct).create(Team.sharded);
            legsRef.set(legsMega.x - 110f, legsMega.y);
            legsRef.add();
            var under = Vars.world.buildWorld(legsMega.x, legsMega.y);
            Log.info("[drv] legs 参照物已放: 位置=(@,@) 巨兽脚下建筑=@ 相机=(@,@)",
                (int)legsRef.x, (int)legsRef.y, under == null ? "无" : under.block.name,
                (int)Core.camera.position.x, (int)Core.camera.position.y);
        }catch(Throwable t){ Log.err("[drv] legsRefSpawn failed", t); }
    }

    static void legsMerge(){
        try{
            Object merged = legsA == null ? null
                : combineCall("combine.units.UnitComboMerge", "merge", new Class<?>[]{Unit.class}, legsA);
            if(merged instanceof Unit u) legsMega = u;
            Log.info("[drv] legs 融合结果=@", merged);
        }catch(Throwable t){ Log.err("[drv] legsMerge failed", t); }
    }

    /** 打一只腿类单位的"腿展"（每根腿的 base 相对身体中心的距离），用来对账缩放比例。 */
    static void logLegSpan(String tag, Unit u){
        if(u == null || !(u instanceof mindustry.gen.Legsc l)){ Log.info("[drv] @ 不是腿类单位", tag); return; }
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < l.legs().length; i++){
            var leg = l.legs()[i];
            sb.append(String.format(" %.1f", arc.math.Mathf.dst(u.x, u.y, leg.base.x, leg.base.y)));
        }
        Log.info("[drv] @ 腿展(中心到脚)=@ hitSize=@ 腿数=@", tag, sb.toString(), u.hitSize(), l.legs().length);
    }

    static void legsReport(){
        try{
            if(legsMega == null || !legsMega.isValid()){ Log.info("[drv] legs: 巨兽没了"); return; }
            Unit u = legsMega;
            camTarget = u;
            Core.camera.position.set(u.x, u.y);
            Object dom = field(u.getClass(), u, "dominant");
            Log.info("[drv] legs 巨兽: type=@ hitSize=@ 代表类型=@(hitSize=@) 贴图缩放=@ dom.legRegion=@ dom.legCount=@",
                u.type.name, u.hitSize(), dom instanceof UnitType ? ((UnitType)dom).name : "-",
                dom instanceof UnitType ? ((UnitType)dom).hitSize : -1f,
                dom instanceof UnitType ? (u.hitSize() / ((UnitType)dom).hitSize) : -1f,
                dom instanceof UnitType ? regionName(((UnitType)dom).legRegion) : "-",
                dom instanceof UnitType ? ((UnitType)dom).legCount : -1);
            Log.info("[drv] legs 巨兽是不是 Legsc=" + (u instanceof mindustry.gen.Legsc)
                + " 成员数=" + memberCount(u));
            try{
                Object att = field(u.getClass(), u, "attKind");
                Object legs = field(u.getClass(), u, "legs");
                int n = legs instanceof Object[] a ? a.length : -1;
                StringBuilder sb = new StringBuilder();
                if(legs instanceof Object[] a){
                    for(int i = 0; i < Math.min(n, 6); i++){
                        var l = (mindustry.entities.Leg)a[i];
                        sb.append(" [").append(i).append(" base=").append((int)l.base.x).append(",").append((int)l.base.y)
                          .append(" joint=").append((int)l.joint.x).append(",").append((int)l.joint.y).append("]");
                    }
                }
                Log.info("[drv] legs 部件: attKind=@ legs.length=@ 单位位置=(@,@) 腿=@", att, n, (int)u.x, (int)u.y, sb.toString());
            }catch(Throwable t){ Log.err("[drv] legs 部件报告失败", t); }
        }catch(Throwable t){ Log.err("[drv] legsReport failed", t); }
    }

    // ---------------- dagger + vela（地面组合）：武器 + 碰撞箱 ----------------
    static Unit duoMega, duoDagger, duoVela, duoEnemy, duoAlly;
    static int duoOx = -1, duoOy = -1;

    static void setupDuoScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.state.rules.fog = false;
            Vars.state.rules.staticFog = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            // 关掉"设置里的绘制碰撞箱" → 画出 hitbox（看碰撞箱到底多大、和身体对不对得上）
            Core.settings.put("drawhitboxes", true);
            int ox = -1, oy = -1;
            outer:
            for(int y=45;y<120;y++){
                for(int x=40;x<190;x++){
                    boolean ok = true;
                    for(int dy=-2;dy<=2 && ok;dy++) for(int dx=-3;dx<=3;dx++){
                        Tile t = Vars.world.tile(x+dx, y+dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] duo: 没找到陆地"); return; }
            duoOx = ox; duoOy = oy;
            Building core = placeBL(Blocks.coreShard, ox + 16, oy + 10);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
            Core.camera.position.set(ox * 8f, oy * 8f);
            Timer.schedule(Driver::duoSpawn, 2f);
        }catch(Throwable t){ Log.err("[drv] setupDuoScene failed", t); }
    }

    static void duoSpawn(){
        try{
            float cx = duoOx * 8f, cy = duoOy * 8f;
            duoDagger = UnitTypes.dagger.create(Team.sharded);
            duoDagger.set(cx - 20f, cy);
            duoDagger.add();
            duoVela = UnitTypes.vela.create(Team.sharded);
            duoVela.set(cx + 20f, cy);
            duoVela.add();
            // 敌方靶子：贴近一点（60 单位）逼它开火；看它到底发的是光束还是子弹、打谁
            duoEnemy = UnitTypes.dagger.create(Team.crux);
            duoEnemy.set(cx + 60f, cy);
            duoEnemy.add();
            // 自己人：故意打残（40% 血）—— vela 的维修光束（RepairBeamWeapon）本来只治它
            duoAlly = UnitTypes.dagger.create(Team.sharded);
            duoAlly.set(cx, cy + 40f);
            duoAlly.add();
            duoAlly.health(duoAlly.maxHealth() * 0.4f);
            Core.camera.position.set(cx + 40f, cy);
            Log.info("[drv] duo 场景: dagger+vela 已就位，敌方靶子 @,@ 伤员 @,@（血 @%）",
                (int)duoEnemy.x, (int)duoEnemy.y, (int)duoAlly.x, (int)duoAlly.y, (int)(duoAlly.healthf() * 100));
        }catch(Throwable t){ Log.err("[drv] duoSpawn failed", t); }
    }

    static void duoMerge(){
        try{
            Object merged = duoDagger == null ? null
                : combineCall("combine.units.UnitComboMerge", "merge", new Class<?>[]{Unit.class}, duoDagger);
            if(merged instanceof Unit u){
                duoMega = u;
                camTarget = u;
                installCameraLock();
                // 直接按住扳机（controllable 武器只有玩家扣扳机才开火；autoTarget 的维修光束
                // 会用自己的索敌覆盖 mount.shoot，所以这里只影响那些"要玩家瞄"的武器）
                installTrigger(u, duoEnemy);
                Log.info("[drv] duo 融合后立刻: 类=@ 成员=@ 挂座=@ 能力=@ 有武器=@", u.getClass().getName(),
                    memberCount(u), u.mounts() == null ? -1 : u.mounts().length, u.abilities() == null ? -1 : u.abilities().length, u.hasWeapons());
            }
        }catch(Throwable t){ Log.err("[drv] duoMerge failed", t); }
    }

    /** 每帧替目标单位按住扳机并对准 target（截图用：逼它开火，好看清武器到底发什么）。 */
    static void installTrigger(Unit u, Unit target){
        var t = new arc.scene.ui.layout.Table();
        t.touchable = arc.scene.event.Touchable.disabled;
        t.update(() -> {
            if(u == null || !u.isAdded()) return;
            float tx = target != null && target.isValid() ? target.x : u.x + 100f;
            float ty = target != null && target.isValid() ? target.y : u.y;
            if(u.mounts() == null) return;
            for(var m : u.mounts()){
                m.aimX = tx;
                m.aimY = ty;
                m.shoot = true;
                m.rotate = true;
            }
        });
        Vars.ui.hudGroup.addChild(t);
    }

    static int memberCount(Unit u){
        try{ return (Integer)u.getClass().getMethod("memberCount").invoke(u); }catch(Throwable t){ return -1; }
    }

    static void duoReport(){
        try{
            if(duoMega == null || !duoMega.isValid()){ Log.info("[drv] duo: 巨兽没了"); return; }
            Unit u = duoMega;
            camTarget = u;
            Core.camera.position.set(u.x, u.y);
            Object dom = field(u.getClass(), u, "dominant");
            Log.info("[drv] duo 巨兽: type=@ flying=@ 综合hitSize=@ 代表类型=@(hitSize=@) 贴图缩放=@",
                u.type.name, u.type.flying, u.hitSize(), dom instanceof UnitType ? ((UnitType)dom).name : "-",
                dom instanceof UnitType ? ((UnitType)dom).hitSize : -1f,
                dom instanceof UnitType ? (u.hitSize() / ((UnitType)dom).hitSize) : -1f);
            Log.info("[drv] duo 稳定后: 类=@ 成员=@ 挂座=@ 能力=@ 有武器=@", u.getClass().getName(), memberCount(u),
                u.mounts() == null ? -1 : u.mounts().length, u.abilities() == null ? -1 : u.abilities().length, u.hasWeapons());
            int idx = 0;
            if(u.mounts() != null) for(var m : u.mounts()){
                Log.info("[drv]   挂座@ 武器=@ mount=@ bullet=@ 目标=@", idx++,
                    m.weapon.getClass().getSimpleName(), m.getClass().getSimpleName(),
                    m.weapon.bullet == null ? "null" : m.weapon.bullet.getClass().getSimpleName(),
                    m.target == null ? "null" : m.target.getClass().getSimpleName());
            }
            if(duoEnemy != null)
                Log.info("[drv] duo 敌方靶子: 血=@/@ 在=@,@ 巨兽在=@,@ 玩家单位=@", duoEnemy.health(), duoEnemy.maxHealth(),
                    (int)duoEnemy.x, (int)duoEnemy.y, (int)u.x, (int)u.y,
                    Vars.player.unit() == null ? "null" : Vars.player.unit().type.name);
            if(duoAlly != null)
                Log.info("[drv] duo 伤员: 血=@%@", (int)(duoAlly.healthf() * 100));
            for(var m : u.mounts())
                Log.info("[drv] duo 挂座目标: 武器=@ target=@ shoot=@", m.weapon.getClass().getSimpleName(),
                    m.target == null ? "null" : m.target.getClass().getSimpleName(), m.shoot);
        }catch(Throwable t){ Log.err("[drv] duoReport failed", t); }
    }

    // ---------------- 组合巨兽：两艘船在深水里合体（水阻） ----------------
    static int shipOx = -1, shipOy = -1;
    static Unit shipMegaUnit, rissoA, rissoB, shipRef;

    static void setupShipScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            // 关掉战争迷雾：否则深水那块是没探索过的，单位 inFogTo() 直接不画，截图上啥都看不到
            Vars.state.rules.fog = false;
            Vars.state.rules.staticFog = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            int ox = -1, oy = -1;
            outer:
            for(int y = 45; y < 150; y++){
                for(int x = 40; x < 200; x++){
                    boolean ok = true;
                    for(int dy = -2; dy <= 2 && ok; dy++) for(int dx = -3; dx <= 3; dx++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor() == null || !t.floor().isDeep() || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] shipmega: 没找到深水"); return; }
            // 核心必须放在**陆地**上（水里 placeBL 会失败 → 队伍没核心 → 那块水域没被探索 →
            // 单位 inFogTo() 为真，截图里什么都看不到）
            int coreX = -1, coreY = -1;
            outerCore:
            for(int r = 3; r <= 12; r++){
                for(int dy = -r; dy <= r; dy++) for(int dx = -r; dx <= r; dx++){
                    if(Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    Tile t = Vars.world.tile(ox + dx, oy + dy);
                    if(t != null && t.floor() != null && !t.floor().isLiquid && t.block() == Blocks.air){ coreX = ox + dx; coreY = oy + dy; break outerCore; }
                }
            }
            if(coreX >= 0){
                Building core = placeBL(Blocks.coreShard, coreX, coreY);
                if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
                Log.info("[drv] shipmega: 岸上核心 @,@ 放入=@", coreX, coreY, core != null);
            }else Log.err("[drv] shipmega: 附近没找到陆地放核心");
            shipOx = ox; shipOy = oy;
            Log.info("[drv] shipmega: 深水=@,@ 地形=@", ox, oy, Vars.world.tile(ox, oy).floor().name);
            Timer.schedule(Driver::shipSpawn, 2f);
        }catch(Throwable t){ Log.err("[drv] setupShipScene failed", t); }
    }

    static void shipSpawn(){
        try{
            float cx = shipOx * 8f, cy = shipOy * 8f;
            rissoA = UnitTypes.risso.create(Team.sharded);
            rissoA.set(cx - 12f, cy);
            rissoA.add();
            rissoB = UnitTypes.risso.create(Team.sharded);
            rissoB.set(cx + 12f, cy);
            rissoB.add();
            // 对照船放远一点：merge 会把 160 单位内的同队未编组单位一起并走
            shipRef = UnitTypes.risso.create(Team.sharded);
            shipRef.set(cx + 220f, cy);
            shipRef.add();
            Core.camera.position.set(cx, cy);
            Log.info("[drv] shipmega: 两艘 risso + 一台对照船已就位");
        }catch(Throwable t){ Log.err("[drv] shipSpawn failed", t); }
    }

    static void shipMerge(){
        try{
            Object merged = rissoA == null ? null
                : combineCall("combine.units.UnitComboMerge", "merge", new Class<?>[]{Unit.class}, rissoA);
            if(merged instanceof Unit u) shipMegaUnit = u;
            Log.info("[drv] shipmega 融合结果=@", merged);
        }catch(Throwable t){ Log.err("[drv] shipMerge failed", t); }
    }

    static void shipReport(){
        try{
            if(shipMegaUnit == null){ Log.info("[drv] shipmega: 没有巨兽"); return; }
            Unit u = shipMegaUnit;
            camTarget = u;
            installCameraLock();
            Core.camera.position.set(u.x, u.y);
            if(shipRef != null && shipRef.isValid())
                Log.info("[drv] shipmega 对照（同地形）: 巨兽 speed=@ 系数=@ 有效=@（地形 @） | 原版 risso speed=@ 系数=@ 有效=@",
                    u.type.speed, u.floorSpeedMultiplier(), u.type.speed * u.floorSpeedMultiplier(),
                    u.floorOn() == null ? "null" : u.floorOn().name,
                    shipRef.type.speed, shipRef.floorSpeedMultiplier(),
                    shipRef.type.speed * shipRef.floorSpeedMultiplier());
            Log.info("[drv] shipmega: type=@ hitSize=@ 溺水=@ elevation=@ 盾=@", u.type.name, u.hitSize(), u.canDrown(), u.elevation, u.shield());
        }catch(Throwable t){ Log.err("[drv] shipReport failed", t); }
    }

    static void megaCommandMode(){
        try{
            if(megaUnit == null) return;
            // 真按 Shift 进指挥模式做不到（commandMode 每帧按按键状态重算），
            // 所以这里把"命令菜单列表用的那个控件"直接搭出来：原版面板用的是
            // StatValues.stack(content.unit(unit.type.id), 数量) → stack(type.uiIcon, ...)，
            // 画出来的是不是巨兽自己的图标，一眼就能看。
            Vars.control.input.selectedUnits.clear();
            Vars.control.input.selectedUnits.add(megaUnit);
            arc.Events.fire(mindustry.game.EventType.Trigger.unitCommandChange);

            mindustry.type.UnitType resolved = Vars.content.unit(megaUnit.type.id);
            Object dominant = field(megaUnit.getClass(), megaUnit, "dominant");
            String domIcon = "-", resIcon = "-";
            if(dominant instanceof UnitType dt && dt.uiIcon != null) domIcon = regionName(dt.uiIcon);
            if(resolved != null && resolved.uiIcon != null) resIcon = regionName(resolved.uiIcon);
            Log.info("[drv] 指挥模式: 巨兽 type=@(id=@) → content.unit(id)=@(@) 名字=@",
                megaUnit.type.name, megaUnit.type.id, resolved == null ? "null" : resolved.name,
                resolved == null ? "-" : resolved.getClass().getSimpleName(),
                resolved == null ? "-" : resolved.localizedName);
            Log.info("[drv]   代表成员图标=@ 面板会用的图标=@ 同一个=@", domIcon, resIcon, domIcon.equals(resIcon));
            Log.info("[drv]   选择集=@", Vars.control.input.selectedUnits.size);

            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            panel.add("命令菜单里那一格（StatValues.stack(content.unit(type.id), 1)）：").left().row();
            panel.table(t -> {
                t.left();
                t.add(mindustry.world.meta.StatValues.stack(resolved, 1)).pad(6f);
                t.add(new arc.scene.ui.Image(UnitTypes.dagger.uiIcon)).size(32f).pad(6f).tooltip("这是 dagger（旧 bug 里显示的那个）");
                t.add("← 左=现在面板会显示的；右=dagger（旧 bug 对照）").left().padLeft(6f);
            }).left().row();
            // 命令菜单里的指令按钮行（原版面板就是遍历 content.unit(id).commands 画这些）
            panel.add("命令按钮（面板遍历 type.commands 画的就是这些）：").left().padTop(8f).row();
            StringBuilder names = new StringBuilder();
            panel.table(t -> {
                t.left();
                int col = 0;
                for(var command : resolved.commands){
                    t.button(mindustry.gen.Icon.icons.get(command.icon, mindustry.gen.Icon.cancel), mindustry.ui.Styles.clearNoneTogglei, () -> {})
                        .size(44f).pad(3f).tooltip(command.localized());
                    if(++col % 8 == 0) t.row();
                }
            }).left().row();
            for(var command : resolved.commands) names.append(command.name).append(' ');
            Log.info("[drv]   面板会用到的指令: @", names.toString());
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("指挥模式单位图标核对");
            d.cont.add(panel).pad(10f);
            d.show();
        }catch(Throwable t){ Log.err("[drv] megaCommandMode failed", t); }
    }

    static void megaMerge(){
        try{
            Log.info("[drv] 融合前: Groups.unit=@ mace 有效=@ 已添加=@ 血=@ oct 有效=@ 已添加=@ 血=@ 盾=@",
                Groups.unit.size(),
                maceUnit == null ? "-" : maceUnit.isValid(), maceUnit == null ? "-" : maceUnit.isAdded(), maceUnit == null ? -1f : maceUnit.health(),
                octUnit == null ? "-" : octUnit.isValid(), octUnit == null ? "-" : octUnit.isAdded(), octUnit == null ? -1f : octUnit.health(),
                octUnit == null ? -1f : octUnit.shield());
            Object gid = combineCall("combine.units.UnitComboDamage", "comboId", new Class<?>[]{Unit.class}, maceUnit);
            Log.info("[drv] mace comboId=@", gid);
            Object merged = combineCall("combine.units.UnitComboMerge", "merge", new Class<?>[]{Unit.class}, maceUnit);
            Log.info("[drv] 融合结果=@", merged);
            if(merged instanceof Unit u) megaUnit = u;
            if(megaUnit != null){
                megaDump("融合后");
                shot("mega_world");
            }
        }catch(Throwable t){ Log.err("[drv] megaMerge failed", t); }
    }

    static String regionName(arc.graphics.g2d.TextureRegion r){
        return r == null ? "null" : (r + "@" + System.identityHashCode(r));
    }

    static void megaDump(String tag){
        try{
            if(megaUnit == null){ Log.info("[drv] mega @: 没有巨兽", tag); return; }
            Unit u = megaUnit;
            Object dominant = field(u.getClass(), u, "dominant");
            Object drawScale = field(u.getClass(), u, "drawScale");
            Log.info("[drv] mega @: type=@ 类=@ 有效=@ 已添加=@ hitSize=@ 血=@/@ 盾=@ 力场数=@ 挂座=@ dominant=@ drawScale=@",
                tag, u.type.name, u.getClass().getName(), u.isValid(), u.isAdded(), u.hitSize(), u.health(), u.maxHealth(), u.shield(),
                u.abilities().length, u.mounts().length, dominant, drawScale);
            for(var a : u.abilities()){
                Log.info("[drv]   能力 @ (max=@ scaledMax=@)", a.getClass().getName(),
                    a instanceof mindustry.entities.abilities.ForceFieldAbility ff ? ff.max : "-",
                    a instanceof mindustry.entities.abilities.ForceFieldAbility ff ? ff.scaledMax(u) : "-");
            }
            try{
                Class<?> mt = Class.forName("combine.units.mega.MegaUnitType", true, ml);
                Log.info("[drv]   type 是 MegaUnitType? = @ 类=@", mt.isInstance(u.type), u.type.getClass().getName());
            }catch(Throwable t){ Log.err("[drv] 类型检查失败", t); }
            if(dominant instanceof UnitType dt){
                Log.info("[drv]   dominant fullIcon=@ found=@ region=@ found=@ uiIcon=@",
                    regionName(dt.fullIcon), dt.fullIcon != null && Core.atlas.isFound(dt.fullIcon),
                    regionName(dt.region), dt.region != null && Core.atlas.isFound(dt.region), regionName(dt.uiIcon));
            }
            Log.info("[drv]   type.fullIcon=@ found=@ type.region=@ found=@ drawShields=@ flying=@",
                regionName(u.type.fullIcon), u.type.fullIcon != null && Core.atlas.isFound(u.type.fullIcon),
                regionName(u.type.region), u.type.region != null && Core.atlas.isFound(u.type.region),
                u.type.drawShields, u.type.flying);
            // 绘制裁剪：EntityGroup.draw 用 clipSize 做视口裁剪
            try{
                float clip = u.clipSize();
                arc.math.geom.Rect vp = Core.camera.bounds(new arc.math.geom.Rect());
                boolean overlaps = vp.overlaps(u.x - clip / 2f, u.y - clip / 2f, clip, clip);
                boolean inDrawGroup = false;
                int drawSize = 0;
                for(var d : Groups.draw){
                    drawSize++;
                    if(d == (Object)u) inDrawGroup = true;
                }
                Log.info("[drv]   clipSize=@ 视口=@x@+@x@ 裁剪结果=@ 在绘制组=@ 绘制组大小=@ 在单位组=@",
                    clip, (int)vp.x, (int)vp.y, (int)vp.width, (int)vp.height, overlaps, inDrawGroup, drawSize,
                    Groups.unit.contains(o -> o == u));
            }catch(Throwable t){ Log.err("[drv] 裁剪诊断失败", t); }
        }catch(Throwable t){ Log.err("[drv] megaDump failed", t); }
    }

    static Object field(Class<?> c, Object o, String name){
        Class<?> k = c;
        while(k != null){
            try{
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            }catch(Throwable ignored){ k = k.getSuperclass(); }
        }
        return null;
    }

    static float megaShieldMax(){
        for(var a : megaUnit.abilities()){
            if(a instanceof mindustry.entities.abilities.ForceFieldAbility ff) return ff.max;
        }
        return -1f;
    }

    static void run(int ticks){
        for(int i = 0; i < ticks; i++){
            arc.util.Time.delta = 1f;
            Vars.logic.update();
        }
    }

    /** 帧计数器：挂在 HUD 上的空表，只用来数"画面真的跑了多少帧"。 */
    static int frames = 0, framesAtAdd = -1;
    static void installFrameCounter(){
        var t = new arc.scene.ui.layout.Table();
        t.update(() -> frames++);
        t.touchable = arc.scene.event.Touchable.disabled;
        Vars.ui.hudGroup.addChild(t);
    }
    static void maybeShotAfter(){
        if(framesAtAdd < 0) return;
        if(frames - framesAtAdd >= 30){
            Log.info("[drv] 加料后又跑了 @ 帧 → 截图", frames - framesAtAdd);
            shot("coop_panel_after");
            finish();
        }
    }
    static void finish(){
        Log.info("[drv] done（frames=@）", frames);
        Core.app.exit();
    }

    /** 关掉所有弹窗（模组信息框、设置界面……），免得盖住要截的东西。 */
    static void hideDialogs(){
        try{
            for(arc.scene.Element e : Core.scene.root.getChildren()){
                if(e instanceof arc.scene.ui.Dialog d){
                    d.hide();
                    Log.info("[drv] 关掉弹窗");
                }
            }
        }catch(Throwable t){ Log.err("[drv] hideDialogs failed", t); }
    }

    /**
     * 场景阶段**每秒**清一次弹窗。
     * 客户端的"检查更新"弹窗是启动后隔几秒才弹出来的（软渲染下时机不固定），
     * 只在场景开头清一次会正好被它盖住截图（照片里全是版本列表，看不到单位）。
     */
    static void keepDialogsHidden(){
        Timer.schedule(Driver::hideDialogs, 2f, 1f, 60);
    }

    static void step1(){
        ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        shot("main");
        Vars.ui.settings.show();
        Timer.schedule(() -> { shot("settings_menu"); clickButton("组合工厂"); }, 2.5f);
        Timer.schedule(() -> { shot("settings_list"); }, 5f);
        Timer.schedule(() -> { Log.info("[drv] list 模式结束"); Core.app.exit(); }, 6.5f);
    }

    /** 在场景里找文字包含 key 的 TextButton，触发它的 ClickListener。 */
    static boolean clickButton(String key){
        try{
            return hit(Core.scene.root, key);
        }catch(Throwable t){ Log.err("[drv] clickButton failed", t); }
        return false;
    }
    static boolean hit(arc.scene.Element e, String key){
        if(e instanceof arc.scene.ui.TextButton tb){
            CharSequence cs = tb.getText();
            if(cs != null && cs.toString().contains(key)){
                for(var l : tb.getListeners()){
                    if(l instanceof arc.scene.event.ClickListener cl){
                        cl.clicked(null, 0f, 0f);
                        Log.info("[drv] clicked button: @", cs);
                        return true;
                    }
                }
            }
        }
        if(e instanceof arc.scene.Group g){
            for(arc.scene.Element c : g.getChildren()) if(hit(c, key)) return true;
        }
        return false;
    }

    /** 载一张图并跑起来，让 WorldLoadEvent 上的处理（换星球造价 / 扫树）真的执行一遍。 */
    static void setupTechWorld(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            Log.info("[drv] tech2 场景: 地图=@ 星球=@ 战役=@", map.name(),
                Vars.state.getPlanet() == null ? "null" : Vars.state.getPlanet().name, Vars.state.isCampaign());
        }catch(Throwable t){ Log.err("[drv] setupTechWorld failed", t); }
    }

    /** 真正打开科技树，并把每棵根树的页面都切一遍（rebuildTree → rebuildAll → canSpend）。 */
    static void openTechRoots(){
        try{
            Vars.ui.research.show();
            Log.info("[drv] 科技树已打开，根数=@", mindustry.content.TechTree.roots.size);
            float t = 1f;
            for(mindustry.content.TechTree.TechNode r : new arc.struct.Seq<>(mindustry.content.TechTree.roots)){
                String rootName = r.content == null ? "null" : r.content.name;
                // 一棵树一步地切、切完再截：全挤在同一帧里切的话，截图永远只拍到最后一棵。
                // rebuildTree = switchTree + checkNodes + treeLayout（只 switchTree 不排布，节点会全叠中间）
                Timer.schedule(() -> {
                    try{
                        Vars.ui.research.rebuildTree(r);
                        Log.info("[drv] rebuildTree @ (content=@)", r.localizedName(), rootName);
                    }catch(Throwable ex){ Log.err("[drv] rebuildTree 崩了: @", r, ex); }
                }, t);
                t += 1f;
                Timer.schedule(() -> shot("tech_root_" + rootName), t);
                t += 1f;
            }
        }catch(Throwable t){ Log.err("[drv] openTechRoots failed", t); }
    }

    /** 科技树体检：按原版 canSpend 的写法把每个节点解引用一遍，报出坏节点是谁。 */
    /** 把镜头居中到当前这棵树里某个方块的节点上（树很大，不居中节点会在屏幕外）。 */
    /** 当前界面缓存的那棵树里，有没有我们三个方块（用来验 ResearchDialog 的节点缓存新不新）。 */
    static void treeHasOurNodes(String tag){
        try{
            StringBuilder sb = new StringBuilder();
            for(String n : new String[]{"connection", "node", "liquid-unloader"}){
                boolean found = false;
                for(mindustry.ui.dialogs.ResearchDialog.TechTreeNode ttn : Vars.ui.research.nodes){
                    if(ttn.node != null && ttn.node.content != null && ttn.node.content.name.equals(n)){ found = true; break; }
                }
                sb.append(n).append('=').append(found).append(' ');
            }
            Log.info("[drv] 界面树缓存（@）: 节点@ 个，其中 @", tag, Vars.ui.research.nodes.size, sb);
        }catch(Throwable t){ Log.err("[drv] treeHasOurNodes failed", t); }
    }

    static void centerTree(String contentName){
        try{
            for(mindustry.ui.dialogs.ResearchDialog.TechTreeNode ttn : Vars.ui.research.nodes){
                if(ttn.node == null || ttn.node.content == null) continue;
                if(!ttn.node.content.name.equals(contentName)) continue;
                Vars.ui.research.view.panX = -ttn.x;
                Vars.ui.research.view.panY = -ttn.y + 60f;
                Log.info("[drv] 居中 @: 树坐标 @,@ (当前树可见节点 @ 个)", contentName,
                    (int)ttn.x, (int)ttn.y, Vars.ui.research.nodes.size);
                return;
            }
            Log.err("[drv] 当前这棵树里没有 @", contentName);
        }catch(Throwable t){ Log.err("[drv] centerTree failed", t); }
    }

    /** 把三个方块在当前这棵树里的节点位置打出来（截图按坐标裁）。 */
    static void locateTechNodes(){
        try{
            for(arc.scene.Element e : Vars.ui.research.view.getChildren()){
                if(!(e instanceof arc.scene.ui.ImageButton btn)) continue;
                if(!(btn.userObject instanceof mindustry.content.TechTree.TechNode tn) || tn.content == null) continue;
                String n = tn.content.name;
                if(!(n.equals("connection") || n.equals("node") || n.equals("liquid-unloader"))) continue;
                arc.math.geom.Vec2 v = new arc.math.geom.Vec2(btn.x + btn.getWidth() / 2f, btn.y + btn.getHeight() / 2f);
                e.parent.localToStageCoordinates(v);
                Log.info("[drv] 节点 @: 屏幕 x=@ y=@ (截图坐标 y'=@) 材料@项 有图标=@",
                    n, (int)v.x, (int)v.y, (int)(Core.graphics.getHeight() - v.y),
                    tn.requirements.length, tn.content.uiIcon != null && tn.content.uiIcon.found());
            }
        }catch(Throwable t){ Log.err("[drv] locateTechNodes failed", t); }
    }

    static void techScan(){
        try{
            int bad = 0, total = mindustry.content.TechTree.all.size;
            for(mindustry.content.TechTree.TechNode n : mindustry.content.TechTree.all){
                if(n == null || n.content == null){ Log.err("[drv] 坏节点: content=null"); bad++; continue; }
                try{
                    if(n.requirements == null || n.finishedRequirements == null
                        || n.requirements.length != n.finishedRequirements.length){
                        Log.err("[drv] 坏节点 @: req=@ fin=@", n.content.name,
                            n.requirements == null ? "null" : n.requirements.length,
                            n.finishedRequirements == null ? "null" : n.finishedRequirements.length);
                        bad++;
                        continue;
                    }
                    for(int i = 0; i < n.requirements.length; i++){
                        // 照抄原版 canSpend 的取值顺序
                        int finAmount = n.finishedRequirements[i].amount, reqAmount = n.requirements[i].amount;
                        if(n.requirements[i].item == null){
                            Log.err("[drv] 坏节点 @ 第 @ 项 item=null", n.content.name, i);
                            bad++;
                        }
                    }
                }catch(NullPointerException e){
                    StringBuilder sb = new StringBuilder();
                    for(int i = 0; i < n.requirements.length; i++){
                        sb.append(i).append(':');
                        sb.append(n.requirements[i] == null ? "null"
                            : n.requirements[i].item == null ? "item=null" : n.requirements[i].item.name);
                        sb.append(n.finishedRequirements[i] == null ? "|fin=null " : " ");
                    }
                    Log.err("[drv] canSpend 会在 @ 上崩: @", n.content.name, sb);
                    bad++;
                }
            }
            Log.info("[drv] 科技树扫描: 总节点=@ 坏节点=@", total, bad);
            for(String bn : new String[]{"connection", "node", "liquid-unloader"}){
                Block b = Vars.content.block(bn);
                if(b == null){ Log.err("[drv] 找不到方块 @", bn); continue; }
                Log.info("[drv] 方块 @: cls=@ 有 techNode=@ techNodes=@ 解锁=@ uiIcon=@ 图标可用=@",
                    b.name, b.getClass().getName(), b.techNode != null, b.techNodes.size, b.alwaysUnlocked,
                    b.uiIcon, b.uiIcon != null && b.uiIcon.found());
            }
        }catch(Throwable t){ Log.err("[drv] techScan failed", t); }
    }

    static void shot(String name){
        try{
            if(counter < 0) counter = nextIndex();
            arc.files.Fi f = Core.files.absolute(outDir).child(String.format("%03d_%s.png", counter++, name));
            ScreenUtils.saveScreenshot(f);
            Log.info("[drv] shot @  (@x@)", f.name(), Core.graphics.getWidth(), Core.graphics.getHeight());
        }catch(Throwable t){ Log.err("[drv] shot failed: @", name, t); }
    }

    /** 把 combine 的列表塞进一个独立对话框渲染（等同设置里那一页的内容）。 */
    static void showList(){
        try{
            ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
            // -Ddrv.uiscale=200 → 模拟"小逻辑宽度"（PC 上 uiscale 调大/窗口小），用来复现排版问题
            String us = System.getProperty("drv.uiscale");
            if(us != null){
                try{
                    arc.scene.ui.layout.Scl.setProduct(Float.parseFloat(us) / 100f);
                    Core.settings.put("uiscale", Integer.parseInt(us));
                    Log.info("[drv] uiscale=@（逻辑宽度 ≈ @×@）", us, Core.graphics.getWidth(), Core.graphics.getHeight());
                }catch(Throwable t){ Log.err("[drv] uiscale 设置失败", t); }
            }
            if(Vars.ui.settings != null){ try{ Vars.ui.settings.hide(); }catch(Throwable ignored){} }
            Class<?> stCls = Class.forName("mindustry.ui.dialogs.SettingsMenuDialog$SettingsTable", true, Driver.class.getClassLoader());
            Object st = stCls.getConstructor().newInstance();
            Class<?> cbl = Class.forName("combine.ui.ComboBlockList", true, ml);
            java.lang.reflect.Method build = cbl.getDeclaredMethod("build", stCls);
            build.setAccessible(true);
            build.invoke(null, st);
            Class<?> baseDlg = Class.forName("mindustry.ui.dialogs.BaseDialog", true, Driver.class.getClassLoader());
            Object d = baseDlg.getConstructor(String.class).newInstance("组合工厂");
            var cont = baseDlg.getField("cont").get(d);
            cont.getClass().getMethod("add", Class.forName("arc.scene.Element")).invoke(cont, st);
            baseDlg.getMethod("show").invoke(d);
            Log.info("[drv] list dialog shown");
        }catch(Throwable t){ Log.err("[drv] showList failed", t); }
    }

    /** 加载一张图、放两台扩展建筑、灌点东西进池子，然后打开 CoopPanel。 */
    /** 放一个炮台 + 一个装液体的伙伴 + 组合节点，然后把炮台的"配置面板"（冷却液选择）弹出来截图。 */
    static void setupCoolantScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            Block turretB = null, makerB = null, nodeB = null;
            for(Block b : Vars.content.blocks()){
                if(turretB == null && b.getClass().getName().startsWith("combine.turret.CombinedItemTurret")
                    && b instanceof mindustry.world.blocks.defense.turrets.ItemTurret it
                    && it.ammoTypes != null && it.ammoTypes.containsKey(Items.copper)) turretB = b;
                if(makerB == null && b.name.endsWith("liquid-maker")) makerB = b;
                if(nodeB == null && b.getClass().getName().equals("combine.net.ComboNode")) nodeB = b;
            }
            if(turretB == null || makerB == null || nodeB == null){
                Log.err("[drv] coolant 场景缺方块: @ @ @", turretB, makerB, nodeB);
                return;
            }
            for(int y=40;y<120;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            Building maker = place(makerB, 60, 60);
            Building turret = place(turretB, 74, 60);
            maker.items.add(Items.copper, 200);
            maker.liquids.add(Liquids.water, 300f);
            maker.liquids.add(Liquids.cryofluid, 300f);
            place(nodeB, 67, 60);
            Core.camera.position.set(turret.x, turret.y);
            // 把炮台的配置面板（冷却液选择）弹出来
            arc.scene.ui.layout.Table t2 = new arc.scene.ui.layout.Table();
            turret.getClass().getMethod("buildConfiguration", arc.scene.ui.layout.Table.class).invoke(turret, t2);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("冷却液选择（炮台配置面板）");
            d.cont.add(t2).pad(10f);
            d.show();
            Log.info("[drv] 冷却液选择面板已弹出");
        }catch(Throwable t){ Log.err("[drv] setupCoolantScene failed", t); }
    }

    /** 载入一张有核心的图，然后真的把原版发射界面弹出来（看它会不会崩 / 内置蓝图在不在）。 */
    static boolean launchShown;

    /**
     * 场1：核心 + 两台容器（并进核心）+ 一台灌了氰气的升华站。
     * 打开"方块状态"显示（renderer.drawStatus），并把容器信息面板弹出来截图对比数字。
     */
    static void setupStatusScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            // 方块状态菱形（红=noinput / 橙=nooutput / 绿=active）
            Vars.renderer.drawStatus = true;
            Core.settings.put("blockstatus", true);
            for(int y=40;y<120;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }

            Block coreB = null, contB = null, subB = null;
            for(Block b : Vars.content.blocks()){
                if(b.name.equals("core-shard")) coreB = b;
                if(b.name.equals("container")) contB = b;
                if(b.name.equals("sublimate")) subB = b;
            }
            if(coreB == null || contB == null || subB == null){
                Log.err("[drv] status 场景缺方块: core=@ cont=@ sublimate=@", coreB, contB, subB);
                return;
            }
            Building core = placeBL(coreB, 60, 60);
            // 容器要**紧贴**核心才能并进核心池（中间隔一格就不算相邻）
            c1 = placeBL(contB, 63, 60);
            placeBL(contB, 65, 60);
            int cap = core instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild cb ? cb.storageCapacity : -1;
            // 每种物品各自装满容量：容量是"每种物品各自"的上限，
            // 以前的地板写成 items.total()（所有物品总和）→ 面板会显示成 6 倍的容量。
            Item[] types = {Items.copper, Items.lead, Items.graphite, Items.silicon, Items.metaglass, Items.titanium};
            // 故意塞到**超过**容量（用户存档就是这种历史超容存量）：上限必须还是"核心 + 容器"，
            // 不能跟着库存抬成 23074 这种数字，同时这些超容存量也不该被删。
            for(Item it : types) core.items.set(it, cap + 500);
            int total = core.items.total();

            // 升华站：只灌氰气（用户场景），方块状态菱形必须是绿的
            Building sub = placeBL(subB, 72, 60);
            sub.liquids.add(Liquids.cyanogen, 500f);
            // 镜头把三样东西都框进来：无限火力工厂 / 核心+容器 / 升华站
            Core.camera.position.set(sub.x - 56f, sub.y);

            // 无限火力（X 端的 rules.cheat）下组合单位工厂照样要产出单位：
            // 把工厂激活延迟清零、打开 cheat、进度推到完工线，几秒后它应该已经吐出一个单位载荷。
            Vars.state.rules.unitFactoryActivationDelay = 0f;
            Team.sharded.rules().unitFactoryActivationDelay = 0f;
            Block facB = null;
            for(Block blk : Vars.content.blocks())
                if(blk.getClass().getName().startsWith("combine.units.CombinedUnitFactory")){ facB = blk; break; }
            if(facB != null && ((mindustry.world.blocks.units.UnitFactory) facB).plans.size > 0){
                Building fac = placeBL(facB, 52, 60);
                Team.sharded.rules().cheat = true;
                var ufb = (mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild) fac;
                ufb.currentPlan = 0;
                ufb.progress = ((mindustry.world.blocks.units.UnitFactory) facB).plans.get(0).time - 1f;
                Log.info("[drv] 无限火力工厂: @ 计划=@ 时间=@ 池内物品=@ cheat=@",
                    facB.name, ((mindustry.world.blocks.units.UnitFactory) facB).plans.get(0).unit.name,
                    ((mindustry.world.blocks.units.UnitFactory) facB).plans.get(0).time, fac.items.total(), fac.cheating());
            }

            Log.info("[drv] status 场景: 核心静态容量=@ 已塞物品总和=@ 升华站类=@ 氰气=@",
                cap, total, subB.getClass().getName(), sub.liquids.get(Liquids.cyanogen));
            Events.on(EventType.UnitCreateEvent.class, e -> {
                unitCreates++;
                Log.info("[drv] 单位产出事件 #@: @", unitCreates, e.unit.type.name);
            });
        }catch(Throwable t){ Log.err("[drv] setupStatusScene failed", t); }
    }

    static int unitCreates = 0;

    // ---------------- 组合墙修复 ----------------
    // ---------------- 蓝图里的组合连接器 ----------------
    // ---------------- 用户复现存档：存读档物品变多 ----------------
    static int repPhase = 0;
    static int repBefore = -1;
    static String repSaveName = "sector-serpulo-223.msav";

    static int repWorldTotal(){
        java.util.IdentityHashMap<mindustry.world.modules.ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
        int total = 0;
        for(Tile t : Vars.world.tiles){
            if(t == null || t.build == null || t.build.items == null) continue;
            if(seen.put(t.build.items, Boolean.TRUE) != null) continue;
            total += t.build.items.total();
        }
        return total;
    }

    static String repCoreItems(){
        for(Building b : Vars.state.teams.get(Team.sharded).cores){
            StringBuilder sb = new StringBuilder();
            for(mindustry.type.Item it : Vars.content.items()){
                int n = b.items.get(it);
                if(n > 0) sb.append(it.name).append('=').append(n).append(' ');
            }
            return sb.toString();
        }
        return "无核心";
    }

    // ---------------- 用户报：读档之后组合建筑要"刺激电网"才更新 ----------------
    static int pwrPhase = 0;

    static void setupPowerScene(){
        try{
            hideDialogs();
            arc.files.Fi f = Vars.dataDirectory.child("saves/" + repSaveName);
            Log.info("[drv] pwr 存档=@ 存在=@", f.absolutePath(), f.exists());
            if(!f.exists()) f = Vars.dataDirectory.child("saves/" + repSaveName + "-backup.msav");
            mindustry.io.SaveIO.load(f);
            Vars.state.set(mindustry.core.GameState.State.playing);
            Log.info("[drv] pwr 载入完成 地图=@", Vars.state.map.name());
        }catch(Throwable t){ Log.err("[drv] setupPowerScene failed", t); }
    }

    static boolean pwrHasUpdater(mindustry.world.blocks.power.PowerGraph g){
        for(var e : Groups.powerGraph){
            if(e instanceof mindustry.gen.PowerGraphUpdater u && u.graph == g) return true;
        }
        return false;
    }

    /** 电网体检：每张电网列出来，看成员表/产耗/有没有 updater；再统计组合建筑的电网是否自洽。 */
    static void powerAudit(String tag){
        try{
            var byGraph = new java.util.LinkedHashMap<mindustry.world.blocks.power.PowerGraph, arc.struct.Seq<Building>>();
            int powered = 0, deadTopology = 0, deadGraph = 0, noProd = 0;
            for(Tile t : Vars.world.tiles){
                Building b = t == null ? null : t.build;
                if(b == null || !b.isValid() || b.power == null || b.power.graph == null) continue;
                powered++;
                byGraph.computeIfAbsent(b.power.graph, g -> new arc.struct.Seq<>()).add(b);
            }
            Log.info("[drv] pwr ===== @ : 有电建筑=@ 电网张数=@ =====", tag, powered, byGraph.size());
            for(var e : byGraph.entrySet()){
                var g = e.getKey();
                var members = e.getValue();
                boolean updater = pwrHasUpdater(g);
                // 自洽性：按原版连接规则重算这张电网里的建筑，看看有没有人被漏掉
                arc.struct.ObjectSet<Building> seen = new arc.struct.ObjectSet<>();
                arc.struct.Seq<Building> comp = new arc.struct.Seq<>();
                arc.struct.Queue<Building> q = new arc.struct.Queue<>();
                arc.struct.Seq<Building> tmp = new arc.struct.Seq<>();
                q.addLast(members.first()); seen.add(members.first());
                while(!q.isEmpty()){
                    Building cur = q.removeFirst();
                    comp.add(cur);
                    for(Building nb : cur.getPowerConnections(tmp)){
                        if(nb != null && nb.power != null && seen.add(nb)) q.addLast(nb);
                    }
                }
                boolean consistent = comp.size == members.size;
                if(!consistent) deadTopology++;
                if(!updater) deadGraph++;
                if(g.producers.size > 0 && g.getLastPowerProduced() <= 0f) noProd++;
                StringBuilder names = new StringBuilder();
                int shown = 0;
                for(Building b : members){
                    if(shown++ >= 8){ names.append(" +").append(members.size - 8); break; }
                    names.append(' ').append(b.block.name).append('@').append(b.tileX()).append(',').append(b.tileY());
                }
                Log.info("[drv] pwr 电网#@ 成员=@ 产=@ 耗=@ 产出=@ 需要=@ updater=@ 自洽=@ 建筑:@",
                    g.getID(), members.size, g.producers.size, g.consumers.size,
                    g.getLastPowerProduced(), g.getLastPowerNeeded(), updater, consistent, names);
            }
            Log.info("[drv] pwr @ 汇总: 有电=@ 电网=@ 不自洽电网=@ 无updater电网=@ 有待发电却产出0=@",
                tag, powered, byGraph.size(), deadTopology, deadGraph, noProd);
        }catch(Throwable t){ Log.err("[drv] powerAudit failed", t); }
    }

    static void powerStep(){
        try{
            if(pwrPhase == 0){
                powerAudit("A 读档后（没刺激过电网）");
                pwrPhase = 1;
            }else if(pwrPhase == 1){
                mindustry.io.SaveIO.save(Core.files.absolute("/tmp/cl/pwr1.msav"));
                mindustry.io.SaveIO.load(Core.files.absolute("/tmp/cl/pwr1.msav"));
                Vars.state.set(mindustry.core.GameState.State.playing);
                pwrPhase = 2;
            }else if(pwrPhase == 2){
                powerAudit("B 存读一次后（没刺激过电网）");
                mindustry.io.SaveIO.save(Core.files.absolute("/tmp/cl/pwr2.msav"));
                mindustry.io.SaveIO.load(Core.files.absolute("/tmp/cl/pwr2.msav"));
                Vars.state.set(mindustry.core.GameState.State.playing);
                pwrPhase = 3;
            }else if(pwrPhase == 3){
                powerAudit("C 再存读一次后（没刺激过电网）");
                Log.info("[drv] pwr 结束");
                Core.app.exit();
                pwrPhase = 4;
            }
        }catch(Throwable t){ Log.err("[drv] powerStep failed", t); }
    }

    static void setupReproScene(){
        try{
            hideDialogs();
            arc.files.Fi f = Vars.dataDirectory.child("saves/" + repSaveName);
            Log.info("[drv] rep 存档=@ 存在=@", f.absolutePath(), f.exists());
            if(!f.exists()){ // 回退：也看看备份
                f = Vars.dataDirectory.child("saves/" + repSaveName + "-backup.msav");
                Log.info("[drv] rep 换备份存档=@ 存在=@", f.absolutePath(), f.exists());
            }
            mindustry.io.SaveIO.load(f);
            Vars.state.set(mindustry.core.GameState.State.playing);
            Vars.state.set(mindustry.core.GameState.State.paused);   // 暂停：隔离生产/消耗
            Log.info("[drv] rep 载入完成 地图=@ sector=@", Vars.state.map.name(),
                Vars.state.rules.sector == null ? "null" : Vars.state.rules.sector.name());
        }catch(Throwable t){ Log.err("[drv] setupReproScene failed", t); }
    }

    static void repDump(String tag){
        try{
            Class.forName("combine.net.ComboNet", true, ml).getMethod("debugDumpPools", String.class).invoke(null, tag);
        }catch(Throwable t){ Log.err("[drv] repDump failed", t); }
    }

    static void reproStep(){
        try{
            if(repPhase == 0){
                repBefore = repWorldTotal();
                Log.info("[drv] rep A 世界总量=@ 核心物品: @", repBefore, repCoreItems());
                repDump("A 读档后");
                mindustry.io.SaveIO.save(Core.files.absolute("/tmp/cl/repc1.msav"));
                mindustry.io.SaveIO.load(Core.files.absolute("/tmp/cl/repc1.msav"));
                repPhase = 1;
            }else if(repPhase == 1){
                int now = repWorldTotal();
                Log.info("[drv] rep B 存读一次后 世界总量=@（差 @） 核心物品: @", now, now - repBefore, repCoreItems());
                repDump("B 存读一次后");
                mindustry.io.SaveIO.save(Core.files.absolute("/tmp/cl/repc2.msav"));
                mindustry.io.SaveIO.load(Core.files.absolute("/tmp/cl/repc2.msav"));
                repPhase = 2;
            }else if(repPhase == 2){
                int now = repWorldTotal();
                Log.info("[drv] rep C 再存读一次后 世界总量=@（相对 A 差 @） 核心物品: @", now, now - repBefore, repCoreItems());
                repDump("C 再存读一次后");
                shot("repro_items");
                Log.info("[drv] rep 结束");
                Core.app.exit();
                repPhase = 3;
            }
        }catch(Throwable t){ Log.err("[drv] reproStep failed", t); }
    }

    static void setupBlueprintScene(){
        try{
            hideDialogs();
            Block conn = null;
            for(Block b : Vars.content.blocks()) if(b.getClass().getName().equals("combine.net.ComboConnector")) conn = b;
            if(conn == null){ Log.err("[drv] 没找到组合连接器"); return; }
            Log.info("[drv] 连接器=@ name=@ id=@", conn, conn.name, conn.id);
            Log.info("[drv] 连接器状态: alwaysUnlocked=@ unlocked=@ unlockedNow=@ visible=@ banned=@ placeable=@",
                conn.alwaysUnlocked, conn.unlocked(), conn.unlockedNow(), conn.isVisible(), conn.isBanned(), conn.isPlaceable());

            // 先看"启动时从磁盘读进来的"蓝图：修复前这里会缺连接器（读蓝图早于模组建方块）
            for(mindustry.game.Schematic sc : Vars.schematics.all()){
                if(!"组合连接器测试".equals(sc.tags.get("name"))) continue;
                StringBuilder sb = new StringBuilder();
                int n = 0;
                for(mindustry.game.Schematic.Stile st : sc.tiles){
                    sb.append(st.block == null ? "air" : st.block.name).append(' ');
                    if(st.block != null && st.block.getClass().getName().equals("combine.net.ComboConnector")) n++;
                }
                Log.info("[drv] 启动时读到的老蓝图: 格数=@ 连接器=@/3 方块: @", sc.tiles.size, n, sb);
            }

            var s = new mindustry.game.Schematic(new arc.struct.Seq<>(), new arc.struct.StringMap(), 3, 1);
            for(int i = 0; i < 3; i++)
                s.tiles.add(new mindustry.game.Schematic.Stile(conn, i, 0, null, (byte)0));
            s.tags.put("name", "组合连接器测试");
            arc.files.Fi dir = Vars.dataDirectory.child("schematics");
            dir.mkdirs();
            arc.files.Fi file = dir.child("comboconn-test.msch");
            mindustry.game.Schematics.write(s, file);
            Log.info("[drv] 蓝图写好: @ (@ 字节) 格数=@", file.absolutePath(), file.length(), s.tiles.size);

            // 重新从磁盘读（= 退出重进时走的那条路）
            Vars.schematics.load();
            mindustry.game.Schematic found = null;
            for(mindustry.game.Schematic sc : Vars.schematics.all()){
                if("组合连接器测试".equals(sc.tags.get("name"))) found = sc;
            }
            if(found == null){
                Log.err("[drv] 重新 load() 后找不到这张蓝图！");
            }else{
                StringBuilder sb = new StringBuilder();
                int count = 0;
                for(mindustry.game.Schematic.Stile st : found.tiles){
                    sb.append(st.block == null ? "null" : st.block.name).append(' ');
                    if(st.block != null && st.block.getClass().getName().equals("combine.net.ComboConnector")) count++;
                }
                Log.info("[drv] 重新 load() 后: 格数=@ 连接器=@/3 方块: @", found.tiles.size, count, sb);
                var plans = Vars.schematics.toPlans(found, 10, 10, true);
                StringBuilder pb = new StringBuilder();
                int pconn = 0;
                for(var plan : plans){
                    pb.append(plan.block == null ? "null" : plan.block.name).append(' ');
                    if(plan.block != null && plan.block.getClass().getName().equals("combine.net.ComboConnector")) pconn++;
                }
                Log.info("[drv] 放到场上会生成 @ 个计划，其中连接器 @ 个: @", plans.size, pconn, pb);
            }
            Vars.ui.schematics.show();
        }catch(Throwable t){ Log.err("[drv] setupBlueprintScene failed", t); }
    }

    static Building wall1, wall2, wall3;
    static int wallFrames = -1, wallPhase = 0;

    static void setupWallScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<140;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            Block wall = null, mendBlock = null;
            for(Block b : Vars.content.blocks()){
                if(wall == null && b.name.equals("copper-wall")) wall = b;
                if(mendBlock == null && b.name.equals("mend-projector")) mendBlock = b;
            }
            final Block mend = mendBlock;
            if(wall == null || mend == null){ Log.err("[drv] wall 场景缺方块: wall=@ mend=@", wall, mend); return; }
            wall1 = placeBL(wall, 60, 60);
            wall2 = placeBL(wall, 61, 60);
            wall3 = placeBL(wall, 62, 60);
            // 修复投影要电：直接给这个队伍开 cheat
            Team.sharded.rules().cheat = true;
            wall2.damage(600f);
            Core.camera.position.set(wall2.x, wall2.y + 24f);
            Log.info("[drv] wall 场景: 1=@ 2=@ 3=@ 组=@ 血=@/@/@ 破损=@", wall1, wall2, wall3,
                field(wall1, "seqSize"), wall1.health, wall2.health, wall3.health, wall1.damaged());
            Timer.schedule(() -> {
                try{
                    placeBL(mend, 61, 56);
                    Log.info("[drv] 修复投影已放置");
                }catch(Throwable t){ Log.err("[drv] 放修复投影失败", t); }
            }, 10f);
        }catch(Throwable t){ Log.err("[drv] setupWallScene failed", t); }
    }

    static void wallRepairStep(){
        try{
            if(wall1 == null) return;
            if(wallFrames < 0){
                wallFrames = frames;
                return;
            }
            if(frames - wallFrames < 30) return;
            if(wallPhase == 0){
                shot("wall_damaged");
                Log.info("[drv] wall 残血: 血=@/@/@ 破损=@", wall1.health, wall2.health, wall3.health, wall1.damaged());
                wallPhase = 1;
                wallFrames = -1;
            }else if(wallPhase == 1 && !wall1.damaged() && !wall2.damaged() && !wall3.damaged()){
                shot("wall_repaired");
                Log.info("[drv] wall 已修满: 血=@/@/@ 破损=@", wall1.health, wall2.health, wall3.health, wall1.damaged());
                wallPhase = 2;
                Core.app.exit();
            }
        }catch(Throwable t){ Log.err("[drv] wallRepairStep failed", t); }
    }

    // ---------------- 核心单位：修废墟 / 重建 / 批量改方向 ----------------
    static int rbPhase = 0, rbFrames = -1, rbRuins = 0, rbBroken = 0, rbRotConvs = 0, rbStartTick = 0;
    static Block rbWall, rbConv;

    static void setupRebuildScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.state.rules.derelictRepair = true;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<150;y++) for(int x=30;x<210;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            for(Block b : Vars.content.blocks()){
                if(rbWall == null && b.name.equals("copper-wall")) rbWall = b;
                if(rbConv == null && b.name.equals("conveyor")) rbConv = b;
            }
            if(rbWall == null || rbConv == null){ Log.err("[drv] rebuild 场景缺方块 wall=@ conv=@", rbWall, rbConv); return; }

            // 找一块陆地
            int ox = -1, oy = -1;
            outer:
            for(int y = 45; y < 130; y++){
                for(int x = 35; x < 190; x++){
                    boolean ok = true;
                    for(int dy = 0; dy < 22 && ok; dy++) for(int dx = 0; dx < 22; dx++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor().isDeep() || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] 没找到陆地"); return; }
            rdOx = ox; rdOy = oy;

            // 玩家的核心 + 材料（重建要吃料）
            Building core = placeBL(Blocks.coreShard, ox + 18, oy + 18);
            if(core != null && core.items != null){
                for(Item it : Vars.content.items()) core.items.set(it, 4000);
            }
            // 4x4 面 derelict 废墟
            for(int y=0;y<4;y++) for(int x=0;x<4;x++){
                Building w = placeBL(rbWall, ox + 2 + x, oy + 6 + y);
                if(w != null) w.changeTeam(Team.derelict);
            }
            // 4 台"被摧毁的建筑"（队伍计划表：原版 rebuildArea 的第一段来源）
            Vars.player.team().data().plans.clear();
            for(int x=0;x<4;x++){
                Vars.player.team().data().plans.addLast(new Teams.BlockPlan(ox + 8 + x, oy + 6, (short)0, rbConv, null));
            }
            // 一排方向 0 的传送带（后面拖一排方向 1 的计划 = 批量改方向）
            for(int x=0;x<8;x++){
                Building c = placeBL(rbConv, ox + 2 + x, oy + 2);
                if(c != null){ c.rotation = 0; try{ c.updateProximity(); }catch(Throwable ignored){} }
            }
            // 把镜头对到场景中央（能同时看到三块）
            Core.camera.position.set((ox + 6) * 8f, (oy + 6) * 8f);

            // 真客户端里 player.unit() 默认是 null（玩家还没接管单位）：
            // 手动造一台核心单位（= 核心机，本模组按核心尺寸给它挂了多把建造武器）并接管它，
            // 这样下面调原版 InputHandler.rebuildArea 才走得通（它要求 player.isBuilder()）。
            Unit u = Vars.player.unit();
            if(u == null){
                u = ((mindustry.world.blocks.storage.CoreBlock) Blocks.coreShard).unitType.create(Vars.player.team());
                u.set((ox + 6) * 8f, (oy + 6) * 8f);
                u.add();
                Vars.player.unit(u);
            }else{
                u.set((ox + 6) * 8f, (oy + 6) * 8f);
            }
            int mountCount = 0;
            for(var wm : u.mounts) if(wm.weapon != null && wm.weapon.getClass().getName().equals("combine.MultiBuildWeapon")) mountCount++;
            Log.info("[drv] rebuild 场景: 原点=@,@ 核心=@ 废墟=16 待重建=4 玩家单位=@ 建造挂座=@",
                ox, oy, core, u.type.name, mountCount);

            rbRuins = 16; rbBroken = 4; rbRotConvs = 8;
        }catch(Throwable t){ Log.err("[drv] setupRebuildScene failed", t); }
    }

    /** 场景摆好后（截图"改之前"）再下命令：原版 B 键框选 + 拖一排原地转向。 */
    static void rebuildGo(){
        try{
            Unit u = Vars.player.unit();
            if(u == null){ Log.err("[drv] rebuildGo: 玩家没单位"); return; }
            shot("rebuild_before");
            // 原版 B 键框选（真流程）：一次把 废墟 + 被摧毁建筑 全排进玩家单位的队列
            Vars.control.input.rebuildArea(rdOx, rdOy + 2, rdOx + 12, rdOy + 10);
            // 再拖一排原地转向计划（和玩家拖着传送带划过自己那一排生成的一样）
            for(int x=0;x<8;x++) u.addBuild(new mindustry.entities.units.BuildPlan(rdOx + 2 + x, rdOy + 2, 1, rbConv, null));
            rbStartTick = (int)Vars.state.tick;
            Log.info("[drv] rebuild 场景: 排好计划 @ 条（框选 + 8 条转向）", u.plans().size);
        }catch(Throwable t){ Log.err("[drv] rebuildGo failed", t); }
    }

    static void rebuildStep(){
        try{
            if(rbFrames < 0){ rbFrames = frames; return; }
            int repaired = 0, rebuilt = 0, rotated = 0;
            for(Tile t : Vars.world.tiles){
                if(t == null || t.build == null) continue;
                if(t.block() == rbWall && t.team() == Team.sharded) repaired++;
                else if(t.block() == rbConv && t.build.rotation == 1) rotated++;
            }
            // 重建好的传送带（被摧毁建筑那 4 格）
            for(int x = 0; x < 4; x++){
                Tile t = Vars.world.tile(rdOx + 8 + x, rdOy + 6);
                if(t != null && t.block() == rbConv && t.build != null) rebuilt++;
            }
            Unit u = Vars.player.unit();
            int queue = u == null || u.plans() == null ? -1 : u.plans().size;
            Log.info("[drv] rebuild 检查@: 废墟修好=@/@ 重建成=@/@ 转向=@/@ 队列=@ 暂停=@ tick=@ state=@ isGame=@ 单位=@(@,@) updateBuilding=@ canBuild=@",
                rbPhase, repaired, rbRuins, rebuilt, rbBroken, rotated, rbRotConvs, queue,
                Vars.state.isPaused(), (int)Vars.state.tick,
                Vars.state.getState(), Vars.state.isGame(),
                u == null ? "null" : u.type.name, u == null ? -1 : (int)(u.x / 8), u == null ? -1 : (int)(u.y / 8),
                u == null ? "-" : u.updateBuilding(), u == null ? "-" : u.canBuild());
            if(rbPhase == 0){
                rbPhase = 1;
            }else if(repaired >= rbRuins && rebuilt >= rbBroken && rotated >= rbRotConvs){
                shot("rebuild_after");
                Log.info("[drv] rebuild 完成: 废墟=@ 重建=@ 转向=@ (用时 @ 渲染帧 / @ 逻辑 tick)",
                    repaired, rebuilt, rotated, frames - rbFrames, (int)Vars.state.tick - rbStartTick);
                Core.app.exit();
            }
        }catch(Throwable t){ Log.err("[drv] rebuildStep failed", t); }
    }

    static int rdOx = -1, rdOy = -1;

    // ---------------- 组合连接器 ----------------
    static Building connA, connB, connLink, connNode;
    static int connPhase = 0, connFrames = -1;

    static void setupConnScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<120;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }

            Block press = null, connB_ = null, nodeB = null;
            for(Block b : Vars.content.blocks()){
                if(press == null && b.getClass().getName().equals("combine.production.CombinedCrafter") && b.size == 2) press = b;
                if(connB_ == null && b.getClass().getName().equals("combine.net.ComboConnector")) connB_ = b;
                if(nodeB == null && b.getClass().getName().equals("combine.net.ComboNode")) nodeB = b;
            }
            if(press == null || connB_ == null || nodeB == null){ Log.err("[drv] conn 场景缺方块"); return; }
            // 左边一台组合工厂 + 一串连接器 + 右边一台组合工厂
            connA = placeBL(press, 60, 60);
            connLink = placeBL(connB_, 62, 60);
            placeBL(connB_, 63, 60);
            placeBL(connB_, 64, 60);
            connB = placeBL(press, 65, 60);
            // 旁边再放一个组合节点（看它会不会自动连线）
            connNode = placeBL(nodeB, 61, 66);
            connA.items.add(Items.copper, 40);
            Core.camera.position.set((connA.x + connB.x) / 2f, connA.y + 8f);
            Log.info("[drv] conn 场景: A=@ B=@ 同池=@ 连接器=@ 节点links=@",
                connA, connB, connA.items == connB.items, connLink, field(connNode, "links"));
        }catch(Throwable t){ Log.err("[drv] setupConnScene failed", t); }
    }

    static void stepConn(){
        try{
            if(connLink == null) return;
            if(connFrames < 0){ connFrames = frames; return; }
            if(frames - connFrames < 25) return;
            if(connPhase == 0){
                shot("conn_world");
                Log.info("[drv] conn 世界截图: A池=@ B池=@ 同池=@", connA.items.total(), connB.items.total(), connA.items == connB.items);
                connPhase = 1;
                connFrames = -1;
                openConnPanel();
            }else{
                shot("conn_panel");
                Log.info("[drv] conn 模式结束");
                Core.app.exit();
            }
        }catch(Throwable t){ Log.err("[drv] stepConn failed", t); }
    }

    static void openConnPanel(){
        try{
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            connLink.getClass().getMethod("display", arc.scene.ui.layout.Table.class).invoke(connLink, panel);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("组合连接器信息");
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] conn 面板已弹出: 子元素=@", panel.getChildren().size);
        }catch(Throwable t){ Log.err("[drv] openConnPanel failed", t); }
    }

    // ---------------- 核反应堆发电效率 / 燃料条 ----------------
    static Building genNuke, genOther;
    static int genNukeCap = 30;

    static Object field(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return null; }

    static float productionEfficiency(Building b){
        Object v = field(b, "productionEfficiency");
        return v instanceof Number n ? n.floatValue() : -1f; }

    /**
     * 核反应堆 + 一台"容量被撑到 10 万"的发电机（模拟用户说的"组合了一台容量极大的建筑"）。
     * 核容量应该还是核反应堆自己那一份，燃料只要够那一份就该满效率。
     */
    static void setupGenScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            // 客户端 `display()` 里有 "不是本方就只画标题" 的检查，而暂停时 updateTile 不跑
            // （效率永远是 0），所以这里：建在玩家自己的队上 + 别停在暂停态。
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<120;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }

            Block nuke = null, other = null;
            for(Block b : Vars.content.blocks()){
                if(b.name.equals("thorium-reactor")) nuke = b;
                if(other == null && b.getClass().getName().endsWith("CombinedGenerator")
                    && "consume".equals(String.valueOf(field(b, "mode"))) && b.itemCapacity <= 20) other = b;
            }
            if(nuke == null || other == null){ Log.err("[drv] gen 场景缺方块: nuke=@ other=@", nuke, other); return; }
            other.itemCapacity = 100000;   // 撑大池子容量
            int nsz = Math.max(nuke.size, 1);
            genNukeCap = Math.max(nuke.itemCapacity, 1);
            genNuke = placeBL(nuke, 60, 60, Vars.player.team());
            genOther = placeBL(other, 60 + nsz, 60, Vars.player.team());
            Core.camera.position.set(genNuke.x, genNuke.y);
            Log.info("[drv] gen 场景: 队=@ 玩家队=@ paused=@ playing=@ 同池=@ 池容量=@ 核容量=@ 单台核容量=@",
                genNuke.team, Vars.player.team(), Vars.state.isPaused(), Vars.state.isPlaying(),
                genNuke.items == genOther.items, field(genNuke, "comboTotalItemCap"),
                field(genNuke, "comboNuclearItemCap"), nuke.itemCapacity);
        }catch(Throwable t){ Log.err("[drv] setupGenScene failed", t); }
    }

    static void genSetFuel(int n){
        try{
            if(genNuke == null){ Log.err("[drv] genNuke 为空"); return; }
            genNuke.items.set(Items.thorium, n);
            // 让下一帧真的跑过 updateTile（面板/效率都靠它算）
            Timer.schedule(() -> Log.info("[drv] gen 燃料=@ 效率=@", genNuke.items.get(Items.thorium),
                productionEfficiency(genNuke)), 0.5f);
        }catch(Throwable t){ Log.err("[drv] genSetFuel failed", t); }
    }

    static void openGenPanel(){
        try{
            if(genNuke == null){ Log.err("[drv] genNuke 为空"); return; }
            Log.info("[drv] gen 开面板: 燃料=@ 效率=@ 池容量=@ 核容量=@",
                genNuke.items.get(Items.thorium), productionEfficiency(genNuke),
                field(genNuke, "comboTotalItemCap"), field(genNuke, "comboNuclearItemCap"));
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            genNuke.getClass().getMethod("display", arc.scene.ui.layout.Table.class).invoke(genNuke, panel);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("核反应堆面板（组合进大容量发电机）");
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] gen 面板已弹出: 子元素=@ 场景顶层=@ 尺寸=@x@",
                panel.getChildren().size, Core.scene == null ? -1 : Core.scene.root.getChildren().size,
                (int)d.getWidth(), (int)d.getHeight());
        }catch(Throwable t){ Log.err("[drv] openGenPanel failed", t); }
    }

    /**
     * 卡帧数的截图推进：
     *   开面板 → 真过了 30 帧 → 截"半池" → 塞 1000 个（超核容量）重开面板 → 再 30 帧 → 截"满池" → 退出。
     */
    static int genPhase = 0, genFrames = -1;

    static void genStep(){
        try{
            if(genNuke == null) return;
            if(genFrames < 0){
                genFrames = frames;
                return;
            }
            if(frames - genFrames < 30) return;
            if(genPhase == 0){
                shot("gen_panel_half");
                Log.info("[drv] gen 半池: 燃料=@ 效率=@", genNuke.items.get(Items.thorium), productionEfficiency(genNuke));
                genPhase = 1;
                genFrames = -1;
                hideDialogs();
                genSetFuel(1000);
                openGenPanel();
            }else{
                shot("gen_panel_over1000");
                Log.info("[drv] gen 超容量: 燃料=@ 效率=@", genNuke.items.get(Items.thorium), productionEfficiency(genNuke));
                Log.info("[drv] gen 模式结束（frames=@ paused=@）", frames, Vars.state.isPaused());
                Core.app.exit();
            }
        }catch(Throwable t){ Log.err("[drv] genStep failed", t); }
    }

    /** 等仓库重算跑完（合并/容量都稳定）再打印数字 + 把容器的信息面板弹出来截图。 */
    static void openStatusPanel(){
        try{
            if(c1 == null){ Log.err("[drv] c1 为空，没法开面板"); return; }
            mindustry.world.blocks.storage.CoreBlock.CoreBuild core = c1.team.core();
            Log.info("[drv] status 结果: 核心容量=@ 库存总和=@ 容器并入核心=@ 容器模块==核心模块=@",
                core == null ? -1 : core.storageCapacity,
                core == null || core.items == null ? -1 : core.items.total(),
                c1 instanceof mindustry.world.blocks.storage.StorageBlock.StorageBuild sb && sb.linkedCore != null,
                core != null && c1.items == core.items);
            Building fac = null;
            for(Building b : Vars.state.teams.get(Team.sharded).buildings)
                if(b.block.getClass().getName().startsWith("combine.units.CombinedUnitFactory")) { fac = b; break; }
            if(fac != null){
                var ufb = (mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild) fac;
                Log.info("[drv] 无限火力工厂结果: progress=@ 计划=@ payload=@ 单位=@",
                    ufb.progress, ufb.unit() == null ? "-" : ufb.unit().name,
                    ufb.payload, ufb.payload == null ? "-" : ufb.payload.unit.type.name);
            }
            Log.info("[drv] 无限火力工厂累计产出单位数=@", unitCreates);
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            c1.getClass().getMethod("display", arc.scene.ui.layout.Table.class).invoke(c1, panel);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("容器面板（组合仓库并进核心）");
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] 容器面板已弹出（panel 子元素=@）", panel.getChildren().size);
        }catch(Throwable t){ Log.err("[drv] openStatusPanel failed", t); }
    }

    static void setupLaunchScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            for(int y=40;y<120;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }

            mindustry.world.blocks.storage.CoreBlock coreB = null;
            for(Block b : Vars.content.blocks()) if(b.name.equals("core-shard") && b instanceof mindustry.world.blocks.storage.CoreBlock cb) coreB = cb;
            if(coreB == null){ Log.err("[drv] 没找到 core-shard"); return; }
            Log.info("[drv] core-shard 实例=@（组合类=@）", coreB.getClass().getName(),
                coreB.getClass().getName().startsWith("combine."));
            Building core = place(coreB, 60, 60);
            Core.camera.position.set(core.x, core.y);

            var sectors = Planets.serpulo.sectors;
            if(sectors.isEmpty()){ Log.err("[drv] serpulo 没有 sector，没法开发射界面"); return; }
            Sector dest = sectors.get(Math.min(3, sectors.size - 1));
            Vars.state.rules.sector = dest;

            Log.info("[drv] getLoadout(核心)=@ getLoadouts().get(核心)=@",
                Vars.universe.getLoadout(coreB),
                Vars.schematics.getLoadouts().get(coreB));

            // 真·发射界面（和玩家点"发射"是同一条代码路径）
            new mindustry.ui.dialogs.LaunchLoadoutDialog().show(coreB, dest, dest, () -> {});
            launchShown = true;
            Log.info("[drv] 发射界面已弹出（没崩）");
        }catch(Throwable t){ Log.err("[drv] setupLaunchScene failed", t); }
    }

    static void setupWorld(){
        try{
            // 先把设置界面收起来，不然它会一直盖在世界上面（截图就看不到 CoopPanel 了）
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            // 别让它因为"没有核心"判负：一旦弹结算界面，CoopPanel 就被盖住了
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            Class<?> coop = Class.forName("combine.coop.CoopCombo", true, ml);
            java.lang.reflect.Method eligible = coop.getMethod("eligible", Block.class);
            int ext = 0;
            for(Block b : Vars.content.blocks()) if((Boolean)eligible.invoke(null, b)) ext++;
            Log.info("[drv] 扩展建筑数量=@（方块总数 @）", ext, Vars.content.blocks().size);
            Block prod = null;
            for(Block b : Vars.content.blocks()){
                if((Boolean)eligible.invoke(null, b) && b.name.endsWith("冶炼厂") && b.hasItems && b.hasLiquids){ prod = b; break; }
            }
            for(Block b : Vars.content.blocks()){
                if(prod != null) break;
                if((Boolean)eligible.invoke(null, b) && b.hasItems && b.size <= 2){ prod = b; break; }
            }
            if(prod == null){ Log.err("[drv] 没找到可用的扩展建筑"); return; }
            Log.info("[drv] 用 @ (@) 做组合体", prod.name, prod.getClass().getName());
            int sz = Math.max(prod.size, 1);
            for(int y=40;y<120;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            b1 = place(prod, 60, 60);
            b2 = place(prod, 60 + sz, 60);
            // 让镜头对着这两台
            Core.camera.position.set(b1.x, b1.y);
            // 给组里塞点东西（两台共用一份池子）
            addPool(Items.copper, 40, 0f);
            Log.info("[drv] placed @ @  items=@", b1.block.name, b2.block.name, b1.items.get(Items.copper));
            Class<?> coopPanel = Class.forName("combine.coop.CoopPanel", true, ml);
            coopPanel.getMethod("tapped", Tile.class).invoke(null, b1.tile);
            Log.info("[drv] CoopPanel tapped");
        }catch(Throwable t){ Log.err("[drv] setupWorld failed", t); }
    }

    static Building place(Block b, int x, int y){
        mindustry.world.Build.beginPlace(null, b, Team.sharded, x, y, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(x, y), b, null, (byte)0, Team.sharded, null);
        return Vars.world.build(x, y);
    }

    /** 和 headless 测试同一套坐标约定：(x,y) 是方块左下角，锚点 = 左下角 + (size-1)/2。 */
    static Building placeBL(Block b, int x, int y){
        return placeBL(b, x, y, Team.sharded);
    }

    static Building placeBL(Block b, int x, int y, Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte)0, team, null);
        return Vars.world.build(ax, ay);
    }

    /** 往共享池里加物品/液体（CoopPanel 应该立刻显示出来）。 */
    static void addStuff(){
        try{
            addPool(Items.silicon, 25, 0f);
            addPool(null, 0, 30f);   // 液体
            Log.info("[drv] added: copper=@ silicon=@ liquids=@",
                b1.items.get(Items.copper), b1.items.get(Items.silicon), totalLiquid(b1));
            framesAtAdd = frames;
        }catch(Throwable t){ Log.err("[drv] addStuff failed", t); }
    }

    static void addPool(Item item, int amount, float liquidAmount){
        if(b1 == null) return;
        if(item != null && b1.items != null) b1.items.add(item, amount);
        if(liquidAmount > 0f && b1.liquids != null){
            Liquid liq = null;
            for(Liquid l : Vars.content.liquids()) if(l.name.equals("water")) liq = l;
            if(liq != null) b1.liquids.add(liq, liquidAmount);
        }
    }

    static float totalLiquid(Building b){
        if(b.liquids == null) return 0f;
        float s = 0f;
        for(Liquid l : Vars.content.liquids()) s += b.liquids.get(l);
        return s;
    }
}
