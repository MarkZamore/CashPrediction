package ru.cashprediction.fx;

import javafx.scene.Node;
import javafx.stage.Window;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.fx.action.FxActions;

import java.time.LocalDate;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Что компоненты главного окна (меню, панель инструментов, таблица, график, сводка) знают об оболочке приложения.
 *
 * <p>Интерфейс нужен, чтобы меню и представления не зависели от конкретного {@link AppController}: они получают
 * фасад команд, документ плана и несколько действий самого окна (фокус на фильтр, переход к дате в таблице, выход).
 * Реализуется {@link AppController}.</p>
 *
 * <p>Все методы вызываются только в FX Application Thread.</p>
 */
public interface ShellContext {

    /**
     * Фасад команд: всё, что открывают меню и контекстные меню.
     *
     * @return фасад команд
     */
    FxActions actions();

    /**
     * Документ открытого плана.
     *
     * @return документ; план меняется только через него
     */
    PlanDocument document();

    /**
     * Рекордер сессии: компоненты сообщают ему об изменениях ({@code touch}) и регистрируют всплывающие окна.
     *
     * @return рекордер текущего сеанса
     */
    SessionRecorder recorder();

    /**
     * Текущие настройки приложения.
     *
     * @return настройки
     */
    AppSettings settings();

    /**
     * Меняет настройки; оболочка сохраняет их в {@code settings.md} с задержкой.
     *
     * @param change функция «старые настройки → новые»
     */
    void updateSettings(UnaryOperator<AppSettings> change);

    /**
     * Меняет параметры вида ({@code PlanDocument.setViewState}).
     *
     * @param change функция «старый вид → новый»
     */
    void updateView(UnaryOperator<ViewState> change);

    /**
     * Сегодняшняя дата.
     *
     * @return сегодня
     */
    LocalDate today();

    /**
     * Выделенная строка таблицы прогноза.
     *
     * @return идентификатор строки или пусто
     */
    Optional<String> selectedRowId();

    /**
     * Главное окно — владелец всплывающих окон.
     *
     * @return главное окно
     */
    Window ownerWindow();

    /** Переводит фокус в поле фильтра (Ctrl+F). */
    void focusFilter();

    /**
     * Узел графика для «Сохранить график PNG…».
     *
     * @return узел графика
     */
    Node chartNode();

    /**
     * Переключает вид на таблицу и выделяет первую строку не раньше даты.
     *
     * @param date дата
     */
    void showTableFrom(LocalDate date);

    /** Выход из программы по обычному пути: снимок, вопрос о несохранённых изменениях, настройки, маркер закрытия. */
    void requestExit();
}
