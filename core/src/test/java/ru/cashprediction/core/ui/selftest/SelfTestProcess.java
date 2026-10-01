package ru.cashprediction.core.ui.selftest;

/** Вспомогательная JVM для настоящей проверки обнаружения живого второго процесса. */
public final class SelfTestProcess {
    private SelfTestProcess() { }
    /** Ждёт закрытия входного канала; не запускает приложение, не пишет файлы и не открывает реестр. */
    public static void main(String[] arguments) throws Exception { System.in.read(); }
}
