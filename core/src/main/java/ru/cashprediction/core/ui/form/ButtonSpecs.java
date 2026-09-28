package ru.cashprediction.core.ui.form;

import ru.cashprediction.core.ui.text.UiText;

/**
 * Кнопки панели форм с текстами каталога {@code button.*} (спецификация v2, §8.8) и общими id.
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class ButtonSpecs {

    /** id подтверждающей кнопки (роль OK). */
    public static final String OK = "ok";
    /** id кнопки «Отмена» (роль CANCEL). */
    public static final String CANCEL = "cancel";
    /** id кнопки «Закрыть» (роль CANCEL: калькулятор цели, корректировка без правила). */
    public static final String CLOSE = "close";

    private ButtonSpecs() {
    }

    /**
     * Подтверждающая кнопка с id {@link #OK}.
     *
     * @param text текст из каталога, например {@code UiText.get("button.save")}
     * @return кнопка
     */
    public static ButtonSpec ok(String text) {
        return new ButtonSpec(OK, text, ButtonRole.OK, "");
    }

    /** @return «Отмена» с id {@link #CANCEL} и ролью CANCEL */
    public static ButtonSpec cancel() {
        return new ButtonSpec(CANCEL, UiText.get("button.cancel"), ButtonRole.CANCEL, "");
    }

    /** @return «Закрыть» с id {@link #CLOSE} и ролью CANCEL */
    public static ButtonSpec close() {
        return new ButtonSpec(CLOSE, UiText.get("button.close"), ButtonRole.CANCEL, "");
    }

    /**
     * Произвольная кнопка.
     *
     * @param id   id
     * @param text текст
     * @param role роль
     * @return кнопка
     */
    public static ButtonSpec of(String id, String text, ButtonRole role) {
        return new ButtonSpec(id, text, role, "");
    }
}
