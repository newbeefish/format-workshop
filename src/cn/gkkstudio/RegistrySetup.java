package cn.gkkstudio;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 首次运行时把「用 格式作坊 转换 → 转为图片 / 转为视频 / 转为音频」
 * 写进当前用户的右键菜单（HKCU，不需要管理员）。
 * 用 jpackage 的 jpackage.app-path 取得启动器路径，移动目录后下次启动会自动更新。
 */
final class RegistrySetup {
    private static final String MENU_LABEL = "用 格式作坊 转换";
    private static final String VERB = "FormatWorkshop";
    private static boolean done;

    private RegistrySetup() {
    }

    static void ensureContextMenu() {
        if (done) {
            return;
        }
        done = true;
        try {
            String exe = System.getProperty("jpackage.app-path");
            if (exe == null || !Files.isRegularFile(Path.of(exe))) {
                return;
            }
            // 清掉旧版本（KGGStudio）留下的菜单项
            reg("delete", "HKCU\\Software\\Classes\\*\\shell\\KGGStudio", "/f");
            reg("delete", "HKCU\\Software\\Classes\\Directory\\shell\\KGGStudio", "/f");
            reg("delete", "HKCU\\Software\\Classes\\Directory\\Background\\shell\\KGGStudio", "/f");

            // 文件、文件夹、文件夹空白处三种位置
            writeVerb("Software\\Classes\\*\\shell\\" + VERB, exe, "%1");
            writeVerb("Software\\Classes\\Directory\\shell\\" + VERB, exe, "%1");
            writeVerb("Software\\Classes\\Directory\\Background\\shell\\" + VERB, exe, "%V");
        } catch (Exception ignored) {
            // 注册表写入失败不影响主功能
        }
    }

    private static void writeVerb(String key, String exe, String placeholder) {
        String full = "HKCU\\" + key;
        // 先删掉旧的父项（含历史上的级联结构），改成三个并列的普通命令项，
        // 每一项都有独立 command，不依赖级联菜单，绝不会再出现“找不到应用”。
        reg("delete", full, "/f");

        writeItem(full, "01", "用 格式作坊转为图片", exe, "--image", placeholder);
        writeItem(full, "02", "用 格式作坊转为视频", exe, "--video", placeholder);
        writeItem(full, "03", "用 格式作坊转为音频", exe, "--audio", placeholder);
    }

    private static void writeItem(String parent, String id, String label, String exe, String flag, String placeholder) {
        String key = parent + "." + id;
        reg("delete", key, "/f");
        reg("add", key, "/v", "MUIVerb", "/d", label, "/f");
        reg("add", key, "/v", "Icon", "/d", exe + ",0", "/f");
        reg("add", key, "/v", "MultiSelectModel", "/d", "Player", "/f");
        writeCommand(key + "\\command", "\"" + exe + "\" " + flag + " \"" + placeholder + "\"");
    }

    /** reg.exe 直接接收带引号的 /d 会报语法错误，这里按 cmd 的转义方式传入。 */
    private static void writeCommand(String key, String command) {
        try {
            String escaped = command.replace("\"", "\\\"");
            String line = "reg add \"" + key + "\" /ve /d \"" + escaped + "\" /f";
            Process process = new ProcessBuilder("cmd.exe", "/c", line).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            process.waitFor();
        } catch (Exception ignored) {
            // 忽略
        }
    }

    private static void reg(String... arguments) {
        try {
            List<String> command = new java.util.ArrayList<>();
            command.add(new File(System.getenv("SystemRoot"), "System32\\reg.exe").getAbsolutePath());
            for (String argument : arguments) {
                command.add(argument);
            }
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            process.waitFor();
        } catch (Exception ignored) {
            // 忽略单条失败
        }
    }
}
