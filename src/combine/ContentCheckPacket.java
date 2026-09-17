package combine;

import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.net.NetConnection;
import mindustry.net.Packet;

/** 联机内容自检包：只带一段方块名列表（分块发送），逻辑见 {@link ComboContentCheck}。 */
public class ContentCheckPacket extends Packet{
    /** true = 服务端发给客户端；false = 客户端回给服务端 */
    public boolean fromServer;
    /** 本机方块总数 */
    public int total;
    /** 本包第一项的下标 */
    public int index;
    /** 本包携带的名字 */
    public String[] names = new String[0];

    public static ContentCheckPacket of(boolean fromServer, String[] all, int index){
        ContentCheckPacket packet = new ContentCheckPacket();
        packet.fromServer = fromServer;
        packet.total = all.length;
        packet.index = index;
        int len = Math.max(0, Math.min(ComboContentCheck.chunkSize, all.length - index));
        packet.names = new String[len];
        System.arraycopy(all, index, packet.names, 0, len);
        return packet;
    }

    @Override
    public void write(Writes write){
        write.bool(fromServer);
        write.i(total);
        write.i(index);
        write.s(names.length);
        for(String name : names)
            write.str(name == null ? "" : name);
    }

    @Override
    public void read(Reads read){
        fromServer = read.bool();
        total = read.i();
        index = read.i();
        int len = Math.max(read.s(), 0);
        names = new String[len];
        for(int i = 0; i < len; i++){
            String name = read.str();
            names[i] = name == null || name.isEmpty() ? null : name;
        }
    }

    @Override
    public void handleClient(){
        ComboContentCheck.clientReceived(this);
    }

    @Override
    public void handleServer(NetConnection connection){
        ComboContentCheck.serverReceived(connection, this);
    }
}
