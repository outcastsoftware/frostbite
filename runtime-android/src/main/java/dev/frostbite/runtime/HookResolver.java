package dev.frostbite.runtime;

import dev.frostbite.api.Arguments;
import dev.frostbite.api.At;
import dev.frostbite.api.CallbackInfo;
import dev.frostbite.api.CallbackInfoReturnable;
import dev.frostbite.api.Il2CppObject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

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
                    .collect(Collectors.toList());
            if (matches.size() != 1)
                throw new IllegalArgumentException(hook.modId() + ": selector " +
                        hook.selector().expression() + " matched " + matches.size() + " methods: " +
                        matches.stream().map(ResolvedMethod::signature).collect(Collectors.toList()));
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
        return Collections.unmodifiableList(result);
    }

    private static void verifyCallback(Method callback, ResolvedMethod target, boolean returnsVoid) {
        Class<?>[] parameters = callback.getParameterTypes();
        boolean self = target.signature().contains("(self");
        if (self && (parameters.length == 0 ||
                (parameters[0] != Object.class && parameters[0] != Il2CppObject.class)))
            throw new IllegalArgumentException("Instance callback needs Object self: " + callback);
        // A trailing CallbackInfo (void) or CallbackInfoReturnable (non-void) lets the callback
        // cancel the original. Non-void targets must use CallbackInfoReturnable.
        boolean lastCi = parameters.length > 0 &&
                (parameters[parameters.length - 1] == CallbackInfo.class ||
                 parameters[parameters.length - 1] == CallbackInfoReturnable.class);
        if (!returnsVoid && (!lastCi || parameters[parameters.length - 1] != CallbackInfoReturnable.class))
            throw new IllegalArgumentException("Non-void HEAD needs CallbackInfoReturnable: " + callback);
        // Alternative shape: (self?, Arguments, CallbackInfo?) — a mutable view of the raw
        // arguments instead of typed-per-argument parameters.
        boolean raw = false;
        for (Class<?> type : parameters) if (type == Arguments.class) { raw = true; break; }
        if (raw) {
            int expectedRaw = (self ? 1 : 0) + 1 + (lastCi ? 1 : 0);
            if (parameters.length != expectedRaw || parameters[self ? 1 : 0] != Arguments.class)
                throw new IllegalArgumentException("Arguments callback must be (self?, Arguments, CallbackInfo?): " + callback);
            return;
        }
        int arguments = target.abi().length() - 2;
        int expected = (self ? 1 : 0) + arguments + (lastCi ? 1 : 0);
        if (parameters.length != expected)
            throw new IllegalArgumentException("Callback parameter count does not match target: " + callback);
        for (int i = 0; i < arguments; i++) {
            char kind = target.abi().charAt(i + 2);
            Class<?> declared = parameters[i + (self ? 1 : 0)];
            boolean ok = switch (kind) {
                case 'I' -> declared == int.class;
                case 'J' -> declared == long.class;
                case 'Z' -> declared == boolean.class;
                case 'F' -> declared == float.class;
                case 'D' -> declared == double.class;
                case 'P', 'S' -> declared == Object.class || declared == Il2CppObject.class;
                default -> false;   // unknown target argument type
            };
            if (!ok)
                throw new IllegalArgumentException("Callback parameter " + i + " type " + declared
                        + " does not match target argument '" + kind + "': " + callback);
        }
    }

    public record ResolvedHook(ResolvedMethod method, HookDeclaration hook) {}
}
