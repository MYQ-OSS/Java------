package nosql.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 存储引擎单元测试 (Engine CRUD + TTL + 数据类型)
 * 对应指导书 2.2.1.1 数据库 CRUD 功能和 2.2.1.5(1) 多种 Value 类型
 * 对应测试用例 TC-01：单机 CRUD
 */
@DisplayName("存储引擎 (Engine)")
class EngineTest {

    private final List<Engine> engines = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Engine e : engines) {
            e.shutdown();
        }
        engines.clear();
    }

    private Engine createEngine(Path tempDir) {
        Engine e = new Engine(tempDir.toFile(), 10000); // 大阈值避免自动刷写
        engines.add(e);
        return e;
    }

    // ═══════════════════════════════════
    // String 类型 CRUD
    // ═══════════════════════════════════

    @Test
    @DisplayName("SET / GET 基本读写")
    void testSetAndGet(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        engine.set("name", "Alice");
        assertEquals("Alice", engine.get("name"));
    }

    @Test
    @DisplayName("GET 不存在的 key 返回 null")
    void testGetNonExistent(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        assertNull(engine.get("no_such_key"));
    }

    @Test
    @DisplayName("SET 覆盖已有 key")
    void testSetOverwrite(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        engine.set("count", "1");
        engine.set("count", "2");
        assertEquals("2", engine.get("count"));
    }

    @Test
    @DisplayName("DELETE 删除 key")
    void testDelete(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        engine.set("temp", "value");
        assertTrue(engine.exists("temp"));

        engine.delete("temp");
        assertFalse(engine.exists("temp"));
        assertNull(engine.get("temp"));
    }

    @Test
    @DisplayName("EXISTS 检查 key 存在性")
    void testExists(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        assertFalse(engine.exists("x"));
        engine.set("x", "1");
        assertTrue(engine.exists("x"));
    }

    @Test
    @DisplayName("INCR / DECR 数值增减")
    void testIncrDecr(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        assertEquals(1, engine.incr("counter"));
        assertEquals(2, engine.incr("counter"));
        assertEquals(1, engine.decr("counter"));
        assertEquals(0, engine.decr("counter"));
    }

    @Test
    @DisplayName("INCR 对非数值 key 抛异常")
    void testIncrOnNonNumeric(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        engine.set("name", "hello");
        // Engine.incr 先检查类型(String)再 Long.parseLong，
        // "hello" 是 String 所以通过类型检查，但 parseLong 失败 → NumberFormatException
        assertThrows(NumberFormatException.class, () -> engine.incr("name"));
    }

    // ═══════════════════════════════════
    // TTL 过期
    // ═══════════════════════════════════

    @Test
    @DisplayName("EXPIRE 设置 key 过期")
    void testExpire(@TempDir Path tempDir) throws InterruptedException {
        Engine engine = createEngine(tempDir);
        engine.set("session", "token");
        assertTrue(engine.expire("session", 1)); // 1 秒过期

        // 立即检查应还存在
        assertTrue(engine.exists("session"));

        // 等待过期
        Thread.sleep(1100);
        assertFalse(engine.exists("session"));
        assertNull(engine.get("session"));
    }

    @Test
    @DisplayName("TTL 查询剩余存活时间")
    void testTtl(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        engine.set("key", "val");
        assertEquals(-1, engine.ttl("key")); // 无过期 = -1

        engine.expire("key", 100);
        long ttl = engine.ttl("key");
        assertTrue(ttl > 0 && ttl <= 100);

        // 不存在 key = -2
        assertEquals(-2, engine.ttl("nonexistent"));
    }

    // ═══════════════════════════════════
    // 批量操作 MSET / MGET
    // ═══════════════════════════════════

    @Test
    @DisplayName("MSET 批量写入")
    void testMset(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        int count = engine.mset(List.of("a", "1", "b", "2", "c", "3"));
        assertEquals(3, count);
        assertEquals("1", engine.get("a"));
        assertEquals("2", engine.get("b"));
        assertEquals("3", engine.get("c"));
    }

    @Test
    @DisplayName("MGET 批量读取")
    void testMget(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        engine.set("a", "1");
        engine.set("b", "2");

        List<String> results = engine.mget(List.of("a", "b", "c"));
        assertEquals(3, results.size());
        assertEquals("1", results.get(0));
        assertEquals("2", results.get(1));
        assertNull(results.get(2)); // 不存在的 key
    }

    // ═══════════════════════════════════
    // Hash 类型
    // ═══════════════════════════════════

    @Test
    @DisplayName("HSET / HGET Hash 字段读写")
    void testHsetHget(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        assertEquals(1, engine.hset("user:1", "name", "Alice"));
        assertEquals(0, engine.hset("user:1", "name", "Bob")); // 覆盖

        assertEquals("Bob", engine.hget("user:1", "name"));
        assertNull(engine.hget("user:1", "age"));
    }

    @Test
    @DisplayName("HDEL 删除 Hash 字段")
    void testHdel(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        engine.hset("user:1", "name", "Alice");
        engine.hset("user:1", "age", "20");

        assertEquals(1, engine.hdel("user:1", "name"));
        assertEquals(0, engine.hdel("user:1", "name")); // 已删除
        assertNotNull(engine.hget("user:1", "age"));
    }

    @Test
    @DisplayName("HGETALL 获取全部字段")
    void testHgetall(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        engine.hset("config", "host", "localhost");
        engine.hset("config", "port", "8080");

        Map<String, String> all = engine.hgetall("config");
        assertEquals(2, all.size());
        assertEquals("localhost", all.get("host"));
        assertEquals("8080", all.get("port"));
    }

    // ═══════════════════════════════════
    // List 类型
    // ═══════════════════════════════════

    @Test
    @DisplayName("LPUSH / RPUSH / LPOP / RPOP List 操作")
    void testListOps(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        assertEquals(1, engine.rpush("queue", "a"));
        assertEquals(2, engine.rpush("queue", "b"));
        assertEquals(3, engine.lpush("queue", "c")); // 头插

        // 顺序应为: c, a, b
        List<String> range = engine.lrange("queue", 0, -1);
        assertEquals(3, range.size());
        assertEquals("c", range.get(0));
        assertEquals("a", range.get(1));
        assertEquals("b", range.get(2));

        assertEquals("c", engine.lpop("queue"));
        assertEquals("b", engine.rpop("queue"));
        assertEquals(1, engine.lrange("queue", 0, -1).size());
    }

    @Test
    @DisplayName("LRANGE 支持负索引")
    void testLrangeNegativeIndex(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        engine.rpush("list", "0");
        engine.rpush("list", "1");
        engine.rpush("list", "2");

        List<String> result = engine.lrange("list", -2, -1);
        assertEquals(2, result.size());
        assertEquals("1", result.get(0));
        assertEquals("2", result.get(1));
    }

    // ═══════════════════════════════════
    // Set 类型
    // ═══════════════════════════════════

    @Test
    @DisplayName("SADD / SREM / SMEMBERS / SISMEMBER Set 操作")
    void testSetOps(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        assertEquals(1, engine.sadd("tags", "java"));
        assertEquals(1, engine.sadd("tags", "db"));
        assertEquals(0, engine.sadd("tags", "java")); // 重复不计数

        Set<String> members = engine.smembers("tags");
        assertEquals(2, members.size());
        assertTrue(members.contains("java"));

        assertEquals(1, engine.sismember("tags", "java"));
        assertEquals(0, engine.sismember("tags", "python"));

        assertEquals(1, engine.srem("tags", "java"));
        assertEquals(0, engine.srem("tags", "java")); // 已删除
    }

    // ═══════════════════════════════════
    // Collection 管理
    // ═══════════════════════════════════

    @Test
    @DisplayName("CREATE / DROP / LIST COLLECTION")
    void testCollectionOps(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);

        assertEquals("OK", engine.createCollection("users"));
        assertEquals("OK", engine.createCollection("orders"));

        Set<String> colls = engine.listCollections();
        assertTrue(colls.contains("users"));
        assertTrue(colls.contains("orders"));

        // 使用 collection:key 模式写入数据
        engine.set("users:1001", "Alice");
        // 通过 key 前缀自动识别 collection
        colls = engine.listCollections();
        assertTrue(colls.contains("users"));

        // DROP collection 删除前缀匹配的所有 key
        engine.dropCollection("users");
        assertNull(engine.get("users:1001"));
    }

    // ═══════════════════════════════════
    // 系统命令
    // ═══════════════════════════════════

    @Test
    @DisplayName("DBSIZE 返回 key 总数")
    void testDbSize(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        assertEquals(0, engine.dbSize());
        engine.set("a", "1");
        engine.set("b", "2");
        assertEquals(2, engine.dbSize());
    }

    @Test
    @DisplayName("KEYS 列出所有 key")
    void testKeys(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        engine.set("a", "1");
        engine.set("b", "2");
        engine.set("c", "3");

        Set<String> keys = engine.keys();
        assertEquals(3, keys.size());
        assertTrue(keys.containsAll(Set.of("a", "b", "c")));
    }

    @Test
    @DisplayName("TYPE 返回值类型")
    void testType(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        engine.set("str", "hello");
        engine.rpush("list", "item");
        engine.sadd("set", "member");
        engine.hset("hash", "field", "value");

        assertEquals("string", engine.type("str"));
        assertEquals("list", engine.type("list"));
        assertEquals("set", engine.type("set"));
        assertEquals("hash", engine.type("hash"));
        assertEquals("none", engine.type("nonexistent"));
    }

    @Test
    @DisplayName("FLUSHALL 清空所有数据")
    void testFlushAll(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        engine.set("a", "1");
        engine.set("b", "2");
        engine.createCollection("test");

        engine.clear();
        assertEquals(0, engine.dbSize());
        assertNull(engine.get("a"));
    }

    // ═══════════════════════════════════
    // 类型安全
    // ═══════════════════════════════════

    @Test
    @DisplayName("对 List 类型的 key 执行 GET 抛 WRONGTYPE 异常")
    void testWrongTypeOnGet(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        engine.rpush("mylist", "item");
        assertThrows(ClassCastException.class, () -> engine.get("mylist"));
    }

    @Test
    @DisplayName("对 String 类型的 key 执行 LPUSH 抛 WRONGTYPE 异常")
    void testWrongTypeOnLpush(@TempDir Path tempDir) {
        Engine engine = createEngine(tempDir);
        engine.set("mystr", "hello");
        assertThrows(ClassCastException.class, () -> engine.lpush("mystr", "item"));
    }
}
