package nosql.cluster;

import nosql.server.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集群管理器单元测试
 * 对应指导书 2.2.2.2 动态角色选举（组队必做）
 * 对应测试用例 TC-05：集群选主
 */
@DisplayName("集群管理器 (ClusterManager)")
class ClusterManagerTest {

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = resources.size() - 1; i >= 0; i--) {
            try { resources.get(i).close(); } catch (Exception ignored) {}
        }
        resources.clear();
    }

    private Database openDb(Path tempDir) throws Exception {
        Database db = new Database(tempDir.toString(), 10 * 1024 * 1024);
        resources.add(db);
        return db;
    }

    private ClusterManager openCm(String nodeId, int port, String peers, Database db) {
        ClusterManager cm = new ClusterManager(nodeId, port, peers, db);
        resources.add(cm);
        return cm;
    }

    @Test
    @DisplayName("单节点启动时 peers 为空，isWritable 返回 true")
    void testSingleNodeIsWritable(@TempDir Path tempDir) throws Exception {
        Database db = openDb(tempDir);
        ClusterManager cm = openCm("node_1", 8081, "", db);
        assertTrue(cm.isWritable());
        assertEquals(ClusterManager.Role.FOLLOWER, cm.getCurrentRole());
    }

    @Test
    @DisplayName("单节点模式下初始角色为 FOLLOWER")
    void testInitialRole(@TempDir Path tempDir) throws Exception {
        Database db = openDb(tempDir);
        ClusterManager cm = openCm("node_1", 8081, "", db);
        assertEquals(ClusterManager.Role.FOLLOWER, cm.getCurrentRole());
        assertNull(cm.getLeaderId());
    }

    @Test
    @DisplayName("收到更大 term → 降级为 FOLLOWER 并投票")
    void testHigherTermDowngrade(@TempDir Path tempDir) throws Exception {
        Database db = openDb(tempDir);
        ClusterManager cm = openCm("node_1", 8081, "", db);
        List<String> resp = cm.handleElectionPacket(
                List.of("CLUSTER_ELECT", "REQUEST_VOTE", "5", "node_2"));
        assertEquals("VOTE_GRANTED", resp.get(0));
        assertEquals("5", resp.get(1));
    }

    @Test
    @DisplayName("同 term 未投票 → 投票给候选者")
    void testVoteForCandidate(@TempDir Path tempDir) throws Exception {
        Database db = openDb(tempDir);
        ClusterManager cm = openCm("node_1", 8081, "", db);
        List<String> resp = cm.handleElectionPacket(
                List.of("CLUSTER_ELECT", "REQUEST_VOTE", "1", "node_2"));
        assertEquals("VOTE_GRANTED", resp.get(0));
    }

    @Test
    @DisplayName("同 term 已投别人 → 拒绝")
    void testRejectDuplicateVote(@TempDir Path tempDir) throws Exception {
        Database db = openDb(tempDir);
        ClusterManager cm = openCm("node_1", 8081, "", db);
        cm.handleElectionPacket(List.of("CLUSTER_ELECT", "REQUEST_VOTE", "1", "node_2"));
        List<String> resp = cm.handleElectionPacket(
                List.of("CLUSTER_ELECT", "REQUEST_VOTE", "1", "node_3"));
        assertEquals("VOTE_REJECTED", resp.get(0));
    }

    @Test
    @DisplayName("term 更小 → 拒绝")
    void testRejectLowerTerm(@TempDir Path tempDir) throws Exception {
        Database db = openDb(tempDir);
        ClusterManager cm = openCm("node_1", 8081, "", db);
        cm.handleElectionPacket(List.of("CLUSTER_ELECT", "REQUEST_VOTE", "5", "node_2"));
        List<String> resp = cm.handleElectionPacket(
                List.of("CLUSTER_ELECT", "REQUEST_VOTE", "3", "node_3"));
        assertEquals("VOTE_REJECTED", resp.get(0));
    }

    @Test
    @DisplayName("收到心跳后设置为 FOLLOWER (对应冲突三)")
    void testHeartbeatMakesFollower(@TempDir Path tempDir) throws Exception {
        Database db = openDb(tempDir);
        ClusterManager cm = openCm("node_1", 8081, "", db);
        cm.handleHeartbeatPacket(List.of("CLUSTER_HEARTBEAT", "node_2", "2"));
        assertEquals(ClusterManager.Role.FOLLOWER, cm.getCurrentRole());
        assertEquals("node_2", cm.getLeaderId());
    }

    @Test
    @DisplayName("term 更小的心跳被忽略 (旧 Leader 拒绝)")
    void testIgnoreLowerTermHeartbeat(@TempDir Path tempDir) throws Exception {
        Database db = openDb(tempDir);
        ClusterManager cm = openCm("node_1", 8081, "", db);
        cm.handleHeartbeatPacket(List.of("CLUSTER_HEARTBEAT", "leader_new", "5"));
        cm.handleHeartbeatPacket(List.of("CLUSTER_HEARTBEAT", "leader_old", "1"));
        assertEquals("leader_new", cm.getLeaderId());
    }

    @Test
    @DisplayName("正确执行复制命令")
    void testHandleReplication(@TempDir Path tempDir) throws Exception {
        Database db = openDb(tempDir);
        ClusterManager cm = openCm("node_1", 8081, "", db);
        cm.handleReplicationPacket(List.of("CLUSTER_REPLICATE", "SET", "rk", "rv"));
        assertEquals("rv", db.execute(List.of("GET", "rk"), true));
    }

    @Test
    @DisplayName("handleSyncJoinRequest 返回全量快照")
    void testHandleSyncJoin(@TempDir Path tempDir) throws Exception {
        Database db = openDb(tempDir);
        ClusterManager cm = openCm("node_1", 8081, "", db);
        db.execute(List.of("SET", "k1", "v1"), true);
        db.execute(List.of("CREATE", "COLLECTION", "test"), true);
        List<String> snapshot = cm.handleSyncJoinRequest();
        assertNotNull(snapshot);
        assertFalse(snapshot.isEmpty());
    }

    @Test
    @DisplayName("close: 正常关闭不抛异常")
    void testClose(@TempDir Path tempDir) throws Exception {
        Database db = openDb(tempDir);
        ClusterManager cm = openCm("node_1", 8081, "", db);
        cm.close();
        db.close();
    }

    @Test
    @DisplayName("parsePeers: 过滤自身节点")
    void testPeersParsing(@TempDir Path tempDir) throws Exception {
        Database db = openDb(tempDir);
        ClusterManager cm = openCm("node_1", 8081,
                "node_1=127.0.0.1:8081,node_2=127.0.0.1:8082,node_3=127.0.0.1:8083", db);
        // 集群 + FOLLOWER → 不可写
        assertFalse(cm.isWritable());
    }
}
