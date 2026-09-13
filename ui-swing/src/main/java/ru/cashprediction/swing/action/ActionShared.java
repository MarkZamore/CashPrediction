package ru.cashprediction.swing.action;

import java.nio.file.Path;
import java.util.Objects;
import ru.cashprediction.swing.dialog.SwingDialogHost;

/**
 * Общие для всех групп команд объекты: контекст приложения, хост диалогов, помощник сообщений и папка, из которой
 * в этом сеансе открываются планы.
 *
 * <p>Группы команд ({@link FileActions}, {@link EditActions}, {@link ToolActions}, {@link RecoveryActions},
 * {@link HelpActions}) создаёт фасад {@link SwingActions} и передаёт им один экземпляр этого класса: так команды
 * разных меню видят одно и то же состояние (например, «Правка → Параметры плана» переименовывает файл средствами
 * команд «Файл»).</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
final class ActionShared {

    private final SwingAppContext context;
    private final SwingDialogHost host;
    private final Alerts alerts;
    /** Команды меню «Файл» (назначаются фасадом сразу после создания групп). */
    private FileActions files;
    /** Папка, из которой «Открыть…» показывает планы; {@code null} — CashMemory. */
    private Path browseFolder;

    /**
     * Создаёт общие объекты.
     *
     * @param context контекст приложения
     * @param host    хост диалогов
     */
    ActionShared(SwingAppContext context, SwingDialogHost host) {
        this.context = Objects.requireNonNull(context, "context");
        this.host = Objects.requireNonNull(host, "host");
        this.alerts = new Alerts(host);
    }

    /** @return контекст приложения */
    SwingAppContext context() {
        return context;
    }

    /** @return хост диалогов */
    SwingDialogHost host() {
        return host;
    }

    /** @return помощник сообщений */
    Alerts alerts() {
        return alerts;
    }

    /** @return команды меню «Файл» */
    FileActions files() {
        return Objects.requireNonNull(files, "files");
    }

    /**
     * Запоминает группу команд «Файл».
     *
     * @param fileActions команды «Файл»
     */
    void setFiles(FileActions fileActions) {
        files = fileActions;
    }

    /** @return папка, из которой сейчас открываются планы (CashMemory или выбранная в этом сеансе) */
    Path plansFolder() {
        return browseFolder != null ? browseFolder : context.layout().dir();
    }

    /** @return выбрана ли на этот сеанс папка, отличная от CashMemory */
    boolean browsingOtherFolder() {
        return browseFolder != null;
    }

    /**
     * Меняет папку, из которой открываются планы, на время сеанса. Ничего на диск не пишет.
     *
     * @param folder папка или {@code null}, чтобы вернуться к CashMemory
     */
    void setBrowseFolder(Path folder) {
        browseFolder = folder == null || folder.equals(context.layout().dir()) ? null : folder;
    }
}
