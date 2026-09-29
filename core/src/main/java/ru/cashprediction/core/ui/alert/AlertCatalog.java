package ru.cashprediction.core.ui.alert;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.diagnostics.Severity;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.session.CrashDetector;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.ui.form.ButtonRole;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;

/** Фабрика всех сообщений ядра. JavaFX: Alert → Swing: JOptionPane → Web: dialog. */
public final class AlertCatalog {
    public static final String PURPOSE_DELETE_RULE = "deleteRule";
    public static final String PURPOSE_DELETE_ONE_TIME = "deleteOneTime";
    public static final String PURPOSE_ACTUALIZE = "actualize";
    public static final String PURPOSE_APPLY_WHAT_IF = "applyWhatIf";
    public static final String PURPOSE_CLEAR_SNAPSHOTS = "clearSnapshots";
    public static final List<String> RESTORABLE_PURPOSES = List.of(PURPOSE_DELETE_RULE, PURPOSE_DELETE_ONE_TIME, PURPOSE_ACTUALIZE, PURPOSE_APPLY_WHAT_IF, PURPOSE_CLEAR_SNAPSHOTS);
    public static final String BUTTON_OK = "ok";
    public static final String BUTTON_CANCEL = "cancel";
    public static final String BUTTON_SAVE = "save";
    public static final String BUTTON_DONT_SAVE = "dontSave";
    public static final String BUTTON_OVERWRITE = "overwrite";
    public static final String BUTTON_RELOAD = "reload";
    public static final String BUTTON_RESTORE_REGISTRY = "restoreRegistry";
    public static final String BUTTON_RESTORE_XML = "restoreXml";
    public static final String BUTTON_RESTORE_SERVER = "restoreServer";
    public static final String BUTTON_NO_RESTORE = "noRestore";
    private AlertCatalog() { }

    /** @return запрос сохранения несохранённого плана */
    public static AlertSpec unsavedChanges(String planName) { return alert(AlertKind.CONFIRMATION, "unsavedChanges", "", "alert.unsaved.title", "", "alert.unsaved.header", UiText.get("alert.unsaved.content"), "", false, buttons(save(), button(BUTTON_DONT_SAVE, "button.dontSave", ButtonRole.OTHER), cancel()), BUTTON_SAVE, false, planName); }
    /** @return запрос замены файла при первом сохранении */
    public static AlertSpec overwriteOnFirstSave(String fileBaseName) { return alert(AlertKind.CONFIRMATION, "overwriteOnFirstSave", "", "alert.overwriteFirst.title", "", "alert.overwriteFirst.header", UiText.get("alert.overwriteFirst.content"), "", false, buttons(overwrite(), cancel()), BUTTON_OVERWRITE, false, fileBaseName); }
    /** @return запрос замены файла из выбора файлов */
    public static AlertSpec replaceFile(String fileName) { return alert(AlertKind.CONFIRMATION, "replaceFile", "", "alert.replace.title", "", "alert.replace.header", "", "", false, buttons(button("replace", "button.replace", ButtonRole.OK), cancel()), "replace", false, fileName); }
    /** @return предупреждение о внешнем изменении файла */
    public static AlertSpec externalChange(String fileBaseName) { return alert(AlertKind.WARNING, "externalChange", "", "alert.external.title", "", "alert.external.header", UiText.get("alert.external.content"), "", false, buttons(overwrite(), button(BUTTON_RELOAD, "button.reload", ButtonRole.OTHER), cancel()), BUTTON_OVERWRITE, false, fileBaseName); }

    /** @return восстанавливаемое подтверждение удаления правила */
    public static AlertSpec deleteRule(String ruleId, String title, Money amount, String currency, String recurrence, int adjustments) {
        String detail = adjustments <= 0 ? UiText.get("alert.deleteRule.none") : UiText.get("alert.deleteRule.adjustments", UiFormats.count(adjustments, UiText.get("alert.adjustment.one"), UiText.get("alert.adjustment.few"), UiText.get("alert.adjustment.many")));
        return alert(AlertKind.CONFIRMATION, PURPOSE_DELETE_RULE, ruleId, "alert.deleteRule.title", "", "alert.deleteRule.header", detail + "\n" + UiText.get("alert.undo"), "", false, buttons(delete(), cancel()), "delete", true, title, amount.format(currency), recurrence);
    }
    /** @return восстанавливаемое подтверждение удаления разовой операции */
    public static AlertSpec deleteOneTime(String txId, String title, LocalDate date, Money amount, String currency) { return alert(AlertKind.CONFIRMATION, PURPOSE_DELETE_ONE_TIME, txId, "alert.deleteOneTime.title", "", "alert.deleteOneTime.header", UiText.get("alert.undo"), "", false, buttons(delete(), cancel()), "delete", true, title, UiFormats.date(date), amount.format(currency)); }
    /** @return предупреждение с подробностями диагностики */
    public static AlertSpec loadDiagnostics(String fileBaseName, List<Diagnostic> diagnostics) { return alert(AlertKind.WARNING, "loadDiagnostics", "", "alert.diagnostics.title", "", "alert.diagnostics.header", UiText.get("alert.diagnostics.content"), diagnosticDetails(diagnostics), true, buttons(ok()), BUTTON_OK, false, fileBaseName); }
    /** @return результат проверки плана */
    public static AlertSpec validation(List<String> lines) {
        List<String> safe = lines == null ? List.of() : List.copyOf(lines);
        if (safe.isEmpty()) return alert(AlertKind.INFORMATION, "validation", "", "alert.validation.title", "", "alert.validation.ok", UiText.get("alert.validation.ok.content"), "", false, buttons(ok()), BUTTON_OK, false);
        return alert(AlertKind.WARNING, "validation", "", "alert.validation.title", "", "alert.validation.found", UiText.get("alert.validation.content"), String.join("\n", safe), true, buttons(ok()), BUTTON_OK, false, safe.size());
    }
    /** @return результат удаления неиспользуемых корректировок */
    public static AlertSpec cleanup(int removed) { return removed <= 0 ? alert(AlertKind.INFORMATION, "cleanup", "", "alert.cleanup.none", "", "alert.cleanup.none", UiText.get("alert.cleanup.none.content"), "", false, buttons(ok()), BUTTON_OK, false) : alert(AlertKind.INFORMATION, "cleanup", "", "alert.cleanup.done", "", "alert.cleanup.done", UiText.get("alert.undo"), "", false, buttons(ok()), BUTTON_OK, false, removed); }
    /** @return сведения о сборке и папке данных */
    public static AlertSpec about(String displayVersion, ClientProfile profile, String javaVersion, Path cashMemory) { return alert(AlertKind.INFORMATION, "about", "", "alert.about.title", "", "alert.about.header", UiText.get("alert.about.content", displayVersion, profile.clientTitle(), javaVersion, cashMemory), "", false, buttons(ok()), BUTTON_OK, false); }
    /** @return список сочетаний клавиш */
    public static AlertSpec hotkeys(String hotkeysText) { return alert(AlertKind.INFORMATION, "hotkeys", "", "alert.hotkeys.title", "", "alert.hotkeys.header", UiText.get("alert.hotkeys.content"), safe(hotkeysText), true, buttons(ok()), BUTTON_OK, false); }
    /** @return справка о формате файла */
    public static AlertSpec fileFormat(String userGuide) { return alert(AlertKind.INFORMATION, "fileFormat", "", "alert.format.title", "", "alert.format.header", UiText.get("alert.format.content"), safe(userGuide), true, buttons(ok()), BUTTON_OK, false); }
    /** @return сообщение о выбранной папке планов */
    public static AlertSpec cashMemoryFolder(Path cashMemory, Path otherFolderOrNull) { String content = otherFolderOrNull == null ? UiText.get("alert.folder.content", cashMemory) : UiText.get("alert.folder.other", otherFolderOrNull); return alert(AlertKind.INFORMATION, "cashMemoryFolder", "", "alert.folder.title", "", "alert.folder.header", content, "", false, buttons(button("otherFolder", "button.otherFolder", ButtonRole.OTHER), button("backToCashMemory", "button.backToCashMemory", ButtonRole.OTHER), button("close", "button.close", ButtonRole.CANCEL)), "close", false, cashMemory); }
    /** @return подтверждение актуализации плана */
    public static AlertSpec actualize(LocalDate today, Money balance, String currency) { return alert(AlertKind.CONFIRMATION, PURPOSE_ACTUALIZE, "", "alert.actualize.title", "", "alert.actualize.header", UiText.get("alert.actualize.content", balance.format(currency)), "", false, buttons(button("actualize", "button.actualize", ButtonRole.OK), cancel()), "actualize", true, UiFormats.date(today)); }
    /** @return подтверждение переноса режима что-если в план */
    public static AlertSpec applyWhatIf(List<String> parts) { String value = parts == null || parts.isEmpty() ? "" : String.join("\n", parts); return alert(AlertKind.CONFIRMATION, PURPOSE_APPLY_WHAT_IF, "", "alert.whatIf.title", "", "alert.whatIf.header", UiText.get("alert.whatIf.content", value), "", false, buttons(button("apply", "button.apply", ButtonRole.OK), cancel()), "apply", true); }
    /** @return просмотр последнего снимка */
    public static AlertSpec lastSnapshot(List<String> contentLines, String details) { String content = contentLines == null || contentLines.isEmpty() ? UiText.get("alert.snapshot.empty") : String.join("\n", contentLines); return alert(AlertKind.INFORMATION, "lastSnapshot", "", "alert.snapshot.title", "", "alert.snapshot.header", content, safe(details), true, 760, buttons(ok()), BUTTON_OK, false); }
    /** @return подтверждение очистки снимков */
    public static AlertSpec clearSnapshots() { return alert(AlertKind.CONFIRMATION, PURPOSE_CLEAR_SNAPSHOTS, "", "alert.clearSnapshots.title", "", "alert.clearSnapshots.header", UiText.get("alert.clearSnapshots.content"), "", false, buttons(button("clear", "button.clear", ButtonRole.OK), cancel()), "clear", true); }
    /** @return предупреждение перед учебным аварийным завершением */
    public static AlertSpec simulateHalt(ClientProfile profile) { String key = profile.kind().name().equals("WEB") ? "alert.halt.web" : "alert.halt.header"; return alert(AlertKind.WARNING, "simulateHalt", "", "alert.halt.title", "", key, "", "", false, buttons(button("halt", "button.halt", ButtonRole.OK), cancel()), "halt", false); }
    /** @return сообщение о втором экземпляре приложения */
    public static AlertSpec alreadyRunning(ClientProfile profile) { List<AlertButton> values = profile.kind().name().equals("WEB") ? buttons(button("continue", "button.continue", ButtonRole.OK)) : buttons(button("openWithoutRestore", "button.openWithoutRestore", ButtonRole.OK), button("exit", "button.exit", ButtonRole.CANCEL)); return alert(AlertKind.WARNING, "alreadyRunning", "", "alert.alreadyRunning.title", "", "alert.alreadyRunning.header", UiText.get("alert.alreadyRunning.content"), "", false, values, values.getFirst().id(), false); }
    /** @return сообщение об ошибке до открытия главного окна */
    public static AlertSpec startupError(Throwable error) { return alert(AlertKind.ERROR, "startupError", "", "alert.startup.title", "", "alert.startup.header", message(error), stack(error), false, buttons(ok()), BUTTON_OK, false); }
    /** @return выбор снимка после аварийного завершения */
    public static AlertSpec crashRecovery(CrashDetector.Detection detection, SessionSnapshot preview, String defaultStore, ClientProfile profile) {
        List<AlertButton> values = new ArrayList<>();
        for (Map.Entry<String, CrashDetector.StoreInfo> entry : detection.stores().entrySet()) { if (!profile.kind().name().equals("WEB") || entry.getKey().equals("server")) values.add(restoreButton(entry.getKey(), entry.getValue())); }
        values.add(button(BUTTON_NO_RESTORE, "button.noRestore", ButtonRole.CANCEL));
        String previewText = preview == null ? "" : UiText.get("restore.preview", preview.client(), preview.windows().size());
        String defaultId = values.stream().filter(b -> b.id().equals(defaultStore) && b.enabled()).findFirst().orElseGet(() -> values.stream().filter(AlertButton::enabled).findFirst().orElse(values.getLast())).id();
        return alert(AlertKind.WARNING, "crashRecovery", "", "alert.recovery.title", "⟲", "alert.recovery.header", UiText.get("alert.recovery.content") + (previewText.isBlank() ? "" : "\n" + previewText), "", false, 720, values, defaultId, false);
    }
    /** @return отчёт об открытых после восстановления окнах */
    public static AlertSpec restoreReport(int windowsRestored, List<String> warnings) { List<String> safe = warnings == null ? List.of() : List.copyOf(warnings); return alert(safe.isEmpty() ? AlertKind.INFORMATION : AlertKind.WARNING, "restoreReport", "", "alert.restore.title", "", "alert.restore.header", safe.isEmpty() ? "" : String.join("\n", safe), String.join("\n", safe), !safe.isEmpty(), buttons(ok()), BUTTON_OK, false, windowsRestored); }
    /** @return предложение сохранить несохранённый план снимка */
    public static AlertSpec recorderNotStarted(String planMarkdown) { return alert(AlertKind.WARNING, "recorderNotStarted", "", "alert.recorder.title", "", "alert.recorder.header", UiText.get("alert.recorder.content"), safe(planMarkdown), false, buttons(button("saveSnapshotPlan", "button.saveSnapshotPlan", ButtonRole.OK), button("skip", "button.skip", ButtonRole.CANCEL)), "saveSnapshotPlan", false); }
    /** @return сообщение о неперехваченной ошибке */
    public static AlertSpec uncaught(Throwable error, boolean jsError) { String title = jsError ? "alert.info.title" : "alert.uncaught.title"; String header = jsError ? "alert.uncaught.js" : "alert.uncaught.header"; List<AlertButton> values = jsError ? buttons(button("reloadPage", "button.reloadPage", ButtonRole.OK), button("continueWork", "button.continueWork", ButtonRole.CANCEL)) : buttons(button("closeProgram", "button.closeProgram", ButtonRole.OK)); return alert(AlertKind.ERROR, "uncaught", "", title, "", header, message(error), stack(error), false, values, values.getFirst().id(), false); }
    /** @return информационное сообщение по ключу */
    public static AlertSpec info(String key, Object... args) { String prefix = "info." + safe(key); return new AlertSpec(AlertKind.INFORMATION, prefix, "", UiText.get("alert.info.title"), "", UiText.has(prefix) ? UiText.get(prefix, args) : UiText.get("err.generic"), UiText.has(prefix + ".content") ? UiText.get(prefix + ".content", args) : "", "", false, 460, buttons(ok()), BUTTON_OK, false); }
    /** @return ошибка недоступной быстрой правки */
    public static AlertSpec quickEditUnavailable(String missingRowOrEmpty) { boolean missing = missingRowOrEmpty != null && !missingRowOrEmpty.isBlank(); return new AlertSpec(AlertKind.ERROR, "quickEdit", "", UiText.get("alert.quickEdit.title"), "", UiText.get("alert.quickEdit.header"), missing ? UiText.get("alert.quickEdit.notInTable", missingRowOrEmpty) : UiText.get("alert.quickEdit.content"), "", false, 460, buttons(ok()), BUTTON_OK, false); }
    /** @return типовая ошибка по ключу */
    public static AlertSpec error(String key, Throwable error, Object... args) { String prefix = "err." + safe(key); String content = error == null && UiText.has(prefix + ".content") ? UiText.get(prefix + ".content", args) : message(error); return new AlertSpec(AlertKind.ERROR, prefix, "", UiText.get("alert.uncaught.title"), "", UiText.has(prefix) ? UiText.get(prefix, args) : UiText.get("err.generic"), content, stack(error), false, 460, buttons(ok()), BUTTON_OK, false); }

    private static AlertSpec alert(AlertKind kind, String purpose, String target, String titleKey, String glyph, String headerKey, String content, String details, boolean expanded, List<AlertButton> buttons, String defaultButton, boolean restorable, Object... args) { return alert(kind, purpose, target, titleKey, glyph, headerKey, content, details, expanded, 460, buttons, defaultButton, restorable, args); }
    private static AlertSpec alert(AlertKind kind, String purpose, String target, String titleKey, String glyph, String headerKey, String content, String details, boolean expanded, int width, List<AlertButton> buttons, String defaultButton, boolean restorable, Object... args) { return new AlertSpec(kind, purpose, target, UiText.get(titleKey), glyph, UiText.get(headerKey, args), content, details, expanded, width, buttons, defaultButton, restorable); }
    private static AlertButton button(String id, String textKey, ButtonRole role) { return new AlertButton(id, UiText.get(textKey), role, true, ""); }
    private static AlertButton ok() { return button(BUTTON_OK, "button.ok", ButtonRole.OK); }
    private static AlertButton cancel() { return button(BUTTON_CANCEL, "button.cancel", ButtonRole.CANCEL); }
    private static AlertButton save() { return button(BUTTON_SAVE, "button.save", ButtonRole.OK); }
    private static AlertButton overwrite() { return button(BUTTON_OVERWRITE, "button.overwrite", ButtonRole.OK); }
    private static AlertButton delete() { return button("delete", "button.delete", ButtonRole.OK); }
    private static List<AlertButton> buttons(AlertButton... values) { return List.of(values); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String message(Throwable error) { return error == null ? "" : (error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage()); }
    private static String stack(Throwable error) { if (error == null) return ""; StringWriter out = new StringWriter(); error.printStackTrace(new PrintWriter(out)); return out.toString(); }
    private static String diagnosticDetails(List<Diagnostic> values) { return values == null ? "" : values.stream().filter(d -> d.severity() != Severity.INFO).map(Diagnostic::format).reduce((a, b) -> a + "\n" + b).orElse(""); }
    private static AlertButton restoreButton(String id, CrashDetector.StoreInfo info) { String base = switch (id) { case "registry" -> "button.restoreRegistry"; case "xml" -> "button.restoreXml"; default -> "button.restoreServer"; }; String key = info.restorable() ? base : base + ".none"; String time = info.snapshotAt().map(value -> UiFormats.time(value.atZone(ZoneId.systemDefault()).toLocalTime())).orElse(""); return new AlertButton(id, UiText.get(key, time), ButtonRole.OTHER, info.restorable(), info.problem()); }
}
