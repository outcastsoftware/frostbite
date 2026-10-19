package dev.frostbite.api;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface AccessMethod {
    String value();
    SelectorMode mode() default SelectorMode.EXACT;
}
