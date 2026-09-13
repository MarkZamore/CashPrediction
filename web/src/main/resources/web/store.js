/**
 * @file Общее состояние тонкого клиента и реестры, через которые модули общаются без циклических импортов.
 *
 * Сервер — единственный источник истины: store.state всегда равно последнему ответу GET /api/state (или ответу
 * любой правки). Модули подписываются на изменения через store.on() и перерисовываются.
 * Реестр commands заполняет commands.js, реестр windowFactories — editors.js/commands.js
 * (фабрики восстанавливаемых окон по WindowType, аналог WindowFactory ядра).
 */

/** Слушатели изменения состояния. */
const listeners = [];

/** Ожидающие показа окна: id окна → функция resolve. */
const shownWaiters = new Map();

/**
 * Хранилище состояния клиента.
 */
export const store = {
  /** Последнее состояние с сервера (или null до первой загрузки). */
  state: null,
  /** Выделенная строка таблицы (rowId прогноза). */
  selectedRowId: '',
  /** Части главного окна, созданные app.js: {table, chart, summary, toolbar, filterInput, updateStatus}. */
  ui: {},
  /** Сервер остановлен из браузера («Выход») — экран «нет связи» не показывается. */
  stopped: false,
  /** Сервер остановлен аварийно из меню «Симулировать сбой». */
  crashSimulated: false,

  /**
   * Заменяет состояние и оповещает подписчиков.
   * @param {object} state новое состояние с сервера
   */
  set(state) {
    if (!state || typeof state !== 'object' || !state.plan) {
      return;
    }
    this.state = state;
    for (const fn of listeners.slice()) {
      fn(state);
    }
  },

  /**
   * Подписывает функцию на изменения состояния.
   * @param {(state: object) => void} fn слушатель
   * @returns {() => void} функция отписки
   */
  on(fn) {
    listeners.push(fn);
    return () => {
      const i = listeners.indexOf(fn);
      if (i >= 0) listeners.splice(i, 1);
    };
  },

  /**
   * Строка прогноза по идентификатору среди видимых строк.
   * @param {string} rowId идентификатор строки
   * @returns {object|null} строка или null
   */
  row(rowId) {
    const rows = this.state?.forecast?.rows || [];
    return rows.find((r) => r.rowId === rowId) || null;
  },

  /** @returns {object|null} выделенная строка прогноза */
  selectedRow() {
    return this.selectedRowId ? this.row(this.selectedRowId) : null;
  },

  /**
   * Регулярная операция плана по идентификатору.
   * @param {string} id идентификатор правила ("r1")
   * @returns {object|null} правило в JSON-форме PlanJson.rule
   */
  rule(id) {
    return (this.state?.plan?.rules || []).find((r) => r.id === id) || null;
  },

  /**
   * Разовая операция плана по идентификатору.
   * @param {string} id идентификатор ("t1")
   * @returns {object|null} операция
   */
  oneTime(id) {
    return (this.state?.plan?.oneTimes || []).find((t) => t.id === id) || null;
  },

  /**
   * Корректировка события.
   * @param {string} ruleId идентификатор правила
   * @param {string} originalDate номинальная дата (ISO)
   * @returns {object|null} корректировка или null
   */
  adjustment(ruleId, originalDate) {
    return (this.state?.plan?.adjustments || [])
      .find((a) => a.ruleId === ruleId && a.originalDate === originalDate) || null;
  },
};

/**
 * Реестр команд приложения: имя → функция. Заполняется в commands.js; меню, тулбар, горячие клавиши
 * и контекстные меню вызывают команды только по имени.
 */
export const commands = {};

/**
 * Выполняет команду по имени; ошибки команд обрабатывает сама команда (обёртка run в commands.js).
 * @param {string} name имя команды
 * @param {...any} args аргументы
 * @returns {Promise<any>|any} результат команды
 */
export function cmd(name, ...args) {
  const fn = commands[name];
  if (!fn) {
    throw new Error(`Неизвестная команда «${name}»`);
  }
  return fn(...args);
}

/**
 * Фабрики восстанавливаемых окон: WindowType → (windowState) => Promise.
 * Аналог WindowFactory ядра: по сохранённому на сервере состоянию окна открывает такой же диалог.
 */
export const windowFactories = {};

/**
 * Сообщает, что окно показано (его ждёт последовательное восстановление окон).
 * @param {string} id идентификатор окна
 */
export function notifyShown(id) {
  const resolve = shownWaiters.get(id);
  if (resolve) {
    shownWaiters.delete(id);
    resolve(true);
  }
}

/**
 * Ждёт показа окна с данным идентификатором (не дольше timeoutMs).
 * @param {string} id идентификатор окна
 * @param {number} [timeoutMs=4000] таймаут
 * @returns {Promise<boolean>} true — показано, false — таймаут
 */
export function waitShown(id, timeoutMs = 4000) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      shownWaiters.delete(id);
      resolve(false);
    }, timeoutMs);
    shownWaiters.set(id, (value) => {
      clearTimeout(timer);
      resolve(value);
    });
  });
}
