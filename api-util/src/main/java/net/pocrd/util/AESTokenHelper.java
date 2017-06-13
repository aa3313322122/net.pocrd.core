package net.pocrd.util;

import net.pocrd.annotation.NotThreadSafe;
import net.pocrd.define.ConstField;
import net.pocrd.entity.CallerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

/**
 * 处理使用AES秘钥加密用户信息而产生的token
 *
 * @author rendong
 */
@NotThreadSafe
public class AESTokenHelper {
    private static final Logger logger                   = LoggerFactory.getLogger(AESTokenHelper.class);
    private static final short  DEVICE_TOKEN_VERSION_1_0 = 10;
    private AesHelper aes;

    public AESTokenHelper(String pwd) {
        aes = new AesHelper(Base64Util.decode(pwd), null);
    }

    public AESTokenHelper(AesHelper helper) {
        aes = helper;
    }

    /**
     * 解析调用者信息
     */
    private CallerInfo parse(byte[] token) {
        DataInputStream dis = null;
        CallerInfo caller = null;
        try {
            dis = new DataInputStream(new ByteArrayInputStream(aes.decrypt(token)));
            short tokenVersion = dis.readShort(); // token version for backward compliance
            if (tokenVersion != DEVICE_TOKEN_VERSION_1_0) {
                logger.error("token version mismatch!");
                return null;
            }
            caller = new CallerInfo();
            caller.expire = dis.readLong();
            caller.securityLevel = dis.readInt();
            caller.appid = dis.readInt();
            caller.deviceId = dis.readLong();
            caller.uid = dis.readLong();
            short len = dis.readShort();
            if (len > 0) {
                caller.key = new byte[len];
                if (len != dis.read(caller.key)) {
                    return null;
                }
            }
            if (dis.available() > 0) {
                len = dis.readByte();
                if (len > 0) {
                    byte[] bs = new byte[len];
                    if (len != dis.read(bs)) {
                        return null;
                    }
                    caller.oauthid = new String(bs, ConstField.UTF8);
                }
            }
            if (dis.available() > 0) {
                len = dis.readByte();
                if (len > 0) {
                    byte[] bs = new byte[len];
                    if (len != dis.read(bs)) {
                        return null;
                    }
                    caller.role = new String(bs, ConstField.UTF8);
                }
            }
            if (dis.available() > 0) {
                return null;
            }
        } catch (Exception e) {
            logger.error("token parse failed.", e);
        } finally {
            if (dis != null) {
                try {
                    dis.close();
                } catch (IOException e) {
                    logger.error("token parse failed.close input stream failed!", e);
                }
            }
        }
        return caller;
    }

    /**
     * 生成用户token
     */
    private byte[] generate(CallerInfo caller) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8);
        DataOutputStream dos = new DataOutputStream(baos);
        byte[] token = null;
        try {
            dos.writeShort(DEVICE_TOKEN_VERSION_1_0);
            dos.writeLong(caller.expire);
            dos.writeInt(caller.securityLevel);
            dos.writeInt(caller.appid);
            dos.writeLong(caller.deviceId);
            dos.writeLong(caller.uid);
            short len = caller.key == null ? 0 : (short)caller.key.length;
            dos.writeShort(len);
            if (caller.key != null) {
                dos.write(caller.key);
            }
            byte[] oauthid = caller.oauthid == null ? null : caller.oauthid.getBytes(ConstField.UTF8);
            if (oauthid != null) {
                dos.writeByte(oauthid.length);
                dos.write(oauthid);
            }
            byte[] roles = caller.role == null ? null : caller.role.getBytes(ConstField.UTF8);
            if (roles != null) {
                dos.writeByte(roles.length);
                dos.write(roles);
            }
            byte[] bs = baos.toByteArray();
            token = aes.encrypt(bs);
        } catch (IOException e) {
            throw new RuntimeException("generator token failed.", e);
        } finally {
            try {
                dos.close();
            } catch (IOException e) {
                logger.error("generator token failed.close output stream failed!", e);
            }
        }
        return token;
    }

    /**
     * 生成token string
     *
     * @param caller
     */
    public String generateToken(CallerInfo caller) {
        return toHax3(caller.securityLevel).append(Base64Util.encodeToString(this.generate(caller))).toString();
    }

    /**
     * 从base64编码的字符串中解析调用者信息
     */
    public CallerInfo parseToken(String token) {
        try {
            return parse(Base64Util.decode(token.substring(3)));
        } catch (Exception e) {
            logger.error("token parse failed.", e);
        }
        return null;
    }

    private static StringBuilder toHax3(int value) {
        StringBuilder sb = new StringBuilder(50);
        sb.append("000");
        for (int i = 2; i >= 0; i--) {
            switch (value % 16) {
                case 0:
                    sb.setCharAt(i, '0');
                    break;
                case 1:
                    sb.setCharAt(i, '1');
                    break;
                case 2:
                    sb.setCharAt(i, '2');
                    break;
                case 3:
                    sb.setCharAt(i, '3');
                    break;
                case 4:
                    sb.setCharAt(i, '4');
                    break;
                case 5:
                    sb.setCharAt(i, '5');
                    break;
                case 6:
                    sb.setCharAt(i, '6');
                    break;
                case 7:
                    sb.setCharAt(i, '7');
                    break;
                case 8:
                    sb.setCharAt(i, '8');
                    break;
                case 9:
                    sb.setCharAt(i, '9');
                    break;
                case 10:
                    sb.setCharAt(i, 'A');
                    break;
                case 11:
                    sb.setCharAt(i, 'B');
                    break;
                case 12:
                    sb.setCharAt(i, 'C');
                    break;
                case 13:
                    sb.setCharAt(i, 'D');
                    break;
                case 14:
                    sb.setCharAt(i, 'E');
                    break;
                case 15:
                    sb.setCharAt(i, 'F');
                    break;
            }
            value = value / 16;
        }
        return sb;
    }
}
