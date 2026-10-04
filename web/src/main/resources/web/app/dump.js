/** @file Дамп схемы 1 из живых DOM-виджетов, подключается только самотестом. */
import {bounds, visible, dialogContentBounds} from './dom.js';
export {dialogContentBounds} from './dom.js';
import {fieldValue, radioInputs} from './render-form.js';
import {nativeAlertGlyph} from './render-alert.js';

/** Канонические имена палитры ядра; значение читается из CSS, а не из модели. */
const palette = ['bg.window', 'bg.surface', 'bg.alt', 'text.primary', 'text.muted', 'text.past', 'border', 'border.strong', 'accent', 'accent.weak', 'income', 'expense', 'negative.bg', 'cushion.bg', 'warn', 'total.bg', 'grid', 'line.zero', 'line.cushion', 'line.goal', 'line.today', 'tooltip.bg', 'tooltip.text', 'shadow'];
let computedPalette;
let readingPalette = null;
const preparedDumps = new WeakMap();
const preparingApps = new WeakSet();

/** Прогревает реальную палитру до секции чтения, не подставляя ожидаемые цвета. */
function warmPalette() {
  const result = new Map();
  const probe = document.createElement('span'); document.body.append(probe);
  try {
    for (const name of palette) {
      probe.style.color = `var(--cp-${name.replaceAll('.', '-')})`;
      const rgb = getComputedStyle(probe).color;
      if (!result.has(rgb)) result.set(rgb, name);
    }
  } finally { probe.remove(); }
  return result;
}

/** Нормализует вычисленный браузером цвет. */
export function tokenColor(value, colors = readingPalette) {
  if (!value || value === 'rgba(0, 0, 0, 0)' || value === 'transparent') return '';
  if (colors) return colors.get(value) || value;
  computedPalette ||= warmPalette();
  return computedPalette.get(value) || value;
}

/** Читает оформление текста настоящего элемента. */
function style(node, colors = readingPalette) {
  const css = getComputedStyle(node);
  return {color: tokenColor(css.color, colors), bold: Number(css.fontWeight) >= 600, italic: css.fontStyle === 'italic', strike: css.textDecorationLine.includes('line-through')};
}

/** Находит фактически видимый фон через прозрачных предков живого элемента. */
function background(node, colors = readingPalette) {
  for (let current = node; current; current = current.parentElement) {
    const value = getComputedStyle(current).backgroundColor;
    if (value !== 'transparent' && value !== 'rgba(0, 0, 0, 0)') return tokenColor(value, colors);
  }
  return '';
}

/** Читает кнопки в визуальном порядке, исключая служебные кнопки каркаса. */
function buttons(root, selector = '.window-buttons button, .side-column button, .field-wrap > button[data-kind=BUTTON]') {
  const origin = root.matches('dialog, .quick-edit') ? bounds(root).x : 0;
  return [...root.querySelectorAll(selector)].filter(visible).map(/** Снимает идентификатор, текст, доступность и положение живой кнопки. */ node => ({id: node.dataset.cpId, text: node.textContent, tooltip: node.dataset.tooltip || '', enabled: !node.disabled, isDefault: node.classList.contains('default-button'), x: bounds(node).x - origin}));
}

/** Переводит измеренное содержимое в координаты главного DOM-содержимого, не меняя размеры. */
function relativeDialogContent(root) {
  // У popup нет заголовка ОС: рамка и padding принадлежат его настоящему содержимому.
  const content = root.classList.contains('quick-edit') ? bounds(root) : dialogContentBounds(root);
  const main = bounds(document.getElementById('main'));
  return {...content, x: content.x - main.x, y: content.y - main.y};
}

/** Измеряет базовую линию живого текста нулевым строчным маркером без подстановки размера шрифта. */
function baseline(node) {
  const marker = document.createElement('span');
  marker.style.cssText = 'display:inline-block;vertical-align:baseline;width:0;height:0;margin:0;padding:0;border:0';
  node.append(marker);
  try { const rect = node.getBoundingClientRect(); return {x: rect.x, y: marker.getBoundingClientRect().y, width: rect.width, height: 1}; }
  finally { marker.remove(); }
}

/** Читает дерево меню, включая ещё не раскрытые настоящие узлы. */
export function menu(node) {
  const control = node.querySelector(':scope > button');
  const input = node.querySelector(':scope > input');
  const panel = node.querySelector(':scope > .menu-panel');
  return {id: node.dataset.cpId, kind: node.dataset.kind,
    text: node.querySelector(':scope > button > .menu-label')?.textContent || (node.dataset.kind === 'Slider' ? '' : node.querySelector(':scope > .menu-label')?.textContent || ''),
    accel: node.querySelector(':scope > button > .menu-accel')?.textContent || '',
    enabled: control ? !control.disabled : input ? !input.disabled : false,
    checked: control?.getAttribute('aria-checked') === 'true', group: node.dataset.group || '',
    value: input?.value || '', valueLabel: node.dataset.kind === 'Slider' ? node.querySelector(':scope > .menu-label')?.textContent || '' : '',
    tooltip: control?.dataset.tooltip || input?.dataset.tooltip || '',
    children: panel ? [...panel.children].map(menu) : []};
}

/** Читает одну форму из её полей, подписей и реально открытого каркаса. */
function windowDump(form) {
  const root = form.node;
  const fields = [];
  for (const [id, entry] of form.fields) {
    const {control, holder, label} = entry;
    if (!(entry.peers || [entry]).some(/** Проверяет видимость хотя бы одного представления составного поля. */ peer => visible(peer.holder))) continue;
    // Визуальный разделитель подписи не входит в семантическую модель поля, как в Swing и JavaFX.
    fields.push({id, kind: control.dataset.kind, label: entry.checkLabel?.textContent || label.textContent.replace(/:$/, ''),
      text: control.dataset.kind === 'PREVIEW' ? '' : ['CHOICE', 'LIST'].includes(control.dataset.kind) ? control.selectedOptions[0]?.textContent || '' : fieldValue(control), prompt: control.placeholder || '', tooltip: control.dataset.tooltip || '', suffix: holder.querySelector('.suffix')?.textContent || '',
      enabled: !control.disabled && control.getAttribute('aria-disabled') !== 'true', visible: visible(holder), readOnly: !!control.readOnly || control.getAttribute('aria-readonly') === 'true',
      options: control.dataset.kind === 'PREVIEW' ? [...control.querySelectorAll('.preview-item')].map(/** Читает текст варианта предварительного просмотра. */ item => item.textContent) : control.dataset.kind === 'RADIO' ? radioInputs(control).map(/** Читает подпись переключателя у его родительского элемента. */ input => input.parentElement.textContent) : [...(holder.querySelectorAll('option').length ? holder.querySelectorAll('option') : control.querySelectorAll('label'))].map(/** Читает текст варианта выбора или подписи поля. */ option => option.textContent)});
  }
  const details = root.querySelector('.details-text');
  return {id: root.dataset.cpId, type: root.dataset.kind, purpose: root.dataset.purpose, title: root.querySelector('.window-title-text').textContent,
    header: root.querySelector('.window-header-text').textContent, glyph: root.querySelector('.window-glyph').textContent,
    modal: root.matches(':modal'), ownerId: root.dataset.ownerId, page: Number(root.dataset.page), bounds: relativeDialogContent(root),
    sections: [...root.querySelectorAll('.section, .side-caption')].map(/** Читает заголовок секции или боковой колонки формы. */ n => n.textContent), hints: [...root.querySelectorAll('.hint')].map(/** Читает текст подсказки формы. */ n => n.textContent), fields,
    preview: [...root.querySelectorAll('.preview-item')].map(/** Читает текст элемента предварительного просмотра формы. */ n => n.textContent), previewSelected: Number(root.querySelector('.preview-item[aria-selected=true]')?.dataset.previewIndex ?? -1),
    results: [...root.querySelectorAll('.result-line')].map(/** Снимает текст и вычисленный цвет строки результата. */ n => ({text: n.textContent, color: style(n).color})), problem: root.querySelector('.problem').textContent,
    buttons: buttons(root), details: details?.value || '', detailsLink: root.querySelector('.details-link')?.textContent || '', detailsExpanded: details ? !details.hidden : false};
}

/** Читает AlertSpec из открытого сообщения, без подстановки модели. */
function alertDump(alert) {
  const root = alert.node; const details = root.querySelector('.details-text');
  const glyphNode = root.querySelector('.window-glyph');
  const visibleGlyph = glyphNode && visible(glyphNode) && getComputedStyle(glyphNode).visibility === 'visible' ? glyphNode.textContent : '';
  const glyph = root.dataset.glyphMode === 'native'
    ? visibleGlyph && visibleGlyph === nativeAlertGlyph(root.dataset.kind) ? '' : visibleGlyph || 'missing-native-icon:' + root.dataset.kind
    : visibleGlyph;
  return {id: root.dataset.cpId, purpose: root.dataset.purpose, kind: root.dataset.kind, windowTitle: root.querySelector('.window-title-text').textContent,
    glyph, minWidth: Number(root.dataset.minWidth), header: root.querySelector('.window-header-text').textContent,
    content: root.querySelector('.alert-content').textContent, details: details?.value || '', detailsLink: root.querySelector('.details-link')?.textContent || '', detailsExpanded: details ? !details.hidden : false, buttons: buttons(root)};
}

/** Читает текст и оформление строки после настоящего рендеринга виджета. */
function rowDump(node, columns, colors = readingPalette) {
  const cells = []; const styles = {};
  for (const column of columns) {
    const cell = [...node.children].find(/** Находит ячейку строки по идентификатору текущего столбца. */ n => n.dataset.cpId === column.dataset.cpId);
    cells.push(cell?.textContent || '');
    styles[column.dataset.cpId] = style(cell || node, colors);
  }
  return {index: Number(node.dataset.index), rowId: node.dataset.cpId, kind: node.dataset.kind, cells, background: background(node, colors), styles};
}

/** Прокручивает настоящую таблицу и хеширует тексты реально показанных строк. */
async function tableDump(app, samples = null, verify = null, colors = null) {
  const table = app.table; const columns = [...table.header.children]; const count = Number(table.root.getAttribute('aria-rowcount'));
  const tableHidden = table.root.hidden; const chartHidden = app.chart.root.hidden;
  if (tableHidden) { table.root.hidden = false; app.chart.root.hidden = true; }
  const savedTop = table.scroll.scrollTop;
  const rows = []; const chunks = []; let length = 0; let selectedRowId = '';
  try {
    let index = 0;
    while (index < count) {
      table.scroll.scrollTop = index * 26; await table.paint(); verify?.();
      const viewport = table.scroll.getBoundingClientRect();
      const nodes = [...table.canvas.children].filter(/** Оставляет строки с нужным индексом, целиком помещающиеся в области прокрутки. */ node => {
        const rect = node.getBoundingClientRect(); return Number(node.dataset.index) >= index && rect.top >= viewport.top && rect.bottom <= viewport.bottom;
      });
      if (!nodes.length) throw new Error(`No visible row ${index}`);
      for (const node of nodes) {
        const row = rowDump(node, columns, colors);
        if (samples) samples.set(row.index, JSON.stringify(row.cells));
        const selected = node.getAttribute('aria-selected') === 'true' && visible(node);
        if (selected) selectedRowId = node.dataset.cpId;
        if (row.index !== index) throw new Error(`Visible row gap ${index}`);
        const bytes = new TextEncoder().encode(JSON.stringify(row.cells)); const size = new Uint8Array(4); new DataView(size.buffer).setUint32(0, bytes.length);
        chunks.push(size, bytes); length += 4 + bytes.length;
        if (index < 60 || selected) rows.push(row);
        index++;
      }
    }
  } finally { table.scroll.scrollTop = savedTop; await table.paint(); table.root.hidden = tableHidden; app.chart.root.hidden = chartHidden; }
  verify?.();
  const data = new Uint8Array(length); let offset = 0;
  for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.length; }
  const hash = await crypto.subtle.digest('SHA-256', data);
  verify?.();
  return {columns: columns.map(/** Читает текст заголовка столбца таблицы. */ n => n.textContent), rowCount: count, rows, rowsDigest: [...new Uint8Array(hash)].map(/** Преобразует байт хеша в две шестнадцатеричные цифры. */ byte => byte.toString(16).padStart(2, '0')).join(''),
    placeholder: table.root.querySelector('.placeholder-text')?.textContent || '', placeholderButtons: buttons(table.root, '.placeholder-buttons button'), selectedRowId};
}

/** Читает отрисованную SVG-сцену графика. */
function chartDump(app) {
  const root = app.chart.root; const plot = app.chart.scene?.plot;
  const xLabels = [], yLabels = [], lineLabels = [];
  for (const label of root.querySelectorAll('g:first-child > text[data-kind=Label]')) {
    if (plot && Number(label.getAttribute('y')) > plot.plotY + plot.plotHeight) xLabels.push(label.textContent);
    else if (plot && Number(label.getAttribute('x')) < plot.plotX) yLabels.push(label.textContent);
    else lineLabels.push(label.textContent);
  }
  return {legend: [...root.querySelectorAll('.chart-legend text')].map(/** Читает текст элемента легенды графика. */ n => n.textContent), xLabels, yLabels, lineLabels,
    markerCount: root.querySelectorAll('[data-kind=Circle]').length, barCount: root.querySelectorAll('[data-kind=Box]').length, notice: root.querySelector('.chart-empty')?.textContent || ''};
}

/** Читает часть старого дампа, которая исторически предшествует обходу всей таблицы. */
function readDumpStart(app, step, scenario, readBaseline = baseline) {
  const mainVisible = visible(document.getElementById('main'));
  const regionIds = ['menuBar', 'toolbar', 'summary', 'table.header', 'center', 'status']; const regions = {};
  for (const id of regionIds) { const node = id === 'table.header' ? app.table.header : document.getElementById(id); if (visible(node)) regions[id] = bounds(node); }
  const toolbar = document.getElementById('toolbar');
  const toolbarText = [...toolbar.querySelectorAll('.toolbar-label')].find(visible);
  const statusText = [...document.querySelectorAll('.status-segment')].find(/** Находит видимый непустой сегмент статуса для измерения базовой линии. */ node => visible(node) && node.textContent);
  if (toolbarText) regions['toolbar.baseline'] = readBaseline(toolbarText);
  if (statusText) regions['status.baseline'] = readBaseline(statusText);
  for (const column of app.table.header.children) if (visible(column)) regions['table.column.' + column.dataset.cpId] = bounds(column);
  const firstRect = toolbar.querySelector('.toolbar-node')?.getBoundingClientRect();
  const firstCenterY = firstRect ? firstRect.y + firstRect.height / 2 : 0;
  const items = [...toolbar.children].map(/** Снимает свойства, геометрию и вложенные пункты элемента панели инструментов. */ node => {
    const control = node.querySelector(':scope > button, :scope > input'); const css = control ? style(control) : {color: '', bold: false};
    const rect = node.getBoundingClientRect();
    return {id: node.dataset.cpId, kind: node.dataset.kind, text: control?.matches('input') ? control.value : control?.querySelector('.toolbar-label')?.textContent ?? control?.textContent ?? '', prompt: control?.placeholder || '', tooltip: control?.dataset.tooltip || '', enabled: !control?.disabled, selected: control?.getAttribute('aria-pressed') === 'true', color: css.color, bold: css.bold,
      row: rect.y + rect.height / 2 > firstCenterY + 10 ? 1 : 0, bounds: bounds(node), items: [...(node.querySelector(':scope > .menu-panel')?.children || [])].map(menu)};
  });
  const summary = document.getElementById('summary');
  const screens = document.getElementById('screens');
  const result = {schema: 1, client: 'web', scenario, step,
    frame: mainVisible ? {title: document.title, titleBar: 'tab', minSize: null, contentWidth: innerWidth, contentHeight: innerHeight, regions} : null,
    menuBar: [...document.getElementById('menuBar').children].map(menu), toolbar: {wrap: items.some(/** Проверяет перенос элемента панели инструментов на следующую строку. */ item => item.row > 0), items},
    summary: {visible: !summary.hidden, cards: [...summary.querySelectorAll('.card')].map(/** Снимает тексты, цвета, подсказку и границы карточки сводки. */ node => ({id: node.dataset.cpId, title: node.querySelector('.card-title').textContent, value: node.querySelector('.card-value').textContent, valueColor: style(node.querySelector('.card-value')).color,
      caption: node.querySelector('.card-caption').textContent, captionColor: style(node.querySelector('.card-caption')).color, tooltip: node.dataset.tooltip || '', bounds: bounds(node)})), unavailableText: summary.querySelector('.summary-unavailable')?.textContent || ''}};
  return {mainVisible, result, screens};
}

/** Читает часть старого дампа после таблицы, сохраняя порядок свойств схемы 1. */
function readDumpEnd(app, start, table) {
  const {mainVisible, result, screens} = start;
  result.table = table;
  Object.assign(result, {chart: mainVisible ? chartDump(app) : null, status: [...document.querySelectorAll('.status-segment')].filter(/** Включает сегменты статуса только при видимости главного экрана. */ node => mainVisible).map(/** Снимает текст, подсказку, цвет и видимость сегмента статуса. */ node => ({id: node.dataset.cpId, text: node.textContent, tooltip: node.dataset.tooltip || '', color: style(node).color, visible: !node.hidden})),
    contextMenus: [...document.querySelectorAll('.context-menu')].filter(visible).map(/** Снимает цель и дерево пунктов видимого контекстного меню. */ node => ({target: node.dataset.target, items: [...node.children].map(menu)})),
    windows: [...app.windows.values()].filter(/** Оставляет только фактически показанные формы. */ form => form.showing()).map(windowDump), alerts: [...app.alerts.values()].filter(/** Оставляет только открытые окна сообщений. */ alert => alert.node.open).map(alertDump),
    popups: [...document.querySelectorAll('[data-popup-kind]')].filter(visible).map(/** Снимает вид, строки текста и границы видимого всплывающего элемента. */ node => ({kind: node.dataset.popupKind, lines: node.innerText.split('\n'), bounds: bounds(node)})),
    screens: screens.hidden ? [] : [{kind: screens.dataset.kind, title: screens.querySelector('.screen-title').textContent, text: screens.querySelector('.screen-text').textContent, buttons: buttons(screens, 'button')}],
    chooserRequests: [...app.chooserRequests.values()].map(/** Копирует свойства сохранённого запроса обозревателя файлов. */ request => ({...request})), classCensus: {}, counters: {}});
  if (!mainVisible) { result.menuBar = []; result.toolbar = null; result.summary = null; }
  // Чтение состояния не закрывает меню: следующий PNG должен показать те же живые виджеты.
  return result;
}

/** Возвращает прежний обычный дамп с тем же порядком чтения до/после async-обхода таблицы. */
export async function dump(app, step, scenario = '') {
  const start = readDumpStart(app, step, scenario);
  return readDumpEnd(app, start, start.mainVisible ? await tableDump(app) : null);
}

/** Проверяет техническое условие подготовленного чтения, не создавая пользовательских сообщений. */
function requirePrepared(ok, message) { if (!ok) throw new Error(message); }

/** Копирует всю frozen идентичность PaintCaptureRequest, включая дедлайн и намерения, без эталонных данных. */
function dumpIdentity(request) {
  const keys = ['runId', 'captureId', 'commandNumber', 'scenario', 'step', 'attempt', 'planSha256', 'cardStates', 'deadlineNanos'];
  requirePrepared(request && Object.keys(request).sort().join() === keys.sort().join(), 'Exact dump request keys');
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  requirePrepared(uuid.test(request.runId) && uuid.test(request.captureId) && /^[0-9a-f]{64}$/.test(request.planSha256), 'Dump capture identity');
  requirePrepared(Number.isInteger(request.commandNumber) && request.commandNumber >= 0 && request.commandNumber <= 2147483647
    && Number.isInteger(request.attempt) && request.attempt > 0 && request.attempt <= 2147483647, 'Dump command/attempt');
  for (const name of [request.scenario, request.step]) requirePrepared(typeof name === 'string' && /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/.test(name)
    && !name.endsWith('.') && !/^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)/i.test(name), 'Dump safe component');
  requirePrepared(request.cardStates && typeof request.cardStates === 'object' && !Array.isArray(request.cardStates)
    && Object.keys(request.cardStates).length <= 32, 'Dump card intentions');
  const cardStates = Object.create(null); let active = 0;
  for (const name of Object.keys(request.cardStates).sort()) {
    const state = request.cardStates[name]; requirePrepared(name.trim() && name.length <= 4096 && ['NORMAL', 'HOVER', 'FOCUS'].includes(state), 'Dump card intention');
    cardStates[name] = state; if (state !== 'NORMAL') active++;
  }
  requirePrepared(active <= 1 && typeof request.deadlineNanos === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(request.deadlineNanos)
    && BigInt(request.deadlineNanos) <= 9223372036854775807n, 'Dump deadline');
  const {runId, captureId, commandNumber, scenario, step, attempt, planSha256, deadlineNanos} = request;
  return Object.freeze({runId, captureId, commandNumber, scenario, step, attempt, planSha256, cardStates: Object.freeze(cardStates), deadlineNanos});
}

/** Читает поколения только как защиту актуальности; содержимое моделей не попадает в raw. */
function dumpGeneration(app) {
  const table = app.table;
  const values = [app.transport.generation, app.transport.seq, table.epoch, table.generation, table.model?.revision ?? null];
  for (const value of values) requirePrepared(value === null || Number.isSafeInteger(value) && value >= 0, 'Dump generation counters');
  return {values, transport: app.transport, table, model: table.model, main: document.getElementById('main'),
    root: table.root, header: table.header, canvas: table.canvas, scroll: table.scroll, chart: app.chart.root};
}

/** Отклоняет замену приложения/таблицы/модели, ABA поколений и незавершённое намерение. */
function verifyGeneration(app, saved) {
  const current = dumpGeneration(app);
  for (const key of ['transport', 'table', 'model', 'main', 'root', 'header', 'canvas', 'scroll', 'chart'])
    requirePrepared(current[key] === saved[key], 'Stale dump object: ' + key);
  requirePrepared(JSON.stringify(current.values) === JSON.stringify(saved.values) && !app.transport.stopped && app.transport.connected
    && !app.resyncing && app.transport.pending === 0, 'Stale or unsettled dump generation');
}

/** Сохраняет вычисленное оформление и геометрию baseline без вставки маркера. */
function baselineSignature(node) {
  const css = getComputedStyle(node), properties = [];
  for (const property of css) properties.push([property, css.getPropertyValue(property)]);
  return JSON.stringify([node.textContent, bounds(node), properties, document.fonts?.status]);
}

/** Читает CSSOM/палитру, от которых зависят подготовленные невидимые строки и baseline. */
function dumpCssSignature() {
  const values = [innerWidth, innerHeight, devicePixelRatio, document.fonts?.status];
  const css = getComputedStyle(document.body);
  for (const name of palette) values.push([name, css.getPropertyValue('--cp-' + name.replaceAll('.', '-'))]);
  let ruleCount = 0;
  /** Читает фактическое дерево CSSOM, не делая новые style-записи. */
  function rules(list, depth = 0) {
    requirePrepared(depth <= 32, 'Unsupported CSSOM nesting');
    return [...list].map(/** Сохраняет правило вместе с действующим media-условием и вложенными правилами. */ rule => {
      requirePrepared(++ruleCount <= 100000, 'Unsupported CSSOM size');
      return [rule.cssText, rule.media ? matchMedia(rule.media.mediaText).matches : null,
        rule.cssRules ? rules(rule.cssRules, depth + 1) : null, rule.styleSheet ? rules(rule.styleSheet.cssRules, depth + 1) : null];
    });
  }
  try {
    for (const sheet of [...document.styleSheets, ...(document.adoptedStyleSheets || [])]) values.push([sheet.href, sheet.disabled, sheet.media.mediaText,
      sheet.media.mediaText ? matchMedia(sheet.media.mediaText).matches : null, rules(sheet.cssRules)]);
  } catch (cause) { throw new Error('Unsupported dump CSSOM inspection', {cause}); }
  return JSON.stringify(values);
}

/** Проверяет deadline на монотонных часах вкладки, а не Java nanoTime другого процесса. */
function verifyDumpDeadline(identity) {
  requirePrepared(BigInt(identity.deadlineNanos) > BigInt(Math.floor(performance.now() * 1000000)), 'Prepared dump deadline expired');
}

/**
 * Готовит full-table digest/первые 60 строк и выделенную строку из реального обхода DOM.
 * Здесь разрешены paint/запросы страниц, прокрутка, хеширование, палитра и baseline-маркеры.
 * /api/test/counters остаётся в test-driver: MAIN должен завершить его запрос до read.
 * После подготовки контекст одноразовый. MAIN обязан вызвать discardPreparedDump в finally,
 * если observer отменён до read, иначе наблюдатель таблицы останется подключённым.
 * Невидимые строки являются результатом подготовительного обхода той же ревизии, не новой
 * съёмкой. Смена CSS/таблицы/поколений их инвалидирует; произвольные скрытые изменения
 * серверных строк без новой ревизии этим клиентом недоказуемы и требуют серверного контракта.
 * CSS-сверка здесь проверяет только конечные значения, не является complete CSSOM journal:
 * CSS/font ABA и внешние селекторы невидимых строк требуют отдельного строгого наблюдения.
 * MAIN завершает fonts/image/layout до подготовки; последующие изменения baseline/CSS
 * отклоняются, а не исправляются записью DOM внутри секции read.
 */
export async function prepareDump(app, request) {
  const identity = dumpIdentity(request); verifyDumpDeadline(identity);
  requirePrepared(!preparingApps.has(app), 'Concurrent dump preparation'); preparingApps.add(app);
  let observer = null;
  try {
    const generation = dumpGeneration(app); verifyGeneration(app, generation);
    requirePrepared(document.fonts?.status !== 'loading', 'Unsettled dump fonts');
    const mainVisible = visible(generation.main), cssBefore = dumpCssSignature(), tableHidden = app.table.root.hidden, chartHidden = app.chart.root.hidden;
    const samples = new Map(), paletteSnapshot = warmPalette();
    const table = mainVisible ? await tableDump(app, samples, /** Проверяет актуальность после каждой асинхронной границы полного обхода таблицы. */ () => verifyGeneration(app, generation), paletteSnapshot) : null;
    verifyGeneration(app, generation); verifyDumpDeadline(identity);
    const baselines = new Map();
    const toolbarText = [...document.getElementById('toolbar').querySelectorAll('.toolbar-label')].find(visible);
    const statusText = [...document.querySelectorAll('.status-segment')].find(/** Находит реальный baseline статуса для подготовительного измерения. */ node => visible(node) && node.textContent);
    for (const node of [toolbarText, statusText]) if (node) baselines.set(node, {value: baseline(node), signature: baselineSignature(node)});
    const css = dumpCssSignature(); requirePrepared(css === cssBefore, 'CSS changed during dump preparation');
    requirePrepared(visible(generation.main) === mainVisible && app.table.root.hidden === tableHidden && app.chart.root.hidden === chartHidden, 'Dump visibility changed');
    const changes = {dirty: false};
    observer = new MutationObserver(/** Помнит любые изменения таблицы после подготовки, включая возврат прежнего текста/выделения. */ records => { if (records.length) changes.dirty = true; });
    observer.observe(app.table.root, {subtree: true, childList: true, attributes: true, characterData: true});
    const prepared = Object.freeze({identity, generation: generation.values[0], sequence: generation.values[1], tableEpoch: generation.values[2]});
    const viewport = tableViewport(app.table);
    preparedDumps.set(prepared, {app, identity, generation, mainVisible, tableHidden, chartHidden, table, samples, paletteSnapshot, baselines, css, observer, changes, viewport});
    return prepared;
  } catch (error) { observer?.disconnect(); throw error; }
  finally { preparingApps.delete(app); }
}

/** Освобождает неиспользованный контекст; повторная отмена безопасна. */
export function discardPreparedDump(prepared) {
  const context = preparedDumps.get(prepared); if (!context) return;
  context.observer.disconnect(); preparedDumps.delete(prepared);
}

/** Снимает положение и размеры восстановленной области, не меняя прокрутку. */
function tableViewport(table) {
  return JSON.stringify([table.scroll.scrollTop, table.scroll.scrollLeft, table.header.scrollLeft,
    table.scroll.clientWidth, table.scroll.clientHeight, bounds(table.scroll)]);
}

/** Читает живой canvas и сверяет его тексты с подготовленным digest той же ревизии. */
function readPreparedTable(app, context) {
  if (!context.table) return null;
  const table = app.table, columns = [...table.header.children], result = structuredClone(context.table);
  requirePrepared(Number(table.root.getAttribute('aria-rowcount')) === result.rowCount, 'Prepared row count changed');
  const currentColumns = columns.map(/** Читает заголовки из текущего DOM, не из модели таблицы. */ node => node.textContent);
  requirePrepared(JSON.stringify(currentColumns) === JSON.stringify(result.columns), 'Prepared columns changed');
  const seen = new Set();
  for (const node of table.canvas.children) {
    const row = rowDump(node, columns);
    requirePrepared(!seen.has(row.index) && context.samples.get(row.index) === JSON.stringify(row.cells), 'Live canvas differs from prepared table digest'); seen.add(row.index);
    const index = result.rows.findIndex(/** Находит архивную строку, представленную текущим живым canvas. */ saved => saved.index === row.index);
    if (index >= 0) {
      requirePrepared(result.rows[index].rowId === row.rowId && result.rows[index].kind === row.kind, 'Prepared row identity changed');
      result.rows[index] = row;
    }
    if (node.getAttribute('aria-selected') === 'true') requirePrepared(row.rowId === result.selectedRowId, 'Live selection differs from prepared table');
  }
  requirePrepared((table.root.dataset.selectedRowId || '') === result.selectedRowId, 'Prepared selection changed');
  result.columns = currentColumns;
  result.placeholder = table.root.querySelector('.placeholder-text')?.textContent || '';
  result.placeholderButtons = buttons(table.root, '.placeholder-buttons button');
  return result;
}

/**
 * Синхронно читает actual DOM схемы 1 без сети, Promise, прокрутки и DOM-записей.
 * Подготовленный digest/невидимые строки сохраняют происхождение предыдущего обхода;
 * текущие canvas-строки и все прочие виджеты читаются сейчас. stale не маскируется обновлением.
 * Контекст потребляется и освобождается при любом исходе read.
 */
export function readPreparedDump(app, request, prepared) {
  const context = preparedDumps.get(prepared); requirePrepared(context, 'Unknown prepared dump context');
  try {
    requirePrepared(context.app === app, 'Prepared dump application mismatch');
    requirePrepared(JSON.stringify(dumpIdentity(request)) === JSON.stringify(context.identity), 'Prepared dump request mismatch');
    verifyGeneration(app, context.generation); verifyDumpDeadline(context.identity);
    requirePrepared(!context.changes.dirty && context.observer.takeRecords().length === 0, 'Prepared table was mutated');
    requirePrepared(tableViewport(app.table) === context.viewport && app.table.paintFrame === null && app.table.loads.size === 0, 'Prepared table viewport unsettled');
    requirePrepared(dumpCssSignature() === context.css && visible(context.generation.main) === context.mainVisible
      && app.table.root.hidden === context.tableHidden && app.chart.root.hidden === context.chartHidden, 'Prepared dump CSS/visibility changed');
    requirePrepared(!readingPalette, 'Reentrant prepared dump read'); readingPalette = context.paletteSnapshot;
    try {
      const readBaseline = /** Возвращает измеренный baseline только для того же живого узла и оформления. */ node => {
        const saved = context.baselines.get(node);
        requirePrepared(saved && saved.signature === baselineSignature(node), 'Prepared baseline changed'); return {...saved.value};
      };
      const start = readDumpStart(app, context.identity.step, context.identity.scenario, readBaseline);
      const result = readDumpEnd(app, start, readPreparedTable(app, context));
      verifyGeneration(app, context.generation); verifyDumpDeadline(context.identity);
      requirePrepared(!context.changes.dirty && context.observer.takeRecords().length === 0, 'Mutation during prepared dump read');
      return result;
    } finally { readingPalette = null; }
  } finally { discardPreparedDump(prepared); }
}
