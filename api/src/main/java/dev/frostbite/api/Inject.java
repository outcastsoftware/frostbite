package dev.frostbite.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Inject {
    /** Method selector, including receiver kind and parameter types, e.g. UpdateMovement(self). */
    String method();
    At at();
    SelectorMode mode() default SelectorMode.EXACT;
    int priority() default 0;
}
