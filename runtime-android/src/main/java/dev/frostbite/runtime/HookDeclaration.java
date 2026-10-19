package dev.frostbite.runtime;

import dev.frostbite.api.At;
import dev.frostbite.api.MethodSelector;
import java.lang.reflect.Method;

public record HookDeclaration(String modId, String assembly, String namespace,
                              String targetClass, MethodSelector selector, At at,
                              int priority, Object mixin, Method callback) {}
