/** @file Меню из узлов ядра и навигация по настоящим кнопкам. */
import {element, identify, button, debounce} from './dom.js';
import {scope} from './keys.js';

/** Передаёт объявленную ядром клавишу меню через обычное намерение клавиатуры. */
function menuKey(app, key, target) {
  const focus = scope(target, app);
  const chord = {ctrl: false, alt: false, shift: false, key};
  if (app.hotkeys.some(binding => binding.chord.key === key && !binding.chord.ctrl && !binding.chord.alt && !binding.chord.shift && binding.scopes.includes(focus.scope))) app.send({type: 'key', chord, ...focus});
}

/** Отображает физическое сочетание клавиш без регистрации ускорителя браузера. */
export function accelerator(chord) {
  if (!chord) return '';
  return [chord.ctrl && 'Ctrl', chord.alt && 'Alt', chord.shift && 'Shift',
    chord.key.replace('DIGIT', '').replace('ENTER', 'Enter').replace('DELETE', 'Delete')].filter(Boolean).join('+');
}

/** Строит все виды меню и хранит только состояние их раскрытия. */
export class Menus {
  /** Подключает закрытие меню и клавиатурную навигацию. */
  constructor(app) {
    this.app = app;
    this.panels = [];
    document.addEventListener('pointerdown', event => {
      if (!event.target.closest('.menu-panel, .menu-node, .toolbar-node')) this.close();
    });
    document.addEventListener('keydown', event => this.navigate(event), true);
    document.addEventListener('keyup', event => {
      if ((event.code === 'AltLeft' || event.code === 'AltRight') && this.altPending) {
        this.altPending = false;
        if (document.querySelector('dialog:modal')) return;
        event.preventDefault(); event.stopImmediatePropagation();
        menuKey(this.app, 'ALT', event.target);
        document.querySelector('#menuBar button:enabled')?.focus();
      }
    }, true);
  }

  /** Закрывает раскрытые панели, сохраняя узлы меню для дампа. */
  close() {
    for (const panel of this.panels) panel.hidden = true;
    this.panels.length = 0;
  }

  /** Рисует строку меню. JavaFX: MenuBar → Swing: JMenuBar → Web: nav[role=menubar]. */
  bar(model) {
    const root = document.getElementById('menuBar');
    for (const fn of this.barDebouncers || []) { fn.cancel(); this.app.debouncers.delete(fn); }
    this.barDebouncers = [];
    root.replaceChildren(...model.menus.map(node => this.node(node, 'MENU', true)));
  }

  /** Строит панель дочерних пунктов. JavaFX: Menu → Swing: JMenu → Web: div[role=menu]. */
  panel(items, source) {
    const root = element('div', 'menu-panel');
    root.role = 'menu';
    root.hidden = true;
    root.append(...items.map(node => this.node(node, source)));
    return root;
  }

  /** Открывает меню у кнопки, ограничивая его видимой областью. */
  open(panel, anchor, top = false) {
    this.app.popups.dismissHover();
    if (top) this.close();
    panel.hidden = false;
    panel.style.position = 'fixed';
    const rect = anchor.getBoundingClientRect();
    const nested = anchor.closest('.menu-panel');
    panel.style.left = `${Math.max(0, Math.min(nested ? rect.right : rect.left, innerWidth - panel.offsetWidth))}px`;
    panel.style.top = `${Math.max(0, Math.min(nested ? rect.top : rect.bottom, innerHeight - panel.offsetHeight))}px`;
    this.panels.push(panel);
    panel.querySelector('button:enabled, input:enabled')?.focus();
  }

  /** Создаёт один виджет узла с модельной доступностью. */
  node(model, source, top = false) {
    const wrap = identify(element('div', 'menu-node'), model.id, model.kind);
    wrap.dataset.group = model.group || '';
    if (model.kind === 'Separator') {
      // JavaFX: SeparatorMenuItem → Swing: JPopupMenu.Separator → Web: hr.
      wrap.append(element('hr'));
      return wrap;
    }
    if (model.kind === 'Slider' || model.kind === 'Spinner') {
      // JavaFX: CustomMenuItem → Swing: SwingSliderMenuItem → Web: input в меню.
      wrap.classList.add('menu-custom');
      const label = element('div', 'menu-label', model.label || model.currentLabel || '');
      const input = element('input');
      input.type = model.kind === 'Slider' ? 'range' : 'number';
      input.min = model.min; input.max = model.max; input.step = model.step || 1; input.value = model.value;
      input.dataset.tooltip = model.tooltip || '';
      input.dataset.applyDelayMs = model.applyDelayMs || 0;
      input.style.width = `${model.widthPx || model.fieldWidthPx || 160}px`;
      const commit = debounce(() => this.app.send({type: model.kind === 'Slider' ? 'sliderCommit' : 'spinnerCommit', itemId: model.id, value: Number(input.value)}), model.applyDelayMs || 0);
      this.app.debouncers.add(commit);
      (this.barDebouncers ||= []).push(commit);
      input.addEventListener('input', () => {
        if (model.kind === 'Spinner') commit();
        else label.textContent = model.labels?.[Number(input.value) - model.min] ?? model.currentLabel ?? '';
      });
      input.addEventListener('change', () => commit.flush());
      input.addEventListener('keydown', event => { if (event.key === 'Enter') commit.flush(); });
      wrap.append(label, input);
      return wrap;
    }
    // JavaFX: MenuItem → Swing: JMenuItem → Web: button[role=menuitem].
    const item = button(model.id + '.action', '', model.tooltip, model.kind !== 'Info' && model.enabled !== false);
    item.role = 'menuitem';
    if (!top) {
      // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: menuitemcheckbox.
      // JavaFX: RadioMenuItem → Swing: JRadioButtonMenuItem → Web: menuitemradio.
      if (model.kind === 'Check' || model.kind === 'Radio') {
        item.role = model.kind === 'Check' ? 'menuitemcheckbox' : 'menuitemradio';
        item.setAttribute('aria-checked', String(!!(model.checked || model.selected)));
      }
      item.append(element('span', 'menu-mark', model.checked || model.selected ? '\u2713' : ''));
    }
    item.append(element('span', 'menu-label', model.text || ''));
    if (!top) item.append(element('span', 'menu-accel', accelerator(model.accel)));
    wrap.append(item);
    item.addEventListener('pointerenter', () => this.app.send({type: 'menuHover', itemId: model.id}));
    item.addEventListener('pointerleave', () => this.app.send({type: 'menuHover', itemId: null}));
    if (model.kind === 'Submenu') {
      const panel = this.panel(model.children, source);
      wrap.append(panel);
      item.setAttribute('aria-haspopup', 'menu');
      item.addEventListener('click', () => this.open(panel, item, top));
    } else item.addEventListener('click', () => {
      this.app.popups.dismissHover();
      this.close();
      this.app.command(model.command, model.args, source);
    });
    return wrap;
  }

  /** Открывает контекстное меню, полученное через запрос ядра. */
  async context(target, x, y, items) {
    // JavaFX: ContextMenuEvent → Swing: MouseEvent → Web: contextmenu.
    // JavaFX: ContextMenu → Swing: JPopupMenu → Web: div[role=menu].
    this.app.popups.dismissHover(); this.close();
    document.querySelectorAll('.context-menu').forEach(node => node.remove());
    const response = items ? {result: items} : await this.app.transport.query({type: 'contextMenu', target});
    if (!response.result) return;
    const panel = this.panel(response.result, target.kind === 'preview' ? 'FORM' : 'CONTEXT_MENU');
    panel.classList.add('context-menu');
    panel.dataset.target = target.kind === 'pastHeader' ? target.kind : target.kind === 'chart' ? `chart:${target.x},${target.y}` : target.kind === 'preview' ? `preview:${target.windowId}:${target.index}` : `${target.kind}:${target.rowId || target.cardId || ''}`;
    const owner = [...document.querySelectorAll('dialog[open]')].at(-1) || document.body;
    owner.append(panel);
    panel.hidden = false; panel.style.position = 'fixed';
    panel.style.left = `${Math.max(0, Math.min(x, innerWidth - panel.offsetWidth))}px`;
    panel.style.top = `${Math.max(0, Math.min(y, innerHeight - panel.offsetHeight))}px`;
    this.panels.push(panel);
    panel.querySelector('button:enabled')?.focus();
  }

  /** Перемещает фокус внутри раскрытого меню и по строке меню. */
  navigate(event) {
    const alt = event.code === 'AltLeft' || event.code === 'AltRight';
    if (!alt) this.altPending = false;
    if (alt && !event.ctrlKey && !event.shiftKey) {
      this.altPending = true; event.preventDefault(); event.stopImmediatePropagation(); return;
    }
    const panel = this.panels.at(-1);
    if (!panel && document.activeElement.closest('#menuBar')) {
      const controls = [...document.querySelectorAll('#menuBar > .menu-node > button:enabled')];
      const index = controls.indexOf(document.activeElement);
      if (event.code === 'ArrowRight') controls[(index + 1) % controls.length]?.focus();
      else if (event.code === 'ArrowLeft') controls[(index + controls.length - 1) % controls.length]?.focus();
      else if (event.code === 'ArrowDown' || event.code === 'ArrowUp') document.activeElement.click();
      else if (event.code === 'Escape') document.getElementById('table').focus();
      else return;
      event.preventDefault(); event.stopImmediatePropagation(); return;
    }
    if (!panel && event.code !== 'F10') return;
    if (!panel && (event.shiftKey || event.ctrlKey || event.altKey && event.code === 'F10')) return;
    if (!panel) {
      event.preventDefault(); event.stopImmediatePropagation();
      menuKey(this.app, 'F10', event.target);
      document.querySelector('#menuBar button:enabled')?.focus(); return;
    }
    const buttons = [...panel.querySelectorAll(':scope > .menu-node > button:enabled')];
    const index = buttons.indexOf(document.activeElement);
    if (event.code === 'Escape') this.close();
    else if (event.code === 'ArrowDown') buttons[(index + 1) % buttons.length]?.focus();
    else if (event.code === 'ArrowUp') buttons[(index + buttons.length - 1) % buttons.length]?.focus();
    else if (event.code === 'Home') buttons[0]?.focus();
    else if (event.code === 'End') buttons.at(-1)?.focus();
    else if (event.code === 'ArrowRight') document.activeElement?.click();
    else if (event.code === 'ArrowLeft') { panel.hidden = true; this.panels.pop(); panel.parentElement.querySelector('button')?.focus(); }
    else return;
    event.preventDefault(); event.stopImmediatePropagation();
  }
}
