package dev.frostbite.api;

import java.lang.annotation.*;

/**
 * Marks an abstract mixin method as an instance-field accessor on the enclosing
 * {@code @Mixin} target. A getter takes the target instance and returns the field
 * value; a setter takes the instance and the new value and returns void.
 *
 * <pre>{@code
 * @AccessField(value = "runTime", mode = AccessMode.GETTER)
 * public int get_runTime(Object characterMotor);
 * @AccessField(value = "runTime", mode = AccessMode.SETTER)
 * public void set_runTime(Object characterMotor, int value);
 * }</pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface AccessField {
    String value();
    AccessMode mode();
}
