package cn.gkkstudio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 在安装目录生成「卸载 格式作坊.cmd」，并在开始菜单补一个卸载快捷方式，
 * 内部调用 Windows Installer 的卸载命令（MsiExec /X 产品码）。
 * 这样用户不需要记命令行，也能“点一下就删掉”。
 */
final class UninstallHelper {
    private static final String SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            $exe = $env:FMT_EXE
            if (-not $exe) { exit 1 }
            $root = Split-Path -Parent $exe
            $paths = @(
              'HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall',
              'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall'
            )
            $entry = $null
            foreach ($p in $paths) {
              foreach ($k in (Get-ChildItem $p -ErrorAction SilentlyContinue)) {
                $props = Get-ItemProperty $k.PSPath -ErrorAction SilentlyContinue
                if ($props -and $props.DisplayName -eq '格式作坊') { $entry = $k; break }
              }
              if ($entry) { break }
            }
            if (-not $entry) { exit 1 }
            $code = $entry.PSChildName
            $cmdPath = Join-Path $root '卸载 格式作坊.cmd'
            $lines = @('@echo off', 'rem 卸载 KGG 格式作坊', ('msiexec /x ' + $code + ' /qb'), '')
            Set-Content -LiteralPath $cmdPath -Value $lines -Encoding OEM

            $menu = Join-Path $env:APPDATA 'Microsoft\\Windows\\Start Menu\\Programs\\格式作坊'
            New-Item -ItemType Directory -Path $menu -Force | Out-Null
            $ws = New-Object -ComObject WScript.Shell
            $lnkPath = Join-Path $menu '卸载 格式作坊.lnk'
            $lnk = $ws.CreateShortcut($lnkPath)
            $lnk.TargetPath = Join-Path $env:SystemRoot 'System32\\msiexec.exe'
            $lnk.Arguments = ('/x ' + $code + ' /qb')
            $lnk.IconLocation = ($exe + ',0')
            $lnk.WorkingDirectory = $root
            $lnk.Save()
            """;

    private UninstallHelper() {
    }

    static void ensure() {
        try {
            String exe = System.getProperty("jpackage.app-path");
            if (exe == null || !Files.isRegularFile(Path.of(exe))) {
                return;
            }
            Path script = Files.createTempFile("fmt-uninstall-", ".ps1");
            try {
                // PowerShell 5.1 按 ANSI 读 .ps1，必须带 BOM 才能识别中文
                Files.writeString(script, "\uFEFF" + SCRIPT, StandardCharsets.UTF_8);
                ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile",
                        "-ExecutionPolicy", "Bypass", "-File", script.toString());
                builder.environment().put("FMT_EXE", exe);
                Process process = builder.redirectErrorStream(true).start();
                process.getInputStream().readAllBytes();
                process.waitFor();
            } finally {
                try {
                    Files.deleteIfExists(script);
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        } catch (Exception ignored) {
            // 失败不影响主功能
        }
    }
}
