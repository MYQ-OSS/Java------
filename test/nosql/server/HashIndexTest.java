package nosql.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 哈希索引单元测试
 * 对应指导书 2.2.1.3(1) 索引加速查询
 */
@DisplayName("哈希索引 (HashIndex)")
class HashIndexTest {

    @Test
    @DisplayName("基本 put / get 操作")
    void testPutAndGet() {
        HashIndex index = new HashIndex();
        index.put("user:1001", "sstable_100.sst", 0L);
        index.put("user:1002", "sstable_100.sst", 128L);

        HashIndex.IndexEntry entry = index.get("user:1001");
        assertNotNull(entry);
        assertEquals("sstable_100.sst", entry.sstableFileName);
        assertEquals(0L, entry.offset);

        entry = index.get("user:1002");
        assertNotNull(entry);
        assertEquals(128L, entry.offset);
    }

    @Test
    @DisplayName("查询不存在的 key 返回 null")
    void testGetNonExistent() {
        HashIndex index = new HashIndex();
        assertNull(index.get("nonexistent"));
    }

    @Test
    @DisplayName("remove 删除索引条目")
    void testRemove() {
        HashIndex index = new HashIndex();
        index.put("key1", "test.sst", 100L);
        assertEquals(1, index.size());

        index.remove("key1");
        assertEquals(0, index.size());
        assertNull(index.get("key1"));
    }

    @Test
    @DisplayName("containsKey 检查存在性")
    void testContainsKey() {
        HashIndex index = new HashIndex();
        index.put("key1", "test.sst", 0L);

        assertTrue(index.containsKey("key1"));
        assertFalse(index.containsKey("key2"));
    }

    @Test
    @DisplayName("批量 putAll 操作")
    void testPutAll() {
        HashIndex index = new HashIndex();
        java.util.Map<String, HashIndex.IndexEntry> entries = new java.util.HashMap<>();
        entries.put("a", new HashIndex.IndexEntry("f1.sst", 0L));
        entries.put("b", new HashIndex.IndexEntry("f1.sst", 64L));
        entries.put("c", new HashIndex.IndexEntry("f1.sst", 128L));

        index.putAll(entries);
        assertEquals(3, index.size());
    }

    @Test
    @DisplayName("getAllKeys 获取所有索引键")
    void testGetAllKeys() {
        HashIndex index = new HashIndex();
        index.put("a", "f.sst", 0L);
        index.put("b", "f.sst", 1L);
        index.put("c", "f.sst", 2L);

        var keys = index.getAllKeys();
        assertEquals(3, keys.size());
        assertTrue(keys.contains("a"));
        assertTrue(keys.contains("b"));
        assertTrue(keys.contains("c"));
    }

    @Test
    @DisplayName("clear 清空所有索引")
    void testClear() {
        HashIndex index = new HashIndex();
        index.put("key", "f.sst", 0L);
        index.clear();
        assertEquals(0, index.size());
        assertNull(index.get("key"));
    }

    @Test
    @DisplayName("removeBySSTable 批量删除指定 SSTable 的条目")
    void testRemoveBySSTable() {
        HashIndex index = new HashIndex();
        index.put("a", "old.sst", 0L);
        index.put("b", "old.sst", 64L);
        index.put("c", "new.sst", 0L);

        index.removeBySSTable("old.sst");
        assertEquals(1, index.size());
        assertNull(index.get("a"));
        assertNull(index.get("b"));
        assertNotNull(index.get("c"));
    }

    @Test
    @DisplayName("持久化保存并加载恢复 (@TempDir)")
    void testSaveAndLoad(@TempDir Path tempDir) throws Exception {
        HashIndex index = new HashIndex();
        index.put("user:1", "sst_1.sst", 100L);
        index.put("user:2", "sst_1.sst", 256L);
        index.put("中文键", "sst_2.sst", 512L);

        File idxFile = tempDir.resolve("hash_index.idx").toFile();
        index.saveToFile(idxFile);
        assertTrue(idxFile.exists());
        assertTrue(idxFile.length() > 0);

        // 恢复到新索引
        HashIndex restored = new HashIndex();
        restored.loadFromFile(idxFile);
        assertEquals(3, restored.size());

        HashIndex.IndexEntry entry = restored.get("user:1");
        assertNotNull(entry);
        assertEquals("sst_1.sst", entry.sstableFileName);
        assertEquals(100L, entry.offset);

        entry = restored.get("中文键");
        assertNotNull(entry);
        assertEquals(512L, entry.offset);
    }

    @Test
    @DisplayName("加载不存在的索引文件（不抛异常）")
    void testLoadNonExistent() throws Exception {
        HashIndex index = new HashIndex();
        index.loadFromFile(new File("nonexistent.idx"));
        assertEquals(0, index.size());
    }
}
