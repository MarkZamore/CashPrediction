package ru.cashprediction.fx.dialog;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.session.CrashDetector;
import ru.cashprediction.core.session.CrashDetector.StoreInfo;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SnapshotSchema;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/**
 * Диалог 17 «Восстановление»: показывается при старте, до главного окна, если прошлый сеанс оборвался.
 *
 * <pre>
 * Предыдущий сеанс CashPrediction завершился аварийно. Начат: 13.09.2026 10:00, клиент: JavaFX.
 * Восстановить открытые окна и введённые данные?
 * [ Из реестра Windows (сохранено 10:15:30) ]  [ Из XML-файла (сохранено 10:15:31) ]  [ Не восстанавливать ]
 * </pre>
 *
 * <p>Хранилище, из которого восстановиться нельзя (недоступно, снимка нет, снимок повреждён), даёт
 * отключённую кнопку с причиной во всплывающей подсказке. Кнопка по умолчанию (Enter) — хранилище,
 * выбранное в меню «Восстановление → Хранилище по умолчанию», если из него можно восстановиться.</p>
 *
 * <p>Результат — {@link RecoveryChoice}; закрытие крестиком означает «Не восстанавливать».
 * Не восстанавливается сам (его показ предшествует сеансу). Только FX Application Thread.</p>
 */
// JavaFX: Dialog<R> + Alert-подобная панель → Swing: JOptionPane.showOptionDialog (SwingRecoveryDialog) → Web: баннер «Восстановить с сервера / Начать заново»
public final class FxRecoveryDialog extends Dialog<RecoveryChoice> {

    /** Идентификатор хранилища реестра ({@code RegistrySessionStore.id()}). */
    public static final String REGISTRY_STORE_ID = "registry";
    /** Идентификатор XML-хранилища ({@code XmlSessionStore.id()}). */
    public static final String XML_STORE_ID = "xml";

    private static final DateTimeFormatter STARTED = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm");

    /**
     * Создаёт диалог по результату обнаружения сбоя.
     *
     * @param detection результат {@link CrashDetector#detect}
     * @param preferred хранилище по умолчанию из настроек
     */
    public FxRecoveryDialog(CrashDetector.Detection detection, RecoveryStoreKind preferred) {
        StoreInfo registry = info(detection.stores(), REGISTRY_STORE_ID);
        StoreInfo xml = info(detection.stores(), XML_STORE_ID);
        ButtonType registryButton = AppButtonTypes.fromRegistry(registry.snapshotAt());
        ButtonType xmlButton = AppButtonTypes.fromXml(xml.snapshotAt());

        ButtonType defaultButton;
        if (preferred == RecoveryStoreKind.XML && xml.restorable()) {
            defaultButton = xmlButton;
        } else if (registry.restorable()) {
            defaultButton = registryButton;
        } else if (xml.restorable()) {
            defaultButton = xmlButton;
        } else {
            defaultButton = AppButtonTypes.NO_RESTORE;
        }

        RecoveryPane pane = new RecoveryPane(Map.of(registryButton, registry, xmlButton, xml), defaultButton);
        setDialogPane(pane);
        setTitle("Восстановление сеанса");
        pane.setHeaderText("Предыдущий сеанс CashPrediction завершился аварийно." + startedText(detection.findMarker()));
        Label text = new Label("Восстановить открытые окна и введённые данные?\n\n"
                + storeLine("Реестр Windows", registry) + "\n" + storeLine("XML-файл", xml));
        text.setWrapText(true);
        pane.setContent(text);
        // JavaFX: ButtonType → Swing: SwingButtonType (текст + роль) + JOptionPane.showOptionDialog → Web: <button value> → returnValue
        pane.getButtonTypes().setAll(registryButton, xmlButton, AppButtonTypes.NO_RESTORE);
        setResultConverter(button -> {
            if (button == registryButton) {
                return RecoveryChoice.REGISTRY;
            }
            if (button == xmlButton) {
                return RecoveryChoice.XML;
            }
            return RecoveryChoice.NONE;
        });
    }

    private static StoreInfo info(Map<String, StoreInfo> stores, String id) {
        StoreInfo found = stores.get(id);
        return found != null ? found : new StoreInfo(false, Optional.empty(), "хранилище не проверялось");
    }

    private static String startedText(Optional<SessionMarker> marker) {
        return marker.map(m -> "\nНачат: " + STARTED.format(m.startedAt().atZone(ZoneId.systemDefault()))
                + ", клиент: " + SnapshotSchema.clientTitle(m.client()) + ".").orElse("");
    }

    private static String storeLine(String title, StoreInfo info) {
        if (info.restorable()) {
            return "• " + title + ": снимок от " + AppButtonTypes.localTime(info.snapshotAt().orElseThrow());
        }
        return "• " + title + ": восстановить нельзя — " + reason(info);
    }

    private static String reason(StoreInfo info) {
        if (!info.problem().isBlank()) {
            return info.problem();
        }
        if (!info.available()) {
            return "хранилище недоступно";
        }
        return "снимка нет";
    }

    /** Панель, которая отключает кнопки невосстановимых хранилищ и назначает кнопку по умолчанию. */
    private static final class RecoveryPane extends AppDialogPane {

        private final Map<ButtonType, StoreInfo> stores;
        private final ButtonType defaultButton;

        RecoveryPane(Map<ButtonType, StoreInfo> stores, ButtonType defaultButton) {
            super(null, "⟲");
            this.stores = stores;
            this.defaultButton = defaultButton;
            // Три длинные кнопки со временем снимка не должны обрезаться многоточием.
            setPrefWidth(900);
        }

        /**
         * Создаёт кнопку: назначает кнопку по умолчанию, а кнопку невосстановимого хранилища отключает
         * и снабжает подсказкой с причиной.
         *
         * @param buttonType тип кнопки
         * @return узел кнопки (для отключённой — обёртка, получающая события мыши для подсказки)
         */
        @Override
        protected Node createButton(ButtonType buttonType) {
            Node node = super.createButton(buttonType);
            if (!(node instanceof Button button)) {
                return node;
            }
            button.setDefaultButton(buttonType == defaultButton);
            StoreInfo info = stores.get(buttonType);
            if (info == null) {
                return node;
            }
            if (info.restorable()) {
                // JavaFX: Tooltip → Swing: setToolTipText → Web: title / <div class="tooltip">
                button.setTooltip(new Tooltip("Открыть план, главное окно и диалоги в том виде, как они были при сбое"));
                return node;
            }
            button.setDisable(true);
            // Отключённая кнопка не получает событий мыши, и её собственная подсказка не показалась бы.
            // Поэтому подсказка с причиной ставится на обёртку, которая события получает.
            StackPane wrapper = new StackPane(button);
            ButtonBar.setButtonData(wrapper, buttonType.getButtonData());
            // JavaFX: Tooltip → Swing: setToolTipText → Web: title / <div class="tooltip">
            Tooltip.install(wrapper, new Tooltip("Недоступно: " + reason(info)));
            return wrapper;
        }
    }
}
