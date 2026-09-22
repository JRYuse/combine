package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.UnitType; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："客户端进行单位合体会变成幽灵单位"。
 *
 * <p>根因是两条通道没有先后保证：巨兽是靠**实体快照**出现的（UDP、可能先到），
 * 而"成员已经被收进巨兽"是靠 {@code Call.unitDespawn} 通知的（可靠通道、可能后到）。
 * 中间那段时间客户端画面上就是"巨兽已经在了，两个成员还站在旁边" —— 看着就是幽灵单位。
 *
 * <p>改法：成员块里带上**成员的原始 id**（格式加版本字节，老存档照样读），客户端读到成员表后
 * 把这几个 id 对应的世界实体当场摘掉（同 id **且同类型**才摘，防 id 被复用误删）。
 *
 * <p>这个测试人为造出那个竞态：融合出巨兽后，把成员以**原来的 id** 重新放回世界（模拟"快照先到、
 * 删除后到"），再调 {@link #removeGhostMembers} —— 幽灵必须被摘干净；而"同 id 但类型不同"的
 * 单位必须留着（不能误删别的单位）。
 */
public class MegaGhostMemberTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_cj/data";
    static int pass = 0, fail = 0;
    static ClassLoader ml;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){}; Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MGM] " + t); };
        new HeadlessApplication(new MegaGhostMemberTest(), t -> t.printStackTrace());
    }

    static void check(String n, boolean ok){ System.out.println("[MGM] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i = 0; i < f; i++){ Time.delta = 1f; Vars.logic.update(); } }
    static Building place(Block b, int x, int y, Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte)0, team, null);
        Building bu = Vars.world.build(ax, ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu;
    }
    static int removeGhosts(Unit mega){
        try{ return (Integer)mega.getClass().getMethod("removeGhostMembers").invoke(mega); }catch(Throwable t){ return -1; }
    }
    @SuppressWarnings("unchecked")
    static Seq<Object> memberPayloads(Unit mega){
        try{ return (Seq<Object>)mega.getClass().getMethod("members").invoke(mega); }catch(Throwable t){ return new Seq<>(); }
    }
    static Unit payloadUnit(Object payload){
        try{ return (Unit)payload.getClass().getField("unit").get(payload); }catch(Throwable t){ return null; }
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

            Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.waves = false;
            Vars.state.rules.canGameOver = false;
            Vars.logic.play();
            run(20);
            for(int y = 25; y < 175; y++) for(int x = 10; x < 250; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
            }
            run(10);

            int ox = -1, oy = -1;
            outer:
            for(int y = 45; y < 140; y++){
                for(int x = 40; x < 200; x++){
                    boolean ok = true;
                    for(int dy = -2; dy <= 2 && ok; dy++) for(int dx = -3; dx <= 3; dx++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ System.out.println("[MGM] 没找到陆地"); System.exit(3); }
            place(Blocks.coreShard, ox + 16, oy + 10, Team.sharded);
            run(10);

            // 融合两只 dagger（成员 = 279xxx 那两只，被收进巨兽后就不在世界里了）
            Unit a = UnitTypes.dagger.create(Team.sharded);
            a.set(ox * 8f - 15f, oy * 8f);
            a.add();
            Unit b = UnitTypes.dagger.create(Team.sharded);
            b.set(ox * 8f + 15f, oy * 8f);
            b.add();
            run(10);
            int idA = a.id(), idB = b.id();

            Unit mega = null;
            try{
                mega = (Unit)Class.forName("combine.units.UnitComboMerge", true, ml)
                    .getMethod("merge", Unit.class).invoke(null, a);
            }catch(Throwable t){ Log.err("[MGM] 融合失败", t); }
            if(mega == null){ System.out.println("[MGM] 融合失败"); System.exit(3); }
            run(10);
            check("融合成功（成员 " + memberPayloads(mega).size + "）", memberPayloads(mega).size == 2);
            check("融合后两只成员都不在世界里（服务端语义）",
                Groups.unit.getByID(idA) == null && Groups.unit.getByID(idB) == null);

            // ---- 人为造竞态：把成员按**原 id** 重新放回世界 = "快照先到、删除通知还没到" ----
            Unit ghostA = UnitTypes.dagger.create(Team.sharded);
            ghostA.set(ox * 8f - 15f, oy * 8f);
            ghostA.id(idA);
            ghostA.add();
            Unit ghostB = UnitTypes.dagger.create(Team.sharded);
            ghostB.set(ox * 8f + 15f, oy * 8f);
            ghostB.id(idB);
            ghostB.add();
            // 再放一只**同 id 但类型不同**的（防止 id 被复用后误删别的单位）
            Unit other = UnitTypes.mace.create(Team.sharded);
            other.set(ox * 8f, oy * 8f + 200f);
            other.id(idA + 10000);
            other.add();
            run(5);
            check("竞态已摆好：两只幽灵成员 + 巨兽同时在世界上",
                Groups.unit.getByID(idA) != null && Groups.unit.getByID(idB) != null);

            int removed = removeGhosts(mega);
            System.out.println("[MGM] removeGhostMembers 摘掉 " + removed + " 只；"
                + "idA=" + idA + " 还在=" + (Groups.unit.getByID(idA) != null)
                + "，idB=" + idB + " 还在=" + (Groups.unit.getByID(idB) != null));
            check("两只幽灵成员都被摘掉", Groups.unit.getByID(idA) == null && Groups.unit.getByID(idB) == null);
            check("巨兽自己没被误删", mega.isAdded() && Groups.unit.getByID(mega.id()) == mega);
            check("别的单位（id 不同/类型不同）没被误删", other.isAdded() && Groups.unit.getByID(other.id()) == other);

            // ---- 反向保护：id 撞了但类型不同 → 不能摘 ----
            Unit sameIdOtherType = UnitTypes.mace.create(Team.sharded);
            sameIdOtherType.set(ox * 8f, oy * 8f - 200f);
            sameIdOtherType.id(idA);          // 与"成员 A 的 id"相同，但类型是 mace
            sameIdOtherType.add();
            run(3);
            int removed2 = removeGhosts(mega);
            System.out.println("[MGM] id 相同但类型不同：removeGhostMembers 摘掉 " + removed2 + " 只");
            check("同 id 但类型不同 → 不摘（防 id 复用误删）",
                Groups.unit.getByID(idA) == sameIdOtherType && sameIdOtherType.isAdded());

            System.out.println("[MGM] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){
            t.printStackTrace();
            System.exit(2);
        }
    }
}
