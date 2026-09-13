package ru.cashprediction.fx.popup;

import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

import java.time.LocalDate;
import java.util.List;

/**
 * Карточка дня на графике: дата, баланс на конец дня и события этого дня.
 *
 * <p>Наследник «голого» {@link PopupWindow}, без готового содержимого, в отличие от {@code Popup} и
 * {@code PopupControl}. Карточка кладётся в детей корня сцены {@code PopupWindow} (в JavaFX 25 это {@link Pane},
 * в старых версиях — {@link Group}). Окно
 * само закрывается при щелчке мимо ({@code autoHide}); график прячет его, когда указатель уходит с области
 * построения.</p>
 *
 * <p>Не восстанавливается после сбоя: это подсказка при наведении, а не окно с введёнными данными.
 * Только FX Application Thread.</p>
 */
// JavaFX: PopupWindow → Swing: JWindow (undecorated, setFocusableWindowState(false)) → Web: absolute div, autoHide по pointerdown
public final class DayCardPopupWindow extends PopupWindow {

    /** Больше строк событий карточка не показывает, чтобы не закрыть график. */
    private static final int MAX_EVENTS = 8;

    private final VBox card = new VBox(3);
    private LocalDate shownDate;

    /** Создаёт пустую карточку. */
    public DayCardPopupWindow() {
        card.setPadding(new Insets(8));
        card.setStyle("-fx-background-color: #ffffff; -fx-border-color: #8c959f; -fx-border-radius: 4;"
                + " -fx-background-radius: 4; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.25), 8, 0, 0, 2);");
        card.setMouseTransparent(true);
        // Содержимое PopupWindow задаётся через корень его сцены: публичного getContent() у него нет.
        // В старых версиях JavaFX корень — Group, в JavaFX 25 — Pane; оба дают доступ к детям, остальное — замена корня.
        Parent root = getScene().getRoot();
        if (root instanceof Pane pane) {
            pane.getChildren().add(card);
        } else if (root instanceof Group group) {
            group.getChildren().add(card);
        } else {
            getScene().setRoot(card);
        }
        setAutoHide(true);
        setConsumeAutoHidingEvents(false);
        setAutoFix(true);
    }

    /**
     * Показывает (или обновляет) карточку дня рядом с указателем.
     *
     * @param owner    окно графика
     * @param screenX  экранная координата X указателя
     * @param screenY  экранная координата Y указателя
     * @param date     день
     * @param balance  баланс на конец дня
     * @param events   события дня (строки прогноза)
     * @param currency валюта плана
     * @param cushion  подушка безопасности (баланс ниже неё выделяется)
     */
    public void showDay(Window owner, double screenX, double screenY, LocalDate date, Money balance,
                        List<ForecastRow> events, String currency, Money cushion) {
        if (!date.equals(shownDate) || !isShowing()) {
            fill(date, balance, events, currency, cushion);
            shownDate = date;
        }
        if (isShowing()) {
            setX(screenX + 16);
            setY(screenY + 16);
        } else {
            show(owner, screenX + 16, screenY + 16);
        }
    }

    /**
     * День, который показан сейчас.
     *
     * @return дата или {@code null}
     */
    public LocalDate shownDate() {
        return isShowing() ? shownDate : null;
    }

    private void fill(LocalDate date, Money balance, List<ForecastRow> events, String currency, Money cushion) {
        card.getChildren().clear();
        Label title = new Label(DateFormats.ru(date) + ", " + RuText.weekdayFull(date.getDayOfWeek()));
        title.setStyle("-fx-font-weight: bold;");
        Label total = new Label("Баланс на конец дня: " + balance.format(currency));
        String color = balance.isNegative() ? "#b3261e" : balance.isLessThan(cushion) ? "#8a5300" : "#1f2328";
        total.setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
        card.getChildren().addAll(title, total);
        List<ForecastRow> shown = events.stream().filter(r -> r.origin() != ru.cashprediction.core.forecast.Origin.START).toList();
        if (shown.isEmpty()) {
            Label none = new Label("Событий нет");
            none.setStyle("-fx-text-fill: #57606a;");
            card.getChildren().add(none);
            return;
        }
        for (int i = 0; i < Math.min(MAX_EVENTS, shown.size()); i++) {
            ForecastRow row = shown.get(i);
            String amount = row.flags().skipped() ? "пропущено" : row.amount().formatSigned();
            Label line = new Label(amount + "  " + row.title());
            line.setStyle("-fx-text-fill: " + (row.flags().skipped() ? "#57606a" : row.isIncome() ? "#1b7f3b" : "#b3261e") + ";");
            card.getChildren().add(line);
        }
        if (shown.size() > MAX_EVENTS) {
            card.getChildren().add(new Label("… и ещё " + (shown.size() - MAX_EVENTS)));
        }
    }
}
