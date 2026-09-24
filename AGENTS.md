# 规则：这个仓库（组合工厂 / combine）

## 写完 / 改完必须跑验证器

**不要只靠读代码就下结论**，改完按下面顺序跑（细节见 `verify/README.md`）：

1. 编译：`./gradlew --offline deploy` → 产物 `build/libs/combine.jar`
2. headless 逻辑测试（至少两套数据集，含 js 和 java 扩展建筑各一套）：

   ```bash
   verify/run-headless.sh mx /tmp/mp_coop/data combine.dbg.DetachTest
   verify/run-headless.sh mx /tmp/mp_cj/data   combine.dbg.DetachTest
   ```

   涉及"设置里的开关 / 名单 / 过滤"就再跑 `combine.dbg.FilterTest` 和
   `combine.dbg.ContentTableTest`。

3. 改动涉及 **UI / 面板 / 布局 / 绘制**（设置列表、CoopPanel、各种 display 面板）→
   必须跑真客户端截图，两张图要对得上：

   ```bash
   verify/run-client.sh vanilla /tmp/mp_coop/data list   # 设置列表
   verify/run-client.sh vanilla /tmp/mp_coop/data coop   # CoopPanel 实时刷新
   verify/run-client.sh mx      /tmp/mp_coop/data list   # 再用 MindustryX 跑一遍
   ```

   截图在 `~/sd/shots/`（自动建目录、按 001_ 002_ 连续编号），用看图工具确认（例如按钮有没有被裁掉、数字有没有变）。

4. 报告结论时给**证据**：跑的是哪个 jar / 哪套模组、关键数字（容量、数量、id 顺序）、截图文件路径。

## 清理

- 临时测试类、探针 print、`[dbg]` 日志 —— **用完就删**，不要提交进仓库。
- `verify/` 下的东西是长期工具，可以提交；`verify/build/`、`verify/shots/` 是产物，不提交。

## 其他约定

- **交付模组**用：`verify/deliver.sh`（等价于下面三步，别漏）
  1. 用**兼容安卓的编译命令**：`./gradlew --offline deploy`
     （`deploy` = desktop + android 合并，产物里有 `classes.dex`；只跑 `./gradlew jar` 出来的是纯桌面包，安卓端装不上）
  2. 交付前**删掉所有调试日志/探针**（`[dbg]`、`dbgTicks`、`System.out.print`、驱动用的 print 之类），脚本会 grep 检查
  3. 编译产物放 `~/sd`：只放 `~/sd/combine.jar`
     （**不要**另外生成带版本号的名字，使用者自己改名字）
  4. 不要动mod.hjson
- 提交信息用中文，说清"根因 + 改法"。
- 推送用 22 端口常超时，走 443：
  `GIT_SSH_COMMAND="ssh -p 443 -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20" git push git@ssh.github.com:R112007/combine.git master`

## 真联机验证（`run-mp.sh` + `lagnet.py`）：线程数必须有界

```bash
verify/run-mp.sh official /tmp/mp_cj/data 200 20 80   # 官方服务端 + 真客户端，代理加 200ms 延迟 / 20% UDP 丢包
```

它起三个进程：真·官方 headless 服务端、`verify/lagnet.py`（链路代理）、真客户端（Xvfb 软渲染，边跑边截图），
最后比对"服务端出现过的每种状态，客户端是不是都看到过"。用法见 `verify/README.md` 第 6 节。

- **改 `verify/lagnet.py` 必须跑 `verify/lagnet-selftest.py`**：1200 个包 @60/s 压一遍，
  线程数峰值必须是**个位数**、零丢包、RTT ≈ 2×latency。
- **禁止 per-packet / per-event 起线程或进程**：一个流向一条常驻 `Link`（连接建立时创建、断开时 `close()`）。
  旧版本每转一个 UDP 包 `Link()` 一次 = 每包泄漏一条永不退出的线程；2.5 分钟攒到 786 条，
  把 proot（单线程 ptrace 事件循环）拖成活锁，**整个会话（含 codex 自己）一起冻死** ——
  2026-09-22 两次实测（16:17:50、14:54:47，同一个 `run-mp.sh ... 200 20 80`）。
  所有长跑工具同理，细节见 `/root/.codex/AGENTS.md` 里 proot 那一节。
- lagnet 自带 `--max-threads`（默认 64）自检：超了打印原因并主动退出，不让泄漏累积；
  `run-mp.sh` 每跑一次都会打印线程数峰值（原始采样在 `verify/build/mp-threads.log`）。
- 排查"会话卡死"：`ps -o pid,stat,cmd | grep -E 'codex|java|python3'` 出现 `t (tracing stop)`
  = proot 活锁（`TracerPid` 指向那个终端的 proot）；`kill -9 <proot pid>`，再按 `TracerPid` 收掉残留 tracee。

## 验证次数上限：**同一个问题最多跑 4 次验证**，别浪费时间

一个"问题"（一次改动 / 一个要查清的现象）最多跑 **4 次**验证命令（编译算 1 次；同一批测试的一次成套运行算 1 次）。
第 4 次之后必须停手写结论：要么已经全绿，要么把"还没验到的部分 + 原因"如实写进报告，
不能靠再跑一遍碰运气。

- 同一份代码、同一套数据目录、同一个测试 —— **不要重复跑**（跑第二遍只有两个结果：
  一样的结果，或者环境抖动带来的假结果，两种都没信息量）。
- 失败先看**证据**（日志/截图/关键数字）再决定要不要重跑：
  数据目录不匹配（缺 `coop-producer`/`liquid-maker`/`冶炼厂`）、两阶段测试没带
  `-Dxx.mode=write` 这类**用法问题**不算一次"验证"，改对用法再跑一次即可；
  只有"同一件事、同样的跑法，跑第二遍"才消耗次数。
- 这台机器是 proot（单线程 ptrace）：**并行跑多个 java/客户端进程不会更快**，
  只会互相拖慢（实测两套 suite 同时跑时每个测试从 ~40 秒涨到 ~10 分钟，还跑出过假超时）。
  一次只跑一个验证进程，优先用小而准的目标测试（先 `DetachTest`/`ComboReflectBridgeTest`，
  再上全套；真客户端/联机这类几分钟的活单独排）。
- 报告里写清"跑了几次、每次跑的是什么"：没跑到的（例如超时/数据集不适用）要明确标出来，
  不许把"没跑"写成"过了"。
