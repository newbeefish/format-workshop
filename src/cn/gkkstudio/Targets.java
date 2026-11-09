package cn.gkkstudio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 输出格式目标：按 FFmpeg 能写出的扩展名生成，不再写死几个枚举。
 * 启动时读一次 ffmpeg -encoders，本机编不出来的格式会自动从列表里去掉。
 */
final class Targets {

    /** 下拉框中的一个输出格式。 */
    record Target(String extension, String label) {
        String dotted() {
            return "." + this.extension;
        }

        @Override
        public String toString() {
            return this.label;
        }
    }

    private static final List<String> IMAGE_ORDER = List.of(
            "png", "jpg", "jpeg", "webp", "bmp", "tiff", "tif", "gif", "tga",
            "ppm", "pgm", "pbm", "pnm", "pcx", "dds", "exr", "hdr", "jp2", "j2k",
            "dpx", "qoi", "psd", "sgi", "sun", "xbm", "xpm", "pam", "pict", "pfm",
            "jfif", "jpe");

    private static final List<String> VIDEO_ORDER = List.of(
            "mp4", "mkv", "mov", "webm", "avi", "flv", "wmv", "ts", "m4v", "mpg",
            "mpeg", "3gp", "ogv", "vob", "m2ts", "mts", "asf", "f4v", "rm", "rmvb",
            "mxf", "nut", "y4m", "ivf", "h264", "mjpeg", "dv", "wtv", "3g2", "gif");

    private static final List<String> AUDIO_ORDER = List.of(
            "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma", "aiff", "ac3",
            "eac3", "dts", "mp2", "amr", "au", "mka", "wv", "tta", "spx", "caf",
            "m4b", "w64", "gsm", "voc", "xwma");

    private static final Set<String> IMAGE_BLACKLIST = Set.of(
            "ico", "cur", "icb", "vda", "vst", "dib", "rgba", "rgb", "bw", "ras",
            "pic", "cin", "j2c", "apng", "jfif", "jpe");

    private static final Set<String> VIDEO_BLACKLIST = Set.of(
            "m1v", "m2v", "m2p", "m2t", "m4s", "ismv", "dav", "roq", "nsv", "amv",
            "mvi", "mtaf", "mse", "h265", "hevc", "avc", "vc1", "gxf", "lxf", "mod",
            "tod", "rec", "pva", "divx", "xvid", "swf", "yuv",
            "rmvb", "m1a", "mpe", "qt", "rmd");

    private static final Set<String> AUDIO_BLACKLIST = Set.of(
            "dts", "dtshd", "wv", "xwma", "alac", "m4r", "mp1", "mpa", "snd", "wave");

    private static final Map<String, String> AUDIO_ENCODER = Map.ofEntries(
            Map.entry("mp3", "libmp3lame"), Map.entry("mpa", "libmp3lame"),
            Map.entry("mp2", "mp2"), Map.entry("mp1", "mp2"),
            Map.entry("wav", "pcm_s16le"), Map.entry("wave", "pcm_s16le"),
            Map.entry("w64", "pcm_s16le"), Map.entry("caf", "pcm_s16le"),
            Map.entry("flac", "flac"), Map.entry("mka", "flac"),
            Map.entry("aac", "aac"), Map.entry("m4a", "aac"),
            Map.entry("m4b", "aac"), Map.entry("m4r", "aac"),
            Map.entry("ogg", "libvorbis"), Map.entry("oga", "libvorbis"),
            Map.entry("opus", "libopus"),
            Map.entry("wma", "wmav2"), Map.entry("xwma", "wmav2"),
            Map.entry("aiff", "pcm_s16be"), Map.entry("aif", "pcm_s16be"),
            Map.entry("aifc", "pcm_s16be"), Map.entry("au", "pcm_s16be"),
            Map.entry("snd", "pcm_s16be"),
            Map.entry("ac3", "ac3"), Map.entry("eac3", "eac3"),
            Map.entry("dts", "dca"), Map.entry("dtshd", "dca"),
            Map.entry("amr", "libopencore_amrnb"),
            Map.entry("wv", "wavpack"), Map.entry("tta", "tta"),
            Map.entry("spx", "libspeex"), Map.entry("gsm", "libgsm"),
            Map.entry("voc", "pcm_u8"), Map.entry("alac", "alac"));

    private static final Map<String, String> VIDEO_ENCODER = Map.ofEntries(
            Map.entry("webm", "libvpx-vp9"), Map.entry("avi", "mpeg4"),
            Map.entry("wmv", "wmv2"), Map.entry("asf", "wmv2"),
            Map.entry("mpg", "mpeg2video"), Map.entry("mpeg", "mpeg2video"),
            Map.entry("mpe", "mpeg2video"), Map.entry("vob", "mpeg2video"),
            Map.entry("ogv", "libtheora"), Map.entry("y4m", "rawvideo"),
            Map.entry("ivf", "libvpx"), Map.entry("h264", "libx264"),
            Map.entry("mjpeg", "mjpeg"), Map.entry("mjpg", "mjpeg"),
            Map.entry("dv", "dvvideo"), Map.entry("gif", "gif"));

    private static final Map<String, String> IMAGE_ENCODER = Map.ofEntries(
            Map.entry("jpg", "mjpeg"), Map.entry("jpeg", "mjpeg"),
            Map.entry("jpe", "mjpeg"), Map.entry("jfif", "mjpeg"),
            Map.entry("webp", "libwebp"), Map.entry("tga", "targa"),
            Map.entry("jp2", "jpeg2000"), Map.entry("j2k", "jpeg2000"),
            Map.entry("qoi", "qoi"), Map.entry("psd", "psd"),
            Map.entry("sgi", "sgi"), Map.entry("sun", "sunrast"),
            Map.entry("xbm", "xbm"), Map.entry("xpm", "xpm"),
            Map.entry("pam", "pam"), Map.entry("pict", "pict"),
            Map.entry("pfm", "pfm"), Map.entry("dpx", "dpx"),
            Map.entry("exr", "exr"), Map.entry("hdr", "hdr"),
            Map.entry("pcx", "pcx"), Map.entry("dds", "dds"),
            Map.entry("gif", "gif"), Map.entry("bmp", "bmp"),
            Map.entry("tif", "tiff"), Map.entry("tiff", "tiff"),
            Map.entry("pnm", "pnm"), Map.entry("ppm", "ppm"),
            Map.entry("pgm", "pgm"), Map.entry("pbm", "pbm"));

    private static volatile Set<String> availableEncoders;

    private Targets() {
    }

    /** 启动时读一次 ffmpeg -encoders，用来过滤掉本机编不出来的格式。 */
    static synchronized void init(Path ffmpeg) {
        if (availableEncoders != null || ffmpeg == null) {
            return;
        }
        try {
            Process process = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-encoders")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(15L, TimeUnit.SECONDS);
            Set<String> names = new HashSet<>();
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 2 && parts[0].matches("[VAS][F.][S.][X.][B.][D.]")) {
                    names.add(parts[1]);
                }
            }
            availableEncoders = names;
        } catch (Exception exception) {
            availableEncoders = Set.of();
        }
    }

    private static boolean encoderAvailable(String encoder) {
        Set<String> known = availableEncoders;
        if (encoder == null) {
            return true;
        }
        if (known == null) {
            return true;
        }
        if (known.isEmpty()) {
            return false;
        }
        return known.contains(encoder);
    }

    static List<Target> forMode(Mode mode) {
        Set<String> source = switch (mode) {
            case IMAGE -> Formats.IMAGE;
            case VIDEO -> Formats.VIDEO;
            case AUDIO -> Formats.AUDIO;
        };
        List<String> order = switch (mode) {
            case IMAGE -> IMAGE_ORDER;
            case VIDEO -> VIDEO_ORDER;
            case AUDIO -> AUDIO_ORDER;
        };
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String extension : order) {
            if (source.contains(extension) && usable(mode, extension)) {
                ordered.add(extension);
            }
        }
        List<String> rest = new ArrayList<>(source);
        rest.sort(String::compareTo);
        for (String extension : rest) {
            if (usable(mode, extension)) {
                ordered.add(extension);
            }
        }
        List<Target> targets = new ArrayList<>();
        for (String extension : ordered) {
            targets.add(new Target(extension, labelOf(mode, extension)));
        }
        return targets;
    }

    private static boolean usable(Mode mode, String extension) {
        return switch (mode) {
            case IMAGE -> !IMAGE_BLACKLIST.contains(extension)
                    && encoderAvailable(IMAGE_ENCODER.get(extension));
            case VIDEO -> !VIDEO_BLACKLIST.contains(extension)
                    && encoderAvailable(VIDEO_ENCODER.get(extension));
            case AUDIO -> {
                if (AUDIO_BLACKLIST.contains(extension)) {
                    yield false;
                }
                String encoder = AUDIO_ENCODER.get(extension);
                String bare = extension.replace("wave", "wav");
                encoder = encoder != null ? encoder : AUDIO_ENCODER.get(bare);
                yield encoder != null && encoderAvailable(encoder);
            }
        };
    }

    private static String labelOf(Mode mode, String extension) {
        String note = switch (extension) {
            case "mp4" -> " · H.264";
            case "mkv" -> " · 通用封装";
            case "webm" -> " · VP9";
            case "gif" -> " · 动图无声音";
            case "png" -> " · 无损";
            case "jpg", "jpeg" -> " · 最通用";
            case "webp" -> " · 体积小";
            case "flac" -> " · 无损音频";
            case "wav" -> " · 未压缩";
            case "mp3" -> " · 通用音频";
            case "aac", "m4a" -> " · 高效";
            case "opus" -> " · 低码率强";
            default -> "";
        };
        return extension.toUpperCase(java.util.Locale.ROOT) + note;
    }

    static boolean usesQuality(Mode mode, Target target) {
        return switch (mode) {
            case IMAGE -> Set.of("jpg", "jpeg", "jpe", "jfif", "webp").contains(target.extension());
            case VIDEO -> !"gif".equals(target.extension());
            case AUDIO -> "mp3".equals(target.extension());
        };
    }

    static boolean isGif(Target target) {
        return "gif".equals(target.extension());
    }

    // ------------------------------------------------------------------ 参数

    static List<String> imageArgs(Target target, ImageQuality quality) {
        return switch (target.extension()) {
            case "jpg", "jpeg", "jpe", "jfif" ->
                    List.of("-c:v", "mjpeg", "-q:v", String.valueOf(quality.mjpegQ), "-pix_fmt", "yuvj420p");
            case "webp" -> List.of("-c:v", "libwebp", "-quality", String.valueOf(quality.webpQuality),
                    "-compression_level", "4");
            case "bmp", "dib" -> List.of("-c:v", "bmp", "-pix_fmt", "bgr24");
            case "tif", "tiff" -> List.of("-c:v", "tiff", "-compression_algo", "deflate");
            case "gif" -> List.of("-c:v", "gif");
            case "tga" -> List.of("-c:v", "targa");
            case "pcx" -> List.of("-c:v", "pcx");
            case "dds" -> List.of("-c:v", "dds");
            case "exr" -> List.of("-c:v", "exr");
            case "hdr" -> List.of("-c:v", "hdr");
            case "jp2", "j2k" -> List.of("-c:v", "jpeg2000");
            case "dpx" -> List.of("-c:v", "dpx");
            case "qoi" -> List.of("-c:v", "qoi");
            case "psd" -> List.of("-c:v", "psd");
            case "sgi" -> List.of("-c:v", "sgi");
            case "sun" -> List.of("-c:v", "sunrast");
            case "xbm" -> List.of("-c:v", "xbm");
            case "xpm" -> List.of("-c:v", "xpm");
            case "pam" -> List.of("-c:v", "pam");
            case "pfm" -> List.of("-c:v", "pfm");
            case "pict" -> List.of("-c:v", "pict");
            case "pgm" -> List.of("-c:v", "pgm");
            case "pbm" -> List.of("-c:v", "pbm");
            case "pnm" -> List.of("-c:v", "pnm");
            case "ppm" -> List.of("-c:v", "ppm");
            default -> List.of("-c:v", "png", "-compression_level", "6");
        };
    }

    static List<String> videoArgs(Target target, VideoQuality quality) {
        String extension = target.extension();
        if ("gif".equals(extension)) {
            return List.of("-c:v", "gif", "-loop", "0", "-an");
        }
        return switch (extension) {
            case "webm" -> List.of("-c:v", "libvpx-vp9", "-crf", String.valueOf(quality.vp9Crf),
                    "-b:v", "0", "-row-mt", "1", "-c:a", "libopus", "-b:a", "128k");
            case "avi" -> List.of("-c:v", "mpeg4", "-q:v", String.valueOf(Math.max(2, quality.crf / 5)),
                    "-c:a", "libmp3lame", "-b:a", "192k");
            case "wmv", "asf" -> List.of("-c:v", "wmv2", "-q:v", String.valueOf(Math.max(2, quality.crf / 5)),
                    "-c:a", "wmav2", "-b:a", "192k");
            case "mpg", "mpeg", "mpe", "vob" -> List.of("-c:v", "mpeg2video",
                    "-q:v", String.valueOf(Math.max(2, quality.crf / 5)), "-c:a", "mp2", "-b:a", "192k");
            case "ogv" -> List.of("-c:v", "libtheora", "-q:v", "7", "-c:a", "libvorbis", "-b:a", "160k");
            case "y4m" -> List.of("-c:v", "rawvideo", "-an");
            case "ivf" -> List.of("-c:v", "libvpx", "-b:v", "2M", "-an");
            case "h264" -> List.of("-c:v", "libx264", "-crf", String.valueOf(quality.crf), "-an");
            case "mjpeg", "mjpg" -> List.of("-c:v", "mjpeg",
                    "-q:v", String.valueOf(Math.max(2, quality.crf / 5)), "-an");
            case "dv" -> List.of("-c:v", "dvvideo", "-s", "720x576", "-c:a", "pcm_s16le");
            default -> List.of("-c:v", "libx264", "-crf", String.valueOf(quality.crf),
                    "-preset", "medium", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k");
        };
    }

    static List<String> audioArgs(Target target, AudioQuality quality) {
        List<String> base = switch (target.extension()) {
            case "mp3" -> List.of("-c:a", "libmp3lame");
            case "wav", "wave" -> List.of("-c:a", "pcm_s16le");
            case "flac" -> List.of("-c:a", "flac");
            case "aac" -> List.of("-c:a", "aac", "-b:a", "192k", "-f", "adts");
            case "m4a", "m4b", "m4r" -> List.of("-c:a", "aac", "-b:a", "192k");
            case "ogg", "oga" -> List.of("-c:a", "libvorbis", "-q:a", "5");
            case "opus" -> List.of("-c:a", "libopus", "-b:a", "160k");
            case "wma", "xwma" -> List.of("-c:a", "wmav2", "-b:a", "192k");
            case "aiff", "aif", "aifc" -> List.of("-c:a", "pcm_s16be");
            case "ac3" -> List.of("-c:a", "ac3", "-b:a", "192k");
            case "eac3" -> List.of("-c:a", "eac3", "-b:a", "192k");
            case "dts", "dtshd" -> List.of("-c:a", "dca", "-b:a", "755k");
            case "mp2", "mp1" -> List.of("-c:a", "mp2", "-b:a", "192k");
            case "au", "snd" -> List.of("-c:a", "pcm_s16be");
            case "amr" -> List.of("-c:a", "libopencore_amrnb", "-ar", "8000", "-ac", "1");
            case "wv" -> List.of("-c:a", "wavpack", "-sample_fmt", "s16");
            case "tta" -> List.of("-c:a", "tta", "-sample_fmt", "s16");
            case "spx" -> List.of("-c:a", "libspeex", "-ar", "16000", "-ac", "1");
            case "caf", "w64" -> List.of("-c:a", "pcm_s16le");
            case "gsm" -> List.of("-c:a", "libgsm", "-ar", "8000", "-ac", "1");
            case "voc" -> List.of("-c:a", "pcm_u8");
            case "mka" -> List.of("-c:a", "flac");
            case "alac" -> List.of("-c:a", "alac");
            default -> List.of("-c:a", "aac", "-b:a", "192k");
        };
        if ("mp3".equals(target.extension())) {
            List<String> withQuality = new ArrayList<>(base);
            withQuality.addAll(quality.arguments);
            return withQuality;
        }
        return base;
    }
}
