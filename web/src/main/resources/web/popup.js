/**
 * @file Всплывающие элементы без модальности: подсказка (Tooltip), поповер со спарклайном (PopupControl),
 * карточка дня графика (PopupWindow), мини-редактор суммы (Popup) и короткие уведомления.
 */

import { h, svg, clear, debounce, canonicalMoney, displayMoney, parseMoney } from './util.js';
import { api, ApiError } from './api.js';
import { notifyShown } from './store.js';

// ------------------------------------------------------------------ Tooltip

/** Единственный элемент подсказки на странице. */
let tooltipEl = null;
let tooltipTimer = null;

/**
 * Устанавливает кастомную подсказку на элемент (для ячеек таблицы и точек графика, где title неудобен).
 * JavaFX: Tooltip.install → Swing: getToolTipText(MouseEvent) / ToolTipManager → Web: <div class="tooltip">
 * @param {HTMLElement|SVGElement} el элемент
 * @param {(event: MouseEvent) => (string|Node|null)} content функция содержимого (null — без подсказки)
 */
export function installTooltip(el, content) {
  el.addEventListener('mouseenter', (e) => scheduleTooltip(e, content));
  el.addEventListener('mousemove', (e) => moveTooltip(e));
  el.addEventListener('mouseleave', () => hideTooltip());
  el.addEventListener('pointerdown', () => hideTooltip());
}

/**
 * Показывает подсказку для события с задержкой (как у Tooltip в JavaFX).
 * @param {MouseEvent} e событие
 * @param {Function} content функция содержимого
 */
export function scheduleTooltip(e, content) {
  clearTimeout(tooltipTimer);
  const { clientX, clientY } = e;
  tooltipTimer = setTimeout(() => {
    const value = content(e);
    if (value === null || value === undefined || value === '') return;
    showTooltipAt(clientX, clientY, value);
  }, 450);
}

/**
 * Показывает подсказку немедленно в точке.
 * @param {number} x координата X
 * @param {number} y координата Y
 * @param {string|Node} value содержимое
 */
export function showTooltipAt(x, y, value) {
  if (!tooltipEl) {
    // JavaFX: Tooltip → Swing: JToolTip → Web: <div class="tooltip" role="tooltip">
    tooltipEl = h('div', { class: 'tooltip', role: 'tooltip' });
    document.body.append(tooltipEl);
  }
  clear(tooltipEl);
  tooltipEl.append(value instanceof Node ? value : String(value));
  tooltipEl.hidden = false;
  placeFloating(tooltipEl, x + 12, y + 16);
}

/**
 * Двигает видимую подсказку за мышью.
 * @param {MouseEvent} e событие
 */
function moveTooltip(e) {
  if (tooltipEl && !tooltipEl.hidden) placeFloating(tooltipEl, e.clientX + 12, e.clientY + 16);
}

/** Скрывает подсказку. */
export function hideTooltip() {
  clearTimeout(tooltipTimer);
  if (tooltipEl) tooltipEl.hidden = true;
}

/**
 * Размещает плавающий элемент так, чтобы он не вышел за окно.
 * @param {HTMLElement} el элемент (position: fixed)
 * @param {number} x желаемый левый край
 * @param {number} y желаемый верхний край
 */
export function placeFloating(el, x, y) {
  el.style.left = '0px';
  el.style.top = '0px';
  const r = el.getBoundingClientRect();
  const left = x + r.width > window.innerWidth - 4 ? Math.max(4, x - r.width - 24) : x;
  const top = y + r.height > window.innerHeight - 4 ? Math.max(4, y - r.height - 28) : y;
  el.style.left = `${left}px`;
  el.style.top = `${top}px`;
}

// ------------------------------------------------------------------ PopupControl

let popoverEl = null;

/**
 * Показывает поповер у элемента (карточка сводки со спарклайном).
 * JavaFX: PopupControl (SparklinePopupControl со своим Skin) → Swing: SwingPopupControl (JWindow) → Web: div.popover
 * @param {HTMLElement} anchor элемент-якорь
 * @param {Node} content содержимое
 */
export function showPopover(anchor, content) {
  hidePopover();
  popoverEl = h('div', { class: 'popover', role: 'dialog', 'aria-live': 'polite' }, content);
  document.body.append(popoverEl);
  const r = anchor.getBoundingClientRect();
  placeFloating(popoverEl, r.left, r.bottom + 6);
}

/** Скрывает поповер. */
export function hidePopover() {
  if (popoverEl) {
    popoverEl.remove();
    popoverEl = null;
  }
}

/**
 * Строит SVG-спарклайн по точкам графика.
 * @param {Array<{date: string, balanceMinor: number}>} points точки
 * @param {object} [options] {width, height, cushionMinor, goalMinor, highlightDate}
 * @returns {SVGSVGElement} спарклайн
 */
export function sparkline(points, options = {}) {
  const width = options.width || 240;
  const height = options.height || 64;
  const root = svg('svg', { class: 'sparkline', width, height, viewBox: `0 0 ${width} ${height}`, role: 'img',
    'aria-label': 'Динамика баланса' });
  if (!points || points.length < 2) return root;
  const values = points.map((p) => p.balanceMinor);
  let min = Math.min(0, ...values);
  let max = Math.max(...values);
  if (options.goalMinor) max = Math.max(max, options.goalMinor);
  if (max === min) max = min + 1;
  /**
   * Координата X точки спарклайна.
   * @param {number} i индекс точки
   * @returns {number} X, px
   */
  const x = (i) => 2 + (i / (points.length - 1)) * (width - 4);
  /**
   * Координата Y суммы.
   * @param {number} v сумма, копейки
   * @returns {number} Y, px
   */
  const y = (v) => height - 3 - ((v - min) / (max - min)) * (height - 6);
  if (min < 0) {
    root.append(svg('line', { x1: 0, x2: width, y1: y(0), y2: y(0), class: 'spark-zero' }));
  }
  if (options.cushionMinor > 0) {
    root.append(svg('line', { x1: 0, x2: width, y1: y(options.cushionMinor), y2: y(options.cushionMinor), class: 'spark-cushion' }));
  }
  if (options.goalMinor > 0) {
    root.append(svg('line', { x1: 0, x2: width, y1: y(options.goalMinor), y2: y(options.goalMinor), class: 'spark-goal' }));
  }
  let d = `M${x(0).toFixed(1)},${y(values[0]).toFixed(1)}`;
  for (let i = 1; i < points.length; i++) {
    d += `H${x(i).toFixed(1)}V${y(values[i]).toFixed(1)}`;
  }
  root.append(svg('path', { d, class: 'spark-line' }));
  if (options.highlightDate) {
    const idx = points.findIndex((p) => p.date >= options.highlightDate);
    const i = idx < 0 ? points.length - 1 : idx;
    root.append(svg('circle', { cx: x(i), cy: y(values[i]), r: 3, class: 'spark-dot' }));
  }
  return root;
}

// ------------------------------------------------------------------ PopupWindow

let dayCardEl = null;

/**
 * Показывает карточку дня у точки графика.
 * JavaFX: PopupWindow (DayCardPopupWindow, setAutoHide(true)) → Swing: JWindow → Web: absolute div + autoHide по pointerdown
 * @param {number} x координата X экрана
 * @param {number} y координата Y экрана
 * @param {Node} content содержимое
 */
export function showDayCard(x, y, content) {
  if (!dayCardEl) {
    dayCardEl = h('div', { class: 'day-card', role: 'status' });
    document.body.append(dayCardEl);
  }
  clear(dayCardEl);
  dayCardEl.append(content);
  dayCardEl.hidden = false;
  placeFloating(dayCardEl, x + 14, y + 14);
}

/** Скрывает карточку дня. */
export function hideDayCard() {
  if (dayCardEl) dayCardEl.hidden = true;
}

// autoHide: любое нажатие мимо карточки дня или поповера скрывает их.
document.addEventListener('pointerdown', (e) => {
  if (dayCardEl && !dayCardEl.hidden && !dayCardEl.contains(e.target)) hideDayCard();
  if (popoverEl && !popoverEl.contains(e.target)) hidePopover();
}, true);

// ------------------------------------------------------------------ Popup (быстрая правка)

/** Открытый мини-редактор суммы. */
let quickEdit = null;

/**
 * Мини-редактор суммы события у ячейки таблицы; восстанавливаемое окно QUICK_EDIT_POPUP.
 * JavaFX: Popup (QuickEditPopup) → Swing: PopupFactory.getPopup → Web: absolute div с <form>
 */
export class QuickEditPopup {
  /**
   * @param {object} options {ruleId, originalDate, amount (канонический текст), anchor (HTMLElement|null),
   *   title, existing (WindowState с сервера|null), onSubmit(amountText) → Promise}
   */
  constructor(options) {
    this.options = options;
    this.id = null;
    this.closed = false;
    /** Последний запрос отправки суммы (сохранение ждёт его, прежде чем сервер закроет окно). */
    this.inflight = null;
    this.sync = debounce(() => this.sendFields(), 300);
  }

  /**
   * Показывает редактор и регистрирует окно на сервере.
   * @returns {Promise<void>} завершение открытия
   */
  async open() {
    if (quickEdit) await quickEdit.close();
    quickEdit = this;
    // Подсказка ячейки, запланированная наведением перед двойным щелчком, не должна лечь поверх редактора.
    hideTooltip();
    const { existing } = this.options;
    this.input = h('input', { type: 'text', name: 'amount', inputmode: 'decimal', class: 'quick-input',
      'aria-label': 'Новая сумма события', autocomplete: 'off' });
    this.input.value = displayMoney(existing ? existing.fields.amount : this.options.amount);
    this.error = h('div', { class: 'quick-error', role: 'alert' });
    const form = h('form', { class: 'quick-form' },
      h('label', { class: 'quick-title', text: this.options.title || 'Сумма события' }),
      h('div', { class: 'quick-row' }, this.input,
        h('button', { type: 'submit', class: 'btn primary', text: 'OK', title: 'Сохранить сумму (Enter)' }),
        h('button', { type: 'button', class: 'btn', text: '✕', title: 'Отмена (Esc)', onClick: () => this.close() })),
      this.error);
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      this.submit();
    });
    this.input.addEventListener('input', () => {
      this.error.textContent = '';
      this.sync();
    });
    this.input.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        this.close();
      }
    });
    this.el = h('div', { class: 'quick-edit-popup', role: 'dialog', 'aria-label': 'Быстрая правка суммы' }, form);
    document.body.append(this.el);
    const anchor = this.options.anchor;
    if (anchor && anchor.isConnected) {
      const r = anchor.getBoundingClientRect();
      placeFloating(this.el, r.left, r.bottom + 2);
    } else {
      placeFloating(this.el, window.innerWidth / 2 - 120, window.innerHeight / 3);
    }
    // Щелчок мимо закрывает Popup (autoHide), как в JavaFX.
    this.outside = (e) => {
      if (!this.el.contains(e.target)) this.close();
    };
    setTimeout(() => document.addEventListener('pointerdown', this.outside, true), 0);
    if (existing) {
      this.id = existing.id;
    } else {
      try {
        const res = await api.post('/api/session/windows', {
          type: 'QUICK_EDIT_POPUP', modal: false, ownerId: 'main',
          context: { ruleId: this.options.ruleId, originalDate: this.options.originalDate },
          fields: { amount: canonicalMoney(this.input.value) },
        });
        this.id = res.id;
      } catch {
        // Без регистрации редактор всё равно работает, просто не попадёт в снимок.
      }
    }
    if (this.id) notifyShown(this.id);
    this.input.focus();
    this.input.select();
  }

  /** Отправляет поле на сервер (для снимка сессии). */
  sendFields() {
    if (!this.id || this.closed) return;
    this.inflight = api.put(`/api/session/windows/${this.id}`, { fields: { amount: canonicalMoney(this.input.value) } })
      .catch(() => {});
  }

  /** Проверяет и сохраняет сумму. */
  async submit() {
    if (parseMoney(this.input.value) === null || parseMoney(this.input.value) <= 0) {
      this.error.textContent = 'Введите сумму больше нуля, например 95 000,00';
      return;
    }
    try {
      // Отложенная отправка суммы не должна прийти на сервер после правки, которая закроет это окно.
      this.sync.cancel();
      if (this.inflight) await this.inflight;
      await this.options.onSubmit(canonicalMoney(this.input.value), this.id);
      // Правка ушла с windowId — сервер уже закрыл окно, повторный DELETE не нужен.
      this.serverClosed = !!this.id;
      await this.close();
    } catch (e) {
      this.error.textContent = e instanceof ApiError ? e.message : String(e);
    }
  }

  /**
   * Закрывает редактор и удаляет окно на сервере.
   * @returns {Promise<void>} завершение
   */
  async close() {
    if (this.closed) return;
    this.closed = true;
    this.sync.cancel();
    document.removeEventListener('pointerdown', this.outside, true);
    this.el.remove();
    if (quickEdit === this) quickEdit = null;
    if (this.id && !this.serverClosed) {
      await api.del(`/api/session/windows/${this.id}`).catch(() => {});
    }
  }
}

// ------------------------------------------------------------------ уведомления

/**
 * Короткое уведомление внизу страницы.
 * @param {string} message текст
 * @param {'info'|'success'|'warning'|'error'} [kind='info'] вид
 * @param {number} [ms=4000] длительность показа
 */
export function toast(message, kind = 'info', ms = 4000) {
  let host = document.getElementById('toasts');
  if (!host) {
    host = h('div', { id: 'toasts', class: 'toasts', 'aria-live': 'polite' });
    document.body.append(host);
  }
  const el = h('div', { class: `toast ${kind}`, role: kind === 'error' ? 'alert' : 'status', text: message });
  host.append(el);
  setTimeout(() => el.classList.add('hide'), ms);
  setTimeout(() => el.remove(), ms + 400);
}
