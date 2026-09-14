/**
 * @file Описание меню главного окна, панели инструментов, контекстных меню и горячих клавиш.
 *
 * Пункты описываются функциями, которые вычисляются при каждом открытии меню, поэтому флажки, радиогруппы
 * и доступность всегда соответствуют последнему состоянию с сервера. Действия вызываются по имени команды
 * (commands.js), так меню, панель, клавиатура и контекстные меню выполняют одно и то же.
 */

import { h, debounce, parseMoney, ruDate } from './util.js';
import { store, cmd } from './store.js';
import { MenuBar, menuButton, splitMenuButton, registerHotkeys } from './menu.js';

/** Разделитель меню. JavaFX: SeparatorMenuItem → Swing: JPopupMenu.Separator → Web: <li role="separator"><hr> */
const SEPARATOR = Object.freeze({ type: 'separator' });

/** Варианты периода (PeriodChoice). */
export const PERIODS = [['M3', '3 месяца'], ['M6', '6 месяцев'], ['M12', '12 месяцев'], ['M24', '24 месяца'], ['ALL', 'Весь горизонт']];

/** Текст окна «Горячие клавиши» для web-клиента (тот же набор, что в JavaFX и Swing, с заменами браузера). */
export const HOTKEYS_TEXT = [
  'Alt+Shift+N        Новый план                      (в JavaFX и Swing - Ctrl+N)',
  'Alt+Shift+O        Открыть план                    (Ctrl+O)',
  'Ctrl+S             Сохранить',
  'Ctrl+Shift+S       Сохранить как',
  'F2                 Переименовать план',
  'Ctrl+Shift+C       Экспорт в CSV                   (если занято браузером - Alt+Shift+C)',
  'Ctrl+I             Добавить регулярный доход',
  'Ctrl+E             Добавить регулярный расход',
  'Alt+Shift+T        Разовая операция                (Ctrl+T)',
  'Ctrl+J             Скорректировать выбранное событие',
  'Enter              Изменить выбранную строку (в таблице)',
  'Delete             Удалить выбранную операцию (в таблице)',
  'Ctrl+Z / Ctrl+Y    Отменить / Повторить',
  'Ctrl+1 / Ctrl+2    Таблица / График                (также Alt+Shift+1 / Alt+Shift+2)',
  'Ctrl+F             Перейти к фильтру',
  'Ctrl+G             Калькулятор цели',
  'F1                 О программе',
  'F10                Строка меню: стрелки - перемещение, Enter - выбрать, Esc - закрыть',
  'Shift+F10, Menu    Контекстное меню выбранной строки',
  '↑ ↓ PgUp PgDn      Выбор строки в таблице',
  '',
  'В быстрой правке суммы (двойной щелчок по сумме): Enter - сохранить, Esc - закрыть.',
  'Сочетания Ctrl+N, Ctrl+T, Ctrl+W и Ctrl+O браузер не отдаёт странице, поэтому в веб-клиенте',
  'они заменены на Alt+Shift+буква.',
].join('\n');

/** @returns {object} состояние с сервера или пустой объект */
function st() {
  return store.state || {};
}

/** @returns {object} текущий вид (ViewState) или пустой объект */
function vs() {
  return (store.state && store.state.viewState) || {};
}

/**
 * Обычный пункт меню.
 * JavaFX: MenuItem + KeyCombination → Swing: JMenuItem + setAccelerator → Web: <li role="menuitem"> + keydown
 * @param {string} text подпись
 * @param {string|Function} action имя команды или функция
 * @param {string} [accel] подпись сочетания клавиш
 * @param {object} [extra] {disabled, title}
 * @returns {object} описание пункта
 */
function item(text, action, accel = '', extra = {}) {
  return { type: 'item', text, accel, action: typeof action === 'function' ? action : () => cmd(action), ...extra };
}

/**
 * Пункт-флажок.
 * JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: role="menuitemcheckbox"
 * @param {string} text подпись
 * @param {boolean} checked отмечен ли
 * @param {Function} action действие
 * @param {object} [extra] {title, disabled, accel}
 * @returns {object} описание пункта
 */
function check(text, checked, action, extra = {}) {
  return { type: 'check', text, checked, action, ...extra };
}

/**
 * Пункт радиогруппы.
 * JavaFX: RadioMenuItem + ToggleGroup → Swing: JRadioButtonMenuItem + ButtonGroup → Web: role="menuitemradio"
 * @param {string} text подпись
 * @param {boolean} checked выбран ли
 * @param {Function} action действие
 * @param {object} [extra] {accel, title, disabled}
 * @returns {object} описание пункта
 */
function radio(text, checked, action, extra = {}) {
  return { type: 'radio', text, checked, action, ...extra };
}

/**
 * Флажок вида (фильтры и панели меню «Вид»).
 * @param {string} text подпись
 * @param {string} key ключ ViewState
 * @param {string} [title] подсказка
 * @returns {object} описание пункта
 */
function viewCheck(text, key, title) {
  return check(text, !!vs()[key], () => cmd('toggleView', key), { title });
}

/** @returns {Array<object>} пункты периода (радиогруппа, общая для меню «Вид» и кнопки «Период ▾») */
export function periodItems() {
  return PERIODS.map(([value, text]) => radio(`Показать ${text.toLowerCase()}`, vs().period === value, () => cmd('setPeriod', value)));
}

/**
 * Горизонт плана в месяцах (для слайдера).
 * @returns {number} месяцев
 */
function horizonMonths() {
  const plan = st().plan;
  if (!plan || !plan.horizon) return 12;
  const hz = plan.horizon;
  if (hz.kind === 'MONTHS') return hz.count || 12;
  if (hz.kind === 'YEARS') return (hz.count || 1) * 12;
  if (hz.kind === 'UNTIL' && hz.until) {
    const [y1, m1] = plan.startDate.split('-').map(Number);
    const [y2, m2] = hz.until.split('-').map(Number);
    return Math.max(1, (y2 - y1) * 12 + (m2 - m1));
  }
  return 12;
}

/**
 * Подпись горизонта: «12 мес.», «2 г. 3 мес.».
 * @param {number} months месяцев
 * @returns {string} подпись
 */
function horizonLabel(months) {
  const years = Math.floor(months / 12);
  const rest = months % 12;
  if (!years) return `${months} мес.`;
  return rest ? `${months} мес. (${years} г. ${rest} мес.)` : `${months} мес. (${years} г.)`;
}

/**
 * Пользовательский пункт со слайдером горизонта плана (меню не закрывается при движении ползунка).
 * JavaFX: CustomMenuItem (Slider, hideOnClick=false) → Swing: SwingSliderMenuItem (JPanel + JSlider)
 * → Web: <li class="custom"><input type="range">
 * @returns {object} описание пункта
 */
function horizonItem() {
  return {
    type: 'custom',
    render: () => {
      const months = horizonMonths();
      const label = h('span', { class: 'custom-label', text: `Горизонт плана: ${horizonLabel(months)}` });
      const range = h('input', {
        type: 'range', min: '1', max: '120', step: '1', value: String(Math.min(120, months)), class: 'menu-range',
        'aria-label': 'Горизонт плана, месяцев', title: 'Срок прогноза: от 1 до 120 месяцев',
      });
      const send = debounce((value) => cmd('setHorizonMonths', value), 500);
      range.addEventListener('input', () => {
        label.textContent = `Горизонт плана: ${horizonLabel(Number(range.value))}`;
        send(Number(range.value));
      });
      range.addEventListener('change', () => send.flush());
      return h('div', { class: 'custom-slider' }, label, range);
    },
  };
}

/**
 * Пользовательский пункт «Откладывать доп. в месяц» с числовым полем (меню не закрывается при вводе).
 * JavaFX: CustomMenuItem (Spinner, hideOnClick=false) → Swing: JSpinner в JMenu → Web: <li class="custom"><input type="number">
 * @returns {object} описание пункта
 */
function extraSavingItem() {
  return {
    type: 'custom',
    render: () => {
      const w = vs().whatIf || {};
      const minor = parseMoney(w.extraMonthlySaving) || 0;
      const input = h('input', {
        type: 'number', min: '0', step: '1000', value: String(Math.round(minor / 100)), class: 'input menu-number',
        'aria-label': 'Откладывать дополнительно в месяц', title: 'Сумма, которую сценарий откладывает каждый месяц',
      });
      const send = debounce(() => cmd('setExtraSaving', input.value || '0'), 600);
      input.addEventListener('input', () => send());
      input.addEventListener('change', () => send.flush());
      return h('label', { class: 'custom-field' },
        h('span', { class: 'custom-label', text: 'Откладывать доп. в месяц' }),
        h('span', { class: 'custom-input' }, input, h('span', { class: 'suffix', text: (st().plan && st().plan.currency) || '₽' })));
    },
  };
}

/** @returns {Array<object>} пункты «Что-если» (подменю «Инструменты» и кнопка «Что-если ▾») */
export function whatIfItems() {
  const w = vs().whatIf || {};
  /**
   * Включён ли коэффициент сценария (отличается от единицы).
   * @param {string|number} f коэффициент ("1", "0.90")
   * @returns {boolean} true - суммы меняются
   */
  const factorOn = (f) => Number(String(f ?? '1').replace(',', '.')) !== 1;
  return [
    check('Доходы −10 %', factorOn(w.incomeFactor), () => cmd('whatIfIncome'), { title: 'Все доходы на 10 % меньше' }),
    check('Расходы +10 %', factorOn(w.expenseFactor), () => cmd('whatIfExpense'), { title: 'Все расходы на 10 % больше' }),
    extraSavingItem(),
    SEPARATOR,
    item('Применить к плану…', 'applyWhatIf', '', { disabled: !w.active, title: 'Пересчитать суммы операций плана по сценарию' }),
    item('Сбросить «что-если»', 'resetWhatIf', '', { disabled: !w.active }),
  ];
}

/** @returns {Array<object>} пункты подменю «Недавние» */
function recentItems() {
  const recent = (st().settings && st().settings.recentPlans) || [];
  if (!recent.length) return [item('Нет недавних планов', () => {}, '', { disabled: true })];
  return recent.map((name) => item(name.replace(/\.md$/i, ''), () => cmd('openRecent', name), '', { title: name }));
}

/** @returns {object|null} выбранная строка регулярной операции */
function selectedRuleRow() {
  const row = store.selectedRow();
  return row && row.origin === 'RULE' ? row : null;
}

/**
 * Строит строку меню главного окна.
 * JavaFX: MenuBar → Swing: JMenuBar → Web: <nav role="menubar">
 * @param {HTMLElement} nav элемент <nav role="menubar">
 * @returns {MenuBar} строка меню
 */
export function buildMenuBar(nav) {
  // JavaFX: Menu → Swing: JMenu → Web: <ul role="menu"> (каждое верхнее меню)
  return new MenuBar(nav, [
    {
      text: 'Файл',
      items: () => [
        item('Новый план…', 'newPlan', 'Alt+Shift+N'),
        item('Открыть…', 'openPlan', 'Alt+Shift+O', { title: 'План из папки CashMemory или из файла' }),
        item('Открыть пример', 'openSample', '', { title: 'План «Пример» с зарплатой, арендой и целью' }),
        // JavaFX: Menu (вложенное «Недавние») → Swing: JMenu → Web: вложенный <ul role="menu">
        { type: 'submenu', text: 'Недавние', items: recentItems },
        item('Импорт с компьютера…', 'importPlan', '', { title: 'Открыть файл .md, выбранный в браузере' }),
        SEPARATOR,
        item('Сохранить', 'save', 'Ctrl+S'),
        item('Сохранить как…', 'saveAs', 'Ctrl+Shift+S'),
        item('Скачать файл плана (.md)', 'downloadPlan'),
        item('Переименовать…', 'rename', 'F2'),
        SEPARATOR,
        check('Автосохранение', !!(st().settings && st().settings.autosave), () => cmd('toggleAutosave'),
          { title: 'Сохранять план примерно через секунду после каждого изменения' }),
        item('Экспорт CSV…', 'exportCsv', 'Ctrl+Shift+C'),
        item('Сохранить график (SVG)…', 'saveChartSvg'),
        item('Папка CashMemory…', 'cashMemoryFolder'),
        SEPARATOR,
        item('Выход (остановить сервер)', 'exit', '', { title: 'Сохранить настройки и корректно остановить сервер' }),
      ],
    },
    {
      text: 'Правка',
      items: () => {
        const doc = st().document || {};
        const row = store.selectedRow();
        const hasAdj = !!(row && row.origin === 'RULE' && store.adjustment(row.ruleId, row.originalDate));
        return [
          item('Добавить доход…', 'addIncome', 'Ctrl+I'),
          item('Добавить расход…', 'addExpense', 'Ctrl+E'),
          item('Разовая операция…', () => cmd('addOneTime'), 'Alt+Shift+T'),
          item('Изменить…', () => cmd('editSelected'), 'Enter', { disabled: !row }),
          item('Удалить…', () => cmd('deleteSelected'), 'Delete', { disabled: !row || (row.origin !== 'RULE' && row.origin !== 'ONE_TIME') }),
          item('Скорректировать событие…', () => cmd('adjustSelected'), 'Ctrl+J', { disabled: !selectedRuleRow() }),
          item('Вернуть как по правилу', () => cmd('resetAdjustment'), '', { disabled: !hasAdj }),
          SEPARATOR,
          item(doc.undoText ? `Отменить: ${doc.undoText}` : 'Отменить', 'undo', 'Ctrl+Z', { disabled: !st().canUndo }),
          item(doc.redoText ? `Повторить: ${doc.redoText}` : 'Повторить', 'redo', 'Ctrl+Y', { disabled: !st().canRedo }),
          SEPARATOR,
          item('Параметры плана…', 'planSettings'),
          item('Актуализировать на сегодня…', 'actualize', '', { title: 'Перенести начало плана на сегодня с балансом по прогнозу' }),
          item('Сверить баланс…', 'reconcile', '', { title: 'Ввести фактический остаток на сегодня' }),
        ];
      },
    },
    {
      text: 'Вид',
      items: () => [
        radio('Таблица', vs().mode !== 'CHART', () => cmd('setMode', 'TABLE'), { accel: 'Ctrl+1' }),
        radio('График', vs().mode === 'CHART', () => cmd('setMode', 'CHART'), { accel: 'Ctrl+2' }),
        SEPARATOR,
        viewCheck('Доходы', 'showIncome'),
        viewCheck('Расходы', 'showExpense'),
        viewCheck('Разовые операции', 'showOneTime'),
        viewCheck('Пропущенные события', 'showSkipped', 'Показывать зачёркнутые пропущенные события'),
        viewCheck('Итоги по месяцам', 'monthTotals'),
        viewCheck('Маркеры событий на графике', 'chartMarkers'),
        viewCheck('Столбцы итогов месяца на графике', 'chartBars'),
        viewCheck('Панель сводки', 'summaryPanel'),
        SEPARATOR,
        ...periodItems(),
        SEPARATOR,
        horizonItem(),
      ],
    },
    {
      text: 'Инструменты',
      items: () => [
        item('Калькулятор цели…', 'goalCalculator', 'Ctrl+G'),
        { type: 'submenu', text: 'Что-если', items: whatIfItems },
        SEPARATOR,
        item('Проверить план…', 'diagnostics'),
        item('Очистить неиспользуемые корректировки', 'cleanupOrphans'),
        item('Валюта…', 'currency'),
      ],
    },
    {
      text: 'Восстановление',
      items: () => [
        // JavaFX: RadioMenuItem «Реестр Windows / XML-файл» → Swing: JRadioButtonMenuItem → Web: единственный
        // отключённый role="menuitemradio": у браузера состояние хранится на сервере.
        radio('Хранилище: сервер', true, () => {}, {
          disabled: true, title: 'Состояние web-клиента хранится на сервере (CashMemory/web-session.md)',
        }),
        SEPARATOR,
        item('Сделать снимок сейчас', 'snapshotNow'),
        item('Показать последний снимок…', 'showLastSnapshot'),
        item('Очистить снимки…', 'clearSnapshots'),
        SEPARATOR,
        {
          type: 'submenu',
          text: 'Симулировать сбой',
          items: () => [
            item('Остановить сервер аварийно…', 'simulateServerCrash', '', { title: 'Runtime.halt(3) на сервере без сохранения' }),
            item('Необработанная ошибка JavaScript', 'simulateJsError', '', { title: 'Аналог необработанного исключения в UI-потоке' }),
          ],
        },
      ],
    },
    {
      text: 'Справка',
      items: () => [
        item('О программе', 'about', 'F1'),
        item('Горячие клавиши…', 'hotkeys'),
        item('Формат файла .md…', 'formatHelp'),
      ],
    },
  ]);
}

/**
 * Строит панель инструментов.
 * @param {HTMLElement} container элемент панели
 * @returns {{filter: HTMLInputElement, update: (state: object) => void}} панель
 */
export function buildToolbar(container) {
  container.replaceChildren();
  // JavaFX: SplitMenuButton → Swing: SwingSplitMenuButton → Web: div.split-button
  const add = splitMenuButton('＋ Добавить доход', () => cmd('addIncome'), () => [
    item('Добавить расход…', 'addExpense', 'Ctrl+E'),
    item('Разовая операция…', () => cmd('addOneTime'), 'Alt+Shift+T'),
    SEPARATOR,
    item('Скорректировать событие…', () => cmd('adjustSelected'), 'Ctrl+J', { disabled: !selectedRuleRow() }),
  ], { main: 'Новый регулярный доход (Ctrl+I)', arrow: 'Расход, разовая операция, корректировка события' });

  // JavaFX: MenuButton → Swing: SwingMenuButton → Web: <button> + <ul role="menu">
  const period = menuButton(() => {
    const found = PERIODS.find(([value]) => value === vs().period);
    return `Период: ${found ? found[1] : '-'}`;
  }, () => [...periodItems(), SEPARATOR, horizonItem()], 'Сколько месяцев показывать в таблице и на графике');

  // JavaFX: MenuButton → Swing: SwingMenuButton → Web: <button> + <ul role="menu">
  const whatIf = menuButton(() => (vs().whatIf && vs().whatIf.active ? 'Что-если ●' : 'Что-если'), whatIfItems,
    'Сценарии «что-если»: план не меняется, пока сценарий не применён');

  // JavaFX: Tooltip → Swing: setToolTipText → Web: title (у простых кнопок)
  const tableButton = h('button', {
    type: 'button', class: 'tool-button toggle', 'aria-pressed': 'true', title: 'Таблица событий (Ctrl+1)', text: '▦ Таблица',
    onClick: () => cmd('setMode', 'TABLE'),
  });
  const chartButton = h('button', {
    type: 'button', class: 'tool-button toggle', 'aria-pressed': 'false', title: 'График баланса (Ctrl+2)', text: '📈 График',
    onClick: () => cmd('setMode', 'CHART'),
  });
  const toggle = h('div', { class: 'toggle-group', role: 'group', 'aria-label': 'Вид' }, tableButton, chartButton);

  const filter = h('input', {
    type: 'search', class: 'input filter-input', placeholder: 'Фильтр (Ctrl+F)', 'aria-label': 'Фильтр строк',
    title: 'Показывать только строки, где название, категория или заметка содержат этот текст',
  });
  const sendFilter = debounce(() => cmd('setFilterText', filter.value), 300);
  filter.addEventListener('input', () => sendFilter());
  filter.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      sendFilter.flush();
      if (store.ui.table) store.ui.table.focus();
    }
  });

  const undo = h('button', {
    type: 'button', class: 'tool-button icon', title: 'Отменить (Ctrl+Z)', 'aria-label': 'Отменить', text: '↶',
    onClick: () => cmd('undo'),
  });
  const redo = h('button', {
    type: 'button', class: 'tool-button icon', title: 'Повторить (Ctrl+Y)', 'aria-label': 'Повторить', text: '↷',
    onClick: () => cmd('redo'),
  });
  const goal = h('button', {
    type: 'button', class: 'tool-button', title: 'Калькулятор цели (Ctrl+G)', text: '◎ Цель', onClick: () => cmd('goalCalculator'),
  });
  const save = h('button', {
    type: 'button', class: 'tool-button save-button', title: 'Сохранить план (Ctrl+S)', text: '💾 Сохранить',
    onClick: () => cmd('save'),
  });
  container.append(add, h('span', { class: 'tool-sep', 'aria-hidden': 'true' }), period, whatIf, goal,
    h('span', { class: 'tool-sep', 'aria-hidden': 'true' }), toggle, filter, h('span', { class: 'tool-spacer' }), undo, redo, save);

  return {
    filter,
    /**
     * Обновляет панель по состоянию.
     * @param {object} state состояние с сервера
     */
    update(state) {
      const v = state.viewState;
      period.updateText();
      whatIf.updateText();
      whatIf.classList.toggle('active', !!(v.whatIf && v.whatIf.active));
      tableButton.setAttribute('aria-pressed', String(v.mode !== 'CHART'));
      chartButton.setAttribute('aria-pressed', String(v.mode === 'CHART'));
      if (document.activeElement !== filter && filter.value !== v.filterText) filter.value = v.filterText || '';
      undo.disabled = !state.canUndo;
      undo.title = state.document.undoText ? `Отменить: ${state.document.undoText} (Ctrl+Z)` : 'Отменить (Ctrl+Z)';
      redo.disabled = !state.canRedo;
      redo.title = state.document.redoText ? `Повторить: ${state.document.redoText} (Ctrl+Y)` : 'Повторить (Ctrl+Y)';
      save.classList.toggle('dirty', !!state.dirty);
    },
  };
}

/**
 * Можно ли выполнять «табличные» сочетания Enter и Delete: фокус в таблице или на самой странице,
 * а не на кнопке, в меню или в диалоге.
 * @param {KeyboardEvent} e событие
 * @returns {boolean} да, если сочетание относится к таблице
 */
function tableContext(e) {
  const target = /** @type {HTMLElement} */ (e.target);
  if (target === document.body) return true;
  return !!(target.closest && target.closest('.table-wrap'));
}

/**
 * Регистрирует горячие клавиши главного окна (раздел 6 плана).
 */
export function registerAppHotkeys() {
  registerHotkeys([
    { keys: ['Alt+Shift+N'], action: () => cmd('newPlan') },
    { keys: ['Alt+Shift+O'], action: () => cmd('openPlan') },
    { keys: ['Ctrl+S'], action: () => cmd('save'), allowInInput: true },
    { keys: ['Ctrl+Shift+S'], action: () => cmd('saveAs'), allowInInput: true },
    { keys: ['Ctrl+I'], action: () => cmd('addIncome') },
    { keys: ['Ctrl+E'], action: () => cmd('addExpense') },
    { keys: ['Alt+Shift+T'], action: () => cmd('addOneTime') },
    { keys: ['Ctrl+J'], action: () => cmd('adjustSelected') },
    { keys: ['Enter'], action: () => cmd('editSelected'), when: tableContext },
    { keys: ['Delete'], action: () => cmd('deleteSelected'), when: tableContext },
    { keys: ['Ctrl+Z'], action: () => cmd('undo') },
    { keys: ['Ctrl+Y', 'Ctrl+Shift+Z'], action: () => cmd('redo') },
    { keys: ['Ctrl+1', 'Alt+Shift+1'], action: () => cmd('setMode', 'TABLE') },
    { keys: ['Ctrl+2', 'Alt+Shift+2'], action: () => cmd('setMode', 'CHART') },
    { keys: ['Ctrl+G'], action: () => cmd('goalCalculator') },
    { keys: ['Ctrl+F'], action: () => cmd('focusFilter'), allowInInput: true },
    { keys: ['F1'], action: () => cmd('about'), allowInInput: true },
    { keys: ['F2'], action: () => cmd('rename') },
    { keys: ['Ctrl+Shift+C', 'Alt+Shift+C'], action: () => cmd('exportCsv') },
  ]);
}

/**
 * Пункты контекстного меню строки таблицы.
 * JavaFX: ContextMenu (ForecastContextMenu) → Swing: JPopupMenu → Web: <ul class="context-menu" role="menu">
 * @param {object} row строка прогноза
 * @returns {Array<object>} пункты
 */
export function rowContextItems(row) {
  const isRule = row.origin === 'RULE';
  const isOneTime = row.origin === 'ONE_TIME';
  const rule = isRule ? store.rule(row.ruleId) : null;
  const hasAdj = isRule && !!store.adjustment(row.ruleId, row.originalDate);
  const editText = isOneTime ? 'Изменить разовую операцию…' : row.origin === 'START' ? 'Параметры плана…' : 'Изменить операцию…';
  return [
    item(editText, () => cmd('editSelected', row), 'Enter', { disabled: row.origin === 'WHAT_IF' }),
    item('Скорректировать событие…', () => cmd('adjustSelected', row), 'Ctrl+J', { disabled: !isRule }),
    item('Пропустить это событие', () => cmd('skipOccurrence', row), '', { disabled: !isRule || row.flags.skipped }),
    item('Вернуть как по правилу', () => cmd('resetAdjustment', row), '', { disabled: !hasAdj }),
    SEPARATOR,
    item(`Добавить разовую на ${row.dateText}…`, () => cmd('addOneTime', row.date)),
    item('Перейти к правилу…', () => cmd('goToRule', row), '', { disabled: !isRule }),
    item(rule && !rule.enabled ? 'Включить правило' : 'Отключить правило', () => cmd('toggleRuleEnabled', row), '', { disabled: !isRule }),
    SEPARATOR,
    item('Копировать строку', () => cmd('copyRow', row)),
    item('Удалить…', () => cmd('deleteSelected', row), 'Delete', { disabled: !isRule && !isOneTime }),
  ];
}

/**
 * Пункты контекстного меню строки «итог месяца».
 * @param {HTMLElement} tr строка таблицы
 * @returns {Array<object>} пункты
 */
export function monthTotalContextItems(tr) {
  return [
    item('Копировать итог', () => cmd('copyText', [...tr.cells].map((c) => c.textContent).filter(Boolean).join('\t'))),
    SEPARATOR,
    viewCheck('Итоги по месяцам', 'monthTotals'),
  ];
}

/**
 * Пункты контекстного меню графика.
 * JavaFX: ContextMenu → Swing: JPopupMenu → Web: <ul class="context-menu" role="menu">
 * @param {string} iso дата под курсором
 * @returns {Array<object>} пункты
 */
export function chartContextItems(iso) {
  return [
    item(`Показать в таблице с ${ruDate(iso)}`, () => cmd('showInTable', iso)),
    item(`Добавить разовую операцию на ${ruDate(iso)}…`, () => cmd('addOneTime', iso)),
    SEPARATOR,
    viewCheck('Маркеры событий', 'chartMarkers'),
    viewCheck('Столбцы итогов месяца', 'chartBars'),
    SEPARATOR,
    item('Сохранить график (SVG)…', 'saveChartSvg'),
  ];
}

/**
 * Пункты контекстного меню карточки сводки.
 * @param {{title:string, value:string, date?:string}} card карточка
 * @returns {Array<object>} пункты
 */
export function cardContextItems(card) {
  return [
    item('Копировать значение', () => cmd('copyText', `${card.title}: ${card.value}`)),
    item('Показать в таблице', () => cmd('showInTable', card.date), '', { disabled: !card.date }),
    item('Показать на графике', () => cmd('setMode', 'CHART')),
    SEPARATOR,
    viewCheck('Панель сводки', 'summaryPanel'),
  ];
}
