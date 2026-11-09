package cn.gkkstudio;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** 杂项板块支持的操作。 */
enum MiscAction {
    ZIP_PACK("打包为 ZIP", "把选中的文件/文件夹压成一个 .zip",
            "输出 .zip；文件夹会连同子目录一起打包", Set.of("zip"), false),
    ZIP_UNPACK("解压 ZIP", "把 .zip 解到同名文件夹",
            "输出一个文件夹；自动跳过不安全的路径", Set.of("zip"), true),
    TAR_PACK("打包为 TAR.GZ", "压缩成 .tar.gz（Linux 友好）",
            "输出 .tar.gz；保留目录结构", Set.of("tar", "tgz", "gz"), false),
    TAR_UNPACK("解压 TAR / GZ", "解开 .tar / .tar.gz / .tgz / .gz",
            "输出一个文件夹", Set.of("tar", "tgz", "gz"), true),
    IMG_TO_PDF("图片合成 PDF", "每张图片单独生成一个 PDF",
            "输出 .pdf；按图片原始尺寸成页", Set.of("jpg", "jpeg", "png", "bmp", "gif", "tif", "tiff", "webp"), false),
    PDF_TO_IMG("PDF 拆成图片", "把 PDF 每一页导出成图片",
            "输出文件夹；默认 150 DPI PNG", Set.of("pdf"), true);

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
        return this.extensions.contains(extension);
    }

    static List<MiscAction> all() {
        return List.of(values());
    }

    @Override
    public String toString() {
        return this.label;
    }
}
