package dev.frostbite.desktop;

import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

/** Small shared view helpers: the Outcast mark, stroke icons, and an animated toggle. */
final class Ui {
    static final Color TEXT = Color.web("#F3F3EF");
    static final Color MUTED = Color.web("#AEB2B0");
    static final Color HIGHLIGHT = Color.web("#D5CBBC");
    static final Color INK = Color.web("#0E0F10");
    private Ui() {}

    /** The Frostbite mark (viewBox 512): a hexagon shell with a six-arm frost crystal. */
    static Node logo(double size) {
        SVGPath hex = new SVGPath();
        hex.setContent("M256 44 L440 150 L440 362 L256 468 L72 362 L72 150 Z");
        hex.setFill(Color.TRANSPARENT);
        hex.setStroke(TEXT);
        hex.setStrokeWidth(16);
        hex.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        Group marks = new Group(hex);
        for (int i = 0; i < 6; i++) {
            Group arm = frostArm();
            arm.getTransforms().add(new javafx.scene.transform.Rotate(i * 60, 256, 256));
            marks.getChildren().add(arm);
        }
        marks.getChildren().add(new javafx.scene.shape.Circle(256, 256, 20, TEXT));
        double scale = size / 512.0;
        marks.setScaleX(scale);
        marks.setScaleY(scale);
        return new Group(marks);
    }

    private static Group frostArm() {
        return new Group(
                strokePath("M256 250 L256 92"), strokePath("M256 146 L222 114"),
                strokePath("M256 146 L290 114"), strokePath("M256 192 L230 168"),
                strokePath("M256 192 L282 168"));
    }

    private static SVGPath strokePath(String d) {
        SVGPath p = new SVGPath();
        p.setContent(d);
        p.setFill(Color.TRANSPARENT);
        p.setStroke(TEXT);
        p.setStrokeWidth(15);
        p.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        return p;
    }

    /** A stroke icon from 16x16 (or 24x24) path data. */
    static SVGPath icon(String path, double size, Color color, double width, double viewbox) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.setFill(Color.TRANSPARENT);
        p.setStroke(color);
        p.setStrokeWidth(width);
        p.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        p.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        double scale = size / viewbox;
        p.setScaleX(scale);
        p.setScaleY(scale);
        return p;
    }

    static Label label(String text, String... styles) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styles);
        return l;
    }

    static Region grow() {
        Region r = new Region();
        javafx.scene.layout.HBox.setHgrow(r, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.VBox.setVgrow(r, javafx.scene.layout.Priority.ALWAYS);
        return r;
    }

    /** An animated on/off switch backed by a BooleanProperty. */
    static final class ToggleSwitch extends StackPane {
        private final BooleanProperty on = new SimpleBooleanProperty();
        private final Region track = new Region();
        private final Region knob = new Region();

        ToggleSwitch(boolean initial) {
            on.set(initial);
            getStyleClass().add("toggle");
            setMinSize(42, 24);
            setPrefSize(42, 24);
            setMaxSize(42, 24);
            track.setPrefSize(42, 24);
            track.getStyleClass().add("toggle-track");
            knob.setPrefSize(18, 18);
            knob.setMaxSize(18, 18);
            knob.getStyleClass().add("toggle-knob");
            StackPane.setAlignment(knob, javafx.geometry.Pos.CENTER_LEFT);
            knob.setTranslateX(3);
            getChildren().addAll(track, knob);
            render(false);
            setOnMouseClicked(e -> { on.set(!on.get()); render(true); });
            on.addListener((o, a, b) -> render(true));
        }

        BooleanProperty onProperty() { return on; }
        boolean isOn() { return on.get(); }

        private void render(boolean animate) {
            boolean v = on.get();
            track.setStyle("-fx-background-color: " + (v ? "#D5CBBC" : "#373A3C") + ";");
            knob.setStyle("-fx-background-color: " + (v ? "#0E0F10" : "#AEB2B0") + ";");
            double target = v ? 21 : 3;
            if (animate) {
                TranslateTransition t = new TranslateTransition(Duration.millis(160), knob);
                t.setToX(target);
                t.setInterpolator(Interpolator.EASE_BOTH);
                t.play();
            } else {
                knob.setTranslateX(target);
            }
        }
    }
}
