/** @file Сегменты статуса в порядке модели. */
import {element, identify, color} from './dom.js';

/** Обновляет текст и цвет всех сегментов, включая скрытые. */
export function renderStatus(model) {
  const root = document.getElementById('status');
  root.replaceChildren();
  for (const segment of model.segments) {
    const node = identify(element('span', 'status-segment', segment.text), segment.id);
    node.hidden = !segment.visible; node.style.color = color(segment.color);
    node.style.flex = segment.grow ? '1' : ''; node.dataset.tooltip = segment.tooltip;
    root.append(node);
  }
}
