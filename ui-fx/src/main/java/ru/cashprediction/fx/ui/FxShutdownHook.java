package ru.cashprediction.fx.ui;

/** При остановке JVM сохраняет последний снятый снимок, не обращаясь к FX и не отмечая чистый выход. */
final class FxShutdownHook {
    private FxShutdownHook() { }

    /** Регистрирует действие ядра, безопасное и до установки рекордера, и после чистого закрытия. */
    static void install(Runnable saveShutdownSnapshot) {
        Runtime.getRuntime().addShutdownHook(new Thread(saveShutdownSnapshot, "cashprediction-fx-shutdown"));
    }
}
