package combine.util;

import arc.Core;
import arc.Events;
import arc.input.InputProcessor;
import arc.input.KeyCode;
import arc.scene.Scene;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;

/**
 * 【arc 输入兜底】只吞掉 arc 那条**已知**的 UI 输入空指针，别的一律照旧抛。
 *
 * <p>崩溃长这样（用户 2026-09-29 安卓/触屏 PC 报告）：
 * <pre>
 * NullPointerException: Cannot invoke "arc.scene.Scene.addTouchFocus(...)"
 *   because the return value of "arc.scene.Element.getScene()" is null
 *   at arc.scene.Element.notify(Element.java:167)
 *   at arc.scene.Element.fire(Element.java:139)
 *   at arc.scene.Scene.touchDown(Scene.java:302)
 * </pre>
 *
 * <p>根因在 arc 自己：{@code Scene.touchDown} 命中一个元素后调 {@code target.fire(event)}；
 * {@code Element.fire} 会**先**把"祖先链"快照下来（注释原话：让继承结构的变化不影响事件传播），
 * 再从下往上逐个 {@code notify}。只要在这条链上任意一个监听器里把某个**祖先**从场景里摘掉
 * （最典型：按钮/勾选框的点击回调里把父表 {@code clearChildren()} 重画，或 {@code dialog.hide()}
 * 立刻 {@code remove()}），轮到这个祖先自己 {@code notify} 时它已经是"没有 Scene 的元素"了 ——
 * {@code Element.notify} 依然无条件调 {@code getScene().addTouchFocus(...)}，当场 NPE。
 * arc 至今（2026-09 的 master）没有加 null 判断，所以只能从**触发方**根治：
 * 本模组自己的两处界面已经改成"延后一帧重建"（见 {@code ComboBlockList.defer}、
 * {@code UnitComboBind} 的待重建标记）；但玩家装了别的模组时，别的模组的界面照样能踩中。
 *
 * <p>所以这里再加一道**兜底**：把客户端 {@code Scene} 在输入派发链里的那一环换成这个代理，
 * 只捕获并吞掉这条 NPE（打完日志就当"这次点击没生效"），游戏继续；其它异常原样抛出，
 * 不掩盖任何真实问题。
 *
 * <p>为什么要吞而不是修：出事时目标元素确实被命中了（{@code Scene.touchDown} 已经走到
 * {@code target.fire}），说明这一下本来就点在 UI 上 —— 所以吞掉后**返回 true（消费掉）**，
 * 不让这一下再漏给游戏世界（否则松手会变成"点了一下地面"）。按键/滚轮/移动不消费，照常往下传。
 */
public final class ComboInputGuard{
    private ComboInputGuard(){}

    /** 已经装上的代理（换过 Scene 就重建）。 */
    private static Guard installed;
    /** 日志节流：前几条都打，之后每分钟最多一条。 */
    private static float lastWarn = -1000f;
    private static int swallowed = 0;

    /** 由 {@link combine.Main#init()} 在客户端调一次。 */
    public static void register(){
        if(Vars.headless)
            return;
        // 主菜单/暂停菜单也在画 → draw 这一路足够覆盖"Scene 被重建"的情况
        Events.run(Trigger.draw, ComboInputGuard::ensureInstalled);
        ensureInstalled();
    }

    /** 确保 Scene 在输入派发链里被我们的代理包住（幂等；没装成也不影响游戏）。 */
    public static void ensureInstalled(){
        try{
            if(Core.scene == null || Core.input == null)
                return;
            Seq<InputProcessor> list = Core.input.getInputProcessors();
            if(list == null)
                return;

            int sceneIdx = -1, guardIdx = -1;
            Guard found = null;
            for(int i = 0; i < list.size; i++){
                InputProcessor p = list.get(i);
                if(p instanceof Guard g){
                    if(g.scene == Core.scene){
                        guardIdx = i;
                        found = g;
                    }else{
                        // 旧场景留下的代理：清掉（新场景下面会重新装）
                        list.remove(i);
                        i--;
                    }
                }else if(p == Core.scene){
                    sceneIdx = i;
                }
            }

            if(guardIdx >= 0){
                // 已经有代理：把漏进去的"裸 Scene"摘掉，否则一次事件会被派发两遍
                if(sceneIdx >= 0)
                    list.remove(sceneIdx);
                installed = found;
                return;
            }

            if(sceneIdx >= 0){
                Guard g = new Guard(Core.scene);
                list.set(sceneIdx, g);
                installed = g;
            }
        }catch(Throwable t){
            // 兜底本身绝不能添乱
            Log.err("[combine] 输入兜底安装失败（忽略，游戏照常）", t);
        }
    }

    /** 是不是"元素已经被摘掉、arc 还去读它的 Scene"这条空指针。 */
    public static boolean isDetachedElementNpe(Throwable t){
        for(Throwable c = t; c != null; c = c.getCause()){
            if(!(c instanceof NullPointerException))
                continue;
            String m = c.getMessage();
            if(m != null && (m.contains("addTouchFocus") || m.contains("getScene")))
                return true;
            for(StackTraceElement e : c.getStackTrace()){
                if("arc.scene.Element".equals(e.getClassName())
                    && ("notify".equals(e.getMethodName()) || "fire".equals(e.getMethodName())))
                    return true;
            }
        }
        return false;
    }

    static void warn(String phase, Throwable t){
        swallowed++;
        if(swallowed <= 5 || Time.time - lastWarn > 60f){
            lastWarn = Time.time;
            Log.warn("[combine] 界面输入被 arc 的已知空指针打断（已吞掉，游戏继续）：@ —— @\n" +
                "  原因：某个 UI 元素在触摸派发过程中被移除，arc 随后读它的 getScene() 拿到 null。\n" +
                "  本模组自己的列表/对话框重画已经改成延后一帧；如果这条反复出现，多半是别的模组的界面踩的。\n" +
                "  本模组累计吞掉 @ 次。", phase, String.valueOf(t), swallowed);
        }
    }

    /**
     * 兜一层：**只**在"元素已经被摘掉"这条 NPE 上吞掉（打日志 + 返回 {@code consume}），
     * 其它异常原样抛出去 —— 绝不掩盖真实问题。
     *
     * <p>{@code body} 里就是"把这次输入交给 Scene 派发"。公开出来是为了让回归测试能直接
     * 拿现场触发的那条 NPE 验证"吞掉/照抛"的判定（见 {@code combine.dbg.InputGuardTest}）。
     */
    public static boolean protect(String phase, boolean consume, java.util.function.Supplier<Boolean> body){
        try{
            return body.get();
        }catch(Throwable t){
            if(!isDetachedElementNpe(t)){
                if(t instanceof RuntimeException re) throw re;
                if(t instanceof Error e) throw e;
                throw new RuntimeException(t);
            }
            warn(phase, t);
            return consume;
        }
    }

    /** 输入代理：转发给真正的 Scene，只兜住上面那条 NPE。 */
    static class Guard implements InputProcessor{
        final Scene scene;

        Guard(Scene scene){
            this.scene = scene;
        }

        private boolean guard(String phase, boolean consume, Attempt body){
            return protect(phase, consume, body::get);
        }

        // 触摸事件本来就点在 UI 上（能走到 fire 就说明命中了元素）→ 出事时消费掉，别漏给世界
        @Override public boolean touchDown(int x, int y, int pointer, KeyCode button){
            return guard("touchDown", true, () -> scene.touchDown(x, y, pointer, button));
        }

        @Override public boolean touchUp(int x, int y, int pointer, KeyCode button){
            return guard("touchUp", true, () -> scene.touchUp(x, y, pointer, button));
        }

        @Override public boolean touchDragged(int x, int y, int pointer){
            return guard("touchDragged", true, () -> scene.touchDragged(x, y, pointer));
        }

        // 键盘/滚轮/移动：不消费，照常往下传（吞掉异常就行，别让一次抖动吃掉后续输入）
        @Override public boolean mouseMoved(int x, int y){
            return guard("mouseMoved", false, () -> scene.mouseMoved(x, y));
        }

        @Override public boolean scrolled(float amountX, float amountY){
            return guard("scrolled", false, () -> scene.scrolled(amountX, amountY));
        }

        @Override public boolean keyDown(KeyCode key){
            return guard("keyDown", false, () -> scene.keyDown(key));
        }

        @Override public boolean keyUp(KeyCode key){
            return guard("keyUp", false, () -> scene.keyUp(key));
        }

        @Override public boolean keyTyped(char character){
            return guard("keyTyped", false, () -> scene.keyTyped(character));
        }
    }

    interface Attempt{
        boolean get();
    }
}
