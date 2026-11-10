package dev.frostbite.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of live mixin instances. Frostbite creates one instance per {@code @Mixin}
 * class (the generated subclass that implements its accessors) and registers it here,
 * so code anywhere in the mod can reach a mixin's accessors, not just the mixin itself.
 *
 * <pre>{@code
 * CharacterMotorMixin motor = Mixins.get(CharacterMotorMixin.class);
 * int runTime = motor.get_runTime(self);
 * }</pre>
 */
public final class Mixins {
    private static final Map<Class<?>, Object> instances = new ConcurrentHashMap<>();

    private Mixins() {}

    /** Called by the runtime once per mixin after it is instantiated. */
    public static void register(Class<?> mixinClass, Object instance) {
        instances.put(mixinClass, instance);
    }

    /** The live instance for a mixin class, or null if that mixin was not loaded. */
    @SuppressWarnings("unchecked")
    public static <T> T get(Class<T> mixinClass) {
        return (T) instances.get(mixinClass);
    }
}
