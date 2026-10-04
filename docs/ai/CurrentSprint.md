# Текущая итерация CashPrediction

Рабочий контекст для AI. Не входит в архив исходников или портативную поставку.
Обновлять после значимого результата, а не переписывать историю завершённых проверок.

## Актуальная CI-политика и checkpoint (04.10.2026)

По новому решению пользователя автоматический GitHub CI/CD должен быть лёгким; тяжёлые полные проверки выполняются локально либо явным ручным запуском GitHub с `full_checks=true`. Это часть S4, не отложенная S5 задача. Новые workflows интегрированы: compile и короткий smoke не заменяют UI/E2E/portable, полную матрицу, обновление и публикационные guards. Fail-closed выбор базы не должен превращать автоматический запуск в тяжёлую приёмку. Docs-only сохраняет repository-doc-audits. Публикация не получает обход S7_APPROVED_SHA или source/artifact identity.

Статус: финальный root full install завершился 04.10.2026 в 22:42:48 +05:00: BUILD SUCCESS, все 7 модулей, 26:23. Ядро: 2568 tests, 0 failures/errors, 10 skipped. Этот install не является PASS всего локального pipeline или native approval. Финальное исправление barrier интегрировано; module-local test scheduler устранил зависимость FX теста от чужих test classes без новых зависимостей продукта. Focused core/FX regression: 24 PASS, 0 skipped. Лог полного install: C:/Users/Oscar/AppData/Local/Temp/cp-s4-final-candidate-full-install-20261004.log. Receipt `.claude/scratch/light-ci-main-smoke-20261004.md` отдельно фиксирует actual smoke exit0, 24.930 s; это короткий набор, не полный acceptance. Root workflows переведены на лёгкий автоматический маршрут, локальные static/argv checks: 155 PASS; impact/CLI checks: 147 PASS; полный постоянный workflow contract: 415 PASS, smoke contract: 30 PASS. Далее требуется последовательная локальная приёмка точного committed SHA до push, затем проверка нового Actions run. Actual remote CI green ещё не подтверждён. Старые записи ниже сохраняются как история, не как итог нового кандидата. S4/S5 и native approval не объявлены завершёнными.

## Цель и приоритеты

1. Завершить S4: устранить подтверждённые блокеры, получить лёгкий зелёный GitHub CI и отдельно реальные обязательные тяжёлые проверки для того же кандидата.
2. Включить в S4 разделение документации и проверяемый состав исходного `.7z`.
3. Затем завершить S5: соответствие требованиям, UX, производительность, тихое обновление и подготовка поставки. Бывший S7 входит в S5.
4. Выполнить независимую S6 и проверить именно итоговые исходники и portable, прежде чем разместить их в корне.

Перенос задач по запросу пользователя 04.10.2026: вся разработка и подготовка приёмки
входит в S5, включая verification runners/validators, recovery procedures, census,
таблицы требований, исправления, документы/шаблоны sign-off, B1/B2/T и подготовку
portable/source archive с extracted-source build. S6 - только независимая финальная
приёмка frozen committed кандидата, полная чистая матрица, skeptics и подпись.
Новые дефекты S6 возвращаются в S5; не исправлять проверяемый checkout и не считать
подготовительные отчёты независимой подписью. Зелёный CI S4 остаётся первым приоритетом.

Документальная партия: авторство всех десяти документов закончено; независимые reviews
получены, точечные corrections и обновление guard выполнены. Это не закрытая приёмка S4
и не восемь активных авторских задач. Сейчас приоритет - зелёный CI S4; дальнейшее
расширение Graphify и интеграция S5/S6 отложены с сохранением прогресса. По новому
назначению пользователя шесть агентов готовят изолированные S5-only patches;
два собирают свидетельства CI и actual extracted-source build. Извлечённая
S4 rehearsal сборка завершилась успешно; актуальный CI отказал на четырёх core cases,
устраняются точные причины. Полная native-приёмка остаётся pending.
Шесть технических документов описывают фактический код по-русски,
без вымышленных возможностей, SQL, Storybook или CoreUI. Эта документальная партия
не разрешает новые запуски Maven/GUI или изменения кода.

## Статус на 04.10.2026

Дополнение: run `37210862261` для `a7d542f` отказал до сборки на чтении режима
дисплея. MAIN воспроизвёл ошибку PowerShell binder без native calls:
`IsNull(string, $null)` возвращает false, с `[NullString]::Value` - true.
Пять вызовов Win32 исправлены на настоящий NULL; добавлены отрицательный контроль
и проверка всех callsites. Фактическую настройку runner подтвердит следующий CI.

Актуальное уточнение после предыдущих записей: CI run `37207973876` для
`551bc520490b412da992da5135cccce14378d556` завершился failure. `Build and test`
успешен; обязательный `Actual UI gates` отказал на
`GateCoverageDesktopTest.actualDesktopFocusRoundTrip`: `Desktop too narrow`, строка 29.
Recovery, полные профили и portable не выполнялись и не считаются успешными.
MAIN добавляет настройку поддерживаемого режима экрана Windows-runner до сборки
и раннюю проверку логической ширины JDK. Порог существующего gate и настоящие
Robot-проверки не ослабляются. Локальная компиляция Win32 ABI и Java probe успешна,
но фактическое применение режима и GUI-приёмка требуют нового CI.

| Задача | Статус и свидетельство |
|---|---|
| Общая компиляция | `mvn -B -ntp -DskipTests install`: семь модулей успешны, 16:21. Тесты этой командой не проверены. |
| Исправления отказов предыдущего install | Свежий выборочный прогон: 144 теста, без ошибок, отказов и пропусков, 16:19. Это не полный install. |
| Полный install | Новый `mvn -B -ntp install` успешен: все семь модулей, завершение 17:42:27, 23:58 мин, session51893. В core 2553 теста, 0 отказов/ошибок, 10 environment/opt-in пропусков. Полная обязательная GUI-матрица выполняется отдельно. Предыдущий отказ 16:14 и его первичные отчёты сохранены. |
| Swing s18 | Один диагностический запуск: семь шагов успешны. Не заменяет всю матрицу. |
| Desktop preflight | По разрешению пользователя повторён с пассивной диагностикой: один тест PASS, без отказов, ошибок и пропусков, завершение 17:15:48, session15115. Строгий expected `7` и однократный Robot-ввод сохранены. Это не вся UI-матрица; причина прежнего `?` не установлена. |
| Документы | Все десять документов написаны и reviewed. После исправления guard полный doc-audit: 35 тестов PASS, без пропусков, 17:13:37. После устранения навигационной зависимости AI-документов от локального scratch те же 35 тестов успешны внутри полного install 17:42:27. |
| Упаковка | Политика требует шесть технических документов и исключает AI-контекст. Сохранённые focused receipts: FinalDelivery 469 checks / 72 cases PASS, embedded candidate pins 16 PASS, boundaries 68 PASS, agent policy 30 PASS, SKILL staging 108 PASS. Это synthetic/mock/StageOnly, не реальный source build или native PASS. |
| Actual source build | Настоящий S4 rehearsal архив создан, integrity/extraction PASS: 1924 файла, шесть технических документов, без агентских файлов. SHA-256 архива `7E764B30AC7EE9649BB8772F71ACCB61DC71C359BE2AEABFC77F6A838E385DDB`. Извлечённый `mvn install` с собственным изначально пустым Maven repository завершился BUILD SUCCESS, session3342 exit 0: все шесть поставляемых модулей, 24:50 мин, 18:10:03. Rehearsal соответствует app source 8bead73; более поздний fix двух CI-скриптов не включён в этот архив. Это не конечная поставка S5/S6. |
| Удалённый CI текущих изменений | Первый run 37203159067, SHA8bead73, отказал до компиляции на PowerShell AppendAllLines. Writer исправлен, полный workflow fixture 210 PASS. [Run 37203591501](https://github.com/MarkZamore/CashPrediction/actions/runs/37203591501), SHA `fb0cea7306b8ccee8ac4661bf8aaa5037c775eb5`, завершился FAILURE: core 2553 tests, 1 failure, 3 errors, 1 skip. Три ошибки CASHMEMORY_ROOT в новых fixtures: runner использует `C:/Users/RUNNER~1`, canonical root отличается от входного alias. Fixture roots приведены через toRealPath, без изменения production guards. В ReconnectCredentials добавлено bounded ожидание строгой private lock initialization в прежнем общем межпроцессном budget, без расширения ACL. Targeted 27/27 PASS без пропусков, 18:36:07; workflow fixture 251/0 и отдельный отказ конфликтующих scope switches проверены. Полный install исправленного root работает: session43980, log cp-s4-ci-fixes-full-install.log. Новый коммит/push ещё не выполнен. UI/E2E/portable steps прежнего run не выполнялись. Green не подтверждён. |
| S5, S6 и конечная поставка | Не завершены. Статусы требований и приёмки не повышать по наличию черновиков. |

Новое подтверждение после исправлений CI: полный root install session43980 завершился exit0 / BUILD SUCCESS в 19:02:59, 25:44 мин, все семь модулей. Всего 3246 tests, 0 failures/errors, 133 environment/opt-in skips; core 2559, update-tool 24, FX 252, Swing 236, Web 140, doc-audits 35. Пропуски не считаются GUI/native PASS. Финальная actual версия workflow fixtures после scope-conflict guard: 251/0. Исправления готовятся к отдельному commit/push; нового удалённого green ещё нет.

Источники статуса: локальный `.claude/scratch/integration-2026-10-04-audit-complete.md`
фиксирует session67475 (полный install с отказами, исходные отчёты сохранены в
`C:/Users/Oscar/AppData/Local/Temp/cp-s4-install-failure-b3838c67c0944c41ba403fd55a4cedf4`),
session47924 (144 выборочных теста) и session74380 (компиляция с пропуском тестов).
Это сохранённые свидетельства MAIN, не новые запуски автора документа.
Новое свидетельство doc-audit передано MAIN в текущем задании: первый запуск 33 тестов
выявил obsolete regex для одноаргументного guard. Проверка обновлена для строгого
binding storage + owned CashMemory, добавлены девять positive/negative cases;
повторный запуск 34 тестов PASS в 16:43:02. Это устранение устаревшего audit-контракта,
не основание объявить весь S4 зелёным. Автор документа этот запуск не повторял.
Локальный `.claude/scratch/swing-s18-native-once-review.json` привязан к
`C:/Users/Oscar/AppData/Local/Temp/cp-swing-s18-2130df9194cb4eb89c0c04d2de1e679d/native-once`;
он не доказывает исправление причины старого отказа или полную native-приёмку.
Старый локальный `.claude/scratch/full-install-report-2026-10-04.md`
относится к ночному checkpoint, а не к текущему кандидату.

Независимые reviews сохранены локально в `.claude/scratch/`:
`architecture-six-docs-coverage-review.md`, `documentation-stage-consistency-review.md`,
`documentation-link-guard-independent-review.md`, `db-schema-independent-review.md` и
`ui-kit-class-mapping-independent-review.md`.
F1/F3 дополнены в schema, F2 имеет отдельный локальный `architecture-six-docs-F2-closure.md`;
это не автоматическое закрытие остальных замечаний. Guard review отделяет допустимый
локальный путь от реально поставляемого файла и выявляет пропуски разбора ссылок;
его исправление и новый regression receipt принадлежат MAIN.
Локальный `.claude/scratch/docpack-integration-20261004-receipt.md`
сохраняет команды, SHA входов и границы уже выполненных focused проверок.
Эти проверки при обновлении статуса не повторялись. Указанные scratch/TEMP paths -
происхождение локальных свидетельств, а не обязательные файлы clean checkout.
Они не включаются в Git или поставку; репозиторная навигация ниже ведёт только
к документам и исходникам кандидата.

## Файлы и исполнители

Состав этапов: [stages.md](../design/stages.md). Проверяемый список: [PROJECT_REQUIREMENTS.md](../../PROJECT_REQUIREMENTS.md).
Границы изменения: [ChangeRequest.md](ChangeRequest.md); карта контекста: [ContextDump.md](ContextDump.md).

- Авторы готовых технических документов: Huygens - `architecture.md`, Peirce - `techstack.md`, Halley - `ui-kit.md`, Einstein - `db-schema.md`, Heisenberg - `edge-cases.md`, Sartre - `linx.md`. Авторство закончено; текущие точечные corrections не означают повторное создание документов.
- Четыре AI-документа reviewed: `CurrentSprint.md`, `ContextDump.md`, `ChangeRequest.md`, `LegacyWarning.md`; очереди на создание последних двух больше нет.
- Текущие восемь назначений: Peirce наблюдает полный исправленный root install session43980; Huygens завершает SOA consumer migration; Descartes исправляет найденные ограничения Swing Tab acknowledgement; Halley закрывает cleanup новых Swing buffer-close fixtures; Sartre подключает Web capture envelope к авторизованному маршруту и commit-last persistence; Einstein готовит валидатор настоящих manual recovery receipts S6; Heisenberg готовит isolated orchestration реальных B1/B2/T с provenance; Pasteur проверяет offline release cleanup/retry cases. Это отдельные непересекающиеся задачи, не повтор выполненного аудита. Все восемь статусов running проверены после назначения; исторический список не доказывает их дальнейшее состояние.
- Шесть подготовительных S5 задач пишут только собственные scratch patches/handoffs или temp mirrors. Root app source, workflows и эталоны S4 не меняют. Новый Maven/GUI/native/network/commit/push им не назначен. MAIN принимает изменения только после отдельного review; готовый patch не равен integration или PASS.
- Ведущий (MAIN): обновление guard, назначения и интеграция corrections, этапы, аудит документов и состава поставки, общий Maven, настоящий GUI и GitHub CI. Количество активных исполнителей не выводить из исторического списка владельцев; новые назначения уточнять у MAIN.

## Ограничения и критерии завершения

До восьми полезных непересекающихся задач. Авторство всех десяти документов закончено, reviews получены;
возврат к отложенным задачам только по актуальному отдельному назначению MAIN.
Сохранять историю, исходные отчёты, незакоммиченные изменения и прежние scratch-предложения.
Общий Maven, Robot, клавиатура и portable-проверки выполняются последовательно одним владельцем.
Сначала код и компиляция, затем целевая проверка; не повторять всю GUI-матрицу после каждой правки.
Нельзя ослаблять проверки, обновлять эталоны под дефект или считать диагностический захват приёмкой.
S4 завершается только актуальными обязательными результатами и зелёным CI того же коммита.
Проверка `.7z` включает состав, исключение AI-файлов и сборку фактически извлечённого конечного архива.

Документальный gate S4 требует все шесть `docs/design/{architecture,techstack,edge-cases,db-schema,linx,ui-kit}.md`
в исходном `.7z`; прежний whitelist «только architecture.md» больше не является критерием.
Четыре рабочих документа `docs/ai/{CurrentSprint,ContextDump,ChangeRequest,LegacyWarning}.md`
не входят ни в исходный архив, ни в portable. Готовый текст сам по себе не доказывает
новую политику упаковки: MAIN отдельно проверяет фактический состав и отрицательные fixtures,
не ослабляя остальные gates. До этих свидетельств документальная партия не закрывает S4.

Порядок следующих шагов: получить обязательный GitHub CI опубликованного SHA;
параллельно, на отдельной машине от runner, закончить уже запущенную сборку извлечённого
S4 source-packaging rehearsal. При отказе исправлять точную причину, а не ослаблять gate.
Рабочие metadata updates не публиковать посреди живого run, чтобы не отменять его.
Уже выполненные focused pack fixtures без изменения входов не повторять;
успешный focused desktop preflight не заменяет всю UI/E2E-матрицу.
Конечная поставка остаётся после S5/S6.

Последний неуспешный UI-запуск: `ui-parity/target/gates/a4430ee6-bcef-4242-88ab-431490c95ebf`.
Первичные свидетельства находятся в `actual`, публикуемые очищенные отчёты - в `upload`.

## Desktop-preflight: история отказа и повторной проверки

Первичный отказ: `reports-04-GateCoverageDesktopTest/ru.cashprediction.parity.audit.GateCoverageDesktopTest.txt`
в указанном gate, строка 84 теста; один тест, один отказ, без ошибок и пропусков.
В `actual/parity/desktop-preflight-11969564840112729722/report-preflight.txt` и
`actual-window.png` поле содержит `?`. До единственного `Robot VK_7` прошли проверки
попадания указателя и AWT-фокуса; итоговый снимок через 10038 мс показывает отсутствие
фокуса и указатель вне поля. PNG не показывает отсутствующего поля или обрезанного введённого символа.
Запись не содержит key events, модификаторов и активной OS-раскладки: нельзя доказанно
выбрать между раскладкой, модификаторами и потерей фокуса в момент ввода.
JVM locale `ru` и transform `2.0` сами по себе причины не доказывают.

Сохранена неизменённая рабочая копия теста:
`C:/Users/Oscar/AppData/Local/Temp/cp-desktop-preflight-diagnostic-a4430ee6/source/GateCoverageDesktopTest.java`.
Её исходный SHA-256: `D267AB29933685A7A4653DD748A1E61DD683690FA1A41BA08FB73E7F8CAAA77C`.
После этой первичной копии в тест добавлены пассивные наблюдения событий и фокуса.
По отдельному разрешению пользователя выполнен успешный повтор без подмены ввода.
Новые actual/reports сохранены в
`C:/Users/Oscar/AppData/Local/Temp/cp-desktop-preflight-retry-db4e0be4c7904e0db8fb0cb4bf945ae1`.
Один тест PASS за 7.448 с, весь запуск 11.580 с. Первичный отказ не удалён;
повтор доказывает работоспособность preflight в новом запуске, но не его прежнюю причину.
Все прежние scratch-патчи и receipts остаются на месте.
