package dev.frostbite.runtime;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;
import android.view.WindowManager;
import dev.frostbite.api.Mirage;
import java.util.ArrayList;
import java.util.List;

/**
 * Mirage's 2D backend: a transparent, non-touchable view added to the game's OWN window (so it
 * needs no overlay permission) and drawn with an Android {@link Canvas}. Mods issue draw calls
 * through {@link Mirage}; the view redraws every frame via {@link Choreographer}.
 */
final class MirageOverlay extends View implements Mirage.Backend {
    private static final String TAG = "Frostbite";
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    // Mods append to 'commands'; clear() swaps in a fresh list. onDraw renders a snapshot.
    private volatile List<Cmd> commands = new ArrayList<>();
    private final Object lock = new Object();

    private MirageOverlay(Activity activity) {
        super(activity);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Attach a Mirage overlay to the given activity and install it as the Mirage backend. */
    static void install(Activity activity) {
        activity.runOnUiThread(() -> {
            try {
                MirageOverlay view = new MirageOverlay(activity);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION,   // our own window, no permission
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                activity.getWindowManager().addView(view, lp);
                Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                    @Override public void doFrame(long n) {
                        view.invalidate();
                        Choreographer.getInstance().postFrameCallback(this);
                    }
                });
                Mirage.install(view);
                Log.i(TAG, "Mirage 2D overlay attached");
            } catch (Throwable error) {
                Log.e(TAG, "Mirage overlay attach failed", error);
            }
        });
    }

    // ---- Mirage.Backend ----
    @Override public void clear() { commands = new ArrayList<>(); }

    @Override public void fillRect(float x, float y, float w, float h, float radius, int color) {
        add(Cmd.RECT, x, y, w, h, radius, 0, color, null);
    }
    @Override public void strokeRect(float x, float y, float w, float h, float radius, float width, int color) {
        add(Cmd.RECT_STROKE, x, y, w, h, radius, width, color, null);
    }
    @Override public void line(float x1, float y1, float x2, float y2, float width, int color) {
        add(Cmd.LINE, x1, y1, x2, y2, 0, width, color, null);
    }
    @Override public void fillCircle(float cx, float cy, float r, int color) {
        add(Cmd.CIRCLE, cx, cy, r, 0, 0, 0, color, null);
    }
    @Override public void strokeCircle(float cx, float cy, float r, float width, int color) {
        add(Cmd.CIRCLE_STROKE, cx, cy, r, 0, 0, width, color, null);
    }
    @Override public void text(float x, float y, String s, float size, int color) {
        add(Cmd.TEXT, x, y, size, 0, 0, 0, color, s);
    }
    @Override public int width() { int w = getWidth(); return w > 0 ? w : getResources().getDisplayMetrics().widthPixels; }
    @Override public int height() { int h = getHeight(); return h > 0 ? h : getResources().getDisplayMetrics().heightPixels; }
    @Override public void visible(boolean visible) {
        post(() -> setVisibility(visible ? VISIBLE : GONE));
    }

    private void add(int kind, float a, float b, float c, float d, float e, float f, int color, String text) {
        synchronized (lock) { commands.add(new Cmd(kind, a, b, c, d, e, f, color, text)); }
    }

    // ---- rendering ----
    @Override protected void onDraw(Canvas canvas) {
        List<Cmd> snapshot = commands;   // the current list reference; clear() swaps a new one in
        synchronized (lock) {
            for (Cmd c : snapshot) draw(canvas, c);
        }
    }

    private void draw(Canvas canvas, Cmd c) {
        paint.setColor(c.color);
        switch (c.kind) {
            case Cmd.RECT -> {
                paint.setStyle(Paint.Style.FILL);
                rect.set(c.a, c.b, c.a + c.c, c.b + c.d);
                if (c.e > 0) canvas.drawRoundRect(rect, c.e, c.e, paint);
                else canvas.drawRect(rect, paint);
            }
            case Cmd.RECT_STROKE -> {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(c.f);
                rect.set(c.a, c.b, c.a + c.c, c.b + c.d);
                if (c.e > 0) canvas.drawRoundRect(rect, c.e, c.e, paint);
                else canvas.drawRect(rect, paint);
            }
            case Cmd.LINE -> {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(c.f);
                canvas.drawLine(c.a, c.b, c.c, c.d, paint);
            }
            case Cmd.CIRCLE -> {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(c.a, c.b, c.c, paint);
            }
            case Cmd.CIRCLE_STROKE -> {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(c.f);
                canvas.drawCircle(c.a, c.b, c.c, paint);
            }
            case Cmd.TEXT -> {
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(c.c);
                canvas.drawText(c.text, c.a, c.b, paint);
            }
            default -> { }
        }
    }

    /** One draw command: a kind tag plus up to six floats, a color and optional text. */
    private static final class Cmd {
        static final int RECT = 0, RECT_STROKE = 1, LINE = 2, CIRCLE = 3, CIRCLE_STROKE = 4, TEXT = 5;
        final int kind, color;
        final float a, b, c, d, e, f;
        final String text;
        Cmd(int kind, float a, float b, float c, float d, float e, float f, int color, String text) {
            this.kind = kind; this.a = a; this.b = b; this.c = c; this.d = d; this.e = e; this.f = f;
            this.color = color; this.text = text;
        }
    }
}
