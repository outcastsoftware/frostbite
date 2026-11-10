package dev.frostbite.sample;

import android.util.Log;
import dev.frostbite.api.AccessField;
import dev.frostbite.api.Arguments;
import dev.frostbite.api.AccessMethod;
import dev.frostbite.api.AccessMode;
import dev.frostbite.api.AccessStaticField;
import dev.frostbite.api.At;
import dev.frostbite.api.CallbackInfo;
import dev.frostbite.api.CallbackInfoReturnable;
import dev.frostbite.api.Il2Cpp;
import dev.frostbite.api.Il2CppObject;
import dev.frostbite.api.Inject;
import dev.frostbite.api.Mixin;
import dev.frostbite.api.Mixins;

/**
 * Subway Tools: a working cheat suite for {@code SYBO.RunnerCore.Character.CharacterMotor}
 * in Subway Surfers 3.70.3, testable in a live run.
 *
 * <ul>
 *   <li><b>God mode</b> — the character's collider is disabled and the fall check is
 *       forced to report no fall, so trains, barriers, and pits never end the run.</li>
 *   <li><b>Infinite jump</b> — jump is always allowed, even mid-air.</li>
 *   <li><b>No boundary stumble</b> — boundary impacts are disabled.</li>
 *   <li><b>Max acceleration</b> — the motor is kept accelerating every frame.</li>
 *   <li><b>Telemetry</b> — speed, jump count, and grounded state are logged.</li>
 * </ul>
 *
 * Accessors are declared here with no body; Frostbite generates them. Filter logcat for
 * {@code FrostbiteSample} to watch it.
 */
@Mixin(assembly = "Assembly-CSharp", namespace = "SYBO.RunnerCore.Character", targetClass = "CharacterMotor")
public abstract class CharacterMotorMixin {
    private static final String TAG = "FrostbiteSample";
    private long frames;

    // Fields on the boxed Vector3 (a value type) returned by worldPos().
    private static final Il2Cpp.Target VEC3_X = new Il2Cpp.Target("UnityEngine.CoreModule", "UnityEngine", "Vector3", "x");
    private static final Il2Cpp.Target VEC3_Y = new Il2Cpp.Target("UnityEngine.CoreModule", "UnityEngine", "Vector3", "y");
    private static final Il2Cpp.Target VEC3_Z = new Il2Cpp.Target("UnityEngine.CoreModule", "UnityEngine", "Vector3", "z");

    // ---- accessors used by the cheats and the telemetry ----
    @AccessMethod("SetAccelerate(self;boolean)")
    public abstract void setAccelerate(Object motor, boolean on);

    @AccessMethod("set_DisableBoundaryImpact(self;boolean)")
    public abstract void setDisableBoundaryImpact(Object motor, boolean on);

    @AccessMethod("set_ColliderEnable(self;boolean)")
    public abstract void setColliderEnable(Object motor, boolean on);

    @AccessMethod("get_ForwardSpeedTarget(self)")
    public abstract float speed(Object motor);

    @AccessMethod("get_JumpCount(self)")
    public abstract int jumpCount(Object motor);

    @AccessMethod("get_Grounded(self)")
    public abstract boolean grounded(Object motor);

    @AccessField(value = "_canAccelerate", mode = AccessMode.GETTER)
    public abstract boolean canAccelerate(Object motor);

    @AccessField(value = "_canAccelerate", mode = AccessMode.SETTER)
    public abstract void setCanAccelerate(Object motor, boolean value);

    @AccessStaticField(value = "LaneChangeThreshold", mode = AccessMode.GETTER)
    public abstract float laneChangeThreshold();

    // Enum return via invoke: SurfaceKind comes back as its underlying int.
    @AccessMethod("get_LastTouchedSurface(self)")
    public abstract int lastSurface(Object motor);

    // Struct (value type) return and argument: Vector3 is boxed on the way out and
    // unboxed on the way back in.
    @AccessMethod("get_WorldPlacerPosition(self)")
    public abstract Il2CppObject worldPos(Object motor);

    @AccessMethod("Teleport(self;UnityEngine.Vector3)")
    public abstract void teleport(Object motor, Il2CppObject position);

    // Struct-return override target (queried by the game only on impacts; god mode avoids those).
    @AccessMethod("get_LastImpactVelocity(self)")
    public abstract Il2CppObject lastImpactVelocity(Object motor);

    // ---- cheats applied every frame ----
    @Inject(method = "UpdateMovement(self)", at = At.HEAD)
    public void applyCheats(Object self) {
        setCanAccelerate(self, true);
        setAccelerate(self, true);
        setDisableBoundaryImpact(self, true);
        setColliderEnable(self, false);   // god mode: no obstacle collisions
        if (++frames % 120 == 1) {
            // Invoke a hooked static method on another mixin: the override makes it true.
            boolean poolWorking = Mixins.get(PoolManagerMixin.class).working();

            // Struct return + struct field reads + struct-argument round-trip (Vector3).
            Il2CppObject pos = worldPos(self);
            float px = Il2Cpp.getFloat(pos, VEC3_X), py = Il2Cpp.getFloat(pos, VEC3_Y), pz = Il2Cpp.getFloat(pos, VEC3_Z);
            teleport(self, pos);   // pass the struct back in; net no-op, proves unboxing

            // Struct-RETURN override: get_LastImpactVelocity is forced to Vector3(1,1,1).
            Il2CppObject iv = lastImpactVelocity(self);
            Log.i(TAG, "[SubwayTools] struct-return override LastImpactVelocity=("
                    + Il2Cpp.getFloat(iv, VEC3_X) + "," + Il2Cpp.getFloat(iv, VEC3_Y) + "," + Il2Cpp.getFloat(iv, VEC3_Z) + ")");

            Log.i(TAG, "[SubwayTools] speed=" + speed(self) + " jumpCount=" + jumpCount(self)
                    + " grounded=" + grounded(self) + " canAccelerate=" + canAccelerate(self)
                    + " surface=" + lastSurface(self) + " poolWorking(static)=" + poolWorking
                    + " pos=(" + px + "," + py + "," + pz + ")"
                    + " (godMode on, laneThreshold=" + laneChangeThreshold() + ")");
        }
    }

    // ---- god mode: never register a fall (pits/gaps) ----
    @Inject(method = "CheckFalling(self)", at = At.HEAD)
    public void noFalling(Object self, CallbackInfoReturnable<Boolean> callback) {
        callback.setReturnValue(Boolean.FALSE);
    }

    // ---- infinite jump ----
    @Inject(method = "get_CanJump(self)", at = At.HEAD)
    public void alwaysCanJump(Object self, CallbackInfoReturnable<Boolean> callback) {
        callback.setReturnValue(Boolean.TRUE);
    }

    // ---- enum hook argument: MotorStateKind arrives as its underlying int ----
    @Inject(method = "SwitchState(self;SYBO.RunnerCore.Character.MotorStateKind)", at = At.HEAD)
    public void onSwitchState(Object self, int stateKind) {
        Log.i(TAG, "[SubwayTools] SwitchState -> MotorStateKind " + stateKind);
    }

    // ---- universal trampoline: a hook taking a value-type (Vector3) argument, which the
    // fixed trampolines cannot pass. The struct arrives as a boxed object. ----
    private long surfaceTicks;

    @Inject(method = "AdjustVelocityToSurface(self;UnityEngine.Vector3)", at = At.HEAD)
    public void onSurfaceVelocity(Object self, Object velocity) {
        if (++surfaceTicks % 120 != 1) return;
        Il2CppObject v = (Il2CppObject) velocity;
        Log.i(TAG, "[SubwayTools] UNIVERSAL hook AdjustVelocityToSurface vel=("
                + Il2Cpp.getFloat(v, VEC3_X) + "," + Il2Cpp.getFloat(v, VEC3_Y) + "," + Il2Cpp.getFloat(v, VEC3_Z) + ")");
    }

    // ---- argument write-back: force every lane change to the right (+1), whatever was
    // requested. The mutable Arguments view rewrites the argument before the original runs. ----
    @Inject(method = "ChangeLane(self;int)", at = At.HEAD)
    public void forceRightLane(Object self, Arguments args, CallbackInfoReturnable<Boolean> callback) {
        int requested = args.getInt(0);
        args.setInt(0, 1);
        Log.i(TAG, "[SubwayTools] ChangeLane write-back: " + requested + " -> 1 (always right)");
    }

    // ---- struct-RETURN override: force LastImpactVelocity to Vector3(1,1,1) ----
    @Inject(method = "get_LastImpactVelocity(self)", at = At.HEAD)
    public void overrideImpactVelocity(Object self, CallbackInfoReturnable<Object> callback) {
        callback.setReturnValue(Mixins.get(VectorMixin.class).one());
    }

    // ---- NOP a void method by name: cancel ApplyGravity so the character never falls ----
    private long gravityTicks;

    @Inject(method = "ApplyGravity(self)", at = At.HEAD)
    public void noGravity(Object self, CallbackInfo ci) {
        ci.cancel();   // the original ApplyGravity never runs
        if (++gravityTicks % 300 == 1)
            Log.i(TAG, "[SubwayTools] NOP ApplyGravity (no gravity) #" + gravityTicks);
    }
}
