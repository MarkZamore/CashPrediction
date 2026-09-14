package ru.cashprediction.core.ui.alert;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.session.CrashDetector;
import ru.cashprediction.core.session.SessionSnapshot;

/**
 * Все сообщения спецификации (§6, §6A, §8.5, §8.6): одна фабрика на сообщение, тексты только из каталога
 * ({@code alerts_ru.properties}, {@code buttons_ru.properties}). Минимальная ширина 460, если не сказано иное;
 * кнопка по умолчанию — «ОК» (кириллица) роли OK.
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class AlertCatalog {

    /** Назначение: удаление регулярной операции (§6.11), восстанавливается. */
    public static final String PURPOSE_DELETE_RULE = "deleteRule";
    /** Назначение: удаление разовой операции (§6.11), восстанавливается. */
    public static final String PURPOSE_DELETE_ONE_TIME = "deleteOneTime";
    /** Назначение: актуализация (§6.25), восстанавливается. */
    public static final String PURPOSE_ACTUALIZE = "actualize";
    /** Назначение: применить «что-если» (§6.26), восстанавливается. */
    public static final String PURPOSE_APPLY_WHAT_IF = "applyWhatIf";
    /** Назначение: очистить снимки (§6.27), восстанавливается. */
    public static final String PURPOSE_CLEAR_SNAPSHOTS = "clearSnapshots";
    /** Назначения, у которых есть {@link AlertSession}. */
    public static final List<String> RESTORABLE_PURPOSES = List.of(PURPOSE_DELETE_RULE, PURPOSE_DELETE_ONE_TIME,
            PURPOSE_ACTUALIZE, PURPOSE_APPLY_WHAT_IF, PURPOSE_CLEAR_SNAPSHOTS);

    /** Кнопка «ОК». */
    public static final String BUTTON_OK = "ok";
    /** Кнопка «Отмена» (роль CANCEL). */
    public static final String BUTTON_CANCEL = "cancel";
    /** Кнопка «Сохранить». */
    public static final String BUTTON_SAVE = "save";
    /** Кнопка «Не сохранять». */
    public static final String BUTTON_DONT_SAVE = "dontSave";
    /** Кнопка «Перезаписать». */
    public static final String BUTTON_OVERWRITE = "overwrite";
    /** Кнопка «Перечитать». */
    public static final String BUTTON_RELOAD = "reload";
    /** Кнопка восстановления из реестра. */
    public static final String BUTTON_RESTORE_REGISTRY = "restoreRegistry";
    /** Кнопка восстановления из XML-файла. */
    public static final String BUTTON_RESTORE_XML = "restoreXml";
    /** Кнопка восстановления с сервера (web). */
    public static final String BUTTON_RESTORE_SERVER = "restoreServer";
    /** Кнопка «Не восстанавливать». */
    public static final String BUTTON_NO_RESTORE = "noRestore";

    private AlertCatalog() {
    }

    /**
     * §6.12 «Несохранённые изменения»: [Сохранить] (OK) [Не сохранять] (OTHER) [Отмена] (CANCEL).
     *
     * @param planName имя плана
     * @return сообщение
     */
    public static AlertSpec unsavedChanges(String planName) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.unsavedChanges");
    }

    /**
     * §6.13 первое сохранение поверх существующего файла: «Сохранение» / [Перезаписать] [Отмена].
     *
     * @param fileBaseName имя файла без «.md»
     * @return сообщение
     */
    public static AlertSpec overwriteOnFirstSave(String fileBaseName) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.overwriteOnFirstSave");
    }

    /**
     * §6.13 {@code confirm.replaceFile}: «Файл существует» / [Заменить] [Отмена].
     *
     * @param fileName имя файла с расширением
     * @return сообщение
     */
    public static AlertSpec replaceFile(String fileName) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.replaceFile");
    }

    /**
     * §6.14 «Файл изменён снаружи» (WARNING): [Перезаписать] (OK) [Перечитать] (OTHER) [Отмена].
     *
     * @param fileBaseName имя файла без «.md»
     * @return сообщение
     */
    public static AlertSpec externalChange(String fileBaseName) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.externalChange");
    }

    /**
     * §6.11 удаление регулярной операции (восстанавливается, {@code targetId} = id правила).
     *
     * @param ruleId      id правила
     * @param title       название
     * @param amount      сумма
     * @param currency    валюта
     * @param recurrence  описание повтора ({@code Recurrence.toRussian})
     * @param adjustments число корректировок событий правила
     * @return сообщение
     */
    public static AlertSpec deleteRule(String ruleId, String title, Money amount, String currency, String recurrence,
                                       int adjustments) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.deleteRule");
    }

    /**
     * §6.11 удаление разовой операции (восстанавливается).
     *
     * @param txId     id операции
     * @param title    название
     * @param date     дата
     * @param amount   сумма
     * @param currency валюта
     * @return сообщение
     */
    public static AlertSpec deleteOneTime(String txId, String title, LocalDate date, Money amount, String currency) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.deleteOneTime");
    }

    /**
     * §6.17 диагностика после загрузки файла (WARNING, подробности раскрыты).
     *
     * @param fileBaseName имя файла без «.md»
     * @param diagnostics  замечания выше INFO
     * @return сообщение
     */
    public static AlertSpec loadDiagnostics(String fileBaseName, List<Diagnostic> diagnostics) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.loadDiagnostics");
    }

    /**
     * §6.17 «Проверить план»: INFORMATION «Замечаний нет» или WARNING «Найдено замечаний: {N}».
     *
     * @param lines строки с префиксами «План: », «Файл: », «Прогноз: dd.MM.yyyy: », «Прогноз не рассчитан: »
     * @return сообщение
     */
    public static AlertSpec validation(List<String> lines) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.validation");
    }

    /**
     * §6.17 очистка корректировок: «Неиспользуемых корректировок нет» или «Удалено: {N корректировка/…}».
     *
     * @param removed сколько удалено (0 — нечего удалять)
     * @return сообщение
     */
    public static AlertSpec cleanup(int removed) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.cleanup");
    }

    /**
     * §6.18 «О программе».
     *
     * @param displayVersion {@code AppInfo.displayVersion()}
     * @param profile        профиль клиента (строка «Клиент: …», §10 №6)
     * @param javaVersion    {@code java.version}
     * @param cashMemory     папка CashMemory
     * @return сообщение
     */
    public static AlertSpec about(String displayVersion, ClientProfile profile, String javaVersion, Path cashMemory) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.about");
    }

    /**
     * §6.19 «Горячие клавиши» (подробности раскрыты).
     *
     * @param hotkeysText {@code HotkeyTable.text()}
     * @return сообщение
     */
    public static AlertSpec hotkeys(String hotkeysText) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.hotkeys");
    }

    /**
     * §6.20 «Формат файла .md» (подробности раскрыты).
     *
     * @param userGuide {@code MarkdownFormat.userGuide()}
     * @return сообщение
     */
    public static AlertSpec fileFormat(String userGuide) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.fileFormat");
    }

    /**
     * §6.22 «Папка CashMemory»: [Открыть планы из другой папки…] [Вернуться к CashMemory]? [Закрыть].
     *
     * @param cashMemory          папка CashMemory
     * @param otherFolderOrNull   выбранная другая папка планов или {@code null}
     * @return сообщение
     */
    public static AlertSpec cashMemoryFolder(Path cashMemory, Path otherFolderOrNull) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.cashMemoryFolder");
    }

    /**
     * §6.25 актуализация (восстанавливается).
     *
     * @param today    сегодня
     * @param balance  прогнозный баланс на начало дня
     * @param currency валюта
     * @return сообщение
     */
    public static AlertSpec actualize(LocalDate today, Money balance, String currency) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.actualize");
    }

    /**
     * §6.26 применение «что-если» (восстанавливается).
     *
     * @param parts готовые части «Доходы × 0,90», «Расходы × 1,10», «доп. экономия {X} в месяц»
     * @return сообщение
     */
    public static AlertSpec applyWhatIf(List<String> parts) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.applyWhatIf");
    }

    /**
     * §6.27 «Последний снимок» (INFORMATION, 760, подробности раскрыты; хранилище по умолчанию первым).
     *
     * @param contentLines строки содержимого по хранилищам
     * @param details      блоки «=== … ===» с текстами снимков
     * @return сообщение
     */
    public static AlertSpec lastSnapshot(List<String> contentLines, String details) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.lastSnapshot");
    }

    /** @return §6.27 «Очистить снимки» (восстанавливается) */
    public static AlertSpec clearSnapshots() {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.clearSnapshots");
    }

    /**
     * §6.27 «Симуляция сбоя» (WARNING); web — текст «Остановить сервер аварийно, без сохранения?» (§10 №4).
     *
     * @param profile профиль клиента
     * @return сообщение
     */
    public static AlertSpec simulateHalt(ClientProfile profile) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.simulateHalt");
    }

    /**
     * §6.28 «CashPrediction уже запущен»; web — одна кнопка [Продолжить] (§10 №9).
     *
     * @param profile профиль клиента
     * @return сообщение
     */
    public static AlertSpec alreadyRunning(ClientProfile profile) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.alreadyRunning");
    }

    /**
     * §6.28 п. 5 ошибка запуска (ERROR, стек в свёрнутых подробностях).
     *
     * @param error исключение
     * @return сообщение
     */
    public static AlertSpec startupError(Throwable error) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.startupError");
    }

    /**
     * §6.29 «Восстановление сеанса» ({@code glyph} ⟲, 720, не восстанавливается): текст по маркеру, строки по хранилищам,
     * строка сведений о плане, виде и окнах; кнопки хранилищ (OTHER) и «Не восстанавливать» (CANCEL); web — одна
     * кнопка «С сервера…» (§10 №7). Кнопка по умолчанию — хранилище из настроек, если доступно; иначе первое
     * доступное; иначе «Не восстанавливать».
     *
     * @param detection     результат {@code CrashDetector}
     * @param preview       снимок, по которому описываются план, вид и окна (хранилище по умолчанию или первое доступное), или {@code null}
     * @param defaultStore  id хранилища по умолчанию из настроек ({@code registry}, {@code xml}, {@code server})
     * @param profile       профиль клиента
     * @return сообщение
     */
    public static AlertSpec crashRecovery(CrashDetector.Detection detection, SessionSnapshot preview, String defaultStore,
                                          ClientProfile profile) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.crashRecovery");
    }

    /**
     * §6.29 отчёт восстановления (только при замечаниях; подробности раскрыты).
     *
     * @param windowsRestored восстановлено окон
     * @param warnings        замечания дословно
     * @return сообщение
     */
    public static AlertSpec restoreReport(int windowsRestored, List<String> warnings) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.restoreReport");
    }

    /**
     * §6.29 RECORDER_NOT_STARTED: [Сохранить план в файл…] (OK) [Пропустить] (CANCEL), подробности свёрнуты.
     *
     * @param planMarkdown текст несохранённого плана из снимка
     * @return сообщение
     */
    public static AlertSpec recorderNotStarted(String planMarkdown) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.recorderNotStarted");
    }

    /**
     * §6.33 необработанная ошибка: [Закрыть программу]; web для ошибок JS — [Перезагрузить страницу] (OK)
     * [Продолжить работу] (CANCEL) (§10 №10).
     *
     * @param error   исключение (для JS — синтетическое с сообщением и стеком браузера)
     * @param jsError ошибка JavaScript во вкладке web-клиента
     * @return сообщение
     */
    public static AlertSpec uncaught(Throwable error, boolean jsError) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.uncaught");
    }

    /**
     * §8.5 info-сообщение: заголовок окна «CashPrediction», заголовок {@code info.<key>}, содержимое
     * {@code info.<key>.content}, кнопка «ОК».
     *
     * @param key  ключ без префикса, например {@code reconcileUnavailable}
     * @param args аргументы подстановок (общие для заголовка и содержимого)
     * @return сообщение
     */
    public static AlertSpec info(String key, Object... args) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.info");
    }

    /**
     * §8.6 {@code err.quickEdit} «Быстрая правка недоступна» — единственная ошибка каталога с двумя вариантами
     * содержимого, поэтому у неё своя фабрика, а не {@link #error}: без строки — «Быстрая правка суммы доступна только
     * для событий регулярных операций» ({@code err.quickEdit.content}); со строкой — «Строки «{0}» нет в таблице
     * (проверьте период и фильтры)» ({@code err.quickEdit.notInTable}).
     *
     * @param missingRowOrEmpty текст строки, которой нет в таблице, или пустая строка — событие не регулярной операции
     * @return сообщение
     */
    public static AlertSpec quickEditUnavailable(String missingRowOrEmpty) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.quickEditUnavailable");
    }

    /**
     * §8.6 ошибка: заголовок окна «CashPrediction — ошибка», заголовок {@code err.<key>}, содержимое
     * {@code err.<key>.content} или сообщение исключения, стек — в свёрнутых подробностях. Для {@code quickEdit}
     * используйте {@link #quickEditUnavailable(String)}.
     *
     * @param key   ключ без префикса, например {@code save}
     * @param error исключение или {@code null}
     * @param args  аргументы подстановок
     * @return сообщение
     */
    public static AlertSpec error(String key, Throwable error, Object... args) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertCatalog.error");
    }
}
