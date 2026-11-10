# 格式作坊 · Format Workshop

> 当前版本 2.3.4（2026-11）· [下载安装包](https://github.com/newbeefish/format-workshop/releases/latest)

一个纯本地运行的 Windows 图形化格式转换工具，界面用 Java Swing + FlatLaf 手写（紫色主题、玻璃拟态），
内核全部走 **FFmpeg**，另外内置酷狗 / 网易云 / QQ 音乐加密格式的解密能力。

图片、视频、音频三合一，外加一个杂项工具箱；拖进去就能转，不联网、不上传、不覆盖原文件。

![截图](docs/screenshot.png)

## 功能

### 图片
- 输入：FFmpeg 能解的都行（jpg / png / webp / gif / bmp / tif / **ico** / tga / psd / dds / exr / jp2 …）
- 输出：PNG、JPG、WEBP、BMP、TIFF、TGA、PPM/PGM/PBM、PCX、EXR、HDR、JP2、DPX、QOI、SGI、XBM …（共 23 种；GIF 属于视频板块，见下）
- 尺寸：原尺寸 / 最大边 1920 / 1280 / 800 / 缩小一半
- 画质：高质量 / 标准 / 小体积（JPG、WEBP 生效）

### 视频
- 输入：mp4 / mkv / avi / mov / webm / flv / wmv / ts / m4v / mpg / vob / rmvb / mxf …
- **容器和编码分开选**：
  - 容器 16 种：MP4 / MKV / MOV / WEBM / AVI / FLV / WMV / TS / M2TS / MPG / 3GP / OGV / MXF / NUT / DV / GIF
  - 编码 11 种：H.264 / H.265(HEVC) / VP9 / AV1 / MPEG-4 / MPEG-2 / ProRes / WMV2 / Theora / DV / GIF
  - 容器换掉以后只显示它支持的编码，界面上每一项都标了优缺点（例如 “H.264 · 兼容最广 · 硬解普遍”）
- **能只换壳就不重编码**：编码下拉框默认「原样复制 · 不重编码（最快）」。TS / M4S / MKV / MP4 互换、HEVC 保持 HEVC 这类场景直接 `-c copy`，几秒完成、画质零损失；
  只有容器装不下源编码（例如 VP9 进 MP4）或改过分辨率时才退回重编码
- 音轨也跟着容器走：MKV(H.264+FLAC)→MP4 会「视频直封装 + FLAC 重编码成 AAC」，只有一条音轨需要转
- GIF 只在视频板块提供（会按 12fps 抽帧），图片板块不产出 GIF
- 画质：高质量（CRF 18）/ 标准（23）/ 小体积（28）
- 分辨率：原尺寸 / 1080p / 720p / 480p

### 音频
- 输入：mp3 / wav / flac / aac / m4a / ogg / opus / wma / ape / ac3 / dts / amr …
- 输出：MP3、WAV、FLAC、AAC、M4A、OGG、OPUS、WMA、AIFF、AC3、EAC3、MP2、AMR、AU、MKA、TTA、SPX、CAF …（共 25 种）
- 每种格式后面都带一句短说明（MP3 · 通用音频 / FLAC · 无损音频 / OPUS · 低码率强 …），右侧还会列出它的优缺点
- **换容器不重编码**：AAC→M4A/MKA、MP3→WAV 之外只换壳的场景直接封装；FLAC→MP3 这种必须解码重编码的才会慢
- 视频文件也能直接选音频板块，自动抽音轨
- 加密格式自动解密：
  - 酷狗：`.kgg` `.gkk` `.kgm` `.kgma` `.kge` `.kgtemp` `.vpr`
  - 网易云：`.ncm`
  - QQ 音乐（旧版）：`.qmc0` `.qmc2` `.qmc3` `.qmcflac` `.qmcogg` `.mflac` `.mflac0` `.mflach` `.mgg` `.mgg0` `.mgg1` `.mggl`

### 杂项
第四个板块，处理“不是媒体”的文件，按右上角分成四类：

**压缩打包**
- **打包 / 解压 ZIP**：`.zip` ↔ 文件夹（解压时自动防路径穿越）
- **打包 / 解压 TAR.GZ**：Linux 友好的 `.tar.gz`，也认 `.tar`、`.tgz`、`.gz`
- **打包 / 解包 7Z**：LZMA2，比 ZIP 更小
- **打包 / 解包 XZ**：单文件 LZMA2
- **打包 / 解包 JAR**：带 `META-INF/MANIFEST.MF`，也能直接当 zip 解开
- **打包 / 解包 ZPAQ**：走内置的 `zpaq64.exe`
- **压缩格式互转**：在 ZIP / 7Z / TAR.GZ / TAR.XZ / JAR 之间互转，右侧选目标格式；`.jar` `.7z` `.xz` 都能直接拖进来
- **试 ZPAQ 密码**：给忘了密码的 `.zpaq` 用
  - 填数字范围（起 ～ 止，两端都算）、可选前缀 / 后缀、可选补零位数（填 4 就是 0001…）
  - 程序拿 zpaq 自己逐个验证，找到就弹窗提示，并写出 `原名_密码_1234.txt`
  - 只枚举纯数字；8 线程约 150～200 个/秒，1 万个数字约 1 分钟

**文档与图片**
- **图片合成 PDF**：JPG/PNG/BMP/GIF/TIFF/WEBP → `.pdf`（按原图尺寸成页）
- **PDF 拆成图片**：PDF 每页导出 PNG/JPG（150 DPI）

**校验与文本**
- **计算哈希**（SHA-256 / MD5）、**校验哈希**（拿 `.sha256` / `.md5` 和原文件比对）
- **Base64 编码 / 解码**
- **生成二维码 / 识别二维码**
- **批量重命名**（加三位序号前缀）、**改修改时间**

**开发工具**
- **NASM 汇编编译**：`.asm` → `.obj`（win64 格式）
- **ISO 打包 / 解包 / 挂载**
- **APK 签名**：v1 / v2 / v3，可先 zipalign 对齐

压缩、哈希、二维码、PDF 全在 Java 里做（commons-compress / XZ / ZXing / PDFBox 都打进了 jar），
只有 ZPAQ、NASM、ISO 挂载、APK 签名会用到 `app\tools\` 里的命令行程序。
### 其他
- **输出格式不是写死的**：启动时读 `ffmpeg -encoders`，只把本机真的能编出来的格式列进下拉框（当前构建：图片 23 种、视频 16 容器 × 11 编码、音频 25 种，全部实测通过）
- 并发数默认 = 本机逻辑核心数，可手动调低
- 单实例：程序已开着时从右键菜单进来，文件直接追加进现有窗口
- Windows 右键菜单：`用 格式作坊转为图片 / 转为视频 / 转为音频`（写 HKCU，不需要管理员）
- **自动认文件**：拖进来的东西先用文件头（PNG/JPEG/MP4/MKV/FLAC/ZIP…）+ 一次 `ffmpeg -i` 认一遍，
  判断它是图片 / 视频 / 音频 / 压缩包；拖错板块会自动切到该去的板块并告诉你认出了什么
- 队列里每一行的“状态”会写清楚它是什么，例如「视频 · 1920×1080 · 03:21 · H.264 + AAC」、
  「音频 · 04:13 · FLAC」；把视频拖进音频板块会标「会抽音轨」
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

# 3. 生成 app-image / 安装包（需要 WiX 在 PATH 里；WiX 3 的 candle/light 或 WiX 4/5 的 wix.exe 都认）
jpackage --type exe --name "格式作坊" --app-version 2.3.2 `
  --input installer-input --main-jar GkkAudioStudio.jar --main-class cn.gkkstudio.GkkMp3App `
  --icon format-converter.ico --runtime-image runtime --dest dist `
  --win-dir-chooser --win-menu --win-shortcut
```

`--type msi` 只出 `.msi`，`--type exe` 则是 MSI 外面再套一层安装器（另有 `--win-per-user-install` 可装进 `%LOCALAPPDATA%`）。

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
- 「原样复制」是按容器能装哪些编码来判断的：个别冷门组合（例如 AV1 进 MP4）能封装但老播放器未必认，需要兼容性时手动选编码重编码
- 试 ZPAQ 密码只枚举纯数字（可加前后缀），不试字母和符号组合
- 安装包没有代码签名，首次运行 Windows 会提示“未知发布者”

## 免责声明

本项目仅供个人学习与自用，用于转换**自己合法拥有**的媒体文件。
解密音乐文件可能违反相关平台的服务条款，请自行承担使用风险，不要用于传播或商业用途。

## 许可

本项目以 **GNU General Public License v3.0** 发布，完整条款见 [LICENSE](LICENSE)。

你可以自由使用、修改、再分发，但衍生作品也须以 GPL-3.0 开源，且不提供任何担保。

第三方组件遵循各自许可证：FFmpeg（LGPL/GPL，取决于编译选项）、FlatLaf（Apache-2.0），以及随安装包分发的 KGM2MP3 解密组件（kgg-dec / unlockKuGoWin / ncmdump）。
