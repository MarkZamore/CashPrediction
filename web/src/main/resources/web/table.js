/**
 * @file Таблица событий прогноза: Дата | День | Операция | Категория | Доход | Расход | Баланс | Отметки.
 *
 * JavaFX: TableView → Swing: JTable + AbstractTableModel → Web: <table> с липкой шапкой.
 * Доход зелёный, расход красный; фон строки с балансом меньше нуля — светло-красный, ниже подушки — светло-жёлтый;
 * прошедшие события серые; пропущенные зачёркнуты. Отметки: ✎ сумма изменена, → перенесено, ⇄ сдвиг с выходного,
 * ≡ разовая, Δ «что-если». Строки «итог месяца» — при флажке «Итоги по месяцам».
 * Подсказки ячеек — div.tooltip, контекстное меню строки — по событию contextmenu, двойной щелчок по сумме —
 * быстрая правка (Popup).
 */

import { h, ruDate } from './util.js';
import { store, cmd } from './store.js';
import { showContextMenu } from './menu.js';
import { scheduleTooltip, hideTooltip, toast } from './popup.js';
import { rowContextItems, monthTotalContextItems } from './app-menu.js';
import { sendSelection } from './session.js';

/** Колонки таблицы. */
const COLUMNS = [
  { title: 'Дата', cls: 'col-date', tip: 'Фактическая дата события' },
  { title: 'День', cls: 'col-day', tip: 'День недели' },
  { title: 'Операция', cls: 'col-title', tip: 'Название операции' },
  { title: 'Категория', cls: 'col-category', tip: 'Категория операции' },
  { title: 'Доход', cls: 'col-money col-income', tip: 'Поступление денег' },
  { title: 'Расход', cls: 'col-money col-expense', tip: 'Трата денег' },
  { title: 'Баланс', cls: 'col-money col-balance', tip: 'Сколько денег останется после события' },
  {
    title: 'Отметки', cls: 'col-marks',
    tip: '✎ сумма изменена · → перенесено · ⇄ сдвиг с выходного · ≡ разовая · Δ что-если; зачёркнуто — пропущено',
  },
];

/** Пояснения отметок для подсказки. */
const MARK_HELP = {
  '✎': 'сумма изменена корректировкой',
  '→': 'событие перенесено на другую дату',
  '⇄': 'дата сдвинута с выходного дня',
  '≡': 'разовая операция',
  'Δ': 'сумма изменена сценарием «что-если»',
};

/**
 * Таблица событий.
 * JavaFX: TableView<ForecastRow> → Swing: JTable + ForecastTableModel → Web: <table class="forecast-table">
 */
export class ForecastTable {
  /**
   * @param {HTMLElement} container элемент раздела таблицы
   */
  constructor(container) {
    this.container = container;
    /** @type {Map<string, object>} строки прогноза по rowId в порядке дат */
    this.rowsById = new Map();
    this.state = null;
    this.hoverTd = null;
    this.tbody = h('tbody');
    this.table = h('table', { class: 'forecast-table' },
      h('thead', {}, h('tr', {}, COLUMNS.map((c) => h('th', { scope: 'col', class: c.cls, title: c.tip, text: c.title })))),
      this.tbody);
    this.wrap = h('div', {
      class: 'table-wrap', tabindex: '0',
      'aria-label': 'Таблица событий: стрелки — выбор строки, Enter — изменить, Delete — удалить, Shift+F10 — меню',
    }, this.table);
    this.footer = h('div', { class: 'table-footer', hidden: true });
    container.append(this.wrap, this.footer);
    this.installEvents();
  }

  /**
   * Перерисовывает таблицу по состоянию с сервера.
   * @param {object} state состояние
   */
  render(state) {
    hideTooltip();
    this.hoverTd = null;
    this.state = state;
    this.rowsById.clear();
    const frag = document.createDocumentFragment();
    const f = state.forecast;
    if (!f) {
      frag.append(this.emptyRow(state.forecastError ? `Прогноз не построен: ${state.forecastError}` : 'Нет данных прогноза'));
    } else if (!f.rows.length) {
      frag.append(this.emptyRow('В выбранном периоде нет событий. Проверьте флажки меню «Вид» и строку фильтра.'));
    } else {
      const monthTotals = !!state.viewState.monthTotals;
      const byMonth = new Map(((f.summary && f.summary.byMonth) || []).map((m) => [m.month, m]));
      f.rows.forEach((row, i) => {
        this.rowsById.set(row.rowId, row);
        frag.append(this.rowElement(row));
        const month = row.date.slice(0, 7);
        const next = f.rows[i + 1];
        if (monthTotals && (!next || next.date.slice(0, 7) !== month) && byMonth.has(month)) {
          frag.append(this.totalElement(byMonth.get(month)));
        }
      });
    }
    this.tbody.replaceChildren(frag);
    if (state.rowsTruncated) {
      this.footer.textContent = `Показаны первые ${this.rowsById.size} из ${state.rowsTotal} строк. Уменьшите период или уточните фильтр.`;
      this.footer.hidden = false;
    } else {
      this.footer.hidden = true;
    }
    this.highlight();
  }

  /**
   * Строка-сообщение на всю ширину таблицы.
   * @param {string} text текст
   * @returns {HTMLTableRowElement} строка
   */
  emptyRow(text) {
    return h('tr', { class: 'empty-row' }, h('td', { colspan: String(COLUMNS.length), text }));
  }

  /**
   * Строка события.
   * @param {object} row строка прогноза (PlanJson.row)
   * @returns {HTMLTableRowElement} строка таблицы
   */
  rowElement(row) {
    const flags = row.flags || {};
    const whatIf = flags.whatIf || row.origin === 'WHAT_IF';
    const classes = ['row', row.kind === 'INCOME' ? 'kind-income' : 'kind-expense', `origin-${row.origin.toLowerCase()}`];
    if (row.negative) classes.push('negative');
    else if (row.belowCushion) classes.push('below-cushion');
    if (flags.past) classes.push('past');
    if (flags.skipped) classes.push('skipped');
    if (whatIf) classes.push('what-if');
    const isStart = row.origin === 'START';
    const marks = [];
    if (flags.amountChanged) marks.push('✎');
    if (flags.moved) marks.push('→');
    if (flags.shifted) marks.push('⇄');
    if (row.origin === 'ONE_TIME') marks.push('≡');
    if (whatIf) marks.push('Δ');
    return h('tr', { class: classes.join(' '), dataset: { rowId: row.rowId }, 'aria-selected': 'false' },
      h('td', { class: 'col-date', dataset: { col: 'date' }, text: row.dateText }),
      h('td', { class: 'col-day', dataset: { col: 'date' }, text: row.weekday }),
      h('td', { class: 'col-title', dataset: { col: 'title' }, text: row.title }),
      h('td', { class: 'col-category', dataset: { col: 'title' }, text: row.category }),
      h('td', { class: 'col-money col-income amount-cell', dataset: { col: 'amount' }, text: !isStart && row.kind === 'INCOME' ? row.amountText : '' }),
      h('td', { class: 'col-money col-expense amount-cell', dataset: { col: 'amount' }, text: !isStart && row.kind === 'EXPENSE' ? row.amountText : '' }),
      h('td', { class: 'col-money col-balance', dataset: { col: 'balance' }, text: row.balanceAfterText }),
      h('td', { class: 'col-marks', dataset: { col: 'marks' }, text: marks.join(' ') }));
  }

  /**
   * Строка «итог месяца».
   * @param {object} m итог месяца (summary.byMonth)
   * @returns {HTMLTableRowElement} строка таблицы
   */
  totalElement(m) {
    const sign = String(m.net).startsWith('-') ? '' : '+';
    return h('tr', { class: 'month-total', dataset: { month: m.month } },
      h('td', { colspan: '4', class: 'total-title', dataset: { col: 'total' }, text: `${m.title} — итог: ${sign}${m.netText}` }),
      h('td', { class: 'col-money col-income', dataset: { col: 'total' }, text: m.incomeText }),
      h('td', { class: 'col-money col-expense', dataset: { col: 'total' }, text: m.expenseText }),
      h('td', { class: 'col-money col-balance', dataset: { col: 'total' }, text: m.closingBalanceText }),
      h('td', { class: 'col-marks', dataset: { col: 'total' } }));
  }

  /** Обработчики мыши и клавиатуры (делегирование на tbody и обёртку). */
  installEvents() {
    this.tbody.addEventListener('click', (e) => {
      const tr = /** @type {HTMLElement} */ (e.target).closest('tr[data-row-id]');
      if (tr) this.select(tr.dataset.rowId);
    });
    this.tbody.addEventListener('dblclick', (e) => {
      const target = /** @type {HTMLElement} */ (e.target);
      const tr = target.closest('tr[data-row-id]');
      if (!tr) return;
      const row = this.rowsById.get(tr.dataset.rowId);
      if (!row) return;
      const selection = window.getSelection();
      if (selection) selection.removeAllRanges();
      const td = target.closest('td');
      if (td && td.classList.contains('amount-cell') && td.textContent && row.origin === 'RULE' && !row.flags.skipped) {
        cmd('quickEdit', row, td);
      } else {
        cmd('editSelected', row);
      }
    });
    // JavaFX: ContextMenuEvent → Swing: MouseEvent.isPopupTrigger() → Web: событие contextmenu + preventDefault
    this.wrap.addEventListener('contextmenu', (e) => {
      const target = /** @type {HTMLElement} */ (e.target);
      const tr = target.closest('tr');
      if (tr && this.tbody.contains(tr)) {
        if (tr.dataset.rowId) {
          const row = this.rowsById.get(tr.dataset.rowId);
          if (!row) return;
          this.select(row.rowId);
          showContextMenu(e, rowContextItems(row), `Строка ${row.dateText} ${row.title}`);
        } else if (tr.dataset.month) {
          showContextMenu(e, monthTotalContextItems(tr), 'Итог месяца');
        }
        return;
      }
      // Клавиша Menu или Shift+F10 на самой таблице: меню у выбранной строки.
      const row = store.selectedRow();
      const el = row ? this.trFor(row.rowId) : null;
      if (!row || !el) return;
      const r = el.getBoundingClientRect();
      showContextMenu({ preventDefault: () => e.preventDefault(), clientX: r.left + 60, clientY: r.bottom }, rowContextItems(row));
    });
    // JavaFX: Tooltip на ячейке → Swing: JTable.getToolTipText(MouseEvent) → Web: div.tooltip
    this.tbody.addEventListener('mouseover', (e) => {
      const td = /** @type {HTMLElement} */ (e.target).closest('td');
      if (!td || td === this.hoverTd) return;
      this.hoverTd = td;
      hideTooltip();
      scheduleTooltip(e, () => this.cellTooltip(td));
    });
    this.tbody.addEventListener('mouseleave', () => {
      this.hoverTd = null;
      hideTooltip();
    });
    this.wrap.addEventListener('keydown', (e) => {
      const page = Math.max(1, Math.floor(this.wrap.clientHeight / 28) - 1);
      const moves = { ArrowDown: 1, ArrowUp: -1, PageDown: page, PageUp: -page, Home: -1e9, End: 1e9 };
      if (e.key in moves && !e.altKey && !e.ctrlKey && !e.metaKey) {
        e.preventDefault();
        this.move(moves[e.key]);
      }
    });
  }

  /**
   * Сдвигает выделение на delta строк.
   * @param {number} delta смещение
   */
  move(delta) {
    const ids = [...this.rowsById.keys()];
    if (!ids.length) return;
    let index = ids.indexOf(store.selectedRowId);
    index = index < 0 ? (delta > 0 ? 0 : ids.length - 1) : Math.max(0, Math.min(ids.length - 1, index + delta));
    this.select(ids[index], { scroll: true });
  }

  /**
   * Выделяет строку и сообщает серверу (для снимка сессии, selectedRowId).
   * @param {string} rowId идентификатор строки
   * @param {object} [options] {scroll: прокрутить к строке, send: отправить на сервер}
   */
  select(rowId, { scroll = false, send = true } = {}) {
    const changed = store.selectedRowId !== (rowId || '');
    store.selectedRowId = rowId || '';
    this.highlight();
    if (scroll) {
      const tr = this.trFor(rowId);
      if (tr) tr.scrollIntoView({ block: 'nearest' });
    }
    if (send && changed) sendSelection(store.selectedRowId);
  }

  /** Подсвечивает выделенную строку. */
  highlight() {
    const previous = this.tbody.querySelector('tr.selected');
    if (previous) {
      previous.classList.remove('selected');
      previous.setAttribute('aria-selected', 'false');
    }
    const tr = this.trFor(store.selectedRowId);
    if (tr) {
      tr.classList.add('selected');
      tr.setAttribute('aria-selected', 'true');
    }
  }

  /**
   * Строка таблицы по rowId.
   * @param {string} rowId идентификатор
   * @returns {HTMLTableRowElement|null} строка
   */
  trFor(rowId) {
    return rowId ? this.tbody.querySelector(`tr[data-row-id="${CSS.escape(rowId)}"]`) : null;
  }

  /** Переводит фокус на таблицу. */
  focus() {
    this.wrap.focus({ preventScroll: true });
  }

  /**
   * Прокручивает к первой строке с датой не раньше указанной и выделяет её.
   * @param {string} iso дата (ISO)
   */
  scrollToDate(iso) {
    const row = [...this.rowsById.values()].find((r) => r.date >= iso);
    if (!row) {
      toast(`После ${ruDate(iso)} событий в таблице нет`, 'info');
      return;
    }
    this.select(row.rowId);
    const tr = this.trFor(row.rowId);
    if (tr) tr.scrollIntoView({ block: 'center' });
    this.focus();
  }

  /**
   * Текст подсказки ячейки.
   * @param {HTMLElement} td ячейка
   * @returns {string|null} текст или null
   */
  cellTooltip(td) {
    const tr = td.closest('tr');
    if (!tr) return null;
    if (tr.dataset.month) return 'Итог месяца: доходы, расходы и баланс на конец месяца';
    const row = this.rowsById.get(tr.dataset.rowId);
    if (!row || !this.state) return null;
    const cur = this.state.plan.currency;
    const rule = row.ruleId ? store.rule(row.ruleId) : null;
    const adj = row.ruleId ? store.adjustment(row.ruleId, row.originalDate) : null;
    const flags = row.flags || {};
    const lines = [];
    switch (td.dataset.col) {
      case 'date':
        lines.push(`${row.dateText}, ${row.weekday}`);
        if (flags.shifted) lines.push(`Сдвинуто с выходного: по правилу ${ruDate(row.originalDate)}`);
        if (flags.moved) lines.push(`Перенесено с ${ruDate(row.originalDate)}`);
        if (flags.past) lines.push('Событие уже прошло');
        break;
      case 'title':
        lines.push(row.title);
        lines.push(rule ? `${row.originTitle}: ${rule.recurrence.text}` : row.originTitle);
        if (row.category) lines.push(`Категория: ${row.category}`);
        if (rule && !rule.enabled) lines.push('Правило отключено');
        if (row.note) lines.push(`Заметка: ${row.note}`);
        if (adj && adj.note) lines.push(`Корректировка: ${adj.note}`);
        break;
      case 'amount':
        if (!td.textContent) return null;
        lines.push(`${row.amountSignedText} ${cur}`);
        if (flags.amountChanged && rule) lines.push(`По правилу: ${rule.amountText} ${cur}`);
        if (flags.whatIf) lines.push('Сумма изменена сценарием «что-если»');
        if (flags.skipped) lines.push('Событие пропущено и не влияет на баланс');
        if (row.origin === 'RULE' && !flags.skipped) lines.push('Двойной щелчок — быстрая правка суммы');
        break;
      case 'balance':
        lines.push(`Баланс после события: ${row.balanceAfterText} ${cur}`);
        if (row.negative) lines.push('Баланс уходит в минус!');
        else if (row.belowCushion) lines.push(`Ниже подушки безопасности (${this.state.plan.cushionText} ${cur})`);
        break;
      case 'marks':
        for (const mark of td.textContent.split(' ').filter(Boolean)) lines.push(`${mark} — ${MARK_HELP[mark] || ''}`);
        if (flags.skipped) lines.push('зачёркнуто — событие пропущено');
        if (!lines.length) return null;
        break;
      default:
        return null;
    }
    return lines.filter(Boolean).join('\n');
  }
}
