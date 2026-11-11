package dev.frostbite.api;

/**
 * Mirage — Frostbite's render API.
 *
 * <p><b>2D</b> is plain Android drawing on a transparent view in the game's <em>own</em> window, so
 * it needs no overlay permission. Draw rounded rectangles, strokes, lines, circles and text in
 * screen pixels, with full control of color and stroke. Typically a mod rebuilds the frame each
 * tick from a hook: {@link #clear()} then issue draw calls; the overlay shows the latest frame.
 *
 * <p>Colors are packed ARGB {@code int}s ({@code 0xAARRGGBB}); use {@link #rgba}/{@link #rgb}.
 *
 * <pre>{@code
 * Mirage.clear();
 * Mirage.roundRect(40, 40, 260, 120, 18, Mirage.rgba(20, 22, 24, 220));   // panel
 * Mirage.strokeRect(40, 40, 260, 120, 18, 2, Mirage.rgb(213, 203, 188));  // outline
 * Mirage.text(60, 80, "SPEED " + speed, 28, Mirage.rgb(243, 243, 239));
 * }</pre>
 *
 * <p>(3D meshes are a separate part of Mirage, built on Unity's renderer.)
 */
public final class Mirage {
    /** Implemented by the runtime; backs the facade with an Android overlay view. */
    public interface Backend {
        void clear();
        void fillRect(float x, float y, float w, float h, float radius, int color);
        void strokeRect(float x, float y, float w, float h, float radius, float width, int color);
        void line(float x1, float y1, float x2, float y2, float width, int color);
        void fillCircle(float cx, float cy, float r, int color);
        void strokeCircle(float cx, float cy, float r, float width, int color);
        void text(float x, float y, String s, float size, int color);
        int width();
        int height();
        void visible(boolean visible);
    }

    private static volatile Backend backend;
    private Mirage() {}

    /** Installed once by the runtime after the overlay attaches. */
    public static void install(Backend installed) { backend = installed; }

    /** True when the 2D overlay is available (an Android window was attached). */
    public static boolean available() { return backend != null; }

    private static Backend backend() {
        Backend b = backend;
        if (b == null) throw new IllegalStateException("Mirage 2D overlay is not available yet");
        return b;
    }

    /** Begin a new frame — clears everything drawn so far. */
    public static void clear() { backend().clear(); }

    /** Filled rectangle. */
    public static void rect(float x, float y, float w, float h, int color) {
        backend().fillRect(x, y, w, h, 0, color);
    }

    /** Filled rounded rectangle ({@code radius} in pixels). */
    public static void roundRect(float x, float y, float w, float h, float radius, int color) {
        backend().fillRect(x, y, w, h, radius, color);
    }

    /** Rectangle outline, optionally rounded. */
    public static void strokeRect(float x, float y, float w, float h, float radius, float width, int color) {
        backend().strokeRect(x, y, w, h, radius, width, color);
    }

    public static void line(float x1, float y1, float x2, float y2, float width, int color) {
        backend().line(x1, y1, x2, y2, width, color);
    }

    public static void fillCircle(float cx, float cy, float r, int color) {
        backend().fillCircle(cx, cy, r, color);
    }

    public static void strokeCircle(float cx, float cy, float r, float width, int color) {
        backend().strokeCircle(cx, cy, r, width, color);
    }

    /** Text with its baseline-left at (x, y). */
    public static void text(float x, float y, String s, float size, int color) {
        backend().text(x, y, s, size, color);
    }

    /** Overlay width in pixels (the game window). */
    public static int width() { return backend().width(); }
    /** Overlay height in pixels. */
    public static int height() { return backend().height(); }

    /** Show or hide the whole overlay. */
    public static void show() { backend().visible(true); }
    public static void hide() { backend().visible(false); }

    /** Pack an ARGB color. */
    public static int rgba(int r, int g, int b, int a) {
        return ((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
    }
    public static int rgb(int r, int g, int b) { return rgba(r, g, b, 255); }
}
