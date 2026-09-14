package ru.cashprediction.swing.action;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import ru.cashprediction.swing.dialog.SwingAlert;
import ru.cashprediction.swing.dialog.SwingButtonType;
import ru.cashprediction.swing.dialog.SwingDialogHost;
import ru.cashprediction.swing.dialog.SwingText;

/**
 * Короткие сообщения пользователю: информация, предупреждение, ошибка, подтверждение (диалоги 15, 16, 18, 19, 20).
 *
 * <p>Все сообщения — {@link SwingAlert} (Swing-аналог JavaFX {@code Alert}), показываются неблокирующе через
 * хост диалогов; владелец — верхнее модальное окно, чтобы сообщение не оказалось под открытым диалогом.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
public final class Alerts {

    private final SwingDialogHost host;

    /**
     * Создаёт помощника сообщений.
     *
     * @param host хост диалогов
     */
    public Alerts(SwingDialogHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Создаёт сообщение с владельцем — верхним модальным окном (не показывает).
     *
     * @param type    вид сообщения
     * @param title   заголовок окна
     * @param header  полужирный заголовок
     * @param content основной текст
     * @param buttons кнопки (пусто — по умолчанию для вида)
     * @return сообщение
     */
    public SwingAlert create(SwingAlert.AlertType type, String title, String header, String content, SwingButtonType... buttons) {
        // JavaFX: Alert → Swing: SwingAlert (JOptionPane.createDialog) → Web: <dialog class="alert">
        return new SwingAlert(host.activeOwner(), type, title, header, content, buttons);
    }

    /**
     * Показывает сообщение и передаёт выбранную кнопку получателю.
     *
     * @param alert    сообщение
     * @param onResult получатель выбора (пусто — окно закрыто без кнопки)
     */
    public void show(SwingAlert alert, Consumer<Optional<SwingButtonType>> onResult) {
        alert.setOnResult(onResult);
        host.show(alert);
    }

    /**
     * Информационное сообщение ({@code Alert(INFORMATION)}).
     *
     * @param header  заголовок
     * @param content текст
     */
    public void info(String header, String content) {
        show(create(SwingAlert.AlertType.INFORMATION, "CashPrediction", header, content), r -> { });
    }

    /**
     * Информационное сообщение с раскрытой областью «Подробнее» (горячие клавиши, формат файла, снимок сессии).
     *
     * @param title   заголовок окна
     * @param header  заголовок текста
     * @param content основной текст
     * @param details моноширинный текст подробностей
     */
    public void infoWithDetails(String title, String header, String content, String details) {
        SwingAlert alert = create(SwingAlert.AlertType.INFORMATION, title, header, content);
        alert.setDetailsText(details);
        alert.setExpanded(true);
        show(alert, r -> { });
    }

    /**
     * Предупреждение ({@code Alert(WARNING)}) с необязательной областью «Подробнее».
     *
     * @param header  заголовок
     * @param content текст
     * @param details подробности или {@code null}
     */
    public void warning(String header, String content, String details) {
        SwingAlert alert = create(SwingAlert.AlertType.WARNING, "CashPrediction - предупреждение", header, content);
        if (details != null && !details.isBlank()) {
            alert.setDetailsText(details);
            alert.setExpanded(true);
        }
        show(alert, r -> { });
    }

    /**
     * Сообщение об ошибке ({@code Alert(ERROR)}, диалог 16).
     *
     * @param header  что не удалось
     * @param content причина
     */
    public void error(String header, String content) {
        show(create(SwingAlert.AlertType.ERROR, "CashPrediction - ошибка", header, content), r -> { });
    }

    /**
     * Сообщение об ошибке со стеком исключения в «Подробнее».
     *
     * @param header что не удалось
     * @param error  исключение
     */
    public void error(String header, Throwable error) {
        SwingAlert alert = create(SwingAlert.AlertType.ERROR, "CashPrediction - ошибка", header,
                Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName()));
        alert.setDetailsText(SwingText.stackTrace(error));
        show(alert, r -> { });
    }

    /**
     * Подтверждение ({@code Alert(CONFIRMATION)}) с кнопками «подтвердить» и «Отмена».
     *
     * @param title     заголовок окна
     * @param header    вопрос
     * @param content   пояснение
     * @param okButton  подтверждающая кнопка
     * @param onConfirm действие при подтверждении
     */
    public void confirm(String title, String header, String content, SwingButtonType okButton, Runnable onConfirm) {
        SwingAlert alert = create(SwingAlert.AlertType.CONFIRMATION, title, header, content, okButton, AppButtons.CANCEL);
        show(alert, result -> {
            if (result.filter(okButton::equals).isPresent()) {
                onConfirm.run();
            }
        });
    }
}
