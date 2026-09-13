/**
 * @file Диалоги тонкого клиента.
 *
 * Базовый диалог AppDialog поверх элемента <dialog> и <form method="dialog"> — аналог Dialog<R> + DialogPane:
 * заголовок, текст шапки, содержимое, раскрываемые подробности (expandableContent), строка проверки, кнопки со
 * значениями (ButtonType), преобразователь результата (resultConverter) и Promise вместо resultProperty.
 * Поверх него построены сообщения (Alert), ввод строки (TextInputDialog), выбор из списка (ChoiceDialog)
 * и серверный обозреватель файлов и папок (FileChooser / DirectoryChooser).
 *
 * Если у диалога задан windowType, он регистрируется на сервере как восстанавливаемое окно (windows.js).
 */

import { h, clear, planNameProblem } from './util.js';
import { api, ApiError } from './api.js';
import { notifyShown } from './store.js';
import { TrackedWindow } from './windows.js';
import { closeAllMenus } from './menu.js';
import { hideTooltip, hidePopover } from './popup.js';

/**
 * Роли кнопок, как ButtonBar.ButtonData в JavaFX.
 * JavaFX: ButtonBar.ButtonData → Swing: роль SwingButtonType → Web: порядок и оформление <button>
 */
export const ButtonRole = Object.freeze({
  OK_DONE: 'OK_DONE',
  CANCEL_CLOSE: 'CANCEL_CLOSE',
  OTHER: 'OTHER',
  LEFT: 'LEFT',
});

/**
 * Создаёт тип кнопки диалога.
 * JavaFX: ButtonType → Swing: SwingButtonType → Web: <button value> (значение возвращается как returnValue)
 * @param {string} text подпись
 * @param {string} value значение, которое вернёт диалог
 * @param {string} [role=ButtonRole.OTHER] роль
 * @param {object} [extra] {title} — подсказка
 * @returns {Readonly<{text:string, value:string, role:string, title?:string}>} тип кнопки
 */
export function buttonType(text, value, role = ButtonRole.OTHER, extra = {}) {
  return Object.freeze({ text, value, role, ...extra });
}

/** Стандартные типы кнопок (ButtonType.OK, ButtonType.CANCEL…). */
export const ButtonTypes = Object.freeze({
  OK: buttonType('OK', 'ok', ButtonRole.OK_DONE),
  CANCEL: buttonType('Отмена', 'cancel', ButtonRole.CANCEL_CLOSE),
  CLOSE: buttonType('Закрыть', 'close', ButtonRole.CANCEL_CLOSE),
  YES: buttonType('Да', 'yes', ButtonRole.OK_DONE),
  NO: buttonType('Нет', 'no', ButtonRole.CANCEL_CLOSE),
});

/** Счётчик для уникальных идентификаторов элементов. */
let dialogSeq = 0;

/** Открытые диалоги (для проверки «есть ли модальный диалог»). */
const openDialogs = new Set();

/**
 * Текст ошибки для показа пользователю.
 * @param {unknown} e ошибка
 * @returns {string} сообщение
 */
export function errorMessage(e) {
  if (e instanceof ApiError) return e.message;
  if (e && typeof e === 'object' && 'message' in e) return String(e.message);
  return String(e);
}

/** @returns {boolean} открыт ли хотя бы один модальный диалог */
export function hasModalDialog() {
  return [...openDialogs].some((d) => d.options.modal);
}

/**
 * Базовый диалог.
 * JavaFX: Dialog<R> → Swing: SwingDialog<R> (JDialog DOCUMENT_MODAL + resultConverter) → Web: <dialog> + Promise
 */
export class AppDialog {
  /**
   * @param {object} options параметры
   * @param {string} options.title заголовок окна
   * @param {string} [options.header] текст шапки (headerText)
   * @param {string} [options.icon] значок шапки (ℹ ⚠ ✖ ?)
   * @param {Node|string} [options.content] содержимое
   * @param {string|Node} [options.details] раскрываемые подробности (expandableContent)
   * @param {string} [options.detailsLabel] подпись раскрывающей строки
   * @param {Array<object>} [options.buttons] типы кнопок (buttonType)
   * @param {boolean} [options.modal=true] модальный (showModal) или немодальный (show)
   * @param {string} [options.className] дополнительный CSS-класс
   * @param {string} [options.width] ширина (CSS)
   * @param {string} [options.windowType] тип восстанавливаемого окна — диалог регистрируется на сервере
   * @param {object} [options.context] контекст окна
   * @param {string} [options.ownerId] владелец окна
   * @param {object|null} [options.existing] состояние окна с сервера при восстановлении
   * @param {() => object} [options.collect] текущие поля формы в канонической форме
   * @param {(value: string, dialog: AppDialog) => (boolean|void|Promise<boolean|void>)} [options.onButton]
   *   обработчик кнопки; false — диалог остаётся открытым
   * @param {(value: string, dialog: AppDialog) => any} [options.resultConverter] результат диалога по кнопке
   * @param {string|(() => string)} [options.defaultButton] значение кнопки для Enter в поле ввода
   * @param {HTMLElement|string} [options.initialFocus] элемент или селектор первого фокуса
   * @param {(result: any) => void} [options.onClose] вызывается после закрытия
   */
  constructor(options) {
    this.options = { modal: true, buttons: [ButtonTypes.OK], ...options };
    this.closed = false;
    this.busy = false;
    this.serverClosed = false;
    this.buttons = new Map();
    this.disabledValues = new Set();
    this.tracker = this.options.windowType
      ? new TrackedWindow(this.options.windowType, {
        modal: this.options.modal,
        ownerId: this.options.ownerId || 'main',
        context: this.options.context || {},
        existing: this.options.existing || null,
      })
      : null;
    this.promise = new Promise((resolve) => {
      this.resolve = resolve;
    });
  }

  /** @returns {string|null} идентификатор окна на сервере (w1…) или null */
  get windowId() {
    return this.tracker ? this.tracker.id : null;
  }

  /**
   * Ждёт регистрации окна и возвращает его идентификатор (передаётся в правку как windowId).
   * @returns {Promise<string|null>} идентификатор
   */
  async windowIdReady() {
    if (!this.tracker || !this.tracker.ready) return null;
    return this.tracker.ready;
  }

  /** Строит DOM диалога. */
  build() {
    const o = this.options;
    const titleId = `dlg-title-${++dialogSeq}`;
    // JavaFX: Dialog<R> → Swing: SwingDialog<R> → Web: <dialog>
    this.el = h('dialog', {
      class: `app-dialog ${o.modal ? 'modal-dialog' : 'modeless'} ${o.className || ''}`.trim(),
      'aria-labelledby': titleId,
    });
    if (o.width) this.el.style.width = o.width;
    // JavaFX: DialogPane → Swing: SwingDialogPane → Web: <form method="dialog">
    this.form = h('form', { method: 'dialog', class: 'dialog-pane', novalidate: true });
    this.titleBar = h('div', { class: 'dialog-titlebar' },
      h('span', { id: titleId, class: 'dialog-title', text: o.title || 'CashPrediction' }),
      h('button', {
        type: 'button', class: 'dialog-x', title: 'Закрыть (Esc)', 'aria-label': 'Закрыть',
        text: '✕', onClick: () => this.cancel(),
      }));
    this.headerEl = h('div', { class: 'dialog-header', hidden: !o.header },
      o.icon ? h('span', { class: 'dialog-icon', 'aria-hidden': 'true', text: o.icon }) : null,
      h('div', { class: 'dialog-header-text', text: o.header || '' }));
    this.contentEl = h('div', { class: 'dialog-content' },
      typeof o.content === 'string' ? h('p', { class: 'dialog-text', text: o.content }) : o.content);
    let details = null;
    if (o.details) {
      // JavaFX: DialogPane.expandableContent → Swing: SwingDialogPane «Подробнее» → Web: <details>
      details = h('details', { class: 'dialog-details' },
        h('summary', { text: o.detailsLabel || 'Подробнее' }),
        typeof o.details === 'string' ? h('pre', { class: 'details-text', tabindex: '0', text: o.details }) : o.details);
    }
    this.validationEl = h('div', { class: 'dialog-validation', 'aria-live': 'polite', hidden: true });
    this.errorEl = h('div', { class: 'dialog-error', role: 'alert', hidden: true });
    this.buttonBar = h('div', { class: 'dialog-buttons' });
    const left = h('div', { class: 'dialog-buttons-left' });
    const right = h('div', { class: 'dialog-buttons-right' });
    // Кнопки идут в порядке, заданном вызывающим кодом ([Сохранить][Не сохранять][Отмена]), только кнопка отмены
    // всегда последняя, а LEFT уходит в левую группу. Сортировка массивов в браузерах устойчива.
    const sorted = [...o.buttons].sort((a, b) => (a.role === ButtonRole.CANCEL_CLOSE ? 1 : 0) - (b.role === ButtonRole.CANCEL_CLOSE ? 1 : 0));
    for (const type of sorted) {
      // JavaFX: ButtonType → Swing: SwingButtonType → Web: <button value>
      const button = h('button', {
        type: 'submit',
        value: type.value,
        class: `btn${type.role === ButtonRole.OK_DONE ? ' primary' : ''}`,
        title: type.title || null,
        text: type.text,
      });
      this.buttons.set(type.value, button);
      (type.role === ButtonRole.LEFT ? left : right).append(button);
    }
    this.buttonBar.append(left, right);
    // append превратил бы null в текст «null»: блок подробностей добавляется, только если он есть.
    this.form.append(...[this.titleBar, this.headerEl, this.contentEl, details, this.validationEl, this.errorEl, this.buttonBar]
      .filter(Boolean));
    this.el.append(this.form);

    this.form.addEventListener('submit', (e) => {
      e.preventDefault();
      const submitter = /** @type {HTMLButtonElement|null} */ (e.submitter);
      const value = submitter && submitter.value ? submitter.value : this.defaultValue();
      if (value) this.handle(value);
    });
    // Enter в поле ввода — кнопка по умолчанию (а не первая кнопка формы, как решил бы браузер).
    this.form.addEventListener('keydown', (e) => {
      const target = /** @type {HTMLElement} */ (e.target);
      if (e.key === 'Enter' && !e.isComposing && target instanceof HTMLInputElement
          && !['button', 'submit', 'checkbox', 'radio', 'range', 'file'].includes(target.type)) {
        e.preventDefault();
        const value = this.defaultValue();
        if (value) this.handle(value);
      }
      if (e.key === 'Escape' && !this.options.modal) {
        e.preventDefault();
        this.cancel();
      }
    });
    // Esc у модального <dialog>: событие cancel — выполняем кнопку отмены.
    this.el.addEventListener('cancel', (e) => {
      e.preventDefault();
      this.cancel();
    });
    // Браузер может закрыть диалог сам (повторный Esc): считаем это отменой.
    this.el.addEventListener('close', () => {
      if (!this.closed) this.finish(this.cancelValue() || '');
    });
    this.installDrag();
  }

  /** @returns {string} значение кнопки по умолчанию */
  defaultValue() {
    const d = this.options.defaultButton;
    const value = typeof d === 'function' ? d() : d;
    if (value) return value;
    const ok = this.options.buttons.find((b) => b.role === ButtonRole.OK_DONE);
    return ok ? ok.value : '';
  }

  /** @returns {string} значение кнопки отмены ('' — нет кнопки отмены) */
  cancelValue() {
    const cancel = this.options.buttons.find((b) => b.role === ButtonRole.CANCEL_CLOSE);
    if (cancel) return cancel.value;
    return this.options.buttons.length === 1 ? this.options.buttons[0].value : '';
  }

  /** Отмена (Esc, крестик заголовка). */
  cancel() {
    const value = this.cancelValue();
    if (value) {
      this.handle(value);
    } else if (!this.busy) {
      this.finish('');
    }
  }

  /**
   * Показывает диалог.
   * JavaFX: Dialog.show() + resultProperty → Swing: SwingDialog.open(callback) → Web: openDialog(): Promise<R>
   * @returns {Promise<any>} результат (resultConverter или значение кнопки)
   */
  show() {
    closeAllMenus();
    hideTooltip();
    hidePopover();
    this.build();
    this.previousFocus = /** @type {HTMLElement|null} */ (document.activeElement);
    document.body.append(this.el);
    if (this.options.modal) {
      this.el.showModal();
    } else {
      this.el.show();
      this.placeAt(window.innerWidth - Math.min(this.el.offsetWidth, window.innerWidth) - 24, 96);
    }
    const bounds = this.options.existing && this.options.existing.bounds;
    if (bounds && bounds.width > 0) {
      this.placeAt(bounds.x, bounds.y);
    }
    openDialogs.add(this);
    this.focusInitial();
    if (this.tracker) {
      this.tracker.register(this.collect()).then((id) => {
        if (id) notifyShown(id);
      });
    }
    return this.promise;
  }

  /** Переводит фокус на первый элемент. */
  focusInitial() {
    const f = this.options.initialFocus;
    let target = typeof f === 'string' ? this.el.querySelector(f) : f;
    if (!target) {
      target = this.contentEl.querySelector('input:not([type=hidden]):not([disabled]),select,textarea,[tabindex="0"]')
        || this.buttons.get(this.defaultValue()) || this.el.querySelector('button');
    }
    if (target) {
      target.focus();
      if (target instanceof HTMLInputElement && target.type === 'text') target.select();
    }
  }

  /** @returns {object} текущие поля формы для снимка сессии */
  collect() {
    return this.options.collect ? this.options.collect() : {};
  }

  /** Поле изменилось: отправить значения на сервер (аналог SessionRecorder.touch). */
  touch() {
    if (this.tracker && !this.closed) this.tracker.update(this.collect());
  }

  /**
   * Меняет контекст восстанавливаемого окна.
   * @param {object} context ключи контекста
   */
  setContext(context) {
    if (this.tracker) this.tracker.setContext(context);
  }

  /** Сервер закрыл окно сам (правка пришла с windowId): DELETE при закрытии не нужен. */
  markServerClosed() {
    this.serverClosed = true;
  }

  /**
   * Обрабатывает нажатие кнопки.
   * @param {string} value значение кнопки
   * @returns {Promise<void>} завершение
   */
  async handle(value) {
    if (this.busy || this.closed) return;
    const button = this.buttons.get(value);
    if (button && this.disabledValues.has(value)) return;
    this.busy = true;
    this.form.classList.add('busy');
    this.syncButtons();
    let keepOpen = false;
    try {
      if (this.options.onButton) {
        this.clearError();
        // Поля уходят на сервер до правки с windowId: запоздавший PUT попал бы в уже закрытое окно.
        if (this.tracker) await this.tracker.flush();
        const result = await this.options.onButton(value, this);
        keepOpen = result === false;
      }
    } catch (e) {
      if (e instanceof ApiError && e.details) console.error(e.details);
      this.setError(errorMessage(e));
      keepOpen = true;
    } finally {
      this.busy = false;
      if (this.form) this.form.classList.remove('busy');
    }
    if (keepOpen) {
      this.syncButtons();
    } else {
      this.finish(value);
    }
  }

  /**
   * Закрывает диалог с результатом.
   * @param {string} value значение кнопки
   */
  finish(value) {
    if (this.closed) return;
    this.closed = true;
    let result;
    try {
      result = this.options.resultConverter ? this.options.resultConverter(value, this) : value;
    } catch (e) {
      console.error(e);
      result = null;
    }
    openDialogs.delete(this);
    if (this.el.open) this.el.close();
    this.el.remove();
    if (this.tracker) this.tracker.close({ serverClosed: this.serverClosed });
    if (this.previousFocus && this.previousFocus.isConnected) {
      this.previousFocus.focus({ preventScroll: true });
    }
    if (this.options.onClose) this.options.onClose(result);
    this.resolve(result);
  }

  /**
   * Закрывает диалог программно (например, когда его данные потеряли смысл).
   * @param {string} [value=''] значение
   */
  close(value = '') {
    this.finish(value);
  }

  /**
   * Кнопка по значению.
   * JavaFX: DialogPane.lookupButton(ButtonType) → Swing: SwingDialogPane.lookupButton → Web: querySelector('button[value]')
   * @param {string} value значение
   * @returns {HTMLButtonElement|undefined} кнопка
   */
  lookupButton(value) {
    return this.buttons.get(value);
  }

  /**
   * Отключает или включает кнопку (OK отключён, пока форма неверна).
   * @param {string} value значение кнопки
   * @param {boolean} disabled отключить
   */
  setButtonDisabled(value, disabled) {
    if (disabled) this.disabledValues.add(value);
    else this.disabledValues.delete(value);
    this.syncButtons();
  }

  /**
   * Показывает или скрывает кнопку.
   * @param {string} value значение кнопки
   * @param {boolean} visible видимость
   */
  setButtonVisible(value, visible) {
    const button = this.buttons.get(value);
    if (button) button.hidden = !visible;
  }

  /** Приводит состояние кнопок к busy/disabledValues. */
  syncButtons() {
    for (const [value, button] of this.buttons) {
      button.disabled = this.busy || this.disabledValues.has(value);
    }
  }

  /**
   * Сообщение проверки формы (серым под содержимым); пустая строка скрывает его.
   * @param {string} message текст
   */
  setValidation(message) {
    if (!this.validationEl) return;
    this.validationEl.textContent = message || '';
    this.validationEl.hidden = !message;
  }

  /**
   * Ошибка выполнения (красным); пустая строка скрывает её.
   * @param {string} message текст
   */
  setError(message) {
    if (!this.errorEl) return;
    this.errorEl.textContent = message || '';
    this.errorEl.hidden = !message;
  }

  /** Скрывает ошибку. */
  clearError() {
    this.setError('');
  }

  /**
   * Меняет текст шапки.
   * @param {string} text текст
   */
  setHeader(text) {
    if (!this.headerEl) return;
    const textEl = this.headerEl.querySelector('.dialog-header-text');
    if (textEl) textEl.textContent = text;
    this.headerEl.hidden = !text;
  }

  /** Перетаскивание окна за заголовок. */
  installDrag() {
    const bar = this.titleBar;
    bar.addEventListener('pointerdown', (e) => {
      if (e.button !== 0 || /** @type {HTMLElement} */ (e.target).closest('button')) return;
      const r = this.el.getBoundingClientRect();
      const dx = e.clientX - r.left;
      const dy = e.clientY - r.top;
      bar.setPointerCapture(e.pointerId);
      /** @param {PointerEvent} ev событие перемещения */
      const move = (ev) => this.placeAt(ev.clientX - dx, ev.clientY - dy);
      /** Завершение перетаскивания. */
      const up = () => {
        bar.removeEventListener('pointermove', move);
        bar.removeEventListener('pointerup', up);
        bar.removeEventListener('pointercancel', up);
        this.reportBounds();
      };
      bar.addEventListener('pointermove', move);
      bar.addEventListener('pointerup', up);
      bar.addEventListener('pointercancel', up);
    });
  }

  /**
   * Ставит окно в точку, не давая уйти за пределы страницы.
   * @param {number} left левый край
   * @param {number} top верхний край
   */
  placeAt(left, top) {
    const r = this.el.getBoundingClientRect();
    const x = Math.max(0, Math.min(left, window.innerWidth - Math.min(r.width, 160)));
    const y = Math.max(0, Math.min(top, window.innerHeight - 48));
    Object.assign(this.el.style, {
      position: 'fixed', margin: '0', left: `${Math.round(x)}px`, top: `${Math.round(y)}px`, right: 'auto', bottom: 'auto',
    });
  }

  /** Отправляет границы окна на сервер. */
  reportBounds() {
    if (!this.tracker || !this.el) return;
    const r = this.el.getBoundingClientRect();
    this.tracker.setBounds({
      x: Math.round(r.left), y: Math.round(r.top), width: Math.round(r.width), height: Math.round(r.height),
    });
  }
}

/**
 * Открывает диалог и возвращает Promise с результатом.
 * JavaFX: Dialog<R>.showAndWait / DialogHost.open → Swing: SwingDialogHost.open → Web: openDialog(): Promise<R>
 * @param {object} options параметры AppDialog
 * @returns {Promise<any>} результат
 */
export function openDialog(options) {
  return new AppDialog(options).show();
}

/** Значки и заголовки сообщений по типу Alert.AlertType. */
const ALERT_KINDS = {
  INFORMATION: { icon: 'ℹ', title: 'Информация' },
  WARNING: { icon: '⚠', title: 'Внимание' },
  ERROR: { icon: '✖', title: 'Ошибка' },
  CONFIRMATION: { icon: '?', title: 'Подтверждение' },
  NONE: { icon: '', title: 'CashPrediction' },
};

/**
 * Сообщение с кнопками.
 * JavaFX: Alert → Swing: SwingAlert (JOptionPane.showMessageDialog / showOptionDialog) → Web: <dialog class="alert">
 * @param {object} o параметры
 * @param {'INFORMATION'|'WARNING'|'ERROR'|'CONFIRMATION'|'NONE'} [o.type='INFORMATION'] вид (AlertType)
 * @param {string} [o.title] заголовок окна
 * @param {string} [o.header] текст шапки
 * @param {string|Node} [o.content] текст сообщения
 * @param {string|Node} [o.details] раскрываемые подробности
 * @param {Array<object>} [o.buttons] кнопки (по умолчанию OK, у CONFIRMATION — OK и Отмена)
 * @param {object} [o.rest] остальные параметры AppDialog (windowType, context, existing, onButton)
 * @returns {Promise<string>} значение нажатой кнопки
 */
export function alertDialog(o) {
  const type = o.type || 'INFORMATION';
  const kind = ALERT_KINDS[type] || ALERT_KINDS.NONE;
  const buttons = o.buttons || (type === 'CONFIRMATION' ? [ButtonTypes.OK, ButtonTypes.CANCEL] : [ButtonTypes.OK]);
  const content = typeof o.content === 'string'
    ? h('p', { class: 'alert-text', text: o.content })
    : o.content;
  return openDialog({
    ...o,
    title: o.title || kind.title,
    icon: kind.icon,
    buttons,
    content,
    className: `alert alert-${type.toLowerCase()} ${o.className || ''}`.trim(),
    width: o.width || null,
  });
}

/**
 * Сообщение об ошибке (диалог 16 «Ошибка»): текст ошибки и стек сервера в подробностях.
 * JavaFX: Alert(ERROR) + expandableContent → Swing: SwingAlert.error → Web: <dialog class="alert alert-error">
 * @param {unknown} error ошибка
 * @param {string} [header='Операция не выполнена'] текст шапки
 * @returns {Promise<string>} закрытие
 */
export function errorAlert(error, header = 'Операция не выполнена') {
  const details = error instanceof ApiError ? error.details : (error && error.stack) || null;
  return alertDialog({ type: 'ERROR', title: 'Ошибка', header, content: errorMessage(error), details });
}

/**
 * Диалог ввода строки.
 * JavaFX: TextInputDialog → Swing: JOptionPane.showInputDialog(parent, msg, title, QUESTION_MESSAGE, null, null, initial)
 * → Web: <dialog> с <input>
 * @param {object} o параметры
 * @param {string} o.title заголовок
 * @param {string} [o.header] шапка
 * @param {string} o.label подпись поля
 * @param {string} [o.value] начальное значение (в каноническом виде)
 * @param {string} [o.placeholder] подсказка в поле
 * @param {string} [o.inputMode] inputmode ("decimal" для сумм)
 * @param {string} [o.hint] пояснение под полем
 * @param {string} o.purpose назначение окна (rename, reconcile, customCurrency) — контекст TEXT_INPUT
 * @param {object|null} [o.existing] состояние окна с сервера
 * @param {(text: string) => (string|null)} [o.validate] проверка: текст ошибки или null
 * @param {(text: string) => string} [o.toCanonical] перевод введённого текста в каноническую форму
 * @param {(value: string) => string} [o.toDisplay] перевод канонического значения в текст поля
 * @param {(value: string, dialog: AppDialog) => Promise<void>} [o.onSubmit] применение; исключение оставляет окно открытым
 * @returns {Promise<string|null>} каноническое значение или null при отмене
 */
export function textInputDialog(o) {
  const toCanonical = o.toCanonical || ((t) => t);
  const toDisplay = o.toDisplay || ((v) => v);
  const input = h('input', {
    type: 'text', class: 'input text-input', autocomplete: 'off', spellcheck: 'false',
    inputmode: o.inputMode || null, placeholder: o.placeholder || null, 'aria-label': o.label,
  });
  const start = o.existing && o.existing.fields && o.existing.fields.value !== undefined ? o.existing.fields.value : (o.value ?? '');
  input.value = toDisplay(String(start));
  const content = h('div', { class: 'text-input-content' },
    h('label', { class: 'field-label' }, h('span', { text: o.label }), input),
    o.hint ? h('div', { class: 'field-hint', text: o.hint }) : null);
  const dialog = new AppDialog({
    title: o.title,
    header: o.header,
    icon: '✎',
    content,
    buttons: [ButtonTypes.OK, ButtonTypes.CANCEL],
    windowType: 'TEXT_INPUT',
    context: { purpose: o.purpose, ...(o.context || {}) },
    existing: o.existing || null,
    collect: () => ({ value: toCanonical(input.value) }),
    initialFocus: input,
    className: 'text-input-dialog',
    onButton: async (value, d) => {
      if (value !== 'ok') return true;
      const message = o.validate ? o.validate(input.value) : null;
      if (message) {
        d.setValidation(message);
        return false;
      }
      if (o.onSubmit) await o.onSubmit(toCanonical(input.value), d);
      return true;
    },
    resultConverter: (value) => (value === 'ok' ? toCanonical(input.value) : null),
  });
  const promise = dialog.show();
  /** Живая проверка: OK отключён, пока значение неверно. */
  const check = () => {
    const message = o.validate ? o.validate(input.value) : null;
    dialog.setButtonDisabled('ok', !!message);
    dialog.setValidation(message || '');
  };
  input.addEventListener('input', () => {
    dialog.clearError();
    check();
    dialog.touch();
  });
  check();
  return promise;
}

/**
 * Диалог выбора из списка.
 * JavaFX: ChoiceDialog<T> → Swing: JOptionPane.showInputDialog(..., selectionValues[], initial) → Web: <dialog> с <select>
 * @param {object} o параметры
 * @param {string} o.title заголовок
 * @param {string} [o.header] шапка
 * @param {string} o.label подпись списка
 * @param {Array<{value:string, text:string}>} o.choices варианты
 * @param {string} [o.value] выбранный вариант
 * @param {number} [o.listSize] высота списка в строках (0 — выпадающий список)
 * @param {string} o.purpose назначение окна (currency, openPlan) — контекст CHOICE
 * @param {object|null} [o.existing] состояние окна с сервера
 * @param {(value: string, dialog: AppDialog) => Promise<void>} [o.onSubmit] применение
 * @param {string} [o.okText] подпись кнопки OK
 * @returns {Promise<string|null>} выбранное значение или null
 */
export function choiceDialog(o) {
  const select = h('select', {
    class: 'input choice-select', 'aria-label': o.label, size: o.listSize && o.listSize > 1 ? String(o.listSize) : null,
  }, o.choices.map((c) => h('option', { value: c.value, text: c.text })));
  const start = o.existing && o.existing.fields && o.existing.fields.value ? o.existing.fields.value : o.value;
  if (start && o.choices.some((c) => c.value === start)) {
    select.value = start;
  } else if (o.choices.length) {
    select.value = o.choices[0].value;
  }
  const ok = buttonType(o.okText || 'OK', 'ok', ButtonRole.OK_DONE);
  const dialog = new AppDialog({
    title: o.title,
    header: o.header,
    icon: '☰',
    content: h('label', { class: 'field-label' }, h('span', { text: o.label }), select),
    buttons: [ok, ButtonTypes.CANCEL],
    windowType: 'CHOICE',
    context: { purpose: o.purpose, ...(o.context || {}) },
    existing: o.existing || null,
    collect: () => ({ value: select.value }),
    initialFocus: select,
    className: 'choice-dialog',
    onButton: async (value, d) => {
      if (value !== 'ok') return true;
      if (!select.value) {
        d.setValidation('Выберите вариант');
        return false;
      }
      if (o.onSubmit) await o.onSubmit(select.value, d);
      return true;
    },
    resultConverter: (value) => (value === 'ok' ? select.value : null),
  });
  select.addEventListener('change', () => dialog.touch());
  select.addEventListener('dblclick', () => dialog.handle('ok'));
  return dialog.show();
}

/**
 * Серверный обозреватель: выбор файла плана (*.md) или папки.
 * JavaFX: FileChooser (ExtensionFilter "*.md", initialDirectory = CashMemory) → Swing: JFileChooser(FILES_ONLY)
 * + FileNameExtensionFilter → Web: этот диалог поверх GET /api/fs?mode=md.
 * JavaFX: DirectoryChooser → Swing: JFileChooser(DIRECTORIES_ONLY) → Web: этот диалог поверх GET /api/fs?mode=dirs.
 * Браузер не видит диски компьютера, поэтому список папок отдаёт сервер; окно не восстанавливается после сбоя —
 * как и нативные окна выбора файлов в desktop-клиентах.
 * @param {object} o параметры
 * @param {'md'|'dirs'} [o.mode='md'] файлы планов или только папки
 * @param {string} o.title заголовок
 * @param {boolean} [o.save=false] режим сохранения: поле имени файла
 * @param {string} [o.fileName] начальное имя файла (без .md)
 * @param {string} [o.startPath] начальная папка ('' — CashMemory)
 * @param {string} [o.okText] подпись кнопки подтверждения
 * @returns {Promise<{path:string, folder:string, name:string}|null>} выбор или null
 */
export function fileChooserDialog(o) {
  const mode = o.mode === 'dirs' ? 'dirs' : 'md';
  let current = null;
  let selected = null;
  const pathInput = h('input', { type: 'text', class: 'input fs-path', 'aria-label': 'Папка', spellcheck: 'false' });
  const roots = h('select', { class: 'input fs-roots', 'aria-label': 'Диск', title: 'Диск' });
  const up = h('button', { type: 'button', class: 'btn', text: '↑', title: 'На уровень вверх' });
  const home = h('button', { type: 'button', class: 'btn', text: 'CashMemory', title: 'Папка данных программы' });
  const list = h('ul', { class: 'fs-list', role: 'listbox', tabindex: '0', 'aria-label': 'Содержимое папки' });
  const nameInput = h('input', { type: 'text', class: 'input', 'aria-label': 'Имя файла', spellcheck: 'false' });
  nameInput.value = o.fileName || '';
  const status = h('div', { class: 'fs-status', 'aria-live': 'polite' });
  const content = h('div', { class: 'fs-browser' },
    h('div', { class: 'fs-bar' }, roots, up, home, pathInput),
    list,
    o.save ? h('label', { class: 'field-label fs-name' }, h('span', { text: 'Имя файла' }), nameInput, h('span', { class: 'fs-ext', text: '.md' })) : null,
    h('div', { class: 'fs-filter', text: mode === 'md' ? 'Тип файлов: планы CashPrediction (*.md)' : 'Выберите папку (файлы не показываются)' }),
    status);
  const okText = o.okText || (o.save ? 'Сохранить' : mode === 'dirs' ? 'Выбрать папку' : 'Открыть');
  const dialog = new AppDialog({
    title: o.title,
    icon: mode === 'dirs' ? '📁' : '📄',
    header: mode === 'dirs' ? 'Выберите папку на этом компьютере' : o.save ? 'Куда сохранить план' : 'Выберите файл плана',
    content,
    className: 'file-chooser',
    width: 'min(680px, 96vw)',
    buttons: [buttonType(okText, 'ok', ButtonRole.OK_DONE), ButtonTypes.CANCEL],
    initialFocus: o.save ? nameInput : list,
    onButton: (value, d) => {
      if (value !== 'ok') return true;
      if (!current) return false;
      if (o.save) {
        const name = nameInput.value.trim().replace(/\.md$/i, '');
        if (!name) {
          d.setValidation('Введите имя файла');
          return false;
        }
        // Имя файла станет именем плана: те же правила, что PlanValidator.checkPlanName.
        const problem = planNameProblem(name);
        if (problem) {
          d.setValidation(problem);
          return false;
        }
        return true;
      }
      if (mode === 'md' && (!selected || selected.dir)) {
        d.setValidation('Выберите файл .md');
        return false;
      }
      return true;
    },
    resultConverter: (value) => {
      if (value !== 'ok' || !current) return null;
      if (o.save) return { path: '', folder: current.path, name: nameInput.value.trim().replace(/\.md$/i, '') };
      if (mode === 'dirs') {
        const folder = selected && selected.dir ? selected.path : current.path;
        return { path: folder, folder, name: '' };
      }
      return { path: selected.path, folder: current.path, name: selected.name };
    },
  });

  /**
   * Загружает содержимое папки с сервера.
   * @param {string} path полный путь ('' — CashMemory)
   * @returns {Promise<void>} завершение
   */
  const load = async (path) => {
    status.textContent = 'Загрузка…';
    try {
      const res = await api.get('/api/fs', { path, mode });
      current = res;
      selected = null;
      pathInput.value = res.path;
      up.disabled = !res.parent;
      clear(roots);
      for (const root of res.roots || []) {
        roots.append(h('option', { value: root.path, text: root.name }));
      }
      const root = (res.roots || []).find((r) => res.path.toLowerCase().startsWith(r.path.toLowerCase()));
      if (root) roots.value = root.path;
      renderList(res);
      status.textContent = res.truncated ? 'Показаны первые элементы папки' : '';
      dialog.setValidation('');
      dialog.clearError();
    } catch (e) {
      status.textContent = '';
      dialog.setError(errorMessage(e));
    }
  };

  /**
   * Рисует список элементов папки.
   * @param {object} res ответ /api/fs
   */
  const renderList = (res) => {
    clear(list);
    // Служебные файлы CashMemory (settings.md, web-session*.md) — тоже .md, но это не планы: в списке они только
    // мешали бы и позволили бы случайно «открыть» или перезаписать настройки и снимок сессии.
    const inCashMemory = !!res.cashMemory && String(res.path).toLowerCase() === String(res.cashMemory).toLowerCase();
    const entries = res.entries.filter((entry) => !(inCashMemory && !entry.dir
      && /^(settings\.md|web-session.*\.md)$/i.test(entry.name)));
    if (!entries.length) {
      list.append(h('li', { class: 'fs-empty', text: mode === 'md' ? 'Нет папок и файлов .md' : 'Нет вложенных папок' }));
    }
    for (const entry of entries) {
      const li = h('li', {
        class: `fs-entry ${entry.dir ? 'dir' : 'file'}`, role: 'option', tabindex: '-1', 'aria-selected': 'false',
        title: entry.path,
      },
      h('span', { class: 'fs-icon', 'aria-hidden': 'true', text: entry.dir ? '📁' : '📄' }),
      h('span', { class: 'fs-name-text', text: entry.name }),
      h('span', { class: 'fs-meta', text: entry.dir ? '' : `${Math.max(1, Math.round((entry.size || 0) / 1024))} КБ · ${entry.modifiedText}` }));
      li.addEventListener('click', () => select(entry, li));
      li.addEventListener('dblclick', () => {
        if (entry.dir) load(entry.path);
        else dialog.handle('ok');
      });
      list.append(li);
    }
  };

  /**
   * Выделяет элемент списка.
   * @param {object} entry элемент
   * @param {HTMLElement} li строка списка
   */
  const select = (entry, li) => {
    selected = entry;
    for (const other of list.querySelectorAll('.fs-entry')) other.setAttribute('aria-selected', 'false');
    li.setAttribute('aria-selected', 'true');
    li.focus();
    if (o.save && !entry.dir) nameInput.value = entry.name.replace(/\.md$/i, '');
    dialog.setValidation('');
  };

  list.addEventListener('keydown', (e) => {
    const items = [...list.querySelectorAll('.fs-entry')];
    const index = items.findIndex((li) => li.getAttribute('aria-selected') === 'true');
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const next = Math.max(0, Math.min(items.length - 1, index + (e.key === 'ArrowDown' ? 1 : -1)));
      if (items[next]) items[next].click();
    } else if (e.key === 'Enter' && index >= 0) {
      e.preventDefault();
      e.stopPropagation();
      items[index].dispatchEvent(new MouseEvent('dblclick'));
    } else if (e.key === 'Backspace' && current && current.parent) {
      e.preventDefault();
      load(current.parent);
    }
  });
  up.addEventListener('click', () => current && current.parent && load(current.parent));
  home.addEventListener('click', () => load(''));
  roots.addEventListener('change', () => load(roots.value));
  pathInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      e.stopPropagation();
      load(pathInput.value);
    }
  });
  const promise = dialog.show();
  load(o.startPath || '');
  return promise;
}
