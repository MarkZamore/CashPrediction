# Сборка и запуск CashPrediction

## Что нужно

| Инструмент | Версия | Зачем |
|---|---|---|
| JDK | 25 с папкой `jmods` (например, Microsoft Build of OpenJDK 25) | компиляция, `jlink`, `jpackage` |
| Maven | 3.9 или новее | сборка модулей |
| Интернет | только при первой портативной сборке | загрузка jmods JavaFX 25.0.4 от Gluon (~46 МБ) и зависимостей Maven |

WiX, Launch4j и GraalVM не нужны.

## Структура проекта

| Модуль | Содержимое |
|---|---|
| `core` | доменная модель, движок прогноза, файлы CashMemory, снимки сессии; без UI |
| `ui-fx` | настольный клиент на JavaFX |
| `ui-swing` | настольный клиент на Swing |
| `web` | тонкий web-клиент: встроенный HTTP-сервер JDK и HTML/JS |
| `dist` | портативная сборка (включается профилем `-Pdist`) |

## Сборка и тесты

```
mvn -B install
```

Команда компилирует все модули и запускает тесты ядра.

## Запуск в режиме разработки

Сначала один раз установить модули в локальный репозиторий, затем запускать нужный клиент:

```
mvn -B -q -DskipTests install
mvn -pl ui-fx javafx:run
mvn -pl ui-swing exec:exec
mvn -pl web exec:exec
```

В режиме разработки папка `CashMemory` создаётся в корне проекта. Её не нужно добавлять в систему контроля версий.

Шаг `install` обязателен. Команда с `-am` попыталась бы выполнить `javafx:run` и в модуле `core`, где нет главного класса.

## Портативная сборка

```
mvn -B -Pdist -DskipTests package
```

Результат:

```
dist/target/dist/
├── CashPrediction/
│   ├── CashPrediction.exe          JavaFX-клиент
│   ├── CashPrediction-Swing.exe    Swing-клиент
│   ├── CashPrediction-Web.exe      web-сервер тонкого клиента
│   ├── app/                        jar-модули и настройки лаунчеров (*.cfg)
│   └── runtime/                    урезанная Java (jlink)
└── CashPrediction-portable.zip     та же папка в архиве
```

Папку можно скопировать на любой компьютер с Windows 10/11 x64, Java там не нужна. При первом запуске рядом с exe появится `CashMemory`, больше программа ничего не создаёт.

Если модули уже установлены командой `install`, пересобрать только дистрибутив можно быстрее:

```
mvn -B -Pdist -pl dist package
```

### Почему папка, а не один .exe

GraalVM native-image не поддерживает AWT/Swing на Windows, а JavaFX в native-image на Windows не собирается. Поэтому используется `jpackage --type app-image`: папка с настоящими .exe-лаунчерами и встроенной урезанной Java. Установщик не создаётся и не требуется.

### Как устроен конвейер

1. `download-maven-plugin` скачивает `openjfx-25.0.4_windows-x64_bin-jmods.zip` и распаковывает его в `dist/target/javafx-jmods`. Архив кэшируется в `~/.m2`. Вместо загрузки можно указать свою папку: `-Djavafx.jmods=C:\путь\javafx-jmods-25.0.4`.
2. `maven-clean-plugin` удаляет прошлый результат, потому что `jlink` и `jpackage` отказываются писать в существующую папку.
3. `maven-dependency-plugin` копирует jar-модули проекта в `dist/target/mods`.
4. `jlink` собирает `dist/target/runtime` из модулей JDK, jmods JavaFX и модулей проекта.
5. `jpackage` создаёт папку с тремя лаунчерами.
6. PowerShell `Compress-Archive` упаковывает папку в zip.

Версия JavaFX задана одним свойством `javafx.version` в корневом `pom.xml`. Она обязана совпадать с версией jmods.

### JVM-опции лаунчеров

| Опция | Зачем |
|---|---|
| `--enable-native-access=javafx.graphics` | только JavaFX-лаунчер: JEP 472, JavaFX загружает свои DLL; без флага JDK 25 печатает предупреждение |
| `-XX:-UsePerfData` | не создавать `%TEMP%\hsperfdata_<пользователь>` |
| `-XX:-CreateCoredumpOnCrash` | не писать дамп памяти в рабочий каталог при падении JVM |
| `-XX:+SuppressFatalErrorMessage` | при падении самой JVM не создавать отчёт `hs_err_pid*.log` |
| `-XX:-DumpReplayDataOnError` | при сбое JIT-компилятора не создавать `replay_pid*.log` |
| `-Duser.language=ru -Duser.country=RU` | русский интерфейс и форматы независимо от настроек Windows |
| `-Xmx512m` | ограничение памяти |

## Проверка «никаких лишних файлов»

Что сделано, чтобы программа не оставляла следов вне `CashMemory`:

| Источник файлов | Мера |
|---|---|
| `%TEMP%\hsperfdata_*` | `-XX:-UsePerfData` |
| `~/.openjfx/cache` (DLL JavaFX при запуске из jar) | в дистрибутиве JavaFX берётся из jlink-рантайма, кэш не создаётся |
| `hs_err_pid*.log`, `replay_pid*.log`, дампы | `-XX:+SuppressFatalErrorMessage`, `-XX:-DumpReplayDataOnError`, `-XX:-CreateCoredumpOnCrash` |
| `java.util.prefs` | используется только пользовательский корень, на Windows это реестр `HKCU\Software\JavaSoft\Prefs` |
| временные файлы атомарной записи | создаются только внутри CashMemory и удаляются |

Как проверить вручную:

1. Сохранить список файлов `%TEMP%` и `%USERPROFILE%`.
2. Запустить все три exe, поработать, закрыть.
3. Сравнить списки: новых записей быть не должно.
4. Для полной проверки подойдёт Process Monitor с фильтром `Operation = WriteFile` и `Process Name` начинается с `CashPrediction`: запись идёт только в `CashMemory` и в реестр.

Кэш `~/.openjfx` появляется только при запуске JavaFX-клиента в режиме разработки через Maven, это нормально.

## Устранение неполадок

| Симптом | Причина и решение |
|---|---|
| `Failed to delete ...\CashPrediction-Swing.exe` | jpackage создаёт лаунчеры с атрибутом «только чтение». В `dist/pom.xml` для очистки включён `force`; при ручной очистке снимите атрибут |
| `Failed to delete ...\CashPrediction: ... занят другим процессом` | папку держит открытый терминал, Проводник или запущенное приложение. Закройте их или перейдите в другой каталог |
| `Zip attempt N failed` | антивирус ненадолго блокирует свежие файлы. Шаг повторяется до пяти раз; если все попытки неудачны, запустите сборку ещё раз |
| `jlink: module not found: javafx.graphics` | не скачались jmods или версия в `-Djavafx.jmods` не совпадает с `javafx.version` |
| Даты и месяцы выводятся по-английски | в рантайм не попал `jdk.localedata`; он явно указан в `--add-modules` |
| `WARNING: A restricted method in java.lang.System has been called` | JavaFX запущен без `--enable-native-access=javafx.graphics` |
