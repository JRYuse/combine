#!/usr/bin/env python3
"""链路模拟：把客户端和服务器之间的 TCP/UDP 流量过一遍这个代理，加延迟/抖动/丢包。

为什么要做成**独立进程的代理**（而不是在模组里拦 Net）：
  - Mindustry 的实体快照是**每连接**发的（`NetConnection.send` → `arc.net.Connection.sendUDP`），
    根本不经过 `NetProvider`；而且官方专用服务器的启动器会在模组 init **之后**把 `Vars.net`
    重新赋值，模组里换 `Vars.net` 会被覆盖（实测 0 个包经过）。
  - 这台机器是 proot 环境，`tc`/`netem` 是个空壳（返回 0 但完全不生效），`ss`/`netstat` 也不给用。
  代理在 socket 层转发，和游戏实现无关：官方服务端 + 官方客户端也能跑。

用法：
  lagnet.py --listen 6568 --target 6567 [--latency 200] [--jitter 50] [--loss 20] [--warmup 5]

  --latency  单向延迟(ms)：往返 RTT ≈ 2×latency
  --jitter   抖动(%)：每个包再随机 ±latency×jitter/100
  --loss     UDP 丢包率(%)，**双向**都丢；TCP 不丢（丢了就是破坏可靠语义，真实链路里 TCP 会重传）
  --warmup   开头的 N 秒不丢包（让 UDP 注册/握手先过去，不然连都连不上，测不出后面的问题）

【线程数必须有界】一个流向一条常驻 Link（连接建立时创建、断开时 close），线程数只跟客户端数有关、
跟包数无关；外加 --max-threads 自检，超了主动退出。**绝对不要**每包 new 一个 Link/线程：旧版本就是
每转一个 UDP 包起一条永不退出的 `_run` 线程，2 分半攒到 800+ 条，把 proot（单线程 ptrace）拖成活锁，
整个容器（含 codex 会话）一起 `t (tracing stop)`，只能 kill -9 恢复。改完跑 `verify/lagnet-selftest.py`。

统计每 10 秒打一行 `[lagnet]`，跑完（收到 SIGTERM/SIGINT）打总结。
"""
import argparse
import heapq
import os
import random
import signal
import socket
import sys
import threading
import time


class Link:
    """一条单向链路的排队器：按到达时间 + 延迟 排定发送时刻，先到先发（保序）。

    生命周期（**重要，别改回去**）：
      一个流向一条 Link，在连接/客户端**建立时**创建、断开时 `close()`。
      绝不允许每个包 new 一个 —— 旧版本 `udp_loop` 里每收一个包就 `Link(...).push()`，
      而每个 Link 都会起一条 `_run` 线程且永不退出，于是"每个 UDP 包泄漏一条线程"。
      跑 2 分半就攒到 800+ 条常驻线程、每秒上千次 futex 唤醒，proot（单线程 ptrace 事件循环）
      直接被拖成活锁：整个容器所有进程一起 `t (tracing stop)`，连 codex 会话本身都没了响应，
      只能 kill -9 proot。详见 AGENTS.md「proot」那节和 README 的真联机章节。
    """

    def __init__(self, send, latency_ms, jitter_ms, delay_tcp=True):
        self.send = send
        self.latency = latency_ms / 1000.0
        self.jitter = jitter_ms / 1000.0
        self.delay_tcp = delay_tcp
        self.lock = threading.Lock()
        self.cond = threading.Condition(self.lock)
        self.heap = []
        self.seq = 0
        self.last_due = 0.0
        self.closed = False
        self.thread = None
        if self.latency > 0.0 and self.delay_tcp:
            self.thread = threading.Thread(target=self._run, daemon=True)
            self.thread.start()

    def push(self, data, addr=None):
        if self.thread is None:
            # 没配延迟：直接发，别走线程绕一圈
            self.send(data, addr)
            return
        now = time.monotonic()
        due = now + self.latency + (random.uniform(-self.jitter, self.jitter) if self.jitter else 0.0)
        with self.cond:
            # 保序：同一方向不允许后面的包比前面的先发（TCP 尤其需要，UDP 也无所谓）
            due = max(due, self.last_due)
            self.last_due = due
            heapq.heappush(self.heap, (due, self.seq, data, addr, now))
            self.seq += 1
            self.cond.notify()

    def close(self, drain_timeout=None):
        """关掉这条链路：把队列里剩下的包（最多等一个 latency+jitter）发完，然后退线程。"""
        with self.cond:
            self.closed = True
            self.cond.notify()
        if self.thread is not None and self.thread is not threading.current_thread():
            if drain_timeout is None:
                drain_timeout = self.latency + self.jitter + 1.0
            self.thread.join(timeout=drain_timeout)

    def _run(self):
        # 事件驱动：push/close 会 notify，睡就精确睡到下一个包该发的时刻，不轮询
        # （旧版每 0.5 秒醒一次；线程一多就是几百条线程抢 GIL，别学）
        while True:
            with self.cond:
                if not self.heap:
                    if self.closed:
                        return
                    self.cond.wait()
                    continue
                due, _, data, addr, _ = self.heap[0]
                wait = due - time.monotonic()
                if wait > 0:
                    self.cond.wait(wait)
                    continue
                heapq.heappop(self.heap)
            try:
                self.send(data, addr)
            except OSError:
                pass


class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.d = {}

    def add(self, key, n=1):
        with self.lock:
            self.d[key] = self.d.get(key, 0) + n

    def snapshot(self):
        with self.lock:
            return dict(self.d)


class Relay:
    def __init__(self, cfg):
        self.cfg = cfg
        self.stats = Stats()
        self.start = time.monotonic()
        self.stop = False
        self.udp_out = {}          # 客户端地址 -> 连到服务器的出站 UDP socket
        self.udp_links = {}        # 客户端地址 -> {"c2s": Link, "s2c": Link}（每流向一条，常驻）
        self.max_seen_threads = threading.active_count()
        self.udp_lock = threading.Lock()

    # ---------- 丢包判定 ----------
    def drop_udp(self):
        c = self.cfg
        if c.loss <= 0:
            return False
        if time.monotonic() - self.start < c.warmup:
            return False          # 热机期不丢，先让连接握完手
        if random.random() * 100.0 < c.loss:
            self.stats.add("udp_drop")
            return True
        return False

    # ---------- TCP ----------
    def tcp_accept_loop(self):
        srv = socket.socket()
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", self.cfg.listen))
        srv.listen(8)
        while not self.stop:
            try:
                cli, addr = srv.accept()
            except OSError:
                return
            try:
                up = socket.create_connection(("127.0.0.1", self.cfg.target))
            except OSError as e:
                print(f"[lagnet] 连服务器失败: {e}", flush=True)
                cli.close()
                continue
            self.stats.add("tcp_conn")
            print(f"[lagnet] TCP 接入 {addr} → 127.0.0.1:{self.cfg.target}", flush=True)
            cli.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            up.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            threading.Thread(target=self._tcp_pump, args=(cli, up, "c2s"), daemon=True).start()
            threading.Thread(target=self._tcp_pump, args=(up, cli, "s2c"), daemon=True).start()

    def _tcp_write(self, sock, data, tag):
        try:
            sock.sendall(data)
            self.stats.add("tcp_" + tag + "_bytes", len(data))
        except OSError:
            pass

    def _tcp_pump(self, src, dst, tag):
        link = Link(lambda d, a, s=dst: self._tcp_write(s, d, tag), self.cfg.latency, self.cfg.jitter)
        try:
            while not self.stop:
                data = src.recv(65536)
                if not data:
                    break
                link.push(data)
        except OSError:
            pass
        finally:
            link.close()           # 队列里剩下的先发完，再关 socket（否则尾部几个包会被截掉）
            for s in (src, dst):
                try:
                    s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
            time.sleep(max(self.cfg.latency / 1000.0, 0.0) + 0.5)
            for s in (src, dst):
                try:
                    s.close()
                except OSError:
                    pass

    # ---------- UDP ----------
    def udp_loop(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("127.0.0.1", self.cfg.listen))
        # 回程也从**同一个** socket 发：客户端认的是"服务器的地址:端口"，源端口换了它会丢包
        self.udp_sock = sock
        sock.settimeout(0.5)
        while not self.stop:
            try:
                data, addr = sock.recvfrom(65535)
            except socket.timeout:
                continue
            except OSError:
                return
            if self.drop_udp():
                continue
            self.stats.add("udp_c2s")
            self.udp_client_socket(addr)                     # 第一次见到这个客户端才建 socket + 两条 Link
            self.udp_links[addr]["c2s"].push(data)           # 复用常驻 Link，绝不每包 new（旧版的病根）

    def udp_client_socket(self, addr):
        """每个客户端地址一个出站 socket（稳定源端口，服务器靠 RegisterUDP 的 source 认连接）
        + 两条常驻 Link（c2s/s2c）。线程数只跟**客户端数**有关，跟包数无关。"""
        with self.udp_lock:
            out = self.udp_out.get(addr)
            if out is not None:
                return out
            out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            out.connect(("127.0.0.1", self.cfg.target))
            self.udp_out[addr] = out
            self.udp_links[addr] = {
                "c2s": Link(lambda d, a, s=out: self._udp_write(s, d, "c2s"), self.cfg.latency, self.cfg.jitter),
                "s2c": Link(lambda d, a, c=addr: self._udp_back(d, c), self.cfg.latency, self.cfg.jitter),
            }
            print(f"[lagnet] UDP 客户端 {addr} → 127.0.0.1:{self.cfg.target}"
                  f"（回程源 {out.getsockname()}）", flush=True)
            threading.Thread(target=self._udp_reply_loop, args=(addr, out), daemon=True).start()
            return out

    def _udp_write(self, sock, data, tag):
        try:
            sock.send(data)
            # 只记"转发出去"的账；收到的那一笔在 udp_loop/_udp_reply_loop 里记（否则同一个包被记两遍）
            self.stats.add("udp_" + tag + "_out")
        except OSError:
            pass

    def _udp_reply_loop(self, client_addr, out):
        """服务器往回发的 UDP：从 listen 端口回给客户端（源地址要和客户端当初发的一致）。"""
        out.settimeout(0.5)
        while not self.stop:
            try:
                data = out.recv(65535)
            except socket.timeout:
                continue
            except OSError:
                break
            if self.drop_udp():
                continue
            self.stats.add("udp_s2c")
            self.udp_links[client_addr]["s2c"].push(data)

    def _udp_back(self, data, client_addr):
        try:
            self.udp_sock.sendto(data, client_addr)
            self.stats.add("udp_s2c_out")
        except OSError:
            pass

    # ---------- 统计 / 收尾 ----------
    def guard_loop(self):
        """线程数自检：超过上限就**主动退出**。

        宁可这次联机验证失败重跑，也不能让线程泄漏累积到把 proot 拖成活锁 —— 那会冻死整个
        会话（codex 进程本身也在同一个 proot 里）。2026-09-22 两次会话卡死就是这个原因。"""
        while not self.stop:
            time.sleep(2)
            n = threading.active_count()
            self.max_seen_threads = max(self.max_seen_threads, n)
            if n > self.cfg.max_threads:
                print(f"[lagnet] 致命：线程数 {n} > 上限 {self.cfg.max_threads}，判定为线程泄漏，"
                      f"主动退出（继续跑会把 proot 拖成活锁、冻死整个会话）", flush=True)
                os._exit(3)

    def report_loop(self):
        while not self.stop:
            time.sleep(10)
            if self.stop:
                return
            s = self.stats.snapshot()
            print(f"[lagnet] {int(time.monotonic() - self.start)}s 统计: {self._fmt(s)}"
                  f"  线程={threading.active_count()}(峰值 {self.max_seen_threads})", flush=True)

    def _fmt(self, s):
        # 这里的 c2s/s2c 是**收到的**包数（= 1:1 转发时应等于发出去的包数）
        parts = [f"udp c2s={s.get('udp_c2s', 0)} s2c={s.get('udp_s2c', 0)} 丢={s.get('udp_drop', 0)}"]
        if s.get("tcp_conn"):
            parts.append(f"tcp 连接={s['tcp_conn']} c2s={s.get('tcp_c2s_bytes', 0)}B s2c={s.get('tcp_s2c_bytes', 0)}B")
        return "  ".join(parts)

    def run(self):
        cfg = self.cfg
        print(f"[lagnet] 监听 127.0.0.1:{cfg.listen} → 转发到 127.0.0.1:{cfg.target}"
              f"  单向延迟={cfg.latency}ms 抖动=±{cfg.jitter}ms UDP丢包={cfg.loss}% 热机={cfg.warmup}s"
              f"  线程上限={cfg.max_threads}", flush=True)
        threading.Thread(target=self.tcp_accept_loop, daemon=True).start()
        threading.Thread(target=self.udp_loop, daemon=True).start()
        threading.Thread(target=self.report_loop, daemon=True).start()
        threading.Thread(target=self.guard_loop, daemon=True).start()
        while not self.stop:
            time.sleep(0.3)
        for links in self.udp_links.values():
            for link in links.values():
                link.close()
        s = self.stats.snapshot()
        # 队列已排空，所以这时"收到"与"转发出去"应当一致（不一致 = 发送失败）
        print(f"[lagnet] 收工: {self._fmt(s)}"
              f"  转发出去 c2s={s.get('udp_c2s_out', 0)} s2c={s.get('udp_s2c_out', 0)}"
              f"  线程峰值={self.max_seen_threads}", flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--listen", type=int, required=True)
    p.add_argument("--target", type=int, required=True)
    p.add_argument("--latency", type=float, default=0.0)
    p.add_argument("--jitter", type=float, default=50.0)
    p.add_argument("--loss", type=float, default=0.0)
    p.add_argument("--warmup", type=float, default=5.0)
    p.add_argument("--max-threads", type=int, default=64,
                   help="线程数超过它就主动退出（防泄漏拖垮 proot，见 AGENTS.md）")
    cfg = p.parse_args()
    cfg.jitter = cfg.latency * cfg.jitter / 100.0     # 抖动按延迟的百分比算
    relay = Relay(cfg)

    def stop(*_):
        relay.stop = True
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    relay.run()


if __name__ == "__main__":
    main()
