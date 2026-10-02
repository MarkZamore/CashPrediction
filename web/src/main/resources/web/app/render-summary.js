/** @file Карточки сводки с переносом по доступной ширине. */
import {element, identify, color} from './dom.js';

/** Рисует готовые значения и отправляет действия карточек в ядро. */
export function renderSummary(app, model) {
  const root = document.getElementById('summary');
  root.hidden = !model.visible; root.replaceChildren();
  if (model.unavailableText) root.append(element('div', 'summary-unavailable', model.unavailableText));
  for (const card of model.cards) {
    const node = identify(element('div', 'card'), card.id);
    node.tabIndex = 0; node.dataset.tooltip = card.explanation;
    const title = element('div', 'card-title', card.title);
    const value = element('div', 'card-value', card.value); value.style.color = color(card.valueColor);
    const caption = element('div', 'card-caption', card.caption); caption.style.color = color(card.captionColor);
    node.append(title, value, caption); root.append(node);
    node.addEventListener('contextmenu', event => { event.preventDefault(); app.menus.context({kind: 'card', cardId: card.id}, event.clientX, event.clientY); });
    node.addEventListener('dblclick', async () => {
      const response = await app.transport.query({type: 'contextMenu', target: {kind: 'card', cardId: card.id}});
      const action = response.result?.[0];
      if (action?.enabled) app.command(action.command, action.args, 'MAIN');
    });
    app.popups.sparkline(node, card.id);
  }
}
