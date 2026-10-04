# Проверка горячих клавиш S3

## Полная текущая Web hotkey-матрица 02.10.2026, 16:20:43 +05:00

По разрешению выполнена вся `HotkeyCases.forClient("web")`, без выбора имён
и без residual-режима, на настоящей странице в headless Chrome через CDP.
Результат: 55 обнаружено, 55 запущено, 55 успешно, 0 FAIL, 0 ошибок,
0 пропусков; длительность JUnit 197,426 с. Состав: 45 разрешённых проб
(обычные и русские буквенные варианты), 10 отключённых, включая четыре
новые RU disabled-пробы. Резервируемые браузером Ctrl+N/O/T по-прежнему
исключены из Web-матрицы; их Alt+Shift варианты проверены. Это полный
состав текущего Web-стенда, а не все сочетания фокуса и состояния.
Desktop 90 этим прогоном не проверены, desktop-слот не использовался.

Корень артефактов:
`C:/Users/Oscar/AppData/Local/Temp/cashprediction-hotkey-web-full-4ae0308d-9d2a-4c2d-8c64-e12913a54f1c`.
JUnit: `reports/TEST-junit-jupiter.xml`. В `runtime/parity/hotkey-*/`
сохранены все 55 cps, launcher-журналы, 55 selftest.log и 110 реальных
before/after.json. Всего 346 OK, 55 DONE, 0 FAIL. Неуспешных путей нет.
Разрешённые пробы прошли exactlyOnce, отключённые - disabledHint с новым
видимым сообщением и неизменными counters/windows/alerts/popups/chooserRequests.

Исходные JAR версии 1.0.0 заморожены в `reactor/<модуль>/target/`
в 16:17:02 +05:00, SHA исходника и снимка проверен при копировании;
базовые копии помечены read-only. После прогона проверены все 112 jar:
две базовые копии и 110 отдельных копий клиентов. Получено ровно
56 совпадений каждого SHA; изменений не было.

| Jar | Время исходного файла UTC | SHA-256 |
| --- | --- | --- |
| cashprediction-core | 2026-10-02T11:10:59.8339472Z | 17730A8C058E82B1BB91B1CF336CEB0F1CA092DF962D28E036D7689A62500437 |
| cashprediction-web | 2026-10-02T11:14:55.3597365Z | 043465CA0311B0A7B42CA0F707F3B2DB08FC33E3C749BC5DCB4381276EDB0EA4 |

Компилировались только исходники hotkey в отдельную `tests/`; инфраструктура
взята из ранее замороженной папки dependencies. Ни общий target, ни Maven
не использовались для сборки. Точные команды из корня рабочего проекта:

```powershell
$hotkeyWebFullRoot = 'C:/Users/Oscar/AppData/Local/Temp/cashprediction-hotkey-web-full-4ae0308d-9d2a-4c2d-8c64-e12913a54f1c'
$hotkeyWebFullDependencies = 'C:/Users/Oscar/AppData/Local/Temp/cashprediction-hotkey-fidelity-166b865e-ff4c-4069-971b-a4138db17ff0/dependencies'
$hotkeyWebFullJunit = 'C:/Users/Oscar/.m2/repository/org/junit/platform/junit-platform-console-standalone/1.14.4/junit-platform-console-standalone-1.14.4.jar'
$hotkeyWebFullSources = @(Get-ChildItem ui-parity/src/test/java/ru/cashprediction/parity/check/hotkey -Filter '*.java' | ForEach-Object FullName)
javac -encoding UTF-8 --release 25 -Xlint:all,-serial -d "$hotkeyWebFullRoot/tests" -cp "$hotkeyWebFullRoot/reactor/core/target/cashprediction-core-1.0.0.jar;$hotkeyWebFullDependencies;$hotkeyWebFullJunit" $hotkeyWebFullSources
java -XX:-UsePerfData '-Dparity.realClients=true' '-Dparity.clients=web' '-Dparity.hotkey.residual=false' '-Dparity.hotkeys=' '-Dparity.browser=C:/Program Files/Google/Chrome/Application/chrome.exe' "-Dparity.reactor.root=$hotkeyWebFullRoot/reactor" '-Dparity.project.version=1.0.0' "-Dparity.build.directory=$hotkeyWebFullRoot/runtime" -jar $hotkeyWebFullJunit execute --disable-banner --details=tree -cp "$hotkeyWebFullRoot/tests;$hotkeyWebFullRoot/reactor/core/target/cashprediction-core-1.0.0.jar;$hotkeyWebFullDependencies" --select-class ru.cashprediction.parity.check.HotkeyParityTest --reports-dir "$hotkeyWebFullRoot/reports"
```

Повторять только в согласованном слоте с новыми buildDirectory/reports-dir,
чтобы не перезаписать доказательства. Все опыты закрыли свои деревья процессов,
удалили собственные selftest-узлы и прошли сравнение session до/после.
В 16:21:18 +05:00 найдено 0 Java/Chrome процессов с UUID этого прогона;
чужие процессы не завершались. Ошибок стенда или web не воспроизведено.
В этом продолжении изменён только README; golden, допуски и renderer-код
не менялись. Robot, Node, Maven, commit/push и новые агенты не использовались.

## Реальные четыре Web RU disabled-пробы 02.10.2026, 16:14 +05:00

По отдельному разрешению выполнены только новые `disabled edit.adjust Ctrl+J ru`,
`disabled edit.undo Ctrl+Z ru`, `disabled edit.redo Ctrl+Y ru` и
`disabled edit.redo Ctrl+Shift+Z ru`. Настоящая страница приложения работала
в Chrome `--headless=new` через CDP и штатный test.step. Robot, FX/Swing,
Maven, общая матрица и desktop-слот не использовались. Русские события
по-прежнему синтетические DOM KeyboardEvent, без переключения раскладки ОС.

Результат: 4 обнаружено, 4 запущено, 4 успешно, 0 FAIL, 0 ошибок,
0 пропусков; длительность JUnit 14,720 с. В каждом журнале ровно шесть
OK и один SELFTEST DONE (всего 24 OK и 4 DONE). Во всех восьми дампах
счётчики равны `{"file.sample":1}`; windows, alerts, popups и chooserRequests
пусты. После измеряемого события появился новый видимый message:
`status.hint.noRuleEvent`, `status.hint.nothingToUndo` либо
`status.hint.nothingToRedo` соответственно. Новые строгие assertions прошли.
Это доказательство только четырёх проб, а не PASS90 или полной матрицы.

Корень сохранённых артефактов:
`C:/Users/Oscar/AppData/Local/Temp/cashprediction-hotkey-web-ru-7214fc55-99c7-4a03-8d3b-7bb0406c4f49`.
JUnit: `reports/TEST-junit-jupiter.xml`; неуспешных путей нет.
Под `runtime/parity/` сохранены cps, launcher-журналы, selftest.log,
before/after.json и отдельные снимки обоих jar каждого опыта:

| Проба | Каталог |
| --- | --- |
| disabled edit.adjust Ctrl+J ru | hotkey-68dd3799-cce9-4334-b17b-9219acc3663b |
| disabled edit.undo Ctrl+Z ru | hotkey-3d72b885-732e-432c-aac5-21008c2a6d77 |
| disabled edit.redo Ctrl+Y ru | hotkey-0907dc57-7230-4f8d-b0d6-86a4b3918bd4 |
| disabled edit.redo Ctrl+Shift+Z ru | hotkey-1412fca0-7e7d-4ac2-833a-16332dbbf8d8 |

Исходные jar скопированы в `reactor/<модуль>/target/` и помечены read-only
в 16:13:25 +05:00. SHA исходника и копии сравнивался при копировании;
после прогона SHA замороженных jar и всех восьми клиентских копий совпал:

| Jar версии 1.0.0 | Время исходного файла UTC | SHA-256 |
| --- | --- | --- |
| cashprediction-core | 2026-10-02T11:10:59.8339472Z | 17730A8C058E82B1BB91B1CF336CEB0F1CA092DF962D28E036D7689A62500437 |
| cashprediction-web | 2026-10-02T11:12:01.3617557Z | 57AAAD7B288E9244B43A691B4495774B56805C53931D9D4756577E0B12D98EBA |

Компилировались только исходники этого hotkey-каталога в отдельную `tests/`.
Остальная инфраструктура взята из неизменяемой копии прежнего unit-прогона;
ядро для компиляции и работы assertions взято из указанного frozen jar.
Точные команды компиляции и запуска (из корня рабочего проекта):

```powershell
$hotkeyRuRoot = 'C:/Users/Oscar/AppData/Local/Temp/cashprediction-hotkey-web-ru-7214fc55-99c7-4a03-8d3b-7bb0406c4f49'
$hotkeyRuDependencies = 'C:/Users/Oscar/AppData/Local/Temp/cashprediction-hotkey-fidelity-166b865e-ff4c-4069-971b-a4138db17ff0/dependencies'
$hotkeyRuJunit = 'C:/Users/Oscar/.m2/repository/org/junit/platform/junit-platform-console-standalone/1.14.4/junit-platform-console-standalone-1.14.4.jar'
$hotkeyRuSources = @(Get-ChildItem ui-parity/src/test/java/ru/cashprediction/parity/check/hotkey -Filter '*.java' | ForEach-Object FullName)
javac -encoding UTF-8 --release 25 -Xlint:all,-serial -d "$hotkeyRuRoot/tests" -cp "$hotkeyRuRoot/reactor/core/target/cashprediction-core-1.0.0.jar;$hotkeyRuDependencies;$hotkeyRuJunit" $hotkeyRuSources
java -XX:-UsePerfData '-Dparity.realClients=true' '-Dparity.clients=web' '-Dparity.hotkey.residual=false' '-Dparity.hotkeys=disabled edit.undo Ctrl+Z ru,disabled edit.redo Ctrl+Y ru,disabled edit.redo Ctrl+Shift+Z ru,disabled edit.adjust Ctrl+J ru' '-Dparity.browser=C:/Program Files/Google/Chrome/Application/chrome.exe' "-Dparity.reactor.root=$hotkeyRuRoot/reactor" '-Dparity.project.version=1.0.0' "-Dparity.build.directory=$hotkeyRuRoot/runtime" -jar $hotkeyRuJunit execute --disable-banner --details=tree -cp "$hotkeyRuRoot/tests;$hotkeyRuRoot/reactor/core/target/cashprediction-core-1.0.0.jar;$hotkeyRuDependencies" --select-class ru.cashprediction.parity.check.HotkeyParityTest --reports-dir "$hotkeyRuRoot/reports"
```

Команда выше воспроизводит исходный прогон; повторять её следует только
в согласованном слоте с новым buildDirectory/reports-dir для сохранения
первоначального отчёта. Тест удалил собственные selftest-узлы и подтвердил
неизменность session. После прогона нет Chrome/Java процессов с путём
этого UUID. Ошибок стенда или web для исправления не обнаружено;
в этом продолжении изменён только README. Остальные runtime-ограничения
фокуса, enabled у виджетов, лишних модификаторов и физического ввода сохраняются.

## Узкая проверка достоверности 02.10.2026 без запуска клиентов

Изменены только `HotkeyAssertions.java`, `HotkeyCases.java`, этот README;
добавлен `HotkeyFidelityTest.java`. Существующие незакоммиченные файлы
других владельцев сохранены. Robot, реальные окна, браузеры и Maven
в этой проверке не запускались; геометрия диалогов не менялась.

Новые шесть unit-тестов проверяют:

- `rejectsDisappearingZeroCounters`: исчезновение нулевого счётчика теперь
  отклоняется при выполненной и отключённой команде; разрешено появление
  нового целевого счётчика с единицей при сохранённой истории.
- `disabledHintRejectsPopupChanges`: правильная подсказка и неизменные
  счётчики не скрывают открытие или закрытие всплывающего окна.
- `disabledHintRejectsChooserChanges`: то же для запросов выбора файла.
- `russianEventEncodesEveryModifierCombination`: все восемь комбинаций
  Ctrl/Shift/Alt, независимые KeyS и ы/Ы, текущий document.activeElement
  и отказ при отсутствии цели. Выражение здесь не исполняется в браузере.
- `disabledMatrixCoversEveryBindingAndLayout`: все шесть отключённых
  привязок, точные подсказки и отдельный русский вариант каждой буквенной
  привязки во всех клиентах. В web добавлены четыре отсутствовавшие пробы.
- `focusPreparationPrecedesMeasuredKeyForEveryVariant`: подготовка фильтра,
  строки таблицы, undo/redo и группы прошедших завершается до before;
  измеряемое сочетание сохранено в разобранной команде сценария.

До исправлений четыре новые проверки упали на конкретных пробелах;
проверки модификаторов и подготовки фокуса прошли. После исправлений
`HotkeyFidelityTest` (6) и `HotkeyRegressionTest` (10) прошли 16/16,
без пропусков. Все исходники только этого каталога компилировались
JDK 25.0.2 с `--release 25 -Xlint:all,-serial` в отдельную UUID-папку.
Зависимости core и ранее собранного стенда скопированы туда из targets;
общие targets не использовались для записи. Отчёты сохранены:

`C:/Users/Oscar/AppData/Local/Temp/cashprediction-hotkey-fidelity-166b865e-ff4c-4069-971b-a4138db17ff0/red-reports`

`C:/Users/Oscar/AppData/Local/Temp/cashprediction-hotkey-fidelity-166b865e-ff4c-4069-971b-a4138db17ff0/green-reports`

Точная команда повторения unit-проверки на уже сохранённых классах:

```powershell
$hotkeyTaskOutput = 'C:/Users/Oscar/AppData/Local/Temp/cashprediction-hotkey-fidelity-166b865e-ff4c-4069-971b-a4138db17ff0'
$hotkeyTaskJunit = 'C:/Users/Oscar/.m2/repository/org/junit/platform/junit-platform-console-standalone/1.14.4/junit-platform-console-standalone-1.14.4.jar'
java -XX:-UsePerfData '-Dparity.realClients=false' -jar $hotkeyTaskJunit execute --disable-banner --details=summary -cp "$hotkeyTaskOutput/tests;$hotkeyTaskOutput/core;$hotkeyTaskOutput/dependencies" --select-class ru.cashprediction.parity.check.hotkey.HotkeyFidelityTest --select-class ru.cashprediction.parity.check.hotkey.HotkeyRegressionTest
```

Оставшиеся реальные проверки требуют согласованного слота и актуальных
замороженных jar. Новые web-имена для отдельного ограниченного выбора:
`disabled edit.undo Ctrl+Z ru`, `disabled edit.redo Ctrl+Y ru`,
`disabled edit.redo Ctrl+Shift+Z ru`, `disabled edit.adjust Ctrl+J ru`.
Старый `parity.hotkey.residual` намеренно сохраняет прежний состав и
их не включает; выбирать новые имена нужно без этого флага. Более строгий
контроль popups/chooserRequests также ещё не проверен на реальных клиентах.

Unit-проверка подготовки не доказывает фактический фокус. UiDump не содержит
владельца фокуса; NativeKeyDriver при отсутствии владельца использует root
FX или таблицу Swing. Проверка разрешённой команды по счётчику не доказывает
enabled у виджета или её видимый результат. Запреты в других областях,
лишние модификаторы, Alt с другой клавишей, реальные press/release и
физический ввод Windows остаются отдельными runtime-проверками. Эти unit-тесты
не закрывают общую матрицу сценариев и не подтверждают готовность рендереров.

## Ограниченное продолжение 02.10.2026 после сборки ядра 12:10:58

`-Dparity.hotkey.residual=true` выбирает только web `past.toggle Space`,
настольные буквенные события с суффиксами ` en` и ` ru`, а также одиночный Alt.
Обычные зелёные 39 опытов каждого настольного клиента и перепись 23 классов
не запускаются повторно. Для служебных клавиш символ раскладки отсутствует;
к ним относится прежнее доказательство физического кода, а буквенные сочетания
получают отдельные реальные события с латинским и русским символом.

Подготовка web использует `rowclick past@group date`: у объединённой строки
прошедших существует ячейка `date`, а скрытая объединением `title` отсутствует.
Щелчок и последующий Space проходят штатные обработчики настоящей страницы.
Приращение измеряется между дампами после подготовки, поэтому подготовительный
щелчок не засчитывается как исполнение измеряемой клавиши.

`NativeKeyDriver` декорирует только измеряемое событие существующего драйвера.
Запуск, копии клиентских jar, подготовка, SelfTestRunner, дампы, ожидания и
завершение дерева процессов остаются существующими операциями стенда.
Декоратор запускает настоящие порты FX/Swing и вызывает фильтр сцены либо
штатный KeyEventDispatcher; напрямую UiIntents.key и счётчики он не вызывает.
Дополнительный тестовый модуль открывает только пакет клиента для получения
ссылок на живую сцену и диспетчер. Это события инструмента с символами раскладок,
а не переключение раскладки Windows или физический Robot-ввод.

Для Alt сохраняются дополнительные реальные дампы `alt-pressed`, `alt-released`
и `alt-repeat-release`. Требуются неизменные счётчики после нажатия, ровно одно
приращение ui.menuBar после отпускания и отсутствие нового приращения после
повторного отпускания. Метаданные FX задаются разобранным именем сценария до
создания клиента; фактические дампы и счётчики после снятия не переписываются.

Все пробы этого продолжения используют неизменяемую копию core jar от 12:10:58
и отдельную раскладку модулей:
`C:/Users/Oscar/AppData/Local/Temp/cashprediction-hotkey-residual-2018c8ff-1995-4a90-9e65-58c740ba7019`.
`web-reports` содержит успешный реальный повтор Space в Chrome;
`alt-final-reports` содержит успешные раздельные фазы Alt у FX и Swing;
`unit-final-reports` содержит 10 успешных регрессионных проверок.
Результат полной остаточной настольной выборки записывается отдельно в
`desktop-residual-final-reports`; наличие папки само по себе не является успехом.

Остаточная выборка завершена: 90 опытов, из них 83 успешны в первом прогоне.
Семь ошибок относились только к новому декоратору Swing: при запаздывающем
фокусе он не использовал таблицу, хотя штатный SwingUiDriver это делает.
Декоратор исправлен тем же способом; единственный повтор только этих семи
имён прошёл 7/7, без пропусков. Его исходный отчёт находится в
`swing-failed-only-retry-reports`. Первый отчёт с семью FAIL сохранён без
переписывания, зелёные имена не запускались заново. Итого остаточные 45 опытов
FX и 45 Swing закрыты сочетанием двух отчётов, а не одним зелёным полным прогоном.
Это включает по 44 буквенных опыта (латинские/русские символы и отключённые
команды) и Alt; служебные небуквенные клавиши сохраняют прежнее доказательство.

После завершения всех прогонов проверка процессов дала 0 остаточных JVM;
desktop-слот передан агенту визуальных проверок сообщением в его чат.
Изменены только HotkeyParityTest.java и файлы этого каталога: HotkeyCases.java,
HotkeyRun.java, HotkeyRegressionTest.java, RussianKeyInput.java, новый
NativeKeyDriver.java и README.md. Census, клиентские исходники, core,
общий Maven, launcher и pipeline не изменялись. Доказательство общей
готовности S3 требует отдельного зелёного прогона всех 18 реальных сценариев.

`HotkeyParityTest` включается только при `-Dparity.realClients=true` и
выбирает клиентов через существующее `-Dparity.clients=fx,swing,web`.
Без этого флага реальная фабрика пропускается; регрессионные тесты
`HotkeyRegressionTest` выполняются всегда. Пропуск не означает проверку клиента.

## Что измеряется

Для каждой принимаемой клиентом привязки из `HotkeyTable` создаётся отдельный
внешний `hotkey.cps`. Парсер ядра действительно принимает путь к `.cps`, а
`LaunchRequest.selftest` передаёт его через существующий `--selftest`.
Подготовка использует `sample`, `select`, `menu`, `filtertype` и обычные
`key` через драйвер виджетов. После подготовки идут только:

```text
dump before
key Ctrl+S
dump after
```

Пример показывает принцип; сочетание в каждом опыте своё. Каждый запуск
получает свежую папку CashMemory, UUID узла `ru/cashprediction/selftest/...`,
фиксированную дату 2026-09-13; интерфейс ядра используется по умолчанию. `ScenarioFixtures`,
`ClientLauncher`, `WebScenarioSession`, `CdpTestApi`, `UiTestDriver` и
`TestApiBridge` переиспользуются без изменений. Процесс и потомки закрываются,
тестовый узел удаляется, реальные session/fx и session/swing сравниваются
с исходным состоянием даже после ошибки.

Из JSON реального клиента читаются счётчики. Разница целевой команды должна
быть ровно +1, всех остальных команд - 0. Проверяется объединение ключей,
поэтому новая посторонняя команда, исчезнувший счётчик и сброс истории
не останутся незамеченными. Вычитание выполняется в long. Непустые счётчики
после `sample` обязательны: пустая телеметрия web не считается успехом.
Полный журнал должен содержать ровно один `OK` на каждую строку сценария
и один `SELFTEST DONE`. `FAIL`, пропуск, повтор, перестановка и неправильный
текст шага отклоняются до сравнения дампов.

Для отключённых undo, redo (обе привязки), adjust, edit и delete используется
итог месяца. Изменения любых счётчиков запрещены. После нажатия в видимом
сегменте `message` требуется точный текст `status.hint.*` из `UiText`;
до нажатия этого текста быть не должно. Открытие/изменение формы или
предупреждения также запрещено. Итог выбран вместо группы прошедших,
потому что Enter на группе законно перенаправляется в `past.toggle`.

## Области и физические клавиши

Каждая привязка проверяется один раз в подходящей области: обычно TABLE,
для очистки и Enter фильтра - FILTER; Space - на группе прошедших.
Это проверка всех привязок, а не всех комбинаций областей и состояний.
Enter/Esc внутри форм, навигация таблицы и Alt+F4 не входят в HotkeyTable
и не создают ожидаемого приращения команды. Их проверяет отдельный сценарный
паритет. Здесь нельзя подменять их проверкой произвольного счётчика.

Для web Ctrl+N/O/T исключены как резервируемые браузером. Соответствующие
Alt+Shift+N/O/T проверяются, как и Alt+Shift+C/1/2. Остальные настольные
сочетания и все альтернативы проверяются из таблицы, включая одиночный Alt.

Дополнительный опыт для каждой буквенной web-привязки отправляет настоящий
DOM `KeyboardEvent`: `code=KeyS`, `key=ы` (или `Ы` при Shift), с исходными
модификаторами. Событие проходит capture-диспетчер и HTTP-намерение страницы;
ядро напрямую не вызывается. Вторая CDP-связь подключается к той же вкладке
через DevToolsActivePort профиля уже запущенного `WebScenarioSession`.
Только она забирает test.step в этом опыте; мост обычного сборщика не качается.
Дамп и ожидание очередей остаются операциями штатного CdpTestApi.

Это доказывает обработку русского `key` при физическом `code`, но не
переключает раскладку Windows и не делает событие `isTrusted=true`.
Драйверы FX/Swing принимают только KeyChord: у FX текст события пуст,
у Swing CHAR_UNDEFINED. Язык `.cps` не позволяет передать отдельный русский
символ или выбрать раскладку ОС. Поэтому физическая русская раскладка
настольных клиентов остаётся явно непроверенной до расширения их драйверов
другими владельцами. Модельный вывод или добавленные тестом счётчики
не могут закрыть этот пробел.

## Запуск и воспроизведение

После сборки настоящих jar основной владелец может выполнить:

```powershell
mvn -o -B -Pui-tests -pl ui-parity -am test '-Dtest=HotkeyParityTest,HotkeyRegressionTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dparity.realClients=true' '-Dparity.clients=fx,swing,web'
```

Этот запуск требует уже собранных jar модулей: стандартный launcher читает
`<module>/target/cashprediction-<module>-<version>.jar`. Фаза test сама
не обновляет эти jar. При проверке незакоммиченных изменений сначала
нужно собрать актуальные package в выделенной владельцем папке.

Для ограниченного диагностического повтора можно добавить, например,
`'-Dparity.hotkeys=file.save Ctrl+S,disabled edit.adjust Ctrl+J'`.
Имена совпадают с именами динамических тестов после префикса клиента.
Пустое свойство означает полную матрицу. Неизвестные, пустые и повторные
имена отклоняются. Частичный запуск не подтверждает всю матрицу.

Каждый опыт сохраняется в `parity.build.directory/parity/hotkey-<uuid>`.
Там находятся `hotkey.cps`, launcher-журналы, `out/selftest.log`,
`out/hotkey/before.json` и `after.json`. Ошибка содержит сочетание,
точные аргументы `--selftest`, `--home`, `--registry-node`, `--today`,
`--selftest-out` и путь результатов. Для web требуется подключённый
сборщик Edge/test.step; одного ручного запуска сервера недостаточно.
Для русского варианта требуется тот же декоратор DOM-событий HotkeyRun.

Сторонние владельцы не должны запускать общую Maven-сборку параллельно.
Для изолированной проверки достаточно замороженных CoreClasses,
JDK 25 и локального JUnit Console 1.14.4. Все Java-исходники ui-parity
компилируются в отдельный UUID-каталог системной временной папки,
затем запускаются HotkeyRegressionTest и HotkeyParityTest без realClients.
Для реальных опытов можно собрать core и клиентов javac/jar в отдельную
раскладку реактора во временной папке и передать свойства
`parity.reactor.root`, `parity.project.version`, `parity.javafx.version`,
`parity.maven.repository`, `parity.build.directory`. Общие targets
и локальный Maven-репозиторий при этом не изменяются.

## Регрессии самого проверяющего кода

Ограниченные мутации включают +2 вместо +1, выполнение чужой команды,
отсутствие выполнения, выполнение целевой и посторонней команд одновременно,
новый посторонний счётчик, исчезновение старого, отрицательное значение
и крайнее int. Для подсказок проверяются невидимость, чужой сегмент,
неправильный текст, подсказка уже до события и выполнение отключённой команды.
Отдельно проверяются полнота матрицы и принимаемый парсером порядок дампов.
Искусственные наблюдения существуют только в этих unit-тестах и никогда
не передаются настоящей проверке клиента.

## Наблюдения из изолированной проверки 02.10.2026

Все исходники ui-parity скомпилированы JDK 25 без Maven в системной временной
папке. Семь HotkeyRegressionTest проходят. Реальные jar core, FX, Swing
и web также собраны javac/jar только во временной раскладке. Общие targets
не использовались для записи.

Короткие проверки FX и Swing подтвердили 26 разных опытов: сохранение,
график, очистка и Enter фильтра, Alt+Shift+N, undo, обе redo-привязки,
Space группы прошедших, Shift+F10, F10 и отключённые adjust/edit.
Подготовка undo после пропуска переводит фокус на итог месяца: исходное
событие уже скрыто. Регрессия матрицы фиксирует этот порядок.

Полная матрица на этой изолированной сборке затем завершилась за 225 секунд:
78 опытов, 77 успешных. FX прошёл 39/39; Swing прошёл 38/39.
Единственный сбой - `swing: ui.menuBar Alt`: журнал всех шести строк OK/DONE,
но оба дампа имеют только `file.sample=1`, приращение `ui.menuBar=0`.
Путь события неполон: `SwingKeyBridge.dispatchKeyEvent` выполняет одиночный
Alt на KEY_RELEASED, а `SwingUiDriver.key` создаёт только KEY_PRESSED.
Исправление принадлежит владельцу Swing; тест не заменяет Alt на F10
и не исключает привязку из матрицы. Минимальный повтор после сборки jar:

```powershell
mvn -o -B -Pui-tests -pl ui-parity -am test '-Dtest=HotkeyParityTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dparity.realClients=true' '-Dparity.clients=swing' '-Dparity.hotkeys=ui.menuBar Alt'
```

В проверенной сборке web реальные before/after содержат `counters: {}`.
Повторы `file.save Ctrl+S`, `file.save Ctrl+S ru` и
`disabled edit.adjust Ctrl+J` имеют полный журнал OK/DONE, но строго падают
с `Missing controller counters after sample`. В `web/app/dump.js` фактически
возвращается пустая карта. Исправление передачи счётчиков принадлежит
владельцам web/core; стенд не дописывает эти числа в фактический результат.
Точное воспроизведение через обычную раскладку запуска:

```powershell
mvn -o -B -Pui-tests -pl ui-parity -am test '-Dtest=HotkeyParityTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dparity.realClients=true' '-Dparity.clients=web' '-Dparity.hotkeys=file.save Ctrl+S,file.save Ctrl+S ru,disabled edit.adjust Ctrl+J'
```

Также первый web-опыт Alt+Shift+N после холодного запуска Edge достиг
handshake PARITY_URL, но не создал selftest.log и за 45 секунд не завершил
обмен test.step. Стенд сохранил команду и журналы и завершил дерево процессов.
Причина этого сбоя в пределах выделенных файлов не установлена; успешным
нажатием он не объявляется. До передачи счётчиков и устранения этого сбоя
web HotkeyParityTest не является зелёным.

Сохранённая раскладка этого прогона:
`C:/Users/Oscar/AppData/Local/Temp/cashprediction-hotkey-real-c5502d2f-ff85-4ff8-b3cf-7b192a429328`.
`junit-desktop-full/TEST-junit-jupiter.xml` содержит полную матрицу,
`desktop-full/parity/hotkey-57e8a14c-1c0f-4c2a-934c-3a71e1e6baa9`
содержит точный Swing Alt сценарий, команду, журналы и оба фактических дампа.
`junit-web-confirm/TEST-junit-jupiter.xml` и `web-confirm/parity/` содержат
четыре окончательных web-повтора. Все деревья процессов завершены;
тестовые узлы удалены также после неуспешных опытов.

## Возобновлённая проверка 02.10.2026

HotkeyRun теперь копирует все jar module path перед каждым запуском,
поэтому JVM не держит jar общего target открытым при упаковке владельцами.
Исходный JSON проверяется до восстановления UiDump: counters должны быть
объектом с числовыми целыми неотрицательными int, происхождение и шаг
должны совпадать. Строка "1", дробь, отрицательное число, переполнение,
null и дамп модели отвергаются ограниченными регрессионными тестами.

Ограниченная сборка только ui-parity, без -am:

```powershell
mvn -o -B -Pui-tests -pl ui-parity test '-Dtest=HotkeyRegressionTest,FxCensusTest,DirectoryChooserProbeTest,ClientJarSnapshotTest,ClassUsageTest'
```

Результат: 97 успешных проверок и два явно выключенных реальных теста.
Отдельный изолированный javac/JUnit запуск новых проверок: 90/90.

Полная реальная настольная матрица прошла: FX 39/39 и Swing 39/39,
в том числе одиночный Alt (после добавления KEY_RELEASED владельцем Swing).
Длительность матрицы 328,8 с. Копия исходного JUnit отчёта:
`C:/Users/Oscar/AppData/Local/Temp/cashprediction-census-hotkey-check-dc97f5c2-5efa-44a5-8ed6-2adf2a321a4f/desktop-junit/TEST-ru.cashprediction.parity.check.HotkeyParityTest.xml`.

Три web-повтора в Edge завершились до DOM-проверки: браузер вышел с кодом 0
до записи DevToolsActivePort; browser.log пуст. Это реальное падение запуска,
а не пропуск. Те же file.save Ctrl+S, file.save Ctrl+S ru и отключённый
edit.adjust Ctrl+J прошли в установленном Chrome. В обновлённом web jar
телеметрия counters уже работает; прежний дефект пустой карты не повторился.
Для воспроизведения через Chrome можно добавить
`'-Dparity.browser=C:/Program Files/Google/Chrome/Application/chrome.exe'`.

Полная матрица в Chrome: 50/51, 206 с, без пропусков. Единственное падение
`web: past.toggle Space` происходит в подготовке `select past@group`:
test-driver.js ищет дочерний элемент title, а render-table.js объединяет
первые колонки PAST_HEADER в ячейку с id первой колонки. Поэтому title
в DOM отсутствует. Строгая проверка журнала отвергает этот FAIL, хотя
SELFTEST DONE и оба дампа существуют. Исправление принадлежит владельцу web;
сценарий и ожидаемая команда не подменяются.

Точный ограниченный повтор на актуальном jar:

```powershell
mvn -o -B -Pui-tests -pl ui-parity test '-Dtest=HotkeyParityTest' '-Dparity.realClients=true' '-Dparity.clients=web' '-Dparity.browser=C:/Program Files/Google/Chrome/Application/chrome.exe' '-Dparity.hotkeys=past.toggle Space'
```

Полный JUnit отчёт:
`C:/Users/Oscar/AppData/Local/Temp/cashprediction-census-hotkey-check-dc97f5c2-5efa-44a5-8ed6-2adf2a321a4f/web-chrome-junit/TEST-junit-jupiter.xml`.
Неуспешный сценарий, журналы, оба реальных дампа и снимки jar:
`C:/Users/Oscar/AppData/Local/Temp/cashprediction-census-hotkey-check-dc97f5c2-5efa-44a5-8ed6-2adf2a321a4f/web-chrome-full/parity/hotkey-13b434b9-0a6f-4255-9b5b-d3cf53c22eec`.
Все запуски завершили дерево процессов и удалили только свои тестовые узлы;
проверка неизменности настоящих узлов сеанса не дала ошибок.
