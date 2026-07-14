package examples;

import nosql.client.NoSqlSdk;
import java.util.List;

/**
 * NoSqlSdk Java SDK 编程调用示例
 *
 * 编译: javac -encoding UTF-8 -cp dist/cli.jar examples/SdkUsageExample.java
 * 运行: java -cp dist/cli.jar;examples SdkUsageExample
 *
 * 演示了 SDK 的所有核心 API：String、Hash、List、Set、批量操作、系统命令
 */
public class SdkUsageExample {

    public static void main(String[] args) {
        String host = args.length >= 1 ? args[0] : "127.0.0.1";
        int port = args.length >= 2 ? Integer.parseInt(args[1]) : 8080;

        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║   NoSqlSdk Java SDK 编程调用示例          ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println("连接: " + host + ":" + port + "\n");

        try (NoSqlSdk sdk = new NoSqlSdk(host, port)) {

            // ═══════════════════════════════════
            // 1. 基础 String 操作
            // ═══════════════════════════════════
            System.out.println("─── 1. String 基础操作 ───");
            sdk.sendCommand(List.of("SET", "username", "Alice"));
            System.out.println("SET username Alice → " + sdk.sendCommand(List.of("GET", "username")));

            sdk.sendCommand(List.of("SET", "counter", "0"));
            System.out.println("INCR counter → " + sdk.sendCommand(List.of("INCR", "counter")));
            System.out.println("INCR counter → " + sdk.sendCommand(List.of("INCR", "counter")));

            // 设置带过期时间的 key
            sdk.sendCommand(List.of("SET", "session", "abc123", "EX", "60"));
            System.out.println("TTL session → " + sdk.sendCommand(List.of("TTL", "session")) + " 秒");

            // ═══════════════════════════════════
            // 2. Hash 操作
            // ═══════════════════════════════════
            System.out.println("\n─── 2. Hash 操作 ───");
            sdk.sendCommand(List.of("HSET", "user:1001", "name", "Bob"));
            sdk.sendCommand(List.of("HSET", "user:1001", "age", "25"));
            sdk.sendCommand(List.of("HSET", "user:1001", "email", "bob@example.com"));

            System.out.println("HGET user:1001 name → " +
                    sdk.sendCommand(List.of("HGET", "user:1001", "name")));

            System.out.println("HGETALL user:1001 → " +
                    sdk.sendCommand(List.of("HGETALL", "user:1001")));

            // ═══════════════════════════════════
            // 3. List 操作
            // ═══════════════════════════════════
            System.out.println("\n─── 3. List 操作 ───");
            sdk.sendCommand(List.of("RPUSH", "tasks", "设计文档"));
            sdk.sendCommand(List.of("RPUSH", "tasks", "编写代码"));
            sdk.sendCommand(List.of("RPUSH", "tasks", "单元测试"));
            sdk.sendCommand(List.of("LPUSH", "tasks", "需求分析"));

            System.out.println("LRANGE tasks 0 -1 → " +
                    sdk.sendCommand(List.of("LRANGE", "tasks", "0", "-1")));

            System.out.println("LPOP tasks → " + sdk.sendCommand(List.of("LPOP", "tasks")));

            // ═══════════════════════════════════
            // 4. Set 操作
            // ═══════════════════════════════════
            System.out.println("\n─── 4. Set 操作 ───");
            sdk.sendCommand(List.of("SADD", "tags", "java"));
            sdk.sendCommand(List.of("SADD", "tags", "nosql"));
            sdk.sendCommand(List.of("SADD", "tags", "database"));

            System.out.println("SMEMBERS tags → " +
                    sdk.sendCommand(List.of("SMEMBERS", "tags")));
            System.out.println("SISMEMBER tags java → " +
                    sdk.sendCommand(List.of("SISMEMBER", "tags", "java")));

            // ═══════════════════════════════════
            // 5. 批量操作
            // ═══════════════════════════════════
            System.out.println("\n─── 5. 批量操作 ───");
            System.out.println("MSET k1 v1 k2 v2 k3 v3 → " +
                    sdk.sendCommand(List.of("MSET", "k1", "v1", "k2", "v2", "k3", "v3")));

            System.out.println("MGET k1 k2 k3 → " +
                    sdk.sendCommand(List.of("MGET", "k1", "k2", "k3")));

            // ═══════════════════════════════════
            // 6. Collection 管理
            // ═══════════════════════════════════
            System.out.println("\n─── 6. Collection 管理 ───");
            sdk.sendCommand(List.of("CREATE", "COLLECTION", "products"));
            System.out.println("LIST COLLECTIONS → " +
                    sdk.sendCommand(List.of("LIST", "COLLECTIONS")));

            sdk.sendCommand(List.of("SET", "products:100", "iPhone 15"));
            System.out.println("GET products:100 → " +
                    sdk.sendCommand(List.of("GET", "products:100")));

            // ═══════════════════════════════════
            // 7. 系统命令
            // ═══════════════════════════════════
            System.out.println("\n─── 7. 系统信息 ───");
            System.out.println("PING → " + sdk.sendCommand(List.of("PING")));
            System.out.println("DBSIZE → " + sdk.sendCommand(List.of("DBSIZE")));

            String info = (String) sdk.sendCommand(List.of("INFO"));
            System.out.println("INFO →");
            for (String line : info.split("\r\n")) {
                System.out.println("  " + line);
            }

            System.out.println("\n✅ SDK 示例执行完毕！");

        } catch (Exception e) {
            System.err.println("❌ 错误: " + e.getMessage());
            System.err.println("请确保 NoSQL Server 已在 " + host + ":" + port + " 启动。");
            System.exit(1);
        }
    }
}
