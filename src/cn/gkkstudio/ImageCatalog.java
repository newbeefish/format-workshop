package cn.gkkstudio;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 图片格式的说明（优点 / 缺点 / 特性），用在下拉框注解和提示区。 */
final class ImageCatalog {

    record Info(String note, String pros, String cons, String features) {
    }

    private static final Map<String, Info> INFO = new HashMap<>();

    static {
        INFO.put("png", new Info("无损 · 支持透明",
                "完全无损，支持透明通道，截图和设计稿首选",
                "体积大，照片类素材能到 JPG 的好几倍",
                "特性：无损压缩，可反复编辑不掉画质"));
        INFO.put("jpg", new Info("最通用 · 有损",
                "兼容性最好，同画质体积最小，手机/网页都认",
                "有损压缩，不支持透明，反复编辑会累积失真",
                "特性：适合照片、网页配图；透明区域会变成白色"));
        INFO.put("webp", new Info("体积最小 · 有损/无损",
                "同画质比 JPG 小 25–35%，支持透明和动图",
                "老版软件（Win7、部分 Office）不认",
                "特性：网页优化首选，Win10 之后系统原生支持"));
        INFO.put("bmp", new Info("无压缩 · 体积最大",
                "完全不压缩，任何 Windows 程序都能读",
                "体积巨大（一张 1080p 图几十 MB）",
                "特性：只适合做中间文件或老程序兼容"));
        INFO.put("tif", new Info("印刷 / 后期用",
                "无损或高质量压缩，支持多页、CMYK，印刷标准",
                "体积大，普通看图软件支持一般",
                "特性：摄影后期、印刷输出、扫描归档"));
        INFO.put("tiff", INFO.get("tif"));
        INFO.put("tga", new Info("老游戏 / 贴图用",
                "结构简单、无压缩，游戏引擎和 3D 软件常见",
                "体积大，浏览器和办公软件不认",
                "特性：Targa，支持 32 位带透明"));
        INFO.put("ppm", new Info("纯位图 · 无压缩",
                "格式极简单，几乎所有图像库都支持",
                "体积巨大，无元数据",
                "特性：Netpbm 系列，常用于测试和图像处理"));
        INFO.put("pgm", new Info("灰度纯位图",
                "灰度图无损、结构简单", "只支持灰度，体积大",
                "特性：Netpbm 灰度格式，做图像算法的中间格式"));
        INFO.put("pbm", new Info("黑白位图",
                "1 位黑白，体积极小", "只有黑白两色",
                "特性：Netpbm 二值格式，适合线稿、掩码"));
        INFO.put("pnm", new Info("Netpbm 通用",
                "PBM/PGM/PPM 的统称，图像库通用", "体积大、不支持透明",
                "特性：科学计算和机器学习里常见"));
        INFO.put("pcx", new Info("老式 PC 位图",
                "早期 DOS/Windows 图形软件通用", "压缩效率低，现在几乎没人用",
                "特性：PC Paintbrush，老扫描仪和传真常见"));
        INFO.put("exr", new Info("高动态范围 (HDR)",
                "支持 32 位浮点、多通道，影视特效标准",
                "体积非常大，普通看图软件打不开",
                "特性：电影后期合成（ACES）用它存档"));
        INFO.put("hdr", new Info("高动态范围",
                "记录超过 8bit 的亮度，适合环境贴图",
                "体积大，需要 HDR 显示器或后期软件",
                "特性：Radiance HDR，3D 环境光和全景常用"));
        INFO.put("jp2", new Info("JPEG 2000",
                "同画质优于 JPG，支持无损和渐进传输",
                "编码慢、生态小，浏览器基本不支持",
                "特性：医疗影像、数字电影、档案保存"));
        INFO.put("j2k", INFO.get("jp2"));
        INFO.put("dpx", new Info("数字电影中间格式",
                "10/12 位固定位深，电影工业标准",
                "体积极大，只有专业软件认",
                "特性：胶片扫描、DI 调色流程"));
        INFO.put("qoi", new Info("极快编解码",
                "编码解码极快，无损、代码实现极简",
                "生态很小，播放器几乎都不认",
                "特性：游戏截图、快速图像缓存"));
        INFO.put("psd", new Info("Photoshop 工程",
                "保留图层信息（本工具只写合成后的单层）",
                "体积大，只有 PS 系软件能完整打开",
                "特性：导出给设计同事继续编辑"));
        INFO.put("sgi", new Info("SGI 位图",
                "工作站时代标准，支持 RLE 压缩", "现在基本见不到",
                "特性：老 3D 软件和科研图像"));
        INFO.put("sun", new Info("Sun Raster",
                "老 Unix 工作站格式，支持多种位深", "生态几乎消失",
                "特性：SunOS 时代的截图格式"));
        INFO.put("xbm", new Info("C 代码位图",
                "能被直接嵌进 C 源码，X11 图标常用", "只有黑白两色",
                "特性：嵌入式 UI、老 Linux 图标"));
        INFO.put("xpm", new Info("C 代码彩色图",
                "纯文本、可嵌 C 源码，支持少量颜色", "色深有限、体积大",
                "特性：老 X11 桌面图标"));
        INFO.put("pam", new Info("Netpbm 增强版",
                "支持任意通道和透明", "体积大，支持软件少",
                "特性：Netpbm 家族里唯一能带 alpha 的"));
        INFO.put("pict", new Info("Mac 老格式",
                "老 Mac 系统和 QuickTime 支持", "现在几乎淘汰",
                "特性：Mac OS Classic 时代的剪贴板格式"));
        INFO.put("pfm", new Info("浮点位图",
                "记录浮点像素值，做图像算法方便", "只有专业工具读",
                "特性：深度图、法线图、HDR 计算中间格式"));
    }

    private ImageCatalog() {
    }

    static Info of(String extension) {
        Info info = INFO.get(extension);
        if (info == null) {
            info = new Info("FFmpeg 直接写出", "由 FFmpeg 编码器写出", "冷门格式，部分软件可能不认", "");
        }
        return info;
    }
}
