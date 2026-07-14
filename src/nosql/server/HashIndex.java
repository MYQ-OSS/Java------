package nosql.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存哈希索引 — 将 key 映射到 SSTable 文件中的磁盘偏移量
 * 提供 O(1) 磁盘数据定位，满足课程设计 2.2.1.3(1) 索引加速查询要求
 *
 * 索引文件格式 (.idx):
 *   entry_count(4B) + [key_len(2B) + key_bytes + sstable_name_len(2B) + sstable_name_bytes + offset(8B)] * N
 */
public class HashIndex {

    public static class IndexEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String sstableFileName;
        public final long offset;

        public IndexEntry(String sstableFileName, long offset) {
            this.sstableFileName = sstableFileName;
            this.offset = offset;
        }
    }

    private final ConcurrentHashMap<String, IndexEntry> indexMap = new ConcurrentHashMap<>();

    public void put(String key, String sstableFileName, long offset) {
        indexMap.put(key, new IndexEntry(sstableFileName, offset));
    }

    public void putAll(Map<String, IndexEntry> entries) {
        indexMap.putAll(entries);
    }

    public IndexEntry get(String key) {
        return indexMap.get(key);
    }

    public void remove(String key) {
        indexMap.remove(key);
    }

    public void clear() {
        indexMap.clear();
    }

    public int size() {
        return indexMap.size();
    }

    /**
     * 获取当前索引中的所有 key，用于 SSTable 写入时跳过已在磁盘的过时 key
     */
    public boolean containsKey(String key) {
        return indexMap.containsKey(key);
    }

    /**
     * 将哈希索引持久化到 .idx 文件，供重启恢复
     */
    public void saveToFile(File idxFile) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(idxFile)))) {
            dos.writeInt(indexMap.size());
            for (Map.Entry<String, IndexEntry> entry : indexMap.entrySet()) {
                byte[] keyBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] nameBytes = entry.getValue().sstableFileName.getBytes(StandardCharsets.UTF_8);
                dos.writeShort(keyBytes.length);
                dos.write(keyBytes);
                dos.writeShort(nameBytes.length);
                dos.write(nameBytes);
                dos.writeLong(entry.getValue().offset);
            }
            dos.flush();
        }
    }

    /**
     * 从磁盘 .idx 文件恢复哈希索引
     */
    public void loadFromFile(File idxFile) throws IOException {
        if (!idxFile.exists()) return;

        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(idxFile)))) {
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                int keyLen = dis.readUnsignedShort();
                byte[] keyBytes = new byte[keyLen];
                dis.readFully(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);

                int nameLen = dis.readUnsignedShort();
                byte[] nameBytes = new byte[nameLen];
                dis.readFully(nameBytes);
                String fileName = new String(nameBytes, StandardCharsets.UTF_8);

                long offset = dis.readLong();
                indexMap.put(key, new IndexEntry(fileName, offset));
            }
        }
    }

    /**
     * 获取索引中所有 key（用于 KEYS 命令）
     */
    public Set<String> getAllKeys() {
        return new HashSet<>(indexMap.keySet());
    }

    /**
     * 批量删除属于指定 SSTable 文件的所有索引条目（压缩时使用）
     */
    public void removeBySSTable(String sstableFileName) {
        indexMap.entrySet().removeIf(e -> e.getValue().sstableFileName.equals(sstableFileName));
    }
}
