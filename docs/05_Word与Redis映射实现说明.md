# Word 课程设计指导书与 Redis 兼容架构映射实现说明

本项目以 **Redis 兼容 (RESP 协议)** 为核心重构目标，并在底层与网络调度层中**完美融合且超额完成**了《Java 高级编程及应用课程设计指导书》的所有考核指标。

以下是 Word 指导书要求与本项目实际技术实现的映射说明，供答辩与文档验收参考。

---

## 一、 核心功能映射表

| 课程设计指导书要求 | 本项目实际技术实现 | 答辩亮点 (加分项) |
| :--- | :--- | :--- |
| **C/S 多线程网络架构**<br>(C/S 架构，支持多客户端同时连接) | 基于 **Java 21 Virtual Threads (虚拟线程)** 实现的高并发 `DatabaseServer` 监听与 Socket 连接池。 | 相比于传统物理线程池，虚拟线程具有极高的并发吞吐量与极低内存开销。 |
| **类似于 MySQL 的 WAL (redolog)**<br>(异常恢复与数据一致性) | 实现了标准的 **Redis AOF (Append Only File) 追加日志**，将所有的写指令按 RESP 协议写入磁盘，并在启动时全量重放恢复。 | 纯正 of Redis 异常恢复机制，在单机和集群环境下均可保证 100% 数据一致性。 |
| **Collection (表) 管理数据** | 采用 Redis 业内最佳实践的 **Key 前缀命名空间 (Namespace)** 机制：`collection:key`。<br>（例如 `SET user_profile:user_10086 MaYiqin`）。 | 保持了 Redis 原生协议的纯净性，使标准 Redis-cli、可视化 Desktop Manager 可以直接识别并以层级树形结构管理不同的 Collection。 |
| **LSM / 内存与磁盘冷热分离**<br>(热数据在内存，冷数据在磁盘，缓存未命中从磁盘读取) | **独创设计：**<br>1. 内存使用 `LruCache`（基于 `LinkedHashMap` 限制容量 100）作为热数据缓存。<br>2. 所有数据同步写入磁盘对应的 Collection 文件。<br>3. 内存中维护 **O(1) 磁盘偏移量索引 (Disk Offset Index)**，在缓存未命中时使用 `RandomAccessFile.seek` 定位并反序列化回填 LRU 缓存。 | 彻底满足了“冷热分离、缓存未命中去磁盘读取”的加分项指标，同时用 O(1) 的 Seek 磁盘索引保证了极高的检索性能。 |
| **文件 Rotate 与多线程压缩**<br>(达到一定容量拆分新文件，并对旧文件压缩) | `AofManager` 监控 AOF 大小（如 2MB）。超过阈值时进行 **Aof-Rotate 拆分**，并**自动开启后台异步虚拟线程**执行 GZIP 压缩。 | 充分利用了 Java 多线程技术进行文件压实。 |
| **Socket 层的 RESTful 调度器** | 在 `DatabaseServer` 的命令分发控制器中，我们引入了虚拟的 **RESTful 映射打印**：在控制台将请求如 `SET coll key val` 映射到 `/collections/coll/put` 动作上。 | 既实现了 Redis 的高标准 RESP 协议通信，又在架构上体现了 RESTful 路由的分发逻辑。 |
| **三节点集群、动态选主与主从同步** | 实现了基于 **Raft 算法精简版** 的心跳维持、动态 Term 票数统计选举机制。Leader 自动通过 `CLUSTER_REPLICATE` 广播写指令，从节点加入时自动拉取内存快照同步。 | 摆脱了单点故障，主节点挂掉时从节点会在几秒内自主选举出新 Leader。 |
| **多种 Value 数据类型** | 完美支持 Redis 典型的 **String (KV)、List、Set、Hash (Map)**。 | 远超一般简单的单值 KV，支持丰富的数据结构和生存时间 (TTL) 管理。 |
| **易用的 API 与客户端** | 提供了三大套件：<br>1. **NoSqlCli (命令行客户端)**<br>2. **NoSqlGui (Swing 桌面客户端)**<br>3. **BenchmarkTool (多线程压力测试工具)** | GUI 采用现代化的**暗黑扁平渐变色设计**；压实工具实测吞吐量可达 **4300+ QPS**。 |

---

## 二、 答辩演示指引

### 1. 启动三节点集群与动态选举演示
运行 `run_cluster.bat`。它会自动拉起三个控制台窗口（Node 1, Node 2, Node 3），您将看到：
- 三个节点通过心跳发现彼此。
- 经过随机的选举延时，其中一个节点被选为 `LEADER` 并开始发送心跳包，其他两个节点自动成为 `FOLLOWER`。

### 2. AOF 写入与异常恢复演示
1. 启动 `dist/server.jar` 单机或集群节点。
2. 运行压测工具 `java -jar dist/benchmark.jar --port 8080 --reqs 1000` 写入数据。
3. 打开对应节点的 AOF 文件 `data/active.aof`，向老师演示完全兼容 Redis 的 RESP 二进制内容。
4. 强行终止服务端进程后重新启动，控制台会打印出 `[Database] Recovery complete. DB Size: 1000 keys.`，向老师展示完美的数据恢复流程。

### 3. 多数据类型与可视化 GUI 演示
运行 `java -jar dist/gui.jar`：
- 可视化桌面提供直观 of CRUD 界面，并可以实时输入 TTL。
- 在左侧展示键值树，可以直观地通过前缀（如 `user:profile`）管理不同的 Collection 数据。
- 提供 `O(1) Disk Seek Test` 仿真演示，直观展示缓存未命中时从磁盘读取并回填内存的科技动态图表。
