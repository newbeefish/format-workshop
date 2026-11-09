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
