package cn.gkkstudio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 显卡硬编码支持（NVENC / Quick Sync / AMF / D3D12VA）。
 *
 * <p>光看 {@code ffmpeg -encoders} 里有没有名字不够：驱动太旧、机器没有对应显卡时，
 * 编码器照样列出来但一用就报错。所以这里对每个候选做一次真实的 0.4 秒试编码，
 * 通过才认为可用；失败时把 ffmpeg 的原话记下来，界面上可以告诉用户为什么。
 */
final class GpuSupport {

    /** 一个可用的硬件编码方案。 */
    record Gpu(String family, String label, Map<String, String> encoders, String reason) {

        boolean usable() {
            return this.encoders != null && !this.encoders.isEmpty();
        }

        /** h264 / hevc / av1 对应的硬件编码器名；不支持返回 null。 */
        String encoder(String codecName) {
            return this.encoders == null ? null : this.encoders.get(codecName);
        }

        /** 界面上显示的名字，例如「显卡（NVENC）」。 */
        String title() {
            return this.usable() ? "显卡（" + this.label + "）" : "显卡";
        }

        /** 按钮上的短名字：NVENC / QSV / AMF / D3D12。 */
        String shortLabel() {
            return switch (this.family) {
                case "nvenc" -> "NVENC";
                case "qsv" -> "QSV";
                case "amf" -> "AMF";
                case "d3d12va" -> "D3D12";
                default -> this.label;
            };
        }

        static Gpu none(String reason) {
            return new Gpu("", "", Map.of(), reason);
        }
    }

    /** 候选方案，按优先级排：NVIDIA → Intel → AMD → Windows 通用 D3D12。 */
    private static final String[][] FAMILIES = {
            {"nvenc", "NVENC", "h264_nvenc", "hevc_nvenc", "av1_nvenc"},
            {"qsv", "Quick Sync", "h264_qsv", "hevc_qsv", "av1_qsv"},
            {"amf", "AMF", "h264_amf", "hevc_amf", "av1_amf"},
            {"d3d12va", "D3D12", "h264_d3d12va", "hevc_d3d12va", "av1_d3d12va"},
    };

    private static volatile Gpu cached;
    private static volatile boolean detecting;

    private GpuSupport() {
    }

    /** 已经探测到的结果；没探测过返回 null。 */
    static Gpu current() {
        return cached;
    }

    static boolean detecting() {
        return detecting;
    }

    /** 探测一次（会起几个短进程，建议放后台线程）。 */
    static synchronized Gpu detect(Path ffmpeg) {
        if (cached != null) {
            return cached;
        }
        if (ffmpeg == null) {
            cached = Gpu.none("没找到 ffmpeg，无法探测显卡");
            return cached;
        }
        detecting = true;
        try {
            String list = capture(ffmpeg, List.of("-hide_banner", "-encoders"), 20);
            if (list.isEmpty()) {
                cached = Gpu.none("读不到 ffmpeg 的编码器列表");
                return cached;
            }
            String firstReason = "";
            for (String[] family : FAMILIES) {
                if (!list.contains(family[2])) {
                    continue;
                }
                // 先用 H.264 试一帧，通了再试 HEVC / AV1
                String error = testEncode(ffmpeg, family[2]);
                if (error != null) {
                    if (firstReason.isEmpty()) {
                        firstReason = family[1] + "：" + error;
                    }
                    continue;
                }
                Map<String, String> encoders = new LinkedHashMap<>();
                encoders.put("h264", family[2]);
                if (list.contains(family[3]) && testEncode(ffmpeg, family[3]) == null) {
                    encoders.put("hevc", family[3]);
                }
                if (list.contains(family[4]) && testEncode(ffmpeg, family[4]) == null) {
                    encoders.put("av1", family[4]);
                }
                cached = new Gpu(family[0], family[1], encoders, "");
                return cached;
            }
            cached = Gpu.none(firstReason.isEmpty()
                    ? "这台机器没有可用的硬件编码器（没装独立显卡或驱动太旧）"
                    : firstReason);
            return cached;
        } finally {
            detecting = false;
        }
    }

    // ------------------------------------------------------------------ 参数

    /**
     * 硬件编码参数（含音轨），跟 {@link VideoCatalog#args} 一一对应，只是编码器换成了显卡的。
     *
     * @param container 目标容器（决定音轨编码与 hvc1 标签）
     * @param codecName h264 / hevc / av1
     */
    static List<String> videoArgs(Gpu gpu, String codecName, String container, VideoQuality quality) {
        List<String> args = new ArrayList<>();
        String encoder = gpu.encoder(codecName);
        if (encoder == null) {
            return args;
        }
        String family = gpu.family();
        args.addAll(List.of("-c:v", encoder));
        switch (family) {
            case "nvenc" -> {
                args.addAll(List.of("-preset", "p4", "-rc", "vbr", "-cq", String.valueOf(cq(quality)), "-b:v", "0"));
                if ("hevc".equals(codecName) || "av1".equals(codecName)) {
                    args.addAll(List.of("-profile:v", "main"));
                }
            }
            case "qsv" -> args.addAll(List.of("-preset", "medium", "-global_quality", String.valueOf(cq(quality) + 1)));
            case "amf" -> {
                String qp = String.valueOf(cq(quality));
                args.addAll(List.of("-rc", "cqp", "-qp_i", qp, "-qp_p", qp, "-quality", "balanced"));
            }
            default -> args.addAll(List.of("-qp", String.valueOf(cq(quality))));
        }
        args.addAll(List.of("-pix_fmt", "yuv420p"));
        if ("hevc".equals(codecName) && ("mp4".equals(container) || "mov".equals(container))) {
            args.addAll(List.of("-tag:v", "hvc1"));
        }
        VideoCatalog.Container target = VideoCatalog.container(container);
        List<String> audio = VideoCatalog.audioArgsList(target);
        if (!audio.isEmpty() && !"-an".equals(audio.get(0))) {
            args.addAll(audio);
        } else {
            args.add("-an");
        }
        return args;
    }

    /** 硬件编码没有 CRF，用 CQ/QP 近似：值越大越省空间。 */
    private static int cq(VideoQuality quality) {
        return switch (quality) {
            case HIGH -> 20;
            case STANDARD -> 24;
            case SMALL -> 29;
        };
    }

    /** 支持哪些「视频编码」。 */
    static boolean supports(Gpu gpu, String codecName) {
        return gpu != null && gpu.encoder(codecName) != null;
    }

    // ------------------------------------------------------------------ 内部

    /** 试编码一帧；成功返回 null，失败返回 ffmpeg 的错误首行。 */
    private static String testEncode(Path ffmpeg, String encoder) {
        List<String> arguments = List.of("-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=black:s=256x144:r=25:d=0.4",
                "-c:v", encoder, "-f", "null", "-");
        String[] result = run(ffmpeg, arguments, 30);
        return "0".equals(result[0]) ? null : firstLine(result[1]);
    }

    /** 只要输出，不看退出码。 */
    private static String capture(Path ffmpeg, List<String> arguments, int seconds) {
        return run(ffmpeg, arguments, seconds)[1];
    }

    /** @return {退出码, 输出（stderr 合并）} */
    private static String[] run(Path ffmpeg, List<String> arguments, int seconds) {
        try {
            List<String> command = new ArrayList<>();
            command.add(ffmpeg.toString());
            command.addAll(arguments);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String text;
            try (var stream = process.getInputStream()) {
                text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new String[]{"timeout", "探测超时"};
            }
            return new String[]{String.valueOf(process.exitValue()), text.trim()};
        } catch (Exception exception) {
            return new String[]{"-1", exception.getMessage() == null ? "探测失败" : exception.getMessage()};
        }
    }

    private static String firstLine(String text) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("frame=") || trimmed.startsWith("size=")) {
                continue;
            }
            // 去掉 "[h264_nvenc @ 0x...]" 这种前缀
            int bracket = trimmed.lastIndexOf(']');
            if (trimmed.startsWith("[") && bracket > 0) {
                trimmed = trimmed.substring(bracket + 1).trim();
            }
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("error")) {
                continue;
            }
            return trimmed;
        }
        return "未知错误";
    }
}
