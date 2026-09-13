package ru.cashprediction.core.app;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.session.StoreStatus;

/**
 * Неизменяемый снимок состояния приложения (архитектура §3.7): единственный вход всех построителей моделей
 * ({@code MenuModels}, {@code CommandAvailability}, {@code SummaryBuilder}, {@code LazyTableModel},
 * {@code ChartLayout}, {@code StatusBuilder}) и форм ({@code FormContext}). Одно и то же состояние даёт равные модели,
 * поэтому эталонные дампы детерминированы.
 *
 * <p>Контроллер хранит изменяемые части (документ, вид, выделение, окна, сообщения) у себя и строит новый снимок
 * после каждого намерения или события документа ({@code AppController.state()}).</p>
 *
 * @param revision       монотонный номер снимка
 * @param profile        профиль клиента
 * @param today          «сегодня» ({@code AppClock.today()}, фиксируется {@code --today})
 * @param cashMemory     папка CashMemory
 * @param plansFolder    папка, из которой сейчас открываются планы: CashMemory или выбранная на время сеанса (§6.22)
 * @param document       срез документа плана
 * @param view           вид: режим, период, флажки, текст фильтра, «что-если»
 * @param selectedRowId  id выделенной строки таблицы или пустая строка
 * @param pastExpanded   раскрыта ли группа «Прошедшие события» (§5.2)
 * @param settings       настройки приложения (недавние планы, автосохранение, хранилище по умолчанию)
 * @param recorder       состояние записи сеанса
 * @param stores         последние результаты записи по хранилищам (для сегмента session)
 * @param windows        открытые окна
 * @param status         сообщения строки состояния
 * @param autosaveProblem текст последней проблемы автосохранения или пустая строка
 */
public record AppState(long revision, ClientProfile profile, LocalDate today, Path cashMemory, Path plansFolder,
                       DocumentView document, ViewState view, String selectedRowId, boolean pastExpanded,
                       AppSettings settings, RecorderStatus recorder, List<StoreStatus> stores, OpenWindows windows,
                       StatusMessages status, String autosaveProblem) {

    /** Проверяет обязательные поля и заменяет {@code null}. */
    public AppState {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(cashMemory, "cashMemory");
        plansFolder = plansFolder == null ? cashMemory : plansFolder;
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(view, "view");
        selectedRowId = Objects.requireNonNullElse(selectedRowId, "");
        Objects.requireNonNull(settings, "settings");
        recorder = recorder == null ? RecorderStatus.NOT_STARTED : recorder;
        stores = stores == null ? List.of() : List.copyOf(stores);
        windows = windows == null ? OpenWindows.NONE : windows;
        status = status == null ? StatusMessages.EMPTY : status;
        autosaveProblem = Objects.requireNonNullElse(autosaveProblem, "");
    }

    /** @return вид клиента (сокращение для {@code profile().kind()}) */
    public ClientKind client() {
        return profile.kind();
    }
}
