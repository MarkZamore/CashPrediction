/**
 * @file График баланса: SVG, построенный из точек ChartSeries.sample (не больше 1500 точек, их считает сервер).
 *
 * Шаговая линия баланса, горизонтали «ноль», «подушка», «цель», вертикаль «сегодня», маркеры событий, столбцы
 * итогов месяца. Наведение — карточка дня (PopupWindow), маркеры и столбцы — подсказки (Tooltip), правая кнопка —
 * контекстное меню (ContextMenu по ContextMenuEvent), двойной щелчок — переход к дате в таблице.
 * JavaFX: LineChart (createSymbols=false, animated=false) → Swing: BalanceChartComponent (Graphics2D) → Web: SVG
 */

import {
  h, svg, dayNumber, isoFromDay, ruDate, parseMoney, formatMoney, shortMoney, MONTHS_SHORT,
} from './util.js';
import { cmd } from './store.js';
import { showContextMenu } from './menu.js';
import { showDayCard, hideDayCard, scheduleTooltip, hideTooltip } from './popup.js';
import { chartContextItems } from './app-menu.js';

/** Названия дней недели по Date.getUTCDay(). */
const WEEKDAY_NAMES = ['воскресенье', 'понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота'];

/** Наибольшее число маркеров событий (дальше график становится нечитаемым). */
const MAX_MARKERS = 2500;

/** Стили графика для сохранения в файл SVG (в файле нет style.css страницы). */
const EXPORT_CSS = `
  .chart-bg{fill:#fff}
  .chart-grid line{stroke:#e3e6ea;stroke-width:1}
  .chart-grid-v{stroke:#eef0f3;stroke-width:1}
  .chart-label{font:11px "Segoe UI",Arial,sans-serif;fill:#5f6873}
  .chart-caption{font:600 13px "Segoe UI",Arial,sans-serif;fill:#1f2328}
  .chart-axis{stroke:#9aa3ad;stroke-width:1}
  .chart-area{fill:rgba(31,111,235,.10)}
  .chart-line{fill:none;stroke:#1f6feb;stroke-width:2}
  .chart-zero{stroke:#57606a;stroke-width:1.2;stroke-dasharray:5 4}
  .chart-cushion{stroke:#bf8700;stroke-width:1.2;stroke-dasharray:6 4}
  .chart-goal{stroke:#8250df;stroke-width:1.2;stroke-dasharray:2 3}
  .chart-today{stroke:#d1242f;stroke-width:1.2}
  .chart-ref-label.cushion{fill:#9a6700}.chart-ref-label.goal{fill:#8250df}.chart-ref-label.today{fill:#d1242f}
  .chart-marker{stroke:#fff;stroke-width:1}
  .chart-marker.income{fill:#1a7f37}.chart-marker.expense{fill:#cf222e}.chart-marker.mixed{fill:#9a6700}
  .chart-bar.pos{fill:rgba(26,127,55,.55)}.chart-bar.neg{fill:rgba(207,34,46,.55)}
`;

/**
 * «Красивый» шаг сетки: 1, 2 или 5 × 10ⁿ.
 * @param {number} range диапазон значений
 * @param {number} count желаемое число делений
 * @returns {number} шаг
 */
function niceStep(range, count) {
  const raw = range / Math.max(1, count);
  const magnitude = 10 ** Math.floor(Math.log10(raw));
  const norm = raw / magnitude;
  const step = norm < 1.5 ? 1 : norm < 3 ? 2 : norm < 7 ? 5 : 10;
  return step * magnitude;
}

/**
 * Строит SVG графика по состоянию.
 * @param {object} state состояние с сервера (forecast.chart, forecast.rows, plan, viewState, today)
 * @param {number} width ширина, px
 * @param {number} height высота, px
 * @returns {{root: SVGSVGElement, g: object}} элемент и геометрия (для наведения)
 */
export function buildChart(state, width, height) {
  const f = state.forecast;
  const points = f.chart;
  const view = state.viewState;
  const plan = state.plan;
  const days = points.map((p) => dayNumber(p.date));
  const values = points.map((p) => p.balanceMinor);
  const d0 = days[0];
  const d1 = days[days.length - 1];
  const span = Math.max(1, d1 - d0);
  const cushion = parseMoney(plan.cushion) || 0;
  const goal = plan.goal ? parseMoney(plan.goal.target) || 0 : 0;

  let lo = Math.min(0, ...values);
  let hi = Math.max(...values, cushion, goal);
  if (hi <= lo) hi = lo + 100000;
  const barsH = view.chartBars ? Math.round(Math.min(110, height * 0.24)) : 0;
  const m = { left: 80, right: 20, top: 18, bottom: 30 + barsH };
  const plotW = Math.max(40, width - m.left - m.right);
  const plotH = Math.max(40, height - m.top - m.bottom);
  const step = niceStep(hi - lo, Math.max(3, Math.floor(plotH / 60)));
  lo = Math.floor(lo / step) * step;
  hi = Math.ceil(hi / step) * step;
  if (hi === lo) hi = lo + step;
  /** @param {number} day номер дня @returns {number} X */
  const x = (day) => m.left + ((day - d0) / span) * plotW;
  /** @param {number} v копейки @returns {number} Y */
  const y = (v) => m.top + (1 - (v - lo) / (hi - lo)) * plotH;

  const root = svg('svg', {
    class: 'balance-chart', width, height, viewBox: `0 0 ${width} ${height}`, role: 'img',
    'aria-label': `График баланса с ${ruDate(points[0].date)} по ${ruDate(points[points.length - 1].date)}`,
  });
  root.append(svg('rect', { class: 'chart-bg', x: 0, y: 0, width, height }));

  // Горизонтальная сетка и подписи сумм.
  const grid = svg('g', { class: 'chart-grid' });
  for (let v = lo, i = 0; v <= hi + step / 2 && i < 100; v += step, i++) {
    const yy = y(v);
    grid.append(svg('line', { x1: m.left, x2: m.left + plotW, y1: yy, y2: yy }));
    grid.append(svg('text', { x: m.left - 8, y: yy + 4, class: 'chart-label', 'text-anchor': 'end', text: shortMoney(v) }));
  }
  root.append(grid);

  // Месяцы по оси X.
  let yy = Number(points[0].date.slice(0, 4));
  let mm = Number(points[0].date.slice(5, 7));
  if (points[0].date.slice(8) !== '01') {
    mm += 1;
    if (mm > 12) {
      mm = 1;
      yy += 1;
    }
  }
  const monthStarts = [];
  while (monthStarts.length < 1300) {
    const iso = `${yy}-${String(mm).padStart(2, '0')}-01`;
    const d = dayNumber(iso);
    if (d > d1) break;
    monthStarts.push({ d, yy, mm });
    mm += 1;
    if (mm > 12) {
      mm = 1;
      yy += 1;
    }
  }
  const every = monthStarts.length > 48 ? 12 : monthStarts.length > 24 ? 6 : monthStarts.length > 12 ? 2 : 1;
  const xAxis = svg('g', { class: 'chart-x' });
  monthStarts.forEach((ms, i) => {
    const xx = x(ms.d);
    xAxis.append(svg('line', { class: 'chart-grid-v', x1: xx, x2: xx, y1: m.top, y2: m.top + plotH }));
    if (i % every === 0 || every === 12 && ms.mm === 1) {
      const label = ms.mm === 1 || i === 0 ? `${MONTHS_SHORT[ms.mm - 1]} ${ms.yy}` : MONTHS_SHORT[ms.mm - 1];
      xAxis.append(svg('text', { class: 'chart-label', x: xx + 3, y: m.top + plotH + 16, text: label }));
    }
  });
  xAxis.append(svg('line', { class: 'chart-axis', x1: m.left, x2: m.left + plotW, y1: m.top + plotH, y2: m.top + plotH }));
  root.append(xAxis);

  // Столбцы итогов месяца.
  const bars = svg('g', { class: 'chart-bars' });
  if (barsH) {
    const byMonth = (f.summary && f.summary.byMonth) || [];
    const top = m.top + plotH + 26;
    const bh = barsH - 8;
    const mid = top + bh / 2;
    const maxAbs = Math.max(1, ...byMonth.map((b) => Math.abs(parseMoney(b.net) || 0)));
    bars.append(svg('line', { class: 'chart-axis', x1: m.left, x2: m.left + plotW, y1: mid, y2: mid }));
    bars.append(svg('text', { class: 'chart-label', x: m.left - 8, y: mid + 4, 'text-anchor': 'end', text: 'итог мес.' }));
    for (const b of byMonth) {
      const [by, bm] = b.month.split('-').map(Number);
      const start = dayNumber(`${b.month}-01`);
      const end = dayNumber(bm === 12 ? `${by + 1}-01-01` : `${by}-${String(bm + 1).padStart(2, '0')}-01`);
      const xa = x(Math.max(d0, start));
      const xb = Math.min(m.left + plotW, x(Math.min(d1 + 1, end)));
      if (xb <= m.left || xa >= m.left + plotW) continue;
      const net = parseMoney(b.net) || 0;
      const bw = Math.max(2, (xb - xa) * 0.7);
      const bhh = Math.max(1, (Math.abs(net) / maxAbs) * (bh / 2));
      const rect = svg('rect', {
        class: `chart-bar ${net >= 0 ? 'pos' : 'neg'}`, x: (xa + xb) / 2 - bw / 2, y: net >= 0 ? mid - bhh : mid, width: bw, height: bhh,
      });
      rect.dataset.tip = `${b.title}\nИтог: ${b.netText}\nДоходы: ${b.incomeText}\nРасходы: ${b.expenseText}\nБаланс на конец: ${b.closingBalanceText}`;
      bars.append(rect);
    }
  }
  root.append(bars);

  // Горизонтали «ноль», «подушка», «цель».
  const refs = svg('g', { class: 'chart-refs' });
  if (lo < 0) {
    refs.append(svg('line', { class: 'chart-zero', x1: m.left, x2: m.left + plotW, y1: y(0), y2: y(0) }));
  }
  if (cushion > 0) {
    refs.append(svg('line', { class: 'chart-cushion', x1: m.left, x2: m.left + plotW, y1: y(cushion), y2: y(cushion) }));
    refs.append(svg('text', { class: 'chart-label chart-ref-label cushion', x: m.left + plotW - 4, y: y(cushion) - 4, 'text-anchor': 'end', text: `подушка ${plan.cushionText}` }));
  }
  if (goal > 0) {
    refs.append(svg('line', { class: 'chart-goal', x1: m.left, x2: m.left + plotW, y1: y(goal), y2: y(goal) }));
    refs.append(svg('text', { class: 'chart-label chart-ref-label goal', x: m.left + plotW - 4, y: y(goal) - 4, 'text-anchor': 'end', text: `цель «${plan.goal.title || 'цель'}» ${plan.goal.targetText}` }));
  }
  root.append(refs);

  // Шаговая линия баланса и заливка под ней.
  let line = `M${x(days[0]).toFixed(1)},${y(values[0]).toFixed(1)}`;
  for (let i = 1; i < points.length; i++) {
    line += `H${x(days[i]).toFixed(1)}V${y(values[i]).toFixed(1)}`;
  }
  const baseY = y(Math.min(hi, Math.max(lo, 0))).toFixed(1);
  root.append(svg('path', { class: 'chart-area', d: `${line}V${baseY}H${x(days[0]).toFixed(1)}Z` }));
  root.append(svg('path', { class: 'chart-line', d: line }));

  // Вертикаль «сегодня».
  const todayDay = dayNumber(state.today);
  if (todayDay >= d0 && todayDay <= d1) {
    const tx = x(todayDay);
    root.append(svg('line', { class: 'chart-today', x1: tx, x2: tx, y1: m.top, y2: m.top + plotH }));
    root.append(svg('text', { class: 'chart-label chart-ref-label today', x: tx + 4, y: m.top + 11, text: 'сегодня' }));
  }

  // Маркеры событий (последний баланс дня).
  const rowsByDate = new Map();
  for (const row of f.rows) {
    if (row.origin === 'START') continue;
    if (!rowsByDate.has(row.date)) rowsByDate.set(row.date, []);
    rowsByDate.get(row.date).push(row);
  }
  const markers = svg('g', { class: 'chart-markers' });
  if (view.chartMarkers) {
    let count = 0;
    for (const [date, rows] of rowsByDate) {
      const d = dayNumber(date);
      const active = rows.filter((r) => !r.flags.skipped);
      if (d < d0 || d > d1 || !active.length) continue;
      if (++count > MAX_MARKERS) break;
      const last = active[active.length - 1];
      const hasIncome = active.some((r) => r.kind === 'INCOME');
      const hasExpense = active.some((r) => r.kind === 'EXPENSE');
      const marker = svg('circle', {
        class: `chart-marker ${hasIncome && hasExpense ? 'mixed' : hasIncome ? 'income' : 'expense'}`,
        cx: x(d).toFixed(1), cy: y(parseMoney(last.balanceAfter) || 0).toFixed(1), r: 3.6,
      });
      marker.dataset.date = date;
      markers.append(marker);
    }
  }
  root.append(markers);

  return { root, g: { m, plotW, plotH, d0, d1, span, days, values, x, y, rowsByDate } };
}

/**
 * Текст SVG-файла графика (для «Сохранить график»): стили встроены, фон белый.
 * JavaFX: FileChooser + snapshot PNG → Swing: JFileChooser + BufferedImage PNG → Web: скачивание SVG
 * @param {object} state состояние
 * @param {number} width ширина
 * @param {number} height высота
 * @returns {string} разметка SVG
 */
export function chartSvgMarkup(state, width, height) {
  if (!state || !state.forecast || !state.forecast.chart || state.forecast.chart.length < 2) {
    throw new Error('Нет данных для графика: прогноз не построен');
  }
  const { root } = buildChart(state, width, height + 26);
  root.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
  const style = svg('style', { text: EXPORT_CSS });
  root.insertBefore(style, root.firstChild);
  // Подпись вверху файла: план и период.
  const content = svg('g', { transform: 'translate(0,26)' });
  while (root.childNodes.length > 1) content.append(root.childNodes[1]);
  root.append(content);
  root.insertBefore(svg('rect', { class: 'chart-bg', x: 0, y: 0, width, height: height + 26 }), content);
  root.insertBefore(svg('text', {
    class: 'chart-caption', x: 12, y: 18,
    text: `CashPrediction — «${state.plan.name}»: баланс с ${ruDate(state.forecast.chart[0].date)} по ${state.periodEndText || ''}`,
  }), content);
  return `<?xml version="1.0" encoding="UTF-8"?>\n${new XMLSerializer().serializeToString(root)}`;
}

/**
 * График баланса в главном окне.
 * JavaFX: LineChart<Number, Number> → Swing: BalanceChartComponent extends JComponent → Web: <svg class="balance-chart">
 */
export class BalanceChart {
  /**
   * @param {HTMLElement} container раздел графика
   */
  constructor(container) {
    this.container = container;
    this.state = null;
    this.lastSize = '';
    this.legend = h('div', { class: 'chart-legend' });
    this.host = h('div', { class: 'chart-host' });
    container.append(this.legend, this.host);
    // Перерисовка при изменении размеров окна.
    this.observer = new ResizeObserver(() => {
      const size = `${this.host.clientWidth}x${this.host.clientHeight}`;
      if (this.state && !this.container.hidden && size !== this.lastSize) this.draw();
    });
    this.observer.observe(this.host);
  }

  /**
   * Перерисовывает график по состоянию.
   * @param {object} state состояние
   */
  render(state) {
    this.state = state;
    this.draw();
  }

  /** Рисует легенду и SVG. */
  draw() {
    const state = this.state;
    hideDayCard();
    hideTooltip();
    this.renderLegend(state);
    this.lastSize = `${this.host.clientWidth}x${this.host.clientHeight}`;
    const f = state.forecast;
    if (!f || !f.chart || f.chart.length < 2) {
      this.host.replaceChildren(h('div', {
        class: 'chart-empty', text: state.forecastError ? `Прогноз не построен: ${state.forecastError}` : 'Недостаточно данных для графика',
      }));
      return;
    }
    const width = Math.max(320, Math.floor(this.host.clientWidth || 800));
    const height = Math.max(240, Math.floor(this.host.clientHeight || 420));
    const { root, g } = buildChart(state, width, height);
    this.host.replaceChildren(root);
    this.attach(root, g, state);
  }

  /**
   * Легенда графика.
   * @param {object} state состояние
   */
  renderLegend(state) {
    const plan = state.plan;
    const view = state.viewState;
    const items = [
      ['line', 'Баланс', 'Сколько денег на конец дня'],
      ['zero', 'Ноль', 'Ниже линии — долг'],
      parseMoney(plan.cushion) > 0 ? ['cushion', `Подушка ${plan.cushionText}`, 'Неприкосновенный запас из параметров плана'] : null,
      plan.goal ? ['goal', `Цель ${plan.goal.targetText}`, `Цель «${plan.goal.title}»`] : null,
      ['today', 'Сегодня', 'Вертикальная линия — сегодняшний день'],
      view.chartMarkers ? ['income', 'Доход', 'Маркер дня с доходом'] : null,
      view.chartMarkers ? ['expense', 'Расход', 'Маркер дня с расходом'] : null,
      view.chartBars ? ['bar', 'Итог месяца', 'Столбцы внизу: доходы минус расходы за месяц'] : null,
    ].filter(Boolean);
    this.legend.replaceChildren(
      ...items.map(([cls, text, tip]) => h('span', { class: `legend-item legend-${cls}`, title: tip },
        h('span', { class: 'legend-swatch', 'aria-hidden': 'true' }), text)),
      h('span', { class: 'legend-hint', text: 'Наведите — карточка дня · двойной щелчок — к дате в таблице · правая кнопка — меню' }));
  }

  /**
   * Наведение, подсказки маркеров, контекстное меню и двойной щелчок.
   * @param {SVGSVGElement} root SVG
   * @param {object} g геометрия buildChart
   * @param {object} state состояние
   */
  attach(root, g, state) {
    const cur = state.plan.currency;
    const cross = svg('line', { class: 'chart-cross', y1: g.m.top, y2: g.m.top + g.plotH, visibility: 'hidden' });
    const dot = svg('circle', { class: 'chart-dot', r: 4.5, visibility: 'hidden' });
    root.append(cross, dot);

    /** @param {number} clientX координата мыши @returns {number} номер дня */
    const dayAt = (clientX) => {
      const rect = root.getBoundingClientRect();
      const day = Math.round(g.d0 + ((clientX - rect.left - g.m.left) / g.plotW) * g.span);
      return Math.max(g.d0, Math.min(g.d1, day));
    };
    /** @param {number} day номер дня @returns {number} индекс последней точки не позже дня */
    const indexAt = (day) => {
      let lo = 0;
      let hi = g.days.length - 1;
      while (lo < hi) {
        const mid = Math.ceil((lo + hi) / 2);
        if (g.days[mid] <= day) lo = mid;
        else hi = mid - 1;
      }
      return lo;
    };
    /** Скрывает перекрестие и карточку дня. */
    const hide = () => {
      cross.setAttribute('visibility', 'hidden');
      dot.setAttribute('visibility', 'hidden');
      hideDayCard();
    };

    root.addEventListener('pointermove', (e) => {
      const target = /** @type {Element} */ (e.target);
      if (target.classList.contains('chart-marker') || target.classList.contains('chart-bar')) {
        hide();
        return;
      }
      const rect = root.getBoundingClientRect();
      const px = e.clientX - rect.left;
      const py = e.clientY - rect.top;
      if (px < g.m.left || px > g.m.left + g.plotW || py < g.m.top || py > g.m.top + g.plotH) {
        hide();
        return;
      }
      const day = dayAt(e.clientX);
      const value = g.values[indexAt(day)];
      const cx = g.x(day);
      cross.setAttribute('x1', String(cx));
      cross.setAttribute('x2', String(cx));
      cross.setAttribute('visibility', 'visible');
      dot.setAttribute('cx', String(cx));
      dot.setAttribute('cy', String(g.y(value)));
      dot.setAttribute('visibility', 'visible');
      // JavaFX: PopupWindow (DayCardPopupWindow, autoHide) → Swing: JWindow → Web: div.day-card
      showDayCard(e.clientX, e.clientY, dayCard(isoFromDay(day), value, g.rowsByDate, cur));
    });
    root.addEventListener('pointerleave', hide);

    // JavaFX: Tooltip на маркере → Swing: getToolTipText(MouseEvent) → Web: div.tooltip
    root.addEventListener('mouseover', (e) => {
      const target = /** @type {SVGElement} */ (e.target);
      if (target.classList.contains('chart-marker')) {
        scheduleTooltip(e, () => markerTooltip(target.dataset.date, g.rowsByDate, cur));
      } else if (target.classList.contains('chart-bar')) {
        scheduleTooltip(e, () => target.dataset.tip);
      }
    });
    root.addEventListener('mouseout', (e) => {
      const target = /** @type {SVGElement} */ (e.target);
      if (target.classList.contains('chart-marker') || target.classList.contains('chart-bar')) hideTooltip();
    });

    // JavaFX: ContextMenuEvent → Swing: MouseEvent.isPopupTrigger → Web: событие contextmenu + preventDefault
    root.addEventListener('contextmenu', (e) => {
      hide();
      hideTooltip();
      showContextMenu(e, chartContextItems(isoFromDay(dayAt(e.clientX))), 'Меню графика');
    });
    root.addEventListener('dblclick', (e) => {
      hide();
      cmd('showInTable', isoFromDay(dayAt(e.clientX)));
    });
  }
}

/**
 * Содержимое карточки дня.
 * @param {string} iso дата
 * @param {number} value баланс, копейки
 * @param {Map<string, Array<object>>} rowsByDate строки по датам
 * @param {string} cur валюта
 * @returns {HTMLElement} содержимое
 */
function dayCard(iso, value, rowsByDate, cur) {
  const weekday = WEEKDAY_NAMES[new Date(`${iso}T00:00:00Z`).getUTCDay()];
  const rows = rowsByDate.get(iso) || [];
  return h('div', { class: 'day-card-body' },
    h('div', { class: 'day-card-date', text: `${ruDate(iso)}, ${weekday}` }),
    h('div', { class: `day-card-balance${value < 0 ? ' negative' : ''}`, text: `Баланс: ${formatMoney(value)} ${cur}` }),
    rows.length
      ? h('ul', { class: 'day-card-events' }, rows.slice(0, 8).map((r) => h('li', {
        class: `${r.kind === 'INCOME' ? 'income' : 'expense'}${r.flags.skipped ? ' skipped' : ''}`,
      }, h('span', { class: 'amount', text: r.amountSignedText }), ` ${r.title}`)))
      : h('div', { class: 'muted', text: 'Событий в этот день нет' }),
    rows.length > 8 ? h('div', { class: 'muted', text: `и ещё ${rows.length - 8}` }) : null);
}

/**
 * Текст подсказки маркера.
 * @param {string} iso дата
 * @param {Map<string, Array<object>>} rowsByDate строки по датам
 * @param {string} cur валюта
 * @returns {string} текст
 */
function markerTooltip(iso, rowsByDate, cur) {
  const rows = (rowsByDate.get(iso) || []).filter((r) => !r.flags.skipped);
  if (!rows.length) return '';
  const last = rows[rows.length - 1];
  return [
    ruDate(iso),
    ...rows.map((r) => `${r.amountSignedText} ${cur} — ${r.title}`),
    `Баланс: ${last.balanceAfterText} ${cur}`,
  ].join('\n');
}
