package dev.frostbite.desktop;

import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import java.nio.file.Path;

/** Frostbite Desktop — a dark-workshop JavaFX app to install Frostbite and manage mods. */
public final class FrostbiteApp extends Application {
    private final StackPane content = new StackPane();
    private InstallView installView;
    private ModsView modsView;
    private Button navInstall, navMods;
    private double dragX, dragY;

    @Override
    public void start(Stage stage) {
        stage.initStyle(StageStyle.TRANSPARENT);

        installView = new InstallView(this);
        modsView = new ModsView(this);
        content.getChildren().addAll(modsView.node(), installView.node());
        modsView.node().setVisible(false);

        BorderPane body = new BorderPane();
        body.setLeft(sidebar());
        body.setCenter(content);
        BorderPane.setMargin(content, Insets.EMPTY);

        VBox window = new VBox(titleBar(stage), body);
        VBox.setVgrow(body, javafx.scene.layout.Priority.ALWAYS);
        window.getStyleClass().add("window");

        StackPane rootWrap = new StackPane(window);
        rootWrap.getStyleClass().add("root");
        rootWrap.setPadding(new Insets(0));

        Scene scene = new Scene(rootWrap, 1040, 720);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());

        stage.setScene(scene);
        stage.setMinWidth(820);
        stage.setMinHeight(580);
        stage.setTitle("Frostbite");
        select("install");
        stage.show();
    }

    // ---- window chrome ----
    private Node titleBar(Stage stage) {
        HBox left = new HBox(11, Ui.logo(18), styled(new Label("FROSTBITE"), "wordmark"));
        left.setAlignment(Pos.CENTER_LEFT);

        Button min = winButton("M1 8 H10", 11, false);
        min.setOnAction(e -> stage.setIconified(true));
        Button max = winButton(null, 11, false);
        max.setGraphic(Ui.icon("M1.3 1.3 H9.7 V9.7 H1.3 Z", 11, Ui.MUTED, 1.3, 11));
        max.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));
        Button close = winButton("M1.5 1.5 L9.5 9.5 M9.5 1.5 L1.5 9.5", 11, true);
        close.setOnAction(e -> Platform.exit());

        HBox controls = new HBox(6, min, max, close);
        controls.setAlignment(Pos.CENTER_RIGHT);

        HBox bar = new HBox(left, Ui.grow(), controls);
        bar.getStyleClass().add("titlebar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMinHeight(46);
        bar.setPrefHeight(46);
        bar.setOnMousePressed(e -> { dragX = e.getScreenX() - stage.getX(); dragY = e.getScreenY() - stage.getY(); });
        bar.setOnMouseDragged(e -> {
            if (!stage.isMaximized()) { stage.setX(e.getScreenX() - dragX); stage.setY(e.getScreenY() - dragY); }
        });
        return bar;
    }

    private Button winButton(String path, double vb, boolean isClose) {
        Button b = new Button();
        b.getStyleClass().add("winbtn");
        if (isClose) b.getStyleClass().add("winbtn-close");
        if (path != null) b.setGraphic(Ui.icon(path, vb, Ui.MUTED, 1.3, vb));
        return b;
    }

    // ---- sidebar ----
    private Node sidebar() {
        HBox brand = new HBox(12, Ui.logo(30), new VBox(1,
                styled(new Label("Frostbite"), "brand-name"),
                styled(new Label("by Outcast"), "brand-sub")));
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setPadding(new Insets(4, 8, 22, 8));

        navInstall = navItem("01", "Install", "install");
        navMods = navItem("02", "Mods", "mods");
        VBox nav = new VBox(4, navInstall, navMods);

        VBox rail = new VBox(brand, nav, Ui.grow());
        rail.getStyleClass().add("rail");
        rail.setMinWidth(224);
        rail.setPrefWidth(224);
        return rail;
    }

    private Button navItem(String num, String text, String target) {
        Label n = styled(new Label(num), "nav-num");
        Label t = styled(new Label(text), "nav-label");
        HBox box = new HBox(12, n, t);
        box.setAlignment(Pos.CENTER_LEFT);
        Button b = new Button();
        b.setGraphic(box);
        b.setMaxWidth(Double.MAX_VALUE);
        b.getStyleClass().add("nav-item");
        b.setOnAction(e -> select(target));
        b.getProperties().put("num", n);
        b.getProperties().put("label", t);
        return b;
    }

    // ---- navigation ----
    void select(String target) {
        boolean install = target.equals("install");
        styleNav(navInstall, install);
        styleNav(navMods, !install);
        Node show = install ? installView.node() : modsView.node();
        Node hide = install ? modsView.node() : installView.node();
        if (show.isVisible()) return;
        hide.setVisible(false);
        show.setOpacity(0);
        show.setVisible(true);
        FadeTransition fade = new FadeTransition(Duration.millis(260), show);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
        if (!install) modsView.onShown();
    }

    private void styleNav(Button nav, boolean active) {
        nav.getStyleClass().remove("nav-item-active");
        Label num = (Label) nav.getProperties().get("num");
        Label label = (Label) nav.getProperties().get("label");
        num.getStyleClass().remove("nav-num-active");
        label.getStyleClass().remove("nav-label-active");
        if (active) {
            nav.getStyleClass().add("nav-item-active");
            num.getStyleClass().add("nav-num-active");
            label.getStyleClass().add("nav-label-active");
        }
    }

    /** Called by the Install view after a successful build: jump to Mods on that APK. */
    void openMods(Path builtApk) {
        modsView.useApk(builtApk);
        select("mods");
    }

    Path keystore() throws java.io.IOException { return BundledKey.path(); }

    private static <T extends Region> T styled(T node, String style) {
        node.getStyleClass().add(style);
        return node;
    }
    private static Label styled(Label node, String style) {
        node.getStyleClass().add(style);
        return node;
    }

    public static void main(String[] args) { launch(args); }
}
