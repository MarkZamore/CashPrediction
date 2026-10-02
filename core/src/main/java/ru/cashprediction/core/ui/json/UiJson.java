package ru.cashprediction.core.ui.json;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import ru.cashprediction.core.app.Activation;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.text.Texts;
import ru.cashprediction.core.ui.command.CommandArgs;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.command.FocusScope;
import ru.cashprediction.core.ui.command.InvokeSource;
import ru.cashprediction.core.ui.command.KeyChord;
import ru.cashprediction.core.ui.menu.ContextTarget;
import ru.cashprediction.core.ui.view.ScreenPart;
import ru.cashprediction.core.ui.view.table.TableModel;
import ru.cashprediction.core.ui.view.chart.ChartModel;

/**
 * JSON-представление моделей и протокола web-клиента (архитектура §5) поверх мини-JSON ядра ({@code core.json}).
 *
 * <p><b>Правила записи (этап S2, core-protocol-dump):</b> record → объект с полями в порядке компонентов; вид
 * sealed-интерфейса → поле {@code kind} с простым именем записи (например {@code MenuNode.Check} →
 * {@code "kind":"Check"}); enum → имя константы; {@code LocalDate} → ISO {@code 2026-10-05}; {@code YearMonth} →
 * {@code 2026-10}; {@code Path} → строка; {@code null} → {@code null}; {@code TableModel} → колонки, {@code rowCount},
 * {@code revision}, выделение и заглушка без строк; {@code ChartModel} → только {@code revision}. Намерения и запросы
 * читаются обратно в {@link WebIntent} и {@link WebQuery}; неизвестный {@code type} — {@code IllegalArgumentException}.
 * Примеры JSON — {@code docs/ui-protocol.md}; фикстуры — {@code core/src/test/resources/ui-json}.</p>
 *
 * <p><b>Два исключения из правил выше — одна форма во всём протоколе:</b></p>
 * <ul>
 *   <li>{@code CommandId} пишется своим id спецификации ({@code CommandId.id()}: {@code "file.save"},
 *       {@code "row.edit"}), а не именем константы, и читается через {@code CommandId.byId}; неизвестный id —
 *       {@code IllegalArgumentException}. Так команда узла меню, тулбара и контекстного меню, которую вкладка
 *       возвращает намерением {@code command}, совпадает байт в байт.</li>
 *   <li>{@code ContextTarget} пишется полем {@code kind} со значением {@code ContextTarget.kind()} ({@code "row"},
 *       {@code "total"}, {@code "pastHeader"}, {@code "card"}, {@code "chart"}, {@code "preview"}) — тем же ключом,
 *       что у цели в дампе и сценариях ({@code row:<rowId>}), — и полями записи.</li>
 * </ul>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class UiJson {

    private UiJson() {
    }

    /**
     * Дерево JSON ({@code Map}/{@code List}/строки/числа/булевы/{@code null}) для записи, модели или эффекта.
     *
     * @param value значение
     * @return дерево для {@code JsonWriter}
     */
    public static Object toTree(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number number) {
            if (number instanceof Double d && !Double.isFinite(d) || number instanceof Float f && !Float.isFinite(f)) {
                throw new IllegalArgumentException("non-finite JSON number");
            }
            return value;
        }
        if (value instanceof CommandId command) return command.id();
        if (value instanceof Enum<?> enumeration) return enumeration.name();
        if (value instanceof LocalDate || value instanceof YearMonth || value instanceof Path) return value.toString();
        if (value instanceof TableModel table) {
            return object("revision", table.revision(), "columns", table.columns(), "rowCount", table.rowCount(),
                    "selectedRowId", table.selectedRowId(), "scrollToRowId", table.scrollToRowId(),
                    "placeholder", table.placeholder());
        }
        if (value instanceof ChartModel chart) return object("revision", chart.revision());
        if (value instanceof WebEffect effect) {
            Map<String, Object> result = new LinkedHashMap<>(effect(0, effect));
            result.remove("seq");
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, element) -> {
                if (!(key instanceof String) && !(key instanceof Enum<?>)) {
                    throw new IllegalArgumentException("JSON map key");
                }
                String name = key instanceof CommandId command ? command.id()
                        : key instanceof Enum<?> enumeration ? enumeration.name() : (String) key;
                if (result.containsKey(name)) throw new IllegalArgumentException("duplicate JSON map key");
                result.put(name, toTree(element));
            });
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<Object> result = new ArrayList<>();
            iterable.forEach(element -> result.add(toTree(element)));
            return Collections.unmodifiableList(result);
        }
        Class<?> type = value.getClass();
        if (!type.isRecord() || !type.getPackageName().startsWith("ru.cashprediction.core.")) {
            throw new IllegalArgumentException("unsupported UI JSON type: " + type.getName());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof WebIntent intent) result.put("type", intent.type());
        else if (value instanceof WebQuery query) result.put("type", query.type());
        else if (value instanceof ContextTarget target) result.put("kind", target.kind());
        else {
            for (Class<?> contract : type.getInterfaces()) {
                if (contract.isSealed()) { result.put("kind", type.getSimpleName()); break; }
            }
        }
        try {
            for (RecordComponent component : type.getRecordComponents()) {
                // Необязательная ширина не меняет прежний JSON обычных полей.
                if (value instanceof ru.cashprediction.core.ui.form.FieldSpec field
                        && component.getName().equals("widthPx") && field.widthPx() == 0) continue;
                if (result.containsKey(component.getName())) throw new IllegalArgumentException("discriminator collision");
                result.put(component.getName(), toTree(component.getAccessor().invoke(value)));
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("UI JSON schema", exception);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Текст JSON значения.
     *
     * @param value значение
     * @return компактный JSON
     */
    public static String write(Object value) {
        return JsonWriter.write(toTree(value));
    }

    /**
     * Эффект с номером для журнала эффектов: {@code {seq, type, …поля}}.
     *
     * @param seq    номер
     * @param effect эффект
     * @return дерево JSON
     */
    public static Map<String, Object> effect(long seq, WebEffect effect) {
        if (seq < 0) throw invalid("seq");
        Map<String, Object> payload = switch (effect) {
            case WebEffect.Screen screen -> {
                Map<String, Object> parts = new LinkedHashMap<>();
                for (ScreenPart part : screen.parts()) {
                    Object content = switch (part) {
                        case TITLE -> screen.model().windowTitle();
                        case MENU -> screen.model().menuBar();
                        case TOOLBAR -> screen.model().toolbar();
                        case SUMMARY -> screen.model().summary();
                        case TABLE -> screen.model().table();
                        case CHART -> screen.model().chart();
                        case STATUS -> screen.model().status();
                        case MODE -> screen.model().mode();
                    };
                    parts.put(part.name(), toTree(content));
                }
                yield object("revision", screen.model().revision(), "parts", parts);
            }
            case WebEffect.FormOpen open -> {
                var window = new LinkedHashMap<>(object("id", open.windowId(), "ownerId", open.ownerId(), "modal", open.modal(),
                        "placement", open.placement(), "spec", open.spec(), "view", open.view()));
                if (open.chooserRequest() != null) window.put("chooserRequest", toTree(open.chooserRequest()));
                yield object("window", window);
            }
            case WebEffect.FormViewUpdate update -> object("windowId", update.windowId(), "view", update.view(),
                    "echoOf", object("tab", update.echoTab(), "clientRev", update.echoClientRev()));
            case WebEffect.FormClose close -> object("windowId", close.windowId());
            case WebEffect.FormFront front -> object("windowId", front.windowId());
            case WebEffect.AlertOpen open -> open.placement() == null
                    ? object("alertId", open.alertId(), "spec", open.spec())
                    : object("alertId", open.alertId(), "spec", open.spec(), "placement", open.placement());
            case WebEffect.AlertUpdate update -> object("alertId", update.alertId(), "spec", update.spec());
            case WebEffect.AlertClose close -> object("alertId", close.alertId());
            case WebEffect.ContextMenu menu -> object("tab", menu.tab(), "target", menu.target(), "items", menu.items());
            case WebEffect.Focus focus -> object("target", focus.target());
            case WebEffect.Reveal reveal -> object("rowId", reveal.rowId(), "mode", reveal.mode());
            case WebEffect.Clipboard clipboard -> object("text", clipboard.text());
            case WebEffect.Inert inert -> object("value", inert.value());
            case WebEffect.Reload reload -> object("tab", reload.tab());
            case WebEffect.Exit exit -> object("kind", exit.kind(), "title", exit.title(), "text", exit.text());
            case WebEffect.TestStep step -> object("n", step.n(), "command", step.command());
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seq", seq);
        result.put("type", effect.type());
        result.putAll(payload);
        return Collections.unmodifiableMap(result);
    }

    /**
     * Читает намерение {@code intent:{type, …}}.
     *
     * @param json объект намерения
     * @return намерение
     * @throws IllegalArgumentException если тип неизвестен или поля некорректны
     */
    public static WebIntent readIntent(Map<String, Object> json) {
        try {
        return switch (Json.requireString(json, "type")) {
            case "command" -> {
                String id = Json.requireString(json, "command");
                CommandId command = CommandId.byId(id).orElseThrow(() -> invalid("command"));
                InvokeSource source = enumeration(json, "source", InvokeSource.class);
                if (source == InvokeSource.SELFTEST) throw invalid("source");
                Map<String, Object> args = Json.object(json, "args");
                yield new WebIntent.Command(command, new CommandArgs(Json.string(args, "rowId", ""), date(args, "date"),
                        Json.string(args, "cardId", ""), Json.string(args, "key", ""), Json.string(args, "value", "")), source);
            }
            case "key" -> {
                Map<String, Object> chord = Json.asObject(json.get("chord"), "chord");
                yield new WebIntent.Key(new KeyChord(bool(chord, "ctrl"), bool(chord, "shift"), bool(chord, "alt"),
                        identifier(chord, "key")), enumeration(json, "scope", FocusScope.class), Json.string(json, "focusId", ""));
            }
            case "selectRow" -> new WebIntent.SelectRow(Json.requireString(json, "rowId"));
            case "activateRow" -> new WebIntent.ActivateRow(identifier(json, "rowId"), identifier(json, "columnId"),
                    enumeration(json, "how", Activation.class));
            case "filterText" -> new WebIntent.FilterText(Json.requireString(json, "text"));
            case "sliderCommit" -> new WebIntent.SliderCommit(identifier(json, "itemId"), integer(json, "value"));
            case "spinnerCommit" -> new WebIntent.SpinnerCommit(identifier(json, "itemId"), Json.requireLong(json, "value"));
            case "mainGeometry" -> new WebIntent.MainGeometry(bounds(json, "bounds", true), bool(json, "maximized"));
            case "menuHover" -> new WebIntent.MenuHover(Json.string(json, "itemId", null));
            case "closeMain" -> new WebIntent.CloseMain();
            case "formField" -> new WebIntent.FormField(identifier(json, "windowId"), identifier(json, "fieldId"),
                    Json.requireString(json, "raw"), bool(json, "committed"), nonnegative(json, "clientRev"));
            case "formButton" -> new WebIntent.FormButton(identifier(json, "windowId"), identifier(json, "buttonId"));
            case "formPreview" -> {
                int index = integer(json, "index");
                boolean activated = bool(json, "activated");
                if (index < -1 || activated && index < 0) throw invalid("index");
                yield new WebIntent.FormPreview(identifier(json, "windowId"), index, activated);
            }
            case "formActivate" -> new WebIntent.FormActivate(identifier(json, "windowId"), identifier(json, "fieldId"),
                    nonnegativeInt(json, "index"));
            case "formSubmit" -> new WebIntent.FormSubmit(identifier(json, "windowId"), identifier(json, "fieldId"));
            case "formBounds" -> new WebIntent.FormBounds(identifier(json, "windowId"), bounds(json, "bounds", false));
            case "formShown" -> new WebIntent.FormShown(identifier(json, "windowId"));
            case "formClose" -> new WebIntent.FormClose(identifier(json, "windowId"));
            case "alertShown" -> new WebIntent.AlertShown(identifier(json, "windowId"));
            case "alertButton" -> new WebIntent.AlertAnswer(identifier(json, "alertId"), identifier(json, "buttonId"));
            case "clientError" -> new WebIntent.ClientError(Json.requireString(json, "message"), Json.requireString(json, "stack"));
            default -> throw invalid("type");
        };
        } catch (ru.cashprediction.core.json.JsonException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    /**
     * Читает запрос {@code {type, …}}.
     *
     * @param json объект запроса
     * @return запрос
     * @throws IllegalArgumentException если тип неизвестен или поля некорректны
     */
    public static WebQuery readQuery(Map<String, Object> json) {
        try {
        return switch (Json.requireString(json, "type")) {
            case "contextMenu" -> new WebQuery.ContextMenu(target(Json.asObject(json.get("target"), "target")));
            case "tooltip" -> new WebQuery.Tooltip(nonnegative(json, "rev"), nonnegativeInt(json, "index"), identifier(json, "columnId"));
            case "rows" -> {
                int count = nonnegativeInt(json, "count");
                if (count > 300) throw invalid("count");
                yield new WebQuery.Rows(nonnegative(json, "rev"), nonnegativeInt(json, "from"), count);
            }
            case "chartScene" -> new WebQuery.Chart(nonnegative(json, "rev"), dimension(json, "w"), dimension(json, "h"));
            case "chartHover" -> new WebQuery.ChartHover(nonnegative(json, "rev"), number(json, "x"), number(json, "y"),
                    dimension(json, "w"), dimension(json, "h"));
            case "dayCard" -> {
                LocalDate date = date(json, "date");
                if (date == null) throw invalid("date");
                yield new WebQuery.DayCard(date);
            }
            case "sparkline" -> new WebQuery.Sparkline(identifier(json, "cardId"));
            case "calendar" -> {
                YearMonth month;
                try { month = YearMonth.parse(Json.requireString(json, "month")); }
                catch (java.time.DateTimeException exception) { throw invalid("month"); }
                yield new WebQuery.Calendar(month, date(json, "selected"));
            }
            default -> throw invalid("type");
        };
        } catch (ru.cashprediction.core.json.JsonException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    /** Собирает объект в порядке ключей, сохраняя явные null. */
    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], toTree(pairs[index + 1]));
        return Collections.unmodifiableMap(result);
    }

    /** Разбирает закрытый набор целей контекстного меню. */
    private static ContextTarget target(Map<String, Object> json) {
        return switch (Json.requireString(json, "kind")) {
            case "row" -> new ContextTarget.Row(identifier(json, "rowId"));
            case "total" -> new ContextTarget.Total(identifier(json, "rowId"));
            case "pastHeader" -> new ContextTarget.PastHeader(identifier(json, "rowId"));
            case "card" -> new ContextTarget.Card(identifier(json, "cardId"));
            case "chart" -> new ContextTarget.Chart(number(json, "x"), number(json, "y"), dimension(json, "width"), dimension(json, "height"));
            case "preview" -> new ContextTarget.Preview(identifier(json, "windowId"), nonnegativeInt(json, "index"));
            default -> throw invalid("kind");
        };
    }

    /** Читает непустой идентификатор без изменения его содержимого. */
    private static String identifier(Map<String, Object> json, String key) {
        String value = Json.requireString(json, key);
        if (value.isBlank()) throw invalid(key);
        return value;
    }

    /** Не подставляет false вместо отсутствующего обязательного флага. */
    private static boolean bool(Map<String, Object> json, String key) {
        if (!(json.get(key) instanceof Boolean value)) throw invalid(key);
        return value;
    }

    /** Проверяет целочисленное значение и переполнение int. */
    private static int integer(Map<String, Object> json, String key) {
        long value = Json.requireLong(json, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw invalid(key);
        return (int) value;
    }

    /** Проверяет неотрицательный индекс. */
    private static int nonnegativeInt(Map<String, Object> json, String key) {
        int value = integer(json, key);
        if (value < 0) throw invalid(key);
        return value;
    }

    /** Проверяет неотрицательную ревизию. */
    private static long nonnegative(Map<String, Object> json, String key) {
        long value = Json.requireLong(json, key);
        if (value < 0) throw invalid(key);
        return value;
    }

    /** Читает конечную координату, не заменяя отсутствующее число нулём. */
    private static double number(Map<String, Object> json, String key) {
        if (!(json.get(key) instanceof Number value) || !Double.isFinite(value.doubleValue())) throw invalid(key);
        return value.doubleValue();
    }

    /** Читает размер области отрисовки. */
    private static double dimension(Map<String, Object> json, String key) {
        double value = number(json, key);
        if (value < 0) throw invalid(key);
        return value;
    }

    /** Разбирает ISO-дату; отсутствие допускается только для необязательных полей. */
    private static LocalDate date(Map<String, Object> json, String key) {
        String value = Json.string(json, key, null);
        if (value == null) return null;
        try { return LocalDate.parse(value); }
        catch (java.time.DateTimeException exception) { throw invalid(key); }
    }

    /** Разбирает геометрию с обязательными четырьмя координатами. */
    private static WindowBounds bounds(Map<String, Object> json, String key, boolean nullable) {
        if (json.get(key) == null && nullable) return null;
        Map<String, Object> value = Json.asObject(json.get(key), key);
        return new WindowBounds(number(value, "x"), number(value, "y"), dimension(value, "width"), dimension(value, "height"));
    }

    /** Не допускает неизвестных значений перечислений. */
    private static <E extends Enum<E>> E enumeration(Map<String, Object> json, String key, Class<E> type) {
        try { return Enum.valueOf(type, Json.requireString(json, key)); }
        catch (IllegalArgumentException exception) { throw invalid(key); }
    }

    /** Единое локализованное сообщение об ошибке протокола. */
    private static IllegalArgumentException invalid(String key) {
        return new IllegalArgumentException(Texts.get("json.error.uiField", key));
    }
}
