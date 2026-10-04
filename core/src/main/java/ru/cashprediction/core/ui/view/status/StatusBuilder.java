package ru.cashprediction.core.ui.view.status;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.app.StatusMessages;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.StoreStatus;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Построение строки состояния из состояния приложения (спецификация v2, §5.4, тексты §8.1).
 *
 * <p><b>Сегменты</b> (все восемь всегда в модели, в порядке {@link StatusModel#ORDER}; у невидимого сегмента пустые
 * текст и подсказка):</p>
 * <ol>
 *   <li>file: {@code status.file} «Файл: {имя.md}» (подсказка - полный путь) или {@code status.file.none}
 *       (подсказка {@code status.file.tip.none});</li>
 *   <li>dirty: {@code status.dirty} ({@code warn}), {@code status.saved} (план с файлом) или {@code status.clean}
 *       (план без файла и без правок), оба {@code text.muted};</li>
 *   <li>rows: {@code status.rows} - строки событий периода, прошедшие фильтры вида (START, RULE, ONE_TIME, WHAT_IF;
 *       без итогов и PAST_HEADER, в том числе скрытые в свёрнутой группе прошедших); без прогноза - 0;</li>
 *   <li>horizon: {@code status.horizon} «Горизонт: {horizonLabel} (до dd.MM.yyyy)», для UNTIL {@code status.horizon.until};</li>
 *   <li>message (растягивается): по убыванию приоритета - подсказка пункта меню под указателем (info); временное
 *       сообщение, пока не истёк его срок; первое постоянное сообщение (по id причины в алфавитном порядке); если
 *       постоянного сообщения о прогнозе нет, а прогноз не рассчитан - {@code status.msg.forecastFailed} с причиной
 *       из документа. Цвет - по {@link StatusLevel};</li>
 *   <li>whatIf (только когда активно): {@code status.whatIf}, цвет {@code whatif}, подсказка {@code status.whatIf.tip};</li>
 *   <li>autosave (только когда включено): {@code status.autosave} или {@code status.autosave.problem} ({@code warn},
 *       подсказка - текст проблемы);</li>
 *   <li>session: {@code status.session.none} до первой записи; {@code status.session} со списком
 *       {@code status.session.store.ok}/{@code .fail} через « | » (сбой - цвет {@code expense}); web - одно хранилище
 *       {@code store.server}; второй экземпляр {@code status.session.disabled}; ожидание
 *       {@code status.session.pending}; подсказки {@code status.session.tip.ok}/{@code .fail} по строке на
 *       хранилище.</li>
 * </ol>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class StatusBuilder {

    /** Id причины постоянного сообщения «Настройки не сохранены: …» ({@link StatusMessages#persistent()}). */
    public static final String CAUSE_SETTINGS_FAILED = "settingsFailed";
    /** Id причины постоянного сообщения «Прогноз не рассчитан: …» ({@link StatusMessages#persistent()}). */
    public static final String CAUSE_FORECAST_FAILED = "forecastFailed";

    /** Id хранилища реестра ({@code RegistrySessionStore.id()}). */
    private static final String STORE_REGISTRY = "registry";
    /** Id хранилища XML ({@code XmlSessionStore.id()}). */
    private static final String STORE_XML = "xml";
    /** Id хранилища сервера web ({@code MarkdownSessionStore.id()}). */
    private static final String STORE_SERVER = "server";
    /** Разделитель хранилищ в тексте сегмента: «реестр ✓ 10:15:30 | XML ✓ 10:15:31» (§5.4). */
    private static final String STORE_SEPARATOR = " | ";

    private StatusBuilder() {
    }

    /**
     * Строит строку состояния; время записи снимков - в часовом поясе системы.
     *
     * @param state состояние приложения
     * @param now   текущий момент (для истечения сообщений и времени записи)
     * @return модель строки состояния
     */
    public static StatusModel build(AppState state, Instant now) {
        return build(state, now, ZoneId.systemDefault());
    }

    /**
     * Строит строку состояния.
     *
     * @param state состояние приложения
     * @param now   текущий момент (для истечения сообщений)
     * @param zone  часовой пояс времени записи снимков ({@code AppClock.zone()})
     * @return модель строки состояния
     */
    public static StatusModel build(AppState state, Instant now, ZoneId zone) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(zone, "zone");
        return new StatusModel(List.of(file(state), dirty(state), rows(state), horizon(state), message(state, now),
                whatIf(state), autosave(state), session(state, zone)));
    }

    /**
     * Постоянное сообщение «Настройки не сохранены: {причина}» (уровень error) для
     * {@code StatusMessages.withPersistent(CAUSE_SETTINGS_FAILED, …)}.
     *
     * @param reason причина
     * @return сообщение без срока
     */
    public static StatusMessages.Message settingsFailed(String reason) {
        return new StatusMessages.Message(UiText.get("status.msg.settingsFailed", Objects.requireNonNullElse(reason, "")),
                StatusLevel.ERROR, null);
    }

    /**
     * Постоянное сообщение «Прогноз не рассчитан: {причина}» (уровень error) для
     * {@code StatusMessages.withPersistent(CAUSE_FORECAST_FAILED, …)}.
     *
     * @param reason причина
     * @return сообщение без срока
     */
    public static StatusMessages.Message forecastFailed(String reason) {
        return new StatusMessages.Message(UiText.get("status.msg.forecastFailed", Objects.requireNonNullElse(reason, "")),
                StatusLevel.ERROR, null);
    }

    /**
     * Сообщение сегмента «Сообщение» по правилам {@link StatusMessages} (без производного сообщения о прогнозе).
     *
     * @param messages сообщения контроллера
     * @param now      текущий момент
     * @return сообщение или пусто
     */
    public static Optional<StatusMessages.Message> visibleMessage(StatusMessages messages, Instant now) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(now, "now");
        if (!messages.hoverTip().isBlank()) {
            return Optional.of(new StatusMessages.Message(messages.hoverTip(), StatusLevel.INFO, null));
        }
        StatusMessages.Message temporary = messages.message();
        if (temporary != null && (temporary.expiresAt() == null || now.isBefore(temporary.expiresAt()))) {
            return Optional.of(temporary);
        }
        // Карта постоянных сообщений неупорядочена (Map.copyOf): алфавитный порядок причин делает строку детерминированной.
        return messages.persistent().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private static StatusSegment file(AppState state) {
        Path file = state.document().file();
        if (file == null) {
            return segment(StatusModel.FILE, UiText.get("status.file.none"), UiText.get("status.file.tip.none"),
                    ColorToken.TEXT_PRIMARY);
        }
        Path name = file.getFileName();
        return segment(StatusModel.FILE, UiText.get("status.file", name == null ? file.toString() : name.toString()),
                file.toAbsolutePath().normalize().toString(), ColorToken.TEXT_PRIMARY);
    }

    private static StatusSegment dirty(AppState state) {
        DocumentView document = state.document();
        if (document.dirty()) {
            return segment(StatusModel.DIRTY, UiText.get("status.dirty"), "", ColorToken.WARN);
        }
        return document.file() != null
                ? segment(StatusModel.DIRTY, UiText.get("status.saved"), "", ColorToken.TEXT_MUTED)
                : segment(StatusModel.DIRTY, UiText.get("status.clean"), "", ColorToken.TEXT_MUTED);
    }

    private static StatusSegment rows(AppState state) {
        Forecast forecast = state.document().forecast();
        long count = 0;
        if (forecast != null) {
            LocalDate end = state.view().periodEnd(forecast.plan(), forecast.anchor());
            Predicate<ForecastRow> rowFilter = state.view().rowFilter();
            for (ForecastRow row : forecast.rows()) {
                boolean inPeriod = row.origin() == Origin.START || !row.date().isAfter(end);
                if (inPeriod && rowFilter.test(row)) {
                    count++;
                }
            }
        }
        return segment(StatusModel.ROWS, UiText.get("status.rows", count), "", ColorToken.TEXT_PRIMARY);
    }

    private static StatusSegment horizon(AppState state) {
        Plan plan = state.document().plan();
        String end = UiFormats.date(plan.endDate());
        String text = plan.horizon() instanceof Horizon.Until
                ? UiText.get("status.horizon.until", end)
                : UiText.get("status.horizon", UiFormats.horizonLabel(plan.horizon(), plan.startDate()), end);
        return segment(StatusModel.HORIZON, text, "", ColorToken.TEXT_PRIMARY);
    }

    private static StatusSegment message(AppState state, Instant now) {
        Optional<StatusMessages.Message> visible = visibleMessage(state.status(), now);
        DocumentView document = state.document();
        if (visible.isEmpty() && !document.forecastAvailable() && !document.forecastError().isBlank()) {
            // Постоянное сообщение держится, пока причина не исчезнет (§5.4 п. 5): причина видна прямо в документе.
            visible = Optional.of(forecastFailed(document.forecastError()));
        }
        StatusMessages.Message shown = visible.orElse(null);
        return new StatusSegment(StatusModel.MESSAGE, shown == null ? "" : shown.text(), "",
                shown == null ? ColorToken.TEXT_PRIMARY : shown.level().color(), true, true);
    }

    private static StatusSegment whatIf(AppState state) {
        boolean active = !state.view().whatIf().isNone();
        return active
                ? segment(StatusModel.WHAT_IF, UiText.get("status.whatIf"), UiText.get("status.whatIf.tip"),
                ColorToken.WHATIF)
                : hidden(StatusModel.WHAT_IF);
    }

    private static StatusSegment autosave(AppState state) {
        if (!state.settings().autosave()) {
            return hidden(StatusModel.AUTOSAVE);
        }
        String problem = state.autosaveProblem();
        return problem.isBlank()
                ? segment(StatusModel.AUTOSAVE, UiText.get("status.autosave"), "", ColorToken.TEXT_PRIMARY)
                : segment(StatusModel.AUTOSAVE, UiText.get("status.autosave.problem"), problem, ColorToken.WARN);
    }

    private static StatusSegment session(AppState state, ZoneId zone) {
        switch (state.recorder()) {
            case DISABLED_SECOND_INSTANCE -> {
                return segment(StatusModel.SESSION, UiText.get("status.session.disabled"),
                        UiText.get("status.session.disabled.tip"), ColorToken.TEXT_PRIMARY);
            }
            case PENDING_RESTORE -> {
                return segment(StatusModel.SESSION, UiText.get("status.session.pending"), "", ColorToken.TEXT_PRIMARY);
            }
            case NOT_STARTED -> {
                return segment(StatusModel.SESSION, UiText.get("status.session.none"), "", ColorToken.TEXT_PRIMARY);
            }
            case RECORDING -> {
                // Продолжение после switch: запись идёт, текст собирается по хранилищам.
            }
        }
        List<String> parts = new ArrayList<>();
        List<String> tips = new ArrayList<>();
        boolean failed = false;
        for (StoreStatus store : state.stores()) {
            if (!store.ok()) {
                failed = true;
                parts.add(UiText.get("status.session.store.fail", storeName(store.storeId())));
                tips.add(UiText.get("status.session.tip.fail", storeTitle(store.storeId()), store.message()));
            } else if (store.savedAt() != null) {
                String time = UiFormats.time(LocalTime.ofInstant(store.savedAt(), zone));
                parts.add(UiText.get("status.session.store.ok", storeName(store.storeId()), time));
                tips.add(UiText.get("status.session.tip.ok", storeTitle(store.storeId()), time));
            }
        }
        if (parts.isEmpty()) {
            return segment(StatusModel.SESSION, UiText.get("status.session.none"), "", ColorToken.TEXT_PRIMARY);
        }
        return segment(StatusModel.SESSION, UiText.get("status.session", String.join(STORE_SEPARATOR, parts)),
                String.join("\n", tips), failed ? ColorToken.EXPENSE : ColorToken.TEXT_PRIMARY);
    }

    private static String storeName(String storeId) {
        return switch (storeId) {
            case STORE_REGISTRY -> UiText.get("store.registry");
            case STORE_XML -> UiText.get("store.xml");
            case STORE_SERVER -> UiText.get("store.server");
            default -> storeId;
        };
    }

    private static String storeTitle(String storeId) {
        return switch (storeId) {
            case STORE_REGISTRY -> UiText.get("store.registry.title");
            case STORE_XML -> UiText.get("store.xml.title");
            case STORE_SERVER -> UiText.get("store.server.title");
            default -> storeId;
        };
    }

    private static StatusSegment segment(String id, String text, String tooltip, ColorToken color) {
        return new StatusSegment(id, text, tooltip, color, false, true);
    }

    private static StatusSegment hidden(String id) {
        return new StatusSegment(id, "", "", ColorToken.TEXT_PRIMARY, false, false);
    }
}
