package ru.cashprediction.core.ui.selftest;

import java.io.IOException;
import java.time.Duration;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.ui.dump.UiDump;

/**
 * Драйвер клиента для сценариев самотеста (архитектура §6.2): {@code FxUiDriver}, {@code SwingUiDriver},
 * {@code WebSelfTestBridge} (через тестовый API) и {@link ModelUiDriver} (эталоны без интерфейса).
 *
 * <p>Драйвер выполняет команды как пользователь — через настоящий путь событий виджетов (меню открывается и пункт
 * выбирается, клавиша проходит через диспетчер, текст вводится в поле), а не вызовом ядра напрямую. Команды
 * {@code wait}, {@code signal}, {@code dump} и {@code shot} выполняет {@link SelfTestRunner}, запрашивая у драйвера
 * {@link #dump(String)} и {@link #screenshot(String)}.</p>
 */
public interface UiDriver {

    /**
     * Вид управляемого клиента.
     *
     * <p>{@link ModelUiDriver} возвращает вид своего профиля (для эталонов — {@code FX}, чьи различия §10 воспроизводятся);
     * то, что дамп построен из моделей, отмечает строка {@code UiDump.client = "model"}, а не этот метод.</p>
     *
     * @return {@code FX}, {@code SWING} или {@code WEB}
     */
    ClientKind client();

    /**
     * Выполняет команду сценария.
     *
     * @param command команда (кроме Wait, Signal, Dump, Shot)
     * @throws Exception если команду нельзя выполнить (окна нет, пункт отключён, поле не найдено); строка FAIL
     */
    void execute(SelfTestCommand command) throws Exception;

    /**
     * Ждёт, пока интерфейс обработает все события и таймеры задержек (спиннер 600 мс и т. п.).
     *
     * @param timeout наибольшее ожидание
     * @throws Exception если интерфейс не успокоился за время ожидания
     */
    void awaitIdle(Duration timeout) throws Exception;

    /**
     * Дамп того, что сейчас на экране (из настоящих виджетов).
     *
     * @param step имя шага
     * @return дамп схемы 1 (ещё не нормализованный)
     */
    UiDump dump(String step);

    /**
     * Снимок экрана окна 1200×800 при масштабе 1,0 (FX {@code Scene.snapshot}/Robot, Swing Robot, web CDP).
     *
     * @param step имя шага
     * @return байты PNG
     * @throws IOException если снимок не получен
     */
    byte[] screenshot(String step) throws IOException;

    /**
     * Получает согласованный дамп, исходный PNG и наблюдения реальной отрисовки.
     * Обычные отдельные dump/screenshot не заменяют этот барьер: клиент обязан
     * явно реализовать сбор фактических свойств и проверку стабильности кадра.
     *
     * @param request идентичность опыта, намерения ввода и предельное время захвата
     * @return согласованный набор настоящих наблюдений
     * @throws Exception если возможность отсутствует, кадр нестабилен или сбор не удался
     */
    default ru.cashprediction.core.ui.selftest.paint.WidgetCapture capture(
            ru.cashprediction.core.ui.selftest.paint.PaintCaptureRequest request) throws Exception {
        throw new UnsupportedOperationException("widget-paint-v1");
    }
}
