package ru.cashprediction.core.ui.view.chart;

import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.FontToken;

/**
 * Примитив сцены графика (архитектура §3.4). Клиенты рисуют примитивы строго по порядку списка
 * {@link ChartScene#primitives()}: JavaFX {@code Canvas}/{@code GraphicsContext} → Swing {@code Graphics2D} → Web SVG;
 * PNG рисуется из той же сцены (FX Canvas, Swing и web-сервер Java2D).
 */
public sealed interface ChartPrimitive
        permits ChartPrimitive.Area, ChartPrimitive.Polyline, ChartPrimitive.Line, ChartPrimitive.Box,
        ChartPrimitive.Circle, ChartPrimitive.Label {

    /**
     * Залитая область между ломаной и горизонталью {@code baselineY} (заливка под линией баланса до нуля).
     *
     * @param points    вершины ломаной
     * @param baselineY горизонталь, до которой заливать
     * @param fill      цвет заливки
     * @param opacity   непрозрачность 0..1
     */
    record Area(List<ChartPoint> points, double baselineY, ColorToken fill, double opacity) implements ChartPrimitive {
        /** Проверяет поля и копирует список. */
        public Area {
            points = List.copyOf(Objects.requireNonNull(points, "points"));
            Objects.requireNonNull(fill, "fill");
        }
    }

    /**
     * Ломаная (ступенчатая линия баланса, до 1500 точек).
     *
     * @param points вершины по порядку
     * @param stroke линия
     */
    record Polyline(List<ChartPoint> points, Stroke stroke) implements ChartPrimitive {
        /** Проверяет поля и копирует список. */
        public Polyline {
            points = List.copyOf(Objects.requireNonNull(points, "points"));
            Objects.requireNonNull(stroke, "stroke");
        }
    }

    /**
     * Отрезок (сетка, ноль, подушка, цель, сегодня, оси).
     *
     * @param x1     начало x
     * @param y1     начало y
     * @param x2     конец x
     * @param y2     конец y
     * @param stroke линия
     */
    record Line(double x1, double y1, double x2, double y2, Stroke stroke) implements ChartPrimitive {
        /** Проверяет линию. */
        public Line {
            Objects.requireNonNull(stroke, "stroke");
        }
    }

    /**
     * Прямоугольник (столбец итога месяца).
     *
     * @param x       левый край
     * @param y       верхний край
     * @param width   ширина
     * @param height  высота
     * @param fill    цвет заливки
     * @param opacity непрозрачность 0..1
     */
    record Box(double x, double y, double width, double height, ColorToken fill, double opacity) implements ChartPrimitive {
        /** Проверяет цвет. */
        public Box {
            Objects.requireNonNull(fill, "fill");
        }
    }

    /**
     * Круг (маркер дня: r = 3,5, обводка белая 1 px).
     *
     * @param cx          центр x
     * @param cy          центр y
     * @param r           радиус
     * @param fill        заливка
     * @param stroke      цвет обводки или {@code null}
     * @param strokeWidth толщина обводки
     */
    record Circle(double cx, double cy, double r, ColorToken fill, ColorToken stroke, double strokeWidth)
            implements ChartPrimitive {
        /** Проверяет цвет. */
        public Circle {
            Objects.requireNonNull(fill, "fill");
        }
    }

    /**
     * Текст (подписи осей, «0», «подушка 50 000», «сегодня», «итог мес.»).
     *
     * @param x      точка привязки x
     * @param y      базовая линия
     * @param text   текст
     * @param anchor горизонтальная привязка
     * @param color  цвет
     * @param font   шрифт
     */
    record Label(double x, double y, String text, TextAnchor anchor, ColorToken color, FontToken font)
            implements ChartPrimitive {
        /** Проверяет поля. */
        public Label {
            text = Objects.requireNonNullElse(text, "");
            anchor = anchor == null ? TextAnchor.START : anchor;
            Objects.requireNonNull(color, "color");
            font = font == null ? FontToken.SMALL : font;
        }
    }
}
