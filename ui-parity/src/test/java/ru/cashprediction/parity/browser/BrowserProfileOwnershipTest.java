package ru.cashprediction.parity.browser;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Защищает очистку тестового Edge от совпадения с чужим профилем или похожим префиксом. */
class BrowserProfileOwnershipTest {
    /** Допускает реальные варианты кавычек Windows, пробелы и Unicode только в целом аргументе. */
    @Test void exactProfileArgumentAcceptsBothWindowsQuoteForms() {
        Path profile = Path.of("C:/Test Programs/Мои тесты/Δ/profile");
        String path = profile.toString();
        assertTrue(BrowserSession.hasProfileArgument("edge \"--user-data-dir=" + path + "\" about:blank", profile));
        assertTrue(BrowserSession.hasProfileArgument("edge --user-data-dir=\"" + path + "\" about:blank", profile));
        assertFalse(BrowserSession.hasProfileArgument("--user-data-dir=" + path, profile));
        Path simple = Path.of("C:/isolated/profile");
        assertTrue(BrowserSession.hasProfileArgument("--user-data-dir=" + simple, simple));
    }

    /** Чужой путь, суффикс и вложенная строка URL не дают права завершать процесс. */
    @Test void similarOrEmbeddedArgumentsAreNotOwnership() {
        Path profile = Path.of("C:/isolated/profile");
        String path = profile.toString();
        assertFalse(BrowserSession.hasProfileArgument("edge --user-data-dir=" + path + "-other", profile));
        assertFalse(BrowserSession.hasProfileArgument("edge --user-data-dir=" + path + "/child", profile));
        assertFalse(BrowserSession.hasProfileArgument("edge --url=--user-data-dir=" + path, profile));
        assertFalse(BrowserSession.hasProfileArgument("edge --user-data-dir=C:/real-user", profile));
    }
}
