package nosql.server;

import nosql.common.RespParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 兼容 Redis 规范的 AOF (Append Only File) 持久化与重构管理器
 */
public class AofManager implements Closeable {

    private final File dataDir;
    private final long rotateThreshold; // AOF 自动 Rotate（重写）字节大小阈值
    private final ExecutorService rewriteExecutor; // 后台异步压缩重写线程池

    private File activeFile;
    private FileOutputStream activeFos;

    public AofManager(String dataDirPath, long rotateThreshold) throws IOException {
        this.dataDir = new File(dataDirPath);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        this.rotateThreshold = rotateThreshold;
        this.rewriteExecutor = Executors.newFixedThreadPool(2);
        this.activeFile = new File(dataDir, "active.aof");
        initActiveFile();
    }

    private void initActiveFile() throws IOException {
        this.activeFos = new FileOutputStream(activeFile, true);
    }

    /**
     * 追加写操作指令到 active.aof，且实时同步 fsync 刷盘（对应 Redis appendfsync always 机制）
     */
    public synchronized void append(List<String> cmd) throws IOException {
        if (cmd == null || cmd.isEmpty()) {
            return;
        }

        // 将指令转为标准 RESP Array 协议字节数组
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        RespParser.writeArray(baos, cmd);
        byte[] bytes = baos.toByteArray();

        activeFos.write(bytes);
        activeFos.flush();
        // 强制操作系统刷盘，保证 Crash-Safe
        activeFos.getFD().sync();

        // 检查 AOF 大小是否达到分卷（重写）阈值
        if (activeFile.length() >= rotateThreshold) {
            rotateActiveAof();
        }
    }

    /**
     * 执行 AOF Rotate
     */
    private synchronized void rotateActiveAof() throws IOException {
        activeFos.close();

        // 归档命名格式：aof_segment_[timestamp].aof
        String segmentName = "aof_segment_" + System.currentTimeMillis() + ".aof";
        File segmentFile = new File(dataDir, segmentName);

        if (!activeFile.renameTo(segmentFile)) {
            throw new IOException("Failed to rename active.aof to " + segmentName);
        }

        initActiveFile();

        // 提交多线程异步进行 ZIP 压缩归档任务
        rewriteExecutor.submit(new AofCompressionTask(segmentFile));
    }

    /**
     * 系统启动时，加载并重放 AOF 文件历史指令，重建内存引擎状态
     */
    public synchronized void loadAll(Engine engine, CommandExecutor executor) throws Exception {
        // 查找所有 aof_segment 归档包和最新的 active.aof
        File[] zipFiles = dataDir.listFiles((dir, name) -> name.startsWith("aof_segment_") && name.endsWith(".aof.zip"));
        List<File> segments = new ArrayList<>();
        if (zipFiles != null) {
            segments.addAll(Arrays.asList(zipFiles));
        }

        // 按照时间戳升序排序，确保指令回放时序正确
        segments.sort(Comparator.comparing(File::getName));

        // 1. 先回放已归档压缩的历史 AOF 命令
        for (File zipFile : segments) {
            loadFromZip(zipFile, engine, executor);
        }

        // 2. 再回放当前的 active.aof 中的最新命令
        if (activeFile.exists() && activeFile.length() > 0) {
            loadFromAofFile(activeFile, engine, executor);
        }
    }

    private void loadFromZip(File zipFile, Engine engine, CommandExecutor executor) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry = zis.getNextEntry();
            if (entry != null) {
                BufferedInputStream bis = new BufferedInputStream(zis) {
                    @Override
                    public void close() throws IOException {
                        // 拦截 close，防止流被意外提前关闭
                    }
                };
                int cmdCount = 0;
                int errorCount = 0;
                while (true) {
                    try {
                        List<String> cmd = RespParser.readRequest(bis);
                        if (cmd == null || cmd.isEmpty()) {
                            break;
                        }
                        executor.execute(cmd, engine);
                        cmdCount++;
                    } catch (EOFException e) {
                        break;
                    } catch (Exception e) {
                        // 跳过损坏的 AOF 条目，继续恢复后续有效命令
                        errorCount++;
                        System.err.println("[AOF] Skipping corrupted entry in " +
                                zipFile.getName() + ": " + e.getMessage());
                        if (errorCount > 100) {
                            System.err.println("[AOF] Too many errors in " + zipFile.getName() +
                                    ", stopping recovery of this segment");
                            break;
                        }
                    }
                }
                if (errorCount > 0) {
                    System.out.println("[AOF] Recovered " + cmdCount + " commands from " +
                            zipFile.getName() + " (" + errorCount + " corrupted entries skipped)");
                }
            }
        }
    }

    private void loadFromAofFile(File aofFile, Engine engine, CommandExecutor executor) throws Exception {
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(aofFile))) {
            int cmdCount = 0;
            int errorCount = 0;
            while (true) {
                try {
                    List<String> cmd = RespParser.readRequest(bis);
                    if (cmd == null || cmd.isEmpty()) {
                        break;
                    }
                    executor.execute(cmd, engine);
                    cmdCount++;
                } catch (EOFException e) {
                    break;
                } catch (Exception e) {
                    // 跳过损坏的条目或截断的尾部
                    errorCount++;
                    System.err.println("[AOF] Skipping corrupted entry in active.aof: " + e.getMessage());
                    if (errorCount > 50) {
                        System.err.println("[AOF] Too many errors in active.aof, stopping recovery");
                        break;
                    }
                }
            }
            if (errorCount > 0) {
                System.out.println("[AOF] Recovered " + cmdCount + " commands from active.aof (" +
                        errorCount + " corrupted entries skipped)");
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (activeFos != null) {
            activeFos.close();
            activeFos = null;
        }
        rewriteExecutor.shutdown();
    }

    /**
     * AOF 指令重放处理器接口
     */
    public interface CommandExecutor {
        void execute(List<String> cmd, Engine engine) throws Exception;
    }

    /**
     * 负责将 AOF 段文件压缩归档并删除原文件的后台任务
     */
    private static class AofCompressionTask implements Runnable {
        private final File fileToCompress;

        public AofCompressionTask(File file) {
            this.fileToCompress = file;
        }

        @Override
        public void run() {
            File zipFile = new File(fileToCompress.getParent(), fileToCompress.getName() + ".zip");
            try (
                ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)));
                BufferedInputStream bis = new BufferedInputStream(new FileInputStream(fileToCompress))
            ) {
                zos.putNextEntry(new ZipEntry(fileToCompress.getName()));
                byte[] buffer = new byte[4096];
                int len;
                while ((len = bis.read(buffer)) != -1) {
                    zos.write(buffer, 0, len);
                }
                zos.closeEntry();
                zos.flush();
            } catch (IOException e) {
                System.err.println("Failed to compress AOF segment " + fileToCompress.getName() + ": " + e.getMessage());
                return;
            }

            // 压缩完成后物理删除原 AOF 文件
            try {
                Files.delete(fileToCompress.toPath());
            } catch (IOException e) {
                System.err.println("Failed to delete raw AOF after compression " + fileToCompress.getName() + ": " + e.getMessage());
            }
        }
    }
}
