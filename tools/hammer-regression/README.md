# 压缩锤子与连锁重复掉落回归

根因：杖继承的 Useless Mod 挖掘流程先产生原始掉落实体，再收集实体并按压缩锤子配方替换掉落。
连锁在中间的 `BlockDropsEvent` 阶段把压缩圆石提前存入自己的缓存，杖随后又独立生成砂砾，造成原材料返还。
修复只在杖的 `StaffMiningContext` 生效且压缩锤子模式开启时，让该流程自行完成掉落处理。

在项目根目录执行：

```powershell
python tools/hammer-regression/run.py
python tools/hammer-regression/run.py --jar build/libs/veinminerplus-4.2.1-neoforge.jar
python tools/hammer-regression/run.py --jar build/libs/veinminerplus-4.2.2-dev1-neoforge.jar
```

第二条是旧产物的负对照，预期退出码 1；其余预期退出码 0。
测试覆盖不开连锁、单块爆破、8 块爆破、8 块普通连锁、关闭压缩锤子模式、原版镐手动挖掘。
每次使用新的独立存档，统计地面和背包中的掉落，并断言方块全部移除、掉落事件数等于挖掘数。
未附魔的压缩锤子模式每块应得到 9 个砂砾、0 块压缩圆石。

依赖直接复制自提供的测试包：Useless Mod 2.4.6、Useless Stretcher 1.6.3、ExDeorum 3.12、AE2 19.2.17、GuideME 21.1.17。
开发运行时使用项目的 NeoForge 21.1.233；原整合包使用 21.1.248，尚未进行该整合包内的客户端验收。
使用本地 Java 21 和 Gradle 缓存；从已有 userdev 中读取开发运行配置以避免重新下载，不修改正常构建配置。
测试源码放在独立 source set，正常产物不包含测试类。

产物模式只在独立诊断副本中附加测试类，并逐项核对原产物所有 ZIP 条目内容不变。
证据保存在 `build/hammer-regression/run-时间戳/`，包括日志、原产物 SHA256 和条目审计。
提供的整合包、存档和 mods 目录不会被修改。
