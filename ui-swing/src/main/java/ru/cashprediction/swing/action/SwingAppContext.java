package ru.cashprediction.swing.action;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import javax.swing.JFrame;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.io.CashMemoryLayout;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.SessionRecorder;

/**
 * Что фасад команд {@link SwingActions} знает о приложении. Реализуется контроллером главного окна Swing-клиента.
 *
 * <p>Интерфейс отделяет диалоги и команды от конкретного главного окна: команды можно собрать и проверить раньше
 * самого окна, а главное окно не зависит от устройства диалогов. Это зеркало {@code FxAppContext} JavaFX-клиента,
 * поэтому команды трёх клиентов устроены одинаково.</p>
 *
 * <p>Все методы вызываются в потоке диспетчеризации событий Swing (EDT).</p>
 */
public interface SwingAppContext {

    /**
     * Главное окно — владелец диалогов и окон выбора файла.
     *
     * @return главное окно; может быть ещё не показано или {@code null}, пока его нет (диалог восстановления до старта)
     */
    JFrame owner();

    /**
     * Открытый документ плана.
     *
     * @return документ; все изменения плана идут только через него
     */
    PlanDocument document();

    /**
     * Рекордер сессии текущего сеанса.
     *
     * @return рекордер (в режиме второго экземпляра отключён, но существует)
     */
    SessionRecorder recorder();

    /**
     * Раскладка папки CashMemory.
     *
     * @return раскладка
     */
    CashMemoryLayout layout();

    /**
     * Текущие настройки приложения.
     *
     * @return настройки
     */
    AppSettings settings();

    /**
     * Меняет настройки; реализация сохраняет их в {@code settings.md} с задержкой.
     *
     * @param change функция «старые настройки → новые»
     */
    void updateSettings(UnaryOperator<AppSettings> change);

    /**
     * Сегодняшняя дата.
     *
     * @return сегодня
     */
    LocalDate today();

    /**
     * Делает план открытым документом: {@code document().replace(...)}, заголовок окна, сброс выделения.
     *
     * <p>Фасад сам обновляет «последний/недавние планы» в настройках и запоминает время изменения файла для
     * обнаружения правки снаружи, поэтому реализации этого делать не нужно.</p>
     *
     * @param plan        план
     * @param file        файл плана или {@code null}, если план ещё не сохранён
     * @param dirty       есть ли несохранённые изменения
     * @param diagnostics диагностика чтения файла (может быть пустой)
     */
    void openPlanDocument(Plan plan, Path file, boolean dirty, List<Diagnostic> diagnostics);

    /**
     * Выделенная строка таблицы прогноза.
     *
     * @return идентификатор строки ({@code start}, {@code r2@2026-10-01}, {@code t1}, {@code whatif@...}) или пусто
     */
    Optional<String> selectedRowId();

    /**
     * Открыватель быстрой правки суммы (для восстановления {@code QUICK_EDIT_POPUP} и двойного щелчка по сумме).
     *
     * @return открыватель
     */
    QuickEditOpener quickEdit();

    /**
     * Показывает короткое сообщение в строке состояния главного окна («План сохранён: …»).
     *
     * <p>По умолчанию ничего не делает: до появления главного окна показывать сообщение негде.</p>
     *
     * @param message текст сообщения
     */
    default void showStatus(String message) {
        // Строки состояния нет — сообщение не нужно никуда выводить.
    }

    /**
     * Сохраняет текущий график баланса в PNG («Файл → Сохранить график PNG…», только настольные клиенты).
     *
     * <p>Реализация по умолчанию сообщает, что графика нет: команда может быть вызвана раньше, чем главное
     * окно построит компонент графика.</p>
     *
     * @param file файл, выбранный пользователем
     * @throws IOException если записать изображение не удалось или графика нет
     */
    default void writeChartPng(Path file) throws IOException {
        throw new IOException("График ещё не построен");
    }
}
