package ru.cashprediction.fx.dialog;

import javafx.scene.control.Dialog;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.fx.session.FxFieldBinder;

import java.util.ArrayList;
import java.util.List;

/**
 * Базовый класс редакторов CashPrediction: типизированный {@link Dialog}, форма на {@link AppDialogPane},
 * поля привязаны к словарю окон сессии, любое изменение перепроверяет форму и отмечается в рекордере.
 *
 * <p>Подкласс в конструкторе строит форму, привязывает поля через {@link #binder()}, задаёт
 * {@code setResultConverter} и последним вызовом делает {@link #activate()}. Проверку формы подкласс
 * описывает в {@link #validateForm(List, List)}.</p>
 *
 * <p>Показывать такие диалоги нужно только через {@link FxDialogHost}: он назначает идентификатор, владельца
 * и модальность, регистрирует окно в рекордере и не блокирует поток ({@code show()}, а не {@code showAndWait()}),
 * без чего вложенные модальные окна нельзя было бы восстановить.</p>
 *
 * <p>Только FX Application Thread.</p>
 *
 * @param <R> тип результата диалога
 */
// JavaFX: Dialog<R> → Swing: SwingDialog<R> (JDialog DOCUMENT_MODAL + resultConverter + колбэк) → Web: openDialog(id): Promise<R>
public abstract class FxStatefulDialog<R> extends Dialog<R> implements FxRestorableDialog {

    private final AppDialogPane pane;
    private final DialogStateSupport support;

    /**
     * Создаёт диалог с панелью.
     *
     * @param type тип окна из словаря (задаёт и заголовок окна)
     * @param pane панель диалога
     */
    protected FxStatefulDialog(WindowType type, AppDialogPane pane) {
        this.pane = pane;
        setDialogPane(pane);
        setTitle(type.title());
        setResizable(true);
        this.support = new DialogStateSupport(this, type, this::runValidation);
    }

    /** {@inheritDoc} */
    @Override
    public final DialogStateSupport stateSupport() {
        return support;
    }

    /**
     * Связыватель полей формы.
     *
     * @return связыватель
     */
    protected final FxFieldBinder binder() {
        return support.binder();
    }

    /**
     * Панель диалога.
     *
     * @return панель
     */
    protected final AppDialogPane appPane() {
        return pane;
    }

    /**
     * Завершает построение: включает реакцию на изменения полей и выполняет первую проверку формы.
     * Вызывается последней строкой конструктора подкласса.
     */
    protected final void activate() {
        support.activate();
    }

    /**
     * Проверяет форму. Вызывается после каждого изменения поля.
     *
     * @param errors   сюда добавляются ошибки (блокируют «Сохранить»)
     * @param warnings сюда добавляются предупреждения (только показываются)
     */
    protected abstract void validateForm(List<String> errors, List<String> warnings);

    /**
     * Перепроверяет форму вне изменения поля (например, после изменения плана, на который смотрит окно).
     */
    protected final void revalidate() {
        runValidation();
    }

    private void runValidation() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            validateForm(errors, warnings);
        } catch (RuntimeException e) {
            // Проверка не должна ронять приложение: считаем форму невалидной и показываем причину.
            errors.add("Не удалось проверить форму: " + e.getMessage());
        }
        pane.showProblems(errors, warnings);
    }
}
