package ru.cashprediction.fx.ui;

import java.util.concurrent.atomic.AtomicInteger;

/** Создаёт настоящую сессию FX с отказом lifecycle без приложения, сети, реестра или файлов обновления. */
public final class FxEarlyHandoffDeniedSession {
    /** Запрещает создание экземпляра вспомогательного класса. */
    private FxEarlyHandoffDeniedSession() { }

    /** Подменяет только решение lifecycle; beforeUi и close выполняются настоящей FxUpdateSession. */
    public static FxUpdateSession create(AtomicInteger closed) {
        return new FxUpdateSession(() -> new FxUpdateSession.Calls(() -> false,
                () -> { throw new AssertionError("UI ready must not follow denied beforeUi"); }, closed::incrementAndGet));
    }
}
