/**
 * @file Мелкие помощники без зависимостей: построение DOM, отложенный вызов, разбор и формат денег и дат
 * в канонических формах снимка сессии (деньги "95000,00", даты ISO), скачивание, буфер обмена.
 */

const SVG_NS = 'http://www.w3.org/2000/svg';

/**
 * Создаёт HTML-элемент.
 * Свойства: class, text, html запрещён (только текст - защита от XSS), style (строка или объект), dataset (объект),
 * on<Событие> (функция-обработчик), остальные - атрибуты; значения false/null/undefined пропускаются.
 * @param {string} tag имя тега
 * @param {object} [props] свойства и атрибуты
 * @param {...(Node|string|number|null|undefined|Array)} children дочерние узлы или текст
 * @returns {HTMLElement} элемент
 */
export function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  applyProps(el, props);
  appendChildren(el, children);
  return el;
}

/**
 * Создаёт SVG-элемент с атрибутами.
 * @param {string} tag имя SVG-тега
 * @param {object} [attrs] атрибуты (class, text и on<Событие> - как у h)
 * @param {...(Node|string|null|Array)} children дочерние узлы
 * @returns {SVGElement} элемент
 */
export function svg(tag, attrs = {}, ...children) {
  const el = document.createElementNS(SVG_NS, tag);
  applyProps(el, attrs);
  appendChildren(el, children);
  return el;
}

/**
 * Применяет свойства к элементу (общая часть h и svg).
 * @param {Element} el элемент
 * @param {object} props свойства
 */
function applyProps(el, props) {
  for (const [key, value] of Object.entries(props || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') {
      el.setAttribute('class', value);
    } else if (key === 'text') {
      el.textContent = String(value);
    } else if (key === 'style' && typeof value === 'object') {
      Object.assign(el.style, value);
    } else if (key === 'dataset') {
      Object.assign(el.dataset, value);
    } else if (key.startsWith('on') && typeof value === 'function') {
      el.addEventListener(key.slice(2).toLowerCase(), value);
    } else if (key === 'value' && 'value' in el) {
      el.value = value;
    } else if (key === 'checked' && 'checked' in el) {
      el.checked = !!value;
    } else {
      el.setAttribute(key, value === true ? '' : String(value));
    }
  }
}

/**
 * Добавляет детей (массивы разворачиваются, строки становятся текстом).
 * @param {Element} el родитель
 * @param {Array} children дети
 */
function appendChildren(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

/**
 * Удаляет всех детей элемента.
 * @param {Element} el элемент
 */
export function clear(el) {
  while (el.firstChild) el.firstChild.remove();
}

/**
 * Отложенный вызов: функция выполнится через ms после последнего обращения.
 * У результата есть flush() - выполнить немедленно, если вызов ожидается, и cancel().
 * @param {Function} fn функция
 * @param {number} ms задержка, мс
 * @returns {Function & {flush: Function, cancel: Function}} обёртка
 */
export function debounce(fn, ms) {
  let timer = null;
  let lastArgs = null;
  /**
   * Запоминает аргументы и переносит вызов на ms миллисекунд после последнего обращения.
   * @param {...any} args аргументы функции
   */
  const wrapped = (...args) => {
    lastArgs = args;
    clearTimeout(timer);
    timer = setTimeout(() => {
      timer = null;
      fn(...lastArgs);
    }, ms);
  };
  wrapped.flush = () => {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
      return fn(...lastArgs);
    }
    return undefined;
  };
  wrapped.cancel = () => {
    clearTimeout(timer);
    timer = null;
  };
  return wrapped;
}

// ------------------------------------------------------------------ деньги

/**
 * Разбирает сумму так же терпимо, как Money.parse ядра: пробелы-разделители тысяч, запятая или точка.
 * @param {string} text текст ("95 000,5", "-1200")
 * @returns {number|null} сумма в копейках или null, если текст не сумма
 */
export function parseMoney(text) {
  if (text === null || text === undefined) return null;
  const s = String(text).replace(/[\s  ]/g, '').replace(',', '.');
  const m = /^([+-]?)(\d{1,13})(?:\.(\d{0,2}))?$/.exec(s);
  if (!m) return null;
  const minor = Number(m[2]) * 100 + Number((m[3] || '').padEnd(2, '0') || 0);
  return m[1] === '-' ? -minor : minor;
}

/**
 * Каноническая форма суммы для снимка: "95000,00"; некорректный текст - как введён.
 * @param {string} text введённый текст
 * @returns {string} каноническое значение
 */
export function canonicalMoney(text) {
  const t = String(text ?? '');
  if (t.trim() === '') return '';
  const minor = parseMoney(t);
  return minor === null ? t : formatPlain(minor);
}

/**
 * Сумма в копейках → "95000,00" (Money.formatPlain).
 * @param {number} minor копейки
 * @returns {string} текст
 */
export function formatPlain(minor) {
  const sign = minor < 0 ? '-' : '';
  const abs = Math.abs(minor);
  return `${sign}${Math.floor(abs / 100)},${String(abs % 100).padStart(2, '0')}`;
}

/**
 * Сумма в копейках → "95 000,00" (Money.format).
 * @param {number} minor копейки
 * @returns {string} текст
 */
export function formatMoney(minor) {
  const sign = minor < 0 ? '-' : '';
  const abs = Math.abs(minor);
  const major = String(Math.floor(abs / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  return `${sign}${major},${String(abs % 100).padStart(2, '0')}`;
}

/**
 * Каноническое значение суммы для показа в поле: "95000,00" → "95 000,00"; иное - как есть.
 * @param {string} value каноническое значение или введённый текст
 * @returns {string} текст для поля
 */
export function displayMoney(value) {
  const v = String(value ?? '');
  return /^-?\d+,\d{2}$/.test(v) ? formatMoney(parseMoney(v)) : v;
}

/**
 * Короткая подпись оси графика: 150 000 → «150 тыс.», 1 500 000 → «1,5 млн».
 * @param {number} minor копейки
 * @returns {string} подпись
 */
export function shortMoney(minor) {
  const major = minor / 100;
  const abs = Math.abs(major);
  if (abs >= 1e6) return `${(major / 1e6).toFixed(abs >= 1e7 ? 0 : 1).replace('.', ',')} млн`;
  if (abs >= 1e3) return `${Math.round(major / 1e3)} тыс.`;
  return String(Math.round(major));
}

// ------------------------------------------------------------------ даты

/**
 * Разбирает дату ISO (ГГГГ-ММ-ДД) или русскую (ДД.ММ.ГГГГ).
 * @param {string} text текст
 * @returns {string|null} ISO-дата или null
 */
export function parseDate(text) {
  const s = String(text ?? '').trim();
  let y;
  let mo;
  let d;
  let m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s);
  if (m) {
    [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  } else {
    m = /^(\d{1,2})\.(\d{1,2})\.(\d{4})$/.exec(s);
    if (!m) return null;
    [d, mo, y] = [Number(m[1]), Number(m[2]), Number(m[3])];
  }
  const date = new Date(Date.UTC(y, mo - 1, d));
  if (date.getUTCFullYear() !== y || date.getUTCMonth() !== mo - 1 || date.getUTCDate() !== d) return null;
  return `${String(y).padStart(4, '0')}-${String(mo).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

/**
 * Каноническая форма даты для снимка: ISO; пусто - ""; некорректный текст - как введён.
 * @param {string} text введённый текст
 * @returns {string} значение
 */
export function canonicalDate(text) {
  const t = String(text ?? '');
  if (t.trim() === '') return '';
  return parseDate(t) ?? t;
}

/**
 * ISO-дата → ДД.ММ.ГГГГ; иное - как есть.
 * @param {string} iso дата
 * @returns {string} текст
 */
export function ruDate(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso ?? ''));
  return m ? `${m[3]}.${m[2]}.${m[1]}` : String(iso ?? '');
}

/**
 * Число дней от эпохи для ISO-даты (для координат графика).
 * @param {string} iso дата
 * @returns {number} дни
 */
export function dayNumber(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return Math.round(Date.UTC(y, m - 1, d) / 86400000);
}

/**
 * Обратная к dayNumber: номер дня → ISO-дата.
 * @param {number} day номер дня
 * @returns {string} ISO-дата
 */
export function isoFromDay(day) {
  return new Date(day * 86400000).toISOString().slice(0, 10);
}

/** Названия месяцев в именительном падеже. */
export const MONTHS = ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь', 'Июль', 'Август', 'Сентябрь',
  'Октябрь', 'Ноябрь', 'Декабрь'];

/** Короткие названия месяцев для оси графика. */
export const MONTHS_SHORT = ['янв', 'фев', 'мар', 'апр', 'май', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];

/** Дни недели: значение перечисления DayOfWeek → подпись. */
export const WEEKDAYS = [
  ['MONDAY', 'понедельник'], ['TUESDAY', 'вторник'], ['WEDNESDAY', 'среда'], ['THURSDAY', 'четверг'],
  ['FRIDAY', 'пятница'], ['SATURDAY', 'суббота'], ['SUNDAY', 'воскресенье'],
];

/**
 * Русское склонение числительного: plural(3, 'месяц', 'месяца', 'месяцев') → 'месяца'.
 * @param {number} n число
 * @param {string} one форма для 1
 * @param {string} few форма для 2-4
 * @param {string} many форма для 5-20
 * @returns {string} форма
 */
export function plural(n, one, few, many) {
  const a = Math.abs(n) % 100;
  const b = a % 10;
  if (a > 10 && a < 20) return many;
  if (b === 1) return one;
  if (b >= 2 && b <= 4) return few;
  return many;
}

// ------------------------------------------------------------------ прочее

/**
 * Скачивает файл по адресу (ссылка с атрибутом download) - web-аналог FileChooser.showSaveDialog.
 * @param {string} url адрес (с токеном)
 */
export function downloadUrl(url) {
  const a = h('a', { href: url, download: '', style: 'display:none' });
  document.body.append(a);
  a.click();
  a.remove();
}

/**
 * Скачивает текст как файл (Blob).
 * @param {string} text содержимое
 * @param {string} fileName имя файла
 * @param {string} type MIME-тип
 */
export function downloadText(text, fileName, type) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = h('a', { href: url, download: fileName, style: 'display:none' });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

/**
 * Копирует текст в буфер обмена (с запасным способом для старых браузеров).
 * @param {string} text текст
 * @returns {Promise<void>} завершение
 */
export async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const area = h('textarea', { style: 'position:fixed;left:-9999px' });
    area.value = text;
    document.body.append(area);
    area.select();
    document.execCommand('copy');
    area.remove();
  }
}

/**
 * Безопасное чтение localStorage (приватный режим может запрещать доступ).
 * @param {string} key ключ
 * @param {string} fallback значение по умолчанию
 * @returns {string} значение
 */
export function readLocal(key, fallback) {
  try {
    return localStorage.getItem(key) ?? fallback;
  } catch {
    return fallback;
  }
}

/**
 * Безопасная запись localStorage (только удобства конкретного браузера, не данные плана).
 * @param {string} key ключ
 * @param {string} value значение
 */
export function writeLocal(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Хранилище недоступно - удобство просто не запомнится.
  }
}

/** Наибольшая длина имени плана (PlanValidator.MAX_NAME_LENGTH в ядре). */
export const MAX_PLAN_NAME_LENGTH = 80;

/** Символы, запрещённые в именах файлов Windows (PlanValidator.FORBIDDEN_CHARS). */
const FORBIDDEN_NAME_CHARS = '\\/:*?"<>|';

/** Имена служебных файлов CashMemory, которые нельзя занять планом (PlanValidator.RESERVED_NAMES). */
const RESERVED_PLAN_NAMES = new Set(['settings', 'web-session', 'web-session.plan', 'session-fx', 'session-swing']);

/** Имена устройств Windows: файл «CON.md» создать нельзя (PlanValidator.WINDOWS_DEVICE_NAMES). */
const WINDOWS_DEVICE_NAMES = new Set(['con', 'prn', 'aux', 'nul',
  'com1', 'com2', 'com3', 'com4', 'com5', 'com6', 'com7', 'com8', 'com9',
  'lpt1', 'lpt2', 'lpt3', 'lpt4', 'lpt5', 'lpt6', 'lpt7', 'lpt8', 'lpt9']);

/**
 * Проверяет имя плана (оно же имя файла CashMemory/<имя>.md) по тем же правилам, что PlanValidator.checkPlanName
 * в ядре. Проверка повторена в браузере, чтобы ошибка показывалась сразу при вводе, как в JavaFX и Swing, а не только
 * после ответа сервера; окончательно имя всё равно проверяет сервер.
 * @param {string} name имя плана
 * @returns {string} текст ошибки на русском или '' - имя допустимо
 */
export function planNameProblem(name) {
  const n = String(name ?? '').trim();
  if (!n) return 'Имя плана не может быть пустым';
  const chars = [...n];
  if (chars.length > MAX_PLAN_NAME_LENGTH) return `Имя плана длиннее ${MAX_PLAN_NAME_LENGTH} символов`;
  if (chars.some((ch) => FORBIDDEN_NAME_CHARS.includes(ch))) {
    return 'Имя плана не может содержать символы \\ / : * ? " < > |';
  }
  // Управляющие символы (как Character.isISOControl в Java) недопустимы в именах файлов.
  if (chars.some((ch) => {
    const code = ch.codePointAt(0);
    return code < 32 || (code >= 127 && code <= 159);
  })) {
    return 'Имя плана не может содержать управляющие символы';
  }
  // Windows молча отбрасывает точку в конце имени файла, и файл не нашёлся бы по имени плана.
  if (n.endsWith('.')) return 'Имя плана не может заканчиваться точкой';
  const lower = n.toLowerCase();
  if (RESERVED_PLAN_NAMES.has(lower)) return `Имя «${n}» зарезервировано программой для служебного файла`;
  if (WINDOWS_DEVICE_NAMES.has(lower)) return `Имя «${n}» зарезервировано Windows`;
  return '';
}
