/**
 * @file Панель сводки: карточки «Сейчас», «Через N мес.», «Конец периода», «Минимум», «Первый минус»,
 * «Средний итог/мес», «Цель». Наведение или фокус на карточке показывает поповер со спарклайном баланса
 * (PopupControl), правая кнопка - контекстное меню карточки.
 */

import { h, ruDate, parseMoney, formatMoney } from './util.js';
import { cmd } from './store.js';
import { showContextMenu } from './menu.js';
import { showPopover, hidePopover, sparkline } from './popup.js';
import { cardContextItems } from './app-menu.js';

/**
 * Карточки сводки по состоянию.
 * @param {object} state состояние с сервера
 * @returns {Array<{title:string, value:string, sub?:string, date?:string, tone?:string, hint?:string}>} карточки
 */
function buildCards(state) {
  const f = state.forecast;
  const s = f.summary;
  const plan = state.plan;
  const cur = plan.currency;
  const cushion = parseMoney(plan.cushion) || 0;
  /** @param {number|null} minor сумма @returns {string} тон карточки */
  const tone = (minor) => (minor === null ? '' : minor < 0 ? 'negative' : cushion > 0 && minor < cushion ? 'warn' : '');
  const cards = [];
  const now = parseMoney(state.nowBalance);
  cards.push({
    title: 'Сейчас', value: `${state.nowBalanceText} ${cur}`, sub: ruDate(state.today), date: state.today, tone: tone(now),
    hint: 'Баланс на сегодня по прогнозу',
  });
  for (const b of s.balanceAfterMonths || []) {
    const minor = parseMoney(b.balance);
    cards.push({ title: `Через ${b.months} мес.`, value: `${b.balanceText} ${cur}`, sub: ruDate(b.date), date: b.date, tone: tone(minor) });
  }
  const last = f.chart && f.chart.length ? f.chart[f.chart.length - 1] : null;
  if (last) {
    cards.push({
      title: 'Конец периода', value: `${formatMoney(last.balanceMinor)} ${cur}`, sub: state.periodEndText, date: last.date,
      tone: tone(last.balanceMinor), hint: 'Сколько накопится к концу видимого периода',
    });
  }
  if (s.minBalanceText) {
    cards.push({
      title: 'Минимум', value: `${s.minBalanceText} ${cur}`, sub: s.minBalanceDate ? ruDate(s.minBalanceDate) : '',
      date: s.minBalanceDate || undefined, tone: tone(parseMoney(s.minBalance)), hint: 'Самый низкий баланс за горизонт плана',
    });
  }
  cards.push({
    title: 'Первый минус',
    value: s.firstNegativeDate ? ruDate(s.firstNegativeDate) : 'нет',
    sub: s.firstNegativeDate ? 'баланс уходит в минус' : s.firstBelowCushionDate ? `ниже подушки ${ruDate(s.firstBelowCushionDate)}` : 'денег хватает',
    date: s.firstNegativeDate || s.firstBelowCushionDate || undefined,
    tone: s.firstNegativeDate ? 'negative' : s.firstBelowCushionDate ? 'warn' : 'good',
  });
  cards.push({
    title: 'Средний итог/мес', value: `${s.averageMonthlyNetText} ${cur}`, sub: 'доходы минус расходы',
    tone: (parseMoney(s.averageMonthlyNet) || 0) < 0 ? 'negative' : '', hint: 'Сколько в среднем прибавляется за месяц',
  });
  if (plan.goal) {
    cards.push({
      title: `Цель: ${plan.goal.title || 'цель'}`,
      value: s.goalReachDate ? ruDate(s.goalReachDate) : 'не достигается',
      sub: `${plan.goal.targetText} ${cur}`,
      date: s.goalReachDate || undefined,
      tone: s.goalReachDate ? 'good' : 'warn',
      hint: 'Когда баланс впервые достигнет суммы цели',
    });
  }
  return cards;
}

/**
 * Панель сводки.
 */
export class SummaryPanel {
  /**
   * @param {HTMLElement} container раздел сводки
   */
  constructor(container) {
    this.container = container;
    this.timer = null;
  }

  /**
   * Перерисовывает карточки.
   * @param {object} state состояние
   */
  render(state) {
    hidePopover();
    if (!state.forecast) {
      this.container.replaceChildren(h('div', { class: 'summary-empty', text: 'Сводка недоступна: прогноз не построен' }));
      return;
    }
    this.container.replaceChildren(...buildCards(state).map((card) => this.cardElement(card, state)));
  }

  /**
   * Элемент карточки.
   * @param {object} card карточка
   * @param {object} state состояние
   * @returns {HTMLElement} элемент
   */
  cardElement(card, state) {
    const el = h('div', {
      class: `summary-card ${card.tone || ''}`.trim(), tabindex: '0', role: 'group', 'aria-label': `${card.title}: ${card.value}`,
    },
    h('div', { class: 'card-title', text: card.title }),
    h('div', { class: 'card-value', text: card.value }),
    h('div', { class: 'card-sub', text: card.sub || '' }));
    /** Показывает поповер с задержкой. */
    const open = () => {
      clearTimeout(this.timer);
      this.timer = setTimeout(() => this.showSparkline(el, card, state), 350);
    };
    /** Скрывает поповер. */
    const close = () => {
      clearTimeout(this.timer);
      hidePopover();
    };
    el.addEventListener('mouseenter', open);
    el.addEventListener('mouseleave', close);
    el.addEventListener('focus', open);
    el.addEventListener('blur', close);
    // JavaFX: ContextMenuEvent → Swing: MouseEvent.isPopupTrigger → Web: событие contextmenu + preventDefault
    el.addEventListener('contextmenu', (e) => {
      close();
      showContextMenu(e, cardContextItems(card), `Карточка «${card.title}»`);
    });
    el.addEventListener('dblclick', () => {
      if (card.date) cmd('showInTable', card.date);
    });
    return el;
  }

  /**
   * Поповер со спарклайном баланса и отметкой даты карточки.
   * JavaFX: PopupControl (SparklinePopupControl со своим Skin) → Swing: SwingPopupControl (JWindow) → Web: div.popover
   * @param {HTMLElement} el карточка
   * @param {object} card данные карточки
   * @param {object} state состояние
   */
  showSparkline(el, card, state) {
    if (!el.isConnected || !state.forecast.chart.length) return;
    const points = state.forecast.chart;
    const plan = state.plan;
    showPopover(el, h('div', { class: 'spark-popover' },
      h('div', { class: 'spark-title', text: card.title }),
      h('div', { class: 'spark-value', text: card.value }),
      sparkline(points, {
        width: 260,
        height: 72,
        cushionMinor: parseMoney(plan.cushion) || 0,
        goalMinor: plan.goal ? parseMoney(plan.goal.target) || 0 : 0,
        highlightDate: card.date,
      }),
      h('div', { class: 'spark-caption', text: card.hint || `Баланс с ${ruDate(points[0].date)} по ${ruDate(points[points.length - 1].date)}` })));
  }
}
