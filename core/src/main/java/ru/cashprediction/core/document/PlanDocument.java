package ru.cashprediction.core.document;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Warning;
import ru.cashprediction.core.forecast.WarningType;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.recurrence.OccurrenceGenerator;
import ru.cashprediction.core.util.DateFormats;

/**
 * Открытый документ: текущий план, связанный файл, признак несохранённых изменений, отмена/повтор,
 * параметры отображения и лениво вычисляемый прогноз.
 *
 * <p>Это единственный изменяемый объект модели. Сам {@link Plan} неизменяем, поэтому каждое изменение —
 * это замена ссылки на новый план, а история отмены — просто стек прежних планов (глубина {@value #UNDO_LIMIT}).
 * Все три клиента (JavaFX, Swing, Web) работают через этот класс, поэтому команды меню «Правка»
 * и «Инструменты» (актуализация, сверка, применение «что-если», очистка корректировок) реализованы здесь
 * один раз и ведут себя одинаково.</p>
 *
 * <p>Слушатели получают {@link DocumentEvent} синхронно, в том же потоке, сразу после изменения.</p>
 *
 * <p><b>Потоки.</b> Класс не потокобезопасен и должен использоваться из одного потока: в desktop-клиентах —
 * из UI-потока (FX Application Thread / EDT), на web-сервере — под внешней синхронизацией (один монитор
 * на сессию). Прогноз, возвращаемый {@link #forecast()}, неизменяем, и его можно передавать в другие потоки.</p>
 */
public final class PlanDocument {

    /** Глубина истории отмены. */
    public static final int UNDO_LIMIT = 100;

    /** Название разовой операции, которую создаёт {@link #reconcile}. */
    public static final String RECONCILE_TITLE = "Сверка баланса";

    /** Название регулярной операции, которую создаёт {@link #applyWhatIfToPlan()}. */
    public static final String EXTRA_SAVING_TITLE = "Доп. экономия";

    /**
     * Запись истории: план до изменения и описание изменения для пунктов «Отменить …»/«Повторить …».
     *
     * @param description описание изменения на русском
     * @param plan        план, к которому вернёт отмена (или повтор)
     */
    private record HistoryEntry(String description, Plan plan) {
    }

    /** Источник «сегодня»: в приложении — системные часы, в тестах — фиксированная дата. */
    private final Supplier<LocalDate> today;

    /** Стек отмены: первым лежит последнее изменение. */
    private final Deque<HistoryEntry> undoStack = new ArrayDeque<>();

    /** Стек повтора: первым лежит последнее отменённое изменение. */
    private final Deque<HistoryEntry> redoStack = new ArrayDeque<>();

    /** Подписчики на события документа. */
    private final List<Consumer<DocumentEvent>> listeners = new ArrayList<>();

    private Plan plan;
    private Path file;
    private boolean dirty;

    /**
     * План в том виде, в каком он лежит в файле; {@code null}, если такого состояния нет
     * (документ восстановлен из снимка с несохранёнными изменениями). Нужен, чтобы отмена
     * до сохранённого состояния снимала признак «изменён».
     */
    private Plan savedPlan;

    private List<Diagnostic> loadDiagnostics = List.of();
    private ViewState viewState = ViewState.defaults();

    /** Кэш прогноза; {@code null} — нужно пересчитать. */
    private Forecast forecast;

    /**
     * Создаёт документ без несохранённых изменений и без истории.
     *
     * @param plan  план
     * @param file  файл плана или {@code null}, если план ещё не сохранялся
     * @param today источник сегодняшней даты
     */
    public PlanDocument(Plan plan, Path file, Supplier<LocalDate> today) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.today = Objects.requireNonNull(today, "today");
        this.file = file;
        this.savedPlan = plan;
    }

    // ------------------------------------------------------------------ состояние

    /** @return текущий план */
    public Plan plan() {
        return plan;
    }

    /** @return файл плана; пусто, если план ещё не сохранялся */
    public Optional<Path> file() {
        return Optional.ofNullable(file);
    }

    /** @return {@code true}, если есть несохранённые изменения */
    public boolean isDirty() {
        return dirty;
    }

    /** @return диагностика чтения файла, с которым открыт документ (пустой список, если её нет) */
    public List<Diagnostic> loadDiagnostics() {
        return loadDiagnostics;
    }

    /**
     * Сегодняшняя дата из источника, переданного в конструктор.
     *
     * @return сегодня
     */
    public LocalDate today() {
        return Objects.requireNonNull(today.get(), "Источник даты вернул null");
    }

    // ------------------------------------------------------------------ правка и история

    /**
     * Выполняет изменение плана как один шаг истории.
     *
     * <p>Если функция вернула план, равный текущему, ничего не происходит: ни записи в истории,
     * ни признака «изменён», ни событий. Иначе прежний план уходит в стек отмены (самые старые записи сверх
     * {@value #UNDO_LIMIT} отбрасываются), стек повтора очищается, документ помечается изменённым,
     * кэш прогноза сбрасывается и рассылается событие {@link EventKind#PLAN}
     * (вместе с {@link EventKind#DIRTY}, если признак «изменён» поменялся).</p>
     *
     * @param description описание для пунктов «Отменить …»/«Повторить …», например «Добавление правила»
     * @param change      функция, строящая новый план из текущего; исключение из неё оставляет документ как был
     */
    public void edit(String description, UnaryOperator<Plan> change) {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(change, "change");
        Plan next = Objects.requireNonNull(change.apply(plan), "Изменение плана вернуло null");
        if (next.equals(plan)) {
            return;
        }
        push(undoStack, new HistoryEntry(description, plan));
        redoStack.clear();
        applyPlan(next, true);
    }

    /** @return есть ли что отменять */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /** @return есть ли что повторять */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** @return описание изменения, которое отменит {@link #undo()} */
    public Optional<String> undoDescription() {
        return Optional.ofNullable(undoStack.peekFirst()).map(HistoryEntry::description);
    }

    /** @return описание изменения, которое повторит {@link #redo()} */
    public Optional<String> redoDescription() {
        return Optional.ofNullable(redoStack.peekFirst()).map(HistoryEntry::description);
    }

    /**
     * Отменяет последнее изменение; без истории ничего не делает.
     * Признак «изменён» снимается, если план вернулся ровно к сохранённому состоянию.
     */
    public void undo() {
        HistoryEntry entry = undoStack.pollFirst();
        if (entry == null) {
            return;
        }
        push(redoStack, new HistoryEntry(entry.description(), plan));
        applyPlan(entry.plan(), !entry.plan().equals(savedPlan));
    }

    /**
     * Повторяет последнее отменённое изменение; если повторять нечего, ничего не делает.
     */
    public void redo() {
        HistoryEntry entry = redoStack.pollFirst();
        if (entry == null) {
            return;
        }
        push(undoStack, new HistoryEntry(entry.description(), plan));
        applyPlan(entry.plan(), !entry.plan().equals(savedPlan));
    }

    /**
     * Заменяет документ целиком: после «Новый план», «Открыть» или восстановления сессии.
     * История очищается (отменять открытие файла бессмысленно), рассылается событие
     * {@link EventKind#PLAN} + {@link EventKind#FILE} + {@link EventKind#DIRTY}.
     *
     * @param plan        новый план
     * @param file        его файл или {@code null}
     * @param dirty       есть ли у плана несохранённые изменения (например, восстановлен из снимка)
     * @param diagnostics диагностика чтения ({@code null} — нет)
     */
    public void replace(Plan plan, Path file, boolean dirty, List<Diagnostic> diagnostics) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.file = file;
        this.dirty = dirty;
        // Для восстановленного «грязного» плана сохранённой версии в памяти нет: отмена не сделает его чистым.
        this.savedPlan = dirty ? null : plan;
        this.loadDiagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        undoStack.clear();
        redoStack.clear();
        forecast = null;
        fire(DocumentEvent.of(EventKind.PLAN, EventKind.FILE, EventKind.DIRTY));
    }

    /**
     * Отмечает успешное сохранение текущего плана в файл («Сохранить» или «Сохранить как»).
     * История не очищается: после сохранения изменения по-прежнему можно отменить.
     *
     * @param file файл, в который сохранён план
     */
    public void markSaved(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.dirty = false;
        this.savedPlan = plan;
        fire(DocumentEvent.of(EventKind.FILE, EventKind.DIRTY));
    }

    // ------------------------------------------------------------------ вид и прогноз

    /** @return текущие параметры отображения */
    public ViewState viewState() {
        return viewState;
    }

    /**
     * Меняет параметры отображения и рассылает {@link EventKind#VIEW}. Прогноз пересчитывается только при
     * изменении «что-если» или показа пропущенных событий: фильтры и период на сам расчёт не влияют.
     * Равный текущему вид игнорируется.
     *
     * @param state новые параметры
     */
    public void setViewState(ViewState state) {
        Objects.requireNonNull(state, "state");
        if (state.equals(viewState)) {
            return;
        }
        if (viewState.affectsForecast(state)) {
            forecast = null;
        }
        viewState = state;
        fire(DocumentEvent.of(EventKind.VIEW));
    }

    /**
     * Прогноз текущего плана с «что-если» и показом пропущенных из {@link #viewState()}.
     *
     * <p>Результат кэшируется до изменения плана или влияющих на расчёт параметров вида. Кэш также
     * сбрасывается, если сменилось «сегодня» (приложение оставили открытым на ночь): иначе отметки
     * «прошедшие» и карточки «через N месяцев» устарели бы.</p>
     *
     * @return прогноз
     * @throws IllegalStateException если горизонт или число дат правила превышают пределы движка
     */
    public Forecast forecast() {
        LocalDate now = today();
        if (forecast == null || !forecast.today().equals(now)) {
            forecast = ForecastEngine.forecast(plan, viewState.whatIf(), now, viewState.showSkipped());
        }
        return forecast;
    }

    /**
     * Строки для таблицы: от начала плана до конца выбранного периода, прошедшие фильтры вида.
     *
     * @return неизменяемый список строк; «Начальный баланс» входит всегда
     */
    public List<ForecastRow> visibleRows() {
        Forecast f = forecast();
        LocalDate end = viewState.periodEnd(plan, f.anchor());
        List<ForecastRow> result = new ArrayList<>();
        for (ForecastRow row : f.rowsBetween(f.startDate(), end)) {
            if (viewState.accepts(row)) {
                result.add(row);
            }
        }
        return List.copyOf(result);
    }

    // ------------------------------------------------------------------ команды

    /**
     * «Актуализировать на сегодня»: переносит дату начала плана на {@code today} и удаляет разовые операции,
     * которые уже в прошлом ({@code date < today}). Один шаг истории.
     *
     * <p>Начальный баланс по умолчанию — прогнозный баланс на <i>начало</i> дня {@code today}, то есть на
     * конец предыдущего дня. Именно так: начальный баланс плана действует «до событий дня начала»,
     * а события самого {@code today} (зарплата, сегодняшняя разовая операция) после актуализации снова
     * попадут в прогноз; взяв баланс на конец дня, мы учли бы их дважды. Баланс считается по плану без
     * «что-если», чтобы гипотетические коэффициенты не попали в реальные данные.</p>
     *
     * <p>Правилам, фаза которых отсчитывается от даты начала плана («каждые 2 месяца», «каждые 3 дня» с пустым
     * полем «С»), в поле «С» записывается прежняя дата начала: иначе перенос начала сдвинул бы их расписание.</p>
     *
     * @param today           новая дата начала
     * @param balanceOverride фактический баланс на начало дня {@code today}; {@code null} — взять из прогноза
     */
    public void actualize(LocalDate today, Money balanceOverride) {
        Objects.requireNonNull(today, "today");
        LocalDate oldStart = plan.startDate();
        Money balance = balanceOverride != null ? balanceOverride : plainForecast().balanceAt(today.minusDays(1));
        edit("Актуализация на " + DateFormats.ru(today), p -> {
            List<RecurringRule> rules = new ArrayList<>();
            for (RecurringRule rule : p.rules()) {
                boolean pinPhase = rule.from() == null && rule.recurrence().needsAnchor() && !today.equals(oldStart);
                rules.add(pinPhase ? withFrom(rule, oldStart) : rule);
            }
            return p.withStart(today, balance)
                    .withRules(rules)
                    .withOneTimesRemovedIf(tx -> tx.date().isBefore(today));
        });
    }

    /**
     * «Сверить баланс»: добавляет разовую операцию «Сверка баланса» на разницу между фактическим балансом
     * и прогнозом на конец дня {@code today}, так что после сверки прогноз на этот день совпадает с фактом.
     * Прогноз берётся без «что-если». При нулевой разнице ничего не происходит.
     *
     * @param today         дата сверки
     * @param actualBalance фактический баланс на конец дня {@code today}
     * @throws IllegalArgumentException если дата вне горизонта прогноза (разовая операция там не учитывалась бы)
     */
    public void reconcile(LocalDate today, Money actualBalance) {
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(actualBalance, "actualBalance");
        if (today.isBefore(plan.startDate()) || today.isAfter(plan.endDate())) {
            throw new IllegalArgumentException("Сверить баланс можно только на дату внутри горизонта прогноза ("
                    + DateFormats.ru(plan.startDate()) + " – " + DateFormats.ru(plan.endDate()) + ")");
        }
        Money expected = plainForecast().balanceAt(today);
        Money difference = actualBalance.minus(expected);
        if (difference.isZero()) {
            return;
        }
        Kind kind = difference.isPositive() ? Kind.INCOME : Kind.EXPENSE;
        edit(RECONCILE_TITLE, p -> p.withOneTimeAdded(new OneTimeTransaction(p.nextTxId(), today, RECONCILE_TITLE, kind,
                difference.abs(), "", "Прогноз: " + expected.format(p.currency()) + ", факт: " + actualBalance.format(p.currency()))));
    }

    /**
     * «Применить что-если к плану»: переносит текущие параметры «что-если» в сам план и выключает режим.
     *
     * <p>Суммы правил, разовых операций и корректировок «изменить»/«заменить» умножаются на коэффициент своего
     * типа с тем же округлением, что и в движке; при ненулевой дополнительной экономии добавляется правило дохода
     * «Доп. экономия» в последний день месяца (день 31) с датой «С» = {@code max(начало плана, сегодня)}
     * и идентификатором, не занятым ни правилом, ни корректировкой.
     * В итоге прогноз плана после применения совпадает по дням с прогнозом «что-если» до применения.
     * Изменение плана — один шаг истории; затем вид получает {@link WhatIf#NONE}.</p>
     *
     * <p>Сумма 0,00 в плане недопустима ({@code PlanValidator}), а событие на ноль не меняет баланс, поэтому суммы,
     * которые после умножения (коэффициент 0 или округление) обнулились бы, в план не пишутся: правило
     * выключается с прежней суммой и прежними корректировками (при повторном включении вернётся как было),
     * разовая операция удаляется, корректировка «изменить»/«заменить» превращается в «пропустить».</p>
     *
     * @throws IllegalArgumentException если сумма включённого правила обнуляется округлением, а суммы его
     *                                  корректировок — нет: выключить правило, не потеряв эти события, нельзя
     */
    public void applyWhatIfToPlan() {
        WhatIf whatIf = viewState.whatIf();
        if (whatIf.isNone()) {
            return;
        }
        LocalDate now = today();
        edit("Применение «что-если» к плану", p -> {
            List<RecurringRule> rules = new ArrayList<>();
            Set<RuleId> switchedOff = new HashSet<>();
            for (RecurringRule rule : p.rules()) {
                Money scaled = scale(rule.amount(), factorFor(whatIf, rule.kind()));
                if (scaled.isPositive()) {
                    rules.add(rule.withAmount(scaled));
                } else {
                    rules.add(rule.withEnabled(false));
                    switchedOff.add(rule.id());
                }
            }
            List<OneTimeTransaction> oneTimes = new ArrayList<>();
            for (OneTimeTransaction tx : p.oneTimes()) {
                Money scaled = scale(tx.amount(), factorFor(whatIf, tx.kind()));
                if (scaled.isPositive()) {
                    oneTimes.add(new OneTimeTransaction(tx.id(), tx.date(), tx.title(), tx.kind(), scaled, tx.category(), tx.note()));
                }
            }
            List<Adjustment> adjustments = new ArrayList<>();
            for (Adjustment adjustment : p.adjustments()) {
                adjustments.add(scaleAdjustment(p, adjustment, whatIf, switchedOff));
            }
            Plan result = p.withRules(rules).withOneTimes(oneTimes).withAdjustments(adjustments);
            LocalDate anchor = now.isAfter(p.startDate()) ? now : p.startDate();
            Money saving = whatIf.extraMonthlySaving();
            // Если «сейчас» уже после конца горизонта, движок не создаёт строк экономии — и правило не нужно.
            if (saving.isPositive() && !anchor.isAfter(p.endDate())) {
                result = result.withRuleAdded(new RecurringRule(freeRuleId(result), EXTRA_SAVING_TITLE, Kind.INCOME, saving,
                        "", new Recurrence.Monthly(31, 1), anchor, null, WeekendPolicy.NONE, true, ""));
            }
            return result;
        });
        setViewState(viewState.withWhatIf(WhatIf.NONE));
    }

    /**
     * «Очистить неиспользуемые корректировки»: удаляет корректировки, о которых прогноз выдал предупреждение
     * {@link WarningType#ORPHAN_ADJUSTMENT} (правила нет или правило не создаёт событие в эту дату).
     * Корректировки вне горизонта и корректировки выключенных правил не трогаются — движок их сиротами не считает.
     * Все найденные удаляются одним шагом истории.
     *
     * @return число удалённых корректировок (0 — план не изменился)
     */
    public int removeOrphanAdjustments() {
        Set<LocalDate> warnedDates = new HashSet<>();
        for (Warning warning : forecast().warnings()) {
            if (warning.type() == WarningType.ORPHAN_ADJUSTMENT && warning.date() != null) {
                warnedDates.add(warning.date());
            }
        }
        if (warnedDates.isEmpty()) {
            return 0;
        }
        // В предупреждении есть дата, но нет структурированного ключа (идентификатор правила только в тексте),
        // поэтому для корректировок с этими датами условие «сироты» проверяется так же, как в движке.
        Plan p = plan;
        Map<RuleId, Set<LocalDate>> nominalCache = new HashMap<>();
        Set<OccurrenceKey> orphans = new HashSet<>();
        for (Adjustment adjustment : p.adjustments()) {
            OccurrenceKey key = adjustment.key();
            if (warnedDates.contains(key.originalDate()) && isOrphan(p, key, nominalCache)) {
                orphans.add(key);
            }
        }
        int count = (int) p.adjustments().stream().filter(a -> orphans.contains(a.key())).count();
        if (count == 0) {
            return 0;
        }
        edit("Удаление неиспользуемых корректировок: " + count,
                current -> current.withAdjustments(current.adjustments().stream().filter(a -> !orphans.contains(a.key())).toList()));
        return count;
    }

    // ------------------------------------------------------------------ слушатели

    /**
     * Подписывает слушателя на события документа.
     *
     * @param listener слушатель; вызывается синхронно в потоке, изменившем документ
     */
    public void addListener(Consumer<DocumentEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Отписывает слушателя; неизвестный слушатель игнорируется.
     *
     * @param listener слушатель
     */
    public void removeListener(Consumer<DocumentEvent> listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------ внутреннее

    /** Устанавливает новый план, сбрасывает кэш и рассылает события. */
    private void applyPlan(Plan next, boolean newDirty) {
        boolean dirtyChanged = dirty != newDirty;
        plan = next;
        dirty = newDirty;
        forecast = null;
        fire(dirtyChanged ? DocumentEvent.of(EventKind.PLAN, EventKind.DIRTY) : DocumentEvent.of(EventKind.PLAN));
    }

    /**
     * Рассылает событие. Слушатель может отписаться прямо во время рассылки, поэтому обходится копия списка.
     * Исключение одного слушателя не мешает остальным: первое из них пробрасывается после рассылки.
     */
    private void fire(DocumentEvent event) {
        RuntimeException failure = null;
        for (Consumer<DocumentEvent> listener : List.copyOf(listeners)) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Кладёт запись в вершину стека, отбрасывая самые старые записи сверх {@link #UNDO_LIMIT}. */
    private static void push(Deque<HistoryEntry> stack, HistoryEntry entry) {
        stack.addFirst(entry);
        while (stack.size() > UNDO_LIMIT) {
            stack.removeLast();
        }
    }

    /**
     * Прогноз для денежных команд: без «что-если». Кэш переиспользуется, когда «что-если» выключено;
     * показ пропущенных событий на балансы не влияет.
     */
    private Forecast plainForecast() {
        Forecast current = forecast();
        return current.whatIf().isNone() ? current : ForecastEngine.forecast(plan, WhatIf.NONE, current.today(), false);
    }

    /** @return копия правила с другой датой «С» */
    private static RecurringRule withFrom(RecurringRule rule, LocalDate from) {
        return new RecurringRule(rule.id(), rule.title(), rule.kind(), rule.amount(), rule.category(), rule.recurrence(),
                from, rule.until(), rule.weekendPolicy(), rule.enabled(), rule.note());
    }

    /** @return коэффициент «что-если» для типа операции */
    private static BigDecimal factorFor(WhatIf whatIf, Kind kind) {
        return kind == Kind.INCOME ? whatIf.incomeFactor() : whatIf.expenseFactor();
    }

    /** Умножает сумму так же, как движок: при коэффициенте 1 сумма не трогается вовсе. */
    private static Money scale(Money amount, BigDecimal factor) {
        return factor.compareTo(BigDecimal.ONE) == 0 ? amount : amount.times(factor);
    }

    /**
     * Масштабирует новую сумму корректировки коэффициентом типа её правила: движок умножает на коэффициент
     * уже скорректированную сумму. Корректировки без правила оставляются как есть — в прогноз они не попадают.
     * Корректировки выключенного из-за нулевой суммы правила тоже не трогаются, а обнулившаяся сумма
     * превращает корректировку в «пропустить»: вклад в баланс в обоих случаях нулевой.
     *
     * @param switchedOff правила, выключенные при применении, потому что их сумма обнулилась
     * @throws IllegalArgumentException если у включённого правила из {@code switchedOff} есть корректировка
     *                                  с ненулевой суммой после умножения
     */
    private static Adjustment scaleAdjustment(Plan plan, Adjustment adjustment, WhatIf whatIf, Set<RuleId> switchedOff) {
        Optional<RecurringRule> found = plan.findRule(adjustment.key().ruleId());
        if (found.isEmpty()) {
            return adjustment;
        }
        RecurringRule rule = found.get();
        BigDecimal factor = factorFor(whatIf, rule.kind());
        if (switchedOff.contains(rule.id())) {
            boolean keepsMoney = adjustment.action().newAmount().map(a -> scale(a, factor).isPositive()).orElse(false);
            if (rule.enabled() && keepsMoney) {
                throw new IllegalArgumentException("Нельзя применить «что-если»: сумма правила " + rule.id()
                        + (rule.title().isEmpty() ? "" : " «" + rule.title() + "»")
                        + " округляется до нуля, а у его корректировки от " + DateFormats.ru(adjustment.key().originalDate())
                        + " сумма остаётся. Выберите другой процент изменения");
            }
            return adjustment;
        }
        Adjustment.Action action = switch (adjustment.action()) {
            case Adjustment.ChangeAmount(Money amount) -> zeroToSkip(scale(amount, factor), new Adjustment.ChangeAmount(scale(amount, factor)));
            case Adjustment.Replace(Money amount, LocalDate date) -> zeroToSkip(scale(amount, factor), new Adjustment.Replace(scale(amount, factor), date));
            case Adjustment.Skip skip -> skip;
            case Adjustment.MoveDate move -> move;
        };
        return new Adjustment(adjustment.key(), action, adjustment.note());
    }

    /** @return {@code action}, если сумма положительна, иначе «пропустить» (сумма 0 в плане недопустима) */
    private static Adjustment.Action zeroToSkip(Money scaled, Adjustment.Action action) {
        return scaled.isPositive() ? action : new Adjustment.Skip();
    }

    /**
     * Свободный идентификатор для нового правила: не занят ни правилом, ни ключом корректировки.
     * {@link Plan#nextRuleId()} смотрит только на правила, и корректировка-«сирота» с тем же идентификатором
     * (например, её правило не разобралось при чтении файла) молча прицепилась бы к новому правилу.
     */
    private static RuleId freeRuleId(Plan plan) {
        Set<RuleId> used = new HashSet<>();
        plan.rules().forEach(rule -> used.add(rule.id()));
        plan.adjustments().forEach(adjustment -> used.add(adjustment.key().ruleId()));
        RuleId candidate = plan.nextRuleId();
        long number = Long.parseLong(candidate.value().substring(1));
        while (used.contains(candidate)) {
            candidate = new RuleId("r" + (++number));
        }
        return candidate;
    }

    /**
     * Условие «сироты» из движка (для даты внутри горизонта): правила нет, либо включённое правило в своём окне
     * «С/По» не создаёт событие в эту номинальную дату.
     */
    private static boolean isOrphan(Plan plan, OccurrenceKey key, Map<RuleId, Set<LocalDate>> nominalCache) {
        LocalDate start = plan.startDate();
        LocalDate end = plan.endDate();
        LocalDate date = key.originalDate();
        if (date.isBefore(start) || date.isAfter(end)) {
            return false;
        }
        Optional<RecurringRule> found = plan.findRule(key.ruleId());
        if (found.isEmpty()) {
            return true;
        }
        RecurringRule rule = found.get();
        if (!rule.enabled()) {
            return false;
        }
        LocalDate lo = OccurrenceGenerator.windowStart(rule, start);
        LocalDate hi = OccurrenceGenerator.windowEnd(rule, end);
        if (date.isBefore(lo) || date.isAfter(hi)) {
            return false;
        }
        Set<LocalDate> nominal = nominalCache.computeIfAbsent(rule.id(),
                id -> new HashSet<>(OccurrenceGenerator.nominalDates(rule, start, end)));
        return !nominal.contains(date);
    }
}
