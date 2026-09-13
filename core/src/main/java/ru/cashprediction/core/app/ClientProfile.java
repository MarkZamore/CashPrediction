package ru.cashprediction.core.app;

import java.util.Objects;
import ru.cashprediction.core.ui.text.UiText;

/**
 * Что ядру нужно знать о клиенте (архитектура §3.6, {@code UiPort.profile()}).
 *
 * @param kind                вид клиента
 * @param chooser             способ выбора файлов
 * @param nativeReplacePrompt спрашивает ли сам диалог выбора о замене существующего файла (JavaFX: да), тогда
 *                            ядро не показывает {@code confirm.replaceFile}
 * @param toolkitVersion      версия инструмента для строки «Клиент: …» окна «О программе» (§6.18), например
 *                            {@code 25} для JavaFX; пустая строка, если версия не показывается
 */
public record ClientProfile(ClientKind kind, ChooserKind chooser, boolean nativeReplacePrompt, String toolkitVersion) {

    /** Проверяет поля. */
    public ClientProfile {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(chooser, "chooser");
        toolkitVersion = Objects.requireNonNullElse(toolkitVersion, "");
    }

    /**
     * Профиль JavaFX: нативный выбор файлов, нативный вопрос о замене.
     *
     * @param javafxVersion основная версия JavaFX, например {@code 25}
     * @return профиль
     */
    public static ClientProfile fx(String javafxVersion) {
        return new ClientProfile(ClientKind.FX, ChooserKind.NATIVE, true, javafxVersion);
    }

    /** @return профиль Swing: {@code JFileChooser}, о замене спрашивает ядро */
    public static ClientProfile swing() {
        return new ClientProfile(ClientKind.SWING, ChooserKind.SWING, false, "");
    }

    /** @return профиль web: окно ядра «Выбор файла» */
    public static ClientProfile web() {
        return new ClientProfile(ClientKind.WEB, ChooserKind.SERVER_BROWSER, false, "");
    }

    /** @return идентификатор клиента в снимке: {@code fx}, {@code swing}, {@code web} */
    public String snapshotClient() {
        return kind.snapshotClient();
    }

    /**
     * Название клиента для «О программе» и диалога восстановления: «JavaFX 25», «Swing», «браузер»
     * (ключи {@code client.fx}, {@code client.swing}, {@code client.web}).
     *
     * @return текст из каталога
     */
    public String clientTitle() {
        return switch (kind) {
            case FX -> UiText.get("client.fx", toolkitVersion);
            case SWING -> UiText.get("client.swing");
            case WEB -> UiText.get("client.web");
        };
    }
}
