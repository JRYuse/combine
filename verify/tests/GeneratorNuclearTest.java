package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.*;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 组合发电机 nuclear 模式：发电效率必须是
 *   min(目标物品 / 该种发电机（核反应堆）的总容量, 1)
 * 而不是「目标物品 / 整组物品池容量」。
 *
 * 用户报的：核反应堆组合里塞进一台容量极大的发电机后，池子容量被撑大，
 * 想跑满效率得喂进去巨量燃料。
 *
 * 这里用「把另一台发电机的 block.itemCapacity 临时改成 100000」来复现"容量极大的建筑"：
 * 组合池容量变成 10 + 100000，核容量仍是 10。
 */
public class GeneratorNuclearTest implements ApplicationListener{
  static String dataDir="/tmp/mp_coop/data";
  static int pass=0, fail=0;

  public static void main(String[] a){ if(a.length>0) dataDir=a[0];
    Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
    Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
    new HeadlessApplication(new GeneratorNuclearTest(), t->t.printStackTrace()); }

  static void check(String n, boolean ok){ System.out.println("[GN] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
  static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }

  static Building place(Block b,int x,int y,Team team){
    int size = Math.max(b.size, 1);
    int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
    mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
    mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
    Building bu = Vars.world.build(ax,ay);
    if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
    return bu; }

  static Object field(Object o, String name){
    try{
      Class<?> c = o.getClass();
      while(c != null){
        try{ var f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
        catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
      }
    }catch(Throwable ignored){}
    return null; }

  static float fnum(Object o, String name){ Object v = field(o, name); return v instanceof Number n ? n.floatValue() : -1f; }
  static int inum(Object o, String name){ Object v = field(o, name); return v instanceof Number n ? n.intValue() : -1; }
  static float eff(Building b){ return fnum(b, "productionEfficiency"); }
  static void fuel(Building b, Item item, int n){ b.items.set(item, n); }
  static String mode(Block b){
    Object m = field(b, "mode");
    return m == null ? "?" : m.toString(); }

  static Block find(String name){
    for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b;
    return null; }

  /** 第一个非核、非冲击的发电机（用来当"另一台发电机"）。 */
  static Block otherGenerator(){
    for(Block b : Vars.content.blocks()){
      if(!b.getClass().getName().endsWith("CombinedGenerator")) continue;
      String m = mode(b);
      if(m.equals("consume") && b.itemCapacity <= 20) return b;
    }
    return null; }

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

      Block nuke = find("thorium-reactor");
      if(nuke == null){ System.out.println("[GN] 没有 thorium-reactor"); System.exit(3); }
      System.out.println("[GN] thorium-reactor 类=" + nuke.getClass().getName() + " mode=" + mode(nuke)
          + " itemCapacity=" + nuke.itemCapacity);
      check("核反应堆被替换成组合发电机", nuke.getClass().getName().endsWith("CombinedGenerator"));
      check("核反应堆是 nuclear 模式", mode(nuke).equals("nuclear"));

      Block other = otherGenerator();
      if(other == null){ System.out.println("[GN] 找不到另一台可组合的发电机"); System.exit(3); }
      int otherBaseCap = other.itemCapacity;
      System.out.println("[GN] 另一台发电机=" + other.name + " itemCapacity=" + otherBaseCap);

      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.logic.play();
      run(20);
      for(int y=40;y<160;y++) for(int x=20;x<260;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
      run(5);

      int nsz = Math.max(nuke.size, 1);
      int osz = Math.max(other.size, 1);

      // ---------- 场景 1：核反应堆 + 一台容量极大的发电机 ----------
      other.itemCapacity = 100000;
      Building n1 = place(nuke, 60, 60, Team.sharded);
      Building o1 = place(other, 60 + nsz, 60, Team.sharded);
      run(40);

      int poolCap = inum(n1, "comboTotalItemCap");
      int nukeCap = inum(n1, "comboNuclearItemCap");
      System.out.println("[GN] 场景1: 同池=" + (n1.items == o1.items) + " 池容量=" + poolCap
          + " 核容量=" + nukeCap + " 单台核容量=" + nuke.itemCapacity);
      check("两台共用同一个物品池", n1.items == o1.items);
      check("池容量被大容量建筑撑大 (" + poolCap + ")", poolCap >= 100000);
      check("核容量只算核反应堆自己 (" + nukeCap + ")", nukeCap == nuke.itemCapacity);

      int nukeUnitCap = nuke.itemCapacity;
      fuel(n1, Items.thorium, nukeUnitCap);
      run(1);
      float e1 = eff(n1);
      System.out.println("[GN] 场景1: 喂 " + nukeUnitCap + " 个钍 效率=" + e1
          + "（旧公式 " + (nukeUnitCap * 1f / poolCap) + "）");
      check("喂满核容量(" + nukeUnitCap + ")即满效率", e1 >= 0.999f);
      check("旧公式在同一局面下只有 " + String.format(java.util.Locale.ROOT, "%.5f", nukeUnitCap * 1f / poolCap)
          + "（说明确实换了分母）", nukeUnitCap * 1f / poolCap < 0.5f);

      fuel(n1, Items.thorium, 20000);
      run(1);
      float e2 = eff(n1);
      System.out.println("[GN] 场景1: 喂 20000 个钍 效率=" + e2);
      check("效率不超过 1（20000 个燃料）", e2 <= 1.0001f);
      check("效率仍然拉满", e2 >= 0.999f);

      // 池里燃料低于核容量时，效率按比例（不是按池容量比例）
      fuel(n1, Items.thorium, Math.max(nukeUnitCap / 2, 1));
      run(1);
      float e3 = eff(n1);
      float expect3 = Math.min(Math.max(nukeUnitCap / 2, 1) / (float) nukeUnitCap, 1f);
      System.out.println("[GN] 场景1: 喂 " + Math.max(nukeUnitCap / 2, 1) + " 个钍 效率=" + e3 + " 期望≈" + expect3);
      check("燃料不到核容量时按比例出力", Math.abs(e3 - expect3) < 0.02f);

      // ---------- 场景 2：两台核反应堆 ----------
      other.itemCapacity = otherBaseCap;
      for(int y=40;y<160;y++) for(int x=20;x<260;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
      run(5);
      Building n2a = place(nuke, 60, 60, Team.sharded);
      Building n2b = place(nuke, 60 + nsz, 60, Team.sharded);
      run(40);
      int poolCap2 = inum(n2a, "comboTotalItemCap");
      int nukeCap2 = inum(n2a, "comboNuclearItemCap");
      System.out.println("[GN] 场景2: 同池=" + (n2a.items == n2b.items) + " 池容量=" + poolCap2
          + " 核容量=" + nukeCap2 + " 单台核容量=" + nuke.itemCapacity);
      check("两台核反应堆合成一组", n2a.items == n2b.items);
      check("核容量 = 两台之和 (" + nukeCap2 + " = 2 x " + nuke.itemCapacity + ")", nukeCap2 == nuke.itemCapacity * 2);

      fuel(n2a, Items.thorium, nuke.itemCapacity);
      run(1);
      float h1 = eff(n2a);
      System.out.println("[GN] 场景2: 喂 1 台份(" + nuke.itemCapacity + ") 效率=" + h1);
      check("两台时喂 1 台份只有半效率 (" + h1 + " ≈ 0.5)", Math.abs(h1 - 0.5f) < 0.02f);

      fuel(n2a, Items.thorium, nuke.itemCapacity * 2);
      run(1);
      float h2 = eff(n2a);
      System.out.println("[GN] 场景2: 喂 2 台份(" + nuke.itemCapacity * 2 + ") 效率=" + h2);
      check("两台时喂 2 台份满效率", h2 >= 0.999f);

      fuel(n2a, Items.thorium, nuke.itemCapacity * 50);
      run(1);
      float h3 = eff(n2a);
      System.out.println("[GN] 场景2: 喂 50 台份 效率=" + h3);
      check("多喂也不超过 1", h3 <= 1.0001f);

      // ---------- 场景 3：没燃料 = 不发电 ----------
      fuel(n2a, Items.thorium, 0);
      run(2);
      float h4 = eff(n2a);
      System.out.println("[GN] 场景3: 没燃料 效率=" + h4);
      check("没燃料时效率为 0", h4 <= 0.0001f);

      System.out.println("[GN] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
      System.exit(fail==0?0:1);
    }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
  }
}
