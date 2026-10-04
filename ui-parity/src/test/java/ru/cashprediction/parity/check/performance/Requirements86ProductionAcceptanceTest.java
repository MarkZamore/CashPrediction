package ru.cashprediction.parity.check.performance;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import ru.cashprediction.core.json.*;
import static ru.cashprediction.parity.check.performance.DomainLatencyGate.*;

/** Opt-in gate №86: реальные внешние наблюдения обязательны; механические fixtures сюда не подходят. */
public final class Requirements86ProductionAcceptanceTest {
    /** Связывает независимый план/контракт, все события и отдельно одобренный collector receipt. */
    @Test public void actualEvidenceMeetsSavingsAndLatencyContract() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("parity.requirements86"), "Enable parity.requirements86=true; inactive test is not acceptance");
        Path root = Path.of(required("parity.requirements86.evidence")).toAbsolutePath().normalize();
        Path provenance = root.resolve("provenance.json");
        String approved = required("parity.requirements86.approvedProvenanceSha256");
        if (!approved.matches("[0-9a-f]{64}") || !approved.equals(hash(provenance))) throw new AssertionError("REQ86_INDEPENDENT_REVIEW_REQUIRED");
        var proof = Json.asObject(JsonParser.parse(Files.readString(provenance)), "provenance");
        if (!Json.requireString(proof, "scope").equals("ACTUAL_PRODUCTION_PHYSICAL_INPUT_PRESENTED_FRAME")
                || !Json.bool(proof, "independentlyReviewed", false) || Json.bool(proof, "mock", true)) throw new AssertionError("REQ86_PROVENANCE_SCOPE");
        Set<String> pinned = new HashSet<>();
        for (var item : Json.list(proof, "artifactPins")) {
            var pin = Json.asObject(item, "pin");
            if (!pinned.add(Json.requireString(pin, "relativePath"))) throw new AssertionError("REQ86_DUPLICATE_PIN");
            Path file = root.resolve(Json.requireString(pin, "relativePath")).normalize();
            if (!file.startsWith(root.toAbsolutePath().normalize()) || !Json.requireString(pin, "sha256").equals(hash(file))) throw new AssertionError("REQ86_EVIDENCE_BYTES");
        }
        if (Json.list(proof, "artifactPins").isEmpty()) throw new AssertionError("REQ86_NO_RAW_EVIDENCE");
        if (!pinned.containsAll(Set.of("contract.json", "run.json", "expected-keypresses.json",
                "CashMemory/S5-performance-weekly-600.md", "input-transcript.json", "presented-frames.json",
                "collector.json", "ux-observations.json", "collection-inputs.json", "collection.json"))) throw new AssertionError("REQ86_MISSING_EVIDENCE_PIN");
        Contract contract = Requirements86Acceptance.decode(Files.readString(root.resolve("contract.json")), Contract.class);
        Run run = Requirements86Acceptance.decode(Files.readString(root.resolve("run.json")), Run.class);
        var inputs = Requirements86Acceptance.decode(Files.readString(root.resolve("collection-inputs.json")), Requirements86CollectionScenario.Inputs.class);
        if (!inputs.sourceHeadSha().equals(required("parity.requirements86.expectedSourceHeadSha"))) throw new AssertionError("REQ86_FOREIGN_SOURCE_SHA");
        if (!hash(root.resolve("collection-inputs.json")).equals(required("parity.requirements86.approvedInputsSha256"))) throw new AssertionError("REQ86_FOREIGN_INPUT_SHA");
        Requirements86CollectionScenario.validate(inputs, contract);
        Requirements86CollectionScenario.requireSourcePins(Path.of(System.getProperty("user.dir")), inputs);
        var collection = Json.asObject(JsonParser.parse(Files.readString(root.resolve("collection.json"))), "collection");
        if (!Boolean.TRUE.equals(collection.get("nativeRequested")) || !"COLLECTED_NOT_ACCEPTED".equals(collection.get("status"))
                || !inputs.sourceHeadSha().equals(collection.get("sourceHeadSha"))
                || !hash(root.resolve("collection-inputs.json")).equals(collection.get("inputsSha256"))) throw new AssertionError("REQ86_COLLECTION_SCOPE");
        Requirements86Collector.verifyEvidence(root, run);
        if (!Json.requireString(proof, "runId").equals(contract.runId())) throw new AssertionError("REQ86_PROVENANCE_RUN");
        var prefixes = (List<?>) JsonParser.parse(Files.readString(root.resolve("expected-keypresses.json")));
        String fixtureHash = hash(root.resolve("CashMemory/S5-performance-weekly-600.md"));
        for (Client client : Client.values()) {
            var events = contract.events().get(client);
            if (events == null || events.size() != prefixes.size() + 1) throw new AssertionError("REQ86_MISSING_ORDINARY_KEYS");
            for (int i = 0; i < events.size(); i++) {
                if (!events.get(i).fixtureDigest().equals(fixtureHash)) throw new AssertionError("REQ86_FIXTURE_IDENTITY");
                if (i > 0 && !events.get(i).input().equals(Json.requireString(Json.asObject(prefixes.get(i - 1), "prefix"), "input"))) throw new AssertionError("REQ86_KEY_ORDER");
            }
        }
        Set<String> cells = new HashSet<>();
        for (var item : Json.list(proof, "uxCells")) {
            var cell = Json.asObject(item, "uxCell");
            String key = Json.requireString(cell, "client") + "/" + Json.requireString(cell, "step");
            if (!cells.add(key) || !Json.requireString(cell, "status").equals("ACCEPTED")) throw new AssertionError("REQ86_UX_CELL");
        }
        Set<String> expected = new HashSet<>();
        for (Client client : Client.values()) for (String step : List.of("savings-create", "goal-decision", "funding-gap", "compare-scenarios", "save-reopen")) expected.add(client + "/" + step);
        if (!cells.equals(expected)) throw new AssertionError("REQ86_UX_MATRIX");
        // Этот вызов переиспользует существующие бюджеты, clocks, revisions, rows и freshness.
        DomainLatencyGate.verify(contract, run, Instant.now());
    }

    /** Требует явно заданный внешний pin, не извлекает approval из недоверенного receipt. */
    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new AssertionError("REQ86_MISSING_PROPERTY " + name);
        return value;
    }

    /** Хеширует файл независимого evidence. */
    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
