# S5 silent-update signoff template

Overall: PENDING. Prepared 2026-10-03; scoped evidence reviewed 2026-10-04. Тихое обновление входит в S5 вместе с соответствием требованиям и UX; отдельного этапа S7 больше нет. Исторические имена S7 scripts/receipts сохраняются как технические идентификаторы. Отдельные проверки реально выполнены; их ограниченный scope приведён ниже. Полная приёмка обновления в S5 не получена, этот документ не является approval. Итоговая независимая проверка интегрированного продукта выполняется в S6 после S5. Commands and evidence definitions: `.claude/scratch/s7-execution-preparation.md`. Fill only from actual receipts, never from template existence or missing reports.

| Prerequisite / context | Status | Evidence |
| --- | --- | --- |
| Clean tested S5 candidate SHA and empty status for update acceptance | PENDING | PENDING |
| Final S6 publication gate after S5: Invoke-S6Verification serial receipt bound to expected SHA | PENDING | PENDING |
| Final S6 publication gate after S5: 18 scenarios and spec sections 1-10 | PENDING | PENDING |
| Final S6 publication gate after S5: 23 classes and mapping comments | PENDING | PENDING |
| Final S6 publication gate after S5: complete seven manual recovery receipts | PENDING | PENDING |
| Seven cases: FX registry/XML, Swing registry/XML, Web, decline/clear, corrupt-registry/XML | PENDING | PENDING |
| G1 main decisions P01-P12 | PENDING | PENDING |
| G2 engine / G3 lifecycle tested APIs | PENDING | PENDING |
| Six owner reports and G4 integration | PENDING | PENDING |
| Source identity / baseline diff / copied checkout identity | PENDING | PENDING |
| No new deps/Node/updater UI; Russian docs/shared localization | PENDING | PENDING |

| Protocol section | Verification | Status | Evidence / timing |
| --- | --- | --- | --- |
| 1 boundaries/core/dev inert/silent errors | T09-T10 | PENDING | PENDING |
| 2 assets/two bases/retention | T08/T11/rehearsal | PENDING | PENDING |
| 3 schema/monotonic release/tree digest | T01-T03 | PENDING | PENDING |
| 4 delta/payload/path safety | T02-T03/T08 | PENDING | PENDING |
| 5 once/retry/HTTP/cancel/Ready | T04-T05/T17 | PENDING | PENDING |
| 6 leases/journal/helper/rollback/restart | T06-T07/T17 | PENDING | PENDING |
| 7 release/portable matrix | T10-T17/rehearsal | PENDING | PENDING |

| Test ID | Status | Actual command / exit / count / evidence / timing |
| --- | --- | --- |
| T01 schema | PENDING | PENDING |
| T02 paths/ZIP | PENDING | PENDING |
| T03 tree/hash/delta | PENDING | PENDING |
| T04 HTTP/retry/fallback | PENDING | PENDING |
| T05 cancel/locks/Ready | PENDING | PENDING |
| T06 leases/journal | PENDING | PENDING |
| T07 lifecycle/phase faults | PENDING | PENDING |
| T08 CLI | PENDING | PENDING |
| T09 three adapter reports | PENDING | PENDING |
| T10 default install/audits | PENDING | PENDING |
| T11 mocked release | PENDING | PENDING |
| T12 portable safety | PENDING | PENDING |
| T13 ui-tests | PENDING | PENDING |
| T14 e2e | PENDING | PENDING |
| T15 dist packaging | PENDING | PENDING |
| T16 portable smoke | PENDING | PENDING |
| T16P integrated portable parity | PENDING | PENDING |
| T17 update matrix | PENDING | PENDING |

Each scenario requires results.json rows for all three launchers and all three path variants, base/target release/SHA, actual exe/args, HTTP traces, phase logs and managed/user inventories. Aggregate PASS without the full cell list is insufficient.

| Scenario | Status | Per-cell evidence |
| --- | --- | --- |
| B1->T delta | PENDING | PENDING |
| B2->T delta | PENDING | PENDING |
| Deterministic CLI bytes / both round trips | PENDING | PENDING |
| Corrupt delta->verified full | PENDING | PENDING |
| Corrupt full->current app retained | PENDING | PENDING |
| Short cancelled session->next long session | PENDING | PENDING |
| Offline/timeout/malformed manifest | PENDING | PENDING |
| One after-ready pass / <=3 attempts / no polling | PENDING | PENDING |
| All client leases exit / normal close no restart | PENDING | PENDING |
| Abrupt Ready exit / pre-UI correct-client restart | PENDING | PENDING |
| Launch during Applying / safe args / skip SHA | PENDING | PENDING |
| Two-client waiting | PENDING | PENDING |
| Three-client waiting / root/PID reuse isolation | PENDING | PENDING |
| Every journal phase / durable per-move fault | PENDING | PENDING |
| Helper death / locked/readOnly/disk-full rollback | PENDING | PENDING |
| Unicode/spaces/non-ANSI paths and payload | PENDING | PENDING |
| CashMemory invariance / controlled session baseline | PENDING | PENDING |
| Unmanaged root preserved / complete old-or-new | PENDING | PENDING |
| Mocked 0/1/2-base publication / every failure boundary | PENDING | PENDING |
| Pointer-last / cleanup-after / Invoke-Gh retries | PENDING | PENDING |
| Local-only rehearsal / no real GitHub operations | PENDING | PENDING |

| Final check | Status | Evidence |
| --- | --- | --- |
| Matrix repeated on isolated source copy | PENDING | PENDING |
| Frozen phase list equals tested fault list | PENDING | PENDING |
| No client/helper/server descendants remain | PENDING | PENDING |
| Owned UUID registry nodes removed / real sessions unchanged | PENDING | PENDING |
| Success residue absent / failed Ready accounted for | PENDING | PENDING |
| Evidence retained outside cleanup targets | PENDING | PENDING |
| Independent cross-review / owner regressions | PENDING | PENDING |
| Findings ledger / main decisions reconciled | PENDING | PENDING |
| Lead final revision/timestamp/signoff | PENDING | PENDING |

Open findings: PENDING audit, not zero defects. Missing/blocked/skipped/timed-out evidence stays PENDING. Unresolved safety defects prevent signoff. Preparation authorizes no commit, push, publication or protocol changes.

## Наблюдаемые scoped evidence: 2026-10-04

Сверены [текущий журнал](/C:/Users/Oscar/Documents/CashPrediction/.claude/scratch/verification-2026-10-04-current.md) и сохранённые JSON/JSONL receipts; повторных запусков в этой проверке нет. Ни один статус таблиц выше не повышен: T08, T10, T17 и overall остаются PENDING до полного согласованного signoff. Исторический terminal относится к своему запуску, а не ко всем последующим dirty edits. Описание live `13500` ниже сохраняет исторический snapshot прежнего чтения, а не текущий статус очереди; более новые диагностические receipts приведены отдельно.

- **Actual CLI, две patch roundtrips.** В [terminal JSONL Anscombe](/C:/Users/Oscar/.codex/sessions/2026/10/04/rollout-2026-10-04T00-53-35-01a10354-3e67-7a40-8364-5edc190606a0.jsonl:90) сохранён `item_completed`, `exec-04ddd76d-2d2c-4d99-8a39-5aa0e497eaba`, process `15740`, exit `0`: `.github/scripts/Test-S7Pipeline.ps1 -ToolCommandFile .claude/scratch/s7-cli-current-9b7fbbd2.json`, stdout `S7 pipeline: PASS (two actual CLI roundtrips and deterministic containers)`. [Tool command JSON](/C:/Users/Oscar/Documents/CashPrediction/.claude/scratch/s7-cli-current-9b7fbbd2.json) задаёт настоящую JDK JVM и `ru.cashprediction.updatetool.UpdateTool`, не mock. Исполненный pipeline проверяет обе базы, детерминированность full ZIP/двух cpdelta, отказ изменённой базе без публикации pointer и сохранность CashMemory. Журнал отдельно фиксирует последующий Unicode transport контроль с исходным `п路` и transport config `s7-cli-transport-dba767a3.json`; это также CLI scope, не native матрица и не release rehearsal.
- **Default root install `68555`, terminal восстановлен.** [Исходный terminal JSONL MAIN](/C:/Users/Oscar/.codex/sessions/2026/09/16/rollout-2026-09-16T19-15-56-01a0aa93-04a2-7871-adff-5babdb8ce458.jsonl:32273): `item_completed`, `exec-5eb1147a-db76-424b-8354-dde8b0cc5c2d`, process `68555`, status `completed`, exit `0`; команда `& 'C:/Tools/apache-maven-3.9.16/bin/mvn.cmd' -B install`. В stdout все семь reactor modules `SUCCESS`, `BUILD SUCCESS`, `24:52 min`, завершение `2026-10-04T05:57:26+05:00`. Это прямое terminal evidence, не реконструкция PASS по XML. Default install не доказывает opt-in профили, portable/native матрицы или отсутствие skips; в последнем module packaging/install явно skipped конфигурацией.
- **Cold native Unicode `11668`: только одна клетка.** [Cold results.json](/C:/Users/Oscar/AppData/Local/Temp/cp-bootstrap-evidence-1bec27d3-0b24-4526-89a1-19c225e62a51/results.json): `PARTIAL_PASS`, `fullPlanCellCount=396`, выбран `B1/INITIAL/fx/unicode`, ровно `1 PASS / 395 PENDING`, `failure=null`; terminal exit `0` зафиксирован в журнале. PASS-строка имеет `nativeExecuted=true`, `actualExit=0`, `treeOutcome=NEW`, actual exe `Δ 测试/CashPrediction/CashPrediction.exe`, native-window witness/lease и ссылки на launch, checkpoint, journal, managed/user receipts. Transaction `1c1bb8fd-603e-425b-b52e-b8dc1b8c210c`; managed/target tree SHA `b71bedeb0fbef8763211388e6567120acfe50d717bd87e5ff98e83d511a43224`; user before/after SHA совпадают: `8658dba866eda3ad586e49cedd398e1c719f72fb2d61b4b302bfea16f9c2bf24`. Это не полный cold gate и не все Unicode/client combinations.
- **Live native lifecycle `13500`: проверена первая клетка, не terminal run.** [Lifecycle results.json](/C:/Users/Oscar/AppData/Local/Temp/cp-native-lifecycle-evidence-5c5cd5ab-9fe3-497f-93cb-cf5681927f20/results.json) сохраняет overall `PENDING`. Зафиксированный здесь scope: `delta/B1/fx/ascii/SESSION`, `PASS`, `executed=true`, reason `NATIVE_LIFECYCLE_EXECUTED`, exit `0`, `failures=0`, `skipped=0`, interval `2026-10-04T02:20:46.2983752Z` - `2026-10-04T02:24:12.8106249Z`. Cell identity `run-de1755a9-7655-43a8-a0a6-6ff38c0b9527`, actual FX exe PID `23116`, birth ticks `639266772488808918`. [HTTP trace](/C:/Users/Oscar/AppData/Local/Temp/cp-native-lifecycle-evidence-5c5cd5ab-9fe3-497f-93cb-cf5681927f20/run-de1755a9-7655-43a8-a0a6-6ff38c0b9527/httpTrace.json) содержит один успешный GET manifest и один выбранный delta GET; [nonpolling receipt](/C:/Users/Oscar/AppData/Local/Temp/cp-native-lifecycle-evidence-5c5cd5ab-9fe3-497f-93cb-cf5681927f20/run-de1755a9-7655-43a8-a0a6-6ff38c0b9527/nonpolling-observation.json) имеет `LIVE_HTTP_AFTER_READY`, `PASS`, window `5000 ms`, elapsed `5053 ms`, неизменные counters `requests=completed=2`, `active=0`. [Exit receipt](/C:/Users/Oscar/AppData/Local/Temp/cp-native-lifecycle-evidence-5c5cd5ab-9fe3-497f-93cb-cf5681927f20/run-de1755a9-7655-43a8-a0a6-6ff38c0b9527/exit-0.json): `ordinary-no-restart`, exit `0`, `remainingClients=0`; строка results связывает phase log, installer inventories и user before/after. Во время чтения live JSON уже появился дополнительный PASS `delta/B1/fx/cyrillic`; он не расширяет этот signoff scope. Terminal всего `13500`, остальные выбранные клетки и полный lifecycle gate `612` здесь не подтверждены. Runner, его frozen inputs и очередь не изменялись и не перезапускались.

Native inputs связаны с [candidate-images.json](/C:/Users/Oscar/AppData/Local/Temp/CashPredictionDev/native-candidates-509fa4cd-f318-4f4e-8015-e06bfa2b10a0/candidate-images.json) (`BUILT`), bootstrap command SHA `38a036e0262d0718a1c57198f972ae684cbfb120f598198d3a2495a900201566`, lifecycle config SHA `5afe2a0ed34d2a3f21cb92a7916c8dd8ef9f005acda4c300bf3ea1fa6cf96dcb` и lifecycle command SHA `1b5fe26424c096fdc23b8ded2ac308a53f88deb8cb1f8be0fdcb98d1c06037fe` (идентичности запусков из журнала). Версии B1/B2/T `1001/1002/1003` используют dummy SHA `111…/222…/333…`; cold и lifecycle строки прямо содержат B1 `1111111111111111111111111111111111111111` и T `3333333333333333333333333333333333333333`. Это local candidates, НЕ delivery и НЕ real Git/AppInfo provenance.

### Более новые receipts и fixture-only evidence

- **Native diagnostic, выбранные 10 delta cells.** Прочитан [results.json](/C:/Users/Oscar/AppData/Local/Temp/cp-native-lifecycle-evidence-f5f24bf9-5d68-47f8-83c9-f3416b5cbd34/results.json): overall `PENDING`, всего `612` строк, `10 PASS / 0 FAIL / 602 PENDING`. Все десять PASS имеют `executed=true`, `reason=NATIVE_LIFECYCLE_EXECUTED`, `exitCode=0`, `failures=0`, `skipped=0`. Точный scope, везде `delta` и `SESSION`: B1/web/cyrillic, B1/web/unicode; B2/fx/ascii, B2/fx/cyrillic, B2/fx/unicode; B2/swing/ascii, B2/swing/cyrillic, B2/swing/unicode; B2/web/cyrillic, B2/web/unicode. Это отдельный receipt, не сумма с историческими `13500`/другими запусками и не доказательство всей матрицы. Строки содержат B1/B2 `baseRelease=1001/1002`, T `targetRelease=1003`, `baseCommit=1111111111111111111111111111111111111111` либо `2222222222222222222222222222222222222222`, `targetCommit=3333333333333333333333333333333333333333`. Использованы прежние local dummy candidates, не свежие final production images, не real Git release/AppInfo provenance и не delivery. Отсутствие FAIL в этих десяти клетках не закрывает оставшиеся 602 PENDING, advanced routes или общий cleanup gate.
- **Свежий root install, отдельный build scope.** [install.log](/C:/Users/Oscar/AppData/Local/Temp/cp-s4-chain-f9d97316-cea5-4875-b392-bf31f1a6b371/install.log) прямо содержит `BUILD SUCCESS`, семь reactor modules `SUCCESS`, время `23:09 min`, завершение `2026-10-04T08:56:33+05:00`. Сумма шести module test summaries: `3058 tests`, `0 failures`, `0 errors`, `132 skipped` (core `2398/10`, update-tool `24/1`, ui-fx `247/116`, ui-swing `226/5`, web `135/0`, repository-doc-audits `28/0`, формат tests/skipped). Это более новый default root build receipt, не повторный запуск при редактировании документа. Log не заменяет независимый acceptance и не доказывает portable packaging, opt-in UI/e2e/native профили, fresh production update matrix или отсутствие skips. Его успех не переносится на старые candidate images и последующие dirty edits; T10 в полном scope остаётся PENDING.
- **Текущий PowerShellHelperTest XML.** [Surefire XML](/C:/Users/Oscar/Documents/CashPrediction/core/target/surefire-reports/TEST-ru.cashprediction.core.update.install.PowerShellHelperTest.xml): `tests=19`, `failures=0`, `errors=0`, `skipped=1`, `time=222.555`; install log содержит соответствующий summary `19/0/0/1`. В XML присутствуют непропущенные cases `confirmedMissingPidRemovesOrphanLease`, `leaseLookupAndBirthUncertaintyRemainFailSafe`, `registrationWriteFailureDoesNotAuthorizeReplacementWhileUnregisteredNativeClientLives`, `staleLeaseBirthMismatchInstallsWhileForeignPidRemainsAlive`, `staleLeaseDoesNotBypassAnotherMatchingLiveLease`. Пропущен `sourceAndDestinationReparsePointsAreRejectedBeforeTouchingExternalData`: assumption `Windows symlink privilege unavailable`. Это реальные JUnit/helper test receipts, но не ordinary native exe/bootstrap proof, не полный T07/T17 и не разрешение считать skipped case пройденным.
- **Canonical dispatcher/lifecycle integration, MAIN scoped PASS.** Исторически owner сообщил exit `0` для [integration fixture](/C:/Users/Oscar/Documents/CashPrediction/.github/scripts/Test-NativeUpdateLifecycleDispatchIntegrationFixtures.ps1): сначала `59` проверок (`LIFECYCLE_DISPATCH_INTEGRATION_AST_MOCK_AND_PIN_GUARDS`), затем расширенный frozen regression `697` проверок; это последовательные версии suite, не `756` независимых проверок. После AGENT-only freeze MAIN сообщил terminal session `67344`, exit `0`, фактический запуск обновлённого fixture trio: [Dispatcher fixture](/C:/Users/Oscar/Documents/CashPrediction/.github/scripts/Test-NativeUpdateScenarioDispatchFixtures.ps1) `368` проверок, scope `AST_AND_MOCK_ROUTING_ONLY`, `nativeExecuted=false`; [Lifecycle fixture](/C:/Users/Oscar/Documents/CashPrediction/.github/scripts/Test-NativeUpdateLifecycleFixtures.ps1) `240` проверок; integration `697` проверок, `nativeExecuted=false`, canonical cells `612`, full signoff `PENDING`. Эти три результата теперь MAIN scoped PASS по переданной MAIN terminal квитанции, а не только owner report; в этой docs-проверке они повторно не запускались. Scope: AST/function-only imports, mock routing, private-context restoration, exception/persistence contracts и byte-pin guards на dummy files. Они не доказывают native execution, acceptance advanced receipts или финальный production update.

Canonical plan содержит `22` сценария и `612` cells: normal `9`, concurrent `2`, ready `2`, phase `3`, rollback `3`, payload `3`; `journal-fault` и `per-move-fault` имеют по `126` клеток с семью phases `PREPARED`, `WAITING`, `BACKING_UP`, `INSTALLING`, `VERIFYING`, `COMMITTED`, `ROLLING_BACK`, остальные 20 сценариев имеют по `18` SESSION cells. Максимум `MaxCells=612`, default budget `144` сохранён. В integration regression, теперь также выполненном MAIN, проверены все 612 исходных PENDING row references и canonical order при обратном порядке входных scenario/key filters; недостаточный budget отвергается, а не обрезает выбранный фильтр. Selection не означает execution, неисполненные cells остаются PENDING. Dispatcher возвращает raw helper outputs через `helperReceipts` в explicit envelope `status=PENDING`, `scope=NATIVE_HELPER_RECEIPTS_ONLY`; возвращённый helper PASS не является independent acceptance для advanced cell, а final matrix PASS не создаётся.

Frozen fixture SHA256 для MAIN scoped regression результатов, не SHA native evidence:

| Fixture | Checks | SHA256 |
| --- | --- | --- |
| Test-NativeUpdateScenarioDispatchFixtures.ps1 | 368 | `E415BB76F9D48FF540FDC36990AEB0652AE1C34C8E28BBB4D324C5D97A1AB5D6` |
| Test-NativeUpdateLifecycleFixtures.ps1 | 240 | `1510A19594A8843CCBF247949C5F399547C8BB76C405FA4F6C13FFF466EE4A34` |
| Test-NativeUpdateLifecycleDispatchIntegrationFixtures.ps1 | 697 | `0673719613AB42705F4622DA83FC67D96E4D2BCCE498F71E5A0DA5EB0AD1EB3F` |

- **Текущий UI ParityTest, отдельный scoped PASS.** MAIN сообщил фактический `ParityTest PASS`, `1 test / 0 skipped`, `683.193 s` и `18` scenario directories для каждого `fx/swing/web`. Прочитан сохранённый [gate XML](/C:/Users/Oscar/Documents/CashPrediction/ui-parity/target/gates/00892b4c-8be5-4273-afa2-d4d14203ced0/reports-22-ParityTest/TEST-ru.cashprediction.parity.pipeline.ParityTest.xml): `tests=1`, `failures=0`, `errors=0`, `skipped=0`, `time=683.193`. Общий `ui-parity/target/surefire-reports` при этом содержит другой skipped `ParityTest` (`1/0/0/1`, `0.001 s`); он не подменяет сохранённый gate receipt. Это текущий UI parity scope, не native update matrix или полный S7 signoff. Hotkeys на момент сообщения MAIN ещё ongoing: успешный terminal hotkey run здесь не заявляется, остальные требуемые parity/profile receipts и полный T16P остаются PENDING.

Остаются PENDING все полные gates таблиц выше: полный T17 с terminal и всеми обязательными клетками/отказами/клиентами, повтор на изолированной копии, общий cleanup всей native очереди, независимый review требований и обновления в S5, затем финальная проверка S6, real main ancestry/count/AppInfo и CI evidence, финальные source archive/portable delivery и release approval. Мocks/static fixtures не являются native PASS; partial/выбранные PASS-строки, UI ParityTest и свежий default install не закрывают эти gates. Overall: PENDING.
