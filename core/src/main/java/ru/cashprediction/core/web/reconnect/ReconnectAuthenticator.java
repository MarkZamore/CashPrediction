package ru.cashprediction.core.web.reconnect;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Взаимная HMAC-проверка сервера и вкладки с ограниченным одноразовым журналом запросов. */
public final class ReconnectAuthenticator {
    private static final int LIMIT = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ReconnectCredential credential;
    private final String apiToken;
    private final Clock clock;
    private final String generation = randomHex(16);
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    /** Создаёт независимое поколение сервера; часы позволяют проверять срок действия в тестах. */
    public ReconnectAuthenticator(ReconnectCredential credential, String apiToken, Clock clock) {
        if (credential == null || apiToken == null || apiToken.isBlank() || clock == null) throw invalid();
        this.credential = credential;
        this.apiToken = apiToken;
        this.clock = clock;
    }

    /** Возвращает секретные метаданные только для уже аутентифицированного bootstrap. */
    public Map<String, Object> bootstrapMetadata() {
        return Map.of("version", 1, "installationId", credential.id(), "key", credential.keyHex());
    }

    /** Создаёт одноразовый запрос и доказательство сервера, не передавая долговременный ключ. */
    public synchronized Map<String, Object> challenge(String origin, Map<String, Object> request) {
        validateOrigin(origin);
        exact(request, Set.of("installationId", "clientNonce"));
        String installation = hex(request, "installationId", 32);
        String clientNonce = hex(request, "clientNonce", 64);
        if (!constant(credential.id(), installation)) throw invalid();
        Instant now = clock.instant();
        pending.values().removeIf(value -> !now.isBefore(value.expires()) || now.isBefore(value.created()));
        if (pending.size() >= LIMIT) throw invalid();
        String id;
        do { id = randomHex(16); } while (pending.containsKey(id));
        Pending value = new Pending(origin, clientNonce, randomHex(32), id, now, now.plusSeconds(30));
        pending.put(id, value);
        return Map.of("version", 1, "installationId", credential.id(), "serverGeneration", generation,
                "clientNonce", clientNonce, "serverNonce", value.serverNonce(), "challengeId", id,
                "serverProof", proof("server", value));
    }

    /** Атомарно расходует запрос до проверки доказательства: повтор и подбор не возвращают токен. */
    public synchronized Map<String, Object> complete(String origin, Map<String, Object> request) {
        validateOrigin(origin);
        exact(request, Set.of("challengeId", "clientProof"));
        String id = hex(request, "challengeId", 32);
        String given = hex(request, "clientProof", 64);
        Pending value = pending.remove(id);
        Instant now = clock.instant();
        if (value == null || !value.origin().equals(origin) || !now.isBefore(value.expires())
                || now.isBefore(value.created()) || !constant(proof("client", value), given)) throw invalid();
        return Map.of("token", apiToken, "serverGeneration", generation);
    }

    private String proof(String role, Pending value) {
        String canonical = "cashprediction-web-reconnect-v1\n" + role + "\n" + value.origin() + "\n"
                + credential.id() + "\n" + generation + "\n" + value.clientNonce() + "\n"
                + value.serverNonce() + "\n" + value.id();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(credential.keyHex()), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            // Отсутствие обязательного алгоритма JDK не раскрывает материал ключа.
            throw new IllegalStateException("reconnect cryptography unavailable");
        }
    }

    private static void validateOrigin(String origin) {
        if (origin == null || !origin.matches("http://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[1-9][0-9]{0,4}"))
            throw invalid();
        int port = Integer.parseInt(origin.substring(origin.lastIndexOf(':') + 1));
        if (port > 65535) throw invalid();
    }

    private static void exact(Map<String, Object> request, Set<String> keys) {
        if (request == null || !request.keySet().equals(keys)) throw invalid();
    }

    private static String hex(Map<String, Object> request, String field, int length) {
        Object value = request.get(field);
        if (!(value instanceof String text) || text.length() != length || !text.matches("[0-9a-f]+"))
            throw invalid();
        return text;
    }

    private static boolean constant(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes]; RANDOM.nextBytes(value); return HexFormat.of().formatHex(value);
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid reconnect request"); }

    /** Секретов нет в журнале запросов; запись не должна попадать в диагностику. */
    private record Pending(String origin, String clientNonce, String serverNonce, String id, Instant created, Instant expires) { }
}
