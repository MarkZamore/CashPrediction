package ru.cashprediction.core.service.storage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.cashprediction.core.model.Plan;

/** Управляемое хранилище без файлов, фиксирующее реальные обращения потоков приложения к бизнес-контракту. */
public final class FakePlanStorage implements PlanStorage {
    private final Map<Reference, Snapshot> plans = new LinkedHashMap<>();
    private final Map<Collection, List<Reference>> collections = new LinkedHashMap<>();
    private final Map<Reference, Reference> renameTargets = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();
    private long revision;
    private Problem readProblem;
    private Problem writeProblem;
    private Version lastExpected;
    private Reference lastReference;
    private Optional<Version> lastReadExpected = Optional.empty();

    /** @param reference ссылка @param plan план @return новая версия, включая имитацию внешней правки */
    public Version put(Reference reference, Plan plan) {
        Version version = new Version("fake-" + ++revision);
        plans.put(reference, new Snapshot(reference, version, plan, List.of()));
        return version;
    }

    /** @param collection коллекция @param references состав списка */
    public void collection(Collection collection, List<Reference> references) { collections.put(collection, List.copyOf(references)); }
    /** @param source исходная ссылка @param target ссылка после переименования */
    public void renameTarget(Reference source, Reference target) { renameTargets.put(source, target); }
    /** @param problem отказ чтения либо null */
    public void readProblem(Problem problem) { readProblem = problem; }
    /** @param problem отказ записи либо null */
    public void writeProblem(Problem problem) { writeProblem = problem; }
    /** @return неизменяемый журнал обращений */
    public List<String> calls() { return List.copyOf(calls); }
    /** @return последняя обязательная версия записи или переименования */
    public Version lastExpected() { return lastExpected; }
    /** @return последняя ссылка операции изменения */
    public Reference lastReference() { return lastReference; }
    /** @return последняя ожидаемая версия чтения */
    public Optional<Version> lastReadExpected() { return lastReadExpected; }
    /** @param reference ссылка @return текущий снимок имитации */
    public Snapshot snapshot(Reference reference) { return plans.get(reference); }

    /** {@inheritDoc} */
    @Override public Result<List<Entry>> list(Collection collection) {
        calls.add("list");
        return Result.success(collections.getOrDefault(collection, List.of()).stream().map(plans::get)
                .map(snapshot -> new Entry(snapshot.reference(), snapshot.version(), snapshot.plan().name(), Instant.EPOCH)).toList());
    }

    /** {@inheritDoc} */
    @Override public Result<Version> version(Reference reference) {
        calls.add("version");
        return Result.success(currentVersion(reference));
    }

    /** {@inheritDoc} */
    @Override public Result<Snapshot> read(Reference reference, LocalDate today, Optional<Version> expectedVersion) {
        calls.add("read");
        lastReadExpected = expectedVersion;
        if (readProblem != null) return Result.failure(readProblem);
        if (!plans.containsKey(reference)) return failure(Code.MISSING, Conflict.NONE);
        if (expectedVersion.isPresent() && !expectedVersion.get().equals(currentVersion(reference))) return changed();
        return Result.success(plans.get(reference));
    }

    /** {@inheritDoc} */
    @Override public Result<Stored> write(Reference reference, Plan plan, Version expectedVersion) {
        calls.add("write");
        lastExpected = expectedVersion;
        lastReference = reference;
        if (writeProblem != null) return Result.failure(writeProblem);
        if (!expectedVersion.equals(currentVersion(reference))) return changed();
        return Result.success(new Stored(reference, put(reference, plan)));
    }

    /** {@inheritDoc} */
    @Override public Result<Stored> rename(Reference reference, String name, Version expectedVersion) {
        calls.add("rename");
        lastExpected = expectedVersion;
        lastReference = reference;
        if (!plans.containsKey(reference)) return failure(Code.MISSING, Conflict.NONE);
        if (!expectedVersion.equals(currentVersion(reference))) return changed();
        Reference target = renameTargets.get(reference);
        if (plans.containsKey(target)) return failure(Code.CONFLICT, Conflict.NAME_EXISTS);
        Plan renamed = plans.remove(reference).plan().withName(name);
        return Result.success(new Stored(target, put(target, renamed)));
    }

    /** Возвращает версию без дополнительной записи в журнал наблюдений. */
    private Version currentVersion(Reference reference) {
        return plans.containsKey(reference) ? plans.get(reference).version() : Version.ABSENT;
    }
    /** Создаёт управляемый отказ. */
    private static <T> Result<T> failure(Code code, Conflict conflict) { return Result.failure(new Problem(code, conflict, "test")); }
    /** Создаёт конфликт без изменения сохранённого снимка. */
    private static <T> Result<T> changed() { return failure(Code.CONFLICT, Conflict.VERSION_CHANGED); }
}
