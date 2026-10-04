import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import java.util.Locale;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Генератор общих прозрачных значков из геометрии, без шрифтов и сторонних библиотек.
 * Компилируется JDK 25 вместе с ColorToken; графические зависимости остаются вне ядра.
 * Палитра вариантов точно соответствует ColorToken.hex(); геометрия всех цветов одинакова.
 */
public final class GenerateUiIcons {
    private static final String[] IDS = {
        "undo", "redo", "chevron-down", "chevron-right", "close", "check", "dot",
        "previous", "next", "calendar", "up", "edit", "arrow-right", "swap", "list",
        "delta", "ruble", "settings", "refresh", "target", "download", "restore",
        "info", "warning", "error", "question", "chevron-left", "folder", "search"
    };
    private static final ColorToken[] TOKENS = {
        ColorToken.ACCENT, ColorToken.WHATIF, ColorToken.EXPENSE, ColorToken.INCOME,
        ColorToken.TEXT_PRIMARY, ColorToken.TEXT_MUTED, ColorToken.WARN,
        ColorToken.TOOLTIP_TEXT, ColorToken.TEXT_PAST
    };

    private GenerateUiIcons() {
    }

    /**
     * Записывает интерфейсные PNG в единственный общий каталог ресурсов.
     * @param args необязательный путь выходного каталога
     * @throws IOException при ошибке записи
     */
    public static void main(String[] args) throws IOException {
        Path output = Path.of(args.length == 0
                ? "core/src/main/resources/ru/cashprediction/core/ui/icons" : args[0]);
        Files.createDirectories(output);
        for (String id : IDS) {
            write(output, id, id, defaultColor(id));
            for (ColorToken token : TOKENS) {
                write(output, id + "-" + token.name().toLowerCase(Locale.ROOT), id,
                        Integer.parseInt(token.hex().substring(1), 16));
            }
        }
        System.out.println("Generated " + IDS.length * (TOKENS.length + 1) + " shared interface PNG files");
    }

    /** Рисует одну и ту же геометрию выбранным цветом только во время подготовки ресурсов. */
    private static void write(Path output, String filename, String id, int rgb) throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(4, 4);
            g.setColor(new Color(rgb));
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            draw(g, id);
        } finally {
            g.dispose();
        }
        if (!ImageIO.write(image, "png", output.resolve(filename + ".png").toFile())) {
            throw new IOException("PNG writer unavailable");
        }
    }

    /** Сохраняет прежние цвета исходных значков независимо от палитры вариантов. */
    private static int defaultColor(String id) {
        return switch (id) {
            case "check" -> 0x1B7F3B;
            case "folder", "warning" -> 0x8A5300;
            case "target", "info", "question" -> 0x1F6FEB;
            case "error" -> 0xB3261E;
            default -> 0x1F2328;
        };
    }

    private static void draw(Graphics2D g, String id) {
        switch (id) {
            case "chevron-down" -> line(g, 4, 6, 8, 10, 12, 6);
            case "chevron-right" -> line(g, 6, 4, 10, 8, 6, 12);
            case "chevron-left" -> line(g, 10, 4, 6, 8, 10, 12);
            case "previous" -> fill(g, 11, 3, 4, 8, 11, 13);
            case "next" -> fill(g, 5, 3, 12, 8, 5, 13);
            case "close" -> cross(g);
            case "check" -> {
                line(g, 3, 8, 6, 11, 13, 4);
            }
            case "dot" -> g.fill(new Ellipse2D.Double(5, 5, 6, 6));
            case "up" -> {
                line(g, 8, 13, 8, 3);
                line(g, 4, 7, 8, 3, 12, 7);
            }
            case "arrow-right" -> {
                line(g, 2, 8, 13, 8);
                line(g, 9, 4, 13, 8, 9, 12);
            }
            case "swap" -> {
                line(g, 2, 5, 13, 5, 10, 2);
                line(g, 14, 11, 3, 11, 6, 14);
            }
            case "list" -> {
                for (int y = 4; y <= 12; y += 4) {
                    line(g, 3, y, 13, y);
                }
            }
            case "delta" -> line(g, 8, 2, 14, 13, 2, 13, 8, 2);
            case "calendar" -> {
                g.draw(new RoundRectangle2D.Double(2.5, 4, 11, 9.5, 2, 2));
                line(g, 3, 7, 13, 7);
                line(g, 5, 2.5, 5, 5);
                line(g, 11, 2.5, 11, 5);
                for (int y = 9; y <= 11; y += 2) {
                    for (int x = 5; x <= 11; x += 3) {
                        g.fill(new Ellipse2D.Double(x - .5, y - .5, 1, 1));
                    }
                }
            }
            case "edit" -> {
                line(g, 3, 10, 10, 3, 13, 6, 6, 13, 2, 14, 3, 10);
                line(g, 8.5, 4.5, 11.5, 7.5);
            }
            case "ruble" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(5, 14);
                p.lineTo(5, 2);
                p.lineTo(9, 2);
                p.curveTo(14, 2, 14, 8, 9, 8);
                p.lineTo(3, 8);
                g.draw(p);
                line(g, 3, 11, 10, 11);
            }
            case "folder" -> {
                line(g, 2, 13, 2, 4, 6, 4, 8, 6, 14, 6, 14, 13, 2, 13);
            }
            case "search" -> {
                g.draw(new Ellipse2D.Double(2.5, 2.5, 8, 8));
                line(g, 9.5, 9.5, 13.5, 13.5);
            }
            case "target" -> {
                g.draw(new Ellipse2D.Double(2, 2, 12, 12));
                g.draw(new Ellipse2D.Double(5, 5, 6, 6));
                g.fill(new Ellipse2D.Double(7, 7, 2, 2));
            }
            case "settings" -> {
                Path2D p = new Path2D.Double();
                for (int i = 0; i < 32; i++) {
                    double a = i * Math.PI / 16;
                    double r = i % 4 == 0 || i % 4 == 3 ? 6.5 : 5;
                    double x = 8 + r * Math.cos(a), y = 8 + r * Math.sin(a);
                    if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                }
                p.closePath();
                g.draw(p);
                g.draw(new Ellipse2D.Double(5.5, 5.5, 5, 5));
            }
            case "download" -> {
                line(g, 8, 2, 8, 10);
                line(g, 4, 7, 8, 11, 12, 7);
                line(g, 2.5, 11, 2.5, 14, 13.5, 14, 13.5, 11);
            }
            case "undo", "redo" -> {
                if (id.equals("redo")) {
                    g.translate(16, 0);
                    g.scale(-1, 1);
                }
                Path2D p = new Path2D.Double();
                p.moveTo(13, 12);
                p.curveTo(15, 5, 7, 3, 3, 7);
                g.draw(p);
                line(g, 3, 3, 3, 7, 7, 7);
            }
            case "refresh", "restore" -> {
                if (id.equals("restore")) {
                    g.translate(16, 0);
                    g.scale(-1, 1);
                }
                g.draw(new Arc2D.Double(3, 3, 10, 10, 55, 300, Arc2D.OPEN));
                line(g, 9, 2, 12, 4, 9, 6);
            }
            case "info" -> {
                circle(g);
                g.fill(new Ellipse2D.Double(7.2, 4, 1.6, 1.6));
                line(g, 8, 7, 8, 12);
            }
            case "warning" -> {
                line(g, 8, 2, 14, 13.5, 2, 13.5, 8, 2);
                line(g, 8, 6, 8, 9);
                g.fill(new Ellipse2D.Double(7.3, 11, 1.4, 1.4));
            }
            case "error" -> {
                circle(g);
                line(g, 5, 5, 11, 11);
                line(g, 11, 5, 5, 11);
            }
            case "question" -> {
                circle(g);
                Path2D p = new Path2D.Double();
                p.moveTo(5.5, 5.5);
                p.curveTo(5.5, 2.5, 12, 3, 10, 7);
                p.curveTo(9.5, 8, 8, 8, 8, 9.5);
                g.draw(p);
                g.fill(new Ellipse2D.Double(7.3, 11, 1.4, 1.4));
            }
            default -> throw new IllegalArgumentException(id);
        }
    }

    private static void circle(Graphics2D g) {
        g.draw(new Ellipse2D.Double(1.5, 1.5, 13, 13));
    }

    private static void cross(Graphics2D g) {
        line(g, 4, 4, 12, 12);
        line(g, 12, 4, 4, 12);
    }

    private static void line(Graphics2D g, double... points) {
        g.draw(path(points));
    }

    private static void fill(Graphics2D g, double... points) {
        Path2D p = path(points);
        p.closePath();
        g.fill(p);
    }

    private static Path2D path(double... points) {
        Path2D p = new Path2D.Double();
        p.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) {
            p.lineTo(points[i], points[i + 1]);
        }
        return p;
    }
}
