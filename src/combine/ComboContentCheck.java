package combine;
import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.PlayerJoin;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.net.Net;
import mindustry.net.NetConnection;
import mindustry.world.Block;

/**
 * 联机内容自检。
 *
 * Mindustry 联机只校验"模组列表"（名字:版本），方块同步用的是 {@code content.blocks()} 的
 * <b>下标 id</b>，并不会按名字重映射内容。所以两端内容表只要顺序/数量不同，地图里的方块就会
 * 错位甚至消失（"客户端也装了模组却看不到组合建筑"就是这一类）。
 *
 * 这里在玩家入服时两端互换方块名列表，逐 id 比对，把第一处不同直接打进日志/聊天栏。
 */
public class ComboContentCheck{
    /** 每个包携带的名字数量（UDP 单包大小有限，分块发） */
    public static final int chunkSize = 64;
    /** 最多报告多少处不同 */
    public static final int maxReport = 10;

    static boolean registered = false;
    /** 客户端重组中的服务端列表 */
    static final Assembly clientSide = new Assembly();
    /** 服务端按连接重组中的客户端列表 */
    static final ObjectMap<NetConnection, Assembly> serverSide = new ObjectMap<>();

    /** 注册自检包与入服时机（客户端/服务端都要调用，包 id 顺序两端一致）。 */
    public static void register(){
        if(registered)
            return;
        registered = true;

        Net.registerPacket(ContentCheckPacket::new);
        Events.on(PlayerJoin.class, e -> sendServerList(e.player));
    }

    /** 本机内容表的方块名（顺序即 id） */
    public static String[] localNames(){
        Seq<Block> blocks = Vars.content.blocks();
        String[] names = new String[blocks.size];
        for(int i = 0; i < blocks.size; i++){
            Block block = blocks.get(i);
            names[i] = block == null ? null : block.name;
        }
        return names;
    }

    // -------------------- 发送 --------------------

    /** 服务端：把本机列表分块发给刚入服的玩家 */
    static void sendServerList(Player player){
        if(player == null || player.con == null)
            return;
        String[] names = localNames();
        for(int i = 0; i < names.length; i += chunkSize){
            player.con.send(ContentCheckPacket.of(true, names, i), true);
        }
    }

    /** 客户端：把本机列表分块回给服务端 */
    static void sendClientList(){
        String[] names = localNames();
        for(int i = 0; i < names.length; i += chunkSize){
            Vars.net.send(ContentCheckPacket.of(false, names, i), true);
        }
    }

    // -------------------- 接收 --------------------

    static void clientReceived(ContentCheckPacket packet){
        if(!assembly(clientSide, packet))
            return;

        String[] mine = localNames();
        report("客户端", mine, clientSide.names, null);

        // 回一份自己的列表，让服务端也能报一次（一次性，不再触发更多包）
        if(Vars.net != null && Vars.net.client()){
            sendClientList();
        }
        clientSide.reset();
    }

    static void serverReceived(NetConnection connection, ContentCheckPacket packet){
        if(connection == null)
            return;

        Assembly assembly = serverSide.get(connection);
        if(assembly == null){
            assembly = new Assembly();
            serverSide.put(connection, assembly);
        }
        if(!assembly(assembly, packet))
            return;

        report("服务端", localNames(), assembly.names, connection);
        serverSide.remove(connection);
    }

    /** 分块重组；收齐返回 true */
    static boolean assembly(Assembly assembly, ContentCheckPacket packet){
        if(packet.total < 0 || packet.index < 0)
            return false;
        if(assembly.names == null || assembly.names.length != packet.total){
            assembly.names = new String[packet.total];
            assembly.got = 0;
        }
        int end = Math.min(packet.index + packet.names.length, packet.total);
        for(int i = packet.index; i < end; i++){
            String name = packet.names[i - packet.index];
            if(assembly.names[i] == null && name != null){
                assembly.names[i] = name;
                assembly.got++;
            }
        }
        return assembly.got >= packet.total;
    }

    // -------------------- 比对与报告 --------------------

    static void report(String side, String[] mine, String[] remote, NetConnection connection){
        if(remote == null)
            return;

        int common = Math.min(mine.length, remote.length);
        int diffs = 0;
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < common && diffs < maxReport; i++){
            if(!java.util.Objects.equals(mine[i], remote[i])){
                diffs++;
                sb.append("\n  id ").append(i).append(": 本机=").append(mine[i])
                  .append(", 对方=").append(remote[i]);
            }
        }
        if(mine.length != remote.length){
            sb.append("\n  方块总数: 本机=").append(mine.length).append(", 对方=").append(remote.length);
        }

        if(diffs == 0 && mine.length == remote.length){
            return;
        }

        String msg = "[combine] 内容自检不通过(" + side + ")：两端方块表不一致，"
            + "地图里的方块会错位/消失，请确认两端模组与内容装配一致。" + sb;
        Log.warn(msg);

        String chat = "[scarlet]组合工厂：两端方块表不一致（详见日志）[]"
            + (diffs > 0 ? "\n[lightgray]第一处不同: id " + firstDiff(mine, remote) : "");
        if(connection != null){
            Call.infoMessage(connection, chat);
        }else if(Vars.ui != null && Vars.ui.chatfrag != null){
            Vars.ui.chatfrag.addMessage(chat);
        }
    }

    static String firstDiff(String[] mine, String[] remote){
        int common = Math.min(mine.length, remote.length);
        for(int i = 0; i < common; i++){
            if(!java.util.Objects.equals(mine[i], remote[i])){
                return i + " 本机=" + mine[i] + " 对方=" + remote[i];
            }
        }
        return common + " (总数 本机=" + mine.length + " 对方=" + remote.length + ")";
    }

    /** 分块重组缓冲 */
    static class Assembly{
        String[] names;
        int got;

        void reset(){
            names = null;
            got = 0;
        }
    }
}
