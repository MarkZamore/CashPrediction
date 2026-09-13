/**
 * @file Редакторы операций плана: диалог 3 «Регулярная операция» (с живым предпросмотром «Ближайшие даты»),
 * диалог 4 «Разовая операция», диалог 5 «Корректировка события» и диалог 11 «Удаление правила/операции».
 *
 * Все правки идут на сервер в PlanDocument (история отмены, флаг «изменён», автосохранение — как в desktop-клиентах).
 * Каждый диалог — восстанавливаемое окно (RULE_EDITOR, ONE_TIME_EDITOR, ADJUSTMENT_EDITOR, ALERT): при открытии
 * регистрируется на сервере, при вводе отправляет поля, а правка передаёт windowId, чтобы сервер закрыл окно в том же
 * запросе.
 */

import { h, ruDate, WEEKDAYS, debounce, plural, parseMoney } from './util.js';
import { api } from './api.js';
import { store } from './store.js';
import {
  AppDialog, ButtonTypes, ButtonRole, buttonType, alertDialog, errorMessage,
} from './dialogs.js';
import { Form, isValidMoney, isValidDate } from './forms.js';
import { toast } from './popup.js';

/** Тип операции: доход или расход. */
export const KIND_OPTIONS = [['INCOME', 'Доход'], ['EXPENSE', 'Расход']];

/** Виды повтора (RecurrenceKind). */
const RECURRENCE_OPTIONS = [
  ['MONTHLY', 'Ежемесячно'], ['WEEKLY', 'Еженедельно'], ['EVERY_N_DAYS', 'Каждые N дней'], ['YEARLY', 'Ежегодно'],
];

/** Сдвиг события с выходного дня (WeekendPolicy). */
const WEEKEND_OPTIONS = [
  ['NONE', 'Не сдвигать'], ['PREVIOUS_BUSINESS_DAY', 'На пятницу (раньше)'], ['NEXT_BUSINESS_DAY', 'На понедельник (позже)'],
];

/** Действия корректировки (RuFormats.ActionType). */
const ACTION_OPTIONS = [
  ['SKIP', 'Пропустить событие'], ['CHANGE_AMOUNT', 'Изменить сумму'], ['MOVE_DATE', 'Перенести на другую дату'],
  ['REPLACE', 'Заменить сумму и дату'],
];

/** Кнопка «Сохранить» с ролью OK_DONE. */
const SAVE = buttonType('Сохранить', 'ok', ButtonRole.OK_DONE);

/** @returns {string} сегодняшняя дата сервера (ISO) */
function today() {
  return (store.state && store.state.today) || new Date().toISOString().slice(0, 10);
}

/** @returns {string} валюта плана */
function currency() {
  return (store.state && store.state.plan.currency) || '₽';
}

/** @returns {Array<string>} категории плана для автодополнения */
function categories() {
  const plan = store.state && store.state.plan;
  if (!plan) return [];
  const set = new Set(plan.categories || []);
  for (const r of plan.rules || []) if (r.category) set.add(r.category);
  for (const t of plan.oneTimes || []) if (t.category) set.add(t.category);
  return [...set].sort((a, b) => a.localeCompare(b, 'ru'));
}

/**
 * Поля редактора правила из JSON правила плана.
 * @param {object} rule правило (PlanJson.rule)
 * @returns {object} поля RULE_EDITOR в канонической форме
 */
function ruleToFields(rule) {
  const r = rule.recurrence || {};
  return {
    title: rule.title,
    kind: rule.kind,
    amount: rule.amount,
    category: rule.category || '',
    recurrenceKind: r.kind,
    dayOfMonth: r.dayOfMonth != null ? String(r.dayOfMonth) : '',
    everyN: r.everyN != null ? String(r.everyN) : '1',
    weekday: r.weekday || 'MONDAY',
    monthDay: r.monthDay ? String(r.monthDay).replace(/^--/, '') : today().slice(5),
    fromEnabled: String(!!rule.from),
    from: rule.from || '',
    untilEnabled: String(!!rule.until),
    until: rule.until || '',
    weekendPolicy: rule.weekendPolicy || 'NONE',
    enabled: String(rule.enabled !== false),
    note: rule.note || '',
  };
}

/**
 * Диалог 3 «Регулярная операция».
 * JavaFX: Dialog<RecurringRule> + AppDialogPane → Swing: RuleDialog (SwingDialog) → Web: <dialog> + Form
 * @param {object} [options] параметры
 * @param {'create'|'edit'} [options.mode='create'] создание или изменение
 * @param {string} [options.ruleId] идентификатор правила (для edit)
 * @param {'INCOME'|'EXPENSE'} [options.kind='INCOME'] тип новой операции
 * @param {object|null} [options.existing] состояние окна с сервера (восстановление)
 * @returns {Promise<string>} значение нажатой кнопки
 */
export function ruleEditor({ mode = 'create', ruleId = '', kind = 'INCOME', existing = null } = {}) {
  let ctxMode = (existing && existing.context.mode) || mode;
  let id = (existing && existing.context.ruleId) || ruleId;
  let rule = ctxMode === 'edit' ? store.rule(id) : null;
  let warning = '';
  if (ctxMode === 'edit' && !rule) {
    // Правило исчезло из плана (например, после отмены правки): поля сохраняются, операция будет создана заново.
    warning = `Операция «${id}» не найдена в плане — при сохранении она будет создана заново.`;
    ctxMode = 'create';
    id = '';
  }
  const t = today();
  const defaults = {
    title: '', kind, amount: '', category: '', recurrenceKind: 'MONTHLY', dayOfMonth: String(Number(t.slice(8))),
    everyN: '1', weekday: 'MONDAY', monthDay: t.slice(5), fromEnabled: 'false', from: '', untilEnabled: 'false',
    until: '', weekendPolicy: 'NONE', enabled: 'true', note: '',
  };
  const initial = { ...defaults, ...(rule ? ruleToFields(rule) : {}), ...((existing && existing.fields) || {}) };

  const preview = h('div', { class: 'preview-dates', 'aria-live': 'polite' });
  /** Номинальная дата события, выбранного в «Ближайших датах» (ISO; '' — ничего не выбрано). */
  let selectedNominal = '';
  // «Скорректировать событие…» — как в JavaFX и Swing: корректировка выбранной даты открывается поверх редактора.
  const adjustButton = h('button', {
    type: 'button', class: 'btn preview-adjust', text: 'Скорректировать событие…',
    // JavaFX: Tooltip → Swing: setToolTipText → Web: title
    title: ctxMode === 'edit' ? 'Выберите дату в списке «Ближайшие даты»' : 'Доступно при изменении существующего правила',
  });
  adjustButton.disabled = true;
  let dialog = null;
  const form = new Form({ onChange: (name) => changed(name) });
  form.add('title', { label: 'Название', placeholder: 'Например, Зарплата' });
  form.add('kind', { label: 'Тип', type: 'select', options: KIND_OPTIONS });
  form.add('amount', { label: 'Сумма', type: 'money', suffix: currency() });
  form.add('category', { label: 'Категория', list: categories(), placeholder: 'Необязательно' });
  form.section('Расписание');
  form.add('recurrenceKind', { label: 'Повтор', type: 'select', options: RECURRENCE_OPTIONS });
  form.add('dayOfMonth', { label: 'День месяца', type: 'number', min: 1, max: 31, hint: '31 — последний день месяца' });
  form.add('weekday', { label: 'День недели', type: 'select', options: WEEKDAYS });
  form.add('monthDay', { label: 'День года', placeholder: 'ММ-ДД', title: 'Месяц и день через дефис, например 03-08' });
  form.add('everyN', { label: 'Каждые N месяцев', type: 'number', min: 1, max: 999 });
  form.add('fromEnabled', { label: 'Действует с даты', type: 'checkbox' });
  form.add('from', { label: 'С', type: 'date' });
  form.add('untilEnabled', { label: 'Действует по дату', type: 'checkbox' });
  form.add('until', { label: 'По', type: 'date' });
  form.add('weekendPolicy', { label: 'Если выходной', type: 'select', options: WEEKEND_OPTIONS });
  form.addRow('Ближайшие даты', h('div', { class: 'preview-box' }, preview, adjustButton));
  form.section('Прочее');
  form.add('enabled', { label: 'Операция активна', type: 'checkbox' });
  form.add('note', { label: 'Заметка', type: 'textarea' });
  form.fill(initial);

  /** Показывает только поля выбранного вида повтора. */
  const updateVisibility = () => {
    const rk = form.value('recurrenceKind');
    form.setVisible('dayOfMonth', rk === 'MONTHLY');
    form.setVisible('weekday', rk === 'WEEKLY');
    form.setVisible('monthDay', rk === 'YEARLY');
    form.setVisible('everyN', rk !== 'YEARLY');
    form.setLabel('everyN', rk === 'WEEKLY' ? 'Каждые N недель' : rk === 'EVERY_N_DAYS' ? 'Каждые N дней' : 'Каждые N месяцев');
    form.setEnabled('from', form.value('fromEnabled') === 'true');
    form.setEnabled('until', form.value('untilEnabled') === 'true');
  };

  /**
   * Проверка формы.
   * @returns {string} текст первой ошибки ('' — форма верна)
   */
  const validate = () => {
    if (!form.text('title').trim()) return 'Введите название операции';
    if (!isValidMoney(form.text('amount'), { positive: true })) return 'Сумма — число больше нуля, например 45 000,00';
    const rk = form.value('recurrenceKind');
    const n = Number(form.text('everyN') || '1');
    if (rk === 'MONTHLY') {
      const day = Number(form.text('dayOfMonth'));
      if (!Number.isInteger(day) || day < 1 || day > 31) return 'День месяца — число от 1 до 31';
    }
    if (rk !== 'YEARLY' && (!Number.isInteger(n) || n < 1)) return '«Каждые N» — целое число от 1';
    if (rk === 'YEARLY' && !/^(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/.test(form.text('monthDay').trim())) {
      return 'День года — месяц и день через дефис, например 03-08';
    }
    if (form.value('fromEnabled') === 'true' && !isValidDate(form.text('from'))) return 'Дата «С» — в виде ДД.ММ.ГГГГ';
    if (form.value('untilEnabled') === 'true' && !isValidDate(form.text('until'))) return 'Дата «По» — в виде ДД.ММ.ГГГГ';
    return '';
  };

  /**
   * Можно ли скорректировать выбранное событие: только у сохранённого правила (у нового ещё нет идентификатора),
   * как в JavaFX и Swing.
   * @returns {boolean} да, если кнопка «Скорректировать событие…» доступна
   */
  const canAdjust = () => ctxMode === 'edit' && !!id && !!store.rule(id) && !!selectedNominal;

  /** Обновляет доступность кнопки корректировки и подсветку выбранной даты. */
  const syncAdjust = () => {
    adjustButton.disabled = !canAdjust();
    for (const chip of preview.querySelectorAll('.date-chip')) {
      chip.setAttribute('aria-pressed', String(chip.dataset.nominal === selectedNominal));
    }
  };

  /**
   * Открывает корректировку выбранного события поверх редактора правила (владелец окна — этот редактор, поэтому
   * после перезагрузки страницы или сбоя сервера оба окна вернутся в том же порядке).
   * @returns {Promise<void>} завершение
   */
  const openAdjustment = async () => {
    if (!canAdjust()) return;
    const ownerId = (await dialog.windowIdReady()) || 'main';
    const result = adjustmentEditor({ ruleId: id, originalDate: selectedNominal, ownerId });
    if (result) {
      await result;
      // Корректировка изменила план: отметка ✎ у даты появится после перерисовки предпросмотра.
      if (!dialog.closed) refreshPreview();
    }
  };
  adjustButton.addEventListener('click', () => openAdjustment());

  /** Живой предпросмотр ближайших дат (OccurrenceGenerator.upcoming на сервере). */
  const refreshPreview = debounce(async () => {
    try {
      const res = await api.post('/api/preview-dates', { fields: form.collect() });
      preview.replaceChildren();
      if (res.error) {
        preview.append(h('span', { class: 'muted', text: res.error }));
        selectedNominal = '';
        syncAdjust();
        return;
      }
      preview.append(h('span', { class: 'preview-rule', text: `${res.text}: ` }));
      if (!res.dates.length) preview.append(h('span', { class: 'muted', text: 'в пределах плана событий нет' }));
      // Выбор сохраняется между перерисовками, пока такая дата остаётся в списке.
      if (!res.dates.some((d) => d.nominal === selectedNominal)) selectedNominal = '';
      for (const d of res.dates) {
        const adjusted = ctxMode === 'edit' && !!id && !!store.adjustment(id, d.nominal);
        const tips = [];
        if (d.shifted) tips.push(`Сдвинуто с выходного: по правилу ${d.nominalText}`);
        if (adjusted) tips.push('У события есть корректировка');
        if (ctxMode === 'edit') tips.push('Щелчок — выбрать, двойной щелчок — скорректировать');
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        const chip = h('button', {
          type: 'button',
          class: `date-chip${d.shifted ? ' shifted' : ''}${adjusted ? ' adjusted' : ''}`,
          dataset: { nominal: d.nominal },
          'aria-pressed': 'false',
          title: tips.join('\n') || null,
          text: `${d.dateText} ${d.weekday}${d.shifted ? ' ⇄' : ''}${adjusted ? ' ✎' : ''}`,
        });
        chip.addEventListener('click', () => {
          selectedNominal = d.nominal;
          syncAdjust();
        });
        chip.addEventListener('dblclick', () => {
          selectedNominal = d.nominal;
          syncAdjust();
          openAdjustment();
        });
        preview.append(chip);
      }
      syncAdjust();
    } catch (e) {
      preview.replaceChildren(h('span', { class: 'muted', text: errorMessage(e) }));
    }
  }, 250);

  /**
   * Поле изменилось.
   * @param {string} name имя поля
   */
  function changed(name) {
    updateVisibility();
    if (!dialog) return;
    const message = validate();
    dialog.setButtonDisabled('ok', !!message);
    dialog.setValidation(message);
    dialog.clearError();
    dialog.touch();
    if (name !== 'title' && name !== 'note' && name !== 'category') refreshPreview();
  }

  const titleText = ctxMode === 'edit'
    ? `Регулярная операция — ${rule.title}`
    : (form.value('kind') === 'EXPENSE' ? 'Новый регулярный расход' : 'Новый регулярный доход');
  dialog = new AppDialog({
    title: titleText,
    header: warning || 'Доход или расход, который повторяется по расписанию. Сумма всегда положительная, знак задаёт тип.',
    icon: warning ? '⚠' : '↻',
    content: form.el,
    buttons: [SAVE, ButtonTypes.CANCEL],
    width: 'min(620px, 96vw)',
    windowType: 'RULE_EDITOR',
    context: { mode: ctxMode, ruleId: id },
    existing,
    collect: () => form.collect(),
    initialFocus: form.control('title'),
    onButton: async (value, d) => {
      if (value !== 'ok') return true;
      const message = validate();
      if (message) {
        d.setValidation(message);
        return false;
      }
      const body = { fields: form.collect(), windowId: await d.windowIdReady() };
      const res = ctxMode === 'edit'
        ? await api.put(`/api/rules/${encodeURIComponent(id)}`, body)
        : await api.post('/api/rules', body);
      d.markServerClosed();
      store.set(res);
      toast(ctxMode === 'edit' ? 'Операция изменена' : 'Операция добавлена', 'success', 2500);
      return true;
    },
  });
  const promise = dialog.show();
  updateVisibility();
  const message = validate();
  dialog.setButtonDisabled('ok', !!message);
  if (existing) dialog.setValidation(message);
  refreshPreview();
  return promise;
}

/**
 * Диалог 4 «Разовая операция».
 * JavaFX: Dialog<OneTimeTransaction> → Swing: OneTimeDialog → Web: <dialog> + Form
 * @param {object} [options] параметры
 * @param {'create'|'edit'} [options.mode='create'] режим
 * @param {string} [options.txId] идентификатор операции (для edit)
 * @param {string} [options.date] дата новой операции (ISO)
 * @param {'INCOME'|'EXPENSE'} [options.kind='EXPENSE'] тип новой операции
 * @param {object|null} [options.existing] состояние окна с сервера
 * @returns {Promise<string>} значение нажатой кнопки
 */
export function oneTimeEditor({ mode = 'create', txId = '', date = '', kind = 'EXPENSE', existing = null } = {}) {
  let ctxMode = (existing && existing.context.mode) || mode;
  let id = (existing && existing.context.txId) || txId;
  let tx = ctxMode === 'edit' ? store.oneTime(id) : null;
  let warning = '';
  if (ctxMode === 'edit' && !tx) {
    warning = `Операция «${id}» не найдена в плане — при сохранении она будет создана заново.`;
    ctxMode = 'create';
    id = '';
  }
  const initial = {
    date: date || today(), title: '', kind, amount: '', category: '', note: '',
    ...(tx ? {
      date: tx.date, title: tx.title, kind: tx.kind, amount: tx.amount, category: tx.category || '', note: tx.note || '',
    } : {}),
    ...((existing && existing.fields) || {}),
  };
  let dialog = null;
  const form = new Form({ onChange: () => changed() });
  form.add('date', { label: 'Дата', type: 'date' });
  form.add('title', { label: 'Название', placeholder: 'Например, Премия' });
  form.add('kind', { label: 'Тип', type: 'select', options: KIND_OPTIONS });
  form.add('amount', { label: 'Сумма', type: 'money', suffix: currency() });
  form.add('category', { label: 'Категория', list: categories(), placeholder: 'Необязательно' });
  form.add('note', { label: 'Заметка', type: 'textarea' });
  form.fill(initial);

  /** @returns {string} текст первой ошибки или '' */
  const validate = () => {
    if (!isValidDate(form.text('date'))) return 'Дата — в виде ДД.ММ.ГГГГ';
    if (!form.text('title').trim()) return 'Введите название операции';
    if (!isValidMoney(form.text('amount'), { positive: true })) return 'Сумма — число больше нуля, например 60 000,00';
    return '';
  };

  /** Поле изменилось. */
  function changed() {
    if (!dialog) return;
    const message = validate();
    dialog.setButtonDisabled('ok', !!message);
    dialog.setValidation(message);
    dialog.clearError();
    dialog.touch();
  }

  dialog = new AppDialog({
    title: ctxMode === 'edit' ? `Разовая операция — ${tx.title}` : 'Новая разовая операция',
    header: warning || 'Доход или расход, который случится один раз в указанный день.',
    icon: warning ? '⚠' : '≡',
    content: form.el,
    buttons: [SAVE, ButtonTypes.CANCEL],
    width: 'min(520px, 96vw)',
    windowType: 'ONE_TIME_EDITOR',
    context: { mode: ctxMode, txId: id },
    existing,
    collect: () => form.collect(),
    initialFocus: form.control(ctxMode === 'edit' ? 'amount' : 'title'),
    onButton: async (value, d) => {
      if (value !== 'ok') return true;
      const message = validate();
      if (message) {
        d.setValidation(message);
        return false;
      }
      const body = { fields: form.collect(), windowId: await d.windowIdReady() };
      const res = ctxMode === 'edit'
        ? await api.put(`/api/one-time/${encodeURIComponent(id)}`, body)
        : await api.post('/api/one-time', body);
      d.markServerClosed();
      store.set(res);
      toast(ctxMode === 'edit' ? 'Разовая операция изменена' : 'Разовая операция добавлена', 'success', 2500);
      return true;
    },
  });
  const promise = dialog.show();
  const message = validate();
  dialog.setButtonDisabled('ok', !!message);
  if (existing) dialog.setValidation(message);
  return promise;
}

/**
 * Диалог 5 «Корректировка события»: изменить один конкретный платёж регулярной операции.
 * JavaFX: Dialog<Adjustment> → Swing: AdjustmentDialog → Web: <dialog> + Form
 * @param {object} options параметры
 * @param {string} [options.ruleId] идентификатор правила
 * @param {string} [options.originalDate] номинальная дата события по правилу (ISO)
 * @param {object|null} [options.existing] состояние окна с сервера
 * @param {string} [options.ownerId='main'] владелец окна: main или идентификатор редактора правила, поверх которого оно открыто
 * @returns {Promise<string>|false} значение кнопки или false, если правила нет
 */
export function adjustmentEditor({ ruleId = '', originalDate = '', existing = null, ownerId = 'main' } = {}) {
  const rId = (existing && existing.context.ruleId) || ruleId;
  const orig = (existing && existing.context.originalDate) || originalDate;
  const rule = store.rule(rId);
  if (!rule || !orig) {
    if (!existing) toast('Правило корректируемого события не найдено', 'warning');
    return false;
  }
  const adjustment = store.adjustment(rId, orig);
  const RESET = buttonType('Сбросить', 'reset', ButtonRole.LEFT, { title: 'Вернуть событие как по правилу (удалить корректировку)' });
  const initial = {
    action: adjustment ? adjustment.action : 'CHANGE_AMOUNT',
    amount: adjustment && adjustment.amount ? adjustment.amount : rule.amount,
    date: adjustment && adjustment.date ? adjustment.date : orig,
    note: adjustment ? adjustment.note || '' : '',
    ...((existing && existing.fields) || {}),
  };
  let dialog = null;
  const form = new Form({ onChange: () => changed() });
  form.add('action', { label: 'Что сделать', type: 'radio', options: ACTION_OPTIONS });
  form.add('amount', { label: 'Новая сумма', type: 'money', suffix: currency() });
  form.add('date', { label: 'Новая дата', type: 'date' });
  form.add('note', { label: 'Заметка', type: 'textarea', placeholder: 'Почему событие отличается от правила' });
  form.fill(initial);

  /** Доступность суммы и даты по действию. */
  const updateEnabled = () => {
    const action = form.value('action');
    form.setEnabled('amount', action === 'CHANGE_AMOUNT' || action === 'REPLACE');
    form.setEnabled('date', action === 'MOVE_DATE' || action === 'REPLACE');
  };

  /** @returns {string} текст первой ошибки или '' */
  const validate = () => {
    const action = form.value('action');
    if (!action) return 'Выберите, что сделать с событием';
    if ((action === 'CHANGE_AMOUNT' || action === 'REPLACE') && !isValidMoney(form.text('amount'), { positive: true })) {
      return 'Новая сумма — число больше нуля';
    }
    if ((action === 'MOVE_DATE' || action === 'REPLACE') && !isValidDate(form.text('date'))) return 'Новая дата — в виде ДД.ММ.ГГГГ';
    return '';
  };

  /** Поле изменилось. */
  function changed() {
    updateEnabled();
    if (!dialog) return;
    const message = validate();
    dialog.setButtonDisabled('ok', !!message);
    dialog.setValidation(message);
    dialog.clearError();
    dialog.touch();
  }

  dialog = new AppDialog({
    title: 'Корректировка события',
    header: `«${rule.title}», событие ${ruDate(orig)} по правилу: ${rule.amountText} ${currency()}.`
      + (adjustment ? ` Сейчас: ${adjustment.actionText}.` : ''),
    icon: '✎',
    content: form.el,
    buttons: [SAVE, RESET, ButtonTypes.CANCEL],
    width: 'min(540px, 96vw)',
    windowType: 'ADJUSTMENT_EDITOR',
    context: { ruleId: rId, originalDate: orig },
    ownerId,
    existing,
    collect: () => {
      const f = form.collect();
      return { action: f.action, amount: f.amount, date: f.date, note: f.note };
    },
    onButton: async (value, d) => {
      if (value === 'reset') {
        const res = await api.del('/api/adjustments', { ruleId: rId, originalDate: orig }, { windowId: await d.windowIdReady() });
        d.markServerClosed();
        store.set(res);
        toast('Событие возвращено к правилу', 'success', 2500);
        return true;
      }
      if (value !== 'ok') return true;
      const message = validate();
      if (message) {
        d.setValidation(message);
        return false;
      }
      const res = await api.put('/api/adjustments', {
        fields: { ruleId: rId, originalDate: orig, ...form.collect() },
        windowId: await d.windowIdReady(),
      });
      d.markServerClosed();
      store.set(res);
      toast('Корректировка сохранена', 'success', 2500);
      return true;
    },
  });
  const promise = dialog.show();
  dialog.setButtonVisible('reset', !!store.adjustment(rId, orig));
  updateEnabled();
  const message = validate();
  dialog.setButtonDisabled('ok', !!message);
  return promise;
}

/**
 * Диалог 11: подтверждение удаления регулярной операции (с числом удаляемых корректировок).
 * JavaFX: Alert(CONFIRMATION) + ButtonType("Удалить", OK_DONE) → Swing: SwingAlert.confirm → Web: <dialog class="alert">
 * @param {string|null} ruleId идентификатор правила
 * @param {object|null} [existing] состояние окна ALERT с сервера
 * @returns {Promise<string>|false} значение кнопки или false, если правила нет
 */
export function confirmDeleteRule(ruleId, existing = null) {
  const id = (existing && existing.context.targetId) || ruleId;
  const rule = store.rule(id);
  if (!rule) return false;
  const count = (store.state.plan.adjustments || []).filter((a) => a.ruleId === id).length;
  const DELETE = buttonType('Удалить', 'ok', ButtonRole.OK_DONE);
  const content = count
    ? `Вместе с ней ${plural(count, 'будет удалена', 'будут удалены', 'будут удалены')} ${count} ${plural(count, 'корректировка', 'корректировки', 'корректировок')} её событий. Действие можно отменить (Ctrl+Z).`
    : 'Корректировок у этой операции нет. Действие можно отменить (Ctrl+Z).';
  return alertDialog({
    type: 'CONFIRMATION',
    title: 'Удаление операции',
    header: `Удалить регулярную операцию «${rule.title}» (${rule.amountText} ${currency()}, ${rule.recurrence.text})?`,
    content,
    buttons: [DELETE, ButtonTypes.CANCEL],
    windowType: 'ALERT',
    context: { purpose: 'deleteRule', targetId: id },
    existing,
    onButton: async (value, d) => {
      if (value !== 'ok') return true;
      const res = await api.del(`/api/rules/${encodeURIComponent(id)}`, undefined, { windowId: await d.windowIdReady() });
      d.markServerClosed();
      store.set(res);
      toast(`Операция «${rule.title}» удалена`, 'success', 3000);
      return true;
    },
  });
}

/**
 * Диалог 11: подтверждение удаления разовой операции.
 * JavaFX: Alert(CONFIRMATION) + ButtonType("Удалить", OK_DONE) → Swing: SwingAlert.confirm → Web: <dialog class="alert">
 * @param {string|null} txId идентификатор разовой операции
 * @param {object|null} [existing] состояние окна ALERT с сервера
 * @returns {Promise<string>|false} значение кнопки или false, если операции нет
 */
export function confirmDeleteOneTime(txId, existing = null) {
  const id = (existing && existing.context.targetId) || txId;
  const tx = store.oneTime(id);
  if (!tx) return false;
  const DELETE = buttonType('Удалить', 'ok', ButtonRole.OK_DONE);
  return alertDialog({
    type: 'CONFIRMATION',
    title: 'Удаление операции',
    header: `Удалить разовую операцию «${tx.title}» от ${tx.dateText} (${tx.amountText} ${currency()})?`,
    content: 'Корректировок у разовых операций не бывает. Действие можно отменить (Ctrl+Z).',
    buttons: [DELETE, ButtonTypes.CANCEL],
    windowType: 'ALERT',
    context: { purpose: 'deleteOneTime', targetId: id },
    existing,
    onButton: async (value, d) => {
      if (value !== 'ok') return true;
      const res = await api.del(`/api/one-time/${encodeURIComponent(id)}`, undefined, { windowId: await d.windowIdReady() });
      d.markServerClosed();
      store.set(res);
      toast(`Операция «${tx.title}» удалена`, 'success', 3000);
      return true;
    },
  });
}

/**
 * Сумма события для быстрой правки: из корректировки, иначе по правилу.
 * @param {string} ruleId идентификатор правила
 * @param {string} originalDate номинальная дата
 * @returns {string} каноническая сумма
 */
export function occurrenceAmount(ruleId, originalDate) {
  const adj = store.adjustment(ruleId, originalDate);
  const rule = store.rule(ruleId);
  if (adj && adj.amount) return adj.amount;
  return rule ? rule.amount : '';
}

/**
 * Минорные единицы суммы или 0.
 * @param {string} text сумма
 * @returns {number} копейки
 */
export function minorOf(text) {
  return parseMoney(text) ?? 0;
}
