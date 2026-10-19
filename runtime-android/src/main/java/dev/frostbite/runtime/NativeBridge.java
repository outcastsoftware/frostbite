package dev.frostbite.runtime;

import android.util.Log;
import dev.frostbite.api.CallbackInfoReturnable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Coordinates BNM metadata resolution and one Dobby trampoline per address. */
public final class NativeBridge {
    private static final String TAG = "Frostbite";
    private static List<HookDeclaration> declarations = List.of();
    private static volatile boolean ready;
    private static boolean installed;

    private NativeBridge() {}

    static synchronized void initialize(List<LoadedMod> mods) {
        List<HookDeclaration> all = new ArrayList<>();
        for (LoadedMod mod : mods) all.addAll(mod.hooks());
        declarations = List.copyOf(all);
        System.loadLibrary("frostbite");
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

    private static void install() {
        if (installed) return;
        List<ResolvedMethod> candidates = new ArrayList<>();
        Set<String> classes = new HashSet<>();
        for (HookDeclaration hook : declarations) {
            String key = hook.assembly() + "\u0000" + hook.namespace() + "\u0000" + hook.targetClass();
            if (classes.add(key))
                candidates.addAll(List.of(nativeEnumerate(hook.assembly(), hook.namespace(), hook.targetClass())));
        }
        List<HookResolver.ResolvedHook> resolved = HookResolver.resolve(declarations, candidates);
        Map<Long, String> addressAbis = new HashMap<>();
        for (HookResolver.ResolvedHook hook : resolved) {
            ResolvedMethod method = hook.method();
            if (!method.signature().contains("(self"))
                throw new IllegalArgumentException("Native backend supports instance methods only: " +
                        method.signature());
            supportedAbi(method);
            String previous = addressAbis.putIfAbsent(method.address(), method.abi());
            if (previous != null && !previous.equals(method.abi()))
                throw new IllegalArgumentException("Different method ABIs share native address " +
                        Long.toHexString(method.address()));
        }
        CallbackDispatcher.register(resolved);
        Set<Long> addresses = new HashSet<>();
        for (HookResolver.ResolvedHook hook : resolved) {
            ResolvedMethod method = hook.method();
            if (addresses.add(method.address()) &&
                    !nativeInstall(method.address(), returnKind(method.abi().charAt(0)),
                            method.abi().length() - 2))
                throw new IllegalStateException("Dobby failed to hook " + method.signature());
        }
        installed = true;
        Log.i(TAG, "Installed " + addresses.size() + " IL2CPP hook(s)");
    }

    private static void supportedAbi(ResolvedMethod method) {
        String abi = method.abi();
        if (abi.length() < 2 || abi.charAt(1) != ':' || abi.length() > 4)
            throw new IllegalArgumentException("Unsupported native ABI: " + method.signature());
        returnKind(abi.charAt(0));
        for (int i = 2; i < abi.length(); i++)
            if (abi.charAt(i) != 'P' && abi.charAt(i) != 'I' && abi.charAt(i) != 'Z')
                throw new IllegalArgumentException("Unsupported parameter ABI: " + method.signature());
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
    private static native boolean nativeInstall(long address, int returnKind, int argumentCount);
}
