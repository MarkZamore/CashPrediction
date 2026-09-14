package ru.cashprediction.core.app.flow;

import java.util.Objects;

/**
 * Запуск (спецификация v2, §6.28, §6.29).
 *
 * <p><b>Порядок:</b> 1) папка CashMemory (рядом с программой или {@code --home}), settings.md, пустой «Мой план»;
 * 2) хранилища {@code SessionStores.forClient}; 3) {@code CrashDetector.detect}:</p>
 * <ul>
 *   <li>CLEAN_START — открыть settings.lastPlan, если файл есть, иначе первый план CashMemory; показать окно; начать
 *       запись; если план не открыт — первый запуск: мастер поверх окна ({@code FileFlow.firstRunWizard});</li>
 *   <li>CRASHED — диалог восстановления до главного окна ({@code AlertCatalog.crashRecovery}); выбор хранилища →
 *       {@code RestoreCoordinator} (план, вид, окно, окна по порядку, выделение, «что-если», прошедшие), затем отчёт;
 *       «Не восстанавливать» → снимки очищаются, обычный запуск; ошибка чтения → {@code err.readSnapshot}; пустой
 *       снимок → {@code info.snapshotEmpty}; RECORDER_NOT_STARTED — цикл «Сохранить план в файл…» (отмена выбора и
 *       ошибка записи возвращают к окну);</li>
 *   <li>ALREADY_RUNNING — сообщение §6.28; «Открыть без восстановления» — без записи сеанса
 *       ({@code RecorderStatus.DISABLED_SECOND_INSTANCE}); «Выйти» — выход.</li>
 * </ul>
 * <p>4) Загрузка с замечаниями — §6.17; 5) ошибка запуска — {@code AlertCatalog.startupError} и выход. Автоответы
 * самотеста — {@code LaunchOptions.selftestRecovery}.</p>
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class StartupFlow {

    private final FlowContext context;

    /**
     * Создаёт поток.
     *
     * @param context контекст контроллера
     */
    public StartupFlow(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    /** Выполняет запуск (порядок — в описании класса). */
    public void start() {
        throw new UnsupportedOperationException("S2: core-app-session - StartupFlow.start");
    }
}
