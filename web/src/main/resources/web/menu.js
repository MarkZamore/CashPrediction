/**
 * @file Меню тонкого клиента: строка меню, всплывающие меню и подменю, пункты-флажки и радиогруппы,
 * пользовательские пункты с полями ввода, кнопка-меню, кнопка с разделённым меню, контекстное меню
 * и горячие клавиши.
 *
 * Описание пункта меню (объект):
 *   { type: 'item' | 'check' | 'radio' | 'separator' | 'submenu' | 'custom',
 *     text, accel, action(), checked(), disabled(), items (массив или функция), render(close) → Node, title }
 * checked/disabled/text могут быть функциями - они вычисляются при каждом открытии меню, поэтому меню
 * всегда показывает текущее состояние с сервера.
 */

import { h, clear } from './util.js';
import { hideTooltip } from './popup.js';

/** Открытые корневые всплывающие меню (одновременно открыто одно корневое меню и его подменю). */
let openRoot = null;

/**
 * Значение, которое может быть функцией.
 * @param {any} v значение или функция
 * @returns {any} вычисленное значение
 */
function val(v) {
  return typeof v === 'function' ? v() : v;
}

/**
 * Всплывающее меню со списком пунктов.
 * JavaFX: ContextMenu / Menu (всплывающая часть) → Swing: JPopupMenu → Web: <ul role="menu">
 */
export class PopupMenu {
  /**
   * @param {Array|Function} items описание пунктов или функция, возвращающая их
   * @param {object} [options] {parent: PopupMenu, onClose: Function, label: string}
   */
  constructor(items, options = {}) {
    this.items = items;
    this.parent = options.parent || null;
    this.onClose = options.onClose || (() => {});
    this.label = options.label || '';
    this.child = null;
    this.el = null;
    this.itemEls = [];
  }

  /**
   * Показывает меню в точке экрана.
   * @param {number} x левый край, px
   * @param {number} y верхний край, px
   * @param {boolean} [focusFirst=true] перевести фокус на первый доступный пункт
   */
  openAt(x, y, focusFirst = true) {
    if (!this.parent) {
      closeAllMenus();
      openRoot = this;
    }
    this.build();
    document.body.append(this.el);
    // Не даём меню выйти за край окна.
    const rect = this.el.getBoundingClientRect();
    const left = Math.max(4, Math.min(x, window.innerWidth - rect.width - 4));
    const top = Math.max(4, Math.min(y, window.innerHeight - rect.height - 4));
    this.el.style.left = `${left}px`;
    this.el.style.top = `${top}px`;
    if (focusFirst) this.focusIndex(this.nextEnabled(-1, 1));
    else this.el.focus();
  }

  /**
   * Показывает меню под элементом (кнопкой или пунктом строки меню).
   * @param {HTMLElement} anchor элемент-якорь
   * @param {boolean} [focusFirst=true] фокус на первый пункт
   */
  openBelow(anchor, focusFirst = true) {
    const r = anchor.getBoundingClientRect();
    this.anchor = anchor;
    this.openAt(r.left, r.bottom, focusFirst);
  }

  /** Строит DOM меню по текущему состоянию. */
  build() {
    // JavaFX: Menu → Swing: JMenu → Web: <ul role="menu">
    this.el = h('ul', { class: 'menu-popup', role: 'menu', tabindex: '-1', 'aria-label': this.label });
    this.itemEls = [];
    this.el.addEventListener('keydown', (e) => this.onKey(e));
    const items = val(this.items) || [];
    items.forEach((item) => {
      const li = this.buildItem(item);
      this.el.append(li);
      this.itemEls.push({ item, li });
    });
  }

  /**
   * Строит один пункт меню.
   * @param {object} item описание пункта
   * @returns {HTMLLIElement} элемент
   */
  buildItem(item) {
    const type = item.type || 'item';
    if (type === 'separator') {
      // JavaFX: SeparatorMenuItem → Swing: JPopupMenu.Separator → Web: <li role="separator"><hr>
      return h('li', { role: 'separator', class: 'menu-separator' }, h('hr'));
    }
    if (type === 'custom') {
      // JavaFX: CustomMenuItem (hideOnClick=false) → Swing: JPanel в JMenu → Web: <li class="custom">
      const li = h('li', { class: 'menu-custom custom', role: 'none' });
      li.append(item.render(() => closeAllMenus()));
      // Клики и клавиши внутри поля не закрывают меню и не двигают выделение пунктов.
      li.addEventListener('click', (e) => e.stopPropagation());
      li.addEventListener('keydown', (e) => {
        if (e.key !== 'Escape' && e.key !== 'ArrowDown' && e.key !== 'ArrowUp') e.stopPropagation();
      });
      return li;
    }
    const disabled = !!val(item.disabled);
    const text = val(item.text);
    let role = 'menuitem';
    let mark = '';
    if (type === 'check') {
      // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: role="menuitemcheckbox"
      role = 'menuitemcheckbox';
      mark = val(item.checked) ? '✓' : '';
    } else if (type === 'radio') {
      // JavaFX: RadioMenuItem + ToggleGroup → Swing: JRadioButtonMenuItem + ButtonGroup → Web: role="menuitemradio"
      role = 'menuitemradio';
      mark = val(item.checked) ? '●' : '';
    }
    // JavaFX: MenuItem → Swing: JMenuItem + setAccelerator → Web: <li role="menuitem"> + keydown
    const li = h('li', {
      role,
      class: `menu-item${disabled ? ' disabled' : ''}`,
      tabindex: '-1',
      'aria-disabled': disabled ? 'true' : null,
      'aria-checked': type === 'check' || type === 'radio' ? String(!!val(item.checked)) : null,
      'aria-haspopup': type === 'submenu' ? 'menu' : null,
      title: val(item.title) || null,
    },
    h('span', { class: 'menu-mark', 'aria-hidden': 'true', text: mark }),
    h('span', { class: 'menu-text', text }),
    h('span', { class: 'menu-accel', text: type === 'submenu' ? '▸' : (item.accel || '') }));
    li.addEventListener('click', (e) => {
      e.stopPropagation();
      this.activate(item, li);
    });
    li.addEventListener('mouseenter', () => {
      li.focus();
      if (type === 'submenu' && !disabled) this.openSubmenu(item, li, false);
      else this.closeChild();
    });
    return li;
  }

  /**
   * Выполняет пункт меню.
   * @param {object} item описание
   * @param {HTMLElement} li элемент
   */
  activate(item, li) {
    if (val(item.disabled)) return;
    if (item.type === 'submenu') {
      this.openSubmenu(item, li, true);
      return;
    }
    closeAllMenus();
    if (item.action) {
      // Действие выполняется после закрытия меню, чтобы диалог получил фокус.
      setTimeout(() => item.action(), 0);
    }
  }

  /**
   * Открывает подменю справа от пункта.
   * @param {object} item описание подменю
   * @param {HTMLElement} li пункт
   * @param {boolean} focusFirst фокус на первый пункт подменю
   */
  openSubmenu(item, li, focusFirst) {
    if (this.child && this.child.ownerLi === li) {
      if (focusFirst) this.child.focusIndex(this.child.nextEnabled(-1, 1));
      return;
    }
    this.closeChild();
    // JavaFX: Menu (вложенное) → Swing: JMenu в JMenu → Web: вложенный <ul role="menu">
    const sub = new PopupMenu(item.items, { parent: this, label: val(item.text) });
    sub.ownerLi = li;
    this.child = sub;
    li.setAttribute('aria-expanded', 'true');
    const r = li.getBoundingClientRect();
    sub.openAt(r.right - 2, r.top - 4, focusFirst);
  }

  /** Закрывает открытое подменю. */
  closeChild() {
    if (this.child) {
      this.child.ownerLi?.setAttribute('aria-expanded', 'false');
      this.child.close();
      this.child = null;
    }
  }

  /** Закрывает меню и подменю. */
  close() {
    this.closeChild();
    if (this.el) {
      this.el.remove();
      this.el = null;
    }
    if (openRoot === this) {
      openRoot = null;
      this.onClose();
    }
  }

  /**
   * Индекс следующего доступного пункта.
   * @param {number} from текущий индекс
   * @param {number} step +1 или -1
   * @returns {number} индекс или -1
   */
  nextEnabled(from, step) {
    const n = this.itemEls.length;
    for (let i = 1; i <= n; i++) {
      const idx = (from + step * i + n * 2) % n;
      const { item } = this.itemEls[idx];
      if (item.type !== 'separator' && !val(item.disabled)) return idx;
    }
    return -1;
  }

  /**
   * Переводит фокус на пункт.
   * @param {number} idx индекс
   */
  focusIndex(idx) {
    if (idx < 0) return;
    const { item, li } = this.itemEls[idx];
    if (item.type === 'custom') {
      const input = li.querySelector('input,select,button');
      (input || li).focus();
    } else {
      li.focus();
    }
  }

  /** @returns {number} индекс пункта с фокусом */
  focusedIndex() {
    return this.itemEls.findIndex(({ li }) => li === document.activeElement || li.contains(document.activeElement));
  }

  /**
   * Клавиатура: стрелки, Home/End, Enter/пробел, Esc, стрелки влево/вправо для подменю и строки меню.
   * @param {KeyboardEvent} e событие
   */
  onKey(e) {
    const idx = this.focusedIndex();
    const current = idx >= 0 ? this.itemEls[idx] : null;
    switch (e.key) {
      case 'ArrowDown':
        this.focusIndex(this.nextEnabled(idx, 1));
        break;
      case 'ArrowUp':
        this.focusIndex(this.nextEnabled(idx < 0 ? 0 : idx, -1));
        break;
      case 'Home':
        this.focusIndex(this.nextEnabled(-1, 1));
        break;
      case 'End':
        this.focusIndex(this.nextEnabled(0, -1));
        break;
      case 'Enter':
      case ' ':
        if (current && current.item.type !== 'custom') this.activate(current.item, current.li);
        break;
      case 'ArrowRight':
        if (current && current.item.type === 'submenu') {
          this.openSubmenu(current.item, current.li, true);
        } else if (this.menubarMove) {
          this.menubarMove(1);
        } else {
          return;
        }
        break;
      case 'ArrowLeft':
        if (this.parent) {
          const owner = this.ownerLi;
          this.parent.closeChild();
          owner?.focus();
        } else if (this.menubarMove) {
          this.menubarMove(-1);
        } else {
          return;
        }
        break;
      case 'Escape':
        if (this.parent) {
          const owner = this.ownerLi;
          this.parent.closeChild();
          owner?.focus();
        } else {
          const anchor = this.anchor;
          closeAllMenus();
          anchor?.focus();
        }
        break;
      case 'Tab':
        closeAllMenus();
        return;
      default:
        return;
    }
    e.preventDefault();
    e.stopPropagation();
  }
}

/** Закрывает все открытые меню. */
export function closeAllMenus() {
  if (openRoot) openRoot.close();
}

/** @returns {boolean} открыто ли какое-нибудь меню */
export function isMenuOpen() {
  return !!openRoot;
}

// Щелчок мимо меню закрывает его (autoHide).
document.addEventListener('pointerdown', (e) => {
  if (openRoot && !e.target.closest('.menu-popup') && !e.target.closest('[data-menu-anchor]')) {
    closeAllMenus();
  }
}, true);
window.addEventListener('blur', () => closeAllMenus());
window.addEventListener('resize', () => closeAllMenus());

/**
 * Строка меню.
 * JavaFX: MenuBar → Swing: JMenuBar → Web: <nav role="menubar">
 */
export class MenuBar {
  /**
   * @param {HTMLElement} nav элемент <nav role="menubar">
   * @param {Array<{text: string, mnemonic: string, items: Array|Function}>} menus верхние меню
   */
  constructor(nav, menus) {
    this.nav = nav;
    this.menus = menus;
    this.buttons = [];
    this.current = -1;
    clear(nav);
    const list = h('ul', { class: 'menubar-list', role: 'none' });
    menus.forEach((menu, i) => {
      // JavaFX: Menu → Swing: JMenu → Web: <li role="menuitem" aria-haspopup="menu">
      const button = h('li', {
        role: 'menuitem',
        class: 'menubar-item',
        tabindex: i === 0 ? '0' : '-1',
        'aria-haspopup': 'menu',
        'aria-expanded': 'false',
        dataset: { menuAnchor: '1' },
        text: menu.text,
      });
      button.addEventListener('click', () => (this.current === i ? this.closeMenu() : this.open(i, false)));
      button.addEventListener('mouseenter', () => {
        if (this.current >= 0 && this.current !== i) this.open(i, false);
      });
      button.addEventListener('keydown', (e) => this.onKey(e, i));
      list.append(button);
      this.buttons.push(button);
    });
    nav.append(list);
  }

  /**
   * Открывает верхнее меню.
   * @param {number} i индекс
   * @param {boolean} focusFirst фокус на первый пункт
   */
  open(i, focusFirst) {
    const button = this.buttons[i];
    const popup = new PopupMenu(this.menus[i].items, {
      label: this.menus[i].text,
      onClose: () => {
        button.setAttribute('aria-expanded', 'false');
        button.classList.remove('open');
        if (this.popup === popup) {
          this.popup = null;
          this.current = -1;
        }
      },
    });
    popup.menubarMove = (step) => {
      const next = (i + step + this.buttons.length) % this.buttons.length;
      this.open(next, true);
    };
    this.popup = popup;
    popup.openBelow(button, focusFirst);
    // openBelow закрыл предыдущее меню (и сбросил current) - выставляем текущее после этого.
    this.current = i;
    this.buttons.forEach((b, j) => b.setAttribute('tabindex', j === i ? '0' : '-1'));
    button.setAttribute('aria-expanded', 'true');
    button.classList.add('open');
    if (!focusFirst) button.focus();
  }

  /** Закрывает открытое верхнее меню. */
  closeMenu() {
    closeAllMenus();
  }

  /** Переводит фокус на строку меню (F10 / Alt). */
  focus() {
    const i = Math.max(0, this.current);
    this.buttons[i].focus();
  }

  /**
   * Клавиатура на пункте строки меню.
   * @param {KeyboardEvent} e событие
   * @param {number} i индекс пункта
   */
  onKey(e, i) {
    const n = this.buttons.length;
    switch (e.key) {
      case 'ArrowRight':
        this.buttons[(i + 1) % n].focus();
        if (this.current >= 0) this.open((i + 1) % n, false);
        break;
      case 'ArrowLeft':
        this.buttons[(i - 1 + n) % n].focus();
        if (this.current >= 0) this.open((i - 1 + n) % n, false);
        break;
      case 'ArrowDown':
      case 'Enter':
      case ' ':
        this.open(i, true);
        break;
      case 'Escape':
        closeAllMenus();
        this.buttons[i].blur();
        break;
      default:
        return;
    }
    e.preventDefault();
  }
}

/**
 * Кнопка-меню.
 * JavaFX: MenuButton → Swing: SwingMenuButton (JButton + JPopupMenu) → Web: <button> + <ul role="menu">
 * @param {string|Function} text подпись (со стрелкой ▾)
 * @param {Array|Function} items пункты
 * @param {string} [title] всплывающая подсказка (Tooltip → setToolTipText → title)
 * @returns {HTMLButtonElement} кнопка
 */
export function menuButton(text, items, title) {
  const button = h('button', {
    type: 'button', class: 'tool-button menu-button', 'aria-haspopup': 'menu', 'aria-expanded': 'false',
    title, dataset: { menuAnchor: '1' },
  });
  const label = h('span', { text: val(text) });
  button.append(label, h('span', { class: 'arrow', 'aria-hidden': 'true', text: ' ▾' }));
  button.updateText = () => {
    label.textContent = val(text);
  };
  let popup = null;
  /**
   * Открывает список кнопки (повторное нажатие закрывает его).
   * @param {boolean} focusFirst перевести фокус на первый пункт (открытие с клавиатуры)
   */
  const open = (focusFirst) => {
    if (popup) {
      closeAllMenus();
      return;
    }
    popup = new PopupMenu(items, {
      label: val(text),
      onClose: () => {
        popup = null;
        button.setAttribute('aria-expanded', 'false');
      },
    });
    button.setAttribute('aria-expanded', 'true');
    popup.openBelow(button, focusFirst);
  };
  button.addEventListener('click', (e) => open(e.detail === 0));
  button.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (!popup) open(true);
    }
  });
  return button;
}

/**
 * Кнопка с разделённым меню: основная часть выполняет действие, стрелка открывает список.
 * JavaFX: SplitMenuButton → Swing: SwingSplitMenuButton → Web: div.split-button
 * @param {string} text подпись основной кнопки
 * @param {Function} action действие основной кнопки
 * @param {Array|Function} items пункты списка
 * @param {object} [titles] {main, arrow} подсказки
 * @returns {HTMLDivElement} группа кнопок
 */
export function splitMenuButton(text, action, items, titles = {}) {
  const main = h('button', { type: 'button', class: 'tool-button split-main', title: titles.main, text });
  main.addEventListener('click', () => action());
  const arrow = menuButton('', items, titles.arrow);
  arrow.classList.add('split-arrow');
  arrow.setAttribute('aria-label', titles.arrow || 'Ещё');
  return h('div', { class: 'split-button', role: 'group', 'aria-label': text }, main, arrow);
}

/**
 * Показывает контекстное меню в точке события contextmenu.
 * JavaFX: ContextMenu + ContextMenuEvent → Swing: JPopupMenu + isPopupTrigger() → Web: contextmenu + preventDefault
 * @param {MouseEvent|{clientX:number, clientY:number}} event событие (или координаты для клавиши Menu)
 * @param {Array|Function} items пункты
 * @param {string} [label] подпись для экранного диктора
 */
export function showContextMenu(event, items, label = 'Контекстное меню') {
  // JavaFX: ContextMenuEvent → Swing: MouseEvent.isPopupTrigger → Web: событие contextmenu
  if (event.preventDefault) event.preventDefault();
  // Подсказка ячейки, показанная наведением перед щелчком правой кнопкой, иначе лежала бы поверх первых пунктов меню.
  hideTooltip();
  const popup = new PopupMenu(items, { label });
  popup.build = function build() {
    PopupMenu.prototype.build.call(this);
    // JavaFX: ContextMenu → Swing: JPopupMenu → Web: <ul class="context-menu" role="menu">
    this.el.classList.add('context-menu');
  };
  popup.openAt(event.clientX, event.clientY, !event.pointerType || event.detail === 0);
}

// ------------------------------------------------------------------ горячие клавиши

/** Зарегистрированные сочетания: строка «Ctrl+Shift+S» → описание. */
const hotkeys = new Map();

/**
 * Регистрирует горячие клавиши.
 * @param {Array<{keys: string[], action: Function, allowInInput?: boolean, allowInDialog?: boolean,
 *   when?: (e: KeyboardEvent) => boolean}>} list описания
 */
export function registerHotkeys(list) {
  for (const entry of list) {
    for (const k of entry.keys) {
      hotkeys.set(normalizeCombo(k), entry);
    }
  }
}

/**
 * Приводит запись сочетания к единому виду.
 * @param {string} combo «ctrl+shift+s»
 * @returns {string} «Ctrl+Shift+S»
 */
function normalizeCombo(combo) {
  const parts = combo.split('+').map((p) => p.trim());
  const key = parts.pop();
  const mods = new Set(parts.map((p) => p.toLowerCase()));
  return [mods.has('ctrl') && 'Ctrl', mods.has('alt') && 'Alt', mods.has('shift') && 'Shift',
    key.length === 1 ? key.toUpperCase() : key].filter(Boolean).join('+');
}

/**
 * Сочетание из события клавиатуры (по физической клавише, чтобы Ctrl+S работал и в русской раскладке).
 * @param {KeyboardEvent} e событие
 * @returns {string} «Ctrl+S»
 */
function comboOf(e) {
  let key = e.key;
  if (/^Key[A-Z]$/.test(e.code)) key = e.code.slice(3);
  else if (/^Digit\d$/.test(e.code)) key = e.code.slice(5);
  else if (key.length === 1) key = key.toUpperCase();
  return [e.ctrlKey || e.metaKey ? 'Ctrl' : '', e.altKey ? 'Alt' : '', e.shiftKey ? 'Shift' : '', key]
    .filter(Boolean).join('+');
}

/** Сочетания, которые в поле ввода остаются за браузером (правка текста). */
const TEXT_EDITING = new Set(['Ctrl+Z', 'Ctrl+Y', 'Ctrl+A', 'Ctrl+C', 'Ctrl+V', 'Ctrl+X', 'Enter', 'Delete']);

document.addEventListener('keydown', (e) => {
  if (e.defaultPrevented || e.isComposing) return;
  if (e.key === 'F10' && !e.shiftKey) {
    // F10 - фокус на строку меню, как в настольных программах Windows.
    const bar = document.querySelector('.menubar-item');
    if (bar) {
      e.preventDefault();
      bar.focus();
    }
    return;
  }
  const combo = comboOf(e);
  const entry = hotkeys.get(combo);
  if (!entry) return;
  const target = e.target;
  const editable = target instanceof HTMLElement
    && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName));
  if (editable && (TEXT_EDITING.has(combo) || (!entry.allowInInput && !/^(Ctrl|Alt|F\d)/.test(combo)))) return;
  const dialogOpen = document.querySelector('dialog[open].modal-dialog');
  if (dialogOpen && !entry.allowInDialog) return;
  if (isMenuOpen() && !combo.startsWith('Ctrl') && !combo.startsWith('Alt')) return;
  // Условие сочетания (например, Enter и Delete работают только в таблице, а не на кнопках панели).
  if (entry.when && !entry.when(e)) return;
  e.preventDefault();
  closeAllMenus();
  entry.action(e);
});
