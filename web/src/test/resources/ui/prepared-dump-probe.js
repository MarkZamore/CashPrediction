/**
 * @file Опциональная проверка actual browser read для MAIN, не запускается автоматически.
 * MAIN обслуживает файл как ES module, передаёт живой app и frozen PaintCaptureRequest.
 * Подготовка вправе обойти таблицу/запросить страницы; проверяемая синхронная секция не вправе.
 * Здесь нет complete CSSOM/paint обещаний и нет замены CDP whole app capture.
 */
import {prepareDump, readPreparedDump, discardPreparedDump} from '/app/dump.js';

/** Проверяет условие probe, не добавляя пользовательских строк в приложение. */
function check(value, message) { if (!value) throw new Error(message); }

/** Читает позиции всех существующих прокручиваемых узлов перед/после одной секции JS. */
function scrollPositions() {
  return [...document.querySelectorAll('*')].map(/** Сохраняет actual scroll каждого узла по ссылке, не по модели. */ node => [node, node.scrollTop, node.scrollLeft]);
}

/**
 * Выполняет реальную подготовку и затем чистое синхронное чтение с наблюдением DOM.
 * Возвращает actual raw и измеренные записи/позиции; awaitIdle/fonts/images остаются у MAIN.
 * Локальные запреты transport/paint устанавливаются только на время одной синхронной секции,
 * с сохранением чужой подмены при освобождении. Глобальные native методы не перехватываются.
 */
export async function probePreparedDump(app, request) {
  const prepared = await prepareDump(app, request), patches = [], records = [];
  const observer = new MutationObserver(/** Запоминает доставленные браузером DOM записи, не исправляя страницу. */ batch => { records.push(...batch); });
  try {
    const before = scrollPositions(), windowBefore = [scrollX, scrollY];
    observer.observe(document.documentElement, {subtree: true, attributes: true, childList: true, characterData: true});
    /** Запрещает асинхронную работу конкретного app только в проверяемой секции. */
    function forbid(target, key) {
      check(typeof target[key] === 'function', 'Probe dependency missing: ' + key);
      const previous = Object.getOwnPropertyDescriptor(target, key);
      const wrapper = /** Доказывает, что секция не вызывает paint или transport. */ function () { throw new Error('Async work during dump read: ' + key); };
      Object.defineProperty(target, key, {value: wrapper, writable: true, configurable: true}); patches.push({target, key, previous, wrapper});
    }
    forbid(app.table, 'paint'); forbid(app.transport, 'request'); forbid(app.transport, 'query');
    const raw = readPreparedDump(app, request, prepared);
    records.push(...observer.takeRecords());
    check(raw && !raw.then && raw.schema === 1 && raw.client === 'web', 'Probe expected synchronous schema 1');
    check(records.length === 0, 'DOM writes during prepared read');
    for (const [node, top, left] of before) check(node.scrollTop === top && node.scrollLeft === left, 'Scroll changed during prepared read');
    check(windowBefore[0] === scrollX && windowBefore[1] === scrollY, 'Window scroll changed during prepared read');
    return {raw, mutationCount: records.length, scrollChecked: before.length};
  } finally {
    observer.disconnect(); discardPreparedDump(prepared);
    for (const {target, key, previous, wrapper} of patches.reverse()) {
      if (Object.getOwnPropertyDescriptor(target, key)?.value !== wrapper) continue;
      if (previous) Object.defineProperty(target, key, previous); else delete target[key];
    }
  }
}
