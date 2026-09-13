package ru.cashprediction.swing.dialog;

import java.awt.GridLayout;
import java.awt.Window;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.session.CrashDetector;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionStore;
import ru.cashprediction.core.session.SnapshotSchema;
import ru.cashprediction.core.session.store.RegistrySessionStore;
import ru.cashprediction.core.session.store.XmlSessionStore;

/**
 * Диалог 17 «Восстановление после сбоя»: показывается до главного окна, если прошлый сеанс оборвался.
 *
 * <p>Три кнопки: «Из реестра Windows (сохранено ЧЧ:ММ:СС)», «Из XML-файла (сохранено ЧЧ:ММ:СС)»,
 * «Не восстанавливать». Кнопка хранилища, из которого восстановиться нельзя (недоступно, нет снимка, снимок
 * повреждён), отключена, а причина видна в подсказке. Кнопка по умолчанию — хранилище из настройки
 * «Хранилище по умолчанию» ({@code AppSettings.recoveryStore}), если оно пригодно.</p>
 *
 * <p>Окно само не восстанавливается (его нет в словаре окон) и не связано с рекордером: запись сессии ещё не
 * начата. Крестик окна и Esc равносильны «Не восстанавливать».</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: Dialog<RecoveryChoice> + ButtonType("Из реестра"/"Из XML"/"Не восстанавливать") → Swing: SwingDialog<Choice> + SwingButtonType → Web: баннер «Восстановить с сервера / Начать заново»
public final class SwingRecoveryDialog extends SwingDialog<SwingRecoveryDialog.Choice> {

    /** «Не восстанавливать». */
    public static final SwingButtonType DO_NOT_RESTORE = new SwingButtonType("Не восстанавливать", SwingButtonType.Role.CANCEL_CLOSE);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * Выбор пользователя.
     *
     * @param store хранилище, из которого восстановиться; {@code null} — «Не восстанавливать»
     */
    public record Choice(SessionStore store) {

        /**
         * Выбрано ли восстановление.
         *
         * @return {@code true}, если выбрано хранилище
         */
        public boolean restore() {
            return store != null;
        }
    }

    /** Тип кнопки хранилища → хранилище и сведения о нём. */
    private final Map<SwingButtonType, SessionStore> storeButtons = new LinkedHashMap<>();
    private final Map<SwingButtonType, CrashDetector.StoreInfo> infos = new LinkedHashMap<>();

    /**
     * Создаёт диалог.
     *
     * @param owner       владелец; обычно {@code null} — главного окна ещё нет
     * @param detection   результат {@link CrashDetector#detect}
     * @param stores      хранилища клиента (реестр и XML)
     * @param defaultKind хранилище по умолчанию из настроек
     */
    public SwingRecoveryDialog(Window owner, CrashDetector.Detection detection, List<SessionStore> stores,
                               RecoveryStoreKind defaultKind) {
        super(owner, null, null, true, "CashPrediction — восстановление сеанса", null);

        List<SwingButtonType> types = new ArrayList<>();
        JPanel statusLines = new JPanel(new GridLayout(0, 1, 0, 2));
        SwingButtonType preferred = null;
        for (SessionStore store : stores) {
            CrashDetector.StoreInfo info = detection.stores().getOrDefault(store.id(),
                    new CrashDetector.StoreInfo(false, Optional.empty(), "нет сведений о хранилище"));
            String prefix = store instanceof RegistrySessionStore ? "Из реестра Windows"
                    : store instanceof XmlSessionStore ? "Из XML-файла" : "Из хранилища «" + store.title() + "»";
            // Повреждённый или недоступный снимок — «недоступно» (причина в строке состояния и подсказке), а не «снимка нет».
            String saved = info.restorable() && info.snapshotAt().isPresent()
                    ? " (сохранено " + TIME.format(info.snapshotAt().get()) + ")"
                    : info.available() && info.problem().isBlank() ? " (снимка нет)" : " (недоступно)";
            SwingButtonType type = new SwingButtonType(prefix + saved, SwingButtonType.Role.OK_DONE);
            types.add(type);
            storeButtons.put(type, store);
            infos.put(type, info);
            statusLines.add(new JLabel(store.title() + ": " + describe(store, info)));
            boolean isDefault = (defaultKind == RecoveryStoreKind.XML) == (store instanceof XmlSessionStore);
            if (isDefault && info.restorable()) {
                preferred = type;
            }
        }
        types.add(DO_NOT_RESTORE);

        pane().setHeaderText(headerText(detection.findMarker()));
        pane().setContent(statusLines);
        setResultConverter(button -> new Choice(storeButtons.get(button)));
        setButtonTypes(types.toArray(SwingButtonType[]::new));

        // Подсказки: почему кнопка отключена или от какого момента снимок.
        for (Map.Entry<SwingButtonType, CrashDetector.StoreInfo> entry : infos.entrySet()) {
            JButton button = pane().lookupButton(entry.getKey());
            // JavaFX: Tooltip → Swing: setToolTipText → Web: title
            button.setToolTipText(entry.getValue().restorable()
                    ? "Восстановить окна и введённые данные из снимка от " + FULL.format(entry.getValue().snapshotAt().orElseThrow())
                    : "Недоступно: " + describe(storeButtons.get(entry.getKey()), entry.getValue()));
        }
        if (preferred == null) {
            preferred = infos.entrySet().stream().filter(e -> e.getValue().restorable()).map(Map.Entry::getKey)
                    .findFirst().orElse(DO_NOT_RESTORE);
        }
        window().getRootPane().setDefaultButton(pane().lookupButton(preferred));
        setInitialFocus(pane().lookupButton(preferred));
    }

    private static String headerText(Optional<SessionMarker> marker) {
        StringBuilder text = new StringBuilder("Предыдущий сеанс CashPrediction завершился аварийно.");
        marker.ifPresent(m -> {
            text.append(" Начат: ").append(DATE_TIME.format(m.startedAt()));
            if (m.client() != null && !m.client().isBlank()) {
                text.append(", клиент: ").append(SnapshotSchema.clientTitle(m.client()));
            }
            text.append('.');
        });
        return text.append("\nВосстановить открытые окна и введённые данные?").toString();
    }

    private static String describe(SessionStore store, CrashDetector.StoreInfo info) {
        if (info.restorable()) {
            Instant at = info.snapshotAt().orElseThrow();
            return "снимок от " + FULL.format(at);
        }
        if (!info.available()) {
            String reason = !info.problem().isBlank() ? info.problem() : store.unavailableReason();
            return "недоступно" + (reason == null || reason.isBlank() ? "" : " — " + reason);
        }
        if (!info.problem().isBlank()) {
            return info.problem();
        }
        return "снимка нет";
    }

    /**
     * Программно выбирает восстановление из хранилища (автоответ самотеста).
     *
     * @param storeId идентификатор хранилища: {@code registry} или {@code xml}
     * @return {@code null}, если выбор сделан; иначе причина (нет такого хранилища или из него восстановить нельзя)
     */
    public String chooseStore(String storeId) {
        for (Map.Entry<SwingButtonType, SessionStore> entry : storeButtons.entrySet()) {
            if (entry.getValue().id().equals(storeId)) {
                CrashDetector.StoreInfo info = infos.get(entry.getKey());
                if (info == null || !info.restorable()) {
                    return "из хранилища «" + entry.getValue().title() + "» восстановить нельзя: "
                            + describe(entry.getValue(), info == null
                            ? new CrashDetector.StoreInfo(false, Optional.empty(), "нет сведений") : info);
                }
                handleButton(entry.getKey());
                return null;
            }
        }
        return "нет хранилища «" + storeId + "»";
    }

    /**
     * Программно выбирает «Не восстанавливать» (автоответ самотеста).
     */
    public void chooseDoNotRestore() {
        handleButton(DO_NOT_RESTORE);
    }

    /** {@inheritDoc} */
    @Override
    protected boolean isButtonEnabled(SwingButtonType button, String error) {
        CrashDetector.StoreInfo info = infos.get(button);
        return info == null || info.restorable();
    }
}
