package ru.cashprediction.parity.audit;

import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.dump.AllowedDiffs;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.text.Texts;
import static org.junit.jupiter.api.Assertions.*;

/** Защищает точный контракт desktop №8 без GUI, новых snapshots и расширения маски. */
final class SnapshotDetailsAuditTest {
    private static final AllowedDiffs.Entry DESKTOP = new AllowedDiffs.Entry(8, "/alerts/lastSnapshot/details",
            Set.of("fx", "swing"), "§10 №8", "only exact localized store headings");

    /** Один registry suffix с неизменным payload допустим, равные поля не доказывают использование. */
    @Test void onlyRegistrySuffixIsPermitted() {
        var result = SnapshotDetailsAudit.inspect(DESKTOP, registry("fx", "same"), registry("swing", "same"));
        assertEquals(true, result.get("permittedByExactRule")); assertEquals(0, result.get("residualLineCount"));
        assertEquals(false, SnapshotDetailsAudit.inspect(DESKTOP, "same", "same").get("permittedByExactRule"));
    }

    /** Известное имя физического XML-файла допустимо только в полном локализованном heading. */
    @Test void knownXmlHeadingIsPermitted() {
        var result = SnapshotDetailsAudit.inspect(DESKTOP, registry("fx", "same") + "\n\n" + xml("fx", "same"),
                registry("swing", "same") + "\n\n" + xml("swing", "same"));
        assertEquals(true, result.get("permittedByExactRule")); assertEquals(0, result.get("residualLineCount"));
    }

    /** Голые адреса и похожие headings внутри payload не разрешаются. */
    @Test void syntheticAndPayloadPathsAreRejected() {
        for (String[] pair : new String[][]{{"<node>/fx (JSON)", "<node>/swing (JSON)"},
                {"<CashMemory>/session-fx.xml", "<CashMemory>/session-swing.xml"},
                {"payload=" + registry("fx", "same"), "payload=" + registry("swing", "same")},
                {"payload=" + xml("fx", "same"), "payload=" + xml("swing", "same")}}) {
            var result = SnapshotDetailsAudit.inspect(DESKTOP, pair[0], pair[1]);
            assertEquals(false, result.get("permittedByExactRule")); assertEquals(1, result.get("residualLineCount"));
        }
    }

    /** Client, дробная часть времени и bounds не становятся разрешёнными от восстановления suffix. */
    @Test void liveRecorderPayloadIsNotMasked() {
        for (String[] payload : new String[][]{{"client=fx", "client=swing"},
                {"savedAt=<time>.1Z", "savedAt=<time>.2Z"}, {"y=310", "y=465"},
                {"pid=19176", "pid=13616"}, {"<client>fx</client>", "<client>swing</client>"},
                {"<node>/fx", "<node>/swing"}, {"<CashMemory>/session-fx.xml", "<CashMemory>/session-swing.xml"}}) {
            var result = SnapshotDetailsAudit.inspect(DESKTOP, registry("fx", payload[0]) + "\n\n" + xml("fx", payload[0]),
                    registry("swing", payload[1]) + "\n\n" + xml("swing", payload[1]));
            assertEquals(false, result.get("permittedByExactRule")); assertEquals(2, result.get("residualLineCount"));
        }
    }

    /** Настоящий шаблон registry с настоящим локализованным названием хранилища. */
    private static String registry(String client, String body) {
        return UiText.get("s2.recovery.registryBlock", Texts.get("session.store.title.registry"), "<node>/" + client, body);
    }

    /** Настоящий шаблон XML с известным физическим именем файла. */
    private static String xml(String client, String body) {
        return UiText.get("s2.recovery.fileBlock", Texts.get("session.store.title.xml"), "<CashMemory>/session-" + client + ".xml", body);
    }
}
