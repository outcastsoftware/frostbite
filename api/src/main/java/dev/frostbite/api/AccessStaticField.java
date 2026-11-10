package dev.frostbite.api;

import java.lang.annotation.*;

/**
 * Marks an abstract mixin method as a static-field accessor on the enclosing
 * {@code @Mixin} target. No instance parameter: a getter takes nothing and returns
 * the value; a setter takes only the new value and returns void.
 *
 * <pre>{@code
 * @AccessStaticField(value = "instance", mode = AccessMode.GETTER)
 * public Object get_characterMotor();
 * @AccessStaticField(value = "instance", mode = AccessMode.SETTER)
 * public void set_characterMotor(Object value);
 * }</pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface AccessStaticField {
    String value();
    AccessMode mode();
}
