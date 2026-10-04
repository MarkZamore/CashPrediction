# Схемы хранения CashPrediction

Документ описывает текущие reader/writer-контракты, а не проект SQL-базы. Хранилища - редактируемый человеком Markdown, XML и Windows Preferences; служебные данные обновлений - JSON и ZIP. СУБД, SQL-таблиц, ORM и постоянных вторичных индексов нет.

Этот документ входит в набор технической документации исходников вместе с [архитектурой](architecture.md), [стеком](techstack.md), [граничными случаями](edge-cases.md), [LINX](linx.md) и [UI-kit](ui-kit.md). Включение всех шести в source .7z - правило комплектации, не заявление о проверке уже собранного архива. Рабочие CurrentSprint, ContextDump, ChangeRequest и LegacyWarning не являются частью схем данных или архивной технической документации.

Описание кода не подтверждает завершение S4/S5, прохождение native-приёмки или готовность релиза. Отказы и ограничения описаны в [edge-cases.md](edge-cases.md); здесь приведены структуры и протоколы, необходимые для их понимания.

## 1. Размещение и версии

Обычные данные приложения находятся в CashMemory рядом с portable exe. AppEnvironment связывает экземпляр приложения с этой папкой. Открыть внешний Markdown для чтения можно; application-managed save, staging, saveAs, CSV и PNG не получают от этого права писать снаружи CashMemory. Внешний документ остаётся импортированным/readonly до явного сохранения копии внутри папки. CanonicalPaths и AtomicFiles.requireWriteScope отвергают неканонические пути и существующие reparse/symlink-компоненты. Это проверки пути, не межпроцессная блокировка изменения каталогов.

| Представление | Файл/носитель | Версия и точка определения |
|---|---|---|
| План | CashMemory/<имя>.md или внешний файл для чтения | «Формат: CashPrediction 2», MarkdownFormat.FORMAT_VERSION |
| Настройки | CashMemory/settings.md | Отдельного номера схемы нет, SettingsMarkdown |
| Снимок сеанса | Реестр, session-<клиент>.xml, web-session.md | SnapshotSchema.CURRENT = 1 |
| Манифест обновления | update.json | schemaVersion = 2, UpdateCodec |
| Указатель опубликованного релиза | release.json | schemaVersion = 1, сборочные PowerShell scripts |
| Прямая дельта | patch.json внутри .cpdelta | schemaVersion = 1, algorithmVersion = 1 |
| Журнал установки | CashMemory/Updates/install-journal.json | 1 или 2; схема 2 добавляет portable bootstrap |
| Lease, restart request, итог установки | Updates/processes, requests, last-install.json | schemaVersion = 1 |

Версии независимы. ForecastService.algorithmVersion() = 1 обозначает семантику расчёта, не формат плана или сеанса. Смена релизного номера не означает миграцию данных. Общего migration runner и SQL-миграций в коде нет.

Опорные исходники: `core/src/main/java/ru/cashprediction/core/markdown/MarkdownFormat.java`, `core/src/main/java/ru/cashprediction/core/session/SnapshotSchema.java`, `core/src/main/java/ru/cashprediction/core/update/model/UpdateCodec.java`.

## 2. План Markdown: сущности и поля

Корневая сущность Plan содержит name, note, currency, startDate, startBalance, horizon, cushion, nullable goal и неизменяемые списки rules, oneTimes, adjustments, rawBlocks. Имя плана и имя файла связаны соглашением репозитория, но не являются глобальным ID. Несколько файлов могут содержать одинаковое название.

Грамматика заголовков, колонок и слов находится в `core/src/main/resources/ru/cashprediction/core/format/format.properties`. Она не переводится вместе с UI. PlanMarkdownWriter выводит UTF-8 без BOM через вызывающий файловый код, LF и завершающий перевод строки. План не получает timestamp или скрытый служебный UUID.

### 2.1. Заголовок и параметры

Заголовок: `# План: <name>`. Раздел `## Параметры` содержит список `- ключ: значение`.

| Ключ файла | Поле/тип модели | Вывод и чтение |
|---|---|---|
| Формат | Версия текстового кодека | Writer всегда пишет CashPrediction 2 |
| Валюта | currency: String | Подпись валюты, не курс и не денежный тип; default ₽ |
| Начало | startDate: LocalDate | Writer YYYY-MM-DD; reader понимает также русскую запись даты |
| Горизонт | Horizon.Months / Horizon.Years / Horizon.Until | Число месяцев/лет или «до YYYY-MM-DD»; Months 1..600, Years 1..50 |
| Начальный баланс | startBalance: Money | Знаковая сумма; default 0 |
| Подушка безопасности | cushion: Money | Writer опускает ноль |
| Цель | goal.target: Money | Только при наличии Goal |
| Цель к дате | goal.wishDate: LocalDate или null | Необязательная дата |
| Название цели | goal.title: String | Writer опускает пустое название |

При отсутствии/ошибке обязательного параметра reader выдаёт диагностику и использует defaults: валюту ₽, today для начала, 12 месяцев для горизонта, ноль для начального баланса. today передаёт вызывающий; читатель не выбирает произвольную постоянную дату. Без заголовка имя берётся из fallbackName, обычно имени файла. Наличие терпимо полученного Plan не равнозначно прохождению PlanValidator.

`## Заметка` хранит многострочный текст. Необязательные секции могут быть выведены даже пустыми, если к ним привязан RawBlock.

### 2.2. Регулярные операции

`## Регулярные операции` - Markdown-таблица. Полный порядок колонок writer:

`ID | Название | Тип | Сумма | Категория | Повтор | С | По | Выходные | Активна | Заметка`.

| Колонки | Тип/смысл |
|---|---|
| ID | RuleId.value: строка, уникальная внутри списка правил плана |
| Название, Категория, Заметка | Строки |
| Тип | Kind: доход / расход; задаёт знак при расчёте |
| Сумма | Money; предметно положительная величина, знак не хранится как направление операции |
| Повтор | Один из четырёх вариантов Recurrence |
| С, По | nullable LocalDate; границы включительно |
| Выходные | WeekendPolicy: нет / раньше / позже |
| Активна | boolean: да / нет; пустое значение reader считает включённым |

Reader требует колонки Тип, Сумма, Повтор; остальные могут отсутствовать. Распознавание идёт по заголовкам, не по фиксированным позициям. Writer выводит таблицу правил и при пустом списке.

### 2.3. Разовые операции и корректировки

`## Разовые операции`: `ID | Дата | Название | Тип | Сумма | Категория | Заметка`.
OneTimeTransaction содержит TxId, обязательную LocalDate, строки title/category/note, Kind и Money. Для чтения обязательны Дата, Тип, Сумма.

`## Корректировки`: `Правило | Исходная дата | Действие | Новая сумма | Новая дата | Заметка`.
Обязательны Правило, Исходная дата, Действие. Собственного adjustment ID нет: OccurrenceKey = (RuleId, originalDate). Это связь с конкретным номинальным событием правила, до сдвига выходных.

| Действие | Вариант модели | Обязательные данные |
|---|---|---|
| пропустить | Adjustment.Skip | Ключ события |
| изменить | Adjustment.ChangeAmount | Ключ и новая сумма |
| перенести | Adjustment.MoveDate | Ключ и новая дата |
| заменить | Adjustment.Replace | Ключ, новая сумма и новая дата |

Изменение даты не меняет ключ события. Связь с правилом логическая: отсутствующее правило не превращается в новую сущность автоматически. Расчёт диагностирует неприменимые корректировки; нет внешнего ключа СУБД или каскадной SQL-операции.

Исходники структур: `core/src/main/java/ru/cashprediction/core/model/Plan.java`, `core/src/main/java/ru/cashprediction/core/model/RecurringRule.java`, `core/src/main/java/ru/cashprediction/core/model/OneTimeTransaction.java`, `core/src/main/java/ru/cashprediction/core/model/Adjustment.java`, `core/src/main/java/ru/cashprediction/core/model/OccurrenceKey.java`.

### 2.4. Идентификаторы, связи и временные индексы

RuleId и TxId - отдельные типы. После strip значение не пусто и не содержит `|` или `@`. Префиксы r и t - соглашение генератора, а не обязательный regex всех вручную заданных ID. Идентификаторы не являются UUID и не глобальны между файлами.

PlanMarkdownReader.assignIds сохраняет первые допустимые уникальные ID отдельно для rules и oneTimes. Пустым, некорректным и повторным ID назначает rN/tN выше найденного максимального числового суффикса, с диагностикой. Самовольное редактирование ID влияет на связи корректировок; автоматическая миграция произвольных ссылок не обещается.

В ForecastEngine.collectAdjustments строится LinkedHashMap<OccurrenceKey, Adjustment>. При повторном ключе последняя корректировка выигрывает, выдаётся предупреждение. Это временный lookup одного расчёта, не сохраняемый индекс. Генератор прогнозных строк и помесячные maps также живут в памяти. PlanRepository.list перечитывает каталог планов; отдельного индексного файла, SQL-каталога и кешируемой таблицы ссылок нет.

### 2.5. Повтор, перенос и точность

Recurrence.Monthly(dayOfMonth 1..31, everyMonths 1..120), Weekly(weekday, everyWeeks 1..52), EveryNDays(days 1..366), Yearly(MonthDay). Канонические примеры: «ежемесячно 5», «каждые 2 месяца 10», «еженедельно пт», «каждые 3 дня», «ежегодно 03-15».

OccurrenceGenerator берёт опору из «С» правила, иначе из начала плана; пересекает границы правила с горизонтом. Месячный день 29..31 прижимается к последнему дню короткого месяца; 02-29 ежегодного повтора в невисокосный год становится 02-28. После изменения начала плана заданная «С» сохраняет фазу многопериодного повтора.

WeekendPolicy относится к субботе/воскресенью: «раньше» сдвигает на пятницу, «позже» на понедельник. К вручную заданной новой дате корректировки повторный сдвиг не применяется. Что-если масштабирует величину после выбора корректировки; знак задаёт Kind.

Money.minor - long минимальных единиц, 100 единиц на денежную единицу; Long.MIN_VALUE запрещён. Дробный double не является типом суммы. Ввод понимает запятую/точку, валютные символы и корректную группировку разрядов; дробь более двух знаков округляется HALF_UP. В колонках операций явный знак снимается через abs с предупреждением: направление определяется Типом. Начальный баланс остаётся знаковым.

PlanValidator требует положительные суммы операций/изменяющих сумму корректировок, проверяет дубликаты ID и интервалы. MAX_AMOUNT = 99 999 999 999 999 минимальных единиц, MAX_ROWS = 200 000; эти пределы отличаются от предела long. Конструкторы модели намеренно не заменяют всю предметную проверку терпимого чтения.

Расчёт сортирует строки по фактической дате, затем доходы перед расходами, origin и порядку генерации. Балансы и агрегаты - производные данные, не дополнительные разделы файла плана. ForecastService получает today/WhatIf/includeSkipped явно; WhatIf не становится постоянной строкой операции при просмотре сценария.

### 2.6. Ручное редактирование, raw blocks и совместимость

Reader нормализует BOM и окончания строк, сравнивает слова грамматики без учёта регистра с нормализацией ё/е. Разделы можно переставлять; таблицы читаются по заголовкам. Пустые ячейки разрешены там, где данные необязательны. RuFormats понимает также одиночный дефис как пустой ввод, но writer не использует его вместо отсутствующего значения.

Неизвестные секции/свободные фрагменты сохраняются RawBlock(afterSection, lines); неизвестные параметры закрепляются служебным anchor §Параметры:список. Writer возвращает фрагменты после соответствующей секции; неизвестный anchor выводит в конце. Неразобранная строка операции становится диагностикой и пометкой в заметке с исходной строкой, не успешно созданной операцией. Неизвестные колонки дают предупреждение и не участвуют в модели; сохранение произвольных значений этих колонок как структурированных полей не обещается. Канонизация не гарантирует побайтовое сохранение форматирования файла.

В формате 2 ячейка кодирует `|` как `\|`, LF как `<br>`, CR как `&#13;`, экранирует буквальные амперсанд и угловые скобки. Декодирование однопроходное. Заметка имеет отдельное экранирование структурно значимых строк, чтобы её заголовок не стал секцией плана.

PlanMarkdownReader.discoverCellCodec заранее сканирует параметры: ровно CashPrediction 2 включает ENCODED_V2, иначе используется LITERAL_V1, где HTML-подобный текст буквальный. Отсутствующий формат читается; более новая версия даёт предупреждение, не общую процедуру миграции. Writer канонизирует результат в формат 2 при сохранении. Это совместимость чтения старых ячеек и rewrite-on-save, не автоматическое изменение файлов при открытии. Совместимость с прежними файлами, использующими U+2013/U+2014 как тире, не является поддерживаемым требованием.

Опорные методы: PlanMarkdownReader.read/assignIds/discoverCellCodec/checkFormat, PlanMarkdownWriter.write, MarkdownTable.parseRow, RuFormats, Money.parse, PlanValidator.validate. Файлы находятся в `core/src/main/java/ru/cashprediction/core/markdown/PlanMarkdownReader.java`, `core/src/main/java/ru/cashprediction/core/markdown/PlanMarkdownWriter.java`, `core/src/main/java/ru/cashprediction/core/markdown/MarkdownTable.java`, `core/src/main/java/ru/cashprediction/core/diagnostics/PlanValidator.java`.

## 3. Чтение и запись плана, настройки

PlanStorage передаёт Reference, Collection и Version как непрозрачные строковые токены. FilePlanStorage кодирует абсолютный нормализованный путь Base64 URL без padding; это локатор, не ACL и не право записи. Версия файлового адаптера объединяет modified time, creation time, size, fileKey и SHA-256 байтов. Core сравнивает токены, не разбирает их.

read возвращает Snapshot(reference, version, plan, diagnostics); optional expectedVersion ограничивает повторное чтение. write/rename требуют expectedVersion. Version.ABSENT означает ожидаемое отсутствие, не wildcard перезаписи. Категории отказов: MISSING, CONFLICT, CORRUPT, IO_ERROR; причины конфликта VERSION_CHANGED и NAME_EXISTS.

FilePlanStorage.write наблюдает версию до подготовки; PlanRepository.save вызывает guard после staging/force и перед публикацией. ExternalChangeGuard хранит пару принятой reference/version; явный конструктор с managedCashMemory отделяет path-policy от типа injected PlanStorage. Оба аргумента remember(reference, version) проверяются до изменения пары.

AtomicFiles подготавливает полный файл cashprediction-tmp-<pid>-<uuid>.md/.xml/.tmp в той же папке, CREATE_NEW, force(true), затем для существующей цели выполняет одну атомарную замену. На неподдерживающей atomic move ФС fallback слабее. Это не strongCAS: после последней проверки сторонний редактор может успешно записать, а replace перезаписать его правку. Scope-check также не удерживает предков от concurrent junction swap. Нельзя заявлять power-loss-proof из force или Runtime.halt.

Rename в другое имя публикует полный новый файл без замены занятой цели, затем повторно проверяет source перед удалением. Check-to-delete всё ещё не CAS. Case-only rename на Windows использует существующий промежуточный .rename.md; его не следует описывать как непрерывную атомарность имени или доказанное автоматическое crash recovery. Подробные сценарии отказа - в [edge-cases.md](edge-cases.md).

SettingsMarkdown хранит последний/недавние пути, выбранное recovery store, autosave, view, period и флаги отображения. «Недавние планы» - список со `;` и экранированным разделителем; это не индекс планов. Reader использует defaults для отсутствующих/неразобранных настроек. Номера схемы, истории undo/redo и migration journal у settings.md нет.

Опорные файлы: `core/src/main/java/ru/cashprediction/core/service/storage/FilePlanStorage.java`, `core/src/main/java/ru/cashprediction/core/io/PlanRepository.java`, `core/src/main/java/ru/cashprediction/core/io/AtomicFiles.java`, `core/src/main/java/ru/cashprediction/core/app/flow/ExternalChangeGuard.java`, `core/src/main/java/ru/cashprediction/core/markdown/SettingsMarkdown.java`.

## 4. Снимки сеанса: общая логическая схема

SessionSnapshot = schemaVersion:int, savedAt:Instant, client:String, main:MainWindowState, plan:PlanState, windows:List<WindowState>. Официальные клиенты fx/swing/web; валидатор client допускает [a-z0-9]{1,32}. Только схема 1 поддержана; версия менее 1 или более CURRENT отклоняется. Общей миграции снимков нет.

SessionMarker отдельно содержит state running/closed, pid:long, startedAt:Instant, client. Маркер запуска/закрытия не является временем снимка. Для обнаружения аварии CrashDetector учитывает состояние и информацию процесса; один совпавший PID не достаточен для вывода о живом владельце.

| Объект | Поля |
|---|---|
| MainWindowState | bounds или null; maximized:boolean; view:String; planPath:String; period:String; filters:Map<String,Boolean>; filterText:String; selectedRowId:String; whatIfExtra:String |
| PlanState | dirty:boolean; markdown:String |
| WindowState | id:String; type:WindowType или null; modal:boolean; ownerId:String; bounds или null; context:Map<String,String>; fields:Map<String,String> |
| WindowBounds | x/y/width/height:double с проверкой допустимости, положительные размеры |

SessionBridge сохраняет путь внутри CashMemory относительным, внешний путь абсолютным. Восстановление resolves путь относительно CashMemory; наличие пути не расширяет write-scope. Dirty-план переносится как Markdown, а не как undo stack; история undo/redo не является частью снимка. Поля форм хранят сырой ввод строками, включая незавершённое/невалидное значение.

view и period - машинные ключи, например TABLE/CHART и M12, не локализованные подписи. SessionBridge пишет имя enum периода; 12m принимается как alias при чтении, но не является каноническим значением writer. selectedRowId адресует строку прогноза, например r2@2026-10-01, а не номер строки Markdown. whatIfExtra - необязательное строковое добавление схемы 1: старые снимки дают пустое значение; writer опускает его, если пусто.

Windows хранятся в порядке регистрации. ownerId = main или ID другого окна; это цепочка владения, не файловый FK. RestoreCoordinator восстанавливает сначала немодальные, затем модальные окна, сохраняя исходный порядок внутри каждой группы; это не топологическая сортировка владельцев. Новые ID связываются со старыми после успешного открытия окна. Если владелец ещё не восстановлен, пропущен или не открылся, owner заменяется на main с предупреждением; произвольная вложенность не гарантируется. Selection восстанавливается после окон. Неизвестный тип окна кодеки читают как null; coordinator пропускает его с предупреждением.

### 4.1. Словарь окон и связей

WindowType задаёт ключи context и raw fields, общий для клиентов. Native FileChooser/DirectoryChooser/JFileChooser в снимок не входят.

| type | Контекст | Основные поля |
|---|---|---|
| NEW_PLAN_WIZARD | page | name, currency, startDate, startBalance, horizonKind/horizonValue/horizonUntil, displayPeriod, cushion, quickIncomeTitle/Amount/Day, quickExpenseTitle/Amount/Day |
| PLAN_SETTINGS | Пустой | name, currency, startDate, startBalance, horizonKind/horizonValue/horizonUntil, cushion, note, goalTitle/Target/Date |
| RULE_EDITOR | mode, ruleId | title, kind, amount, category, recurrenceKind, dayOfMonth, everyN, weekday, monthDay, fromEnabled/from, untilEnabled/until, weekendPolicy, enabled, note |
| ONE_TIME_EDITOR | mode, txId | date, title, kind, amount, category, note |
| ADJUSTMENT_EDITOR | ruleId, originalDate | action, amount, date, note |
| GOAL_CALCULATOR | Пустой | target, byDateEnabled/byDate, extraSaving |
| TEXT_INPUT / CHOICE | purpose | value |
| ALERT | purpose, targetId | Полей ввода нет |
| CSV_EXPORT | Пустой | separator, bom, range |
| QUICK_EDIT_POPUP | ruleId, originalDate | amount |

В таблице сокращённые группы quickIncomeTitle/Amount/Day означают три ключа с общим префиксом quickIncome, не буквальный ключ с slash. Полный словарь - `core/src/main/java/ru/cashprediction/core/session/WindowType.java`. Режим create/edit и purpose определяют фабрику, а не новую разновидность persistent record.

Опорные модели/протоколы: `core/src/main/java/ru/cashprediction/core/session/SessionSnapshot.java`, `core/src/main/java/ru/cashprediction/core/session/MainWindowState.java`, `core/src/main/java/ru/cashprediction/core/session/WindowState.java`, `core/src/main/java/ru/cashprediction/core/session/RestoreCoordinator.java`, `core/src/main/java/ru/cashprediction/core/app/flow/SessionBridge.java`.

## 5. Реестр, XML и Web Markdown

### 5.1. Windows Preferences

Текущий узел: HKCU\\Software\\JavaSoft\\Prefs\\ru\\cashprediction\\session\\<client>-<8 hex SHA-256 пути CashMemory>. RegistrySessionStore.installationHash считает SHA-256 UTF-8 нормализованного пути в нижнем регистре, берёт первые четыре байта. Значение cashmemory.path хранит абсолютный путь владельца.

Явный --registry-node или cashprediction.registry.node задаёт префикс под ru/cashprediction/, но не внутри session; итоговый узел добавляет /client. --registry memory использует in-memory backend, не настоящий HKCU. Legacy overload forClient(client) оставлен для общего session/client узла прежних клиентов; это совместимый API, не автоматическое объединение или миграция старого узла в новый.

JsonSnapshotCodec пишет schemaVersion/savedAt/client/main/plan/windows с вложениями из раздела 4. RegistrySessionStore разбивает JSON на snapshot.N по 4096 единиц UTF-16; snapshot.count и snapshot.length проверяют число/суммарную длину, snapshot.crc32 - CRC32 UTF-8 полного JSON. snapshot.time - маркер commit. Читаемые дубли plan.path/view/windows.count/window.N.type не заменяют JSON.

Протокол save: сначала flushed и проверенный backup.snapshot.N/count/length/crc32/time прежнего снимка; затем transaction.pending = 1 с flush. Primary snapshot.time удаляется, пишутся новые chunks и дубли, time публикуется последним, выполняются flush и readback всего primary. После commit pending удаляется и flush, затем backup очищается. load читает целый primary; при pending и отсутствующем/повреждённом primary читает целый backup. Это логическое old-or-new recovery, не транзакция всего реестра и не межпроцессный writer lock. Первый снимок без предыдущего не имеет old backup.

Опорный код: `core/src/main/java/ru/cashprediction/core/session/store/RegistrySessionStore.java`, `core/src/main/java/ru/cashprediction/core/session/codec/JsonSnapshotCodec.java`.

### 5.2. XML

CashMemory/session-<client>.xml кодируется XmlSnapshotCodec. Корень session: schema="1", client, optional state/pid/startedAt маркера и savedAt при снимке. main хранит x/y/width/height, maximized, view, period, filterText, optional whatIfExtra; вложения plan(path), filters/filter(id,value), selection(rowId). unsavedPlan имеет dirty и Markdown в CDATA. windows/window имеет id/type/modal/owner и границы; context(key,value), field(id,value) сохраняют строки.

Writer экранирует атрибуты, разделяет CDATA при ]]> и CR; недопустимые XML 1.0 codepoints заменяет U+FFFD. DOM reader запрещает DOCTYPE, external entities/DTD; неизвестные элементы/атрибуты игнорируются, но неподдерживаемая схема отклоняется. Это не обещание точного round-trip любого недопустимого XML-символа.

XmlSessionStore сохраняет snapshot вместе с marker атомарной записью одного файла; markDirty/markClean сохраняют имеющийся snapshot. Неисправный файл не заменяется записью одного marker. Оговорки atomic move/fallback/strongCAS из раздела 3 применяются и здесь.

Опорный код: `core/src/main/java/ru/cashprediction/core/session/codec/XmlSnapshotCodec.java`, `core/src/main/java/ru/cashprediction/core/session/store/XmlSessionStore.java`.

### 5.3. Web session

CashMemory/web-session.md: «# Сессия CashPrediction (web)», ключи Состояние, PID сервера, Начата, Сохранено, Схема; секции Главное окно, Открытые окна, при inline-плане Несохранённый план. Main-ключи: Вид, План, Несохранённые изменения, Период, Фильтры, Строка поиска, Выделено, Границы, Развёрнуто, необязательная Что-если, доп. экономия в месяц.

Окно имеет heading вида «### w1 - RULE_EDITOR (модальное, владелец: main)». Первые строки - Контекст и Границы, далее raw fields. MarkdownSnapshotCodec кодирует backslash, LF/CR/tab и значимые разделители; пары фильтров/контекста не являются таблицей БД.

При dirty MarkdownSessionStore пишет план в web-session.plan.md и ссылку «да (web-session.plan.md)»; при clean sidecar удаляется после session commit. Inline-вариант кодека использует fence длиннее серии backticks внутри плана.

Перед изменением sidecar, если имеется прежний снимок, web-session.md.transaction.md получает первую строку из 64 hex SHA-256 нового session-текста, затем полный прежний session с inline-планом. Затем пишется sidecar, потом session, затем cleanup. Если hash primary не совпадает с journal, load автоматически возвращает прежний inline snapshot; если совпадает, читает новый primary и sidecar. Отсутствующий обязательный sidecar - CORRUPT, не пустой план. При первом сохранении без previous снимка journal с old snapshot не создаётся.

Отсутствующий «Схема» в старом Web Markdown принимается как текущая схема; optional whatIfExtra отсутствует без ошибки. Это default совместимости, не новая миграция. JSON-снимок содержит ту же логическую схему; браузерный sessionStorage токен доступа WebServer не является recovery snapshot или базой планов.

Опорный код: `core/src/main/java/ru/cashprediction/core/session/codec/MarkdownSnapshotCodec.java`, `core/src/main/java/ru/cashprediction/core/session/store/MarkdownSessionStore.java`.

### 5.4. Отказы, восстановление и карантин

SessionStoreException.Code: UNSPECIFIED для legacy адаптера, UNAVAILABLE, CORRUPT, IO_ERROR, UNKNOWN_STORE. Ошибки не классифицируются разбором русского сообщения. LocalRecoverySnapshots читает выбранное хранилище, RestoreCoordinator/SessionBridge применяют план/main/windows; кодек сам не открывает GUI и не выполняет команды пользователя.

Перед новой записью поверх corruption CorruptStoreQuarantine сохраняет verified документ CashMemory/Recovery/<store>-<epochMillis>-<uuid>.md через CREATE_NEW/force/readback. Для XML/Markdown архивируются точные исходные bytes, включая sidecar/journal; для registry - логические значения как UTF-16BE code units. Документ включает длину, SHA-256, Base64 и ограниченный preview. Это не экспорт hive и не SQL backup. Повторная проверка source предшествует мутации; отказ карантина запрещает её. Recovery notice появляется после подтверждённого нового сохранения; карантин не является автоматической миграцией повреждённых данных в валидный Plan.

## 6. Обновления: manifest, delta и staging

Схема обновления строго отделена от пользовательских данных. Инвентарь managed payload разрешает три exe, app/ и runtime/. CashMemory в обновляемый инвентарь не входит; download, staging, journals, helpers, backups находятся в CashMemory/Updates. Установка заменяет управляемые файлы приложения по отдельному протоколу, не через разрешение save внешнего MD.

### 6.1. update.json и инвентарь

release.json не заменяет update.json: текущий producer .github/scripts/S7-Prepare.ps1 пишет schemaVersion=1, releaseNumber, commitSha, publishedAtUtc, assetName, sizeBytes, sha256, note. Это метаданные опубликованного portable ZIP без tree/files/delta inventory. .github/scripts/S7-Release.ps1 проверяет identity и архив; legacy release.json без инвентаря не становится полноценным manifest схемы 2. Названия существующих scripts с S7 не означают отдельного действующего этапа S7. Автоматической миграции release.json в update.json при чтении нет.

UpdateCodec.read требует точный набор ключей, неизвестные/отсутствующие ключи отклоняет. Целые числа передаются JSON integers без дробной/экспоненциальной подстановки.

| Поле manifest схемы 2 | Тип/ограничение |
|---|---|
| schemaVersion | integer, ровно 2 |
| releaseNumber | Положительный int |
| commitSha | 40 lowercase hex |
| version | Непустая строка |
| publishedAtUtc | Instant |
| assetName | CashPrediction-portable.zip |
| sizeBytes | Положительный long, до 512 MiB |
| sha256 | 64 lowercase hex контейнера |
| treeSha256 | 64 lowercase hex инвентаря |
| files | Отсортированный список FileEntry |
| deltaPatches | Не более двух прямых баз |

FileEntry = path:String, sizeBytes:long, sha256:String, readOnly:boolean. Пути relative с /, NFC, без .., пустых сегментов, Windows reserved names, ADS, управляющих символов, завершающих точек/пробелов. Коллизии регистра, file/directory и дубликаты запрещены. Инвентарь отсортирован по unsigned UTF-8 bytes путей; максимум 20 000 файлов, отдельный файл до 512 MiB, сумма дерева до 2 GiB, JSON до 8 MiB.

TreeDeltaEngine.treeHash: SHA-256 от UTF-8 «cashprediction-tree-v1» + NUL, затем для каждого отсортированного FileEntry длина пути int32 big-endian, UTF-8 path, size int64 big-endian, 32 bytes SHA-256, один byte readOnly 0/1. Это контроль идентичности дерева, не подпись автора и не индекс поиска.

Delta descriptor: algorithm = cashprediction-tree-delta, algorithmVersion = 1, baseReleaseNumber, baseCommitSha, baseTreeSha256, assetName, sizeBytes, sha256. assetName = CashPrediction.cpdelta или CashPrediction.from-<baseReleaseNumber>.cpdelta; база старше target, базы/имена уникальны. InstalledVersion - releaseNumber/commitSha/treeSha256, а не только показанный version.

Опорный код: `core/src/main/java/ru/cashprediction/core/update/model/UpdateCodec.java`, `core/src/main/java/ru/cashprediction/core/update/model/UpdateValidation.java`, `core/src/main/java/ru/cashprediction/core/update/tree/TreeDeltaEngine.java`.

### 6.2. .cpdelta и полный ZIP

.cpdelta - ZIP с patch.json и только payload/<managed-path> для добавленных/изменённых файлов. patch.json: schemaVersion=1, algorithm, algorithmVersion=1, baseReleaseNumber/baseCommitSha/baseTreeSha256, targetReleaseNumber/targetCommitSha/targetTreeSha256, payloadFiles. payloadFiles имеет форму FileEntry. Отдельного списка SQL DELETE/операционных записей для каждого удаления нет: итоговое дерево строится по target.files, исчезнувшие файлы базы в него не копируются.

TreeDeltaEngine.apply проверяет фактическую базу, контейнер, точный набор payload, размеры/hash/атрибуты, затем полное итоговое дерево. Это файловая прямая дельта, не бинарный diff произвольных байтов и не цепочка миграций между релизами. Полный ZIP использует корень CashPrediction/ и проверяемый managed inventory; произвольные файлы пользователя не устанавливаются в CashMemory из payload.

### 6.3. Ready publication

UpdatePreparer/ReadyStore работают под prepare.lock. Staging/tree содержит подготовленное дерево, Staging/update.json - target manifest. Перед swap данные force, итоговый tree verified; prepare-journal.json хранит сам target manifest схемы 2, не отдельную произвольно выдуманную journal-схему.

Publication: прежний Ready перемещается в PreviousReady, Staging - в Ready, затем cleanup. recover проверяет Ready, journal-bound Staging и PreviousReady, выбирает подтверждённый кандидат/backup и завершает уборку. Два directory rename - протокол recovery готового update tree; они не используются как новая замена atomic-save пользовательского плана.

ReadyStore.requirePortableInventory требует непустые три exe, три app/*.cfg, app/.jpackage.xml, runtime/bin/jli.dll, runtime/bin/server/jvm.dll, runtime/lib/modules. Наличие папки Ready само по себе не подтверждает готовность. Сетевой fallback delta/full и ошибки проверок рассматривает [edge-cases.md](edge-cases.md).

Опорный код: `core/src/main/java/ru/cashprediction/core/update/net/UpdatePreparer.java`, `core/src/main/java/ru/cashprediction/core/update/net/ReadyStore.java`.

## 7. Установка: журнал и process identity

InstallJournal.prepare сохраняет schemaVersion=1, installationRoot, transactionId(UUID), target(manifest схемы 2), phase, oldFiles(FileEntry[]), oldTreeSha256, operations[], outcome. preparePortable задаёт schemaVersion=2 и добавляет bootstrap. Reader принимает именно схемы 1/2 и точный набор ключей; схемы не преобразуются в одну при чтении.

phase: PREPARED, WAITING, BOOTSTRAPPING, BACKING_UP, INSTALLING, VERIFYING, COMMITTED, ROLLING_BACK. outcome: PENDING, UPDATED, ROLLED_BACK. UPDATED допустим с COMMITTED, ROLLED_BACK с ROLLING_BACK; один phase без проверенного результата не является PASS установки.

operations элемент: kind, path, state=BEFORE/AFTER. Схема 1: BACKUP/INSTALL/UNINSTALL/RESTORE. Схема 2 также BOOT_COPY/BACKUP_COPY/REDIRECT/REPLACE/RESTORE_REPLACE/CFG_SWITCH. Путь связывается с oldFiles или target.files согласно kind; journal проверяет UUID, root, tree hashes и допустимость каждой операции.

bootstrap схемы 2: schemaVersion=1, state=INITIAL/COPYING/COPIED/ACTIVE/RESTORED/CLEANED, publishState=NONE/BEFORE/AFTER, redirectFiles(FileEntry[] трёх cfg), cfgTexts[{path,text}]. Точные UTF-8 bytes cfgTexts должны соответствовать размерам/hash redirectFiles. Временный runtime/bootstrap помещается под Updates/Bootstrap; подмена произвольного runtime-пути в cfg не разрешается. План дополняется bootstrap owner metadata с root/transactionId, а не глобальной записью установки.

PowerShellHelper - независимый helper, который исполняет журнал и откат; журнал записывается до запуска helper. Backup хранит управляемые прежние файлы. После терминального результата active journal переносится в completed-journal.json. last-install.json содержит schemaVersion=1, transactionId, outcome, targetCommitSha; записанный ROLLED_BACK не позволяет немедленно повторить тот же target при обычном scheduling. Completed journal проверяется как терминальный, но отдельно требуется проверка фактического installed/rollback tree.

ProcessLease пишет processes/<leaseId>.json: schemaVersion=1, leaseId UUID, pid, startedAtEpochMillis, installationRoot, client. Lease относится к реальной JVM, не только родителю jpackage; остаётся после close до реального завершения. PID вместе с birth time препятствует принятию повторно использованного PID за прежний процесс.

RestartRequest пишет requests/<requestId>.json: schemaVersion=1, transactionId, requestId, client, args. client выбирает строго один из трёх launcher. safeArgs переносит --home, только Web --no-browser/--no-window и --updated-from <targetSha>; произвольные параметры не сохраняются. Запрос привязан к transactionId, не исполняет произвольный command из JSON.

Prepare/install locks, журналы и ожидание процессов координируют собственный update workflow. Они не решают strongCAS произвольного внешнего редактора MD и не доказывают общий crash/power-loss контракт NTFS. Все native-проверки остаются отдельной приёмкой.

Опорные файлы: `core/src/main/java/ru/cashprediction/core/update/install/InstallJournal.java`, `core/src/main/java/ru/cashprediction/core/update/install/InstallCoordinator.java`, `core/src/main/java/ru/cashprediction/core/update/install/PortableBootstrap.java`, `core/src/main/java/ru/cashprediction/core/update/install/PowerShellHelper.java`, `core/src/main/java/ru/cashprediction/core/update/install/ProcessLease.java`, `core/src/main/java/ru/cashprediction/core/update/install/RestartRequest.java`.

## 8. Проверка изменения формата

Изменение слова в format.properties меняет грамматику сохранённых документов, не только текст UI. Для изменения схемы необходимо отдельно решить поведение старого reader, нового writer и raw/manual workflow; наличие enum/константы не выполняет миграцию само.

Существующие точки regression-проверки: PlanMarkdownRoundTripTest, PlanMarkdownToleranceTest, PlanMarkdownMultilineNotesTest, MarkdownTableTest, SnapshotSchemaAdditionsTest, RegistrySessionStoreTest, RegistrySessionStoreNodeTest, MarkdownSessionStoreTest, UpdateCodecTest, TreeDeltaEngineTest. Их наличие здесь - навигация по коду, не утверждение о текущем зелёном запуске.

В рамках подготовки этого документа выполнена сверка исходников/ресурса, без Maven, GUI, native или пересборки поставки. Упаковка source .7z и проверка исходников из архива выполняются отдельным владельцем после координации MAIN.


## 9. Контракты данных служб и Web API

Этот срез описывает поля текущих объектов и wire JSON, не новые файлы CashMemory и не сетевое выделение SOA-служб. Семантика исполнения/владения - в [architecture.md](architecture.md); область дедупликации запросов и сценарии устаревания/отказов - в [edge-cases.md](edge-cases.md). Полного API-протокола здесь нет.

### 9.1. Команды плана

| Record | Поля и типы |
|---|---|
| PlanCommandRequest | requestId:UUID, expectedRevision:long, description:String, command:PlanCommand |
| PlanCommandSnapshot | revision:long, plan:Plan, undoDescription:Optional<String>, redoDescription:Optional<String> |
| PlanCommandResult | requestId:UUID, status:Status, snapshot:PlanCommandSnapshot, effect:PlanCommandEffect, problems:List<PlanCommandProblem> |
| PlanCommandEffect | reconciliationDifference:Money, removedAdjustments:int; NONE = Money.ZERO/0 |
| PlanCommandProblem | code:PlanCommandError, ruleId:RuleId или null, transactionId:TxId или null, occurrenceKey:OccurrenceKey или null, arguments:Map<String,String>, message:String |

requestId/description/command обязательны; конструктор оболочки не выполняет всю проверку бизнес-значений. Snapshot хранит защитно переданный неизменяемый Plan; Optional описания означает доступность undo/redo, не сериализованный stack истории. Результат копирует problems; адрес проблемы может отсутствовать. arguments - неизменяемая карта машинных данных, message - отдельное локализованное объяснение.

Status: APPLIED - изменение принято; UNCHANGED - равный план; REJECTED - отказ; PREVIEW - проверенный проект без записи. accepted() истинно для всех, кроме REJECTED; changed() - только APPLIED. Snapshot результата - итоговый/проектный для принятого запроса, исходный для отказа. NOTIFICATION_FAILED может сопровождать уже принятое изменение и не означает rollback.

PlanCommand - sealed vocabulary с типизированными payload: AddRule/ReplaceRule(RecurringRule), RemoveRule(RuleId), AddOneTime/ReplaceOneTime(OneTimeTransaction), RemoveOneTime(TxId), PutAdjustment(Adjustment), RemoveAdjustment(OccurrenceKey), UpdateSettings(Settings), SetGoal(Goal, допускает null), SetCurrency(String), SetHorizon(Horizon), RenamePlan(String), Actualize(LocalDate, Money balanceOverride), Reconcile(LocalDate, Money actualBalance), ApplyWhatIf(WhatIf, LocalDate today), Cleanup(LocalDate today, WhatIf, boolean showSkipped), Undo/Redo без полей. Settings содержит name/note/currency:String, startDate:LocalDate, startBalance:Money, horizon:Horizon, cushion:Money, goal:Goal или null. Это не callbacks, UI-сеансы или текстовый JSON parser сумм.

PlanCommandError: STALE_REVISION, REQUEST_ID_REUSED, COMMAND_IN_PROGRESS, INVALID_COMMAND, NOT_FOUND, DUPLICATE_ID, INVALID_AMOUNT, INVALID_RECURRENCE, INVALID_ADJUSTMENT, INVALID_PLAN, CALCULATION_FAILED, NOTIFICATION_FAILED. Код не выводится из message. STALE_REVISION несёт expectedRevision/actualRevision в arguments.

Источники: `core/src/main/java/ru/cashprediction/core/service/plan/PlanCommand.java`, `core/src/main/java/ru/cashprediction/core/service/plan/PlanCommandRequest.java`, `core/src/main/java/ru/cashprediction/core/service/plan/PlanCommandSnapshot.java`, `core/src/main/java/ru/cashprediction/core/service/plan/PlanCommandResult.java`, `core/src/main/java/ru/cashprediction/core/service/plan/PlanCommandProblem.java`, `core/src/main/java/ru/cashprediction/core/service/plan/PlanCommandError.java`. Fixtures: `core/src/test/java/ru/cashprediction/core/service/plan/LocalPlanCommandsTest.java`, `core/src/test/java/ru/cashprediction/core/service/ServiceBoundaryContractTest.java`.

### 9.2. PlanStorage DTO

Раздел 3 описывает файловую реализацию; следующие формы относятся к контракту любого backend, без Path/атрибутов в DTO.

| Record | Поля и типы |
|---|---|
| Reference / Collection / Version | token:String, non-null; только Version имеет ABSENT |
| Entry | reference:Reference, version:Version, name:String, modifiedAt:Instant |
| Snapshot | reference:Reference, version:Version, plan:Plan, diagnostics:List<Diagnostic> |
| Stored | reference:Reference, version:Version |
| Problem | code:Code, conflict:Conflict, detail:String, null detail нормализуется в пустую строку |
| Result<T> | value:T или null, problem:Problem или null; ровно одна ветвь non-null |

list(Collection) возвращает Result<List<Entry>>, version(Reference) - Result<Version>, read(Reference, LocalDate today, Optional<Version> expectedVersion) - Result<Snapshot>, write(Reference, Plan, Version expectedVersion) и rename(Reference, String name, Version expectedVersion) - Result<Stored>. Квитанция Stored может содержать новую reference после rename, без повторного чтения Plan.

Code = MISSING/CONFLICT/CORRUPT/IO_ERROR, Conflict = NONE/VERSION_CHANGED/NAME_EXISTS. Конструктор Problem требует code=CONFLICT тогда и только тогда, когда conflict!=NONE. Result.succeeded() проверяет отсутствие problem; requireValue() при отказе бросает PlanStorageException с той же структурированной проблемой. Списки диагностики копируются; «непрозрачный token» не означает filesystem CAS, авторизацию пути или существование сетевого DTO-кодека.

Источник: `core/src/main/java/ru/cashprediction/core/service/storage/PlanStorage.java`. Fixtures: `core/src/test/java/ru/cashprediction/core/service/storage/FilePlanStorageTest.java`, `core/src/test/java/ru/cashprediction/core/app/file/FileFlowStorageTest.java`.

### 9.3. Запрос и результат прогноза

ForecastRequest = plan:Plan, whatIf:WhatIf, today:LocalDate, includeSkipped:boolean. plan/today обязательны; null whatIf заменяется WhatIf.NONE. WhatIf = incomeFactor:BigDecimal, expenseFactor:BigDecimal, extraMonthlySaving:Money. Алгоритмическая версия берётся из ForecastService.algorithmVersion(), не из request.revision.

Отдельного класса/record ForecastResult в текущем коде нет: ForecastService.calculate(ForecastRequest) возвращает `Forecast`.

| Record результата | Поля и типы |
|---|---|
| Forecast | plan:Plan, whatIf:WhatIf, today/anchor/dailyStart:LocalDate, rows:List<ForecastRow>, dailyBalance:long[], summary:ForecastSummary, warnings:List<Warning> |
| ForecastRow | date/originalDate:LocalDate, title/category/note:String, kind:Kind, amount/balanceAfter:Money, origin:Origin, ruleId:RuleId или null, txId:TxId или null, flags:Flags |
| Flags | shifted/amountChanged/moved/skipped/past/whatIf:boolean |
| ForecastSummary | startBalance/endBalance/totalIncome/totalExpense/averageMonthlyNet/minBalance:Money, anchor/minBalanceDate:LocalDate, balanceAfterMonths:Map<Integer,Money>, firstNegativeDate/firstBelowCushionDate/goalReachDate:Optional<LocalDate>, byMonth:Map<YearMonth,MonthTotals> |
| MonthTotals | income/expense/net/closingBalance:Money |

dailyBalance содержит minor units начиная с dailyStart; массив копируется на входе и при accessor. rows/warnings/maps защищены копиями, не являются записанными индексами. Отсутствующая дата достижения цели представлена Optional.empty(), не вымышленной датой или одиночным дефисом.

Отказ передаётся исключением ForecastFailure, не Result со status: LIMIT_EXCEEDED, AMOUNT_OVERFLOW, AMOUNT_OUT_OF_RANGE, DATE_RANGE_EXCEEDED. Соответствующие типы сохраняют семьи IllegalStateException, ArithmeticException, IllegalArgumentException, DateTimeException, message и cause. Null-request как programming error не объявляется новым видом предметной ошибки.

Источники: `core/src/main/java/ru/cashprediction/core/forecast/service/ForecastRequest.java`, `core/src/main/java/ru/cashprediction/core/forecast/service/ForecastService.java`, `core/src/main/java/ru/cashprediction/core/forecast/service/ForecastFailure.java`, `core/src/main/java/ru/cashprediction/core/forecast/Forecast.java`, `core/src/main/java/ru/cashprediction/core/forecast/ForecastRow.java`, `core/src/main/java/ru/cashprediction/core/forecast/ForecastSummary.java`. Fixture: `core/src/test/java/ru/cashprediction/core/forecast/service/EngineForecastServiceTest.java`.

### 9.4. UiJson: формы данных и маршруты

UiJson.toTree/write сериализует record-поля, String/Boolean/конечные Number, коллекции/maps; LocalDate/YearMonth/Path - строки, enum - name(), CommandId - его id(). WebIntent/WebQuery получают discriminator type, ContextTarget - kind. Этот codec не сериализует методы/callbacks служб и не превращает локальные PlanCommands в HTTP API.

| Маршрут | Форма запроса / ответа |
|---|---|
| GET /api/ui/bootstrap?tab=<uuid> | WebBootstrap: seq:long, client:String, testApi:boolean, profile:ClientProfile, screen:MainScreenModel, hotkeys:List<HotkeyBinding>, windows:List<WebEffect>, overlay:String или null, texts:Map<String,String>; транспорт может дополнить reconnect metadata |
| GET /api/ui/events?tab=<uuid>&after=<seq> | Оболочка {seq, effects:[{seq,type,...payload}]} или {resync:true}, если курсор вытеснен |
| POST /api/ui/intent | {tab:String, afterSeq:long, intent:{type,...поля}}; ответ - та же оболочка эффектов |
| POST /api/ui/query | {type,...поля WebQuery}; {result:<данные или null>} или {stale:true,rev:<actual>} |

WebQuery варианты: contextMenu(target:ContextTarget); tooltip(rev:long,index:int,columnId:String); rows(rev:long,from:int,count:int, максимум 300); chartScene(rev:long,w/h:double); chartHover(rev:long,x/y/w/h:double); dayCard(date:LocalDate); sparkline(cardId:String); calendar(month:YearMonth,selected:LocalDate или null). rev/from/count/index валидируются nonnegative; размеры графика отдельно проверяются codec. Проверка stale относится к текущей table/chart revision, не к версии плана на диске.

Типичные WebIntent формы: command(command:CommandId,args:CommandArgs,source:InvokeSource), key(chord:KeyChord,scope:FocusScope,focusId:String), selectRow(rowId:String), activateRow(rowId/columnId:String,how:Activation), formField(windowId/fieldId/raw:String,committed:boolean,clientRev:long), formButton(windowId/buttonId:String), alertButton(alertId/buttonId:String). JSON type alertButton соответствует record WebIntent.AlertAnswer, chartScene - WebQuery.Chart. command.args содержит rowId/date/cardId/key/value; это не PlanCommandRequest и не его UUID/revision. Полный словарь остаётся в WebIntent/WebQuery.

MainScreenModel: revision:long, windowTitle:String, menuBar:MenuBarModel, toolbar:ToolbarModel, summary:SummaryModel, table:TableModel, chart:ChartModel, status:StatusModel, mode:ViewMode. В wire TableModel содержит revision, columns, rowCount, selectedRowId, scrollToRowId, placeholder без строк/accessor; ChartModel - только revision. Строки/сцена запрашиваются отдельно, не включаются скрыто в bootstrap.

FormView: revision:long, page:int, header:String, fields:Map<String,FieldView>, problem:Problem, buttons:Map<String,ButtonView>, results:List<ResultLine>, preview:List<PreviewItem>, details:String, detailsExpanded:boolean. WebEffect form.view передаёт windowId/view/echoOf{tab,clientRev}; screen передаёт revision/parts. Общая event-оболочка добавляет seq/type; сама вложенная WebEffect в bootstrap не получает отдельный seq.

UiJson.readIntent/readQuery отвергают неизвестный discriminator, неверные типы/диапазоны через IllegalArgumentException; HTTP UiApi переводит невалидный запрос в 400 {error}, timeout/interruption в 503 {busy:true}, неожиданный отказ в 500 {error}; security/route ApiException имеет собственный HTTP status. Устаревший запрос данных - отдельный {stale:true,rev}, не PlanCommandError.STALE_REVISION и не storage CONFLICT. Формат сообщения UI не является машинной классификацией ошибки.

Источники codec/DTO: `core/src/main/java/ru/cashprediction/core/ui/json/UiJson.java`, `core/src/main/java/ru/cashprediction/core/ui/json/WebBootstrap.java`, `core/src/main/java/ru/cashprediction/core/ui/json/WebIntent.java`, `core/src/main/java/ru/cashprediction/core/ui/json/WebQuery.java`, `core/src/main/java/ru/cashprediction/core/ui/json/WebEffect.java`. Транспортные callsites: `web/src/main/java/ru/cashprediction/web/ui/UiApi.java`, `web/src/main/java/ru/cashprediction/web/ui/EffectLog.java`. Fixtures: `core/src/test/java/ru/cashprediction/core/ui/json/UiJsonFixturesTest.java`, `web/src/test/java/ru/cashprediction/web/ui/UiApiTest.java`.

### 9.5. Разные виды revision

| Значение | Область и смысл |
|---|---|
| PlanCommandSnapshot.revision / request.expectedRevision | Предметное состояние одного LocalPlanCommands; растёт на EventKind.PLAN, включая undo/redo/load, не на сохранении или перерисовке |
| PlanStorage.Version | Непрозрачная версия backend для конкретной reference; не счётчик команд и не гарантия filesystem strongCAS |
| MainScreenModel/TableModel/ChartModel.revision | Модель экрана/данных текущего controller; используется для query stale |
| FormView.revision | Последовательность построенных моделей одной формы |
| WebIntent.FormField.clientRev | Номер правки клиента; echoOf связывает ответ с tab и правкой, не является серверным expectedRevision |
| seq / afterSeq / after | Курсор доставки эффектов EffectLog; не предметная ревизия и не отметка сохранения сеанса |

Эти числа/токены нельзя подставлять друг вместо друга. SnapshotSchema, FORMAT_VERSION и algorithmVersion тоже не являются ревизиями документа. Пределы повторов/дедупликации и межвкладочные гонки остаются в [edge-cases.md](edge-cases.md); данный раздел не вводит persistent request ledger, новый API schemaVersion или обещание exactly-once.
