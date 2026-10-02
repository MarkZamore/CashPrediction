# Аудит покрытия §10 для полного отчёта 02.10.2026 16:32

Источник: `ui-parity/target/parity/report-full-20261002-1632.html`,
SHA-256 `83d54c975f96ed8e083de12b540bc67e7f926cec5ea2b03829875fa4ed4f0a9f`.
Машиночитаемое извлечение: `allowance-audit-20261002-1632.json` (2953 символа).
Оригинал отчёта и результаты других владельцев не изменены.

## Что подтверждает отчёт

617 блоков ошибок: 604 непоглощённых различия, 12 unused-записей и один
сбой сбора Swing s12. Сбой содержит четыре FAIL fill: нет enabled focus
target в w2/w3/w4. Его путь сохранён в `collectionErrors` JSON-аудита.
105 групп сравнения вместо ожидаемых 108: отсутствуют golden Swing s12
и две пары с ним. Все 18 сценариев были выбраны, но это не зелёный полный
паритет. Геометрию и визуальные проверки этого отчёта здесь не исправляем.

Корневые группы 604 различий: frame 199, table 148, alerts 115,
toolbar 36, windows 34, menuBar 31, popups 23, contextMenus 12, status 6.
JSON сохраняет также группы comparison/root и числа по сценариям, без
объёмных значений expected/actual. Две записи №8/details сохранены отдельно.

Текущие 18 CPS не вызывают `recovery.showLast`, `recovery.simulate.halt`,
`recovery.simulate.exception` или `file.exit`. В текущих golden нет
alerts purpose simulateHalt, lastSnapshot, uncaught, replaceFile; screens
везде пусты. S08 делает snapshotNow, но не showLast. S14 снимает два
overwriteOnFirstSave, а не chooser-confirmation replaceFile, и Save As
выбирает новый `Другой план.md`. WebScenarioSession задаёт viewport 1200x800.
Это конкретные пробелы покрытия; данных для ослабления unused-аудита нет.

## Дополнительные реальные пробы для обсуждения с MAIN

Сценарии ниже - предложения, не исполненные проверки. Изменения CPS/golden
в core принадлежат MAIN и здесь не внесены. Одного зелёного отдельного теста
недостаточно: он должен передать реальные различия в тот же экземпляр
AllowedDiffs до финального unused-аудита. Текущее ParityTest создаёт его
на матрицу 18 CPS, а тесты в других JVM не могут отметить его записи.

| Записи | Недостающее действие | Проверяемое различие и условия |
| --- | --- | --- |
| №4 `/alerts/simulateHalt/header` | Отдельный CPS: `key Esc`, `sample`, `menu recovery.simulate.halt`, `dump halt-question`, затем отмена | Снять реальный WARNING до ответа у desktop и Web; допустима только пара точных header из UiText. Нажимать halt для этой пробы не требуется. |
| №8 `/alerts/lastSnapshot/content` Web | `menu recovery.showLast`, `dump snapshot-absent`; отдельно подготовленный одинаковый снимок и `dump snapshot-present` | Проверить строки Server против Registry/XML, раскрытые details, порядок хранилищ. Не полагаться на времена разных живых recorder: сначала детерминированные fixture и одинаковое содержимое. |
| №8 `/alerts/lastSnapshot/details` desktop и Web (две записи) | Те же два снимка showLast; сравнение FX/Swing отдельно от Web | Для desktop должно реально остаться различие только `<node>/fx` против `<node>/swing`. Сейчас полный node/client нормализуется целиком; см. ограничение ниже. XML paths session-fx.xml/session-swing.xml и payload также требуют явной проверки, а не новой широкой маски. |
| №10 три `/alerts/uncaught/buttons/*` | В настоящей Web-странице вызвать контролируемое асинхронное исключение, например `setTimeout(() => {throw new Error('audit-js')}, 0)` через CDP | Реальный window.error проходит clientError/HTTP. В Web отсутствует closeProgram и есть reloadPage/continueWork. Обычный `recovery.simulate.exception` вызывает серверное/JVM исключение и не заменяет JS-пробу. Проверить текст/stack отдельно, работающий сервер после continue, отдельным опытом reload. Для использования allowances сравнить реальные именованные buttons с явно обозначенным desktop-контрактом или согласованным desktop-снимком; различия content/stack не поглощать кнопочными правилами. |
| №13 `/alerts/replaceFile` FX | В свежем CashMemory сохранить пример; затем выбрать тот же существующий файл с явным `.md` через `chooser "Пример.md"`, `menu file.saveAs`, снять replacement checkpoint | Swing/Web должны показать replaceFile; FX с nativeReplacePrompt и неизменённым расширением не показывает повторный alert. Если расширение дописано ядром, nativeConfirmed=false, и FX тоже спрашивает: этот вариант не подтверждает allowance. Первый Ctrl+S conflict purpose overwriteOnFirstSave также не подтверждает его. Нужен отдельный согласованный checkpoint: FX уже завершил save, Swing/Web ещё ждут ответа. Остальные различия состояния нельзя скрывать allowance на одном alert. |
| №14 `/screens/offline` | Headless Web и отдельный собственный сервер: после готовности оборвать только его соединение/процесс; дождаться настоящей ошибки транспорта | Снять реальный DOM экрана offline с текстами bootstrap и кнопкой retry; сравнить отсутствие screens в desktop/эталоне. Не вызывать showScreen напрямую. |
| №14 `/screens/stopped` | В отдельной свежей вкладке нажать `file.exit`, при необходимости разрешить discard | Дождаться exit effect WEB_STOPPED и снять DOM после остановки; это завершает приложение, поэтому обычный следующий CPS dump может уже не доставляться. |
| №14 `/screens/crashed` | В отдельной свежей вкладке открыть recovery.simulate.halt и подтвердить halt | Дождаться WEB_CRASHED и снять DOM, не подменять его offline. Серверная авария без exit effect может дать offline; проверять реально полученный kind. |
| №15 `/toolbar/wrap` | Запустить отдельный Web viewport, например 1000x800, и сопоставимый reference при той же измеренной ширине | Нужен настоящий false/true diff и `0 < frame.contentWidth < 1200` в обоих деревьях. При 1200 allowance не действует. CSS-ширина отдельного узла без изменения viewport не доказывает условие. Не расширять ±4 px. |

## Ограничения текущего стенда, которые нужно решить перед такими пробами

1. ScenarioCollector возвращает node + client как контекст нормализации.
   DumpNormalizer заменяет весь этот путь на `<node>`, поэтому нужный №8
   client suffix исчезает до DumpDiff. Unit показывает равенство при полном
   узле и неравенство при префиксе. Текущий отчёт не является ложным unused:
   в нём вообще нет lastSnapshot. Менять нормализацию всех существующих
   сценариев ради будущей пробы без нового reference нельзя. Предложение:
   отдельный контекст нормализации с префиксом для этой согласованной пробы.
2. Web test-driver.dump сначала запрашивает `/api/test/counters`. После
   настоящей остановки сервера этот путь недоступен; стандартный dump не
   снимает post-exit screen. Нужен offline DOM-collector в ui-parity,
   который читает виджеты и не выдаёт старые counters за новые. Его screen
   observation должен быть явно отделён от полного UiDump с телеметрией.
3. WebScenarioSession фиксирует viewport 1200x800; CdpClient закрывает набор
   CDP-команд и не допускает Network.emulateNetworkConditions или Emulation.
   Для narrow-пробы можно переиспользовать EdgeLauncher.startWithViewport
   с другой шириной, без добавления CDP-методов. Для offline - свой сервер
   и его процессы; общие серверы и desktop-слот не трогать.
4. UiTestDriver.screenshot требует 1200x800. Narrow-проба должна сравнивать
   дампы/DOM ширины 1000, а не менять общее требование визуальной матрицы.
5. ParityTest считает полный выбор 18x3 основанием для строгого unused-аудита.
   §9.4 и AllowedDiffs требуют реального срабатывания записи. Счётчик
   выбранных сценариев или существование pointer не является срабатыванием;
   одинаковые деревья тоже не подтверждают allowance. Допустимый partial
   audit уже явно реализован, но не доказывает S3/full PASS.

## Выполненная безопасная проверка

Добавлены ReportAudit и шесть unit-тестов только в ui-parity. Изолированный
javac/JUnit, без Maven/GUI/Robot/Node. Тесты проверяют повторные details,
корневые группы, отдельный сборочный сбой, HTML decode, строгую сводку,
стирание суффикса узла, запрет фиктивного использования записи на равных
деревьях и точный порог width для №15. Реальные дополнительные пробы не
запускались; used/allowances/golden/tolerances не менялись.
Итоговый запуск: 6/6 успешно, 0 ошибок, 0 пропусков, 114 мс;
JUnit-отчёт в `allowanceAuditRun/final-reports/TEST-junit-jupiter.xml`.

Для повторного извлечения нужен класс ReportAudit и замороженный core jar:

```powershell
java -cp "$allowanceAuditRun/classes;$allowanceAuditCore" ru.cashprediction.parity.audit.ReportAudit ui-parity/target/parity/report-full-20261002-1632.html ui-parity/docs/allowance-audit-20261002-1632.json
```

Параметры из фактического изолированного запуска:
`allowanceAuditRun=C:/Users/Oscar/AppData/Local/Temp/cashprediction-allowance-audit-547e4863-6097-4cdb-9bdd-fcbb606912cc`,
`allowanceAuditCore=C:/Users/Oscar/AppData/Local/Temp/cashprediction-hotkey-web-full-4ae0308d-9d2a-4c2d-8c64-e12913a54f1c/reactor/core/target/cashprediction-core-1.0.0.jar`.
