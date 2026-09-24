package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock;
import mindustry.entities.units.BuildPlan;
import java.lang.reflect.Field;

/**
 * 用户报：**联机时服务端和客户端拐角处水管/传送带方向对不上**（并提示"可能是多线程建造没有
 * 正确发送建造的网络通信包"）。
 *
 * 根因：本仓库的建造武器是"队首那格之外"的施工者，它原先直接调 `Build.beginPlace /
 * Build.beginBreak` —— 那**只改本机世界**。原版 {@code BuilderComp} 走的是
 * {@code Call.beginPlace / Call.beginBreak}（服务端权威执行 + 转发给所有客户端），
 * 而武器挂座属于实体组件、**客户端也会跑**（原版就这么设计的：弹道/建造在客户端预测）。
 * 于是两端各自就地改世界、谁也不知道谁；原地改方向(quickRotate)与修废墟这类计划原版是
 * **当场完成、不生成施工格**的，没有任何后续快照能把方向纠正回来 —— 表现就是两端方向永久对不上。
 *
 * 判定（本测试）：同一个"原地改方向"计划，两种身份各跑 30 tick：
 *   · 服务端身份（net.server()）→ 传送带必须被转过去（对照组，证明这条路径真的在工作）；
 *   · 客户端身份（net.client()）→ **本机方向必须一动不动**（要等服务端转发过来的包）。
 * 修前客户端那一跑会把本机传送带转过去（变红）；修后必须保持 0。
 */
public class BuildWeaponNetSyncTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new BuildWeaponNetSyncTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[BWN] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }

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
        run(10);

        // 挂上建造武器（正常在 ClientLoad/ServerLoad 时挂，headless 里手动来一次）
        ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        Class.forName("combine.Main", true, ml).getMethod("addBuildWeapons").invoke(null);

        // 找一格能放传送带的空地（validPlace 只认核心半径内的格子，天然就在建造武器射程里）
        int px = -1, py = -1;
        outer:
        for(int y = 1; y < Vars.world.height() - 1; y++){
            for(int x = 1; x < Vars.world.width() - 1; x++){
                if(Build.validPlace(Blocks.conveyor, Team.sharded, x, y, 0)){ px = x; py = y; break outer; }
            }
        }
        check("找到可放传送带的格子（" + px + "," + py + "）", px >= 0);
        // 直接"完工"放一台传送带（走原版 constructFinish），别留施工格：本测试要的是
        // "原地改方向"那条 quickRotate 路径（原版当场做完、不生成施工格）。
        ConstructBlock.constructFinish(Vars.world.tile(px, py), Blocks.conveyor, null, (byte)0, Team.sharded, null);
        Building placed = Vars.world.build(px, py);
        check("铺好传送带，方向=0（实际=" + Vars.world.tile(px, py).block().name + " rot=" + rot(px, py)
            + " 类=" + (placed == null ? "-" : placed.getClass().getSimpleName()) + "）",
            Vars.world.tile(px, py).block() == Blocks.conveyor && rot(px, py) == 0);

        // 核心机（核心尺寸 → 本模组给它挂了 3 把建造武器）+ 一条"原地改方向"计划
        Unit unit = UnitTypes.alpha.create(Team.sharded);
        unit.set(px * 8f + 4f, py * 8f + 4f);
        unit.add();
        int weapons = 0;
        for(Weapon w : unit.type.weapons) if(w.getClass().getName().equals("combine.MultiBuildWeapon")) weapons++;
        check("核心机装着本模组的建造武器（" + weapons + " 把）", weapons >= 2);

        // ——服务端身份：这一格必须真的被转过去（对照组）——
        unit.addBuild(new BuildPlan(px, py, 1, Blocks.conveyor, null));
        netRole(true);
        run(30);
        check("服务端身份跑 30 tick：传送带被转到方向 1（对照组，证明武器路径真的在干活）", rot(px, py) == 1);

        // ——客户端身份：本机绝不能自己转（要等服务端转发过来的 Call.beginPlace）——
        Build.beginPlace(null, Blocks.conveyor, Team.sharded, px, py, 0, null);
        check("复位：方向回到 0", rot(px, py) == 0);
        unit.addBuild(new BuildPlan(px, py, 1, Blocks.conveyor, null));
        netRole(false);
        run(30);
        check("客户端身份跑 30 tick：本机方向一动不动（修前会自己转成 1）", rot(px, py) == 0);

        System.out.println("[BWN] ===== 结果: PASS=" + pass + " FAIL=" + fail + " =====");
        System.exit(fail == 0 ? 0 : 1);
      }catch(Throwable t){ System.out.println("[BWN] 崩了: " + t); t.printStackTrace(); System.exit(2); }
    }

    static int rot(int x, int y){
        Building b = Vars.world.build(x, y);
        return b == null ? -1 : b.rotation;
    }

    /** 把 Vars.net 摆成"服务端身份"或"客户端身份"（headless 里没有真连接，靠这两个开关即可）。 */
    static void netRole(boolean server){
        try{
            Field f = Net.class.getDeclaredField("server"); f.setAccessible(true); f.setBoolean(Vars.net, server);
            Field g = Net.class.getDeclaredField("active"); g.setAccessible(true); g.setBoolean(Vars.net, true);
        }catch(Throwable t){ System.out.println("[BWN] 摆身份失败: " + t); }
    }
}
