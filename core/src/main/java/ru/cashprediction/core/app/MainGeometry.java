package ru.cashprediction.core.app;

import ru.cashprediction.core.session.WindowBounds;

/**
 * Положение главного окна, которое клиент сообщает ядру ({@code UiPort.mainGeometry()},
 * {@code UiIntents.mainGeometry}) и которое попадает в снимок сеанса ({@code SessionBridge.captureMain}).
 *
 * @param bounds    границы окна в нормальном (не развёрнутом) состоянии или {@code null}, если неизвестны
 *                  (web: вкладка браузера, границы не пишутся)
 * @param maximized развёрнуто ли окно
 */
public record MainGeometry(WindowBounds bounds, boolean maximized) {

    /** Геометрия неизвестна: границ нет, окно не развёрнуто. */
    public static final MainGeometry UNKNOWN = new MainGeometry(null, false);
}
