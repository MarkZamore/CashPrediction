package ru.cashprediction.core.session;

/**
 * Изменяемый источник состояния главного окна и плана для тестов рекордера.
 */
final class FakeSource implements SnapshotSource {

    volatile MainWindowState main = MainWindowState.empty();
    volatile PlanState plan = PlanState.CLEAN;
    volatile boolean fail;
    /** Если задана, {@link #captureMain()} бросает эту ошибку (проверка перехвата {@link Error}). */
    volatile Error error;

    @Override
    public MainWindowState captureMain() {
        if (fail) {
            throw new IllegalStateException("главное окно сломано");
        }
        Error e = error;
        if (e != null) {
            throw e;
        }
        return main;
    }

    @Override
    public PlanState capturePlan() {
        return plan;
    }

    /**
     * Меняет выделенную строку (самое простое изменение состояния).
     *
     * @param rowId новая строка
     */
    void select(String rowId) {
        main = main.withSelectedRowId(rowId);
    }
}
