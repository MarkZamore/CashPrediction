# Будущая проверка S6, переход S7 и доставка

Статус всех действий: NOT RUN / PENDING. Подготовка 03.10.2026 не подтверждает готовность релиза.
Правила: AGENTS.md; docs/design/stages.md S6/S7; docs/design/update-protocol.md.
Матрица приёмки: docs/parity-signoff.md. Исторический .claude/workflows/unified-ui-s6.js сохранён;
его agent()/phase()/parallel()/log(), args и top-level return не доступны текущему локальному runner.
Не запускать этот файл через Node и не выполнять его force-cleanup/overlay команды.

## Текущие инструменты и порядок

Будущий исполнитель читает документы через functions.exec + tools.exec_command (PowerShell), меняет только
согласованные документы через tools.apply_patch. Каждый Maven/GUI этап запускать последовательно и ждать его
завершения через tools.write_stdin по session_id; functions.wait используется только для cell_id от functions.exec.
Никакого Promise.all для Maven, GUI, Robot, keyboard и portable. Одна общая блокировка для всех исполнителей.
Журналы HTML открывать через open_in_codex, реальные PNG смотреть через view_image.
Автоматизация UI только доступным документированным интерфейсом после чтения его skill; native CUA сейчас
отключён, поэтому ручные DPI/keyboard/crash шаги выполняет человек, если другого разрешённого инструмента нет.
Недоступность UI оставляет строки PENDING. Новые chats/subagents не создавать без отдельного запроса.

## Чистый кандидат и сохранность

Текущий checkout содержит много чужих незакоммиченных изменений. HEAD не представляет эти изменения.
Сначала владелец интеграции завершает S5 и фиксирует кандидат. Здесь этого не делать.
Нельзя stash/pop/drop, reset, checkout --, переключать основную ветку, force remove, prune или накладывать diff/tar
на checkout и называть его чистым. Новая уникальная detached worktree содержит только выбранный committed SHA.
Рабочая грязь, удалённые/неотслеживаемые/ignored файлы, stashes и настоящий CashMemory остаются нетронутыми.

Будущая PowerShell сессия, после выбора committed кандидата:
```powershell
$sourceRepo = 'C:/Users/Oscar/Documents/CashPrediction'
$candidate = (git -C $sourceRepo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Не удалось прочитать HEAD' }
$runRoot = Join-Path ([IO.Path]::GetTempPath()) ('cp-s6-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $runRoot -ErrorAction Stop | Out-Null
$clean = Join-Path $runRoot 'checkout'
$evidence = Join-Path $runRoot 'evidence'
New-Item -ItemType Directory -Path $evidence -ErrorAction Stop | Out-Null
git -C $sourceRepo status --porcelain=v1 --untracked-files=all | Set-Content (Join-Path $evidence 'source-status.before.txt')
git -C $sourceRepo stash list --format='%gd %H' | Set-Content (Join-Path $evidence 'stashes.before.txt')
git -C $sourceRepo worktree add --detach $clean $candidate
if ($LASTEXITCODE -ne 0) { throw 'Не удалось создать worktree; ничего не удалять' }
if (git -C $clean status --porcelain) { throw 'Кандидат не чист' }
$candidate | Set-Content (Join-Path $evidence 'candidate.txt')
```

До первого запуска сохранить manifest SHA256 всех tracked source/resources/spec/goldens/scripts/POM в $clean,
версии java/mvn/Windows/Edge, оборудование, registry snapshot всего ru/cashprediction (включая hashed session).
После install сохранить SHA256 core/ui-fx/ui-swing/web target jar и OpenJFX module-path. Для каждого опыта записать
manifest копий module-snapshot. В конце снова вычислить source/jar SHA: любое расхождение инвалидирует связанные
результаты. После финального dist заново заморозить артефакты перед portable. Не сравнивать только HEAD.
Не печатать browser token/полный URL с token в общие журналы.

Registry export до/после допускается только в private evidence; сравнивать значения/список узлов,
а не только timestamp/export bytes. Никогда не импортировать весь backup поверх настоящего registry.
Существующие чужие selftest узлы не удалять; фиксировать baseline и удалять ровно UUID созданные этим run.

## Выполняемые команды и реальные переключатели

JDK 25, Maven 3.9+, OpenJFX 25.0.4, JUnit 5.14.4 по текущему pom.xml, Windows desktop, Edge/Chrome.
Профили ui-tests/e2e лишь подключают модуль; surefire выполняет *Test и *IT. Сам профиль не включает real/e2e.
Launcher берёт target jar, не target/classes; свежий install/package обязателен до реальных запусков.
Все команды ниже будущие. Не добавлять -T, parallel JUnit или фоновые Maven.

Основной автоматический вход: .github/scripts/Invoke-S6Verification.ps1, который ведёт основной владелец.
Не копировать и не заменять его локальным runner. Проверен чтением текущий контракт параметров:
ExpectedCommit (ровно 40 hex), Repository, PreflightOnly; требуется PowerShell 7.
Он отклоняет грязный checkout, несовпадающий HEAD и отсутствие -Parity у Test-Portable до запуска Maven.
Сначала выполнить preflight, затем полный автоматический проход под внешней общей блокировкой:
```powershell
$lockPath = Join-Path ([IO.Path]::GetTempPath()) 'CashPrediction-s6-verification.lock'
$verificationLock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    & pwsh -NoProfile -File "$clean/.github/scripts/Invoke-S6Verification.ps1" -Repository $clean -ExpectedCommit $candidate -PreflightOnly
    if ($LASTEXITCODE -ne 0) { throw 'S6 prerequisite failed; не обходить preflight' }
    & pwsh -NoProfile -File "$clean/.github/scripts/Invoke-S6Verification.ps1" -Repository $clean -ExpectedCommit $candidate
    if ($LASTEXITCODE -ne 0) { throw 'S6 automated verification failed' }
} finally { $verificationLock.Dispose() }
```

Если lock занят, отложить запуск, не удалять файл. Runner последовательно выполняет install, strictUI,
strictE2E, ui-tests/e2e lifecycle profiles, dist, portableParity и sourceVerifyBuild.
Квитанция: $clean/target/s6/<UUID>/automatic-results.json, status AUTOMATED_ONLY,
independentSignoff=false; на ошибке FAILED. StartedAt/finishedAt каждого шага дают длительность.
AUTOMATED_ONLY не является GREEN S6, независимой подписью или разрешением публикации и доставки.
Начало реализации S7 параллельно с S4-S6 разрешено пользователем 03.10.2026;
контракт: docs/design/s7-contract-freeze.md. Финальная приёмка S6/S7 остаётся обязательной.
Strict gates: Invoke-UiGates.ps1 -Mode UI/E2E; свежие XML с точными counts и zero skipped.
Пути runner: target/s6/<UUID>/ui-gates/<UUID>/reports-*, actual/parity, upload;
аналогично e2e-gates; portable evidence в target/s6/<UUID>/portable; журналы шагов рядом с receipt.
Текущий strictUI ограничивает visual четырьмя сценариями/first: дополнительный all/all ниже обязателен.
Первый сбой останавливает runner. Сохранить отчёт, завести дефект владельцу; повторять только после нового freeze.
Reports surefire перезаписываются: перед следующим этапом копировать **/target/surefire-reports в evidence/<этап>/
и фиксировать zero failures + обязательные test counts и skipped reasons. Exit=0 со skipped gates не закрывает S6.
Отчёт parity/report.html тоже перезаписывается; сохранять полное дерево с относительными ссылками между этапами.

Дополнительные точные диагностические команды из $clean (под тем же lock; без -am после install):
```powershell
mvn -B -Pui-tests -pl ui-parity test '-Dtest=VisualParityTest' '-Dparity.realClients=true' '-Dparity.clients=fx,swing,web' '-Dparity.visual=true' '-Dparity.visual.scenarios=all' '-Dparity.visual.checkpoints=all'
mvn -B '-Pdist,e2e' verify '-Dparity.e2e=true' '-Dparity.realClients=true'
mvn -B -Pui-tests -pl ui-parity test '-Dtest=ClassUsageTest,DirectoryChooserProbeTest' '-Dparity.realClients=true' '-Dparity.clients=fx'
mvn -B -Pui-tests -pl ui-parity test '-Dtest=HotkeyParityTest' '-Dparity.realClients=true' '-Dparity.clients=fx,swing,web'
$perfRoot = Join-Path $evidence 'performance'
New-Item -ItemType Directory -Path $perfRoot -ErrorAction Stop | Out-Null
mvn -B -Pui-tests -pl ui-parity test '-Dtest=WebVirtualTablePerformanceTest' '-Dparity.performance=true' "-Dparity.performance.output=$perfRoot" "-Dparity.performance.webJar=$clean/web/target/cashprediction-web-1.0.0.jar" "-Dparity.performance.coreJar=$clean/core/target/cashprediction-core-1.0.0.jar"
```

parity.scenarios / parity.hotkeys - только диагностические фильтры; финальный gate без фильтров.
parity.hotkey.residual=true сокращает coverage и не является полной приёмкой.
parity.allowance.evidence - существующий путь manifest AllowanceEvidence, если требуются дополнительные
реальные пары для §10; читать audit/AllowanceEvidence.java и README-S3.md, не сочинять manifest.
parity.reports.directory меняет surefire XML, parity.build.directory задаёт корень evidence/parity,
не обновляет клиентские jar. Версию имён jar перечитать в POM при изменении кандидата.

| Этап / реальные доказательства | Статус / длительность |
|---|---|
| Чистый committed checkout / source+jar manifests | NOT RUN / PENDING |
| Invoke-S6Verification.ps1 preflight + AUTOMATED_ONLY receipt, не sign-off | NOT RUN / PENDING |
| strictUI/strictE2E: свежие XML, точные counts, zero skipped, manifests/upload | NOT RUN / PENDING |
| default install / каждый модуль target/surefire-reports | NOT RUN / PENDING |
| ui-tests real / ui-parity/target/parity/report.html и report-fx-swing-web.html | NOT RUN / PENDING |
| visual all / ui-parity/target/parity/visual-<UUID>/report.html, PNG/dumps/run logs | NOT RUN / PENDING |
| census / ui-parity/target/parity/class-census-<suffix>/class-census.txt | NOT RUN / PENDING |
| hotkeys / ui-parity/target/parity/hotkey-<UUID>/out, selftest.log, dumps | NOT RUN / PENDING |
| e2e / parity/s4-desktop-crash-*, s4-web-restart-*, before/after snapshots и журналы | NOT RUN / PENDING |
| dist,e2e / dist/target/dist/CashPrediction и ZIP | NOT RUN / PENDING |
| performance / performance/web-virtual-200k-*/evidence.json; отдельно domain budgets | NOT RUN / PENDING |
| Portable / явно заданные WorkDir/EvidenceRoot и очищенные stdout/stderr | NOT RUN / PENDING |
| Ручные 7 restore paths, DPI/keyboard/Web, полный spec/ledger просмотр | NOT RUN / PENDING |
| Registry/process/profile/lock/source/stash hygiene | NOT RUN / PENDING |

PortableExeE2ETest существует в ui-parity/src/test/java/ru/cashprediction/parity/portable/PortableExeE2ETest.java;
.github/scripts/Test-Portable.ps1 принимает -Parity и использует .github/scripts/Portable-Parity.ps1.
Наличие этих инструментов не подтверждает их успешный прогон на замороженном кандидате: сохранить свежие
отчёты PortableExeE2ETest и Test-Portable.ps1 -Parity для всех трёх exe, включая crash/restore.
JVM harness и smoke не заменяют portable parity; статус её приёмки остаётся PENDING.

## Portable и ручные crash/restore

Под lock после dist, новые уникальные внешние каталоги:
```powershell
$portableWork = Join-Path $runRoot 'portable-work'
$portableEvidence = Join-Path $evidence 'portable'
& powershell -NoProfile -ExecutionPolicy Bypass -File "$clean/.github/scripts/Test-Portable.ps1" -PortableDir "$clean/dist/target/dist/CashPrediction" -WorkDir $portableWork -EvidenceRoot $portableEvidence
if ($LASTEXITCODE -ne 0) { throw 'Portable smoke failed' }
```

Реальные параметры: PortableDir, WorkDir, EvidenceRoot, StartTimeoutSeconds, CleanRegistry, Parity, ParityClassPath.
Команда выше выполняет smoke. Отдельный обязательный проход с -Parity выполняется через PowerShell 7
с подготовленными тестовыми классами ui-parity; сохранить его evidence отдельно от smoke.
Локально без CleanRegistry. Скрипт проверяет ascii/Cyrillic/Unicode копии x 3 exe,
CashMemory и SHA/size/readonly остальных файлов; внешние записи этим inventory не доказываются.

Ручной шаблон для отдельной копии (повторить с уникальными case dir/node для каждого из 7 путей):
```powershell
$caseDir = Join-Path $runRoot ('manual-' + [guid]::NewGuid())
Copy-Item -LiteralPath "$clean/dist/target/dist/CashPrediction" -Destination $caseDir -Recurse
$node = 'ru/cashprediction/selftest/' + [guid]::NewGuid()
$launcher = Join-Path $caseDir 'CashPrediction.exe'
# Открывает именно тестовую копию для ручной проверки окна.
$ownedLauncher = Start-Process -FilePath $launcher -ArgumentList @('--home',('"' + $caseDir + '"'),'--registry-node',$node,'--today','2026-09-13') -PassThru
```

Для Swing выбрать CashPrediction-Swing.exe, Web - CashPrediction-Web.exe.
Не использовать --ui: он удалён. Selftest CLI: --selftest s02-sample-table --selftest-out <абсолютный каталог>;
--selftest-recovery registry|xml|none|already-ok выбирает ответ автоматически, поэтому для ручного прохода его не задавать.
--registry memory не годится для persistent crash/restore. Web хранит сеанс на сервере, не в localStorage.

Сохранить PID + CreationDate + executable path всех потомков до crash; launcher PID может уже завершиться.
taskkill /PID <подтверждённый живой PID> /T /F допустим только для своего дерева. Если launcher умер,
найти потомка по сохранённой ancestry и точному executable пути собственной копии; не убивать все java/msedge.
После crash подтвердить отсутствие всех потомков, затем перезапустить тот же launcher/home/node.

Browser: создавать отдельный user-data-dir и записывать точный PID/start time/profile/port.
AutoCloseable BrowserSession/LaunchedClient/RegistryNodeCleaner - основной cleanup harness.
У VisualRun профиль cp-visual-browser-* может находиться в системном Temp: записать конкретный созданный путь.
Нельзя wildcard kill, taskkill /IM msedge.exe, удаление всех Edge профилей или Temp.
Удалять профиль только после завершения собственного дерева и проверки resolved path, владельца и отсутствия junction.
Автоматически открытая вкладка Web в пользовательском браузере: закрывать лишь свою вкладку, не весь браузер.

При ручном cleanup удалить только точный созданный selftest UUID после сохранения evidence и проверки пути;
не очищать session, selftest родителя или другие UUID. Сравнить весь настоящий registry subtree с baseline.
Сохранить все outputs вне checkout. git worktree remove $clean без --force только после чистого status
и закрытия процессов; если есть изменения/locks, оставить worktree для разбора. Не делать recursive delete checkout.
Сравнить исходный status/stash с before, чужие изменения не откатывать.

## S7: параллельная реализация и обязательная финальная приёмка

Реализация S7 разрешена до GREEN S6 и его коммита согласно docs/design/s7-contract-freeze.md
и docs/design/update-protocol.md §8. Публикация и доставка требуют завершённой общей приёмки S6/S7.

Все строки PENDING. Это backlog приёмки протокола; команды нового update-tool/Test-Update пока не утверждать
как существующие. После реализации владельцами сверить CLI и paths с кодом, добавить реальные команды в sign-off.

| Владелец / этап | Что требуется и будущее доказательство | Статус |
|---|---|---|
| Gate публикации и доставки | S6 все строки OK, нет открытых дефектов, source freeze и committed sign-off SHA; завершённая приёмка S7 | PENDING |
| update-core | Schema2 strict manifest; managed exe/app/runtime tree, deterministic digest; ZIP unsafe/duplicate rejection; delta add/change/delete/empty/readonly/Unicode | PENDING |
| update-core | Downgrade/same-release rejection, hash/size checks, fixed URLs, dev no-op, one checker pass, 3 attempts 20s + pauses 1/2s | PENDING |
| update-core | Delta from either base; full fallback; cancellation .download cleanup; lock, stale staging, atomic Ready | PENDING |
| update-tool | inventory/create/apply/verify, deterministic round-trip, corrupt base/patch/target nonzero, JDK-only; не четвёртый клиент | PENDING |
| update-install | Скрытый helper в CashMemory/Updates, lease PID/start/root, все клиенты закрыты до swap; только managed files | PENDING |
| update-install | Journal every-phase fault injection, complete old/new tree, rollback; failed install сохраняет Ready | PENDING |
| clients | Одинаковые lifecycle hooks, стабильный exe identity; нет update menus/status/endpoints; normal exit без restart | PENDING |
| clients | Pre-start recovery restart exact launcher/safe args, anti-loop SHA, silent errors/offline work | PENDING |
| release-ci | latest ZIP/release.json сохранены; 2 успешные update-base-N, обе cpdelta round-trip | PENDING |
| release-ci | Payload до update.json, старые assets/bases удаляются после успеха; каждый GitHub вызов Invoke-Gh | PENDING |
| update-e2e | 3 exe x plain/Cyrillic/non-ANSI, local JDK HttpServer, две app-image версии и обе bases | PENDING |
| update-e2e | delta/full fallback/slow download+long session/offline/corrupt manifest+patch/concurrent clients | PENDING |
| update-e2e | abrupt Ready/pre-start/helper interruption/rollback, installed tree digest и неизменный пользовательский CashMemory | PENDING |
| Finish | install/ui-tests/e2e/dist/Test-Portable/Test-Update, docs/update-signoff.md с evidence, нет helper/nodes/temp/base archive | PENDING |

Нужны две предыдущие базы для полного теста two-base chain; два app-image релиза доказывают лишь одну базу.
Создать локальные fixture bases для обоих путей, никаких публичных публикаций для rehearsal.
Локальные native candidate1001..1003 с dummy metadata являются только тестовыми образами.
Номера 1001..1003 и dummy release/commit metadata не являются Git-релизами (git release),
проверенными опубликованными update-base или финальной доставкой (delivery). Они не заменяют
номер релиза по истории main, настоящий SHA кандидата, CI, sign-off или проверку конечных артефактов.
Для native-матрицы сверять требования к идентичности баз и цели с docs/design/architecture.md;
подготовка образов сама по себе не закрывает PENDING.
Протокол управляет только exe/app/runtime; при CashMemory/Updates изменениях отдельно сравнивать пользовательские
планы/снимки, не требовать неизменности всего служебного Updates.

## Релиз, корневая доставка и архив

| Действие | Статус |
|---|---|
| S6 signed+committed; затем S7 signed+committed и новая финальная полная матрица | PENDING |
| CHANGELOG номер по итоговой истории main; одна пользовательская строка сверху | PENDING |
| Согласованные merge/push; release.yml success, ZIP/release.json (S7 также update assets) | PENDING |
| Скачанный release ZIP: SHA/структура, 3 exe smoke + parity/restore | PENDING |
| В корне проекта: CashPrediction-source.7z из frozen source после S6/S7 | PENDING |
| В корне проекта: папка CashPrediction с тремя exe и их общими app/runtime, без CashMemory | PENDING |
| Проверка именно корневой portable-папки: Test-Portable -Parity, версии/хеши всех трёх exe | PENDING |
| Pack-Source фильтры и VerifyBuild; 7z listing/extract/install именно финального архива | PENDING |
| Source/архив SHA256, delivery inventory, zero generated files/user CashMemory; restore hygiene | PENDING |

Перед будущим коммитом main: N = git rev-list --count HEAD + 1.
Для merge N = число коммитов итоговой main, включая коммиты ветки и merge-коммит;
расчёт «main count + 1» до слияния неверен. Пересчитать при изменении истории.
Предложение строки (не готовая запись): «Единый интерфейс JavaFX, Swing и Web с восстановлением сеанса».
После S7 добавить фактически проверенное тихое обновление. Ни push, ни выпуск не делать в рамках подготовки.

Доставка исходников - отдельный артефакт от portable ZIP. Реальный Pack-Source.ps1 требует PowerShell7 + 7-Zip,
принимает SourceRoot/StageDirectory/OutputPath/SevenZipPath/StageOnly; OutputPath только .7z, существующие цели
не перезаписывает. Исключает .git/.claude/targets/CashMemory, developer docs/workflows, repository-doc-audits,
сохраняет docs/design/architecture.md, тесты/ресурсы/лицензии; staged POM убирает только audit module.
Его -VerifyBuild проверяет временную упаковку, не подтверждает произвольный финальный архив автоматически.

Будущие команды из $clean, под lock после S6/S7:
```powershell
pwsh -NoProfile -File ./dist/scripts/Test-Pack-Source.ps1 -VerifyBuild -SourceRoot $clean
$deliveryRoot = Join-Path $runRoot 'delivery'
New-Item -ItemType Directory -Path $deliveryRoot -ErrorAction Stop | Out-Null
$deliveryArchive = Join-Path $deliveryRoot 'CashPrediction-source.7z'
pwsh -NoProfile -File ./dist/scripts/Pack-Source.ps1 -SourceRoot $clean -OutputPath $deliveryArchive
if ($LASTEXITCODE -ne 0) { throw 'Source packaging failed' }
Get-FileHash -LiteralPath $deliveryArchive -Algorithm SHA256
```

После проверки артефактов разместить исходный архив в
`C:/Users/Oscar/Documents/CashPrediction/CashPrediction-source.7z`, а целиком проверенную portable-папку в
`C:/Users/Oscar/Documents/CashPrediction/CashPrediction/`. В ней должны находиться CashPrediction.exe,
CashPrediction-Swing.exe, CashPrediction-Web.exe и общие app/runtime. Не копировать только exe: без соседних
app/runtime они не являются портативным продуктом. Обе конечные цели заранее проверить на отсутствие;
существующие пользовательские файлы или папки не перезаписывать и не удалять. При конфликте сохранить
проверенные артефакты в owned delivery staging и запросить решение о размещении.
Сверить хеши staging и конечной папки, затем выполнить Test-Portable.ps1 -Parity именно для конечной папки:
проверка должна использовать изолированные копии, не создавать пользовательский CashMemory в доставке.
Сохранить inventory трёх exe и общего runtime, номер релиза и SHA исходников. До S7 это только будущие шаги.
Для именно этого .7z сохранить 7z list/test, извлечь в новую owned папку, проверить исключения и выполнить
mvn -B install в извлечённом дереве (тот же lock), сохранить log/duration/exit.
Не публиковать/передавать артефакт до выполнения этих строк.

Invoke-S6Verification.ps1 и Test-ReleaseSafety.ps1 - репозиторные entry points: первый требует committed git
checkout, второй читает release.yml. Доставленный source7z намеренно не содержит .git и workflows;
эти entry points не являются проверкой извлечённого архива. Для архива обязательны отдельные listing/test,
extract и mvn install; отсутствие репозиторного контекста не заменять пропуском этих проверок.
