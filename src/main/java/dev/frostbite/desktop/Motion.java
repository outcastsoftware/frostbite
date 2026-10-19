package dev.frostbite.desktop;

import javax.swing.Timer;
import java.util.function.DoubleConsumer;

/** Small EDT tween runner shared by navigation, controls, and page transitions. */
final class Motion {
    private Motion() { }

    static Timer tween(int durationMs, DoubleConsumer frame, Runnable finished) {
        long began = System.nanoTime();
        Timer timer = new Timer(16, null);
        timer.addActionListener(event -> {
            double raw = Math.min(1d, (System.nanoTime() - began) / (durationMs * 1_000_000d));
            double eased = 1d - Math.pow(1d - raw, 3d);
            frame.accept(eased);
            if (raw >= 1d) {
                timer.stop();
                if (finished != null) finished.run();
            }
        });
        timer.setCoalesce(true);
        timer.start();
        return timer;
    }

    static double mix(double start, double end, double fraction) {
        return start + (end - start) * fraction;
    }
}
