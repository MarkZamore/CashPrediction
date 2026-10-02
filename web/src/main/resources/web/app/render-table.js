/** @file Виртуальная таблица, страницы по 300 строк и высота строки 26 пикселей. */
import {element, identify, color, look, button} from './dom.js';

/** Рисует только видимые строки готовой модели ядра. */
export class Table {
  /** Подключает прокрутку и клавиатурное выделение. */
  constructor(app) {
    this.app = app; this.root = document.getElementById('table');
    this.header = identify(element('div', 'table-header'), 'table.header');
    this.scroll = element('div', 'table-scroll');
    this.canvas = element('div', 'table-canvas'); this.scroll.append(this.canvas);
    this.root.append(this.header, this.scroll);
    this.pages = new Map(); this.loads = new Map(); this.epoch = 0;
    this.scroll.addEventListener('scroll', () => this.paint());
    this.scroll.addEventListener('scroll', () => { this.header.scrollLeft = this.scroll.scrollLeft; });
    this.root.addEventListener('keydown', event => this.navigate(event));
    this.resize = new ResizeObserver(() => this.paint()); this.resize.observe(this.scroll);
  }

  /** Применяет ревизию и заголовки, оставляя вычисления строк серверу. */
  async update(model) {
    const changed = this.model?.revision !== model.revision;
    this.model = model; this.root.dataset.rowCount = model.rowCount;
    this.root.setAttribute('aria-rowcount', model.rowCount);
    this.root.dataset.selectedRowId = model.selectedRowId || '';
    if (changed) { this.pages.clear(); this.loads.clear(); this.epoch++; }
    this.header.replaceChildren(...model.columns.map(column => {
      const node = identify(element('div', 'table-cell', column.title), column.id);
      node.dataset.tooltip = column.headerTooltip; this.columnStyle(node, column); return node;
    }));
    this.canvas.style.height = `${model.rowCount * 26}px`;
    // Две боковые рамки входят в размер полотна, но не в ширины колонок ядра.
    this.canvas.style.minWidth = `${model.columns.reduce((sum, column) => sum + column.widthPx, 0) + 2}px`;
    this.root.querySelector('.placeholder')?.remove();
    if (model.placeholder) {
      const node = element('div', 'placeholder');
      const text = element('div', 'placeholder-text', model.placeholder.text);
      text.style.color = color(model.placeholder.color); node.append(text);
      const actions = element('div', 'placeholder-buttons');
      for (const action of model.placeholder.buttons) {
        const control = button(action.id, action.text, '');
        control.addEventListener('click', () => this.app.command(action.command, {}, 'MAIN'));
        actions.append(control);
      }
      node.append(actions); this.root.append(node);
    }
    await this.paint();
    if (model.scrollToRowId && this.lastReveal !== model.scrollToRowId) {
      this.lastReveal = model.scrollToRowId;
      await this.reveal(model.scrollToRowId, false);
    }
  }

  /** Применяет ширину и выравнивание столбца. */
  columnStyle(node, column) {
    node.style.width = `${column.widthPx}px`;
    node.style.flexGrow = column.grows ? '1' : '0';
    node.style.textAlign = column.align.toLowerCase();
    if (column.bold) node.style.fontWeight = '700';
  }

  /** Загружает страницу один раз для текущей ревизии. */
  async page(index) {
    const from = Math.floor(index / 300) * 300;
    if (this.pages.has(from)) return this.pages.get(from);
    if (this.loads.has(from)) return this.loads.get(from);
    const rev = this.model.revision; const epoch = this.epoch;
    const load = this.app.transport.query({type: 'rows', rev, from, count: Math.min(300, this.model.rowCount - from)}).then(response => {
      if (epoch !== this.epoch) return [];
      if (response.stale) { this.app.resync(); return []; }
      const rows = response.result || [];
      this.pages.set(from, rows);
      if (this.pages.size > 7) this.pages.delete(this.pages.keys().next().value);
      return rows;
    }).finally(() => { if (epoch === this.epoch) this.loads.delete(from); });
    this.loads.set(from, load); return load;
  }

  /** Получает одну готовую строку из её страницы. */
  async row(index) {
    if (index < 0 || index >= this.model.rowCount) return null;
    return (await this.page(index))[index % 300] || null;
  }

  /** Рисует видимую область с небольшим запасом строк. */
  async paint() {
    if (!this.model) return;
    const epoch = this.epoch;
    const first = Math.max(0, Math.floor(this.scroll.scrollTop / 26) - 3);
    const count = Math.min(this.model.rowCount - first, Math.ceil(this.scroll.clientHeight / 26) + 6);
    const rows = await Promise.all(Array.from({length: Math.max(0, count)}, (_, offset) => this.row(first + offset)));
    if (epoch !== this.epoch || Math.max(0, Math.floor(this.scroll.scrollTop / 26) - 3) !== first) return;
    this.canvas.replaceChildren(...rows.flatMap((row, offset) => row ? [this.widget(row, first + offset)] : []));
    // Измеряем после удаления старых строк: они не должны оставлять полосу у пустой таблицы.
    // Headless Chrome рисует overlay-полосу: резервируем её реальный CSS-размер только при переполнении.
    if (this.scroll.clientHeight > 0) this.scroll.style.scrollbarGutter = this.scroll.scrollHeight > this.scroll.clientHeight ? 'stable' : 'auto';
    // Заголовок занимает реальную область строк без вертикальной полосы прокрутки.
    if (this.scroll.clientWidth > 0) this.header.style.width = `${this.scroll.clientWidth}px`;
  }

  /** Создаёт строку и отправляет реальные щелчки, активацию и наведение. */
  widget(row, index) {
    const node = identify(element('div', 'table-row'), row.rowId, row.kind);
    node.role = 'row'; node.dataset.index = index; node.style.top = `${index * 26}px`;
    node.setAttribute('aria-selected', String(row.rowId === this.model.selectedRowId));
    node.style.background = color(row.rowStyle?.background); look(node, row.rowStyle);
    for (let col = 0; col < this.model.columns.length; col++) {
      const column = this.model.columns[col];
      if (col > 0 && col < row.leadingSpan) continue;
      const cell = identify(element('div', 'table-cell', row.cells[col]), column.id); cell.role = 'gridcell';
      this.columnStyle(cell, column);
      const cellStyle = {...row.rowStyle, ...row.cellStyles?.[column.id]};
      look(cell, {...cellStyle, bold: column.bold || cellStyle.bold});
      if (col === 0 && row.leadingSpan > 1) {
        cell.style.width = `${this.model.columns.slice(0, row.leadingSpan).reduce((sum, c) => sum + c.widthPx, 0)}px`;
        cell.style.flexGrow = this.model.columns.slice(0, row.leadingSpan).some(c => c.grows) ? '1' : '0';
      }
      cell.addEventListener('click', () => { this.root.focus(); this.app.send({type: 'selectRow', rowId: row.rowId}); if (row.kind === 'PAST_HEADER') this.app.send({type: 'activateRow', rowId: row.rowId, columnId: column.id, how: 'CLICK'}); });
      cell.addEventListener('dblclick', () => this.app.send({type: 'activateRow', rowId: row.rowId, columnId: column.id, how: 'DOUBLE_CLICK'}));
      this.app.popups.tooltip(cell, async () => {
        const response = await this.app.transport.query({type: 'tooltip', rev: this.model.revision, index, columnId: column.id});
        return response.result || '';
      });
      node.append(cell);
    }
    node.addEventListener('contextmenu', async event => {
      event.preventDefault(); await this.app.send({type: 'selectRow', rowId: row.rowId});
      const kind = row.kind === 'MONTH_TOTAL' ? 'total' : row.kind === 'PAST_HEADER' ? 'pastHeader' : 'row';
      this.app.menus.context({kind, rowId: row.rowId}, event.clientX, event.clientY);
    });
    return node;
  }

  /** Находит строку в страницах, не вычисляя события самостоятельно. */
  async indexOf(id) {
    for (const [from, rows] of this.pages) {
      const offset = rows.findIndex(row => row.rowId === id);
      if (offset >= 0) return from + offset;
    }
    const epoch = this.epoch;
    for (let from = 0; from < this.model.rowCount; from += 300) {
      const rows = await this.page(from);
      if (epoch !== this.epoch) return -1;
      const offset = rows.findIndex(row => row.rowId === id);
      if (offset >= 0) return from + offset;
    }
    return -1;
  }

  /** Показывает строку для действия, сохраняя прокрутку, если строка уже полностью видна. */
  async ensureVisible(id) {
    const index = await this.indexOf(id);
    if (index < 0) return;
    const top = index * 26, bottom = top + 26;
    if (top < this.scroll.scrollTop) this.scroll.scrollTop = top;
    else if (bottom > this.scroll.scrollTop + this.scroll.clientHeight) this.scroll.scrollTop = bottom - this.scroll.clientHeight;
    await this.paint();
  }

  /** Соблюдает режим ядра: выделение с nearest-прокруткой либо явная прокрутка строки наверх. */
  async reveal(id, select) {
    if (select) {
      await this.ensureVisible(id);
      await this.app.send({type: 'selectRow', rowId: id});
      return;
    }
    const index = await this.indexOf(id);
    if (index < 0) return;
    this.scroll.scrollTop = index * 26; await this.paint();
  }

  /** Обрабатывает только навигацию таблицы, оставляя команды ядру. */
  async navigate(event) {
    if (!['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End'].includes(event.code) || event.ctrlKey || event.altKey) return;
    event.preventDefault(); event.stopPropagation();
    let index = await this.indexOf(this.model.selectedRowId);
    const page = Math.max(1, Math.floor(this.scroll.clientHeight / 26));
    if (event.code === 'Home') index = 0;
    else if (event.code === 'End') index = this.model.rowCount - 1;
    else index += {ArrowUp: -1, ArrowDown: 1, PageUp: -page, PageDown: page}[event.code];
    index = Math.max(0, Math.min(this.model.rowCount - 1, index));
    const row = await this.row(index);
    if (row) await this.reveal(row.rowId, true);
  }
}
