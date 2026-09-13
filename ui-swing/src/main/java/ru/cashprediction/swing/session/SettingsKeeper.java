package ru.cashprediction.swing.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import javax.swing.Timer;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.markdown.SettingsMarkdown;

/**
 * Хранитель настроек приложения: держит текущие {@link AppSettings} и записывает их в {@code CashMemory/settings.md}.
 *
 * <p>Запись отложенная: изменения (вид, период, фильтры, хранилище восстановления, автосохранение, недавние планы)
 * часто идут пачкой — например, щелчки по флажкам меню «Вид», — поэтому файл пишется один раз через 700 мс после
 * последнего изменения ({@code javax.swing.Timer}). При выходе вызывается {@link #writeNow()}.</p>
 *
 * <p>Настройки — единственное место на диске для этих значений (общие для трёх клиентов); в реестр они не пишутся.
 * Класс используется только в потоке EDT.</p>
 */
public final class SettingsKeeper {

    /** Задержка отложенной записи, мс. */
    public static final int WRITE_DELAY_MS = 700;

    private final Path file;
    private final Timer timer;
    private AppSettings settings;
    private AppSettings written;
    private String lastError;

    /**
     * Создаёт хранитель.
     *
     * @param file    файл {@code settings.md}
     * @param initial настройки, прочитанные при запуске
     */
    public SettingsKeeper(Path file, AppSettings initial) {
        this.file = Objects.requireNonNull(file, "file");
        this.settings = Objects.requireNonNull(initial, "initial");
        this.written = initial;
        this.timer = new Timer(WRITE_DELAY_MS, e -> writeNow());
        timer.setRepeats(false);
    }

    /**
     * Текущие настройки.
     *
     * @return настройки
     */
    public AppSettings settings() {
        return settings;
    }

    /**
     * Меняет настройки и откладывает запись файла.
     *
     * @param change функция «старые настройки → новые»
     * @return {@code true}, если настройки действительно изменились
     */
    public boolean update(UnaryOperator<AppSettings> change) {
        AppSettings next = Objects.requireNonNull(change.apply(settings), "settings");
        if (next.equals(settings)) {
            return false;
        }
        settings = next;
        timer.restart();
        return true;
    }

    /**
     * Записывает настройки немедленно, если они изменились с последней записи или файла ещё нет.
     */
    public void writeNow() {
        timer.stop();
        if (settings.equals(written) && Files.exists(file)) {
            return;
        }
        try {
            SettingsMarkdown.save(file, settings);
            written = settings;
            lastError = null;
        } catch (IOException | RuntimeException e) {
            // Настройки не критичны: программа работает дальше, ошибка видна в строке состояния.
            lastError = Objects.requireNonNullElse(e.getMessage(), e.toString());
        }
    }

    /**
     * Причина последней неудачной записи.
     *
     * @return текст ошибки или пусто
     */
    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }
}
