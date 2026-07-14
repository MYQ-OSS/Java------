package nosql.server;

import java.util.*;

/**
 * RESTful 风格的 Socket 调度路由器（增强版）
 *
 * 双重功能：
 * 1. RESP 命令 → RESTful 路由日志打印（控制台可视化）
 * 2. HTTP 请求 → RouteHandler 路由分发（真正的 HTTP API）
 *
 * 满足课程设计 2.2.1.6(4) 要求：
 * "在 Socket 层实现一个简单的类似 RESTful 调度器"
 */
public class RestfulDispatcher {

    // ===== HTTP 路由注册（精确匹配 + 模式匹配） =====
    private final Map<String, RouteHandler> exactRoutes = new HashMap<>();
    private final Map<String, RouteHandler> patternRoutes = new LinkedHashMap<>();
    private Database database; // 关联的数据库实例（用于 HTTP 处理）

    public RestfulDispatcher() {}

    /**
     * 设置数据库引用（用于 HTTP 请求处理器）
     */
    public void setDatabase(Database database) {
        this.database = database;
    }

    /**
     * 注册 HTTP 路由
     * @param method  HTTP 方法（GET/POST/PUT/DELETE/PATCH）
     * @param path    路径，支持 {key} 作为动态路径参数
     * @param handler 处理器
     */
    public void register(String method, String path, RouteHandler handler) {
        if (path.contains("{") && path.contains("}")) {
            String pattern = method + ":" + path;
            patternRoutes.put(pattern, handler);
        } else {
            exactRoutes.put(method + ":" + path, handler);
        }
    }

    /**
     * 查找匹配的路由处理器
     */
    public RouteHandler findHandler(String method, String path) {
        // 1. 精确匹配
        String exactKey = method + ":" + path;
        if (exactRoutes.containsKey(exactKey)) {
            return exactRoutes.get(exactKey);
        }

        // 2. 模式匹配（路径参数 {key}）
        String pathWithoutQuery = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        for (Map.Entry<String, RouteHandler> entry : patternRoutes.entrySet()) {
            String[] keyParts = entry.getKey().split(":", 2);
            if (!keyParts[0].equals(method)) continue;
            String routePath = keyParts[1];

            // 将 {param} 替换为正则 [^/]+，然后匹配
            String regex = routePath
                    .replaceAll("\\{[^}]+\\}", "([^/]+)")
                    .replaceAll("/", "\\\\/");
            if (pathWithoutQuery.matches(regex)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 提取路径参数
     * 例如：路径 /api/v1/keys/user:001，路由 /api/v1/keys/{key}
     * 返回 {"key": "user:001"}
     */
    public Map<String, String> extractPathParams(String path, String routePattern) {
        Map<String, String> params = new HashMap<>();
        String pathClean = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String[] pathParts = pathClean.split("/");
        String[] patternParts = routePattern.split("/");

        for (int i = 0; i < patternParts.length && i < pathParts.length; i++) {
            if (patternParts[i].startsWith("{") && patternParts[i].endsWith("}")) {
                String paramName = patternParts[i].substring(1, patternParts[i].length() - 1);
                params.put(paramName, pathParts[i]);
            }
        }
        return params;
    }

    /**
     * 注册所有标准 RESTful API 路由
     */
    public void registerDefaultRoutes() {
        if (database == null) return;

        // POST /api/v1/keys/{key} — 存储键值对
        register("POST", "/api/v1/keys/{key}", request -> {
            String key = request.getPathParam("/api/v1/keys/");
            if (key == null || key.isEmpty()) {
                return HttpResponse.badRequest("Missing key in path");
            }
            String body = request.getBody();
            if (body == null || body.isEmpty()) {
                return HttpResponse.badRequest("Missing request body");
            }
            // 简单解析 JSON body: {"value": "..."}
            String value = extractJsonField(body, "value");
            if (value == null) {
                // Fallback：body 本身就是 value
                value = body.trim();
            }
            try {
                database.executeLocal(
                    List.of("SET", key, value),
                    database.getEngine()
                );
                return HttpResponse.created(key);
            } catch (Exception e) {
                return HttpResponse.error(500, e.getMessage());
            }
        });

        // GET /api/v1/keys/{key} — 获取值
        register("GET", "/api/v1/keys/{key}", request -> {
            String key = request.getPathParam("/api/v1/keys/");
            if (key == null || key.isEmpty()) {
                return HttpResponse.badRequest("Missing key in path");
            }
            try {
                String value = database.getEngine().get(key);
                if (value == null) {
                    return HttpResponse.notFound("Key not found: " + key);
                }
                return HttpResponse.ok(value);
            } catch (Exception e) {
                return HttpResponse.error(500, e.getMessage());
            }
        });

        // DELETE /api/v1/keys/{key} — 删除键
        register("DELETE", "/api/v1/keys/{key}", request -> {
            String key = request.getPathParam("/api/v1/keys/");
            if (key == null || key.isEmpty()) {
                return HttpResponse.badRequest("Missing key in path");
            }
            try {
                database.getEngine().delete(key);
                return HttpResponse.ok(key + " deleted");
            } catch (Exception e) {
                return HttpResponse.error(500, e.getMessage());
            }
        });

        // GET /api/v1/keys — 列出所有键（可选 ?pattern=...）
        register("GET", "/api/v1/keys", request -> {
            try {
                Map<String, String> params = request.getQueryParams();
                String pattern = params.getOrDefault("pattern", "*");
                java.util.List<String> keys;
                if ("*".equals(pattern)) {
                    keys = new ArrayList<>(database.getEngine().keys());
                } else {
                    // 简单通配符过滤
                    keys = new ArrayList<>();
                    for (String k : database.getEngine().keys()) {
                        if (matchPattern(k, pattern)) keys.add(k);
                    }
                }
                Collections.sort(keys);
                return HttpResponse.ok(keys);
            } catch (Exception e) {
                return HttpResponse.error(500, e.getMessage());
            }
        });

        // GET /api/v1/exists/{key} — 检查键是否存在
        register("GET", "/api/v1/exists/{key}", request -> {
            String key = request.getPathParam("/api/v1/exists/");
            if (key == null || key.isEmpty()) {
                return HttpResponse.badRequest("Missing key in path");
            }
            try {
                boolean exists = database.getEngine().exists(key);
                return HttpResponse.ok(exists);
            } catch (Exception e) {
                return HttpResponse.error(500, e.getMessage());
            }
        });

        // DELETE /api/v1/flush — 清空所有数据
        register("DELETE", "/api/v1/flush", request -> {
            try {
                database.getEngine().clear();
                return HttpResponse.ok("FLUSHALL OK");
            } catch (Exception e) {
                return HttpResponse.error(500, e.getMessage());
            }
        });

        // GET /api/v1/dbsize — 数据库键总数
        register("GET", "/api/v1/dbsize", request -> {
            try {
                int size = database.getEngine().dbSize();
                return HttpResponse.ok(size);
            } catch (Exception e) {
                return HttpResponse.error(500, e.getMessage());
            }
        });

        // GET /api/v1/ping — 健康检查
        register("GET", "/api/v1/ping", request -> HttpResponse.ok("PONG"));

        // GET /api/v1/info — 服务器信息
        register("GET", "/api/v1/info", request -> {
            try {
                return HttpResponse.ok(database.getEngine().getMemTableSize());
            } catch (Exception e) {
                return HttpResponse.error(500, e.getMessage());
            }
        });

        System.out.println("[RESTful] Default API routes registered (10 endpoints)");
    }

    // ===== 简易 JSON 字段提取 =====

    private static String extractJsonField(String json, String fieldName) {
        if (json == null) return null;
        // 简易提取 "fieldName": "value" 或 "fieldName":"value"
        String searchKey = "\"" + fieldName + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return null;
        int start = colonIdx + 1;
        // 跳过空白
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) {
            start++;
        }
        if (start >= json.length()) return null;
        char firstChar = json.charAt(start);
        if (firstChar == '"') {
            // 字符串值
            int end = json.indexOf('"', start + 1);
            if (end < 0) return null;
            return json.substring(start + 1, end);
        } else {
            // 数字/布尔
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != '\n') {
                end++;
            }
            return json.substring(start, end).trim();
        }
    }

    // ===== 通配符匹配 =====

    private static boolean matchPattern(String str, String pattern) {
        // 简单实现：* 匹配任意字符
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return str.matches(regex);
    }

    // ==========================================
    // RESP 命令 → RESTful 路由日志打印（保留原有功能）
    // ==========================================

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
