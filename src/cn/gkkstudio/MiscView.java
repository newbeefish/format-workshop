package cn.gkkstudio;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JCheckBox;
import javax.swing.JPasswordField;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

/** 杂项板块：ZIP / TAR.GZ 打包解包、PDF 拆图、图片合成 PDF。 */
final class MiscView extends JPanel {

    private static final int MAX_CONCURRENCY = Math.max(1, Runtime.getRuntime().availableProcessors());

    private final Path rootDirectory;
    private final List<TransferItem> items = new ArrayList<>();
    private final MiscEngine engine;
    private boolean running;

    private final JComboBox<MiscAction> actionBox = new JComboBox<>(MiscAction.values());
    private final JComboBox<String> imageFormatBox = new JComboBox<>(new String[]{"png", "jpg"});
    private final JLabel imageFormatLabel = UiKit.inspectorLabel("输出图片格式（PDF 拆图）");
    private final JTextField paramField = new JTextField();
    private final JLabel paramLabel = UiKit.inspectorLabel("参数（二维码内容）");
    private final JComboBox<String> archiveFormatBox = new JComboBox<>(new String[]{"zip", "tar.gz", "7z", "jar", "tar.xz"});
    private final JLabel archiveFormatLabel = UiKit.inspectorLabel("目标压缩格式");
    private final JTextField keystoreField = new JTextField();
    private final JTextField aliasField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final JCheckBox v1Box = new JCheckBox("v1（JAR）", true);
    private final JCheckBox v2Box = new JCheckBox("v2", true);
    private final JCheckBox v3Box = new JCheckBox("v3", true);
    private final JCheckBox zipAlignBox = new JCheckBox("先 zipalign 对齐", true);
    private JPanel apkPanel;
    private final JTextField passwordStartField = new JTextField("1");
    private final JTextField passwordEndField = new JTextField("9999");
    private final JTextField passwordPrefixField = new JTextField();
    private final JTextField passwordSuffixField = new JTextField();
    private final JTextField passwordWidthField = new JTextField("4");
    private JPanel passwordPanel;
    private final JTextField outputField = new JTextField();
    private final JSpinner concurrencySpinner = new JSpinner(new SpinnerNumberModel(
            Math.min(MAX_CONCURRENCY, 4), 1, MAX_CONCURRENCY, 1));
    private final JLabel hintLabel = new JLabel(" ");
    private final JLabel summaryLabel = new JLabel("准备就绪");
    private final JButton startButton = UiKit.primaryButton("开始处理");
    private final JButton cancelButton = UiKit.secondaryButton("取消");
    private final JButton addButton = UiKit.secondaryButton("添加文件…");
    private final JButton addFolderButton = UiKit.secondaryButton("添加文件夹…");
    private final JButton removeButton = UiKit.secondaryButton("移除选中");
    private final JButton clearButton = UiKit.secondaryButton("清空列表");
    private final JTable table;
    private final DefaultTableModel tableModel;

    MiscView(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
        this.engine = new MiscEngine(rootDirectory.resolve("logs"));
        this.outputField.setText(rootDirectory.resolve("output").toString());

        this.tableModel = new DefaultTableModel(new Object[]{"文件", "大小", "状态", "进度"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        this.table = new JTable(this.tableModel);
        this.table.setRowHeight(38);
        this.table.setShowGrid(false);
        this.table.setBackground(Color.WHITE);
        this.table.getTableHeader().setReorderingAllowed(false);
        this.table.setDefaultRenderer(Object.class, new MiscCellRenderer());
        this.table.getColumnModel().getColumn(3).setCellRenderer(new UiKit.ProgressRenderer());
        this.table.getColumnModel().getColumn(0).setPreferredWidth(250);
        this.table.getColumnModel().getColumn(1).setPreferredWidth(85);
        this.table.getColumnModel().getColumn(2).setPreferredWidth(330);
        this.table.getColumnModel().getColumn(3).setPreferredWidth(110);

        this.setLayout(new BorderLayout(24, 0));
        this.setOpaque(false);
        this.add(this.buildMain(), BorderLayout.CENTER);
        this.add(this.buildSettings(), BorderLayout.EAST);
        this.wireActions();
        this.filterCategory("压缩打包");
        this.setTransferHandler(new DropHandler());
    }

    // ------------------------------------------------------------------ UI

    private JComponent buildMain() {
        JPanel panel = new JPanel(new BorderLayout(0, 18));
        panel.setOpaque(false);

        UiKit.RoundedPanel drop = new UiKit.RoundedPanel(Color.WHITE, 22);
        drop.setLayout(new BorderLayout());
        drop.setBorder(BorderFactory.createEmptyBorder(28, 24, 28, 24));
        JLabel dropTitle = new JLabel("添加要处理的文件或文件夹", JLabel.CENTER);
        dropTitle.setFont(dropTitle.getFont().deriveFont(Font.BOLD, 19.0f));
        dropTitle.setForeground(UiKit.TEXT);
        JLabel dropHint = new JLabel("拖进来，或点下面的按钮选择", JLabel.CENTER);
        dropHint.setForeground(UiKit.MUTED);
        JPanel dropCenter = new JPanel();
        dropCenter.setOpaque(false);
        dropCenter.setLayout(new BoxLayout(dropCenter, BoxLayout.Y_AXIS));
        dropTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        dropHint.setAlignmentX(Component.CENTER_ALIGNMENT);
        this.addButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        this.addFolderButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        dropCenter.add(Box.createVerticalGlue());
        dropCenter.add(dropTitle);
        dropCenter.add(Box.createVerticalStrut(8));
        dropCenter.add(dropHint);
        dropCenter.add(Box.createVerticalStrut(14));
        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.add(this.addButton);
        buttons.add(this.addFolderButton);
        buttons.setAlignmentX(Component.CENTER_ALIGNMENT);
        dropCenter.add(buttons);
        dropCenter.add(Box.createVerticalGlue());
        drop.add(dropCenter, BorderLayout.CENTER);
        drop.setPreferredSize(new Dimension(0, 210));
        panel.add(drop, BorderLayout.CENTER);

        JPanel listPanel = new JPanel(new BorderLayout(0, 10));
        listPanel.setOpaque(false);
        JPanel listHeader = new JPanel(new BorderLayout());
        listHeader.setOpaque(false);
        this.summaryLabel.setForeground(UiKit.TEXT);
        listHeader.add(this.summaryLabel, BorderLayout.WEST);
        JPanel listButtons = new JPanel();
        listButtons.setOpaque(false);
        listButtons.add(this.removeButton);
        listButtons.add(this.clearButton);
        listHeader.add(listButtons, BorderLayout.EAST);
        listPanel.add(listHeader, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(this.table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Color.WHITE);
        listPanel.add(scroll, BorderLayout.CENTER);
        panel.add(listPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildSettings() {
        UiKit.RoundedPanel panel = new UiKit.RoundedPanel(UiKit.SETTINGS, 28);
        panel.setLayout(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 18, 20));
        panel.setPreferredSize(new Dimension(308, 0));

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("处理设置");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18.0f));
        title.setForeground(UiKit.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(title);
        body.add(Box.createVerticalStrut(8));

        JPanel categories = new JPanel(new java.awt.GridLayout(2, 2, 6, 6));
        categories.setOpaque(false);
        categories.setAlignmentX(Component.LEFT_ALIGNMENT);
        categories.setMaximumSize(new Dimension(252, 64));
        categories.setPreferredSize(new Dimension(252, 64));
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        String[] categoryNames = {"压缩打包", "文档与图片", "校验与文本", "开发工具"};
        for (int index = 0; index < categoryNames.length; index++) {
            javax.swing.JToggleButton button = new javax.swing.JToggleButton(categoryNames[index]);
            button.setFont(button.getFont().deriveFont(java.awt.Font.PLAIN, 12.0f));
            button.setMargin(new java.awt.Insets(2, 2, 2, 2));
            button.setPreferredSize(new Dimension(120, 28));
            button.setMaximumSize(new Dimension(120, 28));
            button.setMinimumSize(new Dimension(120, 28));
            button.setFocusPainted(false);
            button.setSelected(index == 0);
            String category = categoryNames[index];
            button.addActionListener(event -> {
                if (button.isSelected()) {
                    this.filterCategory(category);
                }
            });
            group.add(button);
            categories.add(button);
        }
        body.add(categories);
        body.add(Box.createVerticalStrut(11));

        body.add(UiKit.inspectorLabel("操作"));
        body.add(Box.createVerticalStrut(7));
        this.actionBox.setMaximumSize(new Dimension(228, 34));
        this.actionBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.actionBox);
        body.add(Box.createVerticalStrut(9));

        this.archiveFormatLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.archiveFormatLabel);
        body.add(Box.createVerticalStrut(7));
        this.archiveFormatBox.setMaximumSize(new Dimension(228, 34));
        this.archiveFormatBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.archiveFormatBox);
        body.add(Box.createVerticalStrut(9));

        this.paramLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.paramLabel);
        body.add(Box.createVerticalStrut(7));
        this.paramField.setMaximumSize(new Dimension(228, 34));
        this.paramField.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.paramField);
        body.add(Box.createVerticalStrut(9));

        this.imageFormatLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.imageFormatLabel);
        body.add(Box.createVerticalStrut(7));
        this.imageFormatBox.setMaximumSize(new Dimension(228, 34));
        this.imageFormatBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.imageFormatBox);
        body.add(Box.createVerticalStrut(9));

        this.apkPanel = new JPanel();
        this.apkPanel.setOpaque(false);
        this.apkPanel.setLayout(new BoxLayout(this.apkPanel, BoxLayout.Y_AXIS));
        this.apkPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        this.apkPanel.setVisible(false);
        JPanel ksRow = new JPanel(new BorderLayout(6, 0));
        ksRow.setOpaque(false);
        ksRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension ksSize = new Dimension(268, 34);
        ksRow.setPreferredSize(ksSize);
        ksRow.setMinimumSize(ksSize);
        ksRow.setMaximumSize(ksSize);
        JLabel ksLabel = formLabel("keystore", "签名用的 .jks / .keystore 文件");
        ksLabel.setPreferredSize(new Dimension(62, 24));
        ksRow.add(ksLabel, BorderLayout.WEST);
        this.keystoreField.setToolTipText("签名用的 .jks / .keystore 文件");
        ksRow.add(this.keystoreField, BorderLayout.CENTER);
        JButton ksBrowse = UiKit.secondaryButton("…");
        ksBrowse.setToolTipText("选择 keystore 文件");
        ksBrowse.addActionListener(event -> this.chooseKeystore());
        ksRow.add(ksBrowse, BorderLayout.EAST);
        this.apkPanel.add(ksRow);
        this.apkPanel.add(Box.createVerticalStrut(4));
        this.apkPanel.add(this.formRow("别名", this.aliasField, "keystore 里的 key alias", 62));
        this.apkPanel.add(Box.createVerticalStrut(4));
        this.apkPanel.add(this.formRow("密码", this.passwordField, "keystore 密码", 62));
        this.apkPanel.add(Box.createVerticalStrut(5));
        JPanel flags = new JPanel(new java.awt.GridLayout(1, 4, 2, 0));
        flags.setOpaque(false);
        flags.setAlignmentX(Component.LEFT_ALIGNMENT);
        flags.setMaximumSize(new Dimension(268, 26));
        this.v1Box.setText("v1");
        this.v1Box.setToolTipText("v1 签名（JAR 签名，老系统靠它）");
        this.v1Box.setFont(this.v1Box.getFont().deriveFont(12.0f));
        this.zipAlignBox.setText("对齐");
        this.zipAlignBox.setToolTipText("签名前先用 zipalign 做 4 字节对齐");
        this.zipAlignBox.setFont(this.zipAlignBox.getFont().deriveFont(12.0f));
        this.v2Box.setFont(this.v2Box.getFont().deriveFont(12.0f));
        this.v3Box.setFont(this.v3Box.getFont().deriveFont(12.0f));
        flags.add(this.v1Box);
        flags.add(this.v2Box);
        flags.add(this.v3Box);
        flags.add(this.zipAlignBox);
        this.apkPanel.add(flags);
        this.apkPanel.add(Box.createVerticalStrut(6));
        body.add(this.apkPanel);
        fixHeight(this.apkPanel);

        this.passwordPanel = this.buildPasswordPanel();
        body.add(this.passwordPanel);

        body.add(UiKit.inspectorLabel("保存位置"));
        body.add(Box.createVerticalStrut(7));
        this.outputField.setMaximumSize(new Dimension(228, 34));
        this.outputField.setPreferredSize(new Dimension(228, 34));
        this.outputField.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.outputField);
        body.add(Box.createVerticalStrut(7));
        JButton browse = UiKit.secondaryButton("更改文件夹…");
        browse.setAlignmentX(Component.LEFT_ALIGNMENT);
        browse.addActionListener(event -> this.chooseOutput());
        body.add(browse);
        body.add(Box.createVerticalStrut(7));

        JPanel concurrencyRow = new JPanel(new BorderLayout(10, 0));
        concurrencyRow.setOpaque(false);
        concurrencyRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        concurrencyRow.setMaximumSize(new Dimension(268, 30));
        concurrencyRow.add(this.formLabel("同时处理", "同时干几件事；试密码时就是并行验证的线程数"),
                BorderLayout.WEST);
        this.concurrencySpinner.setPreferredSize(new Dimension(78, 26));
        concurrencyRow.add(this.concurrencySpinner, BorderLayout.EAST);
        body.add(concurrencyRow);

        this.hintLabel.setForeground(UiKit.MUTED);
        this.hintLabel.setFont(this.hintLabel.getFont().deriveFont(12.0f));
        this.hintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(Box.createVerticalStrut(8));
        body.add(this.hintLabel);

        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        this.startButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        this.cancelButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        this.cancelButton.setVisible(false);
        this.startButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        this.cancelButton.setMaximumSize(new Dimension(228, 34));
        bottom.add(this.startButton);
        bottom.add(Box.createVerticalStrut(6));
        bottom.add(this.cancelButton);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    /** 「试 ZPAQ 密码」用的输入框：数字范围 + 前后缀 + 补零位数，挤成四行。 */
    private JPanel buildPasswordPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setVisible(false);

        panel.add(formLabel("猜密码的数字范围", "只在起始～结束之间试，两端都算"));
        panel.add(Box.createVerticalStrut(4));
        panel.add(this.passwordRow("数字", this.buildPasswordRange(), "起始～结束，两端都算；只试数字"));
        panel.add(this.passwordRow("前缀", this.passwordPrefixField, "密码里数字前面的固定内容，可不填"));
        panel.add(this.passwordRow("后缀", this.passwordSuffixField, "密码里数字后面的固定内容，可不填"));
        panel.add(this.passwordRow("位数", this.buildPasswordWidth(), "数字不够前面补 0；填 0 表示不补"));
        return panel;
    }

    /**
     * BoxLayout 按 maximumSize 分配高度，而 JPanel 的默认最大值就等于当时的偏好值，
     * 右栏一挤就会把最后一行压成一条缝。这里把高度显式钉住。
     */
    private static void fixHeight(JComponent panel) {
        Dimension preferred = panel.getPreferredSize();
        panel.setMinimumSize(preferred);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
    }

    /** 表单左侧的小标题。 */
    private JLabel formLabel(String text, String tip) {
        JLabel label = new JLabel(text);
        label.setForeground(UiKit.TEXT);
        label.setFont(label.getFont().deriveFont(13.0f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setToolTipText(tip);
        return label;
    }

    /** 一行「小标题 + 控件」，整行 34 高（含 4px 间距），几个控件要挤在一个屏幕里。 */
    private JComponent passwordRow(String label, JComponent field, String tip) {
        return this.formRow(label, field, tip, 30);
    }

    private JComponent formRow(String label, JComponent field, String tip, int labelWidth) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        // 三个尺寸都写死：不然 JTextField 的偏好高度会随布局时机变化，整行会被压扁
        Dimension size = new Dimension(268, 34);
        row.setPreferredSize(size);
        row.setMinimumSize(size);
        row.setMaximumSize(size);
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        JLabel left = this.formLabel(label, tip);
        left.setFont(left.getFont().deriveFont(12.0f));
        left.setPreferredSize(new Dimension(labelWidth, 22));
        row.add(left, BorderLayout.WEST);
        field.setToolTipText(tip);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    /** 起始 ～ 结束 两个框并排。 */
    private JComponent buildPasswordRange() {
        JPanel row = new JPanel(new java.awt.GridBagLayout());
        row.setOpaque(false);
        java.awt.GridBagConstraints constraints = new java.awt.GridBagConstraints();
        constraints.gridy = 0;
        constraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        constraints.gridx = 0;
        constraints.weightx = 1;
        row.add(this.passwordStartField, constraints);
        constraints.gridx = 1;
        constraints.weightx = 0;
        constraints.insets = new java.awt.Insets(0, 6, 0, 6);
        JLabel tilde = new JLabel("～");
        tilde.setForeground(UiKit.MUTED);
        row.add(tilde, constraints);
        constraints.gridx = 2;
        constraints.weightx = 1;
        constraints.insets = new java.awt.Insets(0, 0, 0, 0);
        row.add(this.passwordEndField, constraints);
        return row;
    }

    /** 补零位数只要窄窄一条。 */
    private JComponent buildPasswordWidth() {
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        this.passwordWidthField.setPreferredSize(new Dimension(70, 24));
        row.add(this.passwordWidthField);
        return row;
    }

    private void wireActions() {
        this.addButton.addActionListener(event -> this.chooseFiles(false));
        this.addFolderButton.addActionListener(event -> this.chooseFiles(true));
        this.clearButton.addActionListener(event -> {
            if (!this.running) {
                this.items.clear();
                this.refresh();
            }
        });
        this.removeButton.addActionListener(event -> {
            if (this.running) {
                return;
            }
            int[] rows = this.table.getSelectedRows();
            for (int index = rows.length - 1; index >= 0; index--) {
                this.items.remove(rows[index]);
            }
            this.refresh();
        });
        this.startButton.addActionListener(event -> this.start());
        this.cancelButton.addActionListener(event -> {
            this.engine.cancel(this.items);
            this.summaryLabel.setText("正在取消…");
        });
        this.actionBox.addActionListener(event -> this.updateHint());
    }

    /** 只保留该分类下的操作。 */
    private void filterCategory(String category) {
        if (this.running) {
            return;
        }
        this.actionBox.removeAllItems();
        for (MiscAction action : MiscAction.values()) {
            if (action.category().equals(category)) {
                this.actionBox.addItem(action);
            }
        }
        if (this.actionBox.getItemCount() == 0) {
            JOptionPane.showMessageDialog(this, "“" + category + "”还没有可用的操作。", "该板块建设中", JOptionPane.INFORMATION_MESSAGE);
            this.actionBox.removeAllItems();
            for (MiscAction action : MiscAction.values()) {
                this.actionBox.addItem(action);
            }
        }
        this.updateHint();
    }

    private void updateHint() {
        MiscAction action = (MiscAction) this.actionBox.getSelectedItem();
        if (action == null) {
            return;
        }
        boolean needParam = action == MiscAction.QR_GENERATE;
        this.paramLabel.setVisible(needParam);
        this.paramField.setVisible(needParam);
        this.imageFormatLabel.setVisible(action == MiscAction.PDF_TO_IMG);
        boolean archiveConvert = action == MiscAction.ARCHIVE_CONVERT;
        this.archiveFormatLabel.setVisible(archiveConvert);
        this.archiveFormatBox.setVisible(archiveConvert);
        if (this.apkPanel != null) {
            this.apkPanel.setVisible(action == MiscAction.APK_SIGN);
        }
        if (this.passwordPanel != null) {
            this.passwordPanel.setVisible(action == MiscAction.ZPAQ_TEST_PASSWORD);
        }
        this.revalidate();
        this.repaint();
        this.imageFormatBox.setVisible(action == MiscAction.PDF_TO_IMG);
        this.imageFormatBox.setEnabled(action == MiscAction.PDF_TO_IMG && !this.running);
        this.hintLabel.setText("<html><div style='width:232px'>" + action.hint + "<br>" + action.detail + "</div></html>");
    }

    private void chooseFiles(boolean folder) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(folder ? "选择文件夹" : "选择文件");
        chooser.setFileSelectionMode(folder ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            List<Path> paths = new ArrayList<>();
            for (File file : chooser.getSelectedFiles()) {
                paths.add(file.toPath());
            }
            this.addPaths(paths);
        }
    }

    private void chooseKeystore() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 keystore（.jks / .keystore / .p12）");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            this.keystoreField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseOutput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            this.outputField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    void addPaths(List<Path> paths) {
        if (this.running) {
            return;
        }
        MiscAction action = (MiscAction) this.actionBox.getSelectedItem();
        List<String> rejected = new ArrayList<>();
        for (Path path : paths) {
            if (!Files.exists(path)) {
                continue;
            }
            if (action.supports(path) || Files.isDirectory(path)) {
                boolean known = this.items.stream().anyMatch(item -> item.source.equals(path));
                if (!known) {
                    this.items.add(new TransferItem(path));
                }
            } else {
                String name = path.getFileName().toString();
                if (!rejected.contains(name)) {
                    rejected.add(name);
                }
            }
        }
        this.refresh();
        if (this.items.isEmpty() && !rejected.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "“" + action.label + "”不支持这些文件：\n" + String.join("、", rejected),
                    "格式不支持", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ------------------------------------------------------------------ 执行

    private void start() {
        if (this.running) {
            return;
        }
        if (this.items.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先添加要处理的文件。", "还没有文件", JOptionPane.WARNING_MESSAGE);
            return;
        }
        MiscAction action = (MiscAction) this.actionBox.getSelectedItem();
        if (this.outputField.getText().isBlank()) {
            this.outputField.setText(this.rootDirectory.resolve("output").toString());
        }
        Path output;
        try {
            output = Path.of(this.outputField.getText().trim());
        } catch (Exception exception) {
            JOptionPane.showMessageDialog(this, "输出路径无效：" + exception.getMessage(), "路径错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (action == MiscAction.APK_SIGN) {
            String ks = this.keystoreField.getText().trim();
            this.engine.setApkOptions(ks.isEmpty() ? null : Path.of(ks), this.aliasField.getText(),
                    new String(this.passwordField.getPassword()),
                    this.v1Box.isSelected(), this.v2Box.isSelected(), this.v3Box.isSelected(),
                    this.zipAlignBox.isSelected());
        }
        if (action == MiscAction.QR_GENERATE) {
            this.engine.setTextParam(this.paramField.getText());
        }
        if (action == MiscAction.ARCHIVE_CONVERT) {
            this.engine.setArchiveTarget((String) this.archiveFormatBox.getSelectedItem());
        }
        if (action == MiscAction.ZPAQ_TEST_PASSWORD) {
            Long from = this.readNumber(this.passwordStartField, "起始数字");
            Long to = this.readNumber(this.passwordEndField, "结束数字");
            Long width = this.readNumber(this.passwordWidthField, "数字位数");
            if (from == null || to == null || width == null) {
                return;
            }
            if (from < 0 || to < 0 || width < 0) {
                JOptionPane.showMessageDialog(this, "这三个数都不能是负数。", "数字不对", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (from > to) {
                JOptionPane.showMessageDialog(this, "起始数要小于结束数。", "范围不对", JOptionPane.WARNING_MESSAGE);
                return;
            }
            this.engine.setPasswordRange(from, to, this.passwordPrefixField.getText(),
                    this.passwordSuffixField.getText(), width.intValue());
        }
        this.running = true;
        this.setBusy(true);
        this.engine.run(this.items, action, output, (String) this.imageFormatBox.getSelectedItem(),
                (Integer) this.concurrencySpinner.getValue(),
                item -> SwingUtilities.invokeLater(this::refresh),
                () -> SwingUtilities.invokeLater(() -> {
                    this.running = false;
                    this.setBusy(false);
                    this.refresh();
                    this.announcePasswords();
                }));
    }

    /** 跑完以后，如果把 ZPAQ 密码试出来了，单独弹一次提示，免得在表格里被截断。 */
    private void announcePasswords() {
        List<String> lines = new ArrayList<>();
        for (TransferItem item : this.items) {
            if (item.note != null && item.note.startsWith("已找回密码：")) {
                lines.add(item.fileName() + "  的密码是  " + item.note.substring("已找回密码：".length()));
            }
        }
        if (!lines.isEmpty()) {
            JOptionPane.showMessageDialog(this, String.join("\n", lines), "密码找回来了", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /** 读一个整数输入框，读不出时弹提示并返回 null。 */
    private Long readNumber(JTextField field, String name) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException exception) {
            JOptionPane.showMessageDialog(this, "“" + name + "”只能填数字：" + text, "数字不对", JOptionPane.WARNING_MESSAGE);
            return null;
        }
    }

    private void setBusy(boolean busy) {
        this.startButton.setEnabled(!busy);
        this.cancelButton.setVisible(busy);
        this.addButton.setEnabled(!busy);
        this.addFolderButton.setEnabled(!busy);
        this.clearButton.setEnabled(!busy);
        this.removeButton.setEnabled(!busy);
        this.actionBox.setEnabled(!busy);
        this.concurrencySpinner.setEnabled(!busy);
        this.imageFormatBox.setEnabled(!busy && this.actionBox.getSelectedItem() == MiscAction.PDF_TO_IMG);
        this.passwordStartField.setEnabled(!busy);
        this.passwordEndField.setEnabled(!busy);
        this.passwordPrefixField.setEnabled(!busy);
        this.passwordSuffixField.setEnabled(!busy);
        this.passwordWidthField.setEnabled(!busy);
    }

    private void refresh() {
        this.tableModel.setRowCount(0);
        for (TransferItem item : this.items) {
            this.tableModel.addRow(new Object[]{
                    item.fileName(),
                    item.size < 0 ? "—" : String.format("%.1f KB", item.size / 1024.0),
                    item.stateText() + " · " + item.detail,
                    item.progress});
        }
        long done = this.items.stream().filter(item -> item.state == TransferItem.State.DONE).count();
        long failed = this.items.stream().filter(item -> item.state == TransferItem.State.FAILED).count();
        this.summaryLabel.setText("待处理 " + this.items.size() + " 个 · 完成 " + done + " · 失败 " + failed);
    }

    MiscEngine engine() {
        return this.engine;
    }

    // ------------------------------------------------------------------ 渲染与拖放

    private static final class MiscCellRenderer implements TableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            JLabel label = new JLabel(value == null ? "" : value.toString());
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
            label.setBackground(selected ? UiKit.HIGHLIGHT : (row % 2 == 0 ? Color.WHITE : new Color(247, 249, 253)));
            label.setForeground(UiKit.TEXT);
            return label;
        }
    }

    private final class DropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && !MiscView.this.running;
        }

        @Override
        public boolean importData(TransferSupport support) {
            try {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                List<Path> paths = new ArrayList<>();
                for (File file : files) {
                    paths.add(file.toPath());
                }
                MiscView.this.addPaths(paths);
                return true;
            } catch (Exception exception) {
                return false;
            }
        }
    }
}
