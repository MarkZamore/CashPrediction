# Навигация по технической документации

Этот индекс связывает документацию CashPrediction с исходниками и официальными справочниками используемых технологий. Он не является отчётом о прохождении сборки, S4/S5 или нативной приёмки. Версии и ссылки сверены 2026-10-04; источником версий зависимостей остаётся [родительский POM](../../pom.xml).

## Шесть документов поставки

Технический комплект исходного `.7z` состоит из этих шести русских документов. Ссылки относительные: они должны работать и после извлечения архива, без локальных путей разработчика.

| Документ | Что искать |
| --- | --- |
| [architecture.md](architecture.md) | Границы core и трёх клиентов, потоки управления, контракты и упаковка |
| [techstack.md](techstack.md) | Стек, версии, ограничения зависимостей и инструменты сборки |
| [edge-cases.md](edge-cases.md) | Ошибочные входы, пограничные состояния, восстановление и проверки отказов |
| [db-schema.md](db-schema.md) | Фактические файловые форматы и снимки сеанса; название не означает наличие SQL-БД |
| [linx.md](linx.md) | Этот навигационный индекс и официальные источники |
| [ui-kit.md](ui-kit.md) | Общие модели, тексты и визуальные контракты JavaFX/Swing/Web |

Состав комплекта - правило поставки, а не утверждение, что архив уже собран или проверен. Перед доставкой проверяют наличие всех шести файлов и разрешение ссылок в staged source. Старую политику «только architecture.md» применять нельзя.

## Маршруты по типу задачи

| Задача | Минимальный контекст | Затем открыть код |
| --- | --- | --- |
| Компонент интерфейса | [UI-kit](ui-kit.md) + [архитектура](architecture.md) | Модель в core, общий каталог текстов, отрисовщики всех трёх клиентов |
| API или контракт обмена | [Архитектура](architecture.md) + [схема данных](db-schema.md) | Java-интерфейс, JSON codec/HTTP route, вызывающий код и тесты контракта |
| Изменение формата / миграция | [Схема данных](db-schema.md) + [технологический стек](techstack.md) | Reader, writer, нормализация и round-trip тест; наличие маршрута не обещает автоматической миграции |
| Ошибка / регрессия | [Пограничные случаи](edge-cases.md) + соответствующий контракт выше | Реальный вызов, отказ/восстановление, минимальный regression test |

Исключение для работы в полном репозитории: MAIN ведёт `CurrentSprint`, `ContextDump`, `ChangeRequest`, `LegacyWarning` как рабочий AI-контекст. При разборе ошибки там дополнительно читают актуальный `ChangeRequest`. Эти материалы не входят в source `.7z`; здесь намеренно нет Markdown-ссылок на них и зависимости человеческих маршрутов от них. Для владельца исходного архива достаточно технических документов и кода. Индекс не заменяет отсутствующий контекст выдуманной историей изменений.

## Официальные справочники и версии

Закрепление версии справочника не равно закреплению всех бинарников CI. POM задаёт версии библиотек, а окружение конкретной сборки фиксируется отдельно в её receipt. Не использовать `latest` вместо документации нужной версии и не объявлять локальную версию обязательной для всех runner.

| Технология | Реальная привязка проекта | Официальный источник и назначение |
| --- | --- | --- |
| JDK / Java SE | `maven.compiler.release=25`, без preview; локальный JDK при сверке 25.0.2 | [Java SE/JDK 25 API](https://docs.oracle.com/en/java/javase/25/docs/api/index.html): NIO, Swing/AWT, Preferences, XML, HTTP client/server |
| OpenJFX | `javafx.version=25.0.4`; те же jmods запрашивает dist | [JavaFX 25 API](https://openjfx.io/javadoc/25/): controls, layout, события и FX thread. Справочник закреплён по major 25, не отдельная patch-страница 25.0.4 |
| JUnit | `junit.version=5.14.4`, BOM; только тесты | [JUnit 5.14.4 API](https://docs.junit.org/5.14.4/api/): Jupiter assertions, fixtures и Platform. Наличие API не доказывает исполнение тестов |
| Maven | Скрипт проверки поставки требует 3.9+; локальная установленная дистрибуция при сверке 3.9.16 | [Maven 3.9.16 reference](https://maven.apache.org/ref/3.9.16/): POM, settings, lifecycle и CLI; версии plugins отдельно перечислены в POM |
| jlink | Инструмент того JDK 25, который выполняет dist package | [jlink 25](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jlink.html): состав встроенного runtime и module path |
| jpackage | Инструмент того же JDK 25; dist использует `--type app-image` | [jpackage 25](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html): runtime image, launchers и `--app-version`. Это portable app-image, не обещание установщика MSI |
| PowerShell для tooling | Скрипты с `#requires -Version 7.0`; локальный `pwsh` при сверке 7.6.5 | [PowerShell, представление 7.6](https://learn.microsoft.com/en-us/powershell/scripting/overview?view=powershell-7.6): scripting/runtime. Minor справочника 7.6 не повышает минимальное требование scripts |
| Windows PowerShell | dist вызывает `powershell.exe`; update helper использует системный Windows PowerShell | [Windows PowerShell 5.1 / powershell.exe](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_powershell_exe?view=powershell-5.1): запуск процесса и аргументы; не смешивать с `pwsh` 7 |
| GitHub CLI | Локальный `gh --version` при сверке: 2.98.0; версия hosted runner может отличаться | [Официальный release CLI 2.98.0](https://github.com/cli/cli/releases/tag/v2.98.0) закрепляет версию; [gh api](https://cli.github.com/manual/gh_api) объясняет CLI и headers, но является обновляемой справкой |
| GitHub REST / Actions | Облачный сервис, единой установленной версии нет | [Releases REST, reference 2022-11-28](https://docs.github.com/en/rest/releases/releases?apiVersion=2022-11-28), [правила API versions](https://docs.github.com/en/rest/about-the-rest-api/api-versions), [workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax). API version выбирается header запроса; версия в ссылке не доказывает, что код посылает этот header. Actions docs обновляемые, не versioned snapshot |

В приложении используются только JDK, OpenJFX в FX-клиенте и JUnit в тестах; web написан на обычных HTML/CSS/JS без CDN и npm. SQL, Storybook и CoreUI не являются частями проекта. Maven, PowerShell и GitHub CLI - инструменты сборки/проверки, не новые runtime-библиотеки приложения.

### Receipt проверки внешних URL

Дата проверки: 2026-10-04. Выполнены прямые `open` запросы purpose-built web-инструмента к официальным страницам, без поисковых snippets. Ниже зафиксировано содержание ответов браузера, а не результат `Test-Path` или предположение по структуре URL. Все 13 внешних URL из таблицы выше вернули читаемый документ:

| Страницы из таблицы | Подтверждение direct open |
| --- | --- |
| Java SE/JDK API 25 | Заголовок `Version 25 API Specification` |
| jpackage 25 и jlink 25 | Документы `The jpackage Command` и `The jlink Command` по путям `/javase/25/` |
| OpenJFX API 25 | Документ `Overview (JavaFX 25)`; это major API, не отдельный patch snapshot 25.0.4 |
| JUnit API 5.14.4 | Заголовок `JUnit 5.14.4 API`; точная версия в URL и документе |
| Maven reference 3.9.16 | Официальный reference `Introduction - Apache Maven`, путь `/ref/3.9.16/`; не latest |
| PowerShell overview 7.6 и Windows PowerShell exe 5.1 | Прочитаны `What is PowerShell?` с `view=powershell-7.6` и `about_PowerShell_exe` с `view=powershell-5.1`; overview содержит баннер об авторизации, но тело документа доступно |
| GitHub CLI release 2.98.0 и gh api manual | В release прочитан заголовок `GitHub CLI 2.98.0`; отдельно прочитан официальный manual `gh api`. Manual не является замороженной копией release 2.98.0 |
| GitHub Releases REST, API versions и Actions workflow syntax | Прочитаны `REST API endpoints for releases`, `API Versions`, `Workflow syntax for GitHub Actions`. Query `apiVersion=2022-11-28` задаёт справочную версию REST, не подтверждает header приложения; Actions docs остаются rolling |

Проверка подтверждает доступность и назначение этих первичных страниц на указанную дату, не неизменяемость их будущего содержания, binary SHA инструментов или совместимость всей сборки. Локальные наблюдения JDK/Maven/pwsh/gh и POM pins описаны отдельно в таблице версий; URL-проверка их не подменяет.

Отрицательный результат также сохранён: прямые обращения к `https://docs.junit.org/5.14.4/user-guide/` и `https://junit.org/junit5/docs/5.14.4/user-guide/` вернули ошибку доступа web-инструмента. Поэтому они не включены как проверенные ссылки; вместо них использован реально открытый точный API URL 5.14.4. Ошибка инструмента сама по себе не доказывает HTTP 404 или отсутствие User Guide. Первичные browse-ответы находятся в истории задачи автора индекса; этот раздел - их текстовый receipt для MAIN, не новый запуск проверок.

## Навигация к реальным контрактам

Пути ниже ведут к доставляемым исходникам. Сначала прочитать контракт, затем его вызывающую сторону; внешняя API-справка не описывает бизнес-правила CashPrediction.

### Управление приложением и интерфейс

- [AppController](../../core/src/main/java/ru/cashprediction/core/app/AppController.java) и [FileFlow](../../core/src/main/java/ru/cashprediction/core/app/flow/FileFlow.java) - общая оркестрация и файловые действия, а не три независимых бизнес-алгоритма клиентов.
- [MainScreenModel](../../core/src/main/java/ru/cashprediction/core/ui/view/MainScreenModel.java), [SummaryBuilder](../../core/src/main/java/ru/cashprediction/core/ui/view/summary/SummaryBuilder.java), [FormSpec](../../core/src/main/java/ru/cashprediction/core/ui/form/FormSpec.java) - модели для отрисовки и контракт форм.
- [UiText](../../core/src/main/java/ru/cashprediction/core/ui/text/UiText.java) делегирует [Texts](../../core/src/main/java/ru/cashprediction/core/text/Texts.java). Общие UTF-8 ресурсы: [toolbar_ru.properties](../../core/src/main/resources/ru/cashprediction/core/ui/text/toolbar_ru.properties), [summary_ru.properties](../../core/src/main/resources/ru/cashprediction/core/ui/text/summary_ru.properties). Язык зафиксирован `ru`; переключатель языка не предполагается.
- Точки запуска: [FxMain](../../ui-fx/src/main/java/ru/cashprediction/fx/FxMain.java), [SwingMain](../../ui-swing/src/main/java/ru/cashprediction/swing/SwingMain.java), [WebMain](../../web/src/main/java/ru/cashprediction/web/WebMain.java). Общая web-модель передаётся через [WebUiPort](../../web/src/main/java/ru/cashprediction/web/ui/WebUiPort.java); браузерная отрисовка начинается в [main.js](../../web/src/main/resources/web/app/main.js).

### Данные, API и восстановление

- [ForecastEngine](../../core/src/main/java/ru/cashprediction/core/forecast/ForecastEngine.java) - расчёт прогноза; [ForecastEngineTest](../../core/src/test/java/ru/cashprediction/core/forecast/ForecastEngineTest.java) - проверяемые примеры, не обещание нового алгоритма.
- [PlanRepository](../../core/src/main/java/ru/cashprediction/core/io/PlanRepository.java) использует [PlanMarkdownReader](../../core/src/main/java/ru/cashprediction/core/markdown/PlanMarkdownReader.java) и [PlanMarkdownWriter](../../core/src/main/java/ru/cashprediction/core/markdown/PlanMarkdownWriter.java); [format.properties](../../core/src/main/resources/ru/cashprediction/core/format/format.properties) содержит грамматику данных, а не перевод UI. См. [round-trip тест](../../core/src/test/java/ru/cashprediction/core/markdown/PlanMarkdownRoundTripTest.java).
- [SessionStore](../../core/src/main/java/ru/cashprediction/core/session/SessionStore.java) задаёт контракт снимков и ошибок; реализации [RegistrySessionStore](../../core/src/main/java/ru/cashprediction/core/session/store/RegistrySessionStore.java), [XmlSessionStore](../../core/src/main/java/ru/cashprediction/core/session/store/XmlSessionStore.java), [MarkdownSessionStore](../../core/src/main/java/ru/cashprediction/core/session/store/MarkdownSessionStore.java). Это реестр/XML/Markdown, не SQL-схема.
- [UiApi](../../web/src/main/java/ru/cashprediction/web/ui/UiApi.java) - реальные HTTP routes, проверка входов и передача вызовов; [ControllerThread](../../web/src/main/java/ru/cashprediction/web/ui/ControllerThread.java) - граница потока контроллера. Не проектировать API по одному имени JSON-поля без чтения route и consumer.

### Версия, обновление и поставка

- [AppInfo](../../core/src/main/java/ru/cashprediction/core/io/AppInfo.java) читает `app.properties` из собранного модуля; его [фильтруемый source-шаблон](../../core/src/main/resources-filtered/ru/cashprediction/core/app.properties) получает `app.release`/`app.commit` при сборке. Локальные defaults `0`/`local` не являются release identity.
- [UpdateManifest](../../core/src/main/java/ru/cashprediction/core/update/model/UpdateManifest.java) описывает schema 2, files/tree/full-archive identity и delta descriptors; [UpdateCodecTest](../../core/src/test/java/ru/cashprediction/core/update/model/UpdateCodecTest.java) проверяет codec. [UpdateLifecycle](../../core/src/main/java/ru/cashprediction/core/update/lifecycle/UpdateLifecycle.java) - реальный жизненный цикл обновления; наличие класса не является native approval.
- [dist/pom.xml](../../dist/pom.xml) связывает jmods, jlink, jpackage и три launcher; [Pack-Source.ps1](../../dist/scripts/Pack-Source.ps1) и [Test-Pack-Source.ps1](../../dist/scripts/Test-Pack-Source.ps1) задают и проверяют состав source. Их результат нужно проверять отдельно от документации.
- [GhRetry.ps1](../../.github/scripts/GhRetry.ps1) предоставляет `Invoke-Gh` для GitHub-вызовов с обработкой transient ошибок. [Get-CiImpact.ps1](../../.github/scripts/Get-CiImpact.ps1) и [Resolve-CiBaseline.ps1](../../.github/scripts/Resolve-CiBaseline.ps1) вычисляют affected scope и baseline; [Test-CiWorkflowImpact.ps1](../../.github/scripts/Test-CiWorkflowImpact.ps1) проверяет wiring. Workflow YAML остаются репозиторной конфигурацией вне source-комплекта, поэтому обязательных ссылок на них здесь нет.

## Поддержание индекса

При смене версии сверить POM, dist jmods/runtime и receipt инструментов, затем открыть именно первичную страницу нужной версии. При переименовании класса проверить относительную ссылку в репозитории и staged source. Ошибка проверки URL не означает отсутствия реализации; успешное открытие URL не доказывает совместимость или прохождение acceptance. Не заменять код поисковыми snippets и не превращать этот индекс в список заявленных PASS.
