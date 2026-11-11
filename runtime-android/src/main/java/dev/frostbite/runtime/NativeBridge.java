package dev.frostbite.runtime;

import android.util.Log;
import dev.frostbite.api.CallbackInfoReturnable;
import dev.frostbite.api.Il2Cpp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Coordinates BNM metadata resolution and one Dobby trampoline per address. */
public final class NativeBridge {
    private static final String TAG = "Frostbite";
    private static List<HookDeclaration> declarations = Collections.emptyList();
    // modId -> its position in the index (load order); earlier mods win hook-priority ties.
    private static Map<String, Integer> modOrder = Collections.emptyMap();
    private static volatile boolean ready;
    private static boolean installed;

    private NativeBridge() {}

    static synchronized void initialize(List<LoadedMod> mods) {
        List<HookDeclaration> all = new ArrayList<>();
        Map<String, Integer> order = new HashMap<>();
        for (LoadedMod mod : mods) {
            order.putIfAbsent(mod.id(), order.size());
            all.addAll(mod.hooks());
        }
        declarations = Collections.unmodifiableList(all);
        modOrder = Collections.unmodifiableMap(order);
        System.loadLibrary("frostbite");
        Il2Cpp.install(new Il2Cpp.Backend() {
            @Override public long get(long self, String asm, String ns, String cls, String field, char abi) {
                return nativeGetField(self, asm, ns, cls, field, abi);
            }
            @Override public void set(long self, String asm, String ns, String cls, String field, char abi, long value) {
                nativeSetField(self, asm, ns, cls, field, abi, value);
            }
            @Override public long invoke(long self, String asm, String ns, String cls, String selector,
                                         char retAbi, String argAbi, long[] args) {
                return nativeInvoke(self, asm, ns, cls, selector, retAbi, argAbi, args);
            }
            @Override public long gcPersist(long self) { return nativeGcPersist(self); }
            @Override public long gcTarget(long handle) { return nativeGcTarget(handle); }
            @Override public void gcFree(long handle) { nativeGcFree(handle); }
        });
        if (ready) install();
    }

    /** Invoked from BNM's IL2CPP-ready event through JNI. */
    public static synchronized void onIl2CppReady() {
        ready = true;
        if (!declarations.isEmpty()) {
            try { install(); }
            catch (RuntimeException | LinkageError error) {
                Log.e(TAG, "IL2CPP hook installation failed", error);
            }
        }
    }

    public static CallbackInfoReturnable<Object> dispatchHead(long address, long receiver,
                                                               long argument0, long argument1) {
        return CallbackDispatcher.dispatchHead(address, receiver, argument0, argument1);
    }

    public static void dispatchTail(long address, long receiver, long argument0, long argument1) {
        CallbackDispatcher.dispatchTail(address, receiver, argument0, argument1);
    }

    public static CallbackInfoReturnable<Object> dispatchHeadStatic(long address, long argument0, long argument1) {
        return CallbackDispatcher.dispatchHeadStatic(address, argument0, argument1);
    }

    public static void dispatchTailStatic(long address, long argument0, long argument1) {
        CallbackDispatcher.dispatchTailStatic(address, argument0, argument1);
    }

    public static CallbackInfoReturnable<Object> dispatchHeadUniversal(long address, long self, long[] values, String abi) {
        return CallbackDispatcher.dispatchHeadUniversal(address, self, values, abi);
    }

    public static void dispatchTailUniversal(long address, long self, long[] values, String abi) {
        CallbackDispatcher.dispatchTailUniversal(address, self, values, abi);
    }

    private static void install() {
        if (installed) return;
        List<ResolvedMethod> candidates = new ArrayList<>();
        Set<String> classes = new HashSet<>();
        for (HookDeclaration hook : declarations) {
            String key = hook.assembly() + "\u0000" + hook.namespace() + "\u0000" + hook.targetClass();
            if (classes.add(key))
                candidates.addAll(Arrays.asList(nativeEnumerate(hook.assembly(), hook.namespace(), hook.targetClass())));
        }
        // Resolve and validate each hook on its own: one unsupported or ambiguous hook is
        // skipped with a warning, never aborting the mods that resolve cleanly.
        List<HookResolver.ResolvedHook> resolved = new ArrayList<>();
        Map<Long, String> addressAbis = new HashMap<>();
        for (HookDeclaration declaration : declarations) {
            try {
                HookResolver.ResolvedHook hook = HookResolver.resolve(List.of(declaration), candidates).get(0);
                ResolvedMethod method = hook.method();
                if (method.abi().length() < 2 || method.abi().charAt(1) != ':')
                    throw new IllegalArgumentException("malformed native ABI: " + method.signature());
                String previous = addressAbis.putIfAbsent(method.address(), method.abi());
                if (previous != null && !previous.equals(method.abi()))
                    throw new IllegalArgumentException("different method ABIs share native address " +
                            Long.toHexString(method.address()));
                resolved.add(hook);
            } catch (RuntimeException error) {
                Log.w(TAG, "Skipping hook [" + declaration.modId() + "] "
                        + declaration.selector().expression() + ": " + error.getMessage());
            }
        }
        resolved.sort(Comparator.comparingLong((HookResolver.ResolvedHook h) -> h.method().address())
                .thenComparing(h -> -h.hook().priority())
                // Reorder in the mod index breaks priority ties: earlier mod runs first.
                .thenComparingInt(h -> modOrder.getOrDefault(h.hook().modId(), Integer.MAX_VALUE))
                .thenComparing(h -> h.hook().modId())
                .thenComparing(h -> h.hook().callback().getName()));
        CallbackDispatcher.register(resolved);
        // Force the universal path for callbacks the fixed trampolines cannot serve: a mutable
        // Arguments (fixed forwards unmodified registers), or a void target that cancels (the
        // fixed void trampoline always runs the original).
        Set<Long> universalAddresses = new HashSet<>();
        for (HookResolver.ResolvedHook hook : resolved) {
            boolean voidTarget = hook.method().returnType().equals("void");
            for (Class<?> type : hook.hook().callback().getParameterTypes()) {
                String name = type.getName();
                if (name.equals("dev.frostbite.api.Arguments") || (voidTarget &&
                        (name.equals("dev.frostbite.api.CallbackInfo") ||
                         name.equals("dev.frostbite.api.CallbackInfoReturnable")))) {
                    universalAddresses.add(hook.method().address());
                    break;
                }
            }
        }
        Set<Long> addresses = new HashSet<>();
        int installedCount = 0;
        for (HookResolver.ResolvedHook hook : resolved) {
            ResolvedMethod method = hook.method();
            if (!addresses.add(method.address())) continue;
            boolean isStatic = !method.signature().contains("(self");
            boolean ok;
            if (fixedSupported(method) && !universalAddresses.contains(method.address())) {
                ok = nativeInstall(method.address(), returnKind(method.abi().charAt(0)),
                        method.abi().length() - 2, isStatic);
            } else {
                // Any signature the fixed trampolines cannot take goes through the universal
                // trampoline: float/double/long/struct args, more than two args, 64-bit/double returns.
                String selector = method.signature();
                String name = selector.substring(0, selector.indexOf('('));
                ok = nativeInstallUniversal(method.address(), method.assembly(), method.namespace(),
                        method.targetClass(), name, method.abi().length() - 2, isStatic);
            }
            if (ok) installedCount++;
            else Log.w(TAG, "Could not hook " + method.signature());
        }
        installed = true;
        Log.i(TAG, "Installed " + installedCount + " IL2CPP hook(s)");
    }

    /** True when the fixed-shape trampolines can take this method: <=2 int/bool/object args
     *  and a void/bool/int/float/object return. Everything else uses the universal trampoline. */
    private static boolean fixedSupported(ResolvedMethod method) {
        String abi = method.abi();
        if (abi.length() > 4) return false;
        char r = abi.charAt(0);
        if (r != 'V' && r != 'Z' && r != 'I' && r != 'F' && r != 'P') return false;
        for (int i = 2; i < abi.length(); i++)
            if (abi.charAt(i) != 'P' && abi.charAt(i) != 'I' && abi.charAt(i) != 'Z') return false;
        return true;
    }

    private static int returnKind(char type) {
        return switch (type) {
            case 'V' -> 0;
            case 'Z' -> 1;
            case 'I' -> 2;
            case 'F' -> 3;
            case 'P' -> 4;
            default -> throw new IllegalArgumentException("Unsupported native return type: " + type);
        };
    }

    private static native ResolvedMethod[] nativeEnumerate(String assembly, String namespace, String targetClass);
    private static native boolean nativeInstall(long address, int returnKind, int argumentCount, boolean isStatic);
    private static native boolean nativeInstallUniversal(long address, String assembly, String namespace,
            String targetClass, String methodName, int paramCount, boolean isStatic);

    // Accessors: self == 0 means a static field or static method. Values marshalled as
    // long (int sign-extended, float via floatToRawIntBits, boolean 0/1, object address).
    static native long nativeGetField(long self, String assembly, String namespace, String targetClass,
                                      String field, char abi);
    static native void nativeSetField(long self, String assembly, String namespace, String targetClass,
                                      String field, char abi, long value);
    static native long nativeInvoke(long self, String assembly, String namespace, String targetClass,
                                    String selector, char returnAbi, String argumentAbi, long[] arguments);

    // Weak GC handles: persist a captured object past its callback. Handle 0 means failure/dead.
    static native long nativeGcPersist(long self);
    static native long nativeGcTarget(long handle);
    static native void nativeGcFree(long handle);
}
