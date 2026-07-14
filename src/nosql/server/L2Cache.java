package nosql.server;

import nosql.common.DbValue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 二级缓存（L2 Cache）— 基于时间淘汰的大容量热数据缓存
 *
 * 架构：
 *   L1 (LruCache) → 最近最少使用淘汰，容量小（500），命中快
 *   L2 (本类)     → 基于最后访问时间淘汰，容量大（5000），补充 L1 未命中
 *
 * L2 作为 L1 的后备：L1 未命中 → 查 L2 → L2 命中即返回并回填 L1
 * 满足课程设计 2.2.1.3(2) 双级缓存要求
 */
public class L2Cache {

    /** 缓存条目：存储值和最后访问时间戳 */
    private static class CacheEntry {
        final DbValue value;
        volatile long lastAccessTime;

        CacheEntry(DbValue value) {
            this.value = value;
            this.lastAccessTime = System.currentTimeMillis();
        }

        void touch() {
            this.lastAccessTime = System.currentTimeMillis();
        }
    }

    private final int maxSize;
    private final long ttlMillis;       // 条目存活时间（毫秒）
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);

    /**
     * @param maxSize   最大条目数
     * @param ttlSeconds 条目 TTL（秒），超过此时间未访问则被清理
     */
    public L2Cache(int maxSize, int ttlSeconds) {
        this.maxSize = maxSize;
        this.ttlMillis = ttlSeconds * 1000L;
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "L2Cache-Cleaner");
            t.setDaemon(true);
            return t;
        });
        // 每 10 秒清理过期条目
        this.cleaner.scheduleAtFixedRate(this::evictExpired, 10, 10, TimeUnit.SECONDS);
    }

    public L2Cache() {
        this(5000, 300); // 默认 5000 条，5 分钟 TTL
    }

    private String buildKey(String collection, String key) {
        return collection + "\0" + key;
    }

    /**
     * 从 L2 缓存获取值，命中时更新访问时间
     */
    public DbValue get(String collection, String key) {
        String cacheKey = buildKey(collection, key);
        CacheEntry entry = cache.get(cacheKey);
        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }
        // TTL 过期检查
        if (System.currentTimeMillis() - entry.lastAccessTime > ttlMillis) {
            cache.remove(cacheKey);
            misses.incrementAndGet();
            return null;
        }
        entry.touch();
        hits.incrementAndGet();
        return entry.value;
    }

    /**
     * 写入 L2 缓存（若容量满则不写入，依赖 L1 淘汰策略）
     */
    public void put(String collection, String key, DbValue value) {
        if (cache.size() >= maxSize) {
            return; // 容量满，静默丢弃（L2 是补充缓存，丢失可接受）
        }
        String cacheKey = buildKey(collection, key);
        cache.put(cacheKey, new CacheEntry(value));
    }

    /**
     * 失效指定 key
     */
    public void invalidate(String collection, String key) {
        cache.remove(buildKey(collection, key));
    }

    /**
     * 清空所有缓存
     */
    public void clear() {
        cache.clear();
    }

    /**
     * 清理过期条目
     */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> now - e.getValue().lastAccessTime > ttlMillis);
    }

    /** 当前缓存条目数 */
    public int size() {
        return cache.size();
    }

    /** 命中次数 */
    public long getHitCount() { return hits.get(); }
    /** 未命中次数 */
    public long getMissCount() { return misses.get(); }

    /** 关闭清理线程 */
    public void shutdown() {
        cleaner.shutdown();
    }
}
