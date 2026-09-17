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

截图落在 `verify/shots/`：

| 文件 | 内容 |
|---|---|
| `01_main.png` | 主菜单（证明客户端真的起来了） |
| `02_settings_menu.png` / `03_list.png` | 设置列表页（看行布局、按钮有没有被挤出面板） |
| `11_panel_open.png` / `12_panel_after.png` | CoopPanel 打开时 vs 往池子里加料后（两张不一样 = 实时刷新 OK） |

原理：`Xvfb` 提供离屏 X，`SDL_VIDEODRIVER=offscreen` 让 SDL 走 EGL，Mesa 软渲染（llvmpipe）出画面；
截图由 `verify/client/Driver.java`（一个驱动 mod）用 `ScreenUtils.saveScreenshot` 自己抓。
驱动 mod 的参数：`-Ddrv.mode=list|coop`、`-Ddrv.out=<目录>`。

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
