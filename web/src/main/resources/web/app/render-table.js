/** @file Виртуальная таблица, страницы по 300 строк и высота строки 26 пикселей. */
import {element, identify, color, look, button} from './dom.js';
import {iconText} from './icon.js';

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
    this.generation = app.transport.generation;
    this.paintFrame = null; this.painted = null;
    this.scroll.addEventListener('scroll', /** Планирует перерисовку видимых строк после прокрутки. */ () => this.schedulePaint());
    this.scroll.addEventListener('scroll', /** Согласует горизонтальную прокрутку заголовка с областью строк. */ () => { this.header.scrollLeft = this.scroll.scrollLeft; });
    this.root.addEventListener('keydown', /** Передаёт нажатие клавиши обработчику навигации таблицы. */ event => this.navigate(event));
    this.resize = new ResizeObserver(/** Планирует перерисовку при изменении размера области строк. */ () => this.schedulePaint()); this.resize.observe(this.scroll);
  }

  /** Применяет ревизию и заголовки, оставляя вычисления строк серверу. */
  async update(model) {
    const generation = this.app.transport.generation;
    this.resetGeneration();
    const changed = this.model?.revision !== model.revision;
    this.model = model; this.painted = null; this.root.dataset.rowCount = model.rowCount;
    this.root.setAttribute('aria-rowcount', model.rowCount);
    this.root.dataset.selectedRowId = model.selectedRowId || '';
    if (changed) { this.pages.clear(); this.loads.clear(); this.epoch++; }
    this.header.replaceChildren(...model.columns.map(/** Создаёт заголовок колонки с текстом, подсказкой и геометрией из модели ядра. */ column => {
      const node = identify(element('div', 'table-cell', column.title), column.id);
      iconText(node, column.title, true);
      node.dataset.tooltip = column.headerTooltip; this.columnStyle(node, column);
      node.style.fontWeight = '400'; return node;
    }));
    this.canvas.style.height = `${model.rowCount * 26}px`;
    // Две боковые рамки входят в размер полотна, но не в ширины колонок ядра.
    this.canvas.style.minWidth = `${model.columns.reduce(/** Накапливает ширину колонок для минимальной ширины полотна таблицы. */ (sum, column) => sum + column.widthPx, 0) + 2}px`;
    this.root.querySelector('.placeholder')?.remove();
    if (model.placeholder) {
      const node = element('div', 'placeholder');
      const text = element('div', 'placeholder-text', model.placeholder.text);
      text.style.color = color(model.placeholder.color); node.append(text);
      const actions = element('div', 'placeholder-buttons');
      for (const action of model.placeholder.buttons) {
        const control = button(action.id, action.text, '');
        control.addEventListener('click', /** Выполняет команду кнопки заглушки пустой таблицы. */ () => this.app.command(action.command, {}, 'MAIN'));
        actions.append(control);
      }
      node.append(actions); this.root.append(node);
    }
    await this.paint();
    if (!this.app.transport.current(generation) || this.model !== model) return;
    if (model.scrollToRowId && this.lastReveal !== model.scrollToRowId) {
      this.lastReveal = model.scrollToRowId;
      await this.reveal(model.scrollToRowId, false);
    }
  }

  /** Применяет ширину и выравнивание столбца; жирность относится только к ячейкам строк. */
  columnStyle(node, column) {
    node.style.width = `${column.widthPx}px`;
    node.style.flexGrow = column.grows ? '1' : '0';
    node.style.textAlign = column.align.toLowerCase();
  }

  /** Сбрасывает страницы нового сервера даже при совпадении номера ревизии модели. */
  resetGeneration() {
    const generation = this.app.transport.generation;
    if (this.generation === generation) return;
    this.generation = generation; this.pages.clear(); this.loads.clear(); this.epoch++;
    this.lastReveal = null;
  }

  /** Загружает страницу один раз для текущей ревизии. */
  async page(index) {
    this.resetGeneration();
    const generation = this.generation;
    const from = Math.floor(index / 300) * 300;
    if (this.pages.has(from)) return this.pages.get(from);
    if (this.loads.has(from)) return this.loads.get(from);
    const rev = this.model.revision; const epoch = this.epoch;
    const load = this.app.transport.query({type: 'rows', rev, from, count: Math.min(300, this.model.rowCount - from)}).then(/** Сохраняет строки актуальной страницы в ограниченном кеше или запрашивает синхронизацию устаревшей ревизии. */ response => {
      if (!this.app.transport.current(generation) || epoch !== this.epoch) return [];
      if (response.stale) { this.app.resync(); return []; }
      const rows = response.result || [];
      this.pages.set(from, rows);
      if (this.pages.size > 7) this.pages.delete(this.pages.keys().next().value);
      return rows;
    }).finally(/** Удаляет завершённую загрузку из реестра только для её текущего поколения и эпохи. */ () => { if (this.app.transport.current(generation) && epoch === this.epoch) this.loads.delete(from); });
    this.loads.set(from, load); return load;
  }

  /** Получает одну готовую строку из её страницы. */
  async row(index) {
    if (index < 0 || index >= this.model.rowCount) return null;
    return (await this.page(index))[index % 300] || null;
  }

  /** Объединяет resize и прокрутку, вынося запись DOM из доставки ResizeObserver. */
  schedulePaint() {
    if (this.paintFrame !== null) return;
    this.paintFrame = requestAnimationFrame(/** Освобождает запланированный кадр и запускает отрисовку видимой области. */ () => {
      this.paintFrame = null;
      this.paint();
    });
  }

  /** Согласует полосу и заголовок только при изменении их фактической геометрии. */
  layoutTable() {
    const height = this.scroll.clientHeight;
    if (height > 0) {
      const gutter = this.scroll.scrollHeight > height ? 'stable' : 'auto';
      if (this.scroll.style.scrollbarGutter !== gutter) this.scroll.style.scrollbarGutter = gutter;
    }
    // Ширину читаем после изменения полосы: она уже может занимать часть области строк.
    const width = this.scroll.clientWidth;
    if (width > 0) {
      const value = `${width}px`;
      if (this.header.style.width !== value) this.header.style.width = value;
    }
    // Изменение ширины и новые заголовки могут сбросить горизонтальную прокрутку.
    if (this.header.scrollLeft !== this.scroll.scrollLeft) this.header.scrollLeft = this.scroll.scrollLeft;
  }

  /** Рисует видимую область с небольшим запасом строк. */
  async paint() {
    if (this.paintFrame !== null) {
      cancelAnimationFrame(this.paintFrame); this.paintFrame = null;
    }
    if (!this.model) return;
    const model = this.model;
    const generation = this.generation;
    if (!this.app.transport.current(generation)) return;
    const epoch = this.epoch;
    const first = Math.max(0, Math.floor(this.scroll.scrollTop / 26) - 3);
    const count = Math.min(this.model.rowCount - first, Math.ceil(this.scroll.clientHeight / 26) + 6);
    const rows = await Promise.all(Array.from({length: Math.max(0, count)}, /** Запрашивает строку по смещению в видимой области с запасом. */ (_, offset) => this.row(first + offset)));
    if (!this.app.transport.current(generation) || epoch !== this.epoch || this.model !== model) return;
    if (Math.max(0, Math.floor(this.scroll.scrollTop / 26) - 3) !== first
        || Math.min(model.rowCount - first, Math.ceil(this.scroll.clientHeight / 26) + 6) !== count) {
      this.schedulePaint(); return;
    }
    const previous = this.painted;
    if (!previous || previous.model !== model || previous.generation !== generation || previous.epoch !== epoch
        || previous.first !== first || previous.rows.length !== rows.length
        || rows.some(/** Проверяет, изменилась ли строка относительно ранее нарисованной области. */ (row, index) => row !== previous.rows[index])) {
      this.canvas.replaceChildren(...rows.flatMap(/** Создаёт узел загруженной строки на её позиции, пропуская отсутствующие строки. */ (row, offset) => row ? [this.widget(row, first + offset)] : []));
      this.painted = {model, generation, epoch, first, rows};
    }
    // Измеряем после удаления старых строк: они не должны оставлять полосу у пустой таблицы.
    this.layoutTable();
  }

  /** Создаёт строку и отправляет реальные щелчки, активацию и наведение. */
  widget(row, index) {
    const widgetGeneration = this.app.transport.generation;
    const node = identify(element('div', 'table-row'), row.rowId, row.kind);
    node.role = 'row'; node.dataset.index = index; node.style.top = `${index * 26}px`;
    node.setAttribute('aria-selected', String(row.rowId === this.model.selectedRowId));
    node.style.background = color(row.rowStyle?.background); look(node, row.rowStyle);
    for (let col = 0; col < this.model.columns.length; col++) {
      const column = this.model.columns[col];
      if (col > 0 && col < row.leadingSpan) continue;
      const cell = identify(element('div', 'table-cell', row.cells[col]), column.id); cell.role = 'gridcell';
      if (column.id === 'marks') cell.dataset.iconPositions = 'glyphs';
      if (row.kind === 'PAST_HEADER' || column.id === 'marks') iconText(cell, row.cells[col], true);
      this.columnStyle(cell, column);
      const cellStyle = {...row.rowStyle, ...row.cellStyles?.[column.id]};
      look(cell, {...cellStyle, bold: column.bold || cellStyle.bold});
      if (col === 0 && row.leadingSpan > 1) {
        cell.style.width = `${this.model.columns.slice(0, row.leadingSpan).reduce(/** Суммирует ширины колонок, покрытых объединённой ведущей ячейкой. */ (sum, c) => sum + c.widthPx, 0)}px`;
        cell.style.flexGrow = this.model.columns.slice(0, row.leadingSpan).some(/** Проверяет, должна ли объединённая ячейка растягиваться вместе с покрытой колонкой. */ c => c.grows) ? '1' : '0';
      }
      cell.addEventListener('click', /** Выделяет строку текущего поколения и активирует заголовок прошлых событий по щелчку. */ () => { if (!this.app.transport.current(widgetGeneration)) return; this.root.focus(); this.app.send({type: 'selectRow', rowId: row.rowId}); if (row.kind === 'PAST_HEADER') this.app.send({type: 'activateRow', rowId: row.rowId, columnId: column.id, how: 'CLICK'}); });
      cell.addEventListener('dblclick', /** Отправляет двойную активацию ячейки, если её поколение ещё актуально. */ () => { if (this.app.transport.current(widgetGeneration)) this.app.send({type: 'activateRow', rowId: row.rowId, columnId: column.id, how: 'DOUBLE_CLICK'}); });
      this.app.popups.tooltip(cell, /** Получает текст подсказки ячейки из ядра для текущего поколения виджета. */ async () => {
        if (!this.app.transport.current(widgetGeneration)) return '';
        const response = await this.app.transport.query({type: 'tooltip', rev: this.model.revision, index, columnId: column.id});
        return response.result || '';
      });
      node.append(cell);
    }
    node.addEventListener('contextmenu', /** Выделяет строку и открывает её контекстное меню после проверки поколения ответа. */ async event => {
      if (!this.app.transport.current(widgetGeneration)) return;
      const generation = this.app.transport.generation;
      event.preventDefault(); await this.app.send({type: 'selectRow', rowId: row.rowId});
      // Выбор штатно перерисовывает строку: снятие старого DOM-узла не отменяет контекстный запрос.
      // Только смена поколения означает устаревшее действие; цель проверяет актуальное ядро.
      if (!this.app.transport.current(generation)) return;
      const kind = row.kind === 'MONTH_TOTAL' ? 'total' : row.kind === 'PAST_HEADER' ? 'pastHeader' : 'row';
      this.app.menus.context({kind, rowId: row.rowId}, event.clientX, event.clientY);
    });
    return node;
  }

  /** Находит строку в страницах, не вычисляя события самостоятельно. */
  async indexOf(id) {
    this.resetGeneration();
    const generation = this.generation;
    for (const [from, rows] of this.pages) {
      const offset = rows.findIndex(/** Находит смещение строки с заданным идентификатором в странице. */ row => row.rowId === id);
      if (offset >= 0) return from + offset;
    }
    const epoch = this.epoch;
    for (let from = 0; from < this.model.rowCount; from += 300) {
      const rows = await this.page(from);
      if (!this.app.transport.current(generation) || epoch !== this.epoch) return -1;
      const offset = rows.findIndex(/** Находит смещение строки с заданным идентификатором в странице. */ row => row.rowId === id);
      if (offset >= 0) return from + offset;
    }
    return -1;
  }

  /** Показывает строку для действия, сохраняя прокрутку, если строка уже полностью видна. */
  async ensureVisible(id) {
    const generation = this.app.transport.generation; const epoch = this.epoch;
    const index = await this.indexOf(id);
    if (!this.app.transport.current(generation) || epoch !== this.epoch || index < 0) return;
    const top = index * 26, bottom = top + 26;
    if (top < this.scroll.scrollTop) this.scroll.scrollTop = top;
    else if (bottom > this.scroll.scrollTop + this.scroll.clientHeight) this.scroll.scrollTop = bottom - this.scroll.clientHeight;
    await this.paint();
  }

  /** Соблюдает режим ядра: выделение с nearest-прокруткой либо явная прокрутка строки наверх. */
  async reveal(id, select) {
    const generation = this.app.transport.generation; const epoch = this.epoch;
    if (select) {
      await this.ensureVisible(id);
      if (!this.app.transport.current(generation) || epoch !== this.epoch) return;
      await this.app.send({type: 'selectRow', rowId: id});
      return;
    }
    const index = await this.indexOf(id);
    if (!this.app.transport.current(generation) || epoch !== this.epoch || index < 0) return;
    this.scroll.scrollTop = index * 26; await this.paint();
  }

  /** Обрабатывает только навигацию таблицы, оставляя команды ядру. */
  async navigate(event) {
    if (!['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End'].includes(event.code) || event.ctrlKey || event.altKey) return;
    event.preventDefault(); event.stopPropagation();
    const generation = this.app.transport.generation; const epoch = this.epoch;
    let index = await this.indexOf(this.model.selectedRowId);
    if (!this.app.transport.current(generation) || epoch !== this.epoch) return;
    const page = Math.max(1, Math.floor(this.scroll.clientHeight / 26));
    if (event.code === 'Home') index = 0;
    else if (event.code === 'End') index = this.model.rowCount - 1;
    else index += {ArrowUp: -1, ArrowDown: 1, PageUp: -page, PageDown: page}[event.code];
    index = Math.max(0, Math.min(this.model.rowCount - 1, index));
    const row = await this.row(index);
    if (!this.app.transport.current(generation) || epoch !== this.epoch) return;
    if (row) await this.reveal(row.rowId, true);
  }
}
