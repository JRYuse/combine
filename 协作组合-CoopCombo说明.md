# 协作组合（CoopCombo）—— 让"继承原版类但写了新功能"的方块也能组合

> 这个仓库是 `combine`（组合工厂）的实验分支：**原仓库 `/root/combine` 一个字节都没动**，
> 本仓库 = 原仓库的一份拷贝 + 一个新机制 `src/combine/CoopCombo.java`。
> 不想要这个机制就把 `CoopCombo.enabled` 置 false，行为立刻退回原仓库。

## 1. 要解决的问题

组合工厂原本的组合做法是「**把原版方块整只换成 `CombinedXxx` 实例**」：
`Replacer` 拿 `BlockCloner` 深拷贝原方块字段，再 `Blocks.xxx = 组合实例`。
这套做法只对「和某个原版方块同名同构」的方块安全，所以 `Main.isExact()` 里
把**子类**过滤掉了：

* JS 模组 `extend(GenericCrafter, ...)` 出来的是 Rhino `JavaAdapter` 类（`adapter22` 这种）；
* Java 模组的具名子类，也一样不是"原版类本身或它的匿名子类"。

被过滤的代价是：**这些方块完全不能组合**。而一旦强行替换成 `CombinedCrafter`，
它们自己的东西（例如废土科技 `library.js` 的多配方逻辑、每台机器各自的配方配置、
自定义 `updateTile` / 面板 / 存档字段）就全没了 —— 也就是你说的"组合后所有功能丧失"。

## 2. 本办法怎么做（不替换方块、不改 build 类）

换一条路：**方块类、build 类一个字节都不动**，只从外部把相邻同类机器的
「库存模块」接在一起。

1. **分组**：相邻（4 邻接）、同队、同一个方块实例的机器算一组（`proximity` BFS 求连通分量）。
2. **并池**：整组共用同一个 `ItemModule` / `LiquidModule` ——
   一台机器吃到的料，全组都能用；一台机器产出的东西，全组都能往外送。
   这跟组合工厂其它组合建筑的语义一致。
3. **容量相加**：`block.itemCapacity` / `block.liquidCapacity` 临时放大成
   「基础值 × 该方块最大组员数」。这些方块自己的容量判断读的就是这两个字段
   （例：`library.js` 里 `this.items.get(item) >= this.getMaximumAccepted(item)`，
   而 `getMaximumAccepted` 就是 `block.itemCapacity`），所以**不碰它们的代码**，
   它们就自动按"整组容量"来吃料/存货。
4. **拆组按比例分池**：一个模块被两个分量同时引用（刚被拆开）时，
   按各分量的容量占比把物品/液体拆开；拆卸掉的成员那一份留在组里，不丢东西。
5. **读档去重**：存档里每个成员都写了一份同样的池子，读档后第一次重算做去重
   （内容相同＝副本，丢弃而不是相加），否则库存翻倍。

因为全程只改 `items` / `liquids` 两个引用和 `itemCapacity` / `liquidCapacity` 两个数字，
**这些方块的所有新功能原样保留**：多配方、每台机器各自的配置、自定义 `updateTile`、
自定义面板、自定义存档字段…… 全部照旧，只是"库存"变成了整组共享。

## 3. 适用范围 / 开关

```java
CoopCombo.enabled            // 总开关，默认 true
CoopCombo.allowCrossType     // 允许不同类型互相组合，默认 true
CoopCombo.keepIntermediates  // 跨类型组保护中间产物，默认 true
CoopCombo.debug              // 每次重算打日志
CoopCombo.blacklist          // 按方块内部名单独排除
CoopCombo.maxAbsorbProbeSiblings / maxAbsorbProbeTypes   // 中间产物判定的性能护栏
CoopCombo.captureBaseCaps()  // 记基础容量（内容装配时自动调一次）
CoopCombo.rebuild()          // 手动重算（一般不用，放置/拆除事件会自动触发）
```

只有这些家族的子类才会被接管（`CoopCombo.familyOk`）：

* `GenericCrafter`（含 `AttributeCrafter` / `HeatCrafter` / `Separator` 及一切子类）
* `Drill`（含 `BeamDrill` / `BurstDrill`）、`WallCrafter`
* `Pump`、`SolidPump`
* `StorageBlock`（**不含** `CoreBlock`）

另外：
* **原版方块一律不碰**（类名 `mindustry.*` 直接跳过），
  免得把 `oil-extractor` 这类"combine 没替换的原版子类"顺手也连起来；
* combine 自己替换出来的组合方块（类名 `combine.*`）也跳过，它们走原本那套机制。

## 4. 跨类型组合（不同方块也能连成一组）

`CoopCombo.allowCrossType = true`（默认）时，**相邻的不同方块**只要都属于上面那些家族，
就会连成同一组：共用物品/液体池、容量相加。典型用法就是自己搭的生产链直接挨着放，
例如「冶炼厂（粗铜→熔融铜）+ 冷却机（熔融铜+水→黄铜）」这种上游/下游机器贴一起。

跨类型带来一个新问题：**中间产物**（A 产、B 要用的东西）会被 A 的搬运逻辑直接倒到组外的
传送带上，B 反而吃不到（这正是 combine 自家组合建筑里"矿渣外流"同款毛病）。
本办法用两条外部手段压住它（都不能改方块代码）：

1. **搬运顺序重排**：把每台成员 `proximity`（原版 dump()/put() 找邻居的顺序）重排成
   `[组内会吃料的机器邻居] → [组外邻居(传送带/容器/…)] → [组内纯仓储邻居]`。
   组内机器只接受自己需要的物品/液体，所以：中间产物被组内吃掉；纯产品组内没人收，
   自然落到组外正常出货；纯仓储邻居排最后，不会把出货口堵死。
2. **搬运轮转归零**：原版 dump() 是从 `cdump` 这个轮转下标开始找邻居的，光排顺序不够——
   轮转会漂到组外邻居上。所以只要池子里还有"组内有人要"的货，就把跨类型组成员的
   `cdump` 压回 0，保证每次搬运都先喂组内机器。（判定结果按"池子内容签名"缓存，
   不会每帧去问 JS 方块的 acceptItem。）

同一种方块之间没有中间产物问题，所以这些手段只作用于**混合类型组**，单类型组保持原版手感。

## 5. 已知边界（这一版故意不做）

| 项 | 现状 | 说明 |
| --- | --- | --- |
| 跨类型组合 | **已支持**（默认开） | `CoopCombo.allowCrossType = false` 可退回同类型 |
| 连接器/节点（ComboNet） | 不参与 | 协作组合只看真实相邻 |
| 电力/热量共享 | 不共享 | 每台机器照原样各自接电，和原版一致 |
| 面板显示"组合 xN" | 不显示 | 要动它们的 build 类才能挂面板，与本办法的前提冲突 |
| 一次 craft 里"连发多颗"倒货的库 | 只能保住一半 | 例如 library.js：它在一次 craft 里连做 20 次 put()，轮转下标没法在帧内归零，实测约 50% 的中间产物仍会被倒出（纯原版式子类工厂是 0% 漏出） |
| 容量粒度 | 方块级，取"最大组" | 同类型的散装单机上限也会跟着变松（只松上限，不丢物品） |

## 6. 实测（headless，真实加载废土科技 0.7.3 的 JS 方块）

测试类：`/tmp/mp1/com/combine/dbg/CoopTest.java`、`CoopPerf.java`、`VanillaSafe.java`

用 `废土科技-冶炼厂`（`library.js` 的多配方工厂，`adapter22`，基础容量 70、液体 60）：

```
[COOP] PASS 两台相邻
[COOP] PASS 被 combine 替换?（应为 false，保持原类）
[COOP] PASS 两台共用同一个物品模块
[COOP] PASS 两台共用同一个液体模块
[COOP] PASS 组容量 = 2×基础 (140)
[COOP] PASS getMaximumAccepted 跟着变大 (140)
[COOP] PASS 各自配方配置保留 (a1=1.0 a2=0.0)          ← 新功能还在
[COOP] PASS 机器仍在按自己的配方生产（液体>0: 120.0）    ← 新功能还在
[COOP] PASS 液体是共享池（同一个模块）
[COOP] PASS 拆组后不再共用模块 / 容量回落 / 没丢物品
[COOP] PASS 4 台成组共用模块, 组容量 = 4×基础 (280)
[COOP] PASS 拆开后按容量比例分 (左 1/3≈67 右 2/3≈133), 容量回到 2×
[COOP] PASS 读档后仍是同一池, 库存没翻倍 (60 ≈ 60)
[COOP] PASS 存储子类也能组合（安山合金容器 200→400）
[COOP] RESULT ALL PASS (pass=22)
```

跨类型（`CoopItemChain` / `CoopCrossTest` / `CoopVanillaStyle`）：

```
废土科技液体链 : 外部液罐收到中间产物(熔融铜)=0，下游产出黄铜=420       ← 中间产物全留在组里
普通子类工厂   : 上游产石墨 -> 下游吃石墨产硅
   keepIntermediates = true : 石墨漏到组外 =   0，硅产出 = 118
   keepIntermediates = false: 石墨漏到组外 = 112，硅产出 =   7          ← 对照：几乎全漏掉
```

性能（144 台 JS 机器连成一个大组）：

```
旧库(协作组合 OFF): 4.442 ms/tick，容量 70，无共享
新库(协作组合 ON):  4.531 ms/tick，容量 10080，共享池      → 机制本身约 +0.09ms/帧
拆/建重算:          2.6 ms/次（其中绝大部分还是那一帧 JS updateTile 的开销）
```

原版无副作用检查：加载地图 + 摆一堆原版方块跑 30 帧后，
**所有原版方块的 itemCapacity/liquidCapacity 一个都没变**。

## 7. 点击查看组合体内容的面板（CoopPanel）

这些方块是「我们改不了 `display()` 方法的 JS/子类方块」，它们自己的信息面板里插不进整组池子；
原版那个点击方块弹出的小库存面板（`BlockInventoryFragment`）又只在"点击没被配方面板吃掉"时才出现
（多配方工厂这类 `configurable` 方块点了先弹配方），所以本库自带一个同款面板：

* **点击组合体** → 在**被点击建筑的正上方**浮出小面板（水平居中、底边贴建筑顶边留 4px；上方塞不下自动翻到正下方；贴屏幕边会自动夹在屏幕内）（仿 `BlockInventoryFragment`：`Tex.inventory` 背景、
  物品/液体图标网格、点第二次或点别处收起、方块失效/世界重载自动收起）；
* 内容：`协作组合 xN + 方块名` / `构成: 各种方块各几台` / **整组共享的物品池**（量/每种上限）/
  **整组共享的液体池**（量/上限）—— 也就是把共享池的真实内容显示出来；
* **只给协作组合接管的方块**（`CoopCombo.eligible`）：原版方块、combine 自己替换出来的组合建筑都不弹
  （后者面板里本来就有整组池子）；
* 开关：`CoopPanel.enabled`；绘制全程 try/catch（同 `ComboUi` 思路），面板异常不会把游戏带崩。
* 配套工具：`CoopPanel.describe(build)` 返回同样的文字摘要，排查/写测试都用它。

## 8. 组级收料（管道贴哪一侧都能进料）

这些方块的 `acceptItem/acceptLiquid` 是**按自己当前配方**判断的。经典例子：

* 冶炼厂（粗铜 → 熔融铜）**不要水**，冷却机（熔融铜 + 水 → 黄铜 + 废料）**要水**；
* 组合成一组之后，如果水管只贴着冶炼厂那一侧，冶炼厂会拒收水 → **水根本进不了组**
  （看起来就像"冷却机不接受水的输入"）。

组合体语义上是一台大机器：**只要组里有人要，就该收得进来**。所以 `CoopCombo.groupIntake`
（默认开）会从贴着组的**运输类方块**（传送带/管道/路由器/运输桥/储液罐…）把"组内有人要的"
物品/液体搬进共享池，等于替那个要料的成员把货接下来：

* 只在「贴着的这个成员自己不肯收」时才动手（正常路径仍走游戏自己的 acceptItem/acceptLiquid）；
* 组里必须真的有成员愿意收；
* 不会去掏别的机器/容器的库存（那是装卸器/卸载器的活）；
* 节奏/上限可调：`intakeInterval`（默认 2 tick）、`intakeItemsPerSource`（2）、`intakeLiquidsPerSource`（20）。

实测（废土科技 冶炼厂+冷却机，水管只贴冶炼厂）：

```
改前: 组内水=0.0    管道剩水=500  → 冷却机永远没水可用
改后: 组内水=120.0  管道剩水=380  → 整组按组容量(60+60)把水收进来
```

**顺带澄清一个易混点**：冷却机的配方写的是「废土科技-水」，所以
`acceptLiquid(废土科技-水)=true`、`acceptLiquid(原版 water)=false`（实测）——
喂原版水进不去是那个模组配方本身的设计，不是组合体的问题。

## 9. 组内共享电力（等价 conductivePower = true）

原版规则（`BuildingComp.getPowerConnections`）：两台挨着的机器如果**都只是耗电方**
（`consumesPower && !outputsPower`），默认**不会**并到同一个电网 —— 每台都得自己接电；
只要其中一台是导电体（`power-node`/电池那种 `conductivePower = true`），两边才并网。

组合体语义上是一台大机器，所以本库在**该方块确实成组（≥2 台）**时把它的
`conductivePower` 打开，并对已有的建筑调 `updatePowerGraph()` 让它立刻并网；
组没了就还原成原值（`new PowerGraph().reflow()` 重新划分）。散装单机保持原版行为，
不会顺手变成导线去桥接别的电网。开关：`CoopCombo.sharePower`（默认 true）。

实测（废土科技 冶炼厂 ×2，太阳能板只接在其中一台那一侧）：

```
sharePower = true : 同电网=true  a.power.status=1.0  b.power.status=1.0   ← 两台都有电
sharePower = false: 同电网=false a.power.status=1.0  b.power.status=0.0   ← 对照组：第二台没电
```

## 10. 任意 Java Block 子类都能用吗？—— 能，附带实测

机制本身**不认类**，只看方块/build 身上有没有可共享的"挂点"：

| 效果 | 实现方式（都不需要改对方代码） | 对类的要求 |
| --- | --- | --- |
| 物品共享 | 整组把 `build.items` 指向同一个 `ItemModule` | build 有 items 模块（`hasItems`） |
| 液体共享 | 整组把 `build.liquids` 指向同一个 `LiquidModule` | build 有 liquids 模块（`hasLiquids`） |
| 容量扩容 | `block.itemCapacity` / `block.liquidCapacity` 设为组内各成员基础容量之和 | 同上（读的就是这两个字段） |
| 电力共享 | 成组时把 `block.conductivePower` 打开（等价于原版导电体），拆组还原 | `hasPower` |
| 热量共享 | 每帧建筑更新之后，把组内产热方的 `heat()` 之和写进耗热方的 `heat` 字段 | 耗热 build 有 `heat` 浮点字段（HeatCrafter/HeatConductor/Turret 都有） |

`CoopCombo.anyFamily`（默认 true）= 不再限制"必须是某个原版家族的子类"，任何 **非原版、非 combine 自己**
的方块，只要带上面任一资源就能成组（`CoopCombo.blacklist` 仍可单独排除；`shareHeat/groupIntake/sharePower` 可单独关）。

实测：本仓库之外另写了一个**纯 Java** 测试模组（`CoopTestMod`：`GenericCrafter`/`HeatProducer`/`HeatCrafter`
子类，各自带自定义 `updateTile`），10 项全过：

```
PASS Java 子类方块都被识别为可组合        PASS 原版方块仍然不碰
PASS 物品池共享 (同一模块)               PASS 容量扩容 (10 → 20)
PASS 电力共享 (只接一侧，两台都通)        PASS 自定义 build 行为保留 (a=120 b=120)
PASS 液体池共享                          PASS 组内液体能互相用 (water=79.8)
PASS 热量共享：隔着中间那台也吃得到热 (heat=10.0)   ← 产热方和耗热方不相邻、只同组
```

（对照：`shareHeat=false` 时不补热。）

## 11. 同步过来的老库修复

本库已经跟老库 `/root/combine` 保持同步（文件与老库逐字节一致）：

* 组合连接器/组合节点的放置掉帧修复：反射字段/方法按 Class 缓存（缓存时一次性 setAccessible，
  "没有这个字段/方法"单独记），事件驱动的网络重建改成"置脏 + 每帧最多一次"。
  实测 256 台组合体（两片各 128 台）的基地：`ComboNet.rebuild()` 4.2ms → **0.27ms**，
  「在组合体之间放连接器那一帧」9.09ms → **0.2ms 额外开销**；
* 组合建筑物品外送的搬运粒度修复（每 tick 搬运，塞得满传送带/管道）；
* 建造武器启停修复：按 E（`Binding.pauseBuilding`）/ 手机端暂停建造时，**所有**建造挂座都看单位的
  `updateBuilding` 开关（原先只有第一个线程停、模组多出来的挂座照旧施工）；玩家操控的单位一旦清空
  建造队列（原版 Q = clearBuilding），挂座也会放手，不再替队伍计划偷偷施工；
* MindustryX 环境音线程崩溃修复（`java.util.NoSuchElementException: 24`，栈在
  `CombinedGenerator.firstAvailableFuel`）：MindustryX 把环境音放在独立 `AudioThread`（20fps）里，
  每条循环都会调 `item.shouldAmbientSound()`，而原版默认实现就是 `return shouldConsume();` ——
  发电机这一路会读库存、遍历 `content.items()` 这个共享序列，主线程一改就跨线程炸。
  现在**所有组合建筑统一禁用环境音循环**（音频线程完全不碰游戏逻辑），
  同时发电机的燃料查找改成遍历"所有物品的快照数组"，再也不碰共享 Seq 的迭代器。

## 12. 怎么用

1. 本仓库构建：`cd /root/combine-ext && ./gradlew --offline deploy`（产物 `build/libs/combine.jar`）。
2. 装它 + 目标模组（例如废土科技），把两个同类子类机器挨着放 —— 它们会自动并成一池。
3. 想关掉：`CoopCombo.enabled = false`（或把方块名加进 `CoopCombo.blacklist`）。
