# Архитектура CashPrediction для разработчиков

Руководство описывает текущие исходники приложения и точки расширения. Пути указаны относительно корня исходников; дополнительные документы для чтения не нужны.
Наличие реализации и тестов не означает, что проверки конкретной сборки уже выполнены.
Документ не подтверждает завершение этапов разработки или готовность публичного релиза.

## 1. Общая организация

CashPrediction - SOA внутри модульного монолита: предметные службы имеют явные контракты, но исполняются локально в одном процессе выбранного клиента.
Это подготовленные границы для возможного перехода к MSA, а не действующая микросервисная система.
Здесь нет независимого развёртывания служб, распределённых транзакций или брокера сообщений.
HTTP web-клиента передаёт намерения интерфейса в тот же контроллер, а не выделяет бизнес-службы в сеть.

JavaFX, Swing и Web - три способа запуска одного приложения. Каждый процесс имеет собственный контроллер, документ и сеанс; процессы не делят объектную память.
Одинаковая модель интерфейса строится в ядре, а клиенты переводят её в виджеты и события.
Общие файлы на диске сами по себе не делают одновременно открытые документы согласованными.

| Каталог | Модуль Java / назначение |
|---|---|
| `core` | `ru.cashprediction.core`: модель, прогноз, команды, хранение, интерфейс, восстановление, обновление |
| `ui-fx` | `ru.cashprediction.fx`: адаптеры JavaFX, `ru.cashprediction.fx.FxMain` |
| `ui-swing` | `ru.cashprediction.swing`: адаптеры Swing, `ru.cashprediction.swing.SwingMain` |
| `web` | `ru.cashprediction.web`: HTTP-сервер JDK, адаптеры и HTML/CSS/JS, `ru.cashprediction.web.WebMain` |
| `update-tool` | `ru.cashprediction.updatetool`: сборочный CLI `ru.cashprediction.updatetool.UpdateTool` |
| `dist` | Maven-модуль упаковки portable; отдельного модуля Java нет |
| `ui-parity` | Тестовый стенд; подключается профилями `ui-tests` и `e2e` |

Основной reactor также содержит `repository-doc-audits` для проверки документов репозитория. В поставке исходников этот модуль исключён; он не участвует в работе приложения.
Состав зависимостей: JDK, OpenJFX для JavaFX-клиента, JUnit для тестов.
Web использует локальные ресурсы без npm и CDN.

`core/src/main/java/module-info.java` не требует `java.desktop` или JavaFX. Ядро использует `java.prefs`, `java.xml` и `java.net.http`.
Swing требует `java.desktop`; Web дополнительно требует `jdk.httpserver`.
JavaFX использует `javafx.controls`; графические операции остаются в клиенте.

## 2. Данные, изменяемое состояние и зависимости

Предметные значения находятся в `ru.cashprediction.core.model`. `Plan`, `RecurringRule`, `OneTimeTransaction`, `Adjustment`, `Money`, идентификаторы и
описания повторов передаются как неизменяемые данные; списки плана копируются защитно.
Методы `with...` возвращают новые значения вместо изменения старых.
Так один снимок можно использовать в прогнозе, истории, предпросмотре и восстановлении
без скрытого изменения уже переданного результата.

Неизменяемость не заменяет бизнес-валидацию. Чтение файла допускает часть повреждённых данных для диагностики и исправления.
`ru.cashprediction.core.diagnostics.PlanValidator` проверяет предметные ограничения;
новые изменения дополнительно проверяет служба команд.
Не следует превращать все конструкторы модели в строгий фильтр открываемых файлов.

`sealed` закрывает ограниченные наборы вариантов данных: `Recurrence`, `PlanCommand`, `MenuNode`, `FormRow`, `FormOutcome`, `ChartPrimitive`.
Это позволяет исчерпывающе разбирать варианты и обнаруживать неполную обработку при компиляции.
Обычные интерфейсы служб и портов остаются точками подмены реализации.
Для нового вида повтора требуется обновить генерацию дат, кодеки и обработку вариантов.

`ru.cashprediction.core.document.PlanDocument` хранит текущий план, файл, dirty-флаг, историю undo/redo, `ViewState`, диагностику и кэш прогноза.
Он изменяем и рассчитан на поток владельца.
`AppState` и `DocumentView` из `ru.cashprediction.core.app` дают данные для построения UI;
это не транспорт предметной службы.

Сборка зависимостей выполняется обычными конструкторами, без DI-фреймворка. `AppController(UiPort, AppEnvironment)` создаёт документ с `EngineForecastService`,
один `LocalPlanCommands`, один `FilePlanStorage`, защиту внешних изменений и потоки действий.
Подписчик команд устанавливается раньше остальных слушателей документа.
`PlanDocument` и `FormContext` принимают `ForecastService`; формы получают тот же исполнитель.
`FileFlow` и `StartupFlow` имеют конструкторы с явным `PlanStorage`.
Совместимые старые конструкторы используют локальные реализации по умолчанию.

Практические паттерны здесь - Command для изменений, Adapter для клиентов и хранилищ, Strategy через `ForecastService` и `SessionStore`, Observer через события документа.
Полиморфизм позволяет подменять порт, часы, планировщик и службы в изолированных тестах.
`FlowContext` связывает сценарии с контроллером; это внутренний интерфейс приложения,
а не независимый удалённый сервис. В нём ещё доступны инфраструктурные объекты документа.
Будущий сетевой адаптер потребует отдельной сериализации, правил отказа и владения состоянием.

## 3. Служба команд плана

Контракт: `ru.cashprediction.core.service.plan.PlanCommands`. Локальная реализация: `LocalPlanCommands`.
Внешний контракт содержит данные, а не `PlanDocument`, UI-сеансы или функции изменения.

`snapshot()` возвращает `PlanCommandSnapshot`: предметную ревизию, план, описания доступной отмены и повтора.
`PlanCommandRequest` содержит UUID `requestId`, `expectedRevision`, описание истории
и типизированный `PlanCommand`. Повтор должен передавать весь тот же запрос.

Команды покрывают правила, разовые операции, корректировки, параметры, цель, валюту, горизонт, имя, актуализацию, сверку баланса, применение what-if, очистку, undo и redo.
Суммы и расписания приходят уже разобранными предметными значениями.
`EditFlow`, `ViewFlow` и `ToolsFlow` используют эту границу для предметных изменений;
файловая загрузка и восстановление отдельно устанавливают документ через `replace`.

Исполнение последовательно проверяет ревизию, строит проект, проверяет результат и фиксирует изменение одним шагом истории.
Равный исходному план даёт `UNCHANGED`, без новой истории или предметной ревизии.
Отказ до записи даёт `REJECTED`; успех - `APPLIED`.
`PlanCommandProblem` содержит машинный код, предметный адрес, аргументы и объяснение.
Среди кодов есть `STALE_REVISION`, `NOT_FOUND`, `DUPLICATE_ID` и ошибки проверки данных.

Предметная ревизия растёт на событиях `EventKind.PLAN`, в том числе undo/redo и замене документа при загрузке. Сохранение, дата и состояние вида сами по себе её не меняют.
Она отличается от ревизии экранной модели, формы и версии сохранённого файла.
Форма может удерживать ожидаемую ревизию открытия; устаревший результат не должен
молча перезаписывать более новый план.

Окно дедупликации - последние 256 завершённых запросов в памяти этого экземпляра. Идентичный повтор возвращает первоначальный результат с первоначальным снимком.
Другой запрос с занятым UUID даёт `REQUEST_ID_REUSED`.
Окно включает отказы проверки данных и ревизии; отказ повторного входа
`COMMAND_IN_PROGRESS` и коллизия UUID окно не занимают.
Загрузка/замена с файловым событием очищает окно.
После вытеснения запроса действуют обычные проверки `expectedRevision`.
Перезапуск не сохраняет дедупликацию; это не гарантия exactly-once в сети.

`preview()` проверяет и рассчитывает проект без изменения документа, истории, ревизии и окна повторов. Результат имеет статус `PREVIEW` и исходную ревизию.
Undo/redo в предпросмотре отклоняются.
Отмена и повтор в `execute()` используют историю документа, включая возврат
к терпимо загруженному состоянию, которое не обязано пройти проверку новых изменений.

Синхронный слушатель может завершиться с ошибкой после фиксации плана. Тогда принятый результат содержит `NOTIFICATION_FAILED`; считать его откатом нельзя.
Локальный исполнитель не потокобезопасен: обращения сериализует поток контроллера.

## 4. Служба сохранённых планов

Контракт: `ru.cashprediction.core.service.storage.PlanStorage`. Адаптер: `FilePlanStorage` над `ru.cashprediction.core.io.PlanRepository`.
Операции: `list`, `version`, `read`, `write`, `rename`.
Контракт использует непрозрачные `Reference`, `Collection` и `Version`,
а не `Path`, файловые атрибуты или транспорт.

Потребитель сравнивает токены версий на равенство, не разбирая их. Преобразования `FilePlanStorage.reference/collection/path` принадлежат файловому адаптеру
и интеграции выбора файла. Код бизнес-команды не должен декодировать локатор.
Непрозрачность контракта не означает, что токен является секретом или правом доступа.

`read` возвращает план с диагностикой и наблюдённой версией. Первое чтение допускает отсутствие ожидаемой версии; последующее может требовать её.
`write` и `rename` обязательно получают `expectedVersion`.
`Version.ABSENT` означает создание при отсутствии файла, а не разрешение перезаписи.
Ошибки имеют категории `MISSING`, `CONFLICT`, `CORRUPT`, `IO_ERROR`.
Причины конфликта различают `VERSION_CHANGED` и `NAME_EXISTS`.

Файловая версия включает атрибуты и SHA-256 содержимого. Сохранение прежнего времени сторонним редактором поэтому не скрывает изменение текста.
Адаптер проверяет версию вокруг чтения и непосредственно перед записью.
Его `synchronized` сериализует только операции одного экземпляра.
Между проверкой и записью остаётся окно для другого процесса:
это не межпроцессный атомарный compare-and-swap.

`ExternalChangeGuard` запоминает принятую версию, а `FileFlow` организует подтверждение, перечитывание и сохранение. Ошибка наблюдения не считается безопасным отсутствием.
Переименование сохранённого плана не включает несохранённые правки текущего документа.
`PlanStorageException` переносит структурированную проблему там, где поток требует исключения.

`PlanRepository`, `PlanMarkdownReader` и `PlanMarkdownWriter` сохраняют Markdown-формат; нераспознанные блоки представлены `RawBlock`.
`AtomicFiles` пишет UTF-8 без BOM во временный файл в той же папке, сбрасывает данные
и заменяет цель. При отсутствии поддержки atomic move используется обычная замена.
Атомарная замена одного файла не делает несколько файлов транзакцией.

## 5. Прогноз, календарь и точность

Контракт: `ru.cashprediction.core.forecast.service.ForecastService`. `EngineForecastService` вызывает `ru.cashprediction.core.forecast.ForecastEngine`.
`ForecastRequest` содержит план, `WhatIf`, явную дату `today` и `includeSkipped`.
Исполнитель не читает часы, не владеет документом, не обращается к UI или хранилищу.
Локальная реализация не хранит состояние и потокобезопасна; кэш принадлежит документу.
В `Forecast` массив ежедневных балансов защищён копиями.

`algorithmVersion()` возвращает версию семантики 1. Она фиксирует фазы повторов, сдвиги выходных, корректировки, порядок и округление;
это не версия Markdown, снимков или HTTP.
Изменение алгоритмической семантики требует явного решения о версии.

Ожидаемые отказы классифицируются через `ForecastFailure.kind()`: `LIMIT_EXCEEDED`, `AMOUNT_OVERFLOW`, `AMOUNT_OUT_OF_RANGE`, `DATE_RANGE_EXCEEDED`.
Конкретные исключения сохраняют исходную причину, сообщение и семейство исключения JDK.
Причину определяют по типу/коду, а не разбором русского текста.
Ошибки программирования вроде null-запроса не маскируются под предметный отказ.

`Money` хранит знаковый `long` минимальных единиц с двумя десятичными знаками. `Long.MIN_VALUE` запрещён; сложение и умножение проверяют переполнение.
Коэффициенты what-if используют `BigDecimal`, округление - `HALF_UP`.
Графические координаты могут быть дробными, но не заменяют точный денежный расчёт.
Формат `80 000,00` стабилен независимо от локали JVM.
Смена обозначения валюты не конвертирует суммы.

`ru.cashprediction.core.recurrence.OccurrenceGenerator` строит календарные события из `Recurrence` и опорной даты. `OccurrenceKey` сохраняет связь с номинальным событием
после переноса даты; корректировка не должна потерять эту идентичность.
`AppClock` обеспечивает явные часы приложения и фиксированную дату для самотестов.

## 6. Контроллер и общая модель интерфейса

Основные контракты находятся в `ru.cashprediction.core.app`. `UiIntents` передаёт действия клиента в `AppController`;
`UiPort` передаёт клиенту экран, формы, сообщения, фокус и запросы выбора файла.
`UiExecutor` и `Scheduler` из `ru.cashprediction.core.session` задают поток и таймеры.
JavaFX использует свой UI-поток, Swing - EDT, Web - последовательный поток контроллера.
HTTP-обработчик передаёт работу этому владельцу состояния.

Потоки в `ru.cashprediction.core.app.flow` распределяют сценарии: `FileFlow` - файлы и экспорт, `EditFlow` - правки, `ViewFlow` - отображение,
`ToolsFlow` - цель/what-if, `HelpFlow` - справка,
`StartupFlow`, `RecoveryFlow` и `ExitFlow` - жизненный цикл.
`AutosaveService` и `SettingsKeeper` выполняют отложенные записи через планировщик.
Отмена подтверждения выхода или ошибка запрошенного в нём сохранения плана оставляют сеанс работающим.
Ошибка записи настроек регистрируется отдельно и не препятствует чистому выходу.

`MainScreenModel` из `ru.cashprediction.core.ui.view` объединяет меню, тулбар, сводку, таблицу, график, статус и режим. `ScreenPart` позволяет обновлять части экрана.
`CommandAvailability` и `HotkeyTable` задают общие правила доступности и клавиш.
`MenuNode`/`ToolbarNode` описывают структуру, флаги, команды и тексты.
Клиент не должен заново вычислять предметную доступность действия.

`TableModel` имеет ленивый доступ к строкам; `LazyTableModel` держит индекс и LRU на 2000 готовых строк.
`ChartModel` строит `ChartScene` с примитивами, легендой и областями попадания.
Рисование PNG выполняет `UiPort`: JavaFX Canvas либо Java2D у Swing/Web.
Ядро получает байты и организует запись.

`FormSpec`, `FormView`, `FormLogic`, `FormSession` находятся в `ru.cashprediction.core.ui.form`.
Конкретные формы лежат в `ru.cashprediction.core.ui.forms.simple`, `.plan`, `.ops`.
`AlertSpec`, `AlertCatalog`, `AlertSession` - в `ru.cashprediction.core.ui.alert`.
Они определяют поля, проверку, кнопки, результат и восстановимое состояние.
Клиенты сохраняют пользовательский ввод при отказе.
Правило эха с `clientRev` защищает новое редактирование от запоздавшего ответа.

`CalendarModel` из `ru.cashprediction.core.ui.view.popup` содержит 42 дня, шесть недель с понедельника, выделение, текущий день и общие подписи.
Календарь рисуется адаптерами, без платформенного DatePicker/input type=date.
Соответствия меню, диалогов и popup отмечены комментариями
`// JavaFX: X → Swing: Y → Web: Z` у мест использования.
Например, `MenuBar` соответствует `JMenuBar` и web-menubar,
`ContextMenu` - `JPopupMenu` и web-контекстному меню.
Эти примеры не являются утверждением о проверке полного набора классов.

## 7. Web-протокол, тексты и ресурсы

Адаптеры: `ru.cashprediction.fx.ui`, `ru.cashprediction.swing.ui`, `ru.cashprediction.web.ui`; браузерный код - `web/src/main/resources/web/app`.
`UiApi` передаёт типизированные намерения контроллеру;
`CoreWebRuntime` управляет его запуском и завершением.
`ru.cashprediction.core.ui.json` содержит `WebBootstrap`, `WebIntent`,
`WebQuery`, `WebEffect` и `UiJson`.

Основные маршруты - `/api/ui/bootstrap`, `/api/ui/events`, `/api/ui/intent`, `/api/ui/query`.
Bootstrap даёт начальное состояние, events - последовательность эффектов с long polling,
intent - действие, query - запрашиваемые данные модели.
Ревизии помогают обнаружить устаревшие запросы таблицы и формы.
Это UI-протокол, его нельзя отождествлять с предметной ревизией `PlanCommands`.

Обычный API проверяет токен и адрес Host локального сервера. Переподключение после перезапуска имеет отдельные challenge/complete-маршруты
и доказательство в `ru.cashprediction.core.web.reconnect`.
Файлы `web-reconnect.md` и `web-reconnect-lock.md` служебные:
их нельзя предлагать пользователю как планы или цели экспорта.
Тестовый API включается явно параметром `--test-api`.

Строки по ключам для всех клиентов читаются из `core/src/main/resources/ru/cashprediction/core/ui/text/<область>_ru.properties`.
Текст справки о формате хранится в общем локализованном документе `help-format_ru.md` в той же папке и загружается через `Texts.document`.
UI использует `ru.cashprediction.core.ui.text.UiText`,
остальное ядро - `ru.cashprediction.core.text.Texts`.
Отдельные английские каталоги и переключатель языка не предусмотрены.
Грамматика файлов отделена: `ru.cashprediction.core.format.FormatWords`
читает `core/src/main/resources/ru/cashprediction/core/format/format.properties`.

Общие цвета, размеры и шрифты задают `DesignTokens`, `ColorToken`, `FontToken`, `TokenCss`. `UiIcons` из `ru.cashprediction.core.ui.token` предоставляет общие PNG,
цветовые варианты и значок приложения из `core/src/main/resources/ru/cashprediction/core/ui/icons`.
JavaFX/Swing загружают эти байты; Web получает их через `/app/icons/`.
Символ значка остаётся семантическим ключом, а не отдельным рисунком платформы.

В UI и записываемых данных используется дефис-минус вместо U+2013/U+2014; U+2212 понимается при вводе суммы, но не используется как UI-минус.
Отсутствующее значение объясняется словами, а не одиночным дефисом.
Комментарии Java/Javadoc и JSDoc пишутся по-русски; web-ресурсы также не содержат типографских тире.

## 8. Диск, реестр и восстановление

`AppPaths` и `CashMemoryLayout` из `ru.cashprediction.core.io` определяют `CashMemory` рядом с приложением; dev-запуск может задать другую базу.
В ней находятся планы `*.md`, `settings.md`, `session-fx.xml`,
`session-swing.xml`, `web-session.md` и `web-session.plan.md`.
Последний хранит несохранённый план web-сеанса.

Для обсуждения границ полезно выделять CashMemoryData: планы, настройки и данные сеанса. Это группа ответственности, а не существующий Java-класс или Maven-модуль.
`PlanStorage` покрывает только сохранённые планы;
настройки и снимки обслуживают `SettingsMarkdown`, `SessionStore` и их адаптеры.
Обновление тоже не проходит через `PlanStorage`.
Поэтому обещание «все файловые операции за одной службой планов» не соответствует коду.

Штатные служебные записи идут в CashMemory и пользовательский реестр. Явный выбор файла для открытия, сохранения или экспорта может задавать внешний путь.
`CashMemoryLayout.isProtectedUserPath` защищает служебные файлы своей копии,
включая реальные алиасы и hard link, от пользовательской перезаписи.
Самотесты могут писать результаты в явно указанную изолированную папку.

`SessionStores` выбирает для FX/Swing реестр и XML, для Web - серверный Markdown. Реализации находятся в `ru.cashprediction.core.session.store`;
кодеки JSON/XML/Markdown - в `ru.cashprediction.core.session.codec`.
Реестр доступен через `PreferencesRegistryBackend` и HKCU Java Preferences.

Узел установки: `ru/cashprediction/session/<клиент>-<8 hex хеша пути CashMemory>`. В Windows это ветка `HKCU\Software\JavaSoft\Prefs\ru\cashprediction`.
Значение `cashmemory.path` хранит путь копии.
Явный `--registry-node` задаёт префикс вне штатной ветки `session`;
`--registry memory` использует память процесса.
Тестовые узлы имеют вид `ru/cashprediction/selftest/<uuid>` и удаляются тестовым стендом.

`SessionRecorder` сохраняет снимок и маркер работы. `CrashDetector` различает чистый запуск, сбой и уже работающий экземпляр,
учитывая PID и время запуска. Для того же сеанса чистый маркер может разрешить
расхождение хранилищ; это не общая транзакция реестра и XML.
Второй экземпляр может открыться без записи сеанса.

`SessionBridge` связывает `SnapshotSource`/`RestoreTarget` с приложением; `RestoreCoordinator` и `CoreWindowFactory` восстанавливают план, вид,
геометрию и окна по сохранённым идентификаторам/контекстам.
`FormSession` сохраняет также незавершённые или неверные поля.
Чистый выход останавливает таймеры, записывает настройки и завершает рекордер;
аварийное завершение оставляет материал для следующего запуска.
Изменение схемы снимков требует проверки кодеков и существующих fixtures.

## 9. Обновление portable

Исходники: `ru.cashprediction.core.update.model`, `.tree`, `.net`, `.install`, `.lifecycle`; эти суффиксы относятся к префиксу `ru.cashprediction.core.update`.
Адаптеры трёх клиентов обращаются к контракту `UpdateSessionLifecycle`; на границе создания приложения фабрика подключает `UpdateLifecycle`.
При `app.release=0` или неподходящих метаданных сборки разработчика он инертен.

Перед UI проверяется локальное восстановление/установка; после готовности UI запускается один фоновый проход подготовки.
Предстартовая проверка достигается только после успешного разрешения модулей JVM и загрузки точки входа:
она не восстанавливает отсутствующий модуль, без которого приложение не может начать выполнение.
`UpdatePreparer` получает манифест, готовит полное дерево либо дельту и проверяет хеши.
Данные подготовки находятся в `CashMemory/Updates`, включая `Ready/tree`
и `Ready/update.json`. Журналы и блокировки защищают переходы подготовки и установки.
`InstallCoordinator`, `ProcessLease` и PowerShell-помощник согласуют замену и перезапуск.
Ошибки подготовки не блокируют обычный запуск и не требуют UI-уведомления.

Production-адрес GitHub фиксирован. Для portable-самотестов свойство `cashprediction.update.selftest.manifest` допускает только `http://127.0.0.1:<порт>/update.json`, одновременно с `--test-api`, `--home` этой копии и UUID-узлом `ru/cashprediction/selftest/<uuid>`.
Обычный запуск игнорирует такое свойство; dev-сборка остаётся инертной.

Update - исключение из правила обычных записей данных: установщик заменяет управляемые exe, `app/` и `runtime/` самой portable-копии.
`UpdateValidation.managedPath` ограничивает это дерево;
планы и настройки CashMemory не входят в обновляемый инвентарь.
`SafeTree` проверяет реальные пути и отказывается от ссылок/reparse.
Эти механизмы требуют проверки сценариев сбоя, блокировок и восстановления.

`UpdateTool` поддерживает `inventory`, `create`, `apply`, `verify`, `manifest`. Он служит подготовке артефактов и не является четвёртым launcher приложения.
Сборочные скрипты передают пути команд через `--arguments-base64` (UTF-8 JSON-массив в ASCII-конверте), поскольку внешний Java-лаунчер Windows может искажать Unicode через ANSI-кодировку. После декодирования выполняется обычная строгая проверка команд и путей.
Метаданные `AppInfo` получают `app.release` и `app.commit` из сборки.
Наличие updater-кода не подтверждает опубликованный манифест или проверенный канал обновлений.

## 10. Сборка, запуск и проверки

Текущий родительский POM задаёт JDK 25 без preview, OpenJFX 25.0.4 и JUnit 5.14.4. Для разработки нужны JDK 25 и Maven; скрипт проверки поставки указывает Maven 3.9+.
Для portable нужны Windows и PowerShell; для упаковки исходников - PowerShell 7 и 7-Zip.
Версия JavaFX jmods в dist должна совпадать с зависимостью JavaFX клиента.

Команды из корня исходников:

```powershell
mvn -B install
mvn -pl ui-fx javafx:run
mvn -pl ui-swing exec:exec
mvn -pl web exec:exec
mvn -B -Pdist -DskipTests package
```

Сначала install устанавливает зависимости модулей для отдельных dev-запусков. `cashprediction.dev.home` задаёт их базовую папку, по умолчанию корень проекта.
`-DskipTests` в portable-команде означает пропуск тестов, а не успешную проверку.

`dist/pom.xml` скачивает JavaFX jmods, собирает runtime через jlink и app-image через jpackage, затем zip.
Результат - `dist/target/dist/CashPrediction` и `CashPrediction-portable.zip`.
В папке три launcher: `CashPrediction.exe`, `CashPrediction-Swing.exe`,
`CashPrediction-Web.exe`, общий runtime и модули приложения.
`dist/launchers/swing.properties` и `web.properties` задают дополнительные точки запуска.
WiX и установка Java пользователем для app-image не требуются.

Runtime содержит только JDK и OpenJFX. Восемь корней jlink - `javafx.controls`, `java.desktop`,
`java.logging`, `java.prefs`, `java.xml`, `jdk.httpserver`, `jdk.localedata`, `java.net.http`;
зависимости добавляются транзитивно. Модули `ru.cashprediction.*` в `runtime/lib/modules` не включаются.
Четыре модульных JAR приложения передаются jpackage через `--module-path target/mods`, без `--input`.
После jpackage `dist/scripts/Normalize-AppModules.ps1 -ImageDir <новый app-image>` проверяет четыре JAR
и три cfg, перемещает JAR из временного `app/mods` прямо в `app/`, меняет module-path на `$APPDIR`
и удаляет пустой `app/mods`. Это сборочный шаг для новой папки без CashMemory, не установщик обновления.
Итоговый cfg задаёт `app.mainmodule` и пару JavaOptions `--module-path`, `$APPDIR`, без `app.classpath`.
Исполняемый код загружается из единственного набора `app/cashprediction-*.jar`, отдельно от системного runtime.
Инструменты обновления читают `ru/cashprediction/core/app.properties` из того же исполняемого core-JAR,
а не из дополнительной копии, предназначенной только для чтения версии.

Эта внешняя компоновка пока не подтверждена как безопасная для cold-bootstrap после частичной установки.
На каждой устойчиво записанной границе установки и отката обычный exe должен иметь доступ к целостному
согласованному набору app-модулей, достаточному для загрузки main и кода восстановления, даже если helper
уже остановлен, а основной app/runtime частично перемещён или заменён. Сохранения одной JVM недостаточно:
ошибка разрешения модулей возникает до вызова `UpdateLifecycle.beforeUi()`.

В текущих исходниках `PortableBootstrap` относит к защищённой полезной нагрузке старые `runtime/*`
и четыре `app/cashprediction-*.jar` (core, ui-fx, ui-swing, web). `BootstrapScript.PrepareBootstrap`
копирует этот набор в отдельное частичное дерево, проверяет его по инвентарю старой версии и публикует
в `CashMemory/Updates/Bootstrap`. Обычные exe сохраняются; их cfg перенаправляются одновременно
на `Bootstrap/runtime` и module-path `Bootstrap/app` до замены файлов основного дерева.
Для внешних модулей принимается только нормализованная пара `--module-path`, `$APPDIR`;
произвольный module-path и `app.classpath` не разрешаются.
Это описание механизма в исходниках, не подтверждение его отказоустойчивости. Ещё нужны реальные
cold-запуски после остановки helper на границах копирования и публикации bootstrap, перенаправления cfg,
BACKING_UP, INSTALLING и отката. До прохождения этих проверок безопасность новой схемы остаётся неподтверждённой.

Такое разделение позволяет менять JAR приложения без включения его кода в большой `runtime/lib/modules`.
При неизменных JDK/OpenJFX и параметрах сборки runtime может остаться прежним, а файловая дельта передаёт
только изменившиеся файлы дерева. Меньшая дельта не гарантируется одной архитектурой: её состав определяется
фактическими хешами, а смена runtime требует обновления соответствующих файлов.
Добавление core-JAR рядом с прежним runtime, уже содержащим приложение, не переносит исполняемый код наружу
и не обеспечивает эту границу. Проверяется реальное разрешение модулей и источник загрузки классов.

После package проверяют наличие JAR, три cfg и встроенную JVM. Например, без запуска main и GUI:

```powershell
$imageRoot = (Resolve-Path 'dist/target/dist/CashPrediction').Path
$imageJava = Join-Path $imageRoot 'runtime/bin/java.exe'
$imageModules = Join-Path $imageRoot 'app'
& $imageJava -XX:-UsePerfData --list-modules
& $imageJava -XX:-UsePerfData --module-path $imageModules --describe-module ru.cashprediction.core
foreach ($mainModule in 'ru.cashprediction.fx/ru.cashprediction.fx.FxMain',
    'ru.cashprediction.swing/ru.cashprediction.swing.SwingMain', 'ru.cashprediction.web/ru.cashprediction.web.WebMain') {
    & $imageJava -XX:-UsePerfData --enable-native-access=javafx.graphics '-Xlog:class+load=info' `
        --module-path $imageModules --dry-run -m $mainModule
    if ($LASTEXITCODE -ne 0) { throw "Module load failed: $mainModule" }
}
```

Список модулей без module-path должен исключать модули приложения; describe-module и журнал загрузки
должны указывать настоящие JAR из app/. Отдельно проверяют ровно четыре app-JAR и их SHA-256 по инвентарю,
отсутствие app/mods и app.classpath, module-path `$APPDIR` во всех трёх cfg, а для versioned-образа -
положительный release и нужный commit в AppInfo. Успех Maven сам по себе не подтверждает
эти условия. Dry-run проверяет разрешение модулей и загрузку main-класса, но не выполнение приложения,
нативный launcher, UI или матрицу обновления.
Загрузка модулей текущей полной версии, проверка метаданных versioned-образа и cold-bootstrap на границах
частичной установки являются отдельными проверками. Только фактические проходы нативной матрицы могут
подтвердить восстановление новой компоновки; обычный modular load этого не доказывает.

`dist/scripts/Set-LauncherUtf8.ps1` после jpackage добавляет `activeCodePage=UTF-8` во встроенные Windows-манифесты exe до упаковки.
Обрабатываются также `runtime/bin/java.exe` и `javaw.exe` внутри app-image; установленный JDK разработчика не изменяется.
Это обеспечивает корректную обработку Unicode-путей launcher.
При управлении процессом учитывают дочернюю JVM: завершать нужно всё дерево.

Дополнительные проверки выполняются явно:

```powershell
mvn -B -pl core "-Dtest=LocalPlanCommandsTest,FilePlanStorageTest,EngineForecastServiceTest" test
mvn -B -Pui-tests verify
mvn -B -Pe2e verify
powershell -NoProfile -ExecutionPolicy Bypass -File .github/scripts/Test-Portable.ps1 -PortableDir dist/target/dist/CashPrediction
pwsh -NoProfile -File dist/scripts/Test-Pack-Source.ps1 -VerifyBuild
```

UI-профилям нужен интерактивный Windows-стол и Edge. Оба профиля подключают `ui-parity`; конкретные требования определяют тесты стенда.
`Test-Portable.ps1` проверяет запуск трёх exe в разных Unicode-путях и инвентарь копии,
но его инвентаризация не доказывает отсутствие записей вне проверяемого дерева.
Локально команда приведена без `-CleanRegistry`.
Дополнительный `-Parity` запускает сценарий и crash/restore через настоящие портативные exe.
Для него нужны PowerShell 7, предварительно собранные тестовые классы `ui-parity` и `java.net.http` во встроенном runtime.
`Test-Pack-Source.ps1 -VerifyBuild` проверяет упаковку и install распакованных исходников.
Ресурсы сценариев, golden-файлы и legacy fixtures остаются частью проверки.
Для изменения службы начинают с соответствующих тестов `core/src/test/java`;
для UI добавляют проверки адаптеров и общего поведения.

Строгие наблюдения отрисовки описаны в `core.ui.selftest.paint`, независимо от схемы `UiDump`.
`PaintCaptureRequest` содержит идентичность опыта и намерения ввода, но не ожидаемые пиксели.
`UiDriver.capture` возвращает связанный `WidgetCapture` только при реализованном клиентском барьере;
по умолчанию метод явно отказывает, а не объединяет независимые dump и screenshot.
Непустой план захвата в `SelfTestRunner` должен покрывать все Shot сценария и проверяется до ввода.
`PaintCaptureFiles` сохраняет raw, PNG и наблюдение с commit последним, затем проверяет прочитанные байты.
Неизвестные свойства, нестабильный кадр, частичный набор и подмена идентичности не могут подтверждать визуальный успех.
Unit-фикстуры этих контрактов не заменяют настоящие захваты и независимо проверенные растровые эталоны.

### 10.1. Диагностический захват отрисовки

В `ru.cashprediction.fx.ui` сборщик `FxPaintCollector.collect(request, scene, toolbar, summary, transaction)`
читает свойства, raw и исходный PNG в одном вызове FX-потока, сравнивая наблюдения до и после снимка.
`Transaction` предоставляет геометрию и порядок поверхностей, поколения журнала, подтверждённый физический ввод,
происхождение установленного `Image` и среду запуска. `Result` сохраняет также неподдержанные и нестабильные
наблюдения; `requireSupported(request)` передаёт их строгой проверке `WidgetCapture`.

`FxCaptureJournal` подключают через `attach(Scene)` до захвата и закрывают на FX-потоке через `close()`.
Он слушает публичные свойства дерева и настоящий post-layout callback, сохраняя изменение даже при возврате
к прежнему значению. `snapshot(Scene)` выполняет настоящий `Scene.snapshot` и увеличивает `renderGeneration`
после успешного возврата. Полнота журнала остаётся `false`, `lastPaintEpoch(Node)` возвращает `null`:
внутренняя краска узлов, Canvas, изменения пикселей и CSS/render движка не получают полного публичного hook.
Post-layout callback и снимок сцены сами по себе не подтверждают показ кадра на экране.

`FxUiDriver.captureDiagnostic(request)` вызывается из рабочего потока при открытой главной сцене.
Один монотонный дедлайн ограничивает ожидание очереди, физический ввод через JavaFX Robot, подтверждения событий,
post-layout барьер и композицию снимков главной и дополнительных поверхностей. Драйвер снимает временные listeners
при завершении и ошибке, возвращая диагностические raw/PNG/наблюдения. `capture(request)` отказывает через
`UnsupportedCapture`, если есть unsupported или нестабильность, и не разрешает успешный commit.
В текущем журнале отсутствует доказательство краски каждого узла, а snapshot меняет поколение внутри захвата;
эти факты остаются причинами отказа. Хешируются фактически запущенные JAR, но загрузка шрифтов и полный состав
артефактов среды не сертифицируются. Диагностическая попытка не означает прохождения визуальной проверки.

В `ru.cashprediction.swing.ui` `SwingIcons` сохраняет исходные PNG и цепочку настоящих decode/scaling
по идентичности `Image`, используя слабые ссылки. Внутренний `decodedSource(Image)` проверяет текущие пиксели
и живых предков; неизвестный или изменённый объект не получает источник из ключа виджета или повторной загрузки.
`SwingPaintJournal(root)` ведёт отдельные эпохи экранной краски: `beginEpoch()`, полный `openOwner(owner, graphics)`
с `PaintScope.complete()` после возврата всего painter, затем `finishEpoch()`. Незавершённый scope и неизвестные
операции остаются диагностическими отказами; рисование в BufferedImage не признаётся экранным.

`SwingCaptureRepaintManager.install(root, journal)` явно устанавливается на EDT и возвращает handle для `close()`.
Он делегирует прежнему менеджеру, учитывает инвалидации и требует `synchronizeJournal()` на EDT перед обеими
границами захвата Robot. `close()` восстанавливает прежний менеджер только при сохранённом владении установкой.
Dirty region и возврат `paintDirtyRegions()` не подтверждают полный paint: автоматические проходы прежнего
менеджера могут миновать обёртку. `SwingPaintCollector.prepare(request, acknowledgedInput)` и `finish(...)`
связывают наблюдения вокруг неизменённого Robot PNG; ввод и ожидания выполняет интегратор вне EDT, слушатели
сборщика снимаются через `close()`. Эти компоненты ещё не соединены в полный `SwingUiDriver.capture`:
драйвер предоставляет обычные `dump`/`screenshot` и наследует отказ строгого захвата из `UiDriver`.

В Web `icon.js` подключает `enableRetainedIconCapture()` только при серверном `bootstrap.testApi=true`
(запуск с `--test-api`) и параметре `paintCapture=1` в URL вкладки, до создания значков при bootstrap.
Одного URL-параметра недостаточно. `retained-icon-sources.js` создаёт `createRetainedIconSources` для разрешённых
same-origin `/app/icons/*.png`: один первоначальный HTTP-ответ каждого URL удерживается до назначения источника
настоящему img или CSS-фону через `createRetainedImageRegistry`. Проверяются ответ, PNG, размер и дедлайн;
повторное назначение использует удержанные байты. Происхождение уже показанного значка не достраивается поздним
fetch по `currentSrc`. `sources.ready()` ждёт установки, `requireHealthy()` сохраняет отказ актуального источника;
они не подтверждают краску или стабильность кадра. При завершении инструментирования `sources.dispose()` отменяет
его запросы, а `registry.dispose()` освобождает принадлежащие реестру blob URL. Наличие этих API не означает
полного драйвера согласованного browser capture.

### 10.2. Подготовка и запуск нативных проверок обновления

Скрипты находятся в `.github/scripts`, сохраняются в поставке исходников и вызываются из корня проекта.
Для подготовки нужны Windows, PowerShell 7, полный JDK 25 и Maven; для нативных GUI-проверок нужен интерактивный
рабочий стол. Входные пути должны быть абсолютными, каноническими и без ссылок/reparse; SHA-256 передаются
для заранее проверенных неизменяемых входов. Сборка, подготовка команд и запуск клиентов являются разными шагами.

| Скрипт | Входы и фактический результат |
|---|---|
| `New-UpdateCandidateImages.ps1` | `SourceSnapshot` из `Pack-Source.ps1 -StageOnly` без target, новый `OutputRoot` под Temp с UUID в конце имени, пути `Maven`/`JdkHome`, три строго возрастающих положительных `ReleaseNumber` и один `Commit`. Копирует снимок, выполняет dist package с пропуском тестов, проверяет AppInfo и сохраняет три app-image, логи и `candidate-images.json`: `BUILT`, `nativeMatrix=PENDING`. |
| `New-UpdateBootstrapCommands.ps1` | Одна или две базы `PortableDir`, более новая `TargetPortableDir`, `Runtime` как полный JDK `bin/java.exe`, `RuntimeSha256`, `JdkModulesSha256`, `CoreJar`/`CoreJarSha256`, `ToolJar`/`ToolJarSha256`. Core JAR должен совпадать с целевым образом. В новом Temp/run-UUID публикует настоящий helper через Java source-file вызов `PowerShellHelper.publish`, выполняет CLI inventory/manifest/verify, создаёт полный ZIP и закреплённый `CommandFile`; возвращает `RunnerParameters`, `PREPARED`, `nativeMatrix=PENDING`. Helper и клиенты во время подготовки не запускаются. |
| `New-NativeUpdateLifecycleConfig.ps1` | `ArtifactDir` ровно с update.json, полным ZIP и двумя прямыми дельтами, `ExpectedManifestSha256`, 1-4 готовых каталога/JAR `HarnessClasspath` с `NativeUpdateServer`, новый `OutputFile` в разрешённой области Temp/UUID или dist/target. Закрепляет каждый файл harness и возвращает `{path, sha256}` для LifecycleFile; процессы не запускает. |
| `Test-NativeUpdateLifecycle.ps1` | Две независимые базы, целевой образ, Runtime, согласованные `CommandFile`/`CommandFileSha256` и `LifecycleFile`/`LifecycleFileSha256`. Повторяет проверки входов, запускает локальный NativeUpdateServer и настоящие jpackage exe в изолированных копиях, выполняет выбранные download/normal-exit сценарии и сохраняет evidence в новом Temp-каталоге. |

Три candidate-образа получают один commit и поэтому сами по себе не образуют допустимые базы bootstrap:
builder требует отличия commit каждой базы от целевого. Для настоящей матрицы нужны образы разных проверенных
снимков с соответствующими release/commit. Полный JDK обязателен для Java source-file публикации helper;
сокращённый runtime из portable не заменяет этот инструмент.

Минимальные вызовы в PowerShell 7 приведены с заранее заполненными параметрами; имена ключей указаны в таблице:

```powershell
$images = & .github/scripts/New-UpdateCandidateImages.ps1 @candidateInputs
$prepared = & .github/scripts/New-UpdateBootstrapCommands.ps1 @bootstrapInputs
$coldInputs = $prepared.RunnerParameters
& .github/scripts/Test-UpdateBootstrap.ps1 @coldInputs

$lifecycle = & .github/scripts/New-NativeUpdateLifecycleConfig.ps1 @lifecycleInputs
$nativeInputs.LifecycleFile = $lifecycle.path
$nativeInputs.LifecycleFileSha256 = $lifecycle.sha256
& .github/scripts/Test-NativeUpdateLifecycle.ps1 @nativeInputs
```

`Test-UpdateBootstrap.ps1` проверяет cold-запуск после остановки bootstrap helper через обычные exe.
Пример lifecycle является отдельным запуском: `$nativeInputs` содержит все параметры последней строки таблицы.
Bootstrap-builder создаёт манифесты без дельт, тогда как lifecycle требует две прямые дельты B1/B2 и точное
совпадение закреплённого целевого манифеста в CommandFile и ArtifactDir. Его результат нельзя передать lifecycle
без отдельной подготовки согласованного набора артефактов и конфигурации по контракту CommandFile.
Harness-классы должны быть предварительно собраны; runtime для сервера не служит альтернативным запуском клиента.

Нативный runner поддерживает `delta`, `corrupt-delta-full`, `cancel-next-session`, `offline`, `timeout`,
`malformed`, `once-three-attempts`, `leases-normal-close`, ограничивая выбор через `Scenario` и `MaxCells`.
Выполненные клетки могут получить PASS, но остальные остаются PENDING; общий результат без ошибки также PENDING,
а `-Signoff` отклоняется из-за неполной матрицы `UpdateEvidence`. Runner не удостоверяет DOM или скриншоты.
Подготовленные образы, команды и диагностические захваты не заменяют исполнение всех необходимых проверок.

## 11. Поставка исходников и расширение

`dist/scripts/Pack-Source.ps1` формирует разрешённое дерево и архив `.7z`. Из документации разработчика сохраняется только этот `docs/design/architecture.md`.
Файлы инструкций/истории агентов, служебные каталоги, результаты сборок и workflows
исключаются. Они не нужны для сборки приложения.
Ресурсы справки и настоящие ресурсы тестов, исходники runtime-модулей,
`update-tool`, `ui-parity`, dist и нужные PowerShell-скрипты сохраняются.

Упаковщик исключает `repository-doc-audits` и удаляет ровно его запись из staged POM. Исходный POM репозитория не меняется; остальные модули и профили остаются.
`.github/workflows` не поставляется, `.github/scripts` сохраняется для проверок.
Архив должен собираться локально без обращения к исключённым документам.
Пример упаковки в ещё не существующий архив:

```powershell
pwsh -NoProfile -File dist/scripts/Pack-Source.ps1 -OutputPath C:/Delivery/finalsource.7z
```

Проверка состава архива и проверка его сборки - разные проверки. Упаковка не подтверждает UI, аварийное восстановление, updater или выпуск.

Новая предметная операция начинается с `PlanCommand`, проверки и результата службы, затем подключается к общему потоку и форме. Новую логику не копируют в три клиента.
Другой backend реализует `PlanStorage`, сохраняя версии и классификацию конфликтов;
конкретные пути и преобразования локаторов остаются на границе адаптера.
Новый renderer реализует `UiPort` и передачу `UiIntents`, используя общие формы,
тексты, календарь, значки и семантику сохранения сеанса.

Для сетевого выделения службы ещё понадобятся транспортные DTO/кодеки, владение данными, устойчивая дедупликация, конкурентная запись и обработка недоступности.
Текущие локальные интерфейсы задают основу, но не предоставляют эти свойства автоматически.
Перед признанием конкретной поставки проверенной нужны свежие результаты сборки,
UI/parity, восстановления, portable, обновлений и распакованных исходников.
Это необходимые области верификации, а не отметки об их прохождении в данном документе.
