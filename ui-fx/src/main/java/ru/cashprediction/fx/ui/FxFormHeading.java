package ru.cashprediction.fx.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import ru.cashprediction.core.ui.token.ColorToken;

/** Шапка формы измеряется по PNG и перенесённому тексту, без отступа прежнего шрифтового глифа. */
final class FxFormHeading extends HBox {
    /** Внешние отступы принадлежат панели формы; сама шапка добавляет только разделитель §6.0. */
    FxFormHeading(Region glyph, Region header) {
        super(10, glyph, header);
        glyph.setPadding(Insets.EMPTY);
        setPadding(Insets.EMPTY);
        setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(header, Priority.ALWAYS);
        header.setMinWidth(0);
        setBorder(new Border(new BorderStroke(Color.web(ColorToken.BORDER.hex()),
                BorderStrokeStyle.SOLID, CornerRadii.EMPTY, new BorderWidths(0, 0, 1, 0))));
    }
}
