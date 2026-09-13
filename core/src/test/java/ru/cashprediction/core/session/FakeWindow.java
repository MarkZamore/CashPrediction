package ru.cashprediction.core.session;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Окно без UI для тестов: хранит состояние в полях.
 */
final class FakeWindow implements StatefulWindow {

    private final String id;
    private final WindowType type;
    private final boolean modal;
    private final String owner;
    final Map<String, String> context = new LinkedHashMap<>();
    final Map<String, String> fields = new LinkedHashMap<>();
    WindowBounds bounds;
    volatile boolean failCapture;
    WindowState applied;

    FakeWindow(String id, WindowType type, boolean modal, String owner) {
        this.id = id;
        this.type = type;
        this.modal = modal;
        this.owner = owner;
    }

    /**
     * Создаёт окно по состоянию (как это делает фабрика клиента) и применяет его.
     *
     * @param state состояние
     * @return окно
     */
    static FakeWindow fromState(WindowState state) {
        FakeWindow window = new FakeWindow(state.id(), state.type(), state.modal(), state.ownerId());
        window.context.putAll(state.context());
        window.applyState(state);
        return window;
    }

    @Override
    public String windowId() {
        return id;
    }

    @Override
    public WindowType windowType() {
        return type;
    }

    @Override
    public boolean modal() {
        return modal;
    }

    @Override
    public String ownerId() {
        return owner;
    }

    @Override
    public WindowState captureState() {
        if (failCapture) {
            throw new IllegalStateException("окно сломано");
        }
        return new WindowState(id, type, modal, owner, bounds, context, fields);
    }

    @Override
    public void applyState(WindowState state) {
        applied = state;
        fields.clear();
        fields.putAll(state.fields());
        bounds = state.bounds();
    }
}
