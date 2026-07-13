package nosql.server;

import nosql.cluster.ClusterManager;
import nosql.common.RespParser;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 兼容 Redis RESP 协议规范的 TCP 服务端主程序
 */
public class DatabaseServer implements AutoCloseable {

    private final int port;
    private final Database database;
    private final ClusterManager clusterManager;
    private final ExecutorService executor;

    private ServerSocket serverSocket;
    private volatile boolean running = true;

    public DatabaseServer(int port, String mode, String nodeId, String peersStr, String dataDir, long rotateThreshold) throws Exception {
        this.port = port;
        this.database = new Database(dataDir, rotateThreshold);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();

        if ("cluster".equalsIgnoreCase(mode)) {
            this.clusterManager = new ClusterManager(nodeId, port, peersStr, this.database);
        } else {
            this.clusterManager = null;
        }
    }

    public void start() throws IOException {
        this.serverSocket = new ServerSocket(port);
        System.out.println("[Server] Redis-compatible NoSQL Server listening on port: " + port);

        if (clusterManager != null) {
            clusterManager.start();
        }

        while (running) {
            try {
                Socket socket = serverSocket.accept();
                executor.submit(() -> handleClient(socket));
            } catch (IOException e) {
                if (!running) break;
                e.printStackTrace();
            }
        }
    }

    private void handleClient(Socket socket) {
        try (
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream()
        ) {
            socket.setSoTimeout(0); // 维持长连接，不超时

            while (running && !socket.isClosed()) {
                List<String> cmd;
                try {
                    cmd = RespParser.readRequest(in);
                } catch (EOFException e) {
                    break; // 客户端正常断开连接
                } catch (IOException e) {
                    break;
                }

                if (cmd == null || cmd.isEmpty()) {
                    continue;
                }

                String action = cmd.get(0).toUpperCase();

                // ==========================================
                // 1. 路由拦截集群特有命令 (CLUSTER_*)
                // ==========================================
                if ("CLUSTER_HEARTBEAT".equals(action)) {
                    if (clusterManager != null) {
                        clusterManager.handleHeartbeatPacket(cmd);
                    }
                    continue; // 单向心跳，无需响应
                }

                if ("CLUSTER_REPLICATE".equals(action)) {
                    if (clusterManager != null) {
                        clusterManager.handleReplicationPacket(cmd);
                    }
                    continue; // 单向复制流，无需响应
                }

                if ("CLUSTER_ELECT".equals(action)) {
                    if (clusterManager != null) {
                        List<String> voteResp = clusterManager.handleElectionPacket(cmd);
                        RespParser.writeArray(out, voteResp);
                    }
                    continue;
                }

                if ("CLUSTER_SYNC".equals(action)) {
                    if (clusterManager != null) {
                        List<String> snapshot = clusterManager.handleSyncJoinRequest();
                        RespParser.writeArray(out, snapshot);
                    }
                    continue;
                }

                // ==========================================
                // 2. 路由常规 Redis 命令
                // ==========================================
                
                // 写操作前置状态检查：Slave 节点禁止写入
                if (isWriteOp(action)) {
                    if (clusterManager != null && !clusterManager.isWritable()) {
                        RespParser.writeError(out, "Operation rejected: Current node is not Cluster Master.");
                        continue;
                    }
                }

                try {
                    // 执行业务指令，并开启 AOF 记录和集群主从同步广播
                    Object result = database.execute(cmd, true);
                    sendRespResponse(out, result);
                } catch (Exception ex) {
                    RespParser.writeError(out, ex.getMessage());
                }
            }
        } catch (Exception e) {
            // 异常捕获，正常记录
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private boolean isWriteOp(String action) {
        switch (action) {
            case "SET":
            case "DEL":
            case "EXPIRE":
            case "INCR":
            case "DECR":
            case "HSET":
            case "HDEL":
            case "LPUSH":
            case "RPUSH":
            case "LPOP":
            case "RPOP":
            case "SADD":
            case "SREM":
            case "FLUSHALL":
                return true;
            default:
                return false;
        }
    }

    /**
     * 将业务计算结果转化为 Redis RESP 协议对应的二进制数据响应流
     */
    @SuppressWarnings("unchecked")
    private void sendRespResponse(OutputStream out, Object result) throws IOException {
        if (result == null) {
            RespParser.writeNullBulkString(out);
        } else if (result instanceof String) {
            String str = (String) result;
            if ("OK".equals(str) || "PONG".equals(str)) {
                RespParser.writeSimpleString(out, str);
            } else {
                RespParser.writeBulkString(out, str);
            }
        } else if (result instanceof Long) {
            RespParser.writeInteger(out, (Long) result);
        } else if (result instanceof Integer) {
            RespParser.writeInteger(out, (Integer) result);
        } else if (result instanceof List) {
            RespParser.writeArray(out, (List<String>) result);
        } else if (result instanceof Set) {
            RespParser.writeArray(out, new ArrayList<>((Set<String>) result));
        } else {
            RespParser.writeBulkString(out, result.toString());
        }
    }

    @Override
    public synchronized void close() throws Exception {
        this.running = false;
        if (serverSocket != null) {
            serverSocket.close();
        }
        if (clusterManager != null) {
            clusterManager.close();
        }
        database.close();
        executor.shutdown();
    }

    public static void main(String[] args) {
        int port = 8080;
        String mode = "standalone";
        String nodeId = "node_1";
        String peersStr = "";
        String dataDir = "./data";
        long rotateThreshold = 2 * 1024 * 1024; // 2MB

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "--mode":
                    mode = args[++i];
                    break;
                case "--id":
                    nodeId = args[++i];
                    break;
                case "--peers":
                    peersStr = args[++i];
                    break;
                case "--data-dir":
                    dataDir = args[++i];
                    break;
                case "--rotate-threshold":
                    rotateThreshold = Long.parseLong(args[++i]);
                    break;
            }
        }

        System.out.println("[System] Launching Redis-compatible database server in " + mode.toUpperCase() + " mode...");
        
        try (DatabaseServer server = new DatabaseServer(port, mode, nodeId, peersStr, dataDir, rotateThreshold)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[System] Shutdown hook triggered. Safely closing database resources...");
                try {
                    server.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
            server.start();
        } catch (Exception e) {
            System.err.println("[System] Failed to launch database server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
