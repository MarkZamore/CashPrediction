# Технический долг и ограничения для AI

Рабочий документ полного репозитория, не входит в source `.7z` или portable. Сверка ниже выполнена чтением текущего кода 2026-10-04, без запуска Maven/GUI. Ссылки на тесты удостоверяют наличие проверки в исходниках, а не её свежий успешный запуск. Документ не даёт разрешения на широкий рефакторинг, удаление fixtures или повышение статуса S5/native.

Назначения, актуальные отказы и результаты запусков находятся в [CurrentSprint.md](CurrentSprint.md). Минимальный контекст - в [ContextDump.md](ContextDump.md), согласованный scope - в [ChangeRequest.md](ChangeRequest.md). Не переносить устаревший статус из исторического сценария в текущий план.

## Что удалено, а что намеренно сохранено

| Область | Текущий факт и защита от неверного вывода |
| --- | --- |
| Выбор старого клиента | В [LaunchOptions](../../core/src/main/java/ru/cashprediction/core/app/LaunchOptions.java) нет выбора `UiMode`: `--ui` обрабатывается как неизвестный аргумент, `cashprediction.ui` не выбирает прежний UI. [LaunchOptionsTest](../../core/src/test/java/ru/cashprediction/core/app/env/LaunchOptionsTest.java), методы `removedUiArgumentIsUnknown`, `removedInlineUiArgumentIsUnknown`, `removedUiPropertyIsIgnored`, сохраняют этот контракт. Не возвращать переключатель ради старого workflow. |
| Старые классы/ресурсы клиента | [NoLegacyJarAudit](../../ui-parity/src/test/java/ru/cashprediction/parity/audit/NoLegacyJarAudit.java) проверяет собранные JAR: desktop allowlist, старые Web classes/resources, `LaunchOptions$UiMode`, multi-release записи и обязательный новый состав. Удалённый source не доказывает отсутствие stale `.class` в target/JAR. [NoLegacyModuleJarsTest](../../ui-parity/src/test/java/ru/cashprediction/parity/audit/NoLegacyModuleJarsTest.java) - отдельный gate реальных модулей; его актуальный PASS нельзя вывести из наличия аудитора. |
| Отрицательные fixtures | [NoLegacyJarAuditTest](../../ui-parity/src/test/java/ru/cashprediction/parity/audit/NoLegacyJarAuditTest.java) намеренно создаёт synthetic JAR со старым именем класса и даже текстом вместо bytecode. Это проверка фильтра, не найденный production legacy и не исполняемая сборка. Не удалять такие имена глобальной заменой. |
| Совместимость снимков | [LegacySnapshotCompatTest](../../core/src/test/java/ru/cashprediction/core/ui/legacy/LegacySnapshotCompatTest.java) и `core/src/test/resources/session/legacy` сохраняют исторические входы для восстановления. Поддержка данных старого снимка не означает поддержку старого клиента/транспорта. [PlanCellCodecSessionCompatibilityTest](../../core/src/test/java/ru/cashprediction/core/session/codec/PlanCellCodecSessionCompatibilityTest.java) отдельно проверяет cell codec. |
| Имена S7 | `.github/scripts/S7-*.ps1` и исторические receipts остаются именами кода/истории. В текущем плане тихое обновление входит в S5; rename не является необходимым исправлением и не подтверждает приёмку. |

## Текущие ограничения, не путать с неисправленным старым дефектом

### Файлы и версия

[FilePlanStorage](../../core/src/main/java/ru/cashprediction/core/service/storage/FilePlanStorage.java) сериализует операции своего экземпляра. `observe` учитывает атрибуты и SHA-256; `read` повторно проверяет версию после чтения; `write` передаёт `checkVersion` в repository publish guard, `rename` также проверяет source. Это уже реализованные защиты, а не TODO «добавить hash».

Оставшаяся граница: проверка непосредственно перед публикацией и замена не являются межпроцессным compare-and-swap. Сторонний редактор не обязан соблюдать локальную синхронизацию и может изменить существующий файл между последней проверкой и заменой. Не утверждать, что этот интервал исключён. Не пытаться исправить его записью поверх primary или удалением primary перед публикацией.

Усиленный staging/no-overwrite/scope уже отражён в [AtomicFiles](../../core/src/main/java/ru/cashprediction/core/io/AtomicFiles.java), [FilePlanStorageTest](../../core/src/test/java/ru/cashprediction/core/service/storage/FilePlanStorageTest.java) и [ScopedAtomicGuardTest](../../core/src/test/java/ru/cashprediction/core/io/ScopedAtomicGuardTest.java). Последний проверяет guard после полного staging, занятое имя после guard, конфликт rename после публикации и сохранение обеих копий. Это не доказательство универсального CAS; текущие исправления нельзя заново объявлять отсутствующими. Для изменения нужна отдельная согласованная модель конкуренции и воспроизводящий тест.

### Локальные службы и предметные команды

[LocalPlanCommands](../../core/src/main/java/ru/cashprediction/core/service/plan/LocalPlanCommands.java) рассчитан на один поток владельца. `RETRY_WINDOW=256`: завершённые запросы и исходные результаты хранятся только в памяти; после вытеснения снова действует `expectedRevision`, после FILE replacement окно очищается. Это не durable exactly-once, не сетевой transport и не распределённая транзакция. Локальные порты не делают модульный монолит готовой MSA.

Ошибка listener после записи плана/истории не означает rollback: `execute` возвращает `APPLIED` с `NOTIFICATION_FAILED`, если проект уже принят. См. [LocalPlanCommandsTest](../../core/src/test/java/ru/cashprediction/core/service/plan/LocalPlanCommandsTest.java): `boundedRetryWindowFallsBackToRevisionCheck`, `observerFailureAndReentrantCommandHaveExplicitResults`, `savingAndViewDoNotChangeRevisionButEqualReplacementDoes`. Не повторять принятую команду новым request ID из-за notification warning.

[ServiceBoundaryContractTest](../../core/src/test/java/ru/cashprediction/core/service/ServiceBoundaryContractTest.java) проверяет выбранный явный список портов и достижимые DTO, generic/constructor/throws/inherited signatures и скомпилированный module descriptor. Он не является аудитом всех классов проекта или всех runtime-вызовов. `ru.cashprediction.web.fixture.ClientControl` в test scope - намеренный отрицательный вход; не считать его production-зависимостью core от Web. Локальные адаптеры намеренно исключены из списка boundary DTO. При новом порте сначала проверить, входит ли он в проверяемый список.

### Захват и визуальное свидетельство

- В [FxUiDriver](../../ui-fx/src/main/java/ru/cashprediction/fx/ui/FxUiDriver.java) уже реализованы `captureDiagnostic` и строгий `capture`: неполный журнал или нестабильная синхронизация приводят к `UnsupportedCapture`, а не успешному `WidgetCapture`. [FxCaptureJournal](../../ui-fx/src/main/java/ru/cashprediction/fx/ui/FxCaptureJournal.java) использует публичные hooks; layout/snapshot не следует автоматически считать полным paint каждого узла или доказательством предъявления кадра пользователю. [FxUiDriverCaptureIntegrationTest](../../ui-fx/src/test/java/ru/cashprediction/fx/ui/FxUiDriverCaptureIntegrationTest.java) - контрактная проверка, не общий native signoff.
- В [SwingUiDriver](../../ui-swing/src/main/java/ru/cashprediction/swing/ui/SwingUiDriver.java) `capture` вызывает `captureDiagnostic(...).requireSupported(...)`; текущий стандартный root не имеет полного painter hooks согласно самому контракту метода. [SwingCaptureRepaintManager](../../ui-swing/src/main/java/ru/cashprediction/swing/ui/SwingCaptureRepaintManager.java) не превращает dirty regions/возврат paint в полный census. Диагностический raw/Robot PNG сохраняется в отказе, не становится успешным commit. См. [SwingDriverCaptureTest](../../ui-swing/src/test/java/ru/cashprediction/swing/ui/SwingDriverCaptureTest.java).
- В Web [retained-icon-sources.js](../../web/src/main/resources/web/app/retained-icon-sources.js) и [paint-observation.js](../../web/src/main/resources/web/app/paint-observation.js) относятся к источникам и наблюдениям. Retained PNG resource сам по себе не доказывает его экранное отображение, стабильность DOM/raw/PNG или доставку физического ввода. Нельзя подменять эти свидетельства HTML-моделью.

Не записывать общий долг «capture отсутствует»: существующая fail-closed реализация и оставшаяся полнота native-доказательства - разные вещи. [UiDriverCaptureContractTest](../../core/src/test/java/ru/cashprediction/core/ui/selftest/paint/UiDriverCaptureContractTest.java) и paint fixtures не разрешают повышать статус по одному synthetic результату. Актуальные GUI-отказы хранить в CurrentSprint, не угадывать их причину по одному снимку.

## Источники исторических требований и опасность DSL

Локальный `.claude/workflows/unified-ui-s0.js` хранит исходное ТЗ/поздние требования/ledger в строковых константах. Это часть происхождения решений, не действующий executable API и не единственный источник текущей истины. Локальный `.claude/workflows/unified-ui-s4.js` содержит старые phase/agent/parallel вызовы, ссылки на документы/legacy запуск и shell lock recipe с `rm -rf`. Эти исторические файлы не входят в Git или поставку и не требуются clean checkout. Прочитать сохранившиеся локальные копии как историю допустимо; выполнять их или копировать cleanup/overlay в текущую среду нельзя без нового review и явного назначения.

Расширение `.js` не делает сценарий обычным Node-приложением: требуемый host DSL здесь не предоставлен. Не добавлять Node/npm, не воссоздавать чужой orchestration runtime и не исполнять строки требований как shell. Старый lock recipe не заменяет текущую сериализацию Maven/desktop у MAIN; cleanup из истории не даёт разрешения удалять чужие файлы, процессы или незавершённые receipts.

Текущий scope задают прямое назначение пользователя, локальные инструкции `AGENTS.md` (если предоставлены средой), [PROJECT_REQUIREMENTS.md](../../PROJECT_REQUIREMENTS.md), актуальные документы и код. Локальные инструкции не являются файлом поставки или обязательной ссылкой clean checkout. Если историческое ТЗ/ledger расходится с текущим контрактом, фиксировать точное расхождение и согласовывать, а не молча возвращать legacy. Сохранять историю и scratch-предложения; не устранять неоднозначность их удалением.

## Границы updater-доказательств

[NativeUpdateRollbackAcceptance.ps1](../../.github/scripts/NativeUpdateRollbackAcceptance.ps1) явно ограничивает census write sites управляемым helper-кодом: `SINGLE_CELL_GUARDED_HELPER_CODE_NOT_OS_WIDE_TRACE`. Это не монитор всех записей Windows. Disk-full путь помечен `simulated-disk-full-IO-boundary`; физически заполненный диск таким тестом не доказан. См. [NativeUpdateRollbackScenarios.ps1](../../.github/scripts/NativeUpdateRollbackScenarios.ps1) и [его fixtures](../../.github/scripts/Test-NativeUpdateRollbackScenariosFixtures.ps1).

Успех mock/CLI/одной native-клетки не обобщается на полную матрицу. Готовый source patch не считается интегрированным; интегрированный source не считается скомпилированным; компиляция не доказывает GUI или опубликованный release. Normal two-delta, mixed/full fallback и bootstrap 0/1 retained bases - разные маршруты; fallback не является normal-two PASS. Использовать exact candidate/source/tool/artifact pins и фактическую область receipt; не смешивать epochs.

## Контракты, которые нельзя потерять при устранении долга

- Терпимое чтение Markdown и сохранение [RawBlock](../../core/src/main/java/ru/cashprediction/core/model/RawBlock.java) нужны для ручного исправления. [PlanMarkdownToleranceTest](../../core/src/test/java/ru/cashprediction/core/markdown/PlanMarkdownToleranceTest.java) проверяет неизвестные секции и параметры. Не заменять загрузку строгим конструктором, отбрасывающим файл целиком; строгое принятие новой команды и терпимая загрузка - разные границы.
- Совместимость snapshot относится к реальным полям/codes и исходным fixtures, не к переведённым заголовкам. Нельзя генерировать «старый» fixture новым writer и считать это независимой compatibility-проверкой.
- [FilePlanStorage](../../core/src/main/java/ru/cashprediction/core/service/storage/FilePlanStorage.java) различает `MISSING`, `CORRUPT`, `IO_ERROR`, конфликт версии и занятого имени. Недоступный primary нельзя считать пустым, стирать или использовать для незаметного fallback.
- Распознавание типографского минуса во вводе суммы не разрешает его в UI/записанных файлах. Это не разрешение вернуть совместимость файлов с запрещёнными тире или одиночный `-` как отсутствующее значение.
- [BootstrapFixture](../../core/src/test/java/ru/cashprediction/core/update/install/BootstrapFixture.java) сохраняет prepared поведение прежних конструкторов; `unpreparedLayout` - test-only сокращение setup для отказа reprepare, не production cache проверок. [BootstrapUnpreparedLayoutContractTest](../../core/src/test/java/ru/cashprediction/core/update/install/BootstrapUnpreparedLayoutContractTest.java) проверяет независимые физические bytes и контракты. Не переносить ускорение fixture в пропуск malicious cases или защит runtime.

## Порядок сопровождения

Перед изменением читать минимальный срез ContextDump и конкретный ChangeRequest. Для каждого риска записывать implementation location, воспроизводящий отказ и границу проверки. После исправления переносить его из текущего долга в закрытую историю с реальным свидетельством, не стирать первичный отказ и не называть один compile/test PASS полной приёмкой. Не исправлять соседние области без назначения; пользовательские данные, незакоммиченный задел и чужие результаты сохранять. Этот обзор не закрывает S4, S5, S6 или native approval.
