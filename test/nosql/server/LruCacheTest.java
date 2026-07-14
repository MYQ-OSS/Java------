package nosql.server;

import nosql.common.DbValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LRU 缓存单元测试
 * 对应指导书 2.2.1.3(2) 数据缓存
 */
@DisplayName("LRU 缓存 (LruCache)")
class LruCacheTest {

    @Test
    @DisplayName("基本 put / get 操作")
    void testPutAndGet() {
        LruCache cache = new LruCache(10);
        DbValue val = new DbValue(DbValue.Type.STRING, "hello");
        cache.put("users", "key1", val);

        DbValue result = cache.get("users", "key1");
        assertNotNull(result);
        assertEquals("hello", result.asString());
    }

    @Test
    @DisplayName("查询不存在的 key 返回 null")
    void testGetNonExistent() {
        LruCache cache = new LruCache(10);
        assertNull(cache.get("default", "nonexistent"));
    }

    @Test
    @DisplayName("invalidate 逐个失效缓存条目")
    void testInvalidate() {
        LruCache cache = new LruCache(10);
        cache.put("default", "k1", new DbValue(DbValue.Type.STRING, "v1"));
        cache.put("default", "k2", new DbValue(DbValue.Type.STRING, "v2"));

        cache.invalidate("default", "k1");
        assertNull(cache.get("default", "k1"));
        assertNotNull(cache.get("default", "k2"));
    }

    @Test
    @DisplayName("不同 collection 的同名 key 互不干扰")
    void testDifferentCollections() {
        LruCache cache = new LruCache(10);
        cache.put("coll_a", "key", new DbValue(DbValue.Type.STRING, "a"));
        cache.put("coll_b", "key", new DbValue(DbValue.Type.STRING, "b"));

        assertEquals("a", cache.get("coll_a", "key").asString());
        assertEquals("b", cache.get("coll_b", "key").asString());
    }

    @Test
    @DisplayName("LRU 淘汰：超出容量时淘汰最久未使用的条目")
    void testLruEviction() {
        LruCache cache = new LruCache(3);

        // 插入 3 条
        cache.put("default", "a", new DbValue(DbValue.Type.STRING, "va"));
        cache.put("default", "b", new DbValue(DbValue.Type.STRING, "vb"));
        cache.put("default", "c", new DbValue(DbValue.Type.STRING, "vc"));

        // 访问 a 使其变为最近使用
        cache.get("default", "a");

        // 插入第 4 条，触发淘汰（最久未使用的 b 应被淘汰）
        cache.put("default", "d", new DbValue(DbValue.Type.STRING, "vd"));

        assertNotNull(cache.get("default", "a")); // 最近被访问，保留
        assertNotNull(cache.get("default", "c"));
        assertNotNull(cache.get("default", "d"));
        // b 是最久未使用的，应被淘汰
        assertNull(cache.get("default", "b"));
    }

    @Test
    @DisplayName("clear 清空所有缓存")
    void testClear() {
        LruCache cache = new LruCache(10);
        cache.put("default", "k1", new DbValue(DbValue.Type.STRING, "v1"));
        cache.put("default", "k2", new DbValue(DbValue.Type.STRING, "v2"));

        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("default", "k1"));
    }

    @Test
    @DisplayName("size 正确反映缓存条目数")
    void testSize() {
        LruCache cache = new LruCache(100);
        assertEquals(0, cache.size());

        cache.put("default", "a", new DbValue(DbValue.Type.STRING, "x"));
        assertEquals(1, cache.size());

        cache.put("default", "b", new DbValue(DbValue.Type.STRING, "y"));
        assertEquals(2, cache.size());

        cache.invalidate("default", "a");
        assertEquals(1, cache.size());
    }
}
