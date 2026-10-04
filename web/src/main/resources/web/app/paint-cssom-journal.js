/**
 * @file Opt-in журнал изменений CSSOM и окружения для наблюдателя краски S5.
 * Создаётся только вместе с test-api observer, передаётся как cssomJournal и освобождается
 * после observer.dispose(). Импорт сам по себе ничего не перехватывает.
 * Обёртки сохраняют this, аргументы, результат, исключение и исходный Promise.
 * Dispose восстанавливает только всё ещё принадлежащий журналу дескриптор.
 * Непокрытые пути перечислены в unsupported(); complete не является флагом разрешения.
 * В произвольной странице полное покрытие недоказуемо: сохранённые ранее native-ссылки,
 * exotic setters CSSStyleDeclaration и мутация массива adoptedStyleSheets обходят обёртки.
 * Поэтому текущий журнал честно возвращает complete=false и не разрешает strict S5.
 */

const activeRealms = new WeakMap();
const METHOD_NAMES = new Set(['setProperty', 'removeProperty', 'insertRule', 'deleteRule', 'addRule', 'removeRule',
  'replace', 'replaceSync', 'appendRule', 'deleteMedium', 'appendMedium', 'set', 'append', 'delete', 'clear',
  'add', 'load', 'animate', 'play', 'pause', 'reverse', 'cancel', 'finish', 'updatePlaybackRate', 'commitStyles', 'persist', 'updateTiming', 'setKeyframes',
  'setFloatValue', 'setStringValue', 'multiplySelf', 'preMultiplySelf', 'translateSelf', 'scaleSelf', 'scale3dSelf',
  'rotateSelf', 'rotateFromVectorSelf', 'rotateAxisAngleSelf', 'skewXSelf', 'skewYSelf', 'invertSelf', 'setMatrixValue']);
const CSS_READ_METHODS = new Set(['constructor', 'item', 'getPropertyValue', 'getPropertyPriority', 'getPropertyCSSValue',
  'getFloatValue', 'getStringValue', 'getCounterValue', 'getRectValue', 'getRGBColorValue', 'findRule',
  'get', 'getAll', 'has', 'entries', 'keys', 'values', 'forEach', 'toString', 'toMatrix', 'to', 'toSum', 'equals',
  'add', 'sub', 'mul', 'div', 'min', 'max', 'multiply', 'translate', 'scale', 'scale3d', 'rotate', 'rotateFromVector',
  'rotateAxisAngle', 'skewX', 'skewY', 'inverse', 'transformPoint', 'toFloat32Array', 'toFloat64Array', 'toJSON']);
const PROTOTYPES = ['CSSStyleDeclaration', 'StyleSheet', 'CSSStyleSheet', 'MediaList', 'CSSRule', 'CSSGroupingRule',
  'CSSConditionRule', 'CSSStyleRule', 'CSSMediaRule', 'CSSSupportsRule', 'CSSImportRule', 'CSSFontFaceRule',
  'CSSPageRule', 'CSSKeyframeRule', 'CSSKeyframesRule', 'CSSNamespaceRule', 'CSSLayerBlockRule', 'CSSLayerStatementRule',
  'CSSContainerRule', 'CSSScopeRule', 'CSSStartingStyleRule', 'CSSPropertyRule', 'CSSPositionTryRule', 'CSSNestedDeclarations',
  'CSSValue', 'CSSPrimitiveValue', 'CSSValueList', 'CSSFontFeatureValuesMap', 'CSSStyleValue', 'CSSKeywordValue', 'CSSUnitValue',
  'CSSUnparsedValue', 'CSSVariableReferenceValue', 'CSSTransformValue', 'CSSTransformComponent', 'CSSTranslate', 'CSSRotate',
  'CSSScale', 'CSSSkew', 'CSSSkewX', 'CSSSkewY', 'CSSPerspective', 'CSSMatrixComponent', 'DOMMatrix', 'DOMMatrixReadOnly',
  'StylePropertyMap', 'StylePropertyMapReadOnly', 'FontFace', 'FontFaceSet', 'Element', 'Animation', 'AnimationEffect', 'KeyframeEffect', 'Document', 'ShadowRoot'];

/** Сравнивает полный дескриптор, не присваивая себе заменённое другим кодом свойство. */
function sameDescriptor(a, b) {
  return !!a && !!b && a.value === b.value && a.get === b.get && a.set === b.set
    && a.writable === b.writable && a.configurable === b.configurable && a.enumerable === b.enumerable;
}

/**
 * Создаёт один ограниченный журнал для realm данного документа.
 * Дополнительный документ/window нужен только для изолированных developer-тестов.
 * subscribe(listener) уведомляет синхронно после перехваченной операции; revision()
 * также забирает недоставленные MutationRecords и проверяет целостность обёрток.
 * Счётчик фиксирует попытки записи, включая ABA и операции с тем же значением.
 * Native replace возвращает исходный Promise без установки then/catch: журнал не
 * меняет unhandledrejection и явно признаёт неизвестную асинхронную фазу замены.
 */
export function createCssomJournal({document: doc = globalThis.document, window: win = doc?.defaultView} = {}) {
  if (!doc || !win || doc.defaultView !== win) throw new Error('CSSOM document realm required');
  if (activeRealms.has(win)) throw new Error('CSSOM journal already active in this realm');
  const listeners = new Set(), patches = [], guards = [], events = [], media = new Map(), issues = new Map(), objectIds = new WeakMap();
  let version = 0, nextId = 0, disposed = false, checking = false, notifying = false, frame = null, mutation = null, lastSnapshot = null;
  const lease = {}; activeRealms.set(win, lease);

  /** Регистрирует наблюдённое изменение, изолируя исключения подписчиков от штатного вызова. */
  function emit(reason) {
    if (disposed) return;
    if (version === Number.MAX_SAFE_INTEGER) { issue('revision-overflow', 'Safe revision limit exhausted', false); return; }
    version++;
    if (notifying) return;
    notifying = true;
    try {
      for (const listener of [...listeners]) {
        if (!listeners.has(listener)) continue;
        try { listener(reason); } catch { issue('subscriber-error', 'Subscriber threw; original operation was preserved', false); }
      }
    } finally { notifying = false; }
  }

  /** Сохраняет устойчивую причину неполного покрытия и уведомляет о первом её появлении. */
  function issue(property, reason, notify = true) {
    if (issues.has(property)) return;
    if (issues.size >= 128) property = 'unsupported-limit';
    if (issues.has(property)) return;
    issues.set(property, {property, reason});
    if (notify) emit('unsupported:' + property);
  }

  /** Даёт фактическому объекту идентичность для наблюдений изменения с возвратом. */
  function id(object) { if (!objectIds.has(object)) objectIds.set(object, ++nextId); return objectIds.get(object); }

  /** Устанавливает точную пересылку одного метода или setter, не затрагивая getter. */
  function patch(prototype, name, label, required = false) {
    if (!prototype) { if (required) issue('missing:' + label, 'Required native prototype unavailable'); return; }
    if (patches.some(/** Не устанавливает вторую обёртку на тот же собственный дескриптор. */ entry => entry.owner === prototype && entry.name === name)) return;
    const original = Object.getOwnPropertyDescriptor(prototype, name);
    if (!original) { if (required) issue('missing:' + label, 'Required native descriptor unavailable'); return; }
    const replacement = {...original}; let modified = false;
    if (typeof original.value === 'function' && METHOD_NAMES.has(name)) {
      if (!original.configurable && !original.writable) { issue('locked:' + label, 'Native method cannot be reversibly wrapped'); return; }
      replacement.value = /** Пересылает вызов с исходными receiver, аргументами, результатом и исключением. */ function (...args) {
        try { return Reflect.apply(original.value, this, args); }
        finally {
          if (name === 'replace' || name === 'load') issue('async:' + label, 'Completion cannot be attached without changing original Promise rejection handling');
          emit('method:' + label);
        }
      };
      modified = true;
    }
    if (typeof original.set === 'function') {
      if (!original.configurable) { issue('locked:' + label, 'Native setter cannot be reversibly wrapped'); return; }
      replacement.set = /** Пересылает исходный setter, не подменяя значение или штатное исключение. */ function (...args) {
        try { return Reflect.apply(original.set, this, args); }
        finally { emit('setter:' + label); }
      };
      modified = true;
    }
    if (!modified) { if (required) issue('unwrapped:' + label, 'Required mutation entry point is not an observable method/setter'); return; }
    try { Object.defineProperty(prototype, name, replacement); patches.push({owner: prototype, name, original, replacement, label}); }
    catch { issue('install:' + label, 'Descriptor wrapper installation failed'); }
  }

  /** Подключает событие и сохраняет точные параметры его последующего удаления. */
  function listen(target, type, reason, capture = false) {
    if (!target?.addEventListener || !target?.removeEventListener) { issue('events:' + reason, 'EventTarget API unavailable'); return; }
    const callback = /** Регистрирует фактически доставленное событие окружения. */ () => emit('event:' + reason);
    target.addEventListener(type, callback, capture); events.push({target, type, callback, capture});
  }

  /** Подписывает фактически используемое media-условие, не заменяя его ожидаемым состоянием. */
  function watchMedia(query) {
    if (!query || media.has(query)) return;
    if (media.size >= 256) { issue('media-limit', 'Media query census limit'); return; }
    try {
      const target = win.matchMedia(query); media.set(query, target); listen(target, 'change', 'media:' + query);
    } catch { issue('media-query', 'Media query subscription failed'); }
  }

  /** Обходит реальные вложенные правила и импортированные stylesheet с защитой от циклов. */
  function sheetSnapshot(sheet, seen, values, depth = 0) {
    if (seen.has(sheet)) return;
    if (depth > 32 || seen.size >= 256) { issue('stylesheet-limit', 'Stylesheet nesting/census limit'); return; }
    seen.add(sheet);
    try {
      watchMedia(sheet.media?.mediaText);
      values.push(['sheet', id(sheet), sheet.href, sheet.disabled, sheet.media?.mediaText]);
      /** Читает фактическое CSSOM-дерево, включая условия media и импортированные листы. */
      function rules(list, nesting) {
        if (nesting > 32 || list.length > 10000) { issue('rule-limit', 'CSS rule nesting/census limit'); return; }
        for (const rule of list) {
          values.push(['rule', id(rule), rule.cssText]); watchMedia(rule.media?.mediaText);
          if (rule.styleSheet) sheetSnapshot(rule.styleSheet, seen, values, depth + 1);
          if (rule.cssRules) rules(rule.cssRules, nesting + 1);
        }
      }
      rules(sheet.cssRules, 0);
    } catch { issue('inaccessible-stylesheet', 'CSSOM cannot be read completely'); }
  }

  /** Читает реальные листы, adopted-массивы, шрифты, viewport и текущие анимации. */
  function snapshot() {
    const values = [win.innerWidth, win.innerHeight, win.devicePixelRatio, doc.visibilityState,
      win.visualViewport ? [win.visualViewport.width, win.visualViewport.height, win.visualViewport.offsetLeft, win.visualViewport.offsetTop, win.visualViewport.scale] : null];
    const seen = new Set();
    for (const sheet of doc.styleSheets || []) sheetSnapshot(sheet, seen, values);
    const adopted = doc.adoptedStyleSheets || [];
    values.push(['adopted', ...adopted.map(/** Сохраняет порядок и идентичность реально принятых листов. */ sheet => id(sheet))]);
    for (const sheet of adopted) sheetSnapshot(sheet, seen, values);
    watchMedia('(resolution: ' + win.devicePixelRatio + 'dppx)');
    if (doc.fonts) {
      values.push(['fonts', doc.fonts.status]);
      for (const font of doc.fonts) values.push(['font', id(font), font.family, font.style, font.weight, font.stretch, font.status, font.unicodeRange, font.featureSettings, font.variationSettings]);
      if (doc.fonts.status !== 'loaded') issue('fonts-loading', 'Font decode/loading is active');
    } else issue('font-api', 'FontFaceSet observation unavailable');
    if (typeof doc.getAnimations === 'function') {
      for (const animation of doc.getAnimations()) {
        values.push(['animation', id(animation), animation.playState, animation.currentTime, animation.startTime, animation.playbackRate]);
        if (!['idle', 'finished'].includes(animation.playState)) issue('active-animation', 'Autonomous timeline cannot be certified by endpoint equality');
      }
    } else issue('animation-api', 'Animation census unavailable');
    for (const node of doc.querySelectorAll?.('iframe, frame, object, embed, *') || []) {
      if (node.shadowRoot) issue('shadow-root', 'Additional shadow realm/adoption is outside this document journal');
      if (['IFRAME', 'FRAME', 'OBJECT', 'EMBED'].includes(node.tagName)) issue('foreign-realm', 'Embedded document mutation paths are outside this realm');
    }
    return JSON.stringify(values);
  }

  /** Забирает pending DOM-записи и проверяет, не заменил ли другой код установленные обёртки. */
  function audit() {
    if (disposed || checking) return;
    checking = true;
    try {
      if (mutation?.takeRecords().length) emit('dom-records');
      for (const entry of patches) if (!sameDescriptor(Object.getOwnPropertyDescriptor(entry.owner, entry.name), entry.replacement))
        issue('override:' + entry.label, 'Wrapper replaced by another owner; replacement is preserved');
      for (const guard of guards) {
        if (win[guard.name] !== guard.constructor || win[guard.name]?.prototype !== guard.owner
          || Object.getPrototypeOf(guard.owner) !== guard.parent)
          issue('prototype:' + guard.name, 'Native constructor/prototype chain changed; new paths are not covered');
        const keys = Reflect.ownKeys(guard.owner);
        if (keys.length !== guard.descriptors.size || keys.some(/** Проверяет добавление, удаление и замену любого собственного дескриптора. */ key =>
          !sameDescriptor(Object.getOwnPropertyDescriptor(guard.owner, key), guard.descriptors.get(key))))
          issue('surface:' + guard.name, 'Prototype surface changed; added or altered entry points are not covered');
      }
      const currentNames = new Set(PROTOTYPES);
      for (const name of Object.getOwnPropertyNames(win)) if (/^CSS[A-Za-z]+$/.test(name)) currentNames.add(name);
      for (const name of currentNames) if (win[name]?.prototype
        && !guards.some(/** Находит прототип, переписанный при установке журнала. */ guard => guard.name === name))
        issue('new-prototype:' + name, 'Observed API prototype appeared after journal installation');
      const value = snapshot();
      if (lastSnapshot !== null && value !== lastSnapshot) emit('observed-snapshot');
      lastSnapshot = value;
    } catch { issue('observation-error', 'Environment/CSSOM observation could not complete'); }
    finally { checking = false; }
  }

  /** Удаляет подписки и восстанавливает только собственные всё ещё установленные дескрипторы. */
  function dispose() {
    if (disposed) return;
    emit('dispose'); disposed = true;
    /** Продолжает освобождение остальных ресурсов даже при отказе одного native API. */
    function cleanup(label, operation) {
      try { operation(); } catch { issue('cleanup:' + label, 'Resource release failed', false); }
    }
    if (frame !== null) cleanup('frame', /** Отменяет собственный запланированный кадр. */ () => win.cancelAnimationFrame?.(frame));
    cleanup('mutation', /** Отключает собственный DOM observer. */ () => mutation?.disconnect());
    for (const {target, type, callback, capture} of events)
      cleanup('event:' + type, /** Удаляет конкретную подписку без вмешательства в чужие listeners. */ () => target.removeEventListener(type, callback, capture));
    let restored = true;
    for (let i = patches.length - 1; i >= 0; i--) {
      const entry = patches[i];
      if (!sameDescriptor(Object.getOwnPropertyDescriptor(entry.owner, entry.name), entry.replacement)) continue;
      try { Object.defineProperty(entry.owner, entry.name, entry.original); }
      catch { restored = false; issue('restore:' + entry.label, 'Own descriptor restoration failed', false); }
    }
    listeners.clear();
    if (restored && activeRealms.get(win) === lease) activeRealms.delete(win);
  }

  try {
    issue('escaped-native-references', 'Previously retained native methods/setters and other-realm invocations cannot be excluded');
    issue('named-css-setters', 'Exotic CSSStyleDeclaration property/index writes may bypass prototype descriptors; rule ABA has no MutationObserver');
    issue('external-renderer-timing', 'Font/media/viewport events can be coalesced; browser compositor changes are not an atomic paint journal');
    if ('adoptedStyleSheets' in doc) issue('adopted-array-aba', 'In-place adoptedStyleSheets array writes cannot be intercepted without changing native getter/array semantics');
    const names = new Set(PROTOTYPES);
    for (const name of Object.getOwnPropertyNames(win)) if (/^CSS[A-Za-z]+$/.test(name)) names.add(name);
    for (const name of names) {
      const prototype = win[name]?.prototype; if (!prototype) continue;
      for (const key of Reflect.ownKeys(prototype)) {
        if (name === 'Element' && key !== 'animate') continue;
        if ((name === 'Document' || name === 'ShadowRoot') && key !== 'adoptedStyleSheets') continue;
        const descriptor = Object.getOwnPropertyDescriptor(prototype, key);
        const label = name + '.' + String(key);
        const immutableMath = /^CSS(?:NumericValue|Math[A-Za-z]+)$/.test(name);
        if (descriptor?.set || (METHOD_NAMES.has(key) && !(immutableMath && key === 'add'))) patch(prototype, key, label);
        else if ((name.startsWith('CSS') || name.startsWith('StylePropertyMap') || name.startsWith('DOMMatrix')) && typeof descriptor?.value === 'function'
          && !CSS_READ_METHODS.has(key) && key !== Symbol.iterator)
          issue('unknown-method:' + label, 'Unclassified CSS method cannot be certified as read-only');
      }
      if (name === 'CSSUnparsedValue' || name === 'CSSTransformValue')
        issue('indexed-css-values:' + name, 'Exotic CSS value index writes bypass prototype setters');
    }
    for (const [name, methods] of [['CSSStyleDeclaration', ['setProperty', 'removeProperty']], ['CSSStyleSheet', ['insertRule', 'deleteRule', 'replaceSync', 'replace']]])
      for (const method of methods) patch(win[name]?.prototype, method, name + '.' + method, true);
    for (const name of names) {
      const constructor = win[name], owner = constructor?.prototype; if (!owner) continue;
      guards.push({name, constructor, owner, parent: Object.getPrototypeOf(owner),
        descriptors: new Map(Reflect.ownKeys(owner).map(/** Запоминает поверхность после установки собственных обёрток. */ key => [key, Object.getOwnPropertyDescriptor(owner, key)]))});
    }
    if (win.MutationObserver && doc.documentElement) {
      mutation = new win.MutationObserver(/** Регистрирует доставленные изменения DOM, включая style/text и возврат прежних значений. */ records => { if (records.length) emit('dom-mutation'); });
      mutation.observe(doc.documentElement, {subtree: true, childList: true, attributes: true, characterData: true});
    } else issue('mutation-observer', 'DOM ABA observer unavailable');
    for (const type of ['resize', 'scroll', 'orientationchange']) listen(win, type, 'window:' + type, true);
    if (win.visualViewport) for (const type of ['resize', 'scroll']) listen(win.visualViewport, type, 'visual-viewport:' + type);
    for (const type of ['load', 'error', 'visibilitychange', 'animationstart', 'animationend', 'animationiteration', 'animationcancel', 'transitionrun', 'transitionend', 'transitioncancel'])
      listen(doc, type, 'document:' + type, true);
    if (doc.fonts) for (const type of ['loading', 'loadingdone', 'loadingerror']) listen(doc.fonts, type, 'fonts:' + type);
    audit();
    /** Проверяет фактическую среду на каждом кадре, не объявляя это защитой от ABA. */
    function tick() { if (disposed) return; audit(); if (!disposed) frame = win.requestAnimationFrame(tick); }
    if (win.requestAnimationFrame && win.cancelAnimationFrame) frame = win.requestAnimationFrame(tick);
    else issue('frame-observer', 'Frame observation API unavailable');
  } catch (error) { dispose(); throw error; }

  return {
    /** Возвращает полноту только при отсутствии всех явно зафиксированных пробелов покрытия. */
    get complete() { audit(); return !disposed && issues.size === 0; },
    /** Читает монотонный счётчик, включая ещё не доставленные DOM-записи. */
    revision() { audit(); return version; },
    /** Подключает уведомления и возвращает идемпотентную отмену конкретной подписки. */
    subscribe(listener) {
      if (disposed || typeof listener !== 'function') throw new Error('Active CSSOM subscriber required');
      listeners.add(listener);
      return /** Освобождает подписку, не затрагивая остальных наблюдателей. */ () => listeners.delete(listener);
    },
    /** Возвращает отдельные копии диагностик неполного покрытия для передачи MAIN. */
    unsupported() { audit(); return [...issues.values()].map(/** Копирует диагностическую запись без изменения внутреннего журнала. */ value => ({...value})); },
    /** Освобождает только ресурсы и дескрипторы, которыми всё ещё владеет этот журнал. */
    dispose
  };
}
