/** @file Универсальные формы: закрытый набор виджетов и эхо ревизии поля. */
import {element, identify, button, color, bounds, restoreBounds, centerDialogContent, debounce, frame, visible} from './dom.js';

/** Добавляет визуальный разделитель к непустой подписи формы (§6.0), не меняя модель поля. */
export function fieldCaption(text) { return !text || text.endsWith(':') ? text : text + ':'; }

/** Возвращает живые переключатели одного name во всех физических фрагментах окна. */
export function radioInputs(node) {
  const name = node.querySelector('input[type=radio]')?.name;
  const root = node.closest('dialog, .quick-edit') || node;
  return [...root.querySelectorAll('input[type=radio]')].filter(input => input.name === name);
}

/** Читает текст настоящего виджета, включая флажки и радиогруппы. */
export function fieldValue(node) {
  if (node.dataset.kind === 'RADIO') return radioInputs(node).find(input => input.checked)?.value || '';
  if (node.type === 'checkbox') return String(node.checked);
  return 'value' in node ? node.value : node.textContent;
}

/** Применяет текст к настоящему виджету без форматирования на клиенте. */
export function setFieldValue(node, value) {
  if (node.dataset.kind === 'RADIO') radioInputs(node).forEach(input => { input.checked = input.value === value; });
  else if (node.type === 'checkbox') node.checked = value === 'true';
  else if ('value' in node) node.value = value;
  else node.textContent = value;
}

/** Диалог одной FormSession; жизненный цикл подтверждается после showModal. */
export class FormWindow {
  /** Создаёт каркас в точном порядке спецификации. */
  constructor(app, window) {
    this.app = app; this.id = window.id; this.spec = window.spec; this.ownerId = window.ownerId;
    this.fields = new Map(); this.sent = new Map(); this.clientRev = 0; this.revision = -1; this.page = -1;
    // JavaFX: Dialog → Swing: SwingDialog → Web: dialog.
    // JavaFX: TextInputDialog → Swing: SwingTextInputDialog → Web: dialog TEXT_INPUT.
    // JavaFX: ChoiceDialog → Swing: SwingChoiceDialog → Web: dialog CHOICE.
    // JavaFX: FileChooser → Swing: JFileChooser → Web: dialog FILE_BROWSER.
    // JavaFX: DirectoryChooser → Swing: JFileChooser → Web: dialog FILE_BROWSER.
    this.popup = this.spec.presentation === 'POPUP';
    // JavaFX: Popup → Swing: PopupFactory → Web: div.quick-edit.
    this.node = identify(element(this.popup ? 'div' : 'dialog'), this.id, this.spec.windowType);
    if (!this.spec.modal && !this.popup) this.node.classList.add('modeless');
    if (this.popup) { this.node.classList.add('popup', 'quick-edit'); this.node.setAttribute('role', 'dialog'); }
    this.node.style.width = `${this.spec.width}px`;
    this.node.dataset.ownerId = this.ownerId; this.node.dataset.purpose = this.spec.purpose || '';
    const title = element('div', 'window-title'); title.append(element('span', 'window-title-text', this.spec.windowTitle));
    const close = button(this.id + '.close', '\u2715', '');
    close.addEventListener('click', () => app.send({type: 'formClose', windowId: this.id})); title.append(close);
    this.header = element('div', 'window-header'); this.header.append(element('span', 'window-glyph', this.spec.glyph), element('span', 'window-header-text'));
    // JavaFX: DialogPane → Swing: SwingDialogPane → Web: div.form-body.
    this.body = element('div', 'form-body'); this.grid = element('div', 'form-grid');
    this.problem = element('div', 'problem'); this.details = element('div', 'details');
    this.hints = element('div', 'popup-hints'); this.hints.hidden = true;
    this.content = element('div', 'form-content'); this.content.append(this.grid);
    this.body.append(this.content, this.problem);
    if (this.popup) this.body.append(this.hints);
    this.body.append(this.details);
    this.buttons = element('div', 'window-buttons');
    this.node.append(title, this.header, this.body, this.buttons); document.body.append(this.node);
    if (this.popup) { title.hidden = true; this.buttons.hidden = true; }
    this.node.addEventListener('cancel', event => { event.preventDefault(); app.send({type: 'formClose', windowId: this.id}); });
    this.node.addEventListener('keydown', event => this.key(event));
    this.node.addEventListener('pointerdown', () => app.menus.close());
    this.update(window.view);
    const placement = window.placement?.bounds;
    this.restoredBounds = Boolean(placement);
    restoreBounds(this.node, placement);
    if (this.popup) {
      const anchor = window.placement?.anchor;
      const row = [...document.querySelectorAll('.table-row')].find(node => node.dataset.cpId === anchor?.rowId);
      const cell = row && [...row.children].find(node => node.dataset.cpId === anchor.columnId);
      const rect = cell && bounds(cell);
      this.node.style.left = `${Math.max(0, Math.min(rect?.x ?? innerWidth / 2 - 150, innerWidth - this.node.offsetWidth))}px`;
      this.node.style.top = `${Math.max(0, Math.min(rect ? rect.y + rect.height : innerHeight / 2 - 50, innerHeight - this.node.offsetHeight))}px`;
    }
    const changed = debounce(() => { if (this.showing()) app.send({type: 'formBounds', windowId: this.id, bounds: bounds(this.node)}); }, 120);
    this.boundsChanged = changed; app.debouncers.add(changed);
    this.observer = new ResizeObserver(() => { this.center(); changed(); }); this.observer.observe(this.node);
  }

  /** Открывает окно и подтверждает реальную видимость в следующем кадре. */
  async show() {
    if (!this.popup) { if (this.spec.modal) this.node.showModal(); else this.node.show(); }
    await frame();
    if (!this.showing()) return;
    this.center();
    this.app.send({type: 'formShown', windowId: this.id});
    this.app.send({type: 'formBounds', windowId: this.id, bounds: bounds(this.node)});
    const first = this.node.querySelector('[data-focus-first="true"]') || this.node.querySelector('input:enabled, select:enabled, textarea:enabled');
    first?.focus(); if (first?.select) first.select();
  }

  /** Проверяет живое свойство dialog.open или реальную видимость div всплывающего окна. */
  showing() { return this.popup ? visible(this.node) : this.node.isConnected && this.node.open; }

  /** Центрирует только новое обычное окно; восстановленные RAW-границы и popup не меняет. */
  center() { if (!this.popup && !this.restoredBounds && this.showing()) centerDialogContent(this.node, this.ownerId); }

  /** Удаляет живое окно и его слушатели измерения. */
  close() {
    this.observer.disconnect(); this.boundsChanged.cancel(); this.app.debouncers.delete(this.boundsChanged);
    for (const field of this.fields.values()) for (const peer of field.peers || [field]) { peer.commit.cancel(); this.app.debouncers.delete(peer.commit); }
    if (!this.popup) this.node.close(); this.node.remove();
  }

  /** Создаёт виджет каждого разрешённого FieldKind. */
  field(spec, inline = false) {
    const holder = identify(element('div', 'field-wrap'), spec.id + '.wrap');
    if (spec.wide) holder.classList.add('wide');
    const label = element('label', 'field-label', spec.kind === 'CHECK' ? '' : fieldCaption(spec.label));
    let control;
    if (spec.kind === 'CHOICE' || spec.kind === 'LIST') {
      control = element('select'); if (spec.kind === 'LIST') control.size = spec.textRows || 6;
    } else if (spec.kind === 'MULTILINE') { control = element('textarea'); control.rows = spec.textRows || 3; }
    else if (spec.kind === 'RADIO') { control = element('fieldset', `radio-group ${spec.orientation === 'VERTICAL' ? 'vertical' : ''}`); }
    else if (spec.kind === 'PREVIEW' || spec.kind === 'RESULT_LINES') control = element('div', spec.kind === 'PREVIEW' ? 'preview' : 'results');
    else if (spec.kind === 'BUTTON') control = button(spec.id, spec.label, spec.tooltip);
    else {
      control = element('input');
      control.type = spec.kind === 'CHECK' ? 'checkbox' : spec.kind === 'SPINNER' ? 'number' : 'text';
      if (spec.kind === 'SPINNER') { control.min = spec.min; control.max = spec.max; control.step = spec.step; }
      if (spec.kind === 'MONEY') control.classList.add('money');
      if (spec.kind === 'EDITABLE_CHOICE') {
        const list = element('datalist'); list.id = `${this.id}-${spec.id}-choices`; control.setAttribute('list', list.id); holder.append(list);
      }
    }
    identify(control, spec.id, spec.kind); control.id = `${this.id}-${spec.id}-${this.fields.size}`; label.htmlFor = control.id;
    control.placeholder = spec.prompt || ''; control.dataset.tooltip = spec.tooltip || '';
    control.dataset.focusFirst = String(spec.focusFirst); control.dataset.suffix = spec.suffix || '';
    if (spec.widthPx > 0) {
      control.style.width = `${spec.widthPx}px`;
      control.style.flex = 'none';
    } else if (spec.columns) control.style.width = `${spec.columns}ch`;
    const commit = debounce(() => this.changed(spec.id, control, false), 120);
    this.app.debouncers.add(commit);
    control.addEventListener('input', () => commit());
    control.addEventListener('change', () => { if (['RADIO', 'CHECK', 'CHOICE', 'LIST'].includes(spec.kind)) commit.flush(); });
    control.addEventListener('blur', () => { commit.cancel(); this.changed(spec.id, control, true); });
    if (spec.kind === 'BUTTON') control.addEventListener('click', () => this.app.send({type: 'formButton', windowId: this.id, buttonId: spec.id}));
    if (spec.kind === 'LIST') control.addEventListener('dblclick', () => this.app.send({type: 'formActivate', windowId: this.id, fieldId: spec.id, index: control.selectedIndex}));
    const entry = {spec, holder, label, control, commit};
    const previous = this.fields.get(spec.id);
    if (previous) {
      if (spec.kind !== 'RADIO' || previous.spec.kind !== 'RADIO') throw new Error(`Duplicate field: ${spec.id}`);
      previous.peers ||= [previous]; previous.peers.push(entry); entry.peers = previous.peers;
    } else this.fields.set(spec.id, entry);
    if (spec.kind === 'CHECK') { const checkLabel = element('label', '', spec.label); checkLabel.htmlFor = control.id; holder.append(control, checkLabel); entry.checkLabel = checkLabel; }
    else holder.append(control);
    if (spec.kind === 'DATE') {
      const calendar = button(spec.id + '.calendar', '\u25a6', this.app.texts['calendar.button.tip']); calendar.classList.add('calendar-button');
      calendar.addEventListener('click', () => {
        // Перестановка компонентов даты нужна только адаптеру календаря, проверки остаются в ядре.
        const displayed = control.value.match(/^(\d{2})\.(\d{2})\.(\d{4})$/);
        const selected = displayed ? `${displayed[3]}-${displayed[2]}-${displayed[1]}` : null;
        const anchor = selected || this.app.screen.summary.cards.find(card => card.id === 'now')?.date;
        if (!anchor) throw new Error('Calendar needs core anchor date');
        this.app.popups.calendar(calendar, selected, date => {
          commit.cancel(); const clientRev = ++this.clientRev;
          this.sent.set(clientRev, new Map([...this.fields].map(([id, field]) => [id, fieldValue(field.control)])));
          this.app.send({type: 'formField', windowId: this.id, fieldId: spec.id, raw: date, committed: true, clientRev});
        }, anchor.slice(0, 7));
      });
      holder.append(calendar);
    }
    if (spec.suffix) holder.append(element('span', 'suffix', spec.suffix));
    if (!inline && !spec.wide && spec.kind !== 'CHECK') this.grid.append(label);
    if (!inline) this.grid.append(holder);
    return holder;
  }

  /** Отправляет сырую правку и хранит снимок текстов для защиты от позднего эха. */
  changed(fieldId, control, committed) {
    if (!this.node.isConnected || control.disabled || control.readOnly) return;
    const clientRev = ++this.clientRev;
    const snapshot = new Map([...this.fields].map(([id, field]) => [id, fieldValue(field.control)]));
    this.sent.set(clientRev, snapshot);
    if (this.sent.size > 200) this.sent.delete(this.sent.keys().next().value);
    this.app.send({type: 'formField', windowId: this.id, fieldId, raw: fieldValue(control), committed, clientRev});
  }

  /** Строит строки страницы в заданном моделью порядке. */
  rows(rows) {
    for (const row of rows) {
      if (row.kind === 'Field') this.field(row.field);
      else if (row.kind === 'Inline') {
        this.grid.append(element('label', 'field-label', fieldCaption(row.label))); const group = element('div', 'field-wrap');
        for (const field of row.fields) group.append(this.field(field, true)); this.grid.append(group);
      } else if (row.kind === 'Section') this.grid.append(element('div', 'section', row.caption));
      else if (row.kind === 'Hint') {
        const hint = identify(element('div', 'hint', row.text), row.id);
        if (this.popup) { this.hints.append(hint); this.hints.hidden = false; }
        else this.grid.append(hint);
      }
      else if (row.kind === 'Results') {
        const results = identify(element('div', 'results'), row.id);
        results.style.setProperty('--cp-result-min-lines', String(row.minLines ?? 0));
        this.grid.append(results);
      }
      else if (row.kind === 'SideColumn') {
        const side = element('div', 'side-column'); side.append(element('strong', 'side-caption', row.caption), this.field(row.preview, true));
        for (const spec of row.buttons) side.append(this.formButton(spec)); this.content.append(element('div', 'form-divider'), side);
        if (row.contextMenu) side.addEventListener('contextmenu', async event => {
          event.preventDefault();
          const item = event.target.closest('[data-preview-index]');
          const index = Number(item?.dataset.previewIndex ?? -1);
          if (item?.getAttribute('aria-disabled') === 'false') { item.click(); await this.app.transport.tail; }
          await this.app.menus.context({kind: 'preview', windowId: this.id, index}, event.clientX, event.clientY);
        });
      }
    }
  }

  /** Создаёт кнопку формы с заданным порядком и ролью. */
  formButton(spec) {
    // JavaFX: ButtonType → Swing: SwingButtonType → Web: button в панели формы.
    const node = button(spec.id, spec.text, spec.tooltip); node.dataset.role = spec.role || 'OTHER';
    node.addEventListener('click', async () => { await this.flush(); this.app.send({type: 'formButton', windowId: this.id, buttonId: spec.id}); });
    return node;
  }

  /** Применяет options, доступность и значение одной модели поля. */
  applyField(entry, view, echo) {
    const {spec, control, holder, label} = entry;
    holder.hidden = !view.visible; label.hidden = !view.visible;
    control.disabled = !view.enabled; control.readOnly = view.readOnly;
    control.setAttribute('aria-readonly', String(view.readOnly));
    control.setAttribute('aria-disabled', String(!view.enabled));
    if (['PREVIEW', 'RESULT_LINES'].includes(spec.kind)) control.inert = !view.enabled;
    control.dataset.tooltip = view.tooltip ?? spec.tooltip ?? '';
    if (view.label != null) { label.textContent = fieldCaption(view.label); if (entry.checkLabel) entry.checkLabel.textContent = view.label; }
    if (view.min != null) control.min = view.min; if (view.max != null) control.max = view.max;
    const options = view.options ?? spec.options ?? [];
    if (['CHOICE', 'LIST', 'EDITABLE_CHOICE', 'RADIO'].includes(spec.kind)) {
      const encoded = JSON.stringify(options);
      if (control.dataset.options !== encoded) {
        const oldValue = fieldValue(control); control.dataset.options = encoded;
        if (spec.kind === 'RADIO') {
          control.replaceChildren();
          for (const option of options) {
            const radio = element('input'); radio.type = 'radio'; radio.name = `${this.id}-${spec.id}`; radio.value = option.value; radio.disabled = !view.enabled;
            const caption = element('label', '', option.text); caption.prepend(radio); control.append(caption);
          }
        } else {
          const target = spec.kind === 'EDITABLE_CHOICE' ? holder.querySelector('datalist') : control;
          target.replaceChildren(...options.map(option => { const node = element('option', '', option.text); node.value = option.value; return node; }));
        }
        setFieldValue(control, oldValue);
      }
    }
    if (spec.kind === 'RADIO') control.querySelectorAll('input').forEach(node => { node.disabled = !view.enabled || view.readOnly; });
    const sent = echo?.tab === this.app.transport.tab ? this.sent.get(echo.clientRev) : null;
    const safe = !sent || sent.get(spec.id) === fieldValue(control);
    if (view.value != null && safe) setFieldValue(control, view.value);
    if (spec.kind === 'PREVIEW') {
      control.replaceChildren(...this.view.preview.map((item, index) => {
        const node = element('div', 'preview-item', item.text); node.dataset.previewIndex = index; node.tabIndex = item.selectable ? 0 : -1;
        node.setAttribute('aria-disabled', String(!item.selectable || !view.enabled));
        node.setAttribute('aria-selected', String(index === this.previewIndex)); node.style.color = color(item.color);
        node.addEventListener('click', () => { if (!item.selectable || !view.enabled) return; this.previewIndex = index; control.querySelectorAll('.preview-item').forEach(n => n.setAttribute('aria-selected', String(n === node))); this.app.send({type: 'formPreview', windowId: this.id, index, activated: false}); });
        node.addEventListener('dblclick', () => { if (item.selectable && view.enabled) this.app.send({type: 'formPreview', windowId: this.id, index, activated: true}); }); return node;
      }));
    }
  }

  /** Обновляет форму, не уничтожая виджеты текущей страницы. */
  update(view, echo) {
    if (view.revision < this.revision) return;
    // Новый набор дат сбрасывает выбор, как замена элементов ListView/JList.
    if (this.page !== view.page || JSON.stringify(this.view?.preview || []) !== JSON.stringify(view.preview)) this.previewIndex = -1;
    this.revision = view.revision; this.view = view;
    this.node.dataset.page = view.page;
    if (this.page !== view.page) {
      for (const entry of this.fields.values()) for (const peer of entry.peers || [entry]) { peer.commit.cancel(); this.app.debouncers.delete(peer.commit); }
      this.fields.clear(); this.grid.replaceChildren(); this.hints.replaceChildren(); this.hints.hidden = true;
      this.content.querySelectorAll(':scope > .side-column, :scope > .form-divider').forEach(node => node.remove()); this.page = view.page;
      this.rows(this.spec.pages[view.page]?.rows || []);
      this.buttons.replaceChildren();
      const left = this.spec.buttons.filter(b => b.role === 'LEFT'); const right = this.spec.buttons.filter(b => b.role !== 'LEFT');
      this.buttons.append(...left.map(b => this.formButton(b)), element('div', 'button-spacer'), ...right.map(b => this.formButton(b)));
    }
    this.header.querySelector('.window-header-text').textContent = view.header;
    for (const [id, entry] of this.fields) {
      const state = view.fields[id]; if (state) for (const peer of entry.peers || [entry]) this.applyField(peer, state, echo);
    }
    for (const node of this.node.querySelectorAll('button[data-role]')) {
      const state = view.buttons[node.dataset.cpId]; if (!state) continue;
      node.hidden = !state.visible; node.disabled = !state.enabled; if (state.text != null) node.textContent = state.text;
      if (state.tooltip != null) node.dataset.tooltip = state.tooltip;
      node.classList.toggle('default-button', node.dataset.cpId === this.spec.defaultButtonId);
    }
    const severity = view.problem?.severity;
    const glyph = severity === 'WARNING' ? '\u26a0 ' : severity === 'ERROR' ? '\u2716 ' : '';
    this.problem.textContent = glyph ? glyph + view.problem.text : ''; this.problem.style.color = color(severity === 'WARNING' ? 'WARN' : severity === 'ERROR' ? 'EXPENSE' : 'TEXT_MUTED');
    this.problem.hidden = this.popup && !glyph;
    for (const node of this.grid.querySelectorAll('.results')) node.replaceChildren(...view.results.map(line => { const row = element('div', 'result-line', line.text); row.style.color = color(line.color); return row; }));
    this.renderDetails(view.details, view.detailsExpanded);
  }

  /** Обновляет ссылку и текст подробностей. */
  renderDetails(text, expanded) {
    this.details.hidden = !text;
    if (this.details.dataset.text === text) return;
    this.details.dataset.text = text; this.details.replaceChildren();
    if (!text) return;
    const link = button(this.id + '.details', this.app.texts[expanded ? 'details.hide' : 'details.show'], ''); link.classList.add('details-link');
    const content = element('textarea', 'details-text'); content.readOnly = true; content.value = text; content.hidden = !expanded;
    link.addEventListener('click', () => { content.hidden = !content.hidden; link.textContent = this.app.texts[content.hidden ? 'details.show' : 'details.hide']; });
    this.details.append(link, content);
  }

  /** Перед действием завершает отложенные правки полей. */
  async flush() {
    for (const entry of this.fields.values()) for (const peer of entry.peers || [entry]) if (peer.commit.pending()) peer.commit.flush();
    await this.app.transport.tail;
  }

  /** Enter в однострочном поле проходит путь submit; Esc проходит путь close. */
  async key(event) {
    if (event.code === 'Escape') { event.preventDefault(); event.stopPropagation(); this.app.send({type: 'formClose', windowId: this.id}); return; }
    if (event.code !== 'Enter' || event.target.tagName === 'TEXTAREA' || event.target.tagName === 'BUTTON') return;
    event.preventDefault(); event.stopPropagation();
    const fieldId = event.target.closest('[data-cp-id][data-kind]')?.dataset.cpId;
    const entry = this.fields.get(fieldId);
    if (entry?.spec.kind === 'LIST') { await this.flush(); this.app.send({type: 'formActivate', windowId: this.id, fieldId, index: entry.control.selectedIndex}); }
    else if (entry && ['TEXT', 'MONEY', 'DATE', 'MONTH_DAY', 'SPINNER', 'EDITABLE_CHOICE'].includes(entry.spec.kind)) {
      entry.commit.cancel(); this.changed(fieldId, entry.control, true); await this.app.transport.tail;
      this.app.send({type: 'formSubmit', windowId: this.id, fieldId});
    } else this.node.querySelector('.default-button:enabled')?.click();
  }
}
