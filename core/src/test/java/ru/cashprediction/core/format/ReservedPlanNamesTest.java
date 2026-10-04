package ru.cashprediction.core.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.io.CashMemoryLayout;
import ru.cashprediction.core.text.Texts;

/** Общие правила служебных имён сохраняют точную грамматику и сообщения обоих потребителей. */
class ReservedPlanNamesTest {

    /** Публичные списки потребителей используют один неизменяемый набор прежних имён. */
    @Test
    void exactNamesAreSharedAndImmutable() {
        assertEquals(List.of("settings", "web-session", "web-session.plan", "session-fx", "session-swing",
                "web-reconnect", "web-reconnect-lock"), ReservedPlanNames.NAMES);
        assertSame(ReservedPlanNames.NAMES, CashMemoryLayout.RESERVED_PLAN_NAMES);
        assertEquals(Set.copyOf(ReservedPlanNames.NAMES), PlanValidator.RESERVED_NAMES);
        assertThrows(UnsupportedOperationException.class, () -> ReservedPlanNames.NAMES.add("plan"));
        assertThrows(UnsupportedOperationException.class, () -> PlanValidator.RESERVED_NAMES.add("plan"));
    }

    /** Резервирование не требует суффикса ключа и не поглощает соседние пользовательские имена. */
    @Test
    void planNameGrammarAndLocalizedDiagnosticStayExact() {
        for (String name : List.of("settings", "web-session", "web-session.plan", "session-fx", "session-swing",
                "web-reconnect", "web-reconnect-lock", "web-reconnect-tmp-", "web-reconnect-tmp-anything")) {
            for (String input : List.of(name, "\u2003" + name.toUpperCase(Locale.ROOT) + "\u2003")) {
                assertTrue(ReservedPlanNames.isReservedPlanName(input), input);
                assertTrue(CashMemoryLayout.isReservedPlanName(input), input);
                assertEquals(Optional.of(Texts.get("diagnostic.name.reservedByApp", input.strip())),
                        PlanValidator.checkPlanName(input), input);
            }
        }
        for (String name : List.of("settings.md", "web-session.plan.md", "session-web", "session-other",
                "settings-personal", "web-reconnect-personal", "web-reconnect-tmp", "xweb-reconnect-tmp-key")) {
            assertFalse(ReservedPlanNames.isReservedPlanName(name), name);
            assertFalse(CashMemoryLayout.isReservedPlanName(name), name);
            assertEquals(Optional.empty(), PlanValidator.checkPlanName(name), name);
        }
        for (String name : new String[] {null, "", "\u2003"}) {
            assertFalse(ReservedPlanNames.isReservedPlanName(name));
            assertFalse(CashMemoryLayout.isReservedPlanName(name));
        }
    }

    /** Файловая грамматика шире имён планов: любые .tmp и session-*.xml остаются служебными. */
    @Test
    void serviceFileGrammarAndWindowsAliasesStayExact() {
        for (String name : List.of("settings.md", "web-session.md", "web-session.plan.md", "session-fx.md",
                "session-swing.md", "web-reconnect.md", "web-reconnect-lock.md", "web-reconnect-tmp-.md",
                "web-reconnect-tmp-anything.md", "session-web.xml", "session-.xml", "session-any-client.xml",
                "settings.md.123.456.tmp", "plan.tmp", ".tmp")) {
            for (String input : List.of(name, " " + name.toUpperCase(Locale.ROOT) + " . . ")) {
                assertTrue(ReservedPlanNames.isServiceFileName(input), input);
                assertTrue(CashMemoryLayout.isServiceFileName(input), input);
            }
        }
        for (String name : new String[] {null, "", ".", "...", "settings", "settings.xml", "settings.md.bak",
                "settings-personal.md", "web-reconnect-tmp.md", "web-reconnect-personal.md", "session-web.md",
                "session.xml", "xsession-web.xml", "session-web.xml.bak", "plan.md", "plan.tmp.md"}) {
            assertFalse(ReservedPlanNames.isServiceFileName(name), String.valueOf(name));
            assertFalse(CashMemoryLayout.isServiceFileName(name), String.valueOf(name));
        }
        assertEquals("session-fx.xml", CashMemoryLayout.sessionXmlFileName("fx"));
        assertEquals("session-swing.xml", CashMemoryLayout.sessionXmlFileName("swing"));
        assertEquals("session-web.xml", CashMemoryLayout.sessionXmlFileName("web"));
    }

    /** Порядок проверок имени и прежние ключи русских сообщений не меняются при извлечении правил. */
    @Test
    void otherNameDiagnosticsKeepTheirPrecedence() {
        assertEquals(Optional.of(Texts.get("diagnostic.name.empty")), PlanValidator.checkPlanName(null));
        assertEquals(Optional.of(Texts.get("diagnostic.name.empty")), PlanValidator.checkPlanName(" "));
        assertEquals(Optional.of(Texts.get("diagnostic.name.tooLong", PlanValidator.MAX_NAME_LENGTH)),
                PlanValidator.checkPlanName("web-reconnect-tmp-" + "x".repeat(PlanValidator.MAX_NAME_LENGTH)));
        assertEquals(Optional.of(Texts.get("diagnostic.name.forbiddenChars")),
                PlanValidator.checkPlanName("web-reconnect-tmp-?"));
        assertEquals(Optional.of(Texts.get("diagnostic.name.controlChars")),
                PlanValidator.checkPlanName("web-reconnect-tmp-x\u0001x"));
        assertEquals(Optional.of(Texts.get("diagnostic.name.endsWithDot")),
                PlanValidator.checkPlanName("web-reconnect-tmp-."));
        assertEquals(Optional.of(Texts.get("diagnostic.name.reservedByWindows", "CON")),
                PlanValidator.checkPlanName(" CON "));
    }
}
