package dev.frostbite.api;

/**
 * Passed to an injection callback that wants to cancel the original method. Declare it as the
 * last callback parameter and call {@link #cancel()} at {@code At.HEAD} to skip the original
 * entirely (a name-based NOP). For a non-void target use {@link CallbackInfoReturnable}, which
 * also supplies the replacement return value.
 *
 * <pre>{@code
 * @Inject(method = "ApplyGravity(self)", at = At.HEAD)
 * public void noGravity(Object self, CallbackInfo ci) {
 *     ci.cancel();   // the original ApplyGravity never runs
 * }
 * }</pre>
 */
public class CallbackInfo {
    boolean cancelled;

    public boolean isCancelled() { return cancelled; }

    public void cancel() { cancelled = true; }
}
