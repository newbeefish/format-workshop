package cn.gkkstudio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 通用转换内核（FFmpeg）。
 *
 * <p>图片 / 视频走标准 FFmpeg 转码；音频额外继承 KGM2 的解密工具链：
 * kgg-dec（KGG v5）、unlockKuGoWin（KGM/KGMA/VPR 兜底）、ncmdump（NCM），
 * 以及内置的 KGM 掩码解码器。
 */
final class MediaEngine implements AutoCloseable {
    private static final Set<String> CONTAINER_EXTENSIONS = Set.of("kgg", "gkk", "kgm", "kgma", "kge", "kgtemp", "vpr", "ncm");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "wav", "aac", "m4a", "flac", "ogg", "wma", "opus", "ape", "aif", "aiff", "wv");
    private static final long NATIVE_KGM_LIMIT = 64L * 1024L * 1024L;
    private static final long TOOL_KGM_LIMIT = 78L * 1024L * 1024L;
    private static final byte[] KGM_MAGIC = new byte[]{124, -43, 50, -21, -122, 2, 127, 75, -88, -81, -90, -114, 15, -1, -103, 20};
    private static final byte[] VPR_MAGIC = new byte[]{5, 40, -68, -106, -23, -28, 90, 67, -111, -86, -67, -48, 122, -11, 54, 49};

    record Settings(
            Path outputDirectory,
            boolean sourceDirectory,
            int concurrency,
            Mode mode,
            String targetExtension,
            List<String> outputArgs,
            String videoFilter,
            boolean imageJob,
            boolean allowDirectCopy,
            /** 用户选的编码 id；"copy" 表示「原样复制，不重编码」。 */
            String codecPreference,
            /** 视频原样封装、音轨必须重编码时用的音频参数。 */
            List<String> videoCopyAudioArgs) {
    }

    interface Listener {
        void itemChanged(TransferItem item);
    }

    private final SystemCapabilities.Toolchain tools;

    Path ffmpegPath() {
        return this.tools.ffmpeg();
    }

    /** 用 ffmpeg -i 探测文件里有没有视频/音频流（用于接纳任意 FFmpeg 能读的格式）。 */
    static boolean hasStream(Path ffmpeg, Path file, boolean needAudio) {
        if (ffmpeg == null) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-i", file.toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(10L, TimeUnit.SECONDS);
            boolean hasVideo = output.contains(": Video:");
            boolean hasAudio = output.contains(": Audio:");
            return needAudio ? hasAudio : (hasVideo || hasAudio);
        } catch (Exception exception) {
            return false;
        }
    }
    private final Path logsDirectory;
    private final List<String> log = new CopyOnWriteArrayList<>();
    private ExecutorService executor;
    private volatile boolean cancelling;
    private Path runWorkDirectory;
    private volatile Path logFile;

    MediaEngine(SystemCapabilities.Toolchain toolchain, Path logsDirectory) {
        this.tools = toolchain;
        this.logsDirectory = logsDirectory;
    }

    List<String> logLines() {
        return List.copyOf(this.log);
    }

    Path logFile() {
        return this.logFile;
    }

    boolean isRunning() {
        return this.executor != null && !this.executor.isShutdown();
    }

    void convertAll(List<TransferItem> items, Settings settings, Listener listener, Runnable onFinished) {
        this.cancelling = false;
        this.log.clear();
        this.runWorkDirectory = null;
        this.executor = Executors.newFixedThreadPool(Math.max(1, settings.concurrency()), runnable -> {
            Thread thread = new Thread(runnable, "kgm-converter-worker");
            thread.setDaemon(true);
            return thread;
        });

        addLine("[信息] 开始转换 · " + settings.mode().title);
        addLine("[信息] 输出格式：" + settings.targetExtension());
        addLine("[信息] 本次任务：" + items.size() + " 个文件");

        CompletableFuture<?>[] futures = items.stream()
                .filter(item -> item.state == TransferItem.State.WAITING
                        || item.state == TransferItem.State.FAILED
                        || item.state == TransferItem.State.CANCELLED)
                .map(item -> CompletableFuture.runAsync(() -> convertOne(item, settings, listener), this.executor))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).whenComplete((ignored, throwable) -> {
            if (this.executor != null) {
                this.executor.shutdown();
            }
            long done = items.stream().filter(item -> item.state == TransferItem.State.DONE).count();
            long failed = items.stream().filter(item -> item.state == TransferItem.State.FAILED).count();
            addLine("[信息] 已生成 " + done + " 个文件");
            if (failed > 0) {
                addLine("[警告] 失败 " + failed + " 个，请查看上方原因。");
            }
            writeLogFile();
            cleanupWorkDirectory();
            if (onFinished != null) {
                onFinished.run();
            }
        });
    }

    void cancel(List<TransferItem> items) {
        this.cancelling = true;
        addLine("[警告] 用户取消，正在停止…");
        for (TransferItem item : items) {
            Process process = item.process;
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (item.state == TransferItem.State.WAITING) {
                item.state = TransferItem.State.CANCELLED;
                item.detail = "已取消";
            }
        }
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
    }

    private void convertOne(TransferItem item, Settings settings, Listener listener) {
        Path temporary = null;
        Path partFile = null;
        try {
            if (this.cancelling) {
                throw new CancellationException();
            }
            item.state = TransferItem.State.ANALYZING;
            item.progress = 2;
            item.detail = "检查文件";
            notifyChanged(item, listener);

            Path outputDirectory = settings.sourceDirectory() ? item.source.getParent() : settings.outputDirectory();
            if (outputDirectory == null) {
                throw new IOException("请选择输出文件夹");
            }
            Files.createDirectories(outputDirectory);

            ResolvedInput resolved = resolveInput(item, listener);
            temporary = resolved.temporary() ? resolved.path() : null;
            if (this.cancelling) {
                throw new CancellationException();
            }

            String baseName = baseName(item.source.getFileName().toString());
            Path target = uniqueOutput(outputDirectory, baseName, settings.targetExtension());
            ProbeInfo probe = probe(resolved.path());
            String sourceExtension = resolved.temporary()
                    ? extensionForCodec(probe.audio(), extension(resolved.path()))
                    : extension(resolved.path());

            item.progress = 16;
            item.detail = resolved.description();
            notifyChanged(item, listener);

            String container = settings.targetExtension().startsWith(".")
                    ? settings.targetExtension().substring(1)
                    : settings.targetExtension();

            // 图片：本来就是目标格式、又没缩放 → 直接复制，别白跑一次编码
            if (settings.imageJob()
                    && (settings.videoFilter() == null || settings.videoFilter().isBlank())
                    && sourceExtension.equalsIgnoreCase(container)) {
                Files.copy(resolved.path(), target, StandardCopyOption.REPLACE_EXISTING);
                item.progress = 100;
                item.state = TransferItem.State.DONE;
                item.detail = "已经是 ." + container + "，直接复制 · " + humanSize(Files.size(target));
                item.output = target;
                addLine("[信息] 直接复制：" + target.getFileName());
                return;
            }

            // 音频：同格式直接复制
            if (settings.allowDirectCopy()
                    && sourceExtension.equalsIgnoreCase(container)
                    && AUDIO_EXTENSIONS.contains(sourceExtension)) {
                Files.copy(resolved.path(), target, StandardCopyOption.REPLACE_EXISTING);
                item.progress = 100;
                item.state = TransferItem.State.DONE;
                item.detail = "无损直出 · " + humanSize(Files.size(target));
                item.output = target;
                addLine("[信息] 已直接输出：" + target.getFileName());
                return;
            }

            // 视频 / 音频：能只换容器就不重编码（-c copy），这是最快的一条路
            boolean copyPreferred = "copy".equals(settings.codecPreference());
            boolean resized = settings.videoFilter() != null && !settings.videoFilter().isBlank();
            FastPath.Decision decision = switch (settings.mode()) {
                case VIDEO -> FastPath.decideVideo(container, probe.video(), probe.audio(), copyPreferred, resized);
                case AUDIO -> FastPath.decideAudio(container, probe.audio(), copyPreferred);
                case IMAGE -> new FastPath.Decision(FastPath.Kind.ENCODE, "");
            };
            List<String> codecArgs = decision.copying()
                    ? copyArguments(settings, container, decision.kind() == FastPath.Kind.COPY_ALL)
                    : settings.outputArgs();
            if (!decision.note().isEmpty()) {
                addLine("[信息] " + item.fileName() + "：" + decision.note());
            }

            item.state = TransferItem.State.CONVERTING;
            item.progress = settings.imageJob() ? 45 : 18;
            item.detail = decision.copying() ? "直接封装…" : (settings.imageJob() ? "编码中…" : "准备编码");
            notifyChanged(item, listener);

            partFile = target.resolveSibling(baseName + ".part" + settings.targetExtension());
            Files.deleteIfExists(partFile);

            List<String> command = new ArrayList<>();
            command.add(this.tools.ffmpeg().toString());
            if (decision.copying()) {
                // TS / 分片流的时间戳常常是乱的，封装前先让 ffmpeg 重新生成
                command.addAll(List.of("-y", "-hide_banner", "-loglevel", "error", "-nostdin",
                        "-fflags", "+genpts", "-i", effectiveInput(resolved.path()).toString()));
            } else {
                command.addAll(List.of("-y", "-hide_banner", "-loglevel", "error", "-nostdin",
                        "-i", effectiveInput(resolved.path()).toString()));
            }
            if (settings.imageJob()) {
                command.addAll(List.of("-frames:v", "1"));
            } else if (settings.mode() == Mode.VIDEO) {
                command.addAll(List.of("-map", "0:v:0", "-map", "0:a?"));
            } else {
                command.addAll(List.of("-map", "0:a:0", "-map_metadata", "0"));
            }
            if (settings.videoFilter() != null && !settings.videoFilter().isBlank()) {
                command.addAll(List.of("-vf", settings.videoFilter()));
            }
            command.addAll(codecArgs);
            if (settings.mode() == Mode.AUDIO && !decision.copying() && container.equalsIgnoreCase("mp3")) {
                command.addAll(List.of("-id3v2_version", "3", "-write_id3v1", "1"));
            }
            command.addAll(List.of("-progress", "pipe:1", "-nostats", partFile.toString()));
            addLine("[信息] 正在" + (decision.copying() ? "封装" : "转换") + "：" + item.fileName()
                    + " -> " + settings.targetExtension());

            Process process = new ProcessBuilder(command).start();
            item.process = process;
            StringBuilder errors = new StringBuilder();
            Thread errorThread = new Thread(() -> readStream(process.getErrorStream(), errors), "ffmpeg-errors");
            errorThread.setDaemon(true);
            errorThread.start();

            double duration = settings.imageJob() ? -1.0 : probe.duration();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int lastProgress = -1;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("out_time_us=") || duration <= 0.0) {
                        continue;
                    }
                    long micros = parseLong(line.substring("out_time_us=".length()).trim());
                    int percent = (int) Math.max(18, Math.min(99, 18 + (micros / (duration * 1_000_000.0)) * 81.0));
                    if (percent > lastProgress) {
                        lastProgress = percent;
                        item.progress = percent;
                        item.detail = percent + "%";
                        notifyChanged(item, listener);
                    }
                }
            }
            int exitCode = process.waitFor();
            errorThread.join(1500L);
            item.process = null;

            if (this.cancelling) {
                throw new CancellationException();
            }
            if (exitCode != 0 || !Files.isRegularFile(partFile) || Files.size(partFile) == 0L) {
                String message = simplifyError(errors.toString());
                throw new IOException(message.isBlank() ? "FFmpeg 无法读取该文件" : message);
            }
            moveOrCopy(partFile, target);
            item.output = target;
            item.state = TransferItem.State.DONE;
            item.progress = 100;
            item.detail = (decision.copying() ? "已封装 · " : "已保存 · ") + humanSize(Files.size(target));
            addLine("[信息] " + (decision.copying() ? "已封装：" : "已保存：") + target);
        } catch (InterruptedException | CancellationException exception) {
            Thread.currentThread().interrupt();
            item.state = TransferItem.State.CANCELLED;
            item.progress = 0;
            item.detail = "已取消";
            addLine("[警告] 已取消：" + item.fileName());
        } catch (Exception exception) {
            item.state = TransferItem.State.FAILED;
            item.progress = 0;
            item.detail = friendlyMessage(item.source, exception);
            addLine("[错误] " + item.fileName() + "：" + item.detail);
        } finally {
            item.process = null;
            deleteQuietly(partFile);
            deleteQuietly(temporary);
            notifyChanged(item, listener);
        }
    }

    /** 组装「原样封装」的参数：视频/音频都 copy，或者只 copy 视频、音频重编码。 */
    private static List<String> copyArguments(Settings settings, String container, boolean copyAudio) {
        List<String> args = new ArrayList<>();
        if (settings.mode() == Mode.VIDEO) {
            args.addAll(List.of("-c:v", "copy"));
            if (copyAudio) {
                args.addAll(List.of("-c:a", "copy"));
            } else if (settings.videoCopyAudioArgs() != null && !settings.videoCopyAudioArgs().isEmpty()) {
                args.addAll(settings.videoCopyAudioArgs());
            } else {
                args.addAll(List.of("-an"));
            }
        } else {
            args.addAll(List.of("-c:a", "copy"));
        }
        args.addAll(FastPath.copyArgs());
        if (FastPath.wantsFastStart(container)) {
            args.addAll(List.of("-movflags", "+faststart"));
        }
        return args;
    }

    private record ResolvedInput(Path path, boolean temporary, String description) {
    }

    /** 音频容器在此解密；图片/视频直接用源文件。 */
    private ResolvedInput resolveInput(TransferItem item, Listener listener) throws IOException {
        Path source = item.source;
        String extension = extension(source);

        if ("ncm".equals(extension)) {
            return decryptNcm(item, listener);
        }

        if (QmcDecoder.isQmc(extension)) {
            item.state = TransferItem.State.DECRYPTING;
            item.progress = 6;
            item.detail = "QMC · QQ 音乐";
            notifyChanged(item, listener);
            addLine("[信息] QMC 解密：" + item.fileName());
            return new ResolvedInput(QmcDecoder.decodeToTemporaryFile(source), true, "已解密 QQ 音乐音频");
        }

        boolean kgmContainer;
        boolean v5Container;
        try {
            kgmContainer = KgmDecoder.supports(source);
            v5Container = !kgmContainer && isKggV5(source);
        } catch (Exception exception) {
            kgmContainer = false;
            v5Container = false;
        }

        if (kgmContainer) {
            long size = Files.size(source);
            if (size > NATIVE_KGM_LIMIT) {
                if (size <= TOOL_KGM_LIMIT && this.tools.canDecodeKgmByTool()) {
                    item.state = TransferItem.State.DECRYPTING;
                    item.progress = 8;
                    item.detail = "unlockKuGoWin · 大文件";
                    notifyChanged(item, listener);
                    addLine("[信息] 文件较大（" + humanSize(size) + "），改用 unlockKuGoWin 解密：" + item.fileName());
                    return new ResolvedInput(runUnlockTool(source), true, "已解密 KGM/VPR 音频");
                }
                throw new IOException("文件 " + humanSize(size) + " 超过解密上限（约 78 MB），"
                        + "KGM 掩码只覆盖 64 MB 音频数据，两个原工具也无法完整还原；"
                        + "请改用体积更小的版本，否则只能得到被截断的坏文件");
            }
            item.state = TransferItem.State.DECRYPTING;
            item.progress = 6;
            item.detail = "内置解码器 · KGM/VPR";
            notifyChanged(item, listener);
            addLine("[信息] 内置解码：" + item.fileName());
            return new ResolvedInput(KgmDecoder.decodeToTemporaryFile(source), true, "已解密 KGM/VPR 音频");
        }

        if (v5Container) {
            if (!this.tools.canDecodeKgg()) {
                throw new IOException("新版 KGG v5 需要 tools 里的 kgg-dec.exe，以及本机酷狗数据库 KGMusicV3.db");
            }
            item.state = TransferItem.State.DECRYPTING;
            item.progress = 6;
            item.detail = "kgg-dec · KGG v5";
            notifyChanged(item, listener);
            return new ResolvedInput(runKggDec(source), true, "已解密新版 KGG v5 音频");
        }

        if (CONTAINER_EXTENSIONS.contains(extension)) {
            if (this.tools.canDecodeKgmByTool()) {
                item.state = TransferItem.State.DECRYPTING;
                item.progress = 8;
                item.detail = "unlockKuGoWin";
                notifyChanged(item, listener);
                return new ResolvedInput(runUnlockTool(source), true, "已解密加密音频");
            }
            throw new IOException("无法识别该加密格式，且 tools 目录缺少 unlockKuGoWin-64.exe / kgm.mask");
        }

        return new ResolvedInput(source, false, "读取源文件");
    }

    private ResolvedInput decryptNcm(TransferItem item, Listener listener) throws IOException {
        if (this.tools.ncmdump() == null) {
            throw new IOException("检测到 NCM 文件，但 tools 中缺少 ncmdump.exe");
        }
        item.state = TransferItem.State.DECRYPTING;
        item.progress = 6;
        item.detail = "ncmdump · NCM";
        notifyChanged(item, listener);
        addLine("[信息] ncmdump 解密：" + item.fileName());

        Path ncmOut = workDirectory().resolve("ncm");
        Files.createDirectories(ncmOut);
        List<String> command = new ArrayList<>();
        command.add(this.tools.ncmdump().toString());
        command.add("-o");
        command.add(ncmOut.toString());
        command.add("--");
        command.add(item.source.toString());
        ProcessResult result = runTool(command, null);
        for (String line : result.lines()) {
            addLine(line);
        }
        if (result.exitCode() != 0) {
            addLine("[警告] ncmdump 退出码：" + result.exitCode());
        }
        Path produced = newestAudio(ncmOut, baseName(item.fileName()));
        if (produced == null) {
            throw new IOException("ncmdump 未生成可读音频（可能文件损坏或不受支持）");
        }
        return new ResolvedInput(produced, true, "已解密 NCM 音频");
    }

    private Path runKggDec(Path source) throws IOException {
        Path kggDir = workDirectory().resolve("kgg");
        Files.createDirectories(kggDir);
        Path copied = kggDir.resolve(source.getFileName().toString());
        Files.copy(source, copied, StandardCopyOption.REPLACE_EXISTING);

        List<String> command = new ArrayList<>();
        command.add(this.tools.kggDec().toString());
        command.add("--db");
        command.add(this.tools.kugouDb().toString());
        command.add("--");
        command.add(copied.toString());
        ProcessResult result = runTool(command, kggDir);
        for (String line : result.lines()) {
            addLine(line);
        }
        Path produced = newestAudio(kggDir, baseName(source.getFileName().toString()));
        if (produced == null) {
            String hint = result.exitCode() == 0 ? "kgg-dec 未生成音频文件" : "kgg-dec 退出码 " + result.exitCode();
            throw new IOException(hint + "；若提示 ekey not found，请在本机酷狗重新下载该歌曲后再试");
        }
        return produced;
    }

    private Path runUnlockTool(Path source) throws IOException {
        Path dir = workDirectory().resolve("unlock");
        Files.createDirectories(dir);
        copyIfPresent(this.tools.unlock(), dir.resolve("unlockKuGoWin-64.exe"));
        copyIfPresent(this.tools.kgmMask(), dir.resolve("kgm.mask"));
        copyIfPresent(this.tools.infra(), dir.resolve("infra.dll"));
        Path copied = dir.resolve(source.getFileName().toString());
        Files.copy(source, copied, StandardCopyOption.REPLACE_EXISTING);

        Process process = new ProcessBuilder(dir.resolve("unlockKuGoWin-64.exe").toString())
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write('\n');
            stdin.flush();
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        try {
            process.waitFor(10L, TimeUnit.MINUTES);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("unlockKuGoWin 执行被中断");
        }
        for (String line : lines) {
            addLine(line);
        }
        Path out = dir.resolve("kgm-vpr-out");
        Path produced = Files.isDirectory(out) ? newestAudio(out, baseName(source.getFileName().toString())) : null;
        if (produced == null) {
            throw new IOException("unlockKuGoWin 未能解出音频：文件可能已损坏、不是有效的 KGM/VPR，或超过 78 MB 上限");
        }
        return produced;
    }

    private record ProcessResult(int exitCode, List<String> lines) {
    }

    private ProcessResult runTool(List<String> command, Path workingDirectory) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        Process process = builder.start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("任务被中断");
        }
        return new ProcessResult(exitCode, lines);
    }

    private Path newestAudio(Path directory, String baseName) throws IOException {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(directory, 2)) {
            List<Path> candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> AUDIO_EXTENSIONS.contains(extension(path)))
                    .sorted(Comparator.comparingLong((Path path) -> path.toFile().lastModified()).reversed())
                    .toList();
            for (Path candidate : candidates) {
                if (candidate.getFileName().toString().startsWith(baseName)) {
                    return candidate;
                }
            }
            return candidates.isEmpty() ? null : candidates.get(0);
        }
    }

    private record ProbeInfo(double duration, String video, String audio) {
    }

    private ProbeInfo probe(Path path) {
        if (this.tools.ffmpeg() == null) {
            return new ProbeInfo(-1.0, "", "");
        }
        try {
            // 不再单独携带 ffprobe：直接读 ffmpeg -i 的信息输出。
            Process process = new ProcessBuilder(this.tools.ffmpeg().toString(),
                    "-hide_banner", "-i", path.toString())
                    .redirectErrorStream(true).start();
            StringBuilder info = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && info.length() < 200_000) {
                    info.append(line).append('\n');
                }
            }
            process.waitFor(10L, TimeUnit.SECONDS);
            return parseProbe(info.toString());
        } catch (Exception ignored) {
            // 探测失败时退化为无进度百分比
        }
        return new ProbeInfo(-1.0, "", "");
    }

    private static final java.util.regex.Pattern DURATION_PATTERN =
            java.util.regex.Pattern.compile("Duration:\\s*(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");
    private static final java.util.regex.Pattern AUDIO_STREAM_PATTERN =
            java.util.regex.Pattern.compile("Stream #\\d+:\\d+.*?: Audio: ([A-Za-z0-9_]+)");
    private static final java.util.regex.Pattern VIDEO_STREAM_PATTERN =
            java.util.regex.Pattern.compile("Stream #\\d+:\\d+.*?: Video: ([A-Za-z0-9_]+)");

    private static ProbeInfo parseProbe(String text) {
        double duration = -1.0;
        String video = "";
        String audio = "";
        java.util.regex.Matcher durationMatcher = DURATION_PATTERN.matcher(text);
        if (durationMatcher.find()) {
            try {
                duration = Integer.parseInt(durationMatcher.group(1)) * 3600
                        + Integer.parseInt(durationMatcher.group(2)) * 60
                        + Double.parseDouble(durationMatcher.group(3));
            } catch (NumberFormatException ignored) {
                duration = -1.0;
            }
        }
        java.util.regex.Matcher audioMatcher = AUDIO_STREAM_PATTERN.matcher(text);
        if (audioMatcher.find()) {
            audio = audioMatcher.group(1);
        }
        java.util.regex.Matcher videoMatcher = VIDEO_STREAM_PATTERN.matcher(text);
        if (videoMatcher.find()) {
            video = videoMatcher.group(1);
        }
        return new ProbeInfo(duration, video, audio);
    }

    /** 头部判断新版 KGG v5（KGM/VPR 魔数 + 版本号 5）。 */
    private static boolean isKggV5(Path path) {
        try {
            if (Files.size(path) < 72L) {
                return false;
            }
            byte[] header = new byte[24];
            try (java.io.InputStream in = Files.newInputStream(path)) {
                if (in.readNBytes(header, 0, header.length) != header.length) {
                    return false;
                }
            }
            boolean magic = startsWith(header, KGM_MAGIC) || startsWith(header, VPR_MAGIC);
            if (!magic) {
                return false;
            }
            int version = (header[20] & 0xFF) | (header[21] & 0xFF) << 8 | (header[22] & 0xFF) << 16 | (header[23] & 0xFF) << 24;
            return version == 5;
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String extensionForCodec(String codec, String fallback) {
        if (codec == null || codec.isBlank()) {
            return fallback;
        }
        return switch (codec) {
            case "mp3" -> "mp3";
            case "flac" -> "flac";
            case "vorbis" -> "ogg";
            case "opus" -> "opus";
            case "aac", "alac" -> "m4a";
            case "wmav1", "wmav2", "wmapro" -> "wma";
            case "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_u8" -> "wav";
            case "ape" -> "ape";
            default -> fallback;
        };
    }

    private Path workDirectory() throws IOException {
        if (this.runWorkDirectory == null) {
            synchronized (this) {
                if (this.runWorkDirectory == null) {
                    this.runWorkDirectory = Files.createTempDirectory("kgm2mp3-work-");
                }
            }
        }
        return this.runWorkDirectory;
    }

    private void cleanupWorkDirectory() {
        Path directory = this.runWorkDirectory;
        if (directory == null) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录允许残留
                }
            });
        } catch (IOException ignored) {
            // 忽略清理失败
        }
        this.runWorkDirectory = null;
    }

    private void writeLogFile() {
        try {
            Files.createDirectories(this.logsDirectory);
            Path file = this.logsDirectory.resolve("run-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()) + ".log");
            Files.writeString(file, String.join(System.lineSeparator(), this.log), StandardCharsets.UTF_8);
            this.logFile = file;
        } catch (Exception ignored) {
            // 日志写入失败不影响转换结果
        }
    }

    private void addLine(String text) {
        this.log.add(text);
    }

    private void notifyChanged(TransferItem item, Listener listener) {
        if (listener != null) {
            listener.itemChanged(item);
        }
    }

    private static Path uniqueOutput(Path directory, String baseName, String extension) {
        Path candidate = directory.resolve(baseName + extension);
        int index = 2;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(baseName + " (" + index++ + ")" + extension);
        }
        return candidate;
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot <= 0 ? name : name.substring(0, dot);
        if (base.endsWith("_kgg-dec")) {
            base = base.substring(0, base.length() - 8);
        }
        return base;
    }

    static String extension(Path path) {
        return extension(path.getFileName().toString());
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String humanSize(long length) {
        if (length < 0) {
            return "—";
        }
        if (length < 1024L) {
            return length + " B";
        }
        if (length < 0x100000L) {
            return String.format(Locale.ROOT, "%.1f KB", length / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", length / 1024.0 / 1024.0);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static void readStream(InputStream stream, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && sink.length() < 6000) {
                sink.append(line).append('\n');
            }
        } catch (IOException ignored) {
            // 忽略
        }
    }

    /** 跨盘（C: → D:/E:）时 Files.move 会失败，这里退化成复制再删除。 */
    /** HEIC/HEIF 先借 heif-dec 转成 PNG，再交给 FFmpeg。 */
    private Path effectiveInput(Path source) {
        String name = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".heic") && !name.endsWith(".heif") && !name.endsWith(".hif")) {
            return source;
        }
        Path tool = this.tools.ffmpeg() == null ? null
                : this.tools.ffmpeg().getParent().resolve("heif").resolve("heif-dec.exe");
        if (tool == null || !Files.isRegularFile(tool)) {
            return source;
        }
        try {
            Path out = Files.createTempFile("heif-", ".png");
            Files.deleteIfExists(out);
            Process process = new ProcessBuilder(tool.toString(), source.toString(), out.toString())
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            process.waitFor();
            if (Files.isRegularFile(out) && Files.size(out) > 0) {
                return out;
            }
        } catch (Exception ignored) {
            // 失败则原样交给 FFmpeg，让它报错
        }
        return source;
    }
    private static void moveOrCopy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (IOException ignored) {
            // 跨卷或目标被占用，走复制
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.deleteIfExists(source);
        } catch (IOException ignored) {
            // 临时文件删不掉不影响结果
        }
    }
    private static String simplifyError(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("invalid data found") || lower.contains("could not find codec parameters")) {
            return "未找到可解码的音视频流，文件可能受保护或格式尚不支持";
        }
        if (lower.contains("permission denied")) {
            return "没有文件写入权限";
        }
        if (lower.contains("no space left")) {
            return "磁盘空间不足";
        }
        if (lower.contains("unknown encoder")) {
            return "当前 FFmpeg 未包含所需的编码器";
        }
        String[] lines = text.strip().split("\\R");
        return lines.length == 0 ? "" : lines[lines.length - 1].strip();
    }

    private static String friendlyMessage(Path source, Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        String extension = extension(source);
        if (CONTAINER_EXTENSIONS.contains(extension) && (message.contains("受保护") || message.contains("Invalid data"))) {
            return "未检测到标准音频流；请确认文件可由官方客户端正常播放并拥有使用权";
        }
        return message.length() > 160 ? message.substring(0, 157) + "…" : message;
    }

    private static void copyIfPresent(Path source, Path destination) throws IOException {
        if (source != null && Files.isRegularFile(source)) {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 忽略
        }
    }

    @Override
    public void close() {
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
    }
}
