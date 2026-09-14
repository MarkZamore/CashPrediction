package ru.cashprediction.core.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.text.CoreModuleDir;
import ru.cashprediction.core.text.JavaSourceScanner;
import ru.cashprediction.core.text.TextCatalog;
import ru.cashprediction.core.text.Texts;
import ru.cashprediction.core.util.RuText;

/**
 * Проверка нелокализуемого словаря формата ({@link FormatWords}, решение L13, этап S0.5).
 *
 * <p>Ресурс читается без ошибок и повторов; имена правильной формы; значения без пробелов по краям; каждое имя,
 * переданное литералом в {@code FormatWords.get}, существует; каждое имя используется литералом в основном коде ядра;
 * ресурс существует в одном экземпляре и никогда не получает суффикса языка; весь словарь совпадает с замороженным
 * списком, поэтому правка грамматики не проходит незаметно. Отсутствие кириллицы в коде ядра
 * проверяет {@code NoCyrillicLiteralsTest}, пересечение имён с ключами каталога — {@code UiTextCatalogTest}.</p>
 */
class FormatWordsTest {

    /** Основной код ядра, где ищутся ссылки на слова. */
    private static final Path CORE_MAIN = CoreModuleDir.resolve("src/main/java");

    /** Имя: латиница и цифры, части через точку. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9]+(\\.[A-Za-z0-9]+)*");
    /** Код прямо перед литералом-именем: обращение к словарю формата. */
    private static final Pattern LOOKUP_CALL = Pattern.compile("FormatWords\\s*\\.\\s*(?:get|has)\\s*\\(\\s*$");

    @Test
    void resourceLoadsWithoutProblemsOrDuplicates() {
        assertEquals(List.of(), FormatWords.loadProblems());
        assertEquals(List.of(), FormatWords.duplicates());
        assertTrue(FormatWords.names().size() > 100, "словарь заполнен: " + FormatWords.names().size());
    }

    @Test
    void namesAreWellFormedAndPrefixed() {
        List<String> bad = FormatWords.names().stream()
                .filter(name -> !NAME.matcher(name).matches()
                        || !(name.startsWith("plan.") || name.startsWith("common.") || name.startsWith("settings.")
                        || name.startsWith("session.md.")))
                .toList();
        assertEquals(List.of(), bad);
    }

    @Test
    void valuesHaveNoOuterSpacesAndAreNotEmpty() {
        // Properties молча съедает пробелы в начале значения, а редакторы — в конце: пробелы добавляет код.
        List<String> bad = new ArrayList<>();
        for (String name : FormatWords.names()) {
            String value = FormatWords.get(name);
            if (value.isEmpty() || !value.equals(value.strip())) {
                bad.add(name + "=«" + value + "»");
            }
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void missingNameAlwaysThrows() {
        // Даже без строгого режима текстов: заглушка вместо слова испортила бы файл пользователя.
        String previous = System.getProperty(TextCatalog.STRICT_PROPERTY);
        System.setProperty(TextCatalog.STRICT_PROPERTY, "false");
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> FormatWords.get("plan.no.such"));
            assertTrue(e.getMessage().contains("plan.no.such"), e.getMessage());
            assertFalse(FormatWords.has("plan.no.such"));
        } finally {
            if (previous == null) {
                System.clearProperty(TextCatalog.STRICT_PROPERTY);
            } else {
                System.setProperty(TextCatalog.STRICT_PROPERTY, previous);
            }
        }
    }

    @Test
    void loaderIsStrictUtf8WithoutLanguageFallback() {
        TextCatalog good = FormatWords.load(name -> FormatWords.FILE_NAME.equals(name)
                ? new ByteArrayInputStream("plan.a=да\nplan.b=нет\nplan.b=ещё\n".getBytes(StandardCharsets.UTF_8)) : null);
        assertEquals("да", good.template("plan.a").orElseThrow());
        assertEquals(List.of(), good.loadProblems());
        assertEquals(1, good.duplicates().size(), "повтор имени виден тесту: " + good.duplicates());

        TextCatalog cp1251 = FormatWords.load(name -> FormatWords.FILE_NAME.equals(name)
                ? new ByteArrayInputStream("plan.a=да\n".getBytes(Charset.forName("windows-1251"))) : null);
        assertFalse(cp1251.loadProblems().isEmpty(), "файл не в UTF-8 - проблема загрузки");

        TextCatalog suffixed = FormatWords.load(name -> "format_ru.properties".equals(name)
                ? new ByteArrayInputStream("plan.a=да\n".getBytes(StandardCharsets.UTF_8)) : null);
        assertFalse(suffixed.has("plan.a"), "файл с суффиксом языка словарём формата не читается");
        assertFalse(suffixed.loadProblems().isEmpty());
    }

    @Test
    void everyNameLookedUpByLiteralExists() {
        List<String> missing = new ArrayList<>();
        int references = 0;
        for (JavaSourceScanner.Literal literal : JavaSourceScanner.literals(CORE_MAIN)) {
            if (LOOKUP_CALL.matcher(literal.codeBefore()).find()) {
                references++;
                if (!FormatWords.has(literal.raw())) {
                    missing.add(literal.file() + ":" + literal.line() + " " + literal.raw());
                }
            }
        }
        assertEquals(List.of(), missing, "слова, которых нет в format.properties");
        assertTrue(references > 100, "сканер находит обращения к FormatWords: " + references);
    }

    @Test
    void everyNameIsUsed() {
        Set<String> used = usedNames();
        List<String> unused = FormatWords.names().stream().filter(name -> !used.contains(name)).toList();
        assertEquals(List.of(), unused, "слова format.properties, на которые нет ссылок");
    }

    @Test
    void resourceIsNeverLocaleSuffixed() throws IOException {
        // Файлы пользователя читаются при любом языке интерфейса: словарь формата существует в одном экземпляре, без
        // суффикса языка, и загрузчик не ищет вариантов вида format_ru.properties.
        assertEquals("/ru/cashprediction/core/format/format.properties", FormatWords.RESOURCE);
        assertNotNull(FormatWords.class.getResource(FormatWords.RESOURCE), FormatWords.RESOURCE);
        Path resources = CoreModuleDir.resolve("src/main/resources");
        try (Stream<Path> files = Files.walk(resources)) {
            List<String> formatFiles = files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().matches("format([_.-].*)?\\.properties"))
                    .map(file -> resources.relativize(file).toString().replace('\\', '/'))
                    .toList();
            assertEquals(List.of("ru/cashprediction/core/format/format.properties"), formatFiles);
        }
        assertFalse(Texts.AREAS.contains("format"), "словарь формата - не область каталога текстов");
    }

    @Test
    void wholeGrammarIsFrozen() {
        // Все слова уже записаны в файлах пользователей или понимаются при их чтении: изменение любого значения, новое или
        // удалённое имя меняет формат (см. заголовок format.properties). Круговые тесты этого не ловят — писатель и
        // читатель берут слово из одного ресурса, — поэтому словарь целиком сверяется с замороженным списком, и любое
        // изменение грамматики требует явной правки этого теста.
        String actual = FormatWords.names().stream()
                .map(name -> name + "=" + FormatWords.get(name) + "\n")
                .collect(java.util.stream.Collectors.joining());
        assertEquals(FROZEN_GRAMMAR, actual);
        // Решение пользователя 2026-09-14 («Замени все знаки длинного тире в интерфейсе всех клиентов на "-"»): в файлах
        // CashMemory только дефис-минус, старые файлы с тире не поддерживаются. Слов с тире в словаре нет, а разделитель
        // заголовка окна web-session.md, который добавляет MarkdownSnapshotCodec, изменён с длинного тире на « - ».
        DashFreeOutput.assertNoDashes("format.properties", actual);
    }

    /** Замороженная грамматика формата: {@code имя=значение}, имена по алфавиту ({@link FormatWords#names()}). */
    private static final String FROZEN_GRAMMAR = """
            common.char.ye=е
            common.char.yo=ё
            common.weekday.accusative.3=среду
            common.weekday.accusative.5=пятницу
            common.weekday.accusative.6=субботу
            common.weekday.full.1=понедельник
            common.weekday.full.2=вторник
            common.weekday.full.3=среда
            common.weekday.full.4=четверг
            common.weekday.full.5=пятница
            common.weekday.full.6=суббота
            common.weekday.full.7=воскресенье
            common.weekday.short.1=пн
            common.weekday.short.2=вт
            common.weekday.short.3=ср
            common.weekday.short.4=чт
            common.weekday.short.5=пт
            common.weekday.short.6=сб
            common.weekday.short.7=вс
            plan.action.change=изменить
            plan.action.change.stem=измен
            plan.action.move=перенести
            plan.action.move.stem=перен
            plan.action.replace=заменить
            plan.action.replace.stem=замен
            plan.action.skip=пропустить
            plan.action.skip.stem=пропус
            plan.bool.no=нет
            plan.bool.yes=да
            plan.col.action=Действие
            plan.col.amount=Сумма
            plan.col.category=Категория
            plan.col.date=Дата
            plan.col.enabled=Активна
            plan.col.from=С
            plan.col.id=ID
            plan.col.kind=Тип
            plan.col.newAmount=Новая сумма
            plan.col.newDate=Новая дата
            plan.col.note=Заметка
            plan.col.originalDate=Исходная дата
            plan.col.recurrence=Повтор
            plan.col.rule=Правило
            plan.col.title=Название
            plan.col.until=По
            plan.col.weekend=Выходные
            plan.format.name=CashPrediction
            plan.horizon.until=до
            plan.key.currency=Валюта
            plan.key.cushion=Подушка безопасности
            plan.key.format=Формат
            plan.key.goal=Цель
            plan.key.goalDate=Цель к дате
            plan.key.goalTitle=Название цели
            plan.key.horizon=Горизонт
            plan.key.start=Начало
            plan.key.startBalance=Начальный баланс
            plan.kind.expense=расход
            plan.kind.income=доход
            plan.parameter.extrasAnchor=§Параметры:список
            plan.recurrence.daily=ежедневно
            plan.recurrence.daily.alias=каждый день
            plan.recurrence.daySuffix.ordinal=го
            plan.recurrence.daySuffix.word=числа
            plan.recurrence.every=каждые
            plan.recurrence.every.stem=кажд
            plan.recurrence.monthly=ежемесячно
            plan.recurrence.weekdayPreposition.1=по
            plan.recurrence.weekdayPreposition.2=в
            plan.recurrence.weekly=еженедельно
            plan.recurrence.yearly=ежегодно
            plan.section.adjustments=Корректировки
            plan.section.note=Заметка
            plan.section.oneTime=Разовые операции
            plan.section.parameters=Параметры
            plan.section.rules=Регулярные операции
            plan.title.word=План
            plan.unit.day.abbr=дн
            plan.unit.day.few=дня
            plan.unit.day.many=дней
            plan.unit.day.one=день
            plan.unit.month.abbr=мес
            plan.unit.month.few=месяца
            plan.unit.month.many=месяцев
            plan.unit.month.one=месяц
            plan.unit.month.stem=месяц
            plan.unit.week.abbr=нед
            plan.unit.week.few=недели
            plan.unit.week.many=недель
            plan.unit.week.one=неделю
            plan.unit.week.stem=недел
            plan.unit.year.few=года
            plan.unit.year.many=лет
            plan.unit.year.one=год
            plan.unparsed.lead=не разобрано, строка
            plan.weekend.next=позже
            plan.weekend.next.alias=на понедельник
            plan.weekend.none=нет
            plan.weekend.none.alias=не сдвигать
            plan.weekend.previous=раньше
            plan.weekend.previous.alias=на пятницу
            session.md.key.bounds=Границы
            session.md.key.context=Контекст
            session.md.key.dirty=Несохранённые изменения
            session.md.key.filterText=Строка поиска
            session.md.key.filters=Фильтры
            session.md.key.maximized=Развёрнуто
            session.md.key.period=Период
            session.md.key.pid=PID сервера
            session.md.key.plan=План
            session.md.key.saved=Сохранено
            session.md.key.schema=Схема
            session.md.key.selected=Выделено
            session.md.key.started=Начата
            session.md.key.state=Состояние
            session.md.key.view=Вид
            session.md.key.whatIfExtra=Что-если, доп. экономия в месяц
            session.md.modal=модальное
            session.md.modeless=немодальное
            session.md.no=нет
            session.md.owner=владелец
            session.md.section.main=## Главное окно
            session.md.section.plan=## Несохранённый план
            session.md.section.windows=## Открытые окна
            session.md.title.prefix=# Сессия CashPrediction (
            session.md.yes=да
            settings.key.autosave=Автосохранение плана
            settings.key.chartBars=Столбцы по месяцам
            settings.key.chartMarkers=Маркеры на графике
            settings.key.lastPlan=Последний план
            settings.key.monthTotals=Итоги по месяцам
            settings.key.period=Период
            settings.key.recentPlans=Недавние планы
            settings.key.recoveryStore=Хранилище восстановления
            settings.key.showExpense=Показывать расходы
            settings.key.showIncome=Показывать доходы
            settings.key.showOneTime=Показывать разовые
            settings.key.showSkipped=Показывать пропущенные
            settings.key.summaryPanel=Панель сводки
            settings.key.view=Вид
            settings.period.all=Весь горизонт
            settings.period.all.alias1=весь
            settings.period.all.alias2=все
            settings.period.m12=12 месяцев
            settings.period.m24=24 месяца
            settings.period.m3=3 месяца
            settings.period.m6=6 месяцев
            settings.recoveryStore.registry=реестр
            settings.recoveryStore.xml=XML
            settings.title=# Настройки CashPrediction
            settings.view.chart=график
            settings.view.table=таблица
            """;

    @Test
    void weekdayWordsOfFormatAndInterfaceAreIndependentButEqualToday() {
        // Два независимых источника (файл и интерфейс); сейчас значения совпадают, при переводе интерфейса разойдутся.
        for (DayOfWeek day : DayOfWeek.values()) {
            assertEquals(RuText.weekdayShort(day), FormatWords.weekdayShort(day), day.name());
            assertEquals(RuText.weekdayFull(day), FormatWords.weekdayFull(day), day.name());
        }
        assertEquals("сб", FormatWords.weekdayShort(DayOfWeek.SATURDAY));
        assertEquals("воскресенье", FormatWords.weekdayFull(DayOfWeek.SUNDAY));
    }

    /** @return имена словаря, встречающиеся строковым литералом в основном коде ядра */
    private static Set<String> usedNames() {
        Set<String> names = FormatWords.names();
        Set<String> used = new LinkedHashSet<>();
        for (JavaSourceScanner.Literal literal : JavaSourceScanner.literals(CORE_MAIN)) {
            if (names.contains(literal.raw())) {
                used.add(literal.raw());
            }
        }
        return used;
    }
}
