# 多线程C/S架构NoSQL分布式数据库系统

> **开发语言**：Java 21 (JDK 21)
> **协作开发**：马奕钦 (组长) & 黎展宏 (组员)

本项目是计算机科学与技术专业《Java高级编程及应用》的课程设计项目。系统基于 C/S 架构，使用自定义 NSP 字节流协议进行通信，支持多线程高并发连接（采用 Java 21 虚拟线程），包含单机模式与集群模式（支持角色动态选举及主从数据复制）。

---

## 📂 项目规范目录结构

为保证代码与文档结构清晰，本目录进行了标准化整理：

```
Java高级课程设计/
├── README.md               # 本说明文档 (使用说明与引导)
├── build.bat               # 一键打包编译脚本 (可双击运行)
├── run_cluster.bat         # 一键启动 3 节点集群脚本 (可双击运行)
├── src/                    # 项目 Java 21 源码目录
│   └── nosql/
│       ├── common/         # NSP协议编解码、数据传输DTO及序列化工具
│       ├── server/         # 内存引擎、WAL日志、Rotate分卷、双级缓存与物理偏移索引
│       ├── client/         # 客户端 SDK、CLI 交互终端与可视化 Swing GUI 界面
│       └── benchmark/      # 多线程并发吞吐压测工具
├── docs/                   # 课程设计文档目录
│   ├── 任务分工文档.md
│   ├── 01_网络通信与序列化协议规范.md
│   ├── 02_单机存储引擎与持久化设计.md
│   ├── 03_集群心跳与动态选主协议.md
│   ├── 04_性能测试与部署方案.md
│   └── 6.0课程设计指导书与评分标准-学生版(1).doc  # 原始 Word 指导书
└── dist/                   # 编译后生成的可执行 JAR 目录
    ├── server.jar          # 数据库服务端程序
    ├── cli.jar             # 交互式 CLI 客户端
    ├── gui.jar             # 可视化 GUI 客户端
    └── benchmark.jar       # 性能压力测试工具
```

---

## 🚀 快速启动与演示说明

本项目的脚本完全支持在 Windows 中直接双击运行：

### 1. 编译构建
双击运行 **`build.bat`**，脚本将自动扫描并编译 `src/` 下所有的 Java 文件，并将打包好的可执行程序输出至 `dist/` 文件夹。

### 2. 单机模式运行
1. 进入 `dist/` 目录，启动 Server：`java -jar server.jar`。
2. 启动 GUI 可视化客户端：双击 `gui.jar`。
3. 启动 CLI 交互客户端：在控制台运行 `java -jar cli.jar`。

### 3. 集群模式运行 (组队必做项)
1. 双击运行 **`run_cluster.bat`**。
2. 脚本将以分布式参数启动 3 个独立的控制台窗口，分别代表 **Node 1 (8081)**、**Node 2 (8082)** 和 **Node 3 (8083)**。
3. 节点启动后将在 1-2 秒内自动发送拉票心跳，完成 **动态角色选举（Leader/Follower）**。
4. 可以使用 CLI 或 GUI 客户端连接其中任意节点（如 `127.0.0.1:8081`）进行主写从读、负载均衡以及高可用断电选主演练。

### 4. 性能压力测试 (Benchmark)
在控制台中运行：
```bash
java -jar dist/benchmark.jar --host 127.0.0.1 --port 8081 --threads 20 --reqs 5000 --mode mix
```
压测结束时，将在控制台实时输出压测平均耗时、成功率、**QPS 吞吐量** 以及 **时延 Latency 曲线**。

---

## 📄 关联设计文档

详细的模块细节、网络路由表、Raft状态转移图和测试用例已全部归类在 `docs/` 下：
- [任务分工文档](file:///D:/m'y'q/Desktop/java%E6%9C%9F%E6%9C%AB%E4%BD%9C%E4%B8%9A/Java%E9%AB%98%E7%BA%A7%E8%AF%BE%E7%A8%8B%E8%AE%BE%E8%AE%A1/docs/%E4%BB%BB%E5%8A%A1%E5%88%86%E5%B7%A5%E6%96%87%E6%A1%A3.md)
- [01_网络通信与序列化协议规范](file:///D:/m'y'q/Desktop/java%E6%9C%9F%E6%9C%AB%E4%BD%9C%E4%B8%9A/Java%E9%AB%98%E7%BA%A7%E8%AF%BE%E7%A8%8B%E8%AE%BE%E8%AE%A1/docs/01_%E7%BD%91%E7%BB%9C%E9%80%9A%E4%BF%A1%E4%B8%8E%E5%BA%8F%E5%88%97%E5%8C%96%E5%8D%8F%E8%AE%AE%E8%A7%84%E8%8C%83.md)
- [02_单机存储引擎与持久化设计](file:///D:/m'y'q/Desktop/java%E6%9C%9F%E6%9C%AB%E4%BD%9C%E4%B8%9A/Java%E9%AB%98%E7%BA%A7%E8%AF%BE%E7%A8%8B%E8%AE%BE%E8%AE%A1/docs/02_%E5%8D%95%E6%9C%BA%E5%AD%98%E5%82%A8%E5%BC%95%E6%93%8E%E4%B8%8E%E6%8C%81%E4%B9%85%E5%8C%96%E8%AE%BE%E8%AE%A1.md)
- [03_集群心跳与动态选主协议](file:///D:/m'y'q/Desktop/java%E6%9C%9F%E6%9C%AB%E4%BD%9C%E4%B8%9A/Java%E9%AB%98%E7%BA%A7%E8%AF%BE%E7%A8%8B%E8%AE%BE%E8%AE%A1/docs/03_%E9%9B%86%E7%BE%A4%E5%BF%83%E8%B7%B3%E4%B8%8E%E5%8A%A8%E6%80%81%E9%80%89%E4%B8%BB%E5%8D%8F%E8%AE%AE.md)
- [04_性能测试与部署方案](file:///D:/m'y'q/Desktop/java%E6%9C%9F%E6%9C%AB%E4%BD%9C%E4%B8%9A/Java%E9%AB%98%E7%BA%A7%E8%AF%BE%E7%A8%8B%E8%AE%BE%E8%AE%A1/docs/04_%E6%80%A7%E8%83%BD%E6%B5%8B%E8%AF%95%E4%B8%8E%E9%83%A8%E7%BD%B2%E6%96%B9%E6%A1%88.md)
