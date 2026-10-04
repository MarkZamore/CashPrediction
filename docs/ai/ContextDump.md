# Карта контекста CashPrediction для AI

Рабочий навигатор репозитория, не документация поставки. Не входит в `.7z`.
Читать нужный срез перед изменением; не анализировать весь проект повторно ради небольшой задачи.
Текущий статус и назначения живут только в [CurrentSprint.md](CurrentSprint.md).

## Устройство проекта

`core` содержит предметные данные, прогноз, службы, общий контроллер, UI-модели, хранение, восстановление и обновление.
`ui-fx`, `ui-swing` и `web` отрисовывают одно приложение. Бизнес-логику и тексты не дублируют в клиентах.
Каждый процесс владеет своим документом и контроллером; вкладки одного Web-сервера используют один контроллер.
SOA реализована локальными контрактами внутри модульного монолита, а не развёрнутыми микросервисами.

Пути пакетов ниже сокращены относительно `ru.cashprediction`; это навигация, а не новые модули.

| Область | Начальная точка чтения |
|---|---|
| Архитектура и владение | `docs/design/architecture.md`, [AppController](../../core/src/main/java/ru/cashprediction/core/app/AppController.java), [FlowContext](../../core/src/main/java/ru/cashprediction/core/app/flow/FlowContext.java), [UiPort](../../core/src/main/java/ru/cashprediction/core/app/UiPort.java) |
| Предметная модель и точность | `core.model`, `core.forecast`, `core.recurrence`, `core.diagnostics` |
| Команды, история, версии | [PlanCommands](../../core/src/main/java/ru/cashprediction/core/service/plan/PlanCommands.java), [LocalPlanCommands](../../core/src/main/java/ru/cashprediction/core/service/plan/LocalPlanCommands.java), [PlanDocument](../../core/src/main/java/ru/cashprediction/core/document/PlanDocument.java) |
| Файлы и конфликты | [FileFlow](../../core/src/main/java/ru/cashprediction/core/app/flow/FileFlow.java), [PlanStorage](../../core/src/main/java/ru/cashprediction/core/service/storage/PlanStorage.java), [ExternalChangeGuard](../../core/src/main/java/ru/cashprediction/core/app/flow/ExternalChangeGuard.java), `core.io.AtomicFiles` |
| Формы и восстановление | `core.ui.form.FormSession`, `core.session.SessionRecorder`, [StartupFlow](../../core/src/main/java/ru/cashprediction/core/app/flow/StartupFlow.java), [SessionStores](../../core/src/main/java/ru/cashprediction/core/app/flow/SessionStores.java), `core.session.codec`, `core.session.store` |
| Общий вид и тексты | `core.ui.menu`, `core.ui.view`, `core.ui.token`, `core.ui.text`, `core.text` |
| Клиенты | [FxApp](../../ui-fx/src/main/java/ru/cashprediction/fx/ui/FxApp.java), [SwingCoreMain](../../ui-swing/src/main/java/ru/cashprediction/swing/ui/SwingCoreMain.java), [CoreWebRuntime](../../web/src/main/java/ru/cashprediction/web/ui/CoreWebRuntime.java), `web/src/main/resources/web/app` |
| Тихое обновление | `core.update`, `update-tool`, `.github/scripts/S7-*.ps1`; историческое имя S7 не означает отдельного этапа |
| Сборка и приёмка | `pom.xml`, `.github/workflows`, `.github/scripts/Invoke-UiGates.ps1`, `ui-parity` |
| Поставка | `dist`, `dist/scripts/Pack-Source.ps1`, `Test-Pack-Source.ps1`, `Test-FinalDelivery.ps1` |

Для трассировки изменения начать с конструктора `AppController`: он создаёт `PlanDocument`,
`LocalPlanCommands`, `FilePlanStorage`, общий `ExternalChangeGuard` и сценарии `*Flow`.
`FileFlow(FlowContext)` получает тот же storage через `externalChanges().storage()`.
Событие документа возвращается в слушатель контроллера: обновляет recorder, автосохранение при изменении плана,
открытые формы и экран. Контракт `UiPort` связывает готовые модели и обратные вызовы с renderer;
обратные вызовы должны выполняться ровно один раз в потоке контроллера.
Для Web этот поток задаёт [ControllerThread](../../web/src/main/java/ru/cashprediction/web/ui/ControllerThread.java),
а не HTTP-поток или вкладка браузера. При ошибке привязки UI искать также соответствующий `*UiPort` клиента.

## Документы и минимальный контекст

Шесть технических документов поставляются разработчику в `docs/design`:
`architecture.md`, `techstack.md`, `edge-cases.md`, `db-schema.md`, `linx.md`, `ui-kit.md`.
Это точный список технических документов для source-архива, не разрешение включать всё `docs`.
Весь `docs/ai`, включая этот файл, `CurrentSprint`, `ChangeRequest` и `LegacyWarning`, остаётся рабочим контекстом
вне архива; пользовательская помощь, ресурсы и лицензии имеют отдельную роль и не становятся AI-документацией.

| Тип задачи | Читать прежде всего |
|---|---|
| Компонент или renderer | `ui-kit.md`, `architecture.md`, нужный раздел `docs/ui-spec.md` |
| Контракт службы или Web API | `architecture.md`, `db-schema.md`, нужный код контракта; для Web также `docs/ui-protocol.md` |
| Миграция хранения | `db-schema.md`, `techstack.md`, реальные compatibility fixtures |
| Ошибка | `edge-cases.md`, [ChangeRequest.md](ChangeRequest.md), исходный отчёт отказа |
| CI или упаковка | `techstack.md`, реальные workflow/scripts, отрицательные фикстуры |

Репозиторные спецификации: `docs/ui-spec.md` и его обязательная копия `docs/design/ui-spec-v2.md`,
`docs/FORMAT.md`, `docs/ui-protocol.md`, `docs/design/update-protocol.md`.
Приёмка: `docs/release-checklist.md`, `docs/parity-signoff.md`, `docs/update-signoff.md`.
Наличие документа, теста или runner не является доказательством успешного исполнения.

Минимальный срез для изменения: контракт, его реализация, реальный вызывающий сценарий и относящийся к нему тест.
Например, команды проверять вместе с `LocalPlanCommandsTest`, файловые операции - с
`core/src/test/java/ru/cashprediction/core/app/file/FileFlowStorageTest.java` и
`core/src/test/java/ru/cashprediction/core/app/flow/ExternalChangeGuardTest.java`,
запуск и восстановление - с `core/src/test/java/ru/cashprediction/core/app/session/startup/StartupFlowStorageTest.java`.
Имена тестов здесь только точки входа: результат и актуальность подтверждает receipt на соответствующий входной SHA.

## Правила и риски

Следовать `AGENTS.md`. Только JDK, OpenJFX и JUnit; Web без npm/CDN.
Инструменты разработки могут отличаться от зависимостей конечного пользователя.
Пользовательские записи ограничены `CashMemory` и разрешённым реестром; настоящие данные не использовать как фикстуры.
Аварийные снимки, Markdown вручную, конфликты стороннего редактора и updater требуют отдельных проверок.
При работе с путями читать [AppEnvironment](../../core/src/main/java/ru/cashprediction/core/app/AppEnvironment.java):
он проверяет допустимость `CashMemory` до writers, но сам не создаёт хранилища;
их подключает `StartupFlow` через `SessionStores`. Не смешивать восстановление сеанса и жизненный цикл обновления
(`core.update.lifecycle`): это разные контракты и разные свидетельства проверки.
Для поставки различать исходный checkout, извлечённое дерево source-архива и принятый portable-кандидат;
успех на старом SHA, mock-инструменте или другом дереве не доказывает сборку и native-приёмку текущей поставки.
Конкретные оставшиеся ограничения: [LegacyWarning.md](LegacyWarning.md); не исправлять их несогласованным рефакторингом.

Для каждого изменения фиксировать воспроизводящий отказ, назначенную область и соразмерную проверку.
Не выполнять commit/push из субагентов. Ведущий интегрирует результаты и проверяет один неизменяемый кандидат.
При завершении этапа обновлять статус по фактическим свидетельствам, а не по памяти диалога.
