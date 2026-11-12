package cn.gkkstudio;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** 杂项板块支持的操作。 */
enum MiscAction {
    ZPAQ_PACK("打包为 ZPAQ", "ZPAQ 高压缩比归档",
            "输出 .zpaq；可用 zpaq 自带密码", Set.of(""), false),
    ZPAQ_UNPACK("解包 ZPAQ", "解开 .zpaq 归档",
            "输出一个文件夹", Set.of("zpaq"), true),
    ZPAQ_TEST_PASSWORD("试 ZPAQ 密码", "在设定的数字范围里一个个试",
            "只试数字：前缀 + 数字 + 后缀；找到后输出 txt，里面写着密码", Set.of("zpaq"), false),
    SEVENZ_PACK("打包为 7Z", "LZMA2 高压缩比",
            "输出 .7z；比 ZIP 更小", Set.of(""), false),
    SEVENZ_UNPACK("解包 7Z", "解开 .7z 压缩包",
            "输出一个文件夹", Set.of("7z"), true),
    XZ_PACK("打包为 XZ", "单文件 LZMA2 压缩",
            "输出 .xz；适合大文件归档", Set.of(""), false),
    XZ_UNPACK("解包 XZ", "解开 .xz 单文件",
            "输出还原后的文件", Set.of("xz"), false),    JAR_PACK("打包为 JAR", "把文件夹打成可执行的 Java 包",
            "输出 .jar（带 META-INF/MANIFEST.MF）", Set.of(""), false),
    JAR_UNPACK("解包 JAR", "把 .jar 当 zip 解开",
            "输出一个文件夹", Set.of("jar"), true),    HASH_VERIFY("校验哈希", "拿 .sha256/.md5 文件和原文件比对",
            "把校验文件拖进来即可，自动找同名原文件", Set.of("sha256", "md5", "txt"), false),    QR_GENERATE("生成二维码", "把文字/网址做成二维码图片",
            "在“参数”里填内容，输出 qr.png", Set.of(""), false),
    QR_READ("识别二维码", "从图片里读出二维码内容",
            "输出同名 .txt", Set.of("png", "jpg", "jpeg", "bmp", "gif"), false),    HASH("计算哈希", "生成 SHA-256 / MD5 校验值",
            "输出 .sha256 文本，可用来校验文件是否损坏", Set.of(""), false),
    BASE64_ENCODE("Base64 编码", "把文件编码成 base64 文本",
            "输出 .b64", Set.of(""), false),
    BASE64_DECODE("Base64 解码", "把 .b64 还原成原文件",
            "输出还原后的文件", Set.of("b64", "txt"), false),
    RENAME("批量重命名", "给文件加三位序号前缀",
            "输出到新目录，原名保留在后面，不改原文件", Set.of(""), false),
    TOUCH("改修改时间", "把文件修改时间改成当前时间",
            "就地修改，输出仍指向原文件", Set.of(""), false),    ARCHIVE_CONVERT("压缩格式互转", "在 ZIP / 7Z / TAR.GZ / TAR.XZ / JAR 之间互转",
            "右侧选目标格式；输入可以是 zip / jar / 7z / tar / tar.gz / tgz / xz", Set.of("zip", "jar", "7z", "tar", "tgz", "gz", "xz"), false),    ZIP_PACK("打包为 ZIP", "把选中的文件/文件夹压成一个 .zip",
            "输出 .zip；文件夹会连同子目录一起打包", Set.of(""), false),
    ZIP_UNPACK("解压 ZIP", "把 .zip 解到同名文件夹",
            "输出一个文件夹；自动跳过不安全的路径", Set.of("zip"), true),
    TAR_PACK("打包为 TAR.GZ", "压缩成 .tar.gz（Linux 友好）",
            "输出 .tar.gz；保留目录结构", Set.of(""), false),
    TAR_UNPACK("解压 TAR / GZ", "解开 .tar / .tar.gz / .tgz / .gz",
            "输出一个文件夹", Set.of("tar", "tgz", "gz"), true),
    IMG_TO_PDF("图片合成 PDF", "每张图片单独生成一个 PDF",
            "输出 .pdf；按图片原始尺寸成页", Set.of("jpg", "jpeg", "png", "bmp", "gif", "tif", "tiff", "webp"), false),
    PDF_TO_IMG("PDF 拆成图片", "把 PDF 每一页导出成图片",
            "输出文件夹；默认 150 DPI PNG", Set.of("pdf"), true),
    ASM_BUILD("汇编编译", "用 NASM 把 .asm 编译成 .obj",
            "输出同目录 .obj；win64 格式，可再用链接器生成 exe", Set.of("asm"), false),    ISO_PACK("打包为 ISO", "把文件夹做成 ISO9660 光盘镜像",
            "输出 .iso；Windows 双击即可挂载", Set.of(""), false),
    ISO_UNPACK("解包 ISO", "把 ISO 里的文件导出",
            "输出一个文件夹；不支持 UDF 盘", Set.of("iso"), true),
    ISO_MOUNT("挂载 ISO（试运行）", "调用 Windows 自带光盘驱动挂载",
            "挂上后自动打开盘符", Set.of("iso"), false),
    APK_SIGN("APK 签名", "用 keystore 给 APK 签名（v1 / v2 / v3）",
            "输出已签名 APK；可先 zipalign 对齐，用官方 apksigner 实现",
            Set.of("apk"), false),
    POLYGLOT_PACK("图片伪装成 ZIP", "把文件塞进 PNG / JPG，图片照常能看",
            "输出一张图片；改后缀成 .zip 就能解压出发出去的东西（先在“载体图片”里选一张图）",
            Set.of(""), false),
    POLYGLOT_UNPACK("从图片里取出内容", "解开藏在图片尾部的压缩包",
            "输出一个文件夹；原图片本身不动，照常能看",
            Set.of("png", "jpg", "jpeg"), true);

    /** 板块分类：压缩打包 / 文档与图片 / 校验与文本 / 开发工具。 */
    String category() {
        return switch (this) {
            case ZPAQ_PACK, ZPAQ_UNPACK, ZPAQ_TEST_PASSWORD, SEVENZ_PACK, SEVENZ_UNPACK, XZ_PACK, XZ_UNPACK, JAR_PACK, JAR_UNPACK, ARCHIVE_CONVERT, ZIP_PACK, ZIP_UNPACK, TAR_PACK, TAR_UNPACK -> "压缩打包";
            case HASH_VERIFY, QR_GENERATE, QR_READ, HASH, BASE64_ENCODE, BASE64_DECODE, RENAME, TOUCH -> "校验与文本";
            case IMG_TO_PDF, PDF_TO_IMG, POLYGLOT_PACK, POLYGLOT_UNPACK -> "文档与图片";
            case ASM_BUILD, ISO_PACK, ISO_UNPACK, ISO_MOUNT, APK_SIGN -> "开发工具";
        };
    }

    final String label;
    final String hint;
    final String detail;
    final Set<String> extensions;
    final boolean producesFolder;

    MiscAction(String label, String hint, String detail, Set<String> extensions, boolean producesFolder) {
        this.label = label;
        this.hint = hint;
        this.detail = detail;
        this.extensions = extensions;
        this.producesFolder = producesFolder;
    }

    boolean supports(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1) : "";
        if (this == TAR_UNPACK) {
            return name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".gz");
        }
        // 空的扩展名代表「任何文件都收」（打包、哈希、改名、改时间…）
        return this.extensions.contains("") || this.extensions.contains(extension);
    }

    /** 只管“把文件收进来打包”的动作；自动换操作时只在它们之间让路。 */
    boolean isPackAction() {
        return switch (this) {
            case ZIP_PACK, TAR_PACK, SEVENZ_PACK, XZ_PACK, JAR_PACK, ZPAQ_PACK -> true;
            default -> false;
        };
    }

    static List<MiscAction> all() {
        return List.of(values());
    }

    @Override
    public String toString() {
        return this.label;
    }
}
