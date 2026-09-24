#!/usr/bin/env python3
"""lagnet.py 自检：验证"线程数有界"和"延迟/丢包转发正确"。

为什么要有这个：
  2026-09-22 两次 codex 会话"卡死"，根因是 lagnet 旧版**每个 UDP 包 new 一个 Link**，
  每个 Link 起一条永不退出的 `_run` 线程：2 分半攒到 800+ 条常驻线程，proot（单线程
  ptrace 事件循环）被拖成活锁，整个容器（含 codex 自己）一起 `t (tracing stop)`。
  所以这里用"灌一批包，看线程数涨不涨"把它钉成回归测试。

跑法：
  verify/lagnet-selftest.py                 # 默认 1200 个包、60 包/秒、单向 50ms
  verify/lagnet-selftest.py --count 3000 --rate 150

判定：
  - 线程数峰值必须 ≤ 20（旧版会 = 包数+3，几千条）
  - 每个包都要回来（loss=0 时不许丢）
  - 平均 RTT ≈ 2×latency（±50%）
  - 收到 SIGTERM 后要立刻退出（说明 Link.close() 真的能收线程）
"""
import argparse
import os
import socket
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
LAGNET = os.path.join(HERE, "lagnet.py")


def threads_of(pid):
    try:
        with open("/proc/%d/status" % pid) as f:
            for line in f:
                if line.startswith("Threads:"):
                    return int(line.split()[1])
    except OSError:
        return -1
    return -1


def free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def start_echo(port, stop):
    """目标端：收到什么回什么（模拟服务器）。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", port))
    s.settimeout(0.2)
    while not stop.is_set():
        try:
            data, addr = s.recvfrom(65535)
        except socket.timeout:
            continue
        except OSError:
            break
        s.sendto(data, addr)
    s.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=1200, help="灌多少个 UDP 包")
    ap.add_argument("--rate", type=float, default=60.0, help="每秒多少个包")
    ap.add_argument("--latency", type=float, default=50.0, help="单向延迟 ms")
    ap.add_argument("--max-threads", type=int, default=20, help="线程数峰值上限（判定用）")
    cfg = ap.parse_args()

    target = free_port()
    listen = free_port()
    stop = threading.Event()
    threading.Thread(target=start_echo, args=(target, stop), daemon=True).start()

    proc = subprocess.Popen(
        [sys.executable, LAGNET, "--listen", str(listen), "--target", str(target),
         "--latency", str(cfg.latency), "--jitter", "0", "--loss", "0", "--warmup", "0"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    time.sleep(1.0)
    if proc.poll() is not None:
        print("FAIL: lagnet 没起来:\n" + proc.stdout.read())
        return 1
    print("[selftest] lagnet pid=%d 监听 %d → %d，启动后线程数=%d"
          % (proc.pid, listen, target, threads_of(proc.pid)))

    cli = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    cli.settimeout(5.0)
    cli.connect(("127.0.0.1", listen))

    sent = 0
    got = 0
    rtts = []
    pending = {}
    peak_threads = 0
    interval = 1.0 / cfg.rate
    t_end = time.monotonic() + cfg.count * interval + 3.0
    next_send = time.monotonic()
    last_report = time.monotonic()

    while (sent < cfg.count or pending) and time.monotonic() < t_end:
        now = time.monotonic()
        if sent < cfg.count and now >= next_send:
            payload = ("pkt-%d" % sent).encode()
            cli.send(payload)
            pending[payload] = time.monotonic()
            sent += 1
            next_send += interval
            continue
        try:
            data = cli.recv(65535)
        except socket.timeout:
            continue
        if data in pending:
            rtts.append((time.monotonic() - pending.pop(data)) * 1000.0)
            got += 1
        if now - last_report >= 5.0:
            last_report = now
            n = threads_of(proc.pid)
            peak_threads = max(peak_threads, n)
            print("[selftest] 已发 %d 收 %d 线程=%d 峰值=%d" % (sent, got, n, peak_threads), flush=True)

    peak_threads = max(peak_threads, threads_of(proc.pid))
    avg_rtt = sum(rtts) / len(rtts) if rtts else -1.0

    # 收工：SIGTERM 后应立刻退出（Link.close() 收线程）
    t0 = time.monotonic()
    proc.terminate()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
    stop_secs = time.monotonic() - t0
    tail = proc.stdout.read() if proc.stdout else ""
    cli.close()
    stop.set()

    print("[selftest] 发 %d 收 %d（丢 %d）平均 RTT %.1fms（目标 %.0fms）线程峰值 %d 收工耗时 %.2fs"
          % (sent, got, sent - got, avg_rtt, 2 * cfg.latency, peak_threads, stop_secs))
    if tail:
        print("[selftest] lagnet 最后输出: " + tail.strip().splitlines()[-1])

    ok = True
    if got != sent:
        print("FAIL: 有包没回来（发 %d 收 %d）" % (sent, got))
        ok = False
    if peak_threads > cfg.max_threads:
        print("FAIL: 线程数峰值 %d > %d —— 又漏线程了（会拖垮 proot）。"
              "一个流向一条常驻 Link，不许每包 new。" % (peak_threads, cfg.max_threads))
        ok = False
    if not (0.5 * 2 * cfg.latency <= avg_rtt <= 1.5 * 2 * cfg.latency + 40):
        print("FAIL: 平均 RTT %.1fms 不像 %.0fms 的延迟链路" % (avg_rtt, 2 * cfg.latency))
        ok = False
    if stop_secs > 5.0:
        print("FAIL: 收工耗时 %.2fs，Link.close() 没收住线程" % stop_secs)
        ok = False
    print("PASS: 线程数有界 + 转发/延迟正常" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
