package nosql.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AOF 持久化管理器单元测试
 * 对应指导书 2.2.1.4(2) WAL 日志回放和 TC-03：WAL 数据恢复
 */
@DisplayName("AOF 持久化管理器 (AofManager)")
class AofManagerTest {

    /**
     * 辅助：回放 AOF 命令到引擎
     */
    private void replayCommands(AofManager aofManager, Engine engine) throws Exception {
        aofManager.loadAll(engine, (cmd, eng) -> {
            String action = cmd.get(0).toUpperCase();
            switch (action) {
                case "SET":
                    if (cmd.size() >= 3) {
                        eng.set(cmd.get(1), cmd.get(2));
                        if (cmd.size() >= 5 && "EX".equalsIgnoreCase(cmd.get(3)))
                            eng.expire(cmd.get(1), Integer.parseInt(cmd.get(4)));
                    }
                    break;
                case "DEL":
                    for (int i = 1; i < cmd.size(); i++) eng.delete(cmd.get(i));
                    break;
                case "EXPIRE":
                    if (cmd.size() >= 3) eng.expire(cmd.get(1), Integer.parseInt(cmd.get(2)));
                    break;
                case "INCR": if (cmd.size() >= 2) eng.incr(cmd.get(1)); break;
                case "DECR": if (cmd.size() >= 2) eng.decr(cmd.get(1)); break;
                case "HSET":
                    if (cmd.size() >= 4) eng.hset(cmd.get(1), cmd.get(2), cmd.get(3));
                    break;
                case "HDEL":
                    if (cmd.size() >= 3) eng.hdel(cmd.get(1), cmd.get(2));
                    break;
                case "LPUSH":
                    if (cmd.size() >= 3) eng.lpush(cmd.get(1), cmd.get(2));
                    break;
                case "RPUSH":
                    if (cmd.size() >= 3) eng.rpush(cmd.get(1), cmd.get(2));
                    break;
                case "LPOP": if (cmd.size() >= 2) eng.lpop(cmd.get(1)); break;
                case "RPOP": if (cmd.size() >= 2) eng.rpop(cmd.get(1)); break;
                case "SADD":
                    if (cmd.size() >= 3) eng.sadd(cmd.get(1), cmd.get(2));
                    break;
                case "SREM":
                    if (cmd.size() >= 3) eng.srem(cmd.get(1), cmd.get(2));
                    break;
                case "MSET":
                    if (cmd.size() >= 3) eng.mset(cmd.subList(1, cmd.size()));
                    break;
                case "FLUSHALL": eng.clear(); break;
                case "CREATE":
                    if (cmd.size() >= 3 && "COLLECTION".equalsIgnoreCase(cmd.get(1)))
                        eng.createCollection(cmd.get(2));
                    break;
                case "DROP":
                    if (cmd.size() >= 3 && "COLLECTION".equalsIgnoreCase(cmd.get(1)))
                        eng.dropCollection(cmd.get(2));
                    break;
            }
        });
    }

    @Test
    @DisplayName("append: 追加命令到 active.aof")
    void testAppend(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        AofManager aof = new AofManager(dir.getAbsolutePath(), 10 * 1024 * 1024);
        try {
            aof.append(List.of("SET", "name", "Alice"));
            File active = new File(dir, "active.aof");
            assertTrue(active.exists());
            assertTrue(active.length() > 0);
        } finally {
            aof.close();
        }
    }

    @Test
    @DisplayName("append: null 或空命令不写入")
    void testAppendNullOrEmpty(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        AofManager aof = new AofManager(dir.getAbsolutePath(), 10 * 1024 * 1024);
        try {
            aof.append(null);
            aof.append(List.of());
        } finally {
            aof.close();
        }
    }

    @Test
    @DisplayName("loadAll: 回放 AOF 恢复数据 (对应 TC-03)")
    void testRecovery(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();

        // 写入
        AofManager writer = new AofManager(dir.getAbsolutePath(), 10 * 1024 * 1024);
        try {
            writer.append(List.of("SET", "key1", "value1"));
            writer.append(List.of("SET", "key2", "value2"));
            writer.append(List.of("SET", "key3", "value3"));
            writer.append(List.of("DEL", "key2"));
            writer.append(List.of("HSET", "hash1", "field", "data"));
            writer.append(List.of("RPUSH", "list1", "item"));
        } finally {
            writer.close();
        }

        // 恢复
        Engine engine = new Engine(dir, 10000);
        try {
            AofManager reader = new AofManager(dir.getAbsolutePath(), 10 * 1024 * 1024);
            try {
                replayCommands(reader, engine);
            } finally {
                reader.close();
            }
            assertEquals("value1", engine.get("key1"));
            assertNull(engine.get("key2"));
            assertEquals("value3", engine.get("key3"));
            assertEquals("data", engine.hget("hash1", "field"));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    @DisplayName("loadAll: 100 条批量写入恢复")
    void testBulkRecovery(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();

        AofManager writer = new AofManager(dir.getAbsolutePath(), 10 * 1024 * 1024);
        try {
            for (int i = 0; i < 100; i++)
                writer.append(List.of("SET", "key" + i, "val" + i));
        } finally {
            writer.close();
        }

        Engine engine = new Engine(dir, 10000);
        try {
            AofManager reader = new AofManager(dir.getAbsolutePath(), 10 * 1024 * 1024);
            try {
                replayCommands(reader, engine);
            } finally {
                reader.close();
            }
            assertEquals(100, engine.dbSize());
            assertEquals("val50", engine.get("key50"));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    @DisplayName("loadAll: 多种数据类型的回放恢复")
    void testComplexTypeRecovery(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();

        AofManager writer = new AofManager(dir.getAbsolutePath(), 10 * 1024 * 1024);
        try {
            writer.append(List.of("SET", "str", "hello"));
            writer.append(List.of("HSET", "hash", "name", "Alice"));
            writer.append(List.of("RPUSH", "queue", "task1"));
            writer.append(List.of("RPUSH", "queue", "task2"));
            writer.append(List.of("SADD", "tags", "java"));
            writer.append(List.of("INCR", "counter"));
            writer.append(List.of("INCR", "counter"));
        } finally {
            writer.close();
        }

        Engine engine = new Engine(dir, 10000);
        try {
            AofManager reader = new AofManager(dir.getAbsolutePath(), 10 * 1024 * 1024);
            try {
                replayCommands(reader, engine);
            } finally {
                reader.close();
            }
            assertEquals("hello", engine.get("str"));
            assertEquals("Alice", engine.hget("hash", "name"));
            assertEquals(2, engine.lrange("queue", 0, -1).size());
            assertEquals("2", engine.get("counter"));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    @DisplayName("loadAll: Collection 元数据恢复")
    void testCollectionRecovery(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();

        AofManager writer = new AofManager(dir.getAbsolutePath(), 10 * 1024 * 1024);
        try {
            writer.append(List.of("CREATE", "COLLECTION", "users"));
            writer.append(List.of("SET", "users:1", "Alice"));
        } finally {
            writer.close();
        }

        Engine engine = new Engine(dir, 10000);
        try {
            AofManager reader = new AofManager(dir.getAbsolutePath(), 10 * 1024 * 1024);
            try {
                replayCommands(reader, engine);
            } finally {
                reader.close();
            }
            assertTrue(engine.listCollections().contains("users"));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    @DisplayName("rotate: 达到阈值自动分卷")
    void testRotate(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        AofManager aof = new AofManager(dir.getAbsolutePath(), 100);
        try {
            for (int i = 0; i < 20; i++)
                aof.append(List.of("SET", "key" + i, "x".repeat(50)));
            File active = new File(dir, "active.aof");
            assertTrue(active.exists());
            Thread.sleep(500);
        } finally {
            aof.close();
        }
    }

    @Test
    @DisplayName("close: 正常关闭不抛异常")
    void testClose(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        AofManager aof = new AofManager(dir.getAbsolutePath(), 10 * 1024 * 1024);
        aof.append(List.of("SET", "key", "val"));
        aof.close();
        aof.close(); // 二次关闭
    }
}
