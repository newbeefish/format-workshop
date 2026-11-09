package cn.gkkstudio;

import java.nio.file.Path;

/** 队列中的一个待转换文件。 */
final class TransferItem {
    enum State {
        WAITING, ANALYZING, DECRYPTING, CONVERTING, DONE, FAILED, CANCELLED
    }

    final Path source;
    final long size;
    volatile State state = State.WAITING;
    volatile int progress;
    volatile String detail = "等待转换";
    volatile Path output;
    volatile Process process;
    /** 完成后想显示的一句话（例如找回的密码）；为空时用默认的“已完成”。 */
    volatile String note;

    TransferItem(Path path) {
        this.source = path;
        long length = -1L;
        try {
            length = java.nio.file.Files.size(path);
        } catch (Exception ignored) {
            // 文件不可读时保持 -1
        }
        this.size = length;
    }

    String stateText() {
        return switch (this.state) {
            case WAITING -> "等待中";
            case ANALYZING -> "正在分析";
            case DECRYPTING -> "正在解密";
            case CONVERTING -> "转换中";
            case DONE -> "已完成";
            case FAILED -> "失败";
            case CANCELLED -> "已取消";
        };
    }

    String fileName() {
        return this.source.getFileName().toString();
    }
}
