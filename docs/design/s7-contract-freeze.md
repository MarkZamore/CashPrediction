# S5: контракт реализации тихого обновления

04.10.2026: отдельный S7 объединён с S5. Соответствие требованиям, UX и обновления принимаются в S5; независимая финальная проверка выполняется в S6. Независимые задачи S5 разрешены параллельно S4 без изменения проверяемого кандидата. Исторические имена S7 scripts/receipts не обозначают отдельный этап и не отменяют проверок этого контракта.

03.10.2026: пользователь разрешил начать реализацию S7 параллельно с S4-S6.
Это отменяет прежний запрет на начало кода до S6, но не отменяет финальную
приёмку, проверку всех клиентов или запрет публикации непроверенного обновления.
Текущий прогон S4 использует замороженные jar; агентам запрещены Maven и GUI.

## Владение и API

Ведущий владеет pom.xml, всеми module-info.java, этим контрактом и интеграцией.
Владельцы не правят чужие пакеты и не создают пустые успешные заглушки.

- W1: core.update.model и core.update.tree, их тесты. Публичные records:
  FileEntry(path, sizeBytes, sha256, readOnly),
  DeltaPatch(baseReleaseNumber, baseCommitSha, baseTreeSha256, assetName, sizeBytes, sha256),
  InstalledVersion(releaseNumber, commitSha, treeSha256),
  UpdateManifest(releaseNumber, commitSha, version, publishedAtUtc, assetName,
  sizeBytes, sha256, treeSha256, files, deltaPatches).
  Номера int, размеры long, опубликованное время Instant, списки List.
  UpdateCodec.read(String) и write(UpdateManifest) в model.
  TreeDeltaEngine.inventory(Path): List<FileEntry>;
  treeHash(List<FileEntry>): String;
  verify(Path, List<FileEntry>, String): void;
  create(Path baseRoot, InstalledVersion base, Path targetRoot,
  UpdateManifest target, Path output): void;
  apply(Path baseRoot, InstalledVersion base, Path patch,
  UpdateManifest target, Path destination): void;
  extractFull(Path zip, UpdateManifest target, Path destination): void.
  IOException на неверные входы; destination должен отсутствовать.
- W2: core.update.net и тесты: UpdatePreparer(Path installationRoot,
  InstalledVersion current, URI manifestUri), prepare(): boolean, close(): void.
  AutoCloseable, один проход на объект; Ready означает проверенную готовую версию.
  Test-only constructor seam для loopback, тайм-аутов и транспорта допустим;
  производственная URI фиксирована, никакого пользовательского override.
- W3: core.update.install и core.update.lifecycle, их тесты:
  UpdateLifecycle.create(Path installationRoot, Path cashMemory,
  String client, String[] args), beforeUi(): boolean,
  afterUiReady(): void, close(): void. boolean false означает завершить этот
  запуск после передачи безопасного запроса на запуск установщику; true - открыть UI.
  AutoCloseable, методы никогда не показывают UI. Общий lifecycle, не три копии.
- W4: update-tool, только JDK и core. CLI inventory/create/apply/verify/manifest.
  Явные именованные аргументы, ненулевой exit при ошибке; README CLI не нужен
  в source-доставке, поэтому help и Javadoc должны описывать контракт.
- W5: три клиентских адаптера, пока только отдельный draft patch, без изменения
  текущих клиентов до завершения прогона S4. Поведение остаётся в UpdateLifecycle.
- W6: новые S7 release scripts, .github/workflows/release.yml и dist integration
  draft; не менять существующие S4/S5 scripts и не публиковать GitHub assets.
  Все публичные операции только через Invoke-Gh. Проверочные mocks обязательны.

## Байты и схемы

CLI freeze для W4/W6:
inventory --root <dir> --out <json> пишет {files,treeSha256};
create --base <dir> --base-release <n> --base-commit <sha> --target <dir>
--manifest <update.json> --out <cpdelta>;
apply --base <dir> --base-release <n> --base-commit <sha> --patch <cpdelta>
--manifest <update.json> --out <new-dir>;
verify --root <dir> --manifest <update.json>;
manifest --root <dir> --archive <zip> --release <n> --commit <sha>
--version <text> --published-at <ISO-Instant> --out <update.json>
[--delta <descriptor.json> максимум два раза]. Descriptor совпадает с DeltaPatch
wire schema; CLI вычисляет sha/size полного zip и дерева, не доверяет caller hash.
create вычисляет base tree, не принимает готовый неподтверждённый digest.
JSON stdout допустим только для результата; technical errors в stderr и exit !=0.

Пути - корректный UTF-8, NFC, разделитель /; сортировка по unsigned UTF-8 bytes.
Не нормализовать опасные входы в безопасные: отвергать их. Запрещены пустые
сегменты, . и .., абсолютные/UNC/drive пути, обратный слеш, двоеточие/ADS,
управляющие символы, Windows reserved characters/names, trailing dot/space,
ссылки и reparse points, Unicode/case collisions через NFC + upper(Locale.ROOT).
Существующие ancestors проверяются без следования ссылкам. Управляются только
CashPrediction.exe, CashPrediction-Swing.exe, CashPrediction-Web.exe, app/** и
runtime/**. Посторонние файлы корня и весь CashMemory сохраняются.

Tree SHA-256: UTF-8 bytes строки cashprediction-tree-v1 и один NUL, далее для
каждого отсортированного FileEntry: 4-byte big-endian длина UTF-8 path, path bytes,
8-byte big-endian size, 32 сырых байта SHA-256 и один байт readOnly (0/1).
Независимый тестовый вектор обязателен; timestamps не входят в digest.

update.json - схема 2 из update-protocol.md, максимум две дельты.
algorithm=cashprediction-tree-delta и algorithmVersion=1 пишутся для каждой.
patch.json: schemaVersion=1, algorithm, algorithmVersion,
baseReleaseNumber/baseCommitSha/baseTreeSha256,
targetReleaseNumber/targetCommitSha/targetTreeSha256, payloadFiles (FileEntry[]).
Payload ZIP entries: payload/<path>. Только изменённые/добавленные файлы;
readOnly-only изменение может переносить прежний content с целевым атрибутом.
Удалённые отсутствуют в target.files. ZIP entries и JSON keys без дублей,
неизвестная схема/алгоритм отвергаются. Unix symlink external attributes также
отвергаются: JDK ZipEntry не является достаточной проверкой ссылок.
Full ZIP имеет один корень CashPrediction/; другие корни/посторонние entries
отвергаются. Контейнеры <=512 MiB, expanded tree <=2 GiB, файл <=512 MiB,
manifest/patch JSON <=8 MiB, files <=20000. ZIP64 для этой версии не допускается.
Архивы детерминированы: порядок и фиксированный timestamp, без локального времени.

## Сеть, Ready и жизненный цикл

Production URI: https://github.com/MarkZamore/CashPrediction/releases/latest/download/update.json.
Не читать arbitrary asset URL из манифеста: assetName один безопасный компонент.
До трёх попыток проверки за один старт, timeout20s и backoff1/2s.
Redirects <=5, HTTPS only, allowlist github.com,
release-assets.githubusercontent.com и objects.githubusercontent.com.
HTTP loopback разрешён только явно внедрённым тестовым seam, не обычной CLI.
java.net.http - модуль JDK, не новая библиотека. Нет polling, кнопок или endpoint.
Полная проверка контейнера/дерева перед Ready и повторно перед установкой.
Все временные данные в installationRoot/CashMemory/Updates, отменённая загрузка
не Ready и удаляется на закрытии. Отдельный FileChannel lock для подготовки.
Ready/PreviousReady/staging одного тома; отдельные atomic rename плюс durable
журнал, потому что multi-directory swap НЕ атомарная транзакция.

Lease JSON schema1: leaseId UUID, pid long, startedAtEpochMillis long,
installationRoot canonical string, client fx/swing/web. PID без birth time
не даёт права считать процесс владельцем; неизвестный живой процесс блокирует
замену безопасно. Регистрировать себя под lock до UI и любых install decisions.
Journal schema1: installationRoot, transactionId UUID, target UpdateManifest,
phase PREPARED/WAITING/BACKING_UP/INSTALLING/VERIFYING/COMMITTED/ROLLING_BACK,
operations с path и durable состоянием BEFORE/AFTER каждого move.
Restart request schema1: transactionId, requestId UUID, client, args String[].
Все metadata/write+force+atomic rename, строгие лимиты/пути/отказ чужому корню.
Нельзя доверять путям из журнала: восстановление только собственных managed paths.

Нормальное закрытие не перезапускает. Предстартовое восстановление возвращает
тот же client. Safe args: --home только исходный installationRoot, --no-browser
и --no-window для Web; служебный --updated-from с ожидаемым target SHA. Остальные
флаги (selftest/test-api/registry-node/произвольные paths) не переносить в обычный
релизный перезапуск. Разработчик release0/без commit40hex полностью inert.
Служебный markdown log использует технические ASCII codes и структурированные
записи без секретов/полных URL tokens; это не UI и не повод hardcode русских текстов.

## Неподменяемые доказательства

Весь approved S7 test matrix сохраняется. Защитить три живых клиента, чужие
процессы, пользовательские данные, все фазы rollback и оба базовых патча.
Особое открытое требование: после kill помощника в фазе перемещения runtime
новый обычный exe должен иметь рабочий recovery bootstrap. Нельзя объявлять
Java beforeUi достаточным, если jpackage не может загрузить JVM. W3/W6 обязаны
дать реализуемое решение или явно сообщить блокирующий дефект, не скрывать его.
Никакой worker не изменяет технологический стек или требования для получения PASS.
