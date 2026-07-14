package nosql.server;

/**
 * RESTful 路由处理器函数式接口
 * 每个 HTTP 端点对应一个 RouteHandler 实现
 */
@FunctionalInterface
public interface RouteHandler {
    /**
     * 处理 HTTP 请求并返回响应
     * @param request 解析后的 HTTP 请求
     * @return HTTP 响应
     */
    HttpResponse handle(HttpRequest request);
}
