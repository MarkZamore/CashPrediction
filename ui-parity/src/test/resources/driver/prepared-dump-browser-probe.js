/** @file Реальный Edge fixture production Table/dump, не whole app и не complete CSSOM доказательство. */
import {Table} from '/app/render-table.js';
import {dump, prepareDump, readPreparedDump, discardPreparedDump} from '/app/dump.js';
import {probePreparedDump} from '/prepared-dump-probe.js';

/** Проверяет actual browser условие с диагностикой CDP. */
function check(value, message) { if (!value) throw new Error(message); }

/** Проверяет синхронный отказ, не принимая Promise или успешный raw за fail closed. */
function rejected(action, fragment) {
  try { const value = action(); check(!value?.then, 'Failure unexpectedly returned Promise'); }
  catch (error) { check(error.message.includes(fragment), 'Wrong failure: ' + error.message); return true; }
  throw new Error('Expected failure: ' + fragment);
}

/** Создаёт полную request identity с deadline на реальных монотонных часах вкладки. */
function request() {
  return {runId: crypto.randomUUID(), captureId: crypto.randomUUID(), commandNumber: 3, scenario: 'prepared-browser', step: 'read',
    attempt: 1, planSha256: 'a'.repeat(64), cardStates: {balance: 'NORMAL'},
    deadlineNanos: String(BigInt(Math.floor(performance.now() * 1000000)) + 20000000000n)};
}

/** Пропускает реальные кадры, доставляя scroll/resize и фоновые production Table.paint. */
async function settle(app) {
  await document.fonts.ready;
  for (let index = 0; index < 3; index++) await new Promise(/** Ждёт настоящий requestAnimationFrame. */ resolve => requestAnimationFrame(resolve));
  await app.table.paint();
  check(app.table.paintFrame === null && app.table.loads.size === 0 && app.transport.pending === 0, 'Fixture table did not settle');
}

/** Создаёт размеры/цвета fixture; это вход рендера, а не выдача ожидаемых raw paint данных. */
function fixtureStyles() {
  const style = document.createElement('style');
  style.textContent = `
    body { margin: 0; font: 13px Arial; color: var(--cp-text-primary); }
    :root {
      --cp-bg-window: #fff; --cp-bg-surface: #fafafa; --cp-bg-alt: #eee; --cp-text-primary: #111;
      --cp-text-muted: #555; --cp-border: #aaa; --cp-border-strong: #777; --cp-accent: #147;
      --cp-accent-weak: #def; --cp-spacing: 4px; --cp-radius: 4px; --cp-header-height: 28px;
      --cp-row-height: 26px; --cp-toolbar-height: 36px; --cp-control-height: 28px;
      --cp-font-family: Arial; --cp-font-base-size: 13px; --cp-font-card-size: 18px;
      --cp-card-value-line-height: 24px; --cp-card-title-line-height: 16px;
      --cp-card-caption-line-height: 16px; --cp-card-content-gap: 2px; --cp-card-gap: 4px;
    }
    #center { flex: 0 0 184px; height: 184px; }
  `;
  document.head.append(style); return style;
}

/** Подключает только HTTP fixture транспорта и настоящий Table; остальные producer сервисы здесь не запускаются. */
async function fixtureApp() {
  const transport = {generation: 1, seq: 4, connected: true, stopped: false, pending: 0, calls: 0,
    /** Проверяет actual generation приложения fixture. */ current(generation) { return generation === this.generation && this.connected; },
    /** Загружает единственный настоящий JSON ответ строк localhost. */ async request(path) {
      this.pending++; this.calls++;
      try { const response = await fetch(path); check(response.ok, 'Fixture HTTP response'); return await response.json(); }
      finally { this.pending--; }
    },
    /** Проверяет фактический запрос production Table.page и передаёт его bounded HTTP fixture. */ async query(query) {
      check(query.type === 'rows' && query.rev === 7 && query.from === 0 && query.count === 84, 'Unexpected Table query');
      return this.request('/fixture/rows');
    }};
  const app = {transport, chart: {root: document.getElementById('chart')}, resyncing: null,
    windows: new Map(), alerts: new Map(), chooserRequests: new Map(),
    popups: {/** Не устанавливает tooltip обработчики, не относящиеся к этому отдельному fixture. */ tooltip() {}},
    /** Отвергает непредусмотренную серверную синхронизацию fixture. */ resync() { throw new Error('Unexpected resync'); }};
  app.table = new Table(app);
  await app.table.update({revision: 7, rowCount: 84, selectedRowId: 'row65', columns: [
    {id: 'date', title: 'Date', headerTooltip: '', widthPx: 180, grows: false, align: 'LEFT', bold: false},
    {id: 'amount', title: 'Amount', headerTooltip: '', widthPx: 180, grows: false, align: 'LEFT', bold: false}
  ]});
  app.table.scroll.scrollTop = 65 * 26; await settle(app);
  check(app.table.canvas.querySelector('[data-index="65"]'), 'Selected row must be rendered in actual canvas');
  return app;
}

/** Проверяет ошибку после собственной подготовки, всегда освобождая request context. */
async function failureCase(app, mutate, fragment) {
  await settle(app);
  const req = request(), prepared = await prepareDump(app, req);
  let restore = null;
  try {
    restore = mutate(req, prepared);
    const result = rejected(/** Вызывает настоящий production sync read на stale состоянии. */ () => readPreparedDump(app, req, prepared), fragment);
    rejected(/** Проверяет потребление ошибочного контекста. */ () => readPreparedDump(app, req, prepared), 'Unknown prepared');
    return result;
  } finally { restore?.(); discardPreparedDump(prepared); }
}

/** Меняет отдельное поколение fixture и возвращает точное освобождение после синхронной проверки. */
function advance(target, key) {
  const saved = target[key]; target[key]++;
  return /** Восстанавливает только собственное временное значение без UI перерисовки. */ () => { target[key] = saved; };
}

/** Проверяет native MutationObserver ABA после реальной доставки microtask, не вызывая callback вручную. */
async function deliveredMutation(app) {
  await settle(app); const req = request(), prepared = await prepareDump(app, req);
  const node = app.table.canvas.querySelector('.table-cell').firstChild, original = node.data;
  let delivered = 0;
  const observer = new MutationObserver(/** Считает реальные доставленные браузером записи CharacterData. */ records => { delivered += records.length; });
  observer.observe(node, {characterData: true});
  try {
    node.data = original + ' changed'; node.data = original;
    await new Promise(/** Ждёт native доставки MO до continuation проверки. */ resolve => queueMicrotask(resolve));
    check(delivered >= 2 && node.data === original, 'Native delivered ABA fixture');
    return rejected(/** Проверяет dirty flag production observer после возврата исходного текста. */ () => readPreparedDump(app, req, prepared), 'Prepared table was mutated');
  } finally { observer.disconnect(); discardPreparedDump(prepared); }
}

/** Загружает другой origin CSS и проверяет настоящий SecurityError вместо искусственной подмены native getter. */
async function foreignCss(app) {
  const link = document.createElement('link'); link.rel = 'stylesheet'; link.href = document.body.dataset.foreignCss;
  try {
    await new Promise(/** Ждёт реальную HTTP загрузку внешнего по origin листа. */ (resolve, reject) => {
      link.addEventListener('load', /** Подтверждает настоящий stylesheet load. */ () => resolve(), {once: true});
      link.addEventListener('error', /** Отвергает CSP/сетевую ошибку вместо ложного SecurityError успеха. */ () => reject(new Error('Foreign stylesheet did not load')), {once: true});
      document.head.append(link);
    });
    let nativeForeignSecurityError = false;
    try { void link.sheet.cssRules; } catch (error) { nativeForeignSecurityError = error.name === 'SecurityError'; }
    check(nativeForeignSecurityError, 'Actual cross origin cssRules SecurityError required');
    let foreignCssUnsupported = false;
    try { const ticket = await prepareDump(app, request()); discardPreparedDump(ticket); }
    catch (error) { foreignCssUnsupported = error.message === 'Unsupported dump CSSOM inspection' && error.cause?.name === 'SecurityError'; }
    check(foreignCssUnsupported, 'Unreadable CSS must fail closed, not return partial prepared raw');
    return {nativeForeignSecurityError, foreignCssUnsupported};
  } finally { link.remove(); }
}

window.runPreparedDumpBrowserProbe = /** Выполняет actual runtime regression на отдельном DOM fixture и освобождает Table observer. */ async () => {
  const style = fixtureStyles(); let app = null;
  try {
    app = await fixtureApp(); const req = request();
    const ordinary = await dump(app, req.step, req.scenario); await settle(app);
    const calls = app.transport.calls, topBefore = app.table.scroll.scrollTop;
    const measured = await probePreparedDump(app, req), raw = measured.raw;
    // Дополнительно сверяем HTTP счётчик на всём prepare/read; sync read отдельно защищён существующей пробой.
    const requestsDuringRead = app.transport.calls - calls, scrollRestored = app.table.scroll.scrollTop === topBefore;
    const ordinarySchemaEqual = JSON.stringify(ordinary) === JSON.stringify(raw);
    check(ordinarySchemaEqual, 'Ordinary and prepared schema/values differ on stable DOM');
    check(app.transport.calls === calls, 'Cached preparation/read unexpectedly requested rows');
    check(scrollRestored, 'Prepared traversal did not restore actual scroll');

    await settle(app); const liveRequest = request(), liveTicket = await prepareDump(app, liveRequest);
    const frozenIdentity = Object.isFrozen(liveTicket) && Object.isFrozen(liveTicket.identity) && Object.isFrozen(liveTicket.identity.cardStates);
    const value = document.querySelector('.card-value'); value.textContent = 'DOM live';
    const column = app.table.model.columns[0], oldTitle = column.title; column.title = 'MODEL ONLY';
    let live;
    try { live = readPreparedDump(app, liveRequest, liveTicket); }
    finally { column.title = oldTitle; discardPreparedDump(liveTicket); }
    const liveDom = live.summary.cards[0].value === value.textContent && live.table.rows.find(/** Находит actual выбранную canvas строку в raw. */ row => row.index === 65).cells[0]
      === app.table.canvas.querySelector('[data-index="65"] .table-cell').textContent;
    const modelNotSerialized = live.table.columns[0] === app.table.header.children[0].textContent && !JSON.stringify(live).includes('MODEL ONLY');
    const singleUse = rejected(/** Отклоняет повторное использование успешно прочитанного контекста. */ () => readPreparedDump(app, liveRequest, liveTicket), 'Unknown prepared');

    await settle(app); const forgeRequest = request(), originalTicket = await prepareDump(app, forgeRequest);
    let forgedTicket;
    try { forgedTicket = rejected(/** Отклоняет внешнюю копию даже frozen свойств ticket. */ () => readPreparedDump(app, forgeRequest, Object.freeze({...originalTicket})), 'Unknown prepared'); }
    finally { discardPreparedDump(originalTicket); }
    const captureIdentity = await failureCase(app, /** Подменяет actual captureId без изменения paint ожиданий. */ request => { request.captureId = crypto.randomUUID(); }, 'request mismatch');
    const attemptIdentity = await failureCase(app, /** Меняет номер попытки того же capture. */ request => { request.attempt++; }, 'request mismatch');
    const intentIdentity = await failureCase(app, /** Меняет намерение карточки после копирования identity. */ request => { request.cardStates.balance = 'HOVER'; }, 'request mismatch');
    const staleGeneration = await failureCase(app, /** Меняет generation транспорта только на время sync отказа. */ () => advance(app.transport, 'generation'), 'generation');
    const staleSequence = await failureCase(app, /** Продвигает порядок применённых эффектов без переписывания DOM. */ () => advance(app.transport, 'seq'), 'generation');
    const staleEpoch = await failureCase(app, /** Меняет эпоху настоящего Table. */ () => advance(app.table, 'epoch'), 'generation');
    const staleModel = await failureCase(app, /** Подменяет model identity той же ревизии. */ () => {
      const saved = app.table.model; app.table.model = {...saved};
      return /** Восстанавливает прежнюю ссылку модели. */ () => { app.table.model = saved; };
    }, 'Stale dump object: model');
    const pendingRequest = await failureCase(app, /** Отмечает незавершённый producer запрос. */ () => advance(app.transport, 'pending'), 'generation');
    const mutationPendingAba = await failureCase(app, /** Делает native DOM ABA без async границы до чтения. */ () => {
      const text = app.table.canvas.querySelector('.table-cell').firstChild, saved = text.data; text.data = saved + ' changed'; text.data = saved;
    }, 'Prepared table was mutated');
    const mutationDeliveredAba = await deliveredMutation(app);
    const cssChanged = await failureCase(app, /** Меняет CSSOM без DOM мутации таблицы. */ () => {
      const index = style.sheet.insertRule('.unused-prepared-probe { color: rgb(23, 24, 25); }', style.sheet.cssRules.length);
      return /** Удаляет только собственное добавленное правило. */ () => style.sheet.deleteRule(index);
    }, 'Prepared dump CSS/visibility changed');
    await settle(app); const abortRequest = request(), abortTicket = await prepareDump(app, abortRequest);
    discardPreparedDump(abortTicket); discardPreparedDump(abortTicket);
    const explicitDiscard = rejected(/** Проверяет отменённый handle без повторного сканирования таблицы. */ () => readPreparedDump(app, abortRequest, abortTicket), 'Unknown prepared');
    await settle(app); const foreign = await foreignCss(app);
    return {raw, schemaKeys: Object.keys(raw), mutationCount: measured.mutationCount, scrollChecked: measured.scrollChecked,
      requestsDuringRead, scrollRestored, ordinarySchemaEqual, frozenIdentity, liveDom, modelNotSerialized, singleUse, forgedTicket,
      captureIdentity, attemptIdentity, intentIdentity, staleGeneration, staleSequence, staleEpoch, staleModel, pendingRequest,
      mutationPendingAba, mutationDeliveredAba, cssChanged, explicitDiscard, ...foreign};
  } finally { app?.table.resize.disconnect(); style.remove(); }
};
window.probeReady = true;
