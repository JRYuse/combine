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
