/*
 * Decompiled with CFR 0.152.
 */
package cn.gkkstudio;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.zip.GZIPInputStream;

final class KgmDecoder {
    private static final int MIN_HEADER_LENGTH = 1024;
    private static final int OWN_KEY_LENGTH = 17;
    private static final byte[] KGM_HEADER = new byte[]{124, -43, 50, -21, -122, 2, 127, 75, -88, -81, -90, -114, 15, -1, -103, 20};
    private static final byte[] VPR_HEADER = new byte[]{5, 40, -68, -106, -23, -28, 90, 67, -111, -86, -67, -48, 122, -11, 54, 49};
    private static final byte[] VPR_MASK_DIFF = new byte[]{37, -33, -24, -90, 117, 30, 117, 14, 47, -128, -13, 45, -72, -74, -29, 17, 0};
    private static final byte[] MASK_MEND = new byte[]{-72, -43, 61, -78, -23, -81, 120, -116, -125, 51, 113, 81, 118, -96, -51, 55, 47, 62, 53, -115, -87, -66, -104, -73, -25, -116, 34, -50, 90, 97, -33, 104, 105, -119, -2, -91, -74, -34, -87, 119, -4, -56, -67, -67, -27, 109, 62, 90, 54, -17, 105, 78, -66, -31, -23, 102, 28, -13, -39, 2, -74, -14, 18, -101, 68, -48, 111, -71, 53, -119, -74, 70, 109, 115, -126, 6, 105, -63, -19, -41, -123, -62, 48, -33, -94, 98, -66, 121, 45, 98, 98, 61, 13, 126, -66, 72, -119, 35, 2, -96, -28, -43, 117, 81, 50, 2, 83, -3, 22, 58, 33, 59, 22, 15, -61, -78, -69, -77, -30, -70, 58, 61, 19, -20, -10, 1, 69, -124, -91, 112, 15, -109, 73, 12, 100, -51, 49, -43, -52, 76, 7, 1, -98, 0, 26, 35, -112, -65, -120, 30, 59, -85, -90, 62, -60, 115, 71, 16, 126, 59, 94, -68, -29, 0, -124, -1, 9, -44, -32, -119, 15, 91, 88, 112, 79, -5, 101, -40, 92, 83, 27, -45, -56, -58, -65, -17, -104, -80, 80, 79, 15, -22, -27, -125, 88, -116, 40, 44, -124, 103, -51, -48, -98, 71, -37, 39, 80, -54, -12, 99, 99, -24, -105, 127, 27, 75, 12, -62, -63, 33, 76, -52, 88, -11, -108, 82, -93, -13, -45, -32, 104, -12, 0, 35, -13, 94, 10, 123, -109, -35, -85, 18, -78, 19, -24, -124, -41, -89, -97, 15, 50, 76, 85, 29, 4, 54, 82, -36, 3, -13, -7, 78, 66, -23, 61, 97, -17, 124, -74, -77, -109, 80};

    private KgmDecoder() {
    }

    static boolean supports(Path path) throws IOException {
        byte[] byArray = new byte[24];
        try (InputStream inputStream = Files.newInputStream(path, new OpenOption[0]);){
            if (inputStream.readNBytes(byArray, 0, byArray.length) != byArray.length) {
                boolean bl = false;
                return bl;
            }
        }
        boolean bl = KgmDecoder.startsWith(byArray, KGM_HEADER) || KgmDecoder.startsWith(byArray, VPR_HEADER);
        return bl && KgmDecoder.littleEndianInt(byArray, 20) != 5;
    }

    static Path decodeToTemporaryFile(Path path) throws IOException {
        Path temp = null;
        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] header = new byte[1024];
            if (in.readNBytes(header, 0, header.length) != header.length) {
                throw new IOException("KGM \u6587\u4ef6\u5934\u4e0d\u5b8c\u6574");
            }
            boolean vpr = startsWith(header, VPR_HEADER);
            if (!vpr && !startsWith(header, KGM_HEADER)) {
                throw new IOException("\u4e0d\u662f\u6709\u6548\u7684 KGM/VPR \u6587\u4ef6");
            }
            int headerLength = littleEndianInt(header, 16);
            if (headerLength < 1024 || (long) headerLength >= Files.size(path)) {
                throw new IOException("KGM \u6587\u4ef6\u5934\u957f\u5ea6\u65e0\u6548");
            }
            if (headerLength > 1024) {
                in.skipNBytes(headerLength - 1024);
            }
            long payloadLength = Files.size(path) - headerLength;
            if ((payloadLength - 1L) >>> 4 >= (long) MaskHolder.MASK.length) {
                throw new IOException("KGM \u97f3\u9891\u6570\u636e\u8d85\u8fc7 64 MB\uff0c\u5185\u7f6e\u89e3\u7801\u5668\u65e0\u6cd5\u5b8c\u6574\u89e3\u5bc6");
            }
            byte[] key = new byte[17];
            System.arraycopy(header, 28, key, 0, 16);
            temp = Files.createTempFile("kgm-decoded-", ".audio");
            try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                byte[] buffer = new byte[65536];
                long position = 0L;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    for (int i = 0; i < read; i++) {
                        int decoded = decodeByte(buffer[i] & 0xFF, position, key);
                        if (vpr) {
                            decoded ^= VPR_MASK_DIFF[(int) (position % VPR_MASK_DIFF.length)] & 0xFF;
                        }
                        buffer[i] = (byte) decoded;
                        position++;
                    }
                    out.write(buffer, 0, read);
                }
            }
            return temp;
        } catch (Exception exception) {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
            if (exception instanceof IOException io) {
                throw io;
            }
            throw new IOException("KGM \u89e3\u7801\u5931\u8d25: " + exception.getMessage(), exception);
        }
    }

    static void createEncryptedFixture(Path path, Path path2) throws IOException {
        byte[] byArray = new byte[1024];
        System.arraycopy(KGM_HEADER, 0, byArray, 0, KGM_HEADER.length);
        KgmDecoder.writeLittleEndianInt(byArray, 16, 1024);
        byte[] byArray2 = new byte[17];
        for (int i = 0; i < 16; ++i) {
            byArray2[i] = (byte)(49 + i * 7);
        }
        System.arraycopy(byArray2, 0, byArray, 28, 16);
        try (BufferedInputStream bufferedInputStream = new BufferedInputStream(Files.newInputStream(path, new OpenOption[0]));
             BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(Files.newOutputStream(path2, new OpenOption[0]));){
            int n;
            ((OutputStream)bufferedOutputStream).write(byArray);
            byte[] byArray3 = new byte[65536];
            long l = 0L;
            while ((n = ((InputStream)bufferedInputStream).read(byArray3)) >= 0) {
                int n2 = 0;
                while (n2 < n) {
                    byArray3[n2] = (byte)KgmDecoder.encodeByte(byArray3[n2] & 0xFF, l, byArray2);
                    ++n2;
                    ++l;
                }
                ((OutputStream)bufferedOutputStream).write(byArray3, 0, n);
            }
        }
    }

    private static int decodeByte(int n, long l, byte[] byArray) {
        int n2 = byArray[(int)(l % (long)byArray.length)] & 0xFF ^ n;
        return KgmDecoder.foldNibble(n2) ^ KgmDecoder.foldNibble(KgmDecoder.maskByte(l));
    }

    private static int encodeByte(int n, long l, byte[] byArray) {
        int n2 = KgmDecoder.foldNibble(KgmDecoder.maskByte(l));
        int n3 = byArray[(int)(l % (long)byArray.length)] & 0xFF;
        return n3 ^ KgmDecoder.foldNibble(n ^ n2);
    }

    private static int maskByte(long l) {
        return MASK_MEND[(int)(l % (long)MASK_MEND.length)] & 0xFF ^ MaskHolder.MASK[(int)(l >>> 4)] & 0xFF;
    }

    private static int foldNibble(int n) {
        return ((n &= 0xFF) ^ (n & 0xF) << 4) & 0xFF;
    }

    private static int littleEndianInt(byte[] byArray, int n) {
        return byArray[n] & 0xFF | (byArray[n + 1] & 0xFF) << 8 | (byArray[n + 2] & 0xFF) << 16 | (byArray[n + 3] & 0xFF) << 24;
    }

    private static void writeLittleEndianInt(byte[] byArray, int n, int n2) {
        byArray[n] = (byte)n2;
        byArray[n + 1] = (byte)(n2 >>> 8);
        byArray[n + 2] = (byte)(n2 >>> 16);
        byArray[n + 3] = (byte)(n2 >>> 24);
    }

    private static boolean startsWith(byte[] byArray, byte[] byArray2) {
        if (byArray.length < byArray2.length) {
            return false;
        }
        for (int i = 0; i < byArray2.length; ++i) {
            if (byArray[i] == byArray2[i]) continue;
            return false;
        }
        return true;
    }

    private static final class MaskHolder {
        static final byte[] MASK = MaskHolder.loadMask();

        private MaskHolder() {
        }

        /*
         * Enabled aggressive exception aggregation
         */
        private static byte[] loadMask() {
            try (InputStream inputStream = KgmDecoder.class.getResourceAsStream("/cn/gkkstudio/kgm-mask-v2.gz");){
                byte[] byArray;
                if (inputStream == null) {
                    throw new IOException("\u5185\u7f6e KGM \u5bc6\u94a5\u8d44\u6e90\u7f3a\u5931");
                }
                try (GZIPInputStream gZIPInputStream = new GZIPInputStream(inputStream);){
                    byte[] byArray2 = gZIPInputStream.readAllBytes();
                    if (byArray2.length != 0x400000) {
                        throw new IOException("\u5185\u7f6e KGM \u5bc6\u94a5\u8d44\u6e90\u957f\u5ea6\u65e0\u6548");
                    }
                    byArray = byArray2;
                }
                return byArray;
            }
            catch (IOException iOException) {
                throw new ExceptionInInitializerError(iOException);
            }
        }
    }
}

