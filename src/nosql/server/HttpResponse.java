package nosql.server;

import java.nio.charset.StandardCharsets;

/**
 * HTTP 响应构建器
 * 生成 JSON 格式的 HTTP 响应（code + message + data）
 * 满足课程设计 2.2.1.6(4) RESTful API 响应格式要求
 */
public class HttpResponse {

    private final int statusCode;
    private final String statusMessage;
    private final Object data;

    private HttpResponse(int statusCode, String statusMessage, Object data) {
        this.statusCode = statusCode;
        this.statusMessage = statusMessage;
        this.data = data;
    }

    // ===== 工厂方法 =====

    /** 200 OK */
    public static HttpResponse ok(Object data) {
        return new HttpResponse(200, "OK", data);
    }

    /** 201 Created */
    public static HttpResponse created(Object data) {
        return new HttpResponse(201, "Created", data);
    }

    /** 400 Bad Request */
    public static HttpResponse badRequest(String message) {
        return new HttpResponse(400, "Bad Request", message);
    }

    /** 404 Not Found */
    public static HttpResponse notFound(String message) {
        return new HttpResponse(404, "Not Found", message);
    }

    /** 500 Internal Server Error */
    public static HttpResponse error(int code, String message) {
        return new HttpResponse(code, "Internal Server Error", message);
    }

    /** 405 Method Not Allowed */
    public static HttpResponse methodNotAllowed() {
        return new HttpResponse(405, "Method Not Allowed", null);
    }

    // ===== 序列化为 HTTP 响应字符串 =====

    /**
     * 转换为完整的 HTTP 响应字符串（含状态行 + 头 + JSON body）
     */
    public String toHttpString() {
        String jsonBody = toJson();
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusMessage).append("\r\n");
        sb.append("Content-Type: application/json; charset=utf-8\r\n");
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, PATCH, OPTIONS\r\n");
        sb.append("Access-Control-Allow-Headers: Content-Type\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        sb.append(jsonBody);

        return sb.toString();
    }

    /**
     * 构建 JSON 字符串
     * 格式: {"code":200,"message":"OK","data":...}
     */
    private String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\"code\":").append(statusCode);
        json.append(",\"message\":\"").append(escapeJson(statusMessage)).append("\"");
        json.append(",\"data\":").append(valueToJson(data));
        json.append("}");
        return json.toString();
    }

    @SuppressWarnings("unchecked")
    private static String valueToJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "\"" + escapeJson((String) value) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof java.util.List) {
            java.util.List<Object> list = (java.util.List<Object>) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(valueToJson(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        // fallback：toString 并加引号
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ===== Getter =====

    public int getStatusCode() { return statusCode; }
    public String getStatusMessage() { return statusMessage; }
    public Object getData() { return data; }
}
