/** @file Подсказки, календарь и карточки, созданные из готовых моделей. */
import {element, color, button} from './dom.js';
import {svgNode} from './render-chart.js';

/** Управляет короткоживущими окнами без бизнес-вычислений. */
export class Popups {
  /** Подключает общие подсказки всех динамических элементов. */
  constructor(app) {
    this.app = app; this.nodes = new Map();
    document.addEventListener('pointerover', event => {
      const node = event.target.closest('[data-tooltip]');
      // Пояснение карточки уже показано внутри спарклайна согласно §5.1.
      if (node && !node.closest('.card') && !node.contains(event.relatedTarget)) this.scheduleTooltip(node, () => node.dataset.tooltip, 600);
    });
    document.addEventListener('pointerout', event => {
      const node = event.target.closest('[data-tooltip]');
      if (node && !node.contains(event.relatedTarget)) this.hide('tooltip');
    });
    document.addEventListener('pointerdown', event => {
      this.dismissHover();
      if (!event.target.closest('.calendar, .calendar-button')) this.hide('calendar');
    });
  }

  /** Закрывает окно и отменяет отложенное появление. */
  hide(kind) {
    clearTimeout(this[kind + 'Timer']);
    this[kind + 'Epoch'] = (this[kind + 'Epoch'] || 0) + 1;
    this.nodes.get(kind)?.remove(); this.nodes.delete(kind);
  }

  /** Закрывает окна наведения при выборе меню или другом щелчке. */
  dismissHover() {
    this.hide('tooltip'); this.hide('sparkline'); this.app.chart?.clearHover();
  }

  /** Ставит всплывающее окно в видимые границы страницы. */
  show(kind, node, x, y, owner = document.body) {
    this.hide(kind);
    node.classList.add('popup', kind); node.dataset.popupKind = kind;
    owner.append(node); this.nodes.set(kind, node);
    node.style.left = `${Math.max(0, Math.min(x, innerWidth - node.offsetWidth))}px`;
    node.style.top = `${Math.max(0, Math.min(y, innerHeight - node.offsetHeight))}px`;
  }

  /** Откладывает запрос подсказки до фактического наведения. */
  scheduleTooltip(node, getText, delay) {
    this.hide('tooltip'); const epoch = this.tooltipEpoch;
    // JavaFX: Tooltip → Swing: JToolTip → Web: div.tooltip.
    this.tooltipTimer = setTimeout(async () => {
      const text = await getText();
      if (!text || epoch !== this.tooltipEpoch || !node.isConnected) return;
      const rect = node.getBoundingClientRect();
      const spacing = Number.parseFloat(getComputedStyle(node).getPropertyValue('--cp-spacing'));
      this.show('tooltip', element('div', '', text), rect.x + spacing, rect.bottom + spacing, node.closest('dialog[open]') || document.body);
      this.tooltipTimer = setTimeout(() => this.hide('tooltip'), 20000);
    }, delay);
  }

  /** Подключает ленивую подсказку ячейки таблицы. */
  tooltip(node, getText) {
    node.addEventListener('pointerenter', () => this.scheduleTooltip(node, getText, 600));
    node.addEventListener('pointerleave', () => this.hide('tooltip'));
  }

  /** Рисует карточку дня без собственных правил отбора событий. */
  dayCard(model, x, y) {
    // JavaFX: PopupWindow → Swing: JWindow → Web: div.dayCard.
    const node = element('div'); node.style.maxWidth = '320px';
    node.append(element('strong', '', model.header));
    const balance = element('div', '', model.balanceLine); balance.style.color = color(model.balanceColor); node.append(balance);
    for (const line of model.lines) { const row = element('div', '', line.text); row.style.color = color(line.color); node.append(row); }
    if (model.moreText || model.noneText) node.append(element('div', '', model.moreText || model.noneText));
    this.show('dayCard', node, x, y);
  }

  /** Подключает задержку показа спарклайна к карточке. */
  sparkline(anchor, cardId) {
    // JavaFX: PopupControl → Swing: SwingPopupControl → Web: div.sparkline.
    const start = () => {
      this.hide('sparkline'); const epoch = this.sparklineEpoch;
      this.sparklineTimer = setTimeout(async () => {
        const response = await this.app.transport.query({type: 'sparkline', cardId});
        if (epoch !== this.sparklineEpoch || !anchor.isConnected || !response.result) return;
        const model = response.result; const node = element('div'); node.style.width = '258px';
        node.append(element('strong', '', model.header), element('div', 'hint', model.explanation));
        if (model.points.length < 2) node.append(element('div', 'spark-no-data', model.noDataText));
        else {
          const svg = svgNode('svg', {width: 240, height: 60, viewBox: '0 0 240 60'});
          if (model.zeroY != null) svg.append(svgNode('line', {x1: 0, x2: 240, y1: model.zeroY * 60, y2: model.zeroY * 60, stroke: color('EXPENSE'), 'stroke-dasharray': '3 3'}));
          svg.append(svgNode('polyline', {points: model.points.map(p => `${p.x * 240},${p.y * 60}`).join(' '), fill: 'none', stroke: color('ACCENT'), 'stroke-width': 1.5}));
          if (model.marker) svg.append(svgNode('circle', {cx: model.marker.x * 240, cy: model.marker.y * 60, r: 3, fill: color('LINE_TODAY')}));
          node.append(svg);
        }
        if (model.minText || model.maxText) {
          const limits = element('div', 'spark-footer');
          limits.append(element('span', '', model.minText), element('span', '', model.maxText)); node.append(limits);
        }
        const rect = anchor.getBoundingClientRect(); this.show('sparkline', node, rect.x, rect.bottom + 4);
      }, 350);
    };
    anchor.addEventListener('pointerenter', start); anchor.addEventListener('focus', start);
    anchor.addEventListener('pointerleave', () => this.hide('sparkline')); anchor.addEventListener('blur', () => this.hide('sparkline'));
  }

  /** Запрашивает и рисует собственный календарь поля даты. */
  async calendar(anchor, selected, choose, month = null) {
    // JavaFX: Popup → Swing: JPopupMenu → Web: div.calendar.
    const response = await this.app.transport.query({type: 'calendar', month, selected: selected || null});
    if (!response.result) return;
    const model = response.result; const node = element('div'); node.style.width = '260px';
    const header = element('div', 'calendar-header');
    const prev = button('calendar.prev', '\u25c0', model.prevTooltip);
    const next = button('calendar.next', '\u25b6', model.nextTooltip);
    header.append(prev, element('strong', '', model.title), next); node.append(header);
    const grid = element('div', 'calendar-grid');
    for (const day of model.weekdays) grid.append(element('span', '', day));
    for (const day of model.days) {
      const control = button(day.date, day.text, ''); control.style.color = color(day.textColor);
      if (day.selected) control.style.background = color('ACCENT_WEAK');
      control.addEventListener('click', () => { choose(day.date); this.hide('calendar'); }); grid.append(control);
    }
    node.append(grid);
    const change = direction => {
      const [year, m] = model.month.split('-').map(Number);
      const date = new Date(Date.UTC(year, m - 1 + direction, 1));
      this.calendar(anchor, selected, choose, date.toISOString().slice(0, 7));
    };
    prev.addEventListener('click', () => change(-1)); next.addEventListener('click', () => change(1));
    node.addEventListener('keydown', event => { if (event.code === 'Escape') { event.preventDefault(); this.hide('calendar'); anchor.focus(); } });
    const rect = anchor.getBoundingClientRect(); this.show('calendar', node, rect.x, rect.bottom + 4, anchor.closest('dialog[open]') || document.body);
    grid.querySelector(`button[data-cp-id="${CSS.escape(selected || '')}"]`)?.focus();
  }
}
