/** @file Тонкий браузерный порт моделей и эффектов единого AppController. */
import {Transport} from './transport.js';
import {Menus} from './render-menu.js';
import {renderToolbar} from './render-toolbar.js';
import {renderSummary} from './render-summary.js';
import {Table} from './render-table.js';
import {Chart} from './render-chart.js';
import {renderStatus} from './render-status.js';
import {FormWindow} from './render-form.js';
import {AlertWindow} from './render-alert.js';
import {Popups} from './render-popups.js';
import {installKeys} from './keys.js';
import {showScreen, hideScreen} from './screens.js';
import {bounds, debounce} from './dom.js';

/** Соединяет рендереры, не владея состоянием плана и решениями ядра. */
export class Application {
  /** Создаёт виджеты и один транспорт вкладки. */
  constructor() {
    this.windows = new Map(); this.alerts = new Map(); this.debouncers = new Set(); this.texts = {}; this.hotkeys = []; this.serverInert = false;
    // История реально показанных обозревателей переживает закрытие окон и resync этой вкладки.
    this.chooserRequests = new Map();
    this.transport = new Transport(effect => this.effect(effect), error => this.offline(error), () => this.resync());
    this.menus = new Menus(this); this.popups = new Popups(this); this.table = new Table(this); this.chart = new Chart(this);
    installKeys(this);
    this.resize = debounce(() => {
      this.send({type: 'mainGeometry', bounds: {x: 0, y: 0, width: innerWidth, height: innerHeight}, maximized: false});
      if (this.screen?.mode === 'CHART') this.chart.update(this.screen.chart);
    }, 120);
    this.debouncers.add(this.resize); window.addEventListener('resize', () => this.resize());
    window.addEventListener('error', event => this.clientError(event.error || new Error(event.message)));
    window.addEventListener('unhandledrejection', event => this.clientError(event.reason));
    window.addEventListener('pagehide', () => this.transport.stop());
    document.addEventListener('pointerdown', event => {
      for (const form of this.windows.values()) if (form.spec.presentation === 'POPUP' && !form.node.contains(event.target)) this.send({type: 'formClose', windowId: form.id});
    });
  }

  /** Отправляет намерение; ошибка транспорта уже обслуживается общим обработчиком. */
  send(intent) { const promise = this.transport.intent(intent); promise.catch(() => {}); return promise; }

  /** Передаёт команду выбранного виджета без собственной доступности. */
  command(command, args = {}, source = 'MAIN') {
    return this.send({type: 'command', command, args: {rowId: '', date: null, cardId: '', key: '', value: '', ...args}, source});
  }

  /** Запускает bootstrap, затем независимое чтение журнала. */
  async start() { await this.resync(); this.transport.poll(); }

  /** Устанавливает один пассивный драйвер; эффект ждёт тот же импорт, а не переотправляется. */
  installDriver() {
    this.testDriverReady ||= import('./test-driver.js').then(({installTestApi}) => {
      this.testDriver = installTestApi(this); return this.testDriver;
    });
    return this.testDriverReady;
  }

  /** Заменяет экран целостным снимком и открывает живые окна по порядку. */
  resync() {
    if (this.resyncing) return this.resyncing;
    this.resyncing = this.bootstrap().finally(() => { this.resyncing = null; });
    return this.resyncing;
  }

  /** Получает bootstrap и восстанавливает только визуальное состояние. */
  async bootstrap() {
    try {
      const data = await this.transport.bootstrap();
      this.texts = data.texts; this.hotkeys = data.hotkeys; this.testApi = data.testApi;
      if (!data.testApi && this.testDriver) { delete window.cpParityTestApi; this.testDriver = null; this.testDriverReady = null; }
      if (data.testApi && !this.testDriver) {
        await this.installDriver();
      }
      for (const form of this.windows.values()) form.close(); for (const alert of this.alerts.values()) alert.close();
      this.windows.clear(); this.alerts.clear(); this.menus.close(); hideScreen();
      this.screen = data.screen;
      this.mainPending = data.overlay === 'RECOVERY_PENDING';
      document.getElementById('main').hidden = this.mainPending;
      this.serverInert = this.mainPending;
      if (this.mainPending) document.title = '';
      else await this.renderMain();
      for (const effect of data.windows) await this.effect(effect);
      if (data.overlay === 'STOPPED' || data.overlay === 'CRASHED') {
        const prefix = data.overlay === 'STOPPED' ? 'offline.stopped' : 'offline.crashed';
        showScreen(this, data.overlay.toLowerCase(), this.texts[prefix + '.title'], this.texts[prefix + '.text']);
        this.transport.stop();
      } else if (data.overlay === 'RECOVERY_PENDING') { this.serverInert = true; this.syncModality(); }
    } catch (error) { this.offline(error); }
  }

  /** Рисует целый главный экран после фактического разрешения его показа ядром. */
  renderMain() {
    const model = this.screen;
    return this.render({TITLE: model.windowTitle, MENU: model.menuBar, TOOLBAR: model.toolbar, SUMMARY: model.summary, TABLE: model.table, CHART: model.chart, STATUS: model.status, MODE: model.mode});
  }

  /** Обновляет только переданные части экрана. */
  async render(parts) {
    const names = {TITLE: 'windowTitle', MENU: 'menuBar', TOOLBAR: 'toolbar', SUMMARY: 'summary', TABLE: 'table', CHART: 'chart', STATUS: 'status', MODE: 'mode'};
    for (const [key, value] of Object.entries(parts)) this.screen[names[key]] = value;
    if ('TITLE' in parts) document.title = parts.TITLE;
    if ('MENU' in parts) this.menus.bar(parts.MENU);
    if ('TOOLBAR' in parts) renderToolbar(this, parts.TOOLBAR);
    if ('SUMMARY' in parts) renderSummary(this, parts.SUMMARY);
    if ('STATUS' in parts) renderStatus(parts.STATUS);
    document.getElementById('table').hidden = this.screen.mode !== 'TABLE';
    document.getElementById('chart').hidden = this.screen.mode !== 'CHART';
    if ('TABLE' in parts) await this.table.update(parts.TABLE);
    if ('CHART' in parts || 'MODE' in parts) await this.chart.update(this.screen.chart);
  }

  /** Применяет один эффект только после дедупликации транспорта. */
  async effect(effect) {
    switch (effect.type) {
      case 'screen':
        this.screen.revision = effect.revision;
        if (this.mainPending) { this.mainPending = false; document.getElementById('main').hidden = false; await this.renderMain(); }
        await this.render(effect.parts); break;
      case 'form.open': {
        const window = effect.window; if (this.windows.has(window.id)) break;
        const form = new FormWindow(this, window); this.windows.set(window.id, form); await form.show(); this.syncModality();
        if (form.spec.presentation === 'FILE_BROWSER' && form.showing()) {
          if (window.chooserRequest && !this.chooserRequests.has(window.id)) {
            const {kind, mode, title, filter, folder, name} = window.chooserRequest;
            this.chooserRequests.set(window.id, Object.freeze({kind, mode, title, filter, folder, name}));
          }
          this.testDriver?.chooserOpened();
        }
        break;
      }
      case 'form.view': this.windows.get(effect.windowId)?.update(effect.view, effect.echoOf); break;
      case 'form.close': this.windows.get(effect.windowId)?.close(); this.windows.delete(effect.windowId); this.syncModality(); break;
      case 'form.front': this.windows.get(effect.windowId)?.node.focus(); break;
      case 'alert.open': {
        if (this.alerts.has(effect.alertId)) break;
        const alert = new AlertWindow(this, effect.alertId, effect.spec, effect.placement); this.alerts.set(effect.alertId, alert); await alert.show(); this.syncModality(); break;
      }
      case 'alert.update': this.alerts.get(effect.alertId)?.update(effect.spec); break;
      case 'alert.close': this.alerts.get(effect.alertId)?.close(); this.alerts.delete(effect.alertId); this.syncModality(); break;
      case 'contextMenu': {
        if (effect.tab !== this.transport.tab) break;
        const target = effect.target;
        if (target.rowId) await this.table.ensureVisible(target.rowId);
        const row = [...document.querySelectorAll('.table-row')].find(n => n.dataset.cpId === target.rowId || n.getAttribute('aria-selected') === 'true');
        const anchor = target.kind === 'card' ? [...document.querySelectorAll('.card')].find(n => n.dataset.cpId === target.cardId) : row?.querySelector('[data-cp-id=title]') || row;
        const rect = bounds(anchor || document.getElementById('center'));
        await this.menus.context(target, rect.x, rect.y + rect.height, effect.items); break;
      }
      case 'focus': {
        const ids = {TABLE: 'table', CHART: 'chart', MENU_BAR: 'menuBar'};
        if (effect.target === 'FILTER') this.filter?.focus();
        else if (effect.target === 'MENU_BAR') document.querySelector('#menuBar button')?.focus();
        else document.getElementById(ids[effect.target])?.focus(); break;
      }
      case 'reveal':
        // Выделение уже задано screen-моделью ядра; из обработки эффекта не посылаем встречный intent.
        if (effect.mode === 'SELECT_AND_SCROLL') await this.table.ensureVisible(effect.rowId);
        else await this.table.reveal(effect.rowId, false);
        break;
      case 'clipboard': await this.clipboard(effect.text); break;
      case 'inert':
        this.serverInert = effect.value;
        if (!effect.value && this.mainPending) { this.mainPending = false; document.getElementById('main').hidden = false; await this.renderMain(); }
        this.syncModality(); break;
      case 'reload': if (effect.tab === this.transport.tab) location.reload(); break;
      case 'exit': this.transport.stop(); showScreen(this, effect.kind === 'WEB_CRASHED' ? 'crashed' : 'stopped', effect.title, effect.text); break;
      case 'test.step':
        if (this.testApi) (await this.installDriver()).step(effect);
        break;
      default: throw new Error(`Unknown effect: ${effect.type}`);
    }
  }

  /** Выражает блокировку вложенной модальности в живых DOM-свойствах. */
  syncModality() {
    const dialogs = [...document.querySelectorAll('dialog[open], .quick-edit')];
    const top = dialogs.filter(node => node.matches(':modal')).at(-1);
    document.getElementById('main').inert = this.serverInert || !!top;
    for (const node of dialogs) node.inert = !!top && node !== top;
  }

  /** Копирует эффект в буфер с запасным DOM-путём. */
  async clipboard(text) {
    try { await navigator.clipboard.writeText(text); }
    catch {
      const input = document.createElement('textarea'); input.value = text; input.style.position = 'fixed'; input.style.left = '-10000px';
      const owner = [...document.querySelectorAll('dialog[open]')].at(-1) || document.body; owner.append(input);
      const focused = document.activeElement; input.select(); document.execCommand('copy'); input.remove(); focused?.focus();
    }
  }

  /** Показывает только предоставленные ядром тексты потери связи. */
  offline(error) {
    this.lastTransportError = error;
    if (this.transport.stopped) return;
    showScreen(this, 'offline', this.texts['offline.title'], this.texts['offline.text'], true);
  }

  /** Передаёт необработанную ошибку в общий поток сообщений ядра. */
  clientError(error) {
    if (this.reportingError) return; this.reportingError = true;
    this.send({type: 'clientError', message: String(error?.message || error), stack: String(error?.stack || '')}).finally(() => { this.reportingError = false; }).catch(() => {});
  }
}

const app = new Application();
app.start();
