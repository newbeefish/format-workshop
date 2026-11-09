package cn.gkkstudio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 最小 ISO9660 读取器：只处理 PVD + 目录树（不做 UDF / Rock Ridge）。 */
final class IsoRead {

    private static final int SECTOR = 2048;

    static final class Entry {
        final String name;
        final boolean dir;
        final int lba;
        final int size;

        Entry(String name, boolean dir, int lba, int size) {
            this.name = name;
            this.dir = dir;
            this.lba = lba;
            this.size = size;
        }
    }

    static List<String> list(Path iso) throws IOException {
        List<String> result = new ArrayList<>();
        try (var channel = Files.newByteChannel(iso)) {
            java.nio.ByteBuffer pvd = java.nio.ByteBuffer.allocate(SECTOR);
            channel.position(16L * SECTOR);
            channel.read(pvd);
            byte[] data = pvd.array();
            if (data[0] != 1 || !"CD001".equals(new String(data, 1, 5, StandardCharsets.US_ASCII))) {
                throw new IOException("不是 ISO9660 镜像");
            }
            int rootLba = little32(data, 158);
            int rootSize = little32(data, 166);
            walk(channel, rootLba, rootSize, "", result, 0);
        }
        return result;
    }

    private static void walk(java.nio.channels.SeekableByteChannel channel, int lba, int size,
                             String prefix, List<String> out, int depth) throws IOException {
        if (depth > 16) {
            return;
        }
        byte[] block = new byte[size];
        channel.position((long) lba * SECTOR);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(block);
        channel.read(buffer);
        int offset = 0;
        while (offset + 33 < block.length) {
            int length = block[offset] & 0xFF;
            if (length == 0) {
                offset = ((offset / SECTOR) + 1) * SECTOR;
                continue;
            }
            int extent = little32(block, offset + 2);
            int dataLength = little32(block, offset + 10);
            boolean dir = (block[offset + 25] & 0x02) != 0;
            int nameLength = block[offset + 32] & 0xFF;
            String name = new String(block, offset + 33, nameLength, StandardCharsets.US_ASCII);
            boolean dot = nameLength == 1 && (name.charAt(0) == 0 || name.charAt(0) == 1);
            if (!dot && nameLength > 0) {
                if (name.endsWith(";1")) {
                    name = name.substring(0, name.length() - 2);
                }
                out.add(prefix + name + (dir ? "/" : "") + " (" + dataLength + " bytes)");
                if (dir) {
                    walk(channel, extent, dataLength, prefix + name + "/", out, depth + 1);
                }
            }
            offset += length;
        }
    }

    static void extract(Path iso, Path target) throws IOException {
        Files.createDirectories(target);
        try (var channel = Files.newByteChannel(iso)) {
            java.nio.ByteBuffer pvd = java.nio.ByteBuffer.allocate(SECTOR);
            channel.position(16L * SECTOR);
            channel.read(pvd);
            byte[] data = pvd.array();
            int rootLba = little32(data, 158);
            int rootSize = little32(data, 166);
            extractDir(channel, rootLba, rootSize, target, 0);
        }
    }

    private static void extractDir(java.nio.channels.SeekableByteChannel channel, int lba, int size,
                                   Path target, int depth) throws IOException {
        if (depth > 16) {
            return;
        }
        byte[] block = new byte[size];
        channel.position((long) lba * SECTOR);
        channel.read(java.nio.ByteBuffer.wrap(block));
        int offset = 0;
        while (offset + 33 < block.length) {
            int length = block[offset] & 0xFF;
            if (length == 0) {
                offset = ((offset / SECTOR) + 1) * SECTOR;
                continue;
            }
            int extent = little32(block, offset + 2);
            int dataLength = little32(block, offset + 10);
            boolean dir = (block[offset + 25] & 0x02) != 0;
            int nameLength = block[offset + 32] & 0xFF;
            String name = new String(block, offset + 33, nameLength, StandardCharsets.US_ASCII);
            boolean dot = nameLength == 1 && (name.charAt(0) == 0 || name.charAt(0) == 1);
            if (!dot && nameLength > 0) {
                if (name.endsWith(";1")) {
                    name = name.substring(0, name.length() - 2);
                }
                name = name.replace('/', '_').replace('\\', '_');
                Path out = target.resolve(name);
                if (dir) {
                    Files.createDirectories(out);
                    extractDir(channel, extent, dataLength, out, depth + 1);
                } else {
                    byte[] content = new byte[dataLength];
                    channel.position((long) extent * SECTOR);
                    channel.read(java.nio.ByteBuffer.wrap(content));
                    try (OutputStream stream = Files.newOutputStream(out)) {
                        stream.write(content);
                    }
                }
            }
            offset += length;
        }
    }

    private static int little32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }
}