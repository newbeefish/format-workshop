package cn.gkkstudio;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.MouseAdapter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/** 一个板块的完整界面：投放区 + 队列 + 设置。 */
final class ConversionView extends JPanel {
    private final Mode mode;
    private Path rootDirectory;
    private Path outputDirectory;
    private MediaEngine engine;

    private final List<TransferItem> items = new CopyOnWriteArrayList<>();
    private final QueueTableModel tableModel = new QueueTableModel();
    private final JTable table = new JTable(this.tableModel) {
        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            super.paintComponent(graphics);
            if (this.getRowCount() == 0) {
                java.awt.Graphics2D graphics2D = (java.awt.Graphics2D) graphics.create();
                graphics2D.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                graphics2D.setColor(UiKit.MUTED);
                graphics2D.setFont(this.getFont().deriveFont(14.0f));
                String line = "等待你的第一个文件";
                graphics2D.drawString(line, (this.getWidth() - graphics2D.getFontMetrics().stringWidth(line)) / 2, this.getHeight() / 2);
                graphics2D.setFont(this.getFont().deriveFont(12.0f));
                line = "在右侧选好格式，点“开始转换”即可。";
                graphics2D.drawString(line, (this.getWidth() - graphics2D.getFontMetrics().stringWidth(line)) / 2, this.getHeight() / 2 + 26);
                graphics2D.dispose();
            }
        }
    };

    private final JLabel countLabel = new JLabel("0 个文件");
    private final JLabel summaryLabel = new JLabel("拖入文件即可开始");
    private final JProgressBar overallProgress = new JProgressBar(0, 100);

    private final JButton startButton = UiKit.primaryButton("开始转换");
    private final JButton cancelButton = UiKit.secondaryButton("取消");
    private final JButton clearButton = UiKit.secondaryButton("清空列表");
    private final JButton removeButton = UiKit.secondaryButton("移除选中");
    private final JButton browseButton = UiKit.secondaryButton("更改文件夹…");
    private final JButton importButton = UiKit.secondaryButton("选择文件…");

    private final JTextField outputField = new JTextField();
    private final JCheckBox sourceDirectory = new JCheckBox("保存到源文件夹");
    private JComboBox<Object> formatBox;
    private JComboBox<Object> codecBox = new JComboBox<>();
    private JLabel codecLabel;
    private JComboBox<Object> sizeBox;
    private JComboBox<Object> qualityBox;
    private JLabel sizeLabel;
    private JLabel qualityLabel;
    private final JLabel hintLabel = new JLabel();
    private static final int MAX_CONCURRENCY = Math.max(1, Runtime.getRuntime().availableProcessors());

    /** 音频板块的「原样复制」：容器装得下源编码时直接封装。 */
    private static final AudioCatalog.Codec COPY_AUDIO =
            new AudioCatalog.Codec("copy", "原样复制 · 不重编码（最快）", java.util.List.of());

    private final JSpinner concurrencySpinner = new JSpinner(new SpinnerNumberModel(MAX_CONCURRENCY, 1, MAX_CONCURRENCY, 1));

    /** 把文件转交给别的板块（自动认文件时用）。 */
    interface BoardRouter {
        void route(Mode mode, java.util.List<Path> paths, String message);
    }

    private final BoardRouter router;

    /** 顶上那个「显卡 / CPU」开关的状态；默认 CPU。 */
    private boolean useGpu;

    private boolean running;

    ConversionView(Mode mode, SystemCapabilities.Toolchain tools, Path rootDirectory) {
        this(mode, tools, rootDirectory, null);
    }

    ConversionView(Mode mode, SystemCapabilities.Toolchain tools, Path rootDirectory, BoardRouter router) {
        this.mode = mode;
        this.rootDirectory = rootDirectory;
        this.router = router;
        this.outputDirectory = rootDirectory.resolve("output");
        this.engine = new MediaEngine(tools, rootDirectory.resolve("logs"));

        Targets.init(tools.ffmpeg());
        this.formatBox = new JComboBox<>(mode == Mode.VIDEO
                ? VideoCatalog.containers().toArray(new Targets.Target[0])
                : Targets.forMode(mode).toArray(new Targets.Target[0]));
        Object[] sizes = mode.sizeOptions();
        this.sizeBox = new JComboBox<>(sizes == null ? new Object[]{"—"} : sizes);
        this.qualityBox = new JComboBox<>(mode.qualityOptions());
        this.sizeLabel = UiKit.inspectorLabel(mode.sizeLabel());
        this.qualityLabel = UiKit.inspectorLabel(mode.qualityLabel());
        this.outputField.setText(this.outputDirectory.toString());

        this.setLayout(new BorderLayout(24, 0));
        this.setOpaque(false);
        this.add(this.buildMainColumn(), BorderLayout.CENTER);
        this.add(this.buildSettingsCard(), BorderLayout.EAST);
        this.configureActions();
        this.updateOptionControls();
        this.refreshSummary();
        this.outputField.setCaretPosition(0);
    }

    Mode mode() {
        return this.mode;
    }

    /** 主窗口切换「显卡 / CPU」时通知各板块。 */
    void setUseGpu(boolean useGpu) {
        this.useGpu = useGpu;
        this.updateOptionControls();
    }

    MediaEngine engine() {
        return this.engine;
    }

    boolean isRunning() {
        return this.running;
    }

    // ------------------------------------------------------------------ 布局

    private JComponent buildMainColumn() {
        JPanel column = new JPanel(new BorderLayout(0, 28));
        column.setOpaque(false);
        column.add(this.buildDropCard(), BorderLayout.NORTH);
        column.add(this.buildQueueCard(), BorderLayout.CENTER);
        return column;
    }

    private JComponent buildDropCard() {
        UiKit.DropPanel drop = new UiKit.DropPanel();
        drop.setLayout(new BoxLayout(drop, BoxLayout.Y_AXIS));
        drop.setBorder(new EmptyBorder(28, 20, 24, 20));
        drop.setPreferredSize(new Dimension(0, 216));

        JLabel icon = new JLabel(new UiKit.ImportIcon());
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel title = new JLabel(this.mode.dropTitle);
        title.setForeground(UiKit.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22.0f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel hint = new JLabel(this.mode.dropHint);
        hint.setForeground(UiKit.MUTED);
        hint.setFont(hint.getFont().deriveFont(14.0f));
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);

        this.importButton.setPreferredSize(new Dimension(170, 36));
        this.importButton.setMaximumSize(new Dimension(170, 36));
        this.importButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        this.importButton.addActionListener(event -> this.chooseFiles());

        drop.add(icon);
        drop.add(Box.createVerticalStrut(12));
        drop.add(title);
        drop.add(Box.createVerticalStrut(8));
        drop.add(hint);
        drop.add(Box.createVerticalStrut(18));
        drop.add(this.importButton);
        new DropTarget(drop, new FileDropListener());
        return drop;
    }

    private JComponent buildQueueCard() {
        JPanel card = new JPanel(new BorderLayout(0, 16));
        card.setOpaque(false);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        JLabel title = new JLabel("待转换的文件");
        title.setForeground(UiKit.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15.0f));
        this.countLabel.setForeground(UiKit.MUTED);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(title);
        left.add(Box.createHorizontalStrut(12));
        left.add(this.countLabel);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        this.removeButton.setPreferredSize(new Dimension(104, 32));
        this.clearButton.setPreferredSize(new Dimension(104, 32));
        actions.add(this.removeButton);
        actions.add(this.clearButton);

        top.add(left, BorderLayout.WEST);
        top.add(actions, BorderLayout.EAST);
        card.add(top, BorderLayout.NORTH);

        this.table.setRowHeight(56);
        this.table.setShowGrid(false);
        this.table.setIntercellSpacing(new Dimension(0, 0));
        this.table.setSelectionBackground(UiKit.HIGHLIGHT);
        this.table.setSelectionForeground(UiKit.TEXT);
        this.table.setBackground(UiKit.CARD);
        this.table.setOpaque(false);
        this.table.setForeground(UiKit.TEXT);
        this.table.setFillsViewportHeight(true);
        this.table.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        this.table.getTableHeader().setPreferredSize(new Dimension(0, 38));
        this.table.getTableHeader().setBackground(new Color(0xFAFAFC));
        this.table.getTableHeader().setForeground(UiKit.MUTED);
        this.table.getColumnModel().getColumn(0).setPreferredWidth(240);
        this.table.getColumnModel().getColumn(1).setPreferredWidth(74);
        this.table.getColumnModel().getColumn(2).setPreferredWidth(205);
        this.table.getColumnModel().getColumn(3).setPreferredWidth(100);
        this.table.getColumnModel().getColumn(3).setCellRenderer(new UiKit.ProgressRenderer());
        DefaultTableCellRenderer headerRenderer = new DefaultTableCellRenderer();
        headerRenderer.setBorder(new EmptyBorder(0, 12, 0, 10));
        headerRenderer.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        headerRenderer.setBackground(new Color(0xFAFAFC));
        headerRenderer.setForeground(UiKit.MUTED);
        for (int column = 0; column < this.table.getColumnCount(); column++) {
            this.table.getColumnModel().getColumn(column).setHeaderRenderer(headerRenderer);
        }
        this.table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable jTable, Object value, boolean selected, boolean focused, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(jTable, value, selected, focused, row, column);
                label.setBorder(new EmptyBorder(0, 12, 0, 10));
                label.setToolTipText(value == null ? null : value.toString());
                if (!selected) {
                    label.setBackground(row % 2 == 0 ? new Color(255, 255, 255, 180) : new Color(247, 249, 253, 180));
                }
                return label;
            }
        });

        JScrollPane scroll = new JScrollPane(this.table);
        scroll.setBorder(new MatteBorder(1, 0, 0, 0, new Color(0xE5E5EA)));
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildSettingsCard() {
        UiKit.RoundedPanel panel = new UiKit.RoundedPanel(UiKit.SETTINGS, 28);
        panel.setLayout(new BorderLayout(0, 12));
        panel.setPreferredSize(new Dimension(308, 0));
        panel.setBorder(new EmptyBorder(20, 20, 18, 20));

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("转换设置");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18.0f));
        title.setForeground(UiKit.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(title);
        body.add(Box.createVerticalStrut(12));

        body.add(UiKit.inspectorLabel(this.mode.formatLabel()));
        body.add(Box.createVerticalStrut(7));
        this.formatBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        this.formatBox.setPreferredSize(new Dimension(244, 34));
        this.formatBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.formatBox);
        body.add(Box.createVerticalStrut(9));

        if (this.mode != Mode.IMAGE) {
            this.codecLabel = UiKit.inspectorLabel(this.mode == Mode.VIDEO ? "视频编码" : "音频编码");
            this.codecLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(this.codecLabel);
            body.add(Box.createVerticalStrut(7));
            this.codecBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            this.codecBox.setPreferredSize(new Dimension(244, 34));
            this.codecBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            this.refreshCodecs();
            body.add(this.codecBox);
            body.add(Box.createVerticalStrut(9));
        }

        if (this.mode.sizeOptions() != null) {
            this.sizeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(this.sizeLabel);
            body.add(Box.createVerticalStrut(7));
            this.sizeBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            this.sizeBox.setPreferredSize(new Dimension(244, 34));
            this.sizeBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(this.sizeBox);
            body.add(Box.createVerticalStrut(9));
        }

        this.qualityLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.qualityLabel);
        body.add(Box.createVerticalStrut(7));
        this.qualityBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        this.qualityBox.setPreferredSize(new Dimension(244, 34));
        this.qualityBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.qualityBox);
        body.add(Box.createVerticalStrut(8));

        this.hintLabel.setForeground(UiKit.MUTED);
        this.hintLabel.setFont(this.hintLabel.getFont().deriveFont(12.0f));
        this.hintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.hintLabel);
        body.add(Box.createVerticalStrut(10));

        body.add(UiKit.inspectorLabel("保存位置"));
        body.add(Box.createVerticalStrut(7));
        this.outputField.putClientProperty("JTextField.placeholderText", "输出文件夹");
        this.outputField.setToolTipText("转换后的文件保存到这里");
        this.outputField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        this.outputField.setPreferredSize(new Dimension(244, 34));
        this.outputField.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.outputField);
        body.add(Box.createVerticalStrut(7));
        this.browseButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        this.browseButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        body.add(this.browseButton);
        body.add(Box.createVerticalStrut(9));
        this.sourceDirectory.setOpaque(false);
        this.sourceDirectory.setForeground(UiKit.TEXT);
        this.sourceDirectory.setFont(this.sourceDirectory.getFont().deriveFont(13.0f));
        this.sourceDirectory.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.sourceDirectory);
        body.add(Box.createVerticalStrut(13));

        JPanel concurrency = new JPanel(new BorderLayout(10, 0));
        concurrency.setOpaque(false);
        concurrency.setAlignmentX(Component.LEFT_ALIGNMENT);
        concurrency.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        concurrency.add(UiKit.inspectorLabel("同时转换"), BorderLayout.WEST);
        this.concurrencySpinner.setPreferredSize(new Dimension(72, 32));
        this.concurrencySpinner.setToolTipText("最多 " + MAX_CONCURRENCY + " 个（等于本机逻辑核心数），每个文件一个解码进程");
        this.concurrencySpinner.setEditor(new JSpinner.NumberEditor(this.concurrencySpinner, "0"));
        concurrency.add(this.concurrencySpinner, BorderLayout.EAST);
        body.add(concurrency);
        body.add(Box.createVerticalGlue());

        JScrollPane bodyScroll = new JScrollPane(body);
        bodyScroll.setBorder(null);
        bodyScroll.setOpaque(false);
        bodyScroll.getViewport().setOpaque(false);
        bodyScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        bodyScroll.getVerticalScrollBar().setUnitIncrement(16);
        bodyScroll.getViewport().setScrollMode(javax.swing.JViewport.BLIT_SCROLL_MODE);
        panel.add(bodyScroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        this.overallProgress.setMinimum(0);
        this.overallProgress.setMaximum(100);
        this.overallProgress.setValue(0);
        this.overallProgress.setStringPainted(false);
        this.overallProgress.setBorderPainted(false);
        this.overallProgress.setForeground(UiKit.PRIMARY);
        this.overallProgress.setBackground(UiKit.TRACK);
        this.overallProgress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        this.overallProgress.setPreferredSize(new Dimension(260, 8));
        this.overallProgress.setAlignmentX(Component.CENTER_ALIGNMENT);
        this.overallProgress.setVisible(false);

        this.startButton.setPreferredSize(new Dimension(260, 42));
        this.startButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        this.startButton.setFont(this.startButton.getFont().deriveFont(Font.BOLD, 14.0f));
        this.startButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        this.cancelButton.setPreferredSize(new Dimension(244, 34));
        this.cancelButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        this.cancelButton.setVisible(false);
        this.cancelButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        this.summaryLabel.setForeground(UiKit.MUTED);
        this.summaryLabel.setFont(this.summaryLabel.getFont().deriveFont(12.0f));
        this.summaryLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        bottom.add(this.overallProgress);
        bottom.add(Box.createVerticalStrut(8));
        bottom.add(this.startButton);
        bottom.add(Box.createVerticalStrut(8));
        bottom.add(this.cancelButton);
        bottom.add(Box.createVerticalStrut(10));
        bottom.add(this.summaryLabel);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    // ------------------------------------------------------------------ 行为

    private void configureActions() {
        this.browseButton.addActionListener(event -> this.chooseOutputDirectory());
        this.clearButton.addActionListener(event -> {
            if (this.running) {
                return;
            }
            this.items.clear();
            this.tableModel.fireTableDataChanged();
            this.refreshSummary();
        });
        this.removeButton.addActionListener(event -> {
            if (this.running) {
                return;
            }
            List<TransferItem> toRemove = new ArrayList<>();
            for (int index : this.table.getSelectedRows()) {
                if (index >= 0 && index < this.items.size()) {
                    toRemove.add(this.items.get(index));
                }
            }
            this.items.removeAll(toRemove);
            this.tableModel.fireTableDataChanged();
            this.refreshSummary();
        });
        this.table.getSelectionModel().addListSelectionListener(event -> this.updateActionState());
        this.startButton.addActionListener(event -> this.startConversion());
        this.cancelButton.addActionListener(event -> this.engine.cancel(this.items));
        this.formatBox.addActionListener(event -> {
            if (this.mode != Mode.IMAGE) {
                this.refreshCodecs();
            }
            this.updateOptionControls();
        });
        this.codecBox.addActionListener(event -> this.updateOptionControls());
        this.sizeBox.addActionListener(event -> this.updateOptionControls());
        this.sourceDirectory.addActionListener(event -> {
            boolean manual = !this.sourceDirectory.isSelected();
            this.outputField.setEnabled(manual);
            this.browseButton.setEnabled(manual);
        });
    }

    /** 容器换了以后，只保留这个容器支持的编码。 */
    private void refreshCodecs() {
        Targets.Target target = (Targets.Target) this.formatBox.getSelectedItem();
        if (this.mode == Mode.IMAGE || target == null) {
            return;
        }
        String extension = target.extension();
        Object previous = this.codecBox.getSelectedItem();
        this.codecBox.removeAllItems();
        if (this.mode == Mode.VIDEO) {
            // GIF 只能重编码，不给「原样复制」这个选项
            if (!"gif".equals(extension)) {
                this.codecBox.addItem(VideoCatalog.COPY);
            }
            for (VideoCatalog.Codec codec : VideoCatalog.container(extension).codecs()) {
                this.codecBox.addItem(codec);
            }
        } else {
            this.codecBox.addItem(COPY_AUDIO);
            List<AudioCatalog.Codec> codecs = AudioCatalog.codecs(extension);
            if (codecs == null) {
                this.codecBox.addItem(new AudioCatalog.Codec("default", "默认编码（FFmpeg 自动）", java.util.List.of()));
            } else {
                for (AudioCatalog.Codec codec : codecs) {
                    this.codecBox.addItem(codec);
                }
            }
        }
        this.codecBox.setEnabled(this.codecBox.getItemCount() > 1 && !this.running);
        if (previous != null) {
            String oldId = previous instanceof VideoCatalog.Codec video ? video.id()
                    : previous instanceof AudioCatalog.Codec audio ? audio.id() : "";
            for (int i = 0; i < this.codecBox.getItemCount(); i++) {
                Object item = this.codecBox.getItemAt(i);
                String id = item instanceof VideoCatalog.Codec video ? video.id()
                        : item instanceof AudioCatalog.Codec audio ? audio.id() : "";
                if (!id.isEmpty() && id.equals(oldId)) {
                    this.codecBox.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    /** 提示行只放得下两行，这里按长度截断。 */
    private static String brief(String text, int limit) {
        if (text == null) {
            return "";
        }
        String trimmed = text.replace("。", "").trim();
        int cut = trimmed.indexOf('，');
        if (cut > 4 && cut < limit) {
            trimmed = trimmed.substring(0, cut);
        }
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit) + "…";
    }
    private void updateOptionControls() {
        Targets.Target target = (Targets.Target) this.formatBox.getSelectedItem();
        boolean qualityEnabled = target != null && Targets.usesQuality(this.mode, target) && !this.copyPreferred();
        String hint;
        switch (this.mode) {
            case IMAGE -> {
                String extension = target == null ? "png" : target.extension();
                this.qualityLabel.setText(qualityEnabled ? "画质（JPG / WEBP）" : "画质（当前格式不使用）");
                ImageCatalog.Info info = ImageCatalog.of(extension);
                hint = brief(info.pros(), 22) + "\n缺点：" + brief(info.cons(), 18);
            }
            case VIDEO -> {
                String extension = target == null ? "mp4" : target.extension();
                VideoCatalog.Container container = VideoCatalog.container(extension);
                VideoCatalog.Codec codec = (VideoCatalog.Codec) this.codecBox.getSelectedItem();
                boolean gif = "gif".equals(container.extension());
                this.qualityLabel.setText(gif ? "质量（GIF 不使用）" : "质量");
                hint = container.label().split(" · ")[0] + "："
                        + brief(container.pros(), 18) + "\n缺点：" + brief(container.cons(), 16)
                        + (codec == null ? "" : "\n" + codec.tag() + "：" + brief(codec.pros(), 20));
                if (this.copyPreferred()) {
                    hint = "原样复制：容器装得下源编码时只换壳、不重编码（秒级）\n"
                            + "装不下或改过分辨率时，自动退回重编码";
                }
            }
            default -> {
                String ext = target == null ? "mp3" : target.extension();
                boolean mp3 = "mp3".equals(ext);
                this.qualityLabel.setText(mp3 ? "音质（MP3 生效）" : "音质（当前编码不生效）");
                AudioCatalog.Info info = AudioCatalog.info(ext);
                hint = brief(info.pros(), 22) + "\n缺点：" + brief(info.cons(), 18);
                if (this.copyPreferred()) {
                    hint = "原样复制：源音频装得进这个容器就直接封装，不重编码\n"
                            + "装不下（例如 FLAC 音轨进 MP4）时自动换成容器默认编码";
                }
            }
        }
        this.qualityBox.setEnabled(qualityEnabled && !this.running);
        this.qualityBox.setForeground(qualityEnabled ? UiKit.TEXT : UiKit.MUTED);
        this.hintLabel.setText(UiKit.wrapHtml(hint == null ? "" : hint, 20));
    }

    private void startConversion() {
        if (this.running) {
            return;
        }
        if (this.items.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先添加需要转换的文件。", "还没有文件", JOptionPane.WARNING_MESSAGE);
            return;
        }
        MediaEngine.Settings settings = this.buildSettings();
        if (settings == null) {
            return;
        }
        this.running = true;
        this.setControlsRunning(true);
        this.engine.convertAll(this.items, settings, item -> SwingUtilities.invokeLater(() -> {
            int index = this.items.indexOf(item);
            if (index >= 0) {
                this.tableModel.fireTableRowsUpdated(index, index);
            }
            this.refreshSummary();
        }), () -> SwingUtilities.invokeLater(() -> {
            this.running = false;
            this.setControlsRunning(false);
            this.refreshSummary();
            Toolkit.getDefaultToolkit().beep();
        }));
    }

    private void setControlsRunning(boolean busy) {
        this.startButton.setEnabled(!busy);
        this.cancelButton.setVisible(busy);
        this.clearButton.setEnabled(!busy);
        this.removeButton.setEnabled(!busy);
        this.importButton.setEnabled(!busy);
        this.formatBox.setEnabled(!busy);
        this.codecBox.setEnabled(!busy);
        this.sizeBox.setEnabled(!busy);
        this.qualityBox.setEnabled(!busy && this.qualityEnabled());
        this.codecBox.setEnabled(!busy && this.codecBox.getItemCount() > 1);
        this.concurrencySpinner.setEnabled(!busy);
        this.sourceDirectory.setEnabled(!busy);
        this.outputField.setEnabled(!busy && !this.sourceDirectory.isSelected());
        this.browseButton.setEnabled(!busy && !this.sourceDirectory.isSelected());
        this.overallProgress.setVisible(busy);
        if (busy) {
            this.overallProgress.setValue(0);
            this.summaryLabel.setText("正在本地处理… 0%");
            SwingUtilities.invokeLater(() -> this.table.requestFocusInWindow());
        }
    }

    /** 选了「原样复制」时，画质/音质这些编码参数没意义。 */
    private boolean copyPreferred() {
        Object selected = this.codecBox.getSelectedItem();
        return selected instanceof VideoCatalog.Codec video ? "copy".equals(video.id())
                : selected instanceof AudioCatalog.Codec audio && "copy".equals(audio.id());
    }

    private boolean qualityEnabled() {
        Targets.Target target = (Targets.Target) this.formatBox.getSelectedItem();
        return target != null && Targets.usesQuality(this.mode, target) && !this.copyPreferred();
    }

    private MediaEngine.Settings buildSettings() {
        Path output = this.resolveOutputDirectory();
        if (!this.sourceDirectory.isSelected() && output == null) {
            return null;
        }
        String extension;
        List<String> args;
        String filter = null;
        boolean image = this.mode == Mode.IMAGE;
        String codecPreference = "";
        List<String> videoCopyAudioArgs = List.of();
        boolean hardwareEncode = false;
        List<String> fallbackArgs = List.of();
        Targets.Target target = (Targets.Target) this.formatBox.getSelectedItem();
        if (target == null) {
            return null;
        }

        switch (this.mode) {
            case IMAGE -> {
                ImageQuality quality = (ImageQuality) this.qualityBox.getSelectedItem();
                ImageScale scale = (ImageScale) this.sizeBox.getSelectedItem();
                extension = target.dotted();
                args = Targets.imageArgs(target, quality);
                filter = scale == null ? null : scale.filter();
            }
            case VIDEO -> {
                VideoQuality quality = (VideoQuality) this.qualityBox.getSelectedItem();
                VideoResolution resolution = (VideoResolution) this.sizeBox.getSelectedItem();
                VideoCatalog.Container container = VideoCatalog.container(target.extension());
                VideoCatalog.Codec codec = (VideoCatalog.Codec) this.codecBox.getSelectedItem();
                if (codec == null) {
                    codec = VideoCatalog.COPY;
                }
                extension = target.dotted();
                // 选「原样复制」时，这里的 args 只是容器装不下时的兜底重编码参数
                List<String> cpuArgs = VideoCatalog.args(container, codec, quality);
                args = cpuArgs;
                codecPreference = codec.id();
                videoCopyAudioArgs = VideoCatalog.audioArgsList(container);
                // 顶上开了显卡、而且显卡支持这个编码，就换硬件编码器，失败再退回 CPU
                String codecName = FastPath.videoName(codec.id());
                GpuSupport.Gpu gpu = GpuSupport.current();
                if (this.useGpu && !"copy".equals(codec.id()) && GpuSupport.supports(gpu, codecName)) {
                    List<String> gpuArgs = GpuSupport.videoArgs(gpu, codecName, container.extension(), quality);
                    if (!gpuArgs.isEmpty()) {
                        args = gpuArgs;
                        hardwareEncode = true;
                        fallbackArgs = cpuArgs;
                    }
                }
                List<String> filters = new ArrayList<>();
                if ("gif".equals(container.extension())) {
                    filters.add("fps=12");
                }
                if (resolution != null && resolution.filter() != null) {
                    filters.add(resolution.filter());
                }
                filter = filters.isEmpty() ? null : String.join(",", filters);
            }
            default -> {
                AudioQuality quality = (AudioQuality) this.qualityBox.getSelectedItem();
                extension = target.dotted();
                Object selected = this.codecBox.getSelectedItem();
                if (selected instanceof AudioCatalog.Codec codec) {
                    codecPreference = codec.id();
                }
                if (selected instanceof AudioCatalog.Codec codec
                        && !"default".equals(codec.id()) && !"copy".equals(codec.id())) {
                    args = new ArrayList<>(codec.args());
                } else {
                    args = Targets.audioArgs(target, quality);
                }
            }
        }
        return new MediaEngine.Settings(output, this.sourceDirectory.isSelected(),
                (Integer) this.concurrencySpinner.getValue(), this.mode, extension, args, filter,
                image, this.mode == Mode.AUDIO, codecPreference, videoCopyAudioArgs,
                hardwareEncode, fallbackArgs);
    }

    private Path resolveOutputDirectory() {
        String text = this.outputField.getText().trim();
        if (text.isEmpty()) {
            this.outputField.setText(this.outputDirectory.toString());
            return this.outputDirectory;
        }
        try {
            return Path.of(text).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            JOptionPane.showMessageDialog(this, "输出路径无效，请重新选择。", "路径错误", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private void chooseFiles() {
        if (this.running) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择" + this.mode.shortName() + "文件");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setFileFilter(new FileNameExtensionFilter("支持的" + this.mode.shortName() + "文件", this.sortedExtensions()));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            this.addPaths(Arrays.stream(chooser.getSelectedFiles()).map(File::toPath).toList());
        }
    }

    private String[] sortedExtensions() {
        return this.mode.extensions.stream().sorted().toArray(String[]::new);
    }

    private void chooseOutputDirectory() {
        String current = this.outputField.getText().trim();
        File start = current.isEmpty() ? null : new File(current);
        if (start == null || !start.isDirectory()) {
            start = new File(System.getProperty("user.home", "."));
        }
        try {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("选择输出文件夹");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setCurrentDirectory(start);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                this.outputField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        } catch (RuntimeException exception) {
            // 某些网络盘/虚拟盘会让文件对话框抛异常，改用手动输入
            JOptionPane.showMessageDialog(this,
                    "系统文件对话框打开失败，请直接在“保存位置”输入框里填写路径后按回车。\n" + exception.getMessage(),
                    "请手动输入路径", JOptionPane.WARNING_MESSAGE);
            this.outputField.requestFocusInWindow();
            this.outputField.selectAll();
        }
    }

    void addPaths(List<Path> paths) {
        this.addPaths(paths, false);
    }

    /**
     * @param autoRouted 这批文件是别的板块自动转过来的，不再往回路由，避免来回踢皮球
     */
    void addPaths(List<Path> paths, boolean autoRouted) {
        if (this.running) {
            return;
        }
        HashSet<Path> known = new HashSet<>();
        for (TransferItem item : this.items) {
            known.add(item.source.toAbsolutePath().normalize());
        }
        List<Path> accepted = new ArrayList<>();
        List<String> rejectedLabels = new ArrayList<>();
        // 认出来属于别的板块的文件：板块 → 文件
        Map<Mode, List<Path>> elsewhere = new LinkedHashMap<>();
        List<String> elsewhereNotes = new ArrayList<>();
        for (Path path : paths) {
            try {
                if (Files.isDirectory(path, new LinkOption[0])) {
                    try (Stream<Path> stream = Files.walk(path)) {
                        stream.filter(candidate -> Files.isRegularFile(candidate, new LinkOption[0]))
                                .filter(this::supported)
                                .forEach(accepted::add);
                    }
                    continue;
                }
                if (!Files.isRegularFile(path, new LinkOption[0])) {
                    continue;
                }
                if (this.supported(path) || this.readableForMode(path)) {
                    accepted.add(path);
                    continue;
                }
                // 当前板块不收：先认一认它到底是什么
                MediaProbe.Info info = MediaProbe.inspect(this.engine.ffmpegPath(), path);
                Mode board = info.board();
                if (!autoRouted && this.router != null && board != null && board != this.mode) {
                    elsewhere.computeIfAbsent(board, key -> new ArrayList<>()).add(path);
                    elsewhereNotes.add(MediaProbe.describe(path, info));
                    continue;
                }
                String extension = MediaEngine.extension(path);
                String label = extension.isEmpty() ? path.getFileName().toString() : "." + extension;
                String line = label + "（" + info.summary() + "）";
                if (!rejectedLabels.contains(line)) {
                    rejectedLabels.add(line);
                }
            } catch (Exception ignored) {
                // 单个路径失败不影响其他文件
            }
        }
        List<TransferItem> added = new ArrayList<>();
        for (Path path : accepted) {
            Path normalized = path.toAbsolutePath().normalize();
            if (known.add(normalized)) {
                TransferItem item = new TransferItem(normalized);
                this.items.add(item);
                added.add(item);
            }
        }
        this.tableModel.fireTableDataChanged();
        this.refreshSummary();
        this.describeInputs(added);

        // 认出来是别的板块的文件 → 交给主窗口切板块并加进去
        if (!elsewhere.isEmpty()) {
            Mode target = elsewhere.keySet().iterator().next();
            List<Path> targets = new ArrayList<>();
            for (List<Path> group : elsewhere.values()) {
                targets.addAll(group);
            }
            StringBuilder message = new StringBuilder();
            message.append("这些文件不是“").append(this.mode.shortName()).append("”，自动帮你去对板块：\n\n");
            int show = Math.min(5, elsewhereNotes.size());
            for (int index = 0; index < show; index++) {
                message.append("  · ").append(elsewhereNotes.get(index)).append('\n');
            }
            if (elsewhereNotes.size() > show) {
                message.append("  … 等 ").append(elsewhereNotes.size()).append(" 个\n");
            }
            message.append("\n已经切到「").append(target.title).append("」并加入队列。");
            this.router.route(target, targets, message.toString());
        }
        // 剩下的确实用不了：说清楚它是什么，别只甩一句“格式不支持”
        if (accepted.isEmpty() && elsewhere.isEmpty() && !rejectedLabels.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    String.join("\n", rejectedLabels) + "\n\n" + this.mode.supportedHint(),
                    "这些文件“" + this.mode.shortName() + "”用不了", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** 后台认一遍刚加进来的文件，把“等待转换”换成「视频 · 1920×1080 · 03:21 · H.264 + AAC」。 */
    private void describeInputs(List<TransferItem> added) {
        if (added.isEmpty() || this.engine.ffmpegPath() == null) {
            return;
        }
        Thread thread = new Thread(() -> {
            for (TransferItem item : added) {
                if (item.state != TransferItem.State.WAITING) {
                    continue;
                }
                MediaProbe.Info info = MediaProbe.inspect(this.engine.ffmpegPath(), item.source);
                String text = info.summary();
                if (this.mode == Mode.AUDIO && info.kind() == MediaProbe.Kind.VIDEO) {
                    text += " · 会抽音轨";
                }
                if (item.state != TransferItem.State.WAITING) {
                    continue;
                }
                item.detail = text;
                int index = this.items.indexOf(item);
                if (index >= 0) {
                    SwingUtilities.invokeLater(() -> this.tableModel.fireTableRowsUpdated(index, index));
                }
            }
        }, "probe-inputs");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean supported(Path path) {
        return this.mode.supports(MediaEngine.extension(path));
    }

    /** 扩展名没收录时，现场用 ffmpeg 探测：只要 FFmpeg 能解出对应流就接纳。 */
    private boolean readableForMode(Path path) {
        if (Formats.known(MediaEngine.extension(path))) {
            return false;
        }
        return MediaEngine.hasStream(this.engine.ffmpegPath(), path, this.mode == Mode.AUDIO);
    }

    private void refreshSummary() {
        long done = this.items.stream().filter(item -> item.state == TransferItem.State.DONE).count();
        long failed = this.items.stream().filter(item -> item.state == TransferItem.State.FAILED).count();
        this.countLabel.setText(this.items.size() + " 个文件");

        int total = 0;
        for (TransferItem item : this.items) {
            total += Math.max(0, Math.min(100, item.progress));
        }
        int average = this.items.isEmpty() ? 0 : total / this.items.size();
        this.overallProgress.setValue(average);

        if (this.items.isEmpty()) {
            this.summaryLabel.setText("拖入文件即可开始");
        } else if (this.running) {
            this.summaryLabel.setText("已完成 " + done + " / " + this.items.size() + " · " + average + "%");
        } else if (done > 0 || failed > 0) {
            this.summaryLabel.setText("完成 " + done + " · 失败 " + failed);
        } else {
            this.summaryLabel.setText("已就绪 " + this.items.size() + " 个文件");
        }
        this.updateActionState();
    }

    private void updateActionState() {
        boolean hasFiles = !this.items.isEmpty();
        this.startButton.setEnabled(!this.running && hasFiles);
        this.clearButton.setEnabled(!this.running && hasFiles);
        this.removeButton.setEnabled(!this.running && this.table.getSelectedRowCount() > 0);
    }

    private final class QueueTableModel extends AbstractTableModel {
        private final String[] columns = new String[]{"文件", "大小", "状态", "进度"};

        @Override
        public int getRowCount() {
            return ConversionView.this.items.size();
        }

        @Override
        public int getColumnCount() {
            return this.columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return this.columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            TransferItem item = ConversionView.this.items.get(row);
            return switch (column) {
                case 0 -> item.fileName();
                case 1 -> MediaEngine.humanSize(item.size);
                case 2 -> item.stateText() + " · " + item.detail;
                case 3 -> item.progress;
                default -> "";
            };
        }
    }

    private final class FileDropListener extends DropTargetAdapter {
        @Override
        public void drop(DropTargetDropEvent event) {
            try {
                event.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY);
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                ConversionView.this.addPaths(files.stream().map(File::toPath).toList());
                event.dropComplete(true);
            } catch (Exception exception) {
                event.dropComplete(false);
            }
        }
    }
}
