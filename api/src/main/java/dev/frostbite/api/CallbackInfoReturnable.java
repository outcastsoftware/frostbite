package dev.frostbite.api;

/**
 * Passed to an injection callback on a non-void target. Call {@link #setReturnValue} at
 * {@code At.HEAD} to skip the original method and return a replacement value; the type must
 * match the target's return type (a struct/value type is a boxed {@link Il2CppObject}).
 */
public final class CallbackInfoReturnable<T> extends CallbackInfo {
    private T returnValue;

    public T getReturnValue() { return returnValue; }

    public void setReturnValue(T value) {
        returnValue = value;
        cancel();
    }
}
