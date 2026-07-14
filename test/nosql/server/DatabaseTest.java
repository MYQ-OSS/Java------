package nosql.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Database 命令调度单元测试
 * 对应指导书 2.2.1.1 数据库正常功能和 TC-01：单机 CRUD
 */
@DisplayName("数据库命令调度 (Database)")
class DatabaseTest {

    private final List<Database> dbs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = dbs.size() - 1; i >= 0; i--) {
            try { dbs.get(i).close(); } catch (Exception ignored) {}
        }
        dbs.clear();
    }

    private Database open(Path tempDir) throws Exception {
        Database db = new Database(tempDir.toAbsolutePath().toString(), 10 * 1024 * 1024);
        dbs.add(db);
        return db;
    }

    @Test
    @DisplayName("SET / GET 命令")
    void testSetGet(@TempDir Path tempDir) throws Exception {
        Database db = open(tempDir);
        assertEquals("OK", db.execute(List.of("SET", "name", "Alice"), true));
        assertEquals("Alice", db.execute(List.of("GET", "name"), true));
    }

    @Test
    @DisplayName("DEL 命令")
    void testDel(@TempDir Path tempDir) throws Exception {
        Database db = open(tempDir);
        db.execute(List.of("SET", "a", "1"), true);
        db.execute(List.of("SET", "b", "2"), true);
        assertEquals(2L, db.execute(List.of("DEL", "a", "b"), true));
        assertNull(db.execute(List.of("GET", "a"), true));
    }

    @Test
    @DisplayName("PING 命令")
    void testPing(@TempDir Path tempDir) throws Exception {
        assertEquals("PONG", open(tempDir).execute(List.of("PING"), true));
    }

    @Test
    @DisplayName("INFO 命令返回监控信息")
    void testInfo(@TempDir Path tempDir) throws Exception {
        Database db = open(tempDir);
        db.execute(List.of("SET", "k", "v"), true);
        String info = (String) db.execute(List.of("INFO"), true);
        assertTrue(info.contains("used_memory"));
        assertTrue(info.contains("role"));
        assertTrue(info.contains("db_keys"));
    }

    @Test
    @DisplayName("DBSIZE 命令")
    void testDbSizeCommand(@TempDir Path tempDir) throws Exception {
        Database db = open(tempDir);
        assertEquals(0L, db.execute(List.of("DBSIZE"), true));
        db.execute(List.of("SET", "a", "1"), true);
        db.execute(List.of("SET", "b", "2"), true);
        assertEquals(2L, db.execute(List.of("DBSIZE"), true));
    }

    @Test
    @DisplayName("KEYS 命令")
    void testKeysCommand(@TempDir Path tempDir) throws Exception {
        Database db = open(tempDir);
        db.execute(List.of("SET", "x", "1"), true);
        db.execute(List.of("SET", "y", "2"), true);
        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) db.execute(List.of("KEYS"), true);
        assertEquals(2, keys.size());
    }

    @Test
    @DisplayName("HSET / HGET / HGETALL 命令")
    void testHashCommands(@TempDir Path tempDir) throws Exception {
        Database db = open(tempDir);
        assertEquals(1L, db.execute(List.of("HSET", "user", "name", "Alice"), true));
        assertEquals("Alice", db.execute(List.of("HGET", "user", "name"), true));
        @SuppressWarnings("unchecked")
        List<String> r = (List<String>) db.execute(List.of("HGETALL", "user"), true);
        assertEquals(2, r.size());
    }

    @Test
    @DisplayName("LPUSH / RPUSH / LRANGE 命令")
    void testListCommands(@TempDir Path tempDir) throws Exception {
        Database db = open(tempDir);
        db.execute(List.of("RPUSH", "queue", "a"), true);
        db.execute(List.of("RPUSH", "queue", "b"), true);
        @SuppressWarnings("unchecked")
        List<String> range = (List<String>) db.execute(List.of("LRANGE", "queue", "0", "-1"), true);
        assertEquals(2, range.size());
    }

    @Test
    @DisplayName("SADD / SMEMBERS / SISMEMBER 命令")
    void testSetCommands(@TempDir Path tempDir) throws Exception {
        Database db = open(tempDir);
        db.execute(List.of("SADD", "tags", "java"), true);
        assertEquals(1L, db.execute(List.of("SISMEMBER", "tags", "java"), true));
        assertEquals(0L, db.execute(List.of("SISMEMBER", "tags", "python"), true));
    }

    @Test
    @DisplayName("MSET / MGET 批量命令")
    void testBulkCommands(@TempDir Path tempDir) throws Exception {
        Database db = open(tempDir);
        assertEquals(3L, db.execute(List.of("MSET", "a", "1", "b", "2", "c", "3"), true));
        @SuppressWarnings("unchecked")
        List<String> vals = (List<String>) db.execute(List.of("MGET", "a", "b", "d"), true);
        assertEquals("1", vals.get(0));
        assertEquals("2", vals.get(1));
        assertNull(vals.get(2));
    }

    @Test
    @DisplayName("INCR / DECR 命令")
    void testIncrDecrCommands(@TempDir Path tempDir) throws Exception {
        Database db = open(tempDir);
        assertEquals(1L, db.execute(List.of("INCR", "c"), true));
        assertEquals(2L, db.execute(List.of("INCR", "c"), true));
    }

    @Test
    @DisplayName("EXPIRE / TTL / EXISTS 命令")
    void testExpireTtlCommands(@TempDir Path tempDir) throws Exception {
        Database db = open(tempDir);
        db.execute(List.of("SET", "s", "t"), true);
        assertEquals(1L, db.execute(List.of("EXPIRE", "s", "3600"), true));
        long ttl = (Long) db.execute(List.of("TTL", "s"), true);
        assertTrue(ttl > 0);
        assertEquals(1L, db.execute(List.of("EXISTS", "s"), true));
    }

    @Test
    @DisplayName("CREATE / DROP / LIST COLLECTION 命令")
    void testCollectionCommands(@TempDir Path tempDir) throws Exception {
        Database db = open(tempDir);
        assertEquals("OK", db.execute(List.of("CREATE", "COLLECTION", "users"), true));
        @SuppressWarnings("unchecked")
        List<String> colls = (List<String>) db.execute(List.of("LIST", "COLLECTIONS"), true);
        assertTrue(colls.contains("users"));
    }

    @Test
    @DisplayName("FLUSHALL 清空命令")
    void testFlushAll(@TempDir Path tempDir) throws Exception {
        Database db = open(tempDir);
        db.execute(List.of("SET", "a", "1"), true);
        assertEquals("OK", db.execute(List.of("FLUSHALL"), true));
        assertEquals(0L, db.execute(List.of("DBSIZE"), true));
    }

    @Test
    @DisplayName("TYPE 命令")
    void testTypeCommand(@TempDir Path tempDir) throws Exception {
        Database db = open(tempDir);
        db.execute(List.of("SET", "s", "hello"), true);
        db.execute(List.of("RPUSH", "l", "item"), true);
        assertEquals("string", db.execute(List.of("TYPE", "s"), true));
        assertEquals("list", db.execute(List.of("TYPE", "l"), true));
    }

    @Test
    @DisplayName("未知命令和参数不足返回错误")
    void testErrorHandling(@TempDir Path tempDir) throws Exception {
        Database db = open(tempDir);
        assertTrue(db.execute(List.of("FOOBAR"), true) instanceof Database.ErrorResult);
        assertTrue(db.execute(List.of("SET", "alone"), true) instanceof Database.ErrorResult);
    }

    @Test
    @DisplayName("重启恢复：数据从 AOF 正确恢复 (对应 TC-03)")
    void testRestartRecovery(@TempDir Path tempDir) throws Exception {
        String dbPath = tempDir.toAbsolutePath().toString();

        // 第一次：写入
        Database db1 = new Database(dbPath, 10 * 1024 * 1024);
        db1.execute(List.of("SET", "persist", "survive"), true);
        db1.execute(List.of("HSET", "hash", "f1", "v1"), true);
        db1.execute(List.of("RPUSH", "list", "item"), true);
        db1.close();

        // 第二次：恢复
        Database db2 = new Database(dbPath, 10 * 1024 * 1024);
        try {
            assertEquals("survive", db2.execute(List.of("GET", "persist"), true));
            assertEquals("v1", db2.execute(List.of("HGET", "hash", "f1"), true));
        } finally {
            db2.close();
        }
    }

    @Test
    @DisplayName("getEngine 返回非 null")
    void testGetEngine(@TempDir Path tempDir) throws Exception {
        assertNotNull(open(tempDir).getEngine());
    }
}
