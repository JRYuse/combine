package combine.dbg;

import arc.*; import arc.backend.headless.HeadlessApplication; import arc.input.KeyCode;
import arc.scene.*; import arc.scene.event.*; import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.*; import mindustry.core.*; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts;

/**
 * 用户 2026-09-29 崩溃（触屏 PC / 安卓都出现过）：
 *
 * <pre>
 * NullPointerException: Cannot invoke "arc.scene.Scene.addTouchFocus(...)"
 *   because the return value of "arc.scene.Element.getScene()" is null
 *   at arc.scene.Element.notify(Element.java:167)
 *   at arc.scene.Element.fire(Element.java:139)
 *   at arc.scene.Scene.touchDown(Scene.java:302)
 * </pre>
 *
 * arc 的 {@code Element.fire} 会先把祖先链快照下来，再从下往上 notify；只要某个监听器
 * 在派发过程中把一个**祖先**从场景里摘掉（典型：点击回调里把父表 clearChildren() / dialog 立刻 remove），
 * 轮到那个祖先自己 notify 时它已经没有 Scene 了，而 arc 照样无条件 {@code getScene().addTouchFocus(...)} → NPE。
 *
 * 本测试现场复现这条 NPE（纯 arc 类，不需要 GL/真窗口），再验证 {@code ComboInputGuard} 的判定：
 * 这条 NPE 必须被吞掉并消费，别的异常必须原样抛。代理真的装进输入链、以及"装了之后鼠标/键盘/触摸
 * 照常工作"，在真客户端那一层验证（见文件末尾说明）。
 */
public class InputGuardTest implements ApplicationListener{
  static String dataDir = "/tmp/mp_coop/data";
  static int pass = 0, fail = 0;
  static ClassLoader ml;

  public static void main(String[] a){
    if(a.length > 0) dataDir = a[0];
    Vars.platform = new Platform(){};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] " + t); };
    new HeadlessApplication(new InputGuardTest(), t -> t.printStackTrace());
  }

  static void check(String n, boolean ok){
    System.out.println("[IG] " + (ok ? "PASS " : "FAIL ") + n);
    if(ok) pass++; else fail++;
  }

  /**
   * 造同样形状的现场（不需要 Scene）：outer（祖先，监听器返回 true）→ inner（命中目标，
   * 它在监听器里把 outer 从父级摘掉）。然后对 inner 派发一次 touchDown —— 和真崩溃里
   * {@code Scene.touchDown → target.fire} 之后发生的事完全一致。
   */
  static Throwable reproduce(){
    Table outer = new Table();
    outer.setSize(800f, 600f);
    outer.setPosition(0f, 0f);
    Table inner = new Table();
    outer.add(inner).size(400f, 400f);
    // 祖先：命中后返回 true（arc 会为它 getScene().addTouchFocus）
    outer.addListener(new InputListener(){
      @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
        return true;
      }
    });
    // 目标：把祖先从父级摘掉（模拟"点击回调里重画父表 / hide 弹窗"），自己返回 false
    inner.addListener(new InputListener(){
      @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
        outer.remove();
        return false;
      }
    });

    InputEvent event = new InputEvent();
    event.type = InputEvent.InputEventType.touchDown;
    event.stageX = 10f;
    event.stageY = 10f;
    event.pointer = 0;
    event.keyCode = KeyCode.mouseLeft;
    try{
      inner.fire(event);
      return null;
    }catch(Throwable t){
      return t;
    }
  }

  @Override public void init(){
    try{
      Core.settings.setDataDirectory(Core.files.local(dataDir));
      Vars.loadLocales = false; Vars.loadSettings(); Vars.headless = true; Vars.init();
      UI.loadColors(); Fonts.loadContentIconsHeadless();
      Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
      Vars.mods.eachClass(Mod::init);
      if(Vars.logic == null) Vars.logic = new Logic();
      ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();

      // ---------- 1) 现场复现：祖先被摘掉 → arc 读 getScene() 拿到 null ----------
      Throwable raw = reproduce();
      check("现场复现 arc 的 NPE（" + raw + "）",
          raw instanceof NullPointerException && String.valueOf(raw.getMessage()).contains("getScene"));

      // ---------- 2) 判定函数：只认这条 NPE ----------
      Class<?> cls = Class.forName("combine.util.ComboInputGuard", true, ml);
      java.lang.reflect.Method isNpe = cls.getMethod("isDetachedElementNpe", Throwable.class);
      boolean other = (Boolean)isNpe.invoke(null, new IllegalStateException("别的东西炸了"));
      boolean thisOne = (Boolean)isNpe.invoke(null, raw);
      check("判定：这条 NPE = true，别的异常 = false", thisOne && !other);

      // ---------- 3) protect()：同样的现场被吞掉，且这一下被消费 ----------
      java.lang.reflect.Method protect = cls.getMethod("protect", String.class, boolean.class,
          java.util.function.Supplier.class);
      boolean guarded = (Boolean)protect.invoke(null, "touchDown", true, (java.util.function.Supplier<Boolean>)() -> {
        Throwable t = reproduce();
        if(t != null) throw sneaky(t);
        return true;
      });
      check("protect()：同一条 NPE 被吞掉并消费（不崩、return true）", guarded);

      // ---------- 4) protect()：别的异常必须原样抛 ----------
      boolean thrown = false;
      try{
        protect.invoke(null, "touchDown", true, (java.util.function.Supplier<Boolean>)() -> {
          throw new IllegalStateException("别的东西炸了");
        });
      }catch(java.lang.reflect.InvocationTargetException ite){
        thrown = ite.getCause() instanceof IllegalStateException;
      }
      check("protect()：别的异常原样抛出（不掩盖真实问题）", thrown);

      System.out.println("[IG] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    }catch(Throwable t){
      t.printStackTrace();
      System.exit(2);
    }
  }

  @SuppressWarnings("unchecked")
  static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T{
    throw (T)t;
  }
}
