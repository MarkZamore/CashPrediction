package ru.cashprediction.fx.view;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.forecast.ChartSeries;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastSummary;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;
import ru.cashprediction.fx.ShellContext;
import ru.cashprediction.fx.menu.ForecastContextMenu;
import ru.cashprediction.fx.popup.SparklinePopupControl;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Панель сводки над таблицей или графиком — ответ на главный вопрос «сколько я накоплю».
 *
 * <p>Карточки: «Сейчас», «Через 1/3/6/12 мес.», «Минимум» (с датой), «Первый минус», «Средний итог/мес»,
 * «Цель: дата». Всё считается от опорной даты {@code anchor = max(начало плана, сегодня)} по сводке прогноза ядра.
 * Наведение на карточку показывает {@link SparklinePopupControl} со спарклайном баланса на её отрезок,
 * правая кнопка — контекстное меню карточки.</p>
 *
 * <p>Видимость панели — флаг «Вид → Панель сводки». Только FX Application Thread.</p>
 */
public final class SummaryBar extends HBox {

    /**
     * Карточка сводки: подписи и отрезок спарклайна.
     *
     * <p>{@code value} — короткое значение на карточке (суммы в целых единицах), {@code exact} — точное значение
     * для всплывающего спарклайна.</p>
     */
    private record Card(String title, String value, String exact, String subtitle, boolean negative, LocalDate date,
                        LocalDate sparkFrom, LocalDate sparkTo, boolean goal, String hint) {
    }

    private final ShellContext shell;
    private final SparklinePopupControl sparkline = new SparklinePopupControl();
    private ContextMenu contextMenu;

    /**
     * Создаёт панель.
     *
     * @param shell оболочка приложения
     */
    public SummaryBar(ShellContext shell) {
        this.shell = Objects.requireNonNull(shell, "shell");
        // Одна строка карточек, которые делят свободную ширину поровну; минимум карточки — ширина её заголовка и
        // значения (см. cardNode): с переносом (FlowPane) при ширине окна 1200 две карточки уезжали на вторую
        // строку и съедали высоту таблицы.
        setSpacing(6);
        setPadding(new Insets(8, 8, 4, 8));
        getStyleClass().add("summary-bar");
    }

    /** Перестраивает карточки по текущему прогнозу. */
    public void refresh() {
        PlanDocument document = shell.document();
        boolean visible = document.viewState().summaryPanel();
        setVisible(visible);
        setManaged(visible);
        getChildren().clear();
        if (!visible) {
            sparkline.hide();
            return;
        }
        Forecast forecast;
        try {
            forecast = document.forecast();
        } catch (IllegalStateException e) {
            Label problem = new Label("Сводка недоступна: " + e.getMessage());
            problem.getStyleClass().add("card-problem");
            getChildren().add(problem);
            return;
        }
        for (Card card : cards(forecast, document.plan())) {
            getChildren().add(cardNode(card, forecast, document.plan()));
        }
    }

    private List<Card> cards(Forecast forecast, Plan plan) {
        ForecastSummary s = forecast.summary();
        String cur = plan.currency();
        LocalDate anchor = s.anchor();
        LocalDate end = forecast.endDate();
        Money now = forecast.balanceAt(anchor);
        java.util.ArrayList<Card> cards = new java.util.ArrayList<>();
        // Суммы на карточках — в целых единицах («177 000 ₽»): при ширине окна 1200 девять карточек в одной строке
        // обрезали полные суммы с копейками многоточием («177 000,…»). Точные суммы — во всплывающем спарклайне.
        cards.add(new Card("Сейчас", whole(now, cur), now.format(cur), "на " + DateFormats.ru(anchor), now.isNegative(),
                anchor, anchor, min(anchor.plusMonths(3), end), false, "Баланс на конец опорного дня и три месяца вперёд"));
        for (int months : List.of(1, 3, 6, 12)) {
            LocalDate at = anchor.plusMonths(months);
            Optional<Money> value = s.balanceAfter(months);
            String title = "Через " + RuText.count(months, "месяц", "месяца", "месяцев");
            cards.add(value.map(v -> new Card(title, whole(v, cur), v.format(cur),
                            DateFormats.ru(at) + " · " + wholeSigned(v.minus(now)), v.isNegative(), at, anchor, at, false,
                            "Баланс через " + RuText.count(months, "месяц", "месяца", "месяцев")
                                    + " и изменение относительно «сейчас» (" + v.minus(now).formatSigned() + ")"))
                    // Короткое «-» вместо «за горизонтом»: длинное слово не помещалось в узкую карточку.
                    .orElse(new Card(title, "-", "за горизонтом", "за горизонтом, план до " + DateFormats.ru(end), false,
                            null, anchor, end, false, "Дата за пределами горизонта плана: увеличьте горизонт в меню «Вид»")));
        }
        cards.add(new Card("Минимум", whole(s.minBalance(), cur), s.minBalance().format(cur),
                DateFormats.ru(s.minBalanceDate()), s.minBalance().isNegative(), s.minBalanceDate(), anchor, end, false,
                "Самый низкий баланс до конца плана"));
        String firstMinus = s.firstNegativeDate().map(DateFormats::ru).orElse("нет");
        cards.add(new Card("Первый минус", firstMinus, firstMinus,
                s.firstBelowCushionDate().map(d -> "ниже подушки с " + DateFormats.ru(d)).orElse("подушка не нарушается"),
                s.firstNegativeDate().isPresent(), s.firstNegativeDate().or(s::firstBelowCushionDate).orElse(null),
                anchor, end, false, "Первый день с отрицательным балансом"));
        // Подпись карточки в сокращённых суммах: с полными суммами карточка была вдвое шире соседних и
        // панель сводки переносилась на вторую строку; точные суммы — в подсказке при наведении.
        cards.add(new Card("Средний итог/мес", wholeSigned(s.averageMonthlyNet()) + " " + cur,
                s.averageMonthlyNet().formatSigned() + " " + cur,
                "доходы " + compact(s.totalIncome()) + " · расходы " + compact(s.totalExpense()),
                s.averageMonthlyNet().isNegative(), null, anchor, end, false,
                "Сколько в среднем прибавляется за месяц; доходы " + s.totalIncome().format()
                        + ", расходы " + s.totalExpense().format()));
        if (plan.goal() == null) {
            cards.add(new Card("Цель: дата", "не задана", "не задана", "Инструменты → Калькулятор цели", false, null,
                    anchor, end, true, "Задайте цель в калькуляторе цели или в параметрах плана"));
        } else {
            // «нет» вместо «не достигается»: короткое значение помещается в карточку, пояснение — в подписи.
            String reach = s.goalReachDate().map(DateFormats::ru).orElse("нет");
            String goalText = "«" + plan.goal().title() + "» " + whole(plan.goal().target(), cur);
            cards.add(new Card("Цель: дата", reach, s.goalReachDate().map(DateFormats::ru).orElse("не достигается"),
                    s.goalReachDate().isPresent() ? goalText : "не достигается · " + goalText, s.goalReachDate().isEmpty(),
                    s.goalReachDate().orElse(null), anchor, end, true,
                    "Когда баланс впервые достигнет цели «" + plan.goal().title() + "» " + plan.goal().target().format(cur)));
        }
        return cards;
    }

    /**
     * Сумма в целых единицах валюты для карточки: {@code 177 000 ₽} (копейки округляются).
     *
     * @param money    сумма
     * @param currency обозначение валюты; пустое — без валюты
     * @return короткий текст
     */
    static String whole(Money money, String currency) {
        String digits = Money.ofMajor(Math.round(money.minor() / 100.0)).format();
        // format() всегда заканчивается на «,00» после округления до целых — отрезаем дробную часть.
        String text = digits.substring(0, digits.length() - 3);
        return currency == null || currency.isBlank() ? text : text + " " + currency;
    }

    /**
     * Сумма в целых единицах со знаком: {@code +46 654}, {@code -1 200}; ноль без знака.
     *
     * @param money сумма
     * @return короткий текст без валюты
     */
    static String wholeSigned(Money money) {
        String text = whole(money, "");
        return text.startsWith("-") || text.equals("0") ? text : "+" + text;
    }

    private VBox cardNode(Card card, Forecast forecast, Plan plan) {
        Label title = new Label(card.title());
        title.getStyleClass().add("card-title");
        Label value = new Label(card.value());
        value.getStyleClass().add("card-value");
        if (card.negative()) {
            value.getStyleClass().add("negative");
        }
        Label subtitle = new Label(card.subtitle());
        subtitle.getStyleClass().add("card-subtitle");
        // Ширину карточки задают заголовок и значение, и они никогда не сжимаются: HBox раздаёт место по
        // предпочтительной ширине, и без этого карточка с длинной подписью отнимала место у соседей, а их
        // значения обрезались («177 0…»). Подпись своей ширины не требует и обрезается многоточием.
        title.setMinWidth(Region.USE_PREF_SIZE);
        value.setMinWidth(Region.USE_PREF_SIZE);
        subtitle.setMinWidth(0);
        subtitle.setPrefWidth(0);
        subtitle.setMaxWidth(Double.MAX_VALUE);
        VBox node = new VBox(1, title, value, subtitle);
        node.getStyleClass().add("summary-card");
        // Узкая карточка обрезает подписи многоточием; полный текст виден во всплывающем спарклайне.
        HBox.setHgrow(node, Priority.ALWAYS);
        node.setMaxWidth(Double.MAX_VALUE);

        node.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> {
            LocalDate to = card.sparkTo().isBefore(card.sparkFrom()) ? card.sparkFrom() : card.sparkTo();
            sparkline.setData(new SparklinePopupControl.Data(card.title() + ": " + card.exact(), card.hint(),
                    safeSample(forecast, card.sparkFrom(), to), plan.currency()));
            Bounds bounds = node.localToScreen(node.getBoundsInLocal());
            if (bounds != null) {
                sparkline.show(node, bounds.getMinX(), bounds.getMaxY() + 4);
            }
        });
        node.addEventHandler(MouseEvent.MOUSE_EXITED, e -> sparkline.hide());
        // JavaFX: ContextMenuEvent → Swing: MouseAdapter.isPopupTrigger() → Web: contextmenu + preventDefault
        node.setOnContextMenuRequested(e -> {
            sparkline.hide();
            if (contextMenu != null) {
                contextMenu.hide();
            }
            // JavaFX: ContextMenu → Swing: JPopupMenu → Web: <ul class="context-menu">
            contextMenu = ForecastContextMenu.forSummaryCard(shell, card.date(), card.goal());
            contextMenu.show(node, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return node;
    }

    private static List<ru.cashprediction.core.forecast.DailyPoint> safeSample(Forecast forecast, LocalDate from, LocalDate to) {
        try {
            return ChartSeries.sample(forecast, from, to, 120);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    /**
     * Сокращённая запись суммы для подписи карточки: «1,50 млн», «896 тыс», «950».
     *
     * @param money сумма
     * @return короткий текст без знака валюты
     */
    static String compact(Money money) {
        double major = Math.abs(money.minor()) / 100.0;
        Locale ru = Locale.forLanguageTag("ru-RU");
        if (major >= 1_000_000) {
            return String.format(ru, "%.2f млн", major / 1_000_000);
        }
        if (major >= 10_000) {
            return String.format(ru, "%.0f тыс", major / 1_000);
        }
        return String.format(ru, "%.0f", major);
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
