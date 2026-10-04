# W4: исходная реализация сборочного CLI S7

Дата: 2026-10-03. Это передача исходников, не результат сборки или тестового прогона.

Реализованы `inventory`, `create`, `apply`, `verify`, `manifest` с именованными
аргументами из `docs/design/s7-contract-freeze.md`. Точка входа:
`ru.cashprediction.updatetool.UpdateTool`. Предлагаемый модуль:
`ru.cashprediction.updatetool`. Справка встроена в CLI и Javadoc; отдельного README нет.

## Файлы

- `src/main/java/ru/cashprediction/updatetool/UpdateTool.java`: пять команд,
  строгие параметры, schema-2 дескрипторы, вычисление фактической базы и full ZIP
  digest, проверка полного пакета относительно исходного managed tree.
- `src/main/java/ru/cashprediction/updatetool/ToolFiles.java`: UTF-8 и NFC,
  безопасные host paths, запрет links/reparse ancestors, запрет output/input
  overlap с case folding, ограниченный потоковый SHA-256, публикация без
  перезаписи и очистка собственных временных данных.
- `src/test/java/ru/cashprediction/updatetool/UpdateToolTest.java`: 16 тестов CLI.
- `src/test/java/ru/cashprediction/updatetool/ToolFilesTest.java`: 5 тестов
  путей, links, UTF-8 и лимитов ввода.
- `build-integration.patch`: предлагаемый POM сборочного модуля с core и
  test-only JUnit; указания для reactor и экспортов core.
- `../.claude/scratch/s7-tool-module-info.patch`: запрошенный отдельный draft
  именованного модуля. Настоящие pom.xml/module-info.java W4 не изменял.

## API и поведение

По прочитанным рабочим исходникам W1 доступны все шесть records/codec/tree
контрактов из freeze: FileEntry, DeltaPatch, InstalledVersion, UpdateManifest,
UpdateCodec.read/write, TreeDeltaEngine.inventory/treeHash/verify/create/apply/extractFull.
Публичные методы кодека и движка статические; сигнатуры соответствуют вызовам CLI.
Ведущий уже добавил root reactor entry и настоящий module-info инструмента,
а также requires java.net.http и экспорты update-пакетов из core. W4 эти файлы
не перезаписывал. Это сверка исходников, а не проверка доступности через module path.

Схема JSON и алгоритм принадлежат core: CLI не вводит собственный wire format
patch.json, digest дерева или ZIP-engine. Для `--delta` JSON сначала включается
в манифест и проходит тот же UpdateCodec.read, что runtime. У descriptor нет
параметра пути к контейнеру: команда manifest проверяет его схему и идентичности,
но не проверяет bytes самого delta-файла. Это делает apply с базой и контейнером;
релизная интеграция должна выполнять оба round-trip до публикации.

Коды завершения: 0 - выполнено/help, 2 - синтаксис CLI, 1 - данные/IO.
У рабочих команд stdout пустой, результаты находятся в явном `--out`.
Ошибки stderr содержат только ASCII-коды и тип исключения, без локализованных
сообщений ядра или входного содержимого. Full asset берётся из имени архива;
UpdateCodec требует CashPrediction-portable.zip.

Output обязан отсутствовать, его parent уже должен существовать. CLI отвергает
пересечение output с базой, целевым деревом и другими входами. Windows drive
path допустим как host argument; правила относительных archive paths остаются
в core. Unsafe paths не приводятся к безопасному виду. Уже проверенные
существующие ancestors канонизируются для сравнения short-name aliases.
Временные файлы размещаются рядом с output в уникальном `.cp-tool-*`.
JSON и patch принудительно flush-ятся перед move. Публикация использует move
без REPLACE_EXISTING; это не заявление о crash-durable транзакции или гарантии
atomic move на любом файловом провайдере.

## Авторские fixtures, не запущенные случаи

Два различных дерева A/release11 и B/release12 дают один C/release13; fixture
проверяет bytes, удаление obsolete, added/empty/Unicode files, все три exe,
runtime, отсутствие переноса CashMemory и сохранение bytes пользовательских
данных обеих баз. Отдельно заданы детерминированность JSON/patch при смене
timestamp и metadata-only readOnly изменение с проверкой payload и результата.

Негативные случаи: неизвестные/повторные/пропущенные аргументы, третий --delta,
неверные номер/SHA/Instant, существующий output, overlap, отсутствие parent,
traversal/ADS/reserved names/non-NFC/case collision, неверная база,
повреждённый SHA контейнера, корректно перехешированные patch с traversal,
неверной схемой, повторным ключом, чужим target SHA или изменённым payload
того же размера, несовпадающий full ZIP, посторонний root/CashMemory entry,
Unix symlink external attributes, ZIP64 marker, malformed UTF-8,
8 MiB JSON и 512 MiB container boundaries, non-forward release.

Тест symlink требует возможности создания ссылки на хосте; при отсутствии
привилегии он явно abort-ится. ReadOnly fixture требует DOS attribute view.
Ни один из этих случаев не выполнялся; статусы PASS не выставлены.

## Проверено чтением и оставшиеся разрывы

Прочитаны AGENTS.md, freeze, весь update-protocol.md и s7-lanminecraft-map.md;
также actual LANMinecraft Program/Patch/Program.cs, DeltaPatchTool.csproj,
release.yml round-trip/descriptor участок и UpdateChainIdentityTests.cs.
CLI использует JDK/core, не переносит BsDiff или .NET зависимости LAN.

До добавления настоящего module-info ведущим `git apply --check` для обоих
предложенных patch-файлов завершился без диагностик. Повторно module-info patch
применять не нужно. Поиск запрещённых тире и trailing whitespace в собственных файлах
совпадений не обнаружил. Это только статические проверки текста.

Оставшийся разрыв интеграции на момент последнего чтения: update-tool/pom.xml
ещё отсутствует. Ведущий владеет этим файлом; готовый текст находится в
build-integration.patch. Reactor и настоящий module-info уже подключены.
После POM нужно согласовать module/main с W6 invocation, реально собрать
и выполнить тесты и обе base round-trip проверки, когда владельцы будут согласованы.
Компиляция, Maven, GUI, Node, commit/push/tag и network-release writes не
выполнялись. S4/S5/S6 файлы W4 не изменял. Живая session67649 не затрагивалась.

Не доказаны запуск именованного модуля, выполнение fixtures, junction/short-name
поведение реальной NTFS и crash/fault поведение публикации CLI. Проверка
ancestors с NOFOLLOW_LINKS не является доказательством защиты от злонамеренной
конкурентной подмены всего дерева между проверкой и IO. Full updater recovery,
журнал, runtime bootstrap и публикация assets относятся к другим владельцам;
этот CLI не подменяет их приёмку.
