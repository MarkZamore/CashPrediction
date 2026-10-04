/**
 * @file Runtime-проба S5 для MAIN: вызывается на реальных виджетах запущенного web-клиента.
 * Не запускается автоматически. Нужны уже подключённый observer, тот же registry,
 * живые image/card и newRequest с новым captureId и дедлайном performance.now.
 * bytes/source - сохранённые байты настоящего исходного ответа, не повторный fetch.
 * Это проверки наблюдений и барьера, а не доказательство равенства CDP PNG эталону.
 */

/** Проверяет runtime-условие без пользовательского диалога. */
function check(value, message) { if (!value) throw new Error(message); }

/** Восстанавливает исходный атрибут, включая его первоначальное отсутствие. */
function restore(node, name, value) { if (value === null) node.removeAttribute(name); else node.setAttribute(name, value); }

/** Выполняет независимые проверки коллектора на фактически установленных DOM-источниках. */
export async function runPaintObservationProbe({observer, registry, image, card, bytes, source, newRequest, cssomABA}) {
  check(image.isConnected && card.isConnected && image.closest('#toolbar'), 'Real toolbar/card widgets required');
  const savedStyle = card.getAttribute('style'), savedImageStyle = image.getAttribute('style');
  const results = []; let pending = null;
  /** Возвращает одну подготовленную попытку с проверенным captureId. */
  async function prepare() {
    const request = newRequest(); const result = await observer.prepareCapture(request);
    check(result.ok && result.value.captureId === request.captureId && result.value.observation.identity.captureId === request.captureId, 'Capture identity');
    pending = result.value; return result.value;
  }
  /** Отменяет внешнюю часть пробы, не объявляя синтетическое ожидание снимком CDP. */
  function abort(prepared) { observer.abortCapture({handle: prepared.handle, captureId: prepared.captureId}); if (pending === prepared) pending = null; }
  /** Находит отдельное img-наблюдение по фактическому источнику и owner без дедупликации. */
  function images(prepared) { return prepared.observation.icons.filter(/** Оставляет только источники живых img. */ icon => icon.paintSource === 'image-node'); }
  try {
    await registry.installImage(image, bytes, source);
    let prepared = await prepare();
    const retained = registry.lookup(image, 'image-node', image.currentSrc);
    check(retained && prepared.observation.assets.some(/** Находит точные байты установленного источника. */ asset => asset.sha256 === retained.sha256 && asset.sourceObjectIdentity === retained.sourceObjectIdentity), 'Installed source provenance');
    abort(prepared); results.push('retained-installed-source');

    const duplicate = image.cloneNode(true); image.after(duplicate);
    try {
      prepared = await prepare();
      check(images(prepared).length >= 2 && prepared.observation.unsupported.some(/** Проверяет отказ от правильного marker без объектной привязки. */ issue => issue.property === 'installed-source'), 'Cloned marker must not attest copied source');
      abort(prepared); results.push('replacement-and-duplicate-not-deduplicated');
    } finally { duplicate.remove(); }

    card.style.borderTopLeftRadius = '10% 20%'; card.style.borderTopRightRadius = '3px 4px';
    card.style.borderBottomLeftRadius = '5px 6px'; card.style.borderBottomRightRadius = '7px 8px';
    prepared = await prepare();
    const observed = prepared.observation.cards.find(/** Находит наблюдение настоящей карточки, не ожидаемый token. */ value => value.id === card.dataset.cpId);
    check(observed.borders[0].radii[0].percentageX && observed.borders[0].radii[0].percentageY
      && observed.borders[0].radii[2].rawRx === 5 && observed.borders[0].radii[3].rawRx === 7, 'CSS radius pair/order');
    check(observed.bounds.x === card.getBoundingClientRect().x, 'Actual fractional card origin');
    abort(prepared); results.push('actual-radius-pairs');
    restore(card, 'style', savedStyle);

    const parent = image.parentElement, parentStyle = parent.getAttribute('style');
    try {
      parent.style.opacity = '0.5';
      prepared = await prepare();
      check(images(prepared).some(/** Проверяет прочитанный множитель предка отдельно от альфа PNG. */ value => value.opacityFactors.some(/** Находит наблюдённую CSS-прозрачность предка. */ factor => factor.value === 0.5) && value.effectiveOpacity <= 0.5), 'Ancestor alpha');
      abort(prepared); results.push('ancestor-opacity');
    } finally { restore(parent, 'style', parentStyle); }

    image.style.filter = 'blur(1px)';
    prepared = await prepare();
    check(prepared.observation.unsupported.some(/** Проверяет явный отказ от непредставимого фильтра. */ issue => issue.property === 'filter'), 'Filter must be Unsupported');
    abort(prepared); results.push('filter-unsupported');
    restore(image, 'style', savedImageStyle);

    prepared = await prepare();
    const changed = card.getAttribute('style'); card.style.opacity = '0.9'; restore(card, 'style', changed);
    let finished = await observer.finishCapture({handle: prepared.handle, captureId: prepared.captureId});
    pending = null;
    check(!finished.value.stable && finished.value.synchronization.changes.some(/** Проверяет зарегистрированный ABA DOM даже при равных концах. */ reason => reason.startsWith('dom-mutation')), 'DOM ABA must reject bracket');
    results.push('dom-aba');

    if (cssomABA) {
      prepared = await prepare(); cssomABA();
      finished = await observer.finishCapture({handle: prepared.handle, captureId: prepared.captureId});
      pending = null;
      check(!finished.value.stable && finished.value.synchronization.changes.includes('cssom'), 'CSSOM ABA must reject bracket');
      results.push('cssom-aba');
    }

    prepared = await prepare(); abort(prepared);
    let rejected = false;
    try { await observer.finishCapture({handle: prepared.handle, captureId: prepared.captureId}); } catch { rejected = true; }
    check(rejected, 'Consumed handle must reject finish'); results.push('abort-consumes-handle');
    return {ok: true, checks: results};
  } finally {
    if (pending) { try { abort(pending); } catch { /* Ошибка проверки уже важнее повторной отмены потреблённого handle. */ } }
    restore(card, 'style', savedStyle); restore(image, 'style', savedImageStyle);
  }
}
