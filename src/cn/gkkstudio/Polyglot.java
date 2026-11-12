package cn.gkkstudio;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 「一张图片，两条命」：把压缩包塞进 PNG / JPG 的尾部。
 *
 * <p>PNG 的规范规定 IEND 之后的数据解码器应当忽略，JPEG 的 EOI 之后同理，
 * 所以尾部拼接一段 ZIP 不会影响看图。关键在于把 ZIP 里的偏移量整体后移
 * （等价于 Info-ZIP 的 {@code zip -A}），这样改后缀成 .zip 以后，
 * Windows 资源管理器、7-Zip、Java 的 ZipFile 都能正常解开。
 */
final class Polyglot {

    private static final byte[] EOCD = {'P', 'K', 5, 6};
    private static final byte[] CEN = {'P', 'K', 1, 2};
    private static final byte[] LOCAL = {'P', 'K', 3, 4};

    /** 进度回调。 */
    interface Progress {

        void update(int percent, String detail);
    }

    private Polyglot() {
    }

    /** 载体只接受 PNG / JPG / JPEG（这两种尾部加数据不会被解码器嫌弃）。 */
    static boolean isCarrier(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    static String carrierExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? ".png" : name.substring(dot).toLowerCase(java.util.Locale.ROOT);
    }

    /** 这张图片尾是不是挂着一个 ZIP。 */
    static boolean containsZip(Path path) {
        try (var channel = Files.newByteChannel(path)) {
            long size = channel.size();
            if (size < 30) {
                return false;
            }
            int window = (int) Math.min(size, 66_000L);
            ByteBuffer buffer = ByteBuffer.allocate(window);
            channel.position(size - window);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // 读到窗口末尾
            }
            byte[] tail = buffer.array();
            for (int index = tail.length - 22; index >= 0; index--) {
                if (tail[index] == EOCD[0] && tail[index + 1] == EOCD[1]
                        && tail[index + 2] == EOCD[2] && tail[index + 3] == EOCD[3]) {
                    int entries = u16(tail, index + 10);
                    int cdOffset = (int) u32(tail, index + 16);
                    return entries > 0 && cdOffset > 0 && cdOffset < size;
                }
            }
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * 把 payload（文件或文件夹）压进载体图片的尾部，写到 target。
     *
     * @return 压缩包里的条目数
     */
    static int pack(Path carrier, Path payload, Path target, Progress progress) throws IOException {
        if (!isCarrier(carrier)) {
            throw new IOException("载体必须是 PNG 或 JPG：" + carrier.getFileName());
        }
        if (!Files.exists(payload)) {
            throw new IOException("要藏的东西不见了：" + payload);
        }
        progress.update(5, "读取载体图片…");
        long carrierSize = Files.size(carrier);

        Path tempZip = Files.createTempFile("polyglot-", ".zip");
        try {
            progress.update(15, "打包要藏的内容…");
            int entries = buildZip(payload, tempZip, progress);
            byte[] zip = Files.readAllBytes(tempZip);
            progress.update(80, "调整压缩包偏移…");
            fixOffsets(zip, carrierSize);
            if (!hasLocalHeader(zip)) {
                throw new IOException("生成的压缩包不可用");
            }
            progress.update(88, "拼到图片尾部…");
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
                Files.copy(carrier, out);
                out.write(zip);
            }
            return entries;
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    /** 从图片里把压缩包解开到 targetDir。 */
    static int unpack(Path polyglot, Path targetDir, Progress progress) throws IOException {
        Files.createDirectories(targetDir);
        int count = 0;
        try (ZipFile zip = new ZipFile(polyglot.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                Path out = targetDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(targetDir)) {
                    throw new IOException("压缩包里有不安全的路径：" + entry.getName());
                }
                Files.createDirectories(out.getParent());
                try (InputStream stream = zip.getInputStream(entry)) {
                    Files.copy(stream, out, StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
                progress.update(Math.min(95, 20 + count * 3), "解出 " + count + " 个文件…");
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ 内部

    private static int buildZip(Path payload, Path tempZip, Progress progress) throws IOException {
        List<Path> entries = new ArrayList<>();
        if (Files.isDirectory(payload)) {
            try (var walk = Files.walk(payload)) {
                walk.filter(path -> !path.equals(payload)).forEach(entries::add);
            }
        } else {
            entries.add(payload);
        }
        Path base = payload.getParent() == null ? payload.toAbsolutePath().getParent() : payload.getParent();
        int total = Math.max(1, entries.size());
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tempZip)))) {
            int index = 0;
            for (Path path : entries) {
                String name = base.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    if (!name.endsWith("/")) {
                        name = name + "/";
                    }
                    zip.putNextEntry(new ZipEntry(name));
                    zip.closeEntry();
                } else {
                    zip.putNextEntry(new ZipEntry(name));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
                index++;
                progress.update(15 + (int) (60.0 * index / total), "打包中… " + index + "/" + total);
            }
        }
        return entries.size();
    }

    private static boolean hasLocalHeader(byte[] data) {
        for (int index = 0; index + 4 <= data.length; index++) {
            if (data[index] == LOCAL[0] && data[index + 1] == LOCAL[1]
                    && data[index + 2] == LOCAL[2] && data[index + 3] == LOCAL[3]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把所有中央目录入口的“本地头偏移”和 EOCD 里“中央目录起始偏移”整体加上 prefix。
     *
     * <p>不这么做的话，改后缀丢进资源管理器会得到“压缩包已损坏”，因为 ZIP 内部存的是
     * 相对整个文件起点的绝对偏移，加上图片长度后就全错位了。
     */
    private static void fixOffsets(byte[] zip, long prefix) throws IOException {
        int eocd = findEocd(zip);
        if (eocd < 0) {
            throw new IOException("压缩包缺少中央目录记录");
        }
        int entries = u16(zip, eocd + 10);
        long cdOffset = u32(zip, eocd + 16);
        if (cdOffset <= 0 || cdOffset >= zip.length) {
            throw new IOException("压缩包偏移异常");
        }
        int position = (int) cdOffset;
        for (int index = 0; index < entries; index++) {
            if (position + 46 > zip.length
                    || zip[position] != CEN[0] || zip[position + 1] != CEN[1]
                    || zip[position + 2] != CEN[2] || zip[position + 3] != CEN[3]) {
                throw new IOException("中央目录结构异常（第 " + (index + 1) + " 项）");
            }
            long offset = u32(zip, position + 42);
            putU32(zip, position + 42, offset + prefix);
            int nameLength = u16(zip, position + 28);
            int extraLength = u16(zip, position + 30);
            int commentLength = u16(zip, position + 32);
            position += 46 + nameLength + extraLength + commentLength;
        }
        putU32(zip, eocd + 16, cdOffset + prefix);
    }

    private static int findEocd(byte[] data) {
        for (int index = data.length - 22; index >= 0; index--) {
            if (data[index] == EOCD[0] && data[index + 1] == EOCD[1]
                    && data[index + 2] == EOCD[2] && data[index + 3] == EOCD[3]) {
                return index;
            }
        }
        return -1;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    private static void putU32(byte[] data, int offset, long value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /** 给提示语用：读一下尾部长什么样。 */
    static String describe(Path path) {
        boolean zip = containsZip(path);
        return path.getFileName() + (zip ? "（图片里藏着压缩包，改后缀 .zip 也能解压）" : "（普通图片）");
    }

    static ByteBuffer littleEndian(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }
}
