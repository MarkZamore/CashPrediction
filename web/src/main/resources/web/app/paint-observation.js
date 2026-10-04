/**
 * @file Наблюдение фактической DOM-краски S5 и барьер внешнего CDP-снимка.
 * Схема следует текущим PaintObservation/PaintObservationCodec; эталонов здесь нет.
 * Интеграция: один registry передаётся установщику значков и createPaintObserver.
 * icon.js должен устанавливать img через registry.installImage, фон через installBackground,
 * передавая сохранённые байты первоначального ответа, а не повторно загружая currentSrc.
 * bootstrap передаёт environment с хешами артефактов и readRenderGeneration.
 * test-driver передаёт awaitIdle, prepareRaw(request) и синхронный readRaw(request, prepared).
 * prepareRaw завершает запрос счётчиков и подготовку таблицы ДО секции чтения;
 * readRaw читает живые виджеты без Promise, запросов, прокрутки и изменения DOM.
 * Драйвер публикует prepareCapture/finishCapture/abortCapture и widget-paint-v1
 * только после подключения этих зависимостей. Внешний снимок должен быть viewport CDP PNG.
 * deadlineNanos задаётся строкой на часах performance.now этой вкладки.
 * Поколения событий подтверждают только freshness; завершённая краска узлов не наблюдается.
 * Нулевые paint epochs означают отсутствие доказательства и сопровождаются Unsupported.
 */

const HASH = /^[0-9a-f]{64}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SIDES = ['top', 'right', 'bottom', 'left'];
const CORNERS = ['TopLeft', 'TopRight', 'BottomLeft', 'BottomRight'];
const MAX_BYTES = 1024 * 1024;
const ZERO_INSETS = Object.freeze({top: 0, right: 0, bottom: 0, left: 0});

/** Проверяет обязательный контракт selftest, не показывая диагностический текст в интерфейсе. */
function requireValue(ok, message) { if (!ok) throw new Error(message); }

/** Возвращает безопасное целое поколение. */
function counter(value) { requireValue(Number.isSafeInteger(value) && value >= 0, 'Invalid generation'); return value; }

/** Читает монотонное время вкладки в наносекундах без потери точности JSON. */
function nanos() { return BigInt(Math.floor(performance.now() * 1000000)).toString(); }

/** Вычисляет SHA-256 исходных байтов средствами браузера. */
async function sha256(bytes) {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return [...digest].map(/** Записывает байт двумя шестнадцатеричными цифрами. */ byte => byte.toString(16).padStart(2, '0')).join('');
}

/** Кодирует ограниченный массив исходных PNG-байтов без изменения изображения. */
function base64(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

/** Читает прямые ARGB-байты установленного декодированного изображения при исходном размере. */
function decodedArgb(image) {
  const width = image.naturalWidth, height = image.naturalHeight;
  requireValue(width > 0 && height > 0 && width * height <= MAX_BYTES / 4, 'Decoded image limit');
  const canvas = document.createElement('canvas'); canvas.width = width; canvas.height = height;
  const context = canvas.getContext('2d', {willReadFrequently: true});
  requireValue(context, 'Canvas decode unavailable');
  context.drawImage(image, 0, 0);
  const rgba = context.getImageData(0, 0, width, height).data;
  const argb = new Uint8Array(rgba.length);
  for (let i = 0; i < rgba.length; i += 4) {
    argb[i] = rgba[i + 3]; argb[i + 1] = rgba[i]; argb[i + 2] = rgba[i + 1]; argb[i + 3] = rgba[i + 2];
  }
  return argb;
}

/**
 * Создаёт реестр реальных установок из уже сохранённых байтов ответа.
 * Каждый blob URL создаётся самим реестром из копии байтов и остаётся неизменяемым.
 * Lookup привязан к объекту узла, типу рисования и установленному URL, а не iconKey.
 * dispose допустим только после удаления использующих изображения виджетов.
 */
export function createRetainedImageRegistry() {
  const bindings = new WeakMap(), urls = new Set(), listeners = new Set(); let revision = 0, disposed = false;
  /** Уведомляет сборщики о любой установке, в том числе заменённой обратно. */
  function changed() { revision++; for (const listener of listeners) listener(); }
  /** Сохраняет байты и устанавливает именно их как источник живого узла. */
  async function install(node, bytes, source, paintSource) {
    requireValue(!disposed && bytes instanceof Uint8Array && bytes.length > 0 && bytes.length <= MAX_BYTES, 'Retained PNG bytes required');
    const original = new URL(source, document.baseURI);
    requireValue(original.origin === location.origin && /^https?:$/.test(original.protocol), 'Same-origin response required');
    const copy = bytes.slice();
    requireValue([137, 80, 78, 71, 13, 10, 26, 10].every(/** Проверяет сигнатуру сохранённого PNG. */ (byte, i) => copy[i] === byte), 'PNG signature');
    const uri = URL.createObjectURL(new Blob([copy], {type: 'image/png'})); urls.add(uri);
    const ticket = {uri, paintSource}; bindings.set(node, ticket); changed();
    const image = paintSource === 'image-node' ? node : new Image();
    image.src = uri;
    if (paintSource === 'css-background') node.style.backgroundImage = `url("${uri}")`;
    await image.decode();
    requireValue(bindings.get(node) === ticket && image.currentSrc === uri, 'Replaced installed source');
    const argb = decodedArgb(image);
    const asset = {assetId: 'asset-' + crypto.randomUUID(), byteLength: copy.length, sha256: await sha256(copy),
      base64: base64(copy), source: original.href, decodedWidth: image.naturalWidth, decodedHeight: image.naturalHeight,
      decodedArgbSha256: await sha256(argb), provenanceMethod: 'decode-capture', sourceObjectIdentity: 'source-' + crypto.randomUUID()};
    requireValue(bindings.get(node) === ticket && image.currentSrc === uri, 'Replaced decoded source');
    ticket.asset = Object.freeze(asset); changed();
    return {...asset};
  }
  return {
    /** Устанавливает PNG в реальный img и сохраняет идентичность этого декодирования. */
    installImage(image, bytes, source) { requireValue(image instanceof HTMLImageElement, 'Image node required'); return install(image, bytes, source, 'image-node'); },
    /** Устанавливает один CSS-фон из тех же неизменяемых байтов, которые сохранены в реестре. */
    installBackground(node, bytes, source) { return install(node, bytes, source, 'css-background'); },
    /** Возвращает только завершённую привязку конкретного установленного источника. */
    lookup(node, paintSource, uri) {
      const binding = bindings.get(node);
      return !disposed && binding?.asset && binding.paintSource === paintSource && binding.uri === uri ? {...binding.asset} : null;
    },
    /** Читает поколение реальных установок. */
    revision() { return revision; },
    /** Подключает журнал изменений источников и возвращает освобождение подписки. */
    subscribe(listener) { listeners.add(listener); return /** Удаляет подписку конкретного сборщика. */ () => listeners.delete(listener); },
    /** Освобождает собственные blob URL после удаления использующих их виджетов. */
    dispose() { disposed = true; changed(); for (const uri of urls) URL.revokeObjectURL(uri); urls.clear(); listeners.clear(); }
  };
}

/** Разбирает геометрическую величину CSS, поддерживая только уже вычисленные px и проценты. */
function length(value, extent, percentage = true) {
  const match = /^(\d+(?:\.\d+)?|\.\d+)(px|%)?$/.exec(value.trim());
  requireValue(match && (match[2] !== '%' || percentage) && (match[2] || Number(match[1]) === 0), 'Unsupported CSS length: ' + value);
  const raw = Number(match[1]);
  return {raw, percent: match[2] === '%', value: match[2] === '%' ? extent * raw / 100 : raw};
}

/**
 * Разрешает четыре CSS-пары радиусов и общий коэффициент перекрытия.
 * Вход и выход следуют TL, TR, BL, BR, а не порядку CSS TL, TR, BR, BL.
 */
export function resolveRadiusPairs(values, width, height) {
  requireValue(values.length === 4 && width >= 0 && height >= 0 && Number.isFinite(width + height), 'Radius box');
  const radii = values.map(/** Разбирает исходные горизонтальный и вертикальный радиусы угла. */ value => {
    const parts = value.trim().split(/\s+/); requireValue(parts.length <= 2, 'Radius pair');
    const x = length(parts[0], width), y = length(parts[1] || parts[0], height);
    return {rawRx: x.raw, rawRy: y.raw, percentageX: x.percent, percentageY: y.percent,
      units: (x.percent ? '%' : 'px') + '/' + (y.percent ? '%' : 'px'), rx: x.value, ry: y.value};
  });
  const sums = [radii[0].rx + radii[1].rx, radii[2].rx + radii[3].rx, radii[0].ry + radii[2].ry, radii[1].ry + radii[3].ry];
  const extents = [width, width, height, height]; let factor = 1;
  for (let i = 0; i < 4; i++) if (sums[i] > 0) factor = Math.min(factor, extents[i] / sums[i]);
  return radii.map(/** Применяет общий коэффициент перекрытия ко всем радиусам. */ radius => ({...radius, rx: radius.rx * factor, ry: radius.ry * factor}));
}

/** Разбирает вычисленный цвет sRGB в знаковое целое ARGB Java, без выбора токена. */
function argb(value) {
  if (value === 'transparent') return 0;
  const match = /^rgba?\(\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)(?:\s*,\s*([\d.]+))?\s*\)$/.exec(value);
  requireValue(match, 'Unsupported computed color: ' + value);
  const channels = match.slice(1, 4).map(/** Проверяет наблюдаемый канал sRGB. */ channel => { const n = Number(channel); requireValue(n >= 0 && n <= 255, 'Color channel'); return Math.round(n); });
  const alpha = match[4] === undefined ? 1 : Number(match[4]); requireValue(alpha >= 0 && alpha <= 1, 'Color alpha');
  return (Math.round(alpha * 255) << 24) | (channels[0] << 16) | (channels[1] << 8) | channels[2];
}

/** Определяет смещение object-position по свободному месту, включая отрицательное при cover. */
function position(value, free) {
  if (value === 'left' || value === 'top') return 0;
  if (value === 'right' || value === 'bottom') return free;
  if (value === 'center') return free / 2;
  const match = /^(-?(?:\d+(?:\.\d+)?|\.\d+))(px|%)$/.exec(value);
  requireValue(match, 'Unsupported image position: ' + value);
  return match[2] === '%' ? free * Number(match[1]) / 100 : Number(match[1]);
}

/** Вычисляет фактический прямоугольник img при object-fit, отдельно от рамки элемента. */
export function imageDestination(content, intrinsicWidth, intrinsicHeight, fit, objectPosition) {
  requireValue(intrinsicWidth > 0 && intrinsicHeight > 0 && Number.isFinite(intrinsicWidth + intrinsicHeight)
    && [content.x, content.y, content.width, content.height].every(/** Проверяет конечность координат контентного прямоугольника. */ Number.isFinite)
    && content.width >= 0 && content.height >= 0, 'Intrinsic/content dimensions');
  let width = content.width, height = content.height;
  if (fit === 'none') { width = intrinsicWidth; height = intrinsicHeight; }
  else if (fit !== 'fill') {
    requireValue(['contain', 'cover', 'scale-down'].includes(fit), 'Unsupported object-fit');
    let scale = fit === 'cover' ? Math.max(width / intrinsicWidth, height / intrinsicHeight) : Math.min(width / intrinsicWidth, height / intrinsicHeight);
    if (fit === 'scale-down') scale = Math.min(1, scale);
    width = intrinsicWidth * scale; height = intrinsicHeight * scale;
  }
  const parts = objectPosition.trim().split(/\s+/);
  requireValue(parts.length === 2, 'Unsupported object-position');
  return {x: content.x + position(parts[0], content.width - width), y: content.y + position(parts[1], content.height - height), width, height};
}

/** Читает border-box без округления дробных координат. */
function box(node) { const r = node.getBoundingClientRect(); return {x: r.x, y: r.y, width: r.width, height: r.height}; }

/** Проверяет пересечение двух фактических прямоугольников. */
function intersects(a, b) { return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height; }

/** Проверяет выход прямоугольника за пределы другого. */
function outside(a, b) { return a.x < b.x || a.y < b.y || a.x + a.width > b.x + b.width || a.y + a.height > b.y + b.height; }

/** Создаёт четыре угла прямоугольника в порядке TL, TR, BL, BR. */
function quad(b) { return [[b.x, b.y], [b.x + b.width, b.y], [b.x, b.y + b.height], [b.x + b.width, b.y + b.height]]; }

/** Читает видимость с учётом предков, без фильтрации источника по служебному классу. */
function visible(node) {
  if (!node.isConnected || !node.getClientRects().length) return false;
  const own = getComputedStyle(node);
  if (own.visibility !== 'visible') return false;
  for (let parent = node; parent; parent = parent.parentElement) {
    const css = getComputedStyle(parent);
    if (css.display === 'none' || Number(css.opacity) === 0) return false;
  }
  return true;
}

/** Строит диагностический путь узла, сохраняя идентичность независимо от cpId. */
function path(node) {
  const parts = [];
  for (let parent = node; parent; parent = parent.parentElement) {
    const index = parent.parentElement ? [...parent.parentElement.children].indexOf(parent) + 1 : 1;
    parts.unshift(parent.localName + ':nth-child(' + index + ')');
  }
  return parts.join('>');
}

/**
 * Создаёт наблюдателя с явным реестром реально установленных источников.
 * cssomJournal обязан считать ВСЕ изменения CSSOM, включая ABA и асинхронный replace:
 * revision(), subscribe(listener), complete === true. Без полного журнала захват Unsupported.
 * Он подключается bootstrap до изменения stylesheet; один fingerprint не доказывает отсутствие ABA.
 * readRenderGeneration обязан считать каждое применение экрана/эффекта, включая изменение с возвратом.
 */
export function createPaintObserver({root, toolbar, summary, registry, cssomJournal, awaitIdle, prepareRaw, readRaw, readRenderGeneration, environment}) {
  requireValue(root?.ownerDocument === document && toolbar && summary, 'Live capture roots required');
  requireValue(registry && ['lookup', 'revision', 'subscribe'].every(/** Проверяет обязательный API реестра установленных изображений. */ name => typeof registry[name] === 'function'), 'Actual retained registry required');
  for (const hook of [awaitIdle, prepareRaw, readRaw, readRenderGeneration]) requireValue(typeof hook === 'function', 'Capture dependency required');
  requireValue(environment && ['os', 'runtime', 'renderer'].every(/** Проверяет обязательные диагностические строки среды. */ name => typeof environment[name] === 'string' && environment[name].length > 0), 'Environment provenance required');
  requireValue(environment.artifactDigests && Object.keys(environment.artifactDigests).length > 0 && Object.keys(environment.artifactDigests).length <= 128,
    'Artifact digests required');
  for (const digest of Object.values(environment.artifactDigests)) requireValue(HASH.test(digest), 'Artifact digest');
  const provenance = structuredClone({os: environment.os, runtime: environment.runtime, renderer: environment.renderer, artifactDigests: environment.artifactDigests});
  const ids = new WeakMap(), used = new Set(); let nextId = 0, transaction = null, disposed = false;
  let pointer = null, modality = 'none', gestureSequence = 0, gestureAcknowledged = false;

  /** Даёт реальному DOM-объекту стабильный локальный идентификатор без записи в DOM. */
  function id(node) { if (!ids.has(node)) ids.set(node, 'dom-' + (++nextId)); return ids.get(node); }
  /** Записывает Unsupported с ограничением размера companion-документа. */
  function unsupported(list, instanceId, property, reason) {
    requireValue(list.length < 512, 'Unsupported limit'); list.push({instanceId, property, reason: String(reason).slice(0, 4096)});
  }
  /** Записывает только доверенный фактический ввод; синтетические события не подтверждают жест. */
  function inputEvent(event) {
    if (!event.isTrusted) return;
    if (event.type.startsWith('pointer')) {
      pointer = event.type === 'pointerout' && !event.relatedTarget ? null : [event.clientX, event.clientY]; modality = 'pointer';
    } else modality = 'keyboard';
    gestureSequence++; gestureAcknowledged = true;
  }
  const inputTypes = ['pointermove', 'pointerdown', 'pointerout', 'keydown'];
  for (const type of inputTypes) document.addEventListener(type, inputEvent, true);

  /** Читает геометрию, множители прозрачности и неподдержанные эффекты всей цепочки предков. */
  function geometry(node, issues, instanceId = id(node)) {
    const bounds = box(node), css = getComputedStyle(node), factors = [], effects = [], filters = [], clipping = [];
    let alpha = 1, clipped = outside(bounds, {x: 0, y: 0, width: innerWidth, height: innerHeight});
    for (let parent = node; parent; parent = parent.parentElement) {
      const style = getComputedStyle(parent), source = id(parent), opacity = Number(style.opacity);
      requireValue(Number.isFinite(opacity) && opacity >= 0 && opacity <= 1, 'Computed opacity');
      factors.push({sourceInstance: source, value: opacity, mechanism: 'css-opacity'}); alpha *= opacity;
      requireValue(factors.length <= 64, 'Opacity ancestor limit');
      for (const property of ['transform', 'translate', 'rotate', 'scale', 'perspective']) {
        const value = style.getPropertyValue(property);
        if (value && value !== 'none') { effects.push(property + ':' + value); unsupported(issues, instanceId, property, 'Transformed quad unavailable in scoped collector'); }
      }
      if (style.zoom && style.zoom !== '1' && style.zoom !== 'normal') unsupported(issues, instanceId, 'zoom', style.zoom);
      for (const property of ['filter', 'backdrop-filter', 'mix-blend-mode', 'mask-image', 'clip-path', 'clip', 'box-shadow', 'text-shadow', '-webkit-box-reflect']) {
        const value = style.getPropertyValue(property);
        if (value && !['none', 'normal', 'auto'].includes(value)) {
          effects.push(property + ':' + value); if (property.includes('filter')) filters.push(value);
          unsupported(issues, instanceId, property, value);
        }
      }
      if (style.getPropertyValue('content-visibility') === 'auto') unsupported(issues, instanceId, 'content-visibility', 'Deferred paint');
      if (style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0) unsupported(issues, instanceId, 'outline', 'Outline contour is not represented by CSS border layers');
      if (parent !== node && (style.overflowX !== 'visible' || style.overflowY !== 'visible')) {
        clipping.push(source + ':overflow=' + style.overflowX + '/' + style.overflowY);
        const rect = box(parent);
        const clip = {x: rect.x + parseFloat(style.borderLeftWidth), y: rect.y + parseFloat(style.borderTopWidth),
          width: Math.max(0, rect.width - parseFloat(style.borderLeftWidth) - parseFloat(style.borderRightWidth)),
          height: Math.max(0, rect.height - parseFloat(style.borderTopWidth) - parseFloat(style.borderBottomWidth))};
        if (outside(bounds, clip)) { clipped = true; unsupported(issues, instanceId, 'clipping', 'Ancestor overflow intersects destination'); }
        if (style.borderRadius !== '0px') unsupported(issues, instanceId, 'clipping', 'Rounded ancestor clip requires contour observation');
      }
    }
    if (clipped) unsupported(issues, instanceId, 'clipped', 'Destination leaves supported viewport or clipping box');
    return {bounds, localBox: {x: 0, y: 0, width: bounds.width, height: bounds.height},
      localToPng: [devicePixelRatio, 0, 0, devicePixelRatio, bounds.x * devicePixelRatio, bounds.y * devicePixelRatio], css,
      opacityFactors: factors, effectiveOpacity: alpha, effects, filters, clipping, clipped};
  }

  /** Переводит наблюдённый логический прямоугольник в PNG без округления. */
  function png(b) { return {x: b.x * devicePixelRatio, y: b.y * devicePixelRatio, width: b.width * devicePixelRatio, height: b.height * devicePixelRatio}; }

  /** Получает фактический контентный прямоугольник относительно border-box элемента. */
  function contentBox(g, padding = true) {
    const s = g.css, widths = {}, pads = {};
    for (const side of SIDES) {
      widths[side] = length(s.getPropertyValue('border-' + side + '-width'), 0, false).value;
      pads[side] = padding ? length(s.getPropertyValue('padding-' + side), 0, false).value : 0;
    }
    return {x: widths.left + pads.left, y: widths.top + pads.top,
      width: Math.max(0, g.bounds.width - widths.left - widths.right - pads.left - pads.right),
      height: Math.max(0, g.bounds.height - widths.top - widths.bottom - pads.top - pads.bottom)};
  }

  /** Читает роль из фактической привязки контрольного элемента; неизвестное сохраняется явно. */
  function role(node) {
    const control = node.closest('button, input, select');
    if (node.closest('.toolbar-arrow') || control?.dataset.cpId?.endsWith('.arrow')) return 'arrow';
    if (control?.dataset.cpId?.endsWith('.clear')) return 'clear';
    return control?.dataset.cpId?.endsWith('.action') ? 'glyph' : 'unclassified';
  }

  /** Проверяет прямоугольник на пересечение с отдельно наблюдёнными поверхностями. */
  function occluded(node, bounds, surfaces) {
    for (const surface of surfaces) if (surface.node !== root && !surface.node.contains(node) && intersects(bounds, surface.logicalBox)) return true;
    return false;
  }

  /** Читает реальные видимые popup/dialog поверхности без предположения об их порядке отрисовки. */
  function surfaceList(issues) {
    const nodes = [root];
    // JavaFX: Popup/Stage/ContextMenu → Swing: JWindow/JDialog/JPopupMenu → Web: dialog и панели меню.
    for (const node of document.querySelectorAll('dialog[open], .quick-edit, [data-popup-kind], .context-menu, .menu-panel, [popover], #screens'))
      if (node !== root && visible(node)) nodes.push(node);
    requireValue(nodes.length <= 64, 'Surface limit');
    return nodes.map(/** Снимает координаты каждой поверхности, сохраняя неизвестный z-order как Unsupported. */ (node, index) => {
      const logicalBox = box(node);
      if (index > 0) unsupported(issues, id(node), 'surface-z-order', 'Overlay composition order requires external evidence');
      return {node, logicalBox, wire: {instanceId: id(node), kind: index === 0 ? 'root' : node.localName,
        screenOrigin: null, pngOrigin: [logicalBox.x * devicePixelRatio, logicalBox.y * devicePixelRatio],
        box: png(logicalBox), zOrder: index, visible: visible(node), occluded: false}};
    });
  }

  /** Читает слои и состояния карточки, не подменяя завершение краски поколением событий. */
  function card(node, issues, surfaces) {
    const g = geometry(node, issues), css = g.css, instanceId = id(node);
    let radii, colors, widths, backgrounds = [], borders = [];
    try {
      radii = resolveRadiusPairs(CORNERS.map(/** Читает вычисленную пару конкретного угла рамки. */ corner => css['border' + corner + 'Radius']), g.bounds.width, g.bounds.height);
      colors = SIDES.map(/** Читает фактический цвет стороны рамки. */ side => argb(css.getPropertyValue('border-' + side + '-color')));
      widths = SIDES.map(/** Читает фактическую ширину стороны рамки. */ side => length(css.getPropertyValue('border-' + side + '-width'), 0, false).value);
      borders = [{colors, widths, styles: SIDES.map(/** Читает фактический стиль стороны рамки. */ side => css.getPropertyValue('border-' + side + '-style')),
        insets: {...ZERO_INSETS}, radii, pathRadii: null, strokePlacement: 'css-inside', implementation: 'css-border'}];
      const origin = css.backgroundClip;
      let insets = {...ZERO_INSETS};
      if (origin === 'padding-box' || origin === 'content-box') {
        for (let i = 0; i < 4; i++) insets[SIDES[i]] = widths[i] + (origin === 'content-box' ? length(css.getPropertyValue('padding-' + SIDES[i]), 0, false).value : 0);
      } else requireValue(origin === 'border-box', 'Unsupported background-clip');
      const fillRadii = radii.map(/** Выводит собственный внутренний контур заливки из фактической CSS-рамки. */ (r, i) => {
        if (origin === 'border-box') return {...r};
        const x = Math.max(0, r.rx - (i === 0 || i === 2 ? insets.left : insets.right));
        const y = Math.max(0, r.ry - (i < 2 ? insets.top : insets.bottom));
        return {rawRx: x, rawRy: y, percentageX: false, percentageY: false, units: 'px/px', rx: x, ry: y};
      });
      backgrounds = [{argb: argb(css.backgroundColor), insets, radii: fillRadii, implementation: 'css-background-color/' + origin}];
    } catch (error) { unsupported(issues, instanceId, 'card-layers', error.message); }
    if (css.backgroundImage !== 'none') unsupported(issues, instanceId, 'background-image', css.backgroundImage);
    if (css.borderImageSource !== 'none') unsupported(issues, instanceId, 'border-image', css.borderImageSource);
    if (g.effectiveOpacity !== 1) unsupported(issues, instanceId, 'card-opacity', String(g.effectiveOpacity));
    const covered = occluded(node, g.bounds, surfaces);
    if (covered) unsupported(issues, instanceId, 'occluded', 'Separate overlay intersects card');
    const focus = document.activeElement, hit = pointer ? document.elementFromPoint(pointer[0], pointer[1]) : null;
    if (node.matches(':hover') && (!hit || !node.contains(hit))) unsupported(issues, instanceId, 'physical-hit', 'Hover lacks trusted pointer hit');
    unsupported(issues, instanceId, 'lastPaintEpoch', 'Actual DOM node paint completion unavailable; zero is an unavailable sentinel');
    return {instanceId, id: node.dataset.cpId || instanceId, localBox: g.localBox, localToPng: g.localToPng, bounds: png(g.bounds),
      visible: visible(node), clipped: g.clipped, occluded: covered, clipping: g.clipping, effects: g.effects, backgrounds, borders,
      hover: node.matches(':hover'), focused: focus === node, focusVisible: node.matches(':focus-visible'), focusWithin: !!focus && node.contains(focus),
      focusOwner: focus ? id(focus) : null, physicalHit: pointer ? !!hit && node.contains(hit) : null,
      backgroundImplementation: 'css-background', borderImplementation: 'css-border', lastPaintEpoch: 0};
  }

  /** Читает одиночный URL CSS-фона, не интерпретируя неизвестный paint source как картинку. */
  function backgroundUri(value) { const m = /^url\(["']?([^"')]+)["']?\)$/.exec(value); return m ? new URL(m[1], document.baseURI).href : null; }

  /** Сохраняет источник и геометрию изображения отдельно от недоказанного фактического рисования. */
  function icon(node, paintSource, pseudo, issues, assets, surfaces, order) {
    const instanceId = paintSource === 'image-node' ? id(node) : id(node) + ':' + (pseudo || paintSource);
    const g = geometry(node, issues, instanceId), css = pseudo ? getComputedStyle(node, pseudo) : g.css;
    const owner = node.closest('.toolbar-node') || node.closest('.card') || toolbar;
    const uri = paintSource === 'image-node' ? node.currentSrc : backgroundUri(css.backgroundImage);
    const asset = uri ? registry.lookup(node, paintSource, uri) : null;
    let destination = g.localBox, crop = null, complete = true;
    if (!asset) { unsupported(issues, instanceId, 'installed-source', 'No retained binding for actual current source'); complete = false; }
    else {
      requireValue(asset.byteLength > 0 && asset.byteLength <= MAX_BYTES && HASH.test(asset.sha256) && HASH.test(asset.decodedArgbSha256), 'Invalid registry asset');
      requireValue(typeof asset.base64 === 'string' && asset.base64.length <= Math.ceil(MAX_BYTES / 3) * 4, 'Retained asset limit');
      const previous = assets.get(asset.assetId);
      requireValue(!previous || JSON.stringify(previous) === JSON.stringify(asset), 'Conflicting retained identity'); assets.set(asset.assetId, asset);
      try {
        if (paintSource === 'image-node') {
          requireValue(node.complete && node.naturalWidth === asset.decodedWidth && node.naturalHeight === asset.decodedHeight, 'Installed decode mismatch');
          destination = imageDestination(contentBox(g), node.naturalWidth, node.naturalHeight, css.objectFit, css.objectPosition);
          const content = contentBox(g);
          if (outside(destination, content)) {
            crop = {x: (Math.max(content.x, destination.x) - destination.x) * node.naturalWidth / destination.width,
              y: (Math.max(content.y, destination.y) - destination.y) * node.naturalHeight / destination.height,
              width: Math.min(destination.width, content.width) * node.naturalWidth / destination.width,
              height: Math.min(destination.height, content.height) * node.naturalHeight / destination.height};
            unsupported(issues, instanceId, 'source-crop', 'Cropped image requires separate contour prevalidation'); complete = false;
          }
        } else if (paintSource === 'css-background' && !pseudo) {
          requireValue(css.backgroundRepeat === 'no-repeat' && css.backgroundAttachment === 'scroll', 'Unsupported repeated/fixed background');
          requireValue(css.backgroundClip === 'border-box' && css.borderRadius === '0px', 'Unsupported shaped background clip');
          requireValue(['padding-box', 'content-box', 'border-box'].includes(css.backgroundOrigin), 'Unsupported background origin');
          const content = css.backgroundOrigin === 'border-box' ? g.localBox : contentBox(g, css.backgroundOrigin === 'content-box');
          const size = css.backgroundSize.split(/\s+/); requireValue(size.length <= 2, 'Background size');
          let w = asset.decodedWidth, h = asset.decodedHeight;
          if (size[0] === 'contain' || size[0] === 'cover') {
            const fitted = imageDestination(content, w, h, size[0], '0% 0%'); w = fitted.width; h = fitted.height;
          } else {
            if (size[0] !== 'auto') w = length(size[0], content.width).value;
            if (size[1] && size[1] !== 'auto') {
              h = length(size[1], content.height).value;
              if (size[0] === 'auto') w = h * asset.decodedWidth / asset.decodedHeight;
            } else if (size[0] !== 'auto') h = w * asset.decodedHeight / asset.decodedWidth;
          }
          const positions = css.backgroundPosition.split(/\s+/); requireValue(positions.length === 2, 'Unsupported background position');
          destination = {x: content.x + position(positions[0], content.width - w), y: content.y + position(positions[1], content.height - h), width: w, height: h};
          if (outside(destination, content)) { unsupported(issues, instanceId, 'background-clip', 'Background leaves positioning area'); complete = false; }
        } else { unsupported(issues, instanceId, 'pseudo-geometry', 'Generated paint box cannot be measured by host border-box'); complete = false; }
      } catch (error) { unsupported(issues, instanceId, 'image-geometry', error.message); complete = false; }
    }
    const logical = {x: g.bounds.x + destination.x, y: g.bounds.y + destination.y, width: destination.width, height: destination.height};
    const bounds = png(logical), imageRole = role(node);
    if (imageRole === 'unclassified') { unsupported(issues, instanceId, 'role', 'No actual toolbar role binding'); complete = false; }
    if (occluded(node, logical, surfaces)) unsupported(issues, instanceId, 'occluded', 'Overlay intersects image');
    unsupported(issues, instanceId, 'drawEpoch', 'Actual DOM image draw completion unavailable; zero is an unavailable sentinel');
    // Удержанный источник не доказывает завершённый draw даже при известной геометрии.
    complete = false;
    return {instanceId, ownerInstance: id(owner), ownerId: owner.dataset.cpId || owner.id || id(owner), role: imageRole, widgetPath: path(node) + (pseudo || ''),
      paintSource, assetId: asset?.assetId || null, sourceObjectIdentity: asset?.sourceObjectIdentity || 'unresolved:' + instanceId,
      semanticKey: node.dataset.iconKey || null, variantToken: node.dataset.iconColor || null, rawArgb: null,
      localBox: destination, localToPng: g.localToPng, bounds, quad: quad(bounds), sourceViewport: crop, clipping: g.clipping,
      clipped: g.clipped || !!crop, visible: visible(node), paintOrder: order, opacityFactors: g.opacityFactors,
      effectiveOpacity: g.effectiveOpacity, compositeMode: 'source-over', effects: g.effects, filters: g.filters,
      blendMode: css.mixBlendMode || 'normal', drawEpoch: 0, occurrenceIndex: order, complete};
  }

  /** Собирает все видимые источники под toolbar/summary, включая неизвестные и повторные. */
  function collect() {
    const issues = [], assets = new Map(), icons = [], cards = [], surfaces = surfaceList(issues);
    unsupported(issues, id(root), 'paint-census', 'DOM observations and CSSOM revisions do not prove complete compositor paint coverage');
    if (!visible(root)) unsupported(issues, id(root), 'root-visibility', 'Capture root is not displayed');
    if (innerWidth !== 1200 || innerHeight !== 800 || devicePixelRatio !== 1 || (visualViewport && (visualViewport.scale !== 1 || visualViewport.offsetLeft || visualViewport.offsetTop)))
      unsupported(issues, id(root), 'viewport', 'Strict S5 requires 1200x800 viewport at unit scale');
    if (!document.hasFocus() || document.visibilityState !== 'visible') unsupported(issues, id(root), 'active-root', 'Document is not active and visible');
    if (!cssomJournal || cssomJournal.complete !== true || typeof cssomJournal.revision !== 'function' || typeof cssomJournal.subscribe !== 'function')
      unsupported(issues, id(root), 'cssom-journal', 'Complete CSSOM ABA journal dependency unavailable');
    for (const sheet of [...document.styleSheets, ...document.adoptedStyleSheets]) {
      try { void sheet.cssRules; } catch { unsupported(issues, id(root), 'stylesheet', 'Inaccessible stylesheet cannot be fingerprinted'); }
    }
    if (document.fonts.status !== 'loaded') unsupported(issues, id(root), 'fonts', document.fonts.status);
    if (document.getAnimations().some(/** Выявляет активную либо приостановленную анимацию, влияющую на фактическую краску. */ animation => animation.playState !== 'finished' && animation.playState !== 'idle'))
      unsupported(issues, id(root), 'animation', 'Active animation or transition');
    for (const area of [toolbar, summary]) for (const node of [area, ...area.querySelectorAll('*')]) {
      if (!visible(node)) continue;
      if (node.shadowRoot || ['CANVAS', 'SVG', 'VIDEO', 'IFRAME', 'OBJECT', 'EMBED'].includes(node.tagName)) unsupported(issues, id(node), 'paint-source', 'Unobserved drawable subtree');
      if (node.matches('.card')) cards.push(card(node, issues, surfaces));
      if (node.tagName === 'IMG') icons.push(icon(node, 'image-node', null, issues, assets, surfaces, icons.length));
      const css = getComputedStyle(node);
      if (css.borderImageSource !== 'none' || css.listStyleImage !== 'none') unsupported(issues, id(node), 'additional-image-source', 'Border/list image destination unavailable');
      if (css.backgroundImage !== 'none') icons.push(icon(node, 'css-background', null, issues, assets, surfaces, icons.length));
      for (const pseudo of ['::before', '::after']) {
        const style = getComputedStyle(node, pseudo);
        if (style.content !== 'none' && style.content !== 'normal' && style.display !== 'none') {
          unsupported(issues, id(node) + ':' + pseudo, 'pseudo-element', 'Generated content requires its own paint-box observation');
          icons.push(icon(node, 'pseudo-element', pseudo, issues, assets, surfaces, icons.length));
        }
      }
      if (node.matches('.toolbar-arrow, .glyph-only, .toolbar-label') && Array.from(node.textContent.trim()).length === 1 && !node.querySelector('img')) {
        unsupported(issues, id(node), 'text-glyph', 'Font glyph is not a retained PNG image occurrence');
        icons.push(icon(node, 'text-glyph', null, issues, assets, surfaces, icons.length));
      }
      requireValue(icons.length <= 128 && cards.length <= 32, 'Paint census limit');
    }
    const cardIds = new Set(); for (const c of cards) { requireValue(!cardIds.has(c.id), 'Duplicate card id'); cardIds.add(c.id); }
    let bytes = 0; for (const asset of assets.values()) bytes += asset.byteLength; requireValue(bytes <= MAX_BYTES, 'Total asset limit');
    const focus = document.activeElement;
    return {viewport: {logicalWidth: innerWidth, logicalHeight: innerHeight, pngWidth: Math.round(innerWidth * devicePixelRatio), pngHeight: Math.round(innerHeight * devicePixelRatio),
      logicalToPng: [devicePixelRatio, 0, 0, devicePixelRatio, 0, 0], screenContentOrigin: null, outputScaleX: devicePixelRatio, outputScaleY: devicePixelRatio,
      browserDpr: devicePixelRatio, rootInstance: id(root)},
      environment: {...provenance, contentScale: devicePixelRatio, fontLoadStatus: document.fonts.status},
      interaction: {pointer, modality, focusOwner: focus ? id(focus) : null, activeRoot: document.hasFocus() ? id(root) : id(document.documentElement), gestureSequence, gestureAcknowledged},
      surfaces: surfaces.map(/** Оставляет wire-поля поверхности, не сериализуя DOM-ссылки. */ surface => surface.wire), assets: [...assets.values()], icons, cards, unsupported: issues};
  }

  /** Отпечаток всего DOM и вычисленной CSS-краски, включая поверхности вне главного корня. */
  function fingerprint() {
    const values = [innerWidth, innerHeight, devicePixelRatio, document.hasFocus(), document.visibilityState, document.fonts.status,
      pointer, modality, gestureSequence, id(document.activeElement || document.documentElement), registry.revision(), readRenderGeneration(), cssomJournal?.revision?.() ?? null];
    for (const animation of document.getAnimations()) values.push(['animation', animation.playState, animation.currentTime, animation.playbackRate]);
    const nodes = [...document.querySelectorAll('*')]; requireValue(nodes.length <= 20000, 'DOM fingerprint limit');
    for (const node of nodes) {
      const css = getComputedStyle(node), properties = [];
      for (const property of css) properties.push([property, css.getPropertyValue(property)]);
      values.push([id(node), box(node), node.getAttributeNames().map(/** Сохраняет реальные атрибуты узла в отпечатке. */ name => [name, node.getAttribute(name)]),
        node.childNodes.length, node.textContent, node.value ?? null, node.checked ?? null, node.disabled ?? null, node.scrollLeft, node.scrollTop,
        node.currentSrc ?? null, node.naturalWidth ?? null, node.naturalHeight ?? null, node.matches(':hover'), node.matches(':focus-visible'), properties]);
      for (const pseudo of ['::before', '::after']) {
        const style = getComputedStyle(node, pseudo), properties = [];
        for (const property of style) properties.push([property, style.getPropertyValue(property)]);
        values.push([id(node), pseudo, properties]);
      }
    }
    for (const sheet of [...document.styleSheets, ...document.adoptedStyleSheets]) {
      try { values.push([sheet.href, sheet.disabled, sheet.media.mediaText, [...sheet.cssRules].map(/** Читает фактический текст CSSOM-правила. */ rule => rule.cssText)]); }
      catch { values.push('inaccessible-stylesheet'); }
    }
    return JSON.stringify(values);
  }

  /** Проверяет точные ключи запроса и идентичность, не используя планируемые состояния как наблюдения. */
  function requestIdentity(request) {
    const keys = ['runId', 'captureId', 'commandNumber', 'scenario', 'step', 'attempt', 'planSha256', 'cardStates', 'deadlineNanos'];
    requireValue(request && Object.keys(request).sort().join() === keys.sort().join(), 'Exact capture request keys');
    requireValue(UUID.test(request.runId) && UUID.test(request.captureId) && HASH.test(request.planSha256), 'Capture identity');
    counter(request.commandNumber); requireValue(request.commandNumber <= 2147483647 && Number.isInteger(request.attempt) && request.attempt > 0 && request.attempt <= 2147483647, 'Capture attempt');
    for (const name of [request.scenario, request.step]) requireValue(typeof name === 'string' && /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/.test(name)
      && !name.endsWith('.') && !/^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)/i.test(name), 'Safe capture component');
    requireValue(request.cardStates && typeof request.cardStates === 'object' && !Array.isArray(request.cardStates) && Object.keys(request.cardStates).length <= 32, 'Card intentions');
    let active = 0;
    for (const [name, state] of Object.entries(request.cardStates)) { requireValue(name.trim() && name.length <= 4096 && ['NORMAL', 'HOVER', 'FOCUS'].includes(state), 'Card intention'); if (state !== 'NORMAL') active++; }
    requireValue(active <= 1 && typeof request.deadlineNanos === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(request.deadlineNanos)
      && BigInt(request.deadlineNanos) <= 9223372036854775807n, 'Capture deadline');
    const {runId, captureId, commandNumber, scenario, step, attempt, planSha256} = request;
    return {runId, captureId, commandNumber, client: 'web', scenario, step, attempt, planSha256};
  }

  /** Устанавливает ограниченное ожидание на дедлайне вкладки и при отмене транзакции. */
  async function bounded(promise, tx) {
    requireValue(!tx.released, 'Capture aborted or expired');
    const remaining = Number((BigInt(tx.request.deadlineNanos) - BigInt(nanos())) / 1000000n);
    requireValue(remaining > 0, 'Capture deadline expired');
    let timer;
    try {
      const result = await Promise.race([promise, new Promise(/** Ограничивает ожидание реальным дедлайном запроса. */ (resolve, reject) => {
        timer = setTimeout(/** Прерывает ожидание по истечении дедлайна. */ () => reject(new Error('Capture deadline expired')), Math.min(remaining, 2147483647));
        tx.cancelWait = reject;
      })]);
      requireValue(!tx.released, 'Capture aborted or expired');
      return result;
    } finally { clearTimeout(timer); tx.cancelWait = null; }
  }

  /** Освобождает все наблюдатели и таймеры одной попытки, не меняя пользовательские виджеты. */
  function release(tx) {
    if (tx.released) return tx.cleanupErrors;
    tx.released = true; tx.cleanupErrors = [];
    /** Освобождает каждую независимую подписку даже при отказе предыдущей. */
    function cleanup(operation) { try { operation(); } catch (error) { tx.cleanupErrors.push(error); } }
    cleanup(/** Отменяет непрерывное наблюдение кадров. */ () => cancelAnimationFrame(tx.frame));
    cleanup(/** Снимает таймер внешнего bracket. */ () => clearTimeout(tx.expiry));
    for (const handle of tx.barriers) cleanup(/** Отменяет каждый ожидающий layout-барьер. */ () => cancelAnimationFrame(handle));
    tx.barriers.clear();
    cleanup(/** Останавливает наблюдение DOM. */ () => tx.mutation?.disconnect());
    cleanup(/** Останавливает наблюдение размеров. */ () => tx.resize?.disconnect());
    for (const [target, type, callback, capture] of tx.events)
      cleanup(/** Удаляет listener с исходным режимом capture. */ () => target.removeEventListener(type, callback, capture));
    for (const unsubscribe of tx.subscriptions) cleanup(unsubscribe);
    cleanup(/** Прерывает текущую асинхронную фазу без ожидания её исходного Promise. */ () => tx.cancelWait?.(new Error('Capture aborted')));
    if (transaction === tx) transaction = null;
    return tx.cleanupErrors;
  }

  /** Планирует отменяемый layout-барьер, которым владеет только данная транзакция. */
  function nextFrame(tx) {
    return new Promise(/** Регистрирует handle до передачи управления циклу кадров. */ resolve => {
      const handle = requestAnimationFrame(/** Освобождает исполненный handle и завершает только живой барьер. */ timestamp => {
        tx.barriers.delete(handle); if (!tx.released) resolve(timestamp);
      });
      tx.barriers.add(handle);
    });
  }

  /** Проверяет одноразовый handle вместе с обязательной captureId. */
  function handleFor(value) {
    requireValue(value && Object.keys(value).sort().join() === 'captureId,handle' && transaction
      && transaction.handle === value.handle && transaction.identity.captureId === value.captureId && transaction.phase === 'prepared', 'Unknown or consumed capture handle');
    return transaction;
  }

  /** Начинает непрерывный журнал событий до барьера и продолжает его до finish/abort. */
  function observe(tx) {
    /** Увеличивает поколения на каждом событии, сохраняя ABA даже при равных отпечатках. */
    function change(reason) {
      if (tx.released) return;
      tx.epoch++; tx.layout++; tx.paint++;
      if (tx.phase !== 'warming' && tx.changes.length < 512) tx.changes.push(reason);
    }
    tx.change = change;
    tx.mutation = new MutationObserver(/** Регистрирует любое изменение DOM в пределах страницы. */ records => { if (records.length) change('dom-mutation'); });
    tx.mutation.observe(document.documentElement, {subtree: true, childList: true, attributes: true, characterData: true});
    tx.resize = new ResizeObserver(/** Регистрирует изменение геометрии наблюдаемого узла. */ () => change('resize-observer'));
    for (const node of document.querySelectorAll('*')) tx.resize.observe(node);
    for (const target of [document, window, visualViewport, document.fonts].filter(/** Исключает отсутствующую API из регистрации. */ value => value)) {
      for (const type of ['resize', 'scroll', 'focus', 'blur', 'focusin', 'focusout', 'pointermove', 'pointerdown', 'pointerout', 'keydown', 'keyup',
        'input', 'change', 'load', 'error', 'visibilitychange', 'loading', 'loadingdone', 'loadingerror', 'transitionrun', 'transitionstart', 'transitionend', 'transitioncancel', 'animationstart', 'animationend', 'animationiteration', 'animationcancel']) {
        const callback = /** Записывает событие окружения даже при возврате прежнего состояния. */ () => change('event:' + type);
        target.addEventListener(type, callback, true); tx.events.push([target, type, callback, true]);
      }
    }
    const media = new Set();
    /** Добавляет реально используемый media-query к журналу изменения среды. */
    function mediaQuery(query) {
      if (!query || media.has(query)) return;
      media.add(query); const target = matchMedia(query);
      const callback = /** Регистрирует смену media-query даже при возврате прежнего состояния до кадра. */ () => change('media-query:' + query);
      target.addEventListener('change', callback); tx.events.push([target, 'change', callback, false]);
    }
    /** Обходит вложенные правила stylesheet и подписывается на их media-условия. */
    function rules(list) {
      for (const rule of list) { if (rule.media) mediaQuery(rule.media.mediaText); if (rule.cssRules) rules(rule.cssRules); }
    }
    for (const sheet of [...document.styleSheets, ...document.adoptedStyleSheets]) {
      mediaQuery(sheet.media.mediaText);
      try { rules(sheet.cssRules); } catch { change('inaccessible-stylesheet'); }
    }
    tx.subscriptions.push(registry.subscribe(/** Регистрирует замену реального декодированного источника. */ () => change('registry-source')));
    if (cssomJournal?.subscribe) tx.subscriptions.push(cssomJournal.subscribe(/** Регистрирует CSSOM-изменение с возможным возвратом значения. */ () => change('cssom')));
    /** Сверяет каждый кадр с предыдущим, не считая саму смену rAF изменением краски. */
    function frame(timestamp) {
      if (tx.released) return;
      tx.frameTime = timestamp;
      try { const value = fingerprint(); if (tx.lastFingerprint !== undefined && value !== tx.lastFingerprint) change('frame-fingerprint'); tx.lastFingerprint = value; }
      catch (error) { change('frame-error:' + error.message); }
      if (!tx.released) tx.frame = requestAnimationFrame(frame);
    }
    tx.frame = requestAnimationFrame(frame);
  }

  /** Возвращает наблюдённые поколения транспорта, DOM, реестра и CSSOM. */
  function revisions(tx) {
    return {epoch: tx.epoch, layout: tx.layout, paint: tx.paint, render: counter(readRenderGeneration()), registry: counter(registry.revision()),
      cssom: cssomJournal && typeof cssomJournal.revision === 'function' ? counter(cssomJournal.revision()) : 0};
  }

  /** Проверяет, что асинхронное хеширование не открыло незарегистрированное окно изменения. */
  function flush(tx) {
    if (tx.mutation.takeRecords().length) tx.change('dom-mutation-pending');
    requireValue(!tx.released, 'Capture aborted or expired');
    if (BigInt(nanos()) >= BigInt(tx.request.deadlineNanos)) tx.change('deadline');
  }

  return {
    /** Готовит живые raw/observation в одном обороте JS после загрузки шрифтов, изображений и двух кадров. */
    async prepareCapture(request) {
      requireValue(!disposed && !transaction, 'Capture already active or observer disposed');
      const identity = requestIdentity(request); requireValue(!used.has(identity.captureId), 'Reused captureId');
      requireValue(used.size < 10000, 'Capture identity history limit');
      const copied = structuredClone(request); used.add(identity.captureId);
      const tx = {identity, request: copied, handle: crypto.randomUUID(), phase: 'warming', epoch: 0, layout: 0, paint: 0,
        changes: [], events: [], subscriptions: [], barriers: new Set(), frame: 0, released: false}; transaction = tx;
      try {
        observe(tx);
        await bounded(awaitIdle(copied), tx);
        const prepared = await bounded(prepareRaw(copied), tx);
        await bounded(document.fonts.ready, tx);
        const images = [...toolbar.querySelectorAll('img'), ...summary.querySelectorAll('img')].filter(visible);
        await bounded(Promise.all(images.map(/** Ждёт завершения декодирования фактически установленного изображения. */ image => image.decode())), tx);
        for (let i = 0; i < 2; i++) await bounded(nextFrame(tx), tx);
        flush(tx); tx.phase = 'reading'; tx.changes = [];
        const before = revisions(tx), startNanos = nanos(), sampled = collect();
        const raw = readRaw(copied, prepared);
        requireValue(raw && typeof raw.then !== 'function' && raw.schema === 1 && raw.client === 'web' && raw.scenario === copied.scenario && raw.step === copied.step
          && raw.frame && raw.frame.contentWidth === innerWidth && raw.frame.contentHeight === innerHeight, 'Synchronous actual raw identity/viewport');
        const rawCopy = structuredClone(raw), value = fingerprint();
        tx.before = before; tx.startNanos = startNanos; tx.fingerprint = value; tx.lastFingerprint = value;
        tx.hash = await bounded(sha256(new TextEncoder().encode(value)), tx); flush(tx);
        const after = revisions(tx);
        requireValue(JSON.stringify(before) === JSON.stringify(after) && !tx.changes.length && value === fingerprint(), 'Changed during prepare');
        tx.observation = {schema: 1, kind: 'widget-paint-observation', identity, ...sampled,
          synchronization: {mode: 'bracketed-cdp', epochBefore: before.epoch, epochAfter: after.epoch,
            layoutRevisionBefore: before.layout, layoutRevisionAfter: after.layout, paintRevisionBefore: before.paint, paintRevisionAfter: after.paint,
            renderGenerationBefore: before.render, renderGenerationAfter: after.render, frameId: identity.captureId + ':raf-' + tx.frameTime,
            fingerprintBefore: tx.hash, fingerprintAfter: tx.hash, startNanos, endNanos: nanos(), settled: false, changes: []}};
        requireValue(new TextEncoder().encode(JSON.stringify(tx.observation)).length <= 4 * MAX_BYTES, 'Paint JSON limit');
        tx.phase = 'prepared';
        const delay = Number((BigInt(copied.deadlineNanos) - BigInt(nanos())) / 1000000n);
        tx.expiry = setTimeout(/** Освобождает забытый внешний capture bracket по дедлайну. */ () => release(tx), Math.max(0, Math.min(delay, 2147483647)));
        return {ok: true, value: {handle: tx.handle, captureId: identity.captureId, raw: rawCopy, observation: structuredClone(tx.observation)}};
      } catch (error) { release(tx); throw error; }
    },

    /** Завершает одноразовый CDP-барьер и возвращает окончательное наблюдение для сохранения. */
    async finishCapture(value) {
      const tx = handleFor(value); tx.phase = 'finishing';
      try {
        flush(tx); const final = fingerprint();
        const hash = await bounded(sha256(new TextEncoder().encode(final)), tx); flush(tx);
        const after = revisions(tx);
        if (final !== fingerprint() || tx.fingerprint !== final) tx.change('finish-fingerprint');
        if (after.registry !== tx.before.registry) tx.change('registry-revision');
        if (after.cssom !== tx.before.cssom) tx.change('cssom-revision');
        if (cssomJournal?.complete !== true) tx.change('cssom-journal-incomplete');
        const generations = revisions(tx), before = tx.before;
        const synchronization = {...tx.observation.synchronization, epochAfter: generations.epoch, layoutRevisionAfter: generations.layout,
          paintRevisionAfter: generations.paint, renderGenerationAfter: generations.render, fingerprintAfter: hash, endNanos: nanos(),
          settled: !tx.changes.length, changes: [...tx.changes]};
        const stable = synchronization.settled && synchronization.epochBefore === synchronization.epochAfter
          && before.render === generations.render && synchronization.fingerprintBefore === hash && tx.observation.unsupported.length === 0;
        const observation = {...tx.observation, synchronization};
        return {ok: true, value: {captureId: tx.identity.captureId, stable, synchronization, observation}};
      } finally { requireValue(release(tx).length === 0, 'Capture cleanup failed'); }
    },

    /** Отменяет незавершённую попытку по handle/captureId и немедленно освобождает наблюдателей. */
    abortCapture(value) {
      let tx;
      if (value && Object.keys(value).join() === 'captureId') {
        requireValue(transaction && transaction.identity.captureId === value.captureId && ['warming', 'reading'].includes(transaction.phase), 'Unknown preparing capture');
        tx = transaction;
      } else {
        requireValue(value && Object.keys(value).sort().join() === 'captureId,handle' && transaction
          && transaction.handle === value.handle && transaction.identity.captureId === value.captureId
          && ['prepared', 'finishing'].includes(transaction.phase), 'Unknown capture handle');
        tx = transaction;
      }
      requireValue(release(tx).length === 0, 'Capture cleanup failed');
      return {ok: true, value: {captureId: tx.identity.captureId, aborted: true}};
    },

    /** Удаляет постоянный журнал доверенного ввода и отменяет текущую попытку. */
    dispose() {
      if (disposed) return;
      disposed = true; const errors = transaction ? [...release(transaction)] : [];
      for (const type of inputTypes) {
        try { document.removeEventListener(type, inputEvent, true); } catch (error) { errors.push(error); }
      }
      requireValue(errors.length === 0, 'Capture cleanup failed');
    }
  };
}
