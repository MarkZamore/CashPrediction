/** @file Карточки сводки с переносом по доступной ширине. */
import {element, identify, color} from './dom.js';

const summaryObservers = new WeakMap();

/** Рассчитывает единое число колонок по минимуму и промежутку из токенов ядра. */
function layoutSummary(root) {
  if (root.hidden || !root.getClientRects().length) return;
  const css = getComputedStyle(root);
  // clientWidth округляет дробные пиксели и может раньше порога добавить слишком узкую колонку.
  const width = root.getBoundingClientRect().width - parseFloat(css.paddingLeft) - parseFloat(css.paddingRight)
    - parseFloat(css.borderLeftWidth) - parseFloat(css.borderRightWidth);
  const minimum = parseFloat(css.getPropertyValue('--cp-card-min-width'));
  const gap = parseFloat(css.getPropertyValue('--cp-card-gap'));
  const count = root.querySelectorAll(':scope > .card').length;
  const columns = Math.max(1, Math.min(count, Math.floor((width + gap) / (minimum + gap))));
  const value = String(columns);
  if (root.style.getPropertyValue('--cp-summary-columns') !== value)
    root.style.setProperty('--cp-summary-columns', value);
}

/** Рисует готовые значения и отправляет действия карточек в ядро. */
export function renderSummary(app, model) {
  const root = document.getElementById('summary');
  root.hidden = !model.visible; root.replaceChildren();
  if (!summaryObservers.has(root)) {
    // Один наблюдатель читает текущие карточки, включая изменения видимости и ширины панели.
    // Запись, меняющая высоту панели, выполняется после доставки наблюдения, а не в том же layout-цикле.
    let pending = false;
    const observer = new ResizeObserver(/** Объединяет изменения размера панели в один отложенный пересчёт колонок. */ () => {
      if (pending) return;
      pending = true;
      requestAnimationFrame(/** Снимает признак ожидания и пересчитывает колонки по текущему размеру панели. */ () => { pending = false; layoutSummary(root); });
    });
    summaryObservers.set(root, observer); observer.observe(root);
  }
  if (model.unavailableText) {
    root.append(element('div', 'summary-unavailable', model.unavailableText));
    layoutSummary(root); return;
  }
  for (const card of model.cards) {
    const node = identify(element('div', 'card'), card.id);
    node.tabIndex = 0; node.dataset.tooltip = card.explanation;
    const title = element('div', 'card-title', card.title);
    const value = element('div', 'card-value', card.value); value.style.color = color(card.valueColor);
    const caption = element('div', 'card-caption', card.caption); caption.style.color = color(card.captionColor);
    node.append(title, value, caption); root.append(node);
    // JavaFX: ContextMenu → Swing: JPopupMenu → Web: Menus.context.
    node.addEventListener('contextmenu', /** Подавляет меню браузера и открывает меню ядра для выбранной карточки. */ event => { event.preventDefault(); app.menus.context({kind: 'card', cardId: card.id}, event.clientX, event.clientY); });
    node.addEventListener('dblclick', /** Запрашивает меню карточки и выполняет его первое доступное действие. */ async () => {
      const response = await app.transport.query({type: 'contextMenu', target: {kind: 'card', cardId: card.id}});
      const action = response.result?.[0];
      if (action?.enabled) app.command(action.command, action.args, 'MAIN');
    });
    // JavaFX: Popup → Swing: JWindow → Web: Popups.sparkline.
    app.popups.sparkline(node, card.id);
  }
  layoutSummary(root);
}
