# Правила работы над CashPrediction

## Архитектура

- Три клиента (JavaFX `ui-fx`, Swing `ui-swing`, Web `web`) — только способы запуска одного приложения. Интерфейс у всех трёх одинаковый; поведение, тексты и модель интерфейса живут в `core`, клиенты их отрисовывают.
- Внешних библиотек нет: только JDK, OpenJFX (в `ui-fx`) и JUnit (тесты). В web — чистые HTML/CSS/JS без CDN и npm.
- Весь код комментируется по-русски: Javadoc у каждого класса и открытого метода, JSDoc у каждой функции. У каждого использования классов меню, всплывающих окон и диалогов стоит комментарий соответствия `// JavaFX: X → Swing: Y → Web: Z`.
- Программа пишет файлы только в папку `CashMemory` рядом с exe и в реестр `HKCU\Software\JavaSoft\Prefs\ru\cashprediction`.

## Сборка и тесты

- `mvn -B install` — все модули и тесты; перед коммитом должна быть зелёной.
- `mvn -B -Pdist -DskipTests package` — портативная папка `dist/target/dist/CashPrediction` и zip.
- `.github/scripts/Test-Portable.ps1 -PortableDir dist\target\dist\CashPrediction` — проверка готовых exe; локально без `-CleanRegistry`.
- Лаунчер jpackage перезапускает себя дочерним процессом: окна и JVM живут в потомке, завершать нужно всё дерево процессов.

## Релизы

- Push в `main` запускает `.github/workflows/release.yml`: тесты, портативная сборка, проверка exe, публикация `CashPrediction-portable.zip` и `release.json` в релиз `latest`. Остальные ветки и pull request проверяет `ci.yml`.
- Номер релиза = число коммитов в `main` (`git rev-list --count HEAD`). Перед коммитом в `main` добавить сверху `CHANGELOG.md` раздел `## <текущее число коммитов + 1>` и одну строку `- …` о том, что изменилось для пользователя. Без записи релиз не публикуется.
- При слиянии ветки номер считается по итоговой истории `main`, включая коммиты ветки и сам merge-коммит.
- Номер релиза попадает в сборку через `-Dapp.release` и читается классом `ru.cashprediction.core.io.AppInfo`.
- Все обращения к GitHub в workflow идут через `Invoke-Gh` из `.github/scripts/GhRetry.ps1`: повторы при 429/5xx.
