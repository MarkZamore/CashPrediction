/** @file Дамп схемы 1 из живых DOM-виджетов, подключается только самотестом. */
import {bounds, visible, dialogContentBounds} from './dom.js';
export {dialogContentBounds} from './dom.js';
import {fieldValue, radioInputs} from './render-form.js';
import {nativeAlertGlyph} from './render-alert.js';

/** Канонические имена палитры ядра; значение читается из CSS, а не из модели. */
const palette = ['bg.window', 'bg.surface', 'bg.alt', 'text.primary', 'text.muted', 'text.past', 'border', 'border.strong', 'accent', 'accent.weak', 'income', 'expense', 'negative.bg', 'cushion.bg', 'warn', 'total.bg', 'grid', 'line.zero', 'line.cushion', 'line.goal', 'line.today', 'tooltip.bg', 'tooltip.text', 'shadow'];
let computedPalette;

/** Нормализует вычисленный браузером цвет. */
export function tokenColor(value) {
  if (!value || value === 'rgba(0, 0, 0, 0)' || value === 'transparent') return '';
  if (!computedPalette) {
    computedPalette = new Map();
    const probe = document.createElement('span'); document.body.append(probe);
    for (const name of palette) {
      probe.style.color = `var(--cp-${name.replaceAll('.', '-')})`;
      const rgb = getComputedStyle(probe).color;
      if (!computedPalette.has(rgb)) computedPalette.set(rgb, name);
    }
    probe.remove();
  }
  return computedPalette.get(value) || value;
}

/** Читает оформление текста настоящего элемента. */
function style(node) {
  const css = getComputedStyle(node);
  return {color: tokenColor(css.color), bold: Number(css.fontWeight) >= 600, italic: css.fontStyle === 'italic', strike: css.textDecorationLine.includes('line-through')};
}

/** Находит фактически видимый фон через прозрачных предков живого элемента. */
function background(node) {
  for (let current = node; current; current = current.parentElement) {
    const value = getComputedStyle(current).backgroundColor;
    if (value !== 'transparent' && value !== 'rgba(0, 0, 0, 0)') return tokenColor(value);
  }
  return '';
}

/** Читает кнопки в визуальном порядке, исключая служебные кнопки каркаса. */
function buttons(root, selector = '.window-buttons button, .side-column button, .field-wrap > button[data-kind=BUTTON]') {
  const origin = root.matches('dialog, .quick-edit') ? bounds(root).x : 0;
  return [...root.querySelectorAll(selector)].filter(visible).map(node => ({id: node.dataset.cpId, text: node.textContent, tooltip: node.dataset.tooltip || '', enabled: !node.disabled, isDefault: node.classList.contains('default-button'), x: bounds(node).x - origin}));
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
    if (!(entry.peers || [entry]).some(peer => visible(peer.holder))) continue;
    // Визуальный разделитель подписи не входит в семантическую модель поля, как в Swing и JavaFX.
    fields.push({id, kind: control.dataset.kind, label: entry.checkLabel?.textContent || label.textContent.replace(/:$/, ''),
      text: control.dataset.kind === 'PREVIEW' ? '' : ['CHOICE', 'LIST'].includes(control.dataset.kind) ? control.selectedOptions[0]?.textContent || '' : fieldValue(control), prompt: control.placeholder || '', tooltip: control.dataset.tooltip || '', suffix: holder.querySelector('.suffix')?.textContent || '',
      enabled: !control.disabled && control.getAttribute('aria-disabled') !== 'true', visible: visible(holder), readOnly: !!control.readOnly || control.getAttribute('aria-readonly') === 'true',
      options: control.dataset.kind === 'PREVIEW' ? [...control.querySelectorAll('.preview-item')].map(item => item.textContent) : control.dataset.kind === 'RADIO' ? radioInputs(control).map(input => input.parentElement.textContent) : [...(holder.querySelectorAll('option').length ? holder.querySelectorAll('option') : control.querySelectorAll('label'))].map(option => option.textContent)});
  }
  const details = root.querySelector('.details-text');
  return {id: root.dataset.cpId, type: root.dataset.kind, purpose: root.dataset.purpose, title: root.querySelector('.window-title-text').textContent,
    header: root.querySelector('.window-header-text').textContent, glyph: root.querySelector('.window-glyph').textContent,
    modal: root.matches(':modal'), ownerId: root.dataset.ownerId, page: Number(root.dataset.page), bounds: relativeDialogContent(root),
    sections: [...root.querySelectorAll('.section, .side-caption')].map(n => n.textContent), hints: [...root.querySelectorAll('.hint')].map(n => n.textContent), fields,
    preview: [...root.querySelectorAll('.preview-item')].map(n => n.textContent), previewSelected: Number(root.querySelector('.preview-item[aria-selected=true]')?.dataset.previewIndex ?? -1),
    results: [...root.querySelectorAll('.result-line')].map(n => ({text: n.textContent, color: style(n).color})), problem: root.querySelector('.problem').textContent,
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
function rowDump(node, columns) {
  const cells = []; const styles = {};
  for (const column of columns) {
    const cell = [...node.children].find(n => n.dataset.cpId === column.dataset.cpId);
    cells.push(cell?.textContent || '');
    styles[column.dataset.cpId] = style(cell || node);
  }
  return {index: Number(node.dataset.index), rowId: node.dataset.cpId, kind: node.dataset.kind, cells, background: background(node), styles};
}

/** Прокручивает настоящую таблицу и хеширует тексты реально показанных строк. */
async function tableDump(app) {
  const table = app.table; const columns = [...table.header.children]; const count = Number(table.root.getAttribute('aria-rowcount'));
  const tableHidden = table.root.hidden; const chartHidden = app.chart.root.hidden;
  if (tableHidden) { table.root.hidden = false; app.chart.root.hidden = true; }
  const savedTop = table.scroll.scrollTop;
  const rows = []; const chunks = []; let length = 0; let selectedRowId = '';
  try {
    let index = 0;
    while (index < count) {
      table.scroll.scrollTop = index * 26; await table.paint();
      const viewport = table.scroll.getBoundingClientRect();
      const nodes = [...table.canvas.children].filter(node => {
        const rect = node.getBoundingClientRect(); return Number(node.dataset.index) >= index && rect.top >= viewport.top && rect.bottom <= viewport.bottom;
      });
      if (!nodes.length) throw new Error(`No visible row ${index}`);
      for (const node of nodes) {
        const row = rowDump(node, columns);
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
  const data = new Uint8Array(length); let offset = 0;
  for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.length; }
  const hash = await crypto.subtle.digest('SHA-256', data);
  return {columns: columns.map(n => n.textContent), rowCount: count, rows, rowsDigest: [...new Uint8Array(hash)].map(byte => byte.toString(16).padStart(2, '0')).join(''),
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
  return {legend: [...root.querySelectorAll('.chart-legend text')].map(n => n.textContent), xLabels, yLabels, lineLabels,
    markerCount: root.querySelectorAll('[data-kind=Circle]').length, barCount: root.querySelectorAll('[data-kind=Box]').length, notice: root.querySelector('.chart-empty')?.textContent || ''};
}

/** Возвращает дерево UiDump только из настоящих элементов страницы. */
export async function dump(app, step, scenario = '') {
  const mainVisible = visible(document.getElementById('main'));
  const regionIds = ['menuBar', 'toolbar', 'summary', 'table.header', 'center', 'status']; const regions = {};
  for (const id of regionIds) { const node = id === 'table.header' ? app.table.header : document.getElementById(id); if (visible(node)) regions[id] = bounds(node); }
  const toolbar = document.getElementById('toolbar');
  const toolbarText = [...toolbar.querySelectorAll('.toolbar-label')].find(visible);
  const statusText = [...document.querySelectorAll('.status-segment')].find(node => visible(node) && node.textContent);
  if (toolbarText) regions['toolbar.baseline'] = baseline(toolbarText);
  if (statusText) regions['status.baseline'] = baseline(statusText);
  for (const column of app.table.header.children) if (visible(column)) regions['table.column.' + column.dataset.cpId] = bounds(column);
  const firstRect = toolbar.querySelector('.toolbar-node')?.getBoundingClientRect();
  const firstCenterY = firstRect ? firstRect.y + firstRect.height / 2 : 0;
  const items = [...toolbar.children].map(node => {
    const control = node.querySelector(':scope > button, :scope > input'); const css = control ? style(control) : {color: '', bold: false};
    const rect = node.getBoundingClientRect();
    return {id: node.dataset.cpId, kind: node.dataset.kind, text: control?.matches('input') ? control.value : control?.querySelector('.toolbar-label')?.textContent ?? control?.textContent ?? '', prompt: control?.placeholder || '', tooltip: control?.dataset.tooltip || '', enabled: !control?.disabled, selected: control?.getAttribute('aria-pressed') === 'true', color: css.color, bold: css.bold,
      row: rect.y + rect.height / 2 > firstCenterY + 10 ? 1 : 0, bounds: bounds(node), items: [...(node.querySelector(':scope > .menu-panel')?.children || [])].map(menu)};
  });
  const summary = document.getElementById('summary');
  const screens = document.getElementById('screens');
  const result = {schema: 1, client: 'web', scenario, step,
    frame: mainVisible ? {title: document.title, titleBar: 'tab', minSize: null, contentWidth: innerWidth, contentHeight: innerHeight, regions} : null,
    menuBar: [...document.getElementById('menuBar').children].map(menu), toolbar: {wrap: items.some(item => item.row > 0), items},
    summary: {visible: !summary.hidden, cards: [...summary.querySelectorAll('.card')].map(node => ({id: node.dataset.cpId, title: node.querySelector('.card-title').textContent, value: node.querySelector('.card-value').textContent, valueColor: style(node.querySelector('.card-value')).color,
      caption: node.querySelector('.card-caption').textContent, captionColor: style(node.querySelector('.card-caption')).color, tooltip: node.dataset.tooltip || '', bounds: bounds(node)})), unavailableText: summary.querySelector('.summary-unavailable')?.textContent || ''},
    table: mainVisible ? await tableDump(app) : null, chart: mainVisible ? chartDump(app) : null, status: [...document.querySelectorAll('.status-segment')].filter(node => mainVisible).map(node => ({id: node.dataset.cpId, text: node.textContent, tooltip: node.dataset.tooltip || '', color: style(node).color, visible: !node.hidden})),
    contextMenus: [...document.querySelectorAll('.context-menu')].filter(visible).map(node => ({target: node.dataset.target, items: [...node.children].map(menu)})),
    windows: [...app.windows.values()].filter(form => form.showing()).map(windowDump), alerts: [...app.alerts.values()].filter(alert => alert.node.open).map(alertDump),
    popups: [...document.querySelectorAll('[data-popup-kind]')].filter(visible).map(node => ({kind: node.dataset.popupKind, lines: node.innerText.split('\n'), bounds: bounds(node)})),
    screens: screens.hidden ? [] : [{kind: screens.dataset.kind, title: screens.querySelector('.screen-title').textContent, text: screens.querySelector('.screen-text').textContent, buttons: buttons(screens, 'button')}],
    chooserRequests: [...app.chooserRequests.values()].map(request => ({...request})), classCensus: {}, counters: {}};
  if (!mainVisible) { result.menuBar = []; result.toolbar = null; result.summary = null; }
  // Чтение состояния не закрывает меню: следующий PNG должен показать те же живые виджеты.
  return result;
}
