package nosql.cluster;

import nosql.common.RespParser;
import nosql.common.DbValue;
import nosql.server.Database;
import nosql.server.Engine;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 集群模式管理器，负责多节点心跳、简化版 Raft 选主与主从同步（全部基于标准 RESP 协议通信）
 *
 * ============================================================
 * 本文件处理的集群冲突（共 4 种）：
 * ============================================================
 *
 * 【冲突一】多个节点同时想当 Leader（选举冲突）
 *   处理位置：
 *     - resetElectionTimeout()   → 随机超时 1500~3000ms，避免同时触发选举
 *     - handleElectionPacket()   → 同一 term 内只投一票（先到先得）
 *     - startElection()          → 获得过半数票才晋升 Leader
 *
 * 【冲突二】脑裂（网络分区导致两个 Leader 同时存在）
 *   处理位置：
 *     - startElection()          → votesNeeded = (peers+1)/2 + 1，孤立节点永远拿不到过半数票
 *
 * 【冲突三】旧 Leader 复活（两个不同 term 的 Leader 同时发心跳）
 *   处理位置：
 *     - handleHeartbeatPacket()  → term 比较：term < currentTerm 的心跳被忽略，旧 Leader 自动降级
 *
 * 【冲突七】新节点加入时数据和 Leader 不一致
 *   处理位置：
 *     - joinAndSyncData()        → 新节点主动发 CLUSTER_SYNC 请求全量快照
 *     - handleSyncJoinRequest()  → Leader 遍历所有数据生成快照返回
 *
 * 【冲突六（已修复）】Leader 刚写完就崩溃，数据未同步到 Follower
 *   修复：
 *     - broadcastReplication()   → 改为同步等待，发送 CLUSTER_REPLICATE + 等回复
 *     - 过半数 Follower 确认后才返回成功，未达到多数派则写操作被拒绝
 *     代价：每次写都要等网络往返，延迟升高但保证了数据不丢
 * ============================================================
 *
 * 集群中另外两个冲突在其他文件中处理：
 *
 * 【冲突四】两个客户端同时写同一 Key → Database.java 的 synchronized execute()
 * 【冲突五】写操作发给 Follower 节点 → DatabaseServer.java 的 isWritable() 检查
 */
public class ClusterManager implements Closeable {

    // ============================================================
    // 节点角色枚举（Raft 状态机）
    // ============================================================
    public enum Role {
        FOLLOWER,   // 从节点：只读，接收 Leader 复制，监控心跳
        CANDIDATE,  // 候选者：发起选举，向其他节点拉票
        LEADER      // 主节点：处理写请求，广播心跳，复制数据给 Follower
    }

    private final String nodeId;
    private final int port;
    private final Map<String, String> peers; // nodeId -> "ip:port"（不包含自己）
    private final Database database;

    // ============================================================
    // Raft 核心状态变量
    // ============================================================
    private Role currentRole = Role.FOLLOWER;
    private int currentTerm = 0;        // 当前任期号，Raft 协议的核心，term 大的优先
    private String votedFor = null;     // 当前 term 内投票给了谁（null 表示还没投票）
    private String leaderId = null;     // 当前已知的 Leader 节点 ID
    private final java.util.concurrent.atomic.AtomicLong lastLogIndex =
            new java.util.concurrent.atomic.AtomicLong(0); // 最后一条写命令的日志索引

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private ScheduledFuture<?> electionTimeoutTask;  // 选举超时检测定时任务
    private ScheduledFuture<?> heartbeatTask;        // Leader 心跳广播定时任务

    private final Random random = new Random();
    private long lastHeartbeatTime;  // 上次收到 Leader 心跳的时间戳

    public ClusterManager(String nodeId, int port, String peersStr, Database database) {
        this.nodeId = nodeId;
        this.port = port;
        this.database = database;
        this.peers = new ConcurrentHashMap<>();

        parsePeers(peersStr);
        this.lastHeartbeatTime = System.currentTimeMillis();
        this.database.setClusterRole("FOLLOWER");

        // ============================================================
        // 绑定主节点写指令复制监听器
        // 当 Database.execute() 执行完一条写命令后，会回调这个 Listener
        // Leader 收到回调 → broadcastReplication() → 同步等待多数派确认
        // 返回 false → execute() 返回错误给客户端
        // ============================================================
        this.database.setReplicationListener(cmd -> {
            if (currentRole == Role.LEADER) {
                lastLogIndex.incrementAndGet();
                return broadcastReplication(cmd);
            }
            return true; // Follower/Standalone 不需要复制
        });
    }

    /**
     * 解析集群节点配置字符串
     * 格式: "node_1=127.0.0.1:8081,node_2=127.0.0.1:8082,node_3=127.0.0.1:8083"
     * 过滤掉自己，只保留其他节点到 peers Map 中
     */
    private void parsePeers(String peersStr) {
        if (peersStr == null || peersStr.trim().isEmpty()) {
            return;
        }
        String[] arr = peersStr.split(",");
        for (String item : arr) {
            String[] kv = item.split("=");
            if (kv.length == 2) {
                String peerId = kv[0].trim();
                String address = kv[1].trim();
                // 把自己的 ID 过滤掉，peers 只存其他节点
                // 这样 votesNeeded = (peers.size() + 1) / 2 + 1 的计算才是正确的
                if (!peerId.equals(nodeId)) {
                    peers.put(peerId, address);
                }
            }
        }
    }

    /**
     * 启动集群管理器
     * 1. 开始选举超时检测
     * 2. 1.2 秒后向已有节点请求全量数据同步
     */
    public void start() {
        System.out.println("[Cluster] Node " + nodeId + " starting in cluster mode...");
        resetElectionTimeout();
        scheduler.schedule(this::joinAndSyncData, 1200, TimeUnit.MILLISECONDS);
    }

    // ════════════════════════════════════════════════════════════
    // 冲突一 处理：多个节点同时想当 Leader（选举冲突）
    // 策略：随机超时 + 同一 term 只投一票 + 过半数晋升
    // ════════════════════════════════════════════════════════════

    /**
     * 【冲突一】重置选举超时计时器
     *
     * 每次收到 Leader 心跳后调用此方法，重新开始随机倒计时。
     *
     * 关键设计：timeout = 1500 + random.nextInt(1500)
     *   → 每个节点的超时时间在 1500ms ~ 3000ms 之间随机
     *   → 3 个节点的超时几乎不可能完全相同
     *   → 只有最早超时的那个节点会发起选举
     *   → 避免了多个节点"同时"发起选举的冲突
     *
     * 如果没有这个随机化，3 个节点会在同一时刻超时、同时拉票，
     * 可能导致选票分散、无人过半数，反复选举。
     */
    private synchronized void resetElectionTimeout() {
        if (electionTimeoutTask != null) {
            electionTimeoutTask.cancel(true);
        }
        lastHeartbeatTime = System.currentTimeMillis();
        long timeout = 1500 + random.nextInt(1500); // 【冲突一】随机 1500~3000ms，避免同时选举
        electionTimeoutTask = scheduler.scheduleAtFixedRate(
            this::checkElectionTimeout,
            timeout,
            timeout,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * 【冲突一】定时检查是否选举超时
     *
     * 超过 3.5 秒没收到 Leader 心跳 → 认为 Leader 挂了 → 发起选举
     * 如果当前节点已经是 Leader，则跳过（Leader 不需要检测超时）
     */
    private synchronized void checkElectionTimeout() {
        if (currentRole == Role.LEADER) {
            return;  // 已经是 Leader，不需要检查
        }
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatTime > 3500) {  // 3.5 秒没收到心跳
            System.out.println("[Cluster] Heartbeat timeout. Initiating leader election...");
            startElection();
        }
    }

    /**
     * 【冲突一 + 冲突二】发起 Leader 选举
     *
     * 流程：
     * ① 自己升级为 CANDIDATE，term+1
     * ② 先投自己一票
     * ③ 向所有 peer 发送 CLUSTER_ELECT REQUEST_VOTE 拉票
     * ④ 收集选票，达到过半数 (peers+1)/2+1 就晋升 Leader
     *
     * 【冲突一 - 过半数机制】：
     *   int votesNeeded = (peers.size() + 1) / 2 + 1;
     *   3 节点：peers.size=2 → (2+1)/2+1 = 2 票
     *   只有拿到 ≥2 票的候选者才能当选，确保了只有一个 Leader
     *
     * 【冲突二 - 防脑裂】：
     *   如果 node_1 和 node_2/node_3 网络断开：
     *     node_1 的 peers 有 2 个，但它只能投自己 = 1 票 < 2 → 选不上
     *     node_2 和 node_3 能互相通信 → 互相投票 → 各得 2 票 → 当选
     *   结论：被孤立的节点永远拿不到过半数票，阻止了脑裂
     */
    private synchronized void startElection() {
        currentRole = Role.CANDIDATE;
        database.setClusterRole("CANDIDATE");
        currentTerm++;
        votedFor = nodeId;      // 先投自己一票
        leaderId = null;
        lastHeartbeatTime = System.currentTimeMillis();

        System.out.println("[Cluster] Node " + nodeId + " becomes Candidate. Term: " + currentTerm);

        // 【冲突一 + 冲突二】计算过半数阈值
        // 3 节点集群：peers.size()=2 → (2+1)/2+1=2，需要 2 票
        // 如果节点被孤立，只能拿到自己 1 票 < 2，选不上 → 防止脑裂
        int votesNeeded = (peers.size() + 1) / 2 + 1;
        AtomicInteger voteCount = new AtomicInteger(1);  // 已投自己 1 票

        // 组装选票请求 RESP 命令：CLUSTER_ELECT REQUEST_VOTE <term> <candidateId> <lastLogIndex>
        List<String> electCmd = List.of("CLUSTER_ELECT", "REQUEST_VOTE",
                String.valueOf(currentTerm), nodeId, String.valueOf(lastLogIndex.get()));

        // 向所有 peer 并发拉票
        for (Map.Entry<String, String> entry : peers.entrySet()) {
            String peerId = entry.getKey();
            String address = entry.getValue();
            CompletableFuture.runAsync(() -> {
                try {
                    List<String> resp = sendCommandToPeer(address, electCmd, true);
                    if (resp != null && !resp.isEmpty() && "VOTE_GRANTED".equalsIgnoreCase(resp.get(0))) {
                        int term = Integer.parseInt(resp.get(1));
                        if (term == currentTerm) {
                            int currentVotes = voteCount.incrementAndGet();
                            System.out.println("[Cluster] Received vote from " + peerId + ". Total votes: " + currentVotes);
                            // 【冲突一 + 冲突二】票数达到过半数 → 晋升 Leader
                            if (currentVotes >= votesNeeded) {
                                promoteToLeader();
                            }
                        }
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    /**
     * 晋升为 Leader
     * ① 角色切换为 LEADER
     * ② 停止选举超时检测（Leader 不需要）
     * ③ 启动心跳广播（每秒发一次，维持权威）
     */
    private synchronized void promoteToLeader() {
        if (currentRole != Role.CANDIDATE) {
            return;  // 可能已经被其他线程晋升了，防止重复
        }
        currentRole = Role.LEADER;
        database.setClusterRole("LEADER");
        leaderId = nodeId;
        System.out.println("[Cluster] !!! Node " + nodeId + " is elected as LEADER for Term: " + currentTerm + " !!!");

        // Leader 不需要选举超时检测
        if (electionTimeoutTask != null) {
            electionTimeoutTask.cancel(true);
        }

        // Leader 每秒广播心跳，告诉 Follower "我还活着，别选举"
        heartbeatTask = scheduler.scheduleAtFixedRate(
            this::sendHeartbeats,
            0,
            1000,    // 每 1 秒发一次心跳
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Leader 定时广播心跳
     *
     * Follower 收到心跳后会：
     *   ① 调用 handleHeartbeatPacket() → 更新 term + 设置 role=FOLLOWER
     *   ② 调用 resetElectionTimeout() → 重置自己的选举计时器 → 推迟选举
     *
     * 如果 Leader 挂了 → 心跳停止 → Follower 3.5 秒后超时 → 发起新选举
     */
    private synchronized void sendHeartbeats() {
        if (currentRole != Role.LEADER) {
            if (heartbeatTask != null) {
                heartbeatTask.cancel(true);
            }
            return;
        }

        // 组装心跳包 RESP 命令：CLUSTER_HEARTBEAT <leaderNodeId> <term> <lastLogIndex>
        List<String> hbCmd = List.of("CLUSTER_HEARTBEAT", nodeId,
                String.valueOf(currentTerm), String.valueOf(lastLogIndex.get()));

        for (String address : peers.values()) {
            CompletableFuture.runAsync(() -> {
                try {
                    sendCommandToPeer(address, hbCmd, false);  // false = 单向发送，不等回复
                } catch (Exception ignored) {}
            });
        }
    }

    // ════════════════════════════════════════════════════════════
    // 冲突一 + 冲突三 处理：接收选举请求 + 旧 Leader 降级
    // ════════════════════════════════════════════════════════════

    /**
     * 【冲突一】处理收到的选举投票请求
     *
     * 投票规则（Raft 核心）：
     *   ① 如果对方的 term > 我的 term → 我退回到 FOLLOWER，清空旧票，更新 term
     *   ② 如果对方的 term == 我的 term 且我还没投票 → 投票给他
     *   ③ 如果对方的 term == 我的 term 且我已经投给了同一个人 → 再次确认
     *   ④ 如果对方的 term < 我的 term → 拒绝（对方是过期的候选者）
     *   ⑤ 如果同 term 内我已经投给了别人 → 拒绝（一 term 一票，先到先得）
     *
     * 【冲突三 - 旧 Leader 降级】：
     *   如果收到的 term > currentTerm：
     *     说明集群已经进入了新时代，有新的 Leader 被选出了
     *     自己无论是旧 Leader 还是 CANDIDATE，都必须退回到 FOLLOWER
     *     清空 votedFor，等待新 Leader 的心跳
     */
    public synchronized List<String> handleElectionPacket(List<String> cmd) {
        if (cmd.size() < 4) {
            return List.of("VOTE_REJECTED", String.valueOf(currentTerm));
        }

        String subType = cmd.get(1);
        int term = Integer.parseInt(cmd.get(2));
        String candidateId = cmd.get(3);
        // 解析候选者的最后日志索引（兼容旧版不传此字段的请求）
        long candidateLogIndex = cmd.size() >= 5 ? Long.parseLong(cmd.get(4)) : 0;

        if ("REQUEST_VOTE".equalsIgnoreCase(subType)) {
            // 【冲突三】对方的 term 更大 → 我退回到 FOLLOWER，承认新时代
            if (term > currentTerm) {
                currentTerm = term;
                currentRole = Role.FOLLOWER;
                database.setClusterRole("FOLLOWER");
                votedFor = null;      // 清空旧票，新 term 可以重新投票
                leaderId = null;
                resetElectionTimeout();
            }

            // 【冲突一】同 term + 还没投票（或已投给同一个人）→ 投票
            // 新增判断：候选者的数据必须至少和自己一样新（logIndex 比较）
            if (term == currentTerm && (votedFor == null || votedFor.equals(candidateId))) {
                // lastLogIndex 比较：数据更新的候选者优先
                if (candidateLogIndex >= lastLogIndex.get()) {
                    votedFor = candidateId;
                    System.out.println("[Cluster] Granted vote to candidate: " + candidateId
                            + " in term " + term + " (logIndex: " + candidateLogIndex + ")");
                    return List.of("VOTE_GRANTED", String.valueOf(currentTerm));
                } else {
                    System.out.println("[Cluster] Rejected vote for " + candidateId
                            + ": candidate logIndex " + candidateLogIndex
                            + " < local " + lastLogIndex.get());
                }
            }
        }
        // term 更小，或者同 term 已投给别人，或者候选者数据不够新 → 拒绝
        return List.of("VOTE_REJECTED", String.valueOf(currentTerm));
    }

    // ════════════════════════════════════════════════════════════
    // 冲突三 处理：接收心跳包 + 旧 Leader 自动降级
    // ════════════════════════════════════════════════════════════

    /**
     * 【冲突三】处理收到的心跳包 —— 旧 Leader 复活 / 多个 Leader 冲突
     *
     * 场景 1：node_1 是旧 Leader(term=1)，网络卡顿后恢复
     *         但 node_2 已经是新 Leader(term=2)
     *         node_1 继续发 term=1 的心跳
     *         → term=1 < currentTerm=2 → 被忽略！旧 Leader 的消息无人理睬
     *
     * 场景 2：node_1(term=1) 是 Follower，收到新 Leader(term=2) 的心跳
     *         → term=2 >= currentTerm=1 → 承认对方是 Leader，更新自己的状态
     *         → 重置选举计时器，推迟自己的选举
     *
     * 场景 3：收到心跳的 term 大于当前 term
     *         → 更新 currentTerm，承认新 Leader
     *         → 如果自己之前是 CANDIDATE 或旧 LEADER → 自动降级为 FOLLOWER
     *
     * 关键：term 的比较是 Raft 区分"新旧"的核心机制
     *   term 小的 = 过时的 → 消息被忽略
     *   term 大的 = 新的权威 → 无条件承认
     */
    public synchronized void handleHeartbeatPacket(List<String> cmd) {
        if (cmd.size() < 3) return;
        String termLeader = cmd.get(1);
        int term = Integer.parseInt(cmd.get(2));
        // 更新 Leader 的最新日志索引（兼容旧版不传此字段）
        if (cmd.size() >= 4) {
            long leaderLogIndex = Long.parseLong(cmd.get(3));
            if (leaderLogIndex > lastLogIndex.get()) {
                lastLogIndex.set(leaderLogIndex);
            }
        }

        // 【冲突三】只有 term >= currentTerm 的心跳才被接受
        // 旧 Leader (term 小) 的心跳直接忽略
        if (term >= currentTerm) {
            if (currentRole != Role.FOLLOWER || !termLeader.equals(leaderId) || term > currentTerm) {
                System.out.println("[Cluster] Node " + nodeId + " follows Leader: " + termLeader
                        + " for Term: " + term + " (logIndex: " + lastLogIndex.get() + ")");
            }
            // 【冲突三】自动降级为 FOLLOWER，承认新 Leader
            currentRole = Role.FOLLOWER;
            database.setClusterRole("FOLLOWER");
            currentTerm = term;
            leaderId = termLeader;
            resetElectionTimeout();  // 重置计时器，推迟自己的选举
        }
        // else: term < currentTerm → 旧 Leader 的心跳，直接忽略
    }

    // ════════════════════════════════════════════════════════════
    // 冲突六（已知未处理）：主从复制 —— 异步广播
    // ════════════════════════════════════════════════════════════

    /**
     * 【冲突六（已知未处理）】Follower 接收 Leader 复制来的写命令
     *
     * Leader 执行完写操作后 → broadcastReplication() → Follower 收到 → 此方法处理
     * database.execute(subCmd, false):
     *   subCmd = 去掉 CLUSTER_REPLICATE 前缀后的纯业务命令
     *   false   = 本地执行，不写 AOF（Leader 已经写了），不再次广播（避免死循环）
     */
    public void handleReplicationPacket(List<String> cmd) throws Exception {
        if (cmd.size() < 2) return;
        // cmd.get(0) 为 CLUSTER_REPLICATE，后面跟着写命令列表（例如 ["SET", "name", "mayiqin"]）
        List<String> subCmd = cmd.subList(1, cmd.size());
        database.execute(subCmd, false); // false = 本地执行，不进行二次集群广播，不写 AOF
        lastLogIndex.incrementAndGet();  // Follower 也递增日志索引
    }

    // ════════════════════════════════════════════════════════════
    // 冲突七 处理：新节点加入 → 全量数据同步
    // ════════════════════════════════════════════════════════════

    /**
     * 【冲突七】Leader 处理新节点加入的全量快照请求
     *
     * 新节点启动时，内存是空的，和 Leader 数据不一致。
     * 新节点发送 CLUSTER_SYNC → Leader 收到 → 此方法生成全量快照返回。
     *
     * 快照格式：每条数据用 （SOH 控制字符）分隔字段
     *   String: "SETkeyvalue[EXttl]"
     *   List:   "RPUSHkeyitem" × N + "EXPIREkeyttl"
     *   Set:    "SADDkeymember" × N + "EXPIREkeyttl"
     *   Hash:   "HSETkeyfieldvalue" × N + "EXPIREkeyttl"
     *
     * 新节点收到快照后：engine.clear() → 逐条回放 → 数据完全一致
     */
    @SuppressWarnings("unchecked")
    public synchronized List<String> handleSyncJoinRequest() {
        System.out.println("[Cluster] Received snapshot sync request from follower.");
        List<String> snapshotCmds = new ArrayList<>();
        Engine engine = database.getEngine();

        // 【冲突七】同步 Collection 元数据
        for (String collName : engine.listCollections()) {
            snapshotCmds.add("CREATECOLLECTION" + collName);
        }

        // 【冲突七】扫描内存数据库，把当前的 String, List, Set, Hash 数据导出快照
        for (String key : engine.keys()) {
            String type = engine.type(key);
            long ttl = engine.ttl(key);

            if ("string".equals(type)) {
                String val = engine.get(key);
                if (val != null) {
                    if (ttl > 0) {
                        snapshotCmds.add("SET" + key + "" + val + "EX" + ttl);
                    } else {
                        snapshotCmds.add("SET" + key + "" + val);
                    }
                }
            } else if ("list".equals(type)) {
                List<String> list = engine.lrange(key, 0, -1);
                for (String item : list) {
                    snapshotCmds.add("RPUSH" + key + "" + item);
                }
                if (ttl > 0) snapshotCmds.add("EXPIRE" + key + "" + ttl);
            } else if ("set".equals(type)) {
                Set<String> set = engine.smembers(key);
                for (String item : set) {
                    snapshotCmds.add("SADD" + key + "" + item);
                }
                if (ttl > 0) snapshotCmds.add("EXPIRE" + key + "" + ttl);
            } else if ("hash".equals(type)) {
                Map<String, String> map = engine.hgetall(key);
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    snapshotCmds.add("HSET" + key + "" + entry.getKey() + "" + entry.getValue());
                }
                if (ttl > 0) snapshotCmds.add("EXPIRE" + key + "" + ttl);
            }
        }
        return snapshotCmds;
    }

    // ════════════════════════════════════════════════════════════
    // 冲突六（已修复）：主从复制 —— 同步等待多数派确认（强一致性）
    // ════════════════════════════════════════════════════════════

    /**
     * 【冲突六（已修复）】Leader 同步复制写操作指令给所有 Follower
     *
     * 改为强一致性策略：
     *   ① Leader 向所有 Follower 并发发送 CLUSTER_REPLICATE
     *   ② 每个 Follower 收到后执行命令，回复 "OK"
     *   ③ Leader 等待所有回复（2 秒超时）
     *   ④ 统计确认数是否达到过半数
     *   ⑤ 未达到过半数 → 返回 false → execute() 拒绝本次写操作
     *
     * 代价：每次写都要等网络往返 → 延迟升高，但保证了数据不丢
     */
    private boolean broadcastReplication(List<String> cmd) {
        List<String> repCmd = new ArrayList<>();
        repCmd.add("CLUSTER_REPLICATE");
        repCmd.addAll(cmd);

        int ackCount = 1; // Leader 本地已写入成功
        int majority = (peers.size() + 1) / 2 + 1;
        java.util.concurrent.ConcurrentHashMap<String, Boolean> acks = new java.util.concurrent.ConcurrentHashMap<>();

        // 并发向所有 Follower 发送复制命令
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String address : peers.values()) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    List<String> resp = sendCommandToPeer(address, repCmd, true);
                    if (resp != null && !resp.isEmpty() && "OK".equals(resp.get(0))) {
                        acks.put(address, true);
                    }
                } catch (Exception ignored) {}
            }));
        }

        // 等待所有 Follower 回复（最多 2 秒超时）
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(2000, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {}

        // 统计确认数
        for (String addr : peers.values()) {
            if (acks.containsKey(addr)) ackCount++;
        }

        boolean majorityAchieved = ackCount >= majority;
        if (!majorityAchieved) {
            System.err.println("[Cluster] !!! CRITICAL: Replication only got " + ackCount
                    + "/" + majority + " acks! Write will be rejected.");
        } else {
            System.out.println("[Cluster] Replication confirmed: " + ackCount
                    + "/" + majority + " acks achieved.");
        }
        return majorityAchieved;
    }

    // ════════════════════════════════════════════════════════════
    // 冲突七 处理：新节点主动拉取全量快照
    // ════════════════════════════════════════════════════════════

    /**
     * 【冲突七】新节点主动向已有节点请求全量数据快照进行初始化同步
     *
     * 调用时机：新节点启动 1.2 秒后（给集群发现彼此的时间）
     *
     * 流程：
     *  ① 向任意 Peer 发送 CLUSTER_SYNC → 等待响应
     *  ② Peer（Leader 或 Follower）返回全量快照
     *  ③ 清空本地 engine.clear()
     *  ④ 逐条回放快照命令 → database.execute(cmd, false)
     *     false = 不写 AOF（Leader 有 AOF）、不广播（避免死循环）
     *
     * 如果 Leader 或 Follower 已经是空的（没有数据要同步），
     * 或本身就是 Leader → 跳过同步。
     */
    private void joinAndSyncData() {
        if (currentRole == Role.LEADER || peers.isEmpty()) {
            return;
        }
        System.out.println("[Cluster] Syncing full data snapshot from Master...");
        List<String> joinCmd = List.of("CLUSTER_SYNC");

        for (String address : peers.values()) {
            try {
                List<String> snapshot = sendCommandToPeer(address, joinCmd, true);
                if (snapshot != null && !snapshot.isEmpty()) {
                    System.out.println("[Cluster] Replaying " + snapshot.size() + " snapshot commands to synchronize local database...");
                    database.getEngine().clear();  // 【冲突七】清空本地旧数据

                    for (String line : snapshot) {
                        String[] parts = line.split("");
                        List<String> cmd = Arrays.asList(parts);
                        database.execute(cmd, false);  // 【冲突七】回放快照，不广播不写 AOF
                    }
                    System.out.println("[Cluster] Data synchronization completed successfully.");
                    break;
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * 通过 TCP 连接发送 RESP 命令到指定 Peer 并接收返回结果
     *
     * @param address        "ip:port" 格式
     * @param cmd            RESP 命令列表
     * @param expectResponse true=等待响应（选举投票、同步请求），false=发完就走（心跳、复制）
     */
    private List<String> sendCommandToPeer(String address, List<String> cmd, boolean expectResponse) throws IOException {
        String[] hostPort = address.split(":");
        String host = hostPort[0];
        int port = Integer.parseInt(hostPort[1]);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1200);  // 1.2 秒连接超时
            OutputStream out = socket.getOutputStream();

            // 写入命令
            RespParser.writeArray(out, cmd);

            if (expectResponse) {
                InputStream in = socket.getInputStream();
                // 使用 RESP 响应解析
                return RespParser.readRequest(in);
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════
    // 公共查询接口
    // ════════════════════════════════════════════════════════════

    public synchronized Role getCurrentRole() {
        return currentRole;
    }

    public synchronized String getLeaderId() {
        return leaderId;
    }

    /**
     * 【冲突五 - 写保护】判断当前节点是否可以接受写操作
     *
     * 单机模式（peers 为空）：可以写
     * 集群模式：只有 LEADER 可以写
     *
     * 这个方法被 DatabaseServer.handleClient() 调用：
     *   客户端发来写命令 → isWritable() == false → 直接返回错误
     *   "Operation rejected: Current node is not Cluster Master."
     */
    public synchronized boolean isWritable() {
        return peers.isEmpty() || currentRole == Role.LEADER;
    }

    /** 获取当前最后日志索引（用于测试和集群同步） */
    public long getLastLogIndex() {
        return lastLogIndex.get();
    }

    // ════════════════════════════════════════════════════════════
    // 动态节点加入
    // ════════════════════════════════════════════════════════════

    /**
     * 获取当前节点 IP 地址
     */
    private String getLocalAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * 获取包含自身的完整 peer 列表字符串
     * 格式: "node_1=127.0.0.1:8081,node_2=127.0.0.1:8082,..."
     */
    public String getPeersAsString() {
        StringBuilder sb = new StringBuilder();
        sb.append(nodeId).append("=").append(getLocalAddress()).append(":").append(port);
        for (Map.Entry<String, String> peer : peers.entrySet()) {
            sb.append(",").append(peer.getKey()).append("=").append(peer.getValue());
        }
        return sb.toString();
    }

    /**
     * 添加一个新 peer 到本地集群成员列表
     */
    public synchronized void addPeer(String newNodeId, String newAddress) {
        if (newNodeId.equals(nodeId)) return; // 不添加自己
        peers.put(newNodeId, newAddress);
        System.out.println("[Cluster] Peer added: " + newNodeId + "=" + newAddress
                + " (total peers: " + peers.size() + ")");
    }

    /**
     * 处理 CLUSTER_JOIN 请求（管理员添加新节点）
     *
     * 如果当前是 Leader：添加 peer → 广播给所有节点 → 返回完整 peer 列表
     * 如果当前是 Follower：转发给 Leader
     *
     * @return RESP 响应列表，首元素为状态 ("OK" / "ERR")
     */
    public synchronized List<String> handleJoinRequest(List<String> cmd) {
        if (cmd.size() < 3) {
            return List.of("ERR", "Missing parameters. Usage: CLUSTER_JOIN nodeId=ip:port");
        }

        String newPeerEntry = cmd.get(1); // "node_4=127.0.0.1:8084"
        String[] kv = newPeerEntry.split("=");
        if (kv.length != 2 || kv[0].isEmpty() || kv[1].isEmpty()) {
            return List.of("ERR", "Invalid format, expected nodeId=ip:port");
        }
        String newNodeId = kv[0].trim();
        String newAddress = kv[1].trim();

        // 检查是否已存在
        if (peers.containsKey(newNodeId)) {
            return List.of("ERR", "Node already exists: " + newNodeId);
        }
        if (newNodeId.equals(nodeId)) {
            return List.of("ERR", "Cannot add self as peer");
        }

        if (currentRole == Role.LEADER) {
            // 1. Leader 添加新节点
            addPeer(newNodeId, newAddress);

            // 2. 广播 CLUSTER_PEER_JOIN 给所有现有 peer（通知有新节点加入）
            List<String> broadcastCmd = List.of("CLUSTER_PEER_JOIN", newPeerEntry);
            for (String addr : peers.values()) {
                if (addr.equals(newAddress)) continue; // 新节点单独发完整列表
                CompletableFuture.runAsync(() -> {
                    try {
                        sendCommandToPeer(addr, broadcastCmd, false);
                    } catch (Exception ignored) {}
                });
            }

            // 3. 单独发给新节点完整 peer 列表
            String fullPeerList = getPeersAsString();
            CompletableFuture.runAsync(() -> {
                try {
                    sendCommandToPeer(newAddress, List.of("CLUSTER_PEER_LIST", fullPeerList), false);
                } catch (Exception ignored) {}
            });

            System.out.println("[Cluster] New node joined the cluster: " + newPeerEntry
                    + " (full peer list: " + fullPeerList + ")");

            // 3. 返回完整 peer 列表（含自身）
            return List.of("OK", getPeersAsString());
        }

        // Follower：转发给 Leader
        if (leaderId != null) {
            String leaderAddr = peers.get(leaderId);
            if (leaderAddr != null) {
                try {
                    return sendCommandToPeer(leaderAddr, cmd, true);
                } catch (Exception e) {
                    return List.of("ERR", "Forward to Leader failed: " + e.getMessage());
                }
            }
        }
        return List.of("ERR", "No Leader available");
    }

    /**
     * 处理 Leader 广播的 CLUSTER_PEER_JOIN（单个新节点加入通知）
     */
    public synchronized void handlePeerJoinBroadcast(List<String> cmd) {
        if (cmd.size() < 2) return;
        String peerEntry = cmd.get(1); // "node_4=127.0.0.1:8084"
        String[] kv = peerEntry.split("=");
        if (kv.length == 2) {
            addPeer(kv[0].trim(), kv[1].trim());
        }
    }

    /**
     * 处理 Leader 单独发送的 CLUSTER_PEER_LIST（完整 peer 列表，新节点接收）
     * 用收到的完整列表替换本地 peers，让新节点知道集群所有成员
     */
    public synchronized void handlePeerListBroadcast(List<String> cmd) {
        if (cmd.size() < 2) return;
        String peerListStr = cmd.get(1); // "node_1=ip:port,node_2=ip:port,node_3=ip:port,..."
        String[] entries = peerListStr.split(",");
        int added = 0;
        for (String entry : entries) {
            String[] kv = entry.split("=");
            if (kv.length == 2) {
                String id = kv[0].trim();
                String addr = kv[1].trim();
                if (!id.equals(nodeId) && !peers.containsKey(id)) {
                    peers.put(id, addr);
                    added++;
                }
            }
        }
        if (added > 0) {
            System.out.println("[Cluster] Learned " + added + " peer(s) from Leader: "
                    + peerListStr + " (total peers: " + peers.size() + ")");
        }
    }

    @Override
    public synchronized void close() {
        scheduler.shutdown();
        if (electionTimeoutTask != null) {
            electionTimeoutTask.cancel(true);
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
        }
    }
}
