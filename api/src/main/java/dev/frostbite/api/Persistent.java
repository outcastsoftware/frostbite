package dev.frostbite.api;

/**
 * A captured IL2CPP object that outlives the callback it was taken in.
 *
 * <p>Create one with {@link Il2Cpp#persist(Il2CppObject)} or {@link Il2CppObject#persist()} — for
 * example to stash a hook's {@code self} and edit that same game instance from a later frame:
 *
 * <pre>{@code
 * // in a HEAD hook
 * stashed = self.persist();
 *
 * // any time later
 * Il2CppObject motor = stashed.get();
 * if (motor != null) Il2Cpp.setFloat(motor, SPEED, 0f);  // still alive
 * }</pre>
 *
 * <p>Backed by a weak GC handle, so it tracks the object's real liveness: {@link #get()} returns
 * the live object, or {@code null} once the game has dropped it and the GC has collected it. It
 * does not keep the object alive. Release it with {@link #release()} when you no longer need it.
 */
public final class Persistent {
    private long handle;

    Persistent(long handle) { this.handle = handle; }

    /** The object if still alive, otherwise {@code null}. Re-resolved each call. */
    public Il2CppObject get() {
        if (handle == 0) return null;
        long address = Il2Cpp.gcTarget(handle);
        return address == 0 ? null : new Il2CppObject(address);
    }

    /** True while the object is still alive. */
    public boolean isAlive() { return handle != 0 && Il2Cpp.gcTarget(handle) != 0; }

    /** Free the GC handle. Idempotent; {@link #get()} returns {@code null} afterward. */
    public void release() {
        if (handle != 0) { Il2Cpp.gcFree(handle); handle = 0; }
    }
}
