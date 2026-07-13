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
 */
public class ClusterManager implements Closeable {

    public enum Role {
        FOLLOWER, CANDIDATE, LEADER
    }

    private final String nodeId;
    private final int port;
    private final Map<String, String> peers; // nodeId -> "ip:port"
    private final Database database;

    private Role currentRole = Role.FOLLOWER;
    private int currentTerm = 0;
    private String votedFor = null;
    private String leaderId = null;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private ScheduledFuture<?> electionTimeoutTask;
    private ScheduledFuture<?> heartbeatTask;

    private final Random random = new Random();
    private long lastHeartbeatTime;

    public ClusterManager(String nodeId, int port, String peersStr, Database database) {
        this.nodeId = nodeId;
        this.port = port;
        this.database = database;
        this.peers = new ConcurrentHashMap<>();
        
        parsePeers(peersStr);
        this.lastHeartbeatTime = System.currentTimeMillis();
        this.database.setClusterRole("FOLLOWER");
        
        // 绑定主节点写指令复制监听器
        this.database.setReplicationListener(cmd -> {
            if (currentRole == Role.LEADER) {
                broadcastReplication(cmd);
            }
        });
    }

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
                // 过滤当前节点自己，确保选票过半数基数计算正确且避免自连
                if (!peerId.equals(nodeId)) {
                    peers.put(peerId, address);
                }
            }
        }
    }

    public void start() {
        System.out.println("[Cluster] Node " + nodeId + " starting in cluster mode...");
        resetElectionTimeout();
        scheduler.schedule(this::joinAndSyncData, 1200, TimeUnit.MILLISECONDS);
    }

    private synchronized void resetElectionTimeout() {
        if (electionTimeoutTask != null) {
            electionTimeoutTask.cancel(true);
        }
        lastHeartbeatTime = System.currentTimeMillis();
        long timeout = 1500 + random.nextInt(1500);
        electionTimeoutTask = scheduler.scheduleAtFixedRate(
            this::checkElectionTimeout, 
            timeout, 
            timeout, 
            TimeUnit.MILLISECONDS
        );
    }

    private synchronized void checkElectionTimeout() {
        if (currentRole == Role.LEADER) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatTime > 3500) { 
            System.out.println("[Cluster] Heartbeat timeout. Initiating leader election...");
            startElection();
        }
    }

    private synchronized void startElection() {
        currentRole = Role.CANDIDATE;
        database.setClusterRole("CANDIDATE");
        currentTerm++;
        votedFor = nodeId;
        leaderId = null;
        lastHeartbeatTime = System.currentTimeMillis();
        
        System.out.println("[Cluster] Node " + nodeId + " becomes Candidate. Term: " + currentTerm);

        int votesNeeded = (peers.size() + 1) / 2 + 1;
        AtomicInteger voteCount = new AtomicInteger(1);

        // 组装选票请求 RESP 命令：CLUSTER_ELECT REQUEST_VOTE <term> <candidateId>
        List<String> electCmd = List.of("CLUSTER_ELECT", "REQUEST_VOTE", String.valueOf(currentTerm), nodeId);

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
                            if (currentVotes >= votesNeeded) {
                                promoteToLeader();
                            }
                        }
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    private synchronized void promoteToLeader() {
        if (currentRole != Role.CANDIDATE) {
            return;
        }
        currentRole = Role.LEADER;
        database.setClusterRole("LEADER");
        leaderId = nodeId;
        System.out.println("[Cluster] !!! Node " + nodeId + " is elected as LEADER for Term: " + currentTerm + " !!!");
        
        if (electionTimeoutTask != null) {
            electionTimeoutTask.cancel(true);
        }

        heartbeatTask = scheduler.scheduleAtFixedRate(
            this::sendHeartbeats, 
            0, 
            1000, 
            TimeUnit.MILLISECONDS
        );
    }

    private synchronized void sendHeartbeats() {
        if (currentRole != Role.LEADER) {
            if (heartbeatTask != null) {
                heartbeatTask.cancel(true);
            }
            return;
        }

        // 组装心跳包 RESP 命令：CLUSTER_HEARTBEAT <nodeId> <term>
        List<String> hbCmd = List.of("CLUSTER_HEARTBEAT", nodeId, String.valueOf(currentTerm));

        for (String address : peers.values()) {
            CompletableFuture.runAsync(() -> {
                try {
                    sendCommandToPeer(address, hbCmd, false);
                } catch (Exception ignored) {}
            });
        }
    }

    /**
     * 处理收到的选举选票指令
     */
    public synchronized List<String> handleElectionPacket(List<String> cmd) {
        if (cmd.size() < 4) {
            return List.of("VOTE_REJECTED", String.valueOf(currentTerm));
        }

        String subType = cmd.get(1);
        int term = Integer.parseInt(cmd.get(2));
        String candidateId = cmd.get(3);

        if ("REQUEST_VOTE".equalsIgnoreCase(subType)) {
            if (term > currentTerm) {
                currentTerm = term;
                currentRole = Role.FOLLOWER;
                database.setClusterRole("FOLLOWER");
                votedFor = null;
                leaderId = null;
                resetElectionTimeout();
            }

            if (term == currentTerm && (votedFor == null || votedFor.equals(candidateId))) {
                votedFor = candidateId;
                System.out.println("[Cluster] Granted vote to candidate: " + candidateId + " in term " + term);
                return List.of("VOTE_GRANTED", String.valueOf(currentTerm));
            }
        }
        return List.of("VOTE_REJECTED", String.valueOf(currentTerm));
    }

    /**
     * 处理收到的心跳包
     */
    public synchronized void handleHeartbeatPacket(List<String> cmd) {
        if (cmd.size() < 3) return;
        String termLeader = cmd.get(1);
        int term = Integer.parseInt(cmd.get(2));

        if (term >= currentTerm) {
            if (currentRole != Role.FOLLOWER || !termLeader.equals(leaderId) || term > currentTerm) {
                System.out.println("[Cluster] Node " + nodeId + " follows Leader: " + termLeader + " for Term: " + term);
            }
            currentRole = Role.FOLLOWER;
            database.setClusterRole("FOLLOWER");
            currentTerm = term;
            leaderId = termLeader;
            resetElectionTimeout();
        }
    }

    /**
     * 处理从节点收到的同步复制数据写命令
     */
    public void handleReplicationPacket(List<String> cmd) throws Exception {
        if (cmd.size() < 2) return;
        // cmd.get(0) 为 CLUSTER_REPLICATE，后面跟着写命令列表（例如 ["SET", "name", "mayiqin"]）
        List<String> subCmd = cmd.subList(1, cmd.size());
        database.execute(subCmd, false); // false 代表本地执行，不进行二次集群广播
    }

    /**
     * 新从节点加入主节点，主节点反馈当前所有数据的初始化批量写命令快照
     */
    @SuppressWarnings("unchecked")
    public synchronized List<String> handleSyncJoinRequest() {
        System.out.println("[Cluster] Received snapshot sync request from follower.");
        List<String> snapshotCmds = new ArrayList<>();
        Engine engine = database.getEngine();
        
        // 扫描内存数据库，把当前的 String, List, Set, Hash 数据导出为特殊的带有分隔符的写操作命令
        for (String key : engine.keys()) {
            String type = engine.type(key);
            long ttl = engine.ttl(key);
            
            if ("string".equals(type)) {
                String val = engine.get(key);
                if (val != null) {
                    if (ttl > 0) {
                        snapshotCmds.add("SET\u0001" + key + "\u0001" + val + "\u0001EX\u0001" + ttl);
                    } else {
                        snapshotCmds.add("SET\u0001" + key + "\u0001" + val);
                    }
                }
            } else if ("list".equals(type)) {
                List<String> list = engine.lrange(key, 0, -1);
                for (String item : list) {
                    snapshotCmds.add("RPUSH\u0001" + key + "\u0001" + item);
                }
                if (ttl > 0) snapshotCmds.add("EXPIRE\u0001" + key + "\u0001" + ttl);
            } else if ("set".equals(type)) {
                Set<String> set = engine.smembers(key);
                for (String item : set) {
                    snapshotCmds.add("SADD\u0001" + key + "\u0001" + item);
                }
                if (ttl > 0) snapshotCmds.add("EXPIRE\u0001" + key + "\u0001" + ttl);
            } else if ("hash".equals(type)) {
                Map<String, String> map = engine.hgetall(key);
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    snapshotCmds.add("HSET\u0001" + key + "\u0001" + entry.getKey() + "\u0001" + entry.getValue());
                }
                if (ttl > 0) snapshotCmds.add("EXPIRE\u0001" + key + "\u0001" + ttl);
            }
        }
        return snapshotCmds;
    }

    /**
     * 广播写操作指令给所有的从节点
     */
    private void broadcastReplication(List<String> cmd) {
        List<String> repCmd = new ArrayList<>();
        repCmd.add("CLUSTER_REPLICATE");
        repCmd.addAll(cmd);

        for (String address : peers.values()) {
            CompletableFuture.runAsync(() -> {
                try {
                    sendCommandToPeer(address, repCmd, false);
                } catch (Exception ignored) {}
            });
        }
    }

    /**
     * 向 Peer 主动请求拉取数据快照进行全量初始化同步
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
                    database.getEngine().clear();
                    
                    for (String line : snapshot) {
                        String[] parts = line.split("\u0001");
                        List<String> cmd = Arrays.asList(parts);
                        database.execute(cmd, false);
                    }
                    System.out.println("[Cluster] Data synchronization completed successfully.");
                    break;
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * 通过 TCP 连接发送 RESP 命令到指定 Peer 并接收返回结果
     */
    private List<String> sendCommandToPeer(String address, List<String> cmd, boolean expectResponse) throws IOException {
        String[] hostPort = address.split(":");
        String host = hostPort[0];
        int port = Integer.parseInt(hostPort[1]);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1200);
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

    public synchronized Role getCurrentRole() {
        return currentRole;
    }

    public synchronized String getLeaderId() {
        return leaderId;
    }

    public synchronized boolean isWritable() {
        return peers.isEmpty() || currentRole == Role.LEADER;
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
