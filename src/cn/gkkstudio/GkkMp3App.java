package cn.gkkstudio;

import com.formdev.flatlaf.themes.FlatMacLightLaf;
import java.awt.Color;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** 程序入口：图片 / 视频 / 音频 通用转换器。 */
public final class GkkMp3App {
    private GkkMp3App() {
    }

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        if (args.length > 0 && "--diagnose".equals(args[0])) {
            SystemCapabilities.Toolchain toolchain = SystemCapabilities.detect();
            System.out.println("ffmpeg=" + (toolchain.ffmpeg() == null ? "NOT_FOUND" : toolchain.ffmpeg()));
            System.out.println("kgg-dec=" + (toolchain.kggDec() == null ? "NOT_FOUND" : toolchain.kggDec()));
            System.out.println("unlockKuGoWin=" + (toolchain.unlock() == null ? "NOT_FOUND" : toolchain.unlock()));
            System.out.println("ncmdump=" + (toolchain.ncmdump() == null ? "NOT_FOUND" : toolchain.ncmdump()));
            System.out.println("kugouDb=" + (toolchain.kugouDb() == null ? "NOT_FOUND" : toolchain.kugouDb()));
            System.exit(toolchain.ready() ? 0 : 2);
        }
        boolean cliRequested = Boolean.getBoolean("gkk.cli")
                || Arrays.asList(args).contains("--cli")
                || launchedAsCli();
        if (cliRequested) {
            String[] filtered = Arrays.stream(args).filter(argument -> !"--cli".equals(argument)).toArray(String[]::new);
            System.exit(runCommandLine(filtered));
        }
        List<Path> initialFiles = Arrays.stream(args)
                .filter(argument -> !argument.startsWith("--"))
                .map(Path::of)
                .filter(Files::isRegularFile)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        Mode forcedMode = forcedMode(args);

        // 已经有实例在跑：把文件和目标板块转交给它，不再开第二个窗口
        if (!initialFiles.isEmpty() && SingleInstance.forward(forcedMode, initialFiles)) {
            System.exit(0);
        }

        SwingUtilities.invokeLater(() -> {
            installLookAndFeel();
            MainFrame frame = new MainFrame(initialFiles, forcedMode);
            frame.setVisible(true);
            SingleInstance.listen((mode, files) ->
                    SwingUtilities.invokeLater(() -> frame.handleExternal(mode, files)));
        });
    }

    /** 右键菜单里的 --image / --video / --audio。 */
    private static Mode forcedMode(String[] args) {
        for (String argument : args) {
            switch (argument.toLowerCase(java.util.Locale.ROOT)) {
                case "--image", "--to-image" -> {
                    return Mode.IMAGE;
                }
                case "--video", "--to-video" -> {
                    return Mode.VIDEO;
                }
                case "--audio", "--to-audio" -> {
                    return Mode.AUDIO;
                }
                default -> {
                    // 不是模式参数
                }
            }
        }
        return null;
    }

    /** jpackage 生成的 *-cli.exe 启动器：按可执行文件名判断命令行模式。 */
    private static boolean launchedAsCli() {
        try {
            return ProcessHandle.current().info().command()
                    .map(path -> {
                        String name = Path.of(path).getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return name.endsWith("-cli.exe") || name.endsWith("-cli");
                    })
                    .orElse(false);
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * 命令行：KGG-Studio-cli.exe &lt;输入&gt; [输出文件夹] [格式]
     * 格式不填时：图片 PNG、视频 MP4、音频 MP3。
     */
    private static int runCommandLine(String[] args) {
        if (args.length < 1) {
            System.err.println("用法: KGG-Studio-cli.exe <输入文件> [输出文件夹] [格式]");
            System.err.println("图片: PNG / JPG / WEBP / BMP / TIFF");
            System.err.println("视频: MP4 / MKV / MOV / WEBM / AVI / GIF");
            System.err.println("音频: MP3 / WAV / FLAC / M4A / OGG / OPUS / WMA");
            return 64;
        }
        Path input;
        Path output;
        try {
            input = Path.of(args[0]).toAbsolutePath().normalize();
            output = args.length >= 2 ? Path.of(args[1]).toAbsolutePath().normalize() : input.getParent();
        } catch (InvalidPathException exception) {
            System.err.println("路径无效: " + exception.getInput());
            return 64;
        }
        if (!Files.isRegularFile(input, new LinkOption[0])) {
            System.err.println("输入文件不存在: " + input);
            return 66;
        }
        SystemCapabilities.Toolchain toolchain = SystemCapabilities.detect();
        if (!toolchain.ready()) {
            System.err.println("未找到 FFmpeg，请把 ffmpeg.exe 与 ffprobe.exe 放入 tools 文件夹。");
            return 69;
        }
        String extension = MediaEngine.extension(input);
        Mode mode = Mode.AUDIO;
        for (Mode candidate : Mode.values()) {
            if (candidate.supports(extension)) {
                mode = candidate;
                break;
            }
        }
        String requested = args.length >= 3 ? args[2] : null;
        MediaEngine.Settings settings = buildSettings(mode, output, requested);
        if (settings == null) {
            System.err.println("不支持的格式: " + requested);
            return 64;
        }

        TransferItem item = new TransferItem(input);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> last = new AtomicReference<>("");
        MediaEngine engine = new MediaEngine(toolchain, SystemCapabilities.applicationRoot().resolve("logs"));
        try {
            engine.convertAll(List.of(item), settings, changed -> {
                String text = changed.stateText() + " · " + changed.detail;
                if (!text.equals(last.getAndSet(text))) {
                    System.out.println(text);
                }
            }, latch::countDown);
            if (!latch.await(120L, TimeUnit.MINUTES)) {
                engine.cancel(List.of(item));
                System.err.println("转换超时");
                return 70;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("转换被中断");
            return 130;
        } finally {
            engine.close();
        }
        if (item.state != TransferItem.State.DONE) {
            System.err.println("转换失败: " + item.detail);
            return 1;
        }
        System.out.println("输出文件: " + item.output);
        return 0;
    }

    private static MediaEngine.Settings buildSettings(Mode mode, Path output, String requested) {
        List<String> args;
        String extension;
        String filter = null;
        boolean image = mode == Mode.IMAGE;
        switch (mode) {
            case IMAGE -> {
                ImageFormat format = match(ImageFormat.values(), requested);
                format = format == null ? ImageFormat.PNG : format;
                extension = format.extension;
                args = EncodeArgs.image(format, ImageQuality.HIGH);
            }
            case VIDEO -> {
                VideoFormat format = match(VideoFormat.values(), requested);
                format = format == null ? VideoFormat.MP4 : format;
                extension = format.extension;
                args = EncodeArgs.video(format, VideoQuality.STANDARD);
                filter = format.isGif() ? "fps=12" : null;
            }
            default -> {
                AudioFormat format = match(AudioFormat.values(), requested);
                format = format == null ? AudioFormat.MP3 : format;
                extension = format.extension;
                args = EncodeArgs.audio(format, AudioQuality.HIGH);
            }
        }
        return new MediaEngine.Settings(output, false, 1, mode, extension, new ArrayList<>(args), filter, image, mode == Mode.AUDIO);
    }

    private static <T> T match(T[] values, String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        for (T value : values) {
            Enum<?> constant = (Enum<?>) value;
            if (constant.name().equalsIgnoreCase(requested) || value.toString().equalsIgnoreCase(requested)) {
                return value;
            }
        }
        return null;
    }

    private static void installLookAndFeel() {
        UIManager.put("Component.arc", 12);
        UIManager.put("Button.arc", 14);
        UIManager.put("TextComponent.arc", 12);
        UIManager.put("Component.focusWidth", 2);
        UIManager.put("Component.focusColor", UiKit.PRIMARY);
        UIManager.put("Component.accentColor", UiKit.PRIMARY);
        // FlatMac 主题默认用系统蓝画下拉/微调箭头，这里统一成紫色
        UIManager.put("ComboBox.buttonBackground", new Color(0xF7F7F9));
        UIManager.put("ComboBox.buttonArrowColor", UiKit.PRIMARY);
        UIManager.put("ComboBox.buttonHoverArrowColor", UiKit.PRIMARY_DARK);
        UIManager.put("ComboBox.buttonPressedArrowColor", UiKit.PRIMARY_DARK);
        UIManager.put("Spinner.buttonArrowColor", UiKit.PRIMARY);
        UIManager.put("Spinner.buttonHoverArrowColor", UiKit.PRIMARY_DARK);
        UIManager.put("Spinner.buttonPressedArrowColor", UiKit.PRIMARY_DARK);
        UIManager.put("Button.default.background", UiKit.PRIMARY);
        UIManager.put("Button.default.foreground", Color.WHITE);
        Font font = new Font("Microsoft YaHei UI", Font.PLAIN, 14);
        UIManager.put("defaultFont", font);
        FlatMacLightLaf.setup();
        UIManager.put("defaultFont", font);
        UIManager.put("Button.font", font);
        UIManager.put("Label.font", font);
        UIManager.put("ComboBox.font", font);
        UIManager.put("Table.font", font);
        UIManager.put("TableHeader.font", font.deriveFont(Font.BOLD));
        UIManager.put("TextField.font", font);
        UIManager.put("Spinner.font", font);
        UIManager.put("OptionPane.font", font);
        UIManager.put("OptionPane.messageFont", font);
        UIManager.put("OptionPane.buttonFont", font);
    }
}
