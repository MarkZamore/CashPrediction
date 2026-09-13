package ru.cashprediction.fx.popup;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.PopupControl;
import ru.cashprediction.core.forecast.DailyPoint;

import java.util.List;
import java.util.Objects;

/**
 * Всплывающая подсказка карточки сводки: заголовок, пояснение и спарклайн — маленький график баланса
 * на ближайшие месяцы.
 *
 * <p>Это {@link PopupControl}: всплывающий элемент управления, внешний вид которого задаёт отдельный
 * {@link SparklineSkin}. Данные — свойство {@link #dataProperty()}; скин перерисовывается при их смене, поэтому одно
 * окно переиспользуется для всех карточек.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
// JavaFX: PopupControl → Swing: SwingPopupControl (JWindow + панель с Border) → Web: .popover div
public final class SparklinePopupControl extends PopupControl {

    /**
     * Что показать в подсказке.
     *
     * @param title    крупный заголовок («Через 3 месяца»)
     * @param subtitle пояснение под заголовком
     * @param points   точки баланса по дням (уже прорежённые {@code ChartSeries.sample})
     * @param currency валюта для подписей минимума и максимума
     */
    public record Data(String title, String subtitle, List<DailyPoint> points, String currency) {

        /** Нормализует пустые значения. */
        public Data {
            title = Objects.requireNonNullElse(title, "");
            subtitle = Objects.requireNonNullElse(subtitle, "");
            points = points == null ? List.of() : List.copyOf(points);
            currency = Objects.requireNonNullElse(currency, "");
        }
    }

    private final ObjectProperty<Data> data = new SimpleObjectProperty<>(this, "data",
            new Data("", "", List.of(), ""));

    /** Создаёт подсказку со своим скином. */
    public SparklinePopupControl() {
        getStyleClass().add("sparkline-popup");
        // Подсказка не должна перехватывать события мыши у карточки и главного окна.
        setAutoHide(true);
        setConsumeAutoHidingEvents(false);
        setSkin(new SparklineSkin(this));
    }

    /**
     * Данные подсказки.
     *
     * @return свойство данных
     */
    public ObjectProperty<Data> dataProperty() {
        return data;
    }

    /**
     * Меняет данные подсказки.
     *
     * @param value новые данные
     */
    public void setData(Data value) {
        data.set(Objects.requireNonNull(value, "value"));
    }

    /**
     * Текущие данные.
     *
     * @return данные
     */
    public Data getData() {
        return data.get();
    }
}
