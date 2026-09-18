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
- 提交信息用中文，说清"根因 + 改法"。
- `mod.hjson` 的更新日志只写功能，不写"修复崩溃/改仓库"这类；写之前精简。
- `mod.hjson` 里**不能用半角引号**（HJSON 会被写坏）。
- 推送用 22 端口常超时，走 443：
  `GIT_SSH_COMMAND="ssh -p 443 -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20" git push git@ssh.github.com:R112007/combine.git master`
