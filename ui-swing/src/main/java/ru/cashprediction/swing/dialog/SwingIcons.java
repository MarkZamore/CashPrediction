package ru.cashprediction.swing.dialog;

import java.awt.Image;
import java.net.URL;
import java.util.List;
import javax.swing.ImageIcon;

/**
 * Значок приложения для окон без владельца (диалог восстановления до главного окна, окно ошибки).
 *
 * <p>Окна с владельцем наследуют значок главного окна, поэтому значок нужен только «сиротам».
 * Ресурс читается классом этого же модуля: в модульном режиме ресурсы модуля инкапсулированы.</p>
 */
public final class SwingIcons {

    /** Путь к значку внутри модуля. */
    private static final String ICON_RESOURCE = "/ru/cashprediction/swing/icon.png";

    private SwingIcons() {
    }

    /**
     * Значки приложения для {@code Window.setIconImages}.
     *
     * @return список из одного значка или пустой список, если ресурс не найден
     */
    public static List<Image> appIcons() {
        URL url = SwingIcons.class.getResource(ICON_RESOURCE);
        return url == null ? List.of() : List.of(new ImageIcon(url).getImage());
    }
}
