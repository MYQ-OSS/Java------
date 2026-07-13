package nosql.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 兼容 Redis RESP 协议交互的高级命令行客户端 (Redis-Cli)
 */
public class NoSqlCli {

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 8080;

        for (int i = 0; i < args.length; i++) {
            if ("--host".equals(args[i])) {
                host = args[++i];
            } else if ("--port".equals(args[i])) {
                port = Integer.parseInt(args[++i]);
            }
        }

        System.out.println("=================================================");
        System.out.println(" Welcome to Redis-Compatible CLI Client (Java Edition)");
        System.out.println(" Connected to: " + host + ":" + port);
        System.out.println(" You can type standard Redis commands (e.g. SET, GET, LPUSH)");
        System.out.println(" Type 'exit' or 'quit' to exit.");
        System.out.println("=================================================");

        try (NoSqlSdk sdk = new NoSqlSdk(host, port);
             BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {

            while (true) {
                System.out.print(host + ":" + port + "> ");
                String line = reader.readLine();
                if (line == null) {
                    break;
                }

                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if ("exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) {
                    System.out.println("Goodbye.");
                    break;
                }

                try {
                    // 解析输入行为以空格分隔的命令参数
                    List<String> cmd = parseCommandLine(line);
                    if (cmd.isEmpty()) continue;

                    // 发送给服务器并获取 RESP 响应
                    Object resp = sdk.sendCommand(cmd);
                    printFormattedResponse(resp);
                } catch (Exception e) {
                    System.out.println("(error) " + e.getMessage());
                }
            }

        } catch (Exception e) {
            System.err.println("Connection Failed: " + e.getMessage());
        }
    }

    /**
     * 简易命令词解析，支持用双引号包裹含空格的参数
     */
    private static List<String> parseCommandLine(String line) {
        List<String> args = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (sb.length() > 0) {
                    args.add(sb.toString());
                    sb.setLength(0);
                }
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            args.add(sb.toString());
        }
        return args;
    }

    /**
     * 格式化输出 Redis 命令响应
     */
    @SuppressWarnings("unchecked")
    private static void printFormattedResponse(Object resp) {
        if (resp == null) {
            System.out.println("(nil)");
        } else if (resp instanceof String) {
            String str = (String) resp;
            if ("OK".equals(str) || "PONG".equals(str)) {
                System.out.println(str);
            } else {
                System.out.println("\"" + str + "\"");
            }
        } else if (resp instanceof Long) {
            System.out.println("(integer) " + resp);
        } else if (resp instanceof List) {
            List<Object> list = (List<Object>) resp;
            if (list.isEmpty()) {
                System.out.println("(empty array)");
            } else {
                for (int i = 0; i < list.size(); i++) {
                    System.out.print((i + 1) + ") ");
                    printNestedResponse(list.get(i));
                }
            }
        } else {
            System.out.println(resp.toString());
        }
    }

    private static void printNestedResponse(Object val) {
        if (val == null) {
            System.out.println("(nil)");
        } else if (val instanceof String) {
            System.out.println("\"" + val + "\"");
        } else if (val instanceof Long) {
            System.out.println("(integer) " + val);
        } else {
            System.out.println(val.toString());
        }
    }
}
