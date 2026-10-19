package dev.frostbite.desktop;

import dev.frostbite.host.FrostbiteInstaller;
import dev.frostbite.host.ModApkPatcher;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JScrollBar;
import javax.swing.Scrollable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A Java 27 desktop front end for the two Frostbite APK packaging stages. */
public final class FrostbiteDesktopApp {
    private static final Color BACK = rgb(14, 15, 16);
    private static final Color SIDE = rgb(19, 21, 22);
    private static final Color PANEL = rgb(28, 30, 32);
    private static final Color INPUT = rgb(37, 40, 42);
    private static final Color LINE = rgb(55, 58, 60);
    private static final Color INK = rgb(243, 243, 239);
    private static final Color SOFT = rgb(174, 178, 176);
    private static final Color ACCENT = rgb(213, 203, 188);
    private static final Color LIGHT = rgb(243, 243, 239);
    private static final Color ERROR = rgb(239, 149, 142);
    private static final Font NORMAL = new Font("Segoe UI", Font.PLAIN, 14);
    private static final String SIGNING_ALIAS = "frostbite";
    private static final String SIGNING_PASSWORD = "frostbite";

    private final JFrame frame = new JFrame("Frostbite");
    private final PageDeck deck = new PageDeck();
    private final NavItem installNav = new NavItem("01", "Install Frostbite");
    private final NavItem modsNav = new NavItem("02", "Mods");
    private final InstallPage installPage = new InstallPage();
    private final ModsPage modsPage = new ModsPage();
    private Point dragOffset;

    private FrostbiteDesktopApp() {
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setIconImage(new OutcastLogo(64, LIGHT).toImage());
        frame.setMinimumSize(new Dimension(780, 560));
        frame.setSize(1020, 680);
        frame.setLocationRelativeTo(null);

        JPanel shell = new JPanel(new BorderLayout());
        shell.setBackground(BACK);
        shell.setBorder(BorderFactory.createLineBorder(LINE));
        shell.add(titleBar(), BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(BACK);
        JPanel navigation = sidebar();
        body.add(navigation, BorderLayout.WEST);
        deck.add(scrolling(installPage), "install");
        deck.add(scrolling(modsPage), "mods");
        body.add(deck, BorderLayout.CENTER);
        shell.add(body, BorderLayout.CENTER);
        frame.setContentPane(shell);
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                int width = frame.getWidth() < 960 ? 192 : 220;
                installNav.setCompact(width == 192);
                if (navigation.getPreferredSize().width != width) {
                    navigation.setPreferredSize(new Dimension(width, 0));
                    navigation.revalidate();
                }
            }
        });
        installResizeHandles();
        installNav.activate(true);
        refreshActions();
        frame.setVisible(true);
    }

    private JPanel titleBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BACK);
        bar.setPreferredSize(new Dimension(0, 49));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE));

        JPanel dragArea = new JPanel(new BorderLayout(8, 0));
        dragArea.setOpaque(false);
        dragArea.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 0));
        JLabel icon = new JLabel(new OutcastLogo(30, INK));
        dragArea.add(icon, BorderLayout.WEST);
        dragArea.add(text("OUTCAST     Frostbite", 13, Font.BOLD, INK), BorderLayout.CENTER);
        MouseAdapter drag = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                Point at = event.getLocationOnScreen();
                dragOffset = new Point(at.x - frame.getX(), at.y - frame.getY());
            }
            @Override public void mouseDragged(MouseEvent event) {
                if (dragOffset == null || frame.getExtendedState() == Frame.MAXIMIZED_BOTH) return;
                Point at = event.getLocationOnScreen();
                frame.setLocation(at.x - dragOffset.x, at.y - dragOffset.y);
            }
        };
        bindDrag(dragArea, drag);
        bindDrag(icon, drag);
        for (Component child : dragArea.getComponents()) bindDrag(child, drag);
        bar.add(dragArea, BorderLayout.CENTER);

        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
        controls.add(new WindowControl(WindowControl.Kind.MINIMIZE, () -> frame.setState(Frame.ICONIFIED)));
        controls.add(new WindowControl(WindowControl.Kind.MAXIMIZE, () -> frame.setExtendedState(
                frame.getExtendedState() == Frame.MAXIMIZED_BOTH ? Frame.NORMAL : Frame.MAXIMIZED_BOTH)));
        controls.add(new WindowControl(WindowControl.Kind.CLOSE, frame::dispose));
        bar.add(controls, BorderLayout.EAST);
        return bar;
    }

    private static void bindDrag(Component target, MouseAdapter drag) {
        target.addMouseListener(drag);
        target.addMouseMotionListener(drag);
    }

    private void installResizeHandles() {
        JLayeredPane layer = frame.getLayeredPane();
        int[][] edges = {
                {-1, -1, Cursor.NW_RESIZE_CURSOR}, {0, -1, Cursor.N_RESIZE_CURSOR},
                {1, -1, Cursor.NE_RESIZE_CURSOR}, {-1, 0, Cursor.W_RESIZE_CURSOR},
                {1, 0, Cursor.E_RESIZE_CURSOR}, {-1, 1, Cursor.SW_RESIZE_CURSOR},
                {0, 1, Cursor.S_RESIZE_CURSOR}, {1, 1, Cursor.SE_RESIZE_CURSOR}
        };
        List<JPanel> handles = new ArrayList<>();
        for (int[] edge : edges) {
            int horizontal = edge[0];
            int vertical = edge[1];
            JPanel handle = new JPanel();
            handle.setOpaque(false);
            handle.setCursor(Cursor.getPredefinedCursor(edge[2]));
            MouseAdapter resize = new MouseAdapter() {
                private Point began;
                private Rectangle bounds;

                @Override public void mousePressed(MouseEvent event) {
                    began = event.getLocationOnScreen();
                    bounds = frame.getBounds();
                }

                @Override public void mouseDragged(MouseEvent event) {
                    if (began == null || bounds == null
                            || frame.getExtendedState() == Frame.MAXIMIZED_BOTH) return;
                    int deltaX = event.getXOnScreen() - began.x;
                    int deltaY = event.getYOnScreen() - began.y;
                    Rectangle next = new Rectangle(bounds);
                    if (horizontal < 0) {
                        next.x = Math.min(bounds.x + deltaX,
                                bounds.x + bounds.width - frame.getMinimumSize().width);
                        next.width = bounds.x + bounds.width - next.x;
                    } else if (horizontal > 0) {
                        next.width = Math.max(frame.getMinimumSize().width, bounds.width + deltaX);
                    }
                    if (vertical < 0) {
                        next.y = Math.min(bounds.y + deltaY,
                                bounds.y + bounds.height - frame.getMinimumSize().height);
                        next.height = bounds.y + bounds.height - next.y;
                    } else if (vertical > 0) {
                        next.height = Math.max(frame.getMinimumSize().height, bounds.height + deltaY);
                    }
                    frame.setBounds(next);
                }
            };
            handle.addMouseListener(resize);
            handle.addMouseMotionListener(resize);
            layer.add(handle, JLayeredPane.DRAG_LAYER);
            handles.add(handle);
        }
        Runnable layout = () -> {
            int width = layer.getWidth();
            int height = layer.getHeight();
            int corner = 12;
            int edge = 7;
            for (int i = 0; i < edges.length; i++) {
                int horizontal = edges[i][0];
                int vertical = edges[i][1];
                int x = horizontal < 0 ? 0 : horizontal > 0 ? width - corner : corner;
                int y = vertical < 0 ? 0 : vertical > 0 ? height - corner : corner;
                int w = horizontal == 0 ? width - 2 * corner : corner;
                int h = vertical == 0 ? height - 2 * corner : corner;
                if (horizontal == 0) h = edge;
                if (vertical == 0) w = edge;
                handles.get(i).setBounds(x, y, Math.max(0, w), Math.max(0, h));
            }
        };
        layer.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { layout.run(); }
        });
        SwingUtilities.invokeLater(layout);
    }

    private JPanel sidebar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(SIDE);
        bar.setPreferredSize(new Dimension(220, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(30, 18, 24, 18));

        JPanel brand = new JPanel();
        brand.setOpaque(false);
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        JLabel mark = new JLabel(new OutcastLogo(66, LIGHT));
        mark.setAlignmentX(Component.LEFT_ALIGNMENT);
        brand.add(mark);
        brand.add(Box.createVerticalStrut(17));
        JLabel title = text("Frostbite", 26, Font.BOLD, LIGHT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        brand.add(title);
        bar.add(brand, BorderLayout.NORTH);

        JPanel navigation = new JPanel();
        navigation.setOpaque(false);
        navigation.setLayout(new BoxLayout(navigation, BoxLayout.Y_AXIS));
        navigation.setBorder(BorderFactory.createEmptyBorder(43, 0, 0, 0));
        ButtonGroup group = new ButtonGroup();
        group.add(installNav);
        group.add(modsNav);
        installNav.addActionListener(event -> navigate("install"));
        modsNav.addActionListener(event -> navigate("mods"));
        navigation.add(installNav);
        navigation.add(Box.createVerticalStrut(10));
        navigation.add(modsNav);
        bar.add(navigation, BorderLayout.CENTER);
        return bar;
    }

    private void navigate(String name) {
        deck.transition(name);
        installNav.activate(name.equals("install"));
        modsNav.activate(name.equals("mods"));
    }

    private void refreshActions() {
        installPage.refresh();
        modsPage.refresh();
    }

    private static JScrollPane scrolling(JPanel page) {
        JScrollPane scroll = new JScrollPane(page);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BACK);
        scroll.setBackground(BACK);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        JScrollBar bar = scroll.getVerticalScrollBar();
        bar.setUnitIncrement(22);
        bar.setPreferredSize(new Dimension(9, 0));
        bar.setUI(new BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() {
                trackColor = BACK;
                thumbColor = rgb(75, 79, 81);
            }
            @Override protected JButton createDecreaseButton(int orientation) {
                return zeroScrollButton();
            }
            @Override protected JButton createIncreaseButton(int orientation) {
                return zeroScrollButton();
            }
            @Override protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
                Graphics2D g = (Graphics2D) graphics.create();
                smooth(g);
                g.setColor(thumbColor);
                g.fillRoundRect(bounds.x + 2, bounds.y, Math.max(3, bounds.width - 4),
                        bounds.height, 5, 5);
                g.dispose();
            }
        });
        return scroll;
    }

    private static JButton zeroScrollButton() {
        JButton button = new JButton();
        button.setPreferredSize(new Dimension(0, 0));
        button.setMinimumSize(new Dimension(0, 0));
        button.setMaximumSize(new Dimension(0, 0));
        return button;
    }

    private static Color rgb(int r, int g, int b) { return new Color(r, g, b); }

    private static Color blend(Color start, Color end, double amount) {
        double clamped = Math.max(0d, Math.min(1d, amount));
        return rgb((int) Motion.mix(start.getRed(), end.getRed(), clamped),
                (int) Motion.mix(start.getGreen(), end.getGreen(), clamped),
                (int) Motion.mix(start.getBlue(), end.getBlue(), clamped));
    }

    private static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private static JLabel text(String value, int size, int weight, Color color) {
        JLabel label = new JLabel(value);
        label.setFont(new Font("Segoe UI", weight, size));
        label.setForeground(color);
        return label;
    }

    private static boolean regularFile(JTextField field) {
        try { return Files.isRegularFile(Path.of(field.getText().trim())); }
        catch (RuntimeException invalid) { return false; }
    }

    private static void watch(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { action.run(); }
            @Override public void removeUpdate(DocumentEvent event) { action.run(); }
            @Override public void changedUpdate(DocumentEvent event) { action.run(); }
        });
    }

    private static final class PageDeck extends JPanel {
        private String current = "install";
        private BufferedImage before;
        private BufferedImage after;
        private double fraction;
        private Timer tween;

        PageDeck() {
            super(new CardLayout());
            setOpaque(false);
        }

        void transition(String name) {
            if (name.equals(current)) return;
            if (tween != null) tween.stop();
            before = capture();
            ((CardLayout) getLayout()).show(this, name);
            current = name;
            after = capture();
            fraction = 0d;
            tween = Motion.tween(340, value -> {
                fraction = value;
                repaint();
            }, () -> {
                before = null;
                after = null;
                repaint();
            });
        }

        private BufferedImage capture() {
            if (getWidth() <= 0 || getHeight() <= 0) return null;
            BufferedImage image = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            super.paintChildren(g);
            g.dispose();
            return image;
        }

        @Override protected void paintChildren(Graphics graphics) {
            if (before == null || after == null) {
                super.paintChildren(graphics);
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            int shift = (int) (28 * fraction);
            g.setComposite(AlphaComposite.SrcOver.derive((float) (1d - fraction)));
            g.drawImage(before, -shift, 0, null);
            g.setComposite(AlphaComposite.SrcOver.derive((float) fraction));
            g.drawImage(after, 28 - shift, 0, null);
            g.dispose();
        }
    }

    private static final class NavItem extends JToggleButton {
        private final String number;
        private final String title;
        private boolean compact;
        private double selectedAmount;
        private double hoverAmount;
        private Timer selectedTween;
        private Timer hoverTween;

        NavItem(String number, String title) {
            this.number = number;
            this.title = title;
            setOpaque(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setBorder(BorderFactory.createEmptyBorder());
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
            setPreferredSize(new Dimension(210, 66));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent event) { hoverTo(1d); }
                @Override public void mouseExited(MouseEvent event) { hoverTo(0d); }
            });
        }

        void activate(boolean selected) {
            if (selectedTween != null) selectedTween.stop();
            double start = selectedAmount;
            selectedTween = Motion.tween(240, value -> {
                selectedAmount = Motion.mix(start, selected ? 1d : 0d, value);
                repaint();
            }, null);
        }

        void setCompact(boolean value) {
            compact = value;
            repaint();
        }

        private void hoverTo(double target) {
            if (hoverTween != null) hoverTween.stop();
            double start = hoverAmount;
            hoverTween = Motion.tween(170, value -> {
                hoverAmount = Motion.mix(start, target, value);
                repaint();
            }, null);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            smooth(g);
            g.setColor(blend(SIDE, rgb(45, 48, 49), Math.max(selectedAmount, hoverAmount * 0.55d)));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 13, 13);
            g.setColor(blend(rgb(158, 161, 160), LIGHT, selectedAmount));
            g.setFont(new Font("Segoe UI", Font.BOLD, 17));
            g.drawString(number, 17, 40);
            g.setColor(blend(rgb(173, 176, 174), LIGHT, Math.max(selectedAmount, hoverAmount)));
            g.setFont(NORMAL.deriveFont(Font.BOLD, 14f));
            g.drawString(compact && number.equals("01") ? "Install" : title, 59, 39);
            g.dispose();
        }
    }

    private static final class WindowControl extends JButton {
        enum Kind { MINIMIZE, MAXIMIZE, CLOSE }
        private final Kind kind;
        private double hover;
        private Timer tween;

        WindowControl(Kind kind, Runnable action) {
            this.kind = kind;
            setToolTipText(kind == Kind.MINIMIZE ? "Minimize" : kind == Kind.MAXIMIZE ? "Maximize" : "Close");
            setPreferredSize(new Dimension(48, 48));
            setMaximumSize(new Dimension(48, 48));
            setOpaque(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            addActionListener(event -> action.run());
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent event) { hoverTo(1d); }
                @Override public void mouseExited(MouseEvent event) { hoverTo(0d); }
            });
        }

        private void hoverTo(double target) {
            if (tween != null) tween.stop();
            double start = hover;
            tween = Motion.tween(150, value -> {
                hover = Motion.mix(start, target, value);
                repaint();
            }, null);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            smooth(g);
            Color hoverColor = kind == Kind.CLOSE ? rgb(119, 66, 65) : INPUT;
            g.setColor(blend(BACK, hoverColor, hover));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(INK);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int x = getWidth() / 2;
            int y = getHeight() / 2;
            switch (kind) {
                case MINIMIZE -> g.drawLine(x - 6, y + 4, x + 6, y + 4);
                case MAXIMIZE -> g.drawRect(x - 5, y - 5, 10, 10);
                case CLOSE -> {
                    g.drawLine(x - 5, y - 5, x + 5, y + 5);
                    g.drawLine(x + 5, y - 5, x - 5, y + 5);
                }
            }
            g.dispose();
        }
    }

    private static final class ActionButton extends JButton {
        private final boolean primary;
        private double hover;
        private double pressed;
        private Timer hoverTween;
        private Timer pressTween;

        ActionButton(String title, boolean primary) {
            super(title);
            this.primary = primary;
            setFont(NORMAL.deriveFont(Font.BOLD, 13f));
            setForeground(primary ? BACK : INK);
            setOpaque(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorder(BorderFactory.createEmptyBorder(11, 18, 11, 18));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent event) { animateHover(1d); }
                @Override public void mouseExited(MouseEvent event) { animateHover(0d); }
                @Override public void mousePressed(MouseEvent event) { animatePress(1d); }
                @Override public void mouseReleased(MouseEvent event) { animatePress(0d); }
            });
        }

        private void animateHover(double target) {
            if (hoverTween != null) hoverTween.stop();
            double start = hover;
            hoverTween = Motion.tween(180, value -> {
                hover = Motion.mix(start, target, value);
                repaint();
            }, null);
        }

        private void animatePress(double target) {
            if (pressTween != null) pressTween.stop();
            double start = pressed;
            pressTween = Motion.tween(110, value -> {
                pressed = Motion.mix(start, target, value);
                repaint();
            }, null);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            smooth(g);
            Color base = primary ? ACCENT : INPUT;
            Color over = primary ? INK : rgb(58, 61, 63);
            Color fill = blend(base, over, hover * 0.85d);
            if (!isEnabled()) fill = blend(fill, BACK, 0.65d);
            fill = blend(fill, BACK, pressed * 0.18d);
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 13, 13);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class PathField extends JTextField {
        private double focus;
        private Timer tween;

        PathField() {
            setOpaque(false);
            setFont(NORMAL);
            setForeground(INK);
            setCaretColor(INK);
            setSelectionColor(rgb(105, 105, 104));
            setBorder(BorderFactory.createEmptyBorder(10, 13, 10, 13));
            setPreferredSize(new Dimension(0, 43));
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent event) { focusTo(1d); }
                @Override public void focusLost(FocusEvent event) { focusTo(0d); }
            });
        }

        private void focusTo(double target) {
            if (tween != null) tween.stop();
            double start = focus;
            tween = Motion.tween(190, value -> {
                focus = Motion.mix(start, target, value);
                repaint();
            }, null);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            smooth(g);
            g.setColor(INPUT);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g.setColor(blend(LINE, ACCENT, focus));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class Surface extends JPanel {
        Surface() { setOpaque(false); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            smooth(g);
            g.setColor(PANEL);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 19, 19);
            g.setColor(LINE);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 19, 19);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class StageVisual extends JComponent {
        private final String number;
        private final String title;
        private boolean working;
        private long started;
        private Timer pulse;

        StageVisual(String number, String title) {
            this.number = number;
            this.title = title;
            setPreferredSize(new Dimension(210, 400));
        }

        void setWorking(boolean value) {
            working = value;
            if (pulse != null) pulse.stop();
            if (value) {
                started = System.nanoTime();
                pulse = new Timer(16, event -> repaint());
                pulse.start();
            }
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            smooth(g);
            g.setColor(PANEL);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 19, 19);
            g.setColor(LINE);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 19, 19);

            int center = getWidth() / 2;
            int diameter = 106;
            g.setColor(INPUT);
            g.fillOval(center - diameter / 2, 73, diameter, diameter);
            g.setColor(LINE);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(center - diameter / 2, 73, diameter, diameter);
            if (working) {
                double phase = ((System.nanoTime() - started) / 1_000_000_000d) % 1.4d / 1.4d;
                g.setColor(ACCENT);
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawArc(center - diameter / 2, 73, diameter, diameter,
                        (int) (phase * 360), 76);
            }
            OutcastLogo.paint(g, center - 36, 90, 72, INK);
            g.setColor(INK);
            g.setFont(new Font("Segoe UI", Font.BOLD, 53));
            int numberWidth = g.getFontMetrics().stringWidth(number);
            g.drawString(number, center - numberWidth / 2, 288);
            g.setColor(SOFT);
            g.setFont(NORMAL.deriveFont(14f));
            int titleWidth = g.getFontMetrics().stringWidth(title);
            g.drawString(title, center - titleWidth / 2, 316);
            g.dispose();
        }
    }

    private abstract class WorkPage extends JPanel implements Scrollable {
        final JPanel column = new JPanel() {
            @Override public Dimension getPreferredSize() {
                Dimension natural = super.getPreferredSize();
                return new Dimension(980, natural.height);
            }
        };
        final Surface form = new Surface();
        final JPanel work = new JPanel(new BorderLayout(18, 0));
        final StageVisual visual;
        final JLabel status = text("Ready when you are.", 12, Font.PLAIN, SOFT);
        final ActionButton action;
        boolean busy;

        WorkPage(String heading, String subtitle, String stage, String stageTitle, String actionText) {
            setOpaque(false);
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(28, 36, 10, 36));
            column.setOpaque(false);
            column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
            column.setMinimumSize(new Dimension(0, 0));
            column.setMaximumSize(new Dimension(980, Integer.MAX_VALUE));
            JPanel centered = new JPanel(null) {
                @Override public Dimension getPreferredSize() {
                    return column.getPreferredSize();
                }
                @Override public void doLayout() {
                    int width = Math.min(980, getWidth());
                    int x = Math.max(0, (getWidth() - width) / 2);
                    column.setBounds(x, 0, width, column.getPreferredSize().height);
                    column.doLayout();
                }
            };
            centered.setOpaque(false);
            centered.add(column);
            add(centered, BorderLayout.NORTH);
            JLabel title = text(heading, 32, Font.BOLD, INK);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            column.add(title);
            column.add(Box.createVerticalStrut(8));
            JLabel caption = text(subtitle, 14, Font.PLAIN, SOFT);
            caption.setAlignmentX(Component.LEFT_ALIGNMENT);
            column.add(caption);
            column.add(Box.createVerticalStrut(22));

            JPanel rail = new JPanel();
            rail.setOpaque(false);
            rail.setLayout(new BoxLayout(rail, BoxLayout.X_AXIS));
            rail.setAlignmentX(Component.LEFT_ALIGNMENT);
            rail.setMaximumSize(new Dimension(Integer.MAX_VALUE, 5));
            rail.add(segment(stage.equals("01")));
            rail.add(Box.createHorizontalStrut(8));
            rail.add(segment(stage.equals("02")));
            column.add(rail);
            column.add(Box.createVerticalStrut(22));

            work.setOpaque(false);
            work.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
            form.setBorder(BorderFactory.createEmptyBorder(25, 27, 24, 27));
            work.add(form, BorderLayout.CENTER);
            visual = new StageVisual(stage, stageTitle);
            work.add(visual, BorderLayout.EAST);
            column.add(work);
            addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent event) {
                    boolean showVisual = getWidth() >= 950;
                    if (visual.isVisible() != showVisual) {
                        visual.setVisible(showVisual);
                        work.revalidate();
                        work.repaint();
                    }
                }
            });
            action = new ActionButton(actionText, true);
        }

        private JComponent segment(boolean active) {
            return new JComponent() {
                @Override protected void paintComponent(Graphics graphics) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    smooth(g);
                    g.setColor(active ? ACCENT : LINE);
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                    g.dispose();
                }
                @Override public Dimension getPreferredSize() { return new Dimension(100, 5); }
                @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, 5); }
            };
        }

        void finishForm() {
            form.add(Box.createVerticalStrut(15));
            JPanel footer = new JPanel(new BorderLayout(12, 0));
            footer.setOpaque(false);
            footer.setAlignmentX(Component.LEFT_ALIGNMENT);
            footer.add(status, BorderLayout.CENTER);
            footer.add(action, BorderLayout.EAST);
            form.add(footer);
            int height = form.getPreferredSize().height;
            work.setPreferredSize(new Dimension(0, height));
            work.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
            visual.setPreferredSize(new Dimension(210, height));
        }

        void working(boolean value) {
            busy = value;
            visual.setWorking(value);
            refreshActions();
        }

        void report(String message, Color color) {
            status.setText(message);
            status.setForeground(color);
        }

        abstract void refresh();

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 24;
        }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(24, visible.height - 48);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private JPanel fieldRow(String title, String helper, PathField field, boolean output,
                            String extension, String chooserTitle) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel name = text(title, 13, Font.BOLD, INK);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(name);
        if (!helper.isBlank()) {
            row.add(Box.createVerticalStrut(3));
            JLabel hint = text(helper, 12, Font.PLAIN, SOFT);
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(hint);
        }
        row.add(Box.createVerticalStrut(8));
        if (extension == null) {
            field.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(field);
        } else {
            JPanel line = new JPanel(new BorderLayout(8, 0));
            line.setOpaque(false);
            line.setAlignmentX(Component.LEFT_ALIGNMENT);
            line.add(field, BorderLayout.CENTER);
            ActionButton choose = new ActionButton("Choose", false);
            choose.addActionListener(event -> choose(chooserTitle, extension, output, field));
            line.add(choose, BorderLayout.EAST);
            row.add(line);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        watch(field, this::refreshActions);
        return row;
    }

    private void choose(String title, String extension, boolean save, JTextField target) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileFilter(new FileNameExtensionFilter(extension.equals("zip")
                ? "ZIP files (*.zip)" : "Android APKs (*.apk)", extension));
        if (!target.getText().isBlank()) chooser.setSelectedFile(new File(target.getText()));
        int selection = save ? chooser.showSaveDialog(frame) : chooser.showOpenDialog(frame);
        if (selection != JFileChooser.APPROVE_OPTION) return;
        Path picked = chooser.getSelectedFile().toPath().toAbsolutePath();
        if (save && !picked.getFileName().toString().toLowerCase().endsWith(".apk"))
            picked = picked.resolveSibling(picked.getFileName() + ".apk");
        target.setText(picked.toString());
    }

    private final class InstallPage extends WorkPage {
        private final PathField game = new PathField();
        private final PathField runtime = new PathField();
        private final PathField activity = new PathField();
        private final PathField output = new PathField();

        InstallPage() {
            super("Install Frostbite", "Add the runtime to a Unity IL2CPP game APK.",
                    "01", "Runtime", "Build Frostbite APK");
            form.add(fieldRow("Game APK", "", game, false, "apk", "Select original game APK"));
            form.add(Box.createVerticalStrut(18));
            form.add(fieldRow("Runtime bundle", "", runtime, false, "zip", "Select Frostbite runtime ZIP"));
            form.add(Box.createVerticalStrut(18));
            form.add(fieldRow("Launch Activity", "Full Activity class name from the game's manifest",
                    activity, false, null, ""));
            form.add(Box.createVerticalStrut(18));
            form.add(fieldRow("Output APK", "", output, true, "apk", "Save Frostbite APK"));
            finishForm();
            action.addActionListener(event -> build());
        }

        @Override void refresh() {
            action.setEnabled(!busy && !modsPage.busy && regularFile(game) && regularFile(runtime)
                    && !activity.getText().isBlank() && !output.getText().isBlank());
        }

        private void build() {
            Path target = Path.of(output.getText().trim());
            if (!confirmReplace(target)) return;
            working(true);
            report("Preparing game APK…", SOFT);
            new SwingWorker<Void, String>() {
                @Override protected Void doInBackground() throws Exception {
                    FrostbiteInstaller.install(Path.of(game.getText().trim()),
                            Path.of(runtime.getText().trim()), activity.getText().trim(), target,
                            BundledKey.path(), SIGNING_ALIAS, SIGNING_PASSWORD.toCharArray(),
                            SIGNING_PASSWORD.toCharArray(), this::publish);
                    return null;
                }
                @Override protected void process(List<String> messages) {
                    report(messages.getLast(), SOFT);
                }
                @Override protected void done() {
                    working(false);
                    try {
                        get();
                        report("Frostbite APK ready", INK);
                        modsPage.useInstalled(target);
                        navigate("mods");
                    } catch (Exception error) { showError(InstallPage.this, error); }
                }
            }.execute();
        }
    }

    private final class ModsPage extends WorkPage {
        private final PathField game = new PathField();
        private final PathField output = new PathField();
        private final DefaultListModel<String> selected = new DefaultListModel<>();
        private final DefaultListModel<String> installedModel = new DefaultListModel<>();
        private final JList<String> installedList = new JList<>(installedModel);
        private String installedSource = "";

        ModsPage() {
            super("Mods", "Add or remove .frostbite mods inside the Frostbite game APK.",
                    "02", "Mods", "Build modded APK");
            form.add(fieldRow("Frostbite game APK", "", game, false, "apk", "Select Frostbite game APK"));
            form.add(Box.createVerticalStrut(18));

            JPanel modsRow = new JPanel();
            modsRow.setOpaque(false);
            modsRow.setLayout(new BoxLayout(modsRow, BoxLayout.Y_AXIS));
            modsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel title = text("Mods to add", 13, Font.BOLD, INK);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            modsRow.add(title);
            modsRow.add(Box.createVerticalStrut(8));
            JList<String> list = style(new JList<>(selected));
            JScrollPane listScroll = new JScrollPane(list);
            listScroll.setPreferredSize(new Dimension(300, 102));
            listScroll.setBorder(BorderFactory.createLineBorder(LINE));
            JPanel picker = new JPanel(new BorderLayout(8, 0));
            picker.setOpaque(false);
            picker.setAlignmentX(Component.LEFT_ALIGNMENT);
            picker.add(listScroll, BorderLayout.CENTER);
            JPanel controls = new JPanel();
            controls.setOpaque(false);
            controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
            ActionButton add = new ActionButton("Add mods", false);
            add.addActionListener(event -> addMods());
            ActionButton remove = new ActionButton("Remove", false);
            remove.addActionListener(event -> {
                int[] indices = list.getSelectedIndices();
                for (int i = indices.length - 1; i >= 0; i--) selected.remove(indices[i]);
                refresh();
            });
            controls.add(add);
            controls.add(Box.createVerticalStrut(8));
            controls.add(remove);
            picker.add(controls, BorderLayout.EAST);
            modsRow.add(picker);
            modsRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, modsRow.getPreferredSize().height));
            form.add(modsRow);
            form.add(Box.createVerticalStrut(18));

            JPanel installedRow = new JPanel();
            installedRow.setOpaque(false);
            installedRow.setLayout(new BoxLayout(installedRow, BoxLayout.Y_AXIS));
            installedRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel installedTitle = text("Installed mods", 13, Font.BOLD, INK);
            installedTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
            installedRow.add(installedTitle);
            installedRow.add(Box.createVerticalStrut(3));
            JLabel installedHint = text("Select mods to remove from the chosen APK.", 12, Font.PLAIN, SOFT);
            installedHint.setAlignmentX(Component.LEFT_ALIGNMENT);
            installedRow.add(installedHint);
            installedRow.add(Box.createVerticalStrut(8));
            style(installedList);
            installedList.addListSelectionListener(event -> refresh());
            JScrollPane installedScroll = new JScrollPane(installedList);
            installedScroll.setPreferredSize(new Dimension(300, 102));
            installedScroll.setBorder(BorderFactory.createLineBorder(LINE));
            installedScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            installedRow.add(installedScroll);
            installedRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, installedRow.getPreferredSize().height));
            form.add(installedRow);
            form.add(Box.createVerticalStrut(18));
            form.add(fieldRow("Final APK", "", output, true, "apk", "Save modded game APK"));
            finishForm();
            action.addActionListener(event -> build());
        }

        private JList<String> style(JList<String> created) {
            created.setFont(NORMAL);
            created.setBackground(INPUT);
            created.setForeground(INK);
            created.setSelectionBackground(rgb(82, 82, 81));
            created.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            return created;
        }

        void useInstalled(Path target) {
            game.setText(target.toString());
            String name = target.getFileName().toString();
            String stem = name.toLowerCase().endsWith(".apk") ? name.substring(0, name.length() - 4) : name;
            output.setText(target.resolveSibling(stem + "-mods.apk").toString());
            report("Add .frostbite mods, or remove installed ones.", SOFT);
        }

        private void addMods() {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select .frostbite mods");
            chooser.setMultiSelectionEnabled(true);
            chooser.setFileFilter(new FileNameExtensionFilter("Frostbite mods (*.frostbite)", "frostbite"));
            if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            for (File file : chooser.getSelectedFiles()) {
                String path = file.getAbsolutePath();
                if (!selected.contains(path)) selected.addElement(path);
            }
            refresh();
        }

        /** Reloads the installed mod list whenever the chosen game APK changes. */
        private void loadInstalled() {
            String path = game.getText().trim();
            if (path.equals(installedSource)) return;
            installedSource = path;
            installedModel.clear();
            if (!regularFile(game)) return;
            try {
                for (String name : ModApkPatcher.installedMods(Path.of(path))) installedModel.addElement(name);
            } catch (IOException notFrostbite) {
                // Not a Frostbite APK yet, or unreadable: leave the installed list empty.
            }
        }

        @Override void refresh() {
            loadInstalled();
            boolean hasWork = !selected.isEmpty() || !installedList.getSelectedValuesList().isEmpty();
            action.setEnabled(!busy && !installPage.busy && regularFile(game)
                    && hasWork && !output.getText().isBlank());
        }

        private void build() {
            Path target = Path.of(output.getText().trim());
            if (!confirmReplace(target)) return;
            List<Path> mods = new ArrayList<>();
            for (int i = 0; i < selected.size(); i++) mods.add(Path.of(selected.get(i)));
            List<String> removeNames = new ArrayList<>(installedList.getSelectedValuesList());
            working(true);
            report("Packaging mods…", SOFT);
            new SwingWorker<Void, String>() {
                @Override protected Void doInBackground() throws Exception {
                    ModApkPatcher.apply(Path.of(game.getText().trim()), mods, removeNames, target,
                            BundledKey.path(), SIGNING_ALIAS, SIGNING_PASSWORD.toCharArray(),
                            SIGNING_PASSWORD.toCharArray(), this::publish);
                    return null;
                }
                @Override protected void process(List<String> messages) {
                    report(messages.getLast(), SOFT);
                }
                @Override protected void done() {
                    working(false);
                    try {
                        get();
                        report("Signed APK ready", INK);
                        installedSource = "";
                        JOptionPane.showMessageDialog(frame, "Final APK saved to:\n" + target,
                                "Build complete", JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception error) { showError(ModsPage.this, error); }
                }
            }.execute();
        }
    }

    private static final class BundledKey {
        private static Path extracted;
        static synchronized Path path() throws IOException {
            if (extracted != null && Files.isRegularFile(extracted)) return extracted;
            try (InputStream input = FrostbiteDesktopApp.class.getResourceAsStream(
                    "/frostbite-development.p12")) {
                if (input == null) throw new IOException("Bundled development key is missing");
                extracted = Files.createTempFile("frostbite-development-", ".p12");
                Files.copy(input, extracted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                extracted.toFile().deleteOnExit();
                return extracted;
            }
        }
    }

    private boolean confirmReplace(Path target) {
        return !Files.exists(target) || JOptionPane.showConfirmDialog(frame,
                "Replace the existing APK?\n" + target, "Replace output",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private void showError(WorkPage page, Exception error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        page.report("Build failed: " + cause.getMessage(), ERROR);
        JOptionPane.showMessageDialog(frame, cause.toString(), "Frostbite build failed",
                JOptionPane.ERROR_MESSAGE);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(FrostbiteDesktopApp::new);
    }
}
