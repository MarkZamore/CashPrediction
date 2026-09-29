package ru.cashprediction.core.ui.form;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import ru.cashprediction.core.app.WindowHandle;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/**
 * Сеанс одного открытого окна формы (архитектура §3.5): живёт в ядре (web - на сервере), принимает события
 * виджетов, держит {@link FormState}, вызывает {@link FormLogic} и отдаёт модели в {@link WindowHandle}.
 *
 * <p><b>Жизненный цикл (этап S1, core-forms-framework):</b></p>
 * <ol>
 *   <li>Контроллер создаёт сеанс, вызывает {@code UiPort.openForm(session, spec(), view(), placement)} и
 *       {@link #attach(WindowHandle)}.</li>
 *   <li>Клиент показал окно → {@link #shown()}: для {@code FormSpec.restorable} - {@link Host#registered} (запись
 *       сеанса регистрирует окно ровно один раз).</li>
 *   <li>Каждое изменение виджета → {@link #fieldChanged}: значение переводится в каноническую форму
 *       ({@code FieldCodec.canonical}, некорректный текст хранится как есть), {@code evaluate}, новая ревизия,
 *       {@link Host#touched} (запись сама откладывает сохранение). Правило эха: номер правки клиента запоминается
 *       ({@link #lastClientRev()}), web помечает им эффект {@code form.view}, клиент не перезаписывает поле в фокусе
 *       своим же эхом.</li>
 *   <li>Кнопка → {@link #buttonPressed}: {@code onButton}; результат обрабатывает {@link #apply(FormOutcome)}.</li>
 *   <li>Выбор в списке предпросмотра → {@link #previewSelected}: индекс запоминается в {@link FormState#previewIndex()},
 *       затем {@code onPreview}.</li>
 *   <li>Двойной щелчок или Enter в списке ({@code LIST}) → {@link #fieldActivated}; Enter в однострочном поле →
 *       {@link #fieldSubmitted} (см. ниже).</li>
 *   <li>Крестик или Esc → {@link #closeRequested()} = кнопка роли CANCEL.</li>
 *   <li>Окно закрыто клиентом → {@link #closed()}: {@link Host#unregistered} ровно один раз.</li>
 * </ol>
 *
 * <p><b>Модель для клиента.</b> Сеанс дополняет модель логики: у каждого поля раскладки есть запись
 * {@code FormView.fields}; {@code FieldView.value} - показываемый текст значения ({@code FieldCodec.display}) или
 * значение, заданное логикой, а у поля, которое пользователь сейчас печатает ({@code committed = false}), -
 * {@code null}, чтобы не переформатировать текст под курсором. Пока в строке проблем ошибка, кнопки ролей OK и
 * FINISH отключены (§6.0 «Поведение»). Если после пересчёта список предпросмотра изменился, выбор в нём снимается.
 * Исключение логики (ошибка программы) не роняет окно: оно показывается строкой проблем.</p>
 *
 * <p><b>Обработка {@link FormOutcome}</b> (одна для кнопок, предпросмотра, активации и Enter): {@code Stay} - строка
 * проблем; {@code SetFields} - новые значения полей и пересчёт; {@code Page} - смена страницы; {@code OpenChild} -
 * {@link Host#openChild}; {@code Apply} - {@link Host#applied}, затем {@code fieldUpdates} и пересчёт; {@code Close}
 * - {@link Host#closed} с результатом, затем {@link Host#unregistered} и {@code handle.close()}.</p>
 *
 * <p><b>Ошибка применения</b> (§6.0 «Ошибка ядра при применении показывается в строке проблем, диалог остаётся
 * открытым»): если {@link Host#closed} с непустым результатом или {@link Host#applied} бросает
 * {@link RuntimeException}, окно остаётся открытым, а сообщение исключения становится ошибкой строки проблем до
 * следующего изменения поля.</p>
 *
 * <p><b>Клавиши Enter и Esc в окне формы</b> клиент не отправляет в {@code UiIntents.key}:</p>
 * <ul>
 *   <li>Enter в однострочном поле (TEXT, MONEY, DATE, MONTH_DAY, SPINNER, EDITABLE_CHOICE): клиент сначала передаёт
 *       текст поля ({@link #fieldChanged} с {@code committed = true}), затем {@link #fieldSubmitted}. Ядро спрашивает
 *       {@code FormLogic.onFieldSubmitted}; пустой ответ означает «как Enter по форме» - нажать кнопку по умолчанию,
 *       если она есть и доступна (§6.6: у калькулятора цели её нет, Enter ничего не делает).</li>
 *   <li>Enter вне однострочного поля (флажок, радио, кнопка без фокуса) - {@code buttonPressed(defaultButtonId)},
 *       если кнопка доступна; Enter в LIST - {@link #fieldActivated} выбранного элемента; в MULTILINE Enter -
 *       перевод строки.</li>
 *   <li>Esc - {@link #closeRequested()}.</li>
 *   <li>{@code Presentation.POPUP} (быстрая правка суммы, §5.6.1): видимых кнопок нет, но {@code FormSpec.defaultButtonId}
 *       задан ({@code ok}); Enter идёт тем же путём через {@link #fieldSubmitted} (у POPUP скрытая, но доступная кнопка
 *       нажимается), Esc и щелчок вне окна - {@link #closeRequested()}.</li>
 * </ul>
 *
 * <p><b>Снимок.</b> {@link #captureState()} возвращает {@code WindowState(windowId, type, modal, ownerId,
 * handle.bounds(), контекст + page, значения FormState)}; {@link #applyState(WindowState)} восстанавливает их
 * дословно (значения, страницу, границы) и перерисовывает форму. Ключ {@code page} пишется, если он есть в
 * {@code WindowType.contextKeys()} (мастер) или страница не первая. Выбор в предпросмотре ({@code previewIndex}) в
 * снимок не пишется (правило R3: схема снимка меняется только добавлениями из архитектуры) и после восстановления
 * равен -1.</p>
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class FormSession implements StatefulWindow {

    /**
     * Контроллер, которому сеанс сообщает о событиях окна.
     */
    public interface Host {

        /**
         * Окно показано и должно попасть в запись сеанса ({@code SessionRecorder.register}).
         *
         * @param session сеанс
         */
        void registered(FormSession session);

        /**
         * Окно закрыто и должно уйти из записи ({@code SessionRecorder.unregister}).
         *
         * @param session сеанс
         */
        void unregistered(FormSession session);

        /**
         * Значения или границы изменились ({@code SessionRecorder.touch}).
         *
         * @param session сеанс
         */
        void touched(FormSession session);

        /**
         * Форма закрывается с результатом. Вызывается до {@link #unregistered} и {@code handle.close()}: если результат
         * не {@code null} и применить его нельзя, контроллер бросает {@link RuntimeException} с текстом для пользователя
         * (до того, как убрал окно из своих списков) - окно остаётся открытым, текст попадает в строку проблем.
         *
         * @param session сеанс
         * @param result  результат {@code FormOutcome.Close} или {@code null} при отмене
         */
        void closed(FormSession session, Object result);

        /**
         * Форма просит открыть дочернее окно.
         *
         * @param parent сеанс-владелец
         * @param child  состояние дочернего окна (id - {@code FormOutcome.OpenChild.PENDING_ID}, его заменяет контроллер)
         */
        void openChild(FormSession parent, WindowState child);

        /**
         * Форма просит выполнить действие, оставаясь открытой ({@code FormOutcome.Apply}); после него сеанс
         * применяет {@code fieldUpdates} и пересчитывает модель. {@link RuntimeException} - действие не выполнено:
         * текст в строке проблем, {@code fieldUpdates} не применяются.
         *
         * @param session сеанс
         * @param action  действие
         */
        void applied(FormSession session, Object action);
    }

    private final String windowId;
    private final String ownerId;
    private final WindowType windowType;
    private final boolean modal;
    private final FormLogic logic;
    private final Host host;

    private FormContext context;
    private WindowHandle handle;
    private FormSpec spec;
    /** Вид поля по id в порядке раскладки (первое появление: RADIO может встречаться в нескольких строках). */
    private Map<String, FieldKind> kinds;
    /** id кнопок внутри формы: кнопки колонки предпросмотра и поля BUTTON. */
    private Set<String> innerButtons;
    private FormState state;
    private FormView view;
    private long revision;
    private long lastClientRev;
    /** Проблема из {@code FormOutcome.Stay} или ошибки применения: держится до следующего изменения поля. */
    private Problem stayProblem = Problem.NONE;
    private WindowBounds bounds;
    private boolean registered;
    private boolean unregistered;
    private boolean closing;
    private boolean finished;

    /**
     * Создаёт сеанс. Логика формы не вызывается до {@link #spec()} / {@link #view()}.
     *
     * @param windowType тип окна снимка
     * @param modal      модальное ли окно
     * @param logic      логика формы
     * @param context    окружение (id окна, владелец, контекст, состояние приложения)
     * @param host       контроллер
     */
    public FormSession(WindowType windowType, boolean modal, FormLogic logic, FormContext context, Host host) {
        this.windowType = Objects.requireNonNull(windowType, "windowType");
        this.modal = modal;
        this.logic = Objects.requireNonNull(logic, "logic");
        this.context = Objects.requireNonNull(context, "context");
        this.host = Objects.requireNonNull(host, "host");
        this.windowId = context.windowId();
        this.ownerId = context.ownerId();
    }

    @Override
    public String windowId() {
        return windowId;
    }

    @Override
    public WindowType windowType() {
        return windowType;
    }

    @Override
    public boolean modal() {
        return modal;
    }

    @Override
    public String ownerId() {
        return ownerId;
    }

    /** @return логика формы */
    public FormLogic logic() {
        return logic;
    }

    /** @return текущее окружение формы (после {@link #documentChanged} - новое) */
    public FormContext context() {
        return context;
    }

    /** @return контроллер сеанса */
    public Host host() {
        return host;
    }

    /**
     * Привязывает ручку окна, открытого клиентом.
     *
     * @param handle ручка
     */
    public void attach(WindowHandle handle) {
        this.handle = Objects.requireNonNull(handle, "handle");
    }

    /**
     * Ручка окна: поднять наверх ({@code toFront}, повторный вызов калькулятора цели §6.6), границы для снимка.
     *
     * @return ручка или {@code null}, пока {@link #attach(WindowHandle)} не вызван
     */
    public WindowHandle handle() {
        return handle;
    }

    /** @return раскладка формы (вычисляется один раз) */
    public FormSpec spec() {
        ensureStarted();
        return spec;
    }

    /** @return текущая модель формы */
    public FormView view() {
        ensureStarted();
        return view;
    }

    /** @return текущие значения формы */
    public FormState state() {
        ensureStarted();
        return state;
    }

    /**
     * Номер последней правки клиента, принятой {@link #fieldChanged} (правило эха: web отвечает эффектом
     * {@code form.view} с {@code echoOf.clientRev}).
     *
     * @return номер или 0, если правок ещё не было
     */
    public long lastClientRev() {
        return lastClientRev;
    }

    /** @return закрыта ли форма (после {@code Close}, отмены или {@link #closed()}); события закрытой формы игнорируются */
    public boolean isClosed() {
        return finished;
    }

    /**
     * Виджет поля изменился.
     *
     * @param fieldId   id поля
     * @param raw       текст или значение виджета как есть
     * @param committed {@code true} при потере фокуса, выборе или перед {@link #fieldSubmitted} (поле
     *                  переформатируется: «80000» → «80 000,00»)
     * @param clientRev номер правки клиента для правила эха
     * @return новая модель формы (она же уходит в {@code WindowHandle.update})
     */
    public FormView fieldChanged(String fieldId, String raw, boolean committed, long clientRev) {
        Objects.requireNonNull(fieldId, "fieldId");
        ensureStarted();
        if (finished) {
            return view;
        }
        FieldKind kind = kinds.getOrDefault(fieldId, FieldKind.TEXT);
        Map<String, String> values = new LinkedHashMap<>(state.values());
        values.put(fieldId, FieldCodec.canonical(kind, raw));
        state = new FormState(state.page(), values, state.previewIndex());
        stayProblem = Problem.NONE;
        lastClientRev = clientRev;
        recompute(committed ? null : fieldId);
        push();
        touch();
        return view;
    }

    /**
     * Нажата кнопка (панели, внутри формы или кнопка по умолчанию по Enter).
     *
     * @param buttonId id кнопки; недоступная или скрытая кнопка игнорируется
     */
    public void buttonPressed(String buttonId) {
        ensureStarted();
        if (finished || closing || buttonId == null || !pressable(buttonId)) {
            return;
        }
        apply(guard(() -> logic.onButton(buttonId, state, context)));
    }

    /**
     * Выбран или активирован элемент предпросмотра. Индекс запоминается в {@link FormState#previewIndex()} (-1, если
     * элемент нельзя выбрать), после чего вызывается {@code FormLogic.onPreview} и модель пересчитывается.
     *
     * @param index     номер элемента или -1 - выбор снят
     * @param activated двойной щелчок или пункт контекстного меню «Скорректировать эту дату…»
     */
    public void previewSelected(int index, boolean activated) {
        ensureStarted();
        if (finished || closing) {
            return;
        }
        boolean selectable = index >= 0 && index < view.preview().size() && view.preview().get(index).selectable();
        int accepted = selectable ? index : FormState.NO_PREVIEW;
        state = state.withPreviewIndex(accepted);
        apply(guard(() -> logic.onPreview(accepted, activated, state, context)));
    }

    /**
     * Элемент списка ({@code FieldKind.LIST}) активирован: двойной щелчок или Enter. Примеры: «Открыть план» -
     * двойной щелчок = «Открыть» (§6.10); «Выбор файла» - папка открывается, файл выбирается (§6.21). Значение поля
     * (выбранный элемент) клиент уже передал через {@link #fieldChanged}. Вызывает {@code FormLogic.onFieldActivated}.
     *
     * @param fieldId id поля-списка
     * @param index   номер активированного элемента
     */
    public void fieldActivated(String fieldId, int index) {
        ensureStarted();
        if (finished || closing) {
            return;
        }
        apply(guard(() -> logic.onFieldActivated(fieldId, index, state, context)));
    }

    /**
     * Enter в однострочном поле (правила - в описании класса). Вызывает {@code FormLogic.onFieldSubmitted}; пустой
     * ответ - {@code buttonPressed(spec().defaultButtonId())}, если кнопка по умолчанию задана и доступна. Пример
     * своего ответа: поле пути «Выбора файла» - перейти в папку, а не нажимать OK (§6.21).
     *
     * @param fieldId id поля
     */
    public void fieldSubmitted(String fieldId) {
        ensureStarted();
        if (finished || closing) {
            return;
        }
        Optional<FormOutcome> own;
        try {
            own = Objects.requireNonNullElse(logic.onFieldSubmitted(fieldId, state, context), Optional.empty());
        } catch (RuntimeException e) {
            own = Optional.of(new FormOutcome.Stay(Problem.error(message(e))));
        }
        if (own.isPresent()) {
            apply(own.get());
        } else if (!spec.defaultButtonId().isEmpty()) {
            buttonPressed(spec.defaultButtonId());
        }
    }

    /** Крестик окна, Esc или (для POPUP) щелчок вне окна: как кнопка роли CANCEL. */
    public void closeRequested() {
        ensureStarted();
        if (finished || closing) {
            return;
        }
        Optional<ButtonSpec> cancel = spec.buttons().stream().filter(b -> b.role() == ButtonRole.CANCEL).findFirst();
        if (cancel.isPresent()) {
            // Esc закрывает окно всегда, поэтому доступность кнопки отмены здесь не проверяется.
            String id = cancel.get().id();
            apply(guard(() -> logic.onButton(id, state, context)));
        } else {
            apply(new FormOutcome.Close(null));
        }
    }

    /**
     * Границы окна изменились.
     *
     * @param bounds новые границы
     */
    public void boundsChanged(WindowBounds bounds) {
        if (finished) {
            return;
        }
        this.bounds = bounds;
        touch();
    }

    /** Клиент показал окно. */
    public void shown() {
        ensureStarted();
        if (finished || registered || !spec.restorable()) {
            return;
        }
        registered = true;
        host.registered(this);
    }

    /** Клиент закрыл окно (после {@code WindowHandle.close()} или системного закрытия). */
    public void closed() {
        if (finished || closing) {
            // Окно закрыло само ядро (handle.close() обработчиков не вызывает) или закрытие уже идёт.
            return;
        }
        closing = true;
        try {
            host.closed(this, null);
        } finally {
            // Окна уже нет на экране: снимаем регистрацию, но handle.close() не зовём.
            closing = false;
            finished = true;
            unregisterOnce();
        }
    }

    /**
     * План изменился: если {@code logic.reevaluateOnDocumentChange()}, пересчитать форму с новым состоянием.
     * Новое окружение запоминается в любом случае: кнопки работают с актуальным планом.
     *
     * @param newContext контекст с новым {@code AppState}
     */
    public void documentChanged(FormContext newContext) {
        Objects.requireNonNull(newContext, "newContext");
        ensureStarted();
        if (finished) {
            return;
        }
        context = newContext;
        if (logic.reevaluateOnDocumentChange()) {
            recompute(null);
            push();
        }
    }

    /**
     * Обрабатывает результат логики (правила - в описании класса). Контроллер может передать сюда и свой результат,
     * например {@code Stay(Problem.error(…))}, если действие после выбора файла не выполнено.
     *
     * @param outcome результат; {@code null} - просто пересчитать и перерисовать форму
     */
    public void apply(FormOutcome outcome) {
        ensureStarted();
        if (finished) {
            return;
        }
        switch (outcome) {
            case null -> refresh();
            case FormOutcome.Stay stay -> {
                if (stay.problem().severity() != Problem.Severity.NONE) {
                    stayProblem = stay.problem();
                }
                refresh();
            }
            case FormOutcome.SetFields set -> {
                state = new FormState(state.page(), merged(set.values()), state.previewIndex());
                stayProblem = Problem.NONE;
                refresh();
                touch();
            }
            case FormOutcome.Page page -> {
                int last = Math.max(0, spec.pages().size() - 1);
                state = new FormState(Math.clamp(page.page(), 0, last), state.values());
                stayProblem = Problem.NONE;
                refresh();
                touch();
            }
            case FormOutcome.OpenChild child -> host.openChild(this, child.child());
            case FormOutcome.Apply action -> {
                try {
                    host.applied(this, action.action());
                } catch (RuntimeException e) {
                    stayProblem = Problem.error(message(e));
                    refresh();
                    return;
                }
                state = new FormState(state.page(), merged(action.fieldUpdates()), state.previewIndex());
                refresh();
                touch();
            }
            case FormOutcome.Close close -> close(close.result());
        }
    }

    @Override
    public WindowState captureState() {
        ensureStarted();
        Map<String, String> ctx = new LinkedHashMap<>();
        for (String key : windowType.contextKeys()) {
            if (WindowType.CONTEXT_PAGE.equals(key)) {
                ctx.put(key, Integer.toString(state.page()));
            } else if (context.context().containsKey(key)) {
                ctx.put(key, context.contextValue(key));
            }
        }
        // Прочие ключи контекста - в алфавитном порядке, чтобы снимок был детерминированным.
        new TreeMap<>(context.context()).forEach(ctx::putIfAbsent);
        if (state.page() != 0) {
            ctx.putIfAbsent(WindowType.CONTEXT_PAGE, Integer.toString(state.page()));
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String id : windowType.fieldIds()) {
            if (state.values().containsKey(id)) {
                fields.put(id, state.value(id));
            }
        }
        new TreeMap<>(state.values()).forEach(fields::putIfAbsent);
        WindowBounds current = handle != null && handle.bounds() != null ? handle.bounds() : bounds;
        return new WindowState(windowId, windowType, modal, ownerId, current, ctx, fields);
    }

    @Override
    public void applyState(WindowState restored) {
        Objects.requireNonNull(restored, "state");
        ensureStarted();
        Map<String, String> values = new LinkedHashMap<>(state.values());
        // Дословно: канонические значения и некорректный текст из снимка не переводятся повторно.
        values.putAll(restored.fields());
        Map<String, String> normalized = logic.normalizeRestoredValues(Map.copyOf(values), context);
        if (normalized != null) {
            normalized.forEach((id, value) -> values.put(id, Objects.requireNonNullElse(value, "")));
        }
        int page = 0;
        String pageText = restored.contextValue(WindowType.CONTEXT_PAGE);
        if (FieldCodec.parseLong(pageText).isPresent()) {
            long parsed = FieldCodec.parseLong(pageText).getAsLong();
            page = Math.clamp(parsed, 0, Math.max(0, spec.pages().size() - 1));
        }
        state = new FormState(page, values);
        if (restored.bounds() != null) {
            bounds = restored.bounds();
        }
        stayProblem = Problem.NONE;
        refresh();
    }

    // ------------------------------------------------------------------ внутреннее

    /** Лениво строит раскладку, значения по умолчанию и первую модель. */
    private void ensureStarted() {
        if (spec != null) {
            return;
        }
        spec = Objects.requireNonNull(logic.spec(context), "spec");
        kinds = new LinkedHashMap<>();
        innerButtons = new LinkedHashSet<>();
        for (FormPage page : spec.pages()) {
            for (FormRow row : page.rows()) {
                switch (row) {
                    case FormRow.Field field -> register(field.field());
                    case FormRow.Inline inline -> inline.fields().forEach(this::register);
                    case FormRow.SideColumn side -> {
                        register(side.preview());
                        side.buttons().forEach(button -> innerButtons.add(button.id()));
                    }
                    case FormRow.Section _, FormRow.Hint _, FormRow.Results _ -> {
                        // Разделы, подсказки и места строк результата не содержат полей.
                    }
                }
            }
        }
        Map<String, String> defaults = new LinkedHashMap<>();
        Map<String, String> given = logic.defaults(context);
        if (given != null) {
            given.forEach((id, value) -> defaults.put(id, FieldCodec.canonical(kinds.getOrDefault(id, FieldKind.TEXT),
                    Objects.requireNonNullElse(value, ""))));
        }
        state = new FormState(0, defaults);
        recompute(null);
    }

    private void register(FieldSpec field) {
        kinds.putIfAbsent(field.id(), field.kind());
        if (field.kind() == FieldKind.BUTTON) {
            innerButtons.add(field.id());
        }
    }

    /** @return значения состояния с заменой данных (приведённых к канонической форме) */
    private Map<String, String> merged(Map<String, String> updates) {
        Map<String, String> values = new LinkedHashMap<>(state.values());
        updates.forEach((id, value) -> values.put(id, FieldCodec.canonical(kinds.getOrDefault(id, FieldKind.TEXT),
                Objects.requireNonNullElse(value, ""))));
        return values;
    }

    private void refresh() {
        recompute(null);
        push();
    }

    /**
     * Пересчитывает модель. Если список предпросмотра изменился, выбор в нём снимается и модель считается ещё раз
     * (от выбора зависит, например, кнопка «Скорректировать выбранную дату…»).
     *
     * @param typingField поле, которое пользователь печатает (его текст не перезаписывается), или {@code null}
     */
    private void recompute(String typingField) {
        FormView previous = view;
        FormView next = build(typingField);
        if (state.hasPreviewSelection() && previous != null && !previous.preview().equals(next.preview())) {
            state = state.withPreviewIndex(FormState.NO_PREVIEW);
            next = build(typingField);
        }
        view = next;
    }

    private FormView build(String typingField) {
        FormView raw;
        try {
            raw = Objects.requireNonNull(logic.evaluate(state, context), "evaluate");
        } catch (RuntimeException e) {
            // Логика не должна бросать (FormLogic), но ошибка программы не должна ронять окно.
            FormView last = view;
            raw = new FormView(0, state.page(), last == null ? "" : last.header(), last == null ? null : last.fields(),
                    Problem.error(message(e)), last == null ? null : last.buttons(), null, null, null, false);
        }
        Map<String, FieldView> fields = new LinkedHashMap<>();
        for (Map.Entry<String, FieldKind> entry : kinds.entrySet()) {
            String id = entry.getKey();
            FieldKind kind = entry.getValue();
            FieldView given = raw.fields().get(id);
            if (kind == FieldKind.BUTTON || kind == FieldKind.RESULT_LINES) {
                if (given != null) {
                    fields.put(id, given);
                }
                continue;
            }
            String value;
            if (id.equals(typingField)) {
                value = null;
            } else if (given != null && given.value() != null) {
                // Логика возвращает канонические значения состояния; перед отдачей клиенту превращаем суммы и даты
                // в человекочитаемый вид. Некорректный текст FieldCodec оставляет дословно.
                value = FieldCodec.display(kind, given.value());
            } else {
                value = FieldCodec.display(kind, state.value(id));
            }
            fields.put(id, given == null
                    ? FieldView.of(value)
                    : new FieldView(value, given.visible(), given.enabled(), given.readOnly(), given.label(),
                    given.options(), given.tooltip(), given.min(), given.max()));
        }
        raw.fields().forEach(fields::putIfAbsent);
        Problem problem = stayProblem.severity() != Problem.Severity.NONE ? stayProblem : raw.problem();
        Map<String, ButtonView> buttons = new LinkedHashMap<>(raw.buttons());
        // Логика описывает только отличия состояния: обычные кнопки спецификации видимы и доступны по умолчанию.
        // Благодаря этому форма не теряет «Сохранить», когда после исправления ошибки Problem становится NONE.
        for (ButtonSpec button : spec.buttons()) {
            buttons.putIfAbsent(button.id(), ButtonView.ENABLED);
        }
        if (problem.severity() == Problem.Severity.ERROR) {
            for (ButtonSpec button : spec.buttons()) {
                if (button.role() == ButtonRole.OK || button.role() == ButtonRole.FINISH) {
                    ButtonView current = buttons.getOrDefault(button.id(), ButtonView.ENABLED);
                    buttons.put(button.id(), new ButtonView(false, current.visible(), current.text(), current.tooltip()));
                }
            }
        }
        return new FormView(++revision, state.page(), raw.header(), fields, problem, buttons, raw.results(),
                raw.preview(), raw.details(), raw.detailsExpanded());
    }

    /** @return можно ли нажать кнопку: известна, доступна и видна (у POPUP скрытая кнопка тоже нажимается) */
    private boolean pressable(String buttonId) {
        boolean known = buttonId.equals(spec.defaultButtonId()) || innerButtons.contains(buttonId)
                || spec.buttons().stream().anyMatch(b -> b.id().equals(buttonId));
        if (!known) {
            return false;
        }
        ButtonView button = view.buttons().get(buttonId);
        if (button != null) {
            return button.enabled() && (button.visible() || spec.presentation() == Presentation.POPUP);
        }
        FieldView field = view.fields().get(buttonId);
        return field == null || (field.enabled() && field.visible());
    }

    private void close(Object result) {
        if (closing || finished) {
            return;
        }
        closing = true;
        try {
            host.closed(this, result);
        } catch (RuntimeException e) {
            closing = false;
            if (result != null) {
                stayProblem = Problem.error(message(e));
                refresh();
                return;
            }
            finish();
            throw e;
        }
        finish();
    }

    private void finish() {
        closing = false;
        finished = true;
        unregisterOnce();
        if (handle != null) {
            handle.close();
        }
    }

    private void unregisterOnce() {
        if (registered && !unregistered) {
            unregistered = true;
            host.unregistered(this);
        }
    }

    private void touch() {
        if (registered && !unregistered && !finished) {
            host.touched(this);
        }
    }

    private void push() {
        if (handle != null && !finished) {
            handle.update(view);
        }
    }

    /** Вызов логики, исключение которой превращается в строку проблем. */
    private FormOutcome guard(java.util.function.Supplier<FormOutcome> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            return new FormOutcome.Stay(Problem.error(message(e)));
        }
    }

    private static String message(RuntimeException e) {
        String text = e.getMessage();
        return text == null || text.isBlank() ? e.getClass().getSimpleName() : text;
    }
}
