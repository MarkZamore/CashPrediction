package ru.cashprediction.swing.dialog;

import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.swing.session.SwingFieldBinder;

/**
 * Типизированный диалог с результатом {@code R}. Swing-аналог JavaFX {@code Dialog<R>}.
 *
 * <p><b>Модель показа — неблокирующая.</b> Внутри — {@link JDialog} с модальностью
 * {@link Dialog.ModalityType#DOCUMENT_MODAL} (или немодальный). Диалог показывает {@link SwingDialogHost}
 * через {@code SwingUtilities.invokeLater}, а результат приходит в колбэк {@link #setOnResult}, тоже через
 * {@code invokeLater} — уже после закрытия окна. Вызывающий код никогда не ждёт возврата из
 * {@code setVisible(true)}: модальный {@code JDialog} крутит вложенный цикл событий, и только при такой модели
 * {@code RestoreCoordinator} может открыть цепочку вложенных модальных диалогов, а колбэк одного диалога —
 * спокойно открыть следующий.</p>
 *
 * <p><b>Результат.</b> Нажатие кнопки проходит так: обработчик {@link #setButtonHandler} (если вернул
 * {@code true}, кнопка обработана и окно остаётся открытым — например, «Далее» в мастере) → проверка формы для
 * подтверждающих кнопок → {@link #setResultConverter} (аналог {@code Dialog.setResultConverter}; брошенное им
 * {@link IllegalArgumentException} показывается в строке проверки, окно не закрывается) → закрытие и колбэк с
 * {@code Optional} результата (пусто — отмена).</p>
 *
 * <p><b>Проверка формы.</b> {@link #setValidator} возвращает текст первой ошибки или {@code null}; пока ошибка
 * есть, подтверждающие кнопки ({@code OK_DONE}, {@code FINISH}) отключены, а текст виден под формой.</p>
 *
 * <p><b>Сессия.</b> Диалог реализует {@code StatefulWindow} через {@link SwingFieldBinder}: поля формы
 * привязываются к идентификаторам из {@link WindowType#fieldIds()}, каждое изменение вызывает
 * {@code SessionRecorder.touch()}. Диалог без типа ({@code type == null}) в снимок не попадает.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 *
 * @param <R> тип результата
 */
// JavaFX: Dialog<R> → Swing: SwingDialog<R> (JDialog DOCUMENT_MODAL + resultConverter + колбэк) → Web: openDialog(id): Promise<R>
public class SwingDialog<R> implements SwingHostedWindow {

    private final JDialog dialog;
    private final SwingDialogPane pane = new SwingDialogPane();
    private final SwingFieldBinder binder;
    private final WindowType type;
    private final String ownerId;
    private final SessionRecorder recorder;
    private final Map<String, String> context = new LinkedHashMap<>();
    private final List<Runnable> closeActions = new ArrayList<>();

    private String windowId;
    private Function<SwingButtonType, R> resultConverter = button -> null;
    private Predicate<SwingButtonType> buttonHandler = button -> false;
    private Supplier<String> validator = () -> null;
    private Supplier<String> warningSupplier = () -> null;
    private Consumer<Optional<R>> onResult = result -> { };
    private JComponent initialFocus;
    private String currentError;
    private boolean boundsRestored;
    private boolean closed;

    /**
     * Создаёт диалог.
     *
     * @param owner    окно-владелец; {@code null} — окно без владельца (показывается в панели задач)
     * @param ownerId  идентификатор владельца для снимка: {@code main} или {@code wN}
     * @param type     тип окна для снимка; {@code null} — окно не восстанавливается
     * @param modal    {@code true} — {@code DOCUMENT_MODAL}, {@code false} — немодальное окно
     * @param title    заголовок окна
     * @param recorder рекордер сессии; {@code null}, если записи нет (диалог восстановления до старта)
     */
    protected SwingDialog(Window owner, String ownerId, WindowType type, boolean modal, String title, SessionRecorder recorder) {
        // DOCUMENT_MODAL, а не APPLICATION_MODAL: блокируется только «документ» главного окна, как у JavaFX
        // Modality.WINDOW_MODAL; окна без владельца (ошибка до старта) при этом не мешают друг другу.
        this.dialog = new JDialog(owner, title, modal ? Dialog.ModalityType.DOCUMENT_MODAL : Dialog.ModalityType.MODELESS);
        this.ownerId = ownerId == null || ownerId.isBlank() ? WindowState.MAIN_OWNER : ownerId;
        this.type = type;
        this.recorder = recorder;
        this.binder = new SwingFieldBinder(this::fieldChanged);

        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setContentPane(pane);
        if (owner == null) {
            dialog.setIconImages(SwingIcons.appIcons());
        }
        dialog.addWindowListener(new WindowAdapter() {
            /** Окно показано на экране: фокус переводится на первое поле формы. */
            @Override
            public void windowOpened(WindowEvent e) {
                if (initialFocus != null) {
                    initialFocus.requestFocusInWindow();
                }
            }

            /** Пользователь закрывает окно крестиком: выполняется то же, что кнопка отмены или команда выхода. */
            @Override
            public void windowClosing(WindowEvent e) {
                // Крестик окна — то же, что кнопка отмены (и её обработчик).
                cancel();
            }

            /** Окно закрыто и освобождено: выполняются действия закрытия (снятие регистрации, результат вызывающему). */
            @Override
            public void windowClosed(WindowEvent e) {
                closeActions.forEach(Runnable::run);
            }
        });
        pane.setOnButton(this::handleButton);
        pane.setOnLayoutChanged(() -> {
            if (dialog.isShowing()) {
                dialog.pack();
            }
        });

        JRootPane root = dialog.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelDialog");
        root.getActionMap().put("cancelDialog", new AbstractAction() {
            /** Срабатывание действия (кнопка, Enter, клавиша или таймер): выполняет команду этого слушателя. */
            @Override
            public void actionPerformed(ActionEvent e) {
                cancel();
            }
        });
    }

    // ------------------------------------------------------------------ настройка

    /**
     * Окно Swing этого диалога.
     *
     * @return диалог
     */
    @Override
    public JDialog window() {
        return dialog;
    }

    /**
     * Панель диалога (заголовок, содержимое, кнопки).
     *
     * @return панель
     */
    public SwingDialogPane pane() {
        return pane;
    }

    /**
     * Связыватель полей формы со снимком сессии.
     *
     * @return связыватель
     */
    protected SwingFieldBinder binder() {
        return binder;
    }

    /**
     * Рекордер сессии.
     *
     * @return рекордер или {@code null}
     */
    protected SessionRecorder recorder() {
        return recorder;
    }

    /**
     * Задаёт кнопки диалога; подтверждающая кнопка становится кнопкой по умолчанию (Enter).
     *
     * @param types типы кнопок
     */
    public void setButtonTypes(SwingButtonType... types) {
        pane.setButtonTypes(types);
        JButton defaultButton = pane.defaultButtonType().map(pane::lookupButton).orElse(null);
        dialog.getRootPane().setDefaultButton(defaultButton);
        revalidateForm();
    }

    /**
     * Преобразователь «нажатая кнопка → результат» (аналог {@code Dialog.setResultConverter}).
     * {@code null} из преобразователя означает отмену.
     *
     * @param converter преобразователь; может бросить {@link IllegalArgumentException} с текстом ошибки
     */
    public void setResultConverter(Function<SwingButtonType, R> converter) {
        resultConverter = converter == null ? button -> null : converter;
    }

    /**
     * Обработчик кнопок, которые не закрывают диалог («Далее», «Сбросить», «Из файла…»).
     *
     * @param handler возвращает {@code true}, если кнопка обработана и окно остаётся открытым
     */
    public void setButtonHandler(Predicate<SwingButtonType> handler) {
        buttonHandler = handler == null ? button -> false : handler;
    }

    /**
     * Проверка формы.
     *
     * @param formValidator возвращает текст первой ошибки или {@code null}, если форма валидна
     */
    public void setValidator(Supplier<String> formValidator) {
        validator = formValidator == null ? () -> null : formValidator;
    }

    /**
     * Предупреждение, которое не блокирует подтверждение («2 корректировки перестанут совпадать»).
     *
     * @param supplier возвращает текст предупреждения или {@code null}
     */
    public void setWarningSupplier(Supplier<String> supplier) {
        warningSupplier = supplier == null ? () -> null : supplier;
    }

    /**
     * Получатель результата. Вызывается ровно один раз, после закрытия окна, через {@code invokeLater}.
     *
     * @param consumer получатель: результат или пусто при отмене
     */
    public void setOnResult(Consumer<Optional<R>> consumer) {
        onResult = consumer == null ? result -> { } : consumer;
    }

    /**
     * Добавляет действие при закрытии окна (например, отписка от документа в немодальном окне).
     *
     * @param action действие
     */
    public void addCloseAction(Runnable action) {
        closeActions.add(Objects.requireNonNull(action, "action"));
    }

    /**
     * Компонент, получающий фокус при открытии.
     *
     * @param component компонент
     */
    public void setInitialFocus(JComponent component) {
        initialFocus = component;
    }

    /**
     * Записывает значение контекста окна ({@code mode}, {@code ruleId}, {@code page}...).
     *
     * @param key   ключ из {@link WindowType#contextKeys()}
     * @param value значение
     */
    public void putContext(String key, String value) {
        context.put(key, value == null ? "" : value);
    }

    /**
     * Значение контекста окна.
     *
     * @param key ключ
     * @return значение или пустая строка
     */
    public String contextValue(String key) {
        return context.getOrDefault(key, "");
    }

    // ------------------------------------------------------------------ проверка и изменения

    /**
     * Перепроверяет форму: включает или отключает кнопки и показывает первую ошибку или предупреждение.
     */
    public void revalidateForm() {
        currentError = validator.get();
        for (SwingButtonType buttonType : pane.getButtonTypes()) {
            JButton button = pane.lookupButton(buttonType);
            if (button != null) {
                button.setEnabled(isButtonEnabled(buttonType, currentError));
            }
        }
        String warning = currentError == null ? warningSupplier.get() : null;
        pane.setValidationMessage(currentError != null ? currentError : warning, currentError != null);
    }

    /**
     * Доступна ли кнопка при текущей ошибке формы. По умолчанию подтверждающие кнопки требуют валидной формы.
     *
     * @param buttonType тип кнопки
     * @param error      текст ошибки или {@code null}
     * @return {@code true}, если кнопку можно нажать
     */
    protected boolean isButtonEnabled(SwingButtonType buttonType, String error) {
        return !buttonType.isDefaultButton() || error == null;
    }

    /**
     * Обновляет динамическую часть формы (видимость полей, предпросмотр). Вызывается после каждого изменения
     * поля и после восстановления состояния; значения полей здесь менять нельзя.
     */
    protected void onFieldsChanged() {
        // По умолчанию динамической части нет.
    }

    /**
     * Действие после восстановления полей из снимка (например, переход на сохранённую страницу мастера).
     *
     * @param state восстановленное состояние
     */
    protected void afterStateApplied(WindowState state) {
        // По умолчанию ничего.
    }

    /**
     * Обновляет форму после программного изменения полей (не через ввод пользователя).
     */
    protected void refresh() {
        onFieldsChanged();
        revalidateForm();
        touch();
    }

    /** Сообщает рекордеру об изменении, если окно уже зарегистрировано. */
    protected void touch() {
        if (recorder != null && windowId != null) {
            recorder.touch();
        }
    }

    private void fieldChanged() {
        onFieldsChanged();
        revalidateForm();
        touch();
    }

    // ------------------------------------------------------------------ кнопки и закрытие

    /**
     * Обрабатывает нажатие кнопки (также вызывается для Esc и крестика окна через {@link #cancel()}).
     *
     * @param buttonType тип нажатой кнопки
     */
    public void handleButton(SwingButtonType buttonType) {
        if (closed) {
            return;
        }
        if (buttonHandler.test(buttonType)) {
            return;
        }
        revalidateForm();
        if (!isButtonEnabled(buttonType, currentError)) {
            return;
        }
        R result;
        try {
            result = resultConverter.apply(buttonType);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Ошибка сборки результата (например, неверное сочетание полей) — показываем её и не закрываем окно.
            pane.setValidationMessage(e.getMessage(), true);
            return;
        }
        close(result);
    }

    /**
     * Отмена: кнопка отмены, Esc или крестик окна.
     */
    public void cancel() {
        Optional<SwingButtonType> cancelType = pane.cancelButtonType();
        if (cancelType.isPresent()) {
            handleButton(cancelType.get());
        } else {
            close(null);
        }
    }

    /**
     * Закрывает окно и передаёт результат получателю.
     *
     * @param result результат; {@code null} — отмена
     */
    public void close(R result) {
        if (closed) {
            return;
        }
        closed = true;
        dialog.dispose();
        // Колбэк — после выхода из вложенного цикла модального окна: он может открыть следующий диалог.
        SwingUtilities.invokeLater(() -> onResult.accept(Optional.ofNullable(result)));
    }

    /**
     * Закрыт ли диалог.
     *
     * @return {@code true} после {@link #close}
     */
    public boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------ самотест

    /**
     * Заполняет поля формы как пользовательский ввод (слушатели срабатывают). Средство самотеста.
     *
     * @param fields идентификатор поля → значение в канонической форме
     * @return неизвестные идентификаторы полей
     */
    public List<String> fillFields(Map<String, String> fields) {
        return binder.fill(fields);
    }

    /**
     * Нажимает кнопку по умолчанию (ту, что срабатывает на Enter): «ОК», «Далее» или «Готово». Средство самотеста.
     *
     * @return {@code null}, если кнопка нажата успешно; иначе причина неудачи по-русски
     */
    public String pressDefaultButton() {
        if (closed) {
            return "окно уже закрыто";
        }
        revalidateForm();
        JButton defaultButton = dialog.getRootPane().getDefaultButton();
        SwingButtonType type = null;
        for (SwingButtonType candidate : pane.getButtonTypes()) {
            if (pane.lookupButton(candidate) == defaultButton) {
                type = candidate;
            }
        }
        if (type == null) {
            type = pane.defaultButtonType().orElse(null);
        }
        if (type == null) {
            return "у окна нет кнопки по умолчанию";
        }
        if (!isButtonEnabled(type, currentError)) {
            return "кнопка «" + type.text() + "» недоступна" + (currentError != null ? ": " + currentError : "");
        }
        handleButton(type);
        if (type.isDefaultButton() && !closed) {
            // Преобразователь результата отказал: причина уже показана в строке проверки.
            String message = pane.getValidationText();
            return "окно не закрылось" + (message.isBlank() ? "" : ": " + message);
        }
        return null;
    }

    // ------------------------------------------------------------------ StatefulWindow

    /** {@inheritDoc} */
    @Override
    public String windowId() {
        return windowId;
    }

    /** {@inheritDoc} */
    @Override
    public void assignWindowId(String id) {
        if (windowId == null) {
            windowId = id;
        }
    }

    /** {@inheritDoc} */
    @Override
    public WindowType windowType() {
        return type;
    }

    /** {@inheritDoc} */
    @Override
    public boolean modal() {
        return dialog.getModalityType() != Dialog.ModalityType.MODELESS;
    }

    /** {@inheritDoc} */
    @Override
    public String ownerId() {
        return ownerId;
    }

    /** {@inheritDoc} */
    @Override
    public WindowState captureState() {
        if (windowId == null || type == null) {
            return null;
        }
        Rectangle r = dialog.getBounds();
        WindowBounds bounds = dialog.isShowing() && r.width > 0 && r.height > 0
                ? new WindowBounds(r.x, r.y, r.width, r.height) : null;
        return new WindowState(windowId, type, modal(), ownerId, bounds, Collections.unmodifiableMap(context), binder.capture());
    }

    /** {@inheritDoc} */
    @Override
    public void applyState(WindowState state) {
        windowId = state.id();
        context.putAll(state.context());
        WindowBounds b = state.bounds();
        if (b != null && b.width() > 0 && b.height() > 0) {
            dialog.setBounds((int) Math.round(b.x()), (int) Math.round(b.y()),
                    (int) Math.round(b.width()), (int) Math.round(b.height()));
            boundsRestored = true;
        }
        binder.apply(state.fields());
        afterStateApplied(state);
        onFieldsChanged();
        revalidateForm();
    }

    /** {@inheritDoc} */
    @Override
    public void prepareForShow() {
        onFieldsChanged();
        revalidateForm();
        if (boundsRestored && SwingDialogHost.isOnScreen(dialog.getBounds())) {
            return;
        }
        dialog.pack();
        Dimension size = dialog.getSize();
        dialog.setMinimumSize(new Dimension(Math.min(size.width, 360), Math.min(size.height, 160)));
        dialog.setLocationRelativeTo(dialog.getOwner());
    }
}
