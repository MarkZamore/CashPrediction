package ru.cashprediction.fx.dialog;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Сетка формы «подпись — поле» для диалогов: одинаковые отступы, подписи справа, поля растягиваются.
 *
 * <p>Умеет скрывать строку целиком (подпись и поле): редактор правила показывает только поля, нужные
 * выбранному виду повтора. Скрытая строка не занимает места ({@code managed=false}).</p>
 *
 * <p>Только FX Application Thread.</p>
 */
public final class FormGrid extends GridPane {

    private final Map<Node, Label> labels = new IdentityHashMap<>();
    private int nextRow;

    /** Создаёт пустую форму. */
    public FormGrid() {
        setHgap(10);
        setVgap(8);
        setPadding(new Insets(4, 0, 4, 0));
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setHalignment(HPos.RIGHT);
        labelColumn.setMinWidth(150);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        fieldColumn.setFillWidth(true);
        getColumnConstraints().addAll(labelColumn, fieldColumn);
    }

    /**
     * Добавляет строку «подпись — поле».
     *
     * @param label подпись (без двоеточия)
     * @param field поле
     * @return эта форма (для цепочки вызовов)
     */
    public FormGrid row(String label, Node field) {
        Label caption = new Label(label + ":");
        caption.setLabelFor(field);
        caption.setWrapText(true);
        add(caption, 0, nextRow);
        add(field, 1, nextRow);
        labels.put(field, caption);
        nextRow++;
        return this;
    }

    /**
     * Добавляет узел на всю ширину формы (флажок, предпросмотр, пояснение).
     *
     * @param node узел
     * @return эта форма
     */
    public FormGrid wide(Node node) {
        add(node, 0, nextRow, 2, 1);
        nextRow++;
        return this;
    }

    /**
     * Добавляет заголовок раздела с разделительной линией.
     *
     * @param title текст заголовка
     * @return эта форма
     */
    public FormGrid section(String title) {
        Label caption = new Label(title);
        caption.setStyle("-fx-font-weight: bold; -fx-padding: 6 0 0 0;");
        add(new Separator(), 0, nextRow++, 2, 1);
        add(caption, 0, nextRow++, 2, 1);
        return this;
    }

    /**
     * Показывает или скрывает строку поля вместе с подписью.
     *
     * @param field   поле, добавленное через {@link #row(String, Node)} или {@link #wide(Node)}
     * @param visible показывать ли
     */
    public void setRowVisible(Node field, boolean visible) {
        field.setVisible(visible);
        field.setManaged(visible);
        Label caption = labels.get(field);
        if (caption != null) {
            caption.setVisible(visible);
            caption.setManaged(visible);
        }
    }

    /**
     * Меняет подпись строки.
     *
     * @param field поле
     * @param label новая подпись (без двоеточия)
     */
    public void setLabel(Node field, String label) {
        Label caption = labels.get(field);
        if (caption != null) {
            caption.setText(label + ":");
        }
    }
}
