package cn.gkkstudio;

import java.util.List;
import java.util.Set;

/** 三个转换板块：图片 / 视频 / 音频。 */
enum Mode {
    IMAGE("图片转换", "换格式、改尺寸、压体积。", "添加你的图片", "拖入图片，或从电脑中选择", Formats.IMAGE),
    VIDEO("视频转换", "换封装、换编码、压缩体积。", "添加你的视频", "拖入视频，或从电脑中选择", Formats.VIDEO),
    AUDIO("音频转换", "换格式；KGG / KGM / NCM / QMC 自动解密。", "添加你的音乐", "拖入音频或视频，也可从电脑中选择", audioInputs());

    /** 音频板块还能直接吃视频（抽音轨）和加密音乐容器。 */
    private static Set<String> audioInputs() {
        java.util.HashSet<String> all = new java.util.HashSet<>(Formats.AUDIO);
        all.addAll(Formats.VIDEO);
        all.addAll(Formats.ENCRYPTED);
        return Set.copyOf(all);
    }

    final String title;
    final String subtitle;
    final String dropTitle;
    final String dropHint;
    final Set<String> extensions;

    Mode(String title, String subtitle, String dropTitle, String dropHint, Set<String> extensions) {
        this.title = title;
        this.subtitle = subtitle;
        this.dropTitle = dropTitle;
        this.dropHint = dropHint;
        this.extensions = extensions;
    }

    boolean supports(String extension) {
        return this.extensions.contains(extension);
    }

    String shortName() {
        return switch (this) {
            case IMAGE -> "图片";
            case VIDEO -> "视频";
            case AUDIO -> "音频";
        };
    }

    /** 拒收时的提示文案。 */
    String supportedHint() {
        return switch (this) {
            case IMAGE -> "支持的图片：jpg / jpeg / png / webp / bmp / tif / tiff / gif / ico / tga / ppm";
            case VIDEO -> "支持的视频：mp4 / mkv / avi / mov / webm / flv / wmv / ts / m4v / mpg / mpeg / 3gp";
            case AUDIO -> "支持的音频：mp3 / wav / flac / m4a / ogg / opus / wma / ape，"
                    + "以及酷狗 KGG/KGM、网易云 NCM、QQ 音乐 QMC";
        };
    }

    Object[] formatOptions() {
        return switch (this) {
            case IMAGE -> ImageFormat.values();
            case VIDEO -> VideoFormat.values();
            case AUDIO -> AudioFormat.values();
        };
    }

    Object[] sizeOptions() {
        return switch (this) {
            case IMAGE -> ImageScale.values();
            case VIDEO -> VideoResolution.values();
            case AUDIO -> null;
        };
    }

    String sizeLabel() {
        return switch (this) {
            case IMAGE -> "尺寸";
            case VIDEO -> "分辨率";
            case AUDIO -> "";
        };
    }

    Object[] qualityOptions() {
        return switch (this) {
            case IMAGE -> ImageQuality.values();
            case VIDEO -> VideoQuality.values();
            case AUDIO -> AudioQuality.values();
        };
    }

    String qualityLabel() {
        return switch (this) {
            case IMAGE -> "画质";
            case VIDEO -> "质量";
            case AUDIO -> "音质（仅 MP3）";
        };
    }

    String formatLabel() {
        return "输出格式";
    }
}

/** 图片输出格式。 */
enum ImageFormat {
    PNG(".png", "PNG · 无损"),
    JPG(".jpg", "JPG · 通用"),
    WEBP(".webp", "WEBP · 体积小"),
    BMP(".bmp", "BMP · 位图"),
    TIFF(".tiff", "TIFF · 印刷");

    final String extension;
    final String label;

    ImageFormat(String extension, String label) {
        this.extension = extension;
        this.label = label;
    }

    boolean usesQuality() {
        return this == JPG || this == WEBP;
    }

    @Override
    public String toString() {
        return this.label;
    }
}

enum ImageScale {
    ORIGINAL("原尺寸", null),
    MAX_1920("最大边 1920", 1920),
    MAX_1280("最大边 1280", 1280),
    MAX_800("最大边 800", 800),
    HALF("缩小一半", -1);

    final String label;
    final Integer maxEdge;

    ImageScale(String label, Integer maxEdge) {
        this.label = label;
        this.maxEdge = maxEdge;
    }

    String filter() {
        if (this.maxEdge == null) {
            return null;
        }
        if (this.maxEdge < 0) {
            return "scale=iw/2:ih/2";
        }
        return "scale=w='min(" + this.maxEdge + ",iw)':h='min(" + this.maxEdge + ",ih)':force_original_aspect_ratio=decrease";
    }

    @Override
    public String toString() {
        return this.label;
    }
}

enum ImageQuality {
    HIGH("高质量", 2, 92),
    STANDARD("标准", 5, 82),
    SMALL("小体积", 9, 68);

    final String label;
    final int mjpegQ;
    final int webpQuality;

    ImageQuality(String label, int mjpegQ, int webpQuality) {
        this.label = label;
        this.mjpegQ = mjpegQ;
        this.webpQuality = webpQuality;
    }

    @Override
    public String toString() {
        return this.label;
    }
}

/** 视频输出格式。 */
enum VideoFormat {
    MP4(".mp4", "MP4 · H.264", "libx264", true),
    MKV(".mkv", "MKV · H.264", "libx264", false),
    MOV(".mov", "MOV · H.264", "libx264", false),
    WEBM(".webm", "WEBM · VP9", "libvpx-vp9", false),
    AVI(".avi", "AVI · MPEG-4", "mpeg4", false),
    GIF(".gif", "GIF 动图", "gif", false);

    final String extension;
    final String label;
    final String videoCodec;
    final boolean faststart;

    VideoFormat(String extension, String label, String videoCodec, boolean faststart) {
        this.extension = extension;
        this.label = label;
        this.videoCodec = videoCodec;
        this.faststart = faststart;
    }

    boolean isGif() {
        return this == GIF;
    }

    @Override
    public String toString() {
        return this.label;
    }
}

enum VideoResolution {
    ORIGINAL("原分辨率", 0),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480);

    final String label;
    final int height;

    VideoResolution(String label, int height) {
        this.label = label;
        this.height = height;
    }

    String filter() {
        if (this == ORIGINAL) {
            return null;
        }
        return "scale=w=-2:h='min(" + this.height + ",ih)':force_original_aspect_ratio=decrease:force_divisible_by=2";
    }

    @Override
    public String toString() {
        return this.label;
    }
}

enum VideoQuality {
    HIGH("高质量", 18, 24),
    STANDARD("标准", 23, 32),
    SMALL("小体积", 28, 40);

    final String label;
    final int crf;
    final int vp9Crf;

    VideoQuality(String label, int crf, int vp9Crf) {
        this.label = label;
        this.crf = crf;
        this.vp9Crf = vp9Crf;
    }

    @Override
    public String toString() {
        return this.label;
    }
}

/** 音频输出格式。 */
enum AudioFormat {
    MP3(".mp3", "MP3", true),
    WAV(".wav", "WAV", false),
    FLAC(".flac", "FLAC", false),
    M4A(".m4a", "M4A", false),
    OGG(".ogg", "OGG", false),
    OPUS(".opus", "OPUS", false),
    WMA(".wma", "WMA", false);

    final String extension;
    final String label;
    final boolean supportsQuality;

    AudioFormat(String extension, String label, boolean supportsQuality) {
        this.extension = extension;
        this.label = label;
        this.supportsQuality = supportsQuality;
    }

    @Override
    public String toString() {
        return this.label;
    }
}

enum AudioQuality {
    HIGH("高质量", List.of("-q:a", "0")),
    STANDARD("标准", List.of("-q:a", "2")),
    COMPACT("省空间", List.of("-b:a", "128k"));

    final String label;
    final List<String> arguments;

    AudioQuality(String label, List<String> arguments) {
        this.label = label;
        this.arguments = arguments;
    }

    @Override
    public String toString() {
        return this.label;
    }
}

/** 根据输出格式与选项生成 ffmpeg 参数。 */
final class EncodeArgs {
    private EncodeArgs() {
    }

    static List<String> image(ImageFormat format, ImageQuality quality) {
        return switch (format) {
            case PNG -> List.of("-c:v", "png", "-compression_level", "6");
            case JPG -> List.of("-c:v", "mjpeg", "-q:v", String.valueOf(quality.mjpegQ), "-pix_fmt", "yuvj420p");
            case WEBP -> List.of("-c:v", "libwebp", "-quality", String.valueOf(quality.webpQuality), "-compression_level", "4");
            case BMP -> List.of("-c:v", "bmp", "-pix_fmt", "bgr24");
            case TIFF -> List.of("-c:v", "tiff", "-compression_algo", "deflate");
        };
    }

    static List<String> video(VideoFormat format, VideoQuality quality) {
        if (format.isGif()) {
            return List.of("-c:v", "gif", "-loop", "0", "-an");
        }
        if (format == VideoFormat.WEBM) {
            return List.of("-c:v", "libvpx-vp9", "-crf", String.valueOf(quality.vp9Crf), "-b:v", "0",
                    "-row-mt", "1", "-c:a", "libopus", "-b:a", "128k");
        }
        if (format == VideoFormat.AVI) {
            return List.of("-c:v", "mpeg4", "-q:v", String.valueOf(Math.max(2, quality.crf / 5)),
                    "-c:a", "libmp3lame", "-b:a", "192k");
        }
        List<String> args = new java.util.ArrayList<>(List.of(
                "-c:v", "libx264", "-crf", String.valueOf(quality.crf), "-preset", "medium",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k"));
        if (format.faststart) {
            args.addAll(List.of("-movflags", "+faststart"));
        }
        return args;
    }

    static List<String> audio(AudioFormat format, AudioQuality quality) {
        return switch (format) {
            case MP3 -> withQuality(List.of("-c:a", "libmp3lame"), quality);
            case WAV -> List.of("-c:a", "pcm_s16le");
            case FLAC -> List.of("-c:a", "flac");
            case M4A -> List.of("-c:a", "aac", "-b:a", "192k");
            case OGG -> List.of("-c:a", "libvorbis", "-q:a", "5");
            case OPUS -> List.of("-c:a", "libopus", "-b:a", "160k", "-vbr", "on");
            case WMA -> List.of("-c:a", "wmav2", "-b:a", "192k");
        };
    }

    private static List<String> withQuality(List<String> base, AudioQuality quality) {
        List<String> args = new java.util.ArrayList<>(base);
        args.addAll(quality.arguments);
        return args;
    }
}
