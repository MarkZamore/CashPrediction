/** @file Сообщения с точными кнопками AlertSpec и подтверждением реального показа. */
import {element, identify, button, frame, restoreBounds, centerDialogContent} from './dom.js';

/** Возвращает стандартный видимый значок типа сообщения при отсутствии override. */
export function nativeAlertGlyph(kind) {
  return {INFORMATION: '\u2139', WARNING: '\u26a0', ERROR: '\u2716', CONFIRMATION: '?'}[kind] || '';
}

/** Живой диалог сообщения; первое действие пользователя отправляется один раз. */
export class AlertWindow {
  /** Создаёт каркас сообщения с кнопками ядра. */
  constructor(app, id, spec, placement = null) {
    this.app = app; this.id = id; this.answered = false; this.placement = placement;
    // JavaFX: Alert → Swing: SwingAlert → Web: dialog сообщения.
    this.node = identify(element('dialog', 'alert-window'), id);
    this.node.dataset.ownerId = placement?.ownerId || 'main';
    this.node.addEventListener('cancel', event => { event.preventDefault(); const cancel = this.node.querySelector('button[data-role=CANCEL]'); if (cancel) cancel.click(); });
    this.node.addEventListener('keydown', event => {
      if (event.code === 'Enter' && event.target.tagName !== 'BUTTON' && event.target.tagName !== 'TEXTAREA') { event.preventDefault(); this.node.querySelector('.default-button:enabled')?.click(); }
    });
    document.body.append(this.node); this.update(spec);
  }

  /** Открывает сообщение и подтверждает его только после успешного showModal. */
  async show() {
    this.node.showModal();
    await frame();
    this.center();
    if (this.node.isConnected && this.node.open && !this.answered) this.app.send({type: 'alertShown', windowId: this.id});
  }

  /** Обновляет сообщение, не отправляя повторного подтверждения показа. */
  update(spec) {
    this.spec = spec; this.node.dataset.purpose = spec.purpose; this.node.dataset.kind = spec.kind;
    this.node.style.width = `${spec.minWidth}px`; this.node.dataset.minWidth = spec.minWidth;
    const expanded = this.node.querySelector('.details-text') ? !this.node.querySelector('.details-text').hidden : spec.detailsExpanded;
    this.node.replaceChildren();
    this.node.append(element('div', 'window-title-text window-title', spec.windowTitle));
    this.node.dataset.glyphMode = spec.glyph ? 'override' : 'native';
    const header = element('div', 'window-header'); header.append(element('span', 'window-glyph', spec.glyph || nativeAlertGlyph(spec.kind)), element('span', 'window-header-text', spec.header));
    this.node.append(header, element('div', 'alert-content', spec.content));
    if (spec.details) {
      const area = element('div', 'form-body'); const link = button(this.id + '.details', this.app.texts[expanded ? 'details.hide' : 'details.show'], ''); link.classList.add('details-link');
      const text = element('textarea', 'details-text'); text.readOnly = true; text.value = spec.details; text.hidden = !expanded;
      link.addEventListener('click', () => { text.hidden = !text.hidden; link.textContent = this.app.texts[text.hidden ? 'details.show' : 'details.hide']; });
      area.append(link, text); this.node.append(area);
    }
    const buttons = element('div', 'window-buttons'); buttons.append(element('div', 'button-spacer'));
    for (const item of spec.buttons) {
      // JavaFX: ButtonType → Swing: SwingButtonType → Web: button сообщения.
      const node = button(item.id, item.text, item.tooltip, item.enabled);
      node.dataset.role = item.role; node.classList.toggle('default-button', item.id === spec.defaultButtonId);
      node.addEventListener('click', () => {
        if (this.answered) return; this.answered = true;
        buttons.querySelectorAll('button').forEach(control => { control.disabled = true; });
        this.app.send({type: 'alertButton', alertId: this.id, buttonId: item.id});
      }); buttons.append(node);
    }
    this.node.append(buttons);
    restoreBounds(this.node, this.placement?.bounds);
    this.center();
  }

  /** Центрирует новое сообщение по содержимому владельца, сохраняя восстановленное внешнее окно. */
  center() { if (this.node.open && !this.placement?.bounds) centerDialogContent(this.node, this.node.dataset.ownerId); }

  /** Закрывает окно по эффекту сервера. */
  close() { this.node.close(); this.node.remove(); }
}
