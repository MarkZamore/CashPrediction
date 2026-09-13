package ru.cashprediction.fx.popup;

import javafx.beans.InvalidationListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Skin;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import ru.cashprediction.core.forecast.DailyPoint;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.util.DateFormats;

import java.util.List;

/**
 * Внешний вид {@link SparklinePopupControl}: рамка с заголовком, линия баланса и подписи минимума/максимума.
 *
 * <p>Отделён от самого всплывающего окна, как принято для элементов управления JavaFX: окно хранит данные,
 * скин их рисует. Линия строится из {@link Polyline} в прямоугольнике 240×60; горизонтальная линия нуля
 * рисуется, только если баланс уходит в минус.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
// JavaFX: Skin<PopupControl> → Swing: paintComponent панели SwingPopupControl → Web: SVG внутри .popover
final class SparklineSkin implements Skin<SparklinePopupControl> {

    private static final double WIDTH = 240;
    private static final double HEIGHT = 60;

    private final SparklinePopupControl control;
    private final VBox root = new VBox(4);
    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final Pane plot = new Pane();
    private final Label min = new Label();
    private final Label max = new Label();
    private final InvalidationListener rebuild = o -> rebuild();

    SparklineSkin(SparklinePopupControl control) {
        this.control = control;
        title.setStyle("-fx-font-weight: bold;");
        subtitle.setStyle("-fx-text-fill: #57606a; -fx-font-size: 11px;");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(WIDTH);
        plot.setPrefSize(WIDTH, HEIGHT);
        plot.setMinSize(WIDTH, HEIGHT);
        plot.setMaxSize(WIDTH, HEIGHT);
        min.setStyle("-fx-font-size: 10px; -fx-text-fill: #57606a;");
        max.setStyle("-fx-font-size: 10px; -fx-text-fill: #57606a;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox limits = new HBox(min, spacer, max);
        limits.setMaxWidth(WIDTH);
        root.getChildren().addAll(title, subtitle, plot, limits);
        root.setPadding(new Insets(8));
        root.setStyle("-fx-background-color: #ffffff; -fx-border-color: #8c959f; -fx-border-radius: 4;"
                + " -fx-background-radius: 4; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 8, 0, 0, 2);");
        control.dataProperty().addListener(rebuild);
        rebuild();
    }

    /** {@inheritDoc} */
    @Override
    public SparklinePopupControl getSkinnable() {
        return control;
    }

    /** {@inheritDoc} */
    @Override
    public Node getNode() {
        return root;
    }

    /** {@inheritDoc} Отписывается от данных окна. */
    @Override
    public void dispose() {
        control.dataProperty().removeListener(rebuild);
    }

    private void rebuild() {
        SparklinePopupControl.Data data = control.getData();
        title.setText(data.title());
        subtitle.setText(data.subtitle());
        plot.getChildren().clear();
        List<DailyPoint> points = data.points();
        if (points.size() < 2) {
            min.setText("");
            max.setText(points.isEmpty() ? "нет данных" : points.getFirst().balance().format(data.currency()));
            return;
        }
        long lo = Long.MAX_VALUE;
        long hi = Long.MIN_VALUE;
        for (DailyPoint p : points) {
            lo = Math.min(lo, p.balance().minor());
            hi = Math.max(hi, p.balance().minor());
        }
        // Ровный баланс: делаем окно чуть шире, иначе деление на ноль.
        double span = Math.max(1, hi - lo);
        long first = points.getFirst().date().toEpochDay();
        double days = Math.max(1, points.getLast().date().toEpochDay() - first);
        Polyline line = new Polyline();
        for (DailyPoint p : points) {
            double x = (p.date().toEpochDay() - first) / days * WIDTH;
            double y = HEIGHT - (p.balance().minor() - lo) / span * HEIGHT;
            line.getPoints().addAll(x, y);
        }
        line.setStroke(Color.web("#1f6feb"));
        line.setStrokeWidth(1.5);
        if (lo < 0 && hi > 0) {
            // Линия нуля: видно, где баланс уходит в минус.
            double zeroY = HEIGHT - (0 - lo) / span * HEIGHT;
            Line zero = new Line(0, zeroY, WIDTH, zeroY);
            zero.setStroke(Color.web("#b3261e"));
            zero.getStrokeDashArray().addAll(3.0, 3.0);
            plot.getChildren().add(zero);
        }
        plot.getChildren().add(line);
        min.setText("мин. " + Money.ofMinor(lo).format(data.currency()));
        max.setText("макс. " + Money.ofMinor(hi).format(data.currency()) + " · до " + DateFormats.ru(points.getLast().date()));
    }
}
