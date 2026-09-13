package ru.cashprediction.web;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.json.PlanJson;
import ru.cashprediction.core.markdown.ReadResult;

/**
 * Маршруты меню «Файл»: список планов, новый план, открытие, пример, импорт, сохранение, «Сохранить как»,
 * переименование, перечитывание, скачивание и выбор папки на сеанс.
 *
 * <p>Сами команды выполняет {@link PlanFileCommands}; здесь только разбор тела запроса и ответ. Ответ каждой команды,
 * меняющей документ, — полное состояние ({@link StateJson}), чтобы браузер сразу перерисовался.</p>
 *
 * <p>Класс без изменяемого состояния; маршруты выполняются под монитором {@link ServerState}.</p>
 */
public final class FileApi {

    private final ServerState state;
    private final PlanFileCommands files;

    /**
     * Создаёт маршруты.
     *
     * @param state серверное состояние
     * @param files команды файлов плана
     */
    public FileApi(ServerState state, PlanFileCommands files) {
        this.state = Objects.requireNonNull(state, "state");
        this.files = Objects.requireNonNull(files, "files");
    }

    /**
     * Регистрирует маршруты.
     *
     * @param router маршрутизатор
     */
    public void register(Router router) {
        // Диалог 10 «Открыть план»: JavaFX ChoiceDialog → Swing JOptionPane.showInputDialog → Web <dialog> с <select>.
        router.add("GET", "/api/plans", request -> ApiResponse.json(files.listPlans()));
        router.add("POST", "/api/plans/new", request -> {
            files.newPlan(ApiRequest.stringMap(request.objectOrSelf("fields")));
            closeWindow(request.string("windowId", ""));
            return state();
        });
        router.add("POST", "/api/plans/open", request -> {
            ReadResult result = files.open(request.string("name", ""), request.string("path", ""));
            return withDiagnostics(result);
        });
        router.add("POST", "/api/plans/sample", request -> {
            files.openSample();
            return state();
        });
        router.add("POST", "/api/plans/import", request ->
                withDiagnostics(files.importPlan(request.string("name", ""), request.string("text", ""))));
        router.add("POST", "/api/plans/reload", request -> withDiagnostics(files.reload()));
        router.add("POST", "/api/plans/save", request -> {
            Path saved = files.save(request.bool("overwrite", false));
            return savedState(saved);
        });
        // Диалог 12 «Сохранить как»: JavaFX FileChooser → Swing JFileChooser → Web: обозреватель /api/fs + имя файла.
        router.add("POST", "/api/plans/save-as", request -> {
            Path saved = files.saveAs(request.string("name", ""), request.string("folder", ""), request.bool("overwrite", false));
            return savedState(saved);
        });
        // Диалог 9 «Переименовать»: JavaFX TextInputDialog → Swing JOptionPane.showInputDialog → Web <dialog> с <input>.
        router.add("POST", "/api/plans/rename", request -> {
            files.rename(request.string("name", ""));
            closeWindow(request.string("windowId", ""));
            return state();
        });
        router.add("GET", "/api/plans/download", request -> {
            PlanFileCommands.Download download = files.download(request.query("name", ""));
            return ApiResponse.download("text/markdown; charset=utf-8", download.text(), download.fileName());
        });
        // Диалог 13 «Папка CashMemory»: JavaFX DirectoryChooser → Swing JFileChooser(DIRECTORIES_ONLY) → Web /api/fs?mode=dirs.
        router.add("POST", "/api/plans/folder", request -> ApiResponse.json(files.chooseFolder(request.string("folder", ""))));
    }

    /** @return ответ с полным состоянием */
    private ApiResponse state() {
        return ApiResponse.json(StateJson.build(state));
    }

    /**
     * Состояние плюс диагностика чтения (браузер покажет {@code Alert(WARNING)}, если есть предупреждения).
     *
     * @param result результат чтения плана
     * @return ответ
     */
    private ApiResponse withDiagnostics(ReadResult result) {
        Map<String, Object> m = StateJson.build(state);
        m.put("diagnostics", PlanJson.diagnostics(result.diagnostics()));
        m.put("hasWarnings", result.hasWarnings());
        return ApiResponse.json(m);
    }

    /**
     * Состояние плюс путь сохранённого файла.
     *
     * @param saved путь
     * @return ответ
     */
    private ApiResponse savedState(Path saved) {
        Map<String, Object> m = StateJson.build(state);
        m.put("savedPath", saved.toString());
        return ApiResponse.json(m);
    }

    /**
     * Закрывает окно браузера, из которого пришла команда (мастер, ввод имени), если оно ещё открыто.
     *
     * @param windowId идентификатор или пустая строка
     */
    private void closeWindow(String windowId) {
        if (windowId != null && !windowId.isBlank()) {
            state.windows().stream().filter(w -> w.windowId().equals(windowId)).findFirst()
                    .ifPresent(w -> state.closeWindow(windowId));
        }
    }
}
