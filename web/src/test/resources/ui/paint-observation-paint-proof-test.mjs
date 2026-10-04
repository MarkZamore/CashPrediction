/** @file Проверки достоверности paint-полей в mock DOM; не доказательство браузерной или native краски. */
import {readFile} from 'node:fs/promises';
import {webcrypto} from 'node:crypto';
import assert from 'node:assert/strict';
import test from 'node:test';

const source = await readFile(new URL('../../../main/resources/web/app/paint-observation.js', import.meta.url), 'utf8');
const {createPaintObserver} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));

/** Даёт асинхронной подготовке дойти до управляемого кадрового барьера. */
async function advance() { await new Promise(/** Пропускает оборот Node event loop. */ resolve => setImmediate(resolve)); }

/** Создаёт изолированный mock DOM для выполнения настоящих функций observer без браузера. */
function fixture({widgets = true, retained = true, paintSource = 'image-node', journalComplete = true} = {}) {
  const frames = new Map(), targets = [], observers = [], subscriptions = new Set();
  let nextFrame = 0, journalRevision = 0;
  /** Хранит точные listeners, чтобы проверять освобождение после finish и dispose. */
  class Target {
    /** Регистрирует новую mock цель. */
    constructor() { this.events = []; targets.push(this); }
    /** Запоминает исходный callback и capture-флаг. */
    addEventListener(type, callback, capture = false) { this.events.push({type, callback, capture}); }
    /** Удаляет только совпадающую подписку. */
    removeEventListener(type, callback, capture = false) {
      this.events = this.events.filter(/** Оставляет чужие подписки. */ value => value.type !== type || value.callback !== callback || value.capture !== capture);
    }
  }
  /** Наблюдает управляемые mutation records без подмены кода production observer. */
  class Observer {
    /** Сохраняет callback, очередь и состояние ресурса. */
    constructor(callback) { this.callback = callback; this.records = []; this.active = true; observers.push(this); }
    /** Принимает подключение к mock узлу. */
    observe() {}
    /** Однократно отдаёт ожидающие записи, включая ABA. */
    takeRecords() { const records = this.records; this.records = []; return records; }
    /** Освобождает observer. */
    disconnect() { this.active = false; }
  }
  /** Представляет только используемые сборщиком DOM методы и фактическую геометрию mock опыта. */
  class Node {
    /** Создаёт mock узел с независимым набором CSS classes. */
    constructor(tag, classes, bounds) {
      this.tagName = tag.toUpperCase(); this.localName = tag; this.classes = classes; this.bounds = bounds;
      this.children = []; this.childNodes = this.children; this.dataset = {}; this.textContent = '';
      this.scrollLeft = 0; this.scrollTop = 0; this.isConnected = true; this.parentElement = null;
    }
    /** Соединяет реальное дерево mock ссылок. */
    append(node) { node.parentElement = this; this.children.push(node); return node; }
    /** Возвращает прямоугольник видимого узла. */
    getClientRects() { return [this.bounds]; }
    /** Возвращает исходные дробные координаты без округления. */
    getBoundingClientRect() { return {...this.bounds}; }
    /** Возвращает отсутствие атрибутов данной fixture. */
    getAttributeNames() { return []; }
    /** Проверяет mock selector, не выдумывая hover или focus. */
    matches(selector) {
      return selector.split(',').some(/** Сопоставляет каждый простой selector. */ part => {
        const value = part.trim(); return value.startsWith('.') ? this.classes.includes(value.slice(1)) : value === this.localName;
      });
    }
    /** Ищет ближайшего настоящего mock предка указанного типа. */
    closest(selector) { for (let node = this; node; node = node.parentElement) if (node.matches(selector)) return node; return null; }
    /** Проверяет принадлежность дерева, не идентификатор элемента. */
    contains(node) { return node === this || this.children.some(/** Проверяет потомка рекурсивно. */ child => child.contains(node)); }
    /** Возвращает подходящих потомков в исходном порядке дерева. */
    querySelectorAll(selector) {
      const result = [];
      for (const child of this.children) { if (selector === '*' || child.matches(selector)) result.push(child); result.push(...child.querySelectorAll(selector)); }
      return result;
    }
    /** Возвращает первый подходящий дочерний элемент. */
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    /** Подтверждает только mock завершение decode, не фактический draw. */
    decode() { return Promise.resolve(); }
  }
  const doc = new Target(), win = new Target(), viewport = new Target(), fonts = new Target();
  Object.assign(viewport, {scale: 1, offsetLeft: 0, offsetTop: 0});
  const root = new Node('main', [], {x: 0, y: 0, width: 1200, height: 800}); root.ownerDocument = doc;
  const toolbar = root.append(new Node('section', [], {x: 10, y: 10, width: 200, height: 40}));
  const summary = root.append(new Node('section', [], {x: 10, y: 60, width: 200, height: 100}));
  const control = toolbar.append(new Node('button', ['toolbar-node'], {x: 12, y: 12, width: 30, height: 30})); control.dataset.cpId = 'tb.undo.action';
  const image = control.append(new Node(paintSource === 'image-node' ? 'img' : 'span', [], {x: 14, y: 14, width: 16, height: 16}));
  const card = summary.append(new Node('article', ['card'], {x: 12.25, y: 62.5, width: 100, height: 40})); card.dataset.cpId = 'summary.balance';
  if (!widgets) { toolbar.children.length = 0; summary.children.length = 0; }
  const uri = 'https://mock.invalid/app/icons/undo.png';
  Object.assign(image, {currentSrc: uri, complete: true, naturalWidth: 16, naturalHeight: 16});
  const asset = {assetId: 'mock-retained-image', byteLength: 4, base64: 'AQIDBA==', sha256: 'a'.repeat(64),
    decodedWidth: 16, decodedHeight: 16, decodedArgbSha256: 'b'.repeat(64), source: uri, sourceObjectIdentity: 'mock-installed-object'};
  doc.documentElement = root; doc.fonts = fonts; fonts.status = 'loaded'; fonts.ready = Promise.resolve();
  doc.visibilityState = 'visible'; doc.styleSheets = []; doc.adoptedStyleSheets = []; doc.baseURI = uri;
  doc.hasFocus = /** Обозначает mock активный документ. */ () => true;
  doc.getAnimations = /** Исключает автономную анимацию данной fixture. */ () => [];
  doc.querySelectorAll = /** Отдаёт все mock узлы только для census selector. */ selector => selector === '*' ? [root, ...root.querySelectorAll('*')] : [];
  /** Создаёт computed style для наблюдаемого узла, без ожидаемых production tokens. */
  function style(node, pseudo) {
    const value = {visibility: 'visible', display: 'block', opacity: '1', overflowX: 'visible', overflowY: 'visible',
      outlineStyle: 'none', backgroundColor: 'rgb(255, 255, 255)', backgroundClip: 'border-box', backgroundOrigin: 'border-box',
      backgroundImage: node === image && (paintSource === 'css-background' && !pseudo || paintSource === 'pseudo-element' && pseudo === '::before') ? 'url("' + uri + '")' : 'none',
      borderImageSource: 'none', listStyleImage: 'none', borderRadius: '0px', backgroundRepeat: 'no-repeat', backgroundAttachment: 'scroll',
      backgroundSize: 'auto', backgroundPosition: '0% 0%', objectFit: 'fill', objectPosition: '50% 50%', mixBlendMode: 'normal',
      content: node === image && paintSource === 'pseudo-element' && pseudo === '::before' ? '""' : 'none',
      /** Читает стабильные свойства mock CSS без дополнительных эффектов. */
      getPropertyValue(property) {
        if (property.endsWith('-width') || property.startsWith('padding-')) return '0px';
        if (property.endsWith('-color')) return 'rgb(0, 0, 0)';
        if (property.endsWith('-style')) return 'none';
        return '';
      },
      /** Даёт стабильный пустой CSS fingerprint этой fixture. */
      [Symbol.iterator]: function* () {}};
    for (const corner of ['TopLeft', 'TopRight', 'BottomLeft', 'BottomRight']) value['border' + corner + 'Radius'] = '0px';
    return value;
  }
  const globals = {document: doc, window: win, visualViewport: viewport, MutationObserver: Observer, ResizeObserver: Observer,
    crypto: webcrypto, innerWidth: 1200, innerHeight: 800, devicePixelRatio: 1, getComputedStyle: style,
    /** Планирует управляемый mock кадр. */
    requestAnimationFrame(callback) { frames.set(++nextFrame, callback); return nextFrame; },
    /** Удаляет точный собственный кадр. */
    cancelAnimationFrame(handle) { frames.delete(handle); }};
  const saved = new Map(Object.keys(globals).map(/** Удерживает прежний descriptor каждого global. */ key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  for (const [key, value] of Object.entries(globals)) Object.defineProperty(globalThis, key, {value, writable: true, configurable: true});
  /** Подписывает listener на mock изменение и возвращает точное освобождение. */
  function subscribe(callback) { subscriptions.add(callback); return /** Удаляет только свою подписку. */ () => subscriptions.delete(callback); }
  const registry = {
    /** Возвращает источник только для реально привязанного объекта fixture. */
    lookup(node, kind, sourceUri) { return retained && node === image && kind === paintSource && sourceUri === uri ? asset : null; },
    /** Читает неизменное поколение retained fixture. */ revision() { return 0; }, subscribe};
  const journal = {complete: journalComplete,
    /** Читает контролируемое CSSOM поколение mock зависимости. */ revision() { return journalRevision; }, subscribe};
  const observer = createPaintObserver({root, toolbar, summary, registry, cssomJournal: journal,
    /** Не задерживает mock очередь приложения. */ awaitIdle() { return Promise.resolve(); },
    /** Готовит mock raw до чтения. */ prepareRaw() { return Promise.resolve({}); },
    /** Возвращает raw идентичность того же запроса и viewport. */
    readRaw(request) { return {schema: 1, client: 'web', scenario: request.scenario, step: request.step, frame: {contentWidth: 1200, contentHeight: 800}}; },
    /** Возвращает независимое поколение mock приложения. */ readRenderGeneration() { return 17; },
    environment: {os: 'mock-only', runtime: 'node', renderer: 'contract-fixture-not-native', artifactDigests: {mock: 'c'.repeat(64)}}});
  return {observer, journal, asset, image, card, root, frames, observers, subscriptions, targets,
    /** Создаёт новый запрос с реальным Node монотонным дедлайном. */
    request() { return {runId: webcrypto.randomUUID(), captureId: webcrypto.randomUUID(), commandNumber: 1, scenario: 'paint-proof', step: 'mock', attempt: 1,
      planSha256: 'd'.repeat(64), cardStates: {}, deadlineNanos: (BigInt(Math.floor(performance.now() * 1000000)) + 10000000000n).toString()}; },
    /** Подготавливает capture, увеличивая freshness до первого чтения без каких-либо draw callbacks. */
    async prepare() {
      const pending = observer.prepareCapture(this.request());
      for (let i = 0; i < 3; i++) {
        await advance();
        if (i === 0) observers[0].callback([{type: 'attributes'}]);
        const callbacks = [...frames]; frames.clear();
        for (const [, callback] of callbacks) callback(performance.now());
      }
      return (await pending).value;
    },
    /** Записывает изменение с возвратом текста и pending DOM records между границами. */
    domABA() { const before = card.textContent; card.textContent = 'temporary'; card.textContent = before; observers[0].records.push({type: 'characterData'}, {type: 'characterData'}); },
    /** Считает обе mock CSSOM записи независимо от конечного состояния. */
    cssomABA() { for (let i = 0; i < 2; i++) { journalRevision++; for (const callback of subscriptions) callback('cssom-mock'); } },
    /** Освобождает ресурсы и globals даже при ошибке проверки. */
    restore() {
      try { observer.dispose(); } finally {
        for (const [key, descriptor] of saved) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key]; }
      }
    }
  };
}

/** Находит gap только конкретного наблюдаемого экземпляра. */
function gap(observation, instanceId, property) {
  return observation.unsupported.find(/** Не допускает замены owner gap общим root отказом. */ issue => issue.instanceId === instanceId && issue.property === property);
}

/** Завершает диагностический capture и проверяет отсутствие временных ресурсов. */
async function finish(f, prepared) {
  const result = (await f.observer.finishCapture({captureId: prepared.captureId, handle: prepared.handle})).value;
  assert.equal(result.stable, false); assert.equal(f.frames.size, 0); assert.equal(f.subscriptions.size, 0);
  assert.ok(f.observers.every(/** Проверяет отключение всех временных observers. */ value => !value.active));
  f.observer.dispose(); assert.ok(f.targets.every(/** Проверяет удаление также постоянных input listeners. */ value => value.events.length === 0));
  return result;
}

test('mock freshness is not card paint or retained image draw proof', /** Проверяет sentinel, точные gaps и неизменное provenance. */ async () => {
  const f = fixture();
  try {
    const prepared = await f.prepare(), observation = prepared.observation;
    assert.ok(observation.synchronization.paintRevisionBefore > 0);
    assert.equal(observation.cards.length, 1); assert.equal(observation.icons.length, 1);
    const card = observation.cards[0], icon = observation.icons[0];
    assert.equal(card.lastPaintEpoch, 0); assert.match(gap(observation, card.instanceId, 'lastPaintEpoch').reason, /unavailable sentinel/);
    assert.equal(icon.drawEpoch, 0); assert.equal(icon.complete, false); assert.match(gap(observation, icon.instanceId, 'drawEpoch').reason, /unavailable sentinel/);
    assert.equal(icon.assetId, f.asset.assetId); assert.equal(icon.sourceObjectIdentity, f.asset.sourceObjectIdentity);
    assert.deepEqual(observation.assets, [f.asset]); assert.equal(gap(observation, icon.instanceId, 'installed-source'), undefined);
    assert.equal(icon.effectiveOpacity, 1); assert.deepEqual(icon.localBox, {x: 0, y: 0, width: 16, height: 16});
    assert.deepEqual(observation.unsupported.map(/** Извлекает только причины текущего ограниченного mock опыта. */ value => value.property), ['paint-census', 'drawEpoch', 'lastPaintEpoch']);
    const result = await finish(f, prepared);
    assert.deepEqual(result.observation.icons, observation.icons); assert.deepEqual(result.observation.cards, observation.cards);
    assert.deepEqual(result.observation.assets, observation.assets);
  } finally { f.restore(); }
});

test('mock complete CSSOM and empty census cannot certify paint', /** Проверяет независимый root gap даже без cards и images. */ async () => {
  const f = fixture({widgets: false});
  try {
    const prepared = await f.prepare(), observation = prepared.observation;
    assert.equal(observation.icons.length, 0); assert.equal(observation.cards.length, 0);
    assert.ok(gap(observation, observation.viewport.rootInstance, 'paint-census'));
    assert.equal(gap(observation, observation.viewport.rootInstance, 'cssom-journal'), undefined);
    const result = await finish(f, prepared);
    assert.equal(result.synchronization.settled, true); assert.deepEqual(result.synchronization.changes, []);
    assert.equal(result.synchronization.fingerprintBefore, result.synchronization.fingerprintAfter);
  } finally { f.restore(); }
});

for (const paintSource of ['css-background', 'pseudo-element']) {
  test('mock ' + paintSource + ' provenance does not certify a draw', /** Проверяет общий отказ для всех CSS source occurrences. */ async () => {
    const f = fixture({paintSource});
    try {
      const prepared = await f.prepare(), icon = prepared.observation.icons[0];
      assert.equal(prepared.observation.icons.length, 1); assert.equal(icon.paintSource, paintSource);
      assert.equal(icon.assetId, f.asset.assetId); assert.deepEqual(prepared.observation.assets, [f.asset]);
      assert.equal(icon.drawEpoch, 0); assert.equal(icon.complete, false); assert.ok(gap(prepared.observation, icon.instanceId, 'drawEpoch'));
      await finish(f, prepared);
    } finally { f.restore(); }
  });
}

test('unknown mock source keeps its own gap as well as missing draw proof', /** Проверяет, что paint gap не скрывает исходный отказ provenance. */ async () => {
  const f = fixture({retained: false});
  try {
    const prepared = await f.prepare(), icon = prepared.observation.icons[0];
    assert.equal(icon.assetId, null); assert.equal(icon.complete, false); assert.equal(icon.drawEpoch, 0);
    assert.ok(gap(prepared.observation, icon.instanceId, 'installed-source')); assert.ok(gap(prepared.observation, icon.instanceId, 'drawEpoch'));
    assert.deepEqual(prepared.observation.assets, []); await finish(f, prepared);
  } finally { f.restore(); }
});

test('mock DOM ABA keeps freshness rejection with identical endpoint fingerprint', /** Проверяет сохранение pending records и независимость paint sentinel. */ async () => {
  const f = fixture();
  try {
    const prepared = await f.prepare(); f.domABA(); const result = await finish(f, prepared);
    assert.equal(result.synchronization.fingerprintBefore, result.synchronization.fingerprintAfter);
    assert.ok(result.synchronization.changes.includes('dom-mutation-pending'));
    assert.ok(result.synchronization.epochAfter > result.synchronization.epochBefore);
    assert.ok(result.synchronization.paintRevisionAfter > result.synchronization.paintRevisionBefore);
    assert.equal(result.observation.cards[0].lastPaintEpoch, 0); assert.equal(result.observation.icons[0].drawEpoch, 0);
  } finally { f.restore(); }
});

test('mock CSSOM revisions still reject after prepare', /** Проверяет, что новый paint gap не отключает existing journal subscription. */ async () => {
  const f = fixture();
  try {
    const prepared = await f.prepare(); f.cssomABA(); const result = await finish(f, prepared);
    assert.ok(result.synchronization.changes.includes('cssom')); assert.ok(result.synchronization.changes.includes('cssom-revision'));
    assert.ok(result.synchronization.epochAfter > result.synchronization.epochBefore);
  } finally { f.restore(); }
});

test('mock incomplete journal remains an independent refusal', /** Проверяет сохранение CSSOM отказа рядом с root paint census. */ async () => {
  const f = fixture({journalComplete: false});
  try {
    const prepared = await f.prepare(), observation = prepared.observation;
    assert.ok(gap(observation, observation.viewport.rootInstance, 'paint-census'));
    assert.ok(gap(observation, observation.viewport.rootInstance, 'cssom-journal'));
    const result = await finish(f, prepared); assert.ok(result.synchronization.changes.includes('cssom-journal-incomplete'));
  } finally { f.restore(); }
});

test('mock completeness loss still rejects without a revision change', /** Проверяет finish-проверку complete при уже обязательном root paint gap. */ async () => {
  const f = fixture({widgets: false});
  try {
    const prepared = await f.prepare();
    assert.ok(gap(prepared.observation, prepared.observation.viewport.rootInstance, 'paint-census'));
    f.journal.complete = false;
    const result = await finish(f, prepared);
    assert.ok(result.synchronization.changes.includes('cssom-journal-incomplete'));
    assert.equal(result.synchronization.settled, false);
  } finally { f.restore(); }
});
