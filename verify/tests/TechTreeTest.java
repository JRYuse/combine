package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.ObjectSet; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.content.TechTree.TechNode; import mindustry.core.*;
import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.ItemStack; import mindustry.ui.Fonts; import mindustry.world.Block;

/**
 * 科技树体检（用户报"打开科技树崩溃"）。
 *
 * 崩溃堆栈：ResearchDialog$View.canSpend → `node.finishedRequirements[i].amount` / `node.requirements[i].amount`
 * 读到 null 的 ItemStack（安卓 ART: "Attempt to read from field 'int mindustry.type.ItemStack.amount'"）。
 * 原版 canSpend 是改不了的类，所以这里把整棵树按同样的方式"走一遍"：
 *   1) TechTree.all 里每个节点：requirements / finishedRequirements 必须非 null、等长、元素与 item 都非 null；
 *   2) 模拟 canSpend 的每一项取值（含 finished[i].amount / req[i].amount / req[i].item），单个节点崩了要报出是谁；
 *   3) 组合节点 / 组合连接器 / 液体卸载器 必须**同时**在塞普罗树与埃里克尔树里可达，且两树研究材料分别对得上当前星球。
 */
public class TechTreeTest implements ApplicationListener{
    static String dataDir="/tmp/mp_coop/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new TechTreeTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[TT] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        Vars.mods.eachClass(Mod::init);

        System.out.println("[TT] 科技树节点总数=" + TechTree.all.size + " 根=" + TechTree.roots.size);
        for(Block b : Vars.content.blocks()){
          String cn = b.getClass().getName();
          if(cn.equals("combine.net.ComboConnector") || cn.equals("combine.net.ComboNode") || cn.equals("combine.storage.LiquidUnloader")){
            System.out.println("[TT] 方块: name=" + b.name + " cls=" + cn + " techNode=" + b.techNode
                + " techNodes=" + b.techNodes.size + " unlocked=" + b.alwaysUnlocked);
          }
        }

        // 1+2) 逐节点体检：既查数据，也真的按 canSpend 的写法解引用一遍
        Seq<String> bad = new Seq<>();
        int nullReq = 0, lenMismatch = 0, nullElem = 0;
        for(TechNode n : TechTree.all){
          if(n == null || n.content == null){ bad.add("<content=null>"); continue; }
          ItemStack[] req = n.requirements, fin = n.finishedRequirements;
          if(req == null || fin == null){
            nullReq++; bad.add(n.content.name + "(数组 null: req=" + req + " fin=" + fin + ")");
            continue;
          }
          if(req.length != fin.length){
            lenMismatch++; bad.add(n.content.name + "(长度不等: req=" + req.length + " fin=" + fin.length + ")");
            continue;
          }
          for(int i = 0; i < req.length; i++){
            try{
              // 完全照抄原版 canSpend 的取值顺序
              int finAmount = fin[i].amount, reqAmount = req[i].amount;
              Object item = req[i].item;
              if(item == null){
                nullElem++; bad.add(n.content.name + "(第 " + i + " 项 item=null)");
                break;
              }
              if(finAmount < reqAmount){
                // 命中即消费分支，原版这里还会读 items.has(req[i].item)
              }
            }catch(NullPointerException e){
              nullElem++; bad.add(n.content.name + "(第 " + i + " 项 = null)");
              break;
            }
          }
        }
        System.out.println("[TT] 坏节点: 数组null=" + nullReq + " 长度不等=" + lenMismatch + " 元素null=" + nullElem);
        for(String s : bad) System.out.println("[TT]   坏: " + s);
        check("TechTree.all 里没有会让 canSpend 崩的节点", bad.isEmpty());

        // 3) 三个方块：两棵树都要有
        check("塞普罗树里能到 组合连接器", reachable(Blocks.coreShard.techNode, name("connection")));
        check("塞普罗树里能到 组合节点", reachable(Blocks.coreShard.techNode, name("node")));
        check("塞普罗树里能到 液体卸载器", reachable(Blocks.coreShard.techNode, name("liquid-unloader")));
        boolean hasErekir = Vars.content.block("core-bastion") != null;
        check("埃里克尔树里能到 组合连接器", hasErekir && reachable(Blocks.coreBastion.techNode, name("connection")));
        check("埃里克尔树里能到 组合节点", hasErekir && reachable(Blocks.coreBastion.techNode, name("node")));
        check("埃里克尔树里能到 液体卸载器", hasErekir && reachable(Blocks.coreBastion.techNode, name("liquid-unloader")));

        // 每个节点的 objectives / 可选择性（原版 selectable = 解锁 或 目标全完成；不满足就画锁图标）
        for(TechNode n : TechTree.all)
          if(n != null && n.content != null && (n.content.name.equals("connection") || n.content.name.equals("node")
              || n.content.name.equals("liquid-unloader"))){
            StringBuilder ob = new StringBuilder();
            for(mindustry.game.Objectives.Objective o : n.objectives)
              ob.append(o.getClass().getSimpleName()).append(o.complete() ? "(完成) " : "(未完成) ");
            System.out.println("[TT] 节点 " + n.content.name + " 目标: " + (ob.length() == 0 ? "无" : ob.toString()));
          }

        // 两棵树的研究材料要分星球
        for(TechNode n : TechTree.all)
          if(n != null && (n.content == name("connection") || n.content == name("node") || n.content == name("liquid-unloader"))){
            System.out.println("[TT] 节点 " + n.content.name + ": parent="
                + (n.parent == null || n.parent.content == null ? "null" : n.parent.content.name)
                + " 材料=" + n.requirements.length + " 项");
          }
        String sReq = reqString(Blocks.coreShard.techNode, "node");
        String eReq = reqString(Blocks.coreBastion.techNode, "node");
        System.out.println("[TT] 组合节点研究材料: 塞普罗=" + sReq + " 埃里克尔=" + eReq);
        check("埃里克尔的研究材料不是铜/铅/硅", eReq != null && !eReq.contains("copper") && !eReq.contains("lead") && !eReq.contains("silicon"));
        String sUn = reqString(Blocks.coreShard.techNode, "liquid-unloader");
        String eUn = reqString(Blocks.coreBastion.techNode, "liquid-unloader");
        System.out.println("[TT] 液体卸载器研究材料: 塞普罗=" + sUn + " 埃里克尔=" + eUn);
        check("埃里克尔液体卸载器材料不是铜/铅", eUn != null && !eUn.contains("copper") && !eUn.contains("lead"));

        // 4) 数据兜底：模拟"别的模组按 JSON 重设造价，其中一项物品找不到"留下的坏节点——
        //    TechNode 构造/重设时先赋值 requirements + 分配 finishedRequirements，再逐项填；
        //    中途抛异常 => 节点留在 TechTree.all 里、finishedRequirements 全是 null，
        //    之后打开科技树 canSpend 读 finishedRequirements[i].amount 直接 NPE（用户报的就是这个）。
        ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        Class<?> modMain = Class.forName("combine.Main", true, ml);
        java.lang.reflect.Method sanitize = modMain.getDeclaredMethod("sanitizeContent");
        sanitize.setAccessible(true);

        TechNode victim = null;
        for(TechNode n : TechTree.all)
          if(n != null && n.content == name("node")){ victim = n; break; }
        check("找得到 组合节点 的科技树节点", victim != null);

        boolean threw = false;
        try{
          // 第二项 item=null —— 注意 ItemStack(item, amount) 会把 null 换成铜，
          // 真正会留下 null 的是 JSON 反序列化（物品名找不到）/直接写字段，
          // 所以这里直接写字段来复现。
          ItemStack broken = new ItemStack(Items.copper, 20);
          broken.item = null;
          victim.setupRequirements(new ItemStack[]{new ItemStack(Items.copper, 10), broken});
        }catch(Throwable t){ threw = true; }
        check("模拟损坏：重设造价时抛异常了", threw);
        check("模拟损坏：canSpend 现在会崩（复现用户崩溃）", canSpendWouldCrash(victim));
        System.out.println("[TT] 损坏后: requirements=" + victim.requirements.length
            + " 项, finishedRequirements=" + victim.finishedRequirements.length
            + " 项（其中 null " + countNull(victim.finishedRequirements) + " 项）");

        sanitize.invoke(null);
        check("兜底后 canSpend 不再崩", !canSpendWouldCrash(victim));
        System.out.println("[TT] 兜底后: requirements=" + victim.requirements.length
            + " 项, finishedRequirements=" + victim.finishedRequirements.length
            + " 项, 材料=" + reqStringOf(victim.requirements));

        // 5) 方块造价表兜底：建造菜单 PlacementFragment 读 stack.item（ItemModule.has）
        Block nb = name("node");
        ItemStack[] before = nb.requirements;
        ItemStack badStack = new ItemStack(Items.copper, 5);
        badStack.item = null;
        nb.requirements = new ItemStack[]{badStack, new ItemStack(Items.copper, 5)};
        sanitize.invoke(null);
        check("兜底后方块造价表里没有 null 项", !hasNullItem(nb.requirements));
        System.out.println("[TT] 方块 node 造价: 坏数据清掉后 = " + reqStringOf(nb.requirements));
        nb.requirements = before;

        System.out.println("[TT] 结果: pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 ? 0 : 1);
      }catch(Throwable t){ t.printStackTrace(); System.out.println("[TT] 崩了: " + t); System.exit(2); }
    }

    /**
     * 完全照抄原版 ResearchDialog$View.canSpend 的取值方式（items 用空背包，
     * 保证循环会一路走到出问题的那一项）：会 NPE 就说明用户那种崩溃还在。
     */
    static boolean canSpendWouldCrash(TechNode n){
      try{
        mindustry.type.ItemSeq items = new mindustry.type.ItemSeq();
        for(int i = 0; i < n.requirements.length; i++){
          if(n.finishedRequirements[i].amount < n.requirements[i].amount && items.has(n.requirements[i].item)){
            return false;
          }
        }
        return false;
      }catch(NullPointerException e){
        return true;
      }
    }

    static int countNull(ItemStack[] arr){
      int c = 0;
      for(ItemStack s : arr) if(s == null) c++;
      return c;
    }

    static boolean hasNullItem(ItemStack[] arr){
      for(ItemStack s : arr) if(s == null || s.item == null) return true;
      return false;
    }

    static String reqStringOf(ItemStack[] arr){
      StringBuilder sb = new StringBuilder();
      for(ItemStack s : arr) sb.append(s.item.name).append('=').append(s.amount).append(' ');
      return sb.toString().trim();
    }

    static Block name(String n){ return Vars.content.block(n); }

    /** 从某棵树的根出发，能不能走到这个方块对应的节点。 */
    static boolean reachable(TechNode root, Block b){
        if(root == null || b == null) return false;
        ObjectSet<TechNode> seen = new ObjectSet<>();
        return walk(root, b, seen);
    }
    static boolean walk(TechNode n, Block b, ObjectSet<TechNode> seen){
        if(n == null || !seen.add(n)) return false;
        if(n.content == b) return true;
        for(TechNode c : n.children) if(walk(c, b, seen)) return true;
        return false;
    }

    /** 取某棵树下某个方块节点的研究材料（"copper=80 lead=80"）。 */
    static String reqString(TechNode root, String blockName){
        Block b = name(blockName);
        if(root == null || b == null) return null;
        ObjectSet<TechNode> seen = new ObjectSet<>();
        return find(root, b, seen);
    }
    static String find(TechNode n, Block b, ObjectSet<TechNode> seen){
        if(n == null || !seen.add(n)) return null;
        if(n.content == b){
            StringBuilder sb = new StringBuilder();
            for(ItemStack s : n.requirements) sb.append(s.item.name).append('=').append(s.amount).append(' ');
            return sb.toString().trim();
        }
        for(TechNode c : n.children){
            String r = find(c, b, seen);
            if(r != null) return r;
        }
        return null;
    }
}
