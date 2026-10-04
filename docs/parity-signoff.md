# Подготовка S6: паритет и приёмка

Статус: NOT RUN / PENDING. Подготовлено 03.10.2026; ссылки и автоматические доказательства S4 актуализированы 04.10.2026.
Это план будущего S6 прогона, не подпись приёмки.
Коммит кандидата: PENDING. Дата прогона, исполнитель, Windows/JDK/Maven/браузер/оборудование: PENDING.
Исходники, jar, goldens и спецификация: SHA256 PENDING. Команды: docs/release-checklist.md.
Автоматический runner: .github/scripts/Invoke-S6Verification.ps1, clean checkout + точный ExpectedCommit,
preflight требует portable -Parity. Его receipt AUTOMATED_ONLY / independentSignoff=false не подписывает S6.
Receipt target/s6/<UUID>/automatic-results.json: NOT RUN / PENDING; strictUI/strictE2E/profile evidence: PENDING.
Существующие отчёты S3/S4/S5 не являются доказательствами нового S6. Разделы ниже отдельно фиксируют фактически выполненные MAIN автоматические проверки и незавершённую финальную приёмку.

Для каждой строки сохранить: точную команду/ручные действия, код выхода, длительность, абсолютный путь доказательства,
SHA256 источника и jar, фактический результат каждого клиента, автора просмотра, ссылку на дефект и владельца.
OK допустим только после проверки артефактов замороженного кандидата. Пропуск/недоступное окружение остаётся PENDING,
ошибка становится DEFECT. Недостающий PNG не заменять модельным. При изменении кандидата результаты инвалидировать.

## S4 automated evidence

Свежий root install после исправления физического menu hover завершён 04.10.2026 в 11:18:02 (UTC+05:00): exit 0, семь модулей SUCCESS, 3059 случаев, 0 failures/errors, 133 skipped. Журнал: `C:/Users/Oscar/AppData/Local/Temp/cp-s4-menu-hover-install-62995a48-2b35-494e-9319-d953f6cac8cf/install.log`. Пропуски обычного install не считаются исполненными GUI/security проверками; в частности, проверка symlink в PowerShellHelperTest пропущена из-за недоступных прав Windows. Новая FxMenuHoverTest в install пропущена по opt-in свойству и выполняется отдельно в строгом UI/E2E gate.

Новый UI gate `4fba3c81-d67d-47ec-a707-fe68a7329599` завершён на свежих JAR: 29 suites, 291 cases, 0 failures/errors/skips. Проверены все 18 сценариев для fx/swing/web, 221 горячая клавиша, экземпляры 23 обязательных классов и реальные screenshots. Родительская очередь перешла к E2E только после успешного возврата всех UI шагов. E2E gate `6d5d5b23-718a-4cf9-b869-1a5dde1521ef` завершён: 21 suite, 72 cases, 0 failures/errors/skips; счётчики повторно проверены по TEST-*.xml в reports-* этого gate. UI actual/parity/fresh-run.json закрепляет входы: core `a357c2455ac18885946ad86381d333669e2b0e05f2c9cb7c698e7d690b8dc786`, JavaFX `9743a7cb4699a2a941f0d0c69c4ae24cbeaed19b6ce353b97414db4ec2e1c470`, Swing `e745959c7a802c804dd10c884597890129ec759deecffea3fcfe9721526789eb`, Web `f381eb16eb37aa9aae7d86d4a7d2ec2bd99a14a52616a54fdde95cbfa7611cc4`.

Следующая сборка dist завершилась BUILD SUCCESS 04.10.2026 в 12:04:53 (UTC+05:00). Последовательная очередь MAIN завершилась с exit 0. Реальная portable-проверка прошла все девять комбинаций fx/swing/web и ASCII/кириллического/Unicode-пути: s02-sample-table, пять checkpoints, crash/restore, registryClean и processesClean. Доказательства: `C:/Users/Oscar/AppData/Local/Temp/cp-portable-evidence-779e8abc-c4a4-4a66-ae7f-dd689f33a3f2/run-5f772e2b-a411-4fdf-9118-ab1a113e77c9`. Журнал dist: `C:/Users/Oscar/AppData/Local/Temp/cp-s4-menu-hover-chain-ea09ccf9-eca0-4639-9b64-66790e0e649e/dist.log`. Это автоматические доказательства S4, а не завершение S5/S6/S7, опубликованный CI или окончательная релизная сборка.

Срез существующих доказательств: 04.10.2026, время Asia/Qyzylorda (UTC+05:00).
Основания: [критерии S4](design/stages.md),
[журнал текущей интеграции](../.claude/scratch/verification-2026-10-04-current.md)
и прочитанные XML ниже. Свежая цепочка проверена напрямую по install.log, UI.log и gate reports;
для исторического root install 68555 также проверен terminal receipt ниже.
Время завершения и exit остальных запусков приведены по журналу; XML подтверждают счётчики,
но сами не доказывают exit всего gate или соответствие последним изменённым исходникам.
Это автоматические результаты S4 на своих наборах JAR, не final done и не приёмка S5/S6.
Все таблицы NOT RUN / PENDING ниже относятся к будущему S6 и сохраняют свои требования.

Последний полный root install в цепочке `cp-s4-chain-f9d97316-cea5-4875-b392-bf31f1a6b371`:
[install.log](C:/Users/Oscar/AppData/Local/Temp/cp-s4-chain-f9d97316-cea5-4875-b392-bf31f1a6b371/install.log),
04.10.2026 08:56:33 +05:00, Maven total time 23:09 min, BUILD SUCCESS всех 7 модулей.
Точные итоговые строки Results каждого тестируемого модуля прочитаны из этого лога:

| Модуль свежего root reactor | Reactor result | Tests run | Skipped | Failures / Errors |
|---|---|---|---|---|
| parent | SUCCESS | нет тестов | нет тестов | нет тестов |
| core | SUCCESS | 2398 | 10 | 0 / 0 |
| update-tool | SUCCESS | 24 | 1 | 0 / 0 |
| ui-fx | SUCCESS | 247 | 116 | 0 / 0 |
| ui-swing | SUCCESS | 226 | 5 | 0 / 0 |
| web | SUCCESS | 135 | 0 | 0 / 0 |
| repository-doc-audits | SUCCESS | 28 | 0 | 0 / 0 |
| Итого: все 7 модулей SUCCESS | BUILD SUCCESS | 3058 | 132 | 0 / 0 |

Это обычный root reactor, не `ui-tests`/`ui-parity` и не full3client UI/E2E.
132 пропуска не считаются PASS. Этот результат относится только к входам своего запуска,
не подтверждает последующие dirty изменения и не закрывает приёмку этапов.

Свежий UI gate [2fed0324-3ec0-4293-88dd-43b12f1f61a8](../ui-parity/target/gates/2fed0324-3ec0-4293-88dd-43b12f1f61a8/): FAIL на шаге 04,
`GateCoverageDesktopTest.actualDesktopFocusRoundTrip`. До отказа NoLegacyJarAuditTest 15/15,
NoLegacyModuleJarsTest 1/1 и BrowserProfileOwnershipTest 2/2 прошли без failures/errors/skips:
[audit XML](../ui-parity/target/gates/2fed0324-3ec0-4293-88dd-43b12f1f61a8/reports-01-NoLegacyJarAuditTest/TEST-ru.cashprediction.parity.audit.NoLegacyJarAuditTest.xml),
[module XML](../ui-parity/target/gates/2fed0324-3ec0-4293-88dd-43b12f1f61a8/reports-02-NoLegacyModuleJarsTest/TEST-ru.cashprediction.parity.audit.NoLegacyModuleJarsTest.xml),
[ownership XML](../ui-parity/target/gates/2fed0324-3ec0-4293-88dd-43b12f1f61a8/reports-03-BrowserProfileOwnershipTest/TEST-ru.cashprediction.parity.browser.BrowserProfileOwnershipTest.xml).
BrowserProfileOwnershipTest здесь проверяет контракт владения browser fixture, не реальные три клиента.
Шаг 04: tests 1, failures 1, errors 0, skipped 0; suite time 10.336 s, Maven BUILD FAILURE
04.10.2026 08:56:57 +05:00, total time 11.730 s:
[XML](../ui-parity/target/gates/2fed0324-3ec0-4293-88dd-43b12f1f61a8/reports-04-GateCoverageDesktopTest/TEST-ru.cashprediction.parity.audit.GateCoverageDesktopTest.xml),
[step log](../ui-parity/target/gates/2fed0324-3ec0-4293-88dd-43b12f1f61a8/upload/GateCoverageDesktopTest.log).
Ошибка: `Actual pointer did not reach Swing field`. В
[actual preflight report](../ui-parity/target/gates/2fed0324-3ec0-4293-88dd-43b12f1f61a8/actual/parity/desktop-preflight-5388361374462581000/report-preflight.txt)
наблюдены `actualPointer=null`, `pointerHit=false`, AWT `focused=true`, transform 2.0;
`windowsForeground=not independently observed (AWT focus only)`, `deliveryElapsedMillis=not started`.
Это Swing desktop preflight fixture, не успешный whole-app FX/Swing/Web прогон;
причина недоставленного pointer по этим данным не установлена.

[UI.log цепочки](C:/Users/Oscar/AppData/Local/Temp/cp-s4-chain-f9d97316-cea5-4875-b392-bf31f1a6b371/UI.log)
содержит только `Gate redaction fixtures: PASS`, не результат UI gate; отказ подтверждён step reports выше.
[Fresh run/JAR hashes](../ui-parity/target/gates/2fed0324-3ec0-4293-88dd-43b12f1f61a8/actual/parity/fresh-run.json)
привязывают попытку к её четырём входным JAR. UI шаги 05 и далее, включая fresh allowance,
Parity/Hotkey/ClassUsage/Visual и итоговую coverage, в этой цепочке NOT RUN;
следующий E2E gate NOT RUN, E2E.log и его fresh receipts в каталоге цепочки отсутствуют.
Fresh UI/E2E signoff и delivery остаются PENDING, а не PASS. Исторические scoped доказательства ниже сохраняются.

Последующий focused повтор того же неизменённого `GateCoverageDesktopTest.actualDesktopFocusRoundTrip`
в текущей Session2 прошёл: tests 1, failures 0, errors 0, skipped 0,
[scoped XML](C:/Users/Oscar/AppData/Local/Temp/cp-desktop-preflight-c9596672-a241-414f-82a5-d02629d5bc91/reports/TEST-ru.cashprediction.parity.audit.GateCoverageDesktopTest.xml),
[text report](C:/Users/Oscar/AppData/Local/Temp/cp-desktop-preflight-c9596672-a241-414f-82a5-d02629d5bc91/reports/ru.cashprediction.parity.audit.GateCoverageDesktopTest.txt).
Завершение 04.10.2026 09:06:43 +05:00 и идентичность неизменённого теста сообщены MAIN;
счётчики scoped PASS проверены напрямую в отчётах. Этот повтор не исправляет задним числом
FAIL gate 2fed0324... в Session1. Причина могла зависеть от окружения, но это не доказано.
По сообщению MAIN новый полный UI gate запускается последовательно; его итог PENDING до собственных
fresh reports. Один focused preflight PASS не подтверждает полный UI, E2E или stage signoff.

Ранее успешный полный root install session 68555: команда `mvn -B install` из корня проекта,
04.10.2026 05:57:26 +05:00, Maven total time 24:52 min, exit 0, BUILD SUCCESS.
Проверен [исходный terminal receipt](C:/Users/Oscar/.codex/sessions/2026/09/16/rollout-2026-09-16T19-15-56-01a0aa93-04a2-7871-adff-5babdb8ce458.jsonl:32273):
`item_completed`, `process_id=68555`, `exec-5eb1147a-db76-424b-8354-dde8b0cc5c2d`,
`status=completed`, `exit_code=0`; восстановление подтверждено текущим журналом.
Это собственный terminal полного reactor, не вывод по одним XML и не сумма resume.

| Модуль полного root reactor | Reactor result | Tests run | Skipped | Failures / Errors |
|---|---|---|---|---|
| parent | SUCCESS | нет тестов | нет тестов | нет тестов |
| core | SUCCESS | 2393 | 10 | 0 / 0 |
| update-tool | SUCCESS | 24 | 1 | 0 / 0 |
| ui-fx | SUCCESS | 247 | 116 | 0 / 0 |
| ui-swing | SUCCESS | 226 | 5 | 0 / 0 |
| web | SUCCESS | 135 | 0 | 0 / 0 |
| repository-doc-audits | SUCCESS | 28 | 0 | 0 / 0 |
| Итого: все 7 модулей SUCCESS | BUILD SUCCESS | 3053 | 132 | 0 / 0 |

Область этого доказательства: обычный root reactor и его unit/integration/audit тесты
для исходников и артефактов того запуска. Профиль `ui-tests` не включён, модуль `ui-parity`
не входит в обычный reactor. Этот SUCCESS не доказывает свежий full3client UI gate,
browser fixture не подменяет реальные FX/Swing/Web, а пропущенные проверки не считаются PASS.
Новые изменения кандидата этим receipt автоматически не покрываются.

UI gate session 1809 завершён 04.10.2026 к записи 02:19, exit 0:
[корень gate](../ui-parity/target/gates/672025a8-79c2-48df-877b-11cbc91bd6f7/).
Все 28 групп без FAIL/ERROR/SKIP. Идентичность запуска и хеши входных JAR:
[provenance](../ui-parity/target/gates/672025a8-79c2-48df-877b-11cbc91bd6f7/fresh-evidence/provenance.json),
[evidence manifest](../ui-parity/target/gates/672025a8-79c2-48df-877b-11cbc91bd6f7/fresh-evidence/evidence-manifest.json).

| Проверка | Фактический автоматический результат | Доказательство |
|---|---|---|
| ParityTest | 18 сценариев на реальных FX/Swing/Web; suite 1/1, без пропусков | [XML](../ui-parity/target/gates/672025a8-79c2-48df-877b-11cbc91bd6f7/upload/ParityTest.xml) |
| HotkeyParityTest | 221/221, без пропусков; не заменяет физическую клавиатуру и keyboard-only walkthrough | [XML](../ui-parity/target/gates/672025a8-79c2-48df-877b-11cbc91bd6f7/upload/HotkeyParityTest.xml) |
| ClassUsageTest | 2/2, включая realFxScenariosInstantiateRequired23; реальная перепись FX 23 классов, не полный независимый просмотр всех mapping sites | [XML](../ui-parity/target/gates/672025a8-79c2-48df-877b-11cbc91bd6f7/upload/ClassUsageTest.xml) |
| VisualParityTest | 3/3, включая реальные screenshots; базовая визуальная проверка S4, не усиленная S5 paint/latency приёмка | [XML](../ui-parity/target/gates/672025a8-79c2-48df-877b-11cbc91bd6f7/upload/VisualParityTest.xml) |
| NoLegacyJarAuditTest / NoLegacyModuleJarsTest | 15/15 и 1/1, без пропусков; аудит того набора JAR | [Jar audit](../ui-parity/target/gates/672025a8-79c2-48df-877b-11cbc91bd6f7/upload/NoLegacyJarAuditTest.xml), [module audit](../ui-parity/target/gates/672025a8-79c2-48df-877b-11cbc91bd6f7/upload/NoLegacyModuleJarsTest.xml) |
| CrashRestoreE2ETest / AlreadyRunningE2ETest | 20/20 и 2/2 на реальных desktop процессах, без пропусков; весь gate session 4068 завершился exit 1 из-за UnsavedPlanRestore | [CrashRestore XML](../ui-parity/target/gates/ab233443-8e58-4b05-a1f8-2a045dedab5b/upload/CrashRestoreE2ETest.xml), [AlreadyRunning XML](../ui-parity/target/gates/ab233443-8e58-4b05-a1f8-2a045dedab5b/upload/AlreadyRunningE2ETest.xml) |
| UnsavedPlanRestoreE2ETest, исправленный scoped повтор | Session 5904: 04.10.2026 02:36:01, exit 0, 6/6, без пропусков; исправлено ожидание теста немедленной err.save, поведение продукта не менялось | [XML](../ui-parity/target/surefire-reports/TEST-ru.cashprediction.parity.e2e.interaction.UnsavedPlanRestoreE2ETest.xml) |
| WebCrashRestoreE2ETest, отдельный повтор | Session 70800: 04.10.2026 02:37:28, exit 0, 1/1, без пропусков; реальный Web crash/reconnect/restore | [XML](../ui-parity/target/surefire-reports/TEST-ru.cashprediction.parity.e2e.web.WebCrashRestoreE2ETest.xml), [raw/log evidence](../ui-parity/target/web-crash-regression-20261004/parity/) |

Scoped XML в surefire-reports могут быть перезаписаны. SHA256 прочитанных результатов:
UnsavedPlanRestore `223B4FB6395F1B6A0A853EB75C2606895AAF6E78F4744FBF52B939816E072672`;
WebCrashRestore `EFE8297CB1F457127926B9CF2C11202D797B683A258549B6F9BBB2E8E19FF6D0`.
Unit/codec/widget проверки не подменяют перечисленные реальные клиентские прогоны;
запуск клиентских JVM не доказывает поведение трёх portable EXE.

Остаток S4 для окончательного кандидата: свежий UI gate `-Pui-tests verify` на реальных FX/Swing/Web,
цельный успешный E2E gate после исправлений, привязка source/JAR/goldens к одному frozen SHA,
гигиена итогового прогона (собственные процессы и selftest UUID удалены, настоящие session неизменны),
фактический зелёный CI на PR этого SHA. Ранее успешные компоненты не объявлять отсутствующими;
их результаты не переносить автоматически на изменённый кандидат. Исторический root install 89895
завершился exit 1; успешные resume 5320/26201 сами по себе не являлись зелёным полным reactor.
Этот прежний FAIL не является последним результатом root install: полный 68555 и более свежий
install цепочки, завершённый 08:56:33, подтверждены SUCCESS выше; их восстановление не требует повтора.
UI попытка 2fed0324... в Session1: исторический FAIL на desktop preflight шаге 04;
её последующие UI шаги и E2E NOT RUN. Focused повтор в Session2: PASS 1/1, без пропусков;
новый полный UI gate MAIN имеет статус PENDING до собственных fresh reports.
Успешный fresh UI / цельный E2E / delivery (portable parity и реальный CI окончательного кандидата): PENDING.
Root SUCCESS не закрывает эти gates, не меняет статусы S5/S6 и не означает final done.
BrowserProfileOwnership, FxCleanExit и LaunchedClientReaper имеют частичные успешные доказательства,
но не заменяют итоговую гигиену всего нового прогона.

Portable `-Parity` и `PortableExeE2ETest` реализованы:
[Test-Portable.ps1](../.github/scripts/Test-Portable.ps1),
[PortableExeE2ETest](../ui-parity/src/test/java/ru/cashprediction/parity/portable/PortableExeE2ETest.java).
Их наличие не означает успешный запуск: требуется свежий реальный `-Parity` на окончательном образе.
Записанный 04.10.2026 smoke session 85359 (9 сочетаний пути и EXE) проверял обычный запуск без parity;
он не закрывает S5/S6 portable parity/restore. Семь ручных recovery, масштабы, physical input,
усиленный paint и domain latency остаются отдельными требованиями S5/S6 со статусами ниже.

## Все 18 сценариев

Каждая строка требует дампы и реальные PNG FX/Swing/Web всех checkpoint, сравнение с goldens и docs/ui-spec.md.
Базовые пути: ui-parity/target/parity/report.html; visual-<UUID>/report.html и его ссылки на run/out.
В столбце доказательств ниже указана ожидаемая область, не существующий результат.

| Сценарий | FX | Swing | Web | Просмотр спецификации / доказательства |
|---|---|---|---|---|
| s01-first-run | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s01-first-run, все шаги |
| s02-sample-table | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s02-sample-table, все шаги |
| s03-chart | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s03-chart, все шаги |
| s04-context-menus | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s04-context-menus, все шаги |
| s05-forms-plan | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s05-forms-plan, все шаги |
| s06-forms-ops | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s06-forms-ops, все шаги |
| s07-forms-misc | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s07-forms-misc, все шаги |
| s08-alerts | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s08-alerts, все шаги |
| s09-whatif | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s09-whatif, все шаги |
| s10-filter-empty-states | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s10-filter-empty-states, все шаги |
| s11-past-group-reveal | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s11-past-group-reveal, все шаги |
| s12-quick-edit | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s12-quick-edit, все шаги |
| s13-undo-redo | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s13-undo-redo, все шаги |
| s14-save-conflicts | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s14-save-conflicts, все шаги |
| s15-keyboard | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s15-keyboard, все шаги |
| s16-exit-dirty | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s16-exit-dirty, все шаги |
| s17-recovery-dialog | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s17-recovery-dialog, все шаги |
| s18-already-running | NOT RUN | NOT RUN | NOT RUN | PENDING: report + visual/s18-already-running, все шаги |

## Спецификация §1..§10

Каждый верхний пункт раскрывается до всех подпунктов; отсутствие отдельного доказательства любого подпункта блокирует OK.

| Раздел | Обязательный охват | Статус | Доказательства |
|---|---|---|---|
| §1 | 1.1..1.5: цвета, шрифты, размеры, значки, запрещённые тире и минус | NOT RUN | PENDING |
| §2 | Главное окно, минимум 900x600 | NOT RUN | PENDING |
| §3 | 3.1..3.6: все меню, порядок, доступность, checked, сочетания | NOT RUN | PENDING |
| §4 | Тулбар, split/menu, период и фильтры | NOT RUN | PENDING |
| §5 | 5.1..5.6, 5.6.1..5.6.5; карточки, строки/итоги/группа прошлого, tooltip, chart, status, контекст, popup | NOT RUN | PENDING |
| §6 и §6A | 6.0..6.33: каждая форма/alert/chooser, поля, ошибки, кнопки, потоки, восстановление | NOT RUN | PENDING |
| §7 | Все chords/scopes, desktop/web, русская раскладка | NOT RUN | PENDING |
| §8 | 8.1..8.9: все тексты, время статуса, restore/errors/buttons | NOT RUN | PENDING |
| §9 | Реальная полная матрица, соответствие dumps/goldens | NOT RUN | PENDING |
| §10 | Только закрытые allowances; свежие доказательства каждого исключения | NOT RUN | PENDING |

Подпункты §6 проверять отдельно, включая customCurrency внутри соответствующего контракта, вложенные окна,
invalid raw input, clientRev, порядок кнопок и восстановленные размеры. Имена текущих заголовков брать из
docs/ui-spec.md, не из исторических описаний workflow.

## Перепись 23 классов и соответствий

ClassUsageTest: все 18 сценариев + DirectoryChooserProbe, положительные реальные counts,
правильные metadata каждого dump, один OK на каждую команду и финальный SELFTEST DONE.
23/23 при ошибке сценария не означает успешную перепись. Для каждой строки проверить использование по назначению,
доступ из меню и комментарии каждого места создания в FX/Swing/Web. Аналоги ниже - ориентиры для проверки кода.

| JavaFX | Swing / Web: проверить фактический аналог | Count / назначение / меню | Mapping comments / путь |
|---|---|---|---|
| MenuBar | JMenuBar / nav | NOT RUN | PENDING |
| Menu | JMenu / menu | NOT RUN | PENDING |
| MenuItem | JMenuItem / item | NOT RUN | PENDING |
| CheckMenuItem | JCheckBoxMenuItem / checked item | NOT RUN | PENDING |
| RadioMenuItem | JRadioButtonMenuItem / radio item | NOT RUN | PENDING |
| SeparatorMenuItem | JSeparator / separator | NOT RUN | PENDING |
| CustomMenuItem | компонент меню / custom menu content | NOT RUN | PENDING |
| MenuButton | кнопка меню / button + menu | NOT RUN | PENDING |
| SplitMenuButton | split-кнопка / split button | NOT RUN | PENDING |
| PopupWindow | JWindow / floating div | NOT RUN | PENDING |
| Popup | PopupFactory / popup div | NOT RUN | PENDING |
| PopupControl | popup-компонент / popup control | NOT RUN | PENDING |
| Tooltip | JToolTip / tooltip | NOT RUN | PENDING |
| ContextMenu | JPopupMenu / context menu | NOT RUN | PENDING |
| ContextMenuEvent | MouseEvent / contextmenu | NOT RUN | PENDING |
| Dialog<R> | JDialog / dialog | NOT RUN | PENDING |
| DialogPane | JPanel / dialog content | NOT RUN | PENDING |
| ButtonType | кнопка результата / dialog button | NOT RUN | PENDING |
| Alert | сообщение JDialog / alert dialog | NOT RUN | PENDING |
| TextInputDialog | форма ввода / input dialog | NOT RUN | PENDING |
| ChoiceDialog<T> | форма выбора / choice dialog | NOT RUN | PENDING |
| DirectoryChooser | JFileChooser directory / FileBrowserForm | NOT RUN | PENDING |
| FileChooser | JFileChooser / FileBrowserForm | NOT RUN | PENDING |

## Семь ручных путей восстановления

Каждый путь в отдельной копии exe/home и собственном selftest UUID. Перед crash: изменённый план,
вложенный RuleEditor/Adjustment/Goal, invalid input и bounds; доказать запись обоих хранилищ.
Завершить всё дерево лаунчера; перезапустить тот же exe/home/node, выбрать источник вручную и сравнить
значения/контекст/порядок/bounds с before. Автотест recovery не заменяет ручной проход.

| Путь | Что доказать | Статус | Before / after / журнал |
|---|---|---|---|
| FX / registry | Выбор реестра, восстановление всех окон | NOT RUN | PENDING |
| FX / XML | Выбор XML, восстановление всех окон | NOT RUN | PENDING |
| Swing / registry | Выбор реестра, восстановление всех окон | NOT RUN | PENDING |
| Swing / XML | Выбор XML, восстановление всех окон | NOT RUN | PENDING |
| Web / server | Убить JVM сервера, запустить снова, восстановить серверное состояние и reload | NOT RUN | PENDING |
| Отказ от восстановления | Оба доступных хранилища очищены только в собственной копии | NOT RUN | PENDING |
| Повреждённый registry / исправный XML | Повредить только собственный узел; XML восстанавливается; реальные session не изменены | NOT RUN | PENDING |

## Масштаб, клавиатура, Web и быстродействие

| Проверка | Статус | Доказательства |
|---|---|---|
| FX 100% | NOT RUN | PENDING |
| FX 125% | NOT RUN | PENDING |
| FX 150% | NOT RUN | PENDING |
| Swing 100% | NOT RUN | PENDING |
| Swing 125% | NOT RUN | PENDING |
| Swing 150% | NOT RUN | PENDING |
| FX keyboard-only s01/s05/s06/s15, видимый focus, без мыши | NOT RUN | PENDING |
| Swing keyboard-only s01/s05/s06/s15, видимый focus, без мыши | NOT RUN | PENDING |
| Web keyboard-only s01/s05/s06/s15; Edge и Chrome если установлен | NOT RUN | PENDING |
| HotkeyParityTest: все клиенты/chords, русская раскладка, disabled, ровно один dispatch | NOT RUN | PENDING |
| Desktop 900x600, все диалоги на 1366x768, glyphs, русские chooser | NOT RUN | PENDING |
| Web 400px и 1920px: весь интерфейс без overflow/clipping | NOT RUN | PENDING |
| Web reload в диалоге, clipboard, tooltip, file browser, две вкладки, back/forward | NOT RUN | PENDING |
| Web stop/crash: экраны не позднее 30 секунд | NOT RUN | PENDING |
| 600 месяцев weekly: открыть <2 с; filter-to-render <100 мс у каждого клиента | NOT RUN | PENDING |
| Индекс около 200000 строк <300 мс, средний fetch <1 мс | NOT RUN | PENDING |
| WebVirtualTablePerformanceTest: 200000 synthetic paged rows, 18 cold samples, <50 мс, 400px iframe | NOT RUN | PENDING |
| Разделить synthetic table benchmark и настоящий domain/full-app performance | NOT RUN | PENDING |

Harness масштаба запускается при uiScale=1 и Web DPR=1: visual gate 1200x800 не доказывает 125/150%.
Для масштабов нужны отдельные реальные настройки экрана, записи effective DPI/DPR и PNG без пересчёта.
Историческое руководство hotkey содержит ограничения нативной русской раскладки; проверить реальные события,
не считать синтетический DOM dispatch доказательством физической клавиатуры.

## Требования, ledger и гигиена

| Проверка | Статус | Доказательства |
|---|---|---|
| TZ: бюджет, даты доходов/расходов, любой горизонт, повторения, разовые, корректировки, график и накопления | NOT RUN | PENDING |
| USER_REQS: общие тексты, core logic, одинаковый UI, русский без переключателя | NOT RUN | PENDING |
| USER_REQS: запрет U+2013/U+2014/U+2212 и одиночного дефиса; файлы/fixtures/grammar без legacy exceptions | NOT RUN | PENDING |
| Стек JDK25/OpenJFX/JUnit, JDK HttpServer/CDP, HTML/CSS/JS; внешних production libs/Node/CDN нет | NOT RUN | PENDING |
| Русские Javadoc каждого класса/public API, JSDoc каждой функции, mapping comments всех sites | NOT RUN | PENDING |
| Markdown: читабельность и round-trip настоящих файлов CashMemory | NOT RUN | PENDING |
| Прочитать TZ/USER_REQS/LEDGER unified-ui-s0.js; L1/L1b/L2..L13 каждый с отдельным доказательством | NOT RUN | PENDING |
| Документы README/BUILD/FORMAT/architecture/protocol соответствуют коду; команды проверены | NOT RUN | PENDING |
| CI/public repo/latest ZIP + release.json; Invoke-Gh повторы 429/5xx | NOT RUN | PENDING |
| Изолированный чистый кандидат; default/ui-tests/e2e/dist матрица и длительности | NOT RUN | PENDING |
| Registry до/после: настоящие session и чужие selftest неизменны; собственные UUID удалены | NOT RUN | PENDING |
| Нет собственных JVM/browser/helper, профилей и locks; все evidence сохранены | NOT RUN | PENDING |
| Source/jar SHA freeze до/после; git status кандидата чист; исходная грязь и stashes сохранены | NOT RUN | PENDING |
| Portable три exe, три вида путей; только CashMemory и собственный registry; readonly/SHA дерева | NOT RUN | PENDING |
| Portable -Parity и PortableExeE2ETest: реализация есть (см. S4 automated evidence); подтвердить свежий реальный parity/restore на окончательном образе | NOT RUN | PENDING |
| Root delivery после S6/S7: финальный .7z, фильтры, извлечение/install, SHA и отсутствие generated/user data | NOT RUN | PENDING |

Открытая приёмка: PENDING по всем строкам. Дефекты будущего прогона: PENDING (не проверены).
S6 GREEN / подпись / коммит: PENDING. По последующему разрешению пользователя S7 реализуется параллельно с S4-S6; окончательная S7 приёмка и доставка остаются PENDING, публикация требует завершённых S6/S7.
## Fresh UI gate 04.10.2026

MAIN запустил `.github/scripts/Invoke-UiGates.ps1 -Mode UI`. Запуск `00892b4c-8be5-4273-afa2-d4d14203ced0` завершился с exit 0; последний шаг закончился 09:46:16 +05:00. Прямо проверены 28 свежих XML suites: 290 тестов, 0 failures, 0 errors, 0 skipped. Это результат UI gate, не полной S5/S6/S7 или конечной доставки.

- [ParityTest](../ui-parity/target/gates/00892b4c-8be5-4273-afa2-d4d14203ced0/reports-22-ParityTest/TEST-ru.cashprediction.parity.pipeline.ParityTest.xml): 18 сценариев у каждого из FX/Swing/Web, 683.193 s, PASS.
- [HotkeyParityTest](../ui-parity/target/gates/00892b4c-8be5-4273-afa2-d4d14203ced0/reports-23-HotkeyParityTest/TEST-ru.cashprediction.parity.check.HotkeyParityTest.xml): 221 тест, 1015.743 s, PASS без пропусков.
- [ClassUsageTest](../ui-parity/target/gates/00892b4c-8be5-4273-afa2-d4d14203ced0/reports-24-ClassUsageTest/TEST-ru.cashprediction.parity.check.ClassUsageTest.xml): 2 теста, обязательная перепись 23 классов, PASS.
- [VisualParityTest](../ui-parity/target/gates/00892b4c-8be5-4273-afa2-d4d14203ced0/reports-25-VisualParityTest/TEST-ru.cashprediction.parity.check.VisualParityTest.xml): 3 теста, PASS. Не заменяет полный ручной UX-прогон S5.
- [GateCoverageTest](../ui-parity/target/gates/00892b4c-8be5-4273-afa2-d4d14203ced0/reports-28-GateCoverageTest/TEST-ru.cashprediction.parity.audit.GateCoverageTest.xml): полнота собственных наблюдений этого запуска, PASS.

Исторический отказ desktop preflight в `2fed0324...` сохраняется как отдельная неуспешная попытка. Новый E2E gate завершился успешно; результат приведён ниже. S4 не объявляется полностью завершённым до проверки актуальной портативной сборки, фиксации проверенного источника и CI; S5-изменение минимальной ширины карточки ещё не применено к этой сборке.

## Fresh E2E gate 04.10.2026

MAIN запустил `.github/scripts/Invoke-UiGates.ps1 -Mode E2E` после завершения UI, на тех же production JAR. Запуск `7bd59882-0d93-434c-bda9-f0dc2d25dca2` завершился с exit 0 в 09:58:05 +05:00. Прямо проверены 20 XML suites: 71 тест, 0 failures, 0 errors, 0 skipped.

- CrashRestoreE2ETest: 20 тестов, PASS; отдельное восстановление настольных клиентов из registry и XML.
- AlreadyRunningE2ETest: 2 теста, PASS.
- UnsavedPlanRestoreE2ETest: 6 тестов, PASS.
- WebCrashRestoreE2ETest: 1 тест, PASS.
- Предварительные проверки и native startup rescue FX/Swing также входят в 71 тест; это не ручная UX-приёмка и не проверка окончательного portable image.

Отчёты находятся в `ui-parity/target/gates/7bd59882-0d93-434c-bda9-f0dc2d25dca2/reports-*`. Разделы будущей S6 приёмки выше сохраняют PENDING. Снимок разработки `refs/cashprediction/checkpoints/s4-ui-e2e-20261004-101524` (`c801c6b83da19cf73ba019e2b4f95cce6ab28330`) сохранён отдельно от рабочей ветки; он не является stage commit, CI или релизом.
