package cn.gkkstudio;

import java.util.Set;

/**
 * FFmpeg 常见可解码的扩展名（按图片/视频/音频分类）。
 * 这里尽量覆盖 FFmpeg 自带的解码器与容器；不在表里的扩展名仍会被接受，
 * 由运行时用 ffmpeg -i 探测真实流类型再决定归属，避免“格式不支持”误伤。
 */
final class Formats {
    static final Set<String> IMAGE = Set.of(
            "jpg", "jpeg", "jpe", "jfif", "png", "apng", "gif", "bmp", "dib",
            "tif", "tiff", "webp", "ico", "cur", "tga", "icb", "vda", "vst",
            "ppm", "pgm", "pbm", "pnm", "pfm", "pcx", "dds", "exr", "hdr",
            "pic", "psd", "qoi", "sgi", "rgb", "rgba", "bw", "ras", "sun",
            "xbm", "xpm", "jp2", "j2k", "j2c", "dpx", "cin", "pam", "pict", "avif", "heic", "heif", "hif");

    static final Set<String> VIDEO = Set.of(
            "mp4", "m4v", "mov", "qt", "mkv", "webm", "avi", "divx", "xvid",
            "flv", "f4v", "wmv", "asf", "ts", "m2ts", "mts", "mpg", "mpeg",
            "mpe", "m1v", "m2v", "vob", "3gp", "3g2", "ogv", "rm", "rmvb",
            "rmd", "dv", "mxf", "wtv", "nsv", "amv", "roq", "yuv", "y4m",
            "ivf", "mod", "tod", "m2t", "m2p", "pva", "nut", "rec", "mvi",
            "h264", "h265", "hevc", "avc", "vc1", "mjpeg", "mjpg", "m1a",
            "m4s", "ismv", "dav", "swf", "gxf", "lxf", "mtaf", "mse");

    static final Set<String> AUDIO = Set.of(
            "mp3", "mp2", "mp1", "mpa", "wav", "wave", "flac", "aac", "m4a",
            "alac", "ogg", "oga", "opus", "wma", "ape", "aiff", "aif", "aifc",
            "ac3", "eac3", "dts", "dtshd", "amr", "awb", "au", "snd", "mid",
            "midi", "mka", "ra", "ram", "wv", "tta", "spx", "mpc", "ofr",
            "shn", "acm", "caf", "gsm", "voc", "aa", "aa3", "oma", "at3",
            "at9", "m4b", "m4p", "m4r", "sd2", "sol", "vqf", "w64", "xwma",
            "qcp", "slk", "sln", "tak", "dsf", "dff");

    /** 加密音乐容器（音频板块专用）。 */
    static final Set<String> ENCRYPTED = Set.of(
            "kgg", "gkk", "kgm", "kgma", "kge", "kgtemp", "vpr", "ncm",
            "qmc0", "qmc2", "qmc3", "qmcflac", "qmcogg",
            "mflac", "mflac0", "mflach", "mgg", "mgg0", "mgg1", "mggl");

    private Formats() {
    }

    static boolean known(String extension) {
        return IMAGE.contains(extension) || VIDEO.contains(extension)
                || AUDIO.contains(extension) || ENCRYPTED.contains(extension);
    }
}
