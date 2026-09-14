/**
 * @file Поля форм диалогов и их канонические значения.
 *
 * Каждое поле формы знает, как превратить введённый текст в каноническую форму снимка сессии
 * (WindowState.fields - одинаковую во всех трёх клиентах) и обратно: деньги "95000,00", даты ISO ("" - пусто),
 * флажки "true"/"false", выбор - имя константы. Недописанный текст суммы или даты хранится как введён,
 * чтобы после восстановления пользователь увидел ровно то, что печатал.
 */

import {
  h, parseMoney, formatMoney, canonicalMoney, displayMoney, parseDate, canonicalDate, ruDate,
} from './util.js';

/** Счётчик идентификаторов полей. */
let fieldSeq = 0;

/**
 * Проверяет сумму.
 * @param {string} text текст поля
 * @param {object} [options] {positive: сумма больше нуля, allowEmpty: пустое поле допустимо, allowNegative}
 * @returns {boolean} верна ли сумма
 */
export function isValidMoney(text, { positive = false, allowEmpty = false, allowNegative = false } = {}) {
  const t = String(text ?? '').trim();
  if (!t) return allowEmpty;
  const minor = parseMoney(t);
  if (minor === null) return false;
  if (positive) return minor > 0;
  return allowNegative || minor >= 0;
}

/**
 * Проверяет дату.
 * @param {string} text текст поля (ДД.ММ.ГГГГ или ISO)
 * @param {boolean} [allowEmpty=false] пустое поле допустимо
 * @returns {boolean} верна ли дата
 */
export function isValidDate(text, allowEmpty = false) {
  const t = String(text ?? '').trim();
  if (!t) return allowEmpty;
  return parseDate(t) !== null;
}

/**
 * Форма из именованных полей в сетке «подпись - поле».
 */
export class Form {
  /**
   * @param {object} [options] параметры
   * @param {(name: string) => void} [options.onChange] вызывается при каждом изменении поля
   */
  constructor({ onChange } = {}) {
    this.onChange = onChange || (() => {});
    this.el = h('div', { class: 'form-grid' });
    /** @type {Map<string, {spec: object, control: HTMLElement, row: HTMLElement, label: HTMLElement|null}>} */
    this.fields = new Map();
  }

  /**
   * Добавляет заголовок раздела формы.
   * @param {string} title текст
   * @returns {HTMLElement} элемент
   */
  section(title) {
    const el = h('div', { class: 'form-section', text: title });
    this.el.append(el);
    return el;
  }

  /**
   * Добавляет произвольную строку «подпись - содержимое».
   * @param {string} label подпись
   * @param {Node} node содержимое
   * @returns {HTMLElement} строка
   */
  addRow(label, node) {
    const row = h('div', { class: 'form-row' },
      h('span', { class: 'form-label', text: label }),
      h('div', { class: 'form-control' }, node));
    this.el.append(row);
    return row;
  }

  /**
   * Добавляет поле.
   * @param {string} name имя поля (ключ WindowState.fields)
   * @param {object} spec описание
   * @param {string} spec.label подпись
   * @param {'text'|'money'|'date'|'select'|'checkbox'|'number'|'textarea'|'radio'} [spec.type='text'] вид
   * @param {Array<[string, string]>} [spec.options] варианты для select и radio: [значение, подпись]
   * @param {string} [spec.placeholder] подсказка в поле
   * @param {string} [spec.hint] пояснение под полем
   * @param {string} [spec.suffix] текст после поля (валюта)
   * @param {Array<string>} [spec.list] варианты автодополнения (datalist)
   * @param {number} [spec.min] минимум числа
   * @param {number} [spec.max] максимум числа
   * @param {number} [spec.step] шаг числа
   * @param {number} [spec.rows] строк у textarea
   * @param {string} [spec.title] всплывающая подсказка (Tooltip → setToolTipText → title)
   * @returns {HTMLElement} строка формы
   */
  add(name, spec) {
    const id = `field-${++fieldSeq}-${name}`;
    const type = spec.type || 'text';
    let control;
    let widget;
    switch (type) {
      case 'money': {
        control = h('input', {
          type: 'text', id, class: 'input money-input', inputmode: 'decimal', autocomplete: 'off',
          placeholder: spec.placeholder ?? '0,00', title: spec.title || null,
        });
        // При уходе из поля верная сумма форматируется с разделителями тысяч: «95 000,00».
        control.addEventListener('blur', () => {
          const minor = parseMoney(control.value);
          if (minor !== null && control.value !== formatMoney(minor)) {
            control.value = formatMoney(minor);
            this.onChange(name);
          }
        });
        widget = spec.suffix ? h('span', { class: 'with-suffix' }, control, h('span', { class: 'suffix', text: spec.suffix })) : control;
        break;
      }
      case 'date': {
        control = h('input', {
          type: 'text', id, class: 'input date-input', autocomplete: 'off', placeholder: spec.placeholder ?? 'ДД.ММ.ГГГГ',
          title: spec.title || 'Дата в виде ДД.ММ.ГГГГ',
        });
        // Скрытый системный календарь браузера открывается кнопкой; текстовое поле хранит ввод как есть.
        const picker = h('input', { type: 'date', class: 'date-picker', tabindex: '-1', 'aria-hidden': 'true' });
        const button = h('button', { type: 'button', class: 'btn icon date-button', title: 'Выбрать в календаре', 'aria-label': 'Выбрать в календаре', text: '📅' });
        button.addEventListener('click', () => {
          picker.value = parseDate(control.value) || '';
          try {
            picker.showPicker();
          } catch {
            picker.focus();
          }
        });
        picker.addEventListener('change', () => {
          if (picker.value) {
            control.value = ruDate(picker.value);
            this.onChange(name);
          }
        });
        control.addEventListener('blur', () => {
          const iso = parseDate(control.value);
          if (iso && control.value !== ruDate(iso)) {
            control.value = ruDate(iso);
            this.onChange(name);
          }
        });
        widget = h('span', { class: 'date-field' }, control, button, picker);
        break;
      }
      case 'select':
        control = h('select', { id, class: 'input', title: spec.title || null },
          (spec.options || []).map(([value, text]) => h('option', { value, text })));
        widget = control;
        break;
      case 'checkbox':
        control = h('input', { type: 'checkbox', id, title: spec.title || null });
        widget = control;
        break;
      case 'number':
        control = h('input', {
          type: 'number', id, class: 'input number-input', min: spec.min ?? null, max: spec.max ?? null,
          step: spec.step ?? 1, title: spec.title || null, inputmode: 'numeric',
        });
        widget = spec.suffix ? h('span', { class: 'with-suffix' }, control, h('span', { class: 'suffix', text: spec.suffix })) : control;
        break;
      case 'textarea':
        control = h('textarea', { id, class: 'input', rows: spec.rows || 2, placeholder: spec.placeholder || null });
        widget = control;
        break;
      case 'radio': {
        control = h('div', { class: 'radio-group', role: 'radiogroup', id, 'aria-label': spec.label });
        const groupName = `${id}-group`;
        for (const [value, text] of spec.options || []) {
          control.append(h('label', { class: 'radio-label' },
            h('input', { type: 'radio', name: groupName, value }), h('span', { text })));
        }
        widget = control;
        break;
      }
      default: {
        let listId = null;
        let datalist = null;
        if (spec.list && spec.list.length) {
          listId = `${id}-list`;
          datalist = h('datalist', { id: listId }, spec.list.map((v) => h('option', { value: v })));
        }
        control = h('input', {
          type: 'text', id, class: 'input', autocomplete: 'off', list: listId, placeholder: spec.placeholder || null,
          title: spec.title || null,
        });
        widget = datalist ? h('span', { class: 'with-list' }, control, datalist) : control;
      }
    }
    /** Сообщает форме об изменении поля (ввод текста или выбор значения). */
    const notify = () => this.onChange(name);
    control.addEventListener('input', notify);
    control.addEventListener('change', notify);

    let row;
    let label = null;
    if (type === 'checkbox') {
      label = h('label', { class: 'check-label', for: id, text: spec.label });
      row = h('div', { class: 'form-row check-row' }, h('span', { class: 'form-label' }),
        h('div', { class: 'form-control' }, h('span', { class: 'check-wrap' }, widget, label),
          spec.hint ? h('div', { class: 'field-hint', text: spec.hint }) : null));
    } else {
      label = type === 'radio'
        ? h('span', { class: 'form-label', text: spec.label })
        : h('label', { class: 'form-label', for: id, text: spec.label });
      row = h('div', { class: 'form-row' }, label,
        h('div', { class: 'form-control' }, widget, spec.hint ? h('div', { class: 'field-hint', text: spec.hint }) : null));
    }
    this.el.append(row);
    this.fields.set(name, { spec: { ...spec, type }, control, row, label });
    return row;
  }

  /**
   * Элемент управления поля.
   * @param {string} name имя
   * @returns {HTMLElement|undefined} элемент
   */
  control(name) {
    return this.fields.get(name)?.control;
  }

  /**
   * Каноническое значение поля.
   * @param {string} name имя
   * @returns {string} значение
   */
  value(name) {
    const field = this.fields.get(name);
    if (!field) return '';
    const { spec, control } = field;
    switch (spec.type) {
      case 'money':
        return canonicalMoney(/** @type {HTMLInputElement} */ (control).value);
      case 'date':
        return canonicalDate(/** @type {HTMLInputElement} */ (control).value);
      case 'checkbox':
        return String(/** @type {HTMLInputElement} */ (control).checked);
      case 'radio': {
        const checked = /** @type {HTMLInputElement|null} */ (control.querySelector('input:checked'));
        return checked ? checked.value : '';
      }
      default:
        return /** @type {HTMLInputElement} */ (control).value;
    }
  }

  /**
   * Сырой текст поля (для проверки).
   * @param {string} name имя
   * @returns {string} текст
   */
  text(name) {
    const field = this.fields.get(name);
    if (!field) return '';
    if (field.spec.type === 'checkbox' || field.spec.type === 'radio') return this.value(name);
    return /** @type {HTMLInputElement} */ (field.control).value;
  }

  /**
   * Устанавливает поле по каноническому значению.
   * @param {string} name имя
   * @param {string|boolean|number|null|undefined} value значение
   */
  set(name, value) {
    const field = this.fields.get(name);
    if (!field || value === undefined) return;
    const { spec, control } = field;
    const v = value === null ? '' : String(value);
    switch (spec.type) {
      case 'money':
        /** @type {HTMLInputElement} */ (control).value = displayMoney(v);
        break;
      case 'date':
        /** @type {HTMLInputElement} */ (control).value = /^\d{4}-\d{2}-\d{2}$/.test(v) ? ruDate(v) : v;
        break;
      case 'checkbox':
        /** @type {HTMLInputElement} */ (control).checked = v === 'true';
        break;
      case 'radio':
        for (const input of control.querySelectorAll('input')) {
          /** @type {HTMLInputElement} */ (input).checked = /** @type {HTMLInputElement} */ (input).value === v;
        }
        break;
      case 'select': {
        const select = /** @type {HTMLSelectElement} */ (control);
        if (v && ![...select.options].some((o) => o.value === v)) {
          // Значение не из списка (например, восстановленная пользовательская валюта) - добавляем вариант.
          select.append(h('option', { value: v, text: v }));
        }
        select.value = v;
        break;
      }
      default:
        /** @type {HTMLInputElement} */ (control).value = v;
    }
  }

  /** @returns {object} все поля в канонической форме */
  collect() {
    const result = {};
    for (const name of this.fields.keys()) {
      result[name] = this.value(name);
    }
    return result;
  }

  /**
   * Заполняет поля по объекту канонических значений (неизвестные ключи пропускаются).
   * @param {object} values значения
   */
  fill(values) {
    for (const [name, value] of Object.entries(values || {})) {
      if (this.fields.has(name)) this.set(name, value);
    }
  }

  /**
   * Показывает или скрывает строку поля.
   * @param {string} name имя
   * @param {boolean} visible видимость
   */
  setVisible(name, visible) {
    const field = this.fields.get(name);
    if (field) field.row.hidden = !visible;
  }

  /**
   * Включает или отключает поле.
   * @param {string} name имя
   * @param {boolean} enabled доступность
   */
  setEnabled(name, enabled) {
    const field = this.fields.get(name);
    if (!field) return;
    for (const el of field.row.querySelectorAll('input,select,textarea,button')) {
      /** @type {HTMLInputElement} */ (el).disabled = !enabled;
    }
    field.row.classList.toggle('disabled', !enabled);
  }

  /**
   * Меняет подпись поля.
   * @param {string} name имя
   * @param {string} text подпись
   */
  setLabel(name, text) {
    const field = this.fields.get(name);
    if (field && field.label) field.label.textContent = text;
  }

  /**
   * Переводит фокус на поле.
   * @param {string} name имя
   */
  focus(name) {
    const field = this.fields.get(name);
    if (!field) return;
    const target = field.spec.type === 'radio' ? field.control.querySelector('input') : field.control;
    if (target) /** @type {HTMLElement} */ (target).focus();
  }
}
