package dev.frostbite.api;

public final class CallbackInfoReturnable<T> {
    private boolean cancelled;
    private T returnValue;

    public boolean isCancelled() { return cancelled; }
    public T getReturnValue() { return returnValue; }
    public void setReturnValue(T value) {
        returnValue = value;
        cancelled = true;
    }
}
