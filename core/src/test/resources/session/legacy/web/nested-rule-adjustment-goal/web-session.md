# Сессия CashPrediction (web)

- Состояние: running
- PID сервера: 38748
- Начата: 2026-09-13T22:28:15.843909300Z
- Сохранено: 2026-09-13T22:28:17.160299700Z
- Схема: 1

## Главное окно

- Вид: TABLE
- План:
- Несохранённые изменения: да (web-session.plan.md)
- Период: M12
- Фильтры: showIncome=true; showExpense=true; showOneTime=true; showSkipped=false; monthTotals=true; chartMarkers=true; chartBars=false; summaryPanel=true
- Строка поиска:
- Выделено:
- Границы: нет
- Развёрнуто: нет

## Открытые окна

### w1 - RULE_EDITOR (модальное, владелец: main)

- Контекст: mode=edit; ruleId=r1
- Границы: нет
- title: Зарплата
- kind: INCOME
- amount: 85000,00
- category:
- recurrenceKind: MONTHLY
- dayOfMonth: 5
- weekday: MONDAY
- monthDay: 01-05
- everyN: 1
- fromEnabled: false
- from:
- untilEnabled: false
- until:
- weekendPolicy: PREVIOUS_BUSINESS_DAY
- enabled: true
- note: Повышение

### w2 - ADJUSTMENT_EDITOR (модальное, владелец: w1)

- Контекст: ruleId=r1; originalDate=2026-10-05
- Границы: нет
- action: SKIP
- amount: 80000,00
- date: 2026-10-05
- note: Отпуск

### w3 - GOAL_CALCULATOR (немодальное, владелец: main)

- Контекст:
- Границы: нет
- target: 250000,00
- byDateEnabled: false
- byDate:
- extraSaving:
