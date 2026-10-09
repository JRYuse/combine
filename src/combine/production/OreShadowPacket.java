package combine.production;

import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.net.NetConnection;
import mindustry.net.Packet;

/**
 * 矿物阴影的联机同步包（分块发）。
 *
 * <p>为什么必须同步：这一版的阴影不只是"画出来"——它决定阴影上的钻头按**整片区域的矿**产出、
 * 以及物品能从区域任意一边送出去。产出是服务端权威的，服务端不认识这片阴影的话，联机时
 * 客户端自己算的产量会和服务器对不上（物品一跳一跳）。
 *
 * <p>方向：客户端改完 → 发给服务端；服务端收到后落地 + 转给其它客户端；新玩家入服时补一份。
 * 单人游戏 {@code net.active()==false}，整条路都不会走。
 */
public class OreShadowPacket extends Packet {
  /** 本片阴影一共多少格 */
  public int total;
  /** 本包是第几块 */
  public int index;
  /** 本包携带的格子（{@code Point2.pack(x,y)}） */
  public int[] zone = new int[0];

  @Override
  public void write(Writes write) {
    write.i(total);
    write.i(index);
    write.s(zone.length);
    for (int pos : zone)
      write.i(pos);
  }

  @Override
  public void read(Reads read) {
    total = read.i();
    index = read.i();
    int len = Math.max(read.s(), 0);
    zone = new int[len];
    for (int i = 0; i < len; i++)
      zone[i] = read.i();
  }

  @Override
  public void handleClient() {
    OreShadow.receiveZone(this);
  }

  @Override
  public void handleServer(NetConnection connection) {
    OreShadow.receiveZone(this);
    // 服务端收到客户端的阴影后转给其它客户端（新玩家入服时另有一次性补发）
    try {
      if (mindustry.Vars.net != null)
        mindustry.Vars.net.send(this, true);
    } catch (Throwable ignored) {
    }
  }
}
