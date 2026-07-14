package nosql.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SSTable 磁盘读写与压缩合并单元测试
 * 对应指导书 2.2.1.5(2) LSMT 存储结构
 */
@DisplayName("SSTable 磁盘存储")
class SSTableTest {

    @Test
    @DisplayName("flush: 将内存数据刷写为 SSTable 文件")
    void testFlushAndRead(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        HashIndex hashIndex = new HashIndex();

        // 准备内存数据
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key_a", "value_a");
        data.put("key_b", "value_b");
        data.put("key_c", "value_c");

        Engine engine = new Engine(dir, 10000);

        File sstFile = SSTable.flush(dir, data, engine, hashIndex);
        assertNotNull(sstFile);
        assertTrue(sstFile.exists());
        assertTrue(sstFile.length() > 0);
        assertTrue(sstFile.getName().endsWith(".sst"));

        // 验证索引
        assertEquals(3, hashIndex.size());

        // 验证读取
        assertEquals("value_a", SSTable.read(sstFile, "key_a"));
        assertEquals("value_b", SSTable.read(sstFile, "key_b"));
        assertEquals("value_c", SSTable.read(sstFile, "key_c"));
        assertNull(SSTable.read(sstFile, "key_not_exist"));
    }

    @Test
    @DisplayName("flush: 空数据返回 null")
    void testFlushEmpty(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        HashIndex hashIndex = new HashIndex();
        Engine engine = new Engine(dir, 10000);

        File result = SSTable.flush(dir, Collections.emptyMap(), engine, hashIndex);
        assertNull(result);
    }

    @Test
    @DisplayName("flush 支持 List 类型数据")
    void testFlushListType(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        HashIndex hashIndex = new HashIndex();
        Engine engine = new Engine(dir, 10000);

        ConcurrentLinkedDeque<String> deque = new ConcurrentLinkedDeque<>();
        deque.add("item1");
        deque.add("item2");

        Map<String, Object> data = new HashMap<>();
        data.put("mylist", deque);

        File sstFile = SSTable.flush(dir, data, engine, hashIndex);
        assertNotNull(sstFile);

        String value = SSTable.read(sstFile, "mylist");
        assertNotNull(value);
        assertTrue(value.contains("item1") && value.contains("item2"));
    }

    @Test
    @DisplayName("flush 支持 Set 类型数据")
    void testFlushSetType(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        HashIndex hashIndex = new HashIndex();
        Engine engine = new Engine(dir, 10000);

        Set<String> set = ConcurrentHashMap.newKeySet();
        set.add("tag1");
        set.add("tag2");

        Map<String, Object> data = new HashMap<>();
        data.put("myset", set);

        File sstFile = SSTable.flush(dir, data, engine, hashIndex);
        assertNotNull(sstFile);

        String value = SSTable.read(sstFile, "myset");
        assertNotNull(value);
        assertTrue(value.contains("tag1"));
    }

    @Test
    @DisplayName("flush 支持 Hash 类型数据")
    void testFlushHashType(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        HashIndex hashIndex = new HashIndex();
        Engine engine = new Engine(dir, 10000);

        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        map.put("name", "Alice");
        map.put("age", "20");

        Map<String, Object> data = new HashMap<>();
        data.put("myhash", map);

        File sstFile = SSTable.flush(dir, data, engine, hashIndex);
        assertNotNull(sstFile);

        String value = SSTable.read(sstFile, "myhash");
        assertNotNull(value);
        assertTrue(value.contains("name") && value.contains("Alice"));
    }

    @Test
    @DisplayName("compact: 压缩合并多个 SSTable，重复 key 取最新值")
    void testCompact(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        HashIndex hashIndex = new HashIndex();
        Engine engine = new Engine(dir, 10000);

        // 使用 LinkedHashMap 保证插入顺序
        Map<String, Object> data1 = new LinkedHashMap<>();
        data1.put("a", "1");
        data1.put("b", "2");
        File f1 = SSTable.flush(dir, data1, engine, hashIndex);
        assertNotNull(f1);
        assertEquals(2, SSTable.readAll(f1).size());

        Map<String, Object> data2 = new LinkedHashMap<>();
        data2.put("b", "updated");
        data2.put("c", "3");
        File f2 = SSTable.flush(dir, data2, engine, hashIndex);
        assertNotNull(f2);
        assertEquals(2, SSTable.readAll(f2).size());

        // 验证源文件独立可读
        assertEquals("1", SSTable.read(f1, "a"));
        assertEquals("2", SSTable.read(f1, "b"));
        assertEquals("updated", SSTable.read(f2, "b"));
        assertEquals("3", SSTable.read(f2, "c"));

        // 压缩合并（f1 先创建 → 文件排序靠前 → 先读 → f2 覆盖同 key）
        List<File> sources = new ArrayList<>();
        sources.add(f1);
        sources.add(f2);
        File compacted = SSTable.compact(dir, sources, hashIndex);
        assertNotNull(compacted);
        assertTrue(compacted.exists());

        // 压缩后源文件应被删除
        assertFalse(f1.exists());
        assertFalse(f2.exists());

        // 压缩文件应包含 3 个不同的 key
        List<SSTable.Entry> entries = SSTable.readAll(compacted);
        assertEquals(3, entries.size(), "Compacted file should contain 3 distinct keys");

        // 验证压缩后值正确（b 取 f2 的 updated）
        assertEquals("1", SSTable.read(compacted, "a"));
        assertEquals("updated", SSTable.read(compacted, "b"));
        assertEquals("3", SSTable.read(compacted, "c"));
    }

    @Test
    @DisplayName("listSSTables: 列出数据目录中所有 SSTable 文件")
    void testListSSTables(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        HashIndex hashIndex = new HashIndex();
        Engine engine = new Engine(dir, 10000);

        Map<String, Object> data = new HashMap<>();
        data.put("k", "v");
        SSTable.flush(dir, data, engine, hashIndex);

        List<File> files = SSTable.listSSTables(dir);
        assertEquals(1, files.size());
        assertTrue(files.get(0).getName().endsWith(".sst"));
    }

    @Test
    @DisplayName("read: 损坏的 SSTable 抛 IOException")
    void testReadCorruptedFile(@TempDir Path tempDir) throws Exception {
        File badFile = tempDir.resolve("bad.sst").toFile();
        java.nio.file.Files.write(badFile.toPath(), "not a valid sstable".getBytes());

        assertThrows(Exception.class, () -> SSTable.read(badFile, "any"));
    }
}
