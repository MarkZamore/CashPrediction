/** @file Общие PNG ядра и семантические подписи значков. */
import {icons} from './icons.js';

const colors = new Set(['ACCENT', 'WHATIF', 'EXPENSE', 'INCOME', 'TEXT_PRIMARY', 'TEXT_MUTED', 'WARN', 'TOOLTIP_TEXT', 'TEXT_PAST']);
let captureSources = null;

/** Подключает test-api наблюдение до создания значков; обычная вкладка не загружает этот код. */
export async function enableRetainedIconCapture() {
  if (captureSources) return captureSources;
  const [{createRetainedImageRegistry}, {createRetainedIconSources}] = await Promise.all([
    import('./paint-observation.js'), import('./retained-icon-sources.js')
  ]);
  const registry = createRetainedImageRegistry();
  const sources = createRetainedIconSources({registry, urls: [...new Set(Object.values(icons))]
    .filter(/** Оставляет опубликованные общие PNG, не включая ICO брендинга. */ url => url.endsWith('.png'))});
  captureSources = {registry, sources};
  return captureSources;
}

/** Читает состояние настоящих назначений PNG, не принимая его за готовность кадра. */
export function retainedIconCapture() { return captureSources; }

/** Назначает источник в момент исходного создания/перекраски, а не после отображения значка. */
function installIconSource(node, source, background = false) {
  if (captureSources) {
    if (background) captureSources.sources.installBackground(node, source);
    else captureSources.sources.installImage(node, source);
  } else if (background) node.style.backgroundImage = `url("${source}")`;
  else node.src = source;
}

/** Выбирает опубликованный ядром цветной вариант, сохраняя исходный PNG при отсутствии варианта. */
export function iconUrl(key, color = 'TEXT_PRIMARY') {
  const base = icons[key];
  if (!base || !colors.has(color)) return base;
  const alias = base.slice(base.lastIndexOf('/') + 1, -4) + '-' + color.toLowerCase();
  return icons[alias] || base;
}

/** Перекрашивает только изображения значков, не затрагивая логический текст. */
export function iconColor(node, color = 'TEXT_PRIMARY') {
  node.dataset.iconColor = color;
  for (const image of node.querySelectorAll('img.shared-icon')) installIconSource(image, iconUrl(image.dataset.iconKey, color));
}

/** Находит отдельные служебные токены только в выделенном моделью поле отметок. */
export function serviceMarks(text, keys) {
  text = String(text ?? '');
  const positions = [];
  for (let offset = 0; offset < text.length;) {
    const key = keys.find(/** Проверяет наличие опубликованного значка и границы отдельного служебного токена. */ key => key !== '\u20bd' && icons[key] && text.startsWith(key, offset)
      && (offset === 0 || /\s/.test(text[offset - 1]))
      && (offset + key.length === text.length || /\s/.test(text[offset + key.length])));
    if (key) positions.push({offset, key});
    offset += String.fromCodePoint(text.codePointAt(offset)).length;
  }
  return positions;
}

/**
 * Заменяет только явно обозначенные значки, сохраняя исходный текст для дампа и доступности.
 * Массив {offset, key} задаёт позиции в единицах UTF-16 исходной строки; пустой массив запрещает подстановку.
 */
export function iconText(node, text = '', decorated = false, imageKey = null) {
  text = String(text ?? '');
  node.replaceChildren();
  node.dataset.semanticText = text;
  if (text) node.setAttribute('aria-label', text); else node.removeAttribute('aria-label');
  const keys = Object.keys(icons).filter(/** Оставляет не ASCII ключи значков, исключая знак рубля. */ key => !/^[\x00-\x7f]+$/.test(key) && key !== '\u20bd').sort(/** Сортирует ключи по убыванию длины для приоритета более длинного значка. */ (a, b) => b.length - a.length);
  // Массив позиций полностью определяет декорацию, включая пустой массив для текста пользователя.
  const explicit = Array.isArray(decorated);
  const positions = new Map();
  if (explicit) for (const position of decorated) {
    if (position && Number.isInteger(position.offset) && position.offset >= 0
      && keys.includes(position.key) && text.startsWith(position.key, position.offset)) positions.set(position.offset, position.key);
  }
  const glyphField = !explicit && (node.classList.contains('menu-mark') || node.dataset.iconPositions === 'glyphs');
  const leading = decorated === true && keys.find(/** Находит начальный значок, отделённый пробельным символом от текста. */ key => text.startsWith(key) && /\s/.test(text.slice(key.length, key.length + 1)));
  const trailing = decorated === 'suffix' && keys.find(/** Находит конечный значок, отделённый пробельным символом от текста. */ key => text.endsWith(key) && /\s/.test(text.slice(-key.length - 1, -key.length)));
  const exact = !explicit && icons[text] && text !== '\u20bd' ? text : null;
  let offset = 0;
  while (offset < text.length) {
    const key = positions.get(offset) || (offset === 0 ? exact || leading : null)
      || (trailing && offset === text.length - trailing.length ? trailing : null)
      || (glyphField ? keys.find(/** Находит значок в текущей позиции специального поля отметок. */ key => text.startsWith(key, offset)) : null);
    if (!key) {
      const char = String.fromCodePoint(text.codePointAt(offset));
      node.append(document.createTextNode(char)); offset += char.length; continue;
    }
    const image = document.createElement('img');
    const physicalKey = imageKey || key;
    image.className = 'shared-icon'; installIconSource(image, iconUrl(physicalKey, node.dataset.iconColor || (node.classList.contains('window-glyph') ? 'ACCENT' : 'TEXT_PRIMARY'))); image.alt = '';
    image.setAttribute('aria-hidden', 'true'); image.dataset.iconKey = physicalKey;
    const semantic = document.createElement('span');
    semantic.className = 'icon-semantic'; semantic.textContent = key; semantic.hidden = true;
    node.append(image, semantic); offset += key.length;
  }
  return node;
}

/** Подключает общие стрелки к числовому полю, сохраняя стандартный шаг и события ввода. */
export function spinnerArrows(input, holder) {
  const arrows = document.createElement('span'); arrows.className = 'spinner-arrows';
  for (const [key, direction] of [['\u2191', 1], ['\u25be', -1]]) {
    const control = iconText(document.createElement('button'), key);
    control.type = 'button'; control.tabIndex = -1;
    control.addEventListener('pointerdown', /** Отменяет штатное действие нажатия указателя, сохраняя фокус числового поля. */ event => event.preventDefault());
    control.addEventListener('click', /** Изменяет доступное числовое поле на один шаг и посылает события ввода и изменения. */ () => {
      if (input.disabled || input.readOnly) return;
      input.focus({preventScroll: true});
      if (direction > 0) input.stepUp(); else input.stepDown();
      input.dispatchEvent(new Event('input', {bubbles: true}));
      input.dispatchEvent(new Event('change', {bubbles: true}));
    });
    arrows.append(control);
  }
  holder.append(arrows);
}

/** Назначает общий PNG штатному полю без рисования стрелки шрифтом или браузером. */
export function controlIcon(input, key, color = 'TEXT_PRIMARY') {
  input.dataset.iconKey = key;
  installIconSource(input, iconUrl(key, color), true);
}
