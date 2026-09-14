# Web-протокол единого интерфейса

Тонкий клиент в браузере рисует те же модели ядра, что JavaFX и Swing, и передаёт действия пользователя в тот же `AppController` на сервере. Протокол один к одному повторяет контракты ядра: намерения - `UiIntents` и `FormSession` (`core.ui.json.WebIntent`), запросы - `UiIntents` без изменения состояния (`WebQuery`), эффекты - вызовы `UiPort` (`WebEffect`). JSON строит `core.ui.json.UiJson`.

Источник правды - архитектура §5 (`docs/design/architecture.md`) и записи `core.ui.json`. При изменении контракта этот файл обновляется в том же коммите.

## Общие правила

- Все маршруты `/api/**` требуют существующий ключ доступа и проверку заголовка `Host` (`ApiHandler`).
- Даты - ISO: `2026-10-05`; месяц - `2026-10`. Суммы в моделях - готовые тексты («80 000,00»), в значениях полей - канонические формы `FieldCodec` (`95000,00`).
- Записи ядра превращаются в объекты с полями в порядке компонентов; вид sealed-интерфейса - поле `kind` с простым именем записи (`"kind":"Check"`); enum - имя константы; `null` - `null`.
- **Два исключения, одна форма во всём протоколе:**
  - `CommandId` - всегда id спецификации (`CommandId.id()`): `"file.save"`, `"row.edit"`, `"view.period.M3"`. Так пишутся узлы меню, тулбара, контекстных меню, горячие клавиши и намерение `command`; вкладка возвращает команду узла без преобразования. Чтение - `CommandId.byId`, неизвестный id - ошибка.
  - `ContextTarget` - поле `kind` со значением `ContextTarget.kind()`: `row`, `total`, `pastHeader`, `card`, `chart`, `preview` (тот же ключ, что у цели в дампе и сценариях: `row:<rowId>`), и поля записи.
- `seq` - монотонный номер эффекта в журнале сервера (кольцо на 1000 эффектов); ревизии форм (`view.revision`) монотонны; поле в фокусе не перезаписывается собственным эхом.
- Один `AppController` на процесс сервера: все вкладки показывают одно состояние; на сообщение отвечает первая ответившая вкладка, остальным уходит `alert.close`. Эффекты с полем `tab` (`contextMenu`, `reload`) выполняет только названная вкладка.
- Вкладка ничего не решает (правило R2): что показать, куда поставить точку наведения и перезагружаться ли, говорит ядро.

## Маршруты

| Маршрут | Запрос | Ответ |
|---|---|---|
| `GET /` | - | `index.html` (тексты до загрузки ядра вставлены сервером), `/app/*.js` |
| `GET /app/tokens.css` | - | `TokenCss.webCss()` |
| `GET /api/ui/bootstrap?tab=<uuid>` | - | `WebBootstrap` |
| `GET /api/ui/events?tab=<uuid>&after=<seq>` | long-poll до 25 с | `{"seq":N,"effects":[…]}` или `{"resync":true}`, если `after` старше кольца |
| `POST /api/ui/intent` | `{"tab":"<uuid>","afterSeq":N,"intent":{…}}` | `{"seq":N,"effects":[все эффекты с seq > afterSeq]}`; через 5 с - `503 {"busy":true}`, эффекты затем приходят через `events` |
| `POST /api/ui/query` | `{"type":…, …}` | `{"result":…}` или `{"stale":true,"rev":N}` |
| `POST /api/test/result`, `POST /api/test/dump` | результат шага, дамп | только с `--test-api` |

### bootstrap

```json
{
  "seq": 42,
  "client": "web",
  "testApi": false,
  "profile": {"kind": "WEB", "chooser": "SERVER_BROWSER", "nativeReplacePrompt": false, "toolkitVersion": ""},
  "screen": {
    "revision": 7,
    "windowTitle": "CashPrediction - Пример *",
    "menuBar": {"menus": [{"kind": "Submenu", "id": "file", "text": "Файл", "tooltip": "", "enabled": true, "children": []}]},
    "toolbar": {"items": [{"kind": "Button", "id": "tb.save", "command": "file.save", "glyphOrText": "Сохранить",
                           "tooltip": "Сохранить план в файл .md (Ctrl+S)", "enabled": true, "emphasis": "ACCENT"}]},
    "summary": {"visible": true, "cards": [], "unavailableText": ""},
    "table": {"revision": 7, "columns": [{"id": "date", "title": "Дата", "widthPx": 92, "grows": false, "align": "LEFT",
              "bold": false, "headerTooltip": "Фактическая дата события"}], "rowCount": 311,
              "selectedRowId": "", "scrollToRowId": "r1@2026-10-05", "placeholder": null},
    "chart": {"revision": 7},
    "status": {"segments": [{"id": "file", "text": "Файл: не сохранён", "tooltip": "…", "color": "TEXT_PRIMARY",
                             "grow": false, "visible": true}]},
    "mode": "TABLE"
  },
  "hotkeys": [{"command": "file.new", "chord": {"ctrl": false, "shift": true, "alt": true, "key": "N"},
               "scopes": ["MAIN"], "shown": true}],
  "windows": [{"seq": 40, "type": "form.open", "window": {"id": "w1", "…": "…"}}],
  "overlay": null,
  "texts": {"offline.title": "Нет связи с сервером CashPrediction", "details.show": "Подробности ▸",
            "details.hide": "Скрыть подробности ▾", "calendar.button.tip": "Выбрать дату в календаре"}
}
```

`overlay`: `null`, `STOPPED`, `CRASHED` или `RECOVERY_PENDING`. `texts` - ровно ключи с префиксом `offline.` (`WebBootstrap.OFFLINE_PREFIX`) и `WebBootstrap.CHROME_TEXT_KEYS`: тексты, которые вкладка рисует сама. Новый такой текст добавляется в список ядра, а не в JavaScript.

## Намерения (`intent.type`)

Главное окно (игнорируются, пока открыто модальное окно; `command` с источником `FORM` - если его форма верхняя модальная):

```json
{"type": "command", "command": "file.save", "args": {"rowId": "", "date": null, "cardId": "", "key": "", "value": ""}, "source": "TOOLBAR"}
{"type": "command", "command": "preview.adjust", "args": {"rowId": "", "date": null, "cardId": "", "key": "w2", "value": "3"}, "source": "FORM"}
{"type": "key", "chord": {"ctrl": true, "shift": false, "alt": false, "key": "S"}, "scope": "TABLE", "focusId": ""}
{"type": "key", "chord": {"ctrl": false, "shift": true, "alt": false, "key": "F10"}, "scope": "CARD", "focusId": "m3"}
{"type": "selectRow", "rowId": "r1@2026-10-05"}
{"type": "activateRow", "rowId": "r1@2026-10-05", "columnId": "income", "how": "DOUBLE_CLICK"}
{"type": "filterText", "text": "аренда"}
{"type": "sliderCommit", "itemId": "view.horizonSlider", "value": 24}
{"type": "spinnerCommit", "itemId": "whatIf.extra", "value": 5000}
{"type": "mainGeometry", "bounds": null, "maximized": false}
{"type": "menuHover", "itemId": "file.saveAs"}
{"type": "closeMain"}
```

- `command` - id спецификации (`CommandId.id()`); `source` - `MENU`, `TOOLBAR`, `CONTEXT_MENU`, `HOTKEY`, `MAIN`, `FORM` (контекстное меню внутри формы, в `args.key` - id окна формы). `SELFTEST` вкладка не отправляет.
- `key` - физическая клавиша (`N`, `DIGIT1`, `F2`, `F10`, `ENTER`, `ESCAPE`, `SPACE`, `DELETE`, `CONTEXT_MENU`, `ALT` - одиночное нажатие Alt); `scope` - `MAIN`, `TABLE`, `FILTER`, `TEXT_INPUT`, `CARD`, `POPUP`; `focusId` - id карточки для `CARD` (нужен Shift+F10 / Menu), id окна для `TEXT_INPUT` и `POPUP`, иначе пустая строка. Перед Enter в поле фильтра вкладка без задержки отправляет `filterText` с видимым текстом. Enter и Esc внутри окон форм сюда не приходят - это `formSubmit` и `formClose`.
- `how` - `CLICK` или `DOUBLE_CLICK`.

Окна форм (`windowId` - id из `form.open`):

```json
{"type": "formField", "windowId": "w1", "fieldId": "amount", "raw": "80 000,5", "committed": false, "clientRev": 12}
{"type": "formButton", "windowId": "w1", "buttonId": "ok"}
{"type": "formPreview", "windowId": "w1", "index": 2, "activated": true}
{"type": "formActivate", "windowId": "w3", "fieldId": "plans", "index": 0}
{"type": "formSubmit", "windowId": "w4", "fieldId": "path"}
{"type": "formBounds", "windowId": "w1", "bounds": {"x": 340, "y": 120, "width": 880, "height": 610}}
{"type": "formShown", "windowId": "w1"}
{"type": "formClose", "windowId": "w1"}
```

- `formPreview` → `FormSession.previewSelected` (индекс запоминается в `FormState.previewIndex`); `activated` - двойной щелчок.
- `formActivate` → `FormSession.fieldActivated`: двойной щелчок или Enter по элементу списка (открыть план §6.10; папка или файл в «Выборе файла» §6.21).
- `formSubmit` → `FormSession.fieldSubmitted`: Enter в однострочном поле, после `formField` с `committed: true`. У всплывающего окна быстрой правки без кнопок Enter идёт так же, Esc и щелчок вне окна - `formClose`.

Прочее:

```json
{"type": "alertButton", "alertId": "a3", "buttonId": "dontSave"}
{"type": "clientError", "message": "TypeError: x is undefined", "stack": "render-table.js:120"}
```

## Запросы (`query.type`)

```json
{"type": "contextMenu", "target": {"kind": "row", "rowId": "r1@2026-10-05"}}
{"type": "contextMenu", "target": {"kind": "chart", "x": 640.5, "y": 300, "width": 1180, "height": 520}}
{"type": "contextMenu", "target": {"kind": "preview", "windowId": "w2", "index": 3}}
{"type": "tooltip", "rev": 7, "index": 15, "columnId": "balance"}
{"type": "rows", "rev": 7, "from": 0, "count": 300}
{"type": "chartScene", "rev": 7, "w": 1180, "h": 520}
{"type": "chartHover", "rev": 7, "x": 640.5, "y": 300, "w": 1180, "h": 520}
{"type": "dayCard", "date": "2026-10-05"}
{"type": "sparkline", "cardId": "m3"}
{"type": "calendar", "month": "2026-10", "selected": "2026-10-05"}
```

Цели контекстного меню (`ContextTarget`): `row{rowId}`, `total{rowId}`, `pastHeader{rowId}`, `card{cardId}`, `chart{x,y,width,height}`, `preview{windowId,index}`.

`chartHover` → `UiIntents.chartHover`: `{"result": ChartHover}` (вертикаль, точка на линии баланса, карточка дня - все координаты считает ядро) или `{"result": null}`, если указатель вне области построения; устаревшая ревизия - `stale`.

Ответы:

```json
{"result": [{"kind": "Action", "id": "ctx.row.edit", "command": "row.edit", "args": {"rowId": "r1@2026-10-05", "date": null, "cardId": "", "key": "", "value": ""},
             "text": "Изменить…", "accel": {"ctrl": false, "shift": false, "alt": false, "key": "ENTER"}, "tooltip": "", "enabled": true},
            {"kind": "Separator", "id": "ctx.row.sep.1"}]}
{"result": [{"rowId": "r1@2026-10-05", "kind": "RULE", "cells": ["05.10.2026", "пн", "Зарплата", "", "80 000,00", "", "185 000,00", ""],
             "rowStyle": {"background": null, "text": "TEXT_PRIMARY", "bold": false, "italic": false},
             "cellStyles": {"income": {"text": "INCOME", "bold": false, "italic": false, "strike": false}},
             "leadingSpan": 1, "quickEditable": true}]}
{"stale": true, "rev": 8}
```

Узел слайдера меню несёт `currentLabel` - подпись до первого движения ползунка (горизонт больше 120 месяцев: ползунок на 120, подпись - настоящий горизонт):

```json
{"kind": "Slider", "id": "view.horizonSlider", "command": "view.horizonSlider", "min": 1, "max": 120, "majorTick": 12, "value": 120,
 "labels": ["1 месяц", "…"], "currentLabel": "15 лет", "tooltip": "…", "widthPx": 240}
```

## Эффекты (`effects[].type`)

Каждый эффект - объект `{"seq": N, "type": "…", …поля}`.

```json
{"seq": 43, "type": "screen", "revision": 8, "parts": {"STATUS": {"segments": []}, "TITLE": "CashPrediction - Пример"}}
{"seq": 44, "type": "form.open", "window": {"id": "w1", "ownerId": "main", "modal": true,
  "placement": {"ownerId": "main", "bounds": null, "anchor": null},
  "spec": {"formId": "ruleEditor", "windowType": "RULE_EDITOR", "purpose": "", "presentation": "DIALOG", "windowTitle": "Регулярная операция",
           "glyph": "↻", "width": 880, "modal": true, "resizable": true, "restorable": true, "pages": [], "buttons": [], "defaultButtonId": "ok"},
  "view": {"revision": 1, "page": 0, "header": "Новый регулярный доход", "fields": {}, "problem": {"severity": "NONE", "text": ""},
           "buttons": {}, "results": [], "preview": [], "details": "", "detailsExpanded": false}}}
{"seq": 45, "type": "form.view", "windowId": "w1", "view": {"revision": 2, "…": "…"}, "echoOf": {"tab": "7c1e…", "clientRev": 12}}
{"seq": 46, "type": "form.close", "windowId": "w1"}
{"seq": 47, "type": "form.front", "windowId": "w2"}
{"seq": 48, "type": "alert.open", "alertId": "a3", "spec": {"kind": "CONFIRMATION", "purpose": "unsavedChanges", "targetId": "",
  "windowTitle": "Несохранённые изменения", "glyph": "", "header": "Сохранить изменения в плане «Пример»?", "content": "Если не сохранить, изменения будут потеряны.",
  "details": "", "detailsExpanded": false, "minWidth": 460,
  "buttons": [{"id": "save", "text": "Сохранить", "role": "OK", "enabled": true, "tooltip": ""},
              {"id": "dontSave", "text": "Не сохранять", "role": "OTHER", "enabled": true, "tooltip": ""},
              {"id": "cancel", "text": "Отмена", "role": "CANCEL", "enabled": true, "tooltip": ""}],
  "defaultButtonId": "save", "restorable": false}}
{"seq": 49, "type": "alert.update", "alertId": "a3", "spec": {"…": "…"}}
{"seq": 50, "type": "alert.close", "alertId": "a3"}
{"seq": 51, "type": "contextMenu", "tab": "7c1e…", "target": {"kind": "card", "cardId": "m3"}, "items": [{"kind": "Action", "…": "…"}]}
{"seq": 52, "type": "focus", "target": "FILTER"}
{"seq": 53, "type": "reveal", "rowId": "r1@2026-10-05", "mode": "SELECT_AND_SCROLL"}
{"seq": 54, "type": "clipboard", "text": "05.10.2026\tпн\tЗарплата\t\t80 000,00\t\t185 000,00\t"}
{"seq": 55, "type": "inert", "value": true}
{"seq": 56, "type": "reload", "tab": "7c1e…"}
{"seq": 57, "type": "exit", "kind": "WEB_STOPPED", "title": "CashPrediction остановлен", "text": "Настройки сохранены, сеанс завершён корректно. Вкладку можно закрыть."}
{"seq": 58, "type": "test.step", "n": 3, "command": "menu file.save"}
```

- `contextMenu` - `UiPort.showContextMenu`: меню, открытое с клавиатуры (Shift+F10 / Menu). Показывает только вкладка `tab`: у левого нижнего края ячейки «Операция» выделенной строки или у левого нижнего края карточки. Выбранный пункт - намерение `command` с источником `CONTEXT_MENU`.
- `reload` - перезагрузить страницу вкладки `tab` (кнопка «Перезагрузить страницу» сообщения об ошибке JavaScript, §6.33, §10 №10).
- `alert.open.spec.glyph` - значок полосы заголовка (⟲ у «Восстановление сеанса») или пустая строка - значок типа.
- `focus.target`: `TABLE`, `FILTER`, `MENU_BAR`, `CHART`. `reveal.mode`: `SCROLL_TO_TOP`, `SELECT_AND_SCROLL`. `exit.kind`: `WEB_STOPPED`, `WEB_CRASHED`. `test.step` приходит только с `--test-api`.

## Правило эха

Вкладка нумерует свои правки поля (`clientRev`). Сервер отвечает `form.view` с `echoOf = {tab, clientRev}`. Вкладка применяет `FieldView.value` поля, только если виджет всё ещё показывает текст, отправленный с этим `clientRev`: поздний ответ не перезаписывает более новый ввод, а переформатирование при потере фокуса («80000» → «80 000,00») применяется. `FieldView.min`/`max` (если не `null`) меняют диапазон спиннера, не трогая введённый текст.

## Проверка

`UiJsonTest` (этап S2) проверяет запись и чтение каждого намерения, запроса, эффекта и модели; фикстуры лежат в `core/src/test/resources/ui-json`. Сервер проверяет `UiApiTest` на настоящем HTTP (этап S3).
