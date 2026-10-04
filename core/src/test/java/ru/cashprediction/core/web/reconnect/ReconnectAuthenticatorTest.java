package ru.cashprediction.core.web.reconnect;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Независимая проверка канонической подписи, времени и атомарного расходования запроса. */
class ReconnectAuthenticatorTest {
    private static final String ORIGIN = "http://127.0.0.1:8765";
    private static final ReconnectCredential KEY = new ReconnectCredential("12".repeat(16), "ab".repeat(32));
    private final MutableClock clock = new MutableClock();
    private final ReconnectAuthenticator auth = new ReconnectAuthenticator(KEY, "api-secret", clock);

    @Test void metadataAndRedaction() {
        assertEquals(Map.of("version", 1, "installationId", KEY.id(), "key", KEY.keyHex()), auth.bootstrapMetadata());
        assertFalse(KEY.toString().contains(KEY.keyHex()));
        assertThrows(IllegalArgumentException.class, () -> new ReconnectCredential("bad", KEY.keyHex()));
    }

    @Test void proofMatchesIndependentCanonicalImplementationAndIsOneUse() throws Exception {
        Map<String, Object> c = challenge();
        assertEquals(Set.of("version", "installationId", "serverGeneration", "clientNonce", "serverNonce", "challengeId", "serverProof"), c.keySet());
        assertEquals(proof(c, "server", ORIGIN), c.get("serverProof"));
        for (String field : List.of("installationId", "serverGeneration", "challengeId")) assertTrue(((String)c.get(field)).matches("[0-9a-f]{32}"));
        for (String field : List.of("clientNonce", "serverNonce", "serverProof")) assertTrue(((String)c.get(field)).matches("[0-9a-f]{64}"));
        assertFalse(c.values().contains(KEY.keyHex()));
        Map<String, Object> request = completion(c, "client", ORIGIN);
        assertEquals(Map.of("token", "api-secret", "serverGeneration", c.get("serverGeneration")), auth.complete(ORIGIN, request));
        invalid(() -> auth.complete(ORIGIN, request));
    }

    @Test void roleOriginGenerationAndTamperedProofAreRejectedAndConsumed() throws Exception {
        for (String role : List.of("server", "client")) {
            var c = challenge();
            var request = completion(c, role, role.equals("client") ? "http://localhost:8765" : ORIGIN);
            invalid(() -> auth.complete(ORIGIN, request));
            invalid(() -> auth.complete(ORIGIN, completion(c, "client", ORIGIN)));
        }
        var c = new LinkedHashMap<>(challenge());
        c.put("serverGeneration", "00".repeat(16));
        invalid(() -> auth.complete(ORIGIN, completion(c, "client", ORIGIN)));
        var next = challenge();
        invalid(() -> auth.complete(ORIGIN, Map.of("challengeId", next.get("challengeId"), "clientProof", "00".repeat(32))));
        var another = challenge();
        invalid(() -> auth.complete("http://localhost:8765", completion(another, "client", ORIGIN)));
    }

    @Test void ttlIsStrictAndBackwardsClockFailsClosed() throws Exception {
        var c = challenge(); clock.now = clock.now.plusSeconds(30);
        invalid(() -> auth.complete(ORIGIN, completion(c, "client", ORIGIN)));
        var saved = challenge(); clock.now = clock.now.minusSeconds(1);
        invalid(() -> auth.complete(ORIGIN, completion(saved, "client", ORIGIN)));
        var fresh = challenge(); clock.now = clock.now.plusSeconds(29);
        assertEquals("api-secret", auth.complete(ORIGIN, completion(fresh, "client", ORIGIN)).get("token"));
    }

    @Test void mapBoundAndInputValidation() {
        for (int i = 0; i < 256; i++) challenge();
        invalid(this::challenge);
        clock.now = clock.now.plusSeconds(30); assertNotNull(challenge());
        for (Map<String, Object> input : List.<Map<String, Object>>of(Map.of(),
                Map.of("installationId", KEY.id(), "clientNonce", "a".repeat(10000)),
                Map.of("installationId", KEY.id(), "clientNonce", "AB".repeat(32)),
                Map.of("installationId", "00".repeat(16), "clientNonce", "aa".repeat(32)),
                Map.of("installationId", KEY.id(), "clientNonce", "aa".repeat(32), "extra", true)))
            invalid(() -> auth.challenge(ORIGIN, input));
        for (String origin : List.of("https://127.0.0.1:8765", ORIGIN + "\n", "http://127.0.0.1:65536", "http://evil:8765", "http://127.0.0.1:08765"))
            invalid(() -> auth.challenge(origin, Map.of("installationId", KEY.id(), "clientNonce", "aa".repeat(32))));
    }

    @Test void concurrentCompletionReturnsTokenOnce() throws Exception {
        var c = challenge(); var request = completion(c, "client", ORIGIN);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 16; i++) jobs.add(() -> { try { auth.complete(ORIGIN, request); return true; } catch (IllegalArgumentException rejected) { return false; } });
            int accepted = 0;
            for (var result : pool.invokeAll(jobs)) if (result.get()) accepted++;
            assertEquals(1, accepted);
        }
    }

    @Test void challengeCannotBeUsedInAnotherServerGeneration() throws Exception {
        var c = challenge();
        var other = new ReconnectAuthenticator(KEY, "other-api", clock);
        invalid(() -> other.complete(ORIGIN, completion(c, "client", ORIGIN)));
        assertEquals("api-secret", auth.complete(ORIGIN, completion(c, "client", ORIGIN)).get("token"));
    }

    @Test void duplicateClientNonceStillProducesIndependentChallenges() throws Exception {
        var first = challenge(); var second = challenge();
        assertNotEquals(first.get("challengeId"), second.get("challengeId"));
        assertNotEquals(first.get("serverNonce"), second.get("serverNonce"));
        invalid(() -> auth.complete(ORIGIN, Map.of("challengeId", first.get("challengeId"),
                "clientProof", proof(second, "client", ORIGIN))));
        assertEquals("api-secret", auth.complete(ORIGIN, completion(second, "client", ORIGIN)).get("token"));
    }

    private Map<String, Object> challenge() { return auth.challenge(ORIGIN, Map.of("installationId", KEY.id(), "clientNonce", "34".repeat(32))); }
    private static Map<String, Object> completion(Map<String, Object> c, String role, String origin) throws Exception {
        return Map.of("challengeId", c.get("challengeId"), "clientProof", proof(c, role, origin));
    }
    private static String proof(Map<String, Object> c, String role, String origin) throws Exception {
        String canonical = String.join("\n", "cashprediction-web-reconnect-v1", role, origin,
                (String) c.get("installationId"), (String) c.get("serverGeneration"), (String) c.get("clientNonce"),
                (String) c.get("serverNonce"), (String) c.get("challengeId"));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HexFormat.of().parseHex(KEY.keyHex()), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }
    private static void invalid(org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(IllegalArgumentException.class, action);
        assertEquals("invalid reconnect request", error.getMessage());
    }
    /** Управляемое время без зависимости от ожиданий и планировщика. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-02T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
