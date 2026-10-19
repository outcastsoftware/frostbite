package dev.frostbite.runtime;

import android.util.Log;
import dev.frostbite.api.At;
import dev.frostbite.api.CallbackInfoReturnable;
import dev.frostbite.api.Il2CppObject;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Called by native trampolines. One address has one ordered callback chain. */
public final class CallbackDispatcher {
    private static final String TAG = "Frostbite";
    private static volatile Map<Long, List<HookResolver.ResolvedHook>> chains = Map.of();
    private static final ThreadLocal<Set<Long>> active = ThreadLocal.withInitial(HashSet::new);

    private CallbackDispatcher() {}

    static synchronized void register(List<HookResolver.ResolvedHook> resolved) {
        Map<Long, List<HookResolver.ResolvedHook>> grouped = new HashMap<>();
        for (HookResolver.ResolvedHook hook : resolved)
            grouped.computeIfAbsent(hook.method().address(), ignored -> new java.util.ArrayList<>()).add(hook);
        grouped.replaceAll((ignored, value) -> List.copyOf(value));
        chains = Map.copyOf(grouped);
    }

    public static CallbackInfoReturnable<Object> dispatchHead(long address, long receiver,
                                                               long argument0, long argument1) {
        List<HookResolver.ResolvedHook> chain = chains.get(address);
        if (chain == null || !active.get().add(address)) return null;
        try {
            CallbackInfoReturnable<Object> result = new CallbackInfoReturnable<>();
            Object[] arguments = callbackArguments(chain.get(0).method(), receiver, argument0, argument1);
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

    public static void dispatchTail(long address, long receiver, long argument0, long argument1) {
        List<HookResolver.ResolvedHook> chain = chains.get(address);
        if (chain == null || !active.get().add(address)) return;
        try {
            Object[] arguments = callbackArguments(chain.get(0).method(), receiver, argument0, argument1);
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

    private static Object[] callbackArguments(ResolvedMethod method, long receiver,
                                              long argument0, long argument1) {
        String abi = method.abi();
        Object[] values = new Object[1 + abi.length() - 2];
        values[0] = new Il2CppObject(receiver);
        long[] raw = {argument0, argument1};
        for (int i = 2; i < abi.length(); i++) {
            long value = raw[i - 2];
            values[i - 1] = switch (abi.charAt(i)) {
                case 'I' -> (int) value;
                case 'Z' -> value != 0;
                case 'P' -> value == 0 ? null : new Il2CppObject(value);
                default -> throw new IllegalArgumentException("Unsupported callback ABI: " + abi);
            };
        }
        return values;
    }
}
