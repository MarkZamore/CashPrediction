# Реальные supplemental evidence и unused-аудит

## Результат 02.10.2026

Один полный WebAllowanceTest на первоначальной frozen-паре: 9/9 PASS,
0 skips/failures, 24,198 с; `complete-nine-reports` в прежнем корне
`C:/Users/Oscar/AppData/Local/Temp/cashprediction-web-allowance-45cb3391-e137-495d-b88f-8ccba0624f9e`.
SHA и исходные UTC этой пары сохранены в `web-allowance-probes-20261002.md`.

После release desktop от MAIN выполнена новая согласованная frozen-выборка:

- Web: один полный прогон 9/9 PASS, 24,428 с, без skips/failures.
- Native FX/Swing: 2/2 PASS, 24,857 с; 25 + 26 подтверждённых CPS-строк.
- Изолированные unit-тесты: 20/20 PASS, 586 мс, без skips/failures.
- Supplemental: 10 реальных пар, 13 различий; 12 поглощены, 1 отклонено.
  Реально использовано 11 записей; отдельный экземпляр AllowedDiffs: 34 -> 23 unused.
  Это не общий отчёт 18x3 и не desktop hotkey PASS90.

Новый корень всех артефактов:
`C:/Users/Oscar/AppData/Local/Temp/cashprediction-allowance-native-c694100f-7eae-44c5-918d-aa639648d026`.
Отчёты: `complete-web-reports/TEST-junit-jupiter.xml`,
`complete-native-reports/TEST-junit-jupiter.xml`, `final-unit-reports/TEST-junit-jupiter.xml`.
Первый native pilot 2/2 по 20 строк также сохранён в `reports`/`runtime`;
его checkpoint snapshot-absent не доказал отсутствие и не включён в evidence.

| JAR 1.0.0 | Исходный UTC | SHA-256 |
| --- | --- | --- |
| core | 2026-10-02T11:58:33.0798332Z | EBC3540982C82B8EF4E110839562EF1AEA5636186BA03C8E6DE0DA76EA97119D |
| ui-fx | 2026-10-02T12:08:02.7870477Z | CBCBBDDB1F09775006EA65F6A548EE3C7D6FDFBED6A714D65EE74795B431ACDB |
| ui-swing | 2026-10-02T12:14:52.4691032Z | F9AF9BADBB70EE1F5D386D1CF60DB245B144369A2F96DFF99AC35EEC396A7C2F |
| web | 2026-10-02T12:11:39.9643754Z | 703317635E86E435320164F916100A1ACC1A1DFF572D61C03FC18B65B61A12A1 |

Root copies read-only; все 36 JAR-копий в этом корне проверены, изменённых 0.
Собственных Java/Chrome процессов с UUID корня осталось 0. Завершённые
сеансы проверили очистку только собственных selftest-узлов и неизменность real session.

## Подключение без ручного used

`ParityPipeline.run` получил дополнительный overload с Evidence callback.
Обычный overload сохраняет прежнее поведение. Callback возвращает
ограниченные пары перед финальным unused-аудитом, а штатный compare
вызывает DumpDiff и filter того же AllowedDiffs, который уже использовала
матрица. Supplemental сравнивается с нулевой tolerance; существующие
golden/pairwise правила не изменены. Ошибки evidence добавляются в HTML,
остаточные различия не исчезают, full unused-аудит остаётся строгим.

`ParityTest` поддерживает opt-in `-Dparity.allowance.evidence=<absolute manifest>`.
Без параметра изменений результата нет. При наличии параметра все
SHA-закреплённые байты читаются до запуска матрицы, список пар затем
передаётся в callback. Никаких used-флагов, рефлексии к used или новых allowances нет.

`AllowanceEvidence.load` требует настоящий native client fx/swing,
полную строгую схему UiDump для widget references, абсолютные пути,
selftest UUID-префикс и совпадающий SHA-256 каждого файла. Model reference
запрещён. Manifest загружается полностью до любого filter; ошибка второй
пары не может оставить первую «использованной». SHA доказывает неизменность
байтов, не происхождение: происхождение обеспечивают сохранённые реальные
запуски, CPS/журналы и проверенные checkpoints, не одна строка client.

Проекции явно ограничены: halt header; lastSnapshot content/details/detailsExpanded;
целый объект replaceFile; именованные uncaught buttons; toolbar.wrap и
измеренная contentWidth; самостоятельный DOM screen. Они не объявляют
остальные поля совпавшими. Для narrow требуется одна и та же фактическая
ширина в двух дампах, 0 < width < 1200. Для post-exit нет фиктивного UiDump:
title/text/open/mainInert/retry проверяются отдельно, затем реальный DOM
объект сопоставляется с отсутствием screens в настоящем native dump.

Manifest имеет вид `{ "pairs": [{ "label": "...", "scope": "...",
"reference": source, "actual": source }] }`, где source имеет ровно
`path`, `sha256`, `cashMemory`, `node`. Готовый точный manifest:
`evidence/evidence-manifest.json` в указанном корне.
Подробный аудит: `evidence/evidence-audit.json`; компактный, без payload:
`evidence/evidence-summary.json`.
CLI сохраняет отчёты, но возвращает exit 1 при непоглощённом различии;
текущий supplemental аудит именно RED, не успешная валидация всех allowances.

## Реальные различия и честный остаток

| Пара | Raw | Rejected |
| --- | --- | --- |
| halt FX/Web | 1 | 0 |
| prepared snapshot FX/Web | 2 | 0 |
| prepared snapshot FX/Swing | 1 | 1: /alerts/lastSnapshot/details |
| replaceFile FX/Swing | 1 | 0 |
| replaceFile FX/Web | 1 | 0 |
| native uncaught / JS error buttons | 3 | 0 |
| narrow 900 FX/Web | 1 | 0 |
| offline, stopped, crashed | 3 | 0 |

Из двенадцати ранее отсутствующих записей фактически сработали 11:
№4 header; Web №8 content/details; три №10 buttons; №13 replaceFile;
три №14 screens; №15 wrap. Desktop №8 details не сработала и не замаскирована.
Ни исходный полный отчёт, ни его unused-состояние не переписывались.

Checkpoint replaceFile использует существующий CPS Chooser с явным
`sample.name + .md`: реальная Swing/Web confirmation присутствует, FX
повторный alert отсутствует. Как определено harness contract §10 №12,
OS chooser обходится самотестом. Настоящий Windows native replacement prompt
не тестировался; `chooserNativeConfirmation=not-tested` записано в context.
Это доказательство поведения приложения при штатном selftest chooser result,
не доказательство взаимодействия с OS prompt.

Native absent пока не подтверждён: Clear с продолжающим работу recorder
может легитимно немедленно записать новый снимок. Новый CPS честно называет
checkpoint `snapshot-after-clear`, не включает его как absent-reference.
Web absent проверен в полном прогоне по снятому payload, а не по позднему диску.
Удаление файлов под работающим recorder не выполнялось.

Проверка предложенной ветки fresh startup -> Esc -> showLast по исходникам:
`StartupFlow.ordinary(true)` устанавливает и запускает recorder до
`firstRunWizard()`. `beginRecording()` вызывает start и touch.
`SessionBridge.capturePlan()` сохраняет markdown и для плана без файла,
даже без правок; отмена мастера не отключает recorder. Debounce 400 мс и
periodic 5 с делают раннее отсутствие гонкой, не гарантированным fixture.
На этом основании отдельный native fresh-absent прогон не запускается.

Generator теперь добавляет явно именованную mixed-state пару
`snapshot-native-prepared-vs-web-absent`: native checkpoint действительно
prepared, Web absent подтверждён собственным тестом по captured payload.
Это самостоятельное покрытие реального content/details diff, не
подтверждение одинакового модельного состояния и не доказательство native
absent. Used по-прежнему изменяется только filter настоящих DumpDiff.
Дополнительное поглощение той же записи не увеличивает число used entries.
Первоначальные manifest/audit и все JSON observations сохранены без изменений;
диагностический повтор mixed-state сохраняется в отдельном output.
Он выполнен без GUI на прежних неизменённых observations:
11 пар, 15 raw differences, 14 поглощены, 1 rejected; used entries по-прежнему
11, unused 23, exit 1. Новый output:
`C:/Users/Oscar/AppData/Local/Temp/cashprediction-allowance-fresh-analysis-b8cb6a0d-7992-47d3-b847-912324df61d1/evidence`.
Это повтор анализа старых captures, не переснятые references для нового
core 17:33:36. Новый native capture ожидает release Newton и одновременно
готовые core + FX/Swing JAR с явным environment. Потерянные suffix старых
JSON не восстанавливаются.

MAIN подтвердил раннее стирание suffix в SelfTestRunner и исправляет явный
environment/нормализацию prefix; FX/Swing wiring и новые JAR ещё ожидаются.
Старые JSON уже потеряли информацию; восстанавливать fx/swing в них нельзя.
References требуется переснять только после новых JAR. Даже после этого
payload может отличаться savedAt, client и geometry: правило desktop №8
разрешает только client suffix, а не произвольный payload. Для одинаковых
данных нужен согласованный capture без автоматической перезаписи.

Предложение MAIN для absent: штатный second-instance/no-recording режим
или явная тестовая paused-recorder fixture до capture; затем Menu showLast
и настоящий dump. Нужен одинаковый исходный snapshot для Registry/XML,
также без записи живым recorder во время сравнения. Если такого lifecycle
контракта нет, core/API fixture требует отдельного решения MAIN; здесь
ни core scenarios, ни recorder, ни goldens не менялись.

## Изолированные команды

```powershell
$evidenceRun = 'C:/Users/Oscar/AppData/Local/Temp/cashprediction-allowance-native-c694100f-7eae-44c5-918d-aa639648d026'
$evidenceDeps = 'C:/Users/Oscar/AppData/Local/Temp/cashprediction-hotkey-fidelity-166b865e-ff4c-4069-971b-a4138db17ff0/dependencies'
$evidenceJunit = 'C:/Users/Oscar/.m2/repository/org/junit/platform/junit-platform-console-standalone/1.14.4/junit-platform-console-standalone-1.14.4.jar'
$evidenceCp = "$evidenceRun/classes;$evidenceRun/reactor/core/target/cashprediction-core-1.0.0.jar;$evidenceDeps"
javac -encoding UTF-8 --release 25 -Xlint:all,-serial -cp "$evidenceCp;$evidenceJunit" -d "$evidenceRun/classes" ui-parity/src/test/java/ru/cashprediction/parity/audit/*.java ui-parity/src/test/java/ru/cashprediction/parity/pipeline/ParityPipeline.java ui-parity/src/test/java/ru/cashprediction/parity/pipeline/ParityTest.java ui-parity/src/test/java/ru/cashprediction/parity/pipeline/ModuleSnapshot.java ui-parity/src/test/java/ru/cashprediction/parity/pipeline/GoldenMeasurements.java
java -XX:-UsePerfData '-Dparity.realClients=true' '-Dparity.clients=web' '-Dparity.browser=C:/Program Files/Google/Chrome/Application/chrome.exe' '-Dparity.project.version=1.0.0' "-Dparity.reactor.root=$evidenceRun/reactor" "-Dparity.build.directory=$evidenceRun/complete-web-runtime" -jar $evidenceJunit execute --disable-banner --details=summary -cp $evidenceCp --select-class ru.cashprediction.parity.audit.WebAllowanceTest --reports-dir "$evidenceRun/complete-web-reports"
# Desktop команда исполнена только после release MAIN; повтор требует нового согласованного слота.
java -XX:-UsePerfData '-Dparity.realClients=true' '-Dparity.allowance.desktopReleased=true' '-Dparity.project.version=1.0.0' '-Dparity.javafx.version=25.0.4' "-Dparity.reactor.root=$evidenceRun/reactor" "-Dparity.build.directory=$evidenceRun/complete-native-runtime" -jar $evidenceJunit execute --disable-banner --details=summary -cp $evidenceCp --select-class ru.cashprediction.parity.audit.NativeAllowanceReferenceTest --reports-dir "$evidenceRun/complete-native-reports"
java -cp $evidenceCp ru.cashprediction.parity.audit.AllowanceEvidenceRun "$evidenceRun/complete-native-runtime/parity" "$evidenceRun/complete-web-runtime/parity" "$evidenceRun/allowed-diffs.json" "$evidenceRun/evidence"
java -XX:-UsePerfData "-Dparity.reactor.root=$evidenceRun/final-unit-runtime" -jar $evidenceJunit execute --disable-banner --details=summary -cp $evidenceCp --select-class ru.cashprediction.parity.audit.AllowanceEvidenceTest --select-class ru.cashprediction.parity.audit.ReportAuditTest --select-class ru.cashprediction.parity.audit.WebAllowanceSessionTest --select-class ru.cashprediction.parity.pipeline.ParityPipelineTest --reports-dir "$evidenceRun/final-unit-reports"
```

При повторе использовать новые output directories, не перезаписывать этот
прогон. Для MAIN: добавить manifest-property к согласованному ParityTest
после новых references; это не запускает desktop из callback, а сравнивает
закреплённые наблюдения в том же экземпляре AllowedDiffs. Один оставшийся
rejected diff пока должен оставить общий результат RED. Maven/shared classes,
core/golden/source collector, visual-файлы, commit/push не затронуты.
