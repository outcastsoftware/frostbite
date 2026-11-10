package dev.frostbite.sample;

import android.util.Log;
import dev.frostbite.api.AccessMethod;
import dev.frostbite.api.At;
import dev.frostbite.api.CallbackInfoReturnable;
import dev.frostbite.api.Inject;
import dev.frostbite.api.Mixin;

/**
 * Static-method support on {@code SYBO.Components.Pooling.PoolManager} (all its methods are
 * static, no receiver):
 * <ul>
 *   <li>a static invoker ({@link #working()}) calls the static {@code get_Working},</li>
 *   <li>a static non-void injection overrides that method's boolean return.</li>
 * </ul>
 * Calling {@link #working()} routes through the hook, so the override is observable even
 * though the game does not call this method on its own during a plain run.
 */
@Mixin(assembly = "Assembly-CSharp", namespace = "SYBO.Components.Pooling", targetClass = "PoolManager")
public abstract class PoolManagerMixin {
    private long calls;

    /** Static method invoker: no instance parameter. */
    @AccessMethod("get_Working(static)")
    public abstract boolean working();

    /** Static non-void injection: force the return to true. */
    @Inject(method = "get_Working(static)", at = At.HEAD)
    public void onWorking(CallbackInfoReturnable<Boolean> callback) {
        if (++calls % 120 == 1)
            Log.i("FrostbiteSample", "[SubwayTools] static hook fired: PoolManager.get_Working() #" + calls);
        callback.setReturnValue(Boolean.TRUE);
    }
}
