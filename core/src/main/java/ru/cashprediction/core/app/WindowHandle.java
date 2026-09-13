package ru.cashprediction.core.app;

import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.form.FormView;

/**
 * Открытое клиентом окно формы или сообщения, которым управляет ядро (архитектура §3.6).
 *
 * <p>Все методы вызываются только в потоке контроллера ({@code UiExecutor}) и не блокируют. Web реализует ручку
 * эффектами {@code form.view}, {@code alert.update}, {@code form.close}/{@code alert.close}, {@code form.front}.</p>
 */
public interface WindowHandle {

    /**
     * Перерисовать форму по новой модели. Клиент применяет {@code FieldView.value} поля, только если виджет всё ещё
     * показывает текст, который клиент отправил с этой ревизией (правило эха, архитектура §3.5): поле в фокусе
     * не перезаписывается собственным эхом.
     *
     * @param view новая модель формы (ревизии монотонны)
     */
    void update(FormView view);

    /**
     * Обновить открытое сообщение (например, текст кнопок диалога восстановления).
     *
     * @param spec новое описание сообщения
     */
    void updateAlert(AlertSpec spec);

    /** Закрыть окно без повторного вызова обработчиков закрытия ядра (ядро уже знает о закрытии). */
    void close();

    /** Поднять окно наверх и передать ему фокус (повторный вызов калькулятора цели, §6.6). */
    void toFront();

    /** @return текущие границы окна (для снимка); {@code null}, если окно ещё не показано или границ нет (web) */
    WindowBounds bounds();

    /** @return показано ли окно на экране */
    boolean showing();
}
