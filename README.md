# VeinMinerPlus

VeinMinerPlus 是一个面向 Minecraft 1.21.1 NeoForge 的服务端权威连锁挖掘模组，提供普通连锁、范围连锁和爆破连锁模式，并支持将连锁挖掘产生的掉落物批量回存到 Applied Energistics 2 网络。

> 当前版本：**3.2.0**
> Minecraft：**1.21.1**
> NeoForge：**21.1.233**
> Java：**21**
> 许可证：**MIT**

## 功能

### 连锁挖掘

- 普通连锁：沿相邻方块搜索同类方块。
- 范围连锁：支持 `1x1`、`3x3` 等范围模式。
- 爆破连锁：支持同类方块、所有矿石、所有可挖方块和原木模式。
- 服务端权威执行，客户端只负责按键和界面显示。
- 支持工具耐久、附魔、经验、方块实体、双层植物以及 NeoForge/其他模组的标准破坏事件。
- 支持 TPS 保护：服务器负载升高时自动降低速度、暂停或停止任务。
- 连锁数量和每 Tick 数量都可以通过配置界面调整。

### AE2 连锁闪存卡

新增物品：`veinminerplus:chain_memory_card`

中文名称：**🐖咪牌内存卡**

使用方式：

1. 手持连锁闪存卡。
2. **潜行右键** AE2 设备、线缆或其他可取得 AE 网络节点的方块，绑定维度、坐标和点击面。
3. **潜行右键空气或普通方块**，清除绑定。
4. 开始连锁挖掘时，模组按照主手、副手、快捷栏、主背包的顺序寻找第一张已绑定卡片。
5. 本次任务开始后会固定目标快照，即使中途移动、替换或清除卡片，也不会改变本次结算目标。

卡片会记录：

- 目标维度；
- AE 节点方块坐标；
- 绑定时点击的方块面。

连锁结束后，掉落物会先在内存中按物品组件聚合，再批量尝试插入目标 AE2 网络。聊天框会显示完整存入、部分存入或全部回退掉落的结果。

AE2 不存在时，卡片仍保持注册以保护已有存档数据，但兼容功能会安全失效，原有连锁挖掘功能不受影响。

### 数据安全与回退

- AE 网络不存在、节点失效、网络离线、能源不足或存储空间不足时，未存入的物品不会被静默吞掉，而是按原逻辑掉落在玩家附近。
- 支持跨维度目标和目标区块按需加载，不创建永久强加载票。
- 掉落回存由服务器 Tick 驱动的 `DropReturnGuardian` 负责重试和结算，不创建无法管理生命周期的后台线程。
- 玩家退出、目标维度不可用或重试超时后，会执行安全掉落回退并释放任务状态。
- `doBlockDrops=false` 时不会产生掉落，也不会向 AE2 插入物品。

## 性能优化

本版本的性能优化重点是减少搜索开销、控制单 Tick 工作量和降低掉落物对象数量，同时保留标准方块破坏流程的兼容性。

### 搜索与任务调度

- 使用稀疏区段索引，避免对大量空区段进行逐方块扫描。
- 对已经耗尽的区段进行快速跳过，避免重复搜索。
- 使用原生类型集合保存长坐标和区段数据，减少 `BlockPos`、装箱对象和临时集合分配。
- 将搜索、候选队列、已访问集合和统计数据拆分，降低重复读取和重复判断。
- 任务按 Tick 分片执行，不会因为一次超大连锁任务无限占用服务器线程。

### 动态 Tick 预算

- 在服务器 Tick 开始和结束阶段记录时间预算。
- 默认将约 `47ms` 作为完整 Tick 目标，并预留约 `2ms` 给 Tick 尾部逻辑。
- 健康 TPS 下动态授予更高的 VMP 工作预算；接近截止时间时主动停止当前切片。
- `8192` 是每 Tick 上限，不代表每 Tick 必定挖掘 8192 个方块；实际数量会受到标准方块破坏耗时和服务器 Tick 预算限制。
- 任务停止原因会通过性能日志输出，例如 `DEADLINE`、`TOTAL_LIMIT`、TPS 保护和玩家状态变化等。

### 掉落物与 AE2

- 连锁过程中的掉落物先进入缓冲区并按物品类型、数量和数据组件聚合。
- 每次任务结束时批量回存 AE2，避免每个方块都触发一次 AE2 存储操作。
- AE2 操作只在服务器主线程执行，避免访问 AE2 Grid、Level 和 BlockEntity 时产生线程安全问题。
- `DropReturnGuardian` 采用服务器 Tick 重试，而不是 Java 守护线程或虚拟线程；这样可以保证世界切换、服务器关闭和玩家退出时资源可控地释放。

### 当前测试结果

在大型整合包环境中进行 100,000 方块、爆破“所有方块”、每 Tick 配置上限 8192、掉落回存 AE2 的测试：

| 指标 | 结果 |
| --- | ---: |
| 总挖掘量 | 100,000 方块 |
| 活跃 Tick | 677 |
| 平均挖掘量 | 147.71 方块/Tick |
| 理论吞吐 | 约 2,954 方块/秒 |
| 搜索次数 | 390,307 |
| AE 回存耗时 | 3.59ms |
| AE 物品类型 | 54 |
| 存入 AE 的物品数 | 91,770 |

Spark 分析表明，当前主要耗时来自 Minecraft/NeoForge 的标准 `destroyBlock`、BreakEvent 和全局 Loot Modifier，而不是 VMP 搜索或 AE 回存。因此默认实现优先保证模组兼容性，不绕过标准破坏事件和战利品流程。

## 配置

可通过 NeoForge 模组配置界面或游戏内配置入口调整服务端规则，包括：

- 普通连锁最大方块数；
- 普通连锁每 Tick 最大挖掘数；
- 爆破连锁最大方块数；
- 爆破模式每 Tick 最大挖掘数；
- 爆破搜索距离；
- 是否消耗饱食度；
- 是否将已绑定卡片的掉落物优先存入 AE2；
- VMP 性能日志开关。

连锁数量范围：`1 - 2,100,000,000`。
每 Tick 数量范围：`1 - 8,192`。

## 构建

要求安装 Java 21。Windows 下在项目根目录执行：

```powershell
.\gradlew.bat clean build
```

构建产物：

```text
build/libs/veinminerplus-3.2.0-neoforge.jar
```

根项目的 Minecraft 1.21.1 NeoForge 版本使用 AE2 和 LDLib2 进行开发运行；`forge-1.20.1` 子项目不属于本次 3.2.0 NeoForge 优化范围。

## 依赖与参考项目

### 运行时/开发依赖

| 项目 | 用途 | 版本/关系 | 链接 |
| --- | --- | --- | --- |
| Minecraft | 游戏运行环境和原版方块、掉落、世界 API | 1.21.1 | [Minecraft](https://www.minecraft.net/) |
| NeoForge | 模组加载器、事件总线、配置和网络 API | 21.1.233 | [NeoForge](https://github.com/NeoForged/NeoForge) |
| Applied Energistics 2 | AE 网络节点解析、能源服务和物品存储 API | 可选，19.2.17，兼容范围 19.2.x | [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) |
| LDLib2 | 配置界面和 Modern UI 组件 | 必需，2.2.40 | [LDLib2](https://github.com/Low-Drag-MC/LDLib2) |
| fastutil | 长坐标、队列、集合等低分配数据结构；由 Minecraft/NeoForge 运行环境提供 | 间接依赖 | [fastutil](https://github.com/vigna/fastutil) |

### 构建与性能分析工具

| 项目 | 用途 | 链接 |
| --- | --- | --- |
| ModDevGradle | NeoForge Minecraft 模组 Gradle 开发插件 | [ModDevGradle](https://github.com/NeoForged/ModDevGradle) |
| Gradle Wrapper | 可复现的 Gradle 构建入口 | [Gradle](https://gradle.org/) |
| spark | TPS、MSPT、线程 CPU 和慢 Tick 性能分析 | [spark](https://github.com/Lucko/spark) |

AE2 在本项目中声明为可选依赖：未安装 AE2 时不会加载 AE2 专用兼容实现；LDLib2 当前为配置界面所需的必需依赖。

## 许可证

本项目使用 [MIT License](LICENSE)。第三方项目的代码、资源和许可证以各自仓库为准。

## 反馈

欢迎通过 GitHub Issues 提交问题，并附上：

- `latest.log`；
- VMP 性能日志中的 `[VMP Perf]` 行；
- spark 报告链接或 JSON；
- Minecraft、NeoForge、VeinMinerPlus 和整合包版本；
- 可复现步骤和配置截图。
