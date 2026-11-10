package dev.frostbite.runtime;

import android.util.Log;
import dev.frostbite.api.At;
import dev.frostbite.api.CallbackInfoReturnable;
import dev.frostbite.api.Il2CppObject;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Called by native trampolines. One address has one ordered callback chain. */
public final class CallbackDispatcher {
    private static final String TAG = "Frostbite";
    private static volatile Map<Long, List<HookResolver.ResolvedHook>> chains = Collections.emptyMap();
    private static final ThreadLocal<Set<Long>> active = ThreadLocal.withInitial(HashSet::new);

    private CallbackDispatcher() {}

    static synchronized void register(List<HookResolver.ResolvedHook> resolved) {
        Map<Long, List<HookResolver.ResolvedHook>> grouped = new HashMap<>();
        for (HookResolver.ResolvedHook hook : resolved)
            grouped.computeIfAbsent(hook.method().address(), ignored -> new java.util.ArrayList<>()).add(hook);
        grouped.replaceAll((ignored, value) -> Collections.unmodifiableList(value));
        chains = Collections.unmodifiableMap(grouped);
    }

    public static CallbackInfoReturnable<Object> dispatchHead(long address, long receiver,
                                                               long argument0, long argument1) {
        return dispatchHead(address, callbackArguments(address, receiver, argument0, argument1));
    }

    public static void dispatchTail(long address, long receiver, long argument0, long argument1) {
        dispatchTail(address, callbackArguments(address, receiver, argument0, argument1));
    }

    /**
     * Universal trampoline: native passes raw argument values (bit-encoded) plus their type
     * tags. Each callback gets either typed parameters or a mutable {@link Arguments} view
     * over the same array, so writes flow back to the registers.
     */
    public static CallbackInfoReturnable<Object> dispatchHeadUniversal(long address, long self, long[] values, String abi) {
        List<HookResolver.ResolvedHook> chain = chains.get(address);
        if (chain == null || !active.get().add(address)) return null;
        try {
            CallbackInfoReturnable<Object> result = new CallbackInfoReturnable<>();
            for (HookResolver.ResolvedHook hook : chain) {
                if (hook.hook().at() != At.HEAD) continue;
                try {
                    hook.hook().callback().invoke(hook.hook().mixin(),
                            universalParams(hook, self, values, abi, result));
                } catch (java.lang.reflect.InvocationTargetException | IllegalAccessException error) {
                    Log.e(TAG, "Hook callback failed: " + hook.hook().modId(), error);
                }
                if (result.isCancelled()) break;
            }
            return result;
        } finally {
            active.get().remove(address);
        }
    }

    public static void dispatchTailUniversal(long address, long self, long[] values, String abi) {
        List<HookResolver.ResolvedHook> chain = chains.get(address);
        if (chain == null || !active.get().add(address)) return;
        try {
            for (HookResolver.ResolvedHook hook : chain) {
                if (hook.hook().at() != At.TAIL) continue;
                try {
                    hook.hook().callback().invoke(hook.hook().mixin(),
                            universalParams(hook, self, values, abi, null));
                } catch (java.lang.reflect.InvocationTargetException | IllegalAccessException error) {
                    Log.e(TAG, "Hook callback failed: " + hook.hook().modId(), error);
                }
            }
        } finally {
            active.get().remove(address);
        }
    }

    private static Object[] universalParams(HookResolver.ResolvedHook hook, long self,
                                            long[] values, String abi, CallbackInfoReturnable<Object> result) {
        boolean instance = hook.method().signature().contains("(self");
        Class<?>[] types = hook.hook().callback().getParameterTypes();
        boolean raw = false, wantsCi = false;
        for (Class<?> type : types) {
            if (type == dev.frostbite.api.Arguments.class) raw = true;
            if (type == dev.frostbite.api.CallbackInfo.class || type == CallbackInfoReturnable.class) wantsCi = true;
        }
        java.util.List<Object> params = new java.util.ArrayList<>();
        if (instance) params.add(self == 0 ? null : new Il2CppObject(self));
        if (raw) params.add(new dev.frostbite.api.Arguments(values, abi));
        else for (int i = 0; i < values.length; i++) params.add(marshalArgument(abi.charAt(i), values[i], abi));
        if (wantsCi && result != null) params.add(result);   // void cancel or non-void return override
        return params.toArray();
    }

    /** Static method hooks: no receiver, so arguments are the declared parameters only. */
    public static CallbackInfoReturnable<Object> dispatchHeadStatic(long address, long argument0, long argument1) {
        return dispatchHead(address, callbackArgumentsStatic(address, argument0, argument1));
    }

    public static void dispatchTailStatic(long address, long argument0, long argument1) {
        dispatchTail(address, callbackArgumentsStatic(address, argument0, argument1));
    }

    private static CallbackInfoReturnable<Object> dispatchHead(long address, Object[] arguments) {
        List<HookResolver.ResolvedHook> chain = chains.get(address);
        if (chain == null || arguments == null || !active.get().add(address)) return null;
        try {
            CallbackInfoReturnable<Object> result = new CallbackInfoReturnable<>();
            for (HookResolver.ResolvedHook hook : chain) {
                if (hook.hook().at() != At.HEAD) continue;
                try {
                    if (hook.method().returnType().equals("void"))
                        hook.hook().callback().invoke(hook.hook().mixin(), arguments);
                    else {
                        Object[] withReturn = java.util.Arrays.copyOf(arguments, arguments.length + 1);
                        withReturn[arguments.length] = result;
                        hook.hook().callback().invoke(hook.hook().mixin(), withReturn);
                    }
                } catch (InvocationTargetException | IllegalAccessException error) {
                    Log.e(TAG, "Hook callback failed: " + hook.hook().modId(), error);
                }
                if (result.isCancelled()) break;
            }
            return result;
        } finally {
            active.get().remove(address);
        }
    }

    private static void dispatchTail(long address, Object[] arguments) {
        List<HookResolver.ResolvedHook> chain = chains.get(address);
        if (chain == null || arguments == null || !active.get().add(address)) return;
        try {
            for (HookResolver.ResolvedHook hook : chain) {
                if (hook.hook().at() != At.TAIL) continue;
                try {
                    hook.hook().callback().invoke(hook.hook().mixin(), arguments);
                } catch (InvocationTargetException | IllegalAccessException error) {
                    Log.e(TAG, "Hook callback failed: " + hook.hook().modId(), error);
                }
            }
        } finally {
            active.get().remove(address);
        }
    }

    private static Object[] callbackArguments(long address, long receiver, long argument0, long argument1) {
        List<HookResolver.ResolvedHook> chain = chains.get(address);
        if (chain == null) return null;
        String abi = chain.get(0).method().abi();
        Object[] values = new Object[1 + abi.length() - 2];
        values[0] = new Il2CppObject(receiver);
        long[] raw = {argument0, argument1};
        for (int i = 2; i < abi.length(); i++)
            values[i - 1] = marshalArgument(abi.charAt(i), raw[i - 2], abi);
        return values;
    }

    private static Object[] callbackArgumentsStatic(long address, long argument0, long argument1) {
        List<HookResolver.ResolvedHook> chain = chains.get(address);
        if (chain == null) return null;
        String abi = chain.get(0).method().abi();
        Object[] values = new Object[abi.length() - 2];
        long[] raw = {argument0, argument1};
        for (int i = 2; i < abi.length(); i++)
            values[i - 2] = marshalArgument(abi.charAt(i), raw[i - 2], abi);
        return values;
    }

    private static Object marshalArgument(char kind, long value, String abi) {
        return switch (kind) {
            case 'I' -> (int) value;
            case 'J' -> value;
            case 'F' -> Float.intBitsToFloat((int) value);
            case 'D' -> Double.longBitsToDouble(value);
            case 'Z' -> value != 0;
            case 'P', 'S' -> value == 0 ? null : new Il2CppObject(value);
            default -> throw new IllegalArgumentException("Unsupported callback ABI: " + abi);
        };
    }
}
