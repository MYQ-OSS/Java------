package nosql.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Redis 兼容交互式 Shell 客户端（增强版）
 *
 * 特性：
 * - 命令历史记录（支持 history / !N 重放）
 * - ANSI 彩色输出
 * - 批量执行模式（--file 参数）
 * - 连接状态显示
 */
public class NoSqlCli {

    private static final String VERSION = "2.0.0";
    private static final List<String> history = new ArrayList<>();
    private static final int MAX_HISTORY = 1000;

    // ANSI 颜色
    private static final String RESET = "[0m";
    private static final String RED = "[31m";
    private static final String GREEN = "[32m";
    private static final String YELLOW = "[33m";
    private static final String CYAN = "[36m";
    private static final String BOLD = "[1m";

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 8080;
        String batchFile = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host": case "-h": host = args[++i]; break;
                case "--port": case "-p": port = Integer.parseInt(args[++i]); break;
                case "--file": case "-f": batchFile = args[++i]; break;
                case "--version": case "-v":
                    System.out.println("NoSQL Shell v" + VERSION);
                    return;
            }
        }

        printBanner(host, port);

        // 批量执行模式
        if (batchFile != null) {
            runBatchMode(host, port, batchFile);
            return;
        }

        // 交互模式
        runInteractiveMode(host, port);
    }

    private static void printBanner(String host, int port) {
        System.out.println(CYAN + "╔══════════════════════════════════════════════════╗" + RESET);
        System.out.println(CYAN + "║" + BOLD + "   NoSQL Redis-Compatible Interactive Shell v" + VERSION + "   " + RESET + CYAN + "║" + RESET);
        System.out.println(CYAN + "╠══════════════════════════════════════════════════╣" + RESET);
        System.out.println(CYAN + "║" + RESET + "  Connected to: " + GREEN + host + ":" + port + RESET + "                        " + CYAN + "║" + RESET);
        System.out.println(CYAN + "╚══════════════════════════════════════════════════╝" + RESET);
    }

    private static void printHelp() {
        System.out.println();
        System.out.println(BOLD + "支持的命令类别:" + RESET);
        System.out.println("  " + YELLOW + "String:" + RESET + "  SET, GET, DEL, EXISTS, INCR, DECR, MSET, MGET");
        System.out.println("  " + YELLOW + "Hash:" + RESET + "    HSET, HGET, HDEL, HGETALL");
        System.out.println("  " + YELLOW + "List:" + RESET + "    LPUSH, RPUSH, LPOP, RPOP, LRANGE");
        System.out.println("  " + YELLOW + "Set:" + RESET + "     SADD, SREM, SMEMBERS, SISMEMBER");
        System.out.println("  " + YELLOW + "TTL:" + RESET + "     EXPIRE, TTL");
        System.out.println("  " + YELLOW + "系统:" + RESET + "    PING, INFO, DBSIZE, KEYS, FLUSHALL, TYPE");
        System.out.println("  " + YELLOW + "集合:" + RESET + "    CREATE COLLECTION, DROP COLLECTION, LIST COLLECTIONS");
        System.out.println();
        System.out.println(BOLD + "Shell 内置命令:" + RESET);
        System.out.println("  " + GREEN + "help" + RESET + "     显示此帮助");
        System.out.println("  " + GREEN + "history" + RESET + "  显示命令历史");
        System.out.println("  " + GREEN + "!N" + RESET + "       重放历史中第 N 条命令");
        System.out.println("  " + GREEN + "clear" + RESET + "    清屏");
        System.out.println("  " + GREEN + "exit/quit" + RESET + " 退出");
        System.out.println("  " + GREEN + "about" + RESET + "    关于");
        System.out.println();
    }

    private static void runInteractiveMode(String host, int port) {
        System.out.println("输入 " + GREEN + "help" + RESET + " 查看可用命令，" + GREEN + "exit" + RESET + " 退出。");
        System.out.println();

        try (NoSqlSdk sdk = new NoSqlSdk(host, port);
             BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            System.out.println(GREEN + "✓ 已连接到 " + host + ":" + port + RESET);

            while (true) {
                System.out.print(BOLD + host + ":" + port + "> " + RESET);
                String line = reader.readLine();
                if (line == null) break;

                line = line.trim();
                if (line.isEmpty()) continue;

                // Shell 内置命令
                if (handleShellCommand(line, sdk)) continue;

                // 添加到历史
                addToHistory(line);

                try {
                    List<String> cmd = parseCommandLine(line);
                    if (cmd.isEmpty()) continue;

                    long start = System.nanoTime();
                    Object resp = sdk.sendCommand(cmd);
                    long elapsed = (System.nanoTime() - start) / 1_000_000; // ms

                    printFormattedResponse(resp);
                    if (elapsed > 10) {
                        System.out.println("(" + String.format("%.2f", elapsed / 1000.0) + "s)");
                    }
                } catch (Exception e) {
                    System.out.println(RED + "(error) " + e.getMessage() + RESET);
                }
            }

        } catch (Exception e) {
            System.err.println(RED + "连接失败: " + e.getMessage() + RESET);
        }
        System.out.println("\n再见！");
    }

    /**
     * 处理 Shell 内置命令，返回 true 表示已处理
     */
    private static boolean handleShellCommand(String line, NoSqlSdk sdk) {
        String cmd = line.toUpperCase();

        if ("HELP".equals(cmd) || "?".equals(line)) {
            printHelp();
            return true;
        }
        if ("CLEAR".equals(cmd) || "CLS".equals(cmd)) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
            return true;
        }
        if ("HISTORY".equals(cmd)) {
            printHistory();
            return true;
        }
        if ("ABOUT".equals(cmd)) {
            System.out.println("NoSQL Redis-Compatible Shell v" + VERSION);
            System.out.println("Java 21 | RESP Protocol | 支持单机/集群模式");
            return true;
        }
        // 重放历史命令 !N
        if (line.startsWith("!") && line.length() > 1) {
            try {
                int idx = Integer.parseInt(line.substring(1));
                if (idx >= 0 && idx < history.size()) {
                    String histCmd = history.get(idx);
                    System.out.println("  (replay) " + histCmd);
                    addToHistory(histCmd);
                    try {
                        List<String> parsed = parseCommandLine(histCmd);
                        Object resp = sdk.sendCommand(parsed);
                        printFormattedResponse(resp);
                    } catch (Exception e) {
                        System.out.println(RED + "(error) " + e.getMessage() + RESET);
                    }
                } else {
                    System.out.println(RED + "无效的历史索引: " + idx + RESET);
                }
            } catch (NumberFormatException e) {
                // 不是 !N 格式，继续作为普通命令处理
                return false;
            }
            return true;
        }
        return false;
    }

    private static void addToHistory(String cmd) {
        history.add(cmd);
        if (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    private static void printHistory() {
        if (history.isEmpty()) {
            System.out.println("(empty history)");
            return;
        }
        for (int i = 0; i < history.size(); i++) {
            System.out.printf("  %4d  %s%n", i, history.get(i));
        }
    }

    /**
     * 批量执行模式：从文件读取命令并逐行执行
     */
    private static void runBatchMode(String host, int port, String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            System.err.println(RED + "文件不存在: " + filePath + RESET);
            System.exit(1);
        }

        try (NoSqlSdk sdk = new NoSqlSdk(host, port);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {

            System.out.println(GREEN + "✓ 批量执行模式，从文件: " + filePath + RESET);
            String line;
            int total = 0, success = 0, failed = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                total++;
                try {
                    List<String> cmd = parseCommandLine(line);
                    Object resp = sdk.sendCommand(cmd);
                    success++;
                    System.out.print("  [" + GREEN + "OK" + RESET + "] " + line);
                    System.out.println(" → " + formatShort(resp));
                } catch (Exception e) {
                    failed++;
                    System.out.println("  [" + RED + "FAIL" + RESET + "] " + line + " → " + e.getMessage());
                }
            }

            System.out.println("\n" + BOLD + "执行完毕：" + total + " 条命令，" +
                    GREEN + success + " 成功" + RESET + "，" + RED + failed + " 失败" + RESET);

        } catch (Exception e) {
            System.err.println(RED + "批量执行错误: " + e.getMessage() + RESET);
        }
    }

    private static String formatShort(Object resp) {
        if (resp == null) return "(nil)";
        if (resp instanceof String) {
            String s = (String) resp;
            return s.length() > 40 ? "\"" + s.substring(0, 37) + "...\"" : "\"" + s + "\"";
        }
        if (resp instanceof Long || resp instanceof Integer) return resp.toString();
        if (resp instanceof List) return "(array, " + ((List<?>) resp).size() + " items)";
        return resp.toString();
    }

    /**
     * 简易命令行解析，支持双引号包裹含空格的参数
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

    @SuppressWarnings("unchecked")
    private static void printFormattedResponse(Object resp) {
        if (resp == null) {
            System.out.println("(nil)");
        } else if (resp instanceof String) {
            String str = (String) resp;
            if ("OK".equals(str) || "PONG".equals(str)) {
                System.out.println(GREEN + str + RESET);
            } else if (str.contains("\r\n")) {
                // INFO 等多行输出
                for (String part : str.split("\r\n")) {
                    System.out.println("  " + part);
                }
            } else {
                System.out.println("\"" + str + "\"");
            }
        } else if (resp instanceof Long) {
            System.out.println("(integer) " + resp);
        } else if (resp instanceof Integer) {
            System.out.println("(integer) " + resp);
        } else if (resp instanceof List) {
            List<Object> list = (List<Object>) resp;
            if (list.isEmpty()) {
                System.out.println("(empty array)");
            } else {
                for (int i = 0; i < list.size(); i++) {
                    System.out.printf("  %2d) %s%n", i + 1, formatValue(list.get(i)));
                }
            }
        } else {
            System.out.println(resp.toString());
        }
    }

    private static String formatValue(Object val) {
        if (val == null) return "(nil)";
        if (val instanceof String) return "\"" + val + "\"";
        if (val instanceof Long || val instanceof Integer) return "(integer) " + val;
        return val.toString();
    }
}
