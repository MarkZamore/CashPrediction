# Ошибки, пограничные случаи и границы гарантий

Документ описывает текущий код CashPrediction, а не планируемые возможности. JavaFX, Swing и Web используют общие проверки и модели ядра; клиент отвечает за отображение и передачу ввода. Указанные тесты являются источниками проверяемых контрактов, но само наличие теста не означает успешного выполнения на конкретной сборке.

Все пути ниже относятся к корню исходников. Ссылки на тесты не являются receipts запуска. Здесь нет утверждения о завершении этапа или нативной приёмки. SQL, серверная база данных, учётные записи, роли и вход по паролю не применимы: таких подсистем в приложении нет.

## 1. Пустые состояния, ввод и необычные действия

| Случай | Реакция и граница |
|---|---|
| Нет сохранённого файла | `StatusBuilder.file()` показывает локализованное отсутствие файла. Это не ошибка записи и не доказательство сохранения нового плана. |
| Нет прогноза или расчёт завершился ожидаемым отказом | Общая модель сводки показывает состояние отказа; зависимые команды не становятся доступными только потому, что окно открыто. |
| Цель или событие вне горизонта | Связанные карточки используют осмысленные локализованные состояния, включая «за горизонтом», а не вымышленную дату достижения. |
| Пустая необязательная быстрая операция мастера | Завершение мастера не создаёт пустую операцию. Совпадающее имя существующего плана блокирует переход. |
| Неверная сумма, дата или незавершённое поле | `FormSession` удерживает исходный текст поля и показывает проблему; недопустимое подтверждение не изменяет план. Нормализация корректного значения при commit отличается от сохранения raw-ввода. |
| Ошибка применения формы | Форма остаётся открытой с проблемой, а не закрывается как успешно применённая. |
| Escape, Enter, закрытие окна, дочерняя форма | Общая сессия различает отмену, default-кнопку, собственный submit и открытие дочернего окна. Повторное закрытие не должно повторно снимать регистрацию заменившего окна. |
| Устаревшая строка/выбор предпросмотра | Проверяется актуальность выбора; исчезнувшая строка не является разрешением выполнить действие над другой строкой. |

Точки реализации: `core/src/main/java/ru/cashprediction/core/ui/form/FormSession.java`, `core/src/main/java/ru/cashprediction/core/ui/forms/plan/NewPlanWizardForm.java`, `core/src/main/java/ru/cashprediction/core/ui/command/CommandAvailability.java`, `core/src/main/java/ru/cashprediction/core/ui/view/status/StatusBuilder.java`.

Источники проверки:

- `core/src/test/java/ru/cashprediction/core/ui/form/FormSessionTest.java`: `invalidInputIsKeptAndBlocksOk`, `failedApplicationKeepsFormOpenWithProblem`, `escapeUsesCancelAndClientCloseDoesNotCloseHandleAgain`, `previewSelectionIsValidatedAndClearedWhenListChanges`, `captureCodecApplyRestoresFieldsPageAndBounds`.
- `core/src/test/java/ru/cashprediction/core/ui/forms/plan/NewPlanWizardFormTest.java`: `finishBuildsPlanAndSkipsEmptyQuickOperation`, `existingNameBlocksNext`, `enterFinishesOnLastPage`.
- `core/src/test/java/ru/cashprediction/core/ui/command/CommandAvailabilityTest.java`: проверки причин недоступности по категориям строк, прогнозу, дате, undo/redo и what-if.
- `core/src/test/java/ru/cashprediction/core/ui/view/summary/SummaryModelTest.java`: `beyondHorizonCards`, `goalReachedNotReachedAndUntitled`, `forecastFailureShowsOneLine`.

Raw-поля, страница мастера и дерево владельцев окон входят в снимок сессии. Это не обещает сохранения последнего физического нажатия при немедленном убийстве процесса: запись отложена, а клиент должен успеть передать ввод ядру. Фактическое восстановление видимых полей и вложенных окон проверяется отдельно от roundtrip кодека.

## 2. Проверка предметных изменений и пределы расчёта

`PlanValidator` диагностирует загруженный план. Терпимое чтение повреждённых данных оставляет возможность исправления; оно не означает, что новые недопустимые изменения разрешены. `LocalPlanCommands.execute()` проверяет ожидаемую ревизию; для нового предметного изменения строит проект и валидирует его до фиксации истории. Отмена и повтор используют отдельный путь истории.

- Недопустимые суммы/повторы, отсутствующий объект и повторный идентификатор дают структурированный отказ, без изменения плана, истории и redo.
- Равная команда даёт `UNCHANGED`, а не дополнительный шаг undo. Устаревшая ревизия отклоняется даже для равной команды.
- Идентичный повтор UUID возвращает исходный результат; другой запрос с тем же UUID даёт `REQUEST_ID_REUSED`. Повторный вход во время команды даёт `COMMAND_IN_PROGRESS`.
- Дедупликация ограничена последними 256 завершёнными запросами экземпляра, включая отказы проверки данных и ревизии. Отказы `COMMAND_IN_PROGRESS` и коллизии занятого UUID (`REQUEST_ID_REUSED`) не занимают окно. Замена документа с файловым событием очищает окно; после вытеснения работает обычная проверка ревизии. Перезапуск не сохраняет окно: сетевого exactly-once здесь нет.
- `preview()` не использует дедупликацию и не изменяет документ, историю, предметную ревизию или окно повторов. При актуальной ревизии команды Undo/Redo в предпросмотре отклоняются с `INVALID_COMMAND`.
- Отмена и повтор в `execute()` используют историю документа без валидации как нового предметного изменения. Они могут вернуть терпимо загруженное состояние, включая повреждённый исходник, не обязанное пройти проверку новых изменений; это не разрешение применять новые недопустимые правки.
- Ошибка синхронного слушателя после commit отмечается `NOTIFICATION_FAILED`. Она не означает откат уже принятого изменения. Исполнитель команд требует сериализации владельцем, а не конкурентных вызовов из произвольных потоков.

`Money` использует `long` минимальных денежных единиц и два десятичных знака; `Long.MIN_VALUE` недопустим. Арифметика проверяет переполнение; what-if округляет через `BigDecimal`/`HALF_UP`. Смена названия валюты не конвертирует суммы. `ForecastEngine.MAX_DAYS` ограничивает горизонт 200 000 днями. Ожидаемые отказы службы прогноза имеют виды `LIMIT_EXCEEDED`, `AMOUNT_OVERFLOW`, `AMOUNT_OUT_OF_RANGE`, `DATE_RANGE_EXCEEDED`; неожиданная ошибка программирования не маскируется под них.

Источники: `core/src/test/java/ru/cashprediction/core/diagnostics/PlanValidatorTest.java`; `core/src/test/java/ru/cashprediction/core/service/plan/LocalPlanCommandsTest.java` (в частности `boundedRetryWindowFallsBackToRevisionCheck`, `observerFailureAndReentrantCommandHaveExplicitResults`, `tolerantReplacementIsSeparateAndInvalidDataCanBeRepairedAndUndone`); `core/src/test/java/ru/cashprediction/core/forecast/service/ForecastFailureUiHandlingTest.java` (реальные четыре отказа движка, восстановление после ошибки и отдельное поведение неожиданных исключений).

## 3. Файлы, конкуренция и защищённые пути

`FileFlow` вызывает `PlanStorage`; `FilePlanStorage` связывает контракт с `PlanRepository`. Ошибки разделены на `MISSING`, `CONFLICT`, `CORRUPT`, `IO_ERROR`; конфликт различает изменённую версию и занятое имя. `Version.ABSENT` разрешает создание отсутствующего файла, а не перезапись существующего.

Версия содержит атрибуты и SHA-256: сохранённое сторонним редактором время файла не скрывает изменение содержимого. `ExternalChangeGuard` и файловый поток организуют проверку, подтверждение и перечитывание. Ошибка наблюдения не приравнивается к безопасному отсутствию файла. Переименование сохранённого файла не включает несохранённые правки документа.

`AtomicFiles` записывает временный файл рядом с целью, сбрасывает данные и заменяет файл. При неподдерживаемом atomic move есть обычная замена. Проверки перед мутацией сокращают окно гонки, но `synchronized` защищает лишь экземпляр адаптера: другой процесс всё ещё может изменить путь между последней проверкой и записью. Нет общего межпроцессного compare-and-swap и транзакции для произвольного набора файлов.

`CashMemoryLayout.isProtectedUserPath` защищает служебные файлы своей копии, включая реальные алиасы и hard link, от выбора как пользовательской цели. Внешний Markdown разрешён для чтения/импорта и редактирования в памяти, но не для application-managed записи по внешнему пути. `FilePlanStorage.canWrite()` проверяет scope CashMemory; `FileFlow.save()` для внешнего текущего файла направляет ручное сохранение в «Сохранить как», а autosave сообщает о read-only импорте. `FileChooserService.chooseFile()` начинает SAVE в CashMemory и проверяет окончательную цель через `AtomicFiles.requireWriteScope()`, в том числе после подтверждения замены. CSV и PNG записываются через `AtomicFiles.writeStringScoped()`/`writeScoped()`: цель и staging ограничены CashMemory, scope повторяется перед публикацией. Сам явный выбор внешнего пути не разрешает запись; соседняя папка с похожим именем и небезопасный предок тоже не расширяют scope. Обновление имеет отдельную границу управляемого дерева приложения. Нельзя превращать эти правила в неподтверждённое обещание полного системного аудита всех записей процесса.

Источники проверки:

- `core/src/test/java/ru/cashprediction/core/service/storage/FilePlanStorageTest.java` и `GuardedMutationWindowTest.java` в той же папке: save/rename/create после вмешательства редактора.
- `core/src/test/java/ru/cashprediction/core/service/storage/ManagedWriteScopeTest.java`: внешнее чтение при запрете save/rename, сохранность внешних байтов, отсутствие внешних папок/staging при отказе; `core/src/test/java/ru/cashprediction/core/app/file/FileFlowStorageTest.java`: импорт, managed policy и реальные link ancestors. Это ссылки на контракты тестов, не новый receipt их выполнения.
- `core/src/test/java/ru/cashprediction/core/io/AtomicFilesTest.java`, `ScopedAtomicGuardTest.java`, `CashMemoryUserPathPolicyTest.java`, `CashMemoryJunctionGuardTest.java`.
- `core/src/test/java/ru/cashprediction/core/app/file/ProtectedFileChooserTest.java`, `StartupRenameConflictRegressionTest.java`.

## 4. Снимки сессии, повреждение и карантин

`SessionStores` выбирает реестр/XML для desktop и серверный Markdown для Web. `SessionRecorder` отдельно пишет доступные stores: ошибка одного не останавливает остальные. Это не распределённый commit всех stores.

### 4.1. Классификация и безопасная новая запись

`SessionStoreException.Code.CORRUPT` означает подтверждённое повреждение формата/целостности. `IO_ERROR` и `UNAVAILABLE` не разрешают очистку под видом corruption. Сохранение нового состояния после подтверждённой порчи проходит через `CorruptStoreQuarantine`:

1. Читаются исходные компоненты повреждённого store.
2. В `CashMemory/Recovery` создаётся уникальный `.md` через `CREATE_NEW`, с `force` и точным обратным чтением. Проверяются пути без ссылок; карантин вне папки с именем CashMemory не разрешается.
3. Документ содержит источник, причину, время, длины, SHA-256, ограниченный читаемый preview и полные payload в Base64.
4. Источник повторно сравнивается перед первой мутацией. После успешного сохранения карантина можно записать новый валидный снимок этого store.

Для XML/Markdown payload сохраняет точные байты, включая существующие sidecar и journal Markdown. Для реестра сохраняются логические значения Java Preferences как UTF-16 code units, включая непарные суррогаты, и имена ключей. Это не бинарный экспорт Windows hive или файл `.reg`. Диагностический preview экранирует управляющие символы и Markdown; он не заменяет исходный payload. Карантин не шифруется и может содержать чувствительные данные плана/полей: перед передачей диагностики необходимо учитывать это.

Если карантин не создан/не проверен либо источник изменился, новая запись не объявляется успешной. Сбой создания карантина не разрешает стирать исходные доказательства. Если архив уже создан, а последующий commit не завершён, архив остаётся; само его наличие не доказывает сохранение свежего состояния. Здоровые другие stores не очищаются ради восстановления повреждённого.

`recoveryNotice()` передаётся в `StoreStatus` после успешного нового commit. `StatusBuilder` показывает предупреждение с подсказкой, а не обычный незаметный успех; ошибка записи имеет ошибочный статус. Начало сеанса и отказ карантина не должны выдавать ложное предупреждение об успешном восстановлении. Notice принадлежит текущему экземпляру store; его сохранение после перезапуска не заявлено.

Точки вызова: `XmlSessionStore.save`, `MarkdownSessionStore.save`, `RegistrySessionStore.save` в `core/src/main/java/ru/cashprediction/core/session/store`; далее `CorruptStoreQuarantine.preserve/unchanged`; успешный статус формируется в `SessionRecorder.write`, экранный сегмент - в `StatusBuilder`.

### 4.2. Сбой между фазами записи

- XML использует замену одного файла. Изменение running/closed marker не должно превращать повреждённые исходные байты в пустой снимок.
- Markdown пишет журнал `<session-file>.transaction.md` до изменения sidecar. Журнал содержит предыдущий полный снимок и SHA-256 нового session commit. Чтение возвращает старое состояние до commit либо новое при совпадении хеша; поздняя ошибка удаления журнала не означает откат уже committed состояния.
- Реестр записывает и проверяет `backup.snapshot.*`, затем flush pending до мутации primary. После записи проверяется полный primary; очистка журнала происходит позднее. `snapshot.time` - завершающий ключ primary, но не последняя операция всего протокола: за ним могут идти flush и очистка backup/pending.
- Chunk реестра ограничен 4096 единицами UTF-16. Чтение сверяет count/length, наличие и размеры chunks, CRC32 и кодек; не резервирует буфер по одной недоверенной заявленной длине. Это не повод уменьшать поддерживаемый размер снимка ради теста.

Журналы дают целый старый или новый снимок в предусмотренных фазах, а не универсальную гарантию против отключения питания, внешнего редактирования всех компонентов или сбоя ОС. Повторное чтение перед записью не является межпроцессным CAS.

Источники проверки в `core/src/test/java/ru/cashprediction/core/session/store/`:

- `CorruptStoreRecoveryTest.java`: повреждённые stores, свежие неверные поля, сохранение payload и безопасный отказ карантина.
- `StoreLifecycleBoundaryTest.java`: `observerWarnOnlyAfterNewCommitAndErrorWhenQuarantineFails`, `mixedStoresAcrossStartSaveAndCleanClose`, `actualHkcuCrashAfterPendingFlushBeforePrimaryMutation`. Последний внедряет ошибку после реального HKCU flush; это не убийство нативного клиента.
- `StoreCommitFaultTest.java`: ошибки записи chunks и Markdown sidecar, поздняя ошибка очистки committed journal.
- `XmlSessionStoreTest.java`, `MarkdownSessionStoreTest.java`, `RegistrySessionStoreTest.java`, `RegistrySessionStoreBoundedTest.java`: формат, маркеры, ordering, corruption и недоверенные размеры.

## 5. Запуск, восстановление и выход

До `SessionRecorder.start()` запись не начинается: новый пустой сеанс не должен затереть снимок до решения о восстановлении. `CrashDetector` учитывает PID и время рождения процесса, отличает crash от живого экземпляра и чистого завершения. Чистый маркер одного и того же сеанса может разрешить расхождение stores; это не универсальный выбор «любой здоровый победил». `RecoverySnapshots` классифицирует доступность материала; `RestoreCoordinator` и `CoreWindowFactory` восстанавливают известные идентификаторы и контексты окон.

Второй экземпляр может работать без записи сессии; статус должен отражать отключённый recorder. Снимки разных portable-копий разделены узлом `ru/cashprediction/session/<клиент>-<8 hex хеша пути CashMemory>` и значением `cashmemory.path`; это не синхронизация одновременно открытых планов.

`touch()` использует debounce 400 мс, период записи - 5 с. `saveNow()` из UI снимает текущее состояние; вне UI использует последний снятый снимок. При сломанном capture возможен fallback, а ошибка отдельного окна не должна лишать снимка остальных. Ожидание writeLock ограничено 2 с, но затем код может продолжить запись через синхронизированные stores: это не жёсткий двухсекундный предел всей операции.

Отмена выхода или ошибка запрошенного сохранения плана оставляют сеанс работающим. «Не сохранять» при чистом выходе отличается от crash: отброшенные правки не должны воскреснуть как материал аварии. Ошибка настроек отмечается отдельно и не отменяет чистый выход. Поздние таймеры после shutdown не должны записывать новый running-снимок.

Источники: `core/src/test/java/ru/cashprediction/core/session/CrashDetectorTest.java`, `SessionRecorderTest.java`, `RecoveryAvailabilityBoundaryTest.java`, `RecoverySnapshotsContractTest.java`; `core/src/test/java/ru/cashprediction/core/app/file/ExitFlowTest.java`; `core/src/test/java/ru/cashprediction/core/app/session/startup/RecoveryFlowTest.java`; `core/src/test/java/ru/cashprediction/core/app/flow/StartupRecoveryBoundaryContractTest.java`.

## 6. Web: origin, связь и локальная безопасность

Web - локальный HTTP UI того же приложения, а не публичная служба с аккаунтами. Обычный `UiApi.checkSecurity()` проверяет Host с фактическим loopback-портом и токен (`X-Token` либо параметр `t`), сравнивая байты через `MessageDigest.isEqual`. Нельзя приписывать обычным маршрутам такую же явную проверку Origin, как reconnect: это разные обработчики.

`/api/ui/reconnect/challenge` и `/api/ui/reconnect/complete` отдельно требуют точного loopback Host, совпадающего `http://<Host>` Origin, `X-CP-Reconnect: 1`, POST, JSON и отсутствия query. Тело ограничено 2048 байтами, декодирование UTF-8 строгое. `ReconnectAuthenticator` проверяет доказательство; браузерный `Transport.proofMessage()` связывает его с origin, идентичностью установки, поколением сервера и challenge. Это защита переподключения вкладки, не логин пользователя.

Токен и reconnect-credential хранятся в памяти вкладки/sessionStorage. Начальный `t` удаляется из адресной строки через `history.replaceState`. Отказ/повреждение sessionStorage не блокирует первый bootstrap, но нельзя обещать сохранение credential при reload без работающего storage. Служебные `web-reconnect.md` и `web-reconnect-lock.md` не являются планами и не должны предлагаться как пользовательские цели.

| Сетевой случай | Фактический контракт |
|---|---|
| Неверный Host/токен | Отказ 403, не доступ к контроллеру. |
| Неверный JSON/поле/курсор | 400; intent проверяет курсор до выполнения действия. Общий body ограничен 8 МиБ и при превышении тоже даёт 400, не 413. |
| Ожидание контроллера завершилось по timeout или interruption | 503 с `busy`; это не доказательство, что поставленная работа никогда не выполнилась. Неопределённое действие нельзя слепо повторять. |
| Внутреннее исключение | 500 с общим локализованным сообщением, не stack trace в UI. |
| Выпал старый эффект из журнала | `EffectLog` хранит 1000 эффектов; старый курсор требует resync/bootstrap. Long polling ждёт до 25 с. |
| Потеря связи, старый ответ после reconnect | `Transport` отменяет старое поколение запросов, проверяет generation после await и не применяет старые эффекты к новой модели. |
| Ответ intent потерян | Намерения сериализуются, но транспорт не повторяет неопределённые действия автоматически. Нет устойчивой сетевой дедупликации. |
| Запоздавшее эхо поля | `clientRev` защищает более новый ввод; сервер учитывает ревизии по вкладке/полю. |

Статические ресурсы локальные, без CDN/npm. `StaticHandler` и `HttpUtil` задают CSP, `nosniff`, `no-referrer`, запрет кэширования и ограничение путей ресурсов. Эти меры не защищают от процесса с теми же правами ОС, читающего файлы/память. Тестовые маршруты доступны только при явном `--test-api`; их нельзя считать обычной production-аутентификацией.

Источники: `web/src/main/java/ru/cashprediction/web/ui/UiApi.java`, `EffectLog.java` в той же папке, `web/src/main/resources/web/app/transport.js`; `web/src/test/java/ru/cashprediction/web/ui/UiApiTest.java`, `UiApiTransportMigrationTest.java`, `ReconnectHttpSecurityTest.java`, `ReconnectHttpFilePolicyTest.java`; `web/src/test/java/ru/cashprediction/web/SharedHttpContractTest.java`; `core/src/test/java/ru/cashprediction/core/web/reconnect/ReconnectAuthenticatorTest.java`.

## 7. Тихое обновление: отказы и непроверенные границы

`UpdateLifecycle.beforeUi()` выполняет локальную проверку установки/восстановления до UI, но только после успешной загрузки JVM main-модуля. Он не может восстановить отсутствующий модуль, если JVM не дошла до точки входа. Dev-идентичность с release 0 инертна. Возврат `false` из `InstallCoordinator.beforeUi()` сохраняется как `BLOCKED`; в частности, отказ восстановления при нецелом текущем образе не разрешает UI. Техническое исключение `IOException`/`RuntimeException` координатора обрабатывается отдельно: lifecycle сохраняет проблему `BARRIER_FAILED` и допускает запуск (`ALLOWED`, fail-open). Это политика продолжения работы исправной текущей версии, не подтверждение целостности любого повреждённого образа или завершённого восстановления.

`UpdatePreparer` выполняет один фоновый проход подготовки в экземпляре без блокировки обычного запуска. Для манифеста предусмотрено максимум три попытки, включая первую; штатные паузы между неудачными попытками - 1 и 2 с. Это не три повторные загрузки каждого payload: дельта и полный fallback загружаются по одному разу на проход. Повторный вызов `prepare()` в том же экземпляре не запускает постоянный опрос; новый экземпляр может перечитать манифест. Сетевые/форматные ошибки не означают принятия нового кандидата и не требуют UI-уведомления подготовки. HTTP-дедлайн включает чтение body. Payload ограничен 512 МиБ; размеры и SHA-256 контейнера и дерева проверяются до принятия. Дельта выбирается по release/commit и реальному хешу базы; повреждённая дельта может перейти к проверенному полному образу. Отмена во время дельты не должна запускать полный fallback, следующая сессия начинает загрузку заново, а не обещает resume.

Сетевой или форматный отказ нового кандидата не отменяет уже имеющийся Ready, если его манифест и дерево повторно проверены и его релиз новее установленного. Поэтому `prepare()` может вернуть `true` благодаря проверенному кэшу, а не принятию ошибочного payload. После `close()` результат `false` сам по себе не означает удаления прежнего Ready. Непроверенный или повреждённый кэш такой гарантии не получает; это не безусловное обещание сохранности при отказе диска или файловой системы.

Production endpoint фиксирован; redirect ограничивается. Selftest loopback endpoint разрешён только при специальном сочетании test-api, home и UUID selftest-узла. Это не произвольная пользовательская настройка download URL.

`InstallCoordinator` использует журналы и leases. `ProcessLease` записывает UUID, PID и время рождения настоящей JVM, а не только родителя jpackage; normal close не удаляет lease до фактического выхода JVM/DLL. Helper должен учитывать живые процессы этой установки. Занятая recovery-граница не разрешает параллельно планировать ещё один helper. Отмена пользовательского выхода не равна normal-exit установки.

`SafeTree` и проверка managed paths ограничивают замену exe/app/runtime, отклоняют ссылки/reparse и чужие журналы; планы/настройки не входят в обновляемый инвентарь. Это не межпроцессная блокировка произвольных пользовательских файлов.

Для компоновки с внешним module-path `PortableBootstrap`/`BootstrapScript` готовят проверенный старый runtime и четыре внешних app-JAR в `CashMemory/Updates/Bootstrap`, перенаправляя cfg до замены основного дерева. Проверка четырёх app-JAR вызывается только при внешних модулях; `ReadyStore` допускает также компоновку с модулями jlink внутри `runtime/lib/modules`, без отдельных app-JAR. Размещение модулей не определяет релизную идентичность: инертность release 0 проверяется отдельно. Наличие механизма bootstrap не доказывает cold recovery. Отдельно нужны обычные exe после остановки helper на границах copy/publish bootstrap, перенаправления cfg, BACKING_UP, INSTALLING и rollback. Продолжающая работать старая JVM не является таким доказательством.

Источники: `core/src/test/java/ru/cashprediction/core/update/net/UpdatePreparerTest.java` (три попытки, slow body, concurrency, отмена, delta/full, invalid hash, redirect); `core/src/test/java/ru/cashprediction/core/update/install/InstallCoordinatorTest.java`; `PortableBootstrapTest.java`, `PortableBootstrapRecoveryTest.java`, `ExternalModulesBootstrapRecoveryTest.java`, `BootstrapRecoverySecurityTest.java` в той же папке; `core/src/test/java/ru/cashprediction/core/update/lifecycle/UpdateLifecycleOutcomeContractTest.java`.

Нативные `.github/scripts/Test-UpdateBootstrap.ps1` и `Test-NativeUpdateLifecycle.ps1` проверяют разные границы. Lifecycle runner не удостоверяет DOM/скриншоты; выбранные клетки не равны всей матрице, а его `-Signoff` отклоняет неполный набор evidence. Подготовленные команды и helper, fixture PASS и проверка разрешения модулей не заменяют actual cold/native execution.

## 8. Почему диагностический screenshot может быть unsupported

Строгий capture связывает raw, PNG, идентичность запроса, подтверждённый ввод, поверхности и поколения наблюдения. Независимые dump и screenshot не образуют такой commit. `UiDriver.capture()` по умолчанию отказывает; `PaintCaptureFiles` публикует commit последним и проверяет прочитанные байты. Неизвестные свойства, неполный набор и нестабильность не допускают успешного строгого захвата.

- FX: `FxCaptureJournal` наблюдает публичные свойства и post-layout; `Scene.snapshot` не подтверждает экранный paint каждого узла. Полнота журнала остаётся false, `lastPaintEpoch` неизвестен; snapshot меняет renderGeneration. `FxUiDriver.captureDiagnostic()` может вернуть диагностику, но `capture()` отказывает при unsupported/нестабильности. Canvas/CSS/внутренний renderer и полная сертификация шрифтов не покрываются этим публичным hook.
- Swing: API захвата подключён: `SwingUiDriver.capture(request)` вызывает `captureDiagnostic(request).requireSupported(request)`. `captureDiagnostic()` открывает `CaptureTransaction`, где устанавливаются `SwingCaptureRepaintManager` и `SwingPaintCollector`; raw и границы геометрии читаются на EDT, экранный PNG снимается Robot на worker. Это интеграция вызова, не доказательство полного paint. `SwingPaintJournal` требует полного owner scope и подтверждённого экранного paint; стандартный root без painter hooks не получает фиктивно завершённые scopes. Dirty region и возврат `paintDirtyRegions()` не доказывают полноту; проход прежнего RepaintManager может обойти обёртку. Сборщик и repaint handle требуют EDT/закрытия. `CaptureResult.requireSupported()` при unsupported или нестабильном bracket бросает `UnsupportedCapture` с диагностикой до создания успешной пары; обычные dump/screenshot не устраняют отказ и не дают PASS.
- Web: retained icon bytes включаются до создания иконок лишь при `bootstrap.testApi=true` и `paintCapture=1`. Late fetch по `currentSrc` не доказывает происхождение уже показанной иконки. `ready()`/`requireHealthy()` проверяют источник, не paint/стабильность кадра; полный согласованный browser capture этим не обеспечен.

Источники реализации: `core/src/main/java/ru/cashprediction/core/ui/selftest/paint`; `ui-fx/src/main/java/ru/cashprediction/fx/ui`; `ui-swing/src/main/java/ru/cashprediction/swing/ui`; `web/src/main/resources/web/app/icon.js`, `retained-icon-sources.js`. Контрактные проверки: `core/src/test/java/ru/cashprediction/core/ui/selftest/paint/PaintCaptureFilesTest.java`, `PaintObservationCodecTest.java`, `SelfTestPaintCaptureTest.java` в той же папке. Их fixtures не являются настоящими экранными наблюдениями или независимыми растровыми эталонами.

## 9. Как разделять необходимое доказательство

Проверки группируются по проверяемой границе, а не повторяются целиком после каждого изменения. Для неизменных входов используют сохранённый receipt с SHA исходников, сборки и среды; неизвестная актуальность не превращается в PASS.

| Группа | Что подтверждает | Что остаётся отдельной проверкой |
|---|---|---|
| Core/кодеки/fixtures | Валидацию, классификацию ошибок, raw roundtrip, journal ordering и отказ карантина | Реальное окно, физический ввод и crash процесса |
| Actual файловые stores/HKCU | Запись в реальные изолированные stores и точное сохранение payload на внедрённой границе | Убийство portable JVM, потеря питания, конкурентный чужой процесс |
| Actual GUI/JAR/CDP | Видимые поля, wizard page, предупреждение, reload и восстановление окна/вкладки в указанной среде | Native launcher, cold bootstrap и ручная UX-приёмка |
| Native portable | Реальные exe, дерево процессов, DLL/locks, холодный запуск и выбранные update/crash клетки | Непройденные клетки, независимый screenshot/DOM signoff и итоговая поставка |

Desktop E2E представлены `ui-parity/src/test/java/ru/cashprediction/parity/e2e/desktop/CrashRestoreE2ETest.java`; already-running и unsaved-plan - `ui-parity/src/test/java/ru/cashprediction/parity/e2e/interaction/AlreadyRunningE2ETest.java` и `UnsavedPlanRestoreE2ETest.java`; Web - `ui-parity/src/test/java/ru/cashprediction/parity/e2e/web/WebCrashRestoreE2ETest.java` и `ui-parity/src/test/java/ru/cashprediction/parity/browserprobe/WebReconnectBrowserIT.java`. Эти сценарии нельзя автоматически считать покрытием corrupt-store wizard или всех update crash points.

Необходимо отдельное actual GUI evidence для corruption/recovery: свежий неверный raw-ввод после отказа карантина, успешная новая запись с предупреждением, целые healthy stores, сохранённые payload, страница мастера/вложенные окна и Web reload/reconnect. Требуются actual native cold-запуски на перечисленных bootstrap/install/rollback границах. Unsupported paint остаётся unsupported до полного клиентского барьера, даже при красивом диагностическом PNG.

Любой новый опыт использует собственную CashMemory и `ru/cashprediction/selftest/<uuid>` с cleanup в finally и проверкой отсутствия узла после завершения. Не удаляются production nodes, чужие snapshots или здоровые stores. Receipt фиксирует реальные команды, PID/время рождения, SHA входов и результатов, выполненный сценарий и cleanup; template, stale/synthetic lease или mock не удостоверяют native execution. Включение документа в source `.7z` проверяется упаковкой отдельно и не является следствием наличия этого файла.
