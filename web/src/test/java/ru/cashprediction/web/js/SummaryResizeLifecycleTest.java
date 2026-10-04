package ru.cashprediction.web.js;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Контракты исходников для выхода из resize-доставки; геометрию проверяет настоящий браузер. */
class SummaryResizeLifecycleTest {
    private static final Path MODULE = Files.isDirectory(Path.of("src/main/resources/web/app"))
            ? Path.of(".") : Path.of("web");

    /** Наблюдатель сводки объединяет уведомления, а число колонок записывает только при изменении. */
    @Test void summaryKeepsDeferredAndGuardedLayout() throws Exception {
        String source = source("render-summary.js");
        verifySummary(source);
        assertThrows(AssertionError.class, () -> verifySummary(source.replace("if (pending) return;", "")));
        assertThrows(AssertionError.class, () -> verifySummary(source.replace(
                "root.style.getPropertyValue('--cp-summary-columns') !== value", "true")));
        assertThrows(AssertionError.class, () -> verifySummary(source.replace(
                "requestAnimationFrame(() => { pending = false; layoutSummary(root); });", "layoutSummary(root);")));
        assertThrows(AssertionError.class, () -> verifySummary(source.replace(
                "Math.floor((width - (columns - 1) * gap) / columns)", "(width - (columns - 1) * gap) / columns")));
    }

    /** Resize таблицы только планирует общий кадр; явное обновление отменяет отложенный дубль. */
    @Test void tableObserverDoesNotWriteDuringDelivery() throws Exception {
        String source = source("render-table.js");
        verifyScheduling(source);
        assertThrows(AssertionError.class, () -> verifyScheduling(source.replace(
                "new ResizeObserver(() => this.schedulePaint())", "new ResizeObserver(() => this.paint())")));
        assertThrows(AssertionError.class, () -> verifyScheduling(source.replace(
                "if (this.paintFrame !== null) return;", "")));
        assertThrows(AssertionError.class, () -> verifyScheduling(source.replace(
                "cancelAnimationFrame(this.paintFrame);", "")));
    }

    /** Повторное измерение сохраняет строки, а устаревшая модель не доходит до записи DOM. */
    @Test void tableGuardsGeometryAndRenderedRows() throws Exception {
        String source = source("render-table.js");
        verifyWrites(source);
        assertThrows(AssertionError.class, () -> verifyWrites(source.replace(
                "if (this.scroll.style.scrollbarGutter !== gutter)", "")));
        assertThrows(AssertionError.class, () -> verifyWrites(source.replace(
                "if (this.header.style.width !== value)", "")));
        assertThrows(AssertionError.class, () -> verifyWrites(source.replace(
                "|| this.model !== model", "")));
        assertThrows(AssertionError.class, () -> verifyWrites(source.replace(
                "|| rows.some((row, index) => row !== previous.rows[index])", "")));
    }

    /** Читает назначенный ресурс без запуска JavaScript, сервера или браузера. */
    private static String source(String name) throws Exception {
        return withoutDocumentation(Files.readString(MODULE.resolve("src/main/resources/web/app").resolve(name)));
    }

    /** Нормализует JSDoc перед исходниковыми контрактами, не удаляя исполняемые выражения. */
    private static String withoutDocumentation(String source) {
        return source.replaceAll("(?s)/\\*\\*.*?\\*/[\\t\\r\\n ]*", "");
    }

    /** Документирование callback не меняет контракт и не скрывает настоящую синхронную запись. */
    @Test void documentationDoesNotInvalidateContractsOrHideUnsafeObserver() throws Exception {
        String source = source("render-table.js");
        String documented = source.replace("new ResizeObserver(() => this.schedulePaint())",
                "new ResizeObserver(/** callback documentation */ () => this.schedulePaint())");
        verifyScheduling(withoutDocumentation(documented));
        assertThrows(AssertionError.class, () -> verifyScheduling(withoutDocumentation(documented.replace(
                "() => this.schedulePaint())", "() => this.paint())"))));
        assertEquals("rows.some((row, index) => row !== previous.rows[index])",
                withoutDocumentation("rows.some(/** row documentation */ (row, index) => row !== previous.rows[index])"));
    }

    /** Ограничивает проверку телом нужного метода или наблюдателя, исключая соседние совпадения. */
    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        assertTrue(from >= 0, start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(to >= 0, end);
        return source.substring(from, to);
    }

    /** Фиксирует уже внесённую защиту сводки от записи в цикле доставки наблюдений. */
    private static void verifySummary(String source) {
        String observer = between(source, "const observer = new ResizeObserver", "summaryObservers.set");
        assertTrue(observer.contains("if (pending) return;"));
        assertTrue(observer.contains("pending = true;"));
        assertTrue(observer.contains("requestAnimationFrame(() => { pending = false; layoutSummary(root); });"));
        String layout = between(source, "function layoutSummary(root)", "export function renderSummary");
        assertTrue(layout.contains("if (root.style.getPropertyValue('--cp-summary-columns') !== value)"));
        assertTrue(layout.contains("root.style.setProperty('--cp-summary-columns', value);"));
        assertTrue(layout.contains("Math.floor((width - (columns - 1) * gap) / columns)"));
        assertTrue(layout.contains("if (root.style.getPropertyValue('--cp-summary-card-width') !== cardWidth)"));
        assertTrue(layout.contains("root.style.setProperty('--cp-summary-card-width', cardWidth);"));
    }

    /** Фиксирует единственную отложенную перерисовку для серии resize и scroll. */
    private static void verifyScheduling(String source) {
        String constructor = between(source, "constructor(app)", "async update(model)");
        assertTrue(constructor.contains("new ResizeObserver(() => this.schedulePaint())"));
        assertTrue(constructor.contains("this.scroll.addEventListener('scroll', () => this.schedulePaint());"));
        String schedule = between(source, "schedulePaint() {", "layoutTable() {");
        assertTrue(schedule.contains("if (this.paintFrame !== null) return;"));
        assertTrue(schedule.contains("this.paintFrame = requestAnimationFrame(() => {"));
        assertTrue(schedule.contains("this.paintFrame = null;"));
        assertTrue(schedule.indexOf("this.paintFrame = null;") < schedule.indexOf("this.paint();"));
        String paint = between(source, "async paint() {", "widget(row, index) {");
        assertTrue(paint.contains("cancelAnimationFrame(this.paintFrame);"));
    }

    /** Проверяет защиту стилевых записей и сравнение содержимого перед заменой строк. */
    private static void verifyWrites(String source) {
        String layout = between(source, "layoutTable() {", "async paint() {");
        assertTrue(layout.contains("if (this.scroll.style.scrollbarGutter !== gutter) this.scroll.style.scrollbarGutter = gutter;"));
        assertTrue(layout.contains("if (this.header.style.width !== value) this.header.style.width = value;"));
        assertTrue(layout.contains("if (this.header.scrollLeft !== this.scroll.scrollLeft) this.header.scrollLeft = this.scroll.scrollLeft;"));
        String update = between(source, "async update(model)", "columnStyle(node, column)");
        assertTrue(update.contains("this.model = model; this.painted = null;"));
        String paint = between(source, "async paint() {", "widget(row, index) {");
        assertTrue(paint.contains("if (!this.app.transport.current(generation) || epoch !== this.epoch || this.model !== model) return;"));
        assertTrue(paint.contains("Math.min(model.rowCount - first, Math.ceil(this.scroll.clientHeight / 26) + 6) !== count"));
        assertTrue(paint.contains("this.schedulePaint(); return;"));
        assertTrue(paint.contains("if (!previous || previous.model !== model || previous.generation !== generation || previous.epoch !== epoch"));
        assertTrue(paint.contains("|| previous.first !== first || previous.rows.length !== rows.length"));
        assertTrue(paint.contains("|| rows.some((row, index) => row !== previous.rows[index])) {"));
        assertTrue(paint.contains("this.painted = {model, generation, epoch, first, rows};"));
        assertTrue(paint.indexOf("|| rows.some") < paint.indexOf("this.canvas.replaceChildren"));
        assertTrue(paint.indexOf("this.canvas.replaceChildren") < paint.indexOf("this.layoutTable();"));
    }
}
