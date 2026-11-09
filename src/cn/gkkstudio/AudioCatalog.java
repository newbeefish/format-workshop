package cn.gkkstudio;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 音频格式说明 + 部分容器可选编码。 */
final class AudioCatalog {

    /** 容器里可选的音频编码。 */
    record Codec(String id, String label, List<String> args) {
        @Override
        public String toString() {
            return this.label;
        }
    }

    record Info(String pros, String cons, String features) {
    }

    private static final Map<String, Info> INFO = new HashMap<>();
    private static final Map<String, List<Codec>> CODECS = new HashMap<>();
    /** 下拉框里跟在格式名后面的一句短介绍。 */
    private static final Map<String, String> NOTES = new HashMap<>();

    private static Codec aac(String label, String args) {
        return new Codec("aac", label, List.of(args.split(" ")));
    }

    static {
        INFO.put("mp3", new Info("兼容性最好，几乎所有设备都能放", "同音质体积不如 AAC/Opus",
                "特性：有损压缩，192k 以上人耳基本听不出差别"));
        INFO.put("wav", new Info("无损、未压缩，任何软件都认", "体积巨大（约 10 MB/分钟）",
                "特性：录音、剪辑中间文件的标准"));
        INFO.put("flac", new Info("无损且体积约为 WAV 的一半，支持元数据", "部分老设备不认",
                "特性：无损压缩，适合音乐归档"));
        INFO.put("aac", new Info("同码率音质优于 MP3，苹果生态标准", "老设备兼容性略差",
                "特性：AAC 裸流，常用于流媒体"));
        INFO.put("m4a", new Info("AAC/ALAC 的容器，苹果设备原生支持", "部分车载/老播放器不认",
                "特性：既能装有损 AAC，也能装无损 ALAC"));
        INFO.put("ogg", new Info("开源、免专利，同码率优于 MP3", "部分设备支持一般",
                "特性：常见 Vorbis，也可装 Opus"));
        INFO.put("opus", new Info("低码率下音质最强，适合语音和网络传输", "老播放器支持少",
                "特性：64–96k 就能达到 MP3 128k 的听感"));
        INFO.put("wma", new Info("Windows 生态老格式，体积小", "几乎被淘汰，跨平台支持差",
                "特性：老旧车载和 Windows Media Player 场景"));
        INFO.put("aiff", new Info("苹果的无损未压缩格式", "体积和 WAV 一样大",
                "特性：Mac 录音、Logic 工程常用"));
        INFO.put("ac3", new Info("杜比数字，DVD/影院标配", "码率固定、体积偏大",
                "特性：5.1 声道环绕声常用"));
        INFO.put("eac3", new Info("杜比数字+，比 AC3 效率高", "支持设备少于 AC3",
                "特性：流媒体和蓝光的环绕声"));
        INFO.put("mp2", new Info("MPEG-1 时代音频，DVD/电视广播常用", "效率低于 MP3",
                "特性：MPEG-TS 流里常配这个"));
        INFO.put("amr", new Info("语音专用，文件极小", "只适合人声，音质差",
                "特性：老手机录音、彩信常见"));
        INFO.put("au", new Info("Unix 老音频格式", "生态几乎消失",
                "特性：Sun/NeXT 工作站时代的录音"));
        INFO.put("mka", new Info("Matroska 音频容器，可装任意编码", "部分播放器不认",
                "特性：适合多声道、无损音频存档"));
        INFO.put("tta", new Info("无损压缩，比 FLAC 简单", "生态小，支持软件少",
                "特性：无损音乐归档的备选"));
        INFO.put("spx", new Info("Speex 语音编码，低码率", "只适合人声",
                "特性：VoIP、语音记录"));
        INFO.put("gsm", new Info("电话音质，8kHz", "音质很差，只能听清人声",
                "特性：老语音系统、呼叫中心录音"));
        INFO.put("caf", new Info("苹果 Core Audio 容器", "跨平台支持差",
                "特性：macOS/iOS 音频开发常用"));
        INFO.put("w64", new Info("Sony 的 64 位 WAV，支持超大文件", "支持软件少",
                "特性：超过 4 GB 的录音"));
        INFO.put("voc", new Info("老声卡录音格式", "现在基本见不到",
                "特性：DOS 时代 Sound Blaster 录音"));
        INFO.put("xwma", new Info("Xbox 用的 WMA 变体", "只适合老游戏资源",
                "特性：游戏音频打包"));

        INFO.put("dts", new Info("影院级多声道，码率高", "体积大，普通播放器可能不解", "特性：DTS 环绕声，DVD/蓝光常见"));
        INFO.put("dtshd", new Info("DTS-HD 无损环绕", "文件很大，需要支持设备", "特性：蓝光音轨，可向下兼容 DTS"));
        INFO.put("mp1", new Info("MPEG-1 Layer I，最老的 MP3 前身", "效率低，体积大", "特性：数字广播和老设备"));
        INFO.put("mpa", new Info("MPEG 音频裸流", "没有容器，播放器支持一般", "特性：MP3 裸流，多见于老转码流程"));
        INFO.put("oga", new Info("Ogg 音频容器", "开源、免专利", "特性：可装 Vorbis 或 Opus，扩展名区别只在习惯"));
        INFO.put("aif", new Info("AIFF 苹果无损", "没有压缩，音质原样", "特性：Mac 录音常用，和 WAV 等价的苹果格式"));
        INFO.put("aifc", new Info("AIFF 压缩变体", "兼容老 Mac 软件", "特性：AIFF-C，可带压缩编码"));
        INFO.put("alac", new Info("苹果无损压缩", "无损且支持苹果生态", "特性：iTunes/Apple Music 无损常用，装在 M4A 里"));
        INFO.put("m4r", new Info("iPhone 铃声格式", "iOS 直接识别", "特性：实际是 M4A，长度通常限制 30 秒"));
        INFO.put("m4b", new Info("有声书格式", "支持章节标记和书签", "特性：AAC 编码，播放器能记住进度"));
        INFO.put("wave", new Info("WAV 的另一种扩展名", "兼容性同 WAV", "特性：某些老工具只认 .wave"));
        INFO.put("snd", new Info("AU 的别名", "老 Unix 录音常见", "特性：Sun/NeXT 音频"));
        INFO.put("amr", new Info("语音专用，体积极小", "只适合人声，音质差", "特性：老手机录音、彩信常见"));
        INFO.put("w64", new Info("Sony 64 位 WAV", "支持超过 4GB 的录音", "特性：长时间录音、多轨工程"));

        NOTES.put("mp3", "通用音频");
        NOTES.put("wav", "未压缩");
        NOTES.put("flac", "无损音频");
        NOTES.put("aac", "高效有损");
        NOTES.put("m4a", "苹果通用");
        NOTES.put("ogg", "开源免专利");
        NOTES.put("oga", "开源免专利");
        NOTES.put("opus", "低码率强");
        NOTES.put("wma", "Windows 老格式");
        NOTES.put("aiff", "苹果无损");
        NOTES.put("aif", "苹果无损");
        NOTES.put("aifc", "苹果无损");
        NOTES.put("ac3", "杜比环绕");
        NOTES.put("eac3", "杜比+ 更省");
        NOTES.put("dts", "影院环绕");
        NOTES.put("dtshd", "蓝光无损环绕");
        NOTES.put("mp2", "广播 / 电视");
        NOTES.put("mp1", "老广播");
        NOTES.put("mpa", "MP3 裸流");
        NOTES.put("amr", "语音 / 彩信");
        NOTES.put("au", "老 Unix");
        NOTES.put("snd", "老 Unix");
        NOTES.put("mka", "多声道容器");
        NOTES.put("tta", "无损备选");
        NOTES.put("spx", "语音专用");
        NOTES.put("caf", "苹果容器");
        NOTES.put("m4b", "有声书");
        NOTES.put("m4r", "iPhone 铃声");
        NOTES.put("w64", "超大录音");
        NOTES.put("wave", "未压缩");
        NOTES.put("gsm", "电话音质");
        NOTES.put("voc", "老声卡录音");
        NOTES.put("wv", "无损（WavPack）");
        NOTES.put("xwma", "Xbox 老游戏");
        NOTES.put("alac", "苹果无损");
        CODECS.put("m4a", List.of(
                new Codec("aac", "AAC · 有损 · 体积小", List.of("-c:a", "aac", "-b:a", "192k")),
                new Codec("alac", "ALAC · 无损 · 体积大", List.of("-c:a", "alac"))));
        CODECS.put("mka", List.of(
                new Codec("flac", "FLAC · 无损", List.of("-c:a", "flac")),
                new Codec("aac", "AAC · 有损", List.of("-c:a", "aac", "-b:a", "192k")),
                new Codec("opus", "Opus · 低码率强", List.of("-c:a", "libopus", "-b:a", "160k"))));
        CODECS.put("ogg", List.of(
                new Codec("vorbis", "Vorbis · 兼容最好", List.of("-c:a", "libvorbis", "-q:a", "5")),
                new Codec("opus", "Opus · 同码率更小", List.of("-c:a", "libopus", "-b:a", "160k"))));
        CODECS.put("wav", List.of(
                new Codec("pcm16", "PCM 16bit · 44.1/48k 标准", List.of("-c:a", "pcm_s16le")),
                new Codec("pcm24", "PCM 24bit · 录音棚", List.of("-c:a", "pcm_s24le"))));
        CODECS.put("aiff", List.of(
                new Codec("pcm16be", "PCM 16bit 大端", List.of("-c:a", "pcm_s16be")),
                new Codec("pcm24be", "PCM 24bit 大端", List.of("-c:a", "pcm_s24be"))));
        CODECS.put("caf", List.of(
                new Codec("pcm16", "PCM 16bit", List.of("-c:a", "pcm_s16le")),
                new Codec("pcm24", "PCM 24bit", List.of("-c:a", "pcm_s24le"))));
        CODECS.put("w64", List.of(
                new Codec("pcm16", "PCM 16bit", List.of("-c:a", "pcm_s16le")),
                new Codec("pcm24", "PCM 24bit", List.of("-c:a", "pcm_s24le"))));
    }

    private AudioCatalog() {
    }

    static Info info(String extension) {
        return INFO.getOrDefault(extension, new Info("由 FFmpeg 编码", "冷门格式，部分软件不认", ""));
    }

    /** 格式后面的一句短介绍，没有介绍时返回空串。 */
    static String note(String extension) {
        return NOTES.getOrDefault(extension, "");
    }

    /** 有多个可选编码的容器；没有返回 null 表示沿用默认参数。 */
    static List<Codec> codecs(String extension) {
        return CODECS.get(extension);
    }
}
