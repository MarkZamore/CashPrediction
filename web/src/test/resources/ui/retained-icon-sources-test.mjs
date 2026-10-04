/** @file Developer-only проверки первоначальных HTTP-байтов значков без npm, сети и браузера. */
import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
import test from 'node:test';

globalThis.document = {baseURI: 'http://127.0.0.1:8765/'};
globalThis.location = {origin: 'http://127.0.0.1:8765'};
const source = await readFile(new URL('../../../main/resources/web/app/retained-icon-sources.js', import.meta.url), 'utf8');
const {createRetainedIconSources} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const url = 'http://127.0.0.1:8765/app/icons/up.png';
const other = 'http://127.0.0.1:8765/app/icons/down.png';
const png = Uint8Array.from([137, 80, 78, 71, 13, 10, 26, 10, 31, 53]);

/** Создаёт исходный поток с заданными HTTP-заголовками и счётчиком отмены. */
function response(bytes = png, options = {}) {
  const cancelled = options.cancelled || [];
  const body = new ReadableStream({
    /** Публикует фактические байты один раз без обращения к сети. */
    start(controller) { controller.enqueue(bytes); if (!options.open) controller.close(); },
    /** Сохраняет факт отмены принадлежащего тесту потока. */
    cancel() { cancelled.push(true); }
  });
  return {ok: true, status: 200, url, body,
    headers: new Headers({'Content-Type': 'image/png', ...(options.headers || {})}), ...options.response};
}

/** Создаёт настоящий установщик с наблюдаемыми исходными вызовами декодирующего реестра. */
function fixture(fetcher = async () => response(), registryOverride = {}) {
  const calls = [];
  const registry = {
    /** Сохраняет фактически переданные реестру байты img, не читая маркеры узла. */
    async installImage(node, bytes, source) { calls.push({node, bytes, source, background: false}); },
    /** Сохраняет фактически переданные байты фонового изображения. */
    async installBackground(node, bytes, source) { calls.push({node, bytes, source, background: true}); },
    ...registryOverride
  };
  return {calls, sources: createRetainedIconSources({registry, urls: [url, other], fetcher})};
}

/** Проверяет один ответ для нескольких декодирований, копии байтов и точные fetch options. */
test('retains first response exactly and never re-fetches an installed source', async () => {
  const requests = [];
  const f = fixture(async (source, options) => { requests.push({source, options}); return response(); });
  const first = {}, second = {};
  await Promise.all([f.sources.installImage(first, url), f.sources.installBackground(second, url)]);
  await f.sources.ready();
  assert.equal(requests.length, 1); assert.equal(requests[0].source, url);
  assert.equal(requests[0].options.redirect, 'error'); assert.equal(requests[0].options.credentials, 'same-origin');
  assert.equal(f.calls.length, 2); assert.deepEqual(f.calls[0].bytes, png);
  assert.notEqual(f.calls[0].bytes, f.calls[1].bytes);
  f.calls[0].bytes[8] = 99; assert.equal(f.calls[1].bytes[8], 31); assert.equal(png[8], 31);
  await f.sources.installImage({}, url); assert.equal(f.calls.at(-1).bytes[8], 31);
});

/** Старый медленный ответ не заменяет более позднее назначение другого значка. */
test('late response cannot replace a newer node assignment', async () => {
  let release;
  const f = fixture(async source => source === url ? new Promise(resolve => { release = resolve; })
    : response(png, {response: {url: other}}));
  const node = {}, old = f.sources.installImage(node, url);
  await f.sources.installImage(node, other); release(response()); await old; await f.sources.ready();
  assert.equal(f.calls.length, 1); assert.equal(f.calls[0].source, other);
});

/** Неизвестный адрес и иной origin отвергаются до каких-либо запросов. */
test('only predeclared same-origin PNG sources may be requested', () => {
  let requests = 0; const f = fixture(async () => { requests++; return response(); });
  assert.throws(() => f.sources.installImage({}, '/app/icons/missing.png'), /Unapproved/);
  assert.throws(() => createRetainedIconSources({registry: {installImage() {}, installBackground() {}}, urls: ['https://example.invalid/app/icons/a.png']}), /Icon source URL/);
  assert.equal(requests, 0);
});

/** Ошибочный статус, redirect URL, MIME, длина или сигнатура не дают реестру ни одного источника. */
test('rejects invalid HTTP or PNG evidence and keeps the failure visible', async () => {
  const cases = [
    () => response(png, {response: {status: 404, ok: false}}),
    () => response(png, {response: {url: other}}),
    () => response(png, {headers: {'Content-Type': 'text/plain'}}),
    () => response(png, {headers: {'Content-Length': '11'}}),
    () => response(png, {headers: {'Content-Length': '1048577'}}),
    () => response(new Uint8Array(10))
  ];
  for (const factory of cases) {
    const f = fixture(async () => factory());
    await assert.rejects(f.sources.installImage({}, url));
    await assert.rejects(f.sources.ready()); assert.throws(() => f.sources.requireHealthy());
    assert.equal(f.calls.length, 0);
  }
});

/** Лимит фактического потока действует и без Content-Length; reader отменяется. */
test('bounds streaming bytes even when content length is absent', async () => {
  const cancelled = [], f = fixture(async () => response(new Uint8Array(1048577), {open: true, cancelled}));
  await assert.rejects(f.sources.installImage({}, url), /limit/);
  await assert.rejects(f.sources.ready(), /limit/); assert.equal(cancelled.length, 1); assert.equal(f.calls.length, 0);
});

/** Замена уже декодируемого узла делает отказ старой операции неактуальным, не скрывая новый. */
test('obsolete decode rejection does not poison the current assignment', async () => {
  let rejectOld, entered;
  const begun = new Promise(resolve => { entered = resolve; });
  const f = fixture(async source => response(png, {response: {url: source}}), {
    /** Создаёт управляемое старое декодирование и обычное успешное новое. */
    installImage(node, bytes, source) {
      if (source === url) { entered(); return new Promise((resolve, reject) => { rejectOld = reject; }); }
      return Promise.resolve();
    }
  });
  const node = {}, old = f.sources.installImage(node, url); await begun;
  await f.sources.installImage(node, other); rejectOld(new Error('obsolete decode')); await old;
  await f.sources.ready(); f.sources.requireHealthy();
});

/** Отмена установщика не назначает байты завершившегося после dispose запроса. */
test('dispose aborts owned fetch and forbids subsequent bindings', async () => {
  let signal, release;
  const f = fixture(async (source, options) => { signal = options.signal; return new Promise(resolve => { release = resolve; }); });
  const work = f.sources.installImage({}, url); f.sources.dispose();
  assert.equal(signal.aborted, true); release(response()); await work;
  assert.equal(f.calls.length, 0); assert.throws(() => f.sources.installImage({}, url), /Unapproved/);
  await assert.rejects(f.sources.ready(), /disposed/);
});
