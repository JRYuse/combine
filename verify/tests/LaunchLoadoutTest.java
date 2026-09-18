package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.type.Planet; import mindustry.type.Sector;

/**
 * 发射界面（{@code LaunchLoadoutDialog.show}）不能因为"核心被换成组合实例"而崩。
 *
 * 原版流程：{@code ContentLoader.createBaseContent()} 里 {@code Loadouts.load()} 按当时的
 * {@code Blocks.coreShard} 读内置蓝图（basicShard…），所以 {@code Loadouts.basicShard.tiles}
 * 里存的是**替换前**的原版核心实例；{@code Schematics.load()} 稍后才把内置蓝图登记进
 * {@code schematics.loadouts}，键就是这个旧实例。
 * 组合工厂把 {@code Blocks.coreShard} 换成组合核心（{@link combine.storage.CombinedCoreBlock}），
 * 于是：
 *   {@code universe.getLoadout(组合核心)} → 空 → 返回 null
 *   {@code schematics.getLoadouts().get(组合核心)} → null → 发射界面里 {@code .first()} NPE。
 */
public class LaunchLoadoutTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new LaunchLoadoutTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[LL] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static String id(Object o){ return o == null ? "null" : (o.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(o))); }

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        // 与 ClientLauncher 一致：Schematics 在内容创建前就 new 出来，load() 发生在内容装配之后
        Vars.schematics = new mindustry.game.Schematics();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        Vars.mods.eachClass(Mod::init);

        CoreBlock shard = (CoreBlock)Vars.content.block("core-shard");
        System.out.println("[LL] Blocks.coreShard=" + id(Blocks.coreShard) + " content.block(core-shard)=" + id(shard)
            + " 同一个实例=" + (Blocks.coreShard == shard));
        System.out.println("[LL] Loadouts.basicShard.findCore()=" + id(Loadouts.basicShard.findCore())
            + " 与核心同实例=" + (Loadouts.basicShard.findCore() == shard));
        ClassLoader ml0 = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        Class<?> repl = Class.forName("combine.Replacer", true, ml0);
        arc.struct.ObjectMap<Block, Block> replaced =
            (arc.struct.ObjectMap<Block, Block>) repl.getField("replaced").get(null);
        System.out.println("[LL] 替换表大小=" + replaced.size
            + " 内置蓝图 tiles=" + Loadouts.basicShard.tiles.size
            + " 蓝图里的旧实例残留=" + replaced.containsKey(Loadouts.basicShard.findCore()));

        // 真正跑一遍原版的蓝图库加载（内置蓝图在这里登记进 loadouts 表）
        Vars.schematicDirectory.mkdirs();
        Vars.schematics.load();
        System.out.println("[LL] schematics.loadouts 键=" + keyList(Vars.schematics.getLoadouts()));

        Schematic sel = Vars.universe.getLoadout(shard);
        Seq<Schematic> all = Vars.schematics.getLoadouts().get(shard);
        Schematic def = Vars.schematics.getDefaultLoadout(shard);
        System.out.println("[LL] universe.getLoadout(核心)=" + (sel == null ? "null" : sel.file == null ? "内置蓝图" : sel.file.name())
            + " getLoadouts().get(核心)=" + (all == null ? "null" : all.size + " 张")
            + " getDefaultLoadout(核心)=" + (def == null ? "null" : "有"));

        check("内置发射蓝图已指向组合核心（否则 LaunchLoadoutDialog 里 getLoadout 一定为空）", sel != null);
        check("getLoadouts().get(核心) 不为 null（发射界面第 163 行的 .first()）", all != null);
        check("getDefaultLoadout(核心) 不为 null", def != null);

        // 复刻发射界面里那行代码：selected == null 时兜底取内置蓝图
        boolean crashed = false;
        try{
            if(sel == null) sel = Vars.schematics.getLoadouts().get((CoreBlock)Blocks.coreShard).first();
        }catch(Throwable t){
            crashed = true;
            System.out.println("[LL] 复刻发射界面兜底行 → " + t);
        }
        check("发射界面的兜底行不崩", !crashed && sel != null);

        // —— PlanetDialog.playSelected() 的真实入参：from.info.bestCoreType ——
        // 这条 sector info 是 Planet.load()（内容装配之前）按名字反查出来的，
        // 不修的话会停在旧核心实例上（发射界面拿它去查蓝图库必然查不到）。
        Sector from = null;
        for(Planet p : Vars.content.planets()) for(Sector s : p.sectors) if(from == null) from = s;
        Block best = from == null ? null : from.info.bestCoreType;
        System.out.println("[LL] 首个 sector 的 bestCoreType=" + id(best)
            + " 是旧实例=" + (best != null && replaced.containsKey(best))
            + " getLoadout(bestCoreType)=" + (best instanceof CoreBlock cb ? Vars.universe.getLoadout(cb) : "非核心"));
        check("sector 的 bestCoreType 已换成组合核心", best != null && !replaced.containsKey(best));
        check("playSelected 的入参能查到蓝图（弹出发射界面不会崩）",
            best instanceof CoreBlock cb2 && Vars.universe.getLoadout(cb2) != null);

        // 兜底路径：模拟"有工具/模组在内容装配前就 schematics.load() 过"——
        // 键停在旧核心实例上，remapLoadoutKeys() 必须能把它换回组合核心。
        boolean hasRemap;
        try{ repl.getMethod("remapLoadoutKeys"); hasRemap = true; }catch(Throwable t){ hasRemap = false; }
        if(!hasRemap){
            System.out.println("[LL] 这份 combine 没有 remapLoadoutKeys()，跳过兜底路径检查");
        }else{
        Block oldShard = null;
        for(var e : replaced) if(e.value == shard) oldShard = e.key;
        var map = Vars.schematics.getLoadouts();
        Seq<Schematic> list = map.remove(shard);
        map.put((CoreBlock)oldShard, list);
        System.out.println("[LL] 人为把键换回旧实例后 get(核心)="
            + (map.get(shard) == null ? "null" : map.get(shard).size + " 张"));
        repl.getMethod("remapLoadoutKeys").invoke(null);
        check("兜底：旧键被 remapLoadoutKeys 换回组合核心",
            Vars.schematics.getLoadouts().get(shard) != null
                && Vars.schematics.getDefaultLoadout(shard) != null);
        }

        System.out.println("[LL] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }

    static String keyList(Object map){
        StringBuilder sb = new StringBuilder();
        for(var e : ((arc.struct.ObjectMap<CoreBlock, Seq<Schematic>>)map))
            sb.append(e.key.name).append('(').append(e.key.getClass().getSimpleName()).append("):").append(e.value.size).append(' ');
        return sb.length() == 0 ? "空" : sb.toString();
    }
}
