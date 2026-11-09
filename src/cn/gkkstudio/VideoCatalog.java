package cn.gkkstudio;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频：容器（格式）与编码器分开选，并且每一项都带说明（优点 / 缺点 / 特性）。
 * 容器决定“装进什么文件”，编码器决定“画面怎么压”。
 */
final class VideoCatalog {

    /** 视频编码器。 */
    record Codec(String id, String label, String tag, String pros, String cons, String features) {
        @Override
        public String toString() {
            return this.label;
        }
    }

    /** 视频容器（格式）。 */
    record Container(String extension, String label, String pros, String cons, List<Codec> codecs) {
        @Override
        public String toString() {
            return this.label;
        }
    }

    static final Codec H264 = new Codec("libx264", "H.264 · 兼容最广 · 硬解普遍",
            "H.264 / AVC",
            "几乎所有设备都能播，硬件解码普及，编码速度较快",
            "同画质体积不如 H.265/AV1，不支持 HDR 的 10bit",
            "特性：可放进 MP4/MKV/MOV/TS/FLV，最保险的选择");

    static final Codec H265 = new Codec("libx265", "H.265 / HEVC · 体积比 H.264 小 30–50%",
            "H.265 / HEVC",
            "同画质码率明显更低，适合存档和大片压缩",
            "编码慢很多，老设备/老播放器可能不支持",
            "特性：MP4 里需要 hvc1 标签，Windows 10 起系统才自带给解");

    static final Codec VP9 = new Codec("libvpx-vp9", "VP9 · 免费开源 · 网页友好",
            "VP9",
            "网页/Chrome 生态支持好，同画质比 H.264 省码率",
            "编码很慢，苹果设备支持一般",
            "特性：WEBM 的标准编码，可配 Opus 音频");

    static final Codec AV1 = new Codec("libaom-av1", "AV1 · 压缩率最高 · 速度最慢",
            "AV1",
            "新一代编码，同画质体积最小，免专利",
            "软件编码极慢（比 x265 还慢数倍），很多播放器/设备还不支持",
            "特性：适合长期归档，不建议日常大批量转");

    static final Codec MPEG4 = new Codec("mpeg4", "MPEG-4 Part 2 · 老设备兼容",
            "MPEG-4",
            "老旧播放器、MP4 播放机、部分监控设备认这个",
            "压缩效率低，同体积画质明显差",
            "特性：AVI/DIVX/XVID 时代的通用编码");

    static final Codec MPEG2 = new Codec("mpeg2video", "MPEG-2 · DVD / 广电标准",
            "MPEG-2",
            "DVD、蓝光时代的标准，老播放机和广电设备必认",
            "码率很高、体积大，画质效率低",
            "特性：MPG/VOB/TS/MXF 常用，配 MP2 音频");

    static final Codec PRORES = new Codec("prores_ks", "ProRes · 剪辑中间码 · 体积巨大",
            "ProRes",
            "剪辑软件（FCP/Premiere）原生支持，解码轻松，几乎无损",
            "文件极大（每分钟数 GB），不适合分发",
            "特性：仅 MOV/MXF 支持，通常用 ProRes 422");

    static final Codec WMV2 = new Codec("wmv2", "WMV2 · Windows 老格式",
            "WMV",
            "老 Windows 机器和 WMV 播放器支持",
            "几乎被淘汰，非 Windows 平台支持差",
            "特性：只能装在 WMV/ASF 容器里");

    static final Codec THEORA = new Codec("libtheora", "Theora · 开源老编码",
            "Theora",
            "完全免费，老开源生态（OGG）支持",
            "压缩效率低，现在基本不用",
            "特性：只装 OGV 容器，配 Vorbis 音频");

    static final Codec DVVIDEO = new Codec("dvvideo", "DV · 磁带机标准",
            "DV",
            "DV 摄像机、老剪辑设备直接可用",
            "固定 25Mbps、分辨率固定，文件很大",
            "特性：只装 DV 容器，配 PCM 音频");

    static final Codec GIF = new Codec("gif", "GIF · 动图 · 无声音",
            "GIF",
            "到处都是、能直接发聊天工具，自动循环播放",
            "只有 256 色、没有声音、体积大",
            "特性：只导出 12 帧/秒，适合短表情包");

    static final List<Codec> ALL_CODECS = List.of(H264, H265, VP9, AV1, MPEG4, MPEG2, PRORES, WMV2, THEORA, DVVIDEO, GIF);

    private static final String AAC = "-c:a aac -b:a 160k";
    private static final String OPUS = "-c:a libopus -b:a 128k";
    private static final String MP3 = "-c:a libmp3lame -b:a 192k";
    private static final String VORBIS = "-c:a libvorbis -b:a 160k";
    private static final String WMA = "-c:a wmav2 -b:a 192k";
    private static final String MP2 = "-c:a mp2 -b:a 192k";
    private static final String PCM = "-c:a pcm_s16le";

    static final List<Container> CONTAINERS = List.of(
            new Container("mp4", "MP4 · 兼容最好 · 手机/网页通吃",
                    "通用性最强，iPhone/安卓/浏览器/剪辑软件都认", "多音轨和字幕支持弱，部分编码要额外标签",
                    List.of(H264, H265, AV1, MPEG4)),
            new Container("mkv", "MKV · 万能封装 · 什么编码都能装",
                    "能装任意编码、多音轨、多字幕，体积控制好", "部分播放器/电视不认，Windows 自带播放器支持一般",
                    List.of(H264, H265, VP9, AV1, MPEG4, MPEG2, THEORA)),
            new Container("mov", "MOV · 苹果生态 / 剪辑常用",
                    "Mac 和剪辑软件原生支持，能装 ProRes", "体积偏大，非苹果设备兼容一般",
                    List.of(H264, H265, PRORES, MPEG4)),
            new Container("webm", "WEBM · 网页专用 · 体积小",
                    "网页和 Chrome 生态支持好，体积小", "只支持 VP8/VP9/AV1，苹果设备支持差",
                    List.of(VP9, AV1)),
            new Container("avi", "AVI · 老播放器 / 老设备兼容",
                    "老播放器、车载、监控设备认这个", "不支持现代编码，多音轨支持差，体积大",
                    List.of(MPEG4, MPEG2, WMV2)),
            new Container("flv", "FLV · 老网页/直播平台",
                    "老 Flash 平台和部分直播服务端仍在用", "只支持 H.264 等少数编码，已被淘汰",
                    List.of(H264)),
            new Container("wmv", "WMV · Windows 老格式",
                    "老 Windows 环境和部分播放器认", "兼容性差，几乎被淘汰",
                    List.of(WMV2)),
            new Container("ts", "TS · 直播流 / 电视录制",
                    "适合录制流、IPTV，容错性好", "体积大，普通播放器拖进度条可能不顺",
                    List.of(H264, H265, MPEG2)),
            new Container("mpg", "MPG · DVD 时代格式",
                    "老 DVD 播放机、老设备必认", "压缩效率低，体积很大",
                    List.of(MPEG2)),
            new Container("3gp", "3GP · 老手机视频",
                    "老功能机/早期智能机通用", "分辨率低、功能受限",
                    List.of(H264, MPEG4)),
            new Container("ogv", "OGV · 开源网页视频",
                    "完全开源，老 Firefox 原生支持", "编码效率低，现在极少使用",
                    List.of(THEORA)),
            new Container("mxf", "MXF · 广电 / 专业设备",
                    "广电、专业摄像机与剪辑流程标准", "文件巨大，日常播放器支持一般",
                    List.of(MPEG2, PRORES)),
            new Container("nut", "NUT · 万能测试容器",
                    "几乎能装任何编码，适合实验", "播放器支持少",
                    List.of(H264, H265, VP9, AV1, MPEG4, MPEG2, THEORA)),
            new Container("m2ts", "M2TS · 蓝光 / AVCHD",
                    "蓝光、摄像机原始格式", "体积大，需要支持 Blu-ray 的播放器",
                    List.of(H264, H265, MPEG2)),
            new Container("dv", "DV · 磁带摄像机",
                    "老 DV 摄像机、采集卡通用", "分辨率固定、码率固定，文件极大",
                    List.of(DVVIDEO)),
            new Container("gif", "GIF · 动图（表情包）",
                    "随处可播、自动循环", "256 色、无声音、体积大",
                    List.of(GIF)));

    private VideoCatalog() {
    }

    static List<Targets.Target> containers() {
        List<Targets.Target> list = new ArrayList<>();
        for (Container container : CONTAINERS) {
            list.add(new Targets.Target(container.extension(), container.label()));
        }
        return list;
    }

    static Container container(String extension) {
        for (Container container : CONTAINERS) {
            if (container.extension().equals(extension)) {
                return container;
            }
        }
        return CONTAINERS.get(0);
    }

    static Codec codec(String id) {
        for (Codec codec : ALL_CODECS) {
            if (codec.id().equals(id)) {
                return codec;
            }
        }
        return H264;
    }

    /** 音轨编码跟着容器走。 */
    private static String audioArgs(Container container) {
        return switch (container.extension()) {
            case "webm" -> OPUS;
            case "avi" -> MP3;
            case "wmv" -> WMA;
            case "mpg" -> MP2;
            case "ogv" -> VORBIS;
            case "mxf", "dv" -> PCM + " -ar 48000 -ac 2";
            case "gif" -> "-an";
            default -> AAC;
        };
    }

    static List<String> args(Container container, Codec codec, VideoQuality quality) {
        List<String> args = new ArrayList<>();
        String extension = container.extension();
        if ("gif".equals(extension)) {
            args.addAll(List.of("-c:v", "gif", "-loop", "0", "-an"));
            return args;
        }
        switch (codec.id()) {
            case "libx264" -> args.addAll(List.of("-c:v", "libx264", "-crf", String.valueOf(quality.crf),
                    "-preset", "medium", "-pix_fmt", "yuv420p"));
            case "libx265" -> {
                args.addAll(List.of("-c:v", "libx265", "-crf", String.valueOf(Math.max(16, quality.crf + 5)),
                        "-preset", "medium", "-pix_fmt", "yuv420p"));
                if ("mp4".equals(extension) || "mov".equals(extension)) {
                    args.addAll(List.of("-tag:v", "hvc1"));
                }
            }
            case "libvpx-vp9" -> args.addAll(List.of("-c:v", "libvpx-vp9", "-crf",
                    String.valueOf(quality.vp9Crf), "-b:v", "0", "-row-mt", "1"));
            case "libaom-av1" -> args.addAll(List.of("-c:v", "libaom-av1", "-crf",
                    String.valueOf(quality.vp9Crf), "-b:v", "0", "-cpu-used", "6", "-row-mt", "1"));
            case "mpeg4" -> args.addAll(List.of("-c:v", "mpeg4", "-q:v",
                    String.valueOf(Math.max(2, quality.crf / 5))));
            case "mpeg2video" -> {
                args.addAll(List.of("-c:v", "mpeg2video", "-q:v", String.valueOf(Math.max(2, quality.crf / 5))));
                if ("mxf".equals(extension)) {
                    args.addAll(List.of("-pix_fmt", "yuv422p", "-r", "25"));
                }
            }
            case "prores_ks" -> args.addAll(List.of("-c:v", "prores_ks", "-profile:v", "3", "-vendor", "apl0"));
            case "wmv2" -> args.addAll(List.of("-c:v", "wmv2", "-q:v",
                    String.valueOf(Math.max(2, quality.crf / 5))));
            case "libtheora" -> args.addAll(List.of("-c:v", "libtheora", "-q:v", "7"));
            case "dvvideo" -> args.addAll(List.of("-c:v", "dvvideo", "-s", "720x576", "-r", "25", "-pix_fmt", "yuv420p"));
            default -> args.addAll(List.of("-c:v", "libx264", "-crf", String.valueOf(quality.crf),
                    "-preset", "medium", "-pix_fmt", "yuv420p"));
        }
        String audio = audioArgs(container);
        if (!"-an".equals(audio)) {
            args.addAll(List.of(audio.split(" ")));
        } else {
            args.add("-an");
        }
        return args;
    }
}
