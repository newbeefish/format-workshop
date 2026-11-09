package cn.gkkstudio;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 杂项板块的执行器：ZIP / TAR.GZ 打包解包、PDF 拆图、图片合 PDF。
 * 与 MediaEngine 平级，但完全不依赖 FFmpeg。
 */
final class MiscEngine {
    interface Listener {
        void changed(TransferItem item);
    }

    private final Path logDirectory;
    private Path keystore;
    private String alias = "";
    private String keystorePassword = "";
    private boolean v1 = true;
    private boolean v2 = true;
    private boolean v3 = true;
    private boolean zipalign;

    void setApkOptions(Path keystore, String alias, String password, boolean v1, boolean v2, boolean v3, boolean zipalign) {
        this.keystore = keystore;
        this.alias = alias == null ? "" : alias.trim();
        this.keystorePassword = password == null ? "" : password;
        this.v1 = v1;
        this.v2 = v2;
        this.v3 = v3;
        this.zipalign = zipalign;
    }

    private Path toolsDirectory() {
        Path ffmpeg = SystemCapabilities.detect().ffmpeg();
        return ffmpeg == null ? this.logDirectory.getParent().resolve("app").resolve("tools")
                : ffmpeg.getParent();
    }

    /**
     * 猜 ZPAQ 密码用的数字区间：依次尝试 前缀 + 数字 + 后缀（数字可左补零）。
     * 数字从 start 数到 end，两端都算在内。
     */
    private long passwordStart = 0L;
    private long passwordEnd = 0L;
    private String passwordPrefix = "";
    private String passwordSuffix = "";
    private int passwordWidth = 0;
    /** 单个文件内最多同时开几个 zpaq 进程去试。 */
    private int passwordThreads = 1;

    void setPasswordRange(long start, long end, String prefix, String suffix, int width) {
        this.passwordStart = start;
        this.passwordEnd = end;
        this.passwordPrefix = prefix == null ? "" : prefix;
        this.passwordSuffix = suffix == null ? "" : suffix;
        this.passwordWidth = Math.max(0, Math.min(width, 18));
    }

    private String passwordCandidate(long value) {
        String digits = Long.toString(value);
        if (this.passwordWidth > digits.length()) {
            StringBuilder builder = new StringBuilder(this.passwordWidth);
            for (int index = digits.length(); index < this.passwordWidth; index++) {
                builder.append('0');
            }
            digits = builder.append(digits).toString();
        }
        return this.passwordPrefix + digits + this.passwordSuffix;
    }

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private ExecutorService executor;
    private boolean pdfWarned;

    MiscEngine(Path logDirectory) {
        this.logDirectory = logDirectory;
    }

    void run(List<TransferItem> items, MiscAction action, Path outputDirectory, String imageFormat,
             int concurrency, Listener listener, Runnable onDone) {
        this.cancelled.set(false);
        this.passwordThreads = Math.max(1, concurrency);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, concurrency), runnable -> {
            Thread thread = new Thread(runnable, "misc-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.executor = pool;
        for (TransferItem item : items) {
            pool.submit(() -> {
                try {
                    process(item, action, outputDirectory, imageFormat, listener);
                } catch (Exception exception) {
                    if (this.cancelled.get()) {
                        item.state = TransferItem.State.CANCELLED;
                        item.detail = "已取消";
                    } else {
                        item.state = TransferItem.State.FAILED;
                        item.detail = message(exception);
                    }
                    listener.changed(item);
                }
            });
        }
        pool.shutdown();
        new Thread(() -> {
            try {
                while (!pool.awaitTermination(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (this.cancelled.get()) {
                        pool.shutdownNow();
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            onDone.run();
        }, "misc-waiter").start();
    }

    void cancel(List<TransferItem> items) {
        this.cancelled.set(true);
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
        for (TransferItem item : items) {
            if (item.state == TransferItem.State.WAITING || item.state == TransferItem.State.CONVERTING) {
                item.state = TransferItem.State.CANCELLED;
                item.detail = "已取消";
            }
        }
    }

    void close() {
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
    }

    private static String message(Exception exception) {
        String text = exception.getMessage();
        if (text == null || text.isBlank()) {
            text = exception.getClass().getSimpleName();
        }
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    private void process(TransferItem item, MiscAction action, Path outputDirectory, String imageFormat,
                         Listener listener) throws Exception {
        item.state = TransferItem.State.CONVERTING;
        item.progress = 5;
        item.detail = action.label + "…";
        listener.changed(item);
        Files.createDirectories(outputDirectory);

        switch (action) {
            case ZIP_PACK -> packZip(item, outputDirectory, listener);
            case ZIP_UNPACK -> unpackZip(item, outputDirectory, listener);
            case TAR_PACK -> packTarGz(item, outputDirectory, listener);
            case TAR_UNPACK -> unpackTarGz(item, outputDirectory, listener);
            case IMG_TO_PDF -> imagesToPdf(item, outputDirectory, listener);
            case PDF_TO_IMG -> pdfToImages(item, outputDirectory, imageFormat, listener);
            case APK_SIGN -> signApk(item, outputDirectory, listener);
            case ISO_PACK -> packIso(item, outputDirectory, listener);
            case ISO_UNPACK -> unpackIso(item, outputDirectory, listener);
            case ISO_MOUNT -> mountIso(item, listener);
            case ARCHIVE_CONVERT -> convertArchive(item, outputDirectory, listener);
            case JAR_PACK -> packJar(item, outputDirectory);
            case JAR_UNPACK -> unpackJar(item, outputDirectory);
            case ZPAQ_PACK -> zpaqPack(item, outputDirectory);
            case ZPAQ_UNPACK -> zpaqUnpack(item, outputDirectory);
            case ZPAQ_TEST_PASSWORD -> zpaqTestPassword(item, outputDirectory, listener);
            case SEVENZ_PACK -> sevenZPack(item, outputDirectory);
            case SEVENZ_UNPACK -> sevenZUnpack(item, outputDirectory);
            case XZ_PACK -> xzPack(item, outputDirectory);
            case XZ_UNPACK -> xzUnpack(item, outputDirectory);
            case HASH -> hashFile(item, outputDirectory);
            case HASH_VERIFY -> hashVerify(item, outputDirectory);
            case BASE64_ENCODE -> base64Encode(item, outputDirectory);
            case BASE64_DECODE -> base64Decode(item, outputDirectory);
            case RENAME -> renameFile(item, outputDirectory);
            case TOUCH -> touchFile(item);
            case QR_GENERATE -> qrGenerate(item, outputDirectory);
            case QR_READ -> qrRead(item, outputDirectory);
            case ASM_BUILD -> asmBuild(item, outputDirectory, listener);
        }
        item.progress = 100;
        item.state = TransferItem.State.DONE;
        item.detail = item.note != null ? item.note
                : "已完成" + (item.output == null ? "" : " · " + item.output.getFileName());
        listener.changed(item);
    }

    private String baseName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void safeName(String name) throws IOException {
        if (name.contains("..") || name.startsWith("/") || name.contains(":")) {
            throw new IOException("压缩包里有不安全的路径：" + name);
        }
    }

    // ------------------------------------------------------------------ ZIP

    private void packZip(TransferItem item, Path outputDirectory, Listener listener) throws Exception {
        Path target = unique(outputDirectory.resolve(item.source.getFileName() + ".zip"));
        List<Path> entries = new ArrayList<>();
        if (Files.isDirectory(item.source)) {
            Files.walk(item.source).filter(path -> !path.equals(item.source)).forEach(entries::add);
        } else {
            entries.add(item.source);
        }
        int total = Math.max(1, entries.size());
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {
            int index = 0;
            for (Path path : entries) {
                if (this.cancelled.get()) {
                    throw new IOException("已取消");
                }
                String name = item.source.getParent().relativize(path).toString().replace('\\', '/');
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
                item.progress = 5 + (int) (90.0 * index / total);
                item.detail = "打包中… " + index + "/" + total;
                listener.changed(item);
            }
        }
        item.output = target;
    }

    private void unpackZip(TransferItem item, Path outputDirectory, Listener listener) throws Exception {
        Path target = unique(outputDirectory.resolve(item.source.getFileName().toString() + "_解压"));
        Files.createDirectories(target);
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(item.source)))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (this.cancelled.get()) {
                    throw new IOException("已取消");
                }
                safeName(entry.getName());
                Path out = target.resolve(entry.getName()).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("压缩包里有不安全的路径：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
                item.progress = Math.min(95, 10 + count);
                item.detail = "解压中… 已解出 " + count + " 个";
                listener.changed(item);
            }
        }
        item.output = target;
    }

    // ------------------------------------------------------------------ TAR

    private void packTarGz(TransferItem item, Path outputDirectory, Listener listener) throws Exception {
        String name = item.source.getFileName() + ".tar.gz";
        Path target = unique(outputDirectory.resolve(name));
        List<Path> entries = new ArrayList<>();
        if (Files.isDirectory(item.source)) {
            Files.walk(item.source).filter(path -> !path.equals(item.source)).forEach(entries::add);
        } else {
            entries.add(item.source);
        }
        int total = Math.max(1, entries.size());
        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(target));
             GZIPOutputStream gzip = new GZIPOutputStream(file)) {
            int index = 0;
            for (Path path : entries) {
                if (this.cancelled.get()) {
                    throw new IOException("已取消");
                }
                String entryName = item.source.getParent().relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    writeTarEntry(gzip, entryName + "/", 0L, true);
                } else {
                    writeTarEntry(gzip, entryName, Files.size(path), false);
                    Files.copy(path, gzip);
                    pad(gzip, Files.size(path));
                }
                index++;
                item.progress = 5 + (int) (90.0 * index / total);
                item.detail = "打包中… " + index + "/" + total;
                listener.changed(item);
            }
            gzip.write(new byte[1024]);
        }
        item.output = target;
    }

    private static void writeTarEntry(OutputStream out, String name, long size, boolean directory) throws IOException {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(100, nameBytes.length));
        putOctal(header, 100, 8, directory ? 0755 : 0644);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, size);
        putOctal(header, 136, 12, System.currentTimeMillis() / 1000L);
        header[156] = (byte) (directory ? '5' : '0');
        byte[] magic = "ustar".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, header, 257, magic.length);
        header[263] = '0';
        header[264] = '0';
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        int sum = 0;
        for (byte value : header) {
            sum += value & 0xFF;
        }
        putOctal(header, 148, 7, sum);
        header[155] = ' ';
        out.write(header);
    }

    private static void putOctal(byte[] header, int offset, int length, long value) {
        String text = Long.toOctalString(value);
        StringBuilder builder = new StringBuilder(text);
        while (builder.length() < length - 1) {
            builder.insert(0, '0');
        }
        byte[] bytes = builder.toString().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(length - 1, bytes.length));
        header[offset + length - 1] = 0;
    }

    private static void pad(OutputStream out, long size) throws IOException {
        int remainder = (int) (size % 512);
        if (remainder != 0) {
            out.write(new byte[512 - remainder]);
        }
    }

    private void unpackTarGz(TransferItem item, Path outputDirectory, Listener listener) throws Exception {
        Path target = unique(outputDirectory.resolve(item.source.getFileName().toString() + "_解压"));
        Files.createDirectories(target);
        String name = item.source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(item.source));
             InputStream stream = name.endsWith(".tar") ? raw : new GZIPInputStream(raw)) {
            byte[] header = new byte[512];
            int count = 0;
            while (true) {
                if (this.cancelled.get()) {
                    throw new IOException("已取消");
                }
                int read = readFully(stream, header);
                if (read < 512) {
                    break;
                }
                boolean empty = true;
                for (byte value : header) {
                    if (value != 0) {
                        empty = false;
                        break;
                    }
                }
                if (empty) {
                    break;
                }
                String entryName = new String(header, 0, 100, StandardCharsets.UTF_8).trim();
                if (entryName.isEmpty()) {
                    break;
                }
                long size = parseOctal(header, 124, 12);
                boolean directory = header[156] == '5';
                safeName(entryName);
                Path out = target.resolve(entryName).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("压缩包里有不安全的路径：" + entryName);
                }
                if (directory) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    copyExactly(stream, out, size);
                }
                padSkip(stream, size);
                count++;
                item.progress = Math.min(95, 10 + count);
                item.detail = "解压中… 已解出 " + count + " 个";
                listener.changed(item);
            }
        }
        item.output = target;
    }

    private static int readFully(InputStream stream, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = stream.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static long parseOctal(byte[] buffer, int offset, int length) {
        String text = new String(buffer, offset, length, StandardCharsets.US_ASCII).trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static void copyExactly(InputStream stream, Path target, long size) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = size;
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int read = stream.read(buffer, 0, want);
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private static void padSkip(InputStream stream, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        byte[] buffer = new byte[(int) pad];
        readFully(stream, buffer);
    }

    // ------------------------------------------------------------------ PDF

    private void imagesToPdf(TransferItem item, Path outputDirectory, Listener listener) throws Exception {
        Path target = unique(outputDirectory.resolve(baseName(item.source) + ".pdf"));
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(item.source.toFile());
            if (image == null) {
                throw new IOException("这个图片格式读不出来：" + item.source.getFileName());
            }
            float width = image.getWidth();
            float height = image.getHeight();
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage(
                    new org.apache.pdfbox.pdmodel.common.PDRectangle(width, height));
            document.addPage(page);
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject pdImage =
                    org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document, image);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream stream =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                stream.drawImage(pdImage, 0, 0, width, height);
            }
            item.progress = 80;
            item.detail = "写入 PDF…";
            listener.changed(item);
            document.save(target.toFile());
        }
        item.output = target;
    }

    private void pdfToImages(TransferItem item, Path outputDirectory, String imageFormat, Listener listener) throws Exception {
        String format = imageFormat == null || imageFormat.isBlank() ? "png" : imageFormat;
        Path target = unique(outputDirectory.resolve(baseName(item.source) + "_页面"));
        Files.createDirectories(target);
        try (org.apache.pdfbox.pdmodel.PDDocument document =
                     org.apache.pdfbox.pdmodel.PDDocument.load(item.source.toFile())) {
            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            int pages = document.getNumberOfPages();
            for (int index = 0; index < pages; index++) {
                if (this.cancelled.get()) {
                    throw new IOException("已取消");
                }
                java.awt.image.BufferedImage image = renderer.renderImageWithDPI(index, 150);
                Path file = target.resolve(String.format("page-%03d.%s", index + 1, format));
                javax.imageio.ImageIO.write(image, format, file.toFile());
                item.progress = 5 + (int) (90.0 * (index + 1) / Math.max(1, pages));
                item.detail = "渲染第 " + (index + 1) + "/" + pages + " 页";
                listener.changed(item);
            }
        }
        item.output = target;
    }

    private void hashVerify(TransferItem item, Path outputDirectory) throws Exception {
        String text = Files.readString(item.source, StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)(SHA-?256|MD5)\\s*[:：]\\s*([0-9a-f]{32,64})").matcher(text);
        String algorithm = null;
        String expected = null;
        if (matcher.find()) {
            algorithm = matcher.group(1).toUpperCase(java.util.Locale.ROOT).contains("MD5") ? "MD5" : "SHA-256";
            expected = matcher.group(2).toLowerCase(java.util.Locale.ROOT);
        }
        if (expected == null) {
            java.util.regex.Matcher bare = java.util.regex.Pattern.compile("\\b([0-9a-fA-F]{32}|[0-9a-fA-F]{64})\\b").matcher(text);
            if (bare.find()) {
                expected = bare.group(1).toLowerCase(java.util.Locale.ROOT);
                algorithm = expected.length() == 32 ? "MD5" : "SHA-256";
            }
        }
        if (expected == null) {
            throw new IOException("校验文件里没找到哈希值");
        }
        String name = item.source.getFileName().toString();
        java.util.regex.Matcher fileMatcher = java.util.regex.Pattern.compile("文件\\s*[:：]\\s*(.+)").matcher(text);
        String targetName = fileMatcher.find() ? fileMatcher.group(1).trim() : name;
        Path target = item.source.getParent().resolve(targetName);
        if (!Files.isRegularFile(target)) {
            if (targetName.endsWith(".sha256")) {
                target = item.source.getParent().resolve(targetName.substring(0, targetName.length() - 7));
            } else if (targetName.endsWith(".md5")) {
                target = item.source.getParent().resolve(targetName.substring(0, targetName.length() - 4));
            }
        }
        if (!Files.isRegularFile(target)) {
            throw new IOException("找不到被校验的文件：" + targetName);
        }
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance(algorithm);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(target))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        String actual = hex(digest.digest());
        boolean match = actual.equalsIgnoreCase(expected);
        Path out = unique(outputDirectory.resolve(target.getFileName() + ".verify.txt"));
        Files.writeString(out, (match ? "校验通过" : "校验失败") + System.lineSeparator()
                + "算法: " + algorithm + System.lineSeparator()
                + "文件: " + target.getFileName() + System.lineSeparator()
                + "期望: " + expected + System.lineSeparator()
                + "实际: " + actual + System.lineSeparator(), StandardCharsets.UTF_8);
        item.output = out;
        item.detail = match ? "校验通过" : "校验失败";
    }
    private void hashFile(TransferItem item, Path outputDirectory) throws Exception {
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        java.security.MessageDigest md5 = java.security.MessageDigest.getInstance("MD5");
        try (InputStream in = new BufferedInputStream(Files.newInputStream(item.source))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                sha.update(buffer, 0, read);
                md5.update(buffer, 0, read);
            }
        }
        String text = "SHA-256: " + hex(sha.digest()) + System.lineSeparator()
                + "MD5:     " + hex(md5.digest()) + System.lineSeparator()
                + "文件:    " + item.source.getFileName() + System.lineSeparator();
        Path target = unique(outputDirectory.resolve(item.source.getFileName() + ".sha256"));
        Files.writeString(target, text, StandardCharsets.UTF_8);
        item.output = target;
    }

    private static String hex(byte[] data) {
        StringBuilder builder = new StringBuilder();
        for (byte value : data) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private void base64Encode(TransferItem item, Path outputDirectory) throws Exception {
        Path target = unique(outputDirectory.resolve(item.source.getFileName() + ".b64"));
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target));
             InputStream in = new BufferedInputStream(Files.newInputStream(item.source))) {
            byte[] buffer = new byte[3 * 1024];
            byte[] encoded = new byte[4 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                byte[] slice = read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read);
                byte[] data = java.util.Base64.getEncoder().encode(slice);
                out.write(data);
            }
        }
        item.output = target;
    }

    private void base64Decode(TransferItem item, Path outputDirectory) throws Exception {
        String name = baseName(item.source);
        Path target = unique(outputDirectory.resolve(name.endsWith(".b64") ? name : name));
        byte[] raw = Files.readAllBytes(item.source);
        byte[] decoded = java.util.Base64.getMimeDecoder().decode(raw);
        Files.write(target, decoded);
        item.output = target;
    }

    private void renameFile(TransferItem item, Path outputDirectory) throws Exception {
        Files.createDirectories(outputDirectory);
        int count = 0;
        try (var stream = Files.list(outputDirectory)) {
            count = stream.filter(path -> path.getFileName().toString().matches("\\d{3}_.*")).toList().size();
        }
        String prefix = String.format("%03d_", count + 1);
        Path target = outputDirectory.resolve(prefix + item.source.getFileName());
        Files.copy(item.source, target, StandardCopyOption.REPLACE_EXISTING);
        item.output = target;
    }

    private void touchFile(TransferItem item) throws Exception {
        Files.setLastModifiedTime(item.source, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        item.output = item.source;
    }
    private void asmBuild(TransferItem item, Path outputDirectory, Listener listener) throws Exception {
        Path nasm = toolsDirectory().resolve("nasm.exe");
        if (!Files.isRegularFile(nasm)) {
            throw new IOException("找不到 nasm.exe");
        }
        Files.createDirectories(outputDirectory);
        Path target = unique(outputDirectory.resolve(baseName(item.source) + ".obj"));
        item.detail = "正在编译汇编…";
        listener.changed(item);
        String output = run(List.of(nasm.toString(), "-f", "win64", "-o", target.toString(), item.source.toString()));
        if (!Files.isRegularFile(target)) {
            throw new IOException(output.isBlank() ? "汇编失败" : output);
        }
        item.output = target;
    }
    private void packJar(TransferItem item, Path outputDirectory) throws Exception {
        Path target = unique(outputDirectory.resolve(item.source.getFileName() + ".jar"));
        List<Path> entries = new ArrayList<>();
        if (Files.isDirectory(item.source)) {
            Files.walk(item.source).filter(path -> !path.equals(item.source)).forEach(entries::add);
        } else {
            entries.add(item.source);
        }
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\r\nCreated-By: 格式作坊\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Path path : entries) {
                String name = item.source.getParent().relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    zip.putNextEntry(new ZipEntry(name + "/"));
                    zip.closeEntry();
                } else if (!"META-INF/MANIFEST.MF".equalsIgnoreCase(name)) {
                    zip.putNextEntry(new ZipEntry(name));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        }
        item.output = target;
    }

    private void unpackJar(TransferItem item, Path outputDirectory) throws Exception {
        Path target = unique(outputDirectory.resolve(baseName(item.source) + "_jar"));
        Files.createDirectories(target);
        unzipTo(item.source, target);
        item.output = target;
    }
    private void zpaqPack(TransferItem item, Path outputDirectory) throws Exception {
        Path tool = toolsDirectory().resolve("zpaq64.exe");
        if (!Files.isRegularFile(tool)) {
            throw new IOException("找不到 zpaq64.exe");
        }
        Path target = unique(outputDirectory.resolve(item.source.getFileName() + ".zpaq"));
        Files.createDirectories(outputDirectory);
        String name = item.source.getFileName().toString();
        Process process = new ProcessBuilder(tool.toString(), "a", target.toAbsolutePath().toString(), name)
                .directory(item.source.getParent().toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        if (!Files.isRegularFile(target) || Files.size(target) == 0) {
            throw new IOException(output.trim().isEmpty() ? "zpaq 打包失败" : output.trim());
        }
        item.output = target;
    }

    private void zpaqUnpack(TransferItem item, Path outputDirectory) throws Exception {
        Path tool = toolsDirectory().resolve("zpaq64.exe");
        if (!Files.isRegularFile(tool)) {
            throw new IOException("找不到 zpaq64.exe");
        }
        Path target = unique(outputDirectory.resolve(baseName(item.source) + "_zpaq"));
        Files.createDirectories(target);
        Process process = new ProcessBuilder(tool.toString(), "x", item.source.toAbsolutePath().toString(),
                "-to", target.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        try (var stream = Files.list(target)) {
            if (stream.findAny().isEmpty()) {
                throw new IOException(output.trim().isEmpty() ? "zpaq 解包失败" : output.trim());
            }
        }
        item.output = target;
    }
    /**
     * 在设定好的数字区间里逐个试 ZPAQ 密码：
     * 每个候选值都用 zpaq 自己“列一次目录”，列得出来说明密码对了。
     */
    private void zpaqTestPassword(TransferItem item, Path outputDirectory, Listener listener) throws Exception {
        Path tool = toolsDirectory().resolve("zpaq64.exe");
        if (!Files.isRegularFile(tool)) {
            throw new IOException("找不到 zpaq64.exe");
        }
        if (!Files.isRegularFile(item.source)) {
            throw new IOException("找不到这个 .zpaq 文件");
        }
        long from = Math.min(this.passwordStart, this.passwordEnd);
        long to = Math.max(this.passwordStart, this.passwordEnd);
        if (from < 0) {
            throw new IOException("数字范围不能是负数");
        }
        long total = to - from + 1;
        if (total <= 0) {
            throw new IOException("数字范围不对：起始要小于结束");
        }
        if (total > 100_000_000L) {
            throw new IOException("范围太大（" + total + " 个），请先缩小一些");
        }

        item.detail = "先看看这个归档要不要密码…";
        listener.changed(item);
        if (zpaqProbe(tool, item.source, null) == 0) {
            item.note = "这个归档没有密码，直接解包就行";
            return;
        }

        final java.util.concurrent.atomic.AtomicLong next = new java.util.concurrent.atomic.AtomicLong(from);
        final java.util.concurrent.atomic.AtomicLong tried = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong painted = new java.util.concurrent.atomic.AtomicLong(0L);
        final java.util.concurrent.atomic.AtomicReference<String> hit = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<String> problem = new java.util.concurrent.atomic.AtomicReference<>();
        int threads = Math.max(1, Math.min(this.passwordThreads, 16));
        List<Thread> workers = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            Thread worker = new Thread(() -> {
                while (hit.get() == null && problem.get() == null && !this.cancelled.get()) {
                    long value = next.getAndIncrement();
                    if (value > to) {
                        return;
                    }
                    String candidate = passwordCandidate(value);
                    boolean correct;
                    try {
                        correct = zpaqProbe(tool, item.source, candidate) == 0;
                    } catch (Exception exception) {
                        problem.compareAndSet(null, message(exception));
                        return;
                    }
                    if (correct) {
                        hit.compareAndSet(null, candidate);
                        return;
                    }
                    long done = tried.incrementAndGet();
                    long now = System.currentTimeMillis();
                    if (now - painted.get() >= 200L) {
                        painted.set(now);
                        item.progress = (int) Math.min(95L, 10L + 85L * done / total);
                        item.detail = "已试 " + done + "/" + total + " 个，正在试 " + candidate;
                        listener.changed(item);
                    }
                }
            }, "zpaq-password-" + index);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        if (this.cancelled.get()) {
            throw new IOException("已取消");
        }
        String found = hit.get();
        long attempts = tried.get() + (found == null ? 0L : 1L);
        if (found == null && problem.get() != null) {
            throw new IOException("试密码的时候出错了：" + problem.get());
        }
        if (found == null) {
            throw new IOException("在 " + from + "~" + to + " 里试了 " + attempts + " 个数字，没找到密码");
        }
        Path target = unique(outputDirectory.resolve(baseName(item.source) + "_密码_" + found + ".txt"));
        String text = "归档：" + item.source.toAbsolutePath() + System.lineSeparator()
                + "密码：" + found + System.lineSeparator()
                + "试了：" + attempts + " 个数字" + System.lineSeparator()
                + "范围：前缀“" + this.passwordPrefix + "” + " + from + "~" + to
                + (this.passwordWidth > 0 ? "（补齐 " + this.passwordWidth + " 位）" : "")
                + " + 后缀“" + this.passwordSuffix + "”" + System.lineSeparator();
        Files.writeString(target, text, StandardCharsets.UTF_8);
        item.output = target;
        item.progress = 100;
        item.note = "已找回密码：" + found;
    }

    /** 用 zpaq 列一次目录：返回 0 表示密码对，其他表示不对。 */
    private int zpaqProbe(Path tool, Path archive, String password) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(tool.toString());
        command.add("l");
        command.add(archive.toAbsolutePath().toString());
        if (password != null) {
            command.add("-key");
            command.add(password);
        }
        command.add("-t1");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("zpaq 卡住了");
        }
        return process.exitValue();
    }

    private void sevenZPack(TransferItem item, Path outputDirectory) throws Exception {
        Path target = unique(outputDirectory.resolve(item.source.getFileName() + ".7z"));
        org.apache.commons.compress.archivers.sevenz.SevenZOutputFile out =
                new org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(target.toFile());
        try (out) {
            List<Path> entries = new ArrayList<>();
            if (Files.isDirectory(item.source)) {
                Files.walk(item.source).filter(path -> !path.equals(item.source)).forEach(entries::add);
            } else {
                entries.add(item.source);
            }
            for (Path path : entries) {
                String name = item.source.getParent().relativize(path).toString().replace('\\', '/');
                org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry =
                        new org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry();
                entry.setName(name);
                entry.setDirectory(Files.isDirectory(path));
                if (!Files.isDirectory(path)) {
                    entry.setSize(Files.size(path));
                }
                if (Files.isDirectory(path)) {
                    entry.setDirectory(true);
                    out.putArchiveEntry(entry);
                    out.closeArchiveEntry();
                } else {
                    out.putArchiveEntry(entry);
                    try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                        }
                    }
                    out.closeArchiveEntry();
                }
            }
        }
        item.output = target;
    }

    private void sevenZUnpack(TransferItem item, Path outputDirectory) throws Exception {
        Path target = unique(outputDirectory.resolve(baseName(item.source) + "_7z"));
        Files.createDirectories(target);
        try (org.apache.commons.compress.archivers.sevenz.SevenZFile file =
                     new org.apache.commons.compress.archivers.sevenz.SevenZFile(item.source.toFile())) {
            org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry;
            while ((entry = file.getNextEntry()) != null) {
                Path out = target.resolve(entry.getName()).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("压缩包里有不安全的路径：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(out))) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = file.read(buffer)) > 0) {
                            stream.write(buffer, 0, read);
                        }
                    }
                }
            }
        }
        item.output = target;
    }

    private void xzPack(TransferItem item, Path outputDirectory) throws Exception {
        Path target = unique(outputDirectory.resolve(item.source.getFileName() + ".xz"));
        try (OutputStream out = new org.tukaani.xz.XZOutputStream(
                new BufferedOutputStream(Files.newOutputStream(target)),
                new org.tukaani.xz.LZMA2Options());
             InputStream in = new BufferedInputStream(Files.newInputStream(item.source))) {
            in.transferTo(out);
        }
        item.output = target;
    }

    private void xzUnpack(TransferItem item, Path outputDirectory) throws Exception {
        String name = item.source.getFileName().toString();
        String base = name.toLowerCase(java.util.Locale.ROOT).endsWith(".xz")
                ? name.substring(0, name.length() - 3) : name + ".out";
        Path target = unique(outputDirectory.resolve(base));
        try (InputStream in = new org.tukaani.xz.XZInputStream(
                new BufferedInputStream(Files.newInputStream(item.source)));
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
            in.transferTo(out);
        }
        item.output = target;
    }
    private String textParam = "";

    void setTextParam(String value) {
        this.textParam = value == null ? "" : value;
    }

    private void qrGenerate(TransferItem item, Path outputDirectory) throws Exception {
        String content = this.textParam.isBlank() ? item.fileName() : this.textParam;
        com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
        var matrix = writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(512, 512, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 512; y++) {
            for (int x = 0; x < 512; x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        Path target = unique(outputDirectory.resolve("qr.png"));
        javax.imageio.ImageIO.write(image, "png", target.toFile());
        item.output = target;
    }

    private void qrRead(TransferItem item, Path outputDirectory) throws Exception {
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(item.source.toFile());
        if (image == null) {
            throw new IOException("图片读不出来");
        }
        com.google.zxing.client.j2se.BufferedImageLuminanceSource source =
                new com.google.zxing.client.j2se.BufferedImageLuminanceSource(image);
        com.google.zxing.BinaryBitmap bitmap = new com.google.zxing.BinaryBitmap(
                new com.google.zxing.common.HybridBinarizer(source));
        com.google.zxing.Result result = new com.google.zxing.MultiFormatReader()
                .decodeWithState(bitmap);
        Path target = unique(outputDirectory.resolve(baseName(item.source) + ".txt"));
        Files.writeString(target, result.getText(), StandardCharsets.UTF_8);
        item.output = target;
    }
    private String archiveTarget = "zip";

    void setArchiveTarget(String target) {
        this.archiveTarget = target == null || target.isBlank() ? "zip" : target;
    }

    private void convertArchive(TransferItem item, Path outputDirectory, Listener listener) throws Exception {
        Path temp = Files.createTempDirectory("fmt-arch-");
        try {
            item.detail = "正在解包…";
            listener.changed(item);
            String name = item.source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".zip") || name.endsWith(".jar")) {
                unzipTo(item.source, temp);
            } else if (name.endsWith(".7z")) {
                sevenZTo(item.source, temp);
            } else if (name.endsWith(".xz")) {
                Path inner = temp.resolve("__xz__");
                xzTo(item.source, inner);
                if (name.endsWith(".tar.xz")) {
                    Path unpacked = temp.resolve("__tar__");
                    Files.createDirectories(unpacked);
                    untarTo(inner, unpacked);
                    Files.deleteIfExists(inner);
                    replaceDirectory(temp, unpacked);
                } else {
                    Files.move(inner, temp.resolve(baseName(item.source)), StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                untarTo(item.source, temp);
            }
            item.progress = 55;
            String target = this.archiveTarget == null ? "zip" : this.archiveTarget;
            Path out = unique(outputDirectory.resolve(baseName(item.source) + "." + target));
            item.detail = "正在重新打包为 " + target + "…";
            listener.changed(item);
            packDirectory(temp, out, target);
            item.output = out;
        } finally {
            deleteTree(temp);
        }
    }

    private static void replaceDirectory(Path root, Path source) throws Exception {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                if (path.equals(source)) {
                    continue;
                }
                Path target = root.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void packDirectory(Path dir, Path target, String format) throws Exception {
        switch (format) {
            case "tar.gz" -> tarFrom(dir, target);
            case "7z" -> sevenZFrom(dir, target);
            case "jar" -> jarFrom(dir, target);
            case "tar.xz" -> {
                Path tar = Files.createTempFile("fmt-tar-", ".tar");
                try {
                    tarFromPlain(dir, tar);
                    xzTo(tar, target);
                } finally {
                    Files.deleteIfExists(tar);
                }
            }
            default -> zipFrom(dir, target);
        }
    }

    private static void sevenZTo(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        try (org.apache.commons.compress.archivers.sevenz.SevenZFile file =
                     new org.apache.commons.compress.archivers.sevenz.SevenZFile(source.toFile())) {
            org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry;
            while ((entry = file.getNextEntry()) != null) {
                Path out = target.resolve(entry.getName()).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("压缩包里有不安全的路径：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(out))) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = file.read(buffer)) > 0) {
                            stream.write(buffer, 0, read);
                        }
                    }
                }
            }
        }
    }

    private static void sevenZFrom(Path dir, Path target) throws Exception {
        List<Path> entries = new ArrayList<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(path -> !path.equals(dir)).forEach(entries::add);
        }
        try (org.apache.commons.compress.archivers.sevenz.SevenZOutputFile out =
                     new org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(target.toFile())) {
            for (Path path : entries) {
                String name = dir.relativize(path).toString().replace('\\', '/');
                org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry =
                        new org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry();
                entry.setName(name);
                boolean directory = Files.isDirectory(path);
                entry.setDirectory(directory);
                if (directory) {
                    out.putArchiveEntry(entry);
                    out.closeArchiveEntry();
                } else {
                    entry.setSize(Files.size(path));
                    out.putArchiveEntry(entry);
                    try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                        }
                    }
                    out.closeArchiveEntry();
                }
            }
        }
    }

    private static void jarFrom(Path dir, Path target) throws Exception {
        List<Path> entries = new ArrayList<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(path -> !path.equals(dir)).forEach(entries::add);
        }
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\r\nCreated-By: 格式作坊\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Path path : entries) {
                String name = dir.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    zip.putNextEntry(new ZipEntry(name + "/"));
                    zip.closeEntry();
                } else if (!"META-INF/MANIFEST.MF".equalsIgnoreCase(name)) {
                    zip.putNextEntry(new ZipEntry(name));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        }
    }

    private static void xzTo(Path source, Path target) throws Exception {
        try (OutputStream out = new org.tukaani.xz.XZOutputStream(
                new BufferedOutputStream(Files.newOutputStream(target)),
                new org.tukaani.xz.LZMA2Options());
             InputStream in = new BufferedInputStream(Files.newInputStream(source))) {
            in.transferTo(out);
        }
    }

    private static void tarFromPlain(Path dir, Path target) throws Exception {
        List<Path> entries = new ArrayList<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(path -> !path.equals(dir)).forEach(entries::add);
        }
        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(target))) {
            for (Path path : entries) {
                String name = dir.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    writeTarEntry(file, name + "/", 0L, true);
                } else {
                    writeTarEntry(file, name, Files.size(path), false);
                    Files.copy(path, file);
                    pad(file, Files.size(path));
                }
            }
            file.write(new byte[1024]);
        }
    }
    private static void unzipTo(Path source, Path target) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(source)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                safeName(entry.getName());
                Path out = target.resolve(entry.getName()).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("压缩包里有不安全的路径：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void untarTo(Path source, Path target) throws Exception {
        String name = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(source));
             InputStream stream = name.endsWith(".tar") ? raw : new GZIPInputStream(raw)) {
            byte[] header = new byte[512];
            while (readFully(stream, header) == 512) {
                boolean empty = true;
                for (byte value : header) {
                    if (value != 0) {
                        empty = false;
                        break;
                    }
                }
                if (empty) {
                    break;
                }
                String entryName = new String(header, 0, 100, StandardCharsets.UTF_8).trim();
                if (entryName.isEmpty()) {
                    break;
                }
                long size = parseOctal(header, 124, 12);
                boolean directory = header[156] == '5';
                safeName(entryName);
                Path out = target.resolve(entryName).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("压缩包里有不安全的路径：" + entryName);
                }
                if (directory) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    copyExactly(stream, out, size);
                }
                padSkip(stream, size);
            }
        }
    }

    private static void zipFrom(Path source, Path target) throws Exception {
        List<Path> entries = new ArrayList<>();
        Files.walk(source).filter(path -> !path.equals(source)).forEach(entries::add);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {
            for (Path path : entries) {
                String name = source.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    zip.putNextEntry(new ZipEntry(name + "/"));
                    zip.closeEntry();
                } else {
                    zip.putNextEntry(new ZipEntry(name));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        }
    }

    private static void tarFrom(Path source, Path target) throws Exception {
        List<Path> entries = new ArrayList<>();
        Files.walk(source).filter(path -> !path.equals(source)).forEach(entries::add);
        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(target));
             GZIPOutputStream gzip = new GZIPOutputStream(file)) {
            for (Path path : entries) {
                String name = source.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    writeTarEntry(gzip, name + "/", 0L, true);
                } else {
                    writeTarEntry(gzip, name, Files.size(path), false);
                    Files.copy(path, gzip);
                    pad(gzip, Files.size(path));
                }
            }
            gzip.write(new byte[1024]);
        }
    }

    private static void deleteTree(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private void packIso(TransferItem item, Path outputDirectory, Listener listener) throws Exception {
        Path target = unique(outputDirectory.resolve(item.source.getFileName() + ".iso"));
        item.detail = "正在写入 ISO…";
        listener.changed(item);
        IsoPack.write(item.source, target, item.source.getFileName().toString());
        item.output = target;
    }

    private void unpackIso(TransferItem item, Path outputDirectory, Listener listener) throws Exception {
        Path target = unique(outputDirectory.resolve(baseName(item.source) + "_内容"));
        item.detail = "正在解析 ISO…";
        listener.changed(item);
        IsoRead.extract(item.source, target);
        item.output = target;
    }

    private void mountIso(TransferItem item, Listener listener) throws Exception {
        item.detail = "正在挂载…";
        listener.changed(item);
        String path = item.source.toAbsolutePath().toString();
        String quoted = "'" + path.replace("'", "''") + "'";
        String script = "Mount-DiskImage -ImagePath " + quoted
                + " | Out-Null; $v = Get-DiskImage -ImagePath " + quoted
                + " | Get-Volume; if ($v.DriveLetter) { Start-Process ('file:///' + $v.DriveLetter + ':/'); $v.DriveLetter }";
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        process.waitFor();
        if (output.isEmpty()) {
            throw new IOException("挂载失败，可能需要管理员权限");
        }
    }

    private void signApk(TransferItem item, Path outputDirectory, Listener listener) throws Exception {
        if (this.keystore == null || !Files.isRegularFile(this.keystore)) {
            throw new IOException("请先选择 keystore 文件");
        }
        Path tools = this.toolsDirectory();
        Path javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Path apksigner = tools.resolve("apksigner.jar");
        if (!Files.isRegularFile(apksigner)) {
            throw new IOException("找不到 apksigner.jar：" + apksigner);
        }
        String base = baseName(item.source);
        Path input = item.source;
        Path aligned = null;
        if (this.zipalign) {
            Path zipalignExe = tools.resolve("zipalign.exe");
            if (Files.isRegularFile(zipalignExe)) {
                aligned = Files.createTempFile("zipalign-", ".apk");
                Files.deleteIfExists(aligned);
                run(List.of(zipalignExe.toString(), "-p", "-f", "4", input.toString(), aligned.toString()));
                input = aligned;
                item.progress = 40;
                item.detail = "zipalign 完成，开始签名…";
                listener.changed(item);
            }
        }
        Path target = unique(outputDirectory.resolve(base + "-signed.apk"));
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(javaExe.toString());
        command.add("-jar");
        command.add(apksigner.toString());
        command.add("sign");
        command.add("--ks");
        command.add(this.keystore.toString());
        command.add("--ks-pass");
        command.add("pass:" + this.keystorePassword);
        if (!this.alias.isEmpty()) {
            command.add("--ks-key-alias");
            command.add(this.alias);
        }
        command.add("--v1-signing-enabled");
        command.add(String.valueOf(this.v1));
        command.add("--v2-signing-enabled");
        command.add(String.valueOf(this.v2));
        command.add("--v3-signing-enabled");
        command.add(String.valueOf(this.v3));
        command.add("--out");
        command.add(target.toString());
        command.add(input.toString());
        item.progress = 60;
        item.detail = "正在签名…";
        listener.changed(item);
        String output = run(command);
        if (!Files.isRegularFile(target)) {
            throw new IOException(output.isBlank() ? "签名失败" : output);
        }
        item.output = target;
    }

    private static String run(java.util.List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(600, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("命令超时");
        }
        if (process.exitValue() != 0) {
            throw new IOException(output.isBlank() ? "命令失败" : output.trim());
        }
        return output;
    }

    private static Path unique(Path path) {
        if (!Files.exists(path)) {
            return path;
        }
        String name = path.getFileName().toString();
        String base = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            extension = name.substring(dot);
        }
        for (int index = 2; index < 999; index++) {
            Path candidate = path.resolveSibling(base + " (" + index + ")" + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return path;
    }
}
