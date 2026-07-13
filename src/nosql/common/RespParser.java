package nosql.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RESP (REdis Serialization Protocol) 协议解析与格式化工具类
 */
public class RespParser {

    /**
     * 从输入流中读取一个 Redis 请求命令（通常是数组格式，例如 *3\r\n$3\r\nSET\r\n...）
     * 同时也支持极简的 Inline Command 格式（例如 SET name mayiqin\r\n）以防兼容 Telnet 或简化客户端。
     */
    public static List<String> readRequest(InputStream in) throws IOException {
        int firstByte = in.read();
        if (firstByte == -1) {
            throw new EOFException("Connection closed by peer.");
        }

        if (firstByte == '*') {
            // RESP 数组格式
            int arraySize = readIntegerLine(in);
            List<String> args = new ArrayList<>(arraySize);
            for (int i = 0; i < arraySize; i++) {
                int nextByte = in.read();
                if (nextByte != '$') {
                    throw new IOException("Expected '$' for Bulk String, but got: " + (char) nextByte);
                }
                int bulkLen = readIntegerLine(in);
                byte[] data = new byte[bulkLen];
                int read = 0;
                while (read < bulkLen) {
                    int chunk = in.read(data, read, bulkLen - read);
                    if (chunk == -1) {
                        throw new EOFException("Unexpected EOF while reading Bulk String data.");
                    }
                    read += chunk;
                }
                // 跳过末尾的 \r\n
                in.read(); // \r
                in.read(); // \n
                args.add(new String(data, StandardCharsets.UTF_8));
            }
            return args;
        } else {
            // 行内命令 (Inline Command) 兼容模式
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(firstByte);
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    break;
                }
                if (b != '\r') {
                    baos.write(b);
                }
            }
            String line = baos.toString(StandardCharsets.UTF_8).trim();
            if (line.isEmpty()) {
                return new ArrayList<>();
            }
            // 简单的以空白字符分割
            String[] parts = line.split("\\s+");
            List<String> args = new ArrayList<>();
            for (String part : parts) {
                args.add(part);
            }
            return args;
        }
    }

    private static int readIntegerLine(InputStream in) throws IOException {
        int val = 0;
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                in.read(); // 跳过 \n
                break;
            }
            if (b >= '0' && b <= '9') {
                val = val * 10 + (b - '0');
            }
        }
        return val;
    }

    // ==========================================
    // RESP 响应格式化输出方法
    // ==========================================

    public static void writeSimpleString(OutputStream out, String s) throws IOException {
        String resp = "+" + s + "\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static void writeError(OutputStream out, String errMsg) throws IOException {
        String resp = "-ERR " + errMsg + "\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static void writeInteger(OutputStream out, long val) throws IOException {
        String resp = ":" + val + "\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static void writeBulkString(OutputStream out, String s) throws IOException {
        if (s == null) {
            writeNullBulkString(out);
            return;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.write(('$' + String.valueOf(bytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static void writeNullBulkString(OutputStream out) throws IOException {
        out.write("$-1\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static void writeArray(OutputStream out, List<String> list) throws IOException {
        if (list == null) {
            out.write("*-1\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }
        out.write(('*' + String.valueOf(list.size()) + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String s : list) {
            if (s == null) {
                out.write("$-1\r\n".getBytes(StandardCharsets.UTF_8));
            } else {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                out.write(('$' + String.valueOf(bytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(bytes);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        out.flush();
    }
}
