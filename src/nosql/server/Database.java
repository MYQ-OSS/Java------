package nosql.server;

import nosql.common.DbValue;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * 数据库业务中央管理器，整合 Redis 兼容 Engine 与 AOF 持久化存储
 */
public class Database implements AutoCloseable {

    private final Engine engine;
    private final AofManager aofManager;
    private ReplicationListener replicationListener;
    private final java.util.concurrent.atomic.AtomicLong totalCommands = new java.util.concurrent.atomic.AtomicLong(0);
    private String clusterRole = "STANDALONE";

    public synchronized void setClusterRole(String role) {
        this.clusterRole = role;
    }

    public synchronized String getClusterRole() {
        return this.clusterRole;
    }

    public interface ReplicationListener {
        void onReplicate(List<String> cmd);
    }

    public Database(String dbDir, long rotateThreshold) throws Exception {
        this.engine = new Engine();
        
        File dir = new File(dbDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        
        this.aofManager = new AofManager(dbDir, rotateThreshold);
        
        // 重启恢复：扫描并重放 AOF 文件中的所有历史命令
        recover();
    }

    private void recover() throws Exception {
        System.out.println("[Database] Loading AOF persistence files...");
        aofManager.loadAll(this.engine, this::executeLocal);
        System.out.println("[Database] Recovery complete. DB Size: " + engine.dbSize() + " keys.");
    }

    public void setReplicationListener(ReplicationListener listener) {
        this.replicationListener = listener;
    }

    /**
     * 【只在本地执行指令（用于 AOF 恢复或 Slave 接收复制同步）】
     */
    public void executeLocal(List<String> cmd, Engine engine) throws Exception {
        if (cmd == null || cmd.isEmpty()) {
            return;
        }
        String action = cmd.get(0).toUpperCase();
        switch (action) {
            case "SET":
                if (cmd.size() >= 3) {
                    engine.set(cmd.get(1), cmd.get(2));
                    if (cmd.size() >= 5 && "EX".equalsIgnoreCase(cmd.get(3))) {
                        engine.expire(cmd.get(1), Integer.parseInt(cmd.get(4)));
                    }
                }
                break;
            case "DEL":
                for (int i = 1; i < cmd.size(); i++) {
                    engine.delete(cmd.get(i));
                }
                break;
            case "EXPIRE":
                if (cmd.size() >= 3) {
                    engine.expire(cmd.get(1), Integer.parseInt(cmd.get(2)));
                }
                break;
            case "INCR":
                if (cmd.size() >= 2) {
                    engine.incr(cmd.get(1));
                }
                break;
            case "DECR":
                if (cmd.size() >= 2) {
                    engine.decr(cmd.get(1));
                }
                break;
            case "HSET":
                if (cmd.size() >= 4) {
                    engine.hset(cmd.get(1), cmd.get(2), cmd.get(3));
                }
                break;
            case "HDEL":
                if (cmd.size() >= 3) {
                    engine.hdel(cmd.get(1), cmd.get(2));
                }
                break;
            case "LPUSH":
                if (cmd.size() >= 3) {
                    engine.lpush(cmd.get(1), cmd.get(2));
                }
                break;
            case "RPUSH":
                if (cmd.size() >= 3) {
                    engine.rpush(cmd.get(1), cmd.get(2));
                }
                break;
            case "LPOP":
                if (cmd.size() >= 2) {
                    engine.lpop(cmd.get(1));
                }
                break;
            case "RPOP":
                if (cmd.size() >= 2) {
                    engine.rpop(cmd.get(1));
                }
                break;
            case "SADD":
                if (cmd.size() >= 3) {
                    engine.sadd(cmd.get(1), cmd.get(2));
                }
                break;
            case "SREM":
                if (cmd.size() >= 3) {
                    engine.srem(cmd.get(1), cmd.get(2));
                }
                break;
            case "FLUSHALL":
                engine.clear();
                break;
        }
    }

    /**
     * 【主入口：执行客户端命令】
     * 写指令：AOF 追加 ➔ 本地执行 ➔ 集群广播
     * 读指令：直接本地执行并返回结果
     */
    public synchronized Object execute(List<String> cmd, boolean replicate) throws Exception {
        if (cmd == null || cmd.isEmpty()) {
            return null;
        }
        String action = cmd.get(0).toUpperCase();
        if (!"INFO".equals(action) && !"PING".equals(action)) {
            totalCommands.incrementAndGet();
        }
        boolean isWrite = isWriteCommand(action);

        // 1. 如果是写指令，先写 AOF 追加日志
        if (isWrite && replicate) {
            aofManager.append(cmd);
        }

        // 2. 执行内存数据库动作
        Object result = dispatchCommand(action, cmd);

        // 3. 如果是写指令且当前是 Leader (replicate=true)，触发主从复制广播
        if (isWrite && replicate && replicationListener != null) {
            replicationListener.onReplicate(cmd);
        }

        return result;
    }

    private boolean isWriteCommand(String action) {
        switch (action) {
            case "SET":
            case "DEL":
            case "EXPIRE":
            case "INCR":
            case "DECR":
            case "HSET":
            case "HDEL":
            case "LPUSH":
            case "RPUSH":
            case "LPOP":
            case "RPOP":
            case "SADD":
            case "SREM":
            case "FLUSHALL":
                return true;
            default:
                return false;
        }
    }

    private Object dispatchCommand(String action, List<String> cmd) throws Exception {
        switch (action) {
            case "INFO":
                long totalMem = Runtime.getRuntime().totalMemory();
                long freeMem = Runtime.getRuntime().freeMemory();
                long usedMem = totalMem - freeMem;
                return "used_memory:" + usedMem + "\r\n" +
                       "total_memory:" + totalMem + "\r\n" +
                       "total_commands_processed:" + totalCommands.get() + "\r\n" +
                       "role:" + getClusterRole() + "\r\n" +
                       "db_keys:" + engine.dbSize() + "\r\n";
            case "PING":
                return "PONG";
            case "SET":
                if (cmd.size() < 3) throw new IllegalArgumentException("ERR wrong number of arguments for 'set' command");
                engine.set(cmd.get(1), cmd.get(2));
                if (cmd.size() >= 5 && "EX".equalsIgnoreCase(cmd.get(3))) {
                    engine.expire(cmd.get(1), Integer.parseInt(cmd.get(4)));
                }
                return "OK";
            case "GET":
                if (cmd.size() < 2) throw new IllegalArgumentException("ERR wrong number of arguments for 'get' command");
                return engine.get(cmd.get(1));
            case "DEL":
                if (cmd.size() < 2) throw new IllegalArgumentException("ERR wrong number of arguments for 'del' command");
                long deleted = 0;
                for (int i = 1; i < cmd.size(); i++) {
                    if (engine.exists(cmd.get(i))) {
                        engine.delete(cmd.get(i));
                        deleted++;
                    }
                }
                return deleted;
            case "EXISTS":
                if (cmd.size() < 2) throw new IllegalArgumentException("ERR wrong number of arguments for 'exists' command");
                long count = 0;
                for (int i = 1; i < cmd.size(); i++) {
                    if (engine.exists(cmd.get(i))) count++;
                }
                return count;
            case "EXPIRE":
                if (cmd.size() < 3) throw new IllegalArgumentException("ERR wrong number of arguments for 'expire' command");
                return engine.expire(cmd.get(1), Integer.parseInt(cmd.get(2))) ? 1L : 0L;
            case "TTL":
                if (cmd.size() < 2) throw new IllegalArgumentException("ERR wrong number of arguments for 'ttl' command");
                return engine.ttl(cmd.get(1));
            case "TYPE":
                if (cmd.size() < 2) throw new IllegalArgumentException("ERR wrong number of arguments for 'type' command");
                return engine.type(cmd.get(1));
            case "INCR":
                if (cmd.size() < 2) throw new IllegalArgumentException("ERR wrong number of arguments for 'incr' command");
                return engine.incr(cmd.get(1));
            case "DECR":
                if (cmd.size() < 2) throw new IllegalArgumentException("ERR wrong number of arguments for 'decr' command");
                return engine.decr(cmd.get(1));
            case "HSET":
                if (cmd.size() < 4) throw new IllegalArgumentException("ERR wrong number of arguments for 'hset' command");
                return (long) engine.hset(cmd.get(1), cmd.get(2), cmd.get(3));
            case "HGET":
                if (cmd.size() < 3) throw new IllegalArgumentException("ERR wrong number of arguments for 'hget' command");
                return engine.hget(cmd.get(1), cmd.get(2));
            case "HDEL":
                if (cmd.size() < 3) throw new IllegalArgumentException("ERR wrong number of arguments for 'hdel' command");
                return (long) engine.hdel(cmd.get(1), cmd.get(2));
            case "HGETALL":
                if (cmd.size() < 2) throw new IllegalArgumentException("ERR wrong number of arguments for 'hgetall' command");
                Map<String, String> map = engine.hgetall(cmd.get(1));
                List<String> list = new ArrayList<>();
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    list.add(entry.getKey());
                    list.add(entry.getValue());
                }
                return list;
            case "LPUSH":
                if (cmd.size() < 3) throw new IllegalArgumentException("ERR wrong number of arguments for 'lpush' command");
                return (long) engine.lpush(cmd.get(1), cmd.get(2));
            case "RPUSH":
                if (cmd.size() < 3) throw new IllegalArgumentException("ERR wrong number of arguments for 'rpush' command");
                return (long) engine.rpush(cmd.get(1), cmd.get(2));
            case "LPOP":
                if (cmd.size() < 2) throw new IllegalArgumentException("ERR wrong number of arguments for 'lpop' command");
                return engine.lpop(cmd.get(1));
            case "RPOP":
                if (cmd.size() < 2) throw new IllegalArgumentException("ERR wrong number of arguments for 'rpop' command");
                return engine.rpop(cmd.get(1));
            case "LRANGE":
                if (cmd.size() < 4) throw new IllegalArgumentException("ERR wrong number of arguments for 'lrange' command");
                return engine.lrange(cmd.get(1), Integer.parseInt(cmd.get(2)), Integer.parseInt(cmd.get(3)));
            case "SADD":
                if (cmd.size() < 3) throw new IllegalArgumentException("ERR wrong number of arguments for 'sadd' command");
                return (long) engine.sadd(cmd.get(1), cmd.get(2));
            case "SREM":
                if (cmd.size() < 3) throw new IllegalArgumentException("ERR wrong number of arguments for 'srem' command");
                return (long) engine.srem(cmd.get(1), cmd.get(2));
            case "SMEMBERS":
                if (cmd.size() < 2) throw new IllegalArgumentException("ERR wrong number of arguments for 'smembers' command");
                return new ArrayList<>(engine.smembers(cmd.get(1)));
            case "SISMEMBER":
                if (cmd.size() < 3) throw new IllegalArgumentException("ERR wrong number of arguments for 'sismember' command");
                return (long) engine.sismember(cmd.get(1), cmd.get(2));
            case "DBSIZE":
                return (long) engine.dbSize();
            case "KEYS":
                return new ArrayList<>(engine.keys());
            case "FLUSHALL":
                engine.clear();
                return "OK";
            default:
                throw new UnsupportedOperationException("ERR unknown command '" + action + "'");
        }
    }

    public Engine getEngine() {
        return engine;
    }

    @Override
    public synchronized void close() throws Exception {
        engine.shutdown();
        aofManager.close();
    }
}
