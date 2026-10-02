package combine.net;

import arc.struct.IntIntMap;
import arc.struct.Seq;
import mindustry.gen.Building;

/**
 * 组合节点 / 组合连接器的「共享哪些部分」配置。
 *
 * <p>玩家点开节点或连接器就能勾选：物品 / 液体 / 电力 / 热量。语义：
 *
 * <ul>
 *   <li><b>全勾（默认）</b>：和以前完全一样 —— 跨连接件接起来的几张本地组合体被当成
 *       "一台大机器"（共用一份池子、容量相加、电网并起来、热量通算）。</li>
 *   <li><b>有任意一项没勾</b>：不再合成一台大机器，改由 {@link ComboNet} **逐项**共享：
 *       勾上的共用一份模块 / 并到一张电网，没勾的**每台各留各的**（连本地组合体内部也不并）。
 *       这样"共用液体但不共用物品"、"各接各的电"这类组合才成立。
 *       （用户报的"关闭物品共享后物品模块还是共享的"就是这条：以前没勾只拆到"本地组合体"
 *       那一层，组内还是共用一份。）</li>
 * </ul>
 *
 * <p>配置存在连接件自己身上（{@link Holder}），同一张网络里的连接件保持一致：
 * 点任意一个节点/连接器改配置 = 整张网络一起改（见 {@link #set}）。
 * 存档、多人同步都跟着 {@code write/read}，蓝图/复制粘贴走 {@code config()}。
 */
public final class ComboShare {
    public static final int ITEMS = 1, LIQUIDS = 2, POWER = 4, HEAT = 8;
    public static final int NONE = 0, ALL = ITEMS | LIQUIDS | POWER | HEAT;

    /** 配置字符串前缀：走原版 config 通道（字符串能原样过服务端）。 */
    public static final String prefix = "comboshare:";

    /** 标签顺序 = 面板上四个勾选框的顺序。 */
    public static final int[] bits = {ITEMS, LIQUIDS, POWER, HEAT};
    public static final String[] labels = {"物品", "液体", "电力", "热量"};

    private ComboShare() {
    }

    /** 能存这份配置的方块（组合节点 / 组合连接器）。 */
    public interface Holder {
        int shareMask();

        void shareMask(int mask);

        /** 最后一次配置的序号（两张网络合并时"后改的说了算"）。 */
        int shareStamp();

        void shareStamp(int stamp);

        default boolean shareBit(int bit) {
            return (shareMask() & bit) != 0;
        }
    }

    public static String label(int bit) {
        for (int i = 0; i < bits.length; i++)
            if (bits[i] == bit) return labels[i];
        return "?";
    }

    /** "物品+液体"，一项都没勾时是"都不共享"。 */
    public static String describe(int mask) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bits.length; i++) {
            if ((mask & bits[i]) == 0) continue;
            if (sb.length() > 0) sb.append('+');
            sb.append(labels[i]);
        }
        return sb.length() == 0 ? "都不共享" : sb.toString();
    }

    public static String encode(int mask) {
        return prefix + (mask & ALL);
    }

    /** 不是本模组的配置串返回 -1。 */
    public static int decode(String value) {
        if (value == null || !value.startsWith(prefix)) return -1;
        try {
            return Integer.parseInt(value.substring(prefix.length()).trim()) & ALL;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int stamp = 1;

    public static int nextStamp() {
        return ++stamp;
    }

    // ==================== 网络配置的查询 ====================

    /** 成员 -> 它所在网络的配置（ComboNet 每次 rebuild 刷新；不在网络里的按全共享）。 */
    private static final IntIntMap memberMask = new IntIntMap();

    static void clearMemberMasks() {
        memberMask.clear();
    }

    static void setMemberMask(int pos, int mask) {
        memberMask.put(pos, mask & ALL);
    }

    // ==================== 组合体自己的配置 ====================
    //
    // 连接件（节点/连接器）能把配置存在自己身上（Holder）。组合方块是"替换出来"的，
    // 没有统一基类可以加字段，所以组合体这份配置按 **pos** 存在这里（读档由
    // ComboShareState 那个自定义存档块写回）。语义和连接件完全一样：
    // 点任意一个组合体/连接件改的都是**同一张网络**的配置（resolveShareMask 统一）。

    /** 组合体 pos -> 共享配置（写存档时只写非默认的那些，见 {@link #bodyEntries()}）。 */
    private static final IntIntMap bodyMask = new IntIntMap();
    /** 组合体 pos -> 最后一次配置的序号（同 resolveShareMask 的"后改的说了算"）。 */
    private static final IntIntMap bodyStamp = new IntIntMap();

    public static boolean hasBodyMask(Building b) {
        return b != null && bodyMask.containsKey(b.pos());
    }

    public static int bodyMaskOf(Building b) {
        return b == null ? ALL : bodyMask.get(b.pos(), ALL);
    }

    static int bodyStampOf(Building b) {
        return b == null ? Integer.MIN_VALUE : bodyStamp.get(b.pos(), Integer.MIN_VALUE);
    }

    static void setBodyMask(int pos, int mask, int stamp) {
        bodyMask.put(pos, mask & ALL);
        bodyStamp.put(pos, stamp);
    }

    /** 新世界/读档前清空（旧的 pos -> 配置不能带到新图里）。 */
    public static void clearBodyMasks() {
        bodyMask.clear();
        bodyStamp.clear();
    }

    /** 读档用：把存档里的组合体配置装回来（stamp 取当前序号，天然"后写"）。 */
    public static void loadBodyMask(int pos, int mask) {
        bodyMask.put(pos, mask & ALL);
        bodyStamp.put(pos, nextStamp());
    }

    /** 存档用：所有**非默认**的组合体配置（默认全共享不写，存档不会因为组合体多而变大）。 */
    public static Seq<int[]> bodyEntries() {
        Seq<int[]> out = new Seq<>();
        for (IntIntMap.Entry e : bodyMask)
            if ((e.value & ALL) != ALL) out.add(new int[] { e.key, e.value & ALL });
        return out;
    }

    /**
     * 丢掉"这一格已经没有组合建筑了"的残留配置。
     *
     * <p>组合体这份配置是按 pos 存的，方块被拆掉之后没人来删它 —— 不清理的话，
     * 同一格重新盖一台（甚至是**别的**方块）会默默继承上一台那份配置
     * （"拆了重建，怎么还是各接各的电"）。ComboNet 每帧叫一次，只要表是空的就几乎零成本。
     */
    public static void pruneBodyMasks() {
        if (bodyMask.size == 0) return;
        Seq<Integer> dead = null;
        try {
            for (IntIntMap.Entry e : bodyMask) {
                Building b = mindustry.Vars.world == null ? null : mindustry.Vars.world.build(e.key);
                if (b == null || b.block == null || !combine.util.ComboReflect.isComboBuild(b)) {
                    if (dead == null) dead = new Seq<>();
                    dead.add(e.key);
                }
            }
        } catch (Throwable ignored) {
            return;
        }
        if (dead == null) return;
        for (int i = 0; i < dead.size; i++) {
            int pos = dead.get(i);
            bodyMask.remove(pos);
            bodyStamp.remove(pos);
            memberMask.remove(pos);
        }
    }

    /**
     * 这个建筑所在网络（节点/连接器接起来的一整张网）的共享配置。
     * 连接件读自己存的那份，组合体读它自己那份，其余成员读网络索引（都已由 {@link #set} 统一）。
     */
    public static int maskOf(Building b) {
        if (b == null) return ALL;
        if (b instanceof Holder h) return h.shareMask() & ALL;
        if (bodyMask.containsKey(b.pos())) return bodyMask.get(b.pos()) & ALL;
        return memberMask.get(b.pos(), ALL);
    }

    public static boolean shares(Building b, int bit) {
        return (maskOf(b) & bit) != 0;
    }

    /** 全勾（默认）= 老行为：跨连接件的两端当成"一台大机器"。 */
    public static boolean fullShare(int mask) {
        return (mask & ALL) == ALL;
    }

    public static boolean fullShare(Building b) {
        return fullShare(maskOf(b));
    }

    /**
     * 跨连接件的两端要不要并成一台本地组合体。
     *
     * <p>只要有一项没勾，就不并 —— 剩下的部分交给 ComboNet 在网络层逐项共享，
     * 否则"两端仍是一台机器"会让没勾的那项（比如各自的物品、各自的液体）还是混在一起。
     */
    public static boolean allowLocalJoin(Building linker) {
        if (linker instanceof Holder h) return fullShare(h.shareMask());
        return true;
    }

    // ==================== 用户改配置 ====================

    /** 改一位（勾选框用）。 */
    public static int toggle(Building operator, int bit) {
        int now = maskOf(operator);
        return set(operator, (now & bit) != 0 ? (now & ~bit) : (now | bit));
    }

    /**
     * 设置整张网络的共享配置：操作的那个组合体/连接件 + 同网络的其它成员一起改，
     * 并让 ComboNet 立刻重算（拆池/并池、电网重接、本地组合体分组）。
     */
    public static int set(Building operator, int mask) {
        if (operator == null) return ALL;
        mask &= ALL;
        int s = nextStamp();
        applyMask(operator, mask, s);
        try {
            Seq<Building> linkers = ComboNet.componentLinkers(operator);
            for (int i = 0; i < linkers.size; i++) applyMask(linkers.get(i), mask, s);
            Seq<Building> members = ComboNet.componentMembers(operator);
            for (int i = 0; i < members.size; i++) applyMask(members.get(i), mask, s);
        } catch (Throwable ignored) {
        }
        ComboNet.onShareChanged(operator);
        return mask;
    }

    /**
     * 把一份配置写到某个建筑身上：连接件写自己的字段，组合体写 pos 表，别的成员只更新索引。
     * 注意**不要**给非组合建筑（例如节点连线接进来的 mod 制热机）建 bodyMask —— 它们不参与共享配置。
     */
    private static void applyMask(Building b, int mask, int stamp) {
        if (b == null) return;
        if (b instanceof Holder h) {
            h.shareMask(mask);
            h.shareStamp(stamp);
        } else if (combine.util.ComboReflect.isComboBuild(b)) {
            setBodyMask(b.pos(), mask, stamp);
        }
        setMemberMask(b.pos(), mask);
    }
}
