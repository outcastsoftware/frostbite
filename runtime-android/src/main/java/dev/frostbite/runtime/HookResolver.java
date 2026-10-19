package dev.frostbite.runtime;

import dev.frostbite.api.At;
import dev.frostbite.api.CallbackInfoReturnable;
import dev.frostbite.api.Il2CppObject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resolves metadata selectors and validates callback ordering before native installation. */
public final class HookResolver {
    private HookResolver() {}

    public static List<ResolvedHook> resolve(List<HookDeclaration> declarations,
                                             List<ResolvedMethod> candidates) {
        List<ResolvedHook> result = new ArrayList<>();
        for (HookDeclaration hook : declarations) {
            List<ResolvedMethod> matches = candidates.stream()
                    .filter(method -> method.assembly().equals(hook.assembly()))
                    .filter(method -> method.namespace().equals(hook.namespace()))
                    .filter(method -> method.targetClass().equals(hook.targetClass()))
                    .filter(method -> hook.selector().matches(method.signature()))
                    .toList();
            if (matches.size() != 1)
                throw new IllegalArgumentException(hook.modId() + ": selector " +
                        hook.selector().expression() + " matched " + matches.size() + " methods: " +
                        matches.stream().map(ResolvedMethod::signature).toList());
            ResolvedMethod method = matches.get(0);
            boolean returnsVoid = method.returnType().equals("void");
            if (!returnsVoid && hook.at() == At.TAIL)
                throw new IllegalArgumentException(hook.modId() + ": TAIL requires a void target: " +
                        method.signature());
            verifyCallback(hook.callback(), method, returnsVoid);
            if (method.address() == 0)
                throw new IllegalArgumentException("No native address for " + method.signature());
            result.add(new ResolvedHook(method, hook));
        }
        result.sort(Comparator.comparingLong((ResolvedHook item) -> item.method().address())
                .thenComparing((ResolvedHook item) -> -item.hook().priority())
                .thenComparing(item -> item.hook().modId())
                .thenComparing(item -> item.hook().callback().getName()));
        return List.copyOf(result);
    }

    private static void verifyCallback(Method callback, ResolvedMethod target, boolean returnsVoid) {
        Class<?>[] parameters = callback.getParameterTypes();
        boolean self = target.signature().contains("(self");
        if (self && (parameters.length == 0 ||
                (parameters[0] != Object.class && parameters[0] != Il2CppObject.class)))
            throw new IllegalArgumentException("Instance callback needs Object self: " + callback);
        if (!returnsVoid && (parameters.length == 0 ||
                parameters[parameters.length - 1] != CallbackInfoReturnable.class))
            throw new IllegalArgumentException("Non-void HEAD needs CallbackInfoReturnable: " + callback);
        int arguments = target.abi().length() - 2;
        int expected = (self ? 1 : 0) + arguments + (returnsVoid ? 0 : 1);
        if (parameters.length != expected)
            throw new IllegalArgumentException("Callback parameter count does not match target: " + callback);
        for (int i = 0; i < arguments; i++) {
            char kind = target.abi().charAt(i + 2);
            Class<?> declared = parameters[i + (self ? 1 : 0)];
            if (kind == 'I' && declared != int.class)
                throw new IllegalArgumentException("Integer target argument requires int: " + callback);
            if (kind == 'Z' && declared != boolean.class)
                throw new IllegalArgumentException("Boolean target argument requires boolean: " + callback);
            if (kind == 'P' && declared != Object.class && declared != Il2CppObject.class)
                throw new IllegalArgumentException("Object target argument requires Object or Il2CppObject: " + callback);
        }
    }

    public record ResolvedHook(ResolvedMethod method, HookDeclaration hook) {}
}
