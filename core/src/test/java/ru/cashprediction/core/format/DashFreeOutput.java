package ru.cashprediction.core.format;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Проверка текста, который программа пишет в файлы CashMemory: в его байтах нет длинного (U+2014) и среднего (U+2013)
 * тире.
 *
 * <p>Решение пользователя 2026-09-14 («Замени все знаки длинного тире в интерфейсе всех клиентов на "-"»): в интерфейсе
 * и в файлах CashMemory стоит только дефис-минус «-»; старые файлы тоже переведены на дефис, пока у приложения нет
 * пользователей, поэтому читатели тире не распознают. Символы заданы кодами, чтобы сама проверка не содержала тире.</p>
 */
public final class DashFreeOutput {

    /** Длинное тире U+2014. */
    public static final char EM_DASH = (char) 0x2014;
    /** Среднее тире U+2013. */
    public static final char EN_DASH = (char) 0x2013;

    private DashFreeOutput() {
    }

    /**
     * Проверяет, что UTF-8 байты текста не содержат длинного и среднего тире.
     *
     * @param what что проверяется (для сообщения об ошибке)
     * @param text записываемый текст
     */
    public static void assertNoDashes(String what, String text) {
        assertNoDashes(what, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Проверяет, что байты файла в UTF-8 не содержат длинного (E2 80 94) и среднего (E2 80 93) тире.
     *
     * @param what  что проверяется (для сообщения об ошибке)
     * @param bytes байты файла
     */
    public static void assertNoDashes(String what, byte[] bytes) {
        List<String> found = new ArrayList<>();
        int line = 1;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                line++;
            } else if (i + 2 < bytes.length && bytes[i] == (byte) 0xE2 && bytes[i + 1] == (byte) 0x80
                    && (bytes[i + 2] == (byte) 0x93 || bytes[i + 2] == (byte) 0x94)) {
                found.add("строка " + line + ": U+20" + (bytes[i + 2] == (byte) 0x93 ? "13" : "14"));
            }
        }
        assertEquals(List.of(), found, what + ": в записанном файле есть длинное или среднее тире");
    }
}
