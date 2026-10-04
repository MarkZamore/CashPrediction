package ru.cashprediction.parity.check.performance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

import ru.cashprediction.parity.check.performance.DomainLatencyGate.Client;
import ru.cashprediction.parity.check.performance.DomainLatencyGate.Contract;
import ru.cashprediction.parity.check.performance.DomainLatencyGate.Expected;
import ru.cashprediction.parity.check.performance.DomainLatencyGate.Launch;
import ru.cashprediction.parity.check.performance.DomainLatencyGate.Operation;
import ru.cashprediction.parity.check.performance.DomainLatencyGate.Outcome;
import ru.cashprediction.parity.check.performance.DomainLatencyGate.Paint;
import ru.cashprediction.parity.check.performance.DomainLatencyGate.Row;
import ru.cashprediction.parity.check.performance.DomainLatencyGate.Run;
import ru.cashprediction.parity.check.performance.DomainLatencyGate.Sample;
import ru.cashprediction.parity.check.performance.DomainLatencyGate.Stamp;

/**
 * Проверяет только логику валидатора на рукотворных структурированных данных.
 * Эти фикстуры не являются свидетельствами скорости или отрисовки реальных клиентов.
 */
final class DomainLatencyGateTest {
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
    private static final String FIXTURE = "a".repeat(64);
    private static final String JAR = "b".repeat(64);
    private static final List<Row> OPEN_ROWS = List.of(
            new Row("one", List.of("Alpha", "10")),
            new Row("two", List.of("Beta", "20")));
    private static final List<Row> FILTER_ROWS = List.of(new Row("two", List.of("Beta", "20")));

    @Test
    void acceptsEverySampleBelowStrictBoundsAndDoesNotChargeJvmStartupToOpen() {
        Fixture f = fixture(-20_000_000_000L);
        assertDoesNotThrow(() -> DomainLatencyGate.verify(f.contract(), f.run(), NOW));
        for (Client client : Client.values()) {
            Fixture almostLimit = edit(f, client, 0, s -> withPaint(s, paint(s,
                    new Stamp(s.requestedAt().clockId(), s.requestedAt().nanos() + 1_999_999_999L),
                    s.paint().revision(), s.paint().fixtureDigest(), s.paint().rows())));
            assertDoesNotThrow(() -> DomainLatencyGate.verify(almostLimit.contract(), almostLimit.run(), NOW));
            Fixture almostFilterLimit = edit(f, client, 1, s -> withPaint(s, paint(s,
                    new Stamp(s.requestedAt().clockId(), s.requestedAt().nanos() + 99_999_999L),
                    s.paint().revision(), s.paint().fixtureDigest(), s.paint().rows())));
            assertDoesNotThrow(() ->
                    DomainLatencyGate.verify(almostFilterLimit.contract(), almostFilterLimit.run(), NOW));
        }
    }

    @Test
    void rejectsEqualityAndInjectedDelayForEachClientAndOperation() {
        for (Client client : Client.values()) {
            for (int index = 0; index < 2; index++) {
                long limit = index == 0 ? 2_000_000_000L : 100_000_000L;
                for (long delay : new long[] {limit, limit + 1, limit * 3}) {
                    Fixture bad = edit(fixture(0), client, index, s -> withPaint(s, paint(s,
                            new Stamp(s.requestedAt().clockId(), s.requestedAt().nanos() + delay),
                            s.paint().revision(), s.paint().fixtureDigest(), s.paint().rows())));
                    fails("latency-limit", bad);
                }
            }
        }
    }

    @Test
    void aFastLaterFilterCannotHideASlowOrdinaryFilter() {
        Fixture f = fixture(0);
        var events = new EnumMap<Client, List<Expected>>(f.contract().events());
        var samples = new EnumMap<Client, List<Sample>>(f.run().samples());
        for (Client client : Client.values()) {
            var clientEvents = new ArrayList<>(events.get(client));
            clientEvents.add(new Expected(3, "filter-again", Operation.FILTER, "Alpha",
                    13, FIXTURE, List.of(OPEN_ROWS.get(0))));
            events.put(client, clientEvents);
            Sample prior = samples.get(client).get(1);
            String clock = prior.requestedAt().clockId();
            var clientSamples = new ArrayList<>(samples.get(client));
            Paint ack = new Paint(prior.runId(), client, 3, "filter-again", 13, FIXTURE,
                    List.of(OPEN_ROWS.get(0)), new Stamp(clock, 13_001_000_000L));
            clientSamples.add(new Sample(prior.runId(), client, 3, "filter-again",
                    Operation.FILTER, "Alpha", new Stamp(clock, 13_000_000_000L), NOW,
                    prior.jarDigests(), Outcome.PAINTED, ack));
            samples.put(client, clientSamples);
        }
        Fixture extended = new Fixture(new Contract(f.contract().runId(), f.contract().notBefore(),
                f.contract().maxAge(), f.contract().jars(), events),
                new Run(f.run().runId(), f.run().captureStarted(), f.run().captureFinished(),
                        f.run().launches(), samples));
        assertDoesNotThrow(() -> DomainLatencyGate.verify(extended.contract(), extended.run(), NOW));
        for (Client client : Client.values()) {
            fails("latency-limit", edit(extended, client, 1, s -> withPaint(s,
                    paint(s, new Stamp(s.requestedAt().clockId(), s.requestedAt().nanos() + 150_000_000L),
                            12, FIXTURE, FILTER_ROWS))));
        }
    }

    @Test
    void rejectsStaleRowsWrongCellsOrderRevisionAndFixtureDigest() {
        for (Client client : Client.values()) {
            Fixture f = fixture(0);
            fails("incorrect-paint", edit(f, client, 1, s -> withPaint(s,
                    paint(s, s.paint().paintedAt(), 12, FIXTURE, OPEN_ROWS))));
            fails("incorrect-paint", edit(f, client, 1, s -> withPaint(s,
                    paint(s, s.paint().paintedAt(), 12, FIXTURE,
                            List.of(new Row("two", List.of("Beta", "999")))))));
            fails("incorrect-paint", edit(f, client, 0, s -> withPaint(s,
                    paint(s, s.paint().paintedAt(), 11, FIXTURE,
                            List.of(OPEN_ROWS.get(1), OPEN_ROWS.get(0))))));
            fails("incorrect-paint", edit(f, client, 1, s -> withPaint(s,
                    paint(s, s.paint().paintedAt(), 11, FIXTURE, FILTER_ROWS))));
            fails("incorrect-paint", edit(f, client, 1, s -> withPaint(s,
                    paint(s, s.paint().paintedAt(), 12, "c".repeat(64), FILTER_ROWS))));
        }
    }

    @Test
    void rejectsMissingAcknowledgementRendererErrorsAndTimeouts() {
        for (Client client : Client.values()) {
            Fixture f = fixture(0);
            for (int index = 0; index < 2; index++) {
                fails("missing-renderer-ack", edit(f, client, index, s -> withPaint(s, null)));
                fails("missing-renderer-ack", edit(f, client, index, s -> {
                    Paint p = s.paint();
                    return withPaint(s, new Paint(p.runId(), null, p.sequence(), p.correlation(),
                            p.revision(), p.fixtureDigest(), p.rows(), p.paintedAt()));
                }));
                fails("missing-renderer-ack", edit(f, client, index, s -> {
                    Paint p = s.paint();
                    Client other = client == Client.FX ? Client.WEB : Client.FX;
                    return withPaint(s, new Paint(p.runId(), other, p.sequence(), p.correlation(),
                            p.revision(), p.fixtureDigest(), p.rows(), p.paintedAt()));
                }));
                for (Outcome outcome : List.of(Outcome.ERROR, Outcome.TIMEOUT)) {
                    fails("error-or-timeout", edit(f, client, index, s ->
                            new Sample(s.runId(), client, s.sequence(), s.correlation(), s.operation(),
                                    s.input(), s.requestedAt(), s.capturedAt(), s.jarDigests(),
                                    outcome, s.paint())));
                }
            }
        }
    }

    @Test
    void rejectsSkippedClientsSamplesDuplicatesReorderingAndBestOfAttempts() {
        Fixture f = fixture(0);
        for (Client client : Client.values()) {
            var missingClient = new EnumMap<Client, List<Sample>>(f.run().samples());
            missingClient.remove(client);
            fails("all-clients", withSamples(f, missingClient));
            for (List<Sample> samples : List.of(
                    List.<Sample>of(), List.of(f.run().samples().get(client).get(0)),
                    List.of(f.run().samples().get(client).get(0), f.run().samples().get(client).get(0)),
                    List.of(f.run().samples().get(client).get(1), f.run().samples().get(client).get(0)),
                    List.of(f.run().samples().get(client).get(0), f.run().samples().get(client).get(1),
                            f.run().samples().get(client).get(1)))) {
                var changed = new EnumMap<Client, List<Sample>>(f.run().samples());
                changed.put(client, samples);
                assertThrows(AssertionError.class, () ->
                        DomainLatencyGate.verify(f.contract(), withSamples(f, changed).run(), NOW));
            }
            var launches = new EnumMap<Client, Launch>(f.run().launches());
            launches.remove(client);
            fails("all-clients", withRun(f, new Run(f.run().runId(), f.run().captureStarted(),
                    f.run().captureFinished(), launches, f.run().samples())));
        }
    }

    @Test
    void rejectsWrongRunCorrelationSequenceOperationAndFilterInput() {
        Fixture f = fixture(0);
        for (Client client : Client.values()) {
            Sample original = f.run().samples().get(client).get(1);
            for (Sample bad : List.of(
                    new Sample("old-run", client, 2, "filter", Operation.FILTER, "Beta",
                            original.requestedAt(), NOW, original.jarDigests(), Outcome.PAINTED, original.paint()),
                    new Sample("unit-run-001", client, 2, "wrong", Operation.FILTER, "Beta",
                            original.requestedAt(), NOW, original.jarDigests(), Outcome.PAINTED, original.paint()),
                    new Sample(original.runId(), client, 1, "filter", Operation.FILTER, "Beta",
                            original.requestedAt(), NOW, original.jarDigests(), Outcome.PAINTED, original.paint()),
                    new Sample(original.runId(), client, 2, "filter", Operation.OPEN, "Beta",
                            original.requestedAt(), NOW, original.jarDigests(), Outcome.PAINTED, original.paint()),
                    new Sample(original.runId(), client, 2, "filter", Operation.FILTER, "Alpha",
                            original.requestedAt(), NOW, original.jarDigests(), Outcome.PAINTED, original.paint()))) {
                assertThrows(AssertionError.class, () ->
                        DomainLatencyGate.verify(f.contract(), edit(f, client, 1, s -> bad).run(), NOW));
            }
            fails("paint-identity", edit(f, client, 1, s -> {
                Paint p = s.paint();
                return withPaint(s, new Paint("old-run", client, p.sequence(), p.correlation(),
                        p.revision(), p.fixtureDigest(), p.rows(), p.paintedAt()));
            }));
            fails("paint-identity", edit(f, client, 1, s -> {
                Paint p = s.paint();
                return withPaint(s, new Paint(p.runId(), client, 1, "old-correlation",
                        p.revision(), p.fixtureDigest(), p.rows(), p.paintedAt()));
            }));
        }
        fails("run-id", withRun(f, new Run("old-run", f.run().captureStarted(),
                f.run().captureFinished(), f.run().launches(), f.run().samples())));
    }

    @Test
    void rejectsStaleFutureAndOutOfCaptureEvidence() {
        Fixture f = fixture(0);
        fails("capture-freshness", withRun(f, new Run(f.run().runId(), NOW.minusSeconds(61),
                NOW.minusSeconds(60), f.run().launches(), f.run().samples())));
        fails("capture-freshness", withRun(f, new Run(f.run().runId(), NOW.minusSeconds(11),
                NOW, f.run().launches(), f.run().samples())));
        fails("capture-freshness", withRun(f, new Run(f.run().runId(), NOW, NOW.plusSeconds(1),
                f.run().launches(), f.run().samples())));
        fails("capture-freshness", withRun(f, new Run(f.run().runId(), NOW, NOW.minusSeconds(1),
                f.run().launches(), f.run().samples())));
        fails("sample-freshness", edit(f, Client.WEB, 1, s ->
                new Sample(s.runId(), s.client(), s.sequence(), s.correlation(), s.operation(),
                        s.input(), s.requestedAt(), NOW.minusSeconds(3), s.jarDigests(), s.outcome(), s.paint())));
        // Свежий конец захвата не скрывает старое начало даже при допустимом notBefore.
        Contract looseStart = new Contract(f.contract().runId(), NOW.minusSeconds(120),
                Duration.ofSeconds(10), f.contract().jars(), f.contract().events());
        fails("capture-freshness", new Fixture(looseStart,
                new Run(f.run().runId(), NOW.minusSeconds(11), NOW, f.run().launches(), f.run().samples())));
    }

    @Test
    void acceptsSignedNanoTimeAndWrapButRejectsMixedClocksAndBackwardOrder() {
        assertDoesNotThrow(() -> {
            Fixture signed = fixture(-20_000_000_000L);
            DomainLatencyGate.verify(signed.contract(), signed.run(), NOW);
            Fixture wrap = fixture(Long.MAX_VALUE - 5_000_000_000L);
            DomainLatencyGate.verify(wrap.contract(), wrap.run(), NOW);
        });
        Fixture f = fixture(0);
        fails("monotonic-clock", edit(f, Client.WEB, 1, s -> withPaint(s,
                paint(s, new Stamp("browser-clock", s.paint().paintedAt().nanos()),
                        12, FIXTURE, FILTER_ROWS))));
        fails("monotonic-order", edit(f, Client.FX, 1, s -> withPaint(s,
                paint(s, new Stamp(s.requestedAt().clockId(), s.requestedAt().nanos() - 1),
                        12, FIXTURE, FILTER_ROWS))));
        fails("monotonic-order", edit(f, Client.SWING, 1, s ->
                new Sample(s.runId(), s.client(), s.sequence(), s.correlation(), s.operation(), s.input(),
                        new Stamp(s.requestedAt().clockId(), 9_000_000_000L),
                        s.capturedAt(), s.jarDigests(), s.outcome(), s.paint())));
        fails("monotonic-clock", edit(f, Client.FX, 0, s ->
                new Sample(s.runId(), s.client(), s.sequence(), s.correlation(), s.operation(), s.input(),
                        new Stamp("other-jvm", s.requestedAt().nanos()),
                        s.capturedAt(), s.jarDigests(), s.outcome(), s.paint())));
    }

    @Test
    void rejectsMissingExtraAndMismatchedFrozenJarDigests() {
        Fixture f = fixture(0);
        for (Client client : Client.values()) {
            for (Map<String, String> jars : List.of(Map.<String, String>of(),
                    Map.of("core.jar", "c".repeat(64), "renderer.jar", JAR),
                    Map.of("core.jar", JAR, "renderer.jar", JAR, "extra.jar", JAR))) {
                fails("jar-digest-mismatch", edit(f, client, 1, s ->
                        new Sample(s.runId(), client, s.sequence(), s.correlation(), s.operation(),
                                s.input(), s.requestedAt(), s.capturedAt(), jars, s.outcome(), s.paint())));
            }
        }
        var manifests = new EnumMap<Client, Map<String, String>>(f.contract().jars());
        manifests.put(Client.FX, Map.of());
        fails("missing-jar-manifest", new Fixture(new Contract(f.contract().runId(),
                f.contract().notBefore(), f.contract().maxAge(), manifests, f.contract().events()), f.run()));
    }

    @Test
    void rejectsMissingAndMalformedIndependentContract() {
        Fixture f = fixture(0);
        assertThrows(AssertionError.class, () -> DomainLatencyGate.verify(f.contract(), null, NOW));
        var events = new EnumMap<Client, List<Expected>>(f.contract().events());
        events.remove(Client.WEB);
        fails("all-clients", new Fixture(new Contract(f.contract().runId(), f.contract().notBefore(),
                f.contract().maxAge(), f.contract().jars(), events), f.run()));
        events = new EnumMap<>(f.contract().events());
        Expected open = events.get(Client.FX).get(0);
        events.put(Client.FX, List.of(open, open));
        fails("expected-sequence", new Fixture(new Contract(f.contract().runId(),
                f.contract().notBefore(), f.contract().maxAge(), f.contract().jars(), events), f.run()));
    }

    @Test
    void freezesNestedCallerContainers() {
        Fixture f = fixture(0);
        var cells = new ArrayList<>(List.of("Beta", "20"));
        Row row = new Row("two", cells);
        cells.set(1, "changed");
        assertEquals(FILTER_ROWS.get(0), row);
        var jarMap = new HashMap<>(Map.of("core.jar", JAR, "renderer.jar", JAR));
        var manifests = new EnumMap<Client, Map<String, String>>(Client.class);
        var events = new EnumMap<Client, List<Expected>>(Client.class);
        var observations = new EnumMap<Client, List<Sample>>(Client.class);
        for (Client client : Client.values()) {
            manifests.put(client, jarMap);
            events.put(client, new ArrayList<>(f.contract().events().get(client)));
            observations.put(client, new ArrayList<>(f.run().samples().get(client)));
        }
        Contract contract = new Contract(f.contract().runId(), f.contract().notBefore(),
                f.contract().maxAge(), manifests, events);
        Run run = new Run(f.run().runId(), f.run().captureStarted(), f.run().captureFinished(),
                new EnumMap<>(f.run().launches()), observations);
        jarMap.clear();
        events.values().forEach(List::clear);
        observations.values().forEach(List::clear);
        manifests.clear();
        assertDoesNotThrow(() -> DomainLatencyGate.verify(contract, run, NOW));
        assertThrows(UnsupportedOperationException.class, () -> run.samples().get(Client.FX).clear());
        assertThrows(UnsupportedOperationException.class, () -> contract.jars().get(Client.FX).clear());
        assertThrows(UnsupportedOperationException.class, () -> f.run().samples().get(Client.FX)
                .get(0).jarDigests().clear());
        assertThrows(UnsupportedOperationException.class, () -> f.run().samples().get(Client.FX)
                .get(0).paint().rows().clear());
    }

    private static Fixture fixture(long base) {
        String runId = "unit-run-001";
        var manifests = new EnumMap<Client, Map<String, String>>(Client.class);
        var events = new EnumMap<Client, List<Expected>>(Client.class);
        var launches = new EnumMap<Client, Launch>(Client.class);
        var samples = new EnumMap<Client, List<Sample>>(Client.class);
        // Оракул установлен отдельно; наблюдения ниже не используются при его создании.
        for (Client client : Client.values()) {
            manifests.put(client, Map.of("core.jar", JAR, "renderer.jar", JAR));
            events.put(client, List.of(
                    new Expected(1, "open", Operation.OPEN, "", 11, FIXTURE, OPEN_ROWS),
                    new Expected(2, "filter", Operation.FILTER, "Beta", 12, FIXTURE, FILTER_ROWS)));
        }
        Contract contract = new Contract(runId, NOW.minusSeconds(10), Duration.ofSeconds(10),
                manifests, events);
        for (Client client : Client.values()) {
            String clock = "clock-" + client.name();
            launches.put(client, new Launch(runId, client, new Stamp(clock, base)));
            Paint open = new Paint(runId, client, 1, "open", 11, FIXTURE, OPEN_ROWS,
                    new Stamp(clock, base + 10_050_000_000L));
            Paint filter = new Paint(runId, client, 2, "filter", 12, FIXTURE, FILTER_ROWS,
                    new Stamp(clock, base + 12_010_000_000L));
            samples.put(client, List.of(
                    new Sample(runId, client, 1, "open", Operation.OPEN, "",
                            new Stamp(clock, base + 10_000_000_000L), NOW.minusSeconds(1),
                            Map.of("core.jar", JAR, "renderer.jar", JAR), Outcome.PAINTED, open),
                    new Sample(runId, client, 2, "filter", Operation.FILTER, "Beta",
                            new Stamp(clock, base + 12_000_000_000L), NOW,
                            Map.of("core.jar", JAR, "renderer.jar", JAR), Outcome.PAINTED, filter)));
        }
        return new Fixture(contract, new Run(runId, NOW.minusSeconds(2), NOW, launches, samples));
    }

    private static Paint paint(Sample s, Stamp at, long revision, String digest, List<Row> rows) {
        Paint p = s.paint();
        return new Paint(p.runId(), p.renderer(), p.sequence(), p.correlation(), revision, digest, rows, at);
    }

    private static Sample withPaint(Sample s, Paint paint) {
        return new Sample(s.runId(), s.client(), s.sequence(), s.correlation(), s.operation(), s.input(),
                s.requestedAt(), s.capturedAt(), s.jarDigests(), s.outcome(), paint);
    }

    private static Fixture edit(Fixture f, Client client, int index, UnaryOperator<Sample> change) {
        var samples = new EnumMap<Client, List<Sample>>(f.run().samples());
        var changed = new ArrayList<>(samples.get(client));
        changed.set(index, change.apply(changed.get(index)));
        samples.put(client, changed);
        return withSamples(f, samples);
    }

    private static Fixture withSamples(Fixture f, Map<Client, List<Sample>> samples) {
        Run r = f.run();
        return withRun(f, new Run(r.runId(), r.captureStarted(), r.captureFinished(), r.launches(), samples));
    }

    private static Fixture withRun(Fixture f, Run run) {
        return new Fixture(f.contract(), run);
    }

    private static void fails(String code, Fixture f) {
        assertEquals(code, assertThrows(AssertionError.class, () ->
                DomainLatencyGate.verify(f.contract(), f.run(), NOW)).getMessage());
    }

    /** Пара независимого контракта и изменяемого через копирование тестового захвата. */
    private record Fixture(Contract contract, Run run) { }
}
