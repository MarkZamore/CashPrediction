/** @file Изолированные проверки владения ресурсами paint observer, без браузера и сторонних пакетов. */
import {readFile} from 'node:fs/promises';
import {webcrypto} from 'node:crypto';
import assert from 'node:assert/strict';
import test from 'node:test';

const source = await readFile(new URL('../../../main/resources/web/app/paint-observation.js', import.meta.url), 'utf8');
const {createPaintObserver} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));

/** Создаёт управляемый Promise для отмены во время реального асинхронного ожидания. */
function deferred() {
  let resolve;
  const promise = new Promise(/** Запоминает завершение внешнего ожидания. */ done => { resolve = done; });
  return {promise, resolve};
}

/** Даёт цепочке await продвинуться до ожидающего кадрового барьера. */
async function advance() { for (let i = 0; i < 12; i++) await Promise.resolve(); }

/** Создаёт минимальную среду для настоящего lifecycle кода и восстанавливает globals после проверки. */
function fixture({idle = Promise.resolve(), failSubscription = false, failDisconnect = false, journalComplete = false} = {}) {
  const targets = [], frames = new Map(), subscriptions = new Set(), instances = [];
  let nextFrame = 0, rawCalls = 0;
  /** Хранит listeners с точным capture-флагом для проверки их удаления. */
  class Target {
    /** Регистрирует независимую цель наблюдения. */
    constructor() { this.events = []; targets.push(this); }
    /** Добавляет исходную callback-ссылку и режим capture. */
    addEventListener(type, callback, options = false) { this.events.push({type, callback, capture: options === true}); }
    /** Удаляет только listener с совпадающим capture-флагом. */
    removeEventListener(type, callback, options = false) {
      this.events = this.events.filter(/** Сохраняет все чужие подписки. */ event =>
        event.type !== type || event.callback !== callback || event.capture !== (options === true));
    }
  }
  /** Хранит факт отключения observer, включая контролируемый отказ одного cleanup. */
  class Observer {
    /** Регистрирует реальный callback данной тестовой среды. */
    constructor(callback) { this.callback = callback; this.active = true; instances.push(this); }
    /** Обозначает начало наблюдения конкретного узла. */
    observe() {}
    /** Возвращает отсутствие ожидающих записей в неподвижной странице. */
    takeRecords() { return []; }
    /** Освобождает ресурс и при необходимости имитирует ошибку native API. */
    disconnect() { this.active = false; if (failDisconnect && this === instances[0]) throw new Error('disconnect'); }
  }
  const doc = new Target(), win = new Target(), viewport = new Target(), fonts = new Target(), media = new Target();
  Object.assign(viewport, {scale: 1, offsetLeft: 0, offsetTop: 0});
  const root = {ownerDocument: doc, isConnected: true, childNodes: [], textContent: '', scrollLeft: 0, scrollTop: 0}, toolbar = {}, summary = {};
  root.getClientRects = /** Возвращает видимую поверхность fixture. */ () => [{}];
  root.getBoundingClientRect = /** Возвращает неподвижный viewport root. */ () => ({x: 0, y: 0, width: 1200, height: 800});
  root.getAttributeNames = /** Возвращает отсутствие атрибутов. */ () => [];
  root.matches = /** Возвращает отсутствие hover и focus. */ () => false;
  toolbar.getClientRects = summary.getClientRects = /** Исключает виджеты вне lifecycle fixture. */ () => [];
  toolbar.querySelectorAll = summary.querySelectorAll = /** Возвращает отсутствие изображений до кадровой фазы. */ () => [];
  doc.documentElement = root; doc.fonts = fonts; fonts.ready = Promise.resolve(); fonts.status = 'loaded';
  doc.visibilityState = 'visible'; doc.hasFocus = /** Возвращает активность fixture. */ () => true;
  doc.getAnimations = /** Возвращает отсутствие анимаций. */ () => [];
  doc.querySelectorAll = /** Возвращает только реальные узлы lifecycle fixture. */ selector => selector === '*' ? [root] : [];
  doc.styleSheets = [{media: {mediaText: '(min-width: 1px)'}, cssRules: []}]; doc.adoptedStyleSheets = [];
  const globals = {document: doc, window: win, visualViewport: viewport, MutationObserver: Observer, ResizeObserver: Observer,
    crypto: webcrypto, innerWidth: 1200, innerHeight: 800, devicePixelRatio: 1,
    /** Возвращает неизменный минимальный computed style для проверки bracket. */
    getComputedStyle() { return {visibility: 'visible', display: 'block', opacity: '1',
      /** Возвращает отсутствие дополнительных свойств fixture. */ [Symbol.iterator]: function* () {}}; },
    /** Планирует кадр, сохраняя отменяемый handle. */
    requestAnimationFrame(callback) { frames.set(++nextFrame, callback); return nextFrame; },
    /** Удаляет только собственный кадр по точному handle. */
    cancelAnimationFrame(handle) { frames.delete(handle); },
    /** Возвращает цель реального для fixture media-условия. */
    matchMedia() { return media; }};
  const saved = new Map(Object.keys(globals).map(/** Сохраняет исходные дескрипторы Node globals. */ key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  for (const [key, value] of Object.entries(globals)) Object.defineProperty(globalThis, key, {value, writable: true, configurable: true});
  const registry = {
    /** Возвращает отсутствие установленных источников. */ lookup() { return null; },
    /** Читает неизменное поколение тестовой среды. */ revision() { return 0; },
    /** Подключает listener, который обязан быть удалён даже после ошибки соседнего API. */
    subscribe(callback) { subscriptions.add(callback); return /** Освобождает конкретную подписку. */ () => subscriptions.delete(callback); }
  };
  const journal = {
    complete: journalComplete,
    /** Читает неизменное поколение CSSOM fixture. */ revision() { return 0; },
    /** Имитирует отказ при частично установленном наблюдении. */
    subscribe(callback) { if (failSubscription) throw new Error('subscribe'); return registry.subscribe(callback); }
  };
  const observer = createPaintObserver({root, toolbar, summary, registry, cssomJournal: journal,
    /** Возвращает контролируемый барьер приложения. */ awaitIdle() { return idle; },
    /** Считает недопустимую подготовку raw после отмены. */ prepareRaw() { rawCalls++; return Promise.resolve({}); },
    /** Возвращает синхронный raw неподвижной lifecycle fixture. */
    readRaw(request) { return {schema: 1, client: 'web', scenario: request.scenario, step: request.step,
      frame: {contentWidth: 1200, contentHeight: 800}}; },
    /** Возвращает неподвижное поколение клиента. */ readRenderGeneration() { return 0; },
    environment: {os: 'fixture', runtime: 'node', renderer: 'fixture', artifactDigests: {fixture: 'a'.repeat(64)}}});
  return {observer, journal, frames, subscriptions, instances, targets, media,
    /** Создаёт новый одноразовый запрос с дедлайном на текущих монотонных часах. */
    request() { return {runId: webcrypto.randomUUID(), captureId: webcrypto.randomUUID(), commandNumber: 1, scenario: 'lifecycle',
      step: 'waiting', attempt: 1, planSha256: 'a'.repeat(64), cardStates: {},
      deadlineNanos: (BigInt(Math.floor(performance.now() * 1000000)) + 10000000000n).toString()}; },
    /** Читает фактическое количество вызовов prepareRaw. */ rawCalls() { return rawCalls; },
    /** Освобождает observer и восстанавливает каждый global даже после исключения cleanup. */
    restore() {
      try { observer.dispose(); } finally {
        for (const [key, descriptor] of saved) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key]; }
      }
    }
  };
}

/** Проверяет отсутствие всех временных ресурсов и постоянных listeners после dispose. */
function released(f) {
  assert.equal(f.frames.size, 0); assert.equal(f.subscriptions.size, 0);
  assert.ok(f.instances.every(/** Проверяет каждый созданный observer. */ observer => !observer.active));
  assert.ok(f.targets.every(/** Проверяет каждую цель подписок, включая media с capture=false. */ target => target.events.length === 0));
}

test('dispose during idle stops raw preparation and removes all resources', /** Проверяет отмену незавершённого внешнего Promise. */ async () => {
  const idle = deferred(), f = fixture({idle: idle.promise});
  try {
    const pending = f.observer.prepareCapture(f.request());
    f.observer.dispose(); await assert.rejects(pending, /Capture aborted/);
    idle.resolve(); await advance(); assert.equal(f.rawCalls(), 0); released(f);
    f.observer.dispose();
  } finally { f.restore(); }
});

test('abort cancels pending layout barrier and allows a fresh capture', /** Проверяет rAF leak и повторное использование живого observer после отмены. */ async () => {
  const f = fixture();
  try {
    const request = f.request(), pending = f.observer.prepareCapture(request); await advance();
    assert.equal(f.frames.size, 2);
    f.observer.abortCapture({captureId: request.captureId}); await assert.rejects(pending, /Capture aborted/);
    assert.equal(f.frames.size, 0); assert.equal(f.subscriptions.size, 0); assert.equal(f.media.events.length, 0);
    const next = f.request(), retry = f.observer.prepareCapture(next); await advance();
    assert.equal(f.frames.size, 2); f.observer.abortCapture({captureId: next.captureId}); await assert.rejects(retry, /Capture aborted/);
    f.observer.dispose(); released(f);
  } finally { f.restore(); }
});

test('partial setup failure releases previously registered resources', /** Проверяет cleanup при отказе второй подписки после установки всех observers. */ async () => {
  const f = fixture({failSubscription: true});
  try {
    await assert.rejects(f.observer.prepareCapture(f.request()), /subscribe/);
    f.observer.dispose(); released(f);
  } finally { f.restore(); }
});

test('cleanup failure rejects abort but still cancels wait and remaining resources', /** Проверяет строгий отказ вместо успешного aborted при неисправном cleanup API. */ async () => {
  const idle = deferred(), f = fixture({idle: idle.promise, failDisconnect: true});
  try {
    const request = f.request(), pending = f.observer.prepareCapture(request);
    assert.throws(/** Отменяет попытку с отказом одного disconnect. */ () => f.observer.abortCapture({captureId: request.captureId}), /Capture cleanup failed/);
    await assert.rejects(pending, /Capture aborted/); f.observer.dispose(); released(f);
  } finally { f.restore(); }
});

/** Выполняет реальные callbacks управляемого rAF до завершения двух layout-барьеров. */
async function prepare(f) {
  const pending = f.observer.prepareCapture(f.request());
  for (let i = 0; i < 3; i++) {
    await advance(); const callbacks = [...f.frames]; f.frames.clear();
    for (const [, callback] of callbacks) callback(performance.now());
  }
  return (await pending).value;
}

test('journal completeness loss without revision cannot certify a stable finish', /** Проверяет строгий отказ при утрате покрытия между prepare и finish. */ async () => {
  const f = fixture({journalComplete: true});
  try {
    const prepared = await prepare(f);
    // Полный mock CSSOM журнал не доказывает краску, но его собственный gap ещё отсутствует.
    assert.deepEqual(prepared.observation.unsupported.map(issue => issue.property), ['paint-census']);
    f.journal.complete = false;
    const result = await f.observer.finishCapture({captureId: prepared.captureId, handle: prepared.handle});
    assert.equal(result.value.stable, false);
    assert.ok(result.value.synchronization.changes.includes('cssom-journal-incomplete'));
    f.observer.dispose(); released(f);
  } finally { f.restore(); }
});

test('incomplete journal stays unsupported through a full capture lifecycle', /** Проверяет сохранение отказа независимо от равенства отпечатков. */ async () => {
  const f = fixture();
  try {
    const prepared = await prepare(f);
    assert.ok(prepared.observation.unsupported.some(/** Находит неполное CSSOM покрытие. */ issue => issue.property === 'cssom-journal'));
    const result = await f.observer.finishCapture({captureId: prepared.captureId, handle: prepared.handle});
    assert.equal(result.value.stable, false); f.observer.dispose(); released(f);
  } finally { f.restore(); }
});
