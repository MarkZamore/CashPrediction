package ru.cashprediction.fx.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.geometry.Orientation;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.StoreStatus;
import ru.cashprediction.core.util.DateFormats;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Строка состояния главного окна: файл плана, признак несохранённых изменений, состояние хранилищ снимка сессии,
 * число строк таблицы и горизонт плана.
 *
 * <p>Состояние хранилищ приходит от рекордера ({@code SessionRecorder.addStatusListener}) и выглядит так:
 * «Реестр ✓ 10:15:30 | XML ✓ 10:15:31». При ошибке записи вместо галочки крестик, причина — во всплывающей
 * подсказке.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
public final class StatusBar extends HBox {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Label file = new Label();
    private final Label dirty = new Label();
    private final Label stores = new Label("Снимок сеанса: ещё не записан");
    private final Label rows = new Label();
    private final Label horizon = new Label();
    private final Label message = new Label();

    /** Создаёт строку состояния. */
    public StatusBar() {
        super(8);
        setPadding(new Insets(3, 8, 3, 8));
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("status-bar");
        dirty.getStyleClass().add("status-dirty");
        message.getStyleClass().add("status-message");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(file, dirty, separator(), stores, separator(), message, spacer, rows, separator(), horizon);
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        stores.setTooltip(new Tooltip("Куда записывается снимок сеанса для восстановления после сбоя"));
    }

    private static Separator separator() {
        return new Separator(Orientation.VERTICAL);
    }

    /**
     * Обновляет сведения о плане.
     *
     * @param document  документ плана
     * @param rowCount  число строк событий в таблице
     */
    public void update(PlanDocument document, int rowCount) {
        Plan plan = document.plan();
        file.setText(document.file().map(f -> "Файл: " + f.getFileName()).orElse("Файл: не сохранён"));
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        file.setTooltip(new Tooltip(document.file().map(Object::toString)
                .orElse("План ещё не записан на диск: Ctrl+S сохранит его в CashMemory")));
        dirty.setText(document.isDirty() ? "● есть несохранённые изменения" : document.file().isPresent() ? "сохранён" : "без изменений");
        rows.setText("Строк: " + rowCount);
        horizon.setText("Горизонт: " + plan.horizon().label() + " (до " + DateFormats.ru(plan.endDate()) + ")");
    }

    /**
     * Показывает состояние хранилищ снимка.
     *
     * @param statuses последние события хранилищ (порядок: реестр, XML)
     * @param enabled  включена ли запись сеанса (во втором экземпляре программы — нет)
     */
    public void setStores(Collection<StoreStatus> statuses, boolean enabled) {
        if (!enabled) {
            stores.setText("Запись сеанса отключена (второй экземпляр)");
            // JavaFX: Tooltip → Swing: setToolTipText → Web: title
            stores.setTooltip(new Tooltip("Снимки сеанса пишет первый запущенный экземпляр CashPrediction"));
            return;
        }
        if (statuses.isEmpty()) {
            stores.setText("Снимок сеанса: ещё не записан");
            return;
        }
        List<String> parts = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (StoreStatus status : statuses) {
            String title = switch (status.storeId()) {
                case "registry" -> "Реестр";
                case "xml" -> "XML";
                default -> status.storeId();
            };
            parts.add(title + (status.ok() ? " ✓ " + time(status.savedAt()) : " ✗"));
            details.add(title + ": " + (status.ok() ? "записано " + time(status.savedAt()) : "ошибка")
                    + (status.message().isBlank() ? "" : " — " + status.message()));
        }
        stores.setText(String.join(" | ", parts));
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        stores.setTooltip(new Tooltip(String.join("\n", details)));
    }

    /**
     * Короткое сообщение (например, «Настройки не сохранены: …»).
     *
     * @param text текст; пустая строка убирает сообщение
     */
    public void showMessage(String text) {
        message.setText(text == null ? "" : text);
    }

    /**
     * Текст состояния хранилищ (для самотеста и отладки).
     *
     * @return текст как на экране
     */
    public String storesText() {
        return stores.getText();
    }

    private static String time(Instant instant) {
        return instant == null ? "—" : TIME.format(instant.atZone(ZoneId.systemDefault()));
    }
}
