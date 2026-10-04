/** Проверяет только интеграцию API и cleanup на настоящем тексте драйвера, без доказательства браузерной краски. */
import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';

const source = readFileSync(new URL('../../../main/resources/web/app/test-driver.js', import.meta.url), 'utf8')
  .replace(/^import .*;\r?$/gm, '').replace('export function installTestApi', 'function installTestApi');
const captureId = '00000000-0000-0000-0000-000000000001';

/** Заменяет внешние зависимости для проверки порядка хуков, не имитируя DOM/capture evidence. */
function fixture(mode = 'success') {
  const calls = [], events = [], gate = {}, prepared = {}, counters = {render: 7};
  const app = {testApi: true, iconCapture: {registry: {}}, paintCssomJournal: {complete: false,
    dispose() {calls.push('cssom-dispose');}}, paintGeneration: 3, debouncers: new Set(),
    transport: {tail: Promise.resolve(), effectTail: Promise.resolve(),
      async request() {calls.push('counters'); return {counters};}}};
  let dependencies, late;
  const context = vm.createContext({Map, Set, Object, Number, Error, Promise, structuredClone, performance, setTimeout,
    document: {getElementById: id => ({id})}, window: {addEventListener: (name, action) => events.push([name, action])},
    frame: async () => {}, visible: () => true,
    dump: async () => ({schema: 1}),
    prepareDump: async () => {calls.push('prepare'); return prepared;},
    readPreparedDump: () => {calls.push('read'); return {schema: 1};},
    discardPreparedDump: value => {assert.equal(value, prepared); calls.push('discard');},
    createPaintObserver: value => {
      dependencies = value;
      return {
        async prepareCapture(request) {
          if (mode === 'late') {
            late = new Promise(resolve => {gate.release = resolve;}).then(() => value.prepareRaw(request));
            // Отдельный обработчик нужен только для контроля ожидаемого отказа позднего callback.
            late.catch(() => {}); throw new Error('capture timed out');
          }
          const p = await value.prepareRaw(request);
          if (mode === 'after-prepare') throw new Error('font deadline');
          return {ok: true, value: value.readRaw(request, p)};
        },
        finishCapture: value => ({ok: true, value}),
        abortCapture: value => ({ok: true, value}),
        dispose() {calls.push('observer-dispose');}
      };
    }
  });
  new vm.Script(source + '\nthis.install = installTestApi;').runInContext(context);
  context.install(app);
  return {api: context.window.cpParityTestApi, calls, app, counters, gate, events,
    dependencies: () => dependencies, late: () => late};
}

test('capture requires explicit opt-in and publishes diagnostic, not strict completeness', () => {
  const f = fixture(); f.app.testApi = false;
  assert.throws(() => f.api.configurePaintCapture({environment: {}}), /unavailable/);
  f.app.testApi = true;
  const result = f.api.configurePaintCapture({environment: {os: 'test'}});
  assert.equal(result.value.protocol, 'widget-paint-diagnostic-v1'); assert.equal(result.value.complete, false);
  assert.throws(() => f.api.configurePaintCapture({environment: {}}), /already configured/);
});

test('counter request and table preparation precede synchronous read and cleanup', async () => {
  const f = fixture(); f.api.configurePaintCapture({environment: {}});
  const result = await f.api.prepareCapture({captureId});
  assert.deepEqual(f.calls, ['counters', 'prepare', 'read', 'discard']);
  assert.equal(result.value.counters.render, 7); f.counters.render = 99;
  assert.equal(result.value.counters.render, 7);
  assert.equal(f.dependencies().readRenderGeneration(), 3);
  assert.throws(() => f.dependencies().readRaw({captureId}, {}), /identity missing/);
  await assert.rejects(f.api.prepareCapture({captureId}), /identity unavailable/);
});

test('failure between preparation and read disposes the unconsumed context', async () => {
  const f = fixture('after-prepare'); f.api.configurePaintCapture({environment: {}});
  await assert.rejects(f.api.prepareCapture({captureId}), /font deadline/);
  assert.deepEqual(f.calls, ['counters', 'prepare', 'discard']);
});

test('late preparation after a deadline cannot retain a forgotten dump context', async () => {
  const f = fixture('late'); f.api.configurePaintCapture({environment: {}});
  await assert.rejects(f.api.prepareCapture({captureId}), /capture timed out/);
  f.gate.release(); await assert.rejects(f.late(), /preparation cancelled/);
  assert.deepEqual(f.calls, ['counters', 'prepare', 'discard']);
});

test('pagehide releases observer and original journal wrappers', () => {
  const f = fixture(); f.api.configurePaintCapture({environment: {}});
  const hide = f.events.find(([name]) => name === 'pagehide'); assert.ok(hide); hide[1]();
  assert.deepEqual(f.calls, ['observer-dispose', 'cssom-dispose']);
});
