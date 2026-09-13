package ru.cashprediction.core.app.fake;

import java.util.ArrayList;
import java.util.List;
import ru.cashprediction.core.app.WindowHandle;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.form.FormView;

/**
 * Ручка окна {@link FakeUiPort}: записывает обновления, закрытие и подъём окна; границы задаёт тест.
 *
 * <p>Не потокобезопасна: только поток теста.</p>
 */
public final class FakeWindowHandle implements WindowHandle {

    private final String id;
    private final List<FormView> updates = new ArrayList<>();
    private final List<AlertSpec> alertUpdates = new ArrayList<>();
    private WindowBounds bounds;
    private boolean closed;
    private int toFrontCount;

    /**
     * Создаёт ручку.
     *
     * @param id     id записи порта ({@code form1}, {@code alert2}, …)
     * @param bounds начальные границы или {@code null}
     */
    public FakeWindowHandle(String id, WindowBounds bounds) {
        this.id = id;
        this.bounds = bounds;
    }

    /** @return id записи порта */
    public String id() {
        return id;
    }

    @Override
    public void update(FormView view) {
        updates.add(view);
    }

    @Override
    public void updateAlert(AlertSpec spec) {
        alertUpdates.add(spec);
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void toFront() {
        toFrontCount++;
    }

    @Override
    public WindowBounds bounds() {
        return bounds;
    }

    @Override
    public boolean showing() {
        return !closed;
    }

    /**
     * Задаёт границы, как будто пользователь передвинул окно.
     *
     * @param newBounds границы
     */
    public void setBounds(WindowBounds newBounds) {
        bounds = newBounds;
    }

    /** @return модели, пришедшие в {@link #update(FormView)}, по порядку */
    public List<FormView> updates() {
        return List.copyOf(updates);
    }

    /** @return описания, пришедшие в {@link #updateAlert(AlertSpec)} */
    public List<AlertSpec> alertUpdates() {
        return List.copyOf(alertUpdates);
    }

    /** @return закрыто ли окно */
    public boolean closed() {
        return closed;
    }

    /** @return сколько раз окно поднималось */
    public int toFrontCount() {
        return toFrontCount;
    }
}
