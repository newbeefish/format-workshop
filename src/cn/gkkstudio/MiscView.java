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

        this.setLayout(new BorderLayout(24, 0));
        this.setOpaque(false);
        this.add(this.buildMain(), BorderLayout.CENTER);
        this.add(this.buildSettings(), BorderLayout.EAST);
        this.wireActions();
        this.updateHint();
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
        body.add(Box.createVerticalStrut(12));

        body.add(UiKit.inspectorLabel("操作"));
        body.add(Box.createVerticalStrut(7));
        this.actionBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        this.actionBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.actionBox);
        body.add(Box.createVerticalStrut(9));

        body.add(UiKit.inspectorLabel("输出图片格式（PDF 拆图）"));
        body.add(Box.createVerticalStrut(7));
        this.imageFormatBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        this.imageFormatBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.imageFormatBox);
        body.add(Box.createVerticalStrut(9));

        body.add(UiKit.inspectorLabel("保存位置"));
        body.add(Box.createVerticalStrut(7));
        this.outputField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        this.outputField.setPreferredSize(new Dimension(260, 34));
        this.outputField.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.outputField);
        body.add(Box.createVerticalStrut(7));
        JButton browse = UiKit.secondaryButton("更改文件夹…");
        browse.setAlignmentX(Component.LEFT_ALIGNMENT);
        browse.addActionListener(event -> this.chooseOutput());
        body.add(browse);
        body.add(Box.createVerticalStrut(9));

        body.add(UiKit.inspectorLabel("同时处理"));
        body.add(Box.createVerticalStrut(7));
        this.concurrencySpinner.setMaximumSize(new Dimension(90, 34));
        this.concurrencySpinner.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(this.concurrencySpinner);

        this.hintLabel.setForeground(UiKit.MUTED);
        this.hintLabel.setFont(this.hintLabel.getFont().deriveFont(12.0f));
        this.hintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(Box.createVerticalStrut(10));
        body.add(this.hintLabel);

        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        this.startButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        this.cancelButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        this.cancelButton.setVisible(false);
        this.startButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        this.cancelButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        bottom.add(this.startButton);
        bottom.add(Box.createVerticalStrut(6));
        bottom.add(this.cancelButton);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
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

    private void updateHint() {
        MiscAction action = (MiscAction) this.actionBox.getSelectedItem();
        this.imageFormatBox.setEnabled(action == MiscAction.PDF_TO_IMG && !this.running);
        this.hintLabel.setText("<html>" + action.hint + "<br>" + action.detail + "</html>");
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
        this.running = true;
        this.setBusy(true);
        this.engine.run(this.items, action, output, (String) this.imageFormatBox.getSelectedItem(),
                (Integer) this.concurrencySpinner.getValue(),
                item -> SwingUtilities.invokeLater(this::refresh),
                () -> SwingUtilities.invokeLater(() -> {
                    this.running = false;
                    this.setBusy(false);
                    this.refresh();
                }));
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
