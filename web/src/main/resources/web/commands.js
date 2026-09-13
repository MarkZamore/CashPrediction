/**
 * @file Команды приложения — одно место, откуда меню, панель инструментов, горячие клавиши и контекстные меню
 * вызывают действия (реестр commands в store.js). Здесь же фабрики восстанавливаемых окон по WindowType —
 * web-аналог WindowFactory ядра: по состоянию окна с сервера открывается такой же диалог с теми же полями.
 *
 * Ошибки команд показываются диалогом 16 «Ошибка» (Alert ERROR со стеком сервера в подробностях).
 */

import {
  h, ruDate, canonicalMoney, displayMoney, parseMoney, formatPlain, downloadUrl, downloadText, copyText,
} from './util.js';
import { planNameProblem } from './util.js';
import { api, apiUrl, ApiError } from './api.js';
import { store, commands, windowFactories } from './store.js';
import {
  alertDialog, errorAlert, textInputDialog, choiceDialog, fileChooserDialog, buttonType, ButtonRole, ButtonTypes,
} from './dialogs.js';
import {
  ruleEditor, oneTimeEditor, adjustmentEditor, confirmDeleteRule, confirmDeleteOneTime, occurrenceAmount,
} from './editors.js';
import { newPlanWizard, planSettingsDialog, CURRENCIES } from './plan-dialogs.js';
import { goalCalculator, csvExportDialog } from './tools.js';
import { toast, QuickEditPopup } from './popup.js';
import { showStoppedScreen, showServerDown } from './session.js';
import { HOTKEYS_TEXT } from './app-menu.js';
import { chartSvgMarkup } from './chart.js';
import { abandonWindow } from './windows.js';

/** Значение пункта «Из файла…» в списке планов. */
const FROM_FILE = '__file__';

/** Значение пункта «другая…» в списке валют. */
const OTHER_CURRENCY = '__other__';

/**
 * Оборачивает команду: исключение показывается сообщением об ошибке, а не теряется.
 * @param {Function} fn асинхронная команда
 * @returns {Function} команда с обработкой ошибок
 */
export function run(fn) {
  return async (...args) => {
    try {
      return await fn(...args);
    } catch (e) {
      // Сервер недоступен — экран «нет связи» уже показан обработчиком api.js.
      if (e instanceof ApiError && e.status === 0) return undefined;
      await errorAlert(e);
      return undefined;
    }
  };
}

/** @returns {object} текущий вид (ViewState в JSON) */
function view() {
  return store.state.viewState;
}

/**
 * Применяет ответ открытия плана и показывает диагностику чтения (диалог 15), если есть замечания.
 * @param {object} res ответ сервера с полями diagnostics и hasWarnings
 * @param {string} what что делалось («Открытие», «Импорт»)
 * @returns {Promise<void>} завершение
 */
async function applyWithWarnings(res, what) {
  store.set(res);
  if (res.hasWarnings && res.diagnostics && res.diagnostics.length) {
    // JavaFX: Alert(WARNING) + expandableContent → Swing: SwingAlert.warning → Web: <dialog class="alert alert-warning">
    await alertDialog({
      type: 'WARNING',
      title: 'Диагностика',
      header: `${what}: план прочитан с замечаниями (${res.diagnostics.length})`,
      content: 'Нераспознанные строки не потеряны: они сохранятся в файле как есть. Подробности ниже.',
      details: res.diagnostics.map((d) => d.text).join('\n'),
    });
  }
}

/**
 * Предлагает сохранить несохранённые изменения перед заменой плана.
 * JavaFX: Alert(CONFIRMATION) [Сохранить][Не сохранять][Отмена] → Swing: SwingAlert → Web: <dialog class="alert">
 * @param {string} [action] что пользователь собирается сделать
 * @returns {Promise<boolean>} можно ли продолжать
 */
export async function confirmDiscard(action = '') {
  if (!store.state || !store.state.dirty) return true;
  const SAVE = buttonType('Сохранить', 'save', ButtonRole.OK_DONE);
  const DONT_SAVE = buttonType('Не сохранять', 'discard', ButtonRole.OTHER);
  const value = await alertDialog({
    type: 'CONFIRMATION',
    title: 'Несохранённые изменения',
    header: `Сохранить изменения в плане «${store.state.plan.name}»?`,
    content: `Если не сохранить, изменения будут потеряны${action ? ` (${action})` : ''}.`,
    buttons: [SAVE, DONT_SAVE, ButtonTypes.CANCEL],
  });
  if (value === 'save') return savePlan(false);
  return value === 'discard';
}

/**
 * Сохраняет план: в его файл или, для нового плана, в CashMemory под его именем. Конфликты — диалог 18.
 * @param {boolean} [overwrite=false] пользователь уже согласился перезаписать
 * @returns {Promise<boolean>} сохранён ли план
 */
export async function savePlan(overwrite = false) {
  try {
    const res = await api.post('/api/plans/save', { overwrite });
    store.set(res);
    toast(`План сохранён: ${(res.document && res.document.fileName) || res.savedPath}`, 'success', 3000);
    return true;
  } catch (e) {
    if (e instanceof ApiError && e.status === 409) {
      if (e.conflict === 'externalChange') return resolveExternalChange(e.message);
      if (e.conflict === 'exists') {
        const OVERWRITE = buttonType('Перезаписать', 'ok', ButtonRole.OK_DONE);
        const value = await alertDialog({
          type: 'CONFIRMATION', title: 'Файл уже существует', header: e.message, buttons: [OVERWRITE, ButtonTypes.CANCEL],
        });
        return value === 'ok' ? savePlan(true) : false;
      }
    }
    throw e;
  }
}

/**
 * Диалог 18 «Файл изменён снаружи»: перезаписать или перечитать.
 * JavaFX: Alert(WARNING) + ButtonType «Перезаписать»/«Перечитать» → Swing: SwingAlert → Web: <dialog class="alert">
 * @param {string} message текст сервера
 * @returns {Promise<boolean>} сохранён ли план
 */
async function resolveExternalChange(message) {
  const OVERWRITE = buttonType('Перезаписать', 'overwrite', ButtonRole.OK_DONE);
  const RELOAD = buttonType('Перечитать', 'reload', ButtonRole.OTHER);
  const value = await alertDialog({
    type: 'WARNING',
    title: 'Файл изменён снаружи',
    header: 'Файл плана изменён другой программой после загрузки',
    content: `${message}\n«Перезаписать» сохранит ваш вариант, «Перечитать» загрузит файл с диска (ваши изменения пропадут).`,
    buttons: [OVERWRITE, RELOAD, ButtonTypes.CANCEL],
  });
  if (value === 'overwrite') return savePlan(true);
  if (value === 'reload') {
    await applyWithWarnings(await api.post('/api/plans/reload'), 'Перечитывание');
    toast('План перечитан с диска', 'info');
  }
  return false;
}

/**
 * «Сохранить как…»: серверный обозреватель (FileChooser) и POST /api/plans/save-as.
 * @param {string} name имя плана (файла без .md)
 * @param {string} folder папка
 * @param {boolean} overwrite перезаписать существующий файл
 * @returns {Promise<boolean>} сохранён ли план
 */
async function saveAsTo(name, folder, overwrite) {
  try {
    const res = await api.post('/api/plans/save-as', { name, folder, overwrite });
    store.set(res);
    toast(`План сохранён: ${(res.document && res.document.fileName) || res.savedPath}`, 'success', 3500);
    return true;
  } catch (e) {
    if (e instanceof ApiError && e.status === 409 && e.conflict === 'exists') {
      const OVERWRITE = buttonType('Перезаписать', 'ok', ButtonRole.OK_DONE);
      const value = await alertDialog({
        type: 'CONFIRMATION', title: 'Файл уже существует', header: e.message, buttons: [OVERWRITE, ButtonTypes.CANCEL],
      });
      return value === 'ok' ? saveAsTo(name, folder, true) : false;
    }
    throw e;
  }
}

/**
 * Диалог 10 «Открыть план»: список планов CashMemory (и папки сеанса) плюс «Из файла…».
 * JavaFX: ChoiceDialog<String> → Swing: JOptionPane.showInputDialog(selectionValues) → Web: <dialog> с <select>
 * @param {object|null} [existing] состояние окна CHOICE с сервера
 * @returns {Promise<void>} завершение
 */
async function openPlanChoice(existing = null) {
  let list;
  try {
    list = await api.get('/api/plans');
  } catch (e) {
    if (existing) await abandonWindow(existing);
    throw e;
  }
  const choices = list.plans.map((p) => ({
    value: `name:${p.name}`, text: `${p.name} — изменён ${p.modifiedText}${p.current ? ' (открыт)' : ''}`,
  }));
  for (const p of list.extraPlans || []) {
    choices.push({ value: `path:${p.path}`, text: `${p.name} — папка ${list.extraFolder}` });
  }
  choices.push({ value: FROM_FILE, text: 'Из файла…' });
  let opened = null;
  const value = await choiceDialog({
    title: 'Открыть план',
    header: list.plans.length ? `Планы в папке CashMemory: ${list.folder}` : `В папке CashMemory пока нет планов: ${list.folder}`,
    label: 'План',
    choices,
    value: choices[0].value,
    listSize: Math.min(10, Math.max(4, choices.length)),
    purpose: 'openPlan',
    existing,
    okText: 'Открыть',
    onSubmit: async (v) => {
      if (v === FROM_FILE) return;
      opened = v.startsWith('path:')
        ? await api.post('/api/plans/open', { path: v.slice(5) })
        : await api.post('/api/plans/open', { name: v.slice(5) });
    },
  });
  if (value === FROM_FILE) {
    await openFromFile();
  } else if (opened) {
    await applyWithWarnings(opened, 'Открытие');
    toast(`Открыт план «${opened.plan.name}»`, 'success', 2500);
  }
}

/**
 * «Из файла…»: серверный обозреватель файлов *.md.
 * JavaFX: FileChooser.showOpenDialog → Swing: JFileChooser.showOpenDialog → Web: fileChooserDialog (GET /api/fs?mode=md)
 * @returns {Promise<void>} завершение
 */
async function openFromFile() {
  const chosen = await fileChooserDialog({ mode: 'md', title: 'Открыть план из файла', okText: 'Открыть' });
  if (!chosen) return;
  const res = await api.post('/api/plans/open', { path: chosen.path });
  await applyWithWarnings(res, 'Открытие');
  toast(`Открыт план из файла ${chosen.path}`, 'success', 3000);
}

/**
 * Диалог 9 «Переименовать план».
 * JavaFX: TextInputDialog → Swing: JOptionPane.showInputDialog → Web: <dialog> с <input>
 * @param {object|null} [existing] состояние окна TEXT_INPUT с сервера
 * @returns {Promise<string|null>} новое имя
 */
function renameDialog(existing = null) {
  return textInputDialog({
    title: 'Переименовать план',
    header: 'Новое название плана. Если план сохранён, его файл в CashMemory тоже будет переименован.',
    label: 'Название',
    value: store.state.plan.name,
    purpose: 'rename',
    existing,
    // Те же правила, что PlanValidator.checkPlanName: имя плана — это имя файла в CashMemory.
    validate: (t) => planNameProblem(t) || null,
    onSubmit: async (value, d) => {
      const res = await api.post('/api/plans/rename', { name: value, windowId: await d.windowIdReady() });
      d.markServerClosed();
      store.set(res);
      toast(`План переименован: «${res.plan.name}»`, 'success', 3000);
    },
  });
}

/**
 * Диалог 7 «Сверить баланс»: фактический остаток на сегодня (PlanDocument.reconcile).
 * JavaFX: TextInputDialog → Swing: JOptionPane.showInputDialog → Web: <dialog> с <input>
 * @param {object|null} [existing] состояние окна TEXT_INPUT с сервера
 * @returns {Promise<string|null>} введённая сумма
 */
function reconcileDialog(existing = null) {
  const s = store.state;
  return textInputDialog({
    title: 'Сверить баланс',
    header: `Сколько денег у вас на самом деле сегодня, ${ruDate(s.today)}? По прогнозу — ${s.nowBalanceText || '?'} ${s.plan.currency}. `
      + 'Разница будет учтена в плане.',
    label: 'Фактический баланс',
    value: s.nowBalance || '',
    inputMode: 'decimal',
    purpose: 'reconcile',
    existing,
    toCanonical: canonicalMoney,
    toDisplay: displayMoney,
    validate: (t) => (parseMoney(t) === null ? 'Введите сумму, например 177 000,00' : null),
    onSubmit: async (value, d) => {
      const res = await api.post('/api/reconcile', { balance: value, windowId: await d.windowIdReady() });
      d.markServerClosed();
      store.set(res);
      toast('Баланс сверен', 'success', 2500);
    },
  });
}

/**
 * Диалог 8 «Валюта»: список популярных валют и «другая…».
 * JavaFX: ChoiceDialog<String> → Swing: JOptionPane.showInputDialog(selectionValues) → Web: <dialog> с <select>
 * @param {object|null} [existing] состояние окна CHOICE с сервера
 * @returns {Promise<void>} завершение
 */
async function currencyDialog(existing = null) {
  const current = store.state.plan.currency;
  const choices = CURRENCIES.map((c) => ({ value: c, text: c }));
  choices.push({ value: OTHER_CURRENCY, text: 'другая…' });
  const value = await choiceDialog({
    title: 'Валюта',
    header: `Обозначение валюты плана «${store.state.plan.name}» (суммы не пересчитываются).`,
    label: 'Валюта',
    choices,
    value: CURRENCIES.includes(current) ? current : OTHER_CURRENCY,
    purpose: 'currency',
    existing,
    onSubmit: async (v, d) => {
      if (v === OTHER_CURRENCY) return;
      const res = await api.put('/api/plan/settings', {
        fields: { currency: v }, description: 'Валюта', windowId: await d.windowIdReady(),
      });
      d.markServerClosed();
      store.set(res);
    },
  });
  if (value === OTHER_CURRENCY) {
    await customCurrencyDialog(null);
  }
}

/**
 * «Другая валюта»: ввод обозначения.
 * JavaFX: TextInputDialog → Swing: JOptionPane.showInputDialog → Web: <dialog> с <input>
 * @param {object|null} [existing] состояние окна TEXT_INPUT с сервера
 * @returns {Promise<string|null>} обозначение
 */
function customCurrencyDialog(existing = null) {
  const current = store.state.plan.currency;
  return textInputDialog({
    title: 'Другая валюта',
    header: 'Обозначение валюты — до 10 символов, например ¥, CHF или «руб.»',
    label: 'Обозначение',
    value: CURRENCIES.includes(current) ? '' : current,
    purpose: 'customCurrency',
    existing,
    validate: (t) => (!t.trim() ? 'Введите обозначение' : [...t.trim()].length > 10 ? 'Не больше 10 символов' : null),
    onSubmit: async (value, d) => {
      const res = await api.put('/api/plan/settings', {
        fields: { currency: value.trim() }, description: 'Валюта', windowId: await d.windowIdReady(),
      });
      d.markServerClosed();
      store.set(res);
    },
  });
}

/**
 * «Актуализировать на сегодня…»: предпросмотр и подтверждение (восстанавливаемое окно ALERT, purpose=actualize).
 * JavaFX: Alert(CONFIRMATION) → Swing: SwingAlert.confirm → Web: <dialog class="alert">
 * @param {object|null} [existing] состояние окна с сервера
 * @returns {Promise<void>} завершение
 */
async function actualizeDialog(existing = null) {
  let preview;
  try {
    preview = await api.get('/api/actualize/preview');
  } catch (e) {
    if (existing) await abandonWindow(existing);
    throw e;
  }
  if (!preview.needed) {
    if (existing) await abandonWindow(existing);
    await alertDialog({
      type: 'INFORMATION', title: 'Актуализация', header: 'План уже начинается сегодня', content: `Дата начала — ${preview.startDateText}.`,
    });
    return;
  }
  const ACTUALIZE = buttonType('Актуализировать', 'ok', ButtonRole.OK_DONE);
  await alertDialog({
    type: 'CONFIRMATION',
    title: 'Актуализировать на сегодня',
    header: `Перенести начало плана «${preview.plan}» с ${preview.startDateText} на ${preview.todayText}?`,
    content: `Начальный баланс станет ${preview.balanceText} — столько должно быть на начало сегодняшнего дня по прогнозу `
      + '(без «что-если»). Действие можно отменить (Ctrl+Z).',
    buttons: [ACTUALIZE, ButtonTypes.CANCEL],
    windowType: 'ALERT',
    context: { purpose: 'actualize' },
    existing,
    onButton: async (value, d) => {
      if (value !== 'ok') return true;
      const res = await api.post('/api/actualize', { windowId: await d.windowIdReady() });
      d.markServerClosed();
      store.set(res);
      toast('План актуализирован на сегодня', 'success', 3000);
      return true;
    },
  });
}

/**
 * «Что-если → Применить к плану…»: подтверждение (ALERT, purpose=applyWhatIf).
 * JavaFX: Alert(CONFIRMATION) → Swing: SwingAlert.confirm → Web: <dialog class="alert">
 * @param {object|null} [existing] состояние окна с сервера
 * @returns {Promise<string>|false} значение кнопки или false, если сценарий не включён
 */
function applyWhatIfDialog(existing = null) {
  const w = view().whatIf || {};
  if (!w.active) {
    if (!existing) toast('Сценарий «что-если» не включён', 'info');
    return false;
  }
  const APPLY = buttonType('Применить', 'ok', ButtonRole.OK_DONE);
  const parts = [];
  if (!isOne(w.incomeFactor)) parts.push(`доходы × ${w.incomeFactor}`);
  if (!isOne(w.expenseFactor)) parts.push(`расходы × ${w.expenseFactor}`);
  if (parseMoney(w.extraMonthlySaving)) parts.push(`откладывать ${w.extraMonthlySavingText} в месяц`);
  return alertDialog({
    type: 'CONFIRMATION',
    title: 'Применить «что-если» к плану',
    header: `Изменить суммы операций плана: ${parts.join(', ')}?`,
    content: 'Суммы регулярных и разовых операций будут пересчитаны, сценарий сбросится. Действие можно отменить (Ctrl+Z).',
    buttons: [APPLY, ButtonTypes.CANCEL],
    windowType: 'ALERT',
    context: { purpose: 'applyWhatIf' },
    existing,
    onButton: async (value, d) => {
      if (value !== 'ok') return true;
      const res = await api.post('/api/what-if/apply', { windowId: await d.windowIdReady() });
      d.markServerClosed();
      store.set(res);
      toast('Сценарий применён к плану', 'success', 3000);
      return true;
    },
  });
}

/**
 * Коэффициент «что-если» равен единице?
 * @param {string|number} factor коэффициент ("1", "0.90")
 * @returns {boolean} да, если коэффициент не меняет суммы
 */
function isOne(factor) {
  return Number(String(factor ?? '1').replace(',', '.')) === 1;
}

/**
 * Быстрая правка суммы события у ячейки таблицы (восстанавливаемое окно QUICK_EDIT_POPUP).
 * JavaFX: Popup (QuickEditPopup) → Swing: PopupFactory.getPopup → Web: absolute div с <form>
 * @param {object} options параметры
 * @param {string} options.ruleId идентификатор правила
 * @param {string} options.originalDate номинальная дата события
 * @param {HTMLElement|null} [options.anchor] ячейка таблицы
 * @param {object|null} [options.existing] состояние окна с сервера
 * @returns {boolean} открыт ли редактор
 */
function openQuickEdit({ ruleId, originalDate, anchor = null, existing = null }) {
  const rule = store.rule(ruleId);
  if (!rule) return false;
  const adjustment = store.adjustment(ruleId, originalDate);
  const rowId = `${ruleId}@${originalDate}`;
  const cell = anchor || document.querySelector(`tr[data-row-id="${CSS.escape(rowId)}"] .amount-cell:not(:empty)`);
  const popup = new QuickEditPopup({
    ruleId,
    originalDate,
    amount: occurrenceAmount(ruleId, originalDate),
    anchor: cell,
    existing,
    title: `«${rule.title}» ${ruDate(originalDate)} — новая сумма`,
    onSubmit: async (amountText, windowId) => {
      const fields = { ruleId, originalDate, action: 'CHANGE_AMOUNT', amount: amountText, note: adjustment ? adjustment.note || '' : '' };
      if (adjustment && (adjustment.action === 'MOVE_DATE' || adjustment.action === 'REPLACE')) {
        // Событие уже перенесено: сохраняем перенос и меняем сумму.
        fields.action = 'REPLACE';
        fields.date = adjustment.date;
      }
      const res = await api.put('/api/adjustments', { fields, windowId });
      store.set(res);
      toast('Сумма события изменена (Ctrl+Z — отменить)', 'success', 2500);
    },
  });
  popup.open();
  return true;
}

/**
 * Выбранная строка или переданная.
 * @param {object} [row] строка прогноза
 * @returns {object|null} строка
 */
function rowOrSelected(row) {
  return row && row.rowId ? row : store.selectedRow();
}

/**
 * Диалог 13 «Папка CashMemory»: где лежат данные и выбор другой папки для чтения планов на этот сеанс.
 * JavaFX: DirectoryChooser → Swing: JFileChooser(DIRECTORIES_ONLY) → Web: fileChooserDialog (GET /api/fs?mode=dirs)
 * @returns {Promise<void>} завершение
 */
async function cashMemoryFolder() {
  const list = await api.get('/api/plans');
  const CHOOSE = buttonType('Выбрать другую папку…', 'choose', ButtonRole.LEFT);
  const OPEN = buttonType('Открыть план…', 'open', ButtonRole.OTHER);
  const RESET = buttonType('Забыть папку сеанса', 'reset', ButtonRole.OTHER);
  const lines = [
    h('p', {}, 'Все планы (.md), настройки и снимки сессии хранятся в папке CashMemory рядом с программой:'),
    h('p', {}, h('code', { class: 'path', text: list.folder })),
    h('p', { class: 'muted', text: `Планов в ней: ${list.plans.length}. Программа ничего не записывает за пределами этой папки.` }),
  ];
  if (list.extraFolder) {
    lines.push(h('p', {}, 'На этот сеанс также показываются планы из папки: ', h('code', { class: 'path', text: list.extraFolder })));
    lines.push(h('ul', { class: 'plain-list' }, (list.extraPlans || []).map((p) => h('li', { text: `${p.name} — ${p.modifiedText}` }))));
  }
  const buttons = list.extraFolder ? [CHOOSE, OPEN, RESET, ButtonTypes.CLOSE] : [CHOOSE, OPEN, ButtonTypes.CLOSE];
  const value = await alertDialog({
    type: 'INFORMATION', title: 'Папка CashMemory', header: 'Где хранятся данные CashPrediction', content: h('div', {}, lines), buttons,
  });
  if (value === 'open') {
    if (await confirmDiscard('открытие другого плана')) await openPlanChoice(null);
  } else if (value === 'reset') {
    await api.post('/api/plans/folder', { folder: '' });
    toast('Папка сеанса забыта: показываются только планы CashMemory', 'info');
  } else if (value === 'choose') {
    const chosen = await fileChooserDialog({
      mode: 'dirs', title: 'Папка с планами', startPath: list.extraFolder || list.folder, okText: 'Выбрать папку',
    });
    if (!chosen) return;
    const res = await api.post('/api/plans/folder', { folder: chosen.path });
    if (res.extraProblem) {
      await alertDialog({ type: 'WARNING', title: 'Папка с планами', header: 'Папку не удалось прочитать', content: res.extraProblem });
      return;
    }
    const count = res.extraFolder ? (res.extraPlans || []).length : res.plans.length;
    if (!count) {
      await alertDialog({
        type: 'INFORMATION', title: 'Папка с планами', header: 'В выбранной папке нет планов (.md)', content: chosen.path,
      });
      return;
    }
    toast(`Найдено планов: ${count}. Они появились в «Файл → Открыть…» до конца сеанса.`, 'success', 4000);
    if (await confirmDiscard('открытие плана из папки')) await openPlanChoice(null);
  }
}

/**
 * «Выход»: снимок, вопрос о несохранённых изменениях, корректная остановка сервера.
 * @returns {Promise<void>} завершение
 */
async function exitApp() {
  await api.post('/api/session/snapshot').catch(() => {});
  if (store.state.dirty) {
    const SAVE = buttonType('Сохранить', 'save', ButtonRole.OK_DONE);
    const DONT_SAVE = buttonType('Не сохранять', 'discard', ButtonRole.OTHER);
    const value = await alertDialog({
      type: 'CONFIRMATION',
      title: 'Выход',
      header: 'Сохранить изменения в плане?',
      content: `В плане «${store.state.plan.name}» есть несохранённые изменения.`,
      buttons: [SAVE, DONT_SAVE, ButtonTypes.CANCEL],
    });
    if (value !== 'save' && value !== 'discard') return;
    if (value === 'save' && !(await savePlan(false))) return;
  }
  await api.post('/api/shutdown');
  showStoppedScreen();
}

/**
 * Диалог 15 «Диагностика»: PlanValidator, замечания чтения файла и предупреждения прогноза.
 * JavaFX: Alert(WARNING) + expandableContent → Swing: SwingAlert.warning → Web: <dialog class="alert alert-warning">
 * @returns {Promise<void>} завершение
 */
async function diagnostics() {
  const d = await api.get('/api/diagnostics');
  const lines = [
    ...d.validation.map((x) => x.text),
    ...d.load.map((x) => `Чтение файла: ${x.text}`),
    ...d.warnings.map((x) => `Прогноз: ${x.text}`),
  ];
  if (d.forecastError) lines.push(`Прогноз не построен: ${d.forecastError}`);
  if (!lines.length) {
    await alertDialog({ type: 'INFORMATION', title: 'Проверка плана', header: 'Проблем не найдено', content: 'План корректен, прогноз строится без предупреждений.' });
    return;
  }
  await alertDialog({
    type: 'WARNING',
    title: 'Проверка плана',
    header: `Найдено замечаний: ${lines.length}`,
    content: lines.slice(0, 3).join('\n') + (lines.length > 3 ? '\n…' : ''),
    details: lines.join('\n'),
    detailsLabel: 'Все замечания',
  });
}

/**
 * Регистрирует команды в реестре store.commands.
 */
export function registerCommands() {
  Object.assign(commands, {
    // ---------------------------------------------------------------- Файл
    newPlan: run(async () => {
      if (await confirmDiscard('создание нового плана')) await newPlanWizard();
    }),
    newPlanStartup: run(() => newPlanWizard()),
    openPlan: run(async () => {
      if (await confirmDiscard('открытие другого плана')) await openPlanChoice(null);
    }),
    openFromFile: run(async () => {
      if (await confirmDiscard('открытие другого плана')) await openFromFile();
    }),
    openSample: run(async () => {
      if (!(await confirmDiscard('открытие примера'))) return;
      store.set(await api.post('/api/plans/sample'));
      toast('Открыт пример плана. Он не сохранён: Ctrl+S — сохранить в CashMemory.', 'success', 4500);
    }),
    openRecent: run(async (fileName) => {
      if (!(await confirmDiscard('открытие другого плана'))) return;
      await applyWithWarnings(await api.post('/api/plans/open', { path: fileName }), 'Открытие');
    }),
    importPlan: run(async () => {
      if (!(await confirmDiscard('импорт плана'))) return;
      // JavaFX: FileChooser.showOpenDialog → Swing: JFileChooser → Web: <input type="file" accept=".md">
      const input = h('input', { type: 'file', accept: '.md,text/markdown,text/plain', style: 'display:none' });
      document.body.append(input);
      const file = await new Promise((resolve) => {
        input.addEventListener('change', () => resolve(input.files && input.files[0] ? input.files[0] : null), { once: true });
        input.addEventListener('cancel', () => resolve(null), { once: true });
        input.click();
      });
      input.remove();
      if (!file) return;
      const res = await api.post('/api/plans/import', { name: file.name, text: await file.text() });
      await applyWithWarnings(res, 'Импорт');
      toast(`План «${res.plan.name}» импортирован. Сохраните его в CashMemory (Ctrl+S).`, 'success', 5000);
    }),
    save: run(() => savePlan(false)),
    saveAs: run(async () => {
      // JavaFX: FileChooser.showSaveDialog → Swing: JFileChooser.showSaveDialog → Web: fileChooserDialog (save)
      const chosen = await fileChooserDialog({
        mode: 'md', save: true, title: 'Сохранить план как', fileName: store.state.plan.name, okText: 'Сохранить',
      });
      if (chosen) await saveAsTo(chosen.name, chosen.folder, false);
    }),
    downloadPlan: run(() => {
      // Скачивание текста плана — «Сохранить как» на компьютер пользователя через браузер.
      downloadUrl(apiUrl('/api/plans/download', { name: '' }));
    }),
    rename: run(() => renameDialog(null)),
    toggleAutosave: run(async () => {
      const res = await api.put('/api/settings', { autosave: !store.state.settings.autosave });
      store.set(res);
      toast(res.settings.autosave ? 'Автосохранение включено' : 'Автосохранение выключено', 'info', 2500);
    }),
    exportCsv: run(() => csvExportDialog()),
    saveChartSvg: run(() => {
      const markup = chartSvgMarkup(store.state, 1200, 560);
      downloadText(markup, `${store.state.plan.name} — график.svg`, 'image/svg+xml;charset=utf-8');
      toast('График передан браузеру для сохранения (SVG)', 'success', 3000);
    }),
    cashMemoryFolder: run(cashMemoryFolder),
    exit: run(exitApp),

    // ---------------------------------------------------------------- Правка
    addIncome: run(() => ruleEditor({ mode: 'create', kind: 'INCOME' })),
    addExpense: run(() => ruleEditor({ mode: 'create', kind: 'EXPENSE' })),
    addOneTime: run((date) => oneTimeEditor({ mode: 'create', date: typeof date === 'string' ? date : '' })),
    editSelected: run((row) => {
      const r = rowOrSelected(row);
      if (!r) return toast('Выберите строку в таблице', 'info');
      if (r.origin === 'RULE') return ruleEditor({ mode: 'edit', ruleId: r.ruleId });
      if (r.origin === 'ONE_TIME') return oneTimeEditor({ mode: 'edit', txId: r.txId });
      if (r.origin === 'START') return planSettingsDialog();
      return toast('Строка «что-если» — часть сценария, а не операция плана', 'info');
    }),
    deleteSelected: run((row) => {
      const r = rowOrSelected(row);
      if (r && r.origin === 'RULE') return confirmDeleteRule(r.ruleId);
      if (r && r.origin === 'ONE_TIME') return confirmDeleteOneTime(r.txId);
      return toast('Выберите строку регулярной или разовой операции', 'info');
    }),
    adjustSelected: run((row) => {
      const r = rowOrSelected(row);
      if (!r || r.origin !== 'RULE') return toast('Выберите событие регулярной операции в таблице', 'info');
      return adjustmentEditor({ ruleId: r.ruleId, originalDate: r.originalDate });
    }),
    resetAdjustment: run(async (row) => {
      const r = rowOrSelected(row);
      if (!r || r.origin !== 'RULE' || !store.adjustment(r.ruleId, r.originalDate)) {
        return toast('У выбранного события нет корректировки', 'info');
      }
      store.set(await api.del('/api/adjustments', { ruleId: r.ruleId, originalDate: r.originalDate }));
      return toast('Событие возвращено к правилу', 'success', 2500);
    }),
    skipOccurrence: run(async (row) => {
      const r = rowOrSelected(row);
      if (!r || r.origin !== 'RULE') return toast('Пропустить можно только событие регулярной операции', 'info');
      const adj = store.adjustment(r.ruleId, r.originalDate);
      store.set(await api.put('/api/adjustments', {
        fields: { ruleId: r.ruleId, originalDate: r.originalDate, action: 'SKIP', note: adj ? adj.note || '' : '' },
      }));
      return toast(view().showSkipped ? 'Событие пропущено' : 'Событие пропущено и скрыто (Вид → Пропущенные события)', 'success', 3000);
    }),
    toggleRuleEnabled: run(async (row) => {
      const r = rowOrSelected(row);
      const rule = r && r.ruleId ? store.rule(r.ruleId) : null;
      if (!rule) return toast('Выберите событие регулярной операции', 'info');
      store.set(await api.post(`/api/rules/${encodeURIComponent(rule.id)}/enabled`, { enabled: !rule.enabled }));
      return toast(rule.enabled ? `Правило «${rule.title}» отключено` : `Правило «${rule.title}» включено`, 'success', 2500);
    }),
    goToRule: run((row) => {
      const r = rowOrSelected(row);
      if (!r || !r.ruleId) return toast('У строки нет регулярной операции', 'info');
      return ruleEditor({ mode: 'edit', ruleId: r.ruleId });
    }),
    copyRow: run(async (row) => {
      const r = rowOrSelected(row);
      if (!r) return;
      await copyText([r.dateText, r.weekday, r.title, r.category, r.amountSignedText, r.balanceAfterText].join('\t'));
      toast('Строка скопирована', 'info', 1800);
    }),
    copyText: run(async (text) => {
      await copyText(String(text));
      toast('Скопировано', 'info', 1800);
    }),
    quickEdit: run((row, anchor) => {
      if (!row || row.origin !== 'RULE') return;
      openQuickEdit({ ruleId: row.ruleId, originalDate: row.originalDate, anchor });
    }),
    undo: run(async () => {
      if (!store.state.canUndo) return toast('Отменять нечего', 'info', 1800);
      const text = store.state.document.undoText;
      store.set(await api.post('/api/undo'));
      return toast(text ? `Отменено: ${text}` : 'Отменено', 'info', 2200);
    }),
    redo: run(async () => {
      if (!store.state.canRedo) return toast('Повторять нечего', 'info', 1800);
      const text = store.state.document.redoText;
      store.set(await api.post('/api/redo'));
      return toast(text ? `Повторено: ${text}` : 'Повторено', 'info', 2200);
    }),
    planSettings: run(() => planSettingsDialog()),
    actualize: run(() => actualizeDialog(null)),
    reconcile: run(() => reconcileDialog(null)),

    // ---------------------------------------------------------------- Вид
    setMode: run(async (mode) => store.set(await api.put('/api/view', { mode }))),
    toggleView: run(async (key) => store.set(await api.put('/api/view', { [key]: !view()[key] }))),
    setPeriod: run(async (period) => store.set(await api.put('/api/view', { period }))),
    setFilterText: run(async (filterText) => store.set(await api.put('/api/view', { filterText }))),
    setHorizonMonths: run(async (months) => {
      store.set(await api.put('/api/plan/settings', {
        fields: { horizonKind: 'MONTHS', horizonValue: String(months) }, description: `Горизонт плана: ${months} мес.`,
      }));
    }),
    focusFilter: run(() => {
      const input = store.ui.toolbar && store.ui.toolbar.filter;
      if (input) {
        input.focus();
        input.select();
      }
    }),
    showInTable: run(async (iso) => {
      if (view().mode !== 'TABLE') store.set(await api.put('/api/view', { mode: 'TABLE' }));
      if (iso) store.ui.table.scrollToDate(iso);
    }),

    // ---------------------------------------------------------------- Инструменты
    goalCalculator: run(() => goalCalculator()),
    whatIfIncome: run(async () => {
      store.set(await api.put('/api/what-if', { incomeFactor: isOne(view().whatIf.incomeFactor) ? '0.90' : '1' }));
    }),
    whatIfExpense: run(async () => {
      store.set(await api.put('/api/what-if', { expenseFactor: isOne(view().whatIf.expenseFactor) ? '1.10' : '1' }));
    }),
    setExtraSaving: run(async (text) => {
      const major = Number(String(text ?? '').replace(',', '.'));
      if (!Number.isFinite(major) || major < 0) return;
      store.set(await api.put('/api/what-if', { extraMonthlySaving: formatPlain(Math.round(major * 100)) }));
    }),
    applyWhatIf: run(() => applyWhatIfDialog(null)),
    resetWhatIf: run(async () => {
      store.set(await api.post('/api/what-if/reset'));
      toast('Сценарий «что-если» сброшен', 'info', 2200);
    }),
    diagnostics: run(diagnostics),
    cleanupOrphans: run(async () => {
      const res = await api.post('/api/cleanup-orphans');
      store.set(res);
      toast(res.message, res.removed ? 'success' : 'info', 3000);
    }),
    currency: run(() => currencyDialog(null)),

    // ---------------------------------------------------------------- Восстановление
    snapshotNow: run(async () => {
      const session = await api.post('/api/session/snapshot');
      if (store.ui.updateStatus) store.ui.updateStatus(session);
      toast(`Снимок сессии сохранён: ${session.statusText || 'сервер'}`, 'success', 2500);
    }),
    showLastSnapshot: run(async () => {
      const res = await api.get('/api/session/last');
      // JavaFX: Alert + expandableContent → Swing: SwingAlert + «Подробнее» → Web: <dialog class="alert"> + <details>
      await alertDialog({
        type: 'INFORMATION',
        title: 'Последний снимок сессии',
        header: res.text ? 'Снимок сессии на сервере (web-session.md)' : 'Снимка сессии пока нет',
        content: h('p', {}, 'Файл: ', h('code', { class: 'path', text: res.file })),
        details: res.text || res.problem || 'Файл снимка ещё не создан.',
        detailsLabel: 'Текст снимка',
        width: 'min(760px, 96vw)',
      });
    }),
    clearSnapshots: run(async () => {
      const CLEAR = buttonType('Очистить', 'ok', ButtonRole.OK_DONE);
      const value = await alertDialog({
        type: 'CONFIRMATION',
        title: 'Очистить снимки',
        header: 'Удалить сохранённые снимки сессии?',
        content: 'Текущий сеанс продолжит записываться: новый снимок появится при следующем изменении.',
        buttons: [CLEAR, ButtonTypes.CANCEL],
      });
      if (value !== 'ok') return;
      const session = await api.post('/api/session/clear');
      if (store.ui.updateStatus) store.ui.updateStatus(session);
      toast('Снимки очищены', 'success', 2500);
    }),
    simulateServerCrash: run(async () => {
      const CRASH = buttonType('Остановить аварийно', 'ok', ButtonRole.OK_DONE);
      const value = await alertDialog({
        type: 'WARNING',
        title: 'Симуляция сбоя',
        header: 'Остановить сервер аварийно, без сохранения (Runtime.halt)?',
        content: 'Браузер не может «упасть» за сервер, поэтому сбой симулируется на сервере. Несохранённые изменения '
          + 'останутся только в последнем снимке сессии. При следующем запуске CashPrediction Web предложит «Восстановить с сервера».',
        buttons: [CRASH, ButtonTypes.CANCEL],
      });
      if (value !== 'ok') return;
      store.crashSimulated = true;
      await api.post('/api/debug/crash');
      showServerDown({
        title: 'Сервер остановлен аварийно',
        text: 'Так выглядит сбой: сервер завершился без сохранения. Запустите CashPrediction Web снова и откройте новый '
          + 'адрес из его окна — появится баннер «Восстановить с сервера».',
        retry: false,
      });
    }),
    simulateJsError: () => {
      // Аналог «Необработанное исключение» desktop-клиентов: ошибка в обработчике событий страницы.
      setTimeout(() => {
        throw new Error('Симуляция необработанного исключения (меню «Восстановление → Симулировать сбой»)');
      }, 0);
    },

    // ---------------------------------------------------------------- Справка
    about: run(async () => {
      const about = await api.get('/api/about');
      // Диалог 19. JavaFX: Alert(INFORMATION) → Swing: SwingAlert.info → Web: <dialog class="alert alert-information">
      await alertDialog({
        type: 'INFORMATION',
        title: 'О программе',
        header: `CashPrediction ${about.version} — прогноз бюджета`,
        content: 'Сколько денег будет через месяц, полгода, год при текущем плане доходов и расходов.\n\n'
          + `Клиент: браузер (тонкий клиент), сервер: Java ${about.java}, ${about.os}.\n`
          + `Все данные хранятся в папке CashMemory:\n${about.cashMemory}`,
      });
    }),
    hotkeys: run(() => alertDialog({
      // Диалог 20. JavaFX: Alert + expandableContent → Swing: SwingAlert.infoWithDetails → Web: <dialog> + <details>
      type: 'INFORMATION',
      title: 'Горячие клавиши',
      header: 'Горячие клавиши CashPrediction',
      content: 'Сочетания, занятые браузером (Ctrl+N, Ctrl+T, Ctrl+W, Ctrl+O), заменены на Alt+Shift+буква. Полный список — ниже.',
      details: HOTKEYS_TEXT,
      detailsLabel: 'Список сочетаний',
      width: 'min(680px, 96vw)',
    })),
    formatHelp: run(async () => {
      const res = await api.get('/api/format-help');
      await alertDialog({
        type: 'INFORMATION',
        title: 'Формат файла .md',
        header: 'Формат файла плана CashPrediction',
        content: 'Файл плана — обычный текст Markdown: его можно править в Блокноте. Нераспознанные строки не теряются. Руководство — ниже.',
        details: res.text,
        detailsLabel: 'Руководство по формату',
        width: 'min(820px, 96vw)',
      });
    }),
  });
  registerWindowFactories();
}

/**
 * Фабрики восстанавливаемых окон: WindowType (или «ТИП:назначение») → функция, открывающая окно по состоянию.
 * Аналог WindowFactory ядра. Фабрика возвращает false, если окно открыть нельзя (например, операции уже нет в плане).
 */
function registerWindowFactories() {
  /**
   * Запускает асинхронную фабрику, показывая ошибку и снимая окно с сервера при сбое.
   * @param {object} win состояние окна
   * @param {() => Promise<any>} fn фабрика
   * @returns {Promise<any>} результат
   */
  const safe = (win, fn) => fn().catch((e) => {
    abandonWindow(win);
    errorAlert(e, 'Окно не восстановлено');
  });
  Object.assign(windowFactories, {
    NEW_PLAN_WIZARD: (win) => newPlanWizard({ existing: win }),
    PLAN_SETTINGS: (win) => planSettingsDialog({ existing: win }),
    RULE_EDITOR: (win) => ruleEditor({ existing: win }),
    ONE_TIME_EDITOR: (win) => oneTimeEditor({ existing: win }),
    ADJUSTMENT_EDITOR: (win) => adjustmentEditor({ existing: win }),
    GOAL_CALCULATOR: (win) => goalCalculator({ existing: win }),
    CSV_EXPORT: (win) => csvExportDialog({ existing: win }),
    QUICK_EDIT_POPUP: (win) => openQuickEdit({
      ruleId: win.context.ruleId, originalDate: win.context.originalDate, existing: win,
    }),
    'TEXT_INPUT:rename': (win) => renameDialog(win),
    'TEXT_INPUT:reconcile': (win) => reconcileDialog(win),
    'TEXT_INPUT:customCurrency': (win) => customCurrencyDialog(win),
    'CHOICE:currency': (win) => safe(win, () => currencyDialog(win)),
    'CHOICE:openPlan': (win) => safe(win, () => openPlanChoice(win)),
    'ALERT:deleteRule': (win) => confirmDeleteRule(null, win),
    'ALERT:deleteOneTime': (win) => confirmDeleteOneTime(null, win),
    'ALERT:actualize': (win) => safe(win, () => actualizeDialog(win)),
    'ALERT:applyWhatIf': (win) => applyWhatIfDialog(win),
  });
}
