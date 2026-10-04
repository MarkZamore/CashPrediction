package ru.cashprediction.parity.check.performance;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Проверяет переданные свидетельства задержки реальных клиентов.
 * Сам не запускает клиентов и не доказывает происхождение подтверждения отрисовки.
 * Ожидания, идентификатор запуска, окно свежести и хеши задаёт вызывающая сторона
 * до сбора наблюдений. OPEN измеряется от запроса открытия, отдельно от старта JVM.
 * Разности nanoTime допустимы при интервалах менее 2^63 наносекунд, включая
 * отрицательные значения и переход через границу знакового long.
 */
public final class DomainLatencyGate {
    private static final long OPEN_LIMIT = 2_000_000_000L;
    private static final long FILTER_LIMIT = 100_000_000L;

    private DomainLatencyGate() { }

    /** Полный обязательный набор клиентов. */
    public enum Client { FX, SWING, WEB }

    /** Открытие и обычный ввод фильтра; специальные ускоренные операции не принимаются. */
    public enum Operation { OPEN, FILTER }

    /** Исход наблюдаемой операции; ошибка и тайм-аут всегда проваливают проверку. */
    public enum Outcome { PAINTED, ERROR, TIMEOUT }

    /** Метка одного монотонного источника; знак nanos не ограничивается. */
    public record Stamp(String clockId, long nanos) { }

    /** Полная строка результата, включая идентификатор и упорядоченные значения ячеек. */
    public record Row(String id, List<String> cells) {
        /** Замораживает ячейки строки. */
        public Row {
            cells = List.copyOf(cells);
        }
    }

    /** Ожидание одного события, установленное независимо от наблюдения. */
    public record Expected(long sequence, String correlation, Operation operation, String input,
                           long revision, String fixtureDigest, List<Row> rows) {
        /** Замораживает ожидаемые строки. */
        public Expected {
            rows = List.copyOf(rows);
        }
    }

    /**
     * Замороженный контракт запуска для всех клиентов.
     * jars содержит полный внешний манифест используемых jar каждого клиента.
     * notBefore и maxAge ограничивают весь захват относительно переданного now.
     */
    public record Contract(String runId, Instant notBefore, Duration maxAge,
                           Map<Client, Map<String, String>> jars,
                           Map<Client, List<Expected>> events) {
        /** Делает глубокие неизменяемые копии контейнеров контракта. */
        public Contract {
            jars = freezeJars(jars);
            events = freezeLists(events);
        }
    }

    /** Запуск JVM клиента, имеющий собственную метку, отличную по назначению от OPEN. */
    public record Launch(String runId, Client client, Stamp jvmStart) { }

    /**
     * Явное свидетельство корректной отрисовки.
     * renderer обозначает фактически отрисовавший клиент, а paintedAt - его подтверждение.
     * Эти поля обязан предоставить внешний сборщик после проверки полного результата.
     */
    public record Paint(String runId, Client renderer, long sequence, String correlation,
                        long revision, String fixtureDigest, List<Row> rows, Stamp paintedAt) {
        /** Замораживает содержимое подтверждения. */
        public Paint {
            rows = List.copyOf(rows);
        }
    }

    /** Наблюдение одного реального запроса; отсутствие paint допустимо в модели, но не в gate. */
    public record Sample(String runId, Client client, long sequence, String correlation,
                         Operation operation, String input, Stamp requestedAt, Instant capturedAt,
                         Map<String, String> jarDigests, Outcome outcome, Paint paint) {
        /** Замораживает манифест, снятый внешним сборщиком для данного наблюдения. */
        public Sample {
            jarDigests = Map.copyOf(jarDigests);
        }
    }

    /** Неизменяемый полный захват; пустые или неполные контейнеры будут отвергнуты. */
    public record Run(String runId, Instant captureStarted, Instant captureFinished,
                      Map<Client, Launch> launches, Map<Client, List<Sample>> samples) {
        /** Замораживает запуск и все списки наблюдений. */
        public Run {
            launches = Map.copyOf(launches);
            samples = freezeLists(samples);
        }
    }

    /**
     * Проверяет каждое наблюдение всех трёх клиентов без усреднения или выбора лучшего.
     * Бросает AssertionError с техническим кодом при отсутствующем или неверном свидетельстве.
     * now задаётся вызывающей стороной из доверенных текущих часов, а не из захвата.
     */
    public static void verify(Contract contract, Run run, Instant now) {
        require(contract != null && run != null && now != null, "missing-run");
        require(nonblank(contract.runId()) && contract.runId().equals(run.runId()), "run-id");
        require(contract.notBefore() != null && contract.maxAge() != null
                && !contract.maxAge().isNegative() && !contract.maxAge().isZero(), "capture-policy");
        require(run.captureStarted() != null && run.captureFinished() != null, "capture-window");
        require(!run.captureStarted().isBefore(contract.notBefore())
                && !run.captureFinished().isBefore(run.captureStarted())
                && !run.captureFinished().isAfter(now)
                && Duration.between(run.captureStarted(), now).compareTo(contract.maxAge()) <= 0,
                "capture-freshness");
        Set<Client> clients = Set.of(Client.values());
        require(contract.events().keySet().equals(clients)
                && contract.jars().keySet().equals(clients)
                && run.launches().keySet().equals(clients)
                && run.samples().keySet().equals(clients), "all-clients");
        for (Client client : Client.values()) {
            verifyClient(contract, run, client);
        }
    }

    private static void verifyClient(Contract contract, Run run, Client client) {
        Map<String, String> jars = contract.jars().get(client);
        require(!jars.isEmpty(), "missing-jar-manifest");
        jars.forEach((name, digest) ->
                require(nonblank(name) && sha256(digest), "jar-manifest"));
        List<Expected> expected = contract.events().get(client);
        List<Sample> samples = run.samples().get(client);
        require(expected.size() >= 2 && samples.size() == expected.size(), "sample-count");
        Launch launch = run.launches().get(client);
        require(run.runId().equals(launch.runId()) && launch.client() == client, "launch-identity");
        require(validStamp(launch.jvmStart()), "jvm-origin");
        Stamp previousPaint = launch.jvmStart();
        long previousSequence = Long.MIN_VALUE;
        Set<String> correlations = new HashSet<>();
        for (int index = 0; index < expected.size(); index++) {
            Expected target = expected.get(index);
            Sample sample = samples.get(index);
            require(target.sequence() > previousSequence && nonblank(target.correlation())
                    && correlations.add(target.correlation()), "expected-sequence");
            require(target.operation() == (index == 0 ? Operation.OPEN : Operation.FILTER),
                    "ordinary-open-filter");
            require(target.input() != null && (index == 0 ? target.input().isEmpty()
                    : !target.input().isBlank()), "expected-input");
            require(sha256(target.fixtureDigest()) && validRows(target.rows()), "expected-content");
            require(sample.sequence() == target.sequence()
                    && sample.operation() == target.operation()
                    && target.correlation().equals(sample.correlation())
                    && target.input().equals(sample.input()), "sample-identity");
            require(run.runId().equals(sample.runId()) && sample.client() == client, "sample-run");
            require(sample.capturedAt() != null
                    && !sample.capturedAt().isBefore(run.captureStarted())
                    && !sample.capturedAt().isAfter(run.captureFinished()), "sample-freshness");
            require(jars.equals(sample.jarDigests()), "jar-digest-mismatch");
            require(sample.outcome() == Outcome.PAINTED, "error-or-timeout");
            require(validStamp(sample.requestedAt()), "request-origin");
            elapsed(previousPaint, sample.requestedAt());
            Paint paint = sample.paint();
            require(paint != null && paint.renderer() == client, "missing-renderer-ack");
            require(run.runId().equals(paint.runId()) && paint.sequence() == target.sequence()
                    && target.correlation().equals(paint.correlation()), "paint-identity");
            require(paint.revision() == target.revision()
                    && target.fixtureDigest().equals(paint.fixtureDigest())
                    && target.rows().equals(paint.rows()), "incorrect-paint");
            long latency = elapsed(sample.requestedAt(), paint.paintedAt());
            long limit = target.operation() == Operation.OPEN ? OPEN_LIMIT : FILTER_LIMIT;
            require(latency < limit, "latency-limit");
            previousPaint = paint.paintedAt();
            previousSequence = target.sequence();
        }
    }

    private static long elapsed(Stamp from, Stamp to) {
        require(validStamp(from) && validStamp(to) && from.clockId().equals(to.clockId()),
                "monotonic-clock");
        long difference = to.nanos() - from.nanos();
        require(difference >= 0, "monotonic-order");
        return difference;
    }

    private static boolean validStamp(Stamp stamp) {
        return stamp != null && nonblank(stamp.clockId());
    }

    private static boolean validRows(List<Row> rows) {
        Set<String> ids = new HashSet<>();
        return rows.stream().allMatch(row -> nonblank(row.id()) && ids.add(row.id()));
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static <T> Map<Client, List<T>> freezeLists(Map<Client, List<T>> source) {
        var copy = new java.util.EnumMap<Client, List<T>>(Client.class);
        source.forEach((client, values) -> copy.put(Objects.requireNonNull(client), List.copyOf(values)));
        return Map.copyOf(copy);
    }

    private static Map<Client, Map<String, String>> freezeJars(
            Map<Client, Map<String, String>> source) {
        var copy = new java.util.EnumMap<Client, Map<String, String>>(Client.class);
        source.forEach((client, values) -> copy.put(Objects.requireNonNull(client), Map.copyOf(values)));
        return Map.copyOf(copy);
    }

    private static void require(boolean condition, String code) {
        if (!condition) {
            throw new AssertionError(code);
        }
    }
}
