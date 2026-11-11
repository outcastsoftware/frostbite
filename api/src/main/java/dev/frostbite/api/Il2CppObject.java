package dev.frostbite.api;

/**
 * Java view of an IL2CPP object during a callback. The native pointer is only
 * valid for the current callback unless a future runtime API pins the object.
 */
public final class Il2CppObject {
    private final long nativeAddress;

    public Il2CppObject(long nativeAddress) {
        if (nativeAddress == 0) throw new IllegalArgumentException("Null IL2CPP object");
        this.nativeAddress = nativeAddress;
    }

    public long nativeAddress() { return nativeAddress; }

    /**
     * Keep this object usable after the current callback returns. See {@link Persistent}.
     * The raw {@link #nativeAddress()} is only valid during the callback; a {@code Persistent}
     * re-resolves the object safely and reports when the game has dropped it.
     */
    public Persistent persist() { return Il2Cpp.persist(this); }
}
