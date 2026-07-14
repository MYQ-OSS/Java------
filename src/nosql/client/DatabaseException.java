package nosql.client;

/**
 * easy-db Java SDK 统一异常类
 * 封装所有与数据库通信相关的异常，包括连接失败、命令执行失败、协议错误等
 */
public class DatabaseException extends RuntimeException {

    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
