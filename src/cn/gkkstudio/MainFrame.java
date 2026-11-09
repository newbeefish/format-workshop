package cn.gkkstudio;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

/** 主窗口：左侧板块切换 + 顶部标题 + 各板块内容。 */
final class MainFrame extends JFrame {
    private final Path rootDirectory = SystemCapabilities.applicationRoot();
    private final Path outputDirectory = this.rootDirectory.resolve("output");
    private final Path logsDirectory = this.rootDirectory.resolve("logs");

    private final Map<Mode, ConversionView> views = new EnumMap<>(Mode.class);
    private final Map<Mode, NavItem> navItems = new EnumMap<>(Mode.class);
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(this.cardLayout);

    private final JLabel headerTitle = new JLabel("图片转换");
    private final JLabel headerSubtitle = new JLabel("");
    private final JLabel engineLabel = new JLabel("正在检测转换引擎…");
    private final JLabel engineDotHost = new JLabel();

    private final JButton openOutputButton = UiKit.secondaryButton("打开输出文件夹");
    private final JButton openLogsButton = UiKit.secondaryButton("打开日志文件夹");

    private SystemCapabilities.Toolchain tools;
    private Mode activeMode = Mode.AUDIO;

    MainFrame() {
        this(List.of(), null);
    }

    MainFrame(List<Path> initialFiles) {
        this(initialFiles, null);
    }

    MainFrame(List<Path> initialFiles, Mode forcedMode) {
        super("格式作坊 · 图片 / 视频 / 音频 转换");
        this.setIconImages(UiKit.appIcons());
        this.tools = SystemCapabilities.detect();
        this.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        this.setMinimumSize(new Dimension(1140, 780));
        this.setSize(1260, 860);
        this.setLocationRelativeTo(null);

        for (Mode mode : Mode.values()) {
            ConversionView view = new ConversionView(mode, this.tools, this.rootDirectory);
            this.views.put(mode, view);
            this.cards.add(view, mode.name());
        }

        this.setContentPane(this.buildRoot());
        this.switchTo(initialMode(initialFiles, forcedMode));
        this.updateCapabilities();
        this.outputFieldCaret();
        if (!initialFiles.isEmpty()) {
            SwingUtilities.invokeLater(() -> this.views.get(this.activeMode).addPaths(initialFiles));
        }
        SwingUtilities.invokeLater(() -> this.getRootPane().requestFocusInWindow());
        new Thread(RegistrySetup::ensureContextMenu, "registry-context-menu").start();
        new Thread(UninstallHelper::ensure, "uninstall-helper").start();

        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                List<ConversionView> runningViews = MainFrame.this.views.values().stream()
                        .filter(ConversionView::isRunning).toList();
                if (!runningViews.isEmpty()) {
                    int answer = JOptionPane.showConfirmDialog(MainFrame.this, "仍有转换在进行，确定要退出吗？", "退出确认", JOptionPane.YES_NO_OPTION);
                    if (answer != JOptionPane.YES_OPTION) {
                        return;
                    }
                }
                MainFrame.this.views.values().forEach(view -> view.engine().close());
                MainFrame.this.dispose();
            }
        });
    }

    private Mode initialMode(List<Path> files, Mode forcedMode) {
        if (!files.isEmpty()) {
            String extension = MediaEngine.extension(files.get(0));
            if (forcedMode != null && forcedMode.supports(extension)) {
                return forcedMode;
            }
            for (Mode mode : Mode.values()) {
                if (mode.supports(extension)) {
                    return mode;
                }
            }
        }
        return forcedMode != null ? forcedMode : Mode.AUDIO;
    }

    private void outputFieldCaret() {
        // 各板块的输出框在构造时已设置，无需额外处理
    }

    /** 从右键菜单/第二个实例转发过来的文件：切到对应板块并加入队列。 */
    void handleExternal(Mode mode, List<Path> files) {
        if (files.isEmpty()) {
            return;
        }
        Mode target = mode;
        if (target == null || !target.supports(MediaEngine.extension(files.get(0)))) {
            for (Mode candidate : Mode.values()) {
                if (candidate.supports(MediaEngine.extension(files.get(0)))) {
                    target = candidate;
                    break;
                }
            }
        }
        if (target != null) {
            this.switchTo(target);
        }
        this.views.get(this.activeMode).addPaths(files);
        this.setVisible(true);
        this.setState(JFrame.NORMAL);
        this.toFront();
        this.requestFocus();
    }

    // ------------------------------------------------------------------ 布局

    private java.awt.Container buildRoot() {
        UiKit.GlassBackdrop backdrop = new UiKit.GlassBackdrop();
        backdrop.setLayout(new BorderLayout(0, 0));
        backdrop.setBorder(new EmptyBorder(10, 10, 10, 10));
        backdrop.add(this.buildSidebar(), BorderLayout.WEST);
        backdrop.add(this.buildContent(), BorderLayout.CENTER);
        return backdrop;
    }

    private Component buildSidebar() {
        UiKit.RoundedPanel panel = new UiKit.RoundedPanel(UiKit.SIDEBAR, 28);
        panel.setPreferredSize(new Dimension(202, 0));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(30, 18, 24, 18));

        JLabel logo = new JLabel("格式作坊", new javax.swing.ImageIcon(
                UiKit.appIcons().get(3).getScaledInstance(34, 34, java.awt.Image.SCALE_SMOOTH)), JLabel.LEFT);
        logo.setIconTextGap(9);
        logo.setForeground(UiKit.TEXT);
        logo.setFont(logo.getFont().deriveFont(Font.BOLD, 18.0f));
        logo.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(logo);

        JLabel subtitle = new JLabel("Universal Converter 2.0");
        subtitle.setForeground(UiKit.MUTED);
        subtitle.setFont(subtitle.getFont().deriveFont(11.0f));
        subtitle.setBorder(new EmptyBorder(10, 0, 6, 0));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(subtitle);

        JLabel tag = new JLabel("FFmpeg 内核 · 图 / 视 / 音");
        tag.setForeground(UiKit.PRIMARY_DARK);
        tag.setFont(tag.getFont().deriveFont(Font.BOLD, 11.0f));
        tag.setBorder(new EmptyBorder(0, 0, 24, 0));
        tag.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(tag);

        for (Mode mode : Mode.values()) {
            NavItem item = new NavItem(mode, this.navText(mode));
            this.navItems.put(mode, item);
            panel.add(item);
            panel.add(Box.createVerticalStrut(8));
        }
        panel.add(Box.createVerticalStrut(12));

        this.openOutputButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        this.openOutputButton.setMaximumSize(new Dimension(166, 38));
        this.openOutputButton.setPreferredSize(new Dimension(166, 38));
        this.openOutputButton.addActionListener(event -> this.openDirectory(this.outputDirectory));
        panel.add(this.openOutputButton);
        panel.add(Box.createVerticalStrut(8));

        this.openLogsButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        this.openLogsButton.setMaximumSize(new Dimension(166, 38));
        this.openLogsButton.setPreferredSize(new Dimension(166, 38));
        this.openLogsButton.addActionListener(event -> this.openDirectory(this.logsDirectory));
        panel.add(this.openLogsButton);
        panel.add(Box.createVerticalStrut(12));

        JButton engineButton = UiKit.linkButton("引擎状态与工具…");
        engineButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        engineButton.addActionListener(event -> this.showEngineDialog());
        panel.add(engineButton);

        JButton logButton = UiKit.linkButton("查看转换日志");
        logButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        logButton.setBorder(new EmptyBorder(4, 0, 0, 0));
        logButton.addActionListener(event -> this.showLogDialog());
        panel.add(logButton);

        panel.add(Box.createVerticalGlue());

        JPanel trust = new JPanel();
        trust.setOpaque(false);
        trust.setLayout(new BoxLayout(trust, BoxLayout.Y_AXIS));
        trust.setBorder(new EmptyBorder(15, 0, 0, 0));
        trust.setMaximumSize(new Dimension(180, 130));
        trust.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel trustTitle = new JLabel("本地安全转换");
        trustTitle.setForeground(UiKit.TEXT);
        trustTitle.setFont(trustTitle.getFont().deriveFont(Font.BOLD));
        trust.add(trustTitle);
        trust.add(Box.createVerticalStrut(8));
        for (String line : new String[]{"文件不会上传网络", "源文件永不覆盖", "图片 / 视频 / 音频 三合一"}) {
            JLabel label = new JLabel(line);
            label.setForeground(UiKit.MUTED);
            label.setFont(label.getFont().deriveFont(12.0f));
            trust.add(label);
            trust.add(Box.createVerticalStrut(3));
        }
        panel.add(trust);
        return panel;
    }

    private String navText(Mode mode) {
        return switch (mode) {
            case IMAGE -> "图片转换";
            case VIDEO -> "视频转换";
            case AUDIO -> "音频转换";
        };
    }

    private Component buildContent() {
        JPanel content = new JPanel(new BorderLayout(0, 22));
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(24, 28, 22, 28));
        content.add(this.buildHeader(), BorderLayout.NORTH);
        this.cards.setOpaque(false);
        content.add(this.cards, BorderLayout.CENTER);
        return content;
    }

    private Component buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        this.headerTitle.setForeground(UiKit.TEXT);
        this.headerTitle.setFont(this.headerTitle.getFont().deriveFont(Font.BOLD, 26.0f));
        this.headerSubtitle.setForeground(UiKit.MUTED);
        this.headerSubtitle.setBorder(new EmptyBorder(5, 0, 0, 0));
        titles.add(this.headerTitle);
        titles.add(this.headerSubtitle);
        header.add(titles, BorderLayout.WEST);

        JPanel status = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        status.setOpaque(false);
        this.engineDotHost.setIcon(new UiKit.StatusDot(this.tools.ready()));
        this.engineLabel.setForeground(new Color(1471569));
        this.engineLabel.setFont(this.engineLabel.getFont().deriveFont(Font.BOLD, 12.0f));
        status.add(this.engineDotHost);
        status.add(this.engineLabel);
        JButton details = UiKit.linkButton("详情");
        details.setMargin(new java.awt.Insets(2, 4, 2, 4));
        details.addActionListener(event -> this.showEngineDialog());
        status.add(details);
        header.add(status, BorderLayout.EAST);
        return header;
    }

    // ------------------------------------------------------------------ 切换与弹窗

    private void switchTo(Mode mode) {
        this.activeMode = mode;
        this.cardLayout.show(this.cards, mode.name());
        this.headerTitle.setText(mode.title);
        this.headerSubtitle.setText(mode.subtitle);
        this.navItems.forEach((key, item) -> item.setActive(key == mode));
    }

    private void updateCapabilities() {
        if (this.tools.ready()) {
            int missing = this.tools.missingImportantCount();
            this.engineLabel.setText(missing == 0 ? "本地引擎已就绪" : "引擎就绪（" + missing + " 项工具缺失）");
            this.engineLabel.setForeground(missing == 0 ? new Color(1471569) : UiKit.DANGER);
            this.engineLabel.setToolTipText(this.tools.hasGpu()
                    ? this.tools.gpuName() + "；编码由 CPU 完成，多任务并行加速"
                    : "未检测到独立显卡；编码使用 CPU");
            this.engineDotHost.setIcon(new UiKit.StatusDot(missing == 0));
        } else {
            this.engineLabel.setText("需要安装 FFmpeg");
            this.engineLabel.setForeground(UiKit.DANGER);
            this.engineDotHost.setIcon(new UiKit.StatusDot(false));
        }
    }

    private ConversionView activeView() {
        return this.views.get(this.activeMode);
    }

    private void showEngineDialog() {
        JDialog dialog = new JDialog(this, "引擎状态与工具", true);
        dialog.setSize(640, 600);
        dialog.setLocationRelativeTo(this);
        UiKit.GlassBackdrop host = new UiKit.GlassBackdrop();
        host.setLayout(new BorderLayout());
        host.setBorder(new EmptyBorder(14, 14, 14, 14));
        dialog.setContentPane(host);

        UiKit.RoundedPanel sheet = new UiKit.RoundedPanel(Color.WHITE, 24);
        sheet.setLayout(new BorderLayout(0, 16));
        sheet.setBorder(new EmptyBorder(24, 26, 22, 26));
        host.add(sheet, BorderLayout.CENTER);

        JLabel title = new JLabel("引擎状态");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20.0f));
        title.setForeground(UiKit.TEXT);
        sheet.add(title, BorderLayout.NORTH);

        JPanel rows = new UiKit.ScrollablePanel(new java.awt.GridBagLayout());
        rows.setOpaque(false);
        java.awt.GridBagConstraints constraints = new java.awt.GridBagConstraints();
        constraints.gridx = 0;
        constraints.weightx = 1.0;
        constraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        constraints.anchor = java.awt.GridBagConstraints.WEST;
        constraints.insets = new java.awt.Insets(0, 0, 10, 0);

        this.addRow(rows, constraints, this.statusRow("FFmpeg 转码引擎", this.tools.ffmpeg()));
        this.addRow(rows, constraints, this.statusRow("kgg-dec（KGG v5 解密）", this.tools.kggDec()));
        this.addRow(rows, constraints, this.statusRow("unlockKuGoWin（KGM 兜底）", this.tools.unlock()));
        this.addRow(rows, constraints, this.statusRow("ncmdump（NCM 解密）", this.tools.ncmdump()));
        this.addRow(rows, constraints, this.statusRow("kgm.mask（KGM 掩码）", this.tools.kgmMask()));
        this.addRow(rows, constraints, this.statusRow("infra.dll（酷狗组件）", this.tools.infra()));
        this.addRow(rows, constraints, this.statusRow("酷狗数据库 KGMusicV3.db", this.tools.kugouDb()));
        this.addRow(rows, constraints, this.qqMusicRow());

        constraints.insets = new java.awt.Insets(6, 0, 4, 0);
        this.addRow(rows, constraints, this.pathLine("库文件", this.tools.kugouDb() == null ? "未找到（KGG v5 需要它）" : this.tools.kugouDb().toString()));
        this.addRow(rows, constraints, this.pathLine("输出目录", this.outputDirectory.toString()));
        this.addRow(rows, constraints, this.pathLine("日志目录", this.logsDirectory.toString()));

        constraints.insets = new java.awt.Insets(10, 0, 0, 0);
        JLabel note = new JLabel("<html><div style='width:430px'>图片 / 视频走 FFmpeg 标准转码；音频额外支持酷狗 KGG / KGM / KGMA / VPR、网易云 NCM、QQ 音乐 QMC 解密。"
                + "KGM 掩码覆盖 64 MB；QQ 音乐 musicex 新版不内嵌密钥，会明确提示。均不会输出被截断的坏文件。</div></html>");
        note.setForeground(UiKit.MUTED);
        note.setFont(note.getFont().deriveFont(12.0f));
        this.addRow(rows, constraints, note);

        JScrollPane scroll = new JScrollPane(rows);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        sheet.add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        bottom.setOpaque(false);
        JButton refresh = UiKit.secondaryButton("刷新环境");
        refresh.setPreferredSize(new Dimension(110, 34));
        refresh.addActionListener(event -> {
            dialog.dispose();
            this.tools = SystemCapabilities.detect();
            this.views.values().forEach(view -> view.engine().close());
            this.views.clear();
            this.navItems.clear();
            this.dispose();
            SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));
        });
        JButton close = UiKit.secondaryButton("关闭");
        close.setPreferredSize(new Dimension(90, 34));
        close.addActionListener(event -> dialog.dispose());
        bottom.add(refresh);
        bottom.add(close);
        sheet.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void addRow(JPanel rows, java.awt.GridBagConstraints constraints, Component row) {
        constraints.gridy = rows.getComponentCount();
        rows.add(row, constraints);
    }

    private Component qqMusicRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        JLabel label = new JLabel("QQ 音乐凭据（新版 QMC）");
        label.setForeground(UiKit.TEXT);
        boolean ok = QqMusic.canFetch();
        JLabel state = new JLabel(ok ? "已就绪" : "未就绪");
        state.setForeground(ok ? UiKit.SUCCESS : UiKit.MUTED);
        state.setFont(state.getFont().deriveFont(Font.BOLD, 12.0f));
        state.setToolTipText(QqMusic.status());
        row.add(label, BorderLayout.WEST);
        row.add(state, BorderLayout.EAST);
        return row;
    }

    private Component statusRow(String name, Path tool) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        JLabel label = new JLabel(name);
        label.setForeground(UiKit.TEXT);
        JLabel state = new JLabel(tool != null ? "已就绪" : "缺失 / 未找到");
        state.setForeground(tool != null ? UiKit.SUCCESS : UiKit.DANGER);
        state.setFont(state.getFont().deriveFont(Font.BOLD, 12.0f));
        row.add(label, BorderLayout.WEST);
        row.add(state, BorderLayout.EAST);
        return row;
    }

    private Component pathLine(String name, String value) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        JLabel label = new JLabel(name);
        label.setForeground(UiKit.MUTED);
        label.setFont(label.getFont().deriveFont(12.0f));
        label.setPreferredSize(new Dimension(64, 18));
        JLabel path = new JLabel(UiKit.shortenPath(value, 50));
        path.setForeground(UiKit.TEXT);
        path.setFont(path.getFont().deriveFont(11.5f));
        path.setToolTipText(value);
        row.add(label, BorderLayout.WEST);
        row.add(path, BorderLayout.CENTER);
        return row;
    }

    private void showLogDialog() {
        MediaEngine engine = this.activeView().engine();
        JDialog dialog = new JDialog(this, "转换日志", true);
        dialog.setSize(780, 600);
        dialog.setLocationRelativeTo(this);
        UiKit.GlassBackdrop host = new UiKit.GlassBackdrop();
        host.setLayout(new BorderLayout());
        host.setBorder(new EmptyBorder(14, 14, 14, 14));
        dialog.setContentPane(host);

        UiKit.RoundedPanel sheet = new UiKit.RoundedPanel(Color.WHITE, 24);
        sheet.setLayout(new BorderLayout(0, 14));
        sheet.setBorder(new EmptyBorder(24, 26, 22, 26));
        host.add(sheet, BorderLayout.CENTER);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel title = new JLabel("转换日志 · " + this.activeMode.title);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20.0f));
        title.setForeground(UiKit.TEXT);
        header.add(title, BorderLayout.WEST);
        JLabel fileLabel = new JLabel(engine.logFile() == null ? "尚未生成日志文件" : engine.logFile().getFileName().toString());
        fileLabel.setForeground(UiKit.MUTED);
        fileLabel.setFont(fileLabel.getFont().deriveFont(12.0f));
        header.add(fileLabel, BorderLayout.EAST);
        sheet.add(header, BorderLayout.NORTH);

        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        area.setForeground(UiKit.TEXT);
        area.setBackground(new Color(0xFAFAFC));
        area.setBorder(new EmptyBorder(12, 14, 12, 14));
        List<String> lines = engine.logLines();
        area.setText(lines.isEmpty() ? "还没有转换记录。\n\n添加文件并点击“开始转换”后，这里会显示完整过程。" : String.join("\n", lines));
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, new Color(0xE5E5EA)));
        sheet.add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        bottom.setOpaque(false);
        JButton openLogs = UiKit.secondaryButton("打开日志目录");
        openLogs.setPreferredSize(new Dimension(140, 34));
        openLogs.addActionListener(event -> this.openDirectory(this.logsDirectory));
        JButton close = UiKit.secondaryButton("关闭");
        close.setPreferredSize(new Dimension(90, 34));
        close.addActionListener(event -> dialog.dispose());
        bottom.add(openLogs);
        bottom.add(close);
        sheet.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void openDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            Files.createDirectories(directory);
            Desktop.getDesktop().open(directory.toFile());
        } catch (Exception exception) {
            JOptionPane.showMessageDialog(this, "无法打开文件夹：" + exception.getMessage());
        }
    }

    /** 侧栏板块按钮。 */
    private final class NavItem extends JPanel {
        private final Mode target;
        private final JLabel label;
        private boolean active;
        private boolean hovered;

        NavItem(Mode target, String text) {
            this.target = target;
            this.setOpaque(false);
            this.setLayout(new FlowLayout(FlowLayout.LEFT, 12, 11));
            this.setMaximumSize(new Dimension(180, 45));
            this.setAlignmentX(Component.LEFT_ALIGNMENT);
            this.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            this.label = new JLabel(text);
            this.add(this.label);
            this.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent event) {
                    MainFrame.this.switchTo(target);
                }
            });
            this.applyState();
        }

        void setActive(boolean active) {
            this.active = active;
            this.applyState();
            this.repaint();
        }

        private void applyState() {
            this.label.setForeground(this.active ? UiKit.PRIMARY : UiKit.MUTED);
            this.label.setFont(this.label.getFont().deriveFont(this.active ? Font.BOLD : Font.PLAIN, 14.0f));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D graphics2D = (Graphics2D) graphics.create();
            graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = this.active ? UiKit.HIGHLIGHT : (this.hovered ? new Color(0xEDE7F7) : new Color(0xECECEF));
            graphics2D.setColor(fill);
            graphics2D.fillRoundRect(0, 0, this.getWidth(), this.getHeight(), 12, 12);
            graphics2D.dispose();
            super.paintComponent(graphics);
        }
    }

    // 供外部读取当前板块
    Mode activeMode() {
        return this.activeMode;
    }
}
