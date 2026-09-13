package ru.cashprediction.web;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Выбор порта web-сервера: штатный {@value #DEFAULT_PORT}, а если он занят — любой свободный.
 *
 * <p>Порт не «проверяется заранее», а сразу занимается {@link HttpServer}: между проверкой и занятием порт
 * мог бы перехватить другой процесс. Если штатный порт занят, сервер создаётся на порту 0, и операционная
 * система сама выдаёт свободный.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class PortFinder {

    /** Штатный порт (раздел 2 плана). */
    public static final int DEFAULT_PORT = 8765;

    /** Системное свойство, принудительно задающее порт ({@code 0} — любой свободный). */
    public static final String PORT_PROPERTY = "cashprediction.web.port";

    private PortFinder() {
    }

    /**
     * Создаёт HTTP-сервер на адресе обратной петли.
     *
     * @param host      адрес (обычно 127.0.0.1)
     * @param preferred желаемый порт; 0 — любой свободный
     * @param strict    {@code true} — только этот порт (задан явно свойством), без запасного варианта
     * @param log       журнал сервера
     * @return созданный, но ещё не запущенный сервер
     * @throws IOException если порт занять не удалось
     */
    public static HttpServer bind(InetAddress host, int preferred, boolean strict, ServerLog log) throws IOException {
        try {
            return HttpServer.create(new InetSocketAddress(host, preferred), 0);
        } catch (BindException e) {
            if (strict || preferred == 0) {
                throw new BindException("Порт " + preferred + " занят: " + e.getMessage());
            }
            log.info("Порт " + preferred + " занят другим процессом, выбирается свободный порт");
            return HttpServer.create(new InetSocketAddress(host, 0), 0);
        }
    }

    /**
     * Порт из системного свойства {@value #PORT_PROPERTY}.
     *
     * @return порт или {@code -1}, если свойство не задано
     * @throws IllegalArgumentException если значение не число 0..65535
     */
    public static int configuredPort() {
        String text = System.getProperty(PORT_PROPERTY);
        if (text == null || text.isBlank()) {
            return -1;
        }
        try {
            int port = Integer.parseInt(text.strip());
            if (port < 0 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Некорректный порт в свойстве " + PORT_PROPERTY + ": «" + text + "»");
        }
    }

    /**
     * Свободен ли порт на адресе обратной петли прямо сейчас (для тестов и диагностики).
     *
     * @param port порт
     * @return {@code true}, если порт удалось занять и сразу освободить
     */
    public static boolean isFree(int port) {
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort() == port;
        } catch (IOException e) {
            return false;
        }
    }
}
