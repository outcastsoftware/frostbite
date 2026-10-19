package dev.frostbite.api;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface AccessStaticField {
    String value();
    AccessField.Kind kind();
}
