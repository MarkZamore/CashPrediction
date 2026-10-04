/** @file Экраны остановки и потери связи с общими текстами ядра. */
import {element, button} from './dom.js';

/** Показывает экран, не создавая локальных пользовательских сообщений. */
export function showScreen(app, kind, title, text, retry = false) {
  const root = document.getElementById('screens');
  root.hidden = false; root.dataset.kind = kind; root.replaceChildren(element('h2', 'screen-title', title), element('div', 'screen-text', text));
  document.getElementById('main').inert = true;
  if (retry) {
    const control = button('offline.retry', app.texts['offline.retry'], '');
    // Ручной повтор пользуется тем же единственным handshake, что и фоновое восстановление.
    control.addEventListener('click', /** Запускает ручной повтор общего восстановления соединения. */ () => { app.transport.recover().catch(/** Поглощает отклонение ручного повтора, оставляя состояние восстановления транспорту. */ () => {}); }); root.append(control);
  }
  // JavaFX: Alert → Swing: SwingAlerts → Web: dialog полноэкранного сообщения.
  if (!root.open) root.showModal();
  root.oncancel = /** Предотвращает закрытие полноэкранного сообщения стандартной отменой диалога. */ event => event.preventDefault();
}

/** Снимает экран после нового bootstrap. */
export function hideScreen() {
  const root = document.getElementById('screens');
  if (root.open) root.close(); root.hidden = true;
  document.getElementById('main').inert = false;
}
