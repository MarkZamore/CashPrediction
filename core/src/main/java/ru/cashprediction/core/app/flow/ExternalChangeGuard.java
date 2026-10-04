package ru.cashprediction.core.app.flow;

import java.nio.file.Path;
import java.util.Objects;
import ru.cashprediction.core.service.storage.FilePlanStorage;
import ru.cashprediction.core.service.storage.PlanStorage;

/**
 * Проверка внешнего изменения плана (спецификация v2, §6.14) по непрозрачной версии хранения.
 * Версия успешного чтения или записи запоминается без повторного наблюдения данных.
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class ExternalChangeGuard {

    private final PlanStorage storage;
    private PlanStorage.Reference rememberedReference;
    private PlanStorage.Version rememberedVersion;

    /** Создаёт проверку без запомненного файла. */
    public ExternalChangeGuard() {
        this(new FilePlanStorage());
    }

    /** @param storage общий владелец сохранённых планов приложения */
    public ExternalChangeGuard(PlanStorage storage) { this.storage = Objects.requireNonNull(storage, "storage"); }

    /** @return общий сервис; сохраняет прежние конструкторы потоков изолированных тестов */
    public PlanStorage storage() { return storage; }

    /**
     * @param reference ссылка
     * @param version версия именно прочитанного или записанного снимка
     */
    public void remember(PlanStorage.Reference reference, PlanStorage.Version version) {
        rememberedReference = Objects.requireNonNull(reference, "reference");
        rememberedVersion = Objects.requireNonNull(version, "version");
    }

    /**
     * Возвращает прежнюю версию, не принимая внешнюю правку за успешно прочитанный снимок.
     * @param reference ссылка
     * @param fallback текущая версия, только если ссылка ещё не запомнена
     * @return ожидаемая версия следующей записи
     */
    public PlanStorage.Version expectedVersion(PlanStorage.Reference reference, PlanStorage.Version fallback) {
        return reference.equals(rememberedReference) && rememberedVersion != null ? rememberedVersion : fallback;
    }

    /**
     * Наблюдает версию для прежних сценариев восстановления и изолированных тестов.
     *
     * @param file файл плана
     */
    public void remember(Path file) {
        if (file == null) { forget(); return; }
        rememberedReference = FilePlanStorage.reference(file);
        var observed = storage.version(rememberedReference);
        // Недоступная версия запрещает перезапись до явного подтверждения, не создавая чужих токенов.
        rememberedVersion = observed.succeeded() ? observed.value() : PlanStorage.Version.ABSENT;
    }

    /**
     * Изменён ли файл снаружи после {@link #remember(Path)}.
     *
     * @param file файл плана
     * @return {@code true}, если версия отличается, файл исчез или наблюдение не удалось
     */
    public boolean changedExternally(Path file) {
        PlanStorage.Reference reference = file == null ? null : FilePlanStorage.reference(file);
        if (rememberedReference == null || reference == null || !rememberedReference.equals(reference)) {
            return false;
        }
        var current = storage.version(reference);
        return !current.succeeded() || PlanStorage.Version.ABSENT.equals(current.value())
                || !current.value().equals(rememberedVersion);
    }

    /** Забывает файл (план без файла). */
    public void forget() {
        rememberedReference = null;
        rememberedVersion = null;
    }
}
