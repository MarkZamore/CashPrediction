package ru.cashprediction.swing.action;

import java.awt.Window;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import javax.swing.JFrame;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.swing.dialog.SwingDialogHost;
import ru.cashprediction.swing.dialog.SwingHostedWindow;

/**
 * Общая основа групп команд (файл, правка, инструменты, восстановление, справка): доступ к документу, хосту
 * диалогов, рекордеру и единый способ показать окно по {@link OpenRequest}.
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
abstract class ActionSupport {

    /** Общие объекты всех групп команд. */
    protected final ActionShared shared;

    /**
     * Создаёт группу команд.
     *
     * @param shared общие объекты команд
     */
    protected ActionSupport(ActionShared shared) {
        this.shared = Objects.requireNonNull(shared, "shared");
    }

    /**
     * Контекст приложения.
     *
     * @return контекст
     */
    protected SwingAppContext context() {
        return shared.context();
    }

    /**
     * Документ открытого плана.
     *
     * @return документ
     */
    protected PlanDocument document() {
        return shared.context().document();
    }

    /**
     * Текущий план.
     *
     * @return план
     */
    protected Plan plan() {
        return document().plan();
    }

    /**
     * Хост диалогов.
     *
     * @return хост
     */
    protected SwingDialogHost host() {
        return shared.host();
    }

    /**
     * Рекордер сессии.
     *
     * @return рекордер
     */
    protected SessionRecorder recorder() {
        return shared.context().recorder();
    }

    /**
     * Сегодняшняя дата.
     *
     * @return сегодня
     */
    protected LocalDate today() {
        return shared.context().today();
    }

    /**
     * Помощник сообщений.
     *
     * @return помощник
     */
    protected Alerts alerts() {
        return shared.alerts();
    }

    /**
     * Главное окно — родитель окон выбора файла.
     *
     * @return главное окно или {@code null}, если его ещё нет
     */
    protected JFrame frame() {
        return shared.context().owner();
    }

    /**
     * Команды меню «Файл» (нужны «Правке» для переименования файла вслед за планом).
     *
     * @return команды «Файл»
     */
    protected FileActions files() {
        return shared.files();
    }

    /**
     * Имя плана для настроек: имя файла, если он лежит в CashMemory, иначе полный путь.
     *
     * @param file файл плана
     * @return строка для «Последний план» и «Недавние планы»
     */
    protected String settingsName(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path dir = context().layout().dir().toAbsolutePath().normalize();
        return dir.equals(absolute.getParent()) ? absolute.getFileName().toString() : absolute.toString();
    }

    /**
     * Сообщение в строке состояния главного окна.
     *
     * @param message текст
     */
    protected void status(String message) {
        context().showStatus(message);
    }

    /**
     * Меняет состояние вида ({@code ViewState}) документа.
     *
     * @param change функция «старый вид → новый»
     */
    protected void updateView(UnaryOperator<ViewState> change) {
        document().setViewState(change.apply(document().viewState()));
    }

    /**
     * Изменяет план одним шагом истории; ошибку модели показывает пользователю вместо аварийного завершения.
     *
     * @param description описание для «Отменить …»
     * @param change      изменение
     * @return {@code true}, если изменение применено без исключения
     */
    protected boolean edit(String description, UnaryOperator<Plan> change) {
        try {
            document().edit(description, change);
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            alerts().error("Изменение не применено", Objects.requireNonNullElse(e.getMessage(), e.toString()));
            return false;
        }
    }

    /**
     * Строка прогноза по идентификатору.
     *
     * @param rowId идентификатор строки или {@code null}
     * @return строка или пусто, если её нет или прогноз не рассчитывается
     */
    protected Optional<ForecastRow> findRow(String rowId) {
        if (rowId == null || rowId.isBlank()) {
            return Optional.empty();
        }
        try {
            return document().forecast().findRow(rowId);
        } catch (IllegalStateException e) {
            // Слишком длинный горизонт: прогноза нет — значит, и строки нет.
            return Optional.empty();
        }
    }

    /**
     * Выделенная строка таблицы прогноза.
     *
     * @return строка или пусто
     */
    protected Optional<ForecastRow> selectedRow() {
        return context().selectedRowId().flatMap(this::findRow);
    }

    /**
     * Запрос открытия окна по команде пользователя.
     *
     * @return запрос с владельцем — верхним модальным окном
     */
    protected OpenRequest interactive() {
        return OpenRequest.interactive(host());
    }

    /**
     * Окно-владелец для запроса.
     *
     * @param request запрос
     * @return окно-владелец
     */
    protected Window owner(OpenRequest request) {
        return request.ownerWindow() != null ? request.ownerWindow() : host().ownerWindow(request.ownerId());
    }

    /**
     * Показывает окно: при восстановлении сначала переносит в него сохранённые значения (до показа, чтобы
     * пользователь не увидел «пустую» форму).
     *
     * @param window  окно
     * @param request запрос
     */
    protected void show(SwingHostedWindow window, OpenRequest request) {
        if (request.state() != null) {
            window.applyState(request.state());
        }
        host().show(window, request.onShown(), request.onFailed());
    }

    /**
     * Сообщает, что окно открыть нельзя: пользователю — сообщением, восстановлению — колбэком.
     *
     * @param request запрос
     * @param header  что не удалось
     * @param reason  причина
     */
    protected void cannotOpen(OpenRequest request, String header, String reason) {
        if (request.interactive()) {
            alerts().info(header, reason);
        }
        request.onFailed().accept(header + ": " + reason);
    }
}
