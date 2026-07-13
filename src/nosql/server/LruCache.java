package nosql.server;

import nosql.common.DbValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 线程安全的双级 LRU 缓存系统
 */
public class LruCache {
    private final int capacity;
    private final LinkedHashMap<String, DbValue> cacheMap;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public LruCache(int capacity) {
        this.capacity = capacity;
        // Access-order LinkedHashMap
        this.cacheMap = new LinkedHashMap<String, DbValue>(capacity, 0.75f, true) {
            private static final long serialVersionUID = 1L;
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, DbValue> eldest) {
                return size() > LruCache.this.capacity;
            }
        };
    }

    private String buildCacheKey(String collection, String key) {
        return collection + "\u0000" + key;
    }

    public DbValue get(String collection, String key) {
        String cacheKey = buildCacheKey(collection, key);
        lock.readLock().lock();
        try {
            // LinkedHashMap 的 get 会改变链表顺序，所以在写锁中处理，或者使用 LinkedHashMap 时用同步锁
            // 因为 access-order 的 LinkedHashMap 在 get 时会对 Entry 重新排序（属于写操作），
            // 故需要使用写锁，或者简单的使用 synchronized(cacheMap)。
        } finally {
            lock.readLock().unlock();
        }
        
        // 为了安全起见，对于并发的 access-order LinkedHashMap，所有操作（含 get/put）直接用 synchronized 块或者独占锁最安全。
        synchronized (cacheMap) {
            return cacheMap.get(cacheKey);
        }
    }

    public void put(String collection, String key, DbValue value) {
        String cacheKey = buildCacheKey(collection, key);
        synchronized (cacheMap) {
            cacheMap.put(cacheKey, value);
        }
    }

    public void invalidate(String collection, String key) {
        String cacheKey = buildCacheKey(collection, key);
        synchronized (cacheMap) {
            cacheMap.remove(cacheKey);
        }
    }

    public void clear() {
        synchronized (cacheMap) {
            cacheMap.clear();
        }
    }
    
    public int size() {
        synchronized (cacheMap) {
            return cacheMap.size();
        }
    }
}
