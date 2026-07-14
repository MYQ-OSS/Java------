# easy-db — 多线程 C/S 架构 NoSQL 分布式数据库系统

> **Java 21 课程设计项目** | 兼容 Redis RESP 协议 | 单机 / 集群双模式
>
> 马奕钦 (组长) & 黎展宏 (组员)

---

## 目录

- [1. 环境要求](#1-环境要求)
- [2. 快速开始（3 步跑起来）](#2-快速开始3-步跑起来)
- [3. 所有启动方式](#3-所有启动方式)
- [4. 支持的命令](#4-支持的命令)
- [5. Java SDK 编程调用](#5-java-sdk-编程调用)
- [6. HTTP RESTful API](#6-http-restful-api)
- [7. Shell 命令行工具](#7-shell-命令行工具)
- [8. 性能压测](#8-性能压测)
- [9. 单元测试](#9-单元测试)
- [10. 架构设计](#10-架构设计)
- [11. 项目结构](#11-项目结构)

---

## 1. 环境要求

| 依赖 | 版本 / 说明 |
|------|------------|
| **JDK** | Java 21 或更高 (`javac` + `jar` 需在 PATH 中) |
| **Python** | 3.x（仅编译脚本需要，用于扫描源文件） |
| **操作系统** | Windows 10/11（`.bat` 脚本直接双击运行） |

> **验证环境**：在终端中运行 `javac -version`，确认输出为 `javac 21.x.x`。

---

## 2. 快速开始（3 步跑起来）

### 第一步：编译打包

双击 **`build.bat`**，或在终端中运行：

```cmd
build.bat
```

编译 21 个源文件并打包为 5 个可执行 JAR，输出到 `dist/` 目录：

```
dist/
├── server.jar       # 数据库服务端
├── cli.jar          # 交互式命令行客户端
├── gui.jar          # 可视化桌面客户端
├── benchmark.jar    # 性能压力测试工具
└── shell.jar        # Shell 命令工具
```

### 第二步：启动服务端

双击 **`run_server.bat`**，或在终端中运行：

```cmd
java -jar dist/server.jar --port 8080 --mode standalone --data-dir ./data
```

看到以下输出表示启动成功：

```
[Server] Redis-compatible NoSQL Server listening on port: 8080
```

### 第三步：连接客户端

**方式 A — 命令行客户端（推荐新手）：**

双击 **`run_shell.bat`**，然后输入命令：

```
127.0.0.1:8080> SET name 张三
OK
127.0.0.1:8080> GET name
"张三"
```

**方式 B — 可视化桌面客户端：**

双击 **`run_gui.bat`**，点击「连接服务端」按钮，通过图形界面操作数据库。

---

## 3. 所有启动方式

### 3.1 单机模式

```cmd
:: 服务端
java -jar dist/server.jar --port 8080 --mode standalone --data-dir ./data

:: 命令行客户端（交互模式）
java -jar dist/cli.jar --host 127.0.0.1 --port 8080

:: 命令行客户端（单命令模式）
java -jar dist/cli.jar -c "SET key value"

:: 命令行客户端（批量执行文件）
java -jar dist/cli.jar -f commands.txt

:: GUI 桌面客户端
java -jar dist/gui.jar
```

**服务端参数说明：**

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--port` | `8080` | 监听端口 |
| `--mode` | `standalone` | `standalone` 或 `cluster` |
| `--id` | `node_1` | 集群模式下的节点 ID |
| `--peers` | (空) | 集群节点列表，格式：`id=host:port,id=host:port` |
| `--data-dir` | `./data` | 数据持久化目录 |
| `--rotate-threshold` | `2097152` | AOF 日志分卷阈值（字节，默认 2MB） |

### 3.2 集群模式（3 节点）

双击 **`run_cluster.bat`**，自动启动 3 个控制台窗口：

| 节点 | 端口 | 数据目录 |
|------|------|---------|
| node_1 | 8081 | `./data/n1` |
| node_2 | 8082 | `./data/n2` |
| node_3 | 8083 | `./data/n3` |

启动后节点自动完成 Leader 选举（约 1~3 秒），控制台会打印角色信息。

> **验证集群**：手动关闭 Leader 节点窗口，观察剩余节点在秒级内自动选出新 Leader。

### 3.3 Shell 命令工具

```cmd
:: 设置环境变量（可选，永久生效）
set EASY_DB_HOST=127.0.0.1
set EASY_DB_PORT=8080

:: 直接使用（需先运行 build.bat）
easy-db set name 张三
easy-db get name
easy-db keys user:*

:: 临时切换服务器
set EASY_DB_HOST=192.168.1.100 && easy-db get name
```

---

## 4. 支持的命令

### 4.1 String 操作

| 命令 | 示例 | 说明 |
|------|------|------|
| `SET` | `SET name 张三` | 存储键值对 |
| `GET` | `GET name` | 获取值 |
| `DEL` | `DEL name` | 删除键 |
| `EXISTS` | `EXISTS name` | 检查键是否存在 |
| `INCR` | `INCR counter` | 自增 1 |
| `DECR` | `DECR counter` | 自减 1 |
| `MSET` | `MSET k1 v1 k2 v2` | 批量写入 |
| `MGET` | `MGET k1 k2` | 批量读取 |
| `SET … EX` | `SET token abc EX 60` | 带 TTL 的写入（60 秒后过期） |

### 4.2 Hash 操作

| 命令 | 示例 | 说明 |
|------|------|------|
| `HSET` | `HSET user:1 name 张三` | 设置字段 |
| `HGET` | `HGET user:1 name` | 获取字段 |
| `HDEL` | `HDEL user:1 name` | 删除字段 |
| `HGETALL` | `HGETALL user:1` | 获取全部字段 |

### 4.3 List 操作

| 命令 | 示例 | 说明 |
|------|------|------|
| `LPUSH` | `LPUSH queue item` | 头部插入 |
| `RPUSH` | `RPUSH queue item` | 尾部插入 |
| `LPOP` | `LPOP queue` | 头部弹出 |
| `RPOP` | `RPOP queue` | 尾部弹出 |
| `LRANGE` | `LRANGE queue 0 -1` | 范围查询 |

### 4.4 Set 操作

| 命令 | 示例 | 说明 |
|------|------|------|
| `SADD` | `SADD tags java` | 添加成员 |
| `SREM` | `SREM tags java` | 移除成员 |
| `SMEMBERS` | `SMEMBERS tags` | 全部成员 |
| `SISMEMBER` | `SISMEMBER tags java` | 判断是否存在 |

### 4.5 系统命令

| 命令 | 说明 |
|------|------|
| `PING` | 连通性测试，返回 `PONG` |
| `INFO` | 服务器信息（内存、键数、角色、命中率等） |
| `DBSIZE` | 数据库键总数 |
| `KEYS` / `KEYS pattern` | 列出所有键 / 按模式匹配 |
| `FLUSHALL` | 清空全部数据（不可逆） |
| `EXPIRE key seconds` | 设置过期时间 |
| `TTL key` | 查询剩余生存时间 |
| `TYPE key` | 查询数据类型 |
| `CREATE COLLECTION name` | 创建集合 |
| `DROP COLLECTION name` | 删除集合 |
| `LIST COLLECTIONS` | 列出所有集合 |

---

## 5. Java SDK 编程调用

在自己的 Java 项目中使用 easy-db：

```java
import nosql.client.NoSqlSdk;

public class MyApp {
    public static void main(String[] args) throws Exception {
        // 连接数据库
        try (NoSqlSdk db = new NoSqlSdk("127.0.0.1", 8080)) {

            // String 操作
            db.set("name", "张三");
            String name = db.get("name");        // "张三"

            // 检查存在
            boolean exists = db.exists("name");  // true

            // 批量操作
            db.sendCommand(List.of("MSET", "a", "1", "b", "2", "c", "3"));

            // 列出所有键
            List<String> keys = db.keys("*");

            // Hash 操作
            db.sendCommand(List.of("HSET", "user:1", "name", "张三"));

            // List 操作
            db.sendCommand(List.of("RPUSH", "queue", "task1"));

            // 系统命令
            String pong = db.ping();             // "PONG"
            long size = db.dbSize();             // 键总数
        }
    }
}
```

> 完整示例见 `examples/SdkUsageExample.java`

---

## 6. HTTP RESTful API

服务端启动后，同时支持 HTTP 协议访问（与 RESP 协议共用同一端口）：

```cmd
# 存储数据
curl -X POST http://localhost:8080/api/v1/keys/name -d "张三"

# 查询数据
curl http://localhost:8080/api/v1/keys/name
→ {"code":200,"message":"OK","data":"张三"}

# 列出所有键
curl http://localhost:8080/api/v1/keys

# 检查是否存在
curl http://localhost:8080/api/v1/exists/name

# 删除数据
curl -X DELETE http://localhost:8080/api/v1/keys/name

# 健康检查
curl http://localhost:8080/api/v1/ping
```

**完整 API 端点：**

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/v1/ping` | 健康检查 |
| `GET` | `/api/v1/dbsize` | 键总数 |
| `GET` | `/api/v1/info` | 服务器信息 |
| `POST` | `/api/v1/keys/{key}` | 存储键值（body 为值） |
| `GET` | `/api/v1/keys/{key}` | 获取值 |
| `DELETE` | `/api/v1/keys/{key}` | 删除键 |
| `GET` | `/api/v1/keys?pattern=*` | 列出键（支持通配符） |
| `GET` | `/api/v1/exists/{key}` | 检查存在 |
| `DELETE` | `/api/v1/flush` | 清空所有数据 |

---

## 7. Shell 命令行工具

`easy-db` 让你像使用 `ls`、`grep` 一样直接在终端操作数据库：

```cmd
:: 基本使用
easy-db set name 张三
easy-db get name
easy-db keys user:*

:: 管道组合
easy-db keys * | while read key; do easy-db get $key; done

:: 数据备份
easy-db keys * | while read key; do
    easy-db get "$key" | xargs -I{} echo "$key {}" >> backup.txt
done
```

> 首次使用需运行 `build.bat` 编译生成 `dist/shell.jar`。

---

## 8. 性能压测

```cmd
java -jar dist/benchmark.jar --host 127.0.0.1 --port 8080 --threads 20 --reqs 5000 --mode mix
```

**参数说明：**

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--host` | `127.0.0.1` | 服务器地址 |
| `--port` | `8080` | 服务器端口 |
| `--threads` | `10` | 并发线程数 |
| `--reqs` | `5000` | 每线程请求数 |
| `--mode` | `mix` | `write` / `read` / `mix`（混合 80% 读 20% 写） |

输出示例：

```
================= Benchmark Results =================
Time Taken for Tests : 12.456 seconds
Successful Operations: 100000
Failed Operations    : 0
Throughput (QPS)     : 8028.26 ops/sec
Average Latency      : 1.245 ms
=====================================================
```

---

## 9. 单元测试

```cmd
:: 运行全部 99 个单元测试
run_tests.bat
```

测试覆盖：

| 模块 | 测试数 | 测试类 |
|------|--------|--------|
| RESP 协议解析 | 11 | `RespParserTest` |
| 存储引擎 | 24 | `EngineTest` |
| 数据库调度 | 19 | `DatabaseTest` |
| 集群管理 | 12 | `ClusterManagerTest` |
| SSTable 磁盘存储 | 8 | `SSTableTest` |
| AOF 持久化 | 8 | `AofManagerTest` |
| HashIndex 索引 | 10 | `HashIndexTest` |
| LRU 缓存 | 7 | `LruCacheTest` |

---

## 10. 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                    客户端层 (Client)                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐ │
│  │ NoSqlCli │  │ NoSqlGui │  │ NoSqlSdk │  │  curl   │ │
│  │ (CLI)    │  │ (Swing)  │  │ (Java)   │  │ (HTTP)  │ │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬────┘ │
│       └──────────────┴─────────────┴──────────────┘      │
│                         │ RESP / HTTP                     │
└─────────────────────────┼────────────────────────────────┘
                          ▼
┌─────────────────────────────────────────────────────────┐
│                    服务端层 (Server)                      │
│  ┌──────────────────────────────────────────────────┐   │
│  │              DatabaseServer                       │   │
│  │  (TCP + 虚拟线程 + 协议探测 HTTP/RESP)             │   │
│  └────────────────────┬─────────────────────────────┘   │
│                       ▼                                  │
│  ┌──────────────────────────────────────────────────┐   │
│  │              Database.execute()                    │   │
│  │  (synchronized 串行化 → AOF → 引擎 → 集群广播)     │   │
│  └────────┬─────────────────────────┬────────────────┘   │
│           ▼                         ▼                    │
│  ┌────────────────┐    ┌─────────────────────────┐      │
│  │   AofManager   │    │        Engine            │      │
│  │  (追加写+fsync) │    │  LruCache → L2Cache     │      │
│  │  (分卷+压缩)   │    │    ↓                     │      │
│  └────────────────┘    │  MemTable                │      │
│                         │    ↓ (刷写阈值 1000)     │      │
│                         │  HashIndex → SSTable     │      │
│                         └─────────────────────────┘      │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │           RestfulDispatcher + HTTP API             │   │
│  │  (RESP 命令→RESTful 日志 + HTTP 路由分发)          │   │
│  └──────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                          │
┌─────────────────────────┼────────────────────────────────┐
│                    集群层 (Cluster)                        │
│  ┌──────────────────────────────────────────────────┐   │
│  │            ClusterManager (简化 Raft)              │   │
│  │  Leader 选举 → 心跳维持 → 异步主从复制            │   │
│  │  任期 (Term) 比较 → 日志索引 (LogIndex) 比较      │   │
│  └──────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### 存储引擎 (LSMT) 读写路径

```
写入: Client → AOF 追加 → MemTable (ConcurrentHashMap)
                           ↓ (达阈值 1000 key)
                       SSTable.flush() → 磁盘 + 更新 HashIndex

读取: Client → LruCache (L1, 500条)
                  ↓ miss
               L2Cache (5000条, TTL 5分钟)
                  ↓ miss
               MemTable (ConcurrentHashMap)
                  ↓ miss
               HashIndex (O(1) 偏移量定位)
                  ↓ seek
               SSTable 磁盘读取 → 回填各级缓存
```

### 集群 Raft 状态机

```
启动 → Follower → (心跳超时) → Candidate → (过半数票) → Leader
         ↑                        │                      │
         └──── 收到新Leader心跳 ───┘                      │
         └──── 收到更大 Term ──────────────────────────────┘
```

---

## 11. 项目结构

```
Java高级课程设计/
├── README.md                     # 本文件
├── CLAUDE.md                     # AI 助手指引
├── build.bat                     # 一键编译打包
├── sources.txt                   # 源文件清单
│
├── src/nosql/
│   ├── common/                   # 协议层
│   │   ├── RespParser.java       #   RESP 编解码器
│   │   └── DbValue.java          #   数据类型定义
│   │
│   ├── server/                   # 服务端核心
│   │   ├── DatabaseServer.java   #   TCP 服务器入口（虚拟线程）
│   │   ├── Database.java         #   命令调度中心
│   │   ├── Engine.java           #   LSMT 存储引擎
│   │   ├── SSTable.java          #   磁盘有序表（Data+Index+Footer）
│   │   ├── HashIndex.java        #   内存偏移量索引 O(1)
│   │   ├── LruCache.java         #   一级 LRU 缓存
│   │   ├── L2Cache.java          #   二级 TTL 缓存
│   │   ├── AofManager.java       #   AOF 持久化管理
│   │   ├── RestfulDispatcher.java#   RESTful 路由调度器
│   │   ├── HttpRequest.java      #   HTTP 请求解析
│   │   ├── HttpResponse.java     #   HTTP 响应构建
│   │   └── RouteHandler.java     #   路由处理器接口
│   │
│   ├── cluster/                  # 集群管理
│   │   └── ClusterManager.java   #   简化 Raft（选举+心跳+复制）
│   │
│   ├── client/                   # 客户端
│   │   ├── NoSqlSdk.java         #   Java SDK
│   │   ├── NoSqlCli.java         #   交互式 CLI
│   │   ├── NoSqlGui.java         #   Swing 桌面 GUI
│   │   ├── ShellClient.java      #   Shell 命令工具
│   │   └── DatabaseException.java#   SDK 异常类
│   │
│   └── benchmark/                # 性能测试
│       └── BenchmarkTool.java    #   并发压力测试
│
├── test/nosql/                   # 单元测试（99 tests）
│   ├── common/RespParserTest.java
│   ├── server/ (6 个测试类)
│   └── cluster/ClusterManagerTest.java
│
├── docs/                         # 设计文档 + 指导书
├── lib/                          # JUnit 5 依赖
├── examples/                     # SDK 编程示例
├── dist/                         # 可执行 JAR（build.bat 生成）
├── data/                         # 运行时数据（自动创建）
│
├── easy-db / easy-db.bat         # Shell 工具入口
├── run_server.bat                # 启动单机服务端
├── run_cluster.bat               # 启动 3 节点集群
├── run_shell.bat                 # 启动 CLI 客户端
├── run_gui.bat                   # 启动 GUI 客户端
└── run_tests.bat                 # 运行单元测试
```

---

## 常见问题

**Q: `javac` 不是内部命令？**
A: 安装 [JDK 21](https://adoptium.net/)，确保 `JAVA_HOME/bin` 在系统 PATH 中。

**Q: 双击 bat 文件闪退？**
A: 右键 bat 文件 → 编辑，检查 `dist/server.jar` 是否存在。不存在则先运行 `build.bat`。

**Q: 端口被占用？**
A: 修改 `--port` 参数为其他端口（如 8081），客户端也对应修改。

**Q: 集群模式节点无法选举？**
A: 确保 3 个节点的 `--peers` 参数配置一致，且端口不冲突。防火墙需允许本地回环通信。
