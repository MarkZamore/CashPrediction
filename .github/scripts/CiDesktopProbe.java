import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;

/** Проверяет именно логические размеры JDK на CI до дорогой сборки; не заменяет Robot gate. */
public final class CiDesktopProbe {
    /** Отказывает при headless, другой ОС или недостаточной ширине с учётом DPI Windows. */
    public static void main(String[] args) {
        if (!System.getProperty("os.name").startsWith("Windows") || GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Interactive Windows desktop required");
        }
        var size = Toolkit.getDefaultToolkit().getScreenSize();
        System.out.println("JDK logical desktop: " + size.width + "x" + size.height);
        if (size.width < 1200) {
            throw new IllegalStateException("Desktop too narrow for mandatory S4 gate");
        }
    }
}
