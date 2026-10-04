/** @file Примитивы сцены ядра в SVG, без расчёта прогноза. */
import {color, identify} from './dom.js';

/** Создаёт SVG-узел с безопасными атрибутами. */
export function svgNode(tag, attrs = {}, text = '') {
  const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [name, value] of Object.entries(attrs)) if (value != null) node.setAttribute(name, value);
  node.textContent = text;
  return node;
}

/** Возвращает атрибуты линии из модели ядра. */
function stroke(model) {
  return {stroke: color(model.color), 'stroke-width': model.width, 'stroke-dasharray': model.dash.join(' '), 'stroke-opacity': model.opacity};
}

/** Переводит точки сцены в атрибут SVG. */
function points(values) { return values.map(/** Преобразует точку сцены в пару координат для атрибута SVG. */ point => `${point.x},${point.y}`).join(' '); }

/** Рисует один примитив, сохраняя порядок модели. */
export function primitive(model) {
  switch (model.kind) {
    case 'Area': {
      const vertices = [...model.points];
      if (vertices.length) vertices.push({x: vertices.at(-1).x, y: model.baselineY}, {x: vertices[0].x, y: model.baselineY});
      return svgNode('polygon', {points: points(vertices), fill: color(model.fill), opacity: model.opacity});
    }
    case 'Polyline': return svgNode('polyline', {points: points(model.points), fill: 'none', ...stroke(model.stroke)});
    case 'Line': return svgNode('line', {x1: model.x1, y1: model.y1, x2: model.x2, y2: model.y2, ...stroke(model.stroke)});
    case 'Box': return svgNode('rect', {x: model.x, y: model.y, width: model.width, height: model.height, fill: color(model.fill), opacity: model.opacity});
    case 'Circle': return svgNode('circle', {cx: model.cx, cy: model.cy, r: model.r, fill: color(model.fill), stroke: color(model.stroke), 'stroke-width': model.strokeWidth});
    case 'Label': return svgNode('text', {x: model.x, y: model.y, 'text-anchor': model.anchor.toLowerCase(), fill: color(model.color), 'font-size': `var(--cp-font-${model.font.toLowerCase()}-size)`}, model.text);
    default: throw new Error(`Unknown primitive: ${model.kind}`);
  }
}

/** Запрашивает сцену по реальному размеру области и выводит её в SVG. */
export class Chart {
  /** Подключает только действия указателя. */
  constructor(app) {
    this.app = app; this.root = document.getElementById('chart'); this.epoch = 0;
    this.root.addEventListener('contextmenu', /** Открывает меню ядра для точки графика под указателем. */ event => {
      event.preventDefault(); const p = this.position(event);
      app.menus.context({kind: 'chart', x: p.x, y: p.y, width: this.root.clientWidth, height: this.root.clientHeight}, event.clientX, event.clientY);
    });
    this.root.addEventListener('pointermove', /** Передаёт движение указателя обработчику наведения графика. */ event => this.hover(event));
    this.root.addEventListener('pointerleave', /** Убирает подсветку и карточку дня при выходе указателя с графика. */ () => this.clearHover());
    this.root.addEventListener('dblclick', /** Выполняет первое доступное действие точки графика, если ответ относится к текущей сцене. */ async event => {
      if (this.generation !== app.transport.generation) return;
      const generation = app.transport.generation; const epoch = this.epoch;
      const p = this.position(event);
      const response = await app.transport.query({type: 'contextMenu', target: {kind: 'chart', x: p.x, y: p.y, width: this.root.clientWidth, height: this.root.clientHeight}});
      if (!app.transport.current(generation) || epoch !== this.epoch) return;
      const action = response.result?.[0]; if (action?.enabled) app.command(action.command, action.args, 'MAIN');
    });
  }

  /** Переводит координаты указателя в координаты виджета. */
  position(event) { const rect = this.root.getBoundingClientRect(); return {x: event.clientX - rect.x, y: event.clientY - rect.y}; }

  /** Скрывает наведение и отменяет ещё не доставленный ответ ядра. */
  clearHover() {
    this.hoverEpoch = (this.hoverEpoch || 0) + 1;
    this.root.querySelector('.chart-hover')?.remove(); this.app.popups.hide('dayCard');
  }

  /** Применяет новую сцену только к соответствующей ревизии и размеру. */
  async update(model) {
    const generation = this.app.transport.generation;
    this.generation = generation;
    this.clearHover();
    this.model = model;
    const area = this.root.hidden ? document.getElementById('center') : this.root;
    const epoch = ++this.epoch;
    const response = await this.app.transport.query({type: 'chartScene', rev: model.revision, w: area.clientWidth, h: area.clientHeight});
    if (!this.app.transport.current(generation) || epoch !== this.epoch) return;
    if (response.stale) { this.app.resync(); return; }
    const scene = response.result; if (!scene || !scene.primitives) return;
    const svg = svgNode('svg', {viewBox: `0 0 ${scene.width} ${scene.height}`});
    const drawing = svgNode('g');
    for (const item of scene.primitives) {
      const node = primitive(item); node.dataset.kind = item.kind; drawing.append(node);
    }
    svg.append(drawing);
    this.root.replaceChildren(svg);
    let x = 12;
    for (const item of scene.legend) {
      const group = identify(svgNode('g', {transform: `translate(${x} 18)`}), item.id);
      group.classList.add('chart-legend'); group.dataset.tooltip = item.tooltip;
      if (item.swatch === 'DOT') group.append(svgNode('circle', {cx: 4, cy: -4, r: 3.5, fill: color(item.color)}));
      else if (item.swatch === 'BOX') group.append(svgNode('rect', {x: 0, y: -8, width: 12, height: 8, fill: color(item.color)}));
      else if (item.swatch !== 'NONE') group.append(svgNode('line', {x1: 0, y1: -4, x2: 14, y2: -4, stroke: color(item.color), 'stroke-width': 2, 'stroke-dasharray': item.swatch === 'DASH' ? '4 4' : ''}));
      const text = svgNode('text', {x: item.swatch === 'NONE' ? 0 : 18, 'font-size': 12, fill: color('TEXT_MUTED')}, item.text);
      group.append(text); svg.append(group); x += text.getComputedTextLength(); x += 38;
    }
    if (scene.emptyText) svg.append(svgNode('text', {x: scene.width / 2, y: scene.height / 2, 'text-anchor': 'middle', fill: color(scene.emptyColor), class: 'chart-empty'}, scene.emptyText));
    this.scene = scene;
  }

  /** Отображает координаты наведения, вычисленные ядром. */
  async hover(event) {
    if (!this.model || this.generation !== this.app.transport.generation) return;
    const generation = this.app.transport.generation; const sceneEpoch = this.epoch;
    const epoch = this.hoverEpoch = (this.hoverEpoch || 0) + 1;
    const p = this.position(event);
    const response = await this.app.transport.query({type: 'chartHover', rev: this.model.revision, x: p.x, y: p.y, w: this.root.clientWidth, h: this.root.clientHeight});
    if (!this.app.transport.current(generation) || sceneEpoch !== this.epoch || epoch !== this.hoverEpoch || !this.scene) return;
    this.root.querySelector('.chart-hover')?.remove();
    const hover = response.result;
    if (!hover) { this.app.popups.hide('dayCard'); return; }
    const group = svgNode('g', {class: 'chart-hover'}); const plot = this.scene.plot;
    group.append(svgNode('line', {x1: hover.lineX, x2: hover.lineX, y1: plot.plotY, y2: plot.plotY + plot.plotHeight, stroke: color('TEXT_MUTED'), 'stroke-dasharray': '3 3'}), svgNode('circle', {cx: hover.dot.x, cy: hover.dot.y, r: 4.5, fill: color('ACCENT')}));
    this.root.querySelector('svg')?.append(group);
    const rect = this.root.getBoundingClientRect();
    this.app.popups.dayCard(hover.card, rect.x + hover.cardX, rect.y + hover.cardY);
  }
}
