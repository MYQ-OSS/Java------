package nosql.server;

import nosql.common.DbValue;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 兼容 Redis 核心数据类型及 TTL 过期策略的 LSMT 内存+磁盘混合数据库引擎
 *
 * 架构:
 *   MemTable (dbMap, 内存) → SSTable (磁盘有序表, 达到阈值刷写)
 *   HashIndex (内存) → O(1) 磁盘偏移量定位
 *   LruCache (内存) → 热数据缓存加速
 *
 * 满足课程设计:
 *   2.2.1.3(1) 索引加速查询  → HashIndex
 *   2.2.1.3(2) 数据缓存      → LruCache
 *   2.2.1.5(2) LSMT 存储结构 → MemTable + SSTable + Compaction
 */
public class Engine {
    // MemTable: 内存中的核心数据库存储
    private final ConcurrentHashMap<String, Object> dbMap = new ConcurrentHashMap<>();

    // 过期时间维护：key -> 过期绝对时间戳
    private final ConcurrentHashMap<String, Long> expireMap = new ConcurrentHashMap<>();

    // LSMT 组件
    private final HashIndex hashIndex = new HashIndex();
    private final LruCache lruCache = new LruCache(500); // L1: 500 条热数据缓存
    private final L2Cache l2Cache = new L2Cache(5000, 300); // L2: 5000 条，5 分钟 TTL
    private final File dataDir;
    private final int memtableFlushThreshold; // MemTable 刷写阈值（key 数量）
    private final List<File> sstables = new CopyOnWriteArrayList<>(); // SSTable 文件列表（新→旧）
    private final ScheduledExecutorService cleanScheduler;
    private final ScheduledExecutorService compactionExecutor;

    // Collection 元数据：明确跟踪用户创建的集合
    private final Set<String> collectionNames = ConcurrentHashMap.newKeySet();

    // 刷写完成回调（用于触发 AOF rewrite，携带 dbMap 快照）
    private Consumer<FlushSnapshot> postFlushCallback;

    /**
     * MemTable 刷写时的数据快照，在 dbMap.clear() 前捕获，传递给 AOF rewrite
     */
    public static class FlushSnapshot {
        private final Map<String, Object> data;       // dbMap 的深拷贝
        private final Map<String, Long> expires;       // expireMap 的深拷贝

        FlushSnapshot(Map<String, Object> data, Map<String, Long> expires) {
            this.data = data;
            this.expires = expires;
        }

        public Map<String, Object> getData() { return data; }
        public Map<String, Long> getExpires() { return expires; }
    }

    // 读写统计
    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private final AtomicInteger cacheMisses = new AtomicInteger(0);
    private final AtomicInteger sstableReads = new AtomicInteger(0);

    public Engine(File dataDir) {
        this(dataDir, 1000); // 默认 1000 个 key 触发刷写
    }

    public Engine(File dataDir, int memtableFlushThreshold) {
        this.dataDir = dataDir;
        this.memtableFlushThreshold = memtableFlushThreshold;

        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        // 加载持久化的哈希索引
        File idxFile = new File(dataDir, "hash_index.idx");
        try {
            hashIndex.loadFromFile(idxFile);
            System.out.println("[Engine] Loaded hash index: " + hashIndex.size() + " entries");
        } catch (IOException e) {
            System.out.println("[Engine] No existing hash index found, starting fresh");
        }

        // 扫描已有的 SSTable 文件
        List<File> existingSSTables = SSTable.listSSTables(dataDir);
        this.sstables.addAll(existingSSTables);
        if (!existingSSTables.isEmpty()) {
            System.out.println("[Engine] Found " + existingSSTables.size() + " existing SSTable(s)");
        }

        // 恢复 collection 元数据
        File collFile = new File(dataDir, "collections.meta");
        if (collFile.exists()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(collFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        collectionNames.add(line.trim());
                    }
                }
            } catch (IOException e) {
                System.out.println("[Engine] Failed to load collection metadata");
            }
        }

        // 启动后台定期过期清理
        this.cleanScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Redis-Active-Expire-Daemon");
            t.setDaemon(true);
            return t;
        });
        this.cleanScheduler.scheduleAtFixedRate(this::activeExpireCycle, 5, 5, TimeUnit.SECONDS);

        // 启动后台 SSTable 压缩线程
        this.compactionExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LSMT-Compaction-Daemon");
            t.setDaemon(true);
            return t;
        });
        this.compactionExecutor.scheduleAtFixedRate(this::backgroundCompaction, 30, 30, TimeUnit.SECONDS);
    }

    // ==========================================
    // 过期管理
    // ==========================================

    public boolean isExpired(String key) {
        Long expireTime = expireMap.get(key);
        if (expireTime == null) {
            return false;
        }
        if (System.currentTimeMillis() > expireTime) {
            delete(key);
            return true;
        }
        return false;
    }

    private void activeExpireCycle() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : expireMap.entrySet()) {
            if (now > entry.getValue()) {
                delete(entry.getKey());
            }
        }
    }

    // ==========================================
    // LSMT: MemTable → SSTable 刷写
    // ==========================================

    /**
     * 将当前 MemTable (dbMap) 刷写到磁盘 SSTable，清空 MemTable，更新 HashIndex
     */
    public synchronized void flushMemTableToSSTable() {
        if (dbMap.isEmpty()) return;

        try {
            File sstable = SSTable.flush(dataDir, dbMap, this, hashIndex);
            if (sstable != null) {
                sstables.add(0, sstable); // 新 SSTable 放在最前面

                // 持久化哈希索引
                saveHashIndex();
            }
            // ★★★ 在清空 dbMap 之前捕获快照，用于 AOF rewrite ★★★
            FlushSnapshot snapshot = new FlushSnapshot(new HashMap<>(dbMap), new HashMap<>(expireMap));

            // 清空 MemTable（但保留过期时间映射以支持 SSTable 数据的 TTL）
            dbMap.clear();
            System.out.println("[Engine] MemTable flushed. HashIndex size: " + hashIndex.size());

            // 检查是否需要压缩
            if (sstables.size() >= 3) {
                triggerCompaction();
            }
            // 触发后置回调（AOF rewrite 等），传入快照而非读引擎
            if (postFlushCallback != null) {
                postFlushCallback.accept(snapshot);
            }
        } catch (IOException e) {
            System.err.println("[Engine] Failed to flush MemTable to SSTable: " + e.getMessage());
        }
    }

    /** 注册刷写完成回调（Database 用来触发 AOF rewrite） */
    public void setPostFlushCallback(Consumer<FlushSnapshot> callback) {
        this.postFlushCallback = callback;
    }

    private void saveHashIndex() {
        try {
            hashIndex.saveToFile(new File(dataDir, "hash_index.idx"));
        } catch (IOException e) {
            System.err.println("[Engine] Failed to save hash index: " + e.getMessage());
        }
    }

    private void saveCollectionMeta() {
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(new File(dataDir, "collections.meta")),
                        StandardCharsets.UTF_8))) {
            for (String name : collectionNames) {
                pw.println(name);
            }
        } catch (IOException e) {
            System.err.println("[Engine] Failed to save collection metadata");
        }
    }

    /**
     * 检查是否需要触发刷写
     */
    private void checkFlushThreshold() {
        if (dbMap.size() >= memtableFlushThreshold) {
            flushMemTableToSSTable();
        }
    }

    // ==========================================
    // LSMT: 后台压缩合并
    // ==========================================

    private void backgroundCompaction() {
        try {
            if (sstables.size() < 3) return; // 少于 3 个 SSTable 不压缩

            // 取最旧的 2 个 SSTable 合并
            List<File> sstableSnapshot = new ArrayList<>(sstables);
            // 按文件名排序（时间戳顺序：旧 → 新）
            sstableSnapshot.sort(Comparator.comparing(File::getName));

            if (sstableSnapshot.size() < 3) return;

            // 合并最旧的两个
            List<File> toCompact = sstableSnapshot.subList(0, 2);
            File compacted = SSTable.compact(dataDir, new ArrayList<>(toCompact), hashIndex);

            // 更新 sstables 列表
            sstables.removeAll(toCompact);
            if (compacted != null) {
                sstables.add(compacted);
                sstables.sort((a, b) -> Long.compare(
                        extractTimestamp(b.getName()), extractTimestamp(a.getName())));
            }

            saveHashIndex();
        } catch (Exception e) {
            System.err.println("[Engine] Compaction error: " + e.getMessage());
        }
    }

    private void triggerCompaction() {
        compactionExecutor.execute(() -> {
            try {
                backgroundCompaction();
            } catch (Exception ignored) {}
        });
    }

    private long extractTimestamp(String fileName) {
        try {
            // sstable_<timestamp>.sst
            String num = fileName.replace("sstable_", "").replace(".sst", "");
            return Long.parseLong(num);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==========================================
    // 核心读写方法（含 LRU + HashIndex + SSTable 查找路径）
    // ==========================================

    // 将多种 Java 对象转为字符串存储
    private String objectToString(Object obj) {
        if (obj instanceof String) return (String) obj;
        if (obj instanceof java.util.concurrent.ConcurrentLinkedDeque) {
            StringBuilder sb = new StringBuilder();
            for (Object item : (java.util.concurrent.ConcurrentLinkedDeque<?>) obj) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(item);
            }
            return sb.toString();
        }
        if (obj instanceof Set) {
            StringBuilder sb = new StringBuilder();
            for (Object item : (Set<?>) obj) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(item);
            }
            return sb.toString();
        }
        if (obj instanceof ConcurrentHashMap) {
            return obj.toString();
        }
        return String.valueOf(obj);
    }

    // ==========================================
    // String 操作
    // ==========================================

    public void set(String key, String value) {
        dbMap.put(key, value);
        expireMap.remove(key);
        // 更新 L1，失效 L2（写操作保证缓存与磁盘一致性）
        lruCache.put("default", key, new DbValue(DbValue.Type.STRING, value));
        l2Cache.invalidate("default", key);
        // 检查刷写阈值
        checkFlushThreshold();
    }

    public String get(String key) {
        if (isExpired(key)) {
            return null;
        }

        // 1. 先查 L1 LRU 缓存
        DbValue cached = lruCache.get("default", key);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached.asString();
        }

        // 2. 查 L2 缓存（双级缓存补充）
        DbValue l2Cached = l2Cache.get("default", key);
        if (l2Cached != null) {
            cacheHits.incrementAndGet();
            // 回填 L1
            lruCache.put("default", key, l2Cached);
            return l2Cached.asString();
        }

        // 3. 查 MemTable
        Object obj = dbMap.get(key);
        if (obj != null) {
            cacheMisses.incrementAndGet();
            if (!(obj instanceof String)) {
                throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
            }
            String val = (String) obj;
            // 回填 L1 + L2 缓存
            DbValue dbVal = new DbValue(DbValue.Type.STRING, val);
            lruCache.put("default", key, dbVal);
            l2Cache.put("default", key, dbVal);
            return val;
        }

        // 4. 查 HashIndex → SSTable 磁盘读取
        cacheMisses.incrementAndGet();
        HashIndex.IndexEntry idxEntry = hashIndex.get(key);
        if (idxEntry != null) {
            sstableReads.incrementAndGet();
            try {
                File sstFile = new File(dataDir, idxEntry.sstableFileName);
                if (sstFile.exists()) {
                    String diskVal = SSTable.read(sstFile, key);
                    if (diskVal != null) {
                        // 回填 L1 + L2 缓存和 MemTable（热数据回到内存）
                        DbValue dbVal = new DbValue(DbValue.Type.STRING, diskVal);
                        lruCache.put("default", key, dbVal);
                        l2Cache.put("default", key, dbVal);
                        dbMap.put(key, diskVal);
                        return diskVal;
                    }
                }
            } catch (IOException e) {
                System.err.println("[Engine] SSTable read error for key '" + key + "': " + e.getMessage());
            }
        }

        return null;
    }

    public boolean exists(String key) {
        if (isExpired(key)) return false;
        if (dbMap.containsKey(key)) return true;
        return hashIndex.containsKey(key);
    }

    public void delete(String key) {
        dbMap.remove(key);
        expireMap.remove(key);
        hashIndex.remove(key);
        lruCache.invalidate("default", key);
        l2Cache.invalidate("default", key);
    }

    public boolean expire(String key, int seconds) {
        if (!exists(key)) return false;
        long expireTime = System.currentTimeMillis() + (seconds * 1000L);
        expireMap.put(key, expireTime);
        return true;
    }

    public long ttl(String key) {
        if (!exists(key)) return -2;
        Long expireTime = expireMap.get(key);
        if (expireTime == null) return -1;
        long remaining = (expireTime - System.currentTimeMillis()) / 1000L;
        return remaining < 0 ? -2 : remaining;
    }

    public synchronized long incr(String key) {
        if (isExpired(key)) dbMap.remove(key);
        // SSTable 中的值也要处理：先 get 确保加载到内存
        Object obj = dbMap.get(key);
        if (obj == null && hashIndex.containsKey(key)) {
            String diskVal = get(key); // 从 SSTable 加载
            obj = diskVal;
        }
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
        String strVal = String.valueOf(val);
        dbMap.put(key, strVal);
        lruCache.put("default", key, new DbValue(DbValue.Type.STRING, strVal));
        checkFlushThreshold();
        return val;
    }

    public synchronized long decr(String key) {
        if (isExpired(key)) dbMap.remove(key);
        Object obj = dbMap.get(key);
        if (obj == null && hashIndex.containsKey(key)) {
            obj = get(key);
        }
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
        String strVal = String.valueOf(val);
        dbMap.put(key, strVal);
        lruCache.put("default", key, new DbValue(DbValue.Type.STRING, strVal));
        checkFlushThreshold();
        return val;
    }

    // ==========================================
    // 批量操作 MSET / MGET
    // ==========================================

    /**
     * MSET key1 val1 key2 val2 ...
     * @return 成功写入的数量
     */
    public int mset(List<String> args) {
        int count = 0;
        for (int i = 0; i + 1 < args.size(); i += 2) {
            String key = args.get(i);
            String val = args.get(i + 1);
            set(key, val);
            count++;
        }
        return count;
    }

    /**
     * MGET key1 key2 ...
     * @return 值列表（不存在的 key 对应 null）
     */
    public List<String> mget(List<String> keys) {
        List<String> results = new ArrayList<>();
        for (String key : keys) {
            try {
                String val = get(key);
                results.add(val);
            } catch (Exception e) {
                results.add(null);
            }
        }
        return results;
    }

    // ==========================================
    // Hash 类型操作
    // ==========================================

    @SuppressWarnings("unchecked")
    public int hset(String key, String field, String value) {
        if (isExpired(key)) dbMap.remove(key);
        // 如果 key 在 SSTable 中，先加载到内存
        if (!dbMap.containsKey(key) && hashIndex.containsKey(key)) {
            loadSSTableKeyToMemory(key);
        }
        Object obj = dbMap.computeIfAbsent(key, k -> new ConcurrentHashMap<String, String>());
        if (!(obj instanceof ConcurrentHashMap)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        ConcurrentHashMap<String, String> map = (ConcurrentHashMap<String, String>) obj;
        String old = map.put(field, value);
        checkFlushThreshold();
        return old == null ? 1 : 0;
    }

    @SuppressWarnings("unchecked")
    public String hget(String key, String field) {
        if (isExpired(key)) return null;
        Object obj = dbMap.get(key);
        if (obj == null && hashIndex.containsKey(key)) {
            loadSSTableKeyToMemory(key);
            obj = dbMap.get(key);
        }
        if (obj == null) return null;
        if (!(obj instanceof ConcurrentHashMap)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return ((ConcurrentHashMap<String, String>) obj).get(field);
    }

    @SuppressWarnings("unchecked")
    public int hdel(String key, String field) {
        if (isExpired(key)) return 0;
        Object obj = dbMap.get(key);
        if (obj == null && hashIndex.containsKey(key)) {
            loadSSTableKeyToMemory(key);
            obj = dbMap.get(key);
        }
        if (obj == null) return 0;
        if (!(obj instanceof ConcurrentHashMap)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        String old = ((ConcurrentHashMap<String, String>) obj).remove(field);
        return old != null ? 1 : 0;
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> hgetall(String key) {
        if (isExpired(key)) return Collections.emptyMap();
        Object obj = dbMap.get(key);
        if (obj == null && hashIndex.containsKey(key)) {
            loadSSTableKeyToMemory(key);
            obj = dbMap.get(key);
        }
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
        if (isExpired(key)) dbMap.remove(key);
        if (!dbMap.containsKey(key) && hashIndex.containsKey(key)) {
            loadSSTableKeyToMemory(key);
        }
        Object obj = dbMap.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<String>());
        if (!(obj instanceof ConcurrentLinkedDeque)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        ConcurrentLinkedDeque<String> deque = (ConcurrentLinkedDeque<String>) obj;
        deque.addFirst(value);
        checkFlushThreshold();
        return deque.size();
    }

    @SuppressWarnings("unchecked")
    public int rpush(String key, String value) {
        if (isExpired(key)) dbMap.remove(key);
        if (!dbMap.containsKey(key) && hashIndex.containsKey(key)) {
            loadSSTableKeyToMemory(key);
        }
        Object obj = dbMap.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<String>());
        if (!(obj instanceof ConcurrentLinkedDeque)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        ConcurrentLinkedDeque<String> deque = (ConcurrentLinkedDeque<String>) obj;
        deque.addLast(value);
        checkFlushThreshold();
        return deque.size();
    }

    @SuppressWarnings("unchecked")
    public String lpop(String key) {
        if (isExpired(key)) return null;
        Object obj = dbMap.get(key);
        if (obj == null && hashIndex.containsKey(key)) {
            loadSSTableKeyToMemory(key);
            obj = dbMap.get(key);
        }
        if (obj == null) return null;
        if (!(obj instanceof ConcurrentLinkedDeque)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return ((ConcurrentLinkedDeque<String>) obj).pollFirst();
    }

    @SuppressWarnings("unchecked")
    public String rpop(String key) {
        if (isExpired(key)) return null;
        Object obj = dbMap.get(key);
        if (obj == null && hashIndex.containsKey(key)) {
            loadSSTableKeyToMemory(key);
            obj = dbMap.get(key);
        }
        if (obj == null) return null;
        if (!(obj instanceof ConcurrentLinkedDeque)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return ((ConcurrentLinkedDeque<String>) obj).pollLast();
    }

    @SuppressWarnings("unchecked")
    public List<String> lrange(String key, int start, int end) {
        if (isExpired(key)) return Collections.emptyList();
        Object obj = dbMap.get(key);
        if (obj == null && hashIndex.containsKey(key)) {
            loadSSTableKeyToMemory(key);
            obj = dbMap.get(key);
        }
        if (obj == null) return Collections.emptyList();
        if (!(obj instanceof ConcurrentLinkedDeque)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        ConcurrentLinkedDeque<String> deque = (ConcurrentLinkedDeque<String>) obj;
        List<String> rawList = new ArrayList<>(deque);
        int size = rawList.size();
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
        if (isExpired(key)) dbMap.remove(key);
        if (!dbMap.containsKey(key) && hashIndex.containsKey(key)) {
            loadSSTableKeyToMemory(key);
        }
        Object obj = dbMap.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        if (!(obj instanceof Set)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        Set<String> set = (Set<String>) obj;
        int result = set.add(member) ? 1 : 0;
        checkFlushThreshold();
        return result;
    }

    @SuppressWarnings("unchecked")
    public int srem(String key, String member) {
        if (isExpired(key)) return 0;
        Object obj = dbMap.get(key);
        if (obj == null && hashIndex.containsKey(key)) {
            loadSSTableKeyToMemory(key);
            obj = dbMap.get(key);
        }
        if (obj == null) return 0;
        if (!(obj instanceof Set)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return ((Set<String>) obj).remove(member) ? 1 : 0;
    }

    @SuppressWarnings("unchecked")
    public Set<String> smembers(String key) {
        if (isExpired(key)) return Collections.emptySet();
        Object obj = dbMap.get(key);
        if (obj == null && hashIndex.containsKey(key)) {
            loadSSTableKeyToMemory(key);
            obj = dbMap.get(key);
        }
        if (obj == null) return Collections.emptySet();
        if (!(obj instanceof Set)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return Collections.unmodifiableSet((Set<String>) obj);
    }

    @SuppressWarnings("unchecked")
    public int sismember(String key, String member) {
        if (isExpired(key)) return 0;
        Object obj = dbMap.get(key);
        if (obj == null && hashIndex.containsKey(key)) {
            loadSSTableKeyToMemory(key);
            obj = dbMap.get(key);
        }
        if (obj == null) return 0;
        if (!(obj instanceof Set)) {
            throw new ClassCastException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return ((Set<String>) obj).contains(member) ? 1 : 0;
    }

    // ==========================================
    // Collection 管理
    // ==========================================

    /**
     * CREATE COLLECTION <name> — 显式创建一个数据集合（类比 MySQL 建表）
     */
    public String createCollection(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "ERR collection name cannot be empty";
        }
        collectionNames.add(name.trim());
        saveCollectionMeta();
        System.out.println("[Engine] Collection created: " + name);
        return "OK";
    }

    /**
     * DROP COLLECTION <name> — 删除集合及其所有数据
     */
    public String dropCollection(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "ERR collection name cannot be empty";
        }
        String prefix = name.trim() + ":";
        // 删除所有以 prefix 开头的 key
        for (String key : dbMap.keySet()) {
            if (key.startsWith(prefix)) {
                delete(key);
            }
        }
        // 也清理 SSTable 索引中的数据（标记删除由压缩时处理）
        collectionNames.remove(name.trim());
        saveCollectionMeta();
        System.out.println("[Engine] Collection dropped: " + name + ", keys with prefix '" + prefix + "' removed from memory");
        return "OK";
    }

    /**
     * LIST COLLECTIONS — 列出所有集合
     */
    public Set<String> listCollections() {
        // 同时包含显式创建的 collection 和从 key 前缀推断的 collection
        Set<String> all = new HashSet<>(collectionNames);
        for (String key : dbMap.keySet()) {
            int idx = key.indexOf(':');
            if (idx > 0) {
                all.add(key.substring(0, idx));
            }
        }
        // 也从 hashIndex 推断
        for (String key : keys()) {
            int idx = key.indexOf(':');
            if (idx > 0) {
                all.add(key.substring(0, idx));
            }
        }
        return all;
    }

    // ==========================================
    // SSTable 数据加载辅助
    // ==========================================

    /**
     * 从 SSTable 加载一个 key 到内存 MemTable
     */
    @SuppressWarnings("unchecked")
    private void loadSSTableKeyToMemory(String key) {
        HashIndex.IndexEntry idxEntry = hashIndex.get(key);
        if (idxEntry == null) return;

        try {
            File sstFile = new File(dataDir, idxEntry.sstableFileName);
            if (!sstFile.exists()) return;

            // 读取 SSTable Entry 完整结构
            List<SSTable.Entry> allEntries = SSTable.readAll(sstFile);
            for (SSTable.Entry entry : allEntries) {
                if (entry.key.equals(key)) {
                    String valStr = new String(entry.value, StandardCharsets.UTF_8);
                    switch (entry.type) {
                        case 0: // String
                            dbMap.put(key, valStr);
                            break;
                        case 1: // List
                            ConcurrentLinkedDeque<String> deque = new ConcurrentLinkedDeque<>();
                            if (!valStr.isEmpty()) {
                                for (String item : valStr.split("")) {
                                    if (!item.isEmpty()) deque.add(item);
                                }
                            }
                            dbMap.put(key, deque);
                            break;
                        case 2: // Set
                            Set<String> set = ConcurrentHashMap.newKeySet();
                            if (!valStr.isEmpty()) {
                                for (String item : valStr.split("")) {
                                    if (!item.isEmpty()) set.add(item);
                                }
                            }
                            dbMap.put(key, set);
                            break;
                        case 3: // Hash
                            ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
                            if (!valStr.isEmpty()) {
                                for (String pair : valStr.split("")) {
                                    String[] kv = pair.split("", 2);
                                    if (kv.length == 2) map.put(kv[0], kv[1]);
                                }
                            }
                            dbMap.put(key, map);
                            break;
                    }
                    sstableReads.incrementAndGet();
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("[Engine] Failed to load key '" + key + "' from SSTable: " + e.getMessage());
        }
    }

    // ==========================================
    // 系统级指令
    // ==========================================

    public void clear() {
        dbMap.clear();
        expireMap.clear();
        hashIndex.clear();
        lruCache.clear();
        l2Cache.clear();
        collectionNames.clear();
        // 删除所有 SSTable 文件
        for (File f : sstables) {
            f.delete();
        }
        sstables.clear();
        // 删除索引文件
        new File(dataDir, "hash_index.idx").delete();
        new File(dataDir, "collections.meta").delete();
        System.out.println("[Engine] Database completely cleared");
    }

    public int dbSize() {
        activeExpireCycle();
        return dbMap.size() + hashIndex.size();
    }

    public Set<String> keys() {
        activeExpireCycle();
        Set<String> allKeys = new HashSet<>(dbMap.keySet());
        // 也加入 HashIndex 中引用但不在内存中的 key（从 SSTable 获取）
        allKeys.addAll(hashIndex.getAllKeys());
        return allKeys;
    }

    public void shutdown() {
        // 关闭前刷写 MemTable 到 SSTable
        if (!dbMap.isEmpty()) {
            System.out.println("[Engine] Flushing MemTable before shutdown...");
            flushMemTableToSSTable();
        }
        saveHashIndex();
        saveCollectionMeta();
        cleanScheduler.shutdown();
        compactionExecutor.shutdown();
        l2Cache.shutdown();
        System.out.println("[Engine] Shutdown complete. Cache stats — hits: " +
                cacheHits.get() + ", misses: " + cacheMisses.get() +
                ", sstable reads: " + sstableReads.get() +
                ", L2 hits: " + l2Cache.getHitCount() + ", L2 misses: " + l2Cache.getMissCount());
    }

    public String type(String key) {
        if (!dbMap.containsKey(key) || isExpired(key)) {
            if (hashIndex.containsKey(key)) {
                // key 在 SSTable 中，但不在内存，返回推测类型（实际需要读取 SSTable）
                return "string"; // 默认返回 string，完整的实现需要查 SSTable
            }
            return "none";
        }
        Object obj = dbMap.get(key);
        if (obj instanceof String) return "string";
        if (obj instanceof ConcurrentLinkedDeque) return "list";
        if (obj instanceof Set) return "set";
        if (obj instanceof ConcurrentHashMap) return "hash";
        return "none";
    }

    public Map<String, Object> getRawDbMap() {
        activeExpireCycle();
        return dbMap;
    }

    public Map<String, Long> getRawExpireMap() {
        return expireMap;
    }

    // ==========================================
    // 统计与监控
    // ==========================================

    public int getCacheHitCount() { return cacheHits.get(); }
    public int getCacheMissCount() { return cacheMisses.get(); }
    public int getSSTableReadCount() { return sstableReads.get(); }
    public int getSSTableCount() { return sstables.size(); }
    public int getHashIndexSize() { return hashIndex.size(); }
    public int getMemTableSize() { return dbMap.size(); }

    // ==========================================
    // DEBUG_READ：带完整追踪的读路径（真实定时 + 真实路径）
    // ==========================================

    /**
     * 逐级追踪真实读路径，返回每一步的耗时和命中信息
     */
    public List<String> debugGet(String key) {
        List<String> trace = new ArrayList<>();

        if (isExpired(key)) {
            trace.add("[TTL 过期] Key \"" + key + "\" 已过期，自动删除");
            trace.add("[结果] null");
            return trace;
        }

        // ── L1 LRU 缓存 ──
        long t0 = System.nanoTime();
        DbValue l1Val = lruCache.get("default", key);
        long t1 = System.nanoTime();
        long l1us = (t1 - t0) / 1000;
        if (l1Val != null) {
            trace.add("[L1 LRU 缓存] 命中！(" + l1us + "μs)");
            String val = l1Val.asString();
            trace.add("[结果] \"" + truncate(val, 64) + "\" (" + val.length() + " chars)");
            trace.add("[总耗时] " + l1us + "μs — 纯内存热数据");
            cacheHits.incrementAndGet();
            return trace;
        }
        trace.add("[L1 LRU 缓存] 未命中 (" + l1us + "μs)");

        // ── L2 二级缓存 ──
        t0 = System.nanoTime();
        DbValue l2Val = l2Cache.get("default", key);
        t1 = System.nanoTime();
        long l2us = (t1 - t0) / 1000;
        if (l2Val != null) {
            trace.add("[L2 二级缓存] 命中！(" + l2us + "μs)");
            // 回填 L1
            lruCache.put("default", key, l2Val);
            String val = l2Val.asString();
            trace.add("[结果] \"" + truncate(val, 64) + "\" (" + val.length() + " chars)");
            trace.add("[总耗时] " + (l1us + l2us) + "μs — L2 缓存热数据");
            cacheHits.incrementAndGet();
            return trace;
        }
        trace.add("[L2 二级缓存] 未命中 (" + l2us + "μs)");

        // ── MemTable ──
        t0 = System.nanoTime();
        Object obj = dbMap.get(key);
        t1 = System.nanoTime();
        long memUs = (t1 - t0) / 1000;
        if (obj != null) {
            trace.add("[MemTable 内存表] 命中！(" + memUs + "μs) — 刚写入未刷盘");
            String val = objectToString(obj);
            DbValue dbVal = new DbValue(DbValue.Type.STRING, val);
            lruCache.put("default", key, dbVal);
            l2Cache.put("default", key, dbVal);
            trace.add("[结果] \"" + truncate(val, 64) + "\" (" + val.length() + " chars)");
            trace.add("[总耗时] " + (l1us + l2us + memUs) + "μs — MemTable 内存数据");
            cacheMisses.incrementAndGet();
            return trace;
        }
        trace.add("[MemTable 内存表] 未命中 (" + memUs + "μs)");

        // ── HashIndex → SSTable ──
        t0 = System.nanoTime();
        HashIndex.IndexEntry idxEntry = hashIndex.get(key);
        t1 = System.nanoTime();
        long idxUs = (t1 - t0) / 1000;
        if (idxEntry != null) {
            trace.add("[HashIndex 内存索引] 命中！(" + idxUs + "μs) → SSTable: " + idxEntry.sstableFileName
                    + ", 偏移量: 0x" + Long.toHexString(idxEntry.offset));

            // 真实 SSTable 磁盘读取
            long t2 = System.nanoTime();
            try {
                File sstFile = new File(dataDir, idxEntry.sstableFileName);
                if (sstFile.exists()) {
                    String diskVal = SSTable.read(sstFile, key);
                    long t3 = System.nanoTime();
                    long sstUs = (t3 - t2) / 1000;
                    trace.add("[SSTable 磁盘读取] 二分查找 Index Block → seek 到 0x"
                            + Long.toHexString(idxEntry.offset) + " → 读取 (" + sstUs + "μs, 文件大小 "
                            + (sstFile.length() / 1024) + "KB)");

                    if (diskVal != null) {
                        // 回填 L1 + L2 + MemTable
                        DbValue dbVal = new DbValue(DbValue.Type.STRING, diskVal);
                        lruCache.put("default", key, dbVal);
                        l2Cache.put("default", key, dbVal);
                        dbMap.put(key, diskVal);
                        trace.add("[结果] \"" + truncate(diskVal, 64) + "\" (" + diskVal.length() + " chars)");
                        long totalUs = l1us + l2us + memUs + idxUs + sstUs;
                        trace.add("[总耗时] " + totalUs + "μs (" + String.format("%.2f", totalUs / 1000.0) + "ms) — 磁盘冷数据，已回填各层缓存");
                        sstableReads.incrementAndGet();
                        return trace;
                    }
                } else {
                    trace.add("[SSTable 磁盘读取] 文件不存在: " + idxEntry.sstableFileName);
                }
            } catch (IOException e) {
                trace.add("[SSTable 磁盘读取] 错误: " + e.getMessage());
            }
            cacheMisses.incrementAndGet();
        } else {
            trace.add("[HashIndex 内存索引] 未命中 (" + idxUs + "μs)");
        }

        trace.add("[结果] null（键不存在）");
        long totalUs = l1us + l2us + memUs + idxUs;
        trace.add("[总耗时] " + totalUs + "μs");
        return trace;
    }

    /** 截断长字符串用于展示 */
    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...(" + s.length() + " chars)";
    }

    public HashIndex getHashIndex() {
        return hashIndex;
    }

    public File getDataDir() {
        return dataDir;
    }
}
