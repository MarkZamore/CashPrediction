/**
 * Ядро CashPrediction: доменная модель, движок прогноза, Markdown-хранилище,
 * модель снимка сессии и хранилища снимков. Не зависит ни от JavaFX, ни от Swing.
 *
 * Экспортируются только пакеты, в которых есть классы: javac не разрешает экспортировать
 * пакет, содержащий лишь package-info.java. Все экспортируемые пакеты образуют общий
 * контракт трёх клиентов (JavaFX, Swing, Web), поэтому ни один из них не скрыт квалифицированным
 * экспортом.
 */
module ru.cashprediction.core {
    // Реестр Windows через java.util.prefs.Preferences (пользовательский корень HKCU).
    requires java.prefs;
    // XML-снимок сессии: чтение защищённым DOM-парсером (запись выполняется собственным форматтером).
    requires java.xml;

    // Доменная модель: деньги, правила, разовые операции, корректировки, план.
    exports ru.cashprediction.core.model;
    // Русские тексты и форматы дат.
    exports ru.cashprediction.core.util;
    // Пути CashMemory, атомарная запись файлов, репозиторий планов.
    exports ru.cashprediction.core.io;
    // Генерация дат повторяющихся операций.
    exports ru.cashprediction.core.recurrence;
    // Движок прогноза, сводка, калькулятор цели, данные графика.
    exports ru.cashprediction.core.forecast;
    // Диагностика и проверка плана.
    exports ru.cashprediction.core.diagnostics;
    // Чтение и запись плана и настроек в Markdown.
    exports ru.cashprediction.core.markdown;
    // Документ плана с undo/redo, состояние вида, настройки приложения.
    exports ru.cashprediction.core.document;
    // Мини-JSON (без внешних библиотек) и JSON-представление плана для web-клиента.
    exports ru.cashprediction.core.json;
    // Снимок сессии, запись, обнаружение сбоя и восстановление окон.
    exports ru.cashprediction.core.session;
    // Кодеки снимка: JSON, XML, Markdown.
    exports ru.cashprediction.core.session.codec;
    // Хранилища снимка: реестр, XML-файл, серверный Markdown.
    exports ru.cashprediction.core.session.store;
    // Экспорт прогноза в CSV.
    exports ru.cashprediction.core.export;
}
