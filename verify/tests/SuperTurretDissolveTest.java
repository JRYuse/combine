package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.ItemModule;

/**
 * 合体炮台的三件事：
 *   1) 血量上限自检 —— 用户报"受伤后血量被压制在一个炮台血量的数值上，一直修也恢复不了"：
 *      上限被任何路径写小过之后必须能自己算回来（这里直接把 maxHealth 改小来复现）。
 *   2) 解体 —— 把每一格的炮台放回原处、拆掉本体，库存不丢。
 *   3) 加炮台 —— 把一台现成的合体炮台一起框进来重拼（sources 带 '!' 的那条路），
 *      库存跟着并进新的一台。
 *
 * <p>用法：verify/run-headless.sh mx /tmp/mp_cj/data combine.dbg.SuperTurretDissolveTest
 */
public class SuperTurretDissolveTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_cj/data";
    static int pass = 0, fail = 0;
    static ClassLoader ml; static Class<?> clsST;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){}; Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] " + t); };
        new HeadlessApplication(new SuperTurretDissolveTest(), t -> t.printStackTrace());
    }
    static void check(String n, boolean ok){ System.out.println("[SD] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i = 0; i < f; i++){ arc.util.Time.delta = 1f; Vars.logic.update(); } }
    static Block find(String n){ for(Block b : Vars.content.blocks()) if(b.name.equals(n)) return b; return null; }
    static Building place(Block b, int x, int y, Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte)0, team, null);
        Building bu = Vars.world.build(ax, ay);
        if(bu != null){
            try{ bu.created(); }catch(Throwable ignored){}
            try{ bu.updateProximity(); }catch(Throwable ignored){}
        }
        return bu;
    }
    static Object callStatic(Class<?> c, String m, Class<?>[] sig, Object... args){
        try{ java.lang.reflect.Method mm = c.getMethod(m, sig); mm.setAccessible(true); return mm.invoke(null, args); }
        catch(Throwable t){ System.out.println("[SD] 调用 " + m + " 失败: " + t); return null; }
    }
    static Block blockForSide(int side){ return (Block)callStatic(clsST, "blockForSide", new Class<?>[]{int.class}, side); }
    static Block cellBlock(Block b){ return (Block)callStatic(clsST, "cellBlock", new Class<?>[]{Block.class}, b); }
    static String encodeCell(Block b, float rot){ return (String)callStatic(clsST, "encodeCell", new Class<?>[]{Block.class, float.class}, b, rot); }
    static String encodeConfig(String layout, String sources){
        return (String)callStatic(clsST, "encodeConfig", new Class<?>[]{String.class, String.class}, layout, sources);
    }
    static String layout(Block... blocks){
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < blocks.length; i++){ if(i > 0) sb.append(';'); sb.append(encodeCell(cellBlock(blocks[i]), 90f)); }
        return sb.toString();
    }
    static int worldItems(){
        java.util.IdentityHashMap<ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
        int total = 0;
        for(Tile t : Vars.world.tiles){
            if(t == null || t.build == null || t.build.items == null) continue;
            if(seen.put(t.build.items, Boolean.TRUE) != null) continue;
            total += t.build.items.total();
        }
        return total;
    }
    /** 本队核心的某种物品数量。 */
    static int coreItem(mindustry.type.Item item){
        try{
            var core = Team.sharded.data().core();
            return core == null || core.items == null ? -1 : core.items.get(item);
        }catch(Throwable t){ return -1; }
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
            clsST = Class.forName("combine.turret.SuperTurret", true, ml);
            Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false; Vars.state.rules.waves = false;
            Vars.logic.play();
            run(20);
            for(int y = 30; y < 170; y++) for(int x = 10; x < 250; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
            }
            run(5);

            Block duo = find("duo"), st2 = blockForSide(2);
            // 退款要进核心：先放一个（不然 team.data().core() 是 null）
            place(Blocks.coreShard, 50, 100, Team.sharded);
            run(5);
            float oneCell = duo.health;
            String fourDuo = layout(duo, duo, duo, duo);

            // ---------- 1) 血量上限自检 ----------
            Building a = place(st2, 60, 60, Team.sharded);
            a.configured(null, fourDuo);
            run(10);
            float full = a.maxHealth;
            a.items.add(Items.titanium, 40);  // 钛不是 duo 的弹药，不会被开火吃掉
            run(5);
            int fullItems = worldItems();
            System.out.println("[SD] 血量上限=" + full + "（4×" + oneCell + "）当前=" + a.health);
            check("初始上限 = 4 格之和", Math.abs(full - 4 * oneCell) < 0.5f);
            // 复现"被压制"：把上限写小成**一格炮台的血量**（用户描述的现象）
            a.maxHealth = oneCell;
            a.health = Math.min(a.health, a.maxHealth);
            run(2);
            System.out.println("[SD] 改小后跑 2 帧: maxHealth=" + a.maxHealth + " health=" + a.health);
            check("上限被写小后能自己算回来（" + a.maxHealth + "）", Math.abs(a.maxHealth - full) < 0.5f);
            a.heal(1e6f);
            run(2);
            check("修得上限（" + a.health + " / " + a.maxHealth + "）", Math.abs(a.health - full) < 0.5f);
            check("自检过程没丢东西（" + fullItems + " → " + worldItems() + "）", worldItems() == fullItems);

            // ---------- 2) 解体 ----------
            a.items.add(Items.titanium, 60);
            run(5);
            int beforeDissolve = worldItems();
            
            a.configure((String) clsST.getField("DISSOLVE_TAG").get(null));
            run(12);
            Building anchor = Vars.world.build(60 + (st2.size - 1) / 2, 60 + (st2.size - 1) / 2);
            int duoCount = 0;
            for(int y = 58; y <= 64; y++) for(int x = 58; x <= 64; x++){
                Building b = Vars.world.build(x, y);
                if(b != null && b.block == duo) duoCount++;
            }
            System.out.println("[SD] 解体后 本体=" + (anchor == null ? "没了" : anchor.block.name)
                + " 场上的 duo=" + duoCount + " 物品=" + worldItems() + "（解体前 " + beforeDissolve + "）");
            check("本体已拆掉", anchor == null || anchor.block != st2);
            check("放回了 4 台 duo（实际 " + duoCount + "）", duoCount == 4);
            check("解体不丢物品（" + beforeDissolve + " → " + worldItems() + "）",
                worldItems() >= beforeDissolve);

            // ---------- 3) 加炮台（吸收一台现成的合体炮台） ----------
            // 先造一台 2 格的合体炮台，喂点东西
            Building old = place(st2, 80, 60, Team.sharded);
            old.configured(null, layout(duo, duo));
            run(10);
            old.items.add(Items.titanium, 30);
            run(5);
            int oldCells = ((Number) callStatic(clsST, "loadedCells", new Class<?>[]{Building.class}, old)).intValue();
            // 再造两台普通 duo 当作"要加进去的炮台"
            Building n1 = place(duo, 90, 60, Team.sharded);
            Building n2 = place(duo, 94, 60, Team.sharded);
            run(10);
            int beforeAdd = worldItems();
            String addLayout = layout(duo, duo, duo, duo); // 旧的 2 格 + 新的 2 格
            // 直接调放置器那条路算 sources（旧的合体炮台按它的格数重复写项 + 两台普通炮台）
            Class<?> clsPlacer = Class.forName("combine.turret.SuperTurretPlacer", true, ml);
            Seq<Building> picked = new Seq<>();
            picked.add(old); picked.add(n1); picked.add(n2);
            String addSources = (String) callStatic(clsPlacer, "sourcesOf", new Class<?>[]{Seq.class}, picked);
            System.out.println("[SD] sources=" + addSources);
            Building merged = place(st2, 110, 60, Team.sharded);
            merged.configured(null, encodeConfig(addLayout, addSources));
            run(20);
            int mergedCells = ((Number) callStatic(clsST, "loadedCells", new Class<?>[]{Building.class}, merged)).intValue();
            int mergedTi = merged.items == null ? -1 : merged.items.get(Items.titanium);
            System.out.println("[SD] 加炮台: 旧合体格数=" + oldCells + " 新的格数=" + mergedCells
                + " 钛=" + mergedTi + "（加之前 30，旧合体还在="
                + (Vars.world.build(old.tileX(), old.tileY()) != null) + "）");
            check("加炮台后新的那台有 4 格（实际 " + mergedCells + "）", mergedCells == 4);
            check("被吸收那台的库存跟过来了（钛=" + mergedTi + "）", mergedTi == 30);
            check("被吸收的那台已经拆掉",
                Vars.world.build(old.tileX(), old.tileY()) == null
                    || Vars.world.build(old.tileX(), old.tileY()).block != st2);
            check("加炮台不丢物品（" + beforeAdd + " → " + worldItems() + "）", worldItems() >= beforeAdd);

            // ---------- 4) 12 台 2x2 炮台解体：一台都不能少 ----------
            Block scatter = find("scatter"); // 2x2 的炮台
            Block st4 = blockForSide(4);
            Block[] seq = new Block[12];
            for (int i = 0; i < 12; i++)
                seq[i] = scatter;
            Building big = place(st4, 130, 80, Team.sharded);
            big.configured(null, layout(seq));
            big.items.add(Items.titanium, 50);
            run(10);
            int beforeBig = worldItems();
            int bigCells = ((Number) callStatic(clsST, "loadedCells", new Class<?>[]{Building.class}, big)).intValue();
            big.configure((String) clsST.getField("DISSOLVE_TAG").get(null));
            run(20);
            int scatterCount = 0;
            java.util.HashSet<Building> seenScatter = new java.util.HashSet<>();
            for (int y = 70; y <= 100; y++) for (int x = 120; x <= 150; x++) {
                Building b = Vars.world.build(x, y);
                // 2x2 的炮台会盖住 4 格，按 build 去重才算"几台"
                if (b != null && b.block == scatter && seenScatter.add(b))
                    scatterCount++;
            }
            System.out.println("[SD] 12x2x2: 合体格数=" + bigCells + " 解体后场上 scatter=" + scatterCount
                + " 物品=" + worldItems() + "（解体前 " + beforeBig + "）");
            check("12 台 2x2 都装进去了（" + bigCells + "）", bigCells == 12);
            check("解体后 12 台 2x2 都在（实际 " + scatterCount + "）", scatterCount == 12);
            check("解体不丢物品（" + beforeBig + " → " + worldItems() + "）", worldItems() >= beforeBig);

            // ---------- 5) 原地放置：预览要能盖在被框选的炮台上 ----------
            Class<?> clsPlacer2 = Class.forName("combine.turret.SuperTurretPlacer", true, ml);
            Building t1 = place(duo, 160, 60, Team.sharded);
            Building t2 = place(duo, 161, 60, Team.sharded);
            run(10);
            // 登记"这两格被框住了"
            Seq<Building> framed2 = new Seq<>();
            framed2.add(t1); framed2.add(t2);
            Object framedTiles = callStatic(clsPlacer2, "framedTiles", new Class<?>[]{Seq.class}, framed2);
            callStatic(clsST, "allowReplaceOver", new Class<?>[]{arc.struct.IntSet.class}, framedTiles);
            // 逐格判据（Build.validPlaceIgnoreUnits 对每一格都问这个）：
            boolean okFramed = st2.canPlaceOn(Vars.world.tile(160, 60), Team.sharded, 0)
                && st2.canPlaceOn(Vars.world.tile(161, 60), Team.sharded, 0);
            // 再摆一台**没被框住**的炮台：它那一格必须判非法
            Building stray = place(duo, 163, 60, Team.sharded);
            run(5);
            boolean blockedStray = !st2.canPlaceOn(Vars.world.tile(163, 60), Team.sharded, 0);
            boolean okEmpty = st2.canPlaceOn(Vars.world.tile(166, 60), Team.sharded, 0);
            System.out.println("[SD] 放置判据: 被框住的=" + okFramed + " 没框住的判非法=" + blockedStray
                + " 空地=" + okEmpty);
            check("被框住的炮台那一格可以放下（绿框）", okFramed);
            check("没被框住的炮台那一格判非法（红框）", blockedStray);
            check("空地上照常可以放", okEmpty);

            // 只压住炮台一部分也要能放：合体预览期间"必须完全包住旧方块"那条判据靠放大包围盒放宽
            arc.math.geom.Rect rOn = new arc.math.geom.Rect(), rOff = new arc.math.geom.Rect();
            st2.bounds(183, 60, rOn);
            System.out.println("[SD] 预览期间包围盒=" + rOn);
            callStatic(clsST, "clearReplaceAllowed", new Class<?>[]{});
            st2.bounds(183, 60, rOff);
            System.out.println("[SD] 平时包围盒=" + rOff + " 放大="
                + (rOn.width > rOff.width && rOn.height > rOff.height));
            check("合体预览期间包围盒往外放大（" + rOn.width + "×" + rOn.height + " > "
                + rOff.width + "×" + rOff.height + "）",
                rOn.width > rOff.width && rOn.height > rOff.height && rOn.contains(rOff));
            // 一台只被压住一部分的 2x2 炮台（露在预览外面一格）也要被"包住"
            arc.math.geom.Rect prGrow = new arc.math.geom.Rect();
            callStatic(clsST, "allowReplaceOver", new Class<?>[]{arc.struct.IntSet.class},
                callStatic(clsPlacer2, "framedTiles", new Class<?>[]{Seq.class}, framed2));
            st2.bounds(183, 60, prGrow);
            arc.math.geom.Rect trHalf = new arc.math.geom.Rect();
            scatter.bounds(185, 60, trHalf);
            check("露在预览外面一格的炮台也算被包住", prGrow.grow(0.01f).contains(trHalf));

            // 部分覆盖 + 原地放置：整台原料被顶掉后，露在预览外的格子是**空的**，那一格也必须算数
            // （用户视频：12 台原地合体后只剩 10 台）
            place(scatter, 200, 60, Team.sharded);
            run(5);
            for (int i = 0; i < 2; i++)
                for (int j = 0; j < 2; j++) {
                    Tile ct = Vars.world.tile(200 + i, 60 + j);
                    if (ct != null) ct.setBlock(Blocks.air); // 模拟原版"整台顶掉"
                }
            run(2);
            Building overHalf = place(st2, 201, 60, Team.sharded); // 只压住它的一部分
            overHalf.configured(null, encodeConfig(layout(scatter), "200,60"));
            run(10);
            int overHalfCells = ((Number) callStatic(clsST, "loadedCells",
                new Class<?>[]{Building.class}, overHalf)).intValue();
            System.out.println("[SD] 部分覆盖原地放: 格数=" + overHalfCells + "（期望 1）");
            check("原料只被压住一部分、其余格子已空，也算数（" + overHalfCells + "）", overHalfCells == 1);

            // ---------- 6) 点防炮 / 牵引光束炮也要能**真合体**（框选 → 落地 → 吃掉原料） ----------
            // 它们继承的是 BaseTurret 而不是 Turret，以前"吃掉原料"那条 instanceof 判据
            // 对它们恒为 false → 这一格被清空（用户报的"无法真正合体"）。
            // 按类找（模组会把原版方块换成自己的组合版，名字/类都可能变）
            Block pd = null, tb = null;
            for (Block b : Vars.content.blocks()) {
                if (b instanceof mindustry.world.blocks.defense.turrets.PointDefenseTurret)
                    pd = b;
                else if (b instanceof mindustry.world.blocks.defense.turrets.TractorBeamTurret)
                    tb = b;
            }
            if (pd == null || tb == null) {
                System.out.println("[SD] 这套数据没有 segment/tractor-beam，跳过第 6 组");
            } else {
                Building s1 = place(pd, 220, 60, Team.sharded);
                Building s2 = place(tb, 226, 60, Team.sharded);
                run(10);
                String layMix = layout(pd, tb);
                String srcMix = s1.tileX() + "," + s1.tileY() + ";" + s2.tileX() + "," + s2.tileY();
                Building mix = place(st2, 232, 60, Team.sharded);
                mix.configured(null, encodeConfig(layMix, srcMix));
                run(10);
                int mixCells = ((Number) callStatic(clsST, "loadedCells",
                    new Class<?>[]{Building.class}, mix)).intValue();
                boolean gone = Vars.world.build(s1.tileX(), s1.tileY()) == null
                    && Vars.world.build(s2.tileX(), s2.tileY()) == null;
                System.out.println("[SD] 点防炮+牵引光束炮合体: 格数=" + mixCells + "（期望 2）原料被吃=" + gone);
            check("点防炮/牵引光束炮能真合体（" + mixCells + "）", mixCells == 2);
                check("它们的原料炮台被吃掉", gone);
            }

            // ---------- 7) 拆除合体炮台要退还里面那些炮台的**建造成本** ----------
            Building d1 = place(duo, 240, 60, Team.sharded);
            Building d2 = place(duo, 244, 60, Team.sharded);
            run(10);
            String costSrc = d1.tileX() + "," + d1.tileY() + ";" + d2.tileX() + "," + d2.tileY();
            Building costTurret = place(st2, 250, 60, Team.sharded);
            costTurret.configured(null, encodeConfig(layout(duo, duo), costSrc));
            run(10);
            // 期望退款：两台格子炮台的 requirements × buildCostMultiplier × deconstructRefundMultiplier
            float mul = Vars.state.rules.buildCostMultiplier * Vars.state.rules.deconstructRefundMultiplier;
            Block cellDuo = (Block) callStatic(clsST, "blockOfToken", new Class<?>[]{String.class},
                encodeCell(cellBlock(duo), 90f));
            mindustry.type.Item probe = null;
            int expect = 0;
            if (cellDuo != null && cellDuo.requirements != null && cellDuo.requirements.length > 0) {
                probe = cellDuo.requirements[0].item;
                for (int i = 0; i < 2; i++)
                    expect += Math.round(cellDuo.requirements[0].amount * mul);
            }
            int beforeCost = probe == null ? -1 : coreItem(probe);
            costTurret.onDeconstructed(null);
            run(5);
            int afterCost = probe == null ? -1 : coreItem(probe);
            System.out.println("[SD] 拆除退款: 物品=" + (probe == null ? "?" : probe.name)
                + " 核心 " + beforeCost + " → " + afterCost + "（期望 +" + expect + "）");
            check("拆除合体炮台退还格子炮台造价（+" + (afterCost - beforeCost) + "）",
                probe != null && afterCost - beforeCost == expect && expect > 0);
            // 复原后面还要验"原地放置"，把名单重新登记回去
            callStatic(clsST, "allowReplaceOver", new Class<?>[]{arc.struct.IntSet.class}, framedTiles);
            // 原版放置会把被框住的那两台顶掉；这里直接模拟"顶掉之后落在同一处"（本台盖住那两格）
            Building onTop = place(st2, 160, 60, Team.sharded);
            String onTopSrc = t1.tileX() + "," + t1.tileY() + ";" + t2.tileX() + "," + t2.tileY();
            onTop.configured(null, encodeConfig(layout(duo, duo), onTopSrc));
            run(10);
            int onTopCells = ((Number) callStatic(clsST, "loadedCells", new Class<?>[]{Building.class}, onTop)).intValue();
            System.out.println("[SD] 原地放置: 格数=" + onTopCells + "（预览盖住的原料格也要保留）");
            check("原地放置时被顶掉那两格仍然算数（" + onTopCells + "）", onTopCells == 2);
            callStatic(clsST, "clearReplaceAllowed", new Class<?>[]{});

            System.out.println("[SD] RESULT " + (fail == 0 ? "ALL PASS " : "FAILED ") + "(pass=" + pass + " fail=" + fail + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
