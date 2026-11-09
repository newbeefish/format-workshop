# 格式作坊 · Format Workshop

一个纯本地运行的 Windows 图形化格式转换工具，界面用 Java Swing + FlatLaf 手写（紫色主题、玻璃拟态），
内核全部走 **FFmpeg**，另外内置酷狗 / 网易云 / QQ 音乐加密格式的解密能力。

图片、视频、音频三合一，拖进去就能转，不联网、不上传、不覆盖原文件。

![截图](docs/screenshot.png)

## 功能

### 图片
- 输入：FFmpeg 能解的都行（jpg / png / webp / gif / bmp / tif / **ico** / tga / psd / dds / exr / jp2 …）
- 输出：PNG（无损）/ JPG / WEBP / BMP / TIFF
- 尺寸：原尺寸 / 最大边 1920 / 1280 / 800 / 缩小一半
- 画质：高质量 / 标准 / 小体积（JPG、WEBP 生效）

### 视频
- 输入：mp4 / mkv / avi / mov / webm / flv / wmv / ts / m4v / mpg / vob / rmvb / mxf …
- 输出：MP4（H.264）/ MKV / WEBM（VP9）/ GIF / 提取音频
- 画质：高质量（CRF 18）/ 标准（23）/ 小体积（28）
- 分辨率：原尺寸 / 1080p / 720p / 480p

### 音频
- 输入：mp3 / wav / flac / aac / m4a / ogg / opus / wma / ape / ac3 / dts / amr …
- 输出：MP3 / WAV / FLAC / AAC / M4A / OGG / WMA / OPUS
- 视频文件也能直接选音频板块，自动抽音轨
- 加密格式自动解密：
  - 酷狗：`.kgg` `.gkk` `.kgm` `.kgma` `.kge` `.kgtemp` `.vpr`
  - 网易云：`.ncm`
  - QQ 音乐（旧版）：`.qmc0` `.qmc2` `.qmc3` `.qmcflac` `.qmcogg` `.mflac` `.mflac0` `.mflach` `.mgg` `.mgg0` `.mgg1` `.mggl`

### 其他
- 并发数默认 = 本机逻辑核心数，可手动调低
- 单实例：程序已开着时从右键菜单进来，文件直接追加进现有窗口
- Windows 右键菜单：`用 格式作坊转为图片 / 转为视频 / 转为音频`（写 HKCU，不需要管理员）
- 输出目录、日志目录、引擎自检都能在界面里看

## 运行环境

- Windows 10 / 11 x64
- 安装包自带裁剪过的 JRE（7 个模块，约 48 MB），无需另装 Java

## 从源码构建

需要 JDK 17+（开发时用的是 JDK 25）和 FFmpeg。

```powershell
# 1. 编译
javac -encoding UTF-8 --release 17 -cp "path\to\flatlaf.jar" -d build src\cn\gkkstudio\*.java

# 2. 打 jar（需要把 FlatLaf 的 classes、图标、mask 一起放进去，见 manifest.txt）
jar --create --file GkkAudioStudio.jar --manifest manifest.txt -C stage .

# 3. 生成 app-image / 安装包（需要 WiX 3 在 PATH 里）
jpackage --type exe --name "格式作坊" --app-version 2.0.0 `
  --input installer-input --main-jar GkkAudioStudio.jar --main-class cn.gkkstudio.GkkMp3App `
  --icon format-converter.ico --runtime-image runtime --dest dist `
  --win-dir-chooser --win-menu --win-shortcut
```

### 解密与转换是怎么实现的

三种方式，**除 FFmpeg 外全部随安装包内置，不需要联网下载，也不需要另外装任何东西**：

| 能力 | 实现方式 | 位置 |
|---|---|---|
| 图片 / 视频 / 音频 转换 | FFmpeg | 内置 `app\tools\ffmpeg.exe`（100 MB） |
| 酷狗 KGM（v4） | 纯 Java 解码，掩码表也在 jar 里 | `KgmDecoder.java` + `res\cn\gkkstudio\kgm-mask-v2.gz` |
| 酷狗 KGG（v5）/ KGMA | 内置命令行组件 | `app\tools\kgg-dec.exe` |
| 酷狗 KGM 64–78 MB 兜底 | 内置命令行组件 | `app\tools\unlockKuGoWin-64.exe` + `kgm.mask` + `infra.dll` |
| 网易云 NCM | 内置命令行组件 | `app\tools\ncmdump.exe` |
| QQ 音乐 QMC / MFLAC / MGG（旧版） | 纯 Java 实现（含 TC-TEA、QMC2 映射、改表 RC4） | `QmcDecoder.java`、`TcTea.java` |

所谓“内置命令行组件”，是指 KGM2MP3 项目里那几个解密程序被**打包进了安装包**（就躺在 `app\tools\` 里），程序在需要时把它们当子进程调用。
它们不是 Java 源码，所以没法编进 jar——但这跟“外部工具需要用户自己装/联网下载”是两回事，全都是随包分发的。

源码仓库为了体积和许可清晰，不包含这些二进制（`ffmpeg.exe` 100 MB、`infra.dll` 等），自己构建时按上表放进 `app\tools\` 即可。

## 已知限制

- KGM 单文件超过 64 MB 会切到内置的 unlockKuGoWin 兜底，超过 78 MB 直接报错（内置掩码表只覆盖到 64 MB）
- HEIC / AVIF 依赖 FFmpeg 版本，当前内置的 9.0 不解这两个
- 视频编码只用 CPU（x264 / VP9），没有硬编码
- QQ 音乐 19.57 之后的 `.mflac` 需要登录态（ekey 不内嵌），程序会提示缺少凭据
- 安装包没有代码签名，首次运行 Windows 会提示“未知发布者”

## 免责声明

本项目仅供个人学习与自用，用于转换**自己合法拥有**的媒体文件。
解密音乐文件可能违反相关平台的服务条款，请自行承担使用风险，不要用于传播或商业用途。

## 许可

本项目以 **GNU General Public License v3.0** 发布，完整条款见 [LICENSE](LICENSE)。

你可以自由使用、修改、再分发，但衍生作品也须以 GPL-3.0 开源，且不提供任何担保。

第三方组件遵循各自许可证：FFmpeg（LGPL/GPL，取决于编译选项）、FlatLaf（Apache-2.0），以及随安装包分发的 KGM2MP3 解密组件（kgg-dec / unlockKuGoWin / ncmdump）。
