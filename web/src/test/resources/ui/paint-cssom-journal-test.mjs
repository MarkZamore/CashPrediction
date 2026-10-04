/** @file Изолированные developer-проверки CSSOM-журнала, без GUI и сторонних пакетов. */
import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
import test from 'node:test';

const source = await readFile(new URL('../../../main/resources/web/app/paint-cssom-journal.js', import.meta.url), 'utf8');
const {createCssomJournal} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));

/** Создаёт отдельный realm со собственными дескрипторами для проверки точного forwarding. */
function realm() {
  /** Хранит фактические тестовые CSS-значения и результаты native-подобных операций. */
  class CSSStyleDeclaration {
    /** Создаёт независимые значения и журнал receiver/аргументов. */
    constructor() { this.values = new Map(); this.calls = []; this.failure = null; }
    /** Записывает значение через метод, результат которого нельзя подменять. */
    setProperty(...args) { this.calls.push({receiver: this, args}); if (this.failure) throw this.failure; this.values.set(args[0], args[1]); return this.result; }
    /** Удаляет свойство и возвращает исходное значение. */
    removeProperty(name) { const value = this.values.get(name); this.values.delete(name); return value; }
    /** Читает фактический текст тестовой декларации. */
    get cssText() { return this.text; }
    /** Записывает текст с контролируемым исключением. */
    set cssText(value) { if (this.failure) throw this.failure; this.text = value; }
  }
  /** Хранит реальное дерево тестового листа и исходный Promise replace. */
  class CSSStyleSheet {
    /** Создаёт независимое дерево правил. */
    constructor() { this.cssRules = []; this.media = {mediaText: ''}; this.disabled = false; this.promise = Promise.resolve(this); }
    /** Вставляет правило с фактическим индексом. */
    insertRule(text, index) { this.cssRules.splice(index, 0, {cssText: text}); return index; }
    /** Удаляет правило с заданным индексом. */
    deleteRule(index) { this.cssRules.splice(index, 1); }
    /** Заменяет дерево в текущем обороте JavaScript. */
    replaceSync(text) { this.cssRules = [{cssText: text}]; }
    /** Возвращает строго исходный Promise, не создавая продолжения. */
    replace() { return this.promise; }
  }
  /** Хранит mutable adopted-массив и минимальную наблюдаемую среду. */
  class TestDocument extends EventTarget {
    /** Создаёт пустую страницу без анимаций. */
    constructor() { super(); this.sheets = []; this.adopted = []; this.documentElement = {}; this.visibilityState = 'visible'; }
    /** Возвращает реально подключённые листы. */
    get styleSheets() { return this.sheets; }
    /** Возвращает тот же mutable adopted-массив. */
    get adoptedStyleSheets() { return this.adopted; }
    /** Меняет adopted-массив без подмены native-подобной семантики. */
    set adoptedStyleSheets(value) { this.adopted = value; }
    /** Возвращает фактические активные анимации. */
    getAnimations() { return []; }
    /** Возвращает отсутствующие в изолированной среде дочерние узлы. */
    querySelectorAll() { return []; }
  }
  /** Сохраняет pending MutationRecords для синхронного drain при revision. */
  class MutationObserver {
    /** Запоминает исходный callback. */
    constructor(callback) { this.callback = callback; this.records = []; instances.push(this); }
    /** Обозначает начало наблюдения. */
    observe() { this.active = true; }
    /** Возвращает и потребляет pending записи. */
    takeRecords() { return this.records.splice(0); }
    /** Останавливает наблюдение и освобождает записи. */
    disconnect() { this.active = false; this.records = []; }
  }
  const win = new EventTarget(), doc = new TestDocument(), instances = [], frames = new Map(), queries = new Map(); let frameId = 0;
  doc.defaultView = win; doc.fonts = new EventTarget(); doc.fonts.status = 'loaded';
  doc.fonts[Symbol.iterator] = /** Возвращает пустой набор загруженных шрифтов. */ function* () {};
  Object.assign(win, {CSSStyleDeclaration, CSSStyleSheet, Document: TestDocument, MutationObserver, innerWidth: 1200, innerHeight: 800, devicePixelRatio: 1});
  win.visualViewport = new EventTarget(); Object.assign(win.visualViewport, {width: 1200, height: 800, scale: 1, offsetLeft: 0, offsetTop: 0});
  win.matchMedia = /** Возвращает один фактический EventTarget для каждого тестового media-условия. */ query => {
    if (!queries.has(query)) queries.set(query, new EventTarget()); return queries.get(query);
  };
  win.requestAnimationFrame = /** Планирует наблюдение без запуска браузерного цикла. */ callback => { frames.set(++frameId, callback); return frameId; };
  win.cancelAnimationFrame = /** Удаляет собственное запланированное наблюдение. */ handle => frames.delete(handle);
  return {doc, win, instances, frames, queries, CSSStyleDeclaration, CSSStyleSheet};
}

test('import does not patch; exact forwarding and descriptor restoration', /** Проверяет opt-in установку и полное восстановление собственного дескриптора. */ () => {
  const r = realm(), owner = r.CSSStyleDeclaration.prototype, original = Object.getOwnPropertyDescriptor(owner, 'setProperty');
  const journal = createCssomJournal({document: r.doc, window: r.win});
  try {
    const style = new r.CSSStyleDeclaration(), token = {}; style.result = token;
    assert.equal(style.setProperty('color', 'red', 'important'), token);
    assert.equal(style.calls[0].receiver, style); assert.deepEqual(style.calls[0].args, ['color', 'red', 'important']);
    const failure = new Error('native failure'); style.failure = failure;
    assert.throws(/** Вызывает native-подобное исключение через обёртку. */ () => style.setProperty('color', 'blue'), /** Проверяет идентичность штатного исключения. */ error => error === failure);
  } finally { journal.dispose(); }
  assert.deepEqual(Object.getOwnPropertyDescriptor(owner, 'setProperty'), original); assert.equal(r.frames.size, 0);
});

test('property and rule ABA increments revision synchronously', /** Проверяет два конца ABA при равном итоговом состоянии. */ () => {
  const r = realm(), journal = createCssomJournal({document: r.doc, window: r.win});
  try {
    const style = new r.CSSStyleDeclaration(), sheet = new r.CSSStyleSheet(); let notifications = 0;
    const unsubscribe = journal.subscribe(/** Считает каждую доставленную запись операции. */ () => { notifications++; });
    let before = journal.revision(); style.setProperty('color', 'red'); style.removeProperty('color');
    assert.equal(journal.revision() - before, 2); assert.equal(notifications, 2); assert.equal(style.values.size, 0);
    before = journal.revision(); sheet.insertRule('x { color: red; }', 0); sheet.deleteRule(0);
    assert.equal(journal.revision() - before, 2); assert.equal(sheet.cssRules.length, 0); unsubscribe();
  } finally { journal.dispose(); }
});

test('setters retain exceptions and asynchronous replace returns same Promise', /** Проверяет setter и отсутствие изменяющего обработку отказов then/catch. */ () => {
  const r = realm(), journal = createCssomJournal({document: r.doc, window: r.win});
  try {
    const style = new r.CSSStyleDeclaration(), failure = new Error('setter'); style.failure = failure;
    assert.throws(/** Передаёт запись через перехваченный native setter. */ () => { style.cssText = 'x'; }, /** Проверяет исходный объект ошибки setter. */ error => error === failure);
    const sheet = new r.CSSStyleSheet(); let thenCalls = 0;
    sheet.promise = {then: /** Обнаруживает запрещённое присоединение продолжения к результату replace. */ () => { thenCalls++; }};
    assert.equal(sheet.replace('x {}'), sheet.promise); assert.equal(thenCalls, 0);
    assert.ok(journal.unsupported().some(/** Находит честно непокрытую асинхронную фазу replace. */ issue => issue.property === 'async:CSSStyleSheet.replace'));
  } finally { journal.dispose(); }
});

test('subscriber failure cannot change native result', /** Проверяет изоляцию ошибочного подписчика от записи CSSOM. */ () => {
  const r = realm(), journal = createCssomJournal({document: r.doc, window: r.win});
  try {
    journal.subscribe(/** Имитирует ошибку обработчика наблюдений. */ () => { throw new Error('subscriber'); });
    const style = new r.CSSStyleDeclaration(); style.result = 42;
    assert.equal(style.setProperty('opacity', '0.5'), 42); assert.equal(style.values.get('opacity'), '0.5');
    assert.equal(journal.complete, false);
  } finally { journal.dispose(); }
});

test('third party override survives dispose and is reported', /** Проверяет уважение новой собственности на заменённый метод. */ () => {
  const r = realm(), journal = createCssomJournal({document: r.doc, window: r.win});
  const override = /** Представляет новую стороннюю реализацию после создания журнала. */ function () { return 'third-party'; };
  Object.defineProperty(r.CSSStyleDeclaration.prototype, 'setProperty', {value: override, writable: true, configurable: true});
  assert.ok(journal.unsupported().some(/** Находит причину потери установленной обёртки. */ issue => issue.property === 'override:CSSStyleDeclaration.setProperty'));
  journal.dispose(); assert.equal(r.CSSStyleDeclaration.prototype.setProperty, override);
});

test('pending DOM ABA is drained; adopted arrays retain native identity and remain unsupported', /** Проверяет журнал недоставленных записей и честную границу adopted-массивов. */ () => {
  const r = realm(), array = r.doc.adoptedStyleSheets, journal = createCssomJournal({document: r.doc, window: r.win});
  try {
    assert.equal(r.doc.adoptedStyleSheets, array); array.push(new r.CSSStyleSheet()); array.pop();
    assert.equal(journal.complete, false);
    assert.ok(journal.unsupported().some(/** Находит непокрытый ABA массива adoptedStyleSheets. */ issue => issue.property === 'adopted-array-aba'));
    const before = journal.revision(); r.instances[0].records.push({}, {}); assert.equal(journal.revision(), before + 1);
  } finally { journal.dispose(); }
});

test('fonts media viewport events notify and subscriptions are removed', /** Проверяет подключение окружения и полное прекращение его уведомлений после dispose. */ () => {
  const r = realm(), sheet = new r.CSSStyleSheet(); sheet.media.mediaText = '(min-width: 1px)'; r.doc.sheets.push(sheet);
  const journal = createCssomJournal({document: r.doc, window: r.win}); let count = 0;
  journal.subscribe(/** Считает события независимых источников окружения. */ () => { count++; });
  r.doc.fonts.dispatchEvent(new Event('loadingdone')); r.win.visualViewport.dispatchEvent(new Event('resize'));
  r.queries.get('(min-width: 1px)').dispatchEvent(new Event('change')); assert.equal(count, 3);
  journal.dispose(); const after = count;
  r.doc.fonts.dispatchEvent(new Event('loadingdone')); r.win.dispatchEvent(new Event('resize')); assert.equal(count, after);
});

test('one opt-in journal per realm and idempotent disposal', /** Проверяет ограничение владения обёртками и повторный запуск после освобождения. */ () => {
  const r = realm(), journal = createCssomJournal({document: r.doc, window: r.win});
  assert.throws(/** Пытается установить конкурирующий журнал в тот же realm. */ () => createCssomJournal({document: r.doc, window: r.win}));
  journal.dispose(); journal.dispose(); assert.equal(journal.complete, false);
  const next = createCssomJournal({document: r.doc, window: r.win}); next.dispose();
});

test('cached native bypass is not misrepresented as complete observation', /** Проверяет честный отказ при обходе установленной обёртки через сохранённый метод. */ () => {
  const r = realm(), cached = r.CSSStyleDeclaration.prototype.setProperty, journal = createCssomJournal({document: r.doc, window: r.win});
  try {
    const style = new r.CSSStyleDeclaration(), before = journal.revision();
    Reflect.apply(cached, style, ['color', 'red']); style.values.delete('color');
    assert.equal(journal.revision(), before); assert.equal(journal.complete, false);
    assert.ok(journal.unsupported().some(/** Находит необнаружимый ранее сохранённый native-путь. */ issue => issue.property === 'escaped-native-references'));
  } finally { journal.dispose(); }
});

test('locked descriptors are preserved and coverage is explicitly unsupported', /** Проверяет отсутствие принудительной замены недоступного дескриптора. */ () => {
  const r = realm(), owner = r.CSSStyleSheet.prototype, original = Object.getOwnPropertyDescriptor(owner, 'insertRule');
  Object.defineProperty(owner, 'insertRule', {...original, writable: false, configurable: false});
  const journal = createCssomJournal({document: r.doc, window: r.win});
  try {
    assert.equal(owner.insertRule, original.value);
    assert.ok(journal.unsupported().some(/** Находит недоступный для восстановления CSSOM-метод. */ issue => issue.property === 'locked:CSSStyleSheet.insertRule'));
  } finally { journal.dispose(); }
  assert.equal(owner.insertRule, original.value);
});

test('adopted setter ABA is counted without proxying the native array', /** Проверяет перехватываемое присваивание adoptedStyleSheets отдельно от непокрытой мутации массива. */ () => {
  const r = realm(), original = r.doc.adoptedStyleSheets, journal = createCssomJournal({document: r.doc, window: r.win});
  try {
    const before = journal.revision(); r.doc.adoptedStyleSheets = []; r.doc.adoptedStyleSheets = original;
    assert.equal(journal.revision(), before + 2); assert.equal(r.doc.adoptedStyleSheets, original);
  } finally { journal.dispose(); }
});

test('active autonomous animation has its own unsupported reason', /** Проверяет отдельную диагностику timeline, которую нельзя доказать равенством конечных состояний. */ () => {
  const r = realm(), journal = createCssomJournal({document: r.doc, window: r.win});
  try {
    r.doc.getAnimations = /** Возвращает фактически активный объект тестовой timeline. */ () => [{playState: 'running', currentTime: 12, startTime: 0, playbackRate: 1}];
    assert.ok(journal.unsupported().some(/** Находит непокрытое автономное продвижение анимации. */ issue => issue.property === 'active-animation'));
    assert.equal(journal.complete, false);
  } finally { journal.dispose(); }
});

test('remaining value and feature map entry points preserve forwarding and restore descriptors', /** Проверяет новые семейства без повторного перечисления прежних mutators листов. */ () => {
  const r = realm(), owners = [];
  for (const [name, methods, setter] of [
    ['CSSPrimitiveValue', ['setFloatValue', 'setStringValue'], 'cssText'],
    ['CSSFontFeatureValuesMap', ['set', 'delete', 'clear'], null],
    ['CSSKeywordValue', [], 'value'], ['CSSUnitValue', [], 'value'],
    ['CSSVariableReferenceValue', [], 'variable'], ['CSSTranslate', [], 'x'],
    ['CSSRotate', [], 'angle'], ['CSSScale', [], 'x'], ['CSSSkew', [], 'ax'],
    ['CSSPerspective', [], 'length'], ['CSSMatrixComponent', [], 'matrix'],
    ['DOMMatrix', ['multiplySelf', 'preMultiplySelf', 'translateSelf', 'scaleSelf', 'scale3dSelf', 'rotateSelf',
      'rotateFromVectorSelf', 'rotateAxisAngleSelf', 'skewXSelf', 'skewYSelf', 'invertSelf', 'setMatrixValue'], 'm11']
  ]) {
    /** Представляет отдельное семейство CSSOM с контролируемой native-подобной семантикой. */
    class Value {}
    for (const method of methods) Object.defineProperty(Value.prototype, method, {configurable: true, writable: true,
      /** Сохраняет receiver, аргументы, возвращаемый объект и исходную ошибку. */
      value: function (...args) { this.args = args; if (this.failure) throw this.failure; return this; }});
    if (setter) Object.defineProperty(Value.prototype, setter, {configurable: true,
      /** Читает последнее записанное значение. */ get() { return this.saved; },
      /** Записывает значение, сохраняя исходную ошибку. */ set(value) { if (this.failure) throw this.failure; this.saved = value; }});
    r.win[name] = Value;
    owners.push({name, Value, methods, setter, descriptors: Object.getOwnPropertyDescriptors(Value.prototype)});
  }
  const journal = createCssomJournal({document: r.doc, window: r.win});
  try {
    for (const {Value, methods, setter} of owners) {
      const value = new Value(), failure = new Error('native-value');
      for (const method of methods) {
        let before = journal.revision(); assert.equal(value[method](17, 'px'), value);
        assert.deepEqual(value.args, [17, 'px']); assert.equal(journal.revision(), before + 1);
        value.failure = failure; before = journal.revision();
        assert.throws(/** Вызывает исходную ошибку нового mutator. */ () => value[method](0),
          /** Сравнивает штатное исключение по идентичности. */ error => error === failure);
        assert.equal(journal.revision(), before + 1); value.failure = null;
      }
      if (setter) {
        const before = journal.revision(); value[setter] = 17; value[setter] = 0;
        assert.equal(journal.revision(), before + 2); assert.equal(value[setter], 0);
        value.failure = failure;
        assert.throws(/** Проверяет отказ нового setter без подмены исключения. */ () => { value[setter] = 1; },
          /** Сравнивает исходную ошибку. */ error => error === failure);
      }
    }
    assert.equal(journal.complete, false);
  } finally { journal.dispose(); }
  for (const {Value, descriptors} of owners) assert.deepEqual(Object.getOwnPropertyDescriptors(Value.prototype), descriptors);
});

test('unknown CSS methods and indexed values explicitly remain unsupported', /** Проверяет строгий отказ для неизвестных методов и exotic записей Typed OM. */ () => {
  const r = realm();
  /** Представляет новый неразобранный браузерный API. */
  class CSSFutureValue { /** Имитирует мутацию, которой нет в утверждённом списке. */ rewrite() {} }
  /** Представляет exotic индексный контейнер Typed OM. */
  class CSSUnparsedValue {}
  /** Представляет exotic контейнер transform-компонентов. */
  class CSSTransformValue {}
  Object.assign(r.win, {CSSFutureValue, CSSUnparsedValue, CSSTransformValue});
  const journal = createCssomJournal({document: r.doc, window: r.win});
  try {
    const properties = journal.unsupported().map(/** Читает устойчивый диагностический ключ. */ entry => entry.property);
    assert.ok(properties.includes('unknown-method:CSSFutureValue.rewrite'));
    assert.ok(properties.includes('indexed-css-values:CSSUnparsedValue'));
    assert.ok(properties.includes('indexed-css-values:CSSTransformValue'));
    assert.equal(journal.complete, false);
  } finally { journal.dispose(); }
});

test('prototype guards reject additions chain replacement and late constructors without clobbering them', /** Проверяет целостность всей поверхности, а не только уже обёрнутых методов. */ () => {
  const r = realm(), owner = r.CSSStyleSheet.prototype, journal = createCssomJournal({document: r.doc, window: r.win});
  const extra = /** Представляет сторонний путь записи после установки журнала. */ function () {};
  const parent = {};
  Object.defineProperty(owner, 'futureMutation', {value: extra, configurable: true});
  Object.setPrototypeOf(owner, parent);
  /** Представляет CSS API, появившийся после переписи. */
  class CSSLateValue {}
  /** Представляет стороннюю замену конструктора в realm. */
  class Replacement {}
  r.win.CSSLateValue = CSSLateValue; r.win.CSSStyleSheet = Replacement;
  const properties = journal.unsupported().map(/** Читает причину потери покрытия. */ entry => entry.property);
  assert.ok(properties.includes('surface:CSSStyleSheet')); assert.ok(properties.includes('prototype:CSSStyleSheet'));
  assert.ok(properties.includes('new-prototype:CSSLateValue'));
  journal.dispose(); assert.equal(owner.futureMutation, extra); assert.equal(Object.getPrototypeOf(owner), parent);
  assert.equal(r.win.CSSStyleSheet, Replacement);
});

test('dispose during frame audit cannot schedule another frame', /** Проверяет отмену кадра из синхронного listener наблюдения. */ () => {
  const r = realm(), journal = createCssomJournal({document: r.doc, window: r.win});
  journal.subscribe(/** Освобождает журнал в ходе проверки изменившегося viewport. */ () => journal.dispose());
  const [handle, callback] = [...r.frames][0]; r.frames.delete(handle); r.win.innerWidth++;
  callback(); assert.equal(r.frames.size, 0); assert.equal(journal.complete, false);
});

test('cleanup failure does not prevent remaining descriptor restoration', /** Проверяет продолжение очистки после исключения native API. */ () => {
  const r = realm(), original = Object.getOwnPropertyDescriptors(r.CSSStyleSheet.prototype);
  const journal = createCssomJournal({document: r.doc, window: r.win});
  r.instances[0].disconnect = /** Имитирует отказ одного observer при освобождении. */ () => { throw new Error('disconnect'); };
  journal.dispose(); assert.equal(r.frames.size, 0);
  assert.deepEqual(Object.getOwnPropertyDescriptors(r.CSSStyleSheet.prototype), original);
  assert.ok(journal.unsupported().some(/** Находит сохранённый отказ cleanup. */ entry => entry.property === 'cleanup:mutation'));
  const next = createCssomJournal({document: r.doc, window: r.win}); next.dispose();
});

test('immutable numeric addition remains untouched and inherited matrix guards stay strict', /** Отличает создание нового числового значения от мутации матрицы на месте. */ () => {
  const r = realm();
  /** Представляет неизменяемую арифметику Typed OM. */
  class CSSNumericValue { /** Возвращает новое значение вместо изменения receiver. */ add() { return {}; } }
  /** Представляет базовую читаемую поверхность матрицы. */
  class DOMMatrixReadOnly { /** Возвращает отдельный массив значений. */ toFloat64Array() { return []; } }
  /** Представляет наследуемую mutable матрицу. */
  class DOMMatrix extends DOMMatrixReadOnly { /** Изменяет матрицу на месте. */ invertSelf() { return this; } }
  const original = CSSNumericValue.prototype.add;
  Object.assign(r.win, {CSSNumericValue, DOMMatrix, DOMMatrixReadOnly});
  const journal = createCssomJournal({document: r.doc, window: r.win});
  try {
    assert.equal(CSSNumericValue.prototype.add, original);
    const before = journal.revision(); new CSSNumericValue().add(); assert.equal(journal.revision(), before);
    new DOMMatrix().invertSelf(); assert.equal(journal.revision(), before + 1);
    DOMMatrixReadOnly.prototype.toFloat64Array = /** Представляет стороннюю замену базового API. */ function () { return [1]; };
    assert.ok(journal.unsupported().some(/** Находит нарушение поверхности базового прототипа. */ entry => entry.property === 'surface:DOMMatrixReadOnly'));
  } finally { journal.dispose(); }
});

test('locked typed setter and late optional prototype fail closed', /** Проверяет неперехватываемый setter и появление optional API после установки. */ () => {
  const r = realm();
  /** Представляет native-подобное mutable ключевое слово. */
  class CSSKeywordValue { /** Записывает штатное значение. */ set value(value) { this.saved = value; } }
  const descriptor = Object.getOwnPropertyDescriptor(CSSKeywordValue.prototype, 'value');
  Object.defineProperty(CSSKeywordValue.prototype, 'value', {...descriptor, configurable: false});
  r.win.CSSKeywordValue = CSSKeywordValue;
  const journal = createCssomJournal({document: r.doc, window: r.win});
  try {
    /** Представляет API, отсутствовавший во время initial census. */
    class DOMMatrix {}
    r.win.DOMMatrix = DOMMatrix;
    const properties = journal.unsupported().map(/** Читает причины неполного покрытия. */ entry => entry.property);
    assert.ok(properties.includes('locked:CSSKeywordValue.value')); assert.ok(properties.includes('new-prototype:DOMMatrix'));
    assert.equal(journal.complete, false);
    assert.deepEqual(Object.getOwnPropertyDescriptor(CSSKeywordValue.prototype, 'value'), {...descriptor, configurable: false});
  } finally { journal.dispose(); }
});
