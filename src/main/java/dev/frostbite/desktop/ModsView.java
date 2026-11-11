package dev.frostbite.desktop;

import dev.frostbite.host.ModApkPatcher;
import dev.frostbite.host.ModApkPatcher.ModInfo;
import dev.frostbite.host.ModApkPatcher.ModState;
import dev.frostbite.host.ModArchive;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Step 2: add, remove, reorder, toggle, and inspect the mods inside a Frostbite APK. */
final class ModsView {
    private final FrostbiteApp app;
    private final StackPane root = new StackPane();
    private final TextField apkField = new TextField();
    private final VBox rowsBox = new VBox();
    private final Label status = Ui.label("", "status-muted");
    private final Button buildBtn = new Button("Build modded APK");
    private final List<Row> rows = new ArrayList<>();
    private Path apk;
    private boolean busy;

    ModsView(FrostbiteApp app) {
        this.app = app;
        Label title = Ui.label("Mods", "page-title");
        Label sub = Ui.label("Add, remove, reorder, and toggle the mods inside a Frostbite APK.", "page-sub");
        apkField.getStyleClass().add("text-field");
        apkField.setEditable(false);
        apkField.setPromptText("No Frostbite APK chosen");
        HBox.setHgrow(apkField, Priority.ALWAYS);
        Button change = new Button("Choose APK");
        change.getStyleClass().add("choose-btn");
        change.setOnAction(e -> chooseApk());
        HBox apkRow = new HBox(10, apkField, change);
        HBox headRow = new HBox(new VBox(8, title, sub), Ui.grow());
        headRow.setAlignment(Pos.BOTTOM_LEFT);

        VBox table = new VBox(tableHeader(), scroll(rowsBox));
        table.getStyleClass().add("mods-table");
        VBox.setVgrow(table, Priority.ALWAYS);

        Button add = new Button("Add mods");
        add.getStyleClass().add("choose-btn");
        add.setGraphic(Ui.icon("M8 3 V13 M3 8 H13", 14, Ui.HIGHLIGHT, 1.7, 16));
        add.setOnAction(e -> addMods());
        buildBtn.getStyleClass().add("primary-btn");
        buildBtn.setGraphic(Ui.icon("M3 8 H12 M8 4 l4 4 -4 4", 15, Ui.INK, 1.7, 16));
        buildBtn.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        buildBtn.setOnAction(e -> build());
        HBox actions = new HBox(12, add, status, Ui.grow(), buildBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox page = new VBox(22, headRow, apkRow, table, actions);
        page.setPadding(new Insets(30, 36, 30, 36));
        root.getChildren().add(page);
        renderRows();
        refresh();
    }

    Node node() { return root; }

    void onShown() { if (apk != null && rows.isEmpty()) loadRows(); }

    void useApk(Path built) {
        apk = built;
        apkField.setText(built.toAbsolutePath().toString());
        loadRows();
    }

    // ---- data ----
    private void loadRows() {
        if (apk == null) return;
        status.getStyleClass().setAll("status-muted");
        status.setText("Reading mods…");
        Task<List<ModInfo>> task = new Task<>() {
            @Override protected List<ModInfo> call() throws Exception { return ModApkPatcher.inspect(apk); }
        };
        task.setOnSucceeded(e -> {
            rows.clear();
            for (ModInfo info : task.getValue()) rows.add(Row.existing(info));
            status.setText("");
            renderRows();
            refresh();
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            status.getStyleClass().setAll("status-err");
            status.setText("Could not read APK: " + (ex == null ? "" : ex.getMessage()));
        });
        run(task, "inspect");
    }

    private void chooseApk() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Frostbite game APK");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Android APKs", "*.apk"));
        var file = fc.showOpenDialog(root.getScene().getWindow());
        if (file == null) return;
        useApk(file.toPath());
    }

    private void addMods() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Add .frostbite mods");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Frostbite mods", "*.frostbite"));
        var files = fc.showOpenMultipleDialog(root.getScene().getWindow());
        if (files == null) return;
        for (var f : files) {
            try {
                ModArchive archive = ModArchive.fromFile(f.toPath());
                rows.add(Row.added(f.toPath(), archive.id(), archive.fileName()));
            } catch (Exception ex) {
                status.getStyleClass().setAll("status-err");
                status.setText("Bad mod " + f.getName() + ": " + ex.getMessage());
            }
        }
        renderRows();
        refresh();
    }

    // ---- build ----
    private void build() {
        if (busy || apk == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Save modded APK");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Android APKs", "*.apk"));
        fc.setInitialFileName("frostbite-mods.apk");
        var out = fc.showSaveDialog(root.getScene().getWindow());
        if (out == null) return;
        Path output = out.toPath();

        List<ModState> states = new ArrayList<>();
        List<Path> addFiles = new ArrayList<>();
        for (Row r : rows) {
            if (r.addedFile != null) addFiles.add(r.addedFile);
            else states.add(new ModState(r.fileName, r.enabled));
        }
        setBusy(true);
        status.getStyleClass().setAll("status-muted");
        status.setText("Building…");
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                ModApkPatcher.applyState(apk, states, addFiles, output, app.keystore(),
                        BundledKey.ALIAS, BundledKey.PASSWORD, BundledKey.PASSWORD, this::updateMessage);
                return null;
            }
        };
        task.messageProperty().addListener((o, a, m) -> { if (!m.isBlank()) status.setText(m); });
        task.setOnSucceeded(e -> {
            setBusy(false);
            status.getStyleClass().setAll("status-ok");
            status.setText("Saved " + output.getFileName());
            useApk(output);   // reload from the freshly built APK
        });
        task.setOnFailed(e -> {
            setBusy(false);
            status.getStyleClass().setAll("status-err");
            Throwable ex = task.getException();
            status.setText("Build failed: " + (ex == null ? "" : ex.getMessage()));
        });
        run(task, "mods-build");
    }

    // ---- rows UI ----
    private Node tableHeader() {
        HBox h = new HBox(14,
                fixed(16, ""), fixed(40, "On"), grow("Mod"),
                fixed(74, "Version"), fixed(150, "ABIs"), fixedRight(60, "Hooks"), fixed(70, ""));
        h.getStyleClass().add("table-head");
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private void renderRows() {
        rowsBox.getChildren().clear();
        if (rows.isEmpty()) {
            Label empty = Ui.label(apk == null ? "Choose a Frostbite APK to see its mods."
                    : "No mods in this APK yet. Add one below.", "helper");
            empty.setPadding(new Insets(28, 20, 28, 20));
            rowsBox.getChildren().add(empty);
            return;
        }
        for (int i = 0; i < rows.size(); i++) rowsBox.getChildren().add(rowNode(rows.get(i), i));
    }

    private Node rowNode(Row r, int index) {
        // reorder (existing rows only)
        Button up = iconBtn("M3 7 L6 4 L9 7", "Move up");
        Button down = iconBtn("M3 4 L6 7 L9 4", "Move down");
        up.setDisable(r.addedFile != null || index == 0 || hasAddedBefore(index));
        down.setDisable(r.addedFile != null || index == lastExistingIndex() || index >= lastExistingIndex());
        up.setOnAction(e -> move(index, -1));
        down.setOnAction(e -> move(index, 1));
        VBox reorder = new VBox(2, up, down);
        reorder.setMinWidth(16);

        Node onCell;
        if (r.addedFile != null) {
            onCell = Ui.label("new", "status-ok");
        } else {
            Ui.ToggleSwitch toggle = new Ui.ToggleSwitch(r.enabled);
            toggle.onProperty().addListener((o, a, b) -> r.enabled = b);
            onCell = toggle;
        }
        HBox onBox = new HBox(onCell);
        onBox.setMinWidth(40);
        onBox.setAlignment(Pos.CENTER_LEFT);

        Label id = Ui.label(r.id, "mod-id");
        Label desc = Ui.label(r.addedFile != null ? "pending — will be added" : r.fileName, "mod-desc");
        VBox name = new VBox(2, id, desc);
        HBox.setHgrow(name, Priority.ALWAYS);
        name.setMinWidth(0);

        Label version = Ui.label(r.version, "mod-meta");
        version.setMinWidth(74);
        String abiText = r.abis.isEmpty() ? "—" : String.join(" · ",
                r.abis.stream().map(a -> a.replace("armeabi-", "")).toList());
        Label abis = Ui.label(abiText, "mod-meta");
        abis.setMinWidth(150);
        abis.setPrefWidth(150);
        Label hooks = Ui.label(r.hookCount >= 0 ? String.valueOf(r.hookCount) : "—", "mod-meta");
        hooks.setMinWidth(60);
        hooks.setAlignment(Pos.CENTER_RIGHT);

        Button inspect = iconBtn2("M5 11 L11 5 M6 5 H11 V10", "Inspect " + r.id);
        inspect.setDisable(r.addedFile != null);
        inspect.setOnAction(e -> openInspector(r));
        Button remove = iconBtn2("M3 3 L9 9 M9 3 L3 9", "Remove " + r.id);
        remove.setOnAction(e -> { rows.remove(r); renderRows(); refresh(); });
        HBox tools = new HBox(6, inspect, remove);
        tools.setMinWidth(70);

        HBox row = new HBox(14, reorder, onBox, name, version, abis, hooks, tools);
        row.getStyleClass().add("mod-row");
        row.setAlignment(Pos.CENTER_LEFT);
        if (r.addedFile == null && !r.enabled) row.setOpacity(0.5);
        return row;
    }

    private boolean hasAddedBefore(int index) { return false; }   // added rows always after existing
    private int lastExistingIndex() {
        int last = -1;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).addedFile == null) last = i;
        return last;
    }
    private void move(int index, int delta) {
        int j = index + delta;
        if (j < 0 || j > lastExistingIndex() || rows.get(j).addedFile != null) return;
        Row r = rows.remove(index);
        rows.add(j, r);
        renderRows();
    }

    // ---- inspector sheet ----
    private void openInspector(Row r) {
        Region scrim = new Region();
        scrim.getStyleClass().add("scrim");
        scrim.setOnMouseClicked(e -> root.getChildren().remove(root.getChildren().size() - 1));

        VBox header = new VBox(6, Ui.label(r.id, "mod-id"), Ui.label(r.fileName, "mod-desc"));
        Button close = iconBtn2("M2 2 L10 10 M10 2 L2 10", "Close");
        close.setOnAction(e -> root.getChildren().remove(root.getChildren().size() - 1));
        HBox headRow = new HBox(header, Ui.grow(), close);

        HBox meta = new HBox(10,
                metaCell("Version", r.version), metaCell("Hooks", String.valueOf(r.hookCount)),
                metaCell("Enabled", r.enabled ? "yes" : "no"));
        HBox abis = new HBox(6);
        for (String abi : r.abis) abis.getChildren().add(Ui.label(abi, "abi-chip"));
        VBox abiBox = new VBox(6, Ui.label("NATIVE ABIS", "helper"), abis);

        VBox sheetBody = new VBox(20, meta, abiBox);
        sheetBody.setPadding(new Insets(20, 24, 24, 24));

        VBox sheet = new VBox(16,
                withPad(headRow, new Insets(22, 24, 18, 24)), sheetBody);
        sheet.getStyleClass().add("sheet");
        sheet.setMaxWidth(452);
        sheet.setMinWidth(452);
        StackPane.setAlignment(sheet, Pos.CENTER_RIGHT);

        StackPane overlay = new StackPane(scrim, sheet);
        root.getChildren().add(overlay);
        sheet.setTranslateX(452);
        javafx.animation.TranslateTransition slide = new javafx.animation.TranslateTransition(javafx.util.Duration.millis(260), sheet);
        slide.setToX(0);
        slide.setInterpolator(javafx.animation.Interpolator.EASE_OUT);
        slide.play();
    }

    private Node metaCell(String k, String v) {
        return new VBox(4, Ui.label(k.toUpperCase(), "helper"), Ui.label(v, "mod-meta"));
    }

    // ---- helpers ----
    private void setBusy(boolean b) { busy = b; refresh(); }
    private void refresh() {
        boolean canBuild = !busy && apk != null && !rows.isEmpty();
        buildBtn.setDisable(!canBuild);
    }

    private ScrollPane scroll(Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(sp, Priority.ALWAYS);
        return sp;
    }

    private static Label fixed(double w, String text) { Label l = Ui.label(text); l.setMinWidth(w); l.setPrefWidth(w); return l; }
    private static Label fixedRight(double w, String text) { Label l = fixed(w, text); l.setAlignment(Pos.CENTER_RIGHT); return l; }
    private static Label grow(String text) { Label l = Ui.label(text); HBox.setHgrow(l, Priority.ALWAYS); l.setMaxWidth(Double.MAX_VALUE); return l; }

    private Button iconBtn(String path, String label) {
        Button b = new Button();
        b.setGraphic(Ui.icon(path, 12, Ui.MUTED, 1.4, 12));
        b.getStyleClass().add("row-icon-btn");
        b.setMinSize(22, 16);
        b.setPrefSize(22, 16);
        b.setAccessibleText(label);
        return b;
    }
    private Button iconBtn2(String path, String label) {
        Button b = new Button();
        b.setGraphic(Ui.icon(path, 13, Ui.MUTED, 1.5, 16));
        b.getStyleClass().add("row-icon-btn");
        b.setAccessibleText(label);
        return b;
    }
    private static Node withPad(Node n, Insets pad) { VBox box = new VBox(n); box.setPadding(pad); box.setStyle("-fx-border-color: transparent transparent #373A3C transparent; -fx-border-width: 0 0 1 0;"); return box; }

    private void run(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }

    /** One mod row: either an existing embedded mod or a pending file to add. */
    private static final class Row {
        String fileName, id, version;
        boolean enabled;
        int hookCount;
        List<String> abis;
        Path addedFile;   // non-null for a pending addition

        static Row existing(ModInfo info) {
            Row r = new Row();
            r.fileName = info.fileName(); r.id = info.id(); r.version = info.version();
            r.enabled = info.enabled(); r.hookCount = info.hookCount(); r.abis = info.abis();
            return r;
        }
        static Row added(Path file, String id, String fileName) {
            Row r = new Row();
            r.addedFile = file; r.id = id; r.fileName = fileName;
            r.version = "—"; r.enabled = true; r.hookCount = -1; r.abis = List.of();
            return r;
        }
    }
}
