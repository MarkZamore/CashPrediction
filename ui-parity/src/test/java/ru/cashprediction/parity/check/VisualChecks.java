package ru.cashprediction.parity.check;

import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import ru.cashprediction.core.ui.dump.UiDump;

/** Блокирующие проверки изображения по цветам областей и координатам дампа (§6.3). */
public final class VisualChecks {
    private VisualChecks() { }
    /** Проверяет цвет центра области по CIE76; предел ΔE строго меньше 6. */
    public static void regionColor(byte[] png, UiDump.Box box, int expectedRgb) throws IOException {
        if (!Double.isFinite(box.x()) || !Double.isFinite(box.y()) || !Double.isFinite(box.width())
                || !Double.isFinite(box.height())) throw new IllegalArgumentException("Non-finite region");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) throw new IOException("Invalid PNG");
        int x = (int) Math.floor(box.x() + box.width() / 2), y = (int) Math.floor(box.y() + box.height() / 2);
        if (box.width() <= 0 || box.height() <= 0 || x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight())
            throw new IllegalArgumentException("Region outside image");
        double difference = deltaE(image.getRGB(x, y), expectedRgb);
        if (difference >= 6) throw new AssertionError("Region color deltaE=" + difference);
    }
    /** Проверяет все координаты рамки с заданным допуском (4 px для позиций, 3 px для базовых линий). */
    public static void position(UiDump.Box expected, UiDump.Box actual, double tolerance) {
        if (tolerance < 0 || !Double.isFinite(tolerance)) throw new IllegalArgumentException("tolerance");
        double[] a = {expected.x(), expected.y(), expected.width(), expected.height()};
        double[] b = {actual.x(), actual.y(), actual.width(), actual.height()};
        for (int i = 0; i < a.length; i++) if (!Double.isFinite(a[i]) || !Double.isFinite(b[i])
                || Math.abs(a[i] - b[i]) > tolerance) throw new AssertionError("Region position component " + i);
    }
    /** @return расстояние CIE76 между двумя sRGB цветами */
    public static double deltaE(int a, int b) {
        double[] left = lab(a), right = lab(b);
        return Math.sqrt(Math.pow(left[0] - right[0], 2) + Math.pow(left[1] - right[1], 2)
                + Math.pow(left[2] - right[2], 2));
    }
    /** Преобразует sRGB в CIELAB с белой точкой D65. */
    private static double[] lab(int rgb) {
        double r = linear((rgb >> 16) & 255), g = linear((rgb >> 8) & 255), b = linear(rgb & 255);
        double x = curve((.4124564 * r + .3575761 * g + .1804375 * b) / .95047);
        double y = curve(.2126729 * r + .7151522 * g + .0721750 * b);
        double z = curve((.0193339 * r + .1191920 * g + .9503041 * b) / 1.08883);
        return new double[] {116 * y - 16, 500 * (x - y), 200 * (y - z)};
    }
    /** Убирает гамму sRGB. */
    private static double linear(int component) {
        double v = component / 255.0; return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);
    }
    /** Применяет передаточную функцию CIELAB. */
    private static double curve(double v) { return v > 216.0 / 24389 ? Math.cbrt(v) : v * 24389 / 3132 + 4.0 / 29; }
}
