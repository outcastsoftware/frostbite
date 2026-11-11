package dev.frostbite.api;

/**
 * Runtime-backed access to IL2CPP fields and methods from inside a mod.
 *
 * <p>The API module carries no native code, so the Frostbite runtime installs a
 * {@link Backend} at startup. Mods call the typed helpers here; each resolves the
 * target through BNM by assembly, namespace, class, and member name, then reads,
 * writes, or invokes it. Pass {@code null} as the instance for a static field or a
 * static method; pass the callback's {@code self} otherwise.
 *
 * <p>Supported member types mirror the hook bridge: {@code int}, {@code float},
 * {@code boolean}, and object references (returned as {@link Il2CppObject} or
 * {@code null}). Object addresses are valid only for the current callback.
 */
public final class Il2Cpp {
    /** ABI tags shared with the native bridge: value marshalling is by {@code long}. */
    public static final char INT = 'I', LONG = 'J', FLOAT = 'F', DOUBLE = 'D', BOOL = 'Z', OBJECT = 'P', VOID = 'V';

    /** Implemented by the runtime; carries the JNI calls into libfrostbite. */
    public interface Backend {
        long get(long self, String asm, String ns, String cls, String field, char abi);
        void set(long self, String asm, String ns, String cls, String field, char abi, long value);
        long invoke(long self, String asm, String ns, String cls, String selector,
                    char retAbi, String argAbi, long[] args);
        long gcPersist(long self);
        long gcTarget(long handle);
        void gcFree(long handle);
    }

    private static volatile Backend backend;

    private Il2Cpp() {}

    /** Called once by the runtime before any mod callback runs. */
    public static void install(Backend installed) { backend = installed; }

    private static Backend backend() {
        Backend b = backend;
        if (b == null) throw new IllegalStateException("Frostbite IL2CPP backend not installed");
        return b;
    }

    private static long address(Il2CppObject instance) {
        return instance == null ? 0L : instance.nativeAddress();
    }

    // ---- fields: getters ------------------------------------------------

    public static int getInt(Il2CppObject self, Target field) {
        return (int) backend().get(address(self), field.asm, field.ns, field.cls, field.member, INT);
    }

    public static long getLong(Il2CppObject self, Target field) {
        return backend().get(address(self), field.asm, field.ns, field.cls, field.member, LONG);
    }

    public static double getDouble(Il2CppObject self, Target field) {
        return Double.longBitsToDouble(backend().get(address(self), field.asm, field.ns, field.cls, field.member, DOUBLE));
    }

    public static float getFloat(Il2CppObject self, Target field) {
        return Float.intBitsToFloat((int) backend().get(address(self), field.asm, field.ns, field.cls, field.member, FLOAT));
    }

    public static boolean getBool(Il2CppObject self, Target field) {
        return backend().get(address(self), field.asm, field.ns, field.cls, field.member, BOOL) != 0;
    }

    public static Il2CppObject getObject(Il2CppObject self, Target field) {
        long value = backend().get(address(self), field.asm, field.ns, field.cls, field.member, OBJECT);
        return value == 0 ? null : new Il2CppObject(value);
    }

    // ---- fields: setters ------------------------------------------------

    public static void setInt(Il2CppObject self, Target field, int value) {
        backend().set(address(self), field.asm, field.ns, field.cls, field.member, INT, value);
    }

    public static void setLong(Il2CppObject self, Target field, long value) {
        backend().set(address(self), field.asm, field.ns, field.cls, field.member, LONG, value);
    }

    public static void setDouble(Il2CppObject self, Target field, double value) {
        backend().set(address(self), field.asm, field.ns, field.cls, field.member, DOUBLE, Double.doubleToRawLongBits(value));
    }

    public static void setFloat(Il2CppObject self, Target field, float value) {
        backend().set(address(self), field.asm, field.ns, field.cls, field.member, FLOAT, Float.floatToRawIntBits(value) & 0xffffffffL);
    }

    public static void setBool(Il2CppObject self, Target field, boolean value) {
        backend().set(address(self), field.asm, field.ns, field.cls, field.member, BOOL, value ? 1 : 0);
    }

    public static void setObject(Il2CppObject self, Target field, Il2CppObject value) {
        backend().set(address(self), field.asm, field.ns, field.cls, field.member, OBJECT, address(value));
    }

    // ---- method calls ---------------------------------------------------

    public static void invokeVoid(Il2CppObject self, Target method, Object... args) {
        Marshalled m = marshal(args);
        backend().invoke(address(self), method.asm, method.ns, method.cls, method.member, VOID, m.abi, m.values);
    }

    public static int invokeInt(Il2CppObject self, Target method, Object... args) {
        Marshalled m = marshal(args);
        return (int) backend().invoke(address(self), method.asm, method.ns, method.cls, method.member, INT, m.abi, m.values);
    }

    public static long invokeLong(Il2CppObject self, Target method, Object... args) {
        Marshalled m = marshal(args);
        return backend().invoke(address(self), method.asm, method.ns, method.cls, method.member, LONG, m.abi, m.values);
    }

    public static double invokeDouble(Il2CppObject self, Target method, Object... args) {
        Marshalled m = marshal(args);
        return Double.longBitsToDouble(backend().invoke(address(self), method.asm, method.ns, method.cls, method.member, DOUBLE, m.abi, m.values));
    }

    public static float invokeFloat(Il2CppObject self, Target method, Object... args) {
        Marshalled m = marshal(args);
        return Float.intBitsToFloat((int) backend().invoke(address(self), method.asm, method.ns, method.cls, method.member, FLOAT, m.abi, m.values));
    }

    public static boolean invokeBool(Il2CppObject self, Target method, Object... args) {
        Marshalled m = marshal(args);
        return backend().invoke(address(self), method.asm, method.ns, method.cls, method.member, BOOL, m.abi, m.values) != 0;
    }

    public static Il2CppObject invokeObject(Il2CppObject self, Target method, Object... args) {
        Marshalled m = marshal(args);
        long value = backend().invoke(address(self), method.asm, method.ns, method.cls, method.member, OBJECT, m.abi, m.values);
        return value == 0 ? null : new Il2CppObject(value);
    }

    // ---- persisting objects past the callback ---------------------------

    /**
     * Keep an IL2CPP object usable after the callback returns. Captures the object in a weak
     * GC handle: the returned {@link Persistent} re-resolves it on demand and reports {@code null}
     * once the game drops it and the GC collects it. The object is not kept alive. Call
     * {@link Persistent#release()} when done.
     */
    public static Persistent persist(Il2CppObject object) {
        long handle = backend().gcPersist(object.nativeAddress());
        if (handle == 0) throw new IllegalStateException("Could not persist IL2CPP object");
        return new Persistent(handle);
    }

    static long gcTarget(long handle) { return backend().gcTarget(handle); }
    static void gcFree(long handle) { backend().gcFree(handle); }

    private record Marshalled(String abi, long[] values) {}

    private static Marshalled marshal(Object[] args) {
        StringBuilder abi = new StringBuilder(args.length);
        long[] values = new long[args.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof Integer value) { abi.append(INT); values[i] = value; }
            else if (arg instanceof Long value) { abi.append(LONG); values[i] = value; }
            else if (arg instanceof Float value) { abi.append(FLOAT); values[i] = Float.floatToRawIntBits(value) & 0xffffffffL; }
            else if (arg instanceof Double value) { abi.append(DOUBLE); values[i] = Double.doubleToRawLongBits(value); }
            else if (arg instanceof Boolean value) { abi.append(BOOL); values[i] = value ? 1 : 0; }
            else if (arg == null) { abi.append(OBJECT); values[i] = 0; }
            else if (arg instanceof Il2CppObject value) { abi.append(OBJECT); values[i] = value.nativeAddress(); }
            else throw new IllegalArgumentException("Unsupported IL2CPP argument: " + arg.getClass());
        }
        return new Marshalled(abi.toString(), values);
    }

    /**
     * A field or method on an IL2CPP type. For a field, {@code member} is the field
     * name; for a method it is the canonical selector (e.g. {@code "ChangeLane(self;int)"}
     * or {@code "Pause(static)"}), matching the {@code @Inject} style.
     */
    public static final class Target {
        final String asm, ns, cls, member;
        public Target(String asm, String ns, String cls, String member) {
            this.asm = asm; this.ns = ns; this.cls = cls; this.member = member;
        }
    }
}
