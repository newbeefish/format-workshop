package cn.gkkstudio;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「能只换容器就别重编码」——FFmpeg 的 -c copy。
 *
 * <p>这张表里的绝大多数慢场景（TS→MP4、M4S→MP4、HEVC→MP4、WebM→MKV、AAC 换容器 …）
 * 本质都是在做无谓的重编码。这里按「容器能不能装下源文件的编码」来判断：
 * 装得下就只封装，装不下才重编码。
 */
final class FastPath {

    /** 决定走哪条路。 */
    enum Kind {
        /** 视频和音频都原样封装，最快。 */
        COPY_ALL,
        /** 视频原样封装、音频必须重编码（例如 FLAC 音轨装进 MP4）。 */
        COPY_VIDEO,
        /** 老老实实重编码。 */
        ENCODE
    }

    record Decision(Kind kind, String note) {
        boolean copying() {
            return this.kind != Kind.ENCODE;
        }
    }

    private FastPath() {
    }

    // ------------------------------------------------------------------ 表

    /** 视频容器能装哪些视频编码（ffmpeg 的 codec_name）。 */
    private static final Map<String, Set<String>> VIDEO_IN = Map.ofEntries(
            Map.entry("mp4", Set.of("h264", "hevc", "mpeg4", "av1", "mjpeg")),
            Map.entry("m4v", Set.of("h264", "hevc", "mpeg4", "av1", "mjpeg")),
            Map.entry("mov", Set.of("h264", "hevc", "mpeg4", "av1", "mjpeg", "prores", "dvvideo", "mpeg2video", "png")),
            Map.entry("3gp", Set.of("h264", "mpeg4", "h263")),
            Map.entry("mkv", Set.of("h264", "hevc", "vp8", "vp9", "av1", "mpeg4", "mpeg2video",
                    "mpeg1video", "prores", "theora", "wmv1", "wmv2", "wmv3", "dvvideo", "mjpeg",
                    "flv1", "h263", "msmpeg4v3", "rv40")),
            Map.entry("nut", Set.of("h264", "hevc", "vp8", "vp9", "av1", "mpeg4", "mpeg2video",
                    "mpeg1video", "prores", "theora", "wmv1", "wmv2", "dvvideo", "mjpeg", "flv1")),
            Map.entry("webm", Set.of("vp8", "vp9", "av1")),
            Map.entry("avi", Set.of("mpeg4", "mjpeg", "mpeg2video", "mpeg1video", "wmv2", "dvvideo", "h264")),
            Map.entry("flv", Set.of("h264", "flv1", "vp6f")),
            Map.entry("wmv", Set.of("wmv1", "wmv2", "wmv3", "vc1")),
            Map.entry("ts", Set.of("h264", "hevc", "mpeg2video", "mpeg1video")),
            Map.entry("m2ts", Set.of("h264", "hevc", "mpeg2video", "mpeg1video")),
            Map.entry("mpg", Set.of("mpeg2video", "mpeg1video")),
            Map.entry("ogv", Set.of("theora")),
            Map.entry("mxf", Set.of("mpeg2video", "prores", "dnxhd", "dvvideo")),
            Map.entry("dv", Set.of("dvvideo")),
            Map.entry("gif", Set.of()));

    /** 视频容器能装哪些音频编码。 */
    private static final Map<String, Set<String>> AUDIO_IN_VIDEO = Map.ofEntries(
            Map.entry("mp4", Set.of("aac", "mp3", "ac3", "eac3", "alac", "opus")),
            Map.entry("m4v", Set.of("aac", "mp3", "ac3", "eac3", "alac")),
            Map.entry("mov", Set.of("aac", "mp3", "ac3", "eac3", "alac", "pcm_s16le", "pcm_s24le", "pcm_s16be")),
            Map.entry("3gp", Set.of("aac", "amr_nb")),
            Map.entry("mkv", Set.of("aac", "mp3", "ac3", "eac3", "dts", "flac", "opus", "vorbis", "alac",
                    "pcm_s16le", "pcm_s24le", "wmav2", "mp2")),
            Map.entry("nut", Set.of("aac", "mp3", "ac3", "eac3", "dts", "flac", "opus", "vorbis", "alac",
                    "pcm_s16le", "pcm_s24le", "wmav2", "mp2")),
            Map.entry("webm", Set.of("opus", "vorbis")),
            Map.entry("avi", Set.of("mp3", "ac3", "pcm_s16le", "wmav2", "mp2")),
            Map.entry("flv", Set.of("aac", "mp3")),
            Map.entry("wmv", Set.of("wmav1", "wmav2", "wmapro")),
            Map.entry("ts", Set.of("aac", "mp3", "ac3", "eac3", "mp2", "dts")),
            Map.entry("m2ts", Set.of("aac", "ac3", "eac3", "mp2", "dts", "pcm_bluray")),
            Map.entry("mpg", Set.of("mp2", "ac3", "mp3", "pcm_s16le")),
            Map.entry("ogv", Set.of("vorbis", "opus")),
            Map.entry("mxf", Set.of("pcm_s16le", "pcm_s24le", "mp2")),
            Map.entry("dv", Set.of("pcm_s16le")),
            Map.entry("gif", Set.of()));

    /** 纯音频容器能装哪些音频编码。 */
    private static final Map<String, Set<String>> AUDIO_IN_AUDIO = Map.ofEntries(
            Map.entry("mp3", Set.of("mp3")),
            Map.entry("wav", Set.of("pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_u8")),
            Map.entry("w64", Set.of("pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le")),
            Map.entry("flac", Set.of("flac")),
            Map.entry("aac", Set.of("aac")),
            Map.entry("m4a", Set.of("aac", "alac", "ac3", "eac3")),
            Map.entry("m4b", Set.of("aac", "alac")),
            Map.entry("ogg", Set.of("vorbis", "opus", "flac")),
            Map.entry("oga", Set.of("vorbis", "opus", "flac")),
            Map.entry("opus", Set.of("opus")),
            Map.entry("wma", Set.of("wmav1", "wmav2", "wmapro")),
            Map.entry("aiff", Set.of("pcm_s16be", "pcm_s24be", "pcm_s16le")),
            Map.entry("ac3", Set.of("ac3")),
            Map.entry("eac3", Set.of("eac3")),
            Map.entry("mp2", Set.of("mp2")),
            Map.entry("amr", Set.of("amr_nb", "amr_wb")),
            Map.entry("au", Set.of("pcm_s16be", "pcm_mulaw", "pcm_alaw")),
            Map.entry("mka", Set.of("aac", "mp3", "ac3", "eac3", "dts", "flac", "opus", "vorbis", "alac",
                    "pcm_s16le", "pcm_s24le", "wmav2", "mp2")),
            Map.entry("tta", Set.of("tta")),
            Map.entry("spx", Set.of("speex")),
            Map.entry("caf", Set.of("pcm_s16le", "pcm_s24le", "pcm_f32le", "alac", "aac")),
            Map.entry("gsm", Set.of("gsm")),
            Map.entry("voc", Set.of("pcm_u8")),
            Map.entry("dts", Set.of("dts")),
            Map.entry("dtshd", Set.of("dts")),
            Map.entry("alac", Set.of("alac")));

    /** 编码器 id → ffmpeg 的 codec_name，用来跟源文件比对。 */
    private static final Map<String, String> VIDEO_NAME = Map.ofEntries(
            Map.entry("libx264", "h264"), Map.entry("libx265", "hevc"),
            Map.entry("libvpx-vp9", "vp9"), Map.entry("libvpx", "vp8"),
            Map.entry("libaom-av1", "av1"), Map.entry("mpeg4", "mpeg4"),
            Map.entry("mpeg2video", "mpeg2video"), Map.entry("prores_ks", "prores"),
            Map.entry("wmv2", "wmv2"), Map.entry("libtheora", "theora"),
            Map.entry("dvvideo", "dvvideo"), Map.entry("gif", "gif"));

    private static final Map<String, String> AUDIO_NAME = Map.ofEntries(
            Map.entry("aac", "aac"), Map.entry("alac", "alac"), Map.entry("vorbis", "vorbis"),
            Map.entry("opus", "opus"), Map.entry("flac", "flac"),
            Map.entry("pcm16", "pcm_s16le"), Map.entry("pcm24", "pcm_s24le"),
            Map.entry("pcm16be", "pcm_s16be"), Map.entry("pcm24be", "pcm_s24be"),
            Map.entry("libmp3lame", "mp3"), Map.entry("libvorbis", "vorbis"),
            Map.entry("libopus", "opus"), Map.entry("wmav2", "wmav2"),
            Map.entry("pcm_s16le", "pcm_s16le"), Map.entry("pcm_s16be", "pcm_s16be"),
            Map.entry("pcm_s24le", "pcm_s24le"), Map.entry("pcm_s24be", "pcm_s24be"),
            Map.entry("ac3", "ac3"), Map.entry("eac3", "eac3"), Map.entry("mp2", "mp2"),
            Map.entry("libopencore_amrnb", "amr_nb"), Map.entry("wavpack", "wavpack"),
            Map.entry("tta", "tta"), Map.entry("libspeex", "speex"), Map.entry("libgsm", "gsm"),
            Map.entry("pcm_u8", "pcm_u8"), Map.entry("dca", "dts"));

    static String videoName(String encoderId) {
        return VIDEO_NAME.getOrDefault(encoderId, encoderId == null ? "" : encoderId.toLowerCase(Locale.ROOT));
    }

    static String audioName(String encoderId) {
        return AUDIO_NAME.getOrDefault(encoderId, encoderId == null ? "" : encoderId.toLowerCase(Locale.ROOT));
    }

    /** 容器能不能装下这个视频编码。 */
    static boolean fitsVideo(String container, String codecName) {
        if (codecName == null || codecName.isEmpty()) {
            return false;
        }
        Set<String> allowed = VIDEO_IN.get(container.toLowerCase(Locale.ROOT));
        return allowed != null && !allowed.isEmpty() && allowed.contains(codecName.toLowerCase(Locale.ROOT));
    }

    /** 视频容器能不能装下这个音频编码。 */
    static boolean fitsVideoAudio(String container, String codecName) {
        if (codecName == null || codecName.isEmpty()) {
            return false;
        }
        Set<String> allowed = AUDIO_IN_VIDEO.get(container.toLowerCase(Locale.ROOT));
        return allowed != null && !allowed.isEmpty() && allowed.contains(codecName.toLowerCase(Locale.ROOT));
    }

    /** 纯音频容器能不能装下这个音频编码。 */
    static boolean fitsAudio(String container, String codecName) {
        if (codecName == null || codecName.isEmpty()) {
            return false;
        }
        Set<String> allowed = AUDIO_IN_AUDIO.get(container.toLowerCase(Locale.ROOT));
        return allowed != null && !allowed.isEmpty() && allowed.contains(codecName.toLowerCase(Locale.ROOT));
    }

    /** 容器是不是「什么都能装」的兜底容器。 */
    static boolean isUniversal(String container) {
        String key = container == null ? "" : container.toLowerCase(Locale.ROOT);
        return "mkv".equals(key) || "nut".equals(key) || "mka".equals(key);
    }

    // ------------------------------------------------------------------ 决策

    /**
     * 视频板块：决定封装还是重编码。
     *
     * @param copyPreferred 用户在“视频编码”里选的是「原样复制」
     * @param resized       选了非原分辨率（一旦缩放就必须重编码）
     */
    static Decision decideVideo(String container, String sourceVideo, String sourceAudio,
                                boolean copyPreferred, boolean resized) {
        if (resized) {
            return new Decision(Kind.ENCODE, "缩放过画面，必须重编码");
        }
        if (!copyPreferred) {
            return new Decision(Kind.ENCODE, "按选定的编码重编码");
        }
        if (!fitsVideo(container, sourceVideo)) {
            String note = sourceVideo == null || sourceVideo.isEmpty()
                    ? "没认出来源视频编码，按容器默认编码重编码"
                    : "源视频是 " + sourceVideo.toUpperCase(Locale.ROOT) + "，装不进 "
                    + container.toUpperCase(Locale.ROOT) + "，只能重编码";
            return new Decision(Kind.ENCODE, note);
        }
        boolean audioFits = sourceAudio == null || sourceAudio.isEmpty() || fitsVideoAudio(container, sourceAudio);
        if (audioFits) {
            String audioText = sourceAudio == null || sourceAudio.isEmpty() ? "无音轨" : shortName(sourceAudio);
            return new Decision(Kind.COPY_ALL,
                    "直接封装 · " + shortName(sourceVideo) + " + " + audioText + "（不重编码）");
        }
        return new Decision(Kind.COPY_VIDEO,
                "视频直接封装（" + shortName(sourceVideo) + "），音轨 " + shortName(sourceAudio)
                        + " 装不进 " + container.toUpperCase(Locale.ROOT) + "，单独重编码");
    }

    /** 音频板块：决定封装还是重编码。 */
    static Decision decideAudio(String container, String sourceAudio, boolean copyPreferred) {
        if (!copyPreferred) {
            return new Decision(Kind.ENCODE, "按选定的编码重编码");
        }
        if (!fitsAudio(container, sourceAudio)) {
            String note = sourceAudio == null || sourceAudio.isEmpty()
                    ? "没认出来源音频编码，按容器默认编码重编码"
                    : "源音频是 " + shortName(sourceAudio) + "，装不进 "
                    + container.toUpperCase(Locale.ROOT) + "，只能重编码";
            return new Decision(Kind.ENCODE, note);
        }
        return new Decision(Kind.COPY_ALL, "直接封装 · " + shortName(sourceAudio) + "（不重编码）");
    }

    /** 界面上显示的短名字，别把 pcm_s16le 这种原文甩给用户。 */
    static String shortName(String codec) {
        if (codec == null || codec.isEmpty()) {
            return "未知";
        }
        String key = codec.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "h264" -> "H.264";
            case "hevc", "h265" -> "H.265";
            case "vp8" -> "VP8";
            case "vp9" -> "VP9";
            case "av1" -> "AV1";
            case "mpeg4" -> "MPEG-4";
            case "mpeg2video" -> "MPEG-2";
            case "mpeg1video" -> "MPEG-1";
            case "prores" -> "ProRes";
            case "dnxhd" -> "DNxHD";
            case "theora" -> "Theora";
            case "wmv1", "wmv2", "wmv3" -> "WMV";
            case "vc1" -> "VC-1";
            case "dvvideo" -> "DV";
            case "flv1" -> "Sorenson";
            case "mjpeg" -> "MJPEG";
            case "aac" -> "AAC";
            case "mp3" -> "MP3";
            case "mp2" -> "MP2";
            case "ac3" -> "AC-3";
            case "eac3" -> "E-AC-3";
            case "dts" -> "DTS";
            case "flac" -> "FLAC";
            case "alac" -> "ALAC";
            case "opus" -> "Opus";
            case "vorbis" -> "Vorbis";
            case "wmav1", "wmav2", "wmapro" -> "WMA";
            case "amr_nb", "amr_wb" -> "AMR";
            case "speex" -> "Speex";
            case "gsm" -> "GSM";
            case "tta" -> "TTA";
            case "wavpack" -> "WavPack";
            case "pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s24be", "pcm_s32le",
                 "pcm_f32le", "pcm_u8", "pcm_mulaw", "pcm_alaw", "pcm_bluray" -> "PCM";
            default -> key.toUpperCase(Locale.ROOT);
        };
    }

    /** 纯音频的「原样复制」提示：容器装不下时给用户一句人话。 */
    static String audioFallbackNote(String container, String sourceAudio) {
        return "源音频 " + shortName(sourceAudio) + " 装不进 " + container.toUpperCase(Locale.ROOT);
    }

    /** 封装时要不要给 MP4/MOV 加 faststart（播放器边下边播）。 */
    static boolean wantsFastStart(String container) {
        String key = container == null ? "" : container.toLowerCase(Locale.ROOT);
        return "mp4".equals(key) || "m4v".equals(key) || "mov".equals(key) || "3gp".equals(key);
    }

    /** 封装时的额外参数：时间戳规整，避免 TS→MP4 出现负时间戳导致卡顿。 */
    static List<String> copyArgs() {
        return List.of("-avoid_negative_ts", "make_zero", "-map_metadata", "0");
    }
}
