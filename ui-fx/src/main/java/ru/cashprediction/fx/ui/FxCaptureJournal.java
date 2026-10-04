package ru.cashprediction.fx.ui;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.collections.ObservableSet;
import javafx.collections.SetChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.effect.Effect;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Transform;
import javafx.stage.Window;

/**
 * Локальный журнал публичных свойств живого дерева JavaFX и настоящих post-layout callbacks.
 * Изменения учитываются событиями, поэтому возврат A-B-A не стирает поколение.
 * Область наблюдения задаётся attach; потомки, clip, graphic, shape и transforms
 * подключаются по фактическим ссылкам, включая unmanaged-узлы и узлы скина.
 * Публичные методы *Property наблюдаются через ChangeListener, а не только
 * InvalidationListener: последний может пропустить повторное изменение ленивого свойства.
 * Это ограниченное покрытие публичных свойств, а не полный журнал краски движка.
 * Canvas, изменяемые пиксели, внутренняя анимация Image, CSS движка и произвольный
 * painter не получают доказательства полноты из таких listeners.
 * Никакой запрос pulse, token или завершённый snapshot не подтверждает краску узла.
 * Все публичные операции выполняются на FX-потоке; close снимает все listeners.
 */
public final class FxCaptureJournal implements AutoCloseable {
    private final BooleanSupplier onThread;
    private final IdentityHashMap<Node, Boolean> roots = new IdentityHashMap<>();
    private final IdentityHashMap<Scene, SceneScope> scenes = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Boolean> watched = new IdentityHashMap<>();
    private final List<Runnable> removals = new ArrayList<>();
    private final List<CoverageGap> listenerGaps = new ArrayList<>();
    private long epoch;
    private long layoutRevision;
    private long renderGeneration;
    private boolean rebuilding;
    private boolean closed;

    /** Создаёт журнал без глобальных listeners, файлов или запросов отрисовки. */
    public FxCaptureJournal() { this(Platform::isFxApplicationThread); }

    /** Подменяет только проверку потока для тестов свойств без toolkit; факты краски не подменяются. */
    FxCaptureJournal(BooleanSupplier onThread) { this.onThread = Objects.requireNonNull(onThread); }

    /** Причина неполного покрытия; source является настоящим объектом, а не семантическим token. */
    public record CoverageGap(Object source, String property, String reason) {
        /** Проверяет обязательные поля диагностической записи. */
        public CoverageGap {
            Objects.requireNonNull(source); Objects.requireNonNull(property); Objects.requireNonNull(reason);
        }
    }

    /**
     * Подключает сцену до начала capture bracket и слушает настоящий post-layout pulse.
     * Повторное подключение идемпотентно. Покрытие окон ограничено периодом подключения сцен.
     */
    public void attach(Scene scene) {
        check(); Objects.requireNonNull(scene);
        if (scenes.containsKey(scene)) return;
        SceneScope scope = new SceneScope(scene);
        scenes.put(scene, scope);
        scene.addPostLayoutPulseListener(scope.pulse);
        mutation(); rebuild();
    }

    /** Подключает отдельное настоящее поддерево; это само по себе не даёт барьера сцены. */
    public void attach(Node root) {
        check(); Objects.requireNonNull(root);
        if (roots.put(root, Boolean.TRUE) == null) { mutation(); rebuild(); }
    }

    /** Отключает сцену и её pulse listener; перекрывающиеся явно подключённые области сохраняются. */
    public void detach(Scene scene) {
        check(); Objects.requireNonNull(scene);
        SceneScope scope = scenes.remove(scene);
        if (scope == null) return;
        scene.removePostLayoutPulseListener(scope.pulse);
        mutation(); rebuild();
    }

    /** Отключает явно подключённое поддерево; новое подключение начинает новую историю области. */
    public void detach(Node root) {
        check(); Objects.requireNonNull(root);
        if (roots.remove(root) != null) { mutation(); rebuild(); }
    }

    /**
     * Реализация Transaction.stamp: эпоха событий, консервативная ревизия layout,
     * нулевая неподтверждённая ревизия краски узлов и поколение мутаций/post-layout callbacks.
     * mutationJournalComplete всегда false: публичный JavaFX не предоставляет полного hook.
     * postLayoutPulse означает реальный callback после последнего наблюдаемого изменения
     * всех подключённых сцен, но не подтверждение экранного рендера или краски узлов.
     */
    public FxPaintCollector.Stamp stamp() {
        check();
        boolean settled = !scenes.isEmpty();
        for (SceneScope scope : scenes.values())
            settled &= scope.postLayoutEpoch == epoch && !needsLayout(scope.scene.getRoot());
        return new FxPaintCollector.Stamp(epoch, layoutRevision, 0, renderGeneration, settled, false);
    }

    /**
     * Реализация Transaction.lastPaintEpoch. Всегда null: публичного hook завершения
     * фактической краски отдельного узла нет. Даже snapshot всей сцены не заменяет его.
     */
    public Long lastPaintEpoch(Node node) { check(); Objects.requireNonNull(node); return null; }

    /**
     * Выполняет настоящий синхронный Scene.snapshot, сохраняя его исходный результат.
     * Чтение не увеличивает generation: CSS/layout внутри него могут породить
     * настоящие наблюдаемые мутации, которые по-прежнему учитываются listeners.
     * После успешного возврата сохраняется только отметка поколения снятой сцены,
     * а не новая эпоха краски или свидетельство экранной презентации.
     */
    public WritableImage snapshot(Scene scene) {
        check(); SceneScope scope = requireScene(scene);
        WritableImage image = readSnapshot(() -> scene.snapshot(null));
        scope.snapshotGeneration = renderGeneration;
        return image;
    }

    /** Возвращает поколение, наблюдавшееся на возврате последнего успешного snapshot, либо null. */
    public Long lastSnapshotGeneration(Scene scene) {
        check(); return requireScene(scene).snapshotGeneration;
    }

    /** Выполняет чтение без записи поколения; seam для проверки побочных мутаций без toolkit. */
    <T> T readSnapshot(Supplier<T> reader) {
        check(); return Objects.requireNonNull(reader).get();
    }

    /**
     * Возвращает снимок известных пробелов, включая обязательный отсутствующий node-paint hook.
     * Даже пустое дерево не позволяет объявить полный журнал. Список не является
     * PaintObservation.Unsupported: идентичности capture назначает FxPaintCollector/MAIN.
     */
    public List<CoverageGap> coverageGaps() {
        check(); List<CoverageGap> result = new ArrayList<>(listenerGaps);
        result.add(new CoverageGap(this, "nodePaint", "actual per-node paint completion hook absent"));
        result.add(new CoverageGap(this, "nativePaint", "post-layout callback is not native render completion or presentation"));
        result.add(new CoverageGap(this, "journal", "public observable properties only; internal CSS/render/painter mutations are not covered"));
        for (Object object : watched.keySet()) {
            if (object instanceof Canvas)
                result.add(new CoverageGap(object, "canvas", "GraphicsContext commands and actual render completion hook absent"));
            if (object instanceof Effect)
                result.add(new CoverageGap(object, "effect", "effect properties are observed; internal render effects are not certified"));
            if (object instanceof Image)
                result.add(new CoverageGap(object, "image", "pixel writes and decoded animation frames have no public mutation journal"));
        }
        return List.copyOf(result);
    }

    /** Снимает все listeners и ссылки; повторное закрытие безопасно, остальные операции после него запрещены. */
    @Override public void close() {
        thread(); if (closed) return;
        for (SceneScope scope : scenes.values()) scope.scene.removePostLayoutPulseListener(scope.pulse);
        removeListeners(); scenes.clear(); roots.clear(); watched.clear(); listenerGaps.clear(); closed = true;
    }

    /** Проверяет поток независимо от состояния закрытия. */
    private void thread() {
        if (!onThread.getAsBoolean()) throw new IllegalStateException("FX journal thread required");
    }

    /** Проверяет жизненный цикл всех читающих и изменяющих операций. */
    private void check() { thread(); if (closed) throw new IllegalStateException("FX journal closed"); }

    /** Разрешает только сцену текущей области наблюдения. */
    private SceneScope requireScene(Scene scene) {
        Objects.requireNonNull(scene); SceneScope scope = scenes.get(scene);
        if (scope == null) throw new IllegalArgumentException("scene is not attached");
        return scope;
    }

    /** Каждая наблюдаемая мутация меняет поколение и консервативно инвалидирует layout барьер. */
    private void mutation() {
        epoch = Math.incrementExact(epoch); layoutRevision = Math.incrementExact(layoutRevision);
        renderGeneration = Math.incrementExact(renderGeneration);
    }

    /** Переподключает фактические ссылки после замены дерева, скина, clip, image или transform. */
    private void rebuild() {
        if (rebuilding) return;
        rebuilding = true;
        try {
            removeListeners(); watched.clear(); listenerGaps.clear();
            for (Node root : roots.keySet()) watchObject(root);
            for (Scene scene : scenes.keySet()) watchObject(scene);
            if (!scenes.isEmpty()) {
                watchList(Window.getWindows());
                for (Window window : Window.getWindows()) watchObject(window);
            }
        } finally { rebuilding = false; }
    }

    /** Снимает listeners в обратном порядке, не меняя свойства интерфейса. */
    private void removeListeners() {
        for (int i = removals.size() - 1; i >= 0; i--) removals.get(i).run();
        removals.clear();
    }

    /**
     * Наблюдает доступные публичные zero-arg *Property getters настоящего объекта.
     * Reflection не открывает модули и не вызывает setAccessible; недоступный getter
     * сохраняется как пробел. Отсутствие getters у скрытого painter не означает полноту.
     */
    private void watchObject(Object object) {
        if (!(object instanceof Node || object instanceof Scene || object instanceof Window
                || object instanceof Transform || object instanceof Image || object instanceof Effect)
                || watched.put(object, Boolean.TRUE) != null) return;
        for (Method method : object.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || !method.getName().endsWith("Property")
                    || !ObservableValue.class.isAssignableFrom(method.getReturnType())) continue;
            // parent/scene наблюдаются как связи, но не расширяют явно заданную область до предков.
            boolean followReference = !(object instanceof Node
                    && (method.getName().equals("parentProperty") || method.getName().equals("sceneProperty")));
            try { watchValue((ObservableValue<?>) method.invoke(object), followReference); }
            catch (IllegalAccessException | InvocationTargetException error) {
                listenerGaps.add(new CoverageGap(object, method.getName(), "public property getter unavailable"));
            }
        }
        if (object instanceof Node node) {
            watchList(node.getStyleClass()); watchSet(node.getPseudoClassStates());
            watchList(node.getTransforms()); watchMap(node.getProperties());
            watchObject(node.getClip());
            if (node instanceof Parent parent) {
                watchList(parent.getChildrenUnmodifiable()); watchList(parent.getStylesheets());
                for (Node child : parent.getChildrenUnmodifiable()) watchObject(child);
            }
        }
        if (object instanceof Scene scene) {
            watchList(scene.getStylesheets()); watchObject(scene.getRoot()); watchObject(scene.getWindow());
        }
    }

    /**
     * ChangeListener принудительно валидирует значение после каждого события.
     * Новые ссылки получают listeners сразу в том же callback; старые освобождаются.
     */
    @SuppressWarnings("unchecked")
    private void watchValue(ObservableValue<?> observable, boolean followReference) {
        if (observable == null || watched.put(observable, Boolean.TRUE) != null) return;
        ObservableValue<Object> value = (ObservableValue<Object>) observable;
        ChangeListener<Object> listener = (source, before, after) -> {
            if (closed) return;
            mutation();
            if (!rebuilding && (trackedReference(before) || trackedReference(after))) rebuild();
        };
        value.addListener(listener); removals.add(() -> value.removeListener(listener));
        Object installed = value.getValue();
        if (followReference) watchObject(installed);
    }

    /** Определяет ссылки, замена которых меняет область наблюдаемых объектов. */
    private static boolean trackedReference(Object object) {
        return object instanceof Node || object instanceof Scene || object instanceof Window
                || object instanceof Transform || object instanceof Image || object instanceof Effect;
    }

    /** Подключает список с немедленной переписью после изменения, включая перестановки детей. */
    @SuppressWarnings("unchecked")
    private void watchList(ObservableList<?> list) {
        if (watched.put(list, Boolean.TRUE) != null) return;
        ObservableList<Object> values = (ObservableList<Object>) list;
        ListChangeListener<Object> listener = change -> {
            if (closed) return;
            mutation(); rebuild();
        };
        values.addListener(listener); removals.add(() -> values.removeListener(listener));
        for (Object value : values) watchObject(value);
    }

    /** Подключает карту реальных properties; произвольные изменяемые значения внутри неё не сертифицируются. */
    @SuppressWarnings("unchecked")
    private void watchMap(ObservableMap<?, ?> map) {
        if (watched.put(map, Boolean.TRUE) != null) return;
        ObservableMap<Object, Object> values = (ObservableMap<Object, Object>) map;
        MapChangeListener<Object, Object> listener = change -> {
            if (closed) return;
            mutation(); rebuild();
        };
        values.addListener(listener); removals.add(() -> values.removeListener(listener));
        for (Object value : values.values()) watchObject(value);
    }

    /** Подключает фактические CSS pseudo-states без вывода состояния из цвета рамки. */
    @SuppressWarnings("unchecked")
    private void watchSet(ObservableSet<?> set) {
        if (watched.put(set, Boolean.TRUE) != null) return;
        ObservableSet<Object> values = (ObservableSet<Object>) set;
        SetChangeListener<Object> listener = change -> {
            if (closed) return;
            mutation(); rebuild();
        };
        values.addListener(listener); removals.add(() -> values.removeListener(listener));
    }

    /** Проверяет pending layout также у unmanaged потомков снимаемой сцены. */
    private static boolean needsLayout(Node node) {
        if (node instanceof Parent parent) {
            if (parent.isNeedsLayout()) return true;
            for (Node child : parent.getChildrenUnmodifiable()) if (needsLayout(child)) return true;
        }
        return false;
    }

    /** Состояние подключения сцены, отдельно от истории снимков и отсутствующей краски узлов. */
    private final class SceneScope {
        private final Scene scene;
        private final Runnable pulse;
        private long postLayoutEpoch = -1;
        private Long snapshotGeneration;

        /** Учитывает только callback настоящего post-layout pulse; requestNextPulse не вызывается. */
        private SceneScope(Scene scene) {
            this.scene = scene;
            pulse = () -> {
                check(); layoutRevision = Math.incrementExact(layoutRevision);
                renderGeneration = Math.incrementExact(renderGeneration);
                postLayoutEpoch = needsLayout(scene.getRoot()) ? -1 : epoch;
            };
        }
    }
}
