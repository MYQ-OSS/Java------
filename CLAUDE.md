# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

多线程 C/S 架构 NoSQL 分布式数据库系统（Java 21 课程设计项目）。兼容 Redis RESP 协议，支持单机/集群模式、简化 Raft 选主、主从复制、LSMT 存储引擎和 AOF 持久化。

## 构建与运行

没有 Maven/Gradle 构建系统，纯 `javac` + `jar` 编译。

```cmd
:: 一键编译打包（生成 dist/*.jar）
build.bat

:: 单机模式
java -jar dist/server.jar --port 8080 --mode standalone --data-dir ./data
:: 集群模式（启动 3 节点）
run_cluster.bat

:: 客户端
java -jar dist/cli.jar --host 127.0.0.1 --port 8080
java -jar dist/gui.jar
java -jar dist/benchmark.jar --host 127.0.0.1 --port 8081 --threads 20 --reqs 5000 --mode mix
```

手动编译：
```cmd
javac -encoding UTF-8 -d bin @sources.txt
jar cfe dist/server.jar nosql.server.DatabaseServer -C bin .
```

## 架构分层

```
src/nosql/
├── common/     # 协议层：RESP 编解码、NSP 二进制协议、DTO、序列化工具
├── server/     # 存储引擎：LSMT (MemTable+SSTable+HashIndex+LruCache)、AOF、TCP Server
├── cluster/    # 集群管理：Raft 选主、心跳、主从复制
├── client/     # 客户端：SDK、CLI 终端、Swing GUI
└── benchmark/  # 并发压力测试工具
```

## 核心架构要点

### 通信协议

项目同时定义了两套协议，但**实际运行使用 RESP 协议**：

- **NSP 协议** (`NspPacket`, `OpCode`)：自定义二进制协议，魔数 `0x2A3F`，定义阶段产物，`DatabaseServer` 不使用
- **RESP 协议** (`RespParser`)：Redis 标准序列化协议，`DatabaseServer` 直接通过 `RespParser.readRequest()` 解析客户端命令

`Request`/`Response`/`ProtocolUtil`（Java 原生序列化）是为 GUI 客户端旧接口 (`NoSqlSdk.put/get/delete`) 设计的 DTO，新 SDK 已改用 RESP 命令模式。

### 存储引擎 (LSMT)

读写路径：**LRU Cache → MemTable (`ConcurrentHashMap`) → HashIndex → SSTable 磁盘文件**

- `Engine`：核心引擎，包含 MemTable、过期管理、LSMT 刷写/压缩逻辑
- `HashIndex`：内存中 key → {SSTable文件名, 磁盘偏移量} 的映射，提供 O(1) 磁盘定位
- `SSTable`：磁盘有序表，Data Block + Index Block + Footer 三段式结构，二分搜索 Index Block 定位
- `LruCache`：基于 `LinkedHashMap` access-order 的 LRU 缓存，容量 500 条
- 刷写阈值默认 1000 个 key，SSTable ≥3 个时自动后台压缩

### 持久化 (AOF)

- `AofManager`：`active.aof` 追加写 + `fsync` 实时刷盘（`appendfsync always`）
- 达到 `rotateThreshold`（默认 2MB）时 rotate，旧段 ZIP 压缩归档
- 重启时按时间戳顺序回放所有 AOF 段 + active.aof

### 集群 (Raft 简化版)

`ClusterManager` 实现简化 Raft，所有通信走 RESP 协议（`CLUSTER_*` 命令前缀）：

- **选举**：随机超时 1500~3000ms → 过期发起选举 → 过半数 (`(peers+1)/2+1`) 晋升 Leader
- **心跳**：Leader 每秒广播 → Follower 收到后重置选举计时器
- **Term 比较**：term 小的消息被忽略，term 大的无条件承认（防止旧 Leader 复活）
- **主从复制**：**异步广播**（`CompletableFuture.runAsync`），不等待 Follower 确认 → 牺牲强一致性换性能
- **新节点同步**：启动 1.2s 后发 `CLUSTER_SYNC` 拉全量快照回放

### 写操作冲突处理（5 个关键位置）

| 冲突 | 处理位置 | 策略 |
|------|---------|------|
| 多节点同时选举 | `ClusterManager.resetElectionTimeout()` | 随机超时 + 同 term 只投一票 |
| 脑裂 | `ClusterManager.startElection()` | 过半数票机制 |
| 旧 Leader 复活 | `ClusterManager.handleHeartbeatPacket()` | term 比较降级 |
| 并发写同一 Key | `Database.execute()` 的 `synchronized` | 全局锁串行化 |
| 写操作到 Follower | `DatabaseServer.isWritable()` | 直接拒绝 + 返回错误 |

### Database.execute() 写流程

```
synchronized execute(cmd, replicate=true):
  1. AOF 追加 (aofManager.append)
  2. 内存执行 (dispatchCommand → Engine)
  3. 集群广播 (replicationListener.onReplicate → 异步发送 CLUSTER_REPLICATE)
```

### 服务端长连接模型

`DatabaseServer` 使用 Java 21 虚拟线程（`Executors.newVirtualThreadPerTaskExecutor()`），每个 TCP 连接一个虚拟线程，`socket.setSoTimeout(0)` 维持长连接，循环读取 RESP 命令直到 EOF。

### 数据类型支持

String、List (`ConcurrentLinkedDeque`)、Set (`ConcurrentHashMap.newKeySet()`)、Hash (`ConcurrentHashMap<String,String>`)，支持 TTL 过期。

## 关键文件入口

- 服务端主类：[src/nosql/server/DatabaseServer.java](src/nosql/server/DatabaseServer.java) — `main()` 入口，支持 `--port --mode --id --peers --data-dir --rotate-threshold` 参数
- 命令行参数解析全在各模块 `main()` 中手工实现，无统一 CLI 框架
- 数据目录结构：`data/` 下包含 `active.aof`、`aof_segment_*.aof.zip`、`sstable_*.sst`、`hash_index.idx`、`collections.meta`
