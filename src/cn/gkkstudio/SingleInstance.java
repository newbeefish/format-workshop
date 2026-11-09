package cn.gkkstudio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 单实例协调：程序已经开着的时候，右键菜单再点一次不会开新窗口，
 * 而是把文件（和板块）通过本机回环端口转交给已有窗口。
 */
final class SingleInstance {
    private static final int PORT = 51783;

    private SingleInstance() {
    }

    /** 把请求转给已运行的实例；成功返回 true。 */
    static boolean forward(Mode mode, List<Path> files) {
        if (files.isEmpty()) {
            return false;
        }
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), PORT)) {
            socket.setSoTimeout(3000);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println(mode == null ? "" : mode.name());
            for (Path file : files) {
                writer.println(file.toAbsolutePath().toString());
            }
            writer.flush();
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    interface Handler {
        void onRequest(Mode mode, List<Path> files);
    }

    /** 在当前实例里开监听；端口被占用说明已有实例，返回 false。 */
    static boolean listen(Handler handler) {
        try {
            ServerSocket server = new ServerSocket(PORT, 8, InetAddress.getLoopbackAddress());
            Thread thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                        String first = reader.readLine();
                        Mode mode = null;
                        if (first != null && !first.isBlank()) {
                            try {
                                mode = Mode.valueOf(first.trim());
                            } catch (IllegalArgumentException ignored) {
                                mode = null;
                            }
                        }
                        List<Path> files = new ArrayList<>();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.isBlank()) {
                                files.add(Path.of(line.trim()));
                            }
                        }
                        if (!files.isEmpty()) {
                            handler.onRequest(mode, files);
                        }
                    } catch (Exception ignored) {
                        // 单个请求失败不影响监听
                    }
                }
            }, "single-instance-listener");
            thread.setDaemon(true);
            thread.start();
            return true;
        } catch (IOException exception) {
            return false;
        }
    }
}
