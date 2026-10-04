/** @file Браузерные проверки общих PNG, доступности, текста пользователя и фокуса. */
import {iconText, iconUrl, spinnerArrows} from '/app/icon.js';
import {element, button, look} from '/app/dom.js';
import {Menus} from '/app/render-menu.js';
import {Popups} from '/app/render-popups.js';
import {FormWindow} from '/app/render-form.js';
import {renderStatus} from '/app/render-status.js';
import {icons} from '/app/icons.js';
import {menu} from '/app/dump.js';

/** Проверяет настоящие DOM-виджеты; возвращает измерения для проверки общих размеров в Java. */
export async function runIconRegressionProbe() {
  const previousFocus = document.activeElement;
  const host = element('section'); document.body.append(host);
  const checks = [];
  const app = {debouncers: new Set(), transport: {tab: 'icon-probe', generation: 1, current: generation => generation === 1}, send: () => {}, command: () => {}};
  const status = document.getElementById('status');
  const statusChildren = status ? [...status.childNodes] : [];
  const inspectedTooltips = [];
  // JavaFX: MenuItem -> Swing: JMenuItem -> Web: button[role=menuitem].
  const menus = Object.create(Menus.prototype); menus.app = app;
  // JavaFX: Tooltip -> Swing: JToolTip -> Web: div.tooltip.
  const popups = Object.create(Popups.prototype); popups.app = app; popups.nodes = new Map();
  try {
    const check = (name, passed) => { checks.push({name, passed: !!passed}); };
    /** Применяет предпросмотр через настоящий метод формы без её конструктора и транспорта. */
    const previewField = (windowType, fieldId, items) => {
      // JavaFX: Dialog -> Swing: SwingFormDialog -> Web: FormWindow.
      const form = Object.create(FormWindow.prototype); form.app = app; form.spec = {windowType};
      form.view = {preview: items}; form.previewIndex = 0; form.id = 'icon-probe';
      const control = element('div', 'preview'); const holder = element('div'); const label = element('label');
      holder.append(control); host.append(holder, label);
      form.applyField({spec: {kind: 'PREVIEW', id: fieldId}, control, holder, label}, {visible: true, enabled: true, readOnly: false}, null);
      return control;
    };
    /** Дожидается настоящего отложенного показа подсказки и сохраняет её PNG для проверки загрузки. */
    const shownTooltip = async (anchor, result) => {
      popups.scheduleTooltip(anchor, () => result, 0);
      const deadline = performance.now() + 3000;
      while (!popups.nodes.has('tooltip') && performance.now() < deadline) await new Promise(resolve => requestAnimationFrame(resolve));
      const node = popups.nodes.get('tooltip');
      if (node) inspectedTooltips.push(node);
      return node;
    };
    const caption = '\u2713 caption'; const tip = '\u2713 shared tooltip';
    const control = button('probe.button', caption, tip); host.append(control);
    check('button caption and tooltip accessibility', control.getAttribute('aria-label') === caption && control.getAttribute('aria-description') === tip && control.textContent === caption);
    const radio = menus.node({id: 'probe.radio', kind: 'Radio', text: caption, tooltip: tip, selected: true}, 'MENU');
    const checkbox = menus.node({id: 'probe.check', kind: 'Check', text: caption, checked: true}, 'MENU');
    host.append(radio, checkbox);
    check('radio paints dot with stable logical check', radio.querySelector('.menu-mark img').dataset.iconKey === '\u25cf' && radio.querySelector('.menu-mark').textContent === '\u2713' && radio.querySelector('button').getAttribute('aria-checked') === 'true' && radio.querySelector('button').role === 'menuitemradio');
    check('checkbox paints check', checkbox.querySelector('.menu-mark img').dataset.iconKey === '\u2713');
    check('radio and check dumps retain checked semantics', menu(radio).checked && menu(checkbox).checked && menu(radio).text === caption && menu(radio).kind === 'Radio');
    check('menu accessible name is visible caption', radio.querySelector('button').getAttribute('aria-label') === caption && radio.querySelector('button').getAttribute('aria-description') === tip);
    const filenames = ['report\u2713.md', '\u2713 report.md', 'folder', 'budget \u20bd.md', '<report>\u26a0.md'];
    for (const [index, filename] of filenames.entries()) {
      const recent = menus.node({id: 'file.recent.' + index, kind: 'Action', text: filename, tooltip: filename}, 'MENU');
      const preview = previewField('FILE_BROWSER', 'files', [{text: filename, selectable: true}]).firstElementChild; host.append(recent);
      check('recent and preview preserve user text ' + index, recent.querySelector('.menu-label').textContent === filename && !recent.querySelector('.menu-label img') && recent.querySelector('button').getAttribute('aria-label') === filename && preview.textContent === filename && !preview.querySelector('img'));
    }
    const prose = iconText(element('span'), 'user \u2713 text \u20bd 10', true); host.append(prose);
    check('embedded prose and currency preserved', !prose.querySelector('img') && prose.textContent === 'user \u2713 text \u20bd 10');
    const decorated = iconText(element('span'), '\u2713 caption \u26a0 user text', true); host.append(decorated);
    check('only explicit leading position decorated', decorated.querySelectorAll('img').length === 1 && decorated.textContent === '\u2713 caption \u26a0 user text');
    const details = button('probe.details', 'details \u25b8', ''); host.append(details);
    check('explicit details suffix uses shared image', details.querySelector('img')?.dataset.iconKey === '\u25b8' && details.textContent === 'details \u25b8');
    const currency = iconText(element('span'), '\u20bd', true); host.append(currency);
    check('currency is text', !currency.querySelector('img') && currency.textContent === '\u20bd');
    const mixedText = '\u270e user\n\u21c4 service\n\u270e note \u20bd';
    const explicit = iconText(element('span'), mixedText, [{offset: mixedText.indexOf('\u21c4'), key: '\u21c4'}]); host.append(explicit);
    check('explicit positions preserve other glyphs and currency', explicit.querySelectorAll('img').length === 1 && explicit.querySelector('img').dataset.iconKey === '\u21c4' && explicit.textContent === mixedText && explicit.getAttribute('aria-label') === mixedText);
    const invalid = iconText(element('span'), '\u270e \u20bd', [{offset: -1, key: '\u270e'}, {offset: 0, key: '\u21c4'}, {offset: 2, key: '\u20bd'}, {offset: 0.5, key: '\u270e'}, null]); host.append(invalid);
    check('invalid positions never replace text', !invalid.querySelector('img') && invalid.textContent === '\u270e \u20bd');
    const literal = iconText(element('span', 'menu-mark'), '\u2713', []); host.append(literal);
    check('empty positions preserve even a glyph-only value', literal.textContent === '\u2713' && literal.getAttribute('aria-label') === '\u2713' && !literal.querySelector('img'));
    const statusRoot = status || element('div'); if (!status) { statusRoot.id = 'status'; host.append(statusRoot); }
    const filenameStatus = 'File: \u2713 report \u2717 \u20bd.md';
    const messageStatus = '\u2713 user description\n\u270e note \u20bd';
    const sessionText = 'Snapshot: registry \u2713 10:15:30 | XML \u2717';
    renderStatus({segments: [
      {id: 'file', text: filenameStatus, tooltip: filenameStatus, color: 'TEXT_PRIMARY', visible: true},
      {id: 'message', text: messageStatus, tooltip: '', color: 'TEXT_PRIMARY', visible: true, grow: true},
      {id: 'session', text: sessionText, tooltip: '', color: 'EXPENSE', visible: true}
    ]});
    const saved = statusRoot.querySelector('[data-cp-id="session"]');
    check('status embedded save marks use colored PNGs', saved.querySelectorAll('img').length === 2 && [...saved.querySelectorAll('img')].every(image => image.getAttribute('src') === iconUrl(image.dataset.iconKey, 'EXPENSE')) && saved.textContent === sessionText && saved.getAttribute('aria-label') === sessionText);
    check('status filenames and descriptions remain text', statusRoot.querySelector('[data-cp-id="file"]').textContent === filenameStatus && statusRoot.querySelector('[data-cp-id="message"]').textContent === messageStatus && statusRoot.querySelector('[data-cp-id="message"]').style.flexGrow === '1' && !statusRoot.querySelector('[data-cp-id="file"] img, [data-cp-id="message"] img'));
    const previewText = '05.10.2026  \u21c4 03.10.2026  \u270e adjustment';
    const dates = previewField('RULE_EDITOR', 'dates', [{text: previewText, selectable: true, color: 'TEXT_PAST'}, {text: 'none', selectable: false}]);
    const dateItem = dates.firstElementChild;
    check('rule preview shift and edit marks use colored PNGs', dateItem.querySelectorAll('img').length === 2 && [...dateItem.querySelectorAll('img')].map(image => image.dataset.iconKey).join(',') === '\u21c4,\u270e' && [...dateItem.querySelectorAll('img')].every(image => image.getAttribute('src') === iconUrl(image.dataset.iconKey, 'TEXT_PAST')) && dateItem.textContent === previewText && dateItem.getAttribute('aria-label') === previewText);
    const intents = []; app.send = intent => intents.push(intent);
    dateItem.click(); dateItem.dispatchEvent(new MouseEvent('dblclick', {bubbles: true})); dates.lastElementChild.click();
    check('rule preview retains selection and activation', dateItem.getAttribute('aria-selected') === 'true' && dates.lastElementChild.getAttribute('aria-disabled') === 'true' && intents.length === 2 && intents.every(intent => intent.type === 'formPreview' && intent.index === 0) && !intents[0].activated && intents[1].activated);
    const variants = ['ACCENT', 'WHATIF', 'EXPENSE', 'INCOME', 'TEXT_PRIMARY', 'TEXT_MUTED', 'WARN', 'TOOLTIP_TEXT', 'TEXT_PAST'];
    for (const token of variants) {
      const node = iconText(element('span'), '\u2713'); look(node, {text: token}); host.append(node);
      const expected = '/app/icons/check-' + token.toLowerCase() + '.png';
      check('shared variant ' + token, icons['check-' + token.toLowerCase()] === expected && node.querySelector('img').getAttribute('src') === expected && node.textContent === '\u2713');
    }
    check('unknown color defaults to original', iconUrl('\u2713', 'UNKNOWN') === icons['\u2713']);
    check('unavailable variant defaults to original', iconUrl('check-accent', 'WARN') === icons['check-accent']);
    check('unknown glyph stays absent', iconUrl('unknown-glyph', 'ACCENT') === undefined);
    const spinner = element('div'); const input = element('input'); input.type = 'number'; input.value = '2'; input.step = '1'; input.min = '0'; input.max = '5'; spinner.append(input); spinnerArrows(input, spinner); host.append(spinner);
    const events = []; input.addEventListener('input', () => events.push('input')); input.addEventListener('change', () => events.push('change'));
    control.focus(); spinner.querySelector('button').click();
    check('spinner restores input focus and commits step', document.activeElement === input && input.value === '3' && events.join(',') === 'input,change');
    input.disabled = true; control.focus(); spinner.querySelector('button').click();
    check('disabled spinner preserves focus and value', document.activeElement === control && input.value === '3' && events.length === 2);
    input.disabled = false; input.readOnly = true; spinner.querySelector('button').click();
    check('readonly spinner preserves focus and value', document.activeElement === control && input.value === '3' && events.length === 2);
    popups.scheduleTooltip(control, () => tip, 0);
    const deadline = performance.now() + 3000;
    while (!popups.nodes.has('tooltip') && performance.now() < deadline) await new Promise(resolve => requestAnimationFrame(resolve));
    const tooltip = popups.nodes.get('tooltip');
    check('tooltip uses shared light PNG and semantic text', tooltip?.role === 'tooltip' && tooltip.textContent === tip && tooltip.querySelector('img')?.getAttribute('src') === '/app/icons/check-tooltip_text.png');
    const recentButton = host.querySelector('[data-cp-id="file.recent.1"] button');
    popups.scheduleTooltip(recentButton, () => filenames[1], 0);
    const recentDeadline = performance.now() + 3000;
    while (!popups.nodes.has('tooltip') && performance.now() < recentDeadline) await new Promise(resolve => requestAnimationFrame(resolve));
    const recentTooltip = popups.nodes.get('tooltip');
    check('recent filename tooltip remains user text', recentTooltip?.textContent === filenames[1] && !recentTooltip?.querySelector('img'));
    const header = element('div', 'table-header'); const marks = element('div', 'table-cell'); marks.dataset.cpId = 'marks'; header.append(marks); host.append(header);
    const headerText = '\u270e edited \u00b7 \u2192 moved \u00b7 \u21c4 shifted \u00b7 \u2261 once \u00b7 \u2715 skipped \u00b7 \u0394 whatif';
    const headerTooltip = await shownTooltip(marks, headerText);
    check('table header tooltip paints all service marks', headerTooltip?.textContent === headerText && headerTooltip.querySelectorAll('img').length === 6 && [...headerTooltip.querySelectorAll('img')].map(image => image.dataset.iconKey).join(',') === '\u270e,\u2192,\u21c4,\u2261,\u2715,\u0394' && [...headerTooltip.querySelectorAll('img')].every(image => image.getAttribute('src') === iconUrl(image.dataset.iconKey, 'TOOLTIP_TEXT')));
    const row = element('div', 'table-row'); const cell = element('div', 'table-cell'); row.append(cell); host.append(row);
    const title = '\ud83d\udcb0 \u270e user title\n\u21c4 user description';
    const serviceLines = ['\u270e edited', '\u2192 moved', '\u21c4 shifted', '\u2715 skipped', '\u0394 whatif'];
    const tableText = title + '\nrule\n' + serviceLines.join('\n') + '\nAmount: 10 \u20bd\nNote: \u270e user\n' + headerText;
    let serviceOffset = title.length + '\nrule\n'.length;
    const iconPositions = serviceLines.map(line => { const position = {offset: serviceOffset, key: line[0]}; serviceOffset += line.length + 1; return position; });
    const tableTooltip = await shownTooltip(cell, {text: tableText, iconPositions});
    check('multiline tooltip decorates only declared service positions', tableTooltip?.textContent === tableText && tableTooltip.getAttribute('aria-label') === tableText && tableTooltip.querySelectorAll('img').length === 5 && [...tableTooltip.querySelectorAll('img')].map(image => image.dataset.iconKey).join(',') === '\u270e,\u2192,\u21c4,\u2715,\u0394' && [...tableTooltip.querySelectorAll('img')].every(image => image.getAttribute('src') === iconUrl(image.dataset.iconKey, 'TOOLTIP_TEXT')));
    const plainTableTooltip = await shownTooltip(cell, tableText);
    check('unstructured table tooltip preserves multiline user text', plainTableTooltip?.textContent === tableText && plainTableTooltip.getAttribute('aria-label') === tableText && !plainTableTooltip.querySelector('img'));
    const form = element('div'); const alert = element('div', 'alert-window');
    form.append(element('span', 'window-glyph', '\u2139')); alert.append(element('span', 'window-glyph', '\u2139')); host.append(form, alert);
    const dimensions = node => { const rect = node.getBoundingClientRect(); return {width: rect.width, height: rect.height}; };
    const sizes = {inline: dimensions(control.querySelector('img')), form: dimensions(form.querySelector('img')), alert: dimensions(alert.querySelector('img')), spinner: dimensions(spinner.querySelector('img'))};
    const images = [...host.querySelectorAll('img'), ...(saved?.querySelectorAll('img') || []), ...(tooltip?.querySelectorAll('img') || []), ...inspectedTooltips.flatMap(node => [...node.querySelectorAll('img')])];
    await Promise.all(images.map(image => image.decode()));
    check('all shared PNGs actually loaded', images.every(image => image.complete && image.naturalWidth > 0));
    return {checks, sizes};
  } finally {
    if (status) status.replaceChildren(...statusChildren);
    popups.hide('tooltip'); host.remove();
    if (previousFocus?.isConnected) previousFocus.focus({preventScroll: true});
  }
}
