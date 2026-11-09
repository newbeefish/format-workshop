package cn.gkkstudio;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 音乐凭据读取与 ekey 获取（用于新版 musicex 格式）。
 *
 * <p>Windows 上 QQ 音乐把 uin 存在
 * %APPDATA%\Tencent\QQMusic\QQMusicServiceConfig.ini，
 * 而 authst 只存在于运行中的 QQMusic.exe 进程内存里（没有落盘文件）。
 * 所以这里做三件事：
 * <ol>
 *   <li>读 ini 拿 uin</li>
 *   <li>用 PowerShell + ReadProcessMemory 扫进程内存里的 "authst":"..."</li>
 *   <li>调用 music.vkey.GetEVkey 接口换 ekey</li>
 * </ol>
 * 任何一步缺失都会返回 null，由调用方给出明确提示；不会上传音频文件。
 */
final class QqMusic {
    private static final Pattern EKEY_PATTERN = Pattern.compile("\"ekey\"\\s*:\\s*\"([^\"]+)\"");
    private static String cachedUin;
    private static String cachedAuthst;
    private static boolean scanned;

    private QqMusic() {
    }

    static Path configIni() {
        String appData = System.getenv("APPDATA");
        if (appData == null) {
            return null;
        }
        Path[] candidates = {
                Path.of(appData, "Tencent", "QQMusic", "QQMusicServiceConfig.ini"),
                Path.of(appData, "Tencent", "QQMusic", "Config", "QQMusicServiceConfig.ini"),
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static String uin() {
        if (cachedUin != null) {
            return cachedUin;
        }
        Path ini = configIni();
        if (ini == null) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(ini, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("uin=")) {
                    cachedUin = trimmed.substring(4).trim();
                    return cachedUin;
                }
            }
        } catch (Exception ignored) {
            // 读不到就当没有
        }
        return null;
    }

    static String authst() {
        if (scanned) {
            return cachedAuthst;
        }
        scanned = true;
        try {
            cachedAuthst = scanProcessMemory();
        } catch (Exception ignored) {
            cachedAuthst = null;
        }
        return cachedAuthst;
    }

    /** 供界面显示的状态文案。 */
    static String status() {
        if (configIni() == null) {
            return "未检测到 QQ 音乐（新格式 .mflac/.mgg 需要它在线取密钥）";
        }
        String uin = uin();
        if (uin == null) {
            return "已检测到 QQ 音乐配置，但没有读取到登录账号";
        }
        String authst = authst();
        if (authst == null) {
            return "已检测到账号 " + uin + "；未在 QQMusic.exe 内存中找到 authst（需要客户端正在运行并已登录）";
        }
        return "已获取账号 " + uin + " 的登录凭据，可在线换取新版 QMC 密钥";
    }

    static boolean canFetch() {
        return uin() != null && authst() != null;
    }

    /**
     * 用 musicex 里的资源文件名 + media_mid 换 ekey。
     * 失败返回 null（调用方回退到提示信息）。
     */
    static String fetchEkey(String filename, String mediaMid) {
        if (filename == null || filename.isBlank() || mediaMid == null || mediaMid.isBlank()) {
            return null;
        }
        String uin = uin();
        String authst = authst();
        if (uin == null || authst == null) {
            return null;
        }
        try {
            String body = "{\"comm\":{\"authst\":\"" + authst + "\",\"ct\":\"19\",\"cv\":\"1859\",\"uin\":\"" + uin
                    + "\",\"tmeLoginType\":\"3\"},\"req_1\":{\"module\":\"music.vkey.GetEVkey\",\"method\":\"CgiGetEVkey\","
                    + "\"param\":{\"filename\":[\"" + filename + "\"],\"guid\":\"10000\",\"songmid\":[\"" + mediaMid
                    + "\"],\"songtype\":[1],\"uin\":\"" + uin + "\",\"loginflag\":1,\"platform\":\"27\",\"ctx\":1}}}";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://u.y.qq.com/cgi-bin/musicu.fcg"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return null;
            }
            Matcher matcher = EKEY_PATTERN.matcher(response.body());
            if (matcher.find()) {
                String ekey = matcher.group(1);
                return ekey == null || ekey.isBlank() ? null : ekey;
            }
        } catch (Exception ignored) {
            // 网络不可用或接口变化
        }
        return null;
    }

    // ------------------------------------------------------------------ 内存扫描

    private static final String SCAN_SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            $names = @('QQMusic', 'WeChatAppEx', 'qmbrowser')
            if ($env:KGG_SCAN_PROC) { $names = @($env:KGG_SCAN_PROC) }
            $src = @"
            using System;
            using System.Diagnostics;
            using System.Runtime.InteropServices;
            using System.Text;
            public static class MemScan {
              [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
              [DllImport("kernel32.dll", SetLastError=true)] static extern bool ReadProcessMemory(IntPtr h, IntPtr addr, byte[] buf, int size, out int read);
              [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
              [DllImport("kernel32.dll")] static extern IntPtr VirtualQueryEx(IntPtr h, IntPtr addr, out MEMORY_BASIC_INFORMATION mbi, IntPtr len);
              [StructLayout(LayoutKind.Sequential)] public struct MEMORY_BASIC_INFORMATION {
                public IntPtr BaseAddress; public IntPtr AllocationBase; public uint AllocationProtect;
                public ushort PartitionId; public IntPtr RegionSize; public uint State; public uint Protect; public uint Type;
              }
              public static string Scan(int pid) {
                IntPtr h = OpenProcess(0x0410, false, pid);
                if (h == IntPtr.Zero) return null;
                try {
                  IntPtr addr = IntPtr.Zero;
                  MEMORY_BASIC_INFORMATION mbi = new MEMORY_BASIC_INFORMATION();
                  int size = Marshal.SizeOf(typeof(MEMORY_BASIC_INFORMATION));
                  byte[] buf = new byte[1048576];
                  while (VirtualQueryEx(h, addr, out mbi, (IntPtr)size) != IntPtr.Zero) {
                    long region = mbi.RegionSize.ToInt64();
                    bool readable = mbi.State == 0x1000 && (mbi.Protect & 0x100) == 0 && (mbi.Protect & 0x01) == 0;
                    if (readable && region > 0 && region < 536870912L) {
                      long offset = 0;
                      while (offset < region) {
                        int toRead = (int)Math.Min((long)buf.Length, region - offset);
                        int read;
                        if (ReadProcessMemory(h, new IntPtr(mbi.BaseAddress.ToInt64() + offset), buf, toRead, out read) && read > 0) {
                          string text = Encoding.ASCII.GetString(buf, 0, read);
                          int idx = text.IndexOf("\\"authst\\":\\"", StringComparison.Ordinal);
                          if (idx >= 0) {
                            int start = idx + 10;
                            int end = text.IndexOf('"', start);
                            if (end > start) return text.Substring(start, end - start);
                          }
                          offset += read;
                        } else { break; }
                      }
                    }
                    long next = mbi.BaseAddress.ToInt64() + region;
                    if (next <= addr.ToInt64()) break;
                    addr = new IntPtr(next);
                  }
                  return null;
                } finally { CloseHandle(h); }
              }
            }
            "@
            Add-Type -TypeDefinition $src -Language CSharp
            if ($env:KGG_SCAN_PID) {
              $token = [MemScan]::Scan([int]$env:KGG_SCAN_PID)
              if ($token) { Write-Output $token; exit 0 }
              exit 1
            }
            foreach ($name in $names) {
              foreach ($p in (Get-Process -Name $name -ErrorAction SilentlyContinue)) {
                $token = [MemScan]::Scan($p.Id)
                if ($token) { Write-Output $token; exit 0 }
              }
            }
            exit 1
            """;

    private static String scanProcessMemory() throws IOException {
        Path script = Files.createTempFile("kgq-qqscan-", ".ps1");
        try {
            Files.writeString(script, SCAN_SCRIPT, StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", script.toString())
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            process.waitFor();
            if (output.isEmpty() || output.contains("Exception")) {
                return null;
            }
            return output.lines().findFirst().orElse("").trim();
        } catch (Exception exception) {
            return null;
        } finally {
            try {
                Files.deleteIfExists(script);
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
