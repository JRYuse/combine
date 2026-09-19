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
            }else if(mode.equals("rep")){
                // 用户复现存档（stainedMountains）：读档 → 存档 → 再读档，看物品会不会变多。
                // 这些模组（ve 建 GL shader、饱和火力要 Java 25）在 headless 里跑不起来，所以用真客户端。
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupReproScene, 6f);
                Timer.schedule(Driver::reproStep, 20f, 10f);
                Timer.schedule(() -> { Log.info("[drv] rep 模式超时结束"); Core.app.exit(); }, 600f);
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
            }else{
                Timer.schedule(Driver::step1, 4f);
            }
        });
    }

    static final String mode = System.getProperty("drv.mode", "list");

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
