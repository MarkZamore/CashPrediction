/** @file Явный тестовый порт: реальные щелчки, ввод, клавиши и DOM-дамп. */
import {dump} from './dump.js';
import {radioInputs, setFieldValue} from './render-form.js';
import {visible, frame} from './dom.js';

/** Ожидает готовности интерфейса после очередей запросов и debounce. */
async function idle(app, timeoutMs = 5000) {
  const deadline = performance.now() + timeoutMs;
  let stable = 0;
  while (performance.now() < deadline) {
    await app.transport.tail; await app.transport.effectTail;
    await app.testDriver?.consumeChoice();
    const busy = app.transport.pending || app.resyncing || [...app.debouncers].some(fn => fn.pending());
    stable = busy ? 0 : stable + 1;
    if (stable >= 3) { await frame(); return {ok: true}; }
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error('UI idle timeout');
}

/** Находит единственный живой виджет с идентификатором. */
function byId(id, root = document) {
  const node = [...root.querySelectorAll('[data-cp-id]')].find(n => n.dataset.cpId === id);
  if (!node) throw new Error(`Widget missing: ${id}`);
  return node;
}

/** Открывает реальных предков пункта меню и нажимает его кнопку. */
async function clickMenu(app, id) {
  const node = [...document.querySelectorAll('.menu-node')].find(n => n.dataset.cpId === id);
  if (!node) throw new Error(`Menu missing: ${id}`);
  const ancestors = []; let parent = node.parentElement;
  while (parent) { if (parent.classList.contains('menu-panel')) ancestors.unshift(parent); parent = parent.parentElement; }
  for (const panel of ancestors) { panel.parentElement.querySelector(':scope > button')?.click(); await frame(); }
  const control = node.querySelector(':scope > button');
  if (!control || control.disabled) throw new Error(`Menu unavailable: ${id}`);
  control.click(); await idle(app);
}

/** Находит открытое окно по точному заголовку, назначению или id. */
function windowFor(app, name) {
  const windows = [...app.windows.values()];
  const form = windows.find(window => window.id === name || window.spec.windowTitle === name || window.spec.purpose === name || window.spec.windowType === name) || (!name || name === 'last' ? windows.at(-1) : null);
  if (!form) throw new Error(`Window missing: ${name}`);
  return form;
}

/** Находит поле по id или точной видимой подписи. */
function fieldFor(form, label) {
  const entry = form.fields.get(label) || [...form.fields.values()].flatMap(field => field.peers || [field]).find(field => field.label.textContent === label || field.checkLabel?.textContent === label);
  if (!entry) throw new Error(`Field missing: ${label}`);
  return entry;
}

/** Вводит значение через события настоящего поля. */
function input(entry, text) {
  const node = entry.control;
  if (node.disabled || node.readOnly) throw new Error(`Field unavailable: ${node.dataset.cpId}`);
  node.focus();
  if (node.dataset.kind === 'RADIO') {
    const radio = radioInputs(node).find(n => n.value === text || n.parentElement.textContent === text);
    if (!radio) throw new Error(`Option missing: ${text}`);
    if (radio.disabled || !visible(radio) || radio.closest('[inert]')) throw new Error(`Option unavailable: ${text}`);
    radio.focus();
    radio.click();
  } else {
    let value = text;
    if (node.tagName === 'SELECT') value = [...node.options].find(option => option.textContent === text || option.value === text)?.value ?? text;
    setFieldValue(node, value);
    node.dispatchEvent(new Event('input', {bubbles: true})); node.dispatchEvent(new Event('change', {bubbles: true}));
  }
}

/** Завершает ввод через событие виджета, учитывая отсутствие blur у неактивной headless-страницы. */
function finishInput(entry) {
  const node = document.activeElement && entry.control.contains(document.activeElement) ? document.activeElement : entry.control;
  let delivered = false;
  const observed = () => { delivered = true; };
  entry.control.addEventListener('blur', observed, true);
  node.blur();
  entry.control.removeEventListener('blur', observed, true);
  if (!delivered) entry.control.dispatchEvent(new FocusEvent('blur'));
  node.focus();
}

/** Генерирует физическую клавишу через тот же диспетчер DOM. */
function key(chord) {
  let code = chord.key;
  if (/^[A-Z]$/.test(code)) code = 'Key' + code;
  else if (code.startsWith('DIGIT')) code = 'Digit' + code.slice(5);
  else code = {ENTER: 'Enter', ESCAPE: 'Escape', SPACE: 'Space', DELETE: 'Delete', CONTEXT_MENU: 'ContextMenu', ALT: 'AltLeft'}[code] || code;
  const russian = [1092, 1080, 1089, 1074, 1091, 1072, 1087, 1088, 1096, 1086, 1083, 1076, 1100, 1090, 1097, 1079, 1081, 1082, 1099, 1077, 1075, 1084, 1094, 1095, 1085, 1103];
  let label = code.startsWith('Key') ? String.fromCharCode(russian[code.charCodeAt(3) - 65]) : code.startsWith('Digit') ? code.slice(5) : code === 'Space' ? ' ' : code === 'AltLeft' ? 'Alt' : code;
  if (chord.shift && code.startsWith('Key')) label = label.toUpperCase();
  document.activeElement.dispatchEvent(new KeyboardEvent('keydown', {code, key: label, ctrlKey: !!chord.ctrl, altKey: code === 'AltLeft' || !!chord.alt, shiftKey: !!chord.shift, bubbles: true, cancelable: true}));
  document.activeElement.dispatchEvent(new KeyboardEvent('keyup', {code, key: label, ctrlKey: !!chord.ctrl, altKey: code === 'AltLeft' ? false : !!chord.alt, shiftKey: !!chord.shift, bubbles: true, cancelable: true}));
}

/** Подключает только реализованные действия; неподдерживаемые действия явно падают. */
export function installTestApi(app) {
  const supported = ['Wait', 'Size', 'Sample', 'Menu', 'Click', 'Key', 'View', 'Period', 'Filter', 'FilterType', 'Select', 'DoubleClick', 'RowClick', 'QuickEdit', 'Field', 'Fill', 'Button', 'Answer', 'Ok', 'Cancel', 'Context', 'Hover', 'SliderSet', 'SpinnerSet', 'ListPick', 'FieldEnter', 'Chooser', 'Save', 'Snapshot', 'Exit', 'Dump'];
  const steps = []; let lastStep = -1; let hovered = null; let pendingChoice; let choiceWork;
  /** Сериализует потребление ответа при параллельном журнале и ожидании сборщика. */
  function consumeChoice() {
    if (choiceWork) return choiceWork;
    if (pendingChoice === undefined || ![...app.windows.values()].some(window => window.spec.presentation === 'FILE_BROWSER' && window.showing())) return Promise.resolve();
    choiceWork = applyChoice().finally(() => { choiceWork = null; });
    return choiceWork;
  }
  /** Потребляет один заранее заданный ответ только через открытый обозреватель файлов. */
  async function applyChoice() {
    const form = [...app.windows.values()].find(window => window.spec.presentation === 'FILE_BROWSER' && window.showing());
    if (pendingChoice === undefined || !form) return;
    const choice = pendingChoice; pendingChoice = undefined;
    if (choice.path == null) {
      const cancel = form.node.querySelector('button[data-role=CANCEL]:enabled');
      if (!cancel) throw new Error('Chooser cancel unavailable');
      cancel.click();
    } else {
      const path = String(choice.path); const split = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
      const folder = split >= 0 ? path.slice(0, split + 1) : form.fields.get('path').control.value;
      const entry = form.fields.get('path'); input(entry, folder); finishInput(entry);
      await app.transport.tail; await app.transport.effectTail;
      const name = form.fields.get('name');
      const target = name || form.fields.get('value');
      if (!target) throw new Error('Chooser path field unavailable');
      input(target, name ? path.slice(split + 1) : path); finishInput(target);
      await app.transport.tail; await app.transport.effectTail;
      const ok = form.node.querySelector('.default-button:enabled');
      if (!ok) throw new Error('Chooser confirmation unavailable');
      ok.click();
    }
    await app.transport.tail; await app.transport.effectTail;
  }
  const api = {
    supported,
    /** Исполняет одну команду через реальный виджет. */
    async execute({kind, args = {}}) {
      if (!supported.includes(kind)) throw new Error(`Unsupported widget operation: ${kind}`);
      if (!['Wait', 'Dump'].includes(kind) && hovered) {
        hovered.dispatchEvent(new PointerEvent('pointerleave', {bubbles: false}));
        hovered.dispatchEvent(new PointerEvent('pointerout', {bubbles: true}));
        hovered = null;
      }
      switch (kind) {
        case 'Wait': await new Promise(resolve => setTimeout(resolve, args.millis)); break;
        case 'Size': {
          if (innerWidth !== args.width || innerHeight !== args.height) window.resizeTo(args.width + outerWidth - innerWidth, args.height + outerHeight - innerHeight);
          await frame();
          if (innerWidth !== args.width || innerHeight !== args.height) throw new Error('Browser viewport resize requires collector support'); break;
        }
        case 'Sample': await clickMenu(app, 'file.sample'); break;
        case 'Menu': await clickMenu(app, args.idOrPath); break;
        case 'Click': {
          const [toolbarId, menuId] = args.toolbarId.split('.menu:');
          const wrap = byId(toolbarId);
          if (menuId) {
            const arrow = wrap.querySelector('[data-cp-id$=".arrow"]') || wrap.querySelector(':scope > button');
            arrow.click(); await frame();
            const item = byId(menuId, wrap).querySelector('button'); if (!item || item.disabled) throw new Error('Toolbar menu unavailable'); item.click(); break;
          }
          const control = wrap.tagName === 'BUTTON' ? wrap : wrap.querySelector(':scope > button');
          if (!control || control.disabled) throw new Error(`Toolbar unavailable: ${args.toolbarId}`); control.click(); break;
        }
        case 'Key': key(args.chord); break;
        case 'View': await clickMenu(app, args.mode === 'CHART' ? 'view.chart' : 'view.table'); break;
        case 'Period': await clickMenu(app, 'view.period.' + args.period); break;
        case 'Filter': {
          const item = byId('view.flag.' + args.key); const control = item.querySelector(':scope > button');
          if ((control.getAttribute('aria-checked') === 'true') !== args.value) await clickMenu(app, item.dataset.cpId); break;
        }
        case 'FilterType': app.filter.focus(); app.filter.value = args.text; app.filter.dispatchEvent(new Event('input', {bubbles: true})); break;
        case 'Select': case 'DoubleClick': case 'RowClick': case 'QuickEdit': {
          await app.table.ensureVisible(args.rowId);
          const row = byId(args.rowId, app.table.canvas);
          const columnId = args.columnId || (kind === 'QuickEdit' ? 'income' : '');
          const cell = columnId ? byId(columnId, row) : row.querySelector('[role=gridcell]');
          if (!cell) throw new Error(`Row cell unavailable: ${args.rowId}`);
          cell.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true}));
          if (kind === 'DoubleClick' || kind === 'QuickEdit') {
            await idle(app);
            const liveRow = byId(args.rowId, app.table.canvas);
            const liveCell = columnId ? byId(columnId, liveRow) : liveRow.querySelector('[role=gridcell]');
            liveCell.dispatchEvent(new MouseEvent('dblclick', {bubbles: true, cancelable: true}));
          }
          if (kind === 'QuickEdit') { await idle(app); const form = windowFor(app, 'QUICK_EDIT_POPUP'); input(fieldFor(form, 'amount'), args.amount); } break;
        }
        case 'Field': { const entry = fieldFor(windowFor(app, args.windowTitle), args.label); input(entry, args.text); finishInput(entry); break; }
        case 'Fill': { const form = windowFor(app, args.window); for (const [id, text] of Object.entries(args.values)) { const entry = fieldFor(form, id); input(entry, text); finishInput(entry); } break; }
        case 'Button': case 'Ok': case 'Cancel': {
          const form = windowFor(app, args.windowTitle || args.window);
          const control = [...form.node.querySelectorAll('button')].find(node => kind === 'Ok' ? node.classList.contains('default-button') : kind === 'Cancel' ? node.dataset.role === 'CANCEL' : node.textContent === args.label);
          if (!control || control.disabled || !visible(control)) throw new Error('Form button unavailable'); control.click(); break;
        }
        case 'Answer': {
          const alert = [...app.alerts.values()].at(-1);
          const controls = [...(alert?.node.querySelectorAll('.window-buttons button') || [])];
          let control = controls.find(node => node.textContent === args.buttonText);
          // Разрешённые различия web: серверное хранилище и одна кнопка второго экземпляра.
          if (!control && alert?.spec.purpose === 'crashRecovery') control = controls.find(node => node.dataset.role === 'OTHER' && !node.disabled);
          if (!control && alert?.spec.purpose === 'alreadyRunning' && controls.length === 1) control = controls[0];
          if (!control || control.disabled) throw new Error('Alert button unavailable'); control.click(); break;
        }
        case 'Context': case 'Hover': {
          const [type, ...rest] = args.target.split(':'); const id = rest.join(':'); let node;
          if (type === 'row' || type === 'total' || type === 'pastHeader') {
            const rowId = id || (await app.table.page(0)).find(row => row.kind === 'PAST_HEADER')?.rowId;
            if (!rowId) throw new Error('Past header unavailable');
            await app.table.ensureVisible(rowId); node = byId(rowId, app.table.canvas);
          }
          else if (type === 'card') node = byId(id, document.getElementById('summary'));
          else if (type === 'menu') { await clickMenuAncestors(id); node = byId(id).querySelector('button'); }
          else if (type === 'chart') node = app.chart.root;
          else if (type === 'preview') {
            const [window, index] = rest; const form = windowFor(app, window);
            node = form.node.querySelector(`[data-preview-index="${Number(index)}"]`);
            if (!node) throw new Error('Preview item unavailable');
          }
          else throw new Error(`Unsupported target: ${args.target}`);
          const rect = node.getBoundingClientRect();
          const coordinates = type === 'chart' ? id.split(',').map(Number) : [rect.width / 2, rect.height / 2];
          node.dispatchEvent(kind === 'Context' ? new MouseEvent('contextmenu', {bubbles: true, cancelable: true, clientX: rect.x + coordinates[0], clientY: rect.y + coordinates[1]}) : new PointerEvent(type === 'chart' ? 'pointermove' : 'pointerenter', {bubbles: false, clientX: rect.x + coordinates[0], clientY: rect.y + coordinates[1]}));
          if (kind === 'Hover') { hovered = node; node.dispatchEvent(new PointerEvent('pointerover', {bubbles: true})); await new Promise(resolve => setTimeout(resolve, 700)); } break;
        }
        case 'SliderSet': case 'SpinnerSet': {
          await clickMenuAncestors(args.itemId); const control = byId(args.itemId).querySelector('input'); if (!control) throw new Error('Menu input missing');
          control.focus(); control.value = args.value; control.dispatchEvent(new Event('input', {bubbles: true})); control.dispatchEvent(new Event('change', {bubbles: true}));
          if (kind === 'SpinnerSet') {
            await idle(app);
            await new Promise(resolve => setTimeout(resolve, Number(control.dataset.applyDelayMs)));
          }
          break;
        }
        case 'ListPick': {
          const form = windowFor(app, args.windowTitle); const entry = fieldFor(form, args.label); input(entry, args.itemText);
          if (args.activate) entry.control.dispatchEvent(new MouseEvent('dblclick', {bubbles: true})); break;
        }
        case 'FieldEnter': fieldFor(windowFor(app, args.windowTitle), args.label).control.focus(); key({key: 'ENTER'}); break;
        case 'Chooser': {
          if (pendingChoice !== undefined) throw new Error('Chooser answer already pending');
          pendingChoice = {path: args.path ?? null}; await consumeChoice(); break;
        }
        case 'Save': await clickMenu(app, 'file.save'); break;
        case 'Snapshot': await clickMenu(app, 'recovery.snapshotNow'); break;
        case 'Exit': await clickMenu(app, 'file.exit'); break;
        case 'Dump': break;
        default: throw new Error(`Unsupported widget operation: ${kind}`);
      }
      await idle(app); return {ok: true};
    },
    /** Ждёт отложенной обработки реальных событий. */
    awaitIdle({timeoutMs = 5000} = {}) { return idle(app, timeoutMs); },
    /** Читает настоящие виджеты, не вызывая ModelDump. */
    async dump({step}) {
      await idle(app);
      const snapshot = await app.transport.request('/api/test/counters');
      const counters = snapshot.counters;
      if (!counters || typeof counters !== 'object' || Array.isArray(counters) || Object.values(counters).some(value => !Number.isSafeInteger(value) || value < 0)) throw new Error('Invalid controller counters snapshot');
      const value = await dump(app, step); value.counters = counters;
      return {ok: true, value};
    },
    /** Отдаёт следующий шаг единственному внешнему сборщику. */
    takeStep() { return {ok: true, value: app.resyncing ? null : steps.shift() || null}; }
  };
  /** Раскрывает предков без выбора самого пункта. */
  async function clickMenuAncestors(id) {
    const node = byId(id); const panels = []; let parent = node.parentElement;
    while (parent) { if (parent.classList.contains('menu-panel')) panels.unshift(parent); parent = parent.parentElement; }
    for (const panel of panels) { panel.parentElement.querySelector(':scope > button')?.click(); await frame(); }
  }
  window.cpParityTestApi = api;
  return {
    /** Потребляет ожидающий выбор после завершения очереди реальных эффектов. */
    consumeChoice,
    /** Планирует выбор после применения открытия, не блокируя очередь эффектов своим намерением. */
    chooserOpened() { setTimeout(() => consumeChoice().catch(error => app.clientError(error)), 0); },
    /** Сохраняет упорядоченный шаг без исполнения и без отправки результатов. */
    step(effect) {
      if (!Number.isInteger(effect.n) || effect.n <= lastStep) throw new Error('Test step order');
      lastStep = effect.n; steps.push({type: effect.type, n: effect.n, command: effect.command});
    }
  };
}
