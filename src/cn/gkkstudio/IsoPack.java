package cn.gkkstudio;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** ISO9660 + Joliet 写入（数据盘）。 */
final class IsoPack {

    private static final int SECTOR = 2048;

    private static final class Node {
        final String name;
        final Path path;
        final boolean dir;
        long size;
        final List<Node> kids = new ArrayList<>();
        int lba;
        int sectors;
        int jolietLba;
        int jolietSectors;

        Node(String name, Path path, boolean dir, long size) {
            this.name = name;
            this.path = path;
            this.dir = dir;
            this.size = size;
        }

        byte[] ascii() {
            return (this.dir ? this.name : this.name + ";1").getBytes(StandardCharsets.US_ASCII);
        }

        byte[] joliet() {
            return (this.dir ? this.name : this.name).getBytes(StandardCharsets.UTF_16BE);
        }
    }

    private final List<Node> dirs = new ArrayList<>();
    private final List<Node> files = new ArrayList<>();
    private int tableSectors;
    private int totalSectors;
    private Node root;

    static void write(Path source, Path target, String label) throws IOException {
        IsoPack pack = new IsoPack();
        pack.root = new Node("", source, true, 0);
        pack.scan(pack.root);
        pack.sort(pack.root);
        pack.layout();
        pack.emit(target, label);
    }

    private void scan(Node parent) throws IOException {
        List<Path> kids = new ArrayList<>();
        try (var stream = Files.list(parent.path)) {
            stream.forEach(kids::add);
        }
        for (Path kid : kids) {
            String name = kid.getFileName().toString();
            if (Files.isDirectory(kid)) {
                Node node = new Node(name, kid, true, 0);
                parent.kids.add(node);
                scan(node);
            } else {
                parent.kids.add(new Node(name, kid, false, Files.size(kid)));
            }
        }
    }

    private void sort(Node node) {
        node.kids.sort(Comparator.comparing(kid -> kid.name.toLowerCase(java.util.Locale.ROOT)));
        for (Node kid : node.kids) {
            if (kid.dir) {
                sort(kid);
            }
        }
    }

    private void layout() {
        collect(this.root);
        for (Node dir : this.dirs) {
            int ascii = 68;
            int joliet = 68;
            for (Node kid : dir.kids) {
                ascii += length(kid.ascii().length);
                joliet += length(kid.joliet().length);
            }
            dir.sectors = Math.max(1, (ascii + SECTOR - 1) / SECTOR);
            dir.size = (long) dir.sectors * SECTOR;
            dir.jolietSectors = Math.max(1, (joliet + SECTOR - 1) / SECTOR);
        }
        int tableSize = 0;
        for (Node dir : this.dirs) {
            int nameLength = dir == this.root ? 1 : dir.ascii().length;
            tableSize += 8 + nameLength + (nameLength % 2 == 0 ? 0 : 1);
        }
        this.tableSectors = Math.max(1, (tableSize + SECTOR - 1) / SECTOR);
        int next = 19 + this.tableSectors * 2;
        for (Node dir : this.dirs) {
            dir.lba = next;
            next += dir.sectors;
        }
        for (Node file : this.files) {
            file.sectors = Math.max(1, (int) ((file.size + SECTOR - 1) / SECTOR));
            file.lba = next;
            next += file.sectors;
        }
        for (Node dir : this.dirs) {
            dir.jolietLba = next;
            next += dir.jolietSectors;
        }
        this.totalSectors = next;
    }

    private void collect(Node node) {
        this.dirs.add(node);
        for (Node kid : node.kids) {
            if (kid.dir) {
                collect(kid);
            } else {
                this.files.add(kid);
            }
        }
    }

    private static int length(int nameLength) {
        int value = 33 + nameLength;
        return (value % 2 == 0) ? value : value + 1;
    }

    private void emit(Path target, String label) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), 1 << 20)) {
            out.write(new byte[16 * SECTOR]);
            out.write(descriptor(1, label, false));
            out.write(descriptor(2, label, true));
            byte[] terminator = new byte[SECTOR];
            terminator[0] = (byte) 255;
            System.arraycopy("CD001".getBytes(StandardCharsets.US_ASCII), 0, terminator, 1, 5);
            terminator[6] = 1;
            out.write(terminator);
            byte[] tables = new byte[this.tableSectors * SECTOR * 2];
            pathTable(tables, 0, false);
            pathTable(tables, this.tableSectors * SECTOR, true);
            out.write(tables);
            for (Node dir : this.dirs) {
                out.write(directory(dir, false));
            }
            byte[] buffer = new byte[64 * 1024];
            for (Node file : this.files) {
                try (InputStream in = Files.newInputStream(file.path)) {
                    long remaining = file.size;
                    while (remaining > 0) {
                        int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) {
                            break;
                        }
                        out.write(buffer, 0, read);
                        remaining -= read;
                    }
                }
                int pad = (int) ((SECTOR - (file.size % SECTOR)) % SECTOR);
                if (pad > 0) {
                    out.write(new byte[pad]);
                }
            }
            for (Node dir : this.dirs) {
                out.write(directory(dir, true));
            }
        }
    }

    private byte[] descriptor(int type, String label, boolean joliet) {
        byte[] data = new byte[SECTOR];
        data[0] = (byte) type;
        System.arraycopy("CD001".getBytes(StandardCharsets.US_ASCII), 0, data, 1, 5);
        data[6] = 1;
        fill(data, 8, 32, ' ');
        fill(data, 40, 32, joliet ? 0 : ' ');
        String title = (label == null || label.isBlank()) ? "FORMAT-WORKSHOP" : label;
        byte[] titleBytes = joliet ? title.getBytes(StandardCharsets.UTF_16BE)
                : title.toUpperCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(titleBytes, 0, data, 40, Math.min(32, titleBytes.length));
        both32(data, 80, this.totalSectors);
        both16(data, 120, 1);
        both16(data, 124, 1);
        both16(data, 128, SECTOR);
        int tableSize = 0;
        for (Node dir : this.dirs) {
            int nameLength = dir == this.root ? 1 : dir.ascii().length;
            tableSize += 8 + nameLength + (nameLength % 2 == 0 ? 0 : 1);
        }
        both32(data, 132, joliet ? 0 : tableSize);
        little32(data, 140, joliet ? 0 : 19);
        big32(data, 148, joliet ? 0 : 19 + this.tableSectors);
        byte[] rootRecord = joliet
                ? record(this.root.jolietLba, (int) this.root.jolietSectors * SECTOR, true, new byte[]{0})
                : record(this.root.lba, (int) this.root.size, true, new byte[]{0});
        System.arraycopy(rootRecord, 0, data, 156, rootRecord.length);
        fill(data, 190, 128, ' ');
        fill(data, 318, 128, ' ');
        fill(data, 446, 128, ' ');
        fill(data, 574, 128, ' ');
        volumeDate(data, 813);
        volumeDate(data, 830);
        data[881] = 1;
        if (joliet) {
            data[88] = '%';
            data[89] = '/';
            data[90] = 'E';
        }
        return data;
    }

    private void pathTable(byte[] data, int base, boolean bigEndian) {
        int offset = base;
        for (Node dir : this.dirs) {
            byte[] name = dir == this.root ? new byte[]{0} : dir.ascii();
            data[offset] = (byte) name.length;
            data[offset + 1] = 0;
            if (bigEndian) {
                big32(data, offset + 2, dir.lba);
                big32(data, offset + 6, this.dirs.get(parent(dir)).lba);
            } else {
                little32(data, offset + 2, dir.lba);
                little32(data, offset + 6, this.dirs.get(parent(dir)).lba);
            }
            System.arraycopy(name, 0, data, offset + 8, name.length);
            offset += 8 + name.length + (name.length % 2 == 0 ? 0 : 1);
        }
    }

    private int parent(Node target) {
        for (Node dir : this.dirs) {
            if (dir.kids.contains(target)) {
                return this.dirs.indexOf(dir);
            }
        }
        return 0;
    }

    private byte[] directory(Node dir, boolean joliet) {
        int sectors = joliet ? dir.jolietSectors : dir.sectors;
        int lba = joliet ? dir.jolietLba : dir.lba;
        byte[] data = new byte[sectors * SECTOR];
        int offset = 0;
        offset = put(data, offset, lba, sectors * SECTOR, true, new byte[]{0});
        int parentLba = dir == this.root ? lba : (joliet ? this.dirs.get(parent(dir)).jolietLba : this.dirs.get(parent(dir)).lba);
        int parentSize = dir == this.root ? sectors * SECTOR
                : (joliet ? this.dirs.get(parent(dir)).jolietSectors * SECTOR : (int) this.dirs.get(parent(dir)).size);
        offset = put(data, offset, parentLba, parentSize, true, new byte[]{1});
        for (Node kid : dir.kids) {
            int kidLba = kid.dir ? (joliet ? kid.jolietLba : kid.lba) : kid.lba;
            int kidSize = kid.dir ? (joliet ? kid.jolietSectors * SECTOR : (int) kid.size) : (int) kid.size;
            offset = put(data, offset, kidLba, kidSize, kid.dir, joliet ? kid.joliet() : kid.ascii());
        }
        return data;
    }

    private int put(byte[] data, int offset, int lba, int size, boolean dir, byte[] name) {
        int value = length(name.length);
        data[offset] = (byte) value;
        both32(data, offset + 2, lba);
        both32(data, offset + 10, size);
        date(data, offset + 18);
        data[offset + 25] = (byte) (dir ? 0x02 : 0x00);
        both16(data, offset + 28, 1);
        data[offset + 32] = (byte) name.length;
        System.arraycopy(name, 0, data, offset + 33, name.length);
        return offset + value;
    }

    private byte[] record(int lba, int size, boolean dir, byte[] name) {
        byte[] data = new byte[length(name.length)];
        data[0] = (byte) data.length;
        both32(data, 2, lba);
        both32(data, 10, size);
        date(data, 18);
        data[25] = (byte) (dir ? 0x02 : 0);
        both16(data, 28, 1);
        data[32] = (byte) name.length;
        System.arraycopy(name, 0, data, 33, name.length);
        return data;
    }

    private static void both16(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }

    private static void both32(byte[] data, int offset, int value) {
        little32(data, offset, value);
        big32(data, offset + 4, value);
    }

    private static void little32(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static void big32(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }

    private static void date(byte[] data, int offset) {
        LocalDateTime now = LocalDateTime.now();
        data[offset] = (byte) (now.getYear() - 1900);
        data[offset + 1] = (byte) now.getMonthValue();
        data[offset + 2] = (byte) now.getDayOfMonth();
        data[offset + 3] = (byte) now.getHour();
        data[offset + 4] = (byte) now.getMinute();
        data[offset + 5] = (byte) now.getSecond();
        data[offset + 6] = 0;
    }

    private static void volumeDate(byte[] data, int offset) {
        LocalDateTime now = LocalDateTime.now();
        String text = String.format("%04d%02d%02d%02d%02d%02d%02d", now.getYear(), now.getMonthValue(),
                now.getDayOfMonth(), now.getHour(), now.getMinute(), now.getSecond(), 0);
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, data, offset, bytes.length);
        data[offset + 16] = 0;
    }

    private static void fill(byte[] data, int offset, int length, int value) {
        for (int index = 0; index < length; index++) {
            data[offset + index] = (byte) value;
        }
    }
}