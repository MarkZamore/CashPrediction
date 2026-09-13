/**
 * @file Серверная регистрация восстанавливаемых окон и их повторное открытие.
 *
 * Web-аналог связки StatefulWindow + SessionRecorder.register/unregister/touch из ядра. Каждое окно, открытое
 * в браузере (диалог, калькулятор цели, быстрая правка суммы), регистрируется на сервере
 * (POST /api/session/windows), каждое изменение поля уходит на сервер с задержкой 300 мс
 * (PUT /api/session/windows/{id}), закрытие окна — DELETE. Поэтому состояние окон хранится на сервере:
 * после перезагрузки страницы или сбоя сервера окна открываются заново с теми же введёнными значениями.
 *
 * Нативные окна выбора файла браузера и серверный обозреватель файлов не регистрируются — как FileChooser и
 * DirectoryChooser в desktop-клиентах, они не восстанавливаются (раздел 5.1 плана).
 */

import { api } from './api.js';
import { debounce } from './util.js';
import { windowFactories, notifyShown, waitShown } from './store.js';

/** Задержка отправки введённых значений на сервер, мс (раздел 5.2 плана: Web — событие input + debounce 300 мс). */
export const FIELD_SYNC_DELAY_MS = 300;

/**
 * Окно, зарегистрированное на сервере: хранит идентификатор (w1, w2…), копит изменения и отправляет их пачкой.
 * JavaFX: StatefulWindow + SessionRecorder.register → Swing: StatefulWindow + SessionRecorder.register
 * → Web: POST/PUT/DELETE /api/session/windows
 */
export class TrackedWindow {
  /**
   * @param {string} type тип окна из словаря WindowType ("RULE_EDITOR")
   * @param {object} [options] параметры
   * @param {boolean} [options.modal=true] модальное ли окно
   * @param {string} [options.ownerId='main'] владелец: main или идентификатор другого окна
   * @param {object} [options.context] контекст окна (mode, ruleId, purpose…)
   * @param {object|null} [options.existing] состояние окна с сервера (при восстановлении) — повторная регистрация не нужна
   */
  constructor(type, { modal = true, ownerId = 'main', context = {}, existing = null } = {}) {
    this.type = type;
    this.existing = existing;
    this.modal = existing ? !!existing.modal : modal;
    this.ownerId = (existing && existing.ownerId) || ownerId || 'main';
    this.context = existing ? { ...(existing.context || {}) } : { ...context };
    /** Идентификатор окна на сервере (null до ответа на регистрацию). */
    this.id = null;
    /** Promise с идентификатором окна (null — регистрация не удалась). */
    this.ready = null;
    this.closed = false;
    this.serverClosed = false;
    this.deleted = false;
    this.pendingFields = null;
    this.pendingContext = null;
    this.pendingBounds = null;
    /** Последний запрос отправки полей: применение диалога ждёт его, прежде чем закрыть окно на сервере. */
    this.inflight = null;
    this.sync = debounce(() => this.send(), FIELD_SYNC_DELAY_MS);
  }

  /**
   * Регистрирует окно на сервере в момент показа (для восстановленного окна — просто берёт его идентификатор).
   * @param {object} [fields] начальные значения полей в канонической форме
   * @param {object|null} [bounds] границы окна {x, y, width, height}
   * @returns {Promise<string|null>} идентификатор окна
   */
  register(fields = {}, bounds = null) {
    if (this.ready) {
      return this.ready;
    }
    if (this.existing) {
      this.id = this.existing.id;
      this.ready = Promise.resolve(this.id);
      return this.ready;
    }
    this.ready = api.post('/api/session/windows', {
      type: this.type,
      modal: this.modal,
      ownerId: this.ownerId,
      context: this.context,
      fields,
      bounds,
    }).then((res) => {
      this.id = res.id;
      if (this.closed) {
        // Окно закрыли раньше, чем пришёл ответ: убираем его и на сервере.
        this.deleteOnServer();
      } else if (this.pendingFields || this.pendingContext || this.pendingBounds) {
        this.sync();
      }
      return this.id;
    }).catch(() => null); // Без регистрации окно работает, просто не попадёт в снимок сессии.
    return this.ready;
  }

  /**
   * Запоминает изменённые поля и отправляет их на сервер через 300 мс после последнего изменения.
   * @param {object} fields поля в канонической форме (деньги "95000,00", даты ISO, "true"/"false")
   */
  update(fields) {
    if (this.closed) return;
    this.pendingFields = { ...(this.pendingFields || {}), ...fields };
    this.sync();
  }

  /**
   * Меняет контекст окна (например, номер страницы мастера).
   * @param {object} context изменённые ключи контекста
   */
  setContext(context) {
    if (this.closed) return;
    Object.assign(this.context, context);
    this.pendingContext = { ...(this.pendingContext || {}), ...context };
    this.sync();
  }

  /**
   * Запоминает новые границы окна (после перетаскивания).
   * @param {{x:number, y:number, width:number, height:number}} bounds границы
   */
  setBounds(bounds) {
    if (this.closed) return;
    this.pendingBounds = bounds;
    this.sync();
  }

  /** Отправляет накопленные изменения (вызывается отложенно). */
  send() {
    if (!this.id || this.closed) return;
    const body = {};
    if (this.pendingFields) body.fields = this.pendingFields;
    if (this.pendingContext) body.context = this.pendingContext;
    if (this.pendingBounds) body.bounds = this.pendingBounds;
    this.pendingFields = null;
    this.pendingContext = null;
    this.pendingBounds = null;
    if (Object.keys(body).length === 0) return;
    // Запрос запоминается: правка с windowId уходит только после него, иначе поля могли бы прийти на сервер
    // уже после закрытия окна (ответ 404 и ошибка в консоли браузера).
    this.inflight = api.put(`/api/session/windows/${encodeURIComponent(this.id)}`, body).catch(() => {});
  }

  /**
   * Немедленно отправляет ожидающие изменения (например, перед применением диалога) и ждёт ответа сервера.
   * @returns {Promise<void>} завершение последней отправки полей
   */
  async flush() {
    this.sync.flush();
    if (this.inflight) await this.inflight;
  }

  /**
   * Окно закрыто: удаляет его на сервере (кроме случая, когда сервер уже закрыл его сам — правка с windowId).
   * @param {object} [options] параметры
   * @param {boolean} [options.serverClosed=false] сервер уже закрыл окно в запросе правки
   * @returns {Promise<void>} завершение
   */
  async close({ serverClosed = false } = {}) {
    if (this.closed) return;
    this.closed = true;
    this.serverClosed = serverClosed;
    this.sync.cancel();
    if (serverClosed) return;
    const id = await this.ready;
    if (id) {
      await this.deleteOnServer();
    }
  }

  /**
   * Удаляет окно на сервере один раз.
   * @returns {Promise<void>} завершение
   */
  deleteOnServer() {
    if (!this.id || this.serverClosed || this.deleted) {
      return Promise.resolve();
    }
    this.deleted = true;
    return api.del(`/api/session/windows/${encodeURIComponent(this.id)}`).then(() => {}, () => {});
  }
}

/**
 * Фабрика окна по его сохранённому состоянию: сначала «ТИП:назначение» (TEXT_INPUT:rename), затем просто ТИП.
 * @param {object} win состояние окна с сервера {id, type, context, fields}
 * @returns {Function|null} фабрика или null
 */
function factoryFor(win) {
  const purpose = win.context && win.context.purpose;
  return (purpose && windowFactories[`${win.type}:${purpose}`]) || windowFactories[win.type] || null;
}

/**
 * Окно не удалось открыть заново: снимает ожидание показа и удаляет окно на сервере, чтобы снимок не держал «призрак».
 * @param {object} win состояние окна
 * @returns {Promise<void>} завершение
 */
export function abandonWindow(win) {
  notifyShown(win.id);
  return api.del(`/api/session/windows/${encodeURIComponent(win.id)}`).then(() => {}, () => {});
}

/**
 * Открывает заново окна, которые хранит сервер: сначала немодальные, затем модальные, в порядке открытия
 * (порядок раздела 5.6 плана). Следующее окно открывается после показа предыдущего — как цепочка invokeLater в Swing.
 * Аналог WindowFactory.create + applyState + DialogHost.show в RestoreCoordinator.
 * @param {Array<object>} windows окна {id, type, modal, ownerId, context, fields, bounds}
 * @returns {Promise<number>} сколько окон открыто
 */
export async function restoreWindows(windows) {
  const list = Array.isArray(windows) ? windows : [];
  const ordered = [...list.filter((w) => !w.modal), ...list.filter((w) => w.modal)];
  let restored = 0;
  for (const win of ordered) {
    const factory = factoryFor(win);
    if (!factory) {
      await abandonWindow(win);
      continue;
    }
    // Ожидание ставится ДО вызова фабрики: окно может показаться синхронно.
    const shown = waitShown(win.id);
    let result;
    try {
      result = factory(win);
    } catch (e) {
      console.error('Окно не восстановлено', win, e);
      result = false;
    }
    if (result === false) {
      await abandonWindow(win);
      continue;
    }
    if (await shown) {
      restored += 1;
    }
  }
  return restored;
}
