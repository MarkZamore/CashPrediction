/** @file Общие операции с настоящими узлами страницы. */

/** Создаёт узел без вставки разметки из модели. */
export function element(tag, className = '', text = '') {
  const node = document.createElement(tag);
  node.className = className;
  node.textContent = text ?? '';
  return node;
}

/** Записывает идентификатор модели в виджет. */
export function identify(node, id, kind = '') {
  node.dataset.cpId = id;
  if (kind) node.dataset.kind = kind;
  return node;
}

/** Возвращает ссылку на цвет ядра. */
export function color(token) {
  return token ? `var(--cp-${token.toLowerCase().replaceAll('_', '-')})` : '';
}

/** Применяет оформление текста, уже выбранное ядром. */
export function look(node, style = {}) {
  node.style.color = color(style.text || style.color);
  node.style.fontWeight = style.bold ? '700' : '';
  node.style.fontStyle = style.italic ? 'italic' : '';
  node.style.textDecoration = style.strike ? 'line-through' : '';
}

/** Создаёт кнопку с доступностью и подсказкой из модели. */
export function button(id, text, tooltip, enabled = true) {
  const node = identify(element('button', '', text), id);
  node.type = 'button';
  node.disabled = !enabled;
  node.dataset.tooltip = tooltip || '';
  return node;
}

/** Измеряет видимый прямоугольник в координатах содержимого. */
export function bounds(node) {
  const rect = node.getBoundingClientRect();
  return {x: rect.x, y: rect.y, width: rect.width, height: rect.height};
}

/** Измеряет живое содержимое диалога без рамки и аналога заголовка ОС. */
export function dialogContentBounds(root) {
  const parts = [...root.querySelectorAll(':scope > .window-header, :scope > .form-body, :scope > .window-buttons, :scope > .alert-content')].filter(visible).map(bounds).filter(rect => rect.width > 0 && rect.height > 0);
  if (!parts.length) throw new Error('Dialog content missing');
  const x = Math.min(...parts.map(rect => rect.x)); const y = Math.min(...parts.map(rect => rect.y));
  return {x, y, width: Math.max(...parts.map(rect => rect.x + rect.width)) - x, height: Math.max(...parts.map(rect => rect.y + rect.height)) - y};
}

/** Центрирует измеренное содержимое над живым содержимым владельца, сдвигая внешнее окно. */
export function centerDialogContent(node, ownerId) {
  const owner = [...document.querySelectorAll('dialog[open], .quick-edit')].find(candidate => candidate !== node && candidate.dataset.cpId === ownerId);
  const main = document.getElementById('main');
  const target = owner ? dialogContentBounds(owner) : bounds(visible(main) ? main : document.documentElement);
  const content = dialogContentBounds(node); const outer = bounds(node);
  const x = outer.x + target.x + (target.width - content.width) / 2 - content.x;
  const y = outer.y + target.y + (target.height - content.height) / 2 - content.y;
  node.style.position = 'fixed'; node.style.margin = '0'; node.style.right = 'auto'; node.style.bottom = 'auto';
  node.style.left = `${Math.max(0, Math.min(x, innerWidth - outer.width))}px`;
  node.style.top = `${Math.max(0, Math.min(y, innerHeight - outer.height))}px`;
}

/** Применяет восстановленные границы окна в видимой области браузера. */
export function restoreBounds(node, rectangle) {
  if (!rectangle) return;
  node.style.margin = '0'; node.style.position = 'fixed';
  node.style.left = `${Math.max(0, Math.min(rectangle.x, innerWidth - rectangle.width))}px`;
  node.style.top = `${Math.max(0, Math.min(rectangle.y, innerHeight - rectangle.height))}px`;
  node.style.width = `${rectangle.width}px`; node.style.height = `${rectangle.height}px`;
}

/** Ждёт кадра, в котором вставленные узлы могут быть показаны. */
export function frame() {
  return new Promise(resolve => requestAnimationFrame(resolve));
}

/** Планирует действие и позволяет тесту дождаться последнего ввода. */
export function debounce(fn, delay) {
  let timer;
  const run = (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => { timer = null; fn(...args); }, delay);
  };
  run.flush = (...args) => { clearTimeout(timer); timer = null; return fn(...args); };
  run.cancel = () => { clearTimeout(timer); timer = null; };
  run.pending = () => timer != null;
  return run;
}

/** Проверяет видимость узла с учётом скрытых предков. */
export function visible(node) {
  return node.isConnected && !node.closest('[hidden]') && node.getClientRects().length > 0;
}
