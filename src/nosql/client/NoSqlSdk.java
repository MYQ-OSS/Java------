package nosql.client;

import nosql.common.RespParser;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 兼容 Redis RESP 协议规范的简易客户端 Java SDK
 */
public class NoSqlSdk implements AutoCloseable {

    private final String host;
    private final int port;
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    public NoSqlSdk(String host, int port) throws IOException {
        this.host = host;
        this.port = port;
        connect();
    }

    private void connect() throws IOException {
        this.socket = new Socket(host, port);
        this.socket.setSoTimeout(10000); // 10秒超时
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /**
     * 发送 RESP 命令并读取响应的通用方法，保证线程安全
     */
    public synchronized Object sendCommand(List<String> cmd) throws Exception {
        if (socket == null || socket.isClosed()) {
            connect();
        }

        try {
            // 将命令按照 RESP Array 格式发送
            RespParser.writeArray(out, cmd);
            return readResponse();
        } catch (IOException e) {
            // 断线重连
            System.err.println("[SDK] Reconnecting to Redis Server...");
            try {
                close();
                connect();
                RespParser.writeArray(out, cmd);
                return readResponse();
            } catch (Exception re) {
                throw new Exception("Connection lost: " + re.getMessage());
            }
        }
    }

    /**
     * 解析服务端返回的 RESP 数据结构
     */
    private Object readResponse() throws Exception {
        int b = in.read();
        if (b == -1) {
            throw new EOFException("Server closed connection prematurely.");
        }

        char type = (char) b;
        if (type == '+') {
            return readLine();
        } else if (type == '-') {
            throw new Exception(readLine()); // 抛出服务端 Error 响应
        } else if (type == ':') {
            return Long.parseLong(readLine());
        } else if (type == '$') {
            int len = Integer.parseInt(readLine());
            if (len == -1) {
                return null;
            }
            byte[] data = new byte[len];
            int read = 0;
            while (read < len) {
                int chunk = in.read(data, read, len - read);
                if (chunk == -1) {
                    throw new EOFException("Unexpected EOF during Bulk String read.");
                }
                read += chunk;
            }
            in.read(); // 跳过 \r
            in.read(); // 跳过 \n
            return new String(data, StandardCharsets.UTF_8);
        } else if (type == '*') {
            int size = Integer.parseInt(readLine());
            if (size == -1) {
                return null;
            }
            List<Object> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(readResponse());
            }
            return list;
        } else {
            throw new IOException("Protocol Error: Unknown RESP type prefix: " + type);
        }
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                in.read(); // 跳过 \n
                break;
            }
            baos.write(b);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    // ==========================================
    // 兼容原有接口的方法（内部转换为 Redis 命令）
    // ==========================================

    public void put(String collection, String key, nosql.common.DbValue value) throws Exception {
        // collection 对应 Redis 中以冒号 ":" 拼接的 Key 命名空间规则（例如 coll:key）
        String realKey = collection + ":" + key;
        List<String> cmd = new ArrayList<>();
        cmd.add("SET");
        cmd.add(realKey);
        cmd.add(value.getValue().toString());
        
        // 传递类型参数
        sendCommand(cmd);
    }

    public nosql.common.DbValue get(String collection, String key) throws Exception {
        String realKey = collection + ":" + key;
        List<String> cmd = List.of("GET", realKey);
        
        String res = (String) sendCommand(cmd);
        if (res == null) {
            return null;
        }
        
        // 由于 GUI/CLI 原来读取的是 DbValue，这里进行简易包装后返回
        return new nosql.common.DbValue(nosql.common.DbValue.Type.STRING, res);
    }

    public void delete(String collection, String key) throws Exception {
        String realKey = collection + ":" + key;
        List<String> cmd = List.of("DEL", realKey);
        sendCommand(cmd);
    }

    @Override
    public synchronized void close() throws IOException {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
            socket = null;
        }
        in = null;
        out = null;
    }
}
