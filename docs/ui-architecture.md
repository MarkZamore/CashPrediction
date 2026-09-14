# Архитектура единого интерфейса CashPrediction

Краткая выжимка утверждённого дизайна для тех, кто пишет код. Полные документы: `docs/design/architecture.md` (решение, контракты, проверка одинаковости), `docs/ui-spec.md` (спецификация интерфейса v2 - единственный источник текстов, раскладок и потоков), `docs/design/stages.md` (этапы), `docs/ui-protocol.md` (JSON web-протокола).

## Суть

Три клиента - JavaFX (`ui-fx`), Swing (`ui-swing`) и Web (`web`) - только способы запуска одного приложения. Всё поведение, все тексты и все модели интерфейса живут в `core`: `AppController` строит модели, клиент их отрисовывает и передаёт действия пользователя обратно в ядро.

```
клиент (FX / Swing / web)                       ядро (core)
  виджеты ── действия ──> UiIntents ──> AppController ──> потоки (core.app.flow) ──> PlanDocument, SessionRecorder
  виджеты <── модели ──── UiPort <────────────┘            модели: MainScreenModel, FormSpec/FormView, AlertSpec
```

## Правила

- **R1. В клиентах только код инструмента.** Нет кириллических строковых литералов в `ui-fx`, `ui-swing`, `web` (Java и JS); исключение - комментарии и один `BootstrapFallbackText` на клиент. Проверяет `NoCyrillicLiteralsTest` каждого модуля.
- **R2. Клиенты ничего не решают.** Доступность, отметки, видимость, цвет, порядок и тексты приходят из моделей ядра.
- **R3. Форматы восстановления после сбоя совместимы байт в байт.** Имена `WindowType`, `contextKeys`, `fieldIds`, назначения, хранилища и кодеки не меняют смысла. Изменения схемы только добавляющие: `MainWindowState.whatIfExtra` и ключи `filters` `pastExpanded`, `whatIfIncome`, `whatIfExpense`. Старые снимки читаются.
- **R4. Сборка зелёная на каждом этапе.** Прежний интерфейс остаётся по умолчанию, пока клиент не прошёл паритет (`--ui core|legacy`, свойство `cashprediction.ui`); прежний код удаляется на этапе S4.
- **R5. Ядро не зависит от `javafx.*` и `java.desktop`.**
- **R6. Русский Javadoc и комментарии везде.** У каждого создания 23 классов JavaFX и их аналогов Swing и web - комментарий `// JavaFX: X → Swing: Y → Web: Z`.
- **R7. Без внешних библиотек.** Только JDK, OpenJFX и JUnit. Портативная сборка создаёт только CashMemory.

## Решения журнала требований, действующие в коде

- **L1. `Money.parse` строг к группам разрядов.** Если разряды разделены (пробелы, U+00A0, U+202F, апостроф, повторённые запятая или точка, неосновной из пары «запятая + точка»), первая группа - 1-3 цифры, остальные - ровно 3; иначе ошибка «Некорректная сумма: …».
- **L2. Свой узел реестра у каждой установки.** Узел по умолчанию `ru/cashprediction/session/<клиент>-<8 hex>`, где 8 hex - начало SHA-256 от нормализованного абсолютного пути CashMemory в нижнем регистре; рядом значение `cashmemory.path`. Явный префикс (`--registry-node`, `cashprediction.registry.node`) обязан начинаться с `ru/cashprediction/` и не может лежать в ветке `ru/cashprediction/session` (там общий узел прежних клиентов и узлы установок, L12), узел = `<префикс>/<клиент>`; пустой префикс равен отсутствующему. `--registry memory` - хранилище в памяти процесса. API: `RegistrySessionStore.forClient(client, cashMemoryDir[, prefix])`, `inMemory(client, dir)`; прежний `forClient(client)` сохранён (устаревший) для прежних клиентов до S4 и учитывает свойство узла.
- **L3. Чистый выход после «Не сохранять».** Последний снимок `shutdownClean` не содержит отброшенного плана (`PlanState.CLEAN`) и окон.
- **L12. Безопасность реестра.** Автоматические запуски используют изолированный `--home` и узел `ru/cashprediction/selftest/<uuid>` (или `--registry memory`), удаляют его после себя и не трогают настоящие узлы установок.
- **L13. Все тексты - в одном каталоге.** `core/src/main/resources/ru/cashprediction/core/ui/text/<область>_ru.properties` (UTF-8), поиск `<область>_<язык>` с запасным `<область>`, язык зафиксирован `ru`, английских файлов и переключения языка нет. Длинная справка «Формат файла .md» лежит там же документом `help-format_ru.md` и читается `Texts.document` по тем же правилам (`MarkdownFormat.userGuide()`). Пределы проверок (`PlanValidator.MAX_ROWS`, `Horizon.MAX_MONTHS`, `Recurrence.MAX_*`, `OccurrenceGenerator.MAX_DATES_PER_RULE`) подставляются в тексты из констант, а не повторяются числами. Поиск текста - `core.text.Texts` (без зависимостей, доступен любому слою ядра) и `core.ui.text.UiText` (обёртка для моделей интерфейса). После этапа S0.5 в основном коде ядра нет кириллицы вне комментариев (`NoCyrillicLiteralsTest`): сообщения для пользователя, диагностика, предупреждения прогноза, заголовок и отметки CSV, названия месяцев и дней недели, заголовки перечислений и окон берутся из областей `model`, `dates`, `markdown`, `diagnostics`, `document`, `export`, `forecast`, `io`, `json`, `session`; сообщения только для разработчика пишутся латиницей. Ключевые слова формата файлов плана, `settings.md` и `web-session.md` - грамматика данных: они лежат в нелокализуемом ресурсе `core/src/main/resources/ru/cashprediction/core/format/format.properties` (никогда без суффикса языка) и читаются классом `core.format.FormatWords`; записанные файлы не изменились байт в байт. Публичные методы, которыми пользуются прежние клиенты (`Kind.title()`, `WindowType.title()`, `Horizon.label()`, `Recurrence.toRussian()`, `RuText`, `DateFormats.monthTitle`), сохранены и возвращают те же тексты из ресурсов. Строгий режим `-Dcashprediction.ui.strictText=true` (включён в тестах ядра): отсутствующий ключ - исключение, у пользователя - `!ключ!`.

### Список переноса S0.5, пополненный на этапе S0 (выполнен)

Кириллические литералы, добавленные или изменённые в S0 вне проверявшихся тогда пакетов; этап S0.5 перенёс их вместе с остальными:

- `WindowType.title()` - заголовки окон, в том числе новый «Корректировка события» у `ADJUSTMENT_EDITOR` (спецификация v2, §6.5). Это текст интерфейса: переезжает в ключи `window.title.<тип>` файла `*_ru.properties`, метод `title()` сохраняется и возвращает тот же текст (им пользуются прежние клиенты). Смена заголовка видна в прежних FX (`FxStatefulDialog`) и web (`StateJson`, `WebWindow`) - допустимое изменение прежнего интерфейса этапа S0: web `editors.js` уже показывал этот текст, а в снимки заголовки не пишутся (`SnapshotSchemaAdditionsTest.windowTitlesAreNotPersisted`).
- `MarkdownSnapshotCodec.KEY_WHAT_IF_EXTRA` = «Что-если, доп. экономия в месяц» - грамматика файла `web-session.md`, не текст интерфейса: переезжает в нелокализуемый ресурс формата байт в байт, вместе с запятой и точкой. Строку фиксирует `SnapshotSchemaAdditionsTest.markdownWhatIfExtraKeywordIsFrozen`.
- Прежние сообщения `Money.parse` («Сумма не указана», «Некорректная сумма: …», «Слишком большая сумма: …»): новое сообщение группировки уже в каталоге (`money.error.grouping`).

## Пакеты ядра

| Пакет | Что внутри |
|---|---|
| `core.text` | `TextCatalog`, `Texts` - общий каталог текстов всех слоёв |
| `core.ui.text` | `UiText`, `UiFormats` (целые и сокращённые суммы, горизонт, даты), `Plurals` |
| `core.ui.token` | `ColorToken`, `FontToken`, `DesignTokens` (размеры, задержки, `GLYPHS`), `DialogWidth`, `TokenCss` (web CSS, FX looked-up colors, значения `UIManager`) |
| `core.ui.command` | `CommandId` (все команды §3-§5, §7), `CommandArgs`, `InvokeSource`, `CommandAvailability`, `Availability`, `KeyChord`, `FocusScope`, `HotkeyBinding`, `HotkeyTable` |
| `core.ui.menu` | `MenuNode` (sealed), `MenuBarModel`, `ToolbarNode` (sealed), `ToolbarModel`, `Emphasis`, `ContextTarget` (sealed), `MenuModels` |
| `core.ui.view` | `MainScreenModel`, `ScreenPart` |
| `core.ui.view.summary` | `SummaryModel`, `CardModel`, `SummaryBuilder` |
| `core.ui.view.table` | `TableModel` (ленивая), `LazyTableModel`, `ColumnSpec`, `TableRowView`, `RowKind`, `RowStyle`, `CellStyle`, `Placeholder` |
| `core.ui.view.chart` | `ChartModel`, `ChartScene`, `ChartPrimitive` (sealed), `ChartPoint`, `Stroke`, `TextAnchor`, `LegendItem`, `HitRegion`, `PlotTransform`, `ChartHover`, `ChartScale`, `ChartLayout` |
| `core.ui.view.status` | `StatusModel`, `StatusSegment`, `StatusLevel`, `StatusBuilder` |
| `core.ui.view.popup` | `DayCardModel`, `SparklineModel`, `CalendarModel`, `PopupBuilders` |
| `core.ui.form` | `FormSpec`, `FormPage`, `FormRow` (sealed), `FieldSpec`, `FieldKind`, `Option`, `Orientation`, `ButtonSpec`, `ButtonRole`, `Presentation`, `FormView`, `FieldView`, `ButtonView`, `Problem`, `PreviewItem`, `ResultLine`, `FormState`, `FormContext`, `FormLogic`, `FormOutcome` (sealed), `FormSession`, `FieldCodec`, `FieldChecks` |
| `core.ui.alert` | `AlertSpec`, `AlertKind`, `AlertButton`, `AlertSession`, `AlertCatalog` |
| `core.ui.forms.simple` | `TextInputForms`, `ChoiceForms`, `OpenPlanForm`, `ConfirmForms`, `CsvExportForm`, `FileBrowserForm` |
| `core.ui.forms.plan` | `NewPlanWizardForm`, `PlanSettingsForm`, `HorizonFields`, `GoalCalculatorForm` |
| `core.ui.forms.ops` | `RuleEditorForm`, `OneTimeForm`, `AdjustmentForm`, `QuickEditForm` |
| `core.app` | `AppController`, `AppState`, `DocumentView`, `RecorderStatus`, `OpenWindows`, `StatusMessages`, `UiPort`, `UiIntents`, `WindowHandle`, `Placement`, `Activation`, `FileChooserSpec`, `DirectoryChooserSpec`, `ClientProfile`, `ClientKind`, `ChooserKind`, `ExitKind`, `FocusTarget`, `RevealMode`, `MainGeometry`, `AppEnvironment`, `LaunchOptions`, `AppClock`, `SamplePlan` |
| `core.app.flow` | `FlowContext`, `FormRequest`, `FileFlow`, `EditFlow`, `ViewFlow`, `ToolsFlow`, `RecoveryFlow`, `HelpFlow`, `StartupFlow`, `ExitFlow`, `AutosaveService`, `SettingsKeeper`, `ExternalChangeGuard`, `FileChooserService`, `SessionBridge`, `CoreWindowFactory`, `FormCatalog`, `SessionStores` |
| `core.ui.json` | `UiJson`, `WebIntent`, `WebQuery`, `WebEffect`, `WebBootstrap` |
| `core.ui.dump` | `UiDump` (схема 1), `DumpNormalizer`, `DumpDiff`, `AllowedDiffs` |
| `core.ui.selftest` | `SelfTestScript`, `SelfTestCommand` (sealed), `SelfTestRunner`, `UiDriver`, `ModelUiDriver` |
| `core.io` | `FolderListing` - обозреватель папок окна «Выбор файла» (перенесён из web `FolderBrowserApi`) |

Каждый пакет экспортирован в `module-info.java` и содержит настоящие классы. Модуль `ui-parity` (только тесты) подключается лишь профилями `ui-tests` и `e2e`; сборка по умолчанию его не строит.

## Контракты этапа S0

Все публичные записи, sealed-интерфейсы и интерфейсы заморожены на этапе S0 с русским Javadoc, описывающим поведение со ссылками на разделы спецификации. Ещё не реализованные методы бросают `UnsupportedOperationException("S<этап>: <задача> - <метод>")`. Уже работают: каталог текстов, токены дизайна, `UiFormats`, `LaunchOptions`, `AppEnvironment`, `AppClock`, `SamplePlan`, `FolderListing`, узел реестра (L2), строгий `Money.parse` (L1), поле `whatIfExtra` и дополнительные ключи `filters` во всех кодеках.

Дополнения к архитектуре, сделанные при заморозке:
- `FlowContext` - общий контекст потоков `core.app.flow` (порт, окружение, документ, состояние, настройки, вид, выделение, статусы, открытие форм и сообщений), чтобы три задачи S2 писали потоки параллельно. Файл не принадлежит ни одной задаче S2: недостающая операция - решение ведущего для всех сразу. Открытие форм - один путь `openForm(FormRequest, Placement, onResult)` для меню, дочерних окон и восстановления; `session(windowId)` и `singleInstance(type)` дают сеанс открытой формы (`FormSession.handle().toFront()`, закрытие быстрой правки); `installRecorder`, `setRecorderStatus`, `setAutosaveProblem` - для `StartupFlow` и автосохранения; `showAlert` сам создаёт `AlertSession` для `spec.restorable()`.
- `FormOutcome.Apply(action, fieldUpdates)` - действие без закрытия формы (калькулятор цели: «Записать цель в план», «Показать с доп. экономией»); `FormSession.Host.applied`. `FormOutcome.OpenChild.PENDING_ID` - временный id дочернего окна, который заменяет контроллер.
- События форм: `FormSession.fieldActivated` (двойной щелчок или Enter в списке), `fieldSubmitted` (Enter в однострочном поле; у всплывающего окна без кнопок - кнопка по умолчанию), `FormState.previewIndex` (выбор в предпросмотре, в снимок не пишется), `FieldView.min/max` (диапазон спиннера), одна группа RADIO в нескольких строках по id.
- `CommandId.FILTER_FOCUS_TABLE` (Enter в поле фильтра, §4) и `CommandId.PREVIEW_ADJUST` («Скорректировать эту дату…» в предпросмотре редактора правила, §6.3; источник `InvokeSource.FORM`).
- Клавиатурное контекстное меню: `UiPort.showContextMenu`, `UiIntents.key(chord, scope, focusId)`, web-эффект `contextMenu`. Наведение на график: `UiIntents.chartHover`, запрос `chartHover`. Web: эффект `reload`, `WebBootstrap.CHROME_TEXT_KEYS`.
- `MenuNode.Slider.currentLabel` (горизонт больше 120 месяцев), `AlertSpec.glyph` (⟲ восстановления), `AlertCatalog.quickEditUnavailable` (два варианта `err.quickEdit`).
- `UiDump` содержит все пути §10 (`frame/minSize`, `frame/titleBar`, `toolbar/wrap`, `screens`) и тексты, которые сверяют эталоны: результаты, предпросмотр, подсказки и разделы форм, вид и подсказки полей, значок и ширину сообщений, оформление ячеек, кнопки пустого состояния, группы и значения узлов меню.
- Сценарии самотеста: `slider`, `spinner`, `filtertype`, `rowclick`, `pick`, `enter` - у каждой команды описан настоящий путь событий, которым её выполняет драйвер.
- `Texts.AREAS` = 15 областей интерфейса + `model` (сообщения доменной модели; первое - ошибка группировки разрядов L1).

## Потоки и модальность

- Контроллер живёт в одном потоке: FX Application Thread, Swing EDT или web `ControllerThread`. Все обратные вызовы порта выполняются в нём и ровно один раз; таймеры возвращаются через `UiExecutor`.
- Пока открыто модальное окно, намерения главного окна (MENU, TOOLBAR, CONTEXT_MENU, HOTKEY, MAIN, SELFTEST) игнорируются; команда источника FORM выполняется, только если её форма - верхнее модальное окно. Desktop - `APPLICATION_MODAL`, web - `showModal`.
- Горячие клавиши ловит один диспетчер клиента до инструмента (FX - фильтр сцены, Swing - `KeyEventDispatcher`, web - `keydown` в фазе перехвата) и передаёт в `UiIntents.key`; ускорители меню только отображаются.

## Тестовые средства ядра

- `core/src/test/java/ru/cashprediction/core/app/fake/FakeUiPort` - записывает каждый вызов порта, отвечает на сообщения и выборы файлов по сценарию теста (ответ приходит после возврата из вызова, ровно один раз), работает на прямом `UiExecutor` и `ManualScheduler` с виртуальным временем. `FakeStates` - готовые `AppState`.
- `core/src/test/java/ru/cashprediction/core/text/JavaSourceScanner` - лексер исходников Java и JS: литералы с кодом до и после них, текст без комментариев (`NoCyrillicLiteralsTest`; этап S3 применяет его к клиентам и к JS web), число аргументов вызова.
- `core/src/test/java/ru/cashprediction/core/text/TextKeyUsage` - ссылки на ключи каталога по наборам исходников с владельцем (ядро, клиенты): отсутствующие ключи, расхождение числа аргументов и подстановок, используемые ключи всего и по клиенту (`UiTextCatalogTest`; для областей слоёв ядра неиспользуемый ключ - ошибка, для областей интерфейса проверка включается свойством `cashprediction.catalog.requireAllUsed=true` с этапа S2). `FormatWordsTest` проверяет, что каждое слово формата используется и ресурс не получает суффикса языка.
