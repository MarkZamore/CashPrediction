/**
 * @file Загрузчик тонкого клиента: токен, обработчики ошибок, строка меню, панель инструментов, таблица, график,
 * сводка, строка состояния и первичная загрузка состояния с сервера.
 *
 * Сервер - единственный источник истины: страница показывает последний ответ GET /api/state (или ответ любой правки)
 * и после каждого изменения просто перерисовывается. Числа считает ядро, поэтому они совпадают с JavaFX и Swing.
 */

import { initToken, setServerDownHandler, api, ApiError } from './api.js';
import { store } from './store.js';
import { h, debounce, ruDate } from './util.js';
import { registerCommands } from './commands.js';
import { buildMenuBar, buildToolbar, registerAppHotkeys } from './app-menu.js';
import { ForecastTable } from './table.js';
import { BalanceChart } from './chart.js';
import { SummaryPanel } from './summary.js';
import { initSessionHooks, afterInitialLoad, showServerDown } from './session.js';
import { alertDialog, buttonType, ButtonRole } from './dialogs.js';

/** Идёт показ сообщения о необработанной ошибке (второе подряд не показывается). */
let reportingError = false;

/**
 * Необработанная ошибка JavaScript - аналог необработанного исключения в UI-потоке desktop-клиентов:
 * сервер сохраняет снимок (POST /api/debug/client-error), затем Alert(ERROR) со стеком. Браузер нельзя «аварийно
 * завершить», как Runtime.halt(2), поэтому предлагается перезагрузить страницу - окна вернутся с сервера.
 * @param {unknown} error ошибка
 * @returns {Promise<void>} завершение
 */
async function reportUnhandled(error) {
  if (error instanceof ApiError && error.status === 0) return;
  if (reportingError || store.stopped) return;
  reportingError = true;
  const message = error && typeof error === 'object' && 'message' in error ? String(error.message) : String(error);
  const stack = (error && typeof error === 'object' && 'stack' in error && error.stack) || message;
  try {
    await api.post('/api/debug/client-error', { message, stack: String(stack) });
  } catch {
    // Сервер недоступен: снимок уже записан по таймеру, сообщение всё равно покажем.
  }
  const RELOAD = buttonType('Перезагрузить страницу', 'reload', ButtonRole.OK_DONE);
  const CONTINUE = buttonType('Продолжить работу', 'continue', ButtonRole.CANCEL_CLOSE);
  // JavaFX: Alert(ERROR) + expandableContent (стек) → Swing: SwingAlert.error → Web: <dialog class="alert alert-error">
  const value = await alertDialog({
    type: 'ERROR',
    title: 'Непредвиденная ошибка',
    header: 'Непредвиденная ошибка. Сессия сохранена, при следующем запуске её можно восстановить.',
    content: message,
    details: String(stack),
    detailsLabel: 'Стек вызовов',
    buttons: [RELOAD, CONTINUE],
  });
  reportingError = false;
  if (value === 'reload') location.reload();
}

/** Подключает глобальные обработчики ошибок (до всего остального, как setDefaultUncaughtExceptionHandler в main). */
function installErrorHandlers() {
  window.addEventListener('error', (e) => {
    reportUnhandled(e.error || new Error(e.message));
  });
  window.addEventListener('unhandledrejection', (e) => {
    reportUnhandled(e.reason);
  });
}

/**
 * Перечитывает состояние после автосохранения (сервер сохраняет план примерно через секунду после правки,
 * и флаг «*» в заголовке должен погаснуть).
 */
const refreshAfterAutosave = debounce(async () => {
  try {
    const state = await api.get('/api/state');
    state.autosaveRefresh = true;
    store.set(state);
  } catch {
    // Нет связи - экран «нет связи» покажет api.js.
  }
}, 1800);

/**
 * Планирует перечитывание, если включено автосохранение и план изменён.
 * @param {object} state состояние
 */
function scheduleAutosaveRefresh(state) {
  if (!state.autosaveRefresh && state.settings && state.settings.autosave && state.dirty && state.file !== undefined) {
    refreshAfterAutosave();
  }
}

/**
 * Строка состояния: файл и «*», период, число событий, автосохранение, статус снимков сессии.
 * @param {object} state состояние
 * @param {object} [session] свежие сведения о сессии (из опроса)
 */
function renderStatus(state, session) {
  if (!state) return;
  if (session) state.session = session;
  const s = state.session || {};
  const doc = state.document || {};
  const bar = document.getElementById('statusbar');
  const sessionText = s.state === 'crashed' ? 'Ожидает решения о восстановлении'
    : s.recording ? `Снимок сессии: ${s.statusText || 'ещё не записан'}`
      : s.alreadyRunning ? 'Запись сессии отключена (сервер уже запущен)' : 'Запись сессии не идёт';
  // replaceChildren превратил бы null в текст «null», поэтому пустые сегменты отбрасываются заранее.
  bar.replaceChildren(...[
    h('span', { class: 'status-seg status-file', title: state.file || 'План ещё не сохранён в файл' },
      `${doc.fileName || 'не сохранён'}${state.dirty ? ' *' : ''}`),
    h('span', { class: 'status-seg', text: `Период: до ${state.periodEndText || '-'}` }),
    h('span', { class: 'status-seg', text: `Событий: ${state.rowsTotal ?? 0}` }),
    state.settings && state.settings.autosave
      ? h('span', { class: `status-seg${state.autosaveProblem ? ' warn' : ''}`, title: state.autosaveProblem || 'Автосохранение включено', text: 'Автосохранение' })
      : null,
    h('span', { class: 'status-spacer' }),
    h('span', {
      class: `status-seg status-session ${s.recording ? 'ok' : 'off'}`,
      title: s.storeFile ? `Файл снимка: ${s.storeFile}` : '',
      text: sessionText,
    }),
  ].filter(Boolean));
}

/**
 * Полоса сообщений над таблицей: ошибка прогноза, обрезанная таблица, проблема автосохранения, «что-если».
 * @param {object} state состояние
 */
function renderNotices(state) {
  const bar = document.getElementById('notice-bar');
  const items = [];
  if (state.forecastError) items.push(['error', `Прогноз не построен: ${state.forecastError}`]);
  if (state.rowsTruncated) items.push(['warning', `Таблица обрезана: показаны первые строки из ${state.rowsTotal}.`]);
  if (state.autosaveProblem) items.push(['warning', state.autosaveProblem]);
  if (state.viewState.whatIf && state.viewState.whatIf.active) {
    items.push(['info', 'Включён сценарий «что-если»: цифры отличаются от плана (Инструменты → Что-если → Сбросить).']);
  }
  if (state.forecast && state.forecast.warnings && state.forecast.warnings.length) {
    items.push(['warning', `Предупреждения прогноза: ${state.forecast.warnings.length} (Инструменты → Проверить план).`]);
  }
  bar.replaceChildren(...items.map(([kind, text]) => h('span', { class: `notice ${kind}`, text })));
  bar.hidden = items.length === 0;
}

/**
 * Перерисовывает главное окно по состоянию.
 * @param {object} state состояние
 */
function render(state) {
  document.title = state.title || 'CashPrediction';
  const view = state.viewState;
  store.ui.toolbar.update(state);
  const summary = document.getElementById('summary');
  summary.hidden = !view.summaryPanel;
  if (view.summaryPanel) store.ui.summary.render(state);
  const chartMode = view.mode === 'CHART';
  document.getElementById('view-table').hidden = chartMode;
  document.getElementById('view-chart').hidden = !chartMode;
  if (chartMode) store.ui.chart.render(state);
  else store.ui.table.render(state);
  renderNotices(state);
  renderStatus(state);
}

/**
 * Точка входа.
 * @returns {Promise<void>} завершение
 */
async function main() {
  installErrorHandlers();
  document.getElementById('server-down-retry').addEventListener('click', () => location.reload());
  const token = initToken();
  if (!token) {
    showServerDown({
      title: 'Нет ключа доступа',
      text: 'Откройте CashPrediction по адресу из окна сервера: адрес содержит параметр ?t=… с ключом этого сеанса.',
      retry: false,
    });
    return;
  }
  setServerDownHandler(() => {
    if (!store.stopped && !store.crashSimulated) showServerDown();
  });
  registerCommands();
  store.ui.menuBar = buildMenuBar(document.getElementById('menubar'));
  store.ui.toolbar = buildToolbar(document.getElementById('toolbar'));
  store.ui.table = new ForecastTable(document.getElementById('view-table'));
  store.ui.chart = new BalanceChart(document.getElementById('view-chart'));
  store.ui.summary = new SummaryPanel(document.getElementById('summary'));
  store.ui.updateStatus = (session) => renderStatus(store.state, session);
  registerAppHotkeys();
  store.on(render);
  store.on(scheduleAutosaveRefresh);

  let state;
  let info;
  try {
    [state, info] = await Promise.all([api.get('/api/state'), api.get('/api/session')]);
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      showServerDown({
        title: 'Ключ доступа не подходит',
        text: 'Сервер перезапущен или адрес устарел. Откройте адрес из окна сервера CashPrediction.',
        retry: false,
      });
    } else if (!(e instanceof ApiError && e.status === 0)) {
      reportUnhandled(e);
    }
    return;
  }
  store.selectedRowId = info.selectedRowId || '';
  store.set(state);
  initSessionHooks();
  await afterInitialLoad(state, info);
  // Подсказка при первом запуске пустого плана.
  if (!state.pendingRestore && !state.plan.rules.length && !state.plan.oneTimes.length && !state.openWizard) {
    const table = store.ui.table;
    if (table && table.footer) {
      table.footer.textContent = `В плане «${state.plan.name}» пока нет операций: «＋ Добавить доход» на панели или «Файл → Открыть пример». Начало плана: ${ruDate(state.plan.startDate)}.`;
      table.footer.hidden = false;
    }
  }
}

main();
