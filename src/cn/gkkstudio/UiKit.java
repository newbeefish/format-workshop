package cn.gkkstudio;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;

/** 共用的界面基元：配色、圆角玻璃面板、按钮、图标、进度渲染。 */
final class UiKit {
    static final Color TEXT = new Color(0x1D1D1F);
    static final Color MUTED = new Color(0x626269);
    static final Color PRIMARY = new Color(0x8B3DEF);
    static final Color PRIMARY_DARK = new Color(0x7427D6);
    static final Color HIGHLIGHT = new Color(0xEDE4FD);
    static final Color TRACK = new Color(0xE7DEFA);
    static final Color SUCCESS = new Color(2269291);
    static final Color DANGER = new Color(11880200);
    static final Color CARD = Color.WHITE;
    static final Color SIDEBAR = new Color(0xF0F0F2);
    static final Color SETTINGS = new Color(0xF7F7F9);

    private UiKit() {
    }

    static JButton primaryButton(String text) {
        final JButton button = new JButton(text);
        button.setForeground(Color.WHITE);
        button.setBackground(PRIMARY);
        button.setMargin(new Insets(9, 20, 9, 20));
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent event) {
                if (button.isEnabled()) {
                    button.setBackground(PRIMARY_DARK);
                }
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent event) {
                button.setBackground(PRIMARY);
            }
        });
        return button;
    }

    static JButton secondaryButton(String text) {
        JButton button = new JButton(text);
        button.setForeground(TEXT);
        button.setBackground(Color.WHITE);
        button.putClientProperty("JButton.buttonType", "roundRect");
        return button;
    }

    static JButton linkButton(String text) {
        JButton button = new JButton(text);
        button.setForeground(PRIMARY);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    /**
     * 把纯文本按固定列数硬折行后包成 HTML。
     *
     * <p>右栏只有 268px 宽，靠 HTML 的 width 样式去折行在 Swing 里并不可靠
     * （标签的偏好宽度会超出卡片，文字右边被裁掉），所以这里自己折。
     */
    static String wrapHtml(String text, int columns) {
        StringBuilder builder = new StringBuilder("<html>");
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                builder.append("<br>");
            }
            builder.append(wrapLine(lines[index], columns));
        }
        builder.append("</html>");
        return builder.toString();
    }

    /** 按“中文算 1 列、ASCII 算半列”粗略折行。 */
    private static String wrapLine(String line, int columns) {
        StringBuilder out = new StringBuilder();
        double used = 0;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            double width = ch < 0x2E80 ? 0.55 : 1.0;
            if (used + width > columns) {
                out.append("<br>");
                used = 0;
            }
            out.append(ch);
            used += width;
        }
        return out.toString();
    }

    static JLabel inspectorLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(TEXT);
        label.setFont(label.getFont().deriveFont(13.0f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    static List<Image> appIcons() {
        List<Image> icons = new ArrayList<>();
        for (int size : new int[]{16, 32, 48, 64, 128, 256}) {
            Image loaded = loadIconResource(size);
            if (loaded != null) {
                icons.add(loaded);
                continue;
            }
            icons.add(drawFallbackIcon(size));
        }
        return icons;
    }

    /** 应用图标来自 resources/cn/gkkstudio/icons/icon-<size>.png（由 .ico 提取）。 */
    private static Image loadIconResource(int size) {
        try (java.io.InputStream stream = UiKit.class.getResourceAsStream("/cn/gkkstudio/icons/icon-" + size + ".png")) {
            if (stream != null) {
                return javax.imageio.ImageIO.read(stream);
            }
        } catch (Exception ignored) {
            // 回退到绘制图标
        }
        return null;
    }

    private static Image drawFallbackIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(PRIMARY);
        graphics.fillRoundRect(1, 1, size - 2, size - 2, Math.max(5, size / 4), Math.max(5, size / 4));
        graphics.setColor(new Color(255, 255, 255, 225));
        int barWidth = Math.max(2, size / 13);
        int gap = Math.max(2, size / 12);
        int startX = size / 4;
        int[] heights = new int[]{size * 3 / 8, size * 5 / 8, size * 2 / 7, size / 2};
        for (int i = 0; i < heights.length; i++) {
            int x = startX + i * (barWidth + gap);
            int height = heights[i];
            graphics.fillRoundRect(x, (size - height) / 2, barWidth, height, barWidth, barWidth);
        }
        graphics.dispose();
        return image;
    }

    /** 圆角玻璃卡片。 */
    static class RoundedPanel extends JPanel {
        private final Color color;
        private final int arc;
        private int pointerX = -1;
        private int pointerY = -1;

        RoundedPanel(Color color, int arc) {
            this.color = color;
            this.arc = arc;
            this.setOpaque(false);
            this.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override
                public void mouseMoved(java.awt.event.MouseEvent event) {
                    pointerX = event.getX();
                    pointerY = event.getY();
                    RoundedPanel.this.repaint();
                }
            });
            this.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseExited(java.awt.event.MouseEvent event) {
                    pointerX = -1;
                    pointerY = -1;
                    RoundedPanel.this.repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D graphics2D = (Graphics2D) graphics.create();
            graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean light = this.color.getRed() > 225 && this.color.getGreen() > 225 && this.color.getBlue() > 225;
            if (light) {
                for (int i = 5; i > 0; --i) {
                    graphics2D.setColor(new Color(30, 54, 90, 3));
                    graphics2D.fillRoundRect(i, i + 2, this.getWidth() - i * 2, this.getHeight() - i * 2 - 2, this.arc, this.arc);
                }
                graphics2D.setPaint(new GradientPaint(0.0f, 0.0f, new Color(255, 255, 255, 220), this.getWidth(), this.getHeight(), new Color(246, 250, 255, 156)));
                graphics2D.fillRoundRect(1, 1, this.getWidth() - 3, this.getHeight() - 3, this.arc, this.arc);
                graphics2D.setPaint(new GradientPaint(0.0f, 0.0f, new Color(255, 255, 255, 250), 0.0f, this.getHeight(), new Color(255, 255, 255, 105)));
                graphics2D.setStroke(new BasicStroke(1.2f));
                graphics2D.drawRoundRect(1, 1, this.getWidth() - 3, this.getHeight() - 3, this.arc, this.arc);
                graphics2D.setColor(new Color(255, 255, 255, 120));
                graphics2D.drawRoundRect(3, 3, this.getWidth() - 7, this.getHeight() - 7, this.arc - 2, this.arc - 2);
                if (this.pointerX >= 0) {
                    graphics2D.clip(new RoundRectangle2D.Float(2.0f, 2.0f, this.getWidth() - 5, this.getHeight() - 5, this.arc, this.arc));
                    graphics2D.setPaint(new RadialGradientPaint(this.pointerX, this.pointerY, 150.0f,
                            new float[]{0.0f, 1.0f}, new Color[]{new Color(255, 255, 255, 110), new Color(255, 255, 255, 0)}));
                    graphics2D.fillRect(0, 0, this.getWidth(), this.getHeight());
                }
            } else {
                graphics2D.setColor(this.color);
                graphics2D.fillRoundRect(0, 0, this.getWidth(), this.getHeight(), this.arc, this.arc);
            }
            graphics2D.dispose();
            super.paintComponent(graphics);
        }
    }

    /** 窗口与弹窗的渐变玻璃背景。 */
    static class GlassBackdrop extends JPanel {
        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D graphics2D = (Graphics2D) graphics.create();
            graphics2D.setPaint(new GradientPaint(0.0f, 0.0f, new Color(0xF4EEFF), this.getWidth(), this.getHeight(), new Color(0xF8F0FB)));
            graphics2D.fillRect(0, 0, this.getWidth(), this.getHeight());
            haze(graphics2D, this.getWidth() * 0.28f, this.getHeight() * 0.15f, this.getWidth() * 0.5f, new Color(150, 110, 240, 95));
            haze(graphics2D, this.getWidth() * 0.79f, this.getHeight() * 0.8f, this.getWidth() * 0.48f, new Color(190, 120, 225, 80));
            haze(graphics2D, this.getWidth() * 0.58f, this.getHeight() * 0.45f, this.getWidth() * 0.34f, new Color(255, 255, 255, 210));
            graphics2D.dispose();
        }

        private static void haze(Graphics2D graphics2D, float x, float y, float radius, Color color) {
            graphics2D.setPaint(new RadialGradientPaint(x, y, Math.max(1.0f, radius),
                    new float[]{0.0f, 0.58f, 1.0f},
                    new Color[]{color,
                            new Color(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha() / 3),
                            new Color(color.getRed(), color.getGreen(), color.getBlue(), 0)}));
            graphics2D.fillRect(0, 0, 20000, 20000);
        }
    }

    /** 拖拽投放区。 */
    static final class DropPanel extends RoundedPanel {
        DropPanel() {
            super(Color.WHITE, 28);
        }

        @Override
        protected void paintBorder(Graphics graphics) {
            Graphics2D graphics2D = (Graphics2D) graphics.create();
            graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics2D.setColor(new Color(255, 255, 255, 210));
            graphics2D.drawRoundRect(1, 1, this.getWidth() - 3, this.getHeight() - 3, 28, 28);
            graphics2D.dispose();
        }
    }

    /** 状态圆点。 */
    static final class StatusDot implements Icon {
        private final boolean ready;

        StatusDot(boolean ready) {
            this.ready = ready;
        }

        @Override
        public int getIconWidth() {
            return 8;
        }

        @Override
        public int getIconHeight() {
            return 8;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            graphics.setColor(this.ready ? SUCCESS : DANGER);
            graphics.fillOval(x, y, 8, 8);
        }
    }

    /** 导入图标（向下的箭头托盘）。 */
    static final class ImportIcon implements Icon {
        @Override
        public int getIconWidth() {
            return 40;
        }

        @Override
        public int getIconHeight() {
            return 36;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D graphics2D = (Graphics2D) graphics.create();
            graphics2D.translate(x, y);
            graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics2D.setColor(PRIMARY);
            graphics2D.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics2D.drawLine(20, 3, 20, 23);
            graphics2D.drawLine(13, 16, 20, 23);
            graphics2D.drawLine(20, 23, 27, 16);
            graphics2D.drawLine(5, 23, 5, 32);
            graphics2D.drawLine(5, 32, 35, 32);
            graphics2D.drawLine(35, 32, 35, 23);
            graphics2D.dispose();
        }
    }

    /** 队列里的进度单元格：细圆角进度条 + 百分比。 */
    static final class ProgressRenderer extends JPanel implements TableCellRenderer {
        private int percent;
        private String text = "—";

        ProgressRenderer() {
            this.setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int column) {
            this.percent = value instanceof Integer number ? number : 0;
            this.text = this.percent == 0 ? "—" : this.percent + "%";
            this.setFont(table.getFont().deriveFont(11.5f));
            this.setBackground(selected ? HIGHLIGHT : (row % 2 == 0 ? new Color(255, 255, 255) : new Color(247, 249, 253)));
            return this;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D graphics2D = (Graphics2D) graphics.create();
            graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics2D.setFont(this.getFont());
            java.awt.FontMetrics metrics = graphics2D.getFontMetrics();
            int textWidth = metrics.stringWidth(this.text);
            int barX = 12;
            int barHeight = 6;
            int barY = (this.getHeight() - barHeight) / 2;
            int barWidth = Math.max(8, this.getWidth() - 24 - textWidth - 8);
            graphics2D.setColor(TRACK);
            graphics2D.fillRoundRect(barX, barY, barWidth, barHeight, barHeight, barHeight);
            if (this.percent > 0) {
                int filled = Math.max(barHeight, Math.round(barWidth * this.percent / 100.0f));
                graphics2D.setColor(PRIMARY);
                graphics2D.fillRoundRect(barX, barY, Math.min(barWidth, filled), barHeight, barHeight, barHeight);
            }
            graphics2D.setColor(this.percent >= 100 ? PRIMARY : MUTED);
            int textX = this.getWidth() - 12 - textWidth;
            int textY = (this.getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            graphics2D.drawString(this.text, textX, textY);
            graphics2D.dispose();
        }
    }

    /** 让滚动内容宽度跟随视口的面板。 */
    static final class ScrollablePanel extends JPanel implements javax.swing.Scrollable {
        ScrollablePanel(LayoutManager layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return this.getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
            return 80;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    static javax.swing.border.Border lineBorder() {
        return BorderFactory.createMatteBorder(1, 1, 1, 1, new Color(0xE5E5EA));
    }

    static String shortenPath(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int keep = (max - 3) / 2;
        return value.substring(0, keep) + "..." + value.substring(value.length() - (max - 3 - keep));
    }

    static Font baseFont() {
        return new Font("Microsoft YaHei UI", Font.PLAIN, 14);
    }
}
