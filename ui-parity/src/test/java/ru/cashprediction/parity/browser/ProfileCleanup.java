package ru.cashprediction.parity.browser;

import java.io.IOException;
import java.nio.file.Path;
import ru.cashprediction.parity.io.Dirs;

/** Ограниченно ждёт освобождения временного профиля ОС, не скрывая окончательную ошибку удаления. */
final class ProfileCleanup {
    private ProfileCleanup() { }

    /** Операция удаления, заменяемая в регрессиях для воспроизводимой блокировки файла. */
    @FunctionalInterface interface Deletion { void run() throws IOException; }

    /** Удаляет только заданный отдельный профиль после завершения принадлежащих стенду процессов. */
    static void delete(Path profile) throws IOException {
        retry(() -> Dirs.deleteRecursively(profile), 20, 250);
    }

    /** Повторяет неуспешное удаление; прерывание и последняя ошибка выходят вызывающему коду. */
    static void retry(Deletion deletion, int attempts, long delayMillis) throws IOException {
        if (attempts < 1 || delayMillis < 0) throw new IllegalArgumentException("Cleanup retry limits");
        for (int attempt = 0; ; attempt++) {
            try { deletion.run(); return; }
            catch (IOException failure) {
                if (attempt + 1 == attempts) throw failure;
                try { Thread.sleep(delayMillis); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    IOException stopped = new IOException("Browser profile cleanup interrupted", interrupted);
                    stopped.addSuppressed(failure);
                    throw stopped;
                }
            }
        }
    }
}
