package dev.frostbite.api;

/**
 * Mutable view of a hooked method's arguments. Declare it as a callback parameter (after
 * {@code self} for an instance target) to read <em>and change</em> what the original method
 * receives. Index 0 is the first IL2CPP argument (not {@code self}). Changes made at
 * {@code At.HEAD} are passed to the original method and to later hooks in the chain.
 *
 * <pre>{@code
 * @Inject(method = "ChangeLane(self;int)", at = At.HEAD)
 * public void onChangeLane(Object self, Arguments args) {
 *     args.setInt(0, 1);   // always change lane to the right
 * }
 * }</pre>
 *
 * Objects and value-type (struct) arguments are read and written as {@link Il2CppObject}
 * (a struct is a boxed copy; set a new boxed value, or edit the boxed one's fields in place).
 */
public final class Arguments {
    private final long[] values;   // bit-encoded: float/double bits, boolean 0/1, object/boxed address
    private final String abi;      // one type tag per argument

    public Arguments(long[] values, String abi) {
        this.values = values;
        this.abi = abi;
    }

    public int count() { return values.length; }
    public char type(int index) { return abi.charAt(index); }

    public int getInt(int index) { return (int) values[index]; }
    public long getLong(int index) { return values[index]; }
    public float getFloat(int index) { return Float.intBitsToFloat((int) values[index]); }
    public double getDouble(int index) { return Double.longBitsToDouble(values[index]); }
    public boolean getBool(int index) { return values[index] != 0; }
    public Il2CppObject getObject(int index) {
        long address = values[index];
        return address == 0 ? null : new Il2CppObject(address);
    }

    public void setInt(int index, int value) { values[index] = value; }
    public void setLong(int index, long value) { values[index] = value; }
    public void setFloat(int index, float value) { values[index] = Float.floatToRawIntBits(value) & 0xffffffffL; }
    public void setDouble(int index, double value) { values[index] = Double.doubleToRawLongBits(value); }
    public void setBool(int index, boolean value) { values[index] = value ? 1 : 0; }
    public void setObject(int index, Il2CppObject value) { values[index] = value == null ? 0 : value.nativeAddress(); }
}
