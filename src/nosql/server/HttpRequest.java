package nosql.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 请求解析器
 * 从 Socket 输入流中解析 HTTP-like 请求行、请求头和请求体
 * 满足课程设计 2.2.1.6(4) RESTful API 接口要求
 */
public class HttpRequest {

    private String method;
    private String path;
    private String version;
    private final Map<String, String> headers = new HashMap<>();
    private String body;

    private HttpRequest() {}

    /**
     * 从 BufferedReader 解析 HTTP 请求
     */
    public static HttpRequest parse(BufferedReader reader) throws IOException {
        HttpRequest request = new HttpRequest();

        // 1. 解析请求行: "GET /api/v1/keys/user:001 HTTP/1.1"
        String firstLine = reader.readLine();
        if (firstLine == null || firstLine.isEmpty()) {
            return null;
        }
        String[] parts = firstLine.split(" ", 3);
        if (parts.length < 2) {
            return null;
        }
        request.method = parts[0].toUpperCase();
        request.path = parts[1];
        request.version = parts.length > 2 ? parts[2] : "HTTP/1.1";

        // 2. 解析请求头
        int contentLength = 0;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            String[] headerParts = line.split(": ", 2);
            if (headerParts.length == 2) {
                request.headers.put(headerParts[0], headerParts[1]);
                if ("Content-Length".equalsIgnoreCase(headerParts[0])) {
                    try {
                        contentLength = Integer.parseInt(headerParts[1].trim());
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        // 3. 解析请求体
        if (contentLength > 0) {
            char[] bodyChars = new char[contentLength];
            int totalRead = 0;
            while (totalRead < contentLength) {
                int read = reader.read(bodyChars, totalRead, contentLength - totalRead);
                if (read == -1) break;
                totalRead += read;
            }
            request.body = new String(bodyChars, 0, totalRead);
        }

        return request;
    }

    /**
     * 简易解析：直接从单行文本解析为 HTTP 请求（兼容 text-protocol 模式）
     * 格式: METHOD /path [body]
     */
    public static HttpRequest parseSimple(String text) {
        HttpRequest request = new HttpRequest();
        String[] parts = text.trim().split("\\s+", 3);
        if (parts.length >= 2) {
            request.method = parts[0].toUpperCase();
            request.path = parts[1];
            request.version = "HTTP/1.1";
            if (parts.length >= 3) {
                request.body = parts[2];
            }
        }
        return request;
    }

    // ===== Getter 方法 =====

    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getVersion() { return version; }
    public String getBody() { return body; }
    public String getHeader(String name) { return headers.get(name); }
    public Map<String, String> getHeaders() { return headers; }

    /**
     * 从路径中提取参数
     * 例如：/api/v1/keys/user:001 → 去掉前缀后得到 user:001
     */
    public String getPathParam(String prefix) {
        if (path != null && path.startsWith(prefix)) {
            return path.substring(prefix.length());
        }
        return null;
    }

    /**
     * 解析查询参数
     * 例如：/api/v1/keys?pattern=user:* → {"pattern": "user:*"}
     */
    public Map<String, String> getQueryParams() {
        Map<String, String> params = new HashMap<>();
        if (path == null || !path.contains("?")) return params;
        String queryString = path.substring(path.indexOf("?") + 1);
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], kv[1]);
            }
        }
        return params;
    }

    /**
     * 获取不包含查询参数的纯路径
     */
    public String getPathWithoutQuery() {
        if (path == null) return null;
        int qIdx = path.indexOf('?');
        return qIdx >= 0 ? path.substring(0, qIdx) : path;
    }
}
