package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.meta.*;

/**
 * 组合炮塔（combine.turret.CombinedTurret）的信息面板必须有"弹药/子弹"那一段。
 *
 * 本类单继承 Turret（power/laser 两种模式各自复刻原版子类行为），
 * 原版 PowerTurret.setStats 里的 Stat.ammo(= shootType) 就漏掉了 ——
 * 用户报的"CombineTurret 的 setStat 里没有子弹的信息"。
 */
public class TurretStatAmmoTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_coop/data";
    static int pass = 0, fail = 0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[TS] "+t); };
        new HeadlessApplication(new TurretStatAmmoTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[TS] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }

    /** 这个方块的信息面板里有没有"弹药"（Stat.ammo）那一段。 */
    static boolean hasAmmoStat(Block b){
        try{
            Stats s = b.computeStats();
            var map = s.toMap();
            if(map == null) return false;
            for(var cat : map)
                if(cat.value != null && cat.value.containsKey(Stat.ammo)) return true;
        }catch(Throwable t){ Log.err("[TS] computeStats 失败 " + b.name, t); }
        return false; }

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

        int combos = 0, withAmmo = 0, vanillaPower = 0, vanillaWithAmmo = 0;
        String sample = null;
        for(Block b : Vars.content.blocks()){
            String cn = b.getClass().getName();
            if(cn.startsWith("combine.turret.CombinedTurret")){
                combos++;
                boolean ammo = hasAmmoStat(b);
                if(ammo) withAmmo++;
                if(sample == null){
                    sample = b.name + "(" + cn + ")";
                    Object shoot = null;
                    try{ shoot = b.getClass().getField("shootType").get(b); }catch(Throwable ignored){}
                    System.out.println("[TS] 样本=" + sample + " shootType=" + shoot + " 有弹药段=" + ammo);
                }
            }
            if(cn.equals("mindustry.world.blocks.defense.turrets.PowerTurret")
                || cn.equals("mindustry.world.blocks.defense.turrets.LaserTurret")){
                vanillaPower++;
                if(hasAmmoStat(b)) vanillaWithAmmo++;
            }
        }
        System.out.println("[TS] 组合炮塔 " + combos + " 个，其中带子弹信息 " + withAmmo
            + "；原版基准 Power/Laser " + vanillaPower + " 个，带子弹信息 " + vanillaWithAmmo);
        check("数据集里有组合炮塔(" + combos + ")", combos > 0);
        check("每个组合炮塔的信息面板都有子弹(ammo)信息", combos > 0 && withAmmo == combos);
        // 本数据集里原版 PowerTurret/LaserTurret 已被组合方块顶掉（就是上面那 13 个），
        // 拿不到"原版实例"当基线时如实跳过，不把"没验到"写成"过了"。
        if(vanillaPower == 0)
            System.out.println("[TS] SKIP 原版基准：本数据集的原版 PowerTurret/LaserTurret 已被组合方块替换");
        else
            check("和原版一致（原版 PowerTurret/LaserTurret 也都带子弹信息）",
                vanillaWithAmmo == vanillaPower);

        System.out.println("[TS] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
