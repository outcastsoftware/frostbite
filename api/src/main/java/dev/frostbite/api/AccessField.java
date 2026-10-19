package dev.frostbite.api;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface AccessField {
    String value();
    Kind kind();
    enum Kind { GETTER, SETTER }
}
