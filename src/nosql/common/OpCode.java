package nosql.common;

/**
 * 数据库操作码定义
 */
public interface OpCode {
    byte PUT = 0x01;
    byte GET = 0x02;
    byte DELETE = 0x03;
    byte BULK_PUT = 0x04;
    byte HEARTBEAT = 0x05;
    byte ERROR = 0x06;
    byte LEADER_ELECT = 0x07;
    byte REPLICATE = 0x08;
    byte SYNC_JOIN = 0x09;
    byte SUCCESS = 0x0A;
}
