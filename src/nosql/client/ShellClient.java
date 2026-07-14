package nosql.client;

import java.util.ArrayList;
import java.util.List;

/**
 * easy-db Shell 工具 (Java 实现 + Shell 包装方案)
 *
 * 通过环境变量 EASY_DB_HOST / EASY_DB_PORT 配置连接，
 * 支持在 Windows/Linux Shell 中直接调用数据库命令。
 *
 * 用法:
 *   java -jar dist/shell.jar set name 张三
 *   java -jar dist/shell.jar get name
 *   java -jar dist/shell.jar keys user:*
 *
 * 环境变量:
 *   EASY_DB_HOST  服务器地址 (默认 127.0.0.1)
 *   EASY_DB_PORT  服务器端口 (默认 8080)
 */
public class ShellClient {

    private static final String VERSION = "1.0.0";
    private static String host = "127.0.0.1";
    private static int port = 8080;
    private static boolean silent = false;

    public static void main(String[] args) {
        // 1. 解析环境变量
        host = System.getenv().getOrDefault("EASY_DB_HOST", host);
        String portStr = System.getenv().getOrDefault("EASY_DB_PORT", String.valueOf(port));
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            System.err.println("(error) Invalid EASY_DB_PORT: " + portStr);
            System.exit(1);
        }

        // 2. 解析命令行参数
        List<String> commandArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-s".equals(arg) || "--silent".equals(arg)) {
                silent = true;
            } else if ("-h".equals(arg) || "--help".equals(arg)) {
                printHelp();
                return;
            } else if ("--version".equals(arg) || "-v".equals(arg)) {
                System.out.println("easy-db Shell Tool v" + VERSION + " (Java)");
                return;
            } else {
                commandArgs.add(arg);
            }
        }

        if (commandArgs.isEmpty()) {
            printHelp();
            return;
        }

        // 3. 执行命令
        try (NoSqlSdk sdk = new NoSqlSdk(host, port)) {
            List<String> cmd = parseToCommandList(commandArgs);
            Object result = sdk.sendCommand(cmd);

            if (result == null) {
                System.out.println("(nil)");
            } else if (result instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) result;
                for (Object item : list) {
                    System.out.println(item);
                }
            } else {
                System.out.println(result);
            }
        } catch (Exception e) {
            if (!silent) {
                System.err.println("(error) " + e.getMessage());
            }
            System.exit(1);
        }
    }

    /**
     * 将命令行参数列表转为 SDK 命令列表
     * 第一个参数是命令名，后续是参数
     */
    private static List<String> parseToCommandList(List<String> args) {
        List<String> result = new ArrayList<>();
        for (String arg : args) {
            result.add(arg);
        }
        return result;
    }

    private static void printHelp() {
        System.out.println("easy-db Shell Tool v" + VERSION + " (Java)");
        System.out.println();
        System.out.println("用法: easy-db <command> [args...]");
        System.out.println();
        System.out.println("命令:");
        System.out.println("  set    <key> <value>          存储键值对");
        System.out.println("  get    <key>                  获取键对应的值");
        System.out.println("  del    <key>                  删除键值对");
        System.out.println("  keys   [pattern]              列出所有键");
        System.out.println("  exists <key>                  检查键是否存在");
        System.out.println("  flush                         清空所有数据");
        System.out.println("  hset   <key> <field> <value>  设置 Hash 字段");
        System.out.println("  hget   <key> <field>          获取 Hash 字段");
        System.out.println("  hgetall <key>                 获取 Hash 全部字段");
        System.out.println("  lpush  <key> <value>          列表头部推入");
        System.out.println("  rpush  <key> <value>          列表尾部推入");
        System.out.println("  lrange <key> <start> <end>    列表范围查询");
        System.out.println("  sadd   <key> <member>         集合添加成员");
        System.out.println("  smembers <key>                集合所有成员");
        System.out.println("  incr   <key>                  自增");
        System.out.println("  decr   <key>                  自减");
        System.out.println("  expire <key> <seconds>        设置过期时间");
        System.out.println("  ttl    <key>                  查看剩余生存时间");
        System.out.println("  type   <key>                  查看数据类型");
        System.out.println("  dbsize                        数据库键总数");
        System.out.println("  ping                          服务端连通测试");
        System.out.println("  info                          服务端信息");
        System.out.println();
        System.out.println("环境变量:");
        System.out.println("  EASY_DB_HOST  服务器地址 (默认: 127.0.0.1)");
        System.out.println("  EASY_DB_PORT  服务器端口 (默认: 8080)");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  -s, --silent  静默模式，只输出数据");
        System.out.println("  -h, --help    显示帮助信息");
        System.out.println("  -v, --version 显示版本号");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  easy-db set name 张三");
        System.out.println("  easy-db get name");
        System.out.println("  easy-db keys user:*");
        System.out.println("  EASY_DB_HOST=192.168.1.100 easy-db get name");
    }
}
