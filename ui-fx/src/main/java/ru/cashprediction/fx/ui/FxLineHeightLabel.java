package ru.cashprediction.fx.ui;

import javafx.scene.control.Label;
import javafx.scene.control.SkinBase;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.Text;
import javafx.scene.text.TextBoundsType;

/** Подпись с общим межстрочным шагом; перенос и число строк измеряет настоящий JavaFX Text. */
final class FxLineHeightLabel extends Label {
    private final double lineHeight;

    /** Создаёт подпись, не задавая высоту всего окна или количество видимых строк. */
    FxLineHeightLabel(String text, double lineHeight) {
        super(text);
        this.lineHeight = lineHeight;
        setMinWidth(0); setMaxHeight(USE_PREF_SIZE); setWrapText(true); setSkin(new LineSkin(this));
    }

    /** Возвращает число строк реальной раскладки для ширины текстовой области. */
    int measuredLines(double width) { return ((LineSkin) getSkin()).measure(width); }

    /** Единственный Text рисует исходную подпись с настоящим переносом и общим шагом базовых линий. */
    private static final class LineSkin extends SkinBase<FxLineHeightLabel> {
        private final Text text = new Text();
        private final Text fontLine = new Text("Ag");

        private LineSkin(FxLineHeightLabel label) {
            super(label);
            text.getStyleClass().add("text"); text.setBoundsType(TextBoundsType.LOGICAL);
            text.textProperty().bind(label.textProperty()); text.fontProperty().bind(label.fontProperty());
            text.fillProperty().bind(label.textFillProperty()); fontLine.fontProperty().bind(label.fontProperty());
            getChildren().add(text);
            label.textProperty().addListener((o, previous, next) -> label.requestLayout());
            label.fontProperty().addListener((o, previous, next) -> label.requestLayout());
            label.alignmentProperty().addListener((o, previous, next) -> label.requestLayout());
            label.textAlignmentProperty().addListener((o, previous, next) -> label.requestLayout());
        }

        private int measure(double width) {
            double fontHeight = fontLine.getLayoutBounds().getHeight();
            // Промежуток изменяет именно шаг нарисованных строк, а свободный остаток распределяется вокруг текста.
            text.setLineSpacing(getSkinnable().lineHeight - fontHeight);
            text.setTextAlignment(switch (getSkinnable().getAlignment().getHpos()) {
                case RIGHT -> TextAlignment.RIGHT;
                case CENTER -> TextAlignment.CENTER;
                default -> getSkinnable().getTextAlignment();
            });
            text.setWrappingWidth(Math.max(1, width));
            if (text.getText().isEmpty()) return 0;
            return Math.max(1, (int) Math.round((text.getLayoutBounds().getHeight() + text.getLineSpacing())
                    / getSkinnable().lineHeight));
        }

        /** Минимальная ширина допускает настоящий перенос длинного слова. */
        @Override protected double computeMinWidth(double height, double top, double right, double bottom, double left) { return left + right; }

        /** Предпочтительная ширина определяется краской текста, а ограничение задаёт контейнер. */
        @Override protected double computePrefWidth(double height, double top, double right, double bottom, double left) {
            text.setWrappingWidth(0);
            return left + Math.ceil(text.getLayoutBounds().getWidth()) + right;
        }

        /** Каждый реально перенесённый ряд занимает общую высоту строки. */
        @Override protected double computePrefHeight(double width, double top, double right, double bottom, double left) {
            double available = width < 0 ? computePrefWidth(-1, 0, 0, 0, 0) : Math.max(1, width - left - right);
            return top + measure(available) * getSkinnable().lineHeight + bottom;
        }

        /** Располагает настоящие глифы внутри строковых боксов, не обрезая их и не создавая пустых подписей. */
        @Override protected void layoutChildren(double x, double y, double width, double height) {
            int lines = measure(width);
            double paintedHeight = text.getLayoutBounds().getHeight();
            double lineBoxesHeight = lines * getSkinnable().lineHeight;
            double free = Math.max(0, height - lineBoxesHeight);
            double offset = switch (getSkinnable().getAlignment().getVpos()) {
                case BOTTOM -> free;
                case CENTER -> free / 2;
                default -> 0;
            };
            text.relocate(x, y + offset + Math.max(0, (lineBoxesHeight - paintedHeight) / 2));
        }
    }
}
