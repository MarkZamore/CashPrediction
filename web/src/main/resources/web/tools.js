/**
 * @file Инструменты: диалог 6 «Калькулятор цели» (немодальный, GoalCalculator на сервере)
 * и диалог 14 «Экспорт CSV» (CsvExporter на сервере, файл отдаётся браузеру на скачивание).
 */

import { h, debounce, downloadUrl } from './util.js';
import { api, apiUrl } from './api.js';
import { store } from './store.js';
import { AppDialog, ButtonTypes, ButtonRole, buttonType, errorMessage } from './dialogs.js';
import { Form, isValidMoney, isValidDate } from './forms.js';
import { toast } from './popup.js';

/** Открытый калькулятор цели (он один, как немодальное окно в desktop-клиентах). */
let goalDialog = null;

/**
 * Диалог 6 «Калькулятор цели»: когда накопится сумма и сколько откладывать, чтобы успеть к дате.
 * Немодальный: можно менять план, а расчёт пересчитывается после каждого изменения.
 * JavaFX: Dialog (initModality NONE, GoalCalculatorDialog) → Swing: GoalCalculatorDialog (JDialog MODELESS)
 * → Web: <dialog> через show() вместо showModal()
 * @param {object} [options] параметры
 * @param {object|null} [options.existing] состояние окна с сервера
 * @returns {Promise<string>} закрытие окна
 */
export function goalCalculator({ existing = null } = {}) {
  if (goalDialog && !goalDialog.closed) {
    goalDialog.el.querySelector('input')?.focus();
    return goalDialog.promise;
  }
  const plan = store.state.plan;
  const initial = {
    target: plan.goal ? plan.goal.target : '',
    byDateEnabled: String(!!(plan.goal && plan.goal.wishDate)),
    byDate: plan.goal && plan.goal.wishDate ? plan.goal.wishDate : '',
    extraSaving: '',
    ...((existing && existing.fields) || {}),
  };
  const result = h('div', { class: 'goal-result', 'aria-live': 'polite' });
  let dialog = null;
  const form = new Form({ onChange: () => changed() });
  form.add('target', { label: 'Целевая сумма', type: 'money', suffix: plan.currency });
  form.add('byDateEnabled', { label: 'Успеть к определённой дате', type: 'checkbox' });
  form.add('byDate', { label: 'К дате', type: 'date' });
  form.add('extraSaving', {
    label: 'Откладывать доп. в месяц', type: 'money', suffix: plan.currency, placeholder: '0,00',
    hint: 'Добавляется к текущему плану и «что-если»',
  });
  form.fill(initial);

  /** Пересчёт на сервере (GoalCalculator.reachDate / requiredExtraMonthly). */
  const recalc = debounce(async () => {
    if (!dialog || dialog.closed) return;
    const f = form.collect();
    if (!isValidMoney(form.text('target'), { positive: true })) {
      result.replaceChildren(h('p', { class: 'muted', text: 'Введите целевую сумму больше нуля.' }));
      return;
    }
    try {
      const res = await api.get('/api/goal', {
        target: f.target, byDateEnabled: f.byDateEnabled, byDate: isValidDate(form.text('byDate')) ? f.byDate : '',
        extraSaving: isValidMoney(form.text('extraSaving'), { allowEmpty: true }) ? f.extraSaving : '',
      });
      renderResult(res);
    } catch (e) {
      result.replaceChildren(h('p', { class: 'dialog-error', text: errorMessage(e) }));
    }
  }, 300);

  /**
   * Показывает результат расчёта.
   * @param {object} res ответ /api/goal
   */
  const renderResult = (res) => {
    result.replaceChildren();
    if (res.error) {
      result.append(h('p', { class: 'muted', text: res.error }));
      return;
    }
    if (res.reachDate) {
      result.append(h('p', { class: 'goal-line good' },
        'Сумма ', h('strong', { text: res.targetText }), ' накопится ', h('strong', { text: res.reachDateText }),
        ` (${res.reachInText}).`));
    } else {
      result.append(h('p', { class: 'goal-line bad' },
        `В пределах плана (до ${res.endDateText}) цель не достигается: в конце будет `,
        h('strong', { text: res.endBalanceText }), '.'));
    }
    if (res.byDateText) {
      result.append(h('p', { class: 'goal-line' }, `К ${res.byDateText} будет `, h('strong', { text: res.balanceAtByDateText }), '.'));
      if (res.required) {
        result.append(h('p', { class: 'goal-line' }, res.required === '0,00'
          ? 'Дополнительно откладывать не нужно - цель успевает сама.'
          : ['Чтобы успеть, откладывайте дополнительно ', h('strong', { text: res.requiredText }), ' в месяц.']));
      } else if (res.requiredProblem) {
        result.append(h('p', { class: 'goal-line bad', text: res.requiredProblem }));
      }
    }
  };

  /** Поле изменилось. */
  function changed() {
    form.setEnabled('byDate', form.value('byDateEnabled') === 'true');
    if (!dialog) return;
    dialog.clearError();
    dialog.touch();
    recalc();
  }

  const unsubscribe = store.on(() => recalc());
  dialog = new AppDialog({
    title: 'Калькулятор цели',
    header: 'Когда накопится нужная сумма при текущем плане и сколько откладывать, чтобы успеть к дате.',
    icon: '◎',
    content: h('div', { class: 'goal-calculator' }, form.el, result),
    buttons: [
      buttonType('Сделать целью плана', 'save', ButtonRole.LEFT, { title: 'Записать сумму и дату в «Цель» плана' }),
      ButtonTypes.CLOSE,
    ],
    // Кнопки по умолчанию нет: Enter в поле не должен закрывать немодальный калькулятор.
    modal: false,
    width: 'min(460px, 96vw)',
    windowType: 'GOAL_CALCULATOR',
    existing,
    collect: () => form.collect(),
    onButton: async (value, d) => {
      if (value !== 'save') return true;
      if (!isValidMoney(form.text('target'), { positive: true })) {
        d.setValidation('Введите целевую сумму больше нуля');
        return false;
      }
      const f = form.collect();
      const res = await api.post('/api/goal/save', {
        target: f.target, byDate: f.byDateEnabled === 'true' ? f.byDate : '', title: plan.goal ? plan.goal.title : 'Цель',
      });
      store.set(res);
      toast('Цель плана обновлена', 'success', 2500);
      return false;
    },
    onClose: () => {
      unsubscribe();
      goalDialog = null;
    },
  });
  goalDialog = dialog;
  const promise = dialog.show();
  form.setEnabled('byDate', form.value('byDateEnabled') === 'true');
  recalc();
  return promise;
}

/**
 * Диалог 14 «Экспорт CSV»: разделитель, BOM, диапазон; «Сохранить» - скачивание файла браузером.
 * JavaFX: Dialog<CsvOptions> → FileChooser.showSaveDialog → Swing: CsvExportDialog → JFileChooser
 * → Web: <dialog> → ссылка скачивания GET /api/export.csv?t=…
 * @param {object} [options] параметры
 * @param {object|null} [options.existing] состояние окна с сервера
 * @returns {Promise<string>} значение кнопки
 */
export function csvExportDialog({ existing = null } = {}) {
  const state = store.state;
  let dialog = null;
  const form = new Form({ onChange: () => dialog && dialog.touch() });
  form.add('separator', {
    label: 'Разделитель', type: 'select',
    options: [[';', 'Точка с запятой ( ; ) - Excel'], [',', 'Запятая ( , )'], ['TAB', 'Табуляция']],
  });
  form.add('bom', { label: 'Добавить BOM (Excel правильно покажет кириллицу)', type: 'checkbox' });
  form.add('range', {
    label: 'Строки', type: 'radio',
    options: [['PERIOD', `Видимый период (до ${state.periodEndText || 'конца периода'})`], ['ALL', 'Весь горизонт плана']],
  });
  form.fill({ separator: ';', bom: 'true', range: 'PERIOD', ...((existing && existing.fields) || {}) });
  dialog = new AppDialog({
    title: 'Экспорт в CSV',
    header: 'Таблица событий с балансом для Excel или другой программы. Файл сохранится в папку загрузок браузера.',
    icon: '⇩',
    content: form.el,
    buttons: [buttonType('Скачать CSV', 'ok', ButtonRole.OK_DONE), ButtonTypes.CANCEL],
    windowType: 'CSV_EXPORT',
    existing,
    collect: () => form.collect(),
    onButton: (value) => {
      if (value !== 'ok') return true;
      const f = form.collect();
      // JavaFX: FileChooser.showSaveDialog → Swing: JFileChooser.showSaveDialog → Web: скачивание по ссылке
      downloadUrl(apiUrl('/api/export.csv', { separator: f.separator, bom: f.bom, range: f.range }));
      toast('Файл CSV передан браузеру для сохранения', 'success', 3000);
      return true;
    },
  });
  return dialog.show();
}
