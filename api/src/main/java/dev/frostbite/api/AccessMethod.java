package dev.frostbite.api;

import java.lang.annotation.*;

/**
 * Marks an abstract mixin method as an invoker for a method on the enclosing
 * {@code @Mixin} target. The selector is the canonical signature, so {@code self}
 * or {@code static} picks instance vs static. An instance invoker takes the target
 * instance as its first parameter; the remaining Java parameters map to the IL2CPP
 * arguments in order. Supported parameter and return types are {@code int},
 * {@code float}, {@code boolean}, and object references.
 *
 * <pre>{@code
 * @AccessMethod("ChangeLane(self;int)")
 * public boolean changeLane(Object characterMotor, int lane);
 * @AccessMethod("ResetSession(static)")
 * public void resetSession();
 * }</pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface AccessMethod {
    String value();
}
