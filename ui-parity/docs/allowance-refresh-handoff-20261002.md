# Свежие allowance references для MAIN

Готовый абсолютный manifest:
`C:/Users/Oscar/AppData/Local/Temp/cashprediction-allowance-refresh-1ff7d4eb-2e47-458e-ba9b-70beb37a0ade/evidence/evidence-manifest.json`.

Параметр общего прогона:
`-Dparity.allowance.evidence=C:/Users/Oscar/AppData/Local/Temp/cashprediction-allowance-refresh-1ff7d4eb-2e47-458e-ba9b-70beb37a0ade/evidence/evidence-manifest.json`.

SHA-256 manifest: `5AAFE55219086C4ED3937E4275C745576B2D1BC3BABCF4F14CF1FADF05DB2FF0`.
SHA frozen allowed-diffs: `DD6E38BFB88210BF4B81F22553282D36C5BD54C39C92570ED369349CB24731F0`.
Manifest содержит SHA каждого исходного JSON; observations не пересериализованы
и suffix вручную не восстановлен. Все native captures свежие.

## Выполненные проверки

- Native FX/Swing 2/2 PASS, 23,836 с, 25 + 26 подтверждённых CPS-строк.
- Web 9/9 PASS одним полным прогоном, 24,681 с.
- Isolated unit-тесты 22/22 PASS, 652 мс, без skips/failures.
- Supplemental 11 пар, 15 diffs, 14 поглощены, 1 rejected. Used 11,
  unused 34 -> 23 в отдельном пустом AllowedDiffs; это не полный unused-аудит.
- Все 29 JAR-копий в новом корне проверены: изменённых 0.
  Собственных Java/Chrome процессов с UUID корня осталось 0; desktop освобождён MAIN.

Native raw JSON действительно содержит `<node>\fx` / `<node>\swing`.
Добавлена защита в NativeAllowanceReferenceTest и два NativeReferenceFidelityTest:
стёртый suffix, чужой suffix, похожий fx-extra, сырой UUID и non-native client
отклоняются. Защитная assertion добавлена после capture; сам факт suffix
отдельно проверен непосредственно в новых JSON. Native GUI второй раз не запускался.

## Замороженные JAR и последующее отличие MAIN

| JAR | Исходный UTC | SHA-256 |
| --- | --- | --- |
| core | 2026-10-02T12:33:36.0162208Z | 65DCB61129DD154609B5A0E6A5B5C7909EE39F31ED6853CB058238F84D043C45 |
| ui-fx | 2026-10-02T12:44:13.6648962Z | A38827258874E093031E4ADDA4896CC10C56F56A5F3936842BC42E7EC9B6D9EB |
| ui-swing | 2026-10-02T12:48:39.4421446Z | 00DB322007BD5472AAE64E0ABE2B5D7A5C52F5004E0D9F1EB05E9D67D4EF44B6 |
| web | 2026-10-02T12:34:35.7113812Z | FD2DE7F8963118E1CEEBD5458666C007D7FE42730376A12CCF0AE81875E883DA |

Core уже имеет явный environment и prefix-only runner. В обоих клиентских
JAR через javap подтверждён трёхаргументный вызов SelfTestRunner.
Позднейший core MAIN 17:56 содержит, по сообщению MAIN, только точечный
DumpDiff fix допуска 4 px для schema placeholderButtons/id/x. Captures
получены до этого comparator-only изменения, не на старом suffix-lost runner.
Supplemental работает с tolerance 0; новый comparator будет применён MAIN
к тем же закреплённым captures при полной матрице. JAR в frozen-наборе
не заменялся на новую сборку, рендереры не пересобирались владельцем probes.

## Source/CPS evidence

Точные исполнявшиеся CPS:

- FX `native-runtime/parity/allowance-native-fx-18308998074057200456/native-allowances.cps`,
  SHA `6331CD039E92ECD094BA259A0CB679BFE2E73618C4EFCC765F0621262DC22DB9`.
- Swing `native-runtime/parity/allowance-native-swing-1134110091068018006/native-allowances.cps`,
  SHA `509B49A06D6FE67D157426884D5211C052A76FBE7DDE62880BD33A35EBC9AFAE`.

Текущие исходники ui-parity при handoff:

| Source | SHA-256 |
| --- | --- |
| audit/NativeAllowanceReferenceTest.java | A81C16B85AE1DF002A42F0EBBAA48D73A0EC4601580E679DA10ECCDB177FE58B |
| audit/WebAllowanceTest.java | A69BB30DB3DF236C1B78EF5E98F415B0F20314C8C1A6E8523168501B2DFD8727 |
| audit/AllowanceEvidenceRun.java | 7C15DD6C44941D78F7AF6E14944D9DC0F66A037DEB9199D6173428281423C64E |

Native source SHA относится к версии с добавленной post-capture assertion;
точные выполненные команды доказаны CPS SHA и selftest.log, не заявлением
о тождестве изменённого после capture Java-файла.

## Непоглощённый gate

`/alerts/lastSnapshot/details` FX/Swing остаётся RED, CLI exit 1.
В свежих actual details registry suffix теперь корректен, но различаются:

- XML paths `session-fx.xml` / `session-swing.xml`;
- JSON payload client `fx` / `swing`;
- savedAt `2026-10-02T<time>.758411200Z` / `2026-10-02T<time>.900103Z`;
- main.bounds.y 310 / 465 (другие перечисленные размеры совпали).

Текущее правило desktop №8 разрешает только client suffix registry node.
Допуски не расширены, payload не маскируется. В manifest сохранена
отклонённая пара: подключение к full18 не обещает GREEN. Остальные 11
записей из прежних 12 missing действительно поглотили diffs, не отмечены вручную.
Fresh native absent и OS replacement prompt всё ещё не доказаны; mixed-state
native-prepared/Web-absent явно обозначена и не выдаётся за одинаковую fixture.

Подробный аудит: `evidence/evidence-audit.json`; компактный без payload:
`evidence/evidence-summary.json`; отчёты JUnit: `native-reports`, `web-reports`,
`unit-reports` в указанном новом корне. Новые captures находятся в
`native-runtime` и `web-runtime`, старые корни в этот manifest не включены.

Последние изменения владельца: только NativeAllowanceReferenceTest.java,
новый NativeReferenceFidelityTest.java и этот handoff. Никаких Maven/shared
classes/core/golden/collector/renderer/commit/push изменений.
