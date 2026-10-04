/**
 * @file Runtime-проба реальных браузерных CSSOM-путей для MAIN, без автоматического запуска.
 * Вызывается при отсутствии другого журнала в текущем realm, вне capture-bracket.
 * Создаёт только собственные временные узлы/листы/анимацию и удаляет их в finally.
 * Не проверяет пиксели, настоящую загрузку шрифта или физическую смену viewport.
 */
import {createCssomJournal} from '/app/paint-cssom-journal.js';

/** Проверяет условие runtime-пробы, не создавая пользовательских сообщений. */
function check(value, message) { if (!value) throw new Error(message); }

/** Проверяет реальные CSSOM ABA, сохранение сторонней реализации и границы полноты. */
export async function runCssomJournalProbe() {
  const style = document.createElement('style'), fixture = document.createElement('div'), checks = [];
  style.textContent = '.s5-cssom-journal-probe { padding: 0; }';
  fixture.className = 's5-cssom-journal-probe'; document.head.append(style); document.body.append(fixture);
  const owner = CSSStyleDeclaration.prototype, original = Object.getOwnPropertyDescriptor(owner, 'setProperty');
  let journal = null, animation = null, override = null;
  try {
    journal = createCssomJournal(); check(journal.complete === false, 'Incomplete native coverage must fail closed');
    check(journal.unsupported().some(/** Находит непокрытый обход через ранее сохранённую native-ссылку. */ issue => issue.property === 'escaped-native-references'), 'Missing bypass diagnostic');
    let before = journal.revision();
    fixture.style.setProperty('--s5-probe', '1'); fixture.style.removeProperty('--s5-probe');
    check(journal.revision() >= before + 2, 'Property ABA'); checks.push('native-property-aba');

    before = journal.revision(); const sheet = style.sheet;
    const index = sheet.insertRule('.s5-cssom-journal-probe { margin: 1px; }', sheet.cssRules.length); sheet.deleteRule(index);
    check(journal.revision() >= before + 2, 'Rule ABA'); checks.push('native-rule-aba');

    before = journal.revision(); const saved = sheet.cssRules[0].style.cssText;
    sheet.cssRules[0].style.cssText = 'padding: 1px'; sheet.cssRules[0].style.cssText = saved;
    check(journal.revision() >= before + 2, 'Rule cssText setter ABA'); checks.push('native-rule-setter-aba');

    const adopted = document.adoptedStyleSheets;
    check(document.adoptedStyleSheets === adopted, 'Native adopted getter identity changed');
    check(journal.unsupported().some(/** Находит честную границу мутации adopted-массива на месте. */ issue => issue.property === 'adopted-array-aba'), 'Adoption bypass diagnostic');
    checks.push('adopted-array-not-proxied');

    const constructed = new CSSStyleSheet(); const result = constructed.replace('.s5-cssom-journal-probe { margin: 0; }');
    check(result instanceof Promise, 'Native replace Promise'); await result;
    check(journal.unsupported().some(/** Находит непокрытую асинхронную фазу без навязанного then/catch. */ issue => issue.property === 'async:CSSStyleSheet.replace'), 'Asynchronous replacement diagnostic');
    checks.push('async-replace-unsupported');

    animation = fixture.animate([{opacity: 1}, {opacity: 0.8}], {duration: 10000});
    check(journal.unsupported().some(/** Находит реально созданную активную timeline-анимацию. */ issue => issue.property === 'active-animation'), 'Active animation census');
    animation.cancel(); animation = null; checks.push('active-animation-fails-closed');

    override = /** Представляет временную стороннюю обёртку с сохранением исходной native-семантики. */ function (...args) {
      return Reflect.apply(original.value, this, args);
    };
    Object.defineProperty(owner, 'setProperty', {...original, value: override});
    check(journal.unsupported().some(/** Находит потерю собственности на ранее установленную обёртку. */ issue => issue.property === 'override:CSSStyleDeclaration.setProperty'), 'Third-party override diagnostic');
    journal.dispose(); check(owner.setProperty === override, 'Third-party override was overwritten during dispose');
    checks.push('override-preserved');
    return {ok: true, complete: false, checks, unsupported: journal.unsupported()};
  } finally {
    animation?.cancel(); journal?.dispose();
    if (override && Object.getOwnPropertyDescriptor(owner, 'setProperty')?.value === override) Object.defineProperty(owner, 'setProperty', original);
    fixture.remove(); style.remove();
  }
}
