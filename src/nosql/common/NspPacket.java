package nosql.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 自定义 NSP 数据传输协议包
 */
public class NspPacket {
    public static final short MAGIC = 0x2A3F; // 2 字节魔数

    private byte opCode;
    private byte[] body;

    public NspPacket(byte opCode, byte[] body) {
        this.opCode = opCode;
        this.body = body != null ? body : new byte[0];
    }

    public byte getOpCode() {
        return opCode;
    }

    public byte[] getBody() {
        return body;
    }

    /**
     * 从输入流中读取一个完整的包，解决粘包/半包问题
     */
    public static NspPacket readFromStream(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        
        // 1. 读取魔数
        short magic = dis.readShort();
        if (magic != MAGIC) {
            throw new IOException("Invalid protocol magic number: " + Integer.toHexString(magic & 0xFFFF));
        }
        
        // 2. 读取操作码
        byte opCode = dis.readByte();
        
        // 3. 读取包体长度
        int bodyLength = dis.readInt();
        if (bodyLength < 0 || bodyLength > 10 * 1024 * 1024) { // 限制最大包为 10MB
            throw new IOException("Invalid body length: " + bodyLength);
        }
        
        // 4. 读取包体
        byte[] body = new byte[bodyLength];
        dis.readFully(body);
        
        return new NspPacket(opCode, body);
    }

    /**
     * 将包写入输出流
     */
    public void writeToStream(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeShort(MAGIC);
        dos.writeByte(opCode);
        dos.writeInt(body.length);
        dos.write(body);
        dos.flush();
    }
}
