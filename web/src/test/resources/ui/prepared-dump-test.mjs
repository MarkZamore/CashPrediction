/** @file Изолированные developer проверки production dump с минимальными DOM двойниками, не browser signoff. */
import {readFile} from 'node:fs/promises';
import {webcrypto, createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import test from 'node:test';

/** Создаёт URL модуля в памяти, не меняя production imports на диске. */
function moduleUrl(source) { return 'data:text/javascript;base64,' + Buffer.from(source).toString('base64'); }
const domUrl = moduleUrl(`
/** Читает геометрию двойника. */
export function bounds(node) { const {x,y,width,height}=node.getBoundingClientRect(); return {x,y,width,height}; }
/** Проверяет видимость двойника вместе с предками. */
export function visible(node) { return !!node && !node.hidden && (!node.parentElement || visible(node.parentElement)); }
export {bounds as dialogContentBounds};`);
const formUrl = moduleUrl(`/** Читает живое значение поля. */
export function fieldValue(node) { return node.value; }
/** Читает DOM переключателей. */
export function radioInputs(node) { return node.children; }`);
const alertUrl = moduleUrl(`/** Возвращает технический глиф двойника. */
export function nativeAlertGlyph(kind) { return kind; }`);
const source = await readFile(new URL('../../../main/resources/web/app/dump.js', import.meta.url), 'utf8');
const api = await import(moduleUrl(source.replaceAll("'./dom.js'", JSON.stringify(domUrl))
  .replaceAll("'./render-form.js'", JSON.stringify(formUrl)).replaceAll("'./render-alert.js'", JSON.stringify(alertUrl))));

/** Создаёт допустимую идентичность, не используя ожидаемые paint значения. */
function request() {
  return {runId: '12345678-1234-1234-1234-123456789abc', captureId: '87654321-1234-1234-1234-123456789abc',
    commandNumber: 3, scenario: 'prepared', step: 'live', attempt: 1, planSha256: 'a'.repeat(64),
    cardStates: {balance: 'NORMAL'}, deadlineNanos: '9000000000000000000'};
}

/** Предоставляет свежий DOM двойник и восстанавливает все временные globals после проверки. */
async function fixture(action, count = 70) {
  const globals = ['document', 'getComputedStyle', 'MutationObserver', 'innerWidth', 'innerHeight', 'devicePixelRatio', 'matchMedia', 'crypto'];
  const saved = new Map(); for (const key of globals) saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
  const state = {readOnly: false, writes: 0, paints: 0, css: '', bold: false, observers: [], onPaint: null};
  /** Запрещает побочные действия в синхронной фазе. */
  function write() { assert.equal(state.readOnly, false, 'write in read'); state.writes++; }
  /** Минимальный DOM узел с живыми текстами и геометрией. */
  class Node {
    /** Создаёт элемент двойника. */
    constructor(text = '') {
      this.textContent = text; this.children = []; this.dataset = {}; this.hidden = false; this.parentElement = null;
      this.style = {}; this.attrs = {}; this.queries = new Map(); this.classList = {/** Проверяет пустой список классов двойника. */ contains() { return false; }};
      this.rect = {x: 0, y: 0, width: 100, height: 26, top: 0, bottom: 26}; this.scrollLeft = 0;
    }
    /** Возвращает геометрию без изменения DOM. */
    getBoundingClientRect() { return {...this.rect}; }
    /** Читает заданный атрибут. */
    getAttribute(name) { return this.attrs[name] ?? null; }
    /** Возвращает явно связанный DOM результат. */
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    /** Возвращает явно связанные элементы. */
    querySelectorAll(selector) { return this.queries.get(selector) || []; }
    /** Проверяет тип элемента двойника. */
    matches() { return false; }
    /** Вставляет измерительный маркер только во время подготовки. */
    append(node) { write(); node.parentElement = this; this.children.push(node); }
    /** Удаляет измерительный маркер только во время подготовки. */
    remove() { write(); this.parentElement.children.splice(this.parentElement.children.indexOf(this), 1); }
  }
  /** Моделирует очередь MutationObserver и её освобождение. */
  class Observer {
    /** Сохраняет callback и локальную очередь. */
    constructor(listener) { this.listener = listener; this.records = []; this.active = false; state.observers.push(this); }
    /** Включает наблюдение. */
    observe() { this.active = true; }
    /** Забирает необработанные записи. */
    takeRecords() { const records = this.records; this.records = []; return records; }
    /** Освобождает наблюдение. */
    disconnect() { this.active = false; this.records = []; }
  }
  const nodes = new Map(); for (const id of ['main', 'menuBar', 'toolbar', 'summary', 'center', 'status', 'screens']) nodes.set(id, new Node());
  nodes.get('screens').hidden = true;
  const body = new Node(), toolbarLabel = new Node('label before'), status = new Node('status before'); status.dataset.cpId = 'status';
  nodes.get('toolbar').queries.set('.toolbar-label', [toolbarLabel]);
  const card = new Node(), title = new Node('card'), value = new Node('DOM before'), caption = new Node('caption'); card.dataset.cpId = 'balance';
  card.queries.set('.card-title', [title]); card.queries.set('.card-value', [value]); card.queries.set('.card-caption', [caption]);
  nodes.get('summary').queries.set('.card', [card]);
  const root = new Node(), header = new Node(), column = new Node('column DOM'), canvas = new Node(), scroll = new Node(), chartRoot = new Node();
  column.dataset.cpId = 'amount'; header.children = [column]; root.attrs['aria-rowcount'] = String(count);
  const selected = count > 65 ? 65 : -1; root.dataset.selectedRowId = selected >= 0 ? 'row' + selected : '';
  let top = count > 65 ? 65 * 26 : 0;
  Object.defineProperty(scroll, 'scrollTop', {/** Читает реальную позицию двойника. */ get() { return top; },
    /** Записывает позицию только вне read. */ set(value) { write(); top = value; }});
  scroll.clientHeight = 52; scroll.clientWidth = 100; scroll.rect = {x: 0, y: 0, width: 100, height: 52, top: 0, bottom: 52};
  const table = {root, header, canvas, scroll, epoch: 2, generation: 1, model: {revision: 7, rowCount: 999, rows: 'MODEL MUST NOT LEAK'},
    loads: new Map(), paintFrame: null,
    /** Рисует реальные для двойника строки, а не сериализует модель. */
    async paint() {
      write(); state.paints++; canvas.children = [];
      for (let index = Math.floor(top / 26); index < Math.min(count, Math.floor(top / 26) + 2); index++) {
        const row = new Node(); row.dataset = {index: String(index), cpId: 'row' + index, kind: 'DATA'};
        row.attrs['aria-selected'] = String(index === selected); row.parentElement = root;
        const offset = (index * 26 - top); row.rect = {x: 0, y: offset, width: 100, height: 26, top: offset, bottom: offset + 26};
        const cell = new Node('DOM row ' + index); cell.dataset.cpId = 'amount'; cell.parentElement = row; row.children = [cell]; canvas.children.push(row);
      }
      if (state.onPaint) state.onPaint();
    }};
  const app = {table, chart: {root: chartRoot}, transport: {generation: 1, seq: 4, pending: 0, connected: true, stopped: false},
    resyncing: false, windows: new Map(), alerts: new Map(), chooserRequests: new Map()};
  const doc = {body, title: 'title', fonts: {status: 'loaded'}, adoptedStyleSheets: [],
    styleSheets: [{href: null, disabled: false, media: {mediaText: ''}, /** Читает текущие правила CSS двойника. */ get cssRules() { return [{cssText: state.css}]; }}],
    /** Находит живой узел по имени. */ getElementById(id) { return nodes.get(id); },
    /** Возвращает текущие сегменты статуса. */ querySelectorAll(selector) { return selector === '.status-segment' ? [status] : []; },
    /** Создаёт измерительный узел только до read. */ createElement() { write(); return new Node(); }};
  /** Возвращает вычисленный стиль двойника, включая текущую жирность живой ячейки. */
  function computed(node) {
    return {color: 'rgb(1, 2, 3)', backgroundColor: 'transparent', fontWeight: state.bold ? '700' : '400', fontStyle: 'normal', textDecorationLine: '',
      /** Читает действующее CSS свойство. */ getPropertyValue(name) { return name.startsWith('--cp-') ? 'rgb(1, 2, 3)' : '400'; },
      /** Перечисляет CSS свойства baseline двойника. */ *[Symbol.iterator]() { yield 'font-size'; }};
  }
  const overrides = {document: doc, getComputedStyle: computed, MutationObserver: Observer, innerWidth: 1000, innerHeight: 600, devicePixelRatio: 1,
    /** Читает media состояние двойника. */ matchMedia() { return {matches: true}; },
    crypto: {subtle: {/** Хеширует только в prepare, сохраняя реальные байты строк. */ digest(...args) { assert.equal(state.readOnly, false); return webcrypto.subtle.digest(...args); }}}};
  for (const [key, val] of Object.entries(overrides)) Object.defineProperty(globalThis, key, {value: val, writable: true, configurable: true});
  try { await table.paint(); await action({app, state, nodes, status, toolbarLabel, value, canvas, root, doc}); }
  finally { for (const [key, descriptor] of saved) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key]; } }
}

/** Независимо вычисляет digest по данным DOM двойника и старому wire формату. */
function expectedDigest(count) {
  const hash = createHash('sha256');
  for (let index = 0; index < count; index++) {
    const bytes = Buffer.from(JSON.stringify(['DOM row ' + index])), size = Buffer.alloc(4); size.writeUInt32BE(bytes.length); hash.update(size); hash.update(bytes);
  }
  return hash.digest('hex');
}

test('sync read has no writes/paint/hash, uses live DOM, keeps schema and prepared digest', /** Проверяет чистое чтение и происхождение raw. */ async () => {
  await fixture(/** Меняет только живые данные вне архивной таблицы и её вычисленный стиль. */ async ({app, state, value, status, toolbarLabel}) => {
    const req = request(), prepared = await api.prepareDump(app, req), writes = state.writes, paints = state.paints;
    assert.ok(Object.isFrozen(prepared) && Object.isFrozen(prepared.identity) && Object.isFrozen(prepared.identity.cardStates));
    value.textContent = 'DOM live'; status.textContent = 'status live';
    // Baseline защищён измерением; текст toolbar/status должен оставаться тем же для чтения.
    status.textContent = 'status before'; toolbarLabel.textContent = 'label before';
    state.bold = true;
    // Жирность baseline оставляем прежней, изменяя только вычисленные стили ячеек в двойнике.
    const oldComputed = globalThis.getComputedStyle;
    globalThis.getComputedStyle = /** Ограничивает изменение жирности живыми строками. */ node => {
      const css = oldComputed(node); if (node === toolbarLabel || node === status) css.fontWeight = '400'; return css;
    };
    state.readOnly = true; const raw = api.readPreparedDump(app, req, prepared);
    assert.equal(raw?.then, undefined); assert.equal(state.writes, writes); assert.equal(state.paints, paints);
    assert.deepEqual(Object.keys(raw), ['schema', 'client', 'scenario', 'step', 'frame', 'menuBar', 'toolbar', 'summary', 'table', 'chart', 'status', 'contextMenus', 'windows', 'alerts', 'popups', 'screens', 'chooserRequests', 'classCensus', 'counters']);
    assert.equal(raw.schema, 1); assert.equal(raw.summary.cards[0].value, 'DOM live'); assert.equal(raw.table.rowCount, 70);
    assert.equal(raw.table.rowsDigest, expectedDigest(70)); assert.equal(raw.table.rows.length, 61);
    assert.equal(raw.table.rows.find(/** Находит текущую выбранную строку. */ row => row.index === 65).styles.amount.bold, true);
    assert.equal(raw.table.rows[0].styles.amount.bold, false); assert.ok(!JSON.stringify(raw).includes('MODEL MUST NOT LEAK'));
    assert.equal(state.observers.at(-1).active, false);
    assert.throws(/** Отклоняет повторное чтение потреблённого контекста. */ () => api.readPreparedDump(app, req, prepared), /Unknown/);
  });
});

test('ordinary dump preserves pretable summary and posttable status order', /** Проверяет прежний порядок обычного async дампа. */ async () => {
  await fixture(/** Меняет DOM на асинхронной границе таблицы. */ async ({app, state, value, status}) => {
    state.onPaint = /** Меняет текст после чтения старого префикса. */ () => { value.textContent = 'after'; status.textContent = 'after'; };
    const raw = await api.dump(app, 'ordinary', 'scenario');
    assert.equal(raw.summary.cards[0].value, 'DOM before'); assert.equal(raw.status[0].text, 'after');
    assert.equal(raw.table.rowsDigest, expectedDigest(70)); assert.equal(app.table.scroll.scrollTop, 65 * 26);
  });
});

test('zero rows and hidden main retain ordinary schema shape', /** Проверяет пустую таблицу и экран без главного окна. */ async () => {
  await fixture(/** Читает пустую настоящую таблицу двойника. */ async ({app}) => {
    const req = request(), prepared = await api.prepareDump(app, req), raw = api.readPreparedDump(app, req, prepared);
    assert.equal(raw.table.rowsDigest, expectedDigest(0)); assert.deepEqual(raw.table.rows, []);
  }, 0);
  await fixture(/** Сохраняет null части обычной схемы при скрытом главном окне. */ async ({app, nodes, state}) => {
    nodes.get('main').hidden = true; const req = request(), prepared = await api.prepareDump(app, req); state.readOnly = true;
    const raw = api.readPreparedDump(app, req, prepared);
    assert.equal(raw.frame, null); assert.equal(raw.table, null); assert.equal(raw.chart, null); assert.equal(raw.summary, null); assert.equal(raw.toolbar, null);
  });
});

const staleCases = [
  ['transport', /** Меняет поколение транспорта. */ ({app}) => app.transport.generation++],
  ['sequence', /** Продвигает последовательность даже без изменения модели. */ ({app}) => app.transport.seq++],
  ['epoch', /** Меняет эпоху таблицы. */ ({app}) => app.table.epoch++],
  ['model identity', /** Подменяет модель той же ревизии. */ ({app}) => { app.table.model = {...app.table.model}; }],
  ['pending', /** Оставляет незавершённый запрос. */ ({app}) => app.transport.pending++],
  ['scroll', /** Меняет прокрутку без DOM мутации. */ ({app}) => { app.table.scroll.scrollTop += 26; }],
  ['paint frame', /** Оставляет запланированную перерисовку. */ ({app}) => { app.table.paintFrame = 17; }],
  ['page load', /** Оставляет незавершённую загрузку страницы. */ ({app}) => { app.table.loads.set(0, {}); }],
  ['table generation', /** Меняет поколение кеша страниц таблицы. */ ({app}) => app.table.generation++],
  ['CSS', /** Меняет действующие правила CSSOM. */ ({state}) => { state.css = 'body{color:red}'; }],
  ['baseline', /** Меняет текст измеренного baseline. */ ({toolbarLabel}) => { toolbarLabel.textContent = 'changed'; }],
  ['row text', /** Меняет фактическую ячейку даже без доставленной записи observer. */ ({canvas}) => { canvas.children[0].children[0].textContent = 'changed'; }],
  ['DOM ABA pending', /** Сохраняет ожидающую запись о возвратной мутации. */ ({state}) => { state.observers.at(-1).records.push({type: 'characterData'}); }],
  ['DOM ABA delivered', /** Доставляет запись о мутации с уже прежним текстом. */ ({state}) => { state.observers.at(-1).listener([{type: 'characterData'}]); }]
];
for (const [name, mutate] of staleCases) test('reject stale ' + name, /** Проверяет отказ без обновления/переподготовки. */ async () => {
  await fixture(/** Инвалидирует одноразовый контекст. */ async env => {
    const req = request(), prepared = await api.prepareDump(env.app, req); mutate(env); env.state.readOnly = true;
    assert.throws(/** Читает устаревший контекст. */ () => api.readPreparedDump(env.app, req, prepared));
    assert.equal(env.state.observers.at(-1).active, false);
  });
});

test('identity forgery/mismatch and explicit discard', /** Проверяет привязку request, копирование и безопасный abort. */ async () => {
  await fixture(/** Проверяет разные способы неверного чтения. */ async ({app, state}) => {
    const req = request(), prepared = await api.prepareDump(app, req); req.cardStates.balance = 'HOVER';
    assert.equal(prepared.identity.cardStates.balance, 'NORMAL');
    assert.throws(/** Проверяет неподходящий request. */ () => api.readPreparedDump(app, req, prepared), /mismatch/);
    const ticket = await api.prepareDump(app, request());
    assert.throws(/** Проверяет внешнюю копию ticket. */ () => api.readPreparedDump(app, request(), {...ticket}), /Unknown/);
    api.discardPreparedDump(ticket); api.discardPreparedDump(ticket); assert.equal(state.observers.at(-1).active, false);
    assert.throws(/** Проверяет отменённый ticket. */ () => api.readPreparedDump(app, request(), ticket), /Unknown/);
  });
});

test('preparation rejects revision change/deadline and restores scroll on failure', /** Проверяет аварийную подготовку без утечек observer. */ async () => {
  await fixture(/** Меняет эпоху при первом paint обхода. */ async ({app, state}) => {
    const savedTop = app.table.scroll.scrollTop; state.onPaint = /** Инвалидирует архивную ревизию. */ () => app.table.epoch++;
    await assert.rejects(api.prepareDump(app, request()), /generation/); assert.equal(app.table.scroll.scrollTop, savedTop);
    state.onPaint = null; const req = request(); req.deadlineNanos = '0'; await assert.rejects(api.prepareDump(app, req), /deadline/);
    const ticket = await api.prepareDump(app, request()); api.discardPreparedDump(ticket);
  });
});

test('wrong app consumes known ticket and foreign CSS/fonts fail closed', /** Проверяет освобождение и неподдерживаемое состояние подготовки. */ async () => {
  await fixture(/** Проверяет привязку к app и недоступный CSSOM. */ async ({app, state, doc}) => {
    const req = request(), ticket = await api.prepareDump(app, req);
    assert.throws(/** Отклоняет другую ссылку app. */ () => api.readPreparedDump({...app}, req, ticket), /application mismatch/);
    assert.equal(state.observers.at(-1).active, false);
    doc.fonts.status = 'loading'; await assert.rejects(api.prepareDump(app, req), /fonts/); doc.fonts.status = 'loaded';
    doc.styleSheets = [{href: 'foreign', disabled: false, media: {mediaText: ''},
      /** Моделирует SecurityError настоящего cross origin листа. */ get cssRules() { throw new Error('SecurityError'); }}];
    await assert.rejects(api.prepareDump(app, req), /Unsupported dump CSSOM/);
  });
});
