/** @file Сегменты статуса в порядке модели. */
import {element, identify, color} from './dom.js';
import {iconText, iconColor, serviceMarks} from './icon.js';

/** Обновляет текст и цвет всех сегментов, включая скрытые. */
export function renderStatus(model) {
  const root = document.getElementById('status');
  root.replaceChildren();
  for (const segment of model.segments) {
    const node = identify(element('span', 'status-segment'), segment.id);
    // Только сегмент снимка содержит встроенные служебные отметки; имя файла и причины ошибок остаются текстом.
    const positions = segment.id === 'session' ? serviceMarks(segment.text, ['\u2713', '\u2717'])
      : ['dirty', 'whatIf'].includes(segment.id) ? true : [];
    iconText(node, segment.text, positions);
    node.hidden = !segment.visible; node.style.color = color(segment.color);
    iconColor(node, segment.color);
    node.style.flex = segment.grow ? '1' : ''; node.dataset.tooltip = segment.tooltip;
    root.append(node);
  }
}
