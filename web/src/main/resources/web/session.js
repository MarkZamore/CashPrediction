/**
 * @file Сессия web-клиента на сервере: выделение строки и размеры окна для снимка, «маяк» при закрытии вкладки,
 * опрос статуса снимков, повторное открытие окон после перезагрузки страницы, баннер «Восстановить с сервера /
 * Начать заново» после сбоя сервера и экраны «нет связи» / «сервер остановлен».
 *
 * Web-аналог раздела 5 плана: у браузера нет реестра и файлов, поэтому снимок хранит сервер
 * (CashMemory/web-session.md), а маркер сбоя двухуровневый - pagehide отправляет sendBeacon, а сервер при запуске
 * видит «running» с мёртвым процессом.
 */

import { h, debounce, downloadUrl } from './util.js';
import { api, apiUrl, ApiError } from './api.js';
import { store, cmd } from './store.js';
import { alertDialog, buttonType, ButtonRole, errorAlert } from './dialogs.js';
import { restoreWindows } from './windows.js';
import { toast } from './popup.js';

/** Ключ sessionStorage: уведомления сервера уже показаны в этой вкладке. */
const NOTICES_KEY = 'cashprediction.noticesShown';

/**
 * Отправляет выделенную строку таблицы (MainWindowState.selectedRowId) с задержкой 300 мс.
 * @type {Function & {flush: Function, cancel: Function}}
 */
export const sendSelection = debounce((rowId) => {
  api.put('/api/session/main', { selectedRowId: rowId || '' }).catch(() => {});
}, 300);

/** Отправляет размеры и положение окна браузера (MainWindowState.bounds, maximized). */
const sendBounds = debounce(() => {
  // Свёрнутое окно Windows сообщает координаты около -32000, безоконный браузер - крошечный размер:
  // такие границы не записываются, чтобы восстановление не получило окно «за экраном».
  if (window.screenX < -10000 || window.screenY < -10000 || window.outerWidth < 200 || window.outerHeight < 150) return;
  const maximized = window.outerWidth >= screen.availWidth && window.outerHeight >= screen.availHeight;
  api.put('/api/session/main', {
    bounds: { x: window.screenX, y: window.screenY, width: window.outerWidth, height: window.outerHeight },
    maximized,
  }).catch(() => {});
}, 800);

/**
 * Запрашивает статус сессии (строка состояния «Сервер ✓ 10:15:30»).
 * @returns {Promise<void>} завершение
 */
async function pollSession() {
  if (document.hidden || store.stopped || store.crashSimulated || !store.state) return;
  try {
    const session = await api.get('/api/session');
    if (store.ui.updateStatus) store.ui.updateStatus(session);
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      showServerDown({
        title: 'Сервер перезапущен',
        text: 'Ключ доступа этой вкладки больше не подходит. Откройте новый адрес из окна сервера CashPrediction.',
        retry: false,
      });
    }
  }
}

/**
 * Подключает обработчики сессии: закрытие вкладки, изменение размеров, периодический опрос статуса.
 */
export function initSessionHooks() {
  // Закрытие вкладки обычным образом: сервер сохраняет снимок, окна остаются на сервере до следующего открытия.
  window.addEventListener('pagehide', (e) => {
    if (!e.persisted && !store.stopped) api.beacon('/api/session/exit');
  });
  window.addEventListener('resize', () => sendBounds());
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) pollSession();
  });
  setInterval(pollSession, 5000);
  sendBounds();
}

/**
 * Показывает экран «нет связи с сервером».
 * @param {object} [options] параметры
 * @param {string} [options.title] заголовок
 * @param {string} [options.text] пояснение
 * @param {boolean} [options.retry=true] показывать кнопку «Повторить»
 */
export function showServerDown({
  title = 'Нет связи с сервером CashPrediction',
  text = 'Сервер остановлен или перезапускается. Если CashPrediction Web запущен заново, откройте адрес из его окна.',
  retry = true,
} = {}) {
  const el = document.getElementById('server-down');
  if (!el) return;
  document.getElementById('server-down-title').textContent = title;
  document.getElementById('server-down-text').textContent = text;
  document.getElementById('server-down-retry').hidden = !retry;
  // Экран должен лечь поверх всех модальных диалогов: верхний слой упорядочен по времени вызова showModal(),
  // поэтому уже открытый экран переоткрывается, чтобы оказаться последним.
  if (typeof el.showModal === 'function') {
    if (el.open) el.close();
    if (!el.dataset.cancelGuard) {
      // Esc не закрывает экран: без сервера работать со страницей всё равно нельзя.
      el.addEventListener('cancel', (e) => e.preventDefault());
      el.dataset.cancelGuard = 'true';
    }
    el.showModal();
  } else {
    el.setAttribute('open', '');
  }
}

/** Экран «сервер остановлен» после «Файл → Выход». */
export function showStoppedScreen() {
  store.stopped = true;
  showServerDown({
    title: 'Сервер CashPrediction остановлен',
    text: 'Настройки сохранены, сессия завершена корректно. Вкладку можно закрыть.',
    retry: false,
  });
}

/**
 * Делает главное окно недоступным, пока пользователь решает, восстанавливать ли сессию.
 * @param {boolean} inert недоступно
 */
function setMainInert(inert) {
  for (const id of ['app-header', 'toolbar', 'notice-bar', 'app-main']) {
    const el = document.getElementById(id);
    if (el) el.inert = inert;
  }
}

/**
 * Показывает уведомления сервера при запуске один раз на вкладку (например, «уже запущен другой сервер»).
 * JavaFX: Alert(WARNING) «CashPrediction уже запущен…» → Swing: SwingAlert → Web: <dialog class="alert">
 * @param {object} state состояние
 * @returns {Promise<void>} завершение
 */
async function showNoticesOnce(state) {
  const notices = state.notices || [];
  if (!notices.length) return;
  const text = notices.join('\n');
  try {
    if (sessionStorage.getItem(NOTICES_KEY) === text) return;
    sessionStorage.setItem(NOTICES_KEY, text);
  } catch {
    // sessionStorage недоступен - покажем уведомление ещё раз, это не страшно.
  }
  const already = state.session && state.session.alreadyRunning;
  await alertDialog({
    type: 'WARNING',
    title: already ? 'CashPrediction уже запущен' : 'Сообщения при запуске',
    header: already
      ? 'CashPrediction уже запущен. Этот сервер открыт без восстановления и без записи сессии.'
      : 'При запуске сервера были замечания',
    content: text,
  });
}

/**
 * Предлагает сохранить несохранённый план из снимка, который не удалось открыть (RECORDER_NOT_STARTED),
 * затем начинает запись сессии.
 * JavaFX: FileChooser.showSaveDialog → Swing: JFileChooser → Web: скачивание GET /api/session/unrestored-plan
 * @param {Array<string>} warnings предупреждения восстановления
 * @returns {Promise<void>} завершение
 */
export async function offerUnrestoredPlan(warnings) {
  const DOWNLOAD = buttonType('Скачать план (.md)', 'download', ButtonRole.OK_DONE);
  const SKIP = buttonType('Не сохранять', 'skip', ButtonRole.CANCEL_CLOSE);
  const value = await alertDialog({
    type: 'WARNING',
    title: 'План из снимка не открыт',
    header: 'Несохранённый план из снимка не удалось открыть',
    content: 'Чтобы не потерять изменения, скачайте текст плана - его можно открыть через «Файл → Импорт с компьютера». '
      + 'После этого запись сессии начнётся.',
    details: (warnings || []).join('\n') || null,
    buttons: [DOWNLOAD, SKIP],
  });
  if (value === 'download') downloadUrl(apiUrl('/api/session/unrestored-plan'));
  try {
    store.set(await api.post('/api/session/record'));
  } catch (e) {
    await errorAlert(e, 'Запись сессии не началась');
  }
}

/**
 * Открывает окна, которые хранит сервер, и сообщает об этом.
 * @param {Array<object>} windows окна
 * @returns {Promise<void>} завершение
 */
async function reopenWindows(windows) {
  if (!windows || !windows.length) return;
  const count = await restoreWindows(windows);
  if (count > 0) toast('Состояние восстановлено с сервера', 'success', 4000);
}

/**
 * Баннер восстановления после сбоя сервера (web-аналог диалога 17).
 * JavaFX: Alert + ButtonType «Из реестра Windows» / «Из XML-файла» / «Не восстанавливать» → Swing: SwingRecoveryDialog
 * → Web: баннер с кнопками «Восстановить с сервера» / «Начать заново»
 * @param {object} session сведения о сессии (state.session)
 */
export function showRecoveryBanner(session) {
  const banner = document.getElementById('recovery-banner');
  if (!banner) return;
  const snapshot = session.pendingSnapshot;
  const windowsText = (session.pendingWindows || []).join(', ');
  const restore = h('button', {
    type: 'button', class: 'btn primary',
    text: `Восстановить с сервера${session.pendingSavedAtText ? ` (сохранено ${session.pendingSavedAtText.slice(-8)})` : ''}`,
    disabled: !!session.pendingProblem,
    title: session.pendingProblem || 'Вернуть план, вид, выделение и открытые окна из снимка web-session.md',
  });
  const fresh = h('button', {
    type: 'button', class: 'btn', text: 'Начать заново', title: 'Удалить снимок и начать с последнего сохранённого плана',
  });
  banner.replaceChildren(
    h('div', { class: 'banner-icon', 'aria-hidden': 'true', text: '⚠' }),
    h('div', { class: 'banner-text' },
      h('strong', { text: 'Предыдущий сеанс CashPrediction завершился аварийно.' }),
      h('div', {
        text: `Начат: ${session.pendingStartedAtText || '-'}; снимок сохранён: ${session.pendingSavedAtText || '-'}. `
          + 'Восстановить открытые окна и введённые данные?',
      }),
      h('div', {
        class: 'banner-details',
        text: `План: ${session.pendingPlan || 'без файла'}${session.pendingDirty ? ' (с несохранёнными изменениями)' : ''}`
          + `${snapshot && snapshot.main ? `; вид: ${snapshot.main.view === 'CHART' ? 'график' : 'таблица'}` : ''}`
          + `${windowsText ? `; окна: ${windowsText}` : '; открытых окон не было'}.`,
      }),
      session.pendingProblem ? h('div', { class: 'banner-problem', text: `Снимок не читается: ${session.pendingProblem}` }) : null),
    h('div', { class: 'banner-buttons' }, restore, fresh));
  banner.hidden = false;
  setMainInert(true);
  (session.pendingProblem ? fresh : restore).focus();

  /**
   * Выполняет решение пользователя.
   * @param {boolean} doRestore восстановить (true) или начать заново (false)
   * @returns {Promise<void>} завершение
   */
  const decide = async (doRestore) => {
    restore.disabled = true;
    fresh.disabled = true;
    try {
      const res = await api.post('/api/session/start', { restore: doRestore });
      banner.hidden = true;
      setMainInert(false);
      const selected = doRestore && snapshot && snapshot.main ? snapshot.main.selectedRowId : '';
      if (selected) store.selectedRowId = selected;
      store.set(res);
      if (selected && store.ui.table) store.ui.table.select(selected, { scroll: true, send: false });
      if (!doRestore) {
        toast('Начат новый сеанс', 'info', 2500);
        if (res.openWizard) cmd('newPlanStartup');
        return;
      }
      const lastRestore = res.session && res.session.lastRestore;
      if (lastRestore && lastRestore.recorderNotStarted) {
        await offerUnrestoredPlan(lastRestore.warnings);
      } else if (res.warnings && res.warnings.length) {
        await alertDialog({
          type: 'WARNING', title: 'Восстановление', header: 'Сессия восстановлена с замечаниями', content: res.warnings.join('\n'),
        });
      }
      await reopenWindows(res.windows);
      if (!res.windows || !res.windows.length) toast('Состояние восстановлено с сервера', 'success', 4000);
    } catch (e) {
      restore.disabled = !!session.pendingProblem;
      fresh.disabled = false;
      await errorAlert(e, 'Решение о восстановлении не выполнено');
    }
  };
  restore.addEventListener('click', () => decide(true));
  fresh.addEventListener('click', () => decide(false));
}

/**
 * Действия после первой загрузки страницы: уведомления, баннер сбоя, выделение строки, повторное открытие окон
 * или мастер нового плана.
 * @param {object} state состояние GET /api/state
 * @param {object} info сведения GET /api/session
 * @returns {Promise<void>} завершение
 */
export async function afterInitialLoad(state, info) {
  await showNoticesOnce(state);
  if (state.pendingRestore) {
    showRecoveryBanner(state.session);
    return;
  }
  if (info && info.selectedRowId && store.ui.table) {
    store.ui.table.select(info.selectedRowId, { scroll: true, send: false });
  }
  const lastRestore = state.session && state.session.lastRestore;
  if (lastRestore && lastRestore.recorderNotStarted && !state.session.recording) {
    await offerUnrestoredPlan(lastRestore.warnings);
  }
  if (state.windows && state.windows.length) {
    await reopenWindows(state.windows);
  } else if (state.openWizard) {
    cmd('newPlanStartup');
  }
}
