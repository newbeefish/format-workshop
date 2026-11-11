package cn.gkkstudio;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 杂项板块的「认文件」：看一眼文件头和扩展名，判断它该用哪个操作。
 *
 * <p>小白把 `.zpaq` 拖进杂项时，默认选中的是「打包为 ZPAQ」——那会把它再压一遍。
 * 这里认出是归档就自动换成「解包 ZPAQ」，认出是 PDF / 图片 / APK / 汇编 也一样换成对应操作；
 * 认出是视频音频就直接通知主窗口切到对应板块。
 */
final class MiscDetect {

    /** 推荐结果：要么推荐一个杂项操作，要么建议去别的板块。 */
    record Suggestion(MiscAction action, Mode mediaBoard, String kind) {

        boolean hasAction() {
            return this.action != null;
        }

        boolean hasBoard() {
            return this.mediaBoard != null;
        }
    }

    private MiscDetect() {
    }

    static Suggestion suggest(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        String extension = MediaEngine.extension(path);

        // 先按扩展名分：这些扩展名的意图很明确
        switch (extension) {
            case "asm" -> {
                return new Suggestion(MiscAction.ASM_BUILD, null, "汇编源码");
            }
            case "b64" -> {
                return new Suggestion(MiscAction.BASE64_DECODE, null, "Base64 文本");
            }
            case "sha256", "md5" -> {
                return new Suggestion(MiscAction.HASH_VERIFY, null, "校验文件");
            }
            case "apk" -> {
                return new Suggestion(MiscAction.APK_SIGN, null, "APK 安装包");
            }
            case "zpaq" -> {
                return new Suggestion(MiscAction.ZPAQ_UNPACK, null, "ZPAQ 归档");
            }
            case "7z" -> {
                return new Suggestion(MiscAction.SEVENZ_UNPACK, null, "7Z 归档");
            }
            case "jar" -> {
                return new Suggestion(MiscAction.JAR_UNPACK, null, "JAR 包");
            }
            case "xz" -> {
                return new Suggestion(name.endsWith(".tar.xz") ? MiscAction.TAR_UNPACK : MiscAction.XZ_UNPACK,
                        null, name.endsWith(".tar.xz") ? "TAR.XZ 归档" : "XZ 归档");
            }
            case "tar", "tgz" -> {
                return new Suggestion(MiscAction.TAR_UNPACK, null, "TAR 归档");
            }
            case "gz" -> {
                return new Suggestion(MiscAction.TAR_UNPACK, null, "GZ 压缩包");
            }
            case "zip" -> {
                return new Suggestion(MiscAction.ZIP_UNPACK, null, "ZIP 归档");
            }
            case "iso" -> {
                return new Suggestion(MiscAction.ISO_UNPACK, null, "光盘镜像");
            }
            case "pdf" -> {
                return new Suggestion(MiscAction.PDF_TO_IMG, null, "PDF 文档");
            }
            case "rar", "r00" -> {
                return new Suggestion(null, null, "RAR 归档（暂不支持，请先用别的工具解开）");
            }
            default -> {
                // 继续看文件头
            }
        }

        byte[] head = head(path, 16);
        if (startsWith(head, new byte[]{0x7A, 0x50, 0x51})) {
            return new Suggestion(MiscAction.ZPAQ_UNPACK, null, "ZPAQ 归档");
        }
        if (startsWith(head, new byte[]{'%', 'P', 'D', 'F'})) {
            return new Suggestion(MiscAction.PDF_TO_IMG, null, "PDF 文档");
        }
        if (startsWith(head, new byte[]{0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C})) {
            return new Suggestion(MiscAction.SEVENZ_UNPACK, null, "7Z 归档");
        }
        if (startsWith(head, new byte[]{'R', 'a', 'r', '!'})) {
            return new Suggestion(null, null, "RAR 归档（暂不支持，请先用别的工具解开）");
        }
        if (startsWith(head, new byte[]{(byte) 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00})) {
            return new Suggestion(name.endsWith(".tar.xz") ? MiscAction.TAR_UNPACK : MiscAction.XZ_UNPACK,
                    null, "XZ 归档");
        }
        if (startsWith(head, new byte[]{0x1F, (byte) 0x8B})) {
            return new Suggestion(MiscAction.TAR_UNPACK, null, "GZ 压缩包");
        }
        if (isIso(path)) {
            return new Suggestion(MiscAction.ISO_UNPACK, null, "光盘镜像");
        }
        if (startsWith(head, new byte[]{'P', 'K', 3, 4})) {
            if (name.endsWith(".jar")) {
                return new Suggestion(MiscAction.JAR_UNPACK, null, "JAR 包");
            }
            if (name.endsWith(".apk")) {
                return new Suggestion(MiscAction.APK_SIGN, null, "APK 安装包");
            }
            return new Suggestion(MiscAction.ZIP_UNPACK, null, "ZIP 归档");
        }

        // 媒体文件：杂项里没有能处理它们的操作，建议去对应板块
        MediaProbe.Kind kind = MediaProbe.sniff(path);
        if (kind == MediaProbe.Kind.IMAGE) {
            return new Suggestion(MiscAction.IMG_TO_PDF, null, "图片");
        }
        if (kind == MediaProbe.Kind.VIDEO) {
            return new Suggestion(null, Mode.VIDEO, "视频");
        }
        if (kind == MediaProbe.Kind.AUDIO) {
            return new Suggestion(null, Mode.AUDIO, "音频");
        }
        return new Suggestion(null, null, "认不出来的文件");
    }

    // ------------------------------------------------------------------ 内部

    private static byte[] head(Path path, int count) {
        try (InputStream stream = Files.newInputStream(path)) {
            return stream.readNBytes(count);
        } catch (Exception exception) {
            return new byte[0];
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (data[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    /** ISO9660 的卷描述符在偏移 0x8001，标识是 "CD001"。 */
    private static boolean isIso(Path path) {
        try (var channel = Files.newByteChannel(path)) {
            if (channel.size() < 0x8006L) {
                return false;
            }
            channel.position(0x8001L);
            byte[] tag = new byte[5];
            int read = channel.read(java.nio.ByteBuffer.wrap(tag));
            return read == 5
                    && tag[0] == 'C' && tag[1] == 'D' && tag[2] == '0' && tag[3] == '0' && tag[4] == '1';
        } catch (Exception exception) {
            return false;
        }
    }

    /** 给提示语用的一句话。 */
    static String describe(Path path, Suggestion suggestion) {
        String kind = suggestion.kind();
        MiscAction action = suggestion.action();
        StringBuilder builder = new StringBuilder(path.getFileName().toString());
        builder.append("（").append(kind);
        if (action != null) {
            builder.append(" · 用「").append(action.label).append("」");
        }
        builder.append("）");
        return builder.toString();
    }
}
