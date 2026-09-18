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
            for(Item it : types) core.items.set(it, cap);
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
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null, b, Team.sharded, ax, ay, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte)0, Team.sharded, null);
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
