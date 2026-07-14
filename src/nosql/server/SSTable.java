package nosql.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * LSMT 存储引擎核心：磁盘上的有序字符串表 (Sorted String Table)
 *
 * 文件格式:
 * ┌─────────────────────────────────────┐
 * │  Data Block (N 条记录)              │
 * │  每条: keyLen(4B)+key+type(1B)+     │
 * │        valLen(4B)+val               │
 * ├─────────────────────────────────────┤
 * │  Index Block (M 条)                 │
 * │  每条: keyLen(2B)+key+offset(8B)    │
 * ├─────────────────────────────────────┤
 * │  Footer: indexOffset(8B)+           │
 * │          entryCount(4B)+magic(2B)   │
 * └─────────────────────────────────────┘
 *
 * 读取时通过二分搜索 Index Block 定位 key，然后 RandomAccessFile.seek 到精确偏移量读取 value
 * 满足课程设计 2.2.1.5(2) LSMT 存储结构要求
 */
public class SSTable {

    public static final short MAGIC = (short) 0x53A1; // 'S'+'T'
    private static final int INDEX_SAMPLE_INTERVAL = 1; // 每条 key 都建索引（全索引）

    /**
     * SSTable 中一条 key-value 记录的磁盘表示
     */
    public static class Entry {
        public final String key;
        public final byte type;     // 0=string, 1=list, 2=set, 3=hash
        public final byte[] value;

        public Entry(String key, byte type, byte[] value) {
            this.key = key;
            this.type = type;
            this.value = value;
        }
    }

    /**
     * 将内存中的全部数据刷写为一个 SSTable 文件，同时建立哈希索引
     * @param dir    数据目录
     * @param data   待持久化的内存数据 (key -> Object: String/ConcurrentLinkedDeque/Set/ConcurrentHashMap)
     * @param engine Engine 引用（用于读取复杂类型值）
     * @return 生成的 SSTable 文件
     */
    @SuppressWarnings("unchecked")
    public static File flush(File dir, Map<String, Object> data, Engine engine,
                             HashIndex hashIndex) throws IOException {
        if (data.isEmpty()) return null;

        long timestamp = System.currentTimeMillis();
        File sstableFile = new File(dir, "sstable_" + timestamp + ".sst");

        // 1. 收集所有 key 并排序
        List<String> sortedKeys = new ArrayList<>(data.keySet());
        Collections.sort(sortedKeys);

        // 2. 建立临时的偏移量索引（用于写入 Index Block）
        Map<String, Long> offsetMap = new LinkedHashMap<>();

        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(sstableFile)))) {

            // === Data Block ===
            long dataStartOffset = 0; // Data Block 从文件开头开始
            for (String key : sortedKeys) {
                Object obj = data.get(key);
                byte type;
                byte[] valueBytes;

                if (obj instanceof String) {
                    type = 0;
                    valueBytes = ((String) obj).getBytes(StandardCharsets.UTF_8);
                } else if (obj instanceof java.util.concurrent.ConcurrentLinkedDeque) {
                    type = 1;
                    // 序列化 List: 元素用  分隔
                    java.util.concurrent.ConcurrentLinkedDeque<String> deque =
                            (java.util.concurrent.ConcurrentLinkedDeque<String>) obj;
                    StringBuilder sb = new StringBuilder();
                    for (String item : deque) {
                        if (sb.length() > 0) sb.append('');
                        sb.append(item);
                    }
                    valueBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                } else if (obj instanceof Set) {
                    type = 2;
                    Set<String> set = (Set<String>) obj;
                    StringBuilder sb = new StringBuilder();
                    for (String item : set) {
                        if (sb.length() > 0) sb.append('');
                        sb.append(item);
                    }
                    valueBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                } else if (obj instanceof java.util.concurrent.ConcurrentHashMap) {
                    type = 3;
                    java.util.concurrent.ConcurrentHashMap<String, String> map =
                            (java.util.concurrent.ConcurrentHashMap<String, String>) obj;
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, String> e : map.entrySet()) {
                        if (sb.length() > 0) sb.append('');
                        sb.append(e.getKey()).append('').append(e.getValue());
                    }
                    valueBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                } else {
                    continue; // 跳过无法识别的类型
                }

                // 记录偏移量
                offsetMap.put(key, (long) dos.size());

                // 写入 Data Block 记录
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                dos.writeInt(keyBytes.length);
                dos.write(keyBytes);
                dos.writeByte(type);
                dos.writeInt(valueBytes.length);
                dos.write(valueBytes);
            }

            // === Index Block ===
            long indexOffset = (long) dos.size();
            int indexCount = offsetMap.size();
            for (Map.Entry<String, Long> entry : offsetMap.entrySet()) {
                byte[] keyBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
                dos.writeShort(keyBytes.length);
                dos.write(keyBytes);
                dos.writeLong(entry.getValue());
            }

            // === Footer ===
            dos.writeLong(indexOffset);
            dos.writeInt(indexCount);
            dos.writeShort(MAGIC);
            dos.flush();
        }

        // 3. 将新生成的 SSTable 中的所有 key 注册到哈希索引
        for (Map.Entry<String, Long> entry : offsetMap.entrySet()) {
            hashIndex.put(entry.getKey(), sstableFile.getName(), entry.getValue());
        }

        System.out.println("[SSTable] Flushed " + sortedKeys.size() +
                " keys to " + sstableFile.getName() + " (" + sstableFile.length() + " bytes)");

        return sstableFile;
    }

    /**
     * 从 SSTable 中通过二分搜索读取单个 key 的值
     * @param sstableFile SSTable 文件
     * @param key         要查询的 key
     * @return 反序列化后的值（String），如果不存在返回 null
     */
    public static String read(File sstableFile, String key) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(sstableFile, "r")) {
            // 1. 读取 Footer
            long fileLen = raf.length();
            if (fileLen < 14) return null; // 最小: indexOffset(8B) + entryCount(4B) + magic(2B)

            raf.seek(fileLen - 14);
            long indexOffset = raf.readLong();
            int entryCount = raf.readInt();
            short magic = raf.readShort();
            if (magic != MAGIC) {
                throw new IOException("Corrupted SSTable: invalid magic in " + sstableFile.getName());
            }

            // 2. 二分搜索 Index Block
            long low = 0;
            long high = entryCount - 1;
            Long targetOffset = null;

            raf.seek(indexOffset);
            // 先读入完整 Index Block 以便二分查找
            List<IndexEntry> indexEntries = new ArrayList<>(entryCount);
            for (int i = 0; i < entryCount; i++) {
                int keyLen = raf.readUnsignedShort();
                byte[] keyBytes = new byte[keyLen];
                raf.readFully(keyBytes);
                long offset = raf.readLong();
                indexEntries.add(new IndexEntry(new String(keyBytes, StandardCharsets.UTF_8), offset));
            }

            // 二分搜索
            int foundIdx = Collections.binarySearch(indexEntries,
                    new IndexEntry(key, 0),
                    Comparator.comparing(e -> e.key));
            if (foundIdx < 0) return null;
            targetOffset = indexEntries.get(foundIdx).offset;

            // 3. Seek 到 Data Block 中的精确偏移并读取
            raf.seek(targetOffset);
            int keyLen = raf.readInt();
            byte[] keyBytes = new byte[keyLen];
            raf.readFully(keyBytes);
            String readKey = new String(keyBytes, StandardCharsets.UTF_8);
            byte type = raf.readByte();
            int valLen = raf.readInt();
            byte[] valBytes = new byte[valLen];
            raf.readFully(valBytes);

            return new String(valBytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * 从 SSTable 读取所有 Entry（用于压缩合并）
     */
    public static List<Entry> readAll(File sstableFile) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(sstableFile, "r")) {
            long fileLen = raf.length();
            if (fileLen < 14) return entries;

            raf.seek(fileLen - 14);
            long indexOffset = raf.readLong();
            int entryCount = raf.readInt();
            short magic = raf.readShort();
            if (magic != MAGIC) {
                throw new IOException("Corrupted SSTable: " + sstableFile.getName());
            }

            // 从文件开头顺序读取 Data Block（就是 entryCount 条记录）
            raf.seek(0);
            for (int i = 0; i < entryCount; i++) {
                int keyLen = raf.readInt();
                byte[] keyBytes = new byte[keyLen];
                raf.readFully(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                byte type = raf.readByte();
                int valLen = raf.readInt();
                byte[] valBytes = new byte[valLen];
                raf.readFully(valBytes);
                entries.add(new Entry(key, type, valBytes));
            }
        }
        return entries;
    }

    /**
     * 列出数据目录中所有 SSTable 文件，按时间戳排序（最旧优先 → 最新在最后）
     */
    public static List<File> listSSTables(File dataDir) {
        File[] files = dataDir.listFiles((dir, name) -> name.startsWith("sstable_") && name.endsWith(".sst"));
        if (files == null) return Collections.emptyList();
        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort(Comparator.comparing(File::getName));
        return list;
    }

    /**
     * 压缩合并多个 SSTable 为一个，消除重复 key（保留最新的值）
     * @param dir         数据目录
     * @param sourceFiles 待合并的 SSTable 文件列表
     * @param hashIndex   哈希索引（会更新）
     * @return 合并后的新 SSTable，如果源列表为空则返回 null
     */
    public static File compact(File dir, List<File> sourceFiles, HashIndex hashIndex) throws IOException {
        if (sourceFiles == null || sourceFiles.isEmpty()) return null;

        // 按文件名排序（时间戳顺序：旧→新），后读的覆盖先读的
        sourceFiles.sort(Comparator.comparing(File::getName));

        // 读取所有 SSTable 的所有 Entry，后出现的覆盖先出现的
        Map<String, Entry> merged = new LinkedHashMap<>();
        for (File f : sourceFiles) {
            List<Entry> entries = readAll(f);
            for (Entry e : entries) {
                merged.put(e.key, e); // 后来的覆盖
            }
        }

        if (merged.isEmpty()) {
            // 删除空 SSTable
            for (File f : sourceFiles) {
                hashIndex.removeBySSTable(f.getName());
                f.delete();
            }
            return null;
        }

        // 排序所有 key
        List<String> sortedKeys = new ArrayList<>(merged.keySet());
        Collections.sort(sortedKeys);

        long timestamp = System.currentTimeMillis();
        File compactFile = new File(dir, "sstable_" + timestamp + ".sst");
        // 防止时间戳碰撞：如果同名文件已存在且不是源文件，递增时间戳
        while (compactFile.exists()) {
            timestamp++;
            compactFile = new File(dir, "sstable_" + timestamp + ".sst");
        }

        Map<String, Long> offsetMap = new LinkedHashMap<>();

        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(compactFile)))) {

            for (String key : sortedKeys) {
                Entry e = merged.get(key);
                byte[] keyBytes = e.key.getBytes(StandardCharsets.UTF_8);
                offsetMap.put(key, (long) dos.size());
                dos.writeInt(keyBytes.length);
                dos.write(keyBytes);
                dos.writeByte(e.type);
                dos.writeInt(e.value.length);
                dos.write(e.value);
            }

            long indexOffset = dos.size();
            for (Map.Entry<String, Long> entry : offsetMap.entrySet()) {
                byte[] keyBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
                dos.writeShort(keyBytes.length);
                dos.write(keyBytes);
                dos.writeLong(entry.getValue());
            }

            dos.writeLong(indexOffset);
            dos.writeInt(offsetMap.size());
            dos.writeShort(MAGIC);
            dos.flush();
        }

        // 更新哈希索引：删除旧 SSTable 的条目，添加新的
        for (File f : sourceFiles) {
            hashIndex.removeBySSTable(f.getName());
            f.delete();
        }
        for (Map.Entry<String, Long> entry : offsetMap.entrySet()) {
            hashIndex.put(entry.getKey(), compactFile.getName(), entry.getValue());
        }

        System.out.println("[SSTable] Compacted " + sourceFiles.size() + " files → " +
                compactFile.getName() + " (" + merged.size() + " keys, " +
                compactFile.length() + " bytes)");

        return compactFile;
    }

    // 内部类：索引条目
    private static class IndexEntry {
        final String key;
        final long offset;
        IndexEntry(String key, long offset) {
            this.key = key;
            this.offset = offset;
        }
    }
}
