package ru.cashprediction.core.ui.alert;

import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.WindowHandle;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.FormView;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет отдельный доступ к границам снимка без подмены актуальной геометрии ручки. */
class AlertRestoredBoundsTest {
    /** Исходные границы сохраняются после привязки и показа, а снимок читает живое окно. */
    @Test void restoredBoundsRemainIndependentOfAttachedWindow() {
        var spec = AlertCatalog.deleteRule("r1", "rent", Money.ofMajor(1), "RUB", "monthly", 0);
        var session = new AlertSession("a1", "main", spec, new AlertSession.Host() {
            /** Регистрация не требует внешнего хранилища. */
            @Override public void registered(AlertSession value) { }
            /** Закрытие не требует внешнего хранилища. */
            @Override public void unregistered(AlertSession value) { }
        });
        assertNull(session.restoredBounds());
        var restored = new WindowBounds(120, 140, 640, 480);
        var live = new WindowBounds(220, 240, 700, 500);
        session.applyState(new WindowState("a1", WindowType.ALERT, true, "main", restored, Map.of(), Map.of()));
        assertEquals(restored, session.restoredBounds());
        assertEquals(restored, session.captureState().bounds());
        session.attach(new WindowHandle() {
            /** Этот стенд не отображает формы. */
            @Override public void update(FormView view) { throw new UnsupportedOperationException(); }
            /** Этот стенд не обновляет содержимое сообщения. */
            @Override public void updateAlert(AlertSpec value) { }
            /** Закрытие ручки не имеет побочных действий. */
            @Override public void close() { }
            /** Подъём ручки не имеет побочных действий. */
            @Override public void toFront() { }
            /** Возвращает актуальные границы окна. */
            @Override public WindowBounds bounds() { return live; }
            /** Ручка представляет показанное окно. */
            @Override public boolean showing() { return true; }
        });
        session.shown();
        assertEquals(restored, session.restoredBounds());
        assertEquals(live, session.captureState().bounds());
    }
}
