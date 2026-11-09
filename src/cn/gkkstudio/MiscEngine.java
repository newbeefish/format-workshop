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
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private ExecutorService executor;
    private boolean pdfWarned;

    MiscEngine(Path logDirectory) {
        this.logDirectory = logDirectory;
    }

    void run(List<TransferItem> items, MiscAction action, Path outputDirectory, String imageFormat,
             int concurrency, Listener listener, Runnable onDone) {
        this.cancelled.set(false);
        this.executor = Executors.newFixedThreadPool(Math.max(1, concurrency), runnable -> {
            Thread thread = new Thread(runnable, "misc-worker");
            thread.setDaemon(true);
            return thread;
        });
        for (TransferItem item : items) {
            this.executor.submit(() -> {
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
        this.executor.shutdown();
        new Thread(() -> {
            try {
                while (!this.executor.awaitTermination(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (this.cancelled.get()) {
                        this.executor.shutdownNow();
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
        }
        item.progress = 100;
        item.state = TransferItem.State.DONE;
        item.detail = "已完成" + (item.output == null ? "" : " · " + item.output.getFileName());
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
