package ru.cashprediction.parity.check.visual;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.summary.SummaryBuilder;
import ru.cashprediction.parity.browser.PngHeader;
import ru.cashprediction.parity.check.VisualChecks;

/** Проверяет настоящие PNG и измеренные рамки без золотых файлов и без масштабирования изображения. */
public final class VisualImages {
    private static final List<String> COLUMN_IDS = List.of("date", "day", "title", "category", "income", "expense", "balance", "marks");
    /** Размер снимка при масштабе 1:1. */
    public static final int WIDTH = 1200, HEIGHT = 800;
    private VisualImages() { }

    /** Требует PNG, непрозрачные пиксели и точное совпадение размера с содержимым дампа. */
    public static BufferedImage png(byte[] bytes, UiDump dump) throws IOException {
        PngHeader.read(bytes);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null || image.getWidth() != WIDTH || image.getHeight() != HEIGHT)
            throw new AssertionError("PNG viewport must be exactly 1200x800; no resizing is allowed");
        if (dump.frame() == null || dump.frame().contentWidth() != WIDTH || dump.frame().contentHeight() != HEIGHT)
            throw new AssertionError("Widget viewport must match PNG 1200x800 at scale 1");
        var colors = new HashSet<Integer>();
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int pixel = image.getRGB(x, y);
            if ((pixel >>> 24) != 255) throw new AssertionError("Transparent screenshot pixel: " + x + "," + y);
            if (colors.size() < 16) colors.add(pixel);
        }
        if (colors.size() < 8) throw new AssertionError("Blank/solid screenshot cannot prove rendered widgets");
        return image;
    }

    /** Проверяет реальные области и фиксированные фоновые пробы; ошибки возвращаются независимо друг от друга. */
    public static List<String> check(UiDump dump, byte[] bytes) {
        var failures = new ArrayList<String>();
        attempt(failures, "PNG/viewport", () -> png(bytes, dump));
        if (dump.frame() == null) return failures;
        for (String id : List.of("menuBar", "toolbar", "summary", "center", "status"))
            attempt(failures, "region/" + id, () -> inside(required(dump, id)));
        // Все дополнительные реальные измерения также обязаны быть конечными и помещаться в PNG.
        for (String id : dump.frame().regions().keySet())
            if (!Set.of("menuBar", "toolbar", "summary", "center", "status").contains(id))
                attempt(failures, "region/" + id, () -> inside(required(dump, id)));
        attempt(failures, "measurement/identities", () -> identities(dump));
        attempt(failures, "measurement/summary.cards", () -> {
            if (dump.summary() != null && dump.summary().visible() && dump.summary().unavailableText().isBlank()) {
                var observed = dump.summary().cards().stream().map(UiDump.Card::id).toList();
                if (!observed.equals(SummaryBuilder.CARD_IDS))
                    throw new AssertionError("Missing, reordered or unexpected summary cards: " + observed);
            }
        });
        attempt(failures, "toolbar height", () -> near(36, required(dump, "toolbar").height(), 4));
        attempt(failures, "status height", () -> near(24, required(dump, "status").height(), 4));
        attempt(failures, "status bottom", () -> near(HEIGHT, required(dump, "status").y() + required(dump, "status").height(), 4));
        for (String id : List.of("toolbar", "summary", "status")) {
            // Проба - заранее фиксированная узкая область отступа, не выбранный по подходящему цвету пиксель.
            attempt(failures, "color/" + id, () -> {
                var box = required(dump, id);
                var sample = new UiDump.Box(box.x() + 1, box.y() + box.height() / 2 - 1, 2, 2);
                inside(sample); VisualChecks.regionColor(bytes, sample, ColorToken.BG_WINDOW.argb());
            });
        }
        if (dump.summary() == null || dump.summary().cards().isEmpty()) failures.add("Missing real summary cards");
        else for (var card : dump.summary().cards()) attempt(failures, "card/" + card.id(), () -> {
            var box = cardBox(dump, card); inside(box); contained(required(dump, "summary"), box);
            var sample = new UiDump.Box(box.x() + 8, box.y() + 8, 2, 2);
            inside(sample); VisualChecks.regionColor(bytes, sample, ColorToken.BG_CARD.argb());
        });
        // Схема 1 не содержит этих измерений автоматически. Их отсутствие нельзя закрыть модельным числом.
        for (String id : List.of("toolbar.baseline", "status.baseline"))
            attempt(failures, "measurement/" + id, () -> {
                var baseline = required(dump, id); inside(baseline);
                // Реальная линия имеет высоту 1 px; записанный DumpNormalizer округляет её до 2 px.
                // Это два точных представления одной линии, а не увеличение допуска положения текста.
                if (baseline.height() != 1 && baseline.height() != 2)
                    throw new AssertionError("Baseline must be a raw or normalized one-pixel line");
                contained(required(dump, id.substring(0, id.indexOf('.'))), baseline);
            });
        // Объект table есть и в режиме графика. Видимость подтверждает выбранный настоящий переключатель.
        boolean visibleTable = dump.step().equals("table") || dump.toolbar() != null && dump.toolbar().items().stream()
                .anyMatch(item -> item.id().equals("tb.table") && item.selected());
        if (visibleTable && (dump.table() == null || dump.frame().regions().keySet().stream()
                .filter(k -> k.startsWith("table.column.")).count() != dump.table().columns().size()))
            failures.add("Missing real table.column.<id> boxes for every column; column geometry cannot be verified from column texts");
        if (visibleTable || dump.frame().regions().keySet().stream().anyMatch(k -> k.startsWith("table.column.")))
            attempt(failures, "measurement/table.columns", () -> columns(dump));
        if (dump.toolbar() != null) for (var item : dump.toolbar().items())
            if (!Set.of("Separator", "Spacer").contains(item.kind()))
                attempt(failures, "toolbar/" + item.id(), () -> {
                    inside(item.bounds()); contained(required(dump, "toolbar"), item.bounds());
                });
        attempt(failures, "measurement/buttons", () -> {
            for (double x : buttons(dump, true).values())
                if (!Double.isFinite(x) || x < 0) throw new AssertionError("Invalid button x: " + x);
        });
        return List.copyOf(failures);
    }

    /** Проверяет идентификаторы и рамки заголовков, сопоставляя только тексты, без модельных координат. */
    private static void columns(UiDump dump) {
        if (dump.table() == null) throw new AssertionError("Missing observed table");
        // §5.2 задаёт восемь постоянных колонок; две одинаково урезанные таблицы не доказывают полноту.
        var titles = COLUMN_IDS.stream().map(id -> UiText.get("table.column." + id)).toList();
        if (!dump.table().columns().equals(titles)) throw new AssertionError("Missing, reordered or unexpected table columns");
        var expected = new LinkedHashSet<String>();
        for (String title : dump.table().columns()) {
            String id = COLUMN_IDS.stream().filter(k -> UiText.get("table.column." + k).equals(title)).findFirst()
                    .orElseThrow(() -> new AssertionError("Unknown observed column title: " + title));
            if (!expected.add("table.column." + id)) throw new AssertionError("Duplicate observed column: " + id);
        }
        var actual = new TreeSet<String>();
        dump.frame().regions().keySet().stream().filter(k -> k.startsWith("table.column.")).forEach(actual::add);
        if (!actual.equals(expected)) throw new AssertionError("Missing real table.column measurements: expected " + expected + "; actual " + actual);
        var header = required(dump, "table.header"); inside(header); contained(required(dump, "center"), header);
        double right = header.x();
        for (String id : expected) {
            var box = required(dump, id); inside(box); contained(header, box);
            near(28, box.height(), 4);
            if (box.x() < right - 4) throw new AssertionError("Overlapping or reordered column: " + id);
            right = box.x() + box.width();
        }
    }

    /** Требует нахождения измеренного элемента внутри своей области с допуском геометрии 4 px. */
    private static void contained(UiDump.Box parent, UiDump.Box child) {
        inside(parent); inside(child);
        if (child.x() < parent.x() - 4 || child.y() < parent.y() - 4
                || child.x() + child.width() > parent.x() + parent.width() + 4
                || child.y() + child.height() > parent.y() + parent.height() + 4)
            throw new AssertionError("Measurement outside its region: " + child + " / " + parent);
    }

    /** Сравнивает области, карточки, элементы тулбара и позиции кнопок только между настоящими клиентами. */
    public static List<String> compare(UiDump a, UiDump b) {
        var failures = new ArrayList<String>();
        if (!a.scenario().equals(b.scenario()) || !a.step().equals(b.step()))
            return List.of("Mismatched visual checkpoint identities");
        attempt(failures, "measurement/identities/left", () -> identities(a));
        attempt(failures, "measurement/identities/right", () -> identities(b));
        if (!failures.isEmpty()) return List.copyOf(failures);
        for (String id : List.of("menuBar", "toolbar", "summary", "center", "status"))
            attempt(failures, "region/" + id, () -> VisualChecks.position(required(a, id), required(b, id), 4));
        for (String id : List.of("toolbar.baseline", "status.baseline"))
            attempt(failures, id, () -> VisualChecks.position(required(a, id), required(b, id), 3));
        var columns = new TreeSet<String>();
        a.frame().regions().keySet().stream().filter(k -> k.startsWith("table.column.")).forEach(columns::add);
        b.frame().regions().keySet().stream().filter(k -> k.startsWith("table.column.")).forEach(columns::add);
        for (String id : columns) attempt(failures, id, () -> VisualChecks.position(required(a, id), required(b, id), 4));
        var additional = new TreeSet<String>();
        if (a.frame() != null) additional.addAll(a.frame().regions().keySet());
        if (b.frame() != null) additional.addAll(b.frame().regions().keySet());
        additional.removeAll(List.of("menuBar", "toolbar", "summary", "center", "status", "toolbar.baseline", "status.baseline"));
        additional.removeAll(columns);
        for (String id : additional)
            attempt(failures, "region/" + id, () -> VisualChecks.position(required(a, id), required(b, id), 4));
        Map<String, UiDump.Box> left = boxes(a), right = boxes(b);
        if (!left.keySet().equals(right.keySet())) failures.add("Different visible card/toolbar identities: " + left.keySet() + " / " + right.keySet());
        for (String id : left.keySet()) if (right.containsKey(id))
            attempt(failures, id, () -> VisualChecks.position(left.get(id), right.get(id), 4));
        // x относится к содержимому собственного окна во всех клиентах, а не к viewport страницы.
        // Сравниваем измерения, не объявляя отсутствие координат только по имени web-клиента.
        var buttonsA = buttons(a, true); var buttonsB = buttons(b, true);
        if (!buttonsA.keySet().equals(buttonsB.keySet())) failures.add("Different visible button identities");
        buttonsA.forEach((id, x) -> { if (buttonsB.containsKey(id)) attempt(failures, id, () -> near(x, buttonsB.get(id), 4)); });
        return List.copyOf(failures);
    }

    /** Проверяет конечную положительную рамку полностью внутри PNG, включая края, а не только центр. */
    public static void inside(UiDump.Box box) {
        if (box == null || !Double.isFinite(box.x()) || !Double.isFinite(box.y())
                || !Double.isFinite(box.width()) || !Double.isFinite(box.height()) || box.width() <= 0 || box.height() <= 0
                || box.x() < 0 || box.y() < 0 || box.x() + box.width() > WIDTH || box.y() + box.height() > HEIGHT)
            throw new AssertionError("Invalid or clipped region: " + box);
    }

    private static UiDump.Box required(UiDump dump, String id) {
        if (dump.frame() == null || !dump.frame().regions().containsKey(id)) throw new AssertionError("Missing real region " + id);
        return dump.frame().regions().get(id);
    }

    private static UiDump.Box cardBox(UiDump dump, UiDump.Card card) { return measuredBox(card.bounds()); }
    private static UiDump.Box measuredBox(UiDump.Box box) {
        if (box == null) throw new AssertionError("Missing widget bounds");
        // Контракт UiDump.Box: координаты содержимого окна, одинаковые для каждого клиента.
        return box;
    }

    private static Map<String, UiDump.Box> boxes(UiDump dump) {
        var result = new LinkedHashMap<String, UiDump.Box>();
        if (dump.summary() != null) for (var card : dump.summary().cards()) result.put("card/" + card.id(), cardBox(dump, card));
        if (dump.toolbar() != null) for (var item : dump.toolbar().items())
            if (!Set.of("Separator", "Spacer").contains(item.kind())) result.put("toolbar/" + item.id(), measuredBox(item.bounds()));
        return result;
    }

    private static Map<String, Double> buttons(UiDump dump, boolean includeAlerts) {
        var result = new LinkedHashMap<String, Double>();
        for (var window : dump.windows()) for (var button : window.buttons()) {
            // DOM-дампер уже вычитает origin: у всех клиентов x относится к содержимому окна.
            result.put("window/" + window.id() + "/" + window.type() + "/" + window.purpose() + "/" + button.id(), button.x());
        }
        if (includeAlerts) for (var alert : dump.alerts()) for (var button : alert.buttons()) result.put("alert/" + alert.purpose() + "/" + button.id(), button.x());
        return result;
    }

    /** Не позволяет Map скрыть повторные идентификаторы измеренных карточек, элементов тулбара и кнопок. */
    private static void identities(UiDump dump) {
        var seen = new HashSet<String>();
        if (dump.summary() != null) for (var card : dump.summary().cards()) unique(seen, "card/" + card.id());
        if (dump.toolbar() != null) for (var item : dump.toolbar().items())
            if (!Set.of("Separator", "Spacer").contains(item.kind())) unique(seen, "toolbar/" + item.id());
        for (var window : dump.windows()) for (var button : window.buttons())
            unique(seen, "window/" + window.id() + "/" + window.type() + "/" + window.purpose() + "/" + button.id());
        for (var alert : dump.alerts()) for (var button : alert.buttons())
            unique(seen, "alert/" + alert.purpose() + "/" + button.id());
    }

    /** Требует однозначную адресацию каждого наблюдаемого измерения. */
    private static void unique(Set<String> seen, String id) {
        if (!seen.add(id)) throw new AssertionError("Duplicate visual identity: " + id);
    }

    private static void near(double expected, double actual, double tolerance) {
        if (!Double.isFinite(expected) || !Double.isFinite(actual) || Math.abs(expected - actual) > tolerance)
            throw new AssertionError("Expected " + expected + " +/- " + tolerance + ", actual " + actual);
    }

    @FunctionalInterface private interface Check { void run() throws Exception; }
    private static void attempt(List<String> failures, String label, Check check) {
        try { check.run(); } catch (Exception | AssertionError failure) { failures.add(label + ": " + failure.getMessage()); }
    }
}
