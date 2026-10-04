# Каталог интерфейса CashPrediction

## 1. Назначение и границы

Этот документ описывает компоненты, найденные в текущем коде, а не отдельную библиотеку демонстрационных виджетов. JavaFX, Swing и Web являются тремя renderer одного приложения: каждый запуск имеет собственный AppController, документ и сеанс; объектная память между процессами не общая. Предметное поведение и показываемые тексты задаются в core. Клиенты создают виджеты, обрабатывают события, раскладывают и рисуют полученные модели.

Используются только JDK, OpenJFX у JavaFX-клиента и JUnit в тестах; Web написан на локальных HTML/CSS/JS. Storybook, CoreUI, npm/CDN и SQL-компоненты для этого каталога не нужны и в нём не предполагаются.

По принятой политике исходный архив .7z должен включать все шесть технических документов docs/design: architecture.md, techstack.md, edge-cases.md, db-schema.md, linx.md и ui-kit.md. CurrentSprint, ContextDump, ChangeRequest и LegacyWarning являются рабочим AI-контекстом вне архива. Это правило состава, а не receipt уже собранного архива: упаковка здесь не проверялась.

Источники общей схемы: [architecture.md: слои, контракты и потоки интерфейса](architecture.md), [UiPort](../../core/src/main/java/ru/cashprediction/core/app/UiPort.java), [UiIntents](../../core/src/main/java/ru/cashprediction/core/app/UiIntents.java). При расхождении описания с реализацией проверяется текущий source. Ниже отдельно отмечено уже подключённое диагностическое API Swing: его наличие ещё не подтверждает полный экранный paint.

## 2. Общая модель и три renderer

Путь действия: событие клиентского виджета → UiIntents/AppController → поток сценария и модель core → UiPort → конкретный renderer. Для Web дополнительный транспортный путь проходит через UiApi, ControllerThread и WebIntent/WebEffect; браузер не пересчитывает финансовую модель.

[MainScreenModel](../../core/src/main/java/ru/cashprediction/core/ui/view/MainScreenModel.java) объединяет revision, заголовок, меню, тулбар, сводку, таблицу, график, статус и режим TABLE/CHART. [ScreenPart](../../core/src/main/java/ru/cashprediction/core/ui/view/ScreenPart.java) задаёт обновляемые области. Частичное обновление не разрешает клиенту заново определять доступность предметной команды.

| Область | Общий контракт и построение | Реальный renderer |
| --- | --- | --- |
| Главный экран | MainScreenModel, ScreenPart; UiPort.showMain/render | [MainWindowView.render](../../ui-fx/src/main/java/ru/cashprediction/fx/ui/MainWindowView.java), [MainFrameView.render](../../ui-swing/src/main/java/ru/cashprediction/swing/ui/MainFrameView.java), [Application.render](../../web/src/main/resources/web/app/main.js) |
| Меню и toolbar | [MenuNode](../../core/src/main/java/ru/cashprediction/core/ui/menu/MenuNode.java), MenuBarModel, ToolbarNode/ToolbarModel; MenuBarBuilder, ContextMenuBuilder, ToolbarBuilder | FxMenus/FxToolbar; SwingMenus/SwingToolbar; render-menu.js/render-toolbar.js |
| Команды и клавиши | [CommandId](../../core/src/main/java/ru/cashprediction/core/ui/command/CommandId.java), CommandArgs, CommandAvailability, HotkeyTable, KeyChord/FocusScope | Client key bridges и события виджетов передают действие в core; модель задаёт enabled/checked и привязку команды |
| Сводка | [SummaryModel](../../core/src/main/java/ru/cashprediction/core/ui/view/summary/SummaryModel.java), CardModel, SummaryBuilder | MainWindowView.summary; SwingSummaryPanel; render-summary.js |
| Таблица | [TableModel](../../core/src/main/java/ru/cashprediction/core/ui/view/table/TableModel.java), TableRowView, ColumnSpec, RowStyle/CellStyle, RowKind, Placeholder | FxTable; SwingTable; render-table.js |
| График | [ChartModel](../../core/src/main/java/ru/cashprediction/core/ui/view/chart/ChartModel.java), ChartScene, ChartPrimitive, LegendItem, HitRegion, PlotTransform | FxChartCanvas; SwingChart; render-chart.js. Экспорт PNG: клиентский UiPort, для Web WebChartPng |
| Статус | [StatusModel](../../core/src/main/java/ru/cashprediction/core/ui/view/status/StatusModel.java), StatusSegment/StatusLevel | MainWindowView; SwingStatusBar; render-status.js |
| Popup и календарь | [CalendarModel](../../core/src/main/java/ru/cashprediction/core/ui/view/popup/CalendarModel.java), DayCardModel, SparklineModel, PopupBuilders | FxUiPort/FxQuickEditPopup; SwingPopups/SwingCalendarPopup/SwingQuickEditPopup; render-popups.js/render-form.js |

Файлы адаптеров JavaFX находятся в ui-fx/src/main/java/ru/cashprediction/fx/ui, Swing - в ui-swing/src/main/java/ru/cashprediction/swing/ui, браузерные renderer - в web/src/main/resources/web/app. Серверный адаптер расположен в web/src/main/java/ru/cashprediction/web/ui.

[LazyTableModel](../../core/src/main/java/ru/cashprediction/core/ui/view/table/LazyTableModel.java) предоставляет ленивые строки и LRU-кэш на 2000 готовых строк. Web запрашивает страницы штатным rows query с revision и применяет только актуальные generation/epoch; наличие данных в серверном ответе само по себе не означает, что браузер их уже показал.

[UiApi](../../web/src/main/java/ru/cashprediction/web/ui/UiApi.java) проверяет Host и X-Token и передаёт изменение состояния владельцу ControllerThread. Общие типы [WebBootstrap](../../core/src/main/java/ru/cashprediction/core/ui/json/WebBootstrap.java), WebIntent, WebQuery, WebEffect и UiJson находятся в core.ui.json. Bootstrap/events/intent/query являются UI-протоколом; UI revision не следует смешивать с предметной версией плана или файла.

## 3. Формы, сообщения и повторное использование

### 3.1. Общий каркас

- [FormSpec](../../core/src/main/java/ru/cashprediction/core/ui/form/FormSpec.java): formId/windowType/purpose, Presentation, заголовок и значок, ширина, modal/resizable/restorable, страницы и порядок кнопок.
- [FormView](../../core/src/main/java/ru/cashprediction/core/ui/form/FormView.java): текущая страница, тексты, FieldView/ButtonView, preview/results и проблемы.
- [FormLogic](../../core/src/main/java/ru/cashprediction/core/ui/form/FormLogic.java): проверка и результат действий. FormOutcome описывает Stay, SetFields, Page, OpenChild, Apply и Close.
- [FormSession](../../core/src/main/java/ru/cashprediction/core/ui/form/FormSession.java): состояние одного окна; fieldChanged/buttonPressed/previewSelected/fieldActivated/fieldSubmitted/closeRequested; регистрация shown/closed и взаимодействие с WindowHandle.
- FieldSpec/FieldSpecs, FieldCodec/FieldChecks, Option, PreviewItem, ResultLine, Problem и ButtonSpec/ButtonRole: общий словарь полей, значений, ограничений, ошибок и кнопок.
- [AlertSpec](../../core/src/main/java/ru/cashprediction/core/ui/alert/AlertSpec.java), AlertCatalog и AlertSession: вид сообщения, содержимое, кнопки и ответ. Это отдельный контракт от формы с Presentation.CONFIRM.

FormSession канонизирует значение через FieldCodec, сохраняя некорректный ввод как текст, и пересчитывает FormView. При ошибке поля кнопки OK/FINISH отключаются. Для незавершённого ввода FieldView.value может быть null, чтобы renderer не переформатировал текст под курсором. В Web clientRev защищает свежую правку от запоздавшего form.view echo. Наличие этих контрактов не является свидетельством успешного прохождения всех keyboard/recovery сценариев.

[Presentation](../../core/src/main/java/ru/cashprediction/core/ui/form/Presentation.java) содержит DIALOG, WIZARD, TEXT_INPUT, CHOICE, LIST_CHOICE, CONFIRM, POPUP, FILE_BROWSER. Это способ представления общей формы, а не восемь независимых предметных реализаций. FILE_BROWSER используется для серверного выбора файла Web; у FX/Swing выбор файла выполняют их собственные toolkit wrappers.

### 3.2. Закрытый набор полей

Реальные switch/ветви находятся в [FxFieldWidgets](../../ui-fx/src/main/java/ru/cashprediction/fx/ui/FxFieldWidgets.java), [SwingFieldWidgets](../../ui-swing/src/main/java/ru/cashprediction/swing/ui/SwingFieldWidgets.java) и [FormWindow.field в render-form.js](../../web/src/main/resources/web/app/render-form.js). Источник видов - [FieldKind](../../core/src/main/java/ru/cashprediction/core/ui/form/FieldKind.java).

| FieldKind | JavaFX | Swing | Web |
| --- | --- | --- | --- |
| TEXT, MONEY, MONTH_DAY | TextField; MONEY с денежным форматированием core | JTextField | input type=text; MONEY с классом money |
| DATE | TextField и кнопка общего календаря | JTextField и SwingCalendarPopup | input type=text и div.calendar; не input type=date |
| MULTILINE | TextArea | JTextArea | textarea |
| SPINNER | Spinner | JSpinner | input type=number с min/max/step |
| CHOICE, EDITABLE_CHOICE | ComboBox, при необходимости editable | JComboBox, при необходимости editable | select либо input+datalist |
| CHECK | CheckBox | JCheckBox | input type=checkbox |
| RADIO | Группа RadioButton/ToggleGroup | JPanel с радиокнопками/ButtonGroup | fieldset с radio inputs |
| LIST | ListView | JList | select с size |
| PREVIEW | ListView | JList с renderer предпросмотра | div.preview |
| RESULT_LINES | VBox со строками результата | JPanel со строками результата | div.results |
| BUTTON | Button внутри формы | JButton внутри формы | button |

Календарь строится из CalendarModel: 42 дня, шесть недель с понедельника, выбранная и текущая даты и общие подписи. Клиентская сетка не заменяет модель системным DatePicker. Каноническая дата хранится в ISO, отображение и разбор задаёт FieldCodec; деньги и MONTH_DAY также не должны получать независимые правила в каждом renderer.

### 3.3. Конкретные формы

| Группа core | Реальные компоненты | Что переиспользуется |
| --- | --- | --- |
| ui.forms.plan | NewPlanWizardForm, PlanSettingsForm, GoalCalculatorForm, HorizonFields | Каркас страниц, поля, валидация, расчёт результата и восстановимое состояние |
| ui.forms.ops | RuleEditorForm, OneTimeForm, AdjustmentForm, QuickEditForm, OpsForms | Общая обработка правил/операций; быстрый ввод использует Presentation.POPUP |
| ui.forms.simple | TextInputForms, ChoiceForms, ConfirmForms, OpenPlanForm, CsvExportForm, FileBrowserForm | Одинаковый FormSession; меняются FormSpec, purpose и логика |
| ui.alert | AlertCatalog/AlertSession | Согласованные тексты, роли и порядок кнопок; toolkit alert остаётся клиентским |

FxFormDialog выбирает Dialog, TextInputDialog, ChoiceDialog или Alert, но заменяет содержимое общим FxFormPane/FieldSpec-раскладкой; специализированный editor переиспользуется там, где это применимо. SwingUiPort.openForm выбирает SwingTextInput, SwingChoice, SwingQuickEditPopup либо SwingFormDialog. Web строит FormWindow из Presentation и FormSpec. Нативные заголовки, владельцы, модальность, фокус, события и lifecycle остаются в wrappers; бизнес-валидация, тексты и смысл кнопки остаются в core.

## 4. Токены, шрифты и единые assets

### 4.1. Размеры и цвета

Источники - [DesignTokens](../../core/src/main/java/ru/cashprediction/core/ui/token/DesignTokens.java), [DialogWidth](../../core/src/main/java/ru/cashprediction/core/ui/token/DialogWidth.java), [ColorToken](../../core/src/main/java/ru/cashprediction/core/ui/token/ColorToken.java), [FontToken](../../core/src/main/java/ru/cashprediction/core/ui/token/FontToken.java), [TokenCss](../../core/src/main/java/ru/cashprediction/core/ui/token/TokenCss.java). Значения ниже взяты из кода; это не измерения отрисованного окна.

| Семейство | Примеры текущих значений |
| --- | --- |
| Сетка и строки | SPACING=4, ROW_HEIGHT=26, HEADER_HEIGHT=28, CONTROL_HEIGHT=28 |
| Основные области | TOOLBAR_HEIGHT=36, STATUS_HEIGHT=24; окно по умолчанию 1200x800, минимум 900x600 |
| Формы | FORM_LABEL_MIN_WIDTH=150, FORM_HGAP=10, FORM_VGAP=8, BUTTON_MIN_WIDTH=88 |
| Контент диалогов | ALERT=460, FORM=560, WIZARD=600, GOAL=640, RULE=880, RECOVERY=720, HELP=760, FILE_BROWSER=680 |
| Изображения | INLINE_ICON_SIZE=16, DIALOG_ICON_SIZE=26, ALERT_ICON_SIZE=30 |
| Время UI | TOOLTIP_DELAY_MS=600, CARD_POPUP_DELAY_MS=350, FILTER_DEBOUNCE_MS=0, WHAT_IF_SPINNER_DELAY_MS=600 |

Нулевая намеренная задержка фильтра не доказывает latency budget: планирование, вычисление и paint требуют отдельного реального измерения.

ColorToken задаёт роли bg.*, text.*, border.*, accent.*, income/expense, warning/what-if, сетку и линии графика, tooltip и тень. Примеры: bg.window=#F6F8FA, accent=#1F6FEB, income=#1B7F3B, expense=#B3261E. Роли с одинаковым ARGB могут иметь разные имена: canonical()/byArgb() выбирают канонический токен для дампа, а неизвестный цвет остаётся явным расхождением, а не подменяется ближайшим.

FontToken: BASE 13 px, SMALL 11, CARD 16 bold, HEADER 14 bold, MONO 12, LEGEND 12, MICRO 10. Основной порядок семейств - Segoe UI, Tahoma; моноширинный - Consolas, Cascadia Mono. Это предпочтения, не receipt фактически выбранного и загруженного шрифта.

FxStyles применяет FX стили/lookups; SwingLook - цвета, размеры и шрифты Swing. StaticHandler выдаёт /app/tokens.css из TokenCss.webCss(), а не из независимо поддерживаемой копии палитры. Клиентские styles управляют toolkit/DOM-раскладкой и не являются новым источником семантических токенов.

### 4.2. Иконки

Единственный каталог исходных ресурсов: [core/src/main/resources/ru/cashprediction/core/ui/icons](../../core/src/main/resources/ru/cashprediction/core/ui/icons). [UiIcons](../../core/src/main/java/ru/cashprediction/core/ui/token/UiIcons.java) содержит manifest(), whitelist имён, png(key[, color]), resource(filename) и applicationPng(). application.png/application.ico и цветовые варианты находятся здесь; отдельные клиентские рисунки значков не нужны.

Символ - семантический ключ, а не указание рисовать platform emoji/glyph. Примеры manifest: ↶ → undo.png, ↷ → redo.png, ▾ → chevron-down.png, ✎ → edit.png, ◎ → target.png; есть ASCII aliases folder/search и варианты по ColorToken. Если цветовой вариант недоступен, png(key, color) возвращается к исходному общему ресурсу. Неизвестное имя не разрешает произвольный путь к файлу.

Путь bytes → renderer:

- [FxIcons](../../ui-fx/src/main/java/ru/cashprediction/fx/ui/FxIcons.java): PNG core → Image/ImageView, в том числе значки приложения и диалогов.
- [SwingIcons](../../ui-swing/src/main/java/ru/cashprediction/swing/ui/SwingIcons.java): те же bytes → Image/ImageIcon и toolkit wrappers; decode/scaling не создают второй исходный asset.
- [StaticHandler](../../web/src/main/java/ru/cashprediction/web/StaticHandler.java): /app/icons.js и /app/icons/<filename> обслуживаются через UiIcons. [icon.js](../../web/src/main/resources/web/app/icon.js) назначает img/CSS background из этого manifest.

Декодированный Image, cache или blob URL - runtime-представление общих bytes, не отдельный исходный файл. Сравнение семантического ключа или совпадение source PNG SHA ещё не доказывает, какие пиксели показал renderer после scaling и paint.

SwingIcons хранит provenance по идентичности фактического Image через слабые ImageIdentity/WeakReference, а не по ключу виджета. decodedSource(Image) сверяет текущие размеры и SHA ARGB-пикселей установленного изображения и ещё живых предков decode/scaling с сохранённой цепочкой. Неизвестный, изменённый или нечитаемый проверяемый объект не получает происхождение из cp.text, UiIcons lookup или повторной загрузки ресурса. Эта проверка источника не удостоверяет экранный paint.

## 5. Общая локализация

Источник - [core/src/main/resources/ru/cashprediction/core/ui/text](../../core/src/main/resources/ru/cashprediction/core/ui/text). [UiText](../../core/src/main/java/ru/cashprediction/core/ui/text/UiText.java) делегирует общему [Texts](../../core/src/main/java/ru/cashprediction/core/text/Texts.java)/TextCatalog. Каталог UTF-8, язык зафиксирован ru; отдельного английского комплекта или переключателя языка нет.

| Область | Общие ресурсы |
| --- | --- |
| Навигация и действия | menu_ru.properties, toolbar_ru.properties, hotkeys_ru.properties, buttons_ru.properties |
| Экран | summary_ru.properties, table_ru.properties, chart_ru.properties, status_ru.properties, popup_ru.properties, dates_ru.properties |
| Формы и сообщения | forms-plan_ru.properties, forms-ops_ru.properties, forms-misc_ru.properties, alerts_ru.properties |
| Документ и службы | app/document/model/forecast/io/export/diagnostics/json/markdown/session/restore_ru.properties и существующие s2-*_ru.properties |
| Справка о формате | help-format_ru.md, загружается через Texts.document |

JavaFX/Swing получают готовые строки из моделей и общего каталога. Web получает их в JSON моделей и bootstrap texts; отдельный JS-словарь перевода не ведётся. UiFormats/Plurals задают общее форматирование и формы слов.

Денежное отображение стабильно относительно локали JVM: [Money.format()](../../core/src/main/java/ru/cashprediction/core/model/Money.java) формирует «80 000,00» с пробелом между разрядами и десятичной запятой без locale formatter; [UiFormats](../../core/src/main/java/ru/cashprediction/core/ui/text/UiFormats.java) использует такую же явную группировку для целых/сокращённых сумм. Это общий формат ядра, не отдельные клиентские форматтеры и не конвертация валюты.

Подстановки {0}, {1} выполняет TextCatalog, не MessageFormat. В строгом режиме UiText.STRICT_PROPERTY неизвестный ключ или недостающий аргумент вызывают ошибку; вне строгого режима неизвестный ключ показывается как !key!. Для новых ключей важно сохранить реальную literal-ссылку там, где её ожидает scanner UiTextCatalogTest; нельзя удалять unused-key guard ради динамического lookup.

Грамматика CashMemory отдельно: FormatWords читает core/format/format.properties; её ключевые слова не являются локализацией UI. В показанных пользователю строках и записываемых данных используются дефис-минус, а не U+2013/U+2014 или UI-минус U+2212. Отсутствующее значение объясняется словами. Разбор денежного ввода при этом понимает U+2212 как минус.

## 6. Карта 23 классов JavaFX и реальных аналогов

Закрытый список взят из [FxCensus.required()](../../ui-parity/src/test/java/ru/cashprediction/parity/check/census/FxCensus.java), не из полного набора toolkit widgets. Первые 18 имён относятся к javafx.scene.control, ContextMenuEvent - к javafx.scene.input, последние четыре - к javafx.stage. Таблица описывает конструкцию/callsite и аналог, но не заявляет receipt 23/23 instantiated или native PASS.

| № | JavaFX | Swing | Web | Реальные callsites/адаптеры |
| --- | --- | --- | --- | --- |
| 1 | MenuBar | JMenuBar | nav[role=menubar] | FxMenus.bar / SwingMenus.bar / render-menu.js Menus.bar |
| 2 | Menu | JMenu | button[role=menuitem] и div[role=menu] | FxMenus.item / SwingMenus.widget / Menus.node |
| 3 | MenuItem | JMenuItem | button[role=menuitem] | FxMenus.item / SwingMenus.widget / Menus.node |
| 4 | CheckMenuItem | JCheckBoxMenuItem | button[role=menuitemcheckbox] | Те же menu builders, ветвь CHECK |
| 5 | RadioMenuItem | JRadioButtonMenuItem и ButtonGroup | button[role=menuitemradio] | Те же builders, радио-пункт и общая группа |
| 6 | SeparatorMenuItem | JPopupMenu.Separator | hr | Те же builders, ветвь разделителя |
| 7 | CustomMenuItem | JPanel с JLabel и JSlider/JSpinner | input type=range/number внутри меню | FxMenus.item / SwingMenus.slider, spinner / Menus.node |
| 8 | MenuButton | JButton и JPopupMenu | button и div[role=menu] | FxToolbar.render / SwingToolbar.render / render-toolbar.js |
| 9 | SplitMenuButton | JPanel с основной JButton и JButton-стрелкой | div.toolbar-node.SplitButton с двумя button и menu-panel | Те же toolbar renderer, отдельные основное действие и dropdown |
| 10 | PopupControl | SwingPopups.spark, JWindow | div.sparkline | FxUiPort.spark / SwingPopups.spark / Popups.sparkline в render-popups.js |
| 11 | Tooltip | JToolTip; ToolTipManager и собственные обработчики | div.tooltip | FxStyles.tip/createTip / SwingLook, SwingTable, SwingFieldWidgets / render-popups.js |
| 12 | ContextMenu | JPopupMenu | div.context-menu / div[role=menu] | FxMenus.context / SwingMenus.popup / Menus.context |
| 13 | Dialog | SwingFormDialog, JDialog | dialog формы | FxFormDialog / SwingFormDialog / render-form.js FormWindow |
| 14 | DialogPane | JPanel | div.form-body | FxFormPane в FxFormDialog / SwingFormDialog / FormWindow |
| 15 | ButtonType | JButton в панели действий | button формы или сообщения | FxFormDialog/FxAlerts / SwingFormDialog/SwingAlerts / render-form.js/render-alert.js |
| 16 | Alert | SwingAlerts, JDialog; ранние ошибки через JOptionPane | dialog сообщения | FxAlerts и CONFIRM в FxFormDialog / SwingAlerts / render-alert.js |
| 17 | TextInputDialog | SwingTextInput | dialog с input, Presentation.TEXT_INPUT | FxFormDialog TEXT_INPUT / SwingUiPort.openForm / FormWindow |
| 18 | ChoiceDialog | SwingChoice | dialog с select, Presentation.CHOICE | FxFormDialog CHOICE / SwingUiPort.openForm / FormWindow |
| 19 | ContextMenuEvent | MouseEvent.isPopupTrigger | DOM contextmenu | FxTable/MainWindowView / SwingTable/SwingChart / render-menu.js и render-table.js |
| 20 | PopupWindow | JWindow | div.dayCard и другие popup surfaces | Базовый класс Popup/PopupControl; FxUiPort, SwingPopups, render-popups.js |
| 21 | Popup | PopupFactory, JWindow или JPopupMenu по назначению | div.quick-edit/div.calendar/div.dayCard | FxQuickEditPopup/FxUiPort / SwingQuickEditPopup/SwingCalendarPopup/SwingPopups / render-form.js/render-popups.js |
| 22 | FileChooser | JFileChooser | Presentation.FILE_BROWSER, dialog выбора серверного файла | FxUiPort.chooseFile / SwingUiPort.chooseFile / FileBrowserForm + render-form.js |
| 23 | DirectoryChooser | JFileChooser с DIRECTORIES_ONLY | FILE_BROWSER с режимом выбора папки | FxUiPort.chooseDirectory / SwingUiPort.chooseDirectory / FileBrowserForm + render-form.js |

Файлы для проверки таблицы: [FxMenus](../../ui-fx/src/main/java/ru/cashprediction/fx/ui/FxMenus.java), [SwingMenus](../../ui-swing/src/main/java/ru/cashprediction/swing/ui/SwingMenus.java), [render-menu.js](../../web/src/main/resources/web/app/render-menu.js); [FxToolbar](../../ui-fx/src/main/java/ru/cashprediction/fx/ui/FxToolbar.java), [SwingToolbar](../../ui-swing/src/main/java/ru/cashprediction/swing/ui/SwingToolbar.java), [render-toolbar.js](../../web/src/main/resources/web/app/render-toolbar.js); [FxFormDialog](../../ui-fx/src/main/java/ru/cashprediction/fx/ui/FxFormDialog.java), [SwingFormDialog](../../ui-swing/src/main/java/ru/cashprediction/swing/ui/SwingFormDialog.java), [render-form.js](../../web/src/main/resources/web/app/render-form.js); [FxUiPort](../../ui-fx/src/main/java/ru/cashprediction/fx/ui/FxUiPort.java), [SwingUiPort](../../ui-swing/src/main/java/ru/cashprediction/swing/ui/SwingUiPort.java), [render-popups.js](../../web/src/main/resources/web/app/render-popups.js).

ContextMenuEvent является событием, а не ещё одним виджетом. PopupWindow учитывается как реальный базовый класс созданного Popup/PopupControl: [FxClassUsageProbe.created](../../ui-fx/src/main/java/ru/cashprediction/fx/ui/FxClassUsageProbe.java) проходит цепочку superclass. Импорт класса или наличие new в source не заменяет положительный classCensus настоящего запуска; полный набор типов в одном сценарии не заменяет всю заявленную матрицу.

Toolkit-аналог не обязан иметь такое же Java-имя или inheritance. Например, Swing split button - композиция двух кнопок, Web меню - DOM структура, а FILE_BROWSER - серверная форма, не доступ браузера к произвольному локальному диску. Значки, тексты и предметное действие при этом общие.

## 7. Как посмотреть и проверить компоненты

### 7.1. Существующие клиенты, не новый стенд

Отдельного Storybook или component explorer в проекте нет. Компоненты доступны в обычном главном окне и существующих меню/формах. Для локального просмотра после согласования MAIN используются штатные main:

- ru.cashprediction.fx/ru.cashprediction.fx.FxMain;
- ru.cashprediction.swing/ru.cashprediction.swing.SwingMain;
- ru.cashprediction.web/ru.cashprediction.web.WebMain.

ClientTarget/ClientLauncher уже знают module path и эти entrypoints; собранные portable launcher используют тот же продукт. Просмотр должен иметь изолированный --home и UUID-узел --registry-node ru/cashprediction/selftest/<uuid>; не следует направлять эксперимент в живую session-ветку. Web --test-api включают явно для автоматизации. Headless browser полезен для отдельных DOM-контрактов, но не заменяет обычный клиент и доказательство видимого native frame.

Для одного разрешённого просмотра готовых модулей, без запуска сборки, форма команды такова:

```powershell
# Только после снятия GUI-барьера MAIN. Это пример вызова, не выполненная проверка.
& $java --module-path $fxModulePath --module 'ru.cashprediction.fx/ru.cashprediction.fx.FxMain' --home $fxHome --registry-node $fxSelftestNode
& $java --module-path $swingModulePath --module 'ru.cashprediction.swing/ru.cashprediction.swing.SwingMain' --home $swingHome --registry-node $swingSelftestNode
& $java --module-path $webModulePath --module 'ru.cashprediction.web/ru.cashprediction.web.WebMain' --home $webHome --registry-node $webSelftestNode
```

Каждый home/node должен принадлежать своему запуску. Конкретные JAR paths берут из свежего согласованного candidate, а не из произвольной старой сборки. Завершение launcher не гарантирует завершения дочерней JVM; existing process-tree cleanup и RegistryNodeCleaner обслуживают только owned resources.

### 7.2. Общие сценарии и проверки

[SelfTestScript](../../core/src/main/java/ru/cashprediction/core/ui/selftest/SelfTestScript.java) загружает существующие .cps из core/src/main/resources/ru/cashprediction/core/ui/scenarios либо явно указанного файла. SelfTestRunner выполняет script через UiDriver. Команды включают menu/click/key, fill/ok/cancel, view/period/filtertype, dump/shot и служебную синхронизацию.

| Компоненты | Существующий сценарий для просмотра/воспроизведения |
| --- | --- |
| Старт, экран и таблица | s01-first-run, s02-sample-table |
| График, popup и context menu | s03-chart, s04-context-menus |
| Параметры плана и калькулятор цели | s05-forms-plan |
| Редактор правила, разовая операция, корректировка | s06-forms-ops |
| Прочие формы и сообщения | s07-forms-misc, s08-alerts |
| What-if и меню controls | s09-whatif |
| Пустые состояния и прошедшие события | s10-filter-empty-states, s11-past-group-reveal |
| Быстрая правка и отмена | s12-quick-edit, s13-undo-redo |
| Файловые конфликты и keyboard | s14-save-conflicts, s15-keyboard |
| Dirty exit, восстановление и второй экземпляр | s16-exit-dirty, s17-recovery-dialog, s18-already-running |

Пример добавления к одной из команд клиента: --today 2026-09-13 --selftest s05-forms-plan --selftest-out <owned-output>. Для Web scripted driver используется существующим ScenarioCollector/WebScenarioSession, а не самостоятельно написанным JS runner. Эта таблица показывает точки входа; не обещает покрытие каждого состояния каждой формы только одним сценарием.

Фактические виды проверки:

- Headless core contracts: [UiTextCatalogTest](../../core/src/test/java/ru/cashprediction/core/ui/text/UiTextCatalogTest.java), UiTextTest, NoDashesInUiTextTest, [DesignTokensTest](../../core/src/test/java/ru/cashprediction/core/ui/token/DesignTokensTest.java), [UiIconsTest](../../core/src/test/java/ru/cashprediction/core/ui/token/UiIconsTest.java) и tests форм. Проверяют каталог/контракт, не экранный paint.
- Общая semantic parity: [ParityTest](../../ui-parity/src/test/java/ru/cashprediction/parity/pipeline/ParityTest.java), ParityPipeline и существующие core/src/test/resources/ui-golden. Real path включается parity.realClients=true; selected parity.clients/parity.scenarios ограничивают срез, а не дают PASS всей матрицы.
- [ClassUsageTest](../../ui-parity/src/test/java/ru/cashprediction/parity/check/ClassUsageTest.java)/FxCensus собирают instantiated classCensus всех 23 типов. Отдельный DirectoryChooserProbe проверяет реальный запрос выбора папки и команду; не следует дополнять counts mock-значениями.
- [VisualParityTest](../../ui-parity/src/test/java/ru/cashprediction/parity/check/VisualParityTest.java)/VisualSuite требуют parity.visual=true и parity.realClients=true для реальных снимков. Синтетические проверки PNG/геометрии в тех же тестах являются отдельным scope.
- SharedIconContractTest/SharedIconBrowserContractTest проверяют source/transport contracts общих assets. Положительная fixture не удостоверяет реальное назначение, scaling или краску конкретного Image/DOM node.

Пример команды описывает способ запуска, но не доказывает его исполнение или принятие результата. Для приёмки нужны receipts конкретного согласованного запуска с указанием проверенного candidate, выбранного среза и фактического результата.

## 8. Diagnostics и действительно подтверждённая отрисовка

[UiDump](../../core/src/main/java/ru/cashprediction/core/ui/dump/UiDump.java) схемы 1 содержит данные экрана, геометрию и widget state. ModelUiDriver строит model dump для эталонов; FxUiDriver/SwingUiDriver и Web dump.js читают widgets/DOM. Нормализованный JSON полезен для semantic parity, но model dump, widget dump и PNG являются разными видами evidence.

[UiDriver.capture](../../core/src/main/java/ru/cashprediction/core/ui/selftest/UiDriver.java) по умолчанию явно отказывает. Строгий контракт core.ui.selftest.paint отделён от UiDump: PaintCaptureRequest содержит identity, намерения и дедлайн, не ожидаемые пиксели; WidgetCapture требует согласованных raw/PNG/PaintObservation. PaintCaptureFiles записывает commit последним и проверяет прочитанные bytes. Unsupported, missing metadata, нестабильность и foreign identity не превращаются в успешный capture.

Текущее состояние адаптеров, проверенное по source:

- FX: [FxUiDriver.captureDiagnostic](../../ui-fx/src/main/java/ru/cashprediction/fx/ui/FxUiDriver.java) связывает физический ввод, подтверждения событий, post-layout и снимки с одним дедлайном и cleanup. FxCaptureJournal отслеживает публичные изменения, но lastPaintEpoch(Node) возвращает null, а журнал не является полным hook внутренней краски/CSS/Canvas. Scene.snapshot/post-layout не доказывают показ кадра на экране; unsupported/нестабильность вызывают UnsupportedCapture в capture().
- Swing: [SwingUiDriver.captureDiagnostic](../../ui-swing/src/main/java/ru/cashprediction/swing/ui/SwingUiDriver.java) подключён в текущем root, не только в scratch: raw и geometry читаются на EDT, внешний Robot PNG - на worker, capture() вызывает requireSupported(). [MainFrameView](../../ui-swing/src/main/java/ru/cashprediction/swing/ui/MainFrameView.java) создаёт SwingPaintRoot; CaptureTransaction использует его SwingPaintContext, а SwingPaintJournal, SwingCaptureRepaintManager и SwingPaintCollector ведут диагностический bracket. Однако finish() безусловно добавляет unsupported с property=paint-hooks: без context нет подключённых hooks, с context callbacks подключены, но полный census UI delegate image/text/shape и внешнее перекрытие не поддержаны. requireSupported() отвергает непустой unsupported или нестабильную синхронизацию до создания WidgetCapture. Наличие context/capability, обновлённый focus, dirty region, paintDirtyRegions, BufferedImage или один screenshot не означают supported capture или завершённую экранную краску.
- Web: paintCapture=1 действует только вместе с server bootstrap.testApi=true. [retained-icon-sources.js](../../web/src/main/resources/web/app/retained-icon-sources.js) и icon.js удерживают первоначальные same-origin PNG bytes до назначения img/CSS background; late fetch по currentSrc не восстанавливает происхождение уже показанного изображения. ready()/requireHealthy() и DOM revision не доказывают paint. [paint-observation.js](../../web/src/main/resources/web/app/paint-observation.js) отмечает lastPaintEpoch=0 как unavailable и сохраняет unsupported, а не подтверждение завершения краски узла.

У retained assets освобождаются owned requests/blob URL; у capture снимаются listeners и установленные wrappers/managers с сохранением владения. Cleanup не должен затрагивать чужой UI-сеанс.

Для actual verified render нужны настоящий согласованный запуск, свежие идентификаторы/source и artifact SHA, поддержанные capture boundaries, исходные raw/PNG/observations и независимая проверка растрового результата. Headless contracts, census, model goldens, наличие observer API или успешный диагностический захват сами по себе не утверждают S4/S5/native approval и не подтверждают latency budget. Незавершённые scratch collectors не являются реализованной частью этого каталога.
