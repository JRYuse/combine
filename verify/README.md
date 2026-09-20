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
驱动 mod 的参数：`-Ddrv.mode=list|coop|gen|status|conn|bp|wall|rep|pwr|tech|tech2|technode`、`-Ddrv.out=<目录>`
（`tech` = 主菜单直接开科技树；`tech2` = 进图后再开、并把每棵根树的树页都切一遍截图；`technode` = 把镜头居中到组合连接器/液体卸载器节点再截图，用来核对节点在不在两棵树上、图标对不对）
（`gen` = 核反应堆 + 一台容量 10 万的发电机，看燃料条/发电效率；`status` = 方块状态菱形 + 容器面板；
`conn` = 两台组合工厂 + 一串组合连接器，看连接器贴图/连线与信息面板；
`bp` = 蓝图里存组合连接器，重进游戏后还在不在；`wall` = 组合墙修满不再显示破损；
`rep` = 用户复现存档（stainedMountains）连续存读档，看物品总量会不会涨，
数据目录用 `verify/make-repro-dataset.sh` 造）。

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

## 测试清单（哪个测试要哪套数据）

| 测试 | 需要的模组 | 验什么 |
|---|---|---|
| `combine.dbg.SanityCheck` | 任意 | combine 真的加载了吗（防"假过"） |
| `combine.dbg.DetachTest` | js 或 java 扩展方块 | 设置里开关组合立刻拆池/还原/恢复 |
| `combine.dbg.FilterTest` | js/java 扩展方块 | 显示可组合/不可组合 只列能开关的；NoCombo 排除的不出现 |
| `combine.dbg.ContentTableTest` | 任意 | 手动名单不改内容表（`-Dseed=<方块名>`） |
| `combine.dbg.NodeLinkTest` | `coop-producer`（java 测试模组） | 节点**不自动连线**；点目标=连/断、点已连目标=断开、不会误断另一边；点节点自己=全断 |
| `combine.dbg.PowerSplitTest` | `coop-producer` | 节点/连接器断开后电力真的断开（不只是 graph 不同） |
| `combine.dbg.TechTreeTest` | 任意 | 科技树数据体检：全树无 null 造价节点、组合连接器/节点/液体卸载器在塞普罗与埃里克尔两棵树上（研究材料分星球）；并模拟别的模组留下的坏节点，验证兜底能修好 |
| `combine.dbg.ConnectorSaveTest` | `coop-producer` | 连接器接上两组合体后同池/同电网；存读档后依然同池、物品一份不少 |
| `combine.dbg.TurretAmmoTest` | 任意（有炮塔+容器） | 仓库↔炮塔：每种弹药都进、弹仓按台数放大、从池里扣 |
| `combine.dbg.TurretCoolantTest` | `liquid-maker`（java 测试模组） | 冷却液选择：空选自动取效果最好的、手选生效、没货回退 |
| `combine.dbg.SaveRoundTripTest` | 任意 | 核心+容器 存读档物品不翻倍 |
| `combine.dbg.ModStorageCoreTest` | 需要"别的模组写的仓库"（`verify/make-modstorage-fixture.sh` 造一个） | 模组仓库挨着核心照样扩容、且不被组合压掉 |
| `combine.dbg.CoreCapacityTest` | 任意 | 造/拆容器不丢不涨、存读档一分不差、仓库链读档不涨 |
| `combine.dbg.UnitBarTest` | 任意 | 组合单位工厂/升级厂有原版那些 bar（含单位数量/上限） |
| `combine.dbg.GeneratorNuclearTest` | 任意（有 thorium-reactor） | 核模式发电效率 = 燃料 / 核反应堆总容量（≤1），组合进大容量建筑不再要巨量燃料 |
| `combine.dbg.LinkWallRepairTest` | 任意（有 copper-wall + mend-projector） | 组合墙血池：伤害整组分摊；修复投影能把整组修到满血（不再永远"破损"） |
| `combine.dbg.NewBuildAfterLoadTest` | 任意 | 读档后新建/拆掉组合建筑，核心与各组合建筑的物品总量一份不差 |
| `combine.dbg.DerelictRepairTest` | 任意 | team=derelict 的废墟：排一串计划（蓝图框）后应当**全部**立刻被修好 |
| `combine.dbg.HeatProducerTest` | 任意（有 slag-heater） | 矿渣制热机：每台对外只报**自己**那份热量（邻着 N 台不会被算 N 遍），组内需热方仍拿到整组热量 |
| `combine.dbg.LaunchLoadoutKeyTest` | 任意 | 发射蓝图与核心的对应：`Planet.defaultCore` 指向组合核心；serpulo/erekir 发射不会退回 core-shard 蓝图（Erekir 不该要铜/铅） |
| `combine.dbg.BlueprintReloadTest` | 任意（先 `-Dbr.phase=write` 再 `-Dbr.phase=read`） | 蓝图里的组合连接器：按客户端顺序（读蓝图早于模组建方块）会被丢掉，模组重读蓝图库后恢复 |
| `combine.dbg.LoadDedupeWindowTest` | 任意 | 读档头几帧再合并"同一份池子的副本"必须**去重**（不翻倍）；窗口结束后真库存照常相加 |
| `combine.dbg.NoModCompatTest` | 任意（两阶段：先带模组 `-Dmode=write`，再用**不带模组**的数据目录 `-Dmode=read`） | 关掉模组后存档还能读：地图区里只写原版字节，组合建筑回落到原版建筑（模组自己加的方块回落成空气），物品总量一分不差 |
| `combine.dbg.ComboChunkSaveTest` | 任意 | 自定义存档块真的在搬运模组字段：发电机选中的燃料/炮塔选中的弹药/组合墙 breakTimer 存读档后还在，组合体仍共用一个池子、物品不翻倍 |
| `combine.dbg.AssemblerPayloadTest` | 任意 | 组装机（UnitAssembler）要交的建筑 payload 收不收：配方里的 `PayloadStack.item` 必须已经换成组合实例（还是老实例的话 `acceptPayload` 里 `b.item == payload.content()` 恒 false —— 用户报的"组装机不收建筑输入"）；顺带卡浅层扫描的耗时与幂等 |
| `combine.dbg.HalfBuiltBreakTest` | 任意 | 造到一半的建筑要能拆：挂座认领着的那一格被玩家下拆除指令后必须松手（不能把拆除又 construct 回去） |
| `combine.dbg.CorePoolPullTest` | 任意（有核心/容器/组合节点/组合工厂） | 核心 + 并仓仓库 + 用组合节点接进来的组合工厂：接上时池子必须还是**核心那一份**（不能把核心库存搬到工厂里），断开后工厂要脱离、核心物品一份不少 |
| `combine.dbg.PowerAccountTest` | 任意（有组合工厂/电源） | 组合体耗电记账：3 台组合冶炼厂 + 一侧电源 → 电网"需要"必须正好是整组之和，而且**在线摆出来**与**读档读出来**两条路必须一模一样（联机服务端/客户端对不上的根因） |
| `combine.dbg.PowerGridAuditTest` | 任意（有组合工厂/电源/电力节点） | 组合体↔电网：放好/读档后按原版连接规则重算分量，必须同图、必须有 updater、必须有产出（S1~S8：同类/跨类型组合工厂、发电机组、节点跨距离接电、协作组合、连接器链、电池） |
| `combine.dbg.PowerFreshLoadTest` | 任意 | 两阶段：`-Dpf.mode=write` 摆一个密集混合基地（组合工厂群+协作组合+节点/连接器+电源）并存档，`-Dpf.mode=read` **另起进程**读档，整张图电网必须自洽、不用刺激（`-Dpf.seed=` 换随机基地） |
| `combine.dbg.PowerFuzzTest` | 任意 | 电网不变量模糊测试：随机放/拆方块 + 随机存读档，每步检查「同一分量同一张活电网 + 有电源必须发电」（`-Dfz.seed=` / `-Dfz.steps=`；失败会打印操作历史，`-Dfz.replay=<文件>` 可回放） |

## 复现排版问题

`run-client.sh` 支持 `DRV_UISCALE=<百分比>` 把 UI 缩放调大（等于把逻辑宽度变小）：

```bash
DRV_UISCALE=200 verify/run-client.sh mx /tmp/mp_coop/data list   # 小逻辑宽度下看排版
```
