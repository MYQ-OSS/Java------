package nosql.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RESP 协议解析器单元测试
 * 对应指导书测试用例 TC-01：单机 CRUD
 */
@DisplayName("RESP 协议解析器")
class RespParserTest {

    // ═══════════════════════════════════════════════════
    // readRequest — RESP 数组格式
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("解析标准 RESP 数组命令 (SET key value)")
    void testReadRequestArray() throws IOException {
        // *3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n
        byte[] data = "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(data);

        List<String> result = RespParser.readRequest(in);

        assertEquals(3, result.size());
        assertEquals("SET", result.get(0));
        assertEquals("key", result.get(1));
        assertEquals("value", result.get(2));
    }

    @Test
    @DisplayName("解析空命令 (空行)")
    void testReadRequestEmpty() throws IOException {
        byte[] data = "\r\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(data);

        List<String> result = RespParser.readRequest(in);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("解析客户端断开 (EOF)")
    void testReadRequestClientClose() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        assertThrows(EOFException.class, () -> RespParser.readRequest(in));
    }

    @Test
    @DisplayName("解析 Inline 命令 (兼容 Telnet 模式)")
    void testReadRequestInline() throws IOException {
        byte[] data = "PING\r\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(data);

        List<String> result = RespParser.readRequest(in);

        assertEquals(1, result.size());
        assertEquals("PING", result.get(0));
    }

    @Test
    @DisplayName("解析多单词 Inline 命令")
    void testReadRequestInlineMultiWord() throws IOException {
        byte[] data = "SET mykey myvalue\r\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(data);

        List<String> result = RespParser.readRequest(in);

        assertEquals(3, result.size());
        assertEquals("SET", result.get(0));
        assertEquals("mykey", result.get(1));
        assertEquals("myvalue", result.get(2));
    }

    // ═══════════════════════════════════════════════════
    // 响应输出方法
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("写入简单字符串响应 (+OK)")
    void testWriteSimpleString() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespParser.writeSimpleString(out, "OK");
        assertEquals("+OK\r\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("写入错误响应 (-ERR)")
    void testWriteError() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespParser.writeError(out, "unknown command");
        assertEquals("-ERR unknown command\r\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("写入整数响应 (:100)")
    void testWriteInteger() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespParser.writeInteger(out, 100);
        assertEquals(":100\r\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("写入批量字符串响应")
    void testWriteBulkString() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespParser.writeBulkString(out, "hello");
        assertEquals("$5\r\nhello\r\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("写入 null 批量字符串 ($-1)")
    void testWriteNullBulkString() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespParser.writeNullBulkString(out);
        assertEquals("$-1\r\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("写入数组响应 (包含 null 元素)")
    void testWriteArray() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespParser.writeArray(out, Arrays.asList("a", null, "c"));
        String result = out.toString(StandardCharsets.UTF_8);
        assertTrue(result.startsWith("*3\r\n"));
        assertTrue(result.contains("$1\r\na\r\n"));
        assertTrue(result.contains("$-1\r\n"));
        assertTrue(result.contains("$1\r\nc\r\n"));
    }

    @Test
    @DisplayName("写入 null 数组 (*-1)")
    void testWriteNullArray() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespParser.writeArray(out, null);
        assertEquals("*-1\r\n", out.toString(StandardCharsets.UTF_8));
    }
}
