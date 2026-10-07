package dsd.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.files.Fi; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.modules.ItemModule;

/**
 * 升级单位（dsd 模组的 UpgradeUnitc）逻辑测试：不碰 UI，只验"花核心物品升级"这条链。
 *
 *   verify/run-headless.sh mx /tmp/mp_dsd/data dsd.dbg.UpgradeUnitTest
 *
 * 覆盖：物品不够 → 买不动；够 → 扣掉**刚好一份**消耗并生效；次数到 max（maxSpeed / maxArmor
 * 换算出来的等级）之后再买返回 false 且不扣东西；buff 买完状态真的挂上（常驻）；
 * ability 买完拿到单位私有副本；存读档之后实体还是 MechUpgradeUnit、次数和解锁都还在。
 */
public class UpgradeUnitTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_dsd/data";
    static int pass = 0, fail = 0;
    static ClassLoader ml;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){};
        Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= Log.LogLevel.err.ordinal()) System.out.println("[L] " + t); };
        new HeadlessApplication(new UpgradeUnitTest(), t -> t.printStackTrace());
    }

    static void check(String name, boolean ok){
        System.out.println("[UPG] " + (ok ? "PASS " : "FAIL ") + name);
        if(ok) pass++; else fail++;
    }
    static void run(int frames){ for(int i = 0; i < frames; i++){ arc.util.Time.delta = 1f; Vars.logic.update(); } }

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

    static Object call(Object o, String name, Class<?>[] sig, Object... args) throws Exception{
        var m = o.getClass().getMethod(name, sig);
        m.setAccessible(true);
        return m.invoke(o, args);
    }
    static int intCall(Object o, String name) throws Exception{ return (Integer)call(o, name, new Class<?>[0]); }
    static float floatCall(Object o, String name) throws Exception{ return (Float)call(o, name, new Class<?>[0]); }
    static Object field(Object o, String name) throws Exception{
        var f = o.getClass().getField(name);
        f.setAccessible(true);
        return f.get(o);
    }
    static java.util.ArrayList<Object> keys(Object map) throws Exception{
        Iterable<?> it = (Iterable<?>)map.getClass().getMethod("keys").invoke(map);
        var list = new java.util.ArrayList<Object>();
        for(Object o : it) list.add(o);
        return list;
    }
    static Object entry(Object map, Object key) throws Exception{
        return map.getClass().getMethod("get", Object.class).invoke(map, key);
    }
    static int costTotal(ItemStack[] cost){
        int n = 0;
        for(ItemStack s : cost) n += s.amount;
        return n;
    }

    static float floatField(Object o, String name) throws Exception{
        return (Float)field(o, name);
    }

    /** type 的统计里有没有 Stat.abilities（= 升级能力走过 StatValues.abilities → display()/addStats()）。 */
    static boolean typeStatsHasAbilities(UnitType ut){
        try{
            ut.checkStats(); // 统计是按需算的（打开内容详情时才 setStats）
            Object stats = ut.getClass().getField("stats").get(ut);
            Object cats = stats.getClass().getMethod("toMap").invoke(stats);
            for(Object cat : (Iterable<?>)cats.getClass().getMethod("values").invoke(cats)){
                Iterable<?> keys = (Iterable<?>)cat.getClass().getMethod("keys").invoke(cat);
                for(Object k : keys) if(k == mindustry.world.meta.Stat.abilities) return true;
            }
        }catch(Throwable t){
            System.out.println("[UPG] 读 stats 失败: " + t);
        }
        return false;
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

            // mod.hjson 里 name 是中文，按主类找（run-headless 的 sanity 是 combine 专用的，这里不适用）
            Mods.LoadedMod mod = null;
            for(Mods.LoadedMod m : Vars.mods.list())
                if(m.main != null && m.main.getClass().getName().equals("dsd.Main")) mod = m;
            check("dsd 模组已加载（主类 dsd.Main）", mod != null && mod.main != null);
            System.out.println("[UPG] 模组列表: " + Vars.mods.list().size + " 个，" + mod);
            if(mod == null || mod.main == null){ System.exit(3); return; }
            ml = mod.main.getClass().getClassLoader();

            Class<?> upTypeCls = Class.forName("dsd.entities.units.UpgradeUnitType", true, ml);
            UnitType ut = null;
            for(UnitType u : Vars.content.units()) if(u.name.endsWith("upgrade-mech")) ut = u;
            check("找到内容里的升级单位（名字以 upgrade-mech 结尾）: " + (ut == null ? "无" : ut.name), ut != null);
            check("它的类型是 UpgradeUnitType", ut != null && upTypeCls.isInstance(ut));
            if(ut == null){ System.exit(3); return; }

            Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            if(map == null) map = Vars.maps.all().first();
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            run(20);

            for(int y = 40; y < 100; y++)
                for(int x = 20; x < 160; x++){
                    Tile t = Vars.world.tile(x, y);
                    if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
                }
            run(5);

            Building core = place(Blocks.coreShard, 60, 60, Team.sharded);
            check("核心放下了", core != null && core.items != null);
            run(5);

            Unit unit = ut.create(Team.sharded);
            unit.set(core.x + 24f, core.y);
            unit.add();
            run(5);
            check("生成的是 MechUpgradeUnit 实体（constructor 生效）: " + unit.getClass().getName(),
                unit.getClass().getName().endsWith("MechUpgradeUnit"));
            check("本队核心查得到（面板就是从这里取物品）", unit.team().core() != null);
            if(unit.team().core() == null){ System.exit(3); return; }

            ItemModule coins = unit.team().core().items;
            ItemStack[] speedCost = (ItemStack[])field(unit, "speedCost");
            ItemStack[] armorCost = (ItemStack[])field(unit, "armorCost");
            check("速度升级有消耗定义（" + costTotal(speedCost) + " 件）", speedCost.length > 0);
            check("护甲升级有消耗定义（" + costTotal(armorCost) + " 件）", armorCost.length > 0);

            int maxSpeedLevel = intCall(unit, "maxSpeedLevel");
            int maxArmorLevel = intCall(unit, "maxArmorLevel");
            System.out.println("[UPG] 上限：速度 " + maxSpeedLevel + " 级（每级 +" + floatCall(unit, "speedAmount")
                + "），护甲 " + maxArmorLevel + " 级（每级 +" + floatCall(unit, "armorAmount") + "）");
            check("maxSpeed 换算出的速度等级 > 0", maxSpeedLevel > 0);
            check("maxArmor 换算出的护甲等级 > 0", maxArmorLevel > 0);

            // ---- 1. 物品不够：买不动，也不改任何状态 ----
            coins.set(Items.copper, 0); coins.set(Items.silicon, 0);
            coins.set(Items.metaglass, 0); coins.set(Items.titanium, 0);
            boolean poorBuy = (Boolean)call(unit, "buySpeed", new Class<?>[]{ItemModule.class}, coins);
            check("核心物品不够时买速度返回 false", !poorBuy);
            check("买失败后速度次数还是 0", intCall(unit, "speedTimers") == 0);

            // ---- 2. 够物品：扣掉刚好一份，次数 +1，rawSpeed 涨 ----
            for(ItemStack s : speedCost) coins.set(s.item, s.amount * 20);
            int before = coins.total();
            float rawBefore = floatCall(unit, "rawSpeed");
            boolean okSpeed = (Boolean)call(unit, "buySpeed", new Class<?>[]{ItemModule.class}, coins);
            check("够物品时买速度成功", okSpeed);
            check("速度次数 +1", intCall(unit, "speedTimers") == 1);
            check("核心扣掉的正好是一份消耗（" + (before - coins.total()) + " = " + costTotal(speedCost) + "）",
                before - coins.total() == costTotal(speedCost));
            check("rawSpeed 涨了 speedAmount", Math.abs(floatCall(unit, "rawSpeed") - rawBefore - floatCall(unit, "speedAmount")) < 0.001f);

            // ---- 3. 到 max 之后锁住：不再涨、不再扣 ----
            for(int i = 0; i < maxSpeedLevel + 3; i++)
                call(unit, "buySpeed", new Class<?>[]{ItemModule.class}, coins);
            check("速度次数不会超过 maxSpeed 定义的量（" + intCall(unit, "speedTimers") + " ≤ " + maxSpeedLevel + "）",
                intCall(unit, "speedTimers") == maxSpeedLevel);
            int coinsAtCap = coins.total();
            boolean overBuy = (Boolean)call(unit, "buySpeed", new Class<?>[]{ItemModule.class}, coins);
            check("到上限后再买返回 false（按钮锁死）", !overBuy);
            check("到上限后再买不扣物品", coins.total() == coinsAtCap);
            check("rawSpeed 恰好到 maxSpeed", Math.abs(floatCall(unit, "rawSpeed") - floatCall(unit, "maxSpeed")) < 0.01f);

            // ---- 4. 护甲同理，armor 字段要跟着涨 ----
            float armorBase = ut.armor;
            for(ItemStack s : armorCost) coins.set(s.item, s.amount * 20);
            for(int i = 0; i < maxArmorLevel + 3; i++)
                call(unit, "buyArmor", new Class<?>[]{ItemModule.class}, coins);
            check("护甲次数不会超过 maxArmor 定义的量（" + intCall(unit, "armorTimers") + " ≤ " + maxArmorLevel + "）",
                intCall(unit, "armorTimers") == maxArmorLevel);
            check("armor = 基础 + 每级增量 × 次数（" + armorBase + " → " + unit.armor() + "）",
                Math.abs(unit.armor() - (armorBase + floatCall(unit, "armorAmount") * maxArmorLevel)) < 0.01f);
            check("armor 不超过 maxArmor", unit.armor() <= floatCall(unit, "maxArmor") + 0.01f);

            // ---- 5. buff：解锁 + 常驻状态真的挂上 ----
            Object buffMap = call(unit, "buffMap", new Class<?>[0]);
            check("type 上登记了至少 1 个 buff", keys(buffMap).size() > 0);
            Object effect = keys(buffMap).get(0);
            Object buffEntry = entry(buffMap, effect);
            ItemStack[] buffCost = (ItemStack[])field(buffEntry, "cost");
            for(ItemStack s : buffCost) coins.set(s.item, s.amount * 4);
            int beforeBuff = coins.total();
            boolean okBuff = (Boolean)call(unit, "buyBuff", new Class<?>[]{StatusEffect.class, ItemModule.class}, effect, coins);
            check("买 buff 成功", okBuff);
            check("buff 的 unlocked 变成 true", (Boolean)field(entry(buffMap, effect), "unlocked"));
            check("buff 状态挂到单位身上了", unit.hasEffect((StatusEffect)effect));
            check("买 buff 扣掉正好一份", beforeBuff - coins.total() == costTotal(buffCost));
            boolean again = (Boolean)call(unit, "buyBuff", new Class<?>[]{StatusEffect.class, ItemModule.class}, effect, coins);
            check("已经解锁的 buff 再买返回 false", !again);

            // ---- 6. ability：解锁 + 单位私有副本 ----
            Object abilityMap = call(unit, "abilityMap", new Class<?>[0]);
            check("type 上登记了至少 1 个 ability", keys(abilityMap).size() > 0);
            Object ability = keys(abilityMap).get(0);
            ItemStack[] abilityCost = (ItemStack[])field(entry(abilityMap, ability), "cost");
            for(ItemStack s : abilityCost) coins.set(s.item, s.amount * 4);
            int beforeAbility = coins.total();
            boolean okAbility = (Boolean)call(unit, "buyAbility", new Class<?>[]{mindustry.entities.abilities.Ability.class, ItemModule.class}, ability, coins);
            check("买 ability 成功", okAbility);
            Object abilityEntry = entry(abilityMap, ability);
            check("ability 的 unlocked 变成 true", (Boolean)field(abilityEntry, "unlocked"));
            Object abilityInstance = field(abilityEntry, "instance");
            check("ability 有单位私有副本（不是 type 上共享的那份）",
                abilityInstance != null && abilityInstance != ability);
            check("买 ability 扣掉正好一份", beforeAbility - coins.total() == costTotal(abilityCost));

            // ---- 6b. Ability 的每个钩子都要接上 ----
            // 游戏自己的 draw / displayBars / death / update 全是遍历 unit.abilities 的：不挂进去，
            // 升级出来的能力就只有"我们自己调的那两下"，画的和面板上的条一概没有。
            boolean attached = false;
            for(mindustry.entities.abilities.Ability a : unit.abilities) if(a == abilityInstance) attached = true;
            check("解锁的能力挂进了 unit.abilities（draw / displayBars / death 都靠它）", attached);
            check("能力数组长度 = 类型自带 " + ut.abilities.size + " + 已解锁 1（" + unit.abilities.length + "）",
                unit.abilities.length == ut.abilities.size + 1);
            check("created() 调过了（AbilityEntry.created）", (Boolean)field(abilityEntry, "created"));
            check("created() 真生效（ForceFieldAbility 会给盾，unit.shield > 0）: " + unit.shield, unit.shield > 0);
            check("升级能力进过 type 的统计（display / addStats 那一路）", typeStatsHasAbilities(ut));

            // 给能力塞一个 data：它得靠我们自己的读写同步（游戏按"类型自带能力的位置"对位传，位置对不上）
            ((mindustry.entities.abilities.Ability)abilityInstance).data = 12.5f;

            // 让单位跑几帧：解锁的 ability 副本要能正常 update 不炸
            int healthBefore = (int)unit.health;
            run(30);
            check("解锁 ability 之后跑 30 帧不炸、单位还活着", unit.isValid() && unit.health > 0);
            check("游戏自己的 update 循环在驱动它（30 帧后盾还在）: " + unit.shield, unit.shield > 0);
            System.out.println("[UPG] 跑完 30 帧：血量 " + healthBefore + " → " + (int)unit.health);

            // ---- 6c. JSON 例子：基础字段和 upgrade* 字段全部写在单位 JSON 里（不需要 JS）----
            UnitType jsonUnit = null;
            for(UnitType u : Vars.content.units()) if(u.name.endsWith("-upgrade-mech-json")) jsonUnit = u;
            check("JSON 单位存在（content/units/upgrade-mech-json.json）: "
                + (jsonUnit == null ? "无" : jsonUnit.name), jsonUnit != null);
            if(jsonUnit != null){
                check("JSON 的 template 生效（类是 UpgradeUnitType）: " + jsonUnit.getClass().getName(),
                    jsonUnit.getClass().getName().endsWith("UpgradeUnitType"));
                check("JSON 写的基础字段生效（health=" + jsonUnit.health + " armor=" + jsonUnit.armor
                    + " hitSize=" + jsonUnit.hitSize + " 武器=" + jsonUnit.weapons.size + " 把）",
                    jsonUnit.health == 900f && jsonUnit.armor == 8f && jsonUnit.hitSize == 13f && jsonUnit.weapons.size >= 1);
                // 类型上是字段（单位上才是方法）
                Object jsonAbilities = field(jsonUnit, "abilityMap");
                Object jsonBuffs = field(jsonUnit, "buffMap");
                check("JSON 里 upgradeSpeed/upgradeArmor 生效（speedAmount=" + field(jsonUnit, "speedAmount")
                    + " armorAmount=" + field(jsonUnit, "armorAmount") + "）",
                    (Float)field(jsonUnit, "speedAmount") > 0f && (Float)field(jsonUnit, "armorAmount") > 0f);
                check("JSON 里的 cost 生效（速度/护甲都有消耗）",
                    ((ItemStack[])field(jsonUnit, "speedCost")).length > 0 && ((ItemStack[])field(jsonUnit, "armorCost")).length > 0);
                check("JSON 里 upgradeAbilities 生效（" + keys(jsonAbilities).size() + " 条）", keys(jsonAbilities).size() == 2);
                check("JSON 里 upgradeBuffs 生效（" + keys(jsonBuffs).size() + " 条）", keys(jsonBuffs).size() == 2);
                check("解析器把实体换成了 MechUpgradeUnit", jsonUnit.constructor.get().getClass().getName().endsWith("MechUpgradeUnit"));
                check("JSON 里的 levels 换算生效（速度上限 = 基础 + 每级×级数: " + (Float)field(jsonUnit, "speedAmount") + "×6）",
                    Math.abs(floatField(jsonUnit, "maxSpeed")
                        - (jsonUnit.speed + floatField(jsonUnit, "speedAmount") * 6f)) < 0.001f);

                // 造一台 JSON 单位的实例：买一级速度，验证这套 JSON+JS 出来的单位真能用
                Unit ju = jsonUnit.create(Team.sharded);
                ju.set(core.x - 40f, core.y);
                ju.add();
                run(5);
                ItemStack[] juSpeedCost = (ItemStack[])field(ju, "speedCost");
                for(ItemStack s : juSpeedCost) coins.set(s.item, s.amount * 5);
                int coinsBefore = coins.total();
                boolean juBuy = (Boolean)call(ju, "buySpeed", new Class<?>[]{ItemModule.class}, coins);
                check("JSON+JS 的单位能正常买升级（速度次数 " + intCall(ju, "speedTimers") + "）",
                    juBuy && intCall(ju, "speedTimers") == 1 && coinsBefore - coins.total() == costTotal(juSpeedCost));
            }

            // ---- 7. 存读档：实体还是 MechUpgradeUnit，升级数据都在 ----
            int speedTimers = intCall(unit, "speedTimers"), armorTimers = intCall(unit, "armorTimers");
            float armorNow = unit.armor();
            Fi file = Core.files.absolute("/tmp/mp_dsd/upgrade-roundtrip.msav");
            SaveIO.save(file);
            SaveIO.load(file);
            run(30);

            Unit loaded = null;
            for(Unit u : Groups.unit) if(u.type == ut) loaded = u;
            check("读档后单位还在", loaded != null);
            if(loaded != null){
                check("读档后实体还是 MechUpgradeUnit（classId 注册生效）: " + loaded.getClass().getName(),
                    loaded.getClass().getName().endsWith("MechUpgradeUnit"));
                check("读档后速度次数还在（" + intCall(loaded, "speedTimers") + " = " + speedTimers + "）",
                    intCall(loaded, "speedTimers") == speedTimers);
                check("读档后护甲次数还在（" + intCall(loaded, "armorTimers") + " = " + armorTimers + "）",
                    intCall(loaded, "armorTimers") == armorTimers);
                check("读档后 armor 字段还原（" + loaded.armor() + " ≈ " + armorNow + "）", Math.abs(loaded.armor() - armorNow) < 0.01f);
                Object loadedBuffMap = call(loaded, "buffMap", new Class<?>[0]);
                check("读档后 buff 还是解锁的", (Boolean)field(entry(loadedBuffMap, effect), "unlocked"));
                Object loadedAbilityMap = call(loaded, "abilityMap", new Class<?>[0]);
                Object loadedEntry = entry(loadedAbilityMap, ability);
                check("读档后 ability 还是解锁的", (Boolean)field(loadedEntry, "unlocked"));
                Object loadedInstance = field(loadedEntry, "instance");
                boolean reattached = false;
                for(mindustry.entities.abilities.Ability a : loaded.abilities) if(a == loadedInstance) reattached = true;
                check("读档后能力重新挂回 unit.abilities", reattached);
                check("读档后 created 补调过（盾又满了）: " + loaded.shield, loaded.shield > 0);
                check("读档后能力的 data 保住了（应为 12.5）: "
                    + ((mindustry.entities.abilities.Ability)loadedInstance).data,
                    Math.abs(((mindustry.entities.abilities.Ability)loadedInstance).data - 12.5f) < 0.001f);
            }

            System.out.println("[UPG] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){
            t.printStackTrace();
            System.exit(2);
        }
    }
}
