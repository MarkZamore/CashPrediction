/** @file Разработческие проверки геометрии S5 без браузера и сторонних пакетов. */
import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
import test from 'node:test';

const source = await readFile(new URL('../../../main/resources/web/app/paint-observation.js', import.meta.url), 'utf8');
const {resolveRadiusPairs, imageDestination} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));

test('radius pairs retain percentages and TL TR BL BR order', /** Проверяет независимые пары четырёх углов при разных процентах. */ () => {
  const values = resolveRadiusPairs(['10% 20%', '3px 4px', '5px 6px', '7px 8px'], 100, 40);
  assert.deepEqual(values.map(/** Читает разрешённую пару конкретного угла. */ r => [r.rx, r.ry]), [[10, 8], [3, 4], [5, 6], [7, 8]]);
  assert.equal(values[0].percentageX, true); assert.equal(values[0].percentageY, true);
  assert.equal(values[0].rawRy, 20); assert.equal(values[0].units, '%/%');
});

test('overlap uses one CSS factor for both axes and every corner', /** Проверяет общий коэффициент, ограниченный вертикальной парой углов. */ () => {
  const values = resolveRadiusPairs(['80px 30px', '80px 10px', '20px 30px', '20px 10px'], 100, 30);
  assert.deepEqual(values.map(/** Читает пару после общего уменьшения перекрытия. */ r => [r.rx, r.ry]), [[40, 15], [40, 5], [10, 15], [10, 5]]);
  assert.equal(values[0].rawRx, 80);
});

test('fractional content and off-center contain are not owner slot geometry', /** Проверяет фактическую область пикселей внутри дробной контентной рамки. */ () => {
  const result = imageDestination({x: 2.25, y: 1.5, width: 30.5, height: 20.25}, 10, 20, 'contain', '75% 25%');
  assert.deepEqual(result, {x: 17.53125, y: 1.5, width: 10.125, height: 20.25});
});

test('cover keeps uncropped destination instead of inventing a smaller draw', /** Проверяет выход cover за контентную область до отдельной фиксации crop. */ () => {
  assert.deepEqual(imageDestination({x: 1, y: 2, width: 20, height: 10}, 10, 20, 'cover', '50% 50%'),
    {x: 1, y: -13, width: 20, height: 40});
});

test('scale-down does not upscale natural pixels', /** Проверяет отсутствие увеличения небольшого источника и независимое позиционирование. */ () => {
  assert.deepEqual(imageDestination({x: 0, y: 0, width: 30, height: 20}, 8, 4, 'scale-down', 'right bottom'),
    {x: 22, y: 16, width: 8, height: 4});
});

test('unknown units and complex positions fail closed', /** Проверяет явный отказ от неразрешённых CSS-выражений. */ () => {
  assert.throws(/** Передаёт неизвестную единицу радиуса. */ () => resolveRadiusPairs(['1em', '0', '0', '0'], 40, 20));
  assert.throws(/** Передаёт невычисленное выражение радиуса. */ () => resolveRadiusPairs(['calc(1px + 2%)', '0', '0', '0'], 40, 20));
  assert.throws(/** Передаёт четырёхкомпонентное позиционирование изображения. */ () => imageDestination({x: 0, y: 0, width: 20, height: 20}, 8, 8, 'contain', 'right 2px bottom 1px'));
  assert.throws(/** Передаёт бесконечную геометрию изображения. */ () => imageDestination({x: 0, y: 0, width: Infinity, height: 20}, 8, 8, 'contain', '50% 50%'));
});
