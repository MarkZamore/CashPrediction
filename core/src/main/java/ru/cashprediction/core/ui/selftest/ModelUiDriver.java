package ru.cashprediction.core.ui.selftest;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.ui.dump.UiDump;

/**
 * Драйвер без интерфейса: выполняет сценарий над {@code AppController} и внутренним записывающим портом и строит
 * дамп из моделей (архитектура §6.2). Так генерируются эталоны {@code core/src/test/resources/ui-golden/<сценарий>/
 * <шаг>.json} ({@code UiGoldenTest}, обновление {@code -Dcashprediction.golden.update=true}).
 *
 * <p><b>Поведение (этап S2):</b> порт работает в потоке теста (прямой {@code UiExecutor}, планировщик с виртуальным
 * временем, который {@link #awaitIdle(Duration)} прокручивает); меню, тулбар и контекстные меню «нажимаются» через
 * {@code UiIntents.command} с источником соответствующего вида; поля форм — через {@code FormSession.fieldChanged};
 * {@code answer} отвечает на верхнее сообщение по тексту кнопки; {@code chooser} задаёт ответ следующему выбору; дамп
 * строится из {@code MainScreenModel}, открытых {@code FormView}/{@code AlertSpec} и записанных запросов выбора; границ
 * областей нет (эталон сравнивает их только попарно между клиентами); {@code screenshot} не поддерживается.</p>
 *
 * <p>Не потокобезопасен.</p>
 */
public final class ModelUiDriver implements UiDriver {

    private final AppEnvironment environment;
    private final ClientProfile profile;

    /**
     * Создаёт драйвер.
     *
     * @param environment окружение (изолированный {@code --home}, {@code --registry memory}, {@code --today})
     * @param profile     профиль клиента, чьи различия §10 воспроизводятся (для эталонов — FX)
     */
    public ModelUiDriver(AppEnvironment environment, ClientProfile profile) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    /** @return окружение */
    public AppEnvironment environment() {
        return environment;
    }

    /** @return профиль клиента */
    public ClientProfile profile() {
        return profile;
    }

    @Override
    public ClientKind client() {
        return profile.kind();
    }

    @Override
    public void execute(SelfTestCommand command) {
        throw new UnsupportedOperationException("S2: core-protocol-dump - ModelUiDriver.execute");
    }

    @Override
    public void awaitIdle(Duration timeout) {
        throw new UnsupportedOperationException("S2: core-protocol-dump - ModelUiDriver.awaitIdle");
    }

    @Override
    public UiDump dump(String step) {
        throw new UnsupportedOperationException("S2: core-protocol-dump - ModelUiDriver.dump");
    }

    @Override
    public byte[] screenshot(String step) throws IOException {
        throw new IOException("ModelUiDriver has no screen");
    }
}
