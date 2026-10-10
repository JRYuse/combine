package combine.dbg;

import arc.*;
import arc.backend.headless.HeadlessApplication;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.SaveFileReader;
import mindustry.maps.Map;
import mindustry.mod.*;
import mindustry.net.Net;
import mindustry.ui.Fonts;
import mindustry.world.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * 用户 2026-10-08（第三版）：阴影上的钻头**实际占地也压成 1x1**，一片阴影 = 一个"资源输出站"，
 * 里面的钻头越多产量越大，可以通水加速 —— 本质和合体工厂 / 合体炮台同一套。
 *
 * <p>（模组类不在 javac 的 classpath 上，全部走反射 —— 和别的回归测试同口径。）
 */
public class ShadowDrillTest implements ApplicationListener {
  static String dataDir = "/tmp/mp_coop/data";
  static int pass = 0, fail = 0;
  static ClassLoader ml;
  static Class<?> clsShadow; // combine.production.OreShadow
  static Class<?> clsDrill;  // combine.production.CombinedDrill

  public static void main(String[] a) {
    if (a.length > 0)
      dataDir = a[0];
    Vars.platform = new Platform() {
    };
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> {
      if (l.ordinal() >= arc.util.Log.LogLevel.err.ordinal())
        System.out.println("[SD] " + t);
    };
    new HeadlessApplication(new ShadowDrillTest(), t -> t.printStackTrace());
  }

  static void check(String n, boolean ok) {
    System.out.println("[SD] " + (ok ? "PASS " : "FAIL ") + n);
    if (ok)
      pass++;
    else
      fail++;
  }

  /** 调实例方法（签名不含自己）。 */
  static Object call(Class<?> c, String name, Class<?>[] sig, Object target) {
    try {
      var m = sig == null ? c.getMethod(name) : c.getMethod(name, sig);
      m.setAccessible(true);
      return m.invoke(target);
    } catch (Throwable t) {
      System.out.println("[SD] 调实例 " + c.getSimpleName() + "." + name + " 失败: " + t);
      return null;
    }
  }

  static Object callStatic(Class<?> c, String name, Class<?>[] sig, Object... args) {
    try {
      var m = sig == null ? c.getMethod(name) : c.getMethod(name, sig);
      m.setAccessible(true);
      return m.invoke(null, args);
    } catch (Throwable t) {
      System.out.println("[SD] 调 " + c.getSimpleName() + "." + name + " 失败: " + t);
      return null;
    }
  }

  static int size() {
    return ((Number) callStatic(clsShadow, "size", null)).intValue();
  }

  /** 框一片矿物地板（只认有矿的格子）。 */
  static int mark(int x1, int y1, int x2, int y2) {
    int[] r = (int[]) callStatic(clsShadow, "toggle",
        new Class<?>[] { int.class, int.class, int.class, int.class }, x1, y1, x2, y2);
    return r == null ? -1 : r[0];
  }

  static void run(int f) {
    for (int i = 0; i < f; i++) {
      arc.util.Time.delta = 1f;
      Vars.logic.update();
    }
  }

  static Building place(Block b, int x, int y) {
    int size = Math.max(b.size, 1);
    int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
    mindustry.world.Build.beginPlace(null, b, Team.sharded, ax, ay, 0, null);
    mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte) 0, Team.sharded,
        null);
    Building bu = Vars.world.build(ax, ay);
    if (bu != null) {
      try {
        bu.updateProximity();
      } catch (Throwable ignored) {
      }
      try {
        bu.onProximityUpdate();
      } catch (Throwable ignored) {
      }
    }
    return bu;
  }

  static void clearArea() {
    for (int y = 25; y < 175; y++)
      for (int x = 10; x < 250; x++) {
        Tile t = Vars.world.tile(x, y);
        if (t != null && t.block() != Blocks.air)
          t.setBlock(Blocks.air);
      }
    run(5);
  }

  static void floor(int x0, int y0, int x1, int y1, Block f) {
    for (int y = y0; y <= y1; y++)
      for (int x = x0; x <= x1; x++) {
        Tile t = Vars.world.tile(x, y);
        if (t != null)
          t.setFloor((mindustry.world.blocks.environment.Floor) f);
      }
  }

  static int fieldInt(Object o, String name) {
    try {
      for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        try {
          var f = c.getDeclaredField(name);
          f.setAccessible(true);
          return f.getInt(o);
        } catch (NoSuchFieldException ignored) {
        }
    } catch (Throwable ignored) {
    }
    return Integer.MIN_VALUE;
  }

  static Object fieldObj(Object o, String name) {
    try {
      for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        try {
          var f = c.getDeclaredField(name);
          f.setAccessible(true);
          return f.get(o);
        } catch (NoSuchFieldException ignored) {
        }
    } catch (Throwable ignored) {
    }
    return null;
  }

  static void setField(Object o, String name, Object v) {
    try {
      for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        try {
          var f = c.getDeclaredField(name);
          f.setAccessible(true);
          f.set(o, v);
          return;
        } catch (NoSuchFieldException ignored) {
        }
    } catch (Throwable ignored) {
    }
  }

  @Override
  public void init() {
    try {
      Core.settings.setDataDirectory(Core.files.local(dataDir));
      Vars.loadLocales = false;
      Vars.loadSettings();
      Vars.headless = true;
      Vars.init();
      UI.loadColors();
      Fonts.loadContentIconsHeadless();
      Vars.content.createBaseContent();
      Vars.mods.loadScripts();
      Vars.content.createModContent();
      Vars.content.init();
      Vars.mods.eachClass(Mod::init);
      if (Vars.logic == null)
        Vars.logic = new Logic();
      if (Vars.netServer == null)
        Vars.netServer = new NetServer();
      if (Vars.netClient == null)
        Vars.netClient = new NetClient();
      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.logic.play();
      run(20);
      clearArea();

      Mods.LoadedMod mod = Vars.mods.getMod("combine");
      check("combine 模组已加载", mod != null);
      if (mod == null) {
        System.exit(2);
        return;
      }
      ml = mod.main.getClass().getClassLoader();
      clsShadow = Class.forName("combine.production.OreShadow", true, ml);
      clsDrill = Class.forName("combine.production.CombinedDrill", true, ml);

      // ---------- A：每种钻头有一个"压成 1x1"的隐藏版本（玩家看不到新建筑） ----------
      Block drill = Vars.content.block("mechanical-drill");
      Block small = Vars.content.block("shadow-mechanical-drill");
      check("机械钻头 = " + (drill == null ? "null" : drill.getClass().getSimpleName())
          + "，压格版本 = " + (small == null ? "null" : small.getClass().getSimpleName()),
          drill != null && small != null
              && small.getClass().getName().equals("combine.production.CombinedDrill"));
      if (drill == null || small == null) {
        System.out.println("[SD] RESULT 装配缺失，后面没法测");
        System.exit(2);
        return;
      }
      check("压格版本只占 1 格（size=" + small.size + "）", small.size == 1);
      Number mine = (Number) call(clsDrill, "mineSize", null, small);
      check("压格版本按原来的 2x2 算矿（mineSize=" + mine + "）", mine != null && mine.intValue() == 2);
      check("压格版本不进建造菜单（visibility=" + small.buildVisibility + "）",
          small.buildVisibility == mindustry.world.meta.BuildVisibility.hidden);
      check("压格版本有名字（" + small.localizedName + "）", small.localizedName != null);
      // 【用户 2026-10-10 报"放置合体钻头还不消耗材料"】压格版本必须保留原钻头的造价。
      // 这份 requirements 是两处的唯一口径：客户端 BuilderComp 的"核心有没有料"那一关（缺料就不施工），
      // 以及原版拆除时 ConstructBuild 按 requirements 退料（下面 B2 段锁的就是这一条）。
      {
        mindustry.type.ItemStack[] req = small.requirements;
        StringBuilder sb = new StringBuilder();
        int sum = 0;
        if (req != null)
          for (mindustry.type.ItemStack st : req) {
            sb.append(st.item.name).append('=').append(st.amount).append(' ');
            sum += st.amount;
          }
        System.out.println("[SD] 压格版本造价: [" + sb.toString().trim() + "]");
        check("压格版本保留原钻头造价（放合体钻头要花材料，共 " + sum + "）",
            req != null && req.length > 0 && sum > 0 && drill.requirements != null
                && req.length == drill.requirements.length);
      }
      check("压格版本给了解锁兜底（alwaysUnlocked，战役/联机不会被当成没解锁）", small.alwaysUnlocked);

      Block core = Vars.content.block("core-shard");
      place(core, 30, 150);
      run(10);
      floor(40, 40, 110, 90, Blocks.oreCopper);

      // ---------- B：落地时自动压成 1x1（物理占地，不是只画小） ----------
      mark(60, 70, 66, 74);
      int zone = size();
      System.out.println("[SD] 阴影标记 " + zone + " 格");
      check("阴影记下的格子数 35（" + zone + "）", zone == 35);
      var onNewPlan = clsDrill.getMethod("onNewPlan", mindustry.entities.units.BuildPlan.class);
      onNewPlan.setAccessible(true);
      mindustry.entities.units.BuildPlan plan = new mindustry.entities.units.BuildPlan(60, 70, 0, drill);
      onNewPlan.invoke(drill, plan);
      check("阴影上摆钻头：plan 被换成压格版本（" + (plan.block == null ? "null" : plan.block.name) + "）",
          plan.block == small);
      Building a = place(plan.block, plan.x, plan.y);
      run(5);
      System.out.println("[SD] 压格钻头落在 " + (a == null ? "null" : a.tileX() + "," + a.tileY())
          + "，方块 size=" + (a == null ? -1 : a.block.size)
          + "，右边那格 = " + (a == null ? "?" : String.valueOf(Vars.world.tile(a.tileX() + 1, a.tileY()).block().name)));
      check("它真的只占 1 格（右边那格是空的）",
          a != null && a.block.size == 1 && Vars.world.tile(a.tileX() + 1, a.tileY()).block() == Blocks.air);
      int aDom = fieldInt(a, "dominantItems");
      check("还是按原来 2x2 那 4 格矿算（" + aDom + "）", aDom == 4);

      // 【用户报的"阴影上摆钻头、点建造后钻头直接消失"】落地换的是 hidden 方块，
      // 原版 validPlace 读 isPlaceable()（默认含 isVisible()）→ hidden 一律非法、plan 被丢掉。
      // 这里把"原始放置判据"也锁进回归。
      boolean vpSmall = mindustry.world.Build.validPlace(small, Team.sharded, 62, 72, 0, true, false);
      boolean vpBig = mindustry.world.Build.validPlace(drill, Team.sharded, 74, 60, 0, true, false);
      System.out.println("[SD] 原版放置判据：压格版本=" + vpSmall + "（对照 普通版=" + vpBig + "）");
      check("压格版本在矿上能通过原版放置判据（validPlace=" + vpSmall + "）", vpSmall);
      check("普通版对照同样是 true", vpBig);

      // ---------- C：同一片阴影 = 一个资源输出站（共池、共液） ----------
      Building b1 = place(small, 64, 72);
      run(30);
      check("同区域两台压格钻头共用物品池", a.items == b1.items);
      check("同区域两台压格钻头共用液体池", a.liquids == b1.liquids);
      Object grp = fieldObj(a, "comboGroup");
      int gsize = grp instanceof Seq<?> s2 ? s2.size : -1;
      System.out.println("[SD] 同区域两台压格钻头 comboGroup.size=" + gsize);
      check("同区域的压格钻头并成一组（group=" + gsize + "）", gsize == 2);
      // 通水：随便往一台里灌水，两台都该看到（= 通水给任意一台就能加速整站）
      a.liquids.add(Liquids.water, 8f);
      run(2);
      System.out.println("[SD] 往一台灌水 8：a=" + a.liquids.get(Liquids.water) + " b=" + b1.liquids.get(Liquids.water)
          + " 效率a=" + a.efficiency + " 效率b=" + b1.efficiency);
      check("水是整站共享的（两台都读到）",
          a.liquids.get(Liquids.water) > 0.01f && b1.liquids.get(Liquids.water) > 0.01f);

      // ---------- D：输出站任意一边都能出物品 ----------
      Building out = place(Vars.content.block("container"), 67, 70); // 占 67..68，只挨着区域最后一列 (66,*)
      boolean nearDrill = out != null && a != null
          && Math.abs(out.tileX() - a.tileX()) <= 2 && Math.abs(out.tileY() - a.tileY()) <= 2;
      int before = out == null || out.items == null ? -1 : out.items.total();
      run(900);
      int after = out == null || out.items == null ? -1 : out.items.total();
      System.out.println("[SD] 区域另一边（" + (out == null ? "null" : out.tileX() + "," + out.tileY())
          + "，离钻头 " + (a == null ? "?" : a.tileX() + "," + a.tileY()) + "）的容器：" + before + " → " + after);
      check("物品从输出站任意一边送了出去（收到 " + after + "）", after > 0);
      check("这个出口不在钻头旁边（" + nearDrill + "）", !nearDrill);

      // ---------- D2：每台给整站加「原来那 size×size 铺满矿物」的效率（不看脚下实际几格矿） ----------
      callStatic(clsShadow, "clear", null);
      clearArea();
      floor(40, 40, 110, 90, Blocks.stone);
      floor(60, 70, 60, 70, Blocks.oreCopper);
      floor(64, 70, 65, 70, Blocks.oreCopper);   // 同一片区域里另外几格矿
      mark(60, 69, 65, 71);                       // 只记下"有矿的格子"→ 3 格
      Building sparse = place(small, 60, 70);     // 它自己 2x2 里只有 (60,70) 一格有矿
      run(30);
      int sparseDom = fieldInt(sparse, "dominantItems");
      System.out.println("[SD] 脚下只有 1 格矿时 dominantItems=" + sparseDom
          + "（铺满口径应为 4，按实际矿位只有 1）");
      check("每个钻头按「原来大小铺满矿物」给整站（" + sparseDom + " == 4）", sparseDom == 4);
      callStatic(clsShadow, "clear", null);
      clearArea();
      floor(40, 40, 110, 90, Blocks.oreCopper);
      run(5);

      // ---------- E：不在阴影上的钻头照原版（自己 2x2 那 4 格） ----------
      callStatic(clsShadow, "clear", null);
      clearArea();
      run(5);
      Building plain = place(drill, 80, 60);
      run(5);
      int pDom = fieldInt(plain, "dominantItems");
      System.out.println("[SD] 不在阴影上的钻头 dominantItems=" + pDom + "，size=" + plain.block.size);
      check("不在阴影上就照原版（size=2、4 格矿）", pDom == 4 && plain.block.size == 2);
      check("清掉阴影后区域为空（" + size() + "）", size() == 0);

      // ---------- F：阴影存档块能写能读 ----------
      mark(60, 70, 62, 74);
      int marked = size();
      Class<?> clsChunk = Class.forName("combine.production.OreShadow$Chunk", true, ml);
      SaveFileReader.CustomChunk w = (SaveFileReader.CustomChunk) clsChunk.getDeclaredConstructor().newInstance();
      // 【用户 2026-10-10 报"联机的时候客户端不能合体钻头"】阴影块必须进**入服世界流**：
      // 原版 NetworkIO.writeWorld 只带 writeNet()==true 的自定义块（见 SaveVersion.writeCustomChunks）。
      // 以前是 false（靠 PlayerJoin 那张包补发），可那张包在世界数据发完的同一瞬间就发出去了，
      // 客户端还没读完世界流 → receiveZone 里 world.tile(...) 拿不到东西 → 整片阴影丢掉。
      check("阴影块要进联机入服世界流（writeNet=true）", w.writeNet());
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      w.write(new DataOutputStream(bos));
      SaveFileReader.CustomChunk rd = (SaveFileReader.CustomChunk) clsChunk.getDeclaredConstructor().newInstance();
      rd.read(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
      System.out.println("[SD] 阴影块 " + bos.size() + " 字节，往返后 " + size() + " 格（写前 " + marked + "）");
      check("阴影存档块往返后格数一致", size() == marked && marked > 0);

      // ---------- G：联机同步包（分块 + 整片替换） ----------
      callStatic(clsShadow, "clear", null);
      clearArea();
      floor(40, 40, 110, 90, Blocks.oreCopper);
      mark(60, 70, 84, 79); // 25x10 = 250 格 → 会分成两块（syncChunk=200）
      int zoneSize = size();
      callStatic(clsShadow, "clear", null);
      check("清空后本机没有阴影（" + size() + "）", size() == 0);
      Class<?> clsPkt = Class.forName("combine.production.OreShadowPacket", true, ml);
      java.lang.reflect.Method recv = clsShadow.getDeclaredMethod("receiveZone", clsPkt);
      recv.setAccessible(true);
      java.lang.reflect.Method writeM = clsPkt.getMethod("write", arc.util.io.Writes.class);
      java.lang.reflect.Method readM = clsPkt.getMethod("read", arc.util.io.Reads.class);
      int chunk = 200, chunks = (zoneSize + chunk - 1) / chunk;
      for (int i = 0; i < chunks; i++) {
        int from = i * chunk, to = Math.min(zoneSize, from + chunk);
        int[] zs = new int[to - from];
        for (int k = from; k < to; k++) {
          int idx = k;
          zs[k - from] = arc.math.geom.Point2.pack(60 + idx % 25, 70 + idx / 25);
        }
        Object pkt = clsPkt.getDeclaredConstructor().newInstance();
        setField(pkt, "total", zoneSize);
        setField(pkt, "index", i);
        setField(pkt, "zone", zs);
        // 先真的序列化/反序列化一遍（验证 write/read），再交给接收端
        ByteArrayOutputStream pb = new ByteArrayOutputStream();
        writeM.invoke(pkt, new arc.util.io.Writes(new DataOutputStream(pb)));
        Object back = clsPkt.getDeclaredConstructor().newInstance();
        readM.invoke(back, new arc.util.io.Reads(new DataInputStream(new ByteArrayInputStream(pb.toByteArray()))));
        recv.invoke(null, back);
      }
      System.out.println("[SD] 联机同步包：本机 " + zoneSize + " 格 → 分 " + chunks + " 块 → 重组后 " + size() + " 格");
      check("联机同步包分块重组后格数一致（" + size() + "）", size() == zoneSize);
      mindustry.entities.units.BuildPlan p2 = new mindustry.entities.units.BuildPlan(60, 70, 0, drill);
      onNewPlan.invoke(drill, p2);
      check("同步回来的阴影照样能把钻头压成 1x1（" + (p2.block == null ? "null" : p2.block.name) + "）",
          p2.block == small);

      // ---------- H：输出 —— 区域里/边界上都要能出货（用户报的"阴影的物品输出不正常"） ----------
      callStatic(clsShadow, "clear", null);
      clearArea();
      floor(40, 40, 110, 90, Blocks.oreCopper);
      mark(60, 70, 64, 72); // 5x3 区域
      Building dOut = place(small, 60, 70);
      Block conveyor = Vars.content.block("conveyor");
      Building near = place(conveyor, 61, 70);      // 区域里、紧挨着钻头
      Building inside = place(conveyor, 63, 71);    // 区域里、不挨着钻头
      Building border = place(conveyor, 65, 70);    // 区域外、贴着边界
      int sum0 = dOut.items.total() + near.items.total() + inside.items.total() + border.items.total();
      run(4000);
      int sum1 = dOut.items.total() + near.items.total() + inside.items.total() + border.items.total();
      System.out.println("[SD] 物品守恒检查：4000 tick 合计 " + sum0 + " → " + sum1
          + "（增量 " + (sum1 - sum0) + "）");
      System.out.println("[SD] 出货：紧挨钻头=" + (near == null ? -1 : near.items.total())
          + " 区域里=" + (inside == null ? -1 : inside.items.total())
          + " 区域外=" + (border == null ? -1 : border.items.total())
          + "（钻头池=" + (dOut == null ? -1 : dOut.items.total()) + "）");
      System.out.println("[SD] 出货：紧挨钻头=" + (near == null ? -1 : near.items.total())
          + " 区域里=" + (inside == null ? -1 : inside.items.total())
          + " 区域外=" + (border == null ? -1 : border.items.total())
          + "（钻头池=" + (dOut == null ? -1 : dOut.items.total()) + "）");
      check("区域里紧挨钻头的传送带能收到", near != null && near.items.total() > 0);
      check("区域里（不挨着钻头）的传送带能收到", inside != null && inside.items.total() > 0);
      check("区域外的传送带能收到", border != null && border.items.total() > 0);

      // ---------- J：钻头只挖"自己踩着的那一格"；想两种矿就在两种矿上各摆一台 ----------
      callStatic(clsShadow, "clear", null);
      clearArea();
      floor(40, 40, 110, 90, Blocks.stone);
      floor(60, 70, 62, 70, Blocks.oreCopper);   // 3 格铜
      floor(63, 70, 63, 70, Blocks.oreLead);     // 1 格铅
      mark(60, 70, 63, 70);
      Building onCu = place(small, 60, 70);      // 踩在铜上
      Building onPb = place(small, 63, 70);      // 踩在铅上
      Building sink = place(Vars.content.block("container"), 64, 70); // 占 64..65，贴着区域最后 (63,70)
      run(20);
      int beforeCu = sink.items.get(Items.copper), beforePb = sink.items.get(Items.lead);
      run(3000);
      int cu = sink.items.get(Items.copper) - beforeCu, pb = sink.items.get(Items.lead) - beforePb;
      Object cuItems = fieldObj(onCu, "siteItems"), pbItems = fieldObj(onPb, "siteItems");
      int cuN = cuItems instanceof Seq<?> s1 ? s1.size : -1;
      int pbN = pbItems instanceof Seq<?> s2 ? s2.size : -1;
      System.out.println("[SD] 站位口径：踩铜那台矿种数=" + cuN + " 踩铅那台=" + pbN
          + "；容器里 铜 +" + cu + " 铅 +" + pb);
      check("踩铜的钻头只认铜（矿种数=" + cuN + "）", cuN == 1);
      check("踩铅的钻头只认铅（矿种数=" + pbN + "）", pbN == 1);
      check("站里两种矿都产出来了（铜 +" + cu + " / 铅 +" + pb + "）", cu > 0 && pb > 0);

      // display：面板里两种矿的挖速都要有（各自那台报各自那种）
      try {
        var rates = new arc.struct.ObjectFloatMap<mindustry.type.Item>();
        var gm = onCu.getClass().getMethod("groupDrillRates", arc.struct.ObjectFloatMap.class);
        gm.setAccessible(true);
        gm.invoke(onCu, rates);
        float cuR = rates.get(Items.copper, 0f), pbR = rates.get(Items.lead, 0f);
        System.out.println("[SD] 面板挖速：铜=" + cuR + "/s 铅=" + pbR + "/s（共 " + rates.size + " 种）");
        check("display 里铜的挖速有（" + cuR + "）", cuR > 0.0001f);
        check("display 里铅的挖速也有（" + pbR + "）", pbR > 0.0001f);
      } catch (Throwable t) {
        System.out.println("[SD] 面板挖速检查异常: " + t);
        check("面板挖速检查能跑起来", false);
      }

      // ---------- K：中心矿物色块 = 自己踩着的那一格（不是区域主导矿） ----------
      callStatic(clsShadow, "clear", null);
      clearArea();
      floor(40, 40, 110, 90, Blocks.stone);
      floor(60, 70, 61, 71, Blocks.oreCopper); // 区域里 4 格铜（主导）
      floor(62, 70, 62, 70, Blocks.oreLead);   // 区域里 1 格铅
      mark(60, 70, 62, 71);
      Building dkPb = place(small, 62, 70);    // 踩在铅上，但区域主导是铜
      run(30);
      Object dispPb = fieldObj(dkPb, "displayOre");
      String dnPb = dispPb instanceof mindustry.type.Item it ? it.name : String.valueOf(dispPb);
      Object domPb = fieldObj(dkPb, "dominantItem");
      String domPbName = domPb instanceof mindustry.type.Item it2 ? it2.name : String.valueOf(domPb);
      System.out.println("[SD] 踩铅的压格钻头：displayOre=" + dnPb + " 它挖的矿=" + domPbName);
      check("中心画的是自己踩着的铅（" + dnPb + "）", dispPb == Items.lead);
      check("挖的也是铅（" + domPbName + "）", domPb == Items.lead);

      // ---------- I：点哪落哪（用户 2026-10-10 报"放置位置和点击位置不一样"） ----------
      // 以前：地基压到阴影就算合法，落点由 OreShadow.freeSpotFor 挪到最近的空位 —— 点 A 出 B。
      // 现在：只有"点到的这一格自己在阴影里、而且是空的"才合法，落点就是这一格；
      //       点到已被占的格子 = 不放（不再自动挪走）。
      callStatic(clsShadow, "clear", null);
      clearArea();
      floor(40, 40, 110, 90, Blocks.oreCopper);
      mark(60, 70, 62, 72); // 3x3 = 9 格
      place(small, 62, 72); // 先占掉一格
      run(3);
      boolean vpFree = mindustry.world.Build.validPlace(drill, Team.sharded, 61, 71, 0, true, false);
      check("点在阴影空位上合法（validPlace=" + vpFree + "）", vpFree);
      mindustry.entities.units.BuildPlan aim = new mindustry.entities.units.BuildPlan(61, 71, 0, drill);
      onNewPlan.invoke(drill, aim);
      System.out.println("[SD] 点(61,71) → 落点(" + aim.x + "," + aim.y + ") 方块="
          + (aim.block == null ? "null" : aim.block.name));
      check("点在阴影空位上：位置不动、只换成压格版本（" + aim.x + "," + aim.y + "）",
          aim.block == small && aim.x == 61 && aim.y == 71);
      boolean vpOccupied = mindustry.world.Build.validPlace(drill, Team.sharded, 62, 72, 0, true, false);
      System.out.println("[SD] 点在已被占的阴影格 (62,72)：validPlace=" + vpOccupied);
      check("点在已被占的阴影格上不放（不再自动挪到空位）", !vpOccupied);
      mindustry.entities.units.BuildPlan occupied = new mindustry.entities.units.BuildPlan(62, 72, 0, drill);
      onNewPlan.invoke(drill, occupied);
      check("已被占的那格不会换成压格版本（plan 原样留着，交给原版判非法）", occupied.block == drill);

      // 把 3x3 区域塞满 → 再点就不允许了（每一格都被占）
      for (int y = 70; y <= 72; y++)
        for (int x = 60; x <= 62; x++)
          if (Vars.world.tile(x, y).build == null)
            place(small, x, y);
      run(3);
      boolean vpFull = mindustry.world.Build.validPlace(drill, Team.sharded, 61, 71, 0, true, false);
      System.out.println("[SD] 区域塞满后再点：validPlace=" + vpFull);
      check("阴影区域满了就不能再摆（validPlace=" + vpFull + "）", !vpFull);

      // ---------- L：拆除压格钻头要退回原钻头的造价（用户 2026-10-10） ----------
      // 走原版完整链路：beginBreak 把这一格切成"正在拆"（ConstructBuild，current = 被拆的方块），
      // 再把进度推到 0 —— 原版这时候按 current.requirements × buildCostMultiplier ×
      // deconstructRefundMultiplier 往核心退料。以前压格版本的 requirements 是空的（0 造价），
      // 于是"放置不要钱、拆了也不退钱"；现在两边都跟着原钻头走。
      callStatic(clsShadow, "clear", null);
      clearArea();
      // 上面 clearArea 会把开头那台核心一起清掉（范围含 30,150），退款要往核心退料，这里补一台
      place(core, 30, 150);
      run(5);
      floor(40, 40, 110, 90, Blocks.oreCopper);
      mark(90, 70, 94, 74);
      Building ref = place(small, 90, 70);
      run(5);
      check("拆除用例：压格钻头已摆好", ref != null && ref.block == small);
      {
        mindustry.type.ItemStack req0 = small.requirements[0];
        float rmul = Vars.state.rules.buildCostMultiplier * Vars.state.rules.deconstructRefundMultiplier;
        int expect = Math.round(req0.amount * rmul);
        mindustry.world.blocks.storage.CoreBlock.CoreBuild coreBuild = Team.sharded.data().core();
        int beforeRef = coreBuild == null ? -1 : coreBuild.items.get(req0.item);
        mindustry.world.Build.beginBreak(null, Team.sharded, 90, 70);
        Building mid = Vars.world.build(90, 70);
        String cur = mid instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild cbb
            ? (cbb.current == null ? "null" : cbb.current.name)
            : "-";
        if (mid instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild cbb) {
          mindustry.gen.Unit builder = mindustry.content.UnitTypes.poly.create(Team.sharded);
          builder.set(90 * 8f, 70 * 8f);
          for (int i = 0; i < 40 && Vars.world.build(90, 70) == cbb; i++)
            cbb.deconstruct(builder, coreBuild, 0.1f);
        }
        int afterRef = coreBuild == null ? -1 : coreBuild.items.get(req0.item);
        System.out.println("[SD] 拆除退款: 格子=" + (mid == null ? "null" : mid.getClass().getSimpleName())
            + " current=" + cur + "，" + req0.item.name + " 核心 " + beforeRef + " → " + afterRef
            + "（期望 +" + expect + "）");
        check("拆除压格钻头退还原钻头造价（" + req0.item.name + " +" + (afterRef - beforeRef) + "）",
            expect > 0 && afterRef - beforeRef == expect);
        Vars.world.tile(90, 70).setBlock(Blocks.air);
        run(3);
      }

      System.out.println("[SD] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(2);
    }
  }
}
