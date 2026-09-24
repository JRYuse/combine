# 验证器（组合工厂 / combine）

改完代码**必须跑这里的东西**再下结论，别只靠读代码。三层，从便宜到贵：

| 层 | 命令 | 用时 | 能验什么 |
|---|---|---|---|
| 编译 | `./gradlew --offline deploy` | ~7s | 语法/编译 |
| headless 逻辑测试 | `verify/run-headless.sh mx <数据目录> combine.dbg.DetachTest` | ~10s | 分组、并池/拆池、容量、电力、过滤、内容表 —— 在**真实游戏类**里跑 |
| 真客户端截图 | `verify/run-client.sh mx <数据目录> list\|coop` | ~1.5min | UI：设置列表/按钮布局、CoopPanel 是否实时刷新（真的截图看） |

## 0. 环境（这台机器已经配好，换机器照做）

- Java 21、`pacman -S --needed xorg-server-xvfb mesa glu libxi libxss`
- aarch64 Linux 的 arc SDL native：仓库里带着 `verify/native/libsdl-arcarm64.so`
  （官方 jar 里只有 x64/Win/mac 的；要重编见 `verify/native/build-sdl-native.sh`）
- 用到的 jar：
  - 官方/自编原版：`~/Mindustry/desktop/build/libs/Mindustry.jar`
  - MindustryX：`/root/sd/x.jar`（它没带 headless 后端，headless 跑的时候自动补官方 160.1 的）
  - 官方 160.1 服务端：`/tmp/server160.jar`（来源见下）

## 1. 造数据目录（= 游戏 mods 目录）

```bash
verify/make-dataset.sh /tmp/mp_coop/data /path/to/wasteland        # 废土科技（js 扩展建筑最多的那套）
verify/make-dataset.sh /tmp/mp_cj/data   /path/to/cooptestjava.jar # 纯 java 扩展建筑测试模组
```

常见的几套（手里有就照抄）：

| 目录 | 内容 |
|---|---|
| `/tmp/mp_coop/data` | combine + 废土科技（103 个扩展建筑） |
| `/tmp/mp_cj/data` | combine + cooptestjava（java 子类，液体/热量模块都全） |
| `/tmp/mp_sf/data` | combine + 饱和火力 3.4.4.3 |
| `/tmp/mp_ve/data` | combine + vanilla-expansion 2.1.1.H |

## 2. headless 逻辑测试

```bash
verify/run-headless.sh mx /tmp/mp_coop/data combine.dbg.DetachTest
verify/run-headless.sh mx /tmp/mp_cj/data   combine.dbg.DetachTest
verify/run-headless.sh mx /tmp/mp_coop/data combine.dbg.FilterTest
verify/run-headless.sh mx /tmp/mp_coop/data combine.dbg.ContentTableTest -Dseed=container
```

- `DetachTest`：设置里关掉/打开某建筑组合 → 物品/液体是否立刻拆开、容量与导电性还原、重新打开是否立刻恢复；顺带验列表分类
- `FilterTest`：`显示可组合 / 显示不可组合` 只列能手动开关的建筑，且和关掉的状态对得上
- `ContentTableTest`：设置里的手动名单**不能**改动内容表（`-Dseed=<方块名>` 模拟"上次关过它"）

写法：`combine.dbg.*` 的自定义类放进 `verify/tests/`，`run-headless.sh` 会一起编译。

## 3. 真客户端截图（UI 改动必跑）

```bash
verify/run-client.sh vanilla /tmp/mp_coop/data list    # 设置→组合工厂 那一页
verify/run-client.sh vanilla /tmp/mp_coop/data coop    # CoopPanel 实时刷新
verify/run-client.sh mx      /tmp/mp_coop/data list    # 换 MindustryX 再跑一遍
```

截图落在 `~/sd/shots/`（脚本会自动建目录；文件名带跨次运行的连续序号 001_、002_…，多次跑不会互相覆盖）：

| 文件 | 内容 |
|---|---|
| `01_main.png` | 主菜单（证明客户端真的起来了） |
| `02_settings_menu.png` / `03_list.png` | 设置列表页（看行布局、按钮有没有被挤出面板） |
| `..._panel_open.png` / `..._panel_after.png` | CoopPanel 打开时 vs 往池子里加料后（两张不一样 = 实时刷新 OK） |

原理：`Xvfb` 提供离屏 X，`SDL_VIDEODRIVER=offscreen` 让 SDL 走 EGL，Mesa 软渲染（llvmpipe）出画面；
截图由 `verify/client/Driver.java`（一个驱动 mod）用 `ScreenUtils.saveScreenshot` 自己抓。
驱动 mod 的参数：`-Ddrv.mode=list|coop|gen|status|conn|bp|wall|rebuild|rep|pwr|tech|tech2|technode|mega|pool|userpanel`、`-Ddrv.out=<目录>`
（`tech` = 主菜单直接开科技树；`tech2` = 进图后再开、并把每棵根树的树页都切一遍截图；`technode` = 把镜头居中到组合连接器/液体卸载器节点再截图，用来核对节点在不在两棵树上、图标对不对）
（`gen` = 核反应堆 + 一台容量 10 万的发电机，看燃料条/发电效率；`status` = 方块状态菱形 + 容器面板；
`conn` = 两台组合工厂 + 一串组合连接器，看连接器贴图/连线与信息面板；
`bp` = 蓝图里存组合连接器，重进游戏后还在不在；`wall` = 组合墙修满不再显示破损；
`rebuild` = 核心机（玩家单位）走原版 B 键框选 rebuildArea + 拖一排原地转向计划：
4x4 台 derelict 废墟 + 4 台被摧毁建筑 + 8 台改方向的传送带，看是不是当场全做完
（截图 `*_rebuild_before.png` / `*_rebuild_after.png`，日志里报"废墟/重建/转向"各多少）；
`rep` = 用户复现存档（stainedMountains）连续存读档，看物品总量会不会涨，
数据目录用 `verify/make-repro-dataset.sh` 造）。
（`mega` = mace + 两台 oct 融合成组合巨兽：看身体有没有画出来（混合编组会升空 → 走飞行层）、
会飞的巨兽尾部有没有按体型画出的引擎、信息面板上的力墙条有没有超 100%、指挥模式单位列表用的
图标/指令是不是巨兽自己的（原版面板按 `unit.type.id` 取 `content.unit(id)` 的 uiIcon 与 commands，
派生类型共用基础巨兽的占位 id，占位图标原来写死成 dagger、指令也只有 [移动, 组合]，
所以 poly 这类工程单位合体后 自动重建/辅助建造/治疗建筑/挖矿 会消失）
（截图 `*_mega_world.png` / `*_mega_hud.png` / `*_mega_panel.png` / `*_mega_command.png`，
日志里报 dominant/drawScale/力场上限/bar 比例/引擎参数/面板图标与代表成员图标是否同一个、指令表是不是并集、两艘同型船合体速度有没有砍半/水阻按不按船那套算、
会飞的巨兽是不是一直悬空 + 悬在水面上拆不拆得开）；
`pool` = 4 台并排的组合发电机共享一口池子、灌满燃料 → 开信息面板截图 → 拆掉一台成员再看：
面板里要能看到池子里的燃料、拆成员不能按容量销毁库存
（截图 `*_pool_panel_full.png` / `*_pool_panel_after.png`，日志里报组容量/池总量/世界总量）；
`shipmega` = 两艘 risso 在**深水**里合体：看它是不是按原版船那套算地形速度系数（1.3，未处理水阻时只有 0.2）、
还浮不浮在水面上（截图 `*_ship_mega.png`，日志里报 巨兽/原版船 的 speed×系数=有效速度）；
`userpanel` = 用**用户存档**（数据目录里带 saves/*.msav，例如 `/tmp/mp_usr3/data`）读档，
挑几口"最有货"的组合工厂池子把信息面板弹出来截图，并把面板里的文字打进日志
（看面板是不是真的把池子列出来了））

**注意**：软渲染下一帧很慢（~5fps），截图之间要留够时间，别用 0.5 秒的小间隔下结论。

## 4. 换 jar / 换版本

```bash
MINDX_JAR=/path/to/other.jar  verify/run-headless.sh mx /tmp/mp_coop/data combine.dbg.DetachTest
MINDUSTRY_JAR=/path/to/Mindustry.jar verify/run-client.sh vanilla /tmp/mp_coop/data list
```

官方 160.1 的客户端/服务端 jar 可以从 GitHub 取（服务器上用到的 headless 后端就在服务端 jar 里）：

```bash
curl -L -o /tmp/server160.jar https://github.com/Anuken/Mindustry/releases/download/v160.1/server-release.jar
curl -L -o /tmp/mind160.jar   https://github.com/Anuken/Mindustry/releases/download/v160.1/Mindustry.jar
```

## 5. 交付

```bash
verify/deliver.sh        # = 兼容安卓编译 + 检查调试残留 + 只把 jar 放到 ~/sd/combine.jar
```
带版本号的文件名由使用者自己改，脚本不生成。

## 6. 真联机验证（`run-mp.sh`：真服务端 + 真客户端 + 链路模拟）

前几层都是单进程测试。"单位到底有没有同步过去 / 客户端画没画出来"必须在**真联机**里验：

```bash
verify/run-mp.sh official /tmp/mp_cj/data            # 本地链路（无延迟无丢包）
verify/run-mp.sh official /tmp/mp_cj/data 200 20 80  # 200ms 单向延迟 + 20% UDP 丢包，剧本跑 80 秒
verify/run-mp.sh official /tmp/mp_coop/data 200 20 80
```

三个进程：官方 headless **专用服务器**（`~/sd/server-release.jar`，用 FIFO 喂 `host` 命令）、
`verify/lagnet.py`（socket 代理，加延迟/抖动/UDP 丢包）、**真客户端**（Xvfb + 软渲染，边跑边截图到 `~/sd/shots/`）。
服务端按剧本 造单位 → 融合成组合巨兽 → 解体 → 再融合，客户端每秒记录自己看到的世界；
脚本最后比对"服务端每个**阶段边界**出现过的状态，客户端是不是都看到过"（漏了 = 没同步 / 幽灵 / 看不见），
截图路径和线程数峰值都会打印出来。单次约 4~5 分钟（客户端软渲染加载就要 1.5~2 分钟）。

排查"客户端少看到某个单位 / 单位是幽灵"时加 `MP_VERBOSE=1`：两端每秒把每个单位逐个打一行
（`[MP-DBG]`，含类名/类型/位置/血量/成员数），平时别开 —— 一次跑就是几千行。
客户端还会打一行 `[MP-DRAW]`（巨兽的屏幕坐标、`region` 有没有贴图、`clipSize`/`flyingLayer`、
`elevation`、迷雾判定、镜头位置），**看不到巨兽时先看它**：
镜头没框住（屏幕坐标跑到画面外）和真的没画（`region=null`）是两回事。

**物品账目对账**：两端每秒各打一行 `[MP-ITEMS] ms=<epoch> total=<按模块身份去重的世界物品总量>`
（共享池只算一次），脚本判两件事：①全程不许出现**负数**；②同一时刻两端数量差不能超过
`服务端/20 + 50`（200ms 链路下客户端晚一个快照，几十个差是正常的，11m 那种量级差必须判失败）。
服务端剧本里还有一段**节点乱连**：服务端只摆一个"只会被连来连去、不消耗物品"的小基地
（只有铜，在场工厂配方都不吃铜），然后由**客户端**每 3 秒随机连一根线/断一根线
（走玩家点击那条原版配置通道 `Building.configure → Call.tileConfig`），复现"客户端瞎连"。

**改 `lagnet.py` 必须跑自检**（回归"线程泄漏拖垮 proot"这个坑）：

```bash
verify/lagnet-selftest.py                    # 默认 1200 包 @60/s，单向 50ms
verify/lagnet-selftest.py --count 3000 --rate 150 --latency 200   # 压更狠一点
```

判定：线程数峰值 **个位数**（<20）、零丢包、RTT ≈ 2×latency、SIGTERM 后立刻退出。

**线程数有界是硬要求**（proot 环境下不是性能问题，是"整个会话会不会冻死"的问题）：

- 一个流向一条常驻 `Link`（连接建立时创建、断开时 `close()`）；**绝对不要每包 `Link()`**。
  旧版本就是每收一个 UDP 包 new 一个 Link（每个 Link 起一条永不退出的 `_run` 线程），
  2.5 分钟攒到 786 条线程、每秒上千次 futex 唤醒 → proot（单线程 ptrace 事件循环）进活锁，
  整个容器所有进程一起 `t (tracing stop)`，**连 codex 会话本身都没了响应**（2026-09-22 实测两次）。
- `lagnet.py --max-threads`（默认 64）：线程数超上限就打印原因并主动退出（宁可这次测试失败重跑）；
  每 10 秒的统计行会带 `线程=N(峰值 M)`。
- `run-mp.sh` 会全程采样线程数（原始数据 `verify/build/mp-threads.log`），跑完打印峰值 —— 数字是结论的一部分。

**卡死怎么认、怎么救**：整棵进程树 `ps -o pid,stat,cmd` 显示 `t (tracing stop)`、
`grep TracerPid /proc/<pid>/status` 指向那个终端的 `proot`、proot 自己单核空转且 SIGTERM 不理，
就是 proot 活锁了（不是游戏卡、也不是 API 超时）。`kill -9 <proot pid>`，
再按 `TracerPid` 把残留 tracee 逐个 `kill -9`，顺手清掉 `/tmp/.X11-unix/X99` 等残留。
详细规则见仓库 `AGENTS.md` 和 `/root/.codex/AGENTS.md`。

## 测试清单（哪个测试要哪套数据）

| 测试 | 需要的模组 | 验什么 |
|---|---|---|
| `combine.dbg.SanityCheck` | 任意 | combine 真的加载了吗（防"假过"） |
| `combine.dbg.DrillBeamGrowTest` | 任意（有 beam 模式钻头 = 激光/等离子钻头） | 用户报的"组合钻头 beam 模式的后台物品增长"：**区块物品产出**（`sector.info.rawProduction`）原来在 beam 分支恒为 0 —— `updateBeam()` 直接 `items.add(drop, 1)`，没走原版 `offload()`（`offload` 会先 `produced()` 计入 `handleProduction`），所以物品进了池子、区块产量却一直不涨（同一类修复见 `CombinedCrafter.craft()` 与分离机分支）。现在改走 `offload()`（并保留 "efficiency>0 + 组容量" 闸门）。判定：不接电 900 tick 增长 0；单台+电源 900 tick 产 5、相邻两台共享池 11 ≈ 2×（不是平方）；挂真 sector 后 rawProduction 从 0 → 0.083（= 5/60，与池子产出对得上）|
| `combine.dbg.ComboReflectBridgeTest` | 任意（有组合单位工厂/发射台） | **通用反射桥**：`ComboReflect` 对三种形态的组合建筑都必须成立 —— ①接口 default 方法（组合单位工厂的 `leader()/group()/rebuildCombo()/comboPreUpdate()` 全是 `IUnitCombo` 的 default 方法，实现类自己不声明，`getDeclaredMethod` 顺着类层次找不到，必须去接口里找）；②类自己写的方法（发射台/着陆台）；③只有字段没有方法的组合仓库（`group()` 兜底返回自己）。判定是**行为**不是"没抛异常"：塞 `pendingLeaderPos` 后调 `preUpdate()`，`comboLeader` 必须真的换成那台；加第三台邻居后调 `rebuildLocal()` 组必须变 3。改造前一旦漏了 default 方法这条，`preUpdate`/`rebuildLocal` 会**静默失效**（读档并池、连线变分组全不动，但一声不响） |
| `combine.dbg.MegaRepairFireTest` | **combine + combineunit 两个 jar**（例如 `/tmp/mp_both/data`） | 用户报的"组合了 mega 的巨兽单位在 mega 武器去修复建筑的时候，其他武器的开火会损坏己方建筑"：①基线——同队 `Damage.damage` 与同队单位的子弹都**不会**伤到己方建筑（原版有敌我判定，实测 160→160，共 18 发）；②"借火"（`UnitComboFire`）修前会把同组空闲成员的武器照着开火者跟踪的**己方建筑**（维修/建造武器跟踪的就是它）或**瞄准点压在己方建筑上**时开火，实测己方半血墙各被借火打 14 发；修后这两种情况都是 0 发，而敌方目标照常 14 发。③巨兽自己 `groupable=false`（不参与借火），这一点也印在日志里，免得把"巨兽"和"普通组合"两条路径混起来查 |
| `combine.dbg.CheatModCompatTest` | **combine + combineunit + 无敌作弊模组**（例如 `/tmp/mp_cheat/data`） | 用户报"组合单位和 `/root/sd/questions/` 里的无敌作弊模组冲突，直接崩"（附了安卓崩溃日志：`combineunit.units.UnitComboDamage.replaceUnitConstructors` → Rhino `JavaAdapter` 定义失败 → `suppressed NoClassDefFoundError: combineunit/units/entities/CUnitEntityLegacyAlpha`）。根因：作弊模组 `invincible-ship.js:132` 写的是 `m.constructor = prov(() => extend(UnitTypes.alpha.constructor.get().class, {...}))` —— 继承 **alpha（核心机）构造器产出的类**；而我们把**所有**单位类型的构造器都换成了自己的镜像类，于是它去继承模组类，安卓的内存 dex 解析不了 → 适配器定义失败 → 异常从我们的 `register()` 抛出去 → 整个模组加载崩。修法：①核心机（alpha/beta/gamma/evoke/incite/emanate 及任何 coreUnitDock 类型）**一律不换构造器**（它们本来就不参与组合）；②两处取样都包 try/catch（别的模组的动态构造器取样失败就跳过，不冒到 Mod.init 外面）。判定：alpha/beta/gamma 的构造器仍是 `mindustry.gen.*`；作弊模组单位 `invincible-cheat-mod-v8-invincible-ship` 能造出来且**父类 = `mindustry.gen.UnitEntityLegacyAlpha`**（修前会是 `combineunit.units.entities.CUnitEntityLegacyAlpha`）；一个"取样就抛"的假构造器不会弄崩 `register()`；dagger 照旧被换成镜像；三件模组同装仍能合体 |
| `combine.dbg.UnitComboBridgeTest` | **combine + combineunit 两个 jar**（例如 `/tmp/mp_both/data`） | 跨仓库反射桥：`combine.util.UnitComboBridge` 必须真的把工厂造出来的单位交给 combineunit 打组合标记 —— 无限火力下让组合单位工厂产出单位，用反射问 combineunit 的 `UnitComboDamage.comboId(Unit)`，必须 = 组长坐标 + 1（不是 0），且单位的实体类是 combineunit 的镜像类。只装 combine 时打印 SKIP 退出（不算失败） |
| `combine.dbg.DetachTest` | js 或 java 扩展方块 | 设置里开关组合立刻拆池/还原/恢复 |
| `combine.dbg.FilterTest` | js/java 扩展方块 | 显示可组合/不可组合 只列能开关的；NoCombo 排除的不出现 |
| `combine.dbg.ContentTableTest` | 任意 | 手动名单不改内容表（`-Dseed=<方块名>`） |
| `combine.dbg.NodeLinkTest` | `coop-producer`（java 测试模组） | 节点**不自动连线**；点目标=连/断、点已连目标=断开、不会误断另一边；点节点自己=全断 |
| `combine.dbg.PowerSplitTest` | `coop-producer` | 节点/连接器断开后电力真的断开（不只是 graph 不同） |
| `combine.dbg.TechTreeTest` | 任意 | 科技树数据体检：全树无 null 造价节点、组合连接器/节点/液体卸载器在塞普罗与埃里克尔两棵树上（研究材料分星球）；并模拟别的模组留下的坏节点，验证兜底能修好 |
| `combine.dbg.ConnectorSaveTest` | `coop-producer` | 连接器接上两组合体后同池/同电网；存读档后依然同池、物品一份不少 |
| `combine.dbg.TurretAmmoTest` | 任意（有炮塔+容器） | 仓库↔炮塔：每种弹药都进、弹仓按台数放大、从池里扣 |
| `combine.dbg.TurretClientAmmoTest` | 任意（有炮塔+容器+组合节点） | 用户报"客户端视角内炮台弹药时常归零然后恢复"：服务端炮塔组囤弹 → 取成员的 `writeSync` 字节（= 服务端发的 block snapshot）→ 在另一台炮塔上 `readSync`（= 客户端收快照做的事）→ 客户端看到的弹量必须和服务端**完全一致**。旧行为：原版 `ItemTurretBuild.read` 按**单台** maxAmmo 夹 + 用 short 存数量，3 台 duo 囤到 90 客户端只读出 30，囤到 40000 客户端读出 **-25536**（弹药条直接归零） |
| `combine.dbg.MultiBuildPerfTest` | 任意（有铜墙/核心/传送带） | 用户报"服务端和客户端使用多线程建造后帧率下降十分明显"：量"空闲 / 多线程建造中 / 建造后"的每 tick 耗时（真实 delta 1/60），`-Dperf.block=wall\|conv` 选建组合墙还是传送带、`-Dperf.net=1` 打开 ComboNet 分段计时。修前：传送带 2.32ms/tick（峰值 27.7ms）、组合墙 4.10ms/tick（峰值 59.4ms）；修后：0.14ms / 1.29ms（峰值 1.8ms / 19.9ms） |
| `combine.dbg.WallReplaceTest` | 任意（有铜墙/铅墙/核心） | 用户报"在墙上覆盖新的墙建造的时候多线程建造武器不工作"：8 面铜墙铺好后排 8 个"盖成铅墙"的计划（原版允许同类同尺寸墙互相覆盖，`Block.canReplace`），真实 delta 跑，要求核心机 1 秒内全部盖完、且不比摘掉建造武器的对照组慢。修前：挂座一个都不认领这种计划，只有单位自己一秒一格 → 实测 476 帧（7.9 秒，比对照组 229 帧还慢）；修后 9 帧（0.15 秒） |
| `combine.dbg.TurretCoolantTest` | `liquid-maker`（java 测试模组） | 冷却液选择：空选自动取效果最好的、手选生效、没货回退 |
| `combine.dbg.SaveRoundTripTest` | 任意 | 核心+容器 存读档物品不翻倍 |
| `combine.dbg.ModStorageCoreTest` | 需要"别的模组写的仓库"（`verify/make-modstorage-fixture.sh` 造一个） | 模组仓库挨着核心照样扩容、且不被组合压掉 |
| `combine.dbg.CoreCapacityTest` | 任意 | 造/拆容器不丢不涨、存读档一分不差、仓库链读档不涨 |
| `combine.dbg.UnitBarTest` | 任意 | 组合单位工厂/升级厂有原版那些 bar（含单位数量/上限） |
| `combine.dbg.ClientSnapshotWipeTest` | 任意（有组合工厂 + 物品） | 联机快照不再清空物资：取「跟随者」的 `writeSync` 字节（= 服务端发的 block snapshot）再 `readSync`（= 客户端悬停看物品时做的），整组共用池子必须一份不少（旧行为：快照里是空模块 → `ItemModule.read` 直接清空整组） |
| `combine.dbg.GeneratorPoolKeepTest` | 任意（有 combustion-generator） | 组合发电机满池后拆掉一台成员：池子不被按容量截断（容量只拦新物品进入）、世界物品总量守恒 |
| `combine.dbg.GeneratorNuclearTest` | 任意（有 thorium-reactor） | 核模式发电效率 = 燃料 / 核反应堆总容量（≤1），组合进大容量建筑不再要巨量燃料 |
| `combine.dbg.LinkWallRepairTest` | 任意（有 copper-wall + mend-projector） | 组合墙血池：伤害整组分摊；修复投影能把整组修到满血（不再永远"破损"） |
| `combine.dbg.NewBuildAfterLoadTest` | 任意 | 读档后新建/拆掉组合建筑，核心与各组合建筑的物品总量一份不差 |
| `combine.dbg.DerelictRepairTest` | 任意 | team=derelict 的废墟：排一串计划（蓝图框）后应当**全部**立刻被修好 |
| `combine.dbg.RebuildAreaTest` | 任意（有 copper-wall + conveyor） | 核心机走原版 rebuildArea（废墟+被摧毁建筑）与"拖一排原地转向"：装本模组建造武器的核心机要 1 秒内全做完，对照组（摘掉建造武器）当基线。**必须用真实 delta（1/60）跑**，delta=1 会把原版"一秒一格"的节流掩盖掉 |
| `combine.dbg.HeatProducerTest` | 任意（有 slag-heater） | 矿渣制热机：每台对外只报**自己**那份热量（邻着 N 台不会被算 N 遍），组内需热方仍拿到整组热量 |
| `combine.dbg.LaunchLoadoutKeyTest` | 任意 | 发射蓝图与核心的对应：`Planet.defaultCore` 指向组合核心；serpulo/erekir 发射不会退回 core-shard 蓝图（Erekir 不该要铜/铅） |
| `combine.dbg.BlueprintReloadTest` | 任意（先 `-Dbr.phase=write` 再 `-Dbr.phase=read`） | 蓝图里的组合连接器：按客户端顺序（读蓝图早于模组建方块）会被丢掉，模组重读蓝图库后恢复 |
| `combine.dbg.LoadDedupeWindowTest` | 任意 | 读档头几帧再合并"同一份池子的副本"必须**去重**（不翻倍）；窗口结束后真库存照常相加 |
| `combine.dbg.NoModCompatTest` | 任意（两阶段：先带模组 `-Dmode=write`，再用**不带模组**的数据目录 `-Dmode=read`） | 关掉模组后存档还能读：地图区里只写原版字节，组合建筑回落到原版建筑（模组自己加的方块回落成空气），物品总量一分不差 |
| `combine.dbg.ComboChunkSaveTest` | 任意 | 自定义存档块真的在搬运模组字段：发电机选中的燃料/炮塔选中的弹药/组合墙 breakTimer 存读档后还在，组合体仍共用一个池子、物品不翻倍 |
| `combine.dbg.AssemblerPayloadTest` | 任意 | 组装机（UnitAssembler）要交的建筑 payload 收不收：配方里的 `PayloadStack.item` 必须已经换成组合实例（还是老实例的话 `acceptPayload` 里 `b.item == payload.content()` 恒 false —— 用户报的"组装机不收建筑输入"）；顺带卡浅层扫描的耗时与幂等 |
| `combine.dbg.HalfBuiltBreakTest` | 任意 | 造到一半的建筑要能拆：挂座认领着的那一格被玩家下拆除指令后必须松手（不能把拆除又 construct 回去） |
| `combine.dbg.CorePoolPullTest` | 任意（有核心/容器/组合节点/组合工厂） | 核心 + 并仓仓库 + 用组合节点接进来的组合工厂：接上时池子必须还是**核心那一份**（不能把核心库存搬到工厂里），断开后工厂要脱离、核心物品一份不少 |
| `combine.dbg.CoreFactoryNodeTest` | 任意（有核心/容器/组合节点/组合工厂） | 用户报的「核心贴容器 + 组合节点接组合工厂，**拆工厂**时核心里的东西全没了」：断开工厂／拆掉工厂／拆掉网络里的组长工厂／拆掉贴着核心的容器，四条路核心库存都必须一份不少、世界总量不变 |
| `combine.dbg.ItemConservationFuzz` | 任意（有容器/工厂 + 组合节点） | 物品守恒模糊测试：随机 **造/拆方块（含拆节点/连接器）+ 给任意非核心组合建筑灌物品 + 随机连/断任意组合建筑（含节点互连、连接器、js/java 扩展建筑）+ 存读档 + 手动开关「协作组合」**。判定分档：**连/断/设置组合必须分毫不差**（用户报的"组合节点瞎连导致物品异常增长/减少"就是这条），造/拆只许"不增长 + 丢失不超过被拆模块"。`-Difz.seeds=40 -Difz.steps=400`；`-Difz.resetblocked=1` 清数据目录里被写脏的「手动不组合」名单 |
| `combine.dbg.NodePoolConserveTest` | 任意（有容器/工厂 + 组合节点） | "独立组合体（没并进核心）身上带物品，被节点反复连/断"的最小复现：每次连/断后按**模块身份去重**统计世界物品总量，必须一分不差；失败时逐建筑打印模块身份（`ItemConservationFuzz` 原来只给核心灌物品，核心池有专门的不拆分支，所以从没走到这条路上） |
| `combine.dbg.PoolDedupeBugTest` | 任意（有容器） | 读档去重合并的漏项 bug（用户报的"物品异常减少"）：`ComboNet.mergeDistinctItems` 原来 `for(i=1..)` 只搬 `unique[1..]`，而目标是 `moduleOfFirst()` 挑的（**不一定是 `unique[0]`**）—— 一旦不是，第 0 份库存永远不会被搬走，紧接着 `mergeComponent()` 把各成员都指向目标，那份库存就被静默丢掉。本测试构造成"目标不是第 0 份"的布局：合并后目标里必须同时有 A 的 100 铜和 B 的 50 铅 |
| `combine.dbg.SavePoolAuditTest` | **用户存档**（数据目录 `saves/` 下有 .msav，例如 `/tmp/mp_save/data`） | 用户存档的"组合池子审计"：读档后逐池子打印（模块身份 / 该分量容量 / 逐物品数量），并做四件事：①世界物品总量（按模块身份去重）与面板口径对照，抓"同一份库存被算两遍"；②存→读往返 3 轮不许改变总量；③空跑 600 tick 不许自己涨；④随机连/断组合节点、拆掉连线的成员，总量都不许多出来。用户报的存档（`sector-serpulo-20.msav`）读出来世界总量 **1,090,007,046**（一台激光钻机的池子：煤 522,167,664 / 硅 568,718,682，而该分量容量只有 1840），修复后降到 **124,380**、最大超容 2 倍 |
| `combine.dbg.NodeNetPoolSwingTest` | 任意（有钻机/容器/工厂 + 组合节点） | 照用户视频搭的场景（只有 combine + 生存图）：一坨组合建筑（6 台钻机 + 容器/工厂）+ 2 个组合节点，灌物品后反复连/断节点，**逐 tick** 核对按模块身份去重的世界物品总量必须零跳变；并检查面板"分子分母同源"：`ComboNet.panelItemCap(self)`（该分量 Σ 基础容量）必须 ≥ 面板显示的那份池子里的量。视频现场是"组合钻机 x6 的面板显示 铜 1482273/60、硅 5555520/60，且每帧在百万/几十之间乱跳" |
| `combine.dbg.DrillComboSpeedTest` | 任意（有机械钻头） | 矿机组合挖速：同一片矿 1 台 vs 相邻 3 台组合矿机跑同样 tick，产量必须按台数成倍（3 台 ≈ 3×），面板另有「整组挖速」一行 |

**组合巨兽的贴图规则**（`mega`/`legs`/`mech` 三个真客户端模式各拍一张对比图）：

- 本体一律**优先画代表成员的整只单位图** `unit-<名字>-full`（腿/履带/武器都在图里），
  只有拿不到真整图（模组单位常见：`fullIcon` 被兜底成躯干 region）才退到躯干 region；
- 整图里部件已画好时**不再叠**自己拼的那套部件（否则两套腿）；但**机甲**例外：原版机甲整图里
  的腿是"缩在身体底下的图标姿态"，只画整图看着像没腿，机甲一律再叠一层按体型画的机甲腿；
- 模组单位没有真整图时，一样要自己补部位（腿/机甲腿/履带）。
| `combine.dbg.PowerAccountTest` | 任意（有组合工厂/电源） | 组合体耗电记账：3 台组合冶炼厂 + 一侧电源 → 电网"需要"必须正好是整组之和，而且**在线摆出来**与**读档读出来**两条路必须一模一样（联机服务端/客户端对不上的根因） |
| `combine.dbg.PowerGridAuditTest` | 任意（有组合工厂/电源/电力节点） | 组合体↔电网：放好/读档后按原版连接规则重算分量，必须同图、必须有 updater、必须有产出（S1~S8：同类/跨类型组合工厂、发电机组、节点跨距离接电、协作组合、连接器链、电池） |
| `combine.dbg.PowerFreshLoadTest` | 任意 | 两阶段：`-Dpf.mode=write` 摆一个密集混合基地（组合工厂群+协作组合+节点/连接器+电源）并存档，`-Dpf.mode=read` **另起进程**读档，整张图电网必须自洽、不用刺激（`-Dpf.seed=` 换随机基地） |
| `combine.dbg.PowerFuzzTest` | 任意 | 电网不变量模糊测试：随机放/拆方块 + 随机存读档，每步检查「同一分量同一张活电网 + 有电源必须发电」（`-Dfz.seed=` / `-Dfz.steps=`；失败会打印操作历史，`-Dfz.replay=<文件>` 可回放） |

## 单位侧机制已经拆到 combineunit 仓库

组合巨兽（合体/解体/按成员推导属性）、共享承伤/修复、共享火力、手动编组 UI，连同
单位镜像实体类，都搬到了 **combineunit** 仓库；对应的逻辑测试与真客户端截图也在那边
（`combineunit/verify/`，包名 `combineunit.dbg.*`）。本仓库只留**建筑侧**：

- 组合单位工厂/升级厂/构造机/发射台/着陆台：`src/combine/units/`（`IUnitCombo` 是它们自己的
  组合簿记接口，留在这里）；
- 工厂造出单位后"打组合标记"这一个接触点，走 `combine.util.UnitComboBridge` 反射调
  combineunit 的 `UnitComboDamage.tagProduced`（没装 combineunit 时静默跳过，建筑侧功能不受影响）；
- 真客户端驱动里的 `mega`/`legs`/`mech`/`duo`/`shipmega` 场景与联机剧本（`run-mp.sh`）的
  **单位阶段**都需要数据目录里同时有 `combineunit.jar`（把 build/libs/combineunit.jar 放进
  `data/mods/` 即可），否则那些阶段只会打印"融合失败/没有巨兽"——这是"没装单位侧模组"的预期行为。

## 复现排版问题

`run-client.sh` 支持 `DRV_UISCALE=<百分比>` 把 UI 缩放调大（等于把逻辑宽度变小）：

```bash
DRV_UISCALE=200 verify/run-client.sh mx /tmp/mp_coop/data list   # 小逻辑宽度下看排版
```
