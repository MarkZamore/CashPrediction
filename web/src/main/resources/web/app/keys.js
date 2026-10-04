/** @file Один диспетчер физических клавиш перед штатной обработкой браузера. */

/** Переводит KeyboardEvent.code в имя KeyChord ядра. */
export function physical(code) {
  if (code.startsWith('Key')) return code.slice(3);
  if (code.startsWith('Digit')) return code.toUpperCase();
  if (code === 'ContextMenu') return 'CONTEXT_MENU';
  if (code === 'AltLeft' || code === 'AltRight') return 'ALT';
  return code.toUpperCase();
}

/** Определяет область настоящего элемента в фокусе. */
export function scope(node, app) {
  const dialog = node.closest?.('dialog[open], .quick-edit');
  if (dialog) return {scope: dialog.classList.contains('quick-edit') ? 'POPUP' : 'TEXT_INPUT', focusId: dialog.dataset.cpId};
  if (node === app.filter) return {scope: 'FILTER', focusId: ''};
  const card = node.closest?.('.card'); if (card) return {scope: 'CARD', focusId: card.dataset.cpId};
  if (node.closest?.('#table')) return {scope: 'TABLE', focusId: ''};
  if (node.matches?.('input, textarea, select')) return {scope: 'TEXT_INPUT', focusId: ''};
  return {scope: 'MAIN', focusId: ''};
}

/** Регистрирует физические сочетания, объявленные bootstrap, без двойного срабатывания. */
export function installKeys(app) {
  document.addEventListener('keydown', /** Перехватывает поддержанное физическое сочетание и передаёт его ядру с областью фокуса. */ async event => {
    if (event.defaultPrevented || event.isComposing || event.target.closest('.calendar')) return;
    const focus = scope(event.target, app);
    if (event.target.closest('dialog[open], .quick-edit') && ['Enter', 'Escape'].includes(event.code)) return;
    const chord = {ctrl: event.ctrlKey, shift: event.shiftKey, alt: event.altKey, key: physical(event.code)};
    const binding = app.hotkeys.find(/** Сопоставляет клавишу, модификаторы и область фокуса с объявленной привязкой. */ item => item.chord.key === chord.key && item.chord.ctrl === chord.ctrl && item.chord.alt === chord.alt && item.chord.shift === chord.shift && item.scopes.includes(focus.scope));
    const contextual = (event.code === 'ContextMenu' || event.code === 'F10' && event.shiftKey) && ['TABLE', 'CARD'].includes(focus.scope);
    const filter = focus.scope === 'FILTER' && ['Enter', 'Escape'].includes(event.code);
    if (!binding && !contextual && !filter) return;
    event.preventDefault(); event.stopImmediatePropagation();
    // Глобальный ускоритель работает и из меню; навигацию меню уже обработал Menus.navigate.
    if (event.target.closest('.menu-panel')) app.menus.close();
    if (filter && event.code === 'Enter') { await app.filterCommit.flush(); await app.transport.tail; }
    app.send({type: 'key', chord, ...focus});
  }, true);
}
