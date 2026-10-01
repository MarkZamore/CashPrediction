package ru.cashprediction.parity.driver;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;
import ru.cashprediction.core.ui.selftest.UiDriver;

/** Строгий адаптер настоящего пути событий клиента; неподдерживаемые команды никогда не подтверждаются. */
public final class UiTestDriver implements UiDriver {
    /** Порт рендерера S3: операции выполняются через виджеты, дамп читается из виджетов. */
    public interface TestApi {
        /** @return точные типы реализованных команд */
        Set<Class<? extends SelfTestCommand>> supported();
        /** Выполняет команду через события виджетов; ошибка должна выйти наружу. */
        void execute(SelfTestCommand command) throws Exception;
        /** Ждёт обработку событий и отложенных изменений до заданного срока. */
        void awaitIdle(Duration timeout) throws Exception;
        /** @return ненормализованный дамп реальных виджетов */
        UiDump dump(String step);
        /** @return настоящий PNG окна */
        byte[] screenshot(String step) throws IOException;
    }

    private final ClientKind client;
    private final TestApi api;

    /** Создаёт адаптер одного клиента с явно объявленными возможностями. */
    public UiTestDriver(ClientKind client, TestApi api) {
        this.client = Objects.requireNonNull(client);
        this.api = Objects.requireNonNull(api);
    }

    /** @return вид клиента */
    @Override public ClientKind client() { return client; }

    /** Выполняет только объявленную операцию; команды раннера тоже не принимаются молча. */
    @Override public void execute(SelfTestCommand command) throws Exception {
        Objects.requireNonNull(command);
        if (!api.supported().contains(command.getClass())) {
            throw new UnsupportedOperationException("Unsupported widget operation: " + command.getClass().getSimpleName());
        }
        api.execute(command);
    }

    /** Ждёт фактическое завершение очереди клиента. */
    @Override public void awaitIdle(Duration timeout) throws Exception {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout");
        api.awaitIdle(timeout);
    }

    /** Проверяет происхождение и версию дампа перед передачей раннеру. */
    @Override public UiDump dump(String step) {
        UiDump dump = Objects.requireNonNull(api.dump(step), "widget dump");
        if (dump.schema() != UiDump.SCHEMA || !dump.client().equals(client.name().toLowerCase(java.util.Locale.ROOT))
                || !step.equals(dump.step())) throw new IllegalStateException("Invalid widget dump identity");
        return dump;
    }

    /** Проверяет PNG, чтобы пустой ответ тестового API не считался снимком. */
    @Override public byte[] screenshot(String step) throws IOException {
        byte[] png = api.screenshot(step);
        if (png == null) throw new IOException("Missing screenshot PNG");
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        if (image == null || image.getWidth() != 1200 || image.getHeight() != 800)
            throw new IOException("Screenshot must be a complete 1200x800 PNG");
        try { ru.cashprediction.parity.browser.PngHeader.read(png); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid screenshot PNG", e); }
        return png;
    }
}
