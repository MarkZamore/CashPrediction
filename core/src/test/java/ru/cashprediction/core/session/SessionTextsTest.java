package ru.cashprediction.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.text.JavaSourceScanner;
import ru.cashprediction.core.text.Texts;

/**
 * Этап S0.5: тексты сессии (заголовки окон, предупреждение рекордера, сообщения проверок записей снимка) берутся из
 * каталога ({@code session_ru.properties}) и совпадают с прежними русскими строками кода буква в букву: прежние
 * клиенты сравнивают {@link RestoreCoordinator#RECORDER_NOT_STARTED} целиком.
 */
class SessionTextsTest {

    private static final Instant STARTED = Instant.parse("2026-09-13T10:00:00Z");

    @Test
    void windowTitlesComeFromCatalog() {
        assertEquals(List.of("Новый план", "Параметры плана", "Регулярная операция", "Разовая операция",
                        "Корректировка события", "Калькулятор цели", "Ввод значения", "Выбор значения", "Подтверждение",
                        "Экспорт в CSV", "Быстрая правка суммы"),
                List.of(WindowType.values()).stream().map(WindowType::title).toList());
        for (WindowType type : WindowType.values()) {
            assertTrue(Texts.has(type.titleKey()), type.titleKey());
            assertEquals(Texts.get(type.titleKey()), type.title());
        }
    }

    @Test
    void recorderNotStartedIsUnchanged() {
        assertEquals("Запись сессии не начата: несохранённый план из снимка не открылся, "
                + "а новый снимок затёр бы его. Снимок сохранён и будет предложен при следующем запуске",
                RestoreCoordinator.RECORDER_NOT_STARTED);
    }

    @Test
    void recordValidationMessages() {
        assertEquals("Координаты окна должны быть конечными числами",
                message(() -> new WindowBounds(Double.NaN, 0, 1, 1)));
        assertEquals("Размер окна не может быть отрицательным: -1.0×5.0", message(() -> new WindowBounds(0, 0, -1, 5)));
        assertEquals("Идентификатор окна не может быть пустым",
                message(() -> new WindowState(" ", WindowType.ALERT, true, "main", null, Map.of(), Map.of())));
        assertEquals("Некорректное состояние сеанса: «paused»", message(() -> new SessionMarker("paused", 1, STARTED, "fx")));
        assertEquals("Некорректный идентификатор процесса: -1", message(() -> new SessionMarker("running", -1, STARTED, "fx")));
        assertEquals("Некорректный идентификатор клиента: «Web!»", message(() -> SnapshotSchema.requireClient("Web!")));
        assertEquals("Некорректная версия схемы снимка: 0", message(() -> SnapshotSchema.requireSupported(0)));
        assertEquals("Снимок создан более новой версией программы (схема 2, поддерживается 1)",
                message(() -> SnapshotSchema.requireSupported(2)));
    }

    @Test
    void developerMessagesAreNotCyrillic() {
        // Невозможные состояния описываются для разработчика латиницей и в каталог текстов не попадают.
        assertFalse(JavaSourceScanner.hasCyrillic(message(() -> new RestoreReport(List.of(), -1))));
    }

    /** @return сообщение {@link IllegalArgumentException}, брошенного действием */
    private static String message(Runnable action) {
        return assertThrows(IllegalArgumentException.class, action::run).getMessage();
    }
}
