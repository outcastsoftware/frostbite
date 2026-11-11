package dev.frostbite.desktop;

import dev.frostbite.host.ApkInspector;
import dev.frostbite.host.FrostbiteInstaller;
import dev.frostbite.host.RuntimeProvider;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import java.nio.file.Path;

/** Step 1: patch a game APK with the Frostbite runtime. */
final class InstallView {
    private final FrostbiteApp app;
    private final StackPane root = new StackPane();
    private final TextField gameField = field("No APK chosen");
    private final TextField activityField = field("Choose an APK to detect it");
    private final TextField outputField = field("Choose where to save");
    private final HBox autoChip = autoChip();
    private final Label runtimeStatus = Ui.label("runtime " + RuntimeProvider.DEFAULT_TAG + " · from GitHub", "mod-id");
    private final ProgressBar runtimeBar = new ProgressBar(0);
    private final Button buildBtn = new Button("Build Frostbite APK");
    private final Label status = Ui.label("Aligned and signed (v1·v2·v3), then verified.", "status-muted");
    private boolean busy;

    InstallView(FrostbiteApp app) {
        this.app = app;
        activityField.setEditable(true);
        autoChip.setVisible(false);

        Label title = Ui.label("Install Frostbite", "page-title");
        Label sub = Ui.label("Add the Frostbite runtime to a Unity IL2CPP game APK.", "page-sub");
        VBox heading = new VBox(8, title, sub);

        VBox card = new VBox(18,
                fieldRow("Game APK", gameField, chooseBtn("Choose", this::chooseGame), null),
                activityRow(),
                runtimeRow(),
                fieldRow("Output APK", outputField, chooseBtn("Save as", this::chooseOutput), null));
        card.getStyleClass().add("card");

        Button build = buildBtn;
        build.getStyleClass().add("primary-btn");
        build.setGraphic(Ui.icon("M3 8 H12 M8 4 l4 4 -4 4", 15, Ui.INK, 1.7, 16));
        build.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        build.setOnAction(e -> build());
        HBox actions = new HBox(status, Ui.grow(), build);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox page = new VBox(26, heading, card, Ui.grow(), actions);
        page.setPadding(new Insets(34, 40, 30, 40));
        page.setFillWidth(true);
        root.getChildren().add(page);
        refresh();
    }

    Node node() { return root; }

    // ---- rows ----
    private Node activityRow() {
        Label label = Ui.label("Launch Activity", "field-label");
        HBox head = new HBox(10, label, autoChip);
        head.setAlignment(Pos.CENTER_LEFT);
        Label helper = Ui.label("Read from the APK manifest. Edit it if the game uses a different entry.", "helper");
        activityField.textProperty().addListener((o, a, b) -> refresh());
        VBox box = new VBox(8, head, activityField, helper);
        return box;
    }

    private Node runtimeRow() {
        Label label = Ui.label("Frostbite runtime", "field-label");
        runtimeBar.getStyleClass().add("progress-bar");
        runtimeBar.setMaxWidth(Double.MAX_VALUE);
        runtimeBar.setProgress(0);
        VBox info = new VBox(7, runtimeStatus, runtimeBar);
        HBox.setHgrow(info, Priority.ALWAYS);
        HBox inner = new HBox(14, Ui.icon("M12 3 v12 M7 11 l5 5 5-5 M4 20 h16", 18, Ui.HIGHLIGHT, 1.6, 24), info);
        inner.setAlignment(Pos.CENTER_LEFT);
        inner.setStyle("-fx-background-color:#25282A; -fx-background-radius:11; -fx-border-color:#373A3C; -fx-border-radius:11; -fx-padding:12 16;");
        Label helper = Ui.label("No download needed — the runtime is fetched and cached automatically.", "helper");
        return new VBox(8, label, inner, helper);
    }

    private Node fieldRow(String labelText, TextField field, Button action, String helper) {
        Label label = Ui.label(labelText, "field-label");
        HBox.setHgrow(field, Priority.ALWAYS);
        HBox row = new HBox(10, field, action);
        VBox box = new VBox(8, label, row);
        if (helper != null) box.getChildren().add(Ui.label(helper, "helper"));
        return box;
    }

    // ---- actions ----
    private void chooseGame() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select original game APK");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Android APKs", "*.apk"));
        var file = fc.showOpenDialog(root.getScene().getWindow());
        if (file == null) return;
        gameField.setText(file.getAbsolutePath());
        detectActivity(file.toPath());
        refresh();
    }

    private void chooseOutput() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Frostbite APK");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Android APKs", "*.apk"));
        fc.setInitialFileName("frostbite.apk");
        var file = fc.showSaveDialog(root.getScene().getWindow());
        if (file == null) return;
        outputField.setText(file.getAbsolutePath());
        refresh();
    }

    private void detectActivity(Path apk) {
        autoChip.setVisible(false);
        activityField.setText("Detecting…");
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception { return ApkInspector.detectLaunchActivity(apk); }
        };
        task.setOnSucceeded(e -> {
            String activity = task.getValue();
            if (activity != null && !activity.isBlank()) {
                activityField.setText(activity);
                showAutoChip();
            } else {
                activityField.setText("");
            }
            refresh();
        });
        task.setOnFailed(e -> { activityField.setText(""); refresh(); });
        Thread t = new Thread(task, "detect-activity");
        t.setDaemon(true);
        t.start();
    }

    private void showAutoChip() {
        autoChip.setVisible(true);
        autoChip.setScaleX(0.6);
        autoChip.setScaleY(0.6);
        ScaleTransition st = new ScaleTransition(Duration.millis(190), autoChip);
        st.setToX(1);
        st.setToY(1);
        st.play();
    }

    private void build() {
        if (busy) return;
        Path game = Path.of(gameField.getText().trim());
        Path output = Path.of(outputField.getText().trim());
        String activity = activityField.getText().trim();
        setBusy(true);
        status.getStyleClass().setAll("status-muted");
        status.setText("Preparing…");
        runtimeBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                Path runtime = RuntimeProvider.ensureRuntime(this::updateMessage);
                Platform.runLater(() -> runtimeBar.setProgress(1));
                FrostbiteInstaller.install(game, runtime, activity, output, app.keystore(),
                        BundledKey.ALIAS, BundledKey.PASSWORD, BundledKey.PASSWORD, this::updateMessage);
                return null;
            }
        };
        task.messageProperty().addListener((o, a, msg) -> { if (!msg.isBlank()) status.setText(msg); });
        task.setOnSucceeded(e -> {
            setBusy(false);
            status.getStyleClass().setAll("status-ok");
            status.setText("Frostbite APK ready");
            app.openMods(output);
        });
        task.setOnFailed(e -> {
            setBusy(false);
            runtimeBar.setProgress(0);
            status.getStyleClass().setAll("status-err");
            Throwable ex = task.getException();
            status.setText("Build failed: " + (ex == null ? "unknown" : ex.getMessage()));
        });
        Thread t = new Thread(task, "install");
        t.setDaemon(true);
        t.start();
    }

    private void setBusy(boolean b) { busy = b; refresh(); }

    private void refresh() {
        boolean ready = !busy && isReal(gameField) && !activityField.getText().isBlank()
                && !activityField.getText().equals("Detecting…") && isReal(outputField);
        buildBtn.setDisable(!ready);
    }

    private boolean isReal(TextField f) {
        String t = f.getText();
        return t != null && !t.isBlank() && !t.startsWith("No APK") && !t.startsWith("Choose");
    }

    // ---- small builders ----
    private static TextField field(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        f.setEditable(false);
        f.getStyleClass().add("text-field");
        HBox.setHgrow(f, Priority.ALWAYS);
        return f;
    }

    private Button chooseBtn(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("choose-btn");
        b.setOnAction(e -> action.run());
        return b;
    }

    private static HBox autoChip() {
        HBox box = new HBox(Ui.label("detected from the APK", "status-ok"));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }
}
