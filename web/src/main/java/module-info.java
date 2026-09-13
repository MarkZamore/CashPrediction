/**
 * Web-клиент CashPrediction: встроенный HTTP-сервер JDK (jdk.httpserver) отдаёт статику и JSON API,
 * java.desktop нужен для открытия браузера ({@code Desktop.browse}) и маленького окна статуса сервера,
 * java.logging — чтобы приглушить журнал {@code java.util.prefs} (ядро загружает модуль реестра).
 */
module ru.cashprediction.web {
    // Ядро: модель, прогноз, файлы CashMemory, снимок сессии.
    requires ru.cashprediction.core;
    // com.sun.net.httpserver.HttpServer — сервер без внешних библиотек.
    requires jdk.httpserver;
    // Desktop.browse и Swing-окно статуса сервера.
    requires java.desktop;
    // Logger.getLogger("java.util.prefs").setLevel(SEVERE) в WebMain.
    requires java.logging;
}
