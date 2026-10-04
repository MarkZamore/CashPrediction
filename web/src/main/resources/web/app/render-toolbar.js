/** @file Тулбар: только узлы и параметры модели ядра. */
import {element, identify, button, debounce} from './dom.js';
import {iconColor} from './icon.js';

/** Создаёт элементы тулбара без собственной таблицы команд. */
export function renderToolbar(app, model) {
  const root = document.getElementById('toolbar');
  const focused = document.activeElement === app.filter;
  const selection = focused ? [app.filter.selectionStart, app.filter.selectionEnd] : null;
  for (const fn of app.toolbarDebouncers || []) { fn.cancel(); app.debouncers.delete(fn); }
  app.toolbarDebouncers = [];
  root.replaceChildren();
  for (const item of model.items) {
    const wrap = identify(element('div', `toolbar-node ${item.kind}`), item.id, item.kind);
    root.append(wrap);
    if (item.kind === 'Spacer') continue;
    if (item.kind === 'Separator') { wrap.classList.add('separator'); continue; }
    if (item.kind === 'FilterField') {
      const input = identify(element('input'), item.id + '.input');
      input.value = item.text; input.placeholder = item.prompt; input.dataset.tooltip = item.tooltip;
      wrap.style.width = `${item.widthPx}px`; input.style.width = '100%';
      const send = debounce(/** Отправляет ядру текущее содержимое фильтра после задержки ввода. */ () => app.send({type: 'filterText', text: input.value}), item.debounceMs);
      app.filter = input; app.filterCommit = send; app.debouncers.add(send);
      app.toolbarDebouncers.push(send);
      input.addEventListener('input', /** Перезапускает отложенную отправку фильтра после изменения текста. */ () => send());
      const clear = button(item.id + '.clear', '\u2715', item.clearTooltip);
      clear.classList.add('glyph-only');
      clear.hidden = !item.clearVisible;
      clear.addEventListener('click', /** Очищает поле фильтра и немедленно отправляет пустое значение ядру. */ () => { input.value = ''; send.flush(); });
      wrap.append(input, clear); continue;
    }
    const primary = button(item.id + '.action', '', item.tooltip, item.enabled !== false);
    primary.append(element('span', 'toolbar-label', item.glyphOrText || item.text));
    if (Array.from(item.glyphOrText || item.text || '').length === 1) primary.classList.add('glyph-only');
    primary.classList.add(item.emphasis || 'NONE');
    iconColor(primary, item.enabled === false ? 'TEXT_MUTED' : item.emphasis === 'WHATIF' ? 'WHATIF' : item.emphasis === 'ACCENT' ? 'ACCENT' : 'TEXT_PRIMARY');
    primary.addEventListener('pointerdown', /** Предотвращает перенос фокуса при нажатии на кнопку тулбара. */ event => event.preventDefault());
    if (item.kind === 'Toggle') {
      primary.classList.add('toggle'); primary.setAttribute('aria-pressed', String(item.selected));
    }
    wrap.append(primary);
    if (item.kind === 'MenuButton' || item.kind === 'SplitButton') {
      // JavaFX: MenuButton → Swing: JButton + JPopupMenu → Web: button + div[role=menu].
      // JavaFX: SplitMenuButton → Swing: JPanel с основной JButton и JButton-стрелкой с JPopupMenu → Web: пара button + div[role=menu].
      const panel = app.menus.panel(item.items, 'TOOLBAR');
      wrap.append(panel);
      if (item.kind === 'SplitButton') {
        primary.addEventListener('click', /** Выполняет основную команду составной кнопки тулбара. */ () => app.command(item.main.command, item.main.args, 'TOOLBAR'));
        const arrow = button(item.id + '.arrow', '\u25be', item.tooltip);
        arrow.classList.add('toolbar-arrow');
        arrow.addEventListener('click', /** Открывает меню составной кнопки через её отдельную стрелку. */ () => app.menus.open(panel, primary, true));
        wrap.insertBefore(arrow, panel);
      } else {
        primary.append(element('span', 'toolbar-arrow', '\u25be'));
        primary.addEventListener('click', /** Открывает меню обычной кнопки меню тулбара. */ () => app.menus.open(panel, primary, true));
      }
    } else primary.addEventListener('click', /** Выполняет команду элемента тулбара с аргументами модели ядра. */ () => app.command(item.command, item.args, 'TOOLBAR'));
  }
  if (focused && app.filter?.isConnected) { app.filter.focus(); app.filter.setSelectionRange(...selection); }
}
