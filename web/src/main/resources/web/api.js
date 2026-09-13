/**
 * @file Обёртка над fetch для JSON API сервера CashPrediction.
 *
 * Токен сеанса приходит в адресе страницы (?t=…), сохраняется в sessionStorage и убирается из адресной строки
 * (чтобы не попасть в историю и закладки). Каждый запрос несёт его в заголовке X-Token; ссылки скачивания
 * и navigator.sendBeacon, которые заголовков не поддерживают, — в параметре t.
 */

const TOKEN_KEY = 'cashprediction.token';

/** Токен текущего сеанса. */
let token = '';

/** Обработчик «сервер недоступен» (задаёт app.js). */
let onServerDown = () => {};

/**
 * Ошибка ответа API: статус, русское сообщение сервера, вид конфликта (409) и стек (500).
 */
export class ApiError extends Error {
  /**
   * @param {number} status HTTP-статус (0 — сервер недоступен)
   * @param {string} message сообщение
   * @param {object} [body] тело ответа
   */
  constructor(status, message, body = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.conflict = body.conflict || null;
    this.details = body.details || null;
    this.body = body;
  }
}

/**
 * Читает токен из адреса страницы или sessionStorage.
 * @returns {string} токен ('' — нет токена)
 */
export function initToken() {
  const params = new URLSearchParams(location.search);
  const fromUrl = params.get('t');
  if (fromUrl) {
    token = fromUrl;
    try {
      sessionStorage.setItem(TOKEN_KEY, fromUrl);
      // Токен в памяти вкладки есть — убираем его из адресной строки.
      params.delete('t');
      const query = params.toString();
      history.replaceState(null, '', location.pathname + (query ? `?${query}` : '') + location.hash);
    } catch {
      // sessionStorage недоступен: токен остаётся в адресе, перезагрузка страницы всё равно сработает.
    }
  } else {
    try {
      token = sessionStorage.getItem(TOKEN_KEY) || '';
    } catch {
      token = '';
    }
  }
  return token;
}

/**
 * Задаёт реакцию на недоступность сервера (сеть оборвалась, сервер остановлен).
 * @param {(error: ApiError) => void} fn обработчик
 */
export function setServerDownHandler(fn) {
  onServerDown = fn;
}

/**
 * Адрес API с токеном в параметре (для ссылок скачивания и sendBeacon).
 * @param {string} path путь (/api/...)
 * @param {object} [params] параметры строки запроса
 * @returns {string} адрес
 */
export function apiUrl(path, params = {}) {
  const search = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null) search.set(k, String(v));
  }
  search.set('t', token);
  return `${path}?${search.toString()}`;
}

/**
 * Выполняет запрос и возвращает JSON.
 * @param {string} method HTTP-метод
 * @param {string} path путь
 * @param {object} [options] {params, body, keepalive}
 * @returns {Promise<object>} тело ответа
 * @throws {ApiError} при статусе не 2xx или недоступности сервера
 */
async function request(method, path, { params, body, keepalive } = {}) {
  const search = new URLSearchParams();
  for (const [k, v] of Object.entries(params || {})) {
    if (v !== undefined && v !== null) search.set(k, String(v));
  }
  const url = search.toString() ? `${path}?${search}` : path;
  let response;
  try {
    response = await fetch(url, {
      method,
      headers: { 'X-Token': token, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      keepalive: !!keepalive,
      cache: 'no-store',
    });
  } catch (e) {
    const error = new ApiError(0, 'Сервер CashPrediction недоступен. Возможно, он остановлен.');
    onServerDown(error);
    throw error;
  }
  let data = {};
  const text = await response.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { error: text };
    }
  }
  if (!response.ok) {
    throw new ApiError(response.status, data.error || `Ошибка ${response.status}`, data);
  }
  return data;
}

/**
 * API-клиент: get/post/put/del возвращают Promise с JSON-ответом.
 */
export const api = {
  /**
   * GET-запрос.
   * @param {string} path путь
   * @param {object} [params] параметры
   * @returns {Promise<object>} ответ
   */
  get: (path, params) => request('GET', path, { params }),
  /**
   * POST-запрос.
   * @param {string} path путь
   * @param {object} [body] тело
   * @returns {Promise<object>} ответ
   */
  post: (path, body = {}) => request('POST', path, { body }),
  /**
   * PUT-запрос.
   * @param {string} path путь
   * @param {object} [body] тело
   * @param {boolean} [keepalive] не прерывать при закрытии страницы
   * @returns {Promise<object>} ответ
   */
  put: (path, body = {}, keepalive = false) => request('PUT', path, { body, keepalive }),
  /**
   * DELETE-запрос.
   * @param {string} path путь
   * @param {object} [params] параметры
   * @param {object} [body] тело
   * @returns {Promise<object>} ответ
   */
  del: (path, params, body) => request('DELETE', path, { params, body }),

  /**
   * Отправляет «маяк» при закрытии вкладки (navigator.sendBeacon не ждёт ответа и переживает выгрузку страницы).
   * @param {string} path путь
   */
  beacon(path) {
    try {
      navigator.sendBeacon(apiUrl(path), new Blob(['{}'], { type: 'application/json' }));
    } catch {
      // Браузер без sendBeacon: снимок всё равно пишется сервером по таймеру.
    }
  },

  /** @returns {boolean} есть ли токен */
  hasToken: () => !!token,
};
