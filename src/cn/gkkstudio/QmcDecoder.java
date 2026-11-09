package cn.gkkstudio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

/**
 * QQ 音乐加密格式（QMC）解密。
 *
 * <p>支持：
 * <ul>
 *   <li>QMC1：.qmc0 .qmc2 .qmc3 .qmcflac .qmcogg（固定掩码表，无需密钥）</li>
 *   <li>QMC2：.mflac .mflac0 .mflach .mgg .mgg0 .mgg1 .mggl
 *       （ekey 内嵌于文件尾部的 QTag / V1 格式）</li>
 * </ul>
 * 新版 musicex 尾部（QQ 音乐 ≥ 19.57）不再内嵌 ekey，需要登录客户端从接口取，
 * 这里会给出明确提示而不是静默失败。
 *
 * <p>算法移植自开源实现 ownlight6/qmc-decoder（QMC1/QMC2）、
 * jixunmoe/tc_tea_rust（TC-TEA），并用其公开测试向量校验。
 */
final class QmcDecoder {
    private static final byte[] QMC1_TABLE = {
            (byte) 0xc3, 0x4a, (byte) 0xd6, (byte) 0xca, (byte) 0x90, 0x67, (byte) 0xf7, 0x52,
            (byte) 0xd8, (byte) 0xa1, 0x66, 0x62, (byte) 0x9f, 0x5b, 0x09, 0x00,
            (byte) 0xc3, 0x5e, (byte) 0x95, 0x23, (byte) 0x9f, 0x13, 0x11, 0x7e,
            (byte) 0xd8, (byte) 0x92, 0x3f, (byte) 0xbc, (byte) 0x90, (byte) 0xbb, 0x74, 0x0e,
            (byte) 0xc3, 0x47, 0x74, 0x3d, (byte) 0x90, (byte) 0xaa, 0x3f, 0x51,
            (byte) 0xd8, (byte) 0xf4, 0x11, (byte) 0x84, (byte) 0x9f, (byte) 0xde, (byte) 0x95, 0x1d,
            (byte) 0xc3, (byte) 0xc6, 0x09, (byte) 0xd5, (byte) 0x9f, (byte) 0xfa, 0x66, (byte) 0xf9,
            (byte) 0xd8, (byte) 0xf0, (byte) 0xf7, (byte) 0xa0, (byte) 0x90, (byte) 0xa1, (byte) 0xd6, (byte) 0xf3,
    };

    private static final byte[] ENCV2_PREFIX = "QQMusic EncV2,Key:".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ENCV2_STAGE1 = "386ZJY!@#*$%^&)(".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ENCV2_STAGE2 = "**#!(#$%&^a1cZ,T".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MUSICEX_MAGIC = "musicex\0".getBytes(StandardCharsets.US_ASCII);

    private static final int RC4_FIRST_SEGMENT = 0x80;
    private static final int RC4_SEGMENT = 0x1400;

    private QmcDecoder() {
    }

    /** QMC 扩展名 → 解密后的真实格式（null 表示不是 QMC）。 */
    static String decryptedExtension(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "qmc0", "qmc3" -> "mp3";
            case "qmc2", "qmcogg", "mgg", "mgg0", "mgg1", "mggl" -> "ogg";
            case "qmcflac", "mflac", "mflac0", "mflach" -> "flac";
            default -> null;
        };
    }

    static boolean isQmc(String extension) {
        return decryptedExtension(extension) != null;
    }

    static boolean isQmc1(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "qmc0", "qmc2", "qmc3", "qmcflac", "qmcogg" -> true;
            default -> false;
        };
    }

    /** 解密到临时文件，返回临时文件路径。 */
    static Path decodeToTemporaryFile(Path source) throws IOException {
        byte[] data = Files.readAllBytes(source);
        String extension = MediaEngine.extension(source);
        Footer footer = detectFooter(data);
        byte[] audio;

        if (isQmc1(extension)) {
            int length = footer.type == FooterType.V1 ? data.length - 4 - footer.keySize : data.length;
            if (length <= 0 || length > data.length) {
                throw new IOException("QMC 文件长度异常");
            }
            audio = new byte[length];
            System.arraycopy(data, 0, audio, 0, length);
            decryptQmc1(audio);
        } else {
            String ekey;
            int audioLength;
            switch (footer.type) {
                case QTAG -> {
                    ekey = footer.ekey;
                    audioLength = (int) footer.audioLength;
                }
                case V1 -> {
                    int keyStart = data.length - 4 - footer.keySize;
                    if (keyStart <= 0) {
                        throw new IOException("QMC2 密钥区长度异常");
                    }
                    byte[] keyBytes = new byte[footer.keySize];
                    System.arraycopy(data, keyStart, keyBytes, 0, footer.keySize);
                    ekey = Base64.getEncoder().encodeToString(keyBytes);
                    audioLength = keyStart;
                }
                case MUSICEX -> {
                    String fetched = QqMusic.fetchEkey(footer.fileName, footer.mid);
                    if (fetched == null) {
                        throw new IOException("QQ 音乐新版 musicex 格式（歌曲 " + footer.songId
                                + "）没有把密钥写进文件，需要在线的 ekey。" + QqMusic.status()
                                + "。请启动并登录 QQ 音乐后重试，或换用旧版客户端重新下载。");
                    }
                    ekey = fetched;
                    audioLength = (int) footer.audioLength;
                }
                default -> throw new IOException("无法识别的 QMC 文件尾部（缺少 QTag / V1 密钥信息）");
            }
            if (audioLength <= 0 || audioLength > data.length) {
                throw new IOException("QMC2 音频区长度异常");
            }
            audio = new byte[audioLength];
            System.arraycopy(data, 0, audio, 0, audioLength);
            byte[] key = parseEkey(ekey);
            if (key.length > 300) {
                decryptQmc2Rc4(audio, key);
            } else {
                decryptQmc2Map(audio, key);
            }
        }

        Path temporary = Files.createTempFile("qmc-decoded-", ".audio");
        Files.write(temporary, audio);
        return temporary;
    }

    // ------------------------------------------------------------------ QMC1

    private static int qmc1Mask(int offset) {
        int index = (offset % 0x7fff) & 0x7f;
        if (index > 0x3f) {
            index = (0x80 - index) & 0x3f;
        }
        return QMC1_TABLE[index] & 0xFF;
    }

    static int qmc1MaskForTest(int offset) {
        return qmc1Mask(offset);
    }

    private static void decryptQmc1(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            data[i] ^= (byte) qmc1Mask(i);
        }
    }

    // ------------------------------------------------------------------ QMC2

    private static byte[] parseEkey(String ekey) throws IOException {
        String trimmed = ekey.replace("\0", "").trim();
        byte[] decoded = decodeBase64(trimmed);
        if (decoded == null || decoded.length == 0) {
            throw new IOException("ekey 不是合法的 base64");
        }

        if (startsWith(decoded, ENCV2_PREFIX)) {
            byte[] stage1Input = new byte[decoded.length - ENCV2_PREFIX.length];
            System.arraycopy(decoded, ENCV2_PREFIX.length, stage1Input, 0, stage1Input.length);
            byte[] stage1 = TcTea.decrypt(stage1Input, ENCV2_STAGE1);
            if (stage1 == null) {
                throw new IOException("ekey 的 EncV2 第一阶段解密失败");
            }
            byte[] stage2 = TcTea.decrypt(stage1, ENCV2_STAGE2);
            if (stage2 == null) {
                throw new IOException("ekey 的 EncV2 第二阶段解密失败");
            }
            byte[] encv1 = decodeBase64(new String(stage2, StandardCharsets.US_ASCII).trim());
            if (encv1 == null) {
                throw new IOException("ekey 的 EncV2 内层不是合法 base64");
            }
            decoded = encv1;
        }

        if (decoded.length < 8) {
            throw new IOException("ekey 解出的密钥过短");
        }
        byte[] header = new byte[8];
        System.arraycopy(decoded, 0, header, 0, 8);
        if (decoded.length == 8) {
            return header;
        }
        byte[] body = new byte[decoded.length - 8];
        System.arraycopy(decoded, 8, body, 0, body.length);

        byte[] teaKey = deriveTeaKey(header);
        byte[] decryptedBody = TcTea.decrypt(body, teaKey);
        if (decryptedBody != null) {
            byte[] key = new byte[8 + decryptedBody.length];
            System.arraycopy(header, 0, key, 0, 8);
            System.arraycopy(decryptedBody, 0, key, 8, decryptedBody.length);
            return key;
        }
        return decoded;
    }

    private static byte[] deriveTeaKey(byte[] header) {
        byte[] simple = simpleMakeKey(106, 8);
        byte[] teaKey = new byte[16];
        for (int i = 0; i < 8; i++) {
            teaKey[i * 2] = simple[i];
            teaKey[i * 2 + 1] = header[i];
        }
        return teaKey;
    }

    static byte[] simpleMakeKey(float seed, int size) {
        byte[] result = new byte[size];
        for (int i = 0; i < size; i++) {
            float value = seed + i * 0.1f;
            float tangent = (float) Math.tan(value);
            result[i] = (byte) (int) (100.0f * Math.abs(tangent));
        }
        return result;
    }

    private static byte scrambleByIndex(byte value, int index) {
        int rotation = (index + 4) & 0b111;
        int v = value & 0xFF;
        if (rotation == 0) {
            return value;
        }
        // 注意：这里不是循环移位，而是 (v << r) | (v >> r)，与参考实现一致。
        return (byte) (((v << rotation) | (v >> rotation)) & 0xFF);
    }

    static int qmc2MapMask(byte[] key, long offset) {
        long local = offset;
        if (local > 0x7FFF) {
            local %= 0x7FFF;
        }
        int index = (int) ((local * local + 71214) % key.length);
        return scrambleByIndex(key[index], index) & 0xFF;
    }

    private static void decryptQmc2Map(byte[] data, byte[] key) {
        for (int i = 0; i < data.length; i++) {
            data[i] ^= (byte) qmc2MapMask(key, i);
        }
    }

    private static int calcHashBase(byte[] data) {
        int hash = 1;
        for (byte b : data) {
            int value = b & 0xFF;
            if (value == 0) {
                continue;
            }
            int next = hash * value;
            if (next == 0 || next <= hash) {
                break;
            }
            hash = next;
        }
        return hash;
    }

    private static long calcSegmentKey(int hash, long id, int seed) {
        if (seed == 0) {
            return 0L;
        }
        double dividend = hash;
        double divisor = (double) (id + 1) * seed;
        double key = dividend / divisor * 100.0;
        if (Double.isInfinite(key) || Double.isNaN(key)) {
            return 0L;
        }
        return (long) key;
    }

    private static int rc4Derive(int n, byte[] s, int[] state) {
        state[0] = (state[0] + 1) % n;
        state[1] = ((s[state[0]] & 0xFF) + state[1]) % n;
        byte tmp = s[state[0]];
        s[state[0]] = s[state[1]];
        s[state[1]] = tmp;
        int index = (s[state[0]] & 0xFF) + (s[state[1]] & 0xFF);
        return s[index % n] & 0xFF;
    }

    private static void encryptFirstSegment(byte[] data, int offset, int length, byte[] key, int hash) {
        int n = key.length;
        for (int i = 0; i < length; i++) {
            int position = offset + i;
            int key1 = key[position % n] & 0xFF;
            long key2 = calcSegmentKey(hash, position, key1);
            data[position] ^= key[(int) (key2 % n)];
        }
    }

    private static void encryptOtherSegment(byte[] data, int offset, int length, byte[] key, int hash, byte[] baseS) {
        int segmentId = offset / RC4_SEGMENT;
        int segmentIdSmall = segmentId & 0x1FF;
        int seed = key[segmentIdSmall % key.length] & 0xFF;
        long discard = calcSegmentKey(hash, segmentId, seed) & 0x1FF;
        discard += offset % RC4_SEGMENT;

        int n = key.length;
        byte[] s = baseS.clone();
        int[] state = {0, 0};
        for (long i = 0; i < discard; i++) {
            rc4Derive(n, s, state);
        }
        for (int i = 0; i < length; i++) {
            data[offset + i] ^= (byte) rc4Derive(n, s, state);
        }
    }

    private static void decryptQmc2Rc4(byte[] data, byte[] key) {
        applyQmc2Rc4(data, key);
    }

    /** RC4 流密码（对称，加解密同一函数）。 */
    static void applyQmc2Rc4(byte[] data, byte[] key) {
        int n = key.length;
        byte[] s = new byte[n];
        if (n <= 256) {
            for (int i = 0; i < n; i++) {
                s[i] = (byte) i;
            }
        } else {
            for (int i = 0; i < 256; i++) {
                s[i] = (byte) i;
            }
            for (int i = 256; i < n; i++) {
                s[i] = (byte) (i % 256);
            }
        }
        int j = 0;
        for (int i = 0; i < n; i++) {
            j = (j + (s[i] & 0xFF) + (key[i] & 0xFF)) % n;
            byte tmp = s[i];
            s[i] = s[j];
            s[j] = tmp;
        }
        int hash = calcHashBase(key);

        int offset = 0;
        int remaining = data.length;
        if (offset < RC4_FIRST_SEGMENT) {
            int length = Math.min(remaining, RC4_FIRST_SEGMENT - offset);
            encryptFirstSegment(data, offset, length, key, hash);
            offset += length;
            remaining -= length;
        }
        int toAlign = offset % RC4_SEGMENT;
        if (toAlign != 0) {
            int length = Math.min(remaining, RC4_SEGMENT - toAlign);
            encryptOtherSegment(data, offset, length, key, hash, s);
            offset += length;
            remaining -= length;
        }
        while (remaining > RC4_SEGMENT) {
            encryptOtherSegment(data, offset, RC4_SEGMENT, key, hash, s);
            offset += RC4_SEGMENT;
            remaining -= RC4_SEGMENT;
        }
        if (remaining > 0) {
            encryptOtherSegment(data, offset, remaining, key, hash, s);
        }
    }

    // ------------------------------------------------------------------ 文件尾

    private enum FooterType {
        QTAG, V1, MUSICEX, UNKNOWN
    }

    private static final class Footer {
        FooterType type = FooterType.UNKNOWN;
        String ekey = "";
        long audioLength;
        int keySize;
        long songId;
        String fileName = "";
        String mid = "";
    }

    private static Footer detectFooter(byte[] data) {
        Footer footer = new Footer();
        if (data.length < 16) {
            return footer;
        }

        if (startsWith(data, data.length - 8, MUSICEX_MAGIC)) {
            int magicStart = data.length - 8;
            int versionStart = magicStart - 4;
            int metaSizeStart = versionStart - 4;
            if (metaSizeStart >= 4) {
                int version = readIntLE(data, versionStart);
                long footerSize = readIntLE(data, metaSizeStart) & 0xFFFFFFFFL;
                long metadataSize = footerSize - 16;
                if (version == 1 && metadataSize > 0 && metadataSize <= metaSizeStart) {
                    footer.type = FooterType.MUSICEX;
                    int metadataStart = metaSizeStart - (int) metadataSize;
                    footer.songId = readIntLE(data, metadataStart) & 0xFFFFFFFFL;
                    footer.mid = readUtf16(data, metadataStart + 0x0C, 60);
                    footer.fileName = readUtf16(data, metadataStart + 0x48, 68);
                    footer.audioLength = data.length - footerSize;
                    return footer;
                }
            }
        }

        if (data.length >= 12 && readIntLE(data, data.length - 4) == 0x67615451) {
            int metaSizeBE = readIntBE(data, data.length - 8);
            int metaEnd = data.length - 8;
            int metaStart = Math.max(0, metaEnd - metaSizeBE);
            int comma = -1;
            for (int i = metaStart; i < metaEnd; i++) {
                if (data[i] == ',') {
                    comma = i;
                    break;
                }
            }
            if (comma > 0) {
                footer.type = FooterType.QTAG;
                footer.ekey = new String(data, metaStart, comma - metaStart, StandardCharsets.UTF_8);
                footer.audioLength = metaStart;
                return footer;
            }
        }

        int potentialKeySize = readIntLE(data, data.length - 4);
        if (potentialKeySize > 0 && potentialKeySize <= 0x400) {
            footer.type = FooterType.V1;
            footer.keySize = potentialKeySize;
            return footer;
        }
        return footer;
    }

    private static String readUtf16(byte[] data, int offset, int maxLength) {
        StringBuilder builder = new StringBuilder();
        int end = Math.min(offset + maxLength, data.length);
        for (int i = offset; i + 1 < end; i += 2) {
            char c = (char) ((data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8));
            if (c == 0) {
                break;
            }
            builder.append(c);
        }
        return builder.toString();
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static int readIntBE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return startsWith(data, 0, prefix);
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (offset < 0 || offset + prefix.length > data.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] decodeBase64(String text) {
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException exception) {
            try {
                return Base64.getUrlDecoder().decode(text);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }
}
