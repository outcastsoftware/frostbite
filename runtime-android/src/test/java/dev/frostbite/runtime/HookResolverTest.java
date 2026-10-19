package dev.frostbite.runtime;

import dev.frostbite.api.At;
import dev.frostbite.api.CallbackInfoReturnable;
import dev.frostbite.api.Il2CppObject;
import dev.frostbite.api.MethodSelector;
import dev.frostbite.api.SelectorMode;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class HookResolverTest {
    public static final class Callbacks {
        Il2CppObject seenTarget;
        boolean called;
        public void objectHead(Object self, Il2CppObject target) { }
        public void objectTail(Object self, Object target) { }
        public void wrongPrimitive(Object self, int target) { }
        public void objectReturn(Object self, Il2CppObject target,
                                 CallbackInfoReturnable<Il2CppObject> result) {
            seenTarget = target;
            called = true;
            result.setReturnValue(target);
        }
    }

    private static final String SIGNATURE = "SetTarget(self;Game.Target)";

    private HookDeclaration hook(String callback, At at, Class<?>... parameters) throws Exception {
        Method method = Callbacks.class.getMethod(callback, parameters);
        return new HookDeclaration("test", "Assembly-CSharp", "Game", "Actor",
                new MethodSelector(SIGNATURE, SelectorMode.EXACT), at, 0,
                new Callbacks(), method);
    }

    private ResolvedMethod method(String returnType, String abi) {
        return new ResolvedMethod("Assembly-CSharp", "Game", "Actor", SIGNATURE,
                returnType, abi, 1234L);
    }

    @Test public void acceptsReferenceParameterAtHeadAndTail() throws Exception {
        List<HookResolver.ResolvedHook> hooks = HookResolver.resolve(List.of(
                hook("objectHead", At.HEAD, Object.class, Il2CppObject.class),
                hook("objectTail", At.TAIL, Object.class, Object.class)),
                List.of(method("void", "V:P")));
        assertEquals(2, hooks.size());
    }

    @Test public void acceptsReferenceReturnAtHead() throws Exception {
        assertEquals(1, HookResolver.resolve(List.of(hook("objectReturn", At.HEAD,
                Object.class, Il2CppObject.class, CallbackInfoReturnable.class)),
                List.of(method("Game.Target", "P:P"))).size());
    }

    @Test public void rejectsPrimitiveCallbackForReferenceParameter() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> HookResolver.resolve(List.of(
                hook("wrongPrimitive", At.HEAD, Object.class, int.class)),
                List.of(method("void", "V:P"))));
    }

    @Test public void rejectsNonVoidTail() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> HookResolver.resolve(List.of(
                hook("objectTail", At.TAIL, Object.class, Object.class)),
                List.of(method("Game.Target", "P:P"))));
    }

    @Test public void dispatchesNullableReferenceAndEarlyReturn() throws Exception {
        Callbacks callbacks = new Callbacks();
        Method callback = Callbacks.class.getMethod("objectReturn", Object.class,
                Il2CppObject.class, CallbackInfoReturnable.class);
        HookDeclaration declaration = new HookDeclaration("test", "Assembly-CSharp", "Game", "Actor",
                new MethodSelector(SIGNATURE, SelectorMode.EXACT), At.HEAD, 0, callbacks, callback);
        CallbackDispatcher.register(HookResolver.resolve(List.of(declaration),
                List.of(method("Game.Target", "P:P"))));
        CallbackInfoReturnable<Object> result = CallbackDispatcher.dispatchHead(1234L, 1L, 0L, 0L);
        assertTrue(callbacks.called);
        assertNull(callbacks.seenTarget);
        assertTrue(result.isCancelled());
        assertNull(result.getReturnValue());

        callbacks.called = false;
        result = CallbackDispatcher.dispatchHead(1234L, 1L, 456L, 0L);
        assertTrue(callbacks.called);
        assertEquals(456L, callbacks.seenTarget.nativeAddress());
        assertEquals(456L, ((Il2CppObject) result.getReturnValue()).nativeAddress());
    }
}
