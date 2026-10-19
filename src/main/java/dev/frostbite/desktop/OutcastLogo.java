package dev.frostbite.desktop;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders the supplied transparent Outcast SVG as a vector in Swing. */
final class OutcastLogo implements Icon {
    private static final Path2D SHAPE = loadShape();
    private final int size;
    private final Color color;

    OutcastLogo(int size, Color color) {
        this.size = size;
        this.color = color;
    }

    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }

    @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
        paint((Graphics2D) graphics, x, y, size, color);
    }

    static void paint(Graphics2D graphics, int x, int y, int size, Color color) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Rectangle2D bounds = SHAPE.getBounds2D();
        double scale = size * 0.9d / Math.max(bounds.getWidth(), bounds.getHeight());
        AffineTransform transform = new AffineTransform();
        transform.translate(x + size / 2d, y + size / 2d);
        transform.scale(scale, scale);
        transform.translate(-bounds.getCenterX(), -bounds.getCenterY());
        Shape scaled = transform.createTransformedShape(SHAPE);
        g.setColor(color);
        g.fill(scaled);
        g.dispose();
    }

    BufferedImage toImage() {
        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        paint(g, 0, 0, size, color);
        g.dispose();
        return result;
    }

    private static Path2D loadShape() {
        try (InputStream input = OutcastLogo.class.getResourceAsStream("/outcast-logo.svg")) {
            if (input == null) throw new IOException("Outcast SVG resource is missing");
            String svg = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Matcher path = Pattern.compile("<path\\b[^>]*\\bd=\"([^\"]+)\"").matcher(svg);
            if (!path.find()) throw new IOException("Outcast SVG has no path");
            Matcher tokens = Pattern.compile("[MLZ]|-?\\d+(?:\\.\\d+)?").matcher(path.group(1));
            List<String> values = new ArrayList<>();
            while (tokens.find()) values.add(tokens.group());
            Path2D outline = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            char command = ' ';
            for (int i = 0; i < values.size(); i++) {
                String value = values.get(i);
                if (value.equals("M") || value.equals("L")) {
                    command = value.charAt(0);
                    continue;
                }
                if (value.equals("Z")) {
                    outline.closePath();
                    command = ' ';
                    continue;
                }
                if (command != 'M' && command != 'L') throw new IOException("Unexpected SVG path command");
                if (i + 1 >= values.size()) throw new IOException("Incomplete SVG coordinate pair");
                double x = Double.parseDouble(value);
                double y = Double.parseDouble(values.get(++i));
                if (command == 'M') {
                    outline.moveTo(x, y);
                    command = 'L';
                } else outline.lineTo(x, y);
            }
            return outline;
        } catch (IOException | NumberFormatException error) {
            throw new IllegalStateException("Could not load Outcast vector logo", error);
        }
    }
}
