# Технологический стек, сборка и проверка CashPrediction

Документ описывает текущие POM, Java module descriptors и скрипты, а не результаты их исполнения. Наличие команды, теста или runner не означает зелёную сборку, завершение S4/S5 либо нативное одобрение. Команды ниже - справочник для согласованного запуска после завершения изменений; занятый общий reactor и GUI-стенд нельзя запускать параллельно без координации.

Общие границы приложения описаны в [architecture.md](architecture.md), данные и форматы - в [db-schema.md](db-schema.md), отказы и ограничения - в [edge-cases.md](edge-cases.md), интерфейс - в [ui-kit.md](ui-kit.md), связи компонентов - в [linx.md](linx.md). Эти шесть русских технических документов образуют комплект документации исходников. Рабочие CurrentSprint, ContextDump, ChangeRequest и LegacyWarning из `docs/ai` не входят в этот комплект и архив; ссылки на них для сборки не нужны.

## 1. Версии и разрешённые зависимости

Источник версий - [родительский POM](../../pom.xml); зависимости и конфигурация исполнения уточняются POM модулей. Это закреплённые значения проекта, не утверждение о последних доступных версиях.

| Компонент | Текущее значение и назначение |
| --- | --- |
| Java | `maven.compiler.release=25`, без preview; UTF-8 для исходников и ресурсов. Полный JDK нужен разработчику и сборщику. Точная patch-версия JDK в POM не закреплена. |
| OpenJFX | `25.0.4`, зависимость `org.openjfx:javafx-controls` только у `ui-fx`; base/graphics приходят транзитивно. Для dist скачиваются Windows x64 jmods той же версии с Gluon. |
| JUnit | BOM `5.14.4`, `junit-jupiter` только в области `test`. В продукт не входит. |
| Maven | Требование `3.9+` указано в `dist/scripts/Test-Pack-Source.ps1`; точная версия Maven в root POM не закреплена. |
| Версия Maven-артефактов | `ru.cashprediction`, parent и модули `1.0.0`; это не номер пользовательского релиза. |
| Номер поставки | `app.release`, по умолчанию `0`; `app.commit`, по умолчанию `local`. Фильтрация ресурса core `app.properties` передаёт значения в `AppInfo`; development build имеет release 0. Для versioned-образа требуются положительный release и проверенный commit. Windows exe получает `1.0.${app.release}`. |

Номер публикуемого релиза определяется историей `main`, а не версией JDK/OpenJFX или Maven-артефакта. Положительные локальные метаданные тестового образа сами по себе не доказывают связь с опубликованным коммитом.

Библиотеки продукта ограничены JDK и OpenJFX. Swing/AWT, HTTP-клиент, HTTP-сервер, XML, Preferences и ZIP - API JDK. JSON/Markdown разбираются кодом проекта. Web использует обычные HTML/CSS/JavaScript, без npm, CDN и внешнего framework. SQL/СУБД, Storybook, CoreUI, сторонние JSON/XML/HTTP/ORM-библиотеки не добавляются. Название `db-schema.md` относится к реальной схеме файлов и снимков, а не к существующей SQL-базе.

Сборочные Maven plugins не являются runtime-зависимостями продукта. В parent pluginManagement закреплены:

| Плагины | Версии |
| --- | --- |
| `maven-clean-plugin`, `maven-resources-plugin` | `3.5.0`, `3.5.0` |
| `maven-compiler-plugin`, `maven-surefire-plugin` | `3.16.0`, `3.6.0` |
| `maven-jar-plugin`, `maven-install-plugin` | `3.5.1`, `3.1.4` |
| `maven-dependency-plugin` | `3.11.0` |
| `exec-maven-plugin` | `3.6.4` |
| `javafx-maven-plugin` | `0.0.8` |
| `download-maven-plugin` | `2.1.0` |

## 2. Модули и граница toolkit

Default reactor содержит `core`, `update-tool`, `ui-fx`, `ui-swing`, `web`, `repository-doc-audits`. Профиль `dist` добавляет сборочный модуль `dist`; `ui-tests` и `e2e` добавляют тестовый `ui-parity`.

| Модуль | Явные `requires` в основном `module-info.java`, кроме неявного `java.base` |
| --- | --- |
| `ru.cashprediction.core` | `java.net.http`, `java.prefs`, `java.xml`; без `java.desktop` и JavaFX. Общие модели интерфейса и поведение живут здесь, но toolkit renderer сюда не входит. |
| `ru.cashprediction.fx` | core, `javafx.controls`, `java.logging`; пакет main экспортирован квалифицированно для `javafx.graphics`. |
| `ru.cashprediction.swing` | core, `java.desktop`, `java.logging`. |
| `ru.cashprediction.web` | core, `jdk.httpserver`, `java.desktop`, `java.logging`. Desktop нужен серверному launcher для браузера/окна статуса, а не браузерному JavaScript. |
| `ru.cashprediction.updatetool` | Только core. CLI подготовки обновлений, не четвёртый пользовательский клиент; dist не включает его в четыре app-JAR. |

Descriptors расположены в `core`, `ui-fx`, `ui-swing`, `web`, `update-tool` под `src/main/java/module-info.java`. Дополнительные `add-reads` в testCompile core разрешают тестам compiler API, локальный `jdk.httpserver` и Windows fault handle через `jdk.unsupported`; это не расширяет production descriptor.

Surefire намеренно запускает JUnit на classpath (`useModulePath=false`). Поэтому `Class.getModule().isNamed()` в обычном JUnit не доказывает нарушение архитектуры. Toolkit-границу проверяют по собранному descriptor, реальному графу зависимостей и семантике DTO; отдельная JPMS-фикстура требует явно подготовленного module-path. Успех classpath-теста не заменяет проверку модульного запуска.

## 3. Инструменты разработчика и среда пользователя

Разработчику нужны полный JDK 25 и Maven; portable собирается под Windows x64. CI workflows используют `windows-latest` и Microsoft JDK `25`, без закрепления patch-версии. Для поставки исходников и новых update builders нужен PowerShell 7; часть dist-шагов явно вызывает `powershell.exe`. 7-Zip нужен для `.7z`, но не приложению. Git нужен для истории релизов и Git-provenance builder, не для обычной сборки распакованного source.

UI/parity/e2e требуют интерактивного Windows-стола, Edge и подготовленных модулей/тестового стенда. CDP/HTTP harness реализован средствами JDK; это инструмент проверки, не зависимость web-клиента. Headless проверки нельзя выдавать за реальную отрисовку или работу exe.

Пользователь portable получает встроенный сокращённый runtime и три launcher. Отдельные JDK, Maven, 7-Zip, WiX и установка Java ему не нужны для app-image. Сокращённый runtime не равен полному JDK сборщика и не подходит вместо него для Java source-file публикации helper. Параметр `Runtime` у native runner запускает сервер стенда; пользовательские клиенты запускаются настоящими exe, не этим `java.exe`.

## 4. Сборка и dev-запуск

Команды из корня исходников выполняются отдельно от занятых сборок и нативных gate:

```powershell
mvn -B install
mvn -pl ui-fx javafx:run
mvn -pl ui-swing exec:exec
mvn -pl web exec:exec
mvn -B -Pdist -DskipTests package
```

`install` собирает default reactor и запускает его тесты; не подключает автоматически `ui-parity` или dist. Он устанавливает межмодульные зависимости для отдельных dev-запусков. `cashprediction.dev.home` по умолчанию указывает на корень проекта, где создаётся `CashMemory`. FX запускается по module/class; Swing и Web dev-команды из своих POM используют classpath. Portable launchers используют модули.

`-DskipTests` пропускает исполнение тестов, а не удостоверяет их успех. Dist удаляет свой предыдущий output на prepare-package: не собирать поверх используемого gate-образа. Конвейер в [dist/pom.xml](../../dist/pom.xml): JavaFX jmods, копирование четырёх модульных JAR, `jlink`, `jpackage --type app-image`, нормализация module-path, Windows UTF-8 manifests, ZIP. Выход: `dist/target/dist/CashPrediction` и `dist/target/dist/CashPrediction-portable.zip`.

`jlink` получает только JDK/OpenJFX jmods. Его восемь корней: `javafx.controls`, `java.desktop`, `java.logging`, `java.prefs`, `java.xml`, `jdk.httpserver`, `jdk.localedata`, `java.net.http`; зависимости включаются транзитивно. `--include-locales=ru,en` сохраняет данные форматирования, но не добавляет переключатель языка интерфейса. `--strip-native-commands` не используется: нужны команды runtime и DLL JavaFX.

Код приложения не включается в `runtime/lib/modules`. Четыре JAR core/FX/Swing/Web передаются jpackage через `--module-path`, без `--input`, затем `Normalize-AppModules.ps1` переносит их из временного `app/mods` непосредственно в `app/`. Все три cfg должны иметь пару `--module-path`, `$APPDIR`, без `app.classpath`; `app/mods` в конечном образе отсутствует. `Set-LauncherUtf8.ps1` меняет встроенные Windows manifests трёх exe и image `java.exe`/`javaw.exe`, не установленный JDK.

Три точки запуска: `CashPrediction.exe`, `CashPrediction-Swing.exe`, `CashPrediction-Web.exe`; дополнительные настройки находятся в `dist/launchers/{swing,web}.properties`. JVM-флаги dist ограничивают служебные записи crash/perf/replay и задают русскую локаль; они не доказывают отсутствие любых внешних записей. Jpackage launcher может создавать дочернюю JVM: при остановке проверяемой копии учитывается всё дерево её процессов.

## 5. Что проверяет каждая команда

Новая политика пользователя (04.10.2026): автоматический GitHub CI/CD должен оставаться лёгким (подготовка/компиляция и ограниченные проверки); тяжёлые полные UI/E2E/portable и остальные обязательные acceptance checks выполняются локально либо явно вручную в GitHub с `full_checks=true`. Перечень команд ниже описывает возможности и необходимые проверки, а не обязательный автоматический запуск всех команд на каждый push/PR. Неизвестная affected-база расширяет лёгкий scope fail-closed; docs-only сохраняет аудит документов. Новые workflows подключены в рамках S4; удалённый результат проверяется отдельно. Ни smoke, ни compile, ни unit PASS не доказывают полную приёмку/нативное исполнение. Для публикации сохраняются свежий AppInfo, совпадение source/artifact SHA, S7_APPROVED_SHA и реальные применимые gates одного кандидата.

```powershell
mvn -B -pl core "-Dtest=ru.cashprediction.core.service.plan.LocalPlanCommandsTest,ru.cashprediction.core.service.storage.FilePlanStorageTest,ru.cashprediction.core.forecast.service.EngineForecastServiceTest" test
mvn -B -Pui-tests verify
mvn -B -Pe2e verify
powershell -NoProfile -ExecutionPolicy Bypass -File .github/scripts/Test-Portable.ps1 -PortableDir dist/target/dist/CashPrediction
pwsh -NoProfile -File dist/scripts/Test-Pack-Source.ps1 -VerifyBuild
```

Первая команда - пример affected-unit среза, не полная проверка core. UI-профили подключают один `ui-parity`; его `*Test` и `*IT` выполняются Surefire, без отдельного Failsafe. Требуемые образы, зависимости и интерактивная среда подготавливаются до запуска. Реальные golden-файлы, ресурсы сценариев и legacy fixtures сохраняются, а не заменяются mock-результатами.

`Test-Portable.ps1` копирует образ в три класса Unicode-путей, запускает настоящие exe, проверяет старт, файлы снимков, web-ответ и закрытие API без токена, сравнивает инвентарь копии. Инвентарь не обнаруживает записи вне проверяемого дерева. Локально запускать без `-CleanRegistry`; текущий совместимый флаг CI тоже разрешает удаление только собственных `ru/cashprediction/selftest/<UUID>`, не production session-узлов. `-Parity` дополнительно требует PowerShell 7, заранее собранный `ui-parity` и `java.net.http` в bundled runtime; проверяет реальные exe, s02/goldens и crash/restore. Входные классы и эталоны должны быть заморожены; этот runner не собирает их Maven/javac.

После dist отдельно сверяют четыре app-JAR, их SHA-256, три cfg и versioned AppInfo из исполняемого core-JAR. Из каталога готового образа `runtime/bin/java.exe --list-modules` без module-path не должен показывать модули приложения; `runtime/bin/java.exe --module-path app --describe-module ru.cashprediction.core` должен находить внешний JAR. Для того же runtime и module-path три реальные точки `-m`: `ru.cashprediction.fx/ru.cashprediction.fx.FxMain`, `ru.cashprediction.swing/ru.cashprediction.swing.SwingMain`, `ru.cashprediction.web/ru.cashprediction.web.WebMain`. Добавление `--dry-run` проверяет разрешение и загрузку main, не запуск exe, UI или восстановление после частичной установки. Unit fixtures контрактов raw/PNG/commit отрисовки не заменяют настоящий `WidgetCapture`, стабильный кадр и независимо проверенные растровые эталоны.

## 6. Обновления: сборщик, подготовка, исполнение

Скрипты находятся в `.github/scripts` и входят в source-поставку. Имена некоторых файлов/комментариев исторически содержат S7; это не отдельный этап текущего плана: тихое обновление и соответствие требованиям относятся к S5, после S4 остаются S5 и S6.

Пути входов абсолютные, канонические, без ссылок/reparse и пересечений с output. Предварительные SHA-256 закрепляют уже проверенные неизменяемые файлы, а не произвольные новые значения после подмены. Не перезаписывать существующие output; незавершённые результаты и logs сохраняются для диагностики. Нативный запуск требует отдельного согласования рабочего стола и копий.

Тестовый manifest задаётся свойством JVM `cashprediction.update.selftest.manifest`. [UpdateLifecycle.selftestManifest](../../core/src/main/java/ru/cashprediction/core/update/lifecycle/UpdateLifecycle.java) принимает только `http://127.0.0.1:<port>/update.json` с явно заданным портом 1..65535, без userInfo, query и fragment. Одновременно обязательны `testApi=true` (`--test-api`), абсолютный нормализованный `home`, равный `installationRoot.toAbsolutePath()`, и registryNode формы `ru/cashprediction/selftest/<UUID>` со строчными hex и группами 8-4-4-4-12. Проверяется точная строка свойства и URI, не произвольный localhost alias. Это вход изолированного стенда, не пользовательская настройка endpoint и не снятие versioned Windows-условий создания updater; поведение отказов описано в [edge-cases.md](edge-cases.md).

| Шаг | Обязательные входы и предел результата |
| --- | --- |
| `New-UpdateCandidateImages.ps1` | `SourceSnapshot` из `Pack-Source.ps1 -StageOnly` без target; новый `OutputRoot` под Temp с UUID; `Maven`, `JdkHome`; три строго возрастающих положительных `ReleaseNumber`; либо один 40-hex `Commit`, либо три различных `CommitByRelease` B1/B2/T. Строит три dist-образа одного закреплённого source с пропуском тестов. Receipt: `BUILT`, native matrix `PENDING`; локальные SHA-метаданные не являются Git/publication provenance. Общий Commit не удовлетворяет bootstrap-требованию отличия commit баз от target. |
| `New-GitUpdateCandidateImages.ps1` | `Repository`, `Git`, три `CommitIds` B1/B2/T с ancestor-цепочкой и возрастающими counts, новый `OutputRoot`, `PackSourceSha256`, `CandidateValidatorSha256`. Для `-Build` также `Maven`, `JdkHome`. По умолчанию только подготовка source; явный Build делает install/dist в отдельных Maven repos/settings. Проверяет Git blobs/деревья, не создаёт commit/tag/checkout и не публикует релиз. Native одобрения не даёт. |
| `New-UpdateBootstrapCommands.ps1` | Одна-две `PortableDir`, более новая `TargetPortableDir`; полный JDK `Runtime` (bin/java.exe), `RuntimeSha256`, `JdkModulesSha256`; `CoreJar`/`CoreJarSha256`, `ToolJar`/`ToolJarSha256`. Core JAR побайтно совпадает с целевым образом. Необязательный `OutputDirectory` - новый прямой Temp/run-UUID; `CommandTimeoutSeconds` 10-300. Настоящие CLI inventory/manifest/verify и Java source-file вызов `PowerShellHelper.publish` создают full ZIP и закреплённый CommandFile. Возвращает `RunnerParameters`, `PREPARED`, native matrix `PENDING`; install helper и клиенты не запускаются. |
| `New-NativeUpdateArtifacts.ps1` | `CommandFile`/`CommandFileSha256`, `Runtime`, ровно две `PortableDir` B1/B2, `TargetPortableDir`, новый прямой Temp/UUID `OutputDir`. CLI готовит и проверяет две прямые дельты и full fallback ZIP, публикует update.json и новый согласованный CommandFile. `PREPARED`, native status `PENDING`; исходные inputs не заменяет. |
| `New-NativeUpdateLifecycleConfig.ps1` | `ArtifactDir` ровно с update.json, full ZIP и двумя прямыми дельтами; `ExpectedManifestSha256`; 1-4 готовых JAR/каталога `HarnessClasspath` с NativeUpdateServer; новый `OutputFile` в прямом Temp/UUID либо под dist/target. Закрепляет каждый harness-файл, возвращает `{path, sha256}`; процессов не запускает. Размер/digest контейнеров не заменяет их семантическую проверку core/server. |
| `Test-UpdateBootstrap.ps1` | `PortableDir`, `TargetPortableDir`, `Runtime`, `CommandFile`/`CommandFileSha256`; отдельные checkpoint/cold timeouts, `MaxCells`, необязательный `CellKey`. Проверяет cold-запуск обычных exe после остановки helper; не строит Java и не заменяет recovery ручным запуском helper. |
| `Test-NativeUpdateLifecycle.ps1` | Две независимые базы `PortableDir`, target, `Runtime`, согласованные `CommandFile`/`CommandFileSha256` и `LifecycleFile`/`LifecycleFileSha256`. Запускает NativeUpdateServer и настоящие versioned jpackage exe в изолированных копиях. Selection: `Scenario`, `CellKey`, `MaxCells` 1-612 (default 144), `StepTimeoutSeconds` 30-300 (default 180). Незадействованные клетки остаются PENDING. |

Bootstrap builder создаёт манифест без дельт. Его `RunnerParameters` подходят cold runner, но lifecycle требует отдельной подготовки двух прямых дельт и нового согласованного CommandFile. Manifest SHA в CommandFile, ArtifactDir и LifecycleFile должен совпадать. Harness предварительно компилируется; JShell не используется для публикации helper, чтобы не обращаться к общему Preferences-хранилищу.

Короткая последовательность с заранее проверенными наборами параметров, не готовый запуск без inputs:

```powershell
$prepared = & .github/scripts/New-UpdateBootstrapCommands.ps1 @bootstrapInputs
$coldInputs = $prepared.RunnerParameters
& .github/scripts/Test-UpdateBootstrap.ps1 @coldInputs
$artifactInputs.CommandFile = $prepared.CommandFile
$artifactInputs.CommandFileSha256 = $prepared.CommandFileSha256
$artifacts = & .github/scripts/New-NativeUpdateArtifacts.ps1 @artifactInputs
$lifecycleInputs.ArtifactDir = $artifacts.artifactDir
$lifecycleInputs.ExpectedManifestSha256 = $artifacts.manifestSha256
$lifecycle = & .github/scripts/New-NativeUpdateLifecycleConfig.ps1 @lifecycleInputs
$nativeInputs.CommandFile = $artifacts.commandFile
$nativeInputs.CommandFileSha256 = $artifacts.commandFileSha256
$nativeInputs.LifecycleFile = $lifecycle.path
$nativeInputs.LifecycleFileSha256 = $lifecycle.sha256
& .github/scripts/Test-NativeUpdateLifecycle.ps1 @nativeInputs
```

`artifactInputs` закрепляет command из `$prepared`; `lifecycleInputs` использует `$artifacts.artifactDir` и `$artifacts.manifestSha256`. `nativeInputs` использует НОВЫЕ `$artifacts.commandFile`/`commandFileSha256` и `$lifecycle.path`/`sha256`, а не старый command. Остальные обязательные поля из таблицы заполняются отдельно; синтаксис splatting не создаёт эти данные автоматически.

Текущий dispatcher описывает 22 сценария и 612 клеток, включая durable fault-фазы; это размер плана, не число успешных native запусков. Default Scenario выбирает только восемь download/normal-close сценариев. Дополнительные `-CollectAcceptance`, `AcceptanceContextFile`/`AcceptanceContextSha256` требуют независимых закреплённых доказательств. Advanced receipts сами по себе не превращают PENDING в PASS. `-Signoff` требует всех 612 реально исполненных PASS, `SignoffRequestFile`/`SignoffRequestSha256`, совпадающих command/lifecycle pins и seal текущего results в batches. Частичный выбор, helper-only или mock не заменяет эти условия. Runner не удостоверяет DOM/скриншоты.

В исходниках `PortableBootstrap` и `BootstrapScript` сохраняют старые runtime и четыре app-JAR в `CashMemory/Updates/Bootstrap`, проверяют старый inventory и перенаправляют cfg до замены дерева. Это механизм, не доказанная отказоустойчивость. Нужны cold-запуски после остановки helper на границах подготовки/публикации bootstrap, cfg, BACKING_UP, INSTALLING и rollback. Разрешение модулей полной версии не доказывает запуск частично установленной копии. При одинаковых JDK/OpenJFX runtime может не меняться, но фактическая дельта определяется хешами; её малый размер не гарантируется.

## 7. Source .7z и конечная доставка

Текущая политика [Pack-Source.ps1](../../dist/scripts/Pack-Source.ps1) включает ровно шесть технических файлов `docs/design/{architecture,techstack,edge-cases,db-schema,linx,ui-kit}.md`: `Test-IncludedFile` разрешает эти пути, `Assert-DeliveredSource` требует их наличия в staging. Остальная документация `docs` не поставляется. Ветка `docs/ai` исключается до обхода; CurrentSprint/ContextDump/ChangeRequest/LegacyWarning запрещены по имени независимо от расположения и расширения, включая ресурсы. Агентские инструкции/истории, scratch, результаты сборок, секреты и workflows также остаются вне архива. Ресурсы справки и реальные тестовые ресурсы, лицензии, исходники модулей приложения, update-tool/ui-parity/dist и необходимые PowerShell-скрипты сохраняются.

[Test-Pack-Source.ps1](../../dist/scripts/Test-Pack-Source.ps1) содержит проверки whitelist шести документов и исключения AI-контекста; [Test-FinalDelivery.ps1](../../dist/scripts/Test-FinalDelivery.ps1) использует текущую политику для конечного архива. Это факты исходного кода, не доказательство успешного исполнения тестов или состава уже созданного .7z. `Pack-Source.ps1 -StageOnly` возвращает `StageDirectory`, `OutputPath`, `SevenZipPath`, `FileCount`; создание staging не удостоверяет сборку, native acceptance или конечную доставку.

Упаковщик исключает `repository-doc-audits` и удаляет только его запись из staged POM; root POM не меняется, остальные модули/профили сохраняются. `.github/scripts` поставляется, `.github/workflows` нет. Распакованный проект должен собираться без отсутствующих рабочих документов и агентских файлов. Во время упаковки source заморожен; существующие staging/archive не перезаписываются, ссылки/junction отвергаются.

```powershell
pwsh -NoProfile -File dist/scripts/Pack-Source.ps1 -OutputPath C:/Delivery/CashPrediction-source.7z
pwsh -NoProfile -File dist/scripts/Test-FinalDelivery.ps1 -DeliveryRoot C:/Delivery -VerifyBuild
```

Папка `C:/Delivery` в примере должна уже содержать готовый `CashPrediction` и новый `CashPrediction-source.7z`. `Test-FinalDelivery.ps1` не создаёт и не исправляет их; отсутствие означает FAILED. Он использует 7-Zip, извлекает архив в own Temp, сохраняет receipts/inventories в новом EvidenceRoot; `-VerifyBuild` дополнительно выполняет полный install именно извлечённого дерева с отдельным Maven repository. Можно закрепить `ExpectedArchiveSha256` и `ExpectedPortableInventorySha256`, явно задать `SevenZipPath`/`MavenPath`.

`Test-Pack-Source.ps1 -VerifyBuild` проверяет состав и сборку временно распакованных исходников; `-FiltersOnly` проверяет только предикаты, `-FixturesOnly` - искусственный staging/archive. Это разные уровни доказательств, не native PASS. Конечный artifact, source build, portable smoke, UI/parity, crash recovery и update sign-off проверяются отдельно. Документ не заменяет receipts этих проверок и не объявляет этапы завершёнными.
