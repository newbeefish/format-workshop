package cn.gkkstudio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 腾讯改版 TEA（tc_tea）：16 轮 ECB + 带 salt/padding 的 CBC。
 *
 * <p>QQ 音乐的 ekey（EncV1 / EncV2）用它加密密钥体，移植自开源实现
 * jixunmoe/tc_tea_rust，并用其公开测试向量校验。
 */
final class TcTea {
    private static final int ROUNDS = 16;
    private static final int DELTA = 0x9e3779b9;
    private static final int SALT_LEN = 2;
    private static final int ZERO_LEN = 7;
    private static final int FIXED_PADDING_LEN = 1 + SALT_LEN + ZERO_LEN;

    private TcTea() {
    }

    static int[] parseKey(byte[] key) throws IOException {
        if (key.length != 16) {
            throw new IOException("TC-TEA 密钥必须为 16 字节，实际 " + key.length);
        }
        int[] parsed = new int[4];
        for (int i = 0; i < 4; i++) {
            parsed[i] = readIntBE(key, i * 4);
        }
        return parsed;
    }

    private static int singleRound(int value, int sum, int key1, int key2) {
        int left = (value << 4) + key1;
        int right = (value >>> 5) + key2;
        int mid = sum + value;
        return left ^ mid ^ right;
    }

    static long encryptBlock(long block, int[] key) {
        int y = (int) (block >>> 32);
        int z = (int) block;
        int sum = 0;
        for (int i = 0; i < ROUNDS; i++) {
            sum += DELTA;
            y += singleRound(z, sum, key[0], key[1]);
            z += singleRound(y, sum, key[2], key[3]);
        }
        return ((long) y << 32) | (z & 0xFFFFFFFFL);
    }

    static long decryptBlock(long block, int[] key) {
        int y = (int) (block >>> 32);
        int z = (int) block;
        int sum = DELTA * ROUNDS;
        for (int i = 0; i < ROUNDS; i++) {
            z -= singleRound(y, sum, key[2], key[3]);
            y -= singleRound(z, sum, key[0], key[1]);
            sum -= DELTA;
        }
        return ((long) y << 32) | (z & 0xFFFFFFFFL);
    }

    /** 解密 tc_tea 数据；padding 不正确时返回 null（调用方据此判断格式）。 */
    static byte[] decrypt(byte[] cipher, byte[] keyBytes) {
        try {
            int[] key = parseKey(keyBytes);
            int length = cipher.length;
            if (length < FIXED_PADDING_LEN || (length % 8) != 0) {
                return null;
            }
            byte[] plain = new byte[length];
            long iv1 = 0L;
            long iv2 = 0L;
            for (int offset = 0; offset < length; offset += 8) {
                long cipherBlock = readLongBE(cipher, offset);
                long result = cipherBlock ^ iv2;
                long nextIv2 = decryptBlock(result, key);
                long plainBlock = nextIv2 ^ iv1;
                writeLongBE(plain, offset, plainBlock);
                iv1 = cipherBlock;
                iv2 = nextIv2;
            }
            int padSize = plain[0] & 0b111;
            int start = 1 + padSize + SALT_LEN;
            int end = length - ZERO_LEN;
            if (start < 0 || end < start) {
                return null;
            }
            for (int i = end; i < length; i++) {
                if (plain[i] != 0) {
                    return null;
                }
            }
            byte[] out = new byte[end - start];
            System.arraycopy(plain, start, out, 0, out.length);
            return out;
        } catch (Exception exception) {
            return null;
        }
    }

    /** 加密（仅供自测；salt 固定）。 */
    static byte[] encrypt(byte[] plain, byte[] keyBytes) throws IOException {
        int[] key = parseKey(keyBytes);
        byte[] salt = {(byte) 0xA5, 0x6E, 0x35, (byte) 0xBC, 0x7C, 0x31, 0x04, 0x55, (byte) 0xA0, (byte) 0xBF};
        int len = FIXED_PADDING_LEN + plain.length;
        int padLen = (8 - (len & 0b111)) & 0b111;
        int outLen = len + padLen;
        byte[] cipher = new byte[outLen];
        int headerLen = 1 + padLen + SALT_LEN;

        byte[] header = new byte[16];
        System.arraycopy(salt, 0, header, 0, headerLen);
        int copyToHeader = Math.min(16 - headerLen, plain.length);
        header[0] = (byte) ((header[0] & ~7) | (padLen & 7));
        System.arraycopy(plain, 0, header, headerLen, copyToHeader);
        byte[] rest = new byte[plain.length - copyToHeader];
        System.arraycopy(plain, copyToHeader, rest, 0, rest.length);

        long iv1 = 0L;
        long iv2 = 0L;
        long[] iv = new long[2];
        encryptRound(cipher, 0, header, 0, key, iv);
        encryptRound(cipher, 8, header, 8, key, iv);
        int cipherOffset = 16;
        int restOffset = 0;
        while (restOffset + 8 <= rest.length) {
            encryptRound(cipher, cipherOffset, rest, restOffset, key, iv);
            cipherOffset += 8;
            restOffset += 8;
        }
        if (restOffset < rest.length) {
            byte[] last = new byte[8];
            System.arraycopy(rest, restOffset, last, 0, rest.length - restOffset);
            encryptRound(cipher, cipherOffset, last, 0, key, iv);
        }
        return cipher;
    }

    private static void encryptRound(byte[] cipher, int cipherOffset, byte[] plain, int plainOffset, int[] key, long[] iv) {
        long plainBlock = readLongBE(plain, plainOffset);
        long iv2Next = plainBlock ^ iv[0];
        long result = encryptBlock(iv2Next, key);
        long cipherBlock = result ^ iv[1];
        writeLongBE(cipher, cipherOffset, cipherBlock);
        iv[0] = cipherBlock;
        iv[1] = iv2Next;
    }

    private static int readIntBE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static long readLongBE(byte[] data, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }

    private static void writeLongBE(byte[] data, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            data[offset + i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private TcTea(boolean unused) {
    }
}
