package nosql.server;

import nosql.cluster.ClusterManager;
import nosql.common.RespParser;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 兼容 Redis RESP 协议规范的 TCP 服务端主程序
 * 集成了 RESTful 风格的命令调度器日志输出
 */
public class DatabaseServer implements AutoCloseable {

    private final int port;
    private final Database database;
    private final ClusterManager clusterManager;
    private final ExecutorService executor;
    private final RestfulDispatcher restfulDispatcher;
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    private ServerSocket serverSocket;
    private volatile boolean running = true;

    public DatabaseServer(int port, String mode, String nodeId, String peersStr, String dataDir, long rotateThreshold) throws Exception {
        this.port = port;
        this.database = new Database(dataDir, rotateThreshold);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.restfulDispatcher = new RestfulDispatcher();
        this.restfulDispatcher.setDatabase(this.database);
        this.restfulDispatcher.registerDefaultRoutes(); // 注册 HTTP API 路由

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
                int connCount = activeConnections.incrementAndGet();
                System.out.println("[Server] New connection accepted. Active connections: " + connCount);
                executor.submit(() -> {
                    try {
                        handleClient(socket);
                    } finally {
                        activeConnections.decrementAndGet();
                    }
                });
            } catch (IOException e) {
                if (!running) break;
                System.err.println("[Server] Accept error: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            // 探测连接类型：使用 BufferedInputStream 支持 mark/reset
            socket.setSoTimeout(2000); // 2秒超时用于协议探测
            BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
            bis.mark(4);
            int firstByte = bis.read();

            if (firstByte == 'G' || firstByte == 'P' || firstByte == 'D' || firstByte == 'H' || firstByte == 'O') {
                // 可能是 HTTP 请求
                bis.reset(); // 回退已读字节
                // 将 BufferedInputStream 包装后的 socket 传入 HTTP 处理器
                handleHttpClient(socket, bis);
                return;
            }
            // RESP 协议：首字节通常是 '*'（数组）或 '+'（简单字符串）
            if (firstByte == '*' || firstByte == '+') {
                bis.reset();
                handleRespClient(socket, bis);
                return;
            }
            // 无法识别的协议
            System.out.println("[Server] Unknown protocol, first byte: 0x" +
                Integer.toHexString(firstByte) + " (" + (char)firstByte + ")");
            socket.close();
        } catch (java.net.SocketTimeoutException e) {
            // 超时后按 RESP 处理
            handleRespClient(socket, null);
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * HTTP 请求处理器
     */
    private void handleHttpClient(Socket socket, BufferedInputStream bis) {
        try (
            BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(bis, java.nio.charset.StandardCharsets.UTF_8));
            java.io.PrintWriter writer = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(socket.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8), true)
        ) {
            socket.setSoTimeout(30000); // 30秒超时

            // 1. 解析 HTTP 请求
            HttpRequest request = HttpRequest.parse(reader);
            if (request == null) {
                writer.println(HttpResponse.badRequest("Invalid HTTP request").toHttpString());
                return;
            }

            System.out.println("[HTTP] " + request.getMethod() + " " + request.getPath());

            // 2. 处理 OPTIONS 预检请求（CORS）
            if ("OPTIONS".equals(request.getMethod())) {
                writer.println(HttpResponse.ok("OK").toHttpString());
                return;
            }

            // 3. 查找路由
            RouteHandler handler = restfulDispatcher.findHandler(request.getMethod(), request.getPath());
            if (handler == null) {
                writer.println(HttpResponse.notFound(
                    "Route not found: " + request.getMethod() + " " + request.getPath()).toHttpString());
                return;
            }

            // 4. 执行处理
            HttpResponse response = handler.handle(request);

            // 5. 返回响应
            writer.println(response.toHttpString());
            writer.flush();

        } catch (Exception e) {
            System.err.println("[HTTP] Error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * RESP 协议客户端处理器（原有逻辑）
     */
    private void handleRespClient(Socket socket, BufferedInputStream bis) {
        try {
            // 使用传入的 BufferedInputStream 或创建新的
            InputStream in = bis != null ? bis : socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            socket.setSoTimeout(0); // 维持长连接，不超时

            while (running && !socket.isClosed()) {
                List<String> cmd;
                try {
                    cmd = RespParser.readRequest(in);
                } catch (EOFException e) {
                    break; // 客户端正常断开连接
                } catch (IOException e) {
                    System.err.println("[Server] Client read error: " + e.getMessage());
                    break;
                }

                if (cmd == null || cmd.isEmpty()) {
                    continue;
                }

                String action = cmd.get(0).toUpperCase();

                // ==========================================
                // RESTful 风格路由日志打印
                // ==========================================
                restfulDispatcher.dispatch(cmd);

                // ==========================================
                // 1. 路由拦截集群特有命令 (CLUSTER_*)
                // ==========================================
                if ("CLUSTER_HEARTBEAT".equals(action)) {
                    if (clusterManager != null) {
                        clusterManager.handleHeartbeatPacket(cmd);
                    }
                    continue;
                }

                if ("CLUSTER_REPLICATE".equals(action)) {
                    if (clusterManager != null) {
                        clusterManager.handleReplicationPacket(cmd);
                    }
                    continue;
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

                // ════════════════════════════════════════════════════════
                // 【冲突五】写操作发给 Follower 节点的冲突处理
                //
                // 场景：客户端连到 Follower 节点，执行 SET / DEL 等写命令
                // 处理：直接拒绝，返回错误
                //   "Operation rejected: Current node is not Cluster Master."
                //
                // 原理：集群中只有 Leader 能写数据。
                //   Follower 不转发请求给 Leader（避免额外复杂度），
                //   而是直接拒绝，让客户端自己知道要连 Leader。
                //
                // isWritable() 的实现在 ClusterManager：
                //   单机模式 → true（允许写）
                //   集群模式 → 只有 LEADER 返回 true
                // ════════════════════════════════════════════════════════
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
            System.err.println("[Server] Connection error: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private boolean isWriteOp(String cmd) {
        if ("CREATE".equalsIgnoreCase(cmd) || "DROP".equalsIgnoreCase(cmd)) return true;
        switch (cmd.toUpperCase()) {
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
            case "MSET":
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
        // 处理 ErrorResult 包装的错误
        if (result instanceof Database.ErrorResult) {
            RespParser.writeError(out, ((Database.ErrorResult) result).getMessage());
            return;
        }

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
