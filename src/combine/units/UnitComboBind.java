package combine.units;

import arc.Core;
import arc.Events;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.struct.IntSeq;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.ai.UnitCommand;
import mindustry.ai.types.CommandAI;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.gen.Unit;
import mindustry.ui.dialogs.BaseDialog;

/**
 * 组合单位的手动编组入口与可视化。
 *
 * <p>入口只有<b>命令面板</b>一个（无悬浮窗）：选中单位后，右下角命令按钮区会出现
 * "组合"命令（{@link UnitComboMerge#comboCommand}，注册进了所有单位类型的 commands
 * 列表，图标 {@link Icon#link}）。点击后经原版指令通道下发，{@link #detectCommandButton}
 * 每帧检测、弹出本菜单，并把单位原指令还原。左键点单位不再弹菜单，避免误触。
 * 菜单内容：列出半径内可组合的单个单位（种类 · 距离 · 血量）与单位组
 * （构成 × 数量 · 成员数 · 距离），点击即组合；也可以退出当前组合、
 * 把整组融合为组合巨兽、或把点选的巨兽解体回成员单位。
 *
 * <p>可视化：没有常驻标记；点击/选中组合里某个单位时，它与同组成员之间
 * 会画出队伍色连线，方便看出组合范围。
 */
public class UnitComboBind{
    /** 当前点选/选中的单个单位。 */
    private static Unit picked;
    private static BaseDialog dialog;

    /** "组合"指令下发前，选中单位各自原本的指令（用于点击后还原）。 */
    private static final ObjectMap<Unit, UnitCommand> prevCmd = new ObjectMap<>();
    /** 检测到组合指令后的开菜单冷却（同步往返期间每帧都能检测到，只开一次）。 */
    private static float lastDetect = -1f;

    /** 在 mod init 时调用（无头服务端跳过）。 */
    public static void register(){
        Events.run(Trigger.update, UnitComboBind::tick);
        Events.run(Trigger.draw, UnitComboBind::drawGroups);
    }

    /** 每帧处理。 */
    private static void tick(){
        if(!Vars.state.isGame()){
            picked = null;
            prevCmd.clear();
            return;
        }

        // 左键不再直接弹菜单——组合入口只有命令面板的"组合"按钮（见 detectCommandButton），
        // 避免框选/点选单位做别的操作时误触。这里只做面板指令检测和选中集有效性维护。
        detectCommandButton();

        if(picked != null && (
            picked.team() != Vars.player.team()
            || !pickable(picked)
        )){
            picked = null;
        }
    }

    /**
     * 检测命令面板"组合"指令：原版按钮只是把指令经 Call.setUnitCommand 写到单位上，
     * 这里轮询选中单位，发现组合指令就弹出菜单，并把每个单位原本的指令还原
     * （还原走原版指令通道在服务器结算；组合指令本身不改变单位行为，但仍尽快还原）。
     */
    /** 读取单位当前指令（非 CommandAI 控制返回 null）。 */
    private static UnitCommand currentCommand(Unit u){
        return u.controller() instanceof CommandAI cai ? cai.command : null;
    }

    /** 上次清理 prevCmd 失效键的时间（键是单位引用，选中后被移除的单位必须清掉，否则内存泄漏）。 */
    private static float lastPurge = 0f;

    private static void detectCommandButton(){
        var selected = Vars.control.input.selectedUnits;
        if(selected.isEmpty()){
            prevCmd.clear();
            return;
        }

        UnitCommand combo = UnitComboMerge.comboCommand;
        if(combo == null) return;

        // 定期清理缓存里已失效的单位（如刚被融合进巨兽的选中单位）
        if(Time.time - lastPurge > 300f){
            lastPurge = Time.time;
            for(var it = prevCmd.keys().iterator(); it.hasNext();){
                if(!it.next().isValid()) it.remove();
            }
        }

        boolean any = false;
        for(Unit u : selected){
            if(u.isValid() && currentCommand(u) == combo){
                any = true;
                break;
            }
        }

        if(!any){
            // 维护"组合指令之前"的命令缓存（只缓存选中集，配合上面的定期清理不会泄漏）
            for(Unit u : selected){
                if(currentCommand(u) != null && currentCommand(u) != combo){
                    prevCmd.put(u, currentCommand(u));
                }
            }
            return;
        }

        // 还原指令：按原指令分桶，一桶一次 Call。
        // 注意：回退指令必须非 null（arc ObjectMap 不允许 null 键）——
        // 没缓存过且类型 defaultCommand 为 null（late 注册的类型）时退回 moveCommand。
        Unit target = null;
        ObjectMap<UnitCommand, IntSeq> buckets = new ObjectMap<>();
        for(Unit u : selected){
            if(!u.isValid() || currentCommand(u) != combo) continue;
            if(target == null) target = u;
            UnitCommand back = prevCmd.get(u);
            if(back == null) back = u.type.defaultCommand != null ? u.type.defaultCommand : UnitCommand.moveCommand;
            buckets.get(back, IntSeq::new).add(u.id());
        }
        buckets.each((cmd, ids) -> Call.setUnitCommand(Vars.player, ids.toArray(), cmd));

        if(target != null && Time.time - lastDetect > 30f && (dialog == null || !dialog.isShown()) && pickable(target)){
            lastDetect = Time.time;
            picked = target;
            openDialog();
        }
    }

    /** 可选中弹出组合菜单的单位：可编组单位，或组合巨兽（用于解体）。 */
    private static boolean pickable(Unit u){
        return UnitComboDamage.groupable(u) || u instanceof combine.units.mega.MegaUnitEntity;
    }

    /** 组合可视化：不画任何常驻标记；点击/选中组合里某个单位时，才画出同组成员连线。 */
    private static void drawGroups(){
        if(Vars.state.isMenu()) return;

        // 连线：只看当前关注单位的组合。优先弹菜单的单位 / 指挥模式单选，
        // 否则用鼠标正指着（点击目标）的单位——指向组合里任意成员即显示全组连线
        Unit focus = (picked != null && picked.isValid()) ? picked : null;
        if(focus == null && Vars.control.input.selectedUnits.size == 1){
            Unit s = Vars.control.input.selectedUnits.first();
            if(s != null && s.isValid()) focus = s;
        }
        if(focus == null){
            try{
                Unit h = Vars.control.input.selectedUnit();
                if(h != null && h.isValid()) focus = h;
            }catch(Throwable ignored){
            }
        }
        if(focus != null && UnitComboDamage.groupable(focus)){
            double gid = UnitComboDamage.comboId(focus);
            if(gid != 0.0){
                Draw.z(110f);
                Draw.color(focus.team.color, 0.35f);
                Lines.stroke(1f);
                for(Unit u : Groups.unit){
                    if(u == focus || UnitComboDamage.comboId(u) != gid || !u.isValid()) continue;
                    Lines.line(focus.x, focus.y, u.x, u.y);
                }
            }
        }
        Draw.reset();
    }

    private static void openDialog(){
        Unit u = picked;
        if(u == null) return;
        if(dialog == null) dialog = new BaseDialog("单位组合");
        dialog.cont.clear();
        dialog.buttons.clear();

        // ---- 组合巨兽：只提供解体 ----
        if(u instanceof combine.units.mega.MegaUnitEntity mega){
            int n = mega.memberCount();
            dialog.cont.add("组合巨兽    " + n + " 名成员（" + UnitComboMerge.composition(u) + "）"
                + "    血量 " + (int)(u.healthf() * 100) + "%").padBottom(8f).row();
            dialog.cont.button("解体为成员单位", () -> {
                UnitComboMerge.requestSplit(u);
                dialog.hide();
                picked = null;
            }).size(260f, 48f).padTop(8f).row();
            dialog.addCloseButton();
            dialog.show();
            return;
        }

        boolean grouped = UnitComboDamage.comboId(u) != 0.0;
        int curSize = UnitComboDamage.groupSize(u);

        // 状态行
        dialog.cont.add(u.type.localizedName + (grouped
            ? "    当前组合: " + curSize + " 名成员（" + UnitComboDamage.groupComposition(u) + "）"
            : "    未组合")).padBottom(8f).row();

        dialog.cont.pane(t -> {
            // ---- 与单个单位组合 ----
            t.add("[accent]与单个单位组合[]").left().padTop(4f).row();
            Seq<Unit> singles = UnitComboDamage.ungroupedNear(u, UnitComboDamage.joinRadius);
            if(singles.isEmpty()){
                t.add("[gray]附近没有未组合的单位[]").left().padLeft(10f).row();
            }else{
                for(Unit o : singles){
                    t.button(unitLine(o), () -> {
                        // 必须走服务器：comboId 是同步字段，客户端本地改只会造出"服务端不承认的幽灵组合"
                        UnitComboDamage.requestCombineWith(u, o);
                        rebuildDialog();
                    }).growX().padBottom(3f).row();
                }
            }

            // ---- 并入单位组 ----
            t.add("[accent]并入单位组[]").left().padTop(10f).row();
            Seq<Unit> reps = UnitComboDamage.groupRepsNear(u, UnitComboDamage.joinRadius);
            if(reps.isEmpty()){
                t.add("[gray]附近没有单位组[]").left().padLeft(10f).row();
            }else{
                for(Unit rep : reps){
                    t.button(groupLine(rep), () -> {
                        UnitComboDamage.requestCombineWith(u, rep);
                        rebuildDialog();
                    }).growX().padBottom(3f).row();
                }
            }
        }).width(420f).height(Math.min(320f, 60f + 30f * (UnitComboDamage.countUngroupedNear(u, UnitComboDamage.joinRadius) + UnitComboDamage.groupRepsNear(u, UnitComboDamage.joinRadius).size))).row();

        dialog.cont.button("退出当前组合", () -> {
            UnitComboDamage.requestUngroup(u);
            dialog.hide();
        }).disabled(b -> !grouped).size(260f, 48f).padTop(8f).row();

        // ---- 一键组合：框选单位编成组合（不融合） ----
        dialog.cont.button("组合选中单位", () -> {
            UnitComboDamage.requestGroupSelected(eligibleSelected());
            dialog.hide();
            picked = null;
        }).disabled(b -> eligibleSelected().size < 2).size(260f, 48f).padTop(8f).row();

        // ---- 一键合体：只融合指挥模式框选的单位（核心机/巨兽自动排除） ----
        dialog.cont.button("合体选中单位为组合巨兽", () -> {
            UnitComboMerge.requestMergeSelected(eligibleSelected());
            dialog.hide();
            picked = null;
        }).disabled(b -> eligibleSelected().size < 2).size(260f, 48f).padTop(8f).row();
        dialog.cont.label(() -> "当前框选可合体单位： " + eligibleSelected().size + " 个")
            .padTop(2f).color(arc.graphics.Color.gray).row();

        dialog.addCloseButton();
        dialog.show();
    }

    /** 组合操作后刷新菜单内容（不换弹窗，保持在单位上操作）。 */
    private static void rebuildDialog(){
        if(dialog == null || !dialog.isShown()) return;
        dialog.hide();
        openDialog();
    }

    /** 单个单位列表行：种类 · 距离 · 血量。 */
    private static String unitLine(Unit o){
        return o.type.localizedName + "  ·  " + dist(o) + "  ·  " + (int)(o.healthf() * 100) + "%";
    }

    /** 单位组列表行：构成×数量 · 成员数 · 距离。 */
    private static String groupLine(Unit rep){
        int size = UnitComboDamage.groupSize(rep);
        return UnitComboDamage.groupComposition(rep) + "  ·  " + size + " 名成员  ·  " + dist(rep);
    }

    /** 当前指挥模式框选里可合体的单位（可编组、同队；核心机与巨兽已被 groupable 排除）。 */
    private static Seq<Unit> eligibleSelected(){
        Seq<Unit> out = new Seq<>();
        if(Vars.control == null || Vars.control.input == null) return out;
        for(Unit u : Vars.control.input.selectedUnits){
            if(u != null && u.isValid() && UnitComboDamage.groupable(u) && u.team() == Vars.player.team()) out.add(u);
        }
        return out;
    }

    /** 距离（格）。 */
    private static String dist(Unit o){
        return (int)(o.dst(picked) / mindustry.Vars.tilesize) + "格";
    }
}
