package ru.cashprediction.core.ui.json;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.ui.command.HotkeyBinding;
import ru.cashprediction.core.ui.view.MainScreenModel;

/**
 * Ответ {@code GET /api/ui/bootstrap?tab=<uuid>} (архитектура §5): всё, что нужно вкладке, чтобы нарисовать текущее
 * состояние сервера, в том числе после перезагрузки страницы посреди диалога.
 *
 * @param seq      номер последнего эффекта (дальше — {@code events?after=seq})
 * @param client   всегда {@code web}
 * @param testApi  включён ли тестовый API ({@code --test-api})
 * @param profile  профиль клиента
 * @param screen   полная модель экрана (таблица без строк)
 * @param hotkeys  привязки клавиш web-клиента
 * @param windows  эффекты {@code form.open} и {@code alert.open} открытых окон в порядке открытия
 * @param overlay  {@code null} или вид экрана поверх страницы: {@code STOPPED}, {@code CRASHED}, {@code RECOVERY_PENDING}
 * @param texts    тексты, которые вкладка рисует сама, не получая их в модели: все ключи с префиксом
 *                 {@link #OFFLINE_PREFIX} (экраны без связи с сервером, §6.30) и ключи {@link #CHROME_TEXT_KEYS}
 *                 (оформление, одинаковое во всех окнах). FX и Swing берут те же ключи из {@code UiText}, поэтому
 *                 тексты у трёх клиентов общие.
 */
public record WebBootstrap(long seq, String client, boolean testApi, ClientProfile profile, MainScreenModel screen,
                           List<HotkeyBinding> hotkeys, List<WebEffect> windows, String overlay,
                           Map<String, String> texts) {

    /** Префикс ключей экранов без связи с сервером (§6.30). */
    public static final String OFFLINE_PREFIX = "offline.";

    /**
     * Ключи оформления, которые рисует сам клиент (спецификация v2, §6.0 п. 5, §5.6.5, §8.9): ссылка подробностей
     * сообщений и форм и подсказка кнопки календаря поля даты. Сервер кладёт в {@link #texts()} ровно их и ключи
     * {@link #OFFLINE_PREFIX}; новый текст оформления добавляется сюда, а не пишется в JavaScript.
     */
    public static final List<String> CHROME_TEXT_KEYS = List.of("details.show", "details.hide", "calendar.button.tip");

    /** Проверяет поля и копирует коллекции. */
    public WebBootstrap {
        client = Objects.requireNonNullElse(client, "web");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(screen, "screen");
        hotkeys = List.copyOf(Objects.requireNonNull(hotkeys, "hotkeys"));
        windows = List.copyOf(Objects.requireNonNull(windows, "windows"));
        texts = texts == null ? Map.of() : Map.copyOf(texts);
    }
}
