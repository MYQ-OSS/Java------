package nosql.server;

import java.util.List;

/**
 * RESTful 风格的 Socket 调度路由器
 *
 * 将 RESP 命令映射为 RESTful 资源路径，满足课程设计 2.2.1.6(4) 要求：
 * "在 Socket 层实现一个简单的类似 RESTful 调度器"
 *
 * 每次客户端命令到达时，在控制台打印对应的 RESTful 路由映射。
 */
public class RestfulDispatcher {

    /**
     * 将客户端命令映射为 RESTful 路由并打印到控制台
     * @param cmd RESP 命令列表
     */
    public void dispatch(List<String> cmd) {
        if (cmd == null || cmd.isEmpty()) return;

        String action = cmd.get(0).toUpperCase();
        String route = mapToRoute(cmd, action);

        System.out.println("  [RESTful] " + route + "  ←  " + String.join(" ", cmd));
    }

    private String mapToRoute(List<String> cmd, String action) {
        switch (action) {
            // ===== Key-Value 操作 =====
            case "SET":
                if (cmd.size() >= 3) {
                    String key = cmd.get(1);
                    return formatKeyRoute("PUT", key, "value");
                }
                return "PUT /keys/{key}/value";
            case "GET":
                if (cmd.size() >= 2) {
                    return formatKeyRoute("GET", cmd.get(1), "value");
                }
                return "GET /keys/{key}/value";
            case "DEL":
                if (cmd.size() >= 2) {
                    StringBuilder keys = new StringBuilder();
                    for (int i = 1; i < cmd.size(); i++) {
                        if (keys.length() > 0) keys.append(",");
                        keys.append(cmd.get(i));
                    }
                    return "DELETE /keys?keys=" + keys;
                }
                return "DELETE /keys/{key}";
            case "EXISTS":
                return "HEAD /keys/" + (cmd.size() >= 2 ? cmd.get(1) : "{key}");
            case "INCR":
                return "POST /keys/" + safeIndex(cmd, 1) + "/incr";
            case "DECR":
                return "POST /keys/" + safeIndex(cmd, 1) + "/decr";
            case "EXPIRE":
                return "PATCH /keys/" + safeIndex(cmd, 1) + "/ttl";
            case "TTL":
                return "GET /keys/" + safeIndex(cmd, 1) + "/ttl";
            case "TYPE":
                return "GET /keys/" + safeIndex(cmd, 1) + "/type";

            // ===== Hash 操作 =====
            case "HSET":
                return "PUT /keys/" + safeIndex(cmd, 1) + "/fields/" + safeIndex(cmd, 2);
            case "HGET":
                return "GET /keys/" + safeIndex(cmd, 1) + "/fields/" + safeIndex(cmd, 2);
            case "HDEL":
                return "DELETE /keys/" + safeIndex(cmd, 1) + "/fields/" + safeIndex(cmd, 2);
            case "HGETALL":
                return "GET /keys/" + safeIndex(cmd, 1) + "/fields";

            // ===== List 操作 =====
            case "LPUSH":
                return "POST /keys/" + safeIndex(cmd, 1) + "/items (prepend)";
            case "RPUSH":
                return "POST /keys/" + safeIndex(cmd, 1) + "/items (append)";
            case "LPOP":
                return "DELETE /keys/" + safeIndex(cmd, 1) + "/items?pop=first";
            case "RPOP":
                return "DELETE /keys/" + safeIndex(cmd, 1) + "/items?pop=last";
            case "LRANGE":
                return "GET /keys/" + safeIndex(cmd, 1) + "/items?start=" +
                        safeIndex(cmd, 2) + "&end=" + safeIndex(cmd, 3);

            // ===== Set 操作 =====
            case "SADD":
                return "POST /keys/" + safeIndex(cmd, 1) + "/members";
            case "SREM":
                return "DELETE /keys/" + safeIndex(cmd, 1) + "/members/" + safeIndex(cmd, 2);
            case "SMEMBERS":
                return "GET /keys/" + safeIndex(cmd, 1) + "/members";
            case "SISMEMBER":
                return "GET /keys/" + safeIndex(cmd, 1) + "/members/" + safeIndex(cmd, 2);

            // ===== 集合管理 (Collection) =====
            case "CREATE":
                if (cmd.size() >= 3 && "COLLECTION".equalsIgnoreCase(cmd.get(1))) {
                    return "POST /collections/" + cmd.get(2);
                }
                return "POST /collections/{name}";
            case "DROP":
                if (cmd.size() >= 3 && "COLLECTION".equalsIgnoreCase(cmd.get(1))) {
                    return "DELETE /collections/" + cmd.get(2);
                }
                return "DELETE /collections/{name}";
            case "LIST":
                if (cmd.size() >= 2 && "COLLECTIONS".equalsIgnoreCase(cmd.get(1))) {
                    return "GET /collections";
                }
                return "GET /collections";

            // ===== 批量操作 =====
            case "MSET":
                return "PUT /keys?batch (bulk set)";
            case "MGET":
                return "GET /keys?batch (bulk get)";

            // ===== 系统命令 =====
            case "PING":
                return "GET /_ping";
            case "INFO":
                return "GET /_info";
            case "DBSIZE":
                return "GET /_dbsize";
            case "KEYS":
                return "GET /keys?pattern=" + safeIndex(cmd, 1);
            case "FLUSHALL":
                return "DELETE /_all";

            // ===== 集群命令（内部） =====
            case "CLUSTER_HEARTBEAT":
                return "INTERNAL /cluster/heartbeat";
            case "CLUSTER_ELECT":
                return "INTERNAL /cluster/election";
            case "CLUSTER_REPLICATE":
                return "INTERNAL /cluster/replicate";
            case "CLUSTER_SYNC":
                return "INTERNAL /cluster/sync";

            default:
                return "UNKNOWN /" + action.toLowerCase();
        }
    }

    /**
     * 将 key 按 Collection:Key 模式分解为 RESTful 路径
     * 例如 "users:profile:1001" → "/collections/users/profile/keys/1001/value"
     */
    private String formatKeyRoute(String method, String fullKey, String suffix) {
        int colonIdx = fullKey.indexOf(':');
        if (colonIdx > 0) {
            String collection = fullKey.substring(0, colonIdx);
            String keyPart = fullKey.substring(colonIdx + 1).replace(':', '/');
            return method + " /collections/" + collection + "/keys/" + keyPart + "/" + suffix;
        }
        return method + " /keys/" + fullKey + "/" + suffix;
    }

    private String safeIndex(List<String> list, int index) {
        return index < list.size() ? list.get(index) : "{?}";
    }
}
