package ru.cashprediction.fx.action;

import javafx.stage.Stage;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.io.CashMemoryLayout;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.SessionRecorder;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Что фасад команд {@link FxActions} знает о приложении. Реализуется контроллером главного окна.
 *
 * <p>Интерфейс отделяет диалоги и команды от конкретного главного окна: команды можно собрать и проверить
 * раньше самого окна, а главное окно не зависит от устройства диалогов.</p>
 *
 * <p>Все методы вызываются в FX Application Thread.</p>
 */
public interface FxAppContext {

    /**
     * Главное окно — владелец диалогов.
     *
     * @return сцена главного окна (может быть ещё не показана, например при диалоге восстановления)
     */
    Stage owner();

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
     * <p>Фасад сам обновляет «последний/недавние планы» в настройках и запоминает время изменения файла
     * для обнаружения правки снаружи, поэтому реализации этого делать не нужно.</p>
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
     * Открыватель быстрой правки суммы (для восстановления {@code QUICK_EDIT_POPUP}).
     *
     * @return открыватель
     */
    QuickEditOpener quickEdit();
}
