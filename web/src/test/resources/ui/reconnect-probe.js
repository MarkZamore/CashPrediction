/** @file Реальная HTTP-проверка транспорта без подмены fetch, токена или ключа клиента. */
import {Transport} from '/app/transport.js';
import {Table} from '/app/render-table.js';
import {Chart} from '/app/render-chart.js';
import {Menus} from '/app/render-menu.js';
import {Popups} from '/app/render-popups.js';
import {FormWindow} from '/app/render-form.js';
import {showScreen, hideScreen} from '/app/screens.js';

/** Проверяет условие без вывода секретных данных. */
function check(value, message) { if (!value) throw new Error(message); passed++; }
let passed = 0;
const formShowEvidence = [];
/** Управляет только отдельным HTTP-сервером фикстуры. */
async function control(mode) {
  return fetch('/fixture/control', {method: 'POST', body: mode}).then(response => response.json());
}
/** Ожидает отказ Promise, не сохраняя серверные данные в результате. */
async function rejected(work) { try { await work; return false; } catch { return true; } }
/** Ограничивает ожидание локального барьера, чтобы поломка не зависала до таймаута CDP. */
async function bounded(work, reason, timeoutMs = 2000) {
  let timer;
  try {
    return await Promise.race([work, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(reason)), timeoutMs); })]);
  } finally { clearTimeout(timer); }
}
/** Создаёт транспорт, который получает секрет исключительно штатным bootstrap. */
function transport() {
  const evidence = {snapshots: [], effects: [], offline: 0};
  const value = new Transport(effect => evidence.effects.push(effect.seq), () => evidence.offline++, async () => {
    const snapshot = await value.bootstrap(); evidence.snapshots.push(snapshot.seq);
  });
  return {value, evidence};
}

/** Измеряет реальные границы и inline-размещение узла, не сравнивая ожидаемые координаты. */
function geometry(node) {
  const {x, y, width, height} = node.getBoundingClientRect();
  return JSON.stringify({x, y, width, height, position: node.style.position, left: node.style.left, top: node.style.top, margin: node.style.margin});
}

/** Проверяет DOM-фокус, выделение, геометрию и отсутствие подтверждения в устаревшем show(). */
async function staleFormShow(t, app, sent) {
  for (const popup of [false, true]) {
    const node = document.createElement(popup ? 'div' : 'dialog');
    Object.assign(node.style, {position: 'fixed', margin: '0', left: '37px', top: '43px', width: '180px', height: '60px'});
    if (popup) node.classList.add('quick-edit');
    const input = document.createElement('input'); input.value = 'fixture selection'; input.dataset.focusFirst = 'true'; node.append(input);
    const sink = document.createElement('button'); sink.textContent = 'fixture focus';
    document.body.append(node, sink);
    const form = Object.create(FormWindow.prototype);
    Object.assign(form, {app, generation: t.generation, popup, spec: {modal: false}, node, id: 'fixture', page: 0, restoredBounds: false, ownerId: 'main'});
    const before = sent.length;
    const showing = form.show();
    // Синхронное show() уже выполнено: проверяется только продолжение после кадра.
    input.setSelectionRange(2, 4); sink.focus();
    check(document.activeElement === sink, 'fixture focus baseline exists');
    const originalGeometry = geometry(node);
    t.lost(new Error('fixture loss during animation frame'));
    await bounded(showing, 'animation frame');
    const evidence = {kind: popup ? 'popup' : 'dialog', focusPreserved: document.activeElement === sink,
      selectionPreserved: input.selectionStart === 2 && input.selectionEnd === 4, boundsPreserved: geometry(node) === originalGeometry,
      shownCount: sent.slice(before).filter(intent => intent.type === 'formShown').length,
      boundsCount: sent.slice(before).filter(intent => intent.type === 'formBounds').length, totalSent: sent.length - before};
    check(evidence.focusPreserved, 'obsolete show cannot steal actual DOM focus');
    check(evidence.selectionPreserved, 'obsolete show cannot select the input contents');
    check(evidence.boundsPreserved, 'obsolete show cannot reposition or resize actual DOM');
    check(evidence.shownCount === 0 && evidence.boundsCount === 0 && evidence.totalSent === 0, 'obsolete show cannot confirm session window or send bounds');
    formShowEvidence.push(evidence);
    if (!popup) node.close(); node.remove(); sink.remove();
    await bounded(t.recover(), 'form show reconnect', 5000);
  }
}

/** Проверяет продолжения очереди формы и навигации таблицы после смены поколения, без сетевой подмены. */
async function queuedContinuations(t, app, sent) {
  for (const change of ['generation', 'page']) {
    const dialog = document.createElement('dialog'); document.body.append(dialog); dialog.show();
    const form = Object.create(FormWindow.prototype);
    Object.assign(form, {app, generation: t.generation, popup: false, node: dialog, id: 'fixture', page: 0, fields: new Map()});
    const previousTail = t.tail; let releaseTail;
    t.tail = new Promise(resolve => { releaseTail = resolve; });
    const button = form.formButton({id: 'ok', text: 'fixture', role: 'OK', tooltip: ''}); dialog.append(button);
    const list = document.createElement('select'); list.dataset.cpId = 'list'; list.dataset.kind = 'LIST'; dialog.append(list);
    form.fields.set('list', {spec: {kind: 'LIST'}, control: list, commit: {pending: () => false}});
    const before = sent.length; button.click();
    const key = form.key({code: 'Enter', target: list, preventDefault() {}, stopPropagation() {}});
    if (change === 'generation') t.lost(new Error('fixture form queue disconnect')); else form.page = 1;
    releaseTail(); await bounded(key, 'form key continuation'); await new Promise(resolve => setTimeout(resolve, 20)); t.tail = previousTail;
    check(sent.length === before, 'form button and list Enter must not cross ' + change + ' boundary');
    dialog.close(); dialog.remove(); if (!t.connected) await bounded(t.recover(), 'form queue reconnect', 5000);
  }
  for (const method of ['ensureVisible', 'reveal', 'navigate']) {
    const table = Object.create(Table.prototype);
    let paints = 0, rows = 0;
    Object.assign(table, {app, epoch: 0, model: {selectedRowId: 'fixture', rowCount: 2},
      scroll: {scrollTop: 1000, clientHeight: 10}, indexOf: async () => 0,
      paint: async () => { paints++; }, row: async () => { rows++; return {rowId: 'fixture'}; }});
    const before = sent.length;
    const work = method === 'ensureVisible' ? table.ensureVisible('fixture') : method === 'reveal' ? table.reveal('fixture', true)
      : table.navigate({code: 'ArrowDown', preventDefault() {}, stopPropagation() {}});
    t.lost(new Error('fixture table continuation disconnect')); await bounded(work, 'table continuation');
    check(paints === 0 && rows === 0 && table.scroll.scrollTop === 1000 && sent.length === before, 'table ' + method + ' cancelled after await');
    await bounded(t.recover(), 'table continuation reconnect', 5000);
  }
}

/** Задерживает только доставку уже полученного настоящего HTTP-ответа, не подменяя fetch или данные. */
async function deliveredContinuations(t, app, anchor) {
  for (const kind of ['menu', 'chart', 'hover', 'calendar']) {
    const originalQuery = t.query;
    let announce, release;
    const arrived = new Promise(resolve => { announce = resolve; });
    const gate = new Promise(resolve => { release = resolve; });
    const oldScene = {plot: {plotX: 0, plotY: 0, plotWidth: 400, plotHeight: 100}};
    app.chart.model = {revision: 7}; app.chart.generation = t.generation; app.chart.scene = oldScene;
    t.query = async function(query) {
      const response = await originalQuery.call(this, query); announce(); await gate; return response;
    };
    try {
      const work = kind === 'menu' ? app.menus.context({kind: 'row', rowId: 'fixture'}, 0, 0)
        : kind === 'chart' ? app.chart.update({revision: 7})
        : kind === 'hover' ? app.chart.hover({clientX: 1, clientY: 1})
        : app.popups.calendar(anchor, null, () => { throw new Error('Obsolete calendar action'); });
      await bounded(arrived, 'HTTP response delivery barrier');
      t.lost(new Error('fixture loss after HTTP response')); release();
      await bounded(work, 'renderer response continuation');
      check(!document.querySelector('.context-menu') && !app.popups.nodes.has('calendar') && !document.querySelector('[data-popup-kind=calendar]') && !document.querySelector('.chart-hover')
        && app.chart.scene === oldScene, 'already delivered ' + kind + ' response must not mutate DOM');
    } finally { release(); t.query = originalQuery; }
    await bounded(t.recover(), 'delivery barrier reconnect', 5000);
  }
  app.chart.scene = undefined;
}

/** Проверяет реальные продолжения рендереров и повторную загрузку одинаковой ревизии по HTTP. */
async function renderers() {
  const {value: t} = transport(); await t.bootstrap();
  const sent = [];
  const app = {transport: t, hotkeys: [], debouncers: new Set(), texts: {}, send: intent => { sent.push(intent); return Promise.resolve(); }, resync: () => t.bootstrap()};
  app.popups = new Popups(app); app.chart = new Chart(app); app.menus = new Menus(app);
  const table = new Table(app);
  const model = {revision: 7, rowCount: 1, columns: [], selectedRowId: '', scrollToRowId: ''};
  await table.update(model); const oldRow = (await table.page(0))[0].rowId;
  table.lastReveal = 'old'; await control('disconnect');
  check(await rejected(t.request('/api/ui/events?after=' + t.seq)), 'renderer generation transition');
  await t.recover(); await table.update({...model});
  check((await table.page(0))[0].rowId !== oldRow && table.lastReveal === null, 'same revision reloads pages and reveal marker');

  await staleFormShow(t, app, sent);
  await queuedContinuations(t, app, sent);

  const anchor = document.createElement('button'); document.body.append(anchor);
  let releaseText; let textStarted;
  const textReady = new Promise(resolve => { textStarted = resolve; });
  const delayedText = new Promise(resolve => { releaseText = resolve; });
  app.popups.scheduleTooltip(anchor, () => { textStarted(); return delayedText; }, 0);
  await bounded(textReady, 'tooltip request barrier'); t.lost(new Error('fixture loss during tooltip'));
  releaseText('fixture'); await new Promise(resolve => setTimeout(resolve, 20));
  check(!app.popups.nodes.has('tooltip') && !document.querySelector('[data-popup-kind=tooltip]'), 'late tooltip has no actual DOM node'); await t.recover();

  // Таймер спарклайна не имеет права даже начать запрос после смены поколения.
  const beforeSparkline = await control('inspect');
  app.popups.sparkline(anchor, 'fixture'); anchor.dispatchEvent(new PointerEvent('pointerenter'));
  t.lost(new Error('fixture loss before sparkline timer'));
  await new Promise(resolve => setTimeout(resolve, 400));
  check((await control('inspect')).queries === beforeSparkline.queries && !app.popups.nodes.has('sparkline')
    && !document.querySelector('[data-popup-kind=sparkline]'), 'old sparkline timer makes no request or DOM node');
  await t.recover();
  await deliveredContinuations(t, app, anchor);

  for (const kind of ['menu', 'chart', 'calendar']) {
    await control('slow-query');
    const work = kind === 'menu' ? app.menus.context({kind: 'row', rowId: 'fixture'}, 0, 0)
      : kind === 'chart' ? app.chart.update({revision: 7}) : app.popups.calendar(anchor, null, () => sent.push('calendar'));
    const outcome = rejected(work);
    for (let attempt = 0; attempt < 100 && !(await control('inspect')).queryStarted; attempt++) await new Promise(resolve => setTimeout(resolve, 10));
    check((await control('inspect')).queryStarted, 'renderer request barrier');
    await control('disconnect'); check(await rejected(t.request('/api/ui/events?after=' + t.seq)), 'renderer old token rejected');
    await outcome;
    check(!document.querySelector('.context-menu') && !app.popups.nodes.has('calendar') && !document.querySelector('[data-popup-kind=calendar]') && !app.chart.scene, 'late renderer response not painted');
    await t.recover();
  }
  table.resize.disconnect(); anchor.remove(); t.stop();
}

/** Выполняет проверки против настоящего HTTP и настоящего WebCrypto браузера. */
export async function run() {
  passed = 0;
  formShowEvidence.length = 0;
  const {value: t, evidence} = transport();
  await t.bootstrap(); check(t.seq === 900, 'initial cursor');
  await control('uncertain');
  const first = t.intent({type: 'fixture', id: 'uncertain'});
  const queued = t.intent({type: 'fixture', id: 'queued'});
  check(await rejected(first), 'uncertain intent must reject');
  check(await rejected(queued), 'queued intent must reject');
  const before = await control('restart');
  const a = t.recover(); const b = t.recover(); const c = t.recover();
  check(a === b && b === c, 'single flight identity'); await a;
  const after = await control('inspect');
  check(after.intents === 1 && after.challenges - before.challenges === 1 && after.completions - before.completions === 1, 'no replay and one handshake');
  check(t.seq === 0 && evidence.snapshots.at(-1) === 0, 'old cursor replaced by bootstrap');
  await t.request('/api/ui/events?after=' + t.seq);

  await control('slow-query');
  const query = t.query({type: 'fixture'});
  const queryRejected = rejected(query);
  // Барьер сервера доказывает, что запрос действительно начался до смены поколения.
  for (let attempt = 0; attempt < 100; attempt++) {
    if ((await control('inspect')).queryStarted) break;
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  check((await control('inspect')).queryStarted, 'query admission barrier');
  await control('disconnect');
  check(await rejected(t.intent({type: 'fixture', id: 'expired-token'})), 'expired token loses generation');
  check(await queryRejected, 'stale query rejected');
  await control('normal'); await t.recover();

  for (const mode of ['wrong-proof', 'wrong-origin-proof', 'wrong-nonce', 'redirect']) {
    const start = await control(mode);
    check(await rejected(t.authenticate(t.generation)), 'untrusted server must reject');
    const end = await control('inspect');
    check(end.completions === start.completions && end.traps === start.traps, 'no client proof or redirect leak');
  }
  // Даже сервер с действительным старым токеном не получает bootstrap после неверного доказательства.
  await control('wrong-proof');
  const credentialBefore = JSON.stringify(t.credential);
  t.lost(new Error('fixture manual retry disconnect'));
  const retryBefore = await control('inspect');
  check(await rejected(t.bootstrap()), 'offline bootstrap cannot bypass server proof');
  check((await control('inspect')).bootstraps === retryBefore.bootstraps && !t.connected
    && JSON.stringify(t.credential) === credentialBefore, 'untrusted bootstrap cannot replace reconnect credential');
  let directResync = 0;
  const retryApp = {transport: t, texts: {'offline.retry': 'fixture retry'}, resync: () => { directResync++; return t.bootstrap(); }};
  showScreen(retryApp, 'offline', 'fixture offline', 'fixture disconnected', true);
  document.querySelector('#screens button').click();
  for (let attempt = 0; attempt < 100; attempt++) {
    if ((await control('inspect')).challenges > retryBefore.challenges + 1) break;
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  const retryAfter = await control('inspect');
  check(directResync === 0 && retryAfter.challenges > retryBefore.challenges + 1
    && retryAfter.completions === retryBefore.completions && retryAfter.bootstraps === retryBefore.bootstraps,
    'manual retry joins authenticated recovery without sending bootstrap');
  check(document.getElementById('screens').open && !t.connected
    && JSON.stringify(t.credential) === credentialBefore, 'manual retry keeps offline screen and original key');
  await control('normal'); await bounded(t.recover(), 'manual retry legitimate recovery', 5000); hideScreen();
  check(t.connected, 'manual retry recovers after legitimate server returns');
  await control('normal');
  await control('disconnect');
  check(await rejected(t.request('/api/ui/events?after=' + t.seq)), 'old token must fail');
  const generation = t.generation;
  const staleEffect = t.receive({effects: [{seq: t.seq + 1}]}, generation - 1);
  await staleEffect; check(evidence.effects.length === 0, 'stale effects ignored');
  await t.recover();
  let releaseEffect; let enteredEffect;
  const entered = new Promise(resolve => { enteredEffect = resolve; });
  const released = new Promise(resolve => { releaseEffect = resolve; });
  t.apply = async () => { enteredEffect(); await released; };
  const cursor = t.seq;
  const inFlightEffect = t.receive({effects: [{seq: cursor + 1}]}); await entered;
  await control('disconnect');
  check(await rejected(t.request('/api/ui/events?after=' + cursor)), 'disconnect during effect');
  releaseEffect(); await inFlightEffect;
  check(t.seq === cursor, 'old async effect cannot advance cursor');
  await t.recover(); t.stop();
  const terminalBefore = await control('inspect'); await t.recover();
  check((await control('inspect')).challenges === terminalBefore.challenges, 'terminal transport does not reconnect');

  // Ошибка первого bootstrap не препятствует началу штатного цикла восстановления.
  await control('fail-bootstrap');
  const startup = transport();
  check(await rejected(startup.value.bootstrap()), 'failed initial bootstrap');
  await control('normal');
  const polling = startup.value.poll();
  for (let attempt = 0; attempt < 100 && startup.evidence.snapshots.length === 0; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  check(startup.evidence.snapshots.length === 1, 'startup recovery bootstrap');
  startup.value.stop(); await polling;

  // Повреждённый сохранённый ключ не препятствует нормальному первоначальному UI.
  sessionStorage.setItem('cashprediction.reconnect', '{');
  const damaged = transport(); await damaged.value.bootstrap();
  check(damaged.value.connected, 'damaged storage normal bootstrap'); damaged.value.stop();
  await renderers();
  const final = await control('inspect');
  check(final.rawKeyLeaks === 0 && final.badHeaders === 0 && final.badClientProofs === 0 && final.obsoleteCursors === 0,
    'wire security and fresh cursor');
  return {checks: passed, formShowEvidence, intents: final.intents, rawKeyLeaks: final.rawKeyLeaks, badHeaders: final.badHeaders,
    badClientProofs: final.badClientProofs, obsoleteCursors: final.obsoleteCursors};
}

window.reconnectProbeReady = true;
