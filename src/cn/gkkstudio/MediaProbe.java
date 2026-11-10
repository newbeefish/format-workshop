package cn.gkkstudio;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 认文件：图片 / 视频 / 音频 / 压缩包 / 未知。
 *
 * <p>先用文件头（魔数）和扩展名快速判断，再用一次 {@code ffmpeg -i} 拿到编码、分辨率、时长。
 * 这样小白用户把文件拖错板块时，程序能自己说清楚“这是什么、该去哪”。
 */
final class MediaProbe {

    enum Kind {
        IMAGE("图片"), VIDEO("视频"), AUDIO("音频"), ARCHIVE("压缩包"), UNKNOWN("未知");

        final String label;

        Kind(String label) {
            this.label = label;
        }

        /** 这个文件该去哪个板块；压缩包和认不出来的返回 null。 */
        Mode board() {
            return switch (this) {
                case IMAGE -> Mode.IMAGE;
                case VIDEO -> Mode.VIDEO;
                case AUDIO -> Mode.AUDIO;
                default -> null;
            };
        }
    }

    record Info(Kind kind, String videoCodec, String audioCodec, int width, int height,
                double duration, String note) {

        Mode board() {
            return this.kind.board();
        }

        /** “视频 · 1920×1080 · 03:21 · H.264 + AAC” 这样的一行。 */
        String summary() {
            StringBuilder builder = new StringBuilder(this.kind.label);
            if (this.width > 0 && this.height > 0) {
                builder.append(" · ").append(this.width).append('×').append(this.height);
            }
            if (this.duration > 0.5) {
                builder.append(" · ").append(clock(this.duration));
            }
            String codecs = codecText();
            if (!codecs.isEmpty()) {
                builder.append(" · ").append(codecs);
            }
            if (this.note != null && !this.note.isEmpty()) {
                builder.append(" · ").append(this.note);
            }
            return builder.toString();
        }

        private String codecText() {
            String video = this.videoCodec == null || this.videoCodec.isEmpty()
                    ? "" : FastPath.shortName(this.videoCodec);
            String audio = this.audioCodec == null || this.audioCodec.isEmpty()
                    ? "" : FastPath.shortName(this.audioCodec);
            if (!video.isEmpty() && !audio.isEmpty()) {
                return video + " + " + audio;
            }
            return video.isEmpty() ? audio : video;
        }
    }

    private MediaProbe() {
    }

    /** 快速判断（只看文件头 + 扩展名，不起进程）。 */
    static Kind sniff(Path path) {
        String extension = MediaEngine.extension(path);
        byte[] head = head(path, 32);
        if (looksArchive(head)) {
            return Kind.ARCHIVE;
        }
        Kind byMagic = byMagic(head, extension);
        if (byMagic != null) {
            return byMagic;
        }
        if (Formats.ENCRYPTED.contains(extension)) {
            return Kind.AUDIO;
        }
        if (Formats.IMAGE.contains(extension)) {
            return Kind.IMAGE;
        }
        if (Formats.VIDEO.contains(extension)) {
            return Kind.VIDEO;
        }
        if (Formats.AUDIO.contains(extension)) {
            return Kind.AUDIO;
        }
        return Kind.UNKNOWN;
    }

    /**
     * 完整识别：先 sniff，再用 ffmpeg 补全（有 ffmpeg 时几乎总能给出结论）。
     */
    static Info inspect(Path ffmpeg, Path path) {
        Kind sniffed = sniff(path);
        String extension = MediaEngine.extension(path);
        if (ffmpeg == null || !Files.isRegularFile(path)) {
            return new Info(sniffed, "", "", 0, 0, -1.0, archiveNote(sniffed, extension));
        }
        // 压缩包不用再问 ffmpeg
        if (sniffed == Kind.ARCHIVE) {
            return new Info(Kind.ARCHIVE, "", "", 0, 0, -1.0, "请到「杂项 → 解包」里处理");
        }
        if (Formats.ENCRYPTED.contains(extension)) {
            return new Info(Kind.AUDIO, "", "", 0, 0, -1.0, "加密音乐，会自动解密");
        }

        String text = probeText(ffmpeg, path);
        String videoCodec = firstMatch(VIDEO_STREAM, text);
        String audioCodec = firstMatch(AUDIO_STREAM, text);
        int width = 0;
        int height = 0;
        Matcher size = SIZE_PATTERN.matcher(text);
        if (size.find()) {
            try {
                width = Integer.parseInt(size.group(1));
                height = Integer.parseInt(size.group(2));
            } catch (NumberFormatException ignored) {
                width = 0;
                height = 0;
            }
        }
        double duration = parseDuration(text);

        Kind kind;
        if (sniffed == Kind.IMAGE || (videoCodec.isEmpty() && audioCodec.isEmpty() && sniffed != Kind.UNKNOWN)) {
            kind = sniffed;
        } else if (!videoCodec.isEmpty() && !audioCodec.isEmpty()) {
            kind = Kind.VIDEO;
        } else if (!audioCodec.isEmpty()) {
            kind = Kind.AUDIO;
        } else if (!videoCodec.isEmpty()) {
            // 只有一个视频流：可能是图片被 ffmpeg 当成了单帧视频
            kind = isStillCodec(videoCodec) && duration <= 0.1 ? Kind.IMAGE : Kind.VIDEO;
        } else {
            kind = sniffed == Kind.UNKNOWN ? Kind.UNKNOWN : sniffed;
        }
        String note = kind == Kind.UNKNOWN ? "FFmpeg 也读不出内容，可能文件损坏或不是媒体文件" : "";
        return new Info(kind, videoCodec, audioCodec, width, height, duration, note);
    }

    /** 给“拖错板块”用的提示语。 */
    static String describe(Path path, Info info) {
        return path.getFileName() + "：" + info.summary();
    }

    // ------------------------------------------------------------------ 内部

    private static String archiveNote(Kind kind, String extension) {
        if (kind == Kind.ARCHIVE) {
            return "请到「杂项 → 解包」里处理";
        }
        return "";
    }

    private static final Pattern VIDEO_STREAM =
            Pattern.compile("Stream #\\d+:\\d+.*?: Video: ([A-Za-z0-9_]+)");
    private static final Pattern AUDIO_STREAM =
            Pattern.compile("Stream #\\d+:\\d+.*?: Audio: ([A-Za-z0-9_]+)");
    private static final Pattern SIZE_PATTERN =
            Pattern.compile(",\\s*(\\d{2,5})x(\\d{2,5})[\\s,\\[]");
    private static final Pattern DURATION_PATTERN =
            Pattern.compile("Duration:\\s*(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");

    private static String probeText(Path ffmpeg, Path path) {
        try {
            Process process = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-i", path.toString())
                    .redirectErrorStream(true).start();
            String text;
            try (InputStream stream = process.getInputStream()) {
                text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            process.waitFor(10L, TimeUnit.SECONDS);
            return text.length() > 200_000 ? text.substring(0, 200_000) : text;
        } catch (Exception exception) {
            return "";
        }
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static double parseDuration(String text) {
        Matcher matcher = DURATION_PATTERN.matcher(text);
        if (!matcher.find()) {
            return -1.0;
        }
        try {
            return Integer.parseInt(matcher.group(1)) * 3600
                    + Integer.parseInt(matcher.group(2)) * 60
                    + Double.parseDouble(matcher.group(3));
        } catch (NumberFormatException exception) {
            return -1.0;
        }
    }

    private static boolean isStillCodec(String codec) {
        String key = codec == null ? "" : codec.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "png", "mjpeg", "bmp", "tiff", "webp", "heic", "avif", "gif", "jpeg2000", "jpegls" -> true;
            default -> false;
        };
    }

    private static byte[] head(Path path, int count) {
        try (InputStream stream = Files.newInputStream(path)) {
            return stream.readNBytes(count);
        } catch (Exception exception) {
            return new byte[0];
        }
    }

    private static boolean looksArchive(byte[] head) {
        if (head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            return true;
        }
        if (head.length >= 6 && head[0] == '7' && head[1] == 'z' && head[2] == (byte) 0xBC
                && head[3] == (byte) 0xAF && head[4] == 0x27 && head[5] == 0x1C) {
            return true;
        }
        if (head.length >= 4 && head[0] == 'R' && head[1] == 'a' && head[2] == 'r' && head[3] == '!') {
            return true;
        }
        return head.length >= 2 && head[0] == (byte) 0x1F && head[1] == (byte) 0x8B;
    }

    /** 文件头能确定的类型；确定不了返回 null。 */
    private static Kind byMagic(byte[] head, String extension) {
        if (head.length >= 8 && head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return Kind.IMAGE;
        }
        if (head.length >= 3 && head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF) {
            return Kind.IMAGE;
        }
        if (head.length >= 4 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') {
            return Kind.IMAGE;
        }
        if (head.length >= 2 && head[0] == 'B' && head[1] == 'M' && Formats.IMAGE.contains(extension)) {
            return Kind.IMAGE;
        }
        if (head.length >= 4 && ((head[0] == 'I' && head[1] == 'I' && head[2] == 0x2A && head[3] == 0)
                || (head[0] == 'M' && head[1] == 'M' && head[2] == 0 && head[3] == 0x2A))) {
            return Kind.IMAGE;
        }
        if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return Kind.IMAGE;
        }
        if (head.length >= 4 && head[0] == '8' && head[1] == 'B' && head[2] == 'P' && head[3] == 'S') {
            return Kind.IMAGE;
        }
        if (head.length >= 4 && head[0] == 0 && head[1] == 0 && head[2] == 1 && head[3] == 0
                && ("ico".equals(extension) || "cur".equals(extension))) {
            return Kind.IMAGE;
        }
        // ISO 基媒体（MP4/MOV/M4A/HEIC/AVIF）都有 ftyp box
        if (head.length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            String brand = new String(head, 8, 4, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
            return switch (brand) {
                case "heic", "heix", "heim", "heis", "hevc", "mif1", "msf1", "avif", "avis" -> Kind.IMAGE;
                case "m4a ", "m4b ", "m4p " -> Kind.AUDIO;
                default -> Kind.VIDEO;
            };
        }
        // MPEG-TS：0x47 同步字节，连续几个包都对齐
        if (head.length >= 2 && head[0] == 0x47) {
            return Kind.VIDEO;
        }
        // FLAC / OGG / RIFF-WAVE / MP3(ID3 或帧同步)
        if (head.length >= 4 && head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
            return Kind.AUDIO;
        }
        if (head.length >= 4 && head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') {
            return Kind.AUDIO;
        }
        if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E') {
            return Kind.AUDIO;
        }
        if (head.length >= 3 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            return Kind.AUDIO;
        }
        if (head.length >= 2 && head[0] == (byte) 0xFF && (head[1] & 0xE0) == 0xE0) {
            return Kind.AUDIO;
        }
        // MKV / WebM：EBML 头 1A 45 DF A3
        if (head.length >= 4 && head[0] == 0x1A && head[1] == 0x45 && head[2] == (byte) 0xDF && head[3] == (byte) 0xA3) {
            return "webm".equals(extension) ? Kind.VIDEO : Kind.VIDEO;
        }
        return null;
    }

    /** 03:21 / 1:02:03 */
    static String clock(double seconds) {
        long total = Math.max(0, Math.round(seconds));
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long rest = total % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, rest);
    }
}
