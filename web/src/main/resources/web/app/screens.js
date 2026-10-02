/** @file Экраны остановки и потери связи с общими текстами ядра. */
import {element, button} from './dom.js';

/** Показывает экран, не создавая локальных пользовательских сообщений. */
export function showScreen(app, kind, title, text, retry = false) {
  const root = document.getElementById('screens');
  root.hidden = false; root.dataset.kind = kind; root.replaceChildren(element('h2', 'screen-title', title), element('div', 'screen-text', text));
  document.getElementById('main').inert = true;
  if (retry) {
    const control = button('offline.retry', app.texts['offline.retry'], '');
    control.addEventListener('click', () => app.resync()); root.append(control);
  }
  // JavaFX: Alert → Swing: SwingAlert → Web: dialog полноэкранного сообщения.
  if (!root.open) root.showModal();
  root.oncancel = event => event.preventDefault();
}

/** Снимает экран после нового bootstrap. */
export function hideScreen() {
  const root = document.getElementById('screens');
  if (root.open) root.close(); root.hidden = true;
  document.getElementById('main').inert = false;
}
