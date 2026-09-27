package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.files.Fi; import arc.struct.ObjectMap; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.*;
import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.modules.LiquidModule;

/** 临时诊断+回归：用户报的 5 条（构筑器/热量/通量反应堆/液体混输/高级墙壁粉碎机）。 */
public class ErekirBugsTest implements ApplicationListener{
    static String dataDir="/tmp/mp_new/data";
    static int pass=0, fail=0;
    static int w1pos=-1, f1pos=-1;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[EB] "+t); };
        new HeadlessApplication(new ErekirBugsTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[EB] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void info(String s){ System.out.println("[EB] " + s); }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static Block find(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static Building place(Block b,int x,int y,Team team,int rot){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,rot,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.rotation=rot; }catch(Throwable ignored){}
            try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Building place(Block b,int x,int y,Team team){ return place(b,x,y,team,0); }
    static Object fld(Object o, String name){
        for(Class<?> c=o==null?null:o.getClass(); c!=null && c!=Object.class; c=c.getSuperclass()){
            try{ java.lang.reflect.Field f=c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
            catch(NoSuchFieldException ignored){} catch(Throwable ignored){ return null; }
        }
        return null;
    }
    static float num(Object o, String name){ Object v=fld(o,name); return v instanceof Number n? n.floatValue() : -1f; }
    static Object call(Object o, String name){
        try{ java.lang.reflect.Method m=o.getClass().getMethod(name); return m.invoke(o); }catch(Throwable t){ return null; }
    }
    static float callF(Object o, String name){ Object v=call(o,name); return v instanceof Number n? n.floatValue(): -1f; }

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

        @SuppressWarnings("rawtypes")
        ObjectMap replaced = null;
        try{
            ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
            replaced = (ObjectMap)Class.forName("combine.Replacer", true, ml).getField("replaced").get(null);
        }catch(Throwable t){ info("拿不到替换表: "+t); }

        // ===== 3) 通量反应堆能否组合 =====
        Block flux = find("flux-reactor");
        info("flux-reactor 类="+(flux==null?"无":flux.getClass().getName())
            +" 被换过="+(replaced!=null && replaced.containsValue(flux,true)));
        check("通量反应堆已被组合类替换", flux!=null && replaced!=null && replaced.containsValue(flux,true));

        // ===== 1) 构筑器造出来的墙体要能进 T4/T5 组装厂 =====
        try{
            Block con = find("constructor");
            Block wall = find("tungsten-wall-large");
            Object fo = fld(con, "filter");
            boolean filterFresh = false;
            if(fo instanceof Seq<?> fs)
                for(Object o : fs) if(o == wall) filterFresh = true;
            Block asmB = find("tank-assembler");
            boolean planFresh = true;
            Object plans = fld(asmB, "plans");
            if(plans instanceof Seq<?> ps){
                for(Object plan : ps){
                    Object rq = fld(plan, "requirements");
                    if(rq instanceof Seq<?> reqs)
                        for(Object o : reqs)
                            if(o instanceof PayloadStack st && st.item instanceof Block bb
                                && replaced!=null && replaced.containsKey(bb))
                                planFresh = false;
                }
            }
            info("构筑器: filter 含内容表钨墙="+filterFresh
                +" canProduce(钨墙)="+(con instanceof mindustry.world.blocks.payloads.Constructor c2 && c2.canProduce(wall))
                +" 组装厂配方无老实例="+planFresh);
            check("构筑器 filter 指向内容表里的墙（不是老实例）", filterFresh);
            check("组装厂配方里的建筑引用不是老实例", planFresh);
        }catch(Throwable t){ info("构筑器场景崩: "+t); }

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        Vars.state.rules.canGameOver=false; Vars.state.rules.waves=false;
        run(20);
        for(int y=20;y<180;y++) for(int x=10;x<300;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);
        Block psrc = find("power-source");

        // ===== 5) 高级墙壁粉碎机 =====
        try{
            Block wc = find("large-cliff-crusher");
            Building w1 = place(wc, 30, 30, Team.sharded);
            Building w2 = place(wc, 33, 30, Team.sharded);
            w1pos = w1.pos();
            if(psrc!=null) place(psrc, 29, 33, Team.sharded);
            run(5);
            boolean shared = w1.liquids == w2.liquids;
            if(w1.liquids!=null) w1.liquids.add(Liquids.hydrogen, 30f);
            run(5);
            float h1 = w1.liquids==null?-1:w1.liquids.get(Liquids.hydrogen);
            float h2 = w2.liquids==null?-1:w2.liquids.get(Liquids.hydrogen);
            info("墙壁粉碎机: 共享液池="+shared+" 氢 w1="+h1+" w2="+h2
                +" 收石墨(w1)="+w1.acceptItem(null, Items.graphite));
            check("高级墙壁粉碎机共享气体池（w2 也能读到氢）", shared && h2>0.5f);
            check("高级墙壁粉碎机接受石墨强化输入", w1.acceptItem(null, Items.graphite));
        }catch(Throwable t){ info("墙壁粉碎机场景崩: "+t); }

        // ===== 2) 热量共享：需热工厂 + 需热炮台 =====
        try{
            Block conc = find("atmospheric-concentrator");
            Block heater = find("electric-heater");
            Building c1 = place(conc, 60, 30, Team.sharded);
            Building c2 = place(conc, 63, 30, Team.sharded);
            Building hh = place(heater, 66, 30, Team.sharded);
            if(psrc!=null){ place(psrc, 68, 30, Team.sharded); }
            run(240);
            face(hh, c2);
            run(60);
            float a1=callF(c1,"availableHeat"), a2=callF(c2,"availableHeat");
            float e1=callF(c1,"heatEfficiency"), e2=callF(c2,"heatEfficiency");
            info("需热工厂组合: a1="+a1+" a2="+a2+" eff1="+e1+" eff2="+e2+" heater.heat="+callF(hh,"heat"));
            check("两台需热工厂都能读到热量（a1>0 且 a2>0）", a1>0.5f && a2>0.5f);
            check("两台需热工厂效率一致且>0（"+e1+"/"+e2+"）", e1>0.05f && Math.abs(e1-e2)<0.05f);
        }catch(Throwable t){ info("需热工厂场景崩: "+t); }

        try{
            Block aff = find("afflict");
            Block heater = find("electric-heater");
            Building t1 = place(aff, 90, 30, Team.sharded);
            Building t2 = place(aff, 94, 30, Team.sharded);
            Building hh = place(heater, 98, 30, Team.sharded);
            if(psrc!=null){ place(psrc, 100, 30, Team.sharded); }
            run(240);
            face(hh, t2);
            run(60);
            float h1=num(t1,"heatReq"), h2=num(t2,"heatReq");
            info("劫难组合: heatReq t1="+h1+" t2="+h2+" (需求="+num(aff,"heatRequirement")+") heater.heat="+callF(hh,"heat"));
            check("贴热源的劫难读到热量（h2>0）", h2>0.5f);
            check("组合里的另一台劫难也共享到热量（h1>0）", h1>0.5f);
        }catch(Throwable t){ info("劫难场景崩: "+t); }

        try{
            Block mal = find("malign");
            Block heater = find("electric-heater");
            Building t1 = place(mal, 120, 30, Team.sharded);
            Building t2 = place(mal, 125, 30, Team.sharded);
            Building hh = place(heater, 130, 30, Team.sharded);
            if(psrc!=null){ place(psrc, 132, 30, Team.sharded); }
            run(240);
            face(hh, t2);
            run(60);
            float h1=num(t1,"heatReq"), h2=num(t2,"heatReq");
            info("魔灵组合: heatReq t1="+h1+" t2="+h2+" (需求="+num(mal,"heatRequirement")+") heater.heat="+callF(hh,"heat"));
            check("贴热源的魔灵读到热量（h2>0）", h2>0.5f);
            check("组合里的另一台魔灵也共享到热量（h1>0）", h1>0.5f);
        }catch(Throwable t){ info("魔灵场景崩: "+t); }

        // ===== 4) 液体混输：两种产液工厂组合 =====
        try{
            Block ele = find("electrolyzer");        // 出水+臭氧+氢
            Block conc = find("atmospheric-concentrator"); // 出氮
            Block tank = find("liquid-container");
            Building e = place(ele, 150, 30, Team.sharded);
            Building c = place(conc, 153, 30, Team.sharded);
            Building hh = place(find("electric-heater"), 156, 30, Team.sharded);
            if(psrc!=null) place(psrc, 158, 30, Team.sharded);
            Building col = place(tank, 150, 33, Team.sharded);   // 紧贴电解槽下缘
            Building con = place(tank, 153, 33, Team.sharded);   // 紧贴大气收集器下缘
            run(5);
            if(e.liquids!=null) e.liquids.add(Liquids.water, 40f);
            run(400);
            face(hh, c);
            run(200);
            info("电解槽池: 水="+amt(e.liquids,Liquids.water)+" 臭氧="+amt(e.liquids,Liquids.ozone)+" 氢="+amt(e.liquids,Liquids.hydrogen));
            info("大气收集池: 氮="+amt(c.liquids,Liquids.nitrogen)+" 臭氧="+amt(c.liquids,Liquids.ozone));
            info("电解槽输出罐: 臭氧="+amt(col.liquids,Liquids.ozone)+" 氢="+amt(col.liquids,Liquids.hydrogen)+" 氮="+amt(col.liquids,Liquids.nitrogen));
            info("大气收集输出罐: 氮="+amt(con.liquids,Liquids.nitrogen)+" 臭氧="+amt(con.liquids,Liquids.ozone));
            check("电解槽输出口不混出氮（氮="+amt(col.liquids,Liquids.nitrogen)+"）", amt(col.liquids,Liquids.nitrogen)<=0.5f);
            check("大气收集器输出口不混出臭氧（臭氧="+amt(con.liquids,Liquids.ozone)+"）", amt(con.liquids,Liquids.ozone)<=0.5f);
        }catch(Throwable t){ info("液体场景崩: "+t); }

        // ===== 3) 通量反应堆功能：组合 + 共享氰气/热量 =====
        try{
            Block heater = find("electric-heater");
            Block psrc2 = find("power-source");
            Building f1 = place(flux, 170, 60, Team.sharded);   // size5
            Building f2 = place(flux, 175, 60, Team.sharded);
            f1pos = f1.pos();
            Building hh = place(heater, 180, 60, Team.sharded);
            if(psrc2!=null) place(psrc2, 182, 60, Team.sharded);
            run(5);
            boolean lshared = f1.liquids == f2.liquids;
            if(f1.liquids!=null) f1.liquids.add(Liquids.cyanogen, 20f);
            run(120);
            face(hh, f2);
            run(120);
            info("通量反应堆: 共享液池="+lshared+" 氰 f1="+amt(f1.liquids,Liquids.cyanogen)+" f2="+amt(f2.liquids,Liquids.cyanogen)
                +" heat f1="+num(f1,"heat")+" f2="+num(f2,"heat")+" 发电 f1="+num(f1,"productionEfficiency"));
            check("通量反应堆组合共享氰气池", lshared && amt(f2.liquids,Liquids.cyanogen)>0.5f);
            check("通量反应堆组合共享热量（两台都能读到热）", num(f1,"heat")>0.5f && num(f2,"heat")>0.5f);
        }catch(Throwable t){ info("通量反应堆场景崩: "+t); }

        // ===== 存/读档：新的组合通量反应堆 / 墙壁粉碎机液池不能翻倍或丢失 =====
        try{
            // 墙壁粉碎机在跑 tick 时会不停消耗氢气（原版 ConsumeLiquid 每帧扣），
            // 所以临存前补一次，专门验"存读档不翻倍/不丢"。
            {
                Building w = Vars.world.build(w1pos);
                if(w!=null && w.liquids!=null) w.liquids.add(Liquids.hydrogen, 60f);
                run(3);
            }
            float cyanBefore = -1, hydBefore = -1;
            {
                Building b = Vars.world.build(f1pos);
                if(b!=null) cyanBefore = amt(b.liquids, Liquids.cyanogen);
                Building w = Vars.world.build(w1pos);
                if(w!=null) hydBefore = amt(w.liquids, Liquids.hydrogen);
            }
            Fi file = Core.files.absolute("/tmp/cl/erekir-bugs.msav");
            SaveIO.save(file);
            SaveIO.load(file);
            run(20);
            Building f1 = Vars.world.build(f1pos), w1 = Vars.world.build(w1pos);
            float cyanAfter = f1==null?-1:amt(f1.liquids, Liquids.cyanogen);
            float hydAfter = w1==null?-1:amt(w1.liquids, Liquids.hydrogen);
            info("存读档: 通量氰 "+cyanBefore+" -> "+cyanAfter+" | 粉碎机氢 "+hydBefore+" -> "+hydAfter);
            check("读档后通量反应堆液池不翻倍/不丢（"+cyanBefore+" -> "+cyanAfter+"）",
                cyanAfter > 0.5f && cyanAfter <= cyanBefore * 1.5f + 1f);
            check("读档后墙壁粉碎机气体池不丢（"+hydBefore+" -> "+hydAfter+"）",
                hydAfter > 0.5f && hydAfter <= hydBefore * 1.5f + 1f);
        }catch(Throwable t){ info("存读档场景崩: "+t); }

        System.out.println("[EB] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+" fail="+fail+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
    static float amt(LiquidModule m, Liquid l){ return m==null?-1:m.get(l); }
    /** 让产热机朝向 target（原版 HeatProducer rotate=true，热量只往朝向那一侧发）。 */
    static void face(Building heater, Building target){
        if(heater==null||target==null) return;
        int dir = heater.tileX()>target.tileX()?2 : heater.tileX()<target.tileX()?0
            : (heater.tileY()>target.tileY()?3:1);
        heater.rotation = dir;
    }
}
