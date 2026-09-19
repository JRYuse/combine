package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts;
import mindustry.world.blocks.storage.CoreBlock; import mindustry.world.Block; import mindustry.type.ItemStack;

/**
 * 发射蓝图（内置 loadout）与核心的对应关系。
 *
 * 客户端真实加载顺序：createBaseContent → createModContent → **schematics.load()** → Mod.init()（替换方块）。
 * 这里照抄这个顺序，检查每个（组合）核心还能不能查到自己那份内置发射蓝图。
 * 查不到的话 LaunchLoadoutDialog 会退回 Serpulo 的 core-shard 蓝图 ——
 * 于是"在 Erekir 发射核心却要铜和铅"（用户报的）。
 */
public class LaunchLoadoutKeyTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new LaunchLoadoutKeyTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[LK] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        // ★ 和客户端一致的顺序
        Vars.content.createBaseContent();
        Vars.mods.loadScripts();
        Vars.content.createModContent();
        if(Vars.schematics == null) Vars.schematics = new mindustry.game.Schematics();
        Vars.schematics.load();                 // ← 客户端在这一步 assets.load(schematics)
        Vars.content.init();
        Vars.mods.eachClass(Mod::init);         // ← 这里才做方块替换
        if(Vars.logic==null) Vars.logic=new Logic();
        if(Vars.netServer==null) Vars.netServer=new NetServer();
        if(Vars.netClient==null) Vars.netClient=new NetClient();

        System.out.println("[LK] 内置蓝图: shard=" + Loadouts.basicShard.name() + " foundation=" + Loadouts.basicFoundation.name()
            + " nucleus=" + Loadouts.basicNucleus.name() + " bastion=" + Loadouts.basicBastion.name());

        StringBuilder sb = new StringBuilder();
        for(Block b : Vars.content.blocks()){
            if(!(b instanceof CoreBlock core)) continue;
            Schematic def = Vars.schematics.getDefaultLoadout(core);
            sb.append("\n[LK]   核心 ").append(core.name).append(" (cls=").append(core.getClass().getSimpleName()).append(')')
              .append(" 默认蓝图=").append(def == null ? "null" : def.name())
              .append(" 候选数=").append(Vars.schematics.getLoadouts(core).size);
            if(def != null) sb.append(" 需求=").append(req(def));
        }
        System.out.println("[LK] 各核心的默认发射蓝图:" + sb);

        // Erekir 核心：默认蓝图必须存在、且不要求铜/铅
        CoreBlock bastion = null;
        for(Block b : Vars.content.blocks()) if(b instanceof CoreBlock core && core.name.equals("core-bastion")) bastion = core;
        if(bastion != null && bastion.getClass().getName().startsWith("combine.")){
            Schematic def = Vars.schematics.getDefaultLoadout(bastion);
            check("Erekir 核心（combo）查得到默认发射蓝图", def != null);
            if(def != null){
                boolean cu = false, pb = false;
                for(ItemStack st : def.requirements()){
                    if(st.item == Items.copper) cu = true;
                    if(st.item == Items.lead) pb = true;
                }
                check("Erekir 默认蓝图不含铜/铅（铜=" + cu + " 铅=" + pb + "）", !cu && !pb);
            }
        }else{
            System.out.println("[LK] core-bastion 没被替换？cls=" + (bastion==null?null:bastion.getClass().getName()));
        }
        // Serpulo 核心：默认蓝图还是 shard 那套
        CoreBlock shard = null;
        for(Block b : Vars.content.blocks()) if(b instanceof CoreBlock core && core.name.equals("core-shard")) shard = core;
        if(shard != null){
            Schematic def = Vars.schematics.getDefaultLoadout(shard);
            check("Serpulo 核心也查得到默认蓝图", def != null);
        }

        // ★ 照抄 PlanetDialog.playSelected 的选核心逻辑（含 allowLaunchSchematics 分支），
        //   只看两个正经星球（serpulo/erekir）：发射核心时要求的物品必须该星球上有
        //   （Erekir 要铜/铅就是这一步出的：defaultCore 还攥着被替换掉的旧核心 → 查不到默认蓝图 →
        //    原版兜底成 core-shard 的蓝图）。
        int bad = 0, fellBack = 0;
        for(mindustry.type.Planet planet : Vars.content.planets()){
            if(!planet.name.equals("erekir") && !planet.name.equals("serpulo")) continue;
            for(mindustry.type.Sector sector : planet.sectors){
                mindustry.game.SectorInfo info = sector.info;
                if(info == null) continue;
                Block cb = null;
                if(sector.allowLaunchSchematics() && info.bestCoreType instanceof CoreBlock bc) cb = bc;
                if(cb == null && planet.defaultCore instanceof CoreBlock dc) cb = dc;
                if(!(cb instanceof CoreBlock core)) continue;
                Schematic def = Vars.schematics.getDefaultLoadout(core);
                Schematic sel = def;
                boolean fallback = false;
                if(sel == null){
                    var list = Vars.schematics.getLoadouts().get((CoreBlock)Blocks.coreShard);
                    if(list == null || list.isEmpty()) continue;
                    sel = list.first();
                    fallback = true;
                }
                if(fallback){
                    fellBack++;
                    if(fellBack <= 3) System.out.println("[LK]   ⚠ " + planet.name + " / " + sector.name()
                        + " 核心=" + core.name + " 查不到默认蓝图（原版会退回 core-shard）");
                }
                StringBuilder off = new StringBuilder();
                for(ItemStack st : sel.requirements()) if(!st.item.isOnPlanet(planet)) off.append(st.item.name).append(' ');
                if(off.length() > 0){
                    bad++;
                    if(bad <= 6) System.out.println("[LK]   ⚠ " + planet.name + " / " + sector.name() + " 核心=" + core.name
                        + (core == planet.defaultCore ? "(星球默认核心)" : "") + " 蓝图=" + sel.name() + " 要求星球上没有的: " + off);
                }
            }
        }
        System.out.println("[LK] serpulo/erekir 发射：查不到默认蓝图而退回的次数=" + fellBack + "，会要求星球上没有的物品的 sector 数=" + bad);
        check("serpulo/erekir 发射核心不会再退回 core-shard 蓝图", fellBack == 0);
        check("serpulo/erekir 发射核心不会要求星球上没有的物品（Erekir 不该要铜/铅）", bad == 0);
        for(mindustry.type.Planet planet : Vars.content.planets()){
            if(!planet.name.equals("erekir") && !planet.name.equals("serpulo")) continue;
            if(!(planet.defaultCore instanceof CoreBlock dc)) continue;
            System.out.println("[LK] " + planet.name + " 默认核心=" + dc.name + " 类=" + dc.getClass().getSimpleName()
                + " 默认蓝图=" + (Vars.schematics.getDefaultLoadout(dc) == null ? "null" : Vars.schematics.getDefaultLoadout(dc).name()));
            check(planet.name + " 的 Planet.defaultCore 指向组合核心", dc.getClass().getName().startsWith("combine."));
            check(planet.name + " 的默认核心查得到默认发射蓝图", Vars.schematics.getDefaultLoadout(dc) != null);
        }

        System.out.println("[LK] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
    static String req(Schematic s){
        StringBuilder sb = new StringBuilder();
        for(ItemStack st : s.requirements()) sb.append(st.item.name).append('x').append(st.amount).append(' ');
        return sb.length() == 0 ? "(空)" : sb.toString();
    }
}
