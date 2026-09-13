/**
 * @file Диалоги плана целиком: диалог 1 «Новый план» (мастер из трёх страниц) и диалог 2 «Параметры плана».
 *
 * Мастер создаёт план в памяти сервера (POST /api/plans/new) — файл появится при первом сохранении (Ctrl+S).
 * «Параметры плана» меняют план через PlanDocument (PUT /api/plan/settings), смена названия переименовывает файл.
 * Оба диалога — восстанавливаемые окна (NEW_PLAN_WIZARD с контекстом page, PLAN_SETTINGS).
 */

import { h, planNameProblem } from './util.js';
import { api } from './api.js';
import { store } from './store.js';
import { AppDialog, ButtonTypes, ButtonRole, buttonType } from './dialogs.js';
import { Form, isValidMoney, isValidDate } from './forms.js';
import { toast } from './popup.js';

/** Популярные валюты для автодополнения (как в ChoiceDialog «Валюта»). */
export const CURRENCIES = ['₽', '$', '€', '₸', 'BYN'];

/** Виды горизонта плана. */
const HORIZON_OPTIONS = [['MONTHS', 'месяцев'], ['YEARS', 'лет'], ['UNTIL', 'до даты']];

/**
 * Добавляет в форму поля горизонта: вид, число, дата окончания.
 * @param {Form} form форма
 * @returns {() => void} функция обновления видимости полей
 */
function addHorizonFields(form) {
  form.add('horizonKind', { label: 'Горизонт', type: 'select', options: HORIZON_OPTIONS, title: 'На какой срок строить прогноз' });
  form.add('horizonValue', { label: 'Сколько', type: 'number', min: 1, max: 1200 });
  form.add('horizonUntil', { label: 'До даты', type: 'date' });
  return () => {
    const kind = form.value('horizonKind');
    form.setVisible('horizonValue', kind !== 'UNTIL');
    form.setVisible('horizonUntil', kind === 'UNTIL');
    form.setLabel('horizonValue', kind === 'YEARS' ? 'Лет' : 'Месяцев');
  };
}

/**
 * Проверка полей горизонта.
 * @param {Form} form форма
 * @returns {string} текст ошибки или ''
 */
function validateHorizon(form) {
  const kind = form.value('horizonKind');
  if (kind === 'UNTIL') return isValidDate(form.text('horizonUntil')) ? '' : 'Дата окончания горизонта — в виде ДД.ММ.ГГГГ';
  const n = Number(form.text('horizonValue'));
  return Number.isInteger(n) && n >= 1 ? '' : 'Горизонт — целое число больше нуля';
}

/**
 * Диалог 1 «Новый план»: мастер из трёх страниц.
 * JavaFX: Dialog<Plan> + ButtonType("Назад"/"Далее"/"Готово", OK_DONE) → Swing: NewPlanWizard (SwingDialog) → Web: <dialog>
 * со страницами
 * @param {object} [options] параметры
 * @param {object|null} [options.existing] состояние окна с сервера (контекст page, поля)
 * @returns {Promise<string>} значение кнопки
 */
export function newPlanWizard({ existing = null } = {}) {
  const today = (store.state && store.state.today) || new Date().toISOString().slice(0, 10);
  const initial = {
    name: '', currency: '₽', startDate: today, startBalance: '', horizonKind: 'MONTHS', horizonValue: '12',
    horizonUntil: '', cushion: '', quickIncomeTitle: 'Зарплата', quickIncomeAmount: '', quickIncomeDay: '5',
    quickExpenseTitle: 'Аренда', quickExpenseAmount: '', quickExpenseDay: '1',
    ...((existing && existing.fields) || {}),
  };
  let page = Math.max(0, Math.min(2, Number((existing && existing.context.page) || 0) || 0));
  let dialog = null;
  /** Общий обработчик изменения полей всех трёх страниц мастера. */
  const onChange = () => changed();
  const pages = [new Form({ onChange }), new Form({ onChange }), new Form({ onChange })];
  const [p0, p1, p2] = pages;
  p0.add('name', { label: 'Название плана', placeholder: 'Например, Семейный бюджет 2026' });
  p0.add('currency', { label: 'Валюта', list: CURRENCIES });
  p0.add('startDate', { label: 'Дата начала', type: 'date' });
  p0.add('startBalance', { label: 'Сколько денег сейчас', type: 'money', hint: 'Текущий бюджет на дату начала' });
  const horizonVisibility = addHorizonFields(p1);
  p1.add('cushion', { label: 'Подушка безопасности', type: 'money', hint: 'Строки ниже этой суммы подсвечиваются жёлтым' });
  p2.section('Регулярный доход (необязательно)');
  p2.add('quickIncomeTitle', { label: 'Название' });
  p2.add('quickIncomeAmount', { label: 'Сумма', type: 'money', placeholder: 'пусто — не добавлять' });
  p2.add('quickIncomeDay', { label: 'День месяца', type: 'number', min: 1, max: 31 });
  p2.section('Регулярный расход (необязательно)');
  p2.add('quickExpenseTitle', { label: 'Название' });
  p2.add('quickExpenseAmount', { label: 'Сумма', type: 'money', placeholder: 'пусто — не добавлять' });
  p2.add('quickExpenseDay', { label: 'День месяца', type: 'number', min: 1, max: 31 });
  for (const form of pages) form.fill(initial);

  const PAGE_TITLES = [
    'Шаг 1 из 3. Название, валюта и сколько денег есть сейчас',
    'Шаг 2 из 3. На какой срок строить прогноз',
    'Шаг 3 из 3. Главные доход и расход (остальное можно добавить потом)',
  ];
  const steps = h('ol', { class: 'wizard-steps', 'aria-hidden': 'true' },
    ['Основное', 'Горизонт', 'Операции'].map((t) => h('li', { text: t })));
  const pagesEl = h('div', { class: 'wizard-pages' },
    pages.map((form, i) => h('div', { class: 'wizard-page', dataset: { page: String(i) } }, form.el)));

  /** @returns {object} все поля мастера */
  const collect = () => ({ ...p0.collect(), ...p1.collect(), ...p2.collect() });

  /**
   * Ошибка страницы.
   * @param {number} i номер страницы
   * @returns {string} текст ошибки или ''
   */
  const validatePage = (i) => {
    if (i === 0) {
      if (!p0.text('name').trim()) return 'Введите название плана';
      // Имя плана станет именем файла: те же правила, что PlanValidator.checkPlanName.
      if (planNameProblem(p0.text('name'))) return planNameProblem(p0.text('name'));
      if (!p0.text('currency').trim()) return 'Укажите валюту';
      if (!isValidDate(p0.text('startDate'), true)) return 'Дата начала — в виде ДД.ММ.ГГГГ';
      if (!isValidMoney(p0.text('startBalance'), { allowEmpty: true, allowNegative: true })) return 'Сумма — число, например 150 000,00';
    }
    if (i === 1) {
      const message = validateHorizon(p1);
      if (message) return message;
      if (!isValidMoney(p1.text('cushion'), { allowEmpty: true })) return 'Подушка — неотрицательная сумма';
    }
    if (i === 2) {
      for (const prefix of ['quickIncome', 'quickExpense']) {
        if (!isValidMoney(p2.text(`${prefix}Amount`), { allowEmpty: true, positive: true })) return 'Сумма операции — число больше нуля';
        const day = Number(p2.text(`${prefix}Day`) || '1');
        if (p2.text(`${prefix}Amount`).trim() && (!Number.isInteger(day) || day < 1 || day > 31)) return 'День месяца — от 1 до 31';
      }
    }
    return '';
  };

  /** Показывает текущую страницу и нужные кнопки. */
  const showPage = () => {
    pagesEl.querySelectorAll('.wizard-page').forEach((el, i) => {
      /** @type {HTMLElement} */ (el).hidden = i !== page;
    });
    steps.querySelectorAll('li').forEach((li, i) => li.classList.toggle('current', i === page));
    horizonVisibility();
    if (!dialog) return;
    dialog.setHeader(PAGE_TITLES[page]);
    dialog.setButtonVisible('back', page > 0);
    dialog.setButtonVisible('next', page < 2);
    dialog.setButtonVisible('ok', page === 2);
    const message = validatePage(page);
    dialog.setButtonDisabled('next', !!message);
    dialog.setButtonDisabled('ok', !!message);
    dialog.setValidation(message);
  };

  /** Поле изменилось. */
  function changed() {
    horizonVisibility();
    if (!dialog) return;
    const message = validatePage(page);
    dialog.setButtonDisabled('next', !!message);
    dialog.setButtonDisabled('ok', !!message);
    dialog.setValidation(message);
    dialog.clearError();
    dialog.touch();
  }

  /**
   * Переход на страницу.
   * @param {number} next номер страницы
   */
  const goTo = (next) => {
    page = next;
    dialog.setContext({ page: String(page) });
    showPage();
    const first = pagesEl.querySelector(`.wizard-page[data-page="${page}"] input, .wizard-page[data-page="${page}"] select`);
    if (first) /** @type {HTMLElement} */ (first).focus();
  };

  dialog = new AppDialog({
    title: 'Новый план',
    header: PAGE_TITLES[page],
    icon: '★',
    content: h('div', { class: 'wizard' }, steps, pagesEl),
    buttons: [
      buttonType('‹ Назад', 'back', ButtonRole.LEFT),
      buttonType('Далее ›', 'next', ButtonRole.OTHER),
      buttonType('Готово', 'ok', ButtonRole.OK_DONE),
      ButtonTypes.CANCEL,
    ],
    defaultButton: () => (page < 2 ? 'next' : 'ok'),
    width: 'min(580px, 96vw)',
    windowType: 'NEW_PLAN_WIZARD',
    context: { page: String(page) },
    existing,
    collect,
    onButton: async (value, d) => {
      if (value === 'back') {
        goTo(Math.max(0, page - 1));
        return false;
      }
      if (value === 'next') {
        const message = validatePage(page);
        if (message) {
          d.setValidation(message);
          return false;
        }
        goTo(Math.min(2, page + 1));
        return false;
      }
      if (value !== 'ok') return true;
      for (let i = 0; i < 3; i++) {
        const message = validatePage(i);
        if (message) {
          goTo(i);
          d.setValidation(message);
          return false;
        }
      }
      const res = await api.post('/api/plans/new', { fields: collect(), windowId: await d.windowIdReady() });
      d.markServerClosed();
      store.set(res);
      toast(`План «${res.plan.name}» создан. Сохраните его в CashMemory (Ctrl+S).`, 'success', 5000);
      return true;
    },
  });
  const promise = dialog.show();
  showPage();
  return promise;
}

/**
 * Диалог 2 «Параметры плана».
 * JavaFX: Dialog<Plan> (PlanSettingsDialog) → Swing: PlanSettingsDialog (SwingDialog) → Web: <dialog> + Form
 * @param {object} [options] параметры
 * @param {object|null} [options.existing] состояние окна с сервера
 * @returns {Promise<string>} значение кнопки
 */
export function planSettingsDialog({ existing = null } = {}) {
  const plan = store.state.plan;
  const horizon = plan.horizon || {};
  const initial = {
    name: plan.name,
    currency: plan.currency,
    startDate: plan.startDate,
    startBalance: plan.startBalance,
    horizonKind: horizon.kind || 'MONTHS',
    horizonValue: horizon.count != null ? String(horizon.count) : '12',
    horizonUntil: horizon.until || '',
    cushion: plan.cushion,
    note: plan.note || '',
    goalTitle: plan.goal ? plan.goal.title : '',
    goalTarget: plan.goal ? plan.goal.target : '',
    goalDate: plan.goal && plan.goal.wishDate ? plan.goal.wishDate : '',
    ...((existing && existing.fields) || {}),
  };
  let dialog = null;
  const form = new Form({ onChange: () => changed() });
  form.section('План');
  form.add('name', { label: 'Название', hint: 'Смена названия переименует файл в CashMemory' });
  form.add('currency', { label: 'Валюта', list: CURRENCIES });
  form.add('startDate', { label: 'Дата начала', type: 'date' });
  form.add('startBalance', { label: 'Начальный баланс', type: 'money' });
  form.section('Горизонт и подушка');
  const horizonVisibility = addHorizonFields(form);
  form.add('cushion', { label: 'Подушка безопасности', type: 'money' });
  form.section('Цель накоплений');
  form.add('goalTitle', { label: 'Цель', placeholder: 'Например, Отпуск' });
  form.add('goalTarget', { label: 'Сумма цели', type: 'money', placeholder: 'пусто — без цели' });
  form.add('goalDate', { label: 'Желаемая дата', type: 'date', placeholder: 'необязательно' });
  form.section('Заметка');
  form.add('note', { label: 'Заметка', type: 'textarea', rows: 3 });
  form.fill(initial);

  /** @returns {string} текст первой ошибки или '' */
  const validate = () => {
    if (!form.text('name').trim()) return 'Введите название плана';
    // Смена названия переименует файл: те же правила, что PlanValidator.checkPlanName.
    if (planNameProblem(form.text('name'))) return planNameProblem(form.text('name'));
    if (!form.text('currency').trim()) return 'Укажите валюту';
    if (!isValidDate(form.text('startDate'))) return 'Дата начала — в виде ДД.ММ.ГГГГ';
    if (!isValidMoney(form.text('startBalance'), { allowEmpty: true, allowNegative: true })) return 'Начальный баланс — число';
    const horizonMessage = validateHorizon(form);
    if (horizonMessage) return horizonMessage;
    if (!isValidMoney(form.text('cushion'), { allowEmpty: true })) return 'Подушка — неотрицательная сумма';
    if (!isValidMoney(form.text('goalTarget'), { allowEmpty: true, positive: true })) return 'Сумма цели — число больше нуля';
    if (!isValidDate(form.text('goalDate'), true)) return 'Желаемая дата — в виде ДД.ММ.ГГГГ';
    return '';
  };

  /** Поле изменилось. */
  function changed() {
    horizonVisibility();
    if (!dialog) return;
    const message = validate();
    dialog.setButtonDisabled('ok', !!message);
    dialog.setValidation(message);
    dialog.clearError();
    dialog.touch();
  }

  dialog = new AppDialog({
    title: 'Параметры плана',
    header: `План «${plan.name}»: начало, горизонт прогноза, подушка безопасности и цель.`,
    icon: '⚙',
    content: form.el,
    buttons: [buttonType('Сохранить', 'ok', ButtonRole.OK_DONE), ButtonTypes.CANCEL],
    width: 'min(600px, 96vw)',
    windowType: 'PLAN_SETTINGS',
    existing,
    collect: () => form.collect(),
    onButton: async (value, d) => {
      if (value !== 'ok') return true;
      const message = validate();
      if (message) {
        d.setValidation(message);
        return false;
      }
      const res = await api.put('/api/plan/settings', {
        fields: form.collect(), description: 'Параметры плана', windowId: await d.windowIdReady(),
      });
      d.markServerClosed();
      store.set(res);
      toast('Параметры плана изменены', 'success', 2500);
      return true;
    },
  });
  const promise = dialog.show();
  horizonVisibility();
  const message = validate();
  dialog.setButtonDisabled('ok', !!message);
  if (existing) dialog.setValidation(message);
  return promise;
}
