/**
 * Ядро CashPrediction: доменная модель, движок прогноза, Markdown-хранилище, модель снимка сессии и хранилища
 * снимков, а также всё поведение и все модели интерфейса (контроллер, меню, таблица, график, формы, сообщения,
 * тексты, токены дизайна). Не зависит ни от JavaFX, ни от Swing, ни от {@code java.desktop} (правило R5): клиенты
 * только отрисовывают модели ядра.
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
    // Пути CashMemory, атомарная запись файлов, репозиторий планов, обозреватель папок окна «Выбор файла».
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

    // Общий каталог текстов всех слоёв ядра (решение L13): <область>_ru.properties, без зависимости от интерфейса.
    exports ru.cashprediction.core.text;
    // Контроллер, порт «ядро → клиент», намерения «клиент → ядро», параметры запуска, часы, пример плана.
    exports ru.cashprediction.core.app;
    // Потоки контроллера: файл, правка, вид, инструменты, восстановление, запуск, выход, мост сеанса.
    exports ru.cashprediction.core.app.flow;
    // Тексты интерфейса и форматы чисел и дат.
    exports ru.cashprediction.core.ui.text;
    // Токены дизайна: цвета, шрифты, размеры, значки, CSS для web/FX и значения UIManager для Swing.
    exports ru.cashprediction.core.ui.token;
    // Команды, доступность, горячие клавиши.
    exports ru.cashprediction.core.ui.command;
    // Модели строки меню, тулбара и контекстных меню.
    exports ru.cashprediction.core.ui.menu;
    // Модель главного окна.
    exports ru.cashprediction.core.ui.view;
    // Панель сводки.
    exports ru.cashprediction.core.ui.view.summary;
    // Ленивая таблица прогноза.
    exports ru.cashprediction.core.ui.view.table;
    // Сцена графика.
    exports ru.cashprediction.core.ui.view.chart;
    // Строка состояния.
    exports ru.cashprediction.core.ui.view.status;
    // Модели всплывающих окон: карточка дня, спарклайн, календарь.
    exports ru.cashprediction.core.ui.view.popup;
    // Каркас форм: раскладка, модель, логика, сеанс окна, кодек полей.
    exports ru.cashprediction.core.ui.form;
    // Сообщения: описание, сеанс, каталог всех сообщений спецификации.
    exports ru.cashprediction.core.ui.alert;
    // Простые формы: ввод, выбор, открыть план, подтверждения, CSV, выбор файла web.
    exports ru.cashprediction.core.ui.forms.simple;
    // Формы плана: мастер, параметры, горизонт, калькулятор цели.
    exports ru.cashprediction.core.ui.forms.plan;
    // Формы операций: правило, разовая, корректировка, быстрая правка.
    exports ru.cashprediction.core.ui.forms.ops;
    // JSON web-протокола: намерения, запросы, эффекты.
    exports ru.cashprediction.core.ui.json;
    // Дампы интерфейса для проверки одинаковости клиентов.
    exports ru.cashprediction.core.ui.dump;
    // Сценарии самотеста и драйверы.
    exports ru.cashprediction.core.ui.selftest;
}
