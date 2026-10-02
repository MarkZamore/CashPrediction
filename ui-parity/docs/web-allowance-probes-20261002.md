# Реальные дополнительные Web-пробы §10, 02.10.2026

Обновление: теперь получен единый полный GREEN 9/9 на прежней frozen-паре
(24,198 с, `complete-nine-reports`) и ещё один полный GREEN 9/9 на новой
frozen-паре (24,428 с). Реальная supplemental интеграция, SHA новых сборок,
native references и оставшийся desktop diff описаны в
`allowance-evidence-integration-20261002.md`. Исторические попытки ниже
сохранены; их смешанная сводка больше не является последним результатом.

Добавлены только `audit/WebAllowanceSession.java`, `audit/WebAllowanceTest.java`,
`audit/WebAllowanceSessionTest.java` и этот документ. Сценарии/golden/core,
AllowedDiffs.used, допуски, visual-файлы и основной ParityTest не менялись.
Это отдельное подтверждение реальных Web-состояний; оно не отмечает записи
использованными в экземпляре AllowedDiffs основного прогона 18x3.

## Реализованный путь

Каждая проба запускает свой WebMain без selftest, но с существующим test-api,
no-browser/no-window, свежими CashMemory и UUID selftest-узлом. ClientJarSnapshot
делает отдельные копии замороженных jar. Chrome запускается headless через
EdgeLauncher/CDP. До подготовки ожидается живой NEW_PLAN_WIZARD, затем его
Cancel через test API и Sample. Полный дамп должен показать file.sample=1:
само наличие test API ещё не доказывает завершённый bootstrap.

Обычные действия выполняет CdpTestApi через настоящие виджеты. Post-exit
нажатия используют живые DOM-кнопки, потому что штатное awaitIdle после
остановки транспорта неприменимо. Экраны после выхода собираются в отдельные
`screen-*.json` из DOM: kind/title/text/buttons/open/mainInert. Это не полный
UiDump; counters не подставляются. Ядро и showScreen напрямую не вызываются.

## Доказательство и исправления стенда

Корень артефактов:
`C:/Users/Oscar/AppData/Local/Temp/cashprediction-web-allowance-45cb3391-e137-495d-b88f-8ccba0624f9e`.

| Проба | Успешный исходный каталог относительно корня |
| --- | --- |
| halt-cancel | bootstrap-fixed-runtime/parity/allowance-halt-cancel-6900523702675126297 |
| snapshot-prepared | bootstrap-fixed-runtime/parity/allowance-snapshot-prepared-8858863640914732573 |
| js-error | bootstrap-fixed-runtime/parity/allowance-js-error-14924934579319488443 |
| offline | bootstrap-fixed-runtime/parity/allowance-offline-18381387762320678019 |
| stopped | bootstrap-fixed-runtime/parity/allowance-stopped-9037969145104592715 |
| crashed | bootstrap-fixed-runtime/parity/allowance-crashed-1157627133594241483 |
| replace-file | failed-only-runtime/parity/allowance-replace-file-8773586640138868279 |
| narrow-toolbar | failed-only-runtime/parity/allowance-narrow-toolbar-16442234516676075141 |
| snapshot-absent | absent-only-runtime/parity/allowance-snapshot-absent-14610736428024594565 |

Суммарно девять разных проб подтверждены, без assumptions. Это не один
зелёный полный отчёт: `bootstrap-fixed-reports` содержит 6 PASS и 3 FAIL
за 25,449 с; `failed-only-reports` - 2 PASS и 1 FAIL за 10,254 с;
`absent-only-reports` - 1 PASS за 4,307 с. Первоначальные `reports` и
`retry-reports` также сохранены без перезаписи. Перезапускались только
неисправленные пробы после стабилизации bootstrap. Все FAIL относились
к новому стенду/его предположениям, не к воспроизведённому дефекту Web.

- Ошибка numeric substring в handshake отрезала h из http: исправлена
  длиной буквального префикса и защищена отдельной unit-регрессией.
- Гонка bootstrap оставляла открытый мастер и пустые counters: добавлено
  ожидание реального окна, явная отмена и обязательный file.sample=1.
- Save As случайно выбирал временный web-session.plan.md: фильтр исключает
  служебные web-session-файлы и требует ровно один план. Настоящий replaceFile
  показан для сохранённого Пример.md; после отмены байты файла неизменны.
- При 1000 тулбар помещался в один ряд: это не ошибка Web, №15 разрешает
  перенос при нехватке ширины. В отдельном viewport 900x800 получены реальная
  frame.contentWidth=900, wrap=true и item.row>0. Допуски не изменены.
- Clear удаляет snapshot, но оставляет running marker в web-session.md.
  Открытие сообщения может запустить следующую запись recorder. Поэтому
  отсутствующий снимок проверяется по точному content и decodeDocument
  payload снятого details: marker присутствует, snapshot=null. Более позднее
  содержимое файла не используется как состояние на момент дампа.

Halt-cancel проверяет точный Web header и отмену без остановки сервера.
Prepared snapshot требует сохранённый файл снимка, раскрытые details и
строку Server со снимком. JS-проба генерирует настоящий асинхронный throw
в странице, видит ровно reloadPage/continueWork, сохраняет message/stack,
нажимает continue и проверяет живой сервер без alert. Offline возникает
после завершения только собственной JVM; stopped/crashed - после живых
кнопок file.exit/halt и настоящего exit effect. Все экраны проверены по
локализованным title/text и modal/inert; offline имеет кнопку retry.

## Замороженные сборки

| Jar версии 1.0.0 | Время исходного файла UTC | SHA-256 |
| --- | --- | --- |
| cashprediction-core | 2026-10-02T11:50:38.1473730Z | D68DBB79E72AA7FC3ECD74CD44FD7684CC76D44772E5400D4FD9C05D1D5BF294 |
| cashprediction-web | 2026-10-02T11:52:07.4602554Z | B4E48CA1970898F78DA441904632A1840C9915076853B4E29C40CA260008008F |

Базовые reactor-копии read-only. По окончании все 64 jar (по 32 каждого
модуля, включая копии ошибочных запусков) сохранили эти SHA. Собственных
Java/Chrome процессов с UUID корня осталось 0. Каждый завершённый сеанс
удалил только свой selftest-узел и прошёл сравнение real session до/после.
Desktop Robot, Node, Maven, commit/push и новые агенты не использовались.

## Команды и оставшиеся ограничения

Фактическая компиляция только audit-каталога и пример запуска всей выборки
в новом isolated output (реальные пробы запускать только в разрешённом слоте):

```powershell
$allowanceWebRun = 'C:/Users/Oscar/AppData/Local/Temp/cashprediction-web-allowance-45cb3391-e137-495d-b88f-8ccba0624f9e'
$allowanceWebDeps = 'C:/Users/Oscar/AppData/Local/Temp/cashprediction-hotkey-fidelity-166b865e-ff4c-4069-971b-a4138db17ff0/dependencies'
$allowanceWebJunit = 'C:/Users/Oscar/.m2/repository/org/junit/platform/junit-platform-console-standalone/1.14.4/junit-platform-console-standalone-1.14.4.jar'
javac -encoding UTF-8 --release 25 -Xlint:all,-serial -cp "$allowanceWebRun/reactor/core/target/cashprediction-core-1.0.0.jar;$allowanceWebDeps;$allowanceWebJunit" -d "$allowanceWebRun/classes" ui-parity/src/test/java/ru/cashprediction/parity/audit/*.java
java -XX:-UsePerfData '-Dparity.realClients=true' '-Dparity.clients=web' '-Dparity.browser=C:/Program Files/Google/Chrome/Application/chrome.exe' "-Dparity.reactor.root=$allowanceWebRun/reactor" '-Dparity.project.version=1.0.0' "-Dparity.build.directory=$allowanceWebRun/new-runtime" -jar $allowanceWebJunit execute --disable-banner --details=summary -cp "$allowanceWebRun/classes;$allowanceWebRun/reactor/core/target/cashprediction-core-1.0.0.jar;$allowanceWebDeps" --select-class ru.cashprediction.parity.audit.WebAllowanceTest --reports-dir "$allowanceWebRun/new-reports"
```

Для точного ограниченного повторения добавить
`-Dparity.allowance.probes=snapshot-absent,replace-file,narrow-toolbar` либо
одиночное имя. Фактические повторы использовали три имени, затем только
snapshot-absent. Unit ReportAuditTest + WebAllowanceSessionTest: 7/7 успешно,
0 ошибок/пропусков, 118 мс; отчёт `unit-reports/TEST-junit-jupiter.xml`.

Desktop FX/Swing references и native FX suppression replaceFile пока не
исполнены: Newton/Banach не видны в доступном списке чатов, слот не подтверждён.
Эти Web-наблюдения не подтверждают desktop №8/details, №13 native prompt
или pairwise diff при narrow width. JS reload как отдельное действие ещё
не проверено (кнопка наблюдалась; выполнен continue). Сборщик narrow full
PNG через общий UiTestDriver по-прежнему требует 1200x800 и не изменялся.

Интеграция в строгий unused-аудит требует согласованного desktop/contract
reference и передачи настоящих diffs в тот же AllowedDiffs экземпляр до
финального аудита. Никаких synthetic used-флагов или подавления 12 записей
не добавлено. Изменения core CPS/golden для этих самостоятельных Web-проб
не понадобились. Предложения точных дополнительных действий для MAIN
сохранены в `allowance-coverage-20261002.md`; здесь они не применены.
