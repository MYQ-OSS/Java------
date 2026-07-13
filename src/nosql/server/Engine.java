package nosql.server;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 兼容 Redis 核心数据类型及 TTL 过期策略的内存数据库引擎
 */
public class Engine {
    // 核心数据库存储：支持 String, ConcurrentLinkedDeque(List), Set, ConcurrentHashMap(Hash)
    private final ConcurrentHashMap<String, Object> dbMap = new ConcurrentHashMap<>();
    
    // 过期时间维护：key -> 过期绝对时间戳 (System.currentTimeMillis() + expireMs)
    private final ConcurrentHashMap<String, Long> expireMap = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleanScheduler;

    public Engine() {
        // 启动后台定期清理任务，每 5 秒清理一次过期 Key (符合 Redis 定期删除策略)
        this.cleanScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Redis-Active-Expire-Daemon");
            t.setDaemon(true);
            return t;
        });
        this.cleanScheduler.scheduleAtFixedRate(this::activeExpireCycle, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * 判断 Key 是否已经过期。如果过期，则执行惰性删除并返回 true
     */
    public boolean isExpired(String key) {
        Long expireTime = expireMap.get(key);
        if (expireTime == null) {
            return false;
        }
        if (System.currentTimeMillis() > expireTime) {
            // 惰性删除
            delete(key);
            return true;
        }
        return false;
    }

    /**
     * 定期清理周期（主动扫描并清理过期键）
     */
    private void activeExpireCycle() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : expireMap.entrySet()) {
            if (now > entry.getValue()) {
                delete(entry.getKey());
            }
        }
    }

    // ==========================================
    // 核心指令逻辑实现
    // ==========================================

    /**
     * SET key value [EX seconds]
     */
    public void set(String key, String value) {
        dbMap.put(key, value);
        expireMap.remove(key); // 覆写 Key 时清除原有过期时间
    }

    public String get(String key) {
        if (isExpired(key)) {
            return null;
        }
        Object obj = dbMap.get(key);
        if (obj == null) {
            return null;
        }
        if (!(obj instanceof String)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return (String) obj;
    }

    public boolean exists(String key) {
        if (isExpired(key)) {
            return false;
        }
        return dbMap.containsKey(key);
    }

    public void delete(String key) {
        dbMap.remove(key);
        expireMap.remove(key);
    }

    /**
     * EXPIRE key seconds
     */
    public boolean expire(String key, int seconds) {
        if (!dbMap.containsKey(key) || isExpired(key)) {
            return false;
        }
        long expireTime = System.currentTimeMillis() + (seconds * 1000L);
        expireMap.put(key, expireTime);
        return true;
    }

    /**
     * TTL key
     * @return 剩余生存时间（秒）。如果不存在返回 -2，如果存在但无过期时间返回 -1
     */
    public long ttl(String key) {
        if (!dbMap.containsKey(key) || isExpired(key)) {
            return -2;
        }
        Long expireTime = expireMap.get(key);
        if (expireTime == null) {
            return -1;
        }
        long remaining = (expireTime - System.currentTimeMillis()) / 1000L;
        return remaining < 0 ? -2 : remaining;
    }

    /**
     * INCR key
     */
    public synchronized long incr(String key) {
        if (isExpired(key)) {
            dbMap.remove(key);
        }
        Object obj = dbMap.get(key);
        long val = 0;
        if (obj != null) {
            if (!(obj instanceof String)) {
                throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
            }
            try {
                val = Long.parseLong((String) obj);
            } catch (NumberFormatException e) {
                throw new NumberFormatException("ERR value is not an integer or out of range");
            }
        }
        val++;
        dbMap.put(key, String.valueOf(val));
        return val;
    }

    /**
     * DECR key
     */
    public synchronized long decr(String key) {
        if (isExpired(key)) {
            dbMap.remove(key);
        }
        Object obj = dbMap.get(key);
        long val = 0;
        if (obj != null) {
            if (!(obj instanceof String)) {
                throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
            }
            try {
                val = Long.parseLong((String) obj);
            } catch (NumberFormatException e) {
                throw new NumberFormatException("ERR value is not an integer or out of range");
            }
        }
        val--;
        dbMap.put(key, String.valueOf(val));
        return val;
    }

    // ==========================================
    // Hash 类型操作
    // ==========================================

    @SuppressWarnings("unchecked")
    public int hset(String key, String field, String value) {
        if (isExpired(key)) {
            dbMap.remove(key);
        }
        Object obj = dbMap.computeIfAbsent(key, k -> new ConcurrentHashMap<String, String>());
        if (!(obj instanceof ConcurrentHashMap)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        ConcurrentHashMap<String, String> map = (ConcurrentHashMap<String, String>) obj;
        String old = map.put(field, value);
        return old == null ? 1 : 0; // 新增返回1，更新返回0
    }

    @SuppressWarnings("unchecked")
    public String hget(String key, String field) {
        if (isExpired(key)) {
            return null;
        }
        Object obj = dbMap.get(key);
        if (obj == null) return null;
        if (!(obj instanceof ConcurrentHashMap)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return ((ConcurrentHashMap<String, String>) obj).get(field);
    }

    @SuppressWarnings("unchecked")
    public int hdel(String key, String field) {
        if (isExpired(key)) {
            return 0;
        }
        Object obj = dbMap.get(key);
        if (obj == null) return 0;
        if (!(obj instanceof ConcurrentHashMap)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        String old = ((ConcurrentHashMap<String, String>) obj).remove(field);
        return old != null ? 1 : 0;
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> hgetall(String key) {
        if (isExpired(key)) {
            return Collections.emptyMap();
        }
        Object obj = dbMap.get(key);
        if (obj == null) return Collections.emptyMap();
        if (!(obj instanceof ConcurrentHashMap)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return Collections.unmodifiableMap((ConcurrentHashMap<String, String>) obj);
    }

    // ==========================================
    // List 类型操作
    // ==========================================

    @SuppressWarnings("unchecked")
    public int lpush(String key, String value) {
        if (isExpired(key)) {
            dbMap.remove(key);
        }
        Object obj = dbMap.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<String>());
        if (!(obj instanceof ConcurrentLinkedDeque)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        ConcurrentLinkedDeque<String> deque = (ConcurrentLinkedDeque<String>) obj;
        deque.addFirst(value);
        return deque.size();
    }

    @SuppressWarnings("unchecked")
    public int rpush(String key, String value) {
        if (isExpired(key)) {
            dbMap.remove(key);
        }
        Object obj = dbMap.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<String>());
        if (!(obj instanceof ConcurrentLinkedDeque)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        ConcurrentLinkedDeque<String> deque = (ConcurrentLinkedDeque<String>) obj;
        deque.addLast(value);
        return deque.size();
    }

    @SuppressWarnings("unchecked")
    public String lpop(String key) {
        if (isExpired(key)) {
            return null;
        }
        Object obj = dbMap.get(key);
        if (obj == null) return null;
        if (!(obj instanceof ConcurrentLinkedDeque)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return ((ConcurrentLinkedDeque<String>) obj).pollFirst();
    }

    @SuppressWarnings("unchecked")
    public String rpop(String key) {
        if (isExpired(key)) {
            return null;
        }
        Object obj = dbMap.get(key);
        if (obj == null) return null;
        if (!(obj instanceof ConcurrentLinkedDeque)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return ((ConcurrentLinkedDeque<String>) obj).pollLast();
    }

    @SuppressWarnings("unchecked")
    public List<String> lrange(String key, int start, int end) {
        if (isExpired(key)) {
            return Collections.emptyList();
        }
        Object obj = dbMap.get(key);
        if (obj == null) return Collections.emptyList();
        if (!(obj instanceof ConcurrentLinkedDeque)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        
        ConcurrentLinkedDeque<String> deque = (ConcurrentLinkedDeque<String>) obj;
        List<String> rawList = new ArrayList<>(deque);
        int size = rawList.size();
        
        // 兼容 Redis 负数索引
        if (start < 0) start = size + start;
        if (end < 0) end = size + end;
        if (start < 0) start = 0;
        if (end >= size) end = size - 1;
        if (start > end || start >= size) return Collections.emptyList();
        
        return rawList.subList(start, end + 1);
    }

    // ==========================================
    // Set 类型操作
    // ==========================================

    @SuppressWarnings("unchecked")
    public int sadd(String key, String member) {
        if (isExpired(key)) {
            dbMap.remove(key);
        }
        Object obj = dbMap.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        if (!(obj instanceof Set)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        Set<String> set = (Set<String>) obj;
        return set.add(member) ? 1 : 0;
    }

    @SuppressWarnings("unchecked")
    public int srem(String key, String member) {
        if (isExpired(key)) {
            return 0;
        }
        Object obj = dbMap.get(key);
        if (obj == null) return 0;
        if (!(obj instanceof Set)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        Set<String> set = (Set<String>) obj;
        return set.remove(member) ? 1 : 0;
    }

    @SuppressWarnings("unchecked")
    public Set<String> smembers(String key) {
        if (isExpired(key)) {
            return Collections.emptySet();
        }
        Object obj = dbMap.get(key);
        if (obj == null) return Collections.emptySet();
        if (!(obj instanceof Set)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return Collections.unmodifiableSet((Set<String>) obj);
    }

    @SuppressWarnings("unchecked")
    public int sismember(String key, String member) {
        if (isExpired(key)) {
            return 0;
        }
        Object obj = dbMap.get(key);
        if (obj == null) return 0;
        if (!(obj instanceof Set)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return ((Set<String>) obj).contains(member) ? 1 : 0;
    }

    // ==========================================
    // 系统级指令
    // ==========================================

    public void clear() {
        dbMap.clear();
        expireMap.clear();
    }

    public int dbSize() {
        // 需主动过滤掉已过期但未删除的 Key
        activeExpireCycle();
        return dbMap.size();
    }

    public Set<String> keys() {
        activeExpireCycle();
        return dbMap.keySet();
    }

    public void shutdown() {
        cleanScheduler.shutdown();
    }

    /**
     * 获取指定 Key 对应的数据结构类型名称（Redis TYPE 响应格式）
     */
    public String type(String key) {
        if (!dbMap.containsKey(key) || isExpired(key)) {
            return "none";
        }
        Object obj = dbMap.get(key);
        if (obj instanceof String) return "string";
        if (obj instanceof ConcurrentLinkedDeque) return "list";
        if (obj instanceof Set) return "set";
        if (obj instanceof ConcurrentHashMap) return "hash";
        return "none";
    }

    /**
     * 获取全量快照，供主从复制同步使用
     */
    public Map<String, Object> getRawDbMap() {
        activeExpireCycle();
        return dbMap;
    }

    /**
     * 获取过期的快照，供主从同步
     */
    public Map<String, Long> getRawExpireMap() {
        return expireMap;
    }
}
