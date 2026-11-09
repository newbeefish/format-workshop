package cn.gkkstudio;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 运行环境探测：定位 FFmpeg / FFprobe / SQLite 以及 KGM2 内核所需的解密工具，
 * 并检测本机酷狗数据库 KGMusicV3.db。
 */
final class SystemCapabilities {
    private SystemCapabilities() {
    }

    static Toolchain detect() {
        Path appDir = applicationDirectory();
        Path root = applicationRoot();
        List<Path> dirs = new ArrayList<>();
        dirs.add(root.resolve("tools"));
        dirs.add(appDir.resolve("tools"));
        dirs.add(root);
        dirs.add(appDir);
        Path cwd = Path.of("").toAbsolutePath();
        dirs.add(cwd.resolve("tools"));
        dirs.add(cwd);

        return new Toolchain(
                find(dirs, "ffmpeg.exe", "ffmpeg"),
                find(dirs, "kgg-dec.exe", "kgg-dec"),
                find(dirs, "unlockKuGoWin-64.exe", "unlockKuGoWin-64"),
                find(dirs, "ncmdump.exe", "ncmdump"),
                find(dirs, "kgm.mask", "kgm.mask"),
                find(dirs, "infra.dll", "infra.dll"),
                locateKugouDatabase(dirs),
                detectGpu());
    }

    static Path applicationDirectory() {
        try {
            Path jar = Path.of(SystemCapabilities.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
            Path parent = jar.getParent();
            return parent == null ? jar : parent;
        } catch (Exception exception) {
            return Path.of("").toAbsolutePath();
        }
    }

    /** 应用根目录（jpackage 布局下 jar 位于 app\ 子目录中）。 */
    static Path applicationRoot() {
        Path app = applicationDirectory();
        if (app.getFileName() != null && "app".equalsIgnoreCase(app.getFileName().toString()) && app.getParent() != null) {
            return app.getParent();
        }
        return app;
    }

    private static Path find(List<Path> dirs, String fileName, String command) {
        for (Path dir : dirs) {
            Path candidate = dir.resolve(fileName);
            if (Files.isRegularFile(candidate, new LinkOption[0])) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        try {
            Process process = new ProcessBuilder(command, "-version").redirectErrorStream(true).start();
            if (process.waitFor(2L, TimeUnit.SECONDS) && process.exitValue() == 0) {
                return Path.of(command);
            }
        } catch (Exception ignored) {
            // 未安装则忽略
        }
        return null;
    }

    private static Path locateKugouDatabase(List<Path> dirs) {
        List<Path> candidates = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            candidates.add(Path.of(appData, "Kugou8", "KGMusicV3.db"));
            candidates.add(Path.of(appData, "KuGou8", "KGMusicV3.db"));
            candidates.add(Path.of(appData, "Kugou", "KGMusicV3.db"));
        }
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null) {
            candidates.add(Path.of(userProfile, "AppData", "Roaming", "Kugou8", "KGMusicV3.db"));
        }
        candidates.add(Path.of("C:\\Users\\Administrator\\AppData\\Roaming\\Kugou8\\KGMusicV3.db"));
        for (Path dir : dirs) {
            candidates.add(dir.resolve("KGMusicV3.db"));
        }
        for (Path candidate : candidates) {
            try {
                if (Files.isRegularFile(candidate, new LinkOption[0])) {
                    return candidate.toAbsolutePath().normalize();
                }
            } catch (Exception ignored) {
                // 路径不可用时继续
            }
        }
        return null;
    }

    private static String detectGpu() {
        String nvidia = firstLine(List.of("nvidia-smi", "--query-gpu=name", "--format=csv,noheader"));
        if (nvidia != null) {
            return nvidia;
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            String ps = firstLine(List.of("powershell", "-NoProfile", "-Command",
                    "Get-CimInstance Win32_VideoController | Where-Object {$_.Name -notmatch 'Microsoft Basic'} | Select-Object -First 1 -ExpandProperty Name"));
            if (ps != null) {
                return ps;
            }
        }
        return null;
    }

    private static String firstLine(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String line;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }
            if (process.waitFor(3L, TimeUnit.SECONDS) && process.exitValue() == 0 && line != null && !line.isBlank()) {
                return line.trim();
            }
        } catch (Exception ignored) {
            // 忽略检测失败
        }
        return null;
    }

    record Toolchain(
            Path ffmpeg,
            Path kggDec,
            Path unlock,
            Path ncmdump,
            Path kgmMask,
            Path infra,
            Path kugouDb,
            String gpuName) {

        boolean ready() {
            return this.ffmpeg != null;
        }

        boolean hasGpu() {
            return this.gpuName != null && !this.gpuName.isBlank();
        }

        boolean canDecodeKgg() {
            return this.kggDec != null && this.kugouDb != null;
        }

        boolean canDecodeNcm() {
            return this.ncmdump != null;
        }

        boolean canDecodeKgmByTool() {
            return this.unlock != null && this.kgmMask != null;
        }

        int missingImportantCount() {
            int missing = 0;
            if (this.ffmpeg == null) {
                missing++;
            }
            if (this.kggDec == null) {
                missing++;
            }
            if (this.unlock == null) {
                missing++;
            }
            if (this.ncmdump == null) {
                missing++;
            }
            return missing;
        }
    }
}
